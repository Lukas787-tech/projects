// Settings > AI: which model does which job, the fallback chain, and today's
// usage against free-tier limits.
import { ArrowDown, ArrowUp, CheckCircle2, Plus, Trash2, XCircle } from "lucide-react";
import { useState } from "react";

import type { AiSettings } from "../bindings/AiSettings";
import type { RouteSpec } from "../bindings/RouteSpec";
import type { Task } from "../bindings/Task";
import type { TaskCheck } from "../bindings/TaskCheck";
import { useAiSettings, useKeys, useModels, useOllama, usePresets, useSaveAiSettings, useUsageToday } from "../app/queries";
import { Meter } from "../components/Meter";
import { Badge, Button, Card, Notice, Select } from "../components/ui";
import { errorMessage, ipc } from "../lib/ipc";
import { displayName, shortName } from "../lib/providers";

const TASKS: { task: Task; label: string; hint: string }[] = [
  { task: "interview", label: "Interview", hint: "The producer's questions. Put your best model here." },
  { task: "extraction", label: "Brief extraction", hint: "Turns answers into brief fields. Needs reliable JSON." },
  { task: "research", label: "Research", hint: "Search queries and reference summaries." },
  { task: "kit", label: "Starter kit", hint: "Writes the project files, one section at a time." },
];

/** Whether a provider can be used right now, for the readiness badges. */
function useReadiness() {
  const { data: keys } = useKeys();
  const { data: ollama } = useOllama();
  return (providerId: string): { ready: boolean; label: string } => {
    if (providerId === "ollama") {
      return ollama?.running ? { ready: true, label: "Running" } : { ready: false, label: "Not running" };
    }
    const key = keys?.find((k) => k.provider_id === providerId);
    if (!key) return { ready: false, label: "No key" };
    if (!key.stored) return { ready: false, label: "Key missing" };
    if (key.last_test_ok === false) return { ready: false, label: "Key failed test" };
    return { ready: true, label: "Key saved" };
  };
}

