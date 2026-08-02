# 变更日志

本项目的每个版本都有一份独立的发版说明，含新功能详解、下载物与 SHA-256 校验和。
本文件只做索引；细节点进对应链接。

版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。
可下载的发布物只有终端编码智能体 `springai-code-tui`（自包含运行包，解压即用）。

| 版本 | 类型 | 摘要 |
| --- | --- | --- |
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
