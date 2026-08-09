# 变更日志

本项目的每个版本都有一份独立的发版说明，含新功能详解、下载物与 SHA-256 校验和。
本文件只做索引；细节点进对应链接。

版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。
可下载的发布物只有终端编码智能体 `springai-code-tui`（自包含运行包，解压即用）。

| 版本 | 类型 | 摘要 |
| --- | --- | --- |
| [v1.8.2](docs/release-notes/v1.8.2.md) | 修订版 | **竞态修复 + 日志统一**：忙时提交在回合边界不再被误路由为排队（原子快照消竞态窗口）；全部有意义 debug 日志升 info，错误路径补 error；系统提示工具访问边界措辞放宽为「服从权限引擎」 |
| [v1.8.1](docs/release-notes/v1.8.1.md) | 修订版 | **缩放窗口三连修**：拖动终端宽度不再打烂界面（拖拽中原地清扫 + 停稳后整屏重放，回滚缓冲不再堆残骸）；中文输入法拼音不再「错位」到输入框边框上；模型回复超宽行折行续排、不再被截断（缩放后按新宽度重新折行，内容不丢） |
| [v1.8.0](docs/release-notes/v1.8.0.md) | 功能版 | **不用干等**：后台子 agent（`run_in_background`，派出去的活不占回合，`/tasks` 管理）、**回合中插话**（忙时 `Enter` 直接把话插进下一次模型调用，不必 Esc 砍掉整个回合）、记住上次用的模型（`.codetui/model.json`）；**`BYPASS` 进了 `Shift+Tab` 循环**（⚠️ 不再需要启动参数——**安全性净下降**，升级前请读发版说明） |
| [v1.7.0](docs/release-notes/v1.7.0.md) | 功能版 | **视觉输入**：模型真能看见图片（你贴的 + 工具产的），图从不进会话记忆、单回合花费有硬上限；**`--dangerously-skip-permissions` 改成名副其实**（⚠️ 它此前并不真的跳过全部检查，本版起只剩 deny 规则——**安全性净下降**，升级前请读发版说明） |
| [v1.6.0](docs/release-notes/v1.6.0.md) | 功能版 | **权限管理**：执行前审批面板、`permissions.json` 规则、计划模式、内置底线（⚠️ 默认行为变了，升级前请读发版说明）。*注：其中「BYPASS 也盖不住内置底线」一条已在 v1.7.0 变更* |
| [v1.5.0](docs/release-notes/v1.5.0.md) | 功能版 | 联网搜索（博查 + Brave 两家共存）、MCP 远程传输（Streamable HTTP）、Claude Opus 5 设为 Anthropic 默认模型 |
| [v1.4.0](docs/release-notes/v1.4.0.md) | 功能版 | 通义千问 provider、`*_MODELS` 可配置模型清单、`/mcp` 运行期 MCP 管理 |
| [v1.3.1](docs/release-notes/v1.3.1.md) | 修订版 | — |
| [v1.3.0](docs/release-notes/v1.3.0.md) | 功能版 | 含完整功能全景与⚠️安全声明 |
| [v1.2.1](docs/release-notes/v1.2.1.md) | 修订版 | — |
| [v1.2.0](docs/release-notes/v1.2.0.md) | 功能版 | — |
| [v1.1.0](docs/release-notes/v1.1.0.md) | 功能版 | — |
| [v1.0.0](docs/release-notes/v1.0.0.md) | 首个发布 | 含完整功能全景与⚠️安全声明 |

> 初次接触本项目，建议先读 [v1.3.0](docs/release-notes/v1.3.0.md) 与 [v1.0.0](docs/release-notes/v1.0.0.md)
> ——功能全景与安全声明在这两份里最完整；其余版本的说明只列相对上一版的差异。
