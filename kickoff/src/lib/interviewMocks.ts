// In-memory interview commands for the browser preview and tests. A scripted
// "producer" asks about the next open field and a scripted "extractor" files
// each answer under the field that was just asked about, so the screens behave
// like the real loop. The pack is the real one, exported from Rust by `cargo test`.
import type { Channel } from "@tauri-apps/api/core";

import type { Brief } from "../bindings/Brief";
import type { Completeness } from "../bindings/Completeness";
import type { FieldDef } from "../bindings/FieldDef";
import type { FieldState } from "../bindings/FieldState";
import gamePack from "../bindings/fixtures/gamePack.json";
import type { Flag } from "../bindings/Flag";
import type { Focus } from "../bindings/Focus";
import type { InterviewEvent } from "../bindings/InterviewEvent";
import type { InterviewView } from "../bindings/InterviewView";
import type { PackManifest } from "../bindings/PackManifest";
import type { Project } from "../bindings/Project";
import type { ProjectCard } from "../bindings/ProjectCard";
import type { Stage } from "../bindings/Stage";
import type { StoredMessage } from "../bindings/StoredMessage";
import type { Suggestion } from "../bindings/Suggestion";

export const GAME_PACK = gamePack as PackManifest;

/** A message with this text makes the producer fail, for exercising retries. */
export const MOCK_PRODUCER_FAILS = "make the producer fail";
/** A message with this text makes the extractor fail. */
export const MOCK_EXTRACTOR_FAILS = "make the extractor fail";

/** A project to start the mocks with. `answers` become settled brief fields. */
export interface MockProjectSeed {
  stage?: Stage;
  answers?: Record<string, unknown>;
  messages?: Array<["user" | "producer", string]>;
  flags?: Flag[];
}

export interface InterviewMockOptions {
  projects?: MockProjectSeed[];
  /** Pause between streamed words, so the preview looks like a real reply. */
  streamDelayMs?: number;
}

interface MockProject {
  project: Project;
  messages: StoredMessage[];
  brief: Brief;
  flags: Flag[];
  revisit: string | null;
}

const UNTITLED = "Untitled idea";
const MODEL = { provider_id: "gemini", model: "gemini-best-model" };
const pause = (ms: number) => (ms > 0 ? new Promise((r) => setTimeout(r, ms)) : Promise.resolve());

const emptyState = (): FieldState => ({ value: null, confidence: 0, part_confidence: {}, status: "empty", updated_turn: null });

function isEmpty(def: FieldDef, value: unknown): boolean {
  if (value === null || value === undefined) return true;
  switch (def.kind) {
    case "text":
      return typeof value !== "string" || !value.trim();
    case "list":
      return !Array.isArray(value) || value.length === 0;
    case "parts":
      return def.parts.every((p) => !(value as Record<string, unknown>)[p.id]);
    case "choice":
      return !(value as { choice?: unknown }).choice;
  }
}

function isSettled(def: FieldDef, state: FieldState | undefined): boolean {
  if (!state || isEmpty(def, state.value)) return false;
  if (def.kind === "list" && (state.value as unknown[]).length < def.min_items) return false;
  return state.status === "user_edited" || state.confidence >= GAME_PACK.confidence_threshold;
}

function completeness(brief: Brief): Completeness {
  const required = byPriority().filter((f) => f.required);
  const missing = required.filter((f) => isEmpty(f, brief.fields[f.id]?.value)).map((f) => f.id);
  const weak = required.filter((f) => !missing.includes(f.id) && !isSettled(f, brief.fields[f.id])).map((f) => f.id);
  const settled = required.length - missing.length - weak.length;
  return { ratio: required.length ? settled / required.length : 1, complete: !missing.length && !weak.length, missing, weak };
}

function byPriority(): FieldDef[] {
  return [...GAME_PACK.fields].sort((a, b) => a.priority - b.priority);
}

