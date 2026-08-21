import { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Info, Plus, Search, X } from "lucide-react";
import { api } from "@/lib/api";
import { Kpi } from "@/components/Kpi";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { CadenceCell, ScoreCell, type Cadence } from "@/components/inventory";
import { Pager, usePaging } from "@/components/Paging";
import { AttributeCell, AttributeFilters, ColumnPicker, useDeclaredColumns, type CellValue } from "@/components/DeclaredColumns";
import type { FieldDefinition } from "@/components/AttributeFields";

const ANY = "__any__";

export interface AppRow {
  id: string; name: string; lifecycleState: string;
  owningNodeName: string | null; owningNodeTypeCode: string | null; ancestorNames: string[];
  exposureDeclared: string | null; exposureObserved: string | null; exposureConflict: boolean;
  criticalityCode: string | null; criticalityInherited: boolean; userBase: string;
  riskValue: number | null; riskBand: string | null; riskCoverage: string | null;
  findingCount: number; requestCount: number; projectCount: number; cadence: Cadence | null;
  /**
   * The fold of this application's projects' declared fields.
   *
   * **Absent, not empty, where it has no project.** Nothing recorded because there is nothing to
   * record is a different answer from nothing recorded because nobody has, and the cell says which.
   * `__projects` carries how many projects went into the fold, because a set of three values means
   * something different over four projects than over forty.
   */
  projectAttributes: (Record<string, CellValue> & { __projects?: number }) | null;
}
interface Payload {
  rows: AppRow[];
  nodes: { id: string; name: string; path: string[] }[];
  criticalities: { code: string; ordinal: number }[];
  totals: { applications: number; findings: number; requests: number };
  /**
   * Counts per option, over everything the caller can reach and before this filter narrows anything.
   * Two maps because the number beside an option must mean what the option will do: `exact` when
   * "at least" is off, `atLeast` when it is on.
   */
  reviewCounts: { exact: Record<string, number>; atLeast: Record<string, number> };
  /** The highest exact number offered. Larger counts are reached with "at least" ticked. */
  reviewChoices: number;
  /**
   * The PROJECT fields that can be folded onto an application — selects, booleans and counts.
   *
   * Free text is deliberately absent: four descriptions concatenated is a cell nobody reads and a
   * filter that matches by accident. The server decides this, not the client, so the rule lives in
   * one place.
   */
  projectFields: FieldDefinition[];
}

