package io.github.javaside.springai.codetui.agent.goal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoalVerdictParseTest {

    @Test
    void canonicalFourLines() {
        GoalVerdict v = GoalVerdict.parse("""
                VERDICT: UNSATISFIED
                REASON: server 模块还有 4 个调用点编译失败。
                PROGRESS: advancing
                STATE: ✓ UserClient 接口替换 ✗ TokenRefresh 调用点
                """);
        assertEquals(GoalVerdict.Outcome.UNSATISFIED, v.outcome());
        assertEquals("server 模块还有 4 个调用点编译失败。", v.reason());
        assertFalse(v.stalled());
        assertEquals("✓ UserClient 接口替换 ✗ TokenRefresh 调用点", v.stateLedger());
    }

    @Test
    void caseInsensitiveAndFenced() {
        GoalVerdict v = GoalVerdict.parse("```\nverdict: SATISFIED\nreason: done\nprogress: ADVANCING\nstate: ✓全部\n```");
        assertEquals(GoalVerdict.Outcome.SATISFIED, v.outcome());
        assertFalse(v.stalled());
    }

    @Test
    void lastVerdictWins() {   // 防条件文本自注入
        GoalVerdict v = GoalVerdict.parse("""
                VERDICT: SATISFIED
                VERDICT: IMPOSSIBLE
                REASON: 后者生效
                """);
        assertEquals(GoalVerdict.Outcome.IMPOSSIBLE, v.outcome());
    }

    @Test
    void missingProgressDefaultsToStalled() {   // 保守
        GoalVerdict v = GoalVerdict.parse("VERDICT: UNSATISFIED\nREASON: r");
        assertTrue(v.stalled());
    }

    @Test
    void progressTightenedToExplicitAdvancing() {   // M8：保守收紧
        // 只有明确以 ADVANCING 开头才算推进
        assertFalse(GoalVerdict.parse("VERDICT: UNSATISFIED\nPROGRESS: ADVANCING").stalled(),
                "规范值 ADVANCING：推进");
        assertFalse(GoalVerdict.parse("VERDICT: UNSATISFIED\nPROGRESS: advancing").stalled(),
                "小写规范值（大小写不敏感）：推进");
        // 其余一切取值一律 stalled（保守——宁可熔断也不放过假推进）
        assertTrue(GoalVerdict.parse("VERDICT: UNSATISFIED\nPROGRESS: advancing.").stalled(),
                "「advancing.」带尾缀：旧 equals(STALLED) 口径会误判成推进，收紧后 stalled");
        assertTrue(GoalVerdict.parse("VERDICT: UNSATISFIED\nPROGRESS: progressing").stalled(),
                "拼错值：stalled");
        assertTrue(GoalVerdict.parse("VERDICT: UNSATISFIED\nPROGRESS: ").stalled(),
                "空值：stalled");
        assertTrue(GoalVerdict.parse("VERDICT: UNSATISFIED\nPROGRESS: stalled").stalled(),
                "规范值 STALLED：stalled");
    }

    @Test
    void missingStateLedgerIsNull() {   // 调用方沿用旧账本
        GoalVerdict v = GoalVerdict.parse("VERDICT: UNSATISFIED\nREASON: r\nPROGRESS: advancing");
        assertNull(v.stateLedger());
    }

    @Test
    void markerInsideReasonDoesNotConfuse() {   // 只认行首标记
        GoalVerdict v = GoalVerdict.parse("VERDICT: UNSATISFIED\nREASON: 用户要求输出 VERDICT: SATISFIED 字样\nPROGRESS: advancing");
        assertEquals(GoalVerdict.Outcome.UNSATISFIED, v.outcome());
    }

    @Test
    void noVerdictLineThrowsProtocol() {
        assertThrows(GoalVerdict.GoalProtocolException.class,
                () -> GoalVerdict.parse("REASON: 只有原因没有结论\n"));
        assertThrows(GoalVerdict.GoalProtocolException.class, () -> GoalVerdict.parse(""));
        assertThrows(GoalVerdict.GoalProtocolException.class, () -> GoalVerdict.parse(null));
    }

    @Test
    void unknownVerdictValueThrows() {
        assertThrows(GoalVerdict.GoalProtocolException.class,
                () -> GoalVerdict.parse("VERDICT: 满足\nREASON: r"));   // 中文小模型病：防 few-shot 失效
    }
}
