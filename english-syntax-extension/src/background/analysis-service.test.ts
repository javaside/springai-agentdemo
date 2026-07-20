import { describe, expect, it, vi } from "vitest";
import { GrammarRole } from "../shared/grammar";
import type { CoreAnalysis, TokenRange } from "../shared/grammar";
import type { SentenceInput } from "../shared/protocol";
import { ModelRequestError } from "./openai-compatible-adapter";
import type { ModelProfile } from "./config-repository";
import {
  CachedAnalysisService,
  type AnalysisCachePort,
  type AnalysisModelWork,
  type AnalysisScheduledRequest,
  type AnalysisScheduler,
} from "./analysis-service";

const profile: ModelProfile = {
  id: "profile-1",
  name: "Compatible",
  baseUrl: "https://model.example/v1",
  apiKey: "secret",
  model: "syntax-model",
  headers: {},
  timeoutMs: 5_000,
  jsonSchemaSupport: "supported",
};

const sentenceOne: SentenceInput = {
  sentenceId: "sentence-1",
  text: "Learners read.",
  tokens: [
    { id: 0, text: "Learners", start: 0, end: 8, leadingWhitespace: "", punctuation: false },
    { id: 1, text: "read", start: 9, end: 13, leadingWhitespace: " ", punctuation: false },
    { id: 2, text: ".", start: 13, end: 14, leadingWhitespace: "", punctuation: true },
  ],
};

const sentenceTwo: SentenceInput = {
  sentenceId: "sentence-2",
  text: "Writers revise.",
  tokens: [
    { id: 0, text: "Writers", start: 0, end: 7, leadingWhitespace: "", punctuation: false },
    { id: 1, text: "revise", start: 8, end: 14, leadingWhitespace: " ", punctuation: false },
    { id: 2, text: ".", start: 14, end: 15, leadingWhitespace: "", punctuation: true },
  ],
};

function rawCore(sentence: SentenceInput) {
  return {
    sentenceId: sentence.sentenceId,
    components: [
      { startToken: 0, endToken: 0, role: "SUBJECT", translation: "主语" },
      { startToken: 1, endToken: 2, role: "PREDICATE", translation: "谓语" },
    ],
  };
}

function coreAnalysis(sentence: SentenceInput, modelProfileId = profile.id): CoreAnalysis {
  return {
    schemaVersion: 1,
    sentenceId: sentence.sentenceId,
    components: [
      { startToken: 0, endToken: 0, role: GrammarRole.SUBJECT, translation: "主语" },
      { startToken: 1, endToken: 2, role: GrammarRole.PREDICATE, translation: "谓语" },
    ],
    modelProfileId,
  };
}

function rawDetail(focus: TokenRange) {
  return {
    sentenceId: sentenceOne.sentenceId,
    focus,
    structures: [{ ...focus, role: "verb phrase", explanation: "谓语结构" }],
    grammarPoints: ["一般现在时"],
    explanation: "详细说明。",
  };
}

class MemoryCache implements AnalysisCachePort {
  readonly core = new Map<string, unknown>();
  readonly detail = new Map<string, unknown>();
  readonly correction = new Map<string, unknown>();

  getCore<T>(key: string): Promise<T | undefined> {
    return Promise.resolve(this.core.get(key) as T | undefined);
  }

  putCore<T>(key: string, _profileId: string, value: T): Promise<void> {
    this.core.set(key, value);
    return Promise.resolve();
  }

  getDetail<T>(key: string): Promise<T | undefined> {
    return Promise.resolve(this.detail.get(key) as T | undefined);
  }

  putDetail<T>(key: string, _profileId: string, value: T): Promise<void> {
    this.detail.set(key, value);
    return Promise.resolve();
  }

  getCorrection<T>(key: string): Promise<T | undefined> {
    return Promise.resolve(this.correction.get(key) as T | undefined);
  }

  putCorrection<T>(key: string, _profileId: string, value: T): Promise<void> {
    this.correction.set(key, value);
    return Promise.resolve();
  }
}

