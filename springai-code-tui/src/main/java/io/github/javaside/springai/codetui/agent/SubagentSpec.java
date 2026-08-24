package io.github.javaside.springai.codetui.agent;

import java.util.List;

/**
 * 一个子 agent 的解析结果（来自 agents/*.md 的 frontmatter + 正文）。
 *
 * <p><b>{@code skills} 目前无消费方</b>：{@link SubagentLoader} 会解析它，但
 * {@code SubagentRunner.effectiveSystemPrompt} 并不读——子 agent 拿到的技能能力来自
 * 工具集里的 {@code Skill} 工具（{@code ReloadableSkillTool} 在 decoratedList 里，
 * 除被 spec deny 外子 agent 都有），走的是「模型按需自调用」那条路。
 * 本字段是为「按 spec 预挂载指定技能」预留的，保留解析是为了不让已写了
 * {@code skills:} 的定义文件在加载期报错。要实现该功能，需在
 * {@code effectiveSystemPrompt} 里按名调技能工具并把正文前置，参照
 * {@code CodingAgent.injectSkill}。
 *
 * @param name        frontmatter name，也是 Task 工具 subagent_type 的取值
 * @param description frontmatter description，进 Task 工具描述里的可用 agent 清单
 * @param systemPrompt markdown 正文（子 agent 的系统提示）
 * @param allowTools  frontmatter tools（本项目真实工具名；空 = 继承全部工具）
 * @param denyTools   frontmatter disallowedTools（在 allow 结果上再剔除）
 * @param model       frontmatter model（可空；空 = 跟随激活 provider；支持 provider:model）
 * @param skills      frontmatter skills（可空；<b>解析后当前不生效</b>，见上方说明）
 */
public record SubagentSpec(String name,
                           String description,
                           String systemPrompt,
                           List<String> allowTools,
                           List<String> denyTools,
                           String model,
                           List<String> skills) {
}
