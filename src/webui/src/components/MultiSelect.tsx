import { useEffect, useId, useMemo, useRef, useState } from "react";
import { Check, ChevronsUpDown, Search, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

export interface MultiOption {
  id: string;
  name: string;
  /** A second line, and part of what the filter matches. */
  hint?: string;
}

/**
 * Pick any number of options from a list you can type into.
 *
 * **`null` and `[]` are different values, and the whole control turns on that.** `null` means the
 * filter is not applied — everything matches. `[]` means the person deliberately selected nothing,
 * and nothing matches. Collapsing the two is the classic defect in a filter like this: unticking the
 * last box brings the unfiltered list back, and on the assessment board that means somebody who was
 * narrowing to their own organization is shown every other one instead and reads the totals as theirs.
 * So "Clear all" produces `[]`, and the separate "Any" button produces `null`.
 *
 * **The list is what the caller can reach, not what is currently on screen.** Options derived from
 * visible rows cannot widen a selection: narrow to one item and the rest vanish from the picker.
 *
 * **The filter is a convenience, never a control.** The server scopes the option list and re-applies
 * its own predicate to the query; typing here changes what is easy to find and nothing about what is
 * permitted — product principle 4.
 *
 * Keyboard: Enter or Space opens, typing filters, Arrow keys move the active option, Enter toggles it
 * and leaves the list open (the point of a multi-select is picking several), Escape closes. The active
 * option is announced through `aria-activedescendant` so focus never leaves the text being typed.
 */
export function MultiSelect({ label, options, value, onChange, placeholder = "Any",
                              extra = [], width = "w-52" }: {
  label: string;
  options: MultiOption[];
  /** `null` — no filter. `[]` — nothing selected, so nothing matches. */
  value: string[] | null;
  onChange: (next: string[] | null) => void;
  placeholder?: string;
  /** Options that are not rows, e.g. "Unassigned" or "Not stated". Pinned to the top. */
  extra?: MultiOption[];
  width?: string;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const listId = useId();
  const inputRef = useRef<HTMLInputElement>(null);

  const all = useMemo(() => [...extra, ...options], [extra, options]);
  const shown = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return all;
    return all.filter((o) =>
      o.name.toLowerCase().includes(needle) || (o.hint ?? "").toLowerCase().includes(needle));
  }, [all, query]);

  useEffect(() => { setActive(0); }, [query, open]);
  useEffect(() => { if (open) inputRef.current?.focus(); }, [open]);

  const selected = value ?? [];
  const chosen = new Set(selected);

  function toggle(id: string) {
    const next = new Set(chosen);
    if (next.has(id)) next.delete(id); else next.add(id);
    // An emptied selection stays an empty ARRAY, not null. See the note above: null would silently
    // widen the board back to everything at the exact moment somebody was narrowing it.
    onChange([...next]);
  }

  const summary = value === null
    ? placeholder
    : selected.length === 0
      ? "None selected"
      : selected.length === 1
        ? (all.find((o) => o.id === selected[0])?.name ?? "1 selected")
        : `${selected.length} selected`;

  return (
    <div className={cn("flex flex-col gap-1", width)}>
      <span className="text-xs font-medium text-muted-foreground select-none">{label}</span>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <button type="button" role="combobox" aria-expanded={open} aria-controls={listId}
                  className={cn("flex h-9 w-full items-center justify-between gap-2 rounded-md border",
                    "border-input bg-transparent px-3 text-xs shadow-xs outline-none",
                    "focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:border-ring",
                    // Emphasised when it is actually filtering, so a narrowed board never looks like
                    // the whole board. A filter you cannot see is a filter you forget you applied.
                    value !== null && "border-primary/60 text-primary")}>
            <span className="truncate">{summary}</span>
            <ChevronsUpDown className="size-3.5 shrink-0 opacity-50" />
          </button>
        </PopoverTrigger>
        <PopoverContent className="w-72 p-0" align="start">
          <div className="flex items-center gap-2 border-b px-2.5 py-2">
            <Search className="size-3.5 shrink-0 text-muted-foreground" />
            <input ref={inputRef} value={query} onChange={(e) => setQuery(e.target.value)}
                   placeholder={`Search ${label.toLowerCase()}…`}
                   aria-controls={listId} aria-autocomplete="list"
                   aria-activedescendant={shown[active] ? `${listId}-${active}` : undefined}
                   className="w-full bg-transparent text-xs outline-none placeholder:text-muted-foreground"
                   onKeyDown={(e) => {
                     if (e.key === "ArrowDown") {
                       e.preventDefault();
                       setActive((i) => Math.min(i + 1, shown.length - 1));
                     } else if (e.key === "ArrowUp") {
                       e.preventDefault();
                       setActive((i) => Math.max(i - 1, 0));
                     } else if (e.key === "Enter") {
                       e.preventDefault();
                       const option = shown[active];
                       // Stays open. Closing on the first pick would make picking five options five
                       // round trips through the trigger.
                       if (option) toggle(option.id);
                     } else if (e.key === "Escape") {
                       setOpen(false);
                     }
                   }} />
          </div>

          {/* The three verbs, together and always visible. "Select all" over the FILTERED list is the
              useful form — search "Payments", select all, and you have every Payments project. */}
          <div className="flex items-center gap-1 border-b px-1.5 py-1.5">
            <Button size="sm" variant="ghost" className="h-6 px-1.5 text-[11px]"
                    onClick={() => onChange([...new Set([...selected,
                                                         ...shown.map((o) => o.id)])])}>
              Select all{query.trim() ? ` (${shown.length})` : ""}
            </Button>
            <Button size="sm" variant="ghost" className="h-6 px-1.5 text-[11px]"
                    onClick={() => onChange([])}>
              Clear all
            </Button>
            <span className="flex-1" />
            {/* Distinct from "Clear all", and labelled so. One matches nothing; this removes the
                filter. Two buttons because they are two different questions. */}
            <Button size="sm" variant="ghost" className="h-6 px-1.5 text-[11px]"
                    onClick={() => { onChange(null); setOpen(false); }}>
              <X className="size-3" /> No filter
            </Button>
          </div>

          <div id={listId} role="listbox" aria-multiselectable="true"
               className="max-h-64 overflow-y-auto p-1">
            {shown.length === 0 ? (
              <p className="px-2 py-3 text-xs text-muted-foreground">Nothing matches that.</p>
            ) : shown.map((option, index) => (
              <div key={option.id} id={`${listId}-${index}`} role="option"
                   aria-selected={chosen.has(option.id)}
                   onMouseEnter={() => setActive(index)}
                   onClick={() => toggle(option.id)}
                   className={cn("flex cursor-pointer items-start gap-2 rounded-sm px-2 py-1.5 text-xs",
                     index === active && "bg-accent")}>
                <span className={cn("mt-0.5 flex size-3.5 shrink-0 items-center justify-center rounded-sm border",
                  chosen.has(option.id)
                    ? "border-primary bg-primary text-primary-foreground" : "border-input")}>
                  {chosen.has(option.id) && <Check className="size-2.5" strokeWidth={3} />}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate">{option.name}</span>
                  {option.hint && (
                    <span className="block truncate text-[10px] text-muted-foreground">
                      {option.hint}
                    </span>
                  )}
                </span>
              </div>
            ))}
          </div>

          {value !== null && selected.length === 0 && (
            <p className="border-t px-2.5 py-2 text-[10px] leading-tight text-muted-foreground">
              Nothing selected, so nothing matches. That is deliberate — use <strong>No filter</strong>
              {" "}to see everything again.
            </p>
          )}
        </PopoverContent>
      </Popover>
    </div>
  );
}
