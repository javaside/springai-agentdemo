# 视觉图 400 降级重试 — 实施计划

设计：[2026-10-22-vision-400-degrade-design.md](../specs/2026-10-22-vision-400-degrade-design.md)
分支：`feature/vision-400-degrade`（`--no-ff` 合并回 main）

每个任务固定节奏：**先写失败测试（红）→ 最小实现（绿）→ 提交（功能与测试同一提交）**。

## Task 1：FileReference 新增 DELIVERY_SEND_FAILED + Materializer 拒绝记忆

红（`VisionMaterializerTest` 新增 3 用例）：

1. `rejectedImageIsSkippedWithSendFailedDelivery`：正常兑现一次 →
   `materializer.rejectLastDelivered()` → 同一 prompt 再 materialize →
   出站无 Media、锚点文本该引用块 `delivery: send_failed`。
2. `rejectionDoesNotAffectOtherImages`：两张图只兑现/拒绝其中一张（先删另一张的兑现——
   用两次独立 prompt 分别兑现；或构造两张图、拒其一）。断言另一张照常兑现。
   （实现口径：两张图都兑现后 rejectLastDelivered 会拒两张；本用例改为
   「拒 A 后 materialize 含 A+B 的 prompt，B 照常出图、A 出 send_failed」。）
3. `rejectionSurvivesAcrossTurns`：reject 后换新回合（新 user 锚点带同一引用文本）→
   仍不兑现、delivery=send_failed。

绿（最小实现）：

- `FileReference` 加 `DELIVERY_SEND_FAILED = "send_failed"`；
- `VisionMaterializer`：
  - `rejectedShas`（newKeySet）、`lastDeliveredShas`（volatile）、
    `highWaterBeforeMaterialize`（volatile，`doMaterialize` 开头记录 mark）；
  - `admitAll` 首位判 `rejectedShas` → `Outcome(null, DELIVERY_SEND_FAILED, 0)`，
    不 prepare、不占名额（放在「张数已满」判定之前）；
  - `doMaterialize` 末尾把真兑现的 sha 写进 `lastDeliveredShas`
    （注意：短路返回（什么都没兑现）时也应把 `lastDeliveredShas` 置空集——
    否则 rejectLastDelivered 会误伤上一次的记录）；
  - `rejectLastDelivered()`：并入 rejectedShas、`highWaterIndex = highWaterBeforeMaterialize`。

提交：`feat(code-tui): 视觉物化器记住被服务端拒收的图并新增 send_failed 状态`

## Task 2：装饰器捕获图片 400 剥图重试

红（`VisionMaterializingChatModelTest` 新增 5 用例，假 delegate 可编程抛异常）：

1. `imageRejected400DegradesToOneRetryWithoutMedia`（stream）：第一次抛
   BadRequestException(400, "图片输入格式/解析错误")，第二次成功 →
   delegate 被调 2 次；第二次 prompt 无 Media 且引用文本含 `delivery: send_failed`；
   下游拿到第二次结果。
2. `call` 路径同上（子 agent 也受保护）。
3. `nonImage400NotRetried`：message="参数非法" → 异常传播、只调 1 次。
4. `noMaterializedImagesNoRetry`：纯文本模型（无兑现）+ 图片 400 → 传播、只调 1 次。
5. `secondImageFailurePropagates`：两次图片 400 → 第二次异常传播（重试恰一次）。

绿（最小实现）：

- `VisionMaterializingChatModel`：
  - 抽 `private Prompt materializeOnce(Prompt)`（现有逻辑）；
  - `stream`：`first == prompt` 时直通；否则 `onErrorResume` 挂一次性降级
    （§4.2 代码）。`call` 同逻辑 try/catch 版；
  - `isImageRejected(Throwable)`：400 + message 含「图片」/「image」（大小写不敏感）；
    需要防 null message。

提交：`feat(code-tui): 图片被服务端 400 拒收时自动剥图降级重试一次`

## Task 3：全量回归 + 文档

- `mvn -pl springai-code-tui -am test` 全绿；
- `implementation-map.md` 的视觉条目补一行降级重试入口；
- CHANGELOG 是否加行：**否**——按发布流程，发版说明在 release 时统一写
  （本次是未发版的修复，先落分支）。

提交：`docs(code-tui): 实现地图补视觉 400 降级重试入口`

## 验证命令（可直接复制）

```bash
# 单类（红/绿验证）
mvn -pl springai-code-tui -am test -Dtest='VisionMaterializerTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl springai-code-tui -am test -Dtest='VisionMaterializingChatModelTest' -Dsurefire.failIfNoSpecifiedTests=false

# 全量
mvn -pl springai-code-tui -am test
```

## 风险与回退

- 行为开关：无（硬编码开启）。回退 = revert 两个提交；
- 对既有用例的影响面：`VisionPushOnceTest` / `VisionTurnBudgetTest` 断言兑现行为，
  降级路径只在 400 时触发，常规路径零变化；
- `rejectedShas` 永不清理：量级为「被服务端拒过的图」，实践中每次事故 ≤2 张，可接受。
