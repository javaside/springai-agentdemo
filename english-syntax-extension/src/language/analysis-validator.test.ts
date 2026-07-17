import { describe, expect, it } from "vitest";
import { GrammarRole } from "../shared/grammar";
import type { TokenRange } from "../shared/grammar";
import type { SentenceInput } from "../shared/protocol";
import { validateCoreBatch, validateDetail } from "./analysis-validator";

const request: SentenceInput = {
  sentenceId: "sentence-1",
  text: "Learners read books.",
  tokens: [
    { id: 0, text: "Learners", start: 0, end: 8, leadingWhitespace: "", punctuation: false },
    { id: 1, text: "read", start: 9, end: 13, leadingWhitespace: " ", punctuation: false },
    { id: 2, text: "books", start: 14, end: 19, leadingWhitespace: " ", punctuation: false },
    { id: 3, text: ".", start: 19, end: 20, leadingWhitespace: "", punctuation: true },
  ],
};

const rawCore = {
  sentences: [
    {
      sentenceId: "sentence-1",
      components: [
        { startToken: 0, endToken: 0, role: "SUBJECT", translation: "学习者" },
        { startToken: 1, endToken: 1, role: "PREDICATE", translation: "阅读" },
        { startToken: 2, endToken: 3, role: "OBJECT", translation: "书籍" },
      ],
    },
  ],
};

const expectedAnalysis = {
  schemaVersion: 1,
  sentenceId: "sentence-1",
  components: [
    { startToken: 0, endToken: 0, role: GrammarRole.SUBJECT, translation: "学习者" },
    { startToken: 1, endToken: 1, role: GrammarRole.PREDICATE, translation: "阅读" },
    { startToken: 2, endToken: 3, role: GrammarRole.OBJECT, translation: "书籍" },
  ],
  modelProfileId: "profile-1",
};

function invalidCore(raw: unknown) {
  const result = validateCoreBatch(raw, [request], "profile-1");
  expect(result.ok).toBe(false);
  if (result.ok) {
    throw new Error("expected invalid core output");
  }
  return result.errors;
}

