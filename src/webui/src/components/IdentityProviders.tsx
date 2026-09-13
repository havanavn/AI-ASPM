import { useCallback, useEffect, useState } from "react";
import { Fingerprint, Plus, RefreshCw, X } from "lucide-react";
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
 * Federated identity providers: who may sign in, decided by the tenant's own directory.
 *
 * **Options, not a vendor.** Okta, Microsoft Entra ID, Keycloak, Google Workspace, or any OpenID
 * Connect provider. A preset fills the claim mapping with what the platform knows about a provider;
 * everything it fills is editable, because two subsidiaries' Okta tenants are not configured alike.
 *
 * **The client secret is entered once and never shown.** It is stored in the deployment's secrets
 * provider and the row keeps only a reference; this page shows whether one is held and offers to
 * replace it. A row with no secret is a public client using PKCE alone.
 *
 * **No role name is written here** (ADR-027). Group-to-role mappings pick from whatever roles the
 * tenant defined, and the mapping is evaluated at every sign-in — a group the person left revokes
 * what the mapping granted, and never what an administrator granted by hand (ADR-041).
 */

interface GroupRole { id: string; group: string; roleId: string; roleCode: string; scopeNodeId: string | null; scopeMode: string }
interface Row {
  id: string; code: string; displayName: string; preset: string; presetLabel: string; issuer: string;
  discoveryUrl: string | null; clientId: string; clientSecretHeld: boolean; scopes: string[];
  claimSubject: string; claimUsername: string; claimEmail: string; claimDisplayName: string; claimGroups: string | null;
  jitProvisioning: boolean; allowedEmailDomains: string[]; mfaAssertedByProvider: boolean;
  lifecycleState: string; lastTestStatus: string | null; lastTestDetail: string | null; rowVersion: number;
  startUrl: string; groupRoles: GroupRole[];
}
interface Preset {
  code: string; label: string; scopes: string[]; claimSubject: string; claimUsername: string; claimEmail: string;
  claimDisplayName: string; claimGroups: string | null; issuerHint: string;
}
interface Payload {
  rows: Row[]; presets: Preset[]; redirectUri: string | null; federationConfigured: boolean;
  localSignInEnabled: boolean; mayManage: boolean; elevated: boolean;
}
interface RoleOption { id: string; code: string; label: string }

