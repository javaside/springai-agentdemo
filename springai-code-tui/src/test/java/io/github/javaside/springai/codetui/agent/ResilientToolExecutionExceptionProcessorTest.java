package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResilientToolExecutionExceptionProcessor} 行为验证：
 * 工具执行异常必须转成<b>信息完整</b>的错误文本（含异常类型、消息、cause 链与关键堆栈帧），
 * 供模型对症继续；绝不能 rethrow 终止回合，也不能只给一句「执行出错」让模型无从判断。
 */
class ResilientToolExecutionExceptionProcessorTest {

    private static ToolExecutionException ex(String toolName, Throwable cause) {
        ToolDefinition def = ToolDefinition.builder().name(toolName).description("d")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolExecutionException(def, cause);
    }

    @Test
    void runtimeCause_includesTypeMessageAndStack() {
        String out = new ResilientToolExecutionExceptionProcessor()
                .process(ex("Read", new IllegalStateException("文件不存在: /x")));

        assertTrue(out.contains("Read"), "应点名工具，实际=" + out);
        assertTrue(out.contains("IllegalStateException"), "应含异常类型，实际=" + out);
        assertTrue(out.contains("文件不存在"), "应包含错误消息，实际=" + out);
        assertTrue(out.contains("堆栈"), "应包含堆栈信息，实际=" + out);
        assertTrue(out.contains("("), "堆栈帧应含文件:行，实际=" + out);
        assertFalse(out.contains("\n"), "应为单行文本（tool 结果不跨行），实际=" + out);
        assertFalse(out.contains("请调整后重试"), "错误文本不应附加引导词，实际=" + out);
    }

    @Test
    void errorCause_classLoadingMishap_includesRootCauseType() {
        // 模拟「类加载错乱」这类 Error cause——默认处理器会 rethrow，我们的处理器必须兜住，
        // 且要让模型看到 NoClassDefFoundError 这个根因（它才能判断是环境/版本问题）。
        String out = new ResilientToolExecutionExceptionProcessor()
                .process(ex("AskUserQuestionTool",
                        new NoClassDefFoundError("io/github/javaside/springai/codetui/agent/QuestionSpec")));

        assertTrue(out.contains("AskUserQuestionTool"), "应点名工具，实际=" + out);
        assertTrue(out.contains("NoClassDefFoundError"), "应含根因类型，实际=" + out);
        assertTrue(out.contains("QuestionSpec"), "应含缺失的类名，实际=" + out);
        assertTrue(out.contains("堆栈"), "应含堆栈，实际=" + out);
    }

    @Test
    void nestedCause_chainIsIncluded() {
        Throwable root = new NoClassDefFoundError("io/x/Y");
        Throwable mid = new IllegalStateException("初始化失败", root);
        String out = new ResilientToolExecutionExceptionProcessor().process(ex("Bash", mid));

        assertTrue(out.contains("NoClassDefFoundError"), "应含最底层根因，实际=" + out);
        assertTrue(out.contains("IllegalStateException"), "应含中间层 cause，实际=" + out);
        assertTrue(out.contains("引发于"), "应标出 cause 链关系，实际=" + out);
    }

    @Test
    void stackFramesAreCapped_notFullDump() {
        Throwable t = new IllegalStateException("boom");
        StackTraceElement[] frames = new StackTraceElement[40];
        for (int i = 0; i < 40; i++) {
            frames[i] = new StackTraceElement("pkg.Class" + i, "m" + i, "Class" + i + ".java", i);
        }
        t.setStackTrace(frames);
        String out = new ResilientToolExecutionExceptionProcessor().process(ex("Bash", t));

        assertTrue(out.contains("Class0"), "应含首个栈帧，实际=" + out);
        assertFalse(out.contains("Class30"), "堆栈应被截断，不该出现第 30 帧，实际=" + out);
        // 6 帧上限：出现 0..5，不出现 6
        assertTrue(out.contains("Class5.java"), "应含第 6 帧，实际=" + out);
        assertFalse(out.contains("Class6.java"), "不应超过 6 帧，实际=" + out);
    }
}
