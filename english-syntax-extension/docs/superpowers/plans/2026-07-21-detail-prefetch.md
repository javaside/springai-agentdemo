# 成分详解预加载(按句合批)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 选项页开关「预载成分详解」(默认关)开启后,每句核心分析就绪即以最低优先级**一句一次请求**生成该句全部缺失成分的详解并写入现有缓存;进度经 pill/popup 可见;存储与导出格式零改动。

**Architecture:** content 侧新模块 `DetailPrefetcher` 在句子 `ready` 时按句入队、有界并发发送新消息 `PREFETCH_SENTENCE_DETAILS`;SW 路由到 `CachedAnalysisService.analyzeSentenceDetails`——逐成分查缓存、只把缺失成分放进一次整句 prompt(新 `SENTENCE_DETAILS_SCHEMA`),逐项校验、失败子集一次 repair,合格项逐个写入**现有 detail 缓存键**;调度器仅新增最低优先级档 `prefetch-detail`。开关经 `ConfigRepository`(`prefetchDetail.v1`)存储,由 SW 在 START_SESSION 页面命令上快照下发。

**Tech Stack:** TypeScript + Chrome MV3、IndexedDB、Vitest(fake-indexeddb / happy-dom)、Playwright E2E。

**Spec:** `docs/superpowers/specs/2026-07-21-detail-prefetch-design.md`

**约束(全任务生效):**

- 工作目录 `english-syntax-extension/`,分支 `codex/english-syntax-extension-next`,**不合并主干**。
- 门禁模块内跑:`npm test`、`npm run lint`(基线恰好 1 个错误:`src/options/options.test.ts` 的 no-unnecessary-type-assertion,**不要修它、不要新增**)、`npm run format:check`、`npm run build`、`npx playwright test`。
- 提交信息中文主题 + 尾行 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- 真机验收脚本放 `.superpowers/acceptance/`(已 gitignore,不提交),API key 只从环境变量读、日志脱敏。
- 大测试文件(session-controller/service-worker/popup/options 的 \*.test.ts 各 700-900+ 行)沿用文件内既有 fixture/stub 基建,不要重建;下文给出的是要点断言,具体工厂名以文件现状为准。

**文件结构总览:**

| 文件                                               | 动作  | 职责                                                               |
| -------------------------------------------------- | ----- | ------------------------------------------------------------------ |
| `src/background/config-repository.ts`              | 改    | `prefetchDetail.v1` 读写                                           |
| `src/shared/protocol.ts`                           | 改    | START_SESSION 加 `prefetchDetail?`、新消息对、SessionStatus 3 字段 |
| `src/background/request-scheduler.ts`              | 改    | 优先级档 `prefetch-detail: 4`                                      |
| `src/background/prompts.ts`                        | 改    | `buildSentenceDetailsPrompt`                                       |
| `src/background/analysis-service.ts`               | 改    | `analyzeSentenceDetails`(查缓存→整句请求→逐项校验→repair→写回)     |
| `src/background/service-worker.ts`                 | 改    | 新路由、START_SESSION 下发快照、`isStatus` 新字段、懒代理          |
| `src/content/detail-prefetcher.ts`                 | 建    | 按句队列、有界并发、暂停/恢复/丢弃、计数                           |
| `src/content/session-controller.ts`                | 改    | start 接收开关、ready 喂入、status 计数、生命周期联动              |
| `src/content/content-script.ts`                    | 改    | router 传开关、`isSessionStatus` 新字段                            |
| `src/content/progress-pill.ts`                     | 改    | 「详解预载中 X/Y」与完成(含失败数)文案                             |
| `src/popup/popup.ts`                               | 改    | 副线「详解预载中 X/Y」                                             |
| `src/options/options.ts`                           | 改    | 「预载成分详解」checkbox                                           |
| `tests/support/fake-openai-server.ts`              | 改    | 新 kind `sentence-details`(+repair)与 auto 响应                    |
| 各对应 `*.test.ts` + `tests/e2e/extension.spec.ts` | 改/建 | 单测与 E2E                                                         |

---

### Task 1: ConfigRepository 开关存储

**Files:**

- Modify: `src/background/config-repository.ts`
- Test: `src/background/config-repository.test.ts`

- [ ] **Step 1: Write the failing tests**

在 `config-repository.test.ts` 既有 describe 内追加(沿用文件里现成的内存 StorageArea stub):

```ts
describe("prefetch detail flag", () => {
  it("defaults to false and round-trips true", async () => {
    const repository = new ConfigRepository(storage);
    expect(await repository.getPrefetchDetail()).toBe(false);

    await repository.setPrefetchDetail(true);
    expect(await repository.getPrefetchDetail()).toBe(true);

    await repository.setPrefetchDetail(false);
    expect(await repository.getPrefetchDetail()).toBe(false);
  });

  it("treats a corrupted stored value as false", async () => {
    await storage.set({ "prefetchDetail.v1": "yes" });
    const repository = new ConfigRepository(storage);
    expect(await repository.getPrefetchDetail()).toBe(false);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/background/config-repository.test.ts`
Expected: FAIL,`getPrefetchDetail is not a function`。

- [ ] **Step 3: Implement**

`config-repository.ts` 常量区(`CACHE_LIMIT_MB_KEY` 旁)加:

```ts
const PREFETCH_DETAIL_KEY = "prefetchDetail.v1";
```

`ConfigRepository` 类内 `setCacheLimitMb` 之后加:

```ts
  /** 「预载成分详解」全局开关;非 true 的任何存量值一律按 false。 */
  async getPrefetchDetail(): Promise<boolean> {
    return (await this.storage.get(PREFETCH_DETAIL_KEY))[PREFETCH_DETAIL_KEY] === true;
  }

  async setPrefetchDetail(enabled: boolean): Promise<void> {
    await this.storage.set({ [PREFETCH_DETAIL_KEY]: enabled === true });
  }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/background/config-repository.test.ts`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/background/config-repository.ts src/background/config-repository.test.ts
git commit -m "feat: 配置仓库新增预载成分详解开关" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 协议扩展

**Files:**

- Modify: `src/shared/protocol.ts`
- Test: `src/shared/protocol.test.ts`

- [ ] **Step 1: Write the failing tests**

`protocol.test.ts` 追加(沿用文件内现成的合法消息工厂;`validSentence()`/`validCore()` 一类构造器按文件现状取用):

```ts
describe("prefetch protocol", () => {
  it("accepts START_SESSION with and without the prefetchDetail flag", () => {
    const base = {
      version: MESSAGE_VERSION,
      requestId: "r1",
      type: "START_SESSION",
      tabId: 1,
      documentId: "doc",
    };
    expect(isRequestMessage(base)).toBe(true);
    expect(isRequestMessage({ ...base, prefetchDetail: true })).toBe(true);
    expect(isRequestMessage({ ...base, prefetchDetail: false })).toBe(false);
  });

  it("accepts PREFETCH_SENTENCE_DETAILS with sentence and core only", () => {
    const message = {
      version: MESSAGE_VERSION,
      requestId: "r2",
      type: "PREFETCH_SENTENCE_DETAILS",
      tabId: 1,
      documentId: "doc",
      sentence: validSentence(),
      core: validCore(),
    };
    expect(isRequestMessage(message)).toBe(true);
    expect(isRequestMessage({ ...message, focus: { startToken: 0, endToken: 0 } })).toBe(false);
  });

  it("keeps isSessionComplete independent of detail counters", () => {
    const status = {
      state: "running",
      discovered: 2,
      queued: 0,
      ready: 2,
      failed: 0,
      detailTotal: 6,
      detailReady: 1,
      detailFailed: 0,
    } as const;
    expect(isSessionComplete(status)).toBe(true);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/shared/protocol.test.ts`
