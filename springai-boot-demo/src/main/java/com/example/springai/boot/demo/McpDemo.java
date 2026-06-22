package com.example.springai.boot.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 示例：MCP（Model Context Protocol）客户端。
 *
 * <p>MCP 让你“即插即用”地把外部进程暴露的工具交给模型调用。用 Spring Boot 的 MCP starter，
 * 只需在 application.properties 写几行配置就能连接一个外部 MCP Server——这正是放在 boot 模块的原因：
 * 配置很重的事，交给自动配置最省心（在 core/agent 里手写这套连接代码会相当繁琐）。
 *
 * <p>默认未连接任何 Server（不影响其它示例），会自动检测：配了就列出工具并试用，没配就打印指引。
 */
@Component
public class McpDemo implements Demo {

    private final ChatClient chatClient;
    private final ObjectProvider<ToolCallbackProvider> mcpToolProvider;

    public McpDemo(ChatClient chatClient, ObjectProvider<ToolCallbackProvider> mcpToolProvider) {
        this.chatClient = chatClient;
        this.mcpToolProvider = mcpToolProvider;
    }

    @Override
    public String title() {
        return "MCP 客户端（连接外部工具，需外部 Server，可选）";
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public void run() {
        ToolCallbackProvider provider = mcpToolProvider.getIfAvailable();
        ToolCallback[] tools = provider == null ? new ToolCallback[0] : provider.getToolCallbacks();

        if (tools.length == 0) {
            printSetupGuide();
            return;
        }

        System.out.println("已从 MCP Server 发现 " + tools.length + " 个工具：");
        for (ToolCallback t : tools) {
            System.out.println("  - " + t.getToolDefinition().name());
        }

        String answer = chatClient.prompt()
                .user("请利用可用的工具，完成一个能体现这些工具用途的小任务，并说明你做了什么。")
                .toolCallbacks(provider)
                .call()
                .content();

        System.out.println("\n模型回答：" + answer);
    }

    private void printSetupGuide() {
        System.out.println("""
                当前未连接任何 MCP Server（这是默认状态）。要体验 MCP，请：

                1) 安装 Node.js（提供 npx 命令）。
                2) 在 src/main/resources/application.properties 中加入一个 MCP Server，例如官方文件系统 Server：

                   spring.ai.mcp.client.stdio.connections.fs.command=npx
                   spring.ai.mcp.client.stdio.connections.fs.args=-y,@modelcontextprotocol/server-filesystem,/tmp

                3) 重新启动本应用，再运行本示例：将看到该 Server 暴露的「读写文件」等工具，
                   并可让模型调用它们（例如让它在 /tmp 下列目录或读文件）。

                说明：MCP 让你“即插即用”地给模型接入各种现成工具，是构建智能体的重要拼图。
                """);
    }
}
