package io.github.javaside.springai.codetui.agent.subagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReloadableSubagentTool：实例恒定代理 + {@code reload(specs)} 原子换 delegate 的热重载语义
 * （spec §6.2）。
 *
 * <p>核心保证：<b>同一个 ToolCallback 实例</b>在 reload 后 {@code getToolDefinition()} 现取到
 * 新 roster，且 {@code call()} 的路由（subagent_type → spec）也换到新 specs——「只重建描述、
 * 复用旧路由 map」的半截实现必须在此红（roster 与路由都在 build 期烘焙进 delegate，见被测类
 * javadoc）。
 *
 * <p>delegate 用 {@link SubagentTool#create} 造真工具（非手桩，走 FunctionToolCallback 的
 * JSON 反序列化与结果转换全链路），stub Dispatcher 返回 {@code spec.name()}——call 的返回值
 * 即「实际路由到了哪个 spec」的直接证据。代理构造不建初始 delegate（由 T3 的 SubagentReloader
 * 构造时首次 reload 喂入），故每个用例先手动 reload 一次模拟该初始装载。
 */
class ReloadableSubagentToolTest {

    /** 路由证明桩：返回 spec.name()，call 结果直接暴露路由命中的 spec。 */
    private static final SubagentTool.Dispatcher STUB = (spec, prompt, desc, turnId) -> spec.name();

    /** 与生产装配同形态的 factory：每次调用产出完整重建的 Task 工具（新描述 roster + 新路由 map）。 */
    private static final Function<Map<String, SubagentSpec>, ToolCallback> FACTORY =
            specs -> SubagentTool.create(specs, STUB);

    /** 手造最小 spec（形态对齐 SubagentToolTest：name/description/systemPrompt + 空工具清单）。 */
    private static SubagentSpec spec(String name) {
        return new SubagentSpec(name, "does " + name + " things", "sys", List.of(), List.of(), null, List.of());
    }

    /** 用 LinkedHashMap 而非 Map.of：迭代序固定（同 SubagentToolBackgroundTest），Available 列表断言可复现。 */
    private static Map<String, SubagentSpec> specs(String... names) {
        Map<String, SubagentSpec> m = new LinkedHashMap<>();
        for (String name : names) {
            m.put(name, spec(name));
        }
        return m;
    }

    /**
     * Task 工具的 JSON 入参。{@code model} / {@code run_in_background} 是
     * {@code @ToolParam(required = false)}，省略时 Jackson 给 null；但不能传空串——
     * {@code FunctionToolCallback.call} 有 {@code Assert.hasText(toolInput)}。
     */
    private static String json(String subagentType) {
        return "{\"description\":\"d\",\"prompt\":\"p\",\"subagent_type\":\"" + subagentType + "\"}";
    }

    /**
     * 期望的 call 返回值：FunctionToolCallback 的默认结果转换器（DefaultToolCallResultConverter）
     * 走 {@code JsonHelper.toJson(result, true)}——只对合法 JSON 字符串原样透传，裸名字会被
     * Jackson 序列化<b>带双引号</b>返回（2.0.1 源码实测：JsonHelper L138-143）。stub 返回
     * {@code spec.name()}，故断言按带引号形态拼。
     */
    private static String jsonQuoted(String name) {
        return "\"" + name + "\"";
    }

    /** 首次 reload（Reloader 构造时装载）后，roster 来自 factory 产出——模型据此才知道可选 alpha。 */
    @Test
    void initialRosterFromFactory() {
        ReloadableSubagentTool tool = new ReloadableSubagentTool(FACTORY);
        tool.reload(specs("alpha"));

        assertEquals("Task", tool.getToolDefinition().name(), "代理应转发 delegate 的工具定义（名 Task）");
        assertTrue(tool.getToolDefinition().description().contains("- alpha: does alpha things"),
                "首次装载后 roster 应含 alpha 行");
    }

    /**
     * 杀虫关键：reload 必须换「路由」而非只换描述。半截实现（重建描述、复用旧路由 map）下
     * reload 后描述含 beta，但 call(beta) 仍查旧 map → 抛未知类型错误，返回值断言必红。
     */
    @Test
    void reloadSwapsRoutingNotJustDescription() {
        ReloadableSubagentTool tool = new ReloadableSubagentTool(FACTORY);
        tool.reload(specs("alpha"));
        assertEquals(jsonQuoted("alpha"), tool.call(json("alpha")), "reload 前路由应命中 alpha");

        tool.reload(specs("beta"));

        String desc = tool.getToolDefinition().description();
        assertTrue(desc.contains("- beta: does beta things"), "reload 后 roster 应含 beta：\n" + desc);
        assertFalse(desc.contains("alpha"), "reload 后 roster 不应再含 alpha：\n" + desc);
        assertEquals(jsonQuoted("beta"), tool.call(json("beta")),
                "reload 后路由必须真的换到新 specs——只换描述不换路由的半截实现在此必红");
    }

    /**
     * 未知类型的错误信息里 Available 列表随 reload 更新（路由 map 真换了）。2.0.1 的
     * FunctionToolCallback.callMethod 会把路由函数的 RuntimeException 包成
     * ToolExecutionException，但其构造即 {@code super(cause.getMessage(), cause)}——原消息
     * 逐字透传，故断言 exception message contains 即可（spec §6.2 / plan T2 的既定断言法）。
     */
    @Test
    void unknownTypeListsCurrentRoster() {
        ReloadableSubagentTool tool = new ReloadableSubagentTool(FACTORY);
        tool.reload(specs("alpha"));
        tool.reload(specs("beta", "gamma"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> tool.call(json("no-such")));
        String msg = ex.getMessage();
        assertTrue(msg.contains("beta") && msg.contains("gamma"),
                "Available 列表应是当前 specs 的 key 集（beta, gamma）：\n" + msg);
        assertFalse(msg.contains("alpha"), "旧 specs 的 key 不应残留在 Available 列表：\n" + msg);
    }
}
