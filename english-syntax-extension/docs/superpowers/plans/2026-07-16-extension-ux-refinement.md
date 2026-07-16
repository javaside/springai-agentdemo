# 扩展 UX 精简实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 spec `docs/superpowers/specs/2026-07-16-extension-ux-refinement-design.md` 落地三处 UX:极简 popup、彩色细下划线标注、右下角进度胶囊,附带设置页「启用」配置与 Retina 媒体查询 bug 修复。

**Architecture:** popup 重写为「标题行+状态机主按钮+小字行」;标注去底色、按 GrammarRole 着色下划线(CSS 变量注入);进度胶囊是 content script 内的独立 shadow-DOM 组件,订阅现有 SessionController `onStatus` 回调,零新增协议。

**Tech Stack:** TypeScript + Vite + vitest(happy-dom)+ Playwright(fake-openai-server)。验证命令一律在 `english-syntax-extension/` 项目目录跑:`npm test`、`npm run lint`、`npm run build`、`npx playwright test`、`npm run format:check`。

**通用规约:** 严格 TDD(先写失败测试);每个 Task 一次提交;prettier 收尾;E2E 的 fake server 按提示词首行识别请求类型——本计划不改任何 prompt。

---

### Task 1: popup 重写(状态机主按钮 + 三元素布局)

**Files:**

- Modify: `src/popup/popup.ts`(整文件重写)
- Modify: `src/popup/popup.test.ts`(整文件重写)
- Modify: `src/popup/popup.css`(整文件重写,含删除 `min-resolution: 1.8dppx` bug)

- [ ] **Step 1: 重写失败测试** — 用下面内容整体替换 `src/popup/popup.test.ts`:

```ts
// @vitest-environment happy-dom

import { beforeAll, describe, expect, it, vi } from "vitest";
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

  it("turns into a setup shortcut when no profile exists", async () => {
    const subject = dependencies({ listProfiles: vi.fn(() => Promise.resolve([])) });
    await createPopupPage(root(), subject);

    expect(primary().textContent).toBe("去配置模型");
    expect(primary().disabled).toBe(false);
    expect(subline().textContent).toContain("尚未配置模型");
    primary().click();
    expect(subject.openOptions).toHaveBeenCalled();
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

  it("shows a one-line error on command failure", async () => {
    const subject = dependencies({
      sendCommand: vi.fn(() => Promise.reject(new Error("boom"))),
    });
    await createPopupPage(root(), subject);

    primary().click();

    await vi.waitFor(() => expect(subline().textContent).toContain("操作失败"));
  });
});
```

- [ ] **Step 2: 跑测试确认失败** — `npx vitest run src/popup/popup.test.ts` → 预期 FAIL(`[data-primary]` 不存在等)。

- [ ] **Step 3: 重写实现** — 用下面内容整体替换 `src/popup/popup.ts`:

```ts
import { ConfigRepository, type PublicModelProfile } from "../background/config-repository";
import type { ResponseMessage, SessionStatus } from "../shared/protocol";
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

function isComplete(status: SessionStatus): boolean {
  return (
    status.discovered > 0 &&
    status.queued === 0 &&
    status.ready + status.failed >= status.discovered
  );
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
    if (status.state === "running" && isComplete(status)) {
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
      } finally {
        primary.disabled = primary.disabled && (!supported || profile === undefined);
        if (profile !== undefined && supported) primary.disabled = false;
      }
    })();
  });

  renderStatus();
}

function runtimeDependencies(): PopupDependencies {
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
      });
      return response.type === "SESSION_STATUS" ? response.status : EMPTY_STATUS;
    },
    sendCommand: async (type, context) => {
      const response: ResponseMessage = await chrome.runtime.sendMessage({
        version: MESSAGE_VERSION,
        requestId: `popup:${type}:${crypto.randomUUID()}`,
        type,
        tabId: context.tabId,
      });
      if (response.type === "ERROR") throw new Error(response.error.message);
      return response.type === "SESSION_STATUS" ? response.status : undefined;
    },
    openOptions: () => chrome.runtime.openOptionsPage(),
  };
}

const app = document.querySelector<HTMLElement>("#app");

if (!app) {
  throw new Error("Popup startup failed: #app element not found.");
}

if (typeof chrome !== "undefined" && chrome.runtime !== undefined) {
  void createPopupPage(app, runtimeDependencies());
}
```

