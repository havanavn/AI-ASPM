import { useCallback, useEffect, useState } from "react";
import { KeyRound, LogOut, ShieldCheck, ShieldAlert, Monitor } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Pager, usePaging } from "@/components/Paging";

interface SessionRow {
  id: string;
  factor_state: string;
  created_at: string;
  last_seen_at: string;
  expires_at: string;
  source_address: string | null;
  user_agent: string | null;
  current: boolean;
}
interface Assignment {
  id: string; role_code: string; role_label: string;
  scope_mode: string; scope_node: string; granted_at: string;
}
interface Payload {
  principalId: string;
  username: string | null;
  displayName: string | null;
  email: string | null;
  lifecycleState: string | null;
  mfaEnrolled: boolean;
  mustChangePassword: boolean;
  lastAuthenticatedAt: string | null;
  roles: string[];
  assignments: Assignment[];
  sessions: SessionRow[];
  factorState: string;
}

/**
 * Your own account: who the platform thinks you are, what you can reach, and where you are signed in.
 *
 * The three actions that change a credential — changing a password, enrolling a second factor, and
 * re-presenting one for a sensitive operation — stay on the server-rendered pages and are linked to
 * from here. Their correctness lives in server-side redirects: the dispatcher sends a caller who must
 * change their password to that page and refuses everything else until they have, and reimplementing
 * that gate in the browser is how two gates come to disagree.
 */
export function AccountPage() {
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const sessions = usePaging(data?.sessions ?? []);

  const load = useCallback(() => {
    api.get<Payload>("/api/ui/account").then(setData).catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  async function revoke(id: string) {
    setBusy(id); setError(null);
    try {
      const result = await api.post<{ revoked?: boolean; signOut?: boolean }>(
        "/api/ui/account/sessions/revoke", { session: id });
      // Revoking the session you are sitting in is signing out, and signing out also clears the
      // cookie. The server says so rather than doing half of it here.
      if (result.signOut) { window.location.assign("/sign-out"); return; }
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(null); }
  }

  if (error && !data) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>;

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold tracking-tight">Your account</h1>
        <p className="text-xs text-muted-foreground">
          What this platform knows about you, what you can reach, and every browser currently holding
          a session as you.
        </p>
      </div>

      {data.mustChangePassword && (
        <Card className="border-sev-critical/40">
          <CardContent className="flex flex-wrap items-center justify-between gap-3 text-sm">
            <span>Your password must be changed before you can do anything else.</span>
            <Button size="sm" asChild><a href="/change-password">Change it now</a></Button>
          </CardContent>
        </Card>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader><CardTitle>Identity</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
            <Field label="Username" value={data.username} mono />
            <Field label="Display name" value={data.displayName} />
            <Field label="Email" value={data.email} />
            <Field label="State" value={data.lifecycleState} />
            <div>
              <div className="text-xs text-muted-foreground">Last authenticated</div>
              {/* Never is a fact, and a different one from not recorded. Neither is a blank cell. */}
              <div className="font-mono text-xs">
                {data.lastAuthenticatedAt ?? <span className="italic text-tone-unknown">never</span>}
              </div>
            </div>
            <div>
              <div className="text-xs text-muted-foreground">This session</div>
              <div className="text-sm">{data.factorState.replace(/_/g, " ").toLowerCase()}</div>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Credentials</CardTitle>
            <CardDescription>
              Nobody, including an administrator, can set your password to a value they choose.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <div className="flex items-center gap-2 text-sm">
              {data.mfaEnrolled
                ? <><ShieldCheck className="size-4 text-tone-ok" /> A second factor is enrolled.</>
                : <><ShieldAlert className="size-4 text-sev-critical" /> No second factor is enrolled.</>}
            </div>
            {/* These four stay on the server-rendered tier, and it is a decision rather than the
                last unconverted corner. They are ADR-059's authentication surfaces: class G,
                authorized by the session's factor state rather than by a permission, and reachable
                by a principal holding no role at all — which the deployment bootstrap creates. A
                password form served by a JavaScript bundle also fails closed differently: if the
                bundle fails to load, a person locked out of their account cannot recover, and the
                recovery path is exactly the one that must work on the worst day. Moving them is a
                separate decision with its own argument, not a conversion nobody got to. */}
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="outline" asChild>
                <a href="/change-password"><KeyRound /> Change password</a>
              </Button>
              {!data.mfaEnrolled && (
                <Button size="sm" asChild><a href="/mfa-enrol">Enrol a second factor</a></Button>
              )}
              <Button size="sm" variant="ghost" asChild>
                <a href="/sign-out"><LogOut /> Sign out</a>
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle>What you can reach</CardTitle>
          <CardDescription>
            Each grant is a role AND a scope. A role with no scope would be half an authorization.
          </CardDescription>
        </CardHeader>
        {data.assignments.length === 0 ? (
          <CardContent className="text-sm">
            {/* An account with no live grant reaches nothing at all, which looks like a broken
                platform rather than a configuration gap unless it is said outright. */}
            <span className="text-sev-high">
              You hold no role. Nothing on this platform is visible to you until somebody grants one.
            </span>
          </CardContent>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Role</TableHead>
                <TableHead>Scope</TableHead>
                <TableHead>Granted</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.assignments.map((a) => (
                <TableRow key={a.id}>
                  <TableCell className="text-sm">
                    {a.role_label}
                    <div className="font-mono text-[11px] text-muted-foreground">{a.role_code}</div>
                  </TableCell>
                  <TableCell className="text-xs">
                    <Badge tone={a.scope_mode === "TENANT" ? "warn" : "info"}>{a.scope_mode}</Badge>
                    {a.scope_node && <span className="ml-2">{a.scope_node}</span>}
                  </TableCell>
                  <TableCell className="font-mono text-[11px]">{a.granted_at.slice(0, 16)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><Monitor className="size-4" /> Live sessions</CardTitle>
          <CardDescription>
            Every browser currently holding a session as you. Revoke one you do not recognise.
          </CardDescription>
        </CardHeader>
        {error && <CardContent className="text-sm text-destructive">{error}</CardContent>}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Started</TableHead>
              <TableHead>Last seen</TableHead>
              <TableHead>From</TableHead>
              <TableHead>Browser</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {sessions.rows.map((s) => (
              <TableRow key={s.id}>
                <TableCell className="font-mono text-[11px]">
                  {s.created_at.slice(0, 16)}
                  {s.current && <Badge tone="info" className="ml-2">this browser</Badge>}
                </TableCell>
                <TableCell className="font-mono text-[11px]">{s.last_seen_at.slice(0, 16)}</TableCell>
                <TableCell className="font-mono text-[11px]">{s.source_address ?? "—"}</TableCell>
                <TableCell className="max-w-64 truncate text-[11px] text-muted-foreground">
                  {s.user_agent ?? "—"}
                </TableCell>
                <TableCell className="text-right">
                  <Button size="sm" variant={s.current ? "ghost" : "outline"}
                          disabled={busy === s.id} onClick={() => revoke(s.id)}>
                    {s.current ? "Sign out" : busy === s.id ? "Revoking…" : "Revoke"}
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <Pager paging={sessions} unit="sessions" />
      </Card>
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
