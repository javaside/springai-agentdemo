import { beforeEach, describe, expect, it, vi } from "vitest";
import { GrammarRole } from "../shared/grammar";
import type {
  RequestMessage,
  ResponseMessage,
  SentenceInput,
  SessionStatus,
} from "../shared/protocol";
import type { ModelProfile } from "./config-repository";
import type { AnalysisService } from "./analysis-service";
import { registerServiceWorker, type ServiceWorkerDependencies } from "./service-worker";

function event<T extends (...args: never[]) => unknown>() {
  const listeners: T[] = [];
  return {
    addListener: vi.fn((listener: T) => listeners.push(listener)),
    listeners,
  };
}

function chromeMock() {
  const onInstalled = event<(details: chrome.runtime.InstalledDetails) => void>();
  const onMessage =
    event<
      (
        message: unknown,
        sender: chrome.runtime.MessageSender,
        sendResponse: (response: ResponseMessage) => void,
      ) => boolean | undefined
    >();
  const onConnect = event<(port: chrome.runtime.Port) => void>();
  const onClicked = event<(tab: chrome.tabs.Tab) => void>();
  const onContextClicked =
    event<(info: chrome.contextMenus.OnClickData, tab?: chrome.tabs.Tab) => void>();
  const onRemoved = event<(tabId: number) => void>();
  const onUpdated =
    event<(tabId: number, changeInfo: chrome.tabs.OnUpdatedInfo, tab: chrome.tabs.Tab) => void>();
  const api = {
    runtime: {
      id: "extension-id",
      onInstalled,
      onMessage,
      onConnect,
    },
    action: { onClicked },
    contextMenus: {
      onClicked: onContextClicked,
      removeAll: vi.fn(() => Promise.resolve()),
      create: vi.fn(),
    },
    scripting: { executeScript: vi.fn(() => Promise.resolve([])) },
    tabs: {
      onRemoved,
      onUpdated,
      sendMessage: vi.fn<(tabId: number, message: unknown) => Promise<unknown>>(() =>
        Promise.resolve(undefined),
      ),
    },
  };
  return { api: api as unknown as typeof chrome, events: api };
}

const profiles: ModelProfile[] = [
  {
    id: "profile-a",
    name: "A",
    baseUrl: "https://a.example/v1",
    apiKey: "SECRET-A",
    model: "model-a",
    headers: { "X-Private": "HEADER-A" },
    timeoutMs: 30_000,
    jsonSchemaSupport: "supported",
  },
  {
    id: "profile-b",
    name: "B",
    baseUrl: "https://b.example/v1",
    apiKey: "SECRET-B",
    model: "model-b",
    headers: {},
    timeoutMs: 30_000,
    jsonSchemaSupport: "supported",
  },
];

function dependencies(
  analysisOverrides: Partial<AnalysisService> = {},
  availableProfiles: ModelProfile[] = profiles,
) {
  let activeProfileId = "profile-a";
  const defaultAnalyzeCore: AnalysisService["analyzeCore"] = ({ sentences, profile }) =>
    Promise.resolve({
      result: sentences.map((sentence: SentenceInput) => ({
        schemaVersion: 1,
        sentenceId: sentence.sentenceId,
        components: [
          {
            startToken: 0,
            endToken: 0,
            role: GrammarRole.SUBJECT,
            translation: "学习者",
          },
        ],
        modelProfileId: profile.id,
      })),
      failures: [],
      cacheHit: false,
    });
  const analysisService: AnalysisService = {
    analyzeCore: vi.fn(defaultAnalyzeCore),
    analyzeDetail: vi.fn<AnalysisService["analyzeDetail"]>(),
    reanalyzeWithFeedback: vi.fn<AnalysisService["reanalyzeWithFeedback"]>(),
    lookupCore: vi.fn<AnalysisService["lookupCore"]>(() => Promise.resolve([])),
    lookupDetail: vi.fn<AnalysisService["lookupDetail"]>(() => Promise.resolve(undefined)),
    ...analysisOverrides,
  };
  const deps: ServiceWorkerDependencies = {
    configRepository: {
      getProfile: vi.fn((id: string) =>
        Promise.resolve(availableProfiles.find((profile) => profile.id === id)),
      ),
      getActiveProfile: vi.fn(() =>
        Promise.resolve(availableProfiles.find((profile) => profile.id === activeProfileId)),
      ),
      setActiveProfile: vi.fn((id: string) => {
        activeProfileId = id;
        return Promise.resolve();
      }),
    },
    analysisService,
    scheduler: { cancelDocument: vi.fn() },
    cache: {
      stats: vi.fn(() => Promise.resolve({ entries: 0, estimatedBytes: 0, limitBytes: 1024 })),
      clear: vi.fn(() => Promise.resolve()),
    },
    profileProbe: vi.fn(() => Promise.resolve("supported" as const)),
  };
  return deps;
}

