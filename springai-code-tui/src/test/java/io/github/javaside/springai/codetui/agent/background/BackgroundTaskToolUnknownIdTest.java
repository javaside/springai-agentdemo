package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TaskOutput} 查无此 id 时，把当前实际存在的任务清单一并回给模型。
 *
 * <p>这一刻是它最需要看清单的时刻：它手上的 id 不对（陈旧、来自上个进程、或被淘汰了），
 * 而正确的下一步取决于「现在到底有哪些任务」。只回一句「未知任务」等于让它去猜。
 */
class BackgroundTaskToolUnknownIdTest {

    @Test
    @DisplayName("未知 id：既保留原有解释，也附上当前清单")
    void unknownIdCarriesTheCurrentList() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(64);
        String alive = registry.register("explore", "扫描鉴权相关代码");
        BackgroundTaskTool tool = new BackgroundTaskTool(registry, null, 1);

        String out = tool.fetch(new BackgroundTaskTool.Query("task_不存在", false));

        assertTrue(out.contains("未知任务"), "原有解释必须保留:\n" + out);
        assertTrue(out.contains("重新派发"), "原有的下一步指引必须保留:\n" + out);
        assertTrue(out.contains(alive), "应附上当前实际存在的任务清单:\n" + out);
        assertTrue(out.contains("扫描鉴权相关代码"), "清单要能认得出是哪个任务:\n" + out);
    }

    @Test
    @DisplayName("未知 id 且注册表为空：清单说「当前没有后台任务」，而不是留白让模型猜")
    void unknownIdOnEmptyRegistrySaysSo() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(64);
        BackgroundTaskTool tool = new BackgroundTaskTool(registry, null, 1);

        String out = tool.fetch(new BackgroundTaskTool.Query("task_不存在", false));

        assertTrue(out.contains("未知任务"), "原有解释必须保留:\n" + out);
        assertTrue(out.contains("没有后台任务"), "空清单也要明说:\n" + out);
    }
}
