package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.DynamicAuxChatModel;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.RetryingStreamChatModel;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 守卫：辅助 client（SmartWebFetch 抽取 + <b>会话滚动摘要</b>）的底层 ChatModel
 * <b>绝不能</b>被流式重试装饰器（{@link RetryingStreamChatModel}）包上。
 *
 * <p><b>包上会发生什么</b>：aux 链是<b>动态模型</b>——辅助 ChatClient 用 {@link DynamicAuxChatModel}，
 * 它每次调用<b>实时直取 {@code provider.chatModel()}</b>（跟随 /model 切换）。若 RetryStream 被打进
 * 共享链、或包在 aux 模型外，<b>每一条摘要/抽取请求都套上 L1 零下发重试层</b>：压缩请求出错会在
 * ChatModel 层透明重发（自动触发、无提示），等于给不该重试的旁路也加了重试语义与退避耗时。
 * 主链的 L1 重试只该在 AgentTools 主链 wrap 一处（spec §3.7 装配面收敛一行），aux / 子 agent 都不许碰到它。
 *
 * <p><b>本类为何在 agent 包</b>：{@link AgentTools#auxChatModel} 是 <b>包私有</b> 方法——
 * {@code agent.llm} 包访问不到；与被参照的 {@code AuxClientNotVisionWrappedTest} 同包。
 * 「auxClient 用哪个 ChatModel」的<b>唯一决策点</b>就在这里（从一长串构造里抽出来就是为了能被盯住），
 * 断言它的返回值是 <b>恰好</b> {@link DynamicAuxChatModel}——不止拦流式重试装饰器，套任何一层都会红。
 */
class AuxClientNotRetryWrappedTest {

    private ProviderRegistry registry() {
        return new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
    }

    /** ★ 核心守卫：aux 的 ChatModel 不许是流式重试装饰器。 */
    @Test
    void auxChatModelIsNotRetryWrapped() {
        ChatModel m = AgentTools.auxChatModel(registry());
        assertFalse(m instanceof RetryingStreamChatModel,
                "auxClient 被 RetryingStreamChatModel 包上了：每条摘要/抽取请求都会套 L1 零下发重试层");
    }

    /**
     * 更紧的一道：必须<b>恰好</b>是 DynamicAuxChatModel。
     *
     * <p>只断言「不是重试装饰器」挡不住「先包一层别的、别的里面再包重试」。这条把 aux 钉死成裸的，
     * 任何装饰都得先在这里被看见一眼。
     */
    @Test
    void auxChatModelIsExactlyTheDynamicOne() {
        assertEquals(DynamicAuxChatModel.class, AgentTools.auxChatModel(registry()).getClass(),
                "aux 的 ChatModel 被套了一层——先想清楚那一层会不会跟着每次自动压缩一起触发");
    }

    /** 对照：build 出 runtime 仍离线成功（aux 装配路径不因新装饰器改动而断）。 */
    @Test
    void build_auxPath_stillAssemblesOffline(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(registry(), root, new ConversationState());
        assertNotNull(rt, "build 装配仍须离线成功");
        assertNotNull(rt.client());
    }
}
