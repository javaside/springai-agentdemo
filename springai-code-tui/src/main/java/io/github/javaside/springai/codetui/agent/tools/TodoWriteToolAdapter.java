package io.github.javaside.springai.codetui.agent.tools;

import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

/**
 * TodoWrite 的薄适配器：把入参从「包装记录 {@link Todos}」摊平成 {@code List<TodoItem>}，消除双层 {@code todos} 嵌套。
 *
 * <p><b>为什么需要它</b>：社区库 {@link TodoWriteTool} 的 {@code @Tool} 方法签名是 {@code todoWrite(Todos)}，
 * 而 {@code Todos} 唯一字段也叫 {@code todos}。Spring AI 生成工具入参 schema 时按参数名再包一层，于是「正确」的
 * JSON 是双层的 {@code {"todos":{"todos":[...]}}}。模型（尤其非 Claude 系）几乎必然把它塌成直觉的单层
 * {@code {"todos":[...]}}，Spring AI 便拿到一个数组去反序列化 {@code Todos} 记录 →
 * {@code MismatchedInputException: Cannot deserialize Todos from Array value}，TodoWrite 频繁报错。
 *
 * <p><b>本适配器</b>的 {@code @Tool} 方法签名直接是 {@code List<TodoItem>}，生成的 schema 即单层
 * {@code {"todos":[...]}}，正好对上模型天然产出的形状。校验（唯一 in_progress、content/activeForm 非空、
 * 合法 status）与事件派发（{@code onTodoUpdated}）全部复用委托的库工具，行为不变；工具名保持 {@code "TodoWrite"}。
 * 面向模型的完整描述在装配处（{@code AgentTools}）从库工具原样移植，故这里的 {@code description} 只是兜底。
 *
 * <p>注意：本类的参数名 {@code todos} 必须进入字节码（{@code -parameters}），否则 Spring AI 只能拿到
 * {@code arg0} 作为 schema 属性名 —— 见本模块 pom 的 {@code maven-compiler-plugin} 配置。
 */
public final class TodoWriteToolAdapter {

    private final TodoWriteTool delegate;

    public TodoWriteToolAdapter(TodoWriteTool delegate) {
        this.delegate = delegate;
    }

    @Tool(name = "TodoWrite", description = """
            Create and manage a structured task list for the current coding session. Pass the FULL list every call
            (it replaces the previous list). Each item needs: content (imperative, e.g. "Run tests"), activeForm
            (present continuous, e.g. "Running tests") and status (pending | in_progress | completed).
            Keep at most ONE task in_progress at a time.""")
    public String todoWrite(List<Todos.TodoItem> todos) {
        // 摊平的 List 重新包回库记录，复用库工具的校验 + 事件派发；仅入参形状不同。
        return delegate.todoWrite(new Todos(todos));
    }
}
