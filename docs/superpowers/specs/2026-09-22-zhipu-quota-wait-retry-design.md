# 智谱 Coding Plan 限额感知重试（等到重置时刻自动续跑）设计

日期：2026-09-22
状态：**终稿 v2**（v1 经双 subagent 审核——代码事实核查 + reactor 语义/设计逻辑评审——
修订 3 项必修：限额分支加 filter 门控、预算豁免改扣减偏移、串行子 agent 可 interrupt；
另采纳 5 项勘误与若干实现红线，见文末「v2 修订记录」；待 writing-plans）
参考：智谱 BigModel 错误码文档 `https://docs.bigmodel.cn/cn/api/api-code`
关联：`docs/superpowers/specs/2026-09-03-main-agent-stream-retry-design.md`（L1/L2 重试架构，
本设计在其上扩展）；`docs/superpowers/specs/2026-08-18-subagent-retry-transient-expansion-design.md`

## 0. 摘要

智谱 GLM Coding Plan 有**滚动 5 小时**与**滚动 7 天**两套使用限额。达到限额后 API 返回
HTTP 429（业务码 1308/1310/1316/1317/1318-1321），message 内嵌重置时刻（`next_flush_time`
占位符的实际值，如「您的限额将在 `2026-09-22 15:30:00` 重置」）。

现状：429 已被 `RetryPolicy.shouldRetry` 判为瞬态，会走 L1/L2/子 agent 的指数退避
（封顶 30s，Retry-After 封顶 60s）——但对「几小时后重置」的限额窗口，单层 L1 约 1 分钟
（退避序列 1+2+4+8+16+30s = 61s）即耗尽预算，回合以失败告终，用户必须手动重发。

本设计：**识别智谱限额错误 → 解析重置时刻 → 回合挂起、UI 显示倒计时，睡到重置时刻
自动重试**。三条重试路径（L1 流式透明重试 / L2 回合级续跑 / 子 agent 阻塞重试）全部
覆盖；限额等待**不挤占普通重试预算**（耗尽判定按限额次数扣减，§3.2），由订阅级独立
计数兜底；Esc 随时可取消等待（含串行子 agent——本设计补齐其可 interrupt 短板，§3.4）。

## 1. 背景与事实

### 1.1 智谱限额错误形态（官方错误码文档核实，2026-09-22）

| 业务码 | HTTP | 语义 |
|---|---|---|
| 1308 | 429 | 达到 `${number} ${unit}` 使用上限 |
| 1310 | 429 | 达到每周/每月使用上限 |
| 1316 | 429 | 5 小时上限 + 主账号余额不足，无法超额按量付费 |
| 1317 | 429 | 7 天上限 + 主账号余额不足 |
| 1318/1319/1320/1321 | 429 | 5h/7d 上限叠加子账号/企业月消费上限 |

1316 示例 message（文档原文）：「已达到 5 小时使用上限。主账号余额不足，无法使用超额
按量付费。您的限额将在 `{next_flush_time}` 重置。」——重置时刻**只内嵌在 message 文本**，
无结构化字段、无专用响应头（无 `x-ratelimit-reset` 等）。

**不在等待集合内的近邻错误**：1113（账户欠费）、1309（套餐过期，需续订才恢复）、
1311（模型权限）——这些等到天亮也不会自愈，等它们是浪费；保持现有「429 瞬态、
有限重试后失败」行为。

### 1.2 SDK 能力（openai-java-core 4.49.0 源码核实）

code-tui 智谱 provider 走 spring-ai-openai（openai-java SDK，OkHttp 栈）。HTTP 429 由
SDK 映射为 `RateLimitException`（`OpenAIServiceException` 子类）：

- `statusCode()` == 429；
- `code(): Optional<String>` —— 从响应 body 的 `error.code` 解析，**智谱业务码字符串
  （"1316" 等）可直接拿到**；
- `getMessage()` == `"429: " + error.message` —— 完整中文 message（含重置时刻）可读
  （`firstNonBlankMessage` 对其返回该全文，截 60 显示可用）。

Spring `WebClientResponseException`（WCRE）路径**不识别**为智谱限额：走 WCRE 的
provider（anthropic/deepseek 系）不是智谱，判据保持 provider 中立。

