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
| 4 | Advisor 顺序 | `ToolMemoryAdvisorDemo` | `ToolCallingAdvisor` 配合 `MessageChatMemoryAdvisor`，演示两者**顺序**决定工具调用的中间消息是否进入记忆 |

## 示例 4 详解：ToolCallingAdvisor 与记忆的「顺序」意义

默认情况下工具调用循环发生在 ChatModel **内部**，其它 advisor 看不到。`ToolCallingAdvisor` 把这个
循环搬进 **advisor 链**，于是记忆 advisor 能否“看见”每一轮工具调用，就取决于两者的 `getOrder()`
（**order 越小越靠外层**）：

| 顺序 | 记忆位置 | 工具调用中间消息是否入记忆 | 适用场景 |
|------|---------|--------------------------|---------|
| 记忆 order < 工具 order（**默认**） | 外层，包住整个工具循环 | **否**，只记“提问 + 最终回答” | 想要干净精简的对话历史 |
| 记忆 order > 工具 order | 内层，处于工具循环之内 | **是**，连“请求调用工具/工具返回”都记下 | 想持久化完整工具轨迹（审计/调试/复盘） |

运行该示例会用同一个问题分别跑两种顺序，并打印各自记忆里实际存了几条消息——
默认顺序存 2 条，反转顺序存 6 条，眼见为实。参考官方文档
[Recursive Advisors / ToolCallingAdvisor](https://docs.spring.io/spring-ai/reference/api/advisors-recursive.html#_toolcallingadvisor)。

## 工具是怎么定义的

见 `tools/` 目录：在普通类的方法上加 `@Tool(description=...)`、参数加 `@ToolParam(description=...)`，
description 就是给模型看的“说明书”，模型据此决定**何时调用、传什么参数**。注意：工具机制不依赖 Spring 容器，
普通对象 `new` 出来传给 `.tools(...)` 即可。

- `DateTimeTools`：获取当前时间、计算日期差
- `WeatherTools`：查询天气（演示用，返回假数据）

## MCP 去哪了？

MCP 客户端配置较重（需外部 Server），用 Spring Boot starter 几行配置就能搞定，因此**迁到了
`springai-boot-demo`**，在那里演示更合适。
