import { isTauri } from "@tauri-apps/api/core";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "./app/App";
import "./styles.css";

async function start() {
  // Outside the Tauri webview (plain `npm run dev`), serve commands from mocks.
  if (!isTauri()) {
    const { installDevMocks } = await import("./lib/devMocks");
    installDevMocks({ streamDelayMs: 40 });
  }
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void start();
