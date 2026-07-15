# Chrome 英语句法学习扩展 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个 Chrome Manifest V3 扩展，把可视英文正文转换为“中文成分标签—带下划线英文—对应中文翻译”的可逆学习视图，并支持用户自配 OpenAI-compatible 模型、缓存、详细语法和纠错重试。

**Architecture:** 扩展作为独立的 `english-syntax-extension` npm 工程加入仓库，不进入 Maven `<modules>`。Content Script 只负责正文识别、分句分词和 Shadow DOM 学习块；Manifest V3 Service Worker 独占 API Key、模型请求、校验、队列和 IndexedDB 缓存；Popup 与 Options 通过带版本的判别联合消息访问后台。

**Tech Stack:** Chrome Manifest V3、Chrome 120+、Node.js 22.20+、TypeScript 6.0.3、Vite 8.1.4、vite-plugin-web-extension 4.5.1、Vitest 4.1.10、Happy DOM 20.10.6、fake-indexeddb 6.2.5、Playwright 1.61.1、ESLint 10.7.0、Prettier 3.9.5。

**设计文档：** `docs/superpowers/specs/2026-07-15-chrome-english-syntax-learning-extension-design.md`

## Global Constraints

- Chrome 最低版本为 120；只实现 Manifest V3 和 Chromium。
- 不增加 Java/Spring 后端，也不修改根 `pom.xml` 的 `<modules>`。
- Content Script 永远不能读取 API Key、Authorization 头或完整模型配置。
- 远程 Base URL 只允许 HTTPS；HTTP 只允许 `localhost` 和 `127.0.0.1`。
- 页面内容和模型文本只能通过 `textContent`/文本节点渲染，禁止 `innerHTML`、`eval` 和远程代码。
- 原正文块在完整学习块可显示前保持可见；停止后必须恢复原节点，不重建网页原节点。
- 核心成分必须按 Token 顺序、不重叠，并恰好覆盖所有非标点 Token。
- 初次分析只请求核心结构；详细语法只在用户点击成分后请求。
- 默认每批最多 6 句、4,000 输入 Token、2 个并发请求、45 秒超时、远程错误最多重试两次。
- 默认 IndexedDB 缓存上限为 50 MB；缓存键必须包含模型、提示词版本和协议版本，不能包含 API Key。
- 每个 Task 必须先得到失败测试，再写最小实现，通过本 Task 相关测试后单独 commit。

---

## File Map

```text
english-syntax-extension/
├── manifest.json                         MV3 权限、入口、可选 Host 权限
├── package.json                          npm 脚本与固定依赖
├── vite.config.ts                        Web Extension 构建
├── vite.content.config.ts                固定文件名 IIFE Content Script 构建
├── vitest.config.ts                      单元/DOM 测试
├── playwright.config.ts                  Chromium 扩展 E2E
├── tsconfig.json
├── eslint.config.js
├── .prettierrc.json
├── .gitignore
├── README.md
├── src/
│   ├── shared/
│   │   ├── grammar.ts                    Token、成分和标签枚举
│   │   ├── protocol.ts                   跨上下文消息联合类型
│   │   ├── errors.ts                     可序列化错误
│   │   └── versions.ts                   消息/提示词/缓存版本
│   ├── language/
│   │   ├── segmenter.ts                  分句、分词、稳定 ID
│   │   └── analysis-validator.ts          核心/详细结果校验
│   ├── background/
│   │   ├── config-repository.ts           模型配置与受信存储
│   │   ├── base-url.ts                    URL 与 Host 权限校验
│   │   ├── prompts.ts                     核心/详细/修复提示词
│   │   ├── openai-compatible-adapter.ts   Chat Completions 适配
│   │   ├── request-scheduler.ts           批处理、并发、重试、取消
│   │   ├── analysis-cache.ts              IndexedDB、缓存键、LRU
│   │   ├── analysis-service.ts            缓存→模型→校验编排
│   │   └── service-worker.ts              消息和 contextMenus 入口
│   ├── content/
│   │   ├── document-scanner.ts            安全正文候选识别
│   │   ├── viewport-observer.ts           上下各一屏增量观察
│   │   ├── learning-block.ts              Shadow DOM 三层组件
│   │   ├── block-replacement.ts           隐藏/恢复原节点
│   │   ├── session-controller.ts           页面会话状态机
│   │   └── content-script.ts              注入入口与消息处理
│   ├── popup/                             当前标签页控制和进度
│   └── options/                           模型配置和缓存管理
├── tests/
│   ├── fixtures/pages/                    新闻/博客/Wiki/动态 DOM
│   ├── e2e/extension.spec.ts
│   ├── e2e/fixtures.ts
│   └── support/fake-openai-server.ts
```

---

### Task 1: Manifest V3 工程骨架与构建门禁

**Files:**
- Create: `english-syntax-extension/package.json`
- Create: `english-syntax-extension/manifest.json`
- Create: `english-syntax-extension/vite.config.ts`
- Create: `english-syntax-extension/vite.content.config.ts`
- Create: `english-syntax-extension/vitest.config.ts`
- Create: `english-syntax-extension/tsconfig.json`
- Create: `english-syntax-extension/eslint.config.js`
- Create: `english-syntax-extension/.prettierrc.json`
- Create: `english-syntax-extension/.gitignore`
- Create: `english-syntax-extension/src/background/service-worker.ts`
- Create: `english-syntax-extension/src/content/content-script.ts`
- Create: `english-syntax-extension/src/popup/popup.html`
- Create: `english-syntax-extension/src/popup/popup.ts`
- Create: `english-syntax-extension/src/options/options.html`
- Create: `english-syntax-extension/src/options/options.ts`
- Create: `english-syntax-extension/src/shared/manifest.test.ts`
- Generate: `english-syntax-extension/package-lock.json`

