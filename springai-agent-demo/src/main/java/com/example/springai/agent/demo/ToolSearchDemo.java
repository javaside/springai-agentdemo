package com.example.springai.agent.demo;

import com.example.springai.agent.tools.DateTimeTools;
import com.example.springai.agent.tools.OfficeTools;
import com.example.springai.agent.tools.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
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

        // 日志 advisor：放到工具搜索 advisor 的【内层】（order 更大），这样工具调用循环里
        // 每一轮“发给模型的请求 / 模型的响应”都会被打印，完整交互过程一览无余。
        // 默认格式会把每个工具的完整 JSON Schema 都打出来，太长；这里用精简的自定义格式：
        //   请求 → 本轮模型“能看到哪些工具”；响应 → 模型“决定调用哪个工具，还是直接回答”。
        SimpleLoggerAdvisor loggerAdvisor = SimpleLoggerAdvisor.builder()
                .order(ToolCallingAdvisor.DEFAULT_ORDER + 100)
                .requestToString(ToolSearchDemo::formatRequest)
                .responseToString(ToolSearchDemo::formatResponse)
                .build();

        String answer = chatClient.prompt()
                .user(question)
                // 像平常一样注册全部工具——区别在于：advisor 不会把它们一次性发给模型，
                // 而是先建索引、让模型搜索、再注入命中的工具。
                .tools(new OfficeTools(), new DateTimeTools(), new WeatherTools())
                .advisors(toolSearchAdvisor, loggerAdvisor)
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

    /** 精简打印“本轮发给模型的请求”：只列出这一轮模型能看到哪些工具。 */
    private static String formatRequest(ChatClientRequest request) {
        String tools = "（无）";
        if (request.prompt().getOptions() instanceof ToolCallingChatOptions opts) {
            List<String> names = opts.getToolCallbacks().stream()
                    .map(tc -> tc.getToolDefinition().name())
                    .toList();
            if (!names.isEmpty()) {
                tools = String.join(", ", names);
            }
        }
        return "↗ 发给模型 | 本轮可用工具: " + tools;
    }

    /** 精简打印“模型这一轮的响应”：是请求调用某个工具，还是直接给出最终文本。 */
    private static String formatResponse(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return "↘ 模型响应 | (空)";
        }
        AssistantMessage out = response.getResult().getOutput();
        if (out.getToolCalls() != null && !out.getToolCalls().isEmpty()) {
            String calls = out.getToolCalls().stream()
                    .map(tc -> tc.name() + "(" + clean(tc.arguments()) + ")")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            return "↘ 模型响应 | 请求调用工具: " + calls;
        }
        return "↘ 模型响应 | 直接回答: " + truncate(clean(out.getText()), 50);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
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
