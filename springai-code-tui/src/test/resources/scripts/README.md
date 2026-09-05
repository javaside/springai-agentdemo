# Code TUI PTY smoke scripts

这些脚本从真实 PTY 启动已打包的 Code TUI，并用 `pyte` 回放终端字节。除明确标为 `npx` 的两项外，脚本使用本地桩或不提交模型请求，不需要网络和真实 API key。

## 前置依赖

- macOS/Linux PTY 设施：`openpty`、`fcntl`、`termios`、`TIOCSWINSZ`。
- JDK 与 Maven；`java`、`mvn` 在 `PATH`。
- `/usr/bin/python3` 与 `pyte`。项目既有约定允许从该解释器的 user site-packages 加载 `pyte`；若缺失，按项目/开发机既有 Python 用户环境安装说明处理，不要系统级安装。
- 仅 `mcp_smoke.py`、`mcp_manage_smoke.py` 需要 Node.js 的 `npx`，并可能由 `npx` 获取 MCP 包；它们不属于本地/无网络脚本集。

从**仓库根目录**先执行精确构建命令：

```bash
mvn -q -pl springai-tamboui-inline-patch -am install -DskipTests
mvn -q -pl springai-code-tui -am package -DskipTests
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
```

## 脚本清单

| 脚本 | 验收范围 | 网络/额外依赖 |
|---|---|---|
| `attachment_smoke.py` | 附件识别、`Ctrl+X` 取消及输入内取消态。 | 本地 |
| `attention_smoke.py` | 完成/取消后的终端标题与 BEL。 | 本地 SSE 桩 |
| `background_smoke.py` | 后台任务面板、通知、自动续回合、终止。 | 本地 SSE 桩 |
| `clear_smoke.py` | `/help` 后 `/clear` 真清屏并恢复欢迎横幅。 | 本地 |
| `edit_shortcut_smoke.py` | 输入编辑快捷键及边界行为。 | 本地 |
| `event_driven_fairness_smoke.py` | 5,000 行流式输出期间的按键公平性、完整性、延迟和静止后 2.2s 零终端字节。 | 本地 SSE 桩 |
| `interjection_smoke.py` | 忙时插话 UI、模型消息顺序及 Esc 回填。 | 本地 SSE 桩 |
| `memory_smoke.py` | 长时记忆工具装配与存储目录。 | 本地 |
| `model_memory_smoke.py` | `/model` 选择持久化及重启恢复。 | 本地 |
| `permission_smoke.py` | 权限模式、面板、ASK 阻塞握手与取消历史。 | 本地 SSE 桩；配置中禁用 MCP，不调用 `npx` |
| `render_diff_smoke.py` | 局部差分、按需 IME 补帧、完全静止 2s 零终端字节。 | 本地 |
| `resize_smoke.py` | 真 `SIGWINCH`、generation settle 的 `ESC[3J` 重放与最终画面/光标。 | 本地；pyte 不支持 reflow，视觉 reflow 仍需实机 |
| `stalled_terminal_smoke.py` | 输出高峰期间读端停摆 3s（pty-writer 卡 write(2)）：完整回显落盘晚于末行输出（顺序判据）、恢复后 2000 行零丢失、排空后零字节。证红：`CODETUI_STALLED_MUTATE_SYNC_WRITE=1` 翻转顺序断言。 | 本地 SSE 桩 |
| `stream_box_smoke.py` | 流式预览期间无双边框、正文完整、按需 IME 补帧完成。 | 本地 SSE 桩 |
| `table_render_smoke.py` | markdown 表格：回合以表格结尾且**不按任何键**时自动落地、只出一条 `─` 分隔线、无残留 `\|`、每行不超终端宽、列起始位置按**显示宽**对齐。 | 本地 SSE 桩 |
| `mcp_smoke.py` | 真实 stdio MCP 装配、工具发现、退出及无孤儿进程。 | **需要 `npx`/Node.js，可能联网** |
| `mcp_manage_smoke.py` | `/mcp` 禁用/启用、配置回写及真实重连。 | **需要 `npx`/Node.js，可能联网** |

## 本地/无网络命令

```bash
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/event_driven_fairness_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/render_diff_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/stream_box_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/table_render_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/resize_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/permission_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/interjection_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/attachment_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/clear_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/background_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/attention_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/edit_shortcut_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/memory_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/model_memory_smoke.py
```

## `npx` 脚本命令

仅在 Node.js、`npx` 和所需 MCP 包可用且允许相关网络行为时执行：

```bash
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/mcp_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/mcp_manage_smoke.py
```

所有脚本成功时退出码为 0 并打印 `SMOKE PASS`；失败时退出非零并尽量打印最后画面/原始字节诊断。`event_driven_fairness_smoke.py` 的 `CODETUI_FAIRNESS_MUTATE_IDLE=1` 仅用于证明 idle 原始字节断言能变红，不是正常验收命令。