注意:旧文件底部的 runtime 启动块结构与此相同,整体替换即可。旧 `PopupCommand` 里的 `SWITCH_PROFILE`/`REANALYZE_VISIBLE` 从 popup 移除,但 `src/shared/protocol.ts` 与 content 端的处理保持不动。

- [ ] **Step 4: 重写样式** — 用下面内容整体替换 `src/popup/popup.css`(重点:不再有 `min-resolution: 1.8dppx` 分支——那是 Retina 屏上弹框塌成竖条的直接原因):

```css
:root {
  color-scheme: light dark;
  font-family: system-ui, sans-serif;
  line-height: 1.45;
  background: Canvas;
  color: CanvasText;
}

* {
  box-sizing: border-box;
}

html,
body {
  margin: 0;
}

body {
  width: 300px;
}

.popup-page {
  padding: 0.85rem 1rem 1rem;
}

.popup-page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-block-end: 0.75rem;
}

.popup-page__title {
  margin: 0;
  font-size: 1.05rem;
  line-height: 1.25;
}

.popup-page__gear {
  border: 0;
  background: transparent;
  color: inherit;
  font-size: 1.1rem;
  padding: 0.25rem 0.4rem;
  border-radius: 0.4rem;
  cursor: pointer;
}

.popup-page__gear:hover {
  background: color-mix(in srgb, CanvasText 8%, transparent);
}

.popup-page__primary {
  width: 100%;
  min-height: 2.9rem;
  border: 0;
  border-radius: 0.55rem;
  font: inherit;
  font-weight: 650;
  cursor: pointer;
  background: #155eef;
  color: white;
}

.popup-page__primary:disabled {
  opacity: 0.55;
  cursor: default;
}

.popup-page__subline {
  margin: 0.6rem 0 0;
  font-size: 0.82rem;
  color: GrayText;
  overflow-wrap: anywhere;
}

.popup-page :is(button):focus-visible {
  outline: 3px solid #ffbf47;
  outline-offset: 2px;
}

@media (prefers-color-scheme: dark) {
  .popup-page__primary {
    background: #8ab4ff;
    color: #07152f;
  }
}
```

- [ ] **Step 5: 跑测试确认通过** — `npx vitest run src/popup/popup.test.ts` → 预期全 PASS。

- [ ] **Step 6: 全量单测 + 构建** — `npm test && npm run build` → 预期全绿(popup 之外若有引用旧 API 的编译错,按报错点修:全仓 `grep -rn "REANALYZE_VISIBLE\|SWITCH_PROFILE" src/` 确认只剩 protocol/content 端)。

- [ ] **Step 7: 提交**

```bash
npx prettier --write src/popup/popup.ts src/popup/popup.test.ts src/popup/popup.css
git add src/popup/
git commit -m "feat(extension): minimal popup with a single state-machine button"
```

---

### Task 2: 设置页「启用」配置

**Files:**

- Modify: `src/options/options.ts`
- Modify: `src/options/options.test.ts`

- [ ] **Step 1: 写失败测试** — 在 `src/options/options.test.ts` 的 `dependencies()` 工厂里给返回对象加两个字段(与现有字段并列):

```ts
    getActiveProfileId: vi.fn(() => Promise.resolve(undefined as string | undefined)),
    setActiveProfile: vi.fn(() => Promise.resolve()),
```

再在 `describe("Options page", ...)` 末尾追加测试:

