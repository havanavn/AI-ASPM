import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { CalendarRange, Lock } from "lucide-react";
import { DateField } from "@/components/DateField";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { Backlog, type BacklogPoint } from "@/components/Backlog";
import { BarList, FigureTable, RatioBars, type Slice } from "@/components/Charts";
import { Trend } from "@/components/Trend";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Pager, usePaging } from "@/components/Paging";

interface Counts { requestsRaised: number; requestsClosed: number; findingsFound: number; findingsClosed: number }
interface Analytics {
  from: string; to: string; granularity: "week" | "month";
  headline: Counts;
  previous: Counts & { from: string; to: string };
  backlog: BacklogPoint[];
  requestTrend: { label: string; opened: number; closed: number }[];
  escaped: { label: string; escaped: number; total: number }[];
  attainment: { label: string; met: number; missed: number; noDate: number; stillOpenLate: number }[];
  aging: Slice[];
  retestQueue: Slice[];
  byTrigger: Slice[];
  byTeam: Slice[];
  findingsByTeam: Slice[];
  coverage: {
    nodeId: string; nodeName: string; path: string; applications: number;
    assessedThisYear: number; reviewsThisYear: number; neverAssessed: number;
  }[];
  serviceLevel: { policies: number; clocks: number; measurable: boolean };
  individual?: {
    byAssessor: Slice[];
    byCoverageArea: Slice[];
    seriousFindings: Slice[];
    cycleTime: { key: string; label: string; closed: number; meanDays: number; medianDays: number }[];
  };
}
interface Live {
  headline: Record<string, number>;
  flow: { category: string; state: string; count: number; clockRunning: boolean }[];
  stages: { state: string; transitions: number; averageHours: number; clockRunning: boolean }[];
  waiting: { requestCode: string; state: string; reason: string; hoursWaiting: number }[];
  membersWithCapacity: number;
}

const PRESETS = [
  { label: "30 days", days: 30 }, { label: "90 days", days: 90 },
  { label: "6 months", days: 182 }, { label: "A year", days: 365 },
];

/**
 * Workload — one dashboard, ordered by decision rather than by data source.
 *
 * **This replaced two overlapping pages.** A separate "Analytics" page had grown alongside this one
 * with its own copy of findings-by-severity, its own per-person panel and its own service level
 * panel. Product principle 10 is one name, one meaning, one place, and two dashboards answering the
 * same question is how they come to answer it differently.
 *
 * The order is the argument. **What needs a person today** first, because that is why somebody opens
 * a workload page on a Monday and every figure in it links to the list that produced it. **Is it
 * getting better** second, because that is the question a lead is asked and cannot answer from
 * activity counts. Throughput, coverage and per-person come after, in that order, because they are
 * read weekly, monthly and quarterly.
 */
