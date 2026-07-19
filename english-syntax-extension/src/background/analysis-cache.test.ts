import "fake-indexeddb/auto";
import { describe, expect, it, vi } from "vitest";
import { AnalysisCache, createCoreCacheKey, createCorrectionCacheKey } from "./analysis-cache";

const coreIdentity = {
  normalizedSentence: "The cat sleeps.",
  schemaVersion: 1,
};

describe("analysis cache keys", () => {
  it.each([
    ["sentence", { normalizedSentence: "The dog sleeps." }],
    ["schema version", { schemaVersion: 2 }],
    ["focus interval", { focus: { startToken: 1, endToken: 2 } }],
  ])("separates keys by %s", async (_field, override) => {
    const baseline = await createCoreCacheKey(coreIdentity);
    const changed = await createCoreCacheKey({ ...coreIdentity, ...override });

    expect(changed).not.toBe(baseline);
  });

  it("ignores profile, provider, model, and prompt-version fields entirely", async () => {
    const baseline = await createCoreCacheKey(coreIdentity);
    const withLegacyFields = await createCoreCacheKey({
      ...coreIdentity,
      profileId: "profile-2",
      providerOrigin: "https://other.example.com",
      model: "other-model",
      promptVersion: 99,
    } as never);

    expect(withLegacyFields).toBe(baseline);
  });

  it("separates correction context without including API keys", async () => {
    const secret = "sk-never-cache-this";
    const inputWithSecret = { ...coreIdentity, apiKey: secret };
    const correctionInputWithSecret = {
      ...coreIdentity,
      pageUrl: "https://reader.example/article",
      sentenceInstanceId: "sentence-7",
      correctionContext: "Treat 'sleep' as a noun.",
      apiKey: secret,
    };
    const baseline = await createCoreCacheKey(coreIdentity);
    const withSecret = await createCoreCacheKey(inputWithSecret);
    const correction = await createCorrectionCacheKey(correctionInputWithSecret);
    const correctionWithoutSecret = await createCorrectionCacheKey({
      ...coreIdentity,
      pageUrl: "https://reader.example/article",
      sentenceInstanceId: "sentence-7",
      correctionContext: "Treat 'sleep' as a noun.",
    });
    const changedCorrection = await createCorrectionCacheKey({
      ...coreIdentity,
      pageUrl: "https://reader.example/article",
      sentenceInstanceId: "sentence-7",
      correctionContext: "Treat 'sleep' as a verb.",
    });

    expect(withSecret).toBe(baseline);
    expect(correction).toBe(correctionWithoutSecret);
    expect(correction).not.toBe(changedCorrection);
    expect(withSecret).not.toContain(secret);
    expect(correction).not.toContain(secret);
    expect(withSecret).toMatch(/^[a-f\d]{64}$/u);
    expect(correction).toMatch(/^[a-f\d]{64}$/u);
  });
});

const estimatedSize = (value: unknown) =>
  new TextEncoder().encode(JSON.stringify(value)).byteLength + 256;

async function emptyCache(limitBytes = 50 * 1024 * 1024, now?: () => number) {
  const cache = await AnalysisCache.open({ limitBytes, now });
  await cache.clear();
  return cache;
}

describe("AnalysisCache", () => {
  it("round-trips core and detail values and reports their estimated size", async () => {
    const cache = await emptyCache();
    const coreValue = { sentenceId: "core-sentence", components: ["subject"] };
    const detailValue = { sentenceId: "detail-sentence", explanation: "A detail." };

    await cache.putCore("core-key", "profile-a", coreValue);
    await cache.putDetail("detail-key", "profile-a", detailValue);

    expect(await cache.getCore("core-key")).toEqual(coreValue);
    expect(await cache.getDetail("detail-key")).toEqual(detailValue);
    expect(await cache.stats()).toEqual({
      entries: 2,
      estimatedBytes: estimatedSize(coreValue) + estimatedSize(detailValue),
      limitBytes: 50 * 1024 * 1024,
    });
  });

  it("round-trips correction values through the isolated correction store", async () => {
    const cache = await emptyCache();
    const correctionValue = { sentenceId: "corrected-sentence", components: ["subject"] };

    await cache.putCorrection("correction-key", "profile-a", correctionValue);

    expect(await cache.getCorrection("correction-key")).toEqual(correctionValue);
    expect(await cache.getCore("correction-key")).toBeUndefined();
    expect(await cache.getDetail("correction-key")).toBeUndefined();
  });

  it("updates read recency before evicting the least recently used known-size record", async () => {
    let timestamp = 0;
    const value = "x";
    const cache = await emptyCache(estimatedSize(value) * 2, () => ++timestamp);

    await cache.putCore("first", "profile-a", value);
    await cache.putCore("second", "profile-a", value);
    expect(await cache.getCore("first")).toBe(value);

    await cache.putCore("third", "profile-a", value);

    expect(await cache.getCore("second")).toBeUndefined();
    expect(await cache.getCore("first")).toBe(value);
    expect(await cache.getCore("third")).toBe(value);
    expect((await cache.stats()).entries).toBe(2);
  });

  it("clears only records belonging to the selected profile", async () => {
    const cache = await emptyCache();
    await cache.putCore("core-a", "profile-a", { id: "core-a" });
    await cache.putDetail("detail-a", "profile-a", { id: "detail-a" });
    await cache.putCore("core-b", "profile-b", { id: "core-b" });

    await cache.clearByProfile("profile-a");

    expect(await cache.getCore("core-a")).toBeUndefined();
    expect(await cache.getDetail("detail-a")).toBeUndefined();
    expect(await cache.getCore("core-b")).toEqual({ id: "core-b" });
  });

  it("clears every cache store without touching chrome.storage.local", async () => {
    const local = { get: vi.fn(), set: vi.fn(), clear: vi.fn(), remove: vi.fn() };
    vi.stubGlobal("chrome", { storage: { local } });
    const cache = await emptyCache();
    await cache.putCore("core", "profile-a", { id: "core" });
    await cache.putDetail("detail", "profile-a", { id: "detail" });

    await cache.clear();

    expect(await cache.stats()).toMatchObject({ entries: 0, estimatedBytes: 0 });
    expect(local.get).not.toHaveBeenCalled();
    expect(local.set).not.toHaveBeenCalled();
    expect(local.clear).not.toHaveBeenCalled();
    expect(local.remove).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });
});
