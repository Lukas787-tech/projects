import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { App } from "../app/App";
import type { InterviewView } from "../bindings/InterviewView";
import type { KeyEntry } from "../bindings/KeyEntry";
import { installDevMocks, type MockOptions } from "../lib/devMocks";

const tavily: KeyEntry = {
  id: "key-tavily",
  label: "Tavily",
  kind: "search",
  provider_id: "tavily",
  env_var: "TAVILY_API_KEY",
  created_at: "2026-10-12T00:00:00Z",
  last_used_at: null,
  last_test_at: null,
  last_test_ok: null,
  notes: null,
  stored: true,
};

function open(options: MockOptions = {}) {
  installDevMocks({
    settings: { onboarding_done: true },
    projects: [{ stage: "research", answers: { working_title: "Lighthouse", art_direction: "Pixel art" } }],
    ...options,
  });
  render(<App initialPath="/project/project-1/research" />);
}

const card = () => screen.getByRole("article");
const kept = () => screen.getByRole("complementary", { name: "Kept references" });

async function findReferences() {
  await userEvent.click(await screen.findByRole("button", { name: "Find references" }));
  return screen.findByRole("article", { name: "Stardew Valley" });
}

describe("research", () => {
  it("works without a search key and marks games as unverified", async () => {
    open();
    expect(await screen.findByRole("heading", { name: "Lighthouse: research" })).toBeInTheDocument();
    expect(screen.getByText(/No web search key/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add a key" })).toHaveAttribute("href", "/keys");

    await findReferences();
    expect(within(card()).getByText("Unverified")).toBeInTheDocument();
    expect(within(card()).getByText("Reference only")).toBeInTheDocument();
    expect(within(card()).getByText("Seasons give each week a goal.")).toBeInTheDocument();
    expect(screen.getByText(/mood references, Kenney, and OpenGameArt are skipped/)).toBeInTheDocument();
    // No mood references without search.
    expect(screen.getByRole("button", { name: "Mood 0" })).toBeInTheDocument();
  });

  it("triages with the keyboard and can undo", async () => {
    open({ keys: [tavily] });
    expect(await screen.findByText(/Web search: Tavily · 0 of 1,000 credits used this month/)).toBeInTheDocument();
    await findReferences();
    expect(within(card()).queryByText("Unverified")).not.toBeInTheDocument();

    await userEvent.keyboard("{ArrowRight}");
    expect(await screen.findByRole("article", { name: "Don't Starve" })).toBeInTheDocument();
    expect(within(kept()).getByText("Stardew Valley")).toBeInTheDocument();

    await userEvent.keyboard("{ArrowLeft}");
    expect(await screen.findByRole("article", { name: "Moody lighthouse concept art" })).toBeInTheDocument();
    expect(within(kept()).getByText("Rejected (1)")).toBeInTheDocument();

    await userEvent.keyboard("{Control>}z{/Control}");
    expect(await screen.findByRole("article", { name: "Don't Starve" })).toBeInTheDocument();
    expect(within(kept()).queryByText(/Rejected/)).not.toBeInTheDocument();

    // Unkeeping puts a reference back in the queue.
    await userEvent.click(within(kept()).getByRole("button", { name: "Unkeep Stardew Valley" }));
    expect(await screen.findByRole("article", { name: "Stardew Valley" })).toBeInTheDocument();
  });

  it("shows each asset's license and the credits it needs", async () => {
    open({ keys: [tavily] });
    await findReferences();
    await userEvent.click(screen.getByRole("button", { name: /^Assets/ }));

    const snow = await screen.findByRole("article", { name: "Snow 02" });
    expect(within(snow).getByText("Usable · CC0 1.0")).toBeInTheDocument();
    expect(within(snow).getByText("Powered by Poly Haven")).toBeInTheDocument();
    expect(within(snow).getByText(/Credit: "Snow 02" by Rob Tuytel/)).toBeInTheDocument();
    // The thumbnail arrives from the backend as a data URL (decorative, so no alt text).
    await waitFor(() => expect(snow.querySelector("img")?.getAttribute("src")).toMatch(/^data:image\//));

    await userEvent.click(screen.getByRole("button", { name: /Keep/ }));
    const model = await screen.findByRole("article", { name: "Old Lighthouse" });
    expect(within(model).getByText("Usable · CC BY 4.0")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Reject/ }));

    const boat = await screen.findByRole("article", { name: "Fishing Boat" });
    expect(within(boat).getByText("Reference only")).toBeInTheDocument();
    expect(within(boat).getByText(/doesn't allow use in your game/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Reject/ }));

    const sound = await screen.findByRole("article", { name: "Winter Ambience" });
    expect(within(sound).getByText("License unknown")).toBeInTheDocument();
    expect(within(sound).getByText(/Check the page before using it/)).toBeInTheDocument();
  });

  it("finds more like a reference and keeps it", async () => {
    open({ keys: [tavily] });
    await findReferences();
    await userEvent.keyboard("{ArrowUp}");
    expect(await within(kept()).findByText("Stardew Valley")).toBeInTheDocument();
    expect(await screen.findByRole("article", { name: "Don't Starve" })).toBeInTheDocument();
    // The similar games join the end of the queue.
    expect(screen.getByRole("button", { name: "Games 3" })).toBeInTheDocument();
  });

  it("refines the brief from what was kept", async () => {
    open({ keys: [tavily] });
    await findReferences();
    expect(screen.getByRole("button", { name: /refine brief/i })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: /^Mood/ }));
    await screen.findByRole("article", { name: "Moody lighthouse concept art" });
    await userEvent.keyboard("{ArrowRight}");

    await userEvent.click(screen.getByRole("button", { name: /refine brief/i }));
    const dialog = await screen.findByRole("dialog", { name: "Refine the brief" });
    const proposal = await within(dialog).findByRole("listitem", { name: "Art direction" });
    expect(within(proposal).getByText("Pixel art")).toBeInTheDocument();
    expect(within(proposal).getByText("Inspired by Moody lighthouse concept art")).toBeInTheDocument();
    await userEvent.click(within(proposal).getByRole("button", { name: "Apply" }));
    expect(await within(proposal).findByText("Applied")).toBeInTheDocument();

    const brief = await invoke<InterviewView>("get_interview", { project_id: "project-1" });
    expect(brief.brief.fields.art_direction?.value).toBe("Inspired by Moody lighthouse concept art");
    expect(brief.brief.fields.art_direction?.status).toBe("user_edited");
  });

  it("reports a failed run and lets the user retry", async () => {
    open({ research: { researchFails: "no AI provider is ready" } });
    await userEvent.click(await screen.findByRole("button", { name: "Find references" }));
    expect(await screen.findByText("no AI provider is ready")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Find references" }));
    expect(await screen.findByRole("article", { name: "Stardew Valley" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("no AI provider is ready")).not.toBeInTheDocument());
  });
});
