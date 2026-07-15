// @vitest-environment happy-dom

import { beforeEach, describe, expect, it, vi } from "vitest";
import { GrammarRole } from "../shared/grammar";
import type { CoreAnalysis, DetailAnalysis, Token } from "../shared/grammar";
import type { RequestMessage, ResponseMessage } from "../shared/protocol";
import type { CandidateBlock } from "./document-scanner";
import type {
  ControllerBlock,
  ControllerReplacement,
  RuntimeTransport,
  SentencePhase,
  SessionControllerOptions,
  ViewportPort,
} from "./session-controller";
import { SessionController } from "./session-controller";
import { ContentScriptRouter } from "./content-script";

class FakeLearningBlock implements ControllerBlock {
  expected: string[] = [];
  resolved = new Set<string>();
  cores: CoreAnalysis[] = [];
  details: DetailAnalysis[] = [];
  failures: Array<{ sentenceId: string; sentence: string; message: string }> = [];
  loading: string[] = [];

  setExpectedSentenceIds(ids: readonly string[]): void {
    this.expected = [...ids];
  }

  renderCore(_sentence: string, _tokens: readonly Token[], analysis: CoreAnalysis): void {
    this.cores.push(analysis);
    this.resolved.add(analysis.sentenceId);
  }

  renderFailure(sentenceId: string, sentence: string, message: string): void {
    this.failures.push({ sentenceId, sentence, message });
    this.resolved.add(sentenceId);
  }

  setDetailLoading(sentenceId: string): void {
    this.loading.push(sentenceId);
  }

  renderDetail(analysis: DetailAnalysis): void {
    this.details.push(analysis);
  }

  renderError(): void {}

  isReadyToReplace(): boolean {
    return this.expected.length > 0 && this.expected.every((id) => this.resolved.has(id));
  }
}

class FakeReplacement implements ControllerReplacement {
  shows = 0;
  partialShows = 0;
  restores = 0;

  show(): void {
    this.shows += 1;
  }

  showPartialFailure(
    _original: HTMLElement,
    block: ControllerBlock,
    failures: readonly { sentenceId: string; sentence: string; message: string }[],
  ): void {
    this.partialShows += 1;
    for (const failure of failures) {
      block.renderFailure(failure.sentenceId, failure.sentence, failure.message);
    }
  }

  restore(): void {
    this.restores += 1;
  }
}

class FakeViewport implements ViewportPort {
  observed: CandidateBlock[] = [];
  invalidated: string[] = [];
  disconnected = false;

  constructor(private readonly callback: (candidate: CandidateBlock) => void) {}

  observe(blocks: readonly CandidateBlock[]): void {
    this.observed.push(...blocks);
  }

  invalidate(blockId: string): void {
    this.invalidated.push(blockId);
  }

  disconnect(): void {
    this.disconnected = true;
  }

  emit(index = 0): void {
    this.callback(this.observed[index]!);
  }
}

class FakeTransport implements RuntimeTransport {
  sent: RequestMessage[] = [];
  cancelled: string[] = [];
  reconnects = 0;
  reconnectHandler?: () => void | Promise<void>;
  handler: (message: RequestMessage) => Promise<ResponseMessage>;
  private disconnectHandler?: () => void;

  constructor(handler?: (message: RequestMessage) => Promise<ResponseMessage>) {
    this.handler =
      handler ??
      ((message) =>
        Promise.resolve({
          version: 1,
          requestId: message.requestId,
          type: "CORE_RESULT",
          analyses:
            message.type === "ANALYZE_CORE"
              ? message.sentences.map((sentence) => core(sentence.sentenceId))
              : [],
        }));
  }

  send(message: RequestMessage): Promise<ResponseMessage> {
    this.sent.push(message);
    return this.handler(message);
  }

  cancelDocument(documentId: string): void {
    this.cancelled.push(documentId);
  }

  onDisconnect(handler: () => void): () => void {
    this.disconnectHandler = handler;
    return () => {
      this.disconnectHandler = undefined;
    };
  }

  reconnect(): void | Promise<void> {
    this.reconnects += 1;
    return this.reconnectHandler?.();
  }

  disconnect(): void {
    this.disconnectHandler?.();
  }
}

