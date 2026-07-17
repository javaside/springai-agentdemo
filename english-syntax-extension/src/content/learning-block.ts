import { GRAMMAR_LABELS, GrammarRole } from "../shared/grammar";
import type { CoreAnalysis, DetailAnalysis, Token, TokenRange } from "../shared/grammar";

// Gray #6b7280 is the shared "neutral/functional" bucket (APPOSITIVE,
// INDEPENDENT_ELEMENT, CONJUNCTION).
const ROLE_COLORS: Readonly<Record<GrammarRole, string>> = {
  [GrammarRole.SUBJECT]: "#2563eb",
  [GrammarRole.PREDICATE]: "#dc2626",
  [GrammarRole.OBJECT]: "#059669",
  [GrammarRole.PREDICATIVE]: "#0891b2",
  [GrammarRole.ATTRIBUTE]: "#7c3aed",
  [GrammarRole.ADVERBIAL]: "#d97706",
  [GrammarRole.COMPLEMENT]: "#0891b2",
  [GrammarRole.APPOSITIVE]: "#6b7280",
  [GrammarRole.SUBJECT_CLAUSE]: "#2563eb",
  [GrammarRole.OBJECT_CLAUSE]: "#059669",
  [GrammarRole.PREDICATIVE_CLAUSE]: "#0891b2",
  [GrammarRole.ATTRIBUTIVE_CLAUSE]: "#7c3aed",
  [GrammarRole.ADVERBIAL_CLAUSE]: "#d97706",
  [GrammarRole.INDEPENDENT_ELEMENT]: "#6b7280",
  [GrammarRole.COORDINATE_CLAUSE]: "#0d9488",
  [GrammarRole.CONJUNCTION]: "#6b7280",
};

const STYLES = `
:host {
  display: block;
  max-inline-size: 100%;
  font: inherit;
  color: inherit;
}

.sentences {
  max-inline-size: 100%;
}

.sentence,
.detail-annotations {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  column-gap: 0.5em;
  row-gap: 0.55em;
  max-inline-size: 100%;
  overflow-wrap: anywhere;
}

.component {
  appearance: none;
  display: inline-grid;
  grid-template-rows: repeat(3, auto);
  justify-items: center;
  min-inline-size: 0;
  max-inline-size: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  font: inherit;
  color: inherit;
  text-align: center;
  cursor: pointer;
  overflow-wrap: anywhere;
  transition: opacity 120ms ease;
}

.component:hover {
  opacity: 0.72;
}

.role,
.english,
.translation {
  min-inline-size: 0;
  max-inline-size: 100%;
  white-space: normal;
  overflow-wrap: anywhere;
}

.role,
.annotation-role {
  font-size: max(11px, 0.68em);
  color: var(--syntax-role-color, currentColor);
  opacity: 0.85;
}

.english,
.annotation-english {
  border-bottom: 1.5px solid
    color-mix(in srgb, var(--syntax-role-color, currentColor) 60%, transparent);
  justify-self: stretch;
  text-align: center;
}

.translation {
  font-size: max(12px, 0.8em);
  opacity: 0.78;
}

.punctuation {
  white-space: normal;
  border-bottom: 0;
}

.component:focus-visible,
.retry:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 2px;
}

.detail {
  display: block;
  inline-size: 100%;
  max-inline-size: 100%;
  margin-block: 0.75em;
  overflow-wrap: anywhere;
  border: 1px solid #e2e5e9;
  border-inline-start: 3px solid #0d9488;
  border-radius: 8px;
  background: #fafbfc;
  padding: 1em 1.125em;
}

.sentence-failure {
  inline-size: 100%;
  max-inline-size: 100%;
  margin-block: 0.35em;
  overflow-wrap: anywhere;
}

.detail-annotations {
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

.detail-structure {
  margin-block: 0.2em;
}

.grammar-points,
.detail-summary {
  margin-block-start: 0.875em;
  padding-block-start: 0.625em;
  border-block-start: 1px dashed #e2e5e9;
  font-size: 0.8125rem;
  line-height: 1.7;
}

.retry {
  margin-inline-start: 0.5em;
  border: 1px solid currentColor;
  border-radius: 0.25em;
  background: transparent;
  color: inherit;
  font: inherit;
}

@media (prefers-reduced-motion: reduce) {
  .component {
    transition: none;
  }
}
`;

