package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户消息<b>回显</b>折叠：{@code › } 块超长时只显示前两行 + 折叠标记。
 *
 * <p><b>动机</b>：粘贴折叠（{@code [TEXTn]}）之后，发给模型的已是展开全文——若回显照登，
 * 几千行日志只是从输入框挪到了 scrollback，屏幕照样被刷穿。回显必须同样折叠。
 *
 * <p><b>两处同规则</b>（回放与实时一致是该模块既有纪律）：实时 {@code ConversationState
 * .onUserMessage} 与 {@code -c} 恢复回放 {@code HistoryReplay} 用户块。阈值与粘贴折叠
 * 对齐（&gt;400 字符或 &gt;12 行）——回显的是同一条消息，两边口径不同会显得精神分裂。
 */
class UserEchoFoldTest {

    // ── 纯函数 ─────────────────────────────────────────────

    @Test
    void shortEchoStaysVerbatim() {
        assertEquals("帮我看下 bug", ConversationState.foldUserEcho("帮我看下 bug"));
        String snippet = "try {\n  doWork();\n} catch (Exception e) {\n  log.error(e);\n}";
        assertEquals(snippet, ConversationState.foldUserEcho(snippet), "≤12 行的代码片段回显不折叠");
    }

    @Test
    @DisplayName("超长回显折成前两行 + 全文行数标记")
    void longEchoFoldedToPreviewPlusMarker() {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            if (i > 1) b.append('\n');
            b.append("line-").append(i);
        }
        String folded = ConversationState.foldUserEcho(b.toString());
        assertTrue(folded.startsWith("line-1\nline-2"), "前两行必须保留：\n" + folded);
        assertTrue(folded.contains("已折叠"), "必须说明折叠发生了：\n" + folded);
        assertTrue(folded.contains("30 行"), "必须说清全文规模：\n" + folded);
        assertFalse(folded.contains("line-3"), "第三行起不得再出现：\n" + folded);
    }

    /** 单行超长（>400 字符没有换行）：预览按字符截断——「前两行」在这里等于整行，等于没折。 */
    @Test
    void singleHugeLinePreviewIsCharCapped() {
        String oneLine = "E".repeat(5000);
        String folded = ConversationState.foldUserEcho(oneLine);
        assertTrue(folded.length() < 500, "单行超长的预览必须截断：\n" + folded.length() + " 字符");
        assertTrue(folded.contains("已折叠"));
        assertTrue(folded.contains("E"), "截断保留前缀而非整行丢弃");
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertEquals("", ConversationState.foldUserEcho(""));
        assertEquals(null, ConversationState.foldUserEcho(null));
    }

    // ── 实时通道：onUserMessage ────────────────────────────

    @Test
    @DisplayName("实时回显：长用户消息进 pending 的已是折叠形态")
    void liveUserEchoIsFolded() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            if (i > 1) b.append('\n');
            b.append("log-line-").append(i);
        }
        state.onUserMessage(1L, b.toString());
        OutputLine user = state.drainPending().stream()
                .filter(l -> l.kind() == Kind.USER).findFirst().orElseThrow();
        assertTrue(user.text().contains("log-line-1"), user.text());
        assertTrue(user.text().contains("已折叠"), "实时回显没折叠：\n" + user.text());
        assertFalse(user.text().contains("log-line-5"), user.text());
    }

    // ── 回放通道：HistoryReplay 用户块 ─────────────────────

    @Test
    @DisplayName("恢复回放：长用户块与实时同规则折叠")
    void replayedUserEchoIsFolded() {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            if (i > 1) b.append('\n');
            b.append("hist-line-").append(i);
        }
        List<OutputLine> out = HistoryReplay.toReplayLines(List.of(new UserMessage(b.toString())));
        OutputLine user = out.get(out.size() - 1);
        assertEquals(Kind.USER, user.kind());
        assertTrue(user.text().startsWith("› hist-line-1"), user.text());
        assertTrue(user.text().contains("已折叠"), "回放没折叠，重开会话照样刷屏：\n" + user.text());
    }

    @Test
    void replayedShortUserEchoUnchanged() {
        List<OutputLine> out = HistoryReplay.toReplayLines(List.of(new UserMessage("帮我看下 bug")));
        assertEquals("› 帮我看下 bug", out.get(1).text());
    }
}
