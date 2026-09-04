# Java 后端零基础转 Agent 开发实战教程

[![CI](https://github.com/javaside/springai-agentdemo/actions/workflows/ci.yml/badge.svg)](https://github.com/javaside/springai-agentdemo/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/javaside/springai-agentdemo?display_name=tag&sort=semver&label=release)](https://github.com/javaside/springai-agentdemo/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/javaside/springai-agentdemo/total?label=downloads)](https://github.com/javaside/springai-agentdemo/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net/)

**[⬇️ 下载 code-tui](https://github.com/javaside/springai-agentdemo/releases/latest)** ·
[变更日志](CHANGELOG.md) ·
[参与贡献](CONTRIBUTING.md) ·
[安全策略](SECURITY.md)

---

这是一个 Java 后端开发者**从零学会 Agent 开发**的实战项目。

路线只有一条：先从 **Spring AI 2.0** 学起，把 Agent 开发的基本概念一个个跑通——对话、提示词模板、
流式输出、结构化输出、RAG、工具调用、对话记忆、自动装配、终端界面；再用这些知识去读透一个
真正的编码智能体 **springai-code-tui**，完成实战。走完这条路，Agent 开发对你来说就不再是黑盒。

全项目只有一个核心公式——**Agent = 模型 + 工具 + 记忆 + 循环**。五个模块按这条路线排列
（仓库里另有一个构建辅助模块 `springai-tamboui-inline-patch`，用于给 TamboUI 打内联补丁，不在学习路线上）：

| 步 | 模块 | 对应公式 | 一句话 |
|----|------|---------|--------|
| ① | [springai-core-demo](springai-core-demo/README.md) | 模型 | 对话、模板、流式、结构化输出、RAG |
| ② | [springai-agent-demo](springai-agent-demo/README.md) | 工具 + 记忆 + 循环 | `@Tool` 工具调用、对话记忆、多步 Agent |
| ③ | [springai-boot-demo](springai-boot-demo/README.md) | （工程化） | Spring Boot 自动装配、MCP |
| ④ | [springai-jline-demo](springai-jline-demo/README.md) | （界面） | JLine 3 终端界面基础 |
| ⑤ | [springai-code-tui](springai-code-tui/README.md) | 全部组装 | 真正的命令行编码智能体 |

> **两类读者**：想学 Agent 开发 → 从下方第 1 步开始；只想用终端编码智能体 →
> [⬇️ 下载 code-tui](https://github.com/javaside/springai-agentdemo/releases/latest)
> （发布包附 SHA-256 校验和，安装见[模块 README](springai-code-tui/README.md)）。

## 学习路线

> 全程用 [DeepSeek](https://platform.deepseek.com/)（国内可直连、价格低）做对话模型，
> 一个 API Key 就能跑通全部核心示例（第 3 步的 MCP 示例为可选，需另备 Node.js 环境）。
> 预期投入：第 1–4 步每步半天左右（第 1 步最长，7 个示例 + 首次启动下载 90MB 模型；第 4 步最短）；
> API 费用预计在个位数人民币（示例 prompt 都很短）。**第 5 步例外**：默认模型偏强推理、加上子 Agent
> 并发，重度使用一天的花费是几十元量级——可在 `/model` 切换更便宜的模型、用 `/context` 盯用量。

### 第 1 步 · springai-core-demo —— 学会「跟模型说话」

先把 Spring AI 当成一个普通的 HTTP 客户端库来学。纯 Java、不依赖 Spring Boot，
所有对象在 `CoreDemoApplication.main` 里手动创建，每一步都看得见：

| # | 示例 | 你将学到 |
|---|------|---------|
| 1 | 基础对话 | `ChatClient` 的 `prompt().user().call().content()` |
| 2 | 提示词模板 | 用 `{占位符}` + `param()` 组织提示词，`system()` 设定角色 |
| 3 | 流式输出 | `stream()` 打字机式输出（`Flux`，详见模块 README） |
| 4 | 结构化输出 | `.entity(...)` 让模型直接返回 Java 对象 |
| 5 | 文本向量化 | 本地 `EmbeddingModel` 把文字转向量、算相似度 |
| 6 | RAG 检索增强（手写） | 自己「存知识 → 检索 → 拼上下文 → 提问」，看清 RAG 每一步 |
| 7 | 模块化 RAG（advisor） | `RetrievalAugmentationAdvisor` 一行接入，对比示例 6 |

示例 5–7 是**选修支线**——给模型喂知识（RAG）。赶时间可先跳过、直奔第 2 步，需要时再回来
（但仍会在首次启动时下载 90MB 模型）。

**本步核心认知**：对话模型的 API 本质是「文本进、文本出」；模板、向量、RAG 都是围绕这一点叠加的工程手段。

**✅ 自测**：把 `ChatDemo` 里写死的问题换成你自己的问题再跑一次——你的第一个 AI 接口就通了。

### 第 2 步 · springai-agent-demo —— 学会「让模型干活」

有了对话能力，接下来教模型调用你的 Java 代码。依然是纯 Java、手动创建。

**建议先跑一遍示例 3「多步 Agent」**——看模型自己规划、连续调用工具完成任务，
直观感受一次「智能」如何发生，再回头拆解零件。

核心（Spring AI 原生）：

| # | 示例 | 你将学到 |
|---|------|---------|
| 1 | 工具调用 | `@Tool` 定义方法、`tools(...)` 注册，模型按需调用 Java 代码 |
| 2 | 对话记忆 | `MessageWindowChatMemory` + 记忆 Advisor，多轮对话不忘上文 |
| 3 | 多步 Agent | 模型自动规划、连续调用多个工具完成一个任务（综合题） |
| 4 | Advisor 顺序 | 记忆与工具 Advisor 的顺序决定中间消息是否入记忆（2 条 vs 6 条，一跑便知） |

进阶（第三方库增强件）：

| # | 示例 | 你将学到 |
|---|------|---------|
| 5 | 工具搜索 | `ToolSearchToolCallingAdvisor` 按需发现工具，工具多时省 token |
| 6 | Skill 技能 | 把「可复用的领域指令」按需注入对话 |
| 7 | TodoWrite 任务清单 | 让 Agent 显式维护任务列表并实时展示进度 |

**本步核心认知**：工具 + 记忆 + 模型自己的决策循环，就是 Agent 的全部。
**工程提醒**：模型能「调用」你的代码，不等于它会「使用」你的代码——参数怎么设计、错误怎么反馈，都是工程问题。

**🏅 里程碑**：走到这里，你已经能独立写一个小型业务 Agent 了——模块里的示例工具就是
请假/会议室/快递查询这类普通业务接口，换成你自己的服务，就是生产雏形。

**✅ 自测**：模仿示例里的 `OfficeTools`，给你自己的业务写一个 `@Tool`（比如查库存、查工单），
再让模型调用它。

### 第 3 步 · springai-boot-demo —— 搞懂「自动装配替你做了什么」

前两步你手动 `new` 了 `DeepSeekApi → ChatModel → ChatClient`。这一步换用 Spring Boot starter，
一行 `new` 都不写：

| # | 示例 | 你将学到 |
|---|------|---------|
| 1 | 自动配置揭秘（建议先看）| 打印自动配置好的 Bean 来自哪个 jar（零成本，不调模型） |
| 2 | 极简对话 | 注入即用的 `ChatClient`（由 starter 自动配置的 Builder 构建），底层全自动 |
| 3 | MCP 客户端 | 几行 properties 即可接入外部 MCP Server（可选，需 Node.js；MCP 是什么见模块 README）|

**本步核心认知**：starter = 原始库 + 自动配置。跑一遍「自动配置揭秘」，对照第 1 步手动创建的对象，
你就能弄清 Boot 替你做了什么。这也是初学者最容易迷糊的地方。

> 注：第 5 步的 code-tui 出于启动速度与装配可控性选择了**手动装配**（回到第 1、2 步的写法），
> 它从本步带走的是 MCP；Boot 自动装配的知识用在你自己的业务项目上。

### 第 4 步 · springai-jline-demo —— 学会「做终端界面」

Agent 需要 UI。JLine 3 的 `TerminalBasics` 是一个单文件示例，按 `Terminal` 接口 Javadoc 分块
逐节演示：创建与生命周期、输入输出、能力查询（颜色/控制序列）、原始模式。
交互块（读一行、等一键）会停下来等你操作。第 5 步的流式输出、计划面板、状态栏，
建立在本步的原始模式与按键读取上（窗口缩放等信号处理由 TUI 框架的 jline3 后端承担）。

**本步核心认知**：终端是一块可编程的画布——原始模式与按键读取是 TUI 的地基。

### 第 5 步 · springai-code-tui —— 拼成一个真正的 Agent

前四步的零件，在这里以生产级形态组装。**先说清体量**：code-tui 是一个近三万行、
二十多个功能域的真实工程，不是又一个 demo。第 5 步不是「读完」，而是「边用边读」，请合理安排时间。

| 你学过的概念 | 在 code-tui 里的样子 |
|---|---|
| 工具调用（第 2 步） | 文件读写、Shell、Grep/Glob、联网搜索（需另配搜索 Key）、网页抓取 |
| 多步 Agent（第 2 步） | 自动规划并连续调用工具完成编码任务 |
| 对话记忆（第 2 步） | 升级为事件溯源会话（每轮对话当事件追加存储、可重放）+ 自动压缩（接入 Advisor 链），另有跨会话长期记忆与 `AGENTS.md` 项目指令，详见模块 README |
| MCP（第 3 步） | 本地 stdio + 远程 Streamable HTTP，`/mcp` 面板支持运行期启停 |
| 终端基础（第 4 步） | 单栏对话式 TUI：流式输出、计划面板、任务面板、状态栏 |

另有学习路线之外的进阶能力：多 provider 切换、子 Agent（`Task`/`ParallelTasks` 并发委派）、
权限审批（四档权限模式 + 任何 allow 规则都盖不住的内置底线）、回合中插话、视觉输入（图片不写入对话记忆）。
完整功能与配置见[模块 README](springai-code-tui/README.md)。

**怎么学这一步**：先下载发布包，把它当工具用一天；然后对照 [implementation-map.md](springai-code-tui/docs/implementation-map.md)
（按功能列出「入口 → 关键类 → 实现要点」，主要面向要改代码的读者）阅读源码——你会不断遇到
前四步学过的概念，只是换成了生产级实现；读不懂的部分可以先跳过。
这一步的难点更多在终端 UI 与权限工程，而不是新的 Agent 概念。

**✅ 自测**：用它干一天活后回答——执行前后各看一次 `/context`，占用变化最大的分桶是哪个？为什么长会话没把上下文撑爆？

> **想直接用它，不想学？** [⬇️ 下载发布包](https://github.com/javaside/springai-agentdemo/releases/latest)
> （JDK 17+，填一个 API Key 即可用；发布包附 SHA-256 校验和），安装与配置步骤见[模块 README](springai-code-tui/README.md)。
> ⚠️ 它不是安全沙箱：会给 Agent 开放本机文件系统与 shell 的实质性访问，请只在可随意丢弃、
> 被 Git 干净纳管的目录中运行（详见[安全策略](SECURITY.md)）。

## 运行这个项目

### 准备

- JDK 17+、Maven 3.9+（只下载发布包的读者可跳过 Maven）
- 一个 [DeepSeek](https://platform.deepseek.com/) API Key：`export DEEPSEEK_API_KEY=<你的 Key>`
  （Windows PowerShell：`$env:DEEPSEEK_API_KEY="<你的 Key>"`）
- 技术栈：Spring AI 2.0.0（教学路线主线）；Spring Boot 4.0.7（仅第 3 步 boot 模块作为依赖使用，父工程用它统一第三方库版本）

### 构建并运行

```bash
git clone https://github.com/javaside/springai-agentdemo.git
cd springai-agentdemo
mvn clean package
```

```bash
# 第 1–3 步：控制台菜单，输入序号选示例
# ⚠️ core/boot 首次运行会在菜单出现前静默下载约 90MB 模型（日志级别 WARN，期间无任何输出，请耐心等待）
java -jar springai-core-demo/target/springai-core-demo.jar
java -jar springai-agent-demo/target/springai-agent-demo.jar
java -jar springai-boot-demo/target/springai-boot-demo-<version>.jar    # fat jar，文件名带版本号

# 第 4 步：交互式终端程序，需在真实终端运行（文件名同样带版本号）
java -jar springai-jline-demo/target/springai-jline-demo-<version>.jar

# 第 5 步 code-tui：从可随意丢弃、被 Git 干净纳管的项目目录启动（详见该步说明）
cd /path/to/disposable-git-project
java -jar /path/to/springai-agentdemo/springai-code-tui/target/springai-code-tui.jar
```

### 常见问题

- **首次启动 core/boot 程序时**会从 GitHub 下载本地模型文件（约 90MB，与是否运行向量/RAG 示例无关）。
  国内网络慢时可自备代理；也可用配置覆盖下载地址（boot 模块的
  `spring.ai.embedding.transformer.onnx.model-uri` / `tokenizer.uri`）。
- **想换模型**：Spring AI 的 API 与模型解耦。原始 API 模块：换掉 `spring-ai-deepseek` 依赖，
  `main` 里改用对应 `XxxApi`/`XxxChatModel`；boot 模块：换 starter，改 `spring.ai.<model>.*` 配置。
  业务代码（用 `ChatClient` 的部分）基本不用动。
- **code-tui 还额外支持**：智谱 GLM / 通义千问 / Anthropic / OpenAI / OpenCode Go，
  各家模型清单可经 `*_MODELS` 环境变量配置。
- **示例跑不通**：请附上完整报错信息，提交 [GitHub Issue](https://github.com/javaside/springai-agentdemo/issues)。

## 各模块详细文档

- [springai-core-demo/README.md](springai-core-demo/README.md)
- [springai-agent-demo/README.md](springai-agent-demo/README.md)
- [springai-boot-demo/README.md](springai-boot-demo/README.md)
- [springai-jline-demo/README.md](springai-jline-demo/README.md)
- [springai-code-tui/README.md](springai-code-tui/README.md)

## 参与与安全

- **变更日志**：[CHANGELOG.md](CHANGELOG.md)（各版本发布说明的索引，含发布文件与 SHA-256 校验和）
- **贡献指南**：[CONTRIBUTING.md](CONTRIBUTING.md)（测试命令必须带 `-pl` 模块作用域、真机冒烟测试的 env 门控、spec → plan → TDD 的改动流程）
- **安全策略**：[SECURITY.md](SECURITY.md)。发现漏洞请**勿提交公开 Issue**，发邮件到 283323279@qq.com。
  注意其中「已知且被接受的风险」一节——`springai-code-tui` 无沙箱是有意为之，不作为漏洞受理。

## 许可

本项目以 [Apache License 2.0](LICENSE) 开源（另见 [NOTICE](NOTICE)）。

选它的理由：与所依赖的 Spring AI / spring-ai-community 的许可保持一致，附带显式专利授权；
`springai-code-tui` 的发布包（`-Pdist`）会分发第三方 jar，故包内随附 `LICENSE` 与 `NOTICE`。

> `springai-code-tui` 开放了本机文件系统与 shell 的实质性访问，且**不是安全沙箱**——按 Apache 2.0 的
> 「AS IS」条款不提供任何担保。请先阅读该模块 README 的「安全声明」，再自担风险使用。
