// In-memory research commands for the browser preview and tests. Results are
// scripted but follow the real rules: comparables are unverified without a
// search key, mood references and link-only sites need one, and licenses are
// classified the way the backend does it.
import type { Channel } from "@tauri-apps/api/core";

import type { BriefProposal } from "../bindings/BriefProposal";
import type { InterviewView } from "../bindings/InterviewView";
import type { Reaction } from "../bindings/Reaction";
import type { ResearchProgress } from "../bindings/ResearchProgress";
import type { ResearchRun } from "../bindings/ResearchRun";
import type { ResearchView } from "../bindings/ResearchView";
import type { SearchProviderInfo } from "../bindings/SearchProviderInfo";
import type { SourceCard } from "../bindings/SourceCard";
import type { StoredRef } from "../bindings/StoredRef";

export interface ResearchMockOptions {
  /** References a project starts with, by project index. */
  refs?: Record<number, Array<Partial<StoredRef> & Pick<StoredRef, "title" | "category">>>;
  /** Make the next research run fail with this message. */
  researchFails?: string;
  /** Pause between progress steps. */
  streamDelayMs?: number;
}

type Handlers = Record<string, (a: Record<string, unknown>) => unknown>;

const pause = (ms: number) => (ms > 0 ? new Promise((r) => setTimeout(r, ms)) : Promise.resolve());

export const MOCK_SOURCES: SourceCard[] = [
  { id: "polyhaven", display_name: "Poly Haven", needs_search: false, kinds: ["texture", "hdri", "model"], site_license: "CC0 1.0", ui_credit: "Powered by Poly Haven" },
  { id: "sketchfab", display_name: "Sketchfab", needs_search: false, kinds: ["model"], site_license: null, ui_credit: null },
  { id: "kenney", display_name: "Kenney", needs_search: true, kinds: ["sprite", "model", "audio"], site_license: "CC0 1.0", ui_credit: null },
  { id: "opengameart", display_name: "OpenGameArt", needs_search: true, kinds: ["sprite", "audio"], site_license: null, ui_credit: null },
];

const SEARCH_OPTIONS: SearchProviderInfo[] = [
  { id: "tavily", display_name: "Tavily", signup_url: "https://app.tavily.com", card_required: false, monthly_free_units: 1000, unit: "credit", used_this_month: 0 },
  { id: "brave", display_name: "Brave Search", signup_url: "https://api-dashboard.search.brave.com", card_required: true, monthly_free_units: 1000, unit: "request", used_this_month: 0 },
];

const CC0 = { license_id: "CC0-1.0", license_name: "CC0 1.0", license_url: "https://creativecommons.org/publicdomain/zero/1.0/" };
const CC_BY = { license_id: "CC-BY-4.0", license_name: "CC BY 4.0", license_url: "https://creativecommons.org/licenses/by/4.0/" };

/** A tiny coloured square, standing in for a downloaded thumbnail. */
export function mockThumbnail(seed: string): string {
  const hue = [...seed].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="hsl(${hue},45%,45%)"/></svg>`;
  return `data:image/svg+xml;base64,${btoa(svg)}`;
}

function blank(): StoredRef {
  return {
    id: "",
    category: "visual",
    asset_kind: null,
    title: "",
    summary: null,
    source_id: null,
    source_url: null,
    author: null,
    classification: "reference_only",
    license_id: null,
    license_name: null,
    license_url: null,
    attribution: null,
    thumbnail_url: null,
    origin: "search",
    verified: true,
    metadata: {},
    reaction: null,
    created_at: new Date().toISOString(),
  };
}

/** What a full run finds, with or without a web search key. */
function fullRun(searching: boolean): Array<Partial<StoredRef>> {
  const game = (title: string, slug: string, summary: string, learn: string, avoid: string): Partial<StoredRef> => ({
    category: "comparable",
    title,
    summary,
    source_id: searching ? "tavily" : null,
    source_url: searching ? `https://store.example.com/${slug}` : null,
    thumbnail_url: searching ? `https://img.example.com/${slug}.jpg` : null,
    origin: searching ? "search" : "llm_knowledge",
    verified: searching,
    metadata: { learn, avoid },
  });
  const games = [
    game("Stardew Valley", "stardew", "A cozy farming loop with seasons.", "Seasons give each week a goal.", "Its huge content scope."),
    game("Don't Starve", "dont-starve", "Survival with a strong art style.", "A tight hunger clock.", "Punishing restarts for new players."),
  ];
  const visuals: Array<Partial<StoredRef>> = searching
    ? [
        { category: "visual", title: "Moody lighthouse concept art", summary: "Cold blues and warm windows.", source_id: "tavily", source_url: "https://art.example.com/lighthouse", thumbnail_url: "https://img.example.com/lighthouse.jpg" },
        { category: "visual", title: "Winter coast palette", summary: null, source_id: "tavily", source_url: "https://img.example.com/coast.png", thumbnail_url: "https://img.example.com/coast.png" },
      ]
    : [];
  const assets: Array<Partial<StoredRef>> = [
    {
      category: "asset", asset_kind: "texture", title: "Snow 02", source_id: "polyhaven", source_url: "https://polyhaven.com/a/snow_02",
      author: "Rob Tuytel", classification: "usable", ...CC0, origin: "asset_api", thumbnail_url: "https://cdn.example.com/snow.png",
      attribution: "\"Snow 02\" by Rob Tuytel (https://polyhaven.com/a/snow_02), CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/) (credit not required)",
    },
    {
      category: "asset", asset_kind: "model", title: "Old Lighthouse", source_id: "sketchfab", source_url: "https://sketchfab.com/3d-models/old-lighthouse",
      author: "Mara K", classification: "usable", ...CC_BY, origin: "asset_api", thumbnail_url: "https://media.example.com/lighthouse.jpg",
      attribution: "\"Old Lighthouse\" by Mara K (https://sketchfab.com/3d-models/old-lighthouse), CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/)",
    },
    {
      category: "asset", asset_kind: "model", title: "Fishing Boat", source_id: "sketchfab", source_url: "https://sketchfab.com/3d-models/fishing-boat",
      author: "Sven", classification: "reference_only", license_name: "CC Attribution-NonCommercial", origin: "asset_api",
    },
  ];
  if (searching) {
    assets.push({
      category: "asset", asset_kind: "audio", title: "Winter Ambience", source_id: "opengameart", source_url: "https://opengameart.org/content/winter-ambience",
      classification: "unknown", origin: "search",
    });
  }
  return [...games, ...visuals, ...assets];
}

