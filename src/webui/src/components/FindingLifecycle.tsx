import { useCallback, useEffect, useState } from "react";
import { CalendarClock, CheckCircle2, History, Loader2, RotateCcw, ShieldAlert, Wrench } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { DateField } from "@/components/DateField";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

/** Named once, so the message and the reason a button is disabled cannot drift apart. */
const ACCEPT_PERMISSION = "vul.finding.acceptrisk";

interface Move {
  to: string; label: string; permission: string; permitted: boolean;
  reason: string | null; needsDate: boolean;
}
interface Entry {
  from: string; fromLabel: string; to: string; toLabel: string; note: string;
  acceptedUntil: string | null; occurredAt: string; actor: string;
}
interface View {
  state: string; label: string; moves: Move[]; history: Entry[];
  claimedAt: string | null; claimedBy: string | null;
  acceptedUntil: string | null; proposedUntil: string | null; acceptedReason: string | null;
  closureReason: string | null; verifiedBy: string | null; recurrenceCount: number;
  otherApprovers: number;
}

/**
 * The tone each state wears. Not severity tones: a finding's severity and its position in the
 * lifecycle are different questions, and painting "closed" in the same green a low severity uses
 * invites reading one as the other.
 *
 * FIXED is `warn` on purpose. "The team says it is done" is the state most likely to be misread as
 * finished, and it is the one state where nobody has checked anything — so it looks unresolved,
 * because it is.
 */
const TONE: Record<string, "ok" | "warn" | "info" | "neutral" | "unknown"> = {
  OPEN: "neutral",
  FIXED: "warn",
  CLOSED: "ok",
  REOPEN: "warn",
  // Requested is not accepted. Nothing has been agreed, and a pending request that looked settled
  // would be the easiest number in the platform to improve without improving anything.
  ACCEPTANCE_REQUESTED: "warn",
  ACCEPTED_RISK: "info",
};

const ICON: Record<string, typeof Wrench> = {
  FIXED: Wrench,
  ACCEPTANCE_REQUESTED: ShieldAlert,
  CLOSED: CheckCircle2,
  REOPEN: RotateCcw,
  ACCEPTED_RISK: ShieldAlert,
  OPEN: RotateCcw,
};

/**
 * What each state means, written from the seat of the person reading it.
 *
 * The server sends a label for every state; these are the longer sentences the dropdown needs, because
 * a state name alone does not answer "why would I choose this". "Fix reported" was the one nobody could
 * explain: it exists so that a finding the delivery team says is done stops looking like a finding
 * nobody has touched, and starts appearing in the list of things waiting to be retested. That list is
 * the entire reason the state exists — without it, "claimed three weeks ago and never checked" is
 * indistinguishable from "untouched for three weeks".
 */
const MEANS: Record<string, string> = {
  OPEN: "Nobody has reported a fix. It is open work.",
  FIXED: "The delivery team says it is fixed. Nothing is verified yet — it joins the list of findings "
    + "waiting to be retested, so it stops looking untouched.",
  CLOSED: "You retested it and the fix held. This is the only way a finding closes as fixed.",
  REOPEN: "You retested it and it still reproduces. Different from never-fixed, and worth counting.",
  ACCEPTANCE_REQUESTED: "Somebody has asked to leave this in place until a date. It stays open work "
    + "until a second person approves.",
  ACCEPTED_RISK: "Left in place deliberately, until a date, agreed by two people.",
};

/**
 * Where a finding is, what may be done to it, and the record of how it got there.
 *
 * <h2>One dropdown, one Save — not a row of buttons</h2>
 *
 * The first version drew a button per available move, and pressing one opened a note form whose confirm
 * button carried THE SAME LABEL. So the first press appeared to do nothing, and the obvious response
 * was to press it again. A picker plus one Save has no such ambiguity: choosing is not committing, and
 * exactly one control commits.
 *
 * <h2>The states you cannot reach are still listed, disabled, with the reason</h2>
 *
 * An option that is simply absent teaches nobody. "You do not hold vul.finding.verify" sends somebody
 * to ask for the right thing; "a leaked secret cannot be accepted" is a rule worth restating each time
 * it applies. Neither reveals anything the reader cannot already see — they are reading this finding.
 *
 * <h2>The note is required, and the Save button says so before it is pressed</h2>
 *
 * The database refuses a transition without one. The reviews that need it happen months later, when
 * whoever moved it has forgotten why.
 *
 * <h2>The history is not collapsed</h2>
 *
 * "Closed, reopened twice, then accepted" is the sentence that makes tracking a finding worth more than
 * counting one. Behind a disclosure it is a sentence nobody reads.
 */
