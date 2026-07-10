package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** -c 恢复回放：历史消息 → scrollback 行。校验类型/顺序/跳过 SYSTEM/工具紧凑标记。 */
class HistoryReplayTest {

    @Test
    void emptyOrNullYieldsNoLines() {
        assertTrue(HistoryReplay.toReplayLines(null).isEmpty());
        assertTrue(HistoryReplay.toReplayLines(List.of()).isEmpty());
        assertEquals(0, HistoryReplay.userTurns(null));
    }

    @Test
    void systemMessageIsSkipped() {
        List<OutputLine> out = HistoryReplay.toReplayLines(List.of(new SystemMessage("grounding 提示，不该显示")));
        assertTrue(out.isEmpty(), "SYSTEM 应被跳过");
    }

    @Test
    void userRendersAsBlankSpacerThenPromptBlock() {
        List<OutputLine> out = HistoryReplay.toReplayLines(List.of(new UserMessage("帮我看下 bug")));
        assertEquals(2, out.size());
        assertEquals(Kind.ASSISTANT, out.get(0).kind());
        assertEquals("", out.get(0).text(), "回合间留白");
        assertEquals(Kind.USER, out.get(1).kind());
        assertEquals("› 帮我看下 bug", out.get(1).text());
    }

    @Test
    void userWithInjectedSkillInstructionShowsOnlyOriginalText() {
        // 会话持久化的是 injectSkill 注入后的有效文本；回放须只显示用户原文（与实时 onUserMessage 一致）。
        String stored = "<skill_instruction>\nBase directory: /skills/x\n\n# Systematic Debugging\n\n正文若干行\n</skill_instruction>\n\n帮我排查这个 bug";
        List<OutputLine> out = HistoryReplay.toReplayLines(List.of(new UserMessage(stored)));
        assertEquals(2, out.size());
        assertEquals(Kind.USER, out.get(1).kind());
        assertEquals("› 帮我排查这个 bug", out.get(1).text(), "只留用户原文，剥掉 skill_instruction 块");
    }

    @Test
    void stripSkillInstructionLeavesPlainUserTextUntouched() {
        assertEquals("普通消息", HistoryReplay.stripSkillInstruction("普通消息"));
        assertEquals("", HistoryReplay.stripSkillInstruction(""));
        // 只有开头是注入标记才剥；正文里偶然含该字样不受影响
        assertEquals("聊聊 <skill_instruction> 这个标签",
                HistoryReplay.stripSkillInstruction("聊聊 <skill_instruction> 这个标签"));
    }

    @Test
    void stripSkillInstructionEmptyUserTextAfterSkill() {
        // 用户仅触发 /skill、未附带正文：剥完为空，与实时空用户块一致
        String stored = "<skill_instruction>\nbody\n</skill_instruction>\n\n";
        assertEquals("", HistoryReplay.stripSkillInstruction(stored));
    }

    @Test
    void assistantTextThenToolCallRenderCompactly() {
        AssistantMessage am = AssistantMessage.builder()
                .content("我来排查")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "Grep", "{\"pattern\":\"foo\"}")))
                .build();
        List<OutputLine> out = HistoryReplay.toReplayLines(List.of(am));
        assertEquals(2, out.size());
        assertEquals(Kind.ASSISTANT, out.get(0).kind());
        assertEquals("我来排查", out.get(0).text());
        assertEquals(Kind.TOOL_START, out.get(1).kind());
        assertEquals("Grep", out.get(1).toolName());
        assertTrue(out.get(1).text().startsWith("⏺ Grep"), "工具调用紧凑标记：" + out.get(1).text());
        assertEquals(null, out.get(1).raw(), "raw=null 不重绘 diff");
    }

    @Test
    void multiLineAssistantSplitsPerLine() {
        // 多行 markdown 正文必须拆成「一行一 OutputLine」，否则 printer.assistant 会塌成一行截断。
        AssistantMessage am = new AssistantMessage("第一行\n\n### 标题\n- 项目 A");
        List<OutputLine> out = HistoryReplay.toReplayLines(List.of(am));
        assertEquals(List.of("第一行", "", "### 标题", "- 项目 A"), out.stream().map(OutputLine::text).toList());
        assertTrue(out.stream().allMatch(o -> o.kind() == Kind.ASSISTANT));
    }

    @Test
    void toolResponseRendersAsCompletionMarker() {
        ToolResponseMessage trm = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "Grep", "3 matches")))
                .build();
        List<OutputLine> out = HistoryReplay.toReplayLines(List.of(trm));
        assertEquals(1, out.size());
        assertEquals(Kind.TOOL_OK, out.get(0).kind());
        assertEquals("  ⎿ Grep ✓", out.get(0).text());
    }

    @Test
    void assistantWithBlankTextButToolCallsOmitsEmptyTextLine() {
        AssistantMessage am = AssistantMessage.builder()
                .content("   ")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "Task", "{}")))
                .build();
        List<OutputLine> out = HistoryReplay.toReplayLines(List.of(am));
        assertEquals(1, out.size(), "空白正文不产生行，只剩工具标记");
        assertEquals(Kind.TOOL_START, out.get(0).kind());
    }

    @Test
    void fullTurnOrderingAndUserTurnCount() {
        List<Message> history = List.of(
                new SystemMessage("sys"),
                new UserMessage("问题一"),
                new AssistantMessage("答一"),
                new UserMessage("问题二"),
                new AssistantMessage("答二"));
        List<OutputLine> out = HistoryReplay.toReplayLines(history);
        // 两轮：每轮 留白+USER + ASSISTANT = 3 行；SYSTEM 跳过
        assertEquals(6, out.size());
        assertEquals(List.of(Kind.ASSISTANT, Kind.USER, Kind.ASSISTANT, Kind.ASSISTANT, Kind.USER, Kind.ASSISTANT),
                out.stream().map(OutputLine::kind).toList());
        assertEquals(2, HistoryReplay.userTurns(history), "用户轮数");
    }
}
