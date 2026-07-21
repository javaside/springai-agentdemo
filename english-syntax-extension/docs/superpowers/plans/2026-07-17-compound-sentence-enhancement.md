# 复合句处理增强实现计划（并列分句建模 + 详解面板可视化）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 spec `docs/superpowers/specs/2026-07-17-compound-sentence-enhancement-design.md` 落地两处增强：并列句在正文标注为「并列分句①②…+ 并列连词」（新增 2 个 GrammarRole、提示词复合句规则、CORE_PROMPT_VERSION 1→2），详解面板在原有内容之上新增可视化标注区（两行式内联块 + 序号解释列表）。

**Architecture:** 数据模型只加 2 个枚举成员（`COORDINATE_CLAUSE`/`CONJUNCTION`），`GRAMMAR_LABELS` 与 `ROLE_COLORS` 是 `Readonly<Record<GrammarRole, string>>`，漏写编译不过；校验器角色集合从 `Object.values(GrammarRole)` 派生自动接受新角色。分句编号只在 `SyntaxLearningBlock.renderCore` 渲染期计数，不进数据不进缓存。详解标注区在 `renderDetail` 内渲染 `DetailAnalysis.structures[]`：英文原文按 token 闭区间从渲染期暂存的 Token 表还原（首 token 去前导空格，与正文一致）；颜色按 role 中文名精确反查 `GRAMMAR_LABELS`→`ROLE_COLORS`，匹配不到用灰 `#6b7280`。详解请求/缓存/开合切换/单面板/还原逻辑全部不动。

**Tech Stack:** TypeScript + Vite + vitest（happy-dom + fake-indexeddb）+ Playwright（tests/support/fake-openai-server.ts）。

**验证命令约定:** 一律在 `english-syntax-extension/` 项目目录下运行：`npm test`、`npm run lint`、`npm run format:check`、`npm run build`、`npx playwright test`。

**通用规约:**

- 严格 TDD：每个 Task 先写失败测试、跑出失败、再实现、跑绿、提交。
- 每条提交信息风格与 `git log --oneline -8` 一致（中文、`feat(extension):`/`test(extension):` 前缀），且**每条提交信息末尾必须带独立 trailer 行**：`Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`（用 `git commit -m "主题" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`）。
- 全程只在分支 `codex/english-syntax-extension-next` 上工作；**绝不合并到 main，绝不 push**。
- lint 基线：`npm run lint` 现存 **1 个既有 error（src/options/options.test.ts:167）**，这是被接受的基线状态；通过标准是「除该条外零 error、零新增告警」。
- `npm run build` 偶发 ETIMEDOUT（已知偶发抖动）——失败时原样重跑一次即可。
- Task 之间存在顺序依赖（Task 2 用到 Task 1 的枚举成员，Task 4 用到 Task 3 的 `circledNumber`，Task 5 依赖 1–4），按序执行。

---

### Task 1: GrammarRole 新增 COORDINATE_CLAUSE 与 CONJUNCTION（标签 + 颜色 + 校验器接受）

**Files:**

- Modify: `src/shared/grammar.ts`（enum + GRAMMAR_LABELS）
- Modify: `src/content/learning-block.ts:4-19`（ROLE_COLORS）
- Test: `src/shared/grammar.test.ts`
- Test: `src/language/analysis-validator.test.ts`
- Test: `src/content/learning-block.test.ts`

- [x] **Step 1: 写失败测试——标签**。在 `src/shared/grammar.test.ts` 的 `describe("grammar roles", ...)` 内、现有 it 之后追加：

```ts
it("labels the compound-sentence roles", () => {
  expect(GRAMMAR_LABELS[GrammarRole.COORDINATE_CLAUSE]).toBe("并列分句");
  expect(GRAMMAR_LABELS[GrammarRole.CONJUNCTION]).toBe("并列连词");
});
```

- [x] **Step 2: 写失败测试——校验器接受新角色**。在 `src/language/analysis-validator.test.ts` 的 `describe("core analysis validation", ...)` 内、`it("accepts complete, ordered core coverage", ...)` 之后追加（复用文件顶部已有的 `request` 夹具，"Learners read books." 4 个 token）：

```ts
it("accepts the compound-sentence roles COORDINATE_CLAUSE and CONJUNCTION", () => {
  const result = validateCoreBatch(
    {
      sentences: [
        {
          sentenceId: "sentence-1",
          components: [
            { startToken: 0, endToken: 0, role: "COORDINATE_CLAUSE", translation: "第一分句" },
            { startToken: 1, endToken: 1, role: "CONJUNCTION", translation: "并且" },
            { startToken: 2, endToken: 3, role: "COORDINATE_CLAUSE", translation: "第二分句" },
          ],
        },
      ],
    },
    [request],
    "profile-1",
  );
  expect(result.ok).toBe(true);
});
```

- [x] **Step 3: 写失败测试——正文配色**。在 `src/content/learning-block.test.ts` 的 `describe("SyntaxLearningBlock", ...)` 内、`it("colors each component underline by grammar role without any backdrop", ...)` 之后追加（复用文件顶部的 `sentence`/`tokens`/`analysis` 夹具）：

