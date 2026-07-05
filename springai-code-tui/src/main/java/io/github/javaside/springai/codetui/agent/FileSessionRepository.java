package io.github.javaside.springai.codetui.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件版 {@link SessionRepository}：多槽 + 惰性。会话事件持久化到 {@code <baseDir>/<sessionId>.json}，一 session 一文件。
 *
 * <p><b>惰性加载（不启动即读）</b>：构造函数<em>不</em>扫盘。只有真正访问某个 session（读/写其 id）时才按需从磁盘
 * 加载那一个文件进内存 map。于是默认启动（新 session id、无文件）根本不碰盘；只有 {@code -c} 选到的「最近一次」
 * 会话才被读入。恢复与否由启动方（{@code CodeTuiApplication}）决定 session id，仓库本身只负责存取。
 *
 * <p><b>内存/版本语义</b>照抄 {@code InMemorySessionRepository}（{@code SessionData{session,events,version}}，
 * compute 原子化、version 每 append/replace +1、写日志三法缺失会话抛 {@link IllegalArgumentException}、
 * {@code getEventVersion}/{@code findEvents} 缺失返 0/空）。每次变更在 compute 内原子写盘（tmp + move），delete 删文件。
 *
 * <p><b>序列化</b>范式同 {@code JdbcSessionRepository}：事件拆 type/content/messageData(tool_calls 或 tool 结果)/metadata/branch，
 * 用 Jackson 3。加载时用 {@link SessionEvents#cleanPrefixLength} 裁掉悬空 {@code assistant(tool_calls)} 尾巴（防硬中断残留导致 400）。
 * 坏文件记日志跳过；所有 IO/错误只写文件日志（slf4j），绝不 stdout（会撕 TUI）。
 */
public final class FileSessionRepository implements SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(FileSessionRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path baseDir;
    private final Map<String, SessionData> store = new ConcurrentHashMap<>();

    FileSessionRepository(Path baseDir) {
        this.baseDir = baseDir;
    }

    // ── SessionRepository：元数据 ────────────────────────────────────────

    @Override
    public Session save(Session session) {
        store.compute(session.id(), (id, existing) -> {
            SessionData base = (existing != null) ? existing : loadFromDisk(id);   // 惰性：保留磁盘上已有事件/版本
            List<SessionEvent> events = (base != null) ? base.events() : List.of();
            long version = (base != null) ? base.version() : 0L;
            SessionData data = new SessionData(session, events, version);
            persist(data);
            return data;
        });
        return session;
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        SessionData data = store.computeIfAbsent(sessionId, this::loadFromDisk);   // null 不入 map
        return Optional.ofNullable(data).map(SessionData::session);
    }

    @Override
    public List<Session> findByUserId(String userId) {
        // 惰性仓库仅见已加载会话（本地单用户单活动会话，够用）。
        return store.values().stream()
                .filter(d -> userId.equals(d.session().userId()))
                .map(SessionData::session).toList();
    }

    @Override
    public List<String> findExpiredSessionIds(Instant before) {
        return store.values().stream()
                .filter(d -> d.session().expiresAt() != null && d.session().expiresAt().isBefore(before))
                .map(d -> d.session().id()).toList();
    }

    @Override
    public void delete(String sessionId) {
        store.remove(sessionId);
        deleteFile(sessionId);
    }

    // ── SessionRepository：事件日志 ─────────────────────────────────────

    @Override
    public void appendEvent(SessionEvent event) {
        String sessionId = event.getSessionId();
        store.compute(sessionId, (id, existing) -> {
            SessionData base = (existing != null) ? existing : loadFromDisk(id);
            if (base == null) {
                throw new IllegalArgumentException("Session not found: " + sessionId);
            }
            List<SessionEvent> newEvents = new ArrayList<>(base.events());
            newEvents.add(event);
            SessionData data = base.withEvents(newEvents);
            persist(data);
            return data;
        });
    }

    @Override
    public void replaceEvents(String sessionId, List<SessionEvent> events) {
        store.compute(sessionId, (id, existing) -> {
            SessionData base = (existing != null) ? existing : loadFromDisk(id);
            if (base == null) {
                throw new IllegalArgumentException("Session not found: " + sessionId);
            }
            SessionData data = base.withEvents(List.copyOf(events));
            persist(data);
            return data;
        });
    }

    @Override
    public boolean replaceEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
        boolean[] success = { false };
        store.compute(sessionId, (id, existing) -> {
            SessionData base = (existing != null) ? existing : loadFromDisk(id);
            if (base == null) {
                throw new IllegalArgumentException("Session not found: " + sessionId);
            }
            if (base.version() != expectedVersion) {
                return base;               // CAS 未命中：不变，success 保持 false
            }
            success[0] = true;
            SessionData data = base.withEvents(List.copyOf(events));
            persist(data);
            return data;
        });
        return success[0];
    }

    @Override
    public long getEventVersion(String sessionId) {
        SessionData data = store.computeIfAbsent(sessionId, this::loadFromDisk);
        return (data != null) ? data.version() : 0L;
    }

    @Override
    public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
        SessionData data = store.computeIfAbsent(sessionId, this::loadFromDisk);
        if (data == null) {
            return List.of();
        }
        List<SessionEvent> matched = data.events().stream()
                .filter(filter::matches)
                .collect(Collectors.toCollection(ArrayList::new));
        if (filter.lastN() != null && matched.size() > filter.lastN()) {
            matched = matched.subList(matched.size() - filter.lastN(), matched.size());
        }
        if (filter.pageSize() != null) {
            int pageNum = (filter.page() != null) ? filter.page() : 0;
            int size = filter.pageSize();
            int fromIdx = pageNum * size;
            matched = (fromIdx >= matched.size())
                    ? new ArrayList<>()
                    : matched.subList(fromIdx, Math.min(fromIdx + size, matched.size()));
        }
        return List.copyOf(matched);
    }

    /** 元数据 + 事件日志 + 单调 version 捆绑（同 InMemory 的私有模型）。 */
    private record SessionData(Session session, List<SessionEvent> events, long version) {
        SessionData withEvents(List<SessionEvent> newEvents) {
            return new SessionData(this.session, newEvents, this.version + 1);
        }
    }

    // ── 静态：给启动方选「最近一次」会话（-c）──────────────────────────────

    /** 目录下最后修改时间最新的会话文件对应的 sessionId（文件名去掉 .json）；无则空。仅扫文件名/时间，不解析全文。 */
    public static Optional<String> latestSessionId(Path baseDir) {
        if (!Files.isDirectory(baseDir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(baseDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparingLong(FileSessionRepository::lastModifiedMillis))
                    .map(FileSessionRepository::stem);
        } catch (IOException e) {
            log.warn("扫描会话目录失败 {}：{}", baseDir, e.toString());
            return Optional.empty();
        }
    }

    private static long lastModifiedMillis(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String stem(Path p) {
        String name = p.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }

    // ── 惰性加载单个会话 ─────────────────────────────────────────────────

    /** 从磁盘加载一个会话（含加载即裁悬空）；文件不存在或损坏 → 返回 {@code null}（视为不存在）。 */
    private SessionData loadFromDisk(String sessionId) {
        Path file = fileFor(sessionId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            PersistedSession dto = MAPPER.readValue(json, PersistedSession.class);
            Session session = Session.builder()
                    .id(dto.id())
                    .userId(dto.userId())
                    .createdAt(parseInstant(dto.createdAt()))
                    .expiresAt(dto.expiresAt() == null ? null : parseInstant(dto.expiresAt()))
                    .metadata(dto.metadata() == null ? Map.of() : dto.metadata())
                    .build();
            List<SessionEvent> events = new ArrayList<>();
            for (PersistedEvent pe : nullToEmpty(dto.events())) {
                events.add(toEvent(pe));
            }
            // 加载即净化成 API 合法序列：裁掉尾部悬空 tool_calls，并丢掉孤儿 tool 结果（否则恢复会话首个请求 400）。
            // 内存态即可，下次变更自然回写。
            events = SessionEvents.sanitize(events);
            return new SessionData(session, List.copyOf(events), dto.version());
        } catch (RuntimeException | IOException e) {
            log.warn("跳过损坏的会话文件 {}：{}", file, e.toString());
            return null;   // 坏文件视为不存在，不崩
        }
    }

    // ── 持久化 ──────────────────────────────────────────────────────────

    /** 把一个会话原子写盘（临时文件 + rename）。IO 失败尽力而为：记日志并吞掉，不中断回合、不写 stdout。 */
    private void persist(SessionData data) {
        try {
            Files.createDirectories(baseDir);
            String json = MAPPER.writeValueAsString(toDto(data));
            Path file = fileFor(data.session().id());
            Path tmp = baseDir.resolve(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (RuntimeException | IOException e) {
            log.warn("持久化会话 {} 失败：{}", data.session().id(), e.toString());
        }
    }

    private void deleteFile(String sessionId) {
        try {
            Files.deleteIfExists(fileFor(sessionId));
        } catch (IOException e) {
            log.warn("删除会话文件 {} 失败：{}", sessionId, e.toString());
        }
    }

    private Path fileFor(String sessionId) {
        String safe = sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return baseDir.resolve(safe + ".json");
    }

    // ── DTO ⇄ 领域对象 ─────────────────────────────────────────────────

    private static PersistedSession toDto(SessionData data) {
        Session s = data.session();
        List<PersistedEvent> events = data.events().stream().map(FileSessionRepository::toDto).toList();
        return new PersistedSession(s.id(), s.userId(),
                s.createdAt().toString(),
                s.expiresAt() == null ? null : s.expiresAt().toString(),
                s.metadata(), data.version(), events);
    }

    private static PersistedEvent toDto(SessionEvent e) {
        Message m = e.getMessage();
        List<ToolCallDto> toolCalls = null;
        List<ToolResponseDto> toolResponses = null;
        if (m instanceof AssistantMessage am && am.hasToolCalls()) {
            toolCalls = am.getToolCalls().stream()
                    .map(tc -> new ToolCallDto(tc.id(), tc.type(), tc.name(), tc.arguments())).toList();
        } else if (m instanceof ToolResponseMessage trm) {
            toolResponses = trm.getResponses().stream()
                    .map(tr -> new ToolResponseDto(tr.id(), tr.name(), tr.responseData())).toList();
        }
        return new PersistedEvent(e.getId(), e.getSessionId(), e.getTimestamp().toString(),
                m.getMessageType().name(), m.getText(), toolCalls, toolResponses, e.getMetadata(), e.getBranch());
    }

    private static SessionEvent toEvent(PersistedEvent pe) {
        SessionEvent.Builder b = SessionEvent.builder()
                .id(pe.id())
                .sessionId(pe.sessionId())
                .timestamp(parseInstant(pe.timestamp()))
                .message(toMessage(pe))
                .branch(pe.branch());
        if (pe.metadata() != null) {
            b.metadata(pe.metadata());
        }
        return b.build();
    }

    /** 按 messageType 重建消息（同 JdbcSessionRepository.toMessage）。 */
    private static Message toMessage(PersistedEvent pe) {
        MessageType type = MessageType.valueOf(pe.messageType());
        String content = pe.content() == null ? "" : pe.content();
        return switch (type) {
            case USER -> new UserMessage(content);
            case SYSTEM -> new SystemMessage(content);
            case ASSISTANT -> {
                List<ToolCallDto> tcs = pe.toolCalls();
                if (tcs == null || tcs.isEmpty()) {
                    yield new AssistantMessage(content);
                }
                List<AssistantMessage.ToolCall> calls = tcs.stream()
                        .map(d -> new AssistantMessage.ToolCall(d.id(), d.type(), d.name(), d.arguments())).toList();
                yield AssistantMessage.builder().content(content).toolCalls(calls).build();
            }
            case TOOL -> {
                List<ToolResponseDto> trs = nullToEmpty(pe.toolResponses());
                List<ToolResponseMessage.ToolResponse> responses = trs.stream()
                        .map(d -> new ToolResponseMessage.ToolResponse(d.id(), d.name(), d.responseData())).toList();
                yield ToolResponseMessage.builder().responses(responses).build();
            }
        };
    }

    private static Instant parseInstant(String iso) {
        return (iso == null || iso.isBlank()) ? Instant.EPOCH : Instant.parse(iso);
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    // ── 持久化 DTO（Jackson 3 记录）───────────────────────────────────────

    record PersistedSession(String id, String userId, String createdAt, String expiresAt,
                            Map<String, Object> metadata, long version, List<PersistedEvent> events) {}

    record PersistedEvent(String id, String sessionId, String timestamp, String messageType, String content,
                          List<ToolCallDto> toolCalls, List<ToolResponseDto> toolResponses,
                          Map<String, Object> metadata, String branch) {}

    record ToolCallDto(String id, String type, String name, String arguments) {}

    record ToolResponseDto(String id, String name, String responseData) {}
}
