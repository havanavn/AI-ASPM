import { useCallback, useEffect, useState } from "react";
import { AlertTriangle, CheckCircle2, CircleSlash, HelpCircle, MoonStar, Radio } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

interface Row {
  id: string; label: string; keyId: string; actsAs: string | null; scope: string | null;
  permissions: string[]; expiresAt: string | null; expired: boolean; revokedAt: string | null;
  lastUsedAt: string | null; lastSuccessAt: string | null; lastFailureAt: string | null;
  lastFailureReason: string | null;
  successCount: number; failureCount: number; consecutiveFailures: number;
  outcomeRecorded: boolean; daysSinceSuccess: number | null;
  verdict: string; advice: string;
}
interface Summary {
  liveSubmitters: number; broken: number; silent: number; neverRecorded: number;
  artifactsWithoutSbom: number; artifactsStale: number;
}

/**
 * The five verdicts, each with its own tone AND its own icon.
 *
 * Never colour alone — a monochrome print, a forced-colours mode or a red/green reader has to be able to
 * tell "failing" from "healthy", and on this panel that distinction is the entire content.
 *
 * NEVER_USED is `unknown`, not `ok`. A key nothing has been recorded on has not been measured, and
 * painting it green would be the substitution this whole panel exists to prevent.
 */
interface VerdictStyle {
  tone: "ok" | "warn" | "high" | "neutral" | "unknown"; icon: typeof Radio; text: string;
}

/**
 * The fallback for a verdict this interface does not recognise — a server that has grown a sixth one.
 *
 * A standalone constant rather than `VERDICT.NEVER_USED`, because indexing a Record yields
 * `T | undefined` and the compiler was right to say so. It also has to be the CAUTIOUS answer: an
 * unrecognised verdict rendering as "submitting" would report health nobody measured, which is the
 * substitution this whole panel exists to prevent.
 */
const UNRECOGNISED: VerdictStyle = { tone: "unknown", icon: HelpCircle, text: "not recognised" };

const VERDICT: Record<string, VerdictStyle> = {
  HEALTHY: { tone: "ok", icon: CheckCircle2, text: "submitting" },
  FAILING: { tone: "high", icon: AlertTriangle, text: "failing" },
  SILENT: { tone: "warn", icon: MoonStar, text: "gone quiet" },
  NEVER_USED: { tone: "unknown", icon: HelpCircle, text: "never used" },
  EXPIRED: { tone: "warn", icon: CircleSlash, text: "expired" },
  REVOKED: { tone: "neutral", icon: CircleSlash, text: "revoked" },
};

/**
 * Submission health per integration credential — `PRD-SBM-024`.
 *
 * <h2>Why this panel is not optional</h2>
 *
 * ADR-023 made the SBOM push API the only automated ingestion path in v1 and wrote down what that costs:
 * the endpoint is a single point of failure for the entire SCA capability, "which is why submission
 * health must be visible per credential". An integration failing for weeks is the mechanism by which
 * coverage gaps form — and before this, the only per-credential fact recorded was `last_used_at`, which
 * cannot distinguish a pipeline that submitted from one whose documents were refused forty times, and
 * says nothing at all about a pipeline whose secret went stale.
 *
 * <h2>Silence is a state, not a clean bill</h2>
 *
 * A credential with nothing recorded reads "never used", not "0 failures". Those lead to opposite
 * actions: one means check the build configuration, the other means nothing is wrong. Product principle
 * 1 is the whole design of this table.
 *
 * <h2>Revoked keys are listed, not filtered</h2>
 *
 * "It stopped submitting because somebody revoked its key in August" is the answer to the question this
 * page gets opened with. Filtering revoked keys out makes that pipeline vanish along with its
 * explanation, which is how the same question gets asked again next month.
 *
 * <h2>Each row carries what it costs</h2>
 *
 * The heading pairs the broken integrations with the artifacts that now have no current inventory. A
 * failure count on its own is a number; the same count beside "42 artifacts have never been inventoried"
 * is a decision.
 */
