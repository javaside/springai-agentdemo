import { describe, expect, it } from "vitest";
import { GrammarRole } from "./grammar";
import { isRequestMessage } from "./protocol";

const page = {
  version: 1,
  requestId: "request-1",
  tabId: 7,
  documentId: "document-1",
} as const;

const token = {
  id: 0,
  text: "Learners",
  start: 0,
  end: 8,
  leadingWhitespace: "",
  punctuation: false,
};

const sentence = {
  sentenceId: "sentence-1",
  text: "Learners read.",
  tokens: [token],
};

const core = {
  schemaVersion: 1,
  sentenceId: "sentence-1",
  components: [
    {
      startToken: 0,
      endToken: 0,
      role: GrammarRole.SUBJECT,
      translation: "学习者",
    },
  ],
  modelProfileId: "profile-1",
};

describe("request protocol guard", () => {
  it.each([
    ["START_SESSION", { ...page, type: "START_SESSION" }],
    ["PAUSE_SESSION", { ...page, type: "PAUSE_SESSION" }],
    ["STOP_SESSION", { ...page, type: "STOP_SESSION" }],
    ["GET_SESSION_STATUS", { ...page, type: "GET_SESSION_STATUS" }],
    ["ANALYZE_CORE", { ...page, type: "ANALYZE_CORE", sentences: [sentence] }],
    [
      "ANALYZE_DETAIL",
      {
        ...page,
        type: "ANALYZE_DETAIL",
        sentence,
        core,
        focus: { startToken: 0, endToken: 0 },
      },
    ],
    [
      "REANALYZE_WITH_FEEDBACK",
      {
        ...page,
        type: "REANALYZE_WITH_FEEDBACK",
        sentence,
        core,
        feedback: "Please explain the subject again.",
      },
    ],
    ["SWITCH_PROFILE", { ...page, type: "SWITCH_PROFILE", profileId: "profile-2" }],
    [
      "TEST_PROFILE",
      { version: 1, requestId: "request-1", type: "TEST_PROFILE", profileId: "profile-1" },
    ],
    ["GET_CACHE_STATS", { version: 1, requestId: "request-1", type: "GET_CACHE_STATS" }],
    ["CLEAR_CACHE", { version: 1, requestId: "request-1", type: "CLEAR_CACHE" }],
    ["PARSE_SELECTION", { ...page, type: "PARSE_SELECTION", selectionText: "Learners read." }],
    ["PARSE_CONTEXT_BLOCK", { ...page, type: "PARSE_CONTEXT_BLOCK" }],
  ])("accepts a valid %s request", (_type, request) => {
    expect(isRequestMessage(request)).toBe(true);
  });

  it("rejects an unversioned content message", () => {
    expect(isRequestMessage({ type: "ANALYZE_CORE" })).toBe(false);
  });

  it("requires page correlation identifiers for page requests", () => {
    expect(
      isRequestMessage({
        version: 1,
        requestId: "request-1",
        type: "GET_SESSION_STATUS",
        tabId: 7,
      }),
    ).toBe(false);
  });

  it("rejects unknown top-level properties as executable instructions", () => {
    expect(
      isRequestMessage({
        version: 1,
        requestId: "request-1",
        type: "GET_CACHE_STATS",
        command: "DELETE_ALL_DATA",
      }),
    ).toBe(false);
  });

  it.each([
    ["a null token", { ...sentence, tokens: [null] }],
    ["an arbitrary token", { ...sentence, tokens: [{ command: "RUN" }] }],
    ["a token with a surplus key", { ...sentence, tokens: [{ ...token, command: "RUN" }] }],
    ["a token with a negative ID", { ...sentence, tokens: [{ ...token, id: -1 }] }],
    ["a token with a fractional offset", { ...sentence, tokens: [{ ...token, start: 0.5 }] }],
    ["a token with a reversed range", { ...sentence, tokens: [{ ...token, start: 8, end: 0 }] }],
  ])("rejects ANALYZE_CORE containing %s", (_description, malformedSentence) => {
    expect(
      isRequestMessage({
        ...page,
        type: "ANALYZE_CORE",
        sentences: [malformedSentence],
      }),
    ).toBe(false);
  });

  it.each([
    ["an arbitrary core", { command: "RUN" }, { startToken: 0, endToken: 0 }],
    ["a core with a surplus key", { ...core, command: "RUN" }, { startToken: 0, endToken: 0 }],
    [
      "a component with a surplus key",
      { ...core, components: [{ ...core.components[0], command: "RUN" }] },
      { startToken: 0, endToken: 0 },
    ],
    [
      "an unknown grammar role",
      { ...core, components: [{ ...core.components[0], role: "COMMAND" }] },
      { startToken: 0, endToken: 0 },
    ],
    ["a reversed focus", core, { startToken: 2, endToken: 1 }],
    ["a negative focus", core, { startToken: -1, endToken: 1 }],
    ["a focus with a surplus key", core, { startToken: 0, endToken: 0, command: "RUN" }],
  ])("rejects ANALYZE_DETAIL containing %s", (_description, malformedCore, focus) => {
    expect(
      isRequestMessage({
        ...page,
        type: "ANALYZE_DETAIL",
        sentence,
        core: malformedCore,
        focus,
      }),
    ).toBe(false);
  });

  it.each([
    ["a non-array component collection", { ...core, components: null }],
    [
      "a component with a reversed range",
      { ...core, components: [{ ...core.components[0], startToken: 2, endToken: 1 }] },
    ],
    [
      "a non-string translation",
      { ...core, components: [{ ...core.components[0], translation: 42 }] },
    ],
    ["a whitespace-only sentence ID", { ...core, sentenceId: "   " }],
    ["a whitespace-only model profile ID", { ...core, modelProfileId: "\t" }],
  ])("rejects REANALYZE_WITH_FEEDBACK containing %s", (_description, malformedCore) => {
    expect(
      isRequestMessage({
        ...page,
        type: "REANALYZE_WITH_FEEDBACK",
        sentence,
        core: malformedCore,
        feedback: "Try again.",
      }),
    ).toBe(false);
  });

  it.each([
    ["requestId", { version: 1, requestId: "   ", type: "GET_CACHE_STATS" }],
    ["documentId", { ...page, documentId: "\n", type: "GET_SESSION_STATUS" }],
    [
      "sentenceId",
      { ...page, type: "ANALYZE_CORE", sentences: [{ ...sentence, sentenceId: " " }] },
    ],
    ["profileId", { ...page, type: "SWITCH_PROFILE", profileId: "\t" }],
  ])("rejects a whitespace-only %s", (_field, request) => {
    expect(isRequestMessage(request)).toBe(false);
  });
});
