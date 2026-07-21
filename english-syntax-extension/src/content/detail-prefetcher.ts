import type { CoreAnalysis } from "../shared/grammar";
import type { SentenceInput } from "../shared/protocol";

export interface PrefetchCounts {
  total: number;
  ready: number;
  failed: number;
}

export interface PrefetchItem {
  sentence: SentenceInput;
  core: CoreAnalysis;
}

/** send 的归一化结果:ok 带成分级计数;cancelled 回滚重排;failed 整句计失败。 */
export type PrefetchSendResult =
  { kind: "ok"; succeeded: number; failed: number } | { kind: "cancelled" } | { kind: "failed" };

export interface DetailPrefetcherOptions {
  send(item: PrefetchItem): Promise<PrefetchSendResult>;
  onChange(): void;
  /** 同时在飞的整句请求数;调度器侧还有自己的并发与优先级控制。 */
  concurrency?: number;
}

export class DetailPrefetcher {
  private readonly queue: PrefetchItem[] = [];
  private readonly trackedIds = new Set<string>();
  private readonly concurrency: number;
  private inFlight = 0;
  private paused = false;
  private total = 0;
  private ready = 0;
  private failed = 0;

  constructor(private readonly options: DetailPrefetcherOptions) {
    this.concurrency = options.concurrency ?? 2;
  }

  counts(): PrefetchCounts {
    return { total: this.total, ready: this.ready, failed: this.failed };
  }

  enqueue(sentence: SentenceInput, core: CoreAnalysis): void {
    if (this.trackedIds.has(sentence.sentenceId) || core.components.length === 0) return;
    this.trackedIds.add(sentence.sentenceId);
    this.queue.push({ sentence, core });
    this.total += core.components.length;
    this.options.onChange();
    this.pump();
  }

  /** 句子所在块失效(stale):只丢还在排队的;在飞的让它跑完,结果照常计数。 */
  discard(sentenceId: string): void {
    const index = this.queue.findIndex(({ sentence }) => sentence.sentenceId === sentenceId);
    if (index === -1) return;
    const [dropped] = this.queue.splice(index, 1);
    this.trackedIds.delete(sentenceId);
    this.total -= dropped!.core.components.length;
    this.options.onChange();
  }

  pause(): void {
    this.paused = true;
  }

  resume(): void {
    if (!this.paused) return;
    this.paused = false;
    this.pump();
  }

  private pump(): void {
    while (!this.paused && this.inFlight < this.concurrency && this.queue.length > 0) {
      const item = this.queue.shift()!;
      this.inFlight += 1;
      void this.run(item);
    }
  }

  private async run(item: PrefetchItem): Promise<void> {
    let result: PrefetchSendResult;
    try {
      result = await this.options.send(item);
    } catch {
      result = { kind: "failed" };
    }
    this.inFlight -= 1;
    if (result.kind === "cancelled") {
      // 回滚为待发:计数不动,恢复后重发。
      this.queue.unshift(item);
    } else if (result.kind === "failed") {
      this.failed += item.core.components.length;
      this.options.onChange();
    } else {
      this.ready += result.succeeded;
      this.failed += result.failed;
      this.options.onChange();
    }
    this.pump();
  }
}
