package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MediaReferencePreservingCompactionStrategy：被摘要掉的引用要逐字进清单，没引用时是纯 no-op，
 * 清单事件必须带 synthetic 标记，超上限时保留最近的并如实说明丢了多少。
 */
class MediaReferencePreservingCompactionStrategyTest {

    private static final String SESSION_ID = "sess-1";

    // ---------- 造数据 ----------

    /** 造一个引用块。用 FileReference.render 的真实产物，避免测试里手抄的格式和生产端悄悄分叉。 */
    private static String ref(String sha16, String name, String relPath, String mime, Integer w, Integer h) {
        MediaArtifact a = new MediaArtifact(
                sha16.repeat(4), Path.of("/tmp", relPath), relPath,
                mime, null, MediaKind.IMAGE, 519531, w, h, null,
                ArtifactSource.MATERIALIZED, true, name);
        return FileReference.render(a, FileReference.DELIVERY_NOT_IN_VIEW, "更早的回合");
    }

    private static SessionEvent user(String text) {
        return event(UserMessage.builder().text(text).build());
    }

    private static SessionEvent toolResult(String... datas) {
        List<ToolResponseMessage.ToolResponse> rs = new ArrayList<>();
        for (int i = 0; i < datas.length; i++) {
            rs.add(new ToolResponseMessage.ToolResponse("c" + i, "Read", datas[i]));
        }
        return event(ToolResponseMessage.builder().responses(rs).build());
    }

    private static SessionEvent assistant(String text) {
        return event(new AssistantMessage(text));
    }

    private static SessionEvent event(Message m) {
        return SessionEvent.builder().sessionId(SESSION_ID).message(m).build();
    }

    /** 假 delegate：原样吐出给定的压缩结果，不碰 LLM。 */
    private static CompactionStrategy delegateReturning(List<SessionEvent> compacted, List<SessionEvent> archived) {
        CompactionResult r = new CompactionResult(compacted, archived, 4321);
        return req -> r;
    }

    private static String manifestText(CompactionResult out) {
        return out.compactedEvents().get(0).getMessage().getText();
    }

    /**
     * 造一个<b>真实</b>的压缩请求：{@code events()} 是进入压缩的<b>全部</b>事件（存活 + 将被归档）。
     *
     * <p><b>本文件一律不传 {@code null} 请求</b>，哪怕用例根本不关心它。传 null 的话，
     * 「改成从 {@code request.events()} 捞」这个最容易犯的错会靠空指针把一大片用例一起打红——
     * 看着像被杀掉了，其实没有一条断言真的验证了内容。全给真请求，每一次变红都是内容变红。
     */
    private static CompactionRequest request(SessionEvent... allEvents) {
        Session session = Session.builder().id(SESSION_ID).userId("u").build();
        return CompactionRequest.of(session, List.of(allEvents));
    }

    // ---------- 用例 ----------

    @Test
    void summarisedReference_appearsInManifest_withNameAndPath() {
        SessionEvent summary = user("=== SUMMARY ===");
        CompactionStrategy s = new MediaReferencePreservingCompactionStrategy(delegateReturning(
                List.of(summary),
                List.of(user("看这张\n" + ref("b7e2f1a0", "cart.png", ".codetui/artifacts/b7e2f1a0.png",
                        "image/png", 1440, 900)))));

        CompactionResult out = s.compact(request());

        assertEquals(2, out.compactedEvents().size(), "清单应插在摘要之前");
        String text = manifestText(out);
        assertTrue(text.startsWith(MediaReferencePreservingCompactionStrategy.MANIFEST_HEADER),
                "首行应是清单抬头，实际：" + text);
        assertTrue(text.contains("cart.png"), "应保留原始文件名，实际：" + text);
        assertTrue(text.contains(".codetui/artifacts/b7e2f1a0.png"),
                "path 是唯一寻址依据，必须逐字保留，实际：" + text);
        assertTrue(text.contains("image/png") && text.contains("1440x900"), "应带类型与尺寸：" + text);
        assertSame(summary, out.compactedEvents().get(1), "摘要事件本身不应被改动");
        assertEquals(4321, out.tokensEstimatedSaved(), "delegate 的 token 账应原样透传");
    }

