import { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Search, X } from "lucide-react";
import { api } from "@/lib/api";
import { Pager, usePaging } from "@/components/Paging";
import { Kpi } from "@/components/Kpi";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const ANY = "__any__";

export interface ProjectRow {
  id: string; name: string; lifecycleState: string; description: string | null;
  owningNodeId: string | null; owningNodeName: string | null;
  /** Root first, paired positionally with `ownerAncestorIds`. */
  ownerAncestors: string[]; ownerAncestorIds: string[]; deliveryTeam: string | null;
  criticalityCode: string | null; criticalityInherited: boolean;
  exposureDeclared: string | null; exposureObserved: string | null; exposureConflict: boolean;
  applicationId: string | null; applicationName: string | null;
  componentCount: number; findingTotal: number; findingOpen: number; findingAccepted: number;
  criticalOpen: number; highOpen: number; scaOpen: number; requestCount: number;
  lastDetectedAt: string | null;
}
/** One selectable organization node. `depth` is the level in the tree, so the list can indent. */
interface OrgOption { id: string; name: string; depth: number; path: string }
interface Payload {
  rows: ProjectRow[];
  /** Ids the caller may raise a request against. A subset of rows — seeing is not requesting. */
  raisable: string[];
  applications: { id: string; name: string }[];
  /** Every node with a project beneath it, at every level. The filter is subtree-inclusive. */
  organizations: OrgOption[];
  totals: { projects: number; teams: number; withSevereOpen: number; neverAssessed: number };
}

/**
 * The organization a project belongs to: the topmost node above the team that owns it.
 *
 * A team is who is answerable; the division is where the work sits in the group, and it is the level a
 * reader scanning a conglomerate's estate thinks in. Falls back to the owning node itself, because a
 * project owned directly by a root node has no ancestor above it and is not therefore unowned.
 */
function organizationOf(p: ProjectRow): string | null {
  return p.ownerAncestors.length > 0 ? p.ownerAncestors[0] : p.owningNodeName;
}

/**
 * Projects — the branch of an application one team delivers.
 *
 * An application is the thing the business names; a project is the thing a team is answerable for,
 * and an assessment is requested against the second. The application above each row is **derived**
 * from the composition graph rather than stored on the project, which is what lets an intake form
 * ask only for the project and fill in the rest without the two ever disagreeing.
 */