export function SubmissionHealth() {
  const [data, setData] = useState<{ rows: Row[]; summary: Summary } | null>(null);

  const load = useCallback(() => {
    api.get<{ rows: Row[]; summary: Summary }>("/api/ui/sbom-submission-health")
      .then(setData)
      .catch(() => setData(null));
  }, []);
  useEffect(load, [load]);

  if (!data) return null;
  const { rows, summary } = data;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex flex-wrap items-center gap-2">
          <Radio className="size-4 text-muted-foreground" />
          CI submission health
          {summary.broken > 0 && <Badge tone="high">{summary.broken} failing</Badge>}
          {summary.silent > 0 && <Badge tone="warn">{summary.silent} gone quiet</Badge>}
          {summary.neverRecorded > 0 && (
            <Badge tone="unknown">{summary.neverRecorded} never used</Badge>
          )}
          {summary.broken === 0 && summary.silent === 0 && summary.neverRecorded === 0
            && summary.liveSubmitters > 0 && <Badge tone="ok">all submitting</Badge>}
        </CardTitle>
        <CardDescription>
          The SBOM push API is the only automated way dependency data arrives, so a pipeline that has
          quietly stopped is how a coverage gap forms.{" "}
          {summary.liveSubmitters === 0
            ? "No live key may submit at all — every credential with the submit permission is revoked "
              + "or expired, so nothing can arrive until one is issued."
            : `${summary.liveSubmitters} live key${summary.liveSubmitters === 1 ? "" : "s"} may submit.`}
          {" "}
          {/* The consequence, beside the cause. Neither half moves anybody on its own. */}
          {(summary.artifactsWithoutSbom > 0 || summary.artifactsStale > 0) && (
            <>
              Right now {summary.artifactsWithoutSbom} artifact
              {summary.artifactsWithoutSbom === 1 ? " has" : "s have"} never been inventoried and{" "}
              {summary.artifactsStale} {summary.artifactsStale === 1 ? "has" : "have"} an inventory that
              has aged out.
            </>
          )}
        </CardDescription>
      </CardHeader>

      <CardContent>
        {rows.length === 0 ? (
          <p className="text-xs italic text-tone-unknown">
            No credential holds the submit permission, so no pipeline can push a bill of materials.
            Issue one on the credentials panel above.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Integration</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead>Last accepted</TableHead>
                  <TableHead className="text-right">Accepted</TableHead>
                  <TableHead className="text-right">Refused</TableHead>
                  <TableHead>What to do</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r) => {
                  const v = VERDICT[r.verdict] ?? UNRECOGNISED;
                  const Icon = v.icon;
                  return (
                    <TableRow key={r.id}
                              className={r.revokedAt ? "opacity-60" : undefined}>
                      <TableCell className="text-xs">
                        <div className="font-medium">{r.label}</div>
                        <div className="font-mono text-[10px] text-muted-foreground">{r.keyId}</div>
                        {r.scope && (
                          <div className="text-[10px] text-muted-foreground">{r.scope}</div>
                        )}
                      </TableCell>
                      <TableCell>
                        <Badge tone={v.tone} className="gap-1">
                          <Icon className="size-3" /> {v.text}
                        </Badge>
                        {r.consecutiveFailures > 0 && (
                          <div className="mt-0.5 text-[10px] text-sev-high">
                            {r.consecutiveFailures} in a row
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="tabular text-xs">
                        {r.lastSuccessAt
                          ? (
                            <>
                              {r.lastSuccessAt.slice(0, 10)}
                              {r.daysSinceSuccess !== null && (
                                <div className="text-[10px] text-muted-foreground">
                                  {r.daysSinceSuccess}d ago
                                </div>
                              )}
                            </>
                          )
                          /* Not a dash and not a zero. Nothing has been recorded, and the table says
                             which of those two it is. */
                          : <span className="italic text-tone-unknown">nothing recorded</span>}
                      </TableCell>
                      <TableCell className="tabular text-right text-xs">
                        {r.outcomeRecorded ? r.successCount
                          : <span className="text-tone-unknown">—</span>}
                      </TableCell>
                      <TableCell className="tabular text-right text-xs">
                        {r.outcomeRecorded
                          ? <span className={r.failureCount > 0 ? "text-sev-high" : undefined}>
                              {r.failureCount}
                            </span>
                          : <span className="text-tone-unknown">—</span>}
                      </TableCell>
                      <TableCell className="max-w-96 text-[11px] text-muted-foreground">
                        {r.advice}
                        {/* The reason, verbatim and never truncated. It is the one field that says what
                            is actually wrong, and a reason behind a tooltip is a reason nobody reads. */}
                        {r.lastFailureReason && (
                          <div className="mt-1 rounded border border-border bg-muted/40 p-1.5">
                            <span className="font-medium">Last refusal</span>
                            {r.lastFailureAt && (
                              <span className="tabular"> · {r.lastFailureAt.slice(0, 16)}</span>
                            )}
                            <div>{r.lastFailureReason}</div>
                          </div>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        )}

        {/* Stated once, plainly. The counters began at this deployment's V059 and were deliberately not
            backfilled: every credential here acts as the same principal, so attributing the existing
            snapshots per principal would have credited ten integrations with one pipeline's work. A
            dashboard that inferred history it could not attribute would be wrong in exactly the way this
            one exists to prevent. */}
        <p className="mt-3 text-[11px] text-muted-foreground">
          Counts start from when submission health was first recorded; earlier submissions could not be
          attributed to a specific key, so they are not counted rather than guessed at.
        </p>
      </CardContent>
    </Card>
  );
}
