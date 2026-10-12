// The live project brief beside the interview. Every field can be edited
// directly or sent back to the producer to revisit.
import { AlertTriangle, Lightbulb } from "lucide-react";
import { useState } from "react";

import type { FieldDef } from "../bindings/FieldDef";
import type { InterviewView } from "../bindings/InterviewView";
import type { Suggestion } from "../bindings/Suggestion";
import { errorMessage, ipc } from "../lib/ipc";
import { type FieldDraft, fromDraft, renderValue, STATUS, toDraft } from "../lib/brief";
import { Badge, Button, Dialog, Field, Notice, Select, TextInput } from "./ui";

const FLAG_LABEL = { scope: "Scope", contradiction: "Contradiction", vague: "Too vague" } as const;

export function BriefPanel({ view, onChange }: { view: InterviewView; onChange: (v: InterviewView) => void }) {
  const [editing, setEditing] = useState<FieldDef | null>(null);
  const [dismissed, setDismissed] = useState<string[]>([]);
  const fields = [...view.pack.fields].sort((a, b) => a.priority - b.priority);
  const suggestions = view.suggestions.filter((s) => !dismissed.includes(s.field));

  return (
    <aside className="flex w-[26rem] shrink-0 flex-col border-l border-line bg-surface" aria-label="Brief">
      <div className="border-b border-line px-5 py-3">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted">Brief</h2>
        {view.topics.length > 0 && !view.completeness.complete && (
          <p className="mt-1 text-xs text-muted">Next up: {view.topics.map((t) => t.label).join(", ")}</p>
        )}
        {view.completeness.complete && <p className="mt-1 text-xs text-success">Complete enough to start building.</p>}
      </div>
      <div className="flex-1 overflow-y-auto px-5 py-4">
        {view.flags.length > 0 && (
          <ul className="mb-4 flex flex-col gap-2" aria-label="Producer notes">
            {view.flags.map((flag, i) => (
              <li key={i} className="flex gap-2 rounded-md border border-warning/40 bg-warning/10 px-3 py-2 text-sm">
                <AlertTriangle size={16} className="mt-0.5 shrink-0 text-warning" aria-hidden />
                <span>
                  <span className="font-medium">{FLAG_LABEL[flag.kind]}:</span> {flag.note}
                </span>
              </li>
            ))}
          </ul>
        )}
        <ul className="flex flex-col gap-2">
          {fields.map((def) => {
            const state = view.brief.fields[def.id];
            const status = STATUS[state?.status ?? "empty"];
            const text = renderValue(def, state?.value);
            const suggestion = suggestions.find((s) => s.field === def.id);
            return (
              <li key={def.id}>
                <button
                  type="button"
                  onClick={() => setEditing(def)}
                  className="w-full rounded-md border border-line bg-surface-2/60 px-3 py-2.5 text-left hover:border-accent"
                  aria-label={`${def.label}: ${text || "not discussed yet"}. ${status.label}. Edit`}
                >
                  <span className="flex items-center justify-between gap-2">
                    <span className="text-xs font-medium uppercase tracking-wide text-muted">
                      {def.label}
                      {!def.required && " (optional)"}
                    </span>
                    <Badge tone={status.tone}>{view.revisit === def.id ? "Revisit next" : status.label}</Badge>
                  </span>
                  <span className={`mt-1 block whitespace-pre-wrap wrap-anywhere text-sm ${text ? "" : "text-muted"}`}>{text || "Not discussed yet"}</span>
                </button>
                {suggestion && (
                  <SuggestionRow
                    def={def}
                    suggestion={suggestion}
                    onUse={async () => onChange(await ipc.editBriefField(view.project.id, def.id, suggestion.value))}
                    onDismiss={() => setDismissed((d) => [...d, def.id])}
                  />
                )}
              </li>
            );
          })}
        </ul>
      </div>
      {editing && <FieldDialog view={view} def={editing} onClose={() => setEditing(null)} onChange={onChange} />}
    </aside>
  );
}

function SuggestionRow({ def, suggestion, onUse, onDismiss }: { def: FieldDef; suggestion: Suggestion; onUse: () => Promise<void>; onDismiss: () => void }) {
  return (
    <div className="mt-1 flex items-start gap-2 rounded-md border border-accent/40 bg-accent/10 px-3 py-2 text-sm">
      <Lightbulb size={16} className="mt-0.5 shrink-0 text-accent-strong" aria-hidden />
      <span className="min-w-0 flex-1 wrap-anywhere">
        Producer suggests: {renderValue(def, suggestion.value)}
        <span className="mt-1 flex gap-2">
          <Button variant="ghost" onClick={() => void onUse()}>
            Use it
          </Button>
          <Button variant="ghost" onClick={onDismiss}>
            Keep mine
          </Button>
        </span>
      </span>
    </div>
  );
}

function FieldDialog({
  view,
  def,
  onClose,
  onChange,
}: {
  view: InterviewView;
  def: FieldDef;
  onClose: () => void;
  onChange: (v: InterviewView) => void;
}) {
  const [draft, setDraft] = useState<FieldDraft>(() => toDraft(def, view.brief.fields[def.id]?.value));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function act(action: () => Promise<InterviewView>) {
    setSaving(true);
    setError(null);
    try {
      onChange(await action());
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  }

  const listHint = def.kind === "list" ? `One item per line${def.min_items ? `, ${def.min_items} to ${def.max_items} items` : ""}.` : undefined;

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={def.label}
      description={def.hint}
      footer={
        <>
          <Button variant="ghost" className="mr-auto" disabled={saving} onClick={() => void act(() => ipc.revisitField(view.project.id, def.id))}>
            Ask the producer to revisit
          </Button>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" busy={saving} onClick={() => void act(() => ipc.editBriefField(view.project.id, def.id, fromDraft(def, draft)))}>
            Save
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {(def.kind === "text" || def.kind === "list") && (
          <Field label={def.kind === "list" ? "Items" : "Answer"} hint={listHint}>
            {(id) => (
              <textarea
                id={id}
                rows={def.kind === "list" ? 5 : 3}
                value={draft.text}
                onChange={(e) => setDraft({ ...draft, text: e.target.value })}
                className="w-full rounded-md border border-line bg-surface-2 px-3 py-2 text-sm focus:border-accent focus:outline-none"
              />
            )}
          </Field>
        )}
        {def.kind === "parts" &&
          def.parts.map((part) => (
            <Field key={part.id} label={part.label}>
              {(id) => (
                <TextInput
                  id={id}
                  value={draft.parts[part.id] ?? ""}
                  onChange={(e) => setDraft({ ...draft, parts: { ...draft.parts, [part.id]: e.target.value } })}
                />
              )}
            </Field>
          ))}
        {def.kind === "choice" && (
          <>
            <Field label="Choice">
              {(id) => (
                <Select id={id} value={draft.choice} onChange={(e) => setDraft({ ...draft, choice: e.target.value })}>
                  <option value="">Not decided</option>
                  {def.options.map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.label}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
            <Field label="Why">
              {(id) => <TextInput id={id} value={draft.reason} onChange={(e) => setDraft({ ...draft, reason: e.target.value })} />}
            </Field>
          </>
        )}
        <p className="text-xs text-muted">Your edits are kept as you wrote them; the producer only suggests changes.</p>
        {error && <Notice tone="danger">{error}</Notice>}
      </div>
    </Dialog>
  );
}
