import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Loader2, Plus, ShieldCheck, X } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Pager, usePaging } from "@/components/Paging";

export interface Role {
  id: string; code: string; label: string; description: string | null;
  active: boolean; permissions: string[]; assignments: number; fromTemplate: boolean;
}
export interface PermissionGroup {
  group: string;
  permissions: { code: string; label: string; restricted: boolean; requiresStepUp: boolean }[];
}
interface Payload { roles: Role[]; permissions: PermissionGroup[] }

/**
 * Roles, in the new interface.
 *
 * Roles are tenant data (ADR-027) and the permission catalogue underneath them is product-fixed.
 * That split is what this screen exists to make usable: an administrator composes roles out of a
 * catalogue nobody can extend from here, so a role can be wrong but never incoherent.
 *
 * Retired roles are listed rather than filtered out. A retired role that still has live assignments
 * is the row that most needs attention, and hiding it is how it stays granted for another year.
 */
export function RolesPage() {
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const load = useCallback(() => {
    api.get<Payload>("/api/ui/roles").then(setData).catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  if (!data) return <div className="text-sm text-muted-foreground">{error ?? "Loading…"}</div>;

  const live = data.roles.filter((r) => r.active);
  const retired = data.roles.filter((r) => !r.active);

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-lg font-semibold tracking-tight">Roles</h1>
          <p className="text-xs text-muted-foreground">
            What a role may do is a set of catalogue permissions; where it may do it comes from the
            scope of each grant, not from the role. The two are separate on purpose — one role can
            serve every business unit.
          </p>
        </div>
        <Button variant={creating ? "ghost" : "outline"} size="sm"
                onClick={() => setCreating(!creating)}>
          {creating ? <><X className="size-3" /> Cancel</> : <><Plus className="size-3" /> New role</>}
        </Button>
      </div>

      {creating && <CreateRole onSaved={() => { setCreating(false); load(); }}
                               onCancel={() => setCreating(false)} />}

      <RoleTable title="Active" rows={live}
                 empty="No role is active. Nobody can be granted anything until one exists." />

      {retired.length > 0 && (
        <RoleTable title="Retired" rows={retired}
                   empty=""
                   note="A retired role cannot be granted again, but any grant already made still
                         holds. A non-zero assignment count here is work to do, not history." />
      )}
    </div>
  );
}

function RoleTable({ title, rows, empty, note }: {
  title: string; rows: Role[]; empty: string; note?: string;
}) {
  const paging = usePaging(rows);
  return (
    <Card className="overflow-hidden">
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2">
          <ShieldCheck className="size-4" /> {title}
        </CardTitle>
        {note && <CardDescription>{note}</CardDescription>}
      </CardHeader>
      {rows.length === 0 ? (
        <CardContent className="text-sm text-muted-foreground">{empty}</CardContent>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Role</TableHead>
              <TableHead>Code</TableHead>
              <TableHead className="text-right">Permissions</TableHead>
              <TableHead className="text-right">People holding it</TableHead>
              <TableHead>Origin</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {paging.rows.map((r) => (
              <TableRow key={r.id}>
                <TableCell className="max-w-72 text-xs">
                  <Link to={`/roles/${r.id}`} className="font-medium text-primary hover:underline">
                    {r.label}
                  </Link>
                  {r.description && (
                    <div className="truncate text-[11px] text-muted-foreground">{r.description}</div>
                  )}
                </TableCell>
                <TableCell className="font-mono text-[11px] text-muted-foreground">{r.code}</TableCell>
                <TableCell className="tabular text-right text-xs">
                  {/* Zero is called out. A role with no permissions grants nothing, and a person
                      holding it will report that the platform is broken rather than that they were
                      given an empty role. */}
                  {r.permissions.length === 0
                    ? <Badge tone="unknown">none</Badge> : r.permissions.length}
                </TableCell>
                <TableCell className="tabular text-right text-xs">
                  {!r.active && r.assignments > 0
                    ? <strong className="text-sev-critical">{r.assignments}</strong>
                    : r.assignments}
                </TableCell>
                <TableCell>
                  {r.fromTemplate
                    ? <Badge tone="info">template</Badge>
                    : <Badge tone="neutral">tenant</Badge>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      <Pager paging={paging} unit="roles" />
    </Card>
  );
}

function CreateRole({ onSaved, onCancel }: { onSaved: () => void; onCancel: () => void }) {
  const [code, setCode] = useState("");
  const [label, setLabel] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function create() {
    setBusy(true);
    setProblem(null);
    try {
      await api.post("/api/ui/roles", { code, label, description });
      onSaved();
    } catch (e) {
      setProblem((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>New role</CardTitle>
        <CardDescription>
          Create it first, then choose its permissions. A role starts with none, so creating one
          grants nobody anything until you say what it may do.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {problem && <p className="text-sm text-destructive">{problem}</p>}
        <div className="grid gap-4 md:grid-cols-3">
          <div className="flex flex-col gap-1.5">
            <Label>Code<span className="pl-0.5 text-destructive">*</span></Label>
            <Input value={code} className="font-mono"
                   onChange={(e) => setCode(e.target.value.toUpperCase())}
                   placeholder="APPLICATION_OWNER" />
            <span className="text-[11px] text-muted-foreground">
              Immutable. It appears in audit records that outlive the role, so changing it later would
              make those records refer to something that no longer means what they say.
            </span>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Name<span className="pl-0.5 text-destructive">*</span></Label>
            <Input value={label} onChange={(e) => setLabel(e.target.value)}
                   placeholder="Application owner" />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Description</Label>
            <Input value={description} onChange={(e) => setDescription(e.target.value)}
                   placeholder="Owns an application and its remediation" />
          </div>
        </div>
        <div className="flex gap-2">
          <Button size="sm" onClick={create} disabled={busy || !code.trim() || !label.trim()}>
            {busy && <Loader2 className="size-3 animate-spin" />} Create
          </Button>
          <Button size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>
        </div>
      </CardContent>
    </Card>
  );
}
