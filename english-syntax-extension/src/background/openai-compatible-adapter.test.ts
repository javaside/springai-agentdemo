import { describe, expect, it, vi } from "vitest";
import type { ModelProfile } from "./config-repository";
import {
  ModelRequestError,
  OpenAiCompatibleAdapter,
  type JsonSchemaSpec,
} from "./openai-compatible-adapter";
import { buildCorePrompt, buildDetailPrompt, buildRepairPrompt } from "./prompts";

const profile: ModelProfile = {
  id: "profile-1",
  name: "Compatible",
  baseUrl: "https://model.example/v1",
  apiKey: "secret",
  model: "syntax-model",
  headers: { "X-Tenant": "learning" },
  timeoutMs: 5_000,
  jsonSchemaSupport: "unknown",
};

const schema: JsonSchemaSpec = {
  name: "core_analysis",
  schema: { type: "object", required: ["sentences"] },
};
const messages = [{ role: "user" as const, content: "Analyze." }];

function response(body: unknown, init: ResponseInit = {}): Response {
  return new Response(typeof body === "string" ? body : JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
    ...init,
  });
}

function completion(content: string): Response {
  return response({ choices: [{ message: { content } }] });
}

function requestBody(fetch: ReturnType<typeof vi.fn<typeof globalThis.fetch>>, call: number) {
  const body = fetch.mock.calls[call]![1]?.body;
  if (typeof body !== "string") throw new Error("Expected a string request body");
  return JSON.parse(body) as unknown;
}