const sentence = {
  sentenceId: "sentence-1",
  text: "Learners read.",
  tokens: [
    {
      id: 0,
      text: "Learners",
      start: 0,
      end: 8,
      leadingWhitespace: "",
      punctuation: false,
    },
  ],
};

const emptyRunningStatus: SessionStatus = {
  state: "running",
  discovered: 0,
  queued: 0,
  ready: 0,
  failed: 0,
  profileId: "profile-a",
};

type PageRequest = Extract<RequestMessage, { tabId: number }>;
type PageRequestBody = PageRequest extends infer Message
  ? Message extends PageRequest
    ? Omit<Message, "version" | "requestId" | "tabId" | "documentId">
    : never
  : never;

function pageRequest(body: PageRequestBody, documentId = "document-1"): RequestMessage {
  return {
    ...body,
    version: 1,
    requestId: "request-1",
    tabId: 7,
    documentId,
  };
}

async function dispatch(
  listener: (
    message: unknown,
    sender: chrome.runtime.MessageSender,
    respond: (response: ResponseMessage) => void,
  ) => boolean | undefined,
  message: unknown,
  sender: chrome.runtime.MessageSender = { tab: { id: 7 } as chrome.tabs.Tab },
): Promise<ResponseMessage> {
  return new Promise((resolve) => {
    expect(listener(message, sender, resolve)).toBe(true);
  });
}

