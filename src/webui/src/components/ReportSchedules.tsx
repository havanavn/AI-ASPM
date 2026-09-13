import { useCallback, useEffect, useState } from "react";
import { CalendarClock, Download, FileSpreadsheet, Plus, X } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * Reports: scheduled, per recipient; and the audit evidence export, on demand.
 *
 * **Per recipient.** A schedule with three recipients produces three files, each rendered as that
 * person, within that person's scope at that moment (PRD-DSH-043). Nobody receives a file rendered as
 * somebody else. A recipient who loses access is dropped and the owner told (PRD-DSH-045).
 *
 * **Honest by construction.** Every file opens with an About sheet — scope, period, aggregation basis,
 * coverage caveat, generated-content label, row counts — and no template exists that can remove it
 * (PRD-DSH-042). Audit evidence references evidence by identifier and hash; it never embeds a body.
 */

interface Recipient { principalId: string; name: string; droppedAt: string | null; droppedReason: string | null }
interface Schedule {
  id: string; code: string; displayName: string; reportKind: string; scopeNodeId: string | null; scopePath: string | null;
  periodDays: number; cadence: string; runHourUtc: number; runWeekday: number; runDayOfMonth: number; ownerPrincipalId: string;
  ownerName: string; lifecycleState: string; nextRunAt: string | null; lastRunAt: string | null; lastOutcome: string | null;
  recipients: Recipient[]; rowVersion: number;
}
interface Kind { kind: string; label: string; description: string; sheets: string[]; requiredPermission: string }
interface Payload {
  rows: Schedule[]; kinds: Kind[]; mayManage: boolean; mayExportEvidence: boolean; elevated: boolean; storageConfigured: boolean;
  nodes: { id: string; path: string }[]; people: { id: string; name: string }[];
}
interface Artifact {
  id: string; scheduleId: string | null; scheduleName: string | null; reportKind: string; recipientPrincipalId: string; recipientName: string;
  scopePath: string | null; periodFrom: string; periodTo: string; generatedAt: string; status: string; failureDetail: string | null;
  byteSize: number; sha256: string | null; stored: boolean; downloadCount: number;
}

const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

