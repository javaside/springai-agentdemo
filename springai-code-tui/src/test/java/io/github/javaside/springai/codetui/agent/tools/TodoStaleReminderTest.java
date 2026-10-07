package io.github.javaside.springai.codetui.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 钉死 TodoStaleReminder 状态机：阈值/复位/回合切换/全完成/文案锚点/env 解析。 */
class TodoStaleReminderTest {

    private static TodoItem item(String content, Status status) {
        return new TodoItem(content, status, content + "（进行中描述）");   // record 序：content, status, activeForm
    }

    @Test
    @DisplayName("低于阈值不提醒；到阈值提醒并复位（下轮重新计满）")
    void threshold_fires_then_resets() {
        TodoStaleReminder r = new TodoStaleReminder(3);
        r.onControllerTodoWritten(List.of(item("修登录页", Status.in_progress), item("跑测试", Status.pending)));
        assertNull(r.reminderOrNull(1L, "Bash"), "第 1 次不提醒");
        assertNull(r.reminderOrNull(1L, "Bash"), "第 2 次不提醒");
        String note = r.reminderOrNull(1L, "Bash");
        assertNotNull(note, "第 3 次到阈值应提醒");
        // 断言自定义文案锚点：改坏前缀/漏掉工具名/漏掉条目内容都会红
        assertTrue(note.contains("[任务面板提醒]"), "提醒须带钉死前缀，实际=" + note);
        assertTrue(note.contains("TodoWrite"), "提醒须点名 TodoWrite");
        assertTrue(note.contains("修登录页") && note.contains("跑测试"), "快照须含未完成条目");
        assertTrue(note.contains("▶"), "进行中条目须有 ▶ 标记");
        assertTrue(note.contains("○"), "待办条目须有 ○ 标记");
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
    @DisplayName("工具名 TodoWrite 豁免且不消耗计数")
    void todoWriteName_neverCounts() {
        TodoStaleReminder r = new TodoStaleReminder(2);
        r.onControllerTodoWritten(List.of(item("t", Status.in_progress)));
        assertNull(r.reminderOrNull(1L, "TodoWrite"), "工具名 TodoWrite 直接豁免");
        assertNull(r.reminderOrNull(1L, "Bash"));           // 1
        assertNull(r.reminderOrNull(1L, "TodoWrite"), "豁免不消耗计数");
        assertNotNull(r.reminderOrNull(1L, "Bash"), "第 2 次 Bash 照常到阈值");
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
    @DisplayName("未完成项超过 10 行折为汇总行；单条超 80 字符截断")
    void render_capsAndTruncates() {
        TodoStaleReminder r = new TodoStaleReminder(1);
        String long120 = "长".repeat(120);
        List<TodoItem> items = new java.util.ArrayList<>();
        items.add(item(long120, Status.in_progress));
        for (int i = 1; i < 12; i++) {
            items.add(item("任务" + i, Status.pending));    // 共 12 项未完成
        }
        r.onControllerTodoWritten(items);
        String note = r.reminderOrNull(1L, "Bash");
        assertNotNull(note);
        assertTrue(note.contains("…等共 12 项"), "超 10 行折汇总，实际=" + note);
        assertFalse(note.contains("任务11"), "第 11 条不逐行展开");
        assertFalse(note.contains("长".repeat(90)), "超长条目截断");
        assertTrue(note.contains("12/12 项未完成"), "未完成计数 X/Y");
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
