package io.github.javaside.springai.codetui.agent.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 {@code *_MODELS} 环境变量（逗号分隔模型 id，<b>第一项即默认模型</b>）。
 *
 * <p>未设置、空白或解析后无有效项 → 原样返回内置回退清单，行为与不配置完全一致。
 * 配置来的每个 id 映射为 {@code ModelOption(id, id, "")}（label 显示 id、描述留空）。
 * 不校验 id 有效性——真正校验发生在对话请求时由服务端报错，与内置清单同一策略。
 */
final class ModelListEnv {

    private ModelListEnv() {}

    static List<ModelOption> parse(String env, List<ModelOption> fallback) {
        if (env == null || env.isBlank()) {
            return fallback;
        }
        List<ModelOption> out = new ArrayList<>();
        for (String part : env.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                out.add(new ModelOption(id, id, ""));
            }
        }
        return out.isEmpty() ? fallback : List.copyOf(out);
    }
}
