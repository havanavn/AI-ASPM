import { useCallback, useEffect, useState } from "react";
import { Loader2, Sparkles } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

interface Capability {
  code: string; suggestionKind: string; surface: string; dataCategory: string;
  enabled: boolean; pending: number;
}

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

  const load = useCallback(() => {
    api.get<{ capabilities: Capability[]; mayPromote: boolean }>("/api/ui/suggestions?limit=1")
      .then((d) => {
        setCapabilities(d.capabilities.filter((c) => c.surface === surface));
        setMayRun(d.mayPromote);
      })
      .catch(() => setCapabilities([]));
  }, [surface]);
  useEffect(load, [load]);

  async function run() {
    setBusy(true);
    setNote(null);
    try {
      const r = await api.post<{
        runs: { capability: string; considered: number; proposed: number; detail: string }[];
        proposed: number; ranNothing: boolean;
      }>("/api/ui/agents/analyse", { surface });
      setNote(r.ranNothing
        ? "No capability is switched on for this dashboard yet — turn one on in Configuration."
        : r.proposed === 0
          ? `Nothing new to suggest. ${r.runs.map((x) => x.detail).join(" · ")}`
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

  if (!capabilities || capabilities.length === 0 || !mayRun) return null;
  const enabled = capabilities.filter((c) => c.enabled);
  const sendsRecords = enabled.some((c) => c.dataCategory === "RECORD");

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
        <Button size="sm" variant="secondary" disabled={busy || enabled.length === 0}
                title={enabled.length === 0
                  ? "Nothing is switched on for this dashboard"
                  : `Will run: ${enabled.map((c) => `${c.code} (${c.dataCategory})`).join(", ")}`}
                onClick={() => void run()}>
          {busy ? <Loader2 className="size-3.5 animate-spin" /> : <Sparkles className="size-3.5" />}
          {busy ? "Analysing…" : "Analyse with AI"}
          {enabled.length > 0 && (
            <span className="ml-1 tabular text-muted-foreground">{enabled.length}</span>
          )}
        </Button>
      </div>
      {note && <span className="max-w-96 text-right text-[11px] text-muted-foreground">{note}</span>}
      {enabled.length === 0 && (
        <span className="text-[11px] text-tone-unknown">
          {capabilities.length} capabilit{capabilities.length === 1 ? "y" : "ies"} available, none
          switched on
        </span>
      )}
    </div>
  );
}
