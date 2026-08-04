package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    @DisplayName("JSON 非法 → empty，且绝不抛")
    void malformedJsonIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{");
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    @Test
    @DisplayName("缺 lastModel 键 → empty")
    void missingKeyIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"somethingElse\": \"x\"}");
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    @Test
    @DisplayName("lastModel 不是字符串 → empty（Jackson 3 的 stringValue() 对非文本节点会抛）")
    void nonStringValueIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"lastModel\": 42}");
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
            List<String> names = s.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("model.json"), names, "目录里只该有 model.json:" + names);
        }
    }
}
