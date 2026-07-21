// @vitest-environment happy-dom

import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { PublicModelProfile } from "../background/config-repository";
import { isRequestMessage, type SessionStatus } from "../shared/protocol";
import type { PopupDependencies } from "./popup";

let createPopupPage: typeof import("./popup").createPopupPage;
let runtimeDependencies: typeof import("./popup").runtimeDependencies;

beforeAll(async () => {
  const entryRoot = document.createElement("main");
  entryRoot.id = "app";
  document.body.append(entryRoot);
  ({ createPopupPage, runtimeDependencies } = await import("./popup"));
});

const profiles: PublicModelProfile[] = [
  {
    id: "profile-a",
    name: "DeepSeek",
    baseUrl: "https://api.example.com/v1",
    model: "deepseek-v4-flash",
    timeoutMs: 45_000,
    jsonSchemaSupport: "unsupported",
  },
];

function status(partial: Partial<SessionStatus>): SessionStatus {
  return { state: "stopped", discovered: 0, queued: 0, ready: 0, failed: 0, ...partial };
}

function dependencies(overrides: Partial<PopupDependencies> = {}): PopupDependencies {
  return {
    listProfiles: vi.fn(() => Promise.resolve(profiles)),
    getActiveProfileId: vi.fn(() => Promise.resolve("profile-a")),
    getActiveTab: vi.fn(() => Promise.resolve({ id: 7, url: "https://example.com/article" })),
    getStatus: vi.fn(() => Promise.resolve(status({}))),
    sendCommand: vi.fn(() => Promise.resolve(status({}))),
    openOptions: vi.fn(),
    ...overrides,
  };
}

function root(): HTMLElement {
  document.body.textContent = "";
  const element = document.createElement("main");
  document.body.append(element);
  return element;
}

const primary = (): HTMLButtonElement =>
  document.querySelector<HTMLButtonElement>("[data-primary]")!;
const subline = (): HTMLElement => document.querySelector<HTMLElement>("[data-subline]")!;

