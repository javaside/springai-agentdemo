package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.ModelOption;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import io.github.javaside.springai.codetui.agent.thinking.ModelThinkingSettings;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeTuiViewThinkingSettingsTest {

    private static final class Handler implements SubmitHandler {
        final List<ModelOption> options = List.of(
                new ModelOption("alpha", "alpha", "effort 模型"),
                new ModelOption("beta", "beta", "不可配置模型"));
        String current = "alpha";
        String savedModel;
        ThinkingConfig savedConfig;
        boolean saveReturn = true;

        @Override public Disposable submit(String text) { return () -> { }; }
        @Override public List<ModelOption> models() { return options; }
        @Override public String currentModel() { return current; }
        @Override public void selectModel(String id) { current = id; }

        @Override public ModelThinkingSettings thinkingSettings(String modelId) {
            if ("alpha".equals(modelId)) {
                return new ModelThinkingSettings("p", "alpha", "alpha",
                        ThinkingConfig.defaults(),
                        ThinkingCapabilities.effort(true, List.of("low", "high")));
            }
            return new ModelThinkingSettings("p", "beta", "beta",
                    ThinkingConfig.defaults(), ThinkingCapabilities.unsupported());
        }

        @Override public boolean saveThinkingSettings(String modelId, ThinkingConfig config) {
            savedModel = modelId;
            savedConfig = config;
            return saveReturn;
        }
    }

    private static CodeTuiView openPicker(Handler handler, Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), handler, root);
        v.setInputForTest("/model");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        return v;
    }

    @Test
    void rightArrowOpensSettingsWithoutSwitchingModel(@TempDir Path root) {
        Handler handler = new Handler();
        CodeTuiView v = openPicker(handler, root);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.RIGHT));
        assertTrue(v.configuringThinkingForTest());
        assertEquals("alpha", v.thinkingTargetForTest());
        assertEquals("alpha", handler.current);
    }

    @Test
    void enterSavesAndReturnsToList(@TempDir Path root) {
        Handler handler = new Handler();
        CodeTuiView v = openPicker(handler, root);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.RIGHT));
        // Row 0 = mode. First switch DEFAULT -> ENABLED (initializes effort to first value).
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.RIGHT));
        // Then move to strength row and cycle low -> high.
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.RIGHT));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertFalse(v.configuringThinkingForTest());
        assertEquals("alpha", handler.savedModel);
        assertEquals("high", handler.savedConfig.effort());
    }

    @Test
    void escapeDiscardsDraft(@TempDir Path root) {
        Handler handler = new Handler();
        CodeTuiView v = openPicker(handler, root);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.RIGHT));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.RIGHT));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertFalse(v.configuringThinkingForTest());
        assertEquals(null, handler.savedModel);
    }

    @Test
    void unsupportedModelDoesNotEnterSettings(@TempDir Path root) {
        Handler handler = new Handler();
        CodeTuiView v = openPicker(handler, root);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));   // highlight beta
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.RIGHT));
        assertFalse(v.configuringThinkingForTest());
    }

    /** scope(boolean, ...) 每帧立即求值：不进入二级面板时渲染也必须安全，不能拿 null 去查模型。 */
    @Test
    void renderIsSafeWithoutOpeningSettings(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), new Handler(), root);
        v.renderForTest();   // 不打开 /model，直接构造一帧 UI 树
        assertFalse(v.configuringThinkingForTest());
    }
}
