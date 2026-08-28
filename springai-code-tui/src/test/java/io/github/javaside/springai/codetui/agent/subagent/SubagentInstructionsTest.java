package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.StubListener;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 子 agent 系统提示 = spec.systemPrompt() + 项目指令（非空时）+ 产物路径提示（恒追加）。 */
class SubagentInstructionsTest {

    /** 产物路径提示的特征词；与 {@code SubagentRunner.ARTIFACT_GUIDANCE} 同源（那是 private，这里按内容断言）。 */
    private static final String ARTIFACT_HINT = "artifact 路径写进最终报告";

    private static SubagentSpec spec() {
        // 真实 7 参签名（见 SubagentRunnerOkTest:57）: name, description, systemPrompt, allowTools, denyTools, model, skills
        return new SubagentSpec("explore", "探索型子 agent。", "SUBAGENT 系统提示正文",
                List.of(), List.of(), null, List.of());
    }

    private static SubagentRunner runnerWith(String projectInstructions) {
        return new SubagentRunner(
                new ProviderRegistry(List.of(new DeepSeekProvider("fake-key"))),
                List.of(), new StubListener(), projectInstructions);
    }

    @Test
    void emptyInstructions_addNothingButArtifactHint() {
        String eff = runnerWith("").effectiveSystemPrompt(spec());
        assertTrue(eff.startsWith("SUBAGENT 系统提示正文"), "spec 提示在前");
        assertTrue(eff.contains(ARTIFACT_HINT), "产物路径提示恒追加");
        assertEquals("SUBAGENT 系统提示正文", withoutArtifactHint(eff),
                "无项目指令时，除产物提示外不该多出任何东西");
    }

    @Test
    void nullInstructions_treatedAsEmpty() {
        String eff = runnerWith(null).effectiveSystemPrompt(spec());
        assertEquals("SUBAGENT 系统提示正文", withoutArtifactHint(eff),
                "null 项目指令应归一化为空，除产物提示外不该多出任何东西");
    }

    /**
     * 产物路径提示<b>与 spec 的提示正文无关</b>：任何 spec 都拿得到。
     *
     * <p>这钉的是<b>注入点</b>——提示加在 {@code effectiveSystemPrompt}（所有派发的必经之路）
     * 而不是四份内置 md 里，所以一个提示正文完全不同的 spec 照样带得上它；
     * 若哪天有人把它挪回 md，本用例会红。
     *
     * <p><b>它证明不了「用户自定义 agent 也有」</b>——那个功能今天根本不存在
     * （{@code SubagentLoader.loadBuiltins} 只读 4 个 classpath 内置 md）。这里的 spec 是测试手搓的。
     */
    @Test
    void artifactHint_presentForArbitrarySpec() {
        SubagentSpec custom = new SubagentSpec("提示正文完全不同的", "d", "完全不同的提示正文",
                List.of(), List.of(), null, List.of());
        String eff = runnerWith("").effectiveSystemPrompt(custom);
        assertTrue(eff.contains(ARTIFACT_HINT),
                "自定义 agent 也必须收到产物路径提示，实际：" + eff);
    }

    /** 提示正文不得含花括号：与 PermissionModePrompt 同一条纪律（带 param 的注入路径会被当占位符炸掉）。 */
    @Test
    void artifactHint_hasNoBraces() {
        String eff = runnerWith("").effectiveSystemPrompt(spec());
        assertFalse(eff.contains("{") || eff.contains("}"),
                "系统提示含花括号，模板渲染路径上会炸：" + eff);
    }

    /** 去掉尾部的产物提示段，便于对「其余部分」做逐字断言。 */
    private static String withoutArtifactHint(String prompt) {
        int i = prompt.indexOf("若你在调查中产生了图片");
        return i < 0 ? prompt : prompt.substring(0, i).stripTrailing();
    }

    @Test
    void nonEmptyInstructions_appendedAfterSpecPrompt() {
        String eff = runnerWith("PROJ_INSTR_文本").effectiveSystemPrompt(spec());
        assertTrue(eff.startsWith("SUBAGENT 系统提示正文"), "spec 提示在前");
        assertTrue(eff.contains("PROJ_INSTR_文本"), "项目指令被追加");
        assertTrue(eff.indexOf("SUBAGENT 系统提示正文") < eff.indexOf("PROJ_INSTR_文本"),
                "项目指令应在 spec 提示之后");
    }
}
