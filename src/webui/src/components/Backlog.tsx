import { cn } from "@/lib/utils";
import { FigureTable } from "@/components/Charts";

export interface BacklogPoint { label: string; open: number; serious: number }

const W = 680;
const H = 170;
const PAD = { top: 14, right: 14, bottom: 24, left: 34 };

/**
 * Open findings at the end of each period — the one chart that answers "better or worse".
 *
 * Everything else on this page is **activity**: findings found, requests closed, hours spent.
 * Activity rises when a team works harder and also when it simply tests more, so none of it
 * separates a programme that is reducing risk from one that is merely busy. The backlog does: it
 * falls only when things are closed faster than they arrive.
 *
 * The serious band is drawn as its own filled area inside the total rather than as a second line,
 * because the question is "how much of this backlog is the part that matters" and a reader should
 * not have to subtract two lines by eye.
 */
export function Backlog({ points, empty }: { points: BacklogPoint[]; empty: string }) {
  if (points.length === 0 || points.every((p) => p.open === 0)) {
    return <p className="text-xs italic text-tone-unknown">{empty}</p>;
  }
  const peak = Math.max(1, ...points.map((p) => p.open));
  const stepX = (W - PAD.left - PAD.right) / Math.max(1, points.length - 1);
  const x = (i: number) => PAD.left + i * stepX;
  const y = (v: number) => PAD.top + (1 - v / peak) * (H - PAD.top - PAD.bottom);
  const area = (pick: (p: BacklogPoint) => number) =>
    `M${x(0)},${y(0)} ` + points.map((p, i) => `L${x(i).toFixed(1)},${y(pick(p)).toFixed(1)}`).join(" ")
    + ` L${x(points.length - 1)},${y(0)} Z`;

  const first = points[0]!.open;
  const last = points[points.length - 1]!.open;
  const change = last - first;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span className="tabular text-2xl font-semibold">{last}</span>
        <span className="text-xs text-muted-foreground">open at the end of the window</span>
        {/* Direction in words as well as sign. A bare "+4" needs the reader to know which way is
            good, and for a backlog that is the opposite of most figures on the page. */}
        <span className={cn("text-xs font-medium",
                            change > 0 ? "text-sev-critical" : change < 0 ? "text-tone-ok"
                                                                          : "text-muted-foreground")}>
          {change === 0 ? "unchanged over the window"
            : change > 0 ? `▲ ${change} more than at the start — the backlog grew`
                         : `▼ ${Math.abs(change)} fewer than at the start — the backlog shrank`}
        </span>
      </div>

      <div className="flex flex-wrap gap-3 text-[11px] text-muted-foreground">
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block size-2.5 rounded-sm bg-tone-info/40" />All open
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block size-2.5 rounded-sm bg-sev-critical/60" />Top two severities
        </span>
      </div>

      <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img"
           aria-label={`Open findings at the end of each period, from ${points[0]!.label} to ${points[points.length - 1]!.label}. The table below carries the same figures.`}>
        <line x1={PAD.left} y1={y(0)} x2={W - PAD.right} y2={y(0)} className="stroke-border" />
        <text x={PAD.left - 5} y={y(peak) + 4} textAnchor="end"
              className="fill-muted-foreground text-[9px] tabular">{peak}</text>
        <text x={PAD.left - 5} y={y(0) + 4} textAnchor="end"
              className="fill-muted-foreground text-[9px] tabular">0</text>
        <path d={area((p) => p.open)} className="fill-tone-info/25" />
        <path d={area((p) => p.serious)} className="fill-sev-critical/45" />
        <path d={points.map((p, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(p.open).toFixed(1)}`).join(" ")}
              fill="none" className="stroke-tone-info" strokeWidth="2" />
        {points.map((p, i) => (
          <g key={p.label}>
            <circle cx={x(i)} cy={y(p.open)} r="2.5" className="fill-tone-info" />
            {i % 2 === 0 && (
              <text x={x(i)} y={H - 6} textAnchor="middle"
                    className="fill-muted-foreground text-[9px]">{p.label.slice(-5)}</text>
            )}
          </g>
        ))}
      </svg>

      <FigureTable head={["Period", "Open", "Top two severities"]}
                   rows={points.map((p) => [p.label, String(p.open), String(p.serious)])} />
    </div>
  );
}
