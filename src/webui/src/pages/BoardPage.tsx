import { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Plus, Search, X, Star } from "lucide-react";
import { api } from "@/lib/api";
import type { Board } from "@/lib/types";
import { Kpi } from "@/components/Kpi";
import { stateTone } from "@/components/tone";
import { MultiSelect } from "@/components/MultiSelect";
import { DateField } from "@/components/DateField";
import { Pager, usePaging } from "@/components/Paging";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export function BoardPage() {
  const [params, setParams] = useSearchParams();
  const [board, setBoard] = useState<Board | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState(params.get("q") ?? "");

  const query = useMemo(() => params.toString(), [params]);
  // Called before the early returns below, so the hook order is the same on every render. The
  // board is the longest table in the platform — two hundred requests today — and the one where
  // a reader most needs to be able to say how far through it they are.
  const paging = usePaging(board?.rows ?? []);

  useEffect(() => {
    let live = true;
    setError(null);
    api.get<Board>(`/api/ui/board?${query}`)
      .then((b) => live && setBoard(b))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, [query]);

  /**
   * Read a multi-valued filter out of the URL.
   *
   * Three states, and the URL keeps them apart the same way the server does: an absent key is no
   * filter, a present-but-empty key is "nothing selected, so nothing matches", and a list is itself.
   * Collapsing the middle case is how unticking the last option quietly restores the unfiltered board.
   */
  function multi(key: string): string[] | null {
    if (!params.has(key)) return null;
    const raw = params.get(key) ?? "";
    return raw.split(",").map((s) => s.trim()).filter(Boolean);
  }

  function setMulti(key: string, next: string[] | null) {
    const p = new URLSearchParams(params);
    if (next === null) p.delete(key);
    // Empty stays in the URL as an empty value. It is a filter, not the absence of one, and it has to
    // survive a page reload and a shared link like any other.
    else p.set(key, next.join(","));
    setParams(p, { replace: true });
  }

  function setParam(key: string, value: string) {
    const p = new URLSearchParams(params);
    if (!value) p.delete(key); else p.set(key, value);
    setParams(p, { replace: true });
  }

  if (error) {
    return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  }
  if (!board) {
    return <div className="text-sm text-muted-foreground">Loading…</div>;
  }

  const filtered = params.toString().length > 0;
  const dueNone = params.get("due") === "none";

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-lg font-semibold tracking-tight">Assessment requests</h1>
          <p className="text-xs text-muted-foreground">
            Every request in your scope. Counts are of findings discovered in the request, not of the
            application’s whole history.
          </p>
        </div>
        {/* The way in. It was reachable only from the bottom of a project page, which is not where
            anybody looks for "raise a request" — they look on the board of requests. The form itself
            still decides what may be requested; this is a door, not a permission. */}
        <Button size="sm" asChild>
          <Link to="/requests/new"><Plus /> Request an assessment</Link>
        </Button>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Kpi label="Requests" value={board.totals.requests} />
        <Kpi label="Overdue" value={board.totals.overdue} tone="critical" />
        <Kpi label="No assessor" value={board.totals.unassigned} tone="warn"
             hint="In flight with nobody answerable" />
        <Kpi label="Open findings" value={board.totals.openFindings} tone="info" />
      </div>

      <Card>
        <CardContent className="flex flex-col gap-3">
          {/* Two rows. WHO and WHAT above, WHEN below, because a date range beside eight pickers is a
              date range nobody notices — and the deadline filters are the ones a lead uses most. */}
          {/* A grid, not flex-wrap. Wrapping left the last picker orphaned on a line of its own,
              which reads as a different kind of control from the six above it. */}
          <div className="grid items-end gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-7">
            <div className="flex flex-col gap-1">
              <Label htmlFor="q">Search</Label>
              <div className="relative">
                <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
                <Input id="q" value={q} className="pl-8" placeholder="Title or code"
                       onChange={(e) => setQ(e.target.value)}
                       onKeyDown={(e) => e.key === "Enter" && setParam("q", q)} />
              </div>
            </div>

            <MultiSelect label="Organization" options={board.organizations}
                         value={multi("node")} onChange={(v) => setMulti("node", v)}
                         width="w-full" />
            <MultiSelect label="Application" options={board.applications}
                         value={multi("application")} onChange={(v) => setMulti("application", v)}
                         width="w-full" />
            <MultiSelect label="Project" options={board.projects}
                         value={multi("project")} onChange={(v) => setMulti("project", v)}
                         width="w-full" />
            <MultiSelect label="Assessor" options={board.assessors}
                         value={multi("assessor")} onChange={(v) => setMulti("assessor", v)}
                         width="w-full"
                         // Not a person, and not the absence of a filter either: an in-flight request
                         // with nobody answerable is the row a lead is hunting for.
                         extra={[{ id: "none", name: "Unassigned", hint: "nobody answerable yet" }]} />
            <MultiSelect label="State"
                         options={board.states.map((s) => ({ id: s.code, name: s.label }))}
                         value={multi("state")} onChange={(v) => setMulti("state", v)}
                         width="w-full" />
            <MultiSelect label="Reason"
                         options={board.triggers.map((t) => ({
                           id: t.code,
                           name: t.label + (t.countsAsFullReview ? " ★" : ""),
                           hint: t.countsAsFullReview ? "discharges the periodic obligation" : "",
                         }))}
                         value={multi("trigger")} onChange={(v) => setMulti("trigger", v)}
                         width="w-full"
                         extra={[{ id: "none", name: "Not stated",
                                   hint: "nobody recorded why" }]} />
          </div>

          <div className="flex flex-wrap items-end gap-3 border-t pt-3">
            <DateRange label="Due between" from={params.get("dueFrom") ?? ""}
                       to={params.get("dueTo") ?? ""}
                       onFrom={(v) => setParam("dueFrom", v)} onTo={(v) => setParam("dueTo", v)}
                       disabled={dueNone} />
            {/* Separate from the range, because a request with no deadline is excluded by ANY range
                comparison and "who never set a date" is exactly the question that finds them. */}
            <Button variant="outline" size="sm" onClick={() => setParam("due", dueNone ? "" : "none")}
                    className={dueNone ? "border-primary text-primary" : ""}>
              No deadline set
            </Button>

            <DateRange label="Created between" from={params.get("createdFrom") ?? ""}
                       to={params.get("createdTo") ?? ""}
                       onFrom={(v) => setParam("createdFrom", v)}
                       onTo={(v) => setParam("createdTo", v)} />

            <Button variant="outline" size="sm"
                    onClick={() => setParam("only", params.get("only") === "overdue" ? "" : "overdue")}
                    className={params.get("only") === "overdue" ? "border-sev-critical text-sev-critical" : ""}>
              Overdue only
            </Button>

            <span className="flex-1" />

            <div className="flex w-40 flex-col gap-1">
              <Label>Sort by</Label>
              <select value={params.get("sort") ?? "due"}
                      onChange={(e) => setParam("sort", e.target.value === "due" ? "" : e.target.value)}
                      className="h-9 rounded-md border border-input bg-transparent px-2 text-xs shadow-xs outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50">
                <option value="due">Deadline</option>
                <option value="created">Newest first</option>
                <option value="state">State</option>
                <option value="findings">Open findings</option>
                <option value="title">Title</option>
              </select>
            </div>

            {filtered && (
              <Button variant="ghost" size="sm"
                      onClick={() => { setQ(""); setParams(new URLSearchParams(), { replace: true }); }}>
                <X /> Reset all
              </Button>
            )}
          </div>
        </CardContent>
      </Card>

      <Card className="overflow-hidden">
        {board.rows.length === 0 ? (
          <CardContent className="py-10 text-center text-sm text-muted-foreground">
            {/* Said precisely. "No request matches" over an emptied multi-select reads as an empty
                estate; naming the cause is what stops somebody concluding their requests are gone. */}
            No request matches these filters.
            {[...params.keys()].some((k) => params.get(k) === "" &&
              ["node", "application", "project", "assessor", "state", "trigger"].includes(k)) && (
              <div className="mt-1 text-xs">
                One of your filters has nothing selected, which matches nothing. Open it and choose
                <strong> No filter</strong> to see everything again.
              </div>
            )}
          </CardContent>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Request</TableHead>
                <TableHead>Project</TableHead>
                <TableHead>Application</TableHead>
                <TableHead>Organization</TableHead>
                <TableHead>Reason</TableHead>
                <TableHead className="text-right">Findings</TableHead>
                {/* Created, beside due and closed. Without it a reader cannot tell a request that is
                    late from one raised yesterday with a tight deadline, and those need opposite
                    responses. */}
                <TableHead className="whitespace-nowrap">Created</TableHead>
                <TableHead className="whitespace-nowrap">Due</TableHead>
                <TableHead className="whitespace-nowrap">Closed</TableHead>
                <TableHead>State</TableHead>
                <TableHead>Dev</TableHead>
                <TableHead>Assessor</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {paging.rows.map((row) => (
                <TableRow key={row.id}>
                  <TableCell>
                    <Link to={`/board/${row.id}`} className="font-medium text-primary hover:underline">
                      {row.title ?? row.code}
                    </Link>
                    <div className="font-mono text-[11px] text-muted-foreground">{row.code}</div>
                  </TableCell>
                  <TableCell className="text-xs">
                    {/* The project is what somebody actually asked to have assessed; the
                        application is derived from it. More than one means a full review. */}
                    {row.projects.length === 0
                      ? <span className="italic text-muted-foreground">none named</span>
                      : row.projects.length === 1
                        ? <Link to={`/projects/${row.projects[0]!.id}`}
                                className="text-primary hover:underline">{row.projects[0]!.name}</Link>
                        : (
                          <span title={row.projects.map((p) => p.name).join(", ")}>
                            <Badge tone="info">whole application</Badge>
                            <div className="text-[11px] text-muted-foreground">
                              {row.projects.length} projects
                            </div>
                          </span>
                        )}
                  </TableCell>
                  <TableCell className="text-xs">
                    {row.application ?? <span className="text-muted-foreground italic">none in scope</span>}
                    {row.scopeAssets > 1 && (
                      <div className="text-[11px] text-muted-foreground">+{row.scopeAssets - 1} more</div>
                    )}
                  </TableCell>
                  <TableCell className="text-xs">
                    {row.orgNodeName ?? "—"}
                    {row.orgAncestors.length > 0 && (
                      <div className="text-[11px] text-muted-foreground">{row.orgAncestors.join(" › ")}</div>
                    )}
                  </TableCell>
                  <TableCell className="text-xs">
                    {row.triggerLabel ? (
                      <span className="inline-flex items-center gap-1">
                        {row.triggerIsFullReview && <Star className="size-3 text-tone-info" />}
                        {row.triggerLabel}
                      </span>
                    ) : (
                      <Badge tone="unknown">Not stated</Badge>
                    )}
                  </TableCell>
                  <TableCell className="tabular text-right text-xs">
                    {row.findingTotal === 0 ? (
                      <span className="text-muted-foreground italic">none</span>
                    ) : (
                      <span title="open / risk accepted / total">
                        {row.findingOpen} / {row.findingAccepted} / {row.findingTotal}
                      </span>
                    )}
                    {row.findingSevereOpen > 0 && (
                      <Badge tone="critical" className="ml-1">{row.findingSevereOpen}</Badge>
                    )}
                  </TableCell>
                  <TableCell className="whitespace-nowrap font-mono text-[11px]">
                    {row.createdAt ?? <span className="text-muted-foreground">—</span>}
                  </TableCell>
                  <TableCell className="whitespace-nowrap font-mono text-[11px]">
                    {row.dueAt ? (row.overdue ? <Badge tone="critical">{row.dueAt}</Badge> : row.dueAt)
                              : <span className="text-muted-foreground italic">not set</span>}
                  </TableCell>
                  <TableCell className="whitespace-nowrap font-mono text-[11px]">
                    {row.closedAt ?? <span className="text-muted-foreground">—</span>}
                  </TableCell>
                  <TableCell><Badge tone={stateTone(row.stateCategory)}>{row.stateLabel}</Badge></TableCell>
                  <TableCell className="text-xs">{row.contact ?? "—"}</TableCell>
                  <TableCell className="text-xs">
                    {row.assessor ?? <Badge tone="warn">Unassigned</Badge>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        <Pager paging={paging} unit="requests" />
      </Card>
    </div>
  );
}

/**
 * A from/to pair of dates.
 *
 * Both ends inclusive — the server compares the upper bound against the start of the following day, so
 * a request due at 17:00 on the chosen date is inside the range rather than lost to the comparison.
 */
function DateRange({ label, from, to, onFrom, onTo, disabled }: {
  label: string; from: string; to: string;
  onFrom: (v: string) => void; onTo: (v: string) => void; disabled?: boolean;
}) {
  return (
    <div className="flex flex-col gap-1">
      <Label className={disabled ? "opacity-50" : undefined}>{label}</Label>
      <div className="flex items-center gap-1.5">
        <DateField value={from} onChange={onFrom} disabled={disabled} max={to || undefined}
                   className="w-36" />
        <span className="text-xs text-muted-foreground">→</span>
        <DateField value={to} onChange={onTo} disabled={disabled} min={from || undefined}
                   className="w-36" />
        {(from || to) && !disabled && (
          <Button size="sm" variant="ghost" className="h-7 px-1.5"
                  onClick={() => { onFrom(""); onTo(""); }}>
            <X className="size-3" />
          </Button>
        )}
      </div>
    </div>
  );
}
