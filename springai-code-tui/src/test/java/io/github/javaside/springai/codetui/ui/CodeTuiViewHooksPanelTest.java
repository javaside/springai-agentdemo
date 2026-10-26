package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.hooks.HookRegistry;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /hooks 面板：列表/未信任标记/t 信任即时生效/Esc（断言落 ViewScreen，同 Tasks 纪律）。 */
class CodeTuiViewHooksPanelTest {

    private static final class HooksStub implements SubmitHandler {
        HookRegistry registry;
        HooksStub(HookRegistry r) { registry = r; }
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public io.github.javaside.springai.codetui.agent.hooks.HookRegistry hookRegistry() { return registry; }
    }

    private static HookRegistry registry(Path root, String projJson) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("user.json"),
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"command\":\"u-guard.sh\"}]}}");
        if (projJson != null) Files.writeString(root.resolve("proj.json"), projJson);
        return HookRegistry.load(root, root.resolve("user.json"),
                root.resolve(projJson == null ? "np.json" : "proj.json"), root.resolve("trust.json"));
    }

    @Test
    void listsHooks_marksUntrusted(@TempDir Path root) throws Exception {
        HookRegistry reg = registry(root,
                "{\"hooks\":{\"Stop\":[{\"command\":\"notify.sh\"}]}}");
        CodeTuiView v = new CodeTuiView(new ConversationState(), new HooksStub(reg), root);
        v.setInputForTest("/hooks");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertTrue(v.pickingHooksForTest());
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("u-guard.sh"), "用户层钩子列出：" + screen);
        assertTrue(screen.contains("notify.sh"), "项目层钩子列出");
        assertTrue(screen.contains("未信任"), "项目层未信任标记");
    }

    @Test
    void trustKeyFlipsImmediately_escCloses(@TempDir Path root) throws Exception {
        HookRegistry reg = registry(root,
                "{\"hooks\":{\"PreToolUse\":[{\"command\":\"p-guard.sh\"}]}}");
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, new HooksStub(reg), root);
        v.setInputForTest("/hooks");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(reg.projectLayerUntrusted());

        v.feedKeyForTest(KeyEvent.ofChar('t'));

        assertFalse(reg.projectLayerUntrusted(), "t 即时翻转（同一实例）");
        assertTrue(state.notice().contains("已信任"), "确认回执：" + state.notice());
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertFalse(v.pickingHooksForTest());
    }

    @Test
    void emptyPanelExplains_noRegistryTolerated(@TempDir Path root) throws Exception {
        CodeTuiView v = new CodeTuiView(new ConversationState(), new HooksStub(null), root);
        v.setInputForTest("/hooks");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(v.pickingHooksForTest(), "registry null（桩）也照常打开");
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("未启用钩子") && screen.contains("hooks.json"),
                "空态既说明状态又指路放文件位置：" + screen);
    }
}
