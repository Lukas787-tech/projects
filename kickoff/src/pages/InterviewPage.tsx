// The producer interview: conversation on the left, the live brief on the right.
import { useQueryClient } from "@tanstack/react-query";
import { Channel } from "@tauri-apps/api/core";
import { ArrowLeft, ArrowRight, RotateCcw, Send, Square, Volume2, VolumeX } from "lucide-react";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router";

import type { InterviewEvent } from "../bindings/InterviewEvent";
import type { InterviewView } from "../bindings/InterviewView";
import type { Transcript } from "../bindings/Transcript";
import type { VoiceOverview } from "../bindings/VoiceOverview";
import { queryKeys, useInterview, useSaveVoiceSettings, useVoiceOverview } from "../app/queries";
import { BriefPanel } from "../components/BriefPanel";
import { MicButton, useRecorder } from "../components/VoiceInput";
import { useSpeaking } from "../components/VoiceNotices";
import { Button, Dialog, Notice, Progress, Spinner } from "../components/ui";
import { errorMessage, ipc } from "../lib/ipc";

interface Turn {
  /** The user's message, shown before the backend has saved it. */
  pending: string | null;
  /** The producer's reply as it streams in. */
  streamed: string;
}

export function InterviewPage() {
  const { projectId = "" } = useParams();
  const location = useLocation();
  const client = useQueryClient();
  const { data: view, error: loadError } = useInterview(projectId);
  const [turn, setTurn] = useState<Turn | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [voiceError, setVoiceError] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const startedIdea = useRef(false);
  const busy = turn !== null;
  const { data: voice } = useVoiceOverview();
  const voiceOn = voice?.settings.enabled ?? false;
  const speaking = useSpeaking();

  const onTranscript = (t: Transcript) => {
    const text = t.text.trim();
    if (!text) setVoiceError("I didn't catch anything. Try again, a little closer to the microphone.");
    else if (voice?.settings.review_transcript) setDraft(text);
    else void run(text, "voice");
  };
  const recorder = useRecorder({ onTranscript, onError: setVoiceError });

  // Hold F2 to talk; Esc cancels a recording or interrupts the producer.
  const keys = useRef({ recorder, voiceOn, busy, handsFree: false });
  keys.current = { recorder, voiceOn, busy, handsFree: voice?.settings.hands_free ?? false };
  useEffect(() => {
    const down = (e: KeyboardEvent) => {
      const k = keys.current;
      if (e.key === "F2" && !e.repeat && k.voiceOn && !k.busy) {
        e.preventDefault();
        void k.recorder.start();
      } else if (e.key === "Escape") {
        if (k.recorder.isActive()) void k.recorder.cancel();
        else void ipc.stopSpeaking();
      }
    };
    const up = (e: KeyboardEvent) => {
      if (e.key === "F2" && !keys.current.handsFree) void keys.current.recorder.stop();
    };
    window.addEventListener("keydown", down);
    window.addEventListener("keyup", up);
    return () => {
      window.removeEventListener("keydown", down);
      window.removeEventListener("keyup", up);
    };
  }, []);

  const setView = (v: InterviewView) => client.setQueryData(queryKeys.interview(projectId), v);

  /** Run a turn (or a retry) and stream its events into the screen. */
  async function run(text: string | null, mode: "text" | "voice" = "text") {
    setError(null);
    setNotice(null);
    setTurn({ pending: text, streamed: "" });
    const channel = new Channel<InterviewEvent>();
    channel.onmessage = (event) => {
      switch (event.type) {
        case "delta":
          setTurn((t) => (t ? { ...t, streamed: t.streamed + event.text } : t));
          break;
        case "brief_updated":
          client.setQueryData<InterviewView>(queryKeys.interview(projectId), (v) =>
            v ? { ...v, brief: event.brief, completeness: event.completeness } : v,
          );
          break;
        case "producer_failed":
          setError(event.message);
          break;
        case "extraction_failed":
          setNotice("The brief couldn't be updated from that answer. You can fill in the field yourself on the right.");
          break;
        case "producer_done":
          break;
      }
    };
    try {
      setView(text === null ? await ipc.continueInterview(projectId, channel) : await ipc.sendMessage(projectId, text, mode, channel));
      void client.invalidateQueries({ queryKey: queryKeys.projects });
    } catch (e) {
      setError(errorMessage(e));
      void client.invalidateQueries({ queryKey: queryKeys.interview(projectId) });
    } finally {
      setTurn(null);
    }
  }

  // A project created from the Projects page starts with its one-line idea.
  useEffect(() => {
    const idea = (location.state as { idea?: string } | null)?.idea;
    if (view && idea && view.messages.length === 0 && !startedIdea.current) {
      startedIdea.current = true;
      void run(idea);
    }
  }, [view, location.state]);

  if (loadError) {
    return (
      <div className="p-8">
        <Notice tone="danger">{errorMessage(loadError)}</Notice>
      </div>
    );
  }
  if (!view) return <div className="p-8"><Spinner label="Loading…" /></div>;

  return (
    <div className="flex h-full">
      <section className="flex min-w-0 flex-1 flex-col" aria-label="Interview">
        <Header view={view} onFinished={setView} voice={voice} />
        <Conversation view={view} turn={turn} />
        <div className="border-t border-line px-6 py-3">
          {error && (
            <div className="mb-3">
              <Notice tone="danger">
                <span className="flex flex-wrap items-center justify-between gap-2">
                  <span>{error}</span>
                  {view.awaiting_reply && !busy && (
                    <Button onClick={() => void run(null)}>
                      <RotateCcw size={14} /> Try again
                    </Button>
                  )}
                </span>
              </Notice>
            </div>
          )}
          {!error && view.awaiting_reply && !busy && (
            <div className="mb-3">
              <Notice>
                <span className="flex items-center justify-between gap-2">
                  The producer hasn't answered your last message yet.
                  <Button onClick={() => void run(null)}>Get a reply</Button>
                </span>
              </Notice>
            </div>
          )}
          {/* A retry re-reads the answer into the brief, so the error covers both. */}
          {notice && !error && (
            <div className="mb-3">
              <Notice tone="warning">{notice}</Notice>
            </div>
          )}
          {voiceError && (
            <div className="mb-3">
              <Notice tone="warning">{voiceError}</Notice>
            </div>
          )}
          {speaking && (
            <div className="mx-auto mb-3 flex max-w-2xl items-center justify-between gap-2 rounded-md bg-accent/10 px-3 py-2 text-sm" role="status">
              <span className="inline-flex items-center gap-2">
                <Volume2 size={16} className="text-accent-strong" aria-hidden /> The producer is speaking.
              </span>
              <Button variant="ghost" onClick={() => void ipc.stopSpeaking()}>
                <Square size={12} /> Stop (Esc)
              </Button>
            </div>
          )}
          <Composer
            value={draft}
            onChange={setDraft}
            disabled={busy}
            onSend={(text) => void run(text)}
            empty={view.messages.length === 0}
            mic={voiceOn ? <MicButton recorder={recorder} handsFree={voice?.settings.hands_free ?? false} disabled={busy} /> : null}
          />
        </div>
      </section>
      <BriefPanel view={view} onChange={setView} />
    </div>
  );
}

