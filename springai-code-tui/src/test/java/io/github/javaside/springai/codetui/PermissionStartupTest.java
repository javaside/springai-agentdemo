package io.github.javaside.springai.codetui;

import io.github.javaside.springai.codetui.agent.AgentTools;
import io.github.javaside.springai.codetui.agent.CodingAgent;
import io.github.javaside.springai.codetui.agent.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfig;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动参数 {@code --dangerously-skip-permissions} 与 {@code SubmitHandler} 权限门面。
 *
 * <p><b>为什么本类在 {@code codetui} 包而不是 {@code codetui.agent}</b>：
 * {@code CodeTuiApplication.hasBypassFlag} 是包私有的，而「唯一能进 BYPASS 的入口」
 * 不值得为了测试改成 public。门面那几条断言走的都是公开 API，放这里一样测得到。
 */
class PermissionStartupTest {

    private static PermissionEngine engine(Path root, PermissionMode mode, boolean bypassAllowed) {
        return new PermissionEngine(root, PermissionConfig.empty(), mode, bypassAllowed);
    }

    /** 只装权限门面所需的最小 CodingAgent（其余协作者一律 null，本类不触发 submit）。 */
    private static CodingAgent agentWith(PermissionEngine engine) {
        return new CodingAgent(null, Map.of(), new ConversationState(), "sid", new AtomicLong(),
                null, null, null, List.of(), null, null, null, null, null, null, engine);
    }

    @Test
    @DisplayName("--dangerously-skip-permissions 被识别")
    void recognisesBypassFlag() {
        assertTrue(CodeTuiApplication.hasBypassFlag(new String[]{"--dangerously-skip-permissions"}));
        assertTrue(CodeTuiApplication.hasBypassFlag(new String[]{"-c", "--dangerously-skip-permissions"}));
    }

    @Test
    @DisplayName("没带该参数时不开 BYPASS（默认安全）")
    void defaultsToNoBypass() {
        assertFalse(CodeTuiApplication.hasBypassFlag(new String[]{}));
        assertFalse(CodeTuiApplication.hasBypassFlag(new String[]{"-c"}));
        assertFalse(CodeTuiApplication.hasBypassFlag(new String[]{"--dangerously-skip"}),
                "前缀相近的参数不得误判");
        assertFalse(CodeTuiApplication.hasBypassFlag(new String[]{"--dangerously-skip-permissions=false"}),
                "带值形式不是本参数，不得误判");
    }

    @Test
    @DisplayName("--permission-mode 解析：认 plan/default/acceptEdits，大小写不敏感")
    void parsesPermissionMode() {
        assertEquals(PermissionMode.PLAN,
                CodeTuiApplication.startupMode(new String[]{"--permission-mode", "plan"}));
        assertEquals(PermissionMode.ACCEPT_EDITS,
                CodeTuiApplication.startupMode(new String[]{"--permission-mode", "acceptEdits"}));
        assertEquals(PermissionMode.ACCEPT_EDITS,
                CodeTuiApplication.startupMode(new String[]{"--permission-mode", "ACCEPT_EDITS"}));
        assertEquals(PermissionMode.DEFAULT,
                CodeTuiApplication.startupMode(new String[]{"--permission-mode", "default"}));
        assertEquals(PermissionMode.PLAN,
                CodeTuiApplication.startupMode(new String[]{"--permission-mode=plan"}), "= 形式也要认");
    }

    @Test
    @DisplayName("不给、给错、或想用它进 BYPASS —— 一律回退 null（由调用方落到配置的 defaultMode）")
    void rejectsBadPermissionMode() {
        assertNull(CodeTuiApplication.startupMode(new String[]{}));
        assertNull(CodeTuiApplication.startupMode(new String[]{"--permission-mode"}), "缺值不得读到越界");
        assertNull(CodeTuiApplication.startupMode(new String[]{"--permission-mode", "banana"}));
        assertNull(CodeTuiApplication.startupMode(new String[]{"--permission-mode", "bypass"}),
                "BYPASS 只能由 --dangerously-skip-permissions 进；这里认了就等于开了第二个后门");
        assertNull(CodeTuiApplication.startupMode(new String[]{"--permission-modex", "plan"}),
                "前缀相近的参数不得误判");
        assertNull(CodeTuiApplication.startupMode(new String[]{"--permission-mode", "--continue"}),
                "下一个参数是另一个选项时当缺值：否则模式被静默忽略，而 --continue 照常生效，"
                        + "用户看不出自己漏写了值");
    }

