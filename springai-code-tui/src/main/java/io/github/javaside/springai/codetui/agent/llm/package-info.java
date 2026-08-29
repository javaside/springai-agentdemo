/**
 * provider 接入与 ChatModel 装饰：{@code LlmProvider} SPI 与 6 家实现（DeepSeek/Anthropic/
 * OpenAI/Qwen/Zhipu/OpenCode Go）、{@code ProviderRegistry}（模型选择）、各 provider 专属
 * 编解码（DeepSeek thinking、Qwen SSE 归一），以及 4 个 ChatModel 装饰器（重试/流超时/
 * 用量记录/动态辅助）与 1 个 StreamAdvisor（空流守卫）。
 *
 * <p><b>装饰链</b>：{@code RetryingChatModel} 在 LLM call 粒度重试（代理网关间歇回 200+
 * 空 body，SDK 自带重试不覆盖）；{@code StreamIdleTimeoutChatModel} 兜住流静默；
 * {@code SessionIdStreamGuardAdvisor} 修复切 OpenAI 时空流导致 SessionMemoryAdvisor 抛错的 bug。
 *
 * <p><b>依赖方向</b>：仅依赖 media（视觉包装）/session（用量、事件裁剪）/thinking
 * （思考配置）三个零依赖叶子包，不依赖任何装配层；被装配层与 seam/tools/subagent/ui 消费。
 */
package io.github.javaside.springai.codetui.agent.llm;