```ts
it("colors coordinate clauses teal and conjunctions gray", () => {
  const element = block();
  document.body.append(element.host);

  element.renderCore(sentence, tokens, {
    ...analysis,
    components: [
      { startToken: 0, endToken: 0, role: GrammarRole.COORDINATE_CLAUSE, translation: "分句一" },
      { startToken: 1, endToken: 1, role: GrammarRole.CONJUNCTION, translation: "连词" },
      { startToken: 2, endToken: 3, role: GrammarRole.COORDINATE_CLAUSE, translation: "分句二" },
    ],
  });

  const colors = [...element.host.shadowRoot!.querySelectorAll<HTMLElement>(".component")].map(
    (component) => component.style.getPropertyValue("--syntax-role-color"),
  );
  expect(colors).toEqual(["#0d9488", "#6b7280", "#0d9488"]);
});
```

- [x] **Step 4: 跑测试确认失败**

Run: `npx vitest run src/shared/grammar.test.ts src/language/analysis-validator.test.ts src/content/learning-block.test.ts`
Expected: 3 个新用例 FAIL（`expected undefined to be '并列分句'`、`expected false to be true`、颜色数组不匹配），其余全部保持 PASS。

- [x] **Step 5: 实现——枚举与标签**。`src/shared/grammar.ts` 中 enum 的 `INDEPENDENT_ELEMENT = "INDEPENDENT_ELEMENT",` 一行之后追加两个成员：

```ts
  COORDINATE_CLAUSE = "COORDINATE_CLAUSE",
  CONJUNCTION = "CONJUNCTION",
```

`GRAMMAR_LABELS` 中 `[GrammarRole.INDEPENDENT_ELEMENT]: "独立成分",` 之后追加：

```ts
  [GrammarRole.COORDINATE_CLAUSE]: "并列分句",
  [GrammarRole.CONJUNCTION]: "并列连词",
```

- [x] **Step 6: 实现——颜色**。`src/content/learning-block.ts` 的 `ROLE_COLORS` 中 `[GrammarRole.INDEPENDENT_ELEMENT]: "#6b7280",` 之后追加：

```ts
  [GrammarRole.COORDINATE_CLAUSE]: "#0d9488",
  [GrammarRole.CONJUNCTION]: "#6b7280",
```

- [x] **Step 7: 类型完整性检查**（`Record<GrammarRole, string>` 漏项在这里暴露）

Run: `npx tsc --noEmit`
Expected: 零错误。

- [x] **Step 8: 跑测试确认通过**

Run: `npx vitest run src/shared/grammar.test.ts src/language/analysis-validator.test.ts src/content/learning-block.test.ts`
Expected: 全部 PASS。

- [x] **Step 9: 提交**

```bash
git add src/shared/grammar.ts src/shared/grammar.test.ts src/content/learning-block.ts src/content/learning-block.test.ts src/language/analysis-validator.test.ts
git commit -m "feat(extension): 语法角色新增并列分句与并列连词" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: CORE_PROMPT_VERSION 1→2 + 核心提示词复合句规则

**Files:**

- Modify: `src/shared/versions.ts`
- Modify: `src/background/prompts.ts:31-44`（buildCorePrompt）
- Test: `src/background/analysis-cache.test.ts`（版本参与缓存键 → cache miss）
- Test: `src/background/openai-compatible-adapter.test.ts`（`describe("syntax prompts", ...)` 内的提示词文案断言）

- [x] **Step 1: 写失败测试——版本升级导致缓存 miss**。`src/background/analysis-cache.test.ts` 顶部 import 区追加：

```ts
import { CORE_PROMPT_VERSION } from "../shared/versions";
```

在 `describe("analysis cache keys", ...)` 内、`it.each(...)("separates keys by %s", ...)` 之后追加：

```ts
it("invalidates every version-1 core cache entry after the prompt version bump", async () => {
  expect(CORE_PROMPT_VERSION).toBe(2);
  const previousVersionKey = await createCoreCacheKey({ ...coreIdentity, promptVersion: 1 });
  const currentVersionKey = await createCoreCacheKey({
    ...coreIdentity,
    promptVersion: CORE_PROMPT_VERSION,
  });
  expect(currentVersionKey).not.toBe(previousVersionKey);
});
```

（`coreIdentity` 是该文件顶部已有夹具，`promptVersion: 1`；`analysis-service.ts:590` 生成核心缓存键时把 `CORE_PROMPT_VERSION` 作为 `promptVersion` 传入 `createCoreCacheKey`，所以常量一变、键必变、旧核心缓存必 miss，详解缓存不受影响。）

- [x] **Step 2: 写失败测试——提示词文案**。`src/background/openai-compatible-adapter.test.ts` 中 `describe("syntax prompts", ...)` 内：

（a）把 `it("states all core structural invariants", ...)` 里的 `expect(prompt).toContain("14");` 改成：

```ts
expect(prompt).toContain("16");
```

（b）该 it 之后追加新用例：

```ts
it("states the compound-sentence rules for coordinate clauses and conjunctions", () => {
  const prompt = buildCorePrompt([sentence]);
  expect(prompt).toContain("COORDINATE_CLAUSE");
  expect(prompt).toContain("CONJUNCTION");
  expect(prompt).toMatch(/coordinating conjunction/i);
  expect(prompt).toMatch(/complete Chinese translation/i);
  expect(prompt).toMatch(/subordinate clause.*one whole component/is);
  expect(prompt).toMatch(/never wrap.*single subject-predicate/is);
});
```

- [x] **Step 3: 跑测试确认失败**

Run: `npx vitest run src/background/analysis-cache.test.ts src/background/openai-compatible-adapter.test.ts`
Expected: 新增/修改的断言 FAIL（`expected 1 to be 2`、`expected '…' to contain '16'`、`/coordinating conjunction/i` 不匹配），其余 PASS。

- [x] **Step 4: 实现——升版本**。`src/shared/versions.ts` 整文件改为：

```ts
export const MESSAGE_VERSION = 1 as const;
export const CORE_SCHEMA_VERSION = 1 as const;
export const CORE_PROMPT_VERSION = 2 as const;
export const DETAIL_PROMPT_VERSION = 1 as const;
```

（**不动** `CORE_SCHEMA_VERSION`——components 数据形状不变，spec §1。）

- [x] **Step 5: 实现——提示词规则**。用下面内容整体替换 `src/background/prompts.ts` 的 `buildCorePrompt` 函数（角色数改为从枚举派生，避免再次硬编码漂移）：

```ts
export function buildCorePrompt(sentences: readonly SentenceInput[]): string {
  const roles = Object.values(GrammarRole);
  return [
    "Analyze the numbered English sentences below into core grammatical components.",
    `The role field is a closed ${roles.length}-role enum: ${roles.join(", ")}.`,
    "Every component uses a closed Token interval [startToken, endToken]; both endpoints are inclusive Token IDs from the supplied sentence.",
    "Coverage rule: every non-punctuation Token must be covered exactly once. Components must be ordered, non-overlapping, and may include punctuation but may not contain punctuation only.",
    "Compound-sentence rule: when two or more clauses that could each stand alone as a sentence are joined by a coordinating conjunction (and, but, or, so, ...) or a semicolon, tag each clause as one whole COORDINATE_CLAUSE whose translation is the complete Chinese translation of that clause, and tag the coordinating conjunction as its own separate CONJUNCTION component (in a comma-plus-conjunction pair, tag only the conjunction itself as CONJUNCTION).",
    "Complex-sentence rule: keep tagging a subordinate clause as one whole component with one of the five clause roles (SUBJECT_CLAUSE, OBJECT_CLAUSE, PREDICATIVE_CLAUSE, ATTRIBUTIVE_CLAUSE, ADVERBIAL_CLAUSE); never split its internal structure.",
    "Simple-sentence rule: never wrap a sentence with a single subject-predicate structure in COORDINATE_CLAUSE.",
    "Give every component a concise, non-empty Chinese translation.",
    "Keep every sentenceId and every supplied Token unchanged. Return JSON only, with no Markdown or explanatory prose.",
    CORE_OUTPUT_SHAPE,
    "Numbered sentence requests:",
    serialize(sentences),
  ].join("\n\n");
}
```

（首行 `"Analyze the numbered English sentences..."` 保持不变——`tests/support/fake-openai-server.ts` 的 `detectKind` 按首行识别 core 请求。）

- [x] **Step 6: 跑测试确认通过**

Run: `npx vitest run src/background/analysis-cache.test.ts src/background/openai-compatible-adapter.test.ts`
Expected: 全部 PASS。

- [x] **Step 7: 提交**

```bash
git add src/shared/versions.ts src/background/prompts.ts src/background/analysis-cache.test.ts src/background/openai-compatible-adapter.test.ts
git commit -m "feat(extension): 核心提示词加入复合句规则并升 prompt 版本到 2" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 正文并列分句渲染期编号①②

