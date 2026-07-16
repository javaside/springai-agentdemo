package io.github.javaside.springai.codetui.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * mcp.json 回写：只改 {@code mcpServers.<name>.enabled} 一个字段，其余树原样保留
 * （Jackson 树模型读改写，条目插入顺序不变）。
 *
 * <p>原子写：先写同目录临时文件再 move（优先 ATOMIC_MOVE，不支持的文件系统降级普通替换），
 * 防写一半损坏配置（照 {@code FileSessionRepository} 落盘风格）。
 *
 * <p><b>降级契约</b>：文件缺失 / JSON 非法 / 条目不存在 / 写失败 → 记 WARN、返回 false，<b>绝不抛异常</b>。
 * 调用方（McpRegistry）据 false 提示「仅本次运行生效」。
 */
final class McpConfigWriter {

    private static final Logger log = LoggerFactory.getLogger(McpConfigWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpConfigWriter() {
    }

    static boolean setEnabled(Path file, String serverName, boolean enabled) {
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            JsonNode servers = root.get("mcpServers");
            if (servers == null || !servers.isObject()) {
                log.warn("MCP 回写失败：{} 无 mcpServers 对象。", file);
                return false;
            }
            JsonNode entry = servers.get(serverName);
            if (entry == null || !entry.isObject()) {
                log.warn("MCP 回写失败：{} 无条目 '{}'。", file, serverName);
                return false;
            }
            ((ObjectNode) entry).put("enabled", enabled);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            log.warn("MCP 回写失败：{} '{}' enabled={}：{}", file, serverName, enabled, e.getMessage());
            return false;
        }
    }
}
