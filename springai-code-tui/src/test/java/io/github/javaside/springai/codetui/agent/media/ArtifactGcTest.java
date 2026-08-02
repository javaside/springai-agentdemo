package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactGcTest {

    @TempDir Path dir;

    private Path file(String name, int bytes, long mtimeMillis) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, new byte[bytes]);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtimeMillis));
        return p;
    }

    @Test
    void deletesOldestUntilUnderLimit() throws Exception {
        Path old1 = file("a.png", 600, 1_000L);
        Path old2 = file("b.png", 600, 2_000L);
        Path fresh = file("c.png", 600, 3_000L);

        ArtifactGc.sweep(dir, 1_500);   // 上限 1500 字节，总量 1800

        assertFalse(Files.exists(old1), "最老的 a.png 应被删除");
        assertTrue(Files.exists(old2), "b.png 删到上限以下后应保留");
        assertTrue(Files.exists(fresh), "最新的 c.png 应保留");
    }

    @Test
    void underLimitIsNoOp() throws Exception {
        Path p = file("a.png", 100, 1_000L);
        ArtifactGc.sweep(dir, 10_000);
        assertTrue(Files.exists(p), "未超上限不该删任何文件");
    }

    /** 目录不存在时必须安静返回——启动路径上绝不能抛。 */
    @Test
    void missingDirectoryIsSilent() {
        ArtifactGc.sweep(dir.resolve("nope"), 1000);
    }
}