### 1.3 现有重试架构（三层，判据/退避唯一真相源 `RetryPolicy`）

- **L1** `RetryingStreamChatModel`：主 agent 流式零下发透明重试，`L1_RETRIES=6`
  （总尝试 7），reactive `retryWhen(RetryPolicy.backoffRetry(...))`；
- **L2** `CodingAgent` 回合级续跑：`L2_RESUMES=4`，同 `backoffRetry`，白名单
  `StreamInterruptedException`（SII）；
- **子 agent** `RetryingChatModel`：阻塞 for 循环，`MAX_ATTEMPTS=7`，`sleeper` 可注入。

**reactor 计数事实（3.8.7 源码核实，两项）**：

1. `RetrySignal.totalRetries()` 由框架自数（`RetryWhenMainSubscriber` 在每次 onError
   自增），companion 发出的值只是「触发重试」的载体，**无法重置、无法阻止自增**——
   限额等待同样会推高 `totalRetries`。「限额不挤占普通预算」因此**不能**靠跳过判定
   实现，只能靠**扣减偏移**（§3.2）；
2. `FluxRetryWhen.subscribeOrReturn` 在**每次订阅**时调用 `generateCompanion`（即
   `Retry.from(fn)` 的 `fn.apply`）——**lambda 体内建的局部状态是订阅级**，随
   （重）订阅重建，不跨回合累积。

### 1.4 现有 UI 机制

`AgentListener.onRetryScheduled(turnId, attempt, maxAttempts, backoffMs, reason)`
（default 空）→ `ConversationState.onRetryScheduled`：INFO 输出行 `↻ 重试中 (2/7·传输)：
...，30.0s 后重发`（`formatBackoff` 为 `%.1fs`）+ `status=RETRYING` +
`retryLabel/retryBackoffText`（volatile，离开重试态必须 `clearRetryState()`——契约注释
在 `ConversationState` 内，本设计同步扩展）。桥接纪律：L1 经 `RetryReporter` →
`AgentTools.wireL1` → `CodingAgent.onL1Retry` → `activeTurnL1Sink` 闭包比对
`activeTurnId` 过滤迟到事件。

渲染侧事实：`CodeTuiView` 已是事件驱动（`.noTick()`，无周期 tick）；但 RETRYING 等
非 IDLE 态下 `animationDemandActive()` 为 true——动画协调器以 ~66ms 持续重绘并
publish VIEW，「渲染层每帧现算」有现成先例（`compactElapsedNanos()` 的经过时间显示）。

## 2. 候选方案与取舍

**方案 A（选定）：`RetryPolicy` 真相源内扩展** —— 限额识别、等待时长、预算豁免全部
收进 `agent/llm` 包，三条路径因共同委托 RetryPolicy 自动获益，无装配层重复。

**方案 B（否决）：独立 L0 限额装饰器** —— 包在链最外层专门拦截限额。否决理由：
与 L1 的零下发判定（`emitted`）交互复杂（L0 拿不到下发状态，mid-stream 限额与零下发
限额难以正确分流）；判据/退避出现第二套真相源；三个装配点重复。

**方案 C（否决）：回合失败 + 到点自动重发整回合** —— 跨回合调度需处理用户新输入、
切会话、进程退出的冲突，状态机复杂；用户已选「回合挂起等待」。

## 3. 设计

### 3.1 限额识别与重置时刻解析（新类 `QuotaLimitDetector`，纯函数）

`agent/llm` 包新增：

```java
public record QuotaLimit(String code, Instant resetAt) { }   // resetAt 可为 null（解析失败）

public final class QuotaLimitDetector {
    /** 沿 cause 链找 OpenAIServiceException：429 且 (code ∈ 限额码集合 或 message 双关键词兜底)。 */
    public static Optional<QuotaLimit> detect(Throwable ex);
    /** 从 message 文本解析重置时刻；解析不出返回 QuotaLimit(code, null)。 */
    static Instant parseResetAt(String message);
}
```

- **限额码集合**：`{"1308","1310","1316","1317","1318","1319","1320","1321"}`（字符串
  比对，`code()` 即业务码字符串）。`code()` 为空时以 **message 同时含「使用上限」与
  「重置」**双关键词兜底——单关键词「使用上限」会把同走 openai-java 栈的 Qwen 等
  provider 的中文 429 文案误判进来（需同时存在可解析时间戳才真正进入等待，概率极低
  但非零；双关键词把误判面再压一个量级）。
