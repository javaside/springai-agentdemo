# 缓存导入导出与纯缓存查看 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 分析缓存可导出为 JSON 文件、导入时本地优先合并;popup 在未配置模型时提供「查看缓存」入口,纯缓存渲染已覆盖页面。

**Architecture:** 选项页直连 IndexedDB(复用 `AnalysisCache`,新增批量导出/导入方法),新模块 `cache-transfer.ts` 负责文件格式与校验;service worker 在无 profile 时改走 `CachedAnalysisService` 新增的纯缓存 `lookupCore`/`lookupDetail`,content 侧新增 `skipped` 句子相位与 `renderSkipped` 渲染,popup 状态机加 cache-only 分支。

**Tech Stack:** TypeScript + Chrome MV3、IndexedDB、Vitest(fake-indexeddb / happy-dom)、Playwright E2E。

**Spec:** `docs/superpowers/specs/2026-07-20-cache-import-export-design.md`

**约束(全任务生效):**

- 工作目录 `english-syntax-extension/`,分支 `codex/english-syntax-extension-next`,**不合并主干**。
- 门禁模块内跑:`npm test`、`npm run lint`(基线恰好 1 个错误:`src/options/options.test.ts:167` no-unnecessary-type-assertion,**不要修它、不要新增**)、`npm run format:check`、`npm run build`、`npx playwright test`。
- 提交信息中文主题 + 尾行 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- 真机验收脚本放 `.superpowers/acceptance/`(已 gitignore,不提交),API key 只从环境变量读、日志脱敏。

**文件结构总览:**

| 文件                                                                                          | 动作  | 职责                                                                                 |
| --------------------------------------------------------------------------------------------- | ----- | ------------------------------------------------------------------------------------ |
| `src/background/analysis-cache.ts`                                                            | 改    | 新增 `exportEntries` / `importEntries`(批量、单事务、本地优先)                       |
| `src/options/cache-transfer.ts`                                                               | 建    | 导出文件构造、导入解析/校验/合并计数(纯逻辑)                                         |
| `src/options/options.ts`                                                                      | 改    | 导出/导入按钮 + 直连 `AnalysisCache` 的依赖注入                                      |
| `src/shared/errors.ts`                                                                        | 改    | 新增 `NO_CACHE` 错误码                                                               |
| `src/shared/protocol.ts`                                                                      | 改    | `SessionStatus.skipped?`、`CORE_RESULT.cacheOnly?`、`isSessionComplete` 计入 skipped |
| `src/background/analysis-service.ts`                                                          | 改    | `lookupCore` / `lookupDetail` 纯缓存方法                                             |
| `src/background/service-worker.ts`                                                            | 改    | 无 profile 分支、`NO_CACHE` 文案、`isStatus` 接受 skipped                            |
| `src/content/learning-block.ts`                                                               | 改    | `renderSkipped` + `.sentence-skipped` 样式                                           |
| `src/content/session-controller.ts`                                                           | 改    | `skipped` 相位、cacheOnly 响应处理、全跳过块不替换                                   |
| `src/popup/popup.ts`                                                                          | 改    | 无配置时「查看缓存」状态机分支                                                       |
| 各对应 `*.test.ts` + `src/options/cache-transfer.test.ts`(建) + `tests/e2e/extension.spec.ts` | 改/建 | 单测与 E2E                                                                           |

---

### Task 1: AnalysisCache 批量导出/导入方法

**Files:**

- Modify: `src/background/analysis-cache.ts`
- Test: `src/background/analysis-cache.test.ts`

- [ ] **Step 1: Write the failing tests**

在 `src/background/analysis-cache.test.ts` 末尾(现有 describe 内)追加:

```ts
describe("transfer entries", () => {
  it("exports key/value pairs of one store without bookkeeping fields", async () => {
    const cache = await AnalysisCache.open();
    await cache.putCore("a".repeat(64), "profile-x", { components: [1] });
    await cache.putDetail("b".repeat(64), "profile-x", { structures: [] });

    const core = await cache.exportEntries("core");
    const detail = await cache.exportEntries("detail");

    expect(core).toEqual([{ key: "a".repeat(64), value: { components: [1] } }]);
    expect(detail).toEqual([{ key: "b".repeat(64), value: { structures: [] } }]);
  });

  it("imports only missing keys and never overwrites local entries", async () => {
    const cache = await AnalysisCache.open();
    await cache.putCore("a".repeat(64), "profile-x", { local: true });

    const outcome = await cache.importEntries(
      "core",
      [
        { key: "a".repeat(64), value: { imported: true } },
        { key: "c".repeat(64), value: { imported: true } },
      ],
      "imported",
    );

    expect(outcome).toEqual({ added: 1, skipped: 1 });
    expect(await cache.getCore("a".repeat(64))).toEqual({ local: true });
    expect(await cache.getCore("c".repeat(64))).toEqual({ imported: true });
  });

  it("enforces the byte limit once after a bulk import", async () => {
    const cache = await AnalysisCache.open({ limitBytes: 600 });
    const outcome = await cache.importEntries(
      "core",
      [
        { key: "d".repeat(64), value: { pad: "x".repeat(10) } },
        { key: "e".repeat(64), value: { pad: "y".repeat(10) } },
        { key: "f".repeat(64), value: { pad: "z".repeat(10) } },
      ],
      "imported",
    );

    expect(outcome.added).toBe(3);
    const stats = await cache.stats();
    expect(stats.estimatedBytes).toBeLessThanOrEqual(600);
  });
});
```

注意:该文件已用 fake-indexeddb,不需要新的环境配置。若现有测试的数据库名/打开方式有 beforeEach 清理约定,沿用它。

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/background/analysis-cache.test.ts`
Expected: FAIL,`exportEntries is not a function`。

- [ ] **Step 3: Implement the methods**

在 `src/background/analysis-cache.ts` 中,`CoreCacheKeyInput` 定义之前加类型:

```ts
/** 可导入导出的 store(correction 与页面实例绑定,跨人不可命中,不参与共享)。 */
export type TransferStoreName = "core" | "detail";

export interface TransferEntry {
  key: string;
  value: unknown;
}

