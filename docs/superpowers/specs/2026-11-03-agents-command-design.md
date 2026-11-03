# /agents 列表命令设计（从简版）

- 日期：2026-11-03
- 前置：自定义子 agent 已合 main（`486a804b`）；其 spec §2 把「/agents 浏览命令」列为非目标，
  本设计补上这个缺口（用户侧当前只能靠 /reload 计数或问模型看名单）。
- 分支：`feature/agents-command`

## 1. 目标

`/agents` 把当前全部可用子 agent（内置 + 六层自定义，含同名覆盖后的胜者）打进
scrollback：一行 `- name  [来源]` + 一行缩进 description——完全仿 `/skills` 的
`printSkills()` 样式。只读、无闸门（任何时候可看，同 `/skills`）。

## 2. 非目标

- 不做面板/选择器（无交互需求：子 agent 不是「选用」对象，是模型按 subagent_type 委派的）。
- 不改 `/reload` 摘要行（计数口径已定，/agents 看明细，分工明确）。
- 不显示 systemPrompt 正文 / tools / model（那是实现细节，`guide/subagent.md` 讲）。

## 3. 方案

### 3.1 来源层标签：`AgentInfo`（新 record，agent/subagent 包）

`SubagentCatalog` 目前不记来源，而标签是 /agents 的核心价值（同名覆盖后「谁赢了」
只有来源能回答）。仿 `SkillInfo(name, description, source)`：

```java
public record AgentInfo(String name, String description, String source) {}
```

source 标签口径：**六个自定义层标签与技能侧逐字同口径**（`用户·claude` / `用户·agents` /
`用户` / `项目·claude` / `项目·agents` / `项目`，`SkillCatalog` 层标签先例，用户/项目级
`.codetui` 是裸「用户/项目」），另加 agent 侧独有的 `内置`（技能无内置层）。
不让 `SubagentSpec` 泄进 UI（systemPrompt 大正文 UI 不需要——同 SkillInfo 挡住
库类型的纪律）。备选「存 `Map<String,String> sources`、读取时从 specs 派生」淘汰：
存整 AgentInfo 对齐 SkillInfo 先例，派生方案多一次组装无增益。

### 3.2 `SubagentCatalog.Loaded` 加 `List<AgentInfo> infos`

- 装载时同步维护 `LinkedHashMap<String, AgentInfo>`（与 specs 同 key 同序；
  put 覆盖时 infos 一并覆盖——同名覆盖后标签自动跟随胜者）；
- `Loaded(specs, customCount, warnings, infos)` 四组件。**破坏性变更**（main 无构造点，
  仅 test 两处）：`SubagentReloaderTest` 的 `loaded()` 助手（3 处调用共用，改一行）与
  `AgentRuntimeTest` L206 内联构造——后者第四参**必须传内置 4 个的非空 infos**
  （机械补 `List.of()` 会让 §4.3「agents() 非空」断言恒假）；
- `summary()` 不动（仍从 specs/customCount/warnings 算，不依赖 infos）。
- 双 map（specs↔infos）「同 key 同序」由装载循环**同一次迭代双写**构造性保证
  （SkillCatalog 的 byName+fixes 同款先例）；§4.1 另加 keySet 等序断言显式锁死
  「只写 specs 忘写 infos」的将来漂移。

### 3.3 seam 与 reloader

- `SubagentReloader` 加 `public List<AgentInfo> agents()` → `lastLoaded.infos()`
  （构造即首装，永不为 null）；
- `SubmitHandler` 加 `default List<AgentInfo> agents() { return List.of(); }`
  （返回类型引用 agent.subagent 包——`skills()` 返回 skill 包 `SkillInfo` 的同款
  跨包先例，seam 本就是 UI↔agent 的桥）；
- `CodingAgent` `@Override agents()` → `subagentReloader == null ? List.of() :
  subagentReloader.agents()`。

### 3.4 UI：`/agents` 命令（CodeTuiView）

- `COMMANDS` 表加一行，插在 `/skills` 与 `/reload` 之间（语义相邻）；
  描述「列出可用子 agent（内置 + 自定义，含来源层）」。
- `submitInput` 字符串比较链加 `cmd.equals("/agents")` 分支（放 `/skills` 分支旁）；
  无前缀冲突（`/agents` 不是任何既有命令的前缀，反向也不是）。
- `printAgents()` 仿 `printSkills()`：空列表（仅桩路径可达，生产恒 ≥ 内置 4）提示
  「当前装配未提供子 agent 清单」；非空则头行「可用子 agent（Task/ParallelTasks 按
  subagent_type 委派）：」+ 每条两行。

## 4. 测试策略

1. `SubagentCatalogTest` 补用例：六层各放一个 .md → `infos` 顺序 = 内置 4 先、
   自定义按层序，各条 source 标签正确；项目层覆盖内置 explore → 该条 source 变
   「项目」（标签跟随胜者）；**keySet 等序断言**——
   `assertEquals(List.copyOf(loaded.specs().keySet()), loaded.infos().stream().map(AgentInfo::name).toList())`。
2. `SubagentReloaderTest`：构造后 `agents()` 即含初始 agent；reload 后跟随换新
   （在既有用例上补断言，不新开用例）。
3. `AgentRuntimeTest` 联动（reloadSkillsTriggersSubagentReload 旁）：非 null 路径
   `agents()` 非空；null 桩路径返回空列表。
4. UI 层无测试（`printSkills` 同样无；数据面由上覆盖）。

## 5. 交付物

- `agent/subagent/AgentInfo.java`（新）
- `SubagentCatalog`（Loaded + infos 维护）、`SubagentReloader`（agents()）、
  `SubmitHandler`、`CodingAgent`、`CodeTuiView`（COMMANDS + 分支 + printAgents）
- 测试：CatalogTest/ReloaderTest/AgentRuntimeTest 补断言
- 文档：`reference.md` 斜杠命令表加 `/agents` 行（顺手补 L57 `/reload` 行漏写的
  「子 agent 目录重扫」）；`guide/subagent.md` §3 自定义段提 `/agents`，六层表
  加「/agents 显示标签」列（否则用户第一次看到「用户·agents」无处查）；
  `implementation-map.md` §5 命令表加行
- 不动 README（「子 agent 与计划」行已概括）与 CHANGELOG（发版动作）
