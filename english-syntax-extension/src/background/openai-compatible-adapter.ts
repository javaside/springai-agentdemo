import type { ExtensionError, ExtensionErrorCode } from "../shared/errors";
import { chatCompletionsUrl } from "./base-url";
import type { ModelProfile } from "./config-repository";

export interface JsonSchemaSpec {
  name: string;
  schema: Record<string, unknown>;
  strict?: boolean;
}

export interface ChatMessage {
  role: "system" | "user";
  content: string;
}

export class ModelRequestError extends Error implements ExtensionError {
  constructor(
    public readonly code: ExtensionErrorCode,
    message: string,
    public readonly retryable: boolean,
    public readonly details: Record<string, string | number | boolean> = {},
  ) {
    super(message);
    this.name = "ModelRequestError";
  }
}

export interface OpenAiCompatibleAdapterOptions {
  fetch?: typeof globalThis.fetch;
  persistJsonSchemaSupport?: (
    profileId: string,
    support: ModelProfile["jsonSchemaSupport"],
  ) => Promise<void>;
}

interface ChatCompletionEnvelope {
  choices?: Array<{ message?: { content?: unknown } }>;
}

function responseFormat(schema: JsonSchemaSpec): Record<string, unknown> {
  return {
    type: "json_schema",
    json_schema: {
      name: schema.name,
      strict: schema.strict ?? true,
      schema: schema.schema,
    },
  };
}

function retryAfterMilliseconds(value: string | null): number | undefined {
  if (value === null) return undefined;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.round(seconds * 1_000);
  const date = Date.parse(value);
  if (!Number.isNaN(date)) return Math.max(0, date - Date.now());
  return undefined;
}

function stripSingleJsonFence(content: string): string {
  const trimmed = content.trim();
  const match = /^```json[\t ]*\r?\n([\s\S]*?)\r?\n```$/i.exec(trimmed);
  return match?.[1] ?? trimmed;
}

function invalidOutput(message: string): ModelRequestError {
  return new ModelRequestError("INVALID_MODEL_OUTPUT", message, false);
}

function mapHttpError(status: number, retryAfter: string | null, body: string): ModelRequestError {
  const message = body.trim() || `Model endpoint returned HTTP ${status}`;
  if (status === 401 || status === 403) {
    return new ModelRequestError("AUTH_FAILED", message, false, { status });
  }
  // Some providers (e.g. DeepSeek) reject an unknown model with 400 "Model
  // Not Exist" instead of 404, so sniff the body as well.
  if (
    status === 404 ||
    (status === 400 && /model[\s\S]{0,40}?(?:not exist|not found)/i.test(body))
  ) {
    return new ModelRequestError("MODEL_NOT_FOUND", message, false, { status });
  }
  if (status === 429) {
    const delay = retryAfterMilliseconds(retryAfter);
    return new ModelRequestError(
      "RATE_LIMITED",
      message,
      true,
      delay === undefined ? { status } : { status, retryAfterMs: delay },
    );
  }
  return new ModelRequestError("NETWORK_ERROR", message, status >= 500, { status });
}

export class OpenAiCompatibleAdapter {
  private readonly fetchImplementation: typeof globalThis.fetch;
  private readonly persistJsonSchemaSupport: NonNullable<
    OpenAiCompatibleAdapterOptions["persistJsonSchemaSupport"]
  >;

  constructor(options: OpenAiCompatibleAdapterOptions = {}) {
    this.fetchImplementation = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.persistJsonSchemaSupport = options.persistJsonSchemaSupport ?? (() => Promise.resolve());
  }

  async completeJson(
    profile: ModelProfile,
    messages: readonly ChatMessage[],
    schema: JsonSchemaSpec,
    signal: AbortSignal,
  ): Promise<unknown> {
    const useSchema = profile.jsonSchemaSupport !== "unsupported";
    try {
      return await this.request(profile, messages, schema, signal, useSchema);
    } catch (error) {
      if (useSchema && error instanceof UnsupportedResponseFormatError && !signal.aborted) {
        await this.persistJsonSchemaSupport(profile.id, "unsupported");
        return this.request(profile, messages, schema, signal, false);
      }
      throw error;
    }
  }

