# 缓存键统一化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 缓存键收敛为「句子 + schema 版本(+focus/纠错上下文)」,升提示词版本/换模型/增删 profile 不再失效;「重新解析」通过 bypassCache 强制刷新。

**Architecture:** 三个键构建函数(analysis-service)与键输入类型(analysis-cache)删除 profile/模型/提示词维度;ANALYZE_CORE 消息、CoreBatchInput、analyzeCore 贯通可选 bypassCache;IndexedDB v1→v2 升级清空旧 store。渲染与读时校验兜底已存在,不动。

**Tech Stack:** TypeScript + Chrome MV3、Vitest(happy-dom + fake-indexeddb)、Playwright、真实 DeepSeek 验收脚本(Playwright harness 模式)。

**环境约定(全程有效):**
- 工作目录:`english-syntax-extension/`(worktree 分支 `codex/english-syntax-extension-next`,不合并主干、不推送)。
- 门禁:`npm test`、`npm run lint`(**恰好 1 条既有基线错误** src/options/options.test.ts:167,勿修勿增)、`npm run format:check`、`npm run build`、`npx playwright test`。
- 提交信息结尾加 trailer:`Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- API key 只从环境变量 `DEEPSEEK_API_KEY` 读,任何日志/文件不得出现明文。
- Spec:`docs/superpowers/specs/2026-07-19-cache-key-unification-design.md`。

---

## 文件地图

| 文件 | 职责变化 |
|---|---|
| `src/background/analysis-cache.ts` | 键输入类型瘦身;`DATABASE_VERSION` 2 + 升级清空;删 `clearByProfile` |
| `src/background/analysis-service.ts` | 句子归一化去 profile.id;三个键函数瘦身;`CoreBatchInput.bypassCache`;analyzeCore 跳读 |
| `src/shared/protocol.ts` | `ANALYZE_CORE` 加可选 `bypassCache?: true` + 校验 |
| `src/background/service-worker.ts` | ANALYZE_CORE case 透传 bypassCache |
| `src/content/session-controller.ts` | `BlockRecord.bypassCacheOnce`;reanalyzeVisible 置位;analyzeBlock 消费 |
| 测试 | analysis-cache.test.ts、新建 analysis-cache.migration.test.ts、analysis-service.test.ts、protocol.test.ts、session-controller.test.ts、tests/e2e/extension.spec.ts |
| 验收 | 新建 `.superpowers/acceptance/verify-cache-unification.mjs` |

---

### Task 1: 缓存键收敛

**Files:**
- Modify: `src/background/analysis-cache.ts:25-69`(类型与 identity)
- Modify: `src/background/analysis-service.ts:204-210, 348, 588-621`
- Test: `src/background/analysis-cache.test.ts`、`src/background/analysis-service.test.ts`

- [ ] **Step 1: 改写 analysis-cache.test.ts 的键测试(先失败)**

把文件顶部 fixture 与前两个测试(第 6-37 行)替换为:

```ts
const coreIdentity = {
  normalizedSentence: "The cat sleeps.",
  schemaVersion: 1,
};

