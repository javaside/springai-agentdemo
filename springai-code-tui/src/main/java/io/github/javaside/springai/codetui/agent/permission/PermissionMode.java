package io.github.javaside.springai.codetui.agent.permission;

/**
 * 权限模式。{@code PLAN} 随期 2 落地（加一个常量 + {@code PermissionEngine} 一条 mode 默认分支）。
 *
 * <p><b>切换</b>：UI 的 {@code Shift+Tab}（期 0 已 pty 实测可与裸 Tab 区分）。
 */
public enum PermissionMode {
    /** 只读放行，其余按规则；无规则则 ASK。 */
    DEFAULT,
    /** 额外放行工作目录内的 Write/Edit，以及 mkdir/touch/mv/cp 类文件系统命令。 */
    ACCEPT_EDITS,
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
        if (!bypassAllowed) {
            return this == DEFAULT ? ACCEPT_EDITS : DEFAULT;
        }
        return switch (this) {
            case DEFAULT -> ACCEPT_EDITS;
            case ACCEPT_EDITS -> BYPASS;
            case BYPASS -> DEFAULT;
        };
    }

    /** 状态栏/面板显示用的短标签。 */
    public String label() {
        return switch (this) {
            case DEFAULT -> "默认";
            case ACCEPT_EDITS -> "自动接受编辑";
            case BYPASS -> "跳过权限检查";
        };
    }
}
