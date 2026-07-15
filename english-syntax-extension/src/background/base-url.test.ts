import { describe, expect, it } from "vitest";
import { chatCompletionsUrl, hostPermissionPattern, normalizeBaseUrl } from "./base-url";

describe("model profile URL safety", () => {
  it("normalizes a trailing slash from an HTTPS base URL", () => {
    expect(normalizeBaseUrl("https://api.deepseek.com/v1/")).toBe("https://api.deepseek.com/v1");
  });

  it("appends the chat completions endpoint", () => {
    expect(chatCompletionsUrl("https://api.deepseek.com/v1")).toBe(
      "https://api.deepseek.com/v1/chat/completions",
    );
  });

  it("does not append a duplicate chat completions endpoint", () => {
    expect(chatCompletionsUrl("http://localhost:11434/v1/chat/completions")).toBe(
      "http://localhost:11434/v1/chat/completions",
    );
  });

  it.each(["http://localhost:11434/v1", "http://127.0.0.1:11434/v1"])(
    "allows a local HTTP model endpoint at %s",
    (baseUrl) => {
      expect(normalizeBaseUrl(baseUrl)).toBe(baseUrl);
    },
  );

  it("rejects remote HTTP model endpoints", () => {
    expect(() => normalizeBaseUrl("http://api.example.com/v1")).toThrow("HTTPS");
  });

  it("rejects embedded URL credentials", () => {
    expect(() => normalizeBaseUrl("https://user:pass@example.com/v1")).toThrow("credentials");
  });

  it("derives an exact-origin host permission", () => {
    expect(hostPermissionPattern("https://api.deepseek.com:8443/v1")).toBe(
      "https://api.deepseek.com:8443/*",
    );
  });
});