describe("Popup", () => {
  it("shows only a title row, one primary button and a model subline", async () => {
    await createPopupPage(root(), dependencies());

    expect(document.querySelectorAll("button")).toHaveLength(2); // 主按钮 + 齿轮
    expect(document.querySelector("select")).toBeNull();
    expect(document.querySelector("[data-count]")).toBeNull();
    expect(primary().textContent).toBe("开始学习");
    expect(subline().textContent).toContain("DeepSeek · deepseek-v4-flash");
  });

  it("starts a session from the stopped state", async () => {
    const subject = dependencies();
    await createPopupPage(root(), subject);

    primary().click();

    await vi.waitFor(() =>
      expect(subject.sendCommand).toHaveBeenCalledWith("START_SESSION", {
        tabId: 7,
        url: "https://example.com/article",
      }),
    );
  });

  it("shows live progress while running and pauses on click", async () => {
    const subject = dependencies({
      getStatus: vi.fn(() =>
        Promise.resolve(status({ state: "running", discovered: 5, queued: 3, ready: 2 })),
      ),
      sendCommand: vi.fn(() =>
        Promise.resolve(status({ state: "paused", discovered: 5, queued: 3, ready: 2 })),
      ),
    });
    await createPopupPage(root(), subject);

    expect(primary().textContent).toBe("解析中… 2/5（点击暂停）");
    primary().click();

    await vi.waitFor(() =>
      expect(subject.sendCommand).toHaveBeenCalledWith("PAUSE_SESSION", expect.anything()),
    );
    expect(primary().textContent).toBe("继续学习");
  });

  it("resumes a paused session with START_SESSION", async () => {
    const subject = dependencies({
      getStatus: vi.fn(() =>
        Promise.resolve(status({ state: "paused", discovered: 5, queued: 3, ready: 2 })),
      ),
    });
    await createPopupPage(root(), subject);

    expect(primary().textContent).toBe("继续学习");
    primary().click();

    await vi.waitFor(() =>
      expect(subject.sendCommand).toHaveBeenCalledWith("START_SESSION", expect.anything()),
    );
  });

  it("offers to restore the page once every sentence resolved", async () => {
    const subject = dependencies({
      getStatus: vi.fn(() =>
        Promise.resolve(status({ state: "running", discovered: 5, ready: 4, failed: 1 })),
      ),
    });
    await createPopupPage(root(), subject);

    expect(primary().textContent).toBe("恢复网页原文");
    primary().click();

    await vi.waitFor(() =>
      expect(subject.sendCommand).toHaveBeenCalledWith("STOP_SESSION", expect.anything()),
    );
  });

  it("offers 恢复网页原文 while parsing is still under way", async () => {
    const subject = dependencies({
      getStatus: vi.fn(() =>
        Promise.resolve(status({ state: "running", discovered: 5, queued: 3, ready: 2 })),
      ),
      sendCommand: vi.fn(() => Promise.resolve(status({}))),
    });
    await createPopupPage(root(), subject);

    const secondary = document.querySelector<HTMLButtonElement>("[data-secondary]");
    expect(secondary?.textContent).toBe("恢复网页原文");
    secondary!.click();

    await vi.waitFor(() =>
      expect(subject.sendCommand).toHaveBeenCalledWith("STOP_SESSION", expect.anything()),
    );
    await vi.waitFor(() => expect(primary().textContent).toBe("开始学习"));
    expect(document.querySelector("[data-secondary]")).toBeNull();
  });

  it("offers 恢复网页原文 while paused and hides it once the primary button restores", async () => {
    const paused = dependencies({
      getStatus: vi.fn(() =>
        Promise.resolve(status({ state: "paused", discovered: 5, queued: 3, ready: 2 })),
      ),
    });
    await createPopupPage(root(), paused);
    expect(document.querySelector("[data-secondary]")?.textContent).toBe("恢复网页原文");

    const complete = dependencies({
      getStatus: vi.fn(() =>
        Promise.resolve(status({ state: "running", discovered: 5, ready: 4, failed: 1 })),
      ),
    });
    await createPopupPage(root(), complete);
    expect(primary().textContent).toBe("恢复网页原文");
    expect(document.querySelector("[data-secondary]")).toBeNull();
  });

  it("disables the button while a command is in flight", async () => {
    let release: (value: SessionStatus) => void;
    const subject = dependencies({
      sendCommand: vi.fn(
        () =>
          new Promise<SessionStatus>((resolve) => {
            release = resolve;
          }),
      ),
    });
    await createPopupPage(root(), subject);

    primary().click();
    expect(primary().disabled).toBe(true);

    release!(status({ state: "running", discovered: 1, queued: 1 }));
    await vi.waitFor(() => expect(primary().disabled).toBe(false));
  });

  it("disables the primary button on unsupported pages", async () => {
    const subject = dependencies({
      getActiveTab: vi.fn(() => Promise.resolve({ id: 7, url: "chrome://extensions" })),
    });
    await createPopupPage(root(), subject);

    expect(primary().disabled).toBe(true);
    expect(subline().textContent).toContain("此页面不支持句法解析");
  });

  it("opens the options page from the gear button", async () => {
    const subject = dependencies();
    await createPopupPage(root(), subject);

    document.querySelector<HTMLButtonElement>("[data-action='open-options']")!.click();

    expect(subject.openOptions).toHaveBeenCalled();
  });

  it("shows detail prefetch progress in the subline while it is running", async () => {
    await createPopupPage(
      root(),
      dependencies({
        getStatus: vi.fn(() =>
          Promise.resolve(
            status({
              state: "running",
              discovered: 5,
              ready: 5,
              detailTotal: 20,
              detailReady: 7,
              detailFailed: 1,
            }),
          ),
        ),
      }),
    );
    expect(subline().textContent).toContain("详解预载中 8/20");
  });

  it("shows a one-line error on command failure", async () => {
    const subject = dependencies({
      sendCommand: vi.fn(() => Promise.reject(new Error("boom"))),
    });
    await createPopupPage(root(), subject);

    primary().click();

    await vi.waitFor(() => expect(subline().textContent).toContain("操作失败"));
  });
});

