package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 斜杠补全菜单的<b>可见行封顶 + 选中项居中开窗</b>。
 *
 * <p><b>为什么要封顶</b>（2026-11-01 用户实报，pty 实机复现）：命令全表 21 条全量渲染时，
 * 计划面板（最多 12 行）+ 菜单 21 + 输入框 3 + 状态行 1 ≈ 37 行 live 区一旦超过终端行数，
 * InlineDisplay 的滚动腾行/光标回移记账整体错位——输入框边框被画两份、菜单尾部与状态行
 * 直接丢失。/model、/skills、/resume 三个选择器面板都为同款问题加过 {@code *_PICKER_CAP}，
 * 斜杠菜单是唯一漏网的。开窗公式与 {@code modelPickerChildren} 同源：以选中项为中心、
 * 尾部钳制，保证 ↓ 到最后一条时它仍可见。
 *
 * <p>断言落在 {@link ViewScreen} 渲染结果上：菜单是渲染路径，只测数据列表证不了「画没画上屏」。
 */
class CodeTuiViewSlashMenuTest {

    private static final class Handler implements SubmitHandler {
        @Override public Disposable submit(String text) { return null; }
    }

    /** 菜单行形态："  ❯ /model …" 或 "    /model …"（两空格缩进 + 可选高亮箭头）。 */
    private static final Pattern MENU_ROW = Pattern.compile("^ +(❯ +)?/[a-z]+\\b.*");

    private static long menuRowCount(String screen) {
        return screen.lines().filter(l -> MENU_ROW.matcher(l).matches()).count();
    }

    private static CodeTuiView viewWithSlash(ConversationState s, String typed) {
        CodeTuiView v = new CodeTuiView(s, new Handler(), Path.of("."));
        for (char c : typed.toCharArray()) v.feedKeyForTest(KeyEvent.ofChar(c));
        return v;
    }

    @Test
    @DisplayName("匹配超上限：只画 10 行并注明显示区间，尾部命令不进屏")
    void overCap_showsTenWithRangeNote() {
        CodeTuiView v = viewWithSlash(new ConversationState(), "/");
        String screen = ViewScreen.of(v);
        assertEquals(10, menuRowCount(screen), "菜单必须封顶 10 行:\n" + screen);
        assertTrue(screen.contains("❯ /model"), "选中项（首条）应可见:\n" + screen);
        assertFalse(screen.contains("/exit"), "第 22 条不应进屏（封顶的意义）:\n" + screen);
        assertTrue(screen.contains("显示 1-10 / 共 22 条命令"), "应注明显示区间:\n" + screen);
    }

    @Test
    @DisplayName("选中项移到中部：窗口跟随，被折掉的头几条让位")
    void selectionInMiddle_windowFollows() {
        CodeTuiView v = viewWithSlash(new ConversationState(), "/");
        for (int i = 0; i < 12; i++) v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));   // sel=12 → /review（/agents 插队后顺移一位）
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("❯ /review"), "第 13 条选中时必须可见:\n" + screen);
        assertFalse(Pattern.compile("^ +(❯ +)?/model\\b.*", Pattern.MULTILINE)
                        .matcher(screen).find(),
                "/model 已滑出窗口，不应再画（状态行的 /model 不受影响）:\n" + screen);
        assertTrue(screen.contains("显示 8-17 / 共 22 条命令"), "区间注记应跟随窗口:\n" + screen);
    }

    @Test
    @DisplayName("选中项到末尾：窗口钳制在尾部，最后一条可见")
    void selectionAtTail_windowClampedToTail() {
        CodeTuiView v = viewWithSlash(new ConversationState(), "/");
        for (int i = 0; i < 21; i++) v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));   // sel=21 → /exit（末条）
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("❯ /exit"), "最后一条选中时必须可见:\n" + screen);
        assertTrue(screen.contains("显示 13-22 / 共 22 条命令"), "窗口应钳在尾部:\n" + screen);
    }

    @Test
    @DisplayName("过滤后匹配少：全显示、无区间注记")
    void fewMatches_allShownNoNote() {
        CodeTuiView v = viewWithSlash(new ConversationState(), "/ex");   // 仅 /exit
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("❯ /exit"), "唯一匹配应可见:\n" + screen);
        assertFalse(screen.contains("共 1 条命令"), "不超上限不应有区间注记:\n" + screen);
    }
}
