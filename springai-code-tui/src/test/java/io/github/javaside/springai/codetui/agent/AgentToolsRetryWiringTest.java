package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.interjection.InterjectingChatModel;
import io.github.javaside.springai.codetui.agent.interjection.Interjections;
import io.github.javaside.springai.codetui.agent.llm.DynamicAuxChatModel;
import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.RetryReporter;
import io.github.javaside.springai.codetui.agent.llm.RetryingChatModel;
import io.github.javaside.springai.codetui.agent.llm.RetryingStreamChatModel;
import io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig;
import io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig.StreamRetryMode;
import io.github.javaside.springai.codetui.agent.media.VisionMaterializingChatModel;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：主链 L1 重试的装配（spec §3.7 / §5 装配行）。
 *
 * <p><b>1 链序</b>：{@code AgentTools.build} 出的 client unwrap 依次为 Interjecting → Vision →
 * RetryStream → provider 原始链。生产 provider 链 = Usage(IdleTimeout(raw))（CodeTuiApplication.wrap
 * 在 provider 层完成，测试桩的 provider.chatModel() 即最底层 spy），故最终链
 * Interjecting(Vision(RetryStream(Usage(IdleTimeout(raw))))) 中的 Usage/IdleTimeout 恒在 RetryStream 之下
 * （RetryStream 包的是整个 provider.chatModel()）。
 *
 * <p><b>2 开关</b>：env 不能安全改（改进程环境变量会泄漏给同 JVM 其它用例），且开发者本机真设了
 * {@code CODETUI_STREAM_RETRY} 会让默认走 fromEnv() 的装配随机红——故 build 提供注入重载
 * （含 {@link StreamRetryConfig}），测试逐档断言：off / l2 → 链中无 RetryingStreamChatModel（三元直通不包装）；
 * l1 → 有 RetryingStream 但 CodingAgent L2 关（L2 白名单侧由 Task 6 的 L2_ONLY 测试覆盖，这里只验装配面）。
 *
 * <p><b>3 aux 链</b>：辅助 ChatClient 底层的 {@link DynamicAuxChatModel} 每次调用<b>实时直取
 * provider.chatModel()</b>——不被 RetryStream 包。断言主体在 {@code AuxClientNotRetryWrappedTest}
 * （本类只做装配面一句点名）；与 {@code AuxClientNotVisionWrappedTest} 的「aux 不许被视觉包」同一纪律。
 *
 * <p><b>4 子 agent 链</b>：SubagentRunner 在 {@code execute()} 里自建 client（外层包
 * {@link RetryingChatModel}、内层 provider.chatModel()）——共享链不被 RetryStream 包则子 agent 双层都不含
 * （见 subagentPath_ 用例）。RetryStream 只在主链 wrap 这一行（spec §3.7 装配面收敛一行）。
 *
 * <p><b>5 dispose 守卫落位说明</b>：spec §5 把「dispose 后 boundedElastic 不再 replaceEvents」挂在装配行，
 * 但<b>实际落位 CodingAgentTurnResumeTest 用例 7</b>（repository 桩计数，Task 6 已实现）——本任务不重复。
 *
 * <p><b>6 blank-user 闸门点名</b>（R12：全域清 blank user 只该清续跑伪影，任何新空提交路径都不能被误删）：
 * 空 user 的唯一合法来源是续跑 before() 伪影，因为三处入闸都挡空白——① UI isBlank 早退：
 * {@code CodeTuiView.submitInput} 空回车直接 return（只放行刹车，绝不产生 user 消息；既有测试点名
 * {@code CodeTuiViewBackgroundTest.enterOnEmptyInputReleasesBrake}）；② offer isBlank：
 * {@link Interjections#offer} 空白 null 不入队（本类 blankInterjection_neverQueues 直接断言）；③ skill 注入
 * 非空前缀：{@code CodingAgent.injectSkill} 给文本前恒加 {@code <skill_instruction>…} 块（注入路径的 user
 * 必非空；既有测试点名 {@code CodingAgentSkillInjectTest.submitWithSkill_callsToolAndReportsEvents} 与
 * {@code HistoryReplayTest.userWithInjectedSkillInstructionShowsOnlyOriginalText}）。
 */
class AgentToolsRetryWiringTest {

    /** 视觉模型 id（在 VisionModels 名单内），否则视觉装饰器正确地什么都不做；仅装配链序需要模型身份合法。 */
    private static final String VISION_MODEL = "gpt-5.6-sol";

    // ── 反射 unwrap（参照 AuxClientNotVisionWrappedTest 的 SpyProvider 装配 + 既有测试的反射手法） ──────

    private static ChatModel chatClientModel(ChatClient client) throws Exception {
        Class<?> k = client.getClass();
        while (k != null && k != Object.class) {
            try {
                Field f = k.getDeclaredField("defaultChatClientRequest");
                f.setAccessible(true);
                Object spec = f.get(client);
                Field m = spec.getClass().getDeclaredField("chatModel");
                m.setAccessible(true);
                return (ChatModel) m.get(spec);
            } catch (NoSuchFieldException e) {
                k = k.getSuperclass();
            }
        }
        throw new IllegalStateException("无法从 ChatClient 反射出底层 ChatModel：" + client.getClass());
    }

    /** 读装饰器私有字段 {@code delegate} 的内层模型（Interjecting/Vision/RetryStream/RetryChatModel 同形）。 */
    private static ChatModel delegate(ChatModel wrapper, Class<?> wrapperType) throws Exception {
        Field f = wrapperType.getDeclaredField("delegate");
        f.setAccessible(true);
        return (ChatModel) f.get(wrapper);
    }

    /** 读 RetryingStreamChatModel 私有字段 {@code reporter}（断言装配期包进去的就是 runtime.bridge()）。 */
    private static RetryReporter reporterOf(ChatModel retryStream) throws Exception {
        Field f = RetryingStreamChatModel.class.getDeclaredField("reporter");
        f.setAccessible(true);
        return (RetryReporter) f.get(retryStream);
    }

    /** 注入重载装配：mcpRegistry=null + 隔离测试引擎（与其余 WiringTest 的 3-arg 兼容路径同语义）。 */
    private static AgentTools.AgentRuntime build(ProviderRegistry registry, Path root, StreamRetryConfig cfg) {
        return AgentTools.build(registry, root, new ConversationState(), null,
                AgentTools.testEngine(root), cfg);
    }

    private static SpyProvider spyRegistry() {
        return new SpyProvider();
    }

    private static StreamRetryConfig cfg(StreamRetryMode mode) {
        return new StreamRetryConfig(mode);
    }

    // ── 1/2 链序 + 开关 ─────────────────────────────────────────────────────

    /**
     * ★ 核心链序（ALL，即生产默认）：Interjecting → Vision → RetryingStream → provider 原始 spy。
     * 位置论证的反面：若 RetryStream 被放到 Vision 外（或打进共享 provider 链），这里的 unwrap 顺序即红；
     * 若 wrap 与 runtime.bridge() 不是同一实例（两桥 bug），L1 重试对 UI 永久不可见，reporter 断言即红。
     */
    @Test
    void mainChain_l1Enabled_retryStreamSitsBetweenVisionAndRaw(@TempDir Path root) throws Exception {
        SpyProvider provider = spyRegistry();
        AgentTools.AgentRuntime rt = build(new ProviderRegistry(List.of(provider)), root, cfg(StreamRetryMode.ALL));

        ChatModel interjecting = chatClientModel(rt.client());
        assertInstanceOf(InterjectingChatModel.class, interjecting, "最外层必须是插话装饰器（既有约束不变）");
        ChatModel vision = delegate(interjecting, InterjectingChatModel.class);
        assertInstanceOf(VisionMaterializingChatModel.class, vision, "第二层必须是视觉兑现装饰器");
        ChatModel retry = delegate(vision, VisionMaterializingChatModel.class);
        assertInstanceOf(RetryingStreamChatModel.class, retry,
                "L1 开启时 RetryStream 必须插在 Vision 与 provider.chatModel() 之间（spec §3.7）");
        ChatModel raw = delegate(retry, RetryingStreamChatModel.class);
        assertSame(provider.spy, raw, "RetryStream 底下必须是 provider.chatModel() 原样返回——不得打进共享链");
        // 装配期包进每个 provider 主链的 reporter 必须正是 runtime.bridge()（两段式桥的同一实例）。
        assertSame(rt.bridge(), reporterOf(retry),
                "wrap 用的 reporter 与 AgentRuntime.bridge() 不是同一实例——wireL1 会 bind 到一个无人监听的桥");
    }

    /** 开关 off：三元直通不包装（无 passthrough API），Vision 底下直接是原始链。 */
    @Test
    void switch_off_mainChainHasNoRetryStream(@TempDir Path root) throws Exception {
        SpyProvider provider = spyRegistry();
        AgentTools.AgentRuntime rt =
                build(new ProviderRegistry(List.of(provider)), root, cfg(StreamRetryMode.OFF));

        ChatModel vision = delegate(chatClientModel(rt.client()), InterjectingChatModel.class);
        ChatModel underVision = delegate(vision, VisionMaterializingChatModel.class);
        assertFalse(underVision instanceof RetryingStreamChatModel,
                "CODETUI_STREAM_RETRY=off 时主链不得含 RetryingStreamChatModel");
        assertSame(provider.spy, underVision, "off 直通：Vision 底下必须恰好是 provider.chatModel() 原样");
    }

    /** 开关 l2：L1 不包装（L2-only 模式只该由 CodingAgent 层续跑，主链不得出现 RetryStream）。 */
    @Test
    void switch_l2Only_mainChainHasNoRetryStream(@TempDir Path root) throws Exception {
        SpyProvider provider = spyRegistry();
        AgentTools.AgentRuntime rt =
                build(new ProviderRegistry(List.of(provider)), root, cfg(StreamRetryMode.L2_ONLY));

        ChatModel vision = delegate(chatClientModel(rt.client()), InterjectingChatModel.class);
        ChatModel underVision = delegate(vision, VisionMaterializingChatModel.class);
        assertFalse(underVision instanceof RetryingStreamChatModel,
                "CODETUI_STREAM_RETRY=l2 时 L1 不包装——主链不得含 RetryingStreamChatModel");
        assertSame(provider.spy, underVision, "l2 直通：Vision 底下必须恰好是 provider.chatModel() 原样");
    }

    /** 开关 l1（仅装配面）：有 RetryStream。CodingAgent 侧 L2 关闭由 Task 6 的 L2_ONLY 测试覆盖，这里不重复。 */
    @Test
    void switch_l1Only_mainChainHasRetryStream(@TempDir Path root) throws Exception {
        SpyProvider provider = spyRegistry();
        AgentTools.AgentRuntime rt =
                build(new ProviderRegistry(List.of(provider)), root, cfg(StreamRetryMode.L1_ONLY));

        ChatModel vision = delegate(chatClientModel(rt.client()), InterjectingChatModel.class);
        ChatModel underVision = delegate(vision, VisionMaterializingChatModel.class);
        assertInstanceOf(RetryingStreamChatModel.class, underVision,
                "CODETUI_STREAM_RETRY=l1 时主链必须含 RetryingStreamChatModel");
    }

    // ── 3 aux 链（装配面点名；断言主体在 AuxClientNotRetryWrappedTest） ──────

    @Test
    void auxChatModelIsNotRetryWrapped() {
        ChatModel m = AgentTools.auxChatModel(new ProviderRegistry(List.of(spyRegistry())));
        assertFalse(m instanceof RetryingStreamChatModel, "aux 链不得被 RetryStream 包（摘要/抽取请求不套重试层）");
        assertEquals(DynamicAuxChatModel.class, m.getClass(),
                "aux 的 ChatModel 必须是裸 DynamicAuxChatModel（每次调用实时直取 provider.chatModel()）");
    }

    // ── 4 子 agent 链 ──────────────────────────────────────────────────────

    /**
     * 子 agent 路径不得含 RetryStream（spec §5 守卫）。SubagentRunner 在 {@code execute()} 里自建 client：
     * {@code RetryingChatModel.wrap(selection.provider().chatModel())}（SubagentRunner L269）——外 RetryingChatModel、
     * 内 provider 共享链。AgentTools 主链的 RetryStream 只包在「主 ChatClient 的 ChatModel」上，绝不改动
     * provider.chatModel() 共享链（上面 mainChain 用例已断言主链底 == provider.chatModel() 原样 spy）；
     * 故子 agent 双层（RetryingChatModel 外 + provider 链内）都不会碰到 RetryStream。
     */
    @Test
    void subagentPath_doesNotGetRetryStream(@TempDir Path root) throws Exception {
        SpyProvider provider = spyRegistry();
        ProviderRegistry registry = new ProviderRegistry(List.of(provider));
        AgentTools.AgentRuntime rt = build(registry, root, cfg(StreamRetryMode.ALL));

        // 共享链未被污染（与 mainChain 用例同一断言，锚定到 SubagentRunner 用的 provider.chatModel() 原样）。
        ChatModel vision = delegate(chatClientModel(rt.client()), InterjectingChatModel.class);
        ChatModel mainBottom = delegate(vision, VisionMaterializingChatModel.class);
        while (mainBottom instanceof RetryingStreamChatModel) {
            mainBottom = delegate(mainBottom, RetryingStreamChatModel.class);
        }
        assertSame(provider.spy, mainBottom, "主链底必须回到 provider.chatModel() 原样——RetryStream 只在外层");

        // SubagentRunner 的装配表达式：RetryingChatModel(provider.chatModel())。自外向内 unwrap，全程无 RetryStream。
        ChatModel subagentTop = RetryingChatModel.wrap(provider.chatModel());
        ChatModel cursor = subagentTop;
        assertInstanceOf(RetryingChatModel.class, cursor, "子 agent 路径外层是既有的 call 粒度 RetryingChatModel");
        cursor = delegate(cursor, RetryingChatModel.class);
        assertSame(provider.spy, cursor,
                "RetryingChatModel 底下必须正是 provider.chatModel() 原样——子 agent 不会被 RetryStream 双重重试");
    }

    // ── L1ReporterBridge 形状（两段式桥本身） ───────────────────────────────

    @Test
    void l1ReporterBridge_noopBeforeBind_andForwardsAfter() {
        AgentTools.L1ReporterBridge bridge = new AgentTools.L1ReporterBridge();
        List<String> seen = new ArrayList<>();
        bridge.report(2, 500, "装配期重试");      // 未 bind：volatile null 守卫，必须 no-op 不 NPE
        assertTrue(seen.isEmpty(), "bind 前 report 必须 no-op");

        bridge.bind((attempt, backoffMs, reason) -> seen.add(attempt + "/" + backoffMs + "/" + reason));
        bridge.report(3, 1000, "boom");
        bridge.report(5, 4000, "net down");
        assertEquals(List.of("3/1000/boom", "5/4000/net down"), seen, "bind 后必须按 (attempt, backoffMs, reason) 转发");
    }

    // ── 6 blank-user 闸门：② offer isBlank 直接断言（①③ 见类注释点名既有测试） ──────

    @Test
    void blankInterjection_neverQueues() {
        Interjections q = new Interjections();
        q.offer("");                                  // 空白：不入队
        q.offer("   ");
        q.offer(null);
        assertEquals(0, q.pendingCount(), "空白/null 插话不得入队（空 user 唯一来源是续跑伪影，R12）");
        q.offer("等一下");
        assertEquals(1, q.pendingCount(), "非空插话应正常入队（阳性对照）");
    }

    // ── 测试桩（照抄 AuxClientNotVisionWrappedTest 的 SpyProvider 形状） ─────

    /** 记录最底层 ChatModel 实际收到的 Prompt（本类只做链序/身份断言，不真正发流）。 */
    private static final class SpyChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt p) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
        /** 必须是带模型 id 的 ToolCallingChatOptions：视觉兑现判定读的正是出站 Prompt 的 model。 */
        @Override public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model(VISION_MODEL).build();
        }
    }

    private static final class SpyProvider implements LlmProvider {
        final SpyChatModel spy = new SpyChatModel();
        @Override public String id() { return "spy"; }
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() { return spy; }
        @Override public ChatOptions options(String modelId) {
            return ToolCallingChatOptions.builder().model(modelId).build();
        }
        @Override public List<ModelOption> models() {
            return List.of(new ModelOption(VISION_MODEL, VISION_MODEL, "test"));
        }
        @Override public String defaultModel() { return VISION_MODEL; }
        @Override public io.github.javaside.springai.codetui.agent.media.ModelCapabilities capabilities(String modelId) {
            return new io.github.javaside.springai.codetui.agent.media.ModelCapabilities(true, false);
        }
    }
}
