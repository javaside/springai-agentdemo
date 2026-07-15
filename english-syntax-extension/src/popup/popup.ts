import { ConfigRepository, type PublicModelProfile } from "../background/config-repository";
import type { ResponseMessage, SessionStatus } from "../shared/protocol";
import { MESSAGE_VERSION } from "../shared/versions";

export interface PopupTabContext {
  tabId: number;
  url?: string;
}

export type PopupCommand =
  "START_SESSION" | "PAUSE_SESSION" | "STOP_SESSION" | "SWITCH_PROFILE" | "REANALYZE_VISIBLE";

export interface PopupDependencies {
  listProfiles: () => Promise<PublicModelProfile[]>;
  getActiveProfileId: () => Promise<string | undefined>;
  getActiveTab: () => Promise<{ id?: number; url?: string }>;
  getStatus: (context: PopupTabContext) => Promise<SessionStatus>;
  sendCommand: (
    type: PopupCommand,
    context: PopupTabContext,
    profileId?: string,
  ) => Promise<SessionStatus | undefined>;
  openOptions: () => void;
  confirm: (message: string) => boolean;
}

const EMPTY_STATUS: SessionStatus = {
  state: "stopped",
  discovered: 0,
  queued: 0,
  ready: 0,
  failed: 0,
};

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

function isSupportedUrl(url: string | undefined): boolean {
  if (url === undefined) return false;
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" || parsed.protocol === "http:";
  } catch {
    return false;
  }
}

function statusCounter(label: string, name: string): HTMLDivElement {
  const item = element("div", "popup-page__metric");
  const value = element("strong", "popup-page__metric-value", "0");
  value.dataset.count = name;
  item.append(element("span", "popup-page__metric-label", label), value);
  return item;
}

