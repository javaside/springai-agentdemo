# 手动指定技能（`/skill` 选择器）— 设计文档

日期：2026-07-03
模块：`springai-code-tui`
状态：**v1 已实现并通过测试**（新增 CodingAgentSkillInjectTest / ConversationStateQueueTest；121 项全绿。生产已在 CodeTuiApplication 装配 skillTool；/skill 选择器待手动冒烟）
前置：[技能能力接入设计](2026-07-03-skills-support-design.md)（v1：模型自主调用 + `/skills` 只读清单）
对应前置文档的 §9.2 未来工作「`/skill <name>` 手动强制注入」。

---

## 1. 背景与目标

v1 的技能能力里，**由模型自主决定**是否调用某技能：模型只看到各技能的 name+description，
判断有用时调 `Skill("name")` 工具，库返回正文，模型据此作答。

但有时**用户比模型更清楚该用哪个技能**，希望直接指定，而不把决定权交回模型。
本设计新增 **`/skill` 命令**：用户手动选一个技能挂载，紧接着的下一条消息就带上该技能的完整指令。

### 1.1 目标

1. 用户能通过 `/skill` 选择器（仿 `/model`）挑选一个技能并「挂载」。
2. 挂载**一次性**生效：只作用于紧接着发出的那一条消息，消费后自动清除。
3. 手动注入与模型自动调用**内容一致、UI 表现一致**——用户能在界面看到「Skill 工具被调用」，
   就像模型自己调用那样。
4. 无可用技能、未挂载、挂载后取消等边界都优雅处理。

### 1.2 非目标

- 不做粘性挂载（挂着直到手动取消）——v1 只做一次性。
- 不做 `@提及` 行内注入、不做一次挂载多个技能。
- 不改动模型自主调用（工具）路径的任何行为。

---

## 2. 关键决策（已与用户确认）

| 决策点 | 结论 | 理由 |
| --- | --- | --- |
| 交互形态 | **斜杠选择器 `/skill`**（仿 `/model`） | 复用现有选择器/状态栏/命令分发，实现最轻；不必记技能名 |
| 生效范围 | **一次性**（仅紧接着的下一条消息） | 避免用户忘了还挂着、导致后续消息都被注入 |
| 注入方式 | **发送前真正调用（被装饰的）Skill 工具** | 见 §3，内容零漂移 + UI 免费统一 |
| 工具入参 | **走 `call("{\"command\":\"<name>\"}", ctx)`** | 与模型调用同一入口，事件/返回格式全一致 |
| 忙碌排队 | **挂载随消息入队**（出队时仍带技能） | 用户已确认；语义最直观 |

---

## 3. 核心机制：发送前调用真工具（决策 A）

### 3.1 为什么不是「应用层手拼字符串」，也不是「强制模型调工具」

已核实 `SkillsTool` 的字节码：模型调用 `Skill("name")` 时，工具（一个普通 `FunctionToolCallback`）
返回的就是一段固定文本：

```
Base directory for this skill: {basePath}

{SKILL.md 正文}
```

三种可选实现：

- **手拼字符串**：应用层自己拼 `<skill_instruction>…</skill_instruction>`。缺点：格式可能与库漂移，
  且 UI 不会显示工具调用。
- **强制模型调工具**：靠提示词或 `tool_choice` 逼模型这一轮调 `Skill`。缺点：库无「强制调某工具」入口，
  只能*请求*模型（可能不调/调错/多一轮），把用户的明确意图退化成概率事件；且至少两轮、更慢。
- **✅ 发送前真正调用被装饰的 Skill 工具（本方案）**：在消息发给模型**之前**，应用主动调一次
  那个**已被 `ToolEventCallback` 装饰**的 Skill 工具，拿到正文后拼进消息再发。

### 3.2 本方案为何最优

- **确定性**：正文一定进上下文，不靠模型自觉（符合「用户手动指定」的语义）。
- **内容零漂移**：调的是真工具，返回文本与自动路径**逐字一致**。
- **UI 免费统一**：被调的工具外层是 `ToolEventCallback`，其 `call()` 会发
  `onToolStarted`/`onToolFinished`——于是手动路径**自动**在 TUI 显示出与自动路径**相同**的工具活动行。
- **turnId 天然对齐**：用本回合 turnId 构造 `ToolContext(Map.of("turnId", turnId))` 传入，
  工具事件自然归属到这一回合。
