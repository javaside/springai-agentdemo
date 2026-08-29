package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.llm.ProviderModel;
import io.github.javaside.springai.codetui.agent.llm.ModelPreference;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /model} 选中即落盘，下次启动才恢复得回来。
 *
 * <p><b>三条都要测</b>：写成功（主路径）、写失败（用户必须知道这次没记住）、
 * 以及<b>选中没生效就不写</b>——最后这条防的是「把一个选不中的 id 落到盘上，
 * 下次启动再触发一次『用不了，已回退』」，自己给自己制造失效记录。
 */
class CodeTuiViewModelMemoryTest {

    /** 两个模型；selectModel 只接受清单里的（与 ProviderRegistry.select 同语义）。 */
    private static class Handler implements SubmitHandler {
        final List<ProviderModel> options = List.of(
                new ProviderModel("p", "alpha", "alpha", "第一个"),
                new ProviderModel("p", "beta", "beta", "第二个"));
        String current = "alpha";

        @Override public Disposable submit(String text) { return () -> { }; }
        @Override public List<ProviderModel> models() { return options; }
        @Override public String currentModel() { return current; }
        @Override public String currentProviderId() { return "p"; }
        @Override public void selectModel(String providerId, String modelId) {
            for (ProviderModel m : options) {
                if (m.providerId().equals(providerId) && m.id().equals(modelId)) { current = modelId; return; }
            }
            // 未知 id：静默忽略
        }
    }

    /** selectModel 一律不生效——模拟「选了个 registry 里其实没有的模型」。 */
    private static final class DeafHandler extends Handler {
        @Override public void selectModel(String providerId, String modelId) { /* 什么都不做 */ }
    }

    /** 打开 /model 选择器 → 数字快选第 2 项 → Enter 确认。 */
    private static void pickSecondModel(CodeTuiView v) {
        v.setInputForTest("/model");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        v.feedKeyForTest(KeyEvent.ofChar('2'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    @Test
    @DisplayName("选中即落盘：下次启动读得到的就是它")
    void selectingWritesPreference(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), new Handler(), root);

        pickSecondModel(v);

        assertEquals(Optional.of(new ModelPreference.Choice("p", "beta")), ModelPreference.read(root));
    }

    /**
     * 写失败必须告诉用户。静默失败下次启动还是老模型，用户只会觉得「这功能坏了」，
     * 而且不知道该去看什么。
     */
    @Test
    @DisplayName("写不进去：多打一行「仅本次运行生效」")
    void writeFailureAddsExtraLine(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".codetui"), "我不是目录");   // 让写必然失败
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, new Handler(), root);

        pickSecondModel(v);

        List<String> texts = state.drainPending().stream()
                .map(ConversationState.OutputLine::text).toList();
        assertTrue(texts.stream().anyMatch(t -> t.contains("没能记住")),
                "写失败必须有一行说明:" + texts);
        assertTrue(texts.stream().anyMatch(t -> t.contains("已切换模型")),
                "本次运行内确实切换了，那行确认不能因为写失败就消失:" + texts);
    }

    /**
     * 杀掉「无条件写」这个变异。
     */
    @Test
    @DisplayName("选中没生效：一个字节都不许落盘")
    void ineffectiveSelectionWritesNothing(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), new DeafHandler(), root);

        pickSecondModel(v);

        assertFalse(Files.exists(ModelPreference.fileFor(root)),
                "没生效的选择不该留下记录，否则下次启动会白白触发一次「用不了，已回退」");
    }

    @Test
    @DisplayName("回合进行中：不得确认切换模型")
    void activeTurnBlocksModelSelection(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        Handler handler = new Handler();
        CodeTuiView v = new CodeTuiView(state, handler, root);

        pickSecondModel(v);

        assertEquals("alpha", handler.current, "在飞回合必须继续绑定发起时的模型");
        assertTrue(ViewScreen.of(v).contains("处理完成后再切换"), "拒绝原因必须在选择器中对用户可见");
        assertFalse(Files.exists(ModelPreference.fileFor(root)), "被拒绝的切换不得落盘");
    }

    @Test
    @DisplayName("存在排队消息：不得确认切换模型")
    void queuedMessageBlocksModelSelection(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.enqueue("看图继续", null);
        Handler handler = new Handler();
        CodeTuiView v = new CodeTuiView(state, handler, root);

        pickSecondModel(v);

        assertEquals("alpha", handler.current, "排队消息应沿用入队时的模型能力");
        assertTrue(state.notice().contains("处理完成后再切换"));
        assertFalse(Files.exists(ModelPreference.fileFor(root)), "被拒绝的切换不得落盘");
    }
}
