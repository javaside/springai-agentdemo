/**
 * 技能域：{@code SkillCatalog}（发现 {@code ~/.codetui/skills/} 与项目级技能目录）、
 * {@code SkillInfo}（元数据）、{@code ReloadableSkillTool}（热重载的技能工具——每次调用
 * 重新读技能文件，改技能不用重启）。
 *
 * <p><b>依赖方向</b>：叶子级，零 agent 内部依赖。
 */
package io.github.javaside.springai.codetui.agent.skill;
