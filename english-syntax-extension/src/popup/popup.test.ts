// @vitest-environment happy-dom

import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { PublicModelProfile } from "../background/config-repository";
import type { SessionStatus } from "../shared/protocol";
import type { PopupDependencies } from "./popup";

let createPopupPage: typeof import("./popup").createPopupPage;

beforeAll(async () => {
  const entryRoot = document.createElement("main");
  entryRoot.id = "app";
  document.body.append(entryRoot);
  ({ createPopupPage } = await import("./popup"));
});

const profiles: PublicModelProfile[] = [
  {
    id: "profile-a",
    name: "DeepSeek",
    baseUrl: "https://api.example.com/v1",
    model: "deepseek-chat",
    timeoutMs: 45_000,
    jsonSchemaSupport: "supported",
  },
  {
    id: "profile-b",
    name: "Local Model",
    baseUrl: "http://localhost:11434/v1",
    model: "qwen",
    timeoutMs: 45_000,
    jsonSchemaSupport: "unknown",
  },
];

const stopped: SessionStatus = {
  state: "stopped",
  discovered: 0,
  queued: 0,
  ready: 0,
  failed: 0,
};

function dependencies(overrides: Partial<PopupDependencies> = {}): PopupDependencies {
  return {
    listProfiles: vi.fn(() => Promise.resolve(profiles)),
    getActiveProfileId: vi.fn(() => Promise.resolve("profile-a")),
    getActiveTab: vi.fn(() => Promise.resolve({ id: 7, url: "https://example.com/article" })),
    getStatus: vi.fn(() => Promise.resolve(stopped)),
    sendCommand: vi.fn(() => Promise.resolve(stopped)),
    openOptions: vi.fn(),
    confirm: vi.fn(() => true),
    ...overrides,
  };
}

function root(): HTMLElement {
  document.body.textContent = "";
  const element = document.createElement("main");
  document.body.append(element);
  return element;
}

describe("Popup page", () => {
  beforeEach(() => vi.restoreAllMocks());

  it("shows onboarding when there is no saved model profile", async () => {
    const subject = dependencies({ listProfiles: vi.fn(() => Promise.resolve([])) });
    await createPopupPage(root(), subject);

    expect(document.body.textContent).toContain("尚未配置模型");
    const settings = document.querySelector<HTMLButtonElement>("[data-action='open-options']")!;
    settings.click();
    expect(subject.openOptions).toHaveBeenCalledOnce();
    expect(document.querySelector<HTMLButtonElement>("[data-action='start']")?.disabled).toBe(true);
  });

  it("switches the active profile without sending a reanalysis command", async () => {
    const subject = dependencies();
    await createPopupPage(root(), subject);
    const select = document.querySelector<HTMLSelectElement>("#popup-profile")!;

    select.value = "profile-b";
    select.dispatchEvent(new Event("change"));

    await vi.waitFor(() =>
      expect(subject.sendCommand).toHaveBeenCalledWith(
        "SWITCH_PROFILE",
        expect.objectContaining({ tabId: 7 }),
        "profile-b",
      ),
    );
    expect(subject.sendCommand).not.toHaveBeenCalledWith(
      "REANALYZE_VISIBLE",
      expect.anything(),
      expect.anything(),
    );
  });

  it("renders discovered, queued, completed and failed sentence counts exactly", async () => {
    await createPopupPage(
      root(),
      dependencies({
        getStatus: vi.fn((): Promise<SessionStatus> =>
          Promise.resolve({
            state: "running",
            discovered: 23,
            queued: 4,
            ready: 18,
            failed: 1,
            profileId: "profile-a",
          }),
        ),
      }),
    );

    expect(document.querySelector("[data-count='discovered']")?.textContent).toBe("23");
    expect(document.querySelector("[data-count='queued']")?.textContent).toBe("4");
    expect(document.querySelector("[data-count='ready']")?.textContent).toBe("18");
    expect(document.querySelector("[data-count='failed']")?.textContent).toBe("1");
    expect(document.body.textContent).toContain("失败句子");
  });

  it.each([
    ["stopped", "开始学习", "START_SESSION"],
    ["running", "暂停", "PAUSE_SESSION"],
    ["paused", "继续学习", "START_SESSION"],
  ] as const)("renders the %s primary control", async (state, label, command) => {
    const subject = dependencies({
      getStatus: vi.fn(() => Promise.resolve({ ...stopped, state })),
    });
    await createPopupPage(root(), subject);

    const button = [...document.querySelectorAll<HTMLButtonElement>("button")].find(
      (candidate) => candidate.textContent === label,
    )!;
    button.click();

    await vi.waitFor(() =>
      expect(subject.sendCommand).toHaveBeenCalledWith(command, expect.anything(), undefined),
    );
    expect(document.body.textContent).toContain("停止并恢复网页");
  });

  it("disables start and explains unsupported active tabs", async () => {
    await createPopupPage(
      root(),
      dependencies({
        getActiveTab: vi.fn(() => Promise.resolve({ id: 7, url: "chrome://settings" })),
      }),
    );

    expect(document.querySelector<HTMLButtonElement>("[data-action='start']")?.disabled).toBe(true);
    expect(document.querySelector("[role='alert']")?.textContent).toContain("此页面不支持");
  });

  it("confirms cost before sending an explicit visible-area reanalysis", async () => {
    const subject = dependencies({
      getStatus: vi.fn((): Promise<SessionStatus> =>
        Promise.resolve({ ...stopped, state: "running" }),
      ),
      confirm: vi.fn(() => false),
    });
    await createPopupPage(root(), subject);
    const button = document.querySelector<HTMLButtonElement>("[data-action='reanalyze']")!;

    button.click();
    expect(subject.confirm).toHaveBeenCalledWith(expect.stringContaining("新的模型费用"));
    expect(subject.sendCommand).not.toHaveBeenCalledWith(
      "REANALYZE_VISIBLE",
      expect.anything(),
      expect.anything(),
    );

    vi.mocked(subject.confirm).mockReturnValue(true);
    button.click();
    await vi.waitFor(() =>
      expect(subject.sendCommand).toHaveBeenCalledWith(
        "REANALYZE_VISIBLE",
        expect.anything(),
        undefined,
      ),
    );
  });

  it("uses native keyboard controls, a CSS namespace and a visible focus hook", async () => {
    await createPopupPage(root(), dependencies());

    expect(
      [...document.querySelectorAll<HTMLElement>("button, select")].every(
        (node) => node.tabIndex >= 0,
      ),
    ).toBe(true);
    expect(document.querySelector(".popup-page")).not.toBeNull();
    expect(document.querySelector("[data-focus-style='visible']")).not.toBeNull();
  });
});