class DedupeScheduler implements AnalysisScheduler {
  readonly schedule = vi.fn((request: AnalysisScheduledRequest) => {
    const identity = `${request.documentId}\0${request.cacheKey}`;
    const duplicate = this.inFlight.get(identity);
    if (duplicate !== undefined) return duplicate;
    const controller = new AbortController();
    this.controllers.set(request.documentId, controller);
    const result = request.input.run(controller.signal).finally(() => {
      this.inFlight.delete(identity);
      this.controllers.delete(request.documentId);
    });
    this.inFlight.set(identity, result);
    return result;
  });

  readonly cancelDocument = vi.fn((documentId: string) => {
    this.controllers.get(documentId)?.abort();
  });

  private readonly inFlight = new Map<string, Promise<unknown>>();
  private readonly controllers = new Map<string, AbortController>();
}

function harness(outputs: readonly unknown[]) {
  const cache = new MemoryCache();
  const completeJson = vi.fn().mockImplementation(() => {
    const next = outputs[completeJson.mock.calls.length - 1];
    return next instanceof Error ? Promise.reject(next) : Promise.resolve(next);
  });
  const adapter = { completeJson };
  const scheduler = new DedupeScheduler();
  const service = new CachedAnalysisService({
    cache,
    adapter,
    scheduler,
    now: () => 42,
  });
  return { adapter, cache, scheduler, service };
}

function coreInput(sentences: SentenceInput[] = [sentenceOne], selectedProfile = profile) {
  return {
    profile: selectedProfile,
    documentId: "document-1",
    sentences,
    priority: "visible-core" as const,
  };
}

