package io.github.javaside.springai.codetui.agent;

import org.springaicommunity.agent.utils.MarkdownParser;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 读 agents/*.md → {@link SubagentSpec}。复用框架 {@link MarkdownParser} 解析 frontmatter，
 * 用 {@link DefaultResourceLoader} 读 classpath 资源。
 */
public final class SubagentLoader {

    /** 内置四个子 agent 的 classpath 位置。 */
    private static final List<String> BUILTIN_URIS = List.of(
            "classpath:/agents/general-purpose.md",
            "classpath:/agents/explore.md",
            "classpath:/agents/plan.md",
            "classpath:/agents/bash.md");

    private SubagentLoader() {
    }

    /** 加载内置四个 → name 键的有序 map。 */
    public static Map<String, SubagentSpec> loadBuiltins() {
        Map<String, SubagentSpec> map = new LinkedHashMap<>();
        for (String uri : BUILTIN_URIS) {
            SubagentSpec spec = parse(uri);
            map.put(spec.name(), spec);
        }
        return map;
    }

    /** 解析单个 .md 资源 URI（classpath:/ 或 file:）为 SubagentSpec。 */
    public static SubagentSpec parse(String uri) {
        String md;
        try {
            md = new DefaultResourceLoader().getResource(uri)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取子 agent 定义失败: " + uri, e);
        }
        MarkdownParser parser = new MarkdownParser(md);
        Map<String, Object> fm = parser.getFrontMatter();
        return new SubagentSpec(
                str(fm, "name"),
                str(fm, "description"),
                parser.getContent(),
                csv(fm, "tools"),
                csv(fm, "disallowedTools"),
                blankToNull(str(fm, "model")),
                csv(fm, "skills"));
    }

    private static String str(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 逗号分隔 → trim 去空的 List；缺键或空 → 空 List。 */
    private static List<String> csv(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        if (v == null) {
            return List.of();
        }
        String raw = v.toString();
        List<String> out = new ArrayList<>();
        for (String part : Stream.of(raw.split(",")).map(String::trim).toList()) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }
}
