import type { ExtensionError, ExtensionErrorCode } from "../shared/errors";

export type SchedulerPriority = "user-retry" | "detail-click" | "visible-core" | "prefetch-core";

export interface ScheduledRequest<Input> {
  cacheKey: string;
  documentId: string;
  priority: SchedulerPriority;
  sentenceCount: number;
  input: Input;
  /** Requests with distinct batch keys are never combined in one fetchTask call. */
  batchKey?: string;
}

export type FetchTask<Input, Output> = (
  batch: readonly ScheduledRequest<Input>[],
  signal: AbortSignal,
) => Promise<readonly Output[]>;

export type SchedulerSleep = (milliseconds: number, signal: AbortSignal) => Promise<void>;

export interface RequestSchedulerOptions<Input, Output> {
  fetchTask: FetchTask<Input, Output>;
  sleep?: SchedulerSleep;
  jitter?: () => number;
  estimateTokens?: (request: ScheduledRequest<Input>) => number;
  concurrency?: number;
  maxBatchSentences?: number;
  tokenBudget?: number;
  maxRetries?: number;
}

export class SchedulerRequestError extends Error implements ExtensionError {
  constructor(
    public readonly code: ExtensionErrorCode,
    message: string,
    public readonly retryable: boolean,
    public readonly details: Record<string, string | number | boolean> = {},
  ) {
    super(message);
    this.name = "SchedulerRequestError";
  }
}

interface QueueItem<Input, Output> {
  request: ScheduledRequest<Input>;
  sequence: number;
  promise: Promise<Output>;
  resolve: (value: Output) => void;
  reject: (reason: unknown) => void;
  settled: boolean;
}

interface ActiveBatch<Input, Output> {
  controller: AbortController;
  items: QueueItem<Input, Output>[];
}

const PRIORITY_RANK: Readonly<Record<SchedulerPriority, number>> = {
  "user-retry": 0,
  "detail-click": 1,
  "visible-core": 2,
  "prefetch-core": 3,
};

function cancellationError(): SchedulerRequestError {
  return new SchedulerRequestError("REQUEST_CANCELLED", "Scheduled request was cancelled", false);
}

function abortReason(signal: AbortSignal): Error {
  const reason: unknown = signal.reason;
  return reason instanceof Error ? reason : cancellationError();
}