function Header({ view, onFinished, voice }: { view: InterviewView; onFinished: (v: InterviewView) => void; voice?: VoiceOverview }) {
  const [confirm, setConfirm] = useState(false);
  const [finishing, setFinishing] = useState(false);
  const navigate = useNavigate();
  const { completeness, project } = view;
  const required = view.pack.fields.filter((f) => f.required).length;
  const settled = Math.round(completeness.ratio * required);
  const labels = (ids: string[]) => ids.map((id) => view.pack.fields.find((f) => f.id === id)?.label ?? id);

  async function finish() {
    setFinishing(true);
    try {
      onFinished(await ipc.finishInterview(project.id));
      setConfirm(false);
      void navigate(`/project/${project.id}/research`);
    } finally {
      setFinishing(false);
    }
  }

  return (
    <header className="flex items-center gap-4 border-b border-line px-6 py-3">
      <Link to="/" className="rounded p-1 text-muted hover:bg-surface-2 hover:text-text" aria-label="Back to projects">
        <ArrowLeft size={18} />
      </Link>
      <div className="min-w-0 flex-1">
        <h1 className="truncate text-base font-semibold">{project.name}</h1>
        <div className="mt-1 flex items-center gap-3">
          <div className="w-40">
            <Progress value={completeness.ratio} label="Brief progress" />
          </div>
          <span className="text-xs text-muted">
            {settled} of {required} settled
          </span>
        </div>
      </div>
      {voice?.settings.enabled && <MuteToggle voice={voice} />}
      {project.stage === "interview" ? (
        <Button variant={completeness.complete ? "primary" : "secondary"} onClick={() => (completeness.complete ? void finish() : setConfirm(true))} busy={finishing}>
          Continue to research <ArrowRight size={16} />
        </Button>
      ) : (
        <Link
          to={`/project/${project.id}/research`}
          className="inline-flex items-center gap-2 rounded-md border border-line bg-surface-2 px-3 py-1.5 text-sm font-medium hover:border-accent"
        >
          Research <ArrowRight size={16} />
        </Link>
      )}
      <Dialog
        open={confirm}
        onOpenChange={setConfirm}
        title="Move on with an incomplete brief?"
        description="You can keep refining the brief later; the starter kit will mark gaps as open questions."
        footer={
          <>
            <Button onClick={() => setConfirm(false)}>Keep talking</Button>
            <Button variant="primary" busy={finishing} onClick={() => void finish()}>
              Continue anyway
            </Button>
          </>
        }
      >
        <div className="text-sm">
          {completeness.missing.length > 0 && <p>Not discussed yet: {labels(completeness.missing).join(", ")}.</p>}
          {completeness.weak.length > 0 && <p className="mt-2">Still tentative: {labels(completeness.weak).join(", ")}.</p>}
        </div>
      </Dialog>
    </header>
  );
}