describe("core analysis validation", () => {
  it("accepts complete, ordered core coverage", () => {
    const result = validateCoreBatch(rawCore, [request], "profile-1");
    expect(result).toEqual({ ok: true, value: [expectedAnalysis] });
  });

  it("accepts the compound-sentence roles COORDINATE_CLAUSE and CONJUNCTION", () => {
    const result = validateCoreBatch(
      {
        sentences: [
          {
            sentenceId: "sentence-1",
            components: [
              { startToken: 0, endToken: 0, role: "COORDINATE_CLAUSE", translation: "第一分句" },
              { startToken: 1, endToken: 1, role: "CONJUNCTION", translation: "并且" },
              { startToken: 2, endToken: 3, role: "COORDINATE_CLAUSE", translation: "第二分句" },
            ],
          },
        ],
      },
      [request],
      "profile-1",
    );
    expect(result.ok).toBe(true);
  });

  it("reports the exact path and message for an uncovered lexical token", () => {
    const raw = structuredClone(rawCore);
    raw.sentences[0]!.components.splice(2, 1);

    expect(invalidCore(raw)).toContainEqual({
      path: "sentences[0].components",
      message: "non-punctuation token 2 is not covered",
    });
  });

  it.each([
    [
      "overlapping components",
      [
        { startToken: 0, endToken: 1, role: "SUBJECT", translation: "学习者" },
        { startToken: 1, endToken: 1, role: "PREDICATE", translation: "阅读" },
        { startToken: 2, endToken: 3, role: "OBJECT", translation: "书籍" },
      ],
    ],
    [
      "a reversed interval",
      [
        { startToken: 1, endToken: 0, role: "SUBJECT", translation: "学习者" },
        { startToken: 1, endToken: 1, role: "PREDICATE", translation: "阅读" },
        { startToken: 2, endToken: 3, role: "OBJECT", translation: "书籍" },
      ],
    ],
    [
      "an out-of-range interval",
      [
        { startToken: 0, endToken: 0, role: "SUBJECT", translation: "学习者" },
        { startToken: 1, endToken: 1, role: "PREDICATE", translation: "阅读" },
        { startToken: 2, endToken: 4, role: "OBJECT", translation: "书籍" },
      ],
    ],
    [
      "a punctuation-only component",
      [
        { startToken: 0, endToken: 0, role: "SUBJECT", translation: "学习者" },
        { startToken: 1, endToken: 1, role: "PREDICATE", translation: "阅读" },
        { startToken: 2, endToken: 2, role: "OBJECT", translation: "书籍" },
        { startToken: 3, endToken: 3, role: "INDEPENDENT_ELEMENT", translation: "句号" },
      ],
    ],
    [
      "an unknown role",
      [
        { startToken: 0, endToken: 0, role: "COMMAND", translation: "学习者" },
        { startToken: 1, endToken: 1, role: "PREDICATE", translation: "阅读" },
        { startToken: 2, endToken: 3, role: "OBJECT", translation: "书籍" },
      ],
    ],
    [
      "an empty translation",
      [
        { startToken: 0, endToken: 0, role: "SUBJECT", translation: "  " },
        { startToken: 1, endToken: 1, role: "PREDICATE", translation: "阅读" },
        { startToken: 2, endToken: 3, role: "OBJECT", translation: "书籍" },
      ],
    ],
  ])("rejects %s", (_description, components) => {
    invalidCore({ sentences: [{ sentenceId: "sentence-1", components }] });
  });

  it("rejects a translation over the sentence-relative limit", () => {
    const raw = structuredClone(rawCore);
    raw.sentences[0]!.components[0]!.translation = "译".repeat(501);
    invalidCore(raw);
  });

  it("keeps original component indexes in diagnostics after a malformed component", () => {
    const errors = invalidCore({
      sentences: [
        {
          sentenceId: "sentence-1",
          components: [null, { startToken: 4, endToken: 4, role: "SUBJECT", translation: "越界" }],
        },
      ],
    });

    expect(errors).toContainEqual({
      path: "sentences[0].components[1]",
      message: "token interval is outside the original sentence",
    });
  });

  it("rejects an extra sentence ID", () => {
    invalidCore({
      sentences: [
        ...rawCore.sentences,
        { sentenceId: "sentence-not-requested", components: rawCore.sentences[0]!.components },
      ],
    });
  });

  it.each(["<script>alert(1)</script>", "<IFRAME src=x>", "javascript:alert(1)", "safe\0unsafe"])(
    "rejects script-like translation %j",
    (translation) => {
      const raw = structuredClone(rawCore);
      raw.sentences[0]!.components[0]!.translation = translation;
      invalidCore(raw);
    },
  );
});

const focus: TokenRange = { startToken: 1, endToken: 1 };
const rawDetail = {
  sentenceId: "sentence-1",
  focus,
  structures: [{ startToken: 1, endToken: 2, role: "verb phrase", explanation: "谓语及其宾语" }],
  grammarPoints: ["一般现在时"],
  explanation: "说明阅读这一动作。",
};

function invalidDetail(raw: unknown) {
  const result = validateDetail(raw, request, focus, "profile-1");
  expect(result.ok).toBe(false);
  return result;
}

describe("detail analysis validation", () => {
  it("accepts valid detail and stamps the trusted profile", () => {
    expect(validateDetail(rawDetail, request, focus, "profile-1")).toEqual({
      ok: true,
      value: { ...rawDetail, modelProfileId: "profile-1" },
    });
  });

  it("rejects output that changes the requested focus", () => {
    invalidDetail({ ...rawDetail, focus: { startToken: 1, endToken: 2 } });
  });

  it.each([
    ["a reversed structure", { startToken: 2, endToken: 1 }],
    ["an out-of-range structure", { startToken: 1, endToken: 4 }],
  ])("rejects %s", (_description, interval) => {
    invalidDetail({
      ...rawDetail,
      structures: [{ ...rawDetail.structures[0], ...interval }],
    });
  });

  it("rejects more than 12 grammar points", () => {
    invalidDetail({
      ...rawDetail,
      grammarPoints: Array.from({ length: 13 }, (_, i) => `point ${i}`),
    });
  });

  it("rejects a grammar point longer than 300 characters", () => {
    invalidDetail({ ...rawDetail, grammarPoints: ["语".repeat(301)] });
  });

  it.each([
    ["detail explanation", { explanation: "<script>alert(1)</script>" }],
    ["structure explanation", { structures: [{ ...rawDetail.structures[0], explanation: "\0" }] }],
    ["grammar point", { grammarPoints: ["javascript:alert(1)"] }],
  ])("rejects a script-like %s", (_description, change) => {
    invalidDetail({ ...rawDetail, ...change });
  });
});