export function WorkloadPage() {
  const [params, setParams] = useSearchParams();
  const [data, setData] = useState<Analytics | null>(null);
  const [live, setLive] = useState<Live | null>(null);
  const [error, setError] = useState<string | null>(null);
  const coverage = usePaging(data?.coverage ?? []);
  const cycleTime = usePaging(data?.individual?.cycleTime ?? []);

  const today = useMemo(() => new Date().toISOString().slice(0, 10), []);
  const from = params.get("from") ?? "";
  const to = params.get("to") ?? "";
  const granularity = params.get("granularity") === "month" ? "month" : "week";
  const query = useMemo(() => {
    const q = new URLSearchParams();
    if (from) q.set("from", from);
    if (to) q.set("to", to);
    q.set("granularity", granularity);
    return q.toString();
  }, [from, to, granularity]);

  const load = useCallback(() => {
    api.get<Analytics>(`/api/ui/workload/analytics?${query}`).then(setData)
      .catch((e) => setError(e.message));
    api.get<Live>("/api/ui/workload").then(setLive).catch(() => setLive(null));
  }, [query]);
  useEffect(load, [load]);

  function preset(days: number) {
    const end = new Date();
    const start = new Date(end.getTime() - days * 86400000);
    const next = new URLSearchParams(params);
    next.set("from", start.toISOString().slice(0, 10));
    next.set("to", end.toISOString().slice(0, 10));
    setParams(next, { replace: true });
  }
  function setParam(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value); else next.delete(key);
    setParams(next, { replace: true });
  }

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>;

  const unassigned = live?.headline?.findings_unassigned ?? 0;
  const blocked = live?.waiting?.length ?? 0;
  const awaitingRetest = data.retestQueue.reduce((a, s) => a + s.value, 0);
  const neverReviewed = data.coverage.reduce((a, c) => a + c.neverAssessed, 0);
  const overdueNow = data.attainment.reduce((a, r) => a + r.stillOpenLate, 0);

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold tracking-tight">Workload</h1>
        <p className="text-xs text-muted-foreground">
          What needs a person, whether the estate is improving, and what the team is carrying. A
          panel saying “not measured” is naming something unconfigured, not an empty result.
        </p>
      </div>

      {/* ---- 1. What needs a person today. Every tile links to the list behind it. ---------- */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
        <Action label="Findings with no owner" value={unassigned} to="/board" tone="warn" />
        <Action label="Requests past due, still open" value={overdueNow} to="/board?only=overdue"
                tone="critical" />
        <Action label="Fixes awaiting retest" value={awaitingRetest} to="/board" tone="critical"
                hint="The delivery team is waiting on us" />
        <Action label="Blocked on somebody" value={blocked} to="/board" tone="warn" />
        <Action label="Applications never reviewed" value={neverReviewed} to="/applications"
                tone="critical" />
      </div>

      <Card>
        <CardContent className="flex flex-wrap items-end gap-3">
          <CalendarRange className="mb-2 size-4 text-muted-foreground" />
          <div className="flex flex-col gap-1">
            <Label htmlFor="from">From</Label>
            <DateField id="from" value={from || data.from} max={to || today}
                       onChange={(v) => setParam("from", v)} className="w-36" />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="to">To</Label>
            <DateField id="to" value={to || data.to} max={today} min={from || undefined}
                       onChange={(v) => setParam("to", v)} className="w-36" />
          </div>
          <div className="flex gap-1">
            {PRESETS.map((p) => (
              <Button key={p.label} variant="outline" size="sm" onClick={() => preset(p.days)}>
                {p.label}
              </Button>
            ))}
          </div>
          <div className="flex gap-1">
            {(["week", "month"] as const).map((g) => (
              <Button key={g} size="sm" variant={granularity === g ? "default" : "outline"}
                      onClick={() => setParam("granularity", g)}>By {g}</Button>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* ---- 2. Is it getting better. The question activity counts cannot answer. ----------- */}
      <Card>
        <CardHeader>
          <CardTitle>Open findings over time</CardTitle>
          <CardDescription>
            The backlog at the end of each {data.granularity}. Everything else on this page is
            activity, which rises both when a team works harder and when it simply tests more — this
            is the series that separates the two.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Backlog points={data.backlog}
                   empty="No finding has been recorded in scope, so there is no backlog to draw — this is not a backlog of zero." />
        </CardContent>
      </Card>

      {/* ---- 3. Throughput, against the window before it. ----------------------------------- */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Movement label="Requests raised" now={data.headline.requestsRaised}
                  before={data.previous.requestsRaised} lowerIsBetter={false} />
        <Movement label="Requests closed" now={data.headline.requestsClosed}
                  before={data.previous.requestsClosed} lowerIsBetter={false} />
        <Movement label="Findings found" now={data.headline.findingsFound}
                  before={data.previous.findingsFound} lowerIsBetter={false} />
        <Movement label="Findings closed" now={data.headline.findingsClosed}
                  before={data.previous.findingsClosed} lowerIsBetter={false} />
      </div>
      <p className="-mt-3 text-[11px] text-muted-foreground">
        Compared with {data.previous.from} to {data.previous.to}, the window of the same length
        immediately before this one. Neither direction is automatically good — more findings found
        can mean better testing.
      </p>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Requests raised and closed</CardTitle>
            <CardDescription>
              Both series, never a net figure: a net of zero is a team closing as fast as work
              arrives, and it looks identical to a team doing nothing.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Trend weeks={data.requestTrend}
                   measured={data.requestTrend.some((p) => p.opened || p.closed)} />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Due date attainment</CardTitle>
            <CardDescription>
              Closed on or before the date it was given. Requests with no date are counted
              separately — folding them into “met” would report attainment nothing can evidence.
            </CardDescription>
          </CardHeader>
          <CardContent><AttainmentChart rows={data.attainment} /></CardContent>
        </Card>
      </div>

      {/* ---- 4. Queues. Where work is sitting, and with whom. ------------------------------- */}
      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader>
            <CardTitle>How long findings have been open</CardTitle>
            <CardDescription>The shape matters more than the average.</CardDescription>
          </CardHeader>
          <CardContent><BarList slices={data.aging} tone="warn" empty="Nothing is open in scope." /></CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Waiting for retest</CardTitle>
            <CardDescription>A fix was claimed and nobody has verified it.</CardDescription>
          </CardHeader>
          <CardContent>
            <BarList slices={data.retestQueue} tone="critical" empty="Nothing is waiting on a retest." />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Requests by state</CardTitle>
            <CardDescription>
              A state whose clock is paused is work nobody on this team can move.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {live && live.flow.length > 0 ? (
              <BarList empty="" slices={live.flow.map((f) => ({
                key: f.state, label: `${f.state}${f.clockRunning ? "" : " · waiting"}`,
                value: f.count, population: 0,
              }))} />
            ) : (
              <p className="text-xs italic text-tone-unknown">No request in scope.</p>
            )}
          </CardContent>
        </Card>
      </div>

      {live && live.waiting.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Blocked, and on what</CardTitle>
            <CardDescription>
              The reason is the actionable part — a queue that says only that something is blocked is
              a queue nobody works.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-2">
            {live.waiting.map((w) => (
              <div key={w.requestCode} className="flex flex-wrap items-baseline gap-2 text-xs">
                <code className="text-[11px]">{w.requestCode}</code>
                <Badge tone="warn">{w.state}</Badge>
                <span className="tabular text-muted-foreground">{w.hoursWaiting}h</span>
                {w.reason
                  ? <span>{w.reason}</span>
                  : <span className="italic text-tone-unknown">No reason was recorded.</span>}
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      {/* ---- 5. Coverage and escape. Read monthly. ------------------------------------------ */}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Serious findings that reached production</CardTitle>
            <CardDescription>
              Found through a channel that only exists after release — a bug bounty submission or an
              incident. An external penetration test is deliberately not counted: it runs against
              pre-production, and counting it would inflate this with work that caught things in time.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <RatioBars empty="No serious finding was recorded in this window, so there is nothing to compute a ratio over."
                       points={data.escaped.map((e) => ({ label: e.label, numerator: e.escaped,
                                                          denominator: e.total }))} />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Why work arrives</CardTitle>
            <CardDescription>
              All ad hoc means no cadence; all periodic means nothing is responding to change.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <BarList slices={data.byTrigger} empty="No request was raised in this window." />
          </CardContent>
        </Card>
      </div>

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle>Review coverage by organization</CardTitle>
          <CardDescription>
            Full application reviews completed this year, against what each part of the organization
            owns.
          </CardDescription>
        </CardHeader>
        {data.coverage.length === 0 ? (
          <CardContent className="text-sm text-muted-foreground">
            No part of your scope owns an application.
          </CardContent>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Organization</TableHead>
                <TableHead className="text-right">Applications</TableHead>
                <TableHead className="text-right">Reviewed this year</TableHead>
                <TableHead className="text-right">Reviews run</TableHead>
                <TableHead className="text-right">Never reviewed</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {coverage.rows.map((c) => (
                <TableRow key={c.nodeId}>
                  <TableCell className="text-xs">
                    {c.nodeName}
                    {c.path && <div className="text-[11px] text-muted-foreground">{c.path}</div>}
                  </TableCell>
                  <TableCell className="tabular text-right">{c.applications}</TableCell>
                  <TableCell className="tabular text-right">
                    {c.assessedThisYear} of {c.applications}
                  </TableCell>
                  <TableCell className="tabular text-right text-muted-foreground">
                    {c.reviewsThisYear}
                  </TableCell>
                  <TableCell className="text-right">
                    {c.neverAssessed > 0
                      ? <Badge tone="critical">{c.neverAssessed}</Badge>
                      : <span className="tabular text-muted-foreground">0</span>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        <Pager paging={coverage} unit="organizations" />
      </Card>

      {/* ---- 6. Who. Team first, then per person behind its own permission. ----------------- */}
      <Card>
        <CardHeader>
          <CardTitle>By team</CardTitle>
          <CardDescription>
            Summed from the people on each team. Membership is exclusive — one live team per person —
            which is what lets these bars be added up. Rosters live under{" "}
            <Link to="/access?tab=teams" className="text-primary hover:underline">Access → Teams</Link>.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-6 lg:grid-cols-2">
          <div className="flex flex-col gap-2">
            <div className="text-xs font-medium">Requests</div>
            <BarList slices={data.byTeam} empty="No request was raised in this window." />
          </div>
          <div className="flex flex-col gap-2">
            <div className="text-xs font-medium">Findings recorded</div>
            <BarList slices={data.findingsByTeam} tone="warn"
                     empty="No finding was recorded in this window." />
          </div>
        </CardContent>
      </Card>

      {data.individual ? (
        <>
          <Card>
            <CardHeader className="flex-row items-start justify-between gap-4">
              <div>
                <CardTitle>Per person</CardTitle>
                <CardDescription>
                  For capacity planning. <strong>Not a performance measure and not a ranking</strong> —
                  every list is ordered by name, because a chart sorted by count is a league table
                  whatever its caption says.
                </CardDescription>
              </div>
              <Badge tone="critical"><Lock className="size-3" /> restricted</Badge>
            </CardHeader>
            <CardContent className="grid gap-6 lg:grid-cols-3">
              <div className="flex flex-col gap-2">
                <div className="text-xs font-medium">Requests</div>
                <BarList slices={data.individual.byAssessor}
                         empty="No request was raised in this window." />
              </div>
              <div className="flex flex-col gap-2">
                <div className="text-xs font-medium">Serious findings recorded</div>
                <BarList slices={data.individual.seriousFindings} tone="warn"
                         empty="No serious finding was recorded in this window." />
              </div>
              <div className="flex flex-col gap-2">
                <div className="text-xs font-medium">By coverage area</div>
                <p className="text-[11px] text-muted-foreground">
                  The org node each assessor is scoped to, which is not the same as their team.
                </p>
                <BarList slices={data.individual.byCoverageArea} tone="ok"
                         empty="No request was raised in this window." />
              </div>
            </CardContent>
          </Card>

          <Card className="overflow-hidden">
            <CardHeader>
              <CardTitle>Time to close a request</CardTitle>
              <CardDescription>
                Median beside the mean, always. One engagement blocked for three months moves a mean
                enough to make a team look slow, and the median says whether that is what happened.
              </CardDescription>
            </CardHeader>
            {data.individual.cycleTime.length === 0 ? (
              <CardContent className="text-sm italic text-tone-unknown">
                Not measured. No request was closed in this window, so nothing has a duration — this
                is not zero days.
              </CardContent>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Assessor</TableHead>
                    <TableHead className="text-right">Closed</TableHead>
                    <TableHead className="text-right">Median days</TableHead>
                    <TableHead className="text-right">Mean days</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {cycleTime.rows.map((c) => (
                    <TableRow key={c.key}>
                      <TableCell className="text-xs">{c.label}</TableCell>
                      <TableCell className="tabular text-right">{c.closed}</TableCell>
                      <TableCell className="tabular text-right font-medium">
                        {c.medianDays.toFixed(1)}
                      </TableCell>
                      <TableCell className="tabular text-right text-muted-foreground">
                        {c.meanDays.toFixed(1)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
            <Pager paging={cycleTime} unit="assessors" />
          </Card>
        </>
      ) : (
        <Card>
          <CardContent className="text-xs text-muted-foreground">
            Per-person figures need an explicit permission, which is never implied by seniority
            (PRD-CAP-013).
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader><CardTitle>What is not here, and why</CardTitle></CardHeader>
        <CardContent className="flex flex-col gap-2 text-xs text-muted-foreground">
          <p>
            <strong className="text-foreground">Severity-based service levels.</strong> Due date
            attainment above measures the date each request was actually given. The separate{" "}
            <code>service_level_policy</code> machinery — a target per severity against a business
            calendar, with pause semantics — has {data.serviceLevel.policies} policies and{" "}
            {data.serviceLevel.clocks} clocks configured, so it computes nothing.
          </p>
          <p>
            <strong className="text-foreground">Utilization.</strong> No member availability or
            capacity is recorded, so the denominator does not exist. A ratio against a guess would be
            quoted in a staffing conversation.
          </p>
          <p>
            <strong className="text-foreground">Risk score.</strong> DOC-28's model is not
            implemented, so no application carries one and no chart here is risk-weighted.
          </p>
        </CardContent>
      </Card>
    </div>
  );
}

/**
 * A thing that needs a person, and the list it lives in.
 *
 * Zero is rendered quietly and in the neutral colour — a dashboard where every tile is red at all
 * times is a dashboard people stop reading, and an empty queue is the good outcome.
 */
function Action({ label, value, to, tone, hint }: {
  label: string; value: number; to: string; tone: "warn" | "critical"; hint?: string;
}) {
  const live = value > 0;
  return (
    <Link to={to} className={cn("rounded-lg border bg-card px-4 py-3 transition-colors",
                                live ? "hover:border-primary/50" : "opacity-70 hover:opacity-100")}>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={cn("tabular mt-1 text-2xl font-semibold tracking-tight",
                         live && tone === "critical" && "text-sev-critical",
                         live && tone === "warn" && "text-tone-warn")}>{value}</div>
      <div className="mt-0.5 text-[11px] text-muted-foreground">
        {live ? (hint ?? "Open the list") : "Nothing waiting"}
      </div>
    </Link>
  );
}

/** A count against the same-length window before it. */
function Movement({ label, now, before, lowerIsBetter }: {
  label: string; now: number; before: number; lowerIsBetter: boolean;
}) {
  const delta = now - before;
  const good = lowerIsBetter ? delta < 0 : delta > 0;
  return (
    <div className="rounded-lg border bg-card px-4 py-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="tabular mt-1 text-2xl font-semibold tracking-tight">{now}</div>
      <div className={cn("mt-0.5 text-[11px]",
                         delta === 0 ? "text-muted-foreground"
                                     : good ? "text-tone-ok" : "text-tone-warn")}>
        {/* An arrow AND a word: an arrow alone needs the reader to know which way is good, and for
            half these figures it is genuinely ambiguous. */}
        {delta === 0 ? `unchanged from ${before}`
          : `${delta > 0 ? "▲" : "▼"} ${Math.abs(delta)} from ${before}`}
      </div>
    </div>
  );
}

/**
 * Due date attainment: met, missed, and the two things a simpler chart gets wrong.
 *
 * A request with **no due date** cannot be judged and is shown beside the figure rather than folded
 * into either side — attainment computed over requests that never had a target is attainment the
 * platform cannot evidence. A request **still open past its date** gets its own marker outside the
 * bar: counting it as missed judges work that is not finished, and ignoring it hides an overrun
 * happening right now.
 */
function AttainmentChart({ rows }: {
  rows: { label: string; met: number; missed: number; noDate: number; stillOpenLate: number }[];
}) {
  const judged = rows.reduce((a, r) => a + r.met + r.missed, 0);
  if (judged === 0) {
    return (
      <p className="text-xs italic text-tone-unknown">
        Not measured. No request with a due date was closed in this window, so there is nothing to
        judge — this is not zero attainment.
      </p>
    );
  }
  const peak = Math.max(1, ...rows.map((r) => r.met + r.missed));
  const totalMet = rows.reduce((a, r) => a + r.met, 0);
  const undated = rows.reduce((a, r) => a + r.noDate, 0);

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <span className="tabular text-2xl font-semibold">
          {Math.round((100 * totalMet) / judged)}%
        </span>
        <span className="text-xs text-muted-foreground">
          {totalMet} of {judged} closed on time
        </span>
        {undated > 0 && <Badge tone="unknown">{undated} closed with no date set</Badge>}
      </div>

      <div className="flex flex-wrap gap-3 text-[11px] text-muted-foreground">
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block size-2.5 rounded-sm bg-tone-ok" />On time
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block size-2.5 rounded-sm bg-sev-critical" />Late
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block size-2.5 rounded-sm border border-dashed border-tone-warn" />
          Still open, past due
        </span>
      </div>

      <div className="flex items-end gap-1 overflow-x-auto" style={{ blockSize: "8rem" }}>
        {rows.map((r) => {
          const total = r.met + r.missed;
          return (
            <div key={r.label} className="flex min-w-10 flex-1 flex-col items-center gap-1">
              <span className="tabular text-[10px] text-muted-foreground">
                {total > 0 ? `${Math.round((100 * r.met) / total)}%` : ""}
              </span>
              <div className="flex w-full flex-col-reverse"
                   style={{ blockSize: `${Math.round((100 * total) / peak)}%`,
                            minBlockSize: total ? "2px" : "0" }}>
                {r.met > 0 && <div className="bg-tone-ok"
                                   style={{ blockSize: `${(100 * r.met) / total}%` }} />}
                {r.missed > 0 && <div className="bg-sev-critical"
                                      style={{ blockSize: `${(100 * r.missed) / total}%` }} />}
              </div>
              {r.stillOpenLate > 0 && (
                <span className="tabular rounded border border-dashed border-tone-warn px-1 text-[9px] text-tone-warn">
                  {r.stillOpenLate}
                </span>
              )}
              <span className="text-[9px] text-muted-foreground">{r.label.slice(-5)}</span>
            </div>
          );
        })}
      </div>

      <FigureTable head={["Period", "On time", "Late", "No date", "Open past due"]}
                   rows={rows.map((r) => [r.label, String(r.met), String(r.missed),
                     String(r.noDate), String(r.stillOpenLate)])} />
    </div>
  );
}
