import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
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
import { AnalyseButton } from "@/components/AnalyseButton";
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
  }, []);

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>;

  const unrated = data.severity.find((s) => s.code === "UNRATED");

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold tracking-tight">Overview</h1>
        <p className="text-xs text-muted-foreground">
          What the estate is telling you, then the figures behind it. Everything is over the part of
          the organization you can reach, and carries the population it was computed over — a figure
          with nothing behind it says so rather than showing a zero.
        </p>
      </div>

      {/* HOW BAD, first. One number, its confidence, and what produced it. An executive who reads
          nothing else on this page reads this, and it is the figure they will repeat — so it carries
          its own coverage rather than leaving that on a panel further down that nobody quotes. */}
      <RiskHeadline posture={data.risk.overall} model={data.risk.model} />

      {/* Then WHAT TO DO. A reader who scrolls no further should still leave knowing that — the
          counts below are the working detail behind these sentences. */}
      <Observations items={data.observations} />

      {/* Then WHERE. Two rankings side by side because a reader owns one of them: an executive reads
          the left, an application owner reads the right, and the same page serves both without a
          filter anybody has to remember. */}
      <div className="grid gap-4 lg:grid-cols-2">
        <RiskRanking title="Risk by organization" rows={data.risk.byOrganization}
                     empty="No organization in your scope has anything scored yet."
                     href={(row) => `/applications?node=${row.id}`} />
        <RiskRanking title="Highest risk applications" rows={data.risk.topApplications}
                     empty="No application in your scope has been assessed yet — which is a coverage
                            result, not a clean one."
                     href={(row) => `/applications/${row.id}`} />
      </div>

      <RiskDistribution rows={data.risk.distribution} />

      {/* The operational posture of the same units. The ranking above says how bad; this says what
          is actually happening in each one, which is the next question a reader has. */}
      <OrgPosture rows={data.posture} />

      {/* Full width. Five columns of context is what makes this queue defensible rather than a list
          of titles, and squeezing it into a half column clips the score it is ordered by. */}
      <RiskQueue rows={data.risk.topFindings} />

      {/* Second question, and a different kind of figure. Everything above is a model output that
          depends on factors this deployment cannot supply; everything below is a count or an elapsed
          time over recorded events. The band separates them so a reader who discounts the score does
          not also discount the measurements — they have different reasons to be believed. */}
      <Section title="Are we getting better at it?"
               note="Counts and elapsed times, computed directly from recorded events. No scoring
                     model and no missing factors — these hold whatever you make of the risk figure." />

      <div className="grid gap-4 lg:grid-cols-2">
        <Remediation data={data.surface} />
        <Aging rows={data.surface.aging} />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Categories rows={data.surface.categories} />
        <Growth rows={data.surface.growth} />
      </div>

      <Section title="What you have to defend"
               note="The inventory itself, and the part of it an attacker can reach without
                     credentials." />

      <div className="grid gap-4 lg:grid-cols-2">
        <Estate rows={data.surface.assetClasses} />
        <InternetFacing rows={data.surface.internetFacing} />
      </div>

      <Section title="Working detail"
               note="The counts the sections above are assembled from." />

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {data.kpis.map((kpi) => (
          <MeasureCard key={kpi.key} measure={kpi} label={LABEL[kpi.key] ?? kpi.key}
                       tone={TONE[kpi.key] ?? "neutral"} />
        ))}
      </div>

      <div className="grid gap-4 lg:grid-cols-2">

        <Card>
          <CardHeader><CardTitle>Findings opened and closed</CardTitle></CardHeader>
          <CardContent>
            <Trend weeks={data.trend.weeks} measured={data.trend.measured} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle>Coverage</CardTitle></CardHeader>
          <CardContent className="flex flex-col gap-4">
            {data.coverage.map((row) => (
              <CoverageBar key={row.key} label={LABEL[row.key] ?? row.key}
                           measured={row.measured} inScope={row.inScope} href={row.href} />
            ))}
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
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
                      {/* Linked only where the finding lives inside a request. A finding imported
                          outside an assessment has no detail route yet, and a link that 404s teaches
                          people not to click the rows. */}
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

      {/* At the FOOT of the page, at the user's request. It sat at the top on the argument that a
          coverage caveat changes how every figure above should be read; the counter-argument won,
          and it is the practical one — the panel is long and the figures are what somebody opens
          this page for. The observations near the top still carry the "act now" layer, so the
          caveat is not the only warning above the fold.

          A first attempt at this put the mount inside the Section helper by accident, which rendered
          the whole panel once per band heading. Placed in the page's own tree this time. */}
      <div className="flex justify-end">
        <AnalyseButton surface="/overview" onDone={() => setReload((n) => n + 1)} />
      </div>
      <TopWeaknesses />

      <Suggestions key={reload} />
    </div>
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