Expected: FAIL(flag 被 hasOnlyKeys 拒绝、新消息类型未知)。

- [ ] **Step 3: Implement**

`protocol.ts` 四处:

(a) `RequestMessage` 里把 START_SESSION 一行替换、并在 ANALYZE_DETAIL 之后加新消息:

```ts
  | (PageRequestBase & { type: "START_SESSION"; prefetchDetail?: true })
```

```ts
  | (PageRequestBase & {
      type: "PREFETCH_SENTENCE_DETAILS";
      sentence: SentenceInput;
      core: CoreAnalysis;
    })
```

(b) `SessionStatus` 在 `skipped?` 之后加:

```ts
  /** 详解预载:已就绪句子的成分总数(仅预载开启的会话出现)。 */
  detailTotal?: number;
  /** 详解预载:已确认入缓存的成分数(含预载前已命中缓存的)。 */
  detailReady?: number;
  /** 详解预载:repair 后仍失败的成分数。 */
  detailFailed?: number;
```

(c) `ResponseMessage` 在 DETAIL_RESULT 之后加:

```ts
  | (MessageBase & { type: "SENTENCE_DETAILS_RESULT"; succeeded: number; failed: number })
```

(d) `isRequestMessage`:把 `case "START_SESSION":` 从共享分组里拆出来(分组保留 PAUSE/STOP/GET/REANALYZE_VISIBLE/PARSE_CONTEXT_BLOCK),新增:

```ts
    case "START_SESSION":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "prefetchDetail"]) &&
        hasPageContext(value) &&
        (value.prefetchDetail === undefined || value.prefetchDetail === true)
      );
    case "PREFETCH_SENTENCE_DETAILS":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "sentence", "core"]) &&
        hasPageContext(value) &&
        isSentenceInput(value.sentence) &&
        isCoreAnalysis(value.core)
      );
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/shared/`
Expected: PASS(含既有用例)。

- [ ] **Step 5: Commit**

```bash
git add src/shared/protocol.ts src/shared/protocol.test.ts
git commit -m "feat: 协议支持详解预载消息对与会话详解计数字段" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 调度器最低优先级档

**Files:**

- Modify: `src/background/request-scheduler.ts`
- Test: `src/background/request-scheduler.test.ts`

- [ ] **Step 1: Write the failing test**

`request-scheduler.test.ts` 追加(沿用文件内现成的 scheduler 构造与驱动方式;要点是 fetchTask 记录执行顺序):

```ts
it("runs prefetch-detail requests after every other priority", async () => {
  const order: string[] = [];
  const scheduler = new RequestScheduler<string, string>({
    concurrency: 1,
    fetchTask: async (batch) => {
      order.push(...batch.map(({ input }) => input));
      return batch.map(({ input }) => input);
    },
  });
  const request = (cacheKey: string, priority: SchedulerPriority) =>
    scheduler.schedule({
      cacheKey,
      documentId: "doc",
      priority,
      sentenceCount: 1,
      input: cacheKey,
    });

  await Promise.all([
    request("p-detail", "prefetch-detail"),
    request("p-core", "prefetch-core"),
    request("click", "detail-click"),
  ]);
  expect(order.indexOf("p-detail")).toBeGreaterThan(order.indexOf("p-core"));
  expect(order.indexOf("p-core")).toBeGreaterThan(order.indexOf("click"));
});
```

注意:该文件若有"入队后统一放行"的驱动辅助(fake sleep / 手动 tick),沿用之,确保三个请求先全部入队再开跑,否则顺序断言无意义。

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run src/background/request-scheduler.test.ts`
Expected: FAIL,TS 报 `"prefetch-detail"` 不在 `SchedulerPriority` 联合内(类型错也算失败信号)。

- [ ] **Step 3: Implement**

`request-scheduler.ts`:

```ts
export type SchedulerPriority =
  "user-retry" | "detail-click" | "visible-core" | "prefetch-core" | "prefetch-detail";
```

`PRIORITY_RANK` 加一行:

```ts
  "prefetch-detail": 4,
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/background/request-scheduler.test.ts`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/background/request-scheduler.ts src/background/request-scheduler.test.ts
git commit -m "feat: 调度器新增详解预载最低优先级档" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 整句详解 prompt

**Files:**

- Modify: `src/background/prompts.ts`
- Test: `src/background/prompts.test.ts`

- [ ] **Step 1: Write the failing tests**

`prompts.test.ts` 追加(sentence/core 构造沿用文件内既有工厂):

```ts
describe("buildSentenceDetailsPrompt", () => {
  it("lists only the requested focus ranges and ends with them", () => {
    const prompt = buildSentenceDetailsPrompt(sentence, core, [
      { startToken: 0, endToken: 1 },
      { startToken: 3, endToken: 4 },
    ]);
    expect(prompt.startsWith("Explain each requested grammatical component")).toBe(true);
    expect(prompt).toContain('"details"');
    const focusSection = prompt.split("Requested focus ranges:")[1]!;
    expect(JSON.parse(focusSection.trim())).toEqual([
      { startToken: 0, endToken: 1 },
      { startToken: 3, endToken: 4 },
    ]);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/background/prompts.test.ts`
Expected: FAIL,函数不存在。

- [ ] **Step 3: Implement**

`prompts.ts` 在 `DETAIL_OUTPUT_SHAPE` 之后加(中文角色要求与 `DETAIL_OUTPUT_SHAPE` 保持同一措辞):

```ts
const SENTENCE_DETAILS_OUTPUT_SHAPE = [
  "Output exactly one JSON object of this shape:",
  '{"details": [{"sentenceId": string, "focus": {"startToken": number, "endToken": number}, "structures": [{"startToken": number, "endToken": number, "role": string, "explanation": string, "translation": string}], "grammarPoints": [string], "explanation": string}]}',
  "Return exactly one details entry per requested focus range, echoing the supplied sentenceId and that focus unchanged.",
  "Write explanations, grammar points, and every structure's role field in Chinese. Use concise Chinese grammatical terms for roles (主语/谓语/宾语/定语/状语/系动词/引导词/连词 etc.), never English enum values.",
  "Each entry's structures array must break down the internal components of its focus range. Never return a single structure that covers the entire focus — split it into meaningful sub-components (subject, predicate, object, clauses, etc.).",
  "Give every structure a concise Chinese translation of exactly its own English text in the translation field (a few words, like a gloss under the phrase); keep the longer analysis in explanation.",
].join("\n");
```

文件末尾加(**focuses 必须是 prompt 的最后一段**——假模型与单测都靠"末段即 JSON 数组"解析):

```ts
export function buildSentenceDetailsPrompt(
  sentence: SentenceInput,
  verifiedCore: CoreAnalysis,
  focuses: readonly TokenRange[],
): string {
  return [
    "Explain each requested grammatical component of the single sentence below.",
    "Treat the verified core result and every focus Token range as immutable. Refer only to supplied Token IDs.",
    "Return JSON only, with no Markdown or explanatory prose.",
    SENTENCE_DETAILS_OUTPUT_SHAPE,
    "Selected sentence:",
    serialize(sentence),
    "Verified core result:",
    serialize(verifiedCore),
    "Requested focus ranges:",
    serialize(focuses),
  ].join("\n\n");
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/background/prompts.test.ts`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/background/prompts.ts src/background/prompts.test.ts
git commit -m "feat: 整句成分详解合批 prompt" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: analysis-service 整句详解方法

