package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionConfigWriterTest {

    private static PermissionRule allow(String dsl) {
        return PermissionRule.parse(dsl, PermissionBehavior.ALLOW, RuleScope.PROJECT);
    }

    @Test
    @DisplayName("文件不存在时自动建目录与文件")
    void createsFileWhenMissing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".codetui").resolve("permissions.json");

        assertTrue(PermissionConfigWriter.append(file, allow("Bash(mvn test:*)")));

        assertTrue(Files.isRegularFile(file));
        String json = Files.readString(file);
        assertTrue(json.contains("Bash(mvn test:*)"), "实际内容：" + json);
        assertTrue(json.contains("\"allow\""), "实际内容：" + json);
    }

    @Test
    @DisplayName("追加到已有文件时，保留未知字段与其它规则")
    void preservesExistingContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        Files.writeString(file, """
                {"defaultMode":"ACCEPT_EDITS",
                 "someFutureField":{"keep":true},
                 "allow":["Read(*)"],
                 "deny":["Bash(rm -rf /:*)"]}""");

        assertTrue(PermissionConfigWriter.append(file, allow("Write(src/**)")));

        String json = Files.readString(file);
        assertTrue(json.contains("someFutureField"), "未知字段必须原样保留：" + json);
        assertTrue(json.contains("Read(*)"));
        assertTrue(json.contains("Write(src/**)"));
        assertTrue(json.contains("rm -rf /"));
        assertTrue(json.contains("ACCEPT_EDITS"));
    }

    @Test
    @DisplayName("重复规则不重复写入（幂等）")
    void deduplicates(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        PermissionConfigWriter.append(file, allow("Read(*)"));
        PermissionConfigWriter.append(file, allow("Read(*)"));

        PermissionConfig cfg = PermissionConfigLoader.load(file, dir.resolve("none.json"));
        assertEquals(1, cfg.rules().size(), "同一条规则只应存在一份");
    }

    @Test
    @DisplayName("ask / deny 各写进各自的数组")
    void writesToCorrectArray(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        PermissionConfigWriter.append(file,
                PermissionRule.parse("Bash(git push:*)", PermissionBehavior.ASK, RuleScope.PROJECT));
        PermissionConfigWriter.append(file,
                PermissionRule.parse("Write(/etc/**)", PermissionBehavior.DENY, RuleScope.PROJECT));

        PermissionConfig cfg = PermissionConfigLoader.load(file, dir.resolve("none.json"));
        assertTrue(cfg.rules().stream()
                .anyMatch(r -> r.behavior() == PermissionBehavior.ASK && r.toDsl().equals("Bash(git push:*)")));
        assertTrue(cfg.rules().stream()
                .anyMatch(r -> r.behavior() == PermissionBehavior.DENY && r.toDsl().equals("Write(/etc/**)")));
    }

    @Test
    @DisplayName("降级契约：既有文件 JSON 非法 → 返回 false，不抛异常，不破坏原文件")
    void malformedExistingFileDegrades(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        Files.writeString(file, "{ broken json");

        assertFalse(PermissionConfigWriter.append(file, allow("Read(*)")));
        assertEquals("{ broken json", Files.readString(file), "失败时不得改动原文件");
    }

    // ---- 以下为计划之外补的边界，均为「宁可不写，也不悄悄放宽/丢内容」 ----

    @Test
    @DisplayName("回写后能被 PermissionConfigLoader 原样读回（写-读闭环）")
    void roundTripsThroughLoader(@TempDir Path dir) throws Exception {
        Path file = PermissionConfigLoader.projectFile(dir);   // <root>/.codetui/permissions.json

        assertTrue(PermissionConfigWriter.append(file, allow("Bash(mvn test:*)")));
        assertTrue(PermissionConfigWriter.append(file, allow("Write(src/**)")));

        PermissionConfig cfg = PermissionConfigLoader.load(dir.resolve("no-user.json"), file);
        assertEquals(2, cfg.rules().size(), "实际：" + cfg.rules());
        PermissionRule bash = cfg.rules().stream()
                .filter(r -> "Bash".equals(r.toolName())).findFirst().orElseThrow();
        assertEquals("mvn test:*", bash.pattern());
        assertEquals(PermissionBehavior.ALLOW, bash.behavior());
        assertEquals(RuleScope.PROJECT, bash.scope());
        // 规则必须真的能命中它当初被批准的那次调用
        assertTrue(bash.matches("Bash", "mvn test -pl x", false, dir));
    }

    @Test
    @DisplayName("既有 allow 字段不是数组 → 返回 false，不顶掉用户写的内容")
    void nonArrayFieldDegrades(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        Files.writeString(file, "{\"allow\":\"Read(*)\"}");

        assertFalse(PermissionConfigWriter.append(file, allow("Write(src/**)")));
        assertEquals("{\"allow\":\"Read(*)\"}", Files.readString(file), "失败时不得改动原文件");
    }

    @Test
    @DisplayName("既有文件为空白 → 当空对象处理，正常写入")
    void blankExistingFileIsTreatedAsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        Files.writeString(file, "  \n");

        assertTrue(PermissionConfigWriter.append(file, allow("Read(*)")));
        PermissionConfig cfg = PermissionConfigLoader.load(dir.resolve("none.json"), file);
        assertEquals(1, cfg.rules().size());
    }

    @Test
    @DisplayName("落盘后会被解析成另一条规则的 DSL 一律拒写（防授权悄悄变宽）")
    void rejectsRulesThatDoNotRoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");

        // 字面模式 "*"：作为 glob 只匹配单段相对路径，写成 DSL 却是「该工具全部调用」
        assertFalse(PermissionConfigWriter.append(file,
                new PermissionRule("Read", "*", PermissionBehavior.ALLOW, RuleScope.PROJECT)));
        // 模式带前导空格：parse 会 trim 掉，读回来不是同一条
        assertFalse(PermissionConfigWriter.append(file,
                new PermissionRule("Write", " /etc/**", PermissionBehavior.DENY, RuleScope.PROJECT)));

        assertFalse(Files.exists(file), "拒写时不该留下任何文件");
    }

    @Test
    @DisplayName("并行审批同时回写：读-改-写不丢更新")
    void concurrentAppendsDoNotLoseUpdates(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        int n = 8;
        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(n);
        var ok = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < n; i++) {
            String dsl = "Read(f" + i + ".txt)";
            new Thread(() -> {
                try {
                    start.await();
                    if (PermissionConfigWriter.append(file, allow(dsl))) {
                        ok.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "并发回写超时");

        assertEquals(n, ok.get(), "每次 append 都该报成功");
        PermissionConfig cfg = PermissionConfigLoader.load(dir.resolve("none.json"), file);
        assertEquals(n, cfg.rules().size(),
                "报成功就必须真在文件里，实际：" + cfg.rules());
    }
}
