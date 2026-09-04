# 主 Agent 流式重试与界面重试提示 设计

日期：2026-09-03
状态：**终稿 v11**（10 轮 subagent 评审全部完成，终审裁决 READY，可交 writing-plans）
参考实现：Claude Code（Anthropic 官方 CLI）的行为语义
关联：`docs/superpowers/specs/2026-08-18-subagent-retry-transient-expansion-design.md`（子 agent 重试，已交付）

## 0. 摘要

主 agent 目前对流式故障零重试：流一断，回合直接失败（`handleError` → ⚠ 出错 → IDLE），
用户只能整回合重发，上下文越深损失越大。本设计给主 agent 补上**两层重试**，并让
**每次重试都在界面上显示一条「↻ 重试」提示**（对齐 Claude Code 的可见性标准）：

- **Layer 1（传输层，新 `RetryingStreamChatModel`）**：**零下发**失败（连接错/429/5xx/
  首字节空闲超时/空流/解析错）的透明重试——`doOnSubscribe 重置计数 + concatWith(空流
  转 error) + retryWhen(Retry.backoff)` 非阻塞指数退避，判据与子 agent 同源。
- **Layer 2（回合层，CodingAgent 续跑）**：**mid-stream 断流**（已下发内容后失败）
  无法在 ChatModel 层透明重试（重订阅必与已消费 chunks 拼接重复），改为回合级续跑：
  UI 落定半截残行 + 显示重试提示 + 退避后重走完整 advisor 管线（同 turnId、同一
  Disposable 管辖、Esc 随时可取消）。

**与 Claude Code 的一处刻意分歧（诚实声明）**：Claude Code 的自绘转录区可以撤回
半截输出；本项目是行内滚动 TUI（整行已 println 进终端 scrollback，应用侧不可撤回）。
故 mid-stream 续跑时**半截输出保留在屏幕上**，↻ 提示行之后接新流输出。终端 scrollback
repaint 不在本设计范围（见非目标）。

两层合计把「网关抖一下 → 整回合报废」变成「网关抖一下 → 界面提示重试 → 自动恢复」。

## 1. 背景与动机

### 1.1 现状：主 agent 流式路径零重试

主 agent 请求链（`CodeTuiApplication.wrap` 装饰 provider，`AgentTools` 再包两层）：

```
InterjectingChatModel        插话注入（最外；AgentTools 装配）
  ← VisionMaterializingChatModel  视觉兑现（AgentTools 装配）
      ← UsageRecordingChatModel   记账（CodeTuiApplication.wrap 最外层）
          ← StreamIdleTimeoutChatModel  流空闲超时（wrap 内层）
              ← provider 原生 chatModel
```

（注意相对顺序：Usage 在 IdleTimeout **外**——wrap 注释大意：空闲超时包在 usage
采集内层，确保超时前看到的最后一笔 usage 仍在错误传播前提交，`CodeTuiApplication.wrap`。）

链上没有任何重试。2026-08-17 生产日志记录主 agent 回合失败 7 次，当时明确「本次
不修，另行立项」——本设计偿还该欠账。

### 1.2 为什么 mid-stream 不能在 ChatModel 层透明重试

流式响应已下发 k 个 chunk 后断流：
- 下游（ToolCallingAdvisor 的流式聚合器 / MessageAggregator）已消费 k 个 chunk；
- ChatModel 层重订阅 = 第二次尝试的 chunks 从第 1 个开始重发，下游收到重复内容；
- 聚合器没有「重置/替换」语义，重复无法事后纠正。

因此 mid-stream 失败必须**回到回合层**重走完整管线（advisor 链从头跑，聚合器重建）。
这正是 Claude Code 的结构：SDK 内传输重试 + CLI 层回合重试。

**冷流前提（已确证）**：Spring AI 2.0 的 `.stream().chatClientResponse()` =
`Flux.deferContextual(...)` 包 `advisorChain.nextStream(request)`
（`DefaultChatClient` 源码），每次订阅从链头重跑：SessionMemoryAdvisor.before()
重执行、ChatModel.stream() 重新发 HTTP、Interjecting 重注入插话。重订阅即完整重走，
这是 L2 的地基。

### 1.3 断流时的会话尾部形状（现状事实）

mid-stream 断流（`handleError` 触发前）会话尾部有两种形状，取决于断点位置：

- **形状①纯文本流断**：`… → user(本轮问题)`（assistant 收尾消息未落库，写入在流
  完成后）；
- **形状②工具循环中途断**：`… → user → assistant(tool_calls) → tool 结果 → … →
  悬空 assistant(tool_calls)`——已完成迭代在库，尾部残留悬空 tool_calls（这正是
  既有 `trimDanglingToolCalls` 的处理对象）。

续跑前必须把两种形状都裁回合法前缀（§3.3）。

### 1.4 已有可复用资产

| 资产 | 位置 | 复用方式 |
|---|---|---|
| 瞬态判据 | `RetryingChatModel.shouldRetry` | 原样提取为 `RetryPolicy` 共享（§3.1），并补超时/空流判据 |
| 指数退避参数 | 同上（500ms×2ⁿ 封顶 4s） | L1 同参；L2 独立（§3.3） |
| `trimDanglingToolCalls` | `CodingAgent` | 续跑前裁形状②（幂等，submit 顶部已裁一次） |
| 折叠尾部 user 的先例 | `foldTrailingUserIntoOutbound` | 启发 §3.3 的 strip 语义 |
| UI INFO 行 | `OutputLine.Kind.INFO` | 复用，前缀 `↻` 区分，不新增 Kind |

### 1.5 目标 / 非目标

**目标**
1. 主 agent 流式调用对瞬态故障自动重试（含空闲超时、空流），用户无需手动重发；
2. 每次重试（L1 与 L2）都在界面显示提示行；
3. 取消（Esc）语义最高优先级：任何重试等待期 dispose 立即终止整链，绝不复活；
4. 判据、退避与子 agent 同源，避免两套机制漂移。

**非目标**
- 不改子 agent 已有重试（call() 桥流式 + 阻塞重试，已交付）；
- 不做终端 scrollback repaint / 自绘转录区；
- 不做断点 token 续接（只保「裁回合法前缀后重新生成」）；
- 不做 fallback 模型切换；
- 不覆盖 aux 路径（SmartWebFetchTool 的网页抽取/会话摘要走 `DynamicAuxChatModel`，工具自带
  maxRetries；装配守卫测试钉死 aux **不得**包 L1）。

## 2. 候选方案与取舍

**方案 A：只做 L1（ChatModel 层零下发重试）** —— mid-stream 断流（长回合常态故障）
完全不受益。**否**。

**方案 B：只做 L2（回合级续跑兜一切）** —— 零下发小故障也闪一次重试行，体验粗糙；
且分层本可让部分故障对用户完全无感。**否**。

**方案 C：两层重试（推荐，Claude Code 同构）** —— 零下发走 L1 透明重试；mid-stream
走 L2 续跑。**是**。

## 3. 详细设计

### 3.1 `RetryPolicy`（agent/llm 包，新增类）——判据与退避的唯一真相源

