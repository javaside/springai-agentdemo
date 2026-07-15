import type { ExtensionError } from "./errors";
import type { CoreAnalysis, DetailAnalysis, Token, TokenRange } from "./grammar";
import { MESSAGE_VERSION } from "./versions";

interface MessageBase {
  version: typeof MESSAGE_VERSION;
  requestId: string;
}

interface PageRequestBase extends MessageBase {
  tabId: number;
  documentId: string;
}

export interface SentenceInput {
  sentenceId: string;
  text: string;
  tokens: Token[];
}

export type RequestMessage =
  | (PageRequestBase & { type: "START_SESSION" })
  | (PageRequestBase & { type: "PAUSE_SESSION" })
  | (PageRequestBase & { type: "STOP_SESSION" })
  | (PageRequestBase & { type: "GET_SESSION_STATUS" })
  | (PageRequestBase & { type: "ANALYZE_CORE"; sentences: SentenceInput[] })
  | (PageRequestBase & {
      type: "ANALYZE_DETAIL";
      sentence: SentenceInput;
      core: CoreAnalysis;
      focus: TokenRange;
    })
  | (PageRequestBase & {
      type: "REANALYZE_WITH_FEEDBACK";
      sentence: SentenceInput;
      core: CoreAnalysis;
      feedback: string;
    })
  | (PageRequestBase & { type: "SWITCH_PROFILE"; profileId: string })
  | (MessageBase & { type: "TEST_PROFILE"; profileId: string })
  | (MessageBase & { type: "GET_CACHE_STATS" })
  | (MessageBase & { type: "CLEAR_CACHE" })
  | (PageRequestBase & { type: "PARSE_SELECTION"; selectionText: string })
  | (PageRequestBase & { type: "PARSE_CONTEXT_BLOCK" });

export type SessionState = "stopped" | "running" | "paused";

export interface SessionStatus {
  state: SessionState;
  discovered: number;
  queued: number;
  ready: number;
  failed: number;
  profileId?: string;
}

export interface CacheStats {
  entries: number;
  estimatedBytes: number;
  limitBytes: number;
}

export type ResponseMessage =
  | (MessageBase & { type: "ACK"; acknowledgedType: RequestMessage["type"] })
  | (MessageBase & { type: "SESSION_STATUS"; status: SessionStatus })
  | (MessageBase & { type: "CORE_RESULT"; analyses: CoreAnalysis[] })
  | (MessageBase & { type: "DETAIL_RESULT"; analysis: DetailAnalysis })
  | (MessageBase & { type: "CACHE_STATS"; stats: CacheStats })
  | (MessageBase & {
      type: "PROFILE_TEST_RESULT";
      profileId: string;
      success: boolean;
      latencyMs?: number;
      error?: ExtensionError;
    })
  | (MessageBase & { type: "ERROR"; error: ExtensionError });

const pageOnlyKeys = ["version", "requestId", "type", "tabId", "documentId"] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function hasOnlyKeys(value: Record<string, unknown>, allowedKeys: readonly string[]): boolean {
  return Object.keys(value).every((key) => allowedKeys.includes(key));
}

function hasPageContext(value: Record<string, unknown>): boolean {
  return (
    Number.isSafeInteger(value.tabId) &&
    (value.tabId as number) >= 0 &&
    isNonEmptyString(value.documentId)
  );
}

function isTokenRange(value: unknown): value is TokenRange {
  return (
    isRecord(value) &&
    hasOnlyKeys(value, ["startToken", "endToken"]) &&
    Number.isSafeInteger(value.startToken) &&
    Number.isSafeInteger(value.endToken)
  );
}

function isSentenceInput(value: unknown): value is SentenceInput {
  return (
    isRecord(value) &&
    hasOnlyKeys(value, ["sentenceId", "text", "tokens"]) &&
    isNonEmptyString(value.sentenceId) &&
    typeof value.text === "string" &&
    Array.isArray(value.tokens)
  );
}

export function isRequestMessage(value: unknown): value is RequestMessage {
  if (
    !isRecord(value) ||
    value.version !== MESSAGE_VERSION ||
    !isNonEmptyString(value.requestId) ||
    !isNonEmptyString(value.type)
  ) {
    return false;
  }

  switch (value.type) {
    case "START_SESSION":
    case "PAUSE_SESSION":
    case "STOP_SESSION":
    case "GET_SESSION_STATUS":
    case "PARSE_CONTEXT_BLOCK":
      return hasOnlyKeys(value, pageOnlyKeys) && hasPageContext(value);
    case "ANALYZE_CORE":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "sentences"]) &&
        hasPageContext(value) &&
        Array.isArray(value.sentences) &&
        value.sentences.every(isSentenceInput)
      );
    case "ANALYZE_DETAIL":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "sentence", "core", "focus"]) &&
        hasPageContext(value) &&
        isSentenceInput(value.sentence) &&
        isRecord(value.core) &&
        isTokenRange(value.focus)
      );
    case "REANALYZE_WITH_FEEDBACK":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "sentence", "core", "feedback"]) &&
        hasPageContext(value) &&
        isSentenceInput(value.sentence) &&
        isRecord(value.core) &&
        isNonEmptyString(value.feedback)
      );
    case "SWITCH_PROFILE":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "profileId"]) &&
        hasPageContext(value) &&
        isNonEmptyString(value.profileId)
      );
    case "TEST_PROFILE":
      return (
        hasOnlyKeys(value, ["version", "requestId", "type", "profileId"]) &&
        isNonEmptyString(value.profileId)
      );
    case "GET_CACHE_STATS":
    case "CLEAR_CACHE":
      return hasOnlyKeys(value, ["version", "requestId", "type"]);
    case "PARSE_SELECTION":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "selectionText"]) &&
        hasPageContext(value) &&
        isNonEmptyString(value.selectionText)
      );
    default:
      return false;
  }
}

export function assertNever(value: never): never {
  throw new Error(`Unexpected value: ${String(value)}`);
}