- **一轮出结果**：正文发送前已就位，模型无需再调工具。

唯一区别于自动路径的只有「谁触发调用」：自动=模型，手动=应用（因用户指定）。

### 3.3 注入后的消息形态

```
<skill_instruction>
Base directory for this skill: {basePath}

{SKILL.md 正文}
</skill_instruction>

{用户原始问题}
```

- `{basePath}` 是技能所在目录。项目级技能 basePath 在项目根内，模型可 `read` 其附属文件；
  用户级（`~/.codetui/skills`）在 FileSystemTools 边界外——与自动路径限制一致，非本方案新增问题。
- 会话记忆存的是**注入后的完整消息**（与模型实际所见一致），压缩/回溯语义不受影响。

---

## 4. 组件与改动

改动集中在 `agent` 与 `ui` 两包，复用度高。`SkillCatalog` **无需改动**（不再需要额外正文映射——直接调工具拿）。

### 4.1 `AgentTools.build` / `AgentRuntime` — 上抛「被装饰的 Skill 工具」

现在装饰后的 Skill 工具混在 `decorated[]` 里注册给 ChatClient 后就没了引用。改为单独保留引用：
`AgentRuntime` 新增字段 `ToolCallback skillTool`（无技能时为 `null`）。其余装配不变。

### 4.2 `CodingAgent` — 新增带技能的提交

构造函数多收一个 `ToolCallback skillTool`（可空）。新增：

```java
@Override
public Disposable submit(String text, String skillName) {
    long turnId = activeTurnId.incrementAndGet();
    listener.onTurnStarted(turnId);
    listener.onUserMessage(turnId, text);
    String effectiveText = text;
    if (skillName != null && skillTool != null) {
        // 发送前真正调用（被 ToolEventCallback 装饰的）Skill 工具：触发工具事件（UI 可见）+ 拿正文
        ToolContext ctx = new ToolContext(Map.of("turnId", turnId));
        String body = skillTool.call(toJsonCommand(skillName), ctx);
        effectiveText = "<skill_instruction>\n" + body + "\n</skill_instruction>\n\n" + text;
    }
    // …余下与现有 submit 完全一致（options/system/toolContext/advisors/stream/doOnNext/…），
    //   只是把 .user(text) 换成 .user(effectiveText)
}

@Override
public Disposable submit(String text) { return submit(text, null); }   // 保留原入口，委托
```

- `toJsonCommand(name)` 生成 `{"command":"<name>"}`（技能名来自选择器选中、非用户手打，无解析风险；
  仍做最小转义以防技能名含引号）。
- 工具调用失败（未知技能名等）：`ToolEventCallback` 已发 `onToolFinished(success=false)`，
  此处兜底为「不注入、按原文发送」，不使整条消息失败。

### 4.3 `SubmitHandler` — 加默认方法

```java
/** 带指定技能提交：发送前先调用该技能工具、把正文注入到 text 前。skillName 为 null 等价普通 submit。 */
default Disposable submit(String text, String skillName) { return submit(text); }
```

### 4.4 `ConversationState` — 排队项携带技能（影响面很小）

内部队列元素从 `String` 升级为 `record Queued(String text, String skill)`：

```java
public record Queued(String text, String skill) {}
private final Deque<Queued> queued = new ArrayDeque<>();

public synchronized void enqueue(String msg, String skill) { queued.add(new Queued(msg, skill)); }
public synchronized Queued pollQueued() { return queued.poll(); }          // 返回整个 record
public synchronized int queuedCount() { return queued.size(); }
public synchronized void clearQueued() { queued.clear(); }
public synchronized List<String> queuedSnapshot() {                        // 渲染仍只需文本，签名不变
    return queued.stream().map(Queued::text).toList();
}
```

`queuedSnapshot()` 返回类型不变（`List<String>`），故排队面板渲染 `queuedChildren` **不用改**。
只有 `enqueue`（多一个参数）与 `pollQueued`（返回 record）两处调用点需跟随更新。

### 4.5 `CodeTuiView` — 选择器 + 一次性挂载（照抄 `/model` 那套）