**Files:**

- Modify: `src/content/learning-block.ts`（`circledNumber` 帮助函数 + `renderCore` 内标签计算）
- Test: `src/content/learning-block.test.ts`

- [x] **Step 1: 写失败测试**。在 `src/content/learning-block.test.ts` 顶部夹具区（`analysis` 定义之后、`function block()` 之前）追加复合句夹具：

```ts
const compoundSentence = "The sun rose and the birds sang.";
const compoundTokens: Token[] = [
  { id: 0, text: "The", start: 0, end: 3, leadingWhitespace: "", punctuation: false },
  { id: 1, text: "sun", start: 4, end: 7, leadingWhitespace: " ", punctuation: false },
  { id: 2, text: "rose", start: 8, end: 12, leadingWhitespace: " ", punctuation: false },
  { id: 3, text: "and", start: 13, end: 16, leadingWhitespace: " ", punctuation: false },
  { id: 4, text: "the", start: 17, end: 20, leadingWhitespace: " ", punctuation: false },
  { id: 5, text: "birds", start: 21, end: 26, leadingWhitespace: " ", punctuation: false },
  { id: 6, text: "sang", start: 27, end: 31, leadingWhitespace: " ", punctuation: false },
  { id: 7, text: ".", start: 31, end: 32, leadingWhitespace: "", punctuation: true },
];
const compoundAnalysis: CoreAnalysis = {
  schemaVersion: CORE_SCHEMA_VERSION,
  sentenceId: "sentence-1",
  components: [
    {
      startToken: 0,
      endToken: 2,
      role: GrammarRole.COORDINATE_CLAUSE,
      translation: "太阳升起来了",
    },
    { startToken: 3, endToken: 3, role: GrammarRole.CONJUNCTION, translation: "而且" },
    { startToken: 4, endToken: 6, role: GrammarRole.COORDINATE_CLAUSE, translation: "鸟儿在歌唱" },
  ],
  modelProfileId: "profile-1",
};
```

在 `describe("SyntaxLearningBlock", ...)` 内（Task 1 的配色用例之后）追加两个用例：

