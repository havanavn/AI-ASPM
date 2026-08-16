import { useEffect, useMemo, useRef, useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

/**
 * A month grid, in the product's own design language.
 *
 * <h3>Why this exists rather than the browser's picker</h3>
 *
 * {@link DateField} used to open the native calendar through `showPicker()`. That was cheap and it
 * looked like nothing else on the platform: the native panel is browser chrome, so no stylesheet
 * reaches it — not its typography, not its surface, not dark mode, not its cramped cell size. A
 * control that ignores the design system on the one screen somebody is concentrating hardest is a
 * control that reads as a different application.
 *
 * <p>The cells here are 36 pixels square, which is deliberately larger than the native panel's. A date
 * picker is used with a mouse by somebody scanning for a number; small targets make that a task rather
 * than a glance.
 *
 * <h3>Weeks start on Monday</h3>
 *
 * The platform's dates are ISO 8601 everywhere — stored, displayed, exported and put in URLs — and ISO
 * 8601 defines Monday as the first day of the week. A Sunday-first grid would be a second, quieter
 * locale assumption sitting underneath a field whose whole purpose is to be locale-neutral.
 *
 * <h3>Out-of-range days are shown, disabled, never hidden</h3>
 *
 * A "to" field bounded by "from" still renders the earlier days, greyed. Removing them would make the
 * grid change shape between the two fields of one range, and a reader cannot tell a day that is
 * unavailable from a day that does not exist in a month whose first row has silently moved.
 */

const WEEKDAYS = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"];

/** Splits an ISO date into parts. Returns null for anything that is not a real calendar date. */
function parts(iso: string): { y: number; m: number; d: number } | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso)) return null;
  const [y, m, d] = iso.split("-").map(Number);
  const probe = new Date(Date.UTC(y!, m! - 1, d!));
  if (probe.getUTCFullYear() !== y || probe.getUTCMonth() !== m! - 1 || probe.getUTCDate() !== d) {
    return null;
  }
  return { y: y!, m: m!, d: d! };
}

