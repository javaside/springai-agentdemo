# 自定义子 agent 实施计划

- 日期：2026-11-02
- 设计：[specs/2026-11-02-custom-subagents-design.md](../specs/2026-11-02-custom-subagents-design.md)（两轮评审定稿，以下引用为「spec」）
- 分支：`feature/custom-subagents`
- 纪律：每任务**先写测试、真看到红**（跑一次失败输出留证）→ 最小实现 → 绿 → 功能与测试同提交。
  单测命令一律 `mvn -pl springai-code-tui -am test -Dtest='<类>' -Dsurefire.failIfNoSpecifiedTests=false`。

---

## T1 SubagentCatalog：六层发现 + 内置合并 + 容错

**产出**：`agent/subagent/SubagentCatalog.java`（新）+ `SubagentCatalogTest`

1. 写 `SubagentCatalogTest`（同包 `agent.subagent`，`@TempDir` 两个目录充当假 home / 假项目根）。
   用例（对应 spec §6.1）：
   - `sixLayersAllDiscovered`：六层各放一个合法 .md（frontmatter name/description + 一行正文）
     → `specs` 含全部 6 个 + 内置 4 个，`customCount`=6，`warnings` 空；
   - `projectLayerOverridesBuiltin`：项目 `.codetui/agents/explore.md`（正文写
     `OVERRIDDEN-BODY`）→ `specs.get("explore").systemPrompt()` 含 `OVERRIDDEN-BODY`；
   - `projectBeatsUserSameName`：用户 `.codetui/agents/x.md` 与项目 `.claude/agents/x.md`
     → 取项目正文；
   - `missingNameSkippedWithWarning`：坏 frontmatter（只有 description）→ `warnings`
     含该文件名、`specs` 不含它、其余文件与内置 4 个存活；
   - `missingDescriptionSkipped`：有 name 无 description → 同上跳过；
   - `emptyRootYieldsBuiltinsOnly`：两 TempDir 全空 → 恰 4 个内置、`warnings` 空；
   - `sameLayerDeterministicOrder`：同层放 `b.md`/`a.md`（不同 name）→ 断言
     `specs.keySet()` 中 4 个内置之后按**文件名字典序**出现（a 先于 b）。
     注明：目录迭代序不受 OS 控制，此钉对「未排序但碰巧同序」尽力而为（spec 未列
     此用例，plan 自加，变异杀不死时接受降级为文档性断言）。
   - `subagentFile` 帮助方法：`Files.writeString(dir.resolve("<名>.md"), "---\nname: <名>\ndescription: d\n---\n<body>")`。
2. 跑一次确认**红**（类不存在，编译失败即红）。
3. 最小实现 `SubagentCatalog`（spec §4.1 全部要点：LinkedHashMap、Files.list+sorted+try-with-resources、
   toUri()、name/description 空皆跳过、DIR_NAME/COMPAT_DIR_NAMES 常量、`load(root)` 委托
   `load(root, homeDir())`）。类 javadoc 写层序原则与「为何对齐技能三约定」。
4. 绿 → `feat: SubagentCatalog 六层发现自定义子 agent（内置合并/同名覆盖/坏文件容错）`。

## T2 ReloadableSubagentTool：实例恒定代理 + 路由换轨

**产出**：`agent/subagent/ReloadableSubagentTool.java`（新）+ `ReloadableSubagentToolTest`

1. 写测试（spec §6.2，用 `SubagentTool.create(specs, stub)` 造真 delegate；stub
   Dispatcher `(spec, p, d, t) -> spec.name()` 证明路由走当前 specs）：
   - `initialRosterFromFactory`：factory 以 alpha-specs 建 → 描述含 `alpha`；
   - `reloadSwapsRoutingNotJustDescription`（杀虫关键）：构造后
     `call("{\"subagent_type\":\"alpha\",...}")` 返回 `alpha`；`reload(betaSpecs)` 后描述
     含 `beta` 不含 `alpha`，`call(...type=beta)` 返回 `beta`；
   - `unknownTypeListsCurrentRoster`：reload 后 call 未知 type，抛出的
     `RuntimeException("No subagent found with type: ... Available: ...")` 消息里
     Available 列表是新 specs 的 key 集（已核实 2.0.1 `FunctionToolCallback.call`
     无 try/catch 包装，消息原样透传——断言用 exception message contains）。
   - JSON 入参**直接给模板**（SubagentToolTest 全部走 record 直喂、无 JSON 先例，
     手拼 JSON 先例在 `ReloadableSkillToolTest` L33/L48）：
     `{"description":"d","prompt":"p","subagent_type":"alpha"}`——`model`/
     `run_in_background` 是 `@ToolParam(required=false)` 可省，Jackson 缺字段给 null；
     `FunctionToolCallback.call` 有 `Assert.hasText(toolInput)`，空串会炸，别传空。
