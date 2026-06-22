# Spring AI 2.0 学习示例

一个面向初学者的 **Spring AI 2.0** 演示项目。用最少的样板代码、详尽的中文注释，带你跑通 Spring AI 的核心能力与智能体（Agent）能力。

- **对话模型**：[DeepSeek](https://platform.deepseek.com/)（国内可直连、价格低）
- **向量模型**：本地 ONNX 模型（无需 API Key，离线运行）—— 因为 DeepSeek 官方 API 只提供对话、不提供向量
- **运行方式**：控制台菜单。启动后输入数字选择示例，看完一个再选下一个，**无需改代码、无需重启**

## 技术栈

| 组件 | 版本 |
|------|------|
| Spring AI | 2.0.0 |
| Spring Boot | 4.0.7 |
| Java | 21 |
| Maven | 3.9+ |

## 项目结构

```
springai-agentdemo                  父工程（Maven 聚合 + 统一版本管理，packaging=pom）
├── springai-core-demo              Spring AI 核心能力
│   ├── 0. 自动配置揭秘 ★建议先看（Bean 从哪来）
│   ├── 1. 基础对话（ChatClient）
│   ├── 2. Prompt 模板（占位符 + System 提示词）
│   ├── 3. 流式输出（Streaming）
│   ├── 4. 结构化输出（返回 Java 对象）
│   ├── 5. 文本向量化（本地 Embedding + 相似度）
│   └── 6. RAG 检索增强生成（内存向量库）
└── springai-agent-demo             Spring AI 智能体能力
    ├── 0. 自动配置揭秘 ★建议先看（Bean 从哪来）
    ├── 1. 工具调用（Tool / Function Calling）
    ├── 2. 多轮对话记忆（ChatMemory）
    ├── 3. 多步 Agent（自动串联多个工具）
    └── 4. MCP 客户端（连接外部工具，可选）
```

> **新手必读 · 关于「自动配置」**：你会发现 `EmbeddingModel`、`ChatModel` 这些对象，代码里既没
> `new` 也没 `@Bean`，却能直接注入——这是 Spring Boot 的「自动配置」：starter 在 classpath 上时
> 会自动帮你创建并注册 Bean。每个模块的**第 1 个示例「自动配置揭秘」**会把这些 Bean 的实现类、
> 所在 jar、配置方式打印出来，让“看不见的魔法”变得看得见，**建议第一个运行**（零成本，不调模型）。

## 快速开始

### 1. 准备 DeepSeek API Key

到 https://platform.deepseek.com/ 注册并创建 API Key，然后设置环境变量：

```bash
export DEEPSEEK_API_KEY=你的key      # macOS / Linux
# Windows PowerShell: $env:DEEPSEEK_API_KEY="你的key"
```

### 2. 运行核心能力示例

```bash
mvn -pl springai-core-demo spring-boot:run
```

### 3. 运行智能体示例

```bash
mvn -pl springai-agent-demo spring-boot:run
```

启动后会看到菜单，输入序号即可运行对应示例，输入 `0` 退出。

> **首次运行 core 模块** 会下载本地向量模型文件（约 90MB）。若下载较慢，可设置 HuggingFace 镜像：
> ```bash
> export HF_ENDPOINT=https://hf-mirror.com
> ```

## 构建

```bash
mvn clean package        # 编译 + 打成可执行 jar（位于各模块的 target/ 下）
```

也可直接运行打好的 jar：

```bash
java -jar springai-core-demo/target/springai-core-demo-1.0.0.jar
```

## 想换成别的模型？

本项目用 DeepSeek 演示，但 Spring AI 的 API 与模型解耦。换成 OpenAI / 通义 / Ollama 等，只需：
1. 在对应模块 `pom.xml` 把 `spring-ai-starter-model-deepseek` 换成目标模型的 starter；
2. 在 `application.properties` 改成对应的 `spring.ai.<模型>.*` 配置。

业务代码（ChatClient 那部分）基本不用动——这正是 Spring AI 的价值。

## 各模块详细说明

- [springai-core-demo/README.md](springai-core-demo/README.md)
- [springai-agent-demo/README.md](springai-agent-demo/README.md)
