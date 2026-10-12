import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { App } from "../app/App";
import type { AiSettings } from "../bindings/AiSettings";
import type { AppSettings } from "../bindings/AppSettings";
import type { KeyEntry } from "../bindings/KeyEntry";
import type { VoiceOverview } from "../bindings/VoiceOverview";
import { CAPABLE_OLLAMA, installDevMocks, MOCK_BAD_KEY } from "../lib/devMocks";

async function reachChooseStep() {
  render(<App initialPath="/setup" />);
  await userEvent.click(await screen.findByRole("button", { name: /get started/i }));
}

describe("setup wizard", () => {
  it("recommends Gemini without Ollama, saves the tested key and finishes", async () => {
    installDevMocks();
    await reachChooseStep();

    const gemini = await screen.findByRole("radio", { name: /google gemini/i });
    expect(gemini).toHaveAttribute("aria-checked", "true");
    expect(gemini).toHaveTextContent(/recommended/i);
    expect(gemini).toHaveTextContent(/no card/i);
    await userEvent.click(screen.getByRole("button", { name: /continue/i }));

    expect(await screen.findByText(/google may use prompts/i)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/paste the key here/i), "AIza-test-key-123");
    await userEvent.click(screen.getByRole("button", { name: /test and save key/i }));

    // Optional voice step: download local speech recognition, then go on.
    expect(await screen.findByRole("heading", { name: /talk instead of typing/i })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /download/i }));
    expect(await screen.findByText("Ready")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /^continue$/i }));

    expect(await screen.findByRole("heading", { name: /you're ready/i })).toBeInTheDocument();
    const keys = await invoke<KeyEntry[]>("list_keys");
    expect(keys).toHaveLength(1);
    expect(keys[0]).toMatchObject({ provider_id: "gemini", env_var: "GEMINI_API_KEY", kind: "llm" });
    const ai = await invoke<AiSettings>("get_ai_settings");
    expect(ai.fallback_chain[0]?.provider_id).toBe("gemini");

    await userEvent.click(screen.getByRole("button", { name: /start my first project/i }));
    expect(await screen.findByRole("heading", { name: "Projects" })).toBeInTheDocument();
    const settings = await invoke<AppSettings>("get_settings");
    expect(settings.onboarding_done).toBe(true);
  });

  it("recommends a capable local Ollama and needs no key", async () => {
    installDevMocks({ ollama: CAPABLE_OLLAMA });
    await reachChooseStep();

    const ollama = await screen.findByRole("radio", { name: /ollama on this pc/i });
    expect(ollama).toHaveAttribute("aria-checked", "true");
    expect(ollama).toHaveTextContent("qwen3:8b");
    await userEvent.click(screen.getByRole("button", { name: /continue/i }));
    await userEvent.click(await screen.findByRole("button", { name: /test ollama/i }));

    // Choosing to type turns voice off.
    await userEvent.click(await screen.findByRole("button", { name: /skip, i'll type/i }));
    expect(await screen.findByRole("heading", { name: /you're ready/i })).toBeInTheDocument();
    expect((await invoke<VoiceOverview>("get_voice_overview")).settings.enabled).toBe(false);
    expect(await invoke<KeyEntry[]>("list_keys")).toHaveLength(0);
    const ai = await invoke<AiSettings>("get_ai_settings");
    expect(ai.fallback_chain[0]?.provider_id).toBe("ollama");
  });

  it("explains a rejected key and stores nothing", async () => {
    installDevMocks();
    await reachChooseStep();
    await userEvent.click(await screen.findByRole("radio", { name: /groq/i }));
    await userEvent.click(screen.getByRole("button", { name: /continue/i }));
    await userEvent.type(await screen.findByLabelText(/paste the key here/i), MOCK_BAD_KEY);
    await userEvent.click(screen.getByRole("button", { name: /test and save key/i }));

    expect(await screen.findByText(/key was rejected/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /you're ready/i })).not.toBeInTheDocument();
    expect(await invoke<KeyEntry[]>("list_keys")).toHaveLength(0);
  });

  it("can be skipped", async () => {
    installDevMocks();
    render(<App initialPath="/setup" />);
    await userEvent.click(await screen.findByRole("button", { name: /skip for now/i }));
    await waitFor(async () => expect((await invoke<AppSettings>("get_settings")).onboarding_done).toBe(true));
    expect(await screen.findByRole("heading", { name: "Projects" })).toBeInTheDocument();
  });
});