```ts
it("numbers coordinate clauses ①② at render time when a sentence has two or more", () => {
  const element = block();
  document.body.append(element.host);

  element.renderCore(compoundSentence, compoundTokens, compoundAnalysis);

  const root = element.host.shadowRoot!;
  expect([...root.querySelectorAll(".role")].map((role) => role.textContent)).toEqual([
    "并列分句①",
    "并列连词",
    "并列分句②",
  ]);
  expect(root.querySelector(".component")!.getAttribute("aria-label")).toBe(
    "并列分句①：太阳升起来了",
  );
});

it("keeps a lone coordinate clause unnumbered", () => {
  const element = block();
  document.body.append(element.host);

  element.renderCore(compoundSentence, compoundTokens, {
    ...compoundAnalysis,
    components: [
      {
        startToken: 0,
        endToken: 2,
        role: GrammarRole.COORDINATE_CLAUSE,
        translation: "太阳升起来了",
      },
      { startToken: 3, endToken: 3, role: GrammarRole.CONJUNCTION, translation: "而且" },
      { startToken: 4, endToken: 6, role: GrammarRole.ADVERBIAL, translation: "鸟儿在歌唱" },
    ],
  });

  expect(
    [...element.host.shadowRoot!.querySelectorAll(".role")].map((role) => role.textContent),
  ).toEqual(["并列分句", "并列连词", "状语"]);
});
```

- [x] **Step 2: 跑测试确认失败**

Run: `npx vitest run src/content/learning-block.test.ts`
Expected: 第一个新用例 FAIL（实际标签是 `"并列分句"` 没有①），第二个 PASS（现状即无编号），其余 PASS。

- [x] **Step 3: 实现**。`src/content/learning-block.ts`：

（a）在模块级帮助函数区（`eventDetail` 函数之后、`export class SyntaxLearningBlock` 之前）追加：

```ts
function circledNumber(value: number): string {
  // ①（U+2460）起的带圈数字覆盖 1–20；更长的枚举退回普通数字。
  return value >= 1 && value <= 20 ? String.fromCodePoint(0x2460 + value - 1) : `${value}.`;
}
```

（b）`renderCore` 中，把

```ts
    let nextToken = 0;
    for (const component of analysis.components) {
      this.#appendPunctuation(sentenceElement, tokens, nextToken, component.startToken - 1);
      const componentElement = createElement("button", "component");
      componentElement.type = "button";
      componentElement.dataset.startToken = String(component.startToken);
      componentElement.dataset.endToken = String(component.endToken);
      componentElement.style.setProperty("--syntax-role-color", ROLE_COLORS[component.role]);
      componentElement.setAttribute(
        "aria-label",
        `${GRAMMAR_LABELS[component.role]}：${component.translation}`,
      );
      const role = createElement("span", "role", GRAMMAR_LABELS[component.role]);
```

替换为（编号只在渲染期按出现顺序计数，同句 ≥2 个并列分句才编号；不进数据模型、不进缓存）：

```ts
    const coordinateClauseTotal = analysis.components.filter(
      (component) => component.role === GrammarRole.COORDINATE_CLAUSE,
    ).length;
    let coordinateClauseIndex = 0;
    let nextToken = 0;
    for (const component of analysis.components) {
      this.#appendPunctuation(sentenceElement, tokens, nextToken, component.startToken - 1);
      let label = GRAMMAR_LABELS[component.role];
      if (component.role === GrammarRole.COORDINATE_CLAUSE && coordinateClauseTotal >= 2) {
        coordinateClauseIndex += 1;
        label = `${label}${circledNumber(coordinateClauseIndex)}`;
      }
      const componentElement = createElement("button", "component");
      componentElement.type = "button";
      componentElement.dataset.startToken = String(component.startToken);
      componentElement.dataset.endToken = String(component.endToken);
      componentElement.style.setProperty("--syntax-role-color", ROLE_COLORS[component.role]);
      componentElement.setAttribute("aria-label", `${label}：${component.translation}`);
      const role = createElement("span", "role", label);
```

- [x] **Step 4: 跑测试确认通过**

Run: `npx vitest run src/content/learning-block.test.ts`
Expected: 全部 PASS。

- [x] **Step 5: 提交**

```bash
git add src/content/learning-block.ts src/content/learning-block.test.ts
git commit -m "feat(extension): 正文并列分句渲染期编号①②" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 详解面板标注区（两行式内联块 + 序号解释列表）

**Files:**

- Modify: `src/content/learning-block.ts`（STYLES 追加、Token 表暂存、`renderDetail` 重写、3 个模块级帮助定义）
- Test: `src/content/learning-block.test.ts`

顺序按 spec §3：标注区 → 逐条解释 → 语法点 → 整体讲解。容错：区间越界/反转 → 跳过该条标注块但解释仍按同一序号列出；`structures` 为空 → 不渲染标注区与解释列表（退回现状）。详解请求/缓存/开合切换/单面板/还原逻辑全部不动。

- [x] **Step 1: 写失败测试**。在 `src/content/learning-block.test.ts` 的 `describe("SyntaxLearningBlock", ...)` 内（Task 3 用例之后）追加三个用例（复用顶部 `sentence`/`tokens`/`analysis` 夹具，"Learners read books."）：

```ts
it("renders a numbered annotation zone reconstructed from token ranges above the explanations", () => {
  const element = block();
  document.body.append(element.host);
  element.renderCore(sentence, tokens, analysis);
  element.setDetailLoading("sentence-1", { startToken: 0, endToken: 0 });

  element.renderDetail({
    sentenceId: "sentence-1",
    focus: { startToken: 0, endToken: 0 },
    structures: [
      { startToken: 0, endToken: 1, role: "主语", explanation: "主语与谓语" },
      { startToken: 2, endToken: 2, role: "引导词", explanation: "未知角色回退灰色" },
    ],
    grammarPoints: ["一般现在时"],
    explanation: "整体讲解",
    modelProfileId: "profile-1",
  });

  const root = element.host.shadowRoot!;
  const annotations = [...root.querySelectorAll<HTMLElement>(".annotation")];
  expect(
    annotations.map((annotation) =>
      [...annotation.children].map((child) => [child.className, child.textContent]),
    ),
  ).toEqual([
    [
      ["annotation-role", "① 主语"],
      // 首 token 去前导空格、后续 token 保留——与正文标注同一规则。
      ["annotation-english", "Learners read"],
    ],
    [
      ["annotation-role", "② 引导词"],
      ["annotation-english", "books"],
    ],
  ]);
  expect(
    annotations.map((annotation) => annotation.style.getPropertyValue("--syntax-role-color")),
  ).toEqual(["#2563eb", "#6b7280"]);
  // 顺序：标注区 → 逐条解释 → 语法点 → 整体讲解。
  expect([...root.querySelector(".detail")!.children].map((child) => child.className)).toEqual([
    "detail-annotations",
    "detail-structure",
    "detail-structure",
    "grammar-points",
    "detail-summary",
  ]);
  expect([...root.querySelectorAll(".detail-structure")].map((row) => row.textContent)).toEqual([
    "① 主语：主语与谓语",
    "② 引导词：未知角色回退灰色",
  ]);
});

