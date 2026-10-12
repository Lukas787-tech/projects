// Research and reference triage: one card at a time (← reject, → keep,
// ↑ more like this), the kept board on the right, and brief refinement from
// what the user kept and rejected.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Channel } from "@tauri-apps/api/core";
import {
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  Boxes,
  ExternalLink,
  Gamepad2,
  Image,
  RotateCcw,
  Search,
  Sparkles,
  Undo2,
  X,
} from "lucide-react";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router";

import type { BriefProposal } from "../bindings/BriefProposal";
import type { Reaction } from "../bindings/Reaction";
import type { RefCategory } from "../bindings/RefCategory";
import type { ResearchProgress } from "../bindings/ResearchProgress";
import type { ResearchView } from "../bindings/ResearchView";
import type { SearchStatus } from "../bindings/SearchStatus";
import type { SourceCard } from "../bindings/SourceCard";
import type { StoredRef } from "../bindings/StoredRef";
import { queryKeys } from "../app/queries";
import { Badge, Button, Dialog, EmptyState, Notice, Spinner } from "../components/ui";
import { renderValue } from "../lib/brief";
import { openExternal } from "../lib/external";
import { errorMessage, ipc } from "../lib/ipc";

type Filter = "all" | RefCategory;

const CATEGORY: Record<RefCategory, { label: string; plural: string; icon: typeof Gamepad2 }> = {
  comparable: { label: "Comparable game", plural: "Games", icon: Gamepad2 },
  visual: { label: "Mood reference", plural: "Mood", icon: Image },
  asset: { label: "Asset", plural: "Assets", icon: Boxes },
};

const isKept = (r: StoredRef) => r.reaction === "keep" || r.reaction === "more_like_this";

