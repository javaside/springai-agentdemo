package io.github.javaside.springai.codetui.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死 TodoStaleReminder 状态机：事件对齐触发（完成点）/ 阈值计数 / 复位 / 回合切换 / 快照渲染 / env 解析。
 *
 * <p>两类触发各有分工（见根因文档 v7）：<b>事件对齐</b>在「完成点之后的第一次跳过更新」当场点名，
 * 负责精准；<b>阈值计数</b>在持续不更新时定期重掷，负责不漏。两者共用同一份快照。
 */
class TodoStaleReminderTest {

    private static TodoItem item(String content, Status status) {
        return new TodoItem(content, status, content + "（进行中描述）");   // record 序：content, status, activeForm
    }

    /** 阈值拉到 100：测试长度内计数路径不可能到期，任何提醒都只能来自事件对齐触发。 */
    private static TodoStaleReminder eventOnly() {
        TodoStaleReminder r = new TodoStaleReminder(100);
        r.onControllerTodoWritten(List.of(item("建表", Status.in_progress), item("接线", Status.pending)));
        return r;
    }

    /** 抠出提醒里的 JSON 块：从 `{"todos"` 到最后一个 `}`（其后的指令句不含花括号）。 */
    private static String jsonBlockOf(String note) {
        int start = note.indexOf("{\"todos\"");
        assertTrue(start >= 0, "提醒里应含 {\"todos\" 开头的 JSON 块，实际=" + note);
        return note.substring(start, note.lastIndexOf('}') + 1);
    }

    // ---------- 事件对齐触发（本轮修复的核心） ----------

    @Test
    @DisplayName("完成事件武装提醒：下一个跳过更新的调用当场提醒，并点名触发者")
    void completionEvent_armsThenNextCallFires() {
        TodoStaleReminder r = eventOnly();

        assertNull(r.reminderOrNull(1L, "Task", "{}"),
                "完成事件当刻不提醒——此刻清单必然还没机会更新，提醒只会每次委派都响一遍");
        String note = r.reminderOrNull(1L, "Read", "{}");
        assertNotNull(note, "完成事件后的第一个跳过更新调用应当场提醒");
        assertTrue(note.contains("[任务面板提醒]"), "须带钉死前缀，实际=" + note);
        assertTrue(note.contains("Task"), "须点名触发提醒的完成事件，实际=" + note);
        assertTrue(note.contains("建表") && note.contains("接线"), "须含当前清单快照，实际=" + note);
    }

    @Test
    @DisplayName("完成事件后紧跟 TodoWrite：不提醒，也不残留武装")
    void completionEvent_thenTodoWrite_noReminder() {
        TodoStaleReminder r = eventOnly();

        assertNull(r.reminderOrNull(1L, "Task", "{}"), "完成事件当刻不提醒");
        assertNull(r.reminderOrNull(1L, "TodoWrite", "{}"), "更新动作本身不提醒");
        assertNull(r.reminderOrNull(1L, "Read", "{}"), "已更新过，不得再提醒");
    }

    @Test
    @DisplayName("事件提醒每回合至多一次；换回合重新武装")
    void completionEvent_atMostOncePerTurn() {
        TodoStaleReminder r = eventOnly();

        r.reminderOrNull(1L, "Task", "{}");
        assertNotNull(r.reminderOrNull(1L, "Read", "{}"), "本回合首个完成点要提醒");

        r.reminderOrNull(1L, "Task", "{}");
        assertNull(r.reminderOrNull(1L, "Read", "{}"), "同回合第二次跳过不再刷（每回合至多一次）");

        r.reminderOrNull(2L, "Task", "{}");
        assertNotNull(r.reminderOrNull(2L, "Read", "{}"), "新回合重新武装");
    }

    @Test
    @DisplayName("git commit 是完成事件；普通 Bash 命令不是")
    void gitCommit_isCompletionEvent_plainBashIsNot() {
        TodoStaleReminder r = new TodoStaleReminder(100);
        r.onControllerTodoWritten(List.of(item("提交后要更新的任务", Status.in_progress)));

        assertNull(r.reminderOrNull(1L, "Bash", "cat README.md && ls -la"), "读文件不是完成事件");
        assertNull(r.reminderOrNull(1L, "Read", "{}"), "普通 Bash 不武装提醒");

        assertNull(r.reminderOrNull(1L, "Bash", "cd /p && git commit -m \"docs: x\""), "commit 当刻不提醒");
        String note = r.reminderOrNull(1L, "Read", "{}");
        assertNotNull(note, "commit 之后第一个跳过更新的调用应提醒");
        assertTrue(note.contains("提交后要更新的任务"), "须含快照内容，实际=" + note);
    }

