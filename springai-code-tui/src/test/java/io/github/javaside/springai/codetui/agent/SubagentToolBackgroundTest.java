package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentToolBackgroundTest {

    /** 用 LinkedHashMap 而非 Map.of：迭代序固定，"取错 spec"类缺陷才能稳定被抓到。 */
    private static Map<String, SubagentSpec> specs() {
        Map<String, SubagentSpec> m = new LinkedHashMap<>();
        m.put("explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        m.put("plan", new SubagentSpec("plan", "d", "sys", List.of(), List.of(), null, List.of()));
        return m;
    }

    private static SubagentTool.SubagentCall call(String type, boolean bg) {
        return new SubagentTool.SubagentCall("do things", "the prompt", type, bg);
    }

    @Test
    void backgroundFalseGoesToForegroundDispatcher() {
        List<String> fg = new ArrayList<>();
        List<String> bg = new ArrayList<>();
        var fn = SubagentTool.function(specs(),
                (spec, prompt, desc, turn) -> { fg.add(spec.name()); return "fg:" + spec.name(); },
                (spec, prompt, desc) -> { bg.add(spec.name()); return "bg:" + spec.name(); });
        assertEquals("fg:explore", fn.apply(call("explore", false)));
        assertEquals(List.of("explore"), fg);
        assertTrue(bg.isEmpty());
    }

    @Test
    void backgroundTrueGoesToBackgroundDispatcher() {
        List<String> fg = new ArrayList<>();
        List<String> bg = new ArrayList<>();
        var fn = SubagentTool.function(specs(),
                (spec, prompt, desc, turn) -> { fg.add(spec.name()); return "fg:" + spec.name(); },
                (spec, prompt, desc) -> { bg.add(spec.name()); return "bg:" + spec.name(); });
        assertEquals("bg:plan", fn.apply(call("plan", true)));
        assertEquals(List.of("plan"), bg);
        assertTrue(fg.isEmpty(), "background=true 绝不能落到前台派发器上——那会重新变成阻塞");
    }

    @Test
    void nullBackgroundDefaultsToForeground() {
        var fn = SubagentTool.function(specs(),
                (spec, prompt, desc, turn) -> "fg", (spec, prompt, desc) -> "bg");
        // 模型可能整个省略该字段 → Jackson 给 null。默认必须是前台（向后兼容）
        assertEquals("fg", fn.apply(new SubagentTool.SubagentCall("d", "p", "explore", null)));
    }

    @Test
    void legacyTwoArgCreateRejectsBackgroundWithClearMessage() {
        // 回显桩 / 测试桩用的 2 参 create：没有后台派发器，请求后台时要说清楚而不是静默跑前台
        var fn = SubagentTool.function(specs(), (spec, prompt, desc, turn) -> "fg");
        String out = fn.apply(call("explore", true));
        assertTrue(out.contains("后台模式不可用"), out);
    }

    @Test
    void parallelBackgroundDispatchesEachAndReportsAllTaskIds() {
        var fn = SubagentTool.batchFunction(specs(),
                (dispatches, turn) -> { throw new AssertionError("background 批次不该走前台并发路径"); },
                (spec, prompt, desc) -> "已在后台启动：task_" + spec.name());
        String out = fn.apply(new SubagentTool.ParallelCall(
                List.of(call("explore", true), call("plan", true)), true));
        assertTrue(out.contains("task_explore"));
        assertTrue(out.contains("task_plan"));
    }

    @Test
    void parallelBackgroundReportsPerItemRejectionWithoutFailingTheBatch() {
        var fn = SubagentTool.batchFunction(specs(),
                (dispatches, turn) -> { throw new AssertionError("不该走前台"); },
                (spec, prompt, desc) -> spec.name().equals("plan")
                        ? "后台队列已满，本次未启动。"
                        : "已在后台启动：task_explore");
        String out = fn.apply(new SubagentTool.ParallelCall(
                List.of(call("explore", true), call("plan", true)), true));
        assertTrue(out.contains("task_explore"), "入得进的照常启动");
        assertTrue(out.contains("队列已满"), "入不进的单独标注，不整批拒绝");
    }

    @Test
    void parallelBackgroundFalseStillUsesConcurrentForegroundPath() {
        var fn = SubagentTool.batchFunction(specs(),
                (dispatches, turn) -> List.of("r1", "r2"),
                (spec, prompt, desc) -> { throw new AssertionError("background=false 不该走后台"); });
        String out = fn.apply(new SubagentTool.ParallelCall(
                List.of(call("explore", false), call("plan", false)), false));
        assertTrue(out.contains("r1"));
        assertTrue(out.contains("r2"));
    }

    @Test
    void unknownTypeInBackgroundBatchIsIsolatedNotThrown() {
        var fn = SubagentTool.batchFunction(specs(),
                (dispatches, turn) -> { throw new AssertionError("不该走前台"); },
                (spec, prompt, desc) -> "已在后台启动：task_" + spec.name());
        String out = fn.apply(new SubagentTool.ParallelCall(
                List.of(call("explore", true), call("no-such", true)), true));
        assertTrue(out.contains("task_explore"), "一条未知类型不该拖垮另一条");
        assertTrue(out.contains("no-such"));
    }
}
