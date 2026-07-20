import type { ImportOutcome, TransferEntry, TransferStoreName } from "../background/analysis-cache";
import { CORE_SCHEMA_VERSION } from "../shared/versions";

export const CACHE_FILE_FORMAT = "english-syntax-cache";
export const CACHE_FILE_FORMAT_VERSION = 1;
/** 导入条目统一记账用的 profileId(仅统计/排查,不参与键)。 */
export const IMPORTED_PROFILE_ID = "imported";

export interface CacheTransferPort {
  exportEntries(storeName: TransferStoreName): Promise<TransferEntry[]>;
  importEntries(
    storeName: TransferStoreName,
    entries: readonly TransferEntry[],
    profileId: string,
  ): Promise<ImportOutcome>;
}

export interface CacheExportFile {
  format: typeof CACHE_FILE_FORMAT;
  formatVersion: typeof CACHE_FILE_FORMAT_VERSION;
  schemaVersion: number;
  exportedAt: string;
  core: TransferEntry[];
  detail: TransferEntry[];
}

export type ImportFailureReason = "not-json" | "bad-format" | "schema-mismatch";

export type ImportReport =
  | { ok: true; added: number; skipped: number; invalid: number }
  | { ok: false; reason: ImportFailureReason };

const HEX_KEY = /^[0-9a-f]{64}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isTransferEntry(value: unknown): value is TransferEntry {
  return (
    isRecord(value) &&
    typeof value.key === "string" &&
    HEX_KEY.test(value.key) &&
    isRecord(value.value)
  );
}

export async function exportCacheFile(
  cache: CacheTransferPort,
  now: () => Date = () => new Date(),
): Promise<CacheExportFile> {
  return {
    format: CACHE_FILE_FORMAT,
    formatVersion: CACHE_FILE_FORMAT_VERSION,
    schemaVersion: CORE_SCHEMA_VERSION,
    exportedAt: now().toISOString(),
    core: await cache.exportEntries("core"),
    detail: await cache.exportEntries("detail"),
  };
}

export async function importCacheFile(
  cache: CacheTransferPort,
  text: string,
): Promise<ImportReport> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { ok: false, reason: "not-json" };
  }
  if (
    !isRecord(parsed) ||
    parsed.format !== CACHE_FILE_FORMAT ||
    parsed.formatVersion !== CACHE_FILE_FORMAT_VERSION ||
    !Array.isArray(parsed.core) ||
    !Array.isArray(parsed.detail)
  ) {
    return { ok: false, reason: "bad-format" };
  }
  // 旧 schema 的条目在当前键空间下永远查不到,静默跳过不如整体拒绝并明说。
  if (parsed.schemaVersion !== CORE_SCHEMA_VERSION) {
    return { ok: false, reason: "schema-mismatch" };
  }

  let added = 0;
  let skipped = 0;
  let invalid = 0;
  for (const storeName of ["core", "detail"] as const) {
    const candidates = parsed[storeName] as unknown[];
    const valid = candidates.filter(isTransferEntry);
    invalid += candidates.length - valid.length;
    const outcome = await cache.importEntries(storeName, valid, IMPORTED_PROFILE_ID);
    added += outcome.added;
    skipped += outcome.skipped;
  }
  return { ok: true, added, skipped, invalid };
}
