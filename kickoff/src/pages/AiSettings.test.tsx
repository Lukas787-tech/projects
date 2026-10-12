import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { App } from "../app/App";
import type { AiSettings } from "../bindings/AiSettings";
import { describe as describeStatus } from "../components/AiStatusToasts";
import { Meter } from "../components/Meter";
import { CAPABLE_OLLAMA, installDevMocks, PRESETS } from "../lib/devMocks";

const onboarded = { settings: { onboarding_done: true } };

describe("AI settings", () => {
  it("reorders and edits the fallback chain", async () => {
    installDevMocks({ ...onboarded, ollama: CAPABLE_OLLAMA });
    render(<App initialPath="/settings" />);
    const chain = await screen.findByRole("list", { name: "Fallback chain" });
    const items = within(chain).getAllByRole("listitem");
    expect(items[0]).toHaveTextContent("Google Gemini");
    expect(items[3]).toHaveTextContent(/ollama.*running/i);

    await userEvent.click(within(items[3]!).getByRole("button", { name: "Move up" }));
    await waitFor(async () => {
      const ai = await invoke<AiSettings>("get_ai_settings");
      expect(ai.fallback_chain.map((r) => r.provider_id)).toEqual(["gemini", "groq", "ollama", "openrouter"]);
    });

    await userEvent.click(within(chain).getByRole("button", { name: "Remove OpenRouter (free models)" }));
    await userEvent.selectOptions(screen.getByLabelText("Add provider to fallback chain"), "mistral");
    await waitFor(async () => {
      const ai = await invoke<AiSettings>("get_ai_settings");
      expect(ai.fallback_chain.map((r) => r.provider_id)).toEqual(["gemini", "groq", "ollama", "mistral"]);
    });
  });

  it("checks a task's chain and reports which provider answered", async () => {
    installDevMocks({ ...onboarded, ollama: CAPABLE_OLLAMA });
    render(<App initialPath="/settings" />);
    const checks = await screen.findAllByRole("button", { name: "Check" });
    await userEvent.click(checks[0]!);
    expect(await screen.findByText(/answered by ollama \(local\) using ollama-best-model/i)).toBeInTheDocument();
  });

  it("assigns a provider and model to one task", async () => {
    installDevMocks({ ...onboarded, ollama: CAPABLE_OLLAMA });
    render(<App initialPath="/settings" />);
    const provider = await screen.findByLabelText("Brief extraction provider");
    // Ollama is offered once detection has found it running.
    await within(provider).findByRole("option", { name: "Ollama (local)" });
    await userEvent.selectOptions(provider, "ollama");
    const model = screen.getByLabelText("Brief extraction model");
    await waitFor(() => expect(model).not.toBeDisabled());
    await screen.findByRole("option", { name: /qwen3:8b/ });
    await userEvent.selectOptions(model, "qwen3:8b");
    await waitFor(async () => {
      const ai = await invoke<AiSettings>("get_ai_settings");
      expect(ai.extraction).toEqual({ provider_id: "ollama", model: "qwen3:8b" });
    });
  });

  it("shows today's usage against free-tier limits", async () => {
    installDevMocks(onboarded);
    render(<App initialPath="/settings" />);
    const meter = await screen.findByRole("meter", { name: /requests: 42 of 250/i });
    expect(meter).toHaveAttribute("aria-valuenow", "42");
    expect(screen.getByText(/rate limited 2 times today/i)).toBeInTheDocument();
  });
});

describe("usage meter", () => {
  it("adds a word and icon at the warning and danger thresholds", () => {
    const { rerender } = render(<Meter label="Requests" value={100} limit={250} unit="requests" />);
    expect(screen.queryByText(/near limit|at limit/i)).not.toBeInTheDocument();
    rerender(<Meter label="Requests" value={210} limit={250} unit="requests" />);
    expect(screen.getByText("Near limit")).toBeInTheDocument();
    rerender(<Meter label="Requests" value={250} limit={250} unit="requests" />);
    expect(screen.getByText("At limit")).toBeInTheDocument();
  });

  it("shows only the count when the limit is unknown", () => {
    render(<Meter label="Tokens" value={1200} limit={null} unit="tokens" />);
    expect(screen.queryByRole("meter")).not.toBeInTheDocument();
    expect(screen.getByText(/no published limit/i)).toBeInTheDocument();
  });
});

describe("AI status messages", () => {
  it("describes waits, fallbacks and bad keys in plain words", () => {
    expect(describeStatus({ type: "waiting", provider_id: "gemini", model: "m", seconds: 7, reason: "rate_limited" }, PRESETS)).toBe(
      "Google Gemini is rate limited. Retrying in 7 s.",
    );
    expect(
      describeStatus({ type: "fell_back", from_provider: "gemini", to_provider: "groq", reason: "quota used up" }, PRESETS),
    ).toBe("Switched from Google Gemini to Groq: quota used up");
    expect(describeStatus({ type: "failed", provider_id: "groq", error_kind: "auth", message: "x" }, PRESETS)).toMatch(/rejected its key/);
    expect(describeStatus({ type: "failed", provider_id: "groq", error_kind: "network", message: "x" }, PRESETS)).toBeNull();
  });
});
