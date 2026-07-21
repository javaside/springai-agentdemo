import type { ExtensionError } from "../shared/errors";
import type { CoreAnalysis, DetailAnalysis, Token, TokenRange } from "../shared/grammar";
import type {
  RequestMessage,
  ResponseMessage,
  SentenceInput,
  SessionStatus,
} from "../shared/protocol";
import { MESSAGE_VERSION } from "../shared/versions";
import { createSentenceId, segmentBlock, tokenize } from "../language/segmenter";
import { BlockReplacement } from "./block-replacement";
import type { SentenceFailure } from "./block-replacement";
import { nearestSafeBlock, scanDocument } from "./document-scanner";
import type { CandidateBlock } from "./document-scanner";
import { DetailPrefetcher } from "./detail-prefetcher";
import type { PrefetchSendResult } from "./detail-prefetcher";
import type { SyntaxFocusEventDetail } from "./learning-block";
import { SyntaxLearningBlock } from "./learning-block";
import { ViewportObserver } from "./viewport-observer";

export type SentencePhase =
  | "discovered"
  | "cache-check"
  | "queued"
  | "requesting"
  | "validating"
  | "ready"
  | "failed"
  | "skipped"
  | "stale";

export interface ControllerBlock {
  setExpectedSentenceIds(ids: readonly string[]): void;
  renderCore(sentence: string, tokens: readonly Token[], analysis: CoreAnalysis): void;
  renderFailure(sentenceId: string, sentence: string, message: string): void;
  renderSkipped(sentenceId: string, sentence: string): void;
  setDetailLoading(sentenceId: string, focus: TokenRange): void;
  closeDetails(): void;
  renderDetail(analysis: DetailAnalysis): void;
  renderError(sentenceId: string, focus: TokenRange, message: string): void;
  isReadyToReplace(): boolean;
}

export interface ControllerReplacement {
  show(original: HTMLElement, block: ControllerBlock): void;
  showPartialFailure(
    original: HTMLElement,
    block: ControllerBlock,
    failures: readonly SentenceFailure[],
  ): void;
  restore(): void;
  currentElement(original: HTMLElement): Element;
}

export interface ViewportPort {
  observe(blocks: readonly CandidateBlock[]): void;
  invalidate(blockId: string): void;
  isVisible(element: Element): boolean;
  disconnect(): void;
}

export interface RuntimeTransport {
  send(message: RequestMessage): Promise<ResponseMessage>;
  cancelDocument(documentId: string): void;
  onDisconnect?(handler: () => void): () => void;
  reconnect?(): void | Promise<void>;
  dispose?(): void;
}

interface SentenceRecord {
  input: SentenceInput;
  phase: SentencePhase;
  core?: CoreAnalysis;
}

interface BlockRecord {
  candidate: CandidateBlock & { element: HTMLElement };
  sentences: SentenceRecord[];
  learningBlock: ControllerBlock;
  replacement: ControllerReplacement;
  operationVersion: number;
  /** 「重新解析」一次性标记:下次 analyzeBlock 携带 bypassCache 后即清除。 */
  bypassCacheOnce?: boolean;
}

export interface SessionControllerOptions {
  tabId: number;
  documentId?: string;
  document?: Document;
  transport: RuntimeTransport;
  scan?: (root: ParentNode) => CandidateBlock[];
  createSentenceId?: typeof createSentenceId;
  viewportFactory?: (callback: (candidate: CandidateBlock) => void) => ViewportPort;
  learningBlockFactory?: () => ControllerBlock;
  replacementFactory?: () => ControllerReplacement;
  now?: () => number;
  yieldNow?: () => Promise<void>;
  randomUUID?: () => string;
  setTimeout?: (callback: () => void, delay: number) => ReturnType<typeof setTimeout>;
  clearTimeout?: (timer: ReturnType<typeof setTimeout>) => void;
  onTransition?: (sentenceId: string, phase: SentencePhase) => void;
  onStatus?: (status: SessionStatus) => void;
  requestFeedback?: (sentenceId: string) => string | null | Promise<string | null>;
}

