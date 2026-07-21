import type { ExtensionError } from "../shared/errors";
import { ERROR_CODES } from "../shared/errors";
import { GrammarRole } from "../shared/grammar";
import { isRequestMessage } from "../shared/protocol";
import type { RequestMessage, ResponseMessage, SessionStatus } from "../shared/protocol";
import { MESSAGE_VERSION } from "../shared/versions";
import { SyntaxProgressPill } from "./progress-pill";
import { SessionController } from "./session-controller";
import type { RuntimeTransport, SessionControllerOptions } from "./session-controller";

interface RoutedController {
  readonly status: SessionStatus;
  start(options?: { prefetchDetail?: boolean }): Promise<void>;
  pause(): void;
  resume(): void;
  stop(): void;
  parseSelection(selectionText: string): Promise<ExtensionError | undefined>;
  parseContextBlock(): Promise<ExtensionError | undefined>;
  reanalyzeVisible(): void;
  switchProfile(profileId: string): void;
}

export interface ContentScriptRouterOptions {
  controllerFactory?: (options: SessionControllerOptions) => RoutedController;
  transportFactory?: (tabId: number, documentId: string) => RuntimeTransport;
  relayStatus?: (documentId: string, status: SessionStatus) => void;
}

function invalidMessage(requestId = "invalid-message"): ResponseMessage {
  return {
    version: MESSAGE_VERSION,
    requestId,
    type: "ERROR",
    error: {
      code: "INVALID_MODEL_OUTPUT",
      message: "Invalid or unsupported content-script message",
      retryable: false,
    },
  };
}

function errorResponse(requestId: string, error: ExtensionError): ResponseMessage {
  return { version: MESSAGE_VERSION, requestId, type: "ERROR", error };
}

function statusResponse(requestId: string, status: SessionStatus): ResponseMessage {
  return { version: MESSAGE_VERSION, requestId, type: "SESSION_STATUS", status };
}