从 `RetryingChatModel` 原样搬移 `shouldRetry`（cause 链逐层：类名后缀
`InvalidDataException`/`IoException`、`IOException` 家族、message 含
`no content to map`/`eof reached while reading`/`rate limit`、
`WebClientResponseException` 5xx；4xx/中断/取消红线不重试）与
`backoffMsAfter`。`RetryingChatModel` 改为委托，行为零变化（既有测试全绿为验收）。

**新增两条判据**：
- `StreamIdleTimeoutException`（新类型，见下）→ 瞬态。否则「网关挂起不回数据」这个
  头号瞬态形态两层都救不了；
- `EmptyStreamException`（新类型，L1 自造，见 §3.2）→ 瞬态。空流守卫的产物必须
  命中自家判据（v2 自洽性洞，第 2 轮 Blocker）。

`StreamIdleTimeoutChatModel` 的超时异常从裸 `RuntimeException` 改为
`StreamIdleTimeoutException extends RuntimeException`（message 不变）。

**重试总闸 `CODETUI_STREAM_RETRY`（第 9 轮 M2——自动重试是用户透明的花钱行为，必须给闸）**：

新增环境变量 `CODETUI_STREAM_RETRY`，取值 `all`（默认）| `l1` | `l2` | `off`：
- `all`：两层全开；
- `l1`：仅传输层重试（L2 续跑关闭：retryWhen 白名单恒 false）；
- `l2`：仅回合级续跑（L1 不包装：`RetryingStreamChatModel.wrap` 直通返回 delegate）；
- `off`：两层全关（回到现状行为）。

实现照抄 `LlmTimeouts.from(env)` 先例形态（装配期一次读取、非法值回退默认 +
warn）。测试补「off 时不包装/直通」「非法值回退 all」。注意：Claude Code 并无
重试开关先例（`CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC` 只管非必要流量：
更新/遥测/错误上报；社区正请求可配重试 anthropics/claude-code#23115）——本开关
是本项目自己的补齐，不借 CC 背书。

### 3.2 Layer 1：`RetryingStreamChatModel`（agent/llm 包，新类）

**职责**：包装 stream()，只对「零下发失败」重试；mid-stream 瞬态失败**包装后放行**
给 L2；空流（正常完成但零有效内容）视同零下发失败。

```java
// 单一计数器：filter 与 classify 都读它，无需快照——Reactor 信号传递自带
// happens-before，filter/classify 读到的是终值。重置只在重订阅时（doOnSubscribe）。
private final AtomicInteger emitted = new AtomicInteger();

stream(prompt):
  delegate.stream(prompt)
    .doOnSubscribe(s -> emitted.set(0))                    // 每次订阅/重订阅重置
    .doOnNext(r -> { if (hasContent(r)) emitted.incrementAndGet(); })
    .concatWith(Mono.defer(() -> emitted.get() == 0        // 空流守卫（完成但零有效内容）
        ? Flux.error(new EmptyStreamException(…))          // → 转 error 进 retryWhen
        : Mono.empty()))
    .retryWhen(Retry.backoff(L1_RETRIES, BACKOFF_1)        // ⚠ 首参是【重试次数】
                       .jitter(0d)                          //   显式关 jitter（默认 0.5
                                                          //   会让退避随机化，R6-M1）
                       .maxBackoff(CAP)                    //   L1_RETRIES=4：总尝试=5
                       .filter(ex -> emitted.get() == 0
                                  && RetryPolicy.shouldRetry(ex)))
    .transformDeferred(this::classify)                     // 见下
```

**`emitted` 口径**：只计「有文本或工具调用内容」的 chunk——**text 非空**（`!= null &&
!isEmpty()`，含纯空白：空白 chunk 已被 handleChunk 下发进 UI/聚合器，不计数会破
「零下发 = 下游零观测」不变式，R6-m1）或 hasToolCalls；usage-only / finish-only
（有 finishReason 无文本无 tool_calls）收尾 chunk 不计。`hasContent` null 防护照抄
`RetryingChatModel.isEffectivelyEmpty`（`getResult()` 可为 null）。

**L1 重试安全不变式**：L1 重订阅安全 ⇔ `emitted==0` ⇔ 下游未收到任何非空 chunk
（filter 恒等式保证 emitted>0 恒拒绝重试；唯一缝隙即上段已封的空白 chunk）。

**`classify` 判定树（经 reactor-core 字节码验证的信号流，第 3 轮 M3）**：

能到达 classify 的错误只有两种：
1. `reactor.util.retry.RetryBackoffSpec` 的耗尽包装（包私有
   `reactor.core.Exceptions$RetryExhaustedException`，**不可 instanceof**；判定用公有
   `reactor.core.Exceptions.isRetryExhausted(ex)`）——**取 `ex.getCause()` 解包放行**
   （最后一次原始失败）。L2 见到 `shouldRetry==true` 但非 `StreamInterruptedException`，
   不续跑，直接 `handleError`；
2. 非包装错误——**必然是被 filter 拒绝的**（被接受未耗尽的错误已被重订阅吞掉）。
   其中 `shouldRetry(ex) && emitted>0` → 包装为 **`StreamInterruptedException extends
   RuntimeException`**（字段 `long emittedChunks`；**message 置空**——
   `formatError` 沿 cause 链取首个非空 message，置空让用户直接看到根因网络异常文案）
   放行给 L2；其余（4xx、中断/取消、非瞬态、零下发非瞬态）→ 原样放行。

**关键不变式**：「filter 拒绝 && shouldRetry && emitted==0」不存在——filter 恒等式
（`emitted==0 && shouldRetry` 才接受）保证了被拒绝且瞬态的错误必然 `emitted>0`。
且 filter 拒绝路径上 `emitted` 未被重置（重置只在重订阅的 doOnSubscribe），classify
读到终值。

**类型穿透要求**：`StreamInterruptedException` 必须原样穿透 ToolCallingAdvisor 流式
聚合、SessionMemoryAdvisor 的 `publishOn + ChatClientMessageAggregator`、
Vision/Interjecting（后两者纯委托无错误映射）才能命中 L2 白名单——实现时任何中间层
**不得**新增 `onErrorMap` 拦截；测试显式命名「类型穿透」用例防退化。

参数：`L1_RETRIES = 4`（总尝试 5，与子 agent 对齐；⚠ `Retry.backoff` 首参是重试
次数不是总尝试数）；退避 500ms×2ⁿ 封顶 4s、**jitter(0) 显式关闭**（默认 jitter=0.5
会把退避随机化 0.25–0.75s/0.5–1.5s/…，打翻退避序列测试、UI 文案「0.5s 后重发」
与预算算术；单用户 TUI 无并发也不需要抖动，且与子 agent `Thread.sleep` 版严格
同参）。

