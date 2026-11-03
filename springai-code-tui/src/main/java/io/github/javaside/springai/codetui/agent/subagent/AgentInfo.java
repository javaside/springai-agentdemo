package io.github.javaside.springai.codetui.agent.subagent;

import java.util.List;

/**
 * AgentInfo —— 一个子 agent 的 UI 友好元数据（name + description + 来源层标签 + tools/model 投影）。
 *
 * <p>刻意<b>不</b>让 {@link SubagentSpec} 泄漏进 UI 层，遵守本项目「领域类型不越过接缝」的纪律
 * （{@code SkillInfo} 挡住库类型的同款论证）：spec 的 systemPrompt 是动辄整页的大正文，
 * {@code /agents} 清单只需要「叫什么 / 干什么 / 从哪层来 / 能用哪些工具 / 用什么模型」——
 * 把大对象递过去，UI 侧就多拿一份用不上又<b>不该展示</b>的载荷，还要陪它一起背序列化与演进的包袱。
 *
 * @param name        子 agent 名（= frontmatter 的 name，模型按 subagent_type 委派的取值）
 * @param description 子 agent 描述（= frontmatter 的 description，与 Task 工具 roster 行同源）
 * @param source      来源层标签：内置 / 用户·claude / 用户·agents / 用户 / 项目·claude / 项目·agents / 项目。
 *                    六个自定义层与 {@code SkillCatalog} 层标签<b>逐字同口径</b>（技能侧先例）；
 *                    「内置」为 agent 侧独有——技能无内置层，子 agent 有 classpath 内置 4 个。
 *                    同名覆盖后标签跟随胜者（装载时与 specs 同一次迭代双写，见
 *                    {@link SubagentCatalog.Loaded#infos()}）。
 * @param allowTools  工具白名单投影（= frontmatter 的 tools）。空 = 全工具（继承主 agent 工具集，
 *                    再经 denyTools 剔除）；非空 = 仅这些工具可用。
 * @param denyTools   剔除清单投影（= frontmatter 的 disallowedTools）。语义是在 allowTools 的
 *                    结果上再剔除——即先取白名单（空 = 全部），再删掉这里列出的工具。
 * @param model       指定模型投影（= frontmatter 的 model，支持 {@code provider:modelId}）。
 *                    null/空 = 跟随当前激活模型（运行时解析）；非空 = 定义层写死的静态默认，
 *                    委派该子 agent 时即用它，不随主会话切换而变。
 */
public record AgentInfo(String name, String description, String source,
                        List<String> allowTools, List<String> denyTools, String model) {
}