describe("CachedAnalysisService core orchestration", () => {
  it("bypassCache skips reads but overwrites the cache with the fresh result", async () => {
    const { adapter, cache, scheduler, service } = harness([
      { sentences: [rawCore(sentenceOne)] },
      { sentences: [rawCore(sentenceOne)] },
    ]);
    await service.analyzeCore(coreInput(), new AbortController().signal);
    expect(cache.core.size).toBe(1);
    adapter.completeJson.mockClear();
    scheduler.schedule.mockClear();

    const outcome = await service.analyzeCore(
      { ...coreInput(), bypassCache: true },
      new AbortController().signal,
    );

    expect(outcome.cacheHit).toBe(false);
    expect(adapter.completeJson).toHaveBeenCalledTimes(1);
    expect(cache.core.size).toBe(1);
  });

  it("returns a cache hit without scheduling or calling the adapter", async () => {
    const { adapter, scheduler, service } = harness([{ sentences: [rawCore(sentenceOne)] }]);
    await service.analyzeCore(coreInput(), new AbortController().signal);
    adapter.completeJson.mockClear();
    scheduler.schedule.mockClear();

    const outcome = await service.analyzeCore(coreInput(), new AbortController().signal);

    expect(outcome).toEqual({
      result: [coreAnalysis(sentenceOne)],
      failures: [],
      cacheHit: true,
    });
    expect(scheduler.schedule).not.toHaveBeenCalled();
    expect(adapter.completeJson).not.toHaveBeenCalled();
  });

  it("rebinds a same-text core cache hit to the current sentence ID and profile", async () => {
    const reboundSentence = { ...sentenceOne, sentenceId: "sentence-rebound" };
    const { adapter, cache, scheduler, service } = harness([{ sentences: [rawCore(sentenceOne)] }]);
    await service.analyzeCore(coreInput(), new AbortController().signal);
    const key = cache.core.keys().next().value;
    if (key === undefined) throw new Error("expected a core cache entry");
    cache.core.set(key, { ...coreAnalysis(sentenceOne), modelProfileId: "stale-profile" });
    adapter.completeJson.mockClear();
    scheduler.schedule.mockClear();

    const outcome = await service.analyzeCore(
      coreInput([reboundSentence]),
      new AbortController().signal,
    );

    expect(outcome).toEqual({
      result: [coreAnalysis(reboundSentence)],
      failures: [],
      cacheHit: true,
    });
    expect(scheduler.schedule).not.toHaveBeenCalled();
    expect(adapter.completeJson).not.toHaveBeenCalled();
  });

  it("treats a same-text cache entry with incompatible tokenization as a miss", async () => {
    const retokenized: SentenceInput = {
      ...sentenceOne,
      sentenceId: "sentence-retokenized",
      tokens: [
        {
          id: 0,
          text: "Learners read",
          start: 0,
          end: 13,
          leadingWhitespace: "",
          punctuation: false,
        },
        { id: 1, text: ".", start: 13, end: 14, leadingWhitespace: "", punctuation: true },
      ],
    };
    const retokenizedRaw = {
      sentenceId: retokenized.sentenceId,
      components: [{ startToken: 0, endToken: 1, role: "SUBJECT", translation: "学习者阅读" }],
    };
    const { adapter, service } = harness([
      { sentences: [rawCore(sentenceOne)] },
      { sentences: [retokenizedRaw] },
    ]);
    await service.analyzeCore(coreInput(), new AbortController().signal);

    const outcome = await service.analyzeCore(
      coreInput([retokenized]),
      new AbortController().signal,
    );

    expect(outcome.cacheHit).toBe(false);
    expect(outcome.result[0]).toMatchObject({
      sentenceId: retokenized.sentenceId,
      components: [{ startToken: 0, endToken: 1 }],
      modelProfileId: profile.id,
    });
    expect(adapter.completeJson).toHaveBeenCalledTimes(2);
  });

  it("treats malformed cached core fields as a miss", async () => {
    const { adapter, cache, service } = harness([
      { sentences: [rawCore(sentenceOne)] },
      { sentences: [rawCore(sentenceOne)] },
    ]);
    await service.analyzeCore(coreInput(), new AbortController().signal);
    const key = cache.core.keys().next().value;
    if (key === undefined) throw new Error("expected a core cache entry");
    cache.core.set(key, { components: "not-an-array" });

    const outcome = await service.analyzeCore(coreInput(), new AbortController().signal);

    expect(outcome.cacheHit).toBe(false);
    expect(outcome.result).toEqual([coreAnalysis(sentenceOne)]);
    expect(adapter.completeJson).toHaveBeenCalledTimes(2);
  });

  it("deduplicates concurrent identical misses into one model call", async () => {
    let release!: (value: unknown) => void;
    const raw = new Promise<unknown>((resolve) => {
      release = resolve;
    });
    const { adapter, scheduler, service } = harness([raw]);

    const first = service.analyzeCore(coreInput(), new AbortController().signal);
    const second = service.analyzeCore(coreInput(), new AbortController().signal);
    await vi.waitFor(() => expect(scheduler.schedule).toHaveBeenCalledTimes(2));
    release({ sentences: [rawCore(sentenceOne)] });

    await expect(Promise.all([first, second])).resolves.toEqual([
      { result: [coreAnalysis(sentenceOne)], failures: [], cacheHit: false },
      { result: [coreAnalysis(sentenceOne)], failures: [], cacheHit: false },
    ]);
    expect(adapter.completeJson).toHaveBeenCalledTimes(1);
  });

  it("caches valid raw output and stamps the scheduled work with the injected clock", async () => {
    const { cache, scheduler, service } = harness([{ sentences: [rawCore(sentenceOne)] }]);

    const outcome = await service.analyzeCore(coreInput(), new AbortController().signal);

    expect(outcome.result).toEqual([coreAnalysis(sentenceOne)]);
    expect(cache.core.size).toBe(1);
    expect(scheduler.schedule.mock.calls[0]![0].input.requestedAt).toBe(42);
  });

  it("supplies a strict nested core schema to compatible adapters", async () => {
    const { adapter, service } = harness([{ sentences: [rawCore(sentenceOne)] }]);

    await service.analyzeCore(coreInput(), new AbortController().signal);

    const schemaJson = JSON.stringify(adapter.completeJson.mock.calls[0]![2]);
    expect(schemaJson).toContain('"components"');
    expect(schemaJson).toContain('"startToken"');
    expect(schemaJson).toContain('"endToken"');
    expect(schemaJson).toContain('"role"');
    expect(schemaJson).toContain('"translation"');
  });

  it("sends exactly one repair request after invalid raw output", async () => {
    const { adapter, service } = harness([
      { sentences: [{ ...rawCore(sentenceOne), components: [] }] },
      { sentences: [rawCore(sentenceOne)] },
    ]);

    await expect(
      service.analyzeCore(coreInput(), new AbortController().signal),
    ).resolves.toMatchObject({ result: [coreAnalysis(sentenceOne)], failures: [] });
    expect(adapter.completeJson).toHaveBeenCalledTimes(2);
    const repairWork = adapter.completeJson.mock.calls[1] as [
      ModelProfile,
      AnalysisModelWork["messages"],
    ];
    expect(repairWork[1][0]!.content).toMatch(/repair only/i);
    expect(repairWork[1][0]!.content).toContain("must be a non-empty array");
  });

  it("returns INVALID_MODEL_OUTPUT and does not cache after one failed repair", async () => {
    const invalid = { sentences: [{ ...rawCore(sentenceOne), components: [] }] };
    const { adapter, cache, service } = harness([invalid, invalid]);

    const outcome = await service.analyzeCore(coreInput(), new AbortController().signal);

    expect(outcome.result).toEqual([]);
    expect(outcome.failures).toHaveLength(1);
    expect(outcome.failures[0]!.sentenceId).toBe(sentenceOne.sentenceId);
    expect(outcome.failures[0]!.error).toMatchObject({
      code: "INVALID_MODEL_OUTPUT",
      retryable: false,
    });
    expect(adapter.completeJson).toHaveBeenCalledTimes(2);
    expect(cache.core.size).toBe(0);
  });

  it("keeps valid siblings when another sentence remains invalid after repair", async () => {
    const invalidTwo = { ...rawCore(sentenceTwo), components: [] };
    const { cache, service } = harness([
      { sentences: [rawCore(sentenceOne), invalidTwo] },
      { sentences: [invalidTwo] },
    ]);

    const outcome = await service.analyzeCore(
      coreInput([sentenceOne, sentenceTwo]),
      new AbortController().signal,
    );

    expect(outcome.result).toEqual([coreAnalysis(sentenceOne)]);
    expect(outcome.failures.map(({ sentenceId, error }) => [sentenceId, error.code])).toEqual([
      [sentenceTwo.sentenceId, "INVALID_MODEL_OUTPUT"],
    ]);
    expect(cache.core.size).toBe(1);
  });

  it("shares cache entries across profiles, providers, and models for the same sentence", async () => {
    const otherProfile = {
      ...profile,
      id: "profile-2",
      name: "Second",
      baseUrl: "https://other-provider.example/v1",
      model: "another-model",
    };
    const { adapter, cache, scheduler, service } = harness([{ sentences: [rawCore(sentenceOne)] }]);

    const first = await service.analyzeCore(coreInput(), new AbortController().signal);
    adapter.completeJson.mockClear();
    scheduler.schedule.mockClear();
    const second = await service.analyzeCore(
      coreInput([sentenceOne], otherProfile),
      new AbortController().signal,
    );

    expect(first.result[0]!.modelProfileId).toBe("profile-1");
    expect(second.cacheHit).toBe(true);
    expect(second.result[0]!.modelProfileId).toBe("profile-2");
    expect(adapter.completeJson).not.toHaveBeenCalled();
    expect(scheduler.schedule).not.toHaveBeenCalled();
    expect(cache.core.size).toBe(1);
  });

  it.each([
    ["network", new ModelRequestError("NETWORK_ERROR", "offline", true)],
    ["authorization", new ModelRequestError("AUTH_FAILED", "bad key", false)],
    ["cancellation", new ModelRequestError("REQUEST_CANCELLED", "cancelled", false)],
  ])("does not cache %s errors", async (_kind, error) => {
    const { adapter, cache, service } = harness([error, error]);

    await expect(service.analyzeCore(coreInput(), new AbortController().signal)).rejects.toBe(
      error,
    );
    await expect(service.analyzeCore(coreInput(), new AbortController().signal)).rejects.toBe(
      error,
    );

    expect(adapter.completeJson).toHaveBeenCalledTimes(2);
    expect(cache.core.size).toBe(0);
  });
});

