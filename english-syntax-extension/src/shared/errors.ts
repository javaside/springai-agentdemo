export const ERROR_CODES = [
  "CONFIG_MISSING",
  "HOST_PERMISSION_DENIED",
  "AUTH_FAILED",
  "MODEL_NOT_FOUND",
  "RATE_LIMITED",
  "NETWORK_ERROR",
  "REQUEST_TIMEOUT",
  "INVALID_MODEL_OUTPUT",
  "UNSUPPORTED_PAGE",
  "UNSAFE_CONTENT_BLOCK",
  "SENTENCE_TOO_LONG",
  "REQUEST_CANCELLED",
  "NO_CACHE",
] as const;

export type ExtensionErrorCode = (typeof ERROR_CODES)[number];

export interface ExtensionError {
  code: ExtensionErrorCode;
  message: string;
  retryable: boolean;
  details?: Record<string, string | number | boolean>;
}
