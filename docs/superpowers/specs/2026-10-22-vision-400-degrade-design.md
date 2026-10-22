# 视觉图被服务端 400 拒收后的降级重试 — 设计

日期：2026-10-22
状态：已核准（用户选定「降级重试」方案）

## 1. 目标

视觉链路（`VisionMaterializingChatModel` + `VisionMaterializer`）把当轮图片引用兑现成真
`Media` 后，若服务端以 **400 + 图片类错误文案**（本次事故：智谱
`图片输入格式/解析错误`）拒收，当前行为是**回合直接死亡**，且模型重新 `Read` 同一张图
会再次兑现同一张图 → 再次 400 → 死循环（2026-10-22 会话
`20261022T065602-b524ef` 实录：同一张截图两个回合内被拒两次，每次白传约 1.3MB base64）。

本设计实现：

1. **降级重试**：捕获图片类 400 后，自动剥掉本次兑现的图重试一次，回合不中断；
2. **如实告知**：被拒图的引用块 `delivery` 改写为 `send_failed`，模型明确知道
   「这张图发不出去」，可改走别的路（重新截图、换格式、向用户说明）；
3. **拒绝记忆**：被拒的 sha 在本 materializer 生命周期内不再兑现，杜绝死循环。

## 2. 非目标

- 不重试非图片类 400（参数错、超长等）——那类重试只会白烧一次上传；
- 不做「精确识别哪一张被拒」：整批剥掉本次兑现的图。兑现配额内通常 1–2 张，
  精确归因没有可靠信号（服务端不指明），收益不成比例；
- 不退还 VisionBudget 额度：降级重试是罕见路径，额度按两次发送计可接受（代码注释说明）;
- 不解决服务端瞬时故障本身（那是 provider 侧问题）；
- 不改 call/stream 之外的其他入口（aux/子 agent 同走本装饰器，自动获得同等保护）。

## 3. 已核准的事实依据（均实测）

### 3.1 事故现场

- 会话 `~xibaojun/.codetui/sessions/20261022T065602-b524ef.json`（24 事件）：
  - 事件 20：`mcp__chrome_devtools__take_screenshot` 返回 2600×1720 PNG 引用；
  - 事件 20 之后、事件 21（用户「继续」）之前**没有 assistant 消息**——该次 LLM 请求失败，
    即第一次 400；兑现的正是事件 20 引用的截图；
  - 事件 23：模型 `Read` 同一张图 → 下一轮请求再次兑现 → 第二次 400，回合终止。
- 两次被拒的是同一张图（sha `3dd13bf1f1b62929…`），经 `ImagePreparer` 等比缩放为
  1568×1037 PNG 后以 `data:image/png;base64,…` 发出。

### 3.2 排除对照（2026-10-22 15:12–15:26，脚本在 /tmp/vision_repro/，结果均 HTTP 200）

用 coding plan 端点（与用户实际请求同端点、同 key、同模型 glm-5.3-flash）依次验证：

| # | 变体 | 结果 |
|---|---|---|
| A | PIL 缩放的 1568 PNG，纯 user 带图 | 200 |
| B | `tool` 消息后跟带图 `user`（合成消息位置） | 200 |
| C | stream=true 带图 | 200 |
| D | Java ImageIO 同路径产出（`GenPng.java`：`ImageIO.read` → `TYPE_INT_RGB` 缩放 → `ImageIO.write("png")`） | 200 |
| E | 28 条消息 1:1 重建（会话原文）+ system + tools + stream | 200 |

同一请求体 07:01 被拒、15:16 起通过 ⇒ 判定为**服务端图片解析环节当时拒绝**
（瞬时故障或服务端变更），图片字节、序列化格式、消息结构均无问题。

注意：`ZHIPU_API_KEY` 打 `…/api/paas/v4` 返回 429/1113（无余额），同一 key 打
`ZHIPU_BASE_URL`（`…/api/coding/paas/v4`）正常——用户环境走 Coding Plan 池。

### 3.3 代码事实

- `VisionMaterializer.materialize` 已有 **零变化返回同一对象** 的约定
  （`visionCapable=false` / 什么都没兑现时原样返回），装饰器可用 `==` 判断「是否兑现过图」。
- 高水位线：`doMaterialize` 在兑现前执行 `highWaterIndex = msgs.size()`——**400 后简单重跑
  `materialize(原 prompt)` 什么都收不到**（mark == size ⇒ 收集范围为空）。降级重试必须
  先回退高水位线，或在产物上剥图。本设计选回退（见 §4.2）。
- `com.openai.errors.BadRequestException`（openai-java-core 4.49.0，spring-ai-openai 2.0.1
  的 compile 依赖）暴露 `statusCode()`；测试可经
  `BadRequestException.builder().error(ErrorObject.builder().message(…).build()).build()` 构造。