it("skips the annotation but keeps the numbered explanation for an invalid token range", () => {
  const element = block();
  document.body.append(element.host);
  element.renderCore(sentence, tokens, analysis);
  element.setDetailLoading("sentence-1", { startToken: 0, endToken: 0 });

  element.renderDetail({
    sentenceId: "sentence-1",
    focus: { startToken: 0, endToken: 0 },
    structures: [
      { startToken: 0, endToken: 0, role: "主语", explanation: "第一条" },
      { startToken: 9, endToken: 12, role: "状语", explanation: "第二条区间越界" },
      { startToken: 2, endToken: 1, role: "谓语", explanation: "第三条区间反转" },
    ],
    grammarPoints: [],
    explanation: "整体讲解",
    modelProfileId: "profile-1",
  });

  const root = element.host.shadowRoot!;
  expect(
    [...root.querySelectorAll(".annotation .annotation-role")].map((role) => role.textContent),
  ).toEqual(["① 主语"]);
  // 序号以解释列表为准：标注区少一块也不错位。
  expect([...root.querySelectorAll(".detail-structure")].map((row) => row.textContent)).toEqual([
    "① 主语：第一条",
    "② 状语：第二条区间越界",
    "③ 谓语：第三条区间反转",
  ]);
});

it("falls back to the plain panel when structures is empty", () => {
  const element = block();
  document.body.append(element.host);
  element.renderCore(sentence, tokens, analysis);
  element.setDetailLoading("sentence-1", { startToken: 0, endToken: 0 });

  element.renderDetail({
    sentenceId: "sentence-1",
    focus: { startToken: 0, endToken: 0 },
    structures: [],
    grammarPoints: ["一般现在时"],
    explanation: "整体讲解",
    modelProfileId: "profile-1",
  });

  const root = element.host.shadowRoot!;
  expect(root.querySelector(".detail-annotations")).toBeNull();
  expect(root.querySelector(".detail-structure")).toBeNull();
  expect(root.querySelector(".grammar-points")?.textContent).toBe("一般现在时");
  expect(root.querySelector(".detail-summary")?.textContent).toBe("整体讲解");
});
```

- [x] **Step 2: 跑测试确认失败**

Run: `npx vitest run src/content/learning-block.test.ts`
Expected: 前两个新用例 FAIL（没有 `.annotation` 元素、`.detail-structure` 文本没有序号），第三个可能 PASS（空 structures 现状即不渲染）；既有用例全部 PASS。

- [x] **Step 3: 实现**。`src/content/learning-block.ts` 四处修改：

（a）STYLES 内、`.detail,\n.sentence-failure { ... }` 规则块之后追加：

```css
.detail-annotations {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  column-gap: 0.5em;
  row-gap: 0.55em;
  max-inline-size: 100%;
  margin-block-end: 0.45em;
}

.annotation {
  display: inline-grid;
  grid-template-rows: repeat(2, auto);
  justify-items: center;
  min-inline-size: 0;
  max-inline-size: 100%;
  overflow-wrap: anywhere;
}

.annotation-role {
  font-size: max(11px, 0.68em);
  color: var(--syntax-role-color, currentColor);
  opacity: 0.85;
}

.annotation-english {
  border-bottom: 1.5px solid
    color-mix(in srgb, var(--syntax-role-color, currentColor) 60%, transparent);
  justify-self: stretch;
  text-align: center;
}

.detail-structure {
  margin-block: 0.2em;
}
```

（b）模块级帮助函数区（Task 3 加入的 `circledNumber` 之后、`export class` 之前）追加——`circledNumber` 已在 Task 3 定义为 `function circledNumber(value: number): string`，此处直接复用、**不要重复定义**：

```ts
const FALLBACK_ANNOTATION_COLOR = "#6b7280";

const ROLE_COLOR_BY_LABEL: ReadonlyMap<string, string> = new Map(
  Object.values(GrammarRole).map((role) => [GRAMMAR_LABELS[role], ROLE_COLORS[role]]),
);

/** 详解 structure 的 role 是模型自由文本；按中文名精确匹配成分色，匹配不到统一灰色。 */
function structureColor(role: string): string {
  return ROLE_COLOR_BY_LABEL.get(role) ?? FALLBACK_ANNOTATION_COLOR;
}

