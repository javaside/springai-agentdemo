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

test("clicking a component lazily loads its detail once and re-clicking toggles it", async ({
  harness,
}) => {
  await seedLocalProfile(harness);
  const { page } = await startSession(harness, "error-single.html");
  await expect(learningBlocks(page)).toHaveCount(1, { timeout: 20_000 });

  const component = page.locator(".component").first();
  await component.click();
  await expect(page.locator(".detail")).toContainText("详细语法解析", { timeout: 15_000 });
  expect(harness.fakeModel.recordedOfKind("detail")).toHaveLength(1);

  // A second click on the same component folds the explanation away.
  await component.click();
  await expect(page.locator(".detail")).toHaveCount(0);

  // Reopening is served from the extension cache, so the model is still
  // consulted exactly once.
  await component.click();
  await expect(page.locator(".detail")).toContainText("详细语法解析", { timeout: 15_000 });
  expect(harness.fakeModel.recordedOfKind("detail")).toHaveLength(1);
});

test("a detail panel opens inside its own sentence, moves on other clicks, and restore leaves no artifacts", async ({
  harness,
}) => {
  await seedLocalProfile(harness);
  const page = await openArticle(harness, "dynamic-article.html");
  const originalPair = await page.locator("#pair").evaluate((node) => node.outerHTML);
  const tabId = await harness.tabIdFor(`${harness.pagesOrigin}/dynamic-article.html`);
  const documentId = "e2e-doc-detail-cycle";
  await harness.dispatchFromUi(uiMessage("START_SESSION", { tabId, documentId }));
  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });

  // #pair holds two sentences in one block; open the first sentence's detail.
  const pairBlock = learningBlocks(page).nth(3);
  const firstComponent = pairBlock.locator(".sentence").first().locator(".component").first();
  await firstComponent.click();
  await expect(pairBlock.locator(".detail")).toContainText("详细语法解析", { timeout: 15_000 });

  const placement = await pairBlock.evaluate((host) => {
    const root = host.shadowRoot!;
    const detail = root.querySelector(".detail")!;
    const sentences = [...root.querySelectorAll(".sentence")];
    return {
      detailCount: root.querySelectorAll(".detail").length,
      ownerIsFirstSentence: detail.closest(".sentence") === sentences[0],
      detailBottom: detail.getBoundingClientRect().bottom,
      secondSentenceTop: sentences[1]!.getBoundingClientRect().top,
    };
  });
  expect(placement.detailCount).toBe(1);
  expect(placement.ownerIsFirstSentence).toBe(true);
  // The explanation sits directly under its own sentence, never below the
  // sentences that follow it.
  expect(placement.detailBottom).toBeLessThanOrEqual(placement.secondSentenceTop + 0.5);

  // Clicking a component of the second sentence moves the single open panel.
  await pairBlock.locator(".sentence").nth(1).locator(".component").first().click();
  await expect
    .poll(async () =>
      pairBlock.evaluate((host) => {
        const root = host.shadowRoot!;
        const details = [...root.querySelectorAll(".detail")];
        const sentences = [...root.querySelectorAll(".sentence")];
        return {
          count: details.length,
          inSecondSentence: details[0] === undefined ? false : sentences[1]!.contains(details[0]),
        };
      }),
    )
    .toEqual({ count: 1, inSecondSentence: true });

  // Clicking a component in another block closes this block's panel too:
  // only one explanation stays open across the whole page.
  const introComponent = learningBlocks(page).nth(1).locator(".component").first();
  await introComponent.click();
  await expect(pairBlock.locator(".detail")).toHaveCount(0);
  await expect(page.locator(".detail")).toHaveCount(1);

  // Stop the session while a panel is open: the page keeps zero extension
  // nodes and the original paragraph returns byte-for-byte.
  const stopped = await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId }));
  expect(stopped).toMatchObject({ type: "SESSION_STATUS", status: { state: "stopped" } });
  await expect(learningBlocks(page)).toHaveCount(0);
  await expect(page.locator(".detail")).toHaveCount(0);
  await expect(page.locator("style[data-syntax-learning-hide]")).toHaveCount(0);
  await expect(page.locator("[data-syntax-progress-pill]")).toHaveCount(0);
  expect(await page.locator("#pair").evaluate((node) => node.outerHTML)).toBe(originalPair);
  await expect(page.locator("#pair")).toBeVisible();
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

  const primary = page.locator("[data-primary]");
  await expect(primary).toHaveText("去配置模型");
  await expect(primary).toBeEnabled();
  await expect(page.locator("[data-subline]")).toContainText("尚未配置模型");
  await expect(page.locator("select")).toHaveCount(0);
});

