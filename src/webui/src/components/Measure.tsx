import { Link } from "react-router-dom";
import { cn } from "@/lib/utils";

/**
 * A figure and the population it was computed over.
 *
 * The server sends `value: null` with a `state` whenever the measure is unmeasured, so there is no
 * number here to render by accident. This component keeps the other half of the bargain: when
 * `state` is set it renders the WORD and the action that would measure it, and no numeral appears
 * anywhere in the output — not a zero, not a dash, not an em dash a reader resolves to zero.
 *
 * PRD-UIX-022 calls rendering unmeasured as zero "the interface-layer expression of the PP-1 failure
 * the whole corpus guards against", and this is the component that either commits it or does not.
 */
export interface MeasureValue {
  key: string;
  value: number | null;
  state: string | null;
  measured: number;
  inScope: number;
  href: string;
}

const UNMEASURED_HINT: Record<string, string> = {
  UNMEASURED: "Nothing in scope has been measured yet.",
  EMPTY_NO_DATA: "Nothing in scope yet.",
  EMPTY_FILTERED: "No results for the active filters.",
};

export function MeasureCard({ measure, label, tone = "neutral", hint }: {
  measure: MeasureValue;
  label: string;
  tone?: "neutral" | "critical" | "warn" | "info" | "ok";
  hint?: string;
}) {
  const unmeasured = measure.state !== null;
  const active = !unmeasured && measure.value !== 0 && tone !== "neutral";
  return (
    <Link to={measure.href}
          className="rounded-lg border bg-card px-4 py-3 transition-colors hover:border-primary/50">
      <div className="text-xs text-muted-foreground">{label}</div>
      {unmeasured ? (
        <div className="mt-1 text-base font-medium italic text-tone-unknown">Not measured</div>
      ) : (
        <div className={cn(
          "tabular mt-1 text-2xl font-semibold tracking-tight",
          active && tone === "critical" && "text-sev-critical",
          active && tone === "warn" && "text-tone-warn",
          active && tone === "info" && "text-tone-info",
          active && tone === "ok" && "text-tone-ok",
        )}>
          {measure.value}
        </div>
      )}
      <div className="mt-0.5 text-[11px] text-muted-foreground">
        {unmeasured
          ? (UNMEASURED_HINT[measure.state!] ?? "Not measured.")
          : hint ?? `over ${measure.measured} of ${measure.inScope} in scope`}
      </div>
    </Link>
  );
}

/**
 * A coverage bar.
 *
 * An unmeasured population gets a hatched track and the word — never a 0% bar, which reads as
 * "measured, and none of it is covered". The two are different claims and the difference is the
 * whole reason this surface exists.
 */
export function CoverageBar({ label, measured, inScope, href }: {
  label: string; measured: number; inScope: number; href: string;
}) {
  const known = inScope > 0 && measured > 0;
  const percent = known ? Math.round((100 * measured) / inScope) : 0;
  const fill = percent >= 90 ? "bg-tone-ok" : percent >= 60 ? "bg-tone-warn" : "bg-sev-critical";
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline justify-between gap-2">
        <Link to={href} className="text-xs text-primary hover:underline">{label}</Link>
        {known
          ? <span className="tabular text-xs text-muted-foreground">{percent}%</span>
          : <span className="text-xs italic text-tone-unknown">Not measured</span>}
      </div>
      <div className={cn("h-2 overflow-hidden rounded-full",
                         known ? "bg-muted" : "border border-dashed border-tone-unknown/60")}>
        {known && <div className={cn("h-full rounded-full", fill)} style={{ inlineSize: `${percent}%` }} />}
      </div>
      <div className="text-[11px] text-muted-foreground">
        {inScope === 0
          ? "Nothing in scope."
          : `${measured} of ${inScope} measured`}
      </div>
    </div>
  );
}
