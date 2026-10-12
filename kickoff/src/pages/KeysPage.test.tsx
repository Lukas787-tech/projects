import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { App } from "../app/App";
import type { KeyEntry } from "../bindings/KeyEntry";
import { installDevMocks } from "../lib/devMocks";

const onboarded = { settings: { onboarding_done: true } };

describe("key library", () => {
  it("adds a search key with the preset's variable name, then deletes it", async () => {
    installDevMocks(onboarded);
    render(<App initialPath="/keys" />);
    expect(await screen.findByText(/no keys yet/i)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /add key/i }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.selectOptions(within(dialog).getByLabelText("Service"), "tavily");
    expect(within(dialog).getByLabelText("Environment variable")).toHaveValue("TAVILY_API_KEY");
    expect(within(dialog).getByText("Free tier · no card")).toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText("Key"), "tvly-test-1234567890");
    await userEvent.click(within(dialog).getByRole("button", { name: /save key/i }));

    const list = await screen.findByRole("list", { name: "Keys" });
    expect(within(list).getByText("Tavily")).toBeInTheDocument();
    expect(within(list).getByText("TAVILY_API_KEY")).toBeInTheDocument();
    expect(within(list).queryByText("tvly-test-1234567890")).not.toBeInTheDocument();

    await userEvent.click(within(list).getByRole("button", { name: /delete tavily/i }));
    await userEvent.click(await screen.findByRole("button", { name: /delete key/i }));
    await waitFor(async () => expect(await invoke<KeyEntry[]>("list_keys")).toHaveLength(0));
  });

  it("tests a saved AI key and shows the result", async () => {
    installDevMocks(onboarded);
    await invoke("add_key", {
      input: { label: "Groq", kind: "llm", provider_id: "groq", env_var: "GROQ_API_KEY", secret: "gsk_x", notes: null },
    });
    render(<App initialPath="/keys" />);
    const list = await screen.findByRole("list", { name: "Keys" });
    await userEvent.click(within(list).getByRole("button", { name: "Test" }));
    expect(await within(list).findByText(/working: groq-best-model/i)).toBeInTheDocument();
  });
});