```ts
it("marks the active profile and lets another profile take over", async () => {
  const subject = dependencies({
    listProfiles: vi.fn(() =>
      Promise.resolve([
        {
          id: "p-1",
          name: "DeepSeek",
          baseUrl: "https://api.example.com/v1",
          model: "deepseek-v4-flash",
          timeoutMs: 45_000,
          jsonSchemaSupport: "unknown" as const,
        },
        {
          id: "p-2",
          name: "Local",
          baseUrl: "http://localhost:11434/v1",
          model: "qwen",
          timeoutMs: 45_000,
          jsonSchemaSupport: "unknown" as const,
        },
      ]),
    ),
    getProfile: vi.fn((profileId: string) =>
      Promise.resolve({
        id: profileId,
        name: "DeepSeek",
        baseUrl: "https://api.example.com/v1",
        apiKey: "secret",
        model: "deepseek-v4-flash",
        headers: {},
        timeoutMs: 45_000,
        jsonSchemaSupport: "unknown" as const,
      }),
    ),
    getActiveProfileId: vi.fn(() => Promise.resolve("p-1")),
  });
  await createOptionsPage(root(), subject);

  const select = document.querySelector<HTMLSelectElement>("#options-saved-profile")!;
  expect([...select.options].map((option) => option.textContent)).toEqual([
    "新建配置",
    "DeepSeek · deepseek-v4-flash（启用中）",
    "Local · qwen",
  ]);

  // 载入非启用配置 → 按钮可点,点击后设为启用
  select.value = "p-2";
  select.dispatchEvent(new Event("change"));
  const activate = document.querySelector<HTMLButtonElement>("[data-action='activate-profile']")!;
  await vi.waitFor(() => expect(activate.disabled).toBe(false));
  expect(activate.textContent).toBe("设为启用");

  activate.click();
  await vi.waitFor(() => expect(subject.setActiveProfile).toHaveBeenCalledWith("p-2"));
  expect(activate.textContent).toBe("已启用");
  expect(activate.disabled).toBe(true);
});
```

- [ ] **Step 2: 跑测试确认失败** — `npx vitest run src/options/options.test.ts` → 预期 FAIL(deps 类型报错/按钮不存在)。

- [ ] **Step 3: 实现** — `src/options/options.ts` 改四处:

① `OptionsDependencies` 接口加:

```ts
getActiveProfileId: () => Promise<string | undefined>;
setActiveProfile: (profileId: string) => Promise<void>;
```

② 表单 `actions` 一行(`actions.append(saveButton, testButton);` 处)加第三个按钮:

```ts
const activateButton = element("button", "options-page__secondary", "设为启用");
activateButton.type = "button";
activateButton.dataset.action = "activate-profile";
activateButton.disabled = true;
actions.append(saveButton, testButton, activateButton);
```

③ `loadProfiles` 重写为(带启用标记;注意它现在要读 activeId):

```ts
const loadProfiles = async (selectedId = ""): Promise<void> => {
  const [profiles, activeId] = await Promise.all([
    dependencies.listProfiles(),
    dependencies.getActiveProfileId(),
  ]);
  savedSelect.replaceChildren(element("option", undefined, "新建配置"));
  savedSelect.options[0]!.value = "";
  for (const profile of profiles) {
    const suffix = profile.id === activeId ? "（启用中）" : "";
    const option = element("option", undefined, `${profile.name} · ${profile.model}${suffix}`);
    option.value = profile.id;
    savedSelect.append(option);
  }
  savedSelect.value = selectedId;
  await refreshActivateButton();
};

const refreshActivateButton = async (): Promise<void> => {
  const activeId = await dependencies.getActiveProfileId();
  const selected = savedSelect.value;
  if (selected === "") {
    activateButton.disabled = true;
    activateButton.textContent = "设为启用";
  } else if (selected === activeId) {
    activateButton.disabled = true;
    activateButton.textContent = "已启用";
  } else {
    activateButton.disabled = false;
    activateButton.textContent = "设为启用";
  }
};
```

(把 `refreshActivateButton` 定义放在 `loadProfiles` 之前,避免使用前未定义。)

④ 事件与 runtime 依赖:`savedSelect` 的 change 监听改成同时刷新按钮,并新增 activate 监听:

```ts
savedSelect.addEventListener("change", () => {
  void loadProfile(savedSelect.value);
  void refreshActivateButton();
});
activateButton.addEventListener("click", () => {
  void (async () => {
    await dependencies.setActiveProfile(savedSelect.value);
    await loadProfiles(savedSelect.value);
    result.textContent = "已切换启用配置，随后的解析请求将使用它。";
  })();
});
```

