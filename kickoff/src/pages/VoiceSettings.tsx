// Settings → Voice: how the user talks (speech recognition, microphone) and
// how the producer sounds (speech engines in fallback order, voices, budget).
import { useQueryClient } from "@tanstack/react-query";
import { Channel } from "@tauri-apps/api/core";
import { ArrowDown, ArrowUp, Check, Download, Play, Search, Trash2 } from "lucide-react";
import { useState } from "react";

import type { DownloadProgress } from "../bindings/DownloadProgress";
import type { EngineView } from "../bindings/EngineView";
import type { FishCheckResult } from "../bindings/FishCheckResult";
import type { FishVoicePage } from "../bindings/FishVoicePage";
import type { VoiceOverview } from "../bindings/VoiceOverview";
import type { VoiceRef } from "../bindings/VoiceRef";
import type { VoiceSettings } from "../bindings/VoiceSettings";
import { queryKeys, usePiperVoices, useSaveVoiceSettings, useSystemVoices, useVoiceOverview } from "../app/queries";
import { Meter } from "../components/Meter";
import { Badge, Button, Card, Field, Notice, Progress, Select, Switch, TextInput } from "../components/ui";
import { errorMessage, ipc } from "../lib/ipc";

const LANGUAGES: [string, string][] = [
  ["", "Detect automatically"],
  ["en", "English"],
  ["de", "German"],
  ["fr", "French"],
  ["es", "Spanish"],
  ["it", "Italian"],
  ["pt", "Portuguese"],
  ["nl", "Dutch"],
  ["pl", "Polish"],
  ["sv", "Swedish"],
  ["tr", "Turkish"],
  ["ru", "Russian"],
  ["ja", "Japanese"],
  ["ko", "Korean"],
  ["zh", "Chinese"],
];

/** Runs a download command and tracks its progress (0..1, or null when idle). */
export function useDownload() {
  const client = useQueryClient();
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  async function run(action: (channel: Channel<DownloadProgress>) => Promise<VoiceOverview>) {
    setError(null);
    setProgress(0);
    const channel = new Channel<DownloadProgress>();
    channel.onmessage = (p) => setProgress(p.total ? p.received / p.total : 0);
    try {
      client.setQueryData(queryKeys.voice, await action(channel));
      void client.invalidateQueries({ queryKey: queryKeys.piperVoices });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setProgress(null);
    }
  }
  return { run, progress, busy: progress !== null, error };
}

export function VoiceSettingsSection() {
  const { data: overview, error } = useVoiceOverview();
  const save = useSaveVoiceSettings();
  if (error) {
    return (
      <Card title="Voice">
        <Notice tone="danger">{errorMessage(error)}</Notice>
      </Card>
    );
  }
  if (!overview) return null;
  const settings = overview.settings;
  const update = (patch: Partial<VoiceSettings>) => save.mutate({ ...settings, ...patch });

  return (
    <Card title="Voice">
      <div className="flex flex-col gap-6">
        <Switch
          label="Talk with the producer"
          description="Speak your answers and hear the replies. Turn off to keep the interview text only."
          checked={settings.enabled}
          onChange={(enabled) => update({ enabled })}
        />
        {save.isError && <Notice tone="danger">Couldn't save: {errorMessage(save.error)}</Notice>}
        {settings.enabled && (
          <>
            <Listening overview={overview} update={update} />
            <Speaking overview={overview} update={update} />
          </>
        )}
      </div>
    </Card>
  );
}

type Update = (patch: Partial<VoiceSettings>) => void;

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-4 border-t border-line pt-5">
      <h3 className="text-sm font-semibold uppercase tracking-wide text-muted">{title}</h3>
      {children}
    </section>
  );
}

// ------------------------------------------------------------ listening

