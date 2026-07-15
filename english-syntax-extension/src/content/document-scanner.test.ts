// @vitest-environment happy-dom

import { readFileSync } from "node:fs";
import { beforeEach, describe, expect, it } from "vitest";
import { nearestSafeBlock, scanDocument } from "./document-scanner";

const fixturePaths = {
  article: "tests/fixtures/pages/article.html",
  interactive: "tests/fixtures/pages/interactive.html",
} as const;

function fixture(name: keyof typeof fixturePaths): string {
  return readFileSync(fixturePaths[name], "utf8");
}

describe("scanDocument", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
  });

  it("skips an incidental article without eligible content before the real main", () => {
    document.body.innerHTML = fixture("article");

    const blocks = scanDocument(document);

    expect(blocks.map(({ element }) => element.id || element.tagName)).toEqual([
      "intro",
      "linked",
      "quote",
      "list-item",
    ]);
    expect(
      blocks.every(({ element }) => /^(?:H[1-6]|P|LI|BLOCKQUOTE)$/u.test(element.tagName)),
    ).toBe(true);
    expect(blocks.map(({ text }) => text)).toContain(
      "This English paragraph contains a helpful reference link for readers.",
    );
  });

  it("chooses the only semantic root containing valid safe English candidates", () => {
    document.body.innerHTML = `
      <article>
        <h2>Short note</h2>
        <form><p>This otherwise eligible English text is unsafe form content.</p></form>
        <p>This paragraph includes an unsafe <button>interactive control</button>.</p>
      </article>
      <div role="main">
        <p id="qualified-root-copy">This safe English paragraph belongs to the qualified semantic root.</p>
      </div>`;

    expect(scanDocument(document).map(({ element }) => element.id)).toEqual([
      "qualified-root-copy",
    ]);
  });

  it("chooses the more specific nested semantic root when content scores tie", () => {
    document.body.innerHTML = fixture("article");
    document.querySelector("#incidental-article")!.remove();
    scanDocument(document);
    const laterMainCopy = document.createElement("p");
    laterMainCopy.textContent =
      "This later English paragraph is directly inside main but outside the nested article.";
    document.querySelector("main")!.append(laterMainCopy);

    expect(nearestSafeBlock(laterMainCopy)).toBeNull();
  });

  it("uses a text-density fallback and penalizes link-heavy navigation", () => {
    document.body.innerHTML = `
      <div id="link-farm">
        <p><a href="#1">First linked navigation destination with repeated words</a></p>
        <p><a href="#2">Second linked navigation destination with repeated words</a></p>
      </div>
      <section id="story">
        <p id="fallback-one">A patient writer explains the central idea with clear supporting details.</p>
        <p id="fallback-two">The following paragraph develops the argument for careful English readers.</p>
      </section>`;

    expect(scanDocument(document).map(({ element }) => element.id)).toEqual([
      "fallback-one",
      "fallback-two",
    ]);
  });

  it("requires twenty visible characters and English-dominant letter words in automatic mode", () => {
    document.body.innerHTML = `<main>
      <h2 id="short">Brief heading</h2>
      <p id="mixed">这是 一个 中文 句子 avec quelques mots English</p>
      <p id="glued">abc中文 def中文 ghi中文 jkl中文 mno中文 pqr中文</p>
      <p id="hidden-tail">Brief text <span hidden>with a long hidden English continuation</span></p>
      <p id="english">English readers can reliably recognize this sufficiently long sentence.</p>
    </main>`;

    expect(scanDocument(document).map(({ element }) => element.id)).toEqual(["english"]);
  });

  it("assigns stable WeakMap IDs without changing page attributes", () => {
    document.body.innerHTML = `<main><p id="copy">This English paragraph is deliberately long enough for automatic scanning.</p></main>`;
    const element = document.querySelector("#copy")!;
    const attributes = element.getAttributeNames();

    const first = scanDocument(document)[0];
    const second = scanDocument(document)[0];

    expect(first?.id).toBe(second?.id);
    expect(first?.id).toMatch(/^block-\d+$/u);
    expect(element.getAttributeNames()).toEqual(attributes);
  });
});

describe("nearestSafeBlock", () => {
  beforeEach(() => {
    document.body.innerHTML = fixture("interactive");
  });

  it("walks from a descendant to the nearest safe block inside principal content", () => {
    scanDocument(document);
    const safe = document.querySelector("#safe")!;
    const child = document.createElement("span");
    child.textContent = "chosen words";
    safe.append(child);

    expect(nearestSafeBlock(child)?.element).toBe(safe);
    expect(nearestSafeBlock(document.querySelector("#outside-short"))).toBeNull();
  });

  it("lets a selected short block bypass the automatic root and length limits", () => {
    const short = document.querySelector("#outside-short")!;

    expect(nearestSafeBlock(short, { selection: true })?.text).toBe("Tiny English text");
  });

  it("never accepts selection in editable or password content", () => {
    expect(nearestSafeBlock(document.querySelector("#editable"), { selection: true })).toBeNull();
    expect(nearestSafeBlock(document.querySelector("#password"), { selection: true })).toBeNull();
    expect(
      nearestSafeBlock(document.querySelector("#nested-input"), { selection: true }),
    ).toBeNull();
  });
});
