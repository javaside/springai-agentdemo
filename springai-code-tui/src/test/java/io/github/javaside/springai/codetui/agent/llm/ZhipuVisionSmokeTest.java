package io.github.javaside.springai.codetui.agent.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.MimeTypeUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智谱视觉真机探针：验证 spring-ai-openai 通路对智谱 v4 API 的两个未证实假设——
 * (1) base64 data URL 形态的 image_url 智谱照单全收（DeepSeek 需要 HTTP 改写，智谱预期免改）；
 * (2) 流式 + 工具调用不依赖智谱文档建议的 tool_stream 参数也能正常吐 tool_calls。
 *
 * <p>构造与生产接线（CodeTuiApplication）一致：key + ZHIPU_BASE_URL（未设则回退 bigmodel.cn 默认端点），
 * 避免 Coding Plan key 打错端点被计费层 429。
 *
 * <p><b>门控</b>：联网 + 花钱，双 gate 默认跳过（同 DeepSeekVisionSmokeTest 模式）：
 * {@code source ~/.secrets && CODETUI_LIVE_TESTS=1 mvn test -pl springai-code-tui -Dtest=ZhipuVisionSmokeTest}
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_LIVE_TESTS", matches = "1")
@EnabledIfEnvironmentVariable(named = "ZHIPU_API_KEY", matches = ".+")
class ZhipuVisionSmokeTest {

    private static final String MODEL = "glm-5.3-flash";

    /** 假设 (1)：纯红图 → 模型答「红」。走生产同款 ChatModel.stream 路径。 */
    @Test
    void visionModelSeesInlineRedImage() throws Exception {
        ZhipuProvider p = new ZhipuProvider(System.getenv("ZHIPU_API_KEY"), System.getenv("ZHIPU_BASE_URL"));
        ChatModel model = p.chatModel();
        UserMessage msg = UserMessage.builder()
                .text("这张纯色图片是什么颜色？只答颜色名，不解释。")
                .media(List.of(Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType("image/png"))
                        .data(solidColorPng(Color.RED)).build()))
                .build();
        String text = join(model.stream(new Prompt(msg, p.options(MODEL)))
                .collectList().block(Duration.ofMinutes(3)));
        System.out.println("智谱视觉回答: " + text);
        assertTrue(text != null && !text.isBlank(), "应返回非空回答");
        String t = text.toLowerCase();
        assertTrue(t.contains("红") || t.contains("red"), "应答出颜色（红/red），实际: " + text);
    }

    /** 假设 (2)：流式 + 工具调用（无图）——tool_stream 缺失时 tool_calls 是否照常工作。 */
    @Test
    void streamingToolCallWorks() {
        ZhipuProvider p = new ZhipuProvider(System.getenv("ZHIPU_API_KEY"), System.getenv("ZHIPU_BASE_URL"));
        WeatherTool tool = new WeatherTool();
        ChatClient client = ChatClient.builder(p.chatModel()).defaultTools(tool).build();
        List<String> chunks = client.prompt()
                .user("调用工具查询北京天气，然后告诉我结果")
                .options(p.options(MODEL).mutate())
                .stream().content()
                .collectList()
                .block(Duration.ofMinutes(3));
        assertTrue(tool.invoked.get(), "流式 tool_calls 应正常触发工具（不依赖 tool_stream 参数）");
        assertFalse(String.join("", chunks).isBlank(), "工具结果回填后应有非空流式回答");
    }

    /** 生产形态复合：一张图 + 一次工具调用在同一条流里——agent 带视觉的真实请求形状。 */
    @Test
    void imageAndToolInOneStream() throws Exception {
        ZhipuProvider p = new ZhipuProvider(System.getenv("ZHIPU_API_KEY"), System.getenv("ZHIPU_BASE_URL"));
        WeatherTool tool = new WeatherTool();
        ChatClient client = ChatClient.builder(p.chatModel()).defaultTools(tool).build();
        UserMessage msg = UserMessage.builder()
                .text("先调用工具查询北京天气，再告诉我图片是什么颜色。两件事都做。")
                .media(List.of(Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType("image/png"))
                        .data(solidColorPng(Color.BLUE)).build()))
                .build();
        List<String> chunks = client.prompt()
                .messages(List.of(msg))
                .options(p.options(MODEL).mutate())
                .stream().content()
                .collectList()
                .block(Duration.ofMinutes(3));
        String text = String.join("", chunks);
        System.out.println("智谱图+工具复合回答: " + text);
        assertTrue(tool.invoked.get(), "复合请求里工具仍应被触发");
        String t = text.toLowerCase();
        assertTrue(t.contains("蓝") || t.contains("blue"), "复合请求里图仍应被看见（蓝/blue），实际: " + text);
    }

    static class WeatherTool {
        final AtomicBoolean invoked = new AtomicBoolean();

        @Tool(description = "查询指定城市的当前天气")
        String getWeather(String city) {
            invoked.set(true);
            return city + "：晴，25℃";
        }
    }

    private static String join(List<ChatResponse> responses) {
        return responses.stream()
                .map(r -> r.getResult() != null && r.getResult().getOutput() != null
                        ? r.getResult().getOutput().getText() : "")
                .filter(s -> s != null)
                .collect(java.util.stream.Collectors.joining());
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
