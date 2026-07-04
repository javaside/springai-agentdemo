package com.example.springai.codetui.agent;

import java.util.List;

/**
 * 一个子 agent 的解析结果（来自 agents/*.md 的 frontmatter + 正文）。
 *
 * @param name        frontmatter name，也是 Task 工具 subagent_type 的取值
 * @param description frontmatter description，进 Task 工具描述里的可用 agent 清单
 * @param systemPrompt markdown 正文（子 agent 的系统提示）
 * @param allowTools  frontmatter tools（本项目真实工具名；空 = 继承全部工具）
 * @param denyTools   frontmatter disallowedTools（在 allow 结果上再剔除）
 * @param model       frontmatter model（可空；空 = 跟随激活 provider；支持 provider:model）
 * @param skills      frontmatter skills（可空；预加载进 system）
 */
public record SubagentSpec(String name,
                           String description,
                           String systemPrompt,
                           List<String> allowTools,
                           List<String> denyTools,
                           String model,
                           List<String> skills) {
}
