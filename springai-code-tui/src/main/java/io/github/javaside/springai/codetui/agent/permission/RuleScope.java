package io.github.javaside.springai.codetui.agent.permission;

/** 一条规则的存放层：决定「允许，且别再问」写到哪。 */
public enum RuleScope {
    /** 仅本次会话（进程内存，{@code /clear} 开新会话时清空）。 */
    SESSION,
    /** 项目层 {@code <root>/.codetui/permissions.json}。 */
    PROJECT,
    /** 用户层 {@code ~/.codetui/permissions.json}。 */
    USER
}
