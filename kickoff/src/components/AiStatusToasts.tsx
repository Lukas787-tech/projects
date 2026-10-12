// Shows router activity (rate limits, retries, fallbacks) as short toasts, so
// a pause on a free tier never looks like a hang.
import { isTauri } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { Clock, Shuffle, X } from "lucide-react";
import { useEffect, useState } from "react";

import type { AiStatus } from "../bindings/AiStatus";
import type { PresetCatalog } from "../bindings/PresetCatalog";
import { usePresets } from "../app/queries";
import { AI_STATUS_EVENT } from "../lib/ipc";
import { displayName } from "../lib/providers";

interface Toast {
  id: number;
  status: AiStatus;
  /** For waits: when the wait ends (ms epoch), to show a countdown. */
  until: number | null;
}

const TOAST_MS = 8000;
const MAX_TOASTS = 3;

export function describe(status: AiStatus, catalog: PresetCatalog | undefined, secondsLeft?: number): string | null {
  switch (status.type) {
    case "waiting": {
      const name = displayName(catalog, status.provider_id);
      const s = secondsLeft ?? status.seconds;
      if (status.reason === "rate_limited") return `${name} is rate limited. Retrying in ${s} s.`;
      if (status.reason === "throttled") return `Pacing requests to stay within ${name}'s free limit (${s} s).`;
      return `Connection problem with ${name}. Retrying in ${s} s.`;
    }
    case "fell_back":
      return `Switched from ${displayName(catalog, status.from_provider)} to ${displayName(catalog, status.to_provider)}: ${status.reason}`;
    case "failed":
      // Ordinary failures are followed by a fallback toast; only flag a bad key.
      return status.error_kind === "auth"
        ? `${displayName(catalog, status.provider_id)} rejected its key. Check it in the Key Library.`
        : null;
  }
}

/** Pure toast list logic, separate from the event subscription for testing. */
export function useToastQueue() {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = (status: AiStatus) => {
    const id = Date.now() + Math.random();
    const until = status.type === "waiting" ? Date.now() + status.seconds * 1000 : null;
    setToasts((t) => [...t.slice(-(MAX_TOASTS - 1)), { id, status, until }]);
    const lifetime = until ? Math.max(TOAST_MS, until - Date.now() + 1000) : TOAST_MS;
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), lifetime);
  };
  const dismiss = (id: number) => setToasts((t) => t.filter((x) => x.id !== id));
  return { toasts, push, dismiss };
}

export function AiStatusToasts() {
  const { data: catalog } = usePresets();
  const { toasts, push, dismiss } = useToastQueue();
  const [, tick] = useState(0);

  useEffect(() => {
    if (!isTauri()) return;
    const unlisten = listen<AiStatus>(AI_STATUS_EVENT, (event) => push(event.payload));
    return () => {
      void unlisten.then((fn) => fn());
    };
  }, []);

  // Re-render once a second while a countdown is visible.
  useEffect(() => {
    if (!toasts.some((t) => t.until)) return;
    const timer = window.setInterval(() => tick((n) => n + 1), 1000);
    return () => window.clearInterval(timer);
  }, [toasts]);

  const visible = toasts
    .map((t) => {
      const left = t.until ? Math.max(0, Math.ceil((t.until - Date.now()) / 1000)) : undefined;
      return { ...t, text: describe(t.status, catalog, left) };
    })
    .filter((t) => t.text);

  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-96 flex-col gap-2" aria-live="polite">
      {visible.map((t) => (
        <div key={t.id} className="pointer-events-auto flex items-start gap-3 rounded-lg border border-line bg-surface px-4 py-3 text-sm shadow-lg">
          {t.status.type === "fell_back" ? (
            <Shuffle size={16} className="mt-0.5 shrink-0 text-accent-strong" aria-hidden />
          ) : (
            <Clock size={16} className="mt-0.5 shrink-0 text-warning" aria-hidden />
          )}
          <span className="flex-1">{t.text}</span>
          <button type="button" onClick={() => dismiss(t.id)} aria-label="Dismiss" className="text-muted hover:text-text">
            <X size={14} />
          </button>
        </div>
      ))}
    </div>
  );
}
