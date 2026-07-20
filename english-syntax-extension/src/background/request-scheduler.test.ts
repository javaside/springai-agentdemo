import { describe, expect, it, vi } from "vitest";
import { ModelRequestError } from "./openai-compatible-adapter";
import {
  RequestScheduler,
  type FetchTask,
  type ScheduledRequest,
  type SchedulerSleep,
  type SchedulerPriority,
} from "./request-scheduler";

interface Input {
  id: number;
  tokens: number;
}

function task(
  id: number,
  overrides: Partial<ScheduledRequest<Input>> = {},
): ScheduledRequest<Input> {
  return {
    cacheKey: `key-${id}`,
    documentId: "document-1",
    priority: "visible-core",
    sentenceCount: 1,
    input: { id, tokens: 500 },
    ...overrides,
  };
}

describe("request scheduler", () => {
  it("limits concurrency to two and batches at six sentences and 4,000 estimated tokens", async () => {
    let active = 0;
    let maximumActive = 0;
    const batchSizes: number[] = [];
    const releases: Array<() => void> = [];
    const fetchTask = vi.fn(async (batch: readonly ScheduledRequest<Input>[]) => {
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      batchSizes.push(batch.length);
      await new Promise<void>((resolve) => releases.push(resolve));
      active -= 1;
      return batch.map(({ input }) => input.id);
    });
    const scheduler = new RequestScheduler<Input, number>({
      fetchTask,
      estimateTokens: ({ input }) => input.tokens,
    });

    const results = Array.from({ length: 9 }, (_, id) => scheduler.schedule(task(id)));
    await vi.waitFor(() => expect(fetchTask).toHaveBeenCalledTimes(2));
    expect(maximumActive).toBe(2);
    expect(batchSizes.every((size) => size <= 6)).toBe(true);
    expect(
      fetchTask.mock.calls.every(
        ([batch]) => batch.reduce((sum, request) => sum + request.input.tokens, 0) <= 4_000,
      ),
    ).toBe(true);
    while (releases.length > 0) releases.shift()!();
    await vi.waitFor(() => expect(fetchTask).toHaveBeenCalledTimes(4));
    while (releases.length > 0) releases.shift()!();
    await expect(Promise.all(results)).resolves.toEqual(Array.from({ length: 9 }, (_, id) => id));
  });

  it("returns the same Promise for duplicate cache keys", () => {
    const scheduler = new RequestScheduler<Input, number>({
      fetchTask: (batch) => Promise.resolve(batch.map(({ input }) => input.id)),
    });
    const first = scheduler.schedule(task(1));
    const duplicate = scheduler.schedule(task(2, { cacheKey: "key-1" }));
    expect(duplicate).toBe(first);
  });

  it("does not share cancellation ownership for equal cache keys in different documents", async () => {
    const fetchTask = vi.fn(
      (batch: readonly ScheduledRequest<Input>[], signal: AbortSignal) =>
        new Promise<readonly number[]>((resolve, reject) => {
          if (batch[0]!.documentId === "document-2") {
            resolve(batch.map(({ input }) => input.id));
            return;
          }
          signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
        }),
    );
    const scheduler = new RequestScheduler<Input, number>({ fetchTask });
    const first = scheduler.schedule(task(1));
    const otherDocument = scheduler.schedule(
      task(2, { cacheKey: "key-1", documentId: "document-2" }),
    );
    expect(otherDocument).not.toBe(first);

    scheduler.cancelDocument("document-1");

    await expect(first).rejects.toMatchObject({ code: "REQUEST_CANCELLED" });
    await expect(otherDocument).resolves.toBe(2);
  });

  it("does not dispatch a single request that already exceeds a batch cap", async () => {
    const fetchTask = vi.fn<FetchTask<Input, number>>().mockResolvedValue([1]);
    const scheduler = new RequestScheduler<Input, number>({
      fetchTask,
      estimateTokens: ({ input }) => input.tokens,
    });

    await expect(scheduler.schedule(task(1, { sentenceCount: 7 }))).rejects.toMatchObject({
      code: "SENTENCE_TOO_LONG",
    });
    await expect(
      scheduler.schedule(task(2, { input: { id: 2, tokens: 4_001 } })),
    ).rejects.toMatchObject({ code: "SENTENCE_TOO_LONG" });
    expect(fetchTask).not.toHaveBeenCalled();
  });

  it("dispatches queued work in documented priority order", async () => {
    const seen: SchedulerPriority[] = [];
    const scheduler = new RequestScheduler<Input, number>({
      fetchTask: (batch) => {
        seen.push(...batch.map(({ priority }) => priority));
        return Promise.resolve(batch.map(({ input }) => input.id));
      },
    });
    scheduler.pause();
    const requests = [
      scheduler.schedule(task(1, { priority: "prefetch-core" })),
      scheduler.schedule(task(2, { priority: "visible-core" })),
      scheduler.schedule(task(3, { priority: "detail-click" })),
      scheduler.schedule(task(4, { priority: "user-retry" })),
    ];
    scheduler.resume();
    await Promise.all(requests);
    expect(seen).toEqual(["user-retry", "detail-click", "visible-core", "prefetch-core"]);
  });

  it("does not fill a high-priority batch with lower-priority work", async () => {
    const batches: SchedulerPriority[][] = [];
    const scheduler = new RequestScheduler<Input, number>({
      concurrency: 1,
      fetchTask: (batch) => {
        batches.push(batch.map(({ priority }) => priority));
        return Promise.resolve(batch.map(({ input }) => input.id));
      },
    });
    scheduler.pause();
    const results = [
      scheduler.schedule(task(1, { priority: "user-retry", sentenceCount: 5 })),
      scheduler.schedule(task(2, { priority: "visible-core", sentenceCount: 2 })),
      scheduler.schedule(task(3, { priority: "prefetch-core", sentenceCount: 1 })),
    ];
    scheduler.resume();
    await Promise.all(results);
    expect(batches[0]).toEqual(["user-retry"]);
    expect(batches[1]).toEqual(["visible-core"]);
  });

  it("uses Retry-After for 429 and exponential delays plus jitter for 5xx", async () => {
    const sleep = vi.fn<SchedulerSleep>().mockResolvedValue(undefined);
    const rateFetch = vi
      .fn<FetchTask<Input, number>>()
      .mockRejectedValueOnce(
        new ModelRequestError("RATE_LIMITED", "slow down", true, { retryAfterMs: 2_500 }),
      )
      .mockResolvedValueOnce([1]);
    const rateScheduler = new RequestScheduler<Input, number>({ fetchTask: rateFetch, sleep });
    await expect(rateScheduler.schedule(task(1))).resolves.toBe(1);
    expect(sleep).toHaveBeenCalledWith(2_500, expect.any(AbortSignal));

    sleep.mockClear();
    const serverFetch = vi
      .fn<FetchTask<Input, number>>()
      .mockRejectedValueOnce(
        new ModelRequestError("NETWORK_ERROR", "server", true, { status: 500 }),
      )
      .mockRejectedValueOnce(
        new ModelRequestError("NETWORK_ERROR", "server", true, { status: 503 }),
      )
      .mockResolvedValueOnce([2]);
    const serverScheduler = new RequestScheduler<Input, number>({
      fetchTask: serverFetch,
      sleep,
      jitter: () => 25,
    });
    await expect(serverScheduler.schedule(task(2))).resolves.toBe(2);
    expect(sleep.mock.calls.map(([milliseconds]) => milliseconds)).toEqual([525, 1_025]);
  });

  it("does not retry authentication failures", async () => {
    const fetchTask = vi
      .fn()
      .mockRejectedValue(new ModelRequestError("AUTH_FAILED", "bad key", false));
    const sleep = vi.fn().mockResolvedValue(undefined);
    const scheduler = new RequestScheduler<Input, number>({ fetchTask, sleep });
    await expect(scheduler.schedule(task(1))).rejects.toMatchObject({ code: "AUTH_FAILED" });
    expect(fetchTask).toHaveBeenCalledTimes(1);
    expect(sleep).not.toHaveBeenCalled();
  });

  it("cancels only matching queued and running document work", async () => {
    const fetchTask = vi.fn(
      (batch: readonly ScheduledRequest<Input>[], signal: AbortSignal) =>
        new Promise<readonly number[]>((resolve, reject) => {
          if (batch[0]!.documentId === "other-document") {
            resolve(batch.map(({ input }) => input.id));
            return;
          }
          signal.addEventListener(
            "abort",
            () => {
              const reason: unknown = signal.reason;
              reject(reason instanceof Error ? reason : new Error("Aborted"));
            },
            { once: true },
          );
        }),
    );
    const scheduler = new RequestScheduler<Input, number>({ fetchTask, concurrency: 1 });
    const running = scheduler.schedule(task(1));
    const queued = scheduler.schedule(task(2));
    const unrelated = scheduler.schedule(task(3, { documentId: "other-document" }));
    await vi.waitFor(() => expect(fetchTask).toHaveBeenCalledTimes(1));

    scheduler.cancelDocument("document-1");

    await expect(running).rejects.toMatchObject({ code: "REQUEST_CANCELLED" });
    await expect(queued).rejects.toMatchObject({ code: "REQUEST_CANCELLED" });
    await expect(unrelated).resolves.toBe(3);
  });

  it("honors cancellation that races an already-resolved running fetch", async () => {
    const scheduler = new RequestScheduler<Input, number>({
      fetchTask: () => Promise.resolve([1]),
    });
    const running = scheduler.schedule(task(1));
    scheduler.cancelDocument("document-1");
    await expect(running).rejects.toMatchObject({ code: "REQUEST_CANCELLED" });
  });

  it("runs prefetch-detail requests after every other priority", async () => {
    const order: string[] = [];
    const scheduler = new RequestScheduler<string, string>({
      concurrency: 1,
      fetchTask: async (batch) => {
        order.push(...batch.map(({ input }) => input));
        return batch.map(({ input }) => input);
      },
    });
    const request = (cacheKey: string, priority: SchedulerPriority) =>
      scheduler.schedule({
        cacheKey,
        documentId: "doc",
        priority,
        sentenceCount: 1,
        input: cacheKey,
      });

    scheduler.pause();
    const requests = [
      request("p-detail", "prefetch-detail"),
      request("p-core", "prefetch-core"),
      request("click", "detail-click"),
    ];
    scheduler.resume();
    await Promise.all(requests);
    expect(order.indexOf("p-detail")).toBeGreaterThan(order.indexOf("p-core"));
    expect(order.indexOf("p-core")).toBeGreaterThan(order.indexOf("click"));
  });
});
