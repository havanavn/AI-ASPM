import { Badge } from "@/components/ui/badge";

export interface Cadence {
  completed: number; inFlight: number; abandoned: number;
  lastAt: string | null; intervalMonths: number | null;
  nextDueAt: string | null; status: string;
}

const REVIEW_LABEL: Record<string, string> = {
  CURRENT: "Current", DUE_SOON: "Due soon", OVERDUE: "Overdue",
  NEVER: "Never reviewed", NO_OBLIGATION: "No cycle",
};

/**
 * How an application stands against its review obligation.
 *
 * NEVER is not rendered as a flavour of OVERDUE. An application that has never had a full review has
 * no elapsed interval to report, and putting it in the same bucket as one three weeks late misstates
 * both — see the view comment in V024.
 */
export function CadenceCell({ cadence }: { cadence: Cadence | null }) {
  if (!cadence) return <Badge tone="unknown">unknown</Badge>;
  const tone = cadence.status === "OVERDUE" ? "critical"
    : cadence.status === "DUE_SOON" ? "warn"
    : cadence.status === "NEVER" ? "high"
    : cadence.status === "CURRENT" ? "ok" : "unknown";
  return (
    <div className="flex flex-col gap-0.5">
      <div className="flex items-center gap-1.5">
        <span className="tabular font-semibold">{cadence.completed}</span>
        <Badge tone={tone}>{REVIEW_LABEL[cadence.status] ?? cadence.status}</Badge>
      </div>
      <div className="flex flex-wrap gap-1">
        {cadence.inFlight > 0 && <Badge tone="info">{cadence.inFlight} in flight</Badge>}
        {/* Shown, never hidden: an application where reviews keep being cancelled looks identical
            to one nobody ever scheduled unless the count is on the page. */}
        {cadence.abandoned > 0 && <Badge tone="warn">{cadence.abandoned} abandoned</Badge>}
      </div>
      {cadence.nextDueAt && (
        <span className="font-mono text-[11px] text-muted-foreground">next {cadence.nextDueAt}</span>
      )}
      {!cadence.nextDueAt && cadence.intervalMonths && (
        <span className="text-[11px] text-muted-foreground">every {cadence.intervalMonths} months</span>
      )}
    </div>
  );
}

/**
 * The risk score.
 *
 * PRD-UIX-022: an unmeasured value has NO numeral form. An application nothing has scored shows the
 * word — not a zero, and not a dash that reads as zero. A score that exists shows its coverage
 * beside it, because a score over one scanner's output and a score over full coverage are different
 * claims wearing the same number.
 */
export function ScoreCell({ value, band, coverage }: {
  value: number | null; band: string | null; coverage: string | null;
}) {
  if (value === null || value === undefined) return <Badge tone="unknown">never scored</Badge>;
  const tone = band === "CRITICAL" ? "critical" : band === "HIGH" ? "high"
    : band === "MEDIUM" ? "medium" : "low";
  return (
    <div className="flex flex-col gap-0.5">
      <div className="flex items-center gap-1.5">
        <span className="tabular font-semibold">{value}</span>
        <Badge tone={tone}>{band}</Badge>
      </div>
      {coverage && <span className="text-[11px] text-muted-foreground">coverage {coverage}</span>}
    </div>
  );
}
