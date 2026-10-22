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
        return wrap(delegate, root, supportsImage, new VisionBudget());
    }

    /**
     * 用<b>外部传入</b>的预算实例装配。
     *
     * <p>存在的理由：工具执行期（{@code MediaExternalizingCallback}）也要查同一个回合的额度，
     * 才能在图读不回来时如实告诉模型。两侧各 new 一个 {@link VisionBudget} 的话，出站侧扣的额度
     * 工具侧永远看不到，那条「额度已尽」的判断就恒为 false——正是本次要修的假话来源。
     */
    public static VisionMaterializingChatModel wrap(ChatModel delegate, Path root,
                                                     Predicate<String> supportsImage,
                                                     VisionBudget budget) {
        return wrap(delegate, root, supportsImage, budget, ImageProfile.CONSERVATIVE);
    }

    /**
     * 用<b>指定规格</b>装配（长边上限 + token 口径，见 {@link ImageProfile}）。
     *
     * <p>各家的出站分辨率与计费口径差别很大：统一按 Anthropic 的 1568 / {@code 宽×高/750}
     * 处理，对 DeepSeek 会高估 token 3.6 倍（额度被白白吃光），对 OpenAI/通义又是净丢细节。
     */
    public static VisionMaterializingChatModel wrap(ChatModel delegate, Path root,
                                                     Predicate<String> supportsImage,
                                                     VisionBudget budget,
                                                     ImageProfile profile) {
        return new VisionMaterializingChatModel(
                delegate, new VisionMaterializer(root, new ImagePreparer(profile), budget), supportsImage);
    }

    /** 供 {@code /context} 之外的调用方读取本实例的预算（测试与装配自检用）。 */
    VisionMaterializer materializer() {
        return materializer;
    }

    /** 上次兑现的统计（供 {@code /context} 单列视觉占用）。 */
    public VisionSnapshot lastSnapshot() {
        return materializer.lastSnapshot();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Prompt first = materialize(prompt);
        if (first == prompt) {
            return delegate.call(first);        // 没兑现过图：无从降级
        }
        try {
            return delegate.call(first);
        } catch (RuntimeException e) {
            Prompt degraded = degradeAfterImageRejection(prompt, e);
            if (degraded == null) throw e;
            return delegate.call(degraded);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Prompt first = materialize(prompt);
        if (first == prompt) {
            return delegate.stream(first);      // 没兑现过图：无从降级
        }
        // onErrorResume 只挂在这条流上：降级重发的 Flux 不再有降级钩子 → 结构上「最多降级一次」，
        // 第二次 400 直接传播，无需计数器。
        return delegate.stream(first).onErrorResume(e -> {
            Prompt degraded = degradeAfterImageRejection(prompt, e);
            return degraded == null ? Flux.error(e) : delegate.stream(degraded);
        });
    }

    /**
     * 图片类 400 的唯一降级口：确认「服务端拒图」后记拒绝集、重跑物化、剥掉被拒图重发。
     * 非图片错误返回 null 放行——重试与图片无关的 400 只会白烧一次上传，还会把 delivery
     * 写成假话。判据刻意收窄（400 + 文案含「图片/image」），漏判只是回到现状（回合死），
     * 误判却是真伤害。
     */
    private Prompt degradeAfterImageRejection(Prompt original, Throwable e) {
        if (!isImageRejected400(e)) {
            return null;
        }
        materializer.rejectLastDelivered();
        return materialize(original);
    }

    private static boolean isImageRejected400(Throwable e) {
        if (!(e instanceof com.openai.errors.BadRequestException)) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("图片") || lower.contains("image");
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
