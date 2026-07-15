import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { rebuildTokens, segmentBlock, tokenize } from "./segmenter";

/**
 * The teaching corpus feeds manual regression passes and demos. CI validates
 * only structural invariants — segmentation, lossless tokenization, and the
 * declared lexical token counts. It must never assert a unique model answer,
 * because different models may split grammatical components differently.
 */

interface TeachingSentence {
  id: string;
  category: string;
  text: string;
  requiredLexicalTokenCount: number;
}

const EXPECTED_CATEGORIES = [
  "basic-svo",
  "copular",
  "passive",
  "attribute",
  "adverbial",
  "complement",
  "noun-clause",
  "relative-clause",
  "adverbial-clause",
  "non-finite",
  "inversion-ellipsis-emphasis",
  "quotation-abbreviation-long",
];

const corpus = JSON.parse(
  readFileSync(new URL("../../tests/fixtures/teaching-sentences.json", import.meta.url), "utf8"),
) as TeachingSentence[];

describe("teaching sentence corpus", () => {
  it("covers all twelve categories with three sentences each", () => {
    expect(corpus.length).toBeGreaterThanOrEqual(36);
    const byCategory = new Map<string, number>();
    for (const sentence of corpus) {
      byCategory.set(sentence.category, (byCategory.get(sentence.category) ?? 0) + 1);
    }
    expect([...byCategory.keys()].sort()).toEqual([...EXPECTED_CATEGORIES].sort());
    for (const category of EXPECTED_CATEGORIES) {
      expect(byCategory.get(category), category).toBe(3);
    }
  });

  it("uses unique ids and unique sentence texts", () => {
    expect(new Set(corpus.map(({ id }) => id)).size).toBe(corpus.length);
    expect(new Set(corpus.map(({ text }) => text)).size).toBe(corpus.length);
    for (const sentence of corpus) {
      expect(sentence.id).toMatch(/^[a-z0-9]+(?:-[a-z0-9]+)*$/);
    }
  });

  it.each(corpus.map((sentence) => [sentence.id, sentence] as const))(
    "%s segments to one sentence and tokenizes losslessly",
    (_id, sentence) => {
      const segments = segmentBlock(sentence.text);
      expect(segments).toHaveLength(1);
      expect(segments[0]!.text).toBe(sentence.text);

      const tokens = tokenize(sentence.text);
      expect(rebuildTokens(tokens)).toBe(sentence.text);
      expect(tokens.map(({ id }) => id)).toEqual(tokens.map((_token, index) => index));

      const lexicalCount = tokens.filter((token) => !token.punctuation).length;
      expect(lexicalCount).toBe(sentence.requiredLexicalTokenCount);
      expect(lexicalCount).toBeGreaterThan(0);
    },
  );

  it("keeps every sentence inside the analyzer's automatic selection rules", () => {
    for (const sentence of corpus) {
      expect(sentence.text.length).toBeGreaterThanOrEqual(20);
      expect(sentence.text.length).toBeLessThanOrEqual(2_000);
    }
  });
});
