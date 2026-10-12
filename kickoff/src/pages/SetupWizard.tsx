// First-run setup: get the user to a working, free AI setup in about two
// minutes. Detects Ollama, otherwise recommends a free cloud provider, links
// to its key page, tests the key, and stores it in the key library.
import { CheckCircle2, Cpu, Download, ExternalLink, Mic, Play, Sparkles, XCircle } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router";

import type { ProviderSummary } from "../bindings/ProviderSummary";
import type { ProviderTest } from "../bindings/ProviderTest";
import {
  useAddKey,
  useAiSettings,
  useAppInfo,
  useKeys,
  useOllama,
  usePresets,
  useReplaceKeySecret,
  useSaveAiSettings,
  useSaveSettings,
  useSaveVoiceSettings,
  useSettings,
  useVoiceOverview,
} from "../app/queries";
import { Badge, Button, Field, Notice, Progress, Spinner, TextInput } from "../components/ui";
import { openExternal } from "../lib/external";
import { errorMessage, ipc } from "../lib/ipc";
import { accessLabel, shortName } from "../lib/providers";
import { useDownload } from "./VoiceSettings";

type Step = "welcome" | "choose" | "connect" | "voice" | "done";

/** One line on why you'd pick each free provider. */
const PITCH: Record<string, string> = {
  gemini: "Best quality of the free cloud options. Free key, no card.",
  groq: "Very fast replies on open models. Free key, no card.",
  openrouter: "One key for many free models; small daily allowance.",
  mistral: "European provider; free plan needs phone verification.",
  anthropic: "Claude models. Paid: billed to your Anthropic account.",
};

export function SetupWizard() {
  const navigate = useNavigate();
  const { data: info } = useAppInfo();
  const { data: settings } = useSettings();
  const saveSettings = useSaveSettings();
  const [step, setStep] = useState<Step>("welcome");
  const [choice, setChoice] = useState<string | null>(null);
  const [result, setResult] = useState<ProviderTest | null>(null);

  async function finish() {
    if (settings) await saveSettings.mutateAsync({ ...settings, onboarding_done: true });
    navigate("/");
  }

  return (
    <div className="flex min-h-full items-center justify-center px-6 py-10">
      <div className="w-full max-w-2xl">
        <div className="mb-8 flex items-center gap-3">
          <img src="/icon.png" alt="" className="h-9 w-9 rounded-lg" />
          <span className="text-lg font-semibold">{info?.name}</span>
          <StepDots step={step} />
        </div>

        {step === "welcome" && (
          <section>
            <h1 className="text-2xl font-semibold">Let's connect a free AI model</h1>
            <p className="mt-3 text-muted">
              {info?.name} interviews you about your idea and turns it into everything Claude Code needs to start building.
              For that it needs an AI model. Setup takes about two minutes and nothing here costs money.
            </p>
            <div className="mt-8 flex gap-3">
              <Button variant="primary" onClick={() => setStep("choose")}>
                Get started
              </Button>
              <Button variant="ghost" onClick={() => void finish()}>
                Skip for now
              </Button>
            </div>
          </section>
        )}

        {step === "choose" && (
          <ChooseStep
            onBack={() => setStep("welcome")}
            onChoose={(id) => {
              setChoice(id);
              setResult(null);
              setStep("connect");
            }}
          />
        )}

        {step === "connect" && choice && (
          <ConnectStep
            providerId={choice}
            onBack={() => setStep("choose")}
            onConnected={(r) => {
              setResult(r);
              setStep("voice");
            }}
          />
        )}

        {step === "voice" && <VoiceStep onDone={() => setStep("done")} />}

        {step === "done" && result && (
          <section>
            <div className="flex items-center gap-3">
              <CheckCircle2 className="text-success" size={28} aria-hidden />
              <h1 className="text-2xl font-semibold">You're ready</h1>
            </div>
            <p className="mt-3 text-muted">{result.message}</p>
            <div className="mt-6">
              <Notice>
              Tip: add a second free key (for example Groq) in the Key Library. When one provider's daily limit runs out,{" "}
              {info?.name} switches to the next one automatically.
              </Notice>
            </div>
            <div className="mt-8 flex gap-3">
              <Button variant="primary" busy={saveSettings.isPending} onClick={() => void finish()}>
                <Sparkles size={16} /> Start my first project
              </Button>
            </div>
          </section>
        )}
      </div>
    </div>
  );
}

