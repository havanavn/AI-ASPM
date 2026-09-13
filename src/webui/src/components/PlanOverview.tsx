import { useMemo, useState } from "react";
import { Building2, CalendarRange, UserRound, Users } from "lucide-react";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * The plan from the top: one row per business unit, per pentest team, per person, or per month —
 * each with the same coloured status strip, so a reader compares like with like and clicks the row
 * to narrow the whole page to it.
 *
 * <h2>Colour is paired with a number, always</h2>
 *
 * Each strip segment carries its count as text inside or beside it, and the legend names the states.
 * Overdue and due-soon are red and amber — the pair a red-green colour-blind reader cannot separate —
 * so the strip is never the only carrier: the "unplanned due" column says in figures what the reader
 * most needs, and it is the column that turns red.
 *
 * <h2>"Unplanned due" is the planning finding</h2>
 *
 * An application that is overdue, due soon, or never assessed AND has no window in the plan is work
 * nobody has committed to. That count per group is what a head of security asks a planner about, and
 * it is computed here rather than left to be inferred from two other columns.
 */

export interface OverviewRow {
  assetId: string; name: string; status: string; severeOpen: number; nextDueAt: string | null;
  plannedWindows: number; businessUnitId: string | null; businessUnitName: string | null; criticality: string | null;
}
export interface OverviewWindow {
  id: string; targetAssetId: string; startsOn: string; endsOn: string; state: string;
  teamId: string | null; teamName: string | null; assessorId: string | null; assessorName: string | null;
}
export interface OverviewProject { assetId: string; projectId: string }
export interface Roster {
  teams: { id: string; name: string; members: number }[];
  people: { id: string; name: string; teamId: string | null; teamName: string | null }[];
}

export type Dimension = "unit" | "team" | "person" | "month";

interface Group {
  key: string; label: string; sub?: string;
  apps: Set<string>;
  status: Record<string, number>;
  windows: number; unplannedDue: number; severe: number; nextWindow: string | null;
}

const STATUS_ORDER = ["OVERDUE", "NEVER", "DUE_SOON", "CURRENT", "NO_OBLIGATION"] as const;
const STATUS_META: Record<(typeof STATUS_ORDER)[number], { label: string; bar: string; text: string }> = {
  OVERDUE: { label: "Overdue", bar: "bg-sev-critical", text: "text-sev-critical" },
  NEVER: { label: "Never assessed", bar: "bg-tone-unknown", text: "text-tone-unknown" },
  DUE_SOON: { label: "Due soon", bar: "bg-sev-high", text: "text-sev-high" },
  CURRENT: { label: "Current", bar: "bg-tone-ok", text: "text-tone-ok" },
  NO_OBLIGATION: { label: "No interval", bar: "bg-muted-foreground/30", text: "text-muted-foreground" },
};
const DUE = new Set(["OVERDUE", "NEVER", "DUE_SOON"]);

function emptyGroup(key: string, label: string, sub?: string): Group {
  return { key, label, sub, apps: new Set(), status: {}, windows: 0, unplannedDue: 0, severe: 0, nextWindow: null };
}

function addApp(g: Group, row: OverviewRow, planned: boolean) {
  if (g.apps.has(row.assetId)) return;
  g.apps.add(row.assetId);
  g.status[row.status] = (g.status[row.status] ?? 0) + 1;
  g.severe += row.severeOpen;
  if (DUE.has(row.status) && !planned) g.unplannedDue += 1;
}

function addWindow(g: Group, w: OverviewWindow) {
  g.windows += 1;
  if (!g.nextWindow || w.startsOn < g.nextWindow) g.nextWindow = w.startsOn;
}

