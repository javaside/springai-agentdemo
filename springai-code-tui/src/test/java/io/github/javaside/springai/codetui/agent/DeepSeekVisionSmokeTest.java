package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.util.MimeTypeUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek 视觉真机探针：走完整链路（DeepSeekProvider.chatModel() → DeepSeekThinkingChatModel
 * 注册 → 流式 HTTP 改写 → 真实 API），与生产主 agent 同走 {@code model.stream(...)}。
 * 验证内联 base64 通道与视觉模型回答，断言颜色词以防假阳性（阻塞 {@code call()} 路径在
 * DEFAULT 思考配置下不装改写拦截器，图片根本不会送达模型，故必须走流式生产路径）。
 *
 * <p><b>门控</b>：需要联网 + 花钱，双 gate 默认跳过（同 CodingAgentSpikeTest 模式）：
 * {@code CODETUI_LIVE_TESTS=1} 且配置了 {@code DEEPSEEK_API_KEY} 才会跑。
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_LIVE_TESTS", matches = "1")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class DeepSeekVisionSmokeTest {

    @Test
    void visionModelSeesInlineRedImage() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        ChatModel model = new DeepSeekProvider(key).chatModel();

        byte[] png = solidColorPng(Color.RED);
        UserMessage msg = UserMessage.builder()
                .text("这张纯色图片是什么颜色？只答颜色名，不解释。")
                .media(List.of(Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType("image/png"))
                        .data(png).build()))
                .build();
        // 生产主 agent 同路（model.stream）：DeepSeekThinkingClientHttpConnector 无条件安装，
        // decorateStreaming 在 DEFAULT 思考配置下照样注入图片块；阻塞 call() 路径在 DEFAULT 下
        // 不装 RestClient 改写拦截器 → 图片从未送达模型（Task 6 review F1 假阳性根因），不可用。
        // doFinally 在流终止后才清注册表，collectList().block() 完整消费流，改写发生在订阅期，安全。
        // 流按 SSE 分块产出多条 ChatResponse（首块常为空 content 只带 role），须拼接全部文本取完整回答。
        List<ChatResponse> responses = model.stream(new Prompt(msg,
                DeepSeekChatOptions.builder().model("deepseek-v4-flash-vision-exp").build()))
                .collectList().block();
        String text = responses.stream()
                .map(r -> r.getResult() != null && r.getResult().getOutput() != null
                        ? r.getResult().getOutput().getText() : "")
                .filter(s -> s != null)
                .collect(java.util.stream.Collectors.joining());
        System.out.println("DeepSeek 视觉回答: " + text);
        assertTrue(text != null && !text.isBlank(), "视觉模型应返回非空回答");
        assertFalse(text.toLowerCase().contains("error") && text.toLowerCase().contains("support"),
                "不应出现 'does not support image' 类错误");
        String t = text.toLowerCase();
        assertTrue(t.contains("红") || t.contains("red"),
                "视觉模型应回答颜色（红/red），实际: " + text);
    }

    private static byte[] solidColorPng(Color c) throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }
}