**Files:**

- Modify: `src/background/analysis-service.ts`
- Test: `src/background/analysis-service.test.ts`

- [ ] **Step 1: Write the failing tests**

`analysis-service.test.ts` 追加(sentence/profile/scheduler stub、以及"先经 analyzeDetail 或 analyzeCore 灌缓存"的安排沿用文件内既有 happy-path 用例;core 需含 ≥2 个成分)。要点断言:

```ts
describe("analyzeSentenceDetails", () => {
  it("returns all-cached counts without touching the scheduler", async () => {
    // 先对 core.components 的每个 focus 各调一次 analyzeDetail 灌满缓存,
    // 记录此刻 scheduler.schedule 的调用数 baseline,然后:
    const outcome = await service.analyzeSentenceDetails(
      { profile, documentId: "doc", sentence, core },
      new AbortController().signal,
    );
    expect(outcome).toEqual({ succeeded: core.components.length, failed: 0 });
    expect(schedulerCalls()).toBe(baseline); // 零新增调度
  });

  it("requests only the missing focuses in one call and caches each returned detail", async () => {
    // 缓存里只灌第 1 个成分;adapter/scheduler stub 返回
    // { details: [针对其余每个缺失 focus 的合法 detail JSON] }。
    const outcome = await service.analyzeSentenceDetails(...);
    expect(outcome).toEqual({ succeeded: core.components.length, failed: 0 });
    expect(schedulerCalls()).toBe(baseline + 1); // 只发了一次整句请求
    // prompt 断言:该次请求 messages[0].content 的 "Requested focus ranges:" 末段
    // JSON.parse 后恰为缺失 focus 列表(不含已缓存的第 1 个)。
    // 写回断言:对每个缺失 focus 走 lookupDetail 均命中。
  });

  it("repairs the invalid subset once and counts leftovers as failed", async () => {
    // 首轮响应:focus A 合法、focus B 缺失;repair 轮响应:仍不含 B。
    const outcome = await service.analyzeSentenceDetails(...);
    expect(outcome).toEqual({ succeeded: 1, failed: 1 });
    expect(schedulerCalls()).toBe(baseline + 2); // 首轮 + 一次 repair,不再有第三轮
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/background/analysis-service.test.ts`
Expected: FAIL,方法不存在。

- [ ] **Step 3: Implement types + schema + repair prompt**

`analysis-service.ts`:

(a) `DetailLookupInput` 旁加:

```ts
export interface SentenceDetailsInput extends AnalysisInputBase {
  sentence: SentenceInput;
  core: CoreAnalysis;
}

export interface SentenceDetailsOutcome {
  succeeded: number;
  failed: number;
}
```

(b) `AnalysisService` 接口 `lookupDetail` 之后加:

```ts
  /** 详解预载(按句合批):只补缺失成分,一次整句请求,结果逐成分写入现有缓存键。 */
  analyzeSentenceDetails(
    input: SentenceDetailsInput,
    signal: AbortSignal,
  ): Promise<SentenceDetailsOutcome>;
```

(c) `DETAIL_SCHEMA` 之后加:

```ts
const SENTENCE_DETAILS_SCHEMA: JsonSchemaSpec = {
  name: "sentence_details_analysis",
  schema: {
    type: "object",
    additionalProperties: false,
    required: ["details"],
    properties: {
      details: { type: "array", items: DETAIL_SCHEMA.schema },
    },
  },
};
```

(d) prompts import 里补 `buildSentenceDetailsPrompt`;`detailRepairPrompt` 之后加:

```ts
function sentenceDetailsRepairPrompt(
  input: SentenceDetailsInput,
  focuses: readonly TokenRange[],
  errors: readonly ValidationError[],
  invalidJson: unknown,
): string {
  return [
    "Repair only the structure of the invalid sentence-details JSON so every requested focus has one valid entry.",
    "Keep the sentence ID, Tokens, verified core analysis, and focus ranges unchanged. Return JSON only.",
    `Sentence and Tokens:\n${JSON.stringify(input.sentence, null, 2)}`,
    `Verified core analysis:\n${JSON.stringify(input.core, null, 2)}`,
    `Validation errors:\n${JSON.stringify(errors, null, 2)}`,
    `Invalid JSON:\n${JSON.stringify(invalidJson, null, 2)}`,
    `Requested focus ranges:\n${JSON.stringify(focuses, null, 2)}`,
  ].join("\n\n");
}
```

`ValidationError` 若尚未在该文件 import,从 `./validators` 补(与 `validateDetail` 同源;以文件现状为准)。

- [ ] **Step 4: Implement the method**

`CachedAnalysisService` 内 `lookupDetail` 之后加:

```ts
  async analyzeSentenceDetails(
    input: SentenceDetailsInput,
    signal: AbortSignal,
  ): Promise<SentenceDetailsOutcome> {
    if (signal.aborted) throw cancellationError();
    const targets = await Promise.all(
      input.core.components.map(async (component) => {
        const focus = { startToken: component.startToken, endToken: component.endToken };
        const key = await this.detailKey({ sentence: input.sentence, focus });
        const cached = validateCachedDetail(
          await this.options.cache.getDetail<unknown>(key),
          input.sentence,
          focus,
          input.profile.id,
        );
        return { focus, key, cached };
      }),
    );
    if (signal.aborted) throw cancellationError();
    let missing = targets.filter(({ cached }) => cached === undefined);
    let succeeded = targets.length - missing.length;
    if (missing.length === 0) return { succeeded, failed: 0 };

    const cacheKey = `${missing.map(({ key }) => key).join(":")}:sentence-details`;
    const raw = await this.requestModel(
      input.profile,
      input.documentId,
      "prefetch-detail",
      cacheKey,
      1,
      [
        {
          role: "user",
          content: buildSentenceDetailsPrompt(
            input.sentence,
            input.core,
            missing.map(({ focus }) => focus),
          ),
        },
      ],
      SENTENCE_DETAILS_SCHEMA,
      "sentence-details",
      signal,
    );
    const firstPass = await this.validateAndCacheDetails(input, missing, raw);
    succeeded += firstPass.valid;
    missing = firstPass.invalid;
    if (missing.length > 0) {
      const repairRaw = await this.requestModel(
        input.profile,
        input.documentId,
        "prefetch-detail",
        `${cacheKey}:repair`,
        1,
        [
          {
            role: "user",
            content: sentenceDetailsRepairPrompt(
              input,
              missing.map(({ focus }) => focus),
              firstPass.errors,
              raw,
            ),
          },
        ],
        SENTENCE_DETAILS_SCHEMA,
        "sentence-details-repair",
        signal,
      );
      const secondPass = await this.validateAndCacheDetails(input, missing, repairRaw);
      succeeded += secondPass.valid;
      missing = secondPass.invalid;
    }
    return { succeeded, failed: missing.length };
  }

  /** 逐 focus 从原始响应里捞对应条目、校验并写缓存;返回合格数与失败目标。 */
  private async validateAndCacheDetails(
    input: SentenceDetailsInput,
    targets: readonly { focus: TokenRange; key: string }[],
    raw: unknown,
  ): Promise<{
    valid: number;
    invalid: { focus: TokenRange; key: string }[];
    errors: ValidationError[];
  }> {
    const rawDetails =
      isRecord(raw) && Array.isArray(raw.details) ? (raw.details as unknown[]) : [];
    let valid = 0;
    const invalid: { focus: TokenRange; key: string }[] = [];
    const errors: ValidationError[] = [];
    for (const target of targets) {
      const candidate = rawDetails.find(
        (item) => isRecord(item) && isMatchingFocus(item.focus, target.focus),
      );
      const validation =
        candidate === undefined
          ? undefined
          : validateDetail(candidate, input.sentence, target.focus, input.profile.id);
      if (validation !== undefined && validation.ok) {
        await this.options.cache.putDetail(target.key, input.profile.id, validation.value);
        valid += 1;
        continue;
      }
      invalid.push(target);
      if (validation !== undefined) errors.push(...validation.errors);
      else
        errors.push({
          path: `details[focus ${target.focus.startToken}-${target.focus.endToken}]`,
          message: "missing entry for requested focus",
        });
    }
    return { valid, invalid, errors };
  }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `npx vitest run src/background/analysis-service.test.ts`
Expected: PASS(含既有全部用例)。

- [ ] **Step 6: Commit**

```bash
git add src/background/analysis-service.ts src/background/analysis-service.test.ts
git commit -m "feat: 整句合批的成分详解预载服务方法" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: service worker 路由与开关下发

