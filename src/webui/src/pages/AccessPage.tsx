import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Check, Crown, Lock, Search, ShieldAlert, X } from "lucide-react";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { TeamRoster } from "@/components/TeamRoster";
import { ServiceCredentials } from "@/components/ServiceCredentials";
import { SubmissionHealth } from "@/components/SubmissionHealth";
import { Pager, usePaging } from "@/components/Paging";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export interface UserRow {
  id: string; username: string; displayName: string; email: string;
  lifecycleState: string; mustChangePassword: boolean; mfaEnrolled: boolean;
  lastAuthenticatedAt: string | null; liveAssignments: number; liveSessions: number;
  roleCodes: string[];
}
export interface RoleRow {
  id: string; code: string; label: string; description: string;
  permissionCodes: string[]; assignmentCount: number; active: boolean; fromTemplate: boolean;
}
export interface PermissionRow {
  code: string; domain: string; label: string; restricted: boolean; requiresStepUp: boolean;
}
export interface AssetGrant {
  id: string; assetId: string; assetName: string; principalId: string;
  displayName: string; username: string; capability: "OWN" | "RAISE_REQUEST";
  grantedAt: string; grantedBy: string;
}
interface Payload {
  users: UserRow[];
  assetGrants: AssetGrant[];
  totals: { users: number; withoutSecondFactor: number; mustChangePassword: number; withoutRole: number };
  roles: RoleRow[];
  permissions: PermissionRow[];
  mayGrant: boolean;
  mayReset: boolean;
  elevated: boolean;
}

/**
 * Who holds what, and what each role can do.
 *
 * Two panels because there are two questions and they are asked by different people at different
 * times: "what can this person reach" is asked when somebody joins or moves, and "what does this role
 * mean" is asked when a role is being composed. Splitting them into two pages made the second answer
 * unreachable from the first, which is where it is needed.
 *
 * **No role name and no organizational level name is written into this file** (ADR-027). The matrix
 * renders whatever roles the tenant has defined against whatever the product catalogue contains, and
 * a deployment that renames every role needs no change here.
 */
