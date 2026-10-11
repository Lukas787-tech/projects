import { FolderKanban, KeyRound, Settings } from "lucide-react";
import type { ComponentType } from "react";
import { NavLink, Outlet } from "react-router";

import { useAppInfo } from "../app/queries";

const nav: { to: string; label: string; icon: ComponentType<{ size?: number }> }[] = [
  { to: "/", label: "Projects", icon: FolderKanban },
  { to: "/keys", label: "Key Library", icon: KeyRound },
  { to: "/settings", label: "Settings", icon: Settings },
];

export function Layout() {
  const { data: info } = useAppInfo();
  return (
    <div className="flex h-full">
      <nav className="flex w-56 shrink-0 flex-col border-r border-line bg-surface px-3 py-4" aria-label="Main">
        <div className="mb-6 flex items-center gap-2 px-2">
          <img src="/icon.png" alt="" className="h-7 w-7 rounded-md" />
          <span className="text-base font-semibold">{info?.name ?? ""}</span>
        </div>
        <ul className="flex flex-col gap-1">
          {nav.map(({ to, label, icon: Icon }) => (
            <li key={to}>
              <NavLink
                to={to}
                end={to === "/"}
                className={({ isActive }) =>
                  `flex items-center gap-3 rounded-md px-2 py-2 text-sm transition-colors ${
                    isActive ? "bg-surface-2 text-text" : "text-muted hover:bg-surface-2 hover:text-text"
                  }`
                }
              >
                <Icon size={18} />
                {label}
              </NavLink>
            </li>
          ))}
        </ul>
        <div className="mt-auto px-2 text-xs text-muted">{info ? `v${info.version}` : ""}</div>
      </nav>
      <main className="min-w-0 flex-1 overflow-y-auto px-8 py-6">
        <Outlet />
      </main>
    </div>
  );
}