export function PlanOverview({ rows, windows, projects, roster, onDrill, active }: {
  rows: OverviewRow[]; windows: OverviewWindow[]; projects: OverviewProject[]; roster: Roster;
  /** Narrow the page to one group: an organization node, a team, a person, or a month. */
  onDrill: (dimension: Dimension, id: string | null, label: string) => void;
  active?: { dimension: Dimension; id: string | null } | null;
}) {
  const [dimension, setDimension] = useState<Dimension>("unit");
  const today = new Date().toISOString().slice(0, 10);

  const groups = useMemo(() => {
    const appOfTarget: Record<string, string> = {};
    for (const p of projects) appOfTarget[p.projectId] = p.assetId;
    const rowById: Record<string, OverviewRow> = {};
    for (const r of rows) rowById[r.assetId] = r;
    const planned = windows.filter((w) => w.state === "PLANNED" && w.endsOn >= today);
    const plannedApps = new Set(planned.map((w) => appOfTarget[w.targetAssetId] ?? w.targetAssetId));
    const out = new Map<string, Group>();

    if (dimension === "unit") {
      for (const r of rows) {
        const key = r.businessUnitId ?? "__none";
        const g = out.get(key) ?? emptyGroup(key, r.businessUnitName ?? "No organization");
        addApp(g, r, plannedApps.has(r.assetId));
        out.set(key, g);
      }
      for (const w of planned) {
        const app = rowById[appOfTarget[w.targetAssetId] ?? w.targetAssetId];
        if (!app) continue;
        const g = out.get(app.businessUnitId ?? "__none");
        if (g) addWindow(g, w);
      }
    } else if (dimension === "team" || dimension === "person") {
      const nameOf = (w: OverviewWindow) => dimension === "team" ? [w.teamId, w.teamName] : [w.assessorId, w.assessorName];
      for (const w of planned) {
        const [id, name] = nameOf(w);
        const key = id ?? "__unassigned";
        const label = name ?? (dimension === "team" ? "No team named" : "Nobody named");
        const sub = dimension === "person" ? roster.people.find((p) => p.id === id)?.teamName ?? undefined : undefined;
        const g = out.get(key) ?? emptyGroup(key, label, sub);
        addWindow(g, w);
        const app = rowById[appOfTarget[w.targetAssetId] ?? w.targetAssetId];
        if (app) addApp(g, app, true);
        out.set(key, g);
      }
      // Teams and people on the roster with nothing planned are listed too: an empty row is the
      // capacity nobody scheduled, and it disappears if only planned owners are shown.
      const rosterRows = dimension === "team"
        ? roster.teams.map((t) => ({ id: t.id, label: t.name, sub: `${t.members} member${t.members === 1 ? "" : "s"}` }))
        : roster.people.filter((p) => p.teamId).map((p) => ({ id: p.id, label: p.name, sub: p.teamName ?? undefined }));
      for (const r of rosterRows) {
        if (!out.has(r.id)) out.set(r.id, emptyGroup(r.id, r.label, r.sub));
        else if (r.sub && !out.get(r.id)!.sub) out.get(r.id)!.sub = r.sub;
      }
      // Due work nobody has planned is its own row — the largest number on the screen when a plan is young.
      const orphan = emptyGroup("__unplanned", "Due, not in anybody's plan");
      for (const r of rows) {
        if (DUE.has(r.status) && !plannedApps.has(r.assetId)) addApp(orphan, r, false);
      }
      if (orphan.apps.size > 0) out.set(orphan.key, orphan);
    } else {
      // By month, twelve months from this one: windows starting in the month, and reviews falling due.
      const start = new Date(); start.setUTCDate(1);
      for (let i = 0; i < 12; i++) {
        const d = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + i, 1));
        const key = d.toISOString().slice(0, 7);
        out.set(key, emptyGroup(key, d.toLocaleString("en", { month: "short", year: "numeric", timeZone: "UTC" })));
      }
      for (const w of planned) {
        const g = out.get(w.startsOn.slice(0, 7));
        if (!g) continue;
        addWindow(g, w);
        const app = rowById[appOfTarget[w.targetAssetId] ?? w.targetAssetId];
        if (app) addApp(g, app, true);
      }
      for (const r of rows) {
        if (!r.nextDueAt) continue;
        const g = out.get(r.nextDueAt.slice(0, 7));
        if (g && !g.apps.has(r.assetId)) addApp(g, r, plannedApps.has(r.assetId));
      }
    }
    const list = [...out.values()];
    if (dimension !== "month") {
      list.sort((a, b) => (b.unplannedDue - a.unplannedDue) || ((b.status.OVERDUE ?? 0) - (a.status.OVERDUE ?? 0)) || (b.apps.size - a.apps.size)
        || a.label.localeCompare(b.label));
    }
    return list;
  }, [rows, windows, projects, roster, dimension, today]);

  const totals = useMemo(() => {
    const t = { apps: 0, windows: 0, unplannedDue: 0, severe: 0 };
    for (const g of groups) { t.apps += g.apps.size; t.windows += g.windows; t.unplannedDue += g.unplannedDue; t.severe += g.severe; }
    return t;
  }, [groups]);

  const tabs: { key: Dimension; label: string; icon: React.ReactNode }[] = [
    { key: "unit", label: "Business unit", icon: <Building2 className="size-3.5" /> },
    { key: "team", label: "Pentest team", icon: <Users className="size-3.5" /> },
    { key: "person", label: "Assessor", icon: <UserRound className="size-3.5" /> },
    { key: "month", label: "Month", icon: <CalendarRange className="size-3.5" /> },
  ];

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <CardTitle>Plan overview</CardTitle>
            <CardDescription>
              The same strip per group — who owes what, who is planned for what — and a click narrows everything below to that group.
              <span className="ml-1 font-medium text-sev-critical">Unplanned due</span> is work nobody has committed to yet.
            </CardDescription>
          </div>
          <div className="flex gap-1">
            {tabs.map((t) => (
              <Button key={t.key} size="sm" variant={dimension === t.key ? "secondary" : "ghost"} onClick={() => setDimension(t.key)}>
                {t.icon} {t.label}
              </Button>
            ))}
          </div>
        </div>
        <div className="mt-1 flex flex-wrap gap-3 text-[11px] text-muted-foreground">
          {STATUS_ORDER.map((s) => (
            <span key={s} className="inline-flex items-center gap-1">
              <span className={cn("inline-block size-2.5 rounded-sm", STATUS_META[s].bar)} /> {STATUS_META[s].label}
            </span>
          ))}
        </div>
      </CardHeader>
      <CardContent className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead className="text-left text-[10px] uppercase tracking-wide text-muted-foreground">
            <tr>
              <th className="py-1.5 pr-3">{dimension === "month" ? "Month" : dimension === "unit" ? "Business unit" : dimension === "team" ? "Team" : "Assessor"}</th>
              <th className="py-1.5 pr-3 text-right">Apps</th>
              <th className="py-1.5 pr-3 w-[38%]">Review status</th>
              <th className="py-1.5 pr-3 text-right">Windows planned</th>
              <th className="py-1.5 pr-3 text-right">Unplanned due</th>
              <th className="py-1.5 pr-3 text-right">Crit + high open</th>
              <th className="py-1.5 pr-3">Next window</th>
            </tr>
          </thead>
          <tbody>
            {groups.map((g) => {
              const total = g.apps.size;
              const isActive = active && active.dimension === dimension && (active.id ?? "__unassigned") === g.key;
              const drillable = !g.key.startsWith("__") || g.key === "__unplanned";
              return (
                <tr key={g.key}
                    className={cn("border-t border-border/60 transition-colors", drillable && "cursor-pointer hover:bg-muted/50", isActive && "bg-primary/5")}
                    onClick={() => drillable && onDrill(dimension, g.key === "__unplanned" ? "__unplanned" : g.key, g.label)}
                    title={drillable ? `Show only ${g.label}` : undefined}>
                  <td className="py-1.5 pr-3">
                    <div className="font-medium">{g.label}</div>
                    {g.sub && <div className="text-[10px] text-muted-foreground">{g.sub}</div>}
                  </td>
                  <td className="tabular py-1.5 pr-3 text-right">{total || <span className="text-tone-unknown">—</span>}</td>
                  <td className="py-1.5 pr-3">
                    {total === 0 ? <span className="text-[10px] italic text-tone-unknown">no application</span> : (
                      <div className="flex h-4 w-full overflow-hidden rounded-sm bg-muted">
                        {STATUS_ORDER.filter((s) => (g.status[s] ?? 0) > 0).map((s) => {
                          const n = g.status[s] ?? 0;
                          const pct = (100 * n) / total;
                          return (
                            <div key={s} className={cn("flex items-center justify-center text-[10px] font-medium text-white", STATUS_META[s].bar)}
                                 style={{ inlineSize: `${pct}%` }} title={`${STATUS_META[s].label}: ${n} of ${total}`}>
                              {pct >= 12 ? n : ""}
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </td>
                  <td className="tabular py-1.5 pr-3 text-right">{g.windows || <span className="text-tone-unknown">—</span>}</td>
                  <td className={cn("tabular py-1.5 pr-3 text-right font-semibold", g.unplannedDue > 0 ? "text-sev-critical" : "text-tone-ok")}>
                    {g.unplannedDue}
                  </td>
                  <td className="tabular py-1.5 pr-3 text-right">{g.severe > 0 ? <span className="font-medium text-sev-high">{g.severe}</span> : "—"}</td>
                  <td className="tabular py-1.5 pr-3 text-muted-foreground">{g.nextWindow ?? "—"}</td>
                </tr>
              );
            })}
            {groups.length > 0 && (
              <tr className="border-t-2 border-border font-medium">
                <td className="py-1.5 pr-3">Total</td>
                <td className="tabular py-1.5 pr-3 text-right">{dimension === "month" ? "" : totals.apps}</td>
                <td className="py-1.5 pr-3" />
                <td className="tabular py-1.5 pr-3 text-right">{totals.windows}</td>
                <td className={cn("tabular py-1.5 pr-3 text-right", totals.unplannedDue > 0 ? "text-sev-critical" : "text-tone-ok")}>{dimension === "month" ? "" : totals.unplannedDue}</td>
                <td className="tabular py-1.5 pr-3 text-right">{dimension === "month" ? "" : totals.severe}</td>
                <td />
              </tr>
            )}
          </tbody>
        </table>
        {groups.length === 0 && <p className="py-3 text-xs italic text-tone-unknown">Nothing in scope.</p>}
        {active && (
          <div className="mt-2 text-[11px]">
            <Badge tone="info">narrowed</Badge>{" "}
            <button type="button" className="text-primary hover:underline" onClick={() => onDrill(active.dimension, null, "")}>Show everything again</button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
