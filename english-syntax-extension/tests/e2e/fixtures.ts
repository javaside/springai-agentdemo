import { test as base, chromium, type BrowserContext, type Worker } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { FakeOpenAiServer } from "../support/fake-openai-server";

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, "..", "..");
const distDir = join(projectRoot, "dist");
const fixturePagesDir = join(projectRoot, "tests", "fixtures", "pages");

/**
 * The MV3 optional host-permission prompt is a native Chrome dialog that cannot
 * be dismissed in headless automation, so the end-to-end build promotes the two
 * localhost model hosts to required `host_permissions`. Everything else in the
 * manifest — including the untrusted-context storage restriction — is unchanged,
 * so the tests exercise the real background, content and UI code paths. The
 * canonical dist manifest keeps hosts optional; extension.spec.ts asserts that.
 */
function buildPatchedExtension(): string {
  execFileSync("npm", ["run", "build"], { cwd: projectRoot, stdio: "ignore" });
  const patchedDir = mkdtempSync(join(tmpdir(), "syntax-ext-e2e-"));
  cpSync(distDir, patchedDir, { recursive: true });
  const manifestPath = join(patchedDir, "manifest.json");
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8")) as {
    host_permissions?: string[];
    optional_host_permissions?: string[];
  };
  manifest.host_permissions = ["http://127.0.0.1/*", "http://localhost/*"];
  manifest.optional_host_permissions = ["https://*/*"];
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
  return patchedDir;
}

function startFixturePageServer(): Promise<{ server: Server; origin: string }> {
  const server = createServer((request, response) => {
    const requestPath = (request.url ?? "/").split("?")[0]!;
    const fileName = normalize(requestPath).replace(/^([/\\])+/, "");
    const filePath = join(fixturePagesDir, fileName);
    if (!filePath.startsWith(fixturePagesDir)) {
      response.writeHead(403).end("forbidden");
      return;
    }
    try {
      const body = readFileSync(filePath);
      response.writeHead(200, { "content-type": "text/html; charset=utf-8" });
      response.end(body);
    } catch {
      response.writeHead(404).end("not found");
    }
  });
  return new Promise((resolveServer) => {
    server.listen(0, "127.0.0.1", () => {
      const port = (server.address() as AddressInfo).port;
      resolveServer({ server, origin: `http://127.0.0.1:${port}` });
    });
  });
}

async function findServiceWorker(context: BrowserContext): Promise<Worker> {
  const [existing] = context.serviceWorkers();
  const worker = existing ?? (await context.waitForEvent("serviceworker", { timeout: 15_000 }));
  // Wait one macrotask so the worker finishes evaluating its top-level module.
  await worker.evaluate(() => new Promise<void>((done) => setTimeout(done, 0)));
  return worker;
}

export interface ModelProfileSeed {
  id: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  headers?: Record<string, string>;
  timeoutMs?: number;
  jsonSchemaSupport?: "unknown" | "supported" | "unsupported";
}

export interface ExtensionHarness {
  context: BrowserContext;
  serviceWorker: Worker;
  extensionId: string;
  fakeModel: FakeOpenAiServer;
  pagesOrigin: string;
  optionsUrl: string;
  popupUrl: string;
  /** Persist model profiles and select an active one via the trusted extension context. */
  seedProfiles(profiles: ModelProfileSeed[], activeProfileId: string): Promise<void>;
  /** Return the numeric tab id for an already-open page URL. */
  tabIdFor(url: string): Promise<number>;
  /** Dispatch a trusted-UI request into the service worker as if sent by the popup. */
  dispatchFromUi(message: Record<string, unknown>): Promise<unknown>;
}

interface WorkerScope {
  patchedExtensionDir: string;
}

export const test = base.extend<{ harness: ExtensionHarness }, WorkerScope>({
  patchedExtensionDir: [
    // Playwright fixtures declare dependencies by destructuring; none needed here.
    // eslint-disable-next-line no-empty-pattern
    async ({}, use) => {
      const patchedDir = buildPatchedExtension();
      try {
        await use(patchedDir);
      } finally {
        rmSync(patchedDir, { recursive: true, force: true });
      }
    },
    { scope: "worker" },
  ],

  harness: async ({ patchedExtensionDir }, use) => {
    const userDataDir = mkdtempSync(join(tmpdir(), "syntax-ext-profile-"));
    const { server, origin } = await startFixturePageServer();
    const fakeModel = await FakeOpenAiServer.start();

    const context = await chromium.launchPersistentContext(userDataDir, {
      channel: "chromium",
      headless: true,
      timeout: 30_000,
      args: [
        `--disable-extensions-except=${patchedExtensionDir}`,
        `--load-extension=${patchedExtensionDir}`,
        "--no-first-run",
      ],
    });

    const serviceWorker = await findServiceWorker(context);
    const extensionId = new URL(serviceWorker.url()).host;

    const harness: ExtensionHarness = {
      context,
      serviceWorker,
      extensionId,
      fakeModel,
      pagesOrigin: origin,
      optionsUrl: `chrome-extension://${extensionId}/src/options/options.html`,
      popupUrl: `chrome-extension://${extensionId}/src/popup/popup.html`,
      async seedProfiles(profiles, activeProfileId) {
        await serviceWorker.evaluate(
          async ({ profiles: seeds, activeProfileId: activeId }) => {
            const stored = seeds.map((profile) => ({
              id: profile.id,
              name: profile.name,
              baseUrl: profile.baseUrl.replace(/\/$/, ""),
              apiKey: profile.apiKey,
              model: profile.model,
              headers: profile.headers ?? {},
              timeoutMs: profile.timeoutMs ?? 30_000,
              jsonSchemaSupport: profile.jsonSchemaSupport ?? "supported",
            }));
            await chrome.storage.local.set({
              "profiles.v1": stored,
              "activeProfileId.v1": activeId,
            });
          },
          { profiles, activeProfileId },
        );
      },
      async tabIdFor(url) {
        return serviceWorker.evaluate(async (target: string) => {
          const tabs = await chrome.tabs.query({});
          const tab = tabs.find((candidate) => candidate.url === target);
          if (tab?.id === undefined) throw new Error(`No tab found for ${target}`);
          return tab.id;
        }, url);
      },
      async dispatchFromUi(message) {
        return serviceWorker.evaluate(
          ({ message: payload, extensionId: id }) =>
            new Promise((resolveResponse) => {
              const sender = { id, url: `chrome-extension://${id}/src/popup/popup.html` };
              const event = chrome.runtime.onMessage as unknown as {
                dispatch(
                  message: unknown,
                  sender: unknown,
                  sendResponse: (response: unknown) => void,
                ): unknown;
              };
              event.dispatch(payload, sender, (response: unknown) => resolveResponse(response));
            }),
          { message, extensionId },
        );
      },
    };

    try {
      await use(harness);
    } finally {
      await context.close();
      await fakeModel.stop();
      await new Promise<void>((resolveClose) => server.close(() => resolveClose()));
      rmSync(userDataDir, { recursive: true, force: true });
    }
  },
});

export const expect = test.expect;
