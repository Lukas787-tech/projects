import { isTauri } from "@tauri-apps/api/core";
import { openUrl } from "@tauri-apps/plugin-opener";

/** Open an https link in the user's browser (never inside the app window). */
export async function openExternal(url: string) {
  if (!url.startsWith("https://")) return;
  if (isTauri()) {
    await openUrl(url);
  } else {
    window.open(url, "_blank", "noopener,noreferrer");
  }
}