**Interfaces:**
- Produces: 可构建的 MV3 包；固定入口 `service-worker.ts`、`content-script.ts`、`popup.html`、`options.html`。

- [ ] **Step 1: 写 Manifest 失败测试**

Create `src/shared/manifest.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import manifest from "../../manifest.json";

describe("manifest", () => {
  it("uses temporary page access and optional model hosts", () => {
    expect(manifest.manifest_version).toBe(3);
    expect(manifest.permissions).toEqual(
      expect.arrayContaining(["activeTab", "scripting", "storage", "contextMenus"]),
    );
    expect(manifest).not.toHaveProperty("host_permissions");
    expect(manifest.optional_host_permissions).toEqual([
      "https://*/*",
      "http://localhost/*",
      "http://127.0.0.1/*",
    ]);
  });
});
```

- [ ] **Step 2: 创建依赖与配置并确认测试先失败**

Create `package.json` with exact versions:

```json
{
  "name": "english-syntax-extension",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "engines": { "node": ">=22.20.0" },
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build && vite build --config vite.content.config.ts",
    "test": "vitest run",
    "test:watch": "vitest",
    "test:e2e": "playwright test",
    "lint": "eslint .",
    "format:check": "prettier --check ."
  },
  "devDependencies": {
    "@eslint/js": "10.0.1",
    "@playwright/test": "1.61.1",
    "@types/chrome": "0.1.31",
    "eslint": "10.7.0",
    "fake-indexeddb": "6.2.5",
    "happy-dom": "20.10.6",
    "prettier": "3.9.5",
    "typescript": "6.0.3",
    "typescript-eslint": "8.64.0",
    "vite": "8.1.4",
    "vite-plugin-web-extension": "4.5.1",
    "vitest": "4.1.10"
  }
}
```

Create `vite.config.ts`:

```ts
import { defineConfig } from "vite";
import webExtension from "vite-plugin-web-extension";

export default defineConfig({
  plugins: [webExtension()],
  build: { sourcemap: true, target: "chrome120" },
});
```

Create `vite.content.config.ts` so runtime injection always has a known, import-free file:

```ts
import { defineConfig } from "vite";

export default defineConfig({
  build: {
    target: "chrome120",
    sourcemap: true,
    outDir: "dist",
    emptyOutDir: false,
    lib: {
      entry: "src/content/content-script.ts",
      name: "EnglishSyntaxContentScript",
      formats: ["iife"],
      fileName: () => "content-script.js",
    },
  },
});
```

Create `vitest.config.ts`:

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: { include: ["src/**/*.test.ts"], restoreMocks: true },
});
```

Create `tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2023",
    "useDefineForClassFields": true,
    "lib": ["ES2023", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "noImplicitOverride": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "skipLibCheck": true,
    "noEmit": true,
    "types": ["chrome", "vitest/globals"]
  },
  "include": [
    "src",
    "tests",
    "vite.config.ts",
    "vite.content.config.ts",
    "vitest.config.ts",
    "playwright.config.ts"
  ]
}
```

Create `eslint.config.js`:

```js
import eslint from "@eslint/js";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist/**", "coverage/**", "playwright-report/**", "test-results/**"] },
  eslint.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  {
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
  },
);
```

Create `.prettierrc.json`:

```json
{ "printWidth": 100, "semi": true, "singleQuote": false, "trailingComma": "all" }
```

Create `.gitignore`:

```gitignore
node_modules/
dist/
coverage/
playwright-report/
test-results/
.playwright-profile/
```

Run: `cd english-syntax-extension && npm install && npm test`
Expected: FAIL because `manifest.json` does not exist.

- [ ] **Step 3: 创建 Manifest 与最小入口**

Create `manifest.json`:

```json
{
  "manifest_version": 3,
  "name": "英语句法伴读",
  "version": "0.1.0",
  "minimum_chrome_version": "120",
  "description": "按句子成分对齐英文结构与中文翻译。",
  "permissions": ["activeTab", "scripting", "storage", "contextMenus"],
  "optional_host_permissions": [
    "https://*/*",
    "http://localhost/*",
    "http://127.0.0.1/*"
  ],
  "background": { "service_worker": "src/background/service-worker.ts", "type": "module" },
  "action": { "default_popup": "src/popup/popup.html", "default_title": "英语句法伴读" },
  "options_ui": { "page": "src/options/options.html", "open_in_tab": true }
}
```

Create both HTML pages using this exact structure, changing only title, heading and script path:

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>英语句法伴读</title>
  </head>
  <body>
    <main id="app"><h1>英语句法伴读</h1></main>
    <script type="module" src="./popup.ts"></script>
  </body>
</html>
```

For Options use `设置 · 英语句法伴读`, heading `模型设置`, and `./options.ts`. Each entry script obtains `#app`, throws a clear startup error if absent, and does not mutate the page yet. Create `service-worker.ts` that calls:

```ts
void chrome.storage.local.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
```

Create `content-script.ts` that only sets `document.documentElement.dataset.syntaxLearningExtension = "ready"`. Do not add icons in the first implementation; Chrome supplies the default development icon.

- [ ] **Step 4: 验证构建门禁**

Run: `cd english-syntax-extension && npm test && npm run build && npm run lint && npm run format:check`
Expected: all commands exit 0; `dist/manifest.json` exists, references bundled local assets only, and `dist/content-script.js` contains no top-level `import`.

- [ ] **Step 5: Commit**

```bash
git add english-syntax-extension
git commit -m "build(extension): scaffold Manifest V3 project"
```

