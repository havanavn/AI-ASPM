import { Fragment, useMemo, useState } from "react";
import { cn } from "@/lib/utils";

/**
 * A Gantt chart of assessment work over time.
 *
 * <h2>Why the bars are facts and the plan is a projection</h2>
 *
 * There are two kinds of bar here and they must never look alike. A **request** is work that was
 * committed to — somebody raised it, it has a start and a due date. A **projection** is what the
 * tenant's own review interval implies is owed next; nobody has scheduled it and no capacity has
 * been allocated to it. Drawing the second like the first is how a policy quietly becomes a plan
 * somebody believes, and then a plan somebody is measured against.
 *
 * So a projection is drawn hatched and outlined rather than filled, which survives greyscale
 * printing and colour-vision deficiency — the two cases where a colour-only distinction fails and
 * the reader is not told it has failed.
 *
 * <h2>State is never carried by colour alone</h2>
 *
 * Every bar carries its state in its tooltip and in the table view below the chart, and the legend
 * names all four states in text. The colour makes a state findable at a glance; it does not define
 * it. Status colours here are the reserved ones (ok / info / critical / unknown) and are not reused
 * as a categorical series anywhere on this page.
 */

export interface GanttBar {
  assetId: string;
  requestId: string | null;
  code: string | null;
  label: string;
  kind: "REQUEST" | "FULL_REVIEW" | "PROJECTED" | "PLANNED";
  startAt: string;
  endAt: string;
  state: string;
  open: boolean;
  overdue: boolean;
  fullReview: boolean;
}

export interface GanttRow {
  id: string;
  name: string;
  caption?: string | null;
  href?: string;
}

/**
 * Two orthogonal channels: HUE says what kind of work a bar is, appearance says how it is going.
 *
 * <h2>Why hue moved off state</h2>
 *
 * It used to carry state — red overdue, blue running, green done. That answered the wrong question
 * first. This chart exists to plan periodic reviews, so the primary question is "which of these bars
 * is a full review", and full reviews were indistinguishable from change reviews and ad-hoc tests
 * except in a tooltip. Hue now answers that, and it is the only channel salient enough to.
 *
 * <h2>Ordinary requests are deliberately recessive</h2>
 *
 * A full review is the subject; every other request is context for it. So full reviews get a
 * saturated hue in a band no status colour owns (every status band is taken — red, orange, yellow,
 * green, cyan, blue — and borrowing one would make "full review" read as a severity), and other
 * requests get neutral grey. That contrast is deliberate weight, not an accident of what was left
 * over.
 *
 * <h2>State survives, in a channel that works without colour</h2>
 *
 * Completed is a solid bar. Running is hollow — outlined, not yet sealed. Overdue keeps the dense
 * hatch it already had, which is what made it separable under colour-vision deficiency when it was a
 * hue. Projected stays unfilled with a sparse hatch at the opposite angle.
 *
 * <p>That is four appearances that survive greyscale, so the two hues only ever have to be told apart
 * from each other — and the pair was checked at ΔE 12.8 deutan / 16.8 normal on the dark surface,
 * with the hollow-versus-solid distinction as the secondary encoding the floor band requires.
 */
const KINDS = {
  FULL_REVIEW: { label: "Full review", fill: "bg-plan-review", border: "border-plan-review" },
  REQUEST: { label: "Other assessment", fill: "bg-plan-other", border: "border-plan-other" },
} as const;

/**
 * How a bar is going. Every one of these survives greyscale; overdue ALSO carries colour.
 *
 * <h2>Why overdue gets a colour when the other states do not</h2>
 *
 * Overdue was a hatch alone, and at a bar height of ten pixels that is too quiet for the one state
 * that demands action. It now fills red as well — but the hatch STAYS, and that is the load-bearing
 * part. Simulated against deuteranopia, red against the olive of an ordinary assessment separates at
 * ΔE 2.1, which is nothing: a red-green colour-blind reader would not see the change at all. The hatch
 * is what tells them, exactly as before. The colour is added salience for everyone else, not a
 * replacement for the encoding that works.
 *
 * <p>Red against the violet of a full review is ΔE 22.6, so that pair is separable by colour too.
 */
