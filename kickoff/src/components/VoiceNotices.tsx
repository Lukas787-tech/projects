// Voice activity from the backend (`voice-status` events): whether the
// producer is speaking, and toasts when a voice engine fails over or a
// budget is nearly used up.
import { isTauri } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { AlertTriangle, Volume2, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import type { VoiceNotice } from "../bindings/VoiceNotice";
import { VOICE_STATUS_EVENT } from "../lib/ipc";

const ENGINE_NAMES: Record<string, string> = { fish_audio: "Fish Audio", piper: "Piper", system: "Windows voices" };
const name = (id: string) => ENGINE_NAMES[id] ?? id;
const TOAST_MS = 8000;

export function describeVoiceNotice(notice: VoiceNotice): string | null {
  switch (notice.type) {
    case "switched":
      return `${name(notice.from)} couldn't speak (${notice.reason}). ${name(notice.to)} takes over.`;
    case "gave_up":
      return `Couldn't read the reply aloud: ${notice.reason}. It's still shown as text.`;
    case "budget":
      return `${name(notice.engine)}: ${notice.percent}% of your monthly limit is used.`;
    case "speaking":
      return null;
  }
}

function useVoiceEvents(handler: (notice: VoiceNotice) => void) {
  const latest = useRef(handler);
  latest.current = handler;
  useEffect(() => {
    if (!isTauri()) return;
    const unlisten = listen<VoiceNotice>(VOICE_STATUS_EVENT, (event) => latest.current(event.payload));
    return () => {
      void unlisten.then((fn) => fn());
    };
  }, []);
}

/** True while the producer's reply is playing. */
export function useSpeaking(): boolean {
  const [speaking, setSpeaking] = useState(false);
  useVoiceEvents((n) => {
    if (n.type === "speaking") setSpeaking(n.active);
  });
  return speaking;
}

export function VoiceToasts() {
  const [toasts, setToasts] = useState<{ id: number; notice: VoiceNotice }[]>([]);
  useVoiceEvents((notice) => {
    if (!describeVoiceNotice(notice)) return;
    const id = Date.now() + Math.random();
    setToasts((t) => [...t.slice(-2), { id, notice }]);
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), TOAST_MS);
  });
  if (toasts.length === 0) return null;
  return (
    <div className="pointer-events-none fixed bottom-4 left-1/2 z-50 flex w-96 -translate-x-1/2 flex-col gap-2" aria-live="polite">
      {toasts.map(({ id, notice }) => (
        <div key={id} className="pointer-events-auto flex items-start gap-3 rounded-lg border border-line bg-surface px-4 py-3 text-sm shadow-lg">
          {notice.type === "budget" || notice.type === "gave_up" ? (
            <AlertTriangle size={16} className="mt-0.5 shrink-0 text-warning" aria-hidden />
          ) : (
            <Volume2 size={16} className="mt-0.5 shrink-0 text-accent-strong" aria-hidden />
          )}
          <span className="flex-1">{describeVoiceNotice(notice)}</span>
          <button
            type="button"
            onClick={() => setToasts((t) => t.filter((x) => x.id !== id))}
            aria-label="Dismiss"
            className="text-muted hover:text-text"
          >
            <X size={14} />
          </button>
        </div>
      ))}
    </div>
  );
}
