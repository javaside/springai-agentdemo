export interface CandidateBlock {
  id: string;
  element: Element;
  text: string;
}

export interface NearestSafeBlockOptions {
  selection?: boolean;
}

const BLOCK_SELECTOR = "h1,h2,h3,h4,h5,h6,p,li,blockquote";
const SEMANTIC_ROOT_SELECTOR = "article,main,[role='main']";
const EXCLUSION_SELECTOR =
  "nav,aside,footer,form,pre,code,script,style,noscript,template,svg,canvas,iframe," +
  "[contenteditable],[hidden],[aria-hidden='true']";
const UNSAFE_DESCENDANT_SELECTOR =
  "button,input,textarea,select,video,audio,canvas,iframe,[contenteditable],img,picture";
const MINIMUM_AUTO_TEXT_LENGTH = 20;

const blockIds = new WeakMap<Element, string>();
const principalRoots = new WeakMap<Document, Element>();
let nextBlockId = 1;

function queryElements(root: ParentNode, selector: string): Element[] {
  const matches = Array.from(root.querySelectorAll(selector));
  if (root instanceof Element && root.matches(selector)) matches.unshift(root);
  return matches;
}

function normalizedText(element: Element): string {
  const walker = element.ownerDocument.createTreeWalker(element, NodeFilter.SHOW_TEXT);
  let text = "";
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    const parent = node.parentElement;
    if (parent !== null && isLayoutVisible(parent)) text += node.textContent ?? "";
  }
  return text.replace(/\s+/gu, " ").trim();
}

function isLayoutVisible(element: Element): boolean {
  for (let current: Element | null = element; current !== null; current = current.parentElement) {
    if (current.matches("[hidden],[aria-hidden='true']")) return false;
    const style = getComputedStyle(current);
    if (
      style.display === "none" ||
      style.visibility === "hidden" ||
      style.visibility === "collapse"
    ) {
      return false;
    }
  }
  return true;
}

function isEnglishDominant(text: string): boolean {
  const letterWords = text.match(/\p{L}+(?:['’-]\p{L}+)*/gu) ?? [];
  const englishWordCount = letterWords.filter((word) =>
    /^[A-Za-z]+(?:['’-][A-Za-z]+)*$/u.test(word),
  ).length;
  return englishWordCount / Math.max(1, letterWords.length) >= 0.6;
}

function isSafeElement(element: Element): boolean {
  return (
    element.matches(BLOCK_SELECTOR) &&
    element.closest(EXCLUSION_SELECTOR) === null &&
    element.querySelector(UNSAFE_DESCENDANT_SELECTOR) === null &&
    isLayoutVisible(element)
  );
}

function getBlockId(element: Element): string {
  const existing = blockIds.get(element);
  if (existing !== undefined) return existing;
  const id = `block-${nextBlockId++}`;
  blockIds.set(element, id);
  return id;
}

function createCandidate(element: Element, automatic: boolean): CandidateBlock | null {
  if (!isSafeElement(element)) return null;
  const text = normalizedText(element);
  if (
    text.length === 0 ||
    !isEnglishDominant(text) ||
    (automatic && text.length < MINIMUM_AUTO_TEXT_LENGTH)
  ) {
    return null;
  }
  return { id: getBlockId(element), element, text };
}

function semanticRoot(root: ParentNode): Element | null {
  return (
    queryElements(root, SEMANTIC_ROOT_SELECTOR).find(
      (element) => element.closest(EXCLUSION_SELECTOR) === null && isLayoutVisible(element),
    ) ?? null
  );
}

function fallbackRoot(root: ParentNode): Element | null {
  const safeBlocks = queryElements(root, BLOCK_SELECTOR).filter(
    (element) => createCandidate(element, true) !== null,
  );
  const scores = new Map<Element, { textLength: number; linkedTextLength: number }>();

  for (const block of safeBlocks) {
    const length = normalizedText(block).length;
    const linkedLength = Array.from(block.querySelectorAll("a")).reduce(
      (total, link) => total + normalizedText(link).length,
      0,
    );
    for (let ancestor = block.parentElement; ancestor !== null; ancestor = ancestor.parentElement) {
      if (!root.contains(ancestor) && ancestor !== root) break;
      if (ancestor.closest(EXCLUSION_SELECTOR) !== null || !isLayoutVisible(ancestor)) continue;
      const score = scores.get(ancestor) ?? { textLength: 0, linkedTextLength: 0 };
      score.textLength += length;
      score.linkedTextLength += linkedLength;
      scores.set(ancestor, score);
      if (ancestor === root) break;
    }
  }

  let best: Element | null = null;
  let bestScore = Number.NEGATIVE_INFINITY;
  for (const [element, { textLength, linkedTextLength }] of scores) {
    const score = textLength * (1 - (2 * linkedTextLength) / Math.max(1, textLength));
    if (score > bestScore) {
      best = element;
      bestScore = score;
    }
  }
  return best;
}

function ownerDocument(root: ParentNode): Document | null {
  if (root instanceof Document) return root;
  return root.ownerDocument;
}

function selectPrincipalRoot(root: ParentNode): Element | null {
  return semanticRoot(root) ?? fallbackRoot(root);
}

export function scanDocument(root: ParentNode): CandidateBlock[] {
  const principalRoot = selectPrincipalRoot(root);
  const document = ownerDocument(root);
  if (principalRoot === null) return [];
  if (document !== null) principalRoots.set(document, principalRoot);
  return queryElements(principalRoot, BLOCK_SELECTOR).flatMap((element) => {
    const candidate = createCandidate(element, true);
    return candidate === null ? [] : [candidate];
  });
}

export function nearestSafeBlock(
  target: EventTarget | null,
  options: NearestSafeBlockOptions = {},
): CandidateBlock | null {
  const start =
    target instanceof Element ? target : target instanceof Node ? target.parentElement : null;
  if (start === null) return null;
  if (
    start.matches("input[type='password'],textarea,[contenteditable]") ||
    start.closest("[contenteditable]") !== null
  ) {
    return null;
  }

  const document = start.ownerDocument;
  const principalRoot = options.selection
    ? null
    : (principalRoots.get(document) ?? selectPrincipalRoot(document));
  if (!options.selection && (principalRoot === null || !principalRoot.contains(start))) return null;

  for (let current: Element | null = start; current !== null; current = current.parentElement) {
    if (current.matches(BLOCK_SELECTOR)) {
      const candidate = createCandidate(current, !options.selection);
      if (candidate !== null) return candidate;
    }
    if (!options.selection && current === principalRoot) break;
  }
  return null;
}
