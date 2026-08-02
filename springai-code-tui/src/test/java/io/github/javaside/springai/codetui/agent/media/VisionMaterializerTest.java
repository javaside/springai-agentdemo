package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.content.MediaContent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionMaterializerTest {

    @TempDir Path root;

    private void png(String rel) throws Exception {
        Path p = root.resolve(rel);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        ImageIO.write(new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
    }

    /** 造一个能通过 FileReferenceParser 严格校验的引用块。 */
    private String ref(String name, String rel) {
        return "[file reference]\n"
                + "id: sha256:" + Integer.toHexString(rel.hashCode()) + "\n"
                + "kind: image\n"
                + "mime_type: image/png\n"
                + "size_bytes: 10\n"
                + "name: " + name + "\n"
                + "path: " + rel + "\n"
                + "delivery: not_in_view\n"
                + "reason: x\n"
                + "[/file reference]";
    }

    private ToolResponseMessage toolResult(String body) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "Read", body)))
                .build();
    }

    private AssistantMessage toolCall() {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "Read", "{}")))
                .build();
    }

    private VisionMaterializer materializer() {
        return new VisionMaterializer(root, new ImagePreparer(), new VisionBudget());
    }

    private List<Media> mediaOf(Message m) {
        return ((MediaContent) m).getMedia();
    }

    // ── 工具产图：靠合成 user 消息投递 ──────────────────────────

    @Test
    void toolImageIsDeliveredViaAppendedSyntheticUserMessage() throws Exception {
        png("docs/bug.png");
        Prompt p = new Prompt(List.of(
                new UserMessage("这是什么报错"),
                toolCall(),
                toolResult(ref("bug.png", "docs/bug.png"))));

        List<Message> out = materializer().materialize(p, true).getInstructions();

        assertEquals(4, out.size(), "应追加一条合成消息");
        Message last = out.get(3);
        assertInstanceOf(UserMessage.class, last);
        assertEquals(1, mediaOf(last).size(), "合成消息没带图");
        assertEquals(Boolean.TRUE, last.getMetadata().get(VisionMaterializer.SYNTHETIC_KEY),
                "合成消息必须自证身份");
    }

    /** 工具结果那条一个字都不能改——引用文本是图与路径的绑定。 */
    @Test
    void toolResponseMessageIsLeftByteForByteUnchanged() throws Exception {
        png("docs/bug.png");
        String body = ref("bug.png", "docs/bug.png");
        Prompt p = new Prompt(List.of(new UserMessage("看"), toolCall(), toolResult(body)));

        List<Message> out = materializer().materialize(p, true).getInstructions();

        assertEquals(body,
                ((ToolResponseMessage) out.get(2)).getResponses().get(0).responseData());
    }

    // ── 当轮边界 ────────────────────────────────────────────

    @Test
    void historicalReferencesAreNotMaterialized() throws Exception {
        png("docs/old.png");
        Prompt p = new Prompt(List.of(
                new UserMessage("回合1\n" + ref("old.png", "docs/old.png")),
                new AssistantMessage("好的"),
                new UserMessage("回合2，不带图")));

        List<Message> out = materializer().materialize(p, true).getInstructions();

        assertEquals(3, out.size(), "历史图不该被兑现");
        assertTrue(mediaOf(out.get(0)).isEmpty(), "回合1的图不该挂上来");
    }

    /** ★ 防线：模型可能在自己的回复里照抄引用块格式，不得被兑现。 */
    @Test
    void referencesInsideAssistantMessageAreIgnored() throws Exception {
        png("docs/x.png");
        Prompt p = new Prompt(List.of(
                new UserMessage("hi"),
                new AssistantMessage("我看到了 " + ref("x.png", "docs/x.png"))));

        List<Message> out = materializer().materialize(p, true).getInstructions();

        assertEquals(2, out.size(), "assistant 里复述的引用被当真了");
    }

    /** ★ 防线：合成消息万一漏回列表，锚点判定必须不受影响。 */
    @Test
    void syntheticMessageDoesNotBecomeAnchor() throws Exception {
        png("docs/bug.png");
        UserMessage leaked = UserMessage.builder().text("以下是图片")
                .metadata(Map.of(VisionMaterializer.SYNTHETIC_KEY, true)).build();
        Prompt p = new Prompt(List.of(
                new UserMessage("真实提问"),
                toolCall(),
                toolResult(ref("bug.png", "docs/bug.png")),
                leaked));

        List<Message> out = materializer().materialize(p, true).getInstructions();

        // 锚点仍是 [0]，故 [2] 的工具引用仍在当轮、仍被兑现
        assertEquals(5, out.size(), "合成消息夺走了锚点，当轮的图不再被兑现");
        assertEquals(1, mediaOf(out.get(4)).size());
    }

    // ── 用户贴图：原地补 media + 改写 delivery ──────────────────

    @Test
    void userImageIsAttachedInPlaceAndDeliveryRewritten() throws Exception {
        png("docs/cart.png");
        Prompt p = new Prompt(List.of(
                new UserMessage("照这个改\n" + ref("cart.png", "docs/cart.png"))));

        List<Message> out = materializer().materialize(p, true).getInstructions();

        assertEquals(1, out.size(), "用户贴图应原地补 media，不追加消息");
        assertEquals(1, mediaOf(out.get(0)).size());
        assertTrue(out.get(0).getText().contains("delivery: delivered"),
                "兑现后 delivery 必须改写，否则模型同时收到「你看不见」和那张图");
        assertFalse(out.get(0).getText().contains("delivery: not_in_view"), "旧状态残留");
    }

    // ── 能力闸门 ────────────────────────────────────────────

    @Test
    void noVisionCapabilityMeansSamePromptObject() throws Exception {
        png("docs/bug.png");
        Prompt p = new Prompt(List.of(new UserMessage("看"), toolCall(),
                toolResult(ref("bug.png", "docs/bug.png"))));

        assertSame(p, materializer().materialize(p, false), "无能力时必须原样返回同一对象");
    }

    // ── 配额 ────────────────────────────────────────────────

    /** 工具图只兑现最新一张。 */
    @Test
    void onlyNewestToolImageIsDelivered() throws Exception {
        png("a.png");
        png("b.png");
        Prompt p = new Prompt(List.of(
                new UserMessage("看"),
                toolCall(), toolResult(ref("a.png", "a.png")),
                toolCall(), toolResult(ref("b.png", "b.png"))));

        List<Message> out = materializer().materialize(p, true).getInstructions();

        Message synth = out.get(out.size() - 1);
        assertEquals(1, mediaOf(synth).size(), "工具图配额是 1 张");
        assertTrue(synth.getText().contains("b.png"), "该给最新那张");
        assertFalse(synth.getText().contains("a.png"), "旧的不该给");
    }

    /** ★ 用户当轮贴的图保底，不被工具图挤掉——「照这张稿子改」的稿子必须一直在。 */
    @Test
    void userImagesSurviveEvenWhenToolImagesCompete() throws Exception {
        png("design.png");
        png("t1.png");
        Prompt p = new Prompt(List.of(
                new UserMessage("照这个改\n" + ref("design.png", "design.png")),
                toolCall(), toolResult(ref("t1.png", "t1.png"))));

        List<Message> out = materializer().materialize(p, true).getInstructions();

        assertEquals(1, mediaOf(out.get(0)).size(), "用户的稿子被挤掉了");
        assertTrue(out.get(0).getText().contains("delivery: delivered"));
    }

    /** ★ 回合累计额度用尽后停止兑现。 */
    @Test
    void turnBudgetExhaustionStopsDelivery() throws Exception {
        png("docs/bug.png");
        VisionMaterializer m = materializer();
        Prompt p = new Prompt(List.of(new UserMessage("固定提问"), toolCall(),
                toolResult(ref("bug.png", "docs/bug.png"))));

        for (int i = 0; i < VisionBudget.MAX_TURN_DELIVERIES; i++) {
            assertEquals(4, m.materialize(p, true).getInstructions().size(),
                    "第 " + (i + 1) + " 次应仍在额度内");
        }
        assertEquals(3, m.materialize(p, true).getInstructions().size(),
                "额度用尽后不该再兑现");
    }

    /** 同一请求内同一 sha 不重复兑现——用户贴了图、模型又 Read 了同一张。 */
    @Test
    void sameShaIsNotDeliveredTwiceInOneRequest() throws Exception {
        png("docs/bug.png");
        String r = ref("bug.png", "docs/bug.png");
        Prompt p = new Prompt(List.of(
                new UserMessage("看\n" + r), toolCall(), toolResult(r)));

        List<Message> out = materializer().materialize(p, true).getInstructions();

        int total = out.stream().filter(m -> m instanceof MediaContent)
                .mapToInt(m -> ((MediaContent) m).getMedia().size()).sum();
        assertEquals(1, total, "同一张图发了两份");
    }

    // ── 统计 ────────────────────────────────────────────────

    @Test
    void snapshotReportsDeliveredCount() throws Exception {
        png("docs/bug.png");
        VisionMaterializer m = materializer();
        m.materialize(new Prompt(List.of(new UserMessage("看"), toolCall(),
                toolResult(ref("bug.png", "docs/bug.png")))), true);
        assertEquals(1, m.lastSnapshot().images());
        assertTrue(m.lastSnapshot().tokens() > 0, "视觉 token 应被计入");
    }
}
