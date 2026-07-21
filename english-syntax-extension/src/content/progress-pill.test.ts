// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SessionStatus } from "../shared/protocol";
import { SyntaxProgressPill } from "./progress-pill";

function status(partial: Partial<SessionStatus>): SessionStatus {
  return { state: "stopped", discovered: 0, queued: 0, ready: 0, failed: 0, ...partial };
}

describe("SyntaxProgressPill", () => {
  let pill: SyntaxProgressPill;

  beforeEach(() => {
    vi.useFakeTimers();
    pill = new SyntaxProgressPill();
  });

  afterEach(() => {
    vi.useRealTimers();
    for (const host of document.documentElement.querySelectorAll("[data-syntax-progress-pill]")) {
      host.remove();
    }
  });

  const pillText = (pill: SyntaxProgressPill): string =>
    pill.host.shadowRoot!.querySelector(".pill")!.textContent.replace(/\s+/gu, " ").trim();

  const label = (): string =>
    pill.host
      .shadowRoot!.querySelector(".pill span:last-child")!
      .textContent.replace(/\s+/gu, " ")
      .trim();

  const spinnerVisible = (): boolean => {
    const spinner = pill.host.shadowRoot!.querySelector<HTMLElement>(".spinner")!;
    return spinner.style.display !== "none";
  };

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

  it("stays purely presentational: pointer events pass through", () => {
    const pill = new SyntaxProgressPill();

    pill.update(status({ state: "running", discovered: 5, queued: 5 }));

    const styles = pill.host.shadowRoot!.querySelector("style")!.textContent;
    expect(styles).toMatch(/:host\s*\{[^}]*pointer-events:\s*none/u);
    expect(styles).toMatch(/\.pill\s*\{[^}]*pointer-events:\s*none/u);
  });

  it("disappears immediately when the session stops", () => {
    const pill = new SyntaxProgressPill();

    pill.update(status({ state: "running", discovered: 5, queued: 5 }));
    pill.update(status({ state: "stopped" }));

    expect(pill.host.isConnected).toBe(false);
  });

  it("shows detail prefetch progress after the core phase completes", () => {
    pill.update(
      status({
        state: "running",
        discovered: 2,
        ready: 2,
        detailTotal: 6,
        detailReady: 3,
        detailFailed: 1,
      }),
    );
    expect(label()).toBe("详解预载中 4/6");
    expect(spinnerVisible()).toBe(true);
  });

  it("mentions failed details in the completion text", () => {
    pill.update(
      status({
        state: "running",
        discovered: 2,
        ready: 2,
        detailTotal: 6,
        detailReady: 4,
        detailFailed: 2,
      }),
    );
    expect(label()).toBe("✓ 解析完成（2 个详解失败）");
  });

  it("keeps the plain completion text when prefetch is off", () => {
    pill.update(status({ state: "running", discovered: 2, ready: 2 }));
    expect(label()).toBe("✓ 解析完成");
  });
});
