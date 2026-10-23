package io.github.javaside.springai.codetui.agent.session;

import io.github.javaside.springai.codetui.agent.interjection.InterjectionText;
import io.github.javaside.springai.codetui.agent.media.FileReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** stripAll：会话里存的「注入后」用户文本 → 用户原文。三步剥离搬自 HistoryReplay（纯搬家），此处钉组合行为。 */
class UserTextStripperTest {

    @Test
    void stripAll_removesSkillInstructionPrefix() {
        String stored = "<skill_instruction>\n技能正文\n</skill_instruction>\n\n帮我改 pom";
        assertEquals("帮我改 pom", UserTextStripper.stripAll(stored));
    }

    @Test
    void stripAll_rewritesFileReferenceBlockToOneLine() {
        String block = FileReference.OPEN + "\nname: bug.png\ndimensions: 800x600\nkind: image\n"
                + "path: .codetui/artifacts/abc.png\n" + FileReference.CLOSE;
        String out = UserTextStripper.stripAll(block + "\n看看这个报错");
        // 引用块折叠成一行 📎（字段缺失少显示，不报错）；正文保留
        org.junit.jupiter.api.Assertions.assertTrue(out.startsWith("📎 bug.png"), "折叠行: " + out);
        org.junit.jupiter.api.Assertions.assertTrue(out.endsWith("看看这个报错"));
    }

    @Test
    void stripAll_unwrapsInterjectionAndCombinesAllThree() {
        String raw = "等一下，先看测试";
        String stored = InterjectionText.wrap("<skill_instruction>\nx\n</skill_instruction>\n\n"
                + FileReference.OPEN + "\nname: a.png\n" + FileReference.CLOSE + "\n" + raw);
        // 引用块是【折叠】为一行 📎 而非删除（与 HistoryReplay 回放同语义）——期望必须含折叠行
        assertEquals("📎 a.png\n" + raw, UserTextStripper.stripAll(stored), "包裹+技能前缀剥除、引用块折叠");
    }

    @Test
    void plainTextUntouched_andIncompleteMarkerKept() {
        assertEquals("普通文本", UserTextStripper.stripAll("普通文本"));
        String broken = "<skill_instruction>没闭合";
        assertEquals(broken, UserTextStripper.stripAll(broken), "残缺标记保底原样，不误删");
    }
}