function StepDots({ step }: { step: Step }) {
  const steps: Step[] = ["welcome", "choose", "connect", "voice", "done"];
  const index = steps.indexOf(step);
  return (
    <ol className="ml-auto flex gap-1.5" aria-label={`Step ${index + 1} of ${steps.length}`}>
      {steps.map((s, i) => (
        <li key={s} className={`h-1.5 w-6 rounded-full ${i <= index ? "bg-accent" : "bg-surface-2"}`} />
      ))}
    </ol>
  );
}

function ChooseStep({ onChoose, onBack }: { onChoose: (id: string) => void; onBack: () => void }) {
  const { data: catalog } = usePresets();
  const { data: ollama, isLoading: detecting } = useOllama();

  const cloud = useMemo(() => {
    if (!catalog) return [];
    return catalog.wizard_order
      .map((id) => catalog.llm.find((p) => p.id === id))
      .filter((p): p is ProviderSummary => !!p);
  }, [catalog]);
  const anthropic = catalog?.llm.find((p) => p.id === "anthropic");
  const recommended = ollama?.capable ? "ollama" : cloud[0]?.id;
  const [selected, setSelected] = useState<string | null>(null);
  const current = selected ?? recommended ?? null;

  if (detecting || !catalog) return <Spinner label="Looking for Ollama on this PC…" />;

  return (
    <section>
      <h1 className="text-2xl font-semibold">Choose your AI</h1>
      <p className="mt-2 text-muted">All of these are free. You can add more later; extra providers become automatic backups.</p>
      <div className="mt-6 flex flex-col gap-2" role="radiogroup" aria-label="AI provider">
        {ollama?.running && (
          <Option
            id="ollama"
            selected={current === "ollama"}
            onSelect={setSelected}
            title="Ollama on this PC"
            badge={<Badge tone="success">Free · runs locally</Badge>}
            recommended={recommended === "ollama"}
            icon={<Cpu size={18} />}
          >
            {ollama.capable
              ? `Private and unlimited. Will use ${ollama.recommended_model}.`
              : `Only small models are installed (${ollama.models.map((m) => m.id).join(", ") || "none"}), so answers will be weaker. For better results run "ollama pull qwen3:8b".`}
          </Option>
        )}
        {cloud.map((p) => (
          <Option
            key={p.id}
            id={p.id}
            selected={current === p.id}
            onSelect={setSelected}
            title={p.display_name}
            badge={<Badge tone={accessLabel(p).tone}>{accessLabel(p).text}</Badge>}
            recommended={recommended === p.id}
          >
            {PITCH[p.id] ?? p.notes}
          </Option>
        ))}
      </div>
      {!ollama?.running && (
        <p className="mt-4 text-sm text-muted">
          Have a capable graphics card? Install{" "}
          <button type="button" className="text-accent-strong hover:underline" onClick={() => void openExternal("https://ollama.com/download")}>
            Ollama
          </button>{" "}
          to run models fully offline, then run this setup again from Settings.
        </p>
      )}
      {anthropic && (
        <p className="mt-2 text-sm text-muted">
          Have a paid Anthropic key?{" "}
          <button type="button" className="text-accent-strong hover:underline" onClick={() => onChoose("anthropic")}>
            Use Claude instead
          </button>
        </p>
      )}
      <div className="mt-8 flex gap-3">
        <Button variant="primary" disabled={!current} onClick={() => current && onChoose(current)}>
          Continue
        </Button>
        <Button variant="ghost" onClick={onBack}>
          Back
        </Button>
      </div>
    </section>
  );
}

