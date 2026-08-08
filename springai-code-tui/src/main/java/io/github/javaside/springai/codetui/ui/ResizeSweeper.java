package io.github.javaside.springai.codetui.ui;

import dev.tamboui.terminal.Backend;
import dev.tamboui.toolkit.app.InlineToolkitRunner;

import java.lang.reflect.Field;

/**
 * 终端改宽度后、库按新宽度重画<b>之前</b>，把重排产生的旧帧残迹清掉，让新帧原地盖回去。
 *
 * <h2>要修的是什么</h2>
 * <p>{@code InlineDisplay} 用两个「相对光标记账」维护屏幕底部那块显示区：{@code lastCursorY}
 * （光标停在显示区第几行）与 {@code currentHeight}（显示区占几行）。终端改宽度时会把屏上内容
 * 重新折行：显示区那几行（整宽的框线）被拆成更多物理行，而库收到 {@code ResizeEvent} 只是照着
 * 旧记账再画一帧——新帧只盖住从光标算起的 {@code currentHeight} 行，拆出来的多余行没人擦。
 * 用户实机截图里就是新旧两个框同时在，越拖越多。
 *
 * <h2>修法：赶在重画前，从显示区顶行 {@code ESC[J} 清到屏尾</h2>
 * <p>挂在 {@link dev.tamboui.toolkit.event.EventRouter#addGlobalHandler}（公开 API）上：
 * {@code InlineTuiRunner} 处理 {@code ResizeEvent} 的顺序是<b>先派发事件、后重画</b>，
 * 所以 sweep 与重画发生在同一个事件周期里——擦与画之间不会漏出半帧脏屏。序列：
 * <ol>
 *   <li>{@code CR} + 上移 {@code lastCursorY} 行 → 回到显示区第 0 行的行首；</li>
 *   <li>{@code ESC[J}（清到屏尾）→ 旧帧连同它重排拆出的所有残行一起消失。显示区永远是屏幕上
 *       <b>最靠下</b>的内容，它下面不存在真实历史，往下清永远安全；</li>
 *   <li>下移 {@code lastCursorY} 行还原光标 → 记账与光标依旧一致，<b>一个字段都不用改</b>。
 *       紧接着库的重画自己做 {@code CR + 上移 lastCursorY}，正好从清扫起点开始画。</li>
 * </ol>
 * <p>整个序列拼成一次 {@code writeRaw}：要么全写出去、要么一个字节都没写，不存在
 * 「光标移了一半失败、记账与位置脱节」的中间态。
 *
 * <h2>为什么不钉屏底（上一版翻车的教训）</h2>
 * <p>上一版把显示区绝对定位到 {@code rows - height}（并为此在启动时硬滚一屏建立「贴底」不变量）。
 * 两个都翻了车：启动画面半屏空白；而钉锚靠轮询宽度变化触发，宽度变了意味着库<b>已经</b>在
 * stale 位置画过一帧，钉到屏底后屏幕上就是两个完整的框。教训：<b>修 resize 的挂钩点必须在
 * 库的重画之前，事后怎么钉都晚了</b>；而拿到了正确的挂钩点，原地清扫就够，根本不需要绝对坐标、
 * 不需要贴底不变量、也不需要启动滚屏。
 *
 * <h2>与光标停放约定成对（改一处要想到另一处）</h2>
 * <p>sweep 的起点是「光标行 − lastCursorY」，这在重排后仍然成立的前提是：<b>光标物理落点
 * 没有被重排挪动</b>。终端变窄时光标跟着自己那行字符走，它上方每一行被拆几行、落点就低几行
 * ——起点偏低一行，旧帧顶部就留一行，重画的帧还会往下爬，拖一次窗口累积一片（tmux 实测
 * 连拖 10 档留 20+ 行）。所以 {@code CodeTuiView.InputBox} 在 <b>resize 窗口内</b>
 * （{@code parkCursorAtTop}，首个宽度变化 → 停稳重放完成）把硬件光标钉在<b>显示区第 0 行</b>
 * （上方没有行可拆，位移恒 0）；平时停回文本行——硬件光标是 IME 预编辑锚点，永久钉 0 行
 * 拼音会浮到框顶边框上（用户实报）。代价：每轮拖拽<b>第一步</b>光标还在文本行、清扫起点
 * 错位留 ≤1-2 行残迹，由停稳重放收尾。
 *
 * <h2>已知残留（有界、不累积）</h2>
 * <p>光标所在列超过新终端宽度时（输入极长 + 拖得极窄），光标自己那行也会拆行、落点低
 * ⌊列/新宽⌋ 行，旧帧顶上留同样多行；菜单态没有输入框，库默认把光标停在第 0 行行尾（整宽），
 * 拆行时同理。都只留一次、不随拖拽累积、随对话上滚消失。要擦掉就得精确复算终端折行规则
 * （含 CJK 宽字符在断点上的行为），算错一行就是擦掉真实历史，不值得。
 *
 * <p><b>多路复用器合并 resize 的盲区</b>(tmux 抓包实锤)：tmux 对连发的 resize 会自己合并，
 * 屏幕先重排、TIOCSWINSZ 迟到上百毫秒且中间档位整个丢掉——这段时间应用读到的宽度就是旧的，
 * 按旧宽度画的帧在已变窄的终端里折行，光标每帧净下沉，鬼影留在上方、事后清扫够不着。
 * 拖拽过程中应用侧无解（信息根本没送到）；{@code drain} 的每帧宽度比对把盲窗压到一个 tick。
 * <b>兜底靠二级修复</b>：宽度停稳后（{@link ResizeSettle}）{@code CodeTuiView.replayAfterResize}
 * 把整屏连回滚缓冲一起抹掉、从应用自己的输出留底按新宽度全量重放——不依赖终端重排行为，
 * 盲窗期间的鬼影、被顶走的信息流、以及 reflow 推进 scrollback 的旧帧残骸（本清扫永远够不着，
 * Terminal.app 实测每拖一次存档一份）一次性全部归位。
 *
 * <h2>约定（与 {@link ScreenCleaner} 同源，改一处要想到另一处）</h2>
 * <ul>
 *   <li>反射<b>只读</b>（backend 引用 + 两个计数），绝不写库的记账；</li>
 *   <li>反射先<b>全部解析完</b>再执行副作用；</li>
 *   <li><b>任何失败都返回 false 且绝不抛</b>——这条路挂在事件派发里，抛一次整个 TUI 就崩；</li>
 *   <li>必须在渲染线程调用（全局 handler 本就在渲染线程被调）。</li>
 * </ul>
 *
 * <p>库升级导致字段改名时，{@code ResizeSweeperTest} 的结构断言会先红灯；线上则安全降级成
 * 「维持今天的行为」——resize 残影回来，但不会引入新的坏状态。
 */
