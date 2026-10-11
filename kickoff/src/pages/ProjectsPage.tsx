import { Sparkles } from "lucide-react";

import { EmptyState, PageHeader } from "../components/ui";

export function ProjectsPage() {
  return (
    <>
      <PageHeader title="Projects" description="Every idea you've started, from first sentence to starter kit." />
      <EmptyState icon={<Sparkles size={36} />} title="No projects yet">
        Describe your idea in one sentence and the producer will interview you until it's concrete enough to build.
        Starting new projects arrives in the next update.
      </EmptyState>
    </>
  );
}
