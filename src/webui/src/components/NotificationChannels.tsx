import { useCallback, useEffect, useState } from "react";
import { BellRing, Plus, RefreshCw, X } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * Where notifications go: channels as options, routes per category.
 *
 * **Options, not a vendor.** Email through any SMTP relay, Slack (incoming webhook or bot API),
 * Microsoft Teams, or a signed generic webhook. The credential — relay password, webhook URL, bot
 * token — is entered once and stored by reference; this page shows whether one is held.
 *
 * **Nothing is delivered before the destination proves itself.** A new channel receives a six-digit
 * code through itself; the administrator types it back. Until then it routes nothing (PRD-NTF-043).
 *
 * **Minimal by default.** External content is the subject and a link; a channel opts into detail
 * explicitly (PRD-NTF-032). The in-product channel is always on and needs no row here.
 */

interface Channel {
  id: string; code: string; displayName: string; kind: string; config: Record<string, unknown>;
  secretHeld: boolean; includeDetail: boolean; verified: boolean; verificationTarget: string | null;
  verificationSentAt: string | null; lifecycleState: string; lastStatus: string | null; lastDetail: string | null;
  consecutiveFailures: number; rowVersion: number;
}
interface Route { id: string; category: string; channelId: string; channelCode: string; recipientPrincipalId: string | null; address: string | null }
interface Category { code: string; label: string; mandatory: boolean }
interface CatalogueEvent { kind: string; category: string; label: string; digestible: boolean; mandatory: boolean; emitted: boolean }
interface Payload {
  rows: Channel[]; routes: Route[]; kinds: string[]; categories: Category[]; events: CatalogueEvent[];
  mayManage: boolean; elevated: boolean;
}
interface Delivery {
  id: string; status: string; attempts: number; failureClass: string | null; detail: string | null;
  address: string | null; eventKind: string; title: string; createdAt: string; sentAt: string | null;
}

const KIND_LABEL: Record<string, string> = {
  EMAIL_SMTP: "Email (SMTP relay)",
  SLACK_WEBHOOK: "Slack incoming webhook",
  SLACK_API: "Slack bot (chat.postMessage)",
  TEAMS_WEBHOOK: "Microsoft Teams webhook",
  GENERIC_WEBHOOK: "Generic signed webhook",
};

