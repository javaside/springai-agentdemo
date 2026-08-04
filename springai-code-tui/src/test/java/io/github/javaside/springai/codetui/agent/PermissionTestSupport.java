package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfig;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;

import java.nio.file.Path;
import java.util.List;

/**
 * 造一个<b>恒定判定</b>的 {@link PermissionEngine}，供权限装饰器层的测试用。
 *
 * <p><b>为什么是「真实构造 + 一条通配规则」而不是子类覆写 {@code decide}</b>：
 * {@code PermissionEngine} 是 {@code final} 的，不能继承。而<b>绝不为了测试好写去掉生产类的
 * {@code final}</b>——那是把一个安全组件的可扩展性作为测试便利的代价。
 * 好在它的判定顺序本身就给了稳定的接缝：{@code 工具名 "*" + pattern null} 的规则无条件命中
 * （见 {@code PermissionRule.matches} 前两个分支），而 deny(第 1 步) / ask(第 4 步) / allow(第 5 步)
 * 各自排在模式默认（第 6 步）之前，故一条通配规则就能把整台引擎钉成恒定判定。
 */
final class PermissionTestSupport {

    private PermissionTestSupport() { }

    /**
     * 恒 ASK。
     *
     * <p>注意 BYPASS 档<b>造不出</b> ASK——那一档在 deny 规则之后就地放行、根本不走到 ask 规则，
     * 这正是「BYPASS 不需要任何后台特殊处理」的原因，故本方法不供 BYPASS 使用。
     */
    static PermissionEngine engineAlwaysAsk(PermissionMode mode) {
        return fixed(mode, PermissionBehavior.ASK);
    }

    static PermissionEngine engineAlwaysAllow(PermissionMode mode) {
        return fixed(mode, PermissionBehavior.ALLOW);
    }

    static PermissionEngine engineAlwaysDeny(PermissionMode mode) {
        return fixed(mode, PermissionBehavior.DENY);
    }

    /** {@code *(*)} 规则 = 任意工具的任意调用都命中，behavior 决定恒定结论。 */
    private static PermissionEngine fixed(PermissionMode mode, PermissionBehavior behavior) {
        PermissionRule all = new PermissionRule("*", null, behavior, RuleScope.SESSION);
        return new PermissionEngine(Path.of("."), new PermissionConfig(mode, List.of(all)), mode);
    }
}