2. 红（类不存在）。
3. 最小实现（spec §4.2：public 构造收 factory、无初始 delegate（volatile 初始 null，
   getToolDefinition/call 对 null 抛 IllegalStateException 说明「必须先由 Reloader
   装载」——防御性，装配序保证不会触发）、并发论证进 javadoc）。
4. 绿 → `feat: ReloadableSubagentTool —— Task/ParallelTasks 的热重载代理`。

## T3 SubagentReloader：单次 load 喂双代理 + 摘要

**产出**：`agent/subagent/SubagentReloader.java`（新）+ `SubagentReloaderTest`

1. 写测试（spec §6.3）：
   - `reloadLoadsCatalogExactlyOnceAndFeedsBoth`：catalog 用
     `AtomicReference<Loaded>`（首次 get 返 alpha-specs，构造后换值为 beta-specs）+
     AtomicInteger 计数；两个真代理的 factory 写成 lambda
     `specs -> SubagentTool.create(specs, stub)`（措辞注意：**不是**传固定 specs）。
     构造 Reloader（构造即首次 reload，计数=1）→ 换 AtomicReference 值 → 手动
     `reload()` → 断言 `assertEquals(2, 计数)`（精确 2：变异「两代理各自调 catalog」
     下会变 4、必红），且 Task 与 ParallelTasks 两代理描述**都**含 `beta`；
   - `constructionPerformsInitialLoad`：构造后不调任何方法，两代理描述即含初始 specs
     的 agent（spec §4.3 初始装载）；
   - `summaryReflectsLoaded`：Loaded(M=6,N=2,K=1) → summary 含「共 6 个」「内置 4」
     「自定义文件 2」「跳过 1」。
2. 红。
3. 最小实现（spec §4.3 草签：构造体首行调 reload()、volatile lastLoaded、summary 格式串）。
4. 绿 → `feat: SubagentReloader —— 单次装载喂双代理与 /reload 摘要`。

## T4 装配接线：AgentTools / AgentRuntime / CodingAgent / SubmitHandler / CodeTuiApplication

**产出**：改 `AgentTools`（L605 段）、`AgentRuntime`(+1 字段=23)、`CodingAgent`（尾加重载
+ reloadSkills 扩展 + subagentsSummary）、`SubmitHandler`（default 方法）、
**`CodeTuiApplication`（L314-320 生产构造点改传 `runtime.subagentReloader()`——不传
不报错（null 合法），漏改则生产路径 `/reload` 永不重载子 agent、summary 恒空，
是本任务最隐蔽的假绿点，提交说明单列此行为）**；测试：
`CodingAgent` 联动用例 + 装配级安全钉。

