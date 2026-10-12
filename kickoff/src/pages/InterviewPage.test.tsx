import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { App } from "../app/App";
import type { InterviewView } from "../bindings/InterviewView";
import { installDevMocks } from "../lib/devMocks";
import { GAME_PACK, MOCK_EXTRACTOR_FAILS, MOCK_PRODUCER_FAILS, type MockProjectSeed } from "../lib/interviewMocks";

const IDEA = "A cozy survival game about keeping a lighthouse lit through winter";

/** A project one exchange in: the pitch is known and the producer asked for a title. */
const started: MockProjectSeed = {
  answers: { pitch: IDEA },
  messages: [
    ["user", IDEA],
    ["producer", "Love it. What should we call it for now?"],
  ],
};

/** A valid, settled answer for every required field. */
function everyAnswer(): Record<string, unknown> {
  return Object.fromEntries(
    GAME_PACK.fields
      .filter((f) => f.required)
      .map((f) => {
        switch (f.kind) {
          case "text":
            return [f.id, `Answer for ${f.label}`];
          case "list":
            return [f.id, Array.from({ length: Math.max(1, f.min_items) }, (_, i) => `${f.label} item ${i + 1}`)];
          case "parts":
            return [f.id, Object.fromEntries(f.parts.map((p) => [p.id, `${p.label} answer`]))];
          case "choice":
            return [f.id, { choice: f.options[0]!.id, reason: null }];
        }
      }),
  );
}

function open(seed: MockProjectSeed, options: { streamDelayMs?: number } = {}) {
  installDevMocks({ settings: { onboarding_done: true }, projects: [seed], ...options });
  render(<App initialPath="/project/project-1" />);
}

const brief = () => screen.getByRole("complementary", { name: "Brief" });
const field = (label: string) => within(brief()).getByRole("button", { name: new RegExp(`^${label}:`) });
const answer = async (text: string) => {
  await userEvent.type(await screen.findByLabelText("Your answer"), text);
  await userEvent.click(screen.getByRole("button", { name: "Send" }));
};

