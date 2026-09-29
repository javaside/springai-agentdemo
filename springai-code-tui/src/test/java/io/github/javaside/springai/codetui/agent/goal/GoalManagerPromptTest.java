package io.github.javaside.springai.codetui.agent.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5 钉子：滚动记录（≤8 FIFO、插话原文、2000 码点安全尾部截断）与自动轮 prompt 完整文案
 * （spec §4.1，语言跟随条件：含 CJK → 中文模板，否则英文模板；首轮结论/空账本占位）。
 * brief 骨架 5 组注释全部展开为真断言。
 */
class GoalManagerPromptTest {

    /** brief 基线：maxTurns=25, stalledLimit=3, budget=0(关), gap=0, errorRetry=2, eval=2, protocol=3。 */
    private static GoalConfig cfg() {
        return new GoalConfig(25, 3, 0, 0, 2, 2, 3, 60, "");
    }

    @Test
    void autoTurnPromptChineseCondition() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("迁移 AuthService，mvn -pl server test 退出码 0");
        String p = gm.takeAutoTurn();        // turnsUsed=1
        assertTrue(p.startsWith("[goal 继续 1/25]"));
        assertTrue(p.contains("目标：迁移 AuthService，mvn -pl server test 退出码 0"));
        assertTrue(p.contains("评估器结论（上一轮）：（首轮）"));   // 首轮占位
        assertTrue(p.contains("累积进度：（尚无）"));               // 空账本占位
        assertTrue(p.contains("不要重复已完成的工作"));
        assertTrue(p.contains("可复验的审计证据"));
        assertTrue(GoalText.isContinueMessage(p));
    }

    @Test
    void autoTurnPromptEnglishConditionUsesEnglishScaffold() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("migrate AuthService, tests green");   // 无 CJK → 英文模板
        String p = gm.takeAutoTurn();
        assertTrue(p.startsWith("[goal 继续 1/25]"));       // 标记不随语言变（GoalText 契约）
        assertTrue(p.contains("Goal: migrate AuthService, tests green"));
        assertTrue(p.contains("Evaluator verdict (previous turn): (first turn)"));
        assertTrue(p.contains("Cumulative progress: (none yet)"));
        assertTrue(p.contains("Do not repeat completed work"));
        assertFalse(p.contains("目标："));                  // 中文模板不漏进英文 prompt
        assertTrue(GoalText.isContinueMessage(p));
    }

    @Test
    void promptCarriesLastVerdictAndLedger() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("迁移 AuthService");
        gm.takeAutoTurn();                                   // 第 1 轮
        long e = gm.beginEvaluation();                       // 评估在飞（锁存对话边界）
        assertNotEquals(-1L, e);
        gm.onVerdict(e, new GoalVerdict(
                GoalVerdict.Outcome.UNSATISFIED, "还有 4 个调用点", false, "✓A ✗B", "raw"));
        // 评估输入快照：结论与账本如实传递（stalled=false → 连击 0）
        EvaluationInput in = gm.buildEvaluationInput();
        assertEquals("迁移 AuthService", in.condition());
        assertEquals(1, in.turn());
        assertEquals(25, in.maxTurns());
        assertEquals(0, in.stalledStreak());
        assertEquals("✓A ✗B", in.stateLedger());
        // 下一 prompt 携带上一轮结论与最新账本，且不再是「（首轮）」
        String p = gm.takeAutoTurn();                        // 第 2 轮
        assertTrue(p.startsWith("[goal 继续 2/25]"));
        assertTrue(p.contains("评估器结论（上一轮）：还有 4 个调用点"));
        assertTrue(p.contains("累积进度：✓A ✗B"));
        assertFalse(p.contains("（首轮）"));
    }

    @Test
    void autoTurnPromptUnlimitedMaxTurnsRendersInfinity() {
        GoalManager gm = new GoalManager(new GoalConfig(0, 3, 0, 0, 2, 2, 3, 60, ""), null);
        gm.activate("迁移 AuthService");
        String p = gm.takeAutoTurn();
        assertTrue(p.startsWith("[goal 继续 1/∞]"), "无上限时前缀写 ∞，实际：" + p);
        assertTrue(GoalText.isContinueMessage(p), "HistoryReplay 识别契约对 ∞ 前缀同样成立");
        assertEquals(0, gm.buildEvaluationInput().maxTurns(), "评估输入如实携带 0=无上限");
    }

    @Test
    void rollingHistoryCapsAt8AndCarriesInterjection() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("g");
        gm.takeAutoTurn();                                   // turnsUsed=1
        for (int i = 1; i <= 9; i++) {
            gm.recordTurnMaterial(new GoalTurnMaterial("素材" + i, i,
                    i == 5 ? "等一下，先跑测试再改" : null));
        }
        EvaluationInput in = gm.buildEvaluationInput();
        assertEquals(8, in.recentTurns().size());            // ≤8：第 9 条挤掉最旧
        assertEquals("素材2", in.recentTurns().get(0).assistantTail());   // 素材1 已被挤掉
        assertEquals("素材9", in.recentTurns().get(7).assistantTail());   // 最新在末尾
        assertEquals(1, in.recentTurns().get(0).turn());     // 轮次快照如实
        assertNull(in.recentTurns().get(0).evalReason());    // 尚无 verdict → 首轮结论为 null
        // 用户插话原文对评估器可见（纠偏上下文）
        assertEquals("等一下，先跑测试再改",
                in.recentTurns().stream().filter(r -> r.toolCallCount() == 5)
                        .findFirst().orElseThrow().userInterjection());
        // activate 换代：滚动记录清空（新 goal 不见旧轮次）
        gm.activate("g2");
        assertEquals(0, gm.buildEvaluationInput().recentTurns().size());
    }

    @Test
    void assistantTailTruncatedAt2000() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("g");
        gm.takeAutoTurn();
        // 3000 字符素材，代理对（𝄞=U+1D11E，占 2 字符）恰好横跨朴素 2000 截断点：
        // 索引 999-1000 为代理对 → 朴素 substring(len-2000) 将以低代理开头（劈对）
        String material = "x".repeat(999) + "𝄞" + "y".repeat(1999);
        assertEquals(3000, material.length());
        gm.recordTurnMaterial(new GoalTurnMaterial(material, 0, null));
        String tail = gm.buildEvaluationInput().recentTurns().get(0).assistantTail();
        assertTrue(tail.length() <= 2000, "tail ≤2000 字符，实际 " + tail.length());
        assertFalse(tail.isEmpty());
        assertFalse(Character.isLowSurrogate(tail.charAt(0)), "不得以孤立低代理开头（劈对）");
        assertTrue(tail.endsWith("y".repeat(64)));           // 尾部内容保留
        assertFalse(tail.contains("x"));                     // 超预算的头部被截掉
        // 未超预算的素材原样保留（同一内容直通）
        gm.recordTurnMaterial(new GoalTurnMaterial("短素材", 0, null));
        assertEquals("短素材", gm.buildEvaluationInput().recentTurns().get(1).assistantTail());
    }
}
