package io.github.javaside.springai.codetui.agent.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 两层 hooks.json 加载（契约照 McpConfigLoader：文件缺失/JSON 非法/坏条目 → WARN 跳过，绝不抛）。
 * 合并语义是 concat（用户层在前、全部执行）——钩子无名字可覆盖，同事件全跑。
 */
public final class HookConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(HookConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int DEFAULT_TIMEOUT = 60;
    static final int MAX_TIMEOUT = 120;

    record HookDto(String matcher, String command, Integer timeout) {}
    record HooksFileDto(Map<String, List<HookDto>> hooks) {}

    private HookConfigLoader() {}

    /** 生产入口：~/.codetui/hooks.json + <root>/.codetui/hooks.json。 */
    public static List<HookDefinition> load(Path root) {
        return load(Path.of(System.getProperty("user.home")).resolve(".codetui").resolve("hooks.json"),
                root.resolve(".codetui").resolve("hooks.json"));
    }

    /** 可测入口（测试不碰 user.home）。 */
    public static List<HookDefinition> load(Path userFile, Path projectFile) {
        List<HookDefinition> out = new ArrayList<>();
        out.addAll(parse(userFile, HookSource.USER));
        out.addAll(parse(projectFile, HookSource.PROJECT));
        return out;
    }

    private static List<HookDefinition> parse(Path file, HookSource source) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        HooksFileDto dto;
        try {
            dto = MAPPER.readValue(Files.readString(file), HooksFileDto.class);
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("跳过无法解析的 hooks 配置 {}：{}", file, e.toString());
            return List.of();
        }
        if (dto == null || dto.hooks() == null) {
            return List.of();
        }
        List<HookDefinition> out = new ArrayList<>();
        for (var entry : dto.hooks().entrySet()) {
            HookEvent event = HookEvent.fromConfigKey(entry.getKey());
            if (event == null) {
                log.warn("跳过未知事件键 {}（支持：PreToolUse/PostToolUse/Stop/SessionStart）", entry.getKey());
                continue;
            }
            if (entry.getValue() == null) continue;
            for (HookDto h : entry.getValue()) {
                HookDefinition def = toDefinition(h, event, source, file);
                if (def != null) out.add(def);
            }
        }
        return out;
    }

    private static HookDefinition toDefinition(HookDto h, HookEvent event, HookSource source, Path file) {
        if (h == null || h.command() == null || h.command().isBlank()) {
            log.warn("跳过缺 command 的钩子（{} / {}）", file, event.configKey());
            return null;
        }
        String matcher = (h.matcher() == null || h.matcher().isBlank()) ? "" : h.matcher();
        if (!matcher.isEmpty()) {
            try {
                Pattern.compile(matcher);   // 只验证可编译；matchesTool 每次现编译（钩子低频，不值得缓存）
            } catch (PatternSyntaxException e) {
                log.warn("跳过 matcher 非法的钩子（{}）：{}", matcher, e.toString());
                return null;
            }
        }
        return new HookDefinition(matcher, h.command().strip(), clampTimeout(h.timeout()), event, source);
    }

    private static int clampTimeout(Integer configured) {
        if (configured == null) return DEFAULT_TIMEOUT;
        if (configured < 1) { log.warn("timeout={} 非法，回退默认 {}", configured, DEFAULT_TIMEOUT); return DEFAULT_TIMEOUT; }
        return Math.min(configured, MAX_TIMEOUT);
    }
}
