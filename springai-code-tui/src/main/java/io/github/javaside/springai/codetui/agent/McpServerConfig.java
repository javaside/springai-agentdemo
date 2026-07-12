package io.github.javaside.springai.codetui.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 单个 MCP server 的不可变配置，按传输类型分型（sealed）。
 *
 * <p>本期只实现 {@link StdioServerConfig}；SSE / Streamable HTTP 是预留的扩展变体
 * （见设计文档 §5 扩展点）。公共访问器 {@link #name()} / {@link #enabled()} / {@link #timeoutMs()}
 * 供 {@link McpClientManager} 以传输无关的方式处理。
 */
public sealed interface McpServerConfig permits McpServerConfig.StdioServerConfig {

    /** server 逻辑名（配置里的键；用于工具名前缀与日志）。 */
    String name();

    /** 是否启用；配置省略时由 loader 填 true。 */
    boolean enabled();

    /** request / initialization 超时；配置省略时由 loader 填默认 20s。 */
    Duration timeoutMs();

    /**
     * stdio 传输配置：以子进程方式启动本地 MCP server。
     *
     * @param command 可执行命令（如 {@code npx}），必填
     * @param args    命令参数（可空 → 空列表）
     * @param env     追加环境变量（可空 → 空 map）
     */
    record StdioServerConfig(String name, boolean enabled, Duration timeoutMs,
                             String command, List<String> args, Map<String, String> env)
            implements McpServerConfig {
    }
}
