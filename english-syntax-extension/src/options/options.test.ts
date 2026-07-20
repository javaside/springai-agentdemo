// @vitest-environment happy-dom

import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { ExtensionErrorCode } from "../shared/errors";
import type { OptionsDependencies } from "./options";

let createOptionsPage: typeof import("./options").createOptionsPage;

beforeAll(async () => {
  const entryRoot = document.createElement("main");
  entryRoot.id = "app";
  document.body.append(entryRoot);
  ({ createOptionsPage } = await import("./options"));
});

function dependencies(overrides: Partial<OptionsDependencies> = {}): OptionsDependencies {
  return {
    listProfiles: vi.fn(() => Promise.resolve([])),
    getProfile: vi.fn(() => Promise.resolve(undefined)),
    saveProfile: vi.fn(() => Promise.resolve()),
    requestPermission: vi.fn(() => Promise.resolve(true)),
    testProfile: vi.fn(() =>
      Promise.resolve({ success: true, jsonSchemaSupport: "supported" as const }),
    ),
    getCacheStats: vi.fn(() =>
      Promise.resolve({ entries: 3, estimatedBytes: 1_500_000, limitBytes: 50_000_000 }),
    ),
    clearCache: vi.fn(() => Promise.resolve()),
    getCacheLimitMb: vi.fn(() => Promise.resolve(50)),
    setCacheLimitMb: vi.fn(() => Promise.resolve()),
    getActiveProfileId: vi.fn(() => Promise.resolve(undefined as string | undefined)),
    setActiveProfile: vi.fn(() => Promise.resolve()),
    confirm: vi.fn(() => true),
    exportCacheFile: vi.fn(() =>
      Promise.resolve({
        format: "english-syntax-cache" as const,
        formatVersion: 1 as const,
        schemaVersion: 1,
        exportedAt: "2026-07-20T00:00:00.000Z",
        core: [],
        detail: [],
      }),
    ),
    importCacheFile: vi.fn(() =>
      Promise.resolve({ ok: true as const, added: 2, skipped: 1, invalid: 0 }),
    ),
    ...overrides,
  };
}

function root(): HTMLElement {
  document.body.textContent = "";
  const element = document.createElement("main");
  document.body.append(element);
  return element;
}

function inputByLabel(label: string): HTMLInputElement {
  const labels = [...document.querySelectorAll("label")];
  const match = labels.find((element) => element.textContent?.includes(label));
  const id = match?.htmlFor;
  const input = id === undefined ? null : document.querySelector<HTMLInputElement>("#" + id);
  if (input === null) throw new Error("missing input for " + label);
  return input;
}

function fillRequiredProfile(): void {
  inputByLabel("显示名称").value = "DeepSeek";
  inputByLabel("Base URL").value = "https://api.example.com/v1";
  inputByLabel("API Key").value = "secret-value";
  inputByLabel("Model").value = "deepseek-chat";
  inputByLabel("请求超时").value = "45";
}

