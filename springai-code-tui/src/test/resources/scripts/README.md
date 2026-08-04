# src/test/resources/scripts/

辅助脚本，用于端到端冒烟测试等需要真实运行时环境的场景。

| 脚本 | 用途 |
|---|---|
| `clear_smoke.py` | 在真实伪终端 (PTY) 中启动应用，驱动 `/help` + `/clear`，验证清屏行为。 |
| `memory_smoke.py` | 在真实伪终端中启动应用，验证长时记忆工具在启动时正确装配、存储目录自动创建。 |
| `mcp_smoke.py` | 在真实伪终端中启动应用（配置真实 stdio MCP server），验证 MCP 装配不崩、工具被发现、`/exit` 及时退出、无孤儿子进程。**需 `npx`（Node.js）。** |
| `mcp_manage_smoke.py` | 在真实伪终端中驱动 `/mcp` 面板：禁用→断言行翻转 + `mcp.json` 回写 `enabled:false`，再启用→断言真实重连 + 回写翻回，Esc 关面板、退出无孤儿进程。用 `-Duser.home` 隔离用户层配置。**需 `npx`（Node.js）。** |
| `attachment_smoke.py` | 用户贴图的附件行实机冒烟：输入图片路径→附件行出现、**`Ctrl+X` 取消**、取消态在本次输入内保持、清空后复位、非图片路径不误附。**`Ctrl+X` 只有 pty 能验**——单测入口 `feedKeyForTest` 绕过 TamboUI 按键路由器（`Tab`/`Shift+Tab` 当年就是这么被 `FOCUS_NEXT`/`FOCUS_PREVIOUS` 悄悄吃掉的）。**但 pty 只证「字节到了应用能处理」**，证明不了「用户按下组合键时终端真的发出那个字节」——前任 `Ctrl+G` 就是被 Chrome/Gemini 的 OS 级全局热键抢走的，那一段只有真人实机按一次才算验过。**不需要 key、不需要网络**（全程不按 Enter）。 |
| `permission_smoke.py` | 权限层实机冒烟：`Shift+Tab` 模式循环（含斜杠菜单 / `/mcp` 面板里的裸 Tab 守卫双向验证）、`/permissions` 只读报告，以及**完整的 ASK 阻塞握手**——脚本内起一个 DeepSeek SSE 桩模型（`DEEPSEEK_BASE_URL` 指过去），让它发起 `Bash(git push …)`，断言审批面板渲染（五选项各占一物理行、高亮**纯前景无背景色**）、拒绝后回合继续、Esc 中断后下一条消息不报 400。**不需要真实 key、不需要网络。** |
| `background_smoke.py` | 后台子 agent（`run_in_background`）实机冒烟：`/tasks` 空态、⏱ 面板与状态栏后缀、任务完成后**自动起回合**且通知按 `\n` 逐物理行渲染、`/tasks` → `k` → Enter 终止。脚本内起 DeepSeek SSE 桩模型（按最后一条消息路由，既扮主 agent 也扮被派出的子 agent），整条链是真的。**最有价值的一条断言**：24 行小窗口 + 3 条任务时**输入框是否还在可见区内**——⏱ 面板在输入框上方多占行，那是 `InlineDisplay` 的行数记账问题，离屏 Buffer 没有「屏幕只有 24 行」这个约束、永远测不出来。**不需要真实 key、不需要网络。** |

运行前需要先编译项目：

```bash
mvn -q compile
```

从项目根目录执行：

```bash
python3 src/test/resources/scripts/clear_smoke.py
python3 src/test/resources/scripts/memory_smoke.py
python3 src/test/resources/scripts/mcp_smoke.py
python3 src/test/resources/scripts/mcp_manage_smoke.py
python3 src/test/resources/scripts/attachment_smoke.py
python3 src/test/resources/scripts/permission_smoke.py
python3 src/test/resources/scripts/background_smoke.py
```

> `mcp_smoke.py` / `mcp_manage_smoke.py` / `attachment_smoke.py` / `permission_smoke.py` 额外需要 `dependency:build-classpath` 生成的 `target/cp.txt`：
>
> ```bash
> mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
> ```
