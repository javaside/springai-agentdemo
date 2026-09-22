# 智谱 Coding Plan 限额感知重试（等到重置时刻自动续跑）设计

日期：2026-09-22
状态：**终稿 v1**（brainstorming 已确认口径：回合挂起等待 / 不设等待上限 / 倒计时刷新 /
覆盖主 agent 与子 agent 全部路径；待 writing-plans）
参考：智谱 BigModel 错误码文档 `https://docs.bigmodel.cn/cn/api/api-code`
关联：`docs/superpowers/specs/2026-09-03-main-agent-stream-retry-design.md`（L1/L2 重试架构，
本设计在其上扩展）；`docs/superpowers/specs/2026-08-18-subagent-retry-transient-expansion-design.md`

## 0. 摘要

智谱 GLM Coding Plan 有**滚动 5 小时**与**滚动 7 天**两套使用限额。达到限额后 API 返回
HTTP 429（业务码 1308/1310/1316/1317/1318-1321），message 内嵌重置时刻（`next_flush_time`
占位符的实际值，如「您的限额将在 `2026-09-22 15:30:00` 重置」）。

现状：429 已被 `RetryPolicy.shouldRetry` 判为瞬态，会走 L1/L2/子 agent 的指数退避
（封顶 30s，Retry-After 封顶 60s）——但对「几小时后重置」的限额窗口，约 2 分钟即耗尽
预算，回合以失败告终，用户必须手动重发。

本设计：**识别智谱限额错误 → 解析重置时刻 → 回合挂起、UI 显示倒计时，睡到重置时刻
自动重试**。三条重试路径（L1 流式透明重试 / L2 回合级续跑 / 子 agent 阻塞重试）全部
覆盖；限额等待**不占普通重试预算**，由独立计数兜底；Esc 随时可取消等待。

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
- `getMessage()` == `"429: " + error.message` —— 完整中文 message（含重置时刻）可读。

Spring `WebClientResponseException`（WCRE）路径**不识别**为智谱限额：走 WCRE 的
provider（anthropic/deepseek 系）不是智谱，判据保持 provider 中立。

### 1.3 现有重试架构（三层，判据/退避唯一真相源 `RetryPolicy`）

- **L1** `RetryingStreamChatModel`：主 agent 流式零下发透明重试，`L1_RETRIES=6`
  （总尝试 7），reactive `retryWhen(RetryPolicy.backoffRetry(...))`；
- **L2** `CodingAgent` 回合级续跑：`L2_RESUMES=4`，同 `backoffRetry`，白名单
  `StreamInterruptedException`；
- **子 agent** `RetryingChatModel`：阻塞 for 循环，`MAX_ATTEMPTS=7`，`sleeper` 可注入。

**reactor 计数事实（3.8.7 源码核实）**：`RetrySignal.totalRetries()` 由框架自数
（errors − 1），companion 发出的值只是「触发重试」的载体，**无法重置计数**——
「限额重试不占预算」只能通过**跳过耗尽判定**实现（§3.2）。

### 1.4 现有 UI 机制

`AgentListener.onRetryScheduled(turnId, attempt, maxAttempts, backoffMs, reason)`
（default 空）→ `ConversationState.onRetryScheduled`：INFO 输出行 `↻ 重试中 (2/7·传输)：
...，30s 后重发` + `status=RETRYING` + `retryLabel/retryBackoffText`（volatile，
离开重试态必须 `clearRetryLabel()`）。桥接纪律：L1 经 `RetryReporter` → CodingAgent
`activeTurnL1Sink` 闭包比对 `activeTurnId` 过滤迟到事件。

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
    /** 沿 cause 链找 OpenAIServiceException：429 且 (code ∈ 限额码集合 或 message 含「使用上限」)。 */
    public static Optional<QuotaLimit> detect(Throwable ex);
    /** 从 message 文本解析重置时刻；解析不出返回 QuotaLimit(code, null)。 */
    static Instant parseResetAt(String message);
}
```

- **限额码集合**：`{"1308","1310","1316","1317","1318","1319","1320","1321"}`（字符串
  比对，`code()` 即业务码字符串）。`code()` 为空时以 message 含「使用上限」兜底
  （1316-1321 文案均含该词，防网关改文案结构）。
- **时间解析**（对 message 全文做，不依赖措辞）：
  - `yyyy-MM-dd HH:mm[:ss]`（可能被反引号/引号包裹）→ 按 **Asia/Shanghai** 解析
    （bigmodel.cn 国内站）；带 `T` 分隔的同格式同法；
  - ISO-8601 带偏移/`Z` → 按自带偏移解析；
  - 均失败 → `resetAt = null`。
- **过去/临近时刻**不在此处过滤（detect 保持纯描述）；由 `quotaWaitMs` 统一兜底（§3.2）。
- 沿 cause 链逐层找 `OpenAIServiceException`（Spring AI 可能包一层 RuntimeException，
  与 `shouldRetry`/`retryAfterMs` 同法）。

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
Retry.from(companion -> companion.concatMap(sig -> {
    Throwable failure = sig.failure();
    Optional<QuotaLimit> quota = QuotaLimitDetector.detect(failure);   // 本分支先于普通判定
    if (quota.isPresent() && quota.get().resetAt() != null) {
        if (quotaWaits.incrementAndGet() > MAX_QUOTA_WAITS)            // 订阅级独立计数
            return Mono.error(failure);                                // 限额兜底终态
        long waitMs = quotaWaitMs(quota.get());
        fireQuotaHook(waitMs, quota.get().resetAt(), failure);         // 新回调（§3.3）
        return Mono.delay(scaled(waitMs)).thenReturn(sig.totalRetries());
    }
    ... 现有逻辑原样 ...                                                // 普通重试照旧（含耗尽判定）
}))
```

