import { Fragment, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import {
  CalendarClock, CalendarPlus, ChevronDown, ChevronRight, Search, Trash2, X,
} from "lucide-react";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { BarList, GroupedColumns, type Slice } from "@/components/Charts";
import { Gantt, type GanttBar } from "@/components/Gantt";
import { ReviewPolicy } from "@/components/ReviewPolicy";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PageSize, Pager, usePaging } from "@/components/Paging";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { MultiSelect } from "@/components/MultiSelect";
import { Checkbox } from "@/components/ui/checkbox";
import { PlanWindowDialog, type PlanTarget } from "@/components/PlanWindowDialog";

interface PlanRow {
  assetId: string; name: string; orgPath: string | null; criticality: string | null;
  completed: number; inFlight: number; abandoned: number;
  lastReviewAt: string | null; intervalMonths: number | null; nextDueAt: string | null;
  status: string; openRequests: number; severeOpen: number;
  /** What the engagement is sized from. `apiCount` is null, never 0, where nothing declared one. */
  exposureDeclared: string | null; apiCount: number | null;
  projectCount: number; plannedWindows: number;
}
/**
 * A project under an application.
 *
 * `apiCount` and `accessPath` are tenant-declared fields on the PROJECT type, which is the reason the
 * plan breaks down this far: an application's API count is a sum over its projects, and a sum is not
 * what an engagement is scoped to.
 */
interface PlanProject {
  assetId: string; projectId: string; name: string;
  apiCount: number | null; accessPath: string | null;
  lifecycleState: string; plannedWindows: number;
}
/** A window somebody put in the plan. Not a request — nothing counts it as work in progress. */
interface PlanWindow {
  id: string; targetAssetId: string; targetName: string; targetTypeCode: string;
  startsOn: string; endsOn: string; note: string | null;
  state: "PLANNED" | "CONVERTED" | "CANCELLED";
  requestId: string | null; requestCode: string | null;
}
interface Payload {
  rows: PlanRow[];
  bars: GanttBar[];
  load: { label: string; due: number; started: number; closed: number }[];
  projects: PlanProject[];
  windows: PlanWindow[];
  teams: { id: string; name: string; requests: number }[];
  assessors: { id: string; name: string; requests: number }[];
  unassignedRequests: number;
  fullReviewTriggerId: string | null;
  mayManagePolicy: boolean;
  maySchedule: boolean;
  mayPlan: boolean;
}

/**
 * The cadence states, in the order somebody planning cares about them.
 *
 * The badge tone is paired with the state name in text, never used alone: overdue and due-soon are
 * red and amber, which is the one pair a reader with the commonest colour-vision deficiency cannot
 * separate — and they are the two states this page exists to distinguish.
 */
const STATUS: Record<string, { label: string; tone: "critical" | "unknown" | "high" | "ok" | "neutral" }> = {
  OVERDUE: { label: "Overdue", tone: "critical" },
  NEVER: { label: "Never reviewed", tone: "unknown" },
  DUE_SOON: { label: "Due soon", tone: "high" },
  CURRENT: { label: "Current", tone: "ok" },
  // Not the same as current, and collapsing the two would be the worst error this page could make:
  // an application whose criticality tier has no review interval configured is not on schedule, it
  // has no schedule. It reads as compliant only because nothing was ever required of it.
  NO_OBLIGATION: { label: "No review interval set", tone: "neutral" },
};
const UNKNOWN_STATUS = { label: "Never reviewed", tone: "unknown" } as const;

const FILTERS = ["ALL", "OVERDUE", "DUE_SOON", "NEVER", "NO_OBLIGATION", "CURRENT"] as const;

/** The assessor option that is not a person: requests with no lead at all. */
const UNASSIGNED = "__unassigned__";

/**
 * Reads a multi-select from the query string.
 *
 * `null` — the parameter is absent, so no filter. `[]` — present and empty, so nothing matches. The
 * URL therefore round-trips a cleared picker as a cleared picker rather than as "everything", which is
 * what makes a shared link mean what the sender was looking at.
 */
function readList(params: URLSearchParams, key: string): string[] | null {
  const raw = params.get(key);
  if (raw === null) return null;
  return raw.split(",").filter(Boolean);
}

/**
 * Planning the periodic assessment of the application estate.
 *
 * <h2>What this page is for, and the question it refuses to answer with a guess</h2>
 *
 * Somebody has a fixed number of assessor-weeks and an estate that is larger than it. The question
 * is which applications must be assessed in the next two quarters and which can wait — and it is a
 * question about *coverage over time*, which is why the centrepiece is a Gantt and not a table. A
 * table sorts by "most overdue" and answers "what is worst"; it cannot show that four of the six
 * overdue applications all fall in the same fortnight, which is the fact that changes the plan.
 *
 * <h2>Never assessed is the row that must not be quiet</h2>
 *
 * An application with no assessment on record has nothing to draw, and an empty Gantt row reads as
 * a period in which nothing needed doing. It is marked in text instead. Product principle 1 —
 * measured-and-clear must be distinguishable from never-measured, and on a timeline the default
 * rendering of "never measured" is silence.
 *
 * <h2>Two chart failures this page is built to avoid</h2>
 *
 * The monthly load chart is GROUPED, not stacked: due, started and closed count overlapping
 * populations of the same requests, and stacking them would draw one request three times as a total
 * of three. And the projected bars are hatched rather than merely a different colour, so the
 * distinction between committed work and implied work survives greyscale and colour-vision
 * deficiency.
 */
