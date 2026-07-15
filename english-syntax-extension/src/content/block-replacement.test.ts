// @vitest-environment happy-dom

import { beforeEach, describe, expect, it, vi } from "vitest";
import { CORE_SCHEMA_VERSION } from "../shared/versions";
import { GrammarRole } from "../shared/grammar";
import type { SyntaxLearningBlock } from "./learning-block";
import { BlockReplacement } from "./block-replacement";
import "./learning-block";

const sentence = "Learners read.";
const tokens = [
  { id: 0, text: "Learners", start: 0, end: 8, leadingWhitespace: "", punctuation: false },
  { id: 1, text: "read", start: 9, end: 13, leadingWhitespace: " ", punctuation: false },
  { id: 2, text: ".", start: 13, end: 14, leadingWhitespace: "", punctuation: true },
];

function learningBlock(expectedIds: readonly string[]): SyntaxLearningBlock {
  const block = document.createElement("syntax-learning-block");
  if (expectedIds.length > 0) {
    block.setExpectedSentenceIds(expectedIds);
  }
  return block;
}

function renderReady(block: SyntaxLearningBlock, sentenceId = "sentence-1"): void {
  block.renderCore(sentence, tokens, {
    schemaVersion: CORE_SCHEMA_VERSION,
    sentenceId,
    components: [
      { startToken: 0, endToken: 0, role: GrammarRole.SUBJECT, translation: "学习者" },
      { startToken: 1, endToken: 2, role: GrammarRole.PREDICATE, translation: "阅读" },
    ],
    modelProfileId: "profile-1",
  });
}

