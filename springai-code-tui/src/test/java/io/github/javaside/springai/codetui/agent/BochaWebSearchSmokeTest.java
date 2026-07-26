package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实博查 API 冒烟。绑 {@code BOCHA_API_KEY}：有 key 则 {@code mvn test} 自动跑，无 key 优雅跳过
 * （门控模式同 {@link CodingAgentSpikeTest}）。需要网络与有效 key，会消耗一次搜索额度。
 *
 * <p>这也是 {@code ProxySelector.getDefault()} 那行唯一的实际验证途径——起代理 stub 做单测的成本
 * 明显高于收益，靠真机跑一次体感确认更划算。
 */
@EnabledIfEnvironmentVariable(named = "BOCHA_API_KEY", matches = ".+")
class BochaWebSearchSmokeTest {

    @Test
    void realSearchReturnsResultsWithUrls() {
        BochaWebSearchTool tool = BochaWebSearchTool.builder(System.getenv("BOCHA_API_KEY"))
                .resultCount(3)
                .build();

        String out = tool.webSearch("Spring AI 框架", null, null);

        System.out.println("[smoke] 博查搜索返回：\n" + out);
        assertTrue(out.contains("找到"), "应返回结果列表而非零结果提示，实际=" + out);
        assertTrue(out.contains("http"), "结果里应含可访问的网址，实际=" + out);
    }
}
