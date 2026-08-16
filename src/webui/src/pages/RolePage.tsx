import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, KeyRound, Loader2, ShieldAlert } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { PermissionGroup, Role } from "@/pages/RolesPage";

interface Removal { deletable: boolean; liveAssignments: number; everAssigned: number }
interface Payload { role: Role; permissions: PermissionGroup[]; removal: Removal }

/**
 * One role: its name, what it may do, and how to get rid of it.
 *
 * The permission list is the substance. It is grouped by the catalogue's own domain rather than by a
 * taxonomy invented here, and the two flags that matter — reveals restricted data, demands a second
 * factor — are shown on the permission rather than left for somebody to know. A checkbox that looks
 * like every other checkbox is one that gets ticked in a batch.
 */
export function RolePage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [label, setLabel] = useState("");
  const [description, setDescription] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Payload>(`/api/ui/roles/${id}`).then((d) => {
      setData(d);
      setLabel(d.role.label);
      setDescription(d.role.description ?? "");
      setSelected(new Set(d.role.permissions));
    }).catch((e) => setError(e.message));
  }, [id]);
  useEffect(load, [load]);

  function toggle(code: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code); else next.add(code);
      return next;
    });
    setNotice(null);
  }

  async function act(path: string, body: unknown, then: "reload" | "list") {
    setBusy(true);
    setProblem(null);
    try {
      await api.post(path, body);
      if (then === "list") navigate("/roles"); else { setNotice("Saved."); load(); }
    } catch (e) {
      setProblem((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  }

  if (!data) return <div className="text-sm text-muted-foreground">{error ?? "Loading…"}</div>;

  const role = data.role;
  const original = new Set(role.permissions);
  const added = [...selected].filter((c) => !original.has(c));
  const removed = [...original].filter((c) => !selected.has(c));
  const dirty = label !== role.label || description !== (role.description ?? "")
    || added.length > 0 || removed.length > 0;

  return (
    <div className="flex flex-col gap-5">
      <div>
        <Link to="/roles" className="mb-1 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-3" /> All roles
        </Link>
        <h1 className="flex items-center gap-2 text-lg font-semibold tracking-tight">
          {role.label}
          {!role.active && <Badge tone="neutral">retired</Badge>}
          {role.fromTemplate && <Badge tone="info">template</Badge>}
        </h1>
        <p className="font-mono text-xs text-muted-foreground">{role.code}</p>
      </div>

      {problem && <Card className="border-destructive/40">
        <CardContent className="text-sm text-destructive">{problem}</CardContent></Card>}
      {notice && <Card><CardContent className="text-sm">{notice}</CardContent></Card>}

      {role.fromTemplate && (
        <Card>
          <CardContent className="text-xs text-muted-foreground">
            This role came from the deployment template. Editing it is legitimate, and the next
            template refresh will not carry your change — worth knowing before you make it rather
            than after it disappears.
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader><CardTitle>Name and description</CardTitle></CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <Label>Name</Label>
            <Input value={label} onChange={(e) => { setLabel(e.target.value); setNotice(null); }} />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Description</Label>
            <Input value={description}
                   onChange={(e) => { setDescription(e.target.value); setNotice(null); }} />
          </div>
          <div className="md:col-span-2 text-[11px] text-muted-foreground">
            The code is not editable. It is written into audit records that outlive the role.
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Permissions</CardTitle>
          <CardDescription>
            {selected.size} of the catalogue selected. Scope is not chosen here — a grant decides
            where this role applies, so the same role serves every part of the organization.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-5">
          {data.permissions.map((group) => (
            <div key={group.group} className="flex flex-col gap-2">
              <div className="flex items-baseline gap-2">
                <h3 className="font-mono text-xs font-semibold">{group.group}</h3>
                <span className="text-[11px] text-muted-foreground">
                  {group.permissions.filter((p) => selected.has(p.code)).length} of {group.permissions.length}
                </span>
              </div>
              <div className="grid gap-1.5 md:grid-cols-2 lg:grid-cols-3">
                {group.permissions.map((p) => (
                  <label key={p.code}
                         className="flex cursor-pointer items-start gap-2 rounded-md border p-2 hover:bg-muted/40">
                    <Checkbox checked={selected.has(p.code)} onCheckedChange={() => toggle(p.code)}
                              className="mt-0.5" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-xs font-medium">{p.label}</span>
                      <span className="block truncate font-mono text-[10px] text-muted-foreground">
                        {p.code}
                      </span>
                      {(p.restricted || p.requiresStepUp) && (
                        <span className="mt-1 flex flex-wrap gap-1">
                          {p.restricted && (
                            <Badge tone="critical">
                              <ShieldAlert className="size-2.5" /> restricted data
                            </Badge>
                          )}
                          {p.requiresStepUp && (
                            <Badge tone="warn"><KeyRound className="size-2.5" /> second factor</Badge>
                          )}
                        </span>
                      )}
                    </span>
                  </label>
                ))}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      <div className="flex flex-wrap items-center gap-3">
        <Button size="sm" disabled={busy || !dirty || !label.trim()}
                onClick={() => act(`/api/ui/roles/${role.id}`,
                  { label, description, permissions: [...selected] }, "reload")}>
          {busy && <Loader2 className="size-3 animate-spin" />} Save
        </Button>
        <Button size="sm" variant="ghost" asChild><Link to="/roles">Cancel</Link></Button>
        {/* The diff, before the click. "Save" on a checkbox grid is the control most likely to be
            pressed after an accidental toggle, and a count of what changes is what catches it. */}
        {dirty && (added.length > 0 || removed.length > 0) && (
          <span className="text-[11px] text-muted-foreground">
            {added.length > 0 && `granting ${added.length} more`}
            {added.length > 0 && removed.length > 0 && ", "}
            {removed.length > 0 && (
              <strong className="text-sev-critical">removing {removed.length}</strong>
            )}
            {removed.length > 0 && ` — ${role.assignments} person(s) hold this role`}
          </span>
        )}
      </div>

      <Lifecycle role={role} removal={data.removal} busy={busy} onAct={act} />
    </div>
  );
}

/**
 * Retire, restore, or delete.
 *
 * Deleting is offered only for a role that has never been assigned to anybody. Anything else is
 * retired instead, because removing a role that once granted access strips the meaning from every
 * audit record naming it — and the record of what happened is inviolable. The figures are shown so
 * the choice is obvious rather than enforced by a disabled button nobody understands.
 */
function Lifecycle({ role, removal, busy, onAct }: {
  role: Role; removal: Removal; busy: boolean;
  onAct: (path: string, body: unknown, then: "reload" | "list") => void;
}) {
  const [confirming, setConfirming] = useState<"retire" | "delete" | null>(null);

  return (
    <Card className={role.active ? "border-destructive/30" : undefined}>
      <CardHeader>
        <CardTitle className={role.active ? "text-destructive" : undefined}>
          {role.active ? "Retire or delete" : "Restore"}
        </CardTitle>
        <CardDescription>
          {role.active
            ? `${removal.liveAssignments} person(s) hold this role now; it has been assigned ${removal.everAssigned} time(s) in total.`
            : "Retired. Existing grants still hold — restoring lets it be granted again."}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap items-center gap-2">
        {!role.active && (
          <Button size="sm" variant="outline" disabled={busy}
                  onClick={() => onAct(`/api/ui/roles/${role.id}/restore`, {}, "reload")}>
            Restore
          </Button>
        )}

        {role.active && (confirming === "retire" ? (
          <>
            <span className="text-[11px] text-muted-foreground">
              Retire it? No new grants; the {removal.liveAssignments} existing one(s) keep working
              until revoked.
            </span>
            <Button size="sm" variant="destructive" disabled={busy}
                    onClick={() => onAct(`/api/ui/roles/${role.id}/retire`, {}, "list")}>
              Retire
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setConfirming(null)}>No</Button>
          </>
        ) : (
          <Button size="sm" variant="outline" disabled={busy}
                  onClick={() => setConfirming("retire")}>
            Retire
          </Button>
        ))}

        {removal.deletable ? (
          confirming === "delete" ? (
            <>
              <span className="text-[11px] text-muted-foreground">
                Delete permanently? It has never been assigned, so nothing references it.
              </span>
              <Button size="sm" variant="destructive" disabled={busy}
                      onClick={() => onAct(`/api/ui/roles/${role.id}/delete`, {}, "list")}>
                Delete
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setConfirming(null)}>No</Button>
            </>
          ) : (
            <Button size="sm" variant="ghost" disabled={busy}
                    onClick={() => setConfirming("delete")}>
              Delete permanently
            </Button>
          )
        ) : (
          <span className="text-[11px] text-muted-foreground">
            Cannot be deleted: it has been assigned {removal.everAssigned} time(s), and audit records
            name it. Retiring keeps that history readable.
          </span>
        )}
      </CardContent>
    </Card>
  );
}
