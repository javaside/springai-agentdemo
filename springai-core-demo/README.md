# springai-core-demo —— Spring AI 核心能力（原始 API · 纯 Java）

**不依赖 Spring Boot**，直接用 Spring AI 的原始库。所有对象（`DeepSeekApi`、`ChatModel`、`ChatClient`、
`EmbeddingModel`）都在 `CoreDemoApplication.main` 里**手动创建**——这正是为了让你看清“东西是怎么一步步造出来的”。
对照 `springai-boot-demo` 即可体会自动装配省了多少代码。

## 运行

```bash
export DEEPSEEK_API_KEY=你的key
mvn -pl springai-core-demo -am package        # 先构建（生成 jar 与 target/lib）
java -jar springai-core-demo/target/springai-core-demo.jar
```

启动后按菜单输入序号运行。

## 示例清单

| 菜单项 | 示例 | 关键类 | 你将学到 |
|------|------|--------|---------|
| 1 | 基础对话 | `ChatDemo` | `ChatClient` 的 `prompt().user().call().content()` 同步调用 |
| 2 | Prompt 模板 | `PromptTemplateDemo` | 用 `{占位符}` + `param()` 组织提示词；`system()` 设定角色 |
| 3 | 流式输出 | `StreamDemo` | 用 `stream()` 获得打字机式的逐段输出（Flux） |
| 4 | 结构化输出 | `StructuredOutputDemo` | 用 `entity(...)` 让模型直接返回 Java 对象 / List |
| 5 | 文本向量化 | `EmbeddingDemo` | 用本地 `EmbeddingModel` 把文字转向量，并算余弦相似度 |
| 6 | RAG 检索增强 | `RagDemo` | 手写「存知识 → 检索 → 拼上下文 → 提问」的完整 RAG 流程 |

## 看点：手动接线

打开 `CoreDemoApplication.java`，重点看 `main` 方法里这几步（都是“原始 API”）：

1. `DeepSeekApi.builder()...build()` —— 底层 HTTP 客户端
2. `DeepSeekChatModel.builder().deepSeekApi(api).options(...).build()` —— 对话模型
3. `ChatClient.builder(chatModel).defaultSystem(...).build()` —— 高层对话入口
4. `new TransformersEmbeddingModel()` + `afterPropertiesSet()` —— 本地向量模型（必须手动触发加载）
5. 手动把各 Demo 放进 `List<Demo>` 交给 `ConsoleMenu`

> 这 5 步在 `springai-boot-demo` 里【全部由 starter 自动完成】。

## 关于向量模型

对话走 DeepSeek，**向量走本地 ONNX 模型**（`spring-ai-transformers`），因为 DeepSeek 官方 API 不提供向量能力。
本地模型无需 API Key，首次运行下载约 90MB。

## 打包方式说明

本模块不是 Spring Boot 应用，用 `maven-jar-plugin`（写入 Main-Class）+ `maven-dependency-plugin`
（把依赖复制到 `target/lib/`）打成标准可执行 jar。这样原生库（ONNX）能正常加载，`java -jar` 直接运行。
