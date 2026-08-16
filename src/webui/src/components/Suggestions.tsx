import { useCallback, useEffect, useState } from "react";
import { Check, Play, Sparkles, X } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

interface Row {
  id: string; kind: string; subjectKind: string; subjectId: string; subjectLabel: string;
  headline: string; detail: string; recommendation: string; grounding: string[];
  modelIdentity: string; promptVersion: string; confidenceBand: string; generatedAt: string;
  /** CURRENT, STALE, or UNKNOWN — three answers, because "not recorded" is not "current". */
  freshness: string;
}
interface Capability {
  code: string; suggestionKind: string; subjectKind: string; surface: string;
  dataCategory: string; enabled: boolean; maxPerRun: number; pending: number;
  promoted: number; rejected: number; withdrawn: number; lastDecidedAt: string;
}
interface Payload {
  rows: Row[]; capabilities: Capability[]; mayPromote: boolean; mayManage: boolean;
}

const BAND: Record<string, "ok" | "warn" | "neutral"> = {
  HIGH: "ok", MEDIUM: "warn", LOW: "neutral",
};

/**
 * What this capability's output has been worth, in words a person can act on.
 *
 * Counts and never a percentage below a handful of decisions: "3 of 4 accepted" is honest, "75%" over
 * the same four is a number that reads like evidence and is not. The threshold is stated in the text
 * rather than hidden, so a reader can see they are looking at too little to judge — which is itself
 * the useful answer when a capability has been on for a month and decided nothing.
 */
function record(c: Capability): { text: string; tone: "ok" | "warn" | "neutral" } {
  const decided = c.promoted + c.rejected;
  if (decided === 0) {
    return {
      text: c.pending > 0 ? `${c.pending} waiting, none judged yet` : "nothing judged yet",
      tone: "neutral",
    };
  }
  const share = decided < 5
    ? `${c.promoted} of ${decided} accepted`
    : `${Math.round((c.promoted / decided) * 100)}% accepted of ${decided}`;
  const withdrawn = c.withdrawn > 0 ? ` · ${c.withdrawn} went out of date` : "";
  // Below a third accepted is the signal worth colouring: a capability whose output is mostly
  // rejected is costing review time, and the switch beside it is the answer.
  const tone = decided >= 5 && c.promoted / decided < 0.34 ? "warn" : "ok";
  return { text: share + withdrawn, tone };
}

const FRESHNESS: Record<string, { tone: "warn" | "neutral"; label: string; title: string }> = {
  STALE: {
    tone: "warn",
    label: "out of date",
    title: "The record has changed since this was generated, so it describes a state that no longer "
      + "exists. It cannot be accepted; run the capability again to get a current one.",
  },
  UNKNOWN: {
    tone: "neutral",
    label: "freshness unknown",
    title: "This was generated before the ledger recorded which version of the record it was about, "
      + "so whether it is still true cannot be determined. It cannot be accepted.",
  },
};

/**
 * The AI review queue — where a suggestion becomes a decision, or does not.
 *
 * <h2>This panel is the reason the ledger is not decoration</h2>
 *
 * ADR-005 says AI writes only to a suggestion ledger and that promotion into the record is an audited
 * human action. The ledger table existed from the start, held zero rows, and had no reader — so the
 * rule was satisfied on paper and absent in fact: there was no way for a human to take the action the
 * rule is about. This is that action.
 *
 * <h2>Accepting does not change the finding</h2>
 *
 * It records that a named person accepted the suggestion. Applying it goes through the finding's own
 * write path, with that path's permission and validation. Wiring "accept" straight into the record
 * would give AI output a second route into the system of record — the thing ADR-005 forbids — dressed
 * as a convenience, and the button would be the least suspicious place to put it.
 *
 * <h2>Every suggestion shows its working</h2>
 *
 * The grounding chips are the records the sentence rests on, and the footer names what produced it and
 * which prompt version. A reviewer's question is always "why does it say that". If the only available
 * answer were "the model said so", nobody should promote anything — so the answer is always present,
 * and it is present for the rules version too.
 */
