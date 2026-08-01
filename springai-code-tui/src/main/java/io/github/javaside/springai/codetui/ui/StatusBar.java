package io.github.javaside.springai.codetui.ui;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;

import java.util.ArrayList;
import java.util.List;

import static io.github.javaside.springai.codetui.ui.Theme.DIM;
import static io.github.javaside.springai.codetui.ui.Theme.SHIMMER_HI;
import static io.github.javaside.springai.codetui.ui.Theme.THINK;

/**
 * 状态行的两种「动画」内容渲染：<b>波光</b>（处理中）与<b>压缩进度条</b>。都是
 * {@code (文本, animTick)} → {@link Text} 的<b>纯函数</b>，无状态、不认识 tamboui runner，
 * 视图侧用 {@code richText(...)} 包裹落帧。
 *
 * <p><b>为什么抽出</b>：这段逐字上色 / 三角波光带的下标算术，此前埋在 {@link CodeTuiView}
 * （{@link dev.tamboui.toolkit.app.InlineApp}）里无法单测。抽成纯函数后可喂 tick 断言高亮带位置、
 * 进度条往返。状态行的<b>分发</b>（按 picking/slash/compacting/status 挑哪条）仍留在视图——那是视图的
 * 编排职责，读的是 #4/#5/#6 的态；本类只把选中的「动画型」内容渲染出来。
 */
final class StatusBar {

    /**
     * 处理中状态行内容：{@code label} 上叠一道随 {@code animTick} 左→右扫过的高亮波光（表示系统在动），
     * {@code suffix}（如「· Esc 取消」）保持 {@link Theme#DIM 暗色}静态。{@code base} 是 label 非高亮处底色
     * （思考=THINK、跑工具=RUNNING）。
     */
    Text shimmer(String label, String suffix, Style base, long animTick) {
        return shimmer(label, suffix, base, animTick, null);
    }

    /**
     * 同上，但在最前面插一个静态 {@code leading} 段（{@code null} 则不插）。
     *
     * <p>用途只有一个：权限模式常驻标识（见 {@code CodeTuiView#modeTag}）。放<b>行首</b>而非行尾，
     * 是因为状态行本就接近终端宽度，尾部先被截断——而「现在会不会问你」比「Esc 取消」更不该被截掉。
     */
    Text shimmer(String label, String suffix, Style base, long animTick, Span leading) {
        List<Span> spans = shimmerSpans(label, base, animTick);
        if (leading != null) spans.add(0, leading);
        if (!suffix.isEmpty()) spans.add(Span.styled(suffix, DIM));
        return Text.from(Line.from(spans));
    }

    /** 把 label 逐字符上色：距移动中心 ≤1 的字符用 {@link Theme#SHIMMER_HI 高亮}，其余用 {@code base}，形成左→右扫过的光带。 */
    private List<Span> shimmerSpans(String label, Style base, long animTick) {
        int n = label.length();
        List<Span> spans = new ArrayList<>(n);
        if (n == 0) return spans;
        int period = n + 6;                           // 光带扫完 + 一段间隔再重来（脉冲感）
        int center = (int) ((animTick / 2) % period); // 每 2 帧(~66ms)前进一格，避免过快闪烁
        for (int i = 0; i < n; i++) {
            spans.add(Span.styled(String.valueOf(label.charAt(i)), Math.abs(i - center) <= 1 ? SHIMMER_HI : base));
        }
        return spans;
    }

    /**
     * 压缩状态行内容：「⟳ 正在压缩会话历史…（计时）· 不可中断」+ 一段左右往返的<b>不确定型</b>进度条。
     * 库不暴露压缩进度，故只做真实经过时间 + 往返光块（不伪造百分比）。{@code elapsedNanos} 由
     * {@link ConversationState#compactElapsedNanos()} 提供，{@code animTick} 驱动往返。
     */
    Text compacting(long elapsedNanos, long animTick) {
        long sec = elapsedNanos / 1_000_000_000L;
        String elapsed = sec >= 60 ? (sec / 60) + "m " + (sec % 60) + "s" : sec + "s";
        // v1 压缩不可中断（底层库调用不可取消），明确告知用户 Esc 不会打断本次压缩。
        String label = "⟳ 正在压缩会话历史… (" + elapsed + ") · 不可中断  ";

        int width = 24;                                   // 进度条格数
        int period = width * 2;                           // 往返一轮
        int pos = (int) ((animTick / 2) % period);        // 每 2 帧前进一格
        int center = pos < width ? pos : period - pos;    // 三角波：来回移动

        List<Span> spans = new ArrayList<>(width + 1);
        spans.add(Span.styled(label, THINK));
        for (int i = 0; i < width; i++) {
            boolean lit = Math.abs(i - center) <= 1;
            spans.add(Span.styled(lit ? "▰" : "▱", lit ? SHIMMER_HI : THINK));
        }
        return Text.from(Line.from(spans));
    }
}
