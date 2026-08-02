# Spring AI 2.0 学习示例 & 命令行编码智能体

[![CI](https://github.com/javaside/springai-agentdemo/actions/workflows/ci.yml/badge.svg)](https://github.com/javaside/springai-agentdemo/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/javaside/springai-agentdemo?display_name=tag&sort=semver&label=release)](https://github.com/javaside/springai-agentdemo/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/javaside/springai-agentdemo/total?label=downloads)](https://github.com/javaside/springai-agentdemo/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net/)

**[⬇️ 下载最新版](https://github.com/javaside/springai-agentdemo/releases/latest)** ·
[变更日志](CHANGELOG.md) ·
[参与贡献](CONTRIBUTING.md) ·
[安全策略](SECURITY.md) ·
[code-tui 详细文档](springai-code-tui/README.md)

---

## ⭐ springai-code-tui —— 本项目的重点

一个**真正能用的命令行编码智能体**：在终端里读写代码、跑测试、查资料、派子 agent 干活。仓库里的其他模块是通往它的教学阶梯，**它才是主角**。

### 下载即用（无需构建）

到 **[Releases](https://github.com/javaside/springai-agentdemo/releases/latest)** 拿自包含运行包，解压后配一个 API Key 就能跑：

```bash
# macOS / Linux
tar xzf springai-code-tui-*-dist.tar.gz && cd springai-code-tui-*/
export DEEPSEEK_API_KEY=你的key
bin/code-tui                      # Windows 用 bin\code-tui.cmd
```

包内含启动脚本 + 主 jar + 全部依赖 + `LICENSE`/`NOTICE`/`README`，只需 **JDK 17+**。每版的 SHA-256 校验和见对应[发版说明](CHANGELOG.md)。

> ⚠️ **先读安全声明**：它给智能体开放了本机文件系统与 shell 的实质访问。有副作用的调用会在**执行前**弹审批面板请你确认，但**这不是安全沙箱**——权限层管的是「要不要做这一步」，不是「能做到多远」；你一旦批准，那次调用就以你的用户权限执行、不受目录约束。请只在可随意丢弃、且已被版本控制干净纳管的目录中运行。详见 [模块 README 的安全声明](springai-code-tui/README.md) 与 [SECURITY.md](SECURITY.md)。

### 能做什么

| | |
|---|---|
| **多 provider** | DeepSeek / 智谱 GLM / 通义千问 / Anthropic / OpenAI，`/model` 运行时切换，模型清单可经 `*_MODELS` 自定义 |
| **工具** | 文件读写、Shell、Grep/Glob、联网抓取（webFetch）、**联网搜索**（博查中文 + Brave 英文，模型按内容语言自选）、向用户反问 |
| **视觉输入** | 支持视觉的模型**真能看见图**：你自己贴的（输入框里写路径、或把文件从访达/桌面拖进终端）与工具产的（`Read` 一张 png、MCP 截图）。**图片从不进会话记忆**，落盘的只是文本引用，聊多久上下文都不累积；有硬上限（每请求 ≤3 张用户图 + ≤1 张工具图，每回合累计 ≤12 张·次），路径是自动识别的、误附时 `Ctrl+X` 撤销 |
| **权限管理** | 有副作用的调用**执行前**弹审批面板（允许一次 / 本会话 / 永久 / 拒绝 / 中断），规则写 `permissions.json`，`/permissions` 面板可就地删；另有一层 **allow 规则与 BYPASS 都盖不住**的内置底线。匹配放宽只在 deny 方向（认大小写与符号链接），allow 只认原写法 |
| **计划模式** | `Shift+Tab` 在「默认 / 自动接受编辑 / 计划模式」三档间循环，当前档位常驻状态栏。计划模式下只放行只读调查，写与命令一律**拒绝**（不是询问），模型改用 `ExitPlanMode` 交一份计划，经你批准后才动手；也可用 `--permission-mode plan` 启动 |
| **子 agent** | `Task` 单个委派 / `ParallelTasks` 并发派发，内置 explore / plan / bash / general-purpose 四类 |
| **MCP** | 接入外部工具：本地 stdio 子进程 + **远程 Streamable HTTP**（headers 支持 `${ENV_VAR}` 插值），`/mcp` 面板运行期启停 |
| **上下文** | 事件溯源会话记忆 + 回合感知压缩、跨会话长期记忆、项目指令（`AGENTS.md`）、`-c` 恢复上次会话 |
| **界面** | 单栏对话式 TUI：流式输出、工具活动行、📋 计划面板、⟐ 任务面板、状态栏 |

---

## 仓库里还有什么

一个面向初学者的 **Spring AI 2.0** 演示项目，由浅入深分三层：

**① 原理对比层**（核心教学）——同样拿到一个能用的 `ChatClient`，看「自己接线」与「自动装配」的差别：

- `springai-core-demo` / `springai-agent-demo` —— **纯 Java，使用 Spring AI 原始 API**，所有对象都自己手动 `new`，**看得见每一步**；
- `springai-boot-demo` —— **用 Spring Boot starter 演示「自动装配」**，同样的对象一行 `new` 都不用写。

两边一对照，你就能彻底搞懂「自动配置（auto-configuration）到底替你做了什么」——这正是大多数初学者最容易犯迷糊的地方。

**② 终端基础层** —— `springai-jline-demo`：JLine 3 `Terminal` 接口入门，为终端界面打底。

**③ 综合应用层** —— `springai-code-tui`：把前两层综合成上面那个编码智能体（[回到顶部](#-springai-code-tui--本项目的重点)）。想学「这些零件怎么拼成一个真东西」，读它的源码。

- **对话模型**：[DeepSeek](https://platform.deepseek.com/)（国内可直连、价格低）；`springai-code-tui` 额外支持 智谱 GLM / [通义千问](https://bailian.console.aliyun.com/)（百炼）/ Anthropic / OpenAI（各家模型清单可经 `*_MODELS` 环境变量配置，首项为默认模型）
- **向量模型**：本地 ONNX 模型（无需 API Key，离线运行）—— 因为 DeepSeek 官方 API 只提供对话、不提供向量
- **运行方式**：core/agent/boot 为控制台菜单（输入数字选示例）；jline/code-tui 为交互式终端程序

## 技术栈

| 组件 | 版本 |
|------|------|
| Spring AI | 2.0.0 |
| Spring Boot | 4.0.7（仅 boot 模块使用） |
| Java | 17（基线；JDK 21+ 时 jline 自动启用 FFM 终端后端） |
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
├── springai-boot-demo             【自动装配 · Spring Boot】对比演示
│   └── starter 自动配置好一切，业务代码只需注入
│       1.自动配置揭秘★  2.极简对话  3.MCP 客户端
│
├── springai-jline-demo            【终端基础】JLine 3 Terminal 接口入门
│   └── 单文件逐节演示：原始/回显模式、光标、颜色、按键读取、窗口尺寸…
│
└── springai-code-tui              【综合应用】命令行编码智能体（TUI）
    └── 多 provider（DeepSeek/智谱/千问/Anthropic/OpenAI）+ 子 agent（Task + ParallelTasks 并行）+ 技能
        + 工具调用（文件/Shell/Grep/Glob/联网/反问）+ MCP（接入外部工具）+ 计划/任务面板 + 会话压缩
        + 跨会话长期记忆（AutoMemoryTools）+ 项目指令（AGENTS.md）
        + 权限管理（审批面板 + 规则 + 内置底线 + 计划模式）
        + 视觉输入（自己贴图/拖拽 + 工具产图，图片不入会话记忆，有硬上限）

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
java -jar springai-boot-demo/target/springai-boot-demo-1.6.0.jar

# 终端基础示例（JLine 3）
java -jar springai-jline-demo/target/springai-jline-demo.jar

# 综合应用：命令行编码智能体（先 cd 到一个可随意丢弃的目录再运行，详见其 README 安全声明）
java -jar springai-code-tui/target/springai-code-tui.jar
```

core / agent / boot 启动后按菜单输入序号，`0` 退出；jline / code-tui 为交互式终端程序。

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
- [springai-jline-demo/README.md](springai-jline-demo/README.md)
- [springai-code-tui/README.md](springai-code-tui/README.md)

## 参与与安全

- **变更日志**：[CHANGELOG.md](CHANGELOG.md)（每版发版说明的索引，含下载物与 SHA-256 校验和）
- **贡献指南**：[CONTRIBUTING.md](CONTRIBUTING.md)（**测试命令必须带 `-pl` 模块作用域**、真机冒烟测试的 env 门控、spec → plan → TDD 的改动流程）
- **安全策略**：[SECURITY.md](SECURITY.md)。发现漏洞请**不要开公开 issue**，发邮件到 283323279@qq.com。
  注意其中「已知且被接受的风险」一节——`springai-code-tui` **无沙箱**是设计如此，不作为漏洞受理。

## 许可

本项目以 [Apache License 2.0](LICENSE) 开源（见 [`LICENSE`](LICENSE)、[`NOTICE`](NOTICE)）。

选它的理由：与所依赖的 Spring AI / spring-ai-community 全栈一致（均 Apache 2.0），并附带显式专利授权。
所依赖的第三方库（Spring AI、Spring Boot、spring-ai-community 为 Apache 2.0，TamboUI 为 MIT）均为宽松许可；
`springai-code-tui` 的发布包（`-Pdist`）会分发它们的 jar，故包内随附 `LICENSE` 与 `NOTICE`。

> `springai-code-tui` 给智能体开放了对本机文件系统与 shell 的实质访问、且**非安全沙箱**——按 Apache 2.0 «AS IS»
> 条款不提供任何担保，请阅读该模块 README 的「安全声明」后自担风险使用。
