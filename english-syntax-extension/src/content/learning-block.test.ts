// @vitest-environment happy-dom

import { beforeEach, describe, expect, it, vi } from "vitest";
import { CORE_SCHEMA_VERSION } from "../shared/versions";
import { GrammarRole } from "../shared/grammar";
import type { CoreAnalysis, DetailAnalysis, Token } from "../shared/grammar";
import { SyntaxLearningBlock } from "./learning-block";

const sentence = "Learners read books.";
const tokens: Token[] = [
  { id: 0, text: "Learners", start: 0, end: 8, leadingWhitespace: "", punctuation: false },
  { id: 1, text: "read", start: 9, end: 13, leadingWhitespace: " ", punctuation: false },
  { id: 2, text: "books", start: 14, end: 19, leadingWhitespace: " ", punctuation: false },
  { id: 3, text: ".", start: 19, end: 20, leadingWhitespace: "", punctuation: true },
];
const analysis: CoreAnalysis = {
  schemaVersion: CORE_SCHEMA_VERSION,
  sentenceId: "sentence-1",
  components: [
    { startToken: 0, endToken: 0, role: GrammarRole.SUBJECT, translation: "学习者" },
    { startToken: 1, endToken: 1, role: GrammarRole.PREDICATE, translation: "阅读" },
    { startToken: 2, endToken: 3, role: GrammarRole.OBJECT, translation: "书籍" },
  ],
  modelProfileId: "profile-1",
};

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

function block(): SyntaxLearningBlock {
  return new SyntaxLearningBlock();
}

function analysisFor(sentenceId: string): CoreAnalysis {
  return { ...analysis, sentenceId };
}

function detailFor(sentenceId: string, startToken: number, endToken: number): DetailAnalysis {
  return {
    sentenceId,
    focus: { startToken, endToken },
    structures: [{ startToken, endToken, role: "核心成分", explanation: "承担核心语法功能" }],
    grammarPoints: ["一般现在时"],
    explanation: "针对所选成分的详细语法解析",
    modelProfileId: "profile-1",
  };
}

function sectionOrder(element: SyntaxLearningBlock): Array<string | undefined> {
  return [
    ...element.host.shadowRoot!.querySelectorAll<HTMLElement>(".sentences > [data-sentence-id]"),
  ].map((section) => section.dataset.sentenceId);
}

