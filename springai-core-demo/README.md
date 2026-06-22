# springai-core-demo —— Spring AI 核心能力

演示 Spring AI 最常用的核心功能。运行：

```bash
export DEEPSEEK_API_KEY=你的key
mvn -pl springai-core-demo spring-boot:run
```

启动后按菜单输入序号运行。

## 示例清单

| 菜单项 | 示例 | 关键类 | 你将学到 |
|------|------|--------|---------|
| 1 | 自动配置揭秘 ★建议先看 | `AutoConfigInspectDemo` | 这些 AI Bean 是谁、来自哪个 jar、是自动配置还是手写（零成本，不调模型） |
| 2 | 基础对话 | `ChatDemo` | `ChatClient` 的 `prompt().user().call().content()` 同步调用 |
| 3 | Prompt 模板 | `PromptTemplateDemo` | 用 `{占位符}` + `param()` 组织提示词；`system()` 设定角色 |
| 4 | 流式输出 | `StreamDemo` | 用 `stream()` 获得打字机式的逐段输出（Flux） |
| 5 | 结构化输出 | `StructuredOutputDemo` | 用 `entity(...)` 让模型直接返回 Java 对象 / List，免手写 JSON 解析 |
| 6 | 文本向量化 | `EmbeddingDemo` | 用本地 `EmbeddingModel` 把文字转向量，并算余弦相似度 |
| 7 | RAG 检索增强 | `RagDemo` | 手写「存知识 → 检索 → 拼上下文 → 提问」的完整 RAG 流程 |

## 先搞懂一个概念：自动配置（auto-configuration）

新手最常见的困惑：`EmbeddingModel`、`ChatModel` 这些对象，代码里**既没有 `new`、也没有 `@Bean`**，
怎么就能在构造器里直接注入使用？

答案是 Spring Boot 的核心机制「自动配置」：**只要 starter 在 classpath 上，它内部就会自动帮你
创建并注册好对应的 Bean**，你声明类型即可注入。所以你在本项目里搜不到 `new TransformersEmbeddingModel()`——
它藏在 starter 的 jar 里。

- 想**亲眼看见**这些 Bean 的真实实现类和所在 jar：运行菜单第 1 项「自动配置揭秘」。
- 想**自定义**它们：在 `application.properties` 改对应的 `spring.ai.*` 配置即可。
- 对比记忆：本项目里唯一“手写 `@Bean`”的是 `ChatClient`（为了加默认系统提示词，见 `ChatClientConfig`），
  其余 AI Bean 全是自动配置的。

## 关于向量模型

对话走 DeepSeek，但**向量（Embedding）走本地 ONNX 模型**（`spring-ai-starter-model-transformers`），
因为 DeepSeek 官方 API 不提供向量能力。本地模型无需 API Key，首次运行会下载模型文件（约 90MB）。

## 关键代码入口

- 共享的 `ChatClient` Bean：`config/ChatClientConfig.java`
- 菜单调度：`DemoMenuRunner.java`
- 各示例：`demo/` 目录，均实现统一的 `Demo` 接口
