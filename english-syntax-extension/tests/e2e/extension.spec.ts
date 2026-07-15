import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { Page } from "@playwright/test";
import { test, expect } from "./fixtures";
import type { ExtensionHarness } from "./fixtures";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");

const NEAR_SENTENCES = [
  "Reading English articles builds strong grammar intuition.",
  "The little girl reads an interesting story in the quiet library.",
  "Our teacher explained the difficult grammar rule very clearly.",
  "The old man walked slowly across the busy street.",
  "He carried a heavy bag of fresh vegetables.",
];
const FAR_SENTENCE = "Distant paragraphs wait patiently below the visible viewport boundary.";
const SINGLE_SENTENCE = "Curious students always ask thoughtful questions during class.";

let requestCounter = 0;

function uiMessage(type: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { version: 1, requestId: `e2e:${type}:${++requestCounter}`, type, ...extra };
}

async function seedLocalProfile(
  harness: ExtensionHarness,
  model = "e2e-model",
  apiKey = "sk-e2e-secret",
  id = "profile-e2e",
): Promise<void> {
  await harness.seedProfiles(
    [{ id, name: `Local ${model}`, baseUrl: harness.fakeModel.baseUrl, apiKey, model }],
    id,
  );
}

async function openArticle(harness: ExtensionHarness, path: string): Promise<Page> {
  const page = await harness.context.newPage();
  await page.goto(`${harness.pagesOrigin}/${path}`);
  return page;
}

async function startSession(
  harness: ExtensionHarness,
  path: string,
  documentId = `e2e-doc-${++requestCounter}`,
): Promise<{ page: Page; tabId: number; documentId: string }> {
  const page = await openArticle(harness, path);
  const tabId = await harness.tabIdFor(`${harness.pagesOrigin}/${path}`);
  const response = await harness.dispatchFromUi(uiMessage("START_SESSION", { tabId, documentId }));
  expect(response, JSON.stringify(response)).toMatchObject({ type: "SESSION_STATUS" });
  return { page, tabId, documentId };
}

function learningBlocks(page: Page) {
  return page.locator("[data-syntax-learning-block]");
}

test("options page saves a localhost profile, holds the exact loopback grant and verifies the connection", async ({
  harness,
}) => {
  const page = await harness.context.newPage();
  await page.goto(harness.optionsUrl);
  await page.locator("#options-profile-name").fill("Local Ollama");
  await page.locator("#options-base-url").fill(harness.fakeModel.baseUrl);
  await page.locator("#options-api-key").fill("sk-options-secret");
  await page.locator("#options-model").fill("probe-model");
  await page.locator("#options-timeout").fill("30");

  await page.locator("[data-action='test-profile']").click();
  await expect(page.locator("[data-connection-result]")).toHaveText(
    "连接成功，模型支持 JSON Schema。",
  );

  const probes = harness.fakeModel.recordedOfKind("probe");
  expect(probes.length).toBeGreaterThan(0);
  for (const probe of probes) {
    expect(probe.url).toBe("/v1/chat/completions");
    expect(probe.authorizationPresent).toBe(true);
    expect(probe.model).toBe("probe-model");
    expect(probe.promptText).not.toContain("sk-options-secret");
  }

  const origins = await harness.serviceWorker.evaluate(async () => {
    const permissions = await chrome.permissions.getAll();
    return (permissions.origins ?? []).sort();
  });
  expect(origins).toEqual(["http://127.0.0.1/*", "http://localhost/*"]);

  // The first profile saved through the options page must become active, or
  // a fresh install passes the connection test yet fails every analysis
  // with CONFIG_MISSING.
  const activeProfileId = await harness.serviceWorker.evaluate(async () => {
    const stored = await chrome.storage.local.get("activeProfileId.v1");
    return stored["activeProfileId.v1"];
  });
  expect(typeof activeProfileId).toBe("string");
});

test("the shipped manifest keeps model hosts optional", () => {
  const manifest = JSON.parse(
    readFileSync(join(projectRoot, "dist", "manifest.json"), "utf8"),
  ) as Record<string, unknown>;
  expect(manifest.manifest_version).toBe(3);
  expect(manifest).not.toHaveProperty("host_permissions");
  expect(manifest.optional_host_permissions).toEqual([
    "https://*/*",
    "http://localhost/*",
    "http://127.0.0.1/*",
  ]);
});