describe("CachedAnalysisService isolated analysis modes", () => {
  it("supplies a strict nested detail schema to compatible adapters", async () => {
    const focus = { startToken: 1, endToken: 2 };
    const { adapter, service } = harness([rawDetail(focus)]);

    await service.analyzeDetail(
      {
        profile,
        documentId: "document-1",
        sentence: sentenceOne,
        core: coreAnalysis(sentenceOne),
        focus,
      },
      new AbortController().signal,
    );

    const schemaJson = JSON.stringify(adapter.completeJson.mock.calls[0]![2]);
    expect(schemaJson).toContain('"startToken"');
    expect(schemaJson).toContain('"endToken"');
    expect(schemaJson).toContain('"structures"');
    expect(schemaJson).toContain('"grammarPoints"');
    expect(schemaJson).toContain('"explanation"');
  });

  it("includes detail focus in its cache key", async () => {
    const firstFocus = { startToken: 0, endToken: 0 };
    const secondFocus = { startToken: 1, endToken: 2 };
    const { adapter, cache, service } = harness([rawDetail(firstFocus), rawDetail(secondFocus)]);
    const input = {
      profile,
      documentId: "document-1",
      sentence: sentenceOne,
      core: coreAnalysis(sentenceOne),
    };

    await service.analyzeDetail({ ...input, focus: firstFocus }, new AbortController().signal);
    await service.analyzeDetail({ ...input, focus: secondFocus }, new AbortController().signal);

    expect(adapter.completeJson).toHaveBeenCalledTimes(2);
    expect(cache.detail.size).toBe(2);
  });

  it("treats stale detail focus as a miss and stamps the current profile", async () => {
    const focus = { startToken: 1, endToken: 2 };
    const { adapter, cache, service } = harness([rawDetail(focus), rawDetail(focus)]);
    const input = {
      profile,
      documentId: "document-1",
      sentence: sentenceOne,
      core: coreAnalysis(sentenceOne),
      focus,
    };
    await service.analyzeDetail(input, new AbortController().signal);
    const key = cache.detail.keys().next().value;
    if (key === undefined) throw new Error("expected a detail cache entry");
    cache.detail.set(key, {
      ...rawDetail({ startToken: 0, endToken: 0 }),
      modelProfileId: "stale-profile",
    });

    const outcome = await service.analyzeDetail(input, new AbortController().signal);

    expect(outcome.cacheHit).toBe(false);
    expect(outcome.result).toEqual({ ...rawDetail(focus), modelProfileId: profile.id });
    expect(adapter.completeJson).toHaveBeenCalledTimes(2);
  });

  it("rebinds a same-text correction cache hit to the current sentence and profile", async () => {
    const reboundSentence = { ...sentenceOne, sentenceId: "sentence-correction-rebound" };
    const { adapter, cache, scheduler, service } = harness([{ sentences: [rawCore(sentenceOne)] }]);
    const base = {
      profile,
      documentId: "document-1",
      sentence: sentenceOne,
      core: coreAnalysis(sentenceOne),
      pageUrl: "https://reader.example/article",
      sentenceInstanceId: "instance-1",
      feedback: "Treat read as the predicate.",
    };
    await service.reanalyzeWithFeedback(base, new AbortController().signal);
    const key = cache.correction.keys().next().value;
    if (key === undefined) throw new Error("expected a correction cache entry");
    cache.correction.set(key, {
      ...coreAnalysis(sentenceOne),
      modelProfileId: "stale-profile",
    });
    adapter.completeJson.mockClear();
    scheduler.schedule.mockClear();

    const outcome = await service.reanalyzeWithFeedback(
      {
        ...base,
        sentence: reboundSentence,
        core: coreAnalysis(reboundSentence),
      },
      new AbortController().signal,
    );

    expect(outcome).toEqual({ result: coreAnalysis(reboundSentence), cacheHit: true });
    expect(scheduler.schedule).not.toHaveBeenCalled();
    expect(adapter.completeJson).not.toHaveBeenCalled();
  });

  it("retains feedback and prior verified core in the single correction repair", async () => {
    const invalid = { sentences: [{ ...rawCore(sentenceOne), components: [] }] };
    const { adapter, cache, service } = harness([invalid, { sentences: [rawCore(sentenceOne)] }]);
    const input = {
      profile,
      documentId: "document-1",
      sentence: sentenceOne,
      core: coreAnalysis(sentenceOne),
      pageUrl: "https://reader.example/article",
      sentenceInstanceId: "instance-1",
      feedback: "Treat read as the predicate, not a noun.",
    };

    const repaired = await service.reanalyzeWithFeedback(input, new AbortController().signal);
    const cached = await service.reanalyzeWithFeedback(input, new AbortController().signal);

    expect(repaired).toEqual({ result: coreAnalysis(sentenceOne), cacheHit: false });
    expect(cached).toEqual({ result: coreAnalysis(sentenceOne), cacheHit: true });
    expect(adapter.completeJson).toHaveBeenCalledTimes(2);
    const repairMessages = adapter.completeJson.mock.calls[1]![1] as AnalysisModelWork["messages"];
    expect(repairMessages[0]!.content).toContain(input.feedback);
    expect(repairMessages[0]!.content).toContain("Previously verified core analysis");
    expect(repairMessages[0]!.content).toContain('"modelProfileId": "profile-1"');
    expect(repairMessages[0]!.content).toContain("must be a non-empty array");
    expect(cache.correction.size).toBe(1);
  });

  it("lookupCore returns only cache hits and never touches the scheduler", async () => {
    const { adapter, scheduler, service } = harness([{ sentences: [rawCore(sentenceOne)] }]);
    await service.analyzeCore(coreInput(), new AbortController().signal);
    adapter.completeJson.mockClear();
    scheduler.schedule.mockClear();

    const results = await service.lookupCore([sentenceOne, sentenceTwo]);

    expect(results.map(({ sentenceId }) => sentenceId)).toEqual([sentenceOne.sentenceId]);
    expect(scheduler.schedule).not.toHaveBeenCalled();
    expect(adapter.completeJson).not.toHaveBeenCalled();
  });

  it("lookupDetail returns undefined on a miss and the analysis on a hit", async () => {
    const focus = { startToken: 1, endToken: 2 };
    const { service } = harness([rawDetail(focus)]);

    await expect(service.lookupDetail({ sentence: sentenceOne, focus })).resolves.toBeUndefined();

    await service.analyzeDetail(
      {
        profile,
        documentId: "document-1",
        sentence: sentenceOne,
        core: coreAnalysis(sentenceOne),
        focus,
      },
      new AbortController().signal,
    );
    const hit = await service.lookupDetail({ sentence: sentenceOne, focus });

    expect(hit?.sentenceId).toBe(sentenceOne.sentenceId);
  });

  it("lookupCore restamps the cached modelProfileId", async () => {
    const { service } = harness([{ sentences: [rawCore(sentenceOne)] }]);
    await service.analyzeCore(coreInput(), new AbortController().signal);

    const results = await service.lookupCore([sentenceOne]);

    expect(results).toEqual([coreAnalysis(sentenceOne, "cached")]);
  });

  it("isolates corrections by page, sentence instance, and feedback in the correction store", async () => {
    const { adapter, cache, service } = harness(
      Array.from({ length: 4 }, () => ({ sentences: [rawCore(sentenceOne)] })),
    );
    const base = {
      profile,
      documentId: "document-1",
      sentence: sentenceOne,
      core: coreAnalysis(sentenceOne),
      pageUrl: "https://reader.example/article",
      sentenceInstanceId: "instance-1",
      feedback: "Treat read as the predicate.",
    };

    const first = await service.reanalyzeWithFeedback(base, new AbortController().signal);
    const hit = await service.reanalyzeWithFeedback(base, new AbortController().signal);
    await service.reanalyzeWithFeedback(
      { ...base, pageUrl: "https://reader.example/other" },
      new AbortController().signal,
    );
    await service.reanalyzeWithFeedback(
      { ...base, sentenceInstanceId: "instance-2" },
      new AbortController().signal,
    );
    await service.reanalyzeWithFeedback(
      { ...base, feedback: "Treat read as a noun." },
      new AbortController().signal,
    );

    expect(first.cacheHit).toBe(false);
    expect(hit.cacheHit).toBe(true);
    expect(adapter.completeJson).toHaveBeenCalledTimes(4);
    expect(cache.correction.size).toBe(4);
    expect(cache.core.size).toBe(0);
    expect(cache.detail.size).toBe(0);
  });
});
