import "fake-indexeddb/auto";
import { describe, expect, it } from "vitest";
import { AnalysisCache } from "./analysis-cache";

const DATABASE_NAME = "english-syntax-learning-v1";
const STORE_NAMES = ["core", "detail", "correction"] as const;

function seedVersionOneDatabase(): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, 1);
    request.addEventListener("upgradeneeded", () => {
      for (const storeName of STORE_NAMES) {
        const store = request.result.createObjectStore(storeName, { keyPath: "key" });
        store.createIndex("lastAccessedAt", "lastAccessedAt");
      }
    });
    request.addEventListener("success", () => {
      const database = request.result;
      const transaction = database.transaction("core", "readwrite");
      transaction.objectStore("core").put({
        key: "legacy-key",
        profileId: "profile-legacy",
        value: { sentenceId: "old" },
        createdAt: 1,
        lastAccessedAt: 1,
        estimatedBytes: 100,
      });
      transaction.addEventListener("complete", () => {
        database.close();
        resolve();
      });
      transaction.addEventListener("abort", () => reject(new Error("seed aborted")));
    });
    request.addEventListener("error", () => reject(request.error ?? new Error("seed failed")));
  });
}

describe("AnalysisCache database migration", () => {
  it("upgrades a version-1 database by clearing legacy stores and stays usable", async () => {
    await seedVersionOneDatabase();

    const cache = await AnalysisCache.open();

    expect(await cache.getCore("legacy-key")).toBeUndefined();
    expect(await cache.stats()).toMatchObject({ entries: 0 });
    await cache.putCore("fresh-key", "profile-a", { sentenceId: "new" });
    expect(await cache.getCore("fresh-key")).toEqual({ sentenceId: "new" });
  });
});
