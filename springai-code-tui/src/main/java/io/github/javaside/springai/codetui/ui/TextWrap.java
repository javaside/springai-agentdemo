package io.github.javaside.springai.codetui.ui;

import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 带样式的按显示宽度软折行（中文 2 列）：把一条可能超宽的 {@link Text} 拆成若干条
 * 宽度 ≤ width 的 Text，每条恰好一物理行，span 样式跨拆分点保留。
 *
 * <p><b>为什么必须有它</b>：内联 {@code InlineDisplay.println(Text)} 是定宽渲染，超宽<b>截断</b>
 * ——模型正文一行超过终端宽度，右边直接消失（用户实报「文字没显示全」）。而「一个 println =
 * 一个物理行」的纪律又禁止让终端自己折行（会推歪显示区记账），所以只能打印前拆好。
 * 纯字符串的对应物是 {@code CodeTuiView.wrapSegments}；本类是它的带样式版。
 */
final class TextWrap {

    private TextWrap() {
    }

    /** 拆行；空 Text/空行产出一条空行（保持行数语义）。width < 1 按 1 处理，宽字符放不下也硬吃 1 个防死循环。 */
    static List<Text> wrap(Text text, int width) {
        int w = Math.max(1, width);
        List<Text> out = new ArrayList<>();
        for (Line line : text.lines()) {
            wrapLine(line, w, out);
        }
        if (out.isEmpty()) {
            out.add(Text.from(""));    // 无行的 Text（如 Text.from("") 的空态）也保证产出一行
        }
        return out;
    }

    private static void wrapLine(Line line, int w, List<Text> out) {
        List<Span> cur = new ArrayList<>();
        int used = 0;
        for (Span sp : line.spans()) {
            String rest = sp.content();
            while (!rest.isEmpty()) {
                String take = CharWidth.substringByWidth(rest, w - used);
                if (take.isEmpty()) {
                    if (used > 0) {                       // 本行剩余宽度连下个字符都放不下：换行再试
                        out.add(Text.from(Line.from(cur)));
                        cur = new ArrayList<>();
                        used = 0;
                        continue;
                    }
                    take = rest.substring(0, 1);          // 整行都放不下 1 个宽字符：硬吃 1 个保证前进
                }
                cur.add(Span.styled(take, sp.style()));
                used += CharWidth.of(take);
                rest = rest.substring(take.length());
                if (used >= w && !rest.isEmpty()) {       // 行满且还有内容：落行
                    out.add(Text.from(Line.from(cur)));
                    cur = new ArrayList<>();
                    used = 0;
                }
            }
        }
        out.add(Text.from(Line.from(cur)));               // 收尾：末段（或空行本身）
    }
}
