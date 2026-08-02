# 设计文档：视觉输入（图片真正进模型）

- 日期：2026-08-02
- 模块：`springai-code-tui`
- 类型：设计（brainstorming spec）
- 前置：[2026-07-13 能力感知的媒体外置](2026-07-13-capability-aware-media-externalization-design.md) —— 本文即那份文档 §9 里记为 **Path B** 的兑现

> **一句话**：会话里永远只有文本引用；图片字节只在**出站请求组装的最后一刻**、且**当轮需要**时才变成真 `Media`。
> 这样上下文占用与图片总数无关，只与「当轮看几张」有关。

---

## 1. 背景

### 1.1 今天的状态

七月那期建好了完整的媒体外置地基，但**刻意留了一个关不上的口子**：

```java
// TextReferenceMediaHandler —— 本期唯一实现
public boolean canDeliver(MediaKind kind, ModelCapabilities caps) {
    // Path B：当接上原生 image 注入器后，这里改为
    //   return kind == MediaKind.IMAGE && caps.supportsImageInput();
    return false;
}
```

现状盘点（已核实）：

| 已有 | 位置 |
|---|---|
| `ModelCapabilities(supportsImageInput, supportsVideoInput)` | 每回合冻结进 `ToolContext` |
| `LlmProvider.capabilities(modelId)` | 默认 `TEXT_ONLY`，五家**全未覆写** |
| 内容寻址产物库 / 魔数嗅探 / 尺寸解析 | `MediaArtifactStore` / `MagicSniffer` / `ImageDimensions` |
| 图片→引用的两条外置路径 | `MediaExternalizingCallback`（即时）/ `SessionFileExternalizer`（回合间兜底） |
| **`Media` 的构造与投递** | **完全不存在** —— 代码里没有一处 `new Media` |

所以本期的性质是**给一个已建好的仓库开出口**，不是新建管线。

### 1.2 目标

1. 模型能真正看见**工具产生的图**（MCP 截图/生图、`Read` 一张 png）。
2. 模型能真正看见**用户贴的图**（期 2）。
3. 上述二者的上下文与花费**有硬上限**，且上限可算。
4. 不支持视觉的模型（DeepSeek 等）**行为零变化**。

### 1.3 非目标

- **模型生成图片**（DALL·E 类出站）——另一个方向，几乎不共用代码。
- **视频输入**——`supportsVideoInput` 保留字段，本期恒不兑现（各家支持度差异大、token 不可控）。
- **终端里显示图片**（iTerm2/kitty 图形协议）——你在 TUI 里只会看到 `📎 chart.png`，要看自己开文件。
- **artifact 的引用扫描式 GC**——本期只做体积上限淘汰（见 §7）。
- **旧会话自愈**——历史里被摘要吃掉的引用无法找回。

---

## 2. 核心规则（读本节即可理解全局）

### 规则一：会话里永远只有引用

图片字节**从不写入会话存储**。存储里是结构化文本引用块（`[file reference]`）。

由此白拿四件事：

- 上下文占用与历史图片数**完全无关**
- `FileSessionRepository` / `SessionEvents` 不用改（它们处理的仍是纯文本）
- `-c` 恢复会话后，图仍可通过引用里的 `path` 重新取回
- 压缩（摘要）请求里从来没有图，**压缩本身不花视觉 token**

### 规则二：兑现只发生在「当轮」

> **当轮起点 = 最后一条「非合成」`UserMessage` 的下标。**
> 它自己 + 它之后的消息里的引用 → 兑现；之前的 → 不兑现。

纯位置规则，无水位线、无时间戳、无外部记账，可完全离线单测。

历史图想重看？**`Read <引用里的 path>`** ——不需要新工具，走的就是「工具产图」那条通路。于是「要不要为这张图再花一次钱」由模型按当前话题决定，而不是由一个我拍脑袋定的窗口大小决定。

### 规则三：分来源的预算