function Listening({ overview, update }: { overview: VoiceOverview; update: Update }) {
  const s = overview.settings;
  return (
    <Section title="Listening">
      <fieldset>
        <legend className="mb-2 text-sm font-medium">Speech recognition</legend>
        <div className="flex flex-col gap-2" role="radiogroup">
          <Choice
            name="stt"
            checked={s.input === "whisper_local"}
            onChange={() => update({ input: "whisper_local" })}
            disabled={!overview.local_whisper_available}
            title="On this PC (Whisper)"
            detail="Private and works offline. Needs a one-time model download."
          />
          <Choice
            name="stt"
            checked={s.input === "groq_whisper"}
            onChange={() => update({ input: "groq_whisper" })}
            title="Groq Whisper (cloud, free tier)"
            detail="Fast on any PC. Uses your Groq key; audio is sent to Groq."
          />
        </div>
      </fieldset>
      {s.input === "whisper_local" && <WhisperModels overview={overview} update={update} />}
      {overview.input_problem && <Notice tone="warning">{overview.input_problem}</Notice>}

      <div className="grid grid-cols-2 gap-4">
        <Field label="Language you speak">
          {(id) => (
            <Select id={id} value={s.language ?? ""} onChange={(e) => update({ language: e.target.value || null })}>
              {LANGUAGES.map(([code, name]) => (
                <option key={code} value={code}>
                  {name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Microphone">
          {(id) => (
            <Select id={id} value={s.microphone ?? ""} onChange={(e) => update({ microphone: e.target.value || null })}>
              <option value="">System default</option>
              {overview.microphones.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.name}
                  {m.is_default ? " (default)" : ""}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
      {overview.microphone_problem && <Notice tone="warning">{overview.microphone_problem}</Notice>}
      <Switch
        label="Hands-free"
        description="Stop listening when you stop talking, instead of clicking again or releasing F2."
        checked={s.hands_free}
        onChange={(hands_free) => update({ hands_free })}
      />
      <Switch
        label="Review what I said before sending"
        description="Puts the transcript in the message box so you can fix it first."
        checked={s.review_transcript}
        onChange={(review_transcript) => update({ review_transcript })}
      />
    </Section>
  );
}

function Choice({
  name,
  checked,
  onChange,
  title,
  detail,
  disabled = false,
}: {
  name: string;
  checked: boolean;
  onChange: () => void;
  title: string;
  detail: string;
  disabled?: boolean;
}) {
  return (
    <label
      className={`flex cursor-pointer items-start gap-3 rounded-md border px-3 py-2 ${
        checked ? "border-accent bg-surface-2" : "border-line hover:border-accent"
      } ${disabled ? "cursor-not-allowed opacity-50" : ""}`}
    >
      <input type="radio" name={name} checked={checked} onChange={onChange} disabled={disabled} className="mt-1 accent-[var(--k-accent)]" />
      <span>
        <span className="block text-sm font-medium">{title}</span>
        <span className="block text-xs text-muted">{detail}</span>
      </span>
    </label>
  );
}

function WhisperModels({ overview, update }: { overview: VoiceOverview; update: Update }) {
  const download = useDownload();
  const [active, setActive] = useState<string | null>(null);
  const client = useQueryClient();
  const chosen = overview.settings.whisper_model;
  const hint: Record<string, string> = {
    tiny: "fastest, least accurate",
    base: "recommended",
    "base.en": "English only",
    small: "more accurate, slower",
    "small.en": "English only, more accurate",
    medium: "most accurate, needs a fast PC",
  };
  return (
    <div className="flex flex-col gap-2">
      <ul className="flex flex-col gap-2" aria-label="Speech models">
        {overview.whisper_models.map((m) => (
          <li key={m.name} className="flex items-center gap-3 rounded-md border border-line px-3 py-2 text-sm">
            <label className="flex flex-1 cursor-pointer items-center gap-3">
              <input
                type="radio"
                name="whisper-model"
                checked={chosen === m.name}
                onChange={() => update({ whisper_model: m.name })}
                className="accent-[var(--k-accent)]"
              />
              <span>
                <span className="font-medium">{m.name}</span>
                <span className="text-muted">
                  {" "}
                  · {m.size_mb >= 1000 ? `${(m.size_mb / 1000).toFixed(1)} GB` : `${m.size_mb} MB`}
                  {hint[m.name] ? ` · ${hint[m.name]}` : ""}
                </span>
              </span>
            </label>
            {m.installed ? (
              <>
                <Badge tone="success">Downloaded</Badge>
                <Button
                  variant="ghost"
                  aria-label={`Delete ${m.name} model`}
                  onClick={async () => client.setQueryData(queryKeys.voice, await ipc.deleteWhisperModel(m.name))}
                >
                  <Trash2 size={14} />
                </Button>
              </>
            ) : active === m.name && download.busy ? (
              <div className="w-32">
                <Progress value={download.progress ?? 0} label={`Downloading ${m.name}`} />
              </div>
            ) : (
              <Button
                disabled={download.busy}
                onClick={() => {
                  setActive(m.name);
                  void download.run((ch) => ipc.downloadWhisperModel(m.name, ch));
                }}
              >
                <Download size={14} /> Download
              </Button>
            )}
          </li>
        ))}
      </ul>
      {download.error && <Notice tone="danger">{download.error}</Notice>}
    </div>
  );
}

// ------------------------------------------------------------ speaking

function Speaking({ overview, update }: { overview: VoiceOverview; update: Update }) {
  const s = overview.settings;
  const move = (index: number, by: number) => {
    const order = [...s.output_order];
    const [item] = order.splice(index, 1);
    order.splice(index + by, 0, item!);
    update({ output_order: order });
  };
  return (
    <Section title="The producer's voice">
      <Switch
        label="Read replies aloud"
        description="Click Stop or press Esc to interrupt; starting to talk interrupts too."
        checked={s.speak_replies}
        onChange={(speak_replies) => update({ speak_replies })}
      />
      {overview.speakers_problem && <Notice tone="warning">{overview.speakers_problem}</Notice>}
      <div>
        <p className="mb-2 text-sm text-muted">Tried in this order. If one fails or runs out of free use, the next one takes over.</p>
        <ol className="flex flex-col gap-3" aria-label="Speech engines">
          {overview.engines.map((engine, i) => (
            <li key={engine.id} className="rounded-md border border-line">
              <div className="flex items-center gap-3 px-3 py-2">
                <span className="w-5 text-sm text-muted">{i + 1}.</span>
                <span className="flex-1 text-sm font-medium">{engine.name}</span>
                <Badge tone={engine.ready ? "success" : "neutral"}>{engine.ready ? "Ready" : "Not set up"}</Badge>
                {engine.ready && <PreviewButton engine={engine.id} />}
                <Button variant="ghost" aria-label={`Move ${engine.name} up`} disabled={i === 0} onClick={() => move(i, -1)}>
                  <ArrowUp size={14} />
                </Button>
                <Button
                  variant="ghost"
                  aria-label={`Move ${engine.name} down`}
                  disabled={i === overview.engines.length - 1}
                  onClick={() => move(i, 1)}
                >
                  <ArrowDown size={14} />
                </Button>
              </div>
              <div className="border-t border-line px-3 py-3">
                <EngineSetup engine={engine} overview={overview} update={update} />
              </div>
            </li>
          ))}
        </ol>
      </div>
    </Section>
  );
}

function PreviewButton({ engine, voice = null, label = "Preview" }: { engine: string; voice?: VoiceRef | null; label?: string }) {
  const [error, setError] = useState<string | null>(null);
  return (
    <span className="inline-flex items-center gap-2">
      <Button
        variant="ghost"
        aria-label={`${label} ${voice?.name ?? engine}`}
        onClick={async () => {
          setError(null);
          try {
            await ipc.previewVoice(engine, voice);
          } catch (e) {
            setError(errorMessage(e));
          }
        }}
      >
        <Play size={14} /> {label}
      </Button>
      {error && <span className="text-xs text-danger">{error}</span>}
    </span>
  );
}

function EngineSetup({ engine, overview, update }: { engine: EngineView; overview: VoiceOverview; update: Update }) {
  // The setup below already explains a missing key or install.
  const explained = (engine.id === "fish_audio" && !overview.fish_key) || (engine.id === "piper" && overview.piper_supported && !overview.piper_installed);
  const problem = engine.problem && !explained && <p className="mb-3 text-sm text-muted">{engine.problem}</p>;
  switch (engine.id) {
    case "fish_audio":
      return (
        <>
          {problem}
          <FishSetup overview={overview} update={update} />
        </>
      );
    case "piper":
      return (
        <>
          {problem}
          <PiperSetup overview={overview} update={update} />
        </>
      );
    case "system":
      return (
        <>
          {problem}
          <SystemSetup overview={overview} update={update} />
        </>
      );
    default:
      return problem || null;
  }
}

function FishSetup({ overview, update }: { overview: VoiceOverview; update: Update }) {
  const s = overview.settings;
  const [check, setCheck] = useState<FishCheckResult | null>(null);
  const [checking, setChecking] = useState(false);
  const client = useQueryClient();
  const [budget, setBudget] = useState(s.fish_monthly_budget?.toString() ?? "");

  if (!overview.fish_key) {
    return <p className="text-sm text-muted">Fish Audio has natural voices and a free model for some accounts. Add a key in the Key Library, then check it here.</p>;
  }
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <Button
          busy={checking}
          onClick={async () => {
            setChecking(true);
            try {
              setCheck(await ipc.checkFishAudio());
              void client.invalidateQueries({ queryKey: queryKeys.voice });
            } catch (e) {
              setCheck({ ok: false, model: null, free: false, credit: null, message: errorMessage(e) });
            } finally {
              setChecking(false);
            }
          }}
        >
          Check key
        </Button>
        <span className="text-sm text-muted">{s.fish_model ? `Model: ${s.fish_model}` : "Not checked yet"}</span>
      </div>
      {check && <Notice tone={check.ok ? (check.free ? "success" : "warning") : "danger"}>{check.message}</Notice>}

      <FishVoices current={s.fish_voice} onPick={(fish_voice) => update({ fish_voice })} />

      <div className="grid grid-cols-[12rem_1fr] items-end gap-4">
        <Field label="Monthly limit (characters)" hint="Warnings at 80% and 95%. Empty means no limit.">
          {(id) => (
            <TextInput
              id={id}
              inputMode="numeric"
              value={budget}
              onChange={(e) => setBudget(e.target.value.replace(/\D/g, ""))}
              onBlur={() => update({ fish_monthly_budget: budget ? Number(budget) : null })}
            />
          )}
        </Field>
        <Meter label="Used this month" value={overview.fish_used_this_month} limit={s.fish_monthly_budget} unit="characters" />
      </div>
    </div>
  );
}

function FishVoices({ current, onPick }: { current: VoiceRef | null; onPick: (v: VoiceRef) => void }) {
  const [query, setQuery] = useState("");
  const [page, setPage] = useState<FishVoicePage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [searching, setSearching] = useState(false);
  async function search() {
    setSearching(true);
    setError(null);
    try {
      setPage(await ipc.searchFishVoices(query || null, 1));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setSearching(false);
    }
  }
  return (
    <div className="flex flex-col gap-2">
      <span className="text-sm font-medium">Voice: {current ? current.name : "Fish Audio's default"}</span>
      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          void search();
        }}
      >
        <TextInput aria-label="Search Fish Audio voices" placeholder="Search voices, e.g. narrator" value={query} onChange={(e) => setQuery(e.target.value)} />
        <Button type="submit" busy={searching}>
          <Search size={14} /> Search
        </Button>
      </form>
      {error && <Notice tone="danger">{error}</Notice>}
      {page && (
        <ul className="flex max-h-64 flex-col gap-1 overflow-y-auto" aria-label="Fish Audio voices">
          {page.voices.length === 0 && <li className="text-sm text-muted">No voices found.</li>}
          {page.voices.map((v) => {
            const ref = { id: v.id, name: v.title };
            return (
              <li key={v.id} className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-surface-2">
                <span className="min-w-0 flex-1">
                  <span className="font-medium">{v.title}</span>
                  <span className="text-muted"> · {[...v.languages, ...v.tags].slice(0, 4).join(", ")}</span>
                </span>
                <PreviewButton engine="fish_audio" voice={ref} label="Listen" />
                {current?.id === v.id ? (
                  <Badge tone="accent">
                    <Check size={12} /> Chosen
                  </Badge>
                ) : (
                  <Button variant="ghost" onClick={() => onPick(ref)}>
                    Use
                  </Button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function PiperSetup({ overview, update }: { overview: VoiceOverview; update: Update }) {
  const install = useDownload();
  const voiceDownload = useDownload();
  const { data: voices } = usePiperVoices(overview.piper_supported);
  if (!overview.piper_supported) return null;
  if (!overview.piper_installed) {
    return (
      <div className="flex flex-col gap-2">
        <p className="text-sm text-muted">Piper is a free voice that runs on this PC. The program is 22 MB; each voice is about 60 MB.</p>
        <div className="flex items-center gap-3">
          <Button disabled={install.busy} onClick={() => void install.run((ch) => ipc.installPiper(ch))}>
            <Download size={14} /> Install Piper
          </Button>
          {install.busy && (
            <div className="w-40">
              <Progress value={install.progress ?? 0} label="Installing Piper" />
            </div>
          )}
        </div>
        {install.error && <Notice tone="danger">{install.error}</Notice>}
      </div>
    );
  }
  const chosen = voices?.find((v) => v.key === overview.settings.piper_voice);
  const groups = new Map<string, NonNullable<typeof voices>>();
  for (const v of voices ?? []) groups.set(v.language_name, [...(groups.get(v.language_name) ?? []), v]);
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-end gap-3">
        <div className="flex-1">
          <Field label="Piper voice">
            {(id) => (
              <Select id={id} value={overview.settings.piper_voice} onChange={(e) => update({ piper_voice: e.target.value })}>
                {[...groups].map(([language, list]) => (
                  <optgroup key={language} label={language}>
                    {list.map((v) => (
                      <option key={v.key} value={v.key}>
                        {v.key} ({v.country}, {v.quality}){v.installed ? " ✓" : ""}
                      </option>
                    ))}
                  </optgroup>
                ))}
              </Select>
            )}
          </Field>
        </div>
        {chosen && !chosen.installed && (
          <Button disabled={voiceDownload.busy} onClick={() => void voiceDownload.run((ch) => ipc.downloadPiperVoice(chosen.key, ch))}>
            <Download size={14} /> Download ({chosen.size_mb} MB)
          </Button>
        )}
      </div>
      {voiceDownload.busy && <Progress value={voiceDownload.progress ?? 0} label="Downloading voice" />}
      {voiceDownload.error && <Notice tone="danger">{voiceDownload.error}</Notice>}
      <p className="text-xs text-muted">Each Piper voice has its own license, saved next to it as MODEL_CARD.</p>
    </div>
  );
}

function SystemSetup({ overview, update }: { overview: VoiceOverview; update: Update }) {
  const { data: voices, error } = useSystemVoices(true);
  if (error) return null;
  return (
    <Field label="Windows voice">
      {(id) => (
        <Select
          id={id}
          value={overview.settings.system_voice?.id ?? ""}
          onChange={(e) => {
            const v = voices?.find((x) => x.id === e.target.value);
            update({ system_voice: v ? { id: v.id, name: v.name } : null });
          }}
        >
          <option value="">Windows default</option>
          {voices?.map((v) => (
            <option key={v.id} value={v.id}>
              {v.name} ({v.language})
            </option>
          ))}
        </Select>
      )}
    </Field>
  );
}
