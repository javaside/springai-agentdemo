# 变更日志

本项目的每个版本都有一份独立的发版说明，含新功能详解、下载物与 SHA-256 校验和。
本文件只做索引；细节点进对应链接。

版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。
可下载的发布物只有终端编码智能体 `springai-code-tui`（自包含运行包，解压即用）。

| 版本 | 类型 | 摘要 |
| --- | --- | --- |
| [v1.15.0](docs/release-notes/v1.15.0.md) | 功能版 | **长会话压缩降为一次调用、子 agent 重试真正生效**：自动压缩改乐观全量摘要（约 8 次调用 → 1 次），窗口虚高时按真实失败校准容量（数字锚定一步到位 / 减半探测），knownGood/knownBad 区间按模型跨压缩记忆、绝不重发注定失败的请求，20 次调用硬上限 + 本地兜底永不失控；glm-5.3 入内置清单（默认旗舰，1M 窗口，thinking 不可禁用）；子 agent 重试判定对齐生产日志四类瞬态故障（此前重试层 0 触发、失败全冒泡），改指数退避 |
| [v1.14.1](docs/release-notes/v1.14.1.md) | 修订版 | **界面可见性两修**：模型输出期间输入框不再反复闪出第二条边框（live 区改在顶部增删行，终端写入量 422KB→131KB；该缺陷自 v1.13.0 起存在）；深色终端下次要文字不再近乎不可见（ANSI 亮黑改为 256 色灰阶三档，引用块提到信息行同级，代码块左边栏的 truecolor 改 indexed）；新增调色板对比度单测与流式输入框 pty 实机冒烟 |
| [v1.14.0](docs/release-notes/v1.14.0.md) | 功能版 | **流式调用不再无限卡住，缓存状态更易扫读**：六家 provider 统一增加流式空闲超时（默认 300 秒，可配置）；思考与工具运行期间继续显示缓存命中率；窄终端按信息优先级保留动态状态，缓存命中百分比值单独高亮 |
| [v1.13.0](docs/release-notes/v1.13.0.md) | 功能版 | **缓存命中率可见**：`/context` 新增命中率行（命中/计费输入原值），状态栏常驻「缓存命中 N%」；DeepSeek 流式补 `include_usage`、usage 终止前原子提交、缓存 token 经 nativeUsage 兜底；修复状态栏超宽时动态数据被 80 列终端截断（模型名+动态数据保完整，静态键位提示让位）；`CODETUI_CO_AUTHOR` 提交署名（默认关闭），`config.env` 改逐行解析杜绝 shell 注入 |
| [v1.12.0](docs/release-notes/v1.12.0.md) | 功能版 | **OpenCode Go 思考档位按模型实测并扩充模型清单**：`reasoning_effort` 从一刀切三档改为按模型返回真实档位（DeepSeek/GLM/Kimi/MiniMax/混元旗舰可用到 `max`），模型清单 15 → 22（补入 10、剔除 3 项上游不可用）；修复 CI 构建/打包命令缺 `-am` |
| [v1.11.1](docs/release-notes/v1.11.1.md) | 修订版 | **自动压缩不再过早触发**：修复 OpenCode Go 网关同名模型上下文窗口误判（真实 1M 窗口被按 128K 算，新会话频繁触发压缩），按 modelId 同名回退复用原厂窗口；修正构建/打包命令文档（补 `-am` 与 `-Dsurefire.failIfNoSpecifiedTests=false`） |
| [v1.11.0](docs/release-notes/v1.11.0.md) | 功能版 | **新增 OpenCode Go provider**：一把 `OPENCODE_GO_API_KEY` 接通 MiniMax / Kimi / GLM / DeepSeek / 通义 / MiMo / 混元 / OpenAI / xAI 等聚合网关模型，复用 OpenAI 兼容通路接入，思考强度走 `reasoning_effort` 三档（low/medium/high）；**修复同名模型串号**（模型身份升级为 provider+model，`/model` 与思考设置精确区分来源）；**修复多行粘贴被拆成多次提交**（开启 bracketed paste，整段粘贴一次进入输入框，仅手动 Enter 发送） |
| [v1.10.0](docs/release-notes/v1.10.0.md) | 功能版 | **模型思考设置**：`/model` 列表按 `→` 为每个模型独立配置思考模式与强度（DeepSeek `reasoning_effort`、Anthropic/OpenAI effort、通义 token 预算、智谱 effort），按模型记忆；另有行内差分渲染与 IME 损坏修复、会话预压缩、ChatClient 合并链修复 |
| [v1.9.1](docs/release-notes/v1.9.1.md) | 修订版 | **`/skill` 大量技能不再闪动**：技能选择器改为固定高度的跟随窗口，避免终端反复滚动重排；仍可遍历并挂载全部技能；同步理顺发布包下载运行入口与 JDK 17+ 说明 |
| [v1.9.0](docs/release-notes/v1.9.0.md) | 功能版 | **后台任务不再像卡死**：`/tasks` 面板 RUNNING 行加波光动画，一眼看出还在跑；后台完成通知明确要求模型继续 Todo 计划（而非仅确认结果）；`run_in_background` 使用条件写进工具描述，默认前台、减少误用后台 |
| [v1.8.3](docs/release-notes/v1.8.3.md) | 修订版 | **context 低估修复 + Terminal.app 崩溃缓解**：`/context` token 估算正确纳入工具输出（`ToolResponseMessage.getText()` 空串 bug）；每帧 pty 写入限速 300 行，降低 Terminal.app GCD kevent 崩溃频率；README 拆分为 docs/guide/ 专题文档 |
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