const CONTEXT_ERROR: ExtensionError = {
  code: "UNSAFE_CONTENT_BLOCK",
  message: "请先启动学习模式，或选中文字后解析",
  retryable: false,
};
const TOO_LONG_MESSAGE = "SENTENCE_TOO_LONG：句子超过 2,000 个规范化字符";
const MISSING_RESULT_MESSAGE = "INVALID_MODEL_OUTPUT：模型未返回此句的解析结果";

function defaultYield(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function isHTMLElement(element: Element): element is HTMLElement {
  return element instanceof HTMLElement;
}

function normalizedLength(text: string): number {
  return text.trim().replace(/\s+/gu, " ").length;
}

function responseErrorMessage(response: ResponseMessage): string {
  return response.type === "ERROR"
    ? `${response.error.code}：${response.error.message}`
    : MISSING_RESULT_MESSAGE;
}

export class SessionController {
  readonly documentId: string;

  private readonly document: Document;
  private readonly scan: (root: ParentNode) => CandidateBlock[];
  private readonly sentenceIdFactory: typeof createSentenceId;
  private readonly viewport: ViewportPort;
  private readonly now: () => number;
  private readonly yieldNow: () => Promise<void>;
  private readonly scheduleTimeout: NonNullable<SessionControllerOptions["setTimeout"]>;
  private readonly cancelTimeout: NonNullable<SessionControllerOptions["clearTimeout"]>;
  private readonly blocks = new Map<string, BlockRecord>();
  private readonly sentences = new Map<string, SentenceRecord>();
  private readonly pendingRequestIds = new Map<string, number>();
  private readonly pausedBlocks = new Set<string>();
  private readonly changedBlockIds = new Set<string>();
  private readonly mutationRoots = new Set<ParentNode>();
  private readonly ephemeralSelectionAnchors = new Set<HTMLElement>();
  private readonly detailVersions = new Map<string, number>();
  private prefetcher?: DetailPrefetcher;
  private readonly correctionVersions = new Map<string, number>();
  private readonly reconnectTimers = new Set<ReturnType<typeof setTimeout>>();
  private state: SessionStatus["state"] = "stopped";
  private requestCounter = 0;
  private operationVersion = 0;
  private contextTarget: EventTarget | null = null;
  private mutationObserver?: MutationObserver;
  private mutationTimer?: ReturnType<typeof setTimeout>;
  private removeDisconnectListener?: () => void;
  private selectedProfileId?: string;

  constructor(private readonly options: SessionControllerOptions) {
    this.document = options.document ?? document;
    this.scan = options.scan ?? scanDocument;
    this.sentenceIdFactory = options.createSentenceId ?? createSentenceId;
    this.now = options.now ?? performance.now.bind(performance);
    this.yieldNow = options.yieldNow ?? defaultYield;
    // Wrap the global timers: storing them and calling through `this` would
    // throw "Illegal invocation" in a real Chrome content-script world.
    this.scheduleTimeout = options.setTimeout ?? ((callback, delay) => setTimeout(callback, delay));
    this.cancelTimeout = options.clearTimeout ?? ((timer) => clearTimeout(timer));
    this.documentId =
      options.documentId ?? (options.randomUUID ?? crypto.randomUUID.bind(crypto))();
    this.viewport = (options.viewportFactory ?? ((callback) => new ViewportObserver(callback)))(
      (candidate) => this.queueVisibleBlock(candidate.id),
    );
  }

  get status(): SessionStatus {
    const records = [...this.sentences.values()];
    return {
      state: this.state,
      discovered: records.length,
      queued: records.filter(({ phase }) => phase === "queued").length,
      ready: records.filter(({ phase }) => phase === "ready").length,
      failed: records.filter(({ phase }) => phase === "failed").length,
      skipped: records.filter(({ phase }) => phase === "skipped").length,
      ...(this.selectedProfileId === undefined ? {} : { profileId: this.selectedProfileId }),
      ...(this.prefetcher === undefined
        ? {}
        : (() => {
            const counts = this.prefetcher.counts();
            return {
              detailTotal: counts.total,
              detailReady: counts.ready,
              detailFailed: counts.failed,
            };
          })()),
    };
  }

  async start(options?: { prefetchDetail?: boolean }): Promise<void> {
    if (this.state === "running") return;
    if (this.state === "paused") {
      this.resume();
      return;
    }
    this.state = "running";
    if (options?.prefetchDetail === true) {
      this.prefetcher = new DetailPrefetcher({
        send: (item) => this.sendPrefetch(item.sentence, item.core),
        onChange: () => this.emitStatus(),
      });
    }
    this.document.addEventListener("contextmenu", this.recordContextTarget, true);
    this.document.addEventListener("syntax-detail-request", this.handleDetailEvent);
    this.document.addEventListener("syntax-reanalyze-request", this.handleCorrectionEvent);
    this.document.addEventListener("syntax-correction-request", this.handleExplicitCorrectionEvent);
    this.installMutationObserver();
    this.removeDisconnectListener = this.options.transport.onDisconnect?.(
      this.handleTransportDisconnect,
    );
    const candidates = this.scan(this.document);
    await this.registerCandidates(candidates);
    this.viewport.observe(candidates);
    this.emitStatus();
  }

  pause(): void {
    if (this.state !== "running") return;
    this.state = "paused";
    this.prefetcher?.pause();
    this.emitStatus();
  }

  resume(): void {
    if (this.state !== "paused") return;
    this.state = "running";
    this.prefetcher?.resume();
    const waiting = [...this.pausedBlocks];
    this.pausedBlocks.clear();
    for (const blockId of waiting) this.queueVisibleBlock(blockId);
    this.emitStatus();
  }

  stop(): void {
    if (this.state === "stopped") return;
    this.state = "stopped";
    this.operationVersion += 1;
    this.options.transport.cancelDocument(this.documentId);
    this.prefetcher = undefined;
    this.viewport.disconnect();
    this.mutationObserver?.disconnect();
    this.mutationObserver = undefined;
    if (this.mutationTimer !== undefined) this.cancelTimeout(this.mutationTimer);
    this.mutationTimer = undefined;
    this.removeDisconnectListener?.();
    this.removeDisconnectListener = undefined;
    this.options.transport.dispose?.();
    this.document.removeEventListener("contextmenu", this.recordContextTarget, true);
    this.document.removeEventListener("syntax-detail-request", this.handleDetailEvent);
    this.document.removeEventListener("syntax-reanalyze-request", this.handleCorrectionEvent);
    this.document.removeEventListener(
      "syntax-correction-request",
      this.handleExplicitCorrectionEvent,
    );
    for (const timer of this.reconnectTimers) this.cancelTimeout(timer);
    this.reconnectTimers.clear();
    for (const block of this.blocks.values()) block.replacement.restore();
    for (const anchor of this.ephemeralSelectionAnchors) anchor.remove();
    this.ephemeralSelectionAnchors.clear();
    this.pendingRequestIds.clear();
    this.detailVersions.clear();
    this.correctionVersions.clear();
    this.pausedBlocks.clear();
    this.contextTarget = null;
    this.emitStatus();
  }

  async parseSelection(selectionText: string): Promise<ExtensionError | undefined> {
    const text = selectionText.trim().replace(/\s+/gu, " ");
    if (text.length === 0) return CONTEXT_ERROR;
    if (this.state === "stopped") await this.start();
    const target = this.selectionTarget() ?? this.contextTarget;
    const candidate = nearestSafeBlock(target, { selection: true });
    const element = candidate?.element ?? this.createSelectionAnchor(text);
    const id = `selection-${++this.operationVersion}`;
    const selectionCandidate: CandidateBlock = { id, element, text };
    await this.registerCandidates([selectionCandidate]);
    this.queueVisibleBlock(id, true);
    return undefined;
  }

  async parseContextBlock(): Promise<ExtensionError | undefined> {
    if (this.state === "stopped" || this.contextTarget === null) return CONTEXT_ERROR;
    const candidate = nearestSafeBlock(this.contextTarget);
    if (candidate === null) return CONTEXT_ERROR;
    if (!this.blocks.has(candidate.id)) await this.registerCandidates([candidate]);
    this.queueVisibleBlock(candidate.id, true);
    return undefined;
  }

  switchProfile(profileId: string): void {
    this.selectedProfileId = profileId;
    this.emitStatus();
  }

  reanalyzeVisible(): void {
    if (this.state === "stopped") return;
    const visibleBlockIds = [...this.blocks.entries()]
      .filter(([, block]) =>
        this.viewport.isVisible(block.replacement.currentElement(block.candidate.element)),
      )
      .map(([blockId]) => blockId);
    for (const blockId of visibleBlockIds) {
      const block = this.blocks.get(blockId);
      if (block !== undefined) block.bypassCacheOnce = true;
      this.invalidateBlock(blockId);
      this.queueVisibleBlock(blockId, true);
    }
  }

  async requestDetail(detail: SyntaxFocusEventDetail): Promise<void> {
    const located = this.locateSentence(detail.sentenceId);
    if (located === undefined || located.sentence.core === undefined || this.state !== "running") {
      return;
    }
    // Only one explanation panel is open at a time across the whole page, so
    // every open panel closes when this one opens.
    for (const block of this.blocks.values()) {
      block.learningBlock.closeDetails();
    }
    located.block.learningBlock.setDetailLoading(detail.sentenceId, detail.focus);
    const version = ++this.operationVersion;
    const detailKey = `${detail.sentenceId}:${detail.focus.startToken}:${detail.focus.endToken}`;
    this.detailVersions.set(detailKey, version);
    const request = this.pageRequest({
      type: "ANALYZE_DETAIL",
      sentence: located.sentence.input,
      core: located.sentence.core,
      focus: detail.focus,
    });
    const response = await this.send(request, version);
    if (response === undefined || this.detailVersions.get(detailKey) !== version) return;
    this.detailVersions.delete(detailKey);
    if (response.type === "DETAIL_RESULT" && response.analysis.sentenceId === detail.sentenceId) {
      located.block.learningBlock.renderDetail(response.analysis);
    } else {
      located.block.learningBlock.renderError(
        detail.sentenceId,
        detail.focus,
        // NO_CACHE 是纯缓存模式的预期状态而非故障，面板只给中文引导文案、不带错误码前缀。
        response !== undefined && response.type === "ERROR" && response.error.code === "NO_CACHE"
          ? response.error.message
          : response === undefined
            ? "REQUEST_CANCELLED"
            : responseErrorMessage(response),
      );
    }
  }

  /** 预载发送:把 transport 响应归一化为 prefetcher 的三态结果(transport 拒绝由 prefetcher 兜成 failed)。 */
  private async sendPrefetch(
    sentence: SentenceInput,
    core: CoreAnalysis,
  ): Promise<PrefetchSendResult> {
    const response = await this.options.transport.send(
      this.pageRequest({ type: "PREFETCH_SENTENCE_DETAILS", sentence, core }),
    );
    if (response.type === "SENTENCE_DETAILS_RESULT") {
      return { kind: "ok", succeeded: response.succeeded, failed: response.failed };
    }
    if (response.type === "ERROR" && response.error.code === "REQUEST_CANCELLED") {
      return { kind: "cancelled" };
    }
    return { kind: "failed" };
  }

  async submitCorrection(sentenceId: string, feedback: string): Promise<void> {
    const located = this.locateSentence(sentenceId);
    if (
      located === undefined ||
      located.sentence.core === undefined ||
      feedback.trim().length === 0 ||
      this.state !== "running"
    ) {
      return;
    }
    const version = ++this.operationVersion;
    this.correctionVersions.set(sentenceId, version);
    const request = this.pageRequest({
      type: "REANALYZE_WITH_FEEDBACK",
      sentence: located.sentence.input,
      core: located.sentence.core,
      feedback: feedback.trim(),
    });
    const response = await this.send(request, version);
    if (response === undefined || this.correctionVersions.get(sentenceId) !== version) return;
    this.correctionVersions.delete(sentenceId);
    if (response.type === "CORE_RESULT") {
      const corrected = response.analyses.find(({ sentenceId: id }) => id === sentenceId);
      if (corrected !== undefined) {
        located.block.learningBlock.renderCore(
          located.sentence.input.text,
          located.sentence.input.tokens,
          corrected,
        );
        located.sentence.core = corrected;
      }
    }
  }

  invalidateBlock(blockId: string): void {
    const block = this.blocks.get(blockId);
    if (block === undefined) return;
    block.operationVersion = ++this.operationVersion;
    block.replacement.restore();
    this.viewport.invalidate(blockId);
    for (const sentence of block.sentences) {
      sentence.core = undefined;
      this.transition(sentence, "stale");
      this.prefetcher?.discard(sentence.input.sentenceId);
    }
    this.emitStatus();
  }

  private async registerCandidates(candidates: readonly CandidateBlock[]): Promise<void> {
    let windowStartedAt = this.now();
    for (const candidate of candidates) {
      if (!isHTMLElement(candidate.element)) continue;
      const sentenceParts = segmentBlock(candidate.text);
      const sentences: SentenceRecord[] = [];
      for (const [order, part] of sentenceParts.entries()) {
        const sentenceId = await this.sentenceIdFactory({
          sessionId: this.documentId,
          blockId: candidate.id,
          order,
          normalizedText: part.text.trim().replace(/\s+/gu, " "),
        });
        const sentence: SentenceRecord = {
          input: { sentenceId, text: part.text, tokens: tokenize(part.text) },
          phase: "discovered",
        };
        sentences.push(sentence);
        this.sentences.set(sentenceId, sentence);
        this.options.onTransition?.(sentenceId, "discovered");
        if (this.now() - windowStartedAt >= 8) {
          await this.yieldNow();
          windowStartedAt = this.now();
        }
      }
      if (sentences.length === 0) continue;
      const learningBlock = (
        this.options.learningBlockFactory ?? (() => new SyntaxLearningBlock(this.document))
      )();
      learningBlock.setExpectedSentenceIds(sentences.map(({ input }) => input.sentenceId));
      const replacement = (this.options.replacementFactory ?? (() => new BlockReplacement()))();
      this.blocks.set(candidate.id, {
        candidate: { ...candidate, element: candidate.element },
        sentences,
        learningBlock,
        replacement,
        operationVersion: this.operationVersion,
      });
    }
  }

  private queueVisibleBlock(blockId: string, force = false): void {
    const block = this.blocks.get(blockId);
    if (block === undefined || this.state === "stopped") return;
    if (this.state === "paused" && !force) {
      this.pausedBlocks.add(blockId);
      return;
    }
    if (
      block.sentences.every(
        ({ phase }) => phase === "ready" || phase === "failed" || phase === "skipped",
      )
    ) {
      return;
    }
    void this.analyzeBlock(block);
  }

  private async analyzeBlock(block: BlockRecord): Promise<void> {
    const bypassCache = block.bypassCacheOnce === true;
    block.bypassCacheOnce = undefined;
    const version = ++this.operationVersion;
    block.operationVersion = version;
    const failures: SentenceFailure[] = [];
    const outgoing: SentenceRecord[] = [];
    for (const sentence of block.sentences) {
      this.transition(sentence, "cache-check");
      if (normalizedLength(sentence.input.text) > 2_000) {
        this.transition(sentence, "validating");
        const failure = {
          sentenceId: sentence.input.sentenceId,
          sentence: sentence.input.text,
          message: TOO_LONG_MESSAGE,
        };
        failures.push(failure);
        this.transition(sentence, "failed");
      } else {
        this.transition(sentence, "queued");
        outgoing.push(sentence);
      }
    }
    if (outgoing.length === 0) {
      this.finishBlock(block, failures);
      return;
    }
    for (const sentence of outgoing) this.transition(sentence, "requesting");
    const request = this.pageRequest({
      type: "ANALYZE_CORE",
      sentences: outgoing.map(({ input }) => input),
      ...(bypassCache ? { bypassCache: true as const } : {}),
    });
    const response = await this.send(request, version);
    if (response === undefined || block.operationVersion !== version || this.isStopped()) {
      return;
    }
    for (const sentence of outgoing) this.transition(sentence, "validating");
    const analyses = response.type === "CORE_RESULT" ? response.analyses : [];
    const cacheOnly = response.type === "CORE_RESULT" && response.cacheOnly === true;
    for (const sentence of outgoing) {
      const analysis = analyses.find(({ sentenceId }) => sentenceId === sentence.input.sentenceId);
      if (analysis === undefined) {
        if (cacheOnly) {
          // 纯缓存会话未命中不算失败：不标红、不给重试。部分命中时未命中句以纯原文
          // 参与替换；整块全未命中则由 finishBlock 的守卫保持页面原始 DOM。
          block.learningBlock.renderSkipped(sentence.input.sentenceId, sentence.input.text);
          this.transition(sentence, "skipped");
          continue;
        }
        const failure = {
          sentenceId: sentence.input.sentenceId,
          sentence: sentence.input.text,
          message: responseErrorMessage(response),
        };
        failures.push(failure);
        this.transition(sentence, "failed");
        continue;
      }
      try {
        block.learningBlock.renderCore(sentence.input.text, sentence.input.tokens, analysis);
        sentence.core = analysis;
        this.transition(sentence, "ready");
        this.prefetcher?.enqueue(sentence.input, analysis);
      } catch {
        const failure = {
          sentenceId: sentence.input.sentenceId,
          sentence: sentence.input.text,
          message: MISSING_RESULT_MESSAGE,
        };
        failures.push(failure);
        this.transition(sentence, "failed");
      }
    }
    this.finishBlock(block, failures);
  }

  private finishBlock(block: BlockRecord, failures: readonly SentenceFailure[]): void {
    const original = block.candidate.element;
    if (!isHTMLElement(original)) return;
    // 纯缓存会话整块未命中：替换成纯文本副本会丢失页面原有标记，保持原 DOM。
    if (block.sentences.length > 0 && block.sentences.every(({ phase }) => phase === "skipped")) {
      this.emitStatus();
      return;
    }
    if (failures.length > 0) {
      block.replacement.showPartialFailure(original, block.learningBlock, failures);
    } else if (block.learningBlock.isReadyToReplace()) {
      block.replacement.show(original, block.learningBlock);
    }
    this.emitStatus();
  }

  private transition(sentence: SentenceRecord, phase: SentencePhase): void {
    sentence.phase = phase;
    this.options.onTransition?.(sentence.input.sentenceId, phase);
    this.emitStatus();
  }

  private pageRequest<
    T extends Omit<RequestMessage, "version" | "requestId" | "tabId" | "documentId">,
  >(body: T): RequestMessage {
    return {
      ...body,
      version: MESSAGE_VERSION,
      requestId: `${this.documentId}:${++this.requestCounter}`,
      tabId: this.options.tabId,
      documentId: this.documentId,
    } as RequestMessage;
  }

  private async send(
    request: RequestMessage,
    version: number,
  ): Promise<ResponseMessage | undefined> {
    this.pendingRequestIds.set(request.requestId, version);
    try {
      const response = await this.options.transport.send(request);
      if (
        response.version !== MESSAGE_VERSION ||
        response.requestId !== request.requestId ||
        this.pendingRequestIds.get(request.requestId) !== version
      ) {
        return undefined;
      }
      return response;
    } finally {
      this.pendingRequestIds.delete(request.requestId);
    }
  }

  private locateSentence(
    sentenceId: string,
  ): { block: BlockRecord; sentence: SentenceRecord } | undefined {
    const sentence = this.sentences.get(sentenceId);
    if (sentence === undefined) return undefined;
    for (const block of this.blocks.values()) {
      if (block.sentences.includes(sentence)) return { block, sentence };
    }
    return undefined;
  }

  private selectionTarget(): Node | null {
    const selection = this.document.getSelection();
    return selection?.anchorNode ?? null;
  }

  private createSelectionAnchor(text: string): HTMLElement {
    const anchor = this.document.createElement("span");
    anchor.dataset.syntaxLearningSelectionAnchor = "true";
    anchor.textContent = text;
    this.document.body.append(anchor);
    this.ephemeralSelectionAnchors.add(anchor);
    return anchor;
  }

  private readonly recordContextTarget = (event: Event): void => {
    if (this.state !== "stopped") this.contextTarget = event.target;
  };

  private readonly handleDetailEvent = (event: Event): void => {
    if (!(event instanceof CustomEvent)) return;
    void this.requestDetail(event.detail as SyntaxFocusEventDetail);
  };

  private readonly handleCorrectionEvent = (event: Event): void => {
    if (!(event instanceof CustomEvent)) return;
    const detail = event.detail as SyntaxFocusEventDetail;
    const located = this.locateSentence(detail.sentenceId);
    if (located?.sentence.core === undefined) {
      void this.retryCore(detail.sentenceId);
    } else {
      void this.resolveRetryInteraction(detail);
    }
  };

  private async resolveRetryInteraction(detail: SyntaxFocusEventDetail): Promise<void> {
    const feedback = await Promise.resolve(
      this.options.requestFeedback?.(detail.sentenceId) ?? null,
    );
    if (feedback !== null && feedback.trim().length > 0) {
      await this.submitCorrection(detail.sentenceId, feedback);
    } else {
      await this.requestDetail(detail);
    }
  }

  private readonly handleExplicitCorrectionEvent = (event: Event): void => {
    if (!(event instanceof CustomEvent)) return;
    const detail = event.detail as { sentenceId?: unknown; feedback?: unknown };
    if (typeof detail.sentenceId !== "string" || typeof detail.feedback !== "string") return;
    void this.submitCorrection(detail.sentenceId, detail.feedback);
  };

  private async retryCore(sentenceId: string): Promise<void> {
    const located = this.locateSentence(sentenceId);
    if (located === undefined || this.state !== "running") return;
    const { block, sentence } = located;
    const version = ++this.operationVersion;
    block.operationVersion = version;
    this.transition(sentence, "queued");
    this.transition(sentence, "requesting");
    const request = this.pageRequest({ type: "ANALYZE_CORE", sentences: [sentence.input] });
    const response = await this.send(request, version);
    if (response === undefined || block.operationVersion !== version || this.isStopped()) {
      return;
    }
    this.transition(sentence, "validating");
    const analysis =
      response.type === "CORE_RESULT"
        ? response.analyses.find(({ sentenceId: id }) => id === sentenceId)
        : undefined;
    if (analysis === undefined) {
      if (response.type === "CORE_RESULT" && response.cacheOnly === true) {
        // 纯缓存会话里重试（如 TOO_LONG 失败句）仍未命中：与 analyzeBlock 同语义，
        // 转 skipped 而非 failed，也不再触发失败替换。
        block.learningBlock.renderSkipped(sentenceId, sentence.input.text);
        this.transition(sentence, "skipped");
        this.finishBlock(block, []);
        return;
      }
      this.transition(sentence, "failed");
      this.finishBlock(block, [
        { sentenceId, sentence: sentence.input.text, message: responseErrorMessage(response) },
      ]);
      return;
    }
    block.learningBlock.renderCore(sentence.input.text, sentence.input.tokens, analysis);
    sentence.core = analysis;
    this.transition(sentence, "ready");
    this.prefetcher?.enqueue(sentence.input, analysis);
    this.finishBlock(block, []);
  }

  private installMutationObserver(): void {
    this.mutationObserver = new MutationObserver((mutations) => {
      for (const mutation of mutations) {
        const target =
          mutation.target instanceof Element ? mutation.target : mutation.target.parentElement;
        if (target === null) continue;
        let matchedBlockRoot: ParentNode | null = null;
        for (const [blockId, block] of this.blocks) {
          if (block.candidate.element === target || block.candidate.element.contains(target)) {
            this.changedBlockIds.add(blockId);
            matchedBlockRoot = block.candidate.element.parentElement ?? block.candidate.element;
          }
        }
        const root: ParentNode | null =
          matchedBlockRoot ??
          (mutation.type === "characterData"
            ? (target.parentElement ?? target)
            : (mutation.target as ParentNode));
        if (root !== null) this.mutationRoots.add(root);
      }
      if (
        (this.changedBlockIds.size === 0 && this.mutationRoots.size === 0) ||
        this.mutationTimer !== undefined
      ) {
        return;
      }
      this.mutationTimer = this.scheduleTimeout(() => {
        this.mutationTimer = undefined;
        void this.flushMutations();
      }, 100);
    });
    this.mutationObserver.observe(this.document.documentElement, {
      childList: true,
      characterData: true,
      subtree: true,
    });
  }

  private async flushMutations(): Promise<void> {
    const changed = [...this.changedBlockIds];
    const roots = [...this.mutationRoots];
    this.changedBlockIds.clear();
    this.mutationRoots.clear();
    for (const blockId of changed) {
      const old = this.blocks.get(blockId);
      if (old === undefined) continue;
      this.invalidateBlock(blockId);
      for (const sentence of old.sentences) this.sentences.delete(sentence.input.sentenceId);
      this.blocks.delete(blockId);
    }
    const discovered = new Map<string, CandidateBlock>();
    for (const root of roots) {
      for (const candidate of this.scan(root)) {
        if (!this.blocks.has(candidate.id)) discovered.set(candidate.id, candidate);
      }
    }
    const candidates = [...discovered.values()];
    await this.registerCandidates(candidates);
    this.viewport.observe(candidates);
  }

  private async reconnectDelay(delay: number): Promise<void> {
    await new Promise<void>((resolve) => {
      const timer: { value?: ReturnType<typeof setTimeout> } = {};
      timer.value = this.scheduleTimeout(() => {
        if (timer.value !== undefined) this.reconnectTimers.delete(timer.value);
        resolve();
      }, delay);
      this.reconnectTimers.add(timer.value);
    });
  }

  private unfinishedBlockIds(): string[] {
    return [...this.blocks.values()].flatMap((block) =>
      block.sentences.some(
        ({ phase }) => phase !== "ready" && phase !== "failed" && phase !== "skipped",
      )
        ? [block.candidate.id]
        : [],
    );
  }

  private resumeUnfinishedAfterReconnect(): void {
    for (const blockId of this.unfinishedBlockIds()) {
      if (this.state === "paused") this.pausedBlocks.add(blockId);
      else if (this.state === "running") this.queueVisibleBlock(blockId);
    }
  }

  private readonly handleTransportDisconnect = (): void => {
    void this.reconnectAndResume();
  };

  private async reconnectAndResume(): Promise<void> {
    if (this.isStopped() || this.options.transport.reconnect === undefined) return;
    const delays = [0, 250, 500, 1_000];
    for (const delay of delays) {
      if (this.isStopped()) return;
      if (delay > 0) await this.reconnectDelay(delay);
      if (this.isStopped()) return;
      try {
        await this.options.transport.reconnect();
        this.resumeUnfinishedAfterReconnect();
        return;
      } catch {
        // The bounded retry schedule handles transient worker startup races.
      }
    }
  }

  private emitStatus(): void {
    this.options.onStatus?.(this.status);
  }

  private isStopped(): boolean {
    return this.state === "stopped";
  }
}