export function Suggestions({ subject, defaultKind }: {
  /** Limit to one record's suggestions, e.g. beside a finding. */
  subject?: string;
  defaultKind?: string;
}) {
  const [data, setData] = useState<Payload | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [note, setNote] = useState<string | null>(null);

  const load = useCallback(() => {
    const q = new URLSearchParams();
    if (subject) q.set("subject", subject);
    if (defaultKind) q.set("kind", defaultKind);
    api.get<Payload>(`/api/ui/suggestions?${q.toString()}`)
      .then(setData).catch(() => setData(null));
  }, [subject, defaultKind]);
  useEffect(load, [load]);

  async function decide(id: string, promote: boolean) {
    setBusy(id);
    try {
      await api.post(`/api/ui/suggestions/${id}/decide`,
        promote ? { promote: true } : { promote: false, reason });
      setRejecting(null);
      setReason("");
      load();
    } catch (e) {
      setNote(e instanceof Error ? e.message : "That could not be recorded.");
    } finally {
      setBusy(null);
    }
  }

  async function run(code: string) {
    setBusy(code);
    setNote(null);
    try {
      const r = await api.post<{ proposed: number; considered: number; detail: string }>(
        `/api/ui/agents/${code}/run`, {});
      setNote(`${code}: ${r.proposed} proposed of ${r.considered} considered — ${r.detail}`);
      load();
    } catch (e) {
      setNote(e instanceof Error ? e.message : "The run failed.");
    } finally {
      setBusy(null);
    }
  }

  async function toggle(c: Capability) {
    setBusy(c.code);
    try {
      await api.post(`/api/ui/agents/${c.code}/run`, { enabled: !c.enabled });
      load();
    } finally {
      setBusy(null);
    }
  }

  if (!data) return null;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2">
          <Sparkles className="size-4 text-primary" /> AI suggestions
          {data.rows.length > 0 && <Badge tone="info">{data.rows.length} waiting</Badge>}
        </CardTitle>
        <CardDescription>
          Nothing here has changed any record. Each is a proposal with the evidence it rests on;
          accepting records that you accepted it, and applying it is still your own edit on the
          finding.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {note && <p className="text-xs text-muted-foreground">{note}</p>}

        {data.mayManage && (
          <div className="flex flex-col gap-1.5 rounded border border-border p-2">
            <span className="text-[11px] font-medium text-muted-foreground">
              Capabilities — all off until switched on, and none runs because a page was opened
            </span>
            <div className="flex flex-col gap-1">
              {data.capabilities.map((c) => (
                <span key={c.code} className="flex flex-wrap items-center gap-1">
                  <Button size="sm" variant={c.enabled ? "secondary" : "ghost"}
                          disabled={busy === c.code} onClick={() => void toggle(c)}
                          title={`${c.suggestionKind} · reviewed on ${c.surface} · may read ${c.dataCategory}`}>
                    {c.code}
                    {/* The data category on the chip, because "may read record content" is the
                        decision that matters most and it should not be one click away. */}
                    <Badge tone={c.dataCategory === "RECORD" ? "warn" : "neutral"}>
                      {c.dataCategory}
                    </Badge>
                  </Button>
                  {c.enabled && (
                    <Button size="sm" variant="ghost" disabled={busy === c.code}
                            aria-label={`Run ${c.code}`} onClick={() => void run(c.code)}>
                      <Play className="size-3" />
                    </Button>
                  )}
                  {/* What its output has been worth. Beside the switch, because this is the only
                      thing that makes turning one off a decision somebody can defend rather than a
                      preference — and the figure is useless anywhere the switch is not. */}
                  <Badge tone={record(c).tone}>{record(c).text}</Badge>
                </span>
              ))}
            </div>
          </div>
        )}

        {data.rows.length === 0 && (
          <p className="text-xs italic text-tone-unknown">
            Nothing waiting. {data.mayManage
              ? "Enable a capability above and run it to see what it proposes."
              : "Suggestions appear here when a capability has been run."}
          </p>
        )}

        {data.rows.map((row) => (
          <div key={row.id} className="flex flex-col gap-1.5 rounded border border-border p-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone="info">{row.kind.replace(/_/g, " ").toLowerCase()}</Badge>
              <span className="text-sm font-medium">{row.headline}</span>
              <Badge tone={BAND[row.confidenceBand] ?? "neutral"}>
                {row.confidenceBand?.toLowerCase()} confidence
              </Badge>
              {FRESHNESS[row.freshness] && (
                <Badge tone={FRESHNESS[row.freshness].tone} title={FRESHNESS[row.freshness].title}>
                  {FRESHNESS[row.freshness].label}
                </Badge>
              )}
            </div>
            <p className="text-xs text-muted-foreground">
              <span className="font-medium text-foreground">{row.subjectLabel}</span> — {row.detail}
            </p>
            {row.recommendation && (
              <p className="text-xs text-muted-foreground">{row.recommendation}</p>
            )}
            {/* The working. Not collapsed behind a disclosure: a reviewer who has to click to find
                out why will promote without clicking. */}
            <div className="flex flex-wrap gap-1">
              {row.grounding.map((g) => (
                <span key={g}
                      className="rounded bg-muted px-1.5 py-0.5 font-mono text-[10px]
                                 text-muted-foreground">
                  {g}
                </span>
              ))}
            </div>
            <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
              <span className="text-[10px] text-muted-foreground">
                {/* Never absent. The schema refuses a suggestion that cannot say what produced it. */}
                {row.modelIdentity} · {row.promptVersion} · {row.generatedAt}
              </span>
              {data.mayPromote && (
                rejecting === row.id ? (
                  <span className="flex items-center gap-1.5">
                    <Input value={reason} placeholder="why not?" className="h-7 w-48 text-xs"
                           onChange={(e) => setReason(e.target.value)} />
                    <Button size="sm" variant="destructive" disabled={busy === row.id}
                            onClick={() => void decide(row.id, false)}>Reject</Button>
                    <Button size="sm" variant="ghost"
                            onClick={() => { setRejecting(null); setReason(""); }}>Cancel</Button>
                  </span>
                ) : (
                  <span className="flex items-center gap-1.5">
                    {/* Accept is disabled rather than hidden for anything not CURRENT. Hiding it
                        would leave a reviewer wondering whether they lack the permission; the
                        disabled button with the reason on hover says which of the two it is — and
                        the server refuses it as well, because a disabled button is not a control. */}
                    <Button size="sm" variant="secondary"
                            disabled={busy === row.id || row.freshness !== "CURRENT"}
                            title={FRESHNESS[row.freshness]?.title}
                            onClick={() => void decide(row.id, true)}>
                      <Check className="size-3.5" /> Accept
                    </Button>
                    {/* Rejection needs a reason — the schema refuses one without. A rejected
                        suggestion nobody explained teaches the next reviewer nothing. */}
                    <Button size="sm" variant="ghost" disabled={busy === row.id}
                            onClick={() => setRejecting(row.id)}>
                      <X className="size-3.5" /> Reject
                    </Button>
                  </span>
                )
              )}
            </div>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
