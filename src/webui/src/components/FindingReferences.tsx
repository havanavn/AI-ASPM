import { useCallback, useEffect, useState } from "react";
import { ExternalLink, Send } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

/**
 * "Create in Jira" — and its consequences — on a finding.
 *
 * A reference is one-way: the ticket carries a summary and a link, and what the tracker later says is
 * shown here as observed state. When it disagrees with the platform record the disagreement is shown
 * as a divergence with a decision box, because the platform does not guess whether a closed ticket
 * means a fixed vulnerability (ADR-040, PRD-CON-043).
 */

interface Reference {
  id: string; connectorId: string; connectorName: string; kind: string; status: string;
  externalKey: string | null; externalUrl: string | null; externalState: string | null; externalResolved: boolean | null;
  lastObservedAt: string | null; divergenceKind: string | null; divergenceDetectedAt: string | null;
  divergencePlatformState: string | null; failureDetail: string | null; createdAt: string; createdByName: string;
}
interface Offer { connectorId: string; code: string; displayName: string; kind: string }
interface View { rows: Reference[]; offers: Offer[]; mayCreate: boolean }

export function FindingReferences({ findingId }: { findingId: string }) {
  const [view, setView] = useState<View | null>(null);
  const [chosen, setChosen] = useState("");
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.get<View>(`/api/ui/findings/${findingId}/references`).then(setView).catch(() => setView(null));
  }, [findingId]);
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

  if (!view) return null;
  if (view.rows.length === 0 && (!view.mayCreate || view.offers.length === 0)) return null;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-base">External tracker</CardTitle>
        <CardDescription>
          A ticket created from here carries a summary and a link back — not the write-up. This record stays authoritative;
          a ticket closed over there does not close the finding.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {view.rows.map((r) => (
          <div key={r.id} className="rounded-md border p-3 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{r.connectorName}</span>
              <Badge tone={r.status === "LINKED" ? "ok" : r.status === "PENDING" ? "info" : "critical"}>{r.status}</Badge>
              {r.externalKey && (r.externalUrl
                ? <a className="inline-flex items-center gap-1 underline-offset-2 hover:underline" href={r.externalUrl} target="_blank" rel="noreferrer">{r.externalKey} <ExternalLink className="size-3" /></a>
                : <span>{r.externalKey}</span>)}
              {r.externalState && <span className="text-xs text-muted-foreground">tracker says <span className="font-mono">{r.externalState}</span>
                {r.lastObservedAt ? ` · seen ${r.lastObservedAt.replace("T", " ").slice(0, 16)}` : ""}</span>}
            </div>
            <div className="mt-1 text-xs text-muted-foreground">asked by {r.createdByName} · {r.createdAt.replace("T", " ").slice(0, 16)}</div>
            {r.status === "FAILED" && (
              <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-destructive">
                {r.failureDetail}
                {view.mayCreate && <Button size="sm" variant="outline" disabled={busy} onClick={() => act(`/api/ui/outbound-references/${r.id}/retry`, {})}>Retry</Button>}
              </div>
            )}
            {r.divergenceKind && (
              <div className="mt-2 rounded-md border border-tone-warn/40 bg-tone-warn/10 p-2 text-xs">
                <div className="flex items-center gap-2"><Badge tone="warn">{r.divergenceKind}</Badge>
                  <span>The tracker and this record disagree (platform was {r.divergencePlatformState}). Nothing was changed here; decide.</span></div>
                {view.mayCreate && (
                  <div className="mt-2 flex flex-wrap gap-2">
                    <Input className="max-w-md" placeholder="What was decided, and why (at least 10 characters)"
                           value={notes[r.id] ?? ""} onChange={(e) => setNotes({ ...notes, [r.id]: e.target.value })} />
                    <Button size="sm" variant="outline" disabled={busy} onClick={() => act(`/api/ui/outbound-references/${r.id}/resolve`, { note: notes[r.id] ?? "" })}>Resolve</Button>
                  </div>
                )}
              </div>
            )}
          </div>
        ))}
        {view.mayCreate && view.offers.length > 0 && (
          <div className="flex flex-wrap items-end gap-2">
            <div className="flex min-w-56 flex-col gap-1">
              <Select value={chosen} onValueChange={setChosen}>
                <SelectTrigger><SelectValue placeholder="Create a ticket in…" /></SelectTrigger>
                <SelectContent>{view.offers.map((o) => <SelectItem key={o.connectorId} value={o.connectorId}>{o.displayName}</SelectItem>)}</SelectContent>
              </Select>
            </div>
            <Button size="sm" disabled={busy || !chosen} onClick={() => { act(`/api/ui/findings/${findingId}/references`, { connectorId: chosen }); setChosen(""); }}>
              <Send className="size-3" /> Create reference
            </Button>
          </div>
        )}
        {error && <p className="text-sm text-destructive">{error}</p>}
      </CardContent>
    </Card>
  );
}
