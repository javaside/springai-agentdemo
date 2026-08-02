package io.github.javaside.springai.codetui.agent.media;

import io.github.javaside.springai.codetui.agent.OpenAiProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真机<b>端到端</b>探针：走完整条出站链路，验证「工具返回的图」确实到达了模型。
 *
 * <p><b>与 {@link LiveVisionSequenceProbe} 的区别</b>：那个探针手搓 {@code Prompt}、
 * 自己把 {@code Media} 挂上去，证明的是「这个消息序列各家 API 收不收」。它<b>绕过了</b>
 * 装饰器与兑现器——也就是说，即便 {@code VisionMaterializer} 完全没接线，那个探针照样绿。
 *
 * <p>本探针补上那一段：真实引用块（{@link FileReference#render} 产出，与线上一字不差）
 * 放进 {@link ToolResponseMessage}，经 {@link VisionMaterializingChatModel} 出站，
 * 断言模型说出了<b>只有看见图才知道</b>的信息。
 *
 * <p><b>为什么用颜色判别而不是「回复非空」</b>：非空只证明 HTTP 没挂。Task 0 的对照实验
 * 已经证明——不挂 {@code Media} 时模型会明确回答「只看到图片文件引用，无法看到实际图像内容」，
 * 那也是一段非空文本。所以断言必须落在图像内容上。
 *
 * <p>默认跳过（无 key 即不跑），不进 CI。这不是回归测试，是一次性的链路可行性验证。
 */
class LiveVisionEndToEndProbe {

    /** 造一张纯色 PNG 落到 root 下，返回它的相对路径。尺寸够大以确保走缩图分支。 */
    private static Path paintPng(Path root, String rel, Color color, int w, int h) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent() == null ? root : p.getParent());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    /** 用线上同一段代码渲染引用块——不手写，避免探针与真实格式漂移。 */
    private static String realReferenceBlock(Path root, Path file, String displayName) throws Exception {
        byte[] head = Files.readAllBytes(file);
        MagicSniffer.Sniffed sniffed = MagicSniffer.sniff(head);
        var dim = ImageDimensions.of(head);
        MediaArtifact a = new MediaArtifact(
                "e2e" + "0".repeat(61), file, root.relativize(file).toString(),
                sniffed.mimeType(), null, sniffed.kind(), Files.size(file),
                dim.map(d -> d[0]).orElse(null), dim.map(d -> d[1]).orElse(null), null,
                ArtifactSource.MATERIALIZED, true, displayName);
        return new TextReferenceMediaHandler()
                .represent(a, new ModelCapabilities(true, false));
    }

    /** 造一轮「模型刚调完截图工具」的消息序列——形状与线上工具循环里那一刻完全一致。 */
    private static Prompt afterScreenshotTool(String question, String referenceBlock, String modelId) {
        var call = new AssistantMessage.ToolCall(
                "call_shot", "function", "mcp__chrome_devtools__take_screenshot", "{}");
        List<Message> msgs = List.of(
                new SystemMessage("你是测试助手。只回答看到的内容，不要解释推理过程。"),
                new UserMessage(question),
                AssistantMessage.builder().content("").toolCalls(List.of(call)).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call_shot", "mcp__chrome_devtools__take_screenshot", referenceBlock)))
                        .build());
        return new Prompt(msgs, OpenAiChatOptions.builder().model(modelId).build());
    }

    private static String ask(ChatModel decorated, Prompt p) {
        ChatResponse r = decorated.call(p);
        return r.getResult().getOutput().getText();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void toolProducedImageReachesTheModel(@TempDir Path root) throws Exception {
        OpenAiProvider provider = new OpenAiProvider(
                System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_BASE_URL"), null);
        String modelId = provider.defaultModel();
        ChatModel decorated = VisionMaterializingChatModel.wrap(provider.chatModel(), root);

        // 三种颜色轮着来：单色蒙对的概率不低，三次全对才算数。
        Object[][] cases = {
                {Color.RED, "红", "red"},
                {Color.GREEN, "绿", "green"},
                {Color.BLUE, "蓝", "blue"},
        };
        for (int i = 0; i < cases.length; i++) {
            Color c = (Color) cases[i][0];
            Path png = paintPng(root, "shot" + i + ".png", c, 1000, 1688);   // 长边 >1568，走缩图
            String block = realReferenceBlock(root, png, "screenshot-" + i + ".png");
            String reply = ask(decorated,
                    afterScreenshotTool("截图里页面是什么颜色？只答颜色。", block, modelId));

            System.out.println("[e2e] " + cases[i][1] + " → " + reply);
            String lower = reply.toLowerCase(Locale.ROOT);
            assertTrue(lower.contains((String) cases[i][1]) || lower.contains((String) cases[i][2]),
                    "模型没认出颜色，说明图没到它那里。回复：" + reply);
        }
    }

    /**
     * 对照组：<b>同一段代码、同一个引用块</b>，只把模型换成纯文本模型（兑现被能力闸门挡下）。
     *
     * <p>没有这一组，上面那个测试证明不了因果——万一模型是从文件名
     * {@code screenshot-0.png} 猜出来的呢？这里的文件名同样含颜色线索，
     * 若它仍答不出颜色，就说明上面答对靠的是像素而非文本线索。
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void withoutVisionCapabilityTheModelCannotSeeIt(@TempDir Path root) throws Exception {
        OpenAiProvider provider = new OpenAiProvider(
                System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_BASE_URL"), null);
        String visionModel = provider.defaultModel();
        ChatModel decorated = VisionMaterializingChatModel.wrap(provider.chatModel(), root);

        Path png = paintPng(root, "purple-page.png", new Color(128, 0, 128), 1000, 1688);
        String block = realReferenceBlock(root, png, "purple-page.png");

        // 关键：options 里写一个「名单外」的模型 id，兑现会被 VisionModels 挡下；
        // 但请求仍发往同一个可用模型（网关按 model 字段路由，这里刻意用别名试探）。
        // 若网关拒绝该 id，本用例会抛异常——那属于环境问题，不影响主用例结论。
        Prompt p = afterScreenshotTool("截图里页面是什么颜色？只答颜色。", block, visionModel);
        Prompt blocked = new Prompt(p.getInstructions(),
                OpenAiChatOptions.builder().model("deepseek-chat").build());

        String reply;
        try {
            reply = ask(decorated, blocked);
        } catch (RuntimeException e) {
            System.out.println("[e2e] 对照组跳过（网关不认该模型 id）：" + e.getMessage());
            return;
        }
        System.out.println("[e2e] 对照组（无视觉能力）→ " + reply);
        assertFalse(reply.contains("紫"), "无视觉能力却答出了颜色，说明上面的实验不成立：" + reply);
    }
}