function iso(y: number, m: number, d: number): string {
  return `${String(y).padStart(4, "0")}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
}

/** Monday-based weekday index, 0..6, for the first of a month. */
function firstWeekday(y: number, m: number): number {
  return (new Date(Date.UTC(y, m - 1, 1)).getUTCDay() + 6) % 7;
}

function daysIn(y: number, m: number): number {
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

function shiftMonth(y: number, m: number, by: number): { y: number; m: number } {
  const total = y * 12 + (m - 1) + by;
  return { y: Math.floor(total / 12), m: (total % 12) + 1 };
}

const MONTHS = ["January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December"];

export function Calendar({ value, onSelect, min, max, today }: {
  /** ISO `YYYY-MM-DD`, or empty for no selection. */
  value: string;
  onSelect: (iso: string) => void;
  min?: string;
  max?: string;
  /** Injected in tests; defaults to the real today. */
  today?: string;
}) {
  const now = today ?? new Date().toISOString().slice(0, 10);
  const selected = parts(value);
  const anchor = selected ?? parts(now)!;
  const [view, setView] = useState({ y: anchor.y, m: anchor.m });
  // Follow the value when it changes from outside — typing a date in the field should move the grid to
  // it rather than leaving the panel showing a month the field no longer refers to.
  useEffect(() => {
    const next = parts(value);
    if (next) setView({ y: next.y, m: next.m });
  }, [value]);

  const [focused, setFocused] = useState<string>(value || now);
  const gridRef = useRef<HTMLDivElement>(null);

  const cells = useMemo(() => {
    const lead = firstWeekday(view.y, view.m);
    const count = daysIn(view.y, view.m);
    const previous = shiftMonth(view.y, view.m, -1);
    const next = shiftMonth(view.y, view.m, 1);
    const out: { iso: string; day: number; inMonth: boolean }[] = [];
    // The tail of the previous month, so the first row is never ragged.
    for (let i = lead - 1; i >= 0; i -= 1) {
      const day = daysIn(previous.y, previous.m) - i;
      out.push({ iso: iso(previous.y, previous.m, day), day, inMonth: false });
    }
    for (let day = 1; day <= count; day += 1) {
      out.push({ iso: iso(view.y, view.m, day), day, inMonth: true });
    }
    // Always six rows. A grid that is five rows in February and six in March makes the popover jump
    // height as somebody pages through months, and everything below it moves with it.
    let day = 1;
    while (out.length < 42) {
      out.push({ iso: iso(next.y, next.m, day), day, inMonth: false });
      day += 1;
    }
    return out;
  }, [view]);

  function blocked(candidate: string): boolean {
    return (min !== undefined && min !== "" && candidate < min)
      || (max !== undefined && max !== "" && candidate > max);
  }

  function move(days: number) {
    const p = parts(focused) ?? anchor;
    const shifted = new Date(Date.UTC(p.y, p.m - 1, p.d + days));
    const next = iso(shifted.getUTCFullYear(), shifted.getUTCMonth() + 1, shifted.getUTCDate());
    setFocused(next);
    const np = parts(next)!;
    setView({ y: np.y, m: np.m });
  }

  return (
    <div className="flex flex-col gap-2 p-3"
         onKeyDown={(e) => {
           // Arrows walk the grid, a week at a time vertically — the same movement every calendar in
           // every operating system uses, so nobody has to learn this one.
           const step = e.key === "ArrowLeft" ? -1 : e.key === "ArrowRight" ? 1
             : e.key === "ArrowUp" ? -7 : e.key === "ArrowDown" ? 7 : 0;
           if (step !== 0) {
             e.preventDefault();
             move(step);
           } else if (e.key === "PageUp" || e.key === "PageDown") {
             e.preventDefault();
             setView((v) => shiftMonth(v.y, v.m, e.key === "PageUp" ? -1 : 1));
           } else if (e.key === "Enter" || e.key === " ") {
             e.preventDefault();
             if (!blocked(focused)) onSelect(focused);
           }
         }}>
      <div className="flex items-center justify-between gap-1">
        <Button type="button" size="icon" variant="ghost" aria-label="Previous month"
                className="size-7"
                onClick={() => setView((v) => shiftMonth(v.y, v.m, -1))}>
          <ChevronLeft className="size-4" />
        </Button>
        {/* The month named in words and the year in digits. "08/2026" is one glance shorter and one
            ambiguity longer, and this panel exists because the ambiguous version was the problem. */}
        <div className="text-sm font-medium tabular">
          {MONTHS[view.m - 1]} {view.y}
        </div>
        <Button type="button" size="icon" variant="ghost" aria-label="Next month"
                className="size-7"
                onClick={() => setView((v) => shiftMonth(v.y, v.m, 1))}>
          <ChevronRight className="size-4" />
        </Button>
      </div>

      <div className="grid grid-cols-7 gap-0.5" role="grid" ref={gridRef}>
        {WEEKDAYS.map((label) => (
          <div key={label} role="columnheader"
               className="grid h-7 place-items-center text-[10px] font-medium uppercase
                          tracking-wide text-muted-foreground">
            {label}
          </div>
        ))}
        {cells.map((cell) => {
          const isSelected = cell.iso === value;
          const isToday = cell.iso === now;
          const off = blocked(cell.iso);
          return (
            <button key={cell.iso} type="button" role="gridcell" disabled={off}
                    aria-selected={isSelected}
                    aria-current={isToday ? "date" : undefined}
                    tabIndex={cell.iso === focused ? 0 : -1}
                    onFocus={() => setFocused(cell.iso)}
                    onClick={() => onSelect(cell.iso)}
                    className={cn(
                      // 36px targets. The native panel's were roughly two thirds of this, which is
                      // what made picking a date a task rather than a glance.
                      "size-9 rounded-md text-xs tabular transition-colors",
                      "focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50",
                      isSelected
                        ? "bg-primary font-semibold text-primary-foreground"
                        : off
                          ? "text-muted-foreground/40"
                          : cell.inMonth
                            ? "hover:bg-accent hover:text-accent-foreground"
                            // Outside the month: present, dimmed, still clickable. Hiding them would
                            // make the grid change shape between months.
                            : "text-muted-foreground/60 hover:bg-accent/60",
                      // Today gets a ring rather than a fill, so it never competes with the selection.
                      isToday && !isSelected && "ring-1 ring-inset ring-primary/60 font-medium",
                    )}>
              {cell.day}
            </button>
          );
        })}
      </div>

      <div className="flex items-center justify-between gap-2 border-t border-border pt-2">
        <Button type="button" size="sm" variant="ghost" disabled={blocked(now)}
                onClick={() => onSelect(now)}>
          Today
        </Button>
        {/* Clearing is part of the picker, not only of the text field. Somebody who opened the panel to
            change their mind should not have to close it and select-all-delete. */}
        <Button type="button" size="sm" variant="ghost" onClick={() => onSelect("")}>
          Clear
        </Button>
      </div>
    </div>
  );
}
