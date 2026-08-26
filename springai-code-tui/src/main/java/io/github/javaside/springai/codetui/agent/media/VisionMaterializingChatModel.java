package io.github.javaside.springai.codetui.agent.media;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * 视觉兑现的<b>唯一接线点</b>：在 {@link Prompt} 真正交给 provider 之前，把当轮的图片引用兑现成
 * 真 {@link org.springframework.ai.content.Media}。判断逻辑全在 {@link VisionMaterializer}，本类只负责接线。
 *
 * <p><b>为什么兑现点在 ChatModel 层而不是 advisor</b>：兑现必须发生在会话记忆<b>之后</b>
 * （早了会被写进存储，图片就永久化、跨回合累积回来——那正是整个设计要消灭的东西），又必须在
 * 真正发出<b>之前</b>（晚了就够不着）。advisor 也够得着这个窗口，但它与 {@code SessionMemoryAdvisor}
 * 的相对顺序靠 order 整数维持，<b>排错一位兑现结果就进了存储</b>，且失效时不报错、只是账单慢慢长。
 * 装饰器在整条 advisor 链<b>下游</b>，「出站即兑现」是字面成立的，不依赖任何数字。
 *
 * <p><b>call 与 stream 都要走兑现</b>：主 agent 走 {@code stream}，子 agent 走 {@code call}
 * （经 {@code RetryingChatModel} 桥接到流式）。只改一条等于子 agent 没有视觉。
 *
 * <p><b>模型 id 取自出站 Prompt 的 options</b>，即<b>实际发出去的那个模型</b>，不是从 registry 猜的。
 * 子 agent 可能跑在另一家 provider 上，这样判定天然正确。
 */
public final class VisionMaterializingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final VisionMaterializer materializer;
    private final Predicate<String> supportsImage;

    private VisionMaterializingChatModel(ChatModel delegate, VisionMaterializer materializer,
                                         Predicate<String> supportsImage) {
        this.delegate = delegate;
        this.materializer = materializer;
        this.supportsImage = supportsImage;
    }

    public static VisionMaterializingChatModel wrap(ChatModel delegate, Path root) {
        return wrap(delegate, root, VisionModels::supportsImage);
    }

    public static VisionMaterializingChatModel wrap(ChatModel delegate, Path root,
                                                     Predicate<String> supportsImage) {
        return new VisionMaterializingChatModel(
                delegate, new VisionMaterializer(root, new ImagePreparer(), new VisionBudget()), supportsImage);
    }

    /** 上次兑现的统计（供 {@code /context} 单列视觉占用）。 */
    public VisionSnapshot lastSnapshot() {
        return materializer.lastSnapshot();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(materialize(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(materialize(prompt));
    }

    /** 兑现一次。能力谓词由对应 provider 注入，避免兼容网关的同名模型串用全局能力。 */
    private Prompt materialize(Prompt prompt) {
        String modelId = prompt == null || prompt.getOptions() == null
                ? null
                : prompt.getOptions().getModel();
        return materializer.materialize(prompt, supportsImage.test(modelId));
    }

    /**
     * <b>必须</b>转发 {@link #getOptions()}：ChatClient 构建请求时从这里取基础 options
     * （{@code DefaultChatClientUtils}: {@code getChatModel().getOptions().mutate()}）。漏转发会落到
     * 接口 default（裸 {@code DefaultChatOptions}）→ 不是 {@code ToolCallingChatOptions} →
     * {@code ToolCallingAdvisor} 整个跳过、<b>子 agent 静默丢掉全部工具</b>，且 provider 的 ChatModel
     * 强转家族 options 直接 ClassCastException。本项目在 {@code RetryingChatModel} 上栽过同一个坑。
     */
    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @SuppressWarnings("removal")   // 2.0 起 deprecated，default 已委托 getOptions()；显式转发保险
    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
