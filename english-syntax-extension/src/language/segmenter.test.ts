import { describe, expect, it } from "vitest";
import { createSentenceId, rebuildTokens, segmentBlock, tokenize } from "./segmenter";

describe("segmentBlock", () => {
  it("does not split an English honorific from its sentence", () => {
    expect(
      segmentBlock("Dr. Smith arrived. He sat down.").map((sentence) => sentence.text),
    ).toEqual(["Dr. Smith arrived.", "He sat down."]);
  });

  it("keeps deterministic UTF-16 offsets while excluding separator whitespace", () => {
    expect(segmentBlock("  First. \n Second!  ")).toEqual([
      { text: "First.", start: 2, end: 8 },
      { text: "Second!", start: 11, end: 18 },
    ]);
  });

  it("preserves inter-sentence and trailing gaps through sentence ranges", () => {
    expect(segmentBlock("First.  Second.  ")).toEqual([
      { text: "First.", start: 0, end: 6 },
      { text: "Second.", start: 8, end: 15 },
    ]);
  });

  it("keeps closing quotes and CJK punctuation with the English sentence", () => {
    expect(segmentBlock('He said, "Go." Next。 Last!').map((sentence) => sentence.text)).toEqual([
      'He said, "Go."',
      "Next。",
      "Last!",
    ]);
  });
});

describe("tokenize", () => {
  it("keeps straight-apostrophe contractions as one word token", () => {
    expect(
      tokenize("Learners don't stop.").map((token) => [token.text, token.punctuation]),
    ).toEqual([
      ["Learners", false],
      ["don't", false],
      ["stop", false],
      [".", true],
    ]);
  });

  it("keeps curly-apostrophe contractions and hyphenated words intact", () => {
    expect(tokenize("They’re well-prepared.").map((token) => token.text)).toEqual([
      "They’re",
      "well-prepared",
      ".",
    ]);
  });

  it("emits quotes and CJK punctuation as punctuation tokens after English", () => {
    expect(tokenize('Say "hello"。').map((token) => [token.text, token.punctuation])).toEqual([
      ["Say", false],
      ['"', true],
      ["hello", false],
      ['"', true],
      ["。", true],
    ]);
  });

  it("uses exclusive UTF-16 offsets and sequential token IDs", () => {
    expect(tokenize("  A 😊 test")).toEqual([
      { id: 0, text: "A", start: 2, end: 3, leadingWhitespace: "  ", punctuation: false },
      { id: 1, text: "😊", start: 4, end: 6, leadingWhitespace: " ", punctuation: true },
      { id: 2, text: "test", start: 7, end: 11, leadingWhitespace: " ", punctuation: false },
    ]);
  });

  it("reconstructs all whitespace between tokens exactly", () => {
    expect(rebuildTokens(tokenize("Hello,  world!"))).toBe("Hello,  world!");
    expect(rebuildTokens(tokenize("\tHello,\n\nworld!"))).toBe("\tHello,\n\nworld!");
  });
});

describe("createSentenceId", () => {
  it("returns the first 24 hex characters of the specified SHA-256 input", async () => {
    await expect(
      createSentenceId({
        sessionId: "session-1",
        blockId: "block-1",
        order: 0,
        normalizedText: "Same text.",
      }),
    ).resolves.toBe("667d275dc95590100189d49b");
  });

  it("distinguishes identical text at different block orders and remains stable", async () => {
    const input = {
      sessionId: "session-1",
      blockId: "block-1",
      order: 0,
      normalizedText: "Same text.",
    };

    const [first, repeated, reordered] = await Promise.all([
      createSentenceId(input),
      createSentenceId(input),
      createSentenceId({ ...input, order: 1 }),
    ]);

    expect(first).toBe(repeated);
    expect(first).toMatch(/^[a-f0-9]{24}$/u);
    expect(reordered).toBe("67499c3f8b3146c094b32179");
    expect(reordered).not.toBe(first);
  });
});