export function ReportSchedules() {
  const [data, setData] = useState<Payload | null>(null);
  const [artifacts, setArtifacts] = useState<Artifact[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [editingRecipients, setEditingRecipients] = useState<Schedule | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.get<Payload>("/api/ui/settings/report-schedules").then(setData).catch((e) => setError(e.message));
    api.get<{ rows: Artifact[] }>("/api/ui/reports/artifacts").then((d) => setArtifacts(d.rows)).catch(() => setArtifacts([]));
  }, []);
  useEffect(load, [load]);

  async function act(path: string, body: unknown) {
    setBusy(true);
    setError(null);
    try {
      await api.post(path, body);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  if (error && !data) return <p className="text-sm text-destructive">{error}</p>;
  if (!data) return <p className="text-sm text-muted-foreground">Loading…</p>;
  const kindLabel = (k: string) => data.kinds.find((x) => x.kind === k)?.label ?? k;

  return (
    <div className="flex flex-col gap-4">
      {!data.storageConfigured && (
        <div className="rounded-md border border-tone-warn/40 bg-tone-warn/10 px-3 py-2 text-sm">
          No object store is configured for this deployment, so scheduled reports cannot be retained and every run will fail and
          say so. On-demand audit evidence still downloads directly.
        </div>
      )}

      <Card className="overflow-hidden">
        <CardHeader className="flex-row items-start justify-between">
          <div>
            <CardTitle className="flex items-center gap-2"><CalendarClock className="size-4" /> Scheduled reports</CardTitle>
            <CardDescription>
              Rendered per recipient, as that person, within that person's scope at run time. Three recipients, three files. A
              recipient who loses access is dropped and you are told.
            </CardDescription>
          </div>
          {data.mayManage && (
            <Button size="sm" onClick={() => setAdding((v) => !v)}>
              {adding ? <><X className="size-4" /> Cancel</> : <><Plus className="size-4" /> Add schedule</>}
            </Button>
          )}
        </CardHeader>
        {adding && <ScheduleForm data={data} onDone={() => { setAdding(false); load(); }} />}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Schedule</TableHead>
              <TableHead>Scope · period</TableHead>
              <TableHead>When</TableHead>
              <TableHead>Recipients</TableHead>
              <TableHead>Last run</TableHead>
              <TableHead>State</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.rows.length === 0 && (
              <TableRow><TableCell colSpan={7} className="text-center text-sm text-muted-foreground">No schedule. Reports are on demand only.</TableCell></TableRow>
            )}
            {data.rows.map((s) => (
              <TableRow key={s.id} className={s.lifecycleState === "RETIRED" ? "opacity-60" : ""}>
                <TableCell>
                  <div className="font-medium">{s.displayName}</div>
                  <div className="text-xs text-muted-foreground">{kindLabel(s.reportKind)} · <code className="font-mono">{s.code}</code> · owner {s.ownerName}</div>
                </TableCell>
                <TableCell className="text-xs">
                  <div>{s.scopePath ?? "each recipient's whole reach"}</div>
                  <div className="text-muted-foreground">last {s.periodDays} days</div>
                </TableCell>
                <TableCell className="text-xs">
                  {s.cadence === "DAILY" ? `daily ${pad(s.runHourUtc)}:00 UTC`
                    : s.cadence === "WEEKLY" ? `${WEEKDAYS[s.runWeekday - 1]}s ${pad(s.runHourUtc)}:00 UTC`
                    : `day ${s.runDayOfMonth} monthly, ${pad(s.runHourUtc)}:00 UTC`}
                  {s.nextRunAt && <div className="text-muted-foreground">next {s.nextRunAt.replace("T", " ").slice(0, 16)}</div>}
                </TableCell>
                <TableCell className="text-xs">
                  {s.recipients.filter((r) => !r.droppedAt).map((r) => <div key={r.principalId}>{r.name}</div>)}
                  {s.recipients.filter((r) => r.droppedAt).map((r) => (
                    <div key={r.principalId} className="text-destructive" title={r.droppedReason ?? ""}>{r.name} — dropped</div>
                  ))}
                </TableCell>
                <TableCell className="text-xs text-muted-foreground">
                  {s.lastRunAt ? <>{s.lastRunAt.replace("T", " ").slice(0, 16)}<div>{s.lastOutcome}</div></> : "never"}
                </TableCell>
                <TableCell><Badge tone={s.lifecycleState === "ACTIVE" ? "ok" : s.lifecycleState === "PAUSED" ? "warn" : "neutral"}>{s.lifecycleState}</Badge></TableCell>
                <TableCell className="text-right">
                  {data.mayManage && s.lifecycleState !== "RETIRED" && (
                    <div className="flex flex-wrap justify-end gap-1">
                      {s.lifecycleState === "ACTIVE" && (
                        <Button size="sm" variant="outline" disabled={busy} onClick={() => act(`/api/ui/settings/report-schedules/${s.id}/run`, {})}>Run now</Button>
                      )}
                      <Button size="sm" variant="outline" disabled={busy} onClick={() => setEditingRecipients(editingRecipients?.id === s.id ? null : s)}>Recipients</Button>
                      <Button size="sm" variant="outline" disabled={busy}
                              onClick={() => act(`/api/ui/settings/report-schedules/${s.id}/transition`, { state: s.lifecycleState === "ACTIVE" ? "PAUSED" : "ACTIVE" })}>
                        {s.lifecycleState === "ACTIVE" ? "Pause" : "Resume"}
                      </Button>
                      <Button size="sm" variant="ghost" disabled={busy} onClick={() => act(`/api/ui/settings/report-schedules/${s.id}/transition`, { state: "RETIRED" })}>Retire</Button>
                    </div>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {editingRecipients && (
          <RecipientsPanel schedule={editingRecipients} people={data.people} onDone={() => { setEditingRecipients(null); load(); }} />
        )}
        {error && <p className="px-4 pb-3 text-sm text-destructive">{error}</p>}
      </Card>

      {data.mayExportEvidence && <EvidenceCard nodes={data.nodes} />}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><FileSpreadsheet className="size-4" /> My reports</CardTitle>
          <CardDescription>
            Files rendered for you, and every run of a schedule you own. Each carries its scope, period and basis on its first sheet.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {artifacts.length === 0 ? <p className="text-sm text-muted-foreground">Nothing generated yet.</p> : (
            <Table>
              <TableHeader><TableRow><TableHead>Generated</TableHead><TableHead>Report</TableHead><TableHead>For</TableHead><TableHead>Scope · period</TableHead><TableHead>Status</TableHead><TableHead className="text-right">File</TableHead></TableRow></TableHeader>
              <TableBody>
                {artifacts.map((a) => (
                  <TableRow key={a.id}>
                    <TableCell className="text-xs">{a.generatedAt.replace("T", " ").slice(0, 16)}</TableCell>
                    <TableCell className="text-xs">{kindLabel(a.reportKind)}{a.scheduleName ? <div className="text-muted-foreground">{a.scheduleName}</div> : <div className="text-muted-foreground">on demand</div>}</TableCell>
                    <TableCell className="text-xs">{a.recipientName}</TableCell>
                    <TableCell className="text-xs">{a.scopePath ?? "whole reach"}<div className="text-muted-foreground">{a.periodFrom} → {a.periodTo}</div></TableCell>
                    <TableCell>
                      <Badge tone={a.status === "GENERATED" ? "ok" : "critical"}>{a.status}</Badge>
                      {a.failureDetail && <div className="mt-1 max-w-64 text-xs text-destructive">{a.failureDetail}</div>}
                    </TableCell>
                    <TableCell className="text-right text-xs">
                      {a.stored && a.status === "GENERATED" ? (
                        <a className="inline-flex items-center gap-1 underline-offset-2 hover:underline" href={`/api/ui/reports/artifacts/${a.id}/download`}>
                          <Download className="size-3" /> {(a.byteSize / 1024).toFixed(0)} KB
                        </a>
                      ) : <span className="text-muted-foreground">{a.status === "GENERATED" ? "streamed, not retained" : "—"}</span>}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function pad(n: number) { return String(n).padStart(2, "0"); }

function ScheduleForm({ data, onDone }: { data: Payload; onDone: () => void }) {
  const [kind, setKind] = useState(data.kinds[0]?.kind ?? "FINDING_REGISTER");
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [scope, setScope] = useState("");
  const [period, setPeriod] = useState("30");
  const [cadence, setCadence] = useState("WEEKLY");
  const [hour, setHour] = useState("6");
  const [weekday, setWeekday] = useState("1");
  const [dom, setDom] = useState("1");
  const [recipients, setRecipients] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const selected = data.kinds.find((k) => k.kind === kind);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await api.post("/api/ui/settings/report-schedules", {
        code, displayName: name, reportKind: kind, scopeNodeId: scope || undefined, periodDays: Number(period) || 30, cadence,
        runHourUtc: Number(hour), runWeekday: Number(weekday), runDayOfMonth: Number(dom), recipients,
      });
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <CardContent className="border-t bg-muted/20 py-4">
      <div className="grid gap-3 md:grid-cols-2">
        <div className="flex flex-col gap-1">
          <Label>Report</Label>
          <Select value={kind} onValueChange={setKind}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>{data.kinds.map((k) => <SelectItem key={k.kind} value={k.kind}>{k.label}</SelectItem>)}</SelectContent>
          </Select>
          {selected && <span className="text-xs text-muted-foreground">{selected.description} Sheets: {selected.sheets.join(", ")}. Recipients need <code>{selected.requiredPermission}</code>.</span>}
        </div>
        <div className="flex flex-col gap-1"><Label>Code</Label><Input value={code} onChange={(e) => setCode(e.target.value)} placeholder="weekly-findings" /></div>
        <div className="flex flex-col gap-1"><Label>Display name</Label><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Weekly finding register" /></div>
        <div className="flex flex-col gap-1">
          <Label>Scope</Label>
          <Select value={scope || "__reach"} onValueChange={(v) => setScope(v === "__reach" ? "" : v)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="__reach">Each recipient's whole reach</SelectItem>
              {data.nodes.map((n) => <SelectItem key={n.id} value={n.id}>{n.path || n.id}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1"><Label>Period (days back from each run)</Label><Input type="number" min={1} max={730} value={period} onChange={(e) => setPeriod(e.target.value)} /></div>
        <div className="flex flex-col gap-1">
          <Label>Cadence</Label>
          <Select value={cadence} onValueChange={setCadence}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent><SelectItem value="DAILY">Daily</SelectItem><SelectItem value="WEEKLY">Weekly</SelectItem><SelectItem value="MONTHLY">Monthly</SelectItem></SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1"><Label>Hour (UTC)</Label><Input type="number" min={0} max={23} value={hour} onChange={(e) => setHour(e.target.value)} /></div>
        {cadence === "WEEKLY" && (
          <div className="flex flex-col gap-1">
            <Label>Weekday</Label>
            <Select value={weekday} onValueChange={setWeekday}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>{WEEKDAYS.map((d, i) => <SelectItem key={d} value={String(i + 1)}>{d}</SelectItem>)}</SelectContent>
            </Select>
          </div>
        )}
        {cadence === "MONTHLY" && (
          <div className="flex flex-col gap-1"><Label>Day of month (1–28)</Label><Input type="number" min={1} max={28} value={dom} onChange={(e) => setDom(e.target.value)} /></div>
        )}
      </div>
      <div className="mt-3">
        <Label>Recipients (each gets their own file)</Label>
        <div className="mt-1 grid gap-1 sm:grid-cols-2 lg:grid-cols-3">
          {data.people.map((p) => (
            <label key={p.id} className="flex items-center gap-2 text-sm">
              <Checkbox checked={recipients.includes(p.id)}
                        onCheckedChange={(v) => setRecipients(v ? [...recipients, p.id] : recipients.filter((x) => x !== p.id))} />
              {p.name}
            </label>
          ))}
        </div>
      </div>
      {error && <p className="mt-2 text-sm text-destructive">{error}</p>}
      <div className="mt-3"><Button size="sm" disabled={busy} onClick={submit}>Save schedule</Button></div>
    </CardContent>
  );
}

function RecipientsPanel({ schedule, people, onDone }: { schedule: Schedule; people: { id: string; name: string }[]; onDone: () => void }) {
  const [chosen, setChosen] = useState<string[]>(schedule.recipients.filter((r) => !r.droppedAt).map((r) => r.principalId));
  const [error, setError] = useState<string | null>(null);

  async function save() {
    setError(null);
    try {
      await api.post(`/api/ui/settings/report-schedules/${schedule.id}/recipients`, { recipients: chosen });
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <CardContent className="border-t bg-muted/20 py-4">
      <p className="text-sm font-medium">Recipients — {schedule.displayName}</p>
      <div className="mt-2 grid gap-1 sm:grid-cols-2 lg:grid-cols-3">
        {people.map((p) => (
          <label key={p.id} className="flex items-center gap-2 text-sm">
            <Checkbox checked={chosen.includes(p.id)} onCheckedChange={(v) => setChosen(v ? [...chosen, p.id] : chosen.filter((x) => x !== p.id))} />
            {p.name}
          </label>
        ))}
      </div>
      {error && <p className="mt-2 text-sm text-destructive">{error}</p>}
      <div className="mt-3"><Button size="sm" onClick={save}>Save recipients</Button></div>
    </CardContent>
  );
}

function EvidenceCard({ nodes }: { nodes: { id: string; path: string }[] }) {
  const today = new Date().toISOString().slice(0, 10);
  const [scope, setScope] = useState("");
  const [from, setFrom] = useState(new Date(Date.now() - 89 * 86400000).toISOString().slice(0, 10));
  const [to, setTo] = useState(today);
  const params = new URLSearchParams({ from, to });
  if (scope) params.set("scope", scope);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Audit evidence</CardTitle>
        <CardDescription>
          For a scope and period: assessments with coverage, findings with lifecycle and closure reasons, exceptions with approvals,
          service level compliance with attribution, access review, configuration change history, evidence references. No finding
          text, no credentials, no evidence bodies (PRD-DSH-048). Its generation is audited (PRD-DSH-047).
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap items-end gap-3">
        <div className="flex min-w-64 flex-col gap-1">
          <Label>Scope</Label>
          <Select value={scope || "__reach"} onValueChange={(v) => setScope(v === "__reach" ? "" : v)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="__reach">My whole reach</SelectItem>
              {nodes.map((n) => <SelectItem key={n.id} value={n.id}>{n.path || n.id}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1"><Label>From</Label><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
        <div className="flex flex-col gap-1"><Label>To</Label><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
        <Button size="sm" asChild>
          <a href={`/api/ui/reports/audit-evidence?${params.toString()}`}><Download className="size-3" /> Assemble and download</a>
        </Button>
      </CardContent>
    </Card>
  );
}