describe("analysis cache keys", () => {
  it.each([
    ["sentence", { normalizedSentence: "The dog sleeps." }],
    ["schema version", { schemaVersion: 2 }],
    ["focus interval", { focus: { startToken: 1, endToken: 2 } }],
  ])("separates keys by %s", async (_field, override) => {
    const baseline = await createCoreCacheKey(coreIdentity);
    const changed = await createCoreCacheKey({ ...coreIdentity, ...override });

    expect(changed).not.toBe(baseline);
  });

  it("ignores profile, provider, model, and prompt-version fields entirely", async () => {
    const baseline = await createCoreCacheKey(coreIdentity);
    const withLegacyFields = await createCoreCacheKey({
      ...coreIdentity,
      profileId: "profile-2",
      providerOrigin: "https://other.example.com",
      model: "other-model",
      promptVersion: 99,
    } as never);

    expect(withLegacyFields).toBe(baseline);
  });
```

同时删除文件顶部 `import { CORE_PROMPT_VERSION } from "../shared/versions";`(该测试已随之删除)。第 39-72 行的纠错键测试保持原样(它 spread `coreIdentity`,自动适配新形状)。

- [ ] **Step 2: 跑测确认失败**

Run: `npx vitest run src/background/analysis-cache.test.ts`
Expected: FAIL(`withLegacyFields` 与 baseline 不等——旧实现仍读取这些字段)。

- [ ] **Step 3: 改 analysis-cache.ts 键类型与 identity**

替换第 25-49 行的两个接口与 `coreIdentity` 函数为:

```ts
export interface CoreCacheKeyInput {
  normalizedSentence: string;
  schemaVersion: number;
  focus?: TokenRange;
}

export interface CorrectionCacheKeyInput extends CoreCacheKeyInput {
  pageUrl: string;
  sentenceInstanceId: string;
  correctionContext: string;
}

function coreIdentity(input: CoreCacheKeyInput): readonly unknown[] {
  return [
    input.normalizedSentence,
    input.schemaVersion,
    input.focus === undefined ? null : [input.focus.startToken, input.focus.endToken],
  ];
}
```

- [ ] **Step 4: 改 analysis-service.ts 归一化与三个键函数**

第 204-211 行,`normalizedSentenceIdentity` 与 `providerOrigin` 替换为(`providerOrigin` 整个删除,已无调用方):

```ts
function normalizedSentenceText(sentence: SentenceInput): string {
  return sentence.text.trim().replace(/\s+/gu, " ");
}
```

第 588-621 行三个键函数替换为:

```ts
  private coreKey(sentence: SentenceInput): Promise<string> {
    return createCoreCacheKey({
      normalizedSentence: normalizedSentenceText(sentence),
      schemaVersion: CORE_SCHEMA_VERSION,
    });
  }

  private detailKey(input: DetailInput): Promise<string> {
    return createCoreCacheKey({
      normalizedSentence: normalizedSentenceText(input.sentence),
      schemaVersion: CORE_SCHEMA_VERSION,
      focus: input.focus,
    });
  }

  private correctionKey(input: CorrectionInput): Promise<string> {
    return createCorrectionCacheKey({
      normalizedSentence: normalizedSentenceText(input.sentence),
      schemaVersion: CORE_SCHEMA_VERSION,
      pageUrl: input.pageUrl,
      sentenceInstanceId: input.sentenceInstanceId,
      correctionContext: input.feedback,
    });
  }
```

调用方同步:第 348 行 `key: await this.coreKey(input.profile, sentence)` → `key: await this.coreKey(sentence)`。文件顶部 import 里删除不再使用的 `CORE_PROMPT_VERSION`、`DETAIL_PROMPT_VERSION`(`CORE_SCHEMA_VERSION` 保留;`src/shared/versions.ts` 的常量本身不删,提示词构建仍在用语义)。若 `ModelProfile` 型参仅剩键函数使用处报 unused,按编译器提示清理。

- [ ] **Step 5: 反转 analysis-service.test.ts 的 profile 隔离测试**

第 355-372 行测试 `"isolates cache entries across profile switches even with the same endpoint and model"` 整体替换为:

```ts
  it("shares cache entries across profiles, providers, and models for the same sentence", async () => {
    const otherProfile = {
      ...profile,
      id: "profile-2",
      name: "Second",
      baseUrl: "https://other-provider.example/v1",
      model: "another-model",
    };
    const { adapter, cache, scheduler, service } = harness([{ sentences: [rawCore(sentenceOne)] }]);

    const first = await service.analyzeCore(coreInput(), new AbortController().signal);
    adapter.completeJson.mockClear();
    scheduler.schedule.mockClear();
    const second = await service.analyzeCore(
      coreInput([sentenceOne], otherProfile),
      new AbortController().signal,
    );

    expect(first.result[0]!.modelProfileId).toBe("profile-1");
    expect(second.cacheHit).toBe(true);
    expect(second.result[0]!.modelProfileId).toBe("profile-2");
    expect(adapter.completeJson).not.toHaveBeenCalled();
    expect(scheduler.schedule).not.toHaveBeenCalled();
    expect(cache.core.size).toBe(1);
  });
```

(命中后重绑当前 profile 由既有 `validateCachedCore` 盖章逻辑保证,第 178 行测试已覆盖。)

- [ ] **Step 6: 跑两个测试文件确认通过**

Run: `npx vitest run src/background/analysis-cache.test.ts src/background/analysis-service.test.ts`
Expected: PASS(若 detail/correction 相关测试因键函数签名报编译错,按 Step 4 的新签名同步修正调用处)。

- [ ] **Step 7: 全量单测 + 提交**

Run: `npm test` → 全过。

```bash
git add src/background/analysis-cache.ts src/background/analysis-cache.test.ts src/background/analysis-service.ts src/background/analysis-service.test.ts
git commit -m "feat(cache): 缓存键收敛为句子+schema版本,跨 profile/模型/提示词版本共享

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: bypassCache 强制刷新通道

**Files:**
- Modify: `src/shared/protocol.ts:28, 191-197`
- Modify: `src/background/analysis-service.ts`(CoreBatchInput、analyzeCore)
- Modify: `src/background/service-worker.ts:304-310`(ANALYZE_CORE case)
- Modify: `src/content/session-controller.ts:72-78, 284-293, 428-457`
- Test: `src/shared/protocol.test.ts`、`src/background/analysis-service.test.ts`、`src/content/session-controller.test.ts`

- [ ] **Step 1: protocol 测试(先失败)**

在 `src/shared/protocol.test.ts` 中 ANALYZE_CORE 相关用例旁新增(沿用该文件现有的合法 ANALYZE_CORE 消息构造,记为 `validAnalyzeCore`,按文件内实际 fixture 名替换):

```ts
  it("accepts ANALYZE_CORE with bypassCache: true and rejects other values", () => {
    expect(isRequestMessage({ ...validAnalyzeCore, bypassCache: true })).toBe(true);
    expect(isRequestMessage({ ...validAnalyzeCore, bypassCache: false })).toBe(false);
    expect(isRequestMessage({ ...validAnalyzeCore, bypassCache: "yes" })).toBe(false);
  });
```

Run: `npx vitest run src/shared/protocol.test.ts` → FAIL(未知键被 hasOnlyKeys 拒绝,第一个断言不成立)。

- [ ] **Step 2: protocol 实现**

第 28 行:

```ts
  | (PageRequestBase & { type: "ANALYZE_CORE"; sentences: SentenceInput[]; bypassCache?: true })
```

第 191-197 行 ANALYZE_CORE case:

```ts
    case "ANALYZE_CORE":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "sentences", "bypassCache"]) &&
        hasPageContext(value) &&
        (value.bypassCache === undefined || value.bypassCache === true) &&
        Array.isArray(value.sentences) &&
        value.sentences.every(isSentenceInput)
      );
```

Run: `npx vitest run src/shared/protocol.test.ts` → PASS。

- [ ] **Step 3: service 测试(先失败)**

`src/background/analysis-service.test.ts` core orchestration describe 内新增:

```ts
  it("bypassCache skips reads but overwrites the cache with the fresh result", async () => {
    const { adapter, cache, scheduler, service } = harness([
      { sentences: [rawCore(sentenceOne)] },
      { sentences: [rawCore(sentenceOne)] },
    ]);
    await service.analyzeCore(coreInput(), new AbortController().signal);
    expect(cache.core.size).toBe(1);
    adapter.completeJson.mockClear();
    scheduler.schedule.mockClear();

    const outcome = await service.analyzeCore(
      { ...coreInput(), bypassCache: true },
      new AbortController().signal,
    );

    expect(outcome.cacheHit).toBe(false);
    expect(adapter.completeJson).toHaveBeenCalledTimes(1);
    expect(cache.core.size).toBe(1);
  });
```

Run: `npx vitest run src/background/analysis-service.test.ts` → FAIL(第二次命中缓存,adapter 未被调用)。

- [ ] **Step 4: service 实现**

`CoreBatchInput` 加字段:

```ts
export interface CoreBatchInput extends AnalysisInputBase {
  sentences: readonly SentenceInput[];
  priority?: Extract<SchedulerPriority, "visible-core" | "prefetch-core">;
  /** 「重新解析」置位:跳过读缓存,结果照常覆盖写回。 */
  bypassCache?: boolean;
}
```

`analyzeCore` 中读缓存一行(约 351 行)改为:

```ts
    const cached =
      input.bypassCache === true
        ? keyedSentences.map(() => undefined)
        : await Promise.all(keyedSentences.map(({ key }) => this.options.cache.getCore<unknown>(key)));
```

Run: `npx vitest run src/background/analysis-service.test.ts` → PASS。

- [ ] **Step 5: service worker 透传**

`src/background/service-worker.ts` ANALYZE_CORE case 的 analyzeCore 入参对象(约 305-309 行)加一行:

```ts
              {
                profile,
                documentId: request.documentId,
                sentences: request.sentences,
                ...(request.bypassCache === true ? { bypassCache: true } : {}),
              },
```

- [ ] **Step 6: session-controller 测试(先失败)**

`src/content/session-controller.test.ts` 第 355-369 行附近的 reanalyzeVisible 用例中,`await vi.waitFor(() => expect(subject.transport.sent).toHaveLength(2));` 之后追加断言:

```ts
    const [initial, reanalyzed] = subject.transport.sent;
    expect(initial).not.toHaveProperty("bypassCache");
    expect(reanalyzed).toMatchObject({ type: "ANALYZE_CORE", bypassCache: true });
```

Run: `npx vitest run src/content/session-controller.test.ts` → FAIL。

- [ ] **Step 7: session-controller 实现**

`BlockRecord`(第 72-78 行)加字段:

```ts
interface BlockRecord {
  candidate: CandidateBlock & { element: HTMLElement };
  sentences: SentenceRecord[];
  learningBlock: ControllerBlock;
  replacement: ControllerReplacement;
  operationVersion: number;
  /** 「重新解析」一次性标记:下次 analyzeBlock 携带 bypassCache 后即清除。 */
  bypassCacheOnce?: boolean;
}
```

`reanalyzeVisible()`(第 283-293 行)循环体在 `this.invalidateBlock(blockId);` 前加:

```ts
      const block = this.blocks.get(blockId);
      if (block !== undefined) block.bypassCacheOnce = true;
```

`analyzeBlock`(第 428 行起)开头消费标记:

```ts
  private async analyzeBlock(block: BlockRecord): Promise<void> {
    const bypassCache = block.bypassCacheOnce === true;
    block.bypassCacheOnce = undefined;
```

构造请求处(第 454-457 行)改为:

```ts
    const request = this.pageRequest({
      type: "ANALYZE_CORE",
      sentences: outgoing.map(({ input }) => input),
      ...(bypassCache ? { bypassCache: true as const } : {}),
    });
```

Run: `npx vitest run src/content/session-controller.test.ts` → PASS。

- [ ] **Step 8: 全量单测 + 提交**

Run: `npm test` → 全过。

```bash
git add src/shared/protocol.ts src/shared/protocol.test.ts src/background/analysis-service.ts src/background/analysis-service.test.ts src/background/service-worker.ts src/content/session-controller.ts src/content/session-controller.test.ts
git commit -m "feat(cache): 重新解析走 bypassCache,跳读缓存并覆盖写回

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: IndexedDB v2 迁移 + 删除 clearByProfile

**Files:**
- Modify: `src/background/analysis-cache.ts:5, 109-135, 203-231`
- Test: Create `src/background/analysis-cache.migration.test.ts`;Modify `src/background/analysis-cache.test.ts:130-141`

- [ ] **Step 1: 迁移测试(新文件,先失败)**

创建 `src/background/analysis-cache.migration.test.ts`(独立文件保证拿到全新 fake-indexeddb 注册表,避免与其他用例的已开连接互相阻塞):

```ts
import "fake-indexeddb/auto";
import { describe, expect, it } from "vitest";
import { AnalysisCache } from "./analysis-cache";

const DATABASE_NAME = "english-syntax-learning-v1";
const STORE_NAMES = ["core", "detail", "correction"] as const;

function seedVersionOneDatabase(): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, 1);
    request.addEventListener("upgradeneeded", () => {
      for (const storeName of STORE_NAMES) {
        const store = request.result.createObjectStore(storeName, { keyPath: "key" });
        store.createIndex("lastAccessedAt", "lastAccessedAt");
      }
    });
    request.addEventListener("success", () => {
      const database = request.result;
      const transaction = database.transaction("core", "readwrite");
      transaction.objectStore("core").put({
        key: "legacy-key",
        profileId: "profile-legacy",
        value: { sentenceId: "old" },
        createdAt: 1,
        lastAccessedAt: 1,
        estimatedBytes: 100,
      });
      transaction.addEventListener("complete", () => {
        database.close();
        resolve();
      });
      transaction.addEventListener("abort", () => reject(new Error("seed aborted")));
    });
    request.addEventListener("error", () => reject(request.error ?? new Error("seed failed")));
  });
}