export function ResearchPage() {
  const { projectId = "" } = useParams();
  const client = useQueryClient();
  const key = queryKeys.research(projectId);
  const { data: view, error: loadError } = useQuery({ queryKey: key, queryFn: () => ipc.getResearch(projectId) });
  const [progress, setProgress] = useState<ResearchProgress | null>(null);
  const [running, setRunning] = useState(false);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>("all");
  const [history, setHistory] = useState<string[]>([]);
  const [refineOpen, setRefineOpen] = useState(false);

  const setRef = (updated: StoredRef) =>
    client.setQueryData<ResearchView>(key, (v) => v && { ...v, refs: v.refs.map((r) => (r.id === updated.id ? updated : r)) });

  async function research(seed?: StoredRef) {
    const channel = new Channel<ResearchProgress>();
    channel.onmessage = setProgress;
    setRunning(true);
    setError(null);
    setNotice(null);
    setWarnings([]);
    if (seed) setRef({ ...seed, reaction: "more_like_this" });
    try {
      const run = seed ? await ipc.moreLikeThis(projectId, seed.id, channel) : await ipc.runResearch(projectId, channel);
      setWarnings(run.warnings);
      if (run.added.length === 0) setNotice("Nothing new this time. Everything found was already here.");
      await client.invalidateQueries({ queryKey: key });
    } catch (e) {
      setError(errorMessage(e));
      await client.invalidateQueries({ queryKey: key });
    } finally {
      setRunning(false);
      setProgress(null);
    }
  }

  async function react(item: StoredRef, reaction: Reaction | null) {
    setError(null);
    setRef({ ...item, reaction });
    if (reaction) setHistory((h) => [...h, item.id]);
    try {
      setRef(await ipc.reactToReference(projectId, item.id, reaction));
    } catch (e) {
      setRef(item);
      setError(errorMessage(e));
    }
  }

  function undo() {
    const last = history.at(-1);
    const item = view?.refs.find((r) => r.id === last);
    setHistory((h) => h.slice(0, -1));
    if (item) void react(item, null);
  }

  const refs = view?.refs ?? [];
  const inbox = refs.filter((r) => r.reaction === null && (filter === "all" || r.category === filter));
  const current = inbox[0] ?? null;

  // ← reject, → keep, ↑ more like this, Ctrl+Z undo.
  const keys = useRef({ current, running, refineOpen, react, research, undo });
  keys.current = { current, running, refineOpen, react, research, undo };
  useEffect(() => {
    const down = (e: KeyboardEvent) => {
      const k = keys.current;
      const target = e.target as HTMLElement | null;
      if (k.refineOpen || e.altKey || target?.closest("input, textarea, select, [contenteditable=true]")) return;
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "z") {
        e.preventDefault();
        k.undo();
        return;
      }
      if (!k.current || e.ctrlKey || e.metaKey) return;
      if (e.key === "ArrowLeft") void k.react(k.current, "reject");
      else if (e.key === "ArrowRight") void k.react(k.current, "keep");
      else if (e.key === "ArrowUp" && !k.running) void k.research(k.current);
      else return;
      e.preventDefault();
    };
    window.addEventListener("keydown", down);
    return () => window.removeEventListener("keydown", down);
  }, []);

  if (loadError) {
    return (
      <div className="p-8">
        <Notice tone="danger">{errorMessage(loadError)}</Notice>
      </div>
    );
  }
  if (!view) {
    return (
      <div className="p-8">
        <Spinner label="Loading research…" />
      </div>
    );
  }

  const reacted = refs.some((r) => r.reaction !== null);
  const sourceOf = (r: StoredRef) => view.sources.find((s) => s.id === r.source_id);

  return (
    <div className="flex h-full flex-col">
      <header className="flex items-center gap-4 border-b border-line px-6 py-3">
        <Link
          to={`/project/${projectId}`}
          className="rounded p-1 text-muted hover:bg-surface-2 hover:text-text"
          aria-label="Back to the interview"
        >
          <ArrowLeft size={18} />
        </Link>
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-base font-semibold">{view.project.name}: research</h1>
          <SearchLine search={view.search} />
        </div>
        <Button onClick={() => setRefineOpen(true)} disabled={!reacted || running}>
          <Sparkles size={16} /> Refine brief
        </Button>
        {refs.length > 0 && (
          <Button onClick={() => void research()} disabled={running}>
            <Search size={16} /> Find more
          </Button>
        )}
      </header>

      <div className="space-y-2 px-6 pt-4 empty:hidden">
        {!view.search.active && <NoSearchNotice view={view} />}
        {running && <ProgressLine progress={progress} />}
        {error && <Notice tone="danger">{error}</Notice>}
        {notice && <Notice>{notice}</Notice>}
        {warnings.map((w) => (
          <Notice key={w} tone="warning">
            {w}
          </Notice>
        ))}
      </div>

      <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_minmax(260px,340px)]">
        <section aria-label="References to review" className="min-h-0 overflow-y-auto px-6 py-4">
          <FilterBar refs={refs} filter={filter} onChange={setFilter} />
          {refs.length === 0 ? (
            <EmptyState icon={<Search size={28} />} title="No references yet">
              Find comparable games, mood references and free assets for this brief. Each one comes with its license checked.
              <div className="mt-4">
                <Button variant="primary" onClick={() => void research()} busy={running}>
                  <Search size={16} /> Find references
                </Button>
              </div>
            </EmptyState>
          ) : current ? (
            <div className="mx-auto max-w-2xl">
              <RefCard key={current.id} projectId={projectId} item={current} source={sourceOf(current)} />
              <div className="mt-4 flex items-center justify-center gap-3">
                <Button onClick={() => void react(current, "reject")} aria-keyshortcuts="ArrowLeft">
                  <ArrowLeft size={16} /> Reject
                </Button>
                <Button onClick={() => void research(current)} disabled={running} aria-keyshortcuts="ArrowUp">
                  <ArrowUp size={16} /> More like this
                </Button>
                <Button variant="primary" onClick={() => void react(current, "keep")} aria-keyshortcuts="ArrowRight">
                  Keep <ArrowRight size={16} />
                </Button>
              </div>
              <p className="mt-3 text-center text-xs text-muted">
                {inbox.length - 1 > 0 ? `${inbox.length - 1} more to review. ` : "Last one. "}
                Keys: ← reject, → keep, ↑ more like this, Ctrl+Z undo.
                {history.length > 0 && (
                  <button type="button" className="ml-2 inline-flex items-center gap-1 text-accent hover:underline" onClick={undo}>
                    <Undo2 size={12} /> Undo
                  </button>
                )}
              </p>
            </div>
          ) : (
            <EmptyState icon={<Sparkles size={28} />} title="All reviewed">
              {filter === "all" ? "Find more references, or refine the brief from what you kept." : "Nothing left in this group."}
              {history.length > 0 && (
                <div className="mt-4">
                  <Button onClick={undo}>
                    <Undo2 size={16} /> Undo last
                  </Button>
                </div>
              )}
            </EmptyState>
          )}
        </section>
        <KeptBoard projectId={projectId} refs={refs} onRestore={(r) => void react(r, null)} />
      </div>

      <RefineDialog open={refineOpen} onOpenChange={setRefineOpen} view={view} projectId={projectId} />
    </div>
  );
}

