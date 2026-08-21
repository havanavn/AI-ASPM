import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { ChevronDown, ChevronRight, Search, Trash2, Upload, X } from "lucide-react";
import { api } from "@/lib/api";
import { Kpi } from "@/components/Kpi";
import { StackedColumns, SEVERITY_FILL } from "@/components/Charts";
import { Trend } from "@/components/Trend";
import { Pager, usePaging } from "@/components/Paging";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";

/**
 * Software composition management.
 *
 * What this page used to be was an SBOM COVERAGE list: which assets had submitted a bill of
 * materials and how fresh it was. That is one question out of the module, and not the one anybody
 * opens it to ask. The questions it now answers, in the order somebody asks them:
 *
 * 1. Is the estate covered at all, and is that getting better or worse over time?
 * 2. What is arriving and what is being fixed — new SBOMs, new advisories, advisories closed?
 * 3. Where, in the tree the business recognises, is the exposure? Application › project ›
 *    repository, with the same columns at every level.
 * 4. This CVE — where is it? Every application, project and repository it reaches.
 * 5. This package — where is it, and at which versions?
 * 6. This transitive dependency — what pulled it in, so what do I upgrade?
 *
 * **Coverage is stated before exposure, always.** "12 open advisories" over an estate where three of
 * nineteen repositories have ever submitted an SBOM is not a small problem; it is an unmeasured one,
 * and product principle 1 makes that the most dangerous thing this page could imply. The first KPI
 * is the covered fraction, and the second is what has never been looked at.
 */

interface Summary {
  assets: number; assetsWithSbom: number; assetsCurrent: number; snapshots: number;
  components: number; directComponents: number; vulnerableComponents: number;
  advisoriesOpen: number; criticalOpen: number; highOpen: number; mediumOpen: number;
  lowOpen: number; unratedOpen: number; fixableOpen: number; resolvedLast90Days: number;
  latestSnapshotAt: string | null;
}
interface Month {
  label: string; snapshots: number; appeared: number; resolved: number; components: number;
}
interface AdvisoryRow {
  id: string; key: string; severity: string | null; cvss: number | null; summary: string | null;
  publishedAt: string | null; firstRecordedAt: string | null; componentCount: number;
  assetCount: number; applicationCount: number; unresolved: number; source: string;
}
interface ComponentRow {
  id: string; purl: string; ecosystem: string; name: string; version: string;
  assetCount: number; applicationCount: number; advisoryOpen: number; criticalOpen: number;
  highOpen: number; direct: boolean; licenses: string[];
}
interface TreeRow {
  id: string; name: string; typeCode: string; parentId: string | null; owningNodeName: string | null;
  /** The organization above the owning team. Same definition as the projects and CI/CD tables. */
  orgId: string | null; orgName: string | null;
  parts: number; children: number; sbomParts: number; componentCount: number; directCount: number;
  advisoryOpen: number; criticalOpen: number; highOpen: number; mediumOpen: number; lowOpen: number;
  vulnerableComponents: number; fixableOpen: number; latestSnapshotAt: string | null;
  sbomQuality: string | null; submitsSbom: boolean;
}
interface LocationRow {
  assetId: string; assetName: string; assetTypeCode: string; path: string[];
  applicationId: string | null; applicationName: string | null;
  componentName: string; componentVersion: string; direct: boolean; fixedVersion: string | null;
}
interface NodeAdvisory {
  advisoryId: string; key: string; severity: string | null; ordinal: number; cvss: number | null;
  summary: string | null; description: string | null; cweIds: string[]; references: string[];
  dataSource: string | null; status: string | null;
  publishedAt: string | null; detectedAt: string | null; source: string;
  componentId: string; componentName: string; componentVersion: string; purl: string;
  ecosystem: string; direct: boolean; fixedVersion: string | null; recommendation: string;
  assetId: string; assetName: string; assetTypeCode: string;
  applicationName: string | null; projectName: string | null; snapshotAt: string | null;
}
interface Edge {
  parentId: string; parentName: string; parentVersion: string;
  childId: string; childName: string; childVersion: string; childDirect: boolean;
  childAdvisoryOpen: number; childWorstSeverity: string | null;
}
interface Payload {
  summary: Summary; timeline: Month[]; topAdvisories: AdvisoryRow[]; topComponents: ComponentRow[];
}

type Tab = "estate" | "advisories" | "components";

const ANY = "__any__";

