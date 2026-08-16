import { Fragment, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { GitBranch, Info, Search, Wrench, X } from "lucide-react";
import { api } from "@/lib/api";
import { Kpi } from "@/components/Kpi";
import { severityTone } from "@/components/tone";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Pager, usePaging } from "@/components/Paging";

const ANY = "__any__";

/** The one context this page is about. Everything else on it follows from that. */
const CONTEXT = "AUTOMATED_SCAN";

interface Row {
  id: string; title: string; severity: string; severityOrdinal: number | null;
  state: string; findingClass: string; sourceTool: string;
  orgPath: string | null; assetName: string | null;
  // The payload's own names. Written as `application`/`project` first, which type-checked because the
  // interface was wrong in the same way — so the column rendered "not attributed" on every row while
  // the chart beside it found projects perfectly. Two views of one field disagreeing on screen.
  applicationName: string | null; projectName: string | null;
  ageDays: number | null; description: string | null;
  requestId: string | null;
}
interface Bucket { key: string; label: string; open: number; closed: number; serious: number }
interface Payload {
  summary: {
    total: number; open: number; closed: number; criticalOpen: number; highOpen: number;
    seriousOpen: number; claimedOpen: number;
  };
  rows: Row[];
  byProject: Bucket[];
  byTool: Bucket[];
  /** Every organization node the caller can reach, tree-ordered with its depth, for the filter. */
  organizations?: { id: string; name: string; depth: number }[];
  bySeverity: Bucket[];
  projectTree?: TreeRow[];
  severityOptions: { id: string; name: string; findings: number }[];
  rowCap: number; truncated: boolean;
}

interface TreeRow {
  projectId: string | null; projectName: string | null;
  /** The organization above the team that owns the project. Null only where no project was reached. */
  orgId: string | null; orgName: string | null;
  repositoryId: string | null; repositoryName: string | null;
  open: number; critical: number; serious: number; newestAt: string | null;
}

/**
 * What the pipelines found — the delivery team's own list.
 *
 * <h2>Why this is a separate page and not a filter on the vulnerability dashboard</h2>
 *
 * Two kinds of finding live in this platform and they differ in almost every way that matters to the
 * person reading them. An assessment finding arrives against a ticket, on a cadence, written by an
 * assessor who will retest it. A pipeline finding arrives against a commit, continuously, from a tool,
 * and the person who can fix it is the person who wrote the line. Ranked together, the second buries
 * the first and neither audience recognises the list as theirs.
 *
 * <h2>Why it is the same data underneath</h2>
 *
 * The rows come from `/api/ui/vulnerabilities?context=AUTOMATED_SCAN` — the same query, the same
 * deduplication, the same scope predicate. A separate store was the obvious alternative and it is
 * wrong: ADR-011 runs ONE normalization and deduplication pipeline, and a weakness that semgrep reports
 * in CI and a pentester later finds by hand has to land on one record. Two records means
 * `recurrence_count` never moves — and that count is the only number that tells a team it keeps
 * reintroducing the same mistake.
 *
 * <h2>What is deliberately absent</h2>
 *
 * No request column, no assessor, no workflow vocabulary, no triage controls. This page answers one
 * question — what should this team fix next — and every column that does not serve it is a column that
 * makes somebody think they need permission they do not need.
 */