test("the popup button walks 开始学习 → 解析中 → 继续学习 and finally restores the page", async ({
  harness,
}) => {
  await seedLocalProfile(harness, "popup-flow-model");
  // Hold the first core call back for a beat so the running state stays
  // observable between clicks instead of racing straight to completion.
  harness.fakeModel.script("popup-flow-model", [
    { kind: "http", status: 429, body: '{"error":"slow down"}', retryAfter: "1" },
  ]);
  const articlePage = await openArticle(harness, "dynamic-article.html");
  // A popup rendered in a normal tab carries sender.tab, which the service
  // worker rightly distrusts, and Playwright cannot attach to the native
  // toolbar popup window. Bridging sendMessage through dispatchFromUi keeps
  // every click on the real popup DOM while messages arrive with the trusted
  // popup sender the toolbar would provide.
  const popupPage = await harness.context.newPage();
  await popupPage.exposeFunction("__syntaxDispatchFromUi", (message: Record<string, unknown>) =>
    harness.dispatchFromUi(message),
  );
  await popupPage.addInitScript(() => {
    const bridge = window as unknown as {
      __syntaxDispatchFromUi(message: unknown): Promise<unknown>;
    };
    chrome.runtime.sendMessage = (message: unknown) => bridge.__syntaxDispatchFromUi(message);
  });
  await popupPage.goto(harness.popupUrl);
  // The popup resolves its target from the active tab at load time, so the
  // article must hold focus while the popup renders in a background tab.
  await articlePage.bringToFront();
  await popupPage.reload();

  const primary = popupPage.locator("[data-primary]");
  await expect(primary).toHaveText("开始学习");
  await expect(popupPage.locator("[data-subline]")).toContainText("popup-flow-model");

  await primary.click();
  await expect(primary).toContainText("解析中");
  await expect(primary).toContainText("点击暂停");

  await primary.click();
  await expect(primary).toHaveText("继续学习");

  await primary.click();
  await expect(learningBlocks(articlePage)).toHaveCount(4, { timeout: 20_000 });
  // The below-fold sentence stays queued until it scrolls into view, so bring
  // it in to let the session finish. The pill only fades once every discovered
  // sentence has resolved, so its disappearance marks the completed session
  // the reopened popup must see.
  await articlePage.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await expect(learningBlocks(articlePage)).toHaveCount(5, { timeout: 20_000 });
  await expect(articlePage.locator("[data-syntax-progress-pill] .pill")).toBeHidden({
    timeout: 15_000,
  });

  await popupPage.reload();
  await expect(primary).toHaveText("恢复网页原文");
  await primary.click();
  await expect(primary).toHaveText("开始学习");
  await expect(learningBlocks(articlePage)).toHaveCount(0);
  await expect(articlePage.locator("[data-syntax-progress-pill]")).toHaveCount(0);
  await expect(articlePage.locator("#intro")).toBeVisible();
});