describe("Options page", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("renders accessible native profile fields and keeps the API key masked", async () => {
    await createOptionsPage(root(), dependencies());

    expect(inputByLabel("显示名称")).toBeInstanceOf(HTMLInputElement);
    expect(inputByLabel("Base URL")).toBeInstanceOf(HTMLInputElement);
    expect(inputByLabel("API Key").type).toBe("password");
    expect(inputByLabel("Model")).toBeInstanceOf(HTMLInputElement);
    expect(inputByLabel("请求超时").type).toBe("number");
    expect(document.body.textContent).toContain("自定义请求头");
    expect(document.querySelector("form")).toBeInstanceOf(HTMLFormElement);
    expect(document.querySelector("button[type='submit']")?.textContent).toBe("保存配置");
  });

  it("shows the local-storage privacy warning before the first save", async () => {
    await createOptionsPage(root(), dependencies());

    const warning = document.querySelector("[role='note']");
    expect(warning?.textContent).toContain("不是系统级加密保险箱");
    expect(warning?.textContent).toContain("个人可信设备");
  });

  it("requests only the normalized model origin when saving", async () => {
    const subject = dependencies();
    await createOptionsPage(root(), subject);
    fillRequiredProfile();

    document.querySelector<HTMLFormElement>("form")!.requestSubmit();
    await vi.waitFor(() => expect(subject.saveProfile).toHaveBeenCalledOnce());

    expect(subject.requestPermission).toHaveBeenCalledWith("https://api.example.com/*");
    expect(subject.saveProfile).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "DeepSeek",
        baseUrl: "https://api.example.com/v1",
        apiKey: "secret-value",
        model: "deepseek-chat",
        timeoutMs: 45_000,
      }),
    );
    expect(document.body.textContent).not.toContain("secret-value");
    expect(document.querySelector("[data-api-key]")).toBeNull();
  });

  it("renders repeatable custom headers and rejects forbidden names inline", async () => {
    const subject = dependencies();
    await createOptionsPage(root(), subject);
    fillRequiredProfile();
    document.querySelector<HTMLButtonElement>("[data-action='add-header']")!.click();
    const row = document.querySelector<HTMLElement>("[data-header-row]")!;
    row.querySelector<HTMLInputElement>("[data-header-name]")!.value = "Authorization";
    row.querySelector<HTMLInputElement>("[data-header-value]")!.value = "Bearer hidden";

    document.querySelector<HTMLFormElement>("form")!.requestSubmit();

    await vi.waitFor(() => {
      expect(row.querySelector("[role='alert']")?.textContent).toContain("不能使用");
    });
    expect(subject.saveProfile).not.toHaveBeenCalled();
    expect(document.body.textContent).not.toContain("Bearer hidden");
  });

  it("requests the exact origin before testing and distinguishes connection results", async () => {
    const cases: [ExtensionErrorCode | "JSON_UNSUPPORTED" | undefined, string][] = [
      [undefined, "连接成功，模型支持 JSON Schema"],
      ["HOST_PERMISSION_DENIED", "未获得模型地址访问权限"],
      ["NETWORK_ERROR", "网络连接失败"],
      ["AUTH_FAILED", "鉴权失败，请检查 API Key"],
      ["MODEL_NOT_FOUND", "未找到指定模型"],
      ["INVALID_MODEL_OUTPUT", "模型未能返回有效 JSON"],
      ["JSON_UNSUPPORTED", "连接成功，配置可用"],
    ];

    for (const [error, expected] of cases) {
      const subject = dependencies({
        testProfile: vi.fn(() =>
          Promise.resolve(
            error === undefined
              ? { success: true, jsonSchemaSupport: "supported" as const }
              : error === "JSON_UNSUPPORTED"
                ? { success: true, jsonSchemaSupport: "unsupported" as const }
                : { success: false, error },
          ),
        ),
      });
      await createOptionsPage(root(), subject);
      fillRequiredProfile();
      document.querySelector<HTMLButtonElement>("[data-action='test-profile']")!.click();

      await vi.waitFor(() =>
        expect(document.querySelector("[data-connection-result]")?.textContent).toContain(expected),
      );
      expect(subject.requestPermission).toHaveBeenCalledWith("https://api.example.com/*");
    }
  });

  it("shows the provider status and detail when the failure carries them", async () => {
    const subject = dependencies({
      testProfile: vi.fn(() =>
        Promise.resolve({
          success: false,
          error: "NETWORK_ERROR" as ExtensionErrorCode,
          status: 400,
          detail: '{"error":{"message":"messages: field required"}}',
        }),
      ),
    });
    await createOptionsPage(root(), subject);
    fillRequiredProfile();
    document.querySelector<HTMLButtonElement>("[data-action='test-profile']")!.click();

    await vi.waitFor(() => {
      const text = document.querySelector("[data-connection-result]")?.textContent ?? "";
      expect(text).toContain("HTTP 400");
      expect(text).toContain("messages: field required");
    });
  });

  it("explains that a saved API key is kept when the field is left empty", async () => {
    const subject = dependencies();
    await createOptionsPage(root(), subject);
    const apiKey = inputByLabel("API Key");
    expect(apiKey.placeholder).toContain("必填");
    fillRequiredProfile();

    document.querySelector<HTMLFormElement>("form")!.requestSubmit();
    await vi.waitFor(() => expect(subject.saveProfile).toHaveBeenCalledOnce());

    expect(apiKey.value).toBe("");
    expect(apiKey.placeholder).toContain("已保存");
    expect(apiKey.placeholder).toContain("留空");
  });

  it("offers the four cache limits and requires a button-triggered clear confirmation", async () => {
    const subject = dependencies({ confirm: vi.fn(() => false) });
    await createOptionsPage(root(), subject);

    const choices = [
      ...document.querySelectorAll<HTMLOptionElement>("#options-cache-limit option"),
    ];
    expect(choices.map((option) => option.textContent)).toEqual([
      "10 MB",
      "50 MB",
      "100 MB",
      "200 MB",
    ]);
    expect(document.body.textContent).toContain("3 条");

    const clear = document.querySelector<HTMLButtonElement>("[data-action='clear-cache']")!;
    clear.click();
    expect(subject.confirm).toHaveBeenCalledWith(expect.stringContaining("不会删除模型配置"));
    expect(subject.clearCache).not.toHaveBeenCalled();

    vi.mocked(subject.confirm).mockReturnValue(true);
    clear.click();
    await vi.waitFor(() => expect(subject.clearCache).toHaveBeenCalledOnce());
  });

  it("uses keyboard-operable controls with a CSS namespace and visible focus hook", async () => {
    await createOptionsPage(root(), dependencies());

    const buttons = [...document.querySelectorAll("button")];
    expect(buttons.length).toBeGreaterThan(2);
    expect(buttons.every((button) => button.tabIndex >= 0)).toBe(true);
    expect(document.querySelector(".options-page")).not.toBeNull();
    expect(document.querySelector("[data-focus-style='visible']")).not.toBeNull();
  });

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
    await vi.waitFor(() =>
      expect([...select.options].map((option) => option.textContent)).toEqual([
        "新建配置",
        "DeepSeek · deepseek-v4-flash",
        "Local · qwen（启用中）",
      ]),
    );
    expect(activate.textContent).toBe("已启用");
    expect(activate.disabled).toBe(true);
  });

  it("keeps the activate button usable when switching the profile fails", async () => {
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
          name: "Local",
          baseUrl: "http://localhost:11434/v1",
          apiKey: "secret",
          model: "qwen",
          headers: {},
          timeoutMs: 45_000,
          jsonSchemaSupport: "unknown" as const,
        }),
      ),
      getActiveProfileId: vi.fn(() => Promise.resolve("p-1")),
      setActiveProfile: vi.fn(() => Promise.reject(new Error("Unknown model profile"))),
    });
    await createOptionsPage(root(), subject);

    const select = document.querySelector<HTMLSelectElement>("#options-saved-profile")!;
    select.value = "p-2";
    select.dispatchEvent(new Event("change"));
    const activate = document.querySelector<HTMLButtonElement>("[data-action='activate-profile']")!;
    await vi.waitFor(() => expect(activate.disabled).toBe(false));

    activate.click();
    await vi.waitFor(() =>
      expect(document.querySelector("[data-connection-result]")?.textContent).toContain(
        "切换启用配置失败",
      ),
    );
    expect(activate.disabled).toBe(false);
    expect(activate.textContent).toBe("设为启用");
  });
});

