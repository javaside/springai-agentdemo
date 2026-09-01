package io.github.javaside.springai.codetui.ui;

/**
 * 「需要你看一眼」的边沿检测状态机：只在<b>状态跳变</b>的那一拍要求发提示（BEL + 改 tab 标题），
 * 平态不响；回到平态时要求恢复默认标题。
 *
 * <p><b>为什么要状态机而不是事件处直呼</b>：UI 批与按键/Agent 事件之间没有同步关系
 * （一个回合里可能连着跑好几批），直呼会在相邻批里重复响铃，
 * 或让多个事件源互相覆盖标题。把「上一批是什么状态」记下来，跳变才动作，是唯一的去重办法；
 * 状态机是纯函数（不碰 IO），可直测。
 *
 * <p>四态：
 * <ul>
 *   <li>{@link Phase#IDLE}：平态，默认标题；</li>
 *   <li>{@link Phase#BUSY}：回合跑着（含压缩），不提示；</li>
 *   <li>{@link Phase#WAITING_USER}：有模态（问询 / 权限审批 / 计划审批）在等用户——进入时提示；</li>
 *   <li>{@link Phase#DONE}：回合完成（忙→闲下降沿）——进入时提示，<b>保持</b>到用户下一次按键
 *       （标题持续显示「已完成」，用户切回来一眼可见；按键即恢复默认标题）。</li>
 * </ul>
 *
 * <p><b>抑制规则</b>：用户主动按 Esc 取消回合的那次「忙→闲」不算完成——他刚按过键，必然在场，
 * 再响铃是打扰。
 */
final class AttentionTracker {

    enum Phase { IDLE, BUSY, WAITING_USER, DONE }

    /** 一个 UI 批要执行的动作；NONE=什么都不做。 */
    enum Action { NONE, ALERT_WAITING, ALERT_DONE, RESTORE }

    /** 平态（默认）标题；恢复时用它。 */
    static final String DEFAULT_TITLE = "Spring AI Code TUI";

    private Phase phase = Phase.IDLE;

    /** 用户按过键（人在场）。置标志不直接改相位——标题写回必须留在渲染线程（UI 批），见 userActed。 */
    private boolean userActed;

    Phase phase() {
        return phase;
    }

    /**
     * 推进一拍，返回本拍动作。
     *
     * @param modalWaiting  是否有模态请求在等用户（Ask / 审批 / 计划）
     * @param busy          agent 是否忙（回合中 / 压缩中 / 有在飞子 agent）
     * @param userCancelled 上一拍到本拍之间用户是否主动按 Esc 取消过回合（抑制完成提示）
     */
    Action advance(boolean modalWaiting, boolean busy, boolean userCancelled) {
        Phase prev = phase;
        boolean acted = userActed;
        userActed = false;
        if (modalWaiting) {
            phase = Phase.WAITING_USER;
            return prev == Phase.WAITING_USER ? Action.NONE : Action.ALERT_WAITING;
        }
        if (busy) {
            phase = Phase.BUSY;
            // WAITING→BUSY：模态刚被答完、活继续跑。不响铃（答完就响「完成了」是撒谎），
            // 但要恢复标题——「等待输入」挂在跑动中的 tab 上是陈旧信息。
            // DONE→BUSY：用户按键后发了新消息，同样只恢复标题。
            return prev == Phase.WAITING_USER || prev == Phase.DONE ? Action.RESTORE : Action.NONE;
        }
        if (prev == Phase.DONE) {
            // DONE 是吸收态：标题持续显示「已完成」直到用户按键（acted）或起了新活（上面 busy 分支）。
            if (acted) {
                phase = Phase.IDLE;
                return Action.RESTORE;
            }
            return Action.NONE;
        }
        // 忙→闲下降沿 = 完成（用户取消的除外，见类注释）
        boolean completed = (prev == Phase.BUSY || prev == Phase.WAITING_USER) && !userCancelled;
        phase = completed ? Phase.DONE : Phase.IDLE;
        if (completed) {
            return Action.ALERT_DONE;
        }
        // 回到平态（含 Esc 取消）：若之前挂在提示态，恢复标题
        return prev == Phase.IDLE ? Action.NONE : Action.RESTORE;
    }

    /**
     * 用户按键（任意键）：置「人在场」标志。DONE 态据此在<b>下一个 UI 批</b> 恢复默认标题——
     * 标题写回必须留在渲染线程（两帧之间），不能在按键处理器里直接写裸转义序列。
     */
    void userActed() {
        userActed = true;
    }

    /** 是否处于「挂着提示标题」的态（DONE；恢复动作据此决定）。 */
    boolean showingAttention() {
        return phase == Phase.DONE;
    }
}