`runtimeDependencies()` 返回对象加:

```ts
    getActiveProfileId: () => repository.getActiveProfileId(),
    setActiveProfile: (profileId) => repository.setActiveProfile(profileId),
```

- [ ] **Step 4: 跑测试确认通过** — `npx vitest run src/options/options.test.ts` → 预期全 PASS。

- [ ] **Step 5: 提交**

```bash
npx prettier --write src/options/options.ts src/options/options.test.ts
git add src/options/
git commit -m "feat(extension): activate a saved profile from the options page"
```

---

### Task 3: 彩色细下划线标注(去底色)

**Files:**

- Modify: `src/content/learning-block.ts`
- Modify: `src/content/learning-block.test.ts`

- [ ] **Step 1: 写失败测试** — 在 `src/content/learning-block.test.ts` 里追加(`analysis` 的三个成分 role 依次是 SUBJECT/PREDICATE/OBJECT,fixture 已存在):

```ts
it("colors each component underline by grammar role without any backdrop", () => {
  const element = block();
  document.body.append(element.host);

  element.renderCore(sentence, tokens, analysis);

  const root = element.host.shadowRoot!;
  const colors = [...root.querySelectorAll<HTMLElement>(".component")].map((component) =>
    component.style.getPropertyValue("--syntax-role-color"),
  );
  expect(colors).toEqual(["#2563eb", "#dc2626", "#059669"]); // 主语蓝、谓语红、宾语绿

  const styles = root.querySelector("style")!.textContent!;
  expect(styles).toContain("border-bottom: 1.5px solid");
  expect(styles).toContain("var(--syntax-role-color)");
  // 成分不再有底色块
  expect(styles).not.toMatch(/\.component\s*\{[^}]*background:\s*color-mix/u);
});
```

- [ ] **Step 2: 跑测试确认失败** — `npx vitest run src/content/learning-block.test.ts` → 预期 FAIL。

- [ ] **Step 3: 实现** — `src/content/learning-block.ts`:

① import 行补 `GrammarRole`:

```ts
import { GRAMMAR_LABELS, GrammarRole } from "../shared/grammar";
```

② 在 `STYLES` 常量前加色表(从句沿用对应主成分色;其余灰):

```ts
const ROLE_COLORS: Readonly<Record<GrammarRole, string>> = {
  [GrammarRole.SUBJECT]: "#2563eb",
  [GrammarRole.PREDICATE]: "#dc2626",
  [GrammarRole.OBJECT]: "#059669",
  [GrammarRole.PREDICATIVE]: "#0891b2",
  [GrammarRole.ATTRIBUTE]: "#7c3aed",
  [GrammarRole.ADVERBIAL]: "#d97706",
  [GrammarRole.COMPLEMENT]: "#0891b2",
  [GrammarRole.APPOSITIVE]: "#6b7280",
  [GrammarRole.SUBJECT_CLAUSE]: "#2563eb",
  [GrammarRole.OBJECT_CLAUSE]: "#059669",
  [GrammarRole.PREDICATIVE_CLAUSE]: "#0891b2",
  [GrammarRole.ATTRIBUTIVE_CLAUSE]: "#7c3aed",
  [GrammarRole.ADVERBIAL_CLAUSE]: "#d97706",
  [GrammarRole.INDEPENDENT_ELEMENT]: "#6b7280",
};
```

③ `STYLES` 里三段替换:

`.sentence` 的 `column-gap: 0.7em;` → `column-gap: 0.5em;`

`.component` 块整体替换为(去 padding/圆角/底色,保留三行网格与居中):

```css
.component {
  appearance: none;
  display: inline-grid;
  grid-template-rows: repeat(3, auto);
  justify-items: center;
  min-inline-size: 0;
  max-inline-size: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  font: inherit;
  color: inherit;
  text-align: center;
  cursor: pointer;
  overflow-wrap: anywhere;
  transition: opacity 120ms ease;
}

.component:hover {
  opacity: 0.72;
}
```

`.role` / `.english` / `.translation` 三块替换为:

