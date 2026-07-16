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
  });

  afterEach(() => {
    vi.useRealTimers();
    for (const host of document.documentElement.querySelectorAll("[data-syntax-progress-pill]")) {
      host.remove();
    }
  });

  const pillText = (pill: SyntaxProgressPill): string =>
    pill.host.shadowRoot!.querySelector(".pill")!.textContent!.replace(/\s+/gu, " ").trim();

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
