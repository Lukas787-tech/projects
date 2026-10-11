import { useEffect } from "react";

import { useAppInfo, useSettings } from "./queries";

/** Applies the theme setting and the app name to the document. */
export function ThemeSync() {
  const { data: settings } = useSettings();
  const { data: info } = useAppInfo();
  const theme = settings?.theme ?? "dark";

  useEffect(() => {
    const root = document.documentElement;
    if (theme !== "system") {
      root.dataset.theme = theme;
      return;
    }
    const media = window.matchMedia("(prefers-color-scheme: light)");
    const apply = () => (root.dataset.theme = media.matches ? "light" : "dark");
    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [theme]);

  useEffect(() => {
    if (info) document.title = info.name;
  }, [info]);

  return null;
}
