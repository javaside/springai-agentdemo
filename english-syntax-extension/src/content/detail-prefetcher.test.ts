import { describe, expect, it, vi } from "vitest";
import type { CoreAnalysis } from "../shared/grammar";
import type { SentenceInput } from "../shared/protocol";
import { DetailPrefetcher, type PrefetchSendResult } from "./detail-prefetcher";

function sentence(id: string): SentenceInput {
  return { sentenceId: id, text: `${id} text`, tokens: [] };
}

function core(id: string, componentCount: number): CoreAnalysis {
  return {
    schemaVersion: 1,
    sentenceId: id,
    modelProfileId: "p",
    components: Array.from({ length: componentCount }, (_, index) => ({
      startToken: index,
      endToken: index,
      role: "SUBJECT",
      translation: "x",
    })),
  } as CoreAnalysis;
}

type Deferred = { resolve: (result: PrefetchSendResult) => void };

function harness(concurrency?: number) {
  const pending: Deferred[] = [];
  const sent: string[] = [];
  const onChange = vi.fn();
  const prefetcher = new DetailPrefetcher({
    concurrency,
    onChange,
    send: (item) => {
      sent.push(item.sentence.sentenceId);
      return new Promise<PrefetchSendResult>((resolve) => pending.push({ resolve }));
    },
  });
  const settle = async (result: PrefetchSendResult) => {
    pending.shift()!.resolve(result);
    // 两次微任务:排空 run() 里 await send 这一跳,让结果处理与后续 pump 都跑完。
    await Promise.resolve();
    await Promise.resolve();
  };
  return { prefetcher, pending, sent, settle, onChange };
}

describe("DetailPrefetcher", () => {
  it("counts totals on enqueue, sends with bounded concurrency, and accumulates results", async () => {
    const { prefetcher, sent, settle } = harness(1);
    prefetcher.enqueue(sentence("s1"), core("s1", 3));
    prefetcher.enqueue(sentence("s2"), core("s2", 2));

    expect(prefetcher.counts()).toEqual({ total: 5, ready: 0, failed: 0 });
    expect(sent).toEqual(["s1"]); // concurrency 1: second sentence waits

    await settle({ kind: "ok", succeeded: 2, failed: 1 });
    expect(prefetcher.counts()).toEqual({ total: 5, ready: 2, failed: 1 });
    expect(sent).toEqual(["s1", "s2"]);
  });

  it("ignores duplicate enqueues for the same sentence", () => {
    const { prefetcher } = harness();
    prefetcher.enqueue(sentence("s1"), core("s1", 3));
    prefetcher.enqueue(sentence("s1"), core("s1", 3));
    expect(prefetcher.counts().total).toBe(3);
  });

  it("re-queues a cancelled sentence and resumes it after resume()", async () => {
    const { prefetcher, sent, settle } = harness(1);
    prefetcher.enqueue(sentence("s1"), core("s1", 3));
    prefetcher.pause();
    await settle({ kind: "cancelled" });
    expect(prefetcher.counts()).toEqual({ total: 3, ready: 0, failed: 0 });
    expect(sent).toEqual(["s1"]); // no resend while paused

    prefetcher.resume();
    expect(sent).toEqual(["s1", "s1"]);
  });

  it("does not resend a cancelled sentence until a new pump trigger", async () => {
    const { prefetcher, sent, settle } = harness(1);
    prefetcher.enqueue(sentence("s1"), core("s1", 3));
    await settle({ kind: "cancelled" }); // 未 pause:如 stop 触发的 cancelDocument
    expect(prefetcher.counts()).toEqual({ total: 3, ready: 0, failed: 0 });
    expect(sent).toEqual(["s1"]); // no immediate resend

    prefetcher.enqueue(sentence("s2"), core("s2", 2)); // new pump trigger
    expect(sent).toEqual(["s1", "s1"]); // cancelled item sits at the queue front
  });

  it("counts the whole sentence as failed on a failed send", async () => {
    const { prefetcher, settle } = harness(1);
    prefetcher.enqueue(sentence("s1"), core("s1", 4));
    await settle({ kind: "failed" });
    expect(prefetcher.counts()).toEqual({ total: 4, ready: 0, failed: 4 });
  });

  it("discard() drops a queued sentence and its share of the total", () => {
    const { prefetcher, sent } = harness(1);
    prefetcher.enqueue(sentence("s1"), core("s1", 3)); // in flight
    prefetcher.enqueue(sentence("s2"), core("s2", 2)); // queued
    prefetcher.discard("s2");
    expect(prefetcher.counts().total).toBe(3);
    expect(sent).toEqual(["s1"]);
    prefetcher.enqueue(sentence("s2"), core("s2", 2)); // re-ready can re-enqueue
    expect(prefetcher.counts().total).toBe(5);
  });

  it("notifies onChange on every count movement", async () => {
    const { prefetcher, settle, onChange } = harness(1);
    prefetcher.enqueue(sentence("s1"), core("s1", 1));
    const calls = onChange.mock.calls.length;
    await settle({ kind: "ok", succeeded: 1, failed: 0 });
    expect(onChange.mock.calls.length).toBeGreaterThan(calls);
  });
});