- 新增字段：`pickingSkill`（选择器激活）、`pendingSkill`（已挂载、待生效的技能名，`String`，可空）。
- `COMMANDS` 加 `new SlashCommand("/skill", "指定技能（下条消息生效）")`——自动获得斜杠补全与 `/help` 列举。
- `submitInput()` 加分支：`/skill` → `openSkillPicker()`（技能清单空则提示「当前没有可用技能」，不弹选择器）。
- `openSkillPicker`/`onSkillPickerKey`/`skillPickerChildren` 三方法仿 `/model` 的
  `openModelPicker`/`onModelPickerKey`/`modelPickerChildren`，把「选模型」换成「设 `pendingSkill`」。
  Enter 挂载后 `state.setNotice(...)` 或走状态栏提示。
- **发送路径**（`dispatch` 与入队）消费挂载：
  ```java
  // 空闲直接发：
  private void dispatch(String text) {
      String skill = pendingSkill; pendingSkill = null;    // 一次性：取出即清
      current = onSubmit.submit(text, skill);
      // …原有「使用模型 X」提示不变
  }
  // 忙碌入队：挂载随消息入队，随即清空全局挂载
  state.enqueue(text, pendingSkill); pendingSkill = null;
  // 出队：
  Queued q = state.pollQueued();
  if (q != null) onSubmit.submit(q.text(), q.skill());   // drain 里不经过 dispatch 的模型提示，或按需保留
  ```
  注意：`drain()` 现有出队走的是 `dispatch(next)`；改为出队 `Queued` 后，可让 `dispatch` 重载
  `dispatch(String text, String skill)`，`submitInput` 与 `drain` 都调它，避免两处逻辑分叉。
- 状态栏：`pendingSkill != null` 时显示「已挂载 {name} · 下条生效」（优先级低于思考/压缩指示器）。
- Esc（空闲态）：若有挂载则清除并提示「已取消技能挂载」；否则维持原 Esc 语义（取消回合）。

---

## 5. 数据流（端到端）

```
用户输入 /skill
  └─ openSkillPicker()（清单空→提示，不弹）
       └─ ↑↓ 选 / Enter 挂载 → pendingSkill = "git-commit-message"
            └─ 状态栏：已挂载 git-commit-message · 下条生效

用户打问题 + Enter
  ├─ 空闲：dispatch(text, pendingSkill)；pendingSkill=null
  │    └─ CodingAgent.submit(text, skill)
  │         ├─ 发送前：skillTool.call("{command:skill}", ctx{turnId})
  │         │     └─ ToolEventCallback → onToolStarted/onToolFinished（UI 显示工具活动）
  │         ├─ effectiveText = <skill_instruction>正文</skill_instruction> + text
  │         └─ 正常流式发送（会话记忆存 effectiveText）
  └─ 忙碌：enqueue(text, pendingSkill)；pendingSkill=null
       └─ 回合结束 drain 出队 → submit(text, skill)（同上）
```

---

## 6. 测试计划

- `CodingAgentSkillInjectTest`：
  - `submit(text, "git-commit-message")` → 用假 listener 断言触发了 `onToolStarted`/`onToolFinished`
    （name="Skill"），且注入后文本含技能正文与 `<skill_instruction>` 包裹；
  - `submit(text, null)` 与普通 `submit(text)` 行为等价（不注入、无工具事件）；
  - `skillTool==null` 或技能名未知时不崩、不注入、按原文发送。
- `ConversationStateQueueTest`（新增或并入现有）：`enqueue(text, skill)` → `pollQueued()` 得到
  `Queued(text, skill)`；`queuedSnapshot()` 仍只返回文本列表。
- `CodeTuiView` 一次性语义（若 UI 可测）：dispatch 后 `pendingSkill` 归零；忙碌时技能随消息入队、
  出队仍带技能；Esc 清挂载。

---

## 7. 已知限制与权衡

1. **一次性可能反直觉**：连续多轮想用同一技能需每轮 `/skill` 一次。这是刻意取舍（避免忘记挂载）；
   若反馈强烈，v2 可加粘性模式。
2. **用户级技能附属文件仍读不到**：与自动路径同源限制（FileSystemTools 边界），见 §3.3 与前置文档 §7。
3. **注入占本轮上下文**：技能正文按原文进上下文（与自动路径一致）；超大技能会占较多 token，属固有成本。

---

## 8. 未来工作（v2+）

- 粘性挂载（挂着直到取消）。
- 一次挂载多个技能 / `@提及` 行内注入。
- `/skill <name>` 直接带名（跳过选择器）作为快捷路径。
