import { describe, expect, it } from "vitest";
import { isRequestMessage } from "./protocol";

describe("request protocol guard", () => {
  it("rejects an unversioned content message", () => {
    expect(isRequestMessage({ type: "ANALYZE_CORE" })).toBe(false);
  });

  it("accepts a complete request without page context", () => {
    expect(
      isRequestMessage({
        version: 1,
        requestId: "request-1",
        type: "GET_CACHE_STATS",
      }),
    ).toBe(true);
  });

  it("requires page correlation identifiers for page requests", () => {
    expect(
      isRequestMessage({
        version: 1,
        requestId: "request-1",
        type: "GET_SESSION_STATUS",
        tabId: 7,
      }),
    ).toBe(false);
  });

  it("rejects unknown properties as executable instructions", () => {
    expect(
      isRequestMessage({
        version: 1,
        requestId: "request-1",
        type: "GET_CACHE_STATS",
        command: "DELETE_ALL_DATA",
      }),
    ).toBe(false);
  });
});
