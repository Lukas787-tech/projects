import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { App } from "../app/App";
import type { ProjectCard } from "../bindings/ProjectCard";
import { installDevMocks } from "../lib/devMocks";

const onboarded = { settings: { onboarding_done: true } };

describe("projects", () => {
  it("lists projects with their pitch, stage and brief progress", async () => {
    installDevMocks({
      ...onboarded,
      projects: [
        { answers: { working_title: "Beeline", pitch: "A roguelike where you are the swarm" } },
        { answers: { working_title: "Night Shift" }, stage: "research" },
      ],
    });
    render(<App initialPath="/" />);
    const list = await screen.findByRole("list", { name: "Projects" });
    const beeline = within(list).getByText("Beeline").closest("li")!;
    expect(within(beeline).getByText("A roguelike where you are the swarm")).toBeInTheDocument();
    expect(within(beeline).getByText("Interview")).toBeInTheDocument();
    expect(within(beeline).getByRole("progressbar", { name: "Beeline brief progress" })).toHaveAttribute("aria-valuenow", "14");
    expect(within(within(list).getByText("Night Shift").closest("li")!).getByText("Research")).toBeInTheDocument();
  });

  it("opens a project's interview from the list", async () => {
    installDevMocks({ ...onboarded, projects: [{ answers: { working_title: "Beeline" } }] });
    render(<App initialPath="/" />);
    await userEvent.click(await screen.findByRole("link", { name: /beeline/i }));
    expect(await screen.findByRole("heading", { name: "Beeline" })).toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "Brief" })).toBeInTheDocument();
  });

  it("deletes a project after confirming", async () => {
    installDevMocks({ ...onboarded, projects: [{ answers: { working_title: "Beeline" } }] });
    render(<App initialPath="/" />);
    await userEvent.click(await screen.findByRole("button", { name: "Delete Beeline" }));
    const dialog = await screen.findByRole("dialog", { name: /delete "beeline"/i });
    await userEvent.click(within(dialog).getByRole("button", { name: /delete project/i }));
    await waitFor(() => expect(screen.queryByRole("list", { name: "Projects" })).not.toBeInTheDocument());
    expect(await invoke<ProjectCard[]>("list_projects")).toHaveLength(0);
  });

  it("fills the idea box from an example and won't start an empty idea", async () => {
    installDevMocks(onboarded);
    render(<App initialPath="/" />);
    const start = await screen.findByRole("button", { name: /start interview/i });
    expect(start).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: /swarm of bees/i }));
    expect(screen.getByLabelText(/start a new project/i)).toHaveValue("A fast roguelike where you play as a swarm of bees");
    expect(start).toBeEnabled();
  });
});
