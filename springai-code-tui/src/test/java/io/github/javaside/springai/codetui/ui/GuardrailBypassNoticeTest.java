package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BYPASS 放行内置底线时的<b>留痕</b>。
 *
 * <p>内置底线的价值有两半：拦住你、以及让你知道发生了什么。BYPASS 放弃了前一半，
 * 这批用例钉住后一半——半无人值守场景下人会离开、之后回来看，留痕不是可选项。
 */
class GuardrailBypassNoticeTest {

    /** 即时行：正在发生时屏幕上就要有。 */
    @Test
    @DisplayName("每次放行都立刻进 scrollback")
    void eachBypassProducesAnImmediateLine() {
        ConversationState s = new ConversationState();
        s.onGuardrailBypassed(1L, "写入 .git/ 内部：/p/.git/hooks/pre-commit");
        String text = drainText(s);
        assertTrue(text.contains("BYPASS"), text);
        assertTrue(text.contains(".git/hooks/pre-commit"), text);
    }

    /** 回合末汇总：人回来时不必翻几百行 scrollback。 */
    @Test
    @DisplayName("回合结束汇总本回合全部放行")
    void turnCompleteSummarisesAllBypassesOfThatTurn() {
        ConversationState s = new ConversationState();
        s.onGuardrailBypassed(1L, "写入 .git/ 内部：a");
        s.onGuardrailBypassed(1L, "读取凭据：b");
        drainText(s);                      // 清掉即时行，只看汇总
        s.onTurnComplete(1L);
        String text = drainText(s);
        assertTrue(text.contains("2"), "汇总没说放行了几个：" + text);
        assertTrue(text.contains("a"), text);
        assertTrue(text.contains("b"), text);
    }

    /** 汇总要去重——同一个操作在一个回合里可能命中多次。 */
    @Test
    @DisplayName("汇总去重")
    void summaryDeduplicatesRepeatedBypasses() {
        ConversationState s = new ConversationState();
        s.onGuardrailBypassed(1L, "写入 .git/ 内部：a");
        s.onGuardrailBypassed(1L, "写入 .git/ 内部：a");
        drainText(s);
        s.onTurnComplete(1L);
        String text = drainText(s);
        assertTrue(text.contains("1"), "重复项没去重：" + text);
        assertFalse(text.contains("2"), "重复项没去重：" + text);
    }

    /** 汇总只列本回合的——上一回合的账不该算到这一回合头上。 */
    @Test
    @DisplayName("汇总只覆盖当前回合")
    void summaryOnlyCoversTheCurrentTurn() {
        ConversationState s = new ConversationState();
        s.onGuardrailBypassed(1L, "回合一的操作");
        drainText(s);
        s.onTurnComplete(1L);
        drainText(s);
        s.onGuardrailBypassed(2L, "回合二的操作");
        drainText(s);
        s.onTurnComplete(2L);
        String text = drainText(s);
        assertTrue(text.contains("回合二的操作"), text);
        assertFalse(text.contains("回合一的操作"), "上一回合的记录漏进来了：" + text);
    }

    /**
     * 汇总出完就得清空——重复的 onTurnComplete 不该把同一笔账再刷一遍。
     *
     * <p>这条不是凑数：上面那条「只覆盖当前回合」<b>抓不到</b>「flush 后忘了清空」——
     * 换回合时的重置顺手把残留清掉了，实测把 clear 删掉六条用例照样全绿。
     * 能把缺陷暴露出来的观察点是<b>同一个回合被完成两次</b>（迟到 / 重复的完成事件，
     * 本类各处的迟到过滤说明这在真实路径上会发生）。
     */
    @Test
    @DisplayName("汇总只出一次")
    void summaryIsFlushedOnlyOnce() {
        ConversationState s = new ConversationState();
        s.onGuardrailBypassed(1L, "写入 .git/ 内部：a");
        drainText(s);
        s.onTurnComplete(1L);
        assertTrue(drainText(s).contains("a"), "第一次完成就该出汇总");
        s.onTurnComplete(1L);              // 重复 / 迟到的完成事件
        assertEquals("", drainText(s).trim(), "汇总被刷了第二遍（flush 后没清空）");
    }

    /** 没有任何放行时，回合结束不该多出一行噪音。 */
    @Test
    @DisplayName("无放行则无汇总")
    void turnWithNoBypassProducesNoSummary() {
        ConversationState s = new ConversationState();
        s.onTurnComplete(1L);
        assertEquals("", drainText(s).trim());
    }

    /**
     * 铁律：一个 {@code OutputLine} = 一个物理行。
     *
     * <p>汇总有多行就 push 多次；塞进一个字符串里的 {@code \n} 会被 scrollback 的 println
     * 塌成一行并截断，人回来时看到的就是被吃掉的半句话。
     */
    @Test
    @DisplayName("汇总每行一个 OutputLine，绝不含换行")
    void summaryLinesNeverContainNewline() {
        ConversationState s = new ConversationState();
        s.onGuardrailBypassed(1L, "写入 .git/ 内部：a");
        s.onGuardrailBypassed(1L, "读取凭据：b");
        s.onTurnComplete(1L);
        for (ConversationState.OutputLine l : s.drainPending()) {
            assertFalse(l.text().contains("\n"), "行内含换行会被 println 塌掉：" + l.text());
        }
    }

    /** 取走当前待显示的行并拼成一段文本。 */
    private static String drainText(ConversationState s) {
        StringBuilder b = new StringBuilder();
        for (ConversationState.OutputLine l : s.drainPending()) {
            b.append(l.text()).append('\n');
        }
        return b.toString();
    }
}