```
每请求：用户当轮贴图 ≤3 张（保底，不参与淘汰）
      + 工具产图 ≤1 张（最新那张）
      + 硬上限 6k 视觉 token
每回合：累计兑现 ≤12 张·次，用尽后一律退回引用并告知模型
每张图：长边 >1568 则缩；单图字节超限则转 JPEG；>50 MP 不兑现
```

**单回合视觉花费的绝对上限因此可算**：12 × ~1.8k ≈ **21.6k token**。跑飞的截图循环也就到这儿。

---

## 3. 两条兑现路径（形状不同，不可混为一谈）

`Media` **只能挂在 `UserMessage` 上**（已核实：`UserMessage`/`AssistantMessage` 实现 `MediaContent`，`SystemMessage`/`ToolResponseMessage` 不实现；`AssistantMessage` 是模型自己的输出，语义相反且各家不收输入图）。

### 路径 U：用户贴图 —— 原地补 media（期 2）

引用块在 `UserMessage` 的正文里 → `mutate().media(...)` 就地挂上。

**引用块必须写进正文**，不是冗余：它是跨回合唯一的线索，落盘的就是这段纯文本。

### 路径 T：工具产图 —— 合成一条 user 消息追加（期 1）

引用块在 `ToolResponseMessage` 里，那里挂不上 media。故：

```
[6] Assistant(tool_calls: Read docs/bug.png)
[7] ToolResponse([file reference] … name:bug.png …)     ← 原封不动
[8] User(text:"以下是上面工具结果中的图片：bug.png",
         media:[真字节],
         metadata:{codetui.synthetic:true})               ← 装饰器合成
```

`[7]` 一个字都不改——引用文本是图与路径的绑定，模型读了多张图时全靠它分辨哪张是哪张。

**批处理约束**：模型可能一批调多个工具，`[7a][7b][7c]` 连着。不得在它们中间插入 user 消息（OpenAI 要求 `assistant(tool_calls)` 之后紧跟的 `tool` 消息把全部 `tool_call_id` 答完）。故**一段连续 `ToolResponseMessage` 里的所有图合并成一条 user 消息，追加在整段之后**。

### 两者的策略差异

| | 用户贴图 | 工具产图 |
|---|---|---|
| 引用位置 | `UserMessage` | `ToolResponseMessage` |
| 兑现方式 | 原地 `mutate().media()` | 合成新 user 消息追加 |
| 模型无视觉能力时 | **拦住不发**，提示切模型 | **降级为引用**（= 今天的行为） |
| 预算 | ≤3 张，**保底不淘汰** | ≤1 张（最新） |

**为什么策略必须分开**：用户贴图时人在键盘前，看得见提示、能立刻切模型；静默溢出一个看不见图的回合，只会得到一个自信的错答案。工具产图时是模型在自动跑，为一张附带的截图打断整个回合，等于让附带动作否决主线任务。

---

## 4. 架构：兑现点为什么是 `ChatModel` 装饰器

**硬约束**：兑现必须在会话记忆**之后**、真正发出**之前**。早了会被写进存储（图永久化、跨回合累积回来），晚了够不着。

### 选定：`VisionMaterializingChatModel`（`ChatModel` 装饰器）

- 拿到的是**最终出站 `Prompt`**，「出站即兑现」字面成立，不靠推理
- 模型 id 从 `prompt.getOptions().getModel()` 拿 —— **实际发出去的那个**，不是从 registry 猜的；子 agent 跑在别家 provider 时判定天然正确
- 项目已有三个同类先例：`RetryingChatModel`、`DynamicAuxChatModel`、`QwenSseNormalizingHttpClient`
- 会话存储天然干净：advisor 早已写盘，装饰器改的是其下游的副本

### 落选：Advisor（order > 1000）

也够得着，但它与 `SessionMemoryAdvisor` 的相对顺序**靠 order 整数维持**。排错一位，兑现结果就进了存储，图片跨回合永久累积——这正是整个设计要消灭的东西，不该托付给一个数字。

### 落选：在 `CodingAgent.submit` 直接构造

只能覆盖用户贴图。工具产图诞生于工具循环内部，`submit` 早已返回，够不着。