**Files:**

- Modify: `src/background/service-worker.ts`
- Test: `src/background/service-worker.test.ts`

- [ ] **Step 1: Write the failing tests**

`service-worker.test.ts` 追加(沿用文件的 dependencies stub 与 dispatch 辅助;analysisService stub 需补 `analyzeSentenceDetails: vi.fn()`,否则接口不完整先炸类型):

```ts
describe("detail prefetch", () => {
  it("START_SESSION forwards prefetchDetail: true only when the flag is on and a profile exists", async () => {
    // configRepository stub: getPrefetchDetail → true,getActiveProfile → profile
    await dispatch(startSessionRequest());
    // 断言 tabs.sendMessage 收到的页面命令含 prefetchDetail: true
  });

  it("START_SESSION omits the flag when disabled or when no profile exists", async () => {
    // 两种情况各 dispatch 一次:getPrefetchDetail → false;profile → undefined 且 flag → true
    // 断言页面命令上没有 prefetchDetail 键
  });

  it("PREFETCH_SENTENCE_DETAILS routes to the service and echoes counts", async () => {
    // analyzeSentenceDetails mock → { succeeded: 3, failed: 1 }
    const response = await dispatch(prefetchSentenceDetailsRequest());
    expect(response).toMatchObject({ type: "SENTENCE_DETAILS_RESULT", succeeded: 3, failed: 1 });
  });

  it("PREFETCH_SENTENCE_DETAILS without a profile returns CONFIG_MISSING", async () => {
    const response = await dispatch(prefetchSentenceDetailsRequest());
    expect(response).toMatchObject({ type: "ERROR", error: { code: "CONFIG_MISSING" } });
  });

  it("isStatus accepts and relays detail counters", async () => {
    // 经既有 status-relay 用例的方式发含 detailTotal/detailReady/detailFailed 的状态,
    // 再 GET_SESSION_STATUS 断言原样返回;负数或非整数的 detailTotal 应被拒。
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/background/service-worker.test.ts`
Expected: FAIL。

- [ ] **Step 3: Implement**

`service-worker.ts` 五处:

(a) `sendPageCommand` 的 body 联合类型中 `{ type: "START_SESSION" }` 改为:

```ts
      | { type: "START_SESSION"; prefetchDetail?: true }
```

(b) `case "START_SESSION"` 内,`const profile = await dependencies.configRepository.getActiveProfile();` 之后加一行,并改 sendPageCommand 调用:

```ts
const prefetchDetail =
  profile !== undefined && (await dependencies.configRepository.getPrefetchDetail());
// …
await sendPageCommand(request.tabId, documentId, {
  type: "START_SESSION",
  ...(prefetchDetail ? { prefetchDetail: true } : {}),
});
```

**注意**:文件里还有第二处 `sendPageCommand(tabId, documentId, { type: "START_SESSION" })`(约 634 行,内容脚本重注入/恢复路径)。同样补上快照读取与展开,两处逻辑保持一致。

(c) `route()` 的 `case "ANALYZE_DETAIL"` 之后加:

```ts
        case "PREFETCH_SENTENCE_DETAILS": {
          const profile = await profileFor(request.tabId);
          if (profile === undefined) return errorResponse(request.requestId, "CONFIG_MISSING");
          const outcome = await dependencies.analysisService.analyzeSentenceDetails(
            {
              profile,
              documentId: request.documentId,
              sentence: request.sentence,
              core: request.core,
            },
            new AbortController().signal,
          );
          return {
            version: MESSAGE_VERSION,
            requestId: request.requestId,
            type: "SENTENCE_DETAILS_RESULT",
            succeeded: outcome.succeeded,
            failed: outcome.failed,
          };
        }
```

(signal 用 `new AbortController().signal` 与 ANALYZE_DETAIL case 同款——真正的取消走 `scheduler.cancelDocument`。)

(d) `isStatus` 返回表达式在 `skipped` 检查之后补三条(同款模式):

```ts
    (status.detailTotal === undefined ||
      (Number.isSafeInteger(status.detailTotal) && status.detailTotal >= 0)) &&
    (status.detailReady === undefined ||
      (Number.isSafeInteger(status.detailReady) && status.detailReady >= 0)) &&
    (status.detailFailed === undefined ||
      (Number.isSafeInteger(status.detailFailed) && status.detailFailed >= 0)) &&
```

(e) `defaultDependencies()` 的 analysisService 懒代理加:

```ts
      analyzeSentenceDetails: async (...arguments_) =>
        (await getRuntime()).analysisService.analyzeSentenceDetails(...arguments_),
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/background/`
Expected: PASS(含既有全部用例)。

- [ ] **Step 5: Commit**

```bash
git add src/background/service-worker.ts src/background/service-worker.test.ts
git commit -m "feat: SW 路由整句详解预载并随会话下发开关快照" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: DetailPrefetcher 模块

**Files:**

- Create: `src/content/detail-prefetcher.ts`
- Test: `src/content/detail-prefetcher.test.ts`(新建)

- [ ] **Step 1: Write the failing tests**

新建 `src/content/detail-prefetcher.test.ts`:

```ts
import { describe, expect, it, vi } from "vitest";
import type { CoreAnalysis } from "../shared/grammar";
import type { SentenceInput } from "../shared/protocol";
import { DetailPrefetcher, type PrefetchSendResult } from "./detail-prefetcher";

function sentence(id: string): SentenceInput {
  return { sentenceId: id, text: `${id} text`, tokens: [] };
}

function core(id: string, componentCount: number): CoreAnalysis {
  return {
    schemaVersion: 1,
    sentenceId: id,
    modelProfileId: "p",
    components: Array.from({ length: componentCount }, (_, index) => ({
      startToken: index,
      endToken: index,
      role: "SUBJECT",
      translation: "x",
    })),
  } as CoreAnalysis;
}

