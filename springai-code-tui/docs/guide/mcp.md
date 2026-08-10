# MCP 配置（接入外部工具）

> 本页内容对应 README 的「MCP 配置」章节。


在**项目根**放 `.codetui/mcp.json`（或用户级 `~/.codetui/mcp.json`，两者按 server 名合并、项目级优先），列出要连接的 MCP server。启动时自动连接并把其工具交给智能体；**无此文件则不启用 MCP，一切照常**。

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
    },
    "chrome-devtools": {
      "command": "npx",
      "args": ["chrome-devtools-mcp@latest"],
      "env": { "FOO": "bar" },
      "enabled": true,
      "timeoutMs": 20000
    }
  }
}
```

stdio 字段：`command`（必填，可执行命令）、`args`（可选，参数数组）、`env`（可选，追加环境变量）、`enabled`（可选，默认 `true`；设 `false` 停用该条——也可在程序内用 `/mcp` 面板切换，切换会回写此字段）、`timeoutMs`（可选，连接/初始化超时，默认 20000）。`enabled` 与 `timeoutMs` 两种传输通用。

连接**远程 server**（Streamable HTTP）写 `type: "http"`：

```json
{
  "mcpServers": {
    "context7": {
      "type": "http",
      "url": "https://mcp.context7.com/mcp",
      "headers": { "Authorization": "Bearer ${CONTEXT7_API_KEY}" },
      "timeoutMs": 30000
    }
  }
}
```

- **两种传输**：`type` 省略即 `"stdio"`（本地子进程，`npx` / `uvx` 一类）；远程 server 写 `"http"` 或 `"streamable-http"`（两种拼写都认）。`"sse"`（旧标准，官方已 deprecated）**暂未支持**，配了会记 WARN 并跳过。
- **`url`**（http 类型必填）：写完整端点地址，内部会拆成 baseUri + endpoint 两段。必须是 `http`/`https` 绝对地址，否则记 WARN 并跳过。
- **`headers`**（可选）：其**值**支持 `${ENV_VAR}` 插值——token 留在环境变量里，配置文件只写引用，便于多机共用同一份 `mcp.json`。不含 `${}` 的字面值照常可用。**引用了未定义的环境变量 → 整条 server 跳过并记 WARN**，而不是带着字面量 `${TOKEN}` 去请求（那只会换来一个看不懂的 401）。
- **工具命名**：发现的工具以 `mcp__<server>__<工具名>` 注入（如 `mcp__filesystem__read_file`），既避免与内置工具/多 server 间撞名，也便于在工具活动行一眼看出出处。段内非法字符会被归一。
- **优雅降级**：某个 server 连不上（命令不存在、启动失败、超时）只记一条 WARN 并进「连接失败」态（`/mcp` 面板可见失败原因、可重试启用），**不影响其他 server、不崩启动**；`mcp.json` 缺失或 JSON 非法同样视为「未启用 MCP」。
- **可用范围**：MCP 工具对**主 agent 与子 agent**（`Task` / `ParallelTasks`）都可用。
- **运行期管理（`/mcp`）**：空闲时输入 `/mcp` 打开面板，列出两层配置的全部 server（含 `enabled:false` 与连接失败项，标注来源层/状态/工具数，Tab 展开工具清单）。Enter 切换启用/禁用：**即时生效**（禁用立刻摘除工具并后台关连接；启用后台连接、成功即注入）且**回写**该条目所属层 `mcp.json` 的 `enabled` 字段（重启后保留；回写失败降级为「仅本次运行生效」并提示）。
- **生命周期**：启动时**在后台**并行连接 enabled 项（`init` 立即返回，不挡 TUI 渲染；连接期间 `/mcp` 面板显示「连接中…」、状态栏显示 `⟳ MCP 连接中 N`，全部结束后对话区落一行「已发现 N 个工具」）；运行期经 `/mcp` 启停（新增/删除条目或改 `command` 等仍需重启）；`/exit` 时有界清理子进程（≤2s，绝不拖慢退出，见「已知限制」的残留说明）。
- **后台连接的两道丢弃守卫**：连接结果写回时会复查两件事——进程是否已 `close`（是则当场关掉刚连上的 client，否则它就成了没人认领的孤儿子进程）、该 server 是否已被 `/mcp` 禁用（是则丢弃，否则迟到的写回会把用户明确关掉的东西悄悄复活）。两条都有变异验证钉着。
- **前置**：stdio server 多为 Node 包，需本机有 `node` / `npx`（或对应运行时）。
- **安全**：MCP server 是你在 `mcp.json` 里显式声明的外部子进程，拥有该进程自身的权限（如 filesystem server 能读写你授权的目录）。它与下方「安全声明」同理**非沙箱**——只连接你信任的 server，别把敏感信息交给来路不明的 server。

