package io.github.javaside.springai.codetui.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.core.RequestOptions;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * 千问（DashScope 兼容模式）SSE 归一化 {@link HttpClient} 装饰器——修复流式 tool_calls 分片形状偏差。
 *
 * <p><b>要修什么</b>（2026-07-16 对 qwen3.7-max 真实抓包实锤，见 QwenChunkMergerHypothesisTest）：
 * OpenAI 规范下工具调用的后续增量片<b>不带 id 字段</b>，千问发的却是 {@code "id":""}（空字符串）。
 * Spring AI 2.0.0 {@code OpenAiChatModel$ChunkMerger.mergeDeltas} 以「分片带 id = 新工具调用」判定
 * （{@code Optional.of("")} 也算 present），于是每个增量片都被误判为新调用，合并出无 {@code function.name}
 * 的残片，{@code chunkToChatCompletion} 对残片 {@code Optional.get()} 抛 NoSuchElementException——
 * 主 agent 流式一触发工具调用即崩（框架已知 bug 家族 spring-ai#4629/#4790，2.0.0 未修）。
 *
 * <p><b>怎么修</b>：拦截 {@code text/event-stream} 响应体，逐行处理，把 tool_calls 增量片里的空串
 * id 字段<b>删掉</b>，恢复 OpenAI 规范形状，框架既有合并逻辑即正确。安全性：JSON 字符串值内的引号
 * 必被转义（{@code \"}），故裸 {@code "id":""} 只可能是结构字段、不可能出现在 content 文本里；
 * 顶层 chunk id 恒非空不受影响；解析失败的行原样放行（fail-open）。
 *
 * <p>非流式响应（JSON，工具调用带完整 id）原样透传。仅 QwenProvider 装配本装饰器，不影响其他 provider。
 */
final class QwenSseNormalizingHttpClient implements HttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient delegate;

    QwenSseNormalizingHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
        return maybeNormalize(delegate.execute(request, requestOptions));
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
        return delegate.executeAsync(request, requestOptions).thenApply(this::maybeNormalize);
    }

    @Override
    public void close() {
        delegate.close();
    }

    /** 仅 SSE 响应包归一化流；其余（JSON 等）原样返回。 */
    private HttpResponse maybeNormalize(HttpResponse response) {
        boolean sse = response.headers().values("content-type").stream()
                .anyMatch(v -> v.toLowerCase().contains("text/event-stream"));
        if (!sse) {
            return response;
        }
        InputStream normalized = normalizingStream(response.body());
        return new HttpResponse() {
            @Override public int statusCode() { return response.statusCode(); }
            @Override public Headers headers() { return response.headers(); }
            @Override public InputStream body() { return normalized; }
            @Override public void close() { response.close(); }
        };
    }

    /** 把底层 SSE 字节流包成「逐行归一化」流。包级可见供单测。 */
    static InputStream normalizingStream(InputStream in) {
        return new NormalizingInputStream(in);
    }

    /**
     * 归一化一行 SSE：tool_calls 增量片的空串 id 字段删除，其余行原样返回（同一实例，便于测试判断未动）。
     * 入参不含行终止符。包级可见供单测。
     */
    static String normalizeLine(String line) {
        // 快速排除：非 data 行 / 无 tool_calls / 无空串 id 的行零开销放行
        if (!line.startsWith("data:") || !line.contains("\"tool_calls\"") || !line.contains("\"id\":\"\"")) {
            return line;
        }
        String json = line.substring("data:".length()).trim();
        try {
            JsonNode root = MAPPER.readTree(json);
            boolean changed = false;
            for (JsonNode choice : root.path("choices")) {
                for (JsonNode tc : choice.path("delta").path("tool_calls")) {
                    if (tc.isObject() && tc.path("id").isTextual() && tc.path("id").asText().isEmpty()) {
                        ((ObjectNode) tc).remove("id");
                        changed = true;
                    }
                }
            }
            return changed ? "data: " + MAPPER.writeValueAsString(root) : line;
        } catch (IOException e) {
            return line;   // 非法 JSON：不动，交给下游按原样处理
        }
    }

    /**
     * 逐行变换的字节流。SSE 事件以换行分帧（data 行必以 \n 收尾），故按行缓冲不破坏流式性；
     * 行终止符（\n 或 \r\n）原样保留，UTF-8 按整行解码避免多字节字符截断。
     */
    private static final class NormalizingInputStream extends InputStream {

        private final InputStream in;
        private byte[] buf = new byte[0];
        private int pos;
        private boolean eof;

        NormalizingInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            return ensure() ? buf[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (!ensure()) {
                return -1;
            }
            int n = Math.min(len, buf.length - pos);
            System.arraycopy(buf, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }

        /** 缓冲区耗尽则读入并变换下一行。返回 false = 底层 EOF 且无剩余数据。 */
        private boolean ensure() throws IOException {
            while (pos >= buf.length) {
                if (eof) {
                    return false;
                }
                nextLine();
            }
            return true;
        }

        private void nextLine() throws IOException {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(256);
            int c;
            while ((c = in.read()) != -1) {
                raw.write(c);
                if (c == '\n') {
                    break;
                }
            }
            if (c == -1) {
                eof = true;
            }
            byte[] bytes = raw.toByteArray();
            // 拆出行终止符（\n / \r\n），只对内容部分做变换
            int end = bytes.length;
            while (end > 0 && (bytes[end - 1] == '\n' || bytes[end - 1] == '\r')) {
                end--;
            }
            String content = new String(bytes, 0, end, StandardCharsets.UTF_8);
            String normalized = normalizeLine(content);
            if (normalized.equals(content)) {
                buf = bytes;             // 未变：原字节直通（含终止符）
            } else {
                ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
                out.writeBytes(normalized.getBytes(StandardCharsets.UTF_8));
                out.write(bytes, end, bytes.length - end);
                buf = out.toByteArray();
            }
            pos = 0;
        }
    }
}
