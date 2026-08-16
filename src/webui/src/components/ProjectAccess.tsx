import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Crown, KeyRound, Plus, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { Combobox } from "@/components/Combobox";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

interface Grant {
  id: string; principalId: string; displayName: string; username: string;
  capability: "OWN" | "RAISE_REQUEST"; grantedAt: string; grantedBy: string;
}
interface Payload {
  grants: Grant[];
  people: { id: string; name: string }[];
  mayGrant: boolean;
  mayRaiseRequest: boolean;
}

const CAPABILITY = {
  OWN: { label: "Owner", hint: "Accountable, and may delegate" },
  RAISE_REQUEST: { label: "May request an assessment", hint: "For this project only" },
} as const;

/**
 * Who is accountable for a project, and who may ask for work against it.
 *
 * This is the per-object half of authorization. A role grant covers a subtree of the organization
 * tree — the right shape for "the payments security lead sees every finding under payments" and the
 * wrong one for "Lan owns the refunds project", because the team that delivers refunds today runs
 * other things and will run different ones next year.
 *
 * The controls are disabled from `mayGrant`, which the server computes. That is for the person's
 * benefit only: every one of these calls is refused again on the other side, and an owner without the
 * platform-wide permission still gets `mayGrant: true` here because an owner who cannot delegate is
 * an owner in name only.
 */
export function ProjectAccess({ projectId }: { projectId: string }) {
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [person, setPerson] = useState("");
  const [capability, setCapability] = useState<"OWN" | "RAISE_REQUEST">("RAISE_REQUEST");
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.get<Payload>(`/api/ui/projects/${projectId}/access`)
      .then(setData).catch((e) => setError(e.message));
  }, [projectId]);
  useEffect(load, [load]);

  async function grant() {
    setBusy(true); setError(null);
    try {
      await api.post(`/api/ui/projects/${projectId}/access`, { principal: person, capability });
      setPerson("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  async function revoke(id: string) {
    setBusy(true); setError(null);
    try {
      await api.post(`/api/ui/projects/${projectId}/access/revoke`, { grant: id });
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  if (!data) return null;

  const owners = data.grants.filter((g) => g.capability === "OWN");
  const requesters = data.grants.filter((g) => g.capability === "RAISE_REQUEST");

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2"><KeyRound className="size-4" /> Access</CardTitle>
        <CardDescription>
          Who is accountable for this project, and who may ask for an assessment of it.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {error && <p className="text-sm text-destructive">{error}</p>}

        <div className="flex flex-col gap-2">
          <div className="text-xs font-medium text-muted-foreground">Owner</div>
          {owners.length === 0 ? (
            // Said outright. An unowned project has nobody who can delegate, so every request
            // against it has to go through the security team — which is the bottleneck the owner
            // level exists to remove.
            <p className="text-xs text-sev-high">
              Nobody owns this project. Only the security team can raise requests against it.
            </p>
          ) : owners.map((g) => (
            <GrantRow key={g.id} grant={g} mayGrant={data.mayGrant} busy={busy} onRevoke={revoke} />
          ))}
        </div>

        <div className="flex flex-col gap-2 border-t pt-3">
          <div className="text-xs font-medium text-muted-foreground">May request an assessment</div>
          {requesters.length === 0 ? (
            <p className="text-xs text-muted-foreground">
              Nobody has been delegated. The owner and the security team can still raise requests.
            </p>
          ) : requesters.map((g) => (
            <GrantRow key={g.id} grant={g} mayGrant={data.mayGrant} busy={busy} onRevoke={revoke} />
          ))}
        </div>

        {data.mayGrant && (
          <div className="flex flex-wrap items-end gap-3 border-t pt-3">
            <div className="flex min-w-52 flex-1 flex-col gap-1">
              <Label>Person</Label>
              <Combobox items={data.people.map((p) => {
                          const [name, hint] = p.name.split("·").map((x) => x.trim());
                          return { id: p.id, name: name || p.name, hint };
                        })}
                        value={person} onChange={setPerson}
                        placeholder="Choose somebody" clearLabel="" />
            </div>
            <div className="flex w-56 flex-col gap-1">
              <Label>Grant</Label>
              <Select value={capability}
                      onValueChange={(v) => setCapability(v as "OWN" | "RAISE_REQUEST")}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="RAISE_REQUEST">{CAPABILITY.RAISE_REQUEST.label}</SelectItem>
                  <SelectItem value="OWN">{CAPABILITY.OWN.label}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <Button size="sm" disabled={busy || !person} onClick={grant}>
              <Plus /> {busy ? "Granting…" : "Grant"}
            </Button>
          </div>
        )}
        <p className="text-[11px] text-muted-foreground">
          Granting is authorization configuration, so it asks for a second factor.
        </p>
      </CardContent>
    </Card>
  );
}

function GrantRow({ grant, mayGrant, busy, onRevoke }: {
  grant: Grant; mayGrant: boolean; busy: boolean; onRevoke: (id: string) => void;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2">
      <div className="min-w-0">
        <div className="flex items-center gap-1.5 text-sm">
          {grant.capability === "OWN" && <Crown className="size-3.5 text-tone-warn" />}
          <Link to={`/access/users/${grant.principalId}`} className="text-primary hover:underline">
            {grant.displayName || grant.username}
          </Link>
          <span className="font-mono text-[11px] text-muted-foreground">{grant.username}</span>
        </div>
        <div className="text-[11px] text-muted-foreground">
          granted {grant.grantedAt}{grant.grantedBy ? ` by ${grant.grantedBy}` : ""}
        </div>
      </div>
      <div className="flex items-center gap-2">
        <Badge tone={grant.capability === "OWN" ? "warn" : "info"}>
          {CAPABILITY[grant.capability]?.label ?? grant.capability}
        </Badge>
        {mayGrant && (
          <Button variant="ghost" size="sm" disabled={busy} aria-label="Revoke this grant"
                  onClick={() => onRevoke(grant.id)}><Trash2 /></Button>
        )}
      </div>
    </div>
  );
}
