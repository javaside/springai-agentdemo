package io.github.javaside.springai.agent.demo;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 测试用的脚本化模型：不联网，按脚本决定每次回什么，让会话示例的行为断言不需要 API key。
 *
 * <p>{@link #getOptions()} 必须返回 {@link ToolCallingChatOptions}：ChatClient 是从
 * {@code chatModel.getOptions().mutate()} 起手装工具的，返回普通 ChatOptions 时 {@code .tools(...)}
 * 会被静默丢弃——现象是模型请求了工具调用、工具循环却根本没启动（已弃用的 {@code getDefaultOptions()} 不是入口）。
 */
public final class ScriptedChatModel implements ChatModel {

    private static final AtomicInteger TOOL_CALL_IDS = new AtomicInteger();

    /** 每次被调用时收到的消息条数，按调用先后。 */
    private final List<Integer> promptSizes = new CopyOnWriteArrayList<>();

    private final Function<Prompt, ChatResponse> script;

    public ScriptedChatModel(Function<Prompt, ChatResponse> script) {
        this.script = script;
    }

    public List<Integer> promptSizes() {
        return List.copyOf(this.promptSizes);
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        this.promptSizes.add(prompt.getInstructions().size());
        return this.script.apply(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    public static ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(content).build())));
    }

    public static ChatResponse toolCall(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + TOOL_CALL_IDS.incrementAndGet(), "function",
                        name, arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(message,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
    }

    /** 最后一条用户消息的文本；没有则为空串。 */
    public static String lastUserText(Prompt prompt) {
        String last = "";
        for (Message message : prompt.getInstructions()) {
            if ("user".equals(message.getMessageType().getValue())) {
                last = message.getText();
            }
        }
        return last;
    }

    public static boolean isToolResult(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        return !messages.isEmpty() && messages.get(messages.size() - 1) instanceof ToolResponseMessage;
    }

    public static String firstToolResult(Prompt prompt) {
        for (Message message : prompt.getInstructions()) {
            if (message instanceof ToolResponseMessage toolResult) {
                return toolResult.getResponses().get(0).responseData();
            }
        }
        return "";
    }

}
