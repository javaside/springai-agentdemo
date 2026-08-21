package io.github.javaside.springai.codetui.agent.media;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DeepSeek Files API 上传客户端（OpenAI 兼容端点 {@code POST /files}）。
 *
 * <p><b>sha256 幂等</b>：同一字节只上传一次，file_id 进程内缓存——同一张图在回合内
 * 会被多次组装（无状态请求的固有代价），每次都上传是白花钱。
 *
 * <p><b>失败一律返回 empty</b>：Files 通道是<b>增强</b>不是依赖，调用方（DeepSeekThinkingChatModel）
 * 拿不到 file_id 就降级内联 base64。上传接口经 {@link Uploader} 注入：生产用 RestClient 实现，
 * 单测注入假实现（零网络）。
 */
public final class DeepSeekFileStore {

    /** HTTP 上传注入点。form 是 multipart 字段表，返回服务端响应体（JSON 字符串）。 */
    public interface Uploader {
        String upload(MultiValueMap<String, Object> form);
    }

    private final Uploader uploader;
    private final Map<String, String> bySha = new ConcurrentHashMap<>();

    public DeepSeekFileStore(Uploader uploader) {
        this.uploader = uploader;
    }

    /** 按 sha256 幂等取 file_id；上传失败/响应无 id → empty。 */
    public Optional<String> fileIdFor(byte[] bytes, String filename) {
        String sha = sha256(bytes);
        String cached = bySha.get(sha);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", new NamedByteArrayResource(bytes, filename));
            form.add("purpose", "user_data");
            String body = uploader.upload(form);
            String id = parseFileId(body);
            if (id == null) {
                return Optional.empty();
            }
            bySha.put(sha, id);
            return Optional.of(id);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 解析上传响应取 file_id。纯函数，供单测。 */
    static String parseFileId(String body) {
        try {
            tools.jackson.databind.JsonNode root = new tools.jackson.databind.ObjectMapper().readTree(body);
            String id = root.path("id").asText(null);
            return id == null || id.isBlank() ? null : id;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * multipart 文件名：{@link org.springframework.core.io.ByteArrayResource} 不带文件名，
     * Spring 序列化 multipart 时取 {@code getFilename()} 拿到 null，文件名会缺失——故自定义。
     */
    static final class NamedByteArrayResource extends org.springframework.core.io.ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