test("starting on an article analyzes only viewport-adjacent blocks into three-row components", async ({
  harness,
}) => {
  await seedLocalProfile(harness);
  const { page } = await startSession(harness, "dynamic-article.html");

  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });

  const requestedTexts = harness.fakeModel
    .recordedOfKind("core")
    .flatMap((request) => request.sentenceTexts)
    .sort();
  expect(requestedTexts).toEqual([...NEAR_SENTENCES].sort());
  expect(requestedTexts).not.toContain(FAR_SENTENCE);
  await expect(page.locator("#far")).toBeVisible();

  const structure = await learningBlocks(page)
    .last()
    .evaluate((host) => {
      const root = host.shadowRoot!;
      return [...root.querySelectorAll(".sentence")].map((sentence) => ({
        rowKinds: [...sentence.children].map((child) => child.className),
        components: [...sentence.querySelectorAll(".component")].map((component) =>
          [...component.children].map((child) => child.className),
        ),
      }));
    });
  expect(structure).toHaveLength(2);
  for (const sentence of structure) {
    expect(sentence.components.length).toBeGreaterThanOrEqual(2);
    for (const component of sentence.components) {
      expect(component).toEqual(["role", "english", "translation"]);
    }
    for (const rowKind of sentence.rowKinds) {
      expect(["component", "punctuation"]).toContain(rowKind);
    }
  }
});

test("scrolling analyzes the below-fold block incrementally", async ({ harness }) => {
  await seedLocalProfile(harness);
  const { page } = await startSession(harness, "dynamic-article.html");
  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });
  harness.fakeModel.clearRecorded();

  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));

  await expect(learningBlocks(page)).toHaveCount(5, { timeout: 20_000 });
  const requestedTexts = harness.fakeModel
    .recordedOfKind("core")
    .flatMap((request) => request.sentenceTexts);
  expect(requestedTexts).toEqual([FAR_SENTENCE]);
});

test("reloading the same article renders from the cache with zero model calls", async ({
  harness,
}) => {
  await seedLocalProfile(harness);
  const { page, tabId, documentId } = await startSession(harness, "dynamic-article.html");
  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });

  await page.reload();
  harness.fakeModel.clearRecorded();
  const response = await harness.dispatchFromUi(uiMessage("START_SESSION", { tabId, documentId }));
  expect(response).toMatchObject({ type: "SESSION_STATUS" });

  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });
  expect(harness.fakeModel.recordedOfKind("core", "core-repair", "correction")).toEqual([]);
});

test("clicking a component lazily loads its detail exactly once", async ({ harness }) => {
  await seedLocalProfile(harness);
  const { page } = await startSession(harness, "error-single.html");
  await expect(learningBlocks(page)).toHaveCount(1, { timeout: 20_000 });

  const component = page.locator(".component").first();
  await component.click();
  await expect(page.locator(".detail")).toContainText("详细语法解析", { timeout: 15_000 });
  expect(harness.fakeModel.recordedOfKind("detail")).toHaveLength(1);

  await component.click();
  await expect(page.locator(".detail")).toContainText("详细语法解析");
  expect(harness.fakeModel.recordedOfKind("detail")).toHaveLength(1);
});

test("switching profiles keeps rendered provenance and routes only new work to the new model", async ({
  harness,
}) => {
  await harness.seedProfiles(
    [
      {
        id: "profile-a",
        name: "Model A",
        baseUrl: harness.fakeModel.baseUrl,
        apiKey: "sk-a",
        model: "model-a",
      },
      {
        id: "profile-b",
        name: "Model B",
        baseUrl: harness.fakeModel.baseUrl,
        apiKey: "sk-b",
        model: "model-b",
      },
    ],
    "profile-a",
  );
  const { page, tabId, documentId } = await startSession(harness, "dynamic-article.html");
  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });
  expect(new Set(harness.fakeModel.recordedOfKind("core").map(({ model }) => model))).toEqual(
    new Set(["model-a"]),
  );
  const introTranslationCount = await learningBlocks(page)
    .first()
    .evaluate((host) => host.shadowRoot!.querySelectorAll(".translation").length);
  expect(introTranslationCount).toBeGreaterThan(0);

  harness.fakeModel.clearRecorded();
  const switched = await harness.dispatchFromUi(
    uiMessage("SWITCH_PROFILE", { tabId, documentId, profileId: "profile-b" }),
  );
  expect(switched).toMatchObject({ type: "ACK" });
  await page.waitForTimeout(500);
  expect(harness.fakeModel.recorded()).toEqual([]);
  await expect(learningBlocks(page)).toHaveCount(4);

  await page.evaluate(() => {
    document.querySelector("#mutable")!.textContent =
      "Our students practiced the new grammar pattern with great enthusiasm.";
  });
  await expect
    .poll(() => harness.fakeModel.recordedOfKind("core").length, { timeout: 15_000 })
    .toBeGreaterThan(0);
  const newRequests = harness.fakeModel.recordedOfKind("core");
  expect(new Set(newRequests.map(({ model }) => model))).toEqual(new Set(["model-b"]));
  expect(newRequests.flatMap(({ sentenceTexts }) => sentenceTexts)).toEqual([
    "Our students practiced the new grammar pattern with great enthusiasm.",
  ]);
});

