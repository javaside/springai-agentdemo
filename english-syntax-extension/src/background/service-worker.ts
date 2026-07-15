import type { ExtensionError, ExtensionErrorCode } from "../shared/errors";
import type { CoreAnalysis, DetailAnalysis } from "../shared/grammar";
import { assertNever, isRequestMessage } from "../shared/protocol";
import type {
  CacheStats,
  RequestMessage,
  ResponseMessage,
  SessionStatus,
} from "../shared/protocol";
import { MESSAGE_VERSION } from "../shared/versions";
import { AnalysisCache } from "./analysis-cache";
import { CachedAnalysisService } from "./analysis-service";
import type { AnalysisModelWork, AnalysisService } from "./analysis-service";
import { hostPermissionPattern } from "./base-url";
import { ConfigRepository } from "./config-repository";
import type { ModelProfile } from "./config-repository";
import { OpenAiCompatibleAdapter } from "./openai-compatible-adapter";
import { RequestScheduler } from "./request-scheduler";

const SELECTION_MENU_ID = "syntax-parse-selection";
const CONTEXT_BLOCK_MENU_ID = "syntax-parse-context-block";
const CONTENT_SCRIPT_FILE = "content-script.js";
const CONTEXT_INSTRUCTION = "请先启动学习模式，或选中文字后解析";

interface ConfigPort {
  getProfile(profileId: string): Promise<ModelProfile | undefined>;
  getActiveProfile(): Promise<ModelProfile | undefined>;
  setActiveProfile(profileId: string): Promise<void>;
}

interface SchedulerPort {
  cancelDocument(documentId: string): void;
}

interface CachePort {
  stats(): Promise<CacheStats>;
  clear(): Promise<void>;
}

export interface ServiceWorkerDependencies {
  configRepository: ConfigPort;
  analysisService: AnalysisService;
  scheduler: SchedulerPort;
  cache: CachePort;
}

interface ActiveDocument {
  documentId: string;
  status: SessionStatus;
}

interface StatusRelay {
  version: typeof MESSAGE_VERSION;
  requestId: string;
  type: "SESSION_STATUS";
  status: SessionStatus;
  tabId?: number;
  documentId?: string;
}

function emptyStatus(state: SessionStatus["state"], profileId?: string): SessionStatus {
  return {
    state,
    discovered: 0,
    queued: 0,
    ready: 0,
    failed: 0,
    ...(profileId === undefined ? {} : { profileId }),
  };
}

function requestIdOf(value: unknown): string {
  if (typeof value !== "object" || value === null) return "invalid-message";
  const requestId = (value as { requestId?: unknown }).requestId;
  return typeof requestId === "string" && requestId.length > 0 ? requestId : "invalid-message";
}

const ERROR_MESSAGES: Record<ExtensionErrorCode, string> = {
  CONFIG_MISSING: "No active model profile is configured",
  HOST_PERMISSION_DENIED: "Host permission was denied",
  AUTH_FAILED: "Model profile authentication failed; update its credentials to resume",
  MODEL_NOT_FOUND: "The configured model was not found",
  RATE_LIMITED: "The model provider rate-limited the request",
  NETWORK_ERROR: "The model request failed",
  REQUEST_TIMEOUT: "The model request timed out",
  INVALID_MODEL_OUTPUT: "Invalid or unsupported extension message",
  UNSUPPORTED_PAGE: "The message sender does not match the target tab",
  UNSAFE_CONTENT_BLOCK: CONTEXT_INSTRUCTION,
  SENTENCE_TOO_LONG: "The sentence is too long",
  REQUEST_CANCELLED: "The request was cancelled",
};

function errorResponse(
  requestId: string,
  code: ExtensionErrorCode,
  details?: Record<string, string | number | boolean>,
): Extract<ResponseMessage, { type: "ERROR" }> {
  return {
    version: MESSAGE_VERSION,
    requestId,
    type: "ERROR",
    error: {
      code,
      message: ERROR_MESSAGES[code],
      retryable: code === "RATE_LIMITED" || code === "NETWORK_ERROR" || code === "REQUEST_TIMEOUT",
      ...(details === undefined ? {} : { details }),
    },
  };
}

