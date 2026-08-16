import { useCallback, useEffect, useState } from "react";
import { KeyRound, Plus, X } from "lucide-react";
import { api } from "@/lib/api";
import { Pager, usePaging } from "@/components/Paging";
import { Kpi } from "@/components/Kpi";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * Ingestion credentials: the keys a build pipeline signs its SBOM submissions with.
 *
 * **Not an API key, and the difference is the point.** ADR-004 forbids bearer keys because a bearer
 * token is replayable from anywhere it leaks — a CI log, a shell history, a proxy that records
 * headers. What is issued here is a signing secret: it never crosses the wire, and each request
 * carries an HMAC over the method, path, body digest, a timestamp and a single-use nonce.
 *
 * **The secret is shown exactly once.** Only its digest is stored, so nothing can recover it
 * afterwards — not this page, not an administrator, not somebody reading a database dump. There is
 * deliberately no "show again": backing one would mean keeping the secret, which is the property
 * that makes this safer than a bearer key in the first place.
 */

interface Row {
  id: string; keyId: string; label: string; principalId: string; principalName: string | null;
  scopeNodeId: string; scopeNodeName: string | null; permissions: string[];
  expiresAt: string | null; lastUsedAt: string | null; createdAt: string | null;
  revokedAt: string | null; revokedReason: string | null;
}
interface Person { id: string; username: string; displayName: string }
interface Node { id: string; name: string }