export function CompositionPage() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get("tab") as Tab) ?? "estate";
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [nodes, setNodes] = useState<{ id: string; name: string }[]>([]);
  const org = params.get("org") ?? "";
  const severity = params.get("severity") ?? "";

  useEffect(() => {
    setData(null);
    api.get<Payload>(`/api/ui/dependencies${org ? `?org=${org}` : ""}`)
      .then(setData).catch((e) => setError(e.message));
  }, [org]);

  // The organization list comes from the inventory endpoint, which is the same list the applications
  // page filters by — one source, so the two pages cannot offer different organizations.
  useEffect(() => {
    api.get<{ nodes: { id: string; name: string }[] }>("/api/ui/applications")
      .then((d) => setNodes(d.nodes ?? [])).catch(() => setNodes([]));
  }, []);

  function setOrg(next: string) {
    const p = new URLSearchParams(params);
    if (!next || next === ANY) p.delete("org"); else p.set("org", next);
    setParams(p, { replace: true });
  }

  /** Toggles one band. Nothing selected means every band, which is what an empty filter should mean. */
  function toggleSeverity(band: string) {
    const chosen = severity ? severity.split(",") : [];
    const next = chosen.includes(band) ? chosen.filter((b) => b !== band) : [...chosen, band];
    const p = new URLSearchParams(params);
    if (next.length === 0) p.delete("severity"); else p.set("severity", next.join(","));
    setParams(p, { replace: true });
  }

  function setTab(next: Tab) {
    const p = new URLSearchParams(params);
    p.set("tab", next);
    setParams(p, { replace: true });
  }

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>;

  const s = data.summary;
  const uncovered = Math.max(0, s.assets - s.assetsWithSbom);
  const coveragePercent = s.assets === 0 ? 0 : Math.round((100 * s.assetsWithSbom) / s.assets);

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold tracking-tight">Software composition</h1>
        <p className="text-xs text-muted-foreground">
          What the estate is built out of, which published vulnerabilities those parts carry, and how
          much of it has ever been measured.
        </p>
      </div>

      {/* The filter sits ABOVE the figures, because every figure below is computed under it. A
          filter placed beside a table would leave the KPI row describing a different population
          from the rows underneath it, which is the disagreement a reader cannot diagnose. */}
      <Card>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="flex w-64 flex-col gap-1">
            <Label>Organization</Label>
            <Select value={org || ANY} onValueChange={setOrg}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={ANY}>Everything you can reach</SelectItem>
                {nodes.map((n) => <SelectItem key={n.id} value={n.id}>{n.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1">
            <Label>Severity</Label>
            {/* Toggles rather than a dropdown: the question is "which of these", and a multi-select
                that hides the current answer behind a click is a filter people forget is on. Nothing
                selected means everything, which is what an empty filter should mean. */}
            <div className="flex gap-1">
              {["CRITICAL", "HIGH", "MEDIUM", "LOW"].map((band) => {
                const on = severity.split(",").includes(band);
                return (
                  <button key={band} onClick={() => toggleSeverity(band)}
                          className={cn("rounded-md border px-2 py-1 text-xs",
                            on ? "border-transparent text-background" : "text-muted-foreground",
                            on && (SEVERITY_FILL[band] ?? ""))}>
                    {band[0] + band.slice(1).toLowerCase()}
                  </button>
                );
              })}
            </div>
          </div>
          {(org || severity) && (
            <>
              <Button variant="ghost" size="sm"
                      onClick={() => setParams(new URLSearchParams(
                        tab === "estate" ? {} : { tab }), { replace: true })}><X /> Clear</Button>
              <span className="pb-2 text-xs text-muted-foreground">
                {org && "Every figure on this page is narrowed to this organization. "}
                {severity && "The tree shows only rows carrying an open advisory at the chosen "
                  + "severities, and the advisory lists show only those advisories — the KPI row "
                  + "above stays over everything, because a headline that moved with a filter is a "
                  + "headline nobody can compare."}
              </span>
            </>
          )}
        </CardContent>
      </Card>

      {/* --------------------------------------------------------------------------------------
          COVERAGE FIRST. An advisory count over an estate nobody has scanned is a small number for
          the worst possible reason, and it is the number an executive reads first.
      -------------------------------------------------------------------------------------- */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-6">
        <Kpi label="Parts with an SBOM" value={`${s.assetsWithSbom} of ${s.assets}`}
             tone={coveragePercent < 50 ? "warn" : "ok"}
             hint={`${coveragePercent}% · ${s.assetsCurrent} still current`} />
        <Kpi label="Never submitted" value={uncovered} tone="warn"
             hint="these read as clean because nothing looked" />
        <Kpi label="Open advisories" value={s.advisoriesOpen} tone="info"
             hint={`over ${s.vulnerableComponents} of ${s.components} components`} />
        <Kpi label="Critical and high" value={s.criticalOpen + s.highOpen} tone="critical"
             hint={`${s.criticalOpen} critical · ${s.highOpen} high`} />
        <Kpi label="Have a fix published" value={s.fixableOpen} tone="ok"
             hint={s.advisoriesOpen > 0
               ? `of ${s.advisoriesOpen} open — the rest need a decision`
               : "nothing open"} />
        <Kpi label="Closed in 90 days" value={s.resolvedLast90Days} tone="ok"
             hint="component genuinely upgraded away" />
      </div>

      {s.unratedOpen > 0 && (
        <div className="rounded-lg border border-tone-warn/40 bg-card px-4 py-3 text-sm">
          <span className="tabular font-semibold text-tone-warn">{s.unratedOpen}</span> open
          {" "}{s.unratedOpen === 1 ? "advisory carries" : "advisories carry"} no severity rating from
          the tool that reported them. They are counted separately and never folded into LOW — an
          advisory nobody rated is not a low-severity advisory, it is one nobody has looked at.
        </div>
      )}

      <div className="flex gap-1 border-b">
        {([["estate", "The estate"], ["advisories", "Find a CVE"],
           ["components", "Find a package"]] as [Tab, string][]).map(([key, label]) => (
          <button key={key} onClick={() => setTab(key)}
                  className={cn("border-b-2 px-3 py-2 text-sm",
                    tab === key ? "border-primary font-medium text-foreground"
                                : "border-transparent text-muted-foreground hover:text-foreground")}>
            {label}
          </button>
        ))}
      </div>

      {tab === "estate" && <Estate data={data} org={org} severity={severity} onOrg={setOrg} />}
      {tab === "advisories" && <AdvisorySearch initial={data.topAdvisories} />}
      {tab === "components" && <ComponentSearch initial={data.topComponents} />}
    </div>
  );
}

// ================================================================================================
// The estate: what arrived and what closed, then where it all sits
// ================================================================================================

function Estate({ data, org, severity, onOrg }: {
  data: Payload; org: string; severity: string; onOrg: (next: string) => void;
}) {
  const measured = data.timeline.some((m) => m.snapshots + m.appeared + m.resolved > 0);
  return (
    <div className="flex flex-col gap-5">
      <div className="grid gap-5 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Advisories appearing and closing</CardTitle>
            <CardDescription>
              Appearing is when a submitted document first named the advisory against a component we
              hold. Closing is when the component STOPPED being affected — an upgrade — and not when
              somebody closed a ticket. A ticket closed as accepted risk leaves the component
              vulnerable, and folding the two together is how a chart shows a backlog falling while
              the estate does not change.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Trend unit="month" measured={measured}
                   weeks={data.timeline.map((m) => ({
                     label: m.label, opened: m.appeared, closed: m.resolved,
                   }))} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Bills of materials submitted</CardTitle>
            <CardDescription>
              One column per month. A month with no submission is a month in which nothing was
              measured, which is why the empty columns are drawn rather than skipped — a pipeline
              that was switched off looks exactly like a quiet month on a chart that omits them.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <StackedColumns
              points={data.timeline.map((m) => ({
                label: m.label,
                parts: [{ key: "snapshots", value: m.snapshots, className: "bg-tone-info" }],
              }))}
              legend={[{ key: "snapshots", label: "SBOMs submitted", className: "bg-tone-info" }]}
              empty="No bill of materials has ever been submitted in your scope." />
          </CardContent>
        </Card>
      </div>

      <EstateTree org={org} severity={severity} onOrg={onOrg} />
    </div>
  );
}

/**
 * The application → project → repository tree.
 *
 * Fetched a level at a time. The estate is a graph of unknown depth and a reader opens one branch;
 * sending all of it to render one screen is a payload that grows with the company rather than with
 * the page. Every row's figures cover its whole subtree, so a collapsed row is never a smaller
 * number than the rows it hides.
 */
/**
 * Uploading a new bill of materials for one artifact, and retiring one that is gone.
 *
 * <h2>Upload is a replace, and that is the whole model</h2>
 *
 * Submitting for an artifact that already has a snapshot does not add a second inventory — it
 * supersedes the previous one and re-derives the advisory list, resolving anything the new document
 * no longer contains. So this is the "we fixed it, here is the proof" action, and the fix date is
 * the submission date. The superseded snapshot is kept; it is the record of what the build contained
 * before, and a vulnerability found against it was really there.
 *
 * <h2>Remove is a retire, and there is deliberately no delete</h2>
 *
 * A snapshot is immutable (INV-SBM-01) and no DELETE is granted on it anywhere in the schema. The
 * thing somebody actually wants — "this repository is gone, stop counting it" — is retiring the
 * artifact: it leaves the estate, its history stays, and the findings against it survive. A delete
 * would make the estate look cleaner by destroying the evidence, which is the one outcome this
 * product must never make easy.
 */
function SbomActions({ row, onDone }: { row: TreeRow; onDone: () => void }) {
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  async function upload(file: File) {
    setBusy(true);
    setNote(null);
    try {
      const text = await file.text();
      let document: unknown;
      try {
        document = JSON.parse(text);
      } catch {
        // Named precisely. "Upload failed" would send somebody to check their network when what
        // they have is a Trivy table-format report or an XML CycloneDX.
        throw new Error("That file is not JSON. Export CycloneDX or Trivy JSON, not table output.");
      }
      // The UI route, not /api/v1/sbom-submissions. That one is class F — the pipeline's door — and
      // the dispatcher requires a service principal for it, so a signed-in human is refused there.
      // Both run the same ingestion; only the way the caller is authenticated differs.
      const result = await api.post<{
        warnings?: string[]; componentCount?: number; replacedSnapshotId?: string | null;
      }>(`/api/ui/dependencies/artifact/${row.id}/sbom`, { document });
      const warnings = result.warnings ?? [];
      const what = result.replacedSnapshotId ? "Replaced" : "Stored";
      setNote(`${what}${result.componentCount ? ` — ${result.componentCount} components` : ""}.`
        + (warnings.length ? ` ${warnings.length} warning: ${warnings.join("; ")}` : ""));
      onDone();
    } catch (e) {
      setNote(e instanceof Error ? e.message : "The submission was refused.");
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  }

  async function retire() {
    setBusy(true);
    try {
      await api.post(`/api/ui/dependencies/artifact/${row.id}/retire`,
        { reason: "retired from the software composition inventory" });
      setConfirming(false);
      onDone();
    } catch (e) {
      setNote(e instanceof Error ? e.message : "It could not be retired.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex items-center gap-1">
        <input ref={input} type="file" accept=".json,application/json" className="hidden"
               onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload(f); }} />
        <Button size="sm" variant="ghost" disabled={busy}
                title="Submit a new SBOM. It replaces the current one and re-derives the CVE list."
                onClick={() => input.current?.click()}>
          <Upload className="size-3.5" /> SBOM
        </Button>
        {!confirming ? (
          <Button size="sm" variant="ghost" disabled={busy}
                  title="Retire this artifact. Snapshots and findings are kept."
                  onClick={() => setConfirming(true)}>
            <Trash2 className="size-3.5" />
          </Button>
        ) : (
          // Inline, never a window.confirm. The same objection as the credential revoke flow: a
          // browser dialog cannot say what is kept and what is not, and that is the only thing
          // somebody needs to know before pressing this.
          <span className="flex items-center gap-1">
            <span className="text-[11px] text-muted-foreground">
              Retire? History is kept.
            </span>
            <Button size="sm" variant="destructive" disabled={busy} onClick={() => void retire()}>
              Retire
            </Button>
            <Button size="sm" variant="ghost" disabled={busy}
                    onClick={() => setConfirming(false)}>Cancel</Button>
          </span>
        )}
      </div>
      {note && <span className="max-w-64 text-right text-[10px] text-muted-foreground">{note}</span>}
    </div>
  );
}

// `onOrg` is threaded from the page rather than re-derived here. The filter has one implementation
// and one place that owns the query string; a second copy would drift the day either changes.
function EstateTree({ org, severity, onOrg }: {
  org: string; severity: string; onOrg: (next: string) => void;
}) {
  const [levels, setLevels] = useState<Record<string, TreeRow[]>>({});
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);
  // The row whose advisories are being listed below. One at a time: this is a work list somebody
  // reads top to bottom, and three of them open at once is three lists nobody finishes.
  const [inspecting, setInspecting] = useState<TreeRow | null>(null);

  const load = useCallback((parent: string | null) => {
    const key = parent ?? "";
    setBusy((b) => new Set(b).add(key));
    const query = [parent ? `parent=${parent}` : "", org ? `org=${org}` : "",
                   severity ? `severity=${severity}` : ""].filter(Boolean).join("&");
    api.get<{ rows: TreeRow[] }>(`/api/ui/dependencies/tree${query ? `?${query}` : ""}`)
      .then((d) => setLevels((l) => ({ ...l, [key]: d.rows })))
      .catch((e) => setError(e.message))
      .finally(() => setBusy((b) => { const n = new Set(b); n.delete(key); return n; }));
  }, [org, severity]);

  // Reload from the root when the filter changes, and forget what was expanded: a branch opened
  // under the old filter may not exist under the new one, and leaving it on screen would show rows
  // the filter excludes.
  useEffect(() => { setLevels({}); setOpen(new Set()); load(null); }, [load]);

  function toggle(row: TreeRow) {
    const next = new Set(open);
    if (next.has(row.id)) {
      next.delete(row.id);
    } else {
      next.add(row.id);
      if (!levels[row.id]) load(row.id);
    }
    setOpen(next);
  }

  // Flattened for rendering, so ONE table carries the whole visible tree and the columns line up
  // across levels. A nested table per level would give each level its own column widths, and two
  // rows at different depths would stop being comparable — which is the entire point of a rollup
  // that reports the same columns everywhere.
  const visible = useMemo(() => {
    const out: { row: TreeRow; depth: number }[] = [];
    const walk = (parent: string | null, depth: number) => {
      for (const row of levels[parent ?? ""] ?? []) {
        out.push({ row, depth });
        if (open.has(row.id)) walk(row.id, depth + 1);
      }
    };
    walk(null, 0);
    return out;
  }, [levels, open]);

  const paging = usePaging(visible);

  return (
    <Card className="overflow-hidden">
      <CardHeader>
        <CardTitle>The estate, by where the code lives</CardTitle>
        <CardDescription>
          Application, then the projects that deliver it, then the repositories a pipeline builds.
          Every row counts its whole subtree, and counts an advisory once however many components
          under it are affected — so these figures deliberately do not add up across rows.
        </CardDescription>
      </CardHeader>
      {error && <CardContent className="text-sm text-destructive">{error}</CardContent>}
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Organization</TableHead>
            <TableHead className="text-right">Parts</TableHead>
            <TableHead className="text-right">With SBOM</TableHead>
            <TableHead className="text-right">Components</TableHead>
            <TableHead className="text-right">C / H / M / L</TableHead>
            <TableHead className="text-right">Fixable</TableHead>
            <TableHead>Last SBOM</TableHead>
            {/* Only artifacts carry these. An application is a rollup of repositories and has no
                bill of materials of its own to replace. */}
            <TableHead className="w-56 text-right">SBOM</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {paging.rows.map(({ row, depth }) => (
            <TableRow key={row.id}>
              <TableCell>
                <div className="flex items-center gap-1"
                     style={{ paddingInlineStart: `${depth * 1.2}rem` }}>
                  {row.children > 0 ? (
                    <button onClick={() => toggle(row)}
                            aria-label={open.has(row.id) ? "Collapse" : "Expand"}
                            className="text-muted-foreground hover:text-foreground">
                      {open.has(row.id) ? <ChevronDown className="size-3.5" />
                                        : <ChevronRight className="size-3.5" />}
                    </button>
                  ) : <span className="inline-block size-3.5" />}
                  {/* The name opens the advisory list for this node — application, project or
                      repository alike. The chevron beside it expands the tree instead; two
                      different questions, so two different controls rather than one that guesses. */}
                  <button onClick={() => setInspecting(
                            inspecting?.id === row.id ? null : row)}
                          className={cn("text-sm hover:underline",
                            inspecting?.id === row.id ? "font-semibold text-primary"
                                                      : "text-primary")}>
                    {row.name}
                  </button>
                  <Badge>{row.typeCode}</Badge>
                  {busy.has(row.id) && <span className="text-[11px] text-muted-foreground">…</span>}
                </div>
                {row.owningNodeName && (
                  <div className="pl-6 text-[11px] text-muted-foreground">{row.owningNodeName}</div>
                )}
              </TableCell>
              <TableCell className="text-xs">
                {/* Clicking it applies the filter above rather than opening anything: this table is a
                    tree of the estate, and the useful next move from an organization is "only this
                    one". The same `org` parameter the projects and CI/CD tables use. */}
                {row.orgName ? (
                  <button type="button" className="text-left hover:underline"
                          onClick={() => onOrg(row.orgId ?? ANY)}>{row.orgName}</button>
                ) : (
                  <span className="italic text-tone-unknown">unowned</span>
                )}
              </TableCell>
              <TableCell className="tabular text-right text-xs">{row.parts}</TableCell>
              {/* Covered against total, never covered alone. PRD-SBM-056: a column showing only what
                  was measured reports a clean estate. */}
              <TableCell className="tabular text-right text-xs">
                {row.parts === 0
                  ? (row.submitsSbom ? <Badge tone="ok">own SBOM</Badge>
                                     : <Badge tone="warn">never</Badge>)
                  : row.sbomParts === 0
                    ? <Badge tone="warn">none of {row.parts}</Badge>
                    : `${row.sbomParts} of ${row.parts}`}
              </TableCell>
              <TableCell className="tabular text-right text-xs">
                {row.componentCount === 0
                  ? <span className="italic text-tone-unknown">not measured</span>
                  : row.componentCount}
              </TableCell>
              <TableCell className="tabular text-right text-xs">
                <span className="text-sev-critical">{row.criticalOpen}</span>{" / "}
                <span className="text-sev-high">{row.highOpen}</span>{" / "}
                <span className="text-sev-medium">{row.mediumOpen}</span>{" / "}
                <span className="text-sev-low">{row.lowOpen}</span>
              </TableCell>
              <TableCell className="tabular text-right text-xs">
                {row.advisoryOpen === 0 ? "—" : `${row.fixableOpen} of ${row.advisoryOpen}`}
              </TableCell>
              <TableCell className="font-mono text-[11px]">
                {row.latestSnapshotAt ?? <span className="italic text-tone-unknown">never</span>}
              </TableCell>
              <TableCell className="text-right">
                {/* `submitsSbom` marks the rows that carry a bill of materials of their own. An
                    application is a rollup of the repositories under it and has nothing of its own
                    to replace, so offering it an upload would invite a submission that lands
                    against a target the submitter did not mean. */}
                {row.submitsSbom
                  ? <SbomActions row={row}
                                 onDone={() => { setLevels({}); setOpen(new Set()); load(null); }} />
                  : null}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <Pager paging={paging} unit="rows" />
      {inspecting && (
        <NodeAdvisories node={inspecting} severity={severity}
                        onClose={() => setInspecting(null)} />
      )}
    </Card>
  );
}

/**
 * Every unresolved advisory under one node, whatever level that node is.
 *
 * One component for application, project and repository, because it is one question — the subtree
 * walk behind it is the same, and the only thing that changes is where the reader started.
 *
 * The unit is (advisory, component, repository), not advisory. The same CVE at two versions of the
 * same library in two repositories is two pieces of work with two different upgrades, and collapsing
 * them would name one version and hide the other.
 */
/** The host of a URL, so a reference list reads as sources rather than as a wall of links. */
function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return url.slice(0, 30);
  }
}

function NodeAdvisories({ node, severity, onClose }: {
  node: TreeRow; severity: string; onClose: () => void;
}) {
  const [rows, setRows] = useState<NodeAdvisory[] | null>(null);
  const [timeline, setTimeline] = useState<Month[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const paging = usePaging(rows ?? []);

  useEffect(() => {
    setRows(null);
    api.get<{ rows: NodeAdvisory[]; timeline: Month[] }>(
      `/api/ui/dependencies/node?asset=${node.id}${severity ? `&severity=${severity}` : ""}`)
      .then((d) => { setRows(d.rows); setTimeline(d.timeline ?? []); })
      .catch((e) => setError(e.message));
  }, [node.id, severity]);

  function toggle(key: string) {
    const next = new Set(open);
    if (next.has(key)) next.delete(key); else next.add(key);
    setOpen(next);
  }

  const severe = (rows ?? []).filter((r) => r.severity === "CRITICAL" || r.severity === "HIGH").length;

  return (
    <div className="border-t bg-muted/20">
      <div className="flex flex-wrap items-start justify-between gap-3 px-5 py-4">
        <div>
          <div className="text-sm font-semibold">
            Advisories under {node.name}{" "}
            <Badge>{node.typeCode}</Badge>
          </div>
          <div className="text-xs text-muted-foreground">
            {rows === null ? "Loading…" : rows.length === 0
              ? "Nothing unresolved here."
              : `${rows.length} occurrence${rows.length === 1 ? "" : "s"}, ${severe} at the top two severities. One row per advisory, component version and repository — the same CVE at two versions is two different upgrades.`}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" asChild>
            <a href={`/api/ui/dependencies/export?asset=${node.id}&format=xlsx`}>Export to Excel</a>
          </Button>
          <Button variant="outline" size="sm" asChild>
            <a href={`/api/ui/dependencies/export?asset=${node.id}&format=cyclonedx`}>Export SBOM</a>
          </Button>
          <Button variant="ghost" size="sm" onClick={onClose}><X /> Close</Button>
        </div>
      </div>

      {error && <div className="px-5 pb-4 text-sm text-destructive">{error}</div>}

      {/* THIS NODE'S OWN FLOW. The estate chart at the top answers "is the group getting better";
          this answers "did we fix anything", which is the question the team that owns this
          repository actually has and cannot get from a figure averaged over everybody else. */}
      {timeline.some((m) => m.appeared + m.resolved + m.snapshots > 0) && (
        <div className="border-t px-5 py-4">
          <div className="mb-1 text-xs font-medium">
            Advisories appearing and closing under {node.name}
          </div>
          <div className="mb-3 text-[11px] text-muted-foreground">
            Closing is the month a component stopped appearing in any bill of materials — the month
            somebody shipped the upgrade, not the month a ticket was closed.
          </div>
          <Trend unit="month" measured
                 weeks={timeline.map((m) => ({
                   label: m.label, opened: m.appeared, closed: m.resolved,
                 }))} />
        </div>
      )}

      {rows !== null && rows.length > 0 && (
        <>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Advisory</TableHead>
                <TableHead>Severity</TableHead>
                <TableHead>Component and version now</TableHead>
                <TableHead>Where</TableHead>
                <TableHead>Upgrade to</TableHead>
                <TableHead>Detected</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {paging.rows.map((r) => {
                const key = `${r.advisoryId}-${r.componentId}-${r.assetId}`;
                return (
                  <>
                    <TableRow key={key} className="cursor-pointer" onClick={() => toggle(key)}>
                      <TableCell>
                        <span className="inline-flex items-center gap-1">
                          {open.has(key) ? <ChevronDown className="size-3" />
                                         : <ChevronRight className="size-3" />}
                          <span className="font-mono text-xs font-medium text-primary">{r.key}</span>
                        </span>
                        {r.cvss !== null && (
                          <div className="pl-4 text-[11px] text-muted-foreground">CVSS {r.cvss}</div>
                        )}
                      </TableCell>
                      <TableCell>
                        {r.severity
                          ? <span className="inline-flex items-center gap-1.5 text-xs">
                              <span className={cn("inline-block size-2.5 rounded-sm",
                                SEVERITY_FILL[r.severity] ?? "bg-tone-unknown")} />{r.severity}
                            </span>
                          : <Badge tone="unknown">unrated</Badge>}
                      </TableCell>
                      <TableCell>
                        <div className="font-mono text-[11px]">{r.componentName}</div>
                        <div className="text-[11px]">
                          <span className="font-mono">{r.componentVersion}</span>{" "}
                          <Badge tone={r.direct ? "info" : "neutral"}>
                            {r.direct ? "direct" : "transitive"}
                          </Badge>
                        </div>
                      </TableCell>
                      <TableCell className="text-[11px] text-muted-foreground">
                        {r.assetName}
                        {r.projectName && <div>{r.applicationName} › {r.projectName}</div>}
                      </TableCell>
                      <TableCell className="font-mono text-[11px]">
                        {/* Upstream declining to fix outranks the version column: there is no
                            version to wait for, and showing a blank beside it reads as "not known
                            yet" when it means "never". */}
                        {r.status === "will_not_fix" || r.status === "end_of_life"
                          ? <Badge tone="critical">
                              {r.status === "end_of_life" ? "end of life" : "will not fix"}
                            </Badge>
                          : r.fixedVersion ?? (
                              <span className="italic text-tone-warn">no fix published</span>
                            )}
                      </TableCell>
                      <TableCell className="font-mono text-[11px]">{r.detectedAt ?? "—"}</TableCell>
                    </TableRow>
                    {open.has(key) && (
                      <TableRow key={`${key}-detail`}>
                        <TableCell colSpan={6} className="bg-card">
                          <div className="flex flex-col gap-2 py-1 text-xs">
                            <div>
                              <span className="text-muted-foreground">What it is: </span>
                              {/* The long form where the tool sent one; the summary otherwise. */}
                              {r.description ?? r.summary ?? (
                                <span className="italic text-tone-unknown">
                                  The submitting tool sent no description. The advisory identifier is
                                  the authority — this platform does not write one it was not given.
                                </span>
                              )}
                            </div>
                            <div>
                              <span className="text-muted-foreground">What to do: </span>
                              {r.recommendation}
                            </div>
                            {r.cweIds.length > 0 && (
                              <div>
                                <span className="text-muted-foreground">Weakness class: </span>
                                {r.cweIds.join(", ")}
                              </div>
                            )}
                            {r.references.length > 0 && (
                              <div className="flex flex-wrap gap-3">
                                <span className="text-muted-foreground">Read more:</span>
                                {r.references.slice(0, 4).map((url) => (
                                  <a key={url} href={url} target="_blank" rel="noreferrer noopener"
                                     className="text-primary hover:underline">{hostOf(url)}</a>
                                ))}
                              </div>
                            )}
                            <div className="flex flex-wrap gap-4 text-[11px] text-muted-foreground">
                              <span>Package: <span className="font-mono">{r.purl}</span></span>
                              <span>Ecosystem: {r.ecosystem}</span>
                              <span>Published: {r.publishedAt ?? "not stated"}</span>
                              <span>Reported by: {r.source}</span>
                              {r.dataSource && <span>Advisory database: {r.dataSource}</span>}
                              <span>From the SBOM of {r.snapshotAt ?? "an unknown date"}</span>
                            </div>
                          </div>
                        </TableCell>
                      </TableRow>
                    )}
                  </>
                );
              })}
            </TableBody>
          </Table>
          <Pager paging={paging} unit="occurrences" />
        </>
      )}
    </div>
  );
}

// ================================================================================================
// Search: by CVE, and by package
// ================================================================================================

function AdvisorySearch({ initial }: { initial: AdvisoryRow[] }) {
  const [q, setQ] = useState("");
  const [rows, setRows] = useState<AdvisoryRow[]>(initial);
  const [selected, setSelected] = useState<AdvisoryRow | null>(null);
  const paging = usePaging(rows);

  function run(term: string) {
    api.get<{ rows: AdvisoryRow[] }>(
      `/api/ui/dependencies/advisories?q=${encodeURIComponent(term)}`)
      .then((d) => { setRows(d.rows); setSelected(null); });
  }

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="relative min-w-64 flex-1">
            <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
            <Input value={q} className="pl-8" placeholder="CVE-2021-44228, GHSA-…"
                   onChange={(e) => setQ(e.target.value)}
                   onKeyDown={(e) => e.key === "Enter" && run(q)} />
          </div>
          <Button size="sm" onClick={() => run(q)}>Search</Button>
          {q && <Button variant="ghost" size="sm" onClick={() => { setQ(""); run(""); }}>
            <X /> Clear</Button>}
        </CardContent>
      </Card>

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle>{q ? `Advisories matching "${q}"` : "Open advisories, worst first"}</CardTitle>
          <CardDescription>
            Applications is the figure to triage on: two hundred affected components inside one
            application is a different morning from two hundred across forty. Select a row to see
            every place it reaches.
          </CardDescription>
        </CardHeader>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Advisory</TableHead>
              <TableHead>Severity</TableHead>
              <TableHead className="text-right">CVSS</TableHead>
              <TableHead className="text-right">Applications</TableHead>
              <TableHead className="text-right">Repositories</TableHead>
              <TableHead className="text-right">Components</TableHead>
              <TableHead>First seen here</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {paging.rows.length === 0 ? (
              <TableRow><TableCell colSpan={7} className="py-8 text-center text-sm text-muted-foreground">
                No advisory matches. That is not the same as none existing — only submitted documents
                are searched, and nothing in one has named this.
              </TableCell></TableRow>
            ) : paging.rows.map((a) => (
              <TableRow key={a.id} className="cursor-pointer" onClick={() => setSelected(a)}>
                <TableCell>
                  <span className="font-mono text-xs font-medium text-primary">{a.key}</span>
                  {a.summary && (
                    <div className="max-w-lg truncate text-[11px] text-muted-foreground">{a.summary}</div>
                  )}
                </TableCell>
                <TableCell>
                  {a.severity
                    ? <span className="inline-flex items-center gap-1.5 text-xs">
                        <span className={cn("inline-block size-2.5 rounded-sm",
                          SEVERITY_FILL[a.severity] ?? "bg-tone-unknown")} />{a.severity}
                      </span>
                    : <Badge tone="unknown">unrated</Badge>}
                </TableCell>
                <TableCell className="tabular text-right text-xs">{a.cvss ?? "—"}</TableCell>
                <TableCell className="tabular text-right">{a.applicationCount}</TableCell>
                <TableCell className="tabular text-right">{a.assetCount}</TableCell>
                <TableCell className="tabular text-right">{a.componentCount}</TableCell>
                <TableCell className="font-mono text-[11px]">{a.firstRecordedAt ?? "—"}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <Pager paging={paging} unit="advisories" />
      </Card>

      {selected && <Locations title={`Where ${selected.key} is`} query={`advisory=${selected.id}`}
                              onClose={() => setSelected(null)} />}
    </div>
  );
}

function ComponentSearch({ initial }: { initial: ComponentRow[] }) {
  const [q, setQ] = useState("");
  const [rows, setRows] = useState<ComponentRow[]>(initial);
  const [selected, setSelected] = useState<ComponentRow | null>(null);
  const paging = usePaging(rows);

  function run(term: string) {
    api.get<{ rows: ComponentRow[] }>(
      `/api/ui/dependencies/components?q=${encodeURIComponent(term)}`)
      .then((d) => { setRows(d.rows); setSelected(null); });
  }

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="relative min-w-64 flex-1">
            <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
            <Input value={q} className="pl-8" placeholder="log4j, pkg:npm/lodash, jackson…"
                   onChange={(e) => setQ(e.target.value)}
                   onKeyDown={(e) => e.key === "Enter" && run(q)} />
          </div>
          <Button size="sm" onClick={() => run(q)}>Search</Button>
          {q && <Button variant="ghost" size="sm" onClick={() => { setQ(""); run(""); }}>
            <X /> Clear</Button>}
        </CardContent>
      </Card>

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle>{q ? `Packages matching "${q}"` : "Vulnerable packages, worst first"}</CardTitle>
          <CardDescription>
            One row per package AND version, because that is what an advisory applies to. The same
            library at three versions is three rows, and collapsing them would hide which teams have
            upgraded and which have not.
          </CardDescription>
        </CardHeader>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Package</TableHead>
              <TableHead>Version</TableHead>
              <TableHead>Ecosystem</TableHead>
              <TableHead className="text-right">Applications</TableHead>
              <TableHead className="text-right">Repositories</TableHead>
              <TableHead className="text-right">Open advisories</TableHead>
              <TableHead>How it got here</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {paging.rows.length === 0 ? (
              <TableRow><TableCell colSpan={7} className="py-8 text-center text-sm text-muted-foreground">
                No package matches, among the components of documents that were submitted.
              </TableCell></TableRow>
            ) : paging.rows.map((c) => (
              <TableRow key={c.id} className="cursor-pointer" onClick={() => setSelected(c)}>
                <TableCell className="max-w-80 truncate text-xs font-medium text-primary">{c.name}</TableCell>
                <TableCell className="font-mono text-[11px]">{c.version}</TableCell>
                <TableCell className="text-xs">{c.ecosystem}</TableCell>
                <TableCell className="tabular text-right">{c.applicationCount}</TableCell>
                <TableCell className="tabular text-right">{c.assetCount}</TableCell>
                <TableCell className="tabular text-right text-xs">
                  {c.advisoryOpen === 0 ? "—" : (
                    <>
                      {c.advisoryOpen}
                      {c.criticalOpen + c.highOpen > 0 && (
                        <Badge tone="critical" className="ml-1">
                          {c.criticalOpen + c.highOpen} severe
                        </Badge>
                      )}
                    </>
                  )}
                </TableCell>
                <TableCell>
                  <Badge tone={c.direct ? "info" : "neutral"}>
                    {c.direct ? "declared directly" : "pulled in transitively"}
                  </Badge>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <Pager paging={paging} unit="packages" />
      </Card>

      {selected && <Locations title={`Where ${selected.name} ${selected.version} is`}
                              query={`component=${selected.id}`}
                              onClose={() => setSelected(null)} />}
    </div>
  );
}

/**
 * The drill-down under either search: every place the thing actually sits, with its path.
 *
 * The path matters. "card-issuing-api" alone is a repository name; "Card Issuing › Authorization ›
 * card-issuing-api" is an answer somebody can act on without opening three more pages.
 */
function Locations({ title, query, onClose }: {
  title: string; query: string; onClose: () => void;
}) {
  const [rows, setRows] = useState<LocationRow[] | null>(null);
  const [graphFor, setGraphFor] = useState<LocationRow | null>(null);

  useEffect(() => {
    setRows(null);
    api.get<{ rows: LocationRow[] }>(`/api/ui/dependencies/locations?${query}`)
      .then((d) => setRows(d.rows)).catch(() => setRows([]));
  }, [query]);

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex-row items-start justify-between">
        <div>
          <CardTitle>{title}</CardTitle>
          <CardDescription>
            Only unresolved occurrences. A place that upgraded away is not listed, which is why this
            can be shorter than the counts above after a partial remediation.
          </CardDescription>
        </div>
        <Button variant="ghost" size="sm" onClick={onClose}><X /> Close</Button>
      </CardHeader>
      {rows === null ? (
        <CardContent className="text-sm text-muted-foreground">Loading…</CardContent>
      ) : rows.length === 0 ? (
        <CardContent className="text-sm text-muted-foreground">
          Nothing unresolved. Every occurrence has been upgraded away.
        </CardContent>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Where</TableHead>
              <TableHead>Package</TableHead>
              <TableHead>How</TableHead>
              <TableHead>Fixed in</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((r, i) => (
              <TableRow key={`${r.assetId}-${r.componentName}-${i}`}>
                <TableCell>
                  <div className="text-xs">
                    {r.applicationId ? (
                      <Link to={`/applications/${r.applicationId}`}
                            className="text-primary hover:underline">{r.applicationName}</Link>
                    ) : <span className="italic text-tone-unknown">under no application</span>}
                    {r.path.length > 0 && (
                      <span className="text-muted-foreground"> › {r.path.join(" › ")}</span>
                    )}
                  </div>
                  <div className="text-[11px] text-muted-foreground">
                    {r.assetName} · {r.assetTypeCode}
                  </div>
                </TableCell>
                <TableCell className="font-mono text-[11px]">
                  {r.componentName} {r.componentVersion}
                </TableCell>
                <TableCell>
                  <Badge tone={r.direct ? "info" : "neutral"}>
                    {r.direct ? "direct" : "transitive"}
                  </Badge>
                </TableCell>
                <TableCell className="font-mono text-[11px]">
                  {r.fixedVersion ?? (
                    // Named rather than left blank. "No fix published" is the row that needs a
                    // decision instead of an upgrade, and a blank cell reads as missing data.
                    <span className="italic text-tone-warn">no fix published</span>
                  )}
                </TableCell>
                <TableCell className="text-right">
                  {!r.direct && (
                    <Button variant="outline" size="sm" onClick={() => setGraphFor(r)}>
                      What pulled it in
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {graphFor && <DependencyTree asset={graphFor} onClose={() => setGraphFor(null)} />}
    </Card>
  );
}

/**
 * One repository's dependency graph, walked from the vulnerable package UPWARDS.
 *
 * Upwards, deliberately. A transitive package is not fixed by touching it — it is fixed by upgrading
 * whatever declared it, and that is the edge this walks. Rendering the whole graph downwards from
 * the root would be a prettier picture and would not answer the question that got somebody here.
 *
 * Every chain is shown, not one. A package is routinely reached by more than one route, and naming
 * a single upgrade that does not remove it is worse than naming none.
 */
function DependencyTree({ asset, onClose }: { asset: LocationRow; onClose: () => void }) {
  const [edges, setEdges] = useState<Edge[] | null>(null);

  useEffect(() => {
    api.get<{ rows: Edge[] }>(`/api/ui/dependencies/graph?asset=${asset.assetId}`)
      .then((d) => setEdges(d.rows)).catch(() => setEdges([]));
  }, [asset.assetId]);

  const chains = useMemo(() => {
    if (!edges) return [];
    const byChild = new Map<string, Edge[]>();
    for (const e of edges) {
      const key = `${e.childName}@${e.childVersion}`;
      byChild.set(key, [...(byChild.get(key) ?? []), e]);
    }
    const target = `${asset.componentName}@${asset.componentVersion}`;
    const out: string[][] = [];
    // Depth-bounded and cycle-guarded, for the same reason asset_composition is: a dependency set is
    // a graph, and a cycle has to return a short answer rather than hang the browser.
    const walk = (key: string, trail: string[], seen: Set<string>) => {
      if (seen.has(key) || trail.length > 8 || out.length >= 12) return;
      const parents = byChild.get(key) ?? [];
      if (parents.length === 0) { out.push([...trail].reverse()); return; }
      for (const parent of parents) {
        const parentKey = `${parent.parentName}@${parent.parentVersion}`;
        walk(parentKey, [...trail, parentKey], new Set(seen).add(key));
      }
    };
    walk(target, [target], new Set());
    return out;
  }, [edges, asset]);

  return (
    <CardContent className="border-t pt-4">
      <div className="mb-2 flex items-center justify-between">
        <div className="text-sm font-medium">
          What pulls {asset.componentName} {asset.componentVersion} into {asset.assetName}
        </div>
        <Button variant="ghost" size="sm" onClick={onClose}><X /></Button>
      </div>
      {edges === null ? (
        <p className="text-sm text-muted-foreground">Loading the graph…</p>
      ) : chains.length === 0 ? (
        <p className="text-sm italic text-tone-unknown">
          No dependency graph was submitted for this repository, so the chain cannot be shown. The
          document carried components without a dependencies section — the submitter is the only
          party who can fix that, and the submission response says so.
        </p>
      ) : (
        <div className="flex flex-col gap-2">
          {chains.map((chain, i) => (
            <div key={i} className="flex flex-wrap items-center gap-1 text-xs">
              {chain.map((node, j) => (
                <span key={j} className="flex items-center gap-1">
                  {j > 0 && <ChevronRight className="size-3 text-muted-foreground" />}
                  <span className={cn("rounded px-1.5 py-0.5 font-mono text-[11px]",
                    j === 0 ? "bg-tone-info/20 text-foreground"
                            : j === chain.length - 1 ? "bg-sev-high/20 text-foreground"
                                                     : "bg-muted text-muted-foreground")}>
                    {node}
                  </span>
                </span>
              ))}
            </div>
          ))}
          <p className="mt-1 text-[11px] text-muted-foreground">
            Leftmost is a dependency this repository declares — that is the one to upgrade. Where two
            chains are shown, upgrading only one of them does not remove the package.
            {" "}{edges.length} dependency edges were recorded for this repository.
          </p>
        </div>
      )}
    </CardContent>
  );
}
