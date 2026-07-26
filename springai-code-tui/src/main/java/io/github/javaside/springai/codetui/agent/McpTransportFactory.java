package io.github.javaside.springai.codetui.agent;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Optional;

/**
 * 传输接缝：把 {@link McpServerConfig} 变体映射为 SDK 的 {@link McpClientTransport}。
 *
 * <p>这是加新传输的<b>唯一分型点</b>（设计文档 §5 扩展点）：将来接入 SSE / Streamable HTTP，
 * 只需在此加一个分支，用 mcp-core 现成的 {@code HttpClientSseClientTransport} /
 * {@code HttpClientStreamableHttpTransport}（基于 JDK HttpClient，无新依赖）。
 * {@link McpClientManager} 与其余流程<b>零改动</b>。
 *
 * <p>构造失败或传输未实现 → 记 WARN、返回 {@link Optional#empty()}（降级，不抛）。
 */
public final class McpTransportFactory {

    private static final Logger log = LoggerFactory.getLogger(McpTransportFactory.class);

    private McpTransportFactory() {
    }

    public static Optional<McpClientTransport> create(McpServerConfig config) {
        try {
            if (config instanceof McpServerConfig.StdioServerConfig stdio) {
                ServerParameters params = ServerParameters.builder(stdio.command())
                        .args(stdio.args())
                        .env(stdio.env())
                        .build();
                return Optional.of(new StdioClientTransport(params, McpJsonDefaults.getMapper()));
            }
            log.warn("MCP server '{}' 传输类型未实现，跳过。", config.name());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("MCP server '{}' 构造传输失败，跳过：{}", config.name(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 拆出 baseUri：{@code scheme://authority}（authority 含端口）。SDK 的 {@code builder(baseUri)}
     * 只认这一段，路径要另外经 {@code endpoint(..)} 给。
     */
    static String baseUriOf(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /**
     * 拆出 endpoint：path（含 query）。为空或 {@code "/"} 时返回 {@code null}，表示用 SDK 默认的 {@code /mcp}。
     *
     * <p><b>不拆会错</b>：把整个 {@code https://h/mcp} 当 baseUri 传进去，SDK 会再拼一个默认 {@code /mcp}，
     * 实际请求打到 {@code /mcp/mcp}。
     */
    static String endpointOf(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return null;
        }
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }
}
