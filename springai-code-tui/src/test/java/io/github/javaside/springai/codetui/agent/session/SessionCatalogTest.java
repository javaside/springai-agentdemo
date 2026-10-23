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

    @Test
    void scan_skipsDirectoryNamedLikeJson(@TempDir Path dir) throws IOException {
        // 名字以 .json 结尾的子目录不是会话文件：混进列表会让后续摘要任务对一个目录做 JSON 解析
        Files.createDirectory(dir.resolve("20260303T000000-cc.json"));

        assertEquals(List.of(), SessionCatalog.scan(dir));
    }

    // ── firstUserDigest（Jackson 流式，读到首条 USER 即停）────────────────────

    /** 会话文件的最小合法 JSON 形状（与 FileSessionRepository.PersistedSession 对齐的字段子集）。 */
    private static Path sessionFile(Path dir, String name, String json) throws IOException {
        Path f = dir.resolve(name + ".json");
        Files.writeString(f, json);
        return f;
    }

    @Test
    void digest_readsFirstUser_andStopsBeforeLaterCorruption(@TempDir Path dir) throws IOException {
        // 首条 USER 之后的内容是损坏 JSON：若实现全文解析必抛/必吞，读到首条即停则成功返回——「即停」的决定性证据。
        Path f = sessionFile(dir, "s1", "{\"events\":[{\"messageType\":\"USER\",\"content\":\"帮我修 NPE\"},<<<CORRUPT");
        assertEquals("帮我修 NPE", SessionCatalog.firstUserDigest(f));
    }

    @Test
    void digest_stripsInjections_foldsAndTruncates(@TempDir Path dir) throws IOException {
        String longText = "非".repeat(100);   // 100 个 CJK 字符，超 80 截断
        String stored = "<skill_instruction>\nx\n</skill_instruction>\n\n" + longText + "\n第二行";
        Path f = sessionFile(dir, "s2",
                "{\"events\":[{\"messageType\":\"ASSISTANT\",\"content\":\"跳过\"},{\"messageType\":\"USER\",\"content\":\""
                        + stored.replace("\n", "\\n").replace("\"", "\\\"") + "\"}]}");
        String out = SessionCatalog.firstUserDigest(f);
        org.junit.jupiter.api.Assertions.assertNotNull(out);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("skill_instruction"), "剥离技能前缀");
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("\n"), "多行折叠为单行");
        org.junit.jupiter.api.Assertions.assertTrue(out.codePoints().count() <= 80, "截断到 80 字符: " + out);
    }

    @Test
    void digest_noUser_orCorrupt_orMissingFile_returnsNull(@TempDir Path dir) throws IOException {
        assertEquals(null, SessionCatalog.firstUserDigest(
                sessionFile(dir, "s3", "{\"events\":[{\"messageType\":\"ASSISTANT\",\"content\":\"x\"}]}")));
        assertEquals(null, SessionCatalog.firstUserDigest(sessionFile(dir, "s4", "{ 不是 JSON")));
        assertEquals(null, SessionCatalog.firstUserDigest(dir.resolve("nope.json")));
    }
}
