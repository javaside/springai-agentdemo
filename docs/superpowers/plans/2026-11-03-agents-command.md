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
     附 keySet 等序断言（spec §4.1 原文）。
   - `SubagentReloaderTest` 的 `loaded()` 助手补第四参；既有用例补断言
     「构造后 agents() 含初始 agent；reload 后换新」。
   - `AgentRuntimeTest` L206 内联构造补第四参（**内置 4 个非空 infos**，spec 警告：
     机械补 List.of() 会让 agents() 非空断言恒假）；联动用例补两断言：非 null 路径
     `agents()` 非空、null 桩路径空列表。
   - 红：`AgentInfo`/四参 `Loaded` 不存在 → 编译红，摘要留证。
2. 最小实现（全按 spec §3.1-3.3；AgentInfo javadoc 抄 SkillInfo 的「不让大对象泄进
   UI」论证并写明「内置」标签 agent 侧独有）。
3. 绿 → 回归 `-Dtest='Subagent*Test,AgentRuntimeTest'` →
   `feat: /agents 数据面 —— AgentInfo 投影与来源层标签贯穿 seam`。

## T2 UI：/agents 命令

**产出**：改 `CodeTuiView`（COMMANDS 插 `/skills` 与 `/reload` 之间；submitInput 加
`cmd.equals("/agents")` 分支（/skills 分支旁）；`printAgents()` 仿 printSkills 两行式，
空态文案按 spec §3.4）。

1. 无新测试（UI 打印层，printSkills 同款；数据面 T1 覆盖）。
2. 回归 `-Dtest='CodeTuiView*Test,AgentRuntimeTest'` + `test-compile`。
3. `feat: /agents 命令 —— 列出全部子 agent（含来源层）`。

## T3 文档

- `reference.md`：命令表加 `/agents` 行；顺手补 L57 `/reload` 行「子 agent 目录重扫」。
- `guide/subagent.md`：§3 自定义段提 `/agents`；六层表加「/agents 显示标签」列。
- `implementation-map.md`：§5 命令表加行；§11 技能章的同级（若命令速查表有 /skills 行）
  对齐加 /agents。
- `docs: reference/guide/implementation-map 补 /agents 命令`。

## T4 全量 + 合并（主会话执行）

`mvn -pl springai-code-tui -am test` 全绿 → 终审 → `--no-ff` 合并 main、删分支。