export function researchHandlers(interview: Handlers, hasKey: (provider: string) => boolean, options: ResearchMockOptions = {}): Handlers {
  const refs = new Map<string, StoredRef[]>();
  let nextId = 1;
  let failNext = options.researchFails ?? null;

  const call = (name: string, args: Record<string, unknown>) => {
    const handler = interview[name];
    if (!handler) throw new Error(`missing interview mock ${name}`);
    return handler(args);
  };
  const make = (r: Partial<StoredRef>): StoredRef => ({ ...blank(), ...r, id: `ref-${nextId++}` });
  const list = (projectId: string) => {
    if (!refs.has(projectId)) refs.set(projectId, []);
    return refs.get(projectId)!;
  };
  const interviewView = (projectId: unknown) => call("get_interview", { project_id: projectId }) as InterviewView;
  const active = () => SEARCH_OPTIONS.find((o) => hasKey(o.id)) ?? null;

  // Seed refs for projects that were created from options.
  const seeded = (call("list_projects", {}) as Array<{ project: { id: string } }>).map((c) => c.project.id).reverse();
  for (const [index, items] of Object.entries(options.refs ?? {})) {
    const id = seeded[Number(index)];
    if (id) list(id).push(...items.map(make));
  }

  const view = (projectId: unknown): ResearchView => {
    const iv = interviewView(projectId);
    return {
      project: iv.project,
      pack: iv.pack,
      brief: iv.brief,
      refs: list(iv.project.id),
      search: { active: active(), options: SEARCH_OPTIONS },
      sources: MOCK_SOURCES,
    };
  };

  const key = (r: StoredRef) => (r.category === "comparable" ? `title:${r.title.toLowerCase()}` : `url:${r.source_url}`);

  async function run(projectId: string, found: Array<Partial<StoredRef>>, channel: Channel<ResearchProgress>): Promise<ResearchRun> {
    const send = (p: ResearchProgress) => channel.onmessage(p);
    send({ kind: "planning" });
    await pause(options.streamDelayMs ?? 0);
    if (failNext) {
      const message = failNext;
      failNext = null;
      throw { code: "provider", message };
    }
    const existing = new Set(list(projectId).map(key));
    const total = 3;
    for (let done = 0; done <= total; done++) {
      send({ kind: "searching", done, total });
      await pause(options.streamDelayMs ?? 0);
    }
    const added = found.map(make).filter((r) => {
      if (existing.has(key(r))) return false;
      existing.add(key(r));
      return true;
    });
    list(projectId).push(...added);
    const iv = interviewView(projectId);
    if (iv.project.stage === "interview") call("finish_interview", { project_id: projectId });
    return { added, warnings: [] };
  }

  const find = (projectId: unknown, refId: unknown) => {
    const r = list(projectId as string).find((x) => x.id === refId);
    if (!r) throw { code: "not_found", message: "project or reference not found" };
    return r;
  };

  return {
    get_research: (a) => view(a.project_id),
    run_research: (a) => run(a.project_id as string, fullRun(active() !== null), a.on_progress as Channel<ResearchProgress>),
    more_like_this: (a) => {
      const seed = find(a.project_id, a.ref_id);
      seed.reaction = "more_like_this";
      const similar = [1, 2].map((n) => ({
        ...seed,
        title: `${seed.title}, similar ${n}`,
        source_url: seed.source_url ? `${seed.source_url}-similar-${n}` : null,
        reaction: null,
      }));
      return run(a.project_id as string, similar, a.on_progress as Channel<ResearchProgress>);
    },
    react_to_reference: (a) => {
      const r = find(a.project_id, a.ref_id);
      r.reaction = (a.reaction as Reaction | null) ?? null;
      return { ...r };
    },
    reference_thumbnail: (a) => {
      const r = find(a.project_id, a.ref_id);
      return r.thumbnail_url ? mockThumbnail(r.title) : null;
    },
    refine_brief_from_references: (a): BriefProposal[] => {
      const all = list(a.project_id as string);
      const kept = all.filter((r) => r.reaction === "keep" || r.reaction === "more_like_this");
      const rejected = all.filter((r) => r.reaction === "reject");
      if (!kept.length && !rejected.length) throw { code: "invalid", message: "react to a few references first" };
      const proposals: BriefProposal[] = [];
      const looks = kept.filter((r) => r.category !== "comparable");
      if (looks.length) {
        proposals.push({
          field: "art_direction",
          value: `Inspired by ${looks.map((r) => r.title).join(" and ")}`,
          reason: `You kept ${looks.length === 1 ? "this reference" : "these references"}.`,
        });
      }
      const games = rejected.filter((r) => r.category === "comparable");
      if (games.length) {
        proposals.push({ field: "out_of_scope", value: games.map((g) => `Anything like ${g.title}`), reason: "You rejected these games." });
      }
      return proposals;
    },
  };
}
