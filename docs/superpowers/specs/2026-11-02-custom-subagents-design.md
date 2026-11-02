# 自定义子 agent（`.codetui/agents` 等六层目录）设计

- 日期：2026-11-02
- 状态：已核准（调查完成，等 plan 拆步）
- 分支：`feature/custom-subagents`

## 1. 目标

用户可以在六层约定目录下放置 `<名>.md`（frontmatter + 正文）定义自己的子 agent，
无需改代码、无需重启进程：

1. **发现**：`SubagentCatalog` 扫描六层目录（与 `SkillCatalog` 同层序同原则：
   用户 `.claude/agents` → 用户 `.agents/agents` → 用户 `.codetui/agents` →
   项目 `.claude/agents` → 项目 `.agents/agents` → 项目 `.codetui/agents`），
   与内置 4 个合并，同名后层覆盖（项目 > 用户 > 内置）。
2. **可用**：自定义 agent 立即成为 `Task` / `ParallelTasks` 的合法 `subagent_type`，
   描述进工具 roster，供主 agent 选择。
3. **热重载**：`/reload` 同时重扫子 agent 目录（与技能同一命令、同一种代理模式），
   roster 下一次请求即更新。
4. **容错**：单个 .md 坏（IO 失败 / 缺 name / 缺 description）只跳过该文件并记 warning，
   不影响其余文件与内置 4 个。

## 2. 非目标

- **`skills:` 字段的消费**（按 spec 预挂载技能正文）：`SubagentSpec.skills` 继续保持
  「解析但不生效」。它需要动 `SubagentRunner.effectiveSystemPrompt` 的组装与技能工具
  实例注入，是独立 feature，本期不做。
- **`/agents` 浏览命令或面板**：人对自定义 agent 的最小可见性由 `/reload` 输出行承担
  （`子 agent：内置 4 + 自定义 N`），清单本身在 Task 工具描述里（模型侧）。
- **AGENTS.md / 子 agent 递归**：自定义 agent 不可能递归 fan-out（见 §5 安全论证），
  无需新增防护代码。

## 3. 已核准的事实依据（实测代码，非推测）

1. `SubagentLoader.parse(String uri)` 用 `DefaultResourceLoader`，**已支持 `file:` 协议**，
   今天没有任何 `file:` 调用方（`implementation-map.md` §12 与 `SubagentRunner` L673 注释
   都记载了这一点）。frontmatter 解析（name/description/tools/disallowedTools/model/skills）
   现成可用。
2. `AgentTools.build` L605：`subagentSpecs = SubagentLoader.loadBuiltins()` 是 **build 期一次性
   快照**，L614/L628 分别烘焙进 `Task` / `ParallelTasks` 的工具描述 roster 与路由 map——
   所以「运行期新增定义」必须走代理，不能原地改 map。
3. `ReloadableSkillTool` 已验证同构方案可行：实例恒定的 `ToolCallback` 代理 +
   `volatile Snapshot` 原子替换；依据是 Spring AI `DefaultToolCallingManager
   .resolveToolDefinitions` **每次请求**都现调 `getToolDefinition()`，故代理转发后
   下一次请求模型即见新 roster。
4. `/reload` 链路现成：`CodeTuiView.reloadSkills()` → `onSubmit.reloadSkills()` →
   `CodingAgent.reloadSkills()`（`SubmitHandler.reloadSkills` 是 default 空实现，桩路径安全）。
5. 子 agent 工具集来自 `decoratedList` 过滤（`SubagentRunner.effectiveTools`），
   而 `Task`/`ParallelTasks`/`TaskOutput`/`ListTasks`/记忆工具/`ExitPlanMode` **都不在
   `decoratedList` 里**（`implementation-map.md` §8.1）。