test("a reader correction is cached per feedback text", async ({ harness }) => {
  await seedLocalProfile(harness);
  const { page, tabId } = await startSession(harness, "error-single.html");
  await expect(learningBlocks(page)).toHaveCount(1, { timeout: 20_000 });

  const submitCorrection = async (feedback: string): Promise<void> => {
    await harness.serviceWorker.evaluate(
      async ({ tabId: target, feedback: text }) => {
        await chrome.scripting.executeScript({
          target: { tabId: target },
          func: (correctionFeedback: string) => {
            const sentence = document
              .querySelector("[data-syntax-learning-block]")!
              .shadowRoot!.querySelector<HTMLElement>(".sentence")!;
            document.dispatchEvent(
              new CustomEvent("syntax-correction-request", {
                detail: { sentenceId: sentence.dataset.sentenceId, feedback: correctionFeedback },
              }),
            );
          },
          args: [text],
        });
      },
      { tabId, feedback },
    );
  };

  await submitCorrection("主语的划分不正确");
  await expect(page.locator(".translation").first()).toContainText("已纠正", { timeout: 15_000 });
  expect(harness.fakeModel.recordedOfKind("correction")).toHaveLength(1);
  expect(harness.fakeModel.recordedOfKind("correction")[0]!.promptText).toContain(
    "主语的划分不正确",
  );

  await submitCorrection("主语的划分不正确");
  await page.waitForTimeout(600);
  expect(harness.fakeModel.recordedOfKind("correction")).toHaveLength(1);

  await submitCorrection("请改用另一种成分划分");
  await expect.poll(() => harness.fakeModel.recordedOfKind("correction").length).toBe(2);
});

test("pause blocks new dispatch and stop restores the exact original elements", async ({
  harness,
}) => {
  await seedLocalProfile(harness);
  const page = await openArticle(harness, "dynamic-article.html");
  const originalIntro = await page.locator("#intro").evaluate((node) => node.outerHTML);
  const tabId = await harness.tabIdFor(`${harness.pagesOrigin}/dynamic-article.html`);
  const documentId = "e2e-doc-pause";
  await harness.dispatchFromUi(uiMessage("START_SESSION", { tabId, documentId }));
  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });

  const paused = await harness.dispatchFromUi(uiMessage("PAUSE_SESSION", { tabId, documentId }));
  expect(paused).toMatchObject({ type: "SESSION_STATUS", status: { state: "paused" } });
  harness.fakeModel.clearRecorded();
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(900);
  expect(harness.fakeModel.recorded()).toEqual([]);
  await expect(learningBlocks(page)).toHaveCount(4);

  const stopped = await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId }));
  expect(stopped).toMatchObject({ type: "SESSION_STATUS", status: { state: "stopped" } });
  await expect(learningBlocks(page)).toHaveCount(0);
  expect(await page.locator("#intro").evaluate((node) => node.outerHTML)).toBe(originalIntro);
  await expect(page.locator("style[data-syntax-learning-hide]")).toHaveCount(0);
  await expect(page.locator("#intro")).toBeVisible();
});

test("an authentication failure pauses the profile until its credentials change", async ({
  harness,
}) => {
  await seedLocalProfile(harness, "auth-model", "sk-revoked");
  harness.fakeModel.script("auth-model", [
    { kind: "http", status: 401, body: '{"error":"invalid api key"}' },
  ]);
  const { page } = await startSession(harness, "error-single.html");

  const failure = page.locator(".sentence-failure");
  await expect(failure).toContainText("AUTH_FAILED", { timeout: 20_000 });
  await expect(failure).toContainText(SINGLE_SENTENCE);

  harness.fakeModel.clearRecorded();
  await page.locator("button.retry").click();
  await expect(page.locator(".sentence-failure")).toContainText("AUTH_FAILED");
  await page.waitForTimeout(400);
  expect(harness.fakeModel.recorded()).toEqual([]);

  await seedLocalProfile(harness, "auth-model", "sk-rotated-valid");
  await page.locator("button.retry").click();
  await expect(page.locator(".component").first()).toBeVisible({ timeout: 15_000 });
  expect(harness.fakeModel.recordedOfKind("core").length).toBeGreaterThan(0);
});