function core(sentenceId: string, profile = "profile-a"): CoreAnalysis {
  return {
    schemaVersion: 1,
    sentenceId,
    components: [{ startToken: 0, endToken: 1, role: GrammarRole.SUBJECT, translation: "译文" }],
    modelProfileId: profile,
  };
}

function detail(sentenceId: string): DetailAnalysis {
  return {
    sentenceId,
    focus: { startToken: 0, endToken: 1 },
    structures: [],
    grammarPoints: [],
    explanation: "detail",
    modelProfileId: "profile-a",
  };
}

function deferred<T>(): {
  promise: Promise<T>;
  resolve(value: T): void;
} {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

interface Harness {
  controller: SessionController;
  transport: FakeTransport;
  viewport: FakeViewport;
  learningBlocks: FakeLearningBlock[];
  replacements: FakeReplacement[];
  transitions: SentencePhase[];
}

function harness(
  text = "Readers understand complex sentences.",
  transport = new FakeTransport(),
  overrides: Partial<SessionControllerOptions> = {},
): Harness {
  document.body.innerHTML = `<main><p>${text}</p></main>`;
  const element = document.querySelector("p")!;
  const candidate = { id: "block-1", element, text };
  const learningBlocks: FakeLearningBlock[] = [];
  const replacements: FakeReplacement[] = [];
  const transitions: SentencePhase[] = [];
  let viewport!: FakeViewport;
  const options: SessionControllerOptions = {
    tabId: 9,
    document,
    transport,
    scan: () => [candidate],
    createSentenceId: ({ order }) => Promise.resolve(`sentence-${order + 1}`),
    viewportFactory: (callback) => (viewport = new FakeViewport(callback)),
    learningBlockFactory: () => {
      const block = new FakeLearningBlock();
      learningBlocks.push(block);
      return block;
    },
    replacementFactory: () => {
      const replacement = new FakeReplacement();
      replacements.push(replacement);
      return replacement;
    },
    onTransition: (_id, phase) => transitions.push(phase),
    ...overrides,
  };
  const controller = new SessionController(options);
  return { controller, transport, viewport, learningBlocks, replacements, transitions };
}

async function startAndEmit(subject: Harness): Promise<void> {
  await subject.controller.start();
  subject.viewport.emit();
  await vi.waitFor(() => expect(subject.controller.status.ready).toBe(1));
}

describe("SessionController", () => {
  beforeEach(() => {
    document.body.replaceChildren();
    vi.restoreAllMocks();
  });

  it("moves a visible sentence through the exact analysis phases and replaces only after readiness", async () => {
    const subject = harness();

    await startAndEmit(subject);

    expect(subject.transitions).toEqual([
      "discovered",
      "cache-check",
      "queued",
      "requesting",
      "validating",
      "ready",
    ]);
    expect(subject.learningBlocks[0]!.expected).toEqual(["sentence-1"]);
    expect(subject.replacements[0]!.shows).toBe(1);
  });

  it("retains successful sentences and renders every missing result with original text", async () => {
    const transport = new FakeTransport((message) =>
      Promise.resolve({
        version: 1,
        requestId: message.requestId,
        type: "CORE_RESULT",
        analyses: message.type === "ANALYZE_CORE" ? [core(message.sentences[0]!.sentenceId)] : [],
      }),
    );
    const subject = harness("Readers learn. Writers practice daily.", transport);

    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.failed).toBe(1));

    expect(subject.learningBlocks[0]!.cores).toHaveLength(1);
    expect(subject.learningBlocks[0]!.failures[0]).toMatchObject({
      sentenceId: "sentence-2",
      sentence: "Writers practice daily.",
    });
    expect(subject.replacements[0]!.partialShows).toBe(1);
    expect(subject.replacements[0]!.shows).toBe(0);
  });

  it("pauses new visible work, resumes it, and stop cancels and restores", async () => {
    const subject = harness();
    await subject.controller.start();
    subject.controller.pause();
    subject.viewport.emit();
    await Promise.resolve();
    expect(subject.transport.sent).toHaveLength(0);

    subject.controller.resume();
    await vi.waitFor(() => expect(subject.controller.status.ready).toBe(1));
    subject.controller.stop();

    expect(subject.controller.status.state).toBe("stopped");
    expect(subject.transport.cancelled).toEqual([subject.controller.documentId]);
    expect(subject.replacements[0]!.restores).toBeGreaterThan(0);
    expect(subject.viewport.disconnected).toBe(true);
  });

  it("rejects a response from an invalidated operation version", async () => {
    const pending = deferred<ResponseMessage>();
    const subject = harness(
      undefined,
      new FakeTransport((message) =>
        pending.promise.then((response) => ({ ...response, requestId: message.requestId })),
      ),
    );
    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.transport.sent).toHaveLength(1));

    subject.controller.invalidateBlock("block-1");
    pending.resolve({
      version: 1,
      requestId: "old",
      type: "CORE_RESULT",
      analyses: [core("sentence-1")],
    });
    await Promise.resolve();
    await Promise.resolve();

    expect(subject.learningBlocks[0]!.cores).toHaveLength(0);
    expect(subject.replacements[0]!.restores).toBeGreaterThan(0);
  });

  it("batches character-data mutations for 100 ms, restores stale output, and rescans only the changed block", async () => {
    vi.useFakeTimers();
    const scan = vi.fn((root: ParentNode) => {
      const element = root === document ? document.querySelector("p") : root;
      return element instanceof Element
        ? [{ id: "block-1", element, text: element.textContent ?? "" }]
        : [];
    });
    const subject = harness(undefined, undefined, { scan });
    const element = document.querySelector("p")!;
    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.ready).toBe(1));

    element.firstChild!.textContent = "Readers now understand changing sentences.";
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(99);
    expect(subject.replacements[0]!.restores).toBe(0);
    await vi.advanceTimersByTimeAsync(1);

    expect(subject.replacements[0]!.restores).toBeGreaterThan(0);
    expect(scan).toHaveBeenCalledWith(element);
    vi.useRealTimers();
  });

  it("renders SENTENCE_TOO_LONG locally and never sends it", async () => {
    const subject = harness(`Readers ${"understand ".repeat(210)}sentences.`);
    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.failed).toBe(1));

    expect(subject.transport.sent).toHaveLength(0);
    expect(subject.learningBlocks[0]!.failures[0]!.message).toContain("SENTENCE_TOO_LONG");
    expect(subject.replacements[0]!.partialShows).toBe(1);
  });

  it("yields after eight milliseconds of synchronous discovery work", async () => {
    let time = 0;
    const yieldNow = vi.fn(() => Promise.resolve());
    const subject = harness(undefined, undefined, {
      now: () => (time += 9),
      yieldNow,
    });

    await subject.controller.start();

    expect(yieldNow).toHaveBeenCalled();
  });

  it("prioritizes detail requests and sends correction feedback without rewriting existing profile results", async () => {
    const subject = harness();
    await startAndEmit(subject);
    const block = subject.learningBlocks[0]!;

    subject.controller.switchProfile("profile-b");
    await subject.controller.requestDetail({
      sentenceId: "sentence-1",
      focus: { startToken: 0, endToken: 1 },
    });
    await subject.controller.submitCorrection("sentence-1", "The subject should include Readers.");

    expect(block.cores[0]!.modelProfileId).toBe("profile-a");
    expect(subject.transport.sent.map((message) => message.type)).toEqual([
      "ANALYZE_CORE",
      "ANALYZE_DETAIL",
      "REANALYZE_WITH_FEEDBACK",
    ]);
    expect(subject.transport.sent[2]).toMatchObject({
      feedback: "The subject should include Readers.",
    });
  });

  it("renders a matching detail response", async () => {
    const subject = harness(
      undefined,
      new FakeTransport((message) =>
        Promise.resolve(
          message.type === "ANALYZE_DETAIL"
            ? {
                version: 1,
                requestId: message.requestId,
                type: "DETAIL_RESULT",
                analysis: detail(message.sentence.sentenceId),
              }
            : {
                version: 1,
                requestId: message.requestId,
                type: "CORE_RESULT",
                analyses:
                  message.type === "ANALYZE_CORE"
                    ? message.sentences.map((sentence) => core(sentence.sentenceId))
                    : [],
              },
        ),
      ),
    );
    await startAndEmit(subject);

    await subject.controller.requestDetail({
      sentenceId: "sentence-1",
      focus: { startToken: 0, endToken: 1 },
    });

    expect(subject.learningBlocks[0]!.details).toEqual([detail("sentence-1")]);
  });

  it("records context targets only while active and supports first-use selection text", async () => {
    const subject = harness();
    expect(await subject.controller.parseContextBlock()).toEqual({
      code: "UNSAFE_CONTENT_BLOCK",
      message: "请先启动学习模式，或选中文字后解析",
      retryable: false,
    });
    await subject.controller.start();
    document.querySelector("p")!.dispatchEvent(new MouseEvent("contextmenu", { bubbles: true }));

    await subject.controller.parseContextBlock();
    await subject.controller.parseSelection("Readers select this sentence.");

    await vi.waitFor(() => expect(subject.transport.sent.length).toBeGreaterThanOrEqual(1));
  });

  it("reconnects immediately and resubmits only unfinished sentences", async () => {
    vi.useFakeTimers();
    const pending = deferred<ResponseMessage>();
    const transport = new FakeTransport((message) =>
      pending.promise.then(() => ({
        version: 1,
        requestId: message.requestId,
        type: "CORE_RESULT",
        analyses:
          message.type === "ANALYZE_CORE"
            ? message.sentences.map((sentence) => core(sentence.sentenceId))
            : [],
      })),
    );
    const subject = harness(undefined, transport);
    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(transport.sent).toHaveLength(1));

    transport.disconnect();
    await vi.runOnlyPendingTimersAsync();

    expect(transport.reconnects).toBeGreaterThanOrEqual(1);
    expect(transport.sent).toHaveLength(2);
    vi.useRealTimers();
  });

  it("retries worker reconnection after 250, 500, and 1,000 milliseconds", async () => {
    const pending = deferred<ResponseMessage>();
    const transport = new FakeTransport(() => pending.promise);
    const reconnect = vi
      .fn<() => Promise<void>>()
      .mockRejectedValueOnce(new Error("starting"))
      .mockRejectedValueOnce(new Error("starting"))
      .mockRejectedValueOnce(new Error("starting"))
      .mockResolvedValueOnce(undefined);
    transport.reconnectHandler = reconnect;
    const delays: number[] = [];
    const subject = harness(undefined, transport, {
      setTimeout: (callback, delay) => {
        delays.push(delay);
        callback();
        return 1 as unknown as ReturnType<typeof setTimeout>;
      },
    });
    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(transport.sent).toHaveLength(1));

    transport.disconnect();
    await vi.waitFor(() => expect(reconnect).toHaveBeenCalledTimes(4));

    expect(delays).toEqual([250, 500, 1_000]);
  });
});