**L1 的 UI 可见性**：构造注入可选 `RetryReporter`（接口
`void report(int attempt, long backoffMs, String reason)`；**attempt = 即将进行的
第几次尝试，取值 2..5**（与 UI 文案 `(2/5·传输)` 的尝试序号一致）；无绑定为 no-op）。装配层
把它桥到 CodingAgent → `listener.onRetryScheduled(activeTurnId.get(), attempt,
backoffMs, reason)`。**钩子纪律（R6-m3）**：report 经
`Retry.backoff(...).doBeforeRetry(...)` 触发（doBeforeRetry 在退避 delay 之前、错误
信号线程上同步执行 → onRetryScheduled 严格早于新 chunk，UI 时序不可能乱序）；
backoffMs 用与退避同公式现算（jitter(0) 后与真实 delay 严格相等）。**turnId 绑定
纪律**：桥接闭包在 submit 时携带本回合 turnId，reporter 回调比对「在飞回合 ==
携带值」才发事件——旧链迟到的 ↻ 行不会记到新回合头上（共享 `activeTurnId` 比对
天然够用，无需终态清空）。L1 零下发时屏幕无内容，UI 侧清残为 no-op，只加提示行。
接缝纪律不破坏：装饰器只依赖自己的接口，桥接发生在装配层。**装配时序**：装饰器
在 AgentTools per-provider 循环装配、早于 CodingAgent 存在——用两段式持有者
（装配期建桥对象 → CodingAgent 构造后接线）。

**其他**：`getOptions()/getDefaultOptions()` 转发（三装饰器栽过的坑）；`call()`
原样委托（主 agent 不用 call）；取消/中断在 filter 优先短路。

### 3.3 Layer 2：CodingAgent 回合级续跑

**触发白名单**：白名单 = retryWhen 的 `filter(ex -> ex instanceof
StreamInterruptedException)`；上限 = `Retry.backoff(2, …)`（2 次续跑 + 首次 = 共 3
次完整流）；`emitted>0` 由 classify 出口 2 的**不变式**保证。终态错误经 `doOnError`
走原 `handleError`，不挂第二层判定。

**实现形态——续跑必须是原链内部的 retryWhen，不得另起订阅**：

```java
// submit 内局部变量（⚠ 不得为实例字段——跨回合残留会让下一回合健康首轮
// 误执行 prepareResume：删掉上回合末尾合法 user、注入续跑 notice，第 3 轮 M2）
AtomicInteger resubscriptions = new AtomicInteger();

AtomicBoolean disposed = new AtomicBoolean();   // Esc 置位（见 Disposables.composite）

Flux<ChatClientResponse> pipeline = Flux.defer(() -> {
        // disposed 检查（第 9 轮 M1）：dispose 不能中断 boundedElastic
        // worker 上已经在跑的 defer 体——不查的话，取消后仍会
        // 跑完一轮无谓的 prepareResume 会话写（幽灵请求经 Reactor
        // 三重护栏已确证不存在，但显式短路更干净）：
        if (disposed.get()) {
            return Flux.error(new CancellationException("回合已取消"));
        }
        int resub = resubscriptions.incrementAndGet();
        String outboundUser = null;      // 首轮=原始问题；形状①续跑=问题+插话+notice 单条
        if (resub == 1) {
            outboundUser = effectiveText;
        } else {
            ResumeShape shape = prepareResume();          // 返回形状①/②，见下
            if (shape == ResumeShape.FROM_TEXT) {
                outboundUser = composeResumeUser(effectiveText, interjections);
                // 形状①：把 pending 插话与 notice 一并并进同一条 .user()（第 5 轮 B1：
                // refill 后 Interjecting 若再注入插话，出站将出现两条连续 user → 400；
                // 并进 .user() 后 Interjecting 该轮 pending 已清空，早退分支天然不触发）
            }
            // 形状②（FROM_TOOLS）：outboundUser=null，不调 .user()——notice 走
            // Interjecting 通道（§3.4）
        }
        var spec = client.prompt();
        if (outboundUser != null) spec = spec.user(outboundUser);
        // …options/system/toolContext/advisors 每轮同构设置…
        return spec.stream().chatClientResponse();       // 冷流：重走 advisor 链
    })
    // 阻塞准备段与 advisor 链不跑在 parallel 调度器；位置必须在 defer 与
    // retryWhen 之间（覆盖重订阅）：
    .subscribeOn(Schedulers.boundedElastic())
    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(4))
        .jitter(0d)                                   // 与 L1 同款显式关闭（R4 的坑别踩第二次）
        .filter(ex -> ex instanceof StreamInterruptedException)
        // L2 提示行触发点（与 L1 同款 doBeforeRetry 纪律；退避 delay 前同步执行，
        // onRetryScheduled 严格早于新 chunk）：
        .doBeforeRetry(sig -> listener.onRetryScheduled(turnId,
                (int) sig.totalRetries() + 1,        // attempt = 续跑序号（1..2，与文案「续跑 1/2」一致）
                RESUME_BACKOFF_AFTER((int) sig.totalRetries()),   // 现算，jitter(0) 下与真实 delay 严格相等
                "流中断")))
    .doOnNext(resp -> handleChunk(resp, turnId))
    .doOnError(err -> handleError(unwrapL2(err), turnId))  // 最终失败一次（解包）
    .doOnComplete(() -> handleComplete(turnId))
    .doOnCancel(this::trimDanglingToolCalls);
```

- **provider/options 快照留在 submit 一次性取**（现行 `registry.activeRequestSelection()`
  快照语义不变）：spec 构造进 defer，但选择不随之延迟——否则退避等待期 `/model`
  切换会让续跑打到另一家 provider + 错误 options（同回合模型漂移）；
- **retryWhen 位于 doOnNext/doOnError 之上**：中间失败被吞掉续跑，doOnError 只见
  最终失败——否则每次尝试都把状态打成 IDLE；
- **同一 Disposable**：退避 delay 挂在原链上，`Disposables.composite(reactive,
  () -> { disposed.set(true); cancelTurn(turnId); })` 的 dispose 同时取消 timer、
  重订阅、置 disposed 标志（防 boundedElastic 上残跑的 defer 体再发起一轮
  无谓请求），Esc 在任何等待期都立即终止且不漏 `cancelTurn`；
- **取消语义的 Reactor 保证（第 9 轮字节码级验证）**：幽灵请求
  不存在——`RetryWhenMainSubscriber.cancel()` 先取消 companion（退避 timer）再
  取消上游；`resubscribe()`/`MultiSubscriptionSubscriber.set()` 首行查 cancelled 短路；
  `Operators.setOnce` 见 CancelledSubscription 即拒绝——`request()` 永不发出、Reactive
  Streams 语义下不会建立/发出 HTTP请求；
- **L1 重订阅无连接泄漏**：重订阅 ⇔ 旧订阅已 error 终态（Reactive
  Streams 终态语义，连接随之释放）；两条路径（L1 内部重试/
  StreamInterruptedException 放行）推演一致，不存在旧订阅仍活着的重叠窗口；
- **`unwrapL2`**：L2 自身耗尽时 doOnError 收到 `Exceptions$RetryExhaustedException`
  （英文样板 message），解包取 `getCause()`（= 最后一次 StreamInterruptedException，
  其 cause=原始网络异常；message 置空 → `formatError` 落到根因文案）再进 handleError。

**续跑退避**：1s×2ⁿ 封顶 4s（mid-stream 断流常伴网关深部故障，比 L1 略宽）。

