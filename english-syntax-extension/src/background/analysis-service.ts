import { GrammarRole } from "../shared/grammar";
import type { CoreAnalysis, DetailAnalysis, TokenRange } from "../shared/grammar";
import type { SentenceInput } from "../shared/protocol";
import { CORE_SCHEMA_VERSION } from "../shared/versions";
import { validateCoreBatch, validateDetail } from "../language/analysis-validator";
import type { ValidationError } from "../language/analysis-validator";
import { createCoreCacheKey, createCorrectionCacheKey } from "./analysis-cache";
import type { ModelProfile } from "./config-repository";
import { ModelRequestError } from "./openai-compatible-adapter";
import type { ChatMessage, JsonSchemaSpec } from "./openai-compatible-adapter";
import {
  buildCorePrompt,
  buildDetailPrompt,
  buildRepairPrompt,
  buildSentenceDetailsPrompt,
  CORE_OUTPUT_SHAPE,
} from "./prompts";
import type { ScheduledRequest, SchedulerPriority } from "./request-scheduler";

export interface AnalysisCachePort {
  getCore<T>(key: string): Promise<T | undefined>;
  putCore<T>(key: string, profileId: string, value: T): Promise<void>;
  getDetail<T>(key: string): Promise<T | undefined>;
  putDetail<T>(key: string, profileId: string, value: T): Promise<void>;
  getCorrection<T>(key: string): Promise<T | undefined>;
  putCorrection<T>(key: string, profileId: string, value: T): Promise<void>;
}

export interface AnalysisAdapter {
  completeJson(
    profile: ModelProfile,
    messages: readonly ChatMessage[],
    schema: JsonSchemaSpec,
    signal: AbortSignal,
  ): Promise<unknown>;
}

export interface AnalysisModelWork {
  profile: ModelProfile;
  messages: readonly ChatMessage[];
  schema: JsonSchemaSpec;
  requestedAt: number;
  run(signal: AbortSignal): Promise<unknown>;
}

export type AnalysisScheduledRequest = ScheduledRequest<AnalysisModelWork>;

export interface AnalysisScheduler {
  schedule(request: AnalysisScheduledRequest): Promise<unknown>;
  cancelDocument(documentId: string): void;
}

interface AnalysisInputBase {
  profile: ModelProfile;
  documentId: string;
}

export interface CoreBatchInput extends AnalysisInputBase {
  sentences: readonly SentenceInput[];
  priority?: Extract<SchedulerPriority, "visible-core" | "prefetch-core">;
  /** 「重新解析」置位:跳过读缓存,结果照常覆盖写回。 */
  bypassCache?: boolean;
}

export interface DetailInput extends AnalysisInputBase {
  sentence: SentenceInput;
  core: CoreAnalysis;
  focus: TokenRange;
}

export interface DetailLookupInput {
  sentence: SentenceInput;
  focus: TokenRange;
}

export interface SentenceDetailsInput extends AnalysisInputBase {
  sentence: SentenceInput;
  core: CoreAnalysis;
}

export interface SentenceDetailsOutcome {
  succeeded: number;
  failed: number;
}

export interface CorrectionInput extends AnalysisInputBase {
  sentence: SentenceInput;
  core: CoreAnalysis;
  pageUrl: string;
  sentenceInstanceId: string;
  feedback: string;
}

export interface AnalysisFailure {
  sentenceId: string;
  error: ModelRequestError;
}

export interface CoreBatchOutcome {
  result: CoreAnalysis[];
  failures: AnalysisFailure[];
  cacheHit: boolean;
}

export interface CoreOutcome {
  result: CoreAnalysis;
  cacheHit: boolean;
}

export interface DetailOutcome {
  result: DetailAnalysis;
  cacheHit: boolean;
}

export interface AnalysisService {
  analyzeCore(input: CoreBatchInput, signal: AbortSignal): Promise<CoreBatchOutcome>;
  analyzeDetail(input: DetailInput, signal: AbortSignal): Promise<DetailOutcome>;
  reanalyzeWithFeedback(input: CorrectionInput, signal: AbortSignal): Promise<CoreOutcome>;
  /** 纯缓存查找:只回命中,不进调度器、不需要 profile(popup「查看缓存」模式)。 */
  lookupCore(sentences: readonly SentenceInput[]): Promise<CoreAnalysis[]>;
  lookupDetail(input: DetailLookupInput): Promise<DetailAnalysis | undefined>;
  /** 详解预载(按句合批):只补缺失成分,一次整句请求,结果逐成分写入现有缓存键。 */
  analyzeSentenceDetails(
    input: SentenceDetailsInput,
    signal: AbortSignal,
  ): Promise<SentenceDetailsOutcome>;
}