export function IdentityProviders() {
  const [data, setData] = useState<Payload | null>(null);
  const [roles, setRoles] = useState<RoleOption[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Row | null>(null);
  const [mapping, setMapping] = useState<Row | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Payload>("/api/ui/access/identity-providers").then(setData).catch((e) => setError(e.message));
    api.get<{ roles: RoleOption[] }>("/api/ui/access").then((d) => setRoles(d.roles ?? [])).catch(() => setRoles([]));
  }, []);
  useEffect(load, [load]);

  async function act(row: Row, what: string, body: unknown) {
    setBusy(row.id + what);
    setError(null);
    try {
      await api.post(`/api/ui/access/identity-providers/${row.id}/${what}`, body);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(null); }
  }

  async function setLocal(enabled: boolean) {
    setBusy("local");
    setError(null);
    try {
      await api.post("/api/ui/access/local-sign-in", { enabled });
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(null); }
  }

  if (error && !data) return <p className="text-sm text-destructive">{error}</p>;
  if (!data) return <p className="text-sm text-muted-foreground">Loading…</p>;

  return (
    <div className="flex flex-col gap-4">
      {!data.federationConfigured && (
        <Card className="border-tone-warn">
          <CardContent className="py-3 text-sm">
            Federated sign-in is <strong>configured but not active</strong>: the deployment has no
            <code className="mx-1 font-mono text-xs">ASPM_PUBLIC_BASE_URL</code>, so no redirect URI can be
            registered at a provider. Providers can be prepared here; the sign-in buttons appear once the
            operator sets it.
          </CardContent>
        </Card>
      )}

      <Card className="overflow-hidden">
        <CardHeader className="flex-row items-start justify-between">
          <div>
            <CardTitle className="flex items-center gap-2"><Fingerprint className="size-4" /> Identity providers</CardTitle>
            <CardDescription>
              Okta, Microsoft Entra ID, Keycloak, Google Workspace, or any OpenID Connect provider. A
              provider decides who may sign in, so every change needs a fresh second factor.
              {data.redirectUri && (
                <> Register this redirect URI at each provider:{" "}
                  <code className="rounded bg-muted px-1 font-mono text-xs">{data.redirectUri}</code>
                </>
              )}
            </CardDescription>
          </div>
          {data.mayManage && (
            <Button size="sm" onClick={() => { setAdding((v) => !v); setEditing(null); }}>
              {adding ? <><X className="size-4" /> Cancel</> : <><Plus className="size-4" /> Add provider</>}
            </Button>
          )}
        </CardHeader>
        {adding && (
          <ProviderForm presets={data.presets} onDone={() => { setAdding(false); load(); }} />
        )}
        {editing && (
          <ProviderForm presets={data.presets} existing={editing} onDone={() => { setEditing(null); load(); }} />
        )}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Provider</TableHead>
              <TableHead>Issuer</TableHead>
              <TableHead>Sign-in</TableHead>
              <TableHead>Last test</TableHead>
              <TableHead>State</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.rows.length === 0 && (
              <TableRow><TableCell colSpan={6} className="text-center text-sm text-muted-foreground">
                No identity provider is configured. Sign-in is by local password and authenticator only.
              </TableCell></TableRow>
            )}
            {data.rows.map((row) => (
              <TableRow key={row.id} className={row.lifecycleState === "RETIRED" ? "opacity-60" : ""}>
                <TableCell>
                  <div className="font-medium">{row.displayName}</div>
                  <div className="text-xs text-muted-foreground">{row.presetLabel} · <code className="font-mono">{row.code}</code></div>
                  <div className="mt-1 flex flex-wrap gap-1">
                    {row.mfaAssertedByProvider && <Badge tone="info">MFA at provider</Badge>}
                    {row.jitProvisioning && <Badge tone="info">creates accounts</Badge>}
                    {row.clientSecretHeld ? <Badge tone="ok">secret held</Badge> : <Badge tone="neutral">public client (PKCE)</Badge>}
                    {row.groupRoles.length > 0 && <Badge tone="neutral">{row.groupRoles.length} group mapping(s)</Badge>}
                  </div>
                </TableCell>
                <TableCell className="max-w-64 truncate font-mono text-xs" title={row.issuer}>{row.issuer}</TableCell>
                <TableCell className="font-mono text-xs">{row.lifecycleState === "ACTIVE" ? row.startUrl : "—"}</TableCell>
                <TableCell className="text-xs">
                  {row.lastTestStatus ? (
                    <span className={row.lastTestStatus === "OK" ? "text-tone-ok" : "text-destructive"}>
                      {row.lastTestStatus}{row.lastTestDetail ? ` — ${row.lastTestDetail}` : ""}
                    </span>
                  ) : <span className="text-muted-foreground">never</span>}
                </TableCell>
                <TableCell><Badge tone={row.lifecycleState === "ACTIVE" ? "ok" : row.lifecycleState === "DISABLED" ? "warn" : "neutral"}>{row.lifecycleState}</Badge></TableCell>
                <TableCell className="text-right">
                  {data.mayManage && row.lifecycleState !== "RETIRED" && (
                    <div className="flex flex-wrap justify-end gap-1">
                      <Button size="sm" variant="outline" disabled={busy !== null}
                              onClick={() => act(row, "test", {})}>
                        <RefreshCw className="size-3" /> Test
                      </Button>
                      <Button size="sm" variant="outline" disabled={busy !== null}
                              onClick={() => { setEditing(row); setAdding(false); }}>Edit</Button>
                      <Button size="sm" variant="outline" disabled={busy !== null}
                              onClick={() => setMapping(mapping?.id === row.id ? null : row)}>Groups → roles</Button>
                      <Button size="sm" variant="outline" disabled={busy !== null}
                              onClick={() => act(row, "transition", { state: row.lifecycleState === "ACTIVE" ? "DISABLED" : "ACTIVE" })}>
                        {row.lifecycleState === "ACTIVE" ? "Disable" : "Enable"}
                      </Button>
                      <Button size="sm" variant="ghost" disabled={busy !== null}
                              onClick={() => act(row, "transition", { state: "RETIRED" })}>Retire</Button>
                    </div>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {mapping && (
          <GroupRoleEditor row={data.rows.find((r) => r.id === mapping.id) ?? mapping} roles={roles}
                           onDone={() => { setMapping(null); load(); }} />
        )}
        {error && <p className="px-4 pb-3 text-sm text-destructive">{error}</p>}
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Local sign-in</CardTitle>
          <CardDescription>
            Passwords are for a deployment without a provider, and for break-glass. Once a provider is
            active and at least one account is marked for break-glass on its user page, local sign-in can
            be turned off for everybody else — the server refuses the password, whatever the page shows.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex items-center gap-3">
          <Badge tone={data.localSignInEnabled ? "neutral" : "warn"}>
            {data.localSignInEnabled ? "enabled" : "break-glass only"}
          </Badge>
          {data.mayManage && (
            <Button size="sm" variant="outline" disabled={busy !== null}
                    onClick={() => setLocal(!data.localSignInEnabled)}>
              {data.localSignInEnabled ? "Restrict to break-glass" : "Enable for everyone"}
            </Button>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function ProviderForm({ presets, existing, onDone }: { presets: Preset[]; existing?: Row; onDone: () => void }) {
  const [preset, setPreset] = useState(existing?.preset ?? "GENERIC_OIDC");
  const chosen = presets.find((p) => p.code === preset) ?? presets[0];
  const [code, setCode] = useState(existing?.code ?? "");
  const [displayName, setDisplayName] = useState(existing?.displayName ?? "");
  const [issuer, setIssuer] = useState(existing?.issuer ?? "");
  const [discoveryUrl, setDiscoveryUrl] = useState(existing?.discoveryUrl ?? "");
  const [clientId, setClientId] = useState(existing?.clientId ?? "");
  const [clientSecret, setClientSecret] = useState("");
  const [claimUsername, setClaimUsername] = useState(existing?.claimUsername ?? chosen?.claimUsername ?? "preferred_username");
  const [claimEmail, setClaimEmail] = useState(existing?.claimEmail ?? chosen?.claimEmail ?? "email");
  const [claimDisplayName, setClaimDisplayName] = useState(existing?.claimDisplayName ?? chosen?.claimDisplayName ?? "name");
  const [claimGroups, setClaimGroups] = useState(existing ? (existing.claimGroups ?? "") : (chosen?.claimGroups ?? ""));
  const [scopes, setScopes] = useState((existing?.scopes ?? chosen?.scopes ?? ["openid", "profile", "email"]).join(" "));
  const [jit, setJit] = useState(existing?.jitProvisioning ?? true);
  const [mfa, setMfa] = useState(existing?.mfaAssertedByProvider ?? false);
  const [domains, setDomains] = useState((existing?.allowedEmailDomains ?? []).join(", "));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  function applyPreset(next: string) {
    setPreset(next);
    const p = presets.find((x) => x.code === next);
    if (p && !existing) {
      setClaimUsername(p.claimUsername);
      setClaimEmail(p.claimEmail);
      setClaimDisplayName(p.claimDisplayName);
      setClaimGroups(p.claimGroups ?? "");
      setScopes(p.scopes.join(" "));
    }
  }

  async function submit() {
    setBusy(true);
    setProblem(null);
    const body: Record<string, unknown> = {
      code, displayName, preset, issuer, clientId,
      discoveryUrl: discoveryUrl || undefined,
      clientSecret: clientSecret || undefined,
      scopes: scopes.split(/\s+/).filter(Boolean),
      claimUsername, claimEmail, claimDisplayName,
      claimGroups: claimGroups || null,
      jitProvisioning: jit, mfaAssertedByProvider: mfa,
      allowedEmailDomains: domains.split(",").map((d) => d.trim()).filter(Boolean),
    };
    try {
      if (existing) {
        await api.post(`/api/ui/access/identity-providers/${existing.id}`, { ...body, rowVersion: existing.rowVersion });
      } else {
        await api.post("/api/ui/access/identity-providers", body);
      }
      onDone();
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <CardContent className="flex flex-col gap-3 border-y bg-muted/20 py-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <div className="flex flex-col gap-1">
          <Label>Provider</Label>
          <Select value={preset} onValueChange={applyPreset}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              {presets.map((p) => <SelectItem key={p.code} value={p.code}>{p.label}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label>Sign-in code</Label>
          <Input value={code} placeholder="okta" disabled={!!existing}
                 onChange={(e) => setCode(e.target.value.toLowerCase())} />
          <span className="text-[11px] text-muted-foreground">Appears in the URL: /auth/{code || "<code>"}/start</span>
        </div>
        <div className="flex flex-col gap-1">
          <Label>Shown on the sign-in page as</Label>
          <Input value={displayName} placeholder="Company SSO" onChange={(e) => setDisplayName(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1 lg:col-span-2">
          <Label>Issuer</Label>
          <Input value={issuer} placeholder="https://…" onChange={(e) => setIssuer(e.target.value)} />
          <span className="text-[11px] text-muted-foreground">{chosen?.issuerHint}</span>
        </div>
        <div className="flex flex-col gap-1">
          <Label>Discovery URL (optional)</Label>
          <Input value={discoveryUrl} placeholder="issuer + /.well-known/openid-configuration"
                 onChange={(e) => setDiscoveryUrl(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label>Client ID</Label>
          <Input value={clientId} onChange={(e) => setClientId(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label>{existing?.clientSecretHeld ? "Replace client secret" : "Client secret"}</Label>
          <Input type="password" value={clientSecret} autoComplete="off"
                 placeholder={existing?.clientSecretHeld ? "leave blank to keep the held secret" : "blank for a public client"}
                 onChange={(e) => setClientSecret(e.target.value)} />
          <span className="text-[11px] text-muted-foreground">Stored by reference in the secrets provider; never shown again.</span>
        </div>
        <div className="flex flex-col gap-1">
          <Label>Scopes</Label>
          <Input value={scopes} onChange={(e) => setScopes(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label>Username claim</Label>
          <Input value={claimUsername} onChange={(e) => setClaimUsername(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label>Email claim</Label>
          <Input value={claimEmail} onChange={(e) => setClaimEmail(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label>Display name claim</Label>
          <Input value={claimDisplayName} onChange={(e) => setClaimDisplayName(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label>Groups claim (optional, dotted path allowed)</Label>
          <Input value={claimGroups} placeholder="groups · realm_access.roles" onChange={(e) => setClaimGroups(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label>Allowed email domains (optional)</Label>
          <Input value={domains} placeholder="example.com, sub.example.com" onChange={(e) => setDomains(e.target.value)} />
        </div>
      </div>
      <div className="flex flex-wrap gap-6 text-sm">
        <label className="flex items-center gap-2">
          <Checkbox checked={jit} onCheckedChange={(v) => setJit(v === true)} /> Create an account on first sign-in
        </label>
        <label className="flex items-center gap-2">
          <Checkbox checked={mfa} onCheckedChange={(v) => setMfa(v === true)} />
          Trust this provider's second factor (skip the authenticator step when the token asserts MFA)
        </label>
      </div>
      <p className="text-[11px] text-muted-foreground">
        Discovery runs when you save. A provider that cannot be reached is saved disabled with the reason,
        rather than active with a sign-in button that fails. The issuer must be a public https address —
        a private or link-local destination is refused here and again before every call.
      </p>
      {problem && <p className="text-sm text-destructive">{problem}</p>}
      <div className="flex gap-2">
        <Button size="sm" disabled={busy || !code || !displayName || !issuer || !clientId} onClick={submit}>
          {existing ? "Save changes" : "Add provider"}
        </Button>
        <Button size="sm" variant="ghost" onClick={onDone}>Cancel</Button>
      </div>
    </CardContent>
  );
}

function GroupRoleEditor({ row, roles, onDone }: { row: Row; roles: RoleOption[]; onDone: () => void }) {
  const [mappings, setMappings] = useState<{ group: string; roleId: string }[]>(
    row.groupRoles.map((g) => ({ group: g.group, roleId: g.roleId })));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function save() {
    setBusy(true);
    setProblem(null);
    try {
      await api.post(`/api/ui/access/identity-providers/${row.id}/group-roles`, {
        mappings: mappings.filter((m) => m.group.trim() && m.roleId).map((m) => ({ ...m, scopeMode: "TENANT" })),
      });
      onDone();
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <CardContent className="flex flex-col gap-3 border-t bg-muted/20 py-4">
      <div>
        <div className="text-sm font-medium">Groups → roles for {row.displayName}</div>
        <p className="text-[11px] text-muted-foreground">
          A group value exactly as the provider sends it in <code className="font-mono">{row.claimGroups ?? "(no groups claim configured)"}</code>,
          and the role it grants across the tenant. Evaluated at every sign-in: leaving the group revokes
          the grant it produced. Roles an administrator assigned by hand are never touched.
        </p>
      </div>
      {mappings.map((m, i) => (
        <div key={i} className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
          <Input value={m.group} placeholder="group value"
                 onChange={(e) => setMappings((was) => was.map((x, j) => (j === i ? { ...x, group: e.target.value } : x)))} />
          <Select value={m.roleId} onValueChange={(v) => setMappings((was) => was.map((x, j) => (j === i ? { ...x, roleId: v } : x)))}>
            <SelectTrigger><SelectValue placeholder="Role" /></SelectTrigger>
            <SelectContent>
              {roles.map((r) => <SelectItem key={r.id} value={r.id}>{r.label || r.code}</SelectItem>)}
            </SelectContent>
          </Select>
          <Button size="sm" variant="ghost" onClick={() => setMappings((was) => was.filter((_, j) => j !== i))}>
            <X className="size-4" />
          </Button>
        </div>
      ))}
      <div className="flex gap-2">
        <Button size="sm" variant="outline" onClick={() => setMappings((was) => [...was, { group: "", roleId: "" }])}>
          <Plus className="size-4" /> Add mapping
        </Button>
        <Button size="sm" disabled={busy} onClick={save}>Save mappings</Button>
        <Button size="sm" variant="ghost" onClick={onDone}>Cancel</Button>
      </div>
      {problem && <p className="text-sm text-destructive">{problem}</p>}
    </CardContent>
  );
}