function errorCode(value: unknown): ExtensionErrorCode {
  if (typeof value !== "object" || value === null) return "NETWORK_ERROR";
  const code = (value as Partial<ExtensionError>).code;
  return typeof code === "string" && code in ERROR_MESSAGES ? code : "NETWORK_ERROR";
}

function sanitizeCore(analysis: CoreAnalysis): CoreAnalysis {
  return {
    schemaVersion: analysis.schemaVersion,
    sentenceId: analysis.sentenceId,
    components: analysis.components.map((component) => ({
      startToken: component.startToken,
      endToken: component.endToken,
      role: component.role,
      translation: component.translation,
    })),
    modelProfileId: analysis.modelProfileId,
  };
}

function sanitizeDetail(analysis: DetailAnalysis): DetailAnalysis {
  return {
    sentenceId: analysis.sentenceId,
    focus: {
      startToken: analysis.focus.startToken,
      endToken: analysis.focus.endToken,
    },
    structures: analysis.structures.map((structure) => ({
      startToken: structure.startToken,
      endToken: structure.endToken,
      role: structure.role,
      explanation: structure.explanation,
    })),
    grammarPoints: [...analysis.grammarPoints],
    explanation: analysis.explanation,
    modelProfileId: analysis.modelProfileId,
  };
}

function isStatus(value: unknown): value is SessionStatus {
  if (typeof value !== "object" || value === null) return false;
  const status = value as Partial<SessionStatus>;
  return (
    (status.state === "stopped" || status.state === "running" || status.state === "paused") &&
    [status.discovered, status.queued, status.ready, status.failed].every(
      (count) => Number.isSafeInteger(count) && (count as number) >= 0,
    ) &&
    (status.profileId === undefined || typeof status.profileId === "string")
  );
}

function isStatusRelay(value: unknown): value is StatusRelay {
  if (typeof value !== "object" || value === null) return false;
  const relay = value as Partial<StatusRelay>;
  return (
    relay.version === MESSAGE_VERSION &&
    typeof relay.requestId === "string" &&
    relay.type === "SESSION_STATUS" &&
    isStatus(relay.status) &&
    (relay.tabId === undefined || Number.isSafeInteger(relay.tabId)) &&
    (relay.documentId === undefined || typeof relay.documentId === "string")
  );
}

function relayDocumentId(relay: StatusRelay): string | undefined {
  if (relay.documentId !== undefined) return relay.documentId;
  const marker = relay.requestId.lastIndexOf(":status:");
  return marker > 0 ? relay.requestId.slice(0, marker) : undefined;
}

function generatedDocumentId(tabId: number): string {
  return `tab-${tabId}`;
}

