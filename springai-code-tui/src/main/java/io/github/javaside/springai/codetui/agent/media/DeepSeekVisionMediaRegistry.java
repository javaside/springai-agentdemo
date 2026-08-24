package io.github.javaside.springai.codetui.agent.media;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对象层 → HTTP 改写层的图片字节通道（DeepSeek 专属）。
 *
 * <p><b>为什么需要它</b>：spring-ai-deepseek 2.0.0 序列化消息时只用 {@code getText()}，
 * {@code UserMessage} 上的 {@code Media} 被静默丢弃，且 {@code ChatCompletionMessage.content}
 * 是 {@code String} 装不下 content 数组。因此图片字节必须经本注册表从「对象层」传到
 * 「JSON 改写层」：{@code DeepSeekThinkingChatModel} 按「消息在 instructions 里的下标:该消息
 * 内 media 序号」注册，改写器（{@code DeepSeekThinkingBodyCodec}）按同一 key 消费。
 *
 * <p><b>并发</b>：有图请求在本项目里只有主 agent 当前回合（串行）；无图请求（子 agent 等）
 * 不注册也不清理，与有图请求并发互不干扰。key 消费即删（{@link #take}）+ 请求结束清理
 * （调用方）保证无跨请求残留。
 */
public final class DeepSeekVisionMediaRegistry {

    /** 图片在请求体里的呈现通道。 */
    public enum Transport { INLINE, FILES }

    /** INLINE：base64 内联（bytes+mimeType）；FILES：file_id 引用（fileId）。
     *
     * @param transport 呈现通道，决定下面哪几个字段有值
     * @param bytes     图片原始字节；仅 INLINE 非空
     * @param mimeType  图片 MIME；仅 INLINE 非空
     * @param fileId    Files API 返回的 file_id；仅 FILES 非空
     */
    public record Entry(Transport transport, byte[] bytes, String mimeType, String fileId) {
        public static Entry inline(byte[] bytes, String mimeType) {
            return new Entry(Transport.INLINE, bytes, mimeType, null);
        }
        public static Entry file(String fileId) {
            return new Entry(Transport.FILES, null, null, fileId);
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void put(int messageIndex, int mediaIndex, Entry entry) {
        entries.put(key(messageIndex, mediaIndex), entry);
    }

    /** 消费即删：改写器每取一张图，key 立即失效，天然防止同 key 二次误用。 */
    public Entry take(String key) {
        return entries.remove(key);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
    }

    public static String key(int messageIndex, int mediaIndex) {
        return messageIndex + ":" + mediaIndex;
    }
}
