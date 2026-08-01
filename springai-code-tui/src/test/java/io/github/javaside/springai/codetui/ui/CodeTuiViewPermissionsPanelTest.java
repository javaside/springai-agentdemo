package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /permissions} 交互面板：列出规则、{@code d} 请求删除、Enter 确认、Esc 取消。
 *
 * <p>断言一律落在 {@link ViewScreen#of} 回读的<b>屏幕文本</b>上，而不是内部字段——面板类缺陷多是
 * 「内容构造得出来但被别的分支挡掉了」，只测构造函数是典型的不会失败的测试。
 */
class CodeTuiViewPermissionsPanelTest {

    private static PermissionRule rule(String dsl, PermissionBehavior b, RuleScope s) {
        return PermissionRule.parse(dsl, b, s);
    }

    /** 可编程桩：规则表可变（删除后要真的少一条），删除请求逐条记录。 */
    private static final class PermsStub implements SubmitHandler {
        final List<PermissionRule> rules = new ArrayList<>();
        final List<PermissionRule> removed = new ArrayList<>();
        boolean removeResult = true;

        @Override public Disposable submit(String text) { return null; }
        @Override public List<PermissionRule> permissionRules() { return List.copyOf(rules); }
        @Override public boolean removePermissionRule(PermissionRule r) {
            removed.add(r);
            if (removeResult) rules.remove(r);
            return removeResult;
        }
    }

    private static void openPanel(CodeTuiView v) {
        v.setInputForTest("/permissions");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    private static PermsStub stubWithThreeRules() {
        PermsStub h = new PermsStub();
        h.rules.add(rule("Read(**/.env)", PermissionBehavior.DENY, RuleScope.USER));
        h.rules.add(rule("Bash(mvn test:*)", PermissionBehavior.ALLOW, RuleScope.PROJECT));
        h.rules.add(rule("BochaWebSearch(*)", PermissionBehavior.ALLOW, RuleScope.SESSION));
        return h;
    }

    @Test
    @DisplayName("面板列出每条规则的行为/DSL/来源层，并注明内置底线不可删")
    void listsRulesWithBehaviorDslAndScope(@TempDir Path root) {
        PermsStub h = stubWithThreeRules();
        CodeTuiView v = new CodeTuiView(new ConversationState(), h, root);

        openPanel(v);

        assertTrue(v.pickingPermsForTest(), "面板应已打开");
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("DENY"), "应显示行为，实际：\n" + screen);
        assertTrue(screen.contains("Read(**/.env)"), "应显示 DSL，实际：\n" + screen);
        assertTrue(screen.contains("用户级"), "应显示来源层，实际：\n" + screen);
        assertTrue(screen.contains("项目级"), "应显示来源层，实际：\n" + screen);
        assertTrue(screen.contains("本会话"), "会话级规则也要标出来，实际：\n" + screen);
        assertTrue(screen.contains("内置底线不在此列"), "必须说明底线删不掉，实际：\n" + screen);
    }

    @Test
    @DisplayName("按 d 只是请求删除——桩还没收到删除调用，屏幕上先出现确认行")
    void pressingDOnlyAsksForConfirmation(@TempDir Path root) {
        PermsStub h = stubWithThreeRules();
        CodeTuiView v = new CodeTuiView(new ConversationState(), h, root);
        openPanel(v);

        v.feedKeyForTest(KeyEvent.ofChar('d'));

        assertEquals(List.of(), h.removed, "按 d 不该已经删掉任何东西");
        assertEquals(3, h.rules.size(), "规则表不该变");
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("确认删除"), "应出现确认行，实际：\n" + screen);
        assertTrue(screen.contains("Enter 确认"), "应说明怎么确认，实际：\n" + screen);
    }

    @Test
    @DisplayName("确认之后才真的删，且从列表里消失")
    void confirmingActuallyDeletes(@TempDir Path root) {
        PermsStub h = stubWithThreeRules();
        CodeTuiView v = new CodeTuiView(new ConversationState(), h, root);
        openPanel(v);
        v.feedKeyForTest(KeyEvent.ofChar('j'));            // 移到第二条 Bash(mvn test:*)
        v.feedKeyForTest(KeyEvent.ofChar('d'));

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(1, h.removed.size(), "应恰好删一条");
        assertEquals("Bash(mvn test:*)", h.removed.get(0).toDsl(), "删的应是高亮那条");
        assertTrue(ViewScreen.of(v).contains("已删除"), "应给出删除反馈");
        // 先按一个键清掉 notice：那条提示里也带着刚删的 DSL，不清掉的话
        // 「屏幕上还有这个 DSL」分不清是列表没删还是提示在回显（第一版就栽在这）。
        v.feedKeyForTest(KeyEvent.ofChar('j'));
        String screen = ViewScreen.of(v);
        assertFalse(screen.contains("Bash(mvn test:*)"), "删掉的规则不该还在列表里，实际：\n" + screen);
        assertTrue(screen.contains("Read(**/.env)"), "其余规则要留着，实际：\n" + screen);
    }

    @Test
    @DisplayName("确认行按 Esc 取消：什么都不删，回到列表")
    void escOnConfirmationCancels(@TempDir Path root) {
        PermsStub h = stubWithThreeRules();
        CodeTuiView v = new CodeTuiView(new ConversationState(), h, root);
        openPanel(v);
        v.feedKeyForTest(KeyEvent.ofChar('d'));

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals(List.of(), h.removed, "取消后不得有任何删除");
        assertEquals(3, h.rules.size());
        assertTrue(v.pickingPermsForTest(), "取消确认只回到列表，不该顺手关掉面板");
        String screen = ViewScreen.of(v);
        assertFalse(screen.contains("确认删除"), "确认行应已收起，实际：\n" + screen);
        assertTrue(screen.contains("权限规则"), "应回到列表，实际：\n" + screen);
    }

    @Test
    @DisplayName("★ 删 deny 的确认文案说「放宽权限」，删 allow 的不说——后果不对称，提示也不该对称")
    void denyDeletionWarnsAboutWideningAllowDoesNot(@TempDir Path root) {
        PermsStub h = stubWithThreeRules();
        CodeTuiView v = new CodeTuiView(new ConversationState(), h, root);
        openPanel(v);

        // 第一条是 DENY
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        String denyScreen = ViewScreen.of(v);
        assertTrue(denyScreen.contains("放宽权限"),
                "删 deny 必须警告这是在放宽权限，实际：\n" + denyScreen);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));   // 取消，换到 allow 那条
        v.feedKeyForTest(KeyEvent.ofChar('j'));
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        String allowScreen = ViewScreen.of(v);
        assertTrue(allowScreen.contains("以后会重新询问"),
                "删 allow 应说明以后会重新问，实际：\n" + allowScreen);
        assertFalse(allowScreen.contains("放宽权限"),
                "删 allow 不该说放宽权限——那会把两个方向的后果混为一谈，实际：\n" + allowScreen);
    }

    @Test
    @DisplayName("Enter 在列表态什么都不删——删除键是 d，不该和「移动光标后顺手回车」共用")
    void enterInListModeDeletesNothing(@TempDir Path root) {
        PermsStub h = stubWithThreeRules();
        CodeTuiView v = new CodeTuiView(new ConversationState(), h, root);
        openPanel(v);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(List.of(), h.removed, "列表态的 Enter 不得触发删除");
        assertEquals(3, h.rules.size());
        assertTrue(v.pickingPermsForTest(), "Enter 也不该关掉面板");
        String screen = ViewScreen.of(v);
        assertFalse(screen.contains("确认删除"), "Enter 更不该进确认态，实际：\n" + screen);
    }

    @Test
    @DisplayName("零规则时也能打开，给出配置指引而不是空白面板")
    void opensWithNoRules(@TempDir Path root) {
        PermsStub h = new PermsStub();
        CodeTuiView v = new CodeTuiView(new ConversationState(), h, root);

        openPanel(v);

        assertTrue(v.pickingPermsForTest());
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("permissions.json"), "应指出规则写在哪，实际：\n" + screen);
        // 空列表下按键不得崩（% n 除零）
        v.feedKeyForTest(KeyEvent.ofChar('j'));
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        assertEquals(List.of(), h.removed);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertFalse(v.pickingPermsForTest(), "Esc 仍应能关闭");
    }

    @Test
    @DisplayName("Esc 关闭面板")
    void escClosesPanel(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), stubWithThreeRules(), root);
        openPanel(v);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertFalse(v.pickingPermsForTest());
        assertFalse(ViewScreen.of(v).contains("权限规则（"), "面板应已收起");
    }

    @Test
    @DisplayName("非面板态渲染冒烟——scope 每帧 eager 求值，首行不判空会每帧崩渲染线程")
    void rendersWithoutPanelOpen(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), stubWithThreeRules(), root);

        String screen = ViewScreen.of(v);            // 从未打开过面板

        assertFalse(v.pickingPermsForTest());
        assertFalse(screen.contains("权限规则（"), "没开面板就不该有面板内容，实际：\n" + screen);
    }

    @Test
    @DisplayName("删除结果要能看见——面板状态行早于通用 notice 分支 return，必须自己回显")
    void deletionFeedbackIsVisibleInStatusLine(@TempDir Path root) {
        PermsStub h = stubWithThreeRules();
        h.removeResult = false;                       // 落盘失败
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, h, root);
        openPanel(v);
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertTrue(v.permsStatusText().contains("删除失败"),
                "失败必须说出来，实际：" + v.permsStatusText());
        assertTrue(ViewScreen.of(v).contains("删除失败"), "屏幕上也要看得见");
    }
}
