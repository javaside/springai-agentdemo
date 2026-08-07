package io.github.javaside.springai.codetui.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把用户在回合进行中输入的「插话」追加到<b>下一次模型调用</b>的消息末尾。
 *
 * <p><b>为什么挂在 ChatModel 层</b>（实测结论）：合法的插入位置只有「所有工具结果之后」——
 *
 * <pre>
 * system
 * user       原始提问
 * assistant  tool_calls
 * tool       工具结果
 * user       插话        ← 只有这里合法
 * </pre>
 *
 * 落在 {@code assistant(tool_calls)} 与 {@code tool} 之间就是悬空 tool_calls，下一次请求直接 400。
 * 而会话存储层<b>看不到</b>工具结果何时落库（它与「构建下一次 prompt」是同一步），从那里追加必然错位；
 * 只有本层拿到的 {@link Prompt} 是已经配平的完整消息表。这个项目已有
 * {@link VisionMaterializingChatModel} / {@code RetryingChatModel} 在同一层装饰的先例。
 *
 * <p><b>装饰顺序</b>：本层须在 {@code RetryingChatModel} <b>外层</b>（若两者同时存在）。反了则重试时
 * 队列已被第一次尝试排空，用户的插话会在一次网络抖动后<b>静默消失</b>。当前主 agent 不用
 * RetryingChatModel（只有子 agent 用），故实际不相邻——但这条约束随时可能因接线变动而变得相关。
 *
 * <p><b>不改变回合语义</b>：插话只是多一条 user 消息，不打断工具循环、不取消回合。
 */
final class InterjectingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(InterjectingChatModel.class);

    static final String OPEN = "[interjection]";
    static final String CLOSE = "[/interjection]";

    /**
     * 给模型的行为指引。没有它，模型看到 tool 结果之后突然冒出一条 user 消息，很可能判定
     * 「上一轮结束了，这是新任务」——丢下没做完的工具循环去做新事，或者提前收尾。
     */
    private static final String GUIDE = """
            用户在任务执行中插话，未完成的工作仍在进行中。
            若与当前方向冲突就调整，否则先把手头的做完。""";

    /**
     * 包裹插话文本。沿用 {@code FileReference} 的方括号成对标签约定。
     *
     * <p><b>唯一出处</b>：注入（本类）与回合末补历史（{@code CodingAgent}）都调这个方法。
     * 两处若各写各的，落库文本与模型实际看到的就会有出入，而这种不一致<b>不会报错</b>——
     * 只会在 {@code -c} 恢复后表现为「历史里的话和模型当时的反应对不上」。
     *
     * <p>指引与原话之间用 {@code ---} 分隔，避免模型把指引当成用户说的。
     */
    static String wrapText(String raw) {
        return OPEN + "\n" + GUIDE + "\n---\n" + raw + "\n" + CLOSE;
    }

    private final ChatModel delegate;
    private final Interjections interjections;

    private InterjectingChatModel(ChatModel delegate, Interjections interjections) {
        this.delegate = delegate;
        this.interjections = interjections;
    }

    static ChatModel wrap(ChatModel delegate, Interjections interjections) {
        return interjections == null ? delegate : new InterjectingChatModel(delegate, interjections);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(inject(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(inject(prompt));
    }

    /**
     * 转发底层选项。
     *
     * <p><b>不能省</b>：{@code DefaultChatClientUtils} 用 {@code chatModel.getOptions().mutate()}
     * 起 runtime options builder，返回错类型会让 toolCallbacks 被<b>静默丢弃</b>——模型看不到任何工具。
     * 装饰 ChatModel 时忘了转发 getOptions() 是这个项目踩过的坑。
     */
    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    private Prompt inject(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        Optional<String> text = interjections.drainForInjection(lastToolCallId(messages));
        if (text.isEmpty()) {
            return prompt;
        }
        List<Message> merged = new ArrayList<>(messages);
        merged.add(new UserMessage(wrapText(text.get())));
        log.debug("插话随本次调用送达（{} 字）", text.get().length());
        return new Prompt(merged, prompt.getOptions());
    }

    /**
     * 末尾那条 tool 消息的 id，供回合末补历史时定位。末尾不是 tool 消息（例如模型还在第一次
     * 思考就插了话）则返回 null——那种情况下插话本就该排在原始提问之后，不需要锚。
     */
    private static String lastToolCallId(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolResponseMessage trm) {
                List<ToolResponseMessage.ToolResponse> rs = trm.getResponses();
                return rs.isEmpty() ? null : rs.get(rs.size() - 1).id();
            }
        }
        return null;
    }
}