function SearchLine({ search }: { search: SearchStatus }) {
  const active = search.active;
  if (!active) return <p className="mt-0.5 text-xs text-muted">Web search: off</p>;
  const limit = active.monthly_free_units;
  return (
    <p className="mt-0.5 text-xs text-muted">
      Web search: {active.display_name}
      {limit !== null && ` · ${active.used_this_month.toLocaleString()} of ${limit.toLocaleString()} ${active.unit ?? "search"}s used this month`}
    </p>
  );
}

function NoSearchNotice({ view }: { view: ResearchView }) {
  const free = view.search.options.find((o) => !o.card_required);
  const skipped = ["mood references", ...view.sources.filter((s) => s.needs_search).map((s) => s.display_name)];
  return (
    <Notice tone="warning">
      No web search key. Comparable games come from the AI's memory and are marked <strong>Unverified</strong>;{" "}
      {new Intl.ListFormat("en", { type: "conjunction" }).format(skipped)} are skipped.
      {free && (
        <>
          {" "}
          {free.display_name} is free ({free.monthly_free_units?.toLocaleString()} searches a month, no card).{" "}
          <Link to="/keys" className="font-medium text-accent hover:underline">
            Add a key
          </Link>
        </>
      )}
    </Notice>
  );
}

function ProgressLine({ progress }: { progress: ResearchProgress | null }) {
  let text = "Starting…";
  if (progress?.kind === "planning") text = "Planning searches from your brief…";
  else if (progress?.kind === "searching") text = `Searching… ${progress.done} of ${progress.total}`;
  else if (progress?.kind === "waiting") text = `${progress.source} is rate limited, retrying in ${progress.seconds}s…`;
  return (
    <div role="status" aria-live="polite">
      <Spinner label={text} />
    </div>
  );
}

function FilterBar({ refs, filter, onChange }: { refs: StoredRef[]; filter: Filter; onChange: (f: Filter) => void }) {
  if (refs.length === 0) return null;
  const waiting = (c?: RefCategory) => refs.filter((r) => r.reaction === null && (!c || r.category === c)).length;
  const options: Array<[Filter, string, number]> = [
    ["all", "All", waiting()],
    ...(Object.keys(CATEGORY) as RefCategory[]).map((c): [Filter, string, number] => [c, CATEGORY[c].plural, waiting(c)]),
  ];
  return (
    <div className="mb-4 flex gap-1" role="group" aria-label="Show">
      {options.map(([id, label, count]) => (
        <button
          key={id}
          type="button"
          aria-pressed={filter === id}
          onClick={() => onChange(id)}
          className={`rounded-md px-2.5 py-1 text-sm ${filter === id ? "bg-surface-2 font-medium text-text" : "text-muted hover:text-text"}`}
        >
          {label} <span className="text-xs text-muted">{count}</span>
        </button>
      ))}
    </div>
  );
}

function Thumbnail({ projectId, item, size }: { projectId: string; item: StoredRef; size: "large" | "small" }) {
  const { data } = useQuery({
    queryKey: queryKeys.thumbnail(projectId, item.id),
    queryFn: () => ipc.referenceThumbnail(projectId, item.id),
    enabled: item.thumbnail_url !== null,
    staleTime: Infinity,
  });
  const Icon = CATEGORY[item.category].icon;
  const box = size === "large" ? "h-48 w-full" : "h-10 w-10 shrink-0";
  if (data) return <img src={data} alt="" className={`${box} rounded-md bg-surface-2 object-cover`} />;
  return (
    <div className={`${box} flex items-center justify-center rounded-md bg-surface-2 text-muted`} aria-hidden>
      <Icon size={size === "large" ? 32 : 16} />
    </div>
  );
}

