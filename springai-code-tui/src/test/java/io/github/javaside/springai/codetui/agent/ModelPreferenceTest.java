package io.github.javaside.springai.codetui.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelPreference} 的读写与全部降级路径。
 *
 * <p><b>降级契约是重点</b>：这个类跑在启动路径上，任何一条路抛异常都等于 code-tui 起不来。
 * 为一个「上次用了哪个模型」的偏好把整个工具搞挂，完全不值。
 */
class ModelPreferenceTest {

    @Test
    @DisplayName("写进去再读出来是同一个 id")
    void writeThenReadRoundTrips(@TempDir Path root) {
        assertTrue(ModelPreference.write(root, "deepseek-v4-flash"));
        assertEquals(Optional.of("deepseek-v4-flash"), ModelPreference.read(root));
    }

    @Test
    @DisplayName("文件不存在 → empty（首次运行是常态，不是错误）")
    void missingFileIsEmpty(@TempDir Path root) {
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    /**
     * 「文件不存在不打日志」这条要求，只有断言日志才钉得住。
     *
     * <p>不钉的话，{@code read} 里那道 {@code isRegularFile} 守卫删掉也全绿——因为
     * {@code readString} 抛 {@code NoSuchFileException} 会被下面的 catch 接住，
     * 返回值一模一样。区别只在于：没有守卫，<b>首次运行每次都刷一行 WARN</b>。
     * 兜底路径与成功路径输出相同，变异就杀不掉——所以这里断的是日志，不是返回值。
     */
    @Test
    @DisplayName("文件不存在：一条日志都不许打（首次运行是常态）")
    void missingFileLogsNothing(@TempDir Path root) {
        Logger logger = (Logger) LoggerFactory.getLogger(ModelPreference.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(Optional.empty(), ModelPreference.read(root));
            assertTrue(appender.list.isEmpty(),
                    "首次运行是常态不是错误，一个字都不该往日志里写:" + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 第三条、也是最后一条「不加就抛」的守卫：没有 {@code root == null} 判定，
     * {@code fileFor(null)} 上来就是 NPE。同样会被兜底 catch 吸收，故连日志一起断。
     */
    @Test
    @DisplayName("read(null)：显式挡掉，不该靠兜底 catch")
    void nullRootIsEmpty() {
        Logger logger = (Logger) LoggerFactory.getLogger(ModelPreference.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(Optional.empty(), ModelPreference.read(null));
            assertTrue(appender.list.isEmpty(),
                    "null root 该被显式挡掉，不该惊动兜底 catch:" + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("JSON 非法 → empty，且绝不抛")
    void malformedJsonIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{");
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    /**
     * 键不存在必须被 {@code v == null} <b>显式</b>挡掉：没有它，{@code v.stringValue()} 就是一发 NPE。
     *
     * <p>和 {@link #nonStringValueIsEmpty} 同一个形状——异常会被兜底 catch 吸收成 empty，
     * 与正确路径输出一模一样，只断返回值杀不掉变异。故连日志一起断。
     */
    @Test
    @DisplayName("缺 lastModel 键 → empty，且走的是显式守卫不是兜底 catch")
    void missingKeyIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"somethingElse\": \"x\"}");

        Logger logger = (Logger) LoggerFactory.getLogger(ModelPreference.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(Optional.empty(), ModelPreference.read(root));
            assertTrue(appender.list.isEmpty(),
                    "这是一条已知的、该被显式挡掉的路，不该惊动兜底 catch:" + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 非文本节点必须被 {@code isString()} <b>显式</b>挡掉，而不是抛出去让兜底 catch 接住。
     *
     * <p><b>为什么还要断言日志</b>：{@code read} 外面包了一层 catch-all 之后，把 {@code isString()}
     * 守卫删掉，这条测试的<b>返回值断言照样绿</b>——异常被兜底接住，同样返回 empty。实测确认过。
     * 兜底与正确路径输出相同，变异就杀不掉。
     * 区别只剩一处：走兜底会打一行 WARN。所以这里连日志一起断，守卫没了才有东西会红。
     */
    @Test
    @DisplayName("lastModel 不是字符串 → empty，且走的是显式守卫不是兜底 catch")
    void nonStringValueIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"lastModel\": 42}");

        Logger logger = (Logger) LoggerFactory.getLogger(ModelPreference.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(Optional.empty(), ModelPreference.read(root));
            assertTrue(appender.list.isEmpty(),
                    "这是一条已知的、该被显式挡掉的路，不该惊动兜底 catch:" + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("lastModel 是 JSON null → empty")
    void nullValueIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"lastModel\": null}");
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    @Test
    @DisplayName("lastModel 是空串 → empty")
    void emptyStringValueIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"lastModel\": \"\"}");
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    @Test
    @DisplayName("lastModel 是空串或全空白 → empty")
    void blankValueIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"lastModel\": \"   \"}");
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    @Test
    @DisplayName(".codetui/ 不存在时 write 会把目录建出来")
    void writeCreatesCodetuiDirectory(@TempDir Path root) {
        assertFalse(Files.exists(root.resolve(".codetui")), "前提：目录本来不存在");
        assertTrue(ModelPreference.write(root, "claude-opus-5"));
        assertTrue(Files.isRegularFile(ModelPreference.fileFor(root)));
    }

    /**
     * 写不进去时必须返回 false 而不是抛。
     *
     * <p><b>用「.codetui 是个普通文件」而不是 chmod 去制造失败</b>：chmod 那条路在 root 用户下
     * POSIX 权限位根本不拦人，测试会静默变绿——那种测试比没有还糟。占位文件这招对谁都成立。
     */
    @Test
    @DisplayName("写不进去 → false，且绝不抛")
    void writeFailureReturnsFalse(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".codetui"), "我不是目录");
        assertFalse(ModelPreference.write(root, "deepseek-v4-pro"));
    }

    /**
     * 原子写用的临时文件必须被 move 走，不能留在 .codetui/ 里堆积。
     * 用户会打开这个目录看，满地 .tmp 是会让人以为出了故障的。
     */
    @Test
    @DisplayName("写完不留 .tmp 残骸")
    void noTempFileLeftBehind(@TempDir Path root) throws Exception {
        assertTrue(ModelPreference.write(root, "deepseek-v4-flash"));
        try (var s = Files.list(root.resolve(".codetui"))) {
            List<String> leftovers = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".tmp"))
                    .toList();
            assertEquals(List.of(), leftovers, "原子写的临时文件必须被 move 走:" + leftovers);
        }
        assertTrue(Files.isRegularFile(ModelPreference.fileFor(root)), "目标文件要在");
    }
}
