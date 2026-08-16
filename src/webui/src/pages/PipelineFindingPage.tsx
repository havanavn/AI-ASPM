import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, ExternalLink } from "lucide-react";
import { api } from "@/lib/api";
import { severityTone } from "@/components/tone";
import { FindingLifecycle } from "@/components/FindingLifecycle";
import { Prose } from "@/components/Prose";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

interface Detail {
  id: string; title: string; severity: string; reportedSeverity: string | null;
  findingClass: string; sourceTool: string | null; sourceToolVersion: string | null;
  sourceRuleIdentity: string | null; assessmentContext: string | null;
  orgPath: string | null; assetName: string | null; applicationName: string | null;
  projectName: string | null; repositoryName: string | null;
  firstDetectedAt: string | null; lastDetectedAt: string | null;
  ageDays: number | null; recurrence: number;
  descriptionHtml: string | null; proofOfConceptHtml: string | null;
  executiveRiskCategory: string | null; owaspTop10_2025: string | null; cweId: string | null;
  classificationSource: string | null;
  requestId: string | null;
}

/**
 * One pipeline finding, on its own page.
 *
 * <h2>Why this is not the assessment board's finding page</h2>
 *
 * An assessment finding exists because somebody raised a ticket, so opening it through the request it
 * belongs to is the truth about it. A pipeline finding has no such history: a scanner ran against a
 * commit and nothing was requested. Routing a delivery team through the assessment board to read their
 * own scanner output does two things at once — it makes the board look full of work that is not
 * assessment work, and it makes the team feel supervised in a place they are only trying to fix
 * something. Same record underneath; a different door.
 *
 * <h2>What is here, and what is deliberately not</h2>
 *
 * Here: what was found, where it lives, what tool and rule reported it, how long it has been open, and
 * the one control that matters — the status. Not here: request codes, assessors, triage vocabulary,
 * comment threads aimed at an assessment. None of that is hidden from anybody; it belongs on the board,
 * where it means something.
 *
 * <h2>The status control is the same component the board uses</h2>
 *
 * Deliberately, because the rules must not fork. It reports each move with the permission it needs, so
 * a delivery engineer sees "report as fixed" and an assessor additionally sees "verified — close it",
 * from the same endpoint. A second copy of a state machine is how two screens come to disagree about
 * what a finding is allowed to do.
 */
