# springai-agent-demo —— Spring AI 智能体（Agent）能力

演示如何让模型“不只是聊天”：调用工具、记住上下文、分多步完成任务、接入外部工具（MCP）。运行：

```bash
export DEEPSEEK_API_KEY=你的key
mvn -pl springai-agent-demo spring-boot:run
```

## 示例清单

| 序号 | 示例 | 关键类 | 你将学到 |
|------|------|--------|---------|
| 1 | 工具调用 | `ToolCallingDemo` | 用 `@Tool` 定义方法、`tools(...)` 注册，让模型按需调用 Java 代码 |
| 2 | 对话记忆 | `ChatMemoryDemo` | 用 `MessageWindowChatMemory` + `MessageChatMemoryAdvisor` 实现多轮记忆 |
| 3 | 多步 Agent | `MultiStepAgentDemo` | 模型自动规划并连续调用多个工具完成一个任务 |
| 4 | MCP 客户端 | `McpDemo` | 通过 MCP 协议接入外部工具（需外部 Server，默认未连接） |

## 工具是怎么定义的

见 `tools/` 目录：在普通类的方法上加 `@Tool(description=...)`、参数加 `@ToolParam(description=...)`，
description 就是给模型看的“说明书”，模型据此决定**何时调用、传什么参数**。

- `DateTimeTools`：获取当前时间、计算日期差
- `WeatherTools`：查询天气（演示用，返回假数据）

## MCP 示例如何启用（可选）

MCP 需要一个外部 MCP Server（通常用 `npx` 启动，依赖 Node.js），所以默认未连接，不影响其它示例。
启用步骤见 `application.properties` 中被注释的配置，以及运行 `McpDemo` 时打印的指引。
