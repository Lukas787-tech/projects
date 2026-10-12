import { createHashRouter, createMemoryRouter, type RouteObject } from "react-router";

import { Layout } from "../components/Layout";
import { InterviewPage } from "../pages/InterviewPage";
import { KeysPage } from "../pages/KeysPage";
import { ProjectsPage } from "../pages/ProjectsPage";
import { SettingsPage } from "../pages/SettingsPage";
import { SetupWizard } from "../pages/SetupWizard";

const routes: RouteObject[] = [
  { path: "/setup", element: <SetupWizard /> },
  {
    path: "/",
    element: <Layout />,
    children: [
      { index: true, element: <ProjectsPage /> },
      { path: "project/:projectId", element: <InterviewPage /> },
      { path: "keys", element: <KeysPage /> },
      { path: "settings", element: <SettingsPage /> },
    ],
  },
];

// Hash routing: the Tauri asset server has no SPA fallback for deep links.
// Tests pass an initial path and get an in-memory router instead.
export function createAppRouter(initialPath?: string) {
  return initialPath ? createMemoryRouter(routes, { initialEntries: [initialPath] }) : createHashRouter(routes);
}
