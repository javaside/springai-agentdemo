import { SyntaxLearningBlock } from "./learning-block";

const STYLE_ATTRIBUTE = "data-syntax-learning-hide";
const SAFE_SUFFIX = /^[A-Za-z0-9_-]+$/u;
const reservedHiddenClasses = new Set<string>();
let nextHiddenClassId = 0;

export type HiddenClassSuffixFactory = (attempt: number) => string;

function defaultHiddenClassSuffix(): string {
  nextHiddenClassId += 1;
  return String(nextHiddenClassId);
}

export interface SentenceFailure {
  sentenceId: string;
  sentence: string;
  message: string;
}

function createHideStyle(document: Document, hiddenClass: string): HTMLStyleElement {
  const style = document.createElement("style");
  style.setAttribute(STYLE_ATTRIBUTE, hiddenClass);
  style.textContent = `.${hiddenClass} { display: none !important; }`;
  return style;
}

export class BlockReplacement {
  static readonly hiddenClass = "syntax-learning-original-hidden";

  #original: HTMLElement | null = null;
  #block: HTMLElement | null = null;
  #observer: MutationObserver | null = null;
  #appliedHiddenClass: string | null = null;
  #ownedStyle: HTMLStyleElement | null = null;
  readonly #hiddenClassSuffixFactory: HiddenClassSuffixFactory;

  constructor(hiddenClassSuffixFactory: HiddenClassSuffixFactory = defaultHiddenClassSuffix) {
    this.#hiddenClassSuffixFactory = hiddenClassSuffixFactory;
  }

  get active(): boolean {
    return this.#original !== null && this.#block !== null;
  }

  show(original: HTMLElement, block: SyntaxLearningBlock): void {
    if (!(block instanceof SyntaxLearningBlock)) {
      throw new TypeError("BlockReplacement requires a SyntaxLearningBlock");
    }
    if (!block.isReadyToReplace()) {
      return;
    }
    this.restore();
    if (original.parentNode === null) {
      return;
    }
    const hiddenClass = this.#reserveHiddenClass(original);
    const style = createHideStyle(original.ownerDocument, hiddenClass);
    original.ownerDocument.head.append(style);
    original.after(block);
    original.classList.add(hiddenClass);
    this.#original = original;
    this.#block = block;
    this.#appliedHiddenClass = hiddenClass;
    this.#ownedStyle = style;
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
    if (this.#appliedHiddenClass !== null) {
      this.#original?.classList.remove(this.#appliedHiddenClass);
      reservedHiddenClasses.delete(this.#appliedHiddenClass);
    }
    this.#ownedStyle?.remove();
    this.#block?.remove();
    this.#original = null;
    this.#block = null;
    this.#appliedHiddenClass = null;
    this.#ownedStyle = null;
  }

  currentElement(original: HTMLElement): Element {
    return this.#block ?? original;
  }

  #observePageRemoval(document: Document): void {
    this.#observer = new MutationObserver(() => {
      if (this.#original?.isConnected === false) {
        this.restore();
      }
    });
    this.#observer.observe(document.documentElement, { childList: true, subtree: true });
  }

  #reserveHiddenClass(original: HTMLElement): string {
    for (let attempt = 0; attempt < 100; attempt += 1) {
      const suffix = this.#hiddenClassSuffixFactory(attempt);
      if (!SAFE_SUFFIX.test(suffix)) {
        throw new Error(
          "Hidden-class suffix must contain only letters, numbers, underscores, or hyphens",
        );
      }
      const candidate = `${BlockReplacement.hiddenClass}-${suffix}`;
      if (
        !reservedHiddenClasses.has(candidate) &&
        original.ownerDocument.getElementsByClassName(candidate).length === 0
      ) {
        reservedHiddenClasses.add(candidate);
        return candidate;
      }
    }
    throw new Error("Unable to allocate a collision-free syntax-learning hide class");
  }
}