export interface SyntaxFocusEventDetail {
  sentenceId: string;
  focus: TokenRange;
}

function createElement<K extends keyof HTMLElementTagNameMap>(
  name: K,
  className?: string,
  text?: string,
): HTMLElementTagNameMap[K] {
  const element = document.createElement(name);
  if (className !== undefined) {
    element.className = className;
  }
  if (text !== undefined) {
    element.textContent = text;
  }
  return element;
}

function sameFocus(left: TokenRange, right: TokenRange): boolean {
  return left.startToken === right.startToken && left.endToken === right.endToken;
}

function eventDetail(sentenceId: string, focus: TokenRange): SyntaxFocusEventDetail {
  return {
    sentenceId,
    focus: { startToken: focus.startToken, endToken: focus.endToken },
  };
}

function circledNumber(value: number): string {
  // Circled digits starting at ① (U+2460) cover 1-20; longer enumerations
  // fall back to plain numerals.
  return value >= 1 && value <= 20 ? String.fromCodePoint(0x2460 + value - 1) : `${value}`;
}

const FALLBACK_ANNOTATION_COLOR = "#6b7280";

const ROLE_COLOR_BY_LABEL: ReadonlyMap<string, string> = new Map(
  Object.values(GrammarRole).map((role) => [GRAMMAR_LABELS[role], ROLE_COLORS[role]]),
);

/**
 * 详解 structure 的 role 是模型自由文本（新版提示词要求中文，但提供英文枚举兜底）；
 * 优先精确匹配中文标签拿颜色，若为已知英文枚举值则映射后再查，否则统一灰色。
 */
function structureColor(role: string): string {
  // 优先中文标签精确匹配
  const directColor = ROLE_COLOR_BY_LABEL.get(role);
  if (directColor !== undefined) {
    return directColor;
  }
  // 枚举值兜底：英文枚举 → 中文标签 → 颜色
  if (role in GrammarRole) {
    const chineseLabel = GRAMMAR_LABELS[role as GrammarRole];
    const mappedColor = ROLE_COLOR_BY_LABEL.get(chineseLabel);
    if (mappedColor !== undefined) {
      return mappedColor;
    }
  }
  return FALLBACK_ANNOTATION_COLOR;
}

/** 标注块与解释列表共用的「①+角色名」标签。 */
function structureLabel(index: number, role: string): string {
  return `${circledNumber(index + 1)} ${role}`;
}