---

### Task 2: 共享领域类型、版本与消息协议

**Files:**
- Create: `english-syntax-extension/src/shared/versions.ts`
- Create: `english-syntax-extension/src/shared/grammar.ts`
- Create: `english-syntax-extension/src/shared/errors.ts`
- Create: `english-syntax-extension/src/shared/protocol.ts`
- Create: `english-syntax-extension/src/shared/grammar.test.ts`
- Create: `english-syntax-extension/src/shared/protocol.test.ts`

**Interfaces:**
- Produces: `GrammarRole`, `Token`, `CoreAnalysis`, `DetailAnalysis`, `ExtensionError`, `RequestMessage`, `ResponseMessage`, `assertNever`.

- [ ] **Step 1: 写角色与协议失败测试**

```ts
import { describe, expect, it } from "vitest";
import { GRAMMAR_LABELS, GrammarRole } from "./grammar";
import { isRequestMessage } from "./protocol";

it("maps every role to a Chinese label", () => {
  expect(Object.keys(GRAMMAR_LABELS).sort()).toEqual(Object.values(GrammarRole).sort());
  expect(GRAMMAR_LABELS[GrammarRole.SUBJECT]).toBe("主语");
});

it("rejects an unversioned content message", () => {
  expect(isRequestMessage({ type: "ANALYZE_CORE" })).toBe(false);
});
```

Run: `npm test -- src/shared/grammar.test.ts src/shared/protocol.test.ts`
Expected: FAIL with missing modules.

- [ ] **Step 2: 定义领域类型**

In `versions.ts` export exact constants:

```ts
export const MESSAGE_VERSION = 1 as const;
export const CORE_SCHEMA_VERSION = 1 as const;
export const CORE_PROMPT_VERSION = 1 as const;
export const DETAIL_PROMPT_VERSION = 1 as const;
```

In `grammar.ts`, define the 14 roles from the design, their Chinese labels, and:

```ts
export interface Token {
  id: number;
  text: string;
  start: number;
  end: number;
  leadingWhitespace: string;
  punctuation: boolean;
}

export interface CoreComponent {
  startToken: number;
  endToken: number;
  role: GrammarRole;
  translation: string;
}

export interface CoreAnalysis {
  schemaVersion: 1;
  sentenceId: string;
  components: CoreComponent[];
  modelProfileId: string;
}
```

Define `DetailAnalysis` with `sentenceId`, focus closed interval, `structures`, `grammarPoints`, `explanation`, and `modelProfileId`. In `errors.ts`, define the 12 design error codes and a serializable `ExtensionError { code, message, retryable, details? }` whose details type is `Record<string, string | number | boolean>`.

- [ ] **Step 3: 定义判别联合消息**

`RequestMessage` must include exact variants: `START_SESSION`, `PAUSE_SESSION`, `STOP_SESSION`, `GET_SESSION_STATUS`, `ANALYZE_CORE`, `ANALYZE_DETAIL`, `REANALYZE_WITH_FEEDBACK`, `SWITCH_PROFILE`, `TEST_PROFILE`, `GET_CACHE_STATS`, `CLEAR_CACHE`, `PARSE_SELECTION`, `PARSE_CONTEXT_BLOCK`. Every request contains `version`, `requestId`; page requests also contain `tabId` and `documentId`.

`ResponseMessage` has `ACK`, `SESSION_STATUS`, `CORE_RESULT`, `DETAIL_RESULT`, `CACHE_STATS`, `PROFILE_TEST_RESULT`, and `ERROR`. Implement `isRequestMessage` as a strict version/type/ID guard; do not accept unknown properties as executable instructions.

- [ ] **Step 4: 验证**

Run: `npm test -- src/shared && npm run build`
Expected: PASS and no TypeScript errors.

- [ ] **Step 5: Commit**

```bash
git add english-syntax-extension/src/shared
git commit -m "feat(extension): define grammar and message contracts"
```

---

### Task 3: 确定性分句、分词与稳定句子 ID

**Files:**
- Create: `english-syntax-extension/src/language/segmenter.ts`
- Create: `english-syntax-extension/src/language/segmenter.test.ts`

**Interfaces:**
- Consumes: `Token`.
- Produces: `segmentBlock(text): SegmentedSentence[]`, `tokenize(sentence): Token[]`, `createSentenceId(input): Promise<string>`.

- [ ] **Step 1: 写失败测试**

Test these exact cases:

```ts
expect(segmentBlock("Dr. Smith arrived. He sat down.").map((s) => s.text)).toEqual([
  "Dr. Smith arrived.",
  "He sat down.",
]);

expect(tokenize("Learners don't stop.").map((t) => [t.text, t.punctuation])).toEqual([
  ["Learners", false], ["don't", false], ["stop", false], [".", true],
]);

expect(rebuildTokens(tokenize("Hello,  world!"))).toBe("Hello,  world!");
```

Also test curly apostrophes, hyphenated words, quotes, CJK punctuation after English, and identical text at different block orders producing different IDs.

Run: `npm test -- src/language/segmenter.test.ts`
Expected: FAIL with missing implementation.

- [ ] **Step 2: 实现分句和分词**

Use `Intl.Segmenter("en", { granularity: "sentence" })`, then merge a boundary when the left segment ends in one of `Mr.`, `Mrs.`, `Ms.`, `Dr.`, `Prof.`, `Sr.`, `Jr.`, `e.g.`, `i.e.`, `U.S.`. Tokenize with this Unicode expression:

```ts
const TOKEN_PATTERN = /[\p{L}\p{N}]+(?:['’\-][\p{L}\p{N}]+)*|[^\s]/gu;
```

