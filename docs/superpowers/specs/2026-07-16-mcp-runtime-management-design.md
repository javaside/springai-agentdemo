# /mcp 运行期 MCP 管理设计

日期：2026-07-16
模块：springai-code-tui

## 目标

新增 `/mcp` 斜杠命令：列出已安装（两层 mcp.json 中声明）的 MCP server，可逐个禁用/启用。
切换**立即生效**（禁用即当前会话下一回合模型看不到该 server 的工具；启用即立刻连接并注入工具），
并**持久化**回该条目所属层的 mcp.json（`enabled` 字段），重启后状态保留。管理粒度为**整个 server**（不细到单个工具）。

## 现状与差距

- 启动期一次性流程：`McpConfigLoader.load(root)` 读两层 mcp.json（项目级覆盖用户级、`enabled:false` 直接丢弃）→ `McpClientManager.connectAll` 并行连接 → `toolCallbacks()` 发现工具 → 并入 `AgentTools.build` 的共享工具列表 → **静态烧入**每个 provider 的 `ChatClient.defaultTools(...)`。
- 差距：工具列表启动后不可变；`enabled:false` 的条目被 loader 丢弃后运行期不可见（无法在 /mcp 里列出再启用）；没有任何回写配置的能力。

## 方案取舍（已选 A）

- **A（已选）：运行期 McpRegistry** —— MCP 工具改为每回合从可变 registry 动态注入。双向立即生效，改动面最大但语义最完整。
- B：门禁装饰器 —— 工具列表仍静态，禁用时调用直接拒绝。改动最小，但「启用未连接的 server」仍要重启，且禁用工具的 schema 依旧占上下文、模型仍会尝试调用。
- C：切换时整体重建 ChatClient —— 复用启动装配路径，但运行态替换 `clients` map、被 `CodingAgent`/UI 持有的引用都要跟着换，风险高且实际改动不比 A 小。

## 架构

### 新组件：`McpRegistry`（agent 包）

把现在「加载→连接→发现」的一次性流水线收拢为一个**可变的运行期注册表**，取代裸的 `McpClientManager` 成为 MCP 的唯一中枢：

```java
public final class McpRegistry {
    // name → ServerEntry{ config(McpServerConfig), source(USER/PROJECT),
    //                     enabled, client(McpSyncClient|null), tools(List<ToolCallback>), error(String|null) }

    static McpRegistry init(Path root);          // 启动：加载两层配置（含 enabled:false 条目）+ 并行连接 enabled 项
    List<ServerView> servers();                  // /mcp 面板数据源（名字/来源层/状态/工具数/工具名清单）
    List<ToolCallback> activeTools();            // 当前所有「已启用且已连接」server 的已装饰工具（每回合快照）
    boolean enable(String name);                 // 连接 + 发现 + 装饰 + 回写 enabled:true（阻塞方法；由 UI 层放后台线程调）
    boolean disable(String name);                // 摘除工具 + closeGracefully + 回写 enabled:false
    void close();                                // 退出：关所有已连 client（沿用 2s 预算逻辑）
}
```

要点：

- **`enabled:false` 条目不再被 loader 丢弃**。`McpConfigLoader` 新增保留禁用条目的解析入口（现有 `load()` 语义不变，避免影响既有调用/测试），registry 用新入口拿到全量条目，禁用项以「已禁用、未连接」状态存在，供 /mcp 列出并启用。
- **来源层记录**：解析时给每条标注 USER（`~/.codetui/mcp.json`）或 PROJECT（`<root>/.codetui/mcp.json`），项目级覆盖用户级同名项时取项目级。回写时写回条目所属层的文件。
- `McpClientManager` 的连接/关闭/前缀逻辑下沉为 registry 的实现细节（复用 `connectOne`、`prefixedName`、2s 关闭预算，不重写）。

### 动态注入：工具列表从「静态烧入」改「每回合快照」

MCP 工具**不再**并入 `AgentTools.build` 的 `defaultTools`（内置工具仍静态烧入不变）。改为：

- `AgentTools.build` 接收 `McpRegistry`（取代 `List<ToolCallback> mcpTools` 参数），**装饰循环仍在装配期对 registry 当前工具做**——但装饰动作收拢为 registry 的职责：registry 在 enable/发现时就用 `ToolEventCallback` + `MediaExternalizingCallback` 装饰好（构造 registry 时注入 listener、mediaStore、mediaHandler、root），`activeTools()` 返回的始终是已装饰实例。这样主/子 agent 拿到的 MCP 工具与内置工具行为一致（TUI 显示工具活动行、媒体外置路径①生效）。
- **主 agent**：`CodingAgent.submit` 的 `client.prompt()` 链上追加 `.tools(registry.activeTools().toArray())`。Spring AI 2.0 的 per-request tools 与 defaultTools 是**合并**语义，MCP 工具名带 `mcp__` 前缀不会与内置工具重名，合并安全。
- **子 agent**：`SubagentRunner` 持有 registry，`run()` 每次构建子 agent ChatClient 时 `filterTools(tools, spec)` 的输入改为 `内置装饰工具 + registry.activeTools()` 拼接，MCP 工具同样受 spec 的 allow/deny 过滤。
- 回合内一致性：`activeTools()` 每回合取一次快照，回合中途切换不影响在飞回合（下回合生效）。工具执行拿的是 callback 里闭包的 client 引用，禁用时 `closeGracefully` 后在飞调用自然失败并按现有工具错误路径上报——可接受，且 /mcp 仅空闲时可操作（见 UI 段），实际窗口极小。

## 数据流与持久化

### 切换数据流

