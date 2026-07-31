package io.github.javaside.springai.codetui.agent.permission;

import java.util.List;

/**
 * 两层 {@code permissions.json} 合并后的结果。
 *
 * @param defaultMode 启动模式（项目层可覆盖用户层；{@link PermissionMode#BYPASS} <b>不可</b>由配置声明，
 *                    见 {@link PermissionConfigLoader}）
 * @param rules       全部规则（<b>合并而非覆盖</b>：deny 只增不减，各自带来源层 scope）
 */
public record PermissionConfig(PermissionMode defaultMode, List<PermissionRule> rules) {

    public static PermissionConfig empty() {
        return new PermissionConfig(PermissionMode.DEFAULT, List.of());
    }
}
