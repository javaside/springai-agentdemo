# src/test/resources/scripts/

辅助脚本，用于端到端冒烟测试等需要真实运行时环境的场景。

| 脚本 | 用途 |
|---|---|
| `clear_smoke.py` | 在真实伪终端 (PTY) 中启动应用，驱动 `/help` + `/clear`，验证清屏行为。 |
| `memory_smoke.py` | 在真实伪终端中启动应用，验证长时记忆工具在启动时正确装配、存储目录自动创建。 |
| `mcp_smoke.py` | 在真实伪终端中启动应用（配置真实 stdio MCP server），验证 MCP 装配不崩、工具被发现、`/exit` 及时退出、无孤儿子进程。**需 `npx`（Node.js）。** |

运行前需要先编译项目：

```bash
mvn -q compile
```

从项目根目录执行：

```bash
python3 src/test/resources/scripts/clear_smoke.py
python3 src/test/resources/scripts/memory_smoke.py
python3 src/test/resources/scripts/mcp_smoke.py
```

> `mcp_smoke.py` 额外需要 `dependency:build-classpath` 生成的 `target/cp.txt`：
>
> ```bash
> mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
> ```
