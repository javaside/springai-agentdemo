package io.github.javaside.springai.codetui.agent.mcp;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * 传输接缝：把 {@link McpServerConfig} 变体映射为 SDK 的 {@link McpClientTransport}。
 *
 * <p>这是加新传输的<b>唯一分型点</b>（设计文档 §5 扩展点）。已落地 stdio 与 Streamable HTTP；
 * SSE 仍未实现——旧标准、官方已 deprecated，要加只需在此再补一个分支，用 mcp-core 现成的
 * {@code HttpClientSseClientTransport}（基于 JDK HttpClient，无新依赖）。
 * 加传输时 {@link McpClientManager} 与其余流程<b>零改动</b>——Streamable HTTP 这次即是明证。
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
            if (config instanceof McpServerConfig.HttpServerConfig http) {
                URI uri = URI.create(http.url());
                HttpClientStreamableHttpTransport.Builder builder =
                        HttpClientStreamableHttpTransport.builder(baseUriOf(uri))
                                .connectTimeout(http.timeoutMs())
                                .jsonMapper(McpJsonDefaults.getMapper());
                String endpoint = endpointOf(uri);
                if (endpoint != null) {
                    builder.endpoint(endpoint);
                }
                if (!http.headers().isEmpty()) {
                    builder.httpRequestCustomizer(headerCustomizer(http.headers()));
                }
                return Optional.of(builder.build());
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

    /**
     * 把配置里的 headers 逐条加到每个出站请求上。
     *
     * <p>抽成独立方法是为了<b>能直接单测</b>：真机冒烟证明不了它——实测 Context7 在 initialize
     * 阶段不校验 API key，headers 全部丢失它照样返回 200 与完整 serverInfo。
     */
    static McpSyncHttpClientRequestCustomizer headerCustomizer(Map<String, String> headers) {
        return (requestBuilder, method, uri, body, context) ->
                headers.forEach(requestBuilder::header);
    }
}
