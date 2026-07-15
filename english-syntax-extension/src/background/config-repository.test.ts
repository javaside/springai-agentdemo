import { afterEach, describe, expect, it, vi } from "vitest";
import type { ModelProfile } from "./config-repository";
import { ConfigRepository } from "./config-repository";

const profile: ModelProfile = {
  id: "deepseek",
  name: "DeepSeek",
  baseUrl: "https://api.deepseek.com/v1/",
  apiKey: "secret-api-key",
  model: "deepseek-chat",
  headers: { "X-Tenant": "syntax-team" },
  timeoutMs: 30_000,
  jsonSchemaSupport: "unknown",
};

function storageMock(initial: Record<string, unknown> = {}) {
  const values = structuredClone(initial);

  return {
    values,
    area: {
      get: vi.fn((keys: string | string[]) => {
        const requested = Array.isArray(keys) ? keys : [keys];
        return Promise.resolve(
          Object.fromEntries(
            requested
              .filter((key) => key in values)
              .map((key) => [key, structuredClone(values[key])]),
          ),
        );
      }),
      set: vi.fn((items: Record<string, unknown>) => {
        Object.assign(values, structuredClone(items));
        return Promise.resolve();
      }),
    },
  };
}

describe("ConfigRepository", () => {
  it("round-trips a validated profile through versioned local storage", async () => {
    const storage = storageMock();
    const repository = new ConfigRepository(storage.area);

    await repository.saveProfile(profile);

    expect(storage.values["profiles.v1"]).toEqual([
      { ...profile, baseUrl: "https://api.deepseek.com/v1" },
    ]);
    expect(await repository.getProfile(profile.id)).toEqual({
      ...profile,
      baseUrl: "https://api.deepseek.com/v1",
    });
  });

  it("selects and returns the active profile", async () => {
    const repository = new ConfigRepository(storageMock().area);
    await repository.saveProfile(profile);

    await repository.setActiveProfile(profile.id);

    expect(await repository.getActiveProfile()).toEqual({
      ...profile,
      baseUrl: "https://api.deepseek.com/v1",
    });
  });

  it.each([
    "authorization",
    "AUTHORIZATION",
    "Host",
    "CONTENT-LENGTH",
    "Origin",
    "X-Syntax-Request-Id",
  ])("rejects the forbidden custom header %s case-insensitively", async (header) => {
    const repository = new ConfigRepository(storageMock().area);

    await expect(
      repository.saveProfile({ ...profile, headers: { [header]: "forbidden" } }),
    ).rejects.toThrow("header");
  });

  it.each([4_999, 120_001])("rejects timeout %d outside the allowed bounds", async (timeoutMs) => {
    const repository = new ConfigRepository(storageMock().area);

    await expect(repository.saveProfile({ ...profile, timeoutMs })).rejects.toThrow("timeout");
  });

  it.each([5_000, 120_000])("accepts timeout boundary %d", async (timeoutMs) => {
    const repository = new ConfigRepository(storageMock().area);

    await repository.saveProfile({ ...profile, timeoutMs });

    expect((await repository.getProfile(profile.id))?.timeoutMs).toBe(timeoutMs);
  });

  it("returns only public profile fields to untrusted callers", async () => {
    const repository = new ConfigRepository(storageMock().area);
    await repository.saveProfile(profile);

    const publicProfiles = await repository.listPublicProfiles();

    expect(publicProfiles).toEqual([
      {
        id: "deepseek",
        name: "DeepSeek",
        baseUrl: "https://api.deepseek.com/v1",
        model: "deepseek-chat",
        timeoutMs: 30_000,
        jsonSchemaSupport: "unknown",
      },
    ]);
    expect(JSON.stringify(publicProfiles)).not.toContain(profile.apiKey);
    expect(JSON.stringify(publicProfiles)).not.toContain("syntax-team");
  });

  it("defaults the analysis cache limit to 50 MB and persists an allowed choice", async () => {
    const storage = storageMock();
    const repository = new ConfigRepository(storage.area);

    await expect(repository.getCacheLimitBytes()).resolves.toBe(50 * 1024 * 1024);
    await repository.setCacheLimitMb(100);

    expect(storage.values["cacheLimitMb.v1"]).toBe(100);
    await expect(repository.getCacheLimitBytes()).resolves.toBe(100 * 1024 * 1024);
  });

  it.each([0, 20, 51, 500])("rejects unsupported cache limit %d MB", async (limitMb) => {
    const repository = new ConfigRepository(storageMock().area);

    await expect(repository.setCacheLimitMb(limitMb)).rejects.toThrow("cache limit");
  });
});

describe("service worker security initialization", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it("awaits trusted-context storage access before registering message handlers", async () => {
    const events: string[] = [];
    let allowStorageAccess: (() => void) | undefined;
    let messageHandler: (() => unknown) | undefined;
    vi.stubGlobal("chrome", {
      storage: {
        local: {
          setAccessLevel: vi.fn(async () => {
            events.push("access-start");
            await new Promise<void>((resolve) => {
              allowStorageAccess = resolve;
            });
            events.push("access-complete");
          }),
        },
      },
      runtime: {
        onMessage: {
          addListener: vi.fn((handler: () => unknown) => {
            messageHandler = handler;
            events.push("handler-registered");
          }),
        },
      },
      permissions: { request: vi.fn() },
    });

    const serviceWorker = import("./service-worker");
    await vi.waitFor(() => expect(events).toEqual(["access-start"]));
    expect(events).not.toContain("handler-registered");

    allowStorageAccess?.();
    await serviceWorker;
    await vi.waitFor(() => expect(events).toContain("handler-registered"));

    expect(events).toEqual(["access-start", "access-complete", "handler-registered"]);
    expect(messageHandler?.()).toBeUndefined();
  });

  it("requests permission for only the profile's exact origin", async () => {
    const request = vi.fn(() => Promise.resolve(true));
    vi.stubGlobal("chrome", {
      storage: { local: { setAccessLevel: vi.fn(() => Promise.resolve()) } },
      runtime: { onMessage: { addListener: vi.fn() } },
      permissions: { request },
    });
    const { requestHostPermission } = await import("./service-worker");

    await expect(requestHostPermission(profile)).resolves.toBe(true);
    expect(request).toHaveBeenCalledWith({ origins: ["https://api.deepseek.com/*"] });
  });
});
