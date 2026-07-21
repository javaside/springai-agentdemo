import type { ExtensionError } from "./errors";
import { GrammarRole } from "./grammar";
import type { CoreAnalysis, CoreComponent, DetailAnalysis, Token, TokenRange } from "./grammar";
import { CORE_SCHEMA_VERSION, MESSAGE_VERSION } from "./versions";

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
  | (PageRequestBase & { type: "START_SESSION"; prefetchDetail?: true })
  | (PageRequestBase & { type: "PAUSE_SESSION" })
  | (PageRequestBase & { type: "STOP_SESSION" })
  | (PageRequestBase & { type: "GET_SESSION_STATUS" })
  | (PageRequestBase & { type: "REANALYZE_VISIBLE" })
  | (PageRequestBase & { type: "ANALYZE_CORE"; sentences: SentenceInput[]; bypassCache?: true })
  | (PageRequestBase & {
      type: "ANALYZE_DETAIL";
      sentence: SentenceInput;
      core: CoreAnalysis;
      focus: TokenRange;
    })
  | (PageRequestBase & {
      type: "PREFETCH_SENTENCE_DETAILS";
      sentence: SentenceInput;
      core: CoreAnalysis;
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
  /** 纯缓存会话中未命中而保持原文的句数。 */
  skipped?: number;
  /** 详解预载:已就绪句子的成分总数(仅预载开启的会话出现)。 */
  detailTotal?: number;
  /** 详解预载:已确认入缓存的成分数(含预载前已命中缓存的)。 */
  detailReady?: number;
  /** 详解预载:repair 后仍失败的成分数。 */
  detailFailed?: number;
  profileId?: string;
}

export function isSessionComplete(status: SessionStatus): boolean {
  return (
    status.discovered > 0 &&
    status.queued === 0 &&
    status.ready + status.failed + (status.skipped ?? 0) >= status.discovered
  );
}

export interface CacheStats {
  entries: number;
  estimatedBytes: number;
  limitBytes: number;
}

export type ResponseMessage =
  | (MessageBase & { type: "ACK"; acknowledgedType: RequestMessage["type"] })
  | (MessageBase & { type: "SESSION_STATUS"; status: SessionStatus })
  | (MessageBase & { type: "CORE_RESULT"; analyses: CoreAnalysis[]; cacheOnly?: true })
  | (MessageBase & { type: "DETAIL_RESULT"; analysis: DetailAnalysis })
  | (MessageBase & { type: "SENTENCE_DETAILS_RESULT"; succeeded: number; failed: number })
  | (MessageBase & { type: "CACHE_STATS"; stats: CacheStats })
  | (MessageBase & {
      type: "PROFILE_TEST_RESULT";
      profileId: string;
      success: boolean;
      latencyMs?: number;
      jsonSchemaSupport?: "supported" | "unsupported";
      error?: ExtensionError;
    })
  | (MessageBase & { type: "ERROR"; error: ExtensionError });

const pageOnlyKeys = ["version", "requestId", "type", "tabId", "documentId"] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isNonNegativeSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0;
}

function hasOnlyKeys(value: Record<string, unknown>, allowedKeys: readonly string[]): boolean {
  return Object.keys(value).every((key) => allowedKeys.includes(key));
}

function hasPageContext(value: Record<string, unknown>): boolean {
  return isNonNegativeSafeInteger(value.tabId) && isNonBlankString(value.documentId);
}

function isTokenRange(value: unknown): value is TokenRange {
  return (
    isRecord(value) &&
    hasOnlyKeys(value, ["startToken", "endToken"]) &&
    isNonNegativeSafeInteger(value.startToken) &&
    isNonNegativeSafeInteger(value.endToken) &&
    value.startToken <= value.endToken
  );
}

function isToken(value: unknown): value is Token {
  return (
    isRecord(value) &&
    hasOnlyKeys(value, ["id", "text", "start", "end", "leadingWhitespace", "punctuation"]) &&
    isNonNegativeSafeInteger(value.id) &&
    typeof value.text === "string" &&
    isNonNegativeSafeInteger(value.start) &&
    isNonNegativeSafeInteger(value.end) &&
    value.start <= value.end &&
    typeof value.leadingWhitespace === "string" &&
    typeof value.punctuation === "boolean"
  );
}

