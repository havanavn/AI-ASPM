import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "@/lib/api";
import { Kpi } from "@/components/Kpi";
import { BarList, FigureTable, SeverityBars, StackedColumns, SEVERITY_FILL, SEVERITY_ORDER }
  from "@/components/Charts";
import { Trend } from "@/components/Trend";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * The security posture of one application.
 *
 * Everything here answers a question the profile card above it cannot: not "what is this
 * application" but "what condition is it in, and how much of that do we actually know".
 *
 * Three rules run through every panel and are the reason this is one component rather than charts
 * scattered through the page:
 *
 * 1. **A count is never shown without what it was counted over.** Open findings against total
 *    findings, covered parts against parts, months measured against months elapsed. Zero open
 *    findings over zero findings is an application nobody has looked at, and it is the single most
 *    reassuring — and most wrong — thing this product can display (product principle 1).
 * 2. **An unmeasured value renders as a word, never as a zero.** `PRD-UIX-022`. A mean time to
 *    remediate of "0 days" over nothing closed reads as same-day fixes.
 * 3. **Colour is never the only difference.** DOC-00 section 11.4. Every bar carries its number as
 *    text, every series carries its name, and every chart has the same figures underneath it as a
 *    table — which is also what a screen reader and a copy-paste into a report get.
 */

interface Headline {
  componentCount: number; findingTotal: number; findingOpen: number; findingAccepted: number;
  criticalOpen: number; criticalTotal: number; highOpen: number; highTotal: number;
  mediumOpen: number; lowOpen: number; scaOpen: number; scaTotal: number;
  openOver30Days: number; openOver90Days: number; openOver180Days: number;
  closedLast90Days: number; meanDaysToClose: number | null; openOldestDays: number | null;
  remediationClaimedOpen: number; requestTotal: number; requestOpen: number;
  lastDetectedAt: string | null; lastRequestClosedAt: string | null;
  sbomCoveredParts: number; sbomLatestAt: string | null; sbomRejectedParts: number;
}
interface Severity { code: string; ordinal: number; total: number; open: number; openOver90Days: number }
interface MonthPoint { label: string; opened: number; closed: number }
interface AgeBand { label: string; critical: number; high: number; medium: number; low: number; unrated: number }
interface Part {
  id: string; name: string; typeCode: string; depth: number;
  open: number; criticalOpen: number; highOpen: number; total: number; lastDetectedAt: string | null;
}
interface Slice { key: string; total: number; open: number }
interface Remediation {
  code: string; ordinal: number; closedCount: number;
  meanDaysToClose: number | null; medianDaysToClose: number | null; oldestOpenDays: number | null;
}
interface Assurance {
  findingClass: string; coveredParts: number; findingCount: number; openCount: number;
  lastEvidenceAt: string | null; tools: string[];
}
interface RequestSla {
  met: number; missed: number; openPastDue: number; openWithinDue: number;
  closedNoDueDate: number; openNoDueDate: number;
}
interface Payload {
  measured: boolean; headline: Headline; severities: Severity[]; trend: MonthPoint[];
  ageBands: AgeBand[]; parts: Part[]; classes: Slice[]; contexts: Slice[]; closures: Slice[];
  tools: Slice[]; remediation: Remediation[]; assurance: Assurance[]; assuranceClasses: string[];
  requestSla: RequestSla;
}

/** The contexts in which a finding reaching production means the assurance did not catch it. */
const ESCAPED_CONTEXTS = new Set(["BUG_BOUNTY", "INCIDENT"]);

/** Human wording for the product-fixed vocabulary. Default strings; INT-UIX-004 externalizes them. */
const CLASS_LABEL: Record<string, string> = {
  CODE: "Code review / SAST",
  DEPENDENCY: "Dependencies / SCA",
  RUNTIME: "Running instance / DAST",
  SECRET: "Secret scanning",
  CONFIGURATION: "Configuration review",
  INFRASTRUCTURE: "Infrastructure and network",
  MANUAL: "Human assessment",
};
const CONTEXT_LABEL: Record<string, string> = {
  INTERNAL_PENTEST: "Internal penetration test",
  EXTERNAL_PENTEST: "External penetration test",
  AUTOMATED_SCAN: "Automated scan",
  REDTEAM_INTERNAL: "Internal red team",
  BUG_BOUNTY: "Bug bounty — found in production",
  INCIDENT: "Incident — found in production",
  UNSPECIFIED: "Not stated",
};
const CLOSURE_LABEL: Record<string, string> = {
  FIXED_VERIFIED: "Fixed and verified",
  FIXED_UNVERIFIED: "Fixed, not verified",
  RISK_ACCEPTED: "Risk accepted",
  FALSE_POSITIVE: "Not a finding",
  DUPLICATE: "Duplicate",
  NOT_APPLICABLE: "Not applicable",
  UNSPECIFIED: "No reason recorded",
};