export interface CachedAnalysisServiceOptions {
  cache: AnalysisCachePort;
  adapter: AnalysisAdapter;
  scheduler: AnalysisScheduler;
  now?: () => number;
}

/** 纯缓存模式没有真实 profile,命中值统一改写为该占位 id。 */
const CACHE_ONLY_PROFILE_ID = "cached";

interface InvalidCoreSentence {
  sentence: SentenceInput;
  errors: ValidationError[];
}

const CORE_SCHEMA: JsonSchemaSpec = {
  name: "core_analysis",
  schema: {
    type: "object",
    additionalProperties: false,
    required: ["sentences"],
    properties: {
      sentences: {
        type: "array",
        items: {
          type: "object",
          additionalProperties: false,
          required: ["sentenceId", "components"],
          properties: {
            sentenceId: { type: "string" },
            components: {
              type: "array",
              minItems: 1,
              items: {
                type: "object",
                additionalProperties: false,
                required: ["startToken", "endToken", "role", "translation"],
                properties: {
                  startToken: { type: "integer", minimum: 0 },
                  endToken: { type: "integer", minimum: 0 },
                  role: { type: "string", enum: Object.values(GrammarRole) },
                  translation: { type: "string", minLength: 1 },
                },
              },
            },
          },
        },
      },
    },
  },
};

const DETAIL_SCHEMA: JsonSchemaSpec = {
  name: "detail_analysis",
  schema: {
    type: "object",
    additionalProperties: false,
    required: ["sentenceId", "focus", "structures", "grammarPoints", "explanation"],
    properties: {
      sentenceId: { type: "string" },
      focus: {
        type: "object",
        additionalProperties: false,
        required: ["startToken", "endToken"],
        properties: {
          startToken: { type: "integer", minimum: 0 },
          endToken: { type: "integer", minimum: 0 },
        },
      },
      structures: {
        type: "array",
        items: {
          type: "object",
          additionalProperties: false,
          required: ["startToken", "endToken", "role", "explanation"],
          properties: {
            startToken: { type: "integer", minimum: 0 },
            endToken: { type: "integer", minimum: 0 },
            role: { type: "string", minLength: 1 },
            explanation: { type: "string", minLength: 1 },
            // 译文由提示词强制要求，但 schema 层不列入 required：兼容模式下模型
            // 偶发缺失时按渐进增强降级为两行标注，而非整次 INVALID_MODEL_OUTPUT。
            translation: { type: "string", minLength: 1, maxLength: 120 },
          },
        },
      },
      grammarPoints: {
        type: "array",
        maxItems: 12,
        items: { type: "string", minLength: 1, maxLength: 300 },
      },
      explanation: { type: "string", minLength: 1 },
    },
  },
};

