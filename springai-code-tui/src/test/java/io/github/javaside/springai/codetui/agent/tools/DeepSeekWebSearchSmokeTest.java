package io.github.javaside.springai.codetui.agent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 DeepSeek Web Search API 冒烟。双门控：{@code CODETUI_LIVE_TESTS=1} 显式开（联网、花钱、
 * 墙钟不稳，默认不跑——同 {@link BochaWebSearchSmokeTest}）+ {@code DEEPSEEK_API_KEY}。
 * 成功搜索一次 0.005 元（5 元/千次），零结果与失败请求不计费。
 *
 * <p>这也是 200 响应真实字段形状的唯一验证途径——离线 stub 按官方文档造数据，
 * 真机形状若有出入以此为准回填 spec「已知取舍」。
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_LIVE_TESTS", matches = "1")   // 默认不跑：联网、花钱、且墙钟断言天生不稳
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class DeepSeekWebSearchSmokeTest {

    @Test
    void realSearchReturnsResultsWithUrls() {
        DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder(System.getenv("DEEPSEEK_API_KEY"))
                .resultCount(3)
                .build();

        String out = tool.webSearch("Spring AI 框架");

        System.out.println("[smoke] DeepSeek 搜索返回：\n" + out);
        assertTrue(out.contains("找到"), "应返回结果列表而非零结果提示，实际=" + out);
        assertTrue(out.contains("http"), "结果里应含可访问的网址，实际=" + out);
    }

    /**
     * 搜索语法用例：site: 限定域名。验证语法被真机接受（而非 400 参数错误）——
     * 这是「过滤靠搜索语法」设计的实际验证口。不断言结果全在白名单内（引擎侧重写查询词时
     * 可能放宽限定，脆断言只会制造误报）；调用成功 + 打印结果供人工核对即可。
     */
    @Test
    void searchSyntaxSiteAcceptedByRealApi() {
        DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder(System.getenv("DEEPSEEK_API_KEY"))
                .resultCount(3)
                .build();

        String out = tool.webSearch("Spring AI reference site:docs.spring.io");

        System.out.println("[smoke] site: 语法搜索返回：\n" + out);
        assertTrue(out.contains("找到") || out.contains("没搜到"),
                "调用本身应成功（语法被接受），实际=" + out);
    }
}