### 已验证：合成消息不会回流

反编译 `ToolCallingAdvisor` 字节码：

```
145: ToolCallingManager.executeToolCalls( request.prompt(), chatResponse )
183: ToolExecutionResult.conversationHistory()
167: doGetNextInstructionsForToolCall(request, response, toolExecutionResult)
```

下一轮消息列表由 **advisor 手里的 `request.prompt()`** 派生。装饰器在整条 advisor 链**下游**，它造的 `[8]` 只存在于传给 `delegate.stream()` 的副本里。

**但不依赖这个保证**：那是库的内部实现，升级即可能静默反转（边界锚点变成 `[8]`，之后什么都不兑现，模型在回合中途突然看不见图——不报错、不崩，只是答得变差）。故合成消息**自带 `codetui.synthetic` 元数据**，边界规则定义为「最后一条**非合成** `UserMessage`」。正确性由我们自己写进消息的标记保证，而非库的行为。

---

## 5. 组件

```
VisionModels                  能力名单（硬编码，未知 → 不支持）
FileReferenceParser           严格解析引用块（防注入）
ImagePreparer                 缩图 / 转码 / 尺寸与字节上限（纯函数）
VisionBudget                  分来源配额 + 每回合累计（纯函数 + 有界计数表）
VisionMaterializer            Prompt + caps → Prompt（纯函数，本设计的全部判断在此）
VisionMaterializingChatModel  唯一接线点；转发 getDefaultOptions()
MediaReferencePreservingCompactionStrategy   压缩时保住引用清单
ArtifactGc                    启动时按体积上限淘汰
```

`VisionMaterializer` 是纯函数 —— 边界判定、预算、去重、注入防护全在里面，可完全离线单测。装饰器只负责接线与状态桶。

### 5.1 `VisionModels`：能力判定

```
gpt-5.6-*, gpt-5.5, gpt-5.4   → 支持
claude-*                       → 支持
qwen-vl-*, qwen*-vl*           → 支持
glm-4v*, glm-4.*v*             → 支持
deepseek-*                     → 不支持
其它（含 *_MODELS 自定义 id、兼容层转发） → **不支持**
```

**判错方向不对称**，故默认必须是「不支持」：误判为不支持只是拦住你、看得见、能改；误判为支持会真发出去吃一个 400，浪费上传时间与费用，且各家错误信息未必看得出是图片的问题。

`CODETUI_VISION=off` 全局关闭（省钱逃生口）。

### 5.2 `FileReferenceParser`：严格解析 + 注入防护

三条硬要求，缺一不可：

1. **只扫 `UserMessage` 与 `ToolResponseMessage`**，跳过 `AssistantMessage`。模型看得见引用格式，可能在回复里照抄；无差别扫描会兑现它复述的假引用。
2. **`path` 必须过 `PathContainment.resolveInRoot`**。一个网页里写着 `[file reference] path: ../../../etc/id_rsa`，被 `Read` 进来就成了「工具结果里的引用」——这是真实的提示注入面。
3. **严格匹配自己 render 的完整格式**（`OPEN`/`CLOSE` 配对、必填字段齐全、`kind: image`）。字段不齐即不认，不做启发式补全。

### 5.3 `ImagePreparer`：格式决策表

已实测 JDK ImageIO 支持：`bmp gif jpeg jpg png tif tiff wbmp`——**不支持 WebP / HEIC / AVIF**。

| 格式 | ImageIO | 各家 API | 处理 |
|---|---|---|---|
| PNG / JPEG / GIF | ✅ | ✅ | 长边 >1568 则等比缩 |
| BMP / TIFF | ✅ | ❌ | **解码后转码为 PNG** |
| WebP | ❌ | ✅ | **原样发，不缩** |
| HEIC / AVIF | ❌ | ❌ | **不兑现**，引用注明格式不支持 |

