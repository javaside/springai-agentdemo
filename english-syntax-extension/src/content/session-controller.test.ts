// @vitest-environment happy-dom

import { beforeEach, describe, expect, it, vi } from "vitest";
import { GrammarRole } from "../shared/grammar";
import type { CoreAnalysis, DetailAnalysis, Token, TokenRange } from "../shared/grammar";
import type { RequestMessage, ResponseMessage, SessionStatus } from "../shared/protocol";
import { isSessionComplete } from "../shared/protocol";
import type { CandidateBlock } from "./document-scanner";
import { scanDocument } from "./document-scanner";
import type {
  ControllerBlock,
  ControllerReplacement,
  RuntimeTransport,
  SentencePhase,
  SessionControllerOptions,
  ViewportPort,
} from "./session-controller";
import { SessionController } from "./session-controller";
import { ChromeRuntimeTransport, ContentScriptRouter, isRuntimeResponse } from "./content-script";

class FakeLearningBlock implements ControllerBlock {
  expected: string[] = [];
  resolved = new Set<string>();
  cores: CoreAnalysis[] = [];
  details: DetailAnalysis[] = [];
  failures: Array<{ sentenceId: string; sentence: string; message: string }> = [];
  skips: Array<{ sentenceId: string; sentence: string }> = [];
  errors: Array<{ sentenceId: string; message: string }> = [];
  loading: string[] = [];
  closedDetails = 0;

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

  renderSkipped(sentenceId: string, sentence: string): void {
    this.skips.push({ sentenceId, sentence });
    this.resolved.add(sentenceId);
  }

  setDetailLoading(sentenceId: string): void {
    this.loading.push(sentenceId);
  }

  closeDetails(): void {
    this.closedDetails += 1;
  }

  renderDetail(analysis: DetailAnalysis): void {
    this.details.push(analysis);
  }

  renderError(sentenceId: string, _focus: TokenRange, message: string): void {
    this.errors.push({ sentenceId, message });
  }

  isReadyToReplace(): boolean {
    return this.expected.length > 0 && this.expected.every((id) => this.resolved.has(id));
  }
}

class FakeReplacement implements ControllerReplacement {
  shows = 0;
  partialShows = 0;
  restores = 0;
  originals: HTMLElement[] = [];
  readonly displayed = document.createElement("section");

  show(original: HTMLElement): void {
    this.shows += 1;
    this.originals.push(original);
  }

  showPartialFailure(
    _original: HTMLElement,
    block: ControllerBlock,
    failures: readonly { sentenceId: string; sentence: string; message: string }[],
  ): void {
    this.partialShows += 1;
    this.originals.push(_original);
    for (const failure of failures) {
      block.renderFailure(failure.sentenceId, failure.sentence, failure.message);
    }
  }

  restore(): void {
    this.restores += 1;
  }

  currentElement(): Element {
    return this.displayed;
  }
}

class FakeViewport implements ViewportPort {
  observed: CandidateBlock[] = [];
  invalidated: string[] = [];
  disconnected = false;
  checked: Element[] = [];

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

  visibleBlockIds(): string[] {
    return this.observed.slice(0, 1).map(({ id }) => id);
  }

  isVisible(element: Element): boolean {
    this.checked.push(element);
    return true;
  }

  emit(index = 0): void {
    this.callback(this.observed[index]!);
  }
}

class FakeTransport implements RuntimeTransport {
  sent: RequestMessage[] = [];
  cancelled: string[] = [];
  reconnects = 0;
  disposals = 0;
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

