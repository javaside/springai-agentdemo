package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示词真的渲染得出来：{@code {占位符}} 与 {@code .param(...)} 必须一一对应。
 *
 * <p><b>为什么需要它</b>：{@code SYSTEM_TEMPLATE} 的参数是<b>运行时</b>求值的，漏配一个 param
 * 只有第一个真实请求才会炸。实测（变异验证）：删掉 {@code .param(TODO_DISCIPLINE_KEY, ...)}
 * 之后，全部装配类测试照样全绿——「构建 runtime」不等于「渲染提示词」。
 * 这里用桩 ChatModel 接住请求、把渲染后的系统消息捞出来断言，把这类「上线才炸」的缺口钉在测试里。
 */
class AgentToolsSystemPromptTest {

    @TempDir Path root;

    /** 接住请求里的系统消息，返回一个不发起任何工具调用的响应。 */
    private static final class CapturingModel implements ChatModel {
        private final List<String> systemTexts = new ArrayList<>();

        @Override public ChatResponse call(Prompt p) {
            for (Message m : p.getInstructions()) {
                if (m instanceof SystemMessage sm) {
                    systemTexts.add(sm.getText());
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }

        @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
    }

    /** 可用假 provider：桩模型外只提供 registry 选家所需的最小信息。 */
    private record StubProvider(String id, String model, ChatModel chatModel) implements LlmProvider {
        @Override public boolean available() { return true; }
        @Override public ChatOptions options(String modelId) {
            return ToolCallingChatOptions.builder().model(modelId).build();
        }
        @Override public List<ModelOption> models() { return List.of(new ModelOption(model, model, "")); }
        @Override public String defaultModel() { return model; }
        @Override public ModelCapabilities capabilities(String modelId) { return ModelCapabilities.TEXT_ONLY; }
    }

    @Test
    @DisplayName("系统提示词可渲染，且含任务清单纪律段（占位符已替换）")
    void systemPromptRenders_andCarriesTodoDiscipline() {
        CapturingModel model = new CapturingModel();
        AgentTools.AgentRuntime rt = AgentTools.build(
                new ProviderRegistry(List.of(new StubProvider("stub", "stub-model", model))),
                root, new StubListener());

        RuntimeException failure = null;
        try {
            // 必须消费 CallResponseSpec：Spring AI 的 call() 只是拿到一个惰性 spec，不取内容就不发请求
            rt.client().prompt().user("hi")
                    // 生产路径由 CodingAgent.submit 每回合注入会话 id；不给会被记忆 advisor 拦下
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "prompt-render-test"))
                    .call().content();
        } catch (RuntimeException ex) {
            // 本用例的主体是「提示词渲染得出来」，不是整条请求链路（记忆/压缩 advisor 在桩环境下可能抛）。
            // 记下来只为了让断言失败时能说清原因——渲染一旦失败，请求到不了桩模型，断言照样红。
            failure = ex;
        }

        assertFalse(model.systemTexts.isEmpty(),
                "系统提示词必须渲染到模型请求里；请求未到达桩模型，异常=" + failure);
        String prompt = model.systemTexts.get(0);
        assertTrue(prompt.contains("任务清单纪律（TodoWrite）"),
                "系统提示词须含任务清单纪律段（TODO_DISCIPLINE 参数没接上？）");
        assertFalse(prompt.contains("{TODO_DISCIPLINE}"),
                "占位符必须被替换，实际=" + prompt);
    }
}
