package com.example.springai.codetui.agent;

/**
 * SkillInfo —— 一个技能的 UI 友好元数据（name + description + 来源层标签）。
 *
 * <p>刻意<b>不</b>让库的 {@code org.springaicommunity.agent.tools.SkillsTool.Skill} 泄漏进 UI 层，
 * 遵守本项目「Spring AI / 第三方类型不越过接缝」的纪律：{@code /skills} 命令只需要这三样。
 *
 * @param name        技能名（= SKILL.md frontmatter 的 name，唯一 id，模型按此名调用）
 * @param description 技能描述（= frontmatter 的 description，模型据此判断是否调用）
 * @param source      来源层标签：用户 / 项目（见 {@link SkillCatalog}）
 */
public record SkillInfo(String name, String description, String source) {
}
