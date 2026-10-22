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
import java.util.ArrayList;
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

    /**
     * ★ 回合累计额度用尽后停止兑现。
     *
     * <p>每轮由工具<b>新产出一张图</b>来消耗额度（推送改为"首次推一次"后，同一张图的重复重发
     * 不再发生，额度真正被消耗的路径是「不同图不断出现」与「Read 反复取图」）。
     */
    @Test
    void turnBudgetExhaustionStopsDelivery() throws Exception {
        png("docs/bug.png");
        VisionMaterializer m = materializer();
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage("固定提问"));

        for (int i = 0; i < VisionBudget.MAX_TOOL_TURN_DELIVERIES; i++) {
            msgs.add(toolResult(ref("bug.png", "docs/bug.png")));
            assertEquals(msgs.size() + 1, m.materialize(new Prompt(new ArrayList<>(msgs)), true)
                            .getInstructions().size(),
                    "第 " + (i + 1) + " 次应仍在额度内");
        }
        msgs.add(toolResult(ref("bug.png", "docs/bug.png")));
        assertEquals(msgs.size(), m.materialize(new Prompt(new ArrayList<>(msgs)), true)
                        .getInstructions().size(),
                "额度用尽后不该再兑现（不该追加合成消息）");
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
        // 12 张 > 上限 10：必须超过配额才能测出超额路径（上限放宽到 10 后 4 张已不够用）
        StringBuilder text = new StringBuilder("十二张图");
        for (char c = 'a'; c <= 'l'; c++) {
            String name = c + ".png";
            png(name);
            text.append('\n').append(ref(name, name));
        }
        Prompt p = new Prompt(List.of(new UserMessage(text.toString())));

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
     *
     * <p>用<b>小上限</b>的预算实例来构造该局面：默认上限 32000 高于「10 用户图 + 1 工具图」
     * 的真实成本（约 10–33k，见 {@code VisionBudgetTest#defaultTokenCapAdmitsFullQuotaOfWorstCaseImages}），
     * 正常路径根本触发不到这个分支。
     */
    @Test
    void tokenBudgetOverflowIsMarkedBudgetExceeded() throws Exception {
        bigPng("big1.png"); bigPng("big2.png");
        String text = "两张大图\n" + ref("big1.png", "big1.png") + "\n" + ref("big2.png", "big2.png");
        Prompt p = new Prompt(List.of(new UserMessage(text)));

        // 单张约 3136 token（1568×1500/750，CONSERVATIVE 档不缩放）→ 上限 4000 只放得下一张
        VisionMaterializer m = new VisionMaterializer(root, new ImagePreparer(),
                new VisionBudget(4_000L, VisionBudget.MAX_TOOL_TURN_DELIVERIES,
                        VisionBudget.MAX_USER_TURN_DELIVERIES));
        String out = m.materialize(p, true).getInstructions().get(0).getText();

        assertEquals(1, countOccurrences(out, "delivery: " + FileReference.DELIVERY_DELIVERED),
                "token 预算只容得下一张：\n" + out);
        assertEquals(1, countOccurrences(out, "delivery: " + FileReference.DELIVERY_BUDGET_EXCEEDED),
                "被 token 预算挤掉的那张必须标注：\n" + out);
    }

    /**
     * 用户图<b>不</b>参与工具图回合额度：把工具图额度跑满后，用户图的首推不受影响。
     *
     * <p>这条钉住「用户图有独立额度」——共享一个计数器时，工具循环会把用户图挤掉。
     */
    @Test
    void userImagesAreNotChargedAgainstToolTurnBudget() throws Exception {
        png("x.png");
        png("tool.png");
        VisionMaterializer m = materializer();
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage("固定提问\n" + ref("x.png", "x.png")));

        // 先用工具图把「工具图」额度跑满
        for (int i = 0; i < VisionBudget.MAX_TOOL_TURN_DELIVERIES; i++) {
            msgs.add(toolResult(ref("tool.png", "tool.png")));
            m.materialize(new Prompt(new ArrayList<>(msgs)), true);
        }

        // 新回合：用户贴图，应照常首推（不受工具图额度影响）
        VisionMaterializer fresh = materializer();
        Prompt p = new Prompt(List.of(new UserMessage("新提问\n" + ref("x.png", "x.png"))));
        String out = fresh.materialize(p, true).getInstructions().get(0).getText();

        assertTrue(out.contains("delivery: " + FileReference.DELIVERY_DELIVERED),
                "用户图应照常投递：\n" + out);
        assertFalse(out.contains("delivery: " + FileReference.DELIVERY_TURN_EXHAUSTED),
                "用户图不该被工具图额度标记为耗尽：\n" + out);
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

    /**
     * 造一个与 {@link #ANCHOR} 同回合的请求：turnKey = 锚点文本 hash + 锚点下标，
     * 故这里的锚点文本与下标（恒为 0）必须<b>逐字相同</b>，否则下面几条测的就成了「换了回合」，
     * 什么都验不到。工具结果的内容不参与 turnKey，可以随便换。
     */
    private static final String ANCHOR = "同一个回合的提问";

    private Prompt sameTurn(String toolBody) {
        return new Prompt(List.of(new UserMessage(ANCHOR), toolCall(), toolResult(toolBody)));
    }

    /** 统计按<b>回合</b>累计：同一回合内第二次迭代又兑现一张，快照该是 2 张而不是覆盖成 1 张。 */
    @Test
    void snapshotAccumulatesAcrossIterationsOfSameTurn() throws Exception {
        png("a.png");
        png("b.png");
        VisionMaterializer m = materializer();

        // 真实工具循环是「在同一回合的消息列表上不断追加」——同一对象复用不构成新轮次。
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage(ANCHOR));
        msgs.add(toolCall());
        msgs.add(toolResult(ref("a.png", "a.png")));
        m.materialize(new Prompt(new ArrayList<>(msgs)), true);
        long afterFirst = m.lastSnapshot().tokens();
        assertEquals(1, m.lastSnapshot().images(), "前置条件：第一次迭代应兑现 1 张");

        msgs.add(toolResult(ref("b.png", "b.png")));   // 追加一张新图
        m.materialize(new Prompt(new ArrayList<>(msgs)), true);

        assertEquals(2, m.lastSnapshot().images(),
                "同一回合的两次兑现该累计成 2 张，被覆盖成「上一次请求」了");
        // 两张图尺寸一致（png() 都是 100×80，token 只按尺寸算）→ 累计值恰好翻倍。
        // 只断言「变大了」抓不到「张数累加、token 忘了累加」。
        assertEquals(afterFirst * 2, m.lastSnapshot().tokens(), "token 没跟着累计");
    }

    /**
     * ★ 核心：同一回合内一次「兑现 0 张」的请求<b>不得</b>清零。
     *
     * <p>这正是线上那个 bug：一个回合有几十次工具迭代，回合额度用尽后每次都兑现 0；
     * 回合一结束引用落进历史，按「当轮兑现」规则更不会再兑现。于是用户按 {@code /context}
     * 那一刻读到的「上一次请求」几乎必然是 0——这个数字在实践中恒为零，等于没写。
     */
    @Test
    void snapshotSurvivesARequestThatDeliversNothingInSameTurn() throws Exception {
        png("a.png");
        VisionMaterializer m = materializer();

        m.materialize(sameTurn(ref("a.png", "a.png")), true);
        long tokens = m.lastSnapshot().tokens();
        assertEquals(1, m.lastSnapshot().images(), "前置条件：第一次应兑现 1 张");
        assertTrue(tokens > 0, "前置条件：第一次应记上 token");

        // 同一条锚点消息、同一个下标 → turnKey 不变；工具结果里没有任何引用块 → 本次兑现 0 张。
        m.materialize(sameTurn("这次工具只回了文本，没有任何图片引用"), true);

        assertEquals(1, m.lastSnapshot().images(),
                "同回合内一次没兑现就把账清零——/context 因此恒显示 0，等于这个统计没写");
        assertEquals(tokens, m.lastSnapshot().tokens(), "token 也不该被清零");
    }

    /** 换了回合（锚点消息不同 → turnKey 不同）→ 归零重算，不能把上一轮的账带过来。 */
    @Test
    void snapshotRestartsWhenTurnChanges() throws Exception {
        png("a.png");
        png("b.png");
        VisionMaterializer m = materializer();

        m.materialize(new Prompt(List.of(new UserMessage("回合一"), toolCall(),
                toolResult(ref("a.png", "a.png")))), true);
        long firstTurnTokens = m.lastSnapshot().tokens();
        assertEquals(1, m.lastSnapshot().images(), "前置条件：回合一应兑现 1 张");

        m.materialize(new Prompt(List.of(new UserMessage("回合二"), toolCall(),
                toolResult(ref("b.png", "b.png")))), true);

        assertEquals(1, m.lastSnapshot().images(),
                "新回合该从头算，把上一轮的张数带过来了");
        assertEquals(firstTurnTokens, m.lastSnapshot().tokens(),
                "新回合的 token 该只有本轮这一张（两张图尺寸相同故与上轮等值），带过来就会翻倍");
    }

    /**
     * 回合额度用尽后统计必须<b>停住</b>——张数和 token 都不再涨。
     *
     * <p>token 只能计<b>真发出去</b>的那几张：{@code session.admit} 是先记账、随后才可能被
     * {@code tryConsumeTurnSlot} 挡下。按请求报时这点误差看不见；改成按回合累计后，额度用尽的
     * 那几十次迭代会次次记上一笔，张数不涨而 token 一直涨，面板直接变成胡说。
     */
    @Test
    void snapshotStopsGrowingOnceTurnBudgetIsExhausted() throws Exception {
        png("x.png");
        VisionMaterializer m = materializer();
        // 工具循环在同一回合的消息列表上不断追加（锚点文本与下标恒定 → turnKey 恒定）
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage(ANCHOR));

        // 用「工具图」跑额度，用户锚点不带引用——工具图额度与用户图独立
        for (int i = 0; i < VisionBudget.MAX_TOOL_TURN_DELIVERIES; i++) {
            msgs.add(toolResult(ref("x.png", "x.png")));
            m.materialize(new Prompt(new ArrayList<>(msgs)), true);
        }
        int images = m.lastSnapshot().images();
        long tokens = m.lastSnapshot().tokens();
        assertEquals(VisionBudget.MAX_TOOL_TURN_DELIVERIES, images, "前置条件：应恰好累计到回合上限");
        assertTrue(tokens > 0, "前置条件：应记上 token");

        msgs.add(toolResult(ref("x.png", "x.png")));
        m.materialize(new Prompt(new ArrayList<>(msgs)), true);   // 额度已尽：这次兑现 0 张

        assertEquals(images, m.lastSnapshot().images(), "额度用尽后张数还在涨");
        assertEquals(tokens, m.lastSnapshot().tokens(),
                "额度用尽后 token 还在涨——把「过闸尝试」当成了「真发出去」");
    }

    // ── 服务端拒收后的拒绝记忆（send_failed）─────────────────────

    /**
     * ★ 图片被服务端 400 拒收（实测：智谱「图片输入格式/解析错误」）后，同一张图
     * <b>重发也会再次被拒</b>。{@code rejectLastDelivered()} 必须把它记进拒绝集：
     * 再次物化时不再出图，引用块如实标注 {@code send_failed}——否则模型重新 Read
     * 同一张图就再次兑现、再次 400，回合死循环（2026-10-22 会话 b524ef 实录）。
     */
    @Test
    void rejectedUserImageIsSkippedWithSendFailedDelivery() throws Exception {
        png("docs/bug.png");
        VisionMaterializer m = materializer();
        String r = ref("bug.png", "docs/bug.png");
        Prompt p = new Prompt(List.of(new UserMessage("看\n" + r), toolCall(), toolResult(r)));

        Prompt first = m.materialize(p, true);
        assertEquals(1, countOccurrences(first.getInstructions().get(0).getText(),
                "delivery: " + FileReference.DELIVERY_DELIVERED), "前置条件：首次应兑现");

        m.rejectLastDelivered();
        List<Message> out = m.materialize(p, true).getInstructions();

        assertEquals(3, out.size(), "被拒的图不该再追加合成消息");
        assertTrue(mediaOf(out.get(0)).isEmpty(), "被拒的图不该再挂 media");
        assertTrue(out.get(0).getText().contains("delivery: " + FileReference.DELIVERY_SEND_FAILED),
                "被拒的图必须如实标注 send_failed，否则模型会再 Read 再撞 400：\n"
                        + out.get(0).getText());
        assertFalse(out.get(0).getText().contains("delivery: " + FileReference.DELIVERY_DELIVERED),
                "被拒后不应残留 delivered");
    }

    /** 拒绝只作用于被拒的那张：同回合后续新出现的工具图照常兑现。 */
    @Test
    void rejectionDoesNotBlockOtherToolImages() throws Exception {
        png("a.png");
        png("b.png");
        VisionMaterializer m = materializer();

        m.materialize(new Prompt(List.of(new UserMessage(ANCHOR), toolCall(),
                toolResult(ref("a.png", "a.png")))), true);
        m.rejectLastDelivered();

        // 同回合（锚点不变）追加一张<b>新</b>图：b 与 a 无关，必须照常出图
        List<Message> out = m.materialize(new Prompt(List.of(new UserMessage(ANCHOR), toolCall(),
                toolResult(ref("a.png", "a.png")), toolCall(), toolResult(ref("b.png", "b.png")))),
                true).getInstructions();

        Message synth = out.get(out.size() - 1);
        assertEquals(1, mediaOf(synth).size(), "未被拒的 b 被一起拒了");
        assertTrue(synth.getText().contains("b.png"), "合成消息该列的是 b");
        assertFalse(synth.getText().contains("a.png"), "被拒的 a 不该混进合成消息");
    }

    /** 拒绝集跨回合有效：用户下个回合把同一张图重新贴进来，照样 send_failed。 */
    @Test
    void rejectionSurvivesAcrossTurns() throws Exception {
        png("a.png");
        VisionMaterializer m = materializer();
        m.materialize(new Prompt(List.of(new UserMessage("回合一"), toolCall(),
                toolResult(ref("a.png", "a.png")))), true);
        m.rejectLastDelivered();

        String out = m.materialize(new Prompt(List.of(
                new UserMessage("回合二重新贴\n" + ref("a.png", "a.png")))), true)
                .getInstructions().get(0).getText();

        assertTrue(out.contains("delivery: " + FileReference.DELIVERY_SEND_FAILED),
                "跨回合后同一张图仍该标注 send_failed：\n" + out);
        assertFalse(out.contains("delivery: " + FileReference.DELIVERY_DELIVERED));
    }

}
