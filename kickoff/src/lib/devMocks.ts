// In-memory command handlers so the UI runs in a plain browser (`npm run dev`)
// and in tests, without the Rust backend. Never used inside the Tauri app.
// The provider catalog is the real one, exported from Rust by `cargo test`.
import { mockIPC } from "@tauri-apps/api/mocks";

import type { AiSettings } from "../bindings/AiSettings";
import type { AppInfo } from "../bindings/AppInfo";
import type { AppSettings } from "../bindings/AppSettings";
import presetCatalog from "../bindings/fixtures/presetCatalog.json";
import type { KeyEntry } from "../bindings/KeyEntry";
import type { NewKeyInput } from "../bindings/NewKeyInput";
import type { OllamaInfo } from "../bindings/OllamaInfo";
import type { PresetCatalog } from "../bindings/PresetCatalog";
import type { ProviderTest } from "../bindings/ProviderTest";
import type { SystemStatus } from "../bindings/SystemStatus";
import type { TaskCheck } from "../bindings/TaskCheck";
import type { UsageSummary } from "../bindings/UsageSummary";
import { interviewHandlers, type InterviewMockOptions } from "./interviewMocks";
import { researchHandlers, type ResearchMockOptions } from "./researchMocks";
import { voiceHandlers, type VoiceMockOptions } from "./voiceMocks";

export const MOCK_APP_INFO: AppInfo = { name: "Kickoff", id: "dev.kickoff.studio", version: "0.0.0-dev" };
export const PRESETS = presetCatalog as PresetCatalog;

/** A key typed as this value fails its test, for exercising error paths. */
export const MOCK_BAD_KEY = "bad-key-0000";

export interface MockOptions extends InterviewMockOptions, VoiceMockOptions {
  research?: ResearchMockOptions;
  settings?: Partial<AppSettings>;
  ollama?: OllamaInfo;
  keys?: KeyEntry[];
}

export const NO_OLLAMA: OllamaInfo = { running: false, version: null, models: [], recommended_model: null, capable: false };

export const CAPABLE_OLLAMA: OllamaInfo = {
  running: true,
  version: "0.12.0",
  models: [{ id: "qwen3:8b", parameter_size: "8.2B", billions: 8.2 }],
  recommended_model: "qwen3:8b",
  capable: true,
};

