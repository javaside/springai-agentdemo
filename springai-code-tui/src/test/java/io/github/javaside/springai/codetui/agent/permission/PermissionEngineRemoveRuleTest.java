package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code removeRule}：删一条规则后它必须<b>立刻</b>不再生效。
 *
 * <p><b>为什么每条断言都打 {@code decide} 而不只看 {@code effectiveRules()}</b>：
 * 面板是照 {@code effectiveRules()} 渲染的，但真正拦人的是 {@code decide}。
 * 两者若分家——列表里没了、判定里还在——用户看到的就是
 * <b>「面板说删了、实际还拦着」</b>，那是最坏的一种谎：他会以为是别的原因在挡，
 * 从而去改一堆不相干的地方。故这里一律以判定结论为准，列表只作附加断言。
 */
class PermissionEngineRemoveRuleTest {

    private static PermissionEngine engine(Path root) {
        return new PermissionEngine(root, new PermissionConfig(PermissionMode.DEFAULT, List.of()),
                PermissionMode.DEFAULT);
    }

    private static PermissionBehavior mvnTest(PermissionEngine e) {
        return e.decide("Bash", "{\"command\":\"mvn test\"}").behavior();
    }

    @Test
    @DisplayName("删会话规则后立即失效，且从面板的规则视图里消失")
    void removedSessionRuleStopsApplying(@TempDir Path root) {
        PermissionEngine e = engine(root);
        PermissionRule r = PermissionRule.parse("Bash(mvn test:*)",
                PermissionBehavior.ALLOW, RuleScope.SESSION);
        e.addSessionRule(r);
        assertEquals(PermissionBehavior.ALLOW, mvnTest(e), "前置：加上之后本该放行");

        assertTrue(e.removeRule(r));

        assertEquals(PermissionBehavior.ASK, mvnTest(e),
                "删完必须同步从内存规则表摘掉，否则重启前它还在生效");
        assertFalse(e.effectiveRules().contains(r), "也要从面板据以渲染的视图里消失");
    }

    @Test
    @DisplayName("删落盘规则：文件与内存同时更新，判定立刻改变")
    void removedFileRuleIsGoneFromBoth(@TempDir Path root) {
        PermissionEngine e = engine(root);
        PermissionRule r = PermissionRule.parse("Bash(mvn test:*)",
                PermissionBehavior.ALLOW, RuleScope.PROJECT);
        assertTrue(e.addPersistentRule(r), "前置：先真的写进项目层文件");
        assertEquals(PermissionBehavior.ALLOW, mvnTest(e));

        assertTrue(e.removeRule(r));

        assertEquals(PermissionBehavior.ASK, mvnTest(e));
        assertFalse(e.effectiveRules().contains(r));
    }

    /**
     * <b>这条钉的是行为，不是 {@code foldedTwins.remove} 那一行。</b>
     *
     * <p>实测过：把那行清理停掉，本用例<b>照样绿</b>——因为孪生是按规则做键的缓存，
     * 只在匹配「仍在规则表里」的规则时经 {@code computeIfAbsent} 查到，规则一摘掉就再也查不到。
     * 那行是缓存卫生，不是正确性护栏（引擎里原本的注释把它写成了护栏，已一并更正）。
     *
     * <p>留着这条用例仍有价值：它钉住「删掉 deny 之后，<b>折叠形态</b>也跟着不再命中」。
     * 若将来有人让孪生表变成权威来源（比如匹配时遍历 foldedTwins 而不是遍历规则表），
     * 这条会立刻响。<b>但别把它当成那行清理的守卫。</b>
     */
    @Test
    @DisplayName("删 deny 之后，折叠形态也跟着不再命中")
    void removedDenyStopsMatchingFoldedForm(@TempDir Path root) {
        PermissionEngine e = engine(root);
        PermissionRule deny = PermissionRule.parse("Write(/etc/**)",
                PermissionBehavior.DENY, RuleScope.SESSION);
        e.addSessionRule(deny);
        // 大小写不同的目标只能走折叠这一路命中
        assertEquals(PermissionBehavior.DENY,
                e.decide("Write", "{\"filePath\":\"/ETC/passwd\"}").behavior(),
                "前置：孪生生效时，/ETC 走折叠这一路被拒");

        assertTrue(e.removeRule(deny));

        assertFalse(PermissionBehavior.DENY
                        == e.decide("Write", "{\"filePath\":\"/ETC/passwd\"}").behavior(),
                "删掉的 deny 若在折叠这一路还命中，面板与判定就分家了");
    }

    @Test
    @DisplayName("同一条 DSL 分处两层时，只删选中那一层（record 的 equals 含 scope，摘不错层）")
    void sameDslInTwoLayersIsRemovedPerLayer(@TempDir Path root) {
        PermissionEngine e = engine(root);
        PermissionRule session = PermissionRule.parse("Bash(mvn test:*)",
                PermissionBehavior.ALLOW, RuleScope.SESSION);
        PermissionRule project = PermissionRule.parse("Bash(mvn test:*)",
                PermissionBehavior.ALLOW, RuleScope.PROJECT);
        e.addSessionRule(session);
        assertTrue(e.addPersistentRule(project));

        assertTrue(e.removeRule(session), "只删会话那条");

        assertTrue(e.effectiveRules().contains(project),
                "项目层那条 DSL 相同但 scope 不同，不该被一起摘掉");
        assertEquals(PermissionBehavior.ALLOW, mvnTest(e), "项目层那条仍在，故仍然放行");
    }

    @Test
    @DisplayName("删不存在的规则 → false；null → false 且不抛")
    void removingUnknownRuleIsFalse(@TempDir Path root) {
        PermissionEngine e = engine(root);
        assertFalse(e.removeRule(PermissionRule.parse("Bash(nope:*)",
                PermissionBehavior.ALLOW, RuleScope.SESSION)));
        assertFalse(e.removeRule(null));
    }
}