test("a rate limit with Retry-After is retried transparently", async ({ harness }) => {
  await seedLocalProfile(harness, "busy-model");
  harness.fakeModel.script("busy-model", [
    { kind: "http", status: 429, body: '{"error":"slow down"}', retryAfter: "0" },
  ]);
  const { page } = await startSession(harness, "error-single.html");

  await expect(learningBlocks(page)).toHaveCount(1, { timeout: 20_000 });
  await expect(page.locator(".component").first()).toBeVisible();
  expect(harness.fakeModel.recordedOfKind("core")).toHaveLength(2);
});

test("a partial batch failure isolates one sentence while its sibling renders", async ({
  harness,
}) => {
  await seedLocalProfile(harness, "partial-model");
  harness.fakeModel.script("partial-model", [{ kind: "partial" }, { kind: "coverage-gap" }]);
  const { page } = await startSession(harness, "error-pair.html");

  await expect(page.locator(".sentence-failure")).toContainText("INVALID_MODEL_OUTPUT", {
    timeout: 20_000,
  });
  await expect(page.locator(".sentence-failure")).toContainText(
    "Fresh bread smells drift from the corner bakery.",
  );
  await expect(page.locator("section.sentence")).toHaveCount(1);
  await expect(page.locator("section.sentence .component").first()).toBeVisible();
  expect(harness.fakeModel.recordedOfKind("core")).toHaveLength(1);
  expect(harness.fakeModel.recordedOfKind("core-repair")).toHaveLength(1);
});

test("an invalid response envelope fails the block visibly instead of hanging", async ({
  harness,
}) => {
  await seedLocalProfile(harness, "garbage-model");
  harness.fakeModel.script("garbage-model", [{ kind: "invalid-json" }]);
  const { page } = await startSession(harness, "error-single.html");

  const failure = page.locator(".sentence-failure");
  await expect(failure).toContainText("INVALID_MODEL_OUTPUT", { timeout: 20_000 });
  await expect(failure).toContainText(SINGLE_SENTENCE);
});

test("a script-like translation renders as literal text and never executes", async ({
  harness,
}) => {
  await seedLocalProfile(harness, "hostile-model");
  const payload = "<img src=x onerror=\"document.title='xss-executed'\">字面翻译";
  harness.fakeModel.script("hostile-model", [{ kind: "xss", payload }]);
  const { page } = await startSession(harness, "error-single.html");

  await expect(learningBlocks(page)).toHaveCount(1, { timeout: 20_000 });
  await expect(page.locator(".translation").first()).toHaveText(payload);
  const shadowInventory = await learningBlocks(page)
    .first()
    .evaluate((host) => ({
      images: host.shadowRoot!.querySelectorAll("img").length,
      scripts: host.shadowRoot!.querySelectorAll("script").length,
    }));
  expect(shadowInventory).toEqual({ images: 0, scripts: 0 });
  expect(await page.title()).not.toBe("xss-executed");
});

test("content scripts cannot read the extension's trusted storage", async ({ harness }) => {
  await seedLocalProfile(harness);
  const { tabId } = await startSession(harness, "error-single.html");

  const outcome = await harness.serviceWorker.evaluate(async (target: number) => {
    const [result] = await chrome.scripting.executeScript({
      target: { tabId: target },
      func: async () => {
        try {
          const stored: unknown = await chrome.storage.local.get("profiles.v1");
          return `allowed:${JSON.stringify(stored)}`;
        } catch (error) {
          return `blocked:${String(error)}`;
        }
      },
    });
    return String(result?.result);
  }, tabId);

  expect(outcome).toMatch(/^blocked:/);
  expect(outcome).toMatch(/not allowed/i);
  expect(outcome).not.toContain("sk-e2e-secret");
});

test("the popup guides setup when no model profile exists", async ({ harness }) => {
  const page = await harness.context.newPage();
  await page.goto(harness.popupUrl);

  await expect(page.locator("#popup-profile")).toBeDisabled();
  await expect(page.locator("#popup-profile option")).toHaveText(["尚未配置模型"]);
  await expect(page.locator(".popup-page__notice")).toContainText("此页面不支持句法解析");
  await expect(page.locator("[data-action='start']")).toBeDisabled();
});