/**
 * 按 token 闭区间还原英文原文：首 token 去掉前导空格（与正文标注一致），
 * 其余 token 保留前导空格。区间反转、越界或不完整时返回 undefined。
 */
function annotationEnglish(tokens: readonly Token[], range: TokenRange): string | undefined {
  if (
    !Number.isInteger(range.startToken) ||
    !Number.isInteger(range.endToken) ||
    range.startToken < 0 ||
    range.endToken >= tokens.length ||
    range.startToken > range.endToken
  ) {
    return undefined;
  }
  let english = "";
  for (let index = range.startToken; index <= range.endToken; index += 1) {
    const token = tokens[index];
    if (token === undefined) {
      return undefined;
    }
    english += (index === range.startToken ? "" : token.leadingWhitespace) + token.text;
  }
  return english;
}
```

（`Token`、`TokenRange` 已由文件顶部的 `import type { CoreAnalysis, DetailAnalysis, Token, TokenRange } from "../shared/grammar";` 引入，无需新 import。）

（c）类字段区（`#resolvedSentenceIds = new Set<string>();` 之后）追加渲染期 Token 表暂存：

```ts
  #tokensBySentence = new Map<string, readonly Token[]>();
```

并在 `renderCore` 开头 `this.#validateCoreInput(sentence, tokens, analysis);` 一行之后写入：

```ts
this.#tokensBySentence.set(analysis.sentenceId, [...tokens]);
```

（d）用下面内容整体替换 `renderDetail` 方法：

```ts
  renderDetail(detailAnalysis: DetailAnalysis): void {
    const detail = this.#findDetail(detailAnalysis.sentenceId, detailAnalysis.focus);
    // The reader may have toggled the panel closed while the analysis was in
    // flight; a late response must not reopen it.
    if (detail === null) {
      return;
    }
    detail.className = "detail";
    detail.removeAttribute("aria-busy");
    detail.replaceChildren();

    if (detailAnalysis.structures.length > 0) {
      const tokens = this.#tokensBySentence.get(detailAnalysis.sentenceId) ?? [];
      const annotations = createElement("div", "detail-annotations");
      for (const [index, structure] of detailAnalysis.structures.entries()) {
        const english = annotationEnglish(tokens, structure);
        // 区间越界/反转：跳过标注块，下方解释列表仍按同一序号列出该条。
        if (english === undefined) {
          continue;
        }
        const annotation = createElement("span", "annotation");
        annotation.style.setProperty("--syntax-role-color", structureColor(structure.role));
        annotation.append(
          createElement(
            "span",
            "annotation-role",
            `${circledNumber(index + 1)} ${structure.role}`,
          ),
          createElement("span", "annotation-english", english),
        );
        annotations.append(annotation);
      }
      if (annotations.childElementCount > 0) {
        detail.append(annotations);
      }
      for (const [index, structure] of detailAnalysis.structures.entries()) {
        const row = createElement("div", "detail-structure");
        row.append(
          createElement("strong", "detail-role", `${circledNumber(index + 1)} ${structure.role}`),
          document.createTextNode("："),
          createElement("span", "detail-explanation", structure.explanation),
        );
        detail.append(row);
      }
    }
    if (detailAnalysis.grammarPoints.length > 0) {
      detail.append(
        createElement("div", "grammar-points", detailAnalysis.grammarPoints.join("、")),
      );
    }
    detail.append(createElement("div", "detail-summary", detailAnalysis.explanation));
  }
```

- [x] **Step 4: 跑测试确认通过（含既有回归：单面板/toggle/晚到响应丢弃/XSS 惰性文本）**

Run: `npx vitest run src/content/learning-block.test.ts`
Expected: 全部 PASS。

- [x] **Step 5: 类型检查**

Run: `npx tsc --noEmit`
Expected: 零错误。

- [x] **Step 6: 提交**

