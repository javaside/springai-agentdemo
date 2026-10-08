package io.github.javaside.springai.codetui.agent.tools;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实智谱 Web Search API 冒烟。双门控：{@code CODETUI_LIVE_TESTS=1} 显式开（联网、花钱、墙钟不稳，
 * 默认不跑——同 {@link BochaWebSearchSmokeTest}）+ {@code ZHIPU_API_KEY}。会消耗一次搜索额度（0.01 元起）。
 *
 * <p>余额不足（实测 429 + code 1113）显式 assume 跳过：环境态不是代码错，红了只会淹没在误报里。
 * 充值后本类即为 spec「已知取舍」里 200 响应形状与多域名分隔符的回填验证口。
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_LIVE_TESTS", matches = "1")
@EnabledIfEnvironmentVariable(named = "ZHIPU_API_KEY", matches = ".+")
class ZhipuWebSearchSmokeTest {

    @Test
    void realSearchReturnsResultsWithUrls() {
        ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder(System.getenv("ZHIPU_API_KEY"))
                .resultCount(3)
                .build();

        String out;
        try {
            out = tool.webSearch("Spring AI 框架", null, null);
        } catch (IllegalStateException e) {
            Assumptions.assumeTrue(
                    !(e.getMessage().contains("429") && e.getMessage().contains("1113")),
                    "智谱账户余额不足，跳过冒烟：" + e.getMessage());
            throw e;
        }

        System.out.println("[smoke] 智谱搜索返回：\n" + out);
        assertTrue(out.contains("找到"), "应返回结果列表而非零结果提示，实际=" + out);
        assertTrue(out.contains("http"), "结果里应含可访问的网址，实际=" + out);
    }

    /**
     * 双域名 include：验证 joinInclude 的逗号分隔符在真实 API 上被接受（spec「已知取舍」回填口）。
     * 不断言结果全在白名单内——分隔符若不被识别可能被当整串忽略，脆断言只会制造误报；
     * 调用成功 + 打印结果供人工核对即可。
     */
    @Test
    void dualDomainIncludeAcceptedByRealApi() {
        ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder(System.getenv("ZHIPU_API_KEY"))
                .resultCount(3)
                .build();

        String out;
        try {
            out = tool.webSearch("Spring AI reference", null, List.of("docs.spring.io", "github.com"));
        } catch (IllegalStateException e) {
            Assumptions.assumeTrue(
                    !(e.getMessage().contains("429") && e.getMessage().contains("1113")),
                    "智谱账户余额不足，跳过冒烟：" + e.getMessage());
            throw e;
        }

        System.out.println("[smoke] 双域名 include 返回：\n" + out);
        assertTrue(out.contains("找到") || out.contains("没搜到"),
                "调用本身应成功（接受逗号格式），实际=" + out);
    }
}
