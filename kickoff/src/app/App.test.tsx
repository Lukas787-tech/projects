import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { installDevMocks, MOCK_APP_INFO } from "../lib/devMocks";
import { App } from "./App";

const onboarded = { settings: { onboarding_done: true } };

describe("app shell", () => {
  it("shows the app name from the backend and the main navigation", async () => {
    installDevMocks(onboarded);
    render(<App initialPath="/" />);
    expect(await screen.findByText(MOCK_APP_INFO.name)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /projects/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /key library/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /settings/i })).toBeInTheDocument();
    await waitFor(() => expect(document.title).toBe(MOCK_APP_INFO.name));
  });

  it("sends a first-run user to the setup wizard", async () => {
    installDevMocks();
    render(<App initialPath="/" />);
    expect(await screen.findByRole("heading", { name: /connect a free ai model/i })).toBeInTheDocument();
  });

  it("defaults to the dark theme and applies a changed theme", async () => {
    installDevMocks(onboarded);
    render(<App initialPath="/settings" />);
    const light = await screen.findByRole("radio", { name: "Light" });
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("dark"));
    await userEvent.click(light);
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("light"));
  });

  it("warns on the key library page when keys cannot be stored", async () => {
    installDevMocks(onboarded);
    render(<App initialPath="/keys" />);
    expect(await screen.findByText(/keys can't be saved on this system/i)).toBeInTheDocument();
  });
});