export function ApplicationsPage() {
  const [params, setParams] = useSearchParams();
  const atLeast = params.get("atLeast") === "1";
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState(params.get("q") ?? "");
  const query = useMemo(() => params.toString(), [params]);
  // Before the early returns below: a hook that runs conditionally is a hook that runs in a
  // different order on the next render, which React reports as a different bug entirely.
  const paging = usePaging(data?.rows ?? []);
  const columns = useDeclaredColumns("aspm.columns.applications", data?.projectFields ?? []);

  useEffect(() => {
    let live = true;
    api.get<Payload>(`/api/ui/applications?${query}`)
      .then((d) => live && setData(d)).catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, [query]);

  function setParam(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (!value || value === ANY) next.delete(key); else next.set(key, value);
    setParams(next, { replace: true });
  }

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-lg font-semibold tracking-tight">Applications</h1>
          <p className="text-xs text-muted-foreground">
            What the group runs, who owns it, and when it was last reviewed end to end.
          </p>
        </div>
        <Button variant="outline" size="sm" asChild>
          <Link to="/applications/new"><Plus className="size-3" /> New application</Link>
        </Button>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Kpi label="Applications" value={data.totals.applications} />
        <Kpi label="Findings" value={data.totals.findings} tone="info" />
        <Kpi label="Assessment requests" value={data.totals.requests} />
        <Kpi label="Never fully reviewed" tone="warn"
             value={data.rows.filter((r) => r.cadence?.status === "NEVER").length}
             hint="An obligation exists and nothing has discharged it" />
      </div>

      <Card>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="flex min-w-52 flex-1 flex-col gap-1">
            <Label htmlFor="q">Search</Label>
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
              <Input id="q" value={q} className="pl-8" placeholder="Application name"
                     onChange={(e) => setQ(e.target.value)}
                     onKeyDown={(e) => e.key === "Enter" && setParam("q", q)} />
            </div>
          </div>
          <div className="flex w-56 flex-col gap-1">
            <Label>Organization</Label>
            <Select value={params.get("node") ?? ANY} onValueChange={(v) => setParam("node", v)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={ANY}>Any</SelectItem>
                {data.nodes.map((n) => <SelectItem key={n.id} value={n.id}>{n.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <div className="flex w-44 flex-col gap-1">
            <Label>Criticality</Label>
            <Select value={params.get("criticality") ?? ANY} onValueChange={(v) => setParam("criticality", v)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={ANY}>Any</SelectItem>
                {data.criticalities.map((c) => <SelectItem key={c.code} value={c.code}>{c.code}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          {/* Full reviews CARRIED OUT, not requested.
              
              Laid out like every other filter in this row and nothing more: label, control, no caption.
              The first version explained itself in a line of small text UNDER the select, and because
              the row is `items-end` that extra line made this column taller and lifted the box above
              the others. An explanation that misaligns the form is paying for itself in the wrong
              currency — it moved into the ⓘ, where the taxonomy pickers already keep theirs. */}
          <div className="flex w-64 flex-col gap-1">
            <div className="flex items-center gap-1">
              <Label htmlFor="app-reviews">Full reviews done</Label>
              <Popover>
                <PopoverTrigger asChild>
                  <Button type="button" size="sm" variant="ghost"
                          className="size-5 p-0 text-muted-foreground hover:text-foreground"
                          aria-label="What Full reviews done counts">
                    <Info className="size-3.5" />
                  </Button>
                </PopoverTrigger>
                <PopoverContent align="start" className="w-80 text-xs">
                  <p className="mb-2">
                    Full reviews that were <strong>carried out</strong>. A review that was requested and
                    never performed does not count — the obligation is discharged by doing it, not by
                    asking, and counting both would report coverage nobody performed.
                  </p>
                  <p>
                    <strong>at least</strong> widens the choice to that number or more, which is how an
                    application reviewed nine times is found without the list growing to nine options.
                  </p>
                </PopoverContent>
              </Popover>
            </div>
            <div className="flex items-center gap-2">
              <Select value={params.get("reviews") ?? ANY}
                      onValueChange={(v) => {
                        // Clearing the number clears the modifier with it. "at least" alone means
                        // nothing, and a stale one left in the URL would silently change what a
                        // number selected the next time somebody picked one.
                        const next = new URLSearchParams(params);
                        if (v === ANY) { next.delete("reviews"); next.delete("atLeast"); }
                        else { next.set("reviews", v); }
                        setParams(next, { replace: true });
                      }}>
                <SelectTrigger id="app-reviews" className="flex-1"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value={ANY}>Any number</SelectItem>
                  {Array.from({ length: (data.reviewChoices ?? 5) + 1 }, (_, n) => (
                    <SelectItem key={n} value={String(n)}>
                      {n}
                      {/* The count matches the toggle: exactly-n when it is off, n-or-more when on. */}
                      <span className="pl-1.5 text-muted-foreground">
                        ({(atLeast ? data.reviewCounts.atLeast : data.reviewCounts.exact)[String(n)]
                          ?? 0})
                      </span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {/* Disabled while no number is chosen: "at least" on its own selects nothing, and a live
                  control that cannot change the result reads as one that is broken. */}
              <Label className="flex items-center gap-1.5 whitespace-nowrap text-xs font-normal">
                <Checkbox checked={atLeast} disabled={!params.get("reviews")}
                          onCheckedChange={(v) => setParam("atLeast", v === true ? "1" : "")} />
                at least
              </Label>
            </div>
          </div>
          {/* The PROJECT fields, filtered here as "has a project where…". An application has no
              value of its own for these — it has whatever its projects have. */}
          <AttributeFilters fields={data.projectFields} params={params} onChange={setParam} />
          <ColumnPicker fields={data.projectFields} chosen={columns.chosen} label="Project fields"
                        onToggle={columns.toggle} onMove={columns.move} onClear={columns.clear} />
          {params.toString() !== "" && (
            <Button variant="ghost" size="sm"
                    onClick={() => { setQ(""); setParams(new URLSearchParams(), { replace: true }); }}>
              <X /> Clear
            </Button>
          )}
        </CardContent>
      </Card>

      <Card className="overflow-hidden">
        {data.rows.length === 0 ? (
          <CardContent className="py-10 text-center text-sm text-muted-foreground">
            No application matches these filters.
          </CardContent>
        ) : (
          <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Application</TableHead>
                <TableHead>Organization</TableHead>
                <TableHead>Owner</TableHead>
                <TableHead>Criticality</TableHead>
                <TableHead>Exposure</TableHead>
                <TableHead>Score</TableHead>
                <TableHead className="text-right">Projects</TableHead>
                <TableHead className="text-right">Findings</TableHead>
                <TableHead className="text-right">Requests</TableHead>
                <TableHead>Full reviews</TableHead>
                <TableHead>Lifecycle</TableHead>
                {/* Marked as rolled up. A reader who takes these for the application's own values
                    would read one project's missing WAF as the whole application's. */}
                {columns.columns.map((f) => (
                  <TableHead key={f.key}>
                    {f.label}
                    <span className="block text-[10px] font-normal normal-case text-muted-foreground">
                      across its projects
                    </span>
                  </TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {paging.rows.map((row) => (
                <TableRow key={row.id}>
                  <TableCell>
                    <Link to={`/applications/${row.id}`} className="font-medium text-primary hover:underline">
                      {row.name}
                    </Link>
                    {row.userBase && <div className="text-[11px] text-muted-foreground">{row.userBase}</div>}
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">
                    {row.ancestorNames.length > 0 ? row.ancestorNames.join(" › ") : "—"}
                  </TableCell>
                  <TableCell className="text-xs">
                    {row.owningNodeName ?? <span className="text-muted-foreground italic">unowned</span>}
                    {row.owningNodeTypeCode && (
                      <div className="text-[11px] text-muted-foreground">{row.owningNodeTypeCode}</div>
                    )}
                  </TableCell>
                  <TableCell>
                    {row.criticalityCode
                      ? <Badge tone={row.criticalityCode === "TIER1" ? "critical" : row.criticalityCode === "TIER2" ? "high" : "low"}>
                          {row.criticalityCode}{row.criticalityInherited ? " (inherited)" : ""}
                        </Badge>
                      : <Badge tone="unknown">none</Badge>}
                  </TableCell>
                  <TableCell>
                    <Badge tone={row.exposureConflict ? "warn" : "neutral"}>
                      {row.exposureDeclared ?? "undeclared"}
                    </Badge>
                    {row.exposureConflict && (
                      <div className="text-[11px] text-tone-warn">observed {row.exposureObserved}</div>
                    )}
                  </TableCell>
                  <TableCell><ScoreCell value={row.riskValue} band={row.riskBand} coverage={row.riskCoverage} /></TableCell>
                  {/* The delivery branches this application is built as. A link rather than a
                      figure: the count is only useful if the reader can see WHICH projects, and
                      the projects list already filters by application. A zero is left as plain
                      text, because a link to an empty list is a dead end dressed as a control. */}
                  <TableCell className="tabular text-right">
                    {row.projectCount > 0
                      ? <Link to={`/projects?application=${row.id}`}
                              className="text-primary hover:underline">{row.projectCount}</Link>
                      : <span className="text-muted-foreground">0</span>}
                  </TableCell>
                  <TableCell className="tabular text-right">{row.findingCount}</TableCell>
                  <TableCell className="tabular text-right">{row.requestCount}</TableCell>
                  <TableCell><CadenceCell cadence={row.cadence} /></TableCell>
                  <TableCell><Badge tone={row.lifecycleState === "ACTIVE" ? "ok" : "neutral"}>{row.lifecycleState}</Badge></TableCell>
                  {columns.columns.map((f) => (
                    <TableCell key={f.key}>
                      {row.projectAttributes === null || row.projectAttributes === undefined ? (
                        // No project at all. Distinct from a project that recorded nothing: there is
                        // nothing here that COULD carry the value, and "not recorded" would imply
                        // somebody forgot rather than that the estate is empty underneath.
                        <span className="text-[11px] italic text-muted-foreground">no projects</span>
                      ) : (
                        <AttributeCell field={f} value={row.projectAttributes[f.key]} />
                      )}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
          </div>
        )}
        <Pager paging={paging} unit="applications" />
      </Card>
    </div>
  );
}