- **`quotaWaits` 为订阅级状态**：`Retry.from` 的 companion Flux 每次订阅重建，在
  `generateCompanion` 等价 lambda 内建局部 `AtomicInteger`——与 L1「单回合串行」前提
  一致，无并发问题；
- **限额等待不检查 `totalRetries() >= maxRetries`**（预算豁免；reactor 计数不可重置，
  见 §1.3），普通重试耗尽判定原样保留；
- **限额错误同时满足 `shouldRetry`**（429 判据不变）——若 `resetAt` 解析失败
  （`quota.isPresent() && resetAt == null`），走普通分支：计入预算、按现有指数退避
  重试，耗尽后失败。**这是「文案改版/解析失灵」的安全网**：行为退回现状，无新增风险；
- 长等待经既有测试钩子 `delayMsForTest` 压缩（`Mono.delay` 实际睡眠与上报值分离的
  纪律不变）；
- 取消语义：Esc dispose → `Mono.delay` 定时器随订阅取消，整链立即终止，无复活。

**L1 与 L2 的限额计数相互独立**（各自一次 `backoffRetry` 装配）——合计最多 10 次限额
等待。429 发生在握手期（`emitted==0`），L1 限额等待耗尽后 429 原样放行（不满足 SII 的
`emitted>0` 条件），不进 L2；mid-stream 限额断流（SII 形态）则由 L2 接手等待。

### 3.3 L1（`RetryingStreamChatModel`）与事件通道

- `stream()` 的 `retryWhen` 不变（`backoffRetry` 内部已分流限额）；
- **回调接口扩展**：`RetryPolicy.RetryHook` 保持不动；`RetryingStreamChatModel` /
  装配层新增独立的限额等待钩子（`RetryPolicy.backoffRetry` 增加重载，接收可选
  `QuotaWaitHook`）：

```java
@FunctionalInterface
public interface QuotaWaitHook {
    /** 限额等待已排定。waitMs = 实际等待毫秒；resetAtEpochMs = 解析出的重置时刻（显示用）。 */
    void onQuotaWait(long waitMs, long resetAtEpochMs, String reason);
}
```

- 限额等待场景**不再触发** `onRetryScheduled`（`↻ 重试中` 与 `⏳ 限额等待` 两个状态行
  互斥，防 UI 打架）；reason 复用 `reasonOf`（截 60）。

### 3.4 子 agent 阻塞路径（`RetryingChatModel`）

`call()` 循环改造：

1. **退避对齐**：`sleeper.accept(backoffMsAfter(attempt - 1))` →
   `sleeper.accept(RetryPolicy.nextDelayMs(attempt - 1, last))`——顺手修复它现在
   **没有吃 Retry-After** 的既有偏差（普通 429/529 场景同样受益）；
2. **限额分支**：捕获异常后先 `detect`——命中且 `resetAt != null`：`quotaWaits`
   （循环局部 `AtomicInteger`，`MAX_QUOTA_WAITS=5`）未超限则
   `sleeper.accept(quotaWaitMs(quota))` 且 **attempt 不递增**（预算豁免），超限或
   `shouldRetry` 否决时按现状抛出；命中但 `resetAt == null` → 走现有
   `shouldRetry`/attempt 分支（退化）；
3. **中断退出**：`Thread.sleep` 长等待被 interrupt → 现有 sleeper 实现抛
   `RuntimeException(InterruptedException)`，直接杀循环（Esc 取消子 agent 语义不变）；
4. **可选 UI 桥**：`wrap` 增加重载注入可选 `QuotaWaitHook`，装配层（`SubagentRunner`）
   桥到 `AgentListener.onQuotaWaitScheduled`，文案带「子任务」前缀区分；null 时 no-op
   （既有测试不受影响）。

### 3.5 UI：限额等待倒计时

**`AgentListener` 新增 default 方法**（迟到过滤纪律与 `onRetryScheduled` 同）：

```java
default void onQuotaWaitScheduled(long turnId, long resetAtEpochMs, String reason) { }
```

**`ConversationState`**：

- 新字段：`volatile Long quotaWaitDeadline`（epoch ms，null = 非限额等待）、
  `volatile String quotaWaitReason`；