```css
.role {
  font-size: max(11px, 0.68em);
  color: var(--syntax-role-color, currentColor);
  opacity: 0.85;
}

.english {
  border-bottom: 1.5px solid
    color-mix(in srgb, var(--syntax-role-color, currentColor) 60%, transparent);
  justify-self: stretch;
  text-align: center;
}

.translation {
  font-size: max(12px, 0.8em);
  opacity: 0.78;
}
```

④ `renderCore` 里创建 `componentElement` 后(`componentElement.dataset.endToken = ...` 之后)加一行:

```ts
componentElement.style.setProperty("--syntax-role-color", ROLE_COLORS[component.role]);
```

- [ ] **Step 4: 跑测试确认通过** — `npx vitest run src/content/learning-block.test.ts` → 预期全 PASS。

- [ ] **Step 5: 提交**

```bash
npx prettier --write src/content/learning-block.ts src/content/learning-block.test.ts
git add src/content/learning-block.ts src/content/learning-block.test.ts
git commit -m "feat(extension): role-colored hairline underlines, no component backdrop"
```

---

### Task 4: 右下角进度胶囊

**Files:**

- Create: `src/content/progress-pill.ts`
- Create: `src/content/progress-pill.test.ts`
- Modify: `src/content/content-script.ts`(`installContentScript` 的 `relayStatus` 回调处接线)

- [ ] **Step 1: 写失败测试** — 新建 `src/content/progress-pill.test.ts`:

```ts
// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SessionStatus } from "../shared/protocol";
import { SyntaxProgressPill } from "./progress-pill";

function status(partial: Partial<SessionStatus>): SessionStatus {
  return { state: "stopped", discovered: 0, queued: 0, ready: 0, failed: 0, ...partial };
}

describe("SyntaxProgressPill", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    document.body.replaceChildren();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  const pillText = (pill: SyntaxProgressPill): string =>
    pill.host.shadowRoot!.textContent!.replace(/\s+/gu, " ").trim();

  it("appears with live counts while the session is running", () => {
    const pill = new SyntaxProgressPill();

    pill.update(status({ state: "running", discovered: 5, queued: 3, ready: 2 }));

    expect(pill.host.isConnected).toBe(true);
    expect(pillText(pill)).toContain("句法解析中 2/5");
  });

  it("shows the paused state", () => {
    const pill = new SyntaxProgressPill();

    pill.update(status({ state: "paused", discovered: 5, queued: 3, ready: 2 }));

    expect(pillText(pill)).toContain("已暂停");
  });

  it("announces completion with failures and fades away", () => {
    const pill = new SyntaxProgressPill();

    pill.update(status({ state: "running", discovered: 5, ready: 4, failed: 1 }));

    expect(pillText(pill)).toContain("完成");
    expect(pillText(pill)).toContain("1 句失败");
    vi.advanceTimersByTime(2600);
    expect(pill.host.isConnected).toBe(false);
  });

  it("cancels a pending fade when new sentences arrive", () => {
    const pill = new SyntaxProgressPill();

    pill.update(status({ state: "running", discovered: 2, ready: 2 }));
    vi.advanceTimersByTime(1000);
    pill.update(status({ state: "running", discovered: 4, queued: 2, ready: 2 }));
    vi.advanceTimersByTime(2600);

    expect(pill.host.isConnected).toBe(true);
    expect(pillText(pill)).toContain("2/4");
  });

  it("disappears immediately when the session stops", () => {
    const pill = new SyntaxProgressPill();

    pill.update(status({ state: "running", discovered: 5, queued: 5 }));
    pill.update(status({ state: "stopped" }));

    expect(pill.host.isConnected).toBe(false);
  });
});
```

- [ ] **Step 2: 跑测试确认失败** — `npx vitest run src/content/progress-pill.test.ts` → 预期 FAIL(模块不存在)。

- [ ] **Step 3: 实现** — 新建 `src/content/progress-pill.ts`:

```ts
import type { SessionStatus } from "../shared/protocol";

const FADE_DELAY_MS = 2500;

const STYLES = `
:host {
  all: initial;
}