  dispose(): void {
    this.disposals += 1;
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

  it("renders hits, keeps misses as plain skipped text, and reports skipped in status", async () => {
    const transport = new FakeTransport((message) =>
      Promise.resolve({
        version: 1,
        requestId: message.requestId,
        type: "CORE_RESULT",
        analyses: message.type === "ANALYZE_CORE" ? [core(message.sentences[0]!.sentenceId)] : [],
        cacheOnly: true,
      }),
    );
    const subject = harness("Readers learn. Writers practice daily.", transport);

    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.skipped).toBe(1));

    const block = subject.learningBlocks[0]!;
    expect(block.cores).toHaveLength(1);
    expect(block.skips).toEqual([
      { sentenceId: "sentence-2", sentence: "Writers practice daily." },
    ]);
    expect(block.failures).toHaveLength(0);
    const status = subject.controller.status;
    expect(status.failed).toBe(0);
    expect(isSessionComplete(status)).toBe(true);
    expect(subject.replacements[0]!.shows).toBe(1);
    expect(subject.replacements[0]!.partialShows).toBe(0);
  });

  it("does not replace a block whose sentences are all cache misses", async () => {
    const transport = new FakeTransport((message) =>
      Promise.resolve({
        version: 1,
        requestId: message.requestId,
        type: "CORE_RESULT",
        analyses: [],
        cacheOnly: true,
      }),
    );
    const subject = harness("Readers learn. Writers practice daily.", transport);

    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.skipped).toBe(2));

    expect(subject.replacements[0]!.shows).toBe(0);
    expect(subject.replacements[0]!.partialShows).toBe(0);
    expect(subject.controller.status.failed).toBe(0);
    expect(subject.learningBlocks[0]!.skips).toHaveLength(2);
  });

  it("does not resend analysis for a block whose sentences are all skipped", async () => {
    const transport = new FakeTransport((message) =>
      Promise.resolve({
        version: 1,
        requestId: message.requestId,
        type: "CORE_RESULT",
        analyses: [],
        cacheOnly: true,
      }),
    );
    const subject = harness("Readers learn. Writers practice daily.", transport);
    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.skipped).toBe(2));
    expect(subject.transport.sent).toHaveLength(1);

    subject.viewport.emit();
    await Promise.resolve();

    expect(subject.transport.sent).toHaveLength(1);
  });

  it("converts a retried failed sentence to skipped in a cache-only session", async () => {
    const transport = new FakeTransport((message) =>
      Promise.resolve({
        version: 1,
        requestId: message.requestId,
        type: "CORE_RESULT",
        analyses: [],
        cacheOnly: true,
      }),
    );
    const subject = harness(`Readers ${"understand ".repeat(210)}sentences.`, transport);
    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.failed).toBe(1));
    expect(subject.replacements[0]!.partialShows).toBe(1);

    document.dispatchEvent(
      new CustomEvent("syntax-reanalyze-request", {
        detail: { sentenceId: "sentence-1", focus: { startToken: 0, endToken: 0 } },
      }),
    );

    await vi.waitFor(() => expect(subject.controller.status.skipped).toBe(1));
    expect(subject.controller.status.failed).toBe(0);
    expect(subject.learningBlocks[0]!.skips.map(({ sentenceId }) => sentenceId)).toEqual([
      "sentence-1",
    ]);
    // 重试未命中不再触发失败替换；初始 TOO_LONG 的那一次保持不变。
    expect(subject.replacements[0]!.partialShows).toBe(1);
  });

  it("still fails missing sentences when the response is not cacheOnly", async () => {
    const transport = new FakeTransport((message) =>
      Promise.resolve({
        version: 1,
        requestId: message.requestId,
        type: "CORE_RESULT",
        analyses: [],
      }),
    );
    const subject = harness(undefined, transport);

    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.failed).toBe(1));

    expect(subject.learningBlocks[0]!.skips).toHaveLength(0);
    expect(subject.controller.status.skipped).toBe(0);
    expect(subject.learningBlocks[0]!.failures).toHaveLength(1);
    expect(subject.replacements[0]!.partialShows).toBe(1);
  });

  it("renders the NO_CACHE detail message without the code prefix", async () => {
    const subject = harness(
      undefined,
      new FakeTransport((message) =>
        Promise.resolve(
          message.type === "ANALYZE_DETAIL"
            ? {
                version: 1,
                requestId: message.requestId,
                type: "ERROR",
                error: {
                  code: "NO_CACHE",
                  message: "该成分暂无缓存详解，配置模型后可获取",
                  retryable: false,
                },
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

    expect(subject.learningBlocks[0]!.errors).toEqual([
      { sentenceId: "sentence-1", message: "该成分暂无缓存详解，配置模型后可获取" },
    ]);
  });

  it("keeps the code prefix for detail errors other than NO_CACHE", async () => {
    const subject = harness(
      undefined,
      new FakeTransport((message) =>
        Promise.resolve(
          message.type === "ANALYZE_DETAIL"
            ? {
                version: 1,
                requestId: message.requestId,
                type: "ERROR",
                error: { code: "NETWORK_ERROR", message: "网络请求失败", retryable: true },
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

    expect(subject.learningBlocks[0]!.errors).toEqual([
      { sentenceId: "sentence-1", message: "NETWORK_ERROR：网络请求失败" },
    ]);
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
    expect(subject.transport.disposals).toBe(1);
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

  it("restores and requeues only blocks currently reported visible", async () => {
    const subject = harness();
    await startAndEmit(subject);
    expect(subject.transport.sent).toHaveLength(1);

    subject.controller.reanalyzeVisible();

    await vi.waitFor(() => expect(subject.transport.sent).toHaveLength(2));
    const [initial, reanalyzed] = subject.transport.sent;
    expect(initial).not.toHaveProperty("bypassCache");
    expect(reanalyzed).toMatchObject({ type: "ANALYZE_CORE", bypassCache: true });
    expect(subject.viewport.checked).toContain(subject.replacements[0]!.displayed);
    expect(subject.replacements[0]!.restores).toBeGreaterThan(0);
    expect(subject.viewport.invalidated).toContain("block-1");
    await vi.waitFor(() => expect(subject.controller.status.ready).toBe(1));
  });

  it("batches character-data mutations for 100 ms, restores stale output, and rescans only the changed block", async () => {
    vi.useFakeTimers();
    const scan = vi.fn((root: ParentNode) => {
      const element = root instanceof Element && root.matches("p") ? root : root.querySelector("p");
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
    expect(scan).toHaveBeenCalledTimes(2);
    expect(subject.viewport.observed.at(-1)!.element).toBe(element);
    vi.useRealTimers();
  });

  it("re-discovers a changed block through the real document scanner integration", async () => {
    vi.useFakeTimers();
    document.body.innerHTML =
      "<main><p>Readers understand complex sentences in changing documents.</p></main>";
    let viewport!: FakeViewport;
    const controller = new SessionController({
      tabId: 9,
      document,
      transport: new FakeTransport(),
      scan: scanDocument,
      createSentenceId: ({ order }) => Promise.resolve(`real-sentence-${order + 1}`),
      viewportFactory: (callback) => (viewport = new FakeViewport(callback)),
      learningBlockFactory: () => new FakeLearningBlock(),
      replacementFactory: () => new FakeReplacement(),
    });
    await controller.start();
    expect(viewport.observed).toHaveLength(1);
    const element = document.querySelector("p")!;

    element.firstChild!.textContent =
      "Readers now understand complex sentences in dynamically changing documents.";
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(100);

    expect(viewport.observed).toHaveLength(2);
    expect(viewport.observed[1]!.element).toBe(element);
    vi.useRealTimers();
  });

  it("discovers a newly inserted safe block from a child-list mutation", async () => {
    vi.useFakeTimers();
    document.body.innerHTML =
      "<main><p>Readers understand the first complex sentence in this document.</p></main>";
    let viewport!: FakeViewport;
    const controller = new SessionController({
      tabId: 9,
      document,
      transport: new FakeTransport(),
      scan: scanDocument,
      createSentenceId: ({ blockId, order }) => Promise.resolve(`${blockId}-sentence-${order + 1}`),
      viewportFactory: (callback) => (viewport = new FakeViewport(callback)),
      learningBlockFactory: () => new FakeLearningBlock(),
      replacementFactory: () => new FakeReplacement(),
    });
    await controller.start();
    const added = document.createElement("p");
    added.textContent = "Writers dynamically add another sufficiently long English sentence.";
    document.querySelector("main")!.append(added);

    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(100);

    expect(viewport.observed.some(({ element }) => element === added)).toBe(true);
    vi.useRealTimers();
  });

  it("promotes an inside-block child-list mutation before automatic rescanning", async () => {
    vi.useFakeTimers();
    document.body.innerHTML =
      "<main><p>Readers understand complex sentences with nested inline content.</p></main>";
    let viewport!: FakeViewport;
    const controller = new SessionController({
      tabId: 9,
      document,
      transport: new FakeTransport(),
      scan: scanDocument,
      createSentenceId: ({ order }) => Promise.resolve(`inside-sentence-${order + 1}`),
      viewportFactory: (callback) => (viewport = new FakeViewport(callback)),
      learningBlockFactory: () => new FakeLearningBlock(),
      replacementFactory: () => new FakeReplacement(),
    });
    await controller.start();
    const element = document.querySelector("p")!;
    const inline = document.createElement("span");
    inline.textContent = " Additional words remain eligible.";
    element.append(inline);
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(100);

    expect(viewport.observed.filter((candidate) => candidate.element === element)).toHaveLength(2);
    vi.useRealTimers();
  });

  it("promotes nested inline character mutations from the matched candidate block", async () => {
    vi.useFakeTimers();
    document.body.innerHTML =
      "<main><p>Readers understand <span>deeply nested syntax content</span> in documents.</p></main>";
    let viewport!: FakeViewport;
    const controller = new SessionController({
      tabId: 9,
      document,
      transport: new FakeTransport(),
      scan: scanDocument,
      createSentenceId: ({ order }) => Promise.resolve(`nested-sentence-${order + 1}`),
      viewportFactory: (callback) => (viewport = new FakeViewport(callback)),
      learningBlockFactory: () => new FakeLearningBlock(),
      replacementFactory: () => new FakeReplacement(),
    });
    await controller.start();
    const element = document.querySelector("p")!;
    document.querySelector("span")!.firstChild!.textContent = "new deeply nested syntax content";
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(100);

    expect(viewport.observed.filter((candidate) => candidate.element === element)).toHaveLength(2);
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
    // Every registered block is asked to close its open panel first, so only
    // one explanation stays open across the page.
    expect(subject.learningBlocks[0]!.closedDetails).toBe(1);
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

  it("uses a dedicated safe anchor when first-use selection has no recoverable DOM target", async () => {
    const subject = harness();

    await subject.controller.parseSelection("Readers select this sentence safely.");
    await vi.waitFor(() => expect(subject.transport.sent).toHaveLength(1));
    await vi.waitFor(() => expect(subject.replacements.at(-1)!.shows).toBe(1));

    expect(subject.replacements.at(-1)!.originals[0]).not.toBe(document.body);
  });

  it("routes detail retry and explicit correction component events", async () => {
    const subject = harness();
    await startAndEmit(subject);

    document.dispatchEvent(
      new CustomEvent("syntax-reanalyze-request", {
        detail: { sentenceId: "sentence-1", focus: { startToken: 0, endToken: 1 } },
      }),
    );
    document.dispatchEvent(
      new CustomEvent("syntax-correction-request", {
        detail: { sentenceId: "sentence-1", feedback: "Readers is the subject." },
      }),
    );

    await vi.waitFor(() => expect(subject.transport.sent).toHaveLength(3));
    expect(subject.transport.sent[0]!.type).toBe("ANALYZE_CORE");
    expect(subject.transport.sent.slice(1).map(({ type }) => type)).toEqual(
      expect.arrayContaining(["ANALYZE_DETAIL", "REANALYZE_WITH_FEEDBACK"]),
    );
  });

  it("collects production correction feedback from the retry interaction", async () => {
    const subject = harness(undefined, undefined, {
      requestFeedback: () => "Readers is the subject.",
    });
    await startAndEmit(subject);

    document.dispatchEvent(
      new CustomEvent("syntax-reanalyze-request", {
        detail: { sentenceId: "sentence-1", focus: { startToken: 0, endToken: 1 } },
      }),
    );

    await vi.waitFor(() => expect(subject.transport.sent).toHaveLength(2));
    expect(subject.transport.sent[1]).toMatchObject({
      type: "REANALYZE_WITH_FEEDBACK",
      feedback: "Readers is the subject.",
    });
  });

  it("retries a failed sentence as core analysis without requiring prior core", async () => {
    const subject = harness(
      undefined,
      new FakeTransport((message) =>
        Promise.resolve({
          version: 1,
          requestId: message.requestId,
          type: "CORE_RESULT",
          analyses: [],
        }),
      ),
    );
    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.failed).toBe(1));

    document.dispatchEvent(
      new CustomEvent("syntax-reanalyze-request", {
        detail: { sentenceId: "sentence-1", focus: { startToken: 0, endToken: 0 } },
      }),
    );

    await vi.waitFor(() => expect(subject.transport.sent).toHaveLength(2));
    expect(subject.transport.sent[1]).toMatchObject({ type: "ANALYZE_CORE" });
  });

  it("keeps a detail response valid while unrelated page analysis starts", async () => {
    const detailPending = deferred<ResponseMessage>();
    const transport = new FakeTransport((message) =>
      message.type === "ANALYZE_DETAIL"
        ? detailPending.promise
        : Promise.resolve({
            version: 1,
            requestId: message.requestId,
            type: "CORE_RESULT",
            analyses:
              message.type === "ANALYZE_CORE"
                ? message.sentences.map(({ sentenceId }) => core(sentenceId))
                : [],
          }),
    );
    const subject = harness(undefined, transport);
    await startAndEmit(subject);
    const detailPromise = subject.controller.requestDetail({
      sentenceId: "sentence-1",
      focus: { startToken: 0, endToken: 1 },
    });
    await vi.waitFor(() => expect(transport.sent.at(-1)!.type).toBe("ANALYZE_DETAIL"));
    await subject.controller.parseSelection("Unrelated readers analyze another sentence.");
    const detailRequest = transport.sent.find(({ type }) => type === "ANALYZE_DETAIL")!;

    detailPending.resolve({
      version: 1,
      requestId: detailRequest.requestId,
      type: "DETAIL_RESULT",
      analysis: detail("sentence-1"),
    });
    await detailPromise;

    expect(subject.learningBlocks[0]!.details).toEqual([detail("sentence-1")]);
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

  it("does not resubmit disconnected work while paused", async () => {
    const pending = deferred<ResponseMessage>();
    const transport = new FakeTransport(() => pending.promise);
    const subject = harness(undefined, transport);
    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(transport.sent).toHaveLength(1));
    subject.controller.pause();

    transport.disconnect();
    await vi.waitFor(() => expect(transport.reconnects).toBe(1));

    expect(transport.sent).toHaveLength(1);
  });

  it("cancels scheduled reconnect attempts when stopped", async () => {
    vi.useFakeTimers();
    const pending = deferred<ResponseMessage>();
    const transport = new FakeTransport(() => pending.promise);
    transport.reconnectHandler = vi.fn(() => Promise.reject(new Error("starting")));
    const subject = harness(undefined, transport);
    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(transport.sent).toHaveLength(1));
    transport.disconnect();
    await vi.waitFor(() => expect(transport.reconnects).toBe(1));

    subject.controller.stop();
    await vi.runAllTimersAsync();

    expect(transport.reconnects).toBe(1);
    vi.useRealTimers();
  });
});

describe("detail prefetch integration", () => {
  beforeEach(() => {
    document.body.replaceChildren();
    vi.restoreAllMocks();
  });

  function richCore(sentenceId: string, componentCount = 2): CoreAnalysis {
    return {
      schemaVersion: 1,
      sentenceId,
      modelProfileId: "profile-a",
      components: Array.from({ length: componentCount }, (_, index) => ({
        startToken: index,
        endToken: index,
        role: GrammarRole.SUBJECT,
        translation: "译文",
      })),
    };
  }

  type PrefetchRequest = Extract<RequestMessage, { type: "PREFETCH_SENTENCE_DETAILS" }>;

  function prefetchTransport(
    respond: (message: PrefetchRequest) => ResponseMessage,
  ): FakeTransport {
    return new FakeTransport((message) =>
      Promise.resolve(
        message.type === "PREFETCH_SENTENCE_DETAILS"
          ? respond(message)
          : {
              version: 1,
              requestId: message.requestId,
              type: "CORE_RESULT",
              analyses:
                message.type === "ANALYZE_CORE"
                  ? message.sentences.map(({ sentenceId }) => richCore(sentenceId))
                  : [],
            },
      ),
    );
  }

  function prefetchMessages(transport: FakeTransport): PrefetchRequest[] {
    return transport.sent.filter(
      (message): message is PrefetchRequest => message.type === "PREFETCH_SENTENCE_DETAILS",
    );
  }

  it("feeds ready sentences into the prefetcher and reports detail counts", async () => {
    let lastStatus: SessionStatus | undefined;
    const transport = prefetchTransport((message) => ({
      version: 1,
      requestId: message.requestId,
      type: "SENTENCE_DETAILS_RESULT",
      succeeded: 2,
      failed: 0,
    }));
    const subject = harness("Readers learn. Writers practice daily.", transport, {
      onStatus: (status) => {
        lastStatus = status;
      },
    });

    await subject.controller.start({ prefetchDetail: true });
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.ready).toBe(2));
    await vi.waitFor(() => expect(subject.controller.status.detailReady).toBe(4));

    expect(prefetchMessages(transport).length).toBe(2);
    const [firstPrefetch] = prefetchMessages(transport);
    expect(firstPrefetch).toMatchObject({ type: "PREFETCH_SENTENCE_DETAILS" });
    expect(firstPrefetch!.sentence).toBeDefined();
    expect(firstPrefetch!.core).toBeDefined();
    expect(lastStatus).toMatchObject({ detailTotal: 4, detailReady: 4, detailFailed: 0 });
  });

  it("does not prefetch when started without the flag and omits detail fields", async () => {
    const transport = prefetchTransport((message) => ({
      version: 1,
      requestId: message.requestId,
      type: "SENTENCE_DETAILS_RESULT",
      succeeded: 2,
      failed: 0,
    }));
    const subject = harness("Readers learn. Writers practice daily.", transport);

    await subject.controller.start();
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.ready).toBe(2));
    await Promise.resolve();
    await Promise.resolve();

    expect(prefetchMessages(transport).length).toBe(0);
    const lastStatus = subject.controller.status;
    expect("detailTotal" in lastStatus).toBe(false);
    expect("detailReady" in lastStatus).toBe(false);
    expect("detailFailed" in lastStatus).toBe(false);
  });

  it("counts a whole sentence as failed on an ERROR response and re-queues on cancel", async () => {
    const attempts = new Map<string, number>();
    const transport = prefetchTransport((message) => {
      const sentenceId = message.sentence.sentenceId;
      const attempt = (attempts.get(sentenceId) ?? 0) + 1;
      attempts.set(sentenceId, attempt);
      if (sentenceId === "sentence-1") {
        return {
          version: 1,
          requestId: message.requestId,
          type: "ERROR",
          error: { code: "NETWORK_ERROR", message: "网络请求失败", retryable: true },
        };
      }
      if (attempt === 1) {
        return {
          version: 1,
          requestId: message.requestId,
          type: "ERROR",
          error: { code: "REQUEST_CANCELLED", message: "已取消", retryable: true },
        };
      }
      return {
        version: 1,
        requestId: message.requestId,
        type: "SENTENCE_DETAILS_RESULT",
        succeeded: 2,
        failed: 0,
      };
    });
    const subject = harness("Readers learn. Writers practice daily.", transport);

    await subject.controller.start({ prefetchDetail: true });
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.detailFailed).toBe(2));
    await Promise.resolve();
    await Promise.resolve();

    // 取消的句子不计数、也不自动重发。
    expect(subject.controller.status.detailReady).toBe(0);
    expect(prefetchMessages(transport)).toHaveLength(2);

    subject.controller.pause();
    subject.controller.resume();
    await vi.waitFor(() => expect(subject.controller.status.detailReady).toBe(2));

    const resent = prefetchMessages(transport).filter(
      ({ sentence }) => sentence.sentenceId === "sentence-2",
    );
    expect(resent).toHaveLength(2); // 首发 + resume 后恰好一次重发
    expect(subject.controller.status).toMatchObject({
      detailTotal: 4,
      detailReady: 2,
      detailFailed: 2,
    });
  });

  it("stops prefetching after stop()", async () => {
    const transport = prefetchTransport((message) => ({
      version: 1,
      requestId: message.requestId,
      type: "SENTENCE_DETAILS_RESULT",
      succeeded: 2,
      failed: 0,
    }));
    const subject = harness("Readers learn. Writers practice daily.", transport);

    await subject.controller.start({ prefetchDetail: true });
    subject.viewport.emit();
    await vi.waitFor(() => expect(subject.controller.status.detailReady).toBe(4));
    subject.controller.stop();

    const lastStatus = subject.controller.status;
    expect("detailTotal" in lastStatus).toBe(false);
    expect("detailReady" in lastStatus).toBe(false);
    expect("detailFailed" in lastStatus).toBe(false);
    expect(prefetchMessages(transport)).toHaveLength(2);
  });
});

describe("ContentScriptRouter", () => {
  it("deeply rejects malformed runtime results instead of trusting their type string", () => {
    expect(
      isRuntimeResponse(
        { version: 1, requestId: "request-1", type: "CORE_RESULT", analyses: null },
        "request-1",
      ),
    ).toBe(false);
    expect(
      isRuntimeResponse(
        {
          version: 1,
          requestId: "request-1",
          type: "CORE_RESULT",
          analyses: [core("sentence-1")],
        },
        "request-1",
      ),
    ).toBe(true);
  });

  it("keeps a production Port watchdog and reconnects it after disconnect", () => {
    const disconnectListeners: Array<() => void> = [];
    const port = {
      disconnect: vi.fn(),
      onDisconnect: {
        addListener: (listener: () => void) => disconnectListeners.push(listener),
      },
    };
    const runtime = {
      connect: vi.fn(() => port),
      sendMessage: vi.fn(() =>
        Promise.resolve({
          version: 1,
          requestId: "request-1",
          type: "ACK",
          acknowledgedType: "START_SESSION",
        }),
      ),
    };
    const transport = new ChromeRuntimeTransport(3, "document-1", runtime);
    const disconnected = vi.fn();
    transport.onDisconnect(disconnected);

    disconnectListeners[0]!();
    transport.reconnect();

    expect(disconnected).toHaveBeenCalledOnce();
    expect(runtime.connect).toHaveBeenCalledTimes(2);

    transport.dispose();
    expect(port.disconnect).toHaveBeenCalledOnce();
  });

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
      reanalyzeVisible: vi.fn(),
      switchProfile: vi.fn(),
    };
    const factory = vi.fn(() => controller);
    const router = new ContentScriptRouter({
      controllerFactory: factory,
      transportFactory: () => new FakeTransport(),
    });

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

  it("isSessionStatus accepts detail counters and START_SESSION forwards the flag", async () => {
    expect(
      isRuntimeResponse(
        {
          version: 1,
          requestId: "request-1",
          type: "SESSION_STATUS",
          status: {
            state: "running",
            discovered: 2,
            queued: 0,
            ready: 2,
            failed: 0,
            detailTotal: 4,
            detailReady: 3,
            detailFailed: 1,
          },
        },
        "request-1",
      ),
    ).toBe(true);

    const start = vi.fn<(options?: { prefetchDetail?: boolean }) => Promise<void>>(() =>
      Promise.resolve(),
    );
    const router = new ContentScriptRouter({
      controllerFactory: () => ({
        documentId: "document-1",
        status: { state: "running" as const, discovered: 0, queued: 0, ready: 0, failed: 0 },
        start,
        pause: vi.fn(),
        resume: vi.fn(),
        stop: vi.fn(),
        parseSelection: vi.fn(() => Promise.resolve(undefined)),
        parseContextBlock: vi.fn(() => Promise.resolve(undefined)),
        reanalyzeVisible: vi.fn(),
        switchProfile: vi.fn(),
      }),
      transportFactory: () => new FakeTransport(),
    });

    const response = await router.route({
      version: 1,
      requestId: "start-1",
      type: "START_SESSION",
      tabId: 3,
      documentId: "document-1",
      prefetchDetail: true,
    });

    expect(response).toMatchObject({ type: "SESSION_STATUS" });
    expect(start).toHaveBeenCalledWith({ prefetchDetail: true });
  });

  it("routes a visible-area reanalysis request to the document controller", async () => {
    const reanalyzeVisible = vi.fn();
    const router = new ContentScriptRouter({
      controllerFactory: () => ({
        documentId: "document-1",
        status: { state: "running", discovered: 1, queued: 0, ready: 1, failed: 0 },
        start: vi.fn(() => Promise.resolve()),
        pause: vi.fn(),
        resume: vi.fn(),
        stop: vi.fn(),
        parseSelection: vi.fn(() => Promise.resolve(undefined)),
        parseContextBlock: vi.fn(() => Promise.resolve(undefined)),
        reanalyzeVisible,
        switchProfile: vi.fn(),
      }),
      transportFactory: () => new FakeTransport(),
    });

    const response = await router.route({
      version: 1,
      requestId: "reanalyze-1",
      type: "REANALYZE_VISIBLE",
      tabId: 3,
      documentId: "document-1",
    });

    expect(reanalyzeVisible).toHaveBeenCalledOnce();
    expect(response).toMatchObject({ type: "SESSION_STATUS" });
  });
});