- `onQuotaWaitScheduled`：turnId 过滤 → INFO 行 `⏳ 限额等待：将于 15:30:00 重试（Esc 取消）`
  + `status = RETRYING`（复用，不新增 Status 枚举值）+ `retryLabel = "⏳ 限额等待"` +
  记录 deadline → 启动 ticker（见下）；
- **ticker**：类内单例 daemon `ScheduledExecutorService`（1s 周期；惰性创建，进程唯一）。
  tick 时 `quotaWaitDeadline != null` 才 `publish(VIEW)`——刷新由渲染层从 deadline
  现算剩余时间（`剩 2h13m` / 天级 `剩 6d23h`；复用/扩展 `formatBackoff`）。非限额态
  tick 为空转（一次 volatile 读），开销可忽略；
- **清除纪律**：任何离开等待的事件（新一轮 `onRetryScheduled`/`onQuotaWaitScheduled`、
  首个新 chunk、回合完成/失败/取消）必须 `quotaWaitDeadline = null`——与
  `clearRetryLabel()` 同位置同纪律（该方法的契约注释同步扩展）；
- 渲染（`CodeTuiView`/状态栏消费 `retryLabel` 处）：deadline 非空时显示
  `⏳ 限额等待 剩 2h13m（15:30:00）`，复用现有 RETRYING 渲染位，不新增渲染区。

**等待期间用户新输入**：走现有插话/排队机制（InterjectingChatModel），限额等待在
重试链内挂起，与等待普通退避无区别——不受影响，不新增处理。

### 3.6 装配与桥接

- `CodeTuiApplication`：L1 装配点把 `QuotaWaitHook` 桥到 `listener.onQuotaWaitScheduled`
  （桥侧闭包比对 turnId，与 `activeTurnL1Sink` 同法同位置）；
- `SubagentRunner`：建子 agent `RetryingChatModel` 时注入桥（带「子任务」标记的 reason）；
- 其余 provider（deepseek/anthropic/qwen）装配不变——`detect` 对它们的 429 判据天然
  不命中（无智谱业务码/中文文案），行为零变化。

## 4. 边界与错误处理

| 场景 | 行为 |
|---|---|
| `resetAt` 在过去 / 临近（≤ now+30s） | `quotaWaitMs` 下限 30s 退避，计入限额等待次数（≤5 次后失败） |
| message 改版解析失败 | `resetAt=null` → 退化现有 429 行为（计预算、指数退避、耗尽失败） |
| 1113 欠费 / 1309 套餐过期 / 1311 权限 | 不在码集合内 → 现有行为（有限重试后失败，不等） |
| 1316/1317 用户等待中充值 | **不主动探测**（探测消耗配额）；到点重试，或 Esc 后手动重发立即走超额付费 |
| Esc 取消 | reactive：dispose 取消 `Mono.delay`；阻塞：interrupt 杀 sleeper——现有取消链不动 |
| TUI 进程退出 | 等待随进程终止，不做持久化/重启补偿（YAGNI） |
| 7 天限额 | 照等（口径：不设上限），倒计时天级显示；用户可随时 Esc 放弃 |
| 流式 200 中途断（finish_reason 异常形态） | 无限额信息，不覆盖——现有 L2/SII 机制兜底 |

## 5. 测试计划

1. **`QuotaLimitDetector` 矩阵单测**：1316/1317/1308 文案、反引号包裹时间、ISO 带偏移、
   无秒位、过去时刻、无时间、非限额 429（1302/1305 普通限流）、WCRE 429（不识别）、
   1113/1309（不识别）、cause 链包裹两层的 SDK 异常；
2. **`RetryPolicy` 单测**：`quotaWaitMs`（未来/过去/null）；`backoffRetry` 限额分支
   不占预算（限额后普通重试计数从零起算）、连续 5 次限额后终态、`resetAt=null` 退化
   普通分支、普通耗尽判定不受限额分支影响（`setDelayScaleForTest` 压缩长等待）；
3. **`RetryingStreamChatModel` 单测**：限额等待期 `QuotaWaitHook` 触发且
   `RetryReporter` 不触发；取消（dispose）终止等待；
4. **`RetryingChatModel` 单测**：注入 sleeper 断言——限额等待吃 `quotaWaitMs`、attempt
   不递增、5 次上限、`nextDelayMs` 对齐（Retry-After 场景回归）、interrupt 退出；
5. **`ConversationState` 单测**：deadline 设置/清除纪律（新 chunk、失败、取消路径）、
   tick 仅限额态 publish、格式化（分钟/小时/天级）；
6. **桥接**：CodingAgent 桥 turnId 过滤（迟到丢弃）、SubagentRunner 注入链通。

## 6. 非目标

- 不做等待期充值探测 / 到点前的提前唤醒；
- 不做跨进程限额状态持久化；
- 不覆盖 WebClient 路径（anthropic/deepseek 系 429）与非 SDK 形态的限额识别；
- 不改 L1/L2 普通重试的判据、预算与退避参数（本设计只加分支，不动主干）；
- 不做 `/quota` 之类的限额查询命令（智谱无公开查询端点，另立项目）。
