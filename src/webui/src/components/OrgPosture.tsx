import { useMemo } from "react";
import { Link } from "react-router-dom";
import { Minus, TrendingDown, TrendingUp } from "lucide-react";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Pager, usePaging } from "@/components/Paging";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export interface Posture {
  nodeId: string; name: string;
  applications: number; neverAssessed: number;
  openNow: number; openBefore: number;
  serious: number; exposedSerious: number;
  lastAssessedAt: string | null;
  measured: boolean;
  /** Open findings past their remediation commitment (service level breached, unresolved). */
  breached: number;
  /** Applications the review interval says are owed a review, and those among them with no planned window. */
  reviewsDue: number;
  reviewsUnplanned: number;
}

/**
 * Each organization's posture, worst first.
 *
 * **This is the page's centre of gravity, and the ordering is the argument.** A group total answers
 * a question nobody owns; the person opening this runs one of these companies, or all of them, and
 * either way the first question is "how is mine, and is it worse than the others". Sorting by the
 * composed exposure rather than alphabetically means the row that needs a conversation is the row
 * at the top.
 *
 * The rows are the caller's scope roots. An executive with a tenant-wide grant sees every operating
 * company side by side; a manager scoped to one sees one row. Neither has to set a filter.
 *
 * **No score.** DOC-28 owns the risk model and is unimplemented — inventing a weighting here would
 * be a second, unversioned model that disagrees with it on the day it ships. What ranks a row is a
 * count of findings that are simultaneously serious, internet-facing and on a tier-one asset: every
 * term recorded, nothing weighted.
 */
export function OrgPosture({ rows }: { rows: Posture[] }) {
  // Sorted and paged BEFORE the empty check, so the hooks below run in the same order on every
  // render. React identifies a hook by its call position, not by its name.
  const ranked = useMemo(() => [...rows].sort((a, b) =>
    b.exposedSerious - a.exposedSerious || b.serious - a.serious || b.openNow - a.openNow), [rows]);
  const paging = usePaging(ranked);
  if (rows.length === 0) {
    return null;
  }
  const worst = ranked[0];

  return (
    <div className="overflow-hidden rounded-lg border bg-card">
      <div className="flex flex-wrap items-baseline justify-between gap-2 border-b px-5 py-4">
        <div>
          <div className="text-sm font-semibold tracking-tight">Where each organization stands</div>
          <div className="text-xs text-muted-foreground">
            Most exposed first. Every column is a recorded value rolled up over everything the
            organization owns — nothing here is weighted or scored.
          </div>
        </div>
        {worst && worst.exposedSerious > 0 && (
          <Badge tone="critical">{worst.name} needs attention first</Badge>
        )}
      </div>
      <div className="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Organization</TableHead>
              <TableHead className="text-right">Applications</TableHead>
              <TableHead className="text-right">Never assessed</TableHead>
              <TableHead className="text-right">Open findings</TableHead>
              <TableHead className="text-right">Serious</TableHead>
              <TableHead className="text-right">Serious &amp; exposed</TableHead>
              <TableHead className="text-right">Past commitment</TableHead>
              <TableHead className="text-right">Reviews owed</TableHead>
              <TableHead className="text-right" title="Open findings per 10 applications — the normalization PRD-DSH-035 asks for, so a large unit is not read as a bad one">
                Open / 10 apps
              </TableHead>
              <TableHead>Last assessed</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {paging.rows.map((o) => {
              const delta = o.openNow - o.openBefore;
              return (
                <TableRow key={o.nodeId}>
                  <TableCell className="text-sm font-medium">{o.name}</TableCell>
                  <TableCell className="tabular text-right text-xs">{o.applications}</TableCell>
                  <TableCell className="text-right">
                    {/* The column that starts conversations. An application nobody has looked at
                        contributes no findings, so it makes every other number on the row quieter
                        than the organization actually is. */}
                    {o.neverAssessed > 0
                      ? <Badge tone="critical">{o.neverAssessed}</Badge>
                      : <span className="tabular text-xs text-muted-foreground">0</span>}
                  </TableCell>
                  <TableCell className="text-right">
                    <span className="tabular text-xs">{o.openNow}</span>
                    {/* Direction beside the count. A number alone cannot say whether the team is
                        gaining or losing, and that is the whole question a manager is asked. */}
                    <span className={cn("ml-1.5 inline-flex items-center gap-0.5 text-[11px]",
                                        delta > 0 ? "text-sev-critical"
                                        : delta < 0 ? "text-tone-ok" : "text-muted-foreground")}
                          title={`${o.openBefore} open ninety days ago`}>
                      {delta > 0 ? <TrendingUp className="size-3" />
                        : delta < 0 ? <TrendingDown className="size-3" />
                        : <Minus className="size-3" />}
                      {delta !== 0 && Math.abs(delta)}
                    </span>
                  </TableCell>
                  <TableCell className="tabular text-right text-xs">{o.serious}</TableCell>
                  <TableCell className="text-right">
                    {o.exposedSerious > 0
                      ? <Badge tone="critical">{o.exposedSerious}</Badge>
                      : <span className="tabular text-xs text-muted-foreground">0</span>}
                  </TableCell>
                  <TableCell className="text-right">
                    {o.breached > 0
                      ? <Badge tone="critical">{o.breached}</Badge>
                      : <span className="tabular text-xs text-muted-foreground">0</span>}
                  </TableCell>
                  <TableCell className="text-right">
                    {/* Owed, and how many of those nobody has planned. The second number is the one
                        that becomes next quarter's overdue. */}
                    {o.reviewsDue > 0 ? (
                      <span className="tabular text-xs">
                        {o.reviewsDue}
                        {o.reviewsUnplanned > 0 && (
                          <span className="ml-1 text-sev-high" title="of which no window is planned">
                            ({o.reviewsUnplanned} unplanned)
                          </span>
                        )}
                      </span>
                    ) : <span className="tabular text-xs text-muted-foreground">0</span>}
                  </TableCell>
                  <TableCell className="tabular text-right text-xs">
                    {/* Null, not zero, when there is nothing to divide by (PRD-ASM-023). */}
                    {o.applications === 0
                      ? <span className="italic text-tone-unknown">—</span>
                      : (10 * o.openNow / o.applications).toFixed(1)}
                  </TableCell>
                  <TableCell className="font-mono text-[11px]">
                    {/* Never is a fact, and a worse one than a stale date. */}
                    {o.lastAssessedAt
                      ? o.lastAssessedAt
                      : <span className="italic text-tone-unknown">never</span>}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>
      <Pager paging={paging} unit="organizations" />
      <div className="border-t px-5 py-3 text-[11px] text-muted-foreground">
        <strong>Serious &amp; exposed</strong> counts findings that are at the top two severities{" "}
        <em>and</em> on an internet-facing <em>and</em> business-critical asset — three recorded
        facts, not a score. <strong>Past commitment</strong> is measured against the service level
        the organization set for itself. <strong>Open / 10 apps</strong> normalizes for portfolio
        size only, not for criticality mix; read it beside <em>Never assessed</em>, because an
        unmeasured unit looks clean.{" "}
        <Link to="/applications" className="text-primary hover:underline">Open the inventory</Link>
      </div>
    </div>
  );
}
