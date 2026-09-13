import { useCallback, useEffect, useState } from "react";
import { Cable, Plus, RefreshCw, X } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * Outbound connectors: where a finding can be pushed as a ticket, as options.
 *
 * **Options, not a vendor.** Jira Cloud, Jira Data Center, GitLab issues, ServiceNow, or a signed
 * generic webhook for a tracker the platform does not know. Each kind states the least-privilege set
 * it needs on the target and exactly what it transmits, next to the form that configures it.
 *
 * **One way.** A ticket is a reference to the finding. What the tracker later says is observed and
 * shown; where it disagrees with the platform record it is a divergence for a person, never a change
 * to the finding (ADR-040).
 *
 * **Scoped.** A connector may be limited to one part of the organization tree; a finding outside it is
 * refused before anything leaves (PRD-CON-038).
 */

interface Health {
  lastSuccessAt: string | null; lastAttemptAt: string | null; consecutiveFailures: number;
  lastFailureClass: string | null; lastFailureDetail: string | null; circuitState: string;
  circuitOpenReason: string | null; periodAttempts: number; periodSuccesses: number; successRate: string;
}
interface Connector {
  id: string; code: string; displayName: string; kind: string; adapterVersion: number; config: Record<string, unknown>;
  credentialHeld: boolean; rotationInProgress: boolean; credentialExpiresAt: string | null; scopeNodeId: string | null;
  scopePath: string | null; ownerPrincipalId: string; ownerName: string; lifecycleState: string;
  validationDiagnosis: string | null; observeEveryMinutes: number; rowVersion: number; health: Health; openDivergences: number;
}
interface Kind {
  kind: string; label: string; version: number; credentialLabel: string; minimumPermissions: string[];
  outboundContent: Record<string, string>; enabled: boolean; consequence: string | null;
}
interface Payload {
  rows: Connector[]; catalogue: Kind[]; mayManage: boolean; elevated: boolean;
  nodes: { id: string; path: string }[]; owners: { id: string; name: string }[];
}
interface Operation {
  id: string; kind: string; status: string; attempts: number; failureClass: string | null; detail: string | null;
  createdAt: string; finishedAt: string | null; referenceId: string | null;
}
interface Divergence {
  id: string; connectorName: string; kind: string; externalKey: string | null; externalUrl: string | null; externalState: string | null;
  divergenceKind: string; divergenceDetectedAt: string; divergencePlatformState: string | null;
  findingId: string; findingTitle: string; findingLifecycle: string; findingSeverity: string; requestId: string | null;
}

