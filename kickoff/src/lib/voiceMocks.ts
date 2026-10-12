// In-memory voice commands for the browser preview and tests: model and voice
// installs with progress, a fake recording with level events, and a fixed
// transcript. Nothing touches real audio.
import type { Channel } from "@tauri-apps/api/core";

import type { DownloadProgress } from "../bindings/DownloadProgress";
import type { EngineView } from "../bindings/EngineView";
import type { FishVoiceView } from "../bindings/FishVoiceView";
import type { PiperVoiceView } from "../bindings/PiperVoiceView";
import type { RecordingEvent } from "../bindings/RecordingEvent";
import type { SystemVoiceView } from "../bindings/SystemVoiceView";
import type { VoiceOverview } from "../bindings/VoiceOverview";
import type { VoiceSettings } from "../bindings/VoiceSettings";

/** What the fake microphone "hears". */
export const MOCK_TRANSCRIPT = "It's about a lighthouse keeper who has to survive one long winter.";

export const DEFAULT_VOICE_SETTINGS: VoiceSettings = {
  enabled: true,
  input: "whisper_local",
  whisper_model: "base",
  language: null,
  microphone: null,
  hands_free: false,
  review_transcript: false,
  speak_replies: true,
  output_order: ["fish_audio", "piper", "system"],
  fish_model: null,
  fish_voice: null,
  piper_voice: "en_US-lessac-medium",
  system_voice: null,
  fish_monthly_budget: null,
};

export interface VoiceMockOptions {
  /** Whisper models already downloaded. */
  whisperModels?: string[];
  voiceSettings?: Partial<VoiceSettings>;
}

const WHISPER = [
  { name: "tiny", size_mb: 75 },
  { name: "base", size_mb: 142 },
  { name: "small", size_mb: 466 },
  { name: "medium", size_mb: 1536 },
];

const PIPER: Omit<PiperVoiceView, "installed">[] = [
  { key: "en_US-lessac-medium", language: "en_US", language_name: "English", country: "United States", quality: "medium", size_mb: 63 },
  { key: "en_GB-alan-medium", language: "en_GB", language_name: "English", country: "Great Britain", quality: "medium", size_mb: 63 },
  { key: "de_DE-thorsten-medium", language: "de_DE", language_name: "German", country: "Germany", quality: "medium", size_mb: 63 },
];

const SYSTEM: SystemVoiceView[] = [
  { id: "zira", name: "Microsoft Zira", language: "en-US" },
  { id: "david", name: "Microsoft David", language: "en-US" },
];

const FISH: FishVoiceView[] = [
  { id: "fish-warm", title: "Warm Narrator", description: "Calm and friendly.", languages: ["en"], tags: ["male", "narration"] },
  { id: "fish-bright", title: "Bright Host", description: "Upbeat podcast voice.", languages: ["en"], tags: ["female"] },
];

const NAMES: Record<string, string> = { fish_audio: "Fish Audio", piper: "Piper (local)", system: "Windows voices" };

async function progress(channel: Channel<DownloadProgress>, total: number) {
  for (const part of [0.4, 1]) {
    channel.onmessage({ received: Math.round(total * part), total });
    await Promise.resolve();
  }
}

