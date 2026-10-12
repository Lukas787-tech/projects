// Helpers for presenting provider presets.
import type { KeyKind } from "../bindings/KeyKind";
import type { PresetCatalog } from "../bindings/PresetCatalog";
import type { ProviderSummary } from "../bindings/ProviderSummary";

export const KIND_LABELS: Record<KeyKind, string> = {
  llm: "AI model",
  stt: "Speech to text",
  tts: "Voice",
  search: "Web search",
  asset: "Asset source",
  analytics: "Analytics",
  other: "Other",
};

export function allProviders(catalog: PresetCatalog): ProviderSummary[] {
  return [...catalog.llm, ...catalog.tts, ...catalog.stt, ...catalog.search];
}

export function findProvider(catalog: PresetCatalog | undefined, id: string | null | undefined) {
  if (!catalog || !id) return undefined;
  return allProviders(catalog).find((p) => p.id === id);
}

/** Short, honest label for what using a provider costs. */
export function accessLabel(p: ProviderSummary): { text: string; tone: "success" | "warning" | "neutral" } {
  switch (p.access) {
    case "local":
      return { text: "Free · runs locally", tone: "success" };
    case "free":
      return { text: p.card_required ? "Free tier · card required" : "Free tier · no card", tone: "success" };
    case "free_promo":
      return { text: "Free (promotion, may end)", tone: "warning" };
    case "free_credit":
      return { text: "Free monthly credit · card required", tone: "warning" };
    case "trial":
      return { text: "Trial only · card required", tone: "warning" };
    default:
      return { text: "Paid", tone: "neutral" };
  }
}

export function displayName(catalog: PresetCatalog | undefined, id: string) {
  return findProvider(catalog, id)?.display_name ?? id;
}

/** Name without the parenthetical qualifier: "Ollama (local)" -> "Ollama". */
export function shortName(catalog: PresetCatalog | undefined, id: string) {
  return displayName(catalog, id).replace(/\s*\([^)]*\)\s*$/, "");
}
