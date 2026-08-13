package io.github.javaside.springai.codetui.agent.thinking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ThinkingConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ThinkingConfigStore.class);
    private static final int VERSION = 1;
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Path file;
    private final Map<String, Map<String, ThinkingConfig>> configs;

    private ThinkingConfigStore(Path file, Map<String, Map<String, ThinkingConfig>> configs) {
        this.file = file;
        this.configs = configs;
    }

    public static ThinkingConfigStore inMemory() {
        return new ThinkingConfigStore(null, new LinkedHashMap<>());
    }

    public static ThinkingConfigStore load(Path root) {
        if (root == null) {
            return inMemory();
        }
        Path file = fileFor(root);
        if (!Files.isRegularFile(file)) {
            return new ThinkingConfigStore(file, new LinkedHashMap<>());
        }
        try {
            JsonNode rootNode = MAPPER.readTree(Files.readString(file));
            return new ThinkingConfigStore(file, parse(rootNode));
        } catch (Exception e) {
            log.warn("思考配置 {} 不是合法配置（{}），本次按无配置处理。", file, e.getMessage());
            return new ThinkingConfigStore(file, new LinkedHashMap<>());
        }
    }

    public static Path fileFor(Path root) {
        return root.resolve(".codetui").resolve("thinking.json");
    }

    public synchronized ThinkingConfig get(String providerId, String modelId) {
        Map<String, ThinkingConfig> models = configs.get(providerId);
        return models == null ? ThinkingConfig.defaults()
                : models.getOrDefault(modelId, ThinkingConfig.defaults());
    }

    public synchronized void put(String providerId, String modelId, ThinkingConfig config) {
        requireKey(providerId, "providerId");
        requireKey(modelId, "modelId");
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        if (config.mode() == ThinkingMode.DEFAULT) {
            Map<String, ThinkingConfig> models = configs.get(providerId);
            if (models != null) {
                models.remove(modelId);
                if (models.isEmpty()) {
                    configs.remove(providerId);
                }
            }
            return;
        }
        configs.computeIfAbsent(providerId, ignored -> new LinkedHashMap<>()).put(modelId, config);
    }

    public synchronized Map<String, Map<String, ThinkingConfig>> snapshot() {
        Map<String, Map<String, ThinkingConfig>> copy = new LinkedHashMap<>();
        configs.forEach((provider, models) -> copy.put(provider, Map.copyOf(models)));
        return Map.copyOf(copy);
    }

    public synchronized boolean save() {
        if (file == null) {
            return true;
        }
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());
            ObjectNode document = MAPPER.createObjectNode();
            document.put("version", VERSION);
            ObjectNode providers = document.putObject("providers");
            for (Map.Entry<String, Map<String, ThinkingConfig>> provider : configs.entrySet()) {
                ObjectNode models = providers.putObject(provider.getKey());
                for (Map.Entry<String, ThinkingConfig> model : provider.getValue().entrySet()) {
                    writeConfig(models.putObject(model.getKey()), model.getValue());
                }
            }
            tmp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            cleanup(tmp);
            log.warn("思考配置没能落盘 {}（{}），仅本次运行生效。", file, e.getMessage());
            return false;
        }
    }

    private static Map<String, Map<String, ThinkingConfig>> parse(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("根节点必须是对象");
        }
        JsonNode version = document.get("version");
        if (version == null || !version.isInt() || version.intValue() != VERSION) {
            throw new IllegalArgumentException("不支持的 version");
        }
        JsonNode providers = document.get("providers");
        if (providers == null || !providers.isObject()) {
            throw new IllegalArgumentException("providers 必须是对象");
        }
        Map<String, Map<String, ThinkingConfig>> result = new LinkedHashMap<>();
        providers.properties().forEach(provider -> {
            if (!provider.getValue().isObject()) {
                throw new IllegalArgumentException("provider 节点必须是对象: " + provider.getKey());
            }
            Map<String, ThinkingConfig> models = new LinkedHashMap<>();
            provider.getValue().properties().forEach(model -> models.put(model.getKey(), parseConfig(model.getValue())));
            if (!models.isEmpty()) {
                result.put(provider.getKey(), models);
            }
        });
        return result;
    }

    private static ThinkingConfig parseConfig(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("模型配置必须是对象");
        }
        JsonNode modeNode = node.get("mode");
        if (modeNode == null || !modeNode.isString()) {
            throw new IllegalArgumentException("mode 必须是字符串");
        }
        ThinkingMode mode = ThinkingMode.valueOf(modeNode.stringValue());
        JsonNode effortNode = node.get("effort");
        String effort = effortNode != null && effortNode.isString() ? effortNode.stringValue() : null;
        JsonNode budgetNode = node.get("thinkingBudget");
        Integer budget = budgetNode != null && budgetNode.isInt() ? budgetNode.intValue() : null;
        return new ThinkingConfig(mode, effort, budget);
    }

    private static void writeConfig(ObjectNode node, ThinkingConfig config) {
        node.put("mode", config.mode().name());
        if (config.effort() != null) {
            node.put("effort", config.effort());
        }
        if (config.thinkingBudget() != null) {
            node.put("thinkingBudget", config.thinkingBudget());
        }
    }

    private static void requireKey(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static void cleanup(Path tmp) {
        if (tmp == null) {
            return;
        }
        try {
            Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
        }
    }
}