Offsets are UTF-16 indices from `RegExpExecArray.index`; `end` is exclusive. `rebuildTokens` concatenates `leadingWhitespace + text`. `createSentenceId` returns the first 24 hex characters of SHA-256 over `sessionId\0blockId\0order\0normalizedText`.

- [ ] **Step 3: 验证**

Run: `npm test -- src/language/segmenter.test.ts && npm run build`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add english-syntax-extension/src/language/segmenter.ts english-syntax-extension/src/language/segmenter.test.ts
git commit -m "feat(extension): add deterministic sentence tokenization"
```

---

### Task 4: 核心与详细分析校验器

**Files:**
- Create: `english-syntax-extension/src/language/analysis-validator.ts`
- Create: `english-syntax-extension/src/language/analysis-validator.test.ts`

**Interfaces:**
- Consumes: untrusted JSON, requested `Token[]`, requested sentence IDs.
- Produces: `ValidationResult<CoreAnalysis[]>` and `ValidationResult<DetailAnalysis>`; invalid data never crosses this boundary.

- [ ] **Step 1: 写覆盖失败测试**

Create table tests for valid coverage, missing lexical token, overlap, reversed interval, punctuation-only component, unknown role, empty translation, extra sentence ID, HTML-like translation, and detailed output that changes the focus range. The valid baseline must assert:

```ts
const result = validateCoreBatch(raw, requests, "profile-1");
expect(result).toEqual({ ok: true, value: [expectedAnalysis] });
```

The gap case must assert error path `sentences[0].components` and message `non-punctuation token 2 is not covered`.

Run: `npm test -- src/language/analysis-validator.test.ts`
Expected: FAIL.

- [ ] **Step 2: 实现白名单解析与不变量**

Implement a small `isRecord` guard and parse every field explicitly. Reject strings containing `<script`, `<iframe`, `javascript:` or NUL; rendering is still text-only. Core validation sorts nothing: the model must already return ordered components. Verify every non-punctuation Token has count exactly 1 and every punctuation Token has count 0 or 1. Enforce translation length `<= Math.max(500, englishLength * 8)`.

Detailed validation must ensure every structure interval is inside the original sentence, the requested focus is unchanged, and `grammarPoints` contains at most 12 strings of at most 300 characters each.

- [ ] **Step 3: 验证**

Run: `npm test -- src/language/analysis-validator.test.ts && npm run build`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add english-syntax-extension/src/language/analysis-validator*
git commit -m "feat(extension): validate model syntax output"
```

---

### Task 5: 模型配置、可信存储与动态 Host 权限

**Files:**
- Create: `english-syntax-extension/src/background/base-url.ts`
- Create: `english-syntax-extension/src/background/base-url.test.ts`
- Create: `english-syntax-extension/src/background/config-repository.ts`
- Create: `english-syntax-extension/src/background/config-repository.test.ts`
- Modify: `english-syntax-extension/src/background/service-worker.ts`

**Interfaces:**
- Produces: `ModelProfile`, `normalizeBaseUrl`, `chatCompletionsUrl`, `hostPermissionPattern`, `ConfigRepository`.

- [ ] **Step 1: 写 URL 与存储失败测试**

Required URL cases:

```ts
expect(normalizeBaseUrl("https://api.deepseek.com/v1/"))
  .toBe("https://api.deepseek.com/v1");
expect(chatCompletionsUrl("https://api.deepseek.com/v1"))
  .toBe("https://api.deepseek.com/v1/chat/completions");
expect(chatCompletionsUrl("http://localhost:11434/v1/chat/completions"))
  .toBe("http://localhost:11434/v1/chat/completions");
expect(() => normalizeBaseUrl("http://api.example.com/v1")).toThrow("HTTPS");
expect(() => normalizeBaseUrl("https://user:pass@example.com/v1")).toThrow("credentials");
```

Repository tests mock `chrome.storage.local`, verify profile round-trip, active profile selection, forbidden custom header rejection, and assert serialized storage is never returned by any Content Script message.

- [ ] **Step 2: 实现配置**

Define:

```ts
export interface ModelProfile {
  id: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  headers: Record<string, string>;
  timeoutMs: number;
  jsonSchemaSupport: "unknown" | "supported" | "unsupported";
}
```

Allow timeout 5,000–120,000 ms. Reject case-insensitive forbidden headers `authorization`, `host`, `content-length`, `origin`, `x-syntax-request-id`. `ConfigRepository.listPublicProfiles()` returns `Omit<ModelProfile, "apiKey" | "headers">[]`. Storage keys are `profiles.v1` and `activeProfileId.v1`.

At Service Worker module load, await `setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" })` before registering message handlers. `requestHostPermission(profile)` requests exactly `${url.origin}/*`; do not request `https://*/*` at runtime.

- [ ] **Step 3: 验证**

Run: `npm test -- src/background/base-url.test.ts src/background/config-repository.test.ts && npm run build`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add english-syntax-extension/src/background/base-url* \
  english-syntax-extension/src/background/config-repository* \
  english-syntax-extension/src/background/service-worker.ts
