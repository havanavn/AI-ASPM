import { useCallback, useEffect, useState } from "react";
import { CalendarCog, Check, Lock } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

interface TierRow {
  tierId: string; code: string; ordinal: number;
  intervalMonths: number | null; warnDaysBefore: number | null;
  applications: number; updatedAt: string | null;
}

/**
 * The review interval, per criticality tier — the setting the whole assessment plan is derived from.
 *
 * <h2>This is where a periodic review plan is actually established</h2>
 *
 * Nothing else on the planning page is configuration. The Gantt shows work that exists and the
 * coverage chart counts it; both are consequences of the two numbers set here. An application's next
 * review is its last review plus this interval, so a tier with no interval produces no due date,
 * no overdue state, and no bar on the planning half of the chart.
 *
 * <h2>Why the application count is on the row</h2>
 *
 * Changing an interval from twelve months to twenty-four is a different decision when it governs
 * two applications than when it governs two hundred, and the person making it should not have to go
 * and find out which one they are making. The count is the blast radius, shown where the change is.
 *
 * <h2>Clearing an interval is a decision, not an empty field</h2>
 *
 * A tier with no interval reports "no obligation", which reads like compliance and is not — nothing
 * was ever required of it. So clearing is its own labelled action rather than something that happens
 * when a box is left blank, and the resulting state is spelled out on the row rather than shown as a
 * dash. Product principle 1, at the point where the gap can be corrected.
 */
export function ReviewPolicy({ onChanged }: { onChanged?: () => void }) {
  const [rows, setRows] = useState<TierRow[] | null>(null);
  const [mayManage, setMayManage] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [months, setMonths] = useState("");
  const [warn, setWarn] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<{ rows: TierRow[]; mayManage: boolean }>("/api/ui/review-policy")
      .then((d) => { setRows(d.rows); setMayManage(d.mayManage); })
      .catch(() => setRows([]));
  }, []);
  useEffect(load, [load]);

  function begin(row: TierRow) {
    setEditing(row.tierId);
    setMonths(row.intervalMonths === null ? "" : String(row.intervalMonths));
    setWarn(row.warnDaysBefore === null ? "30" : String(row.warnDaysBefore));
    setError(null);
  }

  async function save(tierId: string, clear: boolean) {
    setBusy(true);
    setError(null);
    try {
      await api.put(`/api/ui/review-policy/${tierId}`, {
        // Always sent, both of them. A dropped field must not be able to clear a tenant's policy by
        // accident — clearing is what the explicit `clear` path is for.
        intervalMonths: clear ? null : Number(months),
        warnDaysBefore: clear ? null : Number(warn),
      });
      setEditing(null);
      setSaved(tierId);
      window.setTimeout(() => setSaved(null), 2500);
      load();
      // The plan above is derived from what just changed, so it is refetched rather than left
      // showing due dates computed from the previous interval.
      onChanged?.();
    } catch (e) {
      // 401/403 never reaches here — the api layer intercepts those and sends the caller to
      // step-up or sign-in, because this operation is class E and a stale second factor is the
      // expected refusal, not an error to render inline.
      setError(e instanceof ApiError ? e.message : "The change could not be saved.");
    } finally {
      setBusy(false);
    }
  }

  if (!rows) return null;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2">
          <CalendarCog className="size-4 text-primary" /> Review interval by criticality
        </CardTitle>
        <CardDescription>
          How long an application at each criticality may go between full reviews. Every due date and
          every overdue state on this page is computed from these two numbers — nothing here
          schedules work, it defines what counts as late.
          {!mayManage && (
            <span className="mt-1 flex items-center gap-1.5 text-tone-unknown">
              <Lock className="size-3" /> You can see the intervals but not change them.
            </span>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {error && <p className="mb-2 text-xs text-sev-critical">{error}</p>}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Criticality</TableHead>
              <TableHead>Review every</TableHead>
              <TableHead>Warn before</TableHead>
              <TableHead className="text-right">Applications</TableHead>
              <TableHead>Last changed</TableHead>
              {mayManage && <TableHead className="w-56" />}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row) => {
              const open = editing === row.tierId;
              return (
                <TableRow key={row.tierId}>
                  <TableCell className="font-medium">{row.code}</TableCell>
                  <TableCell colSpan={open ? 2 : 1}>
                    {open ? (
                      <div className="flex flex-wrap items-end gap-2">
                        <div className="flex flex-col gap-1">
                          <Label className="text-[10px]" htmlFor={`m-${row.tierId}`}>Months</Label>
                          <Input id={`m-${row.tierId}`} type="number" min={1} max={120}
                                 className="h-8 w-24" value={months}
                                 onChange={(e) => setMonths(e.target.value)} />
                        </div>
                        <div className="flex flex-col gap-1">
                          <Label className="text-[10px]" htmlFor={`w-${row.tierId}`}>
                            Warn (days)
                          </Label>
                          <Input id={`w-${row.tierId}`} type="number" min={0}
                                 className="h-8 w-24" value={warn}
                                 onChange={(e) => setWarn(e.target.value)} />
                        </div>
                      </div>
                    ) : row.intervalMonths === null ? (
                      <Badge tone="unknown">No interval set</Badge>
                    ) : (
                      <span className="tabular">{row.intervalMonths} months</span>
                    )}
                  </TableCell>
                  {!open && (
                    <TableCell className="tabular">
                      {row.warnDaysBefore === null ? "—" : `${row.warnDaysBefore} days`}
                    </TableCell>
                  )}
                  <TableCell className="tabular text-right">{row.applications}</TableCell>
                  <TableCell className="tabular text-xs text-muted-foreground">
                    {row.updatedAt ?? "—"}
                  </TableCell>
                  {mayManage && (
                    <TableCell>
                      {open ? (
                        <div className="flex flex-wrap gap-1.5">
                          <Button size="sm" disabled={busy || !months}
                                  onClick={() => save(row.tierId, false)}>Save</Button>
                          {row.intervalMonths !== null && (
                            <Button size="sm" variant="ghost" disabled={busy}
                                    onClick={() => save(row.tierId, true)}>
                              Clear obligation
                            </Button>
                          )}
                          <Button size="sm" variant="ghost" disabled={busy}
                                  onClick={() => setEditing(null)}>Cancel</Button>
                        </div>
                      ) : (
                        <div className="flex items-center gap-2">
                          <Button size="sm" variant="secondary" onClick={() => begin(row)}>
                            {row.intervalMonths === null ? "Set interval" : "Change"}
                          </Button>
                          {saved === row.tierId && (
                            <span className="flex items-center gap-1 text-[11px] text-tone-ok">
                              <Check className="size-3" /> saved
                            </span>
                          )}
                        </div>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
        <p className="mt-2 text-[11px] text-muted-foreground">
          A tier with no interval is not compliant — it is unmeasured. Its applications report “no
          review interval set” rather than a due date, and they are excluded from the overdue count
          because nothing was ever required of them.
        </p>
      </CardContent>
    </Card>
  );
}