    @Test
    @DisplayName("提醒里的清单 JSON 能被真实 TodoWriteTool 原样接受（照抄即用契约）")
    void reminderJson_roundTripsThroughRealTodoWriteTool() {
        TodoStaleReminder r = new TodoStaleReminder(100);
        r.onControllerTodoWritten(List.of(
                item("含\"引号\"与\\反斜杠的任务", Status.in_progress),
                item("待办项", Status.pending)));
        r.reminderOrNull(1L, "Task", "{}");
        String note = r.reminderOrNull(1L, "Read", "{}");
        assertNotNull(note);

        String json = jsonBlockOf(note);
        // 走生产同一条装配路径：AgentTools 注册的是 TodoWriteToolAdapter（库工具的双层 todos 嵌套绑不上），
        // 所以这里也必须用适配器——用库工具会让用例永远红在「形状不对」上，测不到真实契约
        TodoWriteTool delegate = TodoWriteTool.builder().todoEventHandler(todos -> { }).build();
        ToolCallback tool = ToolCallbacks.from(new Object[]{new TodoWriteToolAdapter(delegate)})[0];
        String out = tool.call(json, new ToolContext(Map.of()));

        assertTrue(out.contains("modified successfully"),
                "提醒里的 JSON 必须能被真实工具原样接受（照抄即用），实际=" + out);
    }

    @Test
    @DisplayName("快照过大时退化为紧凑列表（提醒文本不无界膨胀）")
    void reminder_fallsBackToTerseListWhenSnapshotHuge() {
        TodoStaleReminder r = new TodoStaleReminder(1);
        List<TodoItem> items = new ArrayList<>();
        String long200 = "长".repeat(200);
        for (int i = 0; i < 40; i++) {
            items.add(item(i + "-" + long200, Status.pending));
        }
        r.onControllerTodoWritten(items);

        String note = r.reminderOrNull(1L, "Bash", "{}");
        assertNotNull(note);
        assertFalse(note.contains("{\"todos\""), "超上限不注入整份 JSON，实际长度=" + note.length());
        assertTrue(note.contains("…等共 40 项"), "退化为紧凑列表并折行，实际=" + note);
        assertTrue(note.length() < 3000, "提醒文本须有上界，实际=" + note.length());
    }

    // ---------- 阈值计数触发（原有行为，本轮保持不变） ----------

    @Test
    @DisplayName("低于阈值不提醒；到阈值提醒并复位（下轮重新计满）")
    void threshold_fires_then_resets() {
        TodoStaleReminder r = new TodoStaleReminder(3);
        r.onControllerTodoWritten(List.of(item("修登录页", Status.in_progress), item("跑测试", Status.pending)));
        assertNull(r.reminderOrNull(1L, "Bash"), "第 1 次不提醒");
        assertNull(r.reminderOrNull(1L, "Bash"), "第 2 次不提醒");
        String note = r.reminderOrNull(1L, "Bash");
        assertNotNull(note, "第 3 次到阈值应提醒");
        assertTrue(note.contains("[任务面板提醒]"), "须带钉死前缀，实际=" + note);
        assertTrue(note.contains("TodoWrite"), "提醒须点名 TodoWrite");
        assertTrue(note.contains("修登录页") && note.contains("跑测试"), "快照须含未完成条目");
        assertTrue(note.contains("2/2 项未完成"), "须含未完成计数，实际=" + note);
        assertTrue(jsonBlockOf(note).contains("in_progress"), "快照须带 status（照抄只改 status 的前提）");
        // 复位：提醒后重新从 0 计数
        assertNull(r.reminderOrNull(1L, "Bash"));
        assertNull(r.reminderOrNull(1L, "Bash"));
        assertNotNull(r.reminderOrNull(1L, "Bash"));
    }

    @Test
    @DisplayName("快照为空或全部 completed 不提醒")
    void noReminder_whenDoneOrEmpty() {
        TodoStaleReminder r = new TodoStaleReminder(1);
        for (int i = 0; i < 5; i++) {
            assertNull(r.reminderOrNull(1L, "Bash"), "空快照永不提醒");
        }
        r.onControllerTodoWritten(List.of(item("a", Status.completed), item("b", Status.completed)));
        for (int i = 0; i < 5; i++) {
            assertNull(r.reminderOrNull(1L, "Bash"), "全部完成不提醒");
        }
    }

