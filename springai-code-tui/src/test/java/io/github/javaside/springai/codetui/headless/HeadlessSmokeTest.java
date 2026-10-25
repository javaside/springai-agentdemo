package io.github.javaside.springai.codetui.headless;

import io.github.javaside.springai.codetui.agent.session.SessionCatalog;
import io.github.javaside.springai.codetui.agent.session.SessionIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * headless 全链路真机冒烟（DEEPSEEK_API_KEY 门控，同 CodingAgentSpikeTest 纪律）。
 * 直调 HeadlessRunner.run——run 只返回退出码不 System.exit（exit 在 main），同 JVM 可测；
 * stdout 经 System.setOut 捕获（run 内唯一 stdout 写点）。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class HeadlessSmokeTest {

    @Test
    @Timeout(180)
    void oneShot_replyOnStdout_sessionPersisted(@TempDir Path root) throws Exception {
        PrintStream oldOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = HeadlessRunner.run(new String[]{"-p", "只回复四个字：收到请讲"}, root, SessionIds.newId(), false);
        } finally {
            System.setOut(oldOut);
        }

        assertEquals(0, code, "正常回合退出码 0");
        String out = buf.toString(StandardCharsets.UTF_8).strip();
        assertFalse(out.isBlank(), "stdout 有最终回复");
        assertTrue(out.contains("收到"), "回复内容含约定暗号，实际：" + out);
        List<SessionCatalog.SessionSummary> sessions =
                SessionCatalog.scan(root.resolve(".codetui").resolve("sessions"));
        assertEquals(1, sessions.size(), "会话文件已落盘（spec：持久化+可恢复）");
    }

    @Test
    @Timeout(240)
    void resumeChainedSecondStep_carriesContext(@TempDir Path root) throws Exception {
        String firstId = SessionIds.newId();
        assertEquals(0, HeadlessRunner.run(new String[]{"-p", "请记住暗号：蓝鲸。只回复：好的"},
                root, firstId, false));
        String sessionId = SessionCatalog.scan(root.resolve(".codetui").resolve("sessions"))
                .get(0).id();

        PrintStream oldOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = HeadlessRunner.run(new String[]{"-p", "我们约定的暗号是什么？只回复暗号本身"},
                    root, sessionId, true);
        } finally {
            System.setOut(oldOut);
        }

        assertEquals(0, code);
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("蓝鲸"),
                "第二步带上第一步上下文，实际：" + buf);
    }
}