export function Connectors() {
  const [data, setData] = useState<Payload | null>(null);
  const [divergences, setDivergences] = useState<Divergence[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [rotating, setRotating] = useState<Connector | null>(null);
  const [history, setHistory] = useState<{ connector: Connector; rows: Operation[] } | null>(null);
  const [probe, setProbe] = useState<{ connector: Connector; ok: boolean; detail: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.get<Payload>("/api/ui/settings/connectors").then(setData).catch((e) => setError(e.message));
    api.get<{ rows: Divergence[] }>("/api/ui/outbound-references/divergences").then((d) => setDivergences(d.rows)).catch(() => setDivergences([]));
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

  async function runProbe(connector: Connector) {
    setBusy(true);
    try {
      const r = await api.post<{ ok: boolean; detail: string }>(`/api/ui/settings/connectors/${connector.id}/probe`, {});
      setProbe({ connector, ok: r.ok, detail: r.detail });
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  async function showHistory(connector: Connector) {
    const d = await api.get<{ rows: Operation[] }>(`/api/ui/settings/connectors/${connector.id}/operations`);
    setHistory({ connector, rows: d.rows });
  }

  if (error && !data) return <p className="text-sm text-destructive">{error}</p>;
  if (!data) return <p className="text-sm text-muted-foreground">Loading…</p>;
  const label = (kind: string) => data.catalogue.find((k) => k.kind === kind)?.label ?? kind;

  return (
    <div className="flex flex-col gap-4">
      <Card className="overflow-hidden">
        <CardHeader className="flex-row items-start justify-between">
          <div>
            <CardTitle className="flex items-center gap-2"><Cable className="size-4" /> Outbound connectors</CardTitle>
            <CardDescription>
              Jira, GitLab, ServiceNow or a signed webhook. A finding is pushed as a ticket that carries a reference and a
              summary — never the write-up. The platform record stays authoritative; a ticket closed elsewhere is surfaced
              below as a divergence for a person to decide, not applied.
            </CardDescription>
          </div>
          {data.mayManage && (
            <Button size="sm" onClick={() => setAdding((v) => !v)}>
              {adding ? <><X className="size-4" /> Cancel</> : <><Plus className="size-4" /> Add connector</>}
            </Button>
          )}
        </CardHeader>
        {adding && <ConnectorForm catalogue={data.catalogue.filter((k) => k.enabled)} nodes={data.nodes} owners={data.owners}
                                  onDone={() => { setAdding(false); load(); }} />}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Connector</TableHead>
              <TableHead>Destination</TableHead>
              <TableHead>Scope · owner</TableHead>
              <TableHead>Health</TableHead>
              <TableHead>State</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.rows.length === 0 && (
              <TableRow><TableCell colSpan={6} className="text-center text-sm text-muted-foreground">
                No connector. Findings stay in the platform; nothing is pushed anywhere.
              </TableCell></TableRow>
            )}
            {data.rows.map((row) => (
              <TableRow key={row.id} className={row.lifecycleState === "RETIRED" ? "opacity-60" : ""}>
                <TableCell>
                  <div className="font-medium">{row.displayName}</div>
                  <div className="text-xs text-muted-foreground">{label(row.kind)} v{row.adapterVersion} · <code className="font-mono">{row.code}</code></div>
                  <div className="mt-1 flex flex-wrap gap-1">
                    {row.credentialHeld ? <Badge tone="ok">credential held</Badge> : <Badge tone="warn">no credential</Badge>}
                    {row.rotationInProgress && <Badge tone="info">rotation overlap</Badge>}
                    {row.credentialExpiresAt && <Badge tone="neutral">expires {row.credentialExpiresAt.slice(0, 10)}</Badge>}
                    {row.openDivergences > 0 && <Badge tone="warn">{row.openDivergences} divergence(s)</Badge>}
                  </div>
                </TableCell>
                <TableCell className="max-w-64 truncate font-mono text-xs">{destination(row)}</TableCell>
                <TableCell className="text-xs">
                  <div>{row.scopePath ?? "whole tenant"}</div>
                  <div className="text-muted-foreground">owner {row.ownerName}</div>
                </TableCell>
                <TableCell className="text-xs">
                  <HealthCell h={row.health} />
                </TableCell>
                <TableCell>
                  <Badge tone={row.lifecycleState === "ACTIVE" ? "ok" : row.lifecycleState === "FAILED_VALIDATION" ? "critical"
                    : row.lifecycleState === "SUSPENDED" ? "warn" : "neutral"}>{row.lifecycleState}</Badge>
                  {row.validationDiagnosis && <div className="mt-1 max-w-56 text-xs text-destructive">{row.validationDiagnosis}</div>}
                </TableCell>
                <TableCell className="text-right">
                  {data.mayManage && row.lifecycleState !== "RETIRED" && (
                    <div className="flex flex-wrap justify-end gap-1">
                      <Button size="sm" variant="outline" disabled={busy} onClick={() => runProbe(row)}>Test</Button>
                      <Button size="sm" variant="outline" disabled={busy} onClick={() => showHistory(row)}>
                        <RefreshCw className="size-3" /> Operations
                      </Button>
                      <Button size="sm" variant="outline" disabled={busy} onClick={() => setRotating(rotating?.id === row.id ? null : row)}>Rotate</Button>
                      <Button size="sm" variant="outline" disabled={busy}
                              onClick={() => act(`/api/ui/settings/connectors/${row.id}/transition`,
                                { state: row.lifecycleState === "ACTIVE" ? "SUSPENDED" : "ACTIVE" })}>
                        {row.lifecycleState === "ACTIVE" ? "Suspend" : "Activate"}
                      </Button>
                      <Button size="sm" variant="ghost" disabled={busy}
                              onClick={() => act(`/api/ui/settings/connectors/${row.id}/transition`, { state: "RETIRED" })}>Retire</Button>
                    </div>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {rotating && <RotatePanel connector={rotating} credentialLabel={data.catalogue.find((k) => k.kind === rotating.kind)?.credentialLabel ?? "credential"}
                                  onDone={() => { setRotating(null); load(); }} />}
        {probe && (
          <CardContent className="border-t bg-muted/20 py-3 text-sm">
            <span className={probe.ok ? "text-tone-ok" : "text-destructive"}>{probe.ok ? "Reachable" : "Failed"}</span>
            <span className="text-muted-foreground"> — {probe.connector.displayName}: {probe.detail}</span>
          </CardContent>
        )}
        {history && (
          <CardContent className="border-t bg-muted/20 py-4">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-sm font-medium">Recent operations — {history.connector.displayName}</span>
              <Button size="sm" variant="ghost" onClick={() => setHistory(null)}><X className="size-4" /></Button>
            </div>
            {history.rows.length === 0 ? <p className="text-xs text-muted-foreground">Nothing has been attempted on this connector yet.</p> : (
              <Table>
                <TableHeader><TableRow><TableHead>When</TableHead><TableHead>Operation</TableHead><TableHead>Status</TableHead><TableHead>Detail</TableHead></TableRow></TableHeader>
                <TableBody>
                  {history.rows.map((o) => (
                    <TableRow key={o.id}>
                      <TableCell className="text-xs">{o.createdAt.replace("T", " ").slice(0, 16)}</TableCell>
                      <TableCell className="text-xs">{o.kind}</TableCell>
                      <TableCell><Badge tone={o.status === "DONE" ? "ok" : o.status === "QUEUED" || o.status === "LEASED" ? "info" : o.status === "QUARANTINED" ? "warn" : "critical"}>{o.status}</Badge></TableCell>
                      <TableCell className="text-xs text-muted-foreground">{o.attempts} attempt(s){o.failureClass ? ` · ${o.failureClass}` : ""}{o.detail ? ` · ${o.detail}` : ""}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        )}
        {error && <p className="px-4 pb-3 text-sm text-destructive">{error}</p>}
      </Card>

      <DivergenceCard rows={divergences} onChanged={load} />

      <Card>
        <CardHeader>
          <CardTitle>What each connector needs, and what it sends</CardTitle>
          <CardDescription>
            Stated per kind (PRD-CON-016, PRD-CON-036), so the token you create on the target can be the smallest one that
            works, and your data-governance review has the outbound content in writing. A kind this deployment disabled is
            listed with the consequence rather than hidden (PRD-CON-054).
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid gap-2 md:grid-cols-2">
            {data.catalogue.map((k) => (
              <div key={k.kind} className={`rounded-md border p-3 text-xs ${k.enabled ? "" : "opacity-70"}`}>
                <div className="flex items-center gap-2 font-medium">
                  {k.label} <span className="text-muted-foreground">v{k.version}</span>
                  {!k.enabled && <Badge tone="warn">disabled here</Badge>}
                </div>
                {k.consequence && <p className="mt-1 text-destructive">{k.consequence}</p>}
                <p className="mt-2 font-medium">Minimum permissions on the target</p>
                <ul className="list-disc pl-4 text-muted-foreground">{k.minimumPermissions.map((p) => <li key={p}>{p}</li>)}</ul>
                <p className="mt-2 font-medium">Outbound content</p>
                <ul className="text-muted-foreground">
                  {Object.entries(k.outboundContent).map(([op, what]) => <li key={op}><span className="font-mono">{op}</span>: {what}</li>)}
                </ul>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

function HealthCell({ h }: { h: Health }) {
  if (!h.lastAttemptAt) return <span className="text-muted-foreground">nothing attempted yet</span>;
  const bad = h.circuitState === "OPEN";
  return (
    <div className={bad ? "text-destructive" : h.consecutiveFailures > 0 ? "text-tone-warn" : "text-tone-ok"}>
      <div>circuit {h.circuitState} · {h.successRate} of {h.periodAttempts} this period</div>
      {h.consecutiveFailures > 0 && <div>{h.consecutiveFailures} failure(s) in a row · {h.lastFailureClass}</div>}
      {h.circuitOpenReason && <div className="text-muted-foreground">{h.circuitOpenReason}</div>}
      {!bad && h.lastFailureDetail && h.consecutiveFailures > 0 && <div className="text-muted-foreground">{h.lastFailureDetail}</div>}
    </div>
  );
}

function destination(row: Connector): string {
  const c = row.config;
  switch (row.kind) {
    case "JIRA_CLOUD":
    case "JIRA_DATA_CENTER": return `${c.baseUrl} · ${c.projectKey} / ${c.issueType}`;
    case "GITLAB": return `${c.baseUrl} · project ${c.projectId}`;
    case "SERVICENOW": return `${c.baseUrl} · ${c.table}`;
    default: return String(c.url ?? "");
  }
}

const FIELDS: Record<string, { key: string; label: string; placeholder?: string; hint?: string }[]> = {
  JIRA_CLOUD: [
    { key: "baseUrl", label: "Site URL", placeholder: "https://your-site.atlassian.net" },
    { key: "projectKey", label: "Project key", placeholder: "SEC" },
    { key: "issueType", label: "Issue type", placeholder: "Task" },
    { key: "email", label: "Account email of the API token", placeholder: "aspm-bot@example.com" },
  ],
  JIRA_DATA_CENTER: [
    { key: "baseUrl", label: "Base URL", placeholder: "https://jira.example.com/jira" },
    { key: "projectKey", label: "Project key", placeholder: "SEC" },
    { key: "issueType", label: "Issue type", placeholder: "Task" },
  ],
  GITLAB: [
    { key: "baseUrl", label: "GitLab URL", placeholder: "https://gitlab.com" },
    { key: "projectId", label: "Numeric project id", placeholder: "12345678", hint: "Shown under the project name. Use a token without repository scopes (ADR-024)." },
  ],
  SERVICENOW: [
    { key: "baseUrl", label: "Instance URL", placeholder: "https://acme.service-now.com" },
    { key: "table", label: "Table", placeholder: "incident" },
    { key: "username", label: "Integration user", placeholder: "aspm.integration" },
    { key: "resolvedStates", label: "State values that mean resolved", placeholder: "6,7" },
  ],
  GENERIC_WEBHOOK: [
    { key: "url", label: "Receiver URL", placeholder: "https://hooks.example.com/aspm", hint: "Receives signed POSTs with operation=create|observe|probe." },
  ],
};

function ConnectorForm({ catalogue, nodes, owners, onDone }: {
  catalogue: Kind[]; nodes: { id: string; path: string }[]; owners: { id: string; name: string }[]; onDone: () => void;
}) {
  const [kind, setKind] = useState(catalogue[0]?.kind ?? "");
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [config, setConfig] = useState<Record<string, string>>({});
  const [credential, setCredential] = useState("");
  const [expires, setExpires] = useState("");
  const [scope, setScope] = useState("");
  const [owner, setOwner] = useState("");
  const [interval, setInterval] = useState("60");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const selected = catalogue.find((k) => k.kind === kind);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await api.post("/api/ui/settings/connectors", {
        code, displayName: name, kind, config, credential,
        credentialExpiresAt: expires || undefined, scopeNodeId: scope || undefined, ownerPrincipalId: owner || undefined,
        observeEveryMinutes: Number(interval) || 60,
      });
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  if (!selected) return <CardContent className="text-sm text-muted-foreground">Every connector kind is disabled in this deployment.</CardContent>;

  return (
    <CardContent className="border-t bg-muted/20 py-4">
      <div className="grid gap-3 md:grid-cols-2">
        <div className="flex flex-col gap-1">
          <Label>Kind</Label>
          <Select value={kind} onValueChange={(v) => { setKind(v); setConfig({}); }}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>{catalogue.map((k) => <SelectItem key={k.kind} value={k.kind}>{k.label}</SelectItem>)}</SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1"><Label>Code</Label><Input value={code} onChange={(e) => setCode(e.target.value)} placeholder="jira-appsec" /></div>
        <div className="flex flex-col gap-1"><Label>Display name</Label><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Jira — AppSec project" /></div>
        {(FIELDS[kind] ?? []).map((f) => (
          <div key={f.key} className="flex flex-col gap-1">
            <Label>{f.label}</Label>
            <Input value={config[f.key] ?? ""} placeholder={f.placeholder} onChange={(e) => setConfig({ ...config, [f.key]: e.target.value })} />
            {f.hint && <span className="text-xs text-muted-foreground">{f.hint}</span>}
          </div>
        ))}
        <div className="flex flex-col gap-1">
          <Label>{selected.credentialLabel}</Label>
          <Input type="password" value={credential} onChange={(e) => setCredential(e.target.value)} autoComplete="off" />
          <span className="text-xs text-muted-foreground">Stored by reference in the secrets store; never shown again.</span>
        </div>
        <div className="flex flex-col gap-1"><Label>Credential expires (optional)</Label><Input type="date" value={expires} onChange={(e) => setExpires(e.target.value)} /></div>
        <div className="flex flex-col gap-1">
          <Label>Scope (which part of the organization this connector may carry)</Label>
          <Select value={scope || "__all"} onValueChange={(v) => setScope(v === "__all" ? "" : v)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="__all">Whole tenant</SelectItem>
              {nodes.map((n) => <SelectItem key={n.id} value={n.id}>{n.path || n.id}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label>Owner (told when it fails)</Label>
          <Select value={owner || "__me"} onValueChange={(v) => setOwner(v === "__me" ? "" : v)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="__me">Me</SelectItem>
              {owners.map((o) => <SelectItem key={o.id} value={o.id}>{o.name}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1"><Label>Observe tickets every (minutes)</Label><Input type="number" min={5} max={10080} value={interval} onChange={(e) => setInterval(e.target.value)} /></div>
      </div>
      <div className="mt-3 rounded-md border bg-background p-3 text-xs">
        <p className="font-medium">Minimum permissions on the target</p>
        <ul className="list-disc pl-4 text-muted-foreground">{selected.minimumPermissions.map((p) => <li key={p}>{p}</li>)}</ul>
      </div>
      {error && <p className="mt-2 text-sm text-destructive">{error}</p>}
      <div className="mt-3 flex gap-2">
        <Button size="sm" disabled={busy} onClick={submit}>Save (validated, then activate)</Button>
      </div>
    </CardContent>
  );
}

function RotatePanel({ connector, credentialLabel, onDone }: { connector: Connector; credentialLabel: string; onDone: () => void }) {
  const [credential, setCredential] = useState("");
  const [expires, setExpires] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function post(body: unknown) {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/ui/settings/connectors/${connector.id}/rotate`, body);
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <CardContent className="border-t bg-muted/20 py-4">
      <p className="text-sm font-medium">Rotate the credential — {connector.displayName}</p>
      <p className="text-xs text-muted-foreground">
        The new {credentialLabel.toLowerCase()} is proved against the target before it replaces the current one. The current one
        stays valid for a day as a fallback (PRD-CON-022); end the overlap early once the target has switched.
      </p>
      <div className="mt-2 grid gap-3 md:grid-cols-2">
        <div className="flex flex-col gap-1"><Label>New {credentialLabel}</Label><Input type="password" value={credential} onChange={(e) => setCredential(e.target.value)} autoComplete="off" /></div>
        <div className="flex flex-col gap-1"><Label>Expires (optional)</Label><Input type="date" value={expires} onChange={(e) => setExpires(e.target.value)} /></div>
      </div>
      {error && <p className="mt-2 text-sm text-destructive">{error}</p>}
      <div className="mt-3 flex gap-2">
        <Button size="sm" disabled={busy || !credential} onClick={() => post({ credential, credentialExpiresAt: expires || undefined })}>Prove and rotate</Button>
        {connector.rotationInProgress && (
          <Button size="sm" variant="outline" disabled={busy} onClick={() => post({ retirePrevious: true })}>End overlap now</Button>
        )}
      </div>
    </CardContent>
  );
}

export function DivergenceCard({ rows, onChanged }: { rows: Divergence[]; onChanged: () => void }) {
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);

  async function resolve(id: string) {
    setError(null);
    try {
      await api.post(`/api/ui/outbound-references/${id}/resolve`, { note: notes[id] ?? "" });
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Divergences awaiting a decision {rows.length > 0 && <Badge tone="warn">{rows.length}</Badge>}</CardTitle>
        <CardDescription>
          The tracker says one thing and the platform record says another. Nothing was changed on the finding (PRD-CON-042);
          somebody decides. If the finding should move, move it on its own page — that is a finding transition, audited there.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {rows.length === 0 ? <p className="text-sm text-muted-foreground">None open.</p> : (
          <div className="flex flex-col gap-3">
            {rows.map((d) => (
              <div key={d.id} className="rounded-md border p-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone="warn">{d.divergenceKind}</Badge>
                  <a className="font-medium underline-offset-2 hover:underline"
                     href={d.requestId ? `/board/${d.requestId}/findings/${d.findingId}` : `/pipeline/findings/${d.findingId}`}>{d.findingTitle}</a>
                  <span className="text-xs text-muted-foreground">{d.findingSeverity} · platform {d.findingLifecycle}</span>
                </div>
                <div className="mt-1 text-xs text-muted-foreground">
                  {d.connectorName}: {d.externalUrl ? <a className="underline" href={d.externalUrl} target="_blank" rel="noreferrer">{d.externalKey}</a> : d.externalKey}
                  {" "}is <span className="font-mono">{d.externalState ?? "gone"}</span> · detected {d.divergenceDetectedAt.replace("T", " ").slice(0, 16)}
                </div>
                <div className="mt-2 flex flex-wrap gap-2">
                  <Input className="max-w-md" placeholder="What was decided, and why (at least 10 characters)"
                         value={notes[d.id] ?? ""} onChange={(e) => setNotes({ ...notes, [d.id]: e.target.value })} />
                  <Button size="sm" variant="outline" onClick={() => resolve(d.id)}>Resolve</Button>
                </div>
              </div>
            ))}
          </div>
        )}
        {error && <p className="mt-2 text-sm text-destructive">{error}</p>}
      </CardContent>
    </Card>
  );
}