- **OOM 防护**：先用 header-only 读尺寸（已实测 `ImageReader.getWidth(0)` 无需整图解码），**>50 MP 直接不兑现**。绝不先解码再判断——200MB PNG 解码后是 GB 级。
- **字节上限**：Anthropic 单图约 5MB。缩完再看字节，超限则转 JPEG 质量 85；仍超则继续降边长；三次不成退回引用。
- **GIF 动图**缩后只剩一帧，引用里注明。
- **缓存**：按 `sha + 目标边长` 缓存缩后字节（`ConcurrentHashMap`，有界）。一个回合 6 次迭代否则要重复解码编码 6 次。
- **sha 语义**：引用里的 sha 是**原图**的，发出去的是缩后字节，两者不同。sha 用于寻址与去重，不用于校验发出内容。**必须写进文档**，否则会被当 bug 反复报。

### 5.4 `VisionBudget`

**分来源配额**（这条是从最典型的失败用法倒推出来的）：

> 你贴了张设计稿说「照这个改」，模型接着 `Read` 了 3 张别的图。若一视同仁地「从新到旧」取 3 张，**你的稿子恰好被挤出预算**，模型照着别的图改——功能在最典型的用法上直接失效。

故：**用户当轮贴的图保底占位、不参与淘汰**；工具产图只在彼此之间竞争，取最新 1 张。用户一次贴超过 3 张时，由用户的图按贴入顺序占满，工具图一张不给（引用注明）。

**单次工具结果返回多张图**时它们年龄相同，「从新到旧」无从排序 → 定义为**按数组顺序取最后一张**（工具通常把最终结果放后面），与「工具产图每请求 ≤1 张」一致。这是约定，必须写进文档，否则实现者会随便挑。

**每回合累计计数的状态归属**：`ChatModel` 实例被主 agent 与所有子 agent 共用，并发子 agent 会互相冲掉计数。故按 **turnKey 分桶**：

```
turnKey = hash(锚点消息文本) + 锚点之前的消息数
```

同一回合内所有迭代恒定，不同 agent/回合天然不同。有界表（保留最近 8 个 key）防止无限增长。

### 5.5 `delivery` 的五种状态

今天恒定输出 `delivery: reference_only`。接上视觉后，同一句话对模型有四种完全不同的含义，必须区分——否则模型会为一张它根本看不见的图白 `Read` 一次，空转一轮还花钱：

| delivery | 含义 | 模型该做什么 |
|---|---|---|
| `delivered` | 图已随本请求给你 | 直接看 |
| `reference_only` | 当前模型无视觉能力 | 别 Read 了，取回来也看不见 |
| `not_in_view` | 有能力，只是不在当轮 | **`Read` 一次就能看** |
| `budget_exceeded` | 当轮图太多被挤掉 | 想看这张就单独 `Read` 它 |
| `turn_budget_exhausted` | 本回合视觉额度用尽 | 结束本回合后重新发起 |

**兑现时必须同步改写出站副本里的 `delivery` 行**为 `delivered`。只加 media 不改文本，模型会同时收到「这张图你看不见」和那张图——自相矛盾的信号。

### 5.6 `MediaArtifact` 新增 `originalName`

`MATERIALIZED` 产物的路径是 `.codetui/artifacts/b7e2f1….png`，原始文件名丢失。跨回合模型面对多行 sha，无法指认「购物车那张」。

- 用户贴图：取原文件名 `cart.png`
- `Read` 磁盘图：取原文件名
- **MCP 内联字节：根本没有名字** → 合成 `<工具名>-<回合内序号>-<sha8>.png`，如 `chrome-devtools__take_screenshot-01-b7e2f1.png`。工具名从 `ToolDefinition.name()` 取。

`FileReference` 增加 `name:` 行；`Media.Builder.name()` 也带上（已核实该方法存在）。

---

## 6. 压缩交互

### 6.1 「当轮」边界不受影响

`RecursiveSummarizationCompactionStrategy` 是**保留最近 N 条、只摘要更早的**（自动 120 / 手动 20）。产物是 `[摘要] + 最近 N 条`，摘要是一条更早的消息，动不到尾部相对位置。手动 `/compact` 另有 `isBusy` 闸门，只在回合之间跑。