describe("AnalysisCache database migration", () => {
  it("upgrades a version-1 database by clearing legacy stores and stays usable", async () => {
    await seedVersionOneDatabase();

    const cache = await AnalysisCache.open();

    expect(await cache.getCore("legacy-key")).toBeUndefined();
    expect(await cache.stats()).toMatchObject({ entries: 0 });
    await cache.putCore("fresh-key", "profile-a", { sentenceId: "new" });
    expect(await cache.getCore("fresh-key")).toEqual({ sentenceId: "new" });
  });
});
```

Run: `npx vitest run src/background/analysis-cache.migration.test.ts`
Expected: FAIL(现库版本仍为 1,旧记录仍可读,`getCore("legacy-key")` 非 undefined)。

- [ ] **Step 2: 实现 v2 升级**

`analysis-cache.ts` 第 5 行 `DATABASE_VERSION = 1` → `2`。`openDatabase` 的 upgradeneeded 回调(第 112-121 行)替换为:

```ts
    request.addEventListener(
      "upgradeneeded",
      () => {
        for (const storeName of STORE_NAMES) {
          // v1→v2:键构成变更(去 profile/模型/提示词维度),旧键条目永远查不到,
          // 升级时直接清空;新装用户走 createObjectStore 分支。
          if (request.result.objectStoreNames.contains(storeName)) {
            request.transaction?.objectStore(storeName).clear();
          } else {
            const store = request.result.createObjectStore(storeName, { keyPath: "key" });
            store.createIndex("lastAccessedAt", "lastAccessedAt");
          }
        }
      },
      { once: true },
    );
