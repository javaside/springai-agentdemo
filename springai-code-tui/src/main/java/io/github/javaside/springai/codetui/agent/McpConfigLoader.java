package io.github.javaside.springai.codetui.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取两层 {@code mcp.json}（用户级 {@code ~/.codetui/mcp.json} + 项目级 {@code <root>/.codetui/mcp.json}），
 * 合并为 {@link McpServerConfig} 列表。项目级同名项覆盖用户级。
 *
 * <p><b>降级契约</b>：文件缺失 / JSON 非法 / 单条缺必填字段 / 未知 {@code type} / 被 {@code enabled:false} 关闭
 * → 视为空或跳过该条，记 WARN，<b>绝不抛异常</b>（照 {@code SkillCatalog} 风格）。仅 stdio 传输本期落地。
 */
public final class McpConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(McpConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private McpConfigLoader() {
    }

    /** 生产入口：由项目根解析出用户级 + 项目级两个文件路径后加载。 */
    public static List<McpServerConfig> load(Path root) {
        Path userFile = Path.of(System.getProperty("user.home")).resolve(".codetui").resolve("mcp.json");
        Path projectFile = root.resolve(".codetui").resolve("mcp.json");
        return load(userFile, projectFile);
    }

    /** 可测入口：显式两文件。项目级覆盖用户级同名项，保持插入顺序（用户项在前、项目新增在后）。 */
    public static List<McpServerConfig> load(Path userFile, Path projectFile) {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();
        for (McpServerConfig c : parseFile(userFile)) {
            merged.put(c.name(), c);
        }
        for (McpServerConfig c : parseFile(projectFile)) {
            merged.put(c.name(), c);
        }
        return new ArrayList<>(merged.values());
    }

    private static List<McpServerConfig> parseFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (Exception e) {
            log.warn("MCP 配置解析失败，忽略：{}（{}）", file, e.getMessage());
            return List.of();
        }
        JsonNode servers = root.get("mcpServers");
        if (servers == null || !servers.isObject()) {
            return List.of();
        }
        List<McpServerConfig> out = new ArrayList<>();
        servers.properties().forEach(entry -> {
            McpServerConfig cfg = parseEntry(entry.getKey(), entry.getValue());
            if (cfg != null && cfg.enabled()) {
                out.add(cfg);
            }
        });
        return out;
    }

    /** 解析单条；不合法则记 WARN 并返回 null（跳过）。 */
    private static McpServerConfig parseEntry(String name, JsonNode node) {
        String type = node.has("type") ? node.get("type").asString() : "stdio";
        if (!"stdio".equals(type)) {
            log.warn("MCP server '{}' 传输 type='{}' 暂未支持，跳过。", name, type);
            return null;
        }
        JsonNode commandNode = node.get("command");
        if (commandNode == null || commandNode.asString().isBlank()) {
            log.warn("MCP server '{}' 缺 command，跳过。", name);
            return null;
        }
        boolean enabled = !node.has("enabled") || node.get("enabled").asBoolean();
        Duration timeout = node.has("timeoutMs")
                ? Duration.ofMillis(node.get("timeoutMs").asLong()) : DEFAULT_TIMEOUT;

        List<String> args = new ArrayList<>();
        JsonNode argsNode = node.get("args");
        if (argsNode != null && argsNode.isArray()) {
            argsNode.forEach(a -> args.add(a.asString()));
        }
        Map<String, String> env = new LinkedHashMap<>();
        JsonNode envNode = node.get("env");
        if (envNode != null && envNode.isObject()) {
            envNode.properties().forEach(e -> env.put(e.getKey(), e.getValue().asString()));
        }
        return new McpServerConfig.StdioServerConfig(name, enabled, timeout,
                commandNode.asString(), List.copyOf(args), Map.copyOf(env));
    }
}
