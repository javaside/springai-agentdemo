package io.github.javaside.springai.codetui.ui;

/**
 * 「宽度折腾完、已经停稳」的判定器——纯状态机，把连续 resize 合并为一次重放。
 *
 * <p>拖拽过程中不再逐事件清屏：那会把清除与重画的中间态直接暴露给 Windows Terminal。
 * 每次宽度变化只重置静默计数；停稳之后由 {@code CodeTuiView.replayAfterResize} 把整屏连回滚
 * 缓冲一起抹掉、按新宽度全量重放输出留底。重放从应用自己的输出留底重建，不依赖终端
 * reflow 的中间行为。
 *
 * <p><b>为什么敢用「停稳才干重活」的防抖</b>（上一版防抖翻过车，这里不一样）：翻车的那版每次
 * 触发都留下永久残留，防抖参数怎么调都错；重放是<b>幂等的全量重建</b>，多触发一次只是多重建
 * 一次，屏幕结果相同，防抖只影响「什么时候干」，不影响「干完对不对」。
 *
 * <p>手拖窗口是断续的：每个超过静默窗口的停顿都会触发一次重放——没关系，见上。
 */
final class ResizeSettle {

    private final int quietTicks;
    private boolean dirty;       // 有宽度变化，欠一次重放
    private int quiet;           // 宽度连续未变的帧数

    /**
     * @param quietTicks 宽度不再变化后等几帧才算停稳。生产按 drain 的 33ms/帧传 4（≈132ms）：
     *                   合并快速连发事件，同时让松手后的恢复足够及时。
     */
    ResizeSettle(int quietTicks) {
        this.quietTicks = Math.max(1, quietTicks);
    }

    /** 宽度变了（事件或轮询发现均可）。多次连报只欠一次重放。 */
    void changed() {
        dirty = true;
        quiet = 0;
    }

    /**
     * 每帧一喂（宽度未变的帧）。
     *
     * @return true 表示「这一帧该重放了」；返回过一次后归于安静，直到下一次 {@link #changed()}
     */
    boolean onTick() {
        if (!dirty) {
            return false;
        }
        if (++quiet >= quietTicks) {
            dirty = false;
            quiet = 0;
            return true;
        }
        return false;
    }
}