- `FileReference` 五态 delivery：delivered / reference_only / not_in_view /
  budget_exceeded / turn_budget_exhausted。本设计新增第六态 `send_failed`。
- `RetryingStreamChatModel`（L1）只重试零下发瞬态失败，400 被显式放行——降级重试
  放在 Vision 层（L1 内层）不会与 L1 计数交互：400 到达 Vision 时 RetryStream 已放弃该流。

## 4. 设计

### 4.1 错误分类：什么算「图片被拒」

```
BadRequestException（或其 statusCode()==400 的子类）
  且 getMessage() 含「图片」或「image」（忽略大小写）
```

宁可窄不可宽：误判方向不对称——漏判只是回到现状（回合死），误判会把无关 400
当成图片问题白重试一次并把 delivery 写成假话。

### 4.2 降级路径（复用现有改写管线，不新造机制）

`VisionMaterializer` 新增：

- `Set<String> rejectedShas`（`ConcurrentHashMap.newKeySet()`，跨回合保留）；
- `volatile Set<String> lastDeliveredShas`（每次兑现时记录本次真发出的 sha）；
- `volatile int highWaterBeforeMaterialize`（每次 `doMaterialize` 开头记录当时的 mark）;
- `admitAll` 里最高优先级判 `rejectedShas.contains(r.sha())` → 不 prepare、
  delivery 记 `send_failed`、不占张数名额；
- 新方法 `rejectLastDelivered()`：把 `lastDeliveredShas` 并入 `rejectedShas`，
  并把 `highWaterIndex` 回退为 `highWaterBeforeMaterialize`。

`VisionMaterializingChatModel` 改造 `call`/`stream`：

```java
Prompt first = materialize(prompt);
if (first == prompt) return delegate.stream(first);          // 没兑现过图：无从降级
return delegate.stream(first).onErrorResume(e -> {
    if (!isImageRejected(e)) return Flux.error(e);
    materializer.rejectLastDelivered();
    return delegate.stream(materialize(prompt));             // 重跑：被拒图被 send_failed 挡下
});
```

选择「回退高水位线后重跑 `materialize(原 prompt)`」而非「在物化产物上剥图」的理由：
delivery 改写、合成消息组装、预算标注全部复用现有管线，不出现第二种改写路径
（产物上剥图需要再次手改文本，两条路径迟早漂移）。代价与对策：

- **额度双扣**：重跑会重新过闸，非被拒图可能再次消耗回合额度。罕见路径，接受；
- **高水位线回退的并发干扰**：主/子 agent 并发共用同 provider 的 materializer 时，
  回退可能影响对方。该风险在现有 highWater 设计中已存在（volatile 非原子），
  且回合内串行假设已有文档约束（见 `VisionMaterializer` 字段注释），本设计不新增缓解。

### 4.3 只重试一次的结构保证

第二次 `materialize` 产出的 prompt 中被拒图全部被 `send_failed` 挡下、无新兑现 ⇒
产物与原 prompt 相同或不含 media；其 Flux **不挂 onErrorResume**，再次 400 直接放行。
结构上即「最多一次降级」，无需计数器。

### 4.4 delivery 第六态

```java
/** 服务端拒收（400 图片类错误）——这张图重发也会失败，本回合别再试图看它。 */
public static final String DELIVERY_SEND_FAILED = "send_failed";
```

reason 行沿用既有句式：`server rejected the image upload (400); retrying the same
image will fail again`。改写只作用于出站副本（与现有五态同一纪律）。

### 4.5 测试策略（先红后绿）

- `VisionMaterializerTest`：rejectLastDelivered 后同 sha 不再兑现且 delivery=send_failed；
  未被拒的其他图不受影响；跨回合仍生效。
- `VisionMaterializingChatModelTest`（假 delegate 注入 `BadRequestException`）：
  图片 400 → 二次调用且第二次 prompt 无 media、引用文本含 send_failed，下游拿到成功结果；
  非图片 400 / 非 400 / 无兑现 → 不重试；两次 400 → 第二次传播。
  构造：`BadRequestException.builder().error(ErrorObject.builder()
  .message("图片输入格式/解析错误").build()).build()`。

## 5. 被淘汰的备选

- **产物上剥图重试**：需要第二种 delivery 改写路径（delivered→send_failed 手改文本），
  与 §4.2 的「单一改写管线」冲突，否决；
- **精确 sha 级归因**：服务端错误不指明哪张图，无信号来源，否决（见 §2）；
- **在 RetryingStreamChatModel 层做**：那层看不到「哪些图是这次兑现的」，且会与
  L1 零下发计数纠缠，否决；
- **只记不住不重试（轻量版）**：回合仍死一次，用户手动重试体验差，用户已选降级重试。