**`prepareResume()`（仅续跑轮执行；返回 `ResumeShape` 枚举：`FROM_TEXT`=形状①/
`FROM_TOOLS`=形状②）**——执行序（第 5 轮 M2 重排：**判定必须在 trim 之后**——形状②
断在 assistant(tool_calls) 时 trim 前尾部是悬空 tool_calls、trim 后才是 tool 结果，
先判会把形状②误判成形状①）：

1. `trimDanglingToolCalls()`：裁形状②的悬空 assistant(tool_calls)（幂等；首轮跳过
   无害——submit 顶部已裁过）；
2. **分流判定**：trim 后会话尾部是 UserMessage → **形状①**；尾部是 tool 结果/其他
   → **形状②**。（边界说明：形状②轮若断流早于任何 tool 结果落库、trim 把整段裁回
   兜底 `user("")`，会误判为形状①而重放问题——问题在历史里已有一条，模型看到两份。
   无数据丢失、形状合法，代价是 token 噪音；V2 真机校准后若高发再改为「会话内已
   存在本回合非空 user 则不重放」，记 R11）；
3. **`stripTrailingTurnUser()`**（形状①）：删除会话尾部本回合 user（**识别依据：会话最后一条事件是 UserMessage 即属本回合**——续跑只发生在本回合内，尾部 user 必是本回合的）——重走 advisor 链时 `SessionMemoryAdvisor.before()` 必然再 append 一条，删除+重追加 = 恰好一条；
4. **全域清除续跑伪影 blank user**：删除会话中**所有** content 为**空串**（严格
   `isEmpty()`，不用 isBlank——留出「纯空白用户消息」的将来语义）的 UserMessage
   事件（不限于尾部）。安全性：合法回合不可能产生空 user（提交闸门挡空输入、offer
   挡空插话——UI isBlank 早退/`/queue` 空 body 拒绝/`/continue` 恒非空/offer isBlank
   不入队/skill 注入非空前缀；守卫测试点名这些闸门，防未来新增空提交路径被静默
   误删）。**时机两处**（第 5 轮 M1）：prepareResume 内一次 + `handleComplete` 内
   一次（最后一轮续跑成功后不再有 prepareResume，成功路径的伪影靠 handleComplete
   清）；
5. `interjections.refillForResume()`：把 delivered 中已注入但回合未完成的插话
   **addFirst 移回 pending**（旧插话不得被退避期新 offer 的插话插队）；首次尝试
   注入了它们但流报废，模型从未消费，不回填则插话静默丢失。**形状①轮 refill 出的 pending 由 composeResumeUser 并进 .user()（见伪码 defer 分支与 composeResumeUser 段），不走 Interjecting**；
   形状②轮的 pending 仍走 Interjecting 注入；
6. **notice 分流**（第 5 轮 m2）：**仅形状②**调 `interjections.setResumeNotice(NOTICE)`（若会话尾部 user 已含 `<system-notice>` 标记则跳过——形状①→②混变时
   notice 已随 user 落历史，避免双份）；形状①的 notice 已并入 .user() 文本。

**形状①/② 续跑出站形状对比**：

| | 形状①（文本段断） | 形状②（工具循环中途断） |
|---|---|---|
| 会话尾部（strip 后） | 上一轮 assistant(终答) | tool 结果 |
| 出站 user | `.user(问题+插话+notice)` 一条（composeResumeUser） | before() 兜底 user("")（出站不含）+ Interjecting 注入插话/notice 合并条 |
| 本轮问题 | 在出站（重放） | 在会话历史（首轮已落库） |
| 插话（refill 后） | 并进 .user() 同一条 | 并进 Interjecting 合并条 |
| notice 落会话 | 随 user 落（-c 回放可见） | 不落（Interjecting 通道） |

**首轮纪律**：`resubscriptions==1` 时跳过全部步骤（trim/strip 对干净会话是 no-op，
但 notice 绝不能进首轮；统一跳过最简且免边缘案例）。

**`composeResumeUser` 重放表达式恒为纯函数**（第 5 轮「形状①二连断」验证项）：
`effectiveText + "\n\n" + 合并插话 + "\n\n" + NOTICE`，每轮从 submit 闭包原始值重建——
多轮续跑不叠加 notice（strip 先删上一轮含 notice 的那条再重放）。**空插话退化规则**：
无 pending 插话时退化为 `effectiveText + "\n\n" + NOTICE`（不产生连续两个空段）。
实现处加注释钉死。

**续跑注入文本**：

```
<system-notice>
网络中断，你的上一段输出未被保留。请从中断处继续完成回答，不要重复已输出的内容。
</system-notice>
```

**单写者窗口前提（第 9 轮验证）**：prepareResume 的多步读改写（getEvents →
加工 → replaceEvents）非原子，安全依赖窗口内无其他写者：after() 落库只在
聚合完成路径（mid-stream error 不落）、persistInterjection 只在 handleComplete、
子 agent 不写主会话、`/clear`/`/compact` 被 busy 闸门挡住。未来新增会话写者必须
重新评估此窗口；可选加固：仓库已有 CAS 形态
`replaceEvents(sid, events, expectedVersion)`，改用它 + 丢更新重试一次。
（会话文件本身不会损坏：FileSessionRepository 所有变更在
ConcurrentHashMap.compute 同一 bin 锁内完成且 tmp+ATOMIC_MOVE 落盘。）

**续跑重订阅的副作用重放清单（冷流的代价，逐项处置）**：
- `SessionMemoryAdvisor.before()` 重追加 → 步骤 3/4 处置（形状①删+重放并入 user；
  形状②落空 user、出站不含、全域清除伪影）；
- **preflight compaction 重查**（`PreflightCompactionAdvisor` 每次订阅重跑 token
  预估）：多数 no-op；断流回合写了新事件时可能真跑一轮压缩（阻塞 aux 摘要）——
  **接受**：压缩正确且必要，阻塞已被 boundedElastic 隔离；RETRYING 与 compacting
  指示器并存时 View 层 compacting 优先（既有优先级不变）；
- Interjecting 重注入插话 → 步骤 5 想要的（形状②）；
- observation 重启 → NOOP registry，无副作用。

**预算与放大系数**：最坏 = 3 轮完整流 × 每轮 5 次尝试（4 零下发重试 + 1 次
mid-stream 失败）= **15 次请求**；退避累计 = (0.5+1+2+4)×3 + (1+2) = **25.5s**。
零下发耗尽被白名单挡在续跑外，放大只发生在复合场景，封顶明确。
巨大 effectiveText（如 /skill 注入全文后断流）的重放成本有界且与手动重发等价——
不设阈值（截断破坏正确性），仅出站 user 超 200KB 时 `log.warn`
一次（含字节数与续跑轮次，供事后对账）。

### 3.4 注入通道：Interjecting 重构纪律

`InterjectingChatModel` 的插话通道有 delivered 落库（`persistInterjection` 回合末
写会话）、`fireDelivered` → `onUserMessage`（把注入文本当用户原话渲染）、与真实
插话拼接三重冲突。故 `Interjections` 新增**独立字段**：

```java
private volatile String resumeNotice;   // 一次性：注入即清
void setResumeNotice(String text);
```

