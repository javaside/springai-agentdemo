# AGENTS.md

本文件是给在本仓库工作的 AI 编码智能体（Claude Code、Codex、code-tui 等）的项目指令，
约定与 [CONTRIBUTING.md](CONTRIBUTING.md) 一致；该文档始终是更权威的细节来源，冲突时以它为准。
本仓库自己的 `springai-code-tui` 启动时也会读取并注入本文件（项目级一层）。

## 项目概览

Java 后端开发者从零学会 Agent 开发的教学项目（Apache-2.0），主线是 Spring AI 2.0：
前四个模块按学习路线递进（对话 → 工具/记忆/循环 → Boot 自动装配 → 终端界面），
第五个模块 `springai-code-tui` 把全部知识组装成一个近三万行的生产级命令行编码智能体。

- 技术栈：Java 17、Maven 3.9+、Spring AI 2.0.x（BOM 统一版本；core/agent 模块**不用** Spring Boot）。
- 文档语言：中文（注释、错误消息、提交正文均为中文，这是仓库常态）。

## 仓库结构

| 模块 | 角色 | 测试 |
| --- | --- | --- |
| `springai-core-demo` | 第①步：原始 API（对话/模板/流式/结构化输出/RAG） | 无 |
| `springai-agent-demo` | 第②步：`@Tool` 工具调用、对话记忆、多步 Agent | 少量 |
| `springai-boot-demo` | 第③步：Spring Boot starter 自动装配 + MCP | 无 |
| `springai-jline-demo` | 第④步：JLine 3 终端基础 | 无 |
| `springai-code-tui` | 第⑤步：编码智能体（主要开发阵地，绝大多数测试在这里） | 全量 |
| `springai-tamboui-inline-patch` | 构建辅助：TamboUI 兼容层/shadow，**不发布到远程仓库** | 有 |

读 code-tui 源码从 [springai-code-tui/docs/implementation-map.md](springai-code-tui/docs/implementation-map.md)
入手（按功能列「入口 → 关键类 → 实现要点」）。

## 构建与测试命令

**测试命令必须带模块作用域与 `-am`，整仓 `mvn test` 会被空 demo 模块打挂，禁止使用：**

```bash
# 全量测试（code-tui 及其依赖）
mvn -pl springai-code-tui -am test

# 单个测试类
mvn -pl springai-code-tui -am test -Dtest='SomeTest' -Dsurefire.failIfNoSpecifiedTests=false
```

两条硬约束，缺一必挂：

- `-am` 不可省：`springai-code-tui` compile 依赖兄弟模块 `springai-tamboui-inline-patch`，
  它不发布到远程仓库，不带 `-am` 时 Maven 会去 Central 解析然后失败。
- 单类命令的 `-Dsurefire.failIfNoSpecifiedTests=false` 不可省：`-Dtest` 会波及 `-am` 拉进来的
  兄弟模块，匹配不到测试时 surefire 默认视为失败。该参数只放宽这一种错误，不吞断言/编译失败。

```bash
# 编译其余模块（对齐 CI）
mvn -DskipTests package

# 发布包（产出 target/*-dist.tar.gz 与 .zip）
mvn -pl springai-code-tui -am package -Pdist
```

未改代码的打包一律加 `-DskipTests`。

## 代码约束

- **语言级别锁 17**（`maven.compiler.release=17`），不要用更高版本的语法/API。
- 跟着周围代码写：注释密度、命名、习惯用法以同文件为准。
- 注释写**为什么**，不写「做了什么」；绕过某个库的坑时把坑本身记下来（现象 + 实测证据）。
- 升级 `spring-ai.version` 属于高危改动：需同步 code-tui 内两个强绑定的 SDK client 版本，
  并排查硬编码 client 版本与传递依赖（详见根 pom 与 CHANGELOG 的历史教训）。

## 测试要求

- 先写测试，且必须**真的看到它红**；看不到红说明没测到目标行为。
- 断言要能被反向改动杀死（例如断言自定义错误前缀并断言不含默认消息片段，而非只 `contains("503")`）。
- 不确定测试是否有效时做一次变异测试：改坏实现确认变红，再改回来，并确认工作树干净。
- 预期 SEVERE/WARNING 日志的用例不许裸静音：用 patch 模块的
  `dev.tamboui.testutil.ExpectedLog`（capture 接管 → `awaitRecord` 显式断言）接管被测 logger。

## 依赖真实网络 / API key 的测试

真机冒烟测试一律 `@EnabledIfEnvironmentVariable` 门控，没配变量自动跳过不算失败：

`DEEPSEEK_API_KEY`、`BOCHA_API_KEY`、`BRAVE_API_KEY`、`DASHSCOPE_API_KEY`、`CODETUI_MCP_SMOKE_URL`。
新增这类测试沿用同一模式；**测试与文档里绝不能硬编码或明文暴露任何 key**。

已知 flaky：`CodingAgentSpikeTest.todoTurnIdBinding`（真实调用、耗时浮动可能超时）。
撞上先按 CONTRIBUTING.md 的命令单跑那一条确认，不要当成是自己改坏了。

## 提交与分支

- 提交信息 `type: 说明` 前缀（`feat:` / `fix:` / `refactor:` / `test:` / `docs:` / `release:`），正文中文。
- 一次提交只做一件事；功能与其测试放同一个提交。
- **不要直接提交到 `main`**：开 `feature/xxx` 分支，完成后 `--no-ff` 合并。

## 较大改动的流程

新功能、跨多文件重构，先落两份产物再动手（放在 `docs/superpowers/` 下）：

1. `specs/YYYY-MM-DD-<主题>-design.md` —— 目标、非目标、已核准的事实依据、被淘汰的备选及理由。
2. `plans/YYYY-MM-DD-<主题>.md` —— 拆成小任务，每步「先写失败的测试 → 红 → 最小实现 → 绿 → 提交」。

核心要求：凡是「实测得到的事实」都要写下证据（`javap` 输出、`curl` 结果、字节码片段），
不写未验证的推测。

## 文档与发布

- 发版说明写 `docs/release-notes/vX.Y.Z.md`，`CHANGELOG.md` 只加一行索引。
- 文档内链接一律写相对路径；GitHub Release 页面的绝对 URL 转换交给
  `scripts/publish-release-notes.py`，不要为 Release 页改源文件。
- 版权署名统一 `Copyright <年份> Xinghua Zhou`。

## 安全

- 发现安全问题**不要开公开 issue**，见 [SECURITY.md](SECURITY.md)。
- `springai-code-tui` 无沙箱是已知且被接受的风险，不作为漏洞受理。