type Deferred = { resolve: (result: PrefetchSendResult) => void };

function harness(concurrency?: number) {
  const pending: Deferred[] = [];
  const sent: string[] = [];
  const onChange = vi.fn();
  const prefetcher = new DetailPrefetcher({
    concurrency,
    onChange,
    send: (item) => {
      sent.push(item.sentence.sentenceId);
      return new Promise<PrefetchSendResult>((resolve) => pending.push({ resolve }));
    },
  });
  const settle = async (result: PrefetchSendResult) => {
    pending.shift()!.resolve(result);
    await Promise.resolve();
    await Promise.resolve();
  };
  return { prefetcher, pending, sent, settle, onChange };
}

describe("DetailPrefetcher", () => {
  it("counts totals on enqueue, sends with bounded concurrency, and accumulates results", async () => {
    const { prefetcher, sent, settle } = harness(1);
    prefetcher.enqueue(sentence("s1"), core("s1", 3));
    prefetcher.enqueue(sentence("s2"), core("s2", 2));

    expect(prefetcher.counts()).toEqual({ total: 5, ready: 0, failed: 0 });
    expect(sent).toEqual(["s1"]); // 并发 1:第二句等第一句结果

    await settle({ kind: "ok", succeeded: 2, failed: 1 });
    expect(prefetcher.counts()).toEqual({ total: 5, ready: 2, failed: 1 });
    expect(sent).toEqual(["s1", "s2"]);
  });

  it("ignores duplicate enqueues for the same sentence", () => {
    const { prefetcher } = harness();
    prefetcher.enqueue(sentence("s1"), core("s1", 3));
    prefetcher.enqueue(sentence("s1"), core("s1", 3));
    expect(prefetcher.counts().total).toBe(3);
  });

  it("re-queues a cancelled sentence and resumes it after resume()", async () => {
    const { prefetcher, sent, settle } = harness(1);
    prefetcher.enqueue(sentence("s1"), core("s1", 3));
    prefetcher.pause();
    await settle({ kind: "cancelled" });
    expect(prefetcher.counts()).toEqual({ total: 3, ready: 0, failed: 0 });
    expect(sent).toEqual(["s1"]); // 暂停中不重发

    prefetcher.resume();
    expect(sent).toEqual(["s1", "s1"]);
  });

  it("counts the whole sentence as failed on a failed send", async () => {
    const { prefetcher, settle } = harness(1);
    prefetcher.enqueue(sentence("s1"), core("s1", 4));
    await settle({ kind: "failed" });
    expect(prefetcher.counts()).toEqual({ total: 4, ready: 0, failed: 4 });
  });

  it("discard() drops a queued sentence and its share of the total", () => {
    const { prefetcher, sent } = harness(1);
    prefetcher.enqueue(sentence("s1"), core("s1", 3)); // in flight
    prefetcher.enqueue(sentence("s2"), core("s2", 2)); // queued
    prefetcher.discard("s2");
    expect(prefetcher.counts().total).toBe(3);
    expect(sent).toEqual(["s1"]);
    prefetcher.enqueue(sentence("s2"), core("s2", 2)); // 重新就绪可再入队
    expect(prefetcher.counts().total).toBe(5);
  });

  it("notifies onChange on every count movement", async () => {
    const { prefetcher, settle, onChange } = harness(1);
    prefetcher.enqueue(sentence("s1"), core("s1", 1));
    const calls = onChange.mock.calls.length;
    await settle({ kind: "ok", succeeded: 1, failed: 0 });
    expect(onChange.mock.calls.length).toBeGreaterThan(calls);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/content/detail-prefetcher.test.ts`
Expected: FAIL,模块不存在。

- [ ] **Step 3: Implement**

新建 `src/content/detail-prefetcher.ts`:

```ts
import type { CoreAnalysis } from "../shared/grammar";
import type { SentenceInput } from "../shared/protocol";

export interface PrefetchCounts {
  total: number;
  ready: number;
  failed: number;
}

export interface PrefetchItem {
  sentence: SentenceInput;
  core: CoreAnalysis;
}

/** send 的归一化结果:ok 带成分级计数;cancelled 回滚重排;failed 整句计失败。 */
export type PrefetchSendResult =
  { kind: "ok"; succeeded: number; failed: number } | { kind: "cancelled" } | { kind: "failed" };

export interface DetailPrefetcherOptions {
  send(item: PrefetchItem): Promise<PrefetchSendResult>;
  onChange(): void;
  /** 同时在飞的整句请求数;调度器侧还有自己的并发与优先级控制。 */
  concurrency?: number;
}

export class DetailPrefetcher {
  private readonly queue: PrefetchItem[] = [];
  private readonly trackedIds = new Set<string>();
  private readonly concurrency: number;
  private inFlight = 0;
  private paused = false;
  private total = 0;
  private ready = 0;
  private failed = 0;

  constructor(private readonly options: DetailPrefetcherOptions) {
    this.concurrency = options.concurrency ?? 2;
  }

  counts(): PrefetchCounts {
    return { total: this.total, ready: this.ready, failed: this.failed };
  }

  enqueue(sentence: SentenceInput, core: CoreAnalysis): void {
    if (this.trackedIds.has(sentence.sentenceId) || core.components.length === 0) return;
    this.trackedIds.add(sentence.sentenceId);
    this.queue.push({ sentence, core });
    this.total += core.components.length;
    this.options.onChange();
    this.pump();
  }

  /** 句子所在块失效(stale):只丢还在排队的;在飞的让它跑完,结果照常计数。 */
  discard(sentenceId: string): void {
    const index = this.queue.findIndex(({ sentence }) => sentence.sentenceId === sentenceId);
    if (index === -1) return;
    const [dropped] = this.queue.splice(index, 1);
    this.trackedIds.delete(sentenceId);
    this.total -= dropped!.core.components.length;
    this.options.onChange();
  }

  pause(): void {
    this.paused = true;
  }

  resume(): void {
    if (!this.paused) return;
    this.paused = false;
    this.pump();
  }

  private pump(): void {
    while (!this.paused && this.inFlight < this.concurrency && this.queue.length > 0) {
      const item = this.queue.shift()!;
      this.inFlight += 1;
      void this.run(item);
    }
  }

  private async run(item: PrefetchItem): Promise<void> {
    let result: PrefetchSendResult;
    try {
      result = await this.options.send(item);
    } catch {
      result = { kind: "failed" };
    }
    this.inFlight -= 1;
    if (result.kind === "cancelled") {
      // 回滚为待发:计数不动,恢复后重发。
      this.queue.unshift(item);
    } else if (result.kind === "failed") {
      this.failed += item.core.components.length;
      this.options.onChange();
    } else {
      this.ready += result.succeeded;
      this.failed += result.failed;
      this.options.onChange();
    }
    this.pump();
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/content/detail-prefetcher.test.ts`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/content/detail-prefetcher.ts src/content/detail-prefetcher.test.ts
git commit -m "feat: content 侧详解预载队列(按句有界并发与计数)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: session-controller 集成 + content-script 传参

**Files:**

- Modify: `src/content/session-controller.ts`、`src/content/content-script.ts`
- Test: `src/content/session-controller.test.ts`、`src/content/content-script.test.ts`(若无后者则由前者覆盖经 route 的路径)

- [ ] **Step 1: Write the failing tests**

`session-controller.test.ts` 追加(沿用文件的 fake transport/block 基建;transport stub 需能按消息类型分派响应)。要点断言:

```ts
describe("detail prefetch integration", () => {
  it("feeds ready sentences into the prefetcher and reports detail counts", async () => {
    // start({ prefetchDetail: true });ANALYZE_CORE 返回 2 句、每句 2 成分;
    // transport 对 PREFETCH_SENTENCE_DETAILS 返回
    // { type: "SENTENCE_DETAILS_RESULT", succeeded: 2, failed: 0 }
    // …驱动可视块分析(照抄既有用例)…
    expect(prefetchMessages().length).toBe(2); // 每句恰一条
    expect(prefetchMessages()[0]).toMatchObject({
      type: "PREFETCH_SENTENCE_DETAILS",
      sentence: expect.anything(),
      core: expect.anything(),
    });
    expect(lastStatus).toMatchObject({ detailTotal: 4, detailReady: 4, detailFailed: 0 });
  });

  it("does not prefetch when started without the flag and omits detail fields", async () => {
    // start() 后驱动同样的分析
    expect(prefetchMessages().length).toBe(0);
    expect("detailTotal" in lastStatus).toBe(false);
  });

  it("counts a whole sentence as failed on an ERROR response and re-queues on cancel", async () => {
    // 句 A 的预载响应为 ERROR(code 任意非 REQUEST_CANCELLED)→ detailFailed == 成分数;
    // 句 B 先回 REQUEST_CANCELLED → 不计数;pause() 后 resume() → 该句重发一次。
  });

  it("stops prefetching after stop()", async () => {
    // start({prefetchDetail:true}) → stop():status 不再含 detail 字段,无新预载消息
  });
});
```

`content-script.test.ts`(或该文件对应位置)追加:

```ts
it("isSessionStatus accepts detail counters and START_SESSION forwards the flag", async () => {
  // 1) isRuntimeResponse 对含 detailTotal/detailReady/detailFailed 的 SESSION_STATUS 返回 true;
  // 2) router.route(START_SESSION with prefetchDetail: true) 时,
  //    controllerFactory 收到的 controller.start 以 { prefetchDetail: true } 被调用
  //    (用 controllerFactory stub 捕获 start 参数)。
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/content/session-controller.test.ts src/content/content-script.test.ts`
Expected: FAIL。

- [ ] **Step 3: Implement session-controller**

`session-controller.ts`:

(a) import 加:

```ts
import { DetailPrefetcher } from "./detail-prefetcher";
import type { PrefetchSendResult } from "./detail-prefetcher";
```

(b) 私有字段区(`detailVersions` 旁)加:

```ts
  private prefetcher?: DetailPrefetcher;
```

(c) `start()` 签名与开头改为:

```ts
  async start(options?: { prefetchDetail?: boolean }): Promise<void> {
    if (this.state === "running") return;
    if (this.state === "paused") {
      this.resume();
      return;
    }
    this.state = "running";
    if (options?.prefetchDetail === true) {
      this.prefetcher = new DetailPrefetcher({
        send: (item) => this.sendPrefetch(item.sentence, item.core),
        onChange: () => this.emitStatus(),
      });
    }
```

(其余原有语句不动。)

(d) 类内加私有方法(放 `requestDetail` 之后):

```ts
  /** 预载发送:把 transport 响应归一化为 prefetcher 的三态结果。 */
  private async sendPrefetch(
    sentence: SentenceInput,
    core: CoreAnalysis,
  ): Promise<PrefetchSendResult> {
    const response = await this.options.transport.send(
      this.pageRequest({ type: "PREFETCH_SENTENCE_DETAILS", sentence, core }),
    );
    if (response.type === "SENTENCE_DETAILS_RESULT") {
      return { kind: "ok", succeeded: response.succeeded, failed: response.failed };
    }
    if (response.type === "ERROR" && response.error.code === "REQUEST_CANCELLED") {
      return { kind: "cancelled" };
    }
    return { kind: "failed" };
  }
```

(`pageRequest` 是该文件既有的消息构造辅助;若其入参类型是 RequestMessage 的收窄联合,把新消息类型并入。)

(e) `status` getter 的返回对象末尾(profileId 展开之后)加:

```ts
      ...(this.prefetcher === undefined
        ? {}
        : (() => {
            const counts = this.prefetcher.counts();
            return {
              detailTotal: counts.total,
              detailReady: counts.ready,
              detailFailed: counts.failed,
            };
          })()),
```

(f) `analyzeBlock` 中 `this.transition(sentence, "ready");`(renderCore 成功后那处)之后加:

```ts
this.prefetcher?.enqueue(sentence.input, analysis);
```

(g) `pause()` 里 `this.state = "paused";` 之后加 `this.prefetcher?.pause();`;`resume()` 里 `this.state = "running";` 之后加 `this.prefetcher?.resume();`。

(h) `stop()` 里 `this.options.transport.cancelDocument(this.documentId);` 之后加:

```ts
this.prefetcher = undefined;
```

(i) `invalidateBlock` 中对句子 `this.transition(sentence, "stale");` 的循环里加:

```ts
this.prefetcher?.discard(sentence.input.sentenceId);
```

- [ ] **Step 4: Implement content-script**

`content-script.ts`:

(a) `isSessionStatus` 返回表达式补三条:

```ts
    (value.detailTotal === undefined || isSafeInteger(value.detailTotal)) &&
    (value.detailReady === undefined || isSafeInteger(value.detailReady)) &&
    (value.detailFailed === undefined || isSafeInteger(value.detailFailed)) &&
```

(b) `ContentScriptRouter.route` 的 `case "START_SESSION":` 改为:

```ts
      case "START_SESSION":
        await controller.start({ prefetchDetail: request.prefetchDetail === true });
        return statusResponse(request.requestId, controller.status);
```

(`RoutedController` 接口的 `start` 签名同步改为 `start(options?: { prefetchDetail?: boolean }): Promise<void>`。)

- [ ] **Step 5: Run tests to verify they pass**

Run: `npx vitest run src/content/`
Expected: PASS(既有 fake controller/transport 若因签名新增报类型错,补上对应参数/分支)。

- [ ] **Step 6: Commit**

```bash
git add src/content/session-controller.ts src/content/content-script.ts src/content/session-controller.test.ts src/content/content-script.test.ts
git commit -m "feat: 会话控制器接入详解预载并上报成分级进度" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: 进度 pill 文案

**Files:**

- Modify: `src/content/progress-pill.ts`
- Test: `src/content/progress-pill.test.ts`

- [ ] **Step 1: Write the failing tests**

`progress-pill.test.ts` 追加(status 构造沿用文件既有工厂):

```ts
it("shows detail prefetch progress after the core phase completes", () => {
  pill.update(
    status({
      state: "running",
      discovered: 2,
      ready: 2,
      detailTotal: 6,
      detailReady: 3,
      detailFailed: 1,
    }),
  );
  expect(label()).toBe("详解预载中 4/6");
  expect(spinnerVisible()).toBe(true);
});

it("mentions failed details in the completion text", () => {
  pill.update(
    status({
      state: "running",
      discovered: 2,
      ready: 2,
      detailTotal: 6,
      detailReady: 4,
      detailFailed: 2,
    }),
  );
  expect(label()).toBe("✓ 解析完成（2 个详解失败）");
});

it("keeps the plain completion text when prefetch is off", () => {
  pill.update(status({ state: "running", discovered: 2, ready: 2 }));
  expect(label()).toBe("✓ 解析完成");
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/content/progress-pill.test.ts`
Expected: FAIL。

- [ ] **Step 3: Implement**

`progress-pill.ts` 的 `update()` 中,把 `if (isSessionComplete(status)) { … }` 分支替换为:

```ts
if (isSessionComplete(status)) {
  const detailSettled = (status.detailReady ?? 0) + (status.detailFailed ?? 0);
  if (status.detailTotal !== undefined && detailSettled < status.detailTotal) {
    this.#render(`详解预载中 ${detailSettled}/${status.detailTotal}`, true);
    return;
  }
  const detailFailed = status.detailFailed ?? 0;
  this.#render(
    status.failed > 0
      ? `✓ 完成，${status.failed} 句失败`
      : detailFailed > 0
        ? `✓ 解析完成（${detailFailed} 个详解失败）`
        : "✓ 解析完成",
    false,
  );
  this.#fadeTimer = setTimeout(() => this.remove(), FADE_DELAY_MS);
  return;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/content/progress-pill.test.ts`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/content/progress-pill.ts src/content/progress-pill.test.ts
git commit -m "feat: 进度 pill 显示详解预载进度与失败数" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: popup 副线进度

**Files:**

- Modify: `src/popup/popup.ts`
- Test: `src/popup/popup.test.ts`

- [ ] **Step 1: Write the failing test**

`popup.test.ts` 追加(dependencies/status 工厂沿用文件现状):

```ts
it("shows detail prefetch progress in the subline while it is running", async () => {
  await createPopupPage(
    root(),
    dependencies({
      getStatus: vi.fn(() =>
        Promise.resolve(
          status({
            state: "running",
            discovered: 5,
            ready: 5,
            detailTotal: 20,
            detailReady: 7,
            detailFailed: 1,
          }),
        ),
      ),
    }),
  );
  expect(subline().textContent).toContain("详解预载中 8/20");
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run src/popup/popup.test.ts`
Expected: FAIL。

- [ ] **Step 3: Implement**

`popup.ts` 的 `renderStatus` 中,在状态机 if/else 链结束之后、`if (status.state === "running" || status.state === "paused")` 之前插入:

```ts
const detailSettled = (status.detailReady ?? 0) + (status.detailFailed ?? 0);
if (
  status.state === "running" &&
  status.detailTotal !== undefined &&
  detailSettled < status.detailTotal
) {
  subline.textContent = `详解预载中 ${detailSettled}/${status.detailTotal}`;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/popup/popup.test.ts`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/popup/popup.ts src/popup/popup.test.ts
git commit -m "feat: popup 副线显示详解预载进度" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 11: 选项页开关

**Files:**

- Modify: `src/options/options.ts`
- Test: `src/options/options.test.ts`(**lint 基线:该文件现有恰 1 个 no-unnecessary-type-assertion 错误,不要修、不要新增**)

- [ ] **Step 1: Write the failing tests**

`options.test.ts` 的 `dependencies()` 工厂追加两个默认实现(与 exportCacheFile 等并列):

```ts
    getPrefetchDetail: vi.fn(() => Promise.resolve(false)),
    setPrefetchDetail: vi.fn(() => Promise.resolve()),
```

文件末尾追加:

```ts
describe("detail prefetch toggle", () => {
  it("renders the checkbox with the stored value", async () => {
    await createOptionsPage(
      root(),
      dependencies({ getPrefetchDetail: vi.fn(() => Promise.resolve(true)) }),
    );
    const checkbox = document.querySelector<HTMLInputElement>("[data-prefetch-detail]");
    expect(checkbox?.type).toBe("checkbox");
    expect(checkbox?.checked).toBe(true);
    expect(document.body.textContent).toContain("预载成分详解");
  });

  it("persists changes through setPrefetchDetail", async () => {
    const subject = dependencies();
    await createOptionsPage(root(), subject);
    const checkbox = document.querySelector<HTMLInputElement>("[data-prefetch-detail]")!;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event("change"));
    await vi.waitFor(() => expect(subject.setPrefetchDetail).toHaveBeenCalledWith(true));
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/options/options.test.ts`
Expected: FAIL(缺依赖字段导致 TS 报错也算失败信号)。

- [ ] **Step 3: Implement**

`src/options/options.ts` 五处:

(a) `OptionsDependencies` 加(`importCacheFile` 之后):

```ts
getPrefetchDetail: () => Promise<boolean>;
setPrefetchDetail: (enabled: boolean) => Promise<void>;
```

(b) 缓存区 UI:在 `cacheHint` 声明后加(`element(...)` 辅助与 class 命名以文件现状为准;若无现成 hint 样式类,沿用 `cacheHint` 用的那个):

```ts
const prefetchLabel = element("label", "options-page__cache-limit-label");
const prefetchInput = element("input") as HTMLInputElement;
prefetchInput.type = "checkbox";
prefetchInput.dataset.prefetchDetail = "";
prefetchLabel.append(prefetchInput, document.createTextNode(" 预载成分详解"));
const prefetchHint = element(
  "p",
  "options-page__hint",
  "开启后每句解析完成即自动生成全部成分详解并入缓存（可随导出分享）；token 消耗数倍于仅核心解析。下次点击「开始学习」生效。",
);
```

并把 `prefetchLabel`、`prefetchHint` 插入 `cacheSection.append(...)`(放在 `cacheHint` 之后、`clearButton` 之前)。

(c) 初始化:在读取缓存统计的同一初始化段加:

```ts
prefetchInput.checked = await dependencies.getPrefetchDetail();
```

(d) 事件:

```ts
prefetchInput.addEventListener("change", () => {
  void dependencies.setPrefetchDetail(prefetchInput.checked);
});
```

(e) `runtimeDependencies()` 返回对象加:

```ts
    getPrefetchDetail: () => repository.getPrefetchDetail(),
    setPrefetchDetail: (enabled) => repository.setPrefetchDetail(enabled),
```

- [ ] **Step 4: Run tests + lint 基线核对**

Run: `npx vitest run src/options/options.test.ts && npm run lint`
Expected: 测试 PASS;lint 仍恰 1 个基线错误。

- [ ] **Step 5: Commit**

```bash
git add src/options/options.ts src/options/options.test.ts
git commit -m "feat: 选项页新增预载成分详解开关" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 12: E2E(假模型扩展 + 主链路)+ 全量门禁

**Files:**

- Modify: `tests/support/fake-openai-server.ts`、`tests/e2e/extension.spec.ts`(文件内注释与测试标题一律英文)

- [ ] **Step 1: Extend the fake model server**

`fake-openai-server.ts`:

(a) `RequestKind` 联合加 `"sentence-details"` 与 `"sentence-details-repair"`。

(b) `detectKind` 加(前缀与既有 `"Explain only the selected"` 互斥,顺序无所谓):

```ts
if (text.startsWith("Explain each requested grammatical component")) return "sentence-details";
if (text.startsWith("Repair only the structure of the invalid sentence-details JSON")) {
  return "sentence-details-repair";
}
```

(c) auto 响应:prompt 末段(`"Requested focus ranges:"` 之后)是 focus 数组的 JSON;sentenceId 从 prompt 里第一个 `"sentenceId"` 字段取。加辅助:

```ts
function sentenceDetailsTargets(promptText: string): {
  sentenceId: string;
  focuses: { startToken: number; endToken: number }[];
} {
  const sentenceId = promptText.match(/"sentenceId":\s*"([^"]+)"/)?.[1] ?? "unknown";
  const section = promptText.split("Requested focus ranges:")[1] ?? "[]";
  return { sentenceId, focuses: JSON.parse(section.trim()) };
}
```

`respondAuto` 的 switch 加 case(单成分 detail 的合法 JSON 构造已有 helper——即现有 `case "detail"` 用的那个;对每个 focus 各构造一份,包进 `details` 数组):

```ts
      case "sentence-details":
      case "sentence-details-repair": {
        const { sentenceId, focuses } = sentenceDetailsTargets(promptText);
        this.json(response, {
          details: focuses.map((focus) => this.validDetailFor(sentenceId, focus)),
        });
        return;
      }
```

`validDetailFor` 指现有单成分 auto 响应里"给定 sentenceId+focus 生成合法 detail JSON"的构造逻辑;若它目前内联在 `case "detail"` 里,先抽成同文件私有方法再两处复用(注意保持现有 detail 用例全绿)。`promptText` 若在 `respondAuto` 作用域拿不到,把它作为参数从 `reply` 传入(照 `sentences` 的传法)。

- [ ] **Step 2: Write the E2E test**

`extension.spec.ts` 追加:

```ts
test("enabling detail prefetch caches every component and a click needs no model call", async ({
  harness,
}) => {
  await seedLocalProfile(harness);
  await harness.serviceWorker.evaluate(async () => {
    await chrome.storage.local.set({ "prefetchDetail.v1": true });
  });
  const { page, tabId, documentId } = await startSession(harness, "dynamic-article.html");
  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });

  // Poll session status until the prefetch phase settles (probe, not wall clock).
  await expect
    .poll(
      async () => {
        const response = (await harness.dispatchFromUi(
          uiMessage("GET_SESSION_STATUS", { tabId, documentId }),
        )) as { status?: { detailTotal?: number; detailReady?: number; detailFailed?: number } };
        const status = response.status ?? {};
        return (
          status.detailTotal !== undefined &&
          (status.detailReady ?? 0) + (status.detailFailed ?? 0) >= status.detailTotal
        );
      },
      { timeout: 30_000 },
    )
    .toBe(true);

  const sentenceCalls = harness.fakeModel.recordedOfKind("sentence-details").length;
  expect(sentenceCalls).toBeGreaterThan(0);
  expect(harness.fakeModel.recordedOfKind("detail")).toHaveLength(0);

  // A click now renders straight from the cache: no single-detail model call.
  await page.locator(".component").first().click();
  await expect(page.locator(".detail").first()).toBeVisible({ timeout: 10_000 });
  expect(harness.fakeModel.recordedOfKind("detail")).toHaveLength(0);
  expect(harness.fakeModel.recordedOfKind("sentence-details")).toHaveLength(sentenceCalls);

  await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId }));
});
```

`.component`/`.detail` 选择器与 `startSession`/`uiMessage`/`seedLocalProfile` 均为该文件既有约定;若成分/面板在 learning-block 容器内需特殊定位,照抄文件里既有 detail 用例的取法。

- [ ] **Step 3: Run the new test**

Run: `npx playwright test -g "enabling detail prefetch"`
Expected: PASS。

- [ ] **Step 4: Run the full suites**

Run: `npm test && npx playwright test && npm run lint && npm run format:check && npm run build`
Expected: 单测全绿、E2E 全绿(现有 24 条 + 新增)、lint 恰 1 基线错误、format 干净、构建成功。

- [ ] **Step 5: Commit**

```bash
git add tests/support/fake-openai-server.ts tests/e2e/extension.spec.ts
git commit -m "test: 详解预载整句合批 E2E 与假模型 sentence-details 响应" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 13: 真机验收(由主会话执行,不派子代理、不提交)

**Files:**

- Create(磁盘,gitignored): `.superpowers/acceptance/verify-detail-prefetch.mjs`

- [ ] **Step 1: 写脚本**

以 `.superpowers/acceptance/verify-cache-share.mjs` 为模板(同款 manifest patch、fixture server、双浏览器 profile、fetch 探针、dispatchFromUi),流程:

1. 浏览器 A:seed 真实 DeepSeek profile(key 从 `DEEPSEEK_API_KEY` 读,日志 `key <masked>`)并 `chrome.storage.local.set({ "prefetchDetail.v1": true })`;打开 `compound-article.html`,START_SESSION;轮询 GET_SESSION_STATUS 直到 `detailTotal > 0 && detailReady + detailFailed >= detailTotal`(上限 5 分钟);打开 options 页导出缓存,断言导出文件 `detail.length > 0`;关 A。
2. 浏览器 B:全新 profile,**不 seed 模型**;options 页导入该文件;装 fetch 探针;START_SESSION 后点击任一 `.component`;断言详解面板(`.detail`)渲染出内容、探针计数为 0。

- [ ] **Step 2: 运行**

Run: `source ~/.secrets && node .superpowers/acceptance/verify-detail-prefetch.mjs`
Expected: 末尾两行 `Prefetch fills the detail cache: PASS` / `Imported details render on click without model calls: PASS`,退出码 0。

- [ ] **Step 3: 清理**

脚本自删临时目录;不 git add(`.superpowers` 已 gitignore)。

---

## Self-Review 记录

- **Spec 覆盖**:§1 开关链路→Task 1(存储)+6(快照下发)+11(选项页);§2 执行机制→Task 2(协议)+3(优先级)+4(prompt)+5(service)+7(prefetcher)+8(controller 集成);§3 进度→Task 2(字段)+6(isStatus)+8(status getter/isSessionStatus)+9(pill)+10(popup);§4 错误处理→Task 5(repair 子集/failed 计数)+7(cancelled 回滚/failed 整句)+8(ERROR 归一化);§5 测试→各 Task Step 1 + Task 12 E2E + Task 13 真机。
- **类型一致性**:`PrefetchSendResult`(Task 7)与 `sendPrefetch`(Task 8)一致;`SentenceDetailsInput/Outcome`(Task 5)与 SW 调用点(Task 6)一致;`prefetchDetail?: true`(Task 2)与 SW 下发(Task 6)、router 传参(Task 8)一致;`detailTotal/detailReady/detailFailed` 贯穿 Task 2/6/8/9/10;prompt 首行 `"Explain each requested grammatical component"` 与 detectKind(Task 12)、prompts 测试(Task 4)一致;focuses 为 prompt 末段的约定被 Task 4 测试与 Task 12 假模型解析共同锁定。
- **占位符说明**:Task 5/6/8/12 部分单测以「沿用该文件既有 fixture」表述——与上一计划同理:这些测试文件各 700-900+ 行、自带成熟 stub 基建,照抄具体工厂名反而易错;要点断言已完整给出。Task 12 的 `validDetailFor` 指向假模型现有单成分构造逻辑,是"抽方法复用"而非新造。
- **风险点**:① SW 里第二处 START_SESSION sendPageCommand(重注入路径)容易漏——Task 6 Step 3(b) 已显式标注;② `pageRequest` 收窄联合若不含新消息类型会先炸编译,Task 8(d) 已提示;③ E2E 轮询用 GET_SESSION_STATUS 探针而非墙钟,符合既有验收教训。