function Conversation({ view, turn }: { view: InterviewView; turn: Turn | null }) {
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => {
    end.current?.scrollIntoView?.({ block: "end" });
  }, [view.messages.length, turn?.streamed, turn?.pending]);

  return (
    <div className="flex-1 overflow-y-auto px-6 py-5" aria-live="polite">
      {view.messages.length === 0 && !turn && (
        <div className="mx-auto mt-16 max-w-md text-center text-muted">
          <p className="text-base">Describe your game in one sentence to start.</p>
          <p className="mt-2 text-sm">The producer asks one question at a time and fills in the brief on the right as you talk.</p>
        </div>
      )}
      <ol className="mx-auto flex max-w-2xl flex-col gap-4" aria-label="Messages">
        {view.messages.map((m) => (
          <Bubble key={m.id} who={m.role === "user" ? "user" : "producer"} text={m.content} />
        ))}
        {turn?.pending && <Bubble who="user" text={turn.pending} />}
        {turn && (turn.streamed ? <Bubble who="producer" text={turn.streamed} streaming /> : <li><Spinner label="The producer is thinking…" /></li>)}
      </ol>
      <div ref={end} />
    </div>
  );
}

function Bubble({ who, text, streaming = false }: { who: "user" | "producer"; text: string; streaming?: boolean }) {
  const mine = who === "user";
  return (
    <li className={`flex flex-col ${mine ? "items-end" : "items-start"}`}>
      <span className="mb-1 text-xs text-muted">{mine ? "You" : "Producer"}</span>
      <div
        className={`max-w-[85%] whitespace-pre-wrap wrap-anywhere rounded-lg px-4 py-2.5 text-sm leading-relaxed ${
          mine ? "bg-accent/15" : "bg-surface-2"
        }`}
      >
        {text}
        {streaming && <span className="ml-0.5 inline-block h-4 w-1.5 translate-y-0.5 animate-pulse bg-accent" aria-hidden />}
      </div>
    </li>
  );
}

function MuteToggle({ voice }: { voice: VoiceOverview }) {
  const save = useSaveVoiceSettings();
  const on = voice.settings.speak_replies;
  return (
    <Button
      variant="ghost"
      aria-label="Read replies aloud"
      aria-pressed={on}
      title={on ? "Replies are read aloud" : "Replies are text only"}
      onClick={() => {
        if (on) void ipc.stopSpeaking();
        save.mutate({ ...voice.settings, speak_replies: !on });
      }}
    >
      {on ? <Volume2 size={18} /> : <VolumeX size={18} />}
    </Button>
  );
}

function Composer({
  value,
  onChange,
  disabled,
  onSend,
  empty,
  mic,
}: {
  value: string;
  onChange: (text: string) => void;
  disabled: boolean;
  onSend: (text: string) => void;
  empty: boolean;
  mic: ReactNode;
}) {
  const send = () => {
    const text = value.trim();
    if (!text || disabled) return;
    onChange("");
    onSend(text);
  };
  return (
    <div className="mx-auto flex max-w-2xl items-end gap-2">
      <textarea
        aria-label="Your answer"
        rows={2}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            send();
          }
        }}
        placeholder={empty ? "Describe your game in one sentence…" : "Answer the producer… (Enter to send, Shift+Enter for a new line)"}
        className="min-h-[2.75rem] flex-1 resize-none rounded-md border border-line bg-surface-2 px-3 py-2 text-sm placeholder:text-muted focus:border-accent focus:outline-none"
      />
      {mic}
      <Button variant="primary" onClick={send} disabled={disabled || !value.trim()} aria-label="Send">
        <Send size={16} />
      </Button>
    </div>
  );
}