6. 六层目录约定的理由与 `SkillCatalog` 完全同源（skills.sh / agents.md 生态按检测到的
   agent 定向落目录，Claude Code 落 `.claude`，Cursor/Codex 等落 `.agents`）——2026-09-08
   本机实测记录见 `SkillCatalog` 类注释。子 agent 侧的**假设**（未实测，较技能层弱）：
   `.claude/agents/*.md` 是 Claude Code 官方既成约定；`.agents/agents` 是新兴桥接约定
   （尚未成官方标准）。多扫这两层无害（空目录静默跳过），本设计按「对齐技能层口径」
   采纳，**不为该断言背书**。
   （`.gemini` 不扫：与 `.claude`/`.agents` 不同，它不是跨 agent 通用落点，
   技能层也没有认它，保持口径一致。）

## 4. 方案

### 4.1 `SubagentCatalog`（新类，`agent/subagent` 包）

```java
public final class SubagentCatalog {
    /** DIR_NAME = ".codetui/agents"；COMPAT_DIR_NAMES = [".claude/agents", ".agents/agents"] */
    record Loaded(Map<String, SubagentSpec> specs, int customCount, List<String> warnings) {}

    public static Loaded load(Path projectRoot);                 // 生产：真实 ~（public：AgentTools 跨包调用）
    static Loaded load(Path projectRoot, Path homeDir);          // 测试接缝（包级，@TempDir 假 home）
}
```

- 装载顺序：`SubagentLoader.loadBuiltins()`（内置 4 个先入 LinkedHashMap）→ 六层逐层
  `Files.list`（try-with-resources；仅 `.md` 后缀；`.sorted()` 按文件名排序）→
  每个文件 `SubagentLoader.parse(path.toUri().toString())` → `put` 覆盖。
  **URI 必须用 `Path.toUri()`**：手拼 `"file:" + path` 对含 `#`/`?` 的文件名必坏
  （URL fragment/query 截断）。排序消除目录迭代序的不确定性（同层同名覆盖可复现、
  roster 行序跨 reload 不漂移）。目录 API 钉死 `Files.list` + `sorted()` 一种写法。
- 单文件失败（IO 异常 / `parse` 抛 / **`name` 为空** / **`description` 为空**）→
  `warnings.add("<路径>: <原因>")` 跳过，绝不炸整体（纪律同 `HookConfigLoader`）。
  `name` 空是 `str()` 缺键返回 `""`、不抛（已核实 `SubagentLoader` L61-64）；**`description`
  空同样跳过**——对齐 Claude Code 官方语义（有 name 无 description 的文件跳过并记日志），
  否则 roster 里出现 `- foo: ` 行，agent 可路由但模型无从选择。
- 层目录不存在 → 静默跳过。
- 摘要口径：`「共 M 个（内置 4 · 自定义文件 N，跳过 K 个坏文件）」`，其中
  M = `specs.size()`（含覆盖内置后仍存活的），N = 六层成功装载的文件数（含覆盖内置的），
  K = `warnings.size()`。M 与 roster 实际行数严格一致。
- 文件格式：`<层>/<名>.md`（**平铺**，一个文件一个 agent，name 以 frontmatter 为准，
  文件名不参与——与 Claude Code `.claude/agents/*.md` 同形，也是 `parse` 已支持的形态）。

### 4.2 `ReloadableSubagentTool`（新类，`agent/subagent` 包）

- `implements ToolCallback`，**public 构造**收 **`Function<Map<String, SubagentSpec>, ToolCallback> factory`**
  （AgentTools 跨包调用；装配时以固定材料——models roster、Dispatcher、BackgroundDispatcher、
  Task/ParallelTasks 哪个 create——闭包出 factory；factory 每次调用产出**完整重建**的工具：
  新描述 roster + 新路由 map）。构造不建初始 delegate——由 `SubagentReloader` 构造时的
  首次 `reload()` 喂入（见 §4.3 初始装载）；
- `reload(Map<String, SubagentSpec> specs)`：`factory.apply(specs)` 重建 delegate，
  `volatile` 字段原子替换。**payload 由外部喂入**而非代理自己调 loader——保证 Task 与
  ParallelTasks 出自**同一次** `SubagentCatalog.load` 快照（两个代理各自 load 会扫两遍盘、
  且存在「Task 有 x 而 ParallelTasks 没有」的双快照窗口）。
