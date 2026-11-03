# /agents 命令实施计划（薄版）

- 日期：2026-11-03
- 设计：[specs/2026-11-03-agents-command-design.md](../specs/2026-11-03-agents-command-design.md)（已评审修订）
- 分支：`feature/agents-command`
- 纪律：每任务红（留证）→ 最小实现 → 绿 → 提交；命令
  `mvn -pl springai-code-tui -am test -Dtest='<类>' -Dsurefire.failIfNoSpecifiedTests=false`。

## T1 数据面：AgentInfo + Loaded.infos + seam 链

**产出**：新 `agent/subagent/AgentInfo.java`；改 `SubagentCatalog`（装载循环双写 infos，
Loaded 四参）、`SubagentReloader`（agents()）、`SubmitHandler`（default agents()）、
`CodingAgent`（@Override，null 桩返空）。

1. 先改测试（红）：
   - `SubagentCatalogTest` 补 `infosCarrySourceAndFollowOverride`：六层各一 .md →
     infos 序 = 内置 4（source=「内置」）+ 自定义按层序、各条 source 正确；再放
     项目 `.codetui/agents/explore.md` 覆盖 → 该条 source=「项目」、name 序不变；
     **两段装载后各附一次** keySet 等序断言（spec §4.1 原文——第二段锁「覆盖时
     infos 换条目但位置保持」这半边）。新用例自带方法级 @TempDir，别复用既有用例目录。
   - `SubagentReloaderTest`：`loaded()` 助手**内部**从 specs 派生第四参
     （`AgentInfo(name, description, 测试标签)`），**签名与 4 处调用点不动**——
     别改签名机械传 `List.of()`，会让 L106 用例「构造后 agents() 含初始 agent」变红；
     既有用例补断言「构造后 agents() 含初始 agent；reload 后换新」。
   - `AgentRuntimeTest` L206 内联构造改为 `return SubagentCatalog.load(null, null)`
     （两层目录全空 → 恰好内置 4 条自带非空 infos；或从 `loadBuiltins()` 派生，
     禁止 `List.of()`）；联动用例补两断言：非 null 路径 `agents()` 非空、
     null 桩路径空列表。
   - 红：`AgentInfo`/四参 `Loaded` 不存在 → 编译红，摘要留证。
2. 最小实现（全按 spec §3.1-3.3；AgentInfo javadoc 抄 SkillInfo 的「不让大对象泄进
   UI」论证并写明「内置」标签 agent 侧独有；`summary()` 不动——仍从 specs/customCount/
   warnings 算，不依赖 infos）。
3. 绿 → 回归 `-Dtest='Subagent*Test,AgentRuntimeTest'` →
   `feat: /agents 数据面 —— AgentInfo 投影与来源层标签贯穿 seam`。

## T2 UI：/agents 命令

**产出**：改 `CodeTuiView`（COMMANDS 插 `/skills` 与 `/reload` 之间；submitInput 加
`cmd.equals("/agents")` 分支（/skills 分支旁）；`printAgents()` 仿 printSkills 两行式，
空态文案按 spec §3.4）。

1. 无新测试（UI 打印层，printSkills 同款；数据面 T1 覆盖；空态文案生产不可达——
   仅桩路径 `agents()==List.of()` 可达，同不测）。
2. 回归 `-Dtest='CodeTuiView*Test,AgentRuntimeTest'` + `test-compile`。
3. `feat: /agents 命令 —— 列出全部子 agent（含来源层）`。

## T3 文档

- `reference.md`：命令表加 `/agents` 行；顺手补 L57 `/reload` 行「子 agent 目录重扫」。
- `guide/subagent.md`：§3 自定义段提 `/agents`；六层表加「/agents 显示标签」列
  （注意 ①②/④⑤ 是合并行，一格写双标签如「用户·claude / 用户·agents」）。
- `implementation-map.md`：§5 命令表 `/skills`（L363）后插 `/agents` 行；§12 子 agent 章
  热重载段（L922-929 旁）补一句 `/agents` 取 `reloader.agents()`；顶部速查表是功能
  索引、无命令级行，不加。
- `docs: reference/guide/implementation-map 补 /agents 命令`。

## T4 全量 + 合并（主会话执行）

`mvn -pl springai-code-tui -am test` 全绿 → 终审 → `--no-ff` 合并 main、删分支。