const STATES = [
  { key: "overdue", label: "Overdue", hatch: "dense" },
  { key: "open", label: "In progress", hatch: "none" },
  { key: "closed", label: "Completed", hatch: "none" },
  { key: "projected", label: "Projected — not scheduled", hatch: "sparse" },
  // A window somebody put in the plan, as distinct from one the interval implies. The two must not
  // share a texture: "the policy says this is due" and "a person committed to this fortnight" are
  // different claims, and a planner needs to see which of the two a bar is before moving it.
  { key: "planned", label: "Planned — no request raised", hatch: "outline" },
] as const;

/** The texture for a state, as an inline style. Kept beside {@link STATES} so they cannot drift. */
function texture(hatch: "dense" | "sparse" | "none" | "outline"): Record<string, string> {
  if (hatch === "outline") {
    // Hollow: the bar's outline is the commitment, and the empty middle is the work not yet raised.
    // It reads as unfilled in greyscale and to a reader who cannot separate the fill hues at all,
    // which is the whole reason it is a shape and not a shade.
    return {
      backgroundImage: "none",
      backgroundColor: "transparent",
      borderStyle: "dashed",
      borderWidth: "1.5px",
    };
  }
  if (hatch === "dense") {
    return {
      backgroundImage:
        "repeating-linear-gradient(45deg, rgba(0,0,0,0.45) 0 1.5px, transparent 1.5px 4px)",
    };
  }
  if (hatch === "sparse") {
    return {
      backgroundImage:
        "repeating-linear-gradient(135deg, currentColor 0 2px, transparent 2px 5px)",
      color: "var(--tone-unknown)",
    };
  }
  return {};
}

function kindOf(bar: GanttBar): keyof typeof KINDS {
  return bar.fullReview || bar.kind === "FULL_REVIEW" || bar.kind === "PROJECTED"
      || bar.kind === "PLANNED"
    ? "FULL_REVIEW" : "REQUEST";
}

function stateOf(bar: GanttBar): (typeof STATES)[number]["key"] {
  if (bar.kind === "PLANNED") return "planned";
  if (bar.kind === "PROJECTED") return "projected";
  if (bar.overdue) return "overdue";
  return bar.open ? "open" : "closed";
}

const DAY = 86_400_000;

/** How far past today the axis always reaches. Two quarters is a planning horizon people use. */
const LOOK_AHEAD_MONTHS = 6;

function parse(value: string): number {
  const at = Date.parse(value + "T00:00:00Z");
  return Number.isNaN(at) ? 0 : at;
}

function monthTicks(from: number, to: number): { at: number; label: string }[] {
  const ticks: { at: number; label: string }[] = [];
  const cursor = new Date(from);
  cursor.setUTCDate(1);
  cursor.setUTCHours(0, 0, 0, 0);
  // Guarded rather than while(true): a bad date range must render a poor chart, not hang the tab.
  for (let i = 0; i < 240 && cursor.getTime() <= to; i += 1) {
    if (cursor.getTime() >= from) {
      ticks.push({
        at: cursor.getTime(),
        // The year is on every January and on the first tick. Month-only labels were the defect
        // reported on the trend charts — "9, 11, 1, 3" reads as a sequence until it wraps.
        label: `${String(cursor.getUTCMonth() + 1).padStart(2, "0")}/${String(
          cursor.getUTCFullYear(),
        ).slice(2)}`,
      });
    }
    cursor.setUTCMonth(cursor.getUTCMonth() + 1);
  }
  return ticks;
}

