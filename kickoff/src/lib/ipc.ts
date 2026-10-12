// Typed wrappers around Tauri commands. Request/response types are generated
// from Rust by ts-rs into src/bindings (run `cargo test` to refresh them).
// Command arguments are snake_case, matching the Rust side.
import { invoke } from "@tauri-apps/api/core";

import type { AiSettings } from "../bindings/AiSettings";
import type { AppInfo } from "../bindings/AppInfo";
import type { AppSettings } from "../bindings/AppSettings";
import type { CommandError } from "../bindings/CommandError";
import type { KeyEntry } from "../bindings/KeyEntry";
import type { KeyMeta } from "../bindings/KeyMeta";
import type { ModelInfo } from "../bindings/ModelInfo";
import type { NewKeyInput } from "../bindings/NewKeyInput";
import type { OllamaInfo } from "../bindings/OllamaInfo";
import type { PresetCatalog } from "../bindings/PresetCatalog";
import type { ProviderTest } from "../bindings/ProviderTest";
import type { SystemStatus } from "../bindings/SystemStatus";
import type { Task } from "../bindings/Task";
import type { TaskCheck } from "../bindings/TaskCheck";
import type { UsageSummary } from "../bindings/UsageSummary";

export const ipc = {
  appInfo: () => invoke<AppInfo>("app_info"),
  getSettings: () => invoke<AppSettings>("get_settings"),
  saveSettings: (settings: AppSettings) => invoke<AppSettings>("save_settings", { settings }),
  systemStatus: () => invoke<SystemStatus>("system_status"),

  listKeys: () => invoke<KeyEntry[]>("list_keys"),
  addKey: (input: NewKeyInput) => invoke<KeyMeta>("add_key", { input }),
  updateKey: (id: string, label: string, env_var: string, notes: string | null) =>
    invoke<KeyMeta>("update_key", { id, label, env_var, notes }),
  replaceKeySecret: (id: string, secret: string) => invoke<null>("replace_key_secret", { id, secret }),
  deleteKey: (id: string) => invoke<null>("delete_key", { id }),
  testKey: (id: string) => invoke<ProviderTest>("test_key", { id }),

  listPresets: () => invoke<PresetCatalog>("list_presets"),
  getAiSettings: () => invoke<AiSettings>("get_ai_settings"),
  saveAiSettings: (settings: AiSettings) => invoke<AiSettings>("save_ai_settings", { settings }),
  listModels: (provider_id: string, refresh = false) => invoke<ModelInfo[]>("list_models", { provider_id, refresh }),
  testProvider: (provider_id: string, secret: string | null) =>
    invoke<ProviderTest>("test_provider", { provider_id, secret }),
  detectOllama: () => invoke<OllamaInfo>("detect_ollama"),
  usageToday: () => invoke<UsageSummary[]>("usage_today"),
  checkTask: (task: Task) => invoke<TaskCheck>("check_task", { task }),
};

/** Event name for router activity (rate limits, fallbacks). */
export const AI_STATUS_EVENT = "ai-status";

function isCommandError(e: unknown): e is CommandError {
  return typeof e === "object" && e !== null && "code" in e && "message" in e;
}

/** A user-presentable message for any rejected command. */
export function errorMessage(e: unknown): string {
  if (isCommandError(e)) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}
