package io.github.javaside.springai.tamboui.demo;

import java.util.ArrayList;
import java.util.List;

/**
 * 假流式分帧（示例④使用）：把一段完整文本按「每 tick 一个字符」吐出，
 * 模拟模型流式输出。两条通道的分工在这里见形状——
 * 未定稿的残段留在 liveTail（live 区原地刷新），遇 \n 定稿的行进 settledLines
 * （下沉 scrollback），全文耗尽后残段自动定稿（对应回合结束）。
 *
 * <p>真实 code-tui 里这件事由模型 SSE 流驱动；这里用定时器驱动同一形态。</p>
 */
public final class FakeStreamer {

    /** 当前视图：liveTail = 流式残行；settledLines = 已定稿待下沉的行。 */
    public record StreamView(String liveTail, List<String> settledLines) {
    }

    private final String full;
    private final List<String> settled = new ArrayList<>();
    private final StringBuilder tail = new StringBuilder();
    private int position;
    private int settledUpTo;   // settledLines 已被取走的条数（demo 每帧下沉后标记）

    public FakeStreamer(String fullText) {
        this.full = fullText;
    }

    /** 推进一个字符。到达末尾时残段自动定稿。 */
    public void advance() {
        if (done()) {
            return;
        }
        char c = full.charAt(position++);
        if (c == '\n') {
            settled.add(tail.toString());
            tail.setLength(0);
        } else {
            tail.append(c);
        }
        if (position == full.length() && tail.length() > 0) {
            settled.add(tail.toString());
            tail.setLength(0);
        }
    }

    /** 全文是否已耗尽。 */
    public boolean done() {
        return position >= full.length();
    }

    /** 当前视图。settledLines 为「全部已定稿行」（含此前已下沉的）。 */
    public StreamView view() {
        return new StreamView(tail.toString(), List.copyOf(settled));
    }

    /** 自上次调用以来新增的定稿行（demo 用它决定本轮要 println 下沉哪些行）。 */
    public List<String> drainNewlySettled() {
        List<String> fresh = settled.subList(settledUpTo, settled.size());
        List<String> copy = List.copyOf(fresh);
        settledUpTo = settled.size();
        return copy;
    }
}