export interface ImportOutcome {
  added: number;
  skipped: number;
}
```

在 `AnalysisCache` 类里 `stats()` 之前加两个方法:

```ts
async exportEntries(storeName: TransferStoreName): Promise<TransferEntry[]> {
  const transaction = this.database.transaction(storeName, "readonly");
  const done = transactionDone(transaction);
  const records = await requestResult<CacheRecord<unknown>[]>(
    transaction.objectStore(storeName).getAll(),
  );
  await done;
  return records.map(({ key, value }) => ({ key, value }));
}

/** 本地优先合并:已有键跳过,只写缺失键;整批一个事务,最后统一执行一次 LRU 限额。 */
async importEntries(
  storeName: TransferStoreName,
  entries: readonly TransferEntry[],
  profileId: string,
): Promise<ImportOutcome> {
  const transaction = this.database.transaction(storeName, "readwrite");
  const done = transactionDone(transaction);
  const store = transaction.objectStore(storeName);
  let added = 0;
  for (const entry of entries) {
    const existing = await requestResult<CacheRecord<unknown> | undefined>(store.get(entry.key));
    if (existing !== undefined) continue;
    const timestamp = this.nextTimestamp();
    const record: CacheRecord<unknown> = {
      key: entry.key,
      profileId,
      value: entry.value,
      createdAt: timestamp,
      lastAccessedAt: timestamp,
      estimatedBytes: estimateBytes(entry.value),
    };
    store.put(record);
    added += 1;
  }
  await done;
  await this.enforceLimit();
  return { added, skipped: entries.length - added };
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/background/analysis-cache.test.ts`
Expected: PASS(全部,含既有用例)。

- [ ] **Step 5: Commit**

```bash
git add src/background/analysis-cache.ts src/background/analysis-cache.test.ts
git commit -m "feat: 分析缓存支持批量导出与本地优先批量导入" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: cache-transfer 文件格式模块

**Files:**

- Create: `src/options/cache-transfer.ts`
- Test: `src/options/cache-transfer.test.ts`(新建)

- [ ] **Step 1: Write the failing tests**

新建 `src/options/cache-transfer.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { CORE_SCHEMA_VERSION } from "../shared/versions";
import type { TransferEntry, TransferStoreName } from "../background/analysis-cache";
import {
  CACHE_FILE_FORMAT,
  CACHE_FILE_FORMAT_VERSION,
  exportCacheFile,
  importCacheFile,
  type CacheTransferPort,
} from "./cache-transfer";

const KEY_A = "a".repeat(64);
const KEY_B = "b".repeat(64);

function fakePort(initial: Partial<Record<TransferStoreName, TransferEntry[]>> = {}): {
  port: CacheTransferPort;
  written: Record<TransferStoreName, TransferEntry[]>;
} {
  const stores: Record<TransferStoreName, TransferEntry[]> = {
    core: [...(initial.core ?? [])],
    detail: [...(initial.detail ?? [])],
  };
  const written: Record<TransferStoreName, TransferEntry[]> = { core: [], detail: [] };
  return {
    written,
    port: {
      exportEntries: (store) => Promise.resolve([...stores[store]]),
      importEntries: (store, entries) => {
        const existing = new Set(stores[store].map(({ key }) => key));
        const added = entries.filter(({ key }) => !existing.has(key));
        stores[store].push(...added);
        written[store].push(...added);
        return Promise.resolve({ added: added.length, skipped: entries.length - added.length });
      },
    },
  };
}

function validFile(): Record<string, unknown> {
  return {
    format: CACHE_FILE_FORMAT,
    formatVersion: CACHE_FILE_FORMAT_VERSION,
    schemaVersion: CORE_SCHEMA_VERSION,
    exportedAt: "2026-07-20T00:00:00.000Z",
    core: [{ key: KEY_A, value: { components: [] } }],
    detail: [{ key: KEY_B, value: { structures: [] } }],
  };
}

describe("exportCacheFile", () => {
  it("builds a header plus core and detail entries, without correction", async () => {
    const { port } = fakePort({
      core: [{ key: KEY_A, value: { components: [] } }],
      detail: [{ key: KEY_B, value: { structures: [] } }],
    });
    const file = await exportCacheFile(port, () => new Date("2026-07-20T08:00:00Z"));

    expect(file).toEqual({
      format: CACHE_FILE_FORMAT,
      formatVersion: CACHE_FILE_FORMAT_VERSION,
      schemaVersion: CORE_SCHEMA_VERSION,
      exportedAt: "2026-07-20T08:00:00.000Z",
      core: [{ key: KEY_A, value: { components: [] } }],
      detail: [{ key: KEY_B, value: { structures: [] } }],
    });
    expect("correction" in file).toBe(false);
  });
});

describe("importCacheFile", () => {
  it("merges valid entries and reports added/skipped/invalid counts", async () => {
    const { port } = fakePort({ core: [{ key: KEY_A, value: { local: true } }] });
    const file = validFile();
    (file.core as unknown[]).push(
      { key: "not-hex", value: {} },
      { key: "c".repeat(64), value: "not-an-object" },
    );

    const report = await importCacheFile(port, JSON.stringify(file));

    expect(report).toEqual({ ok: true, added: 1, skipped: 1, invalid: 2 });
  });

  it("is idempotent: importing the same file twice skips everything", async () => {
    const { port } = fakePort();
    const text = JSON.stringify(validFile());
    await importCacheFile(port, text);

    const second = await importCacheFile(port, text);

    expect(second).toEqual({ ok: true, added: 0, skipped: 2, invalid: 0 });
  });

  it("rejects the whole file when it is not JSON", async () => {
    const { port, written } = fakePort();
    expect(await importCacheFile(port, "{oops")).toEqual({ ok: false, reason: "not-json" });
    expect(written.core).toHaveLength(0);
  });

  it("rejects the whole file when the format header does not match", async () => {
    const { port } = fakePort();
    const file = validFile();
    file.format = "something-else";
    expect(await importCacheFile(port, JSON.stringify(file))).toEqual({
      ok: false,
      reason: "bad-format",
    });
  });

  it("rejects the whole file when the schema version differs", async () => {
    const { port, written } = fakePort();
    const file = validFile();
    file.schemaVersion = CORE_SCHEMA_VERSION + 1;
    expect(await importCacheFile(port, JSON.stringify(file))).toEqual({
      ok: false,
      reason: "schema-mismatch",
    });
    expect(written.core).toHaveLength(0);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/options/cache-transfer.test.ts`
Expected: FAIL,模块不存在。

- [ ] **Step 3: Implement the module**

新建 `src/options/cache-transfer.ts`:

```ts
import type { ImportOutcome, TransferEntry, TransferStoreName } from "../background/analysis-cache";
import { CORE_SCHEMA_VERSION } from "../shared/versions";

export const CACHE_FILE_FORMAT = "english-syntax-cache";
export const CACHE_FILE_FORMAT_VERSION = 1;
/** 导入条目统一记账用的 profileId(仅统计/排查,不参与键)。 */
export const IMPORTED_PROFILE_ID = "imported";

export interface CacheTransferPort {
  exportEntries(storeName: TransferStoreName): Promise<TransferEntry[]>;
  importEntries(
    storeName: TransferStoreName,
    entries: readonly TransferEntry[],
    profileId: string,
  ): Promise<ImportOutcome>;
}

export interface CacheExportFile {
  format: typeof CACHE_FILE_FORMAT;
  formatVersion: typeof CACHE_FILE_FORMAT_VERSION;
  schemaVersion: number;
  exportedAt: string;
  core: TransferEntry[];
  detail: TransferEntry[];
}

export type ImportFailureReason = "not-json" | "bad-format" | "schema-mismatch";

export type ImportReport =
  | { ok: true; added: number; skipped: number; invalid: number }
  | { ok: false; reason: ImportFailureReason };

const HEX_KEY = /^[0-9a-f]{64}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isTransferEntry(value: unknown): value is TransferEntry {
  return (
    isRecord(value) &&
    typeof value.key === "string" &&
    HEX_KEY.test(value.key) &&
    isRecord(value.value)
  );
}

export async function exportCacheFile(
  cache: CacheTransferPort,
  now: () => Date = () => new Date(),
): Promise<CacheExportFile> {
  return {
    format: CACHE_FILE_FORMAT,
    formatVersion: CACHE_FILE_FORMAT_VERSION,
    schemaVersion: CORE_SCHEMA_VERSION,
    exportedAt: now().toISOString(),
    core: await cache.exportEntries("core"),
    detail: await cache.exportEntries("detail"),
  };
}

export async function importCacheFile(
  cache: CacheTransferPort,
  text: string,
): Promise<ImportReport> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { ok: false, reason: "not-json" };
  }
  if (
    !isRecord(parsed) ||
    parsed.format !== CACHE_FILE_FORMAT ||
    parsed.formatVersion !== CACHE_FILE_FORMAT_VERSION ||
    !Array.isArray(parsed.core) ||
    !Array.isArray(parsed.detail)
  ) {
    return { ok: false, reason: "bad-format" };
  }
  // 旧 schema 的条目在当前键空间下永远查不到,静默跳过不如整体拒绝并明说。
  if (parsed.schemaVersion !== CORE_SCHEMA_VERSION) {
    return { ok: false, reason: "schema-mismatch" };
  }

  let added = 0;
  let skipped = 0;
  let invalid = 0;
  for (const storeName of ["core", "detail"] as const) {
    const candidates = parsed[storeName] as unknown[];
    const valid = candidates.filter(isTransferEntry);
    invalid += candidates.length - valid.length;
    const outcome = await cache.importEntries(storeName, valid, IMPORTED_PROFILE_ID);
    added += outcome.added;
    skipped += outcome.skipped;
  }
  return { ok: true, added, skipped, invalid };
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/options/cache-transfer.test.ts`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/options/cache-transfer.ts src/options/cache-transfer.test.ts
git commit -m "feat: 缓存导出文件格式与本地优先导入合并逻辑" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 选项页导出/导入 UI

**Files:**

- Modify: `src/options/options.ts`
- Test: `src/options/options.test.ts`(**不要碰第 167 行的既有 lint 基线**;若插入代码导致行号漂移,确认 lint 错误数仍恰为 1)

- [ ] **Step 1: Write the failing tests**

在 `src/options/options.test.ts` 的 `dependencies()` 工厂里追加两个新依赖的默认实现(加到 `confirm` 之后、`...overrides` 之前):

```ts
    exportCacheFile: vi.fn(() =>
      Promise.resolve({
        format: "english-syntax-cache" as const,
        formatVersion: 1 as const,
        schemaVersion: 1,
        exportedAt: "2026-07-20T00:00:00.000Z",
        core: [],
        detail: [],
      }),
    ),
    importCacheFile: vi.fn(() =>
      Promise.resolve({ ok: true as const, added: 2, skipped: 1, invalid: 0 }),
    ),
```

文件末尾追加用例:

```ts
describe("cache import/export", () => {
  it("renders export and import controls inside the cache panel", async () => {
    await createOptionsPage(root(), dependencies());

    expect(document.querySelector("[data-action='export-cache']")?.textContent).toBe("导出缓存");
    expect(document.querySelector("[data-action='import-cache']")?.textContent).toBe("导入缓存");
    const fileInput = document.querySelector<HTMLInputElement>("[data-import-input]");
    expect(fileInput?.type).toBe("file");
    expect(fileInput?.accept).toBe(".json,application/json");
  });

  it("shows merge counts and refreshes stats after a successful import", async () => {
    const subject = dependencies();
    await createOptionsPage(root(), subject);

    const fileInput = document.querySelector<HTMLInputElement>("[data-import-input]")!;
    const file = new File([JSON.stringify({ any: "thing" })], "cache.json", {
      type: "application/json",
    });
    Object.defineProperty(fileInput, "files", { value: [file] });
    fileInput.dispatchEvent(new Event("change"));

    await vi.waitFor(() =>
      expect(document.body.textContent).toContain("新增 2 条，已有跳过 1 条，无效丢弃 0 条"),
    );
    expect(subject.importCacheFile).toHaveBeenCalledWith(JSON.stringify({ any: "thing" }));
    expect(subject.getCacheStats).toHaveBeenCalledTimes(2);
  });

  it("maps every import failure reason to a Chinese message", async () => {
    for (const [reason, message] of [
      ["not-json", "不是有效的 JSON"],
      ["bad-format", "文件格式不符"],
      ["schema-mismatch", "schema 版本不匹配"],
    ] as const) {
      const subject = dependencies({
        importCacheFile: vi.fn(() => Promise.resolve({ ok: false as const, reason })),
      });
      await createOptionsPage(root(), subject);
      const fileInput = document.querySelector<HTMLInputElement>("[data-import-input]")!;
      Object.defineProperty(fileInput, "files", { value: [new File(["x"], "cache.json")] });
      fileInput.dispatchEvent(new Event("change"));
      await vi.waitFor(() => expect(document.body.textContent).toContain(message));
    }
  });
});
```

导出按钮触发真实 Blob 下载(`URL.createObjectURL` + anchor click),happy-dom 下不稳定,导出路径由 E2E(Task 7)覆盖;单测只断言按钮存在与 `exportCacheFile` 被调用即可——如需断言调用,用:

```ts
it("invokes the export dependency when 导出缓存 is clicked", async () => {
  const subject = dependencies();
  await createOptionsPage(root(), subject);
  const urlSpy = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:fake");
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);

  document.querySelector<HTMLButtonElement>("[data-action='export-cache']")!.click();

  await vi.waitFor(() => expect(subject.exportCacheFile).toHaveBeenCalledOnce());
  expect(urlSpy).toHaveBeenCalledOnce();
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/options/options.test.ts`
Expected: 新用例 FAIL(缺依赖字段导致 TS 报错也算失败信号;先补 Step 3 的接口再看断言失败也可)。

- [ ] **Step 3: Implement UI and wiring**

`src/options/options.ts` 改动四处:

(a) 顶部 import 增加:

```ts
import { AnalysisCache } from "../background/analysis-cache";
import {
  exportCacheFile,
  importCacheFile,
  type CacheExportFile,
  type ImportReport,
} from "./cache-transfer";
```

(b) `OptionsDependencies` 增加两个成员(`confirm` 之后):

```ts
exportCacheFile: () => Promise<CacheExportFile>;
importCacheFile: (text: string) => Promise<ImportReport>;
```

(c) 缓存区 UI:在 `clearButton` 声明后加:

```ts
const exportButton = element("button", "options-page__secondary", "导出缓存");
exportButton.type = "button";
exportButton.dataset.action = "export-cache";
const importButton = element("button", "options-page__secondary", "导入缓存");
importButton.type = "button";
importButton.dataset.action = "import-cache";
const importInput = element("input") as HTMLInputElement;
importInput.type = "file";
importInput.accept = ".json,application/json";
importInput.dataset.importInput = "";
importInput.hidden = true;
```

并把 `cacheSection.append(...)` 改为:

```ts
cacheSection.append(
  cacheHeading,
  cacheStats,
  cacheLimitLabel,
  cacheLimit,
  cacheHint,
  clearButton,
  exportButton,
  importButton,
  importInput,
  clearStatus,
);
```

(d) 事件与文案:在 `clearButton.addEventListener(...)` 之后加:

```ts
const refreshStats = async (): Promise<void> => {
  const stats = await dependencies.getCacheStats();
  cacheStats.textContent = `${stats.entries} 条，估算占用 ${cacheSize(stats.estimatedBytes)}`;
};

const importFailureMessage = (reason: ImportFailureReason): string => {
  switch (reason) {
    case "not-json":
      return "导入失败：文件不是有效的 JSON。";
    case "bad-format":
      return "导入失败：文件格式不符，不是本扩展导出的缓存文件。";
    case "schema-mismatch":
      return "导入失败：缓存 schema 版本不匹配，请让对方升级扩展后重新导出。";
  }
};

exportButton.addEventListener("click", () => {
  void (async () => {
    try {
      const file = await dependencies.exportCacheFile();
      const blob = new Blob([JSON.stringify(file)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const anchor = element("a");
      anchor.href = url;
      anchor.download = `english-syntax-cache-${file.exportedAt.slice(0, 10).replaceAll("-", "")}.json`;
      anchor.click();
      URL.revokeObjectURL(url);
      clearStatus.textContent = `已导出 ${file.core.length + file.detail.length} 条缓存。`;
    } catch {
      clearStatus.textContent = "导出失败，请重试。";
    }
  })();
});

importButton.addEventListener("click", () => importInput.click());
importInput.addEventListener("change", () => {
  const file = importInput.files?.[0];
  if (file === undefined) return;
  importInput.value = "";
  void (async () => {
    try {
      const report = await dependencies.importCacheFile(await file.text());
      if (!report.ok) {
        clearStatus.textContent = importFailureMessage(report.reason);
        return;
      }
      clearStatus.textContent = `导入完成：新增 ${report.added} 条，已有跳过 ${report.skipped} 条，无效丢弃 ${report.invalid} 条。`;
      await refreshStats();
    } catch {
      clearStatus.textContent = "导入失败：读取或写入缓存时出错，请重试。";
    }
  })();
});
```

`ImportFailureReason` 需要加入 (a) 的 import。

(e) `runtimeDependencies()` 增加直连库的实现(选项页与 SW 同源同库;懒打开、复用一个连接):

```ts
let cachePromise: Promise<AnalysisCache> | undefined;
const openCache = (): Promise<AnalysisCache> =>
  (cachePromise ??= (async () =>
    AnalysisCache.open({ limitBytes: await repository.getCacheLimitBytes() }))());
```

放在 `runtimeDependencies` 函数体内 `repository` 声明之后,返回对象里加:

```ts
    exportCacheFile: async () => exportCacheFile(await openCache()),
    importCacheFile: async (text) => importCacheFile(await openCache(), text),
```

- [ ] **Step 4: Run tests + type check**

Run: `npx vitest run src/options/options.test.ts src/options/cache-transfer.test.ts && npm run build`
Expected: PASS + 构建成功。

- [ ] **Step 5: Run lint(确认基线仍为恰好 1 个错误)**

Run: `npm run lint`
Expected: 只有 `src/options/options.test.ts` 的 no-unnecessary-type-assertion 1 处。

- [ ] **Step 6: Commit**

```bash
git add src/options/options.ts src/options/options.test.ts
git commit -m "feat: 选项页缓存导出/导入按钮与合并结果提示" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 协议扩展 + 纯缓存查找服务 + SW 无 profile 分支

**Files:**

- Modify: `src/shared/errors.ts`、`src/shared/protocol.ts`、`src/background/analysis-service.ts`、`src/background/service-worker.ts`
- Test: `src/background/analysis-service.test.ts`、`src/background/service-worker.test.ts`

- [ ] **Step 1: Write the failing tests(analysis-service)**

`src/background/analysis-service.test.ts` 追加(沿用该文件既有的 fixture 构造方式——有现成的 `profile`/`sentence`/合法 core 分析构造器就复用,下面以现有测试同款工厂为准改写):

```ts
describe("cache-only lookup", () => {
  it("lookupCore returns only cache hits and never touches the scheduler", async () => {
    // 先用正常 analyzeCore 写入一句的缓存(照抄本文件现有 happy-path 用例的安排),
    // 然后对「已缓存句 + 未缓存句」调 lookupCore:
    const results = await service.lookupCore([cachedSentence, missingSentence]);
    expect(results.map(({ sentenceId }) => sentenceId)).toEqual([cachedSentence.sentenceId]);
    expect(scheduler.schedule).not.toHaveBeenCalled(); // lookup 之后新增的调用为 0
  });

  it("lookupDetail returns undefined on a miss and the analysis on a hit", async () => {
    expect(await service.lookupDetail({ sentence: missingSentence, focus })).toBeUndefined();
    // 写入 detail 缓存后(照抄现有 analyzeDetail 用例)再查:
    const hit = await service.lookupDetail({ sentence: cachedSentence, focus });
    expect(hit?.sentenceId).toBe(cachedSentence.sentenceId);
  });
});
```

具体的 fixture 名以该测试文件现状为准;要点断言:命中句返回、未命中句缺席、scheduler 零新增调用、detail miss 返回 `undefined`。

- [ ] **Step 2: Write the failing tests(service-worker)**

`src/background/service-worker.test.ts` 追加(沿用该文件的 dependencies stub 与 dispatch 辅助):

```ts
describe("cache-only sessions without a model profile", () => {
  it("ANALYZE_CORE returns cache hits with cacheOnly instead of CONFIG_MISSING", async () => {
    // getActiveProfile → undefined;analysisService.lookupCore mock 返回 [analysisA]
    const response = await dispatch(coreRequestWithSentences([sentenceA, sentenceB]));
    expect(response).toMatchObject({ type: "CORE_RESULT", cacheOnly: true });
    expect((response as { analyses: unknown[] }).analyses).toHaveLength(1);
  });

  it("ANALYZE_DETAIL returns NO_CACHE on a miss and the analysis on a hit", async () => {
    // lookupDetail → undefined:
    const miss = await dispatch(detailRequest());
    expect(miss).toMatchObject({ type: "ERROR", error: { code: "NO_CACHE" } });
    // lookupDetail → detailAnalysis:
    const hit = await dispatch(detailRequest());
    expect(hit).toMatchObject({ type: "DETAIL_RESULT" });
  });

  it("REANALYZE_WITH_FEEDBACK still requires a profile", async () => {
    const response = await dispatch(feedbackRequest());
    expect(response).toMatchObject({ type: "ERROR", error: { code: "CONFIG_MISSING" } });
  });
});
```

注意:该文件现有 analysisService stub 需要补 `lookupCore`/`lookupDetail` 两个 vi.fn,否则接口不完整会先炸类型。

- [ ] **Step 3: Run tests to verify they fail**

Run: `npx vitest run src/background/analysis-service.test.ts src/background/service-worker.test.ts`
Expected: FAIL(方法/字段不存在)。

- [ ] **Step 4: Implement shared changes**

`src/shared/errors.ts`:`ERROR_CODES` 数组的 `"REQUEST_CANCELLED"` 之后加 `"NO_CACHE"`。

`src/shared/protocol.ts`:

- `SessionStatus` 加成员:`skipped?: number;`(注释:纯缓存会话中未命中而保持原文的句数)。
- `isSessionComplete` 改为:

```ts
export function isSessionComplete(status: SessionStatus): boolean {
  return (
    status.discovered > 0 &&
    status.queued === 0 &&
    status.ready + status.failed + (status.skipped ?? 0) >= status.discovered
  );
}
```

- `ResponseMessage` 的 CORE_RESULT 分支改为:

```ts
  | (MessageBase & { type: "CORE_RESULT"; analyses: CoreAnalysis[]; cacheOnly?: true })
```

- [ ] **Step 5: Implement lookup methods**

`src/background/analysis-service.ts`:

- `AnalysisService` 接口加:

```ts
  /** 纯缓存查找:只回命中,不进调度器、不需要 profile(popup「查看缓存」模式)。 */
  lookupCore(sentences: readonly SentenceInput[]): Promise<CoreAnalysis[]>;
  lookupDetail(input: DetailLookupInput): Promise<DetailAnalysis | undefined>;
```

- 类型(`DetailInput` 旁):

```ts
export interface DetailLookupInput {
  sentence: SentenceInput;
  focus: TokenRange;
}
```

- 常量(类外):

```ts
/** 纯缓存模式没有真实 profile,命中值统一改写为该占位 id。 */
const CACHE_ONLY_PROFILE_ID = "cached";
```

- `CachedAnalysisService` 实现(放 `analyzeDetail` 之后):

```ts
  async lookupCore(sentences: readonly SentenceInput[]): Promise<CoreAnalysis[]> {
    const results: CoreAnalysis[] = [];
    for (const sentence of sentences) {
      const key = await this.coreKey(sentence);
      const value = validateCachedCore(
        await this.options.cache.getCore<unknown>(key),
        sentence,
        CACHE_ONLY_PROFILE_ID,
      );
      if (value !== undefined) results.push(value);
    }
    return results;
  }

  async lookupDetail(input: DetailLookupInput): Promise<DetailAnalysis | undefined> {
    const key = await this.detailKey(input);
    return validateCachedDetail(
      await this.options.cache.getDetail<unknown>(key),
      input.sentence,
      input.focus,
      CACHE_ONLY_PROFILE_ID,
    );
  }
```

- 私有 `detailKey` 放宽签名(实现不变):

```ts
  private detailKey(input: { sentence: SentenceInput; focus: TokenRange }): Promise<string> {
```

- [ ] **Step 6: Implement SW branches**

`src/background/service-worker.ts`:

- `ERROR_MESSAGES` 加(该表其余为英文,但此文案直接展示在详解面板中,沿用 `UNSAFE_CONTENT_BLOCK` 用中文的先例):

```ts
  NO_CACHE: "该成分暂无缓存详解，配置模型后可获取",
```

- `isStatus` 的返回表达式补一条:

```ts
    (status.skipped === undefined ||
      (Number.isSafeInteger(status.skipped) && (status.skipped as number) >= 0)) &&
```

- `ANALYZE_CORE` case 的 `CONFIG_MISSING` 行替换为:

```ts
const profile = await profileFor(request.tabId);
if (profile === undefined) {
  // 纯缓存查看:只回命中,未命中句子由 content 侧保持原文;无 key 可脱敏,直接返回。
  const analyses = await dependencies.analysisService.lookupCore(request.sentences);
  return {
    version: MESSAGE_VERSION,
    requestId: request.requestId,
    type: "CORE_RESULT",
    analyses,
    cacheOnly: true,
  };
}
```

- `ANALYZE_DETAIL` case 同位置替换为:

```ts
const profile = await profileFor(request.tabId);
if (profile === undefined) {
  const analysis = await dependencies.analysisService.lookupDetail({
    sentence: request.sentence,
    focus: request.focus,
  });
  return analysis === undefined
    ? errorResponse(request.requestId, "NO_CACHE")
    : {
        version: MESSAGE_VERSION,
        requestId: request.requestId,
        type: "DETAIL_RESULT",
        analysis,
      };
}
```

- `REANALYZE_WITH_FEEDBACK` 保持 `CONFIG_MISSING` 不动。
- `defaultDependencies()` 的 analysisService 懒代理加两条:

```ts
      lookupCore: async (...arguments_) => (await getRuntime()).analysisService.lookupCore(...arguments_),
      lookupDetail: async (...arguments_) =>
        (await getRuntime()).analysisService.lookupDetail(...arguments_),
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `npx vitest run src/background/ src/shared/`
Expected: PASS(含既有全部用例;若 protocol.test.ts 有 isSessionComplete 用例受影响,按新语义修正)。

- [ ] **Step 8: Commit**

```bash
git add src/shared/errors.ts src/shared/protocol.ts src/background/analysis-service.ts src/background/service-worker.ts src/background/analysis-service.test.ts src/background/service-worker.test.ts
git commit -m "feat: 无模型配置时 service worker 走纯缓存查找" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: content 侧 skipped 相位与渲染

**Files:**

- Modify: `src/content/learning-block.ts`、`src/content/session-controller.ts`
- Test: `src/content/session-controller.test.ts`、`src/content/learning-block.test.ts`(若存在;不存在则由 session-controller 测试经 fake block 覆盖)

- [ ] **Step 1: Write the failing tests**

`src/content/session-controller.test.ts` 追加(沿用该文件的 fake transport/block 基建;其 fake `ControllerBlock` 需补 `renderSkipped` 记录函数):

```ts
describe("cache-only responses", () => {
  it("renders hits, keeps misses as plain skipped text, and reports skipped in status", async () => {
    // transport 对 ANALYZE_CORE 返回 { type:"CORE_RESULT", analyses:[analysisA], cacheOnly:true }
    // 块内两句:A 命中、B 未命中。
    await controller.start();
    // …触发可视分析(照抄现有用例的 queueVisibleBlock 驱动方式)…

    expect(fakeBlock.renderCore).toHaveBeenCalledTimes(1);
    expect(fakeBlock.renderSkipped).toHaveBeenCalledWith(sentenceB.sentenceId, sentenceB.text);
    expect(fakeBlock.renderFailure).not.toHaveBeenCalled();
    expect(lastStatus.skipped).toBe(1);
    expect(lastStatus.failed).toBe(0);
    expect(isSessionComplete(lastStatus)).toBe(true);
  });

  it("does not replace a block whose sentences are all cache misses", async () => {
    // transport 返回 { analyses: [], cacheOnly: true }
    await controller.start();
    // …触发分析…
    expect(fakeReplacement.show).not.toHaveBeenCalled();
    expect(fakeReplacement.showPartialFailure).not.toHaveBeenCalled();
  });

  it("still fails missing sentences when the response is not cacheOnly", async () => {
    // transport 返回 { analyses: [], }(无 cacheOnly)→ 走既有失败路径
    // 断言 renderFailure 被调、status.failed 计数(即回归保护既有行为)。
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/content/session-controller.test.ts`
Expected: FAIL(`renderSkipped` 不存在 / skipped 未上报)。

- [ ] **Step 3: Implement learning-block**

`src/content/learning-block.ts`:

- `STYLES` 中 `.sentence-failure` 规则旁加:

```css
.sentence-skipped {
  inline-size: 100%;
  max-inline-size: 100%;
  margin-block: 0.35em;
  overflow-wrap: anywhere;
}
```

- `renderFailure` 之后加方法:

```ts
  /** 纯缓存模式未命中:按原文渲染,无错误样式、无重试按钮。 */
  renderSkipped(sentenceId: string, sentence: string): void {
    this.#assertExpected(sentenceId);
    const section = createElement("section", "sentence-skipped");
    section.dataset.sentenceId = sentenceId;
    section.append(createElement("span", "original-sentence", sentence));
    this.#placeSentenceSection(sentenceId, section);
    this.#resolvedSentenceIds.add(sentenceId);
  }
```

- [ ] **Step 4: Implement session-controller**

`src/content/session-controller.ts`:

- `SentencePhase` 联合加 `| "skipped"`(注释:纯缓存会话未命中,保持原文)。
- `ControllerBlock` 接口 `renderFailure` 旁加:

```ts
  renderSkipped(sentenceId: string, sentence: string): void;
```

- `status` getter 加:

```ts
      skipped: records.filter(({ phase }) => phase === "skipped").length,
```

- `analyzeBlock` 中 `analysis === undefined` 分支改为:

```ts
const cacheOnly = response.type === "CORE_RESULT" && response.cacheOnly === true;
// …(循环内)…
if (analysis === undefined) {
  if (cacheOnly) {
    block.learningBlock.renderSkipped(sentence.input.sentenceId, sentence.input.text);
    this.transition(sentence, "skipped");
    continue;
  }
  // …既有 failure 逻辑不动…
}
```

(`const cacheOnly` 放在 `const analyses = ...` 那行旁边。)

- `finishBlock` 开头加全跳过守卫:

```ts
// 纯缓存会话整块未命中:保持页面原文,不做替换。
if (block.sentences.length > 0 && block.sentences.every(({ phase }) => phase === "skipped")) {
  this.emitStatus();
  return;
}
```

- `queueVisibleBlock` 的完成判断与 `unfinishedBlockIds` 的进行中判断都把 `"skipped"` 纳入「已完成」:

```ts
if (
  block.sentences.every(
    ({ phase }) => phase === "ready" || phase === "failed" || phase === "skipped",
  )
)
  return;
```

```ts
block.sentences.some(({ phase }) => phase !== "ready" && phase !== "failed" && phase !== "skipped");
```

- `requestDetail` 的错误渲染:`NO_CACHE` 只显示文案不带错误码前缀。把 `renderError` 调用处的消息表达式改为:

```ts
        response !== undefined && response.type === "ERROR" && response.error.code === "NO_CACHE"
          ? response.error.message
          : response === undefined
            ? "REQUEST_CANCELLED"
            : responseErrorMessage(response),
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `npx vitest run src/content/`
Expected: PASS(既有用例的 fake block 若因接口新增 `renderSkipped` 报类型错,给 fake 补 `renderSkipped: vi.fn()`)。

- [ ] **Step 6: Commit**

```bash
git add src/content/learning-block.ts src/content/session-controller.ts src/content/session-controller.test.ts
git commit -m "feat: 纯缓存会话未命中句保持原文并计入 skipped" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: popup「查看缓存」入口

**Files:**

- Modify: `src/popup/popup.ts`
- Test: `src/popup/popup.test.ts`

- [ ] **Step 1: Write the failing tests**

`src/popup/popup.test.ts`:该文件现有「无 profile」相关用例(若断言主按钮为「去配置模型」)按新行为改写,并追加:

```ts
describe("cache-only mode (no profile configured)", () => {
  const noProfile = (overrides: Partial<PopupDependencies> = {}) =>
    dependencies({
      listProfiles: vi.fn(() => Promise.resolve([])),
      getActiveProfileId: vi.fn(() => Promise.resolve(undefined)),
      ...overrides,
    });

  it("shows 查看缓存 on supported pages and starts a session on click", async () => {
    const subject = noProfile();
    await createPopupPage(root(), subject);

    expect(primary().textContent).toBe("查看缓存");
    expect(primary().disabled).toBe(false);
    expect(subline().textContent).toContain("尚未配置模型");
    primary().click();

    await vi.waitFor(() =>
      expect(subject.sendCommand).toHaveBeenCalledWith("START_SESSION", expect.anything()),
    );
    expect(subject.openOptions).not.toHaveBeenCalled();
  });

  it("shows cache-hit progress wording while a cache-only session runs", async () => {
    await createPopupPage(
      root(),
      noProfile({
        getStatus: vi.fn(() =>
          Promise.resolve(status({ state: "running", discovered: 5, ready: 2, skipped: 1 })),
        ),
      }),
    );

    expect(primary().textContent).toBe("缓存命中 2/5 句（点击暂停）");
  });

  it("still blocks unsupported pages when no profile exists", async () => {
    await createPopupPage(
      root(),
      noProfile({ getActiveTab: vi.fn(() => Promise.resolve({ id: 7, url: "chrome://about" })) }),
    );

    expect(primary().disabled).toBe(true);
    expect(subline().textContent).toContain("此页面不支持句法解析");
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run src/popup/popup.test.ts`
Expected: 新用例 FAIL(主按钮仍是「去配置模型」)。

- [ ] **Step 3: Implement popup state machine**

`src/popup/popup.ts` 的 `createPopupPage` 内:

- `modelLine` 声明后加:

```ts
const cacheOnly = profile === undefined;
const cacheOnlyHint = "尚未配置模型，可查看已缓存的解析；点 ⚙︎ 配置模型后可解析新内容。";
```

- `renderStatus` 整体调整为「先判 unsupported,再统一状态机」:

```ts
const renderStatus = (): void => {
  subline.textContent = cacheOnly ? cacheOnlyHint : modelLine;
  secondary.remove();
  if (!supported) {
    primary.textContent = cacheOnly ? "查看缓存" : "开始学习";
    primary.disabled = true;
    subline.textContent = "此页面不支持句法解析，请切换到普通 http/https 网页。";
    return;
  }
  primary.disabled = false;
  const startLabel = cacheOnly ? "查看缓存" : "开始学习";
  if (status.state === "running" && isSessionComplete(status)) {
    primary.textContent = "恢复网页原文";
    command = "STOP_SESSION";
  } else if (status.state === "running") {
    primary.textContent = cacheOnly
      ? `缓存命中 ${status.ready}/${status.discovered} 句（点击暂停）`
      : `解析中… ${status.ready + status.failed}/${status.discovered}（点击暂停）`;
    command = "PAUSE_SESSION";
  } else if (status.state === "paused") {
    primary.textContent = "继续学习";
    command = "START_SESSION";
  } else {
    primary.textContent = startLabel;
    command = "START_SESSION";
  }
  if (status.state === "running" || status.state === "paused") {
    if (command !== "STOP_SESSION") {
      secondary.disabled = false;
      primary.after(secondary);
    }
  }
};
```

(原「profile === undefined → 去配置模型」分支删除;齿轮按钮已承担配置入口。)

- `runCommand` 删除开头的 `if (profile === undefined) { dependencies.openOptions(); return; }` 三行。

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/popup/popup.test.ts`
Expected: PASS(既有「guides setup」类用例已在 Step 1 同步改写)。

- [ ] **Step 5: Commit**

```bash
git add src/popup/popup.ts src/popup/popup.test.ts
git commit -m "feat: popup 未配置模型时提供查看缓存入口" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: E2E 测试

**Files:**

- Modify: `tests/e2e/extension.spec.ts`(文件内注释与测试标题一律英文,沿用现有约定)

- [ ] **Step 1: Write the export/import roundtrip test**

追加(沿用文件内 `startSession`/`uiMessage` 辅助;`dynamic-article.html` 为素材):

```ts
test("exported cache re-imports after a wipe and restores analyses without new model calls", async ({
  harness,
}) => {
  await harness.seedProfiles([defaultProfile(harness)], "profile-a");
  const { page, tabId, documentId } = await startSession(harness, "dynamic-article.html");
  await expect(page.locator("[data-syntax-learning-block]").first()).toBeVisible({
    timeout: 20_000,
  });
  const coldCalls = harness.fakeModel.recordedOfKind("core").length;
  expect(coldCalls).toBeGreaterThan(0);
  await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId }));

  const optionsPage = await harness.context.newPage();
  await optionsPage.goto(harness.optionsUrl);
  const [download] = await Promise.all([
    optionsPage.waitForEvent("download"),
    optionsPage.click("[data-action='export-cache']"),
  ]);
  const exportedPath = await download.path();

  optionsPage.once("dialog", (dialog) => void dialog.accept());
  await optionsPage.click("[data-action='clear-cache']");
  await expect(optionsPage.locator("text=缓存已清空")).toBeVisible();

  await optionsPage.setInputFiles("[data-import-input]", exportedPath);
  await expect(optionsPage.locator("text=导入完成")).toBeVisible({ timeout: 10_000 });
  await expect(optionsPage.locator("text=无效丢弃 0 条")).toBeVisible();
  await optionsPage.close();

  const revisitDocument = `${documentId}-after-import`;
  await harness.dispatchFromUi(uiMessage("START_SESSION", { tabId, documentId: revisitDocument }));
  await expect(page.locator("[data-syntax-learning-block]").first()).toBeVisible({
    timeout: 20_000,
  });
  expect(harness.fakeModel.recordedOfKind("core")).toHaveLength(coldCalls);
  await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId: revisitDocument }));
});
```

`defaultProfile(harness)` 指该文件为其他用例构造 fake-model profile 的既有方式(baseUrl 指向 `harness.fakeModel`),照抄即可。注意「导出按钮触发 anchor 下载」需要 Playwright 允许 download:若 `waitForEvent("download")` 拿不到,给 `optionsPage` 加 `acceptDownloads`(launchPersistentContext 默认为 true,一般无需改)。

- [ ] **Step 2: Write the cache-only viewing test**

```ts
test("a page with cached analyses renders in cache-only mode without any profile", async ({
  harness,
}) => {
  await harness.seedProfiles([defaultProfile(harness)], "profile-a");
  const { page, tabId, documentId } = await startSession(harness, "dynamic-article.html");
  await expect(page.locator("[data-syntax-learning-block]").first()).toBeVisible({
    timeout: 20_000,
  });
  const warmCalls = harness.fakeModel.recordedOfKind("core").length;
  await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId }));

  await harness.serviceWorker.evaluate(async () => {
    await chrome.storage.local.set({ "profiles.v1": [], "activeProfileId.v1": "" });
  });

  const cacheOnlyDocument = `${documentId}-cache-only`;
  await harness.dispatchFromUi(
    uiMessage("START_SESSION", { tabId, documentId: cacheOnlyDocument }),
  );
  await expect(page.locator("[data-syntax-learning-block]").first()).toBeVisible({
    timeout: 20_000,
  });
  expect(harness.fakeModel.recordedOfKind("core")).toHaveLength(warmCalls);
  await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId: cacheOnlyDocument }));
});
```

同时检查既有测试 `"the popup guides setup when no model profile exists"`(约 512 行):其断言若依赖「去配置模型」主按钮,改为断言主按钮显示「查看缓存」、副线包含「尚未配置模型」、齿轮仍可打开设置页。

- [ ] **Step 3: Run the two new tests**

Run: `npx playwright test -g "exported cache|cache-only mode|guides setup"`
Expected: PASS。

- [ ] **Step 4: Run the full suites**

Run: `npm test && npx playwright test && npm run lint && npm run format:check && npm run build`
Expected: 单测全绿、E2E 全绿(现有 22 条 + 新增)、lint 恰 1 基线错误、format 干净、构建成功。

- [ ] **Step 5: Commit**

```bash
git add tests/e2e/extension.spec.ts
git commit -m "test: 缓存导出导入回灌与无配置纯缓存查看 E2E" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: 真机验收(可选,由主会话执行,不派子代理、不提交)

