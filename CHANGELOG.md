# 变更日志

本项目的每个版本都有一份独立的发版说明，含新功能详解、下载物与 SHA-256 校验和。
本文件只做索引；细节点进对应链接。

版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。
可下载的发布物只有终端编码智能体 `springai-code-tui`（自包含运行包，解压即用）。

| 版本 | 类型 | 摘要 |
| --- | --- | --- |
| [v1.19.0](docs/release-notes/v1.19.0.md) | 功能版 | **界面更新改事件驱动 + 智谱视觉模型全面接入**：删除常驻 100ms 重绘与 drain 轮询，改为合并的状态变更通知（coalesced），空闲零输出、高频 token 合并不再逐 token 排队，预览/缩放稳定/动画/上下文刷新/输出续帧全部按需一次性调度，物理行输出预算改按 segment 级懒折行计；智谱内置清单加 `glm-5.3-flash`（原生多模态 · 1M 上下文 · 1/10 价，默认仍旗舰 `glm-5.3` 不变），视觉名单补 `glm-4.6v` / `glm-5v` 前缀（纯文本 `glm-5.3` 不误伤），内置上下文窗口（flash 1M / 4.6v 128K / 4.5v 64K，未核实窗口落保守兜底），thinking 三档；视觉真机冒烟探针（纯图 / 流式工具 / 图+工具复合）；修复状态栏波光续帧竞态等一批 UI 竞态 |
| [v1.18.3](docs/release-notes/v1.18.3.md) | 修订版 | **输出期打字卡死与终端崩溃根治 + agent 包按功能重构**：每帧 pty 写入限速改按物理行计（此前 300 的额度是逻辑 `OutputLine` 条数，一条折行/diff 展开成十几到上百物理行，实测一帧写出 4500 行），并给此前完全没接限速的流式出口补上预算（实测一帧 5000 行）——渲染线程不再被单帧占住数百毫秒，macOS Terminal.app 不再被几百 KB 的 pty 突发推进自身 use-after-free；`codetui.agent` 顶层 88 个类按功能拆进 10 个子包（`llm`/`mcp`/`subagent`/`skill`/`session`/`compaction`/`seam`/`tools`/`prompt`/`interjection`），顶层只留 `CodingAgent` 与 `AgentTools`，无方法体改动、行为无变化；16 个 main 包补齐 `package-info`（写明各包职责、依赖方向与拆包理由，含 2 条已知包循环与提权清单）。`docs/` 下 2026-08-28 之前的历史文档里的 `codetui.agent.*` 类路径按当时结构书写，未随本次调整更新。 |
| [v1.18.2](docs/release-notes/v1.18.2.md) | 修订版 | **OpenCode Go 官方视觉模型 + Provider 能力隔离**：内置 `deepseek-v4-flash-vision-exp`，只为网关已确认的模型开放图片；UI 附件闸门与请求兑现统一按 provider+model 判定，避免聚合网关同名模型串用视觉能力；消息在飞、排队或待送达插话期间禁止切模型，避免图片静默降级；补齐图片处理实现原理与公共 API 文档 |
| [v1.18.1](docs/release-notes/v1.18.1.md) | 修订版 | **DeepSeek 视觉图片可靠送达 + 二进制外置 fail-closed**：修复工具多响应被 Spring AI 展开后图片注册表按绝对下标错位、模型只看到引用而看不到图；项目外图片 Read 会复制进 artifacts 再引用，PNG/PDF hexdump 不再进入模型上下文；外置失败时 fail-closed 移除二进制内容；artifacts 副本 Read 与用户附件统一内容哈希 id，避免同一张图被当作两张 |
| [v1.18.0](docs/release-notes/v1.18.0.md) | 功能版 | **`/context` 上下文占用透明化 + 工具调用报错不再毁掉回合**：报告升级为分类明细（事件按用户/助手/工具分桶，token 统一为「上下文占用」口径=系统提示词+会话消息，构成逐行占比、总和恒 100%）；工具名拼错/工具执行异常改为把错误原因回给模型自纠（不新增别名工具），MCP 工具为空不再覆盖 defaultTools；长期记忆系统提示精简省 34% token，系统提示词补「动手前查技能清单、需求未定须提问」；并入 v1.17.2 的「输出中打字」卡死/终端崩溃修复（token 估算移出渲染线程、残行 20 万上限、渲染降频、预览节流） |
| [v1.17.1](docs/release-notes/v1.17.1.md) | 修订版 | **根治行内 TUI 卡死**：`/model` 切换模型长时间不选择再回车、对话输入后「思考中」界面永久定格、任何按键失效——根因是依赖库 `tamboui-tui` 的 `InlineTuiRunner.run()` 事件循环对 `handler.handle`/`UiRunnable.run` 零 try-catch（全屏版 `TuiRunner` 有），异常逃出循环即杀死渲染线程；现以同包同名 shadow 类给事件循环包 `try(Throwable)`（记录日志并 continue），渲染线程永不因 handler 异常死亡 |
| [v1.17.0](docs/release-notes/v1.17.0.md) | 功能版 | **DeepSeek 视觉模型支持**：`deepseek-v4-flash-vision-exp` 入内置清单（`/model` 可切，默认仍 v4-pro），视觉能力按前缀精确判定、未知模型不发图；修复 spring-ai-deepseek 2.0.0 序列化静默丢图（HTTP 层改写 content → text + image_url/file 块，无图请求逐字节不变、fail-open）；双传输通道（默认 base64 内联，`DEEPSEEK_VISION_TRANSPORT=files` 走 Files API，sha 幂等 + 失败自动降级内联）；沿用「图片只活当轮、单回合硬上限」纪律，每图最多 384 token 计费 |
| [v1.16.0](docs/release-notes/v1.16.0.md) | 功能版 | **终端 tab 会主动提醒，手动压缩恢复保留 20 事件语义**：回合完成或问询/审批等待用户时写 OSC 0/2 tab 标题并发送 BEL，Esc 取消不误响、按键恢复默认标题；修复 glm-5.3 / 1M 窗口下手动 `/compact` 因 token 预算过大而误报「无可压缩内容」，现在长会话即使低于 token 目标也会按最近 20 事件强制归档 |
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
