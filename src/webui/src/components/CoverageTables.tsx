import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import {
  Card, CardContent, CardDescription, CardHeader, CardTitle,
} from "@/components/ui/card";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { Label } from "@/components/ui/label";

export interface CoverageTier {
  code: string;
  label: string;
  ordinal: number;
}

export interface CoverageGroup {
  orgId: string | null;
  orgName: string;
  total: boolean;
  applications: number;
  plan: {
    planned: number; converted: number; done: number; missed: number; cancelled: number;
    /** Fraction, or null where nothing was planned. Never 0 for "no plan". */
    completion: number | null;
  };
  byTier: Record<string, number>;
  tierUnset: number;
  frequency: {
    none: number; once: number; twice: number; more: number; attested: number;
    /** Fraction reviewed at least once, or null where the group is empty. */
    covered: number | null;
  };
}

export interface CoverageReport {
  year: number;
  tiers: CoverageTier[];
  groups: CoverageGroup[];
}

/**
 * A count with its share of the row's population.
 *
 * <p>Both, always. A percentage alone hides that "50%" is one application out of two, which is the
 * commonest way a coverage table misleads the person reading it fastest.
 */
function Share({ value, of }: { value: number; of: number }) {
  if (of === 0) {
    return <span className="text-tone-unknown">—</span>;
  }
  return (
    <>
      <span className="tabular font-medium">{value}</span>
      <span className="tabular pl-1 text-[10px] text-muted-foreground">
        {Math.round((value / of) * 100)}%
      </span>
    </>
  );
}

/**
 * A rate that may not exist.
 *
 * <p>Null is rendered as a dash and never as 0%. Product principle 1 applied to a division: an
 * organization with nothing planned has no completion rate, and printing 0% would say it planned work
 * and delivered none of it — a different and much worse statement than "no plan".
 */
function Rate({ value }: { value: number | null }) {
  if (value === null) {
    return <span className="text-tone-unknown" title="Nothing planned, so there is no rate">—</span>;
  }
  const percent = Math.round(value * 100);
  return (
    <span className={cn("tabular font-medium",
      percent >= 90 ? "text-tone-ok" : percent >= 50 ? "text-tone-warn" : "text-sev-high")}>
      {percent}%
    </span>
  );
}

/** The header cell shape shared by all three tables. */
function Th({ children, className }: { children?: React.ReactNode; className?: string }) {
  return (
    <th className={cn("whitespace-nowrap px-2 py-1.5 text-left text-[10px] font-medium uppercase",
      "tracking-wide text-muted-foreground", className)}>
      {children}
    </th>
  );
}

function OrgCell({ group }: { group: CoverageGroup }) {
  return (
    <td className={cn("px-2 py-1.5", group.total && "font-semibold")}>
      {group.orgName}
      {group.total && (
        <span className="pl-1.5 text-[10px] font-normal text-muted-foreground">
          every organization you can see
        </span>
      )}
    </td>
  );
}

/**
 * The coverage tables: plan progress, the estate by criticality, and how often each application was
 * reviewed — as a total and per organization.
 *
 * <h2>Why the total is a row and not a headline</h2>
 *
 * A headline above a table invites the reader to trust it without checking, and a headline computed
 * separately from the table eventually disagrees with it. Here the total is the first row of the same
 * table, computed from the same per-application figures in one pass, so adding up the column reaches
 * it. It is styled to stand out and it is not styled to be believed on its own.
 *
 * <h2>Why the criticality columns are not Critical / High / Medium / Low</h2>
 *
 * They are whatever the tenant named its criticality tiers, in the order the tenant gave them
 * (ADR-027). The platform holds no opinion about a vocabulary it did not define, so it cannot merge
 * two of them into one column either — a "Medium + Low" column would require the code to decide which
 * of the tenant's tiers are the low ones. Every tier gets its own column and the reader can add.
 *
 * <h2>Grouped by the organization at the top of each branch</h2>
 *
 * One row per root organization, not per node. A row per node would produce a table as deep as the
 * tree, and every application would appear in several rows, so the columns would not add up to the
 * total — which is the one property this component exists to preserve. Narrow the organization filter
 * above to look inside one of them.
 */
