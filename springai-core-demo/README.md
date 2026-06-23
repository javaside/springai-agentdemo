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
| 6 | RAG 检索增强（手写） | `RagDemo` | 手写「存知识 → 检索 → 拼上下文 → 提问」，看清 RAG 每一步原理 |
| 7 | 模块化 RAG（advisor） | `RagAdvisorDemo` | 用 `RetrievalAugmentationAdvisor`，一行 `.advisors(rag)` 自动完成检索+增强 |

## 两种 RAG 写法对比（示例 6 vs 示例 7）

RAG 在本模块给了**两种写法**，建议对照看：

- **示例 6 `RagDemo`（手写）**：自己 `vectorStore.similaritySearch(...)`、自己把检索到的文本拼进提示词。
  代码长，但每一步都看得见，适合理解 RAG 原理。
- **示例 7 `RagAdvisorDemo`（模块化）**：把检索器交给 `RetrievalAugmentationAdvisor`，业务代码只需
  `.advisors(ragAdvisor)` 一行，检索→拼上下文→增强提问全自动。这才是官方推荐的地道写法。

`RetrievalAugmentationAdvisor` 是“模块化”的——检索（`DocumentRetriever`）、查询改写（`QueryTransformer`）、
查询扩展（`QueryExpander`）、结果合并（`DocumentJoiner`）、提示增强（`QueryAugmenter`）都是可插拔组件，
本示例只用了基于向量库的 `VectorStoreDocumentRetriever`，其余用默认。需要 `spring-ai-rag` 依赖。
示例里用 `LoggingRetriever` 装饰器把“自动检索到了哪些文档”打印出来。参考官方文档
[Retrieval Augmented Generation / RetrievalAugmentationAdvisor](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html#_retrievalaugmentationadvisor)。

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
