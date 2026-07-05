# springai-agentdemo v1.0.0

首个正式版。基于 **Spring AI 2.0** 的学习示例项目，核心交付物是一个可直接运行的终端编码智能体 **`springai-code-tui`**。

**本次发布的下载物是两个自包含运行包（解压即用，无需构建）：**

- `springai-code-tui-1.0.0-dist.tar.gz`（macOS / Linux 首选）
- `springai-code-tui-1.0.0-dist.zip`（Windows 首选）

两者内容一致：启动脚本 + 主 jar + 全部运行期依赖（`lib/`）+ `LICENSE`/`NOTICE`/`README`。

---

## 📦 仓库包含的模块

| 模块 | 说明 |
| --- | --- |
| **springai-code-tui** ⭐ | 终端编码智能体（多 provider + 子 agent + 计划/任务面板 + 技能热加载 + 会话持久化）。**本次发布的可下载运行物。** |
| springai-core-demo | Spring AI 原始 API 教学：ChatClient / 流式 / 提示模板 / 结构化输出 / Embedding / RAG。 |
| springai-agent-demo | 智能体教学：工具调用、多步 agent、会话记忆、TodoWrite、技能工具。 |
| springai-boot-demo | Spring Boot 自动装配版对照（starter + application.properties）。 |
| springai-jline-demo | JLine 终端交互基础示例。 |

> demo 模块请 clone 源码后 `mvn` 运行，见各模块 README；下载包只含 `springai-code-tui`。

---

## ✨ springai-code-tui 亮点

- **多 provider**：按环境变量激活 **DeepSeek / Anthropic / OpenAI**，首个可用者激活；`/model` 运行时在当前 provider 的模型间切换；子 agent 可用 `provider:model` 跨 provider 路由。
- **单栏内联 TUI**（TamboUI 0.4.0，纯 Java）：对话滚动区（流式 token 内联渲染 + 工具调用活动 + 子 agent 嵌套行）、**📋 计划面板**（主 agent 的 todo）、**⟐ 任务面板**（本回合派出的子 agent 状态 ▶/✓/✗ + 当前工具）、输入框、底部状态栏。
- **智能体工具**：文件读写（受工作区 root 沙箱约束）、Shell、Grep、Glob、TodoWrite、联网抓取（SmartWebFetch）、向用户反问（AskUserQuestion，多选拍板）、子 agent（`Task`）。
- **子 agent（Task）**：内置 `explore` / `plan` / `bash` / `general-purpose` 四类，串行前台执行，内部工具活动带 taskId 内联嵌套显示。
- **技能（Skills）**：`/skills` 查看清单（模型按需自动调用）、`/skill` 为本条消息指定、`/reload` 运行期重扫技能目录（新增/删除 `SKILL.md` 无需重启即生效）。
- **会话持久化 + 恢复**：按项目隔离落盘 `<项目根>/.codetui/sessions/`；默认启动开全新会话，`-c` / `--continue` 恢复最近一次并**把上次对话直观回放进界面**（仿 Claude Code `--continue`），`/continue` 续跑上次未完成的计划。
- **上下文管理**：窗口记忆多轮会话、token 用量估算（`/context`）、超阈值自动压缩 + `/compact` 手动压缩；把 cwd / git 状态 / 模型名注入系统提示做 grounding。

---

## ⚠️ 安全声明（务必阅读）

**本工具不是安全沙箱。** 它给智能体开放了对本机文件系统和 shell 的实质性访问能力：

- **只有文件读写工具受工作区 root 目录沙箱约束**（越界路径被拒）。
- **Shell / Grep / Glob 不受任何 root 限制**：`ShellTools` 可执行任意 shell 命令（含 `rm -rf`、`curl | bash` 等）；Grep/Glob 传绝对路径或 `../` 可读到 root 之外。
- **联网出口无过滤**：SmartWebFetch 可发起任意对外 HTTP 请求，被提示注入时可能外泄本地读到的内容。
- **子 agent 同权**：`Task` 派出的子 agent 复用同一套未沙箱工具。

**使用建议**：只在**可随意丢弃、且已被 git 干净纳管**的目录中运行；**不要**在 `$HOME`、系统关键目录或重要仓库根目录直接运行。按 Apache 2.0《AS IS》条款，本软件不提供任何担保，风险自担。

---

## 🚀 下载与运行（解压即用）

**前置**：JDK 21+；至少配置一个 provider 的 API key。