describe("OpenAI-compatible chat completions adapter", () => {
  it("probes JSON capability with one low-cost schema request", async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(completion('{"ok":true}'));
    const persistJsonSchemaSupport = vi.fn().mockResolvedValue(undefined);
    const adapter = new OpenAiCompatibleAdapter({ fetch, persistJsonSchemaSupport });

    await expect(adapter.probeJsonCapability(profile, new AbortController().signal)).resolves.toBe(
      "supported",
    );

    expect(fetch).toHaveBeenCalledOnce();
    expect(requestBody(fetch, 0)).toMatchObject({
      model: "syntax-model",
      response_format: { type: "json_schema" },
    });
    // Reasoning models spend the token budget on hidden chain-of-thought and
    // return an empty message when capped, so the probe must not send one.
    expect(requestBody(fetch, 0)).not.toHaveProperty("max_tokens");
    expect(persistJsonSchemaSupport).toHaveBeenCalledWith("profile-1", "supported");
  });

  it("uses one explainable fallback request only after an explicit schema-format rejection", async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(response("response_format is not supported", { status: 400 }))
      .mockResolvedValueOnce(completion('{"ok":true}'));
    const persistJsonSchemaSupport = vi.fn().mockResolvedValue(undefined);
    const adapter = new OpenAiCompatibleAdapter({ fetch, persistJsonSchemaSupport });

    await expect(adapter.probeJsonCapability(profile, new AbortController().signal)).resolves.toBe(
      "unsupported",
    );

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(requestBody(fetch, 1)).not.toHaveProperty("response_format");
    expect(requestBody(fetch, 1)).not.toHaveProperty("max_tokens");
    expect(persistJsonSchemaSupport).toHaveBeenCalledWith("profile-1", "unsupported");
  });

  it("treats a DeepSeek-style serde rejection of response_format as a capability downgrade", async () => {
    // DeepSeek rejects json_schema with a Rust serde message that never says
    // "not supported": Failed to deserialize the JSON body into the target
    // type: response_format: unknown variant `json_schema`, expected `text`
    // or `json_object`.
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(
        response(
          '{"error":{"message":"Failed to deserialize the JSON body into the target type: ' +
            "response_format: unknown variant `json_schema`, expected `text` or `json_object` " +
            'at line 1 column 123","type":"invalid_request_error"}}',
          { status: 400 },
        ),
      )
      .mockResolvedValueOnce(completion('{"ok":true}'));
    const persistJsonSchemaSupport = vi.fn().mockResolvedValue(undefined);
    const adapter = new OpenAiCompatibleAdapter({ fetch, persistJsonSchemaSupport });

    await expect(adapter.probeJsonCapability(profile, new AbortController().signal)).resolves.toBe(
      "unsupported",
    );

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(requestBody(fetch, 1)).not.toHaveProperty("response_format");
    expect(persistJsonSchemaSupport).toHaveBeenCalledWith("profile-1", "unsupported");
  });

  it("maps a DeepSeek-style 400 Model Not Exist to MODEL_NOT_FOUND", async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(
      response('{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}', {
        status: 400,
      }),
    );
    const adapter = new OpenAiCompatibleAdapter({ fetch });

    await expect(
      adapter.completeJson(profile, messages, schema, new AbortController().signal),
    ).rejects.toMatchObject({ code: "MODEL_NOT_FOUND" });
  });

  it("keeps an unrecognized 400 as a non-retryable NETWORK_ERROR carrying the status", async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValue(
        response('{"error":{"message":"messages: field required"}}', { status: 400 }),
      );
    const adapter = new OpenAiCompatibleAdapter({ fetch });

    await expect(
      adapter.completeJson(
        { ...profile, jsonSchemaSupport: "unsupported" },
        messages,
        schema,
        new AbortController().signal,
      ),
    ).rejects.toMatchObject({
      code: "NETWORK_ERROR",
      retryable: false,
      details: { status: 400 },
    });
  });

  it("invokes the default global fetch with the correct receiver", async () => {
    const call = new Response(
      JSON.stringify({ choices: [{ message: { content: '{"ok":true}' } }] }),
      {
        status: 200,
        headers: { "Content-Type": "application/json" },
      },
    );
    const globalFetch = vi.fn<typeof globalThis.fetch>(function (this: unknown) {
      if (this !== undefined && this !== globalThis) {
        throw new TypeError("Illegal invocation");
      }
      return Promise.resolve(call);
    });
    vi.spyOn(globalThis, "fetch").mockImplementation(globalFetch);
    const adapter = new OpenAiCompatibleAdapter();

    await expect(
      adapter.completeJson(profile, messages, schema, new AbortController().signal),
    ).resolves.toEqual({ ok: true });
    expect(globalFetch).toHaveBeenCalledOnce();
  });

  it("rejects a probe response that does not follow the minimal JSON instruction", async () => {
    const persistJsonSchemaSupport = vi.fn().mockResolvedValue(undefined);
    const adapter = new OpenAiCompatibleAdapter({
      fetch: vi.fn<typeof globalThis.fetch>().mockResolvedValue(completion('{"ok":false}')),
      persistJsonSchemaSupport,
    });

    await expect(
      adapter.probeJsonCapability(profile, new AbortController().signal),
    ).rejects.toMatchObject({ code: "INVALID_MODEL_OUTPUT" });
    expect(persistJsonSchemaSupport).not.toHaveBeenCalledWith("profile-1", "supported");
  });

  it.each(["unknown", "supported"] as const)(
    "sends a deterministic JSON-schema request for a %s profile",
    async (jsonSchemaSupport) => {
      const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(completion('{"ok":true}'));
      const adapter = new OpenAiCompatibleAdapter({ fetch });

      await expect(
        adapter.completeJson(
          { ...profile, jsonSchemaSupport },
          messages,
          schema,
          new AbortController().signal,
        ),
      ).resolves.toEqual({ ok: true });

      expect(fetch.mock.calls[0]![0]).toBe("https://model.example/v1/chat/completions");
      const request = fetch.mock.calls[0]![1]!;
      expect(request.method).toBe("POST");
      const headers = new Headers(request.headers);
      expect(headers.get("Authorization")).toBe("Bearer secret");
      expect(headers.get("Content-Type")).toBe("application/json");
      expect(headers.get("X-Tenant")).toBe("learning");
      expect(requestBody(fetch, 0)).toEqual({
        model: "syntax-model",
        messages,
        temperature: 0,
        stream: false,
        response_format: {
          type: "json_schema",
          json_schema: { name: "core_analysis", strict: true, schema: schema.schema },
        },
      });
    },
  );

  it("immediately retries a rejected response_format and persists the capability downgrade", async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(response("response_format is not supported", { status: 400 }))
      .mockResolvedValueOnce(completion('{"ok":true}'));
    const persistJsonSchemaSupport = vi.fn().mockResolvedValue(undefined);
    const adapter = new OpenAiCompatibleAdapter({ fetch, persistJsonSchemaSupport });

    await adapter.completeJson(profile, messages, schema, new AbortController().signal);

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(requestBody(fetch, 1)).not.toHaveProperty("response_format");
    expect(persistJsonSchemaSupport).toHaveBeenCalledWith("profile-1", "unsupported");
  });

  it("keeps the required JSON content type when a custom header uses different casing", async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(completion('{"ok":true}'));
    const adapter = new OpenAiCompatibleAdapter({ fetch });

    await adapter.completeJson(
      { ...profile, headers: { "content-type": "text/plain" } },
      messages,
      schema,
      new AbortController().signal,
    );

    expect(new Headers(fetch.mock.calls[0]![1]!.headers).get("Content-Type")).toBe(
      "application/json",
    );
  });

  it.each([
    [401, "AUTH_FAILED", false, {}],
    [429, "RATE_LIMITED", true, { retryAfterMs: 3_000 }],
    [503, "NETWORK_ERROR", true, { status: 503 }],
  ])("maps HTTP %i to %s", async (status, code, retryable, details) => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(
      response("remote failure", {
        status,
        headers: status === 429 ? { "Retry-After": "3" } : undefined,
      }),
    );
    const adapter = new OpenAiCompatibleAdapter({ fetch });

    const rejection = adapter.completeJson(profile, messages, schema, new AbortController().signal);
    await expect(rejection).rejects.toMatchObject({ code, retryable, details });
  });

  it("maps an internal timeout abort separately from caller cancellation", async () => {
    vi.useFakeTimers();
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation((_url, init) => {
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () =>
          reject(new DOMException("Aborted", "AbortError")),
        );
      });
    });
    const adapter = new OpenAiCompatibleAdapter({ fetch });
    const result = adapter.completeJson(profile, messages, schema, new AbortController().signal);
    const assertion = expect(result).rejects.toMatchObject({
      code: "REQUEST_TIMEOUT",
      retryable: true,
    });

    await vi.advanceTimersByTimeAsync(profile.timeoutMs);
    await assertion;
    vi.useRealTimers();
  });

  it("keeps caller cancellation when fetch rejects only after the later timeout callback", async () => {
    vi.useFakeTimers();
    let rejectFetch!: (reason: Error) => void;
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(
      () =>
        new Promise((_resolve, reject) => {
          rejectFetch = reject;
        }),
    );
    const adapter = new OpenAiCompatibleAdapter({ fetch });
    const caller = new AbortController();
    const result = adapter.completeJson(profile, messages, schema, caller.signal);
    const assertion = expect(result).rejects.toMatchObject({
      code: "REQUEST_CANCELLED",
      retryable: false,
    });

    caller.abort();
    await vi.advanceTimersByTimeAsync(profile.timeoutMs);
    rejectFetch(new DOMException("Aborted", "AbortError"));

    await assertion;
    vi.useRealTimers();
  });

  it("keeps timeout when caller cancellation happens after timeout wins", async () => {
    vi.useFakeTimers();
    let rejectFetch!: (reason: Error) => void;
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(
      () =>
        new Promise((_resolve, reject) => {
          rejectFetch = reject;
        }),
    );
    const adapter = new OpenAiCompatibleAdapter({ fetch });
    const caller = new AbortController();
    const result = adapter.completeJson(profile, messages, schema, caller.signal);
    const assertion = expect(result).rejects.toMatchObject({
      code: "REQUEST_TIMEOUT",
      retryable: true,
    });

    await vi.advanceTimersByTimeAsync(profile.timeoutMs);
    caller.abort();
    rejectFetch(new DOMException("Aborted", "AbortError"));

    await assertion;
    vi.useRealTimers();
  });

  it.each([
    ["malformed envelope", {}, "INVALID_MODEL_OUTPUT"],
    ["missing content", { choices: [{ message: {} }] }, "INVALID_MODEL_OUTPUT"],
  ])("rejects a %s", async (_description, envelope, code) => {
    const adapter = new OpenAiCompatibleAdapter({
      fetch: vi.fn<typeof globalThis.fetch>().mockResolvedValue(response(envelope)),
    });
    await expect(
      adapter.completeJson(profile, messages, schema, new AbortController().signal),
    ).rejects.toMatchObject({ code });
  });

  it("strips one outer JSON fence but never extracts JSON from prose", async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(completion('```json\n{"ok":true}\n```'))
      .mockResolvedValueOnce(completion('Here is the result: {"ok":true}'));
    const adapter = new OpenAiCompatibleAdapter({ fetch });

    await expect(
      adapter.completeJson(profile, messages, schema, new AbortController().signal),
    ).resolves.toEqual({ ok: true });
    await expect(
      adapter.completeJson(profile, messages, schema, new AbortController().signal),
    ).rejects.toBeInstanceOf(ModelRequestError);
  });
});

