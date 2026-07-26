package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 只改写 {@link ToolDefinition} 的 name / description，其余（inputSchema、调用）全透传的装饰器。
 * {@code null} 参数表示保持委托对象原值。
 *
 * <p>两个用途：
 * <ul>
 *   <li>把库工具的完整描述移植到自写适配器上（TodoWrite 适配器用，只换 description）；
 *   <li>给库工具<b>改注册名</b>——库版 {@code BraveWebSearchTool} 的注册名是 {@code WebSearch}，
 *       与本项目博查工具撞名。不改名则模型侧工具分发直接坏，且子 agent 的 allow/deny
 *       （按注册名精确匹配）会跟着错乱。
 * </ul>
 */
public final class RenamedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolDefinition definition;

    public RenamedToolCallback(ToolCallback delegate, String name, String description) {
        this.delegate = delegate;
        ToolDefinition d = delegate.getToolDefinition();
        this.definition = ToolDefinition.builder()
                .name(name == null ? d.name() : name)
                .description(description == null ? d.description() : description)
                .inputSchema(d.inputSchema())
                .build();
    }

    @Override public ToolDefinition getToolDefinition() { return definition; }

    @Override public String call(String toolInput) { return delegate.call(toolInput); }

    @Override public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
