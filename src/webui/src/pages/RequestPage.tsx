import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { DateField } from "@/components/DateField";
import { api } from "@/lib/api";
import type { RequestDetail } from "@/lib/types";
import { severityTone, stateTone } from "@/components/tone";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Pager, usePaging } from "@/components/Paging";
import { Comments } from "@/components/Comments";
import { Combobox } from "@/components/Combobox";
import { RequestParticipants } from "@/components/RequestParticipants";
import { RecordFinding } from "@/components/RecordFinding";

export function RequestPage() {
  const { id = "" } = useParams();
  const [detail, setDetail] = useState<RequestDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [event, setEvent] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const findings = usePaging(detail?.findings ?? []);

  const load = useCallback(() => {
    api.get<RequestDetail>(`/api/ui/board/${id}`)
      .then((d) => { setDetail(d); setEvent(""); setReason(""); })
      .catch((e) => setError(e.message));
  }, [id]);

  useEffect(load, [load]);

  async function apply() {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/ui/board/${id}/transitions`, { event, reason: reason || null });
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  if (!detail) {
    return <div className="text-sm text-muted-foreground">{error ?? "Loading…"}</div>;
  }
  const { row, moves } = detail;
  const uploadTo = `/board/${row.id}/attachments`;
  const selected = moves.find((m) => m.event === event);
  const needsReason = selected?.reasonRequired ?? false;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <Link to="/board" className="mb-1 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
            <ArrowLeft className="size-3" /> All requests
          </Link>
          <h1 className="truncate text-lg font-semibold tracking-tight">{row.title ?? row.code}</h1>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <span className="font-mono">{row.code}</span>
            <Badge tone={stateTone(row.stateCategory)}>{row.stateLabel}</Badge>
            {row.triggerLabel && <Badge tone={row.triggerIsFullReview ? "info" : "neutral"}>{row.triggerLabel}</Badge>}
            {row.overdue && <Badge tone="critical">Overdue</Badge>}
          </div>
        </div>
      </div>

      {error && (
        <div className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
          {error}
        </div>
      )}

      <div className="grid gap-5 lg:grid-cols-3">
        <Card className="lg:col-span-1">
          <CardHeader>
            <CardTitle>State</CardTitle>
            <CardDescription>
              One move at a time, from the workflow the tenant configured.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {moves.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                This request is finished. There is no move from here.
              </p>
            ) : (
              <>
                <div className="flex flex-col gap-1">
                  <Label>Move to</Label>
                  <Select value={event} onValueChange={setEvent} disabled={!detail.mayAct}>
                    <SelectTrigger><SelectValue placeholder="Choose a state" /></SelectTrigger>
                    <SelectContent>
                      {moves.map((m) => (
                        // A blocked move stays in the list, disabled, carrying its reason. Removing
                        // it would leave somebody wondering why the state they expect is missing.
                        <SelectItem key={m.event} value={m.event} disabled={!m.permitted}>
                          {m.toStateLabel}{!m.permitted && m.blockedReason ? ` — ${m.blockedReason}` : ""}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                {needsReason && (
                  <div className="flex flex-col gap-1">
                    <Label htmlFor="reason">Reason (required for this move)</Label>
                    <Input id="reason" value={reason} onChange={(e) => setReason(e.target.value)}
                           placeholder="Say why" autoComplete="off" />
                  </div>
                )}
                <Button size="sm" disabled={!event || busy || (needsReason && !reason.trim()) || !detail.mayAct}
                        onClick={apply}>
                  {busy ? "Applying…" : "Apply"}
                </Button>
              </>
            )}
          </CardContent>
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader><CardTitle>Request</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
            <div>
              <div className="text-xs text-muted-foreground">
                {row.projects.length > 1 ? "Projects covered" : "Project"}
              </div>
              <div className="text-sm">
                {row.projects.length === 0
                  ? <span className="text-muted-foreground">—</span>
                  : (
                    <span className="flex flex-wrap gap-1">
                      {row.projects.map((p) => (
                        <Link key={p.id} to={`/projects/${p.id}`}
                              className="text-primary hover:underline">{p.name}</Link>
                      ))}
                    </span>
                  )}
              </div>
            </div>
            <Field label="Application" value={row.application} />
            <Field label="Organization" value={row.orgNodeName} />
            <Field label="Created" value={row.createdAt} mono />
            <Field label="Due" value={row.dueAt} mono />
            <Field label="Closed" value={row.closedAt} mono />
            <Field label="Dev contact" value={row.contact} />
            <Field label="Assessor" value={row.assessor} />
            <Field label="Findings" value={`${row.findingOpen} open / ${row.findingTotal} total`} />
          </CardContent>
        </Card>
      </div>

      <AssignCard detail={detail} onSaved={load} />

      {/* Only where the caller may act on this request. The server refuses regardless — the button
          is hidden because offering a control that will be denied teaches people to distrust the
          interface, not because hiding it is the control. */}
      {detail.mayAct && <RecordFinding requestId={row.id} onRecorded={load} />}

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle>Findings</CardTitle>
          <CardDescription>Discovered in this request.</CardDescription>
        </CardHeader>
        {detail.findings.length === 0 ? (
          <CardContent className="text-sm text-muted-foreground">
            {/* Not "0 findings". Nothing recorded and nothing found are different claims, and only
                the assessor knows which this is (PP-1). */}
            Nothing has been recorded against this request yet.
          </CardContent>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Finding</TableHead>
                <TableHead>Severity</TableHead>
                <TableHead>Detected</TableHead>
                <TableHead>State</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {findings.rows.map((f) => (
                <TableRow key={f.id}>
                  <TableCell>
                    <Link to={`/board/${row.id}/findings/${f.id}`}
                          className="font-medium text-primary hover:underline">{f.title}</Link>
                    {f.context && <div className="text-[11px] text-muted-foreground">{f.context}</div>}
                  </TableCell>
                  <TableCell><Badge tone={severityTone(f.severity)}>{f.severity ?? "unrated"}</Badge></TableCell>
                  <TableCell className="font-mono text-[11px]">{f.firstDetectedAt ?? "—"}</TableCell>
                  <TableCell>
                    <Badge tone={f.state === "OPEN" ? "warn" : "ok"}>{f.state}</Badge>
                    {f.closureReason === "RISK_ACCEPTED" && (
                      <Badge tone="info" className="ml-1">
                        risk accepted{f.acceptedUntil ? ` → ${f.acceptedUntil}` : ""}
                      </Badge>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        <Pager paging={findings} unit="findings" />
      </Card>
      <RequestParticipants requestId={row.id} />

      <Comments comments={detail.comments} postTo={`/api/ui/board/${row.id}/comments`}
                uploadTo={uploadTo}
                onPosted={(comments) => setDetail({ ...detail, comments })} />
    </div>
  );
}

/**
 * Naming the people and the reason.
 *
 * <p>A combobox rather than a dropdown: a native select is fine for five options and unusable for
 * fifty, and naming the developer and the assessor is the pair of fields that gets set on every
 * request. Radix keeps the keyboard behaviour and the accessible name; the filtering is a plain
 * substring match over a list the SERVER already scoped to people this caller can see.
 */
function AssignCard({ detail, onSaved }: { detail: RequestDetail; onSaved: () => void }) {
  const [contact, setContact] = useState(detail.contactId ?? "");
  const [assessor, setAssessor] = useState(detail.assessorId ?? "");
  const [trigger, setTrigger] = useState(detail.triggerId ?? "");
  const [due, setDue] = useState(detail.row.dueAt ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    setBusy(true); setError(null);
    try {
      await api.post(`/api/ui/board/${detail.row.id}/assign`,
        { contact: contact || null, assessor: assessor || null, trigger: trigger || null, due: due || null });
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  if (!detail.mayAct) return null;
  return (
    <Card>
      <CardHeader>
        <CardTitle>People, deadline and reason</CardTitle>
        <CardDescription>
          Naming an assessor starts the assessment record if the request has none.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="grid gap-3 sm:grid-cols-4">
          <PersonPicker label="Developer" people={detail.people} value={contact} onChange={setContact} />
          <PersonPicker label="Assessor" people={detail.people} value={assessor} onChange={setAssessor} />
          <div className="flex flex-col gap-1">
            <Label htmlFor="due">Due</Label>
            <DateField id="due" value={due} onChange={setDue} />
          </div>
          <div className="flex flex-col gap-1">
            <Label>Reason it exists</Label>
            <Select value={trigger} onValueChange={setTrigger}>
              <SelectTrigger><SelectValue placeholder="Not stated" /></SelectTrigger>
              <SelectContent>
                {detail.triggers.map((t) => (
                  <SelectItem key={t.id} value={t.id}>
                    {t.label}{t.countsAsFullReview ? " ★" : ""}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
        {error && <p className="text-xs text-destructive">{error}</p>}
        <div className="flex justify-end">
          <Button size="sm" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</Button>
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * The developer and the assessor.
 *
 * <p>The server sends each person as one string, "Display name  ·  username", because that is what
 * the server-rendered page needed. Split here so the username becomes a second line and, more to the
 * point, so the filter matches it: people search for the account name at least as often as for the
 * display name, and a filter that only matches what is printed largest is a filter that misses.
 */
function PersonPicker({ label, people, value, onChange }: {
  label: string; people: { id: string; name: string }[]; value: string;
  onChange: (id: string) => void;
}) {
  const items = people.map((p) => {
    const [name, hint] = p.name.split("·").map((part) => part.trim());
    return { id: p.id, name: name || p.name, hint };
  });
  return (
    <div className="flex flex-col gap-1">
      <Label>{label}</Label>
      <Combobox items={items} value={value} onChange={onChange} />
    </div>
  );
}

function Field({ label, value, mono }: { label: string; value: string | null; mono?: boolean }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={mono ? "font-mono text-xs" : "text-sm"}>
        {value ?? <span className="text-muted-foreground">—</span>}
      </div>
    </div>
  );
}
