// Typed wrappers around Tauri commands. Request/response types are generated
// from Rust by ts-rs into src/bindings (run `cargo test` to refresh them).
import { invoke } from "@tauri-apps/api/core";

import type { AppInfo } from "../bindings/AppInfo";
import type { AppSettings } from "../bindings/AppSettings";
import type { CommandError } from "../bindings/CommandError";
import type { SystemStatus } from "../bindings/SystemStatus";

export const ipc = {
  appInfo: () => invoke<AppInfo>("app_info"),
  getSettings: () => invoke<AppSettings>("get_settings"),
  saveSettings: (settings: AppSettings) => invoke<AppSettings>("save_settings", { settings }),
  systemStatus: () => invoke<SystemStatus>("system_status"),
};

function isCommandError(e: unknown): e is CommandError {
  return typeof e === "object" && e !== null && "code" in e && "message" in e;
}

/** A user-presentable message for any rejected command. */
export function errorMessage(e: unknown): string {
  if (isCommandError(e)) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}