.pill {
  position: fixed;
  right: 16px;
  bottom: 16px;
  z-index: 2147483646;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  border-radius: 999px;
  background: rgba(28, 28, 30, 0.88);
  color: #fff;
  font: 13px/1.3 system-ui, sans-serif;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.28);
  transition: opacity 300ms ease;
}

.pill[data-fading] {
  opacity: 0;
}

.spinner {
  inline-size: 12px;
  block-size: 12px;
  border: 2px solid #fff;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}

@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation: none;
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
`;

function isComplete(status: SessionStatus): boolean {
  return (
    status.discovered > 0 &&
    status.queued === 0 &&
    status.ready + status.failed >= status.discovered
  );
}

/**
 * A page-corner progress pill fed by SessionController status updates. Pure
 * display: it never sends commands.
 */
export class SyntaxProgressPill {
  readonly host: HTMLElement;
  readonly #pill: HTMLElement;
  readonly #spinner: HTMLElement;
  readonly #label: HTMLElement;
  readonly #document: Document;
  #fadeTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(doc: Document = document) {
    this.#document = doc;
    this.host = doc.createElement("div");
    this.host.dataset.syntaxProgressPill = "";
    const shadow = this.host.attachShadow({ mode: "open" });
    const style = doc.createElement("style");
    style.textContent = STYLES;
    this.#pill = doc.createElement("div");
    this.#pill.className = "pill";
    this.#pill.setAttribute("role", "status");
    this.#spinner = doc.createElement("span");
    this.#spinner.className = "spinner";
    this.#label = doc.createElement("span");
    this.#pill.append(this.#spinner, this.#label);
    shadow.append(style, this.#pill);
  }

  update(status: SessionStatus): void {
    if (status.state === "stopped") {
      this.remove();
      return;
    }
    this.#cancelFade();
    const done = status.ready + status.failed;
    if (status.state === "paused") {
      this.#render(`⏸ 已暂停 ${done}/${status.discovered}`, false);
      return;
    }
    if (isComplete(status)) {
      this.#render(status.failed > 0 ? `✓ 完成，${status.failed} 句失败` : "✓ 解析完成", false);
      this.#fadeTimer = setTimeout(() => this.remove(), FADE_DELAY_MS);
      return;
    }
    if (status.discovered === 0) {
      this.#render("句法解析中…", true);
      return;
    }
    this.#render(`句法解析中 ${done}/${status.discovered}`, true);
  }

  remove(): void {
    this.#cancelFade();
    this.host.remove();
  }

  #render(text: string, spinning: boolean): void {
    this.#label.textContent = text;
    this.#spinner.style.display = spinning ? "" : "none";
    this.#pill.removeAttribute("data-fading");
    if (!this.host.isConnected) {
      this.#document.documentElement.append(this.host);
    }
  }

  #cancelFade(): void {
    if (this.#fadeTimer !== undefined) {
      clearTimeout(this.#fadeTimer);
      this.#fadeTimer = undefined;
    }
  }
}
```

(淡出动画简化为定时移除,`transition` 留给将来;测试只断言 2.5s 后移除。)

- [ ] **Step 4: 跑测试确认通过** — `npx vitest run src/content/progress-pill.test.ts` → 预期全 PASS。

- [ ] **Step 5: 接线** — `src/content/content-script.ts` 的 `installContentScript()` 里:

顶部 import 区加:

```ts
import { SyntaxProgressPill } from "./progress-pill";
```

`installContentScript` 内 `let statusCounter = 0;` 后加一行,并在 `relayStatus` 回调里同步喂给胶囊:

```ts
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
```

- [ ] **Step 6: 全量单测 + 构建** — `npm test && npm run build` → 预期全绿。

- [ ] **Step 7: 提交**

```bash
npx prettier --write src/content/progress-pill.ts src/content/progress-pill.test.ts src/content/content-script.ts
git add src/content/
git commit -m "feat(extension): corner progress pill driven by session status"
```

---

### Task 5: E2E 更新与新增

**Files:**

- Modify: `tests/e2e/extension.spec.ts`

- [ ] **Step 1: 更新 popup 空配置测试** — 把 `test("the popup guides setup when no model profile exists", ...)` 整体替换为:

```ts
test("the popup guides setup when no model profile exists", async ({ harness }) => {
  const page = await harness.context.newPage();
  await page.goto(harness.popupUrl);

  const primary = page.locator("[data-primary]");
  await expect(primary).toHaveText("去配置模型");
  await expect(primary).toBeEnabled();
  await expect(page.locator("[data-subline]")).toContainText("尚未配置模型");
  await expect(page.locator("select")).toHaveCount(0);
});
```

- [ ] **Step 2: 新增进度胶囊 E2E** — 在同文件末尾追加(参考同文件其他用例获取 `startSession`/页面装载的既有模式;若已有「start session → component visible」的用例,直接在其中插入胶囊断言也可,二选一,别重复建会话流程):

```ts
test("a progress pill appears during analysis and fades after completion", async ({ harness }) => {
  const { page, tabId } = await harness.openArticle("error-single.html");
  await harness.startSession(tabId);

  const pill = page.locator("[data-syntax-progress-pill]");
  await expect(pill).toBeVisible({ timeout: 15_000 });
  await expect(page.locator(".component").first()).toBeVisible({ timeout: 15_000 });
  await expect(pill).toBeHidden({ timeout: 15_000 });
});
```

**注意:** `harness.openArticle` / `harness.startSession` 是示意——先读 `tests/e2e/fixtures.ts` 与相邻用例,用仓库里实际的装载/启动辅助函数替换成同样语义的调用;胶囊 host 在宿主页 light DOM,`[data-syntax-progress-pill]` 直接可查。

- [ ] **Step 3: 跑 E2E** — `npm run build && npx playwright test` → 预期全 PASS(16 旧用例含 popup 改名后的 + 1 新用例)。

- [ ] **Step 4: 提交**

```bash
npx prettier --write tests/e2e/extension.spec.ts
git add tests/e2e/extension.spec.ts
git commit -m "test(extension): cover minimal popup and progress pill end to end"
```

---

### Task 6: README 与真机验收

**Files:**

- Modify: `README.md`(若「使用」章节提到已删除的 popup 按钮/指标)

- [ ] **Step 1: 核对 README** — `grep -n "重新解析\|停止并恢复\|指标\|下拉" README.md`;把提及已删 UI 的句子改为新交互描述(一个主按钮:开始学习→解析中可暂停→完成后恢复原文;模型切换在设置页「设为启用」;右下角胶囊显示进度)。

- [ ] **Step 2: 全部门禁** — `npm test && npm run lint && npm run build && npx playwright test && npm run format:check` → 预期全绿。

- [ ] **Step 3: 真机截图验收** — 用会话里既有的 spike 模式(Playwright + 补 `host_permissions` 的临时 manifest 副本 + `DEEPSEEK_API_KEY` 环境变量,模型 `deepseek-v4-flash`)对 `tests/fixtures/pages/dynamic-article.html` 跑一次真实分析,截图确认:彩色细下划线、无灰底、右下角胶囊出现并在完成后消失;popup 打开是紧凑三元素。截图供用户过目后删除 spike 脚本。

- [ ] **Step 4: 提交收尾**

```bash
git add README.md
git commit -m "docs(extension): describe the simplified popup and progress pill"
```

---

## 自审记录

- **Spec 覆盖**:极简 popup(Task 1)、Retina bug(Task 1 Step 4)、设置页启用(Task 2)、彩色下划线+去底色+0.5em 间距(Task 3)、进度胶囊+onStatus 接线(Task 4)、E2E(Task 5)、真机验收(Task 6)。spec「不做的事」均未出现。
- **占位符**:Task 5 Step 2 的 harness 辅助函数名标注了「示意,先读 fixtures.ts 替换」,是刻意留给执行者按仓库真实 API 对齐,非空占位。
- **类型一致性**:`SyntaxProgressPill.update(SessionStatus)`/`host` 在 Task 4 与 Task 5 的选择器 `[data-syntax-progress-pill]` 一致;popup `PopupCommand` 收窄后 runtime 消息类型仍是 protocol 里的合法 RequestMessage;options 新 deps 与 ConfigRepository 现有方法一一对应。
