package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.bindings.Bindings;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shift+Tab 模式循环与 {@code /permissions} 只读报告。
 *
 * <p>期 0 已在真实 pty 上实测 {@code Shift+Tab} 发 {@code ESC[Z} → {@code code=TAB mods=[S]}，
 * 与裸 Tab 可区分；本类钉的是「两处既有裸 Tab 处理不许把它吃掉」这条守卫。
 */
class CodeTuiViewPermissionModeTest {

    /** 记录 cyclePermissionMode 被调了几次的桩。 */
    private static class ModeStub implements SubmitHandler {
        final AtomicInteger cycles = new AtomicInteger();
        PermissionMode mode = PermissionMode.DEFAULT;
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public PermissionMode permissionMode() { return mode; }
        @Override public PermissionMode cyclePermissionMode() {
            cycles.incrementAndGet();
            mode = mode.next(false);
            return mode;
        }
        @Override public List<PermissionRule> permissionRules() {
            return List.of(PermissionRule.parse("Bash(mvn test:*)",
                    PermissionBehavior.ALLOW, RuleScope.PROJECT));
        }
    }

    private static KeyEvent shiftTab() {
        return KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT);
    }

    /**
     * <b>路由器会先吃掉 Tab / Shift+Tab</b>，除非把焦点导航解绑——这是 pty 实机抓出来的，
     * 本类其余用例（走 {@code InputBox.handleKeyEvent}）全部绕过了 {@code EventRouter}，看不见它。
     *
     * <p>机制：{@code EventRouter.routeKeyEvent} 把焦点导航排在最前，命中 {@code isFocusPrevious()}
     * 后即使 {@code focusPrevious()} 失败（本应用只有输入框一个可聚焦元素）也<b>直接 return UNHANDLED</b>，
     * 不再下发给焦点元素。故这里钉住「Tab/Shift+Tab 不再是焦点导航键」这条前提，
     * 破了它上面所有 Tab 用例都会变成假绿。
     */
    @Test
    @DisplayName("前提：Tab / Shift+Tab 已从焦点导航解绑，否则永远到不了输入框")
    void tabKeysAreNotFocusNavigation(@TempDir Path root) {
        Bindings b = new CodeTuiView(new ConversationState(), new ModeStub(), root).configure(4).bindings();

        assertFalse(b.matches(shiftTab(), Actions.FOCUS_PREVIOUS),
                "Shift+Tab 仍绑着 focusPrevious → EventRouter 会吃掉它，权限模式切不动");
        assertFalse(b.matches(KeyEvent.ofKey(KeyCode.TAB), Actions.FOCUS_NEXT),
                "裸 Tab 仍绑着 focusNext → EventRouter 会吃掉它，斜杠补全/MCP 展开是死的");
        assertTrue(b.matches(new KeyEvent(KeyCode.CHAR, KeyModifiers.CTRL, 'c'), Actions.QUIT),
                "解绑焦点导航不得殃及 Ctrl+C 退出");
    }

    @Test
    @DisplayName("Shift+Tab 循环模式")
    void shiftTabCyclesMode(@TempDir Path root) {
        ConversationState state = new ConversationState();
        ModeStub stub = new ModeStub();
        CodeTuiView v = new CodeTuiView(state, stub, root);

        v.feedKeyForTest(shiftTab());

        assertEquals(1, stub.cycles.get());
        assertEquals(PermissionMode.ACCEPT_EDITS, stub.mode);
        assertTrue(state.notice().contains("自动接受编辑"),
                "切换后应给出可见反馈，实际 notice：" + state.notice());
    }

    @Test
    @DisplayName("Shift+Tab 不把 Tab 字符打进输入框")
    void shiftTabDoesNotTypeIntoInput(@TempDir Path root) {
        ConversationState state = new ConversationState();
        ModeStub stub = new ModeStub();
        CodeTuiView v = new CodeTuiView(state, stub, root);

        v.feedKeyForTest(shiftTab());

        assertEquals("", v.inputTextForTest(), "模式键必须被吞掉，不落进输入框");
    }

    @Test
    @DisplayName("连按两次回到默认（未授权 BYPASS 时两态循环）")
    void shiftTabTwiceReturnsToDefault(@TempDir Path root) {
        ConversationState state = new ConversationState();
        ModeStub stub = new ModeStub();
        CodeTuiView v = new CodeTuiView(state, stub, root);

        v.feedKeyForTest(shiftTab());
        v.feedKeyForTest(shiftTab());

        assertEquals(2, stub.cycles.get());
        assertEquals(PermissionMode.DEFAULT, stub.mode);
        assertTrue(state.notice().contains("默认"), "第二次切换的反馈不该被「按任意键清 notice」吃掉，"
                + "实际 notice：" + state.notice());
    }

    @Test
    @DisplayName("裸 Tab 不触发模式循环（不能把补全/展开抢走）")
    void plainTabDoesNotCycle(@TempDir Path root) {
        ConversationState state = new ConversationState();
        ModeStub stub = new ModeStub();
        CodeTuiView v = new CodeTuiView(state, stub, root);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.TAB));

        assertEquals(0, stub.cycles.get());
    }

    @Test
    @DisplayName("斜杠菜单激活时，裸 Tab 仍补全、Shift+Tab 走模式循环（守卫生效）")
    void slashMenuTabGuard(@TempDir Path root) {
        ConversationState state = new ConversationState();
        ModeStub stub = new ModeStub();
        CodeTuiView v = new CodeTuiView(state, stub, root);
        v.setInputForTest("/mod");                 // 触发补全菜单

        v.feedKeyForTest(shiftTab());
        assertEquals(1, stub.cycles.get(), "菜单激活时 Shift+Tab 也应切模式，不被补全吃掉");

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.TAB));
        assertEquals("/model", v.inputTextForTest(), "裸 Tab 仍应补全");
        assertEquals(1, stub.cycles.get(), "裸 Tab 不得触发模式循环");
    }

    @Test
    @DisplayName("MCP 面板激活时，裸 Tab 仍展开工具清单、Shift+Tab 走模式循环（守卫生效）")
    void mcpPickerTabGuard(@TempDir Path root) {
        ConversationState state = new ConversationState();
        ModeStub stub = new ModeStub() {
            @Override public List<io.github.javaside.springai.codetui.agent.McpRegistry.ServerView> mcpServers() {
                return List.of(new io.github.javaside.springai.codetui.agent.McpRegistry.ServerView(
                        "demo",
                        io.github.javaside.springai.codetui.agent.McpConfigLoader.ConfigSource.PROJECT,
                        io.github.javaside.springai.codetui.agent.McpRegistry.Status.DISABLED,
                        0, List.of(), null));
            }
        };
        CodeTuiView v = new CodeTuiView(state, stub, root);
        v.setInputForTest("/mcp");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(v.pickingMcpForTest(), "前置：MCP 面板应已打开");

        v.feedKeyForTest(shiftTab());
        assertEquals(1, stub.cycles.get(), "MCP 面板激活时 Shift+Tab 也应切模式，不被展开键吃掉");
        assertTrue(v.pickingMcpForTest(), "切模式不该关掉面板");
    }

    @Test
    @DisplayName("审批模态激活时 Shift+Tab 仍能切模式，且不动 pending 请求")
    void shiftTabWorksInsidePermissionModal(@TempDir Path root) {
        ConversationState state = new ConversationState();
        ModeStub stub = new ModeStub();
        state.onTurnStarted(1L);
        java.util.List<io.github.javaside.springai.codetui.agent.PermissionOutcome> sink =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        state.onPermissionRequested(1L, new io.github.javaside.springai.codetui.agent.PermissionRequest(
                1L, null, "Bash", "git push", "{}", "未获授权", null, sink::add));
        CodeTuiView v = new CodeTuiView(state, stub, root);
        v.tickForTest();

        v.feedKeyForTest(shiftTab());

        assertEquals(1, stub.cycles.get());
        assertTrue(sink.isEmpty(), "切模式不得应答 pending 请求");
        assertFalse(v.activePermissionForTest() == null, "面板应仍在（用户仍需应答）");
    }

    @Test
    @DisplayName("/permissions 把当前模式与生效规则打进 scrollback")
    void permissionsCommandPrintsReport(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, new ModeStub(), root);
        v.setInputForTest("/permissions");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        String all = String.join("\n",
                state.drainPending().stream().map(ConversationState.OutputLine::text).toList());
        assertTrue(all.contains("默认"), "应显示当前模式，实际：" + all);
        assertTrue(all.contains("Bash(mvn test:*)"), "应列出生效规则，实际：" + all);
        assertTrue(all.contains("Shift+Tab"), "应说明如何切换模式，实际：" + all);
        assertEquals("", v.inputTextForTest(), "命令执行后应清空输入框");
    }

    @Test
    @DisplayName("/permissions 无自定义规则时给出配置指引，而不是空白")
    void permissionsCommandWithoutRules(@TempDir Path root) {
        ConversationState state = new ConversationState();
        // 默认 SubmitHandler：permissionRules() 空、permissionMode() DEFAULT
        CodeTuiView v = new CodeTuiView(state, (SubmitHandler) t -> null, root);
        v.setInputForTest("/permissions");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        String all = String.join("\n",
                state.drainPending().stream().map(ConversationState.OutputLine::text).toList());
        assertTrue(all.contains("permissions.json"), "应指出规则写在哪，实际：" + all);
        assertTrue(all.contains("内置底线"), "应列出任何 allow 都盖不住的内置检查，实际：" + all);
    }

    @Test
    @DisplayName("/permissions 出现在斜杠命令补全菜单里")
    void permissionsCommandIsCompletable(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, new ModeStub(), root);
        v.setInputForTest("/perm");

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.TAB));

        assertEquals("/permissions", v.inputTextForTest(), "Tab 应补全到 /permissions");
    }
}