function isSentenceInput(value: unknown): value is SentenceInput {
  return (
    isRecord(value) &&
    hasOnlyKeys(value, ["sentenceId", "text", "tokens"]) &&
    isNonBlankString(value.sentenceId) &&
    typeof value.text === "string" &&
    Array.isArray(value.tokens) &&
    value.tokens.every(isToken)
  );
}

const grammarRoles: ReadonlySet<string> = new Set(Object.values(GrammarRole));

function isCoreComponent(value: unknown): value is CoreComponent {
  return (
    isRecord(value) &&
    hasOnlyKeys(value, ["startToken", "endToken", "role", "translation"]) &&
    isNonNegativeSafeInteger(value.startToken) &&
    isNonNegativeSafeInteger(value.endToken) &&
    value.startToken <= value.endToken &&
    typeof value.role === "string" &&
    grammarRoles.has(value.role) &&
    typeof value.translation === "string"
  );
}

function isCoreAnalysis(value: unknown): value is CoreAnalysis {
  return (
    isRecord(value) &&
    hasOnlyKeys(value, ["schemaVersion", "sentenceId", "components", "modelProfileId"]) &&
    value.schemaVersion === CORE_SCHEMA_VERSION &&
    isNonBlankString(value.sentenceId) &&
    Array.isArray(value.components) &&
    value.components.every(isCoreComponent) &&
    isNonBlankString(value.modelProfileId)
  );
}

export function isRequestMessage(value: unknown): value is RequestMessage {
  if (
    !isRecord(value) ||
    value.version !== MESSAGE_VERSION ||
    !isNonBlankString(value.requestId) ||
    !isNonBlankString(value.type)
  ) {
    return false;
  }

  switch (value.type) {
    case "PAUSE_SESSION":
    case "STOP_SESSION":
    case "GET_SESSION_STATUS":
    case "REANALYZE_VISIBLE":
    case "PARSE_CONTEXT_BLOCK":
      return hasOnlyKeys(value, pageOnlyKeys) && hasPageContext(value);
    case "START_SESSION":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "prefetchDetail"]) &&
        hasPageContext(value) &&
        (value.prefetchDetail === undefined || value.prefetchDetail === true)
      );
    case "PREFETCH_SENTENCE_DETAILS":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "sentence", "core"]) &&
        hasPageContext(value) &&
        isSentenceInput(value.sentence) &&
        isCoreAnalysis(value.core)
      );
    case "ANALYZE_CORE":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "sentences", "bypassCache"]) &&
        hasPageContext(value) &&
        (value.bypassCache === undefined || value.bypassCache === true) &&
        Array.isArray(value.sentences) &&
        value.sentences.every(isSentenceInput)
      );
    case "ANALYZE_DETAIL":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "sentence", "core", "focus"]) &&
        hasPageContext(value) &&
        isSentenceInput(value.sentence) &&
        isCoreAnalysis(value.core) &&
        isTokenRange(value.focus)
      );
    case "REANALYZE_WITH_FEEDBACK":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "sentence", "core", "feedback"]) &&
        hasPageContext(value) &&
        isSentenceInput(value.sentence) &&
        isCoreAnalysis(value.core) &&
        isNonBlankString(value.feedback)
      );
    case "SWITCH_PROFILE":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "profileId"]) &&
        hasPageContext(value) &&
        isNonBlankString(value.profileId)
      );
    case "TEST_PROFILE":
      return (
        hasOnlyKeys(value, ["version", "requestId", "type", "profileId"]) &&
        isNonBlankString(value.profileId)
      );
    case "GET_CACHE_STATS":
    case "CLEAR_CACHE":
      return hasOnlyKeys(value, ["version", "requestId", "type"]);
    case "PARSE_SELECTION":
      return (
        hasOnlyKeys(value, [...pageOnlyKeys, "selectionText"]) &&
        hasPageContext(value) &&
        isNonBlankString(value.selectionText)
      );
    default:
      return false;
  }
}

export function assertNever(value: never): never {
  throw new Error(`Unexpected value: ${String(value)}`);
}