```

Run: `npx vitest run src/background/analysis-cache.migration.test.ts` → PASS。

- [ ] **Step 3: 删除 clearByProfile**

删除 `analysis-cache.ts` 第 203-231 行的 `clearByProfile` 方法,及 `analysis-cache.test.ts` 第 130-141 行用例 `"clears only records belonging to the selected profile"`。确认无其他引用:`grep -rn clearByProfile src/` 应为空。

- [ ] **Step 4: 全量单测 + 提交**

Run: `npm test` → 全过。

```bash
git add src/background/analysis-cache.ts src/background/analysis-cache.test.ts src/background/analysis-cache.migration.test.ts
git commit -m "feat(cache): IndexedDB v2 升级清空旧键条目,删除无调用方的 clearByProfile

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: E2E(假模型服务器)

**Files:**
- Modify: `tests/e2e/extension.spec.ts`(文件末尾追加)

- [ ] **Step 1: 写 E2E 用例**

沿用文件内既有 `startSession`/`uiMessage` 辅助与 `harness` fixture,在文件末尾追加:

```ts
test("重开会话零请求命中缓存,重新解析强制二次请求", async ({ harness }) => {
  const { page, tabId, documentId } = await startSession(harness, "dynamic-article.html");
  await expect(page.locator("[data-syntax-learning-block]").first()).toBeVisible();
  await expect
    .poll(() => harness.fakeModel.recordedOfKind("core").length)
    .toBeGreaterThan(0);
  const coldCalls = harness.fakeModel.recordedOfKind("core").length;

  // 二次会话(模拟重开页面):应全部命中缓存,core 请求数不变。
  await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId }));
  const secondDocument = `${documentId}-revisit`;
  await harness.dispatchFromUi(
    uiMessage("START_SESSION", { tabId, documentId: secondDocument }),
  );
  await expect(page.locator("[data-syntax-learning-block]").first()).toBeVisible();
  expect(harness.fakeModel.recordedOfKind("core")).toHaveLength(coldCalls);

  // 重新解析:必须绕过缓存,真实再次请求模型。
  await harness.dispatchFromUi(
    uiMessage("REANALYZE_VISIBLE", { tabId, documentId: secondDocument }),
  );
  await expect
    .poll(() => harness.fakeModel.recordedOfKind("core").length)
    .toBeGreaterThan(coldCalls);
  await expect(page.locator("[data-syntax-learning-block]").first()).toBeVisible();
});
```

