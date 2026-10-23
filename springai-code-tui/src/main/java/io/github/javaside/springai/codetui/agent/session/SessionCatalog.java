package io.github.javaside.springai.codetui.agent.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 会话目录的轻量索引：只扫文件名 + mtime（本机实测 192 个文件，必须瞬时），
 * 内容解析（首条用户消息摘要）是另一条后台路径 {@link #firstUserDigest}（Task 3）。
 */
public final class SessionCatalog {

    private static final Logger log = LoggerFactory.getLogger(SessionCatalog.class);

    private SessionCatalog() {}

    /** 列表项：digest 由后台任务补填（null = 未解析），故提供三参紧凑构造。 */
    public record SessionSummary(String id, long mtimeMillis, long sizeBytes, String digest) {
        public SessionSummary(String id, long mtimeMillis, long sizeBytes) {
            this(id, mtimeMillis, sizeBytes, null);
        }
    }

    /** mtime 降序（最近在前）。目录不存在/空/IO 失败 → 空表，不抛（面板与启动参数共用，都不该被目录问题打断）。 */
    public static List<SessionSummary> scan(Path baseDir) {
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(baseDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(SessionCatalog::toSummary)
                    .sorted(Comparator.comparingLong(SessionSummary::mtimeMillis).reversed())
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            log.warn("扫描会话目录失败 {}：{}", baseDir, e.toString());
            return List.of();
        }
    }

    private static SessionSummary toSummary(Path p) {
        String name = p.getFileName().toString();
        String id = name.substring(0, name.length() - ".json".length());
        try {
            return new SessionSummary(id,
                    Files.getLastModifiedTime(p).toMillis(), Files.size(p));
        } catch (IOException e) {
            return new SessionSummary(id, 0L, 0L);   // mtime/size 拿不到只是排序信息缺失，不值得失败
        }
    }

    // Jackson 3 两个坑（javap 实测 jackson-core 3.1.4）：JsonFactory 在 tools.jackson.core.json 子包
    // （不在 tools.jackson.core）；字段名 token 是 PROPERTY_NAME（Jackson 2 的 FIELD_NAME 已改名）。
    // 且 Jackson 3 异常全为 unchecked（JacksonException extends RuntimeException），catch
    // RuntimeException 即覆盖损坏 JSON——不要 catch IOException（无来源，编译期 unreachable）。
    private static final tools.jackson.core.json.JsonFactory JSON = new tools.jackson.core.json.JsonFactory();
    private static final int DIGEST_MAX = 80;

    /**
     * 流式解析：逐 token 走 events[]，读到首条 USER 的 content 即返回（本机最大会话 773KB，
     * 全文解析在后台线程 × 192 个文件也是白烧 IO——首条 user 几乎总在文件头部）。
     * 返回 null = 无首条 user / 损坏 / 文件不存在（面板统一显示「（无对话）」，见 spec 微调记录）。
     */
    public static String firstUserDigest(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (JsonParser p = JSON.createParser(file.toFile())) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken valueToken = p.nextToken();
                if ("events".equals(field) && valueToken == JsonToken.START_ARRAY) {
                    // 无论找到与否，events 走完/首条命中都到此为止——后面的字段（version 等）不读
                    return firstUserInEvents(p);
                }
                p.skipChildren();   // 其它顶层字段整体跳过
            }
        } catch (RuntimeException e) {
            log.debug("会话摘要解析失败 {}：{}", file, e.toString());
            return null;
        }
        return null;
    }

    /** events 数组内：逐事件对象找 messageType=USER；命中返回折叠截断后的摘要，遍历完无则 null。 */
    private static String firstUserInEvents(JsonParser p) {
        while (p.nextToken() == JsonToken.START_OBJECT) {
            String type = null;
            String content = null;
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String f = p.currentName();
                JsonToken t = p.nextToken();
                if ("messageType".equals(f)) {
                    type = p.getText();
                } else if ("content".equals(f) && t == JsonToken.VALUE_STRING) {
                    content = p.getText();
                } else {
                    p.skipChildren();   // 数组/对象值跳过；标量值 skipChildren 是 no-op
                }
            }
            if ("USER".equals(type) && content != null && !content.isBlank()) {
                String stripped = UserTextStripper.stripAll(content)
                        .replaceAll("\\R+", " ").strip();
                if (stripped.isEmpty()) {
                    return null;   // 剥完只剩注入标记：没有可认的对话内容
                }
                return stripped.codePoints().count() <= DIGEST_MAX
                        ? stripped
                        : new String(stripped.codePoints().limit(DIGEST_MAX)
                                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                                .toString());
            }
        }
        return null;
    }
}
