package io.github.javaside.springai.tamboui;

import dev.tamboui.widgets.input.TextAreaState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 上游 TextAreaState 的行为契约测试（示例④输入框直接依赖）：
 * 插入、多行换行、跨行回删、setText/clear。库升级若改动这些行为，在此红灯。
 */
class TextAreaStateTest {

    @Test
    void insertCharsAccumulateInOrder() {
        TextAreaState state = new TextAreaState();
        state.insert('a');
        state.insert('b');
        assertEquals("ab", state.text());
        assertEquals(0, state.cursorRow());
        assertEquals(2, state.cursorCol());   // 光标在行尾
        assertEquals(1, state.lineCount());
    }

    @Test
    void newlineInsertSplitsLines() {
        TextAreaState state = new TextAreaState();
        state.insert("行1");
        state.insert('\n');
        state.insert("行2");
        assertEquals(2, state.lineCount());
        assertEquals("行1", state.getLine(0));
        assertEquals("行2", state.getLine(1));
    }

    @Test
    void deleteBackwardJoinsLinesWhenAtLineStart() {
        TextAreaState state = new TextAreaState();
        state.insert("ab");
        state.insert('\n');
        state.insert("c");        // 现在两行："ab" / "c"，光标在第二行行尾
        state.deleteBackward();   // 删掉 c，仍在第二行行首
        assertEquals(2, state.lineCount());
        state.deleteBackward();   // 在行首再删 → 回到上一行行尾
        assertEquals("ab", state.text());
        assertEquals(1, state.lineCount());
        assertEquals(2, state.cursorCol());
    }

    @Test
    void setTextReplacesContent() {
        TextAreaState state = new TextAreaState();
        state.insert("旧的");
        state.setText("新的");
        assertEquals("新的", state.text());
    }

    @Test
    void clearEmptiesContent() {
        TextAreaState state = new TextAreaState();
        state.insert("内容");
        state.clear();
        assertEquals("", state.text());
        assertEquals(1, state.lineCount());
    }
}