(若 `recordedOfKind` 的 kind 字面量与 fake-openai-server.ts 的 `RequestKind` 定义不符,以该文件实际枚举为准调整——core 分析请求的 kind 值在 `detectKind` 中定义。)

- [ ] **Step 2: 跑 E2E**

Run: `npx playwright test`
Expected: 22 passed(21 既有 + 本条)。E2E fixture 会自动重建 dist。

- [ ] **Step 3: 提交**

```bash
git add tests/e2e/extension.spec.ts
git commit -m "test(e2e): 重开会话缓存零请求 + 重新解析强制二次请求

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: 全门禁 + 真机验收

**Files:**
- Create: `.superpowers/acceptance/verify-cache-unification.mjs`

- [ ] **Step 1: 全门禁**

Run(依次):`npm test`、`npm run lint`(恰好 1 条基线)、`npm run format:check`(不过则对触碰文件 `npx prettier --write` 后重跑)、`npm run build`、`npx playwright test`。
Expected: 全绿。

- [ ] **Step 2: 写真机验收脚本**

创建 `.superpowers/acceptance/verify-cache-unification.mjs`:以 `.superpowers/acceptance/verify-cache-revisit.mjs` 为底本完整复制,然后做如下改动(其余照抄:manifest 补丁、fixture 服务器、profile 种子、dispatchFromUi、tabIdFor、analyzeOnce 计时器):

1. 临时目录前缀改为 `syntax-ext-unify-`。
2. run 2 之前不只 reload,还要**换 profile**:重新执行 `chrome.storage.local.set` 写入新 profile(`id: 'profile-unify-b'`、`model: 'deepseek-chat'`、其余字段同 run 1),并把 `activeProfileId.v1` 指向它——模拟「删建 profile + 换模型」双重变化。
3. run 2 断言与 revisit 脚本相同:`warmMs < Math.max(3_000, coldMs / 3)`。
4. 追加 run 3(重新解析):

```js
// run 3: REANALYZE_VISIBLE 必须绕过缓存,真实再调模型(耗时回到秒级)。
const reanalyzeStartedAt = Date.now();
await dispatchFromUi({
  version: 1,
  requestId: `verify:REANALYZE:${++sessionCounter}`,
  type: 'REANALYZE_VISIBLE',
  tabId: await tabIdFor(pageUrl),
  documentId: `verify-cache-doc-${sessionCounter - 1}`, // 与 run 2 的会话一致
});
await page.locator('[data-syntax-learning-block]').first().waitFor({ state: 'detached', timeout: 30_000 })
  .catch(() => {}); // 页面恢复原文的瞬间可能极短,允许错过
