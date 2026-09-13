import { useCallback, useEffect, useState } from "react";
import { Loader2, Sparkles } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

interface Capability {
  code: string; suggestionKind: string; surface: string; dataCategory: string;
  enabled: boolean; pending: number; onDemand?: boolean;
}
interface RunResult { capability: string; considered: number; proposed: number; detail: string; throttled?: boolean }
interface Reply { runs: RunResult[]; proposed: number; ranNothing: boolean; throttled?: boolean; retryAfterSeconds?: number }

/**
 * "Analyse with AI" — one button per dashboard, pressed by a person.
 *
 * <h2>Why a button rather than a schedule</h2>
 *
 * Analysis sends a tenant's data to a third party. That has to stay a DECISION, and a decision has to
 * have a moment: somebody looking at a screen, choosing to ask about it. A scheduled sweep makes the
 * same egress a background fact nobody remembers agreeing to, and `PRD-AIC-056` forbids invoking a
 * capability on view for exactly that reason. The button is the whole safeguard, expressed as
 * interaction rather than as policy.
 *
 * <h2>It says what it will send before it sends it</h2>
 *
 * The tooltip and the badge name every capability that will run and what each may read — AGGREGATE
 * (counts, dates, identifiers) or RECORD (the text of findings, which contains attacker-authored
 * strings and secrets recovered from customer code). A button that reveals its data category only in
 * a settings page somewhere else is a button people press without knowing what left.
 *
 * <h2>Nothing switched on means nothing runs, and it says so</h2>
 *
 * Capabilities ship disabled. Pressing this when none is enabled reports that rather than appearing
 * broken — the commonest state on a fresh deployment, and the one where a silent no-op would be read
 * as a failure.
 */
export function AnalyseButton({ surface, onDone }: {
  /** The route whose capabilities to run, e.g. "/vulnerabilities". */
  surface: string;
  onDone?: () => void;
}) {
  const [capabilities, setCapabilities] = useState<Capability[] | null>(null);
  const [mayRun, setMayRun] = useState(false);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [runs, setRuns] = useState<RunResult[] | null>(null);
  /** Seconds the provider asked us to wait after a 429; the button re-enables when it reaches zero. */
  const [wait, setWait] = useState(0);

  const load = useCallback(() => {
    api.get<{ capabilities: Capability[]; mayPromote: boolean }>("/api/ui/suggestions?limit=1")
      .then((d) => {
        // On-demand capabilities (a typed question, a draft button) are not something this runs.
        setCapabilities(d.capabilities.filter((c) => c.surface === surface && !c.onDemand));
        setMayRun(d.mayPromote);
      })
      .catch(() => setCapabilities([]));
  }, [surface]);
  useEffect(load, [load]);
  useEffect(() => {
    if (wait <= 0) return;
    const t = setTimeout(() => setWait((w) => w - 1), 1000);
    return () => clearTimeout(t);
  }, [wait]);

  async function run() {
    setBusy(true);
    setNote(null);
    setRuns(null);
    try {
      const r = await api.post<Reply>("/api/ui/agents/analyse", { surface });
      setRuns(r.runs);
      if (r.throttled) setWait(Math.max(r.retryAfterSeconds ?? 0, 15));
      setNote(r.ranNothing
        ? "No capability is switched on for this dashboard yet — turn one on in Configuration."
        : r.proposed === 0
          ? "Nothing new to suggest."
          : `${r.proposed} new suggestion${r.proposed === 1 ? "" : "s"} — ${r.runs
              .filter((x) => x.proposed > 0)
              .map((x) => `${x.capability} ${x.proposed}`).join(", ")}`);
      onDone?.();
      load();
    } catch (e) {
      setNote(e instanceof Error ? e.message : "The analysis could not be run.");
    } finally {
      setBusy(false);
    }
  }

  if (!capabilities || capabilities.length === 0) return null;
  if (!mayRun) {
    // Said, not hidden (PRD-UIX-024): a button that vanishes for want of a permission reads as a
    // feature that does not work.
    return (
      <span className="text-[11px] text-muted-foreground" title="aic.suggestion.promote">
        Analyse with AI is available to people who may decide on suggestions; your role does not hold that permission.
      </span>
    );
  }
  const enabled = capabilities.filter((c) => c.enabled);
  const sendsRecords = enabled.some((c) => c.dataCategory === "RECORD");
  const throttledRuns = runs?.filter((x) => x.throttled) ?? [];

  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex items-center gap-2">
        {/* The warning sits ON the button, not behind it. Whether record content leaves is the fact
            that decides whether pressing this is acceptable, and it belongs where the finger is. */}
        {sendsRecords && (
          <Badge tone="warn" title="One of the capabilities that will run may read finding text">
            sends record content
          </Badge>
        )}
        <Button size="sm" variant="secondary" disabled={busy || enabled.length === 0 || wait > 0}
                title={enabled.length === 0
                  ? "Nothing is switched on for this dashboard"
                  : `Will run: ${enabled.map((c) => `${c.code} (${c.dataCategory})`).join(", ")}`}
                onClick={() => void run()}>
          {busy ? <Loader2 className="size-3.5 animate-spin" /> : <Sparkles className="size-3.5" />}
          {busy ? "Analysing…" : wait > 0 ? `Provider asked to wait · ${wait}s` : "Analyse with AI"}
          {enabled.length > 0 && (
            <span className="ml-1 tabular text-muted-foreground">{enabled.length}</span>
          )}
        </Button>
      </div>
      {note && <span className="max-w-96 text-right text-[11px] text-muted-foreground">{note}</span>}
      {throttledRuns.length > 0 && (
        <span className="max-w-96 text-right text-[11px] text-tone-warn">
          The model provider rate-limited {throttledRuns.length} capabilit{throttledRuns.length === 1 ? "y" : "ies"};
          those used the rules or were not attempted. Press again when the wait ends to let the model finish.
        </span>
      )}
      {runs && runs.length > 0 && (
        <details className="max-w-xl text-[11px] text-muted-foreground">
          <summary className="cursor-pointer text-right">What each capability did</summary>
          <ul className="mt-1 flex flex-col gap-0.5 text-left">
            {runs.map((x) => (
              <li key={x.capability}>
                <span className="font-mono text-foreground">{x.capability}</span>
                {x.throttled && <Badge tone="warn">rate-limited</Badge>}{" "}
                {x.proposed} proposed of {x.considered} considered — {x.detail}
              </li>
            ))}
          </ul>
        </details>
      )}
      {enabled.length === 0 && (
        <span className="text-[11px] text-tone-unknown">
          {capabilities.length} capabilit{capabilities.length === 1 ? "y" : "ies"} available, none
          switched on
        </span>
      )}
    </div>
  );
}
