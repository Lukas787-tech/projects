import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type { AppSettings } from "../bindings/AppSettings";
import { ipc } from "../lib/ipc";

export const queryKeys = {
  appInfo: ["app-info"] as const,
  settings: ["settings"] as const,
  systemStatus: ["system-status"] as const,
};

export function useAppInfo() {
  return useQuery({ queryKey: queryKeys.appInfo, queryFn: ipc.appInfo, staleTime: Infinity });
}

export function useSettings() {
  return useQuery({ queryKey: queryKeys.settings, queryFn: ipc.getSettings });
}

export function useSaveSettings() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (settings: AppSettings) => ipc.saveSettings(settings),
    onSuccess: (saved) => client.setQueryData(queryKeys.settings, saved),
  });
}

export function useSystemStatus() {
  return useQuery({ queryKey: queryKeys.systemStatus, queryFn: ipc.systemStatus });
}
