import { SyntaxLearningBlock } from "./learning-block";

const STYLE_ATTRIBUTE = "data-syntax-learning-hide";

export interface SentenceFailure {
  sentenceId: string;
  sentence: string;
  message: string;
}

function ensureHideStyle(document: Document): void {
  if (document.head.querySelector(`style[${STYLE_ATTRIBUTE}]`) !== null) {
    return;
  }
  const style = document.createElement("style");
  style.setAttribute(STYLE_ATTRIBUTE, "");
  style.textContent = `.${BlockReplacement.hiddenClass} { display: none !important; }`;
  document.head.append(style);
}

export class BlockReplacement {
  static readonly hiddenClass = "syntax-learning-original-hidden";

  #original: HTMLElement | null = null;
  #block: HTMLElement | null = null;
  #observer: MutationObserver | null = null;

  get active(): boolean {
    return this.#original !== null && this.#block !== null;
  }

  show(original: HTMLElement, block: HTMLElement): void {
    this.restore();
    if (original.parentNode === null) {
      return;
    }
    ensureHideStyle(original.ownerDocument);
    original.after(block);
    original.classList.add(BlockReplacement.hiddenClass);
    this.#original = original;
    this.#block = block;
    this.#observePageRemoval(original.ownerDocument);
  }

  showPartialFailure(
    original: HTMLElement,
    block: SyntaxLearningBlock,
    failures: readonly SentenceFailure[],
  ): void {
    if (failures.length === 0) {
      return;
    }
    for (const failure of failures) {
      block.renderFailure(failure.sentenceId, failure.sentence, failure.message);
    }
    this.show(original, block);
  }

  restore(): void {
    this.#observer?.disconnect();
    this.#observer = null;
    this.#original?.classList.remove(BlockReplacement.hiddenClass);
    this.#block?.remove();
    this.#original = null;
    this.#block = null;
  }

  #observePageRemoval(document: Document): void {
    this.#observer = new MutationObserver(() => {
      if (this.#original?.isConnected === false) {
        this.restore();
      }
    });
    this.#observer.observe(document.documentElement, { childList: true, subtree: true });
  }
}
