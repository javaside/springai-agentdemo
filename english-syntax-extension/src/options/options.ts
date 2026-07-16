import {
  ConfigRepository,
  type ModelProfile,
  type PublicModelProfile,
} from "../background/config-repository";
import { hostPermissionPattern, normalizeBaseUrl } from "../background/base-url";
import type { ExtensionErrorCode } from "../shared/errors";
import type { CacheStats, ResponseMessage } from "../shared/protocol";
import { MESSAGE_VERSION } from "../shared/versions";

const CACHE_LIMITS = [10, 50, 100, 200] as const;
const FORBIDDEN_HEADERS = new Set([
  "authorization",
  "host",
  "content-length",
  "origin",
  "x-syntax-request-id",
]);

export interface ProfileTestResult {
  success: boolean;
  jsonSchemaSupport?: "supported" | "unsupported";
  error?: ExtensionErrorCode;
  /** HTTP status returned by the provider, when the failure carried one. */
  status?: number;
  /** Short provider error detail for display; never contains credentials. */
  detail?: string;
}

export interface OptionsDependencies {
  listProfiles: () => Promise<PublicModelProfile[]>;
  getProfile: (profileId: string) => Promise<ModelProfile | undefined>;
  saveProfile: (profile: ModelProfile) => Promise<void>;
  requestPermission: (originPattern: string) => Promise<boolean>;
  testProfile: (profileId: string) => Promise<ProfileTestResult>;
  getCacheStats: () => Promise<CacheStats>;
  clearCache: () => Promise<void>;
  getCacheLimitMb: () => Promise<number>;
  setCacheLimitMb: (limitMb: number) => Promise<void>;
  getActiveProfileId: () => Promise<string | undefined>;
  setActiveProfile: (profileId: string) => Promise<void>;
  confirm: (message: string) => boolean;
}

function element<K extends keyof HTMLElementTagNameMap>(
  tagName: K,
  className?: string,
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tagName);
  if (className !== undefined) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function field(labelText: string, input: HTMLInputElement): HTMLDivElement {
  const wrapper = element("div", "options-page__field");
  const label = element("label", "options-page__label", labelText);
  label.htmlFor = input.id;
  wrapper.append(label, input);
  return wrapper;
}

function input(id: string, type = "text"): HTMLInputElement {
  const node = element("input", "options-page__input");
  node.id = id;
  node.type = type;
  return node;
}

function requestId(prefix: string): string {
  return `${prefix}:${crypto.randomUUID()}`;
}

function connectionMessage(result: ProfileTestResult): string {
  if (result.success) {
    return result.jsonSchemaSupport === "unsupported"
      ? "连接成功，配置可用。（该服务不提供 JSON Schema 严格模式，已自动采用兼容模式，属正常情况，不影响任何功能；DeepSeek 等多数服务均如此。）"
      : "连接成功，模型支持 JSON Schema。";
  }
  const providerDetail = (): string => {
    const parts = [
      ...(result.status === undefined ? [] : [`HTTP ${result.status}`]),
      ...(result.detail === undefined ? [] : [result.detail.slice(0, 200)]),
    ];
    return parts.length === 0 ? "" : `（${parts.join("：")}）`;
  };
  switch (result.error) {
    case "HOST_PERMISSION_DENIED":
      return "未获得模型地址访问权限，请允许后重试。";
    case "AUTH_FAILED":
      return "鉴权失败，请检查 API Key。";
    case "MODEL_NOT_FOUND":
      return `未找到指定模型，请检查 Model 名称。${providerDetail()}`;
    case "INVALID_MODEL_OUTPUT":
      return "模型未能返回有效 JSON，请更换模型或兼容服务。";
    case "REQUEST_TIMEOUT":
      return "连接超时，请检查地址或增大超时时间。";
    default:
      return result.status === undefined
        ? `网络连接失败，请检查 Base URL 和网络。${providerDetail()}`
        : `服务返回错误${providerDetail()}，请检查 Base URL、Model 和请求头。`;
  }
}