`InterjectingChatModel.inject(prompt)` **重构两条纪律**：
1. **早退分支重构**：现 `if (text.isEmpty()) return prompt` 在无插话时直接返回——
   无插话但有 resumeNotice 时仍要追加；
2. **合并纪律**：注入产物**恒为一条 UserMessage**——「合并后的插话 + notice」用
   `\n\n` 拼接（`drainForInjection` 先例为单换行 `\n` 合并多条 pending；此处升
   双换行，把系统 notice 与用户插话在文本上明确隔离）。独立 append
   会在「插话 + notice」场景产生两条连续 user → DeepSeek 400（注入产物不落会话、
   出站从不 sanitize，无折叠兜底）。

注入顺序：插话在前、notice 在后。**不进 delivered、不 fireDelivered、不参与
takeForHistory/drainForRefill/persistInterjection**——形状②路径的 notice 不落会话、
`-c` 回放不含它。Esc 取消（`takeBackInterjections` 回填输入框）也碰不到它——系统
提示绝不会「还给」用户输入框。

**Esc 清理纪律**：`drainForRefill()` 锁内一并清 `resumeNotice`——Esc 取消与
`/clear` 都经 drainForRefill，一处覆盖两条清理路径。防泄漏窗口：setResumeNotice
之后、inject 消费之前若被 dispose（如 preflight compaction 阻塞期间按 Esc），
notice 会残留到下一回合的 inject 被误注入；drainForRefill 清空后该窗口关闭。
与 inject 的读取保持同一监视器锁，避免清/读撕裂。

### 3.5 UI：重试提示行与状态（ConversationState + View）

**新事件**（default 空实现，测试桩无感）：

```java
/** 重试已排定。L1=零下发透明重试（屏幕无内容，清残为 no-op）；L2=mid-stream 续跑。
 *  UI 应落定流式残行、显示重试提示；reason 为已本地化的短语。 */
default void onRetryScheduled(long turnId, int attempt, long backoffMs, String reason) { }
```

**`ConversationState.onRetryScheduled`**（同步锁内，与 onError 同款迟到过滤）：
1. `flushStreaming()`：半截残行**落定进 scrollback（保留显示，不删）**——行内滚动
   UI 无法撤回已 println 的整行（§0 诚实声明）；残行落定后 `streaming` buffer
   清空、live 预览立即收起（既有 flushStreaming 行为），新一轮 onAssistantToken
   从空 buffer 重新 append，行为正常。**L2 场景（残行非空时）在 ↻ 行前补一条空
   INFO 行**分隔「半截输出 / ↻ / 新输出」三段（对齐 onUserMessage 回合间留白先例；
   L1 无残行时不加，避免用户块下双空行）；
2. 追加提示行（Kind.INFO，前缀 ↻）。**文案与序号**：
   - L1：`↻ 重试中 (2/5·传输)：{reason}，0.5s 后重发`——带「传输」限定词，与 L2
     对仗，避免续跑轮 L1 计数归零后的「2/5 → 1/5 回跳」读起来像 bug；
   - L2：`↻ 重试中 (续跑 1/2)：{reason}，1.0s 后重发`；
   - **reason 单行化**：入文案前经 `summarize()` 式截断（显示宽 ≤80）——超长
     WebClientResponseException message 会把 ↻ 行折成 2-3 行，放大堆积观感；
   - 退避时间为**静态文本**（scrollback println 后不可变，物理上无法倒计时；
     Claude Code 同为静态；若要动态唯一合算落点是 RETRYING 状态行——可抄
     `StatusBar.compacting(elapsedNanos, animTick)` 的动态计时先例，属可选增强）；
3. `status = Status.RETRYING`（新枚举值）；
4. 发布 **`UiDirty.ALL`**（对齐 onError 的 `changed(UiDirty.ALL)`）：status 变更
   影响 isBusy/submissionSnapshot/BackgroundNotifier 闸门（控制流），只发
   OUTPUT|VIEW 会漏掉 CONTROL 重估。

**↻ 行可见性时序（已验证安全）**：`doBeforeRetry` 在退避 delay 之前同步执行 →
publish 发生在退避等待开始前 → UiUpdateCoordinator 单批内 pending 行（println 进
scrollback）与 status 行（live 区重绘）同帧上屏；busy 期间 pending 行无节流压制
（仅 live 预览 150ms 节流与 pty 写饱和闸，均不碰 pending）；RETRYING 非 IDLE →
66ms 动画帧持续续排，退避期间状态行波光照常动，不死屏。

**RETRYING 状态行渲染与宽度预算（R7-M4——80 列必截尾，有前科）**：
- 渲染：`statusLine()` 穷尽 switch 强制补 RETRYING 分支——**复用 `StatusBar.shimmer`
  波光**（与 THINKING/RUNNING_TOOL 同设计语言），静态 `↻` 字符 + 波光，无需旋转动画；
- **宽度算术**：label 固定短格式 `↻ 重试中 2/5·传输` / `↻ 重试中 续跑 1/2`
  （≤17 列）；`· 退避 1.0s` 只在 `terminalWidth() ≥ 100` 时附加；**RETRYING 分支
  省略 cacheHit 后缀**（重试期间最不相关），保留 `· Esc 取消`。测算依据：IDLE 行
  前科 98 列 80 列截尾（CacheHitStatusBarWidthTest）；modeTag 最长 ≈19 + label 17 +
  Esc/Ctrl+C 后缀 ~22 + 排队/插话 ~15 ≈ 73 列，80 列完整可见；
- 测试：`RetryStatusBarWidthTest`（克隆 CacheHitStatusBarWidthTest 的 ViewScreen
  80 列断言法）钉「80 列下 `↻ 重试中` 与 `Esc 取消` 完整可见」。

**RETRYING 的出路（合法 busy 态迁移，共三条，均为如实反映）**：
- `onAssistantToken`：`RETRYING → THINKING`（新增迁移；首个新 chunk 到达）；
- `onToolStarted/onToolFinished`：现有代码无条件写 `RUNNING_TOOL/THINKING`——续跑轮
  模型可直接吐 tool_calls（无文本），首个事件即工具事件，状态自动离开 RETRYING，
  **零改动**（断流瞬间已有主 agent 工具在飞时，其迟到 onToolFinished 落在退避窗口内
  也会提前把 RETRYING 翻成 THINKING——纯视觉、无害，第 9 轮验证）；
- `onSubagentFinished`：同 turnId 放行、只加 SUBAGENT_END 行与面板更新、**不写
  status**（第 9 轮验证）——续跑期间子 agent 完成不打断 RETRYING，↻ 状态行继续波光；
- `onError/onTurnComplete`：终态。

**RETRYING 与 compaction 正交（已验证）**：`compacting` 是独立 volatile，
onCompactionStarted/Finished **不写 status**；RETRYING 期间压缩照常显示（compacting
优先级既有逻辑），完成后 status 仍 RETRYING，首个新 chunk 照常离开。

**同步改动点清单**：`CodeTuiView` 状态行渲染是**穷尽 switch 表达式**——新增枚举值
编译器强制补 RETRYING 分支（利好）；`isIdle/isBusy/submissionSnapshot` 全基于
`==/!= IDLE`，RETRYING 天然 busy **零改动**；全仓 `state.status()` switch 仅此一处。
**RETRYING 期间互斥**：`/clear`、`/compact` 被 isBusy 闸门挡住；后台任务完成通知
要求 idle 不送达（`BackgroundNotifier`），结果在回 IDLE 后再进。