describe("cache import/export", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
  });

  it("renders export and import controls inside the cache panel", async () => {
    await createOptionsPage(root(), dependencies());

    expect(document.querySelector("[data-action='export-cache']")?.textContent).toBe("导出缓存");
    expect(document.querySelector("[data-action='import-cache']")?.textContent).toBe("导入缓存");
    const fileInput = document.querySelector<HTMLInputElement>("[data-import-input]");
    expect(fileInput?.type).toBe("file");
    expect(fileInput?.accept).toBe(".json,application/json");
  });

  it("invokes the export dependency and triggers a download when 导出缓存 is clicked", async () => {
    const subject = dependencies();
    await createOptionsPage(root(), subject);
    const urlSpy = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:fake");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);

    document.querySelector<HTMLButtonElement>("[data-action='export-cache']")!.click();

    await vi.waitFor(() => expect(subject.exportCacheFile).toHaveBeenCalledOnce());
    expect(urlSpy).toHaveBeenCalledOnce();
    expect(document.body.textContent).toContain("已导出 0 条缓存");
  });

  it("shows merge counts and refreshes stats after a successful import", async () => {
    const subject = dependencies();
    await createOptionsPage(root(), subject);

    const fileInput = document.querySelector<HTMLInputElement>("[data-import-input]")!;
    const file = new File([JSON.stringify({ any: "thing" })], "cache.json", {
      type: "application/json",
    });
    Object.defineProperty(fileInput, "files", { value: [file], configurable: true });
    fileInput.dispatchEvent(new Event("change"));

    await vi.waitFor(() =>
      expect(document.body.textContent).toContain("新增 2 条，已有跳过 1 条，无效丢弃 0 条"),
    );
    expect(subject.importCacheFile).toHaveBeenCalledWith(JSON.stringify({ any: "thing" }));
    expect(subject.getCacheStats).toHaveBeenCalledTimes(2);
  });

  it("maps every import failure reason to a Chinese message", async () => {
    for (const [reason, message] of [
      ["not-json", "不是有效的 JSON"],
      ["bad-format", "文件格式不符"],
      ["schema-mismatch", "schema 版本不匹配"],
    ] as const) {
      const subject = dependencies({
        importCacheFile: vi.fn(() => Promise.resolve({ ok: false as const, reason })),
      });
      await createOptionsPage(root(), subject);
      const fileInput = document.querySelector<HTMLInputElement>("[data-import-input]")!;
      Object.defineProperty(fileInput, "files", {
        value: [new File(["x"], "cache.json")],
        configurable: true,
      });
      fileInput.dispatchEvent(new Event("change"));
      await vi.waitFor(() => expect(document.body.textContent).toContain(message));
    }
  });
});
