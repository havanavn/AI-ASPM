import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export interface TrendWeek { label: string; opened: number; closed: number }

const W = 640;
const H = 150;
const PAD = { top: 12, right: 12, bottom: 22, left: 30 };

/**
 * Findings opened against findings closed, one point per ISO week.
 *
 * Two series rather than a net figure. A net line at zero is a team closing forty findings a week
 * while forty-one arrive, and it is indistinguishable from a team doing nothing at all.
 *
 * **Colour is never the only difference between the series.** DOC-00 section 11.4 rejects colour as
 * the sole carrier of meaning, so opened is a solid line with round markers and closed is a dashed
 * line with square ones — the chart survives a monochrome print and a colour deficiency. The same
 * numbers are underneath as a table, which is also what a screen reader gets.
 */
/**
 * The x-axis label.
 *
 * A month becomes `MM/YY` — short enough that twelve of them fit and unambiguous about which year.
 * A week keeps its ISO week number, which already carries no year ambiguity in the twelve-week
 * window the weekly charts draw.
 */
function axisLabel(label: string, unit: "week" | "month"): string {
  if (unit === "month" && /^\d{4}-\d{2}$/.test(label)) {
    return `${label.slice(5)}/${label.slice(2, 4)}`;
  }
  return label.slice(5);
}

export function Trend({ weeks, measured, unit = "week" }: {
  weeks: TrendWeek[]; measured: boolean; unit?: "week" | "month";
}) {
  if (!measured || weeks.length === 0) {
    return (
      <p className="text-xs italic text-tone-unknown">
        Not measured. No finding has been recorded in scope, so there is no history to draw — this is
        not a flat line at zero.
      </p>
    );
  }

  const peak = Math.max(1, ...weeks.map((w) => Math.max(w.opened, w.closed)));
  const stepX = (W - PAD.left - PAD.right) / Math.max(1, weeks.length - 1);
  const x = (i: number) => PAD.left + i * stepX;
  const y = (v: number) => PAD.top + (1 - v / peak) * (H - PAD.top - PAD.bottom);
  const path = (pick: (w: TrendWeek) => number) =>
    weeks.map((w, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(pick(w)).toFixed(1)}`).join(" ");

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-4 text-[11px] text-muted-foreground">
        <span className="inline-flex items-center gap-1.5">
          <svg width="22" height="8" aria-hidden="true">
            <line x1="0" y1="4" x2="22" y2="4" className="stroke-sev-high" strokeWidth="2" />
            <circle cx="11" cy="4" r="2.5" className="fill-sev-high" />
          </svg>
          Opened (solid)
        </span>
        <span className="inline-flex items-center gap-1.5">
          <svg width="22" height="8" aria-hidden="true">
            <line x1="0" y1="4" x2="22" y2="4" className="stroke-tone-ok" strokeWidth="2"
                  strokeDasharray="4 3" />
            <rect x="8.5" y="1.5" width="5" height="5" className="fill-tone-ok" />
          </svg>
          Closed (dashed)
        </span>
      </div>

      <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img"
           aria-label={`Findings opened and closed per ${unit} over the last ${weeks.length} ${unit}s. The table below carries the same figures.`}>
        <line x1={PAD.left} y1={y(0)} x2={W - PAD.right} y2={y(0)} className="stroke-border" />
        <line x1={PAD.left} y1={PAD.top} x2={PAD.left} y2={y(0)} className="stroke-border" />
        <text x={PAD.left - 5} y={y(peak) + 4} textAnchor="end"
              className="fill-muted-foreground text-[9px] tabular">{peak}</text>
        <text x={PAD.left - 5} y={y(0) + 4} textAnchor="end"
              className="fill-muted-foreground text-[9px] tabular">0</text>

        <path d={path((w) => w.opened)} fill="none" className="stroke-sev-high" strokeWidth="2" />
        <path d={path((w) => w.closed)} fill="none" className="stroke-tone-ok" strokeWidth="2"
              strokeDasharray="5 4" />
        {weeks.map((w, i) => (
          <g key={w.label}>
            <circle cx={x(i)} cy={y(w.opened)} r="3" className="fill-sev-high" />
            <rect x={x(i) - 2.5} y={y(w.closed) - 2.5} width="5" height="5" className="fill-tone-ok" />
            {/* Every other label, so twelve periods do not overlap into a grey smear.
                THE YEAR IS PART OF THE LABEL. A monthly axis reading 09, 11, 01, 03 makes the
                reader work out where the year turned over, and a twelve-month window always
                spans two years — so it reads as though the chart jumps backwards in the middle. */}
            {i % 2 === 0 && (
              <text x={x(i)} y={H - 6} textAnchor="middle"
                    className="fill-muted-foreground text-[9px]">{axisLabel(w.label, unit)}</text>
            )}
          </g>
        ))}
      </svg>

      <details className="text-xs">
        <summary className="cursor-pointer text-muted-foreground hover:text-foreground">
          The same figures as a table
        </summary>
        <div className="mt-2 overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{unit === "month" ? "Month" : "Week"}</TableHead>
                <TableHead className="text-right">Opened</TableHead>
                <TableHead className="text-right">Closed</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {weeks.map((w) => (
                <TableRow key={w.label}>
                  <TableCell className="font-mono text-[11px]">{w.label}</TableCell>
                  <TableCell className="tabular text-right">{w.opened}</TableCell>
                  <TableCell className="tabular text-right">{w.closed}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </details>
    </div>
  );
}
