import { ConfigRepository, type PublicModelProfile } from "../background/config-repository";
import { isSessionComplete, type ResponseMessage, type SessionStatus } from "../shared/protocol";
import { MESSAGE_VERSION } from "../shared/versions";

export interface PopupTabContext {
  tabId: number;
  url?: string;
}

export type PopupCommand = "START_SESSION" | "PAUSE_SESSION" | "STOP_SESSION";

export interface PopupDependencies {
  listProfiles: () => Promise<PublicModelProfile[]>;
  getActiveProfileId: () => Promise<string | undefined>;
  getActiveTab: () => Promise<{ id?: number; url?: string }>;
  getStatus: (context: PopupTabContext) => Promise<SessionStatus>;
  sendCommand: (type: PopupCommand, context: PopupTabContext) => Promise<SessionStatus | undefined>;
  openOptions: () => void;
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

export async function createPopupPage(
  root: HTMLElement,
  dependencies: PopupDependencies,
): Promise<void> {
  root.textContent = "";
  root.className = "popup-page";
  root.dataset.focusStyle = "visible";

  const header = element("div", "popup-page__header");
  const heading = element("h1", "popup-page__title", "英语句法伴读");
  const gear = element("button", "popup-page__gear", "⚙︎");
  gear.type = "button";
  gear.dataset.action = "open-options";
  gear.setAttribute("aria-label", "打开模型设置");
  gear.addEventListener("click", dependencies.openOptions);
  header.append(heading, gear);

  const primary = element("button", "popup-page__primary");
  primary.type = "button";
  primary.dataset.primary = "";

  const subline = element("p", "popup-page__subline");
  subline.dataset.subline = "";
  subline.setAttribute("aria-live", "polite");

  root.append(header, primary, subline);

  const [profiles, activeProfileId, activeTab] = await Promise.all([
    dependencies.listProfiles(),
    dependencies.getActiveProfileId(),
    dependencies.getActiveTab(),
  ]);
  const context: PopupTabContext | undefined =
    activeTab.id === undefined ? undefined : { tabId: activeTab.id, url: activeTab.url };
  const supported = context !== undefined && isSupportedUrl(context.url);
  const profile = profiles.find(({ id }) => id === activeProfileId) ?? profiles[0];
  let status =
    context === undefined
      ? EMPTY_STATUS
      : await dependencies.getStatus(context).catch(() => EMPTY_STATUS);

  const modelLine = profile === undefined ? "" : `${profile.name} · ${profile.model}`;
  let command: PopupCommand = "START_SESSION";

  const renderStatus = (): void => {
    subline.textContent = modelLine;
    if (profile === undefined) {
      primary.textContent = "去配置模型";
      primary.dataset.action = "open-options";
      primary.disabled = false;
      subline.textContent = "尚未配置模型，先在设置页添加一个。";
      return;
    }
    if (!supported) {
      primary.textContent = "开始学习";
      primary.disabled = true;
      subline.textContent = "此页面不支持句法解析，请切换到普通 http/https 网页。";
      return;
    }
    primary.disabled = false;
    if (status.state === "running" && isSessionComplete(status)) {
      primary.textContent = "恢复网页原文";
      command = "STOP_SESSION";
    } else if (status.state === "running") {
      primary.textContent = `解析中… ${status.ready + status.failed}/${status.discovered}（点击暂停）`;
      command = "PAUSE_SESSION";
    } else if (status.state === "paused") {
      primary.textContent = "继续学习";
      command = "START_SESSION";
    } else {
      primary.textContent = "开始学习";
      command = "START_SESSION";
    }
  };

  primary.addEventListener("click", () => {
    if (profile === undefined) {
      dependencies.openOptions();
      return;
    }
    if (context === undefined) return;
    primary.disabled = true;
    void (async () => {
      try {
        const next = await dependencies.sendCommand(command, context);
        if (next !== undefined) status = next;
        renderStatus();
      } catch {
        renderStatus();
        subline.textContent = "操作失败，请刷新页面或重新打开扩展后重试。";
      }
    })();
  });

  renderStatus();
}

export function runtimeDependencies(): PopupDependencies {
  const repository = new ConfigRepository();
  return {
    listProfiles: () => repository.listPublicProfiles(),
    getActiveProfileId: () => repository.getActiveProfileId(),
    getActiveTab: async () => {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      return { id: tab?.id, url: tab?.url };
    },
    getStatus: async (context) => {
      const response: ResponseMessage = await chrome.runtime.sendMessage({
        version: MESSAGE_VERSION,
        requestId: `popup:status:${crypto.randomUUID()}`,
        type: "GET_SESSION_STATUS",
        tabId: context.tabId,
        documentId: `popup-tab-${context.tabId}`,
      });
      return response.type === "SESSION_STATUS" ? response.status : EMPTY_STATUS;
    },
    sendCommand: async (type, context) => {
      const response: ResponseMessage = await chrome.runtime.sendMessage({
        version: MESSAGE_VERSION,
        requestId: `popup:${type}:${crypto.randomUUID()}`,
        type,
        tabId: context.tabId,
        documentId: `popup-tab-${context.tabId}`,
      });
      if (response.type === "ERROR") throw new Error(response.error.message);
      return response.type === "SESSION_STATUS" ? response.status : undefined;
    },
    openOptions: () => {
      void chrome.runtime.openOptionsPage();
    },
  };
}

const app = document.querySelector<HTMLElement>("#app");

if (!app) {
  throw new Error("Popup startup failed: #app element not found.");
}

if (typeof chrome !== "undefined" && chrome.runtime !== undefined) {
  void createPopupPage(app, runtimeDependencies());
}