describe("service worker orchestration", () => {
  beforeEach(() => vi.clearAllMocks());

  it("creates both context menu commands exactly once on install", async () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);

    subject.events.runtime.onInstalled.listeners[0]!({ reason: "install" });
    await vi.waitFor(() => expect(subject.events.contextMenus.removeAll).toHaveBeenCalledOnce());

    expect(subject.events.contextMenus.removeAll).toHaveBeenCalledOnce();
    expect(subject.events.contextMenus.create.mock.calls).toEqual([
      [{ id: "syntax-parse-selection", title: "解析选中文本", contexts: ["selection"] }],
      [{ id: "syntax-parse-context-block", title: "解析此区域", contexts: ["page"] }],
    ]);
  });

  it("injects only after an explicit action command and then starts the page", async () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);
    expect(subject.events.scripting.executeScript).not.toHaveBeenCalled();

    subject.events.action.onClicked.listeners[0]!({ id: 7 } as chrome.tabs.Tab);
    await vi.waitFor(() => expect(subject.events.tabs.sendMessage).toHaveBeenCalledOnce());

    expect(subject.events.scripting.executeScript).toHaveBeenCalledWith({
      target: { tabId: 7 },
      files: ["content-script.js"],
    });
    expect(subject.events.tabs.sendMessage).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ type: "START_SESSION", tabId: 7 }),
    );
  });

  it("accepts a popup start command as an explicit extension UI action", async () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);

    const response = await dispatch(
      subject.events.runtime.onMessage.listeners[0]!,
      pageRequest({ type: "START_SESSION" }),
      { id: "extension-id", url: "chrome-extension://extension-id/src/popup/popup.html" },
    );

    expect(response.type).toBe("SESSION_STATUS");
    expect(subject.events.scripting.executeScript).toHaveBeenCalledOnce();
    expect(subject.events.tabs.sendMessage).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ type: "START_SESSION", documentId: "document-1" }),
    );
  });

  it("forwards an explicit popup visible-area reanalysis to the active document", async () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);
    const listener = subject.events.runtime.onMessage.listeners[0]!;
    const popup = {
      id: "extension-id",
      url: "chrome-extension://extension-id/src/popup/popup.html",
    };
    await dispatch(listener, pageRequest({ type: "START_SESSION" }), popup);
    subject.events.tabs.sendMessage.mockClear();

    const response = await dispatch(listener, pageRequest({ type: "REANALYZE_VISIBLE" }), popup);

    expect(response).toMatchObject({ type: "SESSION_STATUS" });
    expect(subject.events.tabs.sendMessage).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ type: "REANALYZE_VISIBLE", documentId: "document-1" }),
    );
  });

  it("binds trusted popup status and controls to the document started by another UI", async () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);
    subject.events.action.onClicked.listeners[0]!({ id: 7 } as chrome.tabs.Tab);
    await vi.waitFor(() => expect(subject.events.tabs.sendMessage).toHaveBeenCalledOnce());
    const activeRequest = subject.events.tabs.sendMessage.mock.calls[0]![1] as PageRequest;
    subject.events.tabs.sendMessage.mockClear();
    const popup = {
      id: "extension-id",
      url: "chrome-extension://extension-id/src/popup/popup.html",
    };

    const status = await dispatch(
      subject.events.runtime.onMessage.listeners[0]!,
      pageRequest({ type: "GET_SESSION_STATUS" }, "popup-tab-7"),
      popup,
    );
    const reanalysis = await dispatch(
      subject.events.runtime.onMessage.listeners[0]!,
      pageRequest({ type: "REANALYZE_VISIBLE" }, "popup-tab-7"),
      popup,
    );

    expect(status).toMatchObject({ type: "SESSION_STATUS", status: { state: "running" } });
    expect(reanalysis).toMatchObject({ type: "SESSION_STATUS" });
    expect(subject.events.tabs.sendMessage).toHaveBeenCalledWith(
      7,
      expect.objectContaining({
        type: "REANALYZE_VISIBLE",
        documentId: activeRequest.documentId,
      }),
    );
  });

  it("runs a real profile probe and returns its JSON capability without secrets", async () => {
    const deps = dependencies();
    const subject = chromeMock();
    registerServiceWorker(deps, subject.api);

    const response = await dispatch(subject.events.runtime.onMessage.listeners[0]!, {
      version: 1,
      requestId: "test-profile-1",
      type: "TEST_PROFILE",
      profileId: "profile-a",
    });

    expect(deps.profileProbe).toHaveBeenCalledWith(
      expect.objectContaining({ id: "profile-a", apiKey: "SECRET-A" }),
      expect.any(AbortSignal),
    );
    expect(response).toMatchObject({
      type: "PROFILE_TEST_RESULT",
      success: true,
      jsonSchemaSupport: "supported",
    });
    expect(JSON.stringify(response)).not.toMatch(/SECRET-A|HEADER-A|apiKey|Authorization/);
  });

  it.each(["NETWORK_ERROR", "AUTH_FAILED", "MODEL_NOT_FOUND", "INVALID_MODEL_OUTPUT"] as const)(
    "maps a %s profile probe failure without leaking its message",
    async (code) => {
      const deps = dependencies();
      vi.mocked(deps.profileProbe).mockRejectedValue(
        Object.assign(new Error("SECRET-A HEADER-A"), { code }),
      );
      const subject = chromeMock();
      registerServiceWorker(deps, subject.api);

      const response = await dispatch(subject.events.runtime.onMessage.listeners[0]!, {
        version: 1,
        requestId: "test-profile-error",
        type: "TEST_PROFILE",
        profileId: "profile-a",
      });

      expect(response).toMatchObject({
        type: "PROFILE_TEST_RESULT",
        success: false,
        error: { code },
      });
      expect(JSON.stringify(response)).not.toMatch(/SECRET-A|HEADER-A/);
    },
  );

  it("assigns a fresh background document ID after navigation in the same tab", async () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);
    const action = subject.events.action.onClicked.listeners[0]!;

    action({ id: 7 } as chrome.tabs.Tab);
    await vi.waitFor(() => expect(subject.events.tabs.sendMessage).toHaveBeenCalledTimes(1));
    const first = subject.events.tabs.sendMessage.mock.calls[0]![1] as PageRequest;
    subject.events.tabs.onUpdated.listeners[0]!(7, { status: "loading" }, {
      id: 7,
    } as chrome.tabs.Tab);
    action({ id: 7 } as chrome.tabs.Tab);
    await vi.waitFor(() => expect(subject.events.tabs.sendMessage).toHaveBeenCalledTimes(2));
    const second = subject.events.tabs.sendMessage.mock.calls[1]![1] as PageRequest;

    expect(second.documentId).not.toBe(first.documentId);
  });

  it.each([
    ["malformed", { type: "ANALYZE_CORE", requestId: "bad" }],
    ["version-mismatched", { version: 2, type: "GET_CACHE_STATS", requestId: "bad" }],
  ])("returns ERROR for a %s message", async (_description, message) => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);

    await expect(
      dispatch(subject.events.runtime.onMessage.listeners[0]!, message),
    ).resolves.toMatchObject({ type: "ERROR", requestId: "bad" });
  });

  it("rejects a page message whose sender tab does not match", async () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);

    const response = await dispatch(
      subject.events.runtime.onMessage.listeners[0]!,
      pageRequest({ type: "ANALYZE_CORE", sentences: [sentence] }),
      { tab: { id: 8 } as chrome.tabs.Tab },
    );

    expect(response).toMatchObject({ type: "ERROR", error: { code: "UNSUPPORTED_PAGE" } });
  });

  it("rejects a stale document request without disturbing the active document", async () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);
    const listener = subject.events.runtime.onMessage.listeners[0]!;
    await dispatch(listener, {
      version: 1,
      requestId: "status-1",
      type: "SESSION_STATUS",
      tabId: 7,
      documentId: "document-current",
      status: emptyRunningStatus,
    });

    const response = await dispatch(
      listener,
      pageRequest({ type: "ANALYZE_CORE", sentences: [sentence] }, "document-stale"),
    );

    expect(response).toMatchObject({ type: "ERROR", error: { code: "REQUEST_CANCELLED" } });
  });

  it("rejects a stale status relay instead of replacing the current document", async () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);
    const listener = subject.events.runtime.onMessage.listeners[0]!;
    await dispatch(listener, {
      version: 1,
      requestId: "status-current",
      type: "SESSION_STATUS",
      tabId: 7,
      documentId: "document-current",
      status: emptyRunningStatus,
    });

    const stale = await dispatch(listener, {
      version: 1,
      requestId: "status-stale",
      type: "SESSION_STATUS",
      tabId: 7,
      documentId: "document-stale",
      status: { ...emptyRunningStatus, discovered: 99 },
    });
    const current = await dispatch(
      listener,
      pageRequest({ type: "GET_SESSION_STATUS" }, "document-current"),
    );

    expect(stale).toMatchObject({ type: "ERROR", error: { code: "REQUEST_CANCELLED" } });
    expect(current).toMatchObject({ type: "SESSION_STATUS", status: { discovered: 0 } });
  });

  it("never exposes profile keys, headers, or surplus service fields in core/detail responses", async () => {
    const leakyCore = {
      schemaVersion: 1 as const,
      sentenceId: sentence.sentenceId,
      components: [
        {
          startToken: 0,
          endToken: 0,
          role: GrammarRole.SUBJECT,
          translation: "学习者 SECRET-A HEADER-A",
          apiKey: "SECRET-A",
        },
      ],
      modelProfileId: "profile-a",
      headers: { Authorization: "SECRET-A" },
    };
    const requestCore = {
      schemaVersion: 1 as const,
      sentenceId: sentence.sentenceId,
      components: [
        {
          startToken: 0,
          endToken: 0,
          role: GrammarRole.SUBJECT,
          translation: "学习者",
        },
      ],
      modelProfileId: "profile-a",
    };
    const leakyDetail = {
      sentenceId: sentence.sentenceId,
      focus: { startToken: 0, endToken: 0 },
      structures: [
        {
          startToken: 0,
          endToken: 0,
          role: "subject",
          explanation: "Subject SECRET-A",
          apiKey: "SECRET-A",
        },
      ],
      grammarPoints: ["subject"],
      explanation: "Detail HEADER-A",
      modelProfileId: "profile-a",
      apiKey: "SECRET-A",
    };
    const deps = dependencies({
      analyzeCore: vi.fn(() =>
        Promise.resolve({
          result: [leakyCore],
          failures: [],
          cacheHit: false,
        }),
      ),
      analyzeDetail: vi.fn(() => Promise.resolve({ result: leakyDetail, cacheHit: false })),
    });
    const subject = chromeMock();
    registerServiceWorker(deps, subject.api);
    const listener = subject.events.runtime.onMessage.listeners[0]!;

    const core = await dispatch(
      listener,
      pageRequest({ type: "ANALYZE_CORE", sentences: [sentence] }),
    );
    const detail = await dispatch(
      listener,
      pageRequest({
        type: "ANALYZE_DETAIL",
        sentence,
        core: requestCore,
        focus: { startToken: 0, endToken: 0 },
      }),
    );

    expect(JSON.stringify([core, detail])).not.toMatch(/SECRET-A|Authorization|apiKey|headers/);
  });

  it("injects on first selection-menu use and forwards Chrome selectionText", async () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);

    subject.events.contextMenus.onClicked.listeners[0]!(
      {
        menuItemId: "syntax-parse-selection",
        selectionText: "Learners read.",
        editable: false,
      },
      { id: 7 } as chrome.tabs.Tab,
    );
    await vi.waitFor(() => expect(subject.events.tabs.sendMessage).toHaveBeenCalledOnce());

    expect(subject.events.scripting.executeScript).toHaveBeenCalledOnce();
    expect(subject.events.tabs.sendMessage).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ type: "PARSE_SELECTION", selectionText: "Learners read." }),
    );
  });

  it("returns Task 11's instruction when region parsing has no active recorded target", () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);

    const response = subject.events.contextMenus.onClicked.listeners[0]!(
      { menuItemId: "syntax-parse-context-block", editable: false },
      { id: 7 } as chrome.tabs.Tab,
    );

    expect(response).toMatchObject({
      type: "ERROR",
      error: {
        code: "UNSAFE_CONTENT_BLOCK",
        message: "请先启动学习模式，或选中文字后解析",
      },
    });
    expect(subject.events.scripting.executeScript).not.toHaveBeenCalled();
  });

  it("sends only a trigger for region parsing when the content script is active", async () => {
    const subject = chromeMock();
    registerServiceWorker(dependencies(), subject.api);
    await dispatch(subject.events.runtime.onMessage.listeners[0]!, {
      version: 1,
      requestId: "status-active",
      type: "SESSION_STATUS",
      tabId: 7,
      documentId: "document-1",
      status: emptyRunningStatus,
    });

    subject.events.contextMenus.onClicked.listeners[0]!(
      { menuItemId: "syntax-parse-context-block", editable: false },
      { id: 7 } as chrome.tabs.Tab,
    );
    await vi.waitFor(() => expect(subject.events.tabs.sendMessage).toHaveBeenCalledOnce());

    expect(subject.events.scripting.executeScript).not.toHaveBeenCalled();
    expect(subject.events.tabs.sendMessage).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ type: "PARSE_CONTEXT_BLOCK", documentId: "document-1" }),
    );
  });

  it("cancels only the recorded document on tab close and navigation", async () => {
    const subject = chromeMock();
    const deps = dependencies();
    registerServiceWorker(deps, subject.api);
    const listener = subject.events.runtime.onMessage.listeners[0]!;
    const status: SessionStatus = {
      state: "running",
      discovered: 1,
      queued: 0,
      ready: 1,
      failed: 0,
      profileId: "profile-a",
    };
    await dispatch(listener, {
      version: 1,
      requestId: "status-1",
      type: "SESSION_STATUS",
      status,
      tabId: 7,
      documentId: "document-1",
    });

    subject.events.tabs.onUpdated.listeners[0]!(7, { status: "loading" }, {
      id: 7,
    } as chrome.tabs.Tab);
    await dispatch(listener, {
      version: 1,
      requestId: "status-2",
      type: "SESSION_STATUS",
      status,
      tabId: 7,
      documentId: "document-2",
    });
    subject.events.tabs.onRemoved.listeners[0]!(7);

    // The injected scheduler port is intentionally represented by a standalone mock function.
    // eslint-disable-next-line @typescript-eslint/unbound-method
    const cancelDocument = vi.mocked(deps.scheduler.cancelDocument);
    expect(cancelDocument).toHaveBeenNthCalledWith(1, "document-1");
    expect(cancelDocument).toHaveBeenNthCalledWith(2, "document-2");
  });

  it("binds a content-script Port to its sender tab and cancels that document on disconnect", () => {
    const subject = chromeMock();
    const deps = dependencies();
    registerServiceWorker(deps, subject.api);
    const onDisconnect = event<() => void>();
    const port = {
      name: "syntax-learning:document-port",
      sender: { tab: { id: 7 } as chrome.tabs.Tab },
      onDisconnect,
    } as unknown as chrome.runtime.Port;

    subject.events.runtime.onConnect.listeners[0]!(port);
    onDisconnect.listeners[0]!();

    // eslint-disable-next-line @typescript-eslint/unbound-method
    expect(deps.scheduler.cancelDocument).toHaveBeenCalledWith("document-port");
  });

  it("pauses new analysis only for the profile that receives a 401", async () => {
    const analyzeCore = vi.fn(({ profile }: Parameters<AnalysisService["analyzeCore"]>[0]) => {
      if (profile.id === "profile-a") {
        return Promise.reject(
          Object.assign(new Error("SECRET-A"), {
            code: "AUTH_FAILED",
            retryable: false,
            details: { status: 401 },
          }),
        );
      }
      return Promise.resolve({ result: [], failures: [], cacheHit: false });
    });
    const deps = dependencies({ analyzeCore });
    const subject = chromeMock();
    registerServiceWorker(deps, subject.api);
    const listener = subject.events.runtime.onMessage.listeners[0]!;

    await dispatch(listener, pageRequest({ type: "ANALYZE_CORE", sentences: [sentence] }));
    await dispatch(listener, pageRequest({ type: "SWITCH_PROFILE", profileId: "profile-b" }));
    const profileB = await dispatch(
      listener,
      pageRequest({ type: "ANALYZE_CORE", sentences: [sentence] }),
    );
    await dispatch(listener, pageRequest({ type: "SWITCH_PROFILE", profileId: "profile-a" }));
    const profileA = await dispatch(
      listener,
      pageRequest({ type: "ANALYZE_CORE", sentences: [sentence] }),
    );

    expect(profileB.type).toBe("CORE_RESULT");
    expect(profileA).toMatchObject({ type: "ERROR", error: { code: "AUTH_FAILED" } });
    expect(analyzeCore).toHaveBeenCalledTimes(2);
    expect(JSON.stringify(profileA)).not.toContain("SECRET-A");
  });

  it("ANALYZE_CORE returns cache hits with cacheOnly instead of CONFIG_MISSING", async () => {
    const cachedAnalysis = {
      schemaVersion: 1 as const,
      sentenceId: sentence.sentenceId,
      components: [
        { startToken: 0, endToken: 0, role: GrammarRole.SUBJECT, translation: "学习者" },
      ],
      modelProfileId: "cached",
    };
    const lookupCore = vi.fn<AnalysisService["lookupCore"]>(() =>
      Promise.resolve([cachedAnalysis]),
    );
    const subject = chromeMock();
    registerServiceWorker(dependencies({ lookupCore }, []), subject.api);

    const response = await dispatch(
      subject.events.runtime.onMessage.listeners[0]!,
      pageRequest({ type: "ANALYZE_CORE", sentences: [sentence] }),
    );

    expect(response).toMatchObject({ type: "CORE_RESULT", cacheOnly: true });
    expect((response as Extract<ResponseMessage, { type: "CORE_RESULT" }>).analyses).toHaveLength(
      1,
    );
    expect(lookupCore).toHaveBeenCalledWith([sentence]);
  });

  it("ANALYZE_DETAIL returns NO_CACHE on a miss and DETAIL_RESULT on a hit", async () => {
    const requestCore = {
      schemaVersion: 1 as const,
      sentenceId: sentence.sentenceId,
      components: [
        { startToken: 0, endToken: 0, role: GrammarRole.SUBJECT, translation: "学习者" },
      ],
      modelProfileId: "profile-a",
    };
    const cachedDetail = {
      sentenceId: sentence.sentenceId,
      focus: { startToken: 0, endToken: 0 },
      structures: [{ startToken: 0, endToken: 0, role: "subject", explanation: "主语结构" }],
      grammarPoints: ["主谓结构"],
      explanation: "详细说明。",
      modelProfileId: "cached",
    };
    const lookupDetail = vi
      .fn<AnalysisService["lookupDetail"]>()
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce(cachedDetail);
    const subject = chromeMock();
    registerServiceWorker(dependencies({ lookupDetail }, []), subject.api);
    const listener = subject.events.runtime.onMessage.listeners[0]!;
    const request = pageRequest({
      type: "ANALYZE_DETAIL",
      sentence,
      core: requestCore,
      focus: { startToken: 0, endToken: 0 },
    });

    const miss = await dispatch(listener, request);
    const hit = await dispatch(listener, request);

    expect(miss).toMatchObject({ type: "ERROR", error: { code: "NO_CACHE" } });
    expect(hit).toMatchObject({ type: "DETAIL_RESULT" });
  });

  it("REANALYZE_WITH_FEEDBACK still requires a profile", async () => {
    const requestCore = {
      schemaVersion: 1 as const,
      sentenceId: sentence.sentenceId,
      components: [
        { startToken: 0, endToken: 0, role: GrammarRole.SUBJECT, translation: "学习者" },
      ],
      modelProfileId: "profile-a",
    };
    const subject = chromeMock();
    registerServiceWorker(dependencies({}, []), subject.api);

    const response = await dispatch(
      subject.events.runtime.onMessage.listeners[0]!,
      pageRequest({
        type: "REANALYZE_WITH_FEEDBACK",
        sentence,
        core: requestCore,
        feedback: "Treat read as the predicate.",
      }),
    );

    expect(response).toMatchObject({ type: "ERROR", error: { code: "CONFIG_MISSING" } });
  });

  it("resumes a paused profile after its credentials change", async () => {
    const availableProfiles = profiles.map((profile) => structuredClone(profile));
    const analyzeCore = vi.fn(({ profile }: Parameters<AnalysisService["analyzeCore"]>[0]) =>
      profile.apiKey === "SECRET-A"
        ? Promise.reject(
            Object.assign(new Error("unauthorized"), {
              code: "AUTH_FAILED",
              retryable: false,
              details: { status: 401 },
            }),
          )
        : Promise.resolve({ result: [], failures: [], cacheHit: false }),
    );
    const deps = dependencies({ analyzeCore }, availableProfiles);
    const subject = chromeMock();
    registerServiceWorker(deps, subject.api);
    const listener = subject.events.runtime.onMessage.listeners[0]!;

    await dispatch(listener, pageRequest({ type: "ANALYZE_CORE", sentences: [sentence] }));
    availableProfiles[0] = { ...availableProfiles[0]!, apiKey: "UPDATED-A" };
    const retried = await dispatch(
      listener,
      pageRequest({ type: "ANALYZE_CORE", sentences: [sentence] }),
    );

    expect(retried.type).toBe("CORE_RESULT");
    expect(analyzeCore).toHaveBeenCalledTimes(2);
  });
});
