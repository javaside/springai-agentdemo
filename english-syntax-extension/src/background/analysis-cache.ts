import type { TokenRange } from "../shared/grammar";
import type { CacheStats } from "../shared/protocol";

const DATABASE_NAME = "english-syntax-learning-v1";
const DATABASE_VERSION = 1;
const DEFAULT_LIMIT_BYTES = 50 * 1024 * 1024;
const STORE_NAMES = ["core", "detail", "correction"] as const;

type StoreName = (typeof STORE_NAMES)[number];

export interface CacheRecord<T> {
  key: string;
  profileId: string;
  value: T;
  createdAt: number;
  lastAccessedAt: number;
  estimatedBytes: number;
}

export interface AnalysisCacheOptions {
  limitBytes?: number;
  now?: () => number;
}

export interface CoreCacheKeyInput {
  normalizedSentence: string;
  schemaVersion: number;
  focus?: TokenRange;
}

export interface CorrectionCacheKeyInput extends CoreCacheKeyInput {
  pageUrl: string;
  sentenceInstanceId: string;
  correctionContext: string;
}

function coreIdentity(input: CoreCacheKeyInput): readonly unknown[] {
  return [
    input.normalizedSentence,
    input.schemaVersion,
    input.focus === undefined ? null : [input.focus.startToken, input.focus.endToken],
  ];
}

async function sha256(value: unknown): Promise<string> {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function createCoreCacheKey(input: CoreCacheKeyInput): Promise<string> {
  return sha256(["core", ...coreIdentity(input)]);
}

export function createCorrectionCacheKey(input: CorrectionCacheKeyInput): Promise<string> {
  return sha256([
    "correction",
    ...coreIdentity(input),
    input.pageUrl,
    input.sentenceInstanceId,
    input.correctionContext,
  ]);
}

function requestResult<T>(request: IDBRequest): Promise<T> {
  return new Promise((resolve, reject) => {
    request.addEventListener(
      "success",
      () => {
        const result: unknown = request.result;
        resolve(result as T);
      },
      { once: true },
    );
    request.addEventListener(
      "error",
      () => reject(request.error ?? new Error("IndexedDB request failed")),
      { once: true },
    );
  });
}

function transactionDone(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.addEventListener("complete", () => resolve(), { once: true });
    transaction.addEventListener(
      "abort",
      () => reject(transaction.error ?? new Error("IndexedDB transaction aborted")),
      { once: true },
    );
    transaction.addEventListener(
      "error",
      () => reject(transaction.error ?? new Error("IndexedDB transaction failed")),
      { once: true },
    );
  });
}

function estimateBytes(value: unknown): number {
  return new TextEncoder().encode(JSON.stringify(value)).byteLength + 256;
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
    request.addEventListener(
      "upgradeneeded",
      () => {
        for (const storeName of STORE_NAMES) {
          const store = request.result.createObjectStore(storeName, { keyPath: "key" });
          store.createIndex("lastAccessedAt", "lastAccessedAt");
        }
      },
      { once: true },
    );
    request.addEventListener("success", () => resolve(request.result), { once: true });
    request.addEventListener(
      "error",
      () =>
        reject(request.error ?? new Error(`Opening IndexedDB database ${DATABASE_NAME} failed`)),
      { once: true },
    );
    request.addEventListener(
      "blocked",
      () => reject(new Error(`Opening IndexedDB database ${DATABASE_NAME} was blocked`)),
      { once: true },
    );
  });
}

export class AnalysisCache {
  private lastTimestamp = Number.NEGATIVE_INFINITY;

  private constructor(
    private readonly database: IDBDatabase,
    private readonly limitBytes: number,
    private readonly now: () => number,
  ) {}

  static async open(options: AnalysisCacheOptions = {}): Promise<AnalysisCache> {
    const limitBytes = options.limitBytes ?? DEFAULT_LIMIT_BYTES;
    if (!Number.isSafeInteger(limitBytes) || limitBytes < 0) {
      throw new Error("Cache byte limit must be a non-negative safe integer");
    }
    return new AnalysisCache(await openDatabase(), limitBytes, options.now ?? Date.now);
  }

  getCore<T>(key: string): Promise<T | undefined> {
    return this.get<T>("core", key);
  }

  async putCore<T>(key: string, profileId: string, value: T): Promise<void> {
    await this.put("core", key, profileId, value);
  }

  getDetail<T>(key: string): Promise<T | undefined> {
    return this.get<T>("detail", key);
  }