```bash
git add src/content/learning-block.ts src/content/learning-block.test.ts
git commit -m "feat(extension): 详解面板新增标注区与序号解释列表" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: E2E——复合句正文标注 + 详解标注区（fake server + 新夹具页）

**Files:**

- Create: `tests/fixtures/pages/compound-article.html`
- Modify: `tests/support/fake-openai-server.ts`（新增 2 个 ScriptedOutcome + 生成器 + 焦点解析提取）
- Test: `tests/e2e/extension.spec.ts`

- [x] **Step 1: 写失败的 E2E 用例**。在 `tests/e2e/extension.spec.ts` 末尾追加（沿用现有惯例：穿透 shadow 的 CSS 定位、`dispatchFromUi`、`learningBlocks`/`openArticle`/`seedLocalProfile`/`uiMessage` 帮助函数）：

```ts
test("a compound sentence renders numbered coordinate clauses and an annotated detail panel", async ({
  harness,
}) => {
  await seedLocalProfile(harness, "compound-model");
  harness.fakeModel.script("compound-model", [{ kind: "compound" }, { kind: "compound-detail" }]);
  const page = await openArticle(harness, "compound-article.html");
  const originalParagraph = await page.locator("#compound").evaluate((node) => node.outerHTML);
  const tabId = await harness.tabIdFor(`${harness.pagesOrigin}/compound-article.html`);
  const documentId = "e2e-doc-compound";
  await harness.dispatchFromUi(uiMessage("START_SESSION", { tabId, documentId }));
  await expect(learningBlocks(page)).toHaveCount(1, { timeout: 20_000 });

  // 正文：并列分句①/② + 并列连词，及各自配色。
  await expect(page.locator(".component .role")).toHaveText(["并列分句①", "并列连词", "并列分句②"]);
  const componentColors = await learningBlocks(page)
    .first()
    .evaluate((host) =>
      [...host.shadowRoot!.querySelectorAll<HTMLElement>(".component")].map((component) =>
        component.style.getPropertyValue("--syntax-role-color"),
      ),
    );
  expect(componentColors).toEqual(["#0d9488", "#6b7280", "#0d9488"]);

  // 点击第一个并列分句 → 详解面板出现两行式标注区与 ①②③ 对应解释。
  const firstClause = page.locator(".component").first();
  await firstClause.click();
  await expect(page.locator(".detail")).toContainText("详细语法解析", { timeout: 15_000 });
  const detail = await learningBlocks(page)
    .first()
    .evaluate((host) => {
      const root = host.shadowRoot!;
      return {
        annotations: [...root.querySelectorAll<HTMLElement>(".annotation")].map((annotation) => ({
          rows: [...annotation.children].map((child) => [child.className, child.textContent]),
          color: annotation.style.getPropertyValue("--syntax-role-color"),
        })),
        structures: [...root.querySelectorAll(".detail-structure")].map((row) => row.textContent),
        grammarPoints: root.querySelector(".grammar-points")?.textContent,
        summary: root.querySelector(".detail-summary")?.textContent,
      };
    });
  expect(detail.annotations).toEqual([
    {
      rows: [
        ["annotation-role", "① 主语"],
        ["annotation-english", "The sun"],
      ],
      color: "#2563eb",
    },
    {
      rows: [
        ["annotation-role", "② 谓语"],
        ["annotation-english", "rose"],
      ],
      color: "#dc2626",
    },
    {
      rows: [
        ["annotation-role", "③ 并列连词"],
        ["annotation-english", "and"],
      ],
      color: "#6b7280",
    },
  ]);
  expect(detail.structures).toEqual([
    "① 主语：The sun 是第一分句的主语。",
    "② 谓语：rose 是第一分句的谓语动词。",
    "③ 并列连词：and 连接前后两个并列分句。",
  ]);
  expect(detail.grammarPoints).toBe("并列句");
  expect(detail.summary).toBe("这是针对所选并列分句的详细语法解析。");
  expect(harness.fakeModel.recordedOfKind("detail")).toHaveLength(1);

  // 再点收起。
  await firstClause.click();
  await expect(page.locator(".detail")).toHaveCount(0);

  // STOP 还原零残留不回归。
  const stopped = await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId }));
  expect(stopped).toMatchObject({ type: "SESSION_STATUS", status: { state: "stopped" } });
  await expect(learningBlocks(page)).toHaveCount(0);
  await expect(page.locator("style[data-syntax-learning-hide]")).toHaveCount(0);
  expect(await page.locator("#compound").evaluate((node) => node.outerHTML)).toBe(
    originalParagraph,
  );
  await expect(page.locator("#compound")).toBeVisible();
});
```

- [x] **Step 2: 跑 E2E 确认失败**

Run: `npx playwright test -g "compound sentence renders"`
Expected: FAIL（TS 编译报 `kind: "compound"` 不在 ScriptedOutcome 联合类型内，或夹具页 404 → 学习块计数超时）。注意该命令内部先执行 `npm run build`（fixtures.ts 的 `buildPatchedExtension`），偶发 ETIMEDOUT 时重跑。

- [x] **Step 3: 实现——夹具页**。新建 `tests/fixtures/pages/compound-article.html`：

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <title>Compound Sentence Fixture</title>
  </head>
  <body>
    <main>
      <article>
        <p id="compound">The sun rose and the birds sang.</p>
      </article>
    </main>
  </body>
</html>
```

（句长 32 字符 > `MINIMUM_AUTO_TEXT_LENGTH = 20`（document-scanner.ts:18），段落可被自动选中。分词为 8 个 token：The(0) sun(1) rose(2) and(3) the(4) birds(5) sang(6) .(7)。）

- [x] **Step 4: 实现——fake server**。`tests/support/fake-openai-server.ts` 四处修改：

（a）`ScriptedOutcome` 联合类型的 `| { kind: "xss"; payload: string }` 之后插入：

```ts
  | { kind: "compound" }
  | { kind: "compound-detail" }
```

（b）`coverageGapComponents` 函数之后追加生成器与焦点解析帮助函数：

```ts
/**
 * A deterministic compound-sentence analysis: the tokens before the first
 * coordinating conjunction form one COORDINATE_CLAUSE, the conjunction is its
 * own CONJUNCTION component, and the remaining lexical tokens form the second
 * COORDINATE_CLAUSE. A sentence without an inner conjunction falls back to
 * the automatic simple analysis so the outcome stays validator-compliant.
 */
function compoundComponents(sentence: PromptSentence): GeneratedComponent[] {
  const conjunction = sentence.tokens.find(
    (token) => !token.punctuation && ["and", "but", "or", "so"].includes(token.text.toLowerCase()),
  );
  const lexical = sentence.tokens.filter((token) => !token.punctuation);
  if (conjunction === undefined || conjunction === lexical[0] || conjunction === lexical.at(-1)) {
    return autoComponents(sentence);
  }
  return [
    {
      startToken: lexical[0]!.id,
      endToken: conjunction.id - 1,
      role: "COORDINATE_CLAUSE",
      translation: "第一分句的完整翻译",
    },
    {
      startToken: conjunction.id,
      endToken: conjunction.id,
      role: "CONJUNCTION",
      translation: "并且",
    },
    {
      startToken: conjunction.id + 1,
      endToken: lexical.at(-1)!.id,
      role: "COORDINATE_CLAUSE",
      translation: "第二分句的完整翻译",
    },
  ];
}

/** Shared by the detail responders: recover the requested focus from the prompt. */
function parseFocus(promptText: string): { startToken: number; endToken: number } {
  const match = /Focus(?: range)?:\s*\n+\s*\{\s*"startToken":\s*(\d+),\s*"endToken":\s*(\d+)/.exec(
    promptText,
  );
  return match
    ? { startToken: Number(match[1]), endToken: Number(match[2]) }
    : { startToken: 0, endToken: 0 };
}
```

