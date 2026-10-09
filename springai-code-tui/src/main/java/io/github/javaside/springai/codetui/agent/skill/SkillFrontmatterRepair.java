package io.github.javaside.springai.codetui.agent.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkillFrontmatterRepair —— 修复上游 {@code spring-ai-agent-utils} MarkdownParser 不认 YAML
 * 块标量导致的 description 损坏。
 *
 * <p><b>坑的实测证据（0.10.0，javap 反编译 {@code MarkdownParser.parseFrontMatter}）</b>：解析器
 * 逐行按<b>第一个冒号</b>切 key/value、去引号，没有块标量概念。于是 {@code description: >}（折叠
 * 块标量，值在后续缩进行）被解析成：
 * <ul>
 *   <li>{@code description} 的值 = 字面量 {@code ">"}（或 {@code "|"}、{@code ">-"} 等）；</li>
 *   <li>续行里<b>带冒号</b>的文本被当成新 key-value 塞进 frontmatter（如 hyperframes 的
 *       {@code "Mandatory entry point"}、hyperframes-audio 首行以冒号结尾时整句成 key、值为空）；</li>
 *   <li>无冒号的续行直接丢弃。</li>
 * </ul>
 * 这些 SKILL.md 本身是合法 YAML（Claude Code 官方客户端可正确折叠），损坏在库侧。本机 2026-10-09
 * 实测 55 个技能里 9 个这么写（hyperframes 生态），故在装载层补一块标量感知的重解析，而非要求用户改技能文件。
 *
 * <p>两个入口分别服务两侧消费方：
 * <ol>
 *   <li>{@link #foldDescription(Path)} —— 给 {@link SkillCatalog}：重读 {@code <skillDir>/SKILL.md}，
 *       折叠出真实 description（{@code >} 族空格连行、{@code |} 族保留换行），修 {@code /skills} 清单；</li>
 *   <li>{@link #repairAvailableSkills(String, Map)} —— 给 {@link ReloadableSkillTool}：库的
 *       {@code Skill.toXml()} 把 frontmatter <b>整个 map</b> 逐条转成 {@code <k>v</k>} 烘进工具描述，
 *       幽灵 key 会以幽灵元素形态混进模型上下文——这里只对损坏的 {@code <skill>} 块整体重建为
 *       name+description，健康块与模板其余部分原样保留（模板未来变化也不用跟着改）。</li>
 * </ol>
 */
final class SkillFrontmatterRepair {

    /** 库把块标量指示符本身当成了值——据此识别「需要补解析」的技能（指示符 + 可选截断后缀）。 */
    private static final Pattern BLOCK_INDICATOR = Pattern.compile("[>|][+-]?");

    /**
     * 工具描述里的单个技能块。两个捕获组：块内全部内容、name。<b>不能假设 {@code <skill>} 紧跟
     * {@code <name>}</b>——实测（hyperframes-audio，2026-10-09）库里 frontmatter 是 HashMap，
     * 幽灵元素（整句 key）可以排在 {@code <name>} 之前输出，故 name 前须放行任意内容。
     */
    private static final Pattern SKILL_BLOCK = Pattern.compile("(?s)<skill>\\s*(.*?<name>(.*?)</name>.*?)</skill>");

    private SkillFrontmatterRepair() {
    }

    /** 值是否为裸块标量指示符（{@code >}、{@code |}、{@code >-}、{@code |+} …）。 */
    static boolean isBlockIndicator(String value) {
        return value != null && BLOCK_INDICATOR.matcher(value).matches();
    }

    /**
     * 重读 {@code <skillDir>/SKILL.md}，把块标量 description 折叠成真实文本。
     *
     * @param skillDir 技能目录（库 {@code Skill.basePath()} 即此语义：SKILL.md 的父目录）
     * @return 折叠后的 description；<b>非块标量写法、缺 description、文件读失败</b>时返回 {@code null}
     *         （调用方保留库的原值，修复不引入新的失败模式）
     */
    static String foldDescription(Path skillDir) {
        String md;
        try {
            md = Files.readString(skillDir.resolve("SKILL.md"));
        } catch (IOException | RuntimeException ex) {
            return null;   // 修复是尽力而为：读不了文件就维持库的现状
        }

        List<String> lines = md.lines().toList();
        // frontmatter = 首行 --- 到下一个 --- 之间；库用 substring 找 "---" 会误伤 "----" 分隔线，这里按行判更稳
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("---")) {
                if (start < 0) {
                    start = i + 1;
                } else {
                    return foldWithin(lines, start, i);
                }
            }
        }
        return null;
    }

    /** 在 [from, end) 的 frontmatter 行里找 {@code description: <块标量>} 并折叠其缩进续行。 */
    private static String foldWithin(List<String> lines, int from, int end) {
        for (int i = from; i < end; i++) {
            String line = lines.get(i);
            if (!line.isBlank() && line.charAt(0) != ' ' && line.charAt(0) != '\t') {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equals("description")
                        && isBlockIndicator(line.substring(colon + 1).trim())) {
                    return foldContinuations(lines, i + 1, end,
                            line.substring(colon + 1).trim().startsWith(">") ? " " : "\n");
                }
            }
        }
        return null;
    }

    /**
     * 收集块标量的缩进续行直到下一个顶格 key / frontmatter 结束。逐行 trim 后连接：
     * {@code >} 族空格连行（YAML 折叠语义），{@code |} 族保留换行（字面语义）。
     * 截断后缀（{@code -}/{@code +}）只影响尾部换行，trim 后无差别，不单独处理。
     */
    private static String foldContinuations(List<String> lines, int from, int end, String joiner) {
        List<String> parts = new ArrayList<>();
        for (int i = from; i < end; i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;   // 折叠标量里的空行是段落分隔，对单行 description 无意义，跳过
            }
            if (line.charAt(0) != ' ' && line.charAt(0) != '\t') {
                break;      // 回到顶格 = 块结束（下一个 key 或 "---"）
            }
            parts.add(line.trim());
        }
        return parts.isEmpty() ? null : String.join(joiner, parts);
    }

    /**
     * 把工具描述里被块标量污染的 {@code <skill>} 块重建为 name+description 两元素（与库 toXml
     * 的块格式一致：两空格缩进、\n 连接）。健康块、{@code <available_skills>} 外的模板文本原样保留。
     *
     * @param toolDescription 库烘焙出的原始工具描述
     * @param fixedByName    name → 折叠后的真实 description（仅含被修复过的技能）
     * @return 修复后的描述；无修复项或未命中任何块时原样返回
     */
    static String repairAvailableSkills(String toolDescription, Map<String, String> fixedByName) {
        if (toolDescription == null || toolDescription.isEmpty() || fixedByName.isEmpty()) {
            return toolDescription;
        }
        Matcher m = SKILL_BLOCK.matcher(toolDescription);
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            String name = m.group(2);
            String fixed = fixedByName.get(name);
            if (fixed == null) {
                continue;   // 健康块：不进 appendReplacement，保留原文
            }
            String rebuilt = "<skill>\n  <name>" + name + "</name>\n  <description>"
                    + fixed + "</description>\n</skill>";
            m.appendReplacement(out, Matcher.quoteReplacement(rebuilt));
            changed = true;
        }
        if (!changed) {
            return toolDescription;
        }
        m.appendTail(out);
        return out.toString();
    }
}
