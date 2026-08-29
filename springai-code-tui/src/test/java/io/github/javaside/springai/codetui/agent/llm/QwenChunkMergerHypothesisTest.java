package io.github.javaside.springai.codetui.agent.llm;

import com.openai.models.chat.completions.ChatCompletionChunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 假设验证（systematic-debugging Phase 3）：千问（DashScope 兼容模式）流式 tool_calls 的后续分片带
 * {@code "id":""}（OpenAI 真身是不带 id 字段），导致 Spring AI 2.0.0 {@code OpenAiChatModel$ChunkMerger}
 * 把每个 arguments 增量片误判为「新工具调用」，合并出无 name 残片，
 * {@code chunkToChatCompletion} 对残片 {@code Optional.get()} 抛 NoSuchElementException。
 *
 * <p>分片 JSON 为 2026-07-16 对 qwen3.7-max 真实抓包原文（tool_calls 相关三片，中间增量片略缩）。
 */
class QwenChunkMergerHypothesisTest {

    /** 真实抓包：首片带完整 id+name。 */
    private static final String CHUNK_FIRST = """
            {"model":"qwen3.7-max","id":"chatcmpl-fb43fd4a-43a6-9a4f-a82a-8f2860670a59","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_ead3ae98e739438eb40c8c31","type":"function","function":{"name":"get_weather","arguments":""}}],"content":"","reasoning_content":""},"index":0,"finish_reason":null,"logprobs":null}],"created":1784138795,"object":"chat.completion.chunk","usage":null}""";

    /** 真实抓包：后续增量片，id 是空字符串（问题所在）。 */
    private static final String CHUNK_DELTA_EMPTY_ID = """
            {"model":"qwen3.7-max","id":"chatcmpl-fb43fd4a-43a6-9a4f-a82a-8f2860670a59","choices":[{"delta":{"tool_calls":[{"index":0,"id":"","type":"function","function":{"arguments":"{\\"city\\": \\"北京\\"}"}}],"content":"","reasoning_content":""},"index":0,"finish_reason":null,"logprobs":null}],"created":1784138795,"object":"chat.completion.chunk","usage":null}""";

    /** 同一增量片但去掉了空 id/type 字段（OpenAI 规范形状，即修复后的期望形状）。 */
    private static final String CHUNK_DELTA_NORMALIZED = """
            {"model":"qwen3.7-max","id":"chatcmpl-fb43fd4a-43a6-9a4f-a82a-8f2860670a59","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\": \\"北京\\"}"}}],"content":"","reasoning_content":""},"index":0,"finish_reason":null,"logprobs":null}],"created":1784138795,"object":"chat.completion.chunk","usage":null}""";

    @Test
    void rawQwenChunks_breakChunkMerger() throws Exception {
        Object merged = mergeChunks(parse(CHUNK_FIRST), parse(CHUNK_DELTA_EMPTY_ID));
        InvocationTargetException e =
                assertThrows(InvocationTargetException.class, () -> chunkToChatCompletion(merged));
        assertInstanceOf(java.util.NoSuchElementException.class, e.getCause());
    }

    @Test
    void normalizedChunks_mergeCleanly() throws Exception {
        Object merged = mergeChunks(parse(CHUNK_FIRST), parse(CHUNK_DELTA_NORMALIZED));
        chunkToChatCompletion(merged);   // 不抛即通过：残片不再产生
    }

    private static ChatCompletionChunk parse(String json) throws Exception {
        return com.openai.core.ObjectMappers.jsonMapper().readValue(json, ChatCompletionChunk.class);
    }

    /** 反射进 Spring AI 私有 ChunkMerger（框架无公开入口，单测只能这么够到合并逻辑）。 */
    private static Object mergeChunks(ChatCompletionChunk... chunks) throws Exception {
        Method m = chunkMergerMethod("mergeChunks", List.class);
        return m.invoke(null, List.of(chunks));
    }

    private static Object chunkToChatCompletion(Object chunk) throws Exception {
        Method m = chunkMergerMethod("chunkToChatCompletion", ChatCompletionChunk.class);
        return m.invoke(null, chunk);
    }

    private static Method chunkMergerMethod(String name, Class<?> param) throws Exception {
        Class<?> merger = Class.forName("org.springframework.ai.openai.OpenAiChatModel$ChunkMerger");
        Method m = merger.getDeclaredMethod(name, param);
        m.setAccessible(true);
        return m;
    }
}