/**
 * 按 token 闭区间还原英文原文：首 token 去掉前导空格、区间末尾的标点不入下划线
 * （均与正文标注一致），其余 token 保留前导空格。区间反转、越界或不完整时返回
 * undefined。
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
    if (token.punctuation && index === range.endToken) {
      continue;
    }
    english += (index === range.startToken ? "" : token.leadingWhitespace) + token.text;
  }
  return english;
}

export class SyntaxLearningBlock {
  /**
   * A content script runs in an isolated world where `window.customElements`
   * is `null`, so this class cannot be registered as a custom element. It wraps
   * a plain host element and owns its shadow root by composition instead. Use
   * {@link host} wherever the surrounding DOM needs the actual element node.
   */
  readonly host: HTMLElement;
  readonly #sentences: HTMLElement;
  #expectedSentenceIds = new Set<string>();
  #expectedSentenceOrder: string[] = [];
  #resolvedSentenceIds = new Set<string>();
  #tokensBySentence = new Map<string, readonly Token[]>();

  constructor(ownerDocument: Document = document) {
    this.host = ownerDocument.createElement("div");
    this.host.dataset.syntaxLearningBlock = "";
    const root = this.host.attachShadow({ mode: "open" });
    const style = createElement("style", undefined, STYLES);
    this.#sentences = createElement("div", "sentences");
    root.append(style, this.#sentences);
  }

  dispatchEvent(event: Event): boolean {
    return this.host.dispatchEvent(event);
  }

  addEventListener(
    type: string,
    listener: EventListenerOrEventListenerObject,
    options?: boolean | AddEventListenerOptions,
  ): void {
    this.host.addEventListener(type, listener, options);
  }

  get isConnected(): boolean {
    return this.host.isConnected;
  }

  remove(): void {
    this.host.remove();
  }

  setExpectedSentenceIds(ids: readonly string[]): void {
    if (ids.length === 0) {
      throw new Error("Expected sentence IDs must be nonempty");
    }
    const expected = new Set(ids);
    if (expected.size !== ids.length) {
      throw new Error("Expected sentence IDs must not contain duplicate IDs");
    }
    if ([...expected].some((id) => id.length === 0)) {
      throw new Error("Expected sentence IDs must be nonempty strings");
    }
    this.#expectedSentenceIds = expected;
    this.#expectedSentenceOrder = [...ids];
    this.#resolvedSentenceIds = new Set();
    this.#tokensBySentence = new Map();
  }

  isReadyToReplace(): boolean {
    return (
      this.#expectedSentenceIds.size > 0 &&
      [...this.#expectedSentenceIds].every((id) => this.#resolvedSentenceIds.has(id))
    );
  }

  renderCore(sentence: string, tokens: readonly Token[], analysis: CoreAnalysis): void {
    this.#validateCoreInput(sentence, tokens, analysis);
    this.#tokensBySentence.set(analysis.sentenceId, [...tokens]);
    const sentenceElement = createElement("section", "sentence");
    sentenceElement.dataset.sentenceId = analysis.sentenceId;
    sentenceElement.setAttribute("aria-label", sentence);

    const coordinateClauseTotal = analysis.components.filter(
      (component) => component.role === GrammarRole.COORDINATE_CLAUSE,
    ).length;
    let coordinateClauseIndex = 0;
    let nextToken = 0;
    for (const component of analysis.components) {
      this.#appendPunctuation(sentenceElement, tokens, nextToken, component.startToken - 1);
      let label = GRAMMAR_LABELS[component.role];
      let accessibleLabel = label;
      if (component.role === GrammarRole.COORDINATE_CLAUSE && coordinateClauseTotal >= 2) {
        coordinateClauseIndex += 1;
        // The visible label keeps the circled digit; the accessible label uses
        // a plain digit that screen readers announce reliably.
        label = `${label}${circledNumber(coordinateClauseIndex)}`;
        accessibleLabel = `${accessibleLabel}${coordinateClauseIndex}`;
      }
      const componentElement = createElement("button", "component");
      componentElement.type = "button";
      componentElement.dataset.startToken = String(component.startToken);
      componentElement.dataset.endToken = String(component.endToken);
      componentElement.style.setProperty("--syntax-role-color", ROLE_COLORS[component.role]);
      componentElement.setAttribute("aria-label", `${accessibleLabel}：${component.translation}`);
      const role = createElement("span", "role", label);
      const english = createElement("span", "english");

      for (let index = component.startToken; index <= component.endToken; index += 1) {
        const token = tokens[index];
        if (token === undefined) {
          continue;
        }
        // The gap between components comes from the sentence layout; keeping
        // the first token's leading whitespace would stretch this component's
        // underline into that gap and blur the component boundary.
        const leadingWhitespace = index === component.startToken ? "" : token.leadingWhitespace;
        if (token.punctuation) {
          if (index === component.endToken) {
            continue;
          }
          english.append(createElement("span", "punctuation", leadingWhitespace + token.text));
        } else {
          english.append(document.createTextNode(leadingWhitespace + token.text));
        }
      }

      const translation = createElement("span", "translation", component.translation);
      componentElement.append(role, english, translation);
      componentElement.addEventListener("click", () => {
        // A second click on the component whose explanation is already open
        // toggles it closed instead of re-requesting the analysis.
        if (this.#closeDetail(analysis.sentenceId, component)) {
          return;
        }
        this.dispatchEvent(
          new CustomEvent<SyntaxFocusEventDetail>("syntax-detail-request", {
            bubbles: true,
            composed: true,
            detail: eventDetail(analysis.sentenceId, component),
          }),
        );
      });
      sentenceElement.append(componentElement);

      for (let index = component.startToken; index <= component.endToken; index += 1) {
        const token = tokens[index];
        if (token?.punctuation === true && index === component.endToken) {
          sentenceElement.append(
            createElement("span", "punctuation", token.leadingWhitespace + token.text),
          );
        }
      }
      nextToken = component.endToken + 1;
    }
    this.#appendPunctuation(sentenceElement, tokens, nextToken, tokens.length - 1);
    this.#placeSentenceSection(analysis.sentenceId, sentenceElement);
    this.#resolvedSentenceIds.add(analysis.sentenceId);
  }

  setDetailLoading(sentenceId: string, focus: TokenRange): void {
    const sentence = this.#sentence(sentenceId);
    if (sentence === null) {
      throw new Error(`Cannot render detail for unknown sentence: ${sentenceId}`);
    }
    // Only one explanation panel stays open at a time; opening a new one
    // switches away from whatever was open before.
    this.closeDetails();

    // Find the clicked component element by matching token range.
    // For sentences that failed core analysis, no components exist in the DOM,
    // so we fall back to appending the detail panel to the sentence container.
    const component = sentence.querySelector(
      `.component[data-start-token="${focus.startToken}"][data-end-token="${focus.endToken}"]`,
    );

    const detail = createElement("div", "detail detail-loading");
    detail.dataset.startToken = String(focus.startToken);
    detail.dataset.endToken = String(focus.endToken);
    detail.setAttribute("aria-live", "polite");
    detail.setAttribute("aria-busy", "true");
    detail.textContent = "正在加载详细解析…";

    if (component !== null) {
      // Insert detail panel immediately after the clicked component,
      // so it appears below the component regardless of sentence wrapping.
      component.after(detail);
    } else {
      // Fallback: append to sentence container (for failed sentences or edge cases).
      sentence.append(detail);
    }
  }

  closeDetails(): void {
    for (const detail of this.#sentences.querySelectorAll(".detail")) {
      detail.remove();
    }
  }

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
        // 区间越界/反转或纯标点（还原为空文本）：跳过标注块，下方解释列表仍按同一序号列出该条。
        if (english === undefined || english === "") {
          continue;
        }
        const annotation = createElement("span", "annotation");
        annotation.style.setProperty("--syntax-role-color", structureColor(structure.role));
        annotation.append(
          createElement("span", "annotation-role", structureLabel(index, structure.role)),
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
          createElement("strong", "detail-role", structureLabel(index, structure.role)),
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

  renderError(sentenceId: string, focus: TokenRange, message: string): void {
    this.#assertExpected(sentenceId);
    const detail = this.#findDetail(sentenceId, focus);
    if (detail === null) {
      return;
    }
    detail.className = "detail detail-error";
    detail.removeAttribute("aria-busy");
    detail.replaceChildren(createElement("span", "error-message", message));
    const retry = createElement("button", "retry", "重新解析");
    retry.type = "button";
    retry.addEventListener("click", () => {
      this.dispatchEvent(
        new CustomEvent<SyntaxFocusEventDetail>("syntax-reanalyze-request", {
          bubbles: true,
          composed: true,
          detail: eventDetail(sentenceId, focus),
        }),
      );
    });
    detail.append(retry);
    this.#resolvedSentenceIds.add(sentenceId);
  }

  renderFailure(sentenceId: string, sentence: string, message: string): void {
    this.#assertExpected(sentenceId);
    const failure = createElement("section", "sentence-failure");
    failure.dataset.sentenceId = sentenceId;
    failure.append(
      createElement("span", "original-sentence", sentence),
      createElement("span", "error-message", ` ${message}`),
    );
    const retry = createElement("button", "retry", "重新解析");
    retry.type = "button";
    retry.addEventListener("click", () => {
      this.dispatchEvent(
        new CustomEvent<SyntaxFocusEventDetail>("syntax-reanalyze-request", {
          bubbles: true,
          composed: true,
          detail: eventDetail(sentenceId, { startToken: 0, endToken: 0 }),
        }),
      );
    });
    failure.append(retry);
    this.#placeSentenceSection(sentenceId, failure);
    this.#resolvedSentenceIds.add(sentenceId);
  }

  #sentence(sentenceId: string): HTMLElement | null {
    for (const sentence of this.#sentences.children) {
      if (sentence instanceof HTMLElement && sentence.dataset.sentenceId === sentenceId) {
        return sentence;
      }
    }
    return null;
  }

  #appendPunctuation(
    sentence: HTMLElement,
    tokens: readonly Token[],
    startToken: number,
    endToken: number,
  ): void {
    for (let index = startToken; index <= endToken; index += 1) {
      const token = tokens[index];
      if (token?.punctuation === true) {
        sentence.append(createElement("span", "punctuation", token.leadingWhitespace + token.text));
      }
    }
  }

  #validateCoreInput(sentence: string, tokens: readonly Token[], analysis: CoreAnalysis): void {
    this.#assertExpected(analysis.sentenceId);
    let offset = 0;
    let reconstructed = "";
    for (const [index, token] of tokens.entries()) {
      reconstructed += token.leadingWhitespace + token.text;
      const expectedStart = offset + token.leadingWhitespace.length;
      if (
        token.id !== index ||
        token.start !== expectedStart ||
        token.end !== expectedStart + token.text.length
      ) {
        throw new Error("Original sentence and tokens do not match");
      }
      offset = token.end;
    }
    if (reconstructed !== sentence) {
      throw new Error("Original sentence and tokens do not match");
    }

    let previousEnd = -1;
    for (const component of analysis.components) {
      if (
        !Number.isInteger(component.startToken) ||
        !Number.isInteger(component.endToken) ||
        component.startToken < 0 ||
        component.endToken < component.startToken ||
        component.endToken >= tokens.length ||
        component.startToken <= previousEnd
      ) {
        throw new Error("Core component ranges must be ordered and non-overlapping");
      }
      previousEnd = component.endToken;
    }
  }

  #assertExpected(sentenceId: string): void {
    if (this.#expectedSentenceIds.size > 0 && !this.#expectedSentenceIds.has(sentenceId)) {
      throw new Error(`Unexpected sentence ID: ${sentenceId}`);
    }
  }

  #findDetail(sentenceId: string, focus: TokenRange): HTMLElement | null {
    const sentence = this.#sentence(sentenceId);
    if (sentence === null) {
      return null;
    }
    for (const candidate of sentence.querySelectorAll<HTMLElement>(".detail")) {
      const candidateFocus = {
        startToken: Number(candidate.dataset.startToken),
        endToken: Number(candidate.dataset.endToken),
      };
      if (sameFocus(candidateFocus, focus)) {
        return candidate;
      }
    }
    return null;
  }

  #closeDetail(sentenceId: string, focus: TokenRange): boolean {
    const detail = this.#findDetail(sentenceId, focus);
    if (detail === null) {
      return false;
    }
    detail.remove();
    return true;
  }

  /**
   * Puts a (re-)rendered sentence section at its position in the block's
   * source order. Appending re-renders and failures at the end used to move a
   * sentence — and every detail panel later opened under it — below the
   * sentences that follow it.
   */
  #placeSentenceSection(sentenceId: string, section: HTMLElement): void {
    const existing = this.#sentence(sentenceId);
    if (existing !== null) {
      existing.replaceWith(section);
      return;
    }
    const order = this.#expectedSentenceOrder.indexOf(sentenceId);
    if (order !== -1) {
      for (const sibling of this.#sentences.children) {
        if (!(sibling instanceof HTMLElement) || sibling.dataset.sentenceId === undefined) {
          continue;
        }
        const siblingOrder = this.#expectedSentenceOrder.indexOf(sibling.dataset.sentenceId);
        if (siblingOrder > order) {
          sibling.before(section);
          return;
        }
      }
    }
    this.#sentences.append(section);
  }
}