export function voiceHandlers(hasKey: (provider: string) => boolean, options: VoiceMockOptions = {}) {
  let settings: VoiceSettings = { ...DEFAULT_VOICE_SETTINGS, ...options.voiceSettings };
  const whisper = new Set(options.whisperModels ?? []);
  const piperVoices = new Set<string>();
  let piperInstalled = false;
  let recording: Channel<RecordingEvent> | null = null;

  const engine = (id: string): EngineView => {
    const problem =
      id === "fish_audio" && !hasKey("fish_audio")
        ? "Add a Fish Audio key in the Key Library."
        : id === "piper" && !piperInstalled
          ? "Install Piper to use it."
          : id === "piper" && !piperVoices.has(settings.piper_voice)
            ? "Download a Piper voice."
            : null;
    return { id, name: NAMES[id] ?? id, ready: problem === null, problem };
  };

  const overview = (): VoiceOverview => ({
    settings,
    local_whisper_available: true,
    whisper_models: WHISPER.map((m) => ({ ...m, installed: whisper.has(m.name) })),
    input_problem:
      settings.input === "whisper_local" && !whisper.has(settings.whisper_model)
        ? `Download the ${settings.whisper_model} speech model to talk.`
        : settings.input === "groq_whisper" && !hasKey("groq")
          ? "Add a Groq key in the Key Library to use Groq Whisper."
          : null,
    microphones: [
      { id: "wasapi:mic-1", name: "Microphone (USB Audio)", is_default: true },
      { id: "wasapi:mic-2", name: "Headset Microphone", is_default: false },
    ],
    microphone_problem: null,
    speakers_problem: null,
    engines: settings.output_order.map(engine),
    piper_supported: true,
    piper_installed: piperInstalled,
    fish_key: hasKey("fish_audio"),
    fish_used_this_month: 12_400,
  });

  return {
    get_voice_overview: () => overview(),
    save_voice_settings: (a: Record<string, unknown>) => {
      settings = a.settings as VoiceSettings;
      return overview();
    },
    download_whisper_model: async (a: Record<string, unknown>) => {
      const model = WHISPER.find((m) => m.name === a.name);
      if (!model) throw { code: "invalid", message: `unknown speech model \`${String(a.name)}\`` };
      await progress(a.on_progress as Channel<DownloadProgress>, model.size_mb * 1_000_000);
      whisper.add(model.name);
      return overview();
    },
    delete_whisper_model: (a: Record<string, unknown>) => {
      whisper.delete(a.name as string);
      return overview();
    },
    install_piper: async (a: Record<string, unknown>) => {
      await progress(a.on_progress as Channel<DownloadProgress>, 22_477_236);
      piperInstalled = true;
      return overview();
    },
    list_piper_voices: (): PiperVoiceView[] => PIPER.map((v) => ({ ...v, installed: piperVoices.has(v.key) })),
    download_piper_voice: async (a: Record<string, unknown>) => {
      await progress(a.on_progress as Channel<DownloadProgress>, 63_000_000);
      piperVoices.add(a.key as string);
      return overview();
    },
    list_system_voices: () => SYSTEM,
    check_fish_audio: () =>
      hasKey("fish_audio")
        ? { ok: true, model: "s2.1-pro-free", free: true, credit: 0, message: "Working: the free s2.1-pro-free model answered." }
        : { ok: false, model: null, free: false, credit: null, message: "Add a Fish Audio key in the Key Library first." },
    search_fish_voices: (a: Record<string, unknown>) => {
      const q = ((a.query as string | null) ?? "").toLowerCase();
      const voices = FISH.filter((v) => v.title.toLowerCase().includes(q));
      return { total: voices.length, voices };
    },
    preview_voice: () => null,
    start_recording: (a: Record<string, unknown>) => {
      const problem = overview().input_problem;
      if (problem) throw { code: "invalid", message: problem };
      recording = a.on_event as Channel<RecordingEvent>;
      const channel = recording;
      for (const level of [0.05, 0.3, 0.6, 0.2]) channel.onmessage({ type: "level", level });
      if (settings.hands_free) {
        window.setTimeout(() => channel.onmessage({ type: "auto_stop", reason: "end_of_speech" }), 20);
      }
      return null;
    },
    stop_recording: () => {
      if (!recording) throw { code: "invalid", message: "not recording" };
      recording = null;
      return { text: MOCK_TRANSCRIPT, seconds: 3.2, engine: settings.input };
    },
    cancel_recording: () => {
      recording = null;
      return null;
    },
    stop_speaking: () => null,
  } satisfies Record<string, (a: Record<string, unknown>) => unknown>;
}