**已知边缘**：自动压缩挂在 `SessionMemoryAdvisor` 上，而 `before()` 每个工具迭代都跑，故**可能在工具循环中途触发**。若单回合自身超过 120 个事件，当轮的 `UserMessage` 会被摘要掉、锚点丢失 → 兜底为**只兑现尾部那段连续 `ToolResponse`**。不崩，只是当轮用户贴的图暂时看不见。罕见（需单回合 >120 事件），不为它加设计。

### 6.2 引用会被摘要吃掉（存量缺陷，本期必修）

摘要由 LLM 生成，结构化引用块**必然被改写**成「用户提供了三张截图」——sha 路径永久丢失，图还在磁盘上但谁都寻址不到。

**这个缺陷今天就存在**，只是今天图反正投不进模型，丢了没人在意；视觉一接上就变成「贴的图聊着聊着就消失了」。

修法：

```java
MediaReferencePreservingCompactionStrategy(delegate) {
    CompactionResult r = delegate.compact(req);
    List<Ref> lost = harvest(r.archivedEvents());   // 只收真被摘要掉的
    return r.withPrepended(附件清单事件(lost));       // 插在摘要之后
}
```

从 `archivedEvents()` 捞而非 `request.events()`——**没被压掉的引用还在原处**，从请求侧捞会重复。（API 已核实：`CompactionResult(compactedEvents, archivedEvents, tokensEstimatedSaved)`。）

清单格式逐字保留寻址信息，不经 LLM 改写：

```
[会话附件清单] 以下文件/图片在更早的对话中出现过，仍可 Read 查看：
  cart.png    image/png  1440×900  .codetui/artifacts/b7e2f1….png
  login.png   image/png  1440×900  .codetui/artifacts/a3f8c2….png
```

**上限保留最近 20 条**，更早的丢弃并注明「另有 N 个更早的附件已不可寻址」。上限必须写死，否则长会话里这张清单自己会长成新的上下文问题。

清单事件**必须带同一个 `codetui.synthetic` 标记**，否则某些压缩形态下它可能成为「最后一条非合成 `UserMessage`」，锚点就错位了。

### 6.3 装饰顺序与两条路径

**顺序**：`Notifying( Preserving( Recursive ) )`。Notifying 在最外层，它上报的 `eventsRemoved` / `tokensEstimatedSaved` 才是**加了附件清单之后的净效果**；反过来包，UI 报的数字会偏乐观。

**两条路径都要接**：`autoStrategy` 包了 `NotifyingCompactionStrategy`，而 `manualStrategy` **刻意不包**（`CodingAgent.runCompaction` 自己上报，包了会重复上报）。保留装饰器必须**分别接进两条策略**——只接自动那条，手动 `/compact` 一按图全丢。这种「两条路径只改了一条」的漏接最难在测试里发现，故两条各配一个用例。

### 6.4 auxClient 绝不能被装饰（本设计最危险的一处接线）

`auxClient` 是压缩用的辅助 ChatClient，摘要请求里的消息**含引用块**。若它的 `ChatModel` 也被 `VisionMaterializingChatModel` 包上：

> 每次压缩都会把历史里的图兑现成真字节发给摘要模型 —— **一次纯文本摘要静默变成视觉请求**。

而压缩是**自动触发**的，你不会注意到，直到看账单。

→ 装配时显式只装饰主/子 agent 的 `ChatModel`，`auxClient` 保持裸的，并在代码里写死注释说明为什么。这是那种「不写下来三个月后一定有人顺手包上」的地方，另配一条断言 auxClient 未被装饰的测试。

### 6.5 压缩阈值不含图片 token

`TokenCountTrigger` 估的是会话存储（纯文本），图不在其中，故**图片永远不会触发压缩**。方向上是对的（图确实不在存储里），但意味着实际出站请求恒比触发器认知的大。好在预算 6k 封顶，**偏差有界且可预测**。可接受，写进文档。

---

