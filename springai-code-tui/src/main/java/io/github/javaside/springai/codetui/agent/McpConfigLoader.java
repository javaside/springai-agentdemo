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
 * <p><b>降级契约</b>：文件缺失 / JSON 非法 / 单条缺必填字段 / 未知 {@code type}
 * → 视为空或跳过该条，记 WARN，<b>绝不抛异常</b>（照 {@code SkillCatalog} 风格）。仅 stdio 传输本期落地。
 * 「被 {@code enabled:false} 关闭 → 跳过」仅适用 {@link #load(Path)}；{@link #loadAll(Path)} 保留禁用项。
 */
public final class McpConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(McpConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private McpConfigLoader() {
    }

    /** 生产入口：由项目根解析出用户级 + 项目级两个文件路径后加载。 */
    public static List<McpServerConfig> load(Path root) {
        return load(userMcpFile(), projectMcpFile(root));
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

    /** 配置来源层：决定 /mcp 面板标注与回写目标文件。 */
    public enum ConfigSource { USER, PROJECT }

    /** 全量加载的单条目：配置 + 来源层 + 所属文件（回写目标）。 */
    public record LoadedServer(McpServerConfig config, ConfigSource source, Path file) { }

    /** 生产入口（全量版）：与 {@link #load(Path)} 同源两文件，但保留 enabled:false 条目，供 /mcp 列出再启用。 */
    public static List<LoadedServer> loadAll(Path root) {
        return loadAll(userMcpFile(), projectMcpFile(root));
    }

    private static Path userMcpFile() {
        return Path.of(System.getProperty("user.home")).resolve(".codetui").resolve("mcp.json");
    }

    private static Path projectMcpFile(Path root) {
        return root.resolve(".codetui").resolve("mcp.json");
    }

    /** 可测入口（全量版）：项目级覆盖用户级同名项（含来源层与回写目标一并换成项目级）。 */
    public static List<LoadedServer> loadAll(Path userFile, Path projectFile) {
        Map<String, LoadedServer> merged = new LinkedHashMap<>();
        for (McpServerConfig c : parseFileAll(userFile)) {
            merged.put(c.name(), new LoadedServer(c, ConfigSource.USER, userFile));
        }
        for (McpServerConfig c : parseFileAll(projectFile)) {
            merged.put(c.name(), new LoadedServer(c, ConfigSource.PROJECT, projectFile));
        }
        return List.copyOf(merged.values());
    }

    private static List<McpServerConfig> parseFile(Path file) {
        List<McpServerConfig> out = new ArrayList<>();
        for (McpServerConfig c : parseFileAll(file)) {
            if (c.enabled()) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<McpServerConfig> parseFileAll(Path file) {
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
            McpServerConfig cfg;
            try {
                cfg = parseEntry(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                // Jackson 3 asString()/asBoolean()/asLong() throw on wrong-shaped nodes;
                // a bad single entry must degrade to skip, never crash startup.
                log.warn("MCP server '{}' 配置字段类型非法，跳过：{}", entry.getKey(), e.getMessage());
                cfg = null;
            }
            if (cfg != null) {
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
