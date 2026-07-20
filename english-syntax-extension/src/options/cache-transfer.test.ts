import { describe, expect, it } from "vitest";
import { CORE_SCHEMA_VERSION } from "../shared/versions";
import type { TransferEntry, TransferStoreName } from "../background/analysis-cache";
import {
  CACHE_FILE_FORMAT,
  CACHE_FILE_FORMAT_VERSION,
  IMPORTED_PROFILE_ID,
  exportCacheFile,
  importCacheFile,
  type CacheTransferPort,
} from "./cache-transfer";

const KEY_A = "a".repeat(64);
const KEY_B = "b".repeat(64);

function fakePort(initial: Partial<Record<TransferStoreName, TransferEntry[]>> = {}): {
  port: CacheTransferPort;
  written: Record<TransferStoreName, TransferEntry[]>;
  receivedProfileIds: string[];
} {
  const stores: Record<TransferStoreName, TransferEntry[]> = {
    core: [...(initial.core ?? [])],
    detail: [...(initial.detail ?? [])],
  };
  const written: Record<TransferStoreName, TransferEntry[]> = { core: [], detail: [] };
  const receivedProfileIds: string[] = [];
  return {
    written,
    receivedProfileIds,
    port: {
      exportEntries: (store) => Promise.resolve([...stores[store]]),
      importEntries: (store, entries, profileId) => {
        receivedProfileIds.push(profileId);
        const existing = new Set(stores[store].map(({ key }) => key));
        const added = entries.filter(({ key }) => !existing.has(key));
        stores[store].push(...added);
        written[store].push(...added);
        return Promise.resolve({ added: added.length, skipped: entries.length - added.length });
      },
    },
  };
}

function validFile(): Record<string, unknown> {
  return {
    format: CACHE_FILE_FORMAT,
    formatVersion: CACHE_FILE_FORMAT_VERSION,
    schemaVersion: CORE_SCHEMA_VERSION,
    exportedAt: "2026-07-20T00:00:00.000Z",
    core: [{ key: KEY_A, value: { components: [] } }],
    detail: [{ key: KEY_B, value: { structures: [] } }],
  };
}

describe("exportCacheFile", () => {
  it("builds a header plus core and detail entries, without correction", async () => {
    const { port } = fakePort({
      core: [{ key: KEY_A, value: { components: [] } }],
      detail: [{ key: KEY_B, value: { structures: [] } }],
    });
    const file = await exportCacheFile(port, () => new Date("2026-07-20T08:00:00Z"));

    expect(file).toEqual({
      format: CACHE_FILE_FORMAT,
      formatVersion: CACHE_FILE_FORMAT_VERSION,
      schemaVersion: CORE_SCHEMA_VERSION,
      exportedAt: "2026-07-20T08:00:00.000Z",
      core: [{ key: KEY_A, value: { components: [] } }],
      detail: [{ key: KEY_B, value: { structures: [] } }],
    });
    expect("correction" in file).toBe(false);
  });
});

describe("importCacheFile", () => {
  it("merges valid entries and reports added/skipped/invalid counts", async () => {
    const { port, receivedProfileIds } = fakePort({
      core: [{ key: KEY_A, value: { local: true } }],
    });
    const file = validFile();
    (file.core as unknown[]).push(
      { key: "not-hex", value: {} },
      { key: "c".repeat(64), value: "not-an-object" },
    );

    const report = await importCacheFile(port, JSON.stringify(file));

    expect(report).toEqual({ ok: true, added: 1, skipped: 1, invalid: 2 });
    expect(receivedProfileIds).toEqual([IMPORTED_PROFILE_ID, IMPORTED_PROFILE_ID]);
  });

  it("is idempotent: importing the same file twice skips everything", async () => {
    const { port } = fakePort();
    const text = JSON.stringify(validFile());
    await importCacheFile(port, text);

    const second = await importCacheFile(port, text);

    expect(second).toEqual({ ok: true, added: 0, skipped: 2, invalid: 0 });
  });

  it("rejects the whole file when it is not JSON", async () => {
    const { port, written } = fakePort();
    expect(await importCacheFile(port, "{oops")).toEqual({ ok: false, reason: "not-json" });
    expect(written.core).toHaveLength(0);
  });

  it("rejects the whole file when the format header does not match", async () => {
    const { port, written } = fakePort();
    const file = validFile();
    file.format = "something-else";
    expect(await importCacheFile(port, JSON.stringify(file))).toEqual({
      ok: false,
      reason: "bad-format",
    });
    expect(written.core).toHaveLength(0);
  });

  it("rejects the whole file when the format version does not match", async () => {
    const { port, written } = fakePort();
    const file = validFile();
    file.formatVersion = CACHE_FILE_FORMAT_VERSION + 1;
    expect(await importCacheFile(port, JSON.stringify(file))).toEqual({
      ok: false,
      reason: "bad-format",
    });
    expect(written.core).toHaveLength(0);
  });

  it("rejects the whole file when the schema version differs", async () => {
    const { port, written } = fakePort();
    const file = validFile();
    file.schemaVersion = CORE_SCHEMA_VERSION + 1;
    expect(await importCacheFile(port, JSON.stringify(file))).toEqual({
      ok: false,
      reason: "schema-mismatch",
    });
    expect(written.core).toHaveLength(0);
  });
});