```bash
# 1) 解压（二选一）
tar xzf springai-code-tui-1.0.0-dist.tar.gz
# 或： unzip springai-code-tui-1.0.0-dist.zip

# 2) 配置 key（至少一个；可同时配多个，用 /model 切换）
export DEEPSEEK_API_KEY=你的key        # DeepSeek（默认 deepseek-v4-pro，另有 v4-flash）
# export ANTHROPIC_API_KEY=你的key     # Anthropic（默认 claude-opus-4-8，另有 fable-5/sonnet-5/haiku-4-5）
# export OPENAI_API_KEY=你的key        # OpenAI（gpt-5.5 等）
# 可选自定义网关：DEEPSEEK_BASE_URL / ANTHROPIC_BASE_URL / OPENAI_BASE_URL

# 3) 切到一个可随意丢弃、已被 git 纳管的项目目录再运行
cd /path/to/some/disposable/project
/path/to/springai-code-tui-1.0.0/bin/code-tui           # Windows：bin\code-tui.cmd
# 或直接： java -jar /path/to/springai-code-tui-1.0.0/springai-code-tui.jar
```

解压后目录结构：

```
springai-code-tui-1.0.0/
├── bin/code-tui         # 启动脚本（sh；自动定位安装目录与 java）
├── bin/code-tui.cmd     # 启动脚本（Windows）
├── springai-code-tui.jar
├── lib/*.jar            # 全部运行期依赖
├── LICENSE  NOTICE  README.md
```

**日志位置**：经 `bin/code-tui` 启动写到安装目录下 `logs/`（只读则回退 `~/.codetui/logs/`）；直接 `java -jar` 写到 `~/.codetui/logs/`。**不写进当前项目目录**。滚动：单文件 10MB、留 7 天、上限 100MB。

---

## ⌨️ 斜杠命令与操作键

| 命令 | 行为 |
| --- | --- |
| `/model` | 打开模型选择器，在当前 provider 的模型间切换 |
| `/continue` | 接着上次未完成的计划续跑（配合 `-c` 恢复） |
| `/compact` | 手动压缩会话历史 |
| `/context` | 查看上下文用量（事件数 / token） |
| `/skill` `/skills` `/reload` | 指定 / 查看 / 重扫技能 |
| `/help` `/exit` | 帮助 / 退出 |

| 按键 | 行为 |
| --- | --- |
| Enter | 发送 |
| `\` + Enter | 输入框内换行 |
| ↑ / ↓ | 回溯 / 前进已提交的历史消息 |
| Esc | 取消当前回合 |
| Ctrl+C | 退出 |

---

## 🐛 本版本关键修复

- **恢复会话（`-c`）首个请求 400**：历史里残留「孤儿 tool 结果」（如被中断的 AskUserQuestion）会被 DeepSeek 拒绝；加载时净化成 API 合法序列（`SessionEvents.sanitize`）。
- **日志不再污染工作目录**：从 `${user.dir}/logs` 改为安装目录 / `~/.codetui/logs`。
- **TodoWrite 频繁失败**：库工具「双层 todos」schema 被薄适配器摊平为单层，消除模型绑定 400。
- 欢迎横幅重设计 + 版本号、技能运行期热加载、取消子 agent 后状态栏卡死修复等。

---

## 🔐 校验（SHA-256）

```
552127ad0fd4c955da0a64cc6fc21c1c563f7a2b424de05b1a514bb8427300a3  springai-code-tui-1.0.0-dist.tar.gz
3207d744bb5e5cf1076c89296a7226d7dfe9915ba6253946a001c31a7da892ee  springai-code-tui-1.0.0-dist.zip
```

```bash
shasum -a 256 -c <<'EOF'
552127ad0fd4c955da0a64cc6fc21c1c563f7a2b424de05b1a514bb8427300a3  springai-code-tui-1.0.0-dist.tar.gz
3207d744bb5e5cf1076c89296a7226d7dfe9915ba6253946a001c31a7da892ee  springai-code-tui-1.0.0-dist.zip
EOF
```

---

## 🧩 已知限制

- 滚动区不可翻页（依赖终端自身 scrollback）；子 agent 串行执行（暂无并行/后台）。
- 工具沙箱不完整（见上「安全声明」）——真沙箱列为后续增强。
- 极端宽字符 / emoji ZWJ 组合下输入框光标可能轻微偏移。

## 📄 许可

[Apache License 2.0](LICENSE)。发布包内随附 `LICENSE` 与 `NOTICE`（含所分发第三方库：Spring AI / Spring Boot / spring-ai-community 为 Apache 2.0，TamboUI 为 MIT）。

**环境**：JDK 21+，macOS / Linux / Windows。
