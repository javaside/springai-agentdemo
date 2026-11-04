package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.subagent.AgentInfo;
import dev.tamboui.text.TerminalWidth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /agents 条目行按终端宽度渐进降级——{@code CodeTuiView.agentRow} / {@code denyFootnote}
 * 纯函数直测，不必起 TUI。
 *
 * <p><b>为何值得钉死</b>：scrollback 出口的 {@code TextWrap} 会把超宽逻辑行软折行，且续行
 * 从第 0 列顶格起排——窄窗口下列对齐被打穿、每条裂成多行，「分组单行式」的扫读价值归零。
 * 折行不保信息，只是把「乱」还回来。本类断言：任何宽度档下每条恒一行（显示宽 ≤ 终端宽），
 * 且降级次序符合「desc 最先丢 → model → tools 清单 → 标签 →（极窄）截 name」。
 *
 * <p>AgentInfo 全部手工构造（六参 record）：nameWidth 统一取 15 是刻意贴合真实形态——
 * 内置清单里 {@code general-purpose} 恰 15 列，是生产环境最常见的对齐宽度；不依赖
 * classpath 资源，那是 {@code SubagentLoader} 的测试边界。
 */
class CodeTuiViewAgentRowTest {

    /** 真实内置 explore 的手工形态（tools 清单、英文长描述）。 */
    private static AgentInfo explore() {
        return new AgentInfo("explore",
                "Read-only codebase exploration specialist. Use to quickly find files, search code, "
                        + "and answer questions about how the codebase works, without modifying anything.",
                "内置", List.of("Read", "Grep", "Glob"), List.of(), null);
    }

    /** 真实内置 bash 的手工形态（3 个工具，触发第 4 级清单缩短）。 */
    private static AgentInfo bash() {
        return new AgentInfo("bash",
                "Command execution specialist for git, build, and test commands. Use to run shell "
                        + "workflows and report results. Follows safe git practices.",
                "内置", List.of("Bash", "BashOutput", "KillShell"), List.of(), null);
    }

    /** 自定义形态：带来源层标签 + 指定模型（内置四件均无 model，model 段只能手工造）。 */
    private static AgentInfo reviewer(String model, String description) {
        return new AgentInfo("reviewer", description, "项目",
                List.of("Read", "Grep"), List.of(), model);
    }

    /** 中文长描述：首句 29 个全角字符 = 58 列，确保第 1 级必超、砍它必够。 */
    private static final String ZH_DESC =
            "审查代码改动的正确性与风格问题，逐条给出可验证的修改建议。补充说明第二句。";

    private static int w(String s) {
        return TerminalWidth.of(s);
    }

    @Test
    @DisplayName("120 宽：完整行——tools、model、描述首句全在")
    void wide120FullRow() {
        String row = CodeTuiView.agentRow(
                reviewer("deepseek-chat", "Reviews code changes for correctness and style."),
                15, true, 120);
        assertTrue(row.contains("Read, Grep"), "工具段被误降级：\n" + row);
        assertTrue(row.contains(" · deepseek-chat"), "model 段被误降级：\n" + row);
        assertTrue(row.contains(" — Reviews code changes for correctness and style."),
                "描述首句被误降级：\n" + row);
        assertFalse(row.contains("…"), "120 宽不该出现任何省略号：\n" + row);
        assertTrue(w(row) <= 120, "完整行也必须不超宽：\n" + row);
    }

    @Test
    @DisplayName("80 宽：砍描述段，tools 与 model 仍在")
    void medium80DropsDescription() {
        String row = CodeTuiView.agentRow(reviewer("deepseek-chat", ZH_DESC), 15, true, 80);
        assertTrue(row.contains("Read, Grep"), "第 2 级必须保留工具段：\n" + row);
        assertTrue(row.contains(" · deepseek-chat"), "第 2 级必须保留 model 段：\n" + row);
        assertFalse(row.contains(" — "), "描述段该砍没砍：\n" + row);
        assertFalse(row.contains("审查"), "描述内容残留在行里：\n" + row);
        assertTrue(w(row) <= 80, row);
    }

    @Test
    @DisplayName("60 宽：再砍 model 段，工具清单仍在")
    void narrow60DropsModelKeepsTools() {
        // 26 列的长 model 让第 2 级（54+3+26=67）超 60，第 3 级（38）放下
        String row = CodeTuiView.agentRow(
                reviewer("anthropic:claude-sonnet-4-5", ZH_DESC), 15, true, 60);
        assertTrue(row.contains("Read, Grep"), "第 3 级必须保留工具段：\n" + row);
        assertFalse(row.contains("claude"), "model 段该砍没砍：\n" + row);
        assertFalse(row.contains(" — "), "描述段该砍没砍：\n" + row);
        assertTrue(w(row) <= 60, row);
    }

    @Test
    @DisplayName("40 宽：工具清单缩短为前 2 个 + …")
    void narrow40ShortensToolsList() {
        // bash 形态：第 3 级 4+4+13+27=48 超 40，第 4 级 4+4+13+19=40 恰放下
        String row = CodeTuiView.agentRow(bash(), 15, false, 40);
        assertTrue(row.contains("Bash, BashOutput, …"), "第 4 级应是前 2 个工具 + …：\n" + row);
        assertFalse(row.contains("KillShell"), "第 3 个工具没被省略：\n" + row);
        assertTrue(w(row) <= 40, row);
    }

