import { useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { History, Loader2, Trash2, TriangleAlert } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DateField } from "@/components/DateField";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/** An assertion already on the record. */
export interface Attestation {
  id: string;
  assetId: string;
  assetName: string;
  performedFrom: string;
  performedTo: string;
  performedBy: string | null;
  evidenceRef: string | null;
  note: string | null;
  attestedByName: string | null;
  attestedAt: string | null;
  withdrawnAt: string | null;
  withdrawalReason: string | null;
}

/**
 * Recording a whole-application review that happened outside the platform.
 *
 * <h2>Why this is not "add an assessment"</h2>
 *
 * The platform has two ways of knowing a review happened, and they are not the same claim. It
 * OBSERVED one when it holds the request, the execution record, the transitions and the findings.
 * It has been TOLD about one when a person states that work happened between two dates. Both
 * discharge the periodic obligation; only the first is evidence.
 *
 * So this dialog is deliberately blunt about what it is doing. It says "you are asserting", it
 * requires a period rather than a single date — because that is how a past engagement is actually
 * remembered — and it asks for the report, marking the assertion as weaker when there is none.
 *
 * <h2>What it refuses</h2>
 *
 * A period that has not finished. Future work is a planned window, and a future-dated assertion would
 * move an application out of the population that is owed work — the one way this feature could
 * corrupt the figures it exists to correct (`PRD-ASM-022`).
 */