（c）`reply` 方法的 `switch` 中、`case "auto":` 之前插入两个分支：

```ts
      case "compound":
        this.respondCore(response, sentences, (sentence) => compoundComponents(sentence));
        return;
      case "compound-detail":
        this.respondCompoundDetail(response, sentences);
        return;
```

（d）`respondDetail` 方法内，把原有的焦点正则推导

```ts
const promptText = this.requests.at(-1)?.promptText ?? "";
const focusMatch =
  /Focus(?: range)?:\s*\n+\s*\{\s*"startToken":\s*(\d+),\s*"endToken":\s*(\d+)/.exec(promptText);
const startToken = focusMatch ? Number(focusMatch[1]) : 0;
const endToken = focusMatch ? Number(focusMatch[2]) : 0;
```

替换为

```ts
const { startToken, endToken } = parseFocus(this.requests.at(-1)?.promptText ?? "");
```

并在 `respondDetail` 方法之后追加新方法：

```ts
  private respondCompoundDetail(response: ServerResponse, sentences: PromptSentence[]): void {
    const sentence = sentences[0];
    const { startToken, endToken } = parseFocus(this.requests.at(-1)?.promptText ?? "");
    response.writeHead(200, { "content-type": "application/json" });
    response.end(
      completion(
        jsonBody({
          sentenceId: sentence?.sentenceId ?? "",
          focus: { startToken, endToken },
          // Token indices assume the compound-article.html fixture sentence
          // "The sun rose and the birds sang." (tokens 0-7).
          structures: [
            {
              startToken: 0,
              endToken: 1,
              role: "主语",
              explanation: "The sun 是第一分句的主语。",
            },
            {
              startToken: 2,
              endToken: 2,
              role: "谓语",
              explanation: "rose 是第一分句的谓语动词。",
            },
            {
              startToken: 3,
              endToken: 3,
              role: "并列连词",
              explanation: "and 连接前后两个并列分句。",
            },
          ],
          grammarPoints: ["并列句"],
          explanation: "这是针对所选并列分句的详细语法解析。",
        }),
      ),
    );
  }
```

- [x] **Step 5: 跑新用例确认通过**

Run: `npx playwright test -g "compound sentence renders"`
Expected: 1 passed。

- [x] **Step 6: 跑全部 E2E 确认无回归**

Run: `npx playwright test`
Expected: 全部 passed（含既有的详解开合、STOP 零残留、XSS 用例）。

- [x] **Step 7: 提交**

```bash
git add tests/fixtures/pages/compound-article.html tests/support/fake-openai-server.ts tests/e2e/extension.spec.ts
git commit -m "test(extension): 复合句正文标注与详解标注区 E2E" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 全量门禁 + dist 重建

**Files:** 无新改动（若 prettier/lint 修复改动了已有文件则一并提交）。

- [x] **Step 1: 单测全量**

Run: `npm test`
Expected: 全部 PASS（较基线新增约 10 个用例）。

- [x] **Step 2: lint**

Run: `npm run lint`
Expected: 仅 1 个既有 error（`src/options/options.test.ts:167`，被接受的基线），本计划触碰的文件零新增 error/warning。若有新增，就地修复后重跑。

- [x] **Step 3: 格式化**

Run: `npx prettier --write src/shared/grammar.ts src/shared/grammar.test.ts src/shared/versions.ts src/background/prompts.ts src/background/analysis-cache.test.ts src/background/openai-compatible-adapter.test.ts src/content/learning-block.ts src/content/learning-block.test.ts src/language/analysis-validator.test.ts tests/support/fake-openai-server.ts tests/e2e/extension.spec.ts tests/fixtures/pages/compound-article.html`
然后 Run: `npm run format:check`
Expected: `All matched files use Prettier code style!`

- [x] **Step 4: 构建 + 重建 dist**

Run: `npm run build`
Expected: `tsc --noEmit` 零错误、两次 vite build 成功、`dist/` 已更新（dist 未纳入 git，无需提交）。偶发 ETIMEDOUT 时原样重跑一次。

- [x] **Step 5: E2E 全量**

Run: `npx playwright test`
Expected: 全部 passed。

- [x] **Step 6: 收尾提交（仅当 Step 2/3 产生了修改）**

```bash
git status --short
# 若有改动：
git add -A
git commit -m "chore(extension): lint 与 prettier 收尾" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [x] **Step 7（人工验收，非阻塞）:** 按 spec §4——真实 DeepSeek key + headed 浏览器，找含并列句与主从复合句的英文页面，浅色+深色页面各截图核对正文分句标注（并列分句①②青绿 `#0d9488`、并列连词灰 `#6b7280`）与详解面板标注区观感。此步由用户执行确认。