export function PipelineFindingPage() {
  const { id = "" } = useParams();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Detail>(`/api/ui/findings/${id}`)
      .then(setDetail)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [id]);
  useEffect(load, [load]);

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!detail) return <div className="text-sm text-muted-foreground">Loading…</div>;

  const where = [detail.projectName, detail.repositoryName, detail.assetName]
    .filter(Boolean).join(" › ");

  return (
    <div className="flex flex-col gap-5">
      <div>
        <Link to="/pipeline"
              className="mb-1 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-3" /> Back to what the pipelines found
        </Link>
        <h1 className="text-lg font-semibold tracking-tight">{detail.title}</h1>
        <div className="mt-1 flex flex-wrap items-center gap-2">
          <Badge tone={severityTone(detail.severity)}>{detail.severity.toLowerCase()}</Badge>
          {/* The tool, its version and the rule. PRD-VUL-004 requires all four be recorded; a developer
              deciding whether a report is theirs to act on asks "which rule fired" before anything
              else, and a finding that cannot say is one they have to go and look up. */}
          {detail.sourceTool && (
            <Badge tone="neutral">
              <span className="font-mono">{detail.sourceTool}</span>
              {detail.sourceToolVersion && (
                <span className="pl-1 text-muted-foreground">{detail.sourceToolVersion}</span>
              )}
            </Badge>
          )}
          {detail.recurrence > 0 && (
            <Badge tone="warn" title="Closed and verified, then came back">
              came back {detail.recurrence}×
            </Badge>
          )}
          {where && <span className="text-xs text-muted-foreground">{where}</span>}
        </div>
      </div>

      {/* The status control, above the prose. Somebody opening a finding is deciding what to do with
          it; the write-up is what they read to decide. */}
      <FindingLifecycle findingId={id} onMoved={load} />

      <div className="grid gap-5 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader className="pb-2"><CardTitle className="text-sm">What was found</CardTitle></CardHeader>
          <CardContent>
            {detail.descriptionHtml
              ? <Prose html={detail.descriptionHtml} />
              : <p className="text-xs italic text-tone-unknown">
                  The tool recorded no description. That is the report as it arrived, not a gap in this
                  page.
                </p>}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm">Facts</CardTitle></CardHeader>
          <CardContent className="flex flex-col gap-2 text-xs">
            <Fact label="Rule" value={detail.sourceRuleIdentity} mono />
            <Fact label="Kind" value={detail.findingClass} />
            <Fact label="Organization" value={detail.orgPath} />
            <Fact label="Project" value={detail.projectName} />
            <Fact label="Repository" value={detail.repositoryName} mono />
            <Fact label="Application" value={detail.applicationName} />
            <Fact label="First seen" value={detail.firstDetectedAt} />
            <Fact label="Last seen" value={detail.lastDetectedAt} />
            <Fact label="Open for" value={detail.ageDays === null ? null : `${detail.ageDays} days`} />
            {/* Severity as the source reported it, beside the effective one in the header. PRD-VUL-006
                keeps them separate: a tenant that overrode a tool's grade should be able to see both,
                and a page showing only the override hides that a decision was made. */}
            <Fact label="Tool said" value={detail.reportedSeverity} />
          </CardContent>
        </Card>
      </div>

      {detail.proofOfConceptHtml && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">How to reproduce it</CardTitle>
          </CardHeader>
          <CardContent><Prose html={detail.proofOfConceptHtml} /></CardContent>
        </Card>
      )}

      <Card>
        <CardHeader className="pb-2"><CardTitle className="text-sm">Classification</CardTitle></CardHeader>
        <CardContent className="flex flex-wrap items-center gap-2 text-xs">
          {detail.executiveRiskCategory || detail.owaspTop10_2025 || detail.cweId ? (
            <>
              {detail.executiveRiskCategory && <Badge tone="info">{detail.executiveRiskCategory}</Badge>}
              {detail.owaspTop10_2025 && <Badge tone="neutral">{detail.owaspTop10_2025}</Badge>}
              {detail.cweId && <Badge tone="neutral">{detail.cweId}</Badge>}
              {detail.classificationSource === "AI_ASSISTED" && (
                <span className="text-muted-foreground">
                  proposed by the classifier and submitted by a person
                </span>
              )}
            </>
          ) : (
            /* Named rather than left blank. 657 of 658 findings predate these fields, and a silent gap
               reads as "not applicable" when it means "nobody has decided yet" (PP-1). */
            <span className="italic text-tone-unknown">
              Not classified yet. Classification happens where a finding is recorded or edited.
            </span>
          )}
        </CardContent>
      </Card>

      {/* Only when there IS one. A pipeline finding usually has no assessment behind it, and a link to
          a request that does not exist is worse than no link. */}
      {detail.requestId && (
        <p className="text-xs text-muted-foreground">
          This finding was recorded during assessment work.{" "}
          <Link className="inline-flex items-center gap-1 hover:underline"
                to={`/board/${detail.requestId}/findings/${detail.id}`}>
            Open it on the assessment board <ExternalLink className="size-3" />
          </Link>
        </p>
      )}
    </div>
  );
}

/** One labelled fact, or the honest absence of it. */
function Fact({ label, value, mono }: { label: string; value: string | null; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <span className="shrink-0 text-muted-foreground">{label}</span>
      {value
        ? <span className={mono ? "truncate font-mono text-[11px]" : "truncate text-right"}>{value}</span>
        : <span className="italic text-tone-unknown">not recorded</span>}
    </div>
  );
}
