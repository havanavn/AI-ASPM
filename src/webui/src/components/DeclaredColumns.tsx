import { useCallback, useEffect, useMemo, useState } from "react";
import { ChevronDown, ChevronUp, Columns3, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { humanise, type FieldDefinition } from "@/components/AttributeFields";

const ANY = "__any__";

/** Values as they arrive: raw on a project, folded to a list or a sum on an application. */
export type CellValue = string | number | boolean | string[] | null | undefined;

/**
 * Which declared fields this table shows, and in what order.
 *
 * **Stored in `localStorage`, per browser, following the page-size control.** It is a display
 * preference: it changes what is easy to see and nothing about what is permitted or what the server
 * returns. Two people looking at the same estate legitimately want different columns, and a
 * tenant-wide setting would make one of them wrong.
 *
 * **The stored list is filtered against the live catalogue on every load, not trusted.** A field that
 * was deprecated, renamed away or declared on a type this reader no longer sees would otherwise
 * persist as a column header over an empty column for ever — and worse, would keep its slot in the
 * order, so the columns beside it would appear to have moved.
 */
export function useDeclaredColumns(storageKey: string, fields: FieldDefinition[]) {
  const available = useMemo(() => new Map(fields.map((f) => [f.key, f])), [fields]);

  const [chosen, setChosen] = useState<string[]>(() => {
    try {
      const raw = window.localStorage.getItem(storageKey);
      return raw ? (JSON.parse(raw) as string[]) : [];
    } catch {
      // A corrupt or hand-edited entry is not worth an error boundary. No columns is a working
      // table; a thrown exception on mount is a blank page.
      return [];
    }
  });

  // Reconciled against the catalogue, but only once it has arrived. Pruning against an empty list
  // during the first render would wipe every stored choice on every load.
  useEffect(() => {
    if (fields.length === 0) return;
    setChosen((prev) => {
      const kept = prev.filter((key) => available.has(key));
      return kept.length === prev.length ? prev : kept;
    });
  }, [fields.length, available]);

  const persist = useCallback((next: string[]) => {
    setChosen(next);
    try {
      window.localStorage.setItem(storageKey, JSON.stringify(next));
    } catch {
      // Private browsing, or a full quota. The columns still work for this session; losing the
      // preference is the smaller failure and not one worth interrupting anybody over.
    }
  }, [storageKey]);

  const columns = useMemo(
    () => chosen.map((key) => available.get(key)).filter((f): f is FieldDefinition => !!f),
    [chosen, available]);

  return {
    columns,
    chosen,
    toggle: (key: string) =>
      persist(chosen.includes(key) ? chosen.filter((k) => k !== key) : [...chosen, key]),
    move: (key: string, delta: number) => {
      const at = chosen.indexOf(key);
      const to = at + delta;
      if (at < 0 || to < 0 || to >= chosen.length) return;
      const next = [...chosen];
      const [moved] = next.splice(at, 1);
      next.splice(to, 0, moved as string);
      persist(next);
    },
    clear: () => persist([]),
  };
}

/**
 * The column picker.
 *
 * Reordering is up/down buttons rather than drag-and-drop: a drag target is unreachable from a
 * keyboard without a great deal of work, and this interface is keyboard-first by design (ADR-006).
 * Two buttons are also legible on a touch screen, which a 4-pixel drag handle is not.
 */
export function ColumnPicker({ fields, chosen, onToggle, onMove, onClear, label = "Columns" }: {
  fields: FieldDefinition[];
  chosen: string[];
  onToggle: (key: string) => void;
  onMove: (key: string, delta: number) => void;
  onClear: () => void;
  label?: string;
}) {
  if (fields.length === 0) return null;
  const byKey = new Map(fields.map((f) => [f.key, f]));
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm">
          <Columns3 className="size-3" /> {label}
          {chosen.length > 0 && <Badge tone="info">{chosen.length}</Badge>}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-80 p-0" align="end">
        {chosen.length > 0 && (
          <div className="border-b p-3">
            <div className="pb-1.5 text-[11px] uppercase tracking-wide text-muted-foreground">
              Shown, in this order
            </div>
            <div className="flex flex-col gap-1">
              {chosen.map((key, index) => (
                <div key={key} className="flex items-center gap-1 text-xs">
                  <span className="flex-1 truncate">{byKey.get(key)?.label ?? key}</span>
                  <Button size="sm" variant="ghost" aria-label={`Move ${key} earlier`}
                          disabled={index === 0} onClick={() => onMove(key, -1)}>
                    <ChevronUp className="size-3" />
                  </Button>
                  <Button size="sm" variant="ghost" aria-label={`Move ${key} later`}
                          disabled={index === chosen.length - 1} onClick={() => onMove(key, 1)}>
                    <ChevronDown className="size-3" />
                  </Button>
                  <Button size="sm" variant="ghost" aria-label={`Remove ${key}`}
                          onClick={() => onToggle(key)}>
                    <X className="size-3" />
                  </Button>
                </div>
              ))}
            </div>
            <Button size="sm" variant="ghost" className="mt-1" onClick={onClear}>Remove all</Button>
          </div>
        )}
        <div className="max-h-72 overflow-y-auto p-3">
          <div className="pb-1.5 text-[11px] uppercase tracking-wide text-muted-foreground">
            Declared fields
          </div>
          <div className="flex flex-col gap-1.5">
            {fields.map((field) => (
              <label key={field.key} className="flex items-start gap-2 text-xs">
                <Checkbox className="mt-0.5" checked={chosen.includes(field.key)}
                          onCheckedChange={() => onToggle(field.key)} />
                <span>
                  {field.label}
                  {field.purpose && (
                    <span className="block text-[11px] leading-tight text-muted-foreground">
                      {field.purpose}
                    </span>
                  )}
                </span>
              </label>
            ))}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/**
 * A filter per filterable declared field, written into the query string as `attr.<key>`.
 *
 * **Prefixed, and in the URL rather than in component state.** The prefix keeps a tenant free to
 * declare a field called `node` without it colliding with the built-in filter of that name. The URL
 * makes the filtered view a link somebody can send, which is most of what a filter is for — and it
 * is the same parameter the server reads, so the list and its total can never come from different
 * predicates.
 *
 * Only fields the catalogue marks `filterable` appear. A free-text note offered as a filter returns
 * one row and teaches people the filters do not work.
 */
export function AttributeFilters({ fields, params, onChange }: {
  fields: FieldDefinition[];
  params: URLSearchParams;
  onChange: (key: string, value: string) => void;
}) {
  const filterable = fields.filter((f) => f.filterable);
  if (filterable.length === 0) return null;
  return (
    <>
      {filterable.map((field) => {
        const value = params.get(`attr.${field.key}`) ?? "";
        const set = (next: string) => onChange(`attr.${field.key}`, next === ANY ? "" : next);

        // Endpoints. Two controls and two parameters, outside the `attr.` namespace, because an
        // environment is not a declared field — folding it in is what let a filter on one of these
        // columns be validated against the wrong catalogue and silently dropped, so the list came
        // back unfiltered while looking filtered.
        //
        // The presence control is the posture question and it is the reason this filter exists:
        // "none recorded" finds the systems whose pre-production estate nobody has inventoried,
        // which is a different and more useful answer than any hostname search (PP-1). The text box
        // is the triage question — somebody has a host from an alert.
        if (field.dataType === "HOSTNAME") {
          const environment = field.environment ?? field.key.replace(/^domain\./, "");
          const state = params.get(`hostState.${environment}`) ?? "";
          const contains = params.get(`host.${environment}`) ?? "";
          return (
            <div key={field.key} className="flex flex-col gap-1.5">
              <Label>{field.label}</Label>
              <div className="flex items-center gap-1.5">
                <Select value={state || ANY}
                        onValueChange={(next) =>
                          onChange(`hostState.${environment}`, next === ANY ? "" : next)}>
                  <SelectTrigger className="w-36"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value={ANY}>Any</SelectItem>
                    <SelectItem value="RECORDED">Recorded</SelectItem>
                    <SelectItem value="ABSENT">None recorded</SelectItem>
                  </SelectContent>
                </Select>
                <Input className="w-40" value={contains} placeholder="host contains"
                       aria-label={`${field.label} contains`}
                       onChange={(e) => onChange(`host.${environment}`, e.target.value)} />
              </div>
            </div>
          );
        }

        if (field.dataType === "SINGLE_SELECT" || field.dataType === "MULTI_SELECT") {
          return (
            <div key={field.key} className="flex flex-col gap-1.5">
              <Label>{field.label}</Label>
              <Select value={value || ANY} onValueChange={set}>
                <SelectTrigger className="w-44"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value={ANY}>Any</SelectItem>
                  {field.permittedValues.map((v) => (
                    <SelectItem key={v} value={v}>{humanise(v)}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          );
        }
        if (field.dataType === "BOOLEAN") {
          return (
            <div key={field.key} className="flex flex-col gap-1.5">
              <Label>{field.label}</Label>
              <Select value={value || ANY} onValueChange={set}>
                <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value={ANY}>Any</SelectItem>
                  <SelectItem value="true">Yes</SelectItem>
                  <SelectItem value="false">No</SelectItem>
                </SelectContent>
              </Select>
            </div>
          );
        }
        // INTEGER, TEXT and URL: an exact match, and labelled as one. A range would need a second
        // parameter and a second predicate on both pages; saying "exact" is better than a box that
        // looks like a search and behaves like an equality test.
        return (
          <div key={field.key} className="flex flex-col gap-1.5">
            <Label>{field.label} <span className="text-muted-foreground">(exact)</span></Label>
            <Input className="w-32" value={value}
                   type={field.dataType === "INTEGER" ? "number" : "text"}
                   onChange={(e) => onChange(`attr.${field.key}`, e.target.value)} />
          </div>
        );
      })}
    </>
  );
}

/**
 * One declared value in a table cell — raw on a project, folded on an application.
 *
 * A list is what an application's fold produces and what a MULTI_SELECT holds on a project; the cell
 * cannot tell them apart and does not need to. What it must not do is render an absent value as
 * anything that reads like a value: `not recorded` is the answer, and it is a different answer from
 * every other one in the column.
 */
export function AttributeCell({ field, value }: { field: FieldDefinition; value: CellValue }) {
  if (value === null || value === undefined || value === "") {
    return <span className="text-[11px] italic text-tone-unknown">not recorded</span>;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return <span className="text-[11px] italic text-tone-unknown">not recorded</span>;
    }
    // A hostname is rendered exactly as recorded. `humanise` lowercases and turns underscores into
    // spaces, which is right for a CONSTANT_CASE enum value and wrong for a host: the string has to
    // be the one somebody can paste into a browser or grep an alert for.
    if (field.dataType === "HOSTNAME") {
      return (
        <span className="flex flex-wrap gap-1">
          {value.map((v) => <Badge key={String(v)}>{String(v)}</Badge>)}
        </span>
      );
    }
    return (
      <span className="flex flex-wrap gap-1">
        {value.map((v) => <Badge key={String(v)}>{humanise(String(v))}</Badge>)}
      </span>
    );
  }
  if (typeof value === "boolean") {
    return value ? <Badge tone="ok">yes</Badge> : <Badge tone="neutral">no</Badge>;
  }
  if (typeof value === "number") return <span className="tabular text-xs">{value}</span>;
  if (field.dataType === "SINGLE_SELECT") return <Badge>{humanise(String(value))}</Badge>;
  if (field.dataType === "URL") {
    return (
      <a href={String(value)} target="_blank" rel="noreferrer noopener"
         className="break-all text-xs text-primary hover:underline">{String(value)}</a>
    );
  }
  return <span className="text-xs">{String(value)}</span>;
}