/**
 * `subject` is the noun the prose uses, and `kind` picks the route.
 *
 * One component for both, because it is one question asked of one aggregate — ADR-009 keeps a single
 * `Asset` with a type registry, and every view behind this is rooted at whatever asset it is asked
 * about. A second copy of this panel for projects would drift from this one within two changes.
 */
export function ApplicationPosture({ applicationId, kind = "applications", subject = "application" }: {
  applicationId: string; kind?: "applications" | "projects"; subject?: string;
}) {
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setData(null);
    setError(null);
    api.get<Payload>(`/api/ui/${kind}/${applicationId}/posture`)
      .then((d) => live && setData(d))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, [applicationId, kind]);

  if (error) {
    // Reported, never rendered as an empty dashboard. A posture panel that fails silently reads as
    // an application with nothing wrong with it, which is the one reading it must never produce.
    return (
      <Card>
        <CardHeader><CardTitle>Security posture</CardTitle></CardHeader>
        <CardContent className="text-sm text-destructive">
          The posture figures could not be read: {error}. Nothing below is a measurement — do not
          read this as a clean {subject}.
        </CardContent>
      </Card>
    );
  }
  if (!data) {
    return <Card><CardContent className="text-sm text-muted-foreground">Loading posture…</CardContent></Card>;
  }
  if (!data.measured) {
    return (
      <Card>
        <CardHeader><CardTitle>Security posture</CardTitle></CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          No posture has been computed for this {subject} yet.
        </CardContent>
      </Card>
    );
  }

  const h = data.headline;
  const severeOpen = h.criticalOpen + h.highOpen;
  const closedTotal = h.findingTotal - h.findingOpen;
  const parts = data.parts.filter((p) => p.total > 0);
  const escaped = data.contexts.filter((c) => ESCAPED_CONTEXTS.has(c.key));
  const escapedTotal = escaped.reduce((a, c) => a + c.total, 0);
  const trendMeasured = data.trend.some((m) => m.opened > 0 || m.closed > 0);
  // The parts an assurance class could have covered: the application itself plus everything under it.
  const coverableParts = h.componentCount + 1;
  const sla = data.requestSla;
  const slaJudged = sla.met + sla.missed;

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h2 className="text-base font-semibold tracking-tight">Security posture</h2>
        <p className="text-xs text-muted-foreground">
          Every figure below covers this {subject} and everything recorded beneath it, and each one
          carries the population it was measured over. A blank is not a zero.
        </p>
      </div>

      {/* ---------------------------------------------------------------------------------------
          The six figures somebody acts on. Open against total first, because a bare open count
          cannot distinguish an application in good shape from one nobody has assessed.
      --------------------------------------------------------------------------------------- */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-6">
        <Kpi label="Open findings" value={h.findingOpen} tone={h.findingOpen > 0 ? "info" : "neutral"}
             hint={`of ${h.findingTotal} ever recorded · ${closedTotal} closed`} />
        <Kpi label="Critical and high open" value={severeOpen} tone="critical"
             hint={`${h.criticalOpen} critical · ${h.highOpen} high`} />
        <Kpi label="Open over 90 days" value={h.openOver90Days} tone="warn"
             hint={h.openOver180Days > 0 ? `${h.openOver180Days} of them over 180` : "none over 180"} />
        <Kpi label="Closed in 90 days" value={h.closedLast90Days} tone="ok"
             hint="what came out of the backlog" />
        <Kpi label="Typical time to fix"
             value={h.meanDaysToClose === null ? "not measured" : `${h.meanDaysToClose} days`}
             hint={h.meanDaysToClose === null
               ? "nothing has closed here yet"
               : `mean over ${closedTotal} closed findings`} />
        <Kpi label="Oldest open"
             value={h.openOldestDays === null ? "nothing open" : `${h.openOldestDays} days`}
             tone="warn"
             hint={h.openOldestDays === null ? "no finding is open" : "the number a mean hides"} />
      </div>

      {/* The two waiting states. Both are people waiting on this platform rather than on a fix, and
          product principle 6 says waiting is visible and attributed rather than inferred. */}
      {(h.remediationClaimedOpen > 0 || sla.openPastDue > 0) && (
        <div className="grid gap-3 sm:grid-cols-2">
          {h.remediationClaimedOpen > 0 && (
            <div className="rounded-lg border border-tone-warn/40 bg-card px-4 py-3 text-sm">
              <span className="tabular font-semibold text-tone-warn">{h.remediationClaimedOpen}</span>
              {" "}open {h.remediationClaimedOpen === 1 ? "finding has" : "findings have"} been
              claimed fixed by the delivery team and not yet verified. Until an assessor verifies
              them they stay open, and the wait is on the security team.
            </div>
          )}
          {sla.openPastDue > 0 && (
            <div className="rounded-lg border border-sev-critical/40 bg-card px-4 py-3 text-sm">
              <span className="tabular font-semibold text-sev-critical">{sla.openPastDue}</span>
              {" "}assessment {sla.openPastDue === 1 ? "request is" : "requests are"} open past their
              due date against this {subject}.
            </div>
          )}
        </div>
      )}

      <div className="grid gap-5 lg:grid-cols-2">
        {/* ------------------------------------------------------------------------------------
            What is open, by severity. The track is the total and the fill is what is still open,
            so the empty part of each bar is what has been closed — one bar rather than two figures
            the reader has to relate.
        ------------------------------------------------------------------------------------ */}
        <Card>
          <CardHeader>
            <CardTitle>Open findings by severity</CardTitle>
            <CardDescription>
              The filled part is still open; the rest of the bar has been closed. Severity is the one
              a human set where anybody triaged it, and the tool's own rating where nobody has.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <SeverityBars
              rows={data.severities.map((s) => ({
                code: s.code, open: s.open, total: s.total, aged: s.openOver90Days,
              }))}
              empty={`No finding has ever been recorded against this ${subject}. That is not the same as none existing — nothing has looked.`} />
            {h.findingAccepted > 0 && (
              <p className="mt-3 text-xs text-muted-foreground">
                {h.findingAccepted} of the closed findings were closed as accepted risk, not as fixed.
                They are counted as closed because they are, and they are named here because an
                accepted risk nobody revisits is the one that outlives the person who accepted it.
              </p>
            )}
          </CardContent>
        </Card>

        {/* ------------------------------------------------------------------------------------
            Age. The chart the open count cannot replace: twenty findings that arrived last week and
            twenty that have been open two years are the same number and opposite situations.
        ------------------------------------------------------------------------------------ */}
        <Card>
          <CardHeader>
            <CardTitle>How long the open findings have been open</CardTitle>
            <CardDescription>
              Days since first detection, by severity. A backlog that is old at the top of the
              severity scale is a different problem from a backlog that is merely large.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <StackedColumns
              points={data.ageBands.map((b) => ({
                label: b.label,
                parts: SEVERITY_ORDER.map((code) => ({
                  key: code,
                  value: code === "CRITICAL" ? b.critical : code === "HIGH" ? b.high
                    : code === "MEDIUM" ? b.medium : code === "LOW" ? b.low : b.unrated,
                  className: SEVERITY_FILL[code] ?? "bg-tone-unknown",
                })),
              }))}
              legend={SEVERITY_ORDER.map((code) => ({
                key: code, label: code, className: SEVERITY_FILL[code] ?? "bg-tone-unknown",
              }))}
              empty="Nothing is open, so there is no age to draw." />
          </CardContent>
        </Card>
      </div>

      {/* ---------------------------------------------------------------------------------------
          Flow. Two series and never a net, because a net of zero is a team closing forty findings
          a month while forty-one arrive — indistinguishable from a team doing nothing.
      --------------------------------------------------------------------------------------- */}
      <Card>
        <CardHeader>
          <CardTitle>Findings opened and closed, last twelve months</CardTitle>
          <CardDescription>
            Opened is counted from first detection and closed from the closure date, so a finding
            found in one month and fixed in another appears in both — which is the point.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Trend unit="month" measured={trendMeasured}
                 weeks={data.trend.map((m) => ({ label: m.label, opened: m.opened, closed: m.closed }))} />
        </CardContent>
      </Card>

      <div className="grid gap-5 lg:grid-cols-2">
        {/* ------------------------------------------------------------------------------------
            Where inside the application the open findings sit. Parts with nothing open are shown
            too: a list of only the parts that have findings is a list of the parts somebody looked
            at, and it reads as the whole application.
        ------------------------------------------------------------------------------------ */}
        <Card>
          <CardHeader>
            <CardTitle>Where the findings are</CardTitle>
            <CardDescription>
              Open findings against everything ever found, per part. A project is a link to its own
              dashboard. Drawn from the same per-part figures as the composition table, so the two
              cannot disagree — and a finding concerning two parts appears under both, which is why
              these do not sum to the headline.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <BarList
              slices={parts.map((p) => ({
                key: p.id,
                // A project has a dashboard of its own; nothing else does yet. Linking only the
                // rows that lead somewhere — see the note on the composition table.
                label: p.typeCode === "PROJECT" ? (
                  <Link to={`/projects/${p.id}`} className="text-primary hover:underline">
                    {p.name} <span className="text-muted-foreground">· {p.typeCode}</span>
                  </Link>
                ) : `${p.name} · ${p.typeCode}`,
                value: p.open, population: p.total,
              }))}
              empty={h.componentCount === 0
                ? `Nothing has been recorded beneath this ${subject}, so every finding sits on the ${subject} itself.`
                : `No part of this ${subject} carries a finding.`} />
            {parts.some((p) => p.criticalOpen + p.highOpen > 0) && (
              <p className="text-xs text-muted-foreground">
                Severe and open:{" "}
                {parts.filter((p) => p.criticalOpen + p.highOpen > 0)
                  .map((p) => `${p.name} (${p.criticalOpen} critical, ${p.highOpen} high)`)
                  .join("; ")}.
              </p>
            )}
            {h.componentCount > 0 && parts.length < h.componentCount && (
              <p className="text-xs text-muted-foreground">
                {h.componentCount - parts.length} of {h.componentCount} parts carry no finding at
                all. That is either a clean part or one nothing has assessed, and the coverage panel
                below is where the difference is answerable.
              </p>
            )}
          </CardContent>
        </Card>

        {/* ------------------------------------------------------------------------------------
            How the weaknesses are being found. The mix answers something the count cannot: an
            application whose findings all come from automated scanning has not been penetration
            tested, however many findings it has.
        ------------------------------------------------------------------------------------ */}
        <Card>
          <CardHeader>
            <CardTitle>How the weaknesses were found</CardTitle>
            <CardDescription>
              The assessment activity each finding came out of. A narrow mix is a coverage result,
              not a clean result.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <BarList
              slices={data.contexts.map((c) => ({
                key: c.key, label: CONTEXT_LABEL[c.key] ?? c.key, value: c.total, population: 0,
              }))}
              empty="No finding carries an assessment context." />
            {escapedTotal > 0 && (
              <p className="text-xs text-tone-warn">
                {escapedTotal} of these were found in production — through a bug bounty report or an
                incident — rather than by an assessment. Those are the ones the assurance did not
                catch, and they are the honest measure of whether it is working.
              </p>
            )}
            {escapedTotal === 0 && data.contexts.length > 0 && (
              <p className="text-xs text-muted-foreground">
                None was found through a bug bounty report or an incident. Nothing recorded escaped
                to production — which is a statement about what was recorded, not a guarantee.
              </p>
            )}
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        {/* Kind of weakness. The classes are product-fixed, so the bars cannot split on spelling. */}
        <Card>
          <CardHeader>
            <CardTitle>What kind of weakness</CardTitle>
            <CardDescription>Open findings by class, against everything ever found in that class.</CardDescription>
          </CardHeader>
          <CardContent>
            <BarList
              slices={data.classes.map((c) => ({
                key: c.key, label: CLASS_LABEL[c.key] ?? c.key, value: c.open, population: c.total,
              }))}
              empty="No finding has been recorded, so there is no distribution to show." />
          </CardContent>
        </Card>

        {/* ------------------------------------------------------------------------------------
            How long fixing takes, and what it costs to look only at the average. The mean and the
            median sit beside each other deliberately: one finding that took three years moves a
            mean and does not move a median, and the gap between them IS the long tail.
        ------------------------------------------------------------------------------------ */}
        <Card className="overflow-hidden">
          <CardHeader>
            <CardTitle>How long remediation takes</CardTitle>
            <CardDescription>
              Over findings that were actually closed. An average that counted the open ones as
              closed today would improve every time a new finding arrived.
            </CardDescription>
          </CardHeader>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Severity</TableHead>
                <TableHead className="text-right">Closed</TableHead>
                <TableHead className="text-right">Mean days</TableHead>
                <TableHead className="text-right">Median days</TableHead>
                <TableHead className="text-right">Oldest still open</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.remediation.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="text-sm text-muted-foreground">
                    Nothing has been recorded here.
                  </TableCell>
                </TableRow>
              ) : data.remediation.map((r) => (
                <TableRow key={r.code}>
                  <TableCell>
                    <span className="inline-flex items-center gap-1.5 text-xs">
                      <span className={`inline-block size-2.5 rounded-sm ${SEVERITY_FILL[r.code] ?? "bg-tone-unknown"}`} />
                      {r.code}
                    </span>
                  </TableCell>
                  <TableCell className="tabular text-right">{r.closedCount}</TableCell>
                  {/* "not measured", never 0. Zero days reads as fixed-the-same-day, which is the
                      most flattering possible reading of nothing having closed at all. */}
                  <TableCell className="tabular text-right">
                    {r.meanDaysToClose === null
                      ? <span className="text-xs italic text-tone-unknown">not measured</span>
                      : r.meanDaysToClose}
                  </TableCell>
                  <TableCell className="tabular text-right">
                    {r.medianDaysToClose === null
                      ? <span className="text-xs italic text-tone-unknown">not measured</span>
                      : r.medianDaysToClose}
                  </TableCell>
                  <TableCell className="tabular text-right">
                    {r.oldestOpenDays === null
                      ? <span className="text-xs text-muted-foreground">none open</span>
                      : `${r.oldestOpenDays} d`}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {data.closures.length > 0 && (
            <CardContent className="border-t pt-4">
              <div className="mb-2 text-xs font-medium">How the closed ones were closed</div>
              <BarList
                slices={data.closures.map((c) => ({
                  key: c.key, label: CLOSURE_LABEL[c.key] ?? c.key, value: c.total, population: 0,
                }))}
                tone="ok"
                empty="Nothing has closed." />
            </CardContent>
          )}
        </Card>
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        {/* ------------------------------------------------------------------------------------
            The coverage panel, and the reason the whole page exists. Every product-fixed class is
            listed, including the ones with no evidence at all — because "nothing of this kind has
            ever run here" is an answer, and a chart drawn only from the classes that produced
            findings silently reports it as a clean result.
        ------------------------------------------------------------------------------------ */}
        <Card className="overflow-hidden">
          <CardHeader>
            <CardTitle>What has ever looked</CardTitle>
            <CardDescription>
              Assurance coverage by activity. A class with no evidence is listed as never — absence
              of evidence is not evidence of absence, and this is the panel that keeps the two apart.
            </CardDescription>
          </CardHeader>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Activity</TableHead>
                <TableHead className="text-right">Parts covered</TableHead>
                <TableHead className="text-right">Findings</TableHead>
                <TableHead>Last evidence</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.assuranceClasses.map((code) => {
                const row = data.assurance.find((a) => a.findingClass === code);
                return (
                  <TableRow key={code}>
                    <TableCell className="text-sm">
                      {CLASS_LABEL[code] ?? code}
                      <div className="font-mono text-[11px] text-muted-foreground">{code}</div>
                    </TableCell>
                    <TableCell className="tabular text-right text-xs">
                      {row ? `${row.coveredParts} of ${coverableParts}` : "—"}
                    </TableCell>
                    <TableCell className="tabular text-right text-xs">
                      {row ? `${row.openCount} open of ${row.findingCount}` : "—"}
                    </TableCell>
                    <TableCell>
                      {row?.lastEvidenceAt
                        ? <span className="font-mono text-[11px]">{row.lastEvidenceAt}</span>
                        : <Badge tone="unknown">never</Badge>}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
          <CardContent className="border-t pt-4 text-xs text-muted-foreground">
            Parts covered counts the parts of this {subject} that produced evidence of that kind,
            against the {coverableParts} that exist. Some of its parts scanned is not the whole {subject}
            scanned, and a tick in a column would say it was.
            {h.sbomCoveredParts === 0 && (
              <> No part of this {subject} has ever submitted a software bill of materials, so the
                dependency picture is whatever a scanner happened to report.</>
            )}
            {h.sbomCoveredParts > 0 && (
              <> {h.sbomCoveredParts} of {coverableParts} parts have submitted a bill of materials
                {h.sbomLatestAt ? `, most recently on ${h.sbomLatestAt}` : ""}.
                {h.sbomRejectedParts > 0 && ` ${h.sbomRejectedParts} submission set was rejected.`}</>
            )}
          </CardContent>
        </Card>

        {/* ------------------------------------------------------------------------------------
            Assessment demand and whether it landed on time. The due date, not a service level
            policy: no policy is configured in this deployment and no clock has run, so a compliance
            percentage would be a percentage over zero measured obligations — which looks like a
            measurement and is not one. Requests with no due date are their own column and are never
            folded into met.
        ------------------------------------------------------------------------------------ */}
        <Card>
          <CardHeader>
            <CardTitle>Assessment requests</CardTitle>
            <CardDescription>
              {h.requestTotal} raised against this {subject}, {h.requestOpen} still open.
              On time means closed on or before the due date the request carried.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {h.requestTotal === 0 ? (
              <p className="text-sm text-muted-foreground">
                No assessment has ever been requested against this {subject}. Every figure above is
                drawn from findings that arrived some other way.
              </p>
            ) : (
              <>
                <BarList
                  slices={[
                    { key: "met", label: "Closed on time", value: sla.met, population: 0 },
                    { key: "missed", label: "Closed late", value: sla.missed, population: 0 },
                    { key: "past", label: "Open, past due", value: sla.openPastDue, population: 0 },
                    { key: "within", label: "Open, within due date", value: sla.openWithinDue, population: 0 },
                    { key: "nodue", label: "No due date set", value: sla.closedNoDueDate + sla.openNoDueDate, population: 0 },
                  ].filter((s) => s.value > 0)}
                  empty="No request carries a due date." />
                <p className="text-xs text-muted-foreground">
                  {slaJudged === 0
                    ? "No closed request carried a due date, so nothing here can be judged on time or late."
                    : `${sla.met} of ${slaJudged} closed requests that had a due date met it.`}
                  {(sla.closedNoDueDate + sla.openNoDueDate) > 0 && (
                    <> {sla.closedNoDueDate + sla.openNoDueDate} request(s) were never given a due
                      date; they are counted separately and never as met.</>
                  )}
                </p>
                <p className="text-xs text-muted-foreground">
                  This is the request's due date, not a configured service level. No service level
                  policy exists in this deployment, and a compliance percentage over zero policies
                  would read as a measurement.
                </p>
                <Link to="/board" className="text-xs text-primary hover:underline">
                  Open the request board →
                </Link>
              </>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Tools, kept small and last. It is a coverage detail rather than a headline, but a class
          covered by one tool and a class covered by three are different confidence levels and
          nothing else on the page says so. */}
      {data.tools.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Which tools produced the evidence</CardTitle>
            <CardDescription>
              Open findings against everything that tool has ever reported here.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {/* The bars, not only the collapsed table. A card whose entire content is a closed
                <details> renders as an empty card, which is what this one did. */}
            <BarList
              slices={data.tools.map((t) => ({
                key: t.key, label: t.key, value: t.open, population: t.total,
              }))}
              empty="No finding records which tool produced it." />
            <FigureTable head={["Tool", "Open", "Total"]}
                         rows={data.tools.map((t) => [t.key, String(t.open), String(t.total)])} />
          </CardContent>
        </Card>
      )}
    </div>
  );
}