function Option({
  id,
  title,
  badge,
  recommended,
  selected,
  onSelect,
  icon,
  children,
}: {
  id: string;
  title: string;
  badge: React.ReactNode;
  recommended: boolean;
  selected: boolean;
  onSelect: (id: string) => void;
  icon?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={() => onSelect(id)}
      className={`flex w-full items-start gap-3 rounded-lg border px-4 py-3 text-left transition-colors ${
        selected ? "border-accent bg-surface-2" : "border-line bg-surface hover:border-accent"
      }`}
    >
      <span
        className={`mt-1 h-4 w-4 shrink-0 rounded-full border-2 ${selected ? "border-accent bg-accent" : "border-line"}`}
        aria-hidden
      />
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-2">
          {icon}
          <span className="font-medium">{title}</span>
          {badge}
          {recommended && <Badge tone="accent">Recommended</Badge>}
        </span>
        <span className="mt-1 block text-sm text-muted">{children}</span>
      </span>
    </button>
  );
}

function ConnectStep({
  providerId,
  onBack,
  onConnected,
}: {
  providerId: string;
  onBack: () => void;
  onConnected: (result: ProviderTest) => void;
}) {
  const { data: catalog } = usePresets();
  const { data: keys } = useKeys();
  const { data: ai } = useAiSettings();
  const addKey = useAddKey();
  const replaceKey = useReplaceKeySecret();
  const saveAi = useSaveAiSettings();
  const provider = catalog?.llm.find((p) => p.id === providerId);
  const existing = keys?.find((k) => k.provider_id === providerId);
  const [secret, setSecret] = useState("");
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<ProviderTest | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (!provider) return null;
  const name = shortName(catalog, providerId);

  /** Put the chosen provider first in the chain, keeping the rest as backups. */
  async function makePrimary() {
    const rest = (ai?.fallback_chain ?? []).filter((r) => r.provider_id !== providerId);
    await saveAi.mutateAsync({
      ...(ai ?? { interview: null, extraction: null, research: null, kit: null, fallback_chain: [] }),
      fallback_chain: [{ provider_id: providerId, model: null }, ...rest],
    });
  }

  async function connect(useSaved: boolean) {
    setError(null);
    setResult(null);
    setTesting(true);
    try {
      const test = await ipc.testProvider(providerId, provider!.needs_key && !useSaved ? secret : null);
      setResult(test);
      if (!test.ok) return;
      if (provider!.needs_key && !useSaved) {
        if (existing) {
          await replaceKey.mutateAsync({ id: existing.id, secret });
        } else {
          await addKey.mutateAsync({
            label: provider!.display_name,
            kind: "llm",
            provider_id: providerId,
            env_var: provider!.key_env ?? `${providerId.toUpperCase()}_API_KEY`,
            secret,
            notes: null,
          });
        }
        setSecret("");
      }
      await makePrimary();
      onConnected(test);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setTesting(false);
    }
  }

  return (
    <section>
      <h1 className="text-2xl font-semibold">Connect {name}</h1>
      {!provider.needs_key ? (
        <p className="mt-2 text-muted">No key needed. {name} runs on this PC; let's check it answers.</p>
      ) : (
        <ol className="mt-4 flex list-decimal flex-col gap-4 pl-5">
          <li>
            <p>Open {name} and create a key{provider.free && !provider.card_required ? " (free, no card)" : ""}.</p>
            {provider.signup_url && (
              <Button className="mt-2" onClick={() => void openExternal(provider.signup_url!)}>
                Open key page <ExternalLink size={14} />
              </Button>
            )}
          </li>
          <li>
            <Field label="Paste the key here" hint="It goes straight to Windows Credential Manager. It never appears in project folders or logs.">
              {(id) => (
                <TextInput
                  id={id}
                  type="password"
                  autoComplete="off"
                  spellCheck={false}
                  value={secret}
                  placeholder="Paste your key"
                  onChange={(e) => setSecret(e.target.value)}
                />
              )}
            </Field>
          </li>
        </ol>
      )}
      {provider.privacy_note && (
        <div className="mt-4">
          <Notice tone="warning">{provider.privacy_note}</Notice>
        </div>
      )}
      {result && !result.ok && (
        <div className="mt-4">
          <Notice tone="danger">
            <span className="inline-flex items-start gap-2">
              <XCircle size={16} className="mt-0.5 shrink-0" />
              {result.message}
            </span>
          </Notice>
        </div>
      )}
      {error && (
        <div className="mt-4">
          <Notice tone="danger">{error}</Notice>
        </div>
      )}
      <div className="mt-8 flex flex-wrap gap-3">
        <Button
          variant="primary"
          busy={testing}
          disabled={provider.needs_key && !secret.trim()}
          onClick={() => void connect(false)}
        >
          {provider.needs_key ? "Test and save key" : `Test ${name}`}
        </Button>
        {provider.needs_key && existing?.stored && (
          <Button busy={testing} onClick={() => void connect(true)}>
            Use my saved key
          </Button>
        )}
        <Button variant="ghost" onClick={onBack}>
          Back
        </Button>
      </div>
    </section>
  );
}

