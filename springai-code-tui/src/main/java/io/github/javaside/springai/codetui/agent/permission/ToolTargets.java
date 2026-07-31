package io.github.javaside.springai.codetui.agent.permission;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 从工具入参 JSON 里抽出「判定目标」——面板显示 + 规则匹配 + 危险检查都用它。
 *
 * <p><b>降级契约</b>：JSON 非法 / 字段缺失 / 字段不是字符串 → 返回 {@code null}，<b>绝不抛异常</b>。
 * 目标为 null 时引擎按「无法核实」处理（带 pattern 的规则不命中，落到模式默认/兜底 ASK）。
 *
 * <p><b>UNKNOWN 工具</b>（含全部 MCP 工具）无登记字段，返回<b>整串入参</b>——
 * 面板上原样展示给人看，规则也可用 {@code 工具名(整串)} 精确放行。
 */
public final class ToolTargets {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolTargets() {
    }

    public static String extract(String toolName, String toolInput) {
        ToolRegistry.Entry entry = ToolRegistry.lookup(toolName);
        if (entry.category() == ToolCategory.UNKNOWN) {
            return toolInput;                       // 未登记：整串入参即目标
        }
        if (entry.targetField() == null || toolInput == null) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(toolInput);
            JsonNode v = root.get(entry.targetField());
            // Jackson 3 的 asString() 对非文本节点会抛，必须先判 isString
            return (v != null && v.isString()) ? v.stringValue() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
