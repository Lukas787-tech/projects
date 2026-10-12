// Talking to the producer: the microphone button, its level meter, and the
// recording state machine (idle → starting → recording → transcribing).
import { Channel } from "@tauri-apps/api/core";
import { Loader2, Mic, Square, X } from "lucide-react";
import { useRef, useState } from "react";

import type { RecordingEvent } from "../bindings/RecordingEvent";
import type { Transcript } from "../bindings/Transcript";
import { errorMessage, ipc } from "../lib/ipc";
import { Button } from "./ui";

export type RecorderState = "idle" | "starting" | "recording" | "transcribing";

export function useRecorder({ onTranscript, onError }: { onTranscript: (t: Transcript) => void; onError: (message: string | null) => void }) {
  const [state, setStateValue] = useState<RecorderState>("idle");
  const [level, setLevel] = useState(0);
  const current = useRef<RecorderState>("idle");
  // Hands-free can hear the end of speech before start() has returned.
  const stopWhenStarted = useRef(false);

  const setState = (s: RecorderState) => {
    current.current = s;
    setStateValue(s);
  };

  async function stop() {
    if (current.current === "starting") {
      stopWhenStarted.current = true;
      return;
    }
    if (current.current !== "recording") return;
    setState("transcribing");
    setLevel(0);
    try {
      onTranscript(await ipc.stopRecording());
    } catch (e) {
      onError(errorMessage(e));
    } finally {
      setState("idle");
    }
  }

  async function start() {
    if (current.current !== "idle") return;
    onError(null);
    stopWhenStarted.current = false;
    setState("starting");
    const channel = new Channel<RecordingEvent>();
    channel.onmessage = (event) => {
      if (event.type === "level") setLevel(event.level);
      else void stop();
    };
    try {
      await ipc.startRecording(channel);
      setState("recording");
      if (stopWhenStarted.current) void stop();
    } catch (e) {
      setState("idle");
      onError(errorMessage(e));
    }
  }

  async function cancel() {
    if (current.current !== "recording" && current.current !== "starting") return;
    setState("idle");
    setLevel(0);
    await ipc.cancelRecording().catch(() => undefined);
  }

  return { state, level, start, stop, cancel, isActive: () => current.current !== "idle" };
}

/** Speech is quiet in RMS terms; a square root makes the meter lively. */
function meterWidth(level: number) {
  return `${Math.round(Math.min(1, Math.sqrt(level) * 1.6) * 100)}%`;
}

export function MicButton({
  recorder,
  handsFree,
  disabled,
}: {
  recorder: ReturnType<typeof useRecorder>;
  handsFree: boolean;
  disabled: boolean;
}) {
  const { state, level } = recorder;
  if (state === "transcribing" || state === "starting") {
    return (
      <span className="inline-flex h-9 items-center gap-2 px-2 text-sm text-muted" role="status">
        <Loader2 size={16} className="animate-spin" aria-hidden />
        {state === "transcribing" ? "Transcribing…" : "Starting…"}
      </span>
    );
  }
  if (state === "recording") {
    return (
      <div className="flex items-center gap-2">
        <div className="flex w-28 items-center gap-2" role="status" aria-label="Listening">
          <span className="h-2 w-2 shrink-0 animate-pulse rounded-full bg-danger" aria-hidden />
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-danger/15">
            <div className="h-full rounded-full bg-danger transition-[width] duration-75" style={{ width: meterWidth(level) }} />
          </div>
        </div>
        <Button variant="ghost" aria-label="Cancel recording" onClick={() => void recorder.cancel()}>
          <X size={16} />
        </Button>
        <Button variant="danger" aria-label="Stop and send" onClick={() => void recorder.stop()}>
          <Square size={14} /> {handsFree ? "Done" : "Send"}
        </Button>
      </div>
    );
  }
  return (
    <Button
      aria-label="Talk"
      title={handsFree ? "Click and talk; it stops when you pause (or hold F2)" : "Click to talk, click again to send (or hold F2)"}
      disabled={disabled}
      onClick={() => void recorder.start()}
    >
      <Mic size={16} />
    </Button>
  );
}
