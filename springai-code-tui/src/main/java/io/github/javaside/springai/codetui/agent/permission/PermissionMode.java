package io.github.javaside.springai.codetui.agent.permission;

/**
 * 权限模式。
 *
 * <p><b>切换</b>：UI 的 {@code Shift+Tab}（期 0 已 pty 实测可与裸 Tab 区分）。
 *
 * <p><b>四档平权</b>：BYPASS 也在 {@code Shift+Tab} 环上，不需要任何启动参数。
 * 配置文件与 {@code --permission-mode} <b>仍然</b>拒绝 BYPASS，见 {@code PermissionConfigLoader}。
 */
public enum PermissionMode {
    /** 只读放行，其余按规则；无规则则 ASK。 */
    DEFAULT,
    /** 额外放行工作目录内的 Write/Edit，以及 mkdir/touch/mv/cp 类文件系统命令。 */
    ACCEPT_EDITS,
    /**
     * 计划模式：只读放行，写文件 / 非只读命令 / 未登记工具一律 <b>DENY</b>——
     * <b>不是 ASK</b>。计划模式的意义就在于「这段时间里根本不可能动手」，
     * 弹个面板让人能当场批准就破功了。模型应转而调用 {@code ExitPlanMode} 提交计划。
     */
    PLAN,
    /**
     * 全放行——<b>只有 deny 规则还拦得住</b>。
     *
     * <p><b>内置危险检查在本档被跳过</b>（{@code doDecide} 的 BYPASS 分支排在内置检查之前
     * 直接返回），只由 {@code PermissionCallback.onGuardrailBypassed} 事后在对话区留一行
     * {@code ⚠ BYPASS 放行：…}。{@code ask} 规则同样跳过——它的语义是「每次都问我」，
     * 与本档的「不问」直接矛盾。
     *
     * <p><b>本档不产生任何 ASK</b>，这正是它在半无人值守下能用的原因。
     */
    BYPASS;

    /**
     * 循环到下一个模式（UI 的 {@code Shift+Tab}）。<b>四档平权</b>，BYPASS 排在 PLAN 之后。
     *
     * <p><b>为什么不带门禁参数</b>：这里曾有一个 {@code bypassAllowed} 参数，
     * 由 {@code --dangerously-skip-permissions} 决定 BYPASS 在不在环上。那把两件事
     * 合并成了一个布尔：「启动时能否<b>起步于</b> BYPASS」和「运行期用户能否<b>切进</b> BYPASS」。
     * 前者该受启动参数管（clone 来的配置、命令行别名都可能让用户全程无感），
     * 后者不该——用户当面按键、在场、有意图，且切完状态栏行首常驻红色 {@code ⚠ 跳过权限检查}。
     * 合并的结果是想临时用一下 BYPASS 就得退出进程、改命令行、重启。
     *
     * <p>顺序与此前带 {@code --dangerously-skip-permissions} 启动时完全一致，没有新的排列。
     */
    public PermissionMode next() {
        return switch (this) {
            case DEFAULT -> ACCEPT_EDITS;
            case ACCEPT_EDITS -> PLAN;
            case PLAN -> BYPASS;
            case BYPASS -> DEFAULT;
        };
    }

    /** 状态栏/面板显示用的短标签。 */
    public String label() {
        return switch (this) {
            case DEFAULT -> "默认";
            case ACCEPT_EDITS -> "自动接受编辑";
            case PLAN -> "计划模式";
            case BYPASS -> "跳过权限检查";
        };
    }
}