describe("SyntaxLearningBlock", () => {
  beforeEach(() => {
    document.body.replaceChildren();
  });

  it("tracks a nonempty unique expected sentence set until every sentence resolves", () => {
    const element = block();

    expect(() => element.setExpectedSentenceIds([])).toThrow(/nonempty/u);
    expect(() => element.setExpectedSentenceIds(["sentence-1", "sentence-1"])).toThrow(
      /duplicate/u,
    );
    expect(element.isReadyToReplace()).toBe(false);

    element.setExpectedSentenceIds(["sentence-1", "sentence-2"]);
    element.renderCore(sentence, tokens, analysis);
    expect(element.isReadyToReplace()).toBe(false);

    element.renderFailure("sentence-2", "A failed original sentence.", "解析失败");
    expect(element.isReadyToReplace()).toBe(true);
  });

  it("renders role, underlined English, then component Chinese without a sentence translation", () => {
    const element = block();
    document.body.append(element.host);

    element.renderCore(sentence, tokens, analysis);

    const root = element.host.shadowRoot!;
    const components = [...root.querySelectorAll<HTMLElement>(".component")];
    expect(components).toHaveLength(3);
    expect(
      components.map((component) =>
        [...component.children].map((child) => [child.className, child.textContent]),
      ),
    ).toEqual([
      [
        ["role", "主语"],
        ["english", "Learners"],
        ["translation", "学习者"],
      ],
      [
        ["role", "谓语"],
        // Leading whitespace of a component's first token is dropped: the
        // spacing between components comes from the layout gap, and keeping
        // it would extend this component's underline into the gap.
        ["english", "read"],
        ["translation", "阅读"],
      ],
      [
        ["role", "宾语"],
        ["english", "books"],
        ["translation", "书籍"],
      ],
    ]);
    expect(root.querySelectorAll(".english")).toHaveLength(3);
    expect(root.querySelector(".sentence-translation")).toBeNull();
    expect(root.querySelector(".sentence")!.textContent?.match(/\./gu)).toHaveLength(1);
    expect(root.querySelector(".punctuation")?.textContent).toBe(".");
    expect(root.querySelector(".punctuation .translation")).toBeNull();
  });

  it("keeps script-like model strings as inert text", () => {
    const element = block();
    document.body.append(element.host);
    const unsafe = "<img src=x onerror=alert(1)>";

    element.renderCore(sentence, tokens, {
      ...analysis,
      components: [{ ...analysis.components[0]!, translation: unsafe }],
    });

    expect(element.host.shadowRoot!.querySelector("img")).toBeNull();
    expect(element.host.shadowRoot!.querySelector(".translation")?.textContent).toBe(unsafe);
  });

  it("keeps uncovered leading, inter-component, and trailing punctuation once in source order", () => {
    const punctuationTokens: Token[] = [
      { id: 0, text: '"', start: 0, end: 1, leadingWhitespace: "", punctuation: true },
      { id: 1, text: "Well", start: 1, end: 5, leadingWhitespace: "", punctuation: false },
      { id: 2, text: ",", start: 5, end: 6, leadingWhitespace: "", punctuation: true },
      { id: 3, text: "learners", start: 7, end: 15, leadingWhitespace: " ", punctuation: false },
      { id: 4, text: "read", start: 16, end: 20, leadingWhitespace: " ", punctuation: false },
      { id: 5, text: ".", start: 20, end: 21, leadingWhitespace: "", punctuation: true },
    ];
    const element = block();
    document.body.append(element.host);
    element.renderCore('"Well, learners read.', punctuationTokens, {
      ...analysis,
      components: [
        { startToken: 1, endToken: 1, role: GrammarRole.INDEPENDENT_ELEMENT, translation: "嗯" },
        { startToken: 3, endToken: 3, role: GrammarRole.SUBJECT, translation: "学习者" },
        { startToken: 4, endToken: 4, role: GrammarRole.PREDICATE, translation: "阅读" },
      ],
    });

    const sentenceRow = element.host.shadowRoot!.querySelector(".sentence")!;
    expect([...sentenceRow.children].map((child) => child.className)).toEqual([
      "punctuation",
      "component",
      "punctuation",
      "component",
      "component",
      "punctuation",
    ]);
    expect(
      [...sentenceRow.querySelectorAll(".punctuation")].map((node) => node.textContent),
    ).toEqual(['"', ",", "."]);
  });

  it.each([
    [
      "overlapping ranges",
      [
        { startToken: 0, endToken: 1, role: GrammarRole.SUBJECT, translation: "学习者阅读" },
        { startToken: 1, endToken: 3, role: GrammarRole.PREDICATE, translation: "阅读书籍" },
      ],
    ],
    [
      "out-of-order ranges",
      [
        { startToken: 2, endToken: 3, role: GrammarRole.OBJECT, translation: "书籍" },
        { startToken: 0, endToken: 1, role: GrammarRole.SUBJECT, translation: "学习者阅读" },
      ],
    ],
  ])("rejects %s atomically so punctuation cannot duplicate", (_description, components) => {
    const element = block();
    document.body.append(element.host);

    expect(() => element.renderCore(sentence, tokens, { ...analysis, components })).toThrow(
      /ordered and non-overlapping/u,
    );
    expect(element.host.shadowRoot!.querySelector(".sentence")).toBeNull();
    expect(element.host.shadowRoot!.querySelector(".punctuation")).toBeNull();
  });

  it("rejects a sentence/token mismatch before changing an existing render", () => {
    const element = block();
    document.body.append(element.host);
    element.renderCore(sentence, tokens, analysis);
    const existing = element.host.shadowRoot!.querySelector(".sentence");

    expect(() => element.renderCore("Different sentence.", tokens, analysis)).toThrow(
      /sentence and tokens/u,
    );
    expect(element.host.shadowRoot!.querySelector(".sentence")).toBe(existing);
  });

  it("requests detail with sentence and focus IDs through a composed keyboard-accessible event", () => {
    const element = block();
    document.body.append(element.host);
    element.renderCore(sentence, tokens, analysis);
    const listener = vi.fn();
    document.addEventListener("syntax-detail-request", listener, { once: true });

    const component = element.host.shadowRoot!.querySelector<HTMLButtonElement>(".component")!;
    component.focus();
    component.click();

    expect(component.tagName).toBe("BUTTON");
    expect(listener).toHaveBeenCalledOnce();
    const event = listener.mock.calls[0]![0] as CustomEvent;
    expect(event.composed).toBe(true);
    expect(event.detail).toEqual({
      sentenceId: "sentence-1",
      focus: { startToken: 0, endToken: 0 },
    });
  });

  it("shows lazy detail loading, detail text, and a retryable error without creating markup", () => {
    const element = block();
    document.body.append(element.host);
    element.renderCore(sentence, tokens, analysis);
    element.setDetailLoading("sentence-1", { startToken: 1, endToken: 1 });
    expect(element.host.shadowRoot!.querySelector("[aria-busy='true']")?.textContent).toContain(
      "加载",
    );

    const detail: DetailAnalysis = {
      sentenceId: "sentence-1",
      focus: { startToken: 1, endToken: 1 },
      structures: [{ startToken: 1, endToken: 1, role: "<img src=x>", explanation: "谓语动词" }],
      grammarPoints: ["一般现在时"],
      explanation: "read 表示阅读",
      modelProfileId: "profile-1",
    };
    element.renderDetail(detail);
    expect(element.host.shadowRoot!.querySelector(".detail")?.textContent).toContain("一般现在时");
    expect(element.host.shadowRoot!.querySelector("img")).toBeNull();

    const listener = vi.fn();
    element.host.addEventListener("syntax-reanalyze-request", listener, { once: true });
    element.renderError("sentence-1", { startToken: 1, endToken: 1 }, "暂时无法解析");
    element.host.shadowRoot!.querySelector<HTMLButtonElement>(".retry")!.click();
    expect(listener).toHaveBeenCalledOnce();
    expect((listener.mock.calls[0]![0] as CustomEvent).detail).toEqual({
      sentenceId: "sentence-1",
      focus: { startToken: 1, endToken: 1 },
    });
  });

  it("re-renders a sentence in place instead of moving it below its later siblings", () => {
    const element = block();
    document.body.append(element.host);
    element.setExpectedSentenceIds(["sentence-1", "sentence-2", "sentence-3"]);
    element.renderCore(sentence, tokens, analysisFor("sentence-1"));
    element.renderCore(sentence, tokens, analysisFor("sentence-2"));
    element.renderCore(sentence, tokens, analysisFor("sentence-3"));

    // A correction or retry re-render must not move the sentence — and any
    // detail panel opened under it — below the sentences that follow it.
    element.renderCore(sentence, tokens, analysisFor("sentence-2"));
    expect(sectionOrder(element)).toEqual(["sentence-1", "sentence-2", "sentence-3"]);

    element.renderFailure("sentence-2", sentence, "解析失败");
    expect(sectionOrder(element)).toEqual(["sentence-1", "sentence-2", "sentence-3"]);
  });

  it("inserts a late failure at its sentence position instead of the block end", () => {
    const element = block();
    document.body.append(element.host);
    element.setExpectedSentenceIds(["sentence-1", "sentence-2", "sentence-3"]);
    element.renderCore(sentence, tokens, analysisFor("sentence-1"));
    element.renderCore(sentence, tokens, analysisFor("sentence-3"));

    element.renderFailure("sentence-2", sentence, "解析失败");

    expect(sectionOrder(element)).toEqual(["sentence-1", "sentence-2", "sentence-3"]);
    expect(
      element.host
        .shadowRoot!.querySelector("[data-sentence-id='sentence-2']")
        ?.classList.contains("sentence-failure"),
    ).toBe(true);
  });

  it("toggles a component's detail panel closed on the second click without a new request", () => {
    const element = block();
    document.body.append(element.host);
    element.renderCore(sentence, tokens, analysis);
    const listener = vi.fn();
    document.addEventListener("syntax-detail-request", listener);
    const component = element.host.shadowRoot!.querySelector<HTMLButtonElement>(".component")!;

    component.click();
    expect(listener).toHaveBeenCalledOnce();
    element.setDetailLoading("sentence-1", { startToken: 0, endToken: 0 });
    element.renderDetail(detailFor("sentence-1", 0, 0));
    expect(element.host.shadowRoot!.querySelectorAll(".detail")).toHaveLength(1);

    component.click();
    expect(element.host.shadowRoot!.querySelectorAll(".detail")).toHaveLength(0);
    expect(listener).toHaveBeenCalledOnce();

    component.click();
    expect(listener).toHaveBeenCalledTimes(2);
    document.removeEventListener("syntax-detail-request", listener);
  });

  it("keeps only the newest detail panel open and anchors it inside its own sentence", () => {
    const element = block();
    document.body.append(element.host);
    element.setExpectedSentenceIds(["sentence-1", "sentence-2"]);
    element.renderCore(sentence, tokens, analysisFor("sentence-1"));
    element.renderCore(sentence, tokens, analysisFor("sentence-2"));

    element.setDetailLoading("sentence-1", { startToken: 0, endToken: 0 });
    element.renderDetail(detailFor("sentence-1", 0, 0));
    element.setDetailLoading("sentence-2", { startToken: 1, endToken: 1 });

    const root = element.host.shadowRoot!;
    const details = root.querySelectorAll<HTMLElement>(".detail");
    expect(details).toHaveLength(1);
    expect(details[0]!.dataset.startToken).toBe("1");
    expect(details[0]!.closest("[data-sentence-id]")).toBe(
      root.querySelector("[data-sentence-id='sentence-2']"),
    );
  });

  it("drops detail and error responses for a panel the reader already closed", () => {
    const element = block();
    document.body.append(element.host);
    element.renderCore(sentence, tokens, analysis);
    const component = element.host.shadowRoot!.querySelector<HTMLButtonElement>(".component")!;

    component.click();
    element.setDetailLoading("sentence-1", { startToken: 0, endToken: 0 });
    component.click();
    expect(element.host.shadowRoot!.querySelectorAll(".detail")).toHaveLength(0);

    element.renderDetail(detailFor("sentence-1", 0, 0));
    expect(element.host.shadowRoot!.querySelectorAll(".detail")).toHaveLength(0);
    element.renderError("sentence-1", { startToken: 0, endToken: 0 }, "暂时无法解析");
    expect(element.host.shadowRoot!.querySelectorAll(".detail")).toHaveLength(0);
  });

  it("includes the required isolated, wrapping, accessible and motion-safe CSS contract", () => {
    const css = block().host.shadowRoot!.querySelector("style")!.textContent;

    expect(css).toContain("inline-grid");
    expect(css).toContain("grid-template-rows: repeat(3");
    expect(css).toContain("max-inline-size: 100%");
    expect(css).toContain("white-space: normal");
    expect(css).toContain("overflow-wrap: anywhere");
    expect(css).toContain("font: inherit");
    expect(css).toContain("color: inherit");
    expect(css).toContain("font-size: max(11px");
    expect(css).toContain("border-bottom: 1.5px solid");
    expect(css).toContain(":focus-visible");
    expect(css).toContain("prefers-reduced-motion: reduce");
  });

  it("colors each component underline by grammar role without any backdrop", () => {
    const element = block();
    document.body.append(element.host);

    element.renderCore(sentence, tokens, analysis);

    const root = element.host.shadowRoot!;
    const colors = [...root.querySelectorAll<HTMLElement>(".component")].map((component) =>
      component.style.getPropertyValue("--syntax-role-color"),
    );
    expect(colors).toEqual(["#2563eb", "#dc2626", "#059669"]); // 主语蓝、谓语红、宾语绿

    const styles = root.querySelector("style")!.textContent;
    expect(styles).toContain("border-bottom: 1.5px solid");
    expect(styles).toContain("var(--syntax-role-color");
    // 成分不再有底色块
    expect(styles).not.toMatch(/\.component\s*\{[^}]*background:\s*color-mix/u);
  });

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
    // The visible label keeps ①; the accessible label uses a plain digit that
    // screen readers announce reliably.
    expect(root.querySelector(".component")!.getAttribute("aria-label")).toBe(
      "并列分句1：太阳升起来了",
    );
  });

  it("restarts coordinate-clause numbering at ① for each sentence", () => {
    const element = block();
    document.body.append(element.host);
    element.setExpectedSentenceIds(["sentence-1", "sentence-2"]);
    element.renderCore(compoundSentence, compoundTokens, compoundAnalysis);
    element.renderCore(compoundSentence, compoundTokens, {
      ...compoundAnalysis,
      sentenceId: "sentence-2",
    });

    const root = element.host.shadowRoot!;
    const rolesBySentence = [...root.querySelectorAll<HTMLElement>("[data-sentence-id]")].map(
      (section) => [...section.querySelectorAll(".role")].map((role) => role.textContent),
    );
    expect(rolesBySentence).toEqual([
      ["并列分句①", "并列连词", "并列分句②"],
      ["并列分句①", "并列连词", "并列分句②"],
    ]);
  });

  it("keeps ①② numbering after an in-place re-render of the same sentence", () => {
    const element = block();
    document.body.append(element.host);
    element.renderCore(compoundSentence, compoundTokens, compoundAnalysis);
    element.renderCore(compoundSentence, compoundTokens, compoundAnalysis);

    const root = element.host.shadowRoot!;
    expect(root.querySelectorAll(".sentence")).toHaveLength(1);
    expect([...root.querySelectorAll(".role")].map((role) => role.textContent)).toEqual([
      "并列分句①",
      "并列连词",
      "并列分句②",
    ]);
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
        { startToken: 3, endToken: 3, role: "标点", explanation: "第四条纯标点" },
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
      "④ 标点：第四条纯标点",
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

  it("excludes a trailing punctuation token from the annotation underline like the body text", () => {
    const element = block();
    document.body.append(element.host);
    element.renderCore(sentence, tokens, analysis);
    element.setDetailLoading("sentence-1", { startToken: 0, endToken: 0 });

    element.renderDetail({
      sentenceId: "sentence-1",
      focus: { startToken: 0, endToken: 0 },
      structures: [{ startToken: 2, endToken: 3, role: "宾语", explanation: "宾语与句号" }],
      grammarPoints: [],
      explanation: "整体讲解",
      modelProfileId: "profile-1",
    });

    // The body text keeps a trailing punctuation token out of the underline;
    // the annotation underline mirrors that rule.
    expect(element.host.shadowRoot!.querySelector(".annotation-english")?.textContent).toBe(
      "books",
    );
  });

  it("skips every annotation but keeps the numbered explanations without a token table", () => {
    const element = block();
    document.body.append(element.host);
    // A failed sentence never went through renderCore, so no token table exists.
    element.renderFailure("sentence-1", sentence, "解析失败");
    element.setDetailLoading("sentence-1", { startToken: 0, endToken: 0 });

    element.renderDetail({
      sentenceId: "sentence-1",
      focus: { startToken: 0, endToken: 0 },
      structures: [
        { startToken: 0, endToken: 0, role: "主语", explanation: "第一条" },
        { startToken: 1, endToken: 1, role: "谓语", explanation: "第二条" },
      ],
      grammarPoints: [],
      explanation: "整体讲解",
      modelProfileId: "profile-1",
    });

    const root = element.host.shadowRoot!;
    expect(root.querySelector(".detail-annotations")).toBeNull();
    expect([...root.querySelectorAll(".detail-structure")].map((row) => row.textContent)).toEqual([
      "① 主语：第一条",
      "② 谓语：第二条",
    ]);
  });

  it("clears stored token tables when the expected sentence set is reset", () => {
    const element = block();
    document.body.append(element.host);
    element.renderCore(sentence, tokens, analysis);

    // A reset starts a new render cycle; a sentence that then fails must not
    // reuse the stale token table from before the reset.
    element.setExpectedSentenceIds(["sentence-1"]);
    element.renderFailure("sentence-1", sentence, "解析失败");
    element.setDetailLoading("sentence-1", { startToken: 0, endToken: 0 });
    element.renderDetail({
      sentenceId: "sentence-1",
      focus: { startToken: 0, endToken: 0 },
      structures: [{ startToken: 0, endToken: 0, role: "主语", explanation: "第一条" }],
      grammarPoints: [],
      explanation: "整体讲解",
      modelProfileId: "profile-1",
    });

    const root = element.host.shadowRoot!;
    expect(root.querySelector(".detail-annotations")).toBeNull();
    expect([...root.querySelectorAll(".detail-structure")].map((row) => row.textContent)).toEqual([
      "① 主语：第一条",
    ]);
  });

  it("maps known English enum role names to their Chinese labels for color lookup", () => {
    const element = block();
    document.body.append(element.host);
    element.renderCore(sentence, tokens, analysis);
    element.setDetailLoading("sentence-1", { startToken: 0, endToken: 0 });

    element.renderDetail({
      sentenceId: "sentence-1",
      focus: { startToken: 0, endToken: 0 },
      structures: [
        { startToken: 0, endToken: 0, role: "SUBJECT", explanation: "英文枚举兜底" },
        { startToken: 1, endToken: 1, role: "PREDICATE", explanation: "另一个英文枚举" },
        { startToken: 2, endToken: 2, role: "COORDINATE_CLAUSE", explanation: "并列分句枚举" },
      ],
      grammarPoints: [],
      explanation: "兜底测试",
      modelProfileId: "profile-1",
    });

    const root = element.host.shadowRoot!;
    const annotations = [...root.querySelectorAll<HTMLElement>(".annotation")];
    // SUBJECT → 主语 → #2563eb, PREDICATE → 谓语 → #dc2626, COORDINATE_CLAUSE → 并列分句 → #0d9488
    expect(
      annotations.map((annotation) => annotation.style.getPropertyValue("--syntax-role-color")),
    ).toEqual(["#2563eb", "#dc2626", "#0d9488"]);
  });
});
