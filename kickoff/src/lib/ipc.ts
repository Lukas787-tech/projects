// Typed wrappers around Tauri commands. Request/response types are generated
// from Rust by ts-rs into src/bindings (run `cargo test` to refresh them).
// Command arguments are snake_case, matching the Rust side.
import { type Channel, invoke } from "@tauri-apps/api/core";

import type { AiSettings } from "../bindings/AiSettings";
import type { AppInfo } from "../bindings/AppInfo";
import type { AppSettings } from "../bindings/AppSettings";
import type { CommandError } from "../bindings/CommandError";
import type { DownloadProgress } from "../bindings/DownloadProgress";
import type { FishCheckResult } from "../bindings/FishCheckResult";
import type { FishVoicePage } from "../bindings/FishVoicePage";
import type { InterviewEvent } from "../bindings/InterviewEvent";
import type { InterviewView } from "../bindings/InterviewView";
import type { KeyEntry } from "../bindings/KeyEntry";
import type { KeyMeta } from "../bindings/KeyMeta";
import type { ModelInfo } from "../bindings/ModelInfo";
import type { NewKeyInput } from "../bindings/NewKeyInput";
import type { OllamaInfo } from "../bindings/OllamaInfo";
import type { PiperVoiceView } from "../bindings/PiperVoiceView";
import type { PresetCatalog } from "../bindings/PresetCatalog";
import type { Project } from "../bindings/Project";
import type { ProjectCard } from "../bindings/ProjectCard";
import type { ProviderTest } from "../bindings/ProviderTest";
import type { RecordingEvent } from "../bindings/RecordingEvent";
import type { SystemStatus } from "../bindings/SystemStatus";
import type { SystemVoiceView } from "../bindings/SystemVoiceView";
import type { Task } from "../bindings/Task";
import type { TaskCheck } from "../bindings/TaskCheck";
import type { Transcript } from "../bindings/Transcript";
import type { UsageSummary } from "../bindings/UsageSummary";
import type { VoiceOverview } from "../bindings/VoiceOverview";
import type { VoiceRef } from "../bindings/VoiceRef";
import type { VoiceSettings } from "../bindings/VoiceSettings";

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

  createProject: () => invoke<Project>("create_project"),
  listProjects: () => invoke<ProjectCard[]>("list_projects"),
  deleteProject: (project_id: string) => invoke<null>("delete_project", { project_id }),
  getInterview: (project_id: string) => invoke<InterviewView>("get_interview", { project_id }),
  sendMessage: (project_id: string, text: string, input_mode: "text" | "voice", on_event: Channel<InterviewEvent>) =>
    invoke<InterviewView>("send_message", { project_id, text, input_mode, on_event }),
  continueInterview: (project_id: string, on_event: Channel<InterviewEvent>) =>
    invoke<InterviewView>("continue_interview", { project_id, on_event }),
  editBriefField: (project_id: string, field: string, value: unknown) =>
    invoke<InterviewView>("edit_brief_field", { project_id, field, value }),
  revisitField: (project_id: string, field: string) => invoke<InterviewView>("revisit_field", { project_id, field }),
  finishInterview: (project_id: string) => invoke<InterviewView>("finish_interview", { project_id }),

  getVoiceOverview: () => invoke<VoiceOverview>("get_voice_overview"),
  saveVoiceSettings: (settings: VoiceSettings) => invoke<VoiceOverview>("save_voice_settings", { settings }),
  downloadWhisperModel: (name: string, on_progress: Channel<DownloadProgress>) =>
    invoke<VoiceOverview>("download_whisper_model", { name, on_progress }),
  deleteWhisperModel: (name: string) => invoke<VoiceOverview>("delete_whisper_model", { name }),
  installPiper: (on_progress: Channel<DownloadProgress>) => invoke<VoiceOverview>("install_piper", { on_progress }),
  listPiperVoices: () => invoke<PiperVoiceView[]>("list_piper_voices"),
  downloadPiperVoice: (key: string, on_progress: Channel<DownloadProgress>) =>
    invoke<VoiceOverview>("download_piper_voice", { key, on_progress }),
  listSystemVoices: () => invoke<SystemVoiceView[]>("list_system_voices"),
  checkFishAudio: () => invoke<FishCheckResult>("check_fish_audio"),
  searchFishVoices: (query: string | null, page: number) => invoke<FishVoicePage>("search_fish_voices", { query, page }),
  previewVoice: (engine: string, voice: VoiceRef | null) => invoke<null>("preview_voice", { engine, voice }),
  startRecording: (on_event: Channel<RecordingEvent>) => invoke<null>("start_recording", { on_event }),
  stopRecording: () => invoke<Transcript>("stop_recording"),
  cancelRecording: () => invoke<null>("cancel_recording"),
  stopSpeaking: () => invoke<null>("stop_speaking"),
};

/** Event name for voice activity (speaking, engine switches, budget). */
export const VOICE_STATUS_EVENT = "voice-status";

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