    @Test
    @DisplayName("工具只有 2 个时不缩（缩了反而更长），直接落第 5 级")
    void twoToolsSkipsShortening() {
        // reviewer 只有 2 个工具：第 3 级 38 > 30，无可缩，第 5 级 27 <= 30
        String row = CodeTuiView.agentRow(reviewer("deepseek-chat", ZH_DESC), 15, true, 30);
        assertFalse(row.contains(", …"), "2 个工具不该出现清单省略号：\n" + row);
        assertFalse(row.contains("Read"), "该到第 5 级却还带着工具段：\n" + row);
        assertTrue(row.contains("[项目]"), row);
    }

    @Test
    @DisplayName("allow 为空时「全工具」三字不缩，直接落第 5 级")
    void allToolsSkipsShortening() {
        AgentInfo generalLike = new AgentInfo("reviewer", ZH_DESC, "项目",
                List.of(), List.of(), "deepseek-chat");
        // 第 3 级 4+8+9+7+6=34 > 30，「全工具」无可缩，第 5 级 27 <= 30
        String row = CodeTuiView.agentRow(generalLike, 15, true, 30);
        assertFalse(row.contains("全工具"), "「全工具」三字该整个丢弃而不是缩：\n" + row);
        assertTrue(row.contains("[项目]"), row);
    }

    @Test
    @DisplayName("36 宽：只剩 name（仍对齐）+ [标签]")
    void veryNarrowOnlyNameAndTag() {
        AgentInfo customExplore = new AgentInfo(explore().name(), explore().description(),
                "项目·agents", explore().allowTools(), List.of(), null);
        // 第 3 级 4+7+10+13+16=50、第 4 级 47 均 > 36；第 5 级 4+7+10+12=33 <= 36
        String row = CodeTuiView.agentRow(customExplore, 15, true, 36);
        assertEquals("  • explore          [项目·agents]", row);
        assertFalse(row.contains("Read"), row);
        assertTrue(w(row) <= 36, row);
    }

    @Test
    @DisplayName("24 宽极窄：放弃对齐，name 截到 19 列 + …")
    void extremeTruncatesName() {
        // 29 列长名：第 5 级剥尾随空白后仍 33 > 24 → 第 6 级 4+19+1=24 恰满
        AgentInfo longName = new AgentInfo("deployment-pipeline-reviewer", ZH_DESC,
                "内置", List.of("Read"), List.of(), null);
        String row = CodeTuiView.agentRow(longName, 29, false, 24);
        assertEquals("  • deployment-pipeline…", row);
        assertTrue(row.contains("…"), "极窄级必须带省略号示意截断：\n" + row);
        assertTrue(row.startsWith("  • deployment"), "name 前缀辨认不出：\n" + row);
        assertTrue(w(row) <= 24, row);
    }

    @Test
    @DisplayName("五个宽度档 × 内置/自定义：显示宽恒 ≤ max(termWidth, 20)")
    void neverExceedsWidth() {
        AgentInfo builtin = new AgentInfo("general-purpose",
                "General-purpose agent for researching complex questions and executing multi-step tasks.",
                "内置", List.of(), List.of("AskUserQuestionTool", "TodoWrite"), null);
        AgentInfo custom = new AgentInfo("风格审查", ZH_DESC, "项目·claude",
                List.of("Read", "Grep", "Glob", "Bash"), List.of(), "deepseek:reasoner");
        for (int termWidth : new int[]{120, 100, 80, 60, 24}) {
            int cap = Math.max(termWidth, 20);
            String b = CodeTuiView.agentRow(builtin, 15, false, termWidth);
            String c = CodeTuiView.agentRow(custom, 15, true, termWidth);
            assertTrue(w(b) <= cap, "内置行在 " + termWidth + " 宽超行（" + w(b) + "）：\n" + b);
            assertTrue(w(c) <= cap, "自定义行在 " + termWidth + " 宽超行（" + w(c) + "）：\n" + c);
        }
    }

    // ── deny 脚注 ──────────────────────────────────────────────

    /** 真实 general-purpose 的 deny 形态：脚注全文 56 列。 */
    private static AgentInfo generalPurpose() {
        return new AgentInfo("general-purpose",
                "General-purpose agent for researching complex questions.",
                "内置", List.of(), List.of("AskUserQuestionTool", "TodoWrite"), null);
    }

    @Test
    @DisplayName("deny 脚注：宽窗原文保留")
    void denyFootnoteKeptWhenWide() {
        String footnote = CodeTuiView.denyFootnote(generalPurpose(), 120);
        assertEquals("※ general-purpose 另剔除：AskUserQuestionTool, TodoWrite", footnote);
        assertFalse(footnote.contains("…"), footnote);
    }

    @Test
    @DisplayName("deny 脚注：超宽按显示宽度截断 + …，恒一行")
    void denyFootnoteTruncatesWhenTooWide() {
        String footnote = CodeTuiView.denyFootnote(generalPurpose(), 40);
        assertTrue(footnote.startsWith("※ general-purpose"), "脚注截断必须保住开头身份：\n" + footnote);
        assertTrue(footnote.endsWith("…"), "截断脚注必须带省略号：\n" + footnote);
        assertFalse(footnote.contains("TodoWrite"), "该被截掉的内容还在：\n" + footnote);
        assertTrue(w(footnote) <= 40, "截断后仍超宽（" + w(footnote) + "）：\n" + footnote);
    }
}
