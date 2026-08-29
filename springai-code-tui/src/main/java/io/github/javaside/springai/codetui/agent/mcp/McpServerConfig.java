package io.github.javaside.springai.codetui.agent.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 单个 MCP server 的不可变配置，按传输类型分型（sealed）。
 *
 * <p>已实现 {@link StdioServerConfig}（本地子进程）与 {@link HttpServerConfig}（远程 Streamable HTTP）；
 * SSE 仍未实现。公共访问器 {@link #name()} / {@link #enabled()} / {@link #timeoutMs()}
 * 供 {@link McpClientManager} 以传输无关的方式处理。
 */
public sealed interface McpServerConfig
        permits McpServerConfig.StdioServerConfig, McpServerConfig.HttpServerConfig {

    /** server 逻辑名（配置里的键；用于工具名前缀与日志）。 */
    String name();

    /** 是否启用；配置省略时由 loader 填 true。 */
    boolean enabled();

    /** request / initialization 超时；配置省略时由 loader 填默认 20s。 */
    Duration timeoutMs();

    /**
     * stdio 传输配置：以子进程方式启动本地 MCP server。
     *
     * @param name      server 逻辑名，见 {@link #name()}
     * @param enabled   是否启用，见 {@link #enabled()}
     * @param timeoutMs request / initialization 超时，见 {@link #timeoutMs()}
     * @param command 可执行命令（如 {@code npx}），必填
     * @param args    命令参数（可空 → 空列表）
     * @param env     追加环境变量（可空 → 空 map）
     */
    record StdioServerConfig(String name, boolean enabled, Duration timeoutMs,
                             String command, List<String> args, Map<String, String> env)
            implements McpServerConfig {
    }

    /**
     * Streamable HTTP 传输配置：连接远程 MCP server。
     *
     * @param name      server 逻辑名，见 {@link #name()}
     * @param enabled   是否启用，见 {@link #enabled()}
     * @param timeoutMs request / initialization 超时，见 {@link #timeoutMs()}
     * @param url     完整端点 URL（如 {@code https://mcp.context7.com/mcp}）；
     *                由 {@link McpTransportFactory} 拆成 baseUri + endpoint 两段喂给 SDK
     * @param headers 请求头，值已在 loader 完成 {@code ${ENV_VAR}} 插值；可空 → 空 map
     */
    record HttpServerConfig(String name, boolean enabled, Duration timeoutMs,
                            String url, Map<String, String> headers)
            implements McpServerConfig {
    }
}
