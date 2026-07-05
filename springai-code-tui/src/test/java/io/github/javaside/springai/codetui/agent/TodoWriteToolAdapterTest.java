package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：薄适配器让模型天然产出的<b>单层</b> {@code {"todos":[...]}} 能被绑定并执行，
 * 而库工具原样注册时要求的是<b>双层</b> {@code {"todos":{"todos":[...]}}}（升级前 TodoWrite 频繁 ✗ 的根因）。
 *
 * <p>依赖本模块以 {@code -parameters} 编译（见 pom）——否则适配器参数名退化为 {@code arg0}，schema 属性名对不上。
 */
class TodoWriteToolAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单层 JSON —— 正是模型（和 Claude Code 真实 TodoWrite）天然产出的形状。 */
    private static final String SINGLE_LEVEL =
            "{\"todos\":[{\"content\":\"探索模块\",\"activeForm\":\"探索模块中\",\"status\":\"in_progress\"}]}";

    private static ToolCallback adapterCallback(AtomicReference<Todos> captured) {
        TodoWriteTool delegate = TodoWriteTool.builder()
                .todoEventHandler(captured::set)   // 复用库校验并回捕事件
                .build();
        return ToolCallbacks.from(new TodoWriteToolAdapter(delegate))[0];
    }

    @Test
    void singleLevelJsonBindsAndFiresEvent() {
        AtomicReference<Todos> captured = new AtomicReference<>();
        ToolCallback cb = adapterCallback(captured);

        String result = cb.call(SINGLE_LEVEL);

        assertTrue(result.contains("modified successfully"), "应返回库工具的成功串：" + result);
        assertNotNull(captured.get(), "事件处理器应收到 Todos（onTodoUpdated 链路）");
        assertEquals(1, captured.get().todos().size());
        assertEquals("探索模块", captured.get().todos().get(0).content());
        assertEquals(Todos.Status.in_progress, captured.get().todos().get(0).status());
    }

    @Test
    void toolNameStaysTodoWrite() {
        assertEquals("TodoWrite", adapterCallback(new AtomicReference<>()).getToolDefinition().name(),
                "工具注册名必须仍是 TodoWrite（子 agent allow/deny 过滤、模型指引都按此名）");
    }

    @Test
    void generatedSchemaIsSingleLevel() {
        String schema = adapterCallback(new AtomicReference<>()).getToolDefinition().inputSchema();
        JsonNode todosProp = MAPPER.readTree(schema).path("properties").path("todos");
        // 单层：todos 属性本身就是数组；双层缺陷时它会是 {type:object, properties:{todos:array}}。
        assertEquals("\"array\"", todosProp.path("type").toString(),
                "todos 属性应直接是 array（单层 schema），实际 schema=" + schema);
    }

    @Test
    void doubleLevelJsonNowRejected() {
        // 反证：升级前「正确」的双层形状，如今喂给适配器反而不再匹配（适配器只认单层）。
        String doubleLevel =
                "{\"todos\":{\"todos\":[{\"content\":\"x\",\"activeForm\":\"x\",\"status\":\"pending\"}]}}";
        ToolCallback cb = adapterCallback(new AtomicReference<>());
        assertThrows(RuntimeException.class, () -> cb.call(doubleLevel),
                "双层 JSON 对单层 schema 应绑定失败（证明形状确实被摊平了）");
    }
}
