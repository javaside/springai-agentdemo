package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultStoreTest {

    @Test
    void shortResultIsReturnedVerbatimWithNoFileWritten(@TempDir Path root) {
        TaskResultStore store = new TaskResultStore(root, 100);
        String out = store.storeAndTruncate("task_ab12", "短结果");
        assertEquals("短结果", out);
        assertFalse(Files.exists(root.resolve(".codetui/artifacts/task-task_ab12.txt")),
                "未超限时不该落盘——凭空多出一堆小文件没有价值");
    }

    @Test
    void longResultIsTruncatedAndFullTextWrittenToDisk(@TempDir Path root) throws IOException {
        TaskResultStore store = new TaskResultStore(root, 20);
        String full = "x".repeat(100);
        String out = store.storeAndTruncate("task_ab12", full);

        assertTrue(out.startsWith("x".repeat(20)));
        assertTrue(out.contains("结果已截断"));
        assertTrue(out.contains("100"), "要写明完整长度，否则用户不知道漏了多少");
        Path artifact = root.resolve(".codetui/artifacts/task-task_ab12.txt");
        assertTrue(out.contains(artifact.toString()), "要给出可 Read 的绝对路径");
        assertEquals(full, Files.readString(artifact));
    }

    @Test
    void truncationHappensOnCharCountNotLineCount(@TempDir Path root) {
        TaskResultStore store = new TaskResultStore(root, 10);
        String out = store.storeAndTruncate("task_x", "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl");
        assertTrue(out.contains("结果已截断"));
    }

    @Test
    void nullResultBecomesEmptyStringNotNpe(@TempDir Path root) {
        assertEquals("", new TaskResultStore(root, 100).storeAndTruncate("task_x", null));
    }

    @Test
    void diskFailureDegradesToInMemoryTruncationInsteadOfThrowing(@TempDir Path root) throws IOException {
        // 把 artifacts 位置占成一个普通文件 → 建目录必失败
        Path codetui = Files.createDirectories(root.resolve(".codetui"));
        Files.writeString(codetui.resolve("artifacts"), "我是文件不是目录");

        TaskResultStore store = new TaskResultStore(root, 10);
        String out = store.storeAndTruncate("task_x", "y".repeat(50));

        assertTrue(out.startsWith("y".repeat(10)));
        assertTrue(out.contains("完整结果落盘失败"),
                "落盘失败要如实说，不能假装给了一个取不回来的路径");
    }
}