export function PlanningPage() {
  const [params, setParams] = useSearchParams();
  const [data, setData] = useState<Payload | null>(null);
  const [failed, setFailed] = useState(false);
  const [filter, setFilter] = useState<(typeof FILTERS)[number]>("ALL");
  const [selected, setSelected] = useState<string | null>(null);
  const [reloads, setReloads] = useState(0);
  const [nodes, setNodes] = useState<{ id: string; name: string }[]>([]);
  const [query, setQuery] = useState("");
  /**
   * Which kinds of work the timeline draws.
   *
   * Filters the BARS, never the rows. Hiding an application because it has no full review would erase
   * the most important thing this page reports — nine of eleven have never had one — so a row with
   * nothing left to draw keeps its "no assessment on record" marker instead of disappearing.
   */
  const [kinds, setKinds] = useState<"all" | "review">("all");
  const orgs = readList(params, "org");
  const teamIds = readList(params, "team");
  const assessorIds = readList(params, "assessor");
  const unassigned = params.get("unassigned") === "true";
  const filtered = orgs !== null || teamIds !== null || assessorIds !== null || unassigned
    || query.trim() !== "";

  /**
   * All four filters live in the URL, and all four go to the SERVER except the search.
   *
   * In the URL because a filtered plan is the thing somebody sends to a colleague — "look at what
   * Payments is owed" is a link, not a description of which dropdowns to set. On the server because
   * SEC-AUZ-016 wants the scope predicate in retrieval: the coverage bars and the monthly load have to
   * be counts of the filtered population, not a subset of a wider answer already computed.
   */
  function setList(key: string, next: string[] | null) {
    const search = new URLSearchParams(params);
    if (next === null) search.delete(key);
    else search.set(key, next.join(","));
    setParams(search, { replace: true });
  }

  // The organization picker's options. Same source the other dashboards use, so the list of places a
  // person may filter by cannot differ between two screens showing the same estate.
  useEffect(() => {
    api.get<{ nodes: { id: string; name: string }[] }>("/api/ui/applications")
      .then((d) => setNodes(d.nodes ?? [])).catch(() => setNodes([]));
  }, []);

  useEffect(() => {
    setFailed(false);
    api.get<Payload>(`/api/ui/assessment-plan?${params.toString()}`)
      .then(setData)
      .catch(() => setFailed(true));
    // Keyed on the whole query string: every filter is a server filter, so any change to it is a
    // refetch. Listing them individually is how one gets forgotten when a fourth is added.
  }, [params.toString(), reloads]);

  /**
   * Search is applied in the browser; the organization filter is applied in the query.
   *
   * That is not an inconsistency. The organization filter is authorization-shaped: SEC-AUZ-016
   * requires the scope predicate to be part of RETRIEVAL, so that the counts and the coverage figures
   * are counts of what the caller may see under that scope rather than a subset of a wider answer
   * that was already computed. Filtering it here would mean the page had fetched the wider estate to
   * display less of it.
   *
   * Search is the opposite kind of thing — a find-in-list over rows already fetched and already
   * authorized. Sending it to the server would add a round trip per keystroke to narrow a list of
   * applications that is measured in tens.
   */
  const searched = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return data?.rows ?? [];
    // A project name matches its application. Somebody looking for "Settlement" is looking for
    // whatever contains it, and a search that only reads the application name sends them away
    // convinced the estate does not hold it.
    const byProject = new Set(
      (data?.projects ?? [])
        .filter((p) => p.name.toLowerCase().includes(needle))
        .map((p) => p.assetId));
    return (data?.rows ?? []).filter((r) =>
      r.name.toLowerCase().includes(needle)
      || (r.orgPath ?? "").toLowerCase().includes(needle)
      || byProject.has(r.assetId));
  }, [data, query]);

  const rows = useMemo(
    () => searched.filter((r) => filter === "ALL" || r.status === filter),
    [searched, filter],
  );
  const paging = usePaging(rows);

  // Full-review requests visible on the timeline, per application. The cadence view only counts a
  // review once an assessment EXECUTION record links it to the asset, so an application can carry
  // full-review requests and still read "never reviewed". That is not a contradiction to hide: it is
  // either an unrecorded execution or a review that was raised and never run, and both are things
  // the person planning needs to see rather than a status they should quietly trust.
  const fullReviewBars = useMemo(() => {
    const tally: Record<string, number> = {};
    for (const bar of data?.bars ?? []) {
      if (bar.kind === "FULL_REVIEW") tally[bar.assetId] = (tally[bar.assetId] ?? 0) + 1;
    }
    return tally;
  }, [data]);

  const projectsByApp = useMemo(() => {
    const map: Record<string, PlanProject[]> = {};
    for (const p of data?.projects ?? []) {
      (map[p.assetId] ??= []).push(p);
    }
    return map;
  }, [data]);

  /**
   * The planned windows drawn as Gantt bars, on the lane of the application they belong to.
   *
   * <p>A window on a PROJECT is drawn on its application's lane, because the chart has one lane per
   * application and a planner who scheduled the parts needs to see them against the whole. The bar
   * says which project it is for, so the lane is not lying about the granularity.
   *
   * <p>Cancelled windows are dropped here and only here: the plan still holds them — that is
   * PRD-ASM-017 — but a cancelled fortnight drawn on the timeline is a commitment that is not one.
   */
  const plannedBars = useMemo<GanttBar[]>(() => {
    const appOfProject: Record<string, string> = {};
    for (const p of data?.projects ?? []) appOfProject[p.projectId] = p.assetId;
    return (data?.windows ?? [])
      .filter((w) => w.state === "PLANNED")
      .map((w) => ({
        assetId: appOfProject[w.targetAssetId] ?? w.targetAssetId,
        requestId: null,
        code: null,
        label: w.targetTypeCode === "PROJECT"
          ? `Planned — ${w.targetName}` : "Planned full review",
        kind: "PLANNED" as const,
        startAt: w.startsOn,
        endAt: w.endsOn,
        state: "PLANNED",
        open: false,
        overdue: false,
        fullReview: true,
      }));
  }, [data]);

  /** Request bars and plan bars in one array, so the chart draws both against one axis. */
  const allBars = useMemo(
    () => [...(data?.bars ?? []), ...plannedBars], [data, plannedBars]);

  /**
   * Which applications are ticked for a bulk plan.
   *
   * <p>Held against the SEARCHED set rather than the whole payload, and cleared when the filter
   * changes: a tick somebody made on a row they can no longer see would put a target into a plan they
   * did not review. The count on the button is therefore always a count of visible rows.
   */
  const [ticked, setTicked] = useState<Set<string>>(new Set());
  const visibleTicked = useMemo(
    () => rows.filter((r) => ticked.has(r.assetId)), [rows, ticked]);

  /** Which application rows are expanded to show their projects. */
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  function toggleTick(assetId: string) {
    setTicked((current) => {
      const next = new Set(current);
      if (!next.delete(assetId)) next.add(assetId);
      return next;
    });
  }

  function toggleExpand(assetId: string) {
    setExpanded((current) => {
      const next = new Set(current);
      if (!next.delete(assetId)) next.add(assetId);
      return next;
    });
  }

  /** Windows for one target, newest plan first. */
  const windowsByTarget = useMemo(() => {
    const map: Record<string, PlanWindow[]> = {};
    for (const w of data?.windows ?? []) (map[w.targetAssetId] ??= []).push(w);
    return map;
  }, [data]);

  async function cancelWindow(id: string) {
    await api.patch(`/api/ui/assessment-plan/windows/${id}`, { state: "CANCELLED" });
    setReloads((n) => n + 1);
  }

  /**
   * Where "schedule this review" goes.
   *
   * A request is scoped to a project, not to an application, so this cannot be a one-click create
   * without choosing a scope on the user's behalf — product principle 4 forbids exactly that. It
   * pre-fills the intake form instead: the full-review trigger, a due date one interval out, and the
   * project where the application has only one. Where it has several, the project field is left for
   * the person to choose.
   */
  function scheduleHref(row: PlanRow): string {
    const search = new URLSearchParams();
    const projects = projectsByApp[row.assetId] ?? [];
    if (projects.length === 1) search.set("project", projects[0]!.projectId);
    if (data?.fullReviewTriggerId) search.set("trigger", data.fullReviewTriggerId);
    // The due date it is already owed, where one exists and has not passed; otherwise a month out,
    // which is a starting point the form lets them change rather than a commitment made for them.
    const due = row.nextDueAt && row.nextDueAt > new Date().toISOString().slice(0, 10)
      ? row.nextDueAt
      : new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10);
    search.set("due", due);
    search.set("title", `Periodic full review — ${row.name}`);
    return `/requests/new?${search.toString()}`;
  }

  // Counted over the SEARCHED set, not the whole payload. A chip reading "Never reviewed 9" beside a
  // table showing two rows describes a population the reader cannot see, and there is no way to tell
  // from the screen which of the two numbers is the one that matters.
  const counts = useMemo(() => {
    const tally: Record<string, number> = {};
    for (const row of searched) tally[row.status] = (tally[row.status] ?? 0) + 1;
    return tally;
  }, [searched]);

  const total = searched.length;
  const coverage: Slice[] = Object.entries(STATUS).map(([key, meta]) => ({
    key,
    label: meta.label,
    value: counts[key] ?? 0,
    // The denominator is the estate, so "3 overdue" is read against how many applications there
    // are. A bare count answers a different question from the one somebody planning is asking.
    population: total,
  }));

  // Where the unassessed risk actually sits. Counting applications treats a payments platform and
  // an internal wiki as one each; weighting by open critical-and-high findings says which of the
  // overdue ones is the one to schedule first.
  const exposure: Slice[] = useMemo(() => searched
    .filter((r) => r.status === "OVERDUE" || r.status === "NEVER")
    .filter((r) => r.severeOpen > 0)
    .sort((a, b) => b.severeOpen - a.severeOpen)
    .slice(0, 8)
    // No denominator: the figure is a count of severe findings, and there is no total it is a part
    // of that would mean anything here. A population of 0 suppresses the "of N" rather than
    // inventing one.
    .map((r) => ({ key: r.assetId, label: r.name, value: r.severeOpen, population: 0 })), [searched]);

  if (failed) {
    return <p className="p-6 text-sm text-muted-foreground">
      The assessment plan could not be loaded. Reload once the service is available.
    </p>;
  }
  if (!data) return <p className="p-6 text-sm text-muted-foreground">Loading…</p>;

  const ganttRows = rows.map((r) => ({ id: r.assetId, name: r.name, caption: r.orgPath }));
  const barsForRows = new Set(ganttRows.map((r) => r.id));
  const ganttBars = allBars.filter((b) => barsForRows.has(b.assetId))
    // A projection IS a full review — it is what the cadence says is owed — so it survives the
    // filter. Dropping it would leave "full reviews only" showing no future work at all. A PLANNED
    // window is the same argument one step further: it is the review somebody committed to.
    .filter((b) => kinds === "all" || b.fullReview || b.kind === "PROJECTED"
            || b.kind === "PLANNED");

  /**
   * How wide one row is, so a full-width cell spans it.
   *
   * Computed rather than written as a literal, because the count changes with two permissions and a
   * hard-coded colSpan silently under-spans for a reader holding one of them — which shows as a
   * stray empty cell at the end of the expanded row, in their session only.
   */
  const columnCount = 12 + (data.mayPlan ? 1 : 0)
    + (data.mayPlan || data.maySchedule ? 1 : 0);
  // Counted the same way the filter selects, projection included — a number beside a button has to
  // equal what pressing it draws. Counting only kind === "FULL_REVIEW" made the chip read 43 while
  // the filtered chart drew 44, and a reader has no way to tell which of the two is wrong.
  const reviewBarCount = data.bars.filter((b) => barsForRows.has(b.assetId)
    && (b.fullReview || b.kind === "PROJECTED")).length;
  const otherBarCount = data.bars.filter((b) => barsForRows.has(b.assetId)
    && !(b.fullReview || b.kind === "PROJECTED")).length;

  return (
    <div className="flex flex-col gap-4 p-4 md:p-6">
      <header>
        <h1 className="flex items-center gap-2 text-lg font-semibold tracking-tight">
          <CalendarClock className="size-5 text-primary" /> Assessment plan
        </h1>
        <p className="text-sm text-muted-foreground">
          When each application was last reviewed, what is in flight, and what the review interval
          says is owed next.
        </p>
      </header>

      {/* ABOVE the charts, deliberately. Every figure below — the Gantt rows, the coverage bars, the
          monthly load — is computed under these filters. A filter placed beside the table would leave
          the charts describing a wider population than the rows underneath them, which is the kind of
          disagreement a reader cannot diagnose from the screen. */}
      <Card>
        <CardContent className="flex flex-wrap items-end gap-3">
          {/* Multi-select, all three. A conglomerate's estate is not read one operating company at a
              time — "what do Payments and Insurance owe between them" is the ordinary question, and a
              single-select forces two page loads and a mental addition to answer it. */}
          <MultiSelect label="Organization" width="w-56"
                       options={nodes.map((n) => ({ id: n.id, name: n.name }))}
                       value={orgs} onChange={(v) => setList("org", v)}
                       placeholder="Everything you can reach" />

          <MultiSelect label="Assessor team" width="w-52"
                       options={(data?.teams ?? []).map((t) => ({
                         id: t.id, name: t.name,
                         hint: `${t.requests} assessment${t.requests === 1 ? "" : "s"} led` }))}
                       value={teamIds} onChange={(v) => setList("team", v)}
                       placeholder="Any team" />

          {/* "Unassigned" is pinned above the people, because a request nobody is leading is a
              planning problem and it is unreachable through a list of names. */}
          <MultiSelect label="Assessor" width="w-52"
                       options={(data?.assessors ?? []).map((a) => ({
                         id: a.id, name: a.name,
                         hint: `${a.requests} led` }))}
                       extra={[{ id: UNASSIGNED, name: "Unassigned",
                                 hint: `${data?.unassignedRequests ?? 0} with no lead` }]}
                       value={unassigned
                         ? [...(assessorIds ?? []), UNASSIGNED]
                         : assessorIds}
                       onChange={(v) => {
                         const search = new URLSearchParams(params);
                         if (v === null) {
                           search.delete("assessor");
                           search.delete("unassigned");
                         } else {
                           const people = v.filter((x) => x !== UNASSIGNED);
                           // Sent as its own parameter rather than a magic value in the list, so the
                           // server never has to recognise a sentinel that means "IS NULL".
                           if (v.includes(UNASSIGNED)) search.set("unassigned", "true");
                           else search.delete("unassigned");
                           if (people.length > 0 || !v.includes(UNASSIGNED)) {
                             search.set("assessor", people.join(","));
                           } else {
                             search.delete("assessor");
                           }
                         }
                         setParams(search, { replace: true });
                       }}
                       placeholder="Anyone" />

          <div className="flex w-64 flex-col gap-1">
            <Label htmlFor="plan-search">Find an application</Label>
            {/* Applied in the BROWSER: a find-in-list over rows already fetched and authorized. A
                round trip per keystroke to narrow a list measured in tens is a round trip wasted. */}
            <div className="relative">
              <Search className="pointer-events-none absolute left-2 top-1/2 size-3.5
                                 -translate-y-1/2 text-muted-foreground" />
              <Input id="plan-search" value={query} className="pl-7 pr-7"
                     placeholder="name or organization"
                     onChange={(e) => setQuery(e.target.value)} />
              {query && (
                <button type="button" onClick={() => setQuery("")}
                        aria-label="Clear the search"
                        className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground
                                   hover:text-foreground">
                  <X className="size-3.5" />
                </button>
              )}
            </div>
          </div>

          <div className="flex flex-col gap-1">
            <Label>Timeline shows</Label>
            <div className="flex gap-1.5">
              <Button size="sm" variant={kinds === "all" ? "secondary" : "ghost"}
                      onClick={() => setKinds("all")}>
                All assessments
                <span className="ml-1.5 tabular text-muted-foreground">
                  {reviewBarCount + otherBarCount}
                </span>
              </Button>
              <Button size="sm" variant={kinds === "review" ? "secondary" : "ghost"}
                      onClick={() => setKinds("review")}>
                {/* The swatch, so the button and the bars it isolates say the same thing without the
                    reader having to hold a colour in their head between the two. */}
                <span className="mr-1 inline-block size-2.5 rounded-sm bg-plan-review" />
                Full reviews only
                <span className="ml-1.5 tabular text-muted-foreground">{reviewBarCount}</span>
              </Button>
            </div>
          </div>

          <div className="flex flex-col gap-1">
            <Label>Cadence</Label>
            <div className="flex flex-wrap gap-1.5">
              {FILTERS.map((key) => (
                <Button key={key} size="sm"
                        variant={filter === key ? "secondary" : "ghost"}
                        onClick={() => setFilter(key)}>
                  {key === "ALL" ? "All" : (STATUS[key] ?? UNKNOWN_STATUS).label}
                  <span className="ml-1.5 tabular text-muted-foreground">
                    {key === "ALL" ? searched.length : counts[key] ?? 0}
                  </span>
                </Button>
              ))}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Said plainly when a filter is narrowing things. Without it, a reader who left a filter on
          and came back reads a partial estate as the whole one. */}
      {filtered && (
        <p className="flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
          <span>
            Showing {searched.length} application{searched.length === 1 ? "" : "s"}
            {orgs !== null && ` in ${orgs.length} organization${orgs.length === 1 ? "" : "s"}`}
            {teamIds !== null && ` · ${teamIds.length} team${teamIds.length === 1 ? "" : "s"}`}
            {(assessorIds !== null || unassigned)
              && ` · ${(assessorIds?.length ?? 0) + (unassigned ? 1 : 0)} assessor selection`}
            {query && ` · matching \u201C${query}\u201D`}.
          </span>
          {/* Said whenever a team or assessor filter is on, because it changes what the ROWS mean:
              an application with no work by that person is not in the answer, so "never reviewed"
              can no longer be read off this list as an estate-wide fact. */}
          {(teamIds !== null || assessorIds !== null || unassigned) && (
            <span className="text-tone-warn">
              Rows are limited to applications this selection has work on.
            </span>
          )}
          <button type="button" className="text-primary hover:underline"
                  onClick={() => { setQuery(""); setParams({}, { replace: true }); }}>
            Clear filters
          </button>
        </p>
      )}

      <Card>
        <CardHeader className="pb-2">
          <CardTitle>Assessment timeline</CardTitle>
          <CardDescription>
            Each bar is an assessment request, from when it started to when it closed — or to its due
            date while it is open. Colour says which kind of work it is; solid means finished, hollow
            means still running. <b className="text-sev-critical">Overdue is red and hatched</b> —
            hatched as well as red, because red against the olive of an ordinary assessment is
            indistinguishable to a red-green colour-blind reader, and the texture is what tells them.
            Sparse-hatched, unfilled bars are what the review interval implies is owed next: nobody has
            scheduled them and no assessor time has been allocated to them.
            {kinds === "review" && (
              <span className="mt-1 block text-tone-warn">
                Showing full reviews only — {otherBarCount} other assessment
                {otherBarCount === 1 ? "" : "s"} in this scope are hidden.
              </span>
            )}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Gantt rows={ganttRows}
                 bars={ganttBars}
                 selected={selected}
                 onSelect={(id) => setSelected((current) => (current === id ? null : id))}
                 empty="No application in this filter has an assessment on record." />
        </CardContent>
      </Card>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader className="pb-2">
            <CardTitle>Review coverage</CardTitle>
            <CardDescription>
              Against each application's own review interval. Never assessed is counted separately
              from overdue — one is a schedule that slipped, the other is a schedule that never ran.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <BarList slices={coverage} empty="No applications in scope." />
          </CardContent>
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader className="pb-2">
            <CardTitle>Assessment load by month</CardTitle>
            <CardDescription>
              What falls due against what actually started. A month where nine reviews come due and
              two are started is the month the plan was missed, and neither figure alone says so.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <GroupedColumns
              points={data.load.map((p) => ({
                label: p.label,
                values: { due: p.due, started: p.started, closed: p.closed },
              }))}
              series={[
                { key: "due", label: "Falls due", className: "bg-sev-high" },
                { key: "started", label: "Started", className: "bg-tone-info" },
                { key: "closed", label: "Closed", className: "bg-tone-ok" },
              ]}
              empty="No assessment activity in this window." />
          </CardContent>
        </Card>
      </div>

      {exposure.length > 0 && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle>Overdue, weighted by what is already open</CardTitle>
            <CardDescription>
              Open critical and high findings on applications that are overdue or never assessed.
              Counting applications treats every one as equal; this says which overdue review to
              schedule first.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <BarList slices={exposure} tone="warn"
                     empty="No overdue application carries an open critical or high finding." />
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2 pb-2">
          <div>
            <CardTitle>Applications</CardTitle>
            <CardDescription>
              Ordered by urgency — overdue first, then never assessed, then soonest due.
            </CardDescription>
          </div>
          <span className="flex items-center gap-2">
            {/* Bulk is the whole answer to "the number of applications is large". Planning a year
                one application at a time is the work this button exists to remove; the dialog states
                the multiplication before it commits to it. Disabled rather than hidden when nothing
                is ticked, so the control explains itself before it is needed. */}
            {data.mayPlan && (
              <PlanWindowDialog
                targets={visibleTicked.map<PlanTarget>((r) => ({
                  id: r.assetId, name: r.name, typeCode: "APPLICATION",
                  intervalMonths: r.intervalMonths, plannedWindows: r.plannedWindows,
                }))}
                onSaved={() => { setTicked(new Set()); setReloads((n) => n + 1); }}
                trigger={
                  <Button size="sm" variant="secondary" disabled={visibleTicked.length === 0}
                          title={visibleTicked.length === 0
                            ? "Tick the applications to plan"
                            : `Plan windows for ${visibleTicked.length} applications`}>
                    <CalendarPlus className="size-3.5" />
                    Plan {visibleTicked.length > 0 ? visibleTicked.length : ""} selected
                  </Button>
                } />
            )}
            <PageSize size={paging.size} onChange={paging.setSize} />
          </span>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                {data.mayPlan && (
                  <TableHead className="w-8">
                    {/* Ticks every VISIBLE row, never the whole payload. A control that silently
                        selected rows behind a filter would put targets into a plan nobody read. */}
                    <Checkbox
                      aria-label="Select every application shown"
                      checked={paging.rows.length > 0
                        && paging.rows.every((r) => ticked.has(r.assetId))}
                      onCheckedChange={(v) => setTicked((current) => {
                        const next = new Set(current);
                        for (const r of paging.rows) {
                          if (v === true) next.add(r.assetId); else next.delete(r.assetId);
                        }
                        return next;
                      })} />
                  </TableHead>
                )}
                <TableHead>Application</TableHead>
                {/* The three facts a planner sizes an engagement from, beside the name rather than a
                    click away: the decision they inform is made while scanning the list. */}
                <TableHead>Criticality</TableHead>
                <TableHead>Exposure</TableHead>
                <TableHead className="text-right">APIs</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Last full review</TableHead>
                <TableHead>Interval</TableHead>
                <TableHead>Next due</TableHead>
                <TableHead className="text-right">Planned</TableHead>
                <TableHead className="text-right">In flight</TableHead>
                <TableHead className="text-right">Open requests</TableHead>
                <TableHead className="text-right">Critical + high open</TableHead>
                {(data.mayPlan || data.maySchedule) && <TableHead className="w-40" />}
              </TableRow>
            </TableHeader>
            <TableBody>
              {paging.rows.map((row) => {
                const status = STATUS[row.status] ?? UNKNOWN_STATUS;
                const projects = projectsByApp[row.assetId] ?? [];
                const windows = (windowsByTarget[row.assetId] ?? [])
                  .concat(projects.flatMap((pr) => windowsByTarget[pr.projectId] ?? []))
                  .filter((w) => w.state !== "CANCELLED");
                return (
                  <Fragment key={row.assetId}>
                  <TableRow className={cn(selected === row.assetId && "bg-primary/5")}
                            onMouseEnter={() => setSelected(row.assetId)}>
                    {data.mayPlan && (
                      <TableCell>
                        <Checkbox checked={ticked.has(row.assetId)}
                                  aria-label={`Select ${row.name}`}
                                  onCheckedChange={() => toggleTick(row.assetId)} />
                      </TableCell>
                    )}
                    <TableCell>
                      <span className="flex items-center gap-1">
                        {/* Expand only where there is something behind it. A disclosure control on a
                            row with no projects teaches the reader that the control does nothing. */}
                        {(projectsByApp[row.assetId]?.length ?? 0) > 0 ? (
                          <button type="button" onClick={() => toggleExpand(row.assetId)}
                                  aria-expanded={expanded.has(row.assetId)}
                                  aria-label={`${expanded.has(row.assetId) ? "Hide" : "Show"} the `
                                    + `${projectsByApp[row.assetId]!.length} projects under ${row.name}`}
                                  className="text-muted-foreground hover:text-foreground">
                            {expanded.has(row.assetId)
                              ? <ChevronDown className="size-3.5" />
                              : <ChevronRight className="size-3.5" />}
                          </button>
                        ) : <span className="inline-block size-3.5" />}
                        <Link to={`/applications/${row.assetId}`}
                              className="font-medium hover:text-primary hover:underline">
                          {row.name}
                        </Link>
                      </span>
                      {row.orgPath && (
                        <div className="pl-4.5 text-[11px] text-muted-foreground">{row.orgPath}</div>
                      )}
                    </TableCell>
                    <TableCell>
                      {row.criticality
                        ? <Badge tone="high">{row.criticality}</Badge>
                        : <Badge tone="unknown">not set</Badge>}
                    </TableCell>
                    <TableCell className="text-xs">
                      {row.exposureDeclared
                        ?? <span className="italic text-tone-unknown">not declared</span>}
                    </TableCell>
                    <TableCell className="tabular text-right text-xs">
                      {/* Null and zero are different answers. "not declared" is an application whose
                          API count nobody recorded; a 0 would claim it was counted and came to none,
                          and would sort to the top of the small-jobs end of the list. */}
                      {row.apiCount === null
                        ? <span className="italic text-tone-unknown">—</span>
                        : row.apiCount}
                      {row.projectCount > 1 && row.apiCount !== null && (
                        <span className="pl-1 text-[10px] text-muted-foreground">
                          /{row.projectCount}
                        </span>
                      )}
                    </TableCell>
                    <TableCell>
                      {/* The state is written out. A coloured pill alone would carry the whole
                          meaning in hue, which fails the reader who cannot separate red from
                          amber — the exact two that matter most here. */}
                      {/* NO_OBLIGATION has two distinct causes and pointing at the wrong one sends
                          somebody to edit a tier interval that was never the problem. Either the
                          application carries no criticality at all — so it belongs to no tier and no
                          policy can reach it — or its tier genuinely has no interval set. Both read
                          as "nothing is owed"; only one is fixable in the table below. */}
                      <Badge tone={status.tone}>
                        {row.status === "NO_OBLIGATION" && !row.criticality
                          ? "No criticality set"
                          : status.label}
                      </Badge>
                      {row.status === "NO_OBLIGATION" && !row.criticality && (
                        <div className="mt-0.5 text-[10px] text-tone-warn">
                          No tier, so no review interval can apply. Set its criticality on the
                          application.
                        </div>
                      )}
                      {row.status === "NEVER" && (fullReviewBars[row.assetId] ?? 0) > 0 && (
                        <div className="mt-0.5 text-[10px] text-tone-warn">
                          {fullReviewBars[row.assetId]} full-review request
                          {fullReviewBars[row.assetId] === 1 ? "" : "s"} raised — none recorded as
                          executed
                        </div>
                      )}
                    </TableCell>
                    <TableCell className="tabular">
                      {row.lastReviewAt ?? <span className="italic text-tone-unknown">never</span>}
                    </TableCell>
                    <TableCell className="tabular">
                      {row.intervalMonths ? `${row.intervalMonths} months`
                        : <span className="italic text-tone-unknown">not set</span>}
                    </TableCell>
                    <TableCell className="tabular">
                      {row.nextDueAt ?? <span className="italic text-tone-unknown">—</span>}
                    </TableCell>
                    <TableCell className="tabular text-right">
                      {row.plannedWindows > 0
                        ? <span className="font-medium">{row.plannedWindows}</span>
                        : <span className="text-tone-unknown">none</span>}
                    </TableCell>
                    <TableCell className="tabular text-right">{row.inFlight || "—"}</TableCell>
                    <TableCell className="tabular text-right">{row.openRequests || "—"}</TableCell>
                    <TableCell className="tabular text-right">
                      {row.severeOpen > 0
                        ? <span className="font-medium text-sev-high">{row.severeOpen}</span>
                        : "—"}
                    </TableCell>
                    {(data.mayPlan || data.maySchedule) && (
                      <TableCell>
                        <span className="flex items-center gap-1">
                          {data.mayPlan && (
                            <PlanWindowDialog
                              targets={[{ id: row.assetId, name: row.name, typeCode: "APPLICATION",
                                          intervalMonths: row.intervalMonths,
                                          plannedWindows: row.plannedWindows }]}
                              onSaved={() => setReloads((n) => n + 1)}
                              trigger={
                                <Button size="sm" variant="ghost" title="Plan windows for the year">
                                  <CalendarPlus className="size-3.5" /> Plan
                                </Button>
                              } />
                          )}
                          {/* Still here, and still the intake form. Raising a request is a different
                              act from planning one: it needs a scope, and product principle 4 forbids
                              the platform choosing one on somebody's behalf. */}
                          {data.maySchedule && (
                            <Button asChild size="sm" variant="ghost"
                                    title="Raise an assessment request now">
                              <Link to={scheduleHref(row)}>Request</Link>
                            </Button>
                          )}
                        </span>
                      </TableCell>
                    )}
                  </TableRow>
                  {expanded.has(row.assetId) && (
                    <TableRow className="bg-muted/30 hover:bg-muted/30">
                      <TableCell colSpan={columnCount} className="py-3">
                        <div className="flex flex-col gap-3 pl-6">
                          {/* WHY THE PROJECT BREAKDOWN IS HERE AND NOT A SEPARATE PAGE. The facts an
                              engagement is sized from — the API count, how it is reached — are
                              declared on the PROJECT, because that is the granularity an inventory
                              records them at. At application level the API count is a sum and the
                              access path is a set of them; neither is what a tester is scoped to. */}
                          <div>
                            <div className="text-[11px] font-medium uppercase tracking-wide
                                            text-muted-foreground">
                              {projects.length} project{projects.length === 1 ? "" : "s"} under
                              {" "}{row.name}
                            </div>
                            <table className="mt-1.5 w-full max-w-4xl text-xs">
                              <thead className="text-left text-[10px] uppercase tracking-wide
                                                text-muted-foreground">
                                <tr>
                                  <th className="py-1 pr-4">Project</th>
                                  <th className="py-1 pr-4">How it is reached</th>
                                  <th className="py-1 pr-4 text-right">APIs</th>
                                  <th className="py-1 pr-4 text-right">Planned</th>
                                  <th className="py-1" />
                                </tr>
                              </thead>
                              <tbody>
                                {projects.map((pr) => (
                                  <tr key={pr.projectId} className="border-t border-border/50">
                                    <td className="py-1 pr-4">
                                      <Link to={`/projects/${pr.projectId}`}
                                            className="hover:text-primary hover:underline">
                                        {pr.name}
                                      </Link>
                                    </td>
                                    <td className="py-1 pr-4">
                                      {/* The tenant's own value, never ranked. Whether PUBLIC is more
                                          exposed than ZTNA is a judgement about a vocabulary this
                                          code did not define (ADR-027). */}
                                      {pr.accessPath
                                        ?? <span className="italic text-tone-unknown">not declared</span>}
                                    </td>
                                    <td className="tabular py-1 pr-4 text-right">
                                      {pr.apiCount === null
                                        ? <span className="italic text-tone-unknown">—</span>
                                        : pr.apiCount}
                                    </td>
                                    <td className="tabular py-1 pr-4 text-right">
                                      {pr.plannedWindows || <span className="text-tone-unknown">none</span>}
                                    </td>
                                    <td className="py-1">
                                      {data.mayPlan && (
                                        <PlanWindowDialog
                                          targets={[{ id: pr.projectId, name: pr.name,
                                                      typeCode: "PROJECT",
                                                      intervalMonths: row.intervalMonths,
                                                      plannedWindows: pr.plannedWindows }]}
                                          onSaved={() => setReloads((n) => n + 1)}
                                          trigger={
                                            <Button size="sm" variant="ghost"
                                                    className="h-6 px-1.5"
                                                    title={`Plan windows for ${pr.name}`}>
                                              <CalendarPlus className="size-3" /> Plan
                                            </Button>
                                          } />
                                      )}
                                    </td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </div>

                          {/* The plan itself, so it can be read and undone where it was made. */}
                          <div>
                            <div className="text-[11px] font-medium uppercase tracking-wide
                                            text-muted-foreground">
                              Planned windows
                            </div>
                            {windows.length === 0 ? (
                              <p className="mt-1 text-[11px] text-muted-foreground">
                                Nothing planned for this application or its projects. That is not the
                                same as nothing being owed — the status column says what is owed.
                              </p>
                            ) : (
                              <ul className="mt-1 flex flex-col gap-1">
                                {windows.map((w) => (
                                  <li key={w.id} className="flex items-center gap-2 text-xs">
                                    <span className="tabular">{w.startsOn} → {w.endsOn}</span>
                                    {w.targetTypeCode === "PROJECT" && (
                                      <Badge tone="neutral">{w.targetName}</Badge>
                                    )}
                                    {w.state === "CONVERTED" && w.requestCode && (
                                      <Link to={`/requests/${w.requestId}`}
                                            className="text-primary hover:underline">
                                        {w.requestCode}
                                      </Link>
                                    )}
                                    {w.note && (
                                      <span className="text-muted-foreground">{w.note}</span>
                                    )}
                                    {data.mayPlan && w.state === "PLANNED" && (
                                      <Button size="sm" variant="ghost" className="h-5 px-1"
                                              aria-label={`Cancel the window starting ${w.startsOn}`}
                                              onClick={() => cancelWindow(w.id)}>
                                        <Trash2 className="size-3" />
                                      </Button>
                                    )}
                                  </li>
                                ))}
                              </ul>
                            )}
                          </div>
                        </div>
                      </TableCell>
                    </TableRow>
                  )}
                  </Fragment>
                );
              })}
              {paging.rows.length === 0 && (
                <TableRow>
                  <TableCell colSpan={columnCount}
                             className="text-center text-sm text-muted-foreground">
                    No application matches this filter.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
          <Pager paging={paging} unit="applications" />
        </CardContent>
      </Card>

      {/* Last, deliberately. The plan is the thing somebody came here to read; the interval is what
          it is computed from, and putting configuration first would make a reader work through a
          settings form before seeing whether anything was wrong. Changing it refetches the plan. */}
      <ReviewPolicy onChanged={() => setReloads((n) => n + 1)} />
    </div>
  );
}
