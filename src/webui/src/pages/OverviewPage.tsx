import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { ChevronDown, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { api } from "@/lib/api";
import { MeasureCard, CoverageBar, type MeasureValue } from "@/components/Measure";
import { Trend, type TrendWeek } from "@/components/Trend";
import { severityTone } from "@/components/tone";
import { Observations, type Observation } from "@/components/Observations";
import { OrgPosture, type Posture } from "@/components/OrgPosture";
import {
  RiskHeadline, RiskDistribution, RiskRanking, RiskQueue, type RiskPayload,
} from "@/components/Risk";
import {
  Remediation, Aging, Categories, Estate, Growth, InternetFacing, type SurfacePayload,
} from "@/components/Surface";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { AskPosture } from "@/components/AskPosture";
import { ExecutiveBrief, type Brief } from "@/components/ExecutiveBrief";
import { AiOnThisPage } from "@/components/AiOnThisPage";
import { Kpi } from "@/components/Kpi";
import { Suggestions } from "@/components/Suggestions";
import { TopWeaknesses } from "@/components/TopWeaknesses";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

interface SeverityRow {
  code: string; ordinal: number; total: number; open: number;
  unassigned: number; agedOverThirtyDays: number;
}
interface RecentRow {
  id: string; requestId: string | null; title: string; severity: string;
  state: string; firstDetectedAt: string | null; sourceTool: string;
}
interface CoverageRow { key: string; measured: number; inScope: number; href: string }
interface Payload {
  /** DOC-28's model, applied. The number an executive arrives for. */
  risk: RiskPayload;
  /** Counts and elapsed times. Nothing here depends on the scoring model's missing factors. */
  surface: SurfacePayload;
  /** One row per organization the caller reaches. The page's centre of gravity. */
  posture: Posture[];
  /** Composed from the facts, ranked. The seam the analysis agent will fill. */
  observations: Observation[];
  kpis: MeasureValue[];
  coverage: CoverageRow[];
  severity: SeverityRow[];
  trend: { weeks: TrendWeek[]; measured: boolean };
  recent: RecentRow[];
  requests: { total: number; open: number; overdue: number; unassigned: number; closedThirtyDays: number };
  estate: { applications: number; applicationsReviewed: number; assets: number; assetsWithSbom: number; nodes: number };
  /** The figures behind the observations, as numbers — the same statement, so the two agree. */
  figures: Record<string, number>;
  /** Executive briefs the narrative capability has drafted, awaiting a decision. */
  briefs: Brief[];
  mayDecide: boolean;
  asOf: string;
}

/** The label for each figure. Keyed the same way the server-rendered page keys them. */
const LABEL: Record<string, string> = {
  "overview.openFindings": "Open findings",
  "overview.severeOpen": "Open at the top two severities",
  "overview.overdueRequests": "Overdue requests",
  "overview.unassignedFindings": "Open findings with no owner",
  "overview.assessmentCoverage": "Applications with a completed review",
  "overview.compositionCoverage": "Assets that submitted an SBOM",
  "overview.sbomCurrency": "SBOMs above the freshness threshold",
};

const TONE: Record<string, "neutral" | "critical" | "warn" | "info" | "ok"> = {
  "overview.openFindings": "info",
  "overview.severeOpen": "critical",
  "overview.overdueRequests": "critical",
  "overview.unassignedFindings": "warn",
};

/**
 * The overview dashboard.
 *
 * Every figure links to the list that produced it. A dashboard number a reader cannot get behind is
 * a number they have to trust, and a link — rather than a modal — also works with the keyboard, in a
 * new tab, and in a printed report.
 */
export function OverviewPage() {
  const [reload, setReload] = useState(0);
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    api.get<Payload>("/api/ui/overview")
      .then((d) => live && setData(d))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, [reload]);

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>;

  const unrated = data.severity.find((s) => s.code === "UNRATED");
  const fig = data.figures ?? {};
  const decisions = data.observations.filter((o) => o.level === "ACT_NOW" || o.level === "WATCH");
  const context = data.observations.filter((o) => o.level !== "ACT_NOW" && o.level !== "WATCH");
  const backlogDelta = (fig.open_total ?? 0) - (fig.open_90_days_ago ?? 0);

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-lg font-semibold tracking-tight">Overview</h1>
          <p className="text-xs text-muted-foreground">
            First what needs a decision, then how bad it is and where, then the working detail.
            Everything is over the part of the organization you can reach; a figure with nothing
            behind it says so rather than showing a zero.
          </p>
        </div>
        <span className="text-[11px] text-muted-foreground">as of {data.asOf.slice(0, 16).replace("T", " ")} UTC</span>
      </div>

      {/* THE EXECUTIVE STRIP. Six figures, each the answer to a question a board asks, each measured
          against something the organization set for itself — a commitment, an acceptance, a review
          interval — rather than against a scale. Colour only where the value is non-zero (Kpi). */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
        <Link to="/vulnerabilities"><Kpi label="Serious & reachable from the internet" value={fig.exposed_serious_open ?? 0} tone="critical"
             hint={(fig.exposed_serious_old ?? 0) > 0 ? `${fig.exposed_serious_old} open over 90 days` : "top two severities, public, business-critical"} /></Link>
        <Link to="/vulnerabilities"><Kpi label="Past remediation commitment" value={fig.sla_breached_open ?? 0} tone="critical"
             hint={`${fig.sla_due_7d ?? 0} due within 7 days`} /></Link>
        <Link to="/vulnerabilities"><Kpi label="Accepted risks expiring in 30 days" value={fig.exceptions_expiring_30d ?? 0} tone="warn"
             hint={`${fig.exceptions_active ?? 0} active exceptions`} /></Link>
        <Link to="/planning"><Kpi label="Reviews owed, nobody planned" value={fig.reviews_due_unplanned ?? 0} tone="warn"
             hint={`${fig.reviews_due ?? 0} owed in total`} /></Link>
        <Link to="/applications"><Kpi label="Applications never assessed" value={fig.apps_never_assessed ?? 0} tone="critical"
             hint={`of ${fig.apps_total ?? 0} — these look clean because nobody looked`} /></Link>
        <Link to="/vulnerabilities"><Kpi label="Open findings" value={fig.open_total ?? 0} tone="info"
             hint={backlogDelta === 0 ? "unchanged over 90 days" : `${backlogDelta > 0 ? "+" : "−"}${Math.abs(backlogDelta)} over 90 days`} /></Link>
      </div>

      {/* HOW BAD and WHAT THE AI SAYS ABOUT IT, side by side. The score carries its own coverage and
          confidence; the brief is generated, grounded in the same figures, and stays a suggestion
          until somebody accepts it. */}
      <div className="grid gap-4 xl:grid-cols-5">
        <div className="xl:col-span-3"><RiskHeadline posture={data.risk.overall} model={data.risk.model} /></div>
        <div className="xl:col-span-2">
          <ExecutiveBrief briefs={data.briefs ?? []} mayDecide={data.mayDecide} onChanged={() => setReload((n) => n + 1)} />
        </div>
      </div>

      {/* WHAT NEEDS A DECISION. Each card is a sentence with its population, a level, and the list
          behind it. Rules write these today; the seam is the one a model would fill, and either way
          the basis is printed on the card. */}
      <Section title="What needs a decision"
               note="Specific, present, and with a population behind each. Act-now items are exposures that exist today; watch items become act-now if nothing changes." />
      {decisions.length > 0
        ? <Observations items={decisions} />
        : <p className="text-sm text-muted-foreground">Nothing in scope needs a decision right now — read that beside the coverage figures above.</p>}

      {/* WHERE. The unit of accountability is the organization, so its table comes before the
          rankings of applications and findings. Coverage is stated per row (PRD-DSH-037) and the
          only normalization is per application count, stated (PRD-DSH-035). */}
      <Section title="Where each organization stands"
               note="One row per organization you reach, its whole subtree rolled up. Recorded values, not scores; never-assessed is shown beside every figure because an unmeasured unit looks clean." />
      <OrgPosture rows={data.posture} />

      <div className="grid gap-4 lg:grid-cols-2">
        <RiskRanking title="Risk by organization" rows={data.risk.byOrganization}
                     empty="No organization in your scope has anything scored yet."
                     href={(row) => `/applications?node=${row.id}`} />
        <RiskRanking title="Highest risk applications" rows={data.risk.topApplications}
                     empty="No application in your scope has been assessed yet — which is a coverage
                            result, not a clean one."
                     href={(row) => `/applications/${row.id}`} />
      </div>

      {context.length > 0 && <Observations items={context} />}

      {/* Everything below is DETAIL, folded. An executive stops here; the people who run the work
          open the bands they own. The state is per browser and remembered. */}
      <Fold id="risk-detail" title="Risk detail" note="The distribution by band and the ten findings the model ranks highest.">
        <RiskDistribution rows={data.risk.distribution} />
        <RiskQueue rows={data.risk.topFindings} />
      </Fold>

      <Fold id="getting-better" title="Are we getting better at it?" defaultOpen
            note="Counts and elapsed times, computed directly from recorded events. No scoring model and no missing factors — these hold whatever you make of the risk figure.">
        <div className="grid gap-4 lg:grid-cols-2">
          <Remediation data={data.surface} />
          <Aging rows={data.surface.aging} />
        </div>
        <div className="grid gap-4 lg:grid-cols-2">
          <Categories rows={data.surface.categories} />
          <Growth rows={data.surface.growth} />
        </div>
        <Card>
          <CardHeader><CardTitle>Findings opened and closed</CardTitle></CardHeader>
          <CardContent>
            <Trend weeks={data.trend.weeks} measured={data.trend.measured} />
          </CardContent>
        </Card>
        <TopWeaknesses />
      </Fold>

      <Fold id="defend" title="What you have to defend" note="The inventory itself, and the part of it an attacker can reach without credentials.">
        <div className="grid gap-4 lg:grid-cols-2">
          <Estate rows={data.surface.assetClasses} />
          <InternetFacing rows={data.surface.internetFacing} />
        </div>
        <Card>
          <CardHeader><CardTitle>The estate you can reach</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-2 gap-4 text-xs sm:grid-cols-3 lg:grid-cols-5">
            <Figure label="Organization nodes" value={data.estate.nodes} to="/organization" />
            <Figure label="Applications" value={data.estate.applications} to="/applications" />
            <Figure label="Assets" value={data.estate.assets} to="/composition" />
            <Figure label="Requests open" value={data.requests.open} to="/board" />
            <Figure label="Requests closed in 30 days" value={data.requests.closedThirtyDays} to="/board" />
          </CardContent>
        </Card>
      </Fold>

      <Fold id="working-detail" title="Working detail" note="The counts the sections above are assembled from, with the coverage each was computed over.">
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          {data.kpis.map((kpi) => (
            <MeasureCard key={kpi.key} measure={kpi} label={LABEL[kpi.key] ?? kpi.key}
                         tone={TONE[kpi.key] ?? "neutral"} />
          ))}
        </div>
        <div className="grid gap-4 lg:grid-cols-2">
          <Card>
            <CardHeader><CardTitle>Coverage</CardTitle></CardHeader>
            <CardContent className="flex flex-col gap-4">
              {data.coverage.map((row) => (
                <CoverageBar key={row.key} label={LABEL[row.key] ?? row.key}
                             measured={row.measured} inScope={row.inScope} href={row.href} />
              ))}
            </CardContent>
          </Card>
          <Card className="overflow-hidden">
            <CardHeader><CardTitle>Open findings by severity</CardTitle></CardHeader>
            {data.severity.length === 0 ? (
              <CardContent className="text-sm text-muted-foreground">
                No finding has been recorded in scope. That is not the same as an estate with none —
                nothing here has been scanned.
              </CardContent>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Severity</TableHead>
                    <TableHead className="text-right">Open</TableHead>
                    <TableHead className="text-right">No owner</TableHead>
                    <TableHead className="text-right">Over 30 days</TableHead>
                    <TableHead className="text-right">All states</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.severity.map((row) => (
                    <TableRow key={row.code}>
                      <TableCell>
                        <Badge tone={row.code === "UNRATED" ? "unknown" : severityTone(row.code)}>
                          {row.code === "UNRATED" ? "not rated" : row.code}
                        </Badge>
                      </TableCell>
                      <TableCell className="tabular text-right">{row.open}</TableCell>
                      <TableCell className="tabular text-right">
                        {row.unassigned > 0 ? <strong>{row.unassigned}</strong> : row.unassigned}
                      </TableCell>
                      <TableCell className="tabular text-right">{row.agedOverThirtyDays}</TableCell>
                      <TableCell className="tabular text-right text-muted-foreground">{row.total}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
            {unrated && (
              <CardContent className="border-t text-[11px] text-muted-foreground">
                {unrated.total} finding{unrated.total === 1 ? " carries" : "s carry"} no severity. They
                are counted here rather than dropped: a finding nobody has rated is the one nobody has
                looked at, and leaving it out of the totals makes the estate look smaller than it is.
              </CardContent>
            )}
          </Card>
        </div>
        <Card className="overflow-hidden">
          <CardHeader><CardTitle>Most recently detected</CardTitle></CardHeader>
          {data.recent.length === 0 ? (
            <CardContent className="text-sm text-muted-foreground">Nothing open in scope.</CardContent>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Finding</TableHead>
                  <TableHead>Severity</TableHead>
                  <TableHead>Detected</TableHead>
                  <TableHead>Source</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.recent.map((row) => (
                  <TableRow key={row.id}>
                    <TableCell className="max-w-72 truncate text-xs">
                      {row.requestId ? (
                        <Link to={`/board/${row.requestId}/findings/${row.id}`}
                              className="font-medium text-primary hover:underline">{row.title}</Link>
                      ) : (
                        <span className="font-medium">{row.title}</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge tone={row.severity === "UNRATED" ? "unknown" : severityTone(row.severity)}>
                        {row.severity === "UNRATED" ? "not rated" : row.severity}
                      </Badge>
                    </TableCell>
                    <TableCell className="font-mono text-[11px]">{row.firstDetectedAt ?? "—"}</TableCell>
                    <TableCell className="text-[11px] text-muted-foreground">{row.sourceTool}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Card>
      </Fold>

      {/* THE AI, explained and usable, in one band at the foot: what runs on this page, a question
          box grounded in the same figures, and the ledger where every proposal waits for a person. */}
      <Section title="AI on this page"
               note="A model writes sentences; every number comes from a query. Nothing changes a record until a person accepts it, and every acceptance is audited." />
      <AiOnThisPage surface="/overview" />
      <AskPosture />
      <Suggestions key={reload} />
    </div>
  );
}

/**
 * A folded band. Closed by default unless told otherwise; the choice is remembered per browser so a
 * reader who opens the operational detail once keeps it open.
 */
function Fold({ id, title, note, defaultOpen = false, children }: {
  id: string; title: string; note: string; defaultOpen?: boolean; children: ReactNode;
}) {
  const key = `overview.fold.${id}`;
  const [open, setOpen] = useState<boolean>(() => {
    try {
      const stored = localStorage.getItem(key);
      return stored === null ? defaultOpen : stored === "1";
    } catch {
      return defaultOpen;
    }
  });
  function toggle() {
    const next = !open;
    setOpen(next);
    try { localStorage.setItem(key, next ? "1" : "0"); } catch { /* per-viewer convenience only */ }
  }
  return (
    <section className="mt-1 border-t pt-3">
      <button type="button" onClick={toggle} aria-expanded={open}
              className="flex w-full items-start gap-2 text-left">
        {open ? <ChevronDown className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
              : <ChevronRight className="mt-0.5 size-4 shrink-0 text-muted-foreground" />}
        <span>
          <span className="block text-sm font-semibold tracking-tight">{title}</span>
          <span className="block text-xs text-muted-foreground">{note}</span>
        </span>
      </button>
      <div className={cn("mt-3 flex flex-col gap-4", !open && "hidden")}>{open && children}</div>
    </section>
  );
}

/**
 * A band heading.
 *
 * The page is long because the questions it answers are different questions, and an unbroken column
 * of cards makes a reader treat the twelfth one as more of the first. The note says what kind of
 * figure follows, which is what decides how much weight to put on it.
 */
function Section({ title, note }: { title: string; note: string }) {
  return (
    <div className="mt-2 border-t pt-4">
      <h2 className="text-sm font-semibold tracking-tight">{title}</h2>
      <p className="text-xs text-muted-foreground">{note}</p>

    </div>
  );
}

function Figure({ label, value, to }: { label: string; value: number; to: string }) {
  return (
    <Link to={to} className="flex flex-col gap-0.5 hover:text-primary">
      <span className="tabular text-xl font-semibold tracking-tight">{value}</span>
      <span className="text-[11px] text-muted-foreground">{label}</span>
    </Link>
  );
}
