package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 📋 计划面板超上限时的<b>折叠窗口方向</b>：锚定第一条非完成项（▶ 恒可见，排在后面的待办
 * 随推进滑入视野），而不是旧版「永远只显示前 10 条」——计划一旦超过 10 条，最后要运行的
 * 几项永远进不了屏幕（用户实测报告）。
 *
 * <p>断言落在 {@link ViewScreen} 渲染结果上：todoChildren 是私有渲染路径，只测纯函数
 * 证不了「真的画上屏、真的没画」。
 */
class CodeTuiViewTodoPanelTest {

    private static final class Handler implements SubmitHandler {
        @Override public Disposable submit(String text) { return null; }
    }

    /** 生成 n 条计划行，第 doneCount+1 条为 ▶（无则全按 fill 状态），其余 ○/✓ 按参数。 */
    private static List<String> plan(int n, int doneCount, String fill) {
        List<String> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            if (i <= doneCount) l.add("✓ 任务" + i);
            else if (i == doneCount + 1) l.add("▶ 任务" + i);
            else l.add(fill + " 任务" + i);
        }
        return l;
    }

    /** 计划落进面板并回读屏幕。onTurnStarted 必须先调：turn 不匹配的 todo 会被丢（迟到过滤）。 */
    private static String screenOf(ConversationState s, List<String> todos) {
        s.onTurnStarted(1);
        s.onTodoUpdated(1, todos);
        return ViewScreen.of(new CodeTuiView(s, new Handler(), Path.of(".")));
    }

    @Test
    @DisplayName("超过上限：运行项 + 尾部待办可见，前面已完成的折叠并注明")
    void overCap_showsRunningAndTail_foldsLeadingDone() {
        // 13 条：前 6 ✓、第 7 ▶、后 6 ○。锚定 ▶ 上方留 2 行 → 窗口取满 10 条时贴到末尾：
        // 任务4..13 可见，任务1..3 折叠，无「还有」注记。
        String screen = screenOf(new ConversationState(), plan(13, 6, "○"));
        assertTrue(screen.contains("▶ 任务7"), "运行项必须可见:\n" + screen);
        assertTrue(screen.contains("○ 任务13"), "最后一条（最后才运行）必须可见——旧版永远看不到:\n" + screen);
        assertTrue(screen.contains("… 前 3 项已折叠"), "顶部折叠应注明条数:\n" + screen);
        assertFalse(screen.contains("任务3 "), "被折叠的前 3 条不得再出现:\n" + screen);
        assertFalse(screen.contains("还有"), "窗口已贴尾，不应再有尾部注记:\n" + screen);
    }

    @Test
    @DisplayName("刚写完计划（全待办）：从头部看全貌，尾部注记还有几项")
    void overCap_allPending_showsHeadWithTailNote() {
        // 无 ✓/▶：锚在第 1 条，窗口=前 10 条 + 「还有 3 项」。
        String screen = screenOf(new ConversationState(), plan(13, -1, "○"));
        assertTrue(screen.contains("○ 任务10"), "前 10 条应可见:\n" + screen);
        assertFalse(screen.contains("任务13"), "超出窗口的尾部不可见:\n" + screen);
        assertTrue(screen.contains("… 还有 3 项"), "尾部应注明条数:\n" + screen);
        assertFalse(screen.contains("已折叠"), "头部无折叠:\n" + screen);
    }

    @Test
    @DisplayName("全部完成：窗口贴尾显示最近的完成项")
    void overCap_allDone_showsTail() {
        String screen = screenOf(new ConversationState(), plan(13, 13, "✓"));
        assertTrue(screen.contains("✓ 任务13"), "最后完成的必须可见:\n" + screen);
        assertTrue(screen.contains("… 前 3 项已折叠"), "顶部折叠应注明条数:\n" + screen);
        assertFalse(screen.contains("任务2 "), "被折叠的前 3 条不得再出现:\n" + screen);
    }

    @Test
    @DisplayName("运行项排在末尾附近：它和它后面的待办必须可见")
    void overCap_runningNearTail_staysVisible() {
        // 13 条：前 11 ✓、第 12 ▶、第 13 ○——旧版显示前 10 条，运行项与其后待办全被折掉。
        String screen = screenOf(new ConversationState(), plan(13, 11, "○"));
        assertTrue(screen.contains("▶ 任务12"), "末尾附近的运行项必须可见:\n" + screen);
        assertTrue(screen.contains("○ 任务13"), "运行项后面的待办必须可见:\n" + screen);
    }

    @Test
    @DisplayName("不超上限：全显示、无任何折叠注记")
    void underCap_showsAllWithoutNotes() {
        String screen = screenOf(new ConversationState(), plan(10, 5, "○"));
        assertTrue(screen.contains("✓ 任务1"), "首条应可见:\n" + screen);
        assertTrue(screen.contains("○ 任务10"), "末条应可见:\n" + screen);
        assertFalse(screen.contains("已折叠"), "不应有顶部注记:\n" + screen);
        assertFalse(screen.contains("还有"), "不应有尾部注记:\n" + screen);
    }
}
