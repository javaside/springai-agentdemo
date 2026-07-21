import { describe, expect, it } from "vitest";
import { GrammarRole } from "../shared/grammar";
import type { CoreAnalysis } from "../shared/grammar";
import type { SentenceInput } from "../shared/protocol";
import { buildSentenceDetailsPrompt } from "./prompts";

const sentence: SentenceInput = {
  sentenceId: "sentence-1",
  text: "Learners read books daily.",
  tokens: [
    { id: 0, text: "Learners", start: 0, end: 8, leadingWhitespace: "", punctuation: false },
    { id: 1, text: "read", start: 9, end: 13, leadingWhitespace: " ", punctuation: false },
    { id: 2, text: "books", start: 14, end: 19, leadingWhitespace: " ", punctuation: false },
    { id: 3, text: "daily", start: 20, end: 25, leadingWhitespace: " ", punctuation: false },
    { id: 4, text: ".", start: 25, end: 26, leadingWhitespace: "", punctuation: true },
  ],
};

const core: CoreAnalysis = {
  schemaVersion: 1,
  sentenceId: sentence.sentenceId,
  components: [
    { startToken: 0, endToken: 1, role: GrammarRole.SUBJECT, translation: "主语" },
    { startToken: 3, endToken: 4, role: GrammarRole.ADVERBIAL, translation: "状语" },
  ],
  modelProfileId: "profile-1",
};

describe("buildSentenceDetailsPrompt", () => {
  it("lists only the requested focus ranges and ends with them", () => {
    const prompt = buildSentenceDetailsPrompt(sentence, core, [
      { startToken: 0, endToken: 1 },
      { startToken: 3, endToken: 4 },
    ]);
    expect(prompt.startsWith("Explain each requested grammatical component")).toBe(true);
    expect(prompt).toContain('"details"');
    const focusSection = prompt.split("Requested focus ranges:")[1]!;
    expect(JSON.parse(focusSection.trim())).toEqual([
      { startToken: 0, endToken: 1 },
      { startToken: 3, endToken: 4 },
    ]);
  });
});