export function AiSettingsSection() {
  const { data: ai } = useAiSettings();
  const save = useSaveAiSettings();
  if (!ai) return null;
  const update = (patch: Partial<AiSettings>) => save.mutate({ ...ai, ...patch });
  return (
    <>
      {save.isError && <Notice tone="danger">Couldn't save AI settings: {errorMessage(save.error)}</Notice>}
      <TaskModelsCard ai={ai} update={update} />
      <FallbackChainCard ai={ai} update={update} />
      <UsageCard />
    </>
  );
}

function TaskModelsCard({ ai, update }: { ai: AiSettings; update: (patch: Partial<AiSettings>) => void }) {
  return (
    <Card title="Models per task">
      <p className="mb-4 text-sm text-muted">
        Each job can use a different model. If the chosen one is unavailable or out of quota, the fallback chain below takes over.
      </p>
      <div className="flex flex-col divide-y divide-line">
        {TASKS.map(({ task, label, hint }) => (
          <TaskRow key={task} task={task} label={label} hint={hint} spec={ai[task]} ai={ai} onChange={(spec) => update({ [task]: spec })} />
        ))}
      </div>
    </Card>
  );
}

function TaskRow({
  task,
  label,
  hint,
  spec,
  ai,
  onChange,
}: {
  task: Task;
  label: string;
  hint: string;
  spec: RouteSpec | null;
  ai: AiSettings;
  onChange: (spec: RouteSpec | null) => void;
}) {
  const { data: catalog } = usePresets();
  const readiness = useReadiness();
  const { data: models } = useModels(spec?.provider_id ?? null);
  const [check, setCheck] = useState<TaskCheck | null>(null);
  const [checking, setChecking] = useState(false);
  const providers = catalog?.llm.filter((p) => readiness(p.id).ready || p.id === spec?.provider_id) ?? [];
  const first = ai.fallback_chain[0];

  async function runCheck() {
    setChecking(true);
    setCheck(null);
    try {
      setCheck(await ipc.checkTask(task));
    } catch (e) {
      setCheck({ ok: false, provider_id: null, model: null, routes: 0, message: errorMessage(e) });
    } finally {
      setChecking(false);
    }
  }

  return (
    <div className="py-3">
      <div className="grid grid-cols-[10rem_1fr_1fr_auto] items-center gap-3">
        <div>
          <div className="text-sm font-medium">{label}</div>
        </div>
        <Select
          aria-label={`${label} provider`}
          value={spec?.provider_id ?? ""}
          onChange={(e) => onChange(e.target.value ? { provider_id: e.target.value, model: null } : null)}
        >
          <option value="">Default chain{first ? ` (${shortName(catalog, first.provider_id)} first)` : ""}</option>
          {providers.map((p) => (
            <option key={p.id} value={p.id}>
              {p.display_name}
            </option>
          ))}
        </Select>
        <Select
          aria-label={`${label} model`}
          disabled={!spec}
          value={spec?.model ?? ""}
          onChange={(e) => spec && onChange({ ...spec, model: e.target.value || null })}
        >
          <option value="">Best available (automatic)</option>
          {models?.map((m) => (
            <option key={m.id} value={m.id}>
              {m.id}
              {m.parameter_size ? ` (${m.parameter_size})` : ""}
            </option>
          ))}
        </Select>
        <Button variant="ghost" busy={checking} onClick={() => void runCheck()}>
          Check
        </Button>
      </div>
      <p className="mt-1 text-xs text-muted">{hint}</p>
      {check && (
        <div className="mt-2">
          <Notice tone={check.ok ? "success" : "danger"}>
            <span className="inline-flex items-start gap-2">
              {check.ok ? <CheckCircle2 size={16} className="mt-0.5 shrink-0" /> : <XCircle size={16} className="mt-0.5 shrink-0" />}
              {check.ok && check.provider_id
                ? `Answered by ${displayName(catalog, check.provider_id)} using ${check.model}.`
                : check.message}
            </span>
          </Notice>
        </div>
      )}
    </div>
  );
}

function FallbackChainCard({ ai, update }: { ai: AiSettings; update: (patch: Partial<AiSettings>) => void }) {
  const { data: catalog } = usePresets();
  const readiness = useReadiness();
  const chain = ai.fallback_chain;
  const available = catalog?.llm.filter((p) => !chain.some((r) => r.provider_id === p.id)) ?? [];

  const move = (from: number, to: number) => {
    const next = [...chain];
    const [item] = next.splice(from, 1);
    next.splice(to, 0, item!);
    update({ fallback_chain: next });
  };

  return (
    <Card title="Fallback chain">
      <p className="mb-4 text-sm text-muted">
        Tried in order whenever the chosen model is rate limited, out of daily quota or unreachable. Providers without a key are skipped.
      </p>
      <ol className="flex flex-col gap-2" aria-label="Fallback chain">
        {chain.map((route, i) => {
          const state = readiness(route.provider_id);
          return (
            <li key={`${route.provider_id}-${route.model ?? "auto"}`} className="flex items-center gap-3 rounded-md border border-line bg-surface-2 px-3 py-2">
              <span className="w-5 text-center text-sm tabular-nums text-muted">{i + 1}</span>
              <span className="flex-1 text-sm">
                {displayName(catalog, route.provider_id)}
                {route.model && <span className="text-muted"> · {route.model}</span>}
              </span>
              <Badge tone={state.ready ? "success" : "neutral"}>{state.label}</Badge>
              <Button variant="ghost" aria-label="Move up" disabled={i === 0} onClick={() => move(i, i - 1)}>
                <ArrowUp size={14} />
              </Button>
              <Button variant="ghost" aria-label="Move down" disabled={i === chain.length - 1} onClick={() => move(i, i + 1)}>
                <ArrowDown size={14} />
              </Button>
              <Button
                variant="ghost"
                aria-label={`Remove ${displayName(catalog, route.provider_id)}`}
                onClick={() => update({ fallback_chain: chain.filter((_, j) => j !== i) })}
              >
                <Trash2 size={14} />
              </Button>
            </li>
          );
        })}
      </ol>
      {available.length > 0 && (
        <div className="mt-3 flex items-center gap-2">
          <Plus size={16} className="text-muted" aria-hidden />
          <Select
            aria-label="Add provider to fallback chain"
            className="max-w-xs"
            value=""
            onChange={(e) => e.target.value && update({ fallback_chain: [...chain, { provider_id: e.target.value, model: null }] })}
          >
            <option value="">Add a provider…</option>
            {available.map((p) => (
              <option key={p.id} value={p.id}>
                {p.display_name}
                {p.free ? "" : " (not free)"}
              </option>
            ))}
          </Select>
        </div>
      )}
    </Card>
  );
}

function UsageCard() {
  const { data: allUsage } = useUsageToday();
  const { data: catalog } = usePresets();
  const readiness = useReadiness();
  // Only providers you can use, or that were used today.
  const usage = allUsage?.filter((u) => u.calls > 0 || readiness(u.provider_id).ready);
  return (
    <Card title="Today's usage">
      <p className="mb-4 text-sm text-muted">
        Calls and tokens per provider today, against each free tier's published daily limit (as of {catalog?.updated}).
        Providers change these limits often.
      </p>
      {usage && usage.length > 0 ? (
        <div className="flex flex-col gap-5">
          {usage.map((u) => (
            <div key={u.provider_id} className="flex flex-col gap-2">
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-medium">{u.display_name}</span>
                {u.rate_limited > 0 && (
                  <span className="text-xs text-muted">
                    Rate limited {u.rate_limited} {u.rate_limited === 1 ? "time" : "times"} today
                  </span>
                )}
              </div>
              <Meter label="Requests" value={u.calls} limit={u.rpd} unit="requests" />
              {u.tpd !== null && <Meter label="Tokens" value={u.tokens} limit={u.tpd} unit="tokens" />}
            </div>
          ))}
        </div>
      ) : (
        <p className="text-sm text-muted">No AI calls yet today.</p>
      )}
    </Card>
  );
}
