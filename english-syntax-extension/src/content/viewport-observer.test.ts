// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { CandidateBlock } from "./document-scanner";
import { ViewportObserver } from "./viewport-observer";

function block(id: string, top: number, bottom: number): CandidateBlock {
  const element = document.createElement("p");
  element.textContent = id;
  element.getBoundingClientRect = () => ({
    top,
    bottom,
    left: 0,
    right: 100,
    width: 100,
    height: bottom - top,
    x: 0,
    y: top,
    toJSON() {},
  });
  return { id, element, text: id };
}

describe("ViewportObserver fallback", () => {
  const originalIntersectionObserver = globalThis.IntersectionObserver;

  beforeEach(() => {
    Object.defineProperty(globalThis, "IntersectionObserver", {
      configurable: true,
      value: undefined,
    });
    Object.defineProperty(window, "innerHeight", { configurable: true, value: 100 });
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      callback(0);
      return 1;
    });
  });

  afterEach(() => {
    Object.defineProperty(globalThis, "IntersectionObserver", {
      configurable: true,
      value: originalIntersectionObserver,
    });
    vi.restoreAllMocks();
  });

  it("emits blocks in the viewport and one viewport above and below", () => {
    const seen: string[] = [];
    const observer = new ViewportObserver((candidate) => seen.push(candidate.id));

    observer.observe([
      block("too-high", -120, -101),
      block("above", -100, -90),
      block("current", 20, 40),
      block("below", 190, 200),
      block("too-low", 201, 220),
    ]);

    expect(seen).toEqual(["above", "current", "below"]);
    observer.disconnect();
  });

  it("deduplicates scroll emissions until invalidated", () => {
    const seen: string[] = [];
    const candidate = block("repeat", 10, 20);
    const observer = new ViewportObserver((entry) => seen.push(entry.id));
    observer.observe([candidate]);

    window.dispatchEvent(new Event("scroll"));
    observer.invalidate(candidate.id);
    window.dispatchEvent(new Event("scroll"));

    expect(seen).toEqual(["repeat", "repeat"]);
    observer.disconnect();
  });

  it("removes scroll and resize listeners when disconnected", () => {
    const remove = vi.spyOn(window, "removeEventListener");
    const observer = new ViewportObserver(() => undefined);

    observer.observe([block("one", 0, 10)]);
    observer.disconnect();

    expect(remove).toHaveBeenCalledWith("scroll", expect.any(Function));
    expect(remove).toHaveBeenCalledWith("resize", expect.any(Function));
  });
});

describe("ViewportObserver IntersectionObserver path", () => {
  it("uses a one-screen vertical rootMargin and supports invalidation", () => {
    let callback!: IntersectionObserverCallback;
    const observe = vi.fn();
    const disconnect = vi.fn();
    const constructor = vi.fn(function (
      this: IntersectionObserver,
      next: IntersectionObserverCallback,
    ) {
      callback = next;
      return {
        observe,
        disconnect,
        unobserve: vi.fn(),
        takeRecords: () => [],
        root: null,
        rootMargin: "",
        thresholds: [],
      };
    });
    Object.defineProperty(globalThis, "IntersectionObserver", {
      configurable: true,
      value: constructor,
    });
    const candidate = block("io", 0, 10);
    const seen: string[] = [];
    const observer = new ViewportObserver((entry) => seen.push(entry.id));
    observer.observe([candidate]);

    expect(constructor).toHaveBeenCalledWith(expect.any(Function), {
      rootMargin: "100% 0px 100% 0px",
    });
    expect(observe).toHaveBeenCalledWith(candidate.element);

    const entry = { target: candidate.element, isIntersecting: true } as IntersectionObserverEntry;
    callback([entry], {} as IntersectionObserver);
    callback([entry], {} as IntersectionObserver);
    observer.invalidate(candidate.id);
    callback([entry], {} as IntersectionObserver);

    expect(seen).toEqual(["io", "io"]);
    observer.disconnect();
    expect(disconnect).toHaveBeenCalledOnce();
  });
});
