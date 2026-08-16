import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, KeyRound, ShieldAlert, ShieldCheck } from "lucide-react";
import { api } from "@/lib/api";
import type { AssetGrant, RoleRow, UserRow } from "@/pages/AccessPage";
import { Combobox } from "@/components/Combobox";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Pager, usePaging } from "@/components/Paging";

interface Assignment {
  id: string; role_code: string; role_label: string;
  scope_mode: string; scope_node: string; granted_at: string;
}
interface Detail extends UserRow {
  assignments: Assignment[];
  assetGrants: AssetGrant[];
  roles: RoleRow[];
  mayGrant: boolean;
  mayReset: boolean;
  elevated: boolean;
}
interface Node { id: string; name: string; typeCode: string; depth: number; parentName: string | null }

/**
 * One principal: what they hold, and the two operations that change it.
 *
 * Granting a role is class E and issuing a credential reset is class C, so the server refuses both
 * without a fresh second factor and answers `STEP_UP_REQUIRED`. The API client turns that into a trip
 * to the step-up page and back. **Nothing here decides whether the caller is elevated** — `elevated`
 * only chooses whether to warn them first, because losing a half-filled form to a redirect is worse
 * than being told in advance.
 */
export function AccessUserPage() {
  const { id = "" } = useParams();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [nodes, setNodes] = useState<Node[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const assignments = usePaging(detail?.assignments ?? []);
  const grants = usePaging(detail?.assetGrants ?? []);
  const [token, setToken] = useState<{ token: string; expiresAt: string; sessionsRevoked: number } | null>(null);

  const [role, setRole] = useState("");
  const [scopeMode, setScopeMode] = useState("SUBTREE");
  const [scopeNode, setScopeNode] = useState("");

  const load = useCallback(() => {
    api.get<Detail>(`/api/ui/access/users/${id}`).then(setDetail).catch((e) => setError(e.message));
  }, [id]);
  useEffect(load, [load]);
  useEffect(() => {
    api.get<{ nodes: Node[] }>("/api/ui/organization")
      .then((d) => setNodes(d.nodes)).catch(() => setNodes([]));
  }, []);

  async function grant() {
    setBusy(true); setError(null);
    try {
      await api.post(`/api/ui/access/users/${id}/roles`,
        { role, scopeMode, scopeNode: scopeMode === "TENANT" ? null : scopeNode });
      setRole(""); setScopeNode("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  async function revoke(assignment: string) {
    setBusy(true); setError(null);
    try {
      await api.post(`/api/ui/access/users/${id}/roles/revoke`, { assignment });
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  async function reset() {
    setBusy(true); setError(null);
    try {
      setToken(await api.post(`/api/ui/access/users/${id}/reset`, {}));
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  if (error && !detail) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!detail) return <div className="text-sm text-muted-foreground">Loading…</div>;

  const canWrite = detail.mayGrant || detail.mayReset;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <Link to="/access" className="mb-1 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
            <ArrowLeft className="size-3" /> Access
          </Link>
          <h1 className="font-mono text-lg font-semibold tracking-tight">{detail.username}</h1>
          <p className="text-xs text-muted-foreground">{detail.displayName} · {detail.email}</p>
        </div>
        <div className="flex flex-wrap justify-end gap-1">
          <Badge tone={detail.lifecycleState === "ACTIVE" ? "ok" : "neutral"}>{detail.lifecycleState}</Badge>
          {detail.mfaEnrolled
            ? <Badge tone="ok"><ShieldCheck className="size-3" /> second factor</Badge>
            : <Badge tone="critical"><ShieldAlert className="size-3" /> no second factor</Badge>}
          {detail.mustChangePassword && <Badge tone="warn">must change password</Badge>}
        </div>
      </div>

      {canWrite && !detail.elevated && (
        <Card className="border-tone-warn/40">
          <CardContent className="text-sm">
            Granting a role and issuing a reset both need a fresh second factor. You will be asked for
            one when you act, and brought back here afterwards.
          </CardContent>
        </Card>
      )}
      {error && <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>}

      {token && (
        <Card className="border-sev-critical/40">
          <CardHeader>
            <CardTitle>Reset issued — shown once</CardTitle>
            <CardDescription>
              This is a bearer credential for the account. It is single-use, expires at{" "}
              {token.expiresAt.slice(0, 16)} UTC, and {token.sessionsRevoked} live session
              {token.sessionsRevoked === 1 ? " was" : "s were"} revoked. Hand it over on a channel you
              trust; it will not be shown again.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <code className="block break-all rounded-md bg-muted p-3 font-mono text-xs">{token.token}</code>
          </CardContent>
        </Card>
      )}

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle>Roles held</CardTitle>
          <CardDescription>
            A grant is a role AND a scope. Revoking is immediate and the record of it remains.
          </CardDescription>
        </CardHeader>
        {detail.assignments.length === 0 ? (
          <CardContent className="text-sm text-sev-high">
            This account holds no role, so it can sign in and reach nothing.
          </CardContent>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Role</TableHead>
                <TableHead>Scope</TableHead>
                <TableHead>Granted</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {assignments.rows.map((a) => (
                <TableRow key={a.id}>
                  <TableCell>
                    {a.role_label}
                    <div className="font-mono text-[11px] text-muted-foreground">{a.role_code}</div>
                  </TableCell>
                  <TableCell className="text-xs">
                    <Badge tone={a.scope_mode === "TENANT" ? "warn" : "info"}>{a.scope_mode}</Badge>
                    {a.scope_node && <span className="ml-2">{a.scope_node}</span>}
                  </TableCell>
                  <TableCell className="font-mono text-[11px]">{a.granted_at.slice(0, 16)}</TableCell>
                  <TableCell className="text-right">
                    {detail.mayGrant && (
                      <Button size="sm" variant="outline" disabled={busy}
                              onClick={() => revoke(a.id)}>Revoke</Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        <Pager paging={assignments} unit="grants" />
      </Card>

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle>Projects they own or may request against</CardTitle>
          <CardDescription>
            Granted per project, on the project. Check this before disabling an account — a project
            whose only owner leaves is a project nobody can delegate on.
          </CardDescription>
        </CardHeader>
        {detail.assetGrants.length === 0 ? (
          <CardContent className="text-sm text-muted-foreground">
            They own nothing and have been delegated nothing.
          </CardContent>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Project</TableHead>
                <TableHead>Capability</TableHead>
                <TableHead>Granted</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {grants.rows.map((g) => (
                <TableRow key={g.id}>
                  <TableCell className="text-xs">
                    <Link to={`/projects/${g.assetId}`}
                          className="text-primary hover:underline">{g.assetName}</Link>
                  </TableCell>
                  <TableCell>
                    <Badge tone={g.capability === "OWN" ? "warn" : "info"}>
                      {g.capability === "OWN" ? "Owner" : "May request"}
                    </Badge>
                  </TableCell>
                  <TableCell className="font-mono text-[11px]">
                    {g.grantedAt}{g.grantedBy ? ` · ${g.grantedBy}` : ""}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        <Pager paging={grants} unit="project grants" />
      </Card>

      {detail.mayGrant && (
        <Card>
          <CardHeader>
            <CardTitle>Grant a role</CardTitle>
            <CardDescription>
              A grant that is not tenant-wide needs a node. The scope is what the role can be used
              over — a role without one would be half an authorization.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="flex flex-col gap-1">
                <Label>Role</Label>
                <Combobox
                  items={detail.roles.map((r) => ({ id: r.id, name: r.label, hint: r.code }))}
                  value={role} onChange={setRole} placeholder="Choose a role" clearLabel="" />
              </div>
              <div className="flex flex-col gap-1">
                <Label>Scope</Label>
                <Select value={scopeMode} onValueChange={setScopeMode}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="SUBTREE">A node and everything under it</SelectItem>
                    <SelectItem value="NODE_ONLY">One node only</SelectItem>
                    <SelectItem value="TENANT">The whole tenant</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1">
                <Label>Node</Label>
                <Combobox
                  items={nodes.map((n) => ({
                    id: n.id,
                    name: `${"— ".repeat(Math.max(0, n.depth))}${n.name}`,
                    hint: n.parentName ? `${n.typeCode} · under ${n.parentName}` : n.typeCode,
                  }))}
                  value={scopeNode} onChange={setScopeNode}
                  placeholder={scopeMode === "TENANT" ? "Not needed" : "Choose a node"}
                  clearLabel="" disabled={scopeMode === "TENANT"} />
              </div>
            </div>
            <div className="flex justify-end">
              <Button size="sm" disabled={busy || !role || (scopeMode !== "TENANT" && !scopeNode)}
                      onClick={grant}>{busy ? "Granting…" : "Grant"}</Button>
            </div>
          </CardContent>
        </Card>
      )}

      {detail.mayReset && (
        <Card>
          <CardHeader>
            <CardTitle>Credential reset</CardTitle>
            <CardDescription>
              There is no field here that sets a password to a value you choose, and that is a refusal
              rather than an omission: a credential an administrator knows is one whose later use
              cannot be attributed to the account holder, which voids every audit entry that account
              then produces. This issues a single-use token instead, and the holder picks the value.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex justify-between gap-3">
            <span className="text-xs text-muted-foreground">
              Every live session for this account is revoked at the same time.
            </span>
            <Button size="sm" variant="destructive" disabled={busy} onClick={reset}>
              <KeyRound /> {busy ? "Issuing…" : "Issue a reset"}
            </Button>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