describe("syntax prompts", () => {
  const sentence = {
    sentenceId: "s-1",
    text: "Readers learn.",
    tokens: [
      { id: 0, text: "Readers", start: 0, end: 7, leadingWhitespace: "", punctuation: false },
      { id: 1, text: "learn", start: 8, end: 13, leadingWhitespace: " ", punctuation: false },
      { id: 2, text: ".", start: 13, end: 14, leadingWhitespace: "", punctuation: true },
    ],
  };

  it("states all core structural invariants", () => {
    const prompt = buildCorePrompt([sentence]);
    expect(prompt).toContain("SUBJECT");
    expect(prompt).toContain("INDEPENDENT_ELEMENT");
    expect(prompt).toContain("14");
    expect(prompt).toMatch(/closed.*Token/i);
    expect(prompt).toMatch(/exactly once/i);
    expect(prompt).toMatch(/Chinese/i);
    expect(prompt).toMatch(/JSON only/i);
  });

  it("spells out the exact output envelope so schema-free models cannot guess", () => {
    // Compatibility mode sends no response_format, so the JSON shape must be
    // stated in the prompt itself; a real model returned a top-level array
    // and punctuation-only components without it.
    const core = buildCorePrompt([sentence]);
    expect(core).toContain('{"sentences":');
    expect(core).toContain('"components":');
    expect(core).toMatch(/not.*top-level.*array|never.*top-level.*array/i);
    expect(core).toMatch(/never.*only punctuation|must not.*only punctuation/i);

    const repair = buildRepairPrompt([sentence], [], {});
    expect(repair).toContain('{"sentences":');

    const verifiedCore = {
      schemaVersion: 1 as const,
      sentenceId: "s-1",
      components: [],
      modelProfileId: "profile-1",
    };
    const detail = buildDetailPrompt(sentence, verifiedCore, { startToken: 0, endToken: 1 });
    expect(detail).toContain('"structures":');
    expect(detail).toContain('"grammarPoints":');
    expect(detail).toContain('"explanation":');
  });

  it("keeps immutable sentence identity and tokens in repair instructions", () => {
    const prompt = buildRepairPrompt([sentence], [{ path: "sentences[0]", message: "gap" }], {
      broken: true,
    });
    expect(prompt).toContain("gap");
    expect(prompt).toContain('"broken": true');
    expect(prompt).toMatch(/do not change.*sentence IDs.*Tokens/is);
  });

  it("limits detail context to the selected sentence, verified core, and focus", () => {
    const core = {
      schemaVersion: 1 as const,
      sentenceId: "s-1",
      components: [],
      modelProfileId: "profile-1",
    };
    const prompt = buildDetailPrompt(sentence, core, { startToken: 0, endToken: 0 });
    expect(prompt).toContain('"sentenceId": "s-1"');
    expect(prompt).toContain('"modelProfileId": "profile-1"');
    expect(prompt).toContain('"startToken": 0');
  });
});