    @Test
    @DisplayName("SubmitHandler 的权限默认实现是安全空值（回显桩/测试桩可省略）")
    void submitHandlerDefaults() {
        SubmitHandler stub = new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
        };
        assertTrue(stub.permissionRules().isEmpty());
        // 默认模式非空，供状态栏显示不至于 NPE
        assertNotNull(stub.permissionMode());
        assertNotNull(stub.cyclePermissionMode(), "循环的默认实现也不得返回 null");
    }

    @Test
    @DisplayName("CodingAgent 的门面直通引擎：读模式 / 循环模式 / 列规则")
    void facadeDelegatesToEngine(@TempDir Path root) {
        PermissionEngine engine = new PermissionEngine(root,
                new PermissionConfig(PermissionMode.DEFAULT,
                        List.of(new PermissionRule("Bash", "git status:*",
                                PermissionBehavior.ALLOW, RuleScope.PROJECT))),
                PermissionMode.DEFAULT, false);
        CodingAgent agent = agentWith(engine);

        assertEquals(PermissionMode.DEFAULT, agent.permissionMode());
        assertEquals(PermissionMode.ACCEPT_EDITS, agent.cyclePermissionMode(), "Shift+Tab 应循环到下一个模式");
        assertEquals(PermissionMode.ACCEPT_EDITS, agent.permissionMode(), "循环后读回的必须是新模式");
        assertEquals(PermissionMode.ACCEPT_EDITS, engine.mode(),
                "门面切完模式，工具那一侧读的引擎必须同步（不然状态栏说一套、工具做另一套）");
        assertEquals(1, agent.permissionRules().size());
        assertEquals("Bash(git status:*)", agent.permissionRules().get(0).toDsl());
    }

    @Test
    @DisplayName("未获授权时门面也进不了 BYPASS（引擎那道闸门不能被门面绕过）")
    void facadeCannotEnterBypassWithoutFlag(@TempDir Path root) {
        CodingAgent agent = agentWith(engine(root, PermissionMode.ACCEPT_EDITS, false));

        assertNotEquals(PermissionMode.BYPASS, agent.cyclePermissionMode(),
                "没带 --dangerously-skip-permissions 时 BYPASS 不该在循环里");
    }

    @Test
    @DisplayName("/clear 开新会话时清掉会话规则（「本会话不再问」不跨会话继承）")
    void clearContextClearsSessionRules(@TempDir Path root) {
        PermissionEngine engine = engine(root, PermissionMode.DEFAULT, false);
        engine.addSessionRule(new PermissionRule("Bash", "ls:*", PermissionBehavior.ALLOW, RuleScope.SESSION));
        CodingAgent agent = agentWith(engine);
        assertEquals(1, agent.permissionRules().size(), "前置条件：会话规则已在");

        agent.clearContext();

        assertTrue(agent.permissionRules().isEmpty(),
                "换会话后旧会话的「本会话允许」必须失效，否则用户以为清干净了其实没有");
    }

    @Test
    @DisplayName("没有引擎的桩路径（旧构造重载）不 NPE，退回默认值")
    void stubPathWithoutEngineIsSafe() {
        CodingAgent agent = agentWith(null);

        assertEquals(PermissionMode.DEFAULT, agent.permissionMode());
        assertEquals(PermissionMode.DEFAULT, agent.cyclePermissionMode());
        assertTrue(agent.permissionRules().isEmpty());
        agent.clearContext();   // 不该炸
    }

    @Test
    @DisplayName("门面背后就是 AgentRuntime 暴露的那个引擎（UI 切模式要对工具生效）")
    void facadeSharesRuntimeEngine(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(
                new ProviderRegistry(List.of(new DeepSeekProvider("fake-key"))), root,
                new ConversationState());
        CodingAgent agent = agentWith(rt.permissionEngine());

        PermissionMode after = agent.cyclePermissionMode();

        assertEquals(after, rt.permissionEngine().mode(),
                "门面切完模式，装配点里那些 PermissionCallback 读到的必须是同一个新模式");
    }
}
