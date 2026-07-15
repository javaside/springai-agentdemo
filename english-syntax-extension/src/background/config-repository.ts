import { normalizeBaseUrl } from "./base-url";

export interface ModelProfile {
  id: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  headers: Record<string, string>;
  timeoutMs: number;
  jsonSchemaSupport: "unknown" | "supported" | "unsupported";
}

export type PublicModelProfile = Omit<ModelProfile, "apiKey" | "headers">;

interface StorageArea {
  get(keys: string | string[]): Promise<Record<string, unknown>>;
  set(items: Record<string, unknown>): Promise<void>;
}

const PROFILES_KEY = "profiles.v1";
const ACTIVE_PROFILE_ID_KEY = "activeProfileId.v1";
const CACHE_LIMIT_MB_KEY = "cacheLimitMb.v1";
const DEFAULT_CACHE_LIMIT_MB = 50;
const CACHE_LIMIT_CHOICES_MB = new Set([10, 50, 100, 200]);
const FORBIDDEN_HEADERS = new Set([
  "authorization",
  "host",
  "content-length",
  "origin",
  "x-syntax-request-id",
]);
const JSON_SCHEMA_SUPPORT = new Set<ModelProfile["jsonSchemaSupport"]>([
  "unknown",
  "supported",
  "unsupported",
]);

function requireNonBlank(value: string, field: string): void {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`Model profile ${field} must not be blank`);
  }
}

function validateProfile(profile: ModelProfile): ModelProfile {
  requireNonBlank(profile.id, "id");
  requireNonBlank(profile.name, "name");
  requireNonBlank(profile.model, "model");
  if (typeof profile.apiKey !== "string") {
    throw new Error("Model profile apiKey must be a string");
  }
  if (
    !Number.isInteger(profile.timeoutMs) ||
    profile.timeoutMs < 5_000 ||
    profile.timeoutMs > 120_000
  ) {
    throw new Error("Model profile timeout must be between 5000 and 120000 milliseconds");
  }
  if (!JSON_SCHEMA_SUPPORT.has(profile.jsonSchemaSupport)) {
    throw new Error("Model profile jsonSchemaSupport is invalid");
  }
  if (
    typeof profile.headers !== "object" ||
    profile.headers === null ||
    Array.isArray(profile.headers)
  ) {
    throw new Error("Model profile headers must be an object");
  }
  for (const [name, value] of Object.entries(profile.headers)) {
    if (FORBIDDEN_HEADERS.has(name.trim().toLowerCase())) {
      throw new Error(`Custom header ${name} is forbidden`);
    }
    if (typeof value !== "string") {
      throw new Error(`Custom header ${name} must have a string value`);
    }
  }

  return structuredClone({ ...profile, baseUrl: normalizeBaseUrl(profile.baseUrl) });
}

export class ConfigRepository {
  constructor(private readonly storage: StorageArea = chrome.storage.local) {}

  async saveProfile(profile: ModelProfile): Promise<void> {
    const validated = validateProfile(profile);
    const profiles = await this.listProfiles();
    const existingIndex = profiles.findIndex(({ id }) => id === validated.id);
    if (existingIndex === -1) {
      profiles.push(validated);
    } else {
      profiles[existingIndex] = validated;
    }
    await this.storage.set({ [PROFILES_KEY]: profiles });
  }

  async listProfiles(): Promise<ModelProfile[]> {
    const stored = (await this.storage.get(PROFILES_KEY))[PROFILES_KEY];
    if (stored === undefined) {
      return [];
    }
    if (!Array.isArray(stored)) {
      throw new Error("Stored model profiles are invalid");
    }
    return stored.map((value) => validateProfile(value as ModelProfile));
  }

  async getProfile(profileId: string): Promise<ModelProfile | undefined> {
    const profile = (await this.listProfiles()).find(({ id }) => id === profileId);
    return profile === undefined ? undefined : structuredClone(profile);
  }

  async setActiveProfile(profileId: string): Promise<void> {
    if ((await this.getProfile(profileId)) === undefined) {
      throw new Error(`Unknown model profile: ${profileId}`);
    }
    await this.storage.set({ [ACTIVE_PROFILE_ID_KEY]: profileId });
  }

  async getActiveProfile(): Promise<ModelProfile | undefined> {
    const profileId = await this.getActiveProfileId();
    return profileId === undefined ? undefined : this.getProfile(profileId);
  }

  async getActiveProfileId(): Promise<string | undefined> {
    const profileId = (await this.storage.get(ACTIVE_PROFILE_ID_KEY))[ACTIVE_PROFILE_ID_KEY];
    return typeof profileId === "string" ? profileId : undefined;
  }

  async listPublicProfiles(): Promise<PublicModelProfile[]> {
    return (await this.listProfiles()).map((profile) => ({
      id: profile.id,
      name: profile.name,
      baseUrl: profile.baseUrl,
      model: profile.model,
      timeoutMs: profile.timeoutMs,
      jsonSchemaSupport: profile.jsonSchemaSupport,
    }));
  }

  async getCacheLimitBytes(): Promise<number> {
    const stored = (await this.storage.get(CACHE_LIMIT_MB_KEY))[CACHE_LIMIT_MB_KEY];
    const limitMb =
      typeof stored === "number" && CACHE_LIMIT_CHOICES_MB.has(stored)
        ? stored
        : DEFAULT_CACHE_LIMIT_MB;
    return limitMb * 1024 * 1024;
  }

  async setCacheLimitMb(limitMb: number): Promise<void> {
    if (!CACHE_LIMIT_CHOICES_MB.has(limitMb)) {
      throw new Error("Analysis cache limit must be 10, 50, 100, or 200 MB");
    }
    await this.storage.set({ [CACHE_LIMIT_MB_KEY]: limitMb });
  }
}
