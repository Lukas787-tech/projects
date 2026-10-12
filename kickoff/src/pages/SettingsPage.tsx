import { open } from "@tauri-apps/plugin-dialog";
import { FolderOpen } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router";

import type { AppSettings } from "../bindings/AppSettings";
import type { Theme } from "../bindings/Theme";
import { useAppInfo, useSaveSettings, useSettings, useSystemStatus } from "../app/queries";
import { Button, Card, Notice, PageHeader } from "../components/ui";
import { AiSettingsSection } from "./AiSettings";
import { errorMessage } from "../lib/ipc";

const themes: { value: Theme; label: string }[] = [
  { value: "dark", label: "Dark" },
  { value: "light", label: "Light" },
  { value: "system", label: "Match Windows" },
];

export function SettingsPage() {
  const { data: settings } = useSettings();
  const save = useSaveSettings();
  const [pickError, setPickError] = useState<string | null>(null);
  const navigate = useNavigate();

  if (!settings) return null;
  const update = (patch: Partial<AppSettings>) => save.mutate({ ...settings, ...patch });

  async function pickProjectsDir() {
    setPickError(null);
    try {
      const dir = await open({ directory: true, multiple: false, title: "Choose a folder for new projects" });
      if (typeof dir === "string") update({ projects_dir: dir });
    } catch (e) {
      setPickError(errorMessage(e));
    }
  }

  return (
    <>
      <PageHeader
        title="Settings"
        actions={<Button onClick={() => navigate("/setup")}>Run setup again</Button>}
      />
      <div className="flex max-w-3xl flex-col gap-4">
        {save.isError && <Notice tone="danger">Couldn't save settings: {errorMessage(save.error)}</Notice>}

        <Card title="Appearance">
          <fieldset>
            <legend className="mb-2 text-sm">Theme</legend>
            <div className="flex gap-2" role="radiogroup">
              {themes.map((t) => (
                <label
                  key={t.value}
                  className={`cursor-pointer rounded-md border px-3 py-1.5 text-sm ${
                    settings.theme === t.value ? "border-accent bg-surface-2" : "border-line hover:border-accent"
                  }`}
                >
                  <input
                    type="radio"
                    name="theme"
                    value={t.value}
                    checked={settings.theme === t.value}
                    onChange={() => update({ theme: t.value })}
                    className="sr-only"
                  />
                  {t.label}
                </label>
              ))}
            </div>
          </fieldset>
        </Card>

        <AiSettingsSection />
        <ProjectsFolderCard settings={settings} onPick={pickProjectsDir} error={pickError} />
        <AboutCard />
      </div>
    </>
  );
}

function ProjectsFolderCard({
  settings,
  onPick,
  error,
}: {
  settings: AppSettings;
  onPick: () => void;
  error: string | null;
}) {
  const { data: status } = useSystemStatus();
  const current = settings.projects_dir ?? status?.default_projects_dir ?? "";
  return (
    <Card title="Projects folder">
      <p className="mb-3 text-sm text-muted">New starter kits are created here, one folder per project.</p>
      <div className="flex items-center gap-3">
        <code className="min-w-0 flex-1 truncate rounded-md bg-surface-2 px-3 py-1.5 font-mono text-sm" title={current}>
          {current}
          {!settings.projects_dir && current && <span className="ml-2 text-muted">(default)</span>}
        </code>
        <Button onClick={onPick}>
          <FolderOpen size={16} /> Choose…
        </Button>
      </div>
      {error && (
        <div className="mt-3">
          <Notice tone="danger">{error}</Notice>
        </div>
      )}
    </Card>
  );
}

function AboutCard() {
  const { data: info } = useAppInfo();
  const { data: status } = useSystemStatus();
  if (!info || !status) return null;
  const rows: [string, string][] = [
    ["Version", info.version],
    ["Data folder", status.data_dir],
    ["Logs", status.log_dir],
    ["Database schema", `v${status.schema_version}`],
    ["Provider presets", `updated ${status.presets_updated}`],
    ["Credential store", status.secret_store_problem ?? "Windows Credential Manager"],
  ];
  return (
    <Card title={`About ${info.name}`}>
      <dl className="grid grid-cols-[10rem_1fr] gap-x-4 gap-y-2 text-sm">
        {rows.map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-muted">{k}</dt>
            <dd className="min-w-0 break-words font-mono">{v}</dd>
          </div>
        ))}
      </dl>
    </Card>
  );
}