await page
  .locator('.component')
  .filter({ has: page.locator('.role', { hasText: '并列连词' }) })
  .first()
  .waitFor({ timeout: 180_000 });
const reanalyzeMs = Date.now() - reanalyzeStartedAt;
log(`run 3 (reanalyze, must hit the real model): ${(reanalyzeMs / 1000).toFixed(1)}s`);

const reanalyzeHitsModel = reanalyzeMs >= 2_000;
console.log(`Reanalyze bypasses cache (>=2s real call): ${reanalyzeHitsModel ? 'PASS' : 'FAIL'}`);
process.exit(cacheWorks && reanalyzeHitsModel ? 0 : 1);
```

安全要求不变:key 只从 `DEEPSEEK_API_KEY` 读、日志 `key <masked>`。

- [ ] **Step 3: 跑真机验收**

Run: `DEEPSEEK_API_KEY="$(source ~/.secrets 2>/dev/null; echo "$DEEPSEEK_API_KEY")" node .superpowers/acceptance/verify-cache-unification.mjs`
Expected: run 1 冷启动秒级;run 2(换 profile + 换模型)≈0s PASS;run 3 重新解析 ≥2s PASS,退出码 0。

- [ ] **Step 4: 提交验收脚本**

```bash
git add .superpowers/acceptance/verify-cache-unification.mjs
git commit -m "test(acceptance): 真机验证换 profile/模型仍命中缓存、重新解析强制刷新

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Plan 自审记录

- Spec 覆盖:§1 键构成→Task 1;§2 旁路→Task 2;§3 兜底(不动,Task 1 Step 5 断言重绑仍生效);§4 迁移→Task 3;§5 清理→Task 3 Step 3;§6 测试→各 Task 内嵌 + Task 4/5。
- 类型一致性:`bypassCache?: true`(协议)与 `bypassCache?: boolean`(CoreBatchInput)刻意不同——协议层收紧到字面量 true,服务层放宽便于内部构造;`normalizedSentenceText`、`bypassCacheOnce` 命名全文一致。
- 已知松动点(实现者按现场调整,不算偏离计划):protocol.test.ts 的 fixture 名、fake server 的 RequestKind 字面量、analysis-service.ts 因签名变化产生的编译错清理。