- **时间解析**（对 message 全文做，不依赖措辞）：
  - `yyyy-MM-dd HH:mm[:ss]`（可能被反引号/引号包裹）→ 按 **Asia/Shanghai** 解析
    （bigmodel.cn 国内站）；带 `T` 分隔的同格式同法；
  - ISO-8601 带偏移/`Z` → 按自带偏移解析；
  - 均失败 → `resetAt = null`。
- **过去/临近时刻**不在此处过滤（detect 保持纯描述）；由 `quotaWaitMs` 统一兜底（§3.2）。
- 沿 cause 链逐层找 `OpenAIServiceException`（Spring AI 可能包一层 RuntimeException，
  与 `shouldRetry`/`retryAfterMs` 同法）。**SII 包装穿透**：`StreamInterruptedException`
  构造时 message 置空但 **cause 保留原始异常**——L2 路径的限额检测沿 cause 链可命中。
- **观测钩子（首次真实命中校准）**：429 且 detect 命中/解析失败均打 WARN 日志（业务码、
  message 原文、解析出的 resetAt 或 null）——限额错误的真实 message 格式无真机样本
  （无法主动构造），解析器按文档文案 + 宽容正则实现；首个真实命中后凭日志校准解析
  规则（解析失败也有退化安全网兜底，§4，不会因猜错出事故）。

### 3.2 `RetryPolicy` 限额分支（唯一真相源扩展）

新常量（与 `BACKOFF_MS` 等并列，含义与理由写 javadoc）：

- `MIN_QUOTA_WAIT_MS = 30_000` —— 解析出的时刻已在过去（服务端时钟偏差/刚重置未生效）
  时的下限退避，**杜绝 0ms 循环轰炸**；
- `MAX_QUOTA_WAITS = 5` —— 单层连续限额等待上限（防「到点重试 → 又 4316 → 又等」
  的异常死循环；正常场景等 1 次即成功）。

新纯函数：

```java
/** 限额等待毫秒 = max(resetAt − now, MIN_QUOTA_WAIT_MS)；resetAt null 返回 −1（调用方退化）。 */
public static long quotaWaitMs(QuotaLimit quota);
```

**`backoffRetry` 扩展**（既有三参重载语义不变——不传限额钩子时限额分支照常生效、
只是无 UI 上报；新增四参重载接收可选 `QuotaWaitHook`，见 §3.3）：

```java
Retry.from(companion -> {
    AtomicInteger quotaWaits = new AtomicInteger();   // ⚠ 订阅级：必须声明在 lambda 体内
    return companion.concatMap(sig -> {
        Throwable failure = sig.failure();
        // 限额分支：同样必须过该层 filter（L1 的 emitted==0 闸门 / L2 的 SII 白名单）——
        // 豁免的只是「耗尽判定」，绝不豁免层白名单，否则 mid-stream 限额被 L1 重订阅重放。
        Optional<QuotaLimit> quota = QuotaLimitDetector.detect(failure);
        if (quota.isPresent() && quota.get().resetAt() != null && filter.test(failure)) {
            if (quotaWaits.incrementAndGet() > MAX_QUOTA_WAITS)
                return Mono.error(failure);                            // 限额兜底终态
            long waitMs = quotaWaitMs(quota.get());
            fireQuotaHook(waitMs, quota.get().resetAt(), failure);     // 新回调（§3.3）
            return Mono.delay(scaled(waitMs)).thenReturn(sig.totalRetries());
        }
        ... 现有逻辑，唯一改动：耗尽判定 ...
        // if (sig.totalRetries() >= maxRetries || !filter.test(failure))   // 旧
        if (sig.totalRetries() - quotaWaits.get() >= maxRetries || !filter.test(failure))
            return Mono.error(failure);
        ...
    });
})
```

- **预算豁免的可实现形态＝扣减偏移**：reactor 的 `totalRetries` 含限额等待次数
  （§1.3 事实 1，无法阻止自增），普通分支耗尽判定改为
  `totalRetries − quotaWaits ≥ maxRetries`。每次重试要么限额要么普通，quotaWaits 恰好
  计限额次数，扣减精确；`quotaWaits == 0` 时判定与现状**逐字节一致**（不违反 §6
  「不动主干」）。↻ 行 attempt 口径同步按 `totalRetries − quotaWaits` 上报（§3.3）；
