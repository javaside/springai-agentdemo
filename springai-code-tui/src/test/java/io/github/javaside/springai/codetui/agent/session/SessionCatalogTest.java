package io.github.javaside.springai.codetui.agent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

// SessionSummary 是嵌套在 SessionCatalog 里的 record，同包引用嵌套类型也得显式导入
import io.github.javaside.springai.codetui.agent.session.SessionCatalog.SessionSummary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** scan：只扫文件名+mtime（192 个会话文件的目录必须瞬时），不解析内容。 */
class SessionCatalogTest {

    @Test
    void scan_sortsByMtimeDesc_andIgnoresNonJson(@TempDir Path dir) throws IOException {
        Path older = dir.resolve("20260101T000000-aaaaaa.json");
        Path newer = dir.resolve("20260202T000000-bbbbbb.json");
        Files.writeString(older, "{}");
        Files.writeString(newer, "{}");
        Files.writeString(dir.resolve("README.md"), "not a session");   // 非 .json 忽略
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.from(Instant.parse("2026-02-02T00:00:00Z")));

        List<SessionSummary> out = SessionCatalog.scan(dir);

        assertEquals(2, out.size());
        assertEquals("20260202T000000-bbbbbb", out.get(0).id(), "mtime 新的在前");
        assertEquals("20260101T000000-aaaaaa", out.get(1).id());
        assertTrue(out.get(0).sizeBytes() > 0);
        assertEquals(null, out.get(0).digest(), "scan 不填 digest——后台任务（Task 3）才填");
    }

    @Test
    void scan_emptyOrMissingDir_returnsEmpty(@TempDir Path dir) {
        assertEquals(List.of(), SessionCatalog.scan(dir.resolve("nope")));
        assertEquals(List.of(), SessionCatalog.scan(dir));
    }
}