final class ResizeSweeper {

    /** 显示区记账的最小接口（只读）。抽出来是为了让核心逻辑可测。 */
    interface Accounting {
        /** 显示区当前占几行（{@code InlineDisplay.currentHeight}）。 */
        int contentHeight();

        /** 光标停在显示区第几行（{@code InlineDisplay.lastCursorY}，0 基）。 */
        int cursorRow();
    }

    private ResizeSweeper() {
    }

    /** 生产入口：从 runner 反射出 backend 与 {@code InlineDisplay} 的两个私有计数，然后清扫。 */
    static boolean sweep(InlineToolkitRunner runner) {
        if (runner == null) {
            return false;          // 测试态（未 start）走这条，非错误
        }
        Backend backend;
        Object display;
        Field lastCursorY;
        Field currentHeight;
        try {
            Object tui = runner.tuiRunner();                     // dev.tamboui.tui.InlineTuiRunner
            backend = (Backend) get(tui, "backend");
            Object viewport = get(tui, "viewport");              // dev.tamboui.tui.InlineViewport
            display = get(viewport, "display");                  // dev.tamboui.inline.InlineDisplay
            lastCursorY = declaredField(display, "lastCursorY");
            currentHeight = declaredField(display, "currentHeight");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;          // 解析阶段失败：一个字节都还没写出去，降级得干干净净
        }
        Object target = display;
        return sweep(backend, new Accounting() {
            @Override public int contentHeight() {
                try {
                    return currentHeight.getInt(target);
                } catch (IllegalAccessException e) {
                    return 0;      // 读不到高度 → 当作「没有显示区」，核心逻辑会据此返回 false
                }
            }

            @Override public int cursorRow() {
                try {
                    return lastCursorY.getInt(target);
                } catch (IllegalAccessException e) {
                    return -1;     // 读不到 → 交给核心逻辑的守卫判掉
                }
            }
        });
    }

    /**
     * 核心逻辑（可测）：回到显示区顶行、清到屏尾、还原光标——一次原子写出。
     *
     * @param backend    终端
     * @param accounting 显示区记账（只读）
     * @return 是否真的清扫了
     */
    static boolean sweep(Backend backend, Accounting accounting) {
        if (backend == null || accounting == null) {
            return false;
        }
        try {
            int height = accounting.contentHeight();
            if (height <= 0) {
                return false;      // 显示区还没分配，没有帧可清（首帧之前）
            }
            int row = accounting.cursorRow();
            if (row < 0 || row >= height) {
                return false;      // 记账不自洽就别动屏幕：清错起点会吃掉真实历史
            }
            StringBuilder seq = new StringBuilder("\r");
            if (row > 0) {
                seq.append("\u001b[").append(row).append("A");
            }
            seq.append("\u001b[J");
            if (row > 0) {
                seq.append("\u001b[").append(row).append("B");
            }
            backend.writeRaw(seq.toString());
            // 显式 flush：正常路径下紧随其后的重画会带 flush，但事件顺序是库的实现细节；
            // 不 flush 的清扫在乱序时就是「看不见的清扫」。多一次 flush 只是帧内多一步，无感。
            backend.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Object get(Object target, String field) throws ReflectiveOperationException {
        return declaredField(target, field).get(target);
    }

    private static Field declaredField(Object target, String field) throws NoSuchFieldException {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f;
    }
}
