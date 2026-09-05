package dev.tamboui.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

/**
 * {@link ExpectedLog} 自身的契约钉：capture 切断/恢复父级输出、awaitRecord 命中与超时、
 * close 对未断言 WARNING+ 的绊线——工具自身失效时不该让上层测试「假绿或假静音」。
 */
class ExpectedLogTest {

    private static final Logger log = Logger.getLogger(ExpectedLogTest.class.getName());

    @Test
    void captureSilencesParentRestoresAfterCloseAndReturnsThrown() throws Exception {
        try (ExpectedLog captured = ExpectedLog.capture(log)) {
            assertFalse(log.getUseParentHandlers(), "capture 期间必须切断父级输出（预期噪音不外泄）");
            log.log(Level.WARNING, "simulated pty failure happened", new IOException("simulated"));
            LogRecord record = captured.awaitRecord(Level.WARNING, "simulated pty failure", 1, TimeUnit.SECONDS);
            assertEquals("simulated", record.getThrown().getMessage(), "thrown 必须完整透传供断言");
        }
        assertTrue(log.getUseParentHandlers(), "close 后必须恢复父级输出（不影响后续真实日志）");
    }

    @Test
    void awaitTimesOutWhenNothingMatches() throws Exception {
        try (ExpectedLog captured = ExpectedLog.capture(log)) {
            assertThrows(AssertionError.class,
                    () -> captured.awaitRecord(Level.SEVERE, "never-logged", 100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void unassertedLoudRecordTripsClose() {
        ExpectedLog captured = ExpectedLog.capture(log);
        log.log(Level.SEVERE, "stray severe");
        AssertionError failure = assertThrows(AssertionError.class, captured::close);
        assertTrue(failure.getMessage().contains("stray severe"), "绊线消息应指明未断言的记录");
        assertTrue(log.getUseParentHandlers(), "绊线触发后父级输出仍须已恢复");
    }
}