describe("BlockReplacement", () => {
  beforeEach(() => {
    document.head.replaceChildren();
    document.body.innerHTML =
      '<p class="article-copy emphasized" style="color: purple">Original</p>';
  });

  it("restores the exact original node without changing its class, style, state, or listeners", () => {
    const original = document.querySelector("p")!;
    const originalClass = original.className;
    const originalStyle = original.getAttribute("style");
    const listener = vi.fn();
    original.addEventListener("click", listener);
    const block = learningBlock(["sentence-1"]);
    renderReady(block);
    const replacement = new BlockReplacement();

    replacement.show(original, block);
    expect(original.nextElementSibling).toBe(block);
    expect(original.className).toContain("article-copy");
    expect(original.getAttribute("style")).toBe(originalStyle);
    expect(getComputedStyle(original).display).toBe("none");
    expect(original.hidden).toBe(false);

    replacement.restore();
    expect(document.querySelector("p")).toBe(original);
    expect(original.hidden).toBe(false);
    expect(original.className).toBe(originalClass);
    expect(original.getAttribute("style")).toBe(originalStyle);
    original.click();
    expect(listener).toHaveBeenCalledOnce();
    expect(block.isConnected).toBe(false);
  });

  it("does not insert or hide an empty or partially resolved learning block", () => {
    const original = document.querySelector("p")!;
    const replacement = new BlockReplacement();
    const empty = learningBlock([]);

    replacement.show(original, empty);
    expect(empty.isConnected).toBe(false);
    expect(original.classList.contains(BlockReplacement.hiddenClass)).toBe(false);

    const partial = learningBlock(["sentence-1", "sentence-2"]);
    renderReady(partial);
    replacement.show(original, partial);
    expect(partial.isConnected).toBe(false);
    expect(original.classList.contains(BlockReplacement.hiddenClass)).toBe(false);
  });

  it("rejects a non-learning element at runtime", () => {
    const original = document.querySelector("p")!;
    const replacement = new BlockReplacement();
    const generic = document.createElement("div") as unknown as SyntaxLearningBlock;

    expect(() => replacement.show(original, generic)).toThrow(/SyntaxLearningBlock/u);
    expect(generic.isConnected).toBe(false);
    expect(original.classList.contains(BlockReplacement.hiddenClass)).toBe(false);
  });

  it("inserts and hides a fully ready success block", () => {
    const original = document.querySelector("p")!;
    const block = learningBlock(["sentence-1"]);
    renderReady(block);
    const replacement = new BlockReplacement();

    replacement.show(original, block);

    expect(original.nextElementSibling).toBe(block);
    expect(original.classList.contains(BlockReplacement.hiddenClass)).toBe(true);
  });

  it("renders every failed sentence as original text before hiding a partially successful block", () => {
    const original = document.querySelector("p")!;
    const block = learningBlock(["sentence-1", "sentence-2"]);
    renderReady(block);
    const replacement = new BlockReplacement();

    replacement.showPartialFailure(original, block, [
      { sentenceId: "sentence-2", sentence: "This sentence stays original.", message: "解析失败" },
    ]);

    const failure = block.shadowRoot!.querySelector(".sentence-failure")!;
    expect(failure.textContent).toContain("This sentence stays original.");
    expect(failure.textContent).toContain("解析失败");
    expect(original.classList.contains(BlockReplacement.hiddenClass)).toBe(true);
  });

  it("does not hide when a partial failure has not been represented", () => {
    const original = document.querySelector("p")!;
    const block = learningBlock(["sentence-1", "sentence-2"]);
    renderReady(block);
    const replacement = new BlockReplacement();

    replacement.showPartialFailure(original, block, []);

    expect(original.classList.contains(BlockReplacement.hiddenClass)).toBe(false);
    expect(block.isConnected).toBe(false);
  });

  it("does not hide when one expected failure row is still missing", () => {
    const original = document.querySelector("p")!;
    const block = learningBlock(["sentence-1", "sentence-2", "sentence-3"]);
    renderReady(block);
    const replacement = new BlockReplacement();

    replacement.showPartialFailure(original, block, [
      { sentenceId: "sentence-2", sentence: "Second failed.", message: "解析失败" },
    ]);

    expect(block.shadowRoot!.querySelector("[data-sentence-id='sentence-2']")).not.toBeNull();
    expect(original.classList.contains(BlockReplacement.hiddenClass)).toBe(false);
    expect(block.isConnected).toBe(false);
  });

  it("removes the learning sibling when the page removes the original", async () => {
    const original = document.querySelector("p")!;
    const block = learningBlock(["sentence-1"]);
    renderReady(block);
    const replacement = new BlockReplacement();
    replacement.show(original, block);

    original.remove();
    await Promise.resolve();

    expect(block.isConnected).toBe(false);
    expect(replacement.active).toBe(false);
  });

  it("moves cleanly to a new pair and registers one important hiding rule", () => {
    const first = document.querySelector("p")!;
    const firstBlock = learningBlock(["sentence-1"]);
    renderReady(firstBlock);
    const replacement = new BlockReplacement();
    replacement.show(first, firstBlock);
    document.body.insertAdjacentHTML("beforeend", "<p>Second</p>");
    const second = document.querySelectorAll("p")[1]!;
    const secondBlock = learningBlock(["sentence-2"]);
    renderReady(secondBlock, "sentence-2");

    replacement.show(second, secondBlock);

    expect(first.classList.contains(BlockReplacement.hiddenClass)).toBe(false);
    expect(firstBlock.isConnected).toBe(false);
    expect(document.querySelectorAll(`style[data-syntax-learning-hide]`)).toHaveLength(1);
    expect(document.querySelector("style")!.textContent).toContain("display: none !important");
  });

  it("preserves a pre-existing hide-class collision through show and restore", () => {
    const original = document.querySelector("p")!;
    original.classList.add(BlockReplacement.hiddenClass);
    const originalClass = original.className;
    const block = learningBlock(["sentence-1"]);
    renderReady(block);
    const replacement = new BlockReplacement();

    replacement.show(original, block);
    replacement.restore();

    expect(original.className).toBe(originalClass);
    expect(original.classList.contains(BlockReplacement.hiddenClass)).toBe(true);
  });
});