function cacheSize(bytes: number): string {
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}

export async function createOptionsPage(
  root: HTMLElement,
  dependencies: OptionsDependencies,
): Promise<void> {
  root.textContent = "";
  root.className = "options-page";
  root.dataset.focusStyle = "visible";

  const heading = element("h1", "options-page__title", "模型设置");
  const intro = element(
    "p",
    "options-page__intro",
    "配置 OpenAI-compatible 模型，并管理本地分析缓存。",
  );
  const warning = element(
    "p",
    "options-page__warning",
    "chrome.storage.local 不是系统级加密保险箱。API Key 只应保存在个人可信设备上。",
  );
  warning.setAttribute("role", "note");

  const profileSection = element("section", "options-page__panel");
  profileSection.setAttribute("aria-labelledby", "options-profile-heading");
  const profileHeading = element("h2", "options-page__section-title", "模型配置");
  profileHeading.id = "options-profile-heading";
  const savedLabel = element("label", "options-page__label", "已保存配置");
  savedLabel.htmlFor = "options-saved-profile";
  const savedSelect = element("select", "options-page__select");
  savedSelect.id = "options-saved-profile";
  savedSelect.append(element("option", undefined, "新建配置"));
  savedSelect.options[0]!.value = "";

  const form = element("form", "options-page__form");
  const idInput = input("options-profile-id", "hidden");
  const nameInput = input("options-profile-name");
  nameInput.required = true;
  const baseUrlInput = input("options-base-url", "url");
  baseUrlInput.required = true;
  baseUrlInput.placeholder = "https://api.example.com/v1";
  const apiKeyInput = input("options-api-key", "password");
  apiKeyInput.autocomplete = "off";
  // The key is never redisplayed after saving; the placeholder explains that
  // leaving the field empty keeps the stored key.
  const API_KEY_REQUIRED_HINT = "必填";
  const API_KEY_SAVED_HINT = "已保存（出于安全不回显），留空表示沿用原 Key";
  apiKeyInput.placeholder = API_KEY_REQUIRED_HINT;
  const modelInput = input("options-model");
  modelInput.required = true;
  const timeoutInput = input("options-timeout", "number");
  timeoutInput.required = true;
  timeoutInput.min = "5";
  timeoutInput.max = "120";
  timeoutInput.value = "45";

  const headersFieldset = element("fieldset", "options-page__headers");
  const headersLegend = element("legend", "options-page__label", "自定义请求头（可选）");
  const headerRows = element("div", "options-page__header-rows");
  const addHeaderButton = element("button", "options-page__secondary", "添加请求头");
  addHeaderButton.type = "button";
  addHeaderButton.dataset.action = "add-header";
  headersFieldset.append(headersLegend, headerRows, addHeaderButton);

  const result = element("p", "options-page__result");
  result.dataset.connectionResult = "";
  result.setAttribute("role", "status");
  result.setAttribute("aria-live", "polite");
  const actions = element("div", "options-page__actions");
  const saveButton = element("button", "options-page__primary", "保存配置");
  saveButton.type = "submit";
  const testButton = element("button", "options-page__secondary", "测试连接");
  testButton.type = "button";
  testButton.dataset.action = "test-profile";
  const activateButton = element("button", "options-page__secondary", "设为启用");
  activateButton.type = "button";
  activateButton.dataset.action = "activate-profile";
  activateButton.disabled = true;
  actions.append(saveButton, testButton, activateButton);
  form.append(
    idInput,
    field("显示名称", nameInput),
    field("Base URL", baseUrlInput),
    field("API Key", apiKeyInput),
    field("Model", modelInput),
    field("请求超时（秒）", timeoutInput),
    headersFieldset,
    actions,
    result,
  );
  profileSection.append(profileHeading, savedLabel, savedSelect, form);

  const cacheSection = element("section", "options-page__panel");
  cacheSection.setAttribute("aria-labelledby", "options-cache-heading");
  const cacheHeading = element("h2", "options-page__section-title", "分析缓存");
  cacheHeading.id = "options-cache-heading";
  const cacheStats = element("p", "options-page__cache-stats", "正在读取缓存…");
  const cacheLimitLabel = element("label", "options-page__label", "缓存上限");
  cacheLimitLabel.htmlFor = "options-cache-limit";
  const cacheLimit = element("select", "options-page__select");
  cacheLimit.id = "options-cache-limit";
  for (const limit of CACHE_LIMITS) {
    const option = element("option", undefined, `${limit} MB`);
    option.value = String(limit);
    cacheLimit.append(option);
  }
  const cacheHint = element(
    "p",
    "options-page__hint",
    "新上限会保存，并在后台缓存下次打开时生效。",
  );
  const clearButton = element("button", "options-page__danger", "清空缓存");
  clearButton.type = "button";
  clearButton.dataset.action = "clear-cache";
  const clearStatus = element("p", "options-page__result");
  clearStatus.setAttribute("role", "status");
  cacheSection.append(
    cacheHeading,
    cacheStats,
    cacheLimitLabel,
    cacheLimit,
    cacheHint,
    clearButton,
    clearStatus,
  );
  root.append(heading, intro, warning, profileSection, cacheSection);

  const addHeaderRow = (name = "", value = ""): void => {
    const row = element("div", "options-page__header-row");
    row.dataset.headerRow = "";
    const nameField = input(`options-header-name-${headerRows.children.length}`);
    nameField.placeholder = "Header 名称";
    nameField.setAttribute("aria-label", "请求头名称");
    nameField.dataset.headerName = "";
    nameField.value = name;
    const valueField = input(`options-header-value-${headerRows.children.length}`);
    valueField.placeholder = "Header 值";
    valueField.setAttribute("aria-label", "请求头值");
    valueField.dataset.headerValue = "";
    valueField.value = value;
    const remove = element("button", "options-page__secondary", "移除");
    remove.type = "button";
    remove.addEventListener("click", () => row.remove());
    const error = element("p", "options-page__inline-error");
    error.setAttribute("role", "alert");
    row.append(nameField, valueField, remove, error);
    headerRows.append(row);
  };

  const readHeaders = (): Record<string, string> | undefined => {
    const headers: Record<string, string> = {};
    let valid = true;
    for (const row of headerRows.querySelectorAll<HTMLElement>("[data-header-row]")) {
      const name = row.querySelector<HTMLInputElement>("[data-header-name]")!.value.trim();
      const value = row.querySelector<HTMLInputElement>("[data-header-value]")!.value;
      const error = row.querySelector<HTMLElement>("[role='alert']")!;
      error.textContent = "";
      if (name === "" && value === "") continue;
      if (name === "" || FORBIDDEN_HEADERS.has(name.toLowerCase())) {
        error.textContent = "该请求头名称不能为空或不能使用。";
        valid = false;
        continue;
      }
      headers[name] = value;
    }
    return valid ? headers : undefined;
  };

  const buildProfile = async (): Promise<ModelProfile | undefined> => {
    const headers = readHeaders();
    if (headers === undefined) return undefined;
    const id = idInput.value || crypto.randomUUID();
    const existing =
      idInput.value === "" ? undefined : await dependencies.getProfile(idInput.value);
    return {
      id,
      name: nameInput.value.trim(),
      baseUrl: normalizeBaseUrl(baseUrlInput.value),
      apiKey: apiKeyInput.value || existing?.apiKey || "",
      model: modelInput.value.trim(),
      headers,
      timeoutMs: Number(timeoutInput.value) * 1000,
      jsonSchemaSupport: existing?.jsonSchemaSupport ?? "unknown",
    };
  };

  const requestProfilePermission = async (profile: ModelProfile): Promise<boolean> =>
    dependencies.requestPermission(hostPermissionPattern(profile.baseUrl));

  let activeProfileId: string | undefined;

  const refreshActivateButton = (): void => {
    const selected = savedSelect.value;
    if (selected === "") {
      activateButton.disabled = true;
      activateButton.textContent = "设为启用";
    } else if (selected === activeProfileId) {
      activateButton.disabled = true;
      activateButton.textContent = "已启用";
    } else {
      activateButton.disabled = false;
      activateButton.textContent = "设为启用";
    }
  };

  const buildProfileOptions = (profiles: PublicModelProfile[], selectedId: string): void => {
    savedSelect.replaceChildren(element("option", undefined, "新建配置"));
    savedSelect.options[0]!.value = "";
    for (const profile of profiles) {
      const suffix = profile.id === activeProfileId ? "（启用中）" : "";
      const option = element("option", undefined, `${profile.name} · ${profile.model}${suffix}`);
      option.value = profile.id;
      savedSelect.append(option);
    }
    savedSelect.value = selectedId;
    refreshActivateButton();
  };

  let lastProfiles: PublicModelProfile[] = [];

  const loadProfiles = async (selectedId = ""): Promise<void> => {
    const [profiles, active] = await Promise.all([
      dependencies.listProfiles(),
      dependencies.getActiveProfileId(),
    ]);
    activeProfileId = active;
    lastProfiles = profiles;
    buildProfileOptions(profiles, selectedId);
  };

  const loadProfile = async (profileId: string): Promise<void> => {
    idInput.value = profileId;
    apiKeyInput.value = "";
    apiKeyInput.placeholder = profileId === "" ? API_KEY_REQUIRED_HINT : API_KEY_SAVED_HINT;
    headerRows.textContent = "";
    if (profileId === "") {
      nameInput.value = "";
      baseUrlInput.value = "";
      modelInput.value = "";
      timeoutInput.value = "45";
      return;
    }
    const profile = await dependencies.getProfile(profileId);
    if (profile === undefined) return;
    nameInput.value = profile.name;
    baseUrlInput.value = profile.baseUrl;
    modelInput.value = profile.model;
    timeoutInput.value = String(profile.timeoutMs / 1000);
    for (const [name, value] of Object.entries(profile.headers)) addHeaderRow(name, value);
  };

  addHeaderButton.addEventListener("click", () => addHeaderRow());
  savedSelect.addEventListener("change", () => {
    void loadProfile(savedSelect.value);
    refreshActivateButton();
  });
  activateButton.addEventListener("click", () => {
    void (async () => {
      const profileId = savedSelect.value;
      if (profileId === "") return;
      activateButton.disabled = true;
      try {
        await dependencies.setActiveProfile(profileId);
        activeProfileId = profileId;
        buildProfileOptions(lastProfiles, savedSelect.value);
        result.textContent = "已切换启用配置，随后的解析请求将使用它。";
      } catch {
        result.textContent = "切换启用配置失败，请刷新页面后重试。";
        refreshActivateButton();
      }
    })();
  });
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    void (async () => {
      try {
        const profile = await buildProfile();
        if (profile === undefined) return;
        if (!(await requestProfilePermission(profile))) {
          result.textContent = connectionMessage({
            success: false,
            error: "HOST_PERMISSION_DENIED",
          });
          return;
        }
        await dependencies.saveProfile(profile);
        idInput.value = profile.id;
        apiKeyInput.value = "";
        apiKeyInput.placeholder = API_KEY_SAVED_HINT;
        result.textContent = "配置已保存。";
        await loadProfiles(profile.id);
      } catch {
        result.textContent = "配置无效，请检查地址、必填项和超时时间。";
      }
    })();
  });
  testButton.addEventListener("click", () => {
    void (async () => {
      try {
        const profile = await buildProfile();
        if (profile === undefined) return;
        if (!(await requestProfilePermission(profile))) {
          result.textContent = connectionMessage({
            success: false,
            error: "HOST_PERMISSION_DENIED",
          });
          return;
        }
        await dependencies.saveProfile(profile);
        idInput.value = profile.id;
        apiKeyInput.value = "";
        apiKeyInput.placeholder = API_KEY_SAVED_HINT;
        result.textContent = connectionMessage(await dependencies.testProfile(profile.id));
        await loadProfiles(profile.id);
      } catch {
        result.textContent = "配置无效，请检查地址、必填项和超时时间。";
      }
    })();
  });
  cacheLimit.addEventListener("change", () => {
    void dependencies.setCacheLimitMb(Number(cacheLimit.value));
    clearStatus.textContent = "缓存上限已保存，将在后台缓存下次打开时生效。";
  });
  clearButton.addEventListener("click", () => {
    if (!dependencies.confirm("确定清空全部分析缓存吗？此操作不会删除模型配置。")) return;
    void dependencies.clearCache().then(async () => {
      clearStatus.textContent = "缓存已清空，模型配置保持不变。";
      const stats = await dependencies.getCacheStats();
      cacheStats.textContent = `${stats.entries} 条，估算占用 ${cacheSize(stats.estimatedBytes)}`;
    });
  });

  const [stats, limit] = await Promise.all([
    dependencies.getCacheStats(),
    dependencies.getCacheLimitMb(),
    loadProfiles(),
  ]).then(([nextStats, nextLimit]) => [nextStats, nextLimit] as const);
  cacheStats.textContent = `${stats.entries} 条，估算占用 ${cacheSize(stats.estimatedBytes)}`;
  cacheLimit.value = String(limit);
}