export function AttestReviewDialog({ assetId, assetName, existing = [], onSaved, trigger }: {
  assetId: string;
  assetName: string;
  /** Assertions already recorded for this application, withdrawn ones included. */
  existing?: Attestation[];
  onSaved: () => void;
  trigger: React.ReactNode;
}) {
  const today = new Date().toISOString().slice(0, 10);
  const [open, setOpen] = useState(false);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [performedBy, setPerformedBy] = useState("");
  const [evidence, setEvidence] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const complete = from !== "" && to !== "" && to >= from && to <= today;

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await api.post("/api/ui/assessment-plan/attestations", {
        assetId,
        performedFrom: from,
        performedTo: to,
        performedBy: performedBy.trim() || null,
        evidenceRef: evidence.trim() || null,
        note: note.trim() || null,
      });
      setFrom(""); setTo(""); setPerformedBy(""); setEvidence(""); setNote("");
      setOpen(false);
      onSaved();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  }

  async function withdraw(id: string) {
    // The reason is required by the endpoint, and rightly: "it was wrong" and "the evidence did not
    // support it" have different consequences for the figure the assertion was propping up.
    const reason = window.prompt(
      "Why is this assertion being withdrawn? The coverage figure it supported changes back.");
    if (!reason || !reason.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/ui/assessment-plan/attestations/${id}/withdraw`, { reason });
      onSaved();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog.Root open={open} onOpenChange={(next) => { setOpen(next); if (!next) setError(null); }}>
      <Dialog.Trigger asChild>{trigger}</Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/50" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 flex max-h-[88vh] w-[min(38rem,94vw)]
                                   -translate-x-1/2 -translate-y-1/2 flex-col gap-4 overflow-y-auto
                                   rounded-lg border bg-background p-5 shadow-xl">
          <div>
            <Dialog.Title className="text-sm font-semibold tracking-tight">
              Record a review already carried out — {assetName}
            </Dialog.Title>
            <Dialog.Description className="mt-1 text-xs text-muted-foreground">
              For work done before this platform held it. This is recorded as <b>asserted</b>, not
              observed: it resets the periodic clock and is labelled everywhere it appears, so nobody
              reads it as a review the platform can substantiate.
            </Dialog.Description>
          </div>

          {/* The other door, named. Somebody whose request IS in the platform should not be here —
              they should mark the request, which makes it count as the observed review it was. */}
          <p className="rounded-md border border-dashed px-3 py-2 text-[11px] text-muted-foreground">
            <b>Is the assessment already in the platform as a request?</b> Then use this only if it
            never ran here. Otherwise open the request and set its trigger to the periodic-review one —
            that records it as observed, with its findings and its history intact. The request board
            filters on <span className="font-medium">Trigger = none</span> to find them.
          </p>

          {existing.length > 0 && (
            <div className="overflow-hidden rounded-md border">
              <div className="border-b bg-muted/40 px-3 py-2 text-[11px] font-medium uppercase
                              tracking-wide text-muted-foreground">
                Already asserted for this application
              </div>
              <ul className="divide-y">
                {existing.map((a) => (
                  <li key={a.id} className="flex items-center gap-2 px-3 py-1.5 text-xs">
                    <span className="tabular font-medium">
                      {a.performedFrom} → {a.performedTo}
                    </span>
                    {a.performedBy && <span className="text-muted-foreground">{a.performedBy}</span>}
                    {a.evidenceRef
                      ? <Badge tone="ok">report attached</Badge>
                      : <Badge tone="unknown">no evidence</Badge>}
                    {a.withdrawnAt && <Badge tone="neutral">withdrawn</Badge>}
                    <span className="flex-1" />
                    {a.attestedByName && (
                      <span className="text-[10px] text-muted-foreground">
                        by {a.attestedByName}
                      </span>
                    )}
                    {!a.withdrawnAt && (
                      <Button variant="ghost" size="sm" className="h-5 px-1" disabled={busy}
                              aria-label={`Withdraw the assertion covering ${a.performedFrom}`}
                              title="Withdraw this assertion"
                              onClick={() => { void withdraw(a.id); }}>
                        <Trash2 className="size-3" />
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div className="flex flex-wrap gap-3">
            <div className="flex flex-col gap-1">
              <Label htmlFor="attest-from">Work started</Label>
              {/* A period, not a date. "The pentest was in Q2" is how it is remembered; forcing a
                  single day would put a made-up precision into the record. */}
              <DateField id="attest-from" value={from} max={today} className="h-8 w-40 text-xs"
                         onChange={setFrom} />
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor="attest-to">Work finished</Label>
              <DateField id="attest-to" value={to} min={from || undefined} max={today}
                         className="h-8 w-40 text-xs" onChange={setTo} />
            </div>
            <div className="flex min-w-48 flex-1 flex-col gap-1">
              <Label htmlFor="attest-by">Who carried it out</Label>
              <Input id="attest-by" value={performedBy} className="h-8 text-xs"
                     placeholder="e.g. an external firm, or an internal team"
                     onChange={(e) => setPerformedBy(e.target.value)} />
            </div>
          </div>

          <div className="flex flex-col gap-1">
            <Label htmlFor="attest-evidence">The report, if there is one</Label>
            <Input id="attest-evidence" value={evidence} className="h-8 text-xs"
                   placeholder="a filename, a document reference, or a location"
                   onChange={(e) => setEvidence(e.target.value)} />
            <p className="text-[11px] text-muted-foreground">
              Optional, and the difference is visible: an assertion with no evidence behind it is
              shown as the weaker claim it is, rather than looking identical to one that has a report.
            </p>
          </div>

          <div className="flex flex-col gap-1">
            <Label htmlFor="attest-note">Note (optional)</Label>
            <Input id="attest-note" value={note} className="h-8 text-xs"
                   placeholder="scope, limitations, anything the next reader needs"
                   onChange={(e) => setNote(e.target.value)} />
          </div>

          {to !== "" && to > today && (
            <p className="flex items-start gap-1.5 text-[11px] text-destructive">
              <TriangleAlert className="mt-px size-3.5 shrink-0" />
              That period has not finished. This records history — to plan future work, use Plan.
            </p>
          )}
          {error && <p className="text-[11px] text-destructive">{error}</p>}

          <div className="flex items-center justify-between gap-3 border-t pt-3">
            <span className="text-[11px] text-muted-foreground">
              Recorded against your name, and shown with it.
            </span>
            <span className="flex items-center gap-2">
              <Dialog.Close asChild>
                <Button variant="ghost" size="sm">Cancel</Button>
              </Dialog.Close>
              <Button size="sm" disabled={!complete || busy} onClick={save}>
                {busy ? <Loader2 className="size-3 animate-spin" /> : <History className="size-3" />}
                Record this review
              </Button>
            </span>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
