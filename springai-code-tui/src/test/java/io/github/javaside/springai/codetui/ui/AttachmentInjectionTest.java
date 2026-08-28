package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.media.FileReference;
import io.github.javaside.springai.codetui.agent.media.FileReferenceParser;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.VisionModels;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentInjectionTest {

    @TempDir Path root;
    @TempDir Path outside;

    private Path png(Path dir, String rel) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent() == null ? dir : p.getParent());
        ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
        return p;
    }

    /** 正文在前、引用块在后——模型先读到用户在问什么。 */
    @Test
    void referenceBlockIsAppendedAfterUserText() throws Exception {
        Path p = png(root, "docs/bug.png");
        String out = CodeTuiView.injectAttachments("看下这个报错",
                List.of(new DetectedImage(p, "bug.png", 64, 48, true)), root);
        assertTrue(out.startsWith("看下这个报错"), out);
        assertTrue(out.contains(FileReference.OPEN), "没有引用块");
        assertTrue(out.contains("name: bug.png"), out);
    }

    /** 项目内的图指原路径、不复制——你更新了 design.png，模型该看到新版。 */
    @Test
    void insideRootImagePointsAtOriginalFileNotACopy() throws Exception {
        Path p = png(root, "docs/design.png");
        String out = CodeTuiView.injectAttachments("照这个改",
                List.of(new DetectedImage(p, "design.png", 64, 48, true)), root);
        assertTrue(out.contains("path: docs/design.png"),
                "项目内的图被复制成 artifact 了，模型将永远看到旧版：\n" + out);
    }

    /**
     * ★ 项目外的图必须复制进 artifacts：按原路径写进引用块会被 FileReferenceParser 的
     * 越界防线整块丢弃，且没有任何报错——图静默消失。
     */
    @Test
    void outsideRootImageMustBeCopiedIntoArtifacts() throws Exception {
        Path p = png(outside, "shot.png");
        String out = CodeTuiView.injectAttachments("看这个",
                List.of(new DetectedImage(p, "shot.png", 64, 48, false)), root);
        assertTrue(out.contains("path: .codetui/artifacts/"),
                "项目外的图没复制进 artifacts，引用块会被解析器丢弃：\n" + out);
        assertTrue(out.contains("name: shot.png"), "复制后丢了原始文件名：" + out);
    }

    /** ★ 产出的块必须能被期 1 的解析器认回来——两边格式一漂移就是图静默消失。 */
    @Test
    void producedBlockIsAcceptedByPhaseOneParser() throws Exception {
        Path p = png(root, "a.png");
        String out = CodeTuiView.injectAttachments("看",
                List.of(new DetectedImage(p, "a.png", 64, 48, true)), root);
        var refs = FileReferenceParser.parse(out, root);
        assertEquals(1, refs.size(), "期 1 的解析器不认这个块：\n" + out);
        assertEquals("a.png", refs.get(0).name());
    }

    /** ★ 项目外的图复制之后，同样必须能被解析器认回来（这才是复制的全部意义）。 */
    @Test
    void copiedOutsideImageIsAlsoAcceptedByParser() throws Exception {
        Path p = png(outside, "desk.png");
        String out = CodeTuiView.injectAttachments("看",
                List.of(new DetectedImage(p, "desk.png", 64, 48, false)), root);
        assertEquals(1, FileReferenceParser.parse(out, root).size(),
                "复制进 artifacts 之后解析器仍不认：\n" + out);
    }

    /** 尺寸为 0 表示「没解析出来」（只读了前 64 KiB），不能渲成 dimensions: 0x0 这种假信息。 */
    @Test
    void zeroDimensionsAreOmittedNotRenderedAsZeroByZero() throws Exception {
        Path p = png(root, "big.png");
        String out = CodeTuiView.injectAttachments("看",
                List.of(new DetectedImage(p, "big.png", 0, 0, true)), root);
        assertFalse(out.contains("dimensions:"), "把未知尺寸渲成了假信息：\n" + out);
        assertEquals(1, FileReferenceParser.parse(out, root).size(), "缺 dimensions 后解析器不认了");
    }

    @Test
    void multipleImagesEachGetTheirOwnBlock() throws Exception {
        Path a = png(root, "a.png");
        Path b = png(root, "b.png");
        String out = CodeTuiView.injectAttachments("对比",
                List.of(new DetectedImage(a, "a.png", 64, 48, true),
                        new DetectedImage(b, "b.png", 64, 48, true)), root);
        assertEquals(2, FileReferenceParser.parse(out, root).size());
    }

    @Test
    void noAttachmentsMeansTextUnchanged() {
        assertEquals("原样", CodeTuiView.injectAttachments("原样", List.of(), root));
    }

    // ── 能力闸门（接线级：纯函数测不到，injectAttachments 根本不知道闸门存在） ──────────

    /** 丢弃型 scrollback 接缝：本类只关心输入框状态与 notice，scrollback 内容不参与。 */
    private static final ScrollbackPrinter.Sink NULL_SINK = new ScrollbackPrinter.Sink() {
        @Override public void println(Text line)   { }
        @Override public void println(String line) { }
    };

    /** 造 view：{@code model} 决定闸门放不放行（SubmitHandler 默认 currentModel() 是空串＝无视觉）。 */
    private static CodeTuiView view(ConversationState state, Path root, String model) {
        return view(state, root, model,
                new ModelCapabilities(VisionModels.supportsImage(model), false));
    }

    private static CodeTuiView view(ConversationState state, Path root, String model,
                                    ModelCapabilities capabilities) {
        return new CodeTuiView(state, new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
            @Override public String currentModel() { return model; }
            @Override public ModelCapabilities currentModelCapabilities() { return capabilities; }
        }, root, NULL_SINK);
    }

    /** 走真实提交路径（Enter），而不是另开一个只给测试用的后门方法。 */
    private static void submit(CodeTuiView v) { v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER)); }

    /**
     * ★ 模型不支持视觉时拦住不发，<b>且输入原地保留</b>。
     *
     * <p>不保留的话，用户切完模型还得把那段话连同路径重贴一遍——这功能不会有人用。
     * 这条同时钉住两个变异：闸门被删（输入被清空）、闸门里先 clearInput 再 return。
     */
    @Test
    @DisplayName("模型无视觉能力：拦住不发，输入框内容原地保留")
    void visionGateKeepsInputWhenModelCannotSeeImages() throws Exception {
        png(root, "docs/bug.png");
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root, "deepseek-chat");   // 不在 VisionModels 名单里
        v.setInputForTest("看下 docs/bug.png");

        submit(v);

        assertEquals("看下 docs/bug.png", v.inputTextForTest(),
                "闸门拦住后输入被清空了，用户得把话连同路径重贴一遍");
        assertTrue(state.notice().contains("deepseek-chat"),
                "没告诉用户是哪个模型不行：" + state.notice());
        assertTrue(state.notice().contains("/model"),
                "没告诉用户怎么补救：" + state.notice());
    }

    /**
     * 闸门只拦「没视觉能力」这一种情况——否则上面那条用例在「闸门永远拦住」的实现下也是绿的。
     */
    @Test
    @DisplayName("模型有视觉能力：正常提交，输入框清空")
    void visionCapableModelSubmitsNormally() throws Exception {
        // supportsImage 内含全局开关：CODETUI_VISION=off 时对所有模型返回 false，本条无从成立。
        Assumptions.assumeTrue(VisionModels.enabled(), "CODETUI_VISION=off，视觉链路整体停用");
        png(root, "docs/bug.png");
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root, "claude-sonnet-5");
        v.setInputForTest("看下 docs/bug.png");

        submit(v);

        assertEquals("", v.inputTextForTest(), "有视觉能力却被闸门拦下了：" + state.notice());
    }

    @Test
    @DisplayName("provider 判为纯文本时，不得因同名模型命中全局视觉名单而放行")
    void providerCapabilityOverridesGlobalVisionModelName() throws Exception {
        png(root, "docs/bug.png");
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root, "gpt-5.6-sol", ModelCapabilities.TEXT_ONLY);
        v.setInputForTest("看下 docs/bug.png");

        submit(v);

        assertEquals("看下 docs/bug.png", v.inputTextForTest());
        assertTrue(state.notice().contains("不支持图片输入"));
    }

    /** Ctrl+X 取消后没有附件，闸门无从触发——文本模型照样能发一条含图片路径的普通消息。 */
    @Test
    @DisplayName("Ctrl+X 取消附件后，文本模型不再被闸门拦")
    void cancelledAttachmentsBypassTheGate() throws Exception {
        png(root, "docs/bug.png");
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root, "deepseek-chat");
        v.setInputForTest("看下 docs/bug.png");
        v.feedKeyForTest(KeyEvent.ofChar('x', dev.tamboui.tui.event.KeyModifiers.CTRL));

        submit(v);

        assertEquals("", v.inputTextForTest(),
                "已经 Ctrl+X 取消了附件，闸门还在拦：" + state.notice());
    }
}
