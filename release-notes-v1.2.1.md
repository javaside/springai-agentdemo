# springai-agentdemo v1.2.1

在 [v1.2.0](release-notes-v1.2.0.md) 基础上的**缺陷修复版**（patch）。核心交付物仍是终端编码智能体 **`springai-code-tui`**。本版修复两处影响体验的问题：**并行子 agent 取消后 `/continue` 续跑全部失败**，以及**报错后 `/exit` 退出卡 ~60s**。无新增功能、无破坏性变更，建议所有 v1.2.0 用户升级。

**下载物仍是两个自包含运行包（解压即用，无需构建）：**

- `springai-code-tui-1.2.1-dist.tar.gz`（macOS / Linux 首选）
- `springai-code-tui-1.2.1-dist.zip`（Windows 首选）

两者内容一致：启动脚本（`bin/`）+ 主 jar + 全部运行期依赖（`lib/`）+ `LICENSE`/`NOTICE`/`README`。运行时界面版本标识为 **`v1.2.1`**。

> 完整功能全景与⚠️安全声明见 [v1.2.0 发布说明](release-notes-v1.2.0.md) 与 [v1.0.0 发布说明](release-notes-v1.0.0.md)；本文只列出相对 v1.2.0 的变化。

---

## 🐛 修复

### 并行子 agent 取消后 `/continue` 续跑全部失败（竞态）

并行执行子 agent（`ParallelTasks`）期间按 Esc 取消、再用 `/continue` 续跑，会出现子 agent 一个都跑不起来。根因是取消路径三道防线被同时穿透：

- **取消不真停子 agent**：Reactor `dispose()` 不 interrupt 阻塞在网络 IO 的子 agent 工具线程，子 agent 继续跑完；
- **会话写入无回合闸门**：`turnId` 迟到过滤只在 UI 层，旧回合迟到的会话写入仍落进同一会话；
- **出站不净化**：活进程会话常驻内存、永不重载，残留的悬空 `tool_calls` / 孤儿 `tool` 结果会让下一条请求（含 `/continue`）持续 `400`、进程内无法自愈。

三层修复：

1. **出站净化** —— `submit` 发请求前先把会话裁到合法前缀，坏历史自愈、不再 `400`（干净时为 no-op）；
2. **取消真拆在飞子 agent** —— 回合取消时对在飞并行子 agent 线程池 `shutdownNow`（`Disposables.composite` 组合取消，立即返回、不阻塞回 IDLE）；
3. **busy 闸门纳入「在飞子 agent」** —— 旧子 agent 未清空前把 `/continue`、普通消息、出队三处都视为忙、排队而非并发写同一会话。

附带：修复在飞计数泄漏（`onSubagentStarted` 抛出会漏 `finally` 递减 → busy 闸门永久卡死、UI 锁死）；取消收尾期状态行提示「⟳ 等待已取消的子 agent 收尾…」，避免消息静默入队被误判卡死；`/continue` 提示词**工具中立化**——不再硬点串行 `Task`，改为按任务独立性自选 `ParallelTasks`（并行）/ `Task`（串行），使并行历史续跑不被逼回串行。

### 报错后 `/exit` 退出卡 ~60s

`main()` 结尾仅 `view.run()`、无 `System.exit`，`/exit` 停 TUI 后 JVM 需等所有**非 daemon** 线程消亡才退出。实测 OkHttp（OpenAI / 智谱 / Anthropic 的异步流式路径）会留下非 daemon 的 `OkHttp Dispatcher` 线程、keep-alive 60s → 进程在 `/exit` 后卡 ~60s（`real 60.17s` 复现）。报错后用户会立即 `/exit`，恰落在这 60s 窗口内，故表现为「报过错就卡很久」（本质是「最后一次请求后 60s 内退出即卡」）。

修复：`view.run()` 返回后强制 `System.exit(0)`（交互期崩溃走 `System.exit(1)`）。TUI 退出语义即「立即终止」：会话已按事件原子落盘、无待刷新状态，终端已由 `quit()` 在 `run()` 返回前恢复，故安全；且一举覆盖所有非 daemon 残留源。同场景实测由 **60.17s 降至 0.17s**。

---

## 🔧 工程

- 全模块版本号 1.2.0 → **1.2.1**。
- 全量测试：`springai-code-tui` **288 用例通过**（新增并行取消 / 在飞计数泄漏 / 出站净化 / busy 闸门 / 收尾提示等回归）；`-Pdist` 产出 1.2.1 运行包。
- PTY 冒烟脚本迁至 `src/test/resources/scripts/`，并修正 `MODULE_ROOT` 上溯层数（迁移深了一层，4→5，使从源码位置运行仍能定位模块根）。

---

## 📦 仓库模块

| 模块 | 说明 |
| --- | --- |
| **springai-code-tui** ⭐ | 终端编码智能体（**本次发布的可下载运行物**）。 |
| springai-core-demo | Spring AI 原始 API 教学。 |
| springai-agent-demo | 智能体教学：工具调用、多步 agent、会话记忆等。 |
| springai-boot-demo | Spring Boot 自动装配版对照。 |
| springai-jline-demo | JLine 终端交互基础示例。 |

> demo 模块请 clone 源码后 `mvn` 运行，见各模块 README；下载包只含 `springai-code-tui`。

---

## 🔐 校验（SHA-256）

```
09ca3c47cb47bb750e8b25a19db637a2f67845e4cc0d9953fb44d01e82ad0e62  springai-code-tui-1.2.1-dist.tar.gz
06afc112349a3c34fadb34fb1f748c1a8fd9f1ac6ef6d7d31cccc4426d47a71d  springai-code-tui-1.2.1-dist.zip
```

```bash
shasum -a 256 -c <<'EOF'
09ca3c47cb47bb750e8b25a19db637a2f67845e4cc0d9953fb44d01e82ad0e62  springai-code-tui-1.2.1-dist.tar.gz
06afc112349a3c34fadb34fb1f748c1a8fd9f1ac6ef6d7d31cccc4426d47a71d  springai-code-tui-1.2.1-dist.zip
EOF
```

---

## 📄 许可

[Apache License 2.0](LICENSE)。发布包内随附 `LICENSE` 与 `NOTICE`（含所分发第三方库：Spring AI / Spring Boot / spring-ai-community 为 Apache 2.0，TamboUI 为 MIT）。

**环境**：JDK 17+，macOS / Linux / Windows。