describe("producer interview", () => {
  it("streams the producer's next question and fills the brief from the answer", async () => {
    open(started, { streamDelayMs: 20 });
    expect(await screen.findByRole("heading", { name: "Untitled idea" })).toBeInTheDocument();
    expect(screen.getByText("1 of 14 settled")).toBeInTheDocument();

    await answer("The Last Lighthouse");
    // While the reply streams, the user's message shows and sending is blocked.
    const messages = screen.getByRole("list", { name: "Messages" });
    expect(within(messages).getByText("The Last Lighthouse")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();

    expect(await within(messages).findByText(/^Got it\. Next, genre:/, {}, { timeout: 3000 })).toBeInTheDocument();
    // The project takes its name from the working title once the turn is saved.
    expect(await screen.findByRole("heading", { name: "The Last Lighthouse" }, { timeout: 3000 })).toBeInTheDocument();
    expect(screen.getByText("2 of 14 settled")).toBeInTheDocument();
    expect(field("Working title")).toHaveAccessibleName(/The Last Lighthouse\. Settled/);
    expect(within(brief()).getByText(/Next up: Genre, Target player/)).toBeInTheDocument();
  });

  it("starts with the idea typed on the projects page", async () => {
    installDevMocks({ settings: { onboarding_done: true } });
    render(<App initialPath="/" />);
    await userEvent.type(await screen.findByLabelText(/start a new project/i), IDEA);
    await userEvent.click(screen.getByRole("button", { name: /start interview/i }));

    const messages = await screen.findByRole("list", { name: "Messages" });
    expect(await within(messages).findByText(IDEA)).toBeInTheDocument();
    expect(await within(messages).findByText(/^Got it\. Next, working title:/)).toBeInTheDocument();
    expect(field("One-line pitch")).toHaveAccessibleName(new RegExp(IDEA));
    // Sent once, even though the page re-renders as the brief updates.
    const view = await invoke<InterviewView>("get_interview", { project_id: "project-1" });
    expect(view.messages.map((m) => m.role)).toEqual(["user", "producer"]);
  });

  it("keeps the user's own edit and offers the producer's version as a suggestion", async () => {
    open(started);
    await userEvent.click(await screen.findByRole("button", { name: /^Genre:/ }));
    let dialog = await screen.findByRole("dialog", { name: "Genre" });
    await userEvent.type(within(dialog).getByLabelText("Answer"), "Cozy survival");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(field("Genre")).toHaveAccessibleName(/Cozy survival\. Edited by you/));

    await userEvent.click(field("Genre"));
    dialog = await screen.findByRole("dialog", { name: "Genre" });
    await userEvent.click(within(dialog).getByRole("button", { name: /ask the producer to revisit/i }));
    await waitFor(() => expect(within(field("Genre")).getByText("Revisit next")).toBeInTheDocument());
    expect(within(brief()).getByText(/Next up: Genre/)).toBeInTheDocument();

    await answer("Survival crafting with a cozy tone");
    const suggestion = await within(brief()).findByText(/Producer suggests: Survival crafting with a cozy tone/);
    expect(field("Genre")).toHaveAccessibleName(/Cozy survival\. Edited by you/);
    await userEvent.click(within(suggestion).getByRole("button", { name: "Use it" }));
    await waitFor(() => expect(field("Genre")).toHaveAccessibleName(/Survival crafting with a cozy tone\. Edited by you/));
    expect(within(brief()).queryByText(/Producer suggests/)).not.toBeInTheDocument();
  });

  it("edits list, multi-part and choice fields", async () => {
    open(started);
    await userEvent.click(await screen.findByRole("button", { name: /^Design pillars:/ }));
    let dialog = await screen.findByRole("dialog", { name: "Design pillars" });
    expect(within(dialog).getByText(/One item per line, 3 to 5 items/)).toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText("Items"), "Warmth vs cold{enter}Every trip matters{enter}Quiet moments");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(field("Design pillars")).toHaveAccessibleName(/Warmth vs cold; Every trip matters; Quiet moments/));

    await userEvent.click(field("Core loop"));
    dialog = await screen.findByRole("dialog", { name: "Core loop" });
    const parts = within(dialog).getAllByRole("textbox");
    await userEvent.type(parts[0]!, "Gather driftwood");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(field("Core loop")).toHaveAccessibleName(/Gather driftwood/));

    await userEvent.click(field("Engine"));
    dialog = await screen.findByRole("dialog", { name: "Engine" });
    await userEvent.selectOptions(within(dialog).getByLabelText("Choice"), "godot");
    await userEvent.type(within(dialog).getByLabelText("Why"), "free and light");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(field("Engine")).toHaveAccessibleName(/Godot 4 \(free and light\)/));
  });

  it("offers a retry when the producer fails, and keeps the answer", async () => {
    open(started);
    await answer(MOCK_PRODUCER_FAILS);
    expect(await screen.findByText(/gemini is rate limited for today/)).toBeInTheDocument();
    expect(within(screen.getByRole("list", { name: "Messages" })).getByText(MOCK_PRODUCER_FAILS)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /try again/i }));
    expect(await screen.findByText(/^Got it\. Next,/)).toBeInTheDocument();
    expect(screen.queryByText(/rate limited/)).not.toBeInTheDocument();
  });

  it("gets a reply to a message the producer never answered", async () => {
    open({ messages: [["user", IDEA]] });
    expect(await screen.findByText(/hasn't answered your last message/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /get a reply/i }));
    expect(await screen.findByText(/^Got it\. Next,/)).toBeInTheDocument();
    expect(screen.queryByText(/hasn't answered your last message/)).not.toBeInTheDocument();
  });

  it("says when the brief couldn't be updated from an answer", async () => {
    open(started);
    await answer(MOCK_EXTRACTOR_FAILS);
    expect(await screen.findByText(/brief couldn't be updated from that answer\. You can fill in the field yourself/)).toBeInTheDocument();
    expect(await screen.findByText(/^Got it\. Next,/)).toBeInTheDocument();
  });

  it("shows the producer's honesty notes", async () => {
    open({ ...started, flags: [{ kind: "scope", field: "scope", note: "An open world is a lot for one person." }] });
    const notes = await screen.findByRole("list", { name: "Producer notes" });
    expect(within(notes).getByText("Scope:")).toBeInTheDocument();
    expect(within(notes).getByText(/open world is a lot/)).toBeInTheDocument();
  });

  it("asks before moving on with an incomplete brief", async () => {
    open(started);
    await userEvent.click(await screen.findByRole("button", { name: /continue to research/i }));
    const dialog = await screen.findByRole("dialog", { name: /incomplete brief/i });
    expect(within(dialog).getByText(/Not discussed yet: Working title, Genre/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: /keep talking/i }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /continue to research/i }));
    await userEvent.click(await screen.findByRole("button", { name: /continue anyway/i }));
    expect(await screen.findByText("Interview finished")).toBeInTheDocument();
  });

  it("moves on straight away once the brief is complete", async () => {
    open({ answers: everyAnswer(), messages: started.messages });
    expect(await screen.findByText(/complete enough to start building/i)).toBeInTheDocument();
    expect(screen.getByText("14 of 14 settled")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /continue to research/i }));
    expect(await screen.findByText("Interview finished")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
