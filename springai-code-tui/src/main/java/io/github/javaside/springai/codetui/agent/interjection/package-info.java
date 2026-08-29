/**
 * 回合中插话（mid-turn interjection）：用户在模型流式输出期间输入的文本，经
 * {@code Interjections} 队列（UI 线程 offer / 模型线程 drain / 回合末取回补历史，全
 * synchronized 纳秒级操作）在下一个工具调用边界注入对话。
 *
 * <p><b>锚点纪律</b>：插话插在"anchor 工具调用"之后而非简单追加末尾（历史里 anchor 可能
 * 不在末尾）；{@code InterjectionText.wrapText} 把原文包上标记，让模型能区分插话与
 * 常规输入。
 *
 * <p><b>依赖方向</b>：叶子级，零 agent 内部依赖。
 */
package io.github.javaside.springai.codetui.agent.interjection;
