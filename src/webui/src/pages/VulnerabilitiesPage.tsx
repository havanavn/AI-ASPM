import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Bug, Download, Search, ShieldAlert, X } from "lucide-react";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { BarList, GroupedColumns, SeverityBars, SEVERITY_FILL, type Slice } from "@/components/Charts";
import { DateField } from "@/components/DateField";
import { MultiSelect } from "@/components/MultiSelect";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { AnalyseButton } from "@/components/AnalyseButton";
import { Suggestions } from "@/components/Suggestions";
import { Kpi } from "@/components/Kpi";
import { PageSize, Pager, usePaging } from "@/components/Paging";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

interface Bucket { key: string; label: string; open: number; closed: number; serious: number }
interface Option { id: string; name: string; findings: number }
interface Row {
  id: string; title: string; severity: string; reportedSeverity: string | null;
  severityOrdinal: number | null; state: string; findingClass: string; sourceTool: string;
  orgPath: string | null; assetName: string | null; assetId: string | null; assetCount: number;
  applicationName: string | null; projectName: string | null; description: string | null;
  assignee: string | null; claimed: boolean; accepted: boolean; internetFacing: boolean;
  recurrence: number; firstDetectedAt: string | null; lastDetectedAt: string | null;
  closedAt: string | null; closureReason: string | null; closureVerified: boolean;
  ageDays: number | null; requestId: string | null; requestCode: string | null;
}
interface Payload {
  summary: Record<string, number | null>;
  rows: Row[]; matching: number; rowCap: number; truncated: boolean;
  bySeverity: Bucket[]; byClass: Bucket[]; byTool: Bucket[]; byAge: Bucket[]; byOrg: Bucket[];
  trend: { label: string; found: number; closed: number }[];
  toolOptions: Option[]; classOptions: Option[]; assigneeOptions: Option[];
  categoryOptions: Option[]; owaspOptions: Option[]; cweOptions: Option[];
  severityOptions: Option[]; unassignedFindings: number;
}

/** The flags a finding can carry, each a prioritisation axis somebody actually asks for. */
/**
 * The lifecycle position as the table shows it. Not severity tones: where a finding sits in its process
 * and how bad it is are different questions, and one palette answering both invites reading a green
 * "closed" as a low severity.
 *
 * "awaiting verification" is warn, not ok. It is the state most likely to be misread as finished and
 * the one state where nobody has checked anything.
 */
const LIFECYCLE_TONE: Record<string, "ok" | "warn" | "high" | "info" | "neutral"> = {
  OPEN: "high",
  FIXED: "warn",
  REOPEN: "warn",
  // Requested is not accepted. It wears the unresolved tone because nothing has been agreed — a
  // pending request that looked settled would be the easiest number in the platform to improve
  // without improving anything.
  ACCEPTANCE_REQUESTED: "warn",
  CLOSED: "ok",
  ACCEPTED_RISK: "info",
};

const LIFECYCLE_TEXT: Record<string, string> = {
  OPEN: "open",
  FIXED: "awaiting verification",
  REOPEN: "reopened",
  ACCEPTANCE_REQUESTED: "acceptance requested",
  CLOSED: "closed",
  ACCEPTED_RISK: "risk accepted",
};

const FLAGS = [
  { key: "internet_facing", label: "Internet-facing",
    hint: "an affected asset is reachable from the internet" },
  { key: "claimed", label: "Fix claimed",
    hint: "somebody says it is fixed; nobody has verified it" },
  { key: "accepted", label: "Risk accepted", hint: "open under a recorded exception" },
  { key: "overridden", label: "Severity overridden",
    hint: "a human changed the severity the tool reported" },
  { key: "recurring", label: "Recurred", hint: "reappeared after being closed" },
  { key: "unverified_closure", label: "Closed unverified",
    hint: "closed with nobody recorded as having checked" },
] as const;

function readList(params: URLSearchParams, key: string): string[] | null {
  const raw = params.get(key);
  if (raw === null) return null;
  return raw.split(",").filter(Boolean);
}