export function Gantt({ rows, bars, today, empty, onSelect, selected }: {
  rows: GanttRow[];
  bars: GanttBar[];
  today?: number;
  empty: string;
  onSelect?: (id: string) => void;
  selected?: string | null;
}) {
  const now = today ?? Date.now();
  /**
   * The row the pointer is over.
   *
   * Held in state rather than done with CSS, because a row here is TWO grid cells — the label and the
   * track — and they are siblings with no element wrapping them. `:hover` on a parent cannot reach
   * across that, and wrapping each row in its own element would give every row its own column widths,
   * which is the thing the single flat grid exists to prevent.
   *
   * Cleared on leaving the whole grid rather than on leaving each cell, so crossing the boundary
   * between a row's label and its track does not flicker.
   */
  const [hovered, setHovered] = useState<string | null>(null);

  /**
   * The window the axis covers.
   *
   * **Driven by real work, not by projections.** A single projection eighteen months out would
   * otherwise stretch the axis to cover it and squeeze every actual assessment into the left third
   * of the chart — which is exactly what the first build of this page did. The window is set by the
   * REQUEST bars, then extended to a bounded look-ahead so near-term projections still land inside
   * it. A projection beyond that edge is clipped and flagged rather than allowed to rescale
   * everything behind it.
   */
  const span = useMemo(() => {
    const real = bars.filter((b) => b.kind !== "PROJECTED")
      .flatMap((b) => [parse(b.startAt), parse(b.endAt)]).filter((s) => s > 0);
    const any = bars.flatMap((b) => [parse(b.startAt), parse(b.endAt)]).filter((s) => s > 0);
    if (any.length === 0) return null;
    const stamps = real.length > 0 ? real : any;
    const from = new Date(Math.min(now, ...stamps));
    from.setUTCDate(1);
    const to = new Date(Math.max(now, ...stamps));
    to.setUTCMonth(to.getUTCMonth() + 1, 1);
    // At least two quarters ahead of today, so the planning half of the chart has room even when
    // every request in the data has already closed.
    const horizon = new Date(now);
    horizon.setUTCMonth(horizon.getUTCMonth() + LOOK_AHEAD_MONTHS, 1);
    return { from: from.getTime(), to: Math.max(to.getTime(), horizon.getTime()) };
  }, [bars, now]);

  const byRow = useMemo(() => {
    const map = new Map<string, GanttBar[]>();
    for (const bar of bars) {
      const list = map.get(bar.assetId);
      if (list) list.push(bar);
      else map.set(bar.assetId, [bar]);
    }
    return map;
  }, [bars]);

  if (!span || rows.length === 0) {
    return <p className="text-xs italic text-tone-unknown">{empty}</p>;
  }
  const width = Math.max(DAY, span.to - span.from);
  const pct = (at: number) => ((at - span.from) / width) * 100;
  const allTicks = monthTicks(span.from, span.to);
  // Every month up to about eighteen; beyond that the labels collide and the axis becomes a smear.
  // Gridlines still fall on every month — only the LABELS thin out, so position stays readable.
  const every = allTicks.length <= 18 ? 1 : allTicks.length <= 36 ? 2 : 3;
  const ticks = allTicks.filter((_, i) => i % every === 0);

  return (
    <div className="flex flex-col gap-3">
      <div className="overflow-x-auto">
        {/* A minimum width rather than a fluid one: below about a pixel a day the bars collapse into
            each other and the chart says nothing it could not have said as a list. Scrolling is the
            honest outcome — a squashed Gantt is a Gantt nobody can read. */}
        <div className="min-w-[46rem]">
          {/* No column gap. The row tint spans both cells, and a gap would leave an unpainted stripe
              down the middle of every highlighted row. The spacing it provided is now padding inside
              the label cell, which the tint covers. */}
          <div className="grid grid-cols-[minmax(9rem,14rem)_1fr]"
               onMouseLeave={() => setHovered(null)}>
            {/* Axis */}
            <div />
            <div className="relative h-5 border-b border-border">
              {ticks.map((tick) => {
                const at = pct(tick.at);
                // Centred on its gridline, except at the ends, where half the label would hang
                // outside the plot and be clipped — the last month read "02/" in the first build.
                const anchor = at > 96 ? "-100%" : at < 2 ? "0%" : "-50%";
                return (
                  <span key={tick.at}
                        className="absolute top-0 text-[10px] tabular text-muted-foreground"
                        style={{ insetInlineStart: `${at}%`,
                                 transform: `translateX(${anchor})` }}>
                    {tick.label}
                  </span>
                );
              })}
            </div>

            {rows.map((row) => {
              const mine = byRow.get(row.id) ?? [];
              const active = selected === row.id;
              // Selected wins over hover, and they are different hues rather than two strengths of
              // one — otherwise "I am pointing at this" and "this one is pinned" become the same
              // signal at slightly different opacity, which is no signal.
              const tint = active ? "bg-primary/5"
                : hovered === row.id ? "bg-foreground/[0.055]" : "";
              return (
                <Fragment key={row.id}>
                  <button type="button"
                          onClick={() => onSelect?.(row.id)}
                          onMouseEnter={() => setHovered(row.id)}
                          // Focus does what hover does, so the row a keyboard user is on is as
                          // findable as the row a mouse is on.
                          onFocus={() => setHovered(row.id)}
                          className={cn(
                            "flex flex-col items-start gap-0 truncate border-b border-border/50 py-1.5 pr-3 pl-1 text-left text-xs transition-colors",
                            tint,
                            active ? "font-medium text-primary" : "hover:text-primary",
                          )}>
                    <span className="w-full truncate">{row.name}</span>
                    {row.caption && (
                      <span className="w-full truncate text-[10px] text-muted-foreground">
                        {row.caption}
                      </span>
                    )}
                  </button>
                  <div className={cn("relative border-b border-border/50 py-1.5 transition-colors",
                                     tint)}
                       onMouseEnter={() => setHovered(row.id)}
                       style={{ minBlockSize: "1.75rem" }}>
                    {/* Month gridlines, recessive. They are the reason a bar's position can be read
                        against a date at all, and they are drawn under the marks. */}
                    {allTicks.map((tick) => (
                      <span key={tick.at} aria-hidden
                            className="absolute inset-y-0 w-px bg-border/40"
                            style={{ insetInlineStart: `${pct(tick.at)}%` }} />
                    ))}
                    <span aria-hidden className="absolute inset-y-0 w-px bg-sev-high/70"
                          style={{ insetInlineStart: `${pct(now)}%` }} />
                    {mine.map((bar, index) => {
                      const state = stateOf(bar);
                      const style = STATES.find((s) => s.key === state) ?? STATES[3];
                      const kind = KINDS[kindOf(bar)];
                      const rawStart = parse(bar.startAt);
                      const rawEnd = Math.max(parse(bar.endAt), rawStart + DAY);
                      // Entirely past the right edge — a projection beyond the horizon. Drawn as a
                      // marker AT the edge rather than dropped: "nothing here" and "something out
                      // past the edge of this chart" are different facts.
                      const beyond = rawStart > span.to;
                      const start = Math.max(rawStart, span.from);
                      const end = Math.min(rawEnd, span.to);
                      const left = beyond ? 99.2 : pct(start);
                      const size = beyond ? 0.8 : Math.max(0.6, pct(end) - left);
                      const projected = state === "projected";
                      const running = state === "open";
                      const overdue = state === "overdue";
                      return (
                        <span key={(bar.requestId ?? "projected") + index}
                              title={`${bar.code ? bar.code + " · " : ""}${bar.label}\n`
                                + `${bar.startAt} → ${bar.endAt}\n`
                                + `${kind.label} · ${style.label}`
                                + (beyond ? "\nbeyond the right edge of this chart" : "")}
                              className={cn(
                                // 4px rounded ends, a 2px surface ring so overlapping bars stay
                                // countable, and a full stop at 0.6% so a one-day bar is still a mark.
                                // Overdue sits two pixels taller. Size is a third cue on top of
                                // colour and texture, and it survives both greyscale and a reader who
                                // has turned colour off entirely.
                                "absolute block rounded ring-2 ring-inset ring-background",
                                overdue ? "h-3" : "h-2.5",
                                // Hue is the KIND. Appearance is the state: solid means finished,
                                // hollow means still running, and both read the same in greyscale.
                                projected
                                  ? cn("border border-dashed bg-transparent", kind.border)
                                  // Red fill, and the KIND moves to the border so it is not lost.
                                  // Overdue outranks kind here: when something is late, "it is late"
                                  // is the fact somebody needs first, and which sort of review it is
                                  // stays recoverable from the border, the tooltip and the table.
                                  : overdue
                                    ? cn("bg-sev-critical border-2", kind.border)
                                    : running
                                      ? cn("border-2 bg-transparent", kind.border)
                                      : kind.fill,
                              )}
                              style={{
                                insetInlineStart: `${left}%`,
                                inlineSize: `${size}%`,
                                insetBlockStart: `${0.45 + (index % 2) * 0.75}rem`,
                                ...texture(style.hatch),
                              }} />
                      );
                    })}
                    {mine.length === 0 && (
                      // An empty row would read as a quiet period. It is not: it is an application
                      // with no assessment on record at all, which is the row most worth seeing.
                      <span className="absolute inset-y-0 left-0 flex items-center text-[10px] italic text-tone-unknown">
                        no assessment on record in this window
                      </span>
                    )}
                  </div>
                </Fragment>
              );
            })}
          </div>
        </div>
      </div>

      {/* Two groups, not one row of six swatches. Kind and state are separate channels now, and a
          combined legend would invite the reader to look for a "completed full review" colour that
          does not exist — because completeness is not a colour here. */}
      <div className="flex flex-wrap items-start gap-x-8 gap-y-2 text-[11px] text-muted-foreground">
        <div className="flex flex-col gap-1">
          <span className="font-medium text-foreground">Kind</span>
          <div className="flex flex-wrap gap-x-3 gap-y-1">
            {Object.entries(KINDS).map(([key, kind]) => (
              <span key={key} className="flex items-center gap-1.5">
                <span className={cn("h-2.5 w-5 rounded", kind.fill)} />
                {kind.label}
              </span>
            ))}
          </div>
        </div>
        <div className="flex flex-col gap-1">
          <span className="font-medium text-foreground">State</span>
          <div className="flex flex-wrap gap-x-3 gap-y-1">
            {/* Drawn in the FOREGROUND ink, not in either kind's hue. A state demonstrated in a
                kind colour is a swatch that says two things: the first version of this legend showed
                "Completed" as a grey box and "Other assessment" as the same grey box, in adjacent
                groups, which made the legend actively misleading rather than merely dense. */}
            <span className="flex items-center gap-1.5">
              <span className="h-2.5 w-5 rounded bg-foreground/50" /> Completed
            </span>
            <span className="flex items-center gap-1.5">
              <span className="h-2.5 w-5 rounded border-2 border-foreground/60 bg-transparent" />
              In progress
            </span>
            {/* The one state swatch that is NOT drawn in foreground ink, because overdue is the one
                state that carries a colour on the chart. Shown hatched as well as red, so the legend
                shows both cues rather than teaching a reader to look only for the colour. */}
            <span className="flex items-center gap-1.5">
              <span className="h-3 w-5 rounded bg-sev-critical"
                    style={texture("dense")} />
              <span className="font-medium text-sev-critical">Overdue</span>
            </span>
            <span className="flex items-center gap-1.5">
              <span className="h-2.5 w-5 rounded border border-dashed border-foreground/50
                               bg-transparent" style={texture("sparse")} />
              Projected — not scheduled
            </span>
            <span className="flex items-center gap-1.5">
              <span className="h-3 w-px bg-sev-high" /> today
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