export function registerServiceWorker(
  dependencies: ServiceWorkerDependencies,
  chromeApi: typeof chrome = chrome,
): void {
  const activeTabs = new Map<number, ActiveDocument>();
  const pausedProfiles = new Set<string>();
  let commandCounter = 0;

  const cancelTab = (tabId: number): void => {
    const active = activeTabs.get(tabId);
    if (active === undefined) return;
    dependencies.scheduler.cancelDocument(active.documentId);
    activeTabs.delete(tabId);
  };

  const inject = async (tabId: number): Promise<void> => {
    await chromeApi.scripting.executeScript({
      target: { tabId },
      files: [CONTENT_SCRIPT_FILE],
    });
  };

  const sendPageCommand = async (
    tabId: number,
    documentId: string,
    body:
      | { type: "START_SESSION" }
      | { type: "PARSE_SELECTION"; selectionText: string }
      | { type: "PARSE_CONTEXT_BLOCK" },
  ): Promise<unknown> => {
    const message: RequestMessage = {
      ...body,
      version: MESSAGE_VERSION,
      requestId: `background:${tabId}:${++commandCounter}`,
      tabId,
      documentId,
    };
    return chromeApi.tabs.sendMessage(tabId, message);
  };

  const profileFor = async (tabId: number): Promise<ModelProfile | undefined> => {
    const selectedId = activeTabs.get(tabId)?.status.profileId;
    return selectedId === undefined
      ? dependencies.configRepository.getActiveProfile()
      : dependencies.configRepository.getProfile(selectedId);
  };

  const route = async (
    request: RequestMessage,
    sender: chrome.runtime.MessageSender,
  ): Promise<ResponseMessage> => {
    const trustedExtensionUi =
      sender.tab === undefined &&
      sender.id === chromeApi.runtime.id &&
      sender.url?.startsWith(`chrome-extension://${chromeApi.runtime.id}/`) === true;
    if ("tabId" in request) {
      if (sender.tab?.id !== request.tabId && !trustedExtensionUi) {
        return errorResponse(request.requestId, "UNSUPPORTED_PAGE");
      }
      const active = activeTabs.get(request.tabId);
      if (
        active !== undefined &&
        active.documentId !== request.documentId &&
        request.type !== "START_SESSION"
      ) {
        return errorResponse(request.requestId, "REQUEST_CANCELLED");
      }
    }

    try {
      switch (request.type) {
        case "ANALYZE_CORE": {
          const profile = await profileFor(request.tabId);
          if (profile === undefined) return errorResponse(request.requestId, "CONFIG_MISSING");
          if (pausedProfiles.has(profile.id))
            return errorResponse(request.requestId, "AUTH_FAILED");
          try {
            const outcome = await dependencies.analysisService.analyzeCore(
              {
                profile,
                documentId: request.documentId,
                sentences: request.sentences,
              },
              new AbortController().signal,
            );
            const authenticationFailure = outcome.failures.find(
              ({ error }) => error.code === "AUTH_FAILED",
            );
            if (authenticationFailure !== undefined) {
              pausedProfiles.add(profile.id);
              return errorResponse(request.requestId, "AUTH_FAILED");
            }
            return {
              version: MESSAGE_VERSION,
              requestId: request.requestId,
              type: "CORE_RESULT",
              analyses: outcome.result.map(sanitizeCore),
            };
          } catch (error) {
            const code = errorCode(error);
            if (code === "AUTH_FAILED") pausedProfiles.add(profile.id);
            return errorResponse(request.requestId, code);
          }
        }
        case "ANALYZE_DETAIL": {
          const profile = await profileFor(request.tabId);
          if (profile === undefined) return errorResponse(request.requestId, "CONFIG_MISSING");
          if (pausedProfiles.has(profile.id))
            return errorResponse(request.requestId, "AUTH_FAILED");
          try {
            const outcome = await dependencies.analysisService.analyzeDetail(
              {
                profile,
                documentId: request.documentId,
                sentence: request.sentence,
                core: request.core,
                focus: request.focus,
              },
              new AbortController().signal,
            );
            return {
              version: MESSAGE_VERSION,
              requestId: request.requestId,
              type: "DETAIL_RESULT",
              analysis: sanitizeDetail(outcome.result),
            };
          } catch (error) {
            const code = errorCode(error);
            if (code === "AUTH_FAILED") pausedProfiles.add(profile.id);
            return errorResponse(request.requestId, code);
          }
        }
        case "REANALYZE_WITH_FEEDBACK": {
          const profile = await profileFor(request.tabId);
          if (profile === undefined) return errorResponse(request.requestId, "CONFIG_MISSING");
          if (pausedProfiles.has(profile.id))
            return errorResponse(request.requestId, "AUTH_FAILED");
          try {
            const outcome = await dependencies.analysisService.reanalyzeWithFeedback(
              {
                profile,
                documentId: request.documentId,
                sentence: request.sentence,
                core: request.core,
                feedback: request.feedback,
                pageUrl: sender.tab?.url ?? "",
                sentenceInstanceId: request.sentence.sentenceId,
              },
              new AbortController().signal,
            );
            return {
              version: MESSAGE_VERSION,
              requestId: request.requestId,
              type: "CORE_RESULT",
              analyses: [sanitizeCore(outcome.result)],
            };
          } catch (error) {
            const code = errorCode(error);
            if (code === "AUTH_FAILED") pausedProfiles.add(profile.id);
            return errorResponse(request.requestId, code);
          }
        }
        case "SWITCH_PROFILE": {
          const profile = await dependencies.configRepository.getProfile(request.profileId);
          if (profile === undefined) return errorResponse(request.requestId, "CONFIG_MISSING");
          await dependencies.configRepository.setActiveProfile(profile.id);
          const active = activeTabs.get(request.tabId);
          activeTabs.set(request.tabId, {
            documentId: request.documentId,
            status: { ...(active?.status ?? emptyStatus("stopped")), profileId: profile.id },
          });
          return {
            version: MESSAGE_VERSION,
            requestId: request.requestId,
            type: "ACK",
            acknowledgedType: request.type,
          };
        }
        case "GET_SESSION_STATUS":
          return {
            version: MESSAGE_VERSION,
            requestId: request.requestId,
            type: "SESSION_STATUS",
            status: activeTabs.get(request.tabId)?.status ?? emptyStatus("stopped"),
          };
        case "START_SESSION": {
          if (!trustedExtensionUi) return errorResponse(request.requestId, "UNSUPPORTED_PAGE");
          const previous = activeTabs.get(request.tabId);
          if (previous !== undefined && previous.documentId !== request.documentId) {
            dependencies.scheduler.cancelDocument(previous.documentId);
          }
          await inject(request.tabId);
          const profile = await dependencies.configRepository.getActiveProfile();
          const status = emptyStatus("running", profile?.id);
          activeTabs.set(request.tabId, { documentId: request.documentId, status });
          await sendPageCommand(request.tabId, request.documentId, { type: "START_SESSION" });
          return {
            version: MESSAGE_VERSION,
            requestId: request.requestId,
            type: "SESSION_STATUS",
            status,
          };
        }
        case "PAUSE_SESSION":
        case "STOP_SESSION": {
          if (!trustedExtensionUi) return errorResponse(request.requestId, "UNSUPPORTED_PAGE");
          await inject(request.tabId);
          await chromeApi.tabs.sendMessage(request.tabId, request);
          const previous = activeTabs.get(request.tabId);
          const status = {
            ...(previous?.status ?? emptyStatus("stopped")),
            state: request.type === "PAUSE_SESSION" ? "paused" : "stopped",
          } satisfies SessionStatus;
          activeTabs.set(request.tabId, { documentId: request.documentId, status });
          if (request.type === "STOP_SESSION")
            dependencies.scheduler.cancelDocument(request.documentId);
          return {
            version: MESSAGE_VERSION,
            requestId: request.requestId,
            type: "SESSION_STATUS",
            status,
          };
        }
        case "PARSE_SELECTION": {
          if (!trustedExtensionUi) return errorResponse(request.requestId, "UNSUPPORTED_PAGE");
          await inject(request.tabId);
          await chromeApi.tabs.sendMessage(request.tabId, request);
          return {
            version: MESSAGE_VERSION,
            requestId: request.requestId,
            type: "ACK",
            acknowledgedType: request.type,
          };
        }
        case "PARSE_CONTEXT_BLOCK": {
          if (!trustedExtensionUi) return errorResponse(request.requestId, "UNSUPPORTED_PAGE");
          const active = activeTabs.get(request.tabId);
          if (active === undefined || active.status.state === "stopped") {
            return errorResponse(request.requestId, "UNSAFE_CONTENT_BLOCK");
          }
          await chromeApi.tabs.sendMessage(request.tabId, request);
          return {
            version: MESSAGE_VERSION,
            requestId: request.requestId,
            type: "ACK",
            acknowledgedType: request.type,
          };
        }
        case "TEST_PROFILE": {
          const profile = await dependencies.configRepository.getProfile(request.profileId);
          return {
            version: MESSAGE_VERSION,
            requestId: request.requestId,
            type: "PROFILE_TEST_RESULT",
            profileId: request.profileId,
            success: profile !== undefined && !pausedProfiles.has(profile.id),
            ...(profile === undefined
              ? { error: errorResponse(request.requestId, "CONFIG_MISSING").error }
              : pausedProfiles.has(profile.id)
                ? { error: errorResponse(request.requestId, "AUTH_FAILED").error }
                : {}),
          };
        }
        case "GET_CACHE_STATS":
          return {
            version: MESSAGE_VERSION,
            requestId: request.requestId,
            type: "CACHE_STATS",
            stats: await dependencies.cache.stats(),
          };
        case "CLEAR_CACHE":
          await dependencies.cache.clear();
          return {
            version: MESSAGE_VERSION,
            requestId: request.requestId,
            type: "ACK",
            acknowledgedType: request.type,
          };
        default:
          return assertNever(request);
      }
    } catch (error) {
      return errorResponse(request.requestId, errorCode(error));
    }
  };

  chromeApi.runtime.onInstalled?.addListener(() => {
    void (async () => {
      await chromeApi.contextMenus.removeAll();
      chromeApi.contextMenus.create({
        id: SELECTION_MENU_ID,
        title: "解析选中文本",
        contexts: ["selection"],
      });
      chromeApi.contextMenus.create({
        id: CONTEXT_BLOCK_MENU_ID,
        title: "解析此区域",
        contexts: ["page"],
      });
    })();
  });

  chromeApi.action?.onClicked.addListener((tab) => {
    if (tab.id === undefined) return;
    const tabId = tab.id;
    void (async () => {
      const documentId = activeTabs.get(tabId)?.documentId ?? generatedDocumentId(tabId);
      await inject(tabId);
      const profile = await dependencies.configRepository.getActiveProfile();
      activeTabs.set(tabId, {
        documentId,
        status: emptyStatus("running", profile?.id),
      });
      await sendPageCommand(tabId, documentId, { type: "START_SESSION" });
    })();
  });

  chromeApi.contextMenus?.onClicked.addListener((info, tab) => {
    const tabId = tab?.id;
    if (tabId === undefined) return;
    if (info.menuItemId === SELECTION_MENU_ID) {
      void (async () => {
        const documentId = activeTabs.get(tabId)?.documentId ?? generatedDocumentId(tabId);
        await inject(tabId);
        const profile = await dependencies.configRepository.getActiveProfile();
        activeTabs.set(tabId, {
          documentId,
          status: emptyStatus("running", profile?.id),
        });
        if (typeof info.selectionText === "string" && info.selectionText.trim().length > 0) {
          await sendPageCommand(tabId, documentId, {
            type: "PARSE_SELECTION",
            selectionText: info.selectionText,
          });
        }
      })();
      return;
    }
    if (info.menuItemId === CONTEXT_BLOCK_MENU_ID) {
      const active = activeTabs.get(tabId);
      if (active === undefined || active.status.state === "stopped") {
        return errorResponse(`background:${tabId}:${++commandCounter}`, "UNSAFE_CONTENT_BLOCK");
      }
      void sendPageCommand(tabId, active.documentId, { type: "PARSE_CONTEXT_BLOCK" });
    }
  });

  chromeApi.runtime.onMessage.addListener((value, sender, sendResponse) => {
    if (typeof sendResponse !== "function") return undefined;
    void (async () => {
      if (isStatusRelay(value)) {
        const tabId = value.tabId ?? sender.tab?.id;
        const documentId = relayDocumentId(value);
        if (tabId === undefined || sender.tab?.id !== tabId || documentId === undefined) {
          return errorResponse(value.requestId, "UNSUPPORTED_PAGE");
        }
        activeTabs.set(tabId, { documentId, status: value.status });
        return {
          version: MESSAGE_VERSION,
          requestId: value.requestId,
          type: "ACK",
          acknowledgedType: "GET_SESSION_STATUS",
        } satisfies ResponseMessage;
      }
      if (!isRequestMessage(value)) {
        return errorResponse(requestIdOf(value), "INVALID_MODEL_OUTPUT");
      }
      return route(value, sender);
    })().then(sendResponse);
    return true;
  });

  chromeApi.runtime.onConnect?.addListener((port) => {
    const tabId = port.sender?.tab?.id;
    if (tabId === undefined || !port.name.startsWith("syntax-learning:")) return;
    const documentId = port.name.slice("syntax-learning:".length);
    if (documentId.length === 0) return;
    const existing = activeTabs.get(tabId);
    activeTabs.set(tabId, {
      documentId,
      status: existing?.status ?? emptyStatus("stopped"),
    });
    port.onDisconnect.addListener(() => {
      if (activeTabs.get(tabId)?.documentId !== documentId) return;
      dependencies.scheduler.cancelDocument(documentId);
      activeTabs.delete(tabId);
    });
  });

  chromeApi.tabs?.onRemoved?.addListener((tabId) => cancelTab(tabId));
  chromeApi.tabs?.onUpdated?.addListener((tabId, changeInfo) => {
    if (changeInfo.status === "loading") cancelTab(tabId);
  });
}

