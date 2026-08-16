import { useEffect, useId, useState } from "react";
import { CalendarDays } from "lucide-react";
import { cn } from "@/lib/utils";
import { Calendar } from "@/components/Calendar";
import { Popover, PopoverAnchor, PopoverContent } from "@/components/ui/popover";

/** Whether a string is a real calendar date in ISO form. Rejects 2026-02-30 as well as 2026-13-01. */
function valid(iso: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso)) {
    return false;
  }
  const [y, m, d] = iso.split("-").map(Number);
  const date = new Date(Date.UTC(y!, m! - 1, d!));
  // Round-trip check: Date rolls 2026-02-30 forward to 2026-03-02, so comparing the parts back is
  // what catches a date that parses but does not exist.
  return date.getUTCFullYear() === y && date.getUTCMonth() === m! - 1 && date.getUTCDate() === d;
}

/**
 * A date field that shows and accepts ISO `YYYY-MM-DD`, always.
 *
 * <h3>Why not `<input type="date">`</h3>
 *
 * A native date input renders in the <b>browser's</b> locale and there is no attribute that changes
 * it: the same field reads `08/12/2026` for one person and `12/08/2026` for another, while every date
 * the platform displays elsewhere — every table cell, every audit line, every export — is ISO. On a
 * board where somebody is filtering a deadline range, an ambiguous 08/12 is not a cosmetic
 * inconsistency: it silently selects the wrong three months.
 *
 * So the visible control is a text input in ISO, and it is the only format it accepts. That is a
 * defensible choice rather than an English-first one — `NFR-INT-003` requires locale-aware formatting
 * for *presentation*, and ISO 8601 is locale-neutral by construction, which is precisely what a field
 * whose value is also a URL parameter and a query predicate needs.
 *
 * <h3>The calendar is the product's own, not the browser's</h3>
 *
 * Typing eight digits is fine for someone who knows the date and awful for someone choosing one, so
 * the calendar button matters. It used to open the NATIVE picker through a hidden `type="date"` input
 * and `showPicker()`. Two things were wrong with that, and both were reported rather than noticed:
 *
 * <ul>
 *   <li>The native panel anchored to that hidden input, which had no width or height, so the browser
 *       fell back to the viewport origin and the calendar opened in the top-left corner of the screen.
 *   <li>It is browser chrome, so no stylesheet reaches it. It carried none of the platform's
 *       typography, surface or dark mode, and its cells were about two thirds the size of these.
 * </ul>
 *
 * <p>So the panel is {@link Calendar}, in a Popover anchored to the field — same surface, same tokens,
 * same dark mode, 36-pixel targets, and it works the same in every browser rather than four ways.
 * No hidden input remains.
 *
 * <h3>A half-typed date is never committed</h3>
 *
 * `onChange` fires only on a complete, real date, or on an empty field. Committing "2026" as somebody
 * types would run a query per keystroke and, on the board, push four meaningless entries into the
 * browser's history. The field shows its own invalid state in the meantime instead.
 */
