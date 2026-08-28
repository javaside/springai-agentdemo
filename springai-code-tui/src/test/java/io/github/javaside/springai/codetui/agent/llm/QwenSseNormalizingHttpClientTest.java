package io.github.javaside.springai.codetui.agent.llm;

import com.openai.models.chat.completions.ChatCompletionChunk;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QwenSseNormalizingHttpClient} 单测。分片 JSON 为 2026-07-16 对 qwen3.7-max 的真实抓包原文。
 *
 * <p>要修的形状偏差：千问流式 tool_calls 的后续增量片带 {@code "id":""}（OpenAI 真身不带 id 字段），
 * 导致 Spring AI ChunkMerger 把增量片误判为新工具调用（详见 QwenChunkMergerHypothesisTest）。
 */
class QwenSseNormalizingHttpClientTest {

    private static final String FIRST = "data: {\"model\":\"qwen3.7-max\",\"id\":\"chatcmpl-fb43\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_ead3ae98e739438eb40c8c31\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}],\"content\":\"\",\"reasoning_content\":\"\"},\"index\":0,\"finish_reason\":null,\"logprobs\":null}],\"created\":1784138795,\"object\":\"chat.completion.chunk\",\"usage\":null}";

    private static final String DELTA_EMPTY_ID = "data: {\"model\":\"qwen3.7-max\",\"id\":\"chatcmpl-fb43\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"\",\"type\":\"function\",\"function\":{\"arguments\":\"{\\\"city\\\": \\\"北京\\\"}\"}}],\"content\":\"\",\"reasoning_content\":\"\"},\"index\":0,\"finish_reason\":null,\"logprobs\":null}],\"created\":1784138795,\"object\":\"chat.completion.chunk\",\"usage\":null}";

    // ── 行级归一化 ──────────────────────────────────────────

    @Test
    void deltaChunk_emptyToolCallId_isRemoved() {
        String out = QwenSseNormalizingHttpClient.normalizeLine(DELTA_EMPTY_ID);
        assertFalse(out.contains("\"id\":\"\""), "空串 id 字段应被删除");
        assertTrue(out.contains("\"arguments\":\"{\\\"city\\\": \\\"北京\\\"}\""), "arguments 增量必须原样保留");
        assertTrue(out.contains("\"id\":\"chatcmpl-fb43\""), "chunk 顶层 id 不能被误伤");
    }

    @Test
    void firstChunk_realToolCallId_isUntouched() {
        assertSame(FIRST, QwenSseNormalizingHttpClient.normalizeLine(FIRST));
    }

    @Test
    void nonDataLines_andContentChunks_passThrough() {
        assertSame("", QwenSseNormalizingHttpClient.normalizeLine(""));
        assertSame(": keep-alive", QwenSseNormalizingHttpClient.normalizeLine(": keep-alive"));
        assertSame("data: [DONE]", QwenSseNormalizingHttpClient.normalizeLine("data: [DONE]"));
        String content = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"index\":0}]}";
        assertSame(content, QwenSseNormalizingHttpClient.normalizeLine(content));
    }

    @Test
    void malformedJson_passesThroughUnchanged() {
        String broken = "data: {\"tool_calls\" \"id\":\"\" 不是合法json";
        assertSame(broken, QwenSseNormalizingHttpClient.normalizeLine(broken));
    }

    // ── 流级包装（多行 SSE、CRLF、UTF-8 中文跨行）──────────────

    @Test
    void stream_normalizesOnlyToolCallLines_preservesStructure() throws Exception {
        String sse = FIRST + "\r\n\r\n" + DELTA_EMPTY_ID + "\n\ndata: [DONE]\n\n";
        InputStream in = QwenSseNormalizingHttpClient.normalizingStream(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        String out = new String(in.readAllBytes(), StandardCharsets.UTF_8);

        String[] lines = out.split("\r?\n", -1);
        assertEquals("data: [DONE]", lines[4]);                       // 行结构保持
        assertTrue(lines[0].contains("call_ead3ae98e739438eb40c8c31")); // 首片 id 保留
        assertFalse(lines[2].contains("\"id\":\"\""));                 // 增量片空 id 已删
        assertTrue(lines[2].contains("北京"));                         // UTF-8 中文无损
    }

    // ── 端到端：归一化后的分片穿过 Spring AI ChunkMerger 不再炸 ──

    @Test
    void normalizedChunks_surviveSpringAiChunkMerger() throws Exception {
        ChatCompletionChunk c1 = parse(QwenSseNormalizingHttpClient.normalizeLine(FIRST));
        ChatCompletionChunk c2 = parse(QwenSseNormalizingHttpClient.normalizeLine(DELTA_EMPTY_ID));

        Class<?> merger = Class.forName("org.springframework.ai.openai.OpenAiChatModel$ChunkMerger");
        Method merge = merger.getDeclaredMethod("mergeChunks", List.class);
        merge.setAccessible(true);
        Method convert = merger.getDeclaredMethod("chunkToChatCompletion", ChatCompletionChunk.class);
        convert.setAccessible(true);

        convert.invoke(null, merge.invoke(null, List.of(c1, c2)));   // 修复前此处抛 NoSuchElementException
    }

    private static ChatCompletionChunk parse(String dataLine) throws Exception {
        return com.openai.core.ObjectMappers.jsonMapper()
                .readValue(dataLine.substring("data: ".length()), ChatCompletionChunk.class);
    }
}