export async function requestHostPermission(
  profile: Pick<ModelProfile, "baseUrl">,
): Promise<boolean> {
  return chrome.permissions.request({ origins: [hostPermissionPattern(profile.baseUrl)] });
}

async function createDefaultRuntime(): Promise<
  Pick<ServiceWorkerDependencies, "analysisService" | "scheduler" | "cache">
> {
  const configRepository = new ConfigRepository();
  const cache = await AnalysisCache.open();
  const adapter = new OpenAiCompatibleAdapter({
    persistJsonSchemaSupport: async (profileId, jsonSchemaSupport) => {
      const profile = await configRepository.getProfile(profileId);
      if (profile !== undefined)
        await configRepository.saveProfile({ ...profile, jsonSchemaSupport });
    },
  });
  const scheduler = new RequestScheduler<AnalysisModelWork, unknown>({
    fetchTask: (requests, signal) => Promise.all(requests.map(({ input }) => input.run(signal))),
  });
  return {
    cache,
    scheduler,
    analysisService: new CachedAnalysisService({ cache, adapter, scheduler }),
  };
}

function defaultDependencies(): ServiceWorkerDependencies {
  const configRepository = new ConfigRepository();
  let runtime: ReturnType<typeof createDefaultRuntime> | undefined;
  const getRuntime = () => (runtime ??= createDefaultRuntime());
  return {
    configRepository,
    analysisService: {
      analyzeCore: async (...arguments_) =>
        (await getRuntime()).analysisService.analyzeCore(...arguments_),
      analyzeDetail: async (...arguments_) =>
        (await getRuntime()).analysisService.analyzeDetail(...arguments_),
      reanalyzeWithFeedback: async (...arguments_) =>
        (await getRuntime()).analysisService.reanalyzeWithFeedback(...arguments_),
    },
    scheduler: {
      cancelDocument: (documentId) => {
        void getRuntime().then(({ scheduler }) => scheduler.cancelDocument(documentId));
      },
    },
    cache: {
      stats: async () => (await getRuntime()).cache.stats(),
      clear: async () => (await getRuntime()).cache.clear(),
    },
  };
}

async function initialize(): Promise<void> {
  if (typeof chrome === "undefined" || chrome.storage?.local === undefined) return;
  await chrome.storage.local.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
  registerServiceWorker(defaultDependencies());
}

void initialize();