- **实现红线（quotaWaits 声明位置）**：必须声明在 `Retry.from(companion -> {...})` 的
  lambda 体内（订阅级，随（重）订阅重建，§1.3 事实 2）。放到 `backoffRetry` 方法体级
  ＝装配级（当前装配点恰好一次性订阅，侥幸无害但语义错）；提为静态/实例字段＝跨回合
  累积（真事故）。钉单测：同一装配的 Flux 订阅两次，计数器各自独立；
- **限额分支不豁免 `filter.test`**（v2 必修修正）：L1 的 filter 是
  `emitted==0 && shouldRetry`——mid-stream 限额 429（`emitted>0`）若被限额分支拦截
  重订阅，`doOnSubscribe` 重置 emitted 后重放已下发内容，破坏零下发透明不变式；
  过 filter 门控后它照旧流出 L1 包装成 SII 交 L2（L2 的 filter `instanceof SII`
  同样先于其限额判定生效）。由此两条路由结论成立：
  - **握手期限额 429**（`emitted==0`）→ L1 限额等待；L1 限额耗尽（5 次）后放行的裸 429
    被 L2 白名单（`instanceof SII`）拒绝 → 回合终态失败，错误 message 含重置时间
    仍对用户可见；
  - **mid-stream 限额断流**（SII 形态）→ L2 接手限额等待（L2 独立 quotaWaits，上限同
    5；L1/L2 合计最多 10 次限额等待）。
- 限额错误若 `resetAt` 解析失败（`quota.isPresent() && resetAt == null`），落普通分支：
  计预算、按现有指数退避重试，耗尽后失败。**这是「文案改版/解析失灵」的安全网**：
  行为退回现状，无新增风险；
- 长等待经既有测试钩子 `delayMsForTest` 压缩（`Mono.delay` 实际睡眠与上报值分离的
  纪律不变）；
- 取消语义：Esc dispose → `Mono.delay` 的 `ScheduledFuture` 随订阅取消
  （`MonoDelayRunnable.cancel` → `dispose`，3.8.7 源码核实），整链立即终止，无复活。

### 3.3 L1（`RetryingStreamChatModel`）与事件通道

- `stream()` 的 `retryWhen` 不变（`backoffRetry` 内部已分流限额）；
- **回调接口扩展**：`RetryPolicy.RetryHook` 保持不动；新增独立限额等待钩子：

```java
@FunctionalInterface
public interface QuotaWaitHook {
    /** 限额等待已排定。waitMs = 实际等待毫秒；resetAtEpochMs = 解析出的重置时刻（显示用）。 */
    void onQuotaWait(long waitMs, long resetAtEpochMs, String reason);
}
```

- 限额等待场景**不触发** `RetryReporter`（`↻ 重试中` 与 `⏳ 限额等待` 两个状态行
  互斥，防 UI 打架）；reason 复用 `reasonOf`（截 60，即智谱 message 摘要）；
- 普通重试的 ↻ 行 attempt 上报口径改为 `totalRetries − quotaWaits + 2`（限额等待
  不再虚增显示的尝试序号）。

### 3.4 子 agent 阻塞路径（`RetryingChatModel`）

`call()` 循环改造（实现红线：**改 while 循环**——「限额等待 attempt 不递增」与 for 的
`attempt++` 冲突；**单次迭代恰一次睡眠**——现「顶部 attempt>1 先睡」与限额睡叠加会
双睡；**限额判定排在 `attempt == MAX_ATTEMPTS` bail 之前**——否则第 7 次尝试上的限额
直接抛出）：

1. **退避对齐**：`sleeper.accept(backoffMsAfter(attempt - 1))` →
   `sleeper.accept(RetryPolicy.nextDelayMs(attempt - 1, last))`——顺手修复它现在
   **没有吃 Retry-After** 的既有偏差（普通 429/529 场景同样受益；已核实现有 sleeper
   测试的 FakeIoException 无 Retry-After 头，`nextDelayMs` 无头时严格等于
   `backoffMsAfter`，测试全绿）；
