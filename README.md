# Spring AI 2.0 学习示例

一个面向初学者的 **Spring AI 2.0** 演示项目。核心设计是一组**对比**：

- `springai-core-demo` / `springai-agent-demo` —— **纯 Java，使用 Spring AI 原始 API**，所有对象都自己手动 `new`，**看得见每一步**；
- `springai-boot-demo` —— **用 Spring Boot starter 演示「自动装配」**，同样的对象一行 `new` 都不用写。

两边一对照，你就能彻底搞懂「自动配置（auto-configuration）到底替你做了什么」——这正是大多数初学者最容易犯迷糊的地方。

- **对话模型**：[DeepSeek](https://platform.deepseek.com/)（国内可直连、价格低）
- **向量模型**：本地 ONNX 模型（无需 API Key，离线运行）—— 因为 DeepSeek 官方 API 只提供对话、不提供向量
- **运行方式**：控制台菜单。启动后输入数字选择示例，看完一个再选下一个

## 技术栈

| 组件 | 版本 |
|------|------|
| Spring AI | 2.0.0 |
| Spring Boot | 4.0.7（仅 boot 模块使用） |
| Java | 21 |
| Maven | 3.9+ |

## 项目结构

```
springai-agentdemo                  父工程（聚合 + 版本管理，packaging=pom，不绑定 Spring Boot）
│
├── springai-core-demo              【原始 API · 纯 Java】Spring AI 核心能力
│   └── main 里手动 new：DeepSeekApi → ChatModel → ChatClient → EmbeddingModel
│       1.对话  2.Prompt模板  3.流式  4.结构化输出  5.本地Embedding  6.RAG
│
├── springai-agent-demo            【原始 API · 纯 Java】Spring AI 智能体能力
│   └── 1.工具调用  2.对话记忆  3.多步 Agent
│
└── springai-boot-demo             【自动装配 · Spring Boot】对比演示
    └── starter 自动配置好一切，业务代码只需注入
        1.自动配置揭秘★  2.极简对话  3.MCP 客户端
```

> **学习路线建议**：先看 `springai-boot-demo` 的「自动配置揭秘」示例，了解 Boot 帮你创建了哪些 Bean；
> 再去 `springai-core-demo` 的 `CoreDemoApplication.main` 看这些 Bean 手动创建时长什么样。一来一回，概念就通了。

## 「原始 API」与「自动装配」到底差在哪

同样是拿到一个能用的 `ChatClient`：

**core/agent（原始 API，纯 Java）—— 你自己接线：**
```java
DeepSeekApi api = DeepSeekApi.builder().apiKey(key).baseUrl("https://api.deepseek.com").build();
DeepSeekChatModel model = DeepSeekChatModel.builder()
        .deepSeekApi(api)
        .options(DeepSeekChatOptions.builder().model(DeepSeekApi.ChatModel.DEEPSEEK_CHAT).temperature(0.7).build())
        .build();
ChatClient chatClient = ChatClient.builder(model).defaultSystem("...").build();
```

**boot（自动装配）—— starter 替你接线，你直接用：**
```java
@Component
class MyDemo {
    MyDemo(ChatClient.Builder builder) {   // ← 已自动配置好，直接注入
        ChatClient chatClient = builder.defaultSystem("...").build();
    }
}
```
配置（api-key、模型名、温度）写在 `application.properties` 的 `spring.ai.deepseek.*`，starter 读取后自动装配。

## 快速开始

### 1. 准备 DeepSeek API Key

到 https://platform.deepseek.com/ 创建 API Key，设置环境变量：

```bash
export DEEPSEEK_API_KEY=你的key      # macOS / Linux
# Windows PowerShell: $env:DEEPSEEK_API_KEY="你的key"
```

### 2. 构建

```bash
mvn clean package
```

### 3. 运行

```bash
# 原始 API 模块（纯 Java，标准可执行 jar + target/lib 依赖）
java -jar springai-core-demo/target/springai-core-demo.jar
java -jar springai-agent-demo/target/springai-agent-demo.jar

# 自动装配模块（Spring Boot，可执行 fat jar；也可用 mvn -pl springai-boot-demo spring-boot:run）
java -jar springai-boot-demo/target/springai-boot-demo-1.0.0.jar
```

启动后按菜单输入序号，`0` 退出。

> **首次运行**涉及本地向量模型的模块（core 的 Embedding/RAG、boot）会下载模型文件（约 90MB）。
> 若慢，设置 HuggingFace 镜像：`export HF_ENDPOINT=https://hf-mirror.com`

## 想换成别的模型？

Spring AI 的 API 与模型解耦。换成 OpenAI / 通义 / Ollama 等：
- **原始 API 模块**：把 `spring-ai-deepseek` 换成目标模型库，`main` 里改用对应的 `XxxApi`/`XxxChatModel`；
- **自动装配模块**：把 `spring-ai-starter-model-deepseek` 换成目标 starter，改 `application.properties` 的 `spring.ai.<模型>.*`。

业务代码（用 `ChatClient` 的部分）基本不用动。

## 各模块详细说明

- [springai-core-demo/README.md](springai-core-demo/README.md)
- [springai-agent-demo/README.md](springai-agent-demo/README.md)
- [springai-boot-demo/README.md](springai-boot-demo/README.md)
