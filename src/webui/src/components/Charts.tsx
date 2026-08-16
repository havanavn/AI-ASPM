import { cn } from "@/lib/utils";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * The chart primitives the workload dashboard is built from.
 *
 * Three rules apply to every one of them, and they are the reason these are shared components
 * rather than markup repeated per chart:
 *
 * 1. **Colour is never the only difference.** DOC-00 §11.4 rejects it, and a monochrome print or a
 *    red/green deficiency has to leave the chart readable — so series differ in line style and
 *    marker shape too, and every bar carries its number as text.
 * 2. **Nothing renders a numeral it cannot justify.** A series with no measured population renders
 *    the word, never a flat line at zero. A flat line at zero is a claim.
 * 3. **The figures are underneath as a table.** That is what a screen reader gets, what a person
 *    checking a number gets, and what survives a copy-paste into a report.
 */

/**
 * One bar. `label` is a node rather than a string so a row can carry a link to the thing it
 * describes — a figure the reader cannot open is a figure they have to take on trust, and DOC-12
 * requires drill-down for exactly that reason.
 */
export interface Slice {
  key: string; label: import("react").ReactNode; value: number; population: number;
}

/** A horizontal bar list. Better than a pie for anything above three categories. */
export function BarList({ slices, empty, tone = "info" }: {
  slices: Slice[]; empty: string; tone?: "info" | "warn" | "critical" | "ok";
}) {
  if (slices.length === 0) {
    return <p className="text-xs italic text-tone-unknown">{empty}</p>;
  }
  const peak = Math.max(1, ...slices.map((s) => s.value));
  const fill = tone === "critical" ? "bg-sev-critical" : tone === "warn" ? "bg-tone-warn"
    : tone === "ok" ? "bg-tone-ok" : "bg-tone-info";
  return (
    <div className="flex flex-col gap-2">
      {slices.map((s) => (
        <div key={s.key} className="flex flex-col gap-1">
          <div className="flex items-baseline justify-between gap-2 text-xs">
            <span className="truncate">{s.label}</span>
            {/* The number as text, always. A bar whose length is the only encoding is a bar
                somebody has to measure with their eye. */}
            <span className="tabular shrink-0 text-muted-foreground">
              {s.value}{s.population > 0 && ` of ${s.population}`}
            </span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-muted">
            <div className={cn("h-full rounded-full", fill)}
                 style={{ inlineSize: `${Math.round((100 * s.value) / peak)}%` }} />
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * The severity ramp, as fill classes, in the order severity is ranked.
 *
 * One place, so the severity mix chart, the age chart and their legends cannot drift into using
 * different colours for the same band. Severity is a STATUS scale and its colours are reserved: they
 * are never reused as "just another series" elsewhere, and every mark drawn in them carries its band
 * name as text — the colour identifies a band a reader already knows, it does not define one.
 */
export const SEVERITY_FILL: Record<string, string> = {
  CRITICAL: "bg-sev-critical",
  HIGH: "bg-sev-high",
  MEDIUM: "bg-sev-medium",
  LOW: "bg-sev-low",
  UNRATED: "bg-tone-unknown",
};

export const SEVERITY_ORDER = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "UNRATED"];

/**
 * A bar per severity band, each in its own colour, each carrying its two figures.
 *
 * Distinct from {@link BarList} because the tone is per row rather than per chart, and because the
 * denominator means something specific here: `value of population` is open of total, so a band
 * reading `0 of 0` is a band nothing has ever been found in and a band reading `0 of 40` is forty
 * findings somebody closed. A bar chart drawn on the open count alone renders those identically.
 */
export function SeverityBars({ rows, empty }: {
  rows: { code: string; open: number; total: number; aged?: number }[]; empty: string;
}) {
  const present = rows.filter((r) => r.total > 0);
  if (present.length === 0) {
    return <p className="text-xs italic text-tone-unknown">{empty}</p>;
  }
  const peak = Math.max(1, ...present.map((r) => r.total));
  return (
    <div className="flex flex-col gap-2.5">
      {present.map((r) => (
        <div key={r.code} className="flex flex-col gap-1">
          <div className="flex items-baseline justify-between gap-2 text-xs">
            <span className="font-medium">{r.code}</span>
            <span className="tabular shrink-0 text-muted-foreground">
              {r.open} open of {r.total}
              {r.aged !== undefined && r.aged > 0 && ` · ${r.aged} over 90 days`}
            </span>
          </div>
          {/* The total is the track and the open count is the fill, so the two are one bar rather
              than two the reader has to relate. The gap between them is what has been closed. */}
          <div className="h-2.5 overflow-hidden rounded-full bg-muted"
               style={{ inlineSize: `${Math.max(4, Math.round((100 * r.total) / peak))}%` }}>
            <div className={cn("h-full rounded-full", SEVERITY_FILL[r.code] ?? "bg-tone-unknown")}
                 style={{ inlineSize: `${Math.round((100 * r.open) / Math.max(1, r.total))}%` }} />
          </div>
        </div>
      ))}
      <FigureTable head={["Severity", "Open", "Total", "Open over 90 days"]}
                   rows={present.map((r) => [r.code, String(r.open), String(r.total),
                     r.aged === undefined ? "—" : String(r.aged)])} />
    </div>
  );
}

export interface StackPoint { label: string; parts: { key: string; value: number; className: string }[] }

/** A stacked column chart, for a severity mix over time. */
export function StackedColumns({ points, empty, legend }: {
  points: StackPoint[]; empty: string; legend: { key: string; label: string; className: string }[];
}) {
  const peak = Math.max(1, ...points.map((p) => p.parts.reduce((a, b) => a + b.value, 0)));
  const anything = points.some((p) => p.parts.some((x) => x.value > 0));
  if (!anything) {
    return <p className="text-xs italic text-tone-unknown">{empty}</p>;
  }
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-3 text-[11px] text-muted-foreground">
        {legend.map((l) => (
          <span key={l.key} className="inline-flex items-center gap-1.5">
            <span className={cn("inline-block size-2.5 rounded-sm", l.className)} />{l.label}
          </span>
        ))}
      </div>
      {/* items-stretch, not items-end. A percentage height needs a parent with a definite one, and
          an items-end row sizes its children to their content — so every bar resolved to zero and the
          chart rendered as a row of numbers with nothing under them. The plot area below is a flex-1
          child of a fixed-height column, which is what gives the percentages something to be a
          percentage of. */}
      <div className="flex items-stretch gap-1 overflow-x-auto pb-1" style={{ blockSize: "9rem" }}>
        {points.map((p) => {
          const total = p.parts.reduce((a, b) => a + b.value, 0);
          return (
            <div key={p.label} className="flex min-w-8 flex-1 flex-col items-center gap-1">
              <span className="tabular text-[10px] text-muted-foreground">{total || ""}</span>
              <div className="flex w-full flex-1 flex-col justify-end">
                <div className="flex w-full flex-col-reverse overflow-hidden rounded-sm"
                     style={{ blockSize: `${Math.round((100 * total) / peak)}%`,
                              minBlockSize: total ? "2px" : "0" }}>
                  {p.parts.filter((x) => x.value > 0).map((x) => (
                    <div key={x.key} className={x.className} title={`${x.key}: ${x.value}`}
                         style={{ blockSize: `${Math.round((100 * x.value) / Math.max(1, total))}%` }} />
                  ))}
                </div>
              </div>
              {/* Only a date label is shortened. Truncating an arbitrary one turned "91–180" into
                  "1–180", which is not a shorter label — it is a different number. */}
              {/* Month labels carry the year. 09, 11, 01 leaves the reader to work out where the
                  year turned over, and a twelve-month window always spans two. */}
              <span className="text-[9px] text-muted-foreground">
                {/^\d{4}-\d{2}$/.test(p.label)
                  ? `${p.label.slice(5)}/${p.label.slice(2, 4)}`
                  : /^\d{4}-\d{2}/.test(p.label) ? p.label.slice(-5) : p.label}
              </span>
            </div>
          );
        })}
      </div>
      <FigureTable
        head={["Period", ...legend.map((l) => l.label)]}
        rows={points.map((p) => [p.label, ...legend.map((l) =>
          String(p.parts.find((x) => x.key === l.key)?.value ?? 0))])} />
    </div>
  );
}

/**
 * Several measures side by side per period, each its own bar.
 *
 * Distinct from {@link StackedColumns}, and the distinction is not cosmetic. Stacking asserts that
 * the parts sum to the whole. Assessments falling DUE in a month, assessments STARTED in it and
 * assessments CLOSED in it are three counts of overlapping populations — a request can be due and
 * started and closed in the same month, and stacking them would draw it three times as a total of
 * three. Grouped bars make the comparison the reader wants (did we start what fell due?) without
 * inventing a sum that means nothing.
 */
export function GroupedColumns({ points, series, empty }: {
  points: { label: string; values: Record<string, number> }[];
  series: { key: string; label: string; className: string }[];
  empty: string;
}) {
  const peak = Math.max(1, ...points.flatMap((p) => series.map((s) => p.values[s.key] ?? 0)));
  if (!points.some((p) => series.some((s) => (p.values[s.key] ?? 0) > 0))) {
    return <p className="text-xs italic text-tone-unknown">{empty}</p>;
  }
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-3 text-[11px] text-muted-foreground">
        {series.map((s) => (
          <span key={s.key} className="inline-flex items-center gap-1.5">
            <span className={cn("inline-block size-2.5 rounded-sm", s.className)} />{s.label}
          </span>
        ))}
      </div>
      <div className="flex items-stretch gap-2 overflow-x-auto pb-1" style={{ blockSize: "9rem" }}>
        {points.map((p) => (
          <div key={p.label} className="flex min-w-10 flex-1 flex-col items-center gap-1">
            <div className="flex w-full flex-1 items-end justify-center gap-0.5">
              {series.map((s) => {
                const value = p.values[s.key] ?? 0;
                return (
                  <div key={s.key} title={`${s.label}: ${value}`}
                       className={cn("w-2 rounded-t ring-2 ring-inset ring-background", s.className)}
                       style={{ blockSize: `${Math.round((100 * value) / peak)}%`,
                                minBlockSize: value ? "2px" : "0" }} />
                );
              })}
            </div>
            <span className="text-[9px] text-muted-foreground">
              {/^\d{4}-\d{2}$/.test(p.label) ? `${p.label.slice(5)}/${p.label.slice(2, 4)}` : p.label}
            </span>
          </div>
        ))}
      </div>
      <FigureTable head={["Month", ...series.map((s) => s.label)]}
                   rows={points.map((p) => [p.label,
                     ...series.map((s) => String(p.values[s.key] ?? 0))])} />
    </div>
  );
}

/** A ratio over time — escaped against total, where a bare percentage would hide the denominator. */
export function RatioBars({ points, empty }: {
  points: { label: string; numerator: number; denominator: number }[]; empty: string;
}) {
  const measured = points.filter((p) => p.denominator > 0);
  if (measured.length === 0) {
    return <p className="text-xs italic text-tone-unknown">{empty}</p>;
  }
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-end gap-1 overflow-x-auto" style={{ blockSize: "7rem" }}>
        {points.map((p) => {
          const known = p.denominator > 0;
          const percent = known ? Math.round((100 * p.numerator) / p.denominator) : 0;
          return (
            <div key={p.label} className="flex min-w-10 flex-1 flex-col items-center gap-1">
              {/* A month with no findings is UNMEASURED, not zero percent. Nothing to escape from
                  is not the same as nothing escaping, and only one of them is good news. */}
              <span className={cn("text-[10px]", known ? "tabular text-muted-foreground"
                                                       : "italic text-tone-unknown")}>
                {known ? `${percent}%` : "—"}
              </span>
              <div className={cn("w-full rounded-t",
                                 known ? "bg-muted" : "border border-dashed border-tone-unknown/50")}
                   style={{ blockSize: "3.5rem", display: "flex", alignItems: "flex-end" }}>
                {known && (
                  <div className={cn("w-full rounded-t",
                                     percent > 0 ? "bg-sev-critical" : "bg-tone-ok/40")}
                       style={{ blockSize: `${Math.max(percent, 2)}%` }} />
                )}
              </div>
              {/* Only a date label is shortened. Truncating an arbitrary one turned "91–180" into
                  "1–180", which is not a shorter label — it is a different number. */}
              {/* Month labels carry the year. 09, 11, 01 leaves the reader to work out where the
                  year turned over, and a twelve-month window always spans two. */}
              <span className="text-[9px] text-muted-foreground">
                {/^\d{4}-\d{2}$/.test(p.label)
                  ? `${p.label.slice(5)}/${p.label.slice(2, 4)}`
                  : /^\d{4}-\d{2}/.test(p.label) ? p.label.slice(-5) : p.label}
              </span>
            </div>
          );
        })}
      </div>
      <FigureTable head={["Period", "Escaped", "Serious findings"]}
                   rows={points.map((p) => [p.label, String(p.numerator),
                     p.denominator === 0 ? "none" : String(p.denominator)])} />
    </div>
  );
}

/** The figures, as a table. Always present, collapsed. */
export function FigureTable({ head, rows }: { head: string[]; rows: string[][] }) {
  return (
    <details className="text-xs">
      <summary className="cursor-pointer text-muted-foreground hover:text-foreground">
        The same figures as a table
      </summary>
      <div className="mt-2 overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>{head.map((h) => <TableHead key={h}>{h}</TableHead>)}</TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r[0]}>
                {r.map((c, i) => (
                  <TableCell key={i} className={i === 0 ? "font-mono text-[11px]" : "tabular"}>
                    {c}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </details>
  );
}