```
/mcp 面板 Enter 切换
  → CodeTuiView 经 CodingAgent 暴露的门面调 registry.enable/disable(name)
  → enable：McpTransportFactory.create → connectOne（阻塞握手，UI 状态栏提示「连接中…」）
            → listTools → 装饰 → entry.tools 就位 → 回写 mcp.json
  → disable：entry.tools 清空 → closeGracefully（后台线程，不阻塞 UI）→ 回写 mcp.json
  → 面板刷新该行状态；下一回合 activeTools() 快照即已变化
```

### mcp.json 回写（新组件 `McpConfigWriter`，agent 包）

- **读-改-写**：读该层现有 JSON 树 → 只改 `mcpServers.<name>.enabled` 一个字段 → 原样写回。**不做全量序列化**，用户手写的注释外字段、条目顺序、缩进尽量保持（Jackson 树模型读写，保插入顺序）。
- 原子写：先写临时文件再 `Files.move(ATOMIC_MOVE)`，防写一半损坏配置（照 `FileSessionRepository` 的落盘风格）。
- 失败降级：回写失败（只读文件系统等）记 WARN + UI notice「已切换（仅本次运行，写回配置失败）」，内存状态仍生效，**不抛异常**（照 `McpConfigLoader` 降级契约）。
- 目标文件缺失（如条目来自用户级但项目级文件不存在）：写回条目**所属来源层**的文件，该文件必存在（条目就是从那读出来的）。

### 与外部编辑的关系

运行期不监听 mcp.json 变化；用户运行中手改文件需重启生效（与技能 /reload 不同，MCP 连接有子进程副作用，本期不做热重载，YAGNI）。

## /mcp 面板 UI

仿现有 `/model`/`/skill` 选择器模式（`pickingMcp` 标志 + `scope(...)` 面板 + 按键拦截）：

```
  MCP 服务器（↑↓ 选择 · Enter 启用/禁用 · Tab 查看工具 · Esc 关闭）
❯ ✓ chrome-devtools   [项目级] 已连接 · 12 工具
  ○ filesystem        [用户级] 已禁用
  ✗ weather           [项目级] 连接失败：timeout
```

- 状态标记：`✓` 已启用且已连接；`○` 已禁用；`✗` 已启用但连接失败（error 摘要跟在行尾）。
- **选中高亮用纯前景色**（PICK_SEL 风格，严禁背景色条——TamboUI 底色会串到下一项）。
- Enter/Space：切换选中项。禁用→启用需连接握手（秒级）：UI 把 `registry.enable` 放后台线程执行、期间状态栏显示「⟳ 连接 <name>…」且该行标记为连接中，完成后回 UI 线程刷新——渲染循环不冻结；启用→禁用即时完成。对 `✗` 项 Enter = 重试连接。
- Tab：展开/收起选中 server 的工具名清单（已连接项列 `mcp__` 前缀后的短名，只读、暗色、缩进；未连接项显示「（未连接，无工具信息）」）。
- 数字 1..9 快选，与现有选择器一致。
- **仅空闲可操作**：`/mcp` 提交时若 `busy()`（回合中/压缩中/在飞子 agent）则 notice「忙碌中，无法管理 MCP」拒绝打开——避免回合中途摘工具/关连接撞上在飞调用。
- 无任何 server 声明时：notice「未配置 MCP server（.codetui/mcp.json）」，不弹面板。
- 斜杠补全菜单 `SLASH_COMMANDS` 注册 `/mcp`（描述「管理 MCP 服务器（启用/禁用）」），`/help` 同步补一行。

## 错误处理

沿用「降级不抛异常」全局契约：

- enable 连接失败：entry 记 error、状态回「已启用但连接失败」（`✗`），**enabled:true 仍回写**（用户意图是启用；下次启动会自动重试连接）。UI notice 显示失败原因摘要。
- disable 时 closeGracefully 异常：吞掉记 WARN（现有行为），工具已摘除即视为成功。
- 回写失败：见持久化段，内存生效 + notice 警示。
- 退出清理：`CodeTuiApplication` 的 `finally { mcpManager.close() }` 改为 `registry.close()`，运行期新启用的连接同样被关闭（关闭预算逻辑不变）。

## 测试

- **单测（模块作用域跑：`mvn test -pl springai-code-tui`）**
  - `McpConfigLoader`：新入口保留 `enabled:false` 条目 + 来源层标注正确（项目覆盖用户时取项目层）。
  - `McpConfigWriter`：只改 `enabled` 字段、其余字段/顺序不动；目标层选择正确；写失败降级不抛。
  - `McpRegistry`：enable/disable 后 `activeTools()` 快照变化；连接失败进 error 态；工具装饰器（ToolEventCallback/MediaExternalizingCallback）已包上（可用 fake transport/client 注入）。
  - `SubagentRunner`：MCP 工具进入 spec allow/deny 过滤（注册名 = `mcp__` 前缀名）。
  - `CodeTuiView`：/mcp 忙时拒绝、空配置提示、面板按键状态机（复用现有选择器单测风格）。
- **render 冒烟**：面板渲染含状态标记/来源层/工具数（TamboUI scope eager 求值——面板方法首行判空防 NPE）。
- **pty 实机冒烟**：打开 /mcp → 切换一项 → 屏幕断言状态标记翻转（渲染类改动按项目惯例必须 pty 验证；须 TIOCSWINSZ + TERM=xterm-256color）。
- 改完须重新 `package` 再实机验证（项目惯例）。

## 范围外（YAGNI）

- 单工具粒度开关；mcp.json 热重载/文件监听；/mcp 内新增/删除 server 条目（仍手编文件）；非 stdio 传输（沿现状）。
