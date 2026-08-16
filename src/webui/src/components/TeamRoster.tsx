import { useCallback, useEffect, useState } from "react";
import { Plus, Users } from "lucide-react";
import { api } from "@/lib/api";
import { Pager, usePaging } from "@/components/Paging";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

interface Team { id: string; name: string; description: string; active: boolean; members: number }
interface Person {
  principalId: string; displayName: string; username: string;
  teamId: string | null; teamName: string | null;
}
interface Payload { teams: Team[]; people: Person[]; mayManage: boolean }

const NONE = "__none__";

/**
 * Assessor teams, and who is on each.
 *
 * **One live team per person**, enforced by the database and not merely by this form. It is
 * arithmetic rather than policy: a person in two teams has their work counted in both, and a
 * per-team chart whose bars sum to more than the work that exists is a chart nobody can plan from.
 * So the control is a single picker per person — a move, never an add.
 *
 * People on no team are listed rather than hidden. They are the reason a per-team chart has a "No
 * team" bar, and the roster is where somebody fixes that.
 */
export function TeamRoster() {
  const [data, setData] = useState<Payload | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const people = usePaging(data?.people ?? []);

  const load = useCallback(() => {
    api.get<Payload>("/api/ui/teams").then(setData).catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  async function act(path: string, body: unknown) {
    setBusy(true); setError(null);
    try {
      await api.post(path, body);
      setName(""); setDescription("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  if (!data) return null;
  const active = data.teams.filter((t) => t.active);

  return (
    <div className="flex flex-col gap-4">
      {error && <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>}

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><Users className="size-4" /> Teams</CardTitle>
          <CardDescription>
            Named groups of assessors. Every per-team figure on Analytics is summed from the people
            listed below.
          </CardDescription>
        </CardHeader>
        {data.teams.length === 0 ? (
          <CardContent className="text-sm text-muted-foreground">
            No team yet. Until one exists, per-team charts show everything under “No team”.
          </CardContent>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Team</TableHead>
                <TableHead className="text-right">Members</TableHead>
                <TableHead>State</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.teams.map((t) => (
                <TableRow key={t.id}>
                  <TableCell className="text-sm">
                    {t.name}
                    {t.description && (
                      <div className="text-[11px] text-muted-foreground">{t.description}</div>
                    )}
                  </TableCell>
                  <TableCell className="tabular text-right">{t.members}</TableCell>
                  <TableCell>
                    {/* Retired teams stay listed. A retired team still owns the history of the
                        engagements its members ran. */}
                    <Badge tone={t.active ? "ok" : "neutral"}>
                      {t.active ? "active" : "retired"}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-right">
                    {data.mayManage && t.active && (
                      <Button variant="outline" size="sm" disabled={busy}
                              onClick={() => act(`/api/ui/teams/${t.id}/retire`, {})}>
                        Retire
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {data.mayManage && (
          <CardContent className="flex flex-wrap items-end gap-3 border-t">
            <div className="flex min-w-44 flex-col gap-1">
              <Label htmlFor="team-name">New team</Label>
              <Input id="team-name" value={name} maxLength={80} placeholder="Name"
                     onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="flex min-w-52 flex-1 flex-col gap-1">
              <Label htmlFor="team-desc">What they cover</Label>
              <Input id="team-desc" value={description} placeholder="Optional"
                     onChange={(e) => setDescription(e.target.value)} />
            </div>
            <Button size="sm" disabled={busy || !name.trim()}
                    onClick={() => act("/api/ui/teams", { name, description })}>
              <Plus /> Create
            </Button>
          </CardContent>
        )}
      </Card>

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle>Who is on which team</CardTitle>
          <CardDescription>
            One team per person. Moving somebody takes them off the team they were on, so the numbers
            on Analytics stay addable.
          </CardDescription>
        </CardHeader>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Person</TableHead>
              <TableHead>Team</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {people.rows.map((p) => (
              <TableRow key={p.principalId}>
                <TableCell className="text-sm">
                  {p.displayName}
                  <div className="font-mono text-[11px] text-muted-foreground">{p.username}</div>
                </TableCell>
                <TableCell>
                  {data.mayManage ? (
                    <Select value={p.teamId ?? NONE} disabled={busy}
                            onValueChange={(v) => act("/api/ui/teams/members",
                              { principal: p.principalId, team: v === NONE ? null : v })}>
                      <SelectTrigger className="w-64"><SelectValue /></SelectTrigger>
                      <SelectContent>
                        <SelectItem value={NONE}>No team</SelectItem>
                        {active.map((t) => (
                          <SelectItem key={t.id} value={t.id}>{t.name}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  ) : p.teamName ? (
                    <span className="text-sm">{p.teamName}</span>
                  ) : (
                    <Badge tone="unknown">no team</Badge>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <Pager paging={people} unit="people" />
      </Card>
    </div>
  );
}
