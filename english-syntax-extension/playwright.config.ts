import { defineConfig } from "@playwright/test";

/**
 * End-to-end tests load the real built extension into a persistent Chromium
 * context. They never touch the network beyond the local fake model and fixture
 * page servers, so they run without any external dependency. Tests share Chrome
 * permission and storage state through a persistent profile, so they run
 * sequentially inside one worker.
 */
export default defineConfig({
  testDir: "./tests/e2e",
  testMatch: /.*\.spec\.ts/,
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],
  use: {
    trace: "on-first-retry",
  },
});
