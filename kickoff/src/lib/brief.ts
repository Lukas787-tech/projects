// Presenting brief fields. Mirrors `render_value` in crates/core/src/brief.rs.
import type { FieldDef } from "../bindings/FieldDef";
import type { FieldStatus } from "../bindings/FieldStatus";

type Parts = Record<string, string | undefined>;
type Choice = { choice?: string | null; reason?: string | null };

export function renderValue(def: FieldDef, value: unknown): string {
  if (value === null || value === undefined) return "";
  switch (def.kind) {
    case "text":
      return typeof value === "string" ? value : "";
    case "list":
      return Array.isArray(value) ? value.join("; ") : "";
    case "parts":
      return def.parts
        .map((p) => ((value as Parts)[p.id] ? `${p.label}: ${(value as Parts)[p.id]}` : null))
        .filter(Boolean)
        .join("\n");
    case "choice": {
      const v = value as Choice;
      const label = def.options.find((o) => o.id === v.choice)?.label;
      if (!label) return "";
      return v.reason ? `${label} (${v.reason})` : label;
    }
  }
}

export const STATUS: Record<FieldStatus, { label: string; tone: "neutral" | "warning" | "success" | "accent" }> = {
  empty: { label: "Not yet", tone: "neutral" },
  draft: { label: "Tentative", tone: "warning" },
  confirmed: { label: "Settled", tone: "success" },
  user_edited: { label: "Edited by you", tone: "accent" },
};

/** Editable form state for a field, and back to the value the backend expects. */
export type FieldDraft = { text: string; parts: Record<string, string>; choice: string; reason: string };

export function toDraft(def: FieldDef, value: unknown): FieldDraft {
  const draft: FieldDraft = { text: "", parts: {}, choice: "", reason: "" };
  if (value === null || value === undefined) return draft;
  if (def.kind === "text" && typeof value === "string") draft.text = value;
  if (def.kind === "list" && Array.isArray(value)) draft.text = value.join("\n");
  if (def.kind === "parts") for (const p of def.parts) draft.parts[p.id] = (value as Parts)[p.id] ?? "";
  if (def.kind === "choice") {
    draft.choice = (value as Choice).choice ?? "";
    draft.reason = (value as Choice).reason ?? "";
  }
  return draft;
}

export function fromDraft(def: FieldDef, draft: FieldDraft): unknown {
  switch (def.kind) {
    case "text":
      return draft.text.trim() || null;
    case "list": {
      const items = draft.text.split("\n").map((s) => s.trim()).filter(Boolean);
      return items.length ? items : null;
    }
    case "parts": {
      const entries = def.parts.map((p) => [p.id, (draft.parts[p.id] ?? "").trim()] as const).filter(([, v]) => v);
      return entries.length ? Object.fromEntries(entries) : null;
    }
    case "choice":
      return draft.choice ? { choice: draft.choice, reason: draft.reason.trim() || null } : null;
  }
}