**显示效果**：

```
用户：帮我重构这段代码
  ⎿ Read src/main/Foo.java ✓
  （……半截 assistant 输出落定保留……）
（空行分隔）
↻ 重试中 (续跑 1/2)：流中断，1.0s 后重发
  （新流 chunks 重新流入，状态栏 ↻ 重试中 · 波光 → spinner）
```

**↻ 行堆积的架构声明（append-only 约束）**：一回合最坏 14 条 ↻ 行（L1 每轮≤4 ×
3 轮 + L2≤2；L1 计数随 L2 重订阅归零重启，序号回跳见上文案说明），逐次追加进 scrollback——append-only 转录无法就地刷新 attempt 计数，「替换最后一行」
语义不存在（pending 是 ArrayDeque 只能 addLast/pollFirst，OutputLine 不可变 record）。
逐行追加是本架构约束下唯一可行的等价呈现，序号文本 `(2/5·传输)` 自解释进度；
Claude Code 为自绘转录区单行就地刷新，与本架构不可比。可选收敛面（非目标）：仅
状态行 live 区可变，首次重试打一条 scrollback 行、后续只滚动状态行计数。

### 3.6 日志与可观测性

- L1 每次重试：装饰器 `log.warn("主 agent 流式请求失败（第 {}/{} 次），{}ms 后重试：{}",
  ...)`——格式对齐子 agent 既有 WARN；RetryReporter 之外的独立记录；
- L2 续跑：`log.warn("回合 {} mid-stream 断流（emitted={}），第 {}/{} 次续跑", ...)`；
- 最终失败：`log.error("回合 {} 重试耗尽（L1/L2 明细见上），最终失败", ...)`。
  **用户可见文案的拼接纪律（R6-M3）**：`handleError` 共享于零重试错误（401/400），
  前缀不能无差别套——CodingAgent 在 doOnError 处按**局部重试计数**（L1 侧计数挂
  submit 闭包内局部 AtomicInteger、经与 turnId 绑定同一路径的桥闭包递增，⚠ 不得为
  桥实例字段——跨回合残留风险同 resubscriptions；L2 读局部 resubscriptions）判定
  是否重试过，重试过才构造**一次性拼好 message**
  的包装：`已自动重试（传输 a 次/续跑 b 次）仍失败：{根因文案}`（根因自行做一遍
  formatError 式 cause 链遍历——不能经两层包装靠 formatError 取 message，它只取
  首个非空会停在包装层，置空 StreamInterruptedException message 的设计就白做了）。
  零重试错误原样透传。屏幕上早前的 ↻ 行与末行失败文案自然呼应（「重试中 (续跑
  2/2)」→「已自动重试（续跑 2 次）仍失败」，保留不删）。

### 3.7 装配（AgentTools 主链）与装饰链顺序

```
InterjectingChatModel.wrap(visionModel, interjections)          ← 最外（不变，既有约束）
  visionModel = VisionMaterializingChatModel.wrap(
      RetryingStreamChatModel.wrap(provider.chatModel(), l1Reporter), ...)
      provider.chatModel() = UsageRecording(IdleTimeout(raw))    ← 不变
```

即 `Interjecting(Vision(RetryStream(Usage(IdleTimeout(raw)))))`。
（开关 `CODETUI_STREAM_RETRY=off/l2` 时 RetryStream 不包装/直通，见 §3.1 末总闸。）

**位置论证**：
- **RetryStream 在 IdleTimeout 外**：空闲超时（含首字节超时）必须可重试；
- **RetryStream 在 Vision 内**：L1 的重订阅复用同一已物化 Prompt。L2 续跑会重调
  Vision.stream() 重新物化——完整管线重走的必然代价，视觉回合占比低，接受；
- **RetryStream 在 Usage 外**：provider.chatModel() 是主/子/aux 三路共用的共享链
  （SubagentRunner 在其外再包 RetryingChatModel、aux 直接用），放共享链内会给子
  agent 双重重试、给 aux 违反守卫。**usage 记账口径（R6-M2）**：连接错/429/5xx 的
  零下发尝试真正无 usage chunk（不记账）；**空流尝试（正常完成但零内容）会带
  usage-only 终末 chunk，各记一笔——这是网关真实计费，如实记录、不做回滚**
  （TokenUsageAccumulator 语义即计费累加；cacheHitPercent 分子分母同增不失真；
  compaction 触发按会话事件文本估算、与 usage 解耦，不虚增）。L2 续跑各轮 usage
  同理是真实花费，本就该各记一笔；上下文占用（ContextStats.estimatedTokens）来自
  会话事件文本估算而非 usage，不受重复请求影响；
- **Interjecting 仍在最外**：插话队列必须在重试层外（两次既有注释钉死的约束），
  L2 的 refill/notice 语义也依赖它每轮重注入。

**守卫测试**：
- 装饰链顺序断言：主链 unwrap 依次为 Interjecting → Vision → RetryStream →
  Usage → IdleTimeout；
- `AuxClientNotRetryWrappedTest`：aux 路径不包 RetryingStreamChatModel；
- 子 agent 路径不包 RetryingStreamChatModel（防未来装配漂移；RetryStream 只在
  AgentTools 主链 wrap，装配面收敛一行）。

## 4. 前置验证任务

| # | 验证 | 方法 | 状态/结论 |
|---|---|---|---|
| V1 | 形状② 续跑时 before() 追加空 user、出站不含它；**含成功路径：两轮续跑（最后一轮成功）后会话无任何 blank UserMessage（handleComplete 清除生效）**；形状① 续跑出站首条 user 含本轮问题文本 | 单测：伪造 advisor/会话记录 append 内容 + 断言出站与会话形状 | 第 3 轮字节码推演已给出 before() 结论，**单测钉死三断言** |
| V2 | 断流后会话尾部实际形状（形状①/②）；工具结果落库时机（决定步骤 2 边界误判率） | 真机：kill 网关连接制造 mid-stream 断流，dump 会话 JSON | 待做；校准 §1.3/§3.3 覆盖面与 R11 |
| V3 | usage-only / finish-only 收尾 chunk 形态；DeepSeek thinking-only chunk 是否进 `AssistantMessage.getText()` | 抓 SSE 原始帧 | 待做；校准 §3.2 hasContent 口径 |

## 5. 测试策略

