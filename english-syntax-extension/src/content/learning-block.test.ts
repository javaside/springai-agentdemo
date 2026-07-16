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

function block(): SyntaxLearningBlock {
  return new SyntaxLearningBlock();
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

    const styles = root.querySelector("style")!.textContent!;
    expect(styles).toContain("border-bottom: 1.5px solid");
    expect(styles).toContain("var(--syntax-role-color");
    // 成分不再有底色块
    expect(styles).not.toMatch(/\.component\s*\{[^}]*background:\s*color-mix/u);
  });
});