2. **限额分支**：捕获异常后先 `detect`——命中且 `resetAt != null` 且 `shouldRetry`：
   循环局部 `quotaWaits`（`MAX_QUOTA_WAITS=5`，每次 `call()` 重建）未超限则
   `sleeper.accept(quotaWaitMs(quota))` 且 **attempt 不递增**（预算豁免），超限或
   `shouldRetry` 否决时按现状抛出；命中但 `resetAt == null` → 走现有
   `shouldRetry`/attempt 分支（退化）。无限循环已封死：quota ≤5 次 + 普通 ≤7 次，
   交错 ≤12 次迭代；
3. **中断退出与可中断性（v2 必修修正）**：`Thread.sleep` 长等待被 interrupt → 现有
   sleeper 实现抛 `RuntimeException(InterruptedException)`，直接杀循环。但现状
   `SubagentRunner.cancelTurn` 只 `shutdownNow` **并行池**——串行（前台 Task）子 agent
   无池、无法强制打断（类注释原话「串行 run() 无池、无法强制打断」），限额等待会把
   不可打断窗口从分钟级拉到小时/天级，且 `inFlight>0` 让 busy 闸门把用户后续输入全部
   排队——Esc 后界面假死。**补齐**：`SubagentRunner` 登记串行子 agent 的执行线程
   （按 parentTurnId 索引，与 `poolsByTurn` 同构），`cancelTurn` 对其 `interrupt()`
   ——sleeper 现成响应 interrupt，机制零新增；
4. **可选 UI 桥**：`wrap` 增加重载注入可选 `QuotaWaitHook`，装配层（`SubagentRunner`
   装配处）桥到 `AgentListener.onQuotaWaitScheduled`，文案带「子任务」前缀区分；null
   时 no-op（既有测试不受影响）。**后台子 agent 特例（v2 勘误）**：后台任务
   `toolContext` 传 `TURN_ID_KEY = -1`（塞真 turnId 会被迟到过滤丢弃），而空闲时
   `acceptingTurnId == -1` 会**穿透**迟到过滤把 ⏳ 行打进空闲界面（已有
   `onToolStarted` 踩过此坑的先例）——桥接处对 `turnId == -1` **直接丢弃 + 日志**
   （后台任务的限额等待只留日志与后台面板既有状态，不开主状态行）。

### 3.5 UI：限额等待倒计时（v2 修订：无 ticker，渲染帧现算）

**`AgentListener` 新增 default 方法**（迟到过滤纪律与 `onRetryScheduled` 同）：

```java
default void onQuotaWaitScheduled(long turnId, long resetAtEpochMs, String reason) { }
```

**`ConversationState`**：

- 新字段：`volatile Long quotaWaitDeadline`（epoch ms，null = 非限额等待）、
  `volatile String quotaWaitReason`；
- `onQuotaWaitScheduled`：turnId 过滤 → INFO 行 `⏳ 限额等待：<reason 摘要>，将于
  15:30:00 重试（Esc 取消）` + `status = RETRYING`（复用，不新增 Status 枚举值）+
  `retryLabel = "⏳ 限额等待"` + 记录 deadline/reason；
- **倒计时＝渲染帧现算，零新增调度器**（v2 修订，删除 v1 的 1s ticker 设计）：
  RETRYING 态下动画协调器已在持续重绘（§1.4 事实），渲染层每帧从
  `quotaWaitDeadline` 现算剩余时间（`剩 2h13m` / 天级 `剩 6d23h`）——与
  `compactElapsedNanos()` 的经过时间显示同款现成模式；不新建
  `ScheduledExecutorService`（CodeTuiView 已刻意 `.noTick()` 拆掉周期 tick，本设计
  不开倒车）；
- **deadline 过期自愈**：渲染现算发现 deadline 已过但状态未清（事件丢失的兜底）时，
  显示「即将重试…」并容忍——下一个事件（重试/失败/完成）必到并清除；
- **清除纪律**：任何离开等待的事件（新一轮 `onRetryScheduled`/`onQuotaWaitScheduled`、
  首个新 chunk、回合完成/失败/取消）必须 `quotaWaitDeadline = null`——并入
  `clearRetryState()`（实名核实，v1 误写 `clearRetryLabel`）同位置同纪律，该方法的
  契约注释同步扩展；
