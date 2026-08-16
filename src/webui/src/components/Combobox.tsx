import { useEffect, useId, useMemo, useRef, useState } from "react";
import { Check, ChevronsUpDown, Search } from "lucide-react";
import { cn } from "@/lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

export interface ComboboxItem {
  id: string;
  name: string;
  /** Optional second line, and part of what the filter matches. */
  hint?: string;
}

/**
 * Pick one item from a list you can type into.
 *
 * A plain `<Select>` is fine for five options and unusable for fifty, and the people list grows with
 * the tenant. This is the control the request page always claimed to have: the comment beside the
 * assignment fields described "a combobox … the filtering is a plain substring match", and what was
 * there was a dropdown with no filter at all. Typing did nothing.
 *
 * **The filter is a convenience, never a control.** The list arrives from the server already scoped
 * to people this caller may name (`SEC-AUZ-016`), and the write re-reads whatever identifier is
 * submitted before using it (`SEC-AUZ-017`). Narrowing a picker client-side changes what is easy to
 * find and nothing about what is permitted — product principle 4.
 *
 * **Keyboard parity is not optional** (`PRD-UIX-013`): Enter or Space opens it, typing filters,
 * Arrow keys move the active option, Enter picks it, Escape closes without changing anything. The
 * active option is announced through `aria-activedescendant` rather than by moving focus out of the
 * input, so a screen reader reads the option while the typed text stays where it was typed.
 */
export function Combobox({ items, value, onChange, placeholder = "Nobody",
                           clearLabel = "Nobody", disabled }: {
  items: ComboboxItem[];
  value: string;
  onChange: (id: string) => void;
  placeholder?: string;
  /** The entry that sets the value back to empty. Omit by passing an empty string. */
  clearLabel?: string;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const listId = useId();
  const inputRef = useRef<HTMLInputElement>(null);

  const selected = items.find((i) => i.id === value) ?? null;

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return items;
    return items.filter((i) =>
      i.name.toLowerCase().includes(needle) || (i.hint ?? "").toLowerCase().includes(needle));
  }, [items, query]);

  // The clear entry sits at index 0 when there is one, so the arrow keys walk a single list.
  const options: (ComboboxItem | null)[] = clearLabel ? [null, ...matches] : matches;

  useEffect(() => { setActive(0); }, [query, open]);
  useEffect(() => { if (open) inputRef.current?.focus(); }, [open]);

  function pick(option: ComboboxItem | null) {
    onChange(option ? option.id : "");
    setOpen(false);
    setQuery("");
  }

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (options.length === 0) return;
      const step = event.key === "ArrowDown" ? 1 : -1;
      setActive((current) => (current + step + options.length) % options.length);
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      if (options.length > 0) pick(options[active] ?? null);
      return;
    }
    if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      setActive(event.key === "Home" ? 0 : options.length - 1);
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        disabled={disabled}
        role="combobox"
        aria-expanded={open}
        className={cn(
          "flex h-9 w-full items-center justify-between gap-2 rounded-md border border-input",
          "bg-transparent px-3 py-2 text-left text-sm",
          "focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50",
        )}
      >
        <span className={cn("truncate", !selected && "text-muted-foreground")}>
          {selected ? selected.name : placeholder}
        </span>
        <ChevronsUpDown className="size-4 shrink-0 opacity-50" />
      </PopoverTrigger>

      <PopoverContent
        className="w-[--radix-popover-trigger-width] min-w-56"
        onOpenAutoFocus={(e) => { e.preventDefault(); inputRef.current?.focus(); }}
      >
        <div className="flex items-center gap-2 border-b px-3">
          <Search className="size-4 shrink-0 text-muted-foreground" />
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder="Type to filter"
            aria-label="Type to filter"
            aria-controls={listId}
            aria-autocomplete="list"
            aria-activedescendant={options.length ? `${listId}-${active}` : undefined}
            className="h-9 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
          />
        </div>

        <ul id={listId} role="listbox" className="max-h-64 overflow-y-auto p-1">
          {options.length === 0 && (
            <li className="px-2 py-3 text-center text-xs text-muted-foreground">
              Nobody matches “{query}”.
            </li>
          )}
          {options.map((option, index) => {
            const isSelected = option ? option.id === value : value === "";
            return (
              <li key={option ? option.id : "__clear__"} id={`${listId}-${index}`} role="option"
                  aria-selected={isSelected}>
                <button
                  type="button"
                  // onMouseDown, not onClick: the input has focus and a click would blur it first,
                  // which closes the popover before the selection lands.
                  onMouseDown={(e) => { e.preventDefault(); pick(option); }}
                  onMouseEnter={() => setActive(index)}
                  className={cn(
                    "flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm",
                    index === active && "bg-accent text-accent-foreground",
                  )}
                >
                  <Check className={cn("size-4 shrink-0", isSelected ? "opacity-100" : "opacity-0")} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate">{option ? option.name : clearLabel}</span>
                    {option?.hint && (
                      <span className="block truncate text-[11px] text-muted-foreground">
                        {option.hint}
                      </span>
                    )}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </PopoverContent>
    </Popover>
  );
}
