import { invoke } from "@tauri-apps/api/core";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { App } from "../app/App";
import type { InterviewView } from "../bindings/InterviewView";
import type { VoiceOverview } from "../bindings/VoiceOverview";
import type { VoiceSettings } from "../bindings/VoiceSettings";
import { installDevMocks } from "../lib/devMocks";
import type { MockProjectSeed } from "../lib/interviewMocks";
import { MOCK_TRANSCRIPT } from "../lib/voiceMocks";

const seed: MockProjectSeed = {
  answers: { pitch: "A lighthouse game" },
  messages: [
    ["user", "A lighthouse game"],
    ["producer", "Love it. What should we call it?"],
  ],
};

function open(voice: { whisperModels?: string[]; voiceSettings?: Partial<VoiceSettings> } = { whisperModels: ["base"] }) {
  installDevMocks({ settings: { onboarding_done: true }, projects: [seed], ...voice });
  render(<App initialPath="/project/project-1" />);
}

const messages = () => screen.getByRole("list", { name: "Messages" });
const lastUserMessage = async () => {
  const view = await invoke<InterviewView>("get_interview", { project_id: "project-1" });
  return view.messages.filter((m) => m.role === "user").at(-1);
};

describe("talking to the producer", () => {
  it("records, transcribes and sends the answer as a voice message", async () => {
    open();
    await userEvent.click(await screen.findByRole("button", { name: "Talk" }));
    expect(await screen.findByRole("status", { name: "Listening" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Stop and send" }));
    expect(await within(messages()).findByText(MOCK_TRANSCRIPT)).toBeInTheDocument();
    await waitFor(async () => expect((await lastUserMessage())?.input_mode).toBe("voice"));
  });

  it("puts the transcript in the box to review when asked", async () => {
    open({ whisperModels: ["base"], voiceSettings: { review_transcript: true } });
    await userEvent.click(await screen.findByRole("button", { name: "Talk" }));
    await userEvent.click(await screen.findByRole("button", { name: "Stop and send" }));
    await waitFor(() => expect(screen.getByLabelText("Your answer")).toHaveValue(MOCK_TRANSCRIPT));
    expect(within(messages()).queryByText(MOCK_TRANSCRIPT)).not.toBeInTheDocument();
  });

  it("stops by itself in hands-free mode", async () => {
    open({ whisperModels: ["base"], voiceSettings: { hands_free: true } });
    await userEvent.click(await screen.findByRole("button", { name: "Talk" }));
    expect(await within(messages()).findByText(MOCK_TRANSCRIPT)).toBeInTheDocument();
  });

  it("holds F2 to talk", async () => {
    open();
    await screen.findByRole("button", { name: "Talk" });
    fireEvent.keyDown(window, { key: "F2" });
    expect(await screen.findByRole("status", { name: "Listening" })).toBeInTheDocument();
    fireEvent.keyUp(window, { key: "F2" });
    expect(await within(messages()).findByText(MOCK_TRANSCRIPT)).toBeInTheDocument();
  });

  it("Esc cancels a recording without sending", async () => {
    open();
    await userEvent.click(await screen.findByRole("button", { name: "Talk" }));
    await screen.findByRole("status", { name: "Listening" });
    fireEvent.keyDown(window, { key: "Escape" });
    expect(await screen.findByRole("button", { name: "Talk" })).toBeInTheDocument();
    expect(within(messages()).queryByText(MOCK_TRANSCRIPT)).not.toBeInTheDocument();
  });

  it("says what to do when the speech model is missing", async () => {
    open({ whisperModels: [] });
    await userEvent.click(await screen.findByRole("button", { name: "Talk" }));
    expect(await screen.findByText(/Download the base speech model/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Talk" })).toBeInTheDocument();
  });

  it("mutes and unmutes spoken replies", async () => {
    open();
    const toggle = await screen.findByRole("button", { name: "Read replies aloud" });
    expect(toggle).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(toggle);
    await waitFor(() => expect(screen.getByRole("button", { name: "Read replies aloud" })).toHaveAttribute("aria-pressed", "false"));
    expect((await invoke<VoiceOverview>("get_voice_overview")).settings.speak_replies).toBe(false);
  });

  it("text only mode has no microphone", async () => {
    open({ voiceSettings: { enabled: false } });
    await screen.findByLabelText("Your answer");
    expect(screen.queryByRole("button", { name: "Talk" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Read replies aloud" })).not.toBeInTheDocument();
  });
});