function runtimeDependencies(): OptionsDependencies {
  const repository = new ConfigRepository();
  const send = (message: unknown): Promise<ResponseMessage> => chrome.runtime.sendMessage(message);
  return {
    listProfiles: () => repository.listPublicProfiles(),
    getProfile: (profileId) => repository.getProfile(profileId),
    saveProfile: (profile) => repository.saveProfile(profile),
    requestPermission: (originPattern) => chrome.permissions.request({ origins: [originPattern] }),
    testProfile: async (profileId) => {
      const response = await send({
        version: MESSAGE_VERSION,
        requestId: requestId("options:test"),
        type: "TEST_PROFILE",
        profileId,
      });
      if (response.type === "PROFILE_TEST_RESULT") {
        const details = response.error?.details;
        return {
          success: response.success,
          ...(response.error === undefined ? {} : { error: response.error.code }),
          ...(response.jsonSchemaSupport === undefined
            ? {}
            : { jsonSchemaSupport: response.jsonSchemaSupport }),
          ...(typeof details?.status === "number" ? { status: details.status } : {}),
          ...(typeof details?.detail === "string" ? { detail: details.detail } : {}),
        };
      }
      return {
        success: false,
        error: response.type === "ERROR" ? response.error.code : "NETWORK_ERROR",
      };
    },
    getCacheStats: async () => {
      const response = await send({
        version: MESSAGE_VERSION,
        requestId: requestId("options:cache-stats"),
        type: "GET_CACHE_STATS",
      });
      if (response.type !== "CACHE_STATS") throw new Error("Cache stats unavailable");
      return response.stats;
    },
    clearCache: async () => {
      await send({
        version: MESSAGE_VERSION,
        requestId: requestId("options:clear-cache"),
        type: "CLEAR_CACHE",
      });
    },
    getCacheLimitMb: async () => (await repository.getCacheLimitBytes()) / (1024 * 1024),
    setCacheLimitMb: (limitMb) => repository.setCacheLimitMb(limitMb),
    getActiveProfileId: () => repository.getActiveProfileId(),
    setActiveProfile: (profileId) => repository.setActiveProfile(profileId),
    confirm: (message) => window.confirm(message),
  };
}

const app = document.querySelector<HTMLElement>("#app");

if (!app) {
  throw new Error("Options startup failed: #app element not found.");
}

if (typeof chrome !== "undefined" && chrome.runtime !== undefined) {
  void createOptionsPage(app, runtimeDependencies());
}
