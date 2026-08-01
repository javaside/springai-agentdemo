package io.github.javaside.springai.codetui.agent.permission;

/**
 * 权限模式。
 *
 * <p><b>切换</b>：UI 的 {@code Shift+Tab}（期 0 已 pty 实测可与裸 Tab 区分）。
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
     * 全放行——但 deny 规则与内置危险检查<b>仍然生效</b>（它们排在 mode 默认之前）。
     * 仅 {@code --dangerously-skip-permissions} 启动可进。
     */
    BYPASS;

    /**
     * 循环到下一个模式。
     *
     * @param bypassAllowed 启动时是否开了 {@code --dangerously-skip-permissions}；
     *                      false 时 BYPASS 不进循环（从 BYPASS 出发只能走到 DEFAULT，回不去）
     */
    public PermissionMode next(boolean bypassAllowed) {
        return switch (this) {
            case DEFAULT -> ACCEPT_EDITS;
            case ACCEPT_EDITS -> PLAN;
            case PLAN -> bypassAllowed ? BYPASS : DEFAULT;
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
