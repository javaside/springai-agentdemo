package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;

/** 一次性参考：打印现有工具经 ToolCallbacks.from 派生的真实注册名，供写 agents/*.md 的 tools 字段。 */
class ToolNameProbeTest {

    @Test
    void printRealToolNames() {
        Path root = Path.of(".").toAbsolutePath();
        Object[] objs = {
                FileSystemTools.builder().allowedDirectory(root).build(),
                ShellTools.builder().build(),
                GrepTool.builder().workingDirectory(root).build(),
                GlobTool.builder().workingDirectory(root).build(),
                TodoWriteTool.builder().build(),
        };
        for (ToolCallback c : ToolCallbacks.from(objs)) {
            System.out.println("TOOL_NAME= " + c.getToolDefinition().name());
        }
    }
}
