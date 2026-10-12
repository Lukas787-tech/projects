import { CheckCircle2, ExternalLink, KeyRound, Plus, RefreshCw, Trash2, XCircle } from "lucide-react";
import { useMemo, useState } from "react";

import type { KeyEntry } from "../bindings/KeyEntry";
import type { KeyKind } from "../bindings/KeyKind";
import type { ProviderTest } from "../bindings/ProviderTest";
import {
  useAddKey,
  useDeleteKey,
  useKeys,
  usePresets,
  useReplaceKeySecret,
  useSystemStatus,
  useTestKey,
} from "../app/queries";
import { Badge, Button, Dialog, EmptyState, Field, Notice, Page, PageHeader, Select, TextInput } from "../components/ui";
import { openExternal } from "../lib/external";
import { errorMessage } from "../lib/ipc";
import { accessLabel, allProviders, displayName, findProvider, KIND_LABELS } from "../lib/providers";

export function KeysPage() {
  const { data: status } = useSystemStatus();
  const { data: keys, isLoading } = useKeys();
  const { data: catalog } = usePresets();
  const [adding, setAdding] = useState(false);

  return (
    <Page>
      <PageHeader
        title="Key Library"
        description="API keys for AI, voice, search and asset services. They're stored in Windows Credential Manager; generated projects only ever see a variable name."
        actions={
          <Button variant="primary" onClick={() => setAdding(true)}>
            <Plus size={16} /> Add key
          </Button>
        }
      />
      {status?.secret_store_problem && (
        <div className="mb-4">
          <Notice tone="warning">Keys can't be saved on this system: {status.secret_store_problem}</Notice>
        </div>
      )}
      {!isLoading && keys?.length === 0 && (
        <EmptyState icon={<KeyRound size={36} />} title="No keys yet">
          Every feature works with free services. When you add a key it stays in the Windows credential store, and
          generated projects reference it by name only.
        </EmptyState>
      )}
      {keys && keys.length > 0 && (
        <ul className="flex flex-col gap-2" aria-label="Keys">
          {keys.map((key) => (
            <KeyRow key={key.id} entry={key} providerName={key.provider_id ? displayName(catalog, key.provider_id) : null} />
          ))}
        </ul>
      )}
      {adding && <AddKeyDialog onClose={() => setAdding(false)} />}
    </Page>
  );
}

function testBadge(entry: KeyEntry) {
  if (!entry.stored) return <Badge tone="danger">Missing from credential store</Badge>;
  if (entry.last_test_ok === true) return <Badge tone="success">Working</Badge>;
  if (entry.last_test_ok === false) return <Badge tone="danger">Last test failed</Badge>;
  return <Badge>Not tested</Badge>;
}