/** The next fields worth exploring, like `Brief::open_topics`. */
function topics(brief: Brief, revisit: string | null, n = 2): Focus[] {
  const c = completeness(brief);
  const optional = byPriority()
    .filter((f) => !f.required && isEmpty(f, brief.fields[f.id]?.value))
    .map((f) => f.id);
  const order: Array<[string, Focus["reason"]]> = [
    ...(revisit ? [[revisit, "revisit"] as [string, Focus["reason"]]] : []),
    ...c.missing.map((id) => [id, "missing"] as [string, Focus["reason"]]),
    ...c.weak.map((id) => [id, "weak"] as [string, Focus["reason"]]),
    ...optional.map((id) => [id, "optional"] as [string, Focus["reason"]]),
  ];
  const seen = new Set<string>();
  const out: Focus[] = [];
  for (const [id, reason] of order) {
    const def = GAME_PACK.fields.find((f) => f.id === id);
    if (!def || seen.has(id)) continue;
    seen.add(id);
    out.push({ field: id, label: def.label, hint: def.hint, reason });
    if (out.length === n) break;
  }
  return out;
}

/** Turn an answer into a value for `def`, as the extractor would. */
function extract(def: FieldDef, text: string): unknown {
  switch (def.kind) {
    case "text":
      return text;
    case "list":
      return text
        .split(/[,;\n]/)
        .map((s) => s.trim())
        .filter(Boolean)
        .slice(0, def.max_items);
    case "parts":
      return Object.fromEntries(def.parts.map((p) => [p.id, text]));
    case "choice": {
      const lower = text.toLowerCase();
      const option = def.options.find((o) => lower.includes(o.id) || lower.includes(o.label.toLowerCase()));
      return option ? { choice: option.id, reason: null } : null;
    }
  }
}

function newBrief(): Brief {
  return { schema: GAME_PACK.brief_schema, fields: Object.fromEntries(GAME_PACK.fields.map((f) => [f.id, emptyState()])) };
}

