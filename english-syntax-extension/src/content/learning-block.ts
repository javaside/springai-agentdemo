import { GRAMMAR_LABELS } from "../shared/grammar";
import type { CoreAnalysis, DetailAnalysis, Token, TokenRange } from "../shared/grammar";

const TAG_NAME = "syntax-learning-block";

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

.sentence {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  max-inline-size: 100%;
  overflow-wrap: anywhere;
}

.component {
  appearance: none;
  display: inline-grid;
  grid-template-rows: repeat(3, auto);
  min-inline-size: 0;
  max-inline-size: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  font: inherit;
  color: inherit;
  text-align: start;
  cursor: pointer;
  overflow-wrap: anywhere;
  transition: opacity 120ms ease;
}

.role,
.english,
.translation {
  min-inline-size: 0;
  max-inline-size: 100%;
  white-space: normal;
  overflow-wrap: anywhere;
}

.role {
  font-size: max(11px, 0.68em);
  opacity: 0.72;
}

.english {
  border-bottom: 2px solid currentColor;
}

.translation {
  opacity: 0.84;
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

.detail,
.sentence-failure {
  inline-size: 100%;
  max-inline-size: 100%;
  margin-block: 0.35em;
  overflow-wrap: anywhere;
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

export class SyntaxLearningBlock extends HTMLElement {
  readonly #sentences: HTMLElement;
  #expectedSentenceIds = new Set<string>();
  #resolvedSentenceIds = new Set<string>();

  constructor() {
    super();
    const root = this.attachShadow({ mode: "open" });
    const style = createElement("style", undefined, STYLES);
    this.#sentences = createElement("div", "sentences");
    root.append(style, this.#sentences);
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
    this.#resolvedSentenceIds = new Set();
  }

  isReadyToReplace(): boolean {
    return (
      this.#expectedSentenceIds.size > 0 &&
      [...this.#expectedSentenceIds].every((id) => this.#resolvedSentenceIds.has(id))
    );
  }

  renderCore(sentence: string, tokens: readonly Token[], analysis: CoreAnalysis): void {
    this.#validateCoreInput(sentence, tokens, analysis);
    this.#sentence(analysis.sentenceId)?.remove();
    const sentenceElement = createElement("section", "sentence");
    sentenceElement.dataset.sentenceId = analysis.sentenceId;
    sentenceElement.setAttribute("aria-label", sentence);

    let nextToken = 0;
    for (const component of analysis.components) {
      this.#appendPunctuation(sentenceElement, tokens, nextToken, component.startToken - 1);
      const componentElement = createElement("button", "component");
      componentElement.type = "button";
      componentElement.dataset.startToken = String(component.startToken);
      componentElement.dataset.endToken = String(component.endToken);
      componentElement.setAttribute(
        "aria-label",
        `${GRAMMAR_LABELS[component.role]}：${component.translation}`,
      );
      const role = createElement("span", "role", GRAMMAR_LABELS[component.role]);
      const english = createElement("span", "english");

      for (let index = component.startToken; index <= component.endToken; index += 1) {
        const token = tokens[index];
        if (token === undefined) {
          continue;
        }
        if (token.punctuation) {
          if (index === component.endToken) {
            continue;
          }
          english.append(
            createElement("span", "punctuation", token.leadingWhitespace + token.text),
          );
        } else {
          english.append(document.createTextNode(token.leadingWhitespace + token.text));
        }
      }

      const translation = createElement("span", "translation", component.translation);
      componentElement.append(role, english, translation);
      componentElement.addEventListener("click", () => {
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
    this.#sentences.append(sentenceElement);
    this.#resolvedSentenceIds.add(analysis.sentenceId);
  }

  setDetailLoading(sentenceId: string, focus: TokenRange): void {
    const detail = this.#detailContainer(sentenceId, focus);
    detail.className = "detail detail-loading";
    detail.setAttribute("aria-live", "polite");
    detail.setAttribute("aria-busy", "true");
    detail.textContent = "正在加载详细解析…";
  }

  renderDetail(detailAnalysis: DetailAnalysis): void {
    const detail = this.#detailContainer(detailAnalysis.sentenceId, detailAnalysis.focus);
    detail.className = "detail";
    detail.removeAttribute("aria-busy");
    detail.replaceChildren();

    for (const structure of detailAnalysis.structures) {
      const row = createElement("div", "detail-structure");
      row.append(
        createElement("strong", "detail-role", structure.role),
        document.createTextNode("："),
        createElement("span", "detail-explanation", structure.explanation),
      );
      detail.append(row);
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
    const detail = this.#detailContainer(sentenceId, focus);
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
    this.#sentence(sentenceId)?.remove();
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
    this.#sentences.append(failure);
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

  #detailContainer(sentenceId: string, focus: TokenRange): HTMLElement {
    const sentence = this.#sentence(sentenceId);
    if (sentence === null) {
      throw new Error(`Cannot render detail for unknown sentence: ${sentenceId}`);
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
    const detail = createElement("div", "detail");
    detail.dataset.startToken = String(focus.startToken);
    detail.dataset.endToken = String(focus.endToken);
    sentence.append(detail);
    return detail;
  }
}

if (!customElements.get(TAG_NAME)) {
  customElements.define(TAG_NAME, SyntaxLearningBlock);
}

declare global {
  interface HTMLElementTagNameMap {
    "syntax-learning-block": SyntaxLearningBlock;
  }
}
