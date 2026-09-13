import { useState } from "react";
import { Link } from "react-router-dom";
import { Check, FileText, Sparkles, X } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { AnalyseButton } from "@/components/AnalyseButton";

/**
 * The executive brief: what the AI wrote about each organization the reader is accountable for.
 *
 * <h2>Where it sits and why</h2>
 *
 * Beside the headline figure, not at the foot of the page in the ledger. A brief that has to be found
 * is not read; a brief beside the number it explains is. It stays a suggestion until a person accepts
 * it (ADR-005) — the buttons here are that decision — and it is labelled generated in every state
 * (PRD-AIC-036).
 *
 * <h2>What the reader is told about how it was made</h2>
 *
 * Which model wrote it, when, and the facts it was allowed to draw a number from. The model writes
 * the sentences; every number comes from a query (ADR-038). A brief is only produced for an
 * organization whose coverage caveat has been raised, so "read against the coverage note" is a
 * condition of its existence rather than a footnote.
 */
export interface Brief {
  id: string; subjectId: string; subjectLabel: string; headline: string; detail: string;
  grounding: string[]; modelIdentity: string; promptVersion: string; generatedAt: string; freshness: string;
}

export function ExecutiveBrief({ briefs, mayDecide, onChanged }: {
  briefs: Brief[]; mayDecide: boolean; onChanged: () => void;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  async function decide(id: string, promote: boolean) {
    setBusy(id); setNote(null);
    try {
      await api.post(`/api/ui/suggestions/${id}/decide`, promote ? { promote: true } : { promote: false, reason: "Not adopted from the overview" });
      onChanged();
    } catch (e) {
      setNote(e instanceof Error ? e.message : "That could not be recorded.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card className="h-full">
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <FileText className="size-4 text-primary" /> Executive brief
          <Badge tone="info">generated</Badge>
        </CardTitle>
        <CardDescription>
          One paragraph per organization, written by the configured model from figures the platform
          queried. Nothing here changes a record until a person accepts it.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {briefs.length === 0 && (
          <div className="rounded-md border border-dashed p-3 text-xs text-muted-foreground">
            <p className="mb-2">
              No brief is waiting. One is drafted per organization whose coverage caveat has been
              raised — the caveat first, so a fluent paragraph is never read over an unmeasured estate.
              Pressing <em>Analyse</em> runs the coverage caveat, the exception brief and the
              narrative for this page; the result appears here for a decision.
            </p>
            <AnalyseButton surface="/overview" onDone={onChanged} />
          </div>
        )}
        {briefs.map((b) => (
          <div key={b.id} className="flex flex-col gap-1.5 rounded-md border p-3 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <Link to={`/applications?node=${b.subjectId}`} className="font-medium hover:text-primary hover:underline">
                {b.subjectLabel}
              </Link>
              {b.freshness === "STALE" && <Badge tone="warn">record changed since</Badge>}
              <span className="ml-auto text-[11px] text-muted-foreground">
                <Sparkles className="mr-1 inline size-3" />{b.modelIdentity} · {b.generatedAt}
              </span>
            </div>
            <p className="whitespace-pre-wrap text-sm">{b.detail}</p>
            {b.grounding.length > 0 && (
              <div className="flex flex-wrap gap-1">
                {b.grounding.map((g) => (
                  <span key={g} className="rounded bg-muted px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground">{g}</span>
                ))}
              </div>
            )}
            {mayDecide && (
              <div className="flex gap-2 pt-1">
                <Button size="sm" variant="secondary" disabled={busy === b.id} onClick={() => decide(b.id, true)}>
                  <Check className="size-3" /> Accept as the organization's statement
                </Button>
                <Button size="sm" variant="ghost" disabled={busy === b.id} onClick={() => decide(b.id, false)}>
                  <X className="size-3" /> Dismiss
                </Button>
              </div>
            )}
          </div>
        ))}
        {note && <p className="text-xs text-destructive">{note}</p>}
        {briefs.length > 0 && (
          <div className="flex justify-end">
            <AnalyseButton surface="/overview" onDone={onChanged} />
          </div>
        )}
      </CardContent>
    </Card>
  );
}