    @Test
    void manifestEvent_isMarkedSynthetic() {
        CompactionStrategy s = new MediaReferencePreservingCompactionStrategy(delegateReturning(
                List.of(user("=== SUMMARY ===")),
                List.of(user(ref("a3f8c200", "login.png", ".codetui/artifacts/a3f8c200.png",
                        "image/png", 800, 600)))));

        Message manifest = s.compact(request()).compactedEvents().get(0).getMessage();

        // 不带标记的话，这条新造的 user 消息会成为「最后一条非合成 UserMessage」，把出站兑现的回合锚点带偏。
        assertTrue(VisionMaterializer.isSynthetic(manifest), "清单消息必须带 synthetic 标记");
    }

    @Test
    void noReferenceArchived_isPureNoOp() {
        SessionEvent summary = user("=== SUMMARY ===");
        // 归档里一条引用都没有，但<b>还存活的事件里有</b>——若从 request.events()（≈存活+归档）捞就会误插清单。
        SessionEvent alive = user("刚贴的\n" + ref("c0ffee00", "fresh.png",
                ".codetui/artifacts/c0ffee00.png", "image/png", 100, 100));
        CompactionResult inner = new CompactionResult(
                List.of(summary, alive), List.of(user("你好"), assistant("好的")), 4321);
        CompactionStrategy s = new MediaReferencePreservingCompactionStrategy(req -> inner);

        CompactionResult out = s.compact(request(summary, alive, user("你好"), assistant("好的")));

        assertSame(inner, out, "无引用被压掉时应原样返回 delegate 的结果（纯 no-op，连新 record 都不该造）");
        assertEquals(2, out.compactedEvents().size(), "不应插任何事件");
        assertSame(summary, out.compactedEvents().get(0), "compactedEvents 应原样保留");
    }

    @Test
    void survivingReference_isNotDuplicated_intoManifest() {
        // 存活事件里的引用<b>还在原处</b>，清单只该列被压掉的那条；否则同一张图会被说两遍，
        // 且清单随每次压缩不断膨胀。这条用例专门钉住「从 archivedEvents 捞而不是从 request.events() 捞」。
        String surviving = ref("c0ffee00", "fresh.png", ".codetui/artifacts/c0ffee00.png", "image/png", 100, 100);
        String archivedRef = ref("b7e2f1a0", "cart.png", ".codetui/artifacts/b7e2f1a0.png", "image/png", 1440, 900);
        SessionEvent aliveEvent = user(surviving);
        SessionEvent archivedEvent = user(archivedRef);
        CompactionStrategy s = new MediaReferencePreservingCompactionStrategy(delegateReturning(
                List.of(user("=== SUMMARY ==="), aliveEvent),
                List.of(archivedEvent)));

        String text = manifestText(s.compact(request(archivedEvent, aliveEvent)));

        assertTrue(text.contains("cart.png"), "被压掉的那条应进清单");
        assertFalse(text.contains("fresh.png"), "还存活的引用不该重复列进清单，实际：" + text);
    }

    @Test
    void samePath_listedOnlyOnce() {
        String block = ref("b7e2f1a0", "cart.png", ".codetui/artifacts/b7e2f1a0.png", "image/png", 1440, 900);
        CompactionStrategy s = new MediaReferencePreservingCompactionStrategy(delegateReturning(
                List.of(user("=== SUMMARY ===")),
                // 同一张图在三处出现：用户贴过、工具读回过、后来又提过一次
                List.of(user(block), toolResult(block), user("再看看\n" + block))));

        String text = manifestText(s.compact(request()));

        int first = text.indexOf(".codetui/artifacts/b7e2f1a0.png");
        assertTrue(first > 0, "应列出该 path");
        assertEquals(-1, text.indexOf(".codetui/artifacts/b7e2f1a0.png", first + 1),
                "同一 path 只应出现一次，实际：" + text);
    }