function ack(request: RequestMessage): ResponseMessage {
  return {
    version: MESSAGE_VERSION,
    requestId: request.requestId,
    type: "ACK",
    acknowledgedType: request.type,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0;
}

function isRange(
  value: unknown,
): value is Record<string, unknown> & { startToken: number; endToken: number } {
  return (
    isRecord(value) &&
    isSafeInteger(value.startToken) &&
    isSafeInteger(value.endToken) &&
    value.startToken <= value.endToken
  );
}

function isCoreAnalysis(value: unknown): boolean {
  return (
    isRecord(value) &&
    value.schemaVersion === 1 &&
    typeof value.sentenceId === "string" &&
    value.sentenceId.length > 0 &&
    typeof value.modelProfileId === "string" &&
    value.modelProfileId.length > 0 &&
    Array.isArray(value.components) &&
    value.components.every(
      (component) =>
        isRange(component) &&
        typeof component.role === "string" &&
        Object.values(GrammarRole).includes(component.role as GrammarRole) &&
        typeof component.translation === "string",
    )
  );
}

function isDetailAnalysis(value: unknown): boolean {
  return (
    isRecord(value) &&
    typeof value.sentenceId === "string" &&
    value.sentenceId.length > 0 &&
    typeof value.modelProfileId === "string" &&
    value.modelProfileId.length > 0 &&
    isRange(value.focus) &&
    Array.isArray(value.structures) &&
    value.structures.every(
      (structure) =>
        isRange(structure) &&
        isRecord(structure) &&
        typeof structure.role === "string" &&
        typeof structure.explanation === "string",
    ) &&
    Array.isArray(value.grammarPoints) &&
    value.grammarPoints.every((point) => typeof point === "string") &&
    typeof value.explanation === "string"
  );
}

function isSessionStatus(value: unknown): value is SessionStatus {
  return (
    isRecord(value) &&
    (value.state === "stopped" || value.state === "running" || value.state === "paused") &&
    isSafeInteger(value.discovered) &&
    isSafeInteger(value.queued) &&
    isSafeInteger(value.ready) &&
    isSafeInteger(value.failed) &&
    (value.detailTotal === undefined || isSafeInteger(value.detailTotal)) &&
    (value.detailReady === undefined || isSafeInteger(value.detailReady)) &&
    (value.detailFailed === undefined || isSafeInteger(value.detailFailed)) &&
    (value.profileId === undefined || typeof value.profileId === "string")
  );
}

function isExtensionError(value: unknown): value is ExtensionError {
  return (
    isRecord(value) &&
    typeof value.code === "string" &&
    ERROR_CODES.includes(value.code as ExtensionError["code"]) &&
    typeof value.message === "string" &&
    typeof value.retryable === "boolean" &&
    (value.details === undefined || isRecord(value.details))
  );
}

export function isRuntimeResponse(value: unknown, requestId: string): value is ResponseMessage {
  if (
    !isRecord(value) ||
    value.version !== MESSAGE_VERSION ||
    value.requestId !== requestId ||
    typeof value.type !== "string"
  ) {
    return false;
  }
  switch (value.type) {
    case "ACK":
      return typeof value.acknowledgedType === "string";
    case "SESSION_STATUS":
      return isSessionStatus(value.status);
    case "CORE_RESULT":
      return Array.isArray(value.analyses) && value.analyses.every(isCoreAnalysis);
    case "DETAIL_RESULT":
      return isDetailAnalysis(value.analysis);
    case "SENTENCE_DETAILS_RESULT":
      return isSafeInteger(value.succeeded) && isSafeInteger(value.failed);
    case "CACHE_STATS":
      return (
        isRecord(value.stats) &&
        isSafeInteger(value.stats.entries) &&
        isSafeInteger(value.stats.estimatedBytes) &&
        isSafeInteger(value.stats.limitBytes)
      );
    case "PROFILE_TEST_RESULT":
      return (
        typeof value.profileId === "string" &&
        typeof value.success === "boolean" &&
        (value.latencyMs === undefined || isSafeInteger(value.latencyMs)) &&
        (value.jsonSchemaSupport === undefined ||
          value.jsonSchemaSupport === "supported" ||
          value.jsonSchemaSupport === "unsupported") &&
        (value.error === undefined || isExtensionError(value.error))
      );
    case "ERROR":
      return isExtensionError(value.error);
    default:
      return false;
  }
}

interface RuntimeWatchdogPort {
  onDisconnect: { addListener(listener: () => void): void };
  disconnect(): void;
}

export interface ContentRuntimeApi {
  connect(connectInfo: { name: string }): RuntimeWatchdogPort;
  sendMessage(message: unknown): Promise<unknown>;
}

function chromeRuntimeApi(): ContentRuntimeApi {
  return {
    connect: (connectInfo) => {
      const port = chrome.runtime.connect(connectInfo);
      return {
        disconnect: () => port.disconnect(),
        onDisconnect: {
          addListener: (listener) =>
            port.onDisconnect.addListener(() => {
              // 页面进 bfcache 时 Chrome 会关闭端口并设置 lastError;onDisconnect
              // 回调里不读它就会在控制台打 "Unchecked runtime.lastError"。
              void chrome.runtime.lastError;
              listener();
            }),
        },
      };
    },
    sendMessage: (message) => chrome.runtime.sendMessage(message),
  };
}

export class ChromeRuntimeTransport implements RuntimeTransport {
  private requestCounter = 0;
  private readonly disconnectHandlers = new Set<() => void>();
  private watchdog?: RuntimeWatchdogPort;

  constructor(
    private readonly tabId: number,
    private readonly documentId: string,
    private readonly runtime: ContentRuntimeApi = chromeRuntimeApi(),
  ) {
    this.connectWatchdog();
  }

  async send(message: RequestMessage): Promise<ResponseMessage> {
    const response = await this.runtime.sendMessage(message);
    if (!isRuntimeResponse(response, message.requestId)) {
      return invalidMessage(message.requestId);
    }
    return response;
  }

  cancelDocument(): void {
    const message: RequestMessage = {
      version: MESSAGE_VERSION,
      requestId: `${this.documentId}:cancel:${++this.requestCounter}`,
      type: "STOP_SESSION",
      tabId: this.tabId,
      documentId: this.documentId,
    };
    void this.runtime.sendMessage(message).catch(() => undefined);
  }

  onDisconnect(handler: () => void): () => void {
    this.disconnectHandlers.add(handler);
    return () => this.disconnectHandlers.delete(handler);
  }

  reconnect(): void {
    this.connectWatchdog();
  }

  dispose(): void {
    const watchdog = this.watchdog;
    this.watchdog = undefined;
    this.disconnectHandlers.clear();
    watchdog?.disconnect();
  }

  private connectWatchdog(): void {
    const watchdog = this.runtime.connect({ name: `syntax-learning:${this.documentId}` });
    this.watchdog = watchdog;
    watchdog.onDisconnect.addListener(() => {
      if (this.watchdog !== watchdog) return;
      this.watchdog = undefined;
      for (const handler of this.disconnectHandlers) handler();
    });
  }
}

export class ContentScriptRouter {
  private readonly controllers = new Map<string, RoutedController>();

  constructor(private readonly options: ContentScriptRouterOptions = {}) {}

  async route(value: unknown): Promise<ResponseMessage> {
    if (!isRequestMessage(value) || !("documentId" in value) || !("tabId" in value)) {
      const requestId =
        typeof value === "object" &&
        value !== null &&
        typeof (value as { requestId?: unknown }).requestId === "string"
          ? (value as { requestId: string }).requestId
          : undefined;
      return invalidMessage(requestId);
    }
    const request = value;
    if (
      request.type === "ANALYZE_CORE" ||
      request.type === "ANALYZE_DETAIL" ||
      request.type === "REANALYZE_WITH_FEEDBACK"
    ) {
      return invalidMessage(request.requestId);
    }
    const controller = this.controller(request.tabId, request.documentId);
    switch (request.type) {
      case "START_SESSION":
        await controller.start({ prefetchDetail: request.prefetchDetail === true });
        return statusResponse(request.requestId, controller.status);
      case "PAUSE_SESSION":
        controller.pause();
        return statusResponse(request.requestId, controller.status);
      case "STOP_SESSION":
        controller.stop();
        this.controllers.delete(request.documentId);
        return statusResponse(request.requestId, controller.status);
      case "GET_SESSION_STATUS":
        return statusResponse(request.requestId, controller.status);
      case "PARSE_SELECTION": {
        const error = await controller.parseSelection(request.selectionText);
        return error === undefined ? ack(request) : errorResponse(request.requestId, error);
      }
      case "PARSE_CONTEXT_BLOCK": {
        const error = await controller.parseContextBlock();
        return error === undefined ? ack(request) : errorResponse(request.requestId, error);
      }
      case "REANALYZE_VISIBLE":
        controller.reanalyzeVisible();
        return statusResponse(request.requestId, controller.status);
      case "SWITCH_PROFILE":
        controller.switchProfile(request.profileId);
        return ack(request);
      default:
        return invalidMessage();
    }
  }

  private controller(tabId: number, documentId: string): RoutedController {
    const existing = this.controllers.get(documentId);
    if (existing !== undefined) return existing;
    const transport = (
      this.options.transportFactory ??
      ((nextTabId, nextDocumentId) => new ChromeRuntimeTransport(nextTabId, nextDocumentId))
    )(tabId, documentId);
    const controller = (
      this.options.controllerFactory ??
      ((controllerOptions) => new SessionController(controllerOptions))
    )({
      tabId,
      documentId,
      transport,
      onStatus: (status) => this.options.relayStatus?.(documentId, status),
      requestFeedback: () => window.prompt("请描述需要纠正的语法解析；取消则重试详细解析"),
    });
    this.controllers.set(documentId, controller);
    return controller;
  }
}

function installContentScript(): void {
  if (typeof chrome === "undefined" || chrome.runtime?.onMessage === undefined) return;
  if (document.documentElement.dataset.syntaxLearningExtension === "ready") return;
  let statusCounter = 0;
  const pill = new SyntaxProgressPill();
  const router = new ContentScriptRouter({
    relayStatus: (documentId, status) => {
      pill.update(status);
      const message: ResponseMessage = {
        version: MESSAGE_VERSION,
        requestId: `${documentId}:status:${++statusCounter}`,
        type: "SESSION_STATUS",
        status,
      };
      void chrome.runtime.sendMessage(message).catch(() => undefined);
    },
  });
  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    void router.route(message).then(sendResponse);
    return true;
  });
  document.documentElement.dataset.syntaxLearningExtension = "ready";
}

installContentScript();
