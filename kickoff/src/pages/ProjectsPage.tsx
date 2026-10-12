import { ArrowRight, Sparkles, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate } from "react-router";

import type { ProjectCard } from "../bindings/ProjectCard";
import type { Stage } from "../bindings/Stage";
import { useCreateProject, useDeleteProject, useProjects } from "../app/queries";
import { Badge, Button, Dialog, Notice, Page, PageHeader, Progress } from "../components/ui";
import { errorMessage } from "../lib/ipc";

const EXAMPLES = [
  "A cozy survival game about keeping a lighthouse lit through winter",
  "A fast roguelike where you play as a swarm of bees",
  "A detective game set in a single apartment building",
];

const STAGE: Record<Stage, string> = { interview: "Interview", research: "Research", kit: "Starter kit", done: "Done" };

export function ProjectsPage() {
  const { data: projects } = useProjects();
  return (
    <Page>
      <PageHeader title="Projects" description="Every idea you've started, from first sentence to starter kit." />
      <NewIdea />
      {projects && projects.length > 0 && (
        <section className="mt-8">
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-muted">Your projects</h2>
          <ul className="flex flex-col gap-2" aria-label="Projects">
            {projects.map((card) => (
              <ProjectRow key={card.project.id} card={card} />
            ))}
          </ul>
        </section>
      )}
    </Page>
  );
}

function NewIdea() {
  const navigate = useNavigate();
  const create = useCreateProject();
  const [idea, setIdea] = useState("");

  async function start() {
    const text = idea.trim();
    if (!text) return;
    const project = await create.mutateAsync();
    navigate(`/project/${project.id}`, { state: { idea: text } });
  }

  return (
    <section className="rounded-lg border border-line bg-surface p-5">
      <label htmlFor="new-idea" className="flex items-center gap-2 text-base font-semibold">
        <Sparkles size={18} className="text-accent-strong" aria-hidden /> Start a new project
      </label>
      <p className="mt-1 text-sm text-muted">Describe your game in one sentence. The producer will interview you until it's concrete.</p>
      <textarea
        id="new-idea"
        rows={2}
        value={idea}
        onChange={(e) => setIdea(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            void start();
          }
        }}
        placeholder="A cozy survival game about…"
        className="mt-3 w-full resize-none rounded-md border border-line bg-surface-2 px-3 py-2 text-sm placeholder:text-muted focus:border-accent focus:outline-none"
      />
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Button variant="primary" busy={create.isPending} disabled={!idea.trim()} onClick={() => void start()}>
          Start interview <ArrowRight size={16} />
        </Button>
        <span className="text-xs text-muted">or try:</span>
        {EXAMPLES.map((example) => (
          <button
            key={example}
            type="button"
            onClick={() => setIdea(example)}
            className="rounded-full border border-line px-2.5 py-1 text-xs text-muted hover:border-accent hover:text-text"
          >
            {example}
          </button>
        ))}
      </div>
      {create.isError && (
        <div className="mt-3">
          <Notice tone="danger">{errorMessage(create.error)}</Notice>
        </div>
      )}
    </section>
  );
}

function ProjectRow({ card }: { card: ProjectCard }) {
  const remove = useDeleteProject();
  const [confirm, setConfirm] = useState(false);
  const { project } = card;
  return (
    <li className="flex items-center gap-4 rounded-lg border border-line bg-surface px-4 py-3 hover:border-accent">
      <Link to={`/project/${project.id}`} className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate font-medium">{project.name}</span>
          <Badge>{STAGE[project.stage]}</Badge>
        </div>
        {card.pitch && <p className="mt-0.5 truncate text-sm text-muted">{card.pitch}</p>}
        <div className="mt-2 max-w-sm">
          <Progress value={card.completeness} label={`${project.name} brief progress`} />
        </div>
      </Link>
      <Button variant="ghost" aria-label={`Delete ${project.name}`} onClick={() => setConfirm(true)}>
        <Trash2 size={14} />
      </Button>
      <Dialog
        open={confirm}
        onOpenChange={setConfirm}
        title={`Delete "${project.name}"?`}
        description="The interview, brief and references are deleted. Generated project folders on disk are not touched."
        footer={
          <>
            <Button onClick={() => setConfirm(false)}>Cancel</Button>
            <Button variant="danger" busy={remove.isPending} onClick={() => remove.mutate(project.id, { onSuccess: () => setConfirm(false) })}>
              Delete project
            </Button>
          </>
        }
      >
        <p className="text-sm">This can't be undone.</p>
      </Dialog>
    </li>
  );
}