- 渲染（`CodeTuiView`/状态栏消费 `retryLabel` 处）：deadline 非空时显示
  `⏳ 限额等待 剩 2h13m（15:30:00）`，复用现有 RETRYING 渲染位，不新增渲染区。

**等待期间用户新输入**：走现有插话/排队机制（InterjectingChatModel），限额等待在
重试链内挂起，与等待普通退避无区别——不受影响，不新增处理。

**L2 续跑通知文案（v2 勘误）**：现 `RESUME_NOTICE` 硬编码「网络中断」，限额续跑场景
失真——按失败类型二选：限额（detect 命中）用「限额等待后续跑」，其余维持「网络中断
后续跑」。

### 3.6 装配与桥接（v2 勘误：装配点实名）

- **L1**（两段式，镜像现有 `RetryReporter` 桥）：`AgentTools.build` 的 L1 wrap 处建
  quota 版 bridge（与 `L1ReporterBridge` 同构、包内可见）→ `wireL1` 扩展接线 →
  `CodingAgent.submit` 内与 `activeTurnL1Sink` 同位置整体替换 quota 版 sink（闭包比对
  `activeTurnId` 过滤迟到）。**注意 L1 装配点在 `AgentTools`，不在 `CodeTuiApplication`**
  （后者仅编排调用 `AgentTools.build`）；
- **L2**：无需桥——`CodingAgent.submit` 的 L2 `backoffRetry` 调用点闭包内已有 turnId
  与 listener，quota 回调直接 `listener.onQuotaWaitScheduled(turnId, resetAt, reason)`；
- **子 agent**：`SubagentRunner` 建 `RetryingChatModel` 处（`execute()` 可从
  toolContext 取 turnId/taskId）注入闭包桥，reason 带「子任务」前缀；后台（-1）丢弃
  （§3.4）；
- 其余 provider（deepseek/anthropic/qwen）装配不变——`detect` 对它们的 429 判据天然
  不命中（无智谱业务码 + 双关键词收紧），行为零变化。

## 4. 边界与错误处理

| 场景 | 行为 |
|---|---|
| `resetAt` 在过去 / 临近（≤ now+30s） | `quotaWaitMs` 下限 30s 退避，计入限额等待次数（≤5 次后失败） |
| message 改版解析失败 | `resetAt=null` → 退化现有 429 行为（计预算、指数退避、耗尽失败） |
| 1113 欠费 / 1309 套餐过期 / 1311 权限 | 不在码集合内 → 现有行为（有限重试后失败，不等） |
| 1316/1317 用户等待中充值 | **不主动探测**（探测消耗配额）；到点重试，或 Esc 后手动重发立即走超额付费 |
| Esc 取消（主 agent） | dispose 取消 `Mono.delay` 定时器（源码核实）——现有取消链不动 |
| Esc 取消（子 agent·并行/后台） | 并行池 `shutdownNow` interrupt（现状）；后台任务走既有 kill/完成通道 |
| Esc 取消（子 agent·串行） | **本设计补齐**：cancelTurn interrupt 登记的串行执行线程，sleeper 杀循环（§3.4.3） |
| 后台子 agent 限额等待 | 无主状态行 UI（-1 事件丢弃 + 日志）；等待照常进行 |
| TUI 进程退出 | 等待随进程终止，不做持久化/重启补偿（YAGNI） |
| 7 天限额 | 照等（口径：不设上限），倒计时天级显示；用户可随时 Esc 放弃 |
| 流式 200 中途断（finish_reason 异常形态） | 无限额信息，不覆盖——现有 L2/SII 机制兜底 |

## 5. 测试计划

1. **`QuotaLimitDetector` 矩阵单测**：1316/1317/1308 文案、反引号包裹时间、ISO 带偏移、
   无秒位、过去时刻、无时间、非限额 429（1302/1305 普通限流）、WCRE 429（不识别）、
   1113/1309（不识别）、单关键词「使用上限」无双关键词「重置」（不识别）、cause 链
   包裹两层的 SDK 异常、SII 包装穿透（cause 保留 429）；
