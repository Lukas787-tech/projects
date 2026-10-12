import { AlertTriangle, OctagonAlert } from "lucide-react";

/** Fraction of a limit at which a meter turns to warning / danger. */
export const WARN_AT = 0.8;
export const DANGER_AT = 0.95;

const levels = {
  ok: { fill: "bg-accent", track: "bg-accent/15", label: null, icon: null },
  warning: { fill: "bg-warning", track: "bg-warning/15", label: "Near limit", icon: AlertTriangle },
  danger: { fill: "bg-danger", track: "bg-danger/15", label: "At limit", icon: OctagonAlert },
} as const;

function compact(n: number) {
  return new Intl.NumberFormat(undefined, { notation: "compact", maximumFractionDigits: 1 }).format(n);
}

/**
 * A usage-against-limit meter. The fill colour carries severity; the track is
 * a lighter step of the same colour, and warning states always add an icon
 * and a word so state never depends on colour alone. Without a known limit it
 * shows the count only.
 */
export function Meter({ label, value, limit, unit }: { label: string; value: number; limit: number | null; unit: string }) {
  if (!limit) {
    return (
      <div className="text-sm">
        <span className="text-muted">{label}: </span>
        <span className="tabular-nums">{compact(value)}</span> {value === 1 ? unit.replace(/s$/, "") : unit}
        <span className="text-muted"> · no published limit</span>
      </div>
    );
  }
  const ratio = value / limit;
  const level = ratio >= DANGER_AT ? levels.danger : ratio >= WARN_AT ? levels.warning : levels.ok;
  const Icon = level.icon;
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="text-muted">{label}</span>
        <span className="inline-flex items-center gap-1.5">
          {Icon && <Icon size={14} aria-hidden className={ratio >= DANGER_AT ? "text-danger" : "text-warning"} />}
          {level.label && <span className="font-medium">{level.label}</span>}
          <span className="tabular-nums">
            {compact(value)} / {compact(limit)}
          </span>
          <span className="text-muted">{unit}</span>
        </span>
      </div>
      <div
        role="meter"
        aria-label={`${label}: ${value} of ${limit} ${unit}`}
        aria-valuemin={0}
        aria-valuemax={limit}
        aria-valuenow={Math.min(value, limit)}
        className={`h-1.5 overflow-hidden rounded-full ${level.track}`}
      >
        <div className={`h-full rounded-full ${level.fill}`} style={{ width: `${Math.min(100, ratio * 100)}%` }} />
      </div>
    </div>
  );
}