export function DateField({ id, value, onChange, min, max, disabled, className,
                            placeholder = "YYYY-MM-DD" }: {
  id?: string;
  /** ISO `YYYY-MM-DD`, or empty. */
  value: string;
  /** Called with a complete valid ISO date, or with "" when the field is cleared. */
  onChange: (iso: string) => void;
  min?: string;
  max?: string;
  disabled?: boolean;
  className?: string;
  placeholder?: string;
}) {
  // Local text, so a partially typed date can exist on screen without existing in the URL.
  const [text, setText] = useState(value);
  const [open, setOpen] = useState(false);
  const fallbackId = useId();
  const inputId = id ?? fallbackId;

  // Re-sync when the value changes underneath — a preset button on the workload page, a reset on the
  // board, or the back button. Guarded so it does not fight what somebody is mid-way through typing.
  useEffect(() => {
    if (value !== text && (valid(text) || text === "")) {
      setText(value);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  // Three distinct failures, because they need three different sentences. "Use YYYY-MM-DD" in front
  // of a correctly formatted 2026-02-30 tells somebody to fix the thing that is already right, and
  // they will retype it identically and conclude the field is broken.
  const shaped = /^\d{4}-\d{2}-\d{2}$/.test(text);
  const incomplete = text !== "" && !shaped;
  const impossible = shaped && !valid(text);
  const outOfRange = valid(text) && ((min && text < min) || (max && text > max));
  const wrong = incomplete || impossible || !!outOfRange;

  function commit(next: string) {
    setText(next);
    if (next === "") {
      onChange("");
    } else if (valid(next) && !((min && next < min) || (max && next > max))) {
      onChange(next);
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      {/* Anchored to the FIELD, not to the calendar button. Anchoring to the button put its right edge
          at the panel's right edge, so a 288px panel opened leftward across the navigation sidebar and
          clipped its first column. The field's left edge is the edge a reader associates the panel
          with anyway. */}
      <PopoverAnchor asChild>
      <div className={cn("relative", className)}>
      <input
        id={inputId}
        type="text"
        inputMode="numeric"
        autoComplete="off"
        spellCheck={false}
        disabled={disabled}
        value={text}
        placeholder={placeholder}
        aria-invalid={wrong}
        // Named in the accessible description rather than only in a placeholder, which a screen
        // reader may not announce and which disappears the moment anybody types.
        aria-describedby={`${inputId}-format`}
        maxLength={10}
        onChange={(e) => {
          // Digits and hyphens only, and the hyphens are inserted rather than required. Typing
          // 20260812 produces 2026-08-12, which is what somebody reading a date off a ticket does.
          const raw = e.target.value.replace(/[^\d-]/g, "");
          const digits = raw.replace(/-/g, "").slice(0, 8);
          let out = digits;
          if (digits.length > 6) {
            out = `${digits.slice(0, 4)}-${digits.slice(4, 6)}-${digits.slice(6)}`;
          } else if (digits.length > 4) {
            out = `${digits.slice(0, 4)}-${digits.slice(4)}`;
          }
          // A trailing hyphen the person typed themselves is kept, so deleting back through the
          // separator does not immediately re-insert it and trap the caret.
          if (raw.endsWith("-") && !out.endsWith("-") && out.length < 10) {
            out += "-";
          }
          commit(out);
        }}
        onBlur={() => {
          // An incomplete entry is discarded on blur rather than left looking applied. A field
          // reading "2026-08" while the board shows unfiltered rows is a field lying about the query.
          if (text !== "" && !valid(text)) {
            setText(value);
          }
        }}
        className={cn(
          "h-9 w-full rounded-md border bg-transparent px-3 text-xs shadow-xs outline-none",
          "font-mono tabular-nums placeholder:font-sans placeholder:text-muted-foreground",
          "focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:border-ring",
          "disabled:cursor-not-allowed disabled:opacity-50",
          "pr-8",
          wrong ? "border-destructive" : "border-input")}
      />

      <span id={`${inputId}-format`} className="sr-only">
        Date in year-month-day format, for example 2026-08-12.
      </span>

      <button type="button" disabled={disabled} tabIndex={-1}
              aria-label="Open the calendar" aria-expanded={open}
              onClick={() => setOpen((o) => !o)}
              className="absolute inset-y-0 right-0 flex w-8 items-center justify-center
                         rounded-r-md text-muted-foreground hover:text-foreground
                         disabled:opacity-50">
        <CalendarDays className="size-3.5" />
      </button>

      {wrong && (
        <p role="status" className="absolute left-0 top-full pt-0.5 text-[10px] leading-tight text-destructive">
          {incomplete ? "Use YYYY-MM-DD"
            : impossible ? "That date does not exist"
            : min && text < min ? `Not before ${min}`
            : `Not after ${max}`}
        </p>
      )}
      </div>
      </PopoverAnchor>

      {/* `align="start"` from the field, so the panel opens down-and-right into the content area
          instead of leftward over the sidebar. */}
      <PopoverContent className="w-auto p-0" align="start" sideOffset={6}
                      onOpenAutoFocus={(e) => {
                        // Let the grid keep its own focus handling. Radix would otherwise focus the
                        // panel container, and the first arrow key would do nothing.
                        e.preventDefault();
                        const cell = (e.currentTarget as HTMLElement)
                          .querySelector<HTMLElement>('[role=gridcell][tabindex="0"]');
                        cell?.focus();
                      }}>
        <Calendar value={valid(text) ? text : ""} min={min} max={max}
                  onSelect={(picked) => {
                    commit(picked);
                    // Closed on choosing a day, and on nothing else. A picker that stays open after a
                    // selection makes somebody click away to confirm what they just did.
                    setOpen(false);
                  }} />
      </PopoverContent>
    </Popover>
  );
}
