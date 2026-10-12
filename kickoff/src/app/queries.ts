import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type { AiSettings } from "../bindings/AiSettings";
import type { AppSettings } from "../bindings/AppSettings";
import type { NewKeyInput } from "../bindings/NewKeyInput";
import { ipc } from "../lib/ipc";

export const queryKeys = {
  appInfo: ["app-info"] as const,
  settings: ["settings"] as const,
  systemStatus: ["system-status"] as const,
  keys: ["keys"] as const,
  presets: ["presets"] as const,
  aiSettings: ["ai-settings"] as const,
  models: (providerId: string) => ["models", providerId] as const,
  ollama: ["ollama"] as const,
  usage: ["usage-today"] as const,
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

export function usePresets() {
  return useQuery({ queryKey: queryKeys.presets, queryFn: ipc.listPresets, staleTime: Infinity });
}

export function useKeys() {
  return useQuery({ queryKey: queryKeys.keys, queryFn: ipc.listKeys });
}

/** Invalidate everything that depends on which keys exist. */
function useKeysChanged() {
  const client = useQueryClient();
  return () => {
    void client.invalidateQueries({ queryKey: queryKeys.keys });
    void client.invalidateQueries({ queryKey: ["models"] });
  };
}

export function useAddKey() {
  const changed = useKeysChanged();
  return useMutation({ mutationFn: (input: NewKeyInput) => ipc.addKey(input), onSuccess: changed });
}

export function useDeleteKey() {
  const changed = useKeysChanged();
  return useMutation({ mutationFn: (id: string) => ipc.deleteKey(id), onSuccess: changed });
}

export function useReplaceKeySecret() {
  const changed = useKeysChanged();
  return useMutation({
    mutationFn: ({ id, secret }: { id: string; secret: string }) => ipc.replaceKeySecret(id, secret),
    onSuccess: changed,
  });
}

export function useTestKey() {
  const changed = useKeysChanged();
  return useMutation({ mutationFn: (id: string) => ipc.testKey(id), onSuccess: changed });
}

export function useAiSettings() {
  return useQuery({ queryKey: queryKeys.aiSettings, queryFn: ipc.getAiSettings });
}

export function useSaveAiSettings() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (settings: AiSettings) => ipc.saveAiSettings(settings),
    onSuccess: (saved) => {
      client.setQueryData(queryKeys.aiSettings, saved);
      void client.invalidateQueries({ queryKey: queryKeys.usage });
    },
  });
}

export function useModels(providerId: string | null) {
  return useQuery({
    queryKey: queryKeys.models(providerId ?? ""),
    queryFn: () => ipc.listModels(providerId!),
    enabled: !!providerId,
    staleTime: 10 * 60 * 1000,
  });
}

export function useOllama() {
  return useQuery({ queryKey: queryKeys.ollama, queryFn: ipc.detectOllama });
}

export function useUsageToday() {
  return useQuery({ queryKey: queryKeys.usage, queryFn: ipc.usageToday, refetchInterval: 30_000 });
}