export function FindingLifecycle({ findingId, onMoved }: {
  findingId: string;
  onMoved?: () => void;
}) {
  const [view, setView] = useState<View | null>(null);
  const [target, setTarget] = useState("");
  const [note, setNote] = useState("");
  const [until, setUntil] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** Set the first time Save is pressed, so the missing-note message appears when it is earned. */
  const [attempted, setAttempted] = useState(false);

  const load = useCallback(() => {
    api.get<View>(`/api/ui/findings/${findingId}/lifecycle`)
      .then((d) => { setView(d); setTarget(""); setAttempted(false); })
      .catch(() => setView(null));
  }, [findingId]);
  useEffect(load, [load]);

  const picked = view?.moves.find((m) => m.to === target) ?? null;

  async function save() {
    if (!picked) return;
    // The check lives HERE rather than on the button's disabled attribute. A disabled button gives no
    // feedback at all: the reported symptom was "I choose a state and nothing happens", and a control
    // that stays grey with an 11px explanation beside it is indistinguishable from one that is broken.
    // Pressing it now always does something — either the change, or a sentence saying what is missing
    // with the cursor placed in the field that is missing it.
    if (note.trim().length < 3) {
      setAttempted(true);
      setError(null);
      document.getElementById("transition-note")?.focus();
      return;
    }
    if (picked.needsDate && !until) {
      setAttempted(true);
      setError(null);
      document.getElementById("transition-until")?.focus();
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/ui/findings/${findingId}/transition`, {
        to: picked.to, note, until: picked.needsDate ? until : null,
      });
      setNote("");
      setUntil("");
      setAttempted(false);
      load();
      onMoved?.();
    } catch (e) {
      // The server's sentence, not a generic failure. It is the only place that knows whether this was
      // "you may not" or "that move does not exist from here", and those need different reactions.
      setError(e instanceof Error ? e.message : "That change was refused.");
    } finally {
      setBusy(false);
    }
  }

  if (!view) return null;
  const StateIcon = ICON[view.state] ?? RotateCcw;
  const noteMissing = attempted && note.trim().length < 3;
  const dateMissing = attempted && !!picked?.needsDate && until === "";

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex flex-wrap items-center gap-2">
          <StateIcon className="size-4 text-muted-foreground" />
          Status
          <Badge tone={TONE[view.state] ?? "neutral"}>{view.label}</Badge>
          {/* Only shown when it has happened. A "reopened 0 times" chip on every finding trains people
              to stop reading the chip. */}
          {view.recurrenceCount > 0 && (
            <Badge tone="warn" title="Closed and verified, then came back">
              came back {view.recurrenceCount}×
            </Badge>
          )}
        </CardTitle>
        <CardDescription>
          {view.state === "FIXED" && view.claimedBy
            ? `${view.claimedBy} reported this fixed on ${(view.claimedAt ?? "").slice(0, 10)}. `
              + "Nobody has verified it yet — a claim is not a closure."
            : view.state === "CLOSED" && view.verifiedBy
              ? `Verified by ${view.verifiedBy}. Reopen it if the weakness comes back.`
              : view.state === "ACCEPTANCE_REQUESTED"
                ? `Somebody has asked to accept this risk until ${view.proposedUntil ?? "an unstated date"}`
                  + ". It needs a SECOND approver — whoever asked cannot approve their own request "
                  + "(INV-VUL-26) — and until somebody does, this is still open work and nothing has "
                  + "been agreed."
                  + (view.otherApprovers === 0
                    ? ` Nobody else currently holds ${ACCEPT_PERMISSION}, so this request cannot be`
                      + " approved by anyone as things stand."
                    : "")
                : view.state === "ACCEPTED_RISK"
                  ? `Accepted until ${view.acceptedUntil ?? "an unrecorded date"}`
                    + (view.acceptedReason ? ` — ${view.acceptedReason}` : "")
                  : view.state === "REOPEN"
                    ? "A fix was reported and did not hold. It is open work again."
                    : "Nobody has reported a fix for this yet."}
        </CardDescription>
      </CardHeader>

      <CardContent className="flex flex-col gap-3">
        {view.moves.length === 0 ? (
          <p className="text-xs italic text-tone-unknown">
            There is nothing to change from here.
          </p>
        ) : (
          <div className="flex flex-col gap-2 rounded border border-border p-3">
            <div className="flex flex-col gap-1">
              <Label htmlFor="lifecycle-target">Change status to</Label>
              <Select value={target}
                      onValueChange={(v) => { setTarget(v); setError(null); }}>
                <SelectTrigger id="lifecycle-target" className="w-full sm:w-96">
                  <SelectValue placeholder="Choose a status…" />
                </SelectTrigger>
                {/* The cursor lands in the note the moment a status is chosen. Choosing and then
                    having to hunt for what to do next is the whole of the reported problem.
                    
                    Done through the select's own close hook, not a timer. Two earlier attempts set the
                    focus on a setTimeout — at 0ms and at 120ms — and both lost: the component restores
                    focus to its trigger as it closes, and it does so after either delay. Racing a
                    library's own behaviour with a guessed number is how a fix works on one machine.
                    preventDefault stops that restore, and then the focus is ours. */}
                <SelectContent
                    onCloseAutoFocus={(e) => {
                      e.preventDefault();
                      document.getElementById("transition-note")?.focus();
                    }}>
                  {view.moves.map((m) => (
                    <SelectItem key={m.to} value={m.to} disabled={!m.permitted}>
                      <span className="flex flex-col gap-0.5 py-0.5">
                        <span>{m.label}</span>
                        {/* The reason sits ON the row it explains. Collected below the card instead,
                            three or four of them became a wall of small text under a control, and the
                            one a person needed was not the one their eye was on. */}
                        {!m.permitted && m.reason && (
                          <span className="max-w-[26rem] text-[11px] text-muted-foreground">
                            {m.reason}
                          </span>
                        )}
                      </span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {/* STACKED under the picker, and never disabled.
                
                Both were defects. Side by side on a wide screen the note sat far to the right of the
                control that had just been used, and it arrived greyed out — so the page opened showing
                two dead inputs and a dead button, which is what a broken feature looks like. It is one
                column now, in the order the work happens, and every field is live from the start. */}
            <div className="flex flex-col gap-1">
              <Label htmlFor="transition-note">
                Why <span className="text-sev-critical">*</span>
              </Label>
              <Input id="transition-note" value={note}
                     className={noteMissing ? "border-sev-critical" : undefined}
                     aria-invalid={noteMissing}
                     placeholder={target === "CLOSED"
                       ? "how you verified it"
                       : target === "REOPEN"
                         ? "what still reproduces"
                         : target === "FIXED"
                           ? "what the team changed, and where it is deployed"
                           : target === "ACCEPTANCE_REQUESTED"
                             ? "why this is being left in place rather than fixed"
                             : "a sentence whoever reviews this will need"}
                     onChange={(e) => { setNote(e.target.value); setError(null); }} />
              {noteMissing && (
                <span className="text-[11px] text-sev-critical">
                  A note is required — this is what the next person to read the finding sees.
                </span>
              )}
            </div>

            {picked?.needsDate && (
              <div className="flex flex-col gap-1">
                <Label htmlFor="transition-until">
                  Accepted until <span className="text-sev-critical">*</span>
                </Label>
                <DateField id="transition-until" value={until} onChange={setUntil} />
                {dateMissing && (
                  <span className="text-[11px] text-sev-critical">A date is required.</span>
                )}
              </div>
            )}

            <div className="flex items-center gap-2">
              {/* Disabled only when there is genuinely nothing to save — no status chosen. Once one is,
                  the button works: it either saves or says what is missing. */}
              <Button size="sm" disabled={busy || !picked} onClick={() => void save()}>
                {busy ? <Loader2 className="size-3.5 animate-spin" /> : null}
                {busy ? "Saving…" : "Save status"}
              </Button>
              {target && (
                <Button size="sm" variant="ghost"
                        onClick={() => { setTarget(""); setNote(""); setUntil("");
                                         setAttempted(false); setError(null); }}>
                  Cancel
                </Button>
              )}
            </div>
          </div>
        )}

        {/* What the chosen option means. Answers "why would I pick this" at the moment it is asked,
            which is the question the old button row never answered. */}
        {picked && MEANS[picked.to] && (
          <p className="text-[11px] text-muted-foreground">{MEANS[picked.to]}</p>
        )}

        {picked?.needsDate && (
          <p className="text-[11px] text-muted-foreground">
            The date is required. An acceptance with no end is not an acceptance — it is a decision to
            stop looking, and the two must not look the same in a year's time. A risk exception is
            recorded against this finding and expires on this date.
          </p>
        )}

        {/* Said before the request is made, not after it stalls. INV-VUL-26 forbids approving your own
            acceptance, so with nobody else holding the permission the request would sit awaiting
            approval indefinitely — and a request that looks like progress is worse than an option that
            was never offered. */}
        {target === "ACCEPTANCE_REQUESTED" && view.otherApprovers === 0 && (
          <p className="text-[11px] text-tone-warn">
            Nobody else currently holds {ACCEPT_PERMISSION}, and you cannot approve your own request.
            This will stay awaiting approval until a second person is granted it.
          </p>
        )}


        {error && <p className="text-xs text-sev-critical">{error}</p>}

        {view.history.length > 0 && (
          <div className="flex flex-col gap-1.5 border-t border-border pt-2">
            <span className="flex items-center gap-1.5 text-[11px] font-medium text-muted-foreground">
              <History className="size-3" /> How it got here
            </span>
            {view.history.map((h, i) => (
              <div key={`${h.occurredAt}-${i}`} className="flex flex-wrap items-baseline gap-1.5 text-xs">
                <span className="tabular text-muted-foreground">{h.occurredAt.slice(0, 16)}</span>
                <Badge tone={TONE[h.to] ?? "neutral"}>{h.toLabel}</Badge>
                <span className="text-muted-foreground">from {h.fromLabel.toLowerCase()}</span>
                <span className="font-medium">{h.actor}</span>
                <span className="text-muted-foreground">— {h.note}</span>
                {h.acceptedUntil && (
                  <span className="flex items-center gap-1 text-muted-foreground">
                    <CalendarClock className="size-3" /> until {h.acceptedUntil}
                  </span>
                )}
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