| 层 | 测试类 | 关键用例 |
|---|---|---|
| 判据 | `RetryPolicyTest` | 迁移判据用例；`StreamIdleTimeoutException/EmptyStreamException → 重试`；与 RetryingChatModel 旧行为等价 |
| L1 | `RetryingStreamChatModelTest` | 零下发 429 → 重试成功；零下发 400/401 → 放行不重试；首字节超时 → 重试；mid-stream 断流 → 不重试、包装 StreamInterruptedException(emitted>0, message 空)——**断言 emittedChunks==k（可观察出口）**；**类型穿透（经真实 DefaultChatClient + SessionMemoryAdvisor 装配，桩 ChatModel 抛 StreamInterruptedException，断言 CodingAgent.doOnError 收到原类型）**；filter 拒绝路径 → emittedChunks 断言；**空白-only chunk 流断流 → 仍判 mid-stream（text 非空口径）**；usage-only 空收尾后断流 → 仍判零下发；空流 → EmptyStreamException → 重试；**空流(带 usage)→重试成功：TokenUsageAccumulator 记 2 笔、cacheHitPercent 仍正确（如实记账声明）**；L1 耗尽出口形状（isRetryExhausted 解包放行原始异常）；emitted 跨重试重置；取消 → 不重试；**退避序列 0.5/1/2/4（jitter(0) + StepVerifier.withVirtualTime + RetryBackoffSpec.scheduler 注入虚拟调度器）**；RetryReporter 收到 (attempt, backoff, reason)（经 doBeforeRetry，backoffMs 与真实 delay 严格相等） |
| L2 | `CodingAgentTurnResumeTest` | **形状①：续跑出站首条 user = 问题+插话+notice 一条（composeResumeUser 合并）**；**形状①+refill → Interjecting 不再注入（pending 已清空）→ 出站仅一条 user**；形状②：续跑请求不带 user + 会话形状断言；**多轮续跑（最后一轮成功）后无任何 blank user（handleComplete 清除）**；**①→②混变：尾部 user 已含 notice 标记 → 不再 setResumeNotice（防双份）**；**②→①边界误判（trim 裁回空 user）：重放问题，会话含两份问题，形状合法、不丢数据（R11 声明行为）**；resubscriptions 局部性；prepareResume 门控（首轮无 notice/无 strip）；**执行序经对抗性夹具断言后果**（尾部=「悬空 tool_calls+user」时若判定先于 trim 必误判 → 断言出站形状；需直测则抽 package-private `prepareResumeForTest` 返回形状/事件快照）；refill addFirst；refill + notice → 出站仅一条尾部 user；白名单外 → 不续跑直接 handleError；**续跑上限 → 耗尽走 handleError 且文案为「已自动重试（…）仍失败：根因」（局部计数拼接式，非两层包装）**；**零重试错误（401）→ 文案无前缀**；Esc 退避等待期 dispose → 无后续事件、cancelTurn 被调；**Esc 在 setResumeNotice→inject 窗口 → notice 不泄漏进下一回合（latch 桩确定性开窗，禁 sleep 竞态写法）**；同 turnId |
| UI | `ConversationStateRetryTest` + `RetryStatusBarWidthTest`（新） | 残行落定且半截整行保留；**L2 残行非空时 ↻ 前有空行、L1 无**；**reason 经单行化（≤80 显示宽）**；**发布 UiDirty.ALL**；RETRYING 进 busy 闸门；onAssistantToken RETRYING→THINKING；RETRYING 下 onToolStarted/Finished 现有迁移不炸；迟到 turnId 过滤；L1/L2 文案区分（·传输/续跑前缀）；RETRYING 期间 onCompactionStarted 不破坏后续迁移；**80 列下 RETRYING 状态行 `↻ 重试中` 与 `Esc 取消` 完整可见（克隆 CacheHitStatusBarWidthTest 断言法）** |
| 装配 | 新建 `AgentToolsRetryWiringTest`（对齐 AgentToolsMediaWiringTest 家族命名）+ `AuxClientNotRetryWrappedTest` + blank-user 闸门守卫 | 装饰链顺序断言；aux/子 agent 路径不含 RetryingStreamChatModel；**空 user 唯一来源是续跑伪影**（点名提交闸门/offer isBlank/skill 前缀，防未来新增空提交路径被全域清除误删）；**CODETUI_STREAM_RETRY 开关**（off 不包装直通 / l1 仅 L2 关（白名单恒 false，mid-stream 直走 handleError 不续跑）/ l2 仅 L1 关 / 非法值回退 all+warn / **dispose 后 boundedElastic 不再 replaceEvents**） |

## 6. 风险与开放问题

| # | 风险 | 缓解 |
|---|---|---|
| R1 | before() 空 user 行为与推演不符 | V1 单测钉死三断言；出站形状断言兜底 |
| R2 | 重试期间用户输入新消息 | RETRYING 非 idle → busy 闸门排队 |
| R3 | 重试放大 token 消耗 | 预算封顶（15 次请求 / 25.5s 退避）；日志带明细 |
| R4 | Retry.backoff 默认 jitter=0.5 随机化退避 | **显式 jitter(0)** 关闭（§3.2）：保住退避序列确定性、UI 文案诚实、预算算术；单用户 TUI 无需抖动 |
| R5 | L2 续跑重调 Vision.materialize | 视觉回合占比低；物化幂等；记录不改 |
| R6 | 行内滚动 UI 半截输出保留 | §0 诚实声明；scrollback repaint 另行立项 |
| R7 | prepareResume 阻塞段跑在 parallel 调度器 | subscribeOn(boundedElastic) 隔离（§3.3） |
| R8 | 「答案已完整、收尾前最后一断」被判 mid-stream 全量续跑 | 不做断点续接的声明代价；可选增强另行立项 |
| R9 | DeepSeek thinking-only chunk 是否计入 hasContent | V3 抓帧定论 |
| R10 | 形状① notice 随 user 落会话，-c 回放可见 | 刻意取舍（换取问题文本保留）；文案中性 |
| R11 | ②→①边界误判重放问题，模型看到两份问题 | 无数据丢失、形状合法；V2 校准误判率，高发再改判定（会话内已存本回合非空 user 则不重放） |
| R12 | 空串用户消息的合法路径未来出现，被全域清除误删 | 守卫测试点名全部空输入闸门；清除条件严格 isEmpty() 不含纯空白 |
| R13 | 自动重试透明花钱（最坏 15 次请求）无用户闸 | `CODETUI_STREAM_RETRY` 总开关（all/l1/l2/off，§3.1 末），照抄 LlmTimeouts.from(env) 先例 |

## 7. 实施切分（供 writing-plans 展开）

0. **前置验证 V1–V3**（结论写回 §4）；
0b. **`CODETUI_STREAM_RETRY` 开关**（LlmTimeouts 同款 env 解析）——先于一切装配生效；
1. `RetryPolicy` 提取 + `StreamIdleTimeoutException`/`EmptyStreamException` 入判据 + `RetryingChatModel` 改委托；
2. `RetryingStreamChatModel` + `RetryReporter`（含 classify 判定树、类型穿透）+ 测试；
3. `AgentListener.onRetryScheduled` + `ConversationState`（残行落定/INFO 行/RETRYING/出路三条）+ 测试；
4. `Interjections.resumeNotice/refillForResume(addFirst)/drainForRefill 清 notice` + `InterjectingChatModel` 早退重构 + 合并纪律 + 测试；
5. CodingAgent 续跑（defer 内重建 spec + 形状分流（ResumeShape 返回值：composeResumeUser 单条 vs 不重放）+ 快照留 submit + retryWhen 白名单 + **L2 doBeforeRetry 触发 onRetryScheduled** + prepareResume 执行序 trim→判定→strip→全域清→refill→notice + handleComplete 清伪影 + boundedElastic + 取消语义）+ 测试；
6. 装配（AgentTools 主链插入 RetryStream、l1Reporter 两段式桥接）+ 链序守卫 + aux/子 agent 守卫 + blank-user 闸门守卫测试。

