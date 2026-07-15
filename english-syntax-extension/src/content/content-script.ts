import type { ExtensionError } from "../shared/errors";
import { isRequestMessage } from "../shared/protocol";
import type { RequestMessage, ResponseMessage, SessionStatus } from "../shared/protocol";
import { MESSAGE_VERSION } from "../shared/versions";
import { SessionController } from "./session-controller";
import type { RuntimeTransport, SessionControllerOptions } from "./session-controller";

interface RoutedController {
  readonly status: SessionStatus;
  start(): Promise<void>;
  pause(): void;
  resume(): void;
  stop(): void;
  parseSelection(selectionText: string): Promise<ExtensionError | undefined>;
  parseContextBlock(): Promise<ExtensionError | undefined>;
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

class ChromeRuntimeTransport implements RuntimeTransport {
  private requestCounter = 0;

  constructor(
    private readonly tabId: number,
    private readonly documentId: string,
  ) {}

  async send(message: RequestMessage): Promise<ResponseMessage> {
    const response: unknown = await chrome.runtime.sendMessage(message);
    if (
      typeof response !== "object" ||
      response === null ||
      (response as { version?: unknown }).version !== MESSAGE_VERSION ||
      (response as { requestId?: unknown }).requestId !== message.requestId ||
      typeof (response as { type?: unknown }).type !== "string"
    ) {
      return invalidMessage(message.requestId);
    }
    return response as ResponseMessage;
  }

  cancelDocument(): void {
    const message: RequestMessage = {
      version: MESSAGE_VERSION,
      requestId: `${this.documentId}:cancel:${++this.requestCounter}`,
      type: "STOP_SESSION",
      tabId: this.tabId,
      documentId: this.documentId,
    };
    void chrome.runtime.sendMessage(message).catch(() => undefined);
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
        await controller.start();
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
    });
    this.controllers.set(documentId, controller);
    return controller;
  }
}

function installContentScript(): void {
  if (typeof chrome === "undefined" || chrome.runtime?.onMessage === undefined) return;
  if (document.documentElement.dataset.syntaxLearningExtension === "ready") return;
  let statusCounter = 0;
  const router = new ContentScriptRouter({
    relayStatus: (documentId, status) => {
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
