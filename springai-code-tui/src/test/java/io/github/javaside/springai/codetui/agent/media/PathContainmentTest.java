// PathContainmentTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathContainmentTest {

    @Test
    void inRootRegularFile_resolves(@TempDir Path root) throws Exception {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "x");
        assertNotNull(PathContainment.resolveInRoot(f.toAbsolutePath().toString(), root));
    }

    @Test
    void outsideRoot_rejected(@TempDir Path root, @TempDir Path other) throws Exception {
        Path f = other.resolve("b.txt");
        Files.writeString(f, "x");
        assertNull(PathContainment.resolveInRoot(f.toAbsolutePath().toString(), root),
                "root 外的文件必须拒绝");
    }

    @Test
    void nonexistentOrNonRegular_null(@TempDir Path root) {
        assertNull(PathContainment.resolveInRoot(root.resolve("nope.txt").toString(), root));
        assertNull(PathContainment.resolveInRoot(root.toString(), root), "目录不是普通文件");
        assertNull(PathContainment.resolveInRoot(null, root));
        assertNull(PathContainment.resolveInRoot("  ", root));
    }

    /** 核心：root 经符号链接指向真实目录（模拟 /tmp -> /private/tmp）。文件参数用「解链后」的真实
     *  路径给出，而 root 用「带链」的路径——normalize 字符串比较会误判越界，toRealPath 两边解链才正确。 */
    @Test
    void symlinkedRoot_fileUnderRealTarget_resolves(@TempDir Path real) throws Exception {
        Path realTarget = real.resolve("realdir");
        Files.createDirectory(realTarget);
        Path f = realTarget.resolve("shot.png");
        Files.writeString(f, "x");

        Path linkRoot = real.resolve("linkdir");
        try {
            Files.createSymbolicLink(linkRoot, realTarget);   // linkRoot -> realTarget
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;   // 平台不支持符号链接（如无权限的 Windows）→ 跳过，不误报
        }

        // root 用带链路径，文件参数用真实解链路径——两边字符串前缀不同，但解链后同源。
        Path resolved = PathContainment.resolveInRoot(f.toAbsolutePath().toString(), linkRoot);
        assertNotNull(resolved, "解符号链接后应判为 in-root，不得误拒");

        // 反向：文件参数走带链路径也应成立。
        Path viaLink = linkRoot.resolve("shot.png");
        assertNotNull(PathContainment.resolveInRoot(viaLink.toString(), real),
                "经链路径的文件也应解析进真实 root");
    }
}