- `getToolDefinition()` / `getToolMetadata()` / 两个 `call(...)` 全部转发当前 delegate；
  转发处每次读一次 volatile 引用（照抄 `ReloadableSkillTool.snapshot.delegate()` 模式）。
- 与 `ReloadableSkillTool` 的差异：**无空兜底**（specs 永远 ≥ 内置 4 个，factory 不可能
  产出空 roster）、无 description 修复链。
- **并发论证**（类 javadoc 必须写，与 ReloadableSkillTool 同源）：`/reload` 由 UI 线程调、
  委派可能正在飞行——volatile 快照原子替换，在飞调用持有的旧 delegate 引用继续用完即弃
  （旧 delegate 不可变：其 specs map 构建完成后无人再写，路由闭包只读）。这与 MCP reload
  需要 busy 守卫不同：代理换快照不关连接、不摘在飞工具。

### 4.3 重载协调者 `SubagentReloader`（新类）与装配接线

摘要通道与快照单次性由一个小协调类统一承担（评审否掉了「经 listener pushInfo」——
`AgentListener` 根本没有该方法；也否掉了「两代理共享 loader Supplier」——共享代码不共享结果）：

```java
public final class SubagentReloader {          // agent/subagent 包
    /** public 构造：AgentTools 跨包装配（参照 ReloadableSkillTool 的 public 构造先例）。 */
    public SubagentReloader(Supplier<SubagentCatalog.Loaded> catalog,
                            ReloadableSubagentTool task, ReloadableSubagentTool parallel);

    /** 一次 catalog.get() → task.reload(specs) + parallel.reload(specs) → 记住 Loaded。 */
    public void reload();

    /** 摘要「子 agent：共 M 个（内置 4 · 自定义文件 N，跳过 K 个坏文件：…）」。
     *  构造体即调 reload()（初始装载一并喂两代理），summary() 永不空指针。 */
    public String summary();
}
```

**初始装载**：`SubagentReloader` 构造体第一件事就是 `reload()`——两个代理的 delegate
在装配期就有了全量 specs（内置 + 自定义），不存在「首次 reload 前描述为空」的窗口；
`ReloadableSubagentTool` 因此只收 factory、不收初始 specs（首次 delegate 由 Reloader
构造时的 reload 产出，装配 build ChatClient 发生在其后，无 NPE 窗口）。

- `AgentTools.build`：L605 处改为建 `SubagentCatalog` supplier（生产 `() -> load(root)`）、
  两个 `ReloadableSubagentTool`（factory 闭包持**同一组** L615-617/L629-630 dispatcher 引用
  与 runner 引用——它们捕获的 `subagentRunner` 建一次永不替换，delegate 重建后闭包持续
  成立）、`SubagentReloader` 组合三者；Task/ParallelTasks 的装饰链（Permission →
  ToolEventCallback）**包在代理上、只包一次**（delegate 重建不穿透装饰层）。
- `AgentRuntime` record 加一个字段 `SubagentReloader subagentReloader`（全仓唯一构造点
  `AgentTools` L837，约 25 个测试只用访问器，零构造调用——加字段只动一处）。
- `CodingAgent`：构造链**尾加** `SubagentReloader subagentReloader` 重载（默认 null，
  不破既有测试；单 client 桩构造 L176-225 同步置 null）；
  `reloadSkills()` 里 `if (subagentReloader != null) subagentReloader.reload();`；
  新实现 `SubmitHandler` 的 `String subagentsSummary()`（default 返回 `""`），
  返回 `subagentReloader == null ? "" : subagentReloader.summary()`。
- `CodeTuiView.reloadSkills()`：技能行**之后**（L4673 `onSubmit.reloadSkills()` 返回后、
  view 自己打技能行后）`String s = onSubmit.subagentsSummary();` 非空则 `state.pushInfo(s)`。
  保序由 view 侧输出天然保证，headless `-p` 无 `/reload` 入口、不涉及。

### 4.4 安全与边界（不写新代码，靠既有结构）

