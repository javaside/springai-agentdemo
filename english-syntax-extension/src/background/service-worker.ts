import { hostPermissionPattern } from "./base-url";
import type { ModelProfile } from "./config-repository";

export async function requestHostPermission(
  profile: Pick<ModelProfile, "baseUrl">,
): Promise<boolean> {
  return chrome.permissions.request({ origins: [hostPermissionPattern(profile.baseUrl)] });
}

async function initialize(): Promise<void> {
  await chrome.storage.local.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
  chrome.runtime.onMessage.addListener(() => undefined);
}

void initialize();
