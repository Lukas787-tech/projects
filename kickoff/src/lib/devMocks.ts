// In-memory command handlers so the UI runs in a plain browser (`npm run dev`)
// and in tests, without the Rust backend. Never used inside the Tauri app.
import { mockIPC } from "@tauri-apps/api/mocks";

import type { AppInfo } from "../bindings/AppInfo";
import type { AppSettings } from "../bindings/AppSettings";
import type { SystemStatus } from "../bindings/SystemStatus";

export const MOCK_APP_INFO: AppInfo = { name: "Kickoff", id: "dev.kickoff.studio", version: "0.0.0-dev" };

export function installDevMocks(initial?: Partial<AppSettings>) {
  let settings: AppSettings = { theme: "dark", projects_dir: null, onboarding_done: false, ...initial };

  const status: SystemStatus = {
    os: "browser",
    data_dir: "(browser preview)",
    log_dir: "(browser preview)",
    schema_version: 1,
    presets_updated: "2026-10-11",
    secret_store_problem: "Browser preview: keys are not stored.",
    default_projects_dir: "C:\\Users\\you\\Documents\\Kickoff Projects",
  };

  mockIPC((cmd, args) => {
    switch (cmd) {
      case "app_info":
        return MOCK_APP_INFO;
      case "get_settings":
        return settings;
      case "save_settings":
        settings = (args as { settings: AppSettings }).settings;
        return settings;
      case "system_status":
        return status;
      default:
        throw { code: "internal", message: `No mock for command "${cmd}"` };
    }
  });
}