git commit -m "feat(extension): secure model profile storage"
```

---

### Task 6: IndexedDB 分析缓存、缓存键与 LRU

**Files:**
- Create: `english-syntax-extension/src/background/analysis-cache.ts`
- Create: `english-syntax-extension/src/background/analysis-cache.test.ts`

**Interfaces:**
- Produces: `AnalysisCache.open`, `getCore`, `putCore`, `getDetail`, `putDetail`, `stats`, `clear`, `clearByProfile`, `enforceLimit`, `createCoreCacheKey`, `createCorrectionCacheKey`.

- [ ] **Step 1: 写失败测试**

Use `fake-indexeddb/auto`. Test exact key separation by sentence, provider origin, model, prompt version, schema version, focus interval and correction context. Assert API Keys never appear in keys. Insert three known-size records with a two-record limit and assert the least recently read record is removed. Assert `clear()` leaves mocked `chrome.storage.local` untouched.

Run: `npm test -- src/background/analysis-cache.test.ts`
Expected: FAIL.

- [ ] **Step 2: 实现缓存**

Database name: `english-syntax-learning-v1`; stores: `core`, `detail`, `correction`; keyPath: `key`; index: `lastAccessedAt`. Define:

```ts
interface CacheRecord<T> {
  key: string;
  profileId: string;
  value: T;
  createdAt: number;
  lastAccessedAt: number;
  estimatedBytes: number;
}
```

Estimate bytes with `new TextEncoder().encode(JSON.stringify(record.value)).byteLength + 256`. Hash canonical key input with SHA-256. `enforceLimit` removes oldest `lastAccessedAt` records in one readwrite transaction until the running estimate is at or below the configured byte limit.

- [ ] **Step 3: 验证**

Run: `npm test -- src/background/analysis-cache.test.ts && npm run build`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add english-syntax-extension/src/background/analysis-cache*
git commit -m "feat(extension): add bounded analysis cache"
```

---

### Task 7: OpenAI-compatible 适配器、提示词与重试调度

**Files:**
- Create: `english-syntax-extension/src/background/prompts.ts`
- Create: `english-syntax-extension/src/background/openai-compatible-adapter.ts`
- Create: `english-syntax-extension/src/background/openai-compatible-adapter.test.ts`
- Create: `english-syntax-extension/src/background/request-scheduler.ts`
- Create: `english-syntax-extension/src/background/request-scheduler.test.ts`

**Interfaces:**
- Consumes: `ModelProfile`, numbered sentence requests, `AbortSignal`.
- Produces: raw core/detail JSON; `RequestScheduler.schedule`, `pause`, `resume`, `cancelDocument`.

- [ ] **Step 1: 写适配器失败测试**

Mock `fetch` and verify the request uses `POST`, Bearer authorization, configured model, temperature 0, and no streaming. Verify `response_format.json_schema` on unknown/supported profiles. Simulate a 400 response containing `response_format is not supported`, then assert one immediate retry without `response_format` and a persisted capability downgrade. Test 401 mapping, 429 `Retry-After`, 5xx, timeout abort, malformed envelope and missing `choices[0].message.content`.

- [ ] **Step 2: 写调度器失败测试**

With fake timers, enqueue 9 requests and assert active calls never exceed 2; batch construction never exceeds 6 sentences or an injected 4,000-token estimator. Assert duplicate cache keys share one Promise. Assert 429 waits `Retry-After`; 5xx uses 500 ms then 1,000 ms plus injected deterministic jitter; 401 is not retried; `cancelDocument` rejects only matching queued/running work with `REQUEST_CANCELLED`.

- [ ] **Step 3: 实现提示词和适配器**

`buildCorePrompt` must state the 14-role enum, closed Token intervals, exact coverage rule, Chinese translation requirement, and JSON-only output. `buildRepairPrompt` includes validation errors and the invalid JSON but instructs the model not to change sentence IDs or Tokens. `buildDetailPrompt` includes only the selected sentence, verified core result and focus range.

Define adapter method:

```ts
completeJson(
  profile: ModelProfile,
  messages: readonly { role: "system" | "user"; content: string }[],
  schema: JsonSchemaSpec,
  signal: AbortSignal,
): Promise<unknown>;
```

Parse content as JSON after stripping only a single outer Markdown JSON fence; never search arbitrary prose for a JSON substring.

- [ ] **Step 4: 实现调度器**

Use an injected `fetchTask` and `sleep` for deterministic tests. Queue priority order is user retry, detail click, visible core, prefetch core. A document cancel aborts its active controllers and removes its queued items. Pausing prevents dispatch but does not abort active work.

- [ ] **Step 5: 验证**

Run: `npm test -- src/background/openai-compatible-adapter.test.ts src/background/request-scheduler.test.ts && npm run build`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add english-syntax-extension/src/background/prompts.ts \
  english-syntax-extension/src/background/openai-compatible-adapter* \
  english-syntax-extension/src/background/request-scheduler*
git commit -m "feat(extension): call and schedule compatible LLMs"
```

---

### Task 8: 安全正文识别与视口增量观察

**Files:**
- Create: `english-syntax-extension/src/content/document-scanner.ts`
- Create: `english-syntax-extension/src/content/document-scanner.test.ts`
- Create: `english-syntax-extension/src/content/viewport-observer.ts`
- Create: `english-syntax-extension/src/content/viewport-observer.test.ts`
- Create: `english-syntax-extension/tests/fixtures/pages/article.html`
- Create: `english-syntax-extension/tests/fixtures/pages/interactive.html`

**Interfaces:**
- Produces: `scanDocument(root): CandidateBlock[]`, `nearestSafeBlock(target)`, `ViewportObserver`.

- [ ] **Step 1: 写 DOM 失败测试**

Use `// @vitest-environment happy-dom`. Fixtures must include `main/article`, navigation, footer, code, form, contenteditable, hidden nodes, paragraph with link, paragraph with image/button, and a short English heading. Assert only safe `h1-h6,p,li,blockquote` nodes in the principal content are returned. Assert right-click selection bypasses 20-character minimum but never accepts editable/password content.

Viewport tests inject bounding rectangles and assert current viewport plus one viewport above/below is observed; scrolling an already-seen block does not emit it twice until `invalidate(blockId)`.