- 自定义 agent 的 `tools:` 写 `Task`/`ParallelTasks`/`TaskOutput`/`ListTasks`/
  `Memory*`/`ExitPlanMode` 都无效——`filterTools` 从 `decoratedList` 过滤，这些工具
  不在其中（§3.5）。递归 fan-out、写长期记忆、替用户切模式，结构上不可能。
- `model:` 字段沿用既有 `resolveSelection`（含跨 provider 路由与 2026-09-09 修复的
  严格报错），无新语义。
- 权限层不变：自定义 agent 的工具调用照走三层装饰链，UNKNOWN 工具（MCP）照旧 ASK/DENY。

## 5. 被淘汰的备选及理由

| 备选 | 淘汰理由 |
| --- | --- |
| 只扫 `.codetui/agents` 两层（用户+项目） | 装不进生态：社区/同事仓库带来的定义落在 `.claude/agents`（Claude Code）或 `.agents/agents`（agents.md 标准），只认自有目录等于每个用户手抄一遍。技能层已为同一理由认了三约定（`SkillCatalog` 类注释，2026-09-08 实测），口径必须对齐，否则「技能六层、agent 两层」两套记忆负担。 |
| 扫 `.gemini/agents` | `.gemini` 是 Gemini 专属落点而非跨 agent 通用约定；技能层也没认它。三约定已覆盖 Claude Code + 通用标准 + 自有，边际收益趋零。 |
| 目录式 agent（`<名>/AGENT.md`，同技能的 `<名>/SKILL.md` 形态） | 子 agent 生态（Claude Code `.claude/agents/*.md`）是**平铺单文件**形态，没有多文件需求（无 references/scripts 附属物）；跟生态走降低迁移成本。 |
| 静态装载（重启生效），不做 `/reload` 热重载 | 技能已立下「放文件 + /reload 即生效」的服务品质，子 agent 反而要重启会造成体验倒退；且 `ReloadableSkillTool` 代理模式已验证、实现成本一个类，没有理由省。 |
| `specs` map 改为可变 ConcurrentHashMap，装配时原地替换内容 | Task/ParallelTasks 的**工具描述 roster 是 build 期烘焙的**（`DESCRIPTION_TEMPLATE.formatted(roster, ...)`），改 map 只改了路由、模型看不到新 agent——必须重建工具（走代理）才完整。原地改 map 是半截子方案。 |
| `skills:` 预挂载一并实现 | 涉及 `SubagentRunner.effectiveSystemPrompt` 组装与技能工具实例注入，验收面独立；与「自定义 agent 的发现/装载/重载」正交。合并做会把两个 feature 的红绿搅在一起。 |

## 6. 测试策略（对应仓库「先写测试、真看到红」纪律）

1. **SubagentCatalogTest**（`@TempDir` 假 home + 假项目根，隔离真实 `~`）：
   - 六层各放一个 .md → 全部出现在 `specs`，摘要计数 N=6 正确；
   - 项目 `.codetui/agents/explore.md` → **覆盖**内置 explore 的 systemPrompt（断言新正文）；
   - 同名跨层（用户 `.codetui/agents/x.md` vs 项目 `.claude/agents/x.md`）→ 项目胜出；
   - 坏文件（缺 `name:`）→ 跳过、`warnings` 含路径、其余文件与内置 4 个不受影响；
   - **有 name 无 description** → 同样跳过进 warnings（对齐 Claude Code 语义）；
   - 全空（无任何目录）→ 只剩内置 4 个、`warnings` 空。
2. **ReloadableSubagentToolTest**（测试注入 factory：用 `SubagentTool.create(specs, stub)` 造真 delegate，
   stub Dispatcher 返回 `spec.name()` 证明路由走的是**当前** specs）：
   - 构造后 `getToolDefinition().description()` roster 含 `alpha`；
   - **路由换轨**（杀虫关键）：`call(subagent_type="alpha")` 返回 `alpha`；`reload(betaSpecs)` 后
     roster 含 `beta` 不含 `alpha`，且 `call(subagent_type="beta")` 返回 `beta`——「重建描述、
     复用旧路由 map」的半截实现在此必红；
   - 未知 type 抛出的错误信息里的 Available 列表随 reload 更新（路由 map 真换了）。
