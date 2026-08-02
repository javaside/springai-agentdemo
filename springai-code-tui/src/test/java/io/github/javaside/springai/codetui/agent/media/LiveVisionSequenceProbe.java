package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真机探针：验证「assistant(tool_calls) → tool → user(带图)」序列是否被服务端接受。
 *
 * <p>默认跳过（无 key 即不跑），CI 不依赖它。这不是回归测试，是一次性的架构可行性验证。
 *
 * <p>断言方式刻意选了**颜色判别**而不是「回复非空」：非空只能证明 HTTP 没挂，
 * 证明不了图片真的进了模型。发一张纯绿图、要求答出「绿」，模型只有真看到像素才答得对。
 * 对照实验（同序列但不挂 Media）实测回「只看到图片文件引用，无法看到实际图像内容」，
 * 说明这个断言确实有判别力。
 */
class LiveVisionSequenceProbe {

    /** 纯绿 16×16 PNG。够小（<100B）不产生真实费用，又有明确可判别的内容。 */
    private static final byte[] GREEN_PNG = solidPng(16, 16, 0, 200, 0);

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void openAiAcceptsUserMessageAfterToolResponse() {
        var provider = new io.github.javaside.springai.codetui.agent.OpenAiProvider(
                System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_BASE_URL"), null);
        ChatResponse r = provider.chatModel().call(sequence(provider.defaultModel()));
        String text = r.getResult().getOutput().getText();
        System.out.println("[probe] model=" + provider.defaultModel() + " reply=" + text);
        assertNotNull(text, "模型没有返回任何文本");
        assertFalse(text.isBlank(), "模型返回空白文本");
        // 上面两条只证明 HTTP 没挂。真的看到了那张绿图才答得出「绿」——
        // 没看到只会说「无法看到实际图像内容」（已做对照实验，见类注释）。
        assertTrue(text.toLowerCase().matches("(?s).*(绿|green).*"), "模型没认出绿色，回复：" + text);
    }

    /** 构造那个可疑序列：system → user → assistant(tool_calls) → tool → user(带图)。 */
    private Prompt sequence(String modelId) {
        var call = new AssistantMessage.ToolCall("call_1", "function", "Read",
                "{\"filePath\":\"probe.png\"}");
        List<Message> msgs = List.of(
                new SystemMessage("你是测试助手，只需回答看到的内容。"),
                new UserMessage("这张图是什么颜色？"),
                AssistantMessage.builder().content("").toolCalls(List.of(call)).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "Read",
                                "[file reference]\nkind: image\nname: probe.png\n[/file reference]")))
                        .build(),
                UserMessage.builder()
                        .text("以下是上面工具结果中的图片：probe.png")
                        // data(byte[]) 走 Builder.data(Object)；data(Resource) 也只是内部先 getContentAsByteArray()，
                        // 两者最终都存 byte[]，OpenAiChatModel 再编成 data:<mime>;base64,... 的 image_url。等价。
                        .media(Media.builder()
                                .mimeType(MimeTypeUtils.IMAGE_PNG)
                                .data(GREEN_PNG)
                                .name("probe.png")
                                .build())
                        .build());
        return new Prompt(msgs, org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model(modelId).build());
    }

    /** 手搓纯色 PNG，免得往仓库里塞二进制夹具。 */
    private static byte[] solidPng(int w, int h, int r, int g, int b) {
        var raw = new ByteArrayOutputStream();
        for (int y = 0; y < h; y++) {
            raw.write(0);                       // filter type: none
            for (int x = 0; x < w; x++) {
                raw.write(r);
                raw.write(g);
                raw.write(b);
            }
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' });
        var ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, w);
        writeInt(ihdr, h);
        ihdr.writeBytes(new byte[] { 8, 2, 0, 0, 0 });  // 8-bit truecolor RGB
        writeChunk(out, "IHDR", ihdr.toByteArray());
        writeChunk(out, "IDAT", deflate(raw.toByteArray()));
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) {
        var deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        writeInt(out, data.length);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }
}