    @Test
    @DisplayName("onControllerTodoWritten 复位计数并整体换快照")
    void controllerWrite_resetsCounter() {
        TodoStaleReminder r = new TodoStaleReminder(2);
        r.onControllerTodoWritten(List.of(item("旧任务", Status.in_progress)));
        r.reminderOrNull(1L, "Bash");                       // 计到 1
        r.onControllerTodoWritten(List.of(item("新任务", Status.in_progress)));
        assertNull(r.reminderOrNull(1L, "Bash"), "复位后从 0 重计");
        String note = r.reminderOrNull(1L, "Bash");
        assertNotNull(note);
        assertTrue(note.contains("新任务"));
        assertFalse(note.contains("旧任务"), "快照应被整体替换");
    }

    @Test
    @DisplayName("回合切换复位计数；快照保留")
    void turnChange_resetsCounter_keepsSnapshot() {
        TodoStaleReminder r = new TodoStaleReminder(2);
        r.onControllerTodoWritten(List.of(item("跨回合任务", Status.in_progress)));
        r.reminderOrNull(1L, "Bash");                       // turn 1 计到 1
        assertNull(r.reminderOrNull(2L, "Bash"), "turn 2 计数归零");
        String note = r.reminderOrNull(2L, "Bash");
        assertNotNull(note);
        assertTrue(note.contains("跨回合任务"), "快照跨回合保留");
    }

    @Test
    @DisplayName("TodoWrite 本身不提醒，并复位计数（它就是更新动作）")
    void todoWriteName_neverFiresAndResetsCounter() {
        TodoStaleReminder r = new TodoStaleReminder(2);
        r.onControllerTodoWritten(List.of(item("t", Status.in_progress)));
        assertNull(r.reminderOrNull(1L, "TodoWrite"), "工具名 TodoWrite 直接豁免");
        assertNull(r.reminderOrNull(1L, "Bash"));                       // 计到 1
        assertNull(r.reminderOrNull(1L, "TodoWrite"), "更新动作复位计数");
        assertNull(r.reminderOrNull(1L, "Bash"), "复位后重新计满（1）");
        assertNotNull(r.reminderOrNull(1L, "Bash"), "第 2 次 Bash 到阈值");
    }

    @Test
    @DisplayName("reset 清空快照（/clear 语义）")
    void reset_clearsSnapshot() {
        TodoStaleReminder r = new TodoStaleReminder(1);
        r.onControllerTodoWritten(List.of(item("t", Status.in_progress)));
        r.reset();
        assertNull(r.reminderOrNull(9L, "Bash"), "reset 后不再提醒");
    }

    @Test
    @DisplayName("清单在阈值内时注入整份 JSON 快照（不截断——部分清单被照抄会静默丢项）")
    void reminder_emitsFullJsonSnapshot() {
        TodoStaleReminder r = new TodoStaleReminder(1);
        String long120 = "长".repeat(120);
        List<TodoItem> items = new ArrayList<>();
        items.add(item(long120, Status.in_progress));
        for (int i = 1; i < 12; i++) {
            items.add(item("任务" + i, Status.pending));    // 共 12 项未完成
        }
        r.onControllerTodoWritten(items);

        String note = r.reminderOrNull(1L, "Bash");
        assertNotNull(note);
        assertTrue(note.contains("12/12 项未完成"), "未完成计数 X/Y，实际=" + note);
        String json = jsonBlockOf(note);
        assertTrue(json.contains("任务11"), "整份清单都要进 JSON");
        assertTrue(json.contains(long120), "条目不截断：照抄必须无损");
    }

    @Test
    @DisplayName("env 解析：默认 8；0/负数停用；非法回退默认")
    void parseEvery_defaultsAndEdges() {
        assertEquals(8, TodoStaleReminder.parseEvery(null));
        assertEquals(8, TodoStaleReminder.parseEvery(" 8 "));
        assertEquals(0, TodoStaleReminder.parseEvery("0"), "0=停用须原样保留");
        assertEquals(-1, TodoStaleReminder.parseEvery("-1"), "负数=停用");
        assertEquals(8, TodoStaleReminder.parseEvery("abc"), "非法回退默认");
        assertEquals(8, TodoStaleReminder.parseEvery(""), "空串回退默认");
        assertFalse(new TodoStaleReminder(0).enabled());
        assertTrue(new TodoStaleReminder(1).enabled());
    }
}
