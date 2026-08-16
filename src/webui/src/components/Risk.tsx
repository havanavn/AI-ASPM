import { Link } from "react-router-dom";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { severityTone } from "@/components/tone";

/** DOC-28 §10.2. `posture` is null where confidence is INSUFFICIENT — the server withholds it. */
export interface RiskPosture {
  scoped: boolean;
  id?: string;
  name?: string;
  posture: number | null;
  band: string | null;
  confidence: "HIGH" | "MEDIUM" | "LOW" | "INSUFFICIENT";
  assets: number;
  measuredAssets: number;
  findings: number;
  worstScore: number;
  components: {
    severityPressure: number; concentration: number; slaHealth: number; coveragePenalty: number;
  };
}

export interface RiskModel {
  version: string;
  factorCoverage: number;
  absentFactors: string[];
}

export interface RiskFinding {
  id: string; requestId: string | null; title: string; severity: string;
  score: number; band: string; exposure: string; criticality: string;
}

export interface RiskPayload {
  overall: RiskPosture;
  distribution: { band: string; findings: number }[];
  byOrganization: RiskPosture[];
  topApplications: RiskPosture[];
  topFindings: RiskFinding[];
  model: RiskModel;
}

/** DOC-28 §6.3 bands, mapped onto the tone scale the rest of the interface already uses. */
export function bandTone(band: string | null): "critical" | "warn" | "info" | "neutral" | "unknown" {
  switch (band) {
    case "CRITICAL": return "critical";
    case "HIGH": return "critical";
    case "MEDIUM": return "warn";
    case "LOW": return "info";
    case "INFORMATIONAL": return "neutral";
    default: return "unknown";
  }
}

const CONFIDENCE_NOTE: Record<string, string> = {
  HIGH: "Above 90% of the estate measured.",
  MEDIUM: "70–90% of the estate measured — the figure moves as coverage does.",
  LOW: "Under 70% measured, or scored on partial factors. Directional, not a target.",
  INSUFFICIENT: "Under 40% of the estate measured. Not enough to be a posture figure.",
};

/**
 * The headline risk figure.
 *
 * At INSUFFICIENT confidence this deliberately shows no number. `PRD-RSK-027` requires it to be
 * presented as a coverage gap, and the reason is worth keeping in view: a score computed over the
 * fifth of the estate that happens to have been scanned is not a low score, it is an unknown one,
 * and the way it fails is that somebody quotes it in a board pack.
 */
export function RiskHeadline({ posture, model }: { posture: RiskPosture; model: RiskModel }) {
  if (!posture.scoped) {
    return (
      <Card>
        <CardContent className="text-sm text-muted-foreground">
          You do not reach any part of the organization tree, so there is nothing to score. That is an
          access question rather than a risk one — ask an administrator for a scope grant.
        </CardContent>
      </Card>
    );
  }

  const coverage = posture.assets === 0 ? 0
    : Math.round((posture.measuredAssets / posture.assets) * 100);
  const shown = posture.posture !== null;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle>Risk across everything you can reach</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap items-end gap-6">
          <div className="flex flex-col gap-1">
            {shown ? (
              <div className="flex items-end gap-2">
                <span className="tabular text-4xl font-semibold leading-none tracking-tight">
                  {posture.posture}
                </span>
                <span className="pb-0.5 text-xs text-muted-foreground">/ 100</span>
                <Badge tone={bandTone(posture.band)}>{posture.band?.toLowerCase()}</Badge>
              </div>
            ) : (
              <div className="flex items-end gap-2">
                <span className="text-2xl font-semibold leading-none tracking-tight text-muted-foreground">
                  Not enough coverage
                </span>
              </div>
            )}
            <span className="text-[11px] text-muted-foreground">
              {shown
                ? "Higher is worse. DOC-28 §10.2 — worst finding, concentration, time in commitment, unmeasured scope."
                : "A number here would be computed over too little of the estate to mean anything."}
            </span>
          </div>

          <div className="flex flex-col gap-1">
            <span className="tabular text-2xl font-semibold leading-none tracking-tight">
              {coverage}%
            </span>
            <span className="text-[11px] text-muted-foreground">
              of applications assessed — {posture.measuredAssets} of {posture.assets}
            </span>
          </div>

          <div className="flex flex-col gap-1">
            <Badge tone={posture.confidence === "HIGH" ? "ok"
              : posture.confidence === "INSUFFICIENT" ? "critical" : "warn"}>
              {posture.confidence.toLowerCase()} confidence
            </Badge>
            <span className="max-w-64 text-[11px] text-muted-foreground">
              {CONFIDENCE_NOTE[posture.confidence]}
            </span>
          </div>
        </div>

        {shown && <Components posture={posture} />}

        {/* Named, not hidden in a tooltip. These three integrations are the difference between a
            directional figure and one somebody can set a target against, and the customer deciding
            whether to buy the feed should be able to see what it buys them. */}
        <p className="border-t pt-3 text-[11px] text-muted-foreground">
          Scored on {Math.round(model.factorCoverage * 100)}% of the model's factor weight.
          No input for {model.absentFactors.join(", ")} — exploit prediction, the known-exploited
          catalogue, and asset data classification. Connecting those three raises confidence and
          changes the ranking; until then the score cannot tell a proven-exploited flaw from a
          theoretical one of the same severity. Model {model.version}.
        </p>
      </CardContent>
    </Card>
  );
}