export function installDevMocks(options: MockOptions = {}) {
  let settings: AppSettings = { theme: "dark", projects_dir: null, onboarding_done: false, ...options.settings };
  let keys: KeyEntry[] = [...(options.keys ?? [])];
  let ai: AiSettings = {
    fallback_chain: ["gemini", "groq", "openrouter", "ollama"].map((provider_id) => ({ provider_id, model: null })),
    interview: null,
    extraction: null,
    research: null,
    kit: null,
  };
  const ollama = options.ollama ?? NO_OLLAMA;
  const hasKey = (provider: string) => keys.some((k) => k.provider_id === provider && k.stored);
  const interview = interviewHandlers(options);
  const extra: Record<string, (a: Record<string, unknown>) => unknown> = {
    ...interview,
    ...researchHandlers(interview, hasKey, { streamDelayMs: options.streamDelayMs, ...options.research }),
    ...voiceHandlers(hasKey, options),
  };
  let nextId = 1;

  const status: SystemStatus = {
    os: "browser",
    data_dir: "(browser preview)",
    log_dir: "(browser preview)",
    schema_version: 2,
    presets_updated: PRESETS.updated,
    secret_store_problem: "Browser preview: keys are not stored.",
    default_projects_dir: "C:\\Users\\you\\Documents\\Kickoff Projects",
  };

  const providerReady = (id: string) =>
    (id === "ollama" && ollama.running) || keys.some((k) => k.provider_id === id && k.stored);

  const test = (provider_id: string, secret: string | null): ProviderTest => {
    const fail = (error_kind: string, message: string): ProviderTest => ({
      ok: false,
      provider_id,
      model: null,
      latency_ms: 300,
      error_kind,
      message,
    });
    if (provider_id === "ollama" && !ollama.running) return fail("unavailable", "Couldn't reach Ollama.");
    if (secret === MOCK_BAD_KEY) return fail("auth", "The key was rejected. Check that you copied the whole key.");
    if (provider_id !== "ollama" && secret === null && !providerReady(provider_id)) {
      return fail("missing_key", "This provider needs an API key; add one in the Key Library");
    }
    const model = provider_id === "ollama" ? (ollama.recommended_model ?? "llama3.2") : `${provider_id}-best-model`;
    return { ok: true, provider_id, model, latency_ms: 840, error_kind: null, message: `Working: ${model} answered in 0.8 s.` };
  };

  mockIPC((cmd, args) => {
    const a = (args ?? {}) as Record<string, unknown>;
    switch (cmd) {
      case "app_info":
        return MOCK_APP_INFO;
      case "get_settings":
        return settings;
      case "save_settings":
        settings = a.settings as AppSettings;
        return settings;
      case "system_status":
        return status;

      case "list_keys":
        return keys;
      case "add_key": {
        const input = a.input as NewKeyInput;
        if (keys.some((k) => k.env_var === input.env_var)) {
          throw { code: "invalid", message: `another key already uses ${input.env_var}` };
        }
        const entry: KeyEntry = {
          id: `key-${nextId++}`,
          label: input.label,
          kind: input.kind,
          provider_id: input.provider_id,
          env_var: input.env_var,
          created_at: new Date().toISOString(),
          last_used_at: null,
          last_test_at: null,
          last_test_ok: null,
          notes: input.notes,
          stored: true,
        };
        keys = [...keys, entry];
        return entry;
      }
      case "replace_key_secret":
        return null;
      case "delete_key":
        keys = keys.filter((k) => k.id !== a.id);
        return null;
      case "test_key": {
        const key = keys.find((k) => k.id === a.id);
        const result = test(key?.provider_id ?? "", null);
        keys = keys.map((k) => (k.id === a.id ? { ...k, last_test_ok: result.ok, last_test_at: new Date().toISOString() } : k));
        return result;
      }

      case "list_presets":
        return PRESETS;
      case "get_ai_settings":
        return ai;
      case "save_ai_settings":
        ai = a.settings as AiSettings;
        return ai;
      case "list_models":
        return a.provider_id === "ollama"
          ? ollama.models.map((m) => ({ id: m.id, parameter_size: m.parameter_size }))
          : [`${a.provider_id}-best-model`, `${a.provider_id}-small-model`].map((id) => ({ id, parameter_size: null }));
      case "test_provider":
        return test(a.provider_id as string, (a.secret as string | null) ?? null);
      case "detect_ollama":
        return ollama;
      case "usage_today":
        return ai.fallback_chain.map((r): UsageSummary => {
          const p = PRESETS.llm.find((x) => x.id === r.provider_id);
          return {
            provider_id: r.provider_id,
            display_name: p?.display_name ?? r.provider_id,
            calls: r.provider_id === "gemini" ? 42 : 0,
            tokens: r.provider_id === "gemini" ? 61_200 : 0,
            rate_limited: r.provider_id === "gemini" ? 2 : 0,
            rpd: p?.rpd ?? null,
            tpd: p?.tpd ?? null,
          };
        });
      case "check_task": {
        const ready = ai.fallback_chain.filter((r) => providerReady(r.provider_id));
        const first = ready[0];
        const result: TaskCheck = first
          ? { ok: true, provider_id: first.provider_id, model: `${first.provider_id}-best-model`, routes: ready.length, message: `Answered by ${first.provider_id}.` }
          : { ok: false, provider_id: null, model: null, routes: 0, message: "No AI provider is ready for this task. Add a key in the Key Library or start Ollama." };
        return result;
      }
      default:
        if (cmd in extra) return extra[cmd]!(a);
        throw { code: "internal", message: `No mock for command "${cmd}"` };
    }
  });
}