export function AccessPage() {
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState("");

  const load = useCallback(() => {
    api.get<Payload>("/api/ui/access").then(setData).catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  const requested = params.get("tab");
  const tab = requested === "roles" || requested === "projects" || requested === "teams"
    || requested === "credentials" ? requested : "people";

  const users = useMemo(() => {
    if (!data) return [];
    const needle = q.trim().toLowerCase();
    if (!needle) return data.users;
    return data.users.filter((u) =>
      u.username.toLowerCase().includes(needle)
      || u.displayName.toLowerCase().includes(needle)
      || u.roleCodes.some((c) => c.toLowerCase().includes(needle)));
  }, [data, q]);
  // Paged over the FILTERED list, so the search narrows the whole set and the pager moves
  // through what survived it. Searching only the visible page would find less the further
  // somebody had already read.
  const paging = usePaging(users);

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>;

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold tracking-tight">Access</h1>
        <p className="text-xs text-muted-foreground">
          The permission catalogue is fixed by the product. Roles, and who holds them, are yours.
        </p>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="People" value={data.totals.users} />
        <Stat label="No second factor" value={data.totals.withoutSecondFactor} tone="critical" />
        <Stat label="Must change password" value={data.totals.mustChangePassword} tone="warn" />
        {/* Counted, not left as an empty cell: an account with no live grant signs in and reaches
            nothing, which reads as a broken platform to them and as an ordinary row to whoever is
            looking at this page. */}
        <Stat label="Hold no role" value={data.totals.withoutRole} tone="warn" />
      </div>

      <div className="flex gap-1 border-b">
        <TabLink active={tab === "people"} onClick={() => setParams({}, { replace: true })}>
          People ({data.users.length})
        </TabLink>
        <TabLink active={tab === "roles"} onClick={() => setParams({ tab: "roles" }, { replace: true })}>
          Roles and permissions ({data.roles.length})
        </TabLink>
        {/* The other half of authorization. A role covers a slice of the ORGANIZATION; these cover
            one thing each. Reading them on two screens is how an access review misses one. */}
        <TabLink active={tab === "projects"}
                 onClick={() => setParams({ tab: "projects" }, { replace: true })}>
          Project ownership ({data.assetGrants.length})
        </TabLink>
        {/* Assessor teams. They live here rather than on Analytics because this is the dashboard for
            managing people, and a roster is a fact about people rather than about a chart. */}
        <TabLink active={tab === "teams"}
                 onClick={() => setParams({ tab: "teams" }, { replace: true })}>
          Teams
        </TabLink>
        {/* Alongside people and roles, because that is what it is: a non-interactive identity with a
            permission and a scope. Putting it under the dependency dashboard would file it as an
            SBOM feature, and it is an access-control one. */}
        <TabLink active={tab === "credentials"}
                 onClick={() => setParams({ tab: "credentials" }, { replace: true })}>
          Integrations
        </TabLink>
      </div>

      {tab === "people" ? (
        <>
          <Card>
            <CardContent className="flex flex-wrap items-center gap-3">
              <div className="relative min-w-52 flex-1">
                <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
                <Input value={q} className="pl-8" placeholder="Name, username or role"
                       aria-label="Filter people" onChange={(e) => setQ(e.target.value)} />
              </div>
              <span className="text-xs text-muted-foreground">
                {users.length} of {data.users.length}
              </span>
            </CardContent>
          </Card>

          <Card className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Person</TableHead>
                  <TableHead>Roles</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead>Last authenticated</TableHead>
                  <TableHead className="text-right">Sessions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {paging.rows.map((u) => (
                  <TableRow key={u.id}>
                    <TableCell>
                      <Link to={`/access/users/${u.id}`}
                            className="font-medium text-primary hover:underline">{u.username}</Link>
                      <div className="text-[11px] text-muted-foreground">{u.displayName}</div>
                    </TableCell>
                    <TableCell className="text-xs">
                      {u.roleCodes.length === 0
                        ? <Badge tone="warn">no role</Badge>
                        : u.roleCodes.join(", ")}
                    </TableCell>
                    <TableCell>
                      <span className="flex flex-wrap gap-1">
                        <Badge tone={u.lifecycleState === "ACTIVE" ? "ok" : "neutral"}>
                          {u.lifecycleState}
                        </Badge>
                        {!u.mfaEnrolled && <Badge tone="critical">no second factor</Badge>}
                        {u.mustChangePassword && <Badge tone="warn">must change password</Badge>}
                      </span>
                    </TableCell>
                    <TableCell className="font-mono text-[11px]">
                      {u.lastAuthenticatedAt ?? <span className="italic text-tone-unknown">never</span>}
                    </TableCell>
                    <TableCell className="tabular text-right">{u.liveSessions}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pager paging={paging} unit="people" />
          </Card>
        </>
      ) : tab === "roles" ? (
        <RoleMatrix roles={data.roles} permissions={data.permissions} />
      ) : tab === "teams" ? (
        <TeamRoster />
      ) : tab === "credentials" ? (
        // ONLY the credentials now. Alert destinations and the scan schedule moved to /settings: a
        // webhook is not an identity — nobody signs in as it and it holds no permission — and a
        // cadence is behaviour, not authorization. A signing credential stays because it IS an
        // identity with a permission and a scope, which is the distinction this tab is organised on.
        <>
          <ServiceCredentials />
          {/* Directly beneath the credentials it reports on. Health on another page is health nobody
              correlates with the key they are about to revoke. */}
          <SubmissionHealth />
        </>
      ) : (
        <AssetGrants grants={data.assetGrants} />
      )}
    </div>
  );
}

/**
 * Roles against the product catalogue.
 *
 * Grouped by permission domain and collapsible, because the catalogue is long and an administrator
 * composing a role thinks in one domain at a time. A restricted permission is marked: those are the
 * ones DOC-07 classifies as needing an explicit grant rather than arriving with seniority.
 */
function RoleMatrix({ roles, permissions }: { roles: RoleRow[]; permissions: PermissionRow[] }) {
  const domains = useMemo(() => {
    const byDomain = new Map<string, PermissionRow[]>();
    for (const p of permissions) {
      const list = byDomain.get(p.domain) ?? [];
      list.push(p);
      byDomain.set(p.domain, list);
    }
    return [...byDomain.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [permissions]);

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardContent className="flex items-start gap-2 text-xs text-muted-foreground">
          <Lock className="mt-0.5 size-4 shrink-0" />
          <span>
            The permission catalogue is fixed by the product and cannot be extended by configuration.
            What a tenant composes is which of them each role carries — <Link className="text-primary hover:underline"
            to="/roles">open the role editor</Link>.
          </span>
        </CardContent>
      </Card>

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle>Roles</CardTitle>
          <CardDescription>Retired roles are shown and marked, not hidden.</CardDescription>
        </CardHeader>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Role</TableHead>
              <TableHead className="text-right">Permissions</TableHead>
              <TableHead className="text-right">Held by</TableHead>
              <TableHead>State</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {roles.map((r) => (
              <TableRow key={r.id}>
                <TableCell>
                  <Link to={`/roles/${r.id}`} className="font-medium text-primary hover:underline">
                    {r.label}
                  </Link>
                  <div className="font-mono text-[11px] text-muted-foreground">{r.code}</div>
                </TableCell>
                <TableCell className="tabular text-right">{r.permissionCodes.length}</TableCell>
                <TableCell className="tabular text-right">{r.assignmentCount}</TableCell>
                <TableCell>
                  <span className="flex flex-wrap gap-1">
                    <Badge tone={r.active ? "ok" : "neutral"}>{r.active ? "active" : "retired"}</Badge>
                    {/* Worth saying: a later product change to the template is OFFERED, never
                        applied, so a tenant that edited one will not silently gain a permission. */}
                    {r.fromTemplate && <Badge tone="info">from a template</Badge>}
                  </span>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Card>

      {domains.map(([domain, rows]) => (
        <Card key={domain} className="overflow-hidden">
          <CardHeader><CardTitle className="font-mono text-xs uppercase">{domain}</CardTitle></CardHeader>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="min-w-64">Permission</TableHead>
                  {roles.map((r) => (
                    <TableHead key={r.id} className="text-center">
                      <span className="font-mono text-[10px]">{r.code}</span>
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((p) => (
                  <TableRow key={p.code}>
                    <TableCell>
                      <div className="font-mono text-[11px]">{p.code}</div>
                      <div className="text-[11px] text-muted-foreground">{p.label}</div>
                      <span className="flex gap-1">
                        {p.restricted && <Badge tone="critical">restricted</Badge>}
                        {p.requiresStepUp && <Badge tone="warn">step-up</Badge>}
                      </span>
                    </TableCell>
                    {roles.map((r) => {
                      const held = r.permissionCodes.includes(p.code);
                      return (
                        <TableCell key={r.id} className="text-center">
                          {/* Not colour alone: a tick and a cross differ in shape, and the cell
                              carries a text label for anything reading it aloud. */}
                          {held
                            ? <Check className="mx-auto size-4 text-tone-ok" aria-label="held" />
                            : <X className="mx-auto size-4 text-muted-foreground/40" aria-label="not held" />}
                        </TableCell>
                      );
                    })}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </Card>
      ))}
    </div>
  );
}

function TabLink({ active, onClick, children }: {
  active: boolean; onClick: () => void; children: React.ReactNode;
}) {
  return (
    <button type="button" onClick={onClick} aria-current={active ? "page" : undefined}
            className={cn("-mb-px border-b-2 px-3 py-2 text-sm transition-colors",
              active ? "border-primary font-medium text-foreground"
                     : "border-transparent text-muted-foreground hover:text-foreground")}>
      {children}
    </button>
  );
}

function Stat({ label, value, tone = "neutral" }: {
  label: string; value: number; tone?: "neutral" | "critical" | "warn";
}) {
  const active = value > 0 && tone !== "neutral";
  return (
    <div className="rounded-lg border bg-card px-4 py-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={cn("tabular mt-1 text-2xl font-semibold tracking-tight",
        active && tone === "critical" && "text-sev-critical",
        active && tone === "warn" && "text-tone-warn")}>{value}</div>
      {active && tone === "critical" && (
        <div className="mt-0.5 flex items-center gap-1 text-[11px] text-sev-critical">
          <ShieldAlert className="size-3" /> needs attention
        </div>
      )}
    </div>
  );
}

/**
 * Who owns what, and who may ask for work against it.
 *
 * Grouped by project rather than by person, because the question an access review asks is "does
 * every project have somebody accountable" — and the answer to that is a project with an empty
 * owner row, which a list sorted by person would never show.
 */
function AssetGrants({ grants }: { grants: AssetGrant[] }) {
  const byAsset = new Map<string, { name: string; id: string; rows: AssetGrant[] }>();
  for (const g of grants) {
    const entry = byAsset.get(g.assetId) ?? { name: g.assetName, id: g.assetId, rows: [] };
    entry.rows.push(g);
    byAsset.set(g.assetId, entry);
  }
  const assets = [...byAsset.values()].sort((a, b) => a.name.localeCompare(b.name));

  if (assets.length === 0) {
    return (
      <Card>
        <CardContent className="py-8 text-center text-sm text-muted-foreground">
          Nothing in your scope has an owner yet. Until a project has one, only the security team can
          raise requests against it — an owner is granted on the project's own page.
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="overflow-hidden">
      <CardHeader>
        <CardTitle>Project ownership and delegation</CardTitle>
        <CardDescription>
          Granted per project, not per team. Change it on the project.
        </CardDescription>
      </CardHeader>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Project</TableHead>
            <TableHead>Person</TableHead>
            <TableHead>Capability</TableHead>
            <TableHead>Granted</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {assets.flatMap((asset) => asset.rows.map((g, index) => (
            <TableRow key={g.id}>
              <TableCell className="text-xs">
                {/* Named once per group. Repeating it on every row makes a two-owner project look
                    like two projects. */}
                {index === 0 && (
                  <Link to={`/projects/${asset.id}`}
                        className="font-medium text-primary hover:underline">{asset.name}</Link>
                )}
              </TableCell>
              <TableCell className="text-xs">
                <Link to={`/access/users/${g.principalId}`} className="text-primary hover:underline">
                  {g.displayName || g.username}
                </Link>
                <div className="font-mono text-[11px] text-muted-foreground">{g.username}</div>
              </TableCell>
              <TableCell>
                <Badge tone={g.capability === "OWN" ? "warn" : "info"}>
                  {g.capability === "OWN"
                    ? <><Crown className="size-3" /> Owner</>
                    : "May request"}
                </Badge>
              </TableCell>
              <TableCell className="font-mono text-[11px]">
                {g.grantedAt}{g.grantedBy ? ` · ${g.grantedBy}` : ""}
              </TableCell>
            </TableRow>
          )))}
        </TableBody>
      </Table>
    </Card>
  );
}