export function ProjectsPage() {
  const [params, setParams] = useSearchParams();
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState(params.get("q") ?? "");
  const query = useMemo(() => params.toString(), [params]);
  const paging = usePaging(data?.rows ?? []);

  useEffect(() => {
    let live = true;
    setError(null);
    api.get<Payload>(`/api/ui/projects?${query}`)
      .then((d) => live && setData(d))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, [query]);

  function setParam(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (!value || value === ANY) next.delete(key);
    else next.set(key, value);
    setParams(next, { replace: true });
  }

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>;

  const filtered = params.toString().length > 0;

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold tracking-tight">Projects</h1>
        <p className="text-xs text-muted-foreground">
          The branch of an application a team is answerable for. Counts roll up everything the project
          contains, not only the project row itself.
        </p>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Kpi label="Projects" value={data.totals.projects} />
        <Kpi label="Teams delivering" value={data.totals.teams} hint="Distinct owners" />
        {/* Counts of PROJECTS. Two projects can share a service, so its findings roll up into
            both — correct per project, and a sum across them would exceed the estate. */}
        <Kpi label="With severe findings open" value={data.totals.withSevereOpen} tone="critical" />
        <Kpi label="Never assessed" value={data.totals.neverAssessed} tone="warn"
             hint="No assessment request raised" />
      </div>

      <Card>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="flex min-w-52 flex-1 flex-col gap-1">
            <Label htmlFor="q">Search</Label>
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
              <Input id="q" value={q} className="pl-8" placeholder="Project name"
                     onChange={(e) => setQ(e.target.value)}
                     onKeyDown={(e) => e.key === "Enter" && setParam("q", q)} />
            </div>
          </div>
          <div className="flex w-64 flex-col gap-1">
            <Label>Organization</Label>
            {/* Subtree-inclusive: picking a division returns every project under every team beneath it.
                Indented by the depth the server sent rather than by parsing the names, and the options
                are ordered by path so each node sits under its own parent. */}
            <Select value={params.get("org") ?? ANY} onValueChange={(v) => setParam("org", v)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={ANY}>Any</SelectItem>
                {data.organizations.map((o) => (
                  <SelectItem key={o.id} value={o.id}>
                    <span style={{ paddingLeft: `${o.depth * 0.75}rem` }}>
                      {o.depth > 0 && <span className="text-muted-foreground">› </span>}{o.name}
                    </span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex w-64 flex-col gap-1">
            <Label>Application</Label>
            <Select value={params.get("application") ?? ANY}
                    onValueChange={(v) => setParam("application", v)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={ANY}>Any</SelectItem>
                {data.applications.map((a) => (
                  <SelectItem key={a.id} value={a.id}>{a.name}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {filtered && (
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
            {filtered
              ? "No project matches these filters."
              : "No project has been registered yet. A project is an asset of type PROJECT contained by an application."}
          </CardContent>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Project</TableHead>
                <TableHead>Organization</TableHead>
                <TableHead>Application</TableHead>
                <TableHead>Team accountable</TableHead>
                <TableHead>Criticality</TableHead>
                <TableHead>Exposure</TableHead>
                <TableHead className="text-right">Parts</TableHead>
                <TableHead className="text-right">Findings</TableHead>
                <TableHead className="text-right">Requests</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {paging.rows.map((p) => (
                <TableRow key={p.id}>
                  <TableCell className="max-w-72">
                    <Link to={`/projects/${p.id}`}
                          className="font-medium text-primary hover:underline">{p.name}</Link>
                    {p.description && (
                      <div className="truncate text-[11px] text-muted-foreground">{p.description}</div>
                    )}
                  </TableCell>
                  <TableCell className="text-xs">
                    {/* The division, with the levels between it and the team beneath. Clicking it
                        narrows the table to that branch rather than opening a page: this is a list of
                        projects, and the useful next move from a division is "only these". */}
                    {organizationOf(p) === null
                      ? <Badge tone="warn">unowned</Badge>
                      : <button type="button" className="text-left text-primary hover:underline"
                                onClick={() => setParam("org",
                                  p.ownerAncestorIds[0] ?? p.owningNodeId ?? "")}>
                          {organizationOf(p)}
                        </button>}
                    {p.ownerAncestors.length > 1 && (
                      <div className="text-[11px] text-muted-foreground">
                        {p.ownerAncestors.slice(1).join(" › ")}
                      </div>
                    )}
                  </TableCell>
                  <TableCell className="text-xs">
                    {/* Derived from the graph. A project outside every application is reported rather
                        than blanked: it is the row an intake form cannot resolve. */}
                    {p.applicationId
                      ? <Link to={`/applications/${p.applicationId}`}
                              className="text-primary hover:underline">{p.applicationName}</Link>
                      : <Badge tone="warn">under no application</Badge>}
                  </TableCell>
                  <TableCell className="text-xs">
                    {/* The team only. The path above it moved to the Organization column rather than
                        being repeated here — one fact in one place (product principle 10). */}
                    {p.owningNodeName ?? <Badge tone="warn">unowned</Badge>}
                  </TableCell>
                  <TableCell className="text-xs">
                    {p.criticalityCode ?? "—"}
                    {p.criticalityInherited && (
                      <div className="text-[11px] text-muted-foreground">inherited</div>
                    )}
                  </TableCell>
                  <TableCell className="text-xs">
                    {p.exposureDeclared ?? "—"}
                    {p.exposureConflict && <Badge tone="critical" className="ml-1">conflict</Badge>}
                  </TableCell>
                  <TableCell className="tabular text-right text-xs">{p.componentCount}</TableCell>
                  <TableCell className="tabular text-right text-xs">
                    {p.findingTotal === 0
                      ? <span className="italic text-muted-foreground">none</span>
                      : <span title="open / risk accepted / total">
                          {p.findingOpen} / {p.findingAccepted} / {p.findingTotal}
                        </span>}
                    {p.criticalOpen + p.highOpen > 0 && (
                      <Badge tone="critical" className="ml-1">{p.criticalOpen + p.highOpen}</Badge>
                    )}
                  </TableCell>
                  <TableCell className="tabular text-right text-xs">
                    {p.requestCount}
                    {/* Offered only where the caller may actually raise one. The row stays visible
                        either way: being able to see a project and being able to ask for work
                        against it are different questions, and hiding the first to express the
                        second would hide projects people are entitled to read. */}
                    {data.raisable.includes(p.id) && (
                      <Link to={`/requests/new?project=${p.id}`}
                            className="ml-2 text-primary hover:underline">request</Link>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        <Pager paging={paging} unit="projects" />
      </Card>
    </div>
  );
}