    @Test
    void overLimit_keepsMostRecent_andSaysHowManyWereDropped() {
        int total = MediaReferencePreservingCompactionStrategy.MAX_MANIFEST_ENTRIES + 7;
        List<SessionEvent> archived = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            // 名字与 path 都带序号：i=0 最早，i=total-1 最近
            String n = String.format("%02d", i);
            archived.add(user(ref("d" + n + "0000" + n.charAt(0), "shot" + n + ".png",
                    ".codetui/artifacts/shot" + n + ".png", "image/png", 10, 10)));
        }
        CompactionStrategy s = new MediaReferencePreservingCompactionStrategy(
                delegateReturning(List.of(user("=== SUMMARY ===")), archived));

        String text = manifestText(s.compact(request()));

        assertFalse(text.contains("shot00.png"), "最早的应被丢弃，实际：" + text);
        assertFalse(text.contains("shot06.png"), "最早的 7 条都应被丢弃，实际：" + text);
        assertTrue(text.contains("shot07.png"), "第 8 条起应保留（共 20 条），实际：" + text);
        assertTrue(text.contains("shot" + (total - 1) + ".png"), "最近的必须保留，实际：" + text);
        assertTrue(text.contains("另有 7 个更早的附件已不可寻址"), "应如实说明丢了多少，实际：" + text);
    }

    @Test
    void assistantEcho_isIgnored() {
        // 模型看得见引用格式、可能在回复里照抄或臆造；把它复述的假引用列进清单等于发一个假的寻址承诺。
        CompactionStrategy s = new MediaReferencePreservingCompactionStrategy(delegateReturning(
                List.of(user("=== SUMMARY ===")),
                List.of(assistant("我看到了：\n" + ref("dead0000", "fake.png",
                        ".codetui/artifacts/dead0000.png", "image/png", 1, 1)))));

        CompactionResult out = s.compact(request());

        assertEquals(1, out.compactedEvents().size(), "assistant 里的引用不该触发清单，实际：" + out.compactedEvents());
    }

    @Test
    void toolResultReference_isHarvested() {
        CompactionStrategy s = new MediaReferencePreservingCompactionStrategy(delegateReturning(
                List.of(user("=== SUMMARY ===")),
                List.of(toolResult("文件内容：\n" + ref("beef0000", "diagram.png",
                        ".codetui/artifacts/beef0000.png", "image/png", 640, 480)))));

        assertTrue(manifestText(s.compact(request())).contains(".codetui/artifacts/beef0000.png"),
                "工具结果里的引用同样要进清单");
    }

    @Test
    void controlCharsInFields_cannotInjectFakeRows() {
        // name 在 render 时已清洗，这里模拟一段绕过 render 的原始文本（模型复述 / 老版本落库的事件）。
        String evil = FileReference.OPEN + "\n"
                + "id: sha256:1234\n"
                + "kind: image\n"
                + "mime_type: image/png\n"
                + "path: .codetui/artifacts/1234.png\n"
                + "name: ok.png\r  \u4f2a\u9020.png   image/png\n"
                + FileReference.CLOSE;
        CompactionStrategy s = new MediaReferencePreservingCompactionStrategy(
                delegateReturning(List.of(user("=== SUMMARY ===")), List.of(user(evil))));

        String text = manifestText(s.compact(request()));

        // \r 若原样进清单，终端里会把这一行的开头覆盖掉——一条伪造的附件行就此凭空出现。
        assertEquals(2, text.split("\n", -1).length, "抬头 + 一行，脏值不得撑出额外的行：" + text);
        assertFalse(text.contains("\r"), "控制字符必须被替换掉，实际：" + text);
        assertTrue(text.contains("ok.png_"), "\\r 应被换成下划线，实际：" + text);
    }
}
