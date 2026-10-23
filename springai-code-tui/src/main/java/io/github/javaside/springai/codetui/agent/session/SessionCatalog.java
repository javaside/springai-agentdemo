package io.github.javaside.springai.codetui.agent.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
}