2. **`RetryPolicy` 单测**：`quotaWaitMs`（未来/过去/null）；`backoffRetry` 限额分支
   **预算扣减口径**（k 次限额等待后，普通重试耗尽判定等效于 `totalRetries−k ≥
   maxRetries`——非「从零起算」）；连续 5 次限额后终态；`resetAt=null` 退化普通
   分支；`quotaWaits==0` 时判定与现状逐字节一致；**filter 门控**（filter 拒绝的
   mid-stream 限额错误不被限额分支拦截）；quotaWaits 订阅级（同一装配两次订阅独立）；
   （`setDelayScaleForTest` 压缩长等待）；
3. **`RetryingStreamChatModel` 单测**：握手期限额等待触发 `QuotaWaitHook` 且
   `RetryReporter` 不触发；**mid-stream 限额 429（emitted>0）不在 L1 重订阅**（零下发
   不变式守护，流出为 SII）；取消（dispose）终止等待；
4. **`RetryingChatModel` 单测**：注入 sleeper 断言——限额等待吃 `quotaWaitMs`、attempt
   不递增、5 次上限、单次迭代恰一次睡眠（无双睡）、`nextDelayMs` 对齐（Retry-After
   场景回归）、interrupt 退出；
5. **`ConversationState` 单测**：deadline 设置/清除纪律（新 chunk、失败、取消路径并入
   `clearRetryState`）、-1 turnId 丢弃、格式化（分钟/小时/天级）；
6. **桥接/装配**：L1 两段式桥 turnId 过滤（迟到丢弃）；L2 闭包直连；SubagentRunner
   注入链通 + 后台 -1 丢弃；**cancelTurn interrupt 串行子 agent 执行线程**（登记/
   清理/中断杀循环）；RESUME_NOTICE 限额文案二选。

## 6. 非目标

- 不做等待期充值探测 / 到点前的提前唤醒；
- 不做跨进程限额状态持久化；
- 不覆盖 WebClient 路径（anthropic/deepseek 系 429）与非 SDK 形态的限额识别；
- 不改 L1/L2 普通重试的判据、预算与退避参数（`quotaWaits==0` 时行为与现状一致，
  本设计只加分支与扣减，不动主干参数）；
- 不做 `/quota` 之类的限额查询命令（智谱无公开查询端点，另立项目）；
- 不为限额等待新增周期 ticker/调度器（复用动画重绘帧现算）。

## v2 修订记录（2026-09-22，双 subagent 审核后）

1. **限额分支加 filter 门控**（必修）：v1 伪码限额分支先于 `filter.test`，mid-stream
   限额 429（emitted>0）会被 L1 拦截重订阅→重放已下发内容，且 L2 白名单同样被绕过；
   v2 限额分支须过 filter，只豁免耗尽判定（§3.2）。
2. **预算豁免改扣减偏移**（必修）：reactor `totalRetries` 无法阻止自增，v1「限额
   等待不占预算/计数从零起算」不可实现；v2 改 `totalRetries − quotaWaits ≥ maxRetries`
   （§3.2），↻ 行 attempt 口径同步扣减（§3.3）。
3. **串行子 agent 可 interrupt**（必修）：v1 断言「Esc 取消子 agent 语义不变」对串行
   路径为假（无池不可打断 + busy 闸门假死）；v2 补执行线程登记 + cancelTurn
   interrupt（§3.4.3、§4）。
4. **后台子 agent turnId=-1 丢弃**（勘误）：防穿透迟到过滤污染空闲界面（§3.4.4）。
5. **删 ticker 改渲染帧现算**（勘误）：RETRYING 态动画协调器已持续重绘，deadline
   现算与 `compactElapsedNanos()` 同款；不开 `.noTick()` 的倒车（§3.5）。
6. 装配点勘误：L1 在 `AgentTools.build`+`wireL1` 两段式（非 CodeTuiApplication）；L2
   闭包直连无需桥（§3.6）。`clearRetryLabel` 实名 `clearRetryState()`（§1.4/§3.5）。
7. 关键词兜底收紧为「使用上限」+「重置」双命中（§3.1）；RESUME_NOTICE 文案按失败
   类型二选（§3.5）；quotaWaits 声明位置红线与订阅级单测（§3.2）；子 agent 循环
   while 化/防双睡/限额判定先于 bail 红线（§3.4）；「约 2 分钟耗尽」改为「单层约
   1 分钟（61s）」（§0）。
