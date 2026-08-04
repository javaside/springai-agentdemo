package io.github.javaside.springai.codetui.agent.background;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 后台任务结果的<b>限幅与落盘</b>。
 *
 * <p><b>为什么必须有</b>：后台任务结果经通知文本进 user 消息 = 直接进会话 = 永久占上下文。
 * 前台子 agent 至少还有人盯着屏幕，后台任务是<b>没人看着的</b>——一个话痨的 explore
 * 能在你离开时把上下文打掉一大块。
 *
 * <p>超限时：完整正文落 {@code .codetui/artifacts/task-<id>.txt}，返回文本只留前 N 字符
 * 加一行说明与<b>绝对路径</b>（模型可以直接 Read 取回）。
 *
 * <p><b>落盘失败不抛异常</b>：结果本身是有价值的，不能因为写不了文件就把它丢掉。
 * 降级为纯内存截断，并在文本里<b>如实说明落盘失败</b>——给一个取不回来的路径比不给更糟。
 */
public final class TaskResultStore {

    private static final Logger log = LoggerFactory.getLogger(TaskResultStore.class);

    /** 默认上限：4000 字符。约等于 1-1.5k token，够放一份结论，不够放一整个文件 dump。 */
    public static final int DEFAULT_MAX_CHARS = 4000;

    private final Path root;
    private final int maxChars;

    public TaskResultStore(Path root) {
        this(root, DEFAULT_MAX_CHARS);
    }

    TaskResultStore(Path root, int maxChars) {
        this.root = root;
        this.maxChars = Math.max(1, maxChars);
    }

    /**
     * 限幅 + （超限时）落盘。返回可以安全塞进会话的文本。
     *
     * @param taskId 用于文件名
     * @param result 子 agent 的完整结果（可空）
     */
    public String storeAndTruncate(String taskId, String result) {
        String full = result == null ? "" : result;
        if (full.length() <= maxChars) {
            return full;
        }
        String head = full.substring(0, maxChars);
        Path artifact = root.resolve(".codetui").resolve("artifacts")
                .resolve("task-" + taskId + ".txt");
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, full);
            return head + "\n\n[结果已截断：完整 " + full.length() + " 字符见 "
                    + artifact.toAbsolutePath() + "，可用 Read 取回]";
        } catch (IOException | RuntimeException e) {
            log.warn("后台任务结果落盘失败：taskId={}", taskId, e);
            return head + "\n\n[结果已截断：完整 " + full.length()
                    + " 字符，但完整结果落盘失败（" + e.getClass().getSimpleName()
                    + "），截断部分之外的内容已丢失]";
        }
    }
}