test("activating a saved profile from the options page routes new sessions to it", async ({
  harness,
}) => {
  const page = await harness.context.newPage();
  await page.goto(harness.optionsUrl);
  const select = page.locator("#options-saved-profile");

  const saveProfile = async (name: string, model: string, apiKey: string): Promise<void> => {
    await select.selectOption("");
    await page.locator("#options-profile-name").fill(name);
    await page.locator("#options-base-url").fill(harness.fakeModel.baseUrl);
    await page.locator("#options-api-key").fill(apiKey);
    await page.locator("#options-model").fill(model);
    await page.locator("#options-timeout").fill("30");
    await page.locator("button[type='submit']").click();
    await expect(select.locator("option", { hasText: `${name} · ${model}` })).toHaveCount(1);
  };

  await saveProfile("Model A", "model-a", "sk-a");
  await saveProfile("Model B", "model-b", "sk-b");
  await expect(select.locator("option", { hasText: "Model A · model-a（启用中）" })).toHaveCount(1);

  const activate = page.locator("[data-action='activate-profile']");
  await select.selectOption({ label: "Model B · model-b" });
  await expect(activate).toHaveText("设为启用");
  await expect(activate).toBeEnabled();
  await activate.click();

  await expect(page.locator("[data-connection-result]")).toContainText("已切换启用配置");
  await expect(activate).toHaveText("已启用");
  await expect(activate).toBeDisabled();
  await expect(select.locator("option", { hasText: "Model B · model-b（启用中）" })).toHaveCount(1);
  await expect(select.locator("option", { hasText: "Model A · model-a（启用中）" })).toHaveCount(0);

  const { page: article } = await startSession(harness, "dynamic-article.html");
  await expect(learningBlocks(article)).toHaveCount(4, { timeout: 20_000 });
  expect(new Set(harness.fakeModel.recordedOfKind("core").map(({ model }) => model))).toEqual(
    new Set(["model-b"]),
  );
});

test("a progress pill appears during analysis and disappears after completion", async ({
  harness,
}) => {
  await seedLocalProfile(harness);
  const { page } = await startSession(harness, "error-single.html");

  // The host div itself has zero size (its shadow pill is position: fixed),
  // so visibility must be asserted on the shadow content, which Playwright's
  // CSS engine reaches through the open shadow root.
  const pill = page.locator("[data-syntax-progress-pill] .pill");
  await expect(pill).toBeVisible({ timeout: 15_000 });
  await expect(page.locator(".component").first()).toBeVisible({ timeout: 15_000 });
  await expect(pill).toBeHidden({ timeout: 15_000 });
});

test("a compound sentence renders numbered coordinate clauses and an annotated detail panel", async ({
  harness,
}) => {
  await seedLocalProfile(harness, "compound-model");
  harness.fakeModel.script("compound-model", [{ kind: "compound" }, { kind: "compound-detail" }]);
  const page = await openArticle(harness, "compound-article.html");
  const originalParagraph = await page.locator("#compound").evaluate((node) => node.outerHTML);
  const tabId = await harness.tabIdFor(`${harness.pagesOrigin}/compound-article.html`);
  const documentId = "e2e-doc-compound";
  await harness.dispatchFromUi(uiMessage("START_SESSION", { tabId, documentId }));
  await expect(learningBlocks(page)).toHaveCount(1, { timeout: 20_000 });

  // 正文：并列分句①/② + 并列连词，及各自配色。
  await expect(page.locator(".component .role")).toHaveText(["并列分句①", "并列连词", "并列分句②"]);
  const componentColors = await learningBlocks(page)
    .first()
    .evaluate((host) =>
      [...host.shadowRoot!.querySelectorAll<HTMLElement>(".component")].map((component) =>
        component.style.getPropertyValue("--syntax-role-color"),
      ),
    );
  expect(componentColors).toEqual(["#0d9488", "#6b7280", "#0d9488"]);

  // 点击第一个并列分句 → 详解面板出现两行式标注区与 ①②③ 对应解释。
  const firstClause = page.locator(".component").first();
  await firstClause.click();
  await expect(page.locator(".detail")).toContainText("详细语法解析", { timeout: 15_000 });
  const detail = await learningBlocks(page)
    .first()
    .evaluate((host) => {
      const root = host.shadowRoot!;
      return {
        annotations: [...root.querySelectorAll<HTMLElement>(".annotation")].map((annotation) => ({
          rows: [...annotation.children].map((child) => [child.className, child.textContent]),
          color: annotation.style.getPropertyValue("--syntax-role-color"),
        })),
        structures: [...root.querySelectorAll(".detail-structure")].map((row) => row.textContent),
        grammarPoints: root.querySelector(".grammar-points")?.textContent,
        summary: root.querySelector(".detail-summary")?.textContent,
      };
    });
  expect(detail.annotations).toEqual([
    {
      rows: [
        ["annotation-role", "① 主语"],
        ["annotation-english", "The sun"],
        ["annotation-translation", "太阳"],
      ],
      color: "#2563eb",
    },
    {
      rows: [
        ["annotation-role", "② 谓语"],
        ["annotation-english", "rose"],
        ["annotation-translation", "升起"],
      ],
      color: "#dc2626",
    },
    {
      rows: [
        ["annotation-role", "③ 并列连词"],
        ["annotation-english", "and"],
        ["annotation-translation", "和"],
      ],
      color: "#6b7280",
    },
  ]);
  expect(detail.structures).toEqual([
    "① 主语：The sun 是第一分句的主语。",
    "② 谓语：rose 是第一分句的谓语动词。",
    "③ 并列连词：and 连接前后两个并列分句。",
  ]);
  expect(detail.grammarPoints).toBe("并列句");
  expect(detail.summary).toBe("这是针对所选并列分句的详细语法解析。");
  expect(harness.fakeModel.recordedOfKind("detail")).toHaveLength(1);

  // 再点收起。
  await firstClause.click();
  await expect(page.locator(".detail")).toHaveCount(0);

  // STOP 还原零残留不回归。
  const stopped = await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId }));
  expect(stopped).toMatchObject({ type: "SESSION_STATUS", status: { state: "stopped" } });
  await expect(learningBlocks(page)).toHaveCount(0);
  await expect(page.locator("style[data-syntax-learning-hide]")).toHaveCount(0);
  expect(await page.locator("#compound").evaluate((node) => node.outerHTML)).toBe(
    originalParagraph,
  );
  await expect(page.locator("#compound")).toBeVisible();
});

