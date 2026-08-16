import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Plus, Trash2, Users } from "lucide-react";
import { api } from "@/lib/api";
import { Combobox } from "@/components/Combobox";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

interface Participant {
  id: string; principalId: string; displayName: string; username: string;
  addedAt: string; addedBy: string;
}
interface Payload {
  participants: Participant[];
  people: { id: string; name: string }[];
  mayManage: boolean;
}

/**
 * The delivery-side people on one assessment.
 *
 * An assessment is a conversation between the team that tested and the team that has to fix. The
 * second group holds none of the security team's permissions, and giving them any over the whole
 * organization to let them answer questions on one engagement would be the widest possible fix for
 * the narrowest possible need.
 *
 * So participation is per request: read it, comment on it, and say when a fix is in place. **Not
 * close a finding** — that stays with whoever retests, because a platform where the team being
 * assessed closes its own findings measures nothing.
 */
export function RequestParticipants({ requestId }: { requestId: string }) {
  const [data, setData] = useState<Payload | null>(null);
  const [person, setPerson] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.get<Payload>(`/api/ui/board/${requestId}/participants`)
      .then(setData).catch((e) => setError(e.message));
  }, [requestId]);
  useEffect(load, [load]);

  async function act(path: string, body: unknown) {
    setBusy(true); setError(null);
    try {
      await api.post(`/api/ui/board/${requestId}/participants${path}`, body);
      setPerson("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  if (!data) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2"><Users className="size-4" /> Delivery team</CardTitle>
        <CardDescription>
          They can read this request, comment on it, and mark a finding as fixed for retest. They
          cannot close one.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {error && <p className="text-sm text-destructive">{error}</p>}
        {data.participants.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            Nobody from the delivery side is on this request yet, so nobody outside the security team
            can answer a question on it.
          </p>
        ) : data.participants.map((p) => (
          <div key={p.id} className="flex items-center justify-between gap-2 rounded-md border px-3 py-2">
            <div className="min-w-0">
              <Link to={`/access/users/${p.principalId}`}
                    className="text-sm text-primary hover:underline">
                {p.displayName || p.username}
              </Link>
              <div className="text-[11px] text-muted-foreground">
                {p.username} · added {p.addedAt}{p.addedBy ? ` by ${p.addedBy}` : ""}
              </div>
            </div>
            {data.mayManage && (
              <Button variant="ghost" size="sm" disabled={busy} aria-label="Remove from this request"
                      onClick={() => act("/remove", { participant: p.id })}><Trash2 /></Button>
            )}
          </div>
        ))}

        {data.mayManage && (
          <div className="flex flex-wrap items-end gap-3 border-t pt-3">
            <div className="min-w-52 flex-1">
              <Combobox items={data.people.map((p) => {
                          const [name, hint] = p.name.split("·").map((x) => x.trim());
                          return { id: p.id, name: name || p.name, hint };
                        })}
                        value={person} onChange={setPerson}
                        placeholder="Add somebody" clearLabel="" />
            </div>
            <Button size="sm" disabled={busy || !person}
                    onClick={() => act("", { principal: person })}>
              <Plus /> Add
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
