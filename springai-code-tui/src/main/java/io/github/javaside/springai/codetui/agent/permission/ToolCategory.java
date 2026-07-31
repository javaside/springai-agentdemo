package io.github.javaside.springai.codetui.agent.permission;

/** 工具类别：决定模式默认行为，以及规则匹配时把目标当路径还是当整串。 */
public enum ToolCategory {
    /** 只读（Read/Grep/Glob/BashOutput/KillShell）：默认放行，仅命中危险路径读时 ASK。 */
    READ_ONLY,
    /** 文件写（Write/Edit）：ACCEPT_EDITS 下工作区内放行，否则 ASK。 */
    FILE_WRITE,
    /** 命令（Bash）：走 {@link BashCommandSplitter} 分段判定。 */
    COMMAND,
    /** 只读网络（WebFetch / 各搜索工具）：放行。 */
    NETWORK_READ,
    /** 内部工具（TodoWrite/Skill/Ask/Task/ParallelTasks/Memory*）：无外部副作用，恒放行。 */
    INTERNAL,
    /** 未登记（含全部 MCP 工具）：保守默认 ASK。 */
    UNKNOWN
}