export function NotificationChannels() {
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [verifying, setVerifying] = useState<Channel | null>(null);
  const [history, setHistory] = useState<{ channel: Channel; rows: Delivery[] } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.get<Payload>("/api/ui/settings/notification-channels").then(setData).catch((e) => setError(e.message));
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

  async function showHistory(channel: Channel) {
    const d = await api.get<{ rows: Delivery[] }>(`/api/ui/settings/notification-channels/${channel.id}/deliveries`);
    setHistory({ channel, rows: d.rows });
  }

  if (error && !data) return <p className="text-sm text-destructive">{error}</p>;
  if (!data) return <p className="text-sm text-muted-foreground">Loading…</p>;

  return (
    <div className="flex flex-col gap-4">
      <Card className="overflow-hidden">
        <CardHeader className="flex-row items-start justify-between">
          <div>
            <CardTitle className="flex items-center gap-2"><BellRing className="size-4" /> Notification channels</CardTitle>
            <CardDescription>
              Email, Slack, Microsoft Teams or a signed webhook. The in-product channel is always on. A new
              destination receives a verification code through itself and delivers nothing until it is confirmed.
            </CardDescription>
          </div>
          {data.mayManage && (
            <Button size="sm" onClick={() => setAdding((v) => !v)}>
              {adding ? <><X className="size-4" /> Cancel</> : <><Plus className="size-4" /> Add channel</>}
            </Button>
          )}
        </CardHeader>
        {adding && <ChannelForm kinds={data.kinds} onDone={() => { setAdding(false); load(); }} />}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Channel</TableHead>
              <TableHead>Destination</TableHead>
              <TableHead>Verified</TableHead>
              <TableHead>Health</TableHead>
              <TableHead>State</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.rows.length === 0 && (
              <TableRow><TableCell colSpan={6} className="text-center text-sm text-muted-foreground">
                No external channel. Notifications appear in the bell menu only.
              </TableCell></TableRow>
            )}
            {data.rows.map((row) => (
              <TableRow key={row.id} className={row.lifecycleState === "RETIRED" ? "opacity-60" : ""}>
                <TableCell>
                  <div className="font-medium">{row.displayName}</div>
                  <div className="text-xs text-muted-foreground">{KIND_LABEL[row.kind] ?? row.kind} · <code className="font-mono">{row.code}</code></div>
                  <div className="mt-1 flex flex-wrap gap-1">
                    {row.secretHeld && <Badge tone="ok">credential held</Badge>}
                    {row.includeDetail ? <Badge tone="warn">sends detail</Badge> : <Badge tone="neutral">subject + link only</Badge>}
                  </div>
                </TableCell>
                <TableCell className="max-w-64 truncate font-mono text-xs">{destination(row)}</TableCell>
                <TableCell>{row.verified ? <Badge tone="ok">verified</Badge> : <Badge tone="warn">not verified</Badge>}</TableCell>
                <TableCell className="text-xs">
                  {row.lastStatus ? (
                    <span className={row.lastStatus === "SENT" || row.lastStatus === "VERIFICATION_SENT" ? "text-tone-ok" : "text-destructive"}>
                      {row.lastStatus}{row.consecutiveFailures > 0 ? ` · ${row.consecutiveFailures} failure(s) in a row` : ""}
                      {row.lastDetail ? <span className="block text-muted-foreground">{row.lastDetail}</span> : null}
                    </span>
                  ) : <span className="text-muted-foreground">nothing sent yet</span>}
                </TableCell>
                <TableCell><Badge tone={row.lifecycleState === "ACTIVE" ? "ok" : row.lifecycleState === "DISABLED" ? "warn" : "neutral"}>{row.lifecycleState}</Badge></TableCell>
                <TableCell className="text-right">
                  {data.mayManage && row.lifecycleState !== "RETIRED" && (
                    <div className="flex flex-wrap justify-end gap-1">
                      {!row.verified && (
                        <Button size="sm" variant="outline" disabled={busy} onClick={() => setVerifying(verifying?.id === row.id ? null : row)}>
                          Verify
                        </Button>
                      )}
                      <Button size="sm" variant="outline" disabled={busy} onClick={() => showHistory(row)}>
                        <RefreshCw className="size-3" /> Deliveries
                      </Button>
                      <Button size="sm" variant="outline" disabled={busy}
                              onClick={() => act(`/api/ui/settings/notification-channels/${row.id}/transition`,
                                { state: row.lifecycleState === "ACTIVE" ? "DISABLED" : "ACTIVE" })}>
                        {row.lifecycleState === "ACTIVE" ? "Disable" : "Enable"}
                      </Button>
                      <Button size="sm" variant="ghost" disabled={busy}
                              onClick={() => act(`/api/ui/settings/notification-channels/${row.id}/transition`, { state: "RETIRED" })}>Retire</Button>
                    </div>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {verifying && <VerifyPanel channel={verifying} onDone={() => { setVerifying(null); load(); }} />}
        {history && (
          <CardContent className="border-t bg-muted/20 py-4">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-sm font-medium">Recent deliveries — {history.channel.displayName}</span>
              <Button size="sm" variant="ghost" onClick={() => setHistory(null)}><X className="size-4" /></Button>
            </div>
            {history.rows.length === 0 ? <p className="text-xs text-muted-foreground">Nothing has been delivered on this channel yet.</p> : (
              <Table>
                <TableHeader><TableRow><TableHead>When</TableHead><TableHead>Event</TableHead><TableHead>To</TableHead><TableHead>Status</TableHead><TableHead>Detail</TableHead></TableRow></TableHeader>
                <TableBody>
                  {history.rows.map((d) => (
                    <TableRow key={d.id}>
                      <TableCell className="text-xs">{d.createdAt.replace("T", " ").slice(0, 16)}</TableCell>
                      <TableCell className="text-xs">{d.eventKind}</TableCell>
                      <TableCell className="font-mono text-xs">{d.address ?? "—"}</TableCell>
                      <TableCell><Badge tone={d.status === "SENT" ? "ok" : d.status === "QUEUED" || d.status === "LEASED" ? "info" : d.status === "SUPPRESSED" ? "neutral" : "critical"}>{d.status}</Badge></TableCell>
                      <TableCell className="text-xs text-muted-foreground">{d.attempts} attempt(s){d.failureClass ? ` · ${d.failureClass}` : ""}{d.detail ? ` · ${d.detail}` : ""}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        )}
        {error && <p className="px-4 pb-3 text-sm text-destructive">{error}</p>}
      </Card>

      <RoutesCard data={data} onChanged={load} />

      <Card>
        <CardHeader>
          <CardTitle>What can be notified</CardTitle>
          <CardDescription>
            The product's event catalogue (PRD-NTF-015). Which category goes where is yours; whether a category exists or
            can be muted is not. Events not yet emitted by the platform are listed so a route can be ready before they are.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {data.categories.map((c) => (
              <div key={c.code} className="rounded-md border p-2 text-xs">
                <div className="flex items-center gap-2 font-medium">
                  {c.label} {c.mandatory && <Badge tone="warn">mandatory</Badge>}
                </div>
                <ul className="mt-1 text-muted-foreground">
                  {data.events.filter((e) => e.category === c.code).map((e) => (
                    <li key={e.kind} className={e.emitted ? "" : "italic"}>{e.label}{e.emitted ? "" : " (not yet emitted)"}</li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

function destination(row: Channel): string {
  switch (row.kind) {
    case "EMAIL_SMTP": return `${row.config.host}:${row.config.port} (${row.config.tls_mode}) from ${row.config.from_address}`;
    case "SLACK_API": return `channel ${row.config.default_channel}`;
    case "GENERIC_WEBHOOK": return String(row.config.url ?? "");
    default: return "the credential is the destination";
  }
}

function ChannelForm({ kinds, onDone }: { kinds: string[]; onDone: () => void }) {
  const [kind, setKind] = useState(kinds.includes("EMAIL_SMTP") ? "EMAIL_SMTP" : kinds[0]);
  const [code, setCode] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [secret, setSecret] = useState("");
  const [includeDetail, setIncludeDetail] = useState(false);
  const [config, setConfig] = useState<Record<string, string>>({ port: "587", tls_mode: "STARTTLS" });
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  function field(key: string, label: string, placeholder = "", type = "text") {
    return (
      <div className="flex flex-col gap-1">
        <Label>{label}</Label>
        <Input type={type} value={config[key] ?? ""} placeholder={placeholder}
               onChange={(e) => setConfig((c) => ({ ...c, [key]: e.target.value }))} />
      </div>
    );
  }

  async function submit() {
    setBusy(true);
    setProblem(null);
    const cfg: Record<string, unknown> = { ...config };
    if (kind === "EMAIL_SMTP") cfg.port = Number(config.port || "587");
    try {
      await api.post("/api/ui/settings/notification-channels", { code, displayName, kind, config: cfg, secret: secret || undefined, includeDetail });
      onDone();
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  const secretLabel = kind === "EMAIL_SMTP" ? "SMTP password (if the relay authenticates)"
    : kind === "SLACK_WEBHOOK" ? "Slack incoming webhook URL"
    : kind === "SLACK_API" ? "Slack bot token (xoxb-…)"
    : kind === "TEAMS_WEBHOOK" ? "Teams incoming webhook URL"
    : "Signing key (optional, HMAC-SHA256 over the body)";

  return (
    <CardContent className="flex flex-col gap-3 border-y bg-muted/20 py-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <div className="flex flex-col gap-1">
          <Label>Kind</Label>
          <Select value={kind} onValueChange={setKind}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>{kinds.map((k) => <SelectItem key={k} value={k}>{KIND_LABEL[k] ?? k}</SelectItem>)}</SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label>Code</Label>
          <Input value={code} placeholder="ops-slack" onChange={(e) => setCode(e.target.value.toLowerCase())} />
        </div>
        <div className="flex flex-col gap-1">
          <Label>Shown as</Label>
          <Input value={displayName} placeholder="AppSec Slack" onChange={(e) => setDisplayName(e.target.value)} />
        </div>
        {kind === "EMAIL_SMTP" && (
          <>
            {field("host", "Relay host", "smtp.example.com")}
            {field("port", "Port", "587")}
            <div className="flex flex-col gap-1">
              <Label>Transport security</Label>
              <Select value={config.tls_mode ?? "STARTTLS"} onValueChange={(v) => setConfig((c) => ({ ...c, tls_mode: v }))}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="STARTTLS">STARTTLS (port 587)</SelectItem>
                  <SelectItem value="IMPLICIT">Implicit TLS (port 465)</SelectItem>
                  <SelectItem value="NONE">None — operator-allowlisted relay only</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {field("from_address", "From address", "aspm@example.com")}
            {field("from_name", "From name", "AI ASPM")}
            {field("username", "Username (optional)")}
            {field("reply_to", "Reply-To (optional)")}
          </>
        )}
        {kind === "SLACK_API" && field("default_channel", "Default channel id", "C0123456789")}
        {kind === "GENERIC_WEBHOOK" && field("url", "Webhook URL", "https://…")}
        <div className="flex flex-col gap-1">
          <Label>{secretLabel}</Label>
          <Input type="password" value={secret} autoComplete="off" onChange={(e) => setSecret(e.target.value)} />
          <span className="text-[11px] text-muted-foreground">Stored by reference in the secrets provider; never shown again.</span>
        </div>
      </div>
      <label className="flex items-center gap-2 text-sm">
        <Checkbox checked={includeDetail} onCheckedChange={(v) => setIncludeDetail(v === true)} />
        Include detail in messages (default is subject and link only — PRD-NTF-032)
      </label>
      <p className="text-[11px] text-muted-foreground">
        A public destination must be an https address outside private ranges; an internal mail relay must be listed by the
        operator (ASPM_SMTP_RELAYS). After saving, send the verification code and type it back — nothing else is delivered until then.
      </p>
      {problem && <p className="text-sm text-destructive">{problem}</p>}
      <div className="flex gap-2">
        <Button size="sm" disabled={busy || !code || !displayName} onClick={submit}>Add channel</Button>
        <Button size="sm" variant="ghost" onClick={onDone}>Cancel</Button>
      </div>
    </CardContent>
  );
}

function VerifyPanel({ channel, onDone }: { channel: Channel; onDone: () => void }) {
  const [target, setTarget] = useState(channel.verificationTarget ?? "");
  const [code, setCode] = useState("");
  const [sent, setSent] = useState(!!channel.verificationSentAt);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function send() {
    setBusy(true);
    setProblem(null);
    try {
      await api.post(`/api/ui/settings/notification-channels/${channel.id}/verify`, { target: target || undefined });
      setSent(true);
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  async function confirm() {
    setBusy(true);
    setProblem(null);
    try {
      await api.post(`/api/ui/settings/notification-channels/${channel.id}/confirm`, { code });
      onDone();
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <CardContent className="flex flex-col gap-3 border-t bg-muted/20 py-4">
      <div className="text-sm font-medium">Verify {channel.displayName}</div>
      <div className="grid gap-3 sm:grid-cols-3">
        {channel.kind === "EMAIL_SMTP" && (
          <div className="flex flex-col gap-1">
            <Label>Send the code to</Label>
            <Input value={target} placeholder="you@example.com" onChange={(e) => setTarget(e.target.value)} />
          </div>
        )}
        <div className="flex items-end">
          <Button size="sm" variant="outline" disabled={busy} onClick={send}>{sent ? "Send again" : "Send code"}</Button>
        </div>
        {sent && (
          <div className="flex items-end gap-2">
            <div className="flex flex-col gap-1">
              <Label>Six-digit code</Label>
              <Input value={code} inputMode="numeric" onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))} />
            </div>
            <Button size="sm" disabled={busy || code.length !== 6} onClick={confirm}>Confirm</Button>
          </div>
        )}
      </div>
      <p className="text-[11px] text-muted-foreground">The code is valid for fifteen minutes and is the only thing sent before the channel is verified.</p>
      {problem && <p className="text-sm text-destructive">{problem}</p>}
    </CardContent>
  );
}

function RoutesCard({ data, onChanged }: { data: Payload; onChanged: () => void }) {
  const active = data.rows.filter((c) => c.lifecycleState === "ACTIVE" && c.verified);
  const [routes, setRoutes] = useState<{ category: string; channelId: string; address: string }[]>(
    data.routes.filter((r) => !r.recipientPrincipalId).map((r) => ({ category: r.category, channelId: r.channelId, address: r.address ?? "" })));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    setRoutes(data.routes.filter((r) => !r.recipientPrincipalId).map((r) => ({ category: r.category, channelId: r.channelId, address: r.address ?? "" })));
  }, [data.routes]);

  async function save() {
    setBusy(true);
    setProblem(null);
    try {
      await api.post("/api/ui/settings/notification-routes", { routes: routes.filter((r) => r.category && r.channelId) });
      onChanged();
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Routes</CardTitle>
        <CardDescription>
          Which channel carries which category, for everyone in the tenant. A person may override a route or mute a
          non-mandatory category from their account page; mandatory categories cannot be muted (PRD-NTF-017).
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {active.length === 0 && <p className="text-xs text-muted-foreground">Verify a channel first; only verified, active channels can carry a route.</p>}
        {routes.map((r, i) => (
          <div key={i} className="grid gap-2 sm:grid-cols-[1fr_1fr_1fr_auto]">
            <Select value={r.category} onValueChange={(v) => setRoutes((was) => was.map((x, j) => (j === i ? { ...x, category: v } : x)))}>
              <SelectTrigger><SelectValue placeholder="Category" /></SelectTrigger>
              <SelectContent>{data.categories.map((c) => <SelectItem key={c.code} value={c.code}>{c.label}</SelectItem>)}</SelectContent>
            </Select>
            <Select value={r.channelId} onValueChange={(v) => setRoutes((was) => was.map((x, j) => (j === i ? { ...x, channelId: v } : x)))}>
              <SelectTrigger><SelectValue placeholder="Channel" /></SelectTrigger>
              <SelectContent>{active.map((c) => <SelectItem key={c.id} value={c.id}>{c.displayName}</SelectItem>)}</SelectContent>
            </Select>
            <Input value={r.address} placeholder="address override (optional)"
                   onChange={(e) => setRoutes((was) => was.map((x, j) => (j === i ? { ...x, address: e.target.value } : x)))} />
            <Button size="sm" variant="ghost" onClick={() => setRoutes((was) => was.filter((_, j) => j !== i))}><X className="size-4" /></Button>
          </div>
        ))}
        {data.mayManage && (
          <div className="flex gap-2">
            <Button size="sm" variant="outline" disabled={active.length === 0}
                    onClick={() => setRoutes((was) => [...was, { category: "", channelId: "", address: "" }])}>
              <Plus className="size-4" /> Add route
            </Button>
            <Button size="sm" disabled={busy} onClick={save}>Save routes</Button>
          </div>
        )}
        {problem && <p className="text-sm text-destructive">{problem}</p>}
      </CardContent>
    </Card>
  );
}