- [ ] **Step 2: 实现扫描器**

Use semantic main containers first. If absent, score ancestors by visible text length minus navigation/link density penalty. Exclusion selector is the exact design list plus `[hidden]` and `[aria-hidden="true"]`. A block is unsafe if it contains `button,input,textarea,select,video,audio,canvas,iframe,[contenteditable]` or direct media. English dominance is `englishWordCount / max(1, letterWordCount) >= 0.6`.

Assign block IDs in a `WeakMap<Element, string>`; do not write IDs into page attributes. `nearestSafeBlock` walks ancestors only to the selected principal content root.

- [ ] **Step 3: 实现视口观察器**

Use `IntersectionObserver` with `rootMargin: "100% 0px 100% 0px"`. Provide a deterministic fallback that compares `getBoundingClientRect()` on throttled scroll/resize when unavailable. Expose `disconnect()` and remove every listener.

- [ ] **Step 4: 验证**

Run: `npm test -- src/content/document-scanner.test.ts src/content/viewport-observer.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add english-syntax-extension/src/content/document-scanner* \
  english-syntax-extension/src/content/viewport-observer* \
  english-syntax-extension/tests/fixtures/pages
git commit -m "feat(extension): discover safe visible article text"
```

---

### Task 9: Shadow DOM 三层学习块与原文可逆替换

**Files:**
- Create: `english-syntax-extension/src/content/learning-block.ts`
- Create: `english-syntax-extension/src/content/learning-block.test.ts`
- Create: `english-syntax-extension/src/content/block-replacement.ts`
- Create: `english-syntax-extension/src/content/block-replacement.test.ts`

**Interfaces:**
- Produces: `<syntax-learning-block>`, `BlockReplacement.show`, `showPartialFailure`, `restore`.

- [ ] **Step 1: 写渲染失败测试**

Build a `CoreAnalysis` for the approved example. Assert Shadow DOM visual order is role, English, translation for every component; English has a semantic class for a solid underline; punctuation is present once and has no translation row. Assert no model string reaches `innerHTML` by passing `<img src=x onerror=alert(1)>` as text and checking no `img` exists.

Replacement tests must retain strict identity:

```ts
const original = document.querySelector("p")!;
replacement.show(original, block);
replacement.restore();
expect(document.querySelector("p")).toBe(original);
expect(original.hidden).toBe(false);
```

Also test a pre-existing class/style is unchanged and an original node removed by the page removes its learning sibling.

- [ ] **Step 2: 实现 Web Component**

Register once with `customElements.define`. Use an open Shadow Root for testability. Create nodes with `document.createElement` and `.textContent`. CSS uses `inline-grid` with three rows, `max-inline-size:100%`, normal wrapping, inherited font/color, 11 px minimum role text, `border-bottom: 2px solid currentColor`, keyboard-visible focus, dark-page-compatible current colors, and `prefers-reduced-motion`.

Expose methods `renderCore(sentence, tokens, analysis)`, `setDetailLoading`, `renderDetail`, `renderError`, and dispatch composed custom events `syntax-detail-request` and `syntax-reanalyze-request` with sentence/focus IDs only.

- [ ] **Step 3: 实现可逆替换**

Insert the learning block after the original. Hide original using one injected class with `display:none!important`; never write inline style. Store only original identity and inserted block. Restore removes the class and inserted node. Do not hide until all sentences in the block are either ready or represented as original-text failure rows.

- [ ] **Step 4: 验证**

Run: `npm test -- src/content/learning-block.test.ts src/content/block-replacement.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add english-syntax-extension/src/content/learning-block* \
  english-syntax-extension/src/content/block-replacement*
git commit -m "feat(extension): render reversible syntax learning blocks"
```

---

### Task 10: 后台分析服务：缓存、模型、校验与一次修复

**Files:**
- Create: `english-syntax-extension/src/background/analysis-service.ts`
- Create: `english-syntax-extension/src/background/analysis-service.test.ts`

**Interfaces:**
- Consumes: validated profile, tokenized sentences, detail focus, optional correction text.
- Produces: `analyzeCore`, `analyzeDetail`, `reanalyzeWithFeedback` with `{ result, cacheHit }`.

- [ ] **Step 1: 写编排失败测试**

Inject fakes for cache, adapter, scheduler and clock. Test:

- cache hit calls neither scheduler nor adapter;
- concurrent identical misses share one model call;
- valid raw output is cached then returned;
- invalid raw output invokes exactly one repair prompt;
- a second invalid output returns `INVALID_MODEL_OUTPUT` and is not cached;
- one invalid sentence in a batch does not discard valid siblings;
- detail cache key includes focus;
- correction uses page URL, sentence instance and feedback in a separate store;
- profile switch yields a different key.

- [ ] **Step 2: 实现服务**

Define:

```ts
export interface AnalysisService {
  analyzeCore(input: CoreBatchInput, signal: AbortSignal): Promise<CoreBatchOutcome>;
  analyzeDetail(input: DetailInput, signal: AbortSignal): Promise<DetailOutcome>;
  reanalyzeWithFeedback(input: CorrectionInput, signal: AbortSignal): Promise<CoreOutcome>;
}
```

For partial batches, map raw sentences by requested ID, validate each independently, cache successes, and repair only failures. Every returned result includes `modelProfileId`. Never cache network errors, authorization errors or cancelled work.

- [ ] **Step 3: 验证**