describe("ContentScriptRouter", () => {
  it("rejects malformed inbound messages and reuses one controller per document ID", async () => {
    const start = vi.fn(() => Promise.resolve());
    const controller = {
      documentId: "controller-document",
      status: { state: "running" as const, discovered: 0, queued: 0, ready: 0, failed: 0 },
      start,
      pause: vi.fn(),
      resume: vi.fn(),
      stop: vi.fn(),
      parseSelection: vi.fn(() => Promise.resolve(undefined)),
      parseContextBlock: vi.fn(() => Promise.resolve(undefined)),
      switchProfile: vi.fn(),
    };
    const factory = vi.fn(() => controller);
    const router = new ContentScriptRouter({ controllerFactory: factory });

    const malformed = await router.route({ type: "START_SESSION" });
    const valid = {
      version: 1,
      requestId: "start-1",
      type: "START_SESSION",
      tabId: 3,
      documentId: "document-1",
    } as const;
    const first = await router.route(valid);
    const second = await router.route({ ...valid, requestId: "start-2" });

    expect(malformed).toMatchObject({ type: "ERROR" });
    expect(first).toMatchObject({ type: "SESSION_STATUS" });
    expect(second).toMatchObject({ type: "SESSION_STATUS" });
    expect(factory).toHaveBeenCalledOnce();
    expect(start).toHaveBeenCalledTimes(2);
  });
});
