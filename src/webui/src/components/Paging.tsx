import { useEffect, useMemo, useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

/**
 * Paging for the tables in this interface.
 *
 * <h2>What this pages, and what it deliberately does not</h2>
 *
 * The rows are sliced **after** they arrive. That is a decision rather than a shortcut, and the
 * reason is that every one of these tables sits under figures counted over the whole population:
 * "63 open findings", "208 assessment requests", "9 never fully reviewed". If paging moved to the
 * server the naive result is a page of rows beside a headline that silently became the headline of
 * that page — and a summary that disagrees with the list it sits on is the defect a reader notices
 * first, without being able to tell which of the two is lying.
 *
 * The cost is stated rather than hidden: the payload still carries every row, so this makes a long
 * table **readable**, not cheap. The one table already paged at the server —
 * {@code GET /api/ui/projects/{id}/requests} — stays that way and uses {@link PageSize} for its own
 * control, so the reader sees one thing whichever side the slicing happens on.
 *
 * <h2>The page size is a preference, not a per-table setting</h2>
 *
 * Chosen once and remembered, because somebody who wants a hundred rows wants a hundred rows on
 * every table, and re-choosing it on each page is the interface making them repeat themselves.
 * Stored in {@code localStorage}: it is a display preference and it carries no tenant data, so it
 * does not belong in the session or on the server.
 */

/** The offered sizes. Twenty is the default because it fits a laptop screen without scrolling. */
export const PAGE_SIZES = [20, 50, 100] as const;

const STORAGE_KEY = "aspm.rowsPerPage";

function storedSize(): number {
  // A stored value that is no longer offered falls back rather than being honoured. Reading a
  // number out of localStorage and trusting it is how a stale key turns into a table showing three
  // rows, with nothing on the page explaining why.
  try {
    const raw = Number(window.localStorage.getItem(STORAGE_KEY));
    return (PAGE_SIZES as readonly number[]).includes(raw) ? raw : PAGE_SIZES[0];
  } catch {
    return PAGE_SIZES[0];
  }
}

export interface Paging<T> {
  /** The rows for the current page. */
  rows: T[];
  page: number;
  setPage: (page: number) => void;
  size: number;
  setSize: (size: number) => void;
  pages: number;
  /** Rows in the whole set, not on this page. */
  total: number;
  /** 1-based index of the first row shown; 0 when the set is empty. */
  from: number;
  /** 1-based index of the last row shown. */
  to: number;
}

/**
 * Slices a list into pages.
 *
 * @param all every row the table has, already filtered. Filtering happens BEFORE paging — a filter
 *     that only searched the current page would be a search that finds less the further you read.
 */
export function usePaging<T>(all: T[], initialSize?: number): Paging<T> {
  // A caller may ask for a smaller page than the shared default. The project tree does: one of its
  // "rows" is a project with its repositories under it, so twenty of them is a hundred lines and a
  // page nobody reaches the bottom of. The stored preference still wins once somebody sets one.
  const [size, setSizeState] = useState(initialSize ?? storedSize);
  const [page, setPage] = useState(0);

  const total = all.length;
  const pages = Math.max(1, Math.ceil(total / size));

  // A filter that shortens the list can leave the reader on a page that no longer exists, looking at
  // an empty table with rows behind it. Snap back rather than render nothing.
  useEffect(() => {
    if (page > pages - 1) {
      setPage(0);
    }
  }, [page, pages]);

  function setSize(next: number) {
    setSizeState(next);
    setPage(0);
    try {
      window.localStorage.setItem(STORAGE_KEY, String(next));
    } catch {
      // A browser refusing storage is not a reason to refuse the change. The size still applies for
      // this visit; it just is not remembered for the next one.
    }
  }

  const safePage = Math.min(page, pages - 1);
  const rows = useMemo(() => all.slice(safePage * size, safePage * size + size),
    [all, safePage, size]);

  return {
    rows, page: safePage, setPage, size, setSize, pages, total,
    from: total === 0 ? 0 : safePage * size + 1,
    to: Math.min(total, (safePage + 1) * size),
  };
}

/**
 * The rows-per-page control on its own.
 *
 * Separate from {@link Pager} so a table paged at the server can carry the same control without
 * pretending to be paged in the browser.
 */
export function PageSize({ size, onChange }: { size: number; onChange: (size: number) => void }) {
  return (
    <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
      Rows
      <Select value={String(size)} onValueChange={(v) => onChange(Number(v))}>
        <SelectTrigger className="h-7 w-[4.5rem] text-xs" aria-label="Rows per page">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {PAGE_SIZES.map((s) => <SelectItem key={s} value={String(s)}>{s}</SelectItem>)}
        </SelectContent>
      </Select>
    </label>
  );
}

/**
 * The footer under a paged table: what is being shown, out of how many, and the way to move.
 *
 * <p>"Showing 21–40 of 208" rather than "Page 2 of 11". The row numbers are the question somebody
 * scanning a long table actually has, and the page number is an implementation detail of the answer.
 *
 * <h2>The count is always shown; only the navigation comes and goes</h2>
 *
 * This footer used to disappear entirely whenever the whole set fitted on one page. The reasoning was
 * that a pager under four rows is furniture — which is true of the BUTTONS and false of everything
 * else in the footer. Reported from use on an estate with three applications in it: the reader saw a
 * table with no count, no rows-per-page control and no footer, and concluded the table had no paging
 * at all and would silently truncate once the estate grew. The absence was doing the opposite of what
 * it was meant to do.
 *
 * <p>So the row count and the size control are unconditional: "Showing 1–3 of 3 applications" is the
 * table stating its own extent, which is the same claim product principle 1 makes everywhere else —
 * a reader must be able to tell a short list from a truncated one. Previous and Next appear only when
 * there is a second page to reach, because those genuinely do nothing on a single-page table.
 *
 * <p>Nothing renders for an empty set. The tables that use this already say why they are empty, in
 * their own words, and "Showing 0–0 of 0" underneath that says less than the sentence above it.
 */
export function Pager<T>({ paging, unit = "rows" }: { paging: Paging<T>; unit?: string }) {
  const { page, setPage, size, setSize, pages, total, from, to } = paging;
  if (total === 0) {
    return null;
  }
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t px-5 py-3 text-xs">
      <span className="tabular text-muted-foreground">
        Showing {from}–{to} of {total} {unit}
      </span>
      <div className="flex items-center gap-3">
        <PageSize size={size} onChange={setSize} />
        {/* Only when there is somewhere to go. `pages` is derived from the ACTUAL page size rather
            than the smallest offered one: a table asking for ten per page and holding eleven rows has
            a second page, and the reader needs to be told it exists. */}
        {pages > 1 && (
          <div className="flex items-center gap-2">
            <span className="tabular text-muted-foreground">Page {page + 1} of {pages}</span>
            <Button variant="outline" size="sm" disabled={page === 0}
                    onClick={() => setPage(page - 1)}>
              <ChevronLeft /> Previous
            </Button>
            <Button variant="outline" size="sm" disabled={page + 1 >= pages}
                    onClick={() => setPage(page + 1)}>
              Next <ChevronRight />
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
