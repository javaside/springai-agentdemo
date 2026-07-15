// @vitest-environment happy-dom

import { beforeEach, describe, expect, it, vi } from "vitest";
import { BlockReplacement } from "./block-replacement";
import "./learning-block";

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
    const block = document.createElement("syntax-learning-block");
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

  it("renders every failed sentence as original text before hiding a partially successful block", () => {
    const original = document.querySelector("p")!;
    const block = document.createElement("syntax-learning-block");
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
    const block = document.createElement("syntax-learning-block");
    const replacement = new BlockReplacement();

    replacement.showPartialFailure(original, block, []);

    expect(original.classList.contains(BlockReplacement.hiddenClass)).toBe(false);
    expect(block.isConnected).toBe(false);
  });

  it("removes the learning sibling when the page removes the original", async () => {
    const original = document.querySelector("p")!;
    const block = document.createElement("syntax-learning-block");
    const replacement = new BlockReplacement();
    replacement.show(original, block);

    original.remove();
    await Promise.resolve();

    expect(block.isConnected).toBe(false);
    expect(replacement.active).toBe(false);
  });

  it("moves cleanly to a new pair and registers one important hiding rule", () => {
    const first = document.querySelector("p")!;
    const firstBlock = document.createElement("syntax-learning-block");
    const replacement = new BlockReplacement();
    replacement.show(first, firstBlock);
    document.body.insertAdjacentHTML("beforeend", "<p>Second</p>");
    const second = document.querySelectorAll("p")[1]!;
    const secondBlock = document.createElement("syntax-learning-block");

    replacement.show(second, secondBlock);

    expect(first.classList.contains(BlockReplacement.hiddenClass)).toBe(false);
    expect(firstBlock.isConnected).toBe(false);
    expect(document.querySelectorAll(`style[data-syntax-learning-hide]`)).toHaveLength(1);
    expect(document.querySelector("style")!.textContent).toContain("display: none !important");
  });
});