describe("cache-only mode (no profile configured)", () => {
  const noProfile = (overrides: Partial<PopupDependencies> = {}) =>
    dependencies({
      listProfiles: vi.fn(() => Promise.resolve([])),
      getActiveProfileId: vi.fn(() => Promise.resolve(undefined)),
      ...overrides,
    });

  it("shows 查看缓存 on supported pages and starts a session on click", async () => {
    const subject = noProfile();
    await createPopupPage(root(), subject);

    expect(primary().textContent).toBe("查看缓存");
    expect(primary().disabled).toBe(false);
    expect(subline().textContent).toContain("尚未配置模型");
    primary().click();

    await vi.waitFor(() =>
      expect(subject.sendCommand).toHaveBeenCalledWith("START_SESSION", expect.anything()),
    );
    expect(subject.openOptions).not.toHaveBeenCalled();
  });

  it("shows cache-hit progress wording while a cache-only session runs", async () => {
    await createPopupPage(
      root(),
      noProfile({
        getStatus: vi.fn(() =>
          Promise.resolve(status({ state: "running", discovered: 5, ready: 2, skipped: 1 })),
        ),
      }),
    );

    expect(primary().textContent).toBe("缓存命中 2/5 句（点击暂停）");
  });

  it("completes a cache-only session into 恢复网页原文 when hits and skips cover everything", async () => {
    await createPopupPage(
      root(),
      noProfile({
        getStatus: vi.fn(() =>
          Promise.resolve(status({ state: "running", discovered: 5, ready: 3, skipped: 2 })),
        ),
      }),
    );

    expect(primary().textContent).toBe("恢复网页原文");
  });

  it("resumes a paused cache-only session with 继续学习", async () => {
    await createPopupPage(
      root(),
      noProfile({
        getStatus: vi.fn(() =>
          Promise.resolve(status({ state: "paused", discovered: 5, ready: 2, skipped: 1 })),
        ),
      }),
    );

    expect(primary().textContent).toBe("继续学习");
  });

  it("keeps the setup entry on unsupported pages", async () => {
    const subject = noProfile({
      getActiveTab: vi.fn(() => Promise.resolve({ id: 7, url: "chrome://extensions" })),
    });
    await createPopupPage(root(), subject);

    expect(primary().textContent).toBe("去配置模型");
    expect(primary().disabled).toBe(false);
    expect(subline().textContent).toContain("先在设置页添加一个");
    primary().click();
    await vi.waitFor(() => expect(subject.openOptions).toHaveBeenCalled());
    expect(subject.sendCommand).not.toHaveBeenCalled();
  });
});

describe("runtimeDependencies", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("sends page-scoped runtime messages the service worker validator accepts", async () => {
    const sendMessage = vi.fn<(message: unknown) => Promise<never>>(() =>
      Promise.resolve({ type: "ACK" } as never),
    );
    vi.stubGlobal("chrome", {
      runtime: { sendMessage, openOptionsPage: vi.fn() },
      tabs: { query: vi.fn() },
      storage: {
        local: {
          get: vi.fn(() => Promise.resolve({})),
          set: vi.fn(() => Promise.resolve(undefined)),
        },
      },
    });

    const deps = runtimeDependencies();
    await deps.getStatus({ tabId: 7 });
    await deps.sendCommand("START_SESSION", { tabId: 7 });

    for (const [message] of sendMessage.mock.calls) {
      expect((message as { documentId: string }).documentId).toBe("popup-tab-7");
      expect(isRequestMessage(message)).toBe(true);
    }
  });
});