function KeyRow({ entry, providerName }: { entry: KeyEntry; providerName: string | null }) {
  const test = useTestKey();
  const remove = useDeleteKey();
  const [replacing, setReplacing] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [result, setResult] = useState<ProviderTest | null>(null);

  async function runTest() {
    setResult(null);
    try {
      setResult(await test.mutateAsync(entry.id));
    } catch (e) {
      setResult({ ok: false, provider_id: entry.provider_id ?? "", model: null, latency_ms: 0, error_kind: "error", message: errorMessage(e) });
    }
  }

  return (
    <li className="rounded-lg border border-line bg-surface px-4 py-3">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium">{entry.label}</span>
            <Badge>{KIND_LABELS[entry.kind]}</Badge>
            {testBadge(entry)}
          </div>
          <div className="mt-1 flex flex-wrap gap-x-3 text-xs text-muted">
            {providerName && providerName !== entry.label && <span>{providerName}</span>}
            <code className="font-mono">{entry.env_var}</code>
          </div>
        </div>
        <div className="flex gap-1">
          <Button variant="ghost" onClick={runTest} busy={test.isPending} disabled={!entry.stored}>
            Test
          </Button>
          <Button variant="ghost" onClick={() => setReplacing(true)}>
            <RefreshCw size={14} /> Replace
          </Button>
          <Button variant="ghost" onClick={() => setConfirmDelete(true)} aria-label={`Delete ${entry.label}`}>
            <Trash2 size={14} />
          </Button>
        </div>
      </div>
      {result && (
        <div className="mt-2">
          <Notice tone={result.ok ? "success" : result.error_kind === "untested" ? "info" : "danger"}>{result.message}</Notice>
        </div>
      )}
      {replacing && <ReplaceSecretDialog entry={entry} onClose={() => setReplacing(false)} />}
      <Dialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title={`Delete "${entry.label}"?`}
        description="The key is removed from Windows Credential Manager. Projects that use it will stop getting it."
        footer={
          <>
            <Button onClick={() => setConfirmDelete(false)}>Cancel</Button>
            <Button
              variant="danger"
              busy={remove.isPending}
              onClick={() => remove.mutate(entry.id, { onSuccess: () => setConfirmDelete(false) })}
            >
              Delete key
            </Button>
          </>
        }
      >
        {remove.isError ? <Notice tone="danger">{errorMessage(remove.error)}</Notice> : <p className="text-sm">This can't be undone.</p>}
      </Dialog>
    </li>
  );
}

const KIND_ORDER: KeyKind[] = ["llm", "tts", "stt", "search"];

export function AddKeyDialog({ onClose, initialProvider }: { onClose: () => void; initialProvider?: string }) {
  const { data: catalog } = usePresets();
  const add = useAddKey();
  const test = useTestKey();
  const providers = useMemo(() => (catalog ? allProviders(catalog).filter((p) => p.needs_key) : []), [catalog]);

  const [providerId, setProviderId] = useState(initialProvider ?? "");
  const provider = findProvider(catalog, providerId);
  const [label, setLabel] = useState(provider?.display_name ?? "");
  const [envVar, setEnvVar] = useState(provider?.key_env ?? "");
  const [kind, setKind] = useState<KeyKind>(provider?.kind ?? "other");
  const [secret, setSecret] = useState("");
  const [testResult, setTestResult] = useState<ProviderTest | null>(null);

  function chooseProvider(id: string) {
    setProviderId(id);
    const p = findProvider(catalog, id);
    setLabel(p?.display_name ?? "");
    setEnvVar(p?.key_env ?? "");
    setKind(p?.kind ?? "other");
  }

  async function save() {
    setTestResult(null);
    const meta = await add.mutateAsync({
      label,
      kind,
      provider_id: providerId || null,
      env_var: envVar,
      secret,
      notes: null,
    });
    setSecret("");
    if (kind === "llm") {
      const result = await test.mutateAsync(meta.id);
      setTestResult(result);
      if (result.ok) onClose();
    } else {
      onClose();
    }
  }

  const access = provider ? accessLabel(provider) : null;

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title="Add a key"
      description="Paste a key once; it goes straight to Windows Credential Manager."
      footer={
        <>
          <Button onClick={onClose}>{testResult && !testResult.ok ? "Close" : "Cancel"}</Button>
          {!testResult && (
            <Button
              variant="primary"
              busy={add.isPending || test.isPending}
              disabled={!label.trim() || !envVar.trim() || !secret.trim()}
              onClick={() => void save().catch(() => {})}
            >
              Save key
            </Button>
          )}
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label="Service">
          {(id) => (
            <Select id={id} value={providerId} onChange={(e) => chooseProvider(e.target.value)}>
              <option value="">Other (custom)</option>
              {KIND_ORDER.map((k) => (
                <optgroup key={k} label={KIND_LABELS[k]}>
                  {providers
                    .filter((p) => p.kind === k)
                    .map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.display_name}
                      </option>
                    ))}
                </optgroup>
              ))}
            </Select>
          )}
        </Field>
        {provider && (
          <div className="flex flex-wrap items-center gap-2 text-sm">
            {access && <Badge tone={access.tone}>{access.text}</Badge>}
            {provider.signup_url && (
              <button
                type="button"
                className="inline-flex items-center gap-1 text-accent-strong hover:underline"
                onClick={() => void openExternal(provider.signup_url!)}
              >
                Get a key <ExternalLink size={13} />
              </button>
            )}
          </div>
        )}
        {provider?.privacy_note && <Notice tone="warning">{provider.privacy_note}</Notice>}
        {provider?.notes && <p className="text-xs text-muted">{provider.notes}</p>}
        <Field label="Name">{(id) => <TextInput id={id} value={label} onChange={(e) => setLabel(e.target.value)} />}</Field>
        {!provider && (
          <Field label="Type">
            {(id) => (
              <Select id={id} value={kind} onChange={(e) => setKind(e.target.value as KeyKind)}>
                {(Object.keys(KIND_LABELS) as KeyKind[]).map((k) => (
                  <option key={k} value={k}>
                    {KIND_LABELS[k]}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        )}
        <Field label="Environment variable" hint="The name generated projects use to ask for this key. The value is never written into a project.">
          {(id) => (
            <TextInput
              id={id}
              value={envVar}
              className="font-mono"
              placeholder="MY_SERVICE_API_KEY"
              onChange={(e) => setEnvVar(e.target.value.toUpperCase())}
            />
          )}
        </Field>
        <Field label="Key">
          {(id) => (
            <TextInput
              id={id}
              type="password"
              autoComplete="off"
              spellCheck={false}
              value={secret}
              onChange={(e) => setSecret(e.target.value)}
              placeholder="Paste the key"
            />
          )}
        </Field>
        {add.isError && <Notice tone="danger">{errorMessage(add.error)}</Notice>}
        {testResult && (
          <Notice tone={testResult.ok ? "success" : "danger"}>
            <span className="inline-flex items-start gap-2">
              {testResult.ok ? <CheckCircle2 size={16} /> : <XCircle size={16} />}
              <span>
                {testResult.ok ? testResult.message : `Saved, but the test failed: ${testResult.message}`}
              </span>
            </span>
          </Notice>
        )}
      </div>
    </Dialog>
  );
}

function ReplaceSecretDialog({ entry, onClose }: { entry: KeyEntry; onClose: () => void }) {
  const replace = useReplaceKeySecret();
  const [secret, setSecret] = useState("");
  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={`Replace "${entry.label}"`}
      description="The old value is overwritten in Windows Credential Manager."
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            busy={replace.isPending}
            disabled={!secret.trim()}
            onClick={() => replace.mutate({ id: entry.id, secret }, { onSuccess: onClose })}
          >
            Replace key
          </Button>
        </>
      }
    >
      <Field label="New key">
        {(id) => (
          <TextInput id={id} type="password" autoComplete="off" spellCheck={false} value={secret} onChange={(e) => setSecret(e.target.value)} />
        )}
      </Field>
      {replace.isError && (
        <div className="mt-3">
          <Notice tone="danger">{errorMessage(replace.error)}</Notice>
        </div>
      )}
    </Dialog>
  );
}