function abortableSleep(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(abortReason(signal));
      return;
    }
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, milliseconds);
    const onAbort = () => {
      clearTimeout(timer);
      reject(abortReason(signal));
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

function errorShape(error: unknown): Partial<ExtensionError> | undefined {
  if (typeof error !== "object" || error === null) return undefined;
  return error;
}

function dedupeKey<Input>(request: ScheduledRequest<Input>): string {
  return `${request.documentId}\u0000${request.cacheKey}`;
}

export class RequestScheduler<Input, Output> {
  private readonly fetchTask: FetchTask<Input, Output>;
  private readonly sleep: SchedulerSleep;
  private readonly jitter: () => number;
  private readonly estimateTokens: (request: ScheduledRequest<Input>) => number;
  private readonly concurrency: number;
  private readonly maxBatchSentences: number;
  private readonly tokenBudget: number;
  private readonly maxRetries: number;
  private readonly queue: Array<QueueItem<Input, Output>> = [];
  private readonly inFlightByCacheKey = new Map<string, QueueItem<Input, Output>>();
  private readonly activeBatches = new Set<ActiveBatch<Input, Output>>();
  private nextSequence = 0;
  private paused = false;

  constructor(options: RequestSchedulerOptions<Input, Output>) {
    this.fetchTask = options.fetchTask;
    this.sleep = options.sleep ?? abortableSleep;
    this.jitter = options.jitter ?? (() => Math.floor(Math.random() * 101));
    this.estimateTokens = options.estimateTokens ?? (() => 0);
    this.concurrency = options.concurrency ?? 2;
    this.maxBatchSentences = options.maxBatchSentences ?? 6;
    this.tokenBudget = options.tokenBudget ?? 4_000;
    this.maxRetries = options.maxRetries ?? 2;

    for (const [name, value] of [
      ["concurrency", this.concurrency],
      ["maxBatchSentences", this.maxBatchSentences],
      ["tokenBudget", this.tokenBudget],
    ] as const) {
      if (!Number.isSafeInteger(value) || value <= 0) {
        throw new Error(`Scheduler ${name} must be a positive safe integer`);
      }
    }
    if (!Number.isSafeInteger(this.maxRetries) || this.maxRetries < 0) {
      throw new Error("Scheduler maxRetries must be a non-negative safe integer");
    }
  }

  schedule(request: ScheduledRequest<Input>): Promise<Output> {
    const duplicate = this.inFlightByCacheKey.get(dedupeKey(request));
    if (duplicate !== undefined) return duplicate.promise;
    if (!Number.isSafeInteger(request.sentenceCount) || request.sentenceCount <= 0) {
      return Promise.reject(new Error("Scheduled sentenceCount must be a positive safe integer"));
    }
    if (
      request.sentenceCount > this.maxBatchSentences ||
      this.estimate(request) > this.tokenBudget
    ) {
      return Promise.reject(
        new SchedulerRequestError(
          "SENTENCE_TOO_LONG",
          "Scheduled request exceeds the sentence or token batch limit",
          false,
        ),
      );
    }

    let resolve!: (value: Output) => void;
    let reject!: (reason: unknown) => void;
    const promise = new Promise<Output>((resolvePromise, rejectPromise) => {
      resolve = resolvePromise;
      reject = rejectPromise;
    });
    const item: QueueItem<Input, Output> = {
      request,
      sequence: this.nextSequence++,
      promise,
      resolve,
      reject,
      settled: false,
    };
    this.queue.push(item);
    this.inFlightByCacheKey.set(dedupeKey(request), item);
    this.pump();
    return promise;
  }

  pause(): void {
    this.paused = true;
  }

  resume(): void {
    this.paused = false;
    this.pump();
  }

  cancelDocument(documentId: string): void {
    const error = cancellationError();
    for (let index = this.queue.length - 1; index >= 0; index -= 1) {
      const item = this.queue[index]!;
      if (item.request.documentId === documentId) {
        this.queue.splice(index, 1);
        this.rejectItem(item, error);
      }
    }
    for (const batch of this.activeBatches) {
      if (batch.items[0]?.request.documentId === documentId) {
        batch.controller.abort(error);
      }
    }
    this.pump();
  }

  private pump(): void {
    if (this.paused) return;
    while (this.activeBatches.size < this.concurrency && this.queue.length > 0) {
      const items = this.takeBatch();
      const activeBatch: ActiveBatch<Input, Output> = {
        items,
        controller: new AbortController(),
      };
      this.activeBatches.add(activeBatch);
      void this.runBatch(activeBatch);
    }
  }

  private takeBatch(): QueueItem<Input, Output>[] {
    this.queue.sort(
      (left, right) =>
        PRIORITY_RANK[left.request.priority] - PRIORITY_RANK[right.request.priority] ||
        left.sequence - right.sequence,
    );
    const first = this.queue.shift()!;
    const selected = [first];
    let sentenceCount = first.request.sentenceCount;
    let tokens = this.estimate(first.request);

    for (let index = 0; index < this.queue.length;) {
      const candidate = this.queue[index]!;
      const sameScope =
        candidate.request.documentId === first.request.documentId &&
        candidate.request.batchKey === first.request.batchKey &&
        candidate.request.priority === first.request.priority;
      const candidateTokens = this.estimate(candidate.request);
      const withinSentences =
        sentenceCount + candidate.request.sentenceCount <= this.maxBatchSentences;
      const withinTokens = tokens + candidateTokens <= this.tokenBudget;
      if (sameScope && withinSentences && withinTokens) {
        selected.push(candidate);
        this.queue.splice(index, 1);
        sentenceCount += candidate.request.sentenceCount;
        tokens += candidateTokens;
      } else {
        index += 1;
      }
    }
    return selected;
  }

  private estimate(request: ScheduledRequest<Input>): number {
    const estimate = this.estimateTokens(request);
    if (!Number.isFinite(estimate) || estimate < 0) {
      throw new Error("Scheduler token estimator must return a non-negative finite number");
    }
    return estimate;
  }

  private async runBatch(batch: ActiveBatch<Input, Output>): Promise<void> {
    try {
      const results = await this.fetchWithRetry(batch);
      if (results.length !== batch.items.length) {
        throw new SchedulerRequestError(
          "INVALID_MODEL_OUTPUT",
          `Batch returned ${results.length} results for ${batch.items.length} requests`,
          false,
        );
      }
      results.forEach((result, index) => this.resolveItem(batch.items[index]!, result));
    } catch (error) {
      const reason: unknown = batch.controller.signal.aborted
        ? abortReason(batch.controller.signal)
        : error;
      batch.items.forEach((item) => this.rejectItem(item, reason));
    } finally {
      this.activeBatches.delete(batch);
      this.pump();
    }
  }

  private async fetchWithRetry(batch: ActiveBatch<Input, Output>): Promise<readonly Output[]> {
    const requests = batch.items.map(({ request }) => request);
    for (let attempt = 0; ; attempt += 1) {
      try {
        const results = await this.fetchTask(requests, batch.controller.signal);
        if (batch.controller.signal.aborted) throw abortReason(batch.controller.signal);
        return results;
      } catch (error) {
        if (batch.controller.signal.aborted) throw abortReason(batch.controller.signal);
        const shape = errorShape(error);
        if (shape?.retryable !== true || attempt >= this.maxRetries) throw error;
        const retryAfter =
          shape.code === "RATE_LIMITED" && typeof shape.details?.retryAfterMs === "number"
            ? shape.details.retryAfterMs
            : undefined;
        const delay = retryAfter ?? 500 * 2 ** attempt + Math.max(0, this.jitter());
        await this.sleep(delay, batch.controller.signal);
      }
    }
  }

  private resolveItem(item: QueueItem<Input, Output>, value: Output): void {
    if (item.settled) return;
    item.settled = true;
    this.inFlightByCacheKey.delete(dedupeKey(item.request));
    item.resolve(value);
  }

  private rejectItem(item: QueueItem<Input, Output>, reason: unknown): void {
    if (item.settled) return;
    item.settled = true;
    this.inFlightByCacheKey.delete(dedupeKey(item.request));
    item.reject(reason);
  }
}
