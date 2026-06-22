# springai-agent-demo —— Spring AI 智能体能力（原始 API · 纯 Java）

**不依赖 Spring Boot**，用 Spring AI 原始库演示智能体能力。`ChatClient` 等对象同样在
`AgentDemoApplication.main` 里手动创建。

## 运行

```bash
export DEEPSEEK_API_KEY=你的key
mvn -pl springai-agent-demo -am package
java -jar springai-agent-demo/target/springai-agent-demo.jar
```

## 示例清单

| 菜单项 | 示例 | 关键类 | 你将学到 |
|------|------|--------|---------|
| 1 | 工具调用 | `ToolCallingDemo` | 用 `@Tool` 定义方法、`tools(...)` 注册，让模型按需调用 Java 代码 |
| 2 | 对话记忆 | `ChatMemoryDemo` | 用 `MessageWindowChatMemory` + `MessageChatMemoryAdvisor` 实现多轮记忆 |
| 3 | 多步 Agent | `MultiStepAgentDemo` | 模型自动规划并连续调用多个工具完成一个任务 |

## 工具是怎么定义的

见 `tools/` 目录：在普通类的方法上加 `@Tool(description=...)`、参数加 `@ToolParam(description=...)`，
description 就是给模型看的“说明书”，模型据此决定**何时调用、传什么参数**。注意：工具机制不依赖 Spring 容器，
普通对象 `new` 出来传给 `.tools(...)` 即可。

- `DateTimeTools`：获取当前时间、计算日期差
- `WeatherTools`：查询天气（演示用，返回假数据）

## MCP 去哪了？

MCP 客户端配置较重（需外部 Server），用 Spring Boot starter 几行配置就能搞定，因此**迁到了
`springai-boot-demo`**，在那里演示更合适。
