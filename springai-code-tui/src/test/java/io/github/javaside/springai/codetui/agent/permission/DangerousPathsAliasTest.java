package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 内置底线也要认符号链接与大小写。它<b>没有 allow 方向</b>（命中只会导致询问/拒绝），
 * 故这里放宽是纯安全方向，不需要引擎那套 deny/allow 不对称。
 *
 * <p><b>为什么每个用例都先 {@code toRealPath()}</b>：{@code @TempDir} 在 macOS 上位于
 * {@code /var/folders/…}，而 {@code /var} 本身就是指向 {@code /private/var} 的符号链接。
 * 不先解析，「被拦住」到底是因为用例自己建的那条链、还是因为系统自带的 {@code /var}，
 * 就分不清了——realpath 之后，有没有链完全由用例自己控制。
 */
class DangerousPathsAliasTest {

    @Test
    @DisplayName("经符号链接写 .ssh/ 也要被拦——底线此前完全不解析链接")
    void writeThroughSymlinkIntoSsh(@TempDir Path tmp) throws Exception {
        Path root = tmp.toRealPath();
        Path fakeHome = Files.createDirectories(root.resolve("home"));
        Path ssh = Files.createDirectories(fakeHome.resolve(".ssh"));
        try {
            Files.createSymbolicLink(root.resolve("shortcut"), ssh);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "本环境不支持创建符号链接");
            return;
        }
        assertNotNull(DangerousPaths.checkWrite(root.resolve("shortcut/authorized_keys"), root),
                "路径里没有 .ssh 字样，但解析后有——这正是两步绕过");
    }

    @Test
    @DisplayName("按名字命中的检查要折叠大小写：写 .ZSHRC 与写 .zshrc 同样危险")
    void shellConfigNameIsCaseFolded(@TempDir Path tmp) throws Exception {
        Path root = tmp.toRealPath();
        String home = System.getProperty("user.home");
        assertNotNull(DangerousPaths.checkWrite(Path.of(home, ".ZSHRC"), root));
        assertNotNull(DangerousPaths.checkWrite(Path.of(home, ".zshrc"), root), "对照：原写法本来就该被拦");
    }

    @Test
    @DisplayName("密钥扩展名同理：x.PEM 与 x.pem 同样是密钥")
    void secretExtensionIsCaseFolded(@TempDir Path tmp) throws Exception {
        Path root = tmp.toRealPath();
        assertNotNull(DangerousPaths.checkRead(Path.of(System.getProperty("user.home"), "x.PEM"), root));
    }

    @Test
    @DisplayName("放宽不得制造假阳性：工作区内的普通文件仍然放行")
    void ordinaryWorkspaceFileStillPasses(@TempDir Path tmp) throws Exception {
        Path root = tmp.toRealPath();
        assertNull(DangerousPaths.checkWrite(root.resolve("src/Main.java"), root));
        assertNull(DangerousPaths.checkRead(root.resolve("README.md"), root));
    }
}