**Files:**

- Create(磁盘,gitignored): `.superpowers/acceptance/verify-cache-share.mjs`

- [ ] **Step 1: 写脚本**

以 `.superpowers/acceptance/verify-cache-unification.mjs` 为模板(同款 manifest patch、fixture server、profile seed、fetch 探针),流程:

1. 浏览器 profile A:seed 真实 DeepSeek profile(key 从 `DEEPSEEK_API_KEY` 读,日志 `key <masked>`),分析 `compound-article.html`(真实调模型,秒级);打开 options 页点「导出缓存」,`waitForEvent("download")` 存到临时路径。
2. 关 A,起全新浏览器 profile B:**不 seed 任何 profile**;打开 options 页 `setInputFiles("[data-import-input]", 导出文件)`,断言「导入完成」;装 fetch 探针;dispatchFromUi `START_SESSION`;断言 `[data-syntax-learning-block]` 渲染、探针计数为 0(缓存命中类断言可用墙钟 ≈0s 佐证,是否调模型一律以 fetch 计数为准)。

- [ ] **Step 2: 运行**

Run: `source ~/.secrets && node .superpowers/acceptance/verify-cache-share.mjs`
Expected: 末尾两行 `Import restores cache: PASS` / `Cache-only viewing without profile & without model calls: PASS`,退出码 0。

