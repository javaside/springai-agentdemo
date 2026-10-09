/**
 * 技能域：{@code SkillCatalog}（发现六层技能目录：用户/项目 × codetui/claude/agents）、
 * {@code SkillInfo}（元数据）、{@code ReloadableSkillTool}（热重载的技能工具——每次调用
 * 重新读技能文件，改技能不用重启）、{@code SkillFrontmatterRepair}（修复上游库不认 YAML
 * 块标量导致的 description 损坏）。
 *
 * <p><b>依赖方向</b>：叶子级，零 agent 内部依赖。
 */
package io.github.javaside.springai.codetui.agent.skill;