Run: `npm test -- src/background/analysis-service.test.ts && npm run build`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add english-syntax-extension/src/background/analysis-service*
git commit -m "feat(extension): orchestrate validated syntax analysis"
```

---

### Task 11: 页面会话状态机、增量解析与详细/纠错交互

**Files:**
- Create: `english-syntax-extension/src/content/session-controller.ts`
- Create: `english-syntax-extension/src/content/session-controller.test.ts`
- Modify: `english-syntax-extension/src/content/content-script.ts`

**Interfaces:**
- Consumes: scanner, segmenter, viewport observer, runtime transport, renderer.
- Produces: `SessionController.start`, `pause`, `resume`, `stop`, `parseSelection`, `parseContextBlock`, `status`.

- [ ] **Step 1: 写状态机失败测试**

Use fakes and assert exact transitions `discovered → cache-check → queued → requesting → validating → ready`, partial failure retention, pause behavior, stop cancellation/restoration, stale response version rejection, dynamic text mutation invalidation, detail click priority, correction feedback, and model switch not changing existing results.

Assert synchronous DOM work yields through an injected scheduler after 8 ms of accumulated work. Assert a sentence over 2,000 normalized characters is rendered as `SENTENCE_TOO_LONG` without sending a message.

- [ ] **Step 2: 实现 SessionController**

Each document gets `crypto.randomUUID()` as `documentId`; each outgoing operation has a monotonic integer version. Keep maps for blocks, sentences and pending request IDs. The controller is the source of truth if the Service Worker restarts. On port disconnect, reconnect once immediately and then with 250/500/1,000 ms delays; re-submit only unfinished sentence keys.

MutationObserver handles `childList` and `characterData`. Batch changed blocks for 100 ms, mark existing results stale, restore their original node, then rescan only those blocks. Stop disconnects observers, removes listeners, cancels document work and restores every `BlockReplacement`.

The right-click target is recorded from the page `contextmenu` event only while Content Script is active. `PARSE_CONTEXT_BLOCK` uses that target; if no target exists, return `UNSAFE_CONTENT_BLOCK` with the message “请先启动学习模式，或选中文字后解析”。Selection parsing works on first context-menu invocation using `selectionText` supplied by Chrome.

- [ ] **Step 3: 接入 Content Script**

`content-script.ts` creates one controller per `documentId`, validates every inbound message, and exposes no global containing profiles or keys. It registers component custom events for detail and correction, and relays status changes to the Service Worker.

- [ ] **Step 4: 验证**

Run: `npm test -- src/content/session-controller.test.ts && npm run build`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add english-syntax-extension/src/content/session-controller* \
  english-syntax-extension/src/content/content-script.ts
git commit -m "feat(extension): manage incremental page analysis sessions"
```

---

### Task 12: Service Worker 消息入口、Context Menu 与会话隔离

**Files:**
- Modify: `english-syntax-extension/src/background/service-worker.ts`
- Create: `english-syntax-extension/src/background/service-worker.test.ts`

**Interfaces:**
- Consumes: shared messages, ConfigRepository, AnalysisService, RequestScheduler.
- Produces: tab/document-scoped responses and context menu commands.

- [ ] **Step 1: 写入口失败测试**

Mock Chrome APIs and test:

- install creates `解析选中文本` and `解析此区域` exactly once;
- action/popup start injects `content-script` only after an active user command;
- malformed/version-mismatched messages return `ERROR`;
- sender tab mismatch is rejected;
- core/detail messages never include profile secrets in responses;
- selection context item can inject on first use;
- region context item without active recorded target returns the explicit instruction from Task 11;
- tab close and navigation call `cancelDocument`;
- 401 pauses new requests for only the affected profile.

- [ ] **Step 2: 实现入口**

Keep `Map<number, { documentId, status }>` for active tabs. Before page commands, call `chrome.scripting.executeScript({ target: { tabId }, files: ["content-script.js"] })`; Content Script initialization is idempotent. Route requests with an exhaustive switch and `assertNever`. Check `sender.tab?.id === message.tabId` for page messages.

Context menu IDs are `syntax-parse-selection` and `syntax-parse-context-block`. Use `info.selectionText` for the first. The second sends only a trigger to an already active Content Script; it never guesses the clicked DOM node.

- [ ] **Step 3: 验证**

Run: `npm test -- src/background/service-worker.test.ts && npm run build`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add english-syntax-extension/src/background/service-worker*
git commit -m "feat(extension): wire secure background orchestration"
```

---

### Task 13: Options 与 Popup 产品界面

**Files:**
- Modify: `english-syntax-extension/src/options/options.html`
- Modify: `english-syntax-extension/src/options/options.ts`
- Create: `english-syntax-extension/src/options/options.css`
- Create: `english-syntax-extension/src/options/options.test.ts`
- Modify: `english-syntax-extension/src/popup/popup.html`
- Modify: `english-syntax-extension/src/popup/popup.ts`
- Create: `english-syntax-extension/src/popup/popup.css`
- Create: `english-syntax-extension/src/popup/popup.test.ts`

**Interfaces:**
- Consumes: public profiles, profile save/test commands, cache stats, session status.
- Produces: accessible model configuration and current-tab controls.

- [ ] **Step 1: 写 UI 失败测试**

Options tests assert labels for name/Base URL/API Key/model/timeout/custom headers, password input by default, privacy warning before first save, exact-origin permission request on save/test, forbidden header inline error, connection result distinction, cache limit choices 10/50/100/200 MB and clear confirmation.

Popup tests assert no-profile onboarding, model switch, status counts, start/pause/resume/stop controls, failed sentence count, and a confirmation before `重新解析可视区域`. Test keyboard operation and visible focus.

- [ ] **Step 2: 实现 Options**

Use native form controls and DOM APIs only. Custom headers are edited as repeatable name/value rows, but saved as a validated record. Never place API Key in a data attribute, URL, log or error text. `测试连接` returns separate messages for permission, network, authentication, model and JSON capability. Cache clear requires a button-triggered confirmation dialog and does not delete profiles.

- [ ] **Step 3: 实现 Popup**

Render active profile select and exact status counters from the design. When the active tab is unsupported, disable start and explain why. Stop button text is `停止并恢复网页`. Switching profiles sends `SWITCH_PROFILE` but never sends a reanalysis command. Use one CSS namespace per page and support 320 px width, dark color scheme and 200% zoom without horizontal scrolling.

- [ ] **Step 4: 验证**

Run: `npm test -- src/options/options.test.ts src/popup/popup.test.ts && npm run build && npm run lint`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add english-syntax-extension/src/options english-syntax-extension/src/popup
git commit -m "feat(extension): add model settings and session controls"
```

