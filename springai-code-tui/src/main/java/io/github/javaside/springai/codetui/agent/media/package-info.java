/**
 * 媒体/视觉域：图片等产物只在请求期临时进入消息，长期会话里只存引用块——控制上下文
 * 占用是本域的核心目的，看图与引用是实现方式。
 *
 * <p>三块能力：① 外置存储（{@code MediaArtifactStore} 内容寻址 + GC + DeepSeek 文件态）；
 * ② 引用解析（文本里的 {@code [artifact:...]} / 文件路径引用 → 请求期物化，
 * {@code VisionMaterializingChatModel} 在 ChatModel 层做物化包装）； ③ 预算与保真
 * （{@code VisionBudget} 限流、{@code ImagePreparer} 压缩、magic bytes 嗅探防伪装）。
 *
 * <p><b>依赖方向</b>：叶子级（28 个类，依赖图最大子包），零 agent 内部依赖——
 * 被 llm/tools/seam/subagent 单向消费。
 */
package io.github.javaside.springai.codetui.agent.media;
