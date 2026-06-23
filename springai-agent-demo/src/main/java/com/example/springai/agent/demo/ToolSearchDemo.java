package com.example.springai.agent.demo;

import com.example.springai.agent.tools.DateTimeTools;
import com.example.springai.agent.tools.OfficeTools;
import com.example.springai.agent.tools.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;

import java.util.List;

/**
 * 示例 5：工具搜索 / 动态工具发现（ToolSearchToolCallingAdvisor）。
 *
 * <p>问题背景：工具一多，传统做法把【所有】工具定义一次性发给模型，既费 token，又容易让模型选错。
 *
 * <p>ToolSearchToolCallingAdvisor 换了思路 ——「按需发现（progressive tool disclosure）」：
 * <ol>
 *   <li>启动时把你注册的所有工具建立索引，但<b>不</b>把它们直接暴露给模型；</li>
 *   <li>只给模型一个内置的元工具 <code>toolSearchTool</code>，让它用自然语言<b>搜索</b>需要的工具；</li>
 *   <li>把搜到的工具<b>注入</b>后，模型再真正调用它们完成任务。</li>
 * </ol>
 *
 * <p>本示例注册了一堆工具（请假、会议室、快递、翻译、乘法、时间、天气……），但只问一个关于“年假”的问题。
 * 你会看到：模型先搜索、命中 queryAnnualLeave，再调用它作答——其余无关工具从未被发送给模型。
 *
 * <p>本示例用 {@link RegexToolIndex}（基于关键词/正则匹配，<b>无需向量模型、零外部依赖</b>），
 * 是最适合入门的索引实现；另有 Lucene、向量（语义检索）两种实现可按需替换。
 */
public class ToolSearchDemo implements Demo {

    private final ChatClient chatClient;

    public ToolSearchDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "工具搜索 / 动态工具发现（ToolSearchToolCallingAdvisor）";
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public void run() {
        // 工具索引：RegexToolIndex 用关键词/正则匹配工具的名称与描述，无需向量模型。
        // 外面再包一层 LoggingToolIndex，把模型每次“搜索工具”的过程打印出来，让“按需发现”看得见。
        ToolIndex toolIndex = new LoggingToolIndex(new RegexToolIndex());

        // 构建工具搜索 advisor：把索引交给它，maxResults 限制每次搜索最多返回几个工具引用。
        ToolSearchToolCallingAdvisor toolSearchAdvisor = ToolSearchToolCallingAdvisor.builder()
                .toolIndex(toolIndex)
                .maxResults(5)
                .build();

        String question = "帮我查一下张三还有多少年假？";
        System.out.println("问：" + question);
        System.out.println("（已注册大量工具：请假/会议室/快递/翻译/乘法/时间/天气……但只有“查年假”与本问题相关）\n");

        String answer = chatClient.prompt()
                .user(question)
                // 像平常一样注册全部工具——区别在于：advisor 不会把它们一次性发给模型，
                // 而是先建索引、让模型搜索、再注入命中的工具。
                .tools(new OfficeTools(), new DateTimeTools(), new WeatherTools())
                .advisors(toolSearchAdvisor)
                // 工具搜索 advisor 按会话缓存工具索引，需提供一个会话 id（默认键就是 ChatMemory.CONVERSATION_ID）
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "tool-search-demo"))
                .call()
                .content();

        System.out.println("答：" + answer);
        System.out.println("""

                说明：上面 🔎 开头的行就是模型在【搜索工具】——它没有拿到全部工具定义，
                而是先用自然语言搜索“年假”，命中 queryAnnualLeave 后才注入并调用它。
                工具越多，这种“按需发现”省下的 token 越可观。
                """);
    }

    /**
     * 一个“装饰器”：包住真正的 {@link ToolIndex}，在每次 search 时打印查询词与命中的工具，
     * 让模型“搜索工具”的过程在控制台可见。其余方法原样转发给被包装的索引。
     */
    private static final class LoggingToolIndex implements ToolIndex {

        private final ToolIndex delegate;

        LoggingToolIndex(ToolIndex delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolSearchResponse search(ToolSearchRequest request) {
            ToolSearchResponse response = delegate.search(request);
            List<String> hits = response.toolReferences().stream().map(ToolReference::toolName).toList();
            System.out.printf("  🔎 模型搜索工具：query=\"%s\" → 命中 %s%n", request.query(), hits);
            return response;
        }

        @Override
        public void indexTool(String sessionId, ToolReference toolReference) {
            delegate.indexTool(sessionId, toolReference);
        }

        @Override
        public void indexTools(String sessionId, List<ToolReference> toolReferences) {
            delegate.indexTools(sessionId, toolReferences);
            System.out.printf("  📇 已为本次会话建立工具索引，共 %d 个工具（但不会全部发给模型）%n",
                    toolReferences.size());
        }

        @Override
        public void clearIndex(String sessionId) {
            delegate.clearIndex(sessionId);
        }
    }
}