function LicenseBadge({ item }: { item: StoredRef }) {
  if (item.classification === "usable") return <Badge tone="success">Usable · {item.license_name ?? item.license_id}</Badge>;
  if (item.classification === "unknown") return <Badge tone="warning">License unknown</Badge>;
  return <Badge>Reference only</Badge>;
}

const hostname = (url: string) => {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return url;
  }
};

const text = (v: unknown) => (typeof v === "string" && v.trim() ? v : null);

function RefCard({ projectId, item, source }: { projectId: string; item: StoredRef; source?: SourceCard }) {
  const category = CATEGORY[item.category];
  const learn = text(item.metadata.learn);
  const avoid = text(item.metadata.avoid);
  const note = text(item.metadata.note);
  return (
    <article aria-label={item.title} className="overflow-hidden rounded-lg border border-line bg-surface">
      <Thumbnail projectId={projectId} item={item} size="large" />
      <div className="space-y-3 p-5">
        <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
          <span>
            {category.label}
            {item.asset_kind && ` · ${item.asset_kind}`}
          </span>
          <LicenseBadge item={item} />
          {!item.verified && (
            <span title="Suggested by the AI from memory; not checked online.">
              <Badge tone="warning">Unverified</Badge>
            </span>
          )}
        </div>
        <h2 className="text-lg font-semibold wrap-anywhere">{item.title}</h2>
        {item.source_url && (
          <button
            type="button"
            onClick={() => void openExternal(item.source_url!)}
            className="inline-flex items-center gap-1 text-sm text-accent hover:underline"
          >
            {source?.display_name ?? hostname(item.source_url)} <ExternalLink size={12} />
          </button>
        )}
        {item.summary && <p className="text-sm wrap-anywhere">{item.summary}</p>}
        {(learn || avoid) && (
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
            {learn && (
              <>
                <dt className="font-medium">Learn</dt>
                <dd>{learn}</dd>
              </>
            )}
            {avoid && (
              <>
                <dt className="font-medium">Avoid</dt>
                <dd>{avoid}</dd>
              </>
            )}
          </dl>
        )}
        {note && <p className="text-sm text-warning">{note}</p>}
        {item.category === "asset" && <AssetLicense item={item} />}
        {source?.ui_credit && <p className="text-xs text-muted">{source.ui_credit}</p>}
      </div>
    </article>
  );
}

function AssetLicense({ item }: { item: StoredRef }) {
  const license = item.license_name ?? item.license_id;
  return (
    <div className="rounded-md bg-surface-2 p-3 text-sm">
      {item.author && <p>By {item.author}</p>}
      {item.classification === "usable" ? (
        <>
          <p>
            License:{" "}
            {item.license_url ? (
              <button type="button" className="text-accent hover:underline" onClick={() => void openExternal(item.license_url!)}>
                {license}
              </button>
            ) : (
              license
            )}
          </p>
          {item.attribution && <p className="mt-1 text-xs text-muted wrap-anywhere">Credit: {item.attribution}</p>}
        </>
      ) : item.classification === "unknown" ? (
        <p>No license we can rely on. Check the page before using it; until then it counts as a reference only.</p>
      ) : (
        <p>{license ? `${license}: ` : ""}this license doesn't allow use in your game (for example, non-commercial only). Keep it as a reference.</p>
      )}
    </div>
  );
}

function KeptBoard({ projectId, refs, onRestore }: { projectId: string; refs: StoredRef[]; onRestore: (r: StoredRef) => void }) {
  const kept = refs.filter(isKept);
  const rejected = refs.filter((r) => r.reaction === "reject");
  return (
    <aside aria-label="Kept references" className="min-h-0 overflow-y-auto border-l border-line px-4 py-4">
      <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-muted">Kept ({kept.length})</h2>
      {kept.length === 0 && <p className="text-sm text-muted">Keep references to collect them here.</p>}
      {(Object.keys(CATEGORY) as RefCategory[]).map((c) => {
        const items = kept.filter((r) => r.category === c);
        if (!items.length) return null;
        return (
          <section key={c} className="mb-4" aria-label={`Kept ${CATEGORY[c].plural.toLowerCase()}`}>
            <h3 className="mb-2 text-xs font-medium text-muted">{CATEGORY[c].plural}</h3>
            <ul className="space-y-2">
              {items.map((r) => (
                <SmallRef key={r.id} projectId={projectId} item={r} action={<RemoveButton item={r} onClick={() => onRestore(r)} />} />
              ))}
            </ul>
          </section>
        );
      })}
      {rejected.length > 0 && (
        <details className="mt-6">
          <summary className="cursor-pointer text-sm text-muted">Rejected ({rejected.length})</summary>
          <ul className="mt-2 space-y-2">
            {rejected.map((r) => (
              <SmallRef
                key={r.id}
                projectId={projectId}
                item={r}
                action={
                  <button
                    type="button"
                    className="rounded p-1 text-muted hover:bg-surface-2 hover:text-text"
                    aria-label={`Review ${r.title} again`}
                    onClick={() => onRestore(r)}
                  >
                    <RotateCcw size={14} />
                  </button>
                }
              />
            ))}
          </ul>
        </details>
      )}
    </aside>
  );
}

