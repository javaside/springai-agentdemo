# springai-core-demo —— Spring AI 核心能力

演示 Spring AI 最常用的核心功能。运行：

```bash
export DEEPSEEK_API_KEY=你的key
mvn -pl springai-core-demo spring-boot:run
```

启动后按菜单输入序号运行。

## 示例清单

| 序号 | 示例 | 关键类 | 你将学到 |
|------|------|--------|---------|
| 1 | 基础对话 | `ChatDemo` | `ChatClient` 的 `prompt().user().call().content()` 同步调用 |
| 2 | Prompt 模板 | `PromptTemplateDemo` | 用 `{占位符}` + `param()` 组织提示词；`system()` 设定角色 |
| 3 | 流式输出 | `StreamDemo` | 用 `stream()` 获得打字机式的逐段输出（Flux） |
| 4 | 结构化输出 | `StructuredOutputDemo` | 用 `entity(...)` 让模型直接返回 Java 对象 / List，免手写 JSON 解析 |
| 5 | 文本向量化 | `EmbeddingDemo` | 用本地 `EmbeddingModel` 把文字转向量，并算余弦相似度 |
| 6 | RAG 检索增强 | `RagDemo` | 手写「存知识 → 检索 → 拼上下文 → 提问」的完整 RAG 流程 |

## 关于向量模型

对话走 DeepSeek，但**向量（Embedding）走本地 ONNX 模型**（`spring-ai-starter-model-transformers`），
因为 DeepSeek 官方 API 不提供向量能力。本地模型无需 API Key，首次运行会下载模型文件（约 90MB）。

## 关键代码入口

- 共享的 `ChatClient` Bean：`config/ChatClientConfig.java`
- 菜单调度：`DemoMenuRunner.java`
- 各示例：`demo/` 目录，均实现统一的 `Demo` 接口