export async function createPopupPage(
  root: HTMLElement,
  dependencies: PopupDependencies,
): Promise<void> {
  root.textContent = "";
  root.className = "popup-page";
  root.dataset.focusStyle = "visible";

  const heading = element("h1", "popup-page__title", "英语句法伴读");
  const profileLabel = element("label", "popup-page__label", "当前模型");
  profileLabel.htmlFor = "popup-profile";
  const profileSelect = element("select", "popup-page__select");
  profileSelect.id = "popup-profile";
  const notice = element("p", "popup-page__notice");
  notice.setAttribute("aria-live", "polite");
  const metrics = element("section", "popup-page__metrics");
  metrics.setAttribute("aria-label", "当前页面解析状态");
  metrics.append(
    statusCounter("已发现句子", "discovered"),
    statusCounter("排队句子", "queued"),
    statusCounter("已完成句子", "ready"),
    statusCounter("失败句子", "failed"),
  );
  const actions = element("div", "popup-page__actions");
  const primary = element("button", "popup-page__primary", "开始学习");
  primary.type = "button";
  primary.dataset.action = "start";
  const stop = element("button", "popup-page__danger", "停止并恢复网页");
  stop.type = "button";
  stop.dataset.action = "stop";
  const reanalyze = element("button", "popup-page__secondary", "重新解析可视区域");
  reanalyze.type = "button";
  reanalyze.dataset.action = "reanalyze";
  actions.append(primary, stop, reanalyze);
  const footer = element("p", "popup-page__footer", "模型切换只影响随后发出的请求。");
  root.append(heading, profileLabel, profileSelect, notice, metrics, actions, footer);

  const [profiles, activeProfileId, activeTab] = await Promise.all([
    dependencies.listProfiles(),
    dependencies.getActiveProfileId(),
    dependencies.getActiveTab(),
  ]);
  const context: PopupTabContext | undefined =
    activeTab.id === undefined ? undefined : { tabId: activeTab.id, url: activeTab.url };
  const supported = context !== undefined && isSupportedUrl(context.url);
  let status =
    context === undefined
      ? EMPTY_STATUS
      : await dependencies.getStatus(context).catch(() => EMPTY_STATUS);

  const renderStatus = (): void => {
    for (const key of ["discovered", "queued", "ready", "failed"] as const) {
      root.querySelector<HTMLElement>(`[data-count='${key}']`)!.textContent = String(status[key]);
    }
    if (status.state === "running") {
      primary.textContent = "暂停";
      primary.dataset.action = "pause";
    } else if (status.state === "paused") {
      primary.textContent = "继续学习";
      primary.dataset.action = "resume";
    } else {
      primary.textContent = "开始学习";
      primary.dataset.action = "start";
    }
    const unavailable = profiles.length === 0 || !supported;
    primary.disabled = unavailable;
    stop.disabled = unavailable || status.state === "stopped";
    reanalyze.disabled = unavailable || status.state === "stopped";
  };

  if (profiles.length === 0) {
    profileSelect.disabled = true;
    profileSelect.append(element("option", undefined, "尚未配置模型"));
    notice.textContent = "尚未配置模型，请先打开设置添加模型配置。";
    const openOptions = element("button", "popup-page__secondary", "打开模型设置");
    openOptions.type = "button";
    openOptions.dataset.action = "open-options";
    openOptions.addEventListener("click", dependencies.openOptions);
    notice.append(document.createTextNode(" "), openOptions);
  } else {
    for (const profile of profiles) {
      const option = element("option", undefined, `${profile.name} · ${profile.model}`);
      option.value = profile.id;
      profileSelect.append(option);
    }
    const selected = status.profileId ?? activeProfileId ?? profiles[0]!.id;
    profileSelect.value = profiles.some(({ id }) => id === selected) ? selected : profiles[0]!.id;
  }

  if (!supported) {
    notice.textContent = "此页面不支持句法解析，请切换到普通 http/https 网页。";
    notice.setAttribute("role", "alert");
  }

  const run = async (type: PopupCommand, profileId?: string): Promise<void> => {
    if (context === undefined) return;
    try {
      const next = await dependencies.sendCommand(type, context, profileId);
      if (next !== undefined) status = next;
      notice.textContent = "";
      renderStatus();
    } catch {
      notice.textContent = "操作失败，请刷新页面或重新打开扩展后重试。";
      notice.setAttribute("role", "alert");
    }
  };

  profileSelect.addEventListener("change", () => void run("SWITCH_PROFILE", profileSelect.value));
  primary.addEventListener("click", () => {
    void run(status.state === "running" ? "PAUSE_SESSION" : "START_SESSION");
  });
  stop.addEventListener("click", () => void run("STOP_SESSION"));
  reanalyze.addEventListener("click", () => {
    if (
      !dependencies.confirm(
        "重新解析可视区域会发起新的模型请求，并可能产生新的模型费用。确定继续吗？",
      )
    ) {
      return;
    }
    void run("REANALYZE_VISIBLE");
  });
  renderStatus();
}

function runtimeDependencies(): PopupDependencies {
  const repository = new ConfigRepository();
  const contextMessage = (
    type: PopupCommand | "GET_SESSION_STATUS",
    context: PopupTabContext,
    profileId?: string,
  ) => ({
    version: MESSAGE_VERSION,
    requestId: `popup:${type}:${crypto.randomUUID()}`,
    type,
    tabId: context.tabId,
    documentId: `popup-tab-${context.tabId}`,
    ...(profileId === undefined ? {} : { profileId }),
  });
  const send = (message: unknown): Promise<ResponseMessage> => chrome.runtime.sendMessage(message);
  return {
    listProfiles: () => repository.listPublicProfiles(),
    getActiveProfileId: () => repository.getActiveProfileId(),
    getActiveTab: async () => {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      return { id: tab?.id, url: tab?.url };
    },
    getStatus: async (context) => {
      const response = await send(contextMessage("GET_SESSION_STATUS", context));
      return response.type === "SESSION_STATUS" ? response.status : EMPTY_STATUS;
    },
    sendCommand: async (type, context, profileId) => {
      const response = await send(contextMessage(type, context, profileId));
      if (response.type === "ERROR") throw new Error(response.error.code);
      return response.type === "SESSION_STATUS" ? response.status : undefined;
    },
    openOptions: () => void chrome.runtime.openOptionsPage(),
    confirm: (message) => window.confirm(message),
  };
}

const app = document.querySelector<HTMLElement>("#app");

if (!app) {
  throw new Error("Popup startup failed: #app element not found.");
}

if (typeof chrome !== "undefined" && chrome.runtime !== undefined) {
  void createPopupPage(app, runtimeDependencies());
}
