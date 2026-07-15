export function normalizeBaseUrl(baseUrl: string): string {
  const trimmedBaseUrl = baseUrl.trim();
  if (trimmedBaseUrl.includes("?") || trimmedBaseUrl.includes("#")) {
    throw new Error("Model base URL must not contain query strings or fragments");
  }

  let url: URL;
  try {
    url = new URL(trimmedBaseUrl);
  } catch {
    throw new Error("Model base URL must be a valid URL");
  }

  if (url.username || url.password) {
    throw new Error("Model base URL must not contain credentials");
  }

  const isLocalHttp =
    url.protocol === "http:" && (url.hostname === "localhost" || url.hostname === "127.0.0.1");
  if (url.protocol !== "https:" && !isLocalHttp) {
    throw new Error("Model base URL must use HTTPS unless it is localhost");
  }

  return url.toString().replace(/\/$/, "");
}

export function chatCompletionsUrl(baseUrl: string): string {
  const normalized = normalizeBaseUrl(baseUrl);
  return normalized.endsWith("/chat/completions") ? normalized : `${normalized}/chat/completions`;
}

export function hostPermissionPattern(baseUrl: string): string {
  return `${new URL(normalizeBaseUrl(baseUrl)).origin}/*`;
}