## 7. artifact GC（本期必须做，不能再推）

七月把 GC 列为 Path B，当时合理——那时只有偶尔 `Read` 一张图。

接上视觉后，截图循环**每次迭代产一张 2MB 的 4K PNG**，而 `.codetui/artifacts/` 是**按项目共享、跨会话累积、`/clear` 也不清**的。一个调试会话跑几十轮就是几百 MB。

**最小形态**：启动时检查目录，超过 500MB 则按 mtime 从旧到新删到上限以下。

不做引用扫描——扫描要遍历所有会话文件，复杂度与出错面大得多；删掉仍被引用的旧图，后果只是模型 `Read` 时拿到「文件不存在」，可恢复。

内容寻址在这里白送一个好处：**同一张截图重复出现只占一份**（sha 相同），静态页面反复截图不会累积。

---

## 8. 其余已识别问题与处置

| 问题 | 处置 |
|---|---|
| **同一请求内同一 sha 兑现两次**（用户贴了 `bug.png`，模型又 `Read` 了它） | 按 sha 在单次请求内去重，保留位置靠前的那次 |
| **`EXISTING_FILE` 不是快照**（回合 5 改了文件，历史图跟着变） | 用户贴的图一律 `MATERIALIZED`（复制快照）；工具 `Read` 的图保持 `EXISTING_FILE`（它本就该是当前内容）。二者语义确实不同 |
| **`-c` 回放会把引用块原样打给用户看**（`HistoryReplay` 读的是存储，存储里那条 user 消息含八行引用块） | `HistoryReplay` 把引用块渲成 `📎 cart.png (1440×900)`。今天不暴露，是因为引用块此前只出现在工具结果里 |
| **Bash 产图不产生引用**（`python plot.py` 写了 `chart.png`，工具结果是纯文本） | **不改**——Bash 输出里出现路径不代表模型想看。模型想看就 `Read`，那条路通。写进文档，否则会被当 bug 反复报 |
| **子 agent 产的图主 agent 摸不着**（只有文本报告回流） | 不加机制。在子 agent 系统提示里加一句「产出图片时把 artifact 路径写进报告」。低成本，失败也只退回今天的行为 |
| **`/context` 低报视觉占用**（`ContextStats` 用 JTokkit 估文本） | 装饰器暴露 volatile 的「上次兑现快照」，`contextStats()` 读它，面板单列「本回合图片 N 张 / Xk token」 |
| **装饰 `ChatModel` 漏转发 `getDefaultOptions()`** | 项目已栽过一次（`subagent-error-reading-response`）。写进任务清单，不靠记性 |
| **终端显示不了图** | 非目标，明确写进 README |

---

## 9. 分期

按**可测性**切分，共享内核无论怎么切都在期 1，不产生重复劳动。

### 期 1：共享内核 + 工具产图（纯后端，可完全离线单测）

`VisionModels` / `FileReferenceParser` / `ImagePreparer` / `VisionBudget` / `VisionMaterializer` / `VisionMaterializingChatModel` / `MediaReferencePreservingCompactionStrategy` / `ArtifactGc` / `originalName` / `delivery` 五态 / 各 provider 覆写 `capabilities()` / auxClient 排除 / `/context` 视觉行。

**交付即有价值**：`Read` 一张图、MCP 截一张图，模型就真看得见了。

### 期 2：用户贴图（UI 密集，需 pty 实机）

输入框路径识别、附件行渲染、能力闸门与输入保留、`-c` 回放引用块渲染、`MATERIALIZED` 快照语义。

期 2 的待解问题（届时细化，此处只登记，不留 TBD 到期 1 的实现里）：

- **误附识别**：`把 docs/bug.png 复制到 tmp/` 里的路径不该被当成附件。倾向规则：**路径独立成词 + 文件真实存在 + 魔数是图片**，并在附件行显示出来让人当场看见附错了
- 路径形态：拖拽带的引号、含空格、`~` 展开、中文名
- 相对路径基准：**project root**（与 `Read`、权限引擎一致）
- 异常文件：不存在 / 非图片 / 超大 / 0 字节
- 撤销附件：不做专门交互，改输入文本即可（附件识别是纯函数）
- 被能力闸门拦住后**输入框内容原地保留**，`/model` 切完能直接回车重发——否则每次都要重贴，功能不会有人用

