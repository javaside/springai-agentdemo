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
 * 注册 → HTTP 层改写 → 真实 API）。验证内联 base64 通道与视觉模型回答。
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
        ChatResponse resp = model.call(new Prompt(msg,
                DeepSeekChatOptions.builder().model("deepseek-v4-flash-vision-exp").build()));

        String text = resp.getResult().getOutput().getText();
        assertTrue(text != null && !text.isBlank(), "视觉模型应返回非空回答");
        System.out.println("DeepSeek 视觉回答: " + text);
        assertFalse(text.toLowerCase().contains("error") && text.toLowerCase().contains("support"),
                "不应出现 'does not support image' 类错误");
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