  async putDetail<T>(key: string, profileId: string, value: T): Promise<void> {
    await this.put("detail", key, profileId, value);
  }

  getCorrection<T>(key: string): Promise<T | undefined> {
    return this.get<T>("correction", key);
  }

  async putCorrection<T>(key: string, profileId: string, value: T): Promise<void> {
    await this.put("correction", key, profileId, value);
  }

  async stats(): Promise<CacheStats> {
    const transaction = this.database.transaction(STORE_NAMES, "readonly");
    const done = transactionDone(transaction);
    const records = await Promise.all(
      STORE_NAMES.map((storeName) =>
        requestResult<CacheRecord<unknown>[]>(transaction.objectStore(storeName).getAll()),
      ),
    );
    await done;
    return {
      entries: records.reduce((total, storeRecords) => total + storeRecords.length, 0),
      estimatedBytes: records.flat().reduce((total, record) => total + record.estimatedBytes, 0),
      limitBytes: this.limitBytes,
    };
  }

  async clear(): Promise<void> {
    const transaction = this.database.transaction(STORE_NAMES, "readwrite");
    const done = transactionDone(transaction);
    for (const storeName of STORE_NAMES) {
      transaction.objectStore(storeName).clear();
    }
    await done;
  }

  async clearByProfile(profileId: string): Promise<void> {
    const transaction = this.database.transaction(STORE_NAMES, "readwrite");
    const done = transactionDone(transaction);
    await Promise.all(
      STORE_NAMES.map(
        (storeName) =>
          new Promise<void>((resolve, reject) => {
            const request = transaction.objectStore(storeName).openCursor();
            request.addEventListener(
              "error",
              () => reject(request.error ?? new Error("Reading the cache cursor failed")),
              { once: true },
            );
            request.addEventListener("success", () => {
              const cursor = request.result;
              if (cursor === null) {
                resolve();
                return;
              }
              if ((cursor.value as CacheRecord<unknown>).profileId === profileId) {
                cursor.delete();
              }
              cursor.continue();
            });
          }),
      ),
    );
    await done;
  }

  async enforceLimit(): Promise<void> {
    const transaction = this.database.transaction(STORE_NAMES, "readwrite");
    const done = transactionDone(transaction);
    const recordsByStore = await Promise.all(
      STORE_NAMES.map(async (storeName) => ({
        storeName,
        records: await requestResult<CacheRecord<unknown>[]>(
          transaction.objectStore(storeName).index("lastAccessedAt").getAll(),
        ),
      })),
    );
    const records = recordsByStore
      .flatMap(({ storeName, records: storeRecords }) =>
        storeRecords.map((record) => ({ storeName, record })),
      )
      .sort((left, right) => left.record.lastAccessedAt - right.record.lastAccessedAt);
    let runningEstimate = records.reduce((total, { record }) => total + record.estimatedBytes, 0);
    for (const { storeName, record } of records) {
      if (runningEstimate <= this.limitBytes) {
        break;
      }
      transaction.objectStore(storeName).delete(record.key);
      runningEstimate -= record.estimatedBytes;
    }
    await done;
  }

  private async get<T>(storeName: StoreName, key: string): Promise<T | undefined> {
    const transaction = this.database.transaction(storeName, "readwrite");
    const done = transactionDone(transaction);
    const store = transaction.objectStore(storeName);
    const record = await requestResult<CacheRecord<T> | undefined>(store.get(key));
    if (record !== undefined) {
      record.lastAccessedAt = this.nextTimestamp(record.lastAccessedAt);
      store.put(record);
    }
    await done;
    return record?.value;
  }

  private async put<T>(storeName: StoreName, key: string, profileId: string, value: T) {
    const timestamp = this.nextTimestamp();
    const record: CacheRecord<T> = {
      key,
      profileId,
      value,
      createdAt: timestamp,
      lastAccessedAt: timestamp,
      estimatedBytes: estimateBytes(value),
    };
    const transaction = this.database.transaction(storeName, "readwrite");
    const done = transactionDone(transaction);
    transaction.objectStore(storeName).put(record);
    await done;
    await this.enforceLimit();
  }

  private nextTimestamp(previous = Number.NEGATIVE_INFINITY): number {
    const timestamp = Math.max(this.now(), this.lastTimestamp + 1, previous + 1);
    this.lastTimestamp = timestamp;
    return timestamp;
  }
}