---

### Task 14: Chromium E2E、教学样本、安全回归与文档

**Files:**
- Create: `english-syntax-extension/playwright.config.ts`
- Create: `english-syntax-extension/tests/e2e/fixtures.ts`
- Create: `english-syntax-extension/tests/e2e/extension.spec.ts`
- Create: `english-syntax-extension/tests/support/fake-openai-server.ts`
- Create: `english-syntax-extension/tests/fixtures/pages/dynamic-article.html`
- Create: `english-syntax-extension/tests/fixtures/teaching-sentences.json`
- Create: `english-syntax-extension/README.md`
- Modify: `README.md`

**Interfaces:**
- Produces: 可重复的真实扩展验收、模拟模型服务器和安装/隐私说明。

- [ ] **Step 1: 创建本地 OpenAI 模拟服务**

Implement a Node HTTP server bound to `127.0.0.1` on an ephemeral port. It records sanitized requests and exposes scripted responses for success, schema-unsupported 400, 401, 429 with `Retry-After`, 500, delayed timeout, invalid JSON, coverage gap, repair success and repair failure. It must never print Authorization values.

- [ ] **Step 2: 创建 Playwright 扩展 Fixture**

`fixtures.ts` runs `npm run build`, launches a persistent Chromium context with `--disable-extensions-except=<dist>` and `--load-extension=<dist>`, discovers the MV3 Service Worker extension ID, serves local fixture pages, and stops every server/context after each worker scope.

Create `playwright.config.ts` with one Chromium project, 30-second test timeout, trace on first retry, no external network dependency, and sequential execution for tests sharing Chrome permission state.

- [ ] **Step 3: 写端到端失败测试**

`extension.spec.ts` must cover:

1. save localhost model, grant exact permission, test connection;
2. start on article, verify only viewport plus margin requests;
3. verify every component has role/English/Chinese rows and no whole-sentence translation;
4. scroll and observe incremental request;
5. reload same fixture and assert cache hit causes zero model calls;
6. click a component and assert one lazy detail call;
7. switch profile and assert current rendered model provenance remains unchanged;
8. reanalyze one sentence with feedback and assert correction-specific cache;
9. pause prevents dispatch, stop restores the exact original element;
10. 401, 429, invalid structure and partial batch failures remain isolated;
11. dynamic article mutation invalidates only the changed block;
12. a script-like translation appears as literal text and creates no executable element;
13. Content Script cannot call `chrome.storage.local.get` after trusted-context restriction.

Run before final wiring: `npm run test:e2e`
Expected: FAIL with missing behavior or fixture wiring, not a skipped suite.

- [ ] **Step 4: 建立教学样本并完成回归**

`teaching-sentences.json` contains at least 36 fixed English sentences: three each for basic SVO, copular, passive, attribute, adverbial, complement, noun clauses, relative clauses, adverbial clauses, non-finite forms, inversion/ellipsis/emphasis, and quotation/abbreviation/long sentence. Each record includes `id`, `category`, `text`, and `requiredLexicalTokenCount`; CI validates tokenization and coverage invariants，不断言唯一的模型答案。

Run: `npm test && npm run test:e2e && npm run build && npm run lint && npm run format:check`
Expected: all commands exit 0; no skipped security/E2E test.

- [ ] **Step 5: 写使用与隐私文档**

`english-syntax-extension/README.md` must document Node requirement, install, build, `chrome://extensions` unpacked loading, profile examples for DeepSeek and localhost Ollama, permissions, text sent to providers, API Key storage risk, cache clearing, unsupported pages, testing commands and troubleshooting for 401/429/CORS/invalid JSON.

Add `english-syntax-extension` to the root README project tree and detailed-module links. State explicitly that it is an independent npm project and is not built by Maven.

- [ ] **Step 6: Final verification**

Run:

```bash
cd english-syntax-extension
npm ci
npm test
npm run test:e2e
npm run build
npm run lint
npm run format:check
git diff --check
```

Expected: every command exits 0; `dist/manifest.json` is MV3; Git diff has no whitespace error.

- [ ] **Step 7: Commit**

```bash
git add english-syntax-extension README.md
git commit -m "test(extension): verify end-to-end learning workflow"
```

---

## Completion Checklist

- [ ] Run the complete verification command block from Task 14.
- [ ] Load `dist` manually in Chrome 120+ and verify one public article plus one localhost model.
- [ ] Confirm `git status --short` contains no generated `dist`, coverage, Playwright report or temporary browser profile.
- [ ] Compare every design requirement in the linked spec to a passing test or documented manual check.
- [ ] Use `superpowers:requesting-code-review` for an independent requirement and quality review.
- [ ] Use `superpowers:verification-before-completion` before any completion claim.
- [ ] Use `superpowers:finishing-a-development-branch` to choose merge, PR or cleanup after tests pass.
