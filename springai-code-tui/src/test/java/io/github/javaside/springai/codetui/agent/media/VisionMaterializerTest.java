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

    /** 长边正好卡在 MAX_EDGE 上：不触发缩放，token 就按这个尺寸算，方便精确算预算。 */
    private void bigPng(String rel) throws Exception {
        Path p = root.resolve(rel);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        ImageIO.write(new BufferedImage(ImagePreparer.MAX_EDGE, 1500, BufferedImage.TYPE_INT_RGB),
                "png", p.toFile());
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

    // ── 被预算跳过的引用要写对 delivery ─────────────────────────

    /**
     * 用户当轮贴了超过配额的图：超出的那些 delivery 必须写成 budget_exceeded。
     *
     * <p>留成 not_in_view 是在说谎——那句话的意思是「Read 一次就能看」，而这张图
     * Read 回来会再次撞上同一个预算、再次被跳过，模型白白空转一轮还花钱。
     * 五态设计的初衷就是消灭这种空转。
     */
    @Test
    void userImagesBeyondQuotaAreMarkedBudgetExceeded() throws Exception {
        png("a.png"); png("b.png"); png("c.png"); png("d.png");
        String text = "四张图\n" + ref("a.png", "a.png") + "\n" + ref("b.png", "b.png")
                + "\n" + ref("c.png", "c.png") + "\n" + ref("d.png", "d.png");
        Prompt p = new Prompt(List.of(new UserMessage(text)));

        String out = materializer().materialize(p, true).getInstructions().get(0).getText();

        assertEquals(VisionBudget.MAX_USER_IMAGES,
                countOccurrences(out, "delivery: " + FileReference.DELIVERY_DELIVERED),
                "应恰好兑现配额上限张数");
        assertTrue(out.contains("delivery: " + FileReference.DELIVERY_BUDGET_EXCEEDED),
                "被配额挤掉的那张仍是 not_in_view，等于骗模型再 Read 一次：\n" + out);
        assertFalse(out.contains("delivery: " + FileReference.DELIVERY_NOT_IN_VIEW),
                "不该再有 not_in_view 残留：\n" + out);
    }

    /**
     * ★ 张数配额（上面那条）和 token 预算是两条<b>独立</b>的跳过路径：
     * 上面那条 4 张小图从没碰过 {@code session.admit}，只在张数上溢出；
     * 这条两张大图没超张数配额，是第二张的 token 越了每请求上限。
     * 少了这条，{@code admit} 返回 false 那个分支的标注就是无人验证的。
     */
    @Test
    void tokenBudgetOverflowIsMarkedBudgetExceeded() throws Exception {
        // 长边不超过 MAX_EDGE 故不缩放，token 按原尺寸算：1568×1500/750 ≈ 3136，两张即超 6000。
        bigPng("big1.png"); bigPng("big2.png");
        String text = "两张大图\n" + ref("big1.png", "big1.png") + "\n" + ref("big2.png", "big2.png");
        Prompt p = new Prompt(List.of(new UserMessage(text)));

        String out = materializer().materialize(p, true).getInstructions().get(0).getText();

        assertEquals(1, countOccurrences(out, "delivery: " + FileReference.DELIVERY_DELIVERED),
                "token 预算只容得下一张：\n" + out);
        assertEquals(1, countOccurrences(out, "delivery: " + FileReference.DELIVERY_BUDGET_EXCEEDED),
                "被 token 预算挤掉的那张必须标注：\n" + out);
    }

    /** 回合累计额度用尽后，当轮引用要写 turn_budget_exhausted——语义与「被本请求配额挤掉」不同：
     *  前者本回合怎么 Read 都没用，后者换一轮少贴几张就行。 */
    @Test
    void turnBudgetExhaustionIsMarkedDistinctly() throws Exception {
        png("x.png");
        VisionMaterializer m = materializer();
        Prompt p = new Prompt(List.of(new UserMessage("固定提问\n" + ref("x.png", "x.png"))));

        for (int i = 0; i < VisionBudget.MAX_TURN_DELIVERIES; i++) {
            m.materialize(p, true);
        }
        String out = m.materialize(p, true).getInstructions().get(0).getText();

        assertTrue(out.contains("delivery: " + FileReference.DELIVERY_TURN_EXHAUSTED),
                "额度用尽后应写 turn_budget_exhausted，而不是让模型以为 Read 一次就能看：\n" + out);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
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