- [ ] **Step 3: 清理**

脚本自删临时目录;不 git add(`.superpowers` 已 gitignore)。

---

## Self-Review 记录

- **Spec 覆盖**:§1 文件格式→Task 2;§2 导出/导入流程→Task 1+2+3(批量单事务即"逐条处理"的实现载体,LRU 收尾、幂等、profileId="imported" 均落地);§3 UI 与错误处理→Task 3(三分类计数行、四类错误文案、读时校验兜底本来就在);§4 纯缓存查看→Task 4(SW/lookup/NO_CACHE)+5(skipped/renderSkipped/全跳过不替换)+6(popup 入口与「缓存命中 X/Y 句」、`ready+skipped=discovered` 即 isSessionComplete 新语义);§5 测试→各 Task Step 1 + Task 7 两条 E2E + Task 8 真机;取舍(不做签名、不补调模型)无需代码。
- **类型一致性**:`TransferStoreName`/`TransferEntry`/`ImportOutcome`(Task 1)在 Task 2/3 引用一致;`DetailLookupInput`(Task 4)与 SW 调用点一致;`renderSkipped(sentenceId, sentence)`(Task 5)与 ControllerBlock 接口一致;`skipped?`(Task 4 协议)与 Task 5 status getter、Task 6 popup 文案一致。
- **占位符**:Task 4/5 部分单测以「沿用该文件既有 fixture」表述——这是刻意的:两个测试文件各 900+/700+ 行,自带成熟的 stub 基建,照抄具体工厂名反而易错;要点断言已完整给出。其余步骤均为完整代码。