/** The four §10.2 components, so a unit that disputes its number can see what produced it. */
function Components({ posture }: { posture: RiskPosture }) {
  const rows: { label: string; value: number; note: string }[] = [
    { label: "Severity pressure", value: posture.components.severityPressure,
      note: "the worst open finding, not the sum — so a large unit is not penalised for being large" },
    { label: "Concentration", value: posture.components.concentration,
      note: "how far the worst sits above the average; high means few assets carry it" },
    { label: "Outside commitment", value: 1 - posture.components.slaHealth,
      note: "open findings past the 90-day commitment window" },
    { label: "Unmeasured scope", value: posture.components.coveragePenalty,
      note: "applications never assessed — scanning less makes this worse, never better" },
  ];
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      {rows.map((row) => (
        <div key={row.label} className="flex flex-col gap-1">
          <div className="flex items-baseline justify-between gap-2">
            <span className="text-[11px] font-medium">{row.label}</span>
            <span className="tabular text-[11px] text-muted-foreground">
              {Math.round(row.value * 100)}
            </span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-muted">
            <div className="h-full rounded-full bg-foreground/70"
                 style={{ inlineSize: `${Math.round(row.value * 100)}%` }} />
          </div>
          <span className="text-[10px] leading-tight text-muted-foreground">{row.note}</span>
        </div>
      ))}
    </div>
  );
}

/** Open findings per score band. */
export function RiskDistribution({ rows }: { rows: { band: string; findings: number }[] }) {
  const total = rows.reduce((sum, r) => sum + r.findings, 0);
  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle>Open findings by risk band</CardTitle></CardHeader>
      <CardContent className="flex flex-col gap-3">
        {total === 0 ? (
          <p className="text-sm text-muted-foreground">
            Nothing open in scope. That is not the same as an estate with no risk in it — check the
            coverage figure above before reading this as good news.
          </p>
        ) : (
          <>
            {rows.map((row) => (
              <div key={row.band} className="flex items-center gap-3">
                <span className="w-28 shrink-0">
                  <Badge tone={bandTone(row.band)}>{row.band.toLowerCase()}</Badge>
                </span>
                <div className="h-2 flex-1 overflow-hidden rounded-full bg-muted">
                  <div className="h-full rounded-full bg-foreground/70"
                       style={{ inlineSize: `${Math.round((row.findings / total) * 100)}%` }} />
                </div>
                <span className="tabular w-10 shrink-0 text-right text-xs">{row.findings}</span>
              </div>
            ))}
            {/* The band is the unit of action, and the reason belongs on the page rather than in a
                specification nobody at this desk has read. */}
            <p className="text-[11px] text-muted-foreground">
              Bands rather than raw scores: the difference between 71 and 74 is inside the model's own
              error, and ordering work by it would be false precision. Severity alone does not set the
              band — an internal low-criticality service and an internet-facing revenue system with the
              same severity land in different ones.
            </p>
          </>
        )}
      </CardContent>
    </Card>
  );
}

