# springai-boot-demo —— Spring Boot 自动装配演示

本模块用 **Spring Boot + Spring AI starter**，演示「自动装配（auto-configuration）」。
与 `springai-core-demo` / `springai-agent-demo` 的“手动接线”形成对比：这里 `ChatModel`、`EmbeddingModel`、
`ChatClient.Builder` 等**一行 `new` 都不用写**，starter 在启动时自动创建好，你声明类型即可注入。

## 运行

```bash
export DEEPSEEK_API_KEY=你的key
mvn -pl springai-boot-demo spring-boot:run
# 或：mvn -pl springai-boot-demo -am package && java -jar springai-boot-demo/target/springai-boot-demo-1.0.0.jar
```

## 示例清单

| 菜单项 | 示例 | 关键类 | 你将学到 |
|------|------|--------|---------|
| 1 | 自动配置揭秘 ★建议先看 | `AutoConfigInspectDemo` | 打印各 AI Bean 的实现类/所在 jar/配置方式，证明它们是自动配置出来的（零成本，不调模型） |
| 2 | 极简对话 | `BootChatDemo` | 只注入一个 `ChatClient` 就能对话，底层全自动 |
| 3 | MCP 客户端 | `McpDemo` | 用 starter + 几行 properties 接入外部 MCP Server（默认未连接） |

## 核心看点：starter = 原始库 + 自动配置

| | core/agent（原始库） | boot（starter） |
|---|---|---|
| 依赖 | `spring-ai-deepseek` 等本体库 | `spring-ai-starter-model-deepseek` 等 starter |
| 创建 Bean | `main` 里手动 `new` / `build` | starter 自动配置，无需任何代码 |
| 读配置 | 自己从环境变量读、传进 builder | 写在 `application.properties`，starter 自动读取 |
| 业务代码 | 需要先接线再使用 | 直接 `@Autowired` / 构造器注入即用 |

运行「自动配置揭秘」示例，会看到容器里自动配置好的 `EmbeddingModel`、`ChatModel`、`ChatClient.Builder`
分别来自哪个 jar——这就是 starter 在背后替你做的事。

## 启用 MCP（可选）

MCP 需要一个外部 MCP Server（通常用 `npx` 启动，依赖 Node.js），默认未连接，不影响其它示例。
启用步骤见 `application.properties` 中被注释的配置，以及运行 `McpDemo` 时打印的指引。
