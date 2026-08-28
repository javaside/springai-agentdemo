package io.github.javaside.springai.codetui.agent.llm;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 空流守卫（{@link StreamAdvisor}）：修复切到 OpenAI/gpt 时 {@code SessionMemoryAdvisor.after()} 抛
 * {@code IllegalStateException("No session ID found in advisor context")} 的 bug。
 *
 * <p><b>根因</b>：{@code SessionMemoryAdvisor}（本项目里 order {@code HIGHEST_PRECEDENCE+1000}，位于 tool 循环<b>内</b>，
 * 每个 tool 迭代跑一次 before/after）的 {@code adviseStream} 用 {@code ChatClientMessageAggregator} 聚合模型流：后者以
 * 空 {@code HashMap} 起底 context，只从<b>流入元素</b>的 {@code context()} 累加；而底层 {@code MessageAggregator.aggregate}
 * 在 {@code doOnComplete} 里回调完成处理器——<b>空流也会触发</b>。于是当某个迭代的模型流为空（OpenAI 官方 SDK 流式路径下
 * 实测出现），聚合到的 context 恒为空，{@code after()} → {@code getSessionId({})} 抛错，本回合 assistant 遂无法落盘
 * （进而堆出连续 USER、被 DeepSeek 400，见 {@link SessionEvents#collapseConsecutiveSameRole}）。DeepSeek 的 WebClient
 * 流式从不为空，故不触发。
 *
 * <p><b>修法</b>：把本守卫装在 {@code SessionMemoryAdvisor} 的<b>正内侧</b>（order {@code +1001}，紧邻其
 * {@code chain.nextStream} 的第一跳、又在 {@code ChatModelStreamAdvisor} 的 {@code LOWEST_PRECEDENCE} 之外），当下游
 * （本迭代模型流）零元素时，用 {@code switchIfEmpty} 补发<b>一条</b>携带 {@code req.context()}（含 session id）的合成响应，
 * 使 {@code SessionMemoryAdvisor} 的聚合 context 非空、{@code after()} 正常落盘一条（空文本的）assistant 事件、
 * {@code ToolCallingAdvisor} 见到一条无 tool_call 的终止响应而干净收尾。
 *
 * <p><b>副作用（已评估、可接受）</b>：空迭代会落盘一条空 {@link AssistantMessage}——序列化无碍，且反而<b>有益</b>
 * （在两条 user 间插入 assistant，阻断连续 USER 堆积）；{@code CodingAgent.handleChunk} 的
 * {@code delta != null && !delta.isEmpty()} 已过滤该合成块，无多余 UI token。<b>只实现 {@link StreamAdvisor}</b>
 * （不碰阻塞/call 路径），对子 agent、辅助 client 零影响。在 DeepSeek 上流非空 ⇒ {@code switchIfEmpty} 永不触发 ⇒
 * 行为零变化、零回归。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class SessionIdStreamGuardAdvisor implements StreamAdvisor {

    // SessionMemoryAdvisor 在本项目固定为 HIGHEST_PRECEDENCE+1000（AgentTools 从不覆盖其 order），
    // +1001 保证本守卫是其 chain.nextStream 触达的第一个 advisor：中间无他物，switchIfEmpty 恰好裹住本迭代的模型流。
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1001;

    @Override
    public String getName() {
        return "sessionIdStreamGuard";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest req, StreamAdvisorChain chain) {
        return chain.nextStream(req).switchIfEmpty(Flux.defer(() -> {
            // 下游本迭代零元素 → 补发一条：携 req.context()（含 session id）+ 格式完整的空 assistant，
            // 使外层 SessionMemoryAdvisor 聚合 context 非空、after() 正常落盘、ToolCallingAdvisor 干净收尾。
            ChatResponse empty = new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
            return Flux.just(ChatClientResponse.builder().chatResponse(empty).context(req.context()).build());
        }));
    }
}
