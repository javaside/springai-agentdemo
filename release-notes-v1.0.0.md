# springai-agentdemo v1.0.0

基于 **Spring AI 2.0** 的学习示例项目，含一个可运行的终端编码智能体 **`springai-code-tui`**。

## ✨ 亮点（springai-code-tui）

- **多 provider**：按环境变量激活 DeepSeek / Anthropic / OpenAI，`/model` 运行时切换（子 agent 可 `provider:model` 跨家路由）。
- **单栏内联 TUI**（TamboUI）：流式 token 内联渲染、工具调用活动、📋 计划面板、⟐ 任务面板（子 agent 状态）。
- **智能体工具**：文件读写 / Shell / Grep / Glob / TodoWrite / 联网抓取 / 向用户反问 / 子 agent（`Task`）。
- **技能热加载**：`/skills` 查看、`/skill` 指定、`/reload` 运行期重扫技能目录（无需重启）。
- **会话持久化 + 恢复**：按项目隔离落盘；`-c` / `--continue` 恢复最近一次会话并**把上次对话直观回放进界面**，`/continue` 续跑未完成的计划。
- **上下文管理**：窗口记忆、token 估算（`/context`）、超阈值自动压缩 + `/compact`。

## ⚠️ 安全声明（务必阅读）

**本工具不是安全沙箱**：只有文件读写受项目根目录约束；Shell / Grep / Glob **不受限**，模型可执行任意命令、读写本机任意可达路径。
**请只在可随意丢弃、且已被 git 干净纳管的目录中运行**，不要在 `$HOME` 或重要仓库根目录直接运行。详见模块 README 的「安全声明」。

## 📦 下载与运行（解压即用，需 JDK 21+）

```bash
tar xzf springai-code-tui-1.0.0-dist.tar.gz     # 或解压 .zip
export DEEPSEEK_API_KEY=你的key                  # 或 ANTHROPIC_API_KEY / OPENAI_API_KEY
cd /path/to/some/disposable/project             # 切到一个可随意丢弃、已被 git 纳管的项目
/path/to/springai-code-tui-1.0.0/bin/code-tui   # Windows：bin\code-tui.cmd
```

日志写到安装目录下 `logs/`（只读则回退 `~/.codetui/logs`），不污染当前项目目录。

## 🔐 校验（SHA-256）

```
552127ad0fd4c955da0a64cc6fc21c1c563f7a2b424de05b1a514bb8427300a3  springai-code-tui-1.0.0-dist.tar.gz
3207d744bb5e5cf1076c89296a7226d7dfe9915ba6253946a001c31a7da892ee  springai-code-tui-1.0.0-dist.zip
```

## 📄 许可

[Apache License 2.0](LICENSE)。发布包内随附 `LICENSE` 与 `NOTICE`（含所分发第三方库的许可声明）。
