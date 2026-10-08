package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务清单纪律段（注入系统提示词）：把更新动作<b>绑定到具体完成事件</b>，而不是笼统要求「记得更新」。
 *
 * <p>根因文档 v7 的度量依据：模型在「完成时刻」的记账槽位会被更具体的动作占掉
 * （SDD 台账行、委派返回、长流程文本）——非 SDD 会话里「提交/记完账顺手更新清单」占 63%，
 * SDD 会话里掉到 37%。纪律段要做的就是把这一槽位显式钉回 TodoWrite。
 */
class AgentToolsTodoDisciplineTest {

    @Test
    @DisplayName("纪律段绑定「提交 / 台账 / 委派返回」三类完成事件，并点名工具")
    void disciplineBindsUpdateToCompletionEvents() {
        String d = AgentTools.TODO_DISCIPLINE;

        assertTrue(d.contains("TodoWrite"), "须点名工具，实际=" + d);
        assertTrue(d.contains("提交"), "须把更新绑定到提交事件");
        assertTrue(d.contains("台账"), "须把更新绑定到台账/进度记录事件（SDD 场景的完成标记）");
        assertTrue(d.contains("子 agent"), "须声明委派返回过审 = 一个任务完成");
        assertTrue(d.contains("completed") && d.contains("in_progress"),
                "须写明要改哪两个 status（照做即对）");
    }

    /** 该段作为 param 值注入，正文不得含花括号，否则 StringTemplate 会炸掉整个系统提示渲染。 */
    @Test
    @DisplayName("纪律段不含花括号（StringTemplate 安全）")
    void disciplineContainsNoTemplateBraces() {
        String d = AgentTools.TODO_DISCIPLINE;
        assertFalse(d.contains("{") || d.contains("}"), "纪律段正文不得含花括号，实际=" + d);
    }
}