export function interviewHandlers(options: InterviewMockOptions = {}) {
  const projects = new Map<string, MockProject>();
  const delay = options.streamDelayMs ?? 0;
  let nextProject = 1;
  let nextMessage = 1;

  const now = () => new Date().toISOString();
  const find = (id: unknown): MockProject => {
    const p = projects.get(id as string);
    if (!p) throw { code: "not_found", message: "That project no longer exists." };
    return p;
  };
  const touch = (p: MockProject) => {
    const title = p.brief.fields.working_title?.value;
    const engine = (p.brief.fields.engine?.value as { choice?: string } | null)?.choice ?? null;
    p.project = { ...p.project, name: typeof title === "string" && title ? title : UNTITLED, engine, updated_at: now() };
  };
  const say = (p: MockProject, role: "user" | "producer", content: string, input_mode: string | null = null) => {
    p.messages.push({
      id: nextMessage++,
      role,
      content,
      input_mode,
      provider_id: role === "producer" ? MODEL.provider_id : null,
      model: role === "producer" ? MODEL.model : null,
      created_at: now(),
    });
  };
  const view = (p: MockProject, suggestions: Suggestion[] = []): InterviewView => ({
    project: p.project,
    pack: GAME_PACK,
    messages: [...p.messages],
    brief: structuredClone(p.brief),
    completeness: completeness(p.brief),
    topics: topics(p.brief, p.revisit),
    flags: [...p.flags],
    revisit: p.revisit,
    suggestions,
    awaiting_reply: p.messages.at(-1)?.role === "user",
  });

  function create(seed: MockProjectSeed = {}): MockProject {
    const id = `project-${nextProject++}`;
    const p: MockProject = {
      project: {
        id,
        name: UNTITLED,
        pack_id: GAME_PACK.id,
        pack_version: GAME_PACK.version,
        stage: seed.stage ?? "interview",
        engine: null,
        folder_path: null,
        created_at: now(),
        updated_at: now(),
      },
      messages: [],
      brief: newBrief(),
      flags: seed.flags ?? [],
      revisit: null,
    };
    for (const [field, value] of Object.entries(seed.answers ?? {})) {
      p.brief.fields[field] = { value, confidence: 0.9, part_confidence: {}, status: "confirmed", updated_turn: 1 };
    }
    for (const [role, content] of seed.messages ?? []) say(p, role, content, role === "user" ? "text" : null);
    touch(p);
    projects.set(id, p);
    return p;
  }
  for (const seed of options.projects ?? []) create(seed);

  /** The producer's next question, streamed word by word. */
  async function reply(p: MockProject, emit: (e: InterviewEvent) => void) {
    const next = topics(p.brief, p.revisit, 1)[0];
    const text = completeness(p.brief).complete
      ? "That's enough for a solid first brief. Continue to research when you're ready, or keep refining anything on the right."
      : `Got it. Next, ${next!.label.toLowerCase()}: ${next!.hint}`;
    const words = text.split(/(?<= )/);
    for (const word of words) {
      emit({ type: "delta", text: word });
      await pause(delay);
    }
    say(p, "producer", text);
    p.revisit = null;
    emit({ type: "producer_done", ...MODEL });
  }

  return {
    create_project: () => create().project,
    list_projects: (): ProjectCard[] =>
      [...projects.values()]
        .sort((a, b) => b.project.updated_at.localeCompare(a.project.updated_at))
        .map((p) => ({
          project: p.project,
          pitch: (p.brief.fields.pitch?.value as string | null) ?? null,
          completeness: completeness(p.brief).ratio,
        })),
    delete_project: (a: Record<string, unknown>) => {
      projects.delete(a.project_id as string);
      return null;
    },
    get_interview: (a: Record<string, unknown>) => view(find(a.project_id)),

    send_message: async (a: Record<string, unknown>) => {
      const p = find(a.project_id);
      const channel = a.on_event as Channel<InterviewEvent>;
      const emit = (e: InterviewEvent) => channel.onmessage(e);
      const text = (a.text as string).trim();
      if (!text) throw { code: "invalid", message: "Type an answer first." };

      // The field the producer just asked about; a first message is the pitch.
      const asked = p.messages.length === 0 ? "pitch" : topics(p.brief, p.revisit, 1)[0]?.field;
      say(p, "user", text, a.input_mode as string);

      const suggestions: Suggestion[] = [];
      const extract_ = () => {
        if (text === MOCK_EXTRACTOR_FAILS) {
          emit({ type: "extraction_failed", message: "The model's answer didn't match the brief schema." });
          return;
        }
        const def = GAME_PACK.fields.find((f) => f.id === asked);
        const value = def ? extract(def, text) : null;
        if (!def || value === null) {
          if (def) p.flags = [{ kind: "vague", field: def.id, note: `That doesn't settle the ${def.label.toLowerCase()} yet.` }];
          return;
        }
        const state = p.brief.fields[def.id] ?? emptyState();
        if (state.status === "user_edited") {
          suggestions.push({ field: def.id, value });
          return;
        }
        const confidence = text.length >= 12 ? 0.9 : 0.5;
        p.brief.fields[def.id] = {
          value,
          confidence,
          part_confidence: {},
          status: confidence >= GAME_PACK.confidence_threshold ? "confirmed" : "draft",
          updated_turn: p.messages.length,
        };
        p.flags = [];
        touch(p);
        emit({ type: "brief_updated", changed: [def.id], brief: structuredClone(p.brief), completeness: completeness(p.brief) });
      };

      // The real producer runs alongside the extractor and looks one topic
      // ahead; extracting first gives the same result here.
      extract_();
      if (text === MOCK_PRODUCER_FAILS) {
        emit({ type: "producer_failed", message: "Every provider in the chain failed: gemini is rate limited for today." });
      } else {
        await reply(p, emit);
      }
      touch(p);
      return view(p, suggestions);
    },

    continue_interview: async (a: Record<string, unknown>) => {
      const p = find(a.project_id);
      if (p.messages.at(-1)?.role !== "user") throw { code: "invalid", message: "There's no message to reply to." };
      const channel = a.on_event as Channel<InterviewEvent>;
      await reply(p, (e) => channel.onmessage(e));
      touch(p);
      return view(p);
    },

    edit_brief_field: (a: Record<string, unknown>) => {
      const p = find(a.project_id);
      const field = a.field as string;
      if (!GAME_PACK.fields.some((f) => f.id === field)) throw { code: "invalid", message: `unknown brief field "${field}"` };
      p.brief.fields[field] =
        a.value === null ? emptyState() : { value: a.value, confidence: 1, part_confidence: {}, status: "user_edited", updated_turn: null };
      touch(p);
      return view(p);
    },
    revisit_field: (a: Record<string, unknown>) => {
      const p = find(a.project_id);
      p.revisit = a.field as string;
      return view(p);
    },
    finish_interview: (a: Record<string, unknown>) => {
      const p = find(a.project_id);
      p.project = { ...p.project, stage: "research", updated_at: now() };
      return view(p);
    },
  } satisfies Record<string, (a: Record<string, unknown>) => unknown>;
}
