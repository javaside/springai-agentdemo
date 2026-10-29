package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.media.VisionBudget;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 装配层注入的视觉接缝。<b>null 实例 = 不接线</b>（完全旧行为）。
 *
 * <p>存在的理由：把「视觉怎么接」（root、{@code ImageProfile}、能力谓词）留在装配点一处，
 * 执行器只负责调用。让 {@link SubagentRunner} 自己 new 装饰器，这份知识就会与
 * {@code AgentTools} 的主链装配各写一份、必然漂移——本项目在工具容错白名单、L1 重试链序上
 * 都栽过「两处各写一份」。
 *
 * <p>{@code budget} 由调用方按<b>每次派发</b>新建，并同时塞进 ToolContext：出站兑现与工具侧
 * 「额度已尽」的判断必须用同一个实例，否则后者永远是错的。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public interface SubagentVision {

    /**
     * 给一次派发的 ChatModel 挂上视觉兑现。
     *
     * <p>链序约定为 {@code Vision(Retrying(raw))}：Vision 在最外层，于是瞬态重试复用同一个
     * 已物化 Prompt（不重复解码/扣额度），而 400 拒图降级由 Vision 的 catch 分支处理。
     *
     * @param base     provider 的 ChatModel（调用方已按需包好重试）
     * @param selection 本次派发实际解析到的 provider + model
     * @param budget   本次派发自有的预算实例（同一实例已随 ToolContext 下发）
     */
    ChatModel decorate(ChatModel base, ProviderRegistry.RequestSelection selection, VisionBudget budget);
}