/**
 * Vulnerability management — the whole finding population, cuttable.
 *
 * <h2>The question this page exists for</h2>
 *
 * "Every open secret on an internet-facing asset that nobody is assigned" is one question, and before
 * this page it took five screens and a mental join. The overview ranks organizations, the application
 * pages rank one application's parts, workload measures the team, composition answers "which
 * dependency" — none of them lets somebody hold the population and cut it.
 *
 * <h2>Everything on the page is computed under the same filter</h2>
 *
 * Headline, five distributions, the trend and the table all come from ONE request under ONE filter.
 * That is not an optimisation: separate requests mean the headline can describe a different population
 * from the table beneath it, and that disagreement is invisible on screen.
 *
 * <h2>What it refuses to show</h2>
 *
 * No SLA column — no service level policy or clock exists in this deployment, so a due date would be
 * computed from nothing and people would work to it. No CWE axis — no weakness taxonomy is recorded,
 * and `finding_class` is a control classification, not a taxonomy. No risk score — DOC-28 owns that
 * model and it is unimplemented; severity and exposure sit side by side instead so a reader weighs
 * them rather than trusting a number nobody can version.
 */
export function VulnerabilitiesPage() {
  const [params, setParams] = useSearchParams();
  const [data, setData] = useState<Payload | null>(null);
  const [failed, setFailed] = useState(false);
  const [nodes, setNodes] = useState<{ id: string; name: string }[]>([]);
  const [draft, setDraft] = useState(params.get("search") ?? "");
  // Bumped when an analysis finishes, so the panel below re-reads the queue rather than leaving
  // somebody staring at the count they had before they pressed the button.
  const [reload, setReload] = useState(0);

  const orgs = readList(params, "org");
  const severities = readList(params, "severity");
  const classes = readList(params, "class");
  const tools = readList(params, "tool");
  const assignees = readList(params, "assignee");
  const unassigned = params.get("unassigned") === "true";
  const state = params.get("state") ?? "";
  const search = params.get("search") ?? "";
  const from = params.get("from") ?? "";
  const to = params.get("to") ?? "";
  const categories = readList(params, "category");
  const owasps = readList(params, "owasp");
  const cwes = readList(params, "cwe");
  const unclassified = params.get("unclassified") === "true";
  const activeFlags = FLAGS.filter((f) => params.get(f.key) === "true");
  const filtered = params.toString() !== "";

  useEffect(() => {
    setFailed(false);
    api.get<Payload>(`/api/ui/vulnerabilities?${params.toString()}`)
      .then(setData).catch(() => setFailed(true));
    // Keyed on the whole query string: every filter is a server filter, so any change is a refetch.
    // Listing them one by one is how the fourth one gets forgotten.
  }, [params.toString()]);

  useEffect(() => {
    api.get<{ nodes: { id: string; name: string }[] }>("/api/ui/applications")
      .then((d) => setNodes(d.nodes ?? [])).catch(() => setNodes([]));
  }, []);

  const set = useCallback((key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value === null) next.delete(key);
    else next.set(key, value);
    setParams(next, { replace: true });
  }, [params, setParams]);

  const setList = useCallback((key: string, next: string[] | null) => {
    set(key, next === null ? null : next.join(","));
  }, [set]);

  // Debounced, because search is a server filter here — the population is up to a few thousand
  // findings and the text is matched against descriptions, which is not a job for the browser.
  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (draft !== search) set("search", draft.trim() === "" ? null : draft);
    }, 350);
    return () => window.clearTimeout(timer);
  }, [draft, search, set]);

  const paging = usePaging(data?.rows ?? []);

  const severityRows = useMemo(() => (data?.bySeverity ?? []).map((b) => ({
    code: b.label, open: b.open, total: b.open + b.closed })), [data]);

  const slice = (buckets: Bucket[]): Slice[] => buckets.map((b) => ({
    key: b.key, label: b.label, value: b.open, population: b.open + b.closed }));

  if (failed) {
    return <p className="p-6 text-sm text-muted-foreground">
      The findings could not be loaded. Reload once the service is available.
    </p>;
  }
  if (!data) return <p className="p-6 text-sm text-muted-foreground">Loading…</p>;
  const s = data.summary;

  return (
    <div className="flex flex-col gap-4 p-4 md:p-6">
      {/* The button in the header, where an action about the whole page belongs — and never on a
          timer. Analysis sends data to a third party, so it stays something a person pressed. */}
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-lg font-semibold tracking-tight">
            <Bug className="size-5 text-primary" /> Vulnerability management
          </h1>
          <p className="text-sm text-muted-foreground">
            Every finding in scope, cuttable by where it is, what it is, who found it, who owns it and
            how it is exposed.
          </p>
        </div>
        <AnalyseButton surface="/vulnerabilities" onDone={() => setReload((n) => n + 1)} />
      </header>

      {/* Above the figures, because every figure below is computed under it. */}
      <Card>
        <CardContent className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <MultiSelect label="Organization" width="w-52"
                         options={nodes.map((n) => ({ id: n.id, name: n.name }))}
                         value={orgs} onChange={(v) => setList("org", v)}
                         placeholder="Everything you can reach" />
            <MultiSelect label="Severity" width="w-44"
                         options={data.severityOptions.map((o) => ({ id: o.id, name: o.name }))}
                         value={severities} onChange={(v) => setList("severity", v)}
                         placeholder="Any severity" />
            <MultiSelect label="Kind" width="w-44"
                         options={data.classOptions.map((o) => ({
                           id: o.id, name: o.name, hint: `${o.findings}` }))}
                         value={classes} onChange={(v) => setList("class", v)}
                         placeholder="Any kind" />
            <MultiSelect label="Found by" width="w-44"
                         options={data.toolOptions.map((o) => ({
                           id: o.id, name: o.name, hint: `${o.findings}` }))}
                         value={tools} onChange={(v) => setList("tool", v)}
                         placeholder="Any tool" />
            {/* "Unassigned" is its own option: 240 of 248 open findings have no owner, so it is the
                dominant state of this estate and a list of names cannot reach it. */}
            <MultiSelect label="Owner" width="w-48"
                         options={data.assigneeOptions.map((o) => ({
                           id: o.id, name: o.name, hint: `${o.findings}` }))}
                         extra={[{ id: "__unassigned__", name: "Unassigned",
                                   hint: `${data.unassignedFindings}` }]}
                         value={unassigned ? [...(assignees ?? []), "__unassigned__"] : assignees}
                         onChange={(v) => {
                           const next = new URLSearchParams(params);
                           if (v === null) { next.delete("assignee"); next.delete("unassigned"); }
                           else {
                             const people = v.filter((x) => x !== "__unassigned__");
                             if (v.includes("__unassigned__")) next.set("unassigned", "true");
                             else next.delete("unassigned");
                             if (people.length > 0 || !v.includes("__unassigned__")) {
                               next.set("assignee", people.join(","));
                             } else next.delete("assignee");
                           }
                           setParams(next, { replace: true });
                         }}
                         placeholder="Anyone" />

            {/* On FIRST detection, not last. "What appeared in Q2" is the question a date range
                answers; filtering on last detection would instead include a five-year-old weakness a
                scan re-confirmed yesterday and exclude one found in the window and since fixed. */}
            {/* The three classifications. Codes, not display names — a renamed category must not
                invalidate a link somebody shared. */}
            <MultiSelect label="Executive risk" width="w-52"
                         options={data.categoryOptions.map((o) => ({
                           id: o.id, name: o.name, hint: `${o.findings}` }))}
                         value={categories} onChange={(v) => setList("category", v)}
                         placeholder="Any category" />
            <MultiSelect label="OWASP 2025" width="w-52"
                         options={data.owaspOptions.map((o) => ({
                           id: o.id, name: o.name, hint: `${o.findings}` }))}
                         value={owasps} onChange={(v) => setList("owasp", v)}
                         placeholder="Any OWASP entry" />
            <MultiSelect label="CWE" width="w-52"
                         options={data.cweOptions.map((o) => ({
                           id: o.id, name: o.name, hint: `${o.findings}` }))}
                         value={cwes} onChange={(v) => setList("cwe", v)}
                         placeholder="Any CWE" />

            <div className="flex flex-col gap-1">
              <Label htmlFor="vuln-from">Detected from</Label>
              <DateField id="vuln-from" value={from} max={to || undefined} className="w-40"
                         onChange={(iso) => set("from", iso === "" ? null : iso)} />
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor="vuln-to">to</Label>
              <DateField id="vuln-to" value={to} min={from || undefined} className="w-40"
                         onChange={(iso) => set("to", iso === "" ? null : iso)} />
            </div>

            <div className="flex flex-col gap-1">
              <Label htmlFor="vuln-search">Search</Label>
              <div className="relative w-56">
                <Search className="pointer-events-none absolute left-2 top-1/2 size-3.5
                                   -translate-y-1/2 text-muted-foreground" />
                <Input id="vuln-search" value={draft} className="pl-7 pr-7"
                       placeholder="title or description"
                       onChange={(e) => setDraft(e.target.value)} />
                {draft && (
                  <button type="button" aria-label="Clear the search"
                          onClick={() => setDraft("")}
                          className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground
                                     hover:text-foreground">
                    <X className="size-3.5" />
                  </button>
                )}
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-end gap-4">
            <div className="flex flex-col gap-1">
              <Label>Or the last…</Label>
              {/* Presets beside the two date fields, not instead of them. "The last 30 days" is a
                  question people ask daily and four clicks in a calendar is four too many; an exact
                  window is a question people ask occasionally and only the fields can express it. */}
              <div className="flex gap-1.5">
                {[["7", "7 days"], ["30", "30 days"], ["90", "90 days"], ["365", "year"]].map(
                  ([days, label]) => {
                    const start = new Date(Date.now() - Number(days) * 86_400_000)
                      .toISOString().slice(0, 10);
                    const on = from === start && to === "";
                    return (
                      <Button key={days} size="sm" variant={on ? "secondary" : "ghost"}
                              onClick={() => {
                                const next = new URLSearchParams(params);
                                if (on) { next.delete("from"); next.delete("to"); }
                                else { next.set("from", start); next.delete("to"); }
                                setParams(next, { replace: true });
                              }}>
                        {label}
                      </Button>
                    );
                  })}
              </div>
            </div>
            {/* ONE axis, and the states people actually name.
                
                The first version offered both: the coarse open/closed pair AND the six lifecycle
                values, nine rows in all — "Open (incl. awaiting verification)" sitting above "— open,
                nothing claimed" and "Not open" above "— closed and verified". Two of those pairs
                overlap, none of them reads as an obvious choice, and a filter somebody has to decode
                before using is a filter they use wrongly. The coarse axis is still there for API
                callers; it does not belong in a picker aimed at a person.
                
                A select rather than a row of toggles: toggles that behave like one radio group read as
                independent filters — somebody clicks Open expecting to narrow and cannot see that
                Closed just switched off. */}
            <div className="flex flex-col gap-1">
              <Label htmlFor="vuln-state">State</Label>
              <Select value={state || "__all__"}
                      onValueChange={(v) => set("state", v === "__all__" ? null : v)}>
                <SelectTrigger id="vuln-state" className="w-52"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="__all__">All states</SelectItem>
                  {/* In lifecycle order, so the list reads as the path a finding takes rather than as
                      an alphabetical set. The values keep their LC_ prefix because the API still
                      accepts the coarse OPEN/CLOSED, and OPEN means a different set on each axis. */}
                  <SelectItem value="LC_OPEN">Open</SelectItem>
                  <SelectItem value="LC_FIXED">Fixed — awaiting verification</SelectItem>
                  <SelectItem value="LC_REOPEN">Reopened</SelectItem>
                  <SelectItem value="LC_CLOSED">Closed</SelectItem>
                  <SelectItem value="LC_ACCEPTED_RISK">Risk accepted</SelectItem>
                  {/* Listed because a finding can sit here, and a state nothing can filter for is a
                      state nobody finds. It exists only because approving your own risk acceptance is
                      forbidden, so there is a wait between asking and being granted. */}
                  <SelectItem value="LC_ACCEPTANCE_REQUESTED">Acceptance requested</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1">
              <Label>Only findings that are…</Label>
              {/* Toggles, not a dropdown. These are the prioritisation questions, and a filter hidden
                  behind a click is a filter people forget is on. */}
              <div className="flex flex-wrap gap-1.5">
                {/* Its own toggle rather than a NULL option in each of the three pickers. 658 findings
                    predate these fields, and "what still needs classifying" is one question, not
                    three. */}
                <Button size="sm" variant={unclassified ? "secondary" : "ghost"}
                        title="Missing at least one of the three classifications"
                        onClick={() => set("unclassified", unclassified ? null : "true")}>
                  Not yet classified
                </Button>
                {FLAGS.map((flag) => {
                  const on = params.get(flag.key) === "true";
                  return (
                    <Button key={flag.key} size="sm" title={flag.hint}
                            variant={on ? "secondary" : "ghost"}
                            onClick={() => set(flag.key, on ? null : "true")}>
                      {flag.label}
                    </Button>
                  );
                })}
              </div>
            </div>
          </div>

          {filtered && (
            <p className="flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
              <span>
                {data.matching} finding{data.matching === 1 ? "" : "s"} match
                {activeFlags.length > 0 && ` · ${activeFlags.map((f) => f.label).join(" + ")}`}
                {search && ` · matching “${search}”`}.
              </span>
              {(from || to) && (
                <span>
                  Detected {from || "any time"} → {to || "now"}.
                </span>
              )}
              <button type="button" className="text-primary hover:underline"
                      onClick={() => { setDraft(""); setParams({}, { replace: true }); }}>
                Clear filters
              </button>
            </p>
          )}
        </CardContent>
      </Card>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-6">
        <Kpi label="Open" value={String(s.open ?? 0)}
             hint={`${s.closed ?? 0} closed · ${s.total ?? 0} in scope`} />
        <Kpi label="Critical and high" value={String(s.seriousOpen ?? 0)} tone="critical"
             hint={`${s.criticalOpen ?? 0} critical · ${s.highOpen ?? 0} high`} />
        {/* The one figure a reader should look at before severity. A medium on something the internet
            can reach outranks a critical on a laptop, and no score on this page claims otherwise. */}
        <Kpi label="Internet-facing" value={String(s.internetFacingOpen ?? 0)} tone="warn"
             hint="an affected asset is publicly reachable" />
        <Kpi label="Nobody assigned" value={String(s.unassignedOpen ?? 0)}
             tone={(s.unassignedOpen ?? 0) > 0 ? "warn" : "ok"}
             hint="open with no owner recorded" />
        <Kpi label="Open over 90 days" value={String(s.openOver90 ?? 0)}
             hint={s.medianOpenDays === null
               ? "nothing open to age"
               : `median ${s.medianOpenDays} days · oldest ${s.oldestOpenDays}`} />
        {/* Claimed-but-unverified is its own figure because a claim is not a closure. */}
        <Kpi label="Fix claimed" value={String(s.claimedOpen ?? 0)}
             hint="still open — nobody has verified the fix" />
      </div>

      {data.truncated && (
        <p className="flex items-start gap-1.5 rounded border border-tone-warn/40 bg-tone-warn/10 p-2
                      text-xs text-tone-warn">
          <ShieldAlert className="mt-0.5 size-3.5 shrink-0" />
          The table shows the first {data.rowCap} of {data.matching} matching findings, worst first.
          Every figure and chart above counts all {data.matching}. Narrow the filters to see the rest —
          the cap is on the list, not on the counting.
        </p>
      )}

      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader className="pb-2">
            <CardTitle>Severity</CardTitle>
            <CardDescription>
              Open against everything ever found at that severity, so a band reading zero open of
              forty is a band somebody cleared — not a band nothing was ever found in.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <SeverityBars rows={severityRows} empty="No findings in this filter." />
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle>Kind of weakness</CardTitle>
            <CardDescription>
              The control classification the platform records. Not a CWE or OWASP category — none is
              stored, and naming the axis honestly is what separates a chart that directs a training
              budget from one that looks like it does.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <BarList slices={slice(data.byClass)} empty="No findings in this filter." />
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle>Who found it</CardTitle>
            <CardDescription>
              A tool contributing nothing for months is a tool that stopped running, and that looks
              identical to a clean estate until you count per tool.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <BarList slices={slice(data.byTool)} empty="No findings in this filter." />
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader className="pb-2">
            <CardTitle>Found against closed</CardTitle>
            <CardDescription>
              Both series, always. Closures alone show a team working hard while the estate gets worse;
              discoveries alone show the opposite. The gap is the only thing here that says whether the
              backlog is growing.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <GroupedColumns
              points={data.trend.map((p) => ({
                label: p.label, values: { found: p.found, closed: p.closed } }))}
              series={[
                { key: "found", label: "Found", className: "bg-sev-high" },
                { key: "closed", label: "Closed", className: "bg-tone-ok" },
              ]}
              empty="No activity in this window." />
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle>How long they have been open</CardTitle>
            <CardDescription>
              Bands, not a histogram of days. "How many have been open past three months" changes a
              decision; the exact spread of days does not.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <BarList slices={data.byAge.map((b) => ({
              key: b.key, label: `${b.label} days`, value: b.open, population: 0 }))}
              tone="warn" empty="Nothing open in this filter." />
            <p className="mt-2 text-[11px] text-muted-foreground">
              {s.openOver30 ?? 0} over 30 days · {s.openOver90 ?? 0} over 90 · {s.openOver180 ?? 0}
              {" "}over 180. Age is shown rather than time-to-SLA because no service level policy is
              configured in this deployment — a due date computed from a default is one people would
              work to.
            </p>
          </CardContent>
        </Card>
      </div>

      {data.byOrg.length > 1 && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle>Where they are</CardTitle>
            <CardDescription>
              By the organization the finding's scope resolved to. Click a bar's organization in the
              filter above to narrow everything on the page to it.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <BarList slices={slice(data.byOrg).slice(0, 12)} empty="No findings in this filter." />
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2 pb-2">
          <div>
            <CardTitle>Findings</CardTitle>
            <CardDescription>
              Open first, then by severity, then oldest first — the order a queue is worked in.
            </CardDescription>
          </div>
          <div className="flex items-center gap-2">
            {/* A link, not a fetch. The browser downloads it with the session cookie attached, so
                nothing has to hold a spreadsheet in memory to hand it to a download. The query string
                is the page's own — the file therefore contains exactly what the screen shows, and it
                carries a sheet naming the filters so it cannot later be read as the whole estate. */}
            <Button asChild size="sm" variant="secondary">
              <a href={`/api/ui/vulnerabilities/export?${params.toString()}`}>
                <Download className="size-3.5" /> Export {data.matching}
                {data.matching === 1 ? " finding" : " findings"}
              </a>
            </Button>
            <PageSize size={paging.size} onChange={paging.setSize} />
          </div>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Finding</TableHead>
                  <TableHead>Severity</TableHead>
                  <TableHead>Kind</TableHead>
                  <TableHead>Found by</TableHead>
                  <TableHead>Organization</TableHead>
                  <TableHead>Application</TableHead>
                  <TableHead>Project</TableHead>
                  <TableHead>Asset</TableHead>
                  <TableHead>Owner</TableHead>
                  <TableHead className="text-right">Age</TableHead>
                  <TableHead>State</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {paging.rows.map((row) => (
                  <TableRow key={row.id}>
                    <TableCell className="max-w-80">
                      {row.requestId ? (
                        <Link to={`/board/${row.requestId}/findings/${row.id}`}
                              className="font-medium hover:text-primary hover:underline">
                          {row.title}
                        </Link>
                      ) : <span className="font-medium">{row.title}</span>}
                      <div className="flex flex-wrap items-center gap-1 pt-0.5">
                        {row.internetFacing && <Badge tone="warn">internet-facing</Badge>}
                        {row.claimed && <Badge tone="info">fix claimed</Badge>}
                        {row.accepted && <Badge tone="neutral">risk accepted</Badge>}
                        {row.recurrence > 0 && (
                          <Badge tone="high">recurred ×{row.recurrence}</Badge>
                        )}
                        {row.requestCode && (
                          <span className="text-[10px] text-muted-foreground">
                            {row.requestCode}
                          </span>
                        )}
                      </div>
                    </TableCell>
                    <TableCell>
                      <span className="flex items-center gap-1.5">
                        <span className={cn("inline-block size-2.5 rounded-sm",
                                            SEVERITY_FILL[row.severity] ?? "bg-tone-unknown")} />
                        <span className="text-xs">{row.severity}</span>
                      </span>
                      {/* Only when they disagree. Printing "reported CRITICAL" beside every critical
                          would bury the six rows where a human changed the grade. */}
                      {row.reportedSeverity && row.reportedSeverity !== row.severity && (
                        <div className="text-[10px] text-tone-warn">
                          tool said {row.reportedSeverity}
                        </div>
                      )}
                    </TableCell>
                    <TableCell className="text-xs">{row.findingClass}</TableCell>
                    <TableCell className="font-mono text-[11px]">{row.sourceTool}</TableCell>
                    {/* Application, project, asset — three columns rather than one "Where", because
                        these are what people group and filter a finding list by, and folding them
                        into one cell means none of them can be sorted. */}
                    {/* Its own column now. It was a caption under the application, which made it
                        unsortable and unreadable at a glance — and the organization is who ANSWERS for
                        the finding, which is a different question from which application carries it. */}
                    <TableCell className="text-xs">
                      {row.orgPath
                        ? row.orgPath.split(" › ").at(-1)
                        /* Named, not dashed. These findings have no organization recorded at all — they
                           were invisible on this dashboard until the scope predicate stopped dropping
                           NULL — and a bare dash reads as "nothing to say" rather than as the gap it
                           is. Somebody should be able to see them and ask why. */
                        : <span className="italic text-tone-unknown">no org recorded</span>}
                      {row.orgPath && row.orgPath.includes(" › ") && (
                        // The full path underneath, because "Payments Core" means different things in
                        // two operating companies and the leaf alone can be ambiguous.
                        <div className="text-[10px] text-muted-foreground">{row.orgPath}</div>
                      )}
                    </TableCell>
                    <TableCell className="text-xs">
                      {row.applicationName
                        ?? <span className="italic text-tone-unknown">—</span>}
                    </TableCell>
                    <TableCell className="text-xs">
                      {row.projectName ?? <span className="italic text-tone-unknown">—</span>}
                    </TableCell>
                    <TableCell className="text-xs">
                      {row.assetName
                        ? <span>{row.assetName}
                            {row.assetCount > 1 && (
                              <span className="text-muted-foreground"> +{row.assetCount - 1}</span>
                            )}
                          </span>
                        : <span className="italic text-tone-unknown">no asset linked</span>}
                    </TableCell>
                    <TableCell className="text-xs">
                      {row.assignee ?? <span className="italic text-tone-unknown">nobody</span>}
                    </TableCell>
                    <TableCell className="tabular text-right text-xs">
                      {row.ageDays === null ? "—" : `${row.ageDays}d`}
                    </TableCell>
                    <TableCell>
                      {/* The lifecycle position, not the coarse open/closed. "awaiting verification"
                          and "open" used to render identically here, which hid the queue of claimed
                          fixes nobody had checked — the one list a verifier most needs. */}
                      <div className="flex flex-col gap-0.5">
                        <Badge tone={LIFECYCLE_TONE[row.state] ?? "neutral"}>
                          {LIFECYCLE_TEXT[row.state] ?? row.state.toLowerCase()}
                        </Badge>
                        {row.state === "CLOSED" && !row.closureVerified && (
                          <span className="text-[10px] text-tone-warn">nobody signed it off</span>
                        )}
                        {row.closureReason && row.state !== "ACCEPTED_RISK" && (
                          <span className="text-[10px] text-muted-foreground">
                            {row.closureReason}
                          </span>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
                {paging.rows.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={11} className="text-center text-sm text-muted-foreground">
                      No finding matches these filters.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </div>
          <Pager paging={paging} unit="findings" />
        </CardContent>
      </Card>

      {/* BELOW the findings, at the user's request. It was above, on the reasoning that a suggestion
          changes how the list should be read — but the panel is long, and putting a long panel in
          front of the thing somebody opened the page for costs them a scroll every single visit. A
          reader who wants the queue will scroll once; a reader who wants a finding should not have to. */}
      <Suggestions key={reload} />
    </div>
  );
}