const SENTENCE_DETAILS_SCHEMA: JsonSchemaSpec = {
  name: "sentence_details_analysis",
  schema: {
    type: "object",
    additionalProperties: false,
    required: ["details"],
    properties: {
      details: { type: "array", items: DETAIL_SCHEMA.schema },
    },
  },
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function normalizedSentenceText(sentence: SentenceInput): string {
  return sentence.text.trim().replace(/\s+/gu, " ");
}

function cancellationError(): ModelRequestError {
  return new ModelRequestError("REQUEST_CANCELLED", "Analysis request was cancelled", false);
}

function invalidOutput(errors: readonly ValidationError[]): ModelRequestError {
  const summary = errors.map(({ path, message }) => `${path || "output"}: ${message}`).join("; ");
  return new ModelRequestError(
    "INVALID_MODEL_OUTPUT",
    `Model output remained invalid after one repair${summary.length === 0 ? "" : `: ${summary}`}`,
    false,
  );
}

function rawSentences(raw: unknown): readonly unknown[] {
  return isRecord(raw) && Array.isArray(raw.sentences) ? raw.sentences : [];
}

function matchingRawSentences(raw: unknown, sentenceId: string): unknown[] {
  return rawSentences(raw).filter(
    (candidate) => isRecord(candidate) && candidate.sentenceId === sentenceId,
  );
}

function invalidRawSubset(raw: unknown, sentenceIds: ReadonlySet<string>): unknown {
  return {
    sentences: rawSentences(raw).filter(
      (candidate) =>
        isRecord(candidate) &&
        typeof candidate.sentenceId === "string" &&
        sentenceIds.has(candidate.sentenceId),
    ),
  };
}

function validateCachedCore(
  cached: unknown,
  sentence: SentenceInput,
  modelProfileId: string,
): CoreAnalysis | undefined {
  const validation = validateCoreBatch(
    {
      sentences: [
        {
          sentenceId: sentence.sentenceId,
          components: isRecord(cached) ? cached.components : undefined,
        },
      ],
    },
    [sentence],
    modelProfileId,
  );
  return validation.ok ? validation.value[0] : undefined;
}

function isMatchingFocus(value: unknown, focus: TokenRange): boolean {
  return (
    isRecord(value) && value.startToken === focus.startToken && value.endToken === focus.endToken
  );
}

function validateCachedDetail(
  cached: unknown,
  sentence: SentenceInput,
  focus: TokenRange,
  modelProfileId: string,
): DetailAnalysis | undefined {
  if (!isRecord(cached) || !isMatchingFocus(cached.focus, focus)) return undefined;
  const validation = validateDetail(
    {
      sentenceId: sentence.sentenceId,
      focus,
      structures: cached.structures,
      grammarPoints: cached.grammarPoints,
      explanation: cached.explanation,
    },
    sentence,
    focus,
    modelProfileId,
  );
  return validation.ok ? validation.value : undefined;
}

function correctionPrompt(input: CorrectionInput): string {
  return [
    "Reanalyze the supplied sentence using the reader's correction feedback.",
    "Keep the sentence ID and Tokens unchanged. Return core-analysis JSON only.",
    CORE_OUTPUT_SHAPE,
    `Sentence and Tokens:\n${JSON.stringify(input.sentence, null, 2)}`,
    `Previously verified core analysis:\n${JSON.stringify(input.core, null, 2)}`,
    `Reader feedback:\n${input.feedback}`,
  ].join("\n\n");
}

function correctionRepairPrompt(
  input: CorrectionInput,
  errors: readonly ValidationError[],
  invalidJson: unknown,
): string {
  return [
    "Repair only the structure of the invalid correction analysis while preserving the entire correction context below.",
    correctionPrompt(input),
    `Validation errors:\n${JSON.stringify(errors, null, 2)}`,
    `Invalid JSON:\n${JSON.stringify(invalidJson, null, 2)}`,
    "Return the repaired core-analysis JSON only. Do not add sentences or change sentence IDs or Tokens.",
  ].join("\n\n");
}

function detailRepairPrompt(
  input: DetailInput,
  errors: readonly ValidationError[],
  invalidJson: unknown,
): string {
  return [
    "Repair only the structure of the invalid detail-analysis JSON.",
    "Keep the sentence ID, Tokens, verified core analysis, and focus unchanged. Return JSON only.",
    `Sentence and Tokens:\n${JSON.stringify(input.sentence, null, 2)}`,
    `Verified core analysis:\n${JSON.stringify(input.core, null, 2)}`,
    `Focus:\n${JSON.stringify(input.focus, null, 2)}`,
    `Validation errors:\n${JSON.stringify(errors, null, 2)}`,
    `Invalid JSON:\n${JSON.stringify(invalidJson, null, 2)}`,
  ].join("\n\n");
}

function sentenceDetailsRepairPrompt(
  input: SentenceDetailsInput,
  focuses: readonly TokenRange[],
  errors: readonly ValidationError[],
  invalidJson: unknown,
): string {
  return [
    "Repair only the structure of the invalid sentence-details JSON so every requested focus has one valid entry.",
    "Keep the sentence ID, Tokens, verified core analysis, and focus ranges unchanged. Return JSON only.",
    `Sentence and Tokens:\n${JSON.stringify(input.sentence, null, 2)}`,
    `Verified core analysis:\n${JSON.stringify(input.core, null, 2)}`,
    `Validation errors:\n${JSON.stringify(errors, null, 2)}`,
    `Invalid JSON:\n${JSON.stringify(invalidJson, null, 2)}`,
    `Requested focus ranges:\n${JSON.stringify(focuses, null, 2)}`,
  ].join("\n\n");
}

export class CachedAnalysisService implements AnalysisService {
  private readonly now: () => number;

  constructor(private readonly options: CachedAnalysisServiceOptions) {
    this.now = options.now ?? Date.now;
  }

  async analyzeCore(input: CoreBatchInput, signal: AbortSignal): Promise<CoreBatchOutcome> {
    if (signal.aborted) throw cancellationError();
    const keyedSentences = await Promise.all(
      input.sentences.map(async (sentence) => ({
        sentence,
        key: await this.coreKey(sentence),
      })),
    );
    const cached =
      input.bypassCache === true
        ? keyedSentences.map(() => undefined)
        : await Promise.all(
            keyedSentences.map(({ key }) => this.options.cache.getCore<unknown>(key)),
          );
    if (signal.aborted) throw cancellationError();

    const resultsById = new Map<string, CoreAnalysis>();
    const missing: Array<{ sentence: SentenceInput; key: string }> = [];
    keyedSentences.forEach((entry, index) => {
      const value = validateCachedCore(cached[index], entry.sentence, input.profile.id);
      if (value === undefined) missing.push(entry);
      else resultsById.set(entry.sentence.sentenceId, value);
    });
    if (missing.length === 0) {
      return {
        result: input.sentences.map(({ sentenceId }) => resultsById.get(sentenceId)!),
        failures: [],
        cacheHit: true,
      };
    }

    const firstRaw = await this.requestModel(
      input.profile,
      input.documentId,
      input.priority ?? "visible-core",
      missing.map(({ key }) => key).join(":"),
      missing.length,
      [{ role: "user", content: buildCorePrompt(missing.map(({ sentence }) => sentence)) }],
      CORE_SCHEMA,
      "core",
      signal,
    );
    const firstPass = await this.validateAndCacheCore(input.profile, missing, firstRaw);
    firstPass.valid.forEach((analysis) => resultsById.set(analysis.sentenceId, analysis));

    let remaining = firstPass.invalid;
    if (remaining.length > 0) {
      const failedIds = new Set(remaining.map(({ sentence }) => sentence.sentenceId));
      const repairRaw = await this.requestModel(
        input.profile,
        input.documentId,
        input.priority ?? "visible-core",
        `${missing.map(({ key }) => key).join(":")}:repair`,
        remaining.length,
        [
          {
            role: "user",
            content: buildRepairPrompt(
              remaining.map(({ sentence }) => sentence),
              remaining.flatMap(({ errors }) => errors),
              invalidRawSubset(firstRaw, failedIds),
            ),
          },
        ],
        CORE_SCHEMA,
        "core-repair",
        signal,
      );
      const keysById = new Map(missing.map(({ sentence, key }) => [sentence.sentenceId, key]));
      const repairEntries = remaining.map(({ sentence }) => ({
        sentence,
        key: keysById.get(sentence.sentenceId)!,
      }));
      const repaired = await this.validateAndCacheCore(input.profile, repairEntries, repairRaw);
      repaired.valid.forEach((analysis) => resultsById.set(analysis.sentenceId, analysis));
      remaining = repaired.invalid;
    }

    const failuresById = new Map(
      remaining.map(({ sentence, errors }) => [
        sentence.sentenceId,
        { sentenceId: sentence.sentenceId, error: invalidOutput(errors) },
      ]),
    );
    return {
      result: input.sentences.flatMap(({ sentenceId }) => {
        const result = resultsById.get(sentenceId);
        return result === undefined ? [] : [result];
      }),
      failures: input.sentences.flatMap(({ sentenceId }) => {
        const failure = failuresById.get(sentenceId);
        return failure === undefined ? [] : [failure];
      }),
      cacheHit: false,
    };
  }

  async analyzeDetail(input: DetailInput, signal: AbortSignal): Promise<DetailOutcome> {
    if (signal.aborted) throw cancellationError();
    const key = await this.detailKey(input);
    const cached = validateCachedDetail(
      await this.options.cache.getDetail<unknown>(key),
      input.sentence,
      input.focus,
      input.profile.id,
    );
    if (signal.aborted) throw cancellationError();
    if (cached !== undefined) return { result: cached, cacheHit: true };

    const raw = await this.requestModel(
      input.profile,
      input.documentId,
      "detail-click",
      key,
      1,
      [{ role: "user", content: buildDetailPrompt(input.sentence, input.core, input.focus) }],
      DETAIL_SCHEMA,
      "detail",
      signal,
    );
    let validation = validateDetail(raw, input.sentence, input.focus, input.profile.id);
    if (!validation.ok) {
      const repairRaw = await this.requestModel(
        input.profile,
        input.documentId,
        "detail-click",
        `${key}:repair`,
        1,
        [{ role: "user", content: detailRepairPrompt(input, validation.errors, raw) }],
        DETAIL_SCHEMA,
        "detail-repair",
        signal,
      );
      validation = validateDetail(repairRaw, input.sentence, input.focus, input.profile.id);
    }
    if (!validation.ok) throw invalidOutput(validation.errors);
    await this.options.cache.putDetail(key, input.profile.id, validation.value);
    return { result: validation.value, cacheHit: false };
  }

  async lookupCore(sentences: readonly SentenceInput[]): Promise<CoreAnalysis[]> {
    const cached = await Promise.all(
      sentences.map(async (sentence) => ({
        sentence,
        value: await this.options.cache.getCore<unknown>(await this.coreKey(sentence)),
      })),
    );
    return cached.flatMap(({ sentence, value }) => {
      const analysis = validateCachedCore(value, sentence, CACHE_ONLY_PROFILE_ID);
      return analysis === undefined ? [] : [analysis];
    });
  }

  async lookupDetail(input: DetailLookupInput): Promise<DetailAnalysis | undefined> {
    const key = await this.detailKey(input);
    return validateCachedDetail(
      await this.options.cache.getDetail<unknown>(key),
      input.sentence,
      input.focus,
      CACHE_ONLY_PROFILE_ID,
    );
  }

  async analyzeSentenceDetails(
    input: SentenceDetailsInput,
    signal: AbortSignal,
  ): Promise<SentenceDetailsOutcome> {
    if (signal.aborted) throw cancellationError();
    const targets = await Promise.all(
      input.core.components.map(async (component) => {
        const focus = { startToken: component.startToken, endToken: component.endToken };
        const key = await this.detailKey({ sentence: input.sentence, focus });
        const cached = validateCachedDetail(
          await this.options.cache.getDetail<unknown>(key),
          input.sentence,
          focus,
          input.profile.id,
        );
        return { focus, key, cached };
      }),
    );
    if (signal.aborted) throw cancellationError();
    let missing: { focus: TokenRange; key: string }[] = targets.filter(
      ({ cached }) => cached === undefined,
    );
    let succeeded = targets.length - missing.length;
    if (missing.length === 0) return { succeeded, failed: 0 };

    const cacheKey = `${missing.map(({ key }) => key).join(":")}:sentence-details`;
    const raw = await this.requestModel(
      input.profile,
      input.documentId,
      "prefetch-detail",
      cacheKey,
      1,
      [
        {
          role: "user",
          content: buildSentenceDetailsPrompt(
            input.sentence,
            input.core,
            missing.map(({ focus }) => focus),
          ),
        },
      ],
      SENTENCE_DETAILS_SCHEMA,
      "sentence-details",
      signal,
    );
    const firstPass = await this.validateAndCacheDetails(input, missing, raw);
    succeeded += firstPass.valid;
    missing = firstPass.invalid;
    if (missing.length > 0) {
      const repairRaw = await this.requestModel(
        input.profile,
        input.documentId,
        "prefetch-detail",
        `${cacheKey}:repair`,
        1,
        [
          {
            role: "user",
            content: sentenceDetailsRepairPrompt(
              input,
              missing.map(({ focus }) => focus),
              firstPass.errors,
              raw,
            ),
          },
        ],
        SENTENCE_DETAILS_SCHEMA,
        "sentence-details-repair",
        signal,
      );
      const secondPass = await this.validateAndCacheDetails(input, missing, repairRaw);
      succeeded += secondPass.valid;
      missing = secondPass.invalid;
    }
    return { succeeded, failed: missing.length };
  }

  /** 逐 focus 从原始响应里捞对应条目、校验并写缓存;返回合格数与失败目标。 */
  private async validateAndCacheDetails(
    input: SentenceDetailsInput,
    targets: readonly { focus: TokenRange; key: string }[],
    raw: unknown,
  ): Promise<{
    valid: number;
    invalid: { focus: TokenRange; key: string }[];
    errors: ValidationError[];
  }> {
    const rawDetails =
      isRecord(raw) && Array.isArray(raw.details) ? (raw.details as unknown[]) : [];
    let valid = 0;
    const invalid: { focus: TokenRange; key: string }[] = [];
    const errors: ValidationError[] = [];
    for (const target of targets) {
      const candidate = rawDetails.find(
        (item) => isRecord(item) && isMatchingFocus(item.focus, target.focus),
      );
      const validation =
        candidate === undefined
          ? undefined
          : validateDetail(candidate, input.sentence, target.focus, input.profile.id);
      if (validation !== undefined && validation.ok) {
        await this.options.cache.putDetail(target.key, input.profile.id, validation.value);
        valid += 1;
        continue;
      }
      invalid.push(target);
      if (validation !== undefined) errors.push(...validation.errors);
      else
        errors.push({
          path: `details[focus ${target.focus.startToken}-${target.focus.endToken}]`,
          message: "missing entry for requested focus",
        });
    }
    return { valid, invalid, errors };
  }

  async reanalyzeWithFeedback(input: CorrectionInput, signal: AbortSignal): Promise<CoreOutcome> {
    if (signal.aborted) throw cancellationError();
    const key = await this.correctionKey(input);
    const cached = validateCachedCore(
      await this.options.cache.getCorrection<unknown>(key),
      input.sentence,
      input.profile.id,
    );
    if (signal.aborted) throw cancellationError();
    if (cached !== undefined) return { result: cached, cacheHit: true };

    const raw = await this.requestModel(
      input.profile,
      input.documentId,
      "user-retry",
      key,
      1,
      [{ role: "user", content: correctionPrompt(input) }],
      CORE_SCHEMA,
      "correction",
      signal,
    );
    let validation = validateCoreBatch(raw, [input.sentence], input.profile.id);
    if (!validation.ok) {
      const repairRaw = await this.requestModel(
        input.profile,
        input.documentId,
        "user-retry",
        `${key}:repair`,
        1,
        [
          {
            role: "user",
            content: correctionRepairPrompt(input, validation.errors, raw),
          },
        ],
        CORE_SCHEMA,
        "correction-repair",
        signal,
      );
      validation = validateCoreBatch(repairRaw, [input.sentence], input.profile.id);
    }
    if (!validation.ok) throw invalidOutput(validation.errors);
    const result = validation.value[0]!;
    await this.options.cache.putCorrection(key, input.profile.id, result);
    return { result, cacheHit: false };
  }

  private async validateAndCacheCore(
    profile: ModelProfile,
    entries: readonly { sentence: SentenceInput; key: string }[],
    raw: unknown,
  ): Promise<{ valid: CoreAnalysis[]; invalid: InvalidCoreSentence[] }> {
    const valid: CoreAnalysis[] = [];
    const invalid: InvalidCoreSentence[] = [];
    for (const { sentence, key } of entries) {
      const validation = validateCoreBatch(
        { sentences: matchingRawSentences(raw, sentence.sentenceId) },
        [sentence],
        profile.id,
      );
      if (validation.ok) {
        const analysis = validation.value[0]!;
        valid.push(analysis);
        await this.options.cache.putCore(key, profile.id, analysis);
      } else {
        invalid.push({ sentence, errors: validation.errors });
      }
    }
    return { valid, invalid };
  }

  private async requestModel(
    profile: ModelProfile,
    documentId: string,
    priority: SchedulerPriority,
    cacheKey: string,
    sentenceCount: number,
    messages: readonly ChatMessage[],
    schema: JsonSchemaSpec,
    batchKey: string,
    signal: AbortSignal,
  ): Promise<unknown> {
    if (signal.aborted) throw cancellationError();
    const work: AnalysisModelWork = {
      profile,
      messages,
      schema,
      requestedAt: this.now(),
      run: (schedulerSignal) =>
        this.options.adapter.completeJson(profile, messages, schema, schedulerSignal),
    };
    const onAbort = () => this.options.scheduler.cancelDocument(documentId);
    signal.addEventListener("abort", onAbort, { once: true });
    try {
      return await this.options.scheduler.schedule({
        cacheKey,
        documentId,
        priority,
        sentenceCount,
        input: work,
        batchKey: `${batchKey}:${profile.id}`,
      });
    } finally {
      signal.removeEventListener("abort", onAbort);
    }
  }

  private coreKey(sentence: SentenceInput): Promise<string> {
    return createCoreCacheKey({
      normalizedSentence: normalizedSentenceText(sentence),
      schemaVersion: CORE_SCHEMA_VERSION,
    });
  }

  private detailKey(input: { sentence: SentenceInput; focus: TokenRange }): Promise<string> {
    return createCoreCacheKey({
      normalizedSentence: normalizedSentenceText(input.sentence),
      schemaVersion: CORE_SCHEMA_VERSION,
      focus: input.focus,
    });
  }

  private correctionKey(input: CorrectionInput): Promise<string> {
    return createCorrectionCacheKey({
      normalizedSentence: normalizedSentenceText(input.sentence),
      schemaVersion: CORE_SCHEMA_VERSION,
      pageUrl: input.pageUrl,
      sentenceInstanceId: input.sentenceInstanceId,
      correctionContext: input.feedback,
    });
  }
}
