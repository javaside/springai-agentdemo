import { describe, expect, it } from "vitest";
import manifest from "../../manifest.json";

describe("manifest", () => {
  it("uses temporary page access and optional model hosts", () => {
    expect(manifest.manifest_version).toBe(3);
    expect(manifest.permissions).toEqual(
      expect.arrayContaining(["activeTab", "scripting", "storage", "contextMenus"]),
    );
    expect(manifest).not.toHaveProperty("host_permissions");
    expect(manifest.optional_host_permissions).toEqual([
      "https://*/*",
      "http://localhost/*",
      "http://127.0.0.1/*",
    ]);
  });
});