export function ServiceCredentials() {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [mayManage, setMayManage] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [issuing, setIssuing] = useState(false);
  const [issued, setIssued] = useState<{ keyId: string; secret: string } | null>(null);
  // Which row is being revoked, and why. Inline rather than a browser prompt: window.prompt is
  // unstyled, unlabelled, cannot be cancelled with anything but its own button, and is invisible to
  // the rest of the design — it was the wrong control in an interface that edits in place everywhere
  // else. It also cannot show what revoking will DO, which is the part somebody needs before saying
  // yes.
  const [revoking, setRevoking] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const paging = usePaging(rows ?? []);

  const load = useCallback(() => {
    api.get<{ rows: Row[]; mayManage: boolean }>("/api/ui/service-credentials")
      .then((d) => { setRows(d.rows); setMayManage(d.mayManage); })
      .catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  async function revoke(row: Row) {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/ui/service-credentials/${row.id}/revoke`, { reason });
      setRevoking(null);
      setReason("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <div className="flex flex-col gap-4">
      {/* The secret, once. Rendered above everything so it cannot be scrolled past. */}
      {issued && (
        <Card className="border-tone-warn">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <KeyRound className="size-4" /> Copy this secret now
            </CardTitle>
            <CardDescription>
              It is shown once and never again — only its digest is stored, so nothing can recover it.
              A lost secret is replaced, not recovered: issue a new key, then revoke this one, in that
              order, so the pipeline never has a gap.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-2">
            <div className="text-xs text-muted-foreground">Key identifier (safe to log)</div>
            <code className="rounded bg-muted px-2 py-1 font-mono text-xs">{issued.keyId}</code>
            <div className="mt-2 text-xs text-muted-foreground">Secret</div>
            <code className="break-all rounded bg-muted px-2 py-1 font-mono text-xs">
              {issued.secret}
            </code>
            <p className="mt-1 text-[11px] text-muted-foreground">
              The signing key is the SHA-256 of this secret, not the secret itself:
              {" "}<code className="font-mono">
                openssl dgst -sha256 -binary &lt;&lt;&lt; "$SECRET" | xxd -p -c 64
              </code>
            </p>
            <div>
              <Button size="sm" variant="outline" onClick={() => setIssued(null)}>
                I have copied it
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      <Card className="overflow-hidden">
        <CardHeader className="flex-row items-start justify-between">
          <div>
            <CardTitle>Ingestion credentials</CardTitle>
            <CardDescription>
              Signing keys for pipelines pushing bills of materials. Each is pinned to one
              organization at issue and cannot address anything outside it.
            </CardDescription>
          </div>
          {mayManage && (
            <Button size="sm" onClick={() => setIssuing(true)}><Plus /> Issue a key</Button>
          )}
        </CardHeader>
        {error && <CardContent className="text-sm text-destructive">{error}</CardContent>}
        {issuing && (
          <IssueForm onCancel={() => setIssuing(false)}
                     onIssued={(v) => { setIssuing(false); setIssued(v); load(); }} />
        )}
        {rows !== null && rows.length === 0 ? (
          <CardContent className="py-8 text-center text-sm text-muted-foreground">
            No ingestion credential has been issued. Until one is, no pipeline can submit an SBOM —
            the submission endpoint refuses anything that is not a service credential.
          </CardContent>
        ) : (
          <>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Key</TableHead>
                  <TableHead>Acts as</TableHead>
                  <TableHead>Organization</TableHead>
                  <TableHead>May do</TableHead>
                  <TableHead>Last used</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {paging.rows.map((r) => (
                  <>
                  <TableRow key={r.id}>
                    <TableCell>
                      <div className="font-mono text-[11px]">{r.keyId}</div>
                      <div className="text-xs">{r.label}</div>
                    </TableCell>
                    <TableCell className="text-xs">{r.principalName ?? "—"}</TableCell>
                    {/* Named, not blank. A null shown as an em dash reads as "unset"; it means
                        "everything", which is the broadest thing this row can say. */}
                    <TableCell className="text-xs">
                      {r.scopeNodeName ?? <Badge tone="warn">the whole tenant</Badge>}
                    </TableCell>
                    <TableCell className="text-[11px] text-muted-foreground">
                      {r.permissions.length === 0
                        ? "everything its identity holds"
                        : r.permissions.join(", ")}
                    </TableCell>
                    <TableCell className="font-mono text-[11px]">
                      {/* Observed, not assumed. A key nobody has used is one somebody can revoke
                          without breaking a pipeline, and that is only knowable if it is recorded. */}
                      {r.lastUsedAt ?? <span className="italic text-tone-unknown">never used</span>}
                    </TableCell>
                    <TableCell>
                      {r.revokedAt
                        ? <>
                            <Badge tone="neutral">revoked {r.revokedAt}</Badge>
                            {r.revokedReason && (
                              <div className="text-[11px] text-muted-foreground">{r.revokedReason}</div>
                            )}
                          </>
                        : r.expiresAt
                          ? <Badge tone="ok">expires {r.expiresAt}</Badge>
                          : <Badge tone="warn">no expiry</Badge>}
                    </TableCell>
                    <TableCell className="text-right">
                      {mayManage && !r.revokedAt && (
                        <Button variant="outline" size="sm"
                                onClick={() => { setRevoking(revoking === r.id ? null : r.id);
                                                 setReason(""); }}>
                          Revoke
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                  {revoking === r.id && (
                    <TableRow key={`${r.id}-revoke`}>
                      <TableCell colSpan={7} className="bg-muted/30">
                        <div className="flex flex-col gap-2 py-1">
                          <div className="text-sm">
                            Revoke <span className="font-mono text-xs">{r.keyId}</span>?
                          </div>
                          {/* What it will DO, before the button. Somebody revoking a key at 2am
                              needs to know it takes effect on the next request and that nothing
                              re-enables it — not to find out afterwards. */}
                          <ul className="ml-4 list-disc text-xs text-muted-foreground">
                            <li>Every request signed with it is refused from the next one onward.</li>
                            <li>Any pipeline using it stops submitting, silently, until it is given
                                a new key — issue the replacement first if you cannot afford a gap.</li>
                            <li>The row stays, revoked. There is no undo and no delete: what a key
                                was permitted to do while it existed is what an incident review
                                reads.</li>
                            {/* Said BEFORE the button. Revoking is class E and needs a fresh second
                                factor, so the browser leaves this page to ask for one and comes
                                back. Somebody who is not told that reads the jump as the click
                                having failed. */}
                            <li>You may be asked for your second factor first — revoking a credential
                                is a change to who can reach the platform, and it comes back here
                                afterwards.</li>
                          </ul>
                          <div className="flex flex-wrap items-end gap-2">
                            <div className="flex min-w-72 flex-1 flex-col gap-1">
                              <Label htmlFor={`reason-${r.id}`}>Why (recorded against the key)</Label>
                              <Input id={`reason-${r.id}`} value={reason} autoFocus
                                     placeholder="Rotated / pipeline decommissioned / suspected leak"
                                     onChange={(e) => setReason(e.target.value)}
                                     onKeyDown={(e) => {
                                       if (e.key === "Enter" && reason.trim()) revoke(r);
                                       if (e.key === "Escape") setRevoking(null);
                                     }} />
                            </div>
                            <Button size="sm" variant="destructive" disabled={busy || !reason.trim()}
                                    onClick={() => revoke(r)}>
                              Revoke this key
                            </Button>
                            <Button size="sm" variant="ghost"
                                    onClick={() => setRevoking(null)}>Cancel</Button>
                          </div>
                          {!reason.trim() && (
                            <p className="text-[11px] text-muted-foreground">
                              A reason is required. A revocation nobody can explain is
                              indistinguishable from one somebody made by mistake.
                            </p>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  )}
                  </>
                ))}
              </TableBody>
            </Table>
            <Pager paging={paging} unit="keys" />
          </>
        )}
      </Card>
    </div>
  );
}

const ALL = "__all__";

/** One permission the catalogue defines. Both flags come from the catalogue, not from this screen. */
interface CataloguePermission {
  code: string; label: string; restricted: boolean; requiresStepUp: boolean;
}
interface PermissionGroup { group: string; permissions: CataloguePermission[] }

function IssueForm({ onIssued, onCancel }: {
  onIssued: (v: { keyId: string; secret: string }) => void; onCancel: () => void;
}) {
  const [label, setLabel] = useState("");
  const [principalId, setPrincipalId] = useState("");
  const [scopeNodeId, setScopeNodeId] = useState("");
  const [expiresInDays, setExpiresInDays] = useState("90");
  const [people, setPeople] = useState<Person[]>([]);
  const [nodes, setNodes] = useState<Node[]>([]);
  const [catalogue, setCatalogue] = useState<PermissionGroup[]>([]);
  // Nothing ticked to begin with. The form used to send one hardcoded permission whatever the
  // credential was for, so every credential in this deployment carries `sbm.sbom.submit` and a
  // pipeline that needed to submit scan results could not be given the permission to do it.
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    // `users`, not `rows`. Reading the wrong key left the picker silently empty and the Issue
    // button permanently disabled, with nothing on screen saying why — the failure mode of a
    // dropdown that renders fine with no options.
    api.get<{ users: Person[] }>("/api/ui/access").then((d) => setPeople(d.users ?? []))
      .catch(() => setPeople([]));
    api.get<{ nodes: Node[] }>("/api/ui/applications").then((d) => setNodes(d.nodes ?? []))
      .catch(() => setNodes([]));
    api.get<{ permissionCatalogue: PermissionGroup[] }>("/api/ui/access")
      .then((d) => setCatalogue(d.permissionCatalogue ?? [])).catch(() => setCatalogue([]));
  }, []);

  async function submit() {
    setBusy(true);
    setProblem(null);
    try {
      const v = await api.post<{ keyId: string; secret: string }>("/api/ui/service-credentials", {
        label, principalId, scopeNodeId,
        permissions: [...chosen],
        expiresInDays: expiresInDays ? Number(expiresInDays) : null,
      });
      onIssued(v);
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <CardContent className="flex flex-col gap-3 border-y bg-muted/20 py-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div className="flex flex-col gap-1">
          <Label>What is it for</Label>
          <Input value={label} placeholder="GSM build pipeline"
                 onChange={(e) => setLabel(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label>Acts as</Label>
          <Select value={principalId} onValueChange={setPrincipalId}>
            <SelectTrigger><SelectValue placeholder="Choose an identity" /></SelectTrigger>
            <SelectContent>
              {people.map((p) => (
                <SelectItem key={p.id} value={p.id}>{p.displayName || p.username}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label>Pinned to</Label>
          {/* "Everything" is a real answer for a central build platform, and this estate has six
              root organizations — so it cannot be expressed by picking one. It is only offered to
              an administrator whose own scope already reaches every node; the server refuses it
              otherwise, and this option simply fails there rather than being hidden. */}
          <Select value={scopeNodeId || ALL} onValueChange={(v) => setScopeNodeId(v === ALL ? "" : v)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>Everything you can reach</SelectItem>
              {nodes.map((n) => <SelectItem key={n.id} value={n.id}>{n.name}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label>Expires in (days)</Label>
          <Input value={expiresInDays} inputMode="numeric" placeholder="leave blank for no expiry"
                 onChange={(e) => setExpiresInDays(e.target.value.replace(/[^0-9]/g, ""))} />
        </div>
      </div>
      <div className="flex flex-col gap-2">
        <Label>What it may do</Label>
        {/* The catalogue, grouped as the catalogue groups it. A flat list of every code is a list
            nobody reads before ticking, and the grouping is a fact about the catalogue rather than a
            taxonomy invented for this screen. */}
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {catalogue.map((group) => (
            <div key={group.group} className="flex flex-col gap-1 rounded-md border p-2">
              <span className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                {group.group}
              </span>
              {group.permissions.map((p) => (
                <label key={p.code} className="flex items-start gap-2 text-xs">
                  <Checkbox checked={chosen.has(p.code)}
                            onCheckedChange={() => setChosen((was) => {
                              const next = new Set(was);
                              if (!next.delete(p.code)) {
                                next.add(p.code);
                              }
                              return next;
                            })} />
                  <span className="min-w-0">
                    <span className="block truncate">{p.label}</span>
                    <span className="block font-mono text-[10px] text-muted-foreground">{p.code}</span>
                    {/* The one warning that is specific to a KEY rather than to a role. A service
                        credential is never step-up authenticated, so a permission that demands a
                        second factor can be ticked here and will refuse on every call — a key that
                        looks configured and does nothing. */}
                    {p.requiresStepUp && (
                      <span className="block text-[10px] text-tone-warn">
                        needs a second factor — a key can never satisfy it
                      </span>
                    )}
                    {p.restricted && !p.requiresStepUp && (
                      <span className="block text-[10px] text-tone-warn">restricted data</span>
                    )}
                  </span>
                </label>
              ))}
            </div>
          ))}
        </div>
      </div>
      <p className="text-[11px] text-muted-foreground">
        The key can never exercise more than the identity behind it holds — the two are intersected on
        every request, so revoking that identity's role revokes the key's reach. Ticking a permission
        the identity does not hold therefore grants nothing. The organization is re-read against your
        own scope before the key is written: you cannot issue one for a division you cannot reach.
      </p>
      {problem && <p className="text-sm text-destructive">{problem}</p>}
      {/* A disabled button that does not say why is a dead control. Naming what is missing is the
          difference between "this is broken" and "I have not finished filling it in". */}
      {(!label || !principalId || chosen.size === 0) && (
        <p className="text-xs text-muted-foreground">
          Still needed: {[!label && "a name", !principalId && "an identity to act as",
                          chosen.size === 0 && "at least one permission"]
            .filter(Boolean).join(", ")}.
          {people.length === 0 && " No identity is listed — you need iam.user.read to choose one."}
        </p>
      )}
      <div className="flex gap-2">
        {/* A key with no permission is a credential that authenticates and can do nothing, which
            reads as a broken integration rather than as an empty form. */}
        <Button size="sm" disabled={busy || !label || !principalId || chosen.size === 0}
                onClick={submit}>Issue</Button>
        <Button size="sm" variant="ghost" onClick={onCancel}><X /> Cancel</Button>
      </div>
    </CardContent>
  );
}

// ================================================================================================
// Vulnerability alerts
// ================================================================================================

interface Alert {
  id: string; label: string; url: string; minSeverityOrdinal: number;
  minSeverityCode: string | null; scopeNodeId: string | null; scopeNodeName: string | null;
  active: boolean; lastDeliveryAt: string | null; lastStatus: string | null;
  consecutiveFailures: number; signed: boolean;
}

/**
 * Where to send word of a newly detected vulnerability, and how severe it has to be.
 *
 * **The threshold is per subscription.** The team who owns payments wants everything; a group
 * security channel wants criticals only. One global setting forces the second to filter noise they
 * never asked for, and the predictable result is a muted channel — worse than no alert, because it
 * is believed to be working.
 */
export function AlertSubscriptions() {
  const [rows, setRows] = useState<Alert[] | null>(null);
  const [mayManage, setMayManage] = useState(false);
  const [nodes, setNodes] = useState<Node[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [label, setLabel] = useState("");
  const [url, setUrl] = useState("");
  const [ordinal, setOrdinal] = useState("2");
  const [scopeNodeId, setScopeNodeId] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.get<{ rows: Alert[]; mayManage: boolean }>("/api/ui/alerts")
      .then((d) => { setRows(d.rows); setMayManage(d.mayManage); })
      .catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load]);
  useEffect(() => {
    api.get<{ nodes: Node[] }>("/api/ui/applications").then((d) => setNodes(d.nodes ?? []))
      .catch(() => setNodes([]));
  }, []);

  async function create() {
    setBusy(true); setError(null);
    try {
      const v = await api.post<{ secret: string }>("/api/ui/alerts", {
        label, url, minSeverityOrdinal: Number(ordinal),
        scopeNodeId: scopeNodeId || null,
      });
      setSecret(v.secret); setLabel(""); setUrl(""); load();
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  async function toggle(row: Alert) {
    try { await api.post(`/api/ui/alerts/${row.id}/active`, { active: !row.active }); load(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }

  return (
    <Card className="overflow-hidden">
      <CardHeader>
        <CardTitle>Vulnerability alerts</CardTitle>
        <CardDescription>
          Called when a submitted bill of materials reveals a vulnerability the platform had not seen
          in that place before. Only newly detected ones — a nightly pipeline resubmitting an
          unchanged document does not re-announce the same advisories every night.
        </CardDescription>
      </CardHeader>

      {secret && (
        <CardContent className="border-y bg-muted/20 py-4">
          <div className="text-xs text-muted-foreground">Signing secret — shown once</div>
          <code className="break-all rounded bg-muted px-2 py-1 font-mono text-xs">{secret}</code>
          <p className="mt-2 text-[11px] text-muted-foreground">
            Every delivery carries <code className="font-mono">X-ASPM-Signature: sha256=…</code>, an
            HMAC of the body with this secret. Verify it: a URL is not a secret, and anything that
            discovers yours can otherwise post to it.
          </p>
          <Button size="sm" variant="outline" className="mt-2" onClick={() => setSecret(null)}>
            I have copied it
          </Button>
        </CardContent>
      )}

      {mayManage && (
        <CardContent className="flex flex-col gap-3 border-b py-4">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <div className="flex flex-col gap-1">
              <Label>Name</Label>
              <Input value={label} placeholder="Payments security channel"
                     onChange={(e) => setLabel(e.target.value)} />
            </div>
            <div className="flex flex-col gap-1">
              <Label>HTTPS endpoint</Label>
              <Input value={url} placeholder="https://hooks.example.com/…"
                     onChange={(e) => setUrl(e.target.value)} />
            </div>
            <div className="flex flex-col gap-1">
              <Label>Alert at or above</Label>
              <Select value={ordinal} onValueChange={setOrdinal}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="1">Critical only</SelectItem>
                  <SelectItem value="2">High and above</SelectItem>
                  <SelectItem value="3">Medium and above</SelectItem>
                  <SelectItem value="4">Everything rated</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1">
              <Label>Covering</Label>
              <Select value={scopeNodeId || "__all__"}
                      onValueChange={(v) => setScopeNodeId(v === "__all__" ? "" : v)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="__all__">Everything you can reach</SelectItem>
                  {nodes.map((n) => <SelectItem key={n.id} value={n.id}>{n.name}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
          </div>
          <p className="text-[11px] text-muted-foreground">
            The endpoint must be https and must not resolve to a private, loopback or link-local
            address — checked again immediately before every delivery, not only when saved. A webhook
            pointed at a metadata service is server-side request forgery, which is the defect class
            this product exists to find in other people's software.
            {" "}An advisory nobody has rated reaches nobody by threshold: waking somebody for a
            vulnerability with no severity is how a channel gets muted.
          </p>
          {error && <p className="text-sm text-destructive">{error}</p>}
          <div>
            <Button size="sm" disabled={busy || !label || !url} onClick={create}>
              <Plus /> Add subscription
            </Button>
          </div>
        </CardContent>
      )}

      {rows !== null && rows.length === 0 ? (
        <CardContent className="py-8 text-center text-sm text-muted-foreground">
          Nothing is subscribed. A new critical vulnerability in the estate is discovered when
          somebody next opens this dashboard.
        </CardContent>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Subscription</TableHead>
              <TableHead>Threshold</TableHead>
              <TableHead>Covering</TableHead>
              <TableHead>Last delivery</TableHead>
              <TableHead>State</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {(rows ?? []).map((r) => (
              <TableRow key={r.id}>
                <TableCell>
                  <div className="text-sm">{r.label}</div>
                  <div className="max-w-72 truncate font-mono text-[11px] text-muted-foreground">
                    {r.url}
                  </div>
                </TableCell>
                <TableCell className="text-xs">
                  {r.minSeverityCode ?? `rank ${r.minSeverityOrdinal}`} and above
                </TableCell>
                <TableCell className="text-xs">{r.scopeNodeName ?? "the whole estate"}</TableCell>
                <TableCell className="font-mono text-[11px]">
                  {/* Never delivered is a fact, and a more useful one than a blank: it means either
                      new or broken, and the failure count beside it says which. */}
                  {r.lastDeliveryAt
                    ? <>{r.lastDeliveryAt}<div className="text-muted-foreground">{r.lastStatus}</div></>
                    : <span className="italic text-tone-unknown">never delivered</span>}
                </TableCell>
                <TableCell>
                  {r.active
                    ? <Badge tone="ok">active</Badge>
                    : <Badge tone="warn">off</Badge>}
                  {r.consecutiveFailures > 0 && (
                    <div className="text-[11px] text-tone-warn">
                      {r.consecutiveFailures} consecutive failures
                    </div>
                  )}
                </TableCell>
                <TableCell className="text-right">
                  {mayManage && (
                    <Button variant="outline" size="sm" onClick={() => toggle(r)}>
                      {r.active ? "Turn off" : "Turn on"}
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Card>
  );
}

// ================================================================================================
// Scheduled re-scanning
// ================================================================================================

interface ScheduleState {
  enabled: boolean; intervalHours: number; batchSize: number; lastTickAt: string | null;
  due: number; unscannable: number; total: number;
}

/**
 * How often stored bills of materials are re-checked against a fresh vulnerability database.
 *
 * **Why this exists at all.** Without it a component's advisory list only ever changed when a
 * pipeline re-pushed — so a repository that stopped building kept its vulnerability picture frozen,
 * and a CVE published after its last push was invisible for ever.
 *
 * **Hours, not a cron expression.** "Not older than N hours" is the property anybody actually wants;
 * a cron string is a small language to validate, to explain here, and to get wrong in a timezone.
 */
export function RescanSchedule() {
  const [state, setState] = useState<ScheduleState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.get<ScheduleState>("/api/ui/rescan-schedule").then(setState)
      .catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  async function save(next: Partial<ScheduleState>) {
    if (!state) return;
    setBusy(true);
    setError(null);
    try {
      setState(await api.post<ScheduleState>("/api/ui/rescan-schedule", {
        enabled: next.enabled ?? state.enabled,
        intervalHours: next.intervalHours ?? state.intervalHours,
        batchSize: next.batchSize ?? state.batchSize,
      }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Scheduled re-scanning</CardTitle>
        <CardDescription>
          Re-checks bills of materials already submitted against a fresh vulnerability database, so a
          CVE published after a repository's last build is found without waiting for the next one.
          The scanner runs in its own container and asks this schedule what is due.
        </CardDescription>
      </CardHeader>
      {error && <CardContent className="text-sm text-destructive">{error}</CardContent>}
      {state === null ? (
        <CardContent className="text-sm text-muted-foreground">Loading…</CardContent>
      ) : (
        <CardContent className="flex flex-col gap-4">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <div className="flex flex-col gap-1">
              <Label>Re-scanning</Label>
              <Select value={state.enabled ? "on" : "off"} disabled={busy}
                      onValueChange={(v) => save({ enabled: v === "on" })}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="on">On</SelectItem>
                  <SelectItem value="off">Off</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1">
              <Label>Re-check anything older than</Label>
              <Select value={String(state.intervalHours)} disabled={busy}
                      onValueChange={(v) => save({ intervalHours: Number(v) })}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="6">6 hours</SelectItem>
                  <SelectItem value="12">12 hours</SelectItem>
                  <SelectItem value="24">a day</SelectItem>
                  <SelectItem value="168">a week</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1">
              <Label>At most per pass</Label>
              <Select value={String(state.batchSize)} disabled={busy}
                      onValueChange={(v) => save({ batchSize: Number(v) })}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {[10, 25, 50, 100].map((n) => (
                    <SelectItem key={n} value={String(n)}>{n} repositories</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1">
              <Label>Scanner last asked</Label>
              <div className="pt-1.5 font-mono text-xs">
                {/* Observed, not assumed. "Never" here means the container is not running or cannot
                    authenticate, and it is the only way to tell that from "nothing was due". */}
                {state.lastTickAt ?? (
                  <span className="italic text-tone-unknown">never — is the scanner running?</span>
                )}
              </div>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
            <Kpi label="Due now" value={state.due}
                 tone={state.enabled && state.due > 0 ? "info" : "neutral"}
                 hint={state.enabled ? "picked up on the next pass" : "re-scanning is off"} />
            <Kpi label="Re-scannable" value={state.total - state.unscannable}
                 hint={`of ${state.total} repositories with a bill of materials`} />
            {/* The number that must never be hidden. These predate document archiving: their bytes
                were never kept, so they can NEVER be re-checked — and an empty "due" queue would
                read as "everything is up to date". */}
            <Kpi label="Can never be re-scanned" value={state.unscannable} tone="warn"
                 hint="submitted before documents were archived — they need one more push" />
          </div>

          {state.unscannable > 0 && (
            <p className="text-xs text-muted-foreground">
              Those {state.unscannable} were submitted before the platform kept the original
              document, so there is nothing to hand a scanner. They are not stale by choice and
              cannot be repaired here — each is fixed the next time its pipeline pushes.
            </p>
          )}
          {!state.enabled && (
            <p className="text-xs text-tone-warn">
              While this is off, a component's vulnerability list only changes when its pipeline
              pushes again. A CVE published after the last build stays invisible.
            </p>
          )}
        </CardContent>
      )}
    </Card>
  );
}