function RemoveButton({ item, onClick }: { item: StoredRef; onClick: () => void }) {
  return (
    <button type="button" className="rounded p-1 text-muted hover:bg-surface-2 hover:text-text" aria-label={`Unkeep ${item.title}`} onClick={onClick}>
      <X size={14} />
    </button>
  );
}

function SmallRef({ projectId, item, action }: { projectId: string; item: StoredRef; action: ReactNode }) {
  return (
    <li className="flex items-center gap-2">
      <Thumbnail projectId={projectId} item={item} size="small" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm" title={item.title}>
          {item.title}
        </p>
        <div className="flex gap-1">
          <LicenseBadge item={item} />
          {!item.verified && <Badge tone="warning">Unverified</Badge>}
        </div>
      </div>
      {action}
    </li>
  );
}

function RefineDialog({
  open,
  onOpenChange,
  view,
  projectId,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  view: ResearchView;
  projectId: string;
}) {
  const client = useQueryClient();
  const [proposals, setProposals] = useState<BriefProposal[] | null>(null);
  const [applied, setApplied] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | "load" | null>(null);

  useEffect(() => {
    if (!open) return;
    setProposals(null);
    setApplied(new Set());
    setError(null);
    setBusy("load");
    ipc
      .refineBriefFromReferences(projectId)
      .then(setProposals)
      .catch((e) => setError(errorMessage(e)))
      .finally(() => setBusy(null));
  }, [open, projectId]);

  async function apply(index: number, p: BriefProposal) {
    setBusy(index);
    setError(null);
    try {
      await ipc.editBriefField(projectId, p.field, p.value);
      setApplied((a) => new Set(a).add(index));
      await client.invalidateQueries({ queryKey: queryKeys.research(projectId) });
      await client.invalidateQueries({ queryKey: queryKeys.interview(projectId) });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title="Refine the brief"
      description="Suggestions from the references you kept and rejected. Apply the ones that fit."
      footer={<Button onClick={() => onOpenChange(false)}>Done</Button>}
    >
      {busy === "load" && <Spinner label="Looking at your reactions…" />}
      {error && <Notice tone="danger">{error}</Notice>}
      {proposals?.length === 0 && <p className="text-sm text-muted">No changes suggested: the brief already matches what you kept.</p>}
      <ul className="space-y-4">
        {proposals?.map((p, i) => {
          const def = view.pack.fields.find((f) => f.id === p.field);
          const current = def ? renderValue(def, view.brief.fields[p.field]?.value) : "";
          const proposed = Array.isArray(p.value) ? p.value.join("; ") : p.value;
          return (
            <li key={`${p.field}-${i}`} className="rounded-md border border-line p-3" aria-label={def?.label ?? p.field}>
              <h3 className="text-sm font-semibold">{def?.label ?? p.field}</h3>
              <p className="mt-1 text-xs text-muted">{p.reason}</p>
              <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
                <dt className="text-muted">Now</dt>
                <dd className="wrap-anywhere">{current || <span className="text-muted">(empty)</span>}</dd>
                <dt className="text-muted">New</dt>
                <dd className="wrap-anywhere">{proposed}</dd>
              </dl>
              <div className="mt-2 flex justify-end">
                {applied.has(i) ? (
                  <Badge tone="success">Applied</Badge>
                ) : (
                  <Button variant="primary" busy={busy === i} disabled={busy !== null} onClick={() => void apply(i, p)}>
                    Apply
                  </Button>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </Dialog>
  );
}