1. 先读 `AgentRuntimeTest` L66-77 的 user.home 替换法与 `RuntimeToolSet` 既有断言。
   装配材料照 `AgentRuntimeTest` 现状：`new DeepSeekProvider("fake-key")` +
   `new ConversationState()` 即可 build，**不需要真 API key / McpRegistry**；取 Task
   工具描述的唯一现成通道是测试助手 `RuntimeToolSet.toolsOf(rt).get("Task")
   .getToolDefinition().description()`（AgentRuntime 无 Task 访问器）。写：
   - `customSubagentInTaskRosterAfterAssembly`：假 home/项目根放 `reviewer.md` →
     build 后 Task 描述含 `reviewer`。**先单独跑此用例**——它不引用新符号、用现有
     `AgentTools.build` 签名即可跑，能观察到**真断言红**（老 build 的 roster 只有内置
     4 个）；看清断言红后再补下面两条（引用新字段的用例此刻编译红，会掩蔽断言红，
     故必须分两步跑）；
   - `securityPinCustomAgentCannotGetTaskOrMemoryOrExitPlan`（spec §6.5 装配级钉）：
     spec `tools: [Task, MemoryCreate, ExitPlanMode]` → `rt.subagentRunner()
     .effectiveTools(spec)` 不含这三个名；**补正向对照**杀恒空实现——同 spec 换
     `tools: [Read]` → 产出含 `Read`；
   - `reloadSkillsTriggersSubagentReload`：**两分法**（单-client 桩构造硬编码
     reloader=null，注入不了真 Reloader）——null 桩路径用既有单-client 构造验证
     `subagentsSummary()` 返回 `""`、`reloadSkills()` 不炸；非 null 用例用**新尾加重载、
     其余参数全传 null + 真 SubagentReloader**（已核实全参构造体仅 `interjections
     != null` 才触碰 listener，reloadSkills/subagentsSummary 只读 reloader，全 null
     无 NPE——全参构造 + null 就是最小真注入，勿再找更轻接缝）→ `reloadSkills()`
     后计数 catalog +1、`subagentsSummary()` 非空。
2. 红（分两步：先 roster 用例断言红留证，再联动用例编译红）。
3. 最小实现：按 spec §4.3 装配段（factory 闭包持 dispatcher/runner 引用；装饰链包代理
   只包一次；`new AgentRuntime(` 唯一构造点 L837 加字段；CodingAgent 尾加重载默认
   null、桩构造置 null；SubmitHandler default `""`；**CodeTuiApplication L314 构造点
   传 `runtime.subagentReloader()`**）。
4. 绿（含**既有装配/运行时测试全量回归**：`AgentRuntimeTest`、`RuntimeToolSet` 相关、
   `CodingAgent` 相关、`AgentToolsSecurityTest`、`AgentToolsTodoReminderEndToEndTest`、
   `AgentToolsBackgroundWiringTest`——它们都消费 `AgentTools.build`）→
   `feat: 装配接线自定义子 agent —— Task/ParallelTasks 走热重载代理（含 CodeTuiApplication 生产构造点）`。

## T5 UI 输出与文案注释同步

**产出**：改 `CodeTuiView`（reloadSkills 摘要行 + L413 命令描述 + L4688 busy 提示）、
`SubagentRunner` L671-674 注释、`SubagentTool` L108 javadoc。

1. `CodeTuiView.reloadSkills()`：`printSkills()` 之后、`reloadMcpConfig()` 之前取
   `onSubmit.subagentsSummary()`，非空 `state.pushInfo(s)`。无独立测试（UI 层，靠 T4
   联动用例覆盖数据面）；文案改动跑既有 CodeTuiView 测试回归。
2. 文案注释同步（四处，行号见 spec §8）。
3. 绿 → `feat: /reload 输出自定义子 agent 摘要 + 文案注释同步`。

## T6 文档 + 变异验证 + 全量回归

1. `implementation-map.md`：§12「定义加载」段改写（六层发现/覆盖/热重载/装配点）、
   L552「22 字段 record」→ 23、速查表「子 agent」行补「自定义」；
   **`docs/guide/subagent.md` 是确定改动项**（实测 L62 明文「不支持用户自定义子 agent
   （没有扫描 `.codetui/agents/ 的代码）」——功能落地后该句为假；L47「四个内置」段
   一并补自定义入口说明）。
   → `docs: implementation-map 更新自定义子 agent`。
2. 变异测试（一次性，不留测试、工作树须还原干净）：
   - 变异 A：SubagentCatalog 装载序改成「先层后内置」→ T1 覆盖用例必红 → 还原；
   - 变异 B：SubagentReloader.reload() 改成两代理各自调 catalog → T3 计数断言必红 → 还原。
3. 全量：`mvn -pl springai-code-tui -am test` 全绿。
4. `--no-ff` 合并回 main，删除 feature 分支。
