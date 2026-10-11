import { KeyRound } from "lucide-react";

import { useSystemStatus } from "../app/queries";
import { EmptyState, Notice, PageHeader } from "../components/ui";

export function KeysPage() {
  const { data: status } = useSystemStatus();
  return (
    <>
      <PageHeader
        title="Key Library"
        description="API keys for AI, voice, search and asset services. Stored in Windows Credential Manager, never in project folders."
      />
      {status?.secret_store_problem && (
        <div className="mb-4">
          <Notice tone="warning">Keys can't be saved on this system: {status.secret_store_problem}</Notice>
        </div>
      )}
      <EmptyState icon={<KeyRound size={36} />} title="No keys yet">
        Every feature works with free services. When you add a key it stays in the Windows credential store, and generated
        projects reference it by name only.
      </EmptyState>
    </>
  );
}