/** Posture per organization or per application, ranked worst first. */
export function RiskRanking({ title, rows, empty, href }: {
  title: string; rows: RiskPosture[]; empty: string; href?: (row: RiskPosture) => string;
}) {
  if (rows.length === 0) {
    return (
      <Card>
        <CardHeader className="pb-2"><CardTitle>{title}</CardTitle></CardHeader>
        <CardContent className="text-sm text-muted-foreground">{empty}</CardContent>
      </Card>
    );
  }
  return (
    <Card className="overflow-hidden">
      <CardHeader className="pb-2"><CardTitle>{title}</CardTitle></CardHeader>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead className="text-right">Risk</TableHead>
            <TableHead className="text-right">Worst finding</TableHead>
            <TableHead className="text-right">Open</TableHead>
            <TableHead className="text-right">Assessed</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.id}>
              <TableCell className="max-w-64 truncate text-xs font-medium">
                {href ? (
                  <Link to={href(row)} className="text-primary hover:underline">{row.name}</Link>
                ) : row.name}
              </TableCell>
              <TableCell className="text-right">
                {row.posture === null ? (
                  // Not a dash and not a zero. Both read as "fine"; this reads as "unknown", which is
                  // what it is, and it is the row a reader should look at hardest.
                  <span className="text-[11px] text-muted-foreground">not enough coverage</span>
                ) : row.findings === 0 && row.measuredAssets > 0 ? (
                  // Assessed and clean. The distinction PP-1 is built on: this is a different claim
                  // from the row above it, and a 0 in a risk column would make them look identical.
                  <Badge tone="ok">assessed, clear</Badge>
                ) : (
                  <span className="inline-flex items-center gap-1.5">
                    <span className="tabular text-xs font-semibold">{row.posture}</span>
                    <Badge tone={bandTone(row.band)}>{row.band?.toLowerCase()}</Badge>
                  </span>
                )}
              </TableCell>
              <TableCell className="tabular text-right text-xs">
                {row.findings > 0 ? row.worstScore : "—"}
              </TableCell>
              <TableCell className="tabular text-right text-xs">{row.findings}</TableCell>
              <TableCell className="tabular text-right text-xs text-muted-foreground">
                {row.measuredAssets}/{row.assets}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Card>
  );
}

/** The highest-scoring open findings — what to work on first, in the model's order. */
export function RiskQueue({ rows }: { rows: RiskFinding[] }) {
  if (rows.length === 0) {
    return null;
  }
  return (
    <Card className="overflow-hidden">
      <CardHeader className="pb-2">
        <CardTitle>Highest risk open findings</CardTitle>
      </CardHeader>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Finding</TableHead>
            <TableHead>Severity</TableHead>
            <TableHead>Exposure</TableHead>
            <TableHead>Criticality</TableHead>
            <TableHead className="text-right">Risk</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.id}>
              <TableCell className="max-w-80 truncate text-xs">
                {row.requestId ? (
                  <Link to={`/board/${row.requestId}/findings/${row.id}`}
                        className="font-medium text-primary hover:underline">{row.title}</Link>
                ) : <span className="font-medium">{row.title}</span>}
              </TableCell>
              <TableCell>
                <Badge tone={row.severity === "UNRATED" ? "unknown" : severityTone(row.severity)}>
                  {row.severity === "UNRATED" ? "not rated" : row.severity.toLowerCase()}
                </Badge>
              </TableCell>
              <TableCell className="text-[11px] text-muted-foreground">
                {row.exposure === "UNCLASSIFIED" ? "not classified" : row.exposure.toLowerCase().replace(/_/g, " ")}
              </TableCell>
              <TableCell className="text-[11px] text-muted-foreground">
                {row.criticality === "UNCLASSIFIED" ? "not classified" : row.criticality.toLowerCase()}
              </TableCell>
              <TableCell className="text-right">
                <span className="inline-flex items-center gap-1.5">
                  <span className="tabular text-xs font-semibold">{row.score}</span>
                  <Badge tone={bandTone(row.band)}>{row.band.toLowerCase()}</Badge>
                </span>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <CardContent className="border-t text-[11px] text-muted-foreground">
        Ordered by risk, not by severity. Where the two disagree it is because exposure or business
        criticality moved the finding — the columns showing which are beside the score for that
        reason.
      </CardContent>
    </Card>
  );
}