test("重开会话零请求命中缓存，重新解析强制二次请求", async ({ harness }) => {
  await seedLocalProfile(harness);
  const { page, tabId, documentId } = await startSession(harness, "dynamic-article.html");
  // 等全部近视口块解析完成再采样冷启动请求数，避免中途欠采样。
  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });
  const coldCalls = harness.fakeModel.recordedOfKind("core").length;
  expect(coldCalls).toBeGreaterThan(0);

  // 二次会话（模拟重开页面）：应全部命中缓存，core 请求数不变。
  await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId }));
  const secondDocument = `${documentId}-revisit`;
  await harness.dispatchFromUi(uiMessage("START_SESSION", { tabId, documentId: secondDocument }));
  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });
  expect(harness.fakeModel.recordedOfKind("core")).toHaveLength(coldCalls);

  // 重新解析：必须绕过缓存，真实再次请求模型。重析可见句集与冷启动同一批，
  // 故 core 请求数应停在冷启动的两倍；轮询到该值即等到全部重析请求落定，
  // 避免迟到请求漏进第三次会话比对。
  await harness.dispatchFromUi(
    uiMessage("REANALYZE_VISIBLE", { tabId, documentId: secondDocument }),
  );
  await expect
    .poll(() => harness.fakeModel.recordedOfKind("core").length, { timeout: 20_000 })
    .toBe(coldCalls * 2);
  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });
  const reanalyzedCalls = harness.fakeModel.recordedOfKind("core").length;

  // 三次会话：重析结果已覆盖写回缓存，且 bypass 是一次性的——再开会话仍零新请求。
  await harness.dispatchFromUi(uiMessage("STOP_SESSION", { tabId, documentId: secondDocument }));
  const thirdDocument = `${documentId}-after-reanalyze`;
  await harness.dispatchFromUi(uiMessage("START_SESSION", { tabId, documentId: thirdDocument }));
  await expect(learningBlocks(page)).toHaveCount(4, { timeout: 20_000 });
  expect(harness.fakeModel.recordedOfKind("core")).toHaveLength(reanalyzedCalls);
});