---

## 10. 测试策略

### 10.1 必须能失败的测试（十条关键防护）

每条都要做**变异验证**：把对应的防护逻辑去掉，测试必须变红。不变红说明写了一个不会失败的测试——本项目在权限那轮已经栽过两次（`allowDoesNotFoldCase` 的断言与分支无关、以及一条编造的因果机制）。

| # | 断言 | 变异点 |
|---|---|---|
| 1 | auxClient 的 `ChatModel` 未被 `VisionMaterializingChatModel` 包裹 | 给 auxClient 包上 → 红 |
| 2 | 用户当轮贴的图在工具产图挤占下仍被兑现 | 去掉保底 → 红 |
| 3 | 手动 `/compact` 后引用清单仍在 | 只给 autoStrategy 接保留装饰器 → 红 |
| 4 | 兑现后出站副本里 `delivery: delivered` | 只加 media 不改文本 → 红 |
| 5 | HEIC 不兑现、BMP 转 PNG、WebP 原样发 | 去掉决策表 → 红 |
| 6 | `AssistantMessage` 里的引用块不被兑现 | 扫描不跳过 assistant → 红 |
| 7 | `path: ../../../etc/x.png` 不被兑现 | 去掉 `resolveInRoot` → 红 |
| 8 | 合成消息混进列表时锚点不变 | 去掉 `synthetic` 跳过 → 红 |
| 9 | 单回合累计超 12 次后停止兑现 | 去掉回合计数 → 红 |
| 10 | 50 MP 图不解码即拒绝 | 改成先解码再判断 → OOM 或超时 |

### 10.2 真机验证（**排在实现之前**）

「`assistant(tool_calls)` → `tool` → **`user`**」这个消息序列各家 API 认不认，目前**只有推理没有证据**。按 OpenAI 规范读是合法的，业界也这么用，但本项目在消息序列上已吃过两次 400（连续 user、悬空 tool_calls），不打算再靠「读规范应该没问题」交付。

**计划第一个任务**：拿真 key 对每家支持视觉的 provider 各发一次该序列（含真实 `Media`），确认不是 400，并确认 `Media` 的 `data` 该传 `byte[]` 还是 `Resource`（各家 adapter 序列化方式需实测）。

**退路**：若某家不认，该 provider 的工具产图改为**攒到下一条真实 user 消息**上，代价是模型晚一轮才看见。

### 10.3 常规

- `VisionMaterializer` 全部判断皆纯函数，离线单测覆盖边界/预算/去重/注入。
- `ImagePreparer` 用极小的真图 fixture（几十字节 PNG/BMP/GIF），测试写临时目录，不污染仓库。
- 压缩：`archivedEvents` 捞取的幂等性、清单上限截断、装饰顺序对上报数字的影响。
- 验证命令**模块作用域**：`mvn test -pl springai-code-tui`，**不得**加 `-DfailIfNoSpecifiedTests=false`。

---

## 11. 风险与取舍

- **同一回合内每次工具迭代都重传当轮的图** —— 无状态请求的固有代价（正文与全部历史每次也在重传），治不了次数，只能靠缩图压单价 + 每回合累计上限封顶。prompt caching 可缓解。**不假装能优化掉。**
- **能力名单会过期** —— 已选「未知当作不支持」，判错方向安全。
- **artifact GC 是体积淘汰不是引用计数** —— 可能删掉仍被引用的旧图，后果是 `Read` 拿到「文件不存在」，可恢复。
- **压缩摘要仍可能丢失引用的上下文含义** —— 清单保住了寻址信息（path/name/尺寸），但「这张图当时是用来干什么的」由摘要决定，可能丢。
- **零新增第三方依赖** —— ImageIO 是 JDK 自带。
