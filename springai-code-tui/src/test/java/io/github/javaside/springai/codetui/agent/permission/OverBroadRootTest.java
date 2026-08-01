package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverBroadRootTest {

    private static final Path HOME = Path.of(System.getProperty("user.home")).normalize();

    @Test
    @DisplayName("判据本身")
    void predicate() {
        assertTrue(DangerousPaths.isOverBroadRoot(Path.of("/")), "文件系统根一定过宽");
        assertTrue(DangerousPaths.isOverBroadRoot(HOME.getParent()), "家目录的父目录含家目录 → 过宽");

        assertFalse(DangerousPaths.isOverBroadRoot(HOME),
                "家目录本身不算过宽——它另有一条独立的家目录豁免，改这里也不生效（见 spec §4）");
        assertFalse(DangerousPaths.isOverBroadRoot(HOME.resolve("projects/demo")));
        assertFalse(DangerousPaths.isOverBroadRoot(null), "null 不该被当成过宽");
    }

    @Test
    @DisplayName("root=/ 时系统位置检查照常生效——此前它对所有路径失效")
    void systemLocationCheckSurvivesRootSlash() {
        assertNotNull(DangerousPaths.checkWrite(Path.of("/usr/local/bin/git"), Path.of("/")),
                "root=/ 时任何绝对路径都 startsWith(\"/\")，豁免必须失效");
    }

    @Test
    @DisplayName("正常 root 不受影响：工作区内照常放行、区外系统位置照常拦")
    void normalRootUnchanged(@TempDir Path tmp) throws Exception {
        Path root = tmp.toRealPath();       // macOS 的 /var → /private/var，先解析掉，免得混进判定
        assertNull(DangerousPaths.checkWrite(root.resolve("src/Main.java"), root));
        assertNotNull(DangerousPaths.checkWrite(Path.of("/usr/local/bin/git"), root));
    }

    /**
     * 判据推导说这些都不含家目录、应当正常，但推导不等于实测——容器与 FHS 部署里
     * {@code /opt} / {@code /srv} / {@code /workspace} 是<b>正常的</b>工作区落点，
     * 误判成过宽会让这些用户的每一次写都多一次审批。
     */
    @Test
    @DisplayName("常见的非家目录工作区不得误判为过宽（/opt、/srv、Docker 的 /workspace 等）")
    void realisticNonHomeWorkspacesAreNotOverBroad() {
        for (String p : new String[]{
                "/opt/myproject", "/srv/app", "/workspace", "/app", "/build",
                "/data/repos/demo", "/var/lib/jenkins/workspace/job", "/mnt/c/code/demo",
                "/private/var/folders/ab/xyz/T/junit123"}) {
            assertFalse(DangerousPaths.isOverBroadRoot(Path.of(p)), p + " 不含家目录，应当正常");
        }
    }

    @Test
    @DisplayName("正常但非家目录的工作区里，写自己的文件照常放行（判据正确不等于豁免仍然生效）")
    void nonHomeWorkspaceStillExemptsItsOwnFiles() {
        Path root = Path.of("/opt/myproject");
        assertNull(DangerousPaths.checkWrite(root.resolve("src/Main.java"), root),
                "/opt 工作区内的普通文件不该因为「不在家目录里」就被当成系统位置");
        assertNotNull(DangerousPaths.checkWrite(Path.of("/usr/local/bin/git"), root),
                "对照：区外系统位置照常拦");
    }
}
