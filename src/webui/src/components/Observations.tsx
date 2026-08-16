import { Link } from "react-router-dom";
import { AlertTriangle, ArrowRight, CircleHelp, Eye, ShieldCheck } from "lucide-react";
import { cn } from "@/lib/utils";

export interface Observation {
  code: string;
  level: "ACT_NOW" | "WATCH" | "HEALTHY" | "UNMEASURED";
  headline: string;
  detail: string;
  evidence: { label: string; value: number }[];
  href: string;
  /** What produced this. A rule today, a model later — see OverviewInsights. */
  basis: string;
}

const STYLE = {
  ACT_NOW:    { icon: AlertTriangle, ring: "border-sev-critical/40", dot: "bg-sev-critical",
                word: "Act now" },
  WATCH:      { icon: Eye,           ring: "border-tone-warn/40",    dot: "bg-tone-warn",
                word: "Watch" },
  UNMEASURED: { icon: CircleHelp,    ring: "border-tone-unknown/40", dot: "bg-tone-unknown",
                word: "Not measured" },
  HEALTHY:    { icon: ShieldCheck,   ring: "border-tone-ok/40",      dot: "bg-tone-ok",
                word: "Healthy" },
} as const;

/**
 * What the estate is telling you, in sentences.
 *
 * A count asks its reader to do the analysis; "31 serious findings sit on internet-facing,
 * business-critical systems, 20 of them open over ninety days" has already done it. The platform
 * holds every term in that sentence, so composing it is the platform's job.
 *
 * **Every card shows the numbers it was derived from, and what derived it.** That is not decoration.
 * The analysis agent will eventually write these instead of a rule set, and the moment a reader
 * cannot ask "why does it say that" the whole panel becomes something to be argued with rather than
 * acted on. The evidence row and the basis line keep the answer on the card.
 */
export function Observations({ items }: { items: Observation[] }) {
  if (items.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-col gap-3">
      {items.map((o) => {
        const style = STYLE[o.level] ?? STYLE.WATCH;
        const Icon = style.icon;
        return (
          <Link key={o.code} to={o.href}
                className={cn("group flex gap-3 rounded-lg border bg-card p-4 transition-colors",
                              style.ring, "hover:border-primary/60")}>
            <Icon className={cn("mt-0.5 size-5 shrink-0",
                                o.level === "ACT_NOW" ? "text-sev-critical"
                                : o.level === "WATCH" ? "text-tone-warn"
                                : o.level === "HEALTHY" ? "text-tone-ok" : "text-tone-unknown")} />
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-baseline gap-x-2">
                {/* The word as well as the colour and the icon. Three carriers, because a
                    monochrome print or a colour deficiency must leave the urgency readable. */}
                <span className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                  {style.word}
                </span>
                <span className="text-sm font-medium">{o.headline}</span>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">{o.detail}</p>
              {o.evidence.length > 0 && (
                <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
                  {o.evidence.map((e) => (
                    <span key={e.label} className="text-[11px] text-muted-foreground">
                      <span className="tabular font-semibold text-foreground">{e.value}</span>{" "}
                      {e.label.toLowerCase()}
                    </span>
                  ))}
                </div>
              )}
              {/* Provenance, deliberately small and deliberately always there. */}
              <div className="mt-1.5 text-[10px] text-muted-foreground/70">
                derived by {o.basis}
              </div>
            </div>
            <ArrowRight className="mt-0.5 size-4 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
          </Link>
        );
      })}
    </div>
  );
}
