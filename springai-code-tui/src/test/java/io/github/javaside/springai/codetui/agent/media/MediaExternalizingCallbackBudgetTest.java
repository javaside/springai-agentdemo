package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 预算归属：工具执行期「额度已尽」必须问「发起本次调用的 agent 的预算」，不是进程里最后一次请求。 */
class MediaExternalizingCallbackBudgetTest {

    @TempDir Path root;

    /** 返回一段 Read 风格文本；输入是相对 root 的图片路径。 */
    private static ToolCallback fakeRead() {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("Read").description("read").inputSchema("{}").build();
            }
            @Override public String call(String input) { return "read " + input; }
        };
    }

    /**
     * 让工具走「视觉模型」分支（否则 capsOf 兜底 TEXT_ONLY → 永远 reference_only，
     * 本用例三个断言全都不可能成立）。ctx 带能力快照；预算按参数可选。
     */
    private String readImage(ToolCallback cb, VisionBudget ctxBudget) {
        Map<String, Object> m = new HashMap<>();
        m.put(MediaExternalizingCallback.CAPABILITIES_KEY, new ModelCapabilities(true, false));
        if (ctxBudget != null) {
            m.put(MediaExternalizingCallback.BUDGET_KEY, ctxBudget);
        }
        return cb.call("{\"file_path\":\"" + root.resolve("a.png") + "\"}", new ToolContext(m));
    }

    private static VisionBudget exhaustedBudget(int limit) {
        VisionBudget b = new VisionBudget(limit);
        // 用唯一 key 开一次会话并把工具图额度吃满，使 currentTurnExhausted() 为真
        for (int i = 0; i < limit; i++) {
            b.open("turn-" + System.nanoTime()).tryConsumeTurnSlot();
        }
        return b;
    }

    private void png() throws Exception {
        if (root.resolve("a.png").getParent() != null) Files.createDirectories(root.resolve("a.png").getParent());
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", root.resolve("a.png").toFile());
    }

    private ToolCallback callback(VisionBudget ctorBudget) {
        MediaArtifactStore store = new MediaArtifactStore(root.resolve(".codetui").resolve("artifacts"), root);
        return new MediaExternalizingCallback(fakeRead(), store, new TextReferenceMediaHandler(), root, ctorBudget);
    }

    private ToolCallback cbWithNullBudget() {
        MediaArtifactStore store = new MediaArtifactStore(root.resolve(".codetui").resolve("artifacts"), root);
        return new MediaExternalizingCallback(fakeRead(), store, new TextReferenceMediaHandler(), root, null);
    }

    @Test
    void ctxBudgetWinsOverConstructorBudget() throws Exception {
        png();
        VisionBudget exhausted = exhaustedBudget(1);
        VisionBudget fresh = new VisionBudget(3);
        String out = readImage(callback(fresh), exhausted);
        assertTrue(out.contains(FileReference.DELIVERY_TURN_EXHAUSTED),
                "ctx 里的预算已耗尽、构造注入的未耗尽 → 必须报耗尽（证明 ctx 优先）。实际：" + out);
    }

    @Test
    void fallsBackToConstructorBudgetWhenCtxHasNone() throws Exception {
        png();
        VisionBudget exhausted = exhaustedBudget(1);
        String out = readImage(callback(exhausted), null);
        assertTrue(out.contains(FileReference.DELIVERY_TURN_EXHAUSTED), "构造注入的预算已耗尽应被读到。实际：" + out);
    }

    @Test
    void noBudgetAnywhereMeansNotExhausted() throws Exception {
        png();
        // MCP 形态：构造 budget=null、ctx 也没有 → 恒 false（与引入本功能前一致）
        String out = readImage(cbWithNullBudget(), null);
        assertFalse(out.contains(FileReference.DELIVERY_TURN_EXHAUSTED), "无预算来源时不得报耗尽。实际：" + out);
        assertTrue(out.contains(FileReference.DELIVERY_NOT_IN_VIEW),
                "有视觉能力但额度未知 → not_in_view（Read 一次就能看）。实际：" + out);
    }
}
