# springai-boot-demo —— Spring Boot 自动装配演示

本模块用 **Spring Boot + Spring AI starter**，演示「自动装配（auto-configuration）」。
与 `springai-core-demo` / `springai-agent-demo` 的“手动接线”形成对比：这里 `ChatModel`、`EmbeddingModel`、
`ChatClient.Builder` 等**一行 `new` 都不用写**，starter 在启动时自动创建好，你声明类型即可注入。

## 运行

```bash
export DEEPSEEK_API_KEY=你的key
mvn -pl springai-boot-demo spring-boot:run
# 或：mvn -pl springai-boot-demo -am package && java -jar springai-boot-demo/target/springai-boot-demo-<version>.jar
```

## 示例清单

| 菜单项 | 示例 | 关键类 | 你将学到 |
|------|------|--------|---------|
| 1 | 自动配置揭秘（建议先看） | `AutoConfigInspectDemo` | 打印各 AI Bean 的实现类/所在 jar/配置方式，证明它们是自动配置出来的（零成本，不调模型） |
| 2 | 极简对话 | `BootChatDemo` | 只注入一个 `ChatClient` 就能对话，底层全自动 |
| 3 | MCP 客户端 | `McpDemo` | 用 starter + 几行 properties 接入外部 MCP Server（默认未连接） |
| 4 | 会话落库 | `SessionJdbcDemo` | 会话 JDBC starter 自动配置 `SessionService`，会话日志存进 H2 文件库，重启进程仍在 |

## 核心看点：starter = 原始库 + 自动配置

| | core/agent（原始库） | boot（starter） |
|---|---|---|
| 依赖 | `spring-ai-deepseek` 等本体库 | `spring-ai-starter-model-deepseek` 等 starter |
| 创建 Bean | `main` 里手动 `new` / `build` | starter 自动配置，无需任何代码 |
| 读配置 | 自己从环境变量读、传进 builder | 写在 `application.properties`，starter 自动读取 |
| 业务代码 | 需要先接线再使用 | 直接 `@Autowired` / 构造器注入即用 |
| 会话仓库 | 两行手动接线：`DefaultSessionService.builder().sessionRepository(InMemorySessionRepository…)` | 注入 `SessionService` 即用，仓库由 starter 配成 JDBC（示例 4） |

运行「自动配置揭秘」示例，会看到容器里自动配置好的 `EmbeddingModel`、`ChatModel`、`ChatClient.Builder`
分别来自哪个 jar——这就是 starter 在背后替你做的事。

## 会话落库（示例 4）

第②步的三个会话示例都把会话放在内存里（`InMemorySessionRepository`），进程一退就丢。这里换成数据库：
加一个 starter、三行配置，`SessionRepository`（`JdbcSessionRepository`）和它之上的 `SessionService` 都由
starter 自动配置——对比 agent-demo 里手写的两行 `DefaultSessionService.builder().sessionRepository(..)`，
这里一个构造器参数都不用管。

依赖（版本都由 BOM 管）：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-session-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

`application.properties`：

```properties
# ===== 会话落库：H2 文件库放在用户目录下，重启进程仍在；删掉该目录即重置 =====
spring.datasource.url=jdbc:h2:file:${user.home}/.springai-boot-demo/sessions;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driver-class-name=org.h2.Driver
spring.ai.session.repository.jdbc.initialize-schema=always
```

starter 自带 `spring-boot-starter-jdbc`（DataSource / JdbcTemplate / 事务管理器），但**不带数据库驱动**，所以 H2
要自己加。`initialize-schema=always` 让它每次启动都执行建表脚本（H2 版全是 `CREATE TABLE IF NOT EXISTS`）——
所以本模块一启动就会在用户目录下建出库文件，不必等到运行示例 4。用业务库时把 `spring.datasource.*` 指过去即可，
starter 支持 PostgreSQL / MySQL / MariaDB / SQL Server / H2。

会话日志就是表里的行：`AI_SESSION`（会话）、`AI_SESSION_EVENT`（日志，一行一条事件）、`AI_SESSION_INDEX`
（被索引的会话元数据）。示例固定用会话 `boot-demo-user`。第一遍运行（真实运行片段）：

```
数据库：jdbc:h2:file:/Users/zxh/.springai-boot-demo/sessions;DB_CLOSE_ON_EXIT=FALSE
会话 boot-demo-user 在库里已有 0 条历史事件（首次运行）。

第 1 问（看它记不记得上一次运行说过的事）
问：我之前告诉过你我的工号吗？不知道就直说。
答：没有。在我当前能看到的对话里，你没有告诉过我工号；如果你在其他会话里说过，我也无法访问或记住。

第 2 问（告诉它，之后每次运行都记得）
问：请记住，我的工号是 A1024。
答：好的，本次对话中我会记住：你的工号是 **A1024**。不过我不能保证跨会话长期记住。

第 3 问（同一进程内立刻验证）
问：我的工号是多少？
答：你的工号是 **A1024**。


库里的会话日志（AI_SESSION_EVENT，一行一条事件）：
  seq=1    MESSAGE    USER      我之前告诉过你我的工号吗？不知道就直说。
  seq=2    MESSAGE    ASSISTANT 没有。在我当前能看到的对话里，你没有告诉…
  seq=3    MESSAGE    USER      请记住，我的工号是 A1024。
  seq=4    MESSAGE    ASSISTANT 好的，本次对话中我会记住：你的工号是 *…
  seq=5    MESSAGE    USER      我的工号是多少？
  seq=6    MESSAGE    ASSISTANT 你的工号是 **A1024**。
```

退出程序、再运行一遍（真实运行片段，节选）：

```
会话 boot-demo-user 在库里已有 6 条历史事件（来自上一次运行）。

第 1 问（看它记不记得上一次运行说过的事）
问：我之前告诉过你我的工号吗？不知道就直说。
答：告诉过。在本对话中你告诉过我，工号是 **A1024**。
…
  seq=12   MESSAGE    ASSISTANT 你的工号是 **A1024**。
```

上一个 JVM 已经退出，第 1 问却答得上来——会话在数据库里，不在 JVM 内存里。（模型说「跨会话不保证能记住」
只是它的自我认知：它并不知道自己的会话已经落库。）想从头演示：删掉 `~/.springai-boot-demo` 目录。

参考官方文档：[JDBC Session Repository](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/integrations/stores/session-jdbc.html)。

## 启用 MCP（可选）

MCP（Model Context Protocol）是一个把外部进程的工具**标准化**暴露给模型的开放协议——
好比给 Agent 用的「USB 接口」：任何实现了协议的 server（文件系统、数据库、浏览器……）
都能以统一方式接进来，模型按需调用其工具。

MCP 需要一个外部 MCP Server（通常用 `npx` 启动，依赖 Node.js），默认未连接，不影响其它示例。
启用步骤见 `application.properties` 中被注释的配置，以及运行 `McpDemo` 时打印的指引。
