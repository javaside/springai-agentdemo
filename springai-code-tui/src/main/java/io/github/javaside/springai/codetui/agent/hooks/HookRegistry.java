package io.github.javaside.springai.codetui.agent.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 钩子注册表——<b>活的单实例</b>（spec 定案）：钩子表装配期不可变；项目层信任态 volatile
 * （UI 线程 {@link #trustProjectLayer} 写 / 工具与 reactive 线程 {@link #hooksFor} 读），
 * 信任翻转对装饰链里同一个实例即时生效。
 *
 * <p><b>信任模型</b>：项目层 hooks.json 的 sha256 记在 ~/.codetui/hooks-trust.json（用户空间，
 * 按项目绝对路径）；内容任何变更即失信任。残余风险（agent 自写信任文件）见 spec——已声明接受。
 */
public final class HookRegistry {

    private static final Logger log = LoggerFactory.getLogger(HookRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int REPLY_CAP = 2000;      // Pre stderr / Post stdout 回传上限（spec）
    private static final int PAYLOAD_OUTPUT_CAP = 8 * 1024;   // Post payload 里 toolOutput 截断

    public record PreResult(boolean blocked, String message) {
        static final PreResult ALLOW = new PreResult(false, null);
    }

    record TrustFileDto(Map<String, String> projects) {}

    private final Path root;
    private final List<HookDefinition> hooks;          // 装配期不可变
    private final Path projectFile;
    private final Path trustFile;
    private final String projectRootKey;               // 项目根绝对路径（信任键）
    private final String projectHash;                  // 项目层内容 hash（无文件则 null）
    /** volatile：UI 线程写（trustProjectLayer）、工具/reactive 线程读（hooksFor）。 */
    private volatile boolean projectTrusted;
    /** 面板 mtime 可疑提示用（未信任则 null；t 翻转时由 UI 线程写，volatile 同上）。 */
    private volatile Instant trustedAt;
    private final ExecutorService notifyExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hook-notify");
                t.setDaemon(true);
                return t;
            });

    private HookRegistry(Path root, List<HookDefinition> hooks, Path projectFile,
                         Path trustFile, boolean projectTrusted, Instant trustedAt) {
        this.root = root;
        this.hooks = List.copyOf(hooks);
        this.projectFile = projectFile;
        this.trustFile = trustFile;
        this.projectRootKey = root.toAbsolutePath().toString();
        this.projectHash = sha256OfFile(projectFile);
        this.projectTrusted = projectTrusted;
        this.trustedAt = trustedAt;
    }

    /** 生产入口：标准两层路径 + 用户空间信任文件。 */
    public static HookRegistry load(Path root) {
        return load(root,
                Path.of(System.getProperty("user.home")).resolve(".codetui").resolve("hooks.json"),
                root.resolve(".codetui").resolve("hooks.json"),
                Path.of(System.getProperty("user.home")).resolve(".codetui").resolve("hooks-trust.json"));
    }

    /** 可测入口（不碰 user.home）。 */
    public static HookRegistry load(Path root, Path userFile, Path projectFile, Path trustFile) {
        List<HookDefinition> all = HookConfigLoader.load(userFile, projectFile);
        boolean trusted = false;
        Instant trustedAt = null;
        String currentHash = sha256OfFile(projectFile);
        if (currentHash != null) {
            TrustFileDto dto = readTrust(trustFile);
            String recorded = (dto == null || dto.projects() == null) ? null
                    : dto.projects().get(root.toAbsolutePath().toString());
            if (currentHash.equals(recorded)) {
                trusted = true;
                trustedAt = mtime(projectFile);
            }
        }
        return new HookRegistry(root, all, projectFile, trustFile, trusted, trustedAt);
    }

    // ── 查询 ────────────────────────────────────────────────

    public boolean projectLayerUntrusted() {
        return projectHash != null && !projectTrusted;
    }

    /** 项目层 hooks.json 的 mtime 晚于信任时刻 → ⚠ 可疑（残余风险的廉价提示）；可信或无配置返回空串。 */
    public String projectFileMtimeNotice() {
        if (!projectLayerUntrusted() && projectTrusted && trustedAt != null) {
            Instant mtime = mtime(projectFile);
            if (mtime != null && mtime.isAfter(trustedAt)) {
                return "⚠ 项目层 hooks.json 在信任后被修改过，建议复查（/hooks 查看内容）";
            }
        }
        return "";
    }

    /** 每次调用按当前信任态过滤（信任翻转即时生效的支点）。 */
    public List<HookDefinition> hooksFor(HookEvent event, String toolName) {
        List<HookDefinition> out = new ArrayList<>();
        for (HookDefinition h : hooks) {
            if (h.event() != event) continue;
            if (h.source() == HookSource.PROJECT && !projectTrusted) continue;
            if (h.matchesTool(toolName)) out.add(h);
        }
        return out;
    }

    /** 只读列表口（忽略信任过滤与 matcher）——/hooks 面板用：未信任的项目层钩子也要可见才能决定信任。 */
    public List<HookDefinition> allFor(HookEvent event) {
        List<HookDefinition> out = new ArrayList<>();
        for (HookDefinition h : hooks) {
            if (h.event() == event) out.add(h);
        }
        return out;
    }

    // ── 信任写入 ────────────────────────────────────────────

    /** 面板 t 键：原子写信任文件 + 就地翻转内存态（同一实例即时生效）。 */
    public synchronized void trustProjectLayer() {
        String hash = sha256OfFile(projectFile);
        if (hash == null) return;   // 项目层文件没了：无事可信任
        TrustFileDto dto = readTrust(trustFile);
        Map<String, String> projects = (dto == null || dto.projects() == null)
                ? new LinkedHashMap<>() : new LinkedHashMap<>(dto.projects());
        projects.put(projectRootKey, hash);
        atomicWriteTrust(new TrustFileDto(projects));
        this.projectTrusted = true;
        this.trustedAt = Instant.now();
    }

    // ── 事件入口（Pre/Post 给 ToolEventCallback；Notify 给 Stop/SessionStart）────

    /** PreToolUse：逐个同步跑，首个 exit 2 胜出；非 2 失败 WARN 继续。永不抛。 */
    public PreResult runPre(String toolName, String toolInput, String sessionId, long turnId, String taskId) {
        for (HookDefinition h : hooksFor(HookEvent.PRE_TOOL_USE, toolName)) {
            HookExecutor.HookResult r = HookExecutor.execute(h,
                    payload(h, toolName, toolInput, null, sessionId, turnId, taskId), root);
            if (r.exitCode() == 2) {
                return new PreResult(true, "⛔ PreToolUse 钩子拦截（" + h.command() + "）："
                        + cap(r.stderr()));
            }
            if (r.exitCode() != 0) {
                log.warn("PreToolUse 钩子非阻断失败（{}）：exit={} stderr={}", h.command(), r.exitCode(), cap(r.stderr()));
            }
        }
        return PreResult.ALLOW;
    }

    /** PostToolUse：exit 0 且 stdout 非空者追加（多钩子依次）；失败不追加、经 notice 回执一行（spec）。永不抛。 */
    public String runPost(String toolName, String toolInput, String toolOutput,
                          String sessionId, long turnId, String taskId, Consumer<String> notice) {
        StringBuilder append = null;
        for (HookDefinition h : hooksFor(HookEvent.POST_TOOL_USE, toolName)) {
            HookExecutor.HookResult r = HookExecutor.execute(h,
                    payload(h, toolName, toolInput, toolOutput, sessionId, turnId, taskId), root);
            if (r.exitCode() == 0 && !r.stdout().isBlank()) {
                if (append == null) append = new StringBuilder();
                append.append("\n\n[PostToolUse 钩子输出]\n").append(cap(r.stdout()));
            } else if (r.exitCode() != 0) {
                log.warn("PostToolUse 钩子失败不追加（{}）：exit={} stderr={}", h.command(), r.exitCode(), cap(r.stderr()));
                notice.accept("⚠ PostToolUse 钩子失败（" + h.command() + "）：" + firstLine(r.stderr()));
            }
        }
        return append == null ? null : append.toString();
    }

    /** 通知型同步执行（SessionStart：assemble 里跑，阻塞可接受）。 */
    public void runNotify(HookEvent event, Consumer<String> notice) {
        for (HookDefinition h : hooksFor(event, null)) {
            HookExecutor.HookResult r = HookExecutor.execute(h,
                    payload(h, null, null, null, null, -1L, null), root);
            if (r.exitCode() != 0) {
                notice.accept("⚠ " + event.configKey() + " 钩子失败（" + h.command() + "）："
                        + firstLine(r.stderr()));
            }
        }
    }

    /** 通知型异步执行（Stop：不阻塞 reactive 线程——spec 定案，次序损失已接受）。 */
    public void runNotifyAsync(HookEvent event, Consumer<String> notice) {
        List<HookDefinition> snapshot = hooksFor(event, null);
        if (snapshot.isEmpty()) return;
        // 捕获快照而非 registry 引用：信任态在提交时已判定（异步窗口内翻转不再影响本批）
        for (HookDefinition h : snapshot) {
            notifyExecutor.execute(() -> {
                try {
                    HookExecutor.HookResult r = HookExecutor.execute(h,
                            payload(h, null, null, null, null, -1L, null), root);
                    if (r.exitCode() != 0) {
                        notice.accept("⚠ " + event.configKey() + " 钩子失败（" + h.command() + "）："
                                + firstLine(r.stderr()));
                    }
                } catch (RuntimeException e) {   // 防御：钩子链任何异常不许杀 notify 线程
                    log.warn("Stop 钩子执行异常（{}）：{}", h.command(), e.toString());
                }
            });
        }
    }

    // ── 内部 ────────────────────────────────────────────────

    private String payload(HookDefinition h, String toolName, String toolInput, String toolOutput,
                           String sessionId, Long turnId, String taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", h.event().configKey());
        if (toolName != null) m.put("toolName", toolName);
        // toolInput 同截 8KB：stdin 写入在 waitFor 前同步执行，Post 钩子不读 stdin 时
        // 未截断的大入参（Write/Edit 整文件内容）会塞满管道缓冲挂死工具线程（评审修订）
        if (toolInput != null) m.put("toolInput", capTo(toolInput, PAYLOAD_OUTPUT_CAP));
        if (toolOutput != null) m.put("toolOutput", capTo(toolOutput, PAYLOAD_OUTPUT_CAP));
        m.put("sessionId", sessionId == null ? "" : sessionId);
        m.put("turnId", turnId == null ? -1L : turnId);
        m.put("taskId", taskId == null ? "" : taskId);
        m.put("root", root.toString());
        return MAPPER.writeValueAsString(m);   // Jackson 3 writeValueAsString 不抛受检异常
    }

    private static String cap(String s) { return capTo(s, REPLY_CAP); }

    private static String capTo(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) return "";
        String line = s.strip().split("\n", 2)[0];
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    private static String sha256OfFile(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (Exception e) {
            log.warn("读取 {} 失败：{}", file, e.toString());
            return null;
        }
    }

    private static Instant mtime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static TrustFileDto readTrust(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return MAPPER.readValue(Files.readString(file), TrustFileDto.class);
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("信任文件不可解析 {}：{}", file, e.toString());
            return null;
        }
    }

    private void atomicWriteTrust(TrustFileDto dto) {
        try {
            Files.createDirectories(trustFile.getParent());
            Path tmp = trustFile.resolveSibling("hooks-trust.json.tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(dto));
            try {
                Files.move(tmp, trustFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException atomicUnsupported) {
                Files.move(tmp, trustFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("写信任文件失败 {}：{}", trustFile, e.toString());
        }
    }
}