  async probeJsonCapability(
    profile: ModelProfile,
    signal: AbortSignal,
  ): Promise<"supported" | "unsupported"> {
    const messages: readonly ChatMessage[] = [
      { role: "system", content: "Return only the requested JSON object." },
      { role: "user", content: 'Return exactly {"ok":true}.' },
    ];
    const schema: JsonSchemaSpec = {
      name: "connection_probe",
      strict: true,
      schema: {
        type: "object",
        properties: { ok: { const: true } },
        required: ["ok"],
        additionalProperties: false,
      },
    };
    let support: "supported" | "unsupported" = "supported";
    let value: unknown;
    try {
      value = await this.request(profile, messages, schema, signal, true, 8);
    } catch (error) {
      if (!(error instanceof UnsupportedResponseFormatError) || signal.aborted) throw error;
      support = "unsupported";
      await this.persistJsonSchemaSupport(profile.id, "unsupported");
      value = await this.request(profile, messages, schema, signal, false, 8);
    }
    if (
      typeof value !== "object" ||
      value === null ||
      Array.isArray(value) ||
      Object.keys(value).length !== 1 ||
      (value as Record<string, unknown>).ok !== true
    ) {
      throw invalidOutput("Model did not follow the connection probe JSON instruction");
    }
    if (support === "supported") {
      await this.persistJsonSchemaSupport(profile.id, "supported");
    }
    return support;
  }

  private async request(
    profile: ModelProfile,
    messages: readonly ChatMessage[],
    schema: JsonSchemaSpec,
    callerSignal: AbortSignal,
    useSchema: boolean,
    maxTokens?: number,
  ): Promise<unknown> {
    const controller = new AbortController();
    let abortCause: "caller" | "timeout" | undefined;
    const onCallerAbort = () => {
      abortCause ??= "caller";
      controller.abort(callerSignal.reason);
    };
    callerSignal.addEventListener("abort", onCallerAbort, { once: true });
    if (callerSignal.aborted) onCallerAbort();
    const timeout = setTimeout(() => {
      abortCause ??= "timeout";
      controller.abort(new DOMException("Request timed out", "TimeoutError"));
    }, profile.timeoutMs);

    try {
      const body: Record<string, unknown> = {
        model: profile.model,
        messages,
        temperature: 0,
        stream: false,
      };
      if (maxTokens !== undefined) body.max_tokens = maxTokens;
      if (useSchema) body.response_format = responseFormat(schema);
      const headers = new Headers(profile.headers);
      headers.set("Content-Type", "application/json");
      headers.set("Authorization", `Bearer ${profile.apiKey}`);
      const response = await this.fetchImplementation(chatCompletionsUrl(profile.baseUrl), {
        method: "POST",
        headers,
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      const text = await response.text();
      if (!response.ok) {
        // Providers phrase the rejection differently: "response_format is not
        // supported", DeepSeek's serde error "response_format: unknown variant
        // `json_schema`", etc. Any 4xx validation error that names the field
        // is treated as a capability downgrade — the schema-free retry either
        // succeeds or surfaces the real error.
        if (
          useSchema &&
          (response.status === 400 || response.status === 422) &&
          /response[_ ]?format|json[_ ]?schema/i.test(text)
        ) {
          throw new UnsupportedResponseFormatError();
        }
        throw mapHttpError(response.status, response.headers.get("Retry-After"), text);
      }

      let envelope: ChatCompletionEnvelope;
      try {
        envelope = JSON.parse(text) as ChatCompletionEnvelope;
      } catch {
        throw invalidOutput("Model response envelope is not valid JSON");
      }
      const content = envelope.choices?.[0]?.message?.content;
      if (typeof content !== "string") {
        throw invalidOutput("Model response is missing choices[0].message.content");
      }
      try {
        return JSON.parse(stripSingleJsonFence(content)) as unknown;
      } catch {
        throw invalidOutput("Model message content is not valid JSON");
      }
    } catch (error) {
      if (error instanceof ModelRequestError || error instanceof UnsupportedResponseFormatError) {
        throw error;
      }
      if (abortCause === "timeout") {
        throw new ModelRequestError("REQUEST_TIMEOUT", "Model request timed out", true);
      }
      if (abortCause === "caller") {
        throw new ModelRequestError("REQUEST_CANCELLED", "Model request was cancelled", false);
      }
      throw new ModelRequestError(
        "NETWORK_ERROR",
        error instanceof Error ? error.message : "Model network request failed",
        true,
      );
    } finally {
      clearTimeout(timeout);
      callerSignal.removeEventListener("abort", onCallerAbort);
    }
  }
}

class UnsupportedResponseFormatError extends Error {}