export function PipelinePage() {
  const [params, setParams] = useSearchParams();
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);

  /**
   * Whether the reader has asked to see records merely LABELLED automated, rather than imported ones.
   *
   * Declared ABOVE the memo that reads it. It was below, which is a temporal dead zone: the module
   * built, type-checked and then failed at runtime with "Cannot access 'g' before initialization" —
   * a blank page, and nothing in the build said so.
   */
  const labelled = params.get("labelled") === "1";

  const query = useMemo(() => {
    const q = new URLSearchParams(params);
    // PROVENANCE FIRST. Unless the reader has explicitly asked to see the labelled set, this page shows
    // only findings that actually arrived through an import — which is what "from CI/CD" means. The
    // label `AUTOMATED_SCAN` is a field an assessor picks, and in this deployment it sits on findings
    // whose tool is `manual-pentest`; a dashboard built on it shows assessment work as pipeline output.
    if (labelled) {
      q.set("context", CONTEXT);
      q.delete("fromPipeline");
    } else {
      q.set("fromPipeline", "1");
      q.delete("context");
    }
    // Open work by default. A delivery team opening this page wants what is outstanding; the closed
    // history is a deliberate second click, not the first thing that greets them.
    if (!q.get("state")) q.set("state", "LC_OPEN");
    q.set("limit", "500");
    // The tree is the point of this page, so it is always requested here and never anywhere else.
    q.set("tree", "1");
    return q.toString();
  }, [params]);

  const [hunt, setHunt] = useState("");
  const paging = usePaging(data?.rows ?? []);

  /**
   * The tree, grouped and searched.
   *
   * Filtered here rather than on the server: the tree is one row per project-and-repository, so even a
   * large group is a few hundred rows, and a search that waits for a round trip is a search people stop
   * using. The finding list below is still narrowed by the server, where the scope predicate lives.
   */
  const groups = useMemo(() => {
    const tree = data?.projectTree ?? [];
    const needle = hunt.trim().toLowerCase();
    const matching = needle
      ? tree.filter((r) => (r.projectName ?? "").toLowerCase().includes(needle)
                        || (r.repositoryName ?? "").toLowerCase().includes(needle)
                        // The organization is searchable too, because "show me everything under
                        // VinFast" is the question somebody scanning this table actually has.
                        || (r.orgName ?? "").toLowerCase().includes(needle))
      : tree;
    const byProject = new Map<string, {
      id: string | null; name: string; orgId: string | null; orgName: string | null;
      open: number; critical: number; serious: number;
      newestAt: string | null; rows: TreeRow[];
    }>();
    for (const row of matching) {
      const key = row.projectId ?? "__none__";
      const found = byProject.get(key) ?? {
        id: row.projectId, name: row.projectName ?? "",
        orgId: row.orgId, orgName: row.orgName, open: 0, critical: 0, serious: 0,
        newestAt: null, rows: [],
      };
      found.open += row.open;
      found.critical += row.critical;
      found.serious += row.serious;
      // The most recent arrival anywhere under the project, which is what "is this still happening"
      // asks. Comparing ISO dates as strings is safe and is why the server sends them that way.
      if (row.newestAt && (!found.newestAt || row.newestAt > found.newestAt)) {
        found.newestAt = row.newestAt;
      }
      found.rows.push(row);
      byProject.set(key, found);
    }
    return [...byProject.values()]
      .map((g) => ({ ...g, rows: [...g.rows].sort((a, b) => b.open - a.open) }))
      .sort((a, b) => b.serious - a.serious || b.open - a.open);
  }, [data, hunt]);

  /**
   * Paged by PROJECT, not by row.
   *
   * A page boundary through the middle of a project would split its repositories across two pages and
   * show a project header with some of its children — which reads as a project that has fewer
   * repositories than it does. Ten projects a page, each whole.
   */
  const treePaging = usePaging(groups, 10);

  /** How many findings the tree counts twice because they sit under more than one project. */
  const shared = useMemo(() => {
    const counted = (data?.projectTree ?? []).reduce((n, r) => n + r.open, 0);
    return Math.max(0, counted - (data?.summary.open ?? 0));
  }, [data]);

  /** Choosing a project or a repository replaces the other: they are one selection, not two filters. */
  function pick(key: "project" | "repository", value: string | null) {
    const next = new URLSearchParams(params);
    next.delete("project");
    next.delete("repository");
    if (value) next.set(key, value);
    setParams(next, { replace: true });
  }

  useEffect(() => {
    let live = true;
    api.get<Payload>(`/api/ui/vulnerabilities?${query}`)
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

  const tools = data.byTool.filter((b) => b.open > 0);

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="flex items-center gap-2 text-lg font-semibold tracking-tight">
          <GitBranch className="size-4 text-muted-foreground" /> What the pipelines found
          <Popover>
            <PopoverTrigger asChild>
              <Button type="button" size="sm" variant="ghost"
                      className="size-5 p-0 text-muted-foreground hover:text-foreground"
                      aria-label="What this page shows">
                <Info className="size-3.5" />
              </Button>
            </PopoverTrigger>
            <PopoverContent align="start" className="w-96 text-xs">
              <p className="mb-2">
                <strong>Scan reports</strong> — what your SAST, secret, IaC and DAST tools reported on a
                commit. Not what an assessor wrote up against a ticket: assessment findings are on the
                Vulnerabilities dashboard and follow a review workflow, these do not.
              </p>
              {/* Said here because the boundary is easy to get wrong, and getting it wrong means looking
                  for a bill of materials on a page that will never hold one. A component inventory is
                  not a finding: it becomes findings only after it is matched against advisories, and
                  that matching and its coverage live on the Dependencies pages. */}
              <p className="mb-2">
                An <strong>SBOM is not a scan report</strong> and does not appear here. A bill of
                materials pushed from a pipeline goes to{" "}
                <Link className="underline" to="/composition">Dependencies</Link> — the same place the
                interactive upload sends one, over the same ingestion code — where it becomes a component
                inventory and is matched against advisories.
              </p>
              <p>
                It is the same underlying record either way. If a scanner and an assessor find the same
                weakness it stays one finding, so the count of times it has come back keeps meaning
                something.
              </p>
            </PopoverContent>
          </Popover>
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Your team's own list. Fix, push, and the finding closes when the next scan stops reporting it —
          nothing here needs the security team to move it along.
        </p>
      </div>

      {/* WHICH SET IS ON SCREEN, always stated. An empty page because nothing has arrived and an empty
          page because nothing matched are different facts, and only one of them is somebody's job. */}
      <Card>
        <CardContent className="flex flex-wrap items-center gap-3 py-3 text-xs">
          {labelled ? (
            <>
              <Badge tone="warn">labelled, not imported</Badge>
              <span className="text-muted-foreground">
                Showing findings an assessor <strong>labelled</strong> as automated scan output. These did
                not arrive from a pipeline — in this data the label also sits on manual pentest work, so
                treat the list as a rehearsal of the layout rather than as CI/CD output.
              </span>
              <Button size="sm" variant="secondary" className="ml-auto"
                      onClick={() => setParam("labelled", "")}>
                Show only what arrived from a pipeline
              </Button>
            </>
          ) : (
            <>
              <Badge tone="info">imported from a pipeline</Badge>
              <span className="text-muted-foreground">
                {data.summary.total === 0
                  ? "No scan report has arrived through a pipeline yet — the report parsers and the "
                    + "import endpoint are not built. This page is empty because nothing arrived, not "
                    + "because nothing matched."
                  : "Scan findings that arrived through an import — provenance, not a label."}
              </span>
              <Button size="sm" variant="ghost" className="ml-auto"
                      onClick={() => setParam("labelled", "1")}>
                See records labelled as automated instead
              </Button>
            </>
          )}
        </CardContent>
      </Card>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Kpi label="Open" value={data.summary.open} />
        <Kpi label="Critical open" tone={data.summary.criticalOpen > 0 ? "critical" : undefined}
             value={data.summary.criticalOpen} />
        <Kpi label="Serious open" tone={data.summary.seriousOpen > 0 ? "warn" : undefined}
             value={data.summary.seriousOpen}
             hint="Critical or high on this tenant's own scale" />
        <Kpi label="Reported fixed, not verified" value={data.summary.claimedOpen}
             hint="Somebody said these were done and no scan has confirmed it yet" />
      </div>

      {/* Only the tool breakdown survives here. The card beside it used to rank projects on a bar
          chart — which is the same question the tree below answers, with the repository, the newest
          arrival and a click on top. Two controls for one question is how a page gets long and a reader
          gets unsure which number is the real one. */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center gap-1.5 text-sm">
            <Wrench className="size-3.5 text-muted-foreground" /> Which tool is reporting
          </CardTitle>
          <CardDescription>
            A tool with a large share is usually one rule firing widely — worth fixing at the rule
            rather than one finding at a time.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-1.5">
          {tools.length === 0
            ? <p className="text-xs italic text-tone-unknown">Nothing outstanding.</p>
            : tools.map((b) => (
              <Button key={b.key} size="sm"
                      variant={params.get("tool") === b.key ? "secondary" : "outline"}
                      onClick={() => setParam("tool", params.get("tool") === b.key ? "" : b.key)}>
                {b.label} <span className="tabular text-muted-foreground">{b.open}</span>
              </Button>
            ))}
        </CardContent>
      </Card>

      {/* PROJECT, THEN REPOSITORY — and a search rather than a picker.
      
          A dropdown was the first shape and it does not survive contact with a real delivery
          organisation: a list of every project in a group is not something anybody scrolls. A typed
          search over a tree scales because the reader narrows it, and the tree answers the question
          they actually arrived with — which of my repositories is worst — by its shape rather than by
          making them sort a flat list in their head. */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm">Projects and their repositories</CardTitle>
          <CardDescription>
            Open findings, worst first. Click a row to narrow the list below it.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex min-w-64 flex-1 flex-col gap-1">
              <Label htmlFor="pipe-search">Find a project</Label>
              <div className="relative">
                <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
                <Input id="pipe-search" value={hunt} className="pl-8"
                       placeholder="Type part of an organization, project or repository name"
                       onChange={(e) => setHunt(e.target.value)} />
              </div>
            </div>
            <div className="flex w-56 flex-col gap-1">
              <Label htmlFor="pipe-org">Organization</Label>
              {/* Subtree-inclusive, and it narrows the PROJECT LIST as well as the findings — see the
                  note on VulnerabilityQuery#projectTree for why both are needed. Indented by the depth
                  the server sent rather than by guessing from the names. */}
              <Select value={params.get("org") ?? ANY}
                      onValueChange={(v) => setParam("org", v)}>
                <SelectTrigger id="pipe-org"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value={ANY}>Everything you can reach</SelectItem>
                  {(data.organizations ?? []).map((o) => (
                    <SelectItem key={o.id} value={o.id}>
                      <span style={{ paddingLeft: `${o.depth * 0.75}rem` }}>
                        {o.depth > 0 && <span className="text-muted-foreground">› </span>}{o.name}
                      </span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex w-44 flex-col gap-1">
              <Label htmlFor="pipe-severity">Severity</Label>
              <Select value={params.get("severity") ?? ANY}
                      onValueChange={(v) => setParam("severity", v)}>
                <SelectTrigger id="pipe-severity"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value={ANY}>Any severity</SelectItem>
                  {/* The tenant's own scale, in its own order — not a fixed CRITICAL/HIGH/MEDIUM/LOW
                      list, which ADR-027 forbids hardcoding and which a tenant renaming its scale
                      would silently break. */}
                  {(data.severityOptions ?? []).map((sv) => (
                    <SelectItem key={sv.id} value={sv.id}>{sv.name}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex w-48 flex-col gap-1">
              <Label htmlFor="pipe-state">Show</Label>
              <Select value={params.get("state") ?? "LC_OPEN"}
                      onValueChange={(v) => setParam("state", v)}>
                <SelectTrigger id="pipe-state"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="LC_OPEN">Open — nothing claimed</SelectItem>
                  <SelectItem value="LC_FIXED">Reported fixed</SelectItem>
                  <SelectItem value="LC_REOPEN">Came back</SelectItem>
                  <SelectItem value="LC_CLOSED">Closed</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {(params.get("project") || params.get("repository") || params.get("severity")
              || params.get("tool")) && (
              <Button variant="ghost" size="sm"
                      onClick={() => setParams(new URLSearchParams(), { replace: true })}>
                <X className="size-3.5" /> Clear
              </Button>
            )}
          </div>

          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Project / repository</TableHead>
                  <TableHead>Organization</TableHead>
                  <TableHead className="text-right">Open</TableHead>
                  <TableHead className="text-right">Critical</TableHead>
                  <TableHead className="text-right">Serious</TableHead>
                  <TableHead>Newest</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {treePaging.rows.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={6} className="text-xs italic text-tone-unknown">
                      {hunt
                        ? `Nothing matches "${hunt}".`
                        : "No open pipeline findings — which is a real answer, not an empty table."}
                    </TableCell>
                  </TableRow>
                )}
                {treePaging.rows.map((g) => (
                  <Fragment key={g.id ?? "none"}>
                    <TableRow className="bg-muted/40">
                      <TableCell className="text-xs font-medium">
                        {g.id ? (
                          <button type="button" className="hover:underline"
                                  onClick={() => pick("project", g.id)}>{g.name}</button>
                        ) : (
                          /* Named, not dropped. These findings are attached to something the
                             composition graph cannot walk to a project — a real gap somebody should
                             see rather than a row quietly missing from a total. */
                          <span className="italic text-tone-unknown">No project attributed</span>
                        )}
                      </TableCell>
                      <TableCell className="text-xs">
                        {/* Clicking it narrows the whole page to that branch of the organization —
                            subtree-inclusive, the same `org` parameter the vulnerability dashboard and
                            the projects inventory use. One name, one meaning, one place. */}
                        {g.orgName ? (
                          <button type="button" className="text-left hover:underline"
                                  onClick={() => setParam("org", g.orgId ?? "")}>{g.orgName}</button>
                        ) : (
                          <span className="italic text-tone-unknown">not attributed</span>
                        )}
                      </TableCell>
                      <TableCell className="tabular text-right text-xs font-medium">{g.open}</TableCell>
                      <TableCell className="tabular text-right text-xs">
                        {g.critical > 0
                          ? <span className="font-medium text-sev-critical">{g.critical}</span>
                          : <span className="text-muted-foreground">0</span>}
                      </TableCell>
                      <TableCell className="tabular text-right text-xs">{g.serious}</TableCell>
                      <TableCell className="tabular text-xs text-muted-foreground">{g.newestAt ?? "—"}</TableCell>
                    </TableRow>
                    {g.rows.map((r) => (
                      <TableRow key={(g.id ?? "none") + "/" + (r.repositoryId ?? "self")}>
                        <TableCell className="pl-8 text-xs">
                          {r.repositoryId ? (
                            <button type="button" className="font-mono hover:underline"
                                    onClick={() => pick("repository", r.repositoryId)}>
                              {r.repositoryName}
                            </button>
                          ) : (
                            /* The scanner named the project or the application, not a repository.
                               Inferring one would be the platform deciding which repository is at
                               fault, so it says what it knows. */
                            <span className="italic text-tone-unknown">
                              recorded against the project, no repository named
                            </span>
                          )}
                        </TableCell>
                        {/* Empty on purpose. A repository is under its project, and the project's row
                            directly above already names the organization — repeating it on every child
                            line is the same fact four times and reads as four different facts. */}
                        <TableCell />
                        <TableCell className="tabular text-right text-xs">{r.open}</TableCell>
                        <TableCell className="tabular text-right text-xs">
                          {r.critical > 0
                            ? <span className="text-sev-critical">{r.critical}</span>
                            : <span className="text-muted-foreground">0</span>}
                        </TableCell>
                        <TableCell className="tabular text-right text-xs">{r.serious}</TableCell>
                        <TableCell className="tabular text-xs text-muted-foreground">
                          {r.newestAt ?? "—"}
                        </TableCell>
                      </TableRow>
                    ))}
                  </Fragment>
                ))}
              </TableBody>
            </Table>
            {/* Paged because a real group has more projects than a screen. Client-side, like every
                other table here: the tree is one row per project-and-repository, so even a large
                organisation is a few hundred rows, and a page turn that waits for a round trip is a
                page turn people stop using. */}
            <Pager paging={treePaging} unit="projects" />
          </div>
          {/* Which control does what, said plainly. Searching narrows this table; clicking a row
              narrows the finding list. They are different actions and the difference is not guessable
              from the controls themselves. */}
          <span className="text-xs text-muted-foreground">
            {data.rows.length} finding{data.rows.length === 1 ? "" : "s"} listed below
            {data.truncated && ` of ${data.summary.total} — narrow the selection to see the rest`}
            {hunt && " · the search filters this table only; click a row to narrow the list"}
            {/* Said rather than reconciled away. A finding on a service two projects both depend on
                belongs to both of them, so the column adds up to more than the estate holds — and
                quietly picking one owner would be the platform deciding whose problem it is. */}
            {shared > 0 && ` · ${shared} finding${shared === 1 ? "" : "s"} appear under more than one `
              + "project because a shared component carries them; the column totals more than the "
              + "estate holds"}
          </span>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Finding</TableHead>
                <TableHead>Severity</TableHead>
                <TableHead>Tool</TableHead>
                <TableHead>Project</TableHead>
                <TableHead>Where</TableHead>
                <TableHead className="text-right">Age</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {paging.rows.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} className="text-xs italic text-tone-unknown">
                    Nothing matches. With the default filters that means your pipelines have no
                    outstanding findings — which is a real answer, not an empty page.
                  </TableCell>
                </TableRow>
              )}
              {paging.rows.map((r) => (
                <TableRow key={r.id}>
                  <TableCell className="max-w-96 text-xs">
                    {/* To the pipeline's OWN detail page, never to the assessment board. Every row
                        here links the same way whether or not an assessment happened to record it —
                        which is the whole point: a delivery team reading its scanner output should not
                        pass through a board built for a different kind of work. */}
                    <Link className="font-medium hover:underline"
                          to={`/pipeline/findings/${r.id}`}>{r.title}</Link>
                    {r.description && (
                      <div className="mt-0.5 line-clamp-2 text-[11px] text-muted-foreground">
                        {r.description}
                      </div>
                    )}
                  </TableCell>
                  <TableCell>
                    <Badge tone={severityTone(r.severity)}>{r.severity.toLowerCase()}</Badge>
                  </TableCell>
                  <TableCell className="font-mono text-[11px]">{r.sourceTool}</TableCell>
                  <TableCell className="text-xs">
                    {r.projectName ?? <span className="italic text-tone-unknown">not attributed</span>}
                  </TableCell>
                  <TableCell className="text-xs">
                    {r.assetName ?? <span className="italic text-tone-unknown">—</span>}
                  </TableCell>
                  <TableCell className="tabular text-right text-xs">
                    {r.ageDays === null ? "—" : `${r.ageDays}d`}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Pager paging={paging} />
        </CardContent>
      </Card>
    </div>
  );
}