## 评审记录

- **第 1 轮**：3 Blocker + 6 Major + 4 Minor，已修（超时判据、清屏改残行落定、stripTrailingTurnUser、链序更正、notice 独立通道、显式恢复 THINKING、白名单+预算、RetryReporter、原链内 retryWhen、空流转 error、会话形状事实、文内清理、emitted 口径）。
- **第 2 轮**：1 Blocker + 8 Major + 2 Minor，已修（EmptyStreamException 入判据；isRetryExhausted+getCause 解包含 unwrapL2；形状②不重放 user；冷流确证+副作用清单；boundedElastic；单计数器；Retry.backoff 参数语义；首轮门控；测试补 6 项；turnId 绑定；白名单表述统一）。
- **第 3 轮**（实施视角）：1 Blocker + 3 Major + 5 Minor，已修（withoutUser→spec 重建；before() 必追加空 user 事实；局部计数器；classify 判定树+类型穿透；UI 穷尽清单；R8/R9；落地清单 6 项；任务顺序确认；绿灯清单）。
- **第 4 轮**（组合视角）：2 Blocker + 2 Major + 1 Minor，已修（形状①问题蒸发→形状分流；refill+notice 双 user 400→合并纪律；中部空 user→全域清除；Esc 窗口 notice 泄漏→drainForRefill 清 notice；序号回跳→文案限定词）。
- **第 5 轮**（形状分流专审）：1 Blocker + 2 Major + 5 Minor，已修：B1 形状①×refill 双 user 400→composeResumeUser 把插话并进 .user() 同一条；M1 全域清除漏成功路径→handleComplete 补清；M2 执行序矛盾→重排（trim→判定→strip→全域清→refill→notice）；m1 ②→①误判重放（R11 声明+V2 校准）；m2 双 notice→尾部已含 notice 标记则跳过；m3 预算表述修正（3 轮×5 次=15）；m4 全域清除守卫测试（isEmpty 严格口径）；m5 九处乱字清理。另验证：形状①二连断循环不丢不叠加、fold 交互一致、形状混变闭环成立。
- **第 6 轮**（角落专审）：0 Blocker + 3 Major + 4 Minor，已修：M1 Retry.backoff 默认 jitter=0.5 → 显式 jitter(0)（退避序列/文案/预算三处自洽）；M2 空流尝试带 usage 记两笔是如实计费 → 表述修正 + 声明不回滚 + 补测试（compaction 与 usage 解耦已验证）；M3 失败前缀与 formatError 机制冲突 → 局部计数 + 一次性拼好 message 的包装（零重试错误无前缀）；m1 空白 chunk 口径 → text 非空（含空白）+ 不变式写死；m2 finish/拼接闭环验证成立（无洞，写入不变式）；m3 UI 时序不可能乱序（doBeforeRetry 钉死钩子 + backoffMs 现算）；m4 四个难测项给出可操作断言方式（emittedChunks 出口断言、虚拟调度器、真实 advisor 装配穿透测试、latch 桩开窗）。
- **第 7 轮**（UI 呈现专审）：0 Blocker + 1 Major + 6 Minor，已修：M4 RETRYING 状态行无宽度预算 → label 短格式（≤17 列）+ 退避仅在 ≥100 列附加 + 省略 cacheHit + RetryStatusBarWidthTest（80 列前科 98 列截尾）；m1 发布 UiDirty.ALL（含 CONTROL）；m2 退避静态文本声明（倒计时可选增强：抄 compacting 计时先例）；m3 L1 ↻ 行位置验证正确、堆叠接受（替换语义不存在已查证）；m5 「Claude Code 同款」错误类比删除 → append-only 架构声明；m6 L2 ↻ 前空行分隔；m8 reason 单行化（≤80 显示宽）。另验证：↻ 行时序安全（doBeforeRetry 先于退避、单批双通道同帧上屏、动画不死屏）；markdown 围栏跨流接续（printer 级 md 单例，隐性利好）；错误终态行红/灰层级清晰。
- **第 8 轮**（全文一致性审计）：14 处，已修：#1 ↛ 行上限矛盾（6→14，L1 随重订阅归零）；#2 L2 提示行触发点缺失（retryWhen 补 doBeforeRetry→onRetryScheduled，attempt 口径 2..3）；#3 \n\n 先例引用失实（drainForInjection 实为单换行，改为「升双换行隔离」表述）；#4 resumeFromText 死参数（改 ResumeShape 枚举返回值）；#5 步骤 5 引用错位；#6 AgentToolsWiringTest 孤儿名（改新建 AgentToolsRetryWiringTest）；#7 评审编号对不上账（Q1→实名引用）；#8 RetryReporter.attempt 口径（2..5 尝试序号）；#9 SmartWebFetch→SmartWebFetchTool；#10 strip「本回合 user」识别依据（尾部即本回合）；#11 composeResumeUser 空插话退化规则；#12 wrap 引文改转述；#13 行内代码换行截断；#14 L1 计数载体（submit 闭包局部 AtomicInteger，不得为桥实例字段）。审计确认：表格/参数数值/遗留术语/§引用/评审落实抽查 8/8 命中。
- **第 9 轮**（安全/资源/极端场景）：0 Blocker + 2 Major + 5 Minor，已修：M1 defer 体内 disposed 检查（防取消后 boundedElastic 残跑一轮无谓会话写；幽灵请求经 Reactor 三重护栏字节码级验证确认不存在，写进取消语义）；M2 `CODETUI_STREAM_RETRY` 计费总闸（all/l1/l2/off，照抄 LlmTimeouts.from(env) 先例；CC 无重试开关先例已核实——DISABLE_NONESSENTIAL_TRAFFIC 只管非必要流量）；m1 L1 重订阅无连接泄漏声明（重订阅⇔旧订阅已 error 终态）；m2 子 agent 事件不打断 RETRYING（onSubagentFinished 不写 status，写进出路清单）；m3 单写者窗口显式前提（+可选 CAS 加固；会话文件无损坏路径：compute bin 锁+原子 move）；m4 巨大 effectiveText 不设阈值、超 200KB 对账 warn。另验证：Esc 退避期 dispose 行为与现状等价。
- **第 10 轮**（终审）：裁决 **READY-WITH-CONDITIONS→条件已全部落实**。收敛轨迹确认（Blocker 3→1→1→2→1→0→0→0→0→0，无翻烧饼，历轮关键决策存续）；完备性终判 5/5 全勾（签名/依据/测试可展开/切分无倒挂/开放问题有路径）；实现期前 3 风险点名（R4 jitter 双处必带验收断言、R1 的 V1 为硬门、R9 的 V3 前置）。条件修补：N1 L2 伪码补 .jitter(0d)；N2 attempt 注释改续跑序号 1..2；N3 装配测试补 l1 取值用例；顺手：§3.7 开关交叉引用、「幽灵」乱字 ×2。
