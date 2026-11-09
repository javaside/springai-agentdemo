package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.style.Color;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.InlineToolkit;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.widgets.input.TextAreaState;
import io.github.javaside.springai.tamboui.TambouiDemo;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textArea;

/**
 * 第 5 课 Toolkit DSL —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>同一类需求换一种写法：不再自己切 Rect、自己写 handleKeyEvent——
 * {@code render()} 只声明<b>整棵 UI 树</b>（panel/column/row/textArea），布局、焦点、
 * 输入编辑全部交给框架。这是官方推荐的日常主力层级。</p>
 *
 * <p>演示的应用是「课程笔记」：左边输入（原生 textArea，多行编辑/光标/中文输入免写一行
 * 键盘代码——对比第 4 课），右边统计面板（每帧由 render() 从状态现算），输入为空时出现
 * 提示面板、有内容即收起（{@code scope} 隐藏 = 面板消失）。</p>
 *
 * <p><b>本课核心认知</b>：声明式 = 你只描述「UI 应该长什么样（随状态）」，框架决定
 * 「怎么画、怎么把事件送到焦点元素」。</p>
 */
public final class ToolkitDslDemo extends ToolkitApp implements TambouiDemo {

    private final TextAreaState notes = new TextAreaState();

    @Override
    public int order() {
        return 5;
    }

    @Override
    public String title() {
        return "Toolkit DSL 声明式 UI（panel/column/textArea/scope——框架管布局与输入）";
    }

    @Override
    public void run() throws Exception {
        super.run();   // ToolkitApp 自举（全屏）
    }

    /**
     * 键位定制：退出只留 Ctrl+C（默认还绑 q/Q——输入框需要能打出这些字母），
     * 并解绑焦点导航让 Tab 落进输入框。与 code-tui 的 configure 同款考量。
     */
    @Override
    protected TuiConfig configure() {
        TuiConfig base = super.configure();
        return base.toBuilder()
                .bracketedPaste(true)
                .bindings(base.bindings().toBuilder()
                        .rebind(KeyTrigger.ctrl('c'), Actions.QUIT)
                        .unbind(Actions.FOCUS_NEXT)
                        .unbind(Actions.FOCUS_PREVIOUS)
                        .build())
                .build();
    }

    @Override
    protected void onStart() {
        notes.insert("在这里记点什么…（试试中文与多行）");
    }

    @Override
    protected Element render() {
        NotesStats stats = NotesStats.of(notes.text());
        boolean empty = notes.text().isBlank();
        return column(
                panel("第 5 课 · Toolkit DSL——render() 只声明这棵树",
                        text("布局/焦点/输入编辑都是框架的事；每个按键后框架重跑 render()，UI 自然跟随状态")),
                row(
                        panel("笔记（原生 textArea）", textArea(notes)),
                        column(
                                panel("统计（每帧现算）", richText(stats(stats))),
                                // scope：条件为 false 时整块隐藏——「面板用完即收起」的全部机制
                                InlineToolkit.scope(empty,
                                        panel("提示", text("输入为空时这块才出现；开打字它就收起"))))),
                text("Ctrl+C 退出（输入框要能打字，退出键只留 Ctrl+C）"));
    }

    private static Text stats(NotesStats s) {
        return Text.from(
                Line.from(Span.styled("字符 " + s.chars(), dev.tamboui.style.Style.create().fg(Color.CYAN))),
                Line.from(Span.styled("行数 " + s.lines() + "（非空 " + s.nonEmptyLines() + "）",
                        dev.tamboui.style.Style.create().fg(Color.GREEN))));
    }
}
