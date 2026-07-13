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

    @Test
    void relativeToRoot_inRoot_isCleanRelative(@TempDir Path root) throws Exception {
        Path f = root.resolve("sub/shot.png");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "x");
        String rel = PathContainment.relativeToRoot(f, root);
        assertEquals("sub/shot.png".replace('/', java.io.File.separatorChar), rel);
        assertFalse(rel.startsWith(".."), "不得是跨越式相对路径");
    }

    /** root 带符号链接、file 已解链时，relativize 不能算出 `../../private/...`——两边解链后应得干净相对路径。 */
    @Test
    void relativeToRoot_symlinkedRoot_noEscape(@TempDir Path real) throws Exception {
        Path realTarget = real.resolve("realdir");
        Files.createDirectory(realTarget);
        Path f = realTarget.resolve("shot.png");
        Files.writeString(f, "x");
        Path linkRoot = real.resolve("linkdir");
        try {
            Files.createSymbolicLink(linkRoot, realTarget);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;
        }
        // root 带链、file 给解链后的真实路径
        String rel = PathContainment.relativeToRoot(f.toRealPath(), linkRoot);
        assertEquals("shot.png", rel, "解链后应得干净相对名，而非 ../../ 跨越式路径");
        assertFalse(rel.contains(".."), "不得含 ..");
    }
}
