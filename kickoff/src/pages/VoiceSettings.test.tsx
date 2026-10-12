import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { App } from "../app/App";
import type { KeyEntry } from "../bindings/KeyEntry";
import type { VoiceOverview } from "../bindings/VoiceOverview";
import { installDevMocks, type MockOptions } from "../lib/devMocks";

const fishKey: KeyEntry = {
  id: "key-fish",
  label: "Fish Audio",
  kind: "tts",
  provider_id: "fish_audio",
  env_var: "FISH_AUDIO_API_KEY",
  created_at: "2026-10-12T00:00:00Z",
  last_used_at: null,
  last_test_at: null,
  last_test_ok: null,
  notes: null,
  stored: true,
};

async function openVoiceSettings(options: MockOptions = {}) {
  installDevMocks({ settings: { onboarding_done: true }, ...options });
  render(<App initialPath="/settings" />);
  return within((await screen.findByRole("heading", { name: "Voice" })).closest("section")!);
}

const engine = (card: ReturnType<typeof within>, name: string) =>
  within(card.getByRole("list", { name: "Speech engines" }).querySelector(`li:has([aria-label="Move ${name} up"])`) as HTMLElement);

describe("voice settings", () => {
  it("downloads a speech model and clears the warning", async () => {
    const card = await openVoiceSettings();
    expect(card.getByText(/Download the base speech model to talk/)).toBeInTheDocument();
    const base = within(card.getByRole("list", { name: "Speech models" })).getByText("base").closest("li")!;
    await userEvent.click(within(base).getByRole("button", { name: /download/i }));
    expect(await within(base).findByText("Downloaded")).toBeInTheDocument();
    await waitFor(() => expect(card.queryByText(/Download the base speech model/)).not.toBeInTheDocument());
  });

  it("shows which voices are ready and keeps the fallback order", async () => {
    const card = await openVoiceSettings();
    const engines = card.getByRole("list", { name: "Speech engines" });
    const order = () => within(engines).getAllByRole("button", { name: /^Move .* down$/ }).map((b) => b.getAttribute("aria-label"));
    expect(order()).toEqual(["Move Fish Audio down", "Move Piper (local) down", "Move Windows voices down"]);
    expect(engine(card, "Fish Audio").getByText("Not set up")).toBeInTheDocument();
    expect(engine(card, "Fish Audio").getByText(/Add a key in the Key Library/)).toBeInTheDocument();
    expect(engine(card, "Windows voices").getByText("Ready")).toBeInTheDocument();

    await userEvent.click(card.getByRole("button", { name: "Move Windows voices up" }));
    await waitFor(() => expect(order()).toEqual(["Move Fish Audio down", "Move Windows voices down", "Move Piper (local) down"]));
    await waitFor(async () =>
      expect((await invoke<VoiceOverview>("get_voice_overview")).settings.output_order).toEqual(["fish_audio", "system", "piper"]),
    );
  });

  it("installs Piper and downloads a voice", async () => {
    const card = await openVoiceSettings();
    const piper = engine(card, "Piper (local)");
    await userEvent.click(piper.getByRole("button", { name: /install piper/i }));
    const select = await piper.findByLabelText("Piper voice");
    expect(select).toHaveValue("en_US-lessac-medium");
    await userEvent.click(piper.getByRole("button", { name: /download \(63 MB\)/i }));
    await waitFor(() => expect(piper.getByText("Ready")).toBeInTheDocument());
    expect(piper.queryByRole("button", { name: /download/i })).not.toBeInTheDocument();
  });

  it("checks a Fish Audio key, picks a voice and sets a budget", async () => {
    const card = await openVoiceSettings({ keys: [fishKey] });
    const fish = engine(card, "Fish Audio");
    expect(fish.getByText("Ready")).toBeInTheDocument();
    await userEvent.click(fish.getByRole("button", { name: "Check key" }));
    expect(await fish.findByText(/the free s2.1-pro-free model answered/)).toBeInTheDocument();

    await userEvent.type(fish.getByLabelText("Search Fish Audio voices"), "warm");
    await userEvent.click(fish.getByRole("button", { name: /search/i }));
    const results = await fish.findByRole("list", { name: "Fish Audio voices" });
    await userEvent.click(within(results).getByRole("button", { name: "Use" }));
    expect(await fish.findByText("Voice: Warm Narrator")).toBeInTheDocument();

    const budget = fish.getByLabelText("Monthly limit (characters)");
    await userEvent.type(budget, "20000");
    await userEvent.tab();
    await waitFor(async () => expect((await invoke<VoiceOverview>("get_voice_overview")).settings.fish_monthly_budget).toBe(20000));
    // 12,400 of 20,000 characters: under the warning level, shown as a meter.
    expect(fish.getByRole("meter", { name: /used this month/i })).toBeInTheDocument();
  });

  it("text only hides the voice options", async () => {
    const card = await openVoiceSettings();
    await userEvent.click(card.getByRole("switch", { name: "Talk with the producer" }));
    await waitFor(() => expect(card.queryByText("Listening")).not.toBeInTheDocument());
    expect((await invoke<VoiceOverview>("get_voice_overview")).settings.enabled).toBe(false);
  });
});