export function CoverageTables({ report, year, onYear }: {
  report: CoverageReport;
  year: number;
  onYear: (year: number) => void;
}) {
  const years = [year - 2, year - 1, year, year + 1].filter((y, i, all) => all.indexOf(y) === i);
  const thisYear = new Date().getUTCFullYear();
  const offered = Array.from(new Set([...years, thisYear, thisYear - 1, thisYear + 1]))
    .sort((a, b) => b - a);

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <CardTitle className="text-sm">Coverage and plan progress — {report.year}</CardTitle>
          <CardDescription>
            A calendar year, because a planning cycle is budgeted as one. Every total is the sum of
            the organization rows beneath it, so the column adds up. Reviews count both the ones this
            platform ran and the ones somebody asserted; where the two differ it is said out loud.
          </CardDescription>
        </div>
        <div className="flex shrink-0 flex-col gap-1">
          <Label htmlFor="coverage-year">Year</Label>
          <Select value={String(year)} onValueChange={(v) => onYear(Number(v))}>
            <SelectTrigger id="coverage-year" className="h-8 w-24 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {offered.map((y) => <SelectItem key={y} value={String(y)}>{y}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      </CardHeader>

      <CardContent className="flex flex-col gap-6">
        {/* ---- 1. The plan, and how much of it happened ---- */}
        <section className="flex flex-col gap-1.5">
          <h3 className="text-xs font-semibold">Plan progress</h3>
          <p className="text-[11px] text-muted-foreground">
            <b>Done</b> is a planned window whose request reached a state the tenant classifies as
            completed. <b>Missed</b> is the finding: the window's period has passed and no request was
            ever raised from it.
          </p>
          <div className="overflow-x-auto rounded-md border">
            <table className="w-full text-xs">
              <thead className="bg-muted/50">
                <tr>
                  <Th>Organization</Th>
                  <Th className="text-right">Applications</Th>
                  <Th className="text-right">Windows planned</Th>
                  <Th className="text-right">Request raised</Th>
                  <Th className="text-right">Done</Th>
                  <Th className="text-right">% done</Th>
                  <Th className="text-right">Missed</Th>
                  <Th className="text-right">Cancelled</Th>
                </tr>
              </thead>
              <tbody>
                {report.groups.map((group) => (
                  <tr key={group.orgId ?? (group.total ? "__total" : "__none")}
                      className={cn("border-t", group.total && "bg-muted/30")}>
                    <OrgCell group={group} />
                    <td className="tabular px-2 py-1.5 text-right">{group.applications}</td>
                    <td className="tabular px-2 py-1.5 text-right">
                      {group.plan.planned || <span className="text-tone-unknown">none</span>}
                    </td>
                    <td className="px-2 py-1.5 text-right">
                      <Share value={group.plan.converted} of={group.plan.planned} />
                    </td>
                    <td className="px-2 py-1.5 text-right">
                      <Share value={group.plan.done} of={group.plan.planned} />
                    </td>
                    <td className="px-2 py-1.5 text-right">
                      <Rate value={group.plan.completion} />
                    </td>
                    <td className="tabular px-2 py-1.5 text-right">
                      {group.plan.missed > 0
                        ? <span className="font-medium text-sev-high">{group.plan.missed}</span>
                        : "—"}
                    </td>
                    <td className="tabular px-2 py-1.5 text-right">
                      {group.plan.cancelled || "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        {/* ---- 2. The estate by the tenant's own criticality tiers ---- */}
        <section className="flex flex-col gap-1.5">
          <h3 className="text-xs font-semibold">Applications by criticality</h3>
          <p className="text-[11px] text-muted-foreground">
            These are your own criticality tiers, in your own order — the platform names none of them
            in code, so it cannot merge two of them into one column either. <b>Not set</b> is its own
            column on purpose: an application nobody classified is not a low-criticality application,
            and no review interval can reach it.
          </p>
          <div className="overflow-x-auto rounded-md border">
            <table className="w-full text-xs">
              <thead className="bg-muted/50">
                <tr>
                  <Th>Organization</Th>
                  {report.tiers.map((tier) => (
                    <Th key={tier.code} className="text-right">
                      <span title={tier.label}>{tier.code}</span>
                    </Th>
                  ))}
                  <Th className="text-right">Not set</Th>
                  <Th className="text-right">Total</Th>
                </tr>
              </thead>
              <tbody>
                {report.groups.map((group) => (
                  <tr key={group.orgId ?? (group.total ? "__total" : "__none")}
                      className={cn("border-t", group.total && "bg-muted/30")}>
                    <OrgCell group={group} />
                    {report.tiers.map((tier) => (
                      <td key={tier.code} className="px-2 py-1.5 text-right">
                        <Share value={group.byTier[tier.code] ?? 0} of={group.applications} />
                      </td>
                    ))}
                    <td className="px-2 py-1.5 text-right">
                      {group.tierUnset > 0 ? (
                        <span className="text-tone-warn">
                          <Share value={group.tierUnset} of={group.applications} />
                        </span>
                      ) : "—"}
                    </td>
                    <td className="tabular px-2 py-1.5 text-right font-medium">
                      {group.applications}
                    </td>
                  </tr>
                ))}
                {report.tiers.length === 0 && (
                  <tr className="border-t">
                    <td colSpan={3} className="px-2 py-3 text-muted-foreground">
                      No criticality tier is configured, so nothing can be classified and no review
                      interval can apply to anything.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </section>

        {/* ---- 3. How often each application was actually reviewed ---- */}
        <section className="flex flex-col gap-1.5">
          <h3 className="text-xs font-semibold">
            Applications by number of reviews in {report.year}
          </h3>
          <p className="text-[11px] text-muted-foreground">
            One count per application, whole-application reviews only — a change review does not
            appear here. The <b>0</b> column is the one to read first: it is the population that went
            a year without a full review.
          </p>
          <div className="overflow-x-auto rounded-md border">
            <table className="w-full text-xs">
              <thead className="bg-muted/50">
                <tr>
                  <Th>Organization</Th>
                  <Th className="text-right">0 reviews</Th>
                  <Th className="text-right">1</Th>
                  <Th className="text-right">2</Th>
                  <Th className="text-right">More than 2</Th>
                  <Th className="text-right">Reviewed at least once</Th>
                  <Th className="text-right">Of which asserted</Th>
                </tr>
              </thead>
              <tbody>
                {report.groups.map((group) => (
                  <tr key={group.orgId ?? (group.total ? "__total" : "__none")}
                      className={cn("border-t", group.total && "bg-muted/30")}>
                    <OrgCell group={group} />
                    <td className="px-2 py-1.5 text-right">
                      {group.frequency.none > 0 ? (
                        <span className="text-sev-high">
                          <Share value={group.frequency.none} of={group.applications} />
                        </span>
                      ) : "—"}
                    </td>
                    <td className="px-2 py-1.5 text-right">
                      <Share value={group.frequency.once} of={group.applications} />
                    </td>
                    <td className="px-2 py-1.5 text-right">
                      <Share value={group.frequency.twice} of={group.applications} />
                    </td>
                    <td className="px-2 py-1.5 text-right">
                      <Share value={group.frequency.more} of={group.applications} />
                    </td>
                    <td className="px-2 py-1.5 text-right">
                      <Rate value={group.frequency.covered} />
                    </td>
                    <td className="px-2 py-1.5 text-right">
                      {/* PRD-ASM-020: a coverage figure derived from review counts has to be able to
                          say how much of it rests on somebody's word rather than on evidence the
                          platform holds. */}
                      {group.frequency.attested > 0
                        ? <Badge tone="unknown">{group.frequency.attested} asserted</Badge>
                        : <span className="text-muted-foreground">none</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </CardContent>
    </Card>
  );
}