/** Optional: download local speech recognition and hear the producer's voice. */
function VoiceStep({ onDone }: { onDone: () => void }) {
  const { data: voice } = useVoiceOverview();
  const save = useSaveVoiceSettings();
  const download = useDownload();
  const [previewError, setPreviewError] = useState<string | null>(null);
  if (!voice) return <Spinner label="Checking your microphone…" />;
  const model = voice.whisper_models.find((m) => m.name === voice.settings.whisper_model);
  const output = voice.engines.find((e) => e.ready);

  return (
    <section>
      <div className="flex items-center gap-3">
        <Mic className="text-accent-strong" size={26} aria-hidden />
        <h1 className="text-2xl font-semibold">Talk instead of typing?</h1>
      </div>
      <p className="mt-3 text-muted">
        The interview works best as a conversation. Speech recognition runs on this PC, so your voice never leaves it. This step is
        optional; you can always type.
      </p>
      <div className="mt-6 flex flex-col gap-4">
        <div className="rounded-lg border border-line p-4">
          <div className="flex items-center justify-between gap-3">
            <div>
              <div className="font-medium">Speech recognition</div>
              <div className="text-sm text-muted">Whisper {model?.name} · {model?.size_mb} MB download</div>
            </div>
            {model?.installed ? (
              <Badge tone="success">Ready</Badge>
            ) : (
              <Button
                variant="primary"
                disabled={download.busy || !voice.local_whisper_available}
                onClick={() => void download.run((ch) => ipc.downloadWhisperModel(voice.settings.whisper_model, ch))}
              >
                <Download size={14} /> Download
              </Button>
            )}
          </div>
          {download.busy && (
            <div className="mt-3">
              <Progress value={download.progress ?? 0} label="Downloading speech recognition" />
            </div>
          )}
          {download.error && (
            <div className="mt-3">
              <Notice tone="danger">{download.error}</Notice>
            </div>
          )}
          {voice.microphone_problem && <p className="mt-2 text-sm text-warning">{voice.microphone_problem}</p>}
        </div>
        <div className="rounded-lg border border-line p-4">
          <div className="flex items-center justify-between gap-3">
            <div>
              <div className="font-medium">The producer's voice</div>
              <div className="text-sm text-muted">
                {output ? `${output.name} is ready.` : "No voice is ready yet."} Fish Audio and Piper voices sound more natural; set them up
                later in Settings → Voice.
              </div>
            </div>
            {output && (
              <Button
                onClick={async () => {
                  setPreviewError(null);
                  try {
                    await ipc.previewVoice(output.id, null);
                  } catch (e) {
                    setPreviewError(errorMessage(e));
                  }
                }}
              >
                <Play size={14} /> Listen
              </Button>
            )}
          </div>
          {previewError && <p className="mt-2 text-sm text-danger">{previewError}</p>}
        </div>
      </div>
      <div className="mt-8 flex gap-3">
        <Button variant="primary" disabled={download.busy} onClick={onDone}>
          Continue
        </Button>
        <Button
          variant="ghost"
          onClick={async () => {
            await save.mutateAsync({ ...voice.settings, enabled: false });
            onDone();
          }}
        >
          Skip, I'll type
        </Button>
      </div>
    </section>
  );
}