3. **SubagentReloaderTest**：
   - **单次 load 双工具同快照**：计数 Supplier 记录 `catalog.get()` 次数，一次 `reload()`
     只 load 一次；Task 与 ParallelTasks 两个代理的描述同步含新 agent；
   - `summary()` 文案与 Loaded 计数一致（M/N/K 各归各位）。
4. **CodingAgentReloadTest（或并入既有装配测试）**：`subagentReloader` 非 null 时
   `reloadSkills()` 触发 reload；`subagentsSummary()` 返回协调者摘要；null（桩路径）
   两方法都安全（`""` / 不炸）。
5. **安全结构回归钉**（防将来有人把 Task 塞进 decoratedList 而论证无声失效）：
   **装配级**断言——`AgentTools.build(...)` 出 `AgentRuntime` 后，取
   `rt.subagentRunner()`，对 `tools: [Task, MemoryCreate, ExitPlanMode]` 的自定义 spec 调
   `effectiveTools(spec)`，断言产出**不含**这三个名字（`subagentRunner()` 访问器与
   public `effectiveTools` 均已具备；本机 `~` 隔离沿用 §6 尾注的 `user.home` 替换法）。
   **不做**「自建 runner + 自造工具列表」的单测版——自造列表里本来就没有 Task，
   断言恒绿，是看不见装配面改动的假绿灯。
6. **变异测试**（一次性，不留在测试里）：把覆盖逻辑改成「先层后内置」（覆盖失效），
   确认 1 中覆盖用例变红；把 `SubagentReloader.reload()` 改成两代理各自取 loader，
   确认 3 的计数断言变红；改回。

注：装配级测试（`RuntimeToolSet`/`AgentRuntimeTest`）若新增 roster 断言，必须按
`AgentRuntimeTest` L66-77 的 `user.home` 临时替换法隔离真实 `~`（装配的 home 三层
来自真实用户目录，contains 型既有断言不受影响，新断言会受本机残留定义污染）。

## 7. 风险与开放问题

- **文件名与 frontmatter name 不一致**：以 frontmatter 为准（文件名只是发现入口）。
  两个不同文件声明同一 name → 后层覆盖前层（与技能同名去重同一语义），不告警——
  技能层也是静默覆盖，保持一致。
- **超大目录**：`Files.list` 平铺一层，文件数天然有限（十几个量级），无递归无性能问题。
- **`.md` 之外的文件**（`.DS_Store`、备份 `.md.bak`）：只认 `.md` 后缀；解析失败也只会
  进 warnings，不会炸。
- **headless `-p`**：走同一 `AgentTools.build`，自动获得自定义 agent，无需改动（也不新增
  测试——装配级既有测试覆盖）。

## 8. 交付物清单

- `agent/subagent/SubagentCatalog.java`（新）
- `agent/subagent/ReloadableSubagentTool.java`（新）
- `agent/subagent/SubagentReloader.java`（新）
- `AgentTools.java`（装配点改造 + `AgentRuntime` 加字段 → record 23 字段）
- `CodingAgent.java`（尾加构造重载 + `reloadSkills` 扩展 + `subagentsSummary` 实现）
- `agent/seam/SubmitHandler.java`（default `String subagentsSummary()` 返回 `""`）
- `CodeTuiView.java`（`/reload` 技能行后追加子 agent 摘要行 + L413 命令描述与
  L4688 busy 提示文案同步「子 agent」）
- 文案/注释同步四处：`SubagentRunner` L671-674「今天没有自定义 agent」注释改写、
  `SubagentTool` L108 `@param subagent_type` 注释改「取值见 Task 工具描述 roster」、
  `implementation-map.md` §12「不支持用户自定义子 agent」段改写 + L552 字段数
- 测试：`SubagentCatalogTest`、`ReloadableSubagentToolTest`、`SubagentReloaderTest`、
  reload 联动用例、安全结构回归钉

