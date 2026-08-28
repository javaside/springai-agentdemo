package io.github.javaside.springai.codetui.agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 改名 / 换描述装饰器：只动 ToolDefinition 的 name 与 description，其余全透传。 */
class RenamedToolCallbackTest {

    /** 最小假工具：记录收到的入参，返回固定串。 */
    private static final class FakeTool implements ToolCallback {
        String lastInput;

        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("OriginalName")
                    .description("original description")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override public String call(String toolInput) {
            lastInput = toolInput;
            return "fake-result";
        }

        @Override public String call(String toolInput, ToolContext toolContext) {
            return call(toolInput);
        }
    }

    @Test
    void replacesNameAndDescription() {
        ToolCallback renamed = new RenamedToolCallback(new FakeTool(), "NewName", "新描述");

        assertEquals("NewName", renamed.getToolDefinition().name());
        assertEquals("新描述", renamed.getToolDefinition().description());
    }

    @Test
    void nullMeansKeepOriginal() {
        ToolCallback onlyDescription = new RenamedToolCallback(new FakeTool(), null, "只换描述");
        assertEquals("OriginalName", onlyDescription.getToolDefinition().name(), "name 传 null 应保持原值");
        assertEquals("只换描述", onlyDescription.getToolDefinition().description());

        ToolCallback onlyName = new RenamedToolCallback(new FakeTool(), "只换名", null);
        assertEquals("只换名", onlyName.getToolDefinition().name());
        assertEquals("original description", onlyName.getToolDefinition().description(),
                "description 传 null 应保持原值");
    }

    @Test
    void keepsInputSchemaUntouched() {
        ToolCallback renamed = new RenamedToolCallback(new FakeTool(), "NewName", "新描述");

        assertEquals("{\"type\":\"object\"}", renamed.getToolDefinition().inputSchema(),
                "inputSchema 不能被改写——模型据此构造入参");
    }

    @Test
    void passesCallThroughToDelegate() {
        FakeTool fake = new FakeTool();
        ToolCallback renamed = new RenamedToolCallback(fake, "NewName", "新描述");

        String out = renamed.call("{\"q\":\"x\"}");

        assertEquals("fake-result", out, "返回值应原样透传");
        assertEquals("{\"q\":\"x\"}", fake.lastInput, "入参应原样透传");
    }
}
