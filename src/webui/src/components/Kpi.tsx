import { cn } from "@/lib/utils";

/**
 * One headline figure.
 *
 * <p>`tone` is applied only when the value is non-zero. Zero overdue requests is good news and must
 * not be painted in the colour of bad news — a dashboard where every tile is red at all times is a
 * dashboard people stop reading.
 */
export function Kpi({ label, value, tone = "neutral", hint }: {
  label: string;
  value: string | number;
  tone?: "neutral" | "critical" | "warn" | "info" | "ok";
  hint?: string;
}) {
  const active = value !== 0 && value !== "0" && tone !== "neutral";
  return (
    <div className="rounded-lg border bg-card px-4 py-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div
        className={cn(
          "tabular mt-1 text-2xl font-semibold tracking-tight",
          active && tone === "critical" && "text-sev-critical",
          active && tone === "warn" && "text-tone-warn",
          active && tone === "info" && "text-tone-info",
          active && tone === "ok" && "text-tone-ok",
        )}
      >
        {value}
      </div>
      {hint && <div className="mt-0.5 text-[11px] text-muted-foreground">{hint}</div>}
    </div>
  );
}
