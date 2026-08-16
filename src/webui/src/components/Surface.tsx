import { Link } from "react-router-dom";
import { Pager, usePaging } from "@/components/Paging";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { BarList, StackedColumns, type Slice } from "@/components/Charts";

export interface SurfacePayload {
  remediation: { closed: number; meanDays: number | null; medianDays: number | null; p90Days: number | null };
  remediationTrend: { label: string; closed: number; medianDays: number | null }[];
  aging: { label: string; findings: number; serious: number }[];
  categories: { code: string; open: number; closed: number; serious: number }[];
  assetClasses: { code: string; label: string; total: number; internetFacing: number; unclassified: number }[];
  growth: { label: string; added: number; cumulative: number }[];
  internetFacing: {
    id: string; name: string; typeCode: string; criticality: string;
    open: number; serious: number; lastAssessedAt: string | null;
  }[];
}

/**
 * Time to remediate.
 *
 * Three figures, not one. They disagree, and the disagreement is the information: a team that closes
 * forty trivial findings in a day and leaves four hard ones open for a year has a good median and a
 * bad mean, and quoting either alone lets one of those two stories be told without the other.
 */
export function Remediation({ data }: { data: SurfacePayload }) {
  const { remediation: r, remediationTrend: trend } = data;
  const measured = r.closed > 0 && r.medianDays !== null;
  const recent = trend.filter((p) => p.medianDays !== null);
  const first = recent.at(0)?.medianDays;
  const last = recent.at(-1)?.medianDays;
  // Only where both ends of the period actually closed something. Comparing against a month with no
  // closures would report a direction the data does not contain.
  const direction = recent.length >= 2 && first != null && last != null ? last - first : null;

  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle>Time to remediate</CardTitle></CardHeader>
      <CardContent className="flex flex-col gap-4">
        {!measured ? (
          <p className="text-sm text-muted-foreground">
            Nothing has been closed in the last year in your scope, so there is no elapsed time to
            measure. That is not a fast team and not a slow one — it is no data.
          </p>
        ) : (
          <>
            <div className="flex flex-wrap gap-6">
              <Figure value={r.medianDays!} unit="days" label="Median"
                      note="the typical finding" />
              <Figure value={r.meanDays!} unit="days" label="Mean"
                      note="pulled by the long tail" />
              <Figure value={r.p90Days!} unit="days" label="90th percentile"
                      note="what it feels like when it is yours" />
              <Figure value={r.closed} unit="" label="Closed"
                      note="the population all three are over" />
            </div>

            {trend.length > 0 && (
              <div className="flex flex-col gap-2">
                <div className="flex items-baseline justify-between">
                  <span className="text-[11px] font-medium">Median days to close, by month</span>
                  {direction !== null && (
                    <span className={`text-[11px] ${direction > 1 ? "text-sev-critical"
                      : direction < -1 ? "text-tone-ok" : "text-muted-foreground"}`}>
                      {direction > 1 ? `slower by ${direction.toFixed(1)} days over the period`
                        : direction < -1 ? `faster by ${Math.abs(direction).toFixed(1)} days over the period`
                        : "roughly flat"}
                    </span>
                  )}
                </div>
                <MedianTrend points={trend} />
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

function Figure({ value, unit, label, note }: {
  value: number; unit: string; label: string; note: string;
}) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="tabular text-2xl font-semibold leading-none tracking-tight">
        {value}{unit && <span className="pl-1 text-xs font-normal text-muted-foreground">{unit}</span>}
      </span>
      <span className="text-[11px] font-medium">{label}</span>
      <span className="text-[10px] text-muted-foreground">{note}</span>
    </div>
  );
}

/**
 * Median close time per month as columns.
 *
 * A month with no closures renders as an empty slot with the word, not as a zero-height bar at the
 * bottom of the axis. Zero days to remediate and nothing to remediate are different claims and the
 * chart has to keep them apart.
 */
function MedianTrend({ points }: { points: { label: string; closed: number; medianDays: number | null }[] }) {
  const peak = Math.max(1, ...points.map((p) => p.medianDays ?? 0));
  return (
    <div className="flex items-end gap-1.5" style={{ blockSize: "84px" }}>
      {points.map((p) => (
        <div key={p.label} className="flex flex-1 flex-col items-center justify-end gap-1"
             title={p.medianDays === null ? `${p.label}: nothing closed`
               : `${p.label}: ${p.medianDays} days median over ${p.closed} closed`}>
          {p.medianDays === null ? (
            <div className="w-full rounded-sm border border-dashed border-tone-unknown/60"
                 style={{ blockSize: "12px" }} />
          ) : (
            <div className="w-full rounded-sm bg-tone-info"
                 style={{ blockSize: `${Math.max(4, Math.round((60 * p.medianDays) / peak))}px` }} />
          )}
          <span className="text-[9px] text-muted-foreground">{p.label.slice(5)}</span>
        </div>
      ))}
    </div>
  );
}

/**
 * How old the open backlog is.
 *
 * Buckets rather than an average age, because the shape decides the response: a backlog with a long
 * tail and one with a uniform spread have the same mean and need different things done about them.
 */
export function Aging({ rows }: { rows: { label: string; findings: number; serious: number }[] }) {
  const total = rows.reduce((s, r) => s + r.findings, 0);
  const old = rows.filter((r) => r.label.startsWith("91") || r.label.startsWith("over"))
    .reduce((s, r) => s + r.findings, 0);
  const oldSerious = rows.filter((r) => r.label.startsWith("91") || r.label.startsWith("over"))
    .reduce((s, r) => s + r.serious, 0);

  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle>How old the open work is</CardTitle></CardHeader>
      <CardContent className="flex flex-col gap-3">
        {total === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing open in scope.</p>
        ) : (
          <>
            <StackedColumns
              points={rows.map((r) => ({
                label: r.label.replace(" days", "").replace("over ", "over "),
                parts: [
                  { key: "serious", value: r.serious, className: "bg-sev-critical" },
                  { key: "rest", value: r.findings - r.serious, className: "bg-tone-info" },
                ],
              }))}
              empty="Nothing open in scope."
              legend={[
                { key: "serious", label: "top two severities", className: "bg-sev-critical" },
                { key: "rest", label: "everything else", className: "bg-tone-info" },
              ]} />
            <p className="text-[11px] text-muted-foreground">
              <strong>{old} of {total}</strong> open findings are older than ninety days, {oldSerious} of
              them at the top two severities. Age is measured from first detection, so a finding that
              was re-reported by a later scan does not get a fresh clock — the age is how long the
              weakness has existed, not how long this record has.
            </p>
          </>
        )}
      </CardContent>
    </Card>
  );
}

const CATEGORY_LABEL: Record<string, string> = {
  CODE: "Application code",
  DEPENDENCY: "Third-party dependency",
  CONFIGURATION: "Configuration",
  INFRASTRUCTURE: "Infrastructure",
  RUNTIME: "Runtime behaviour",
  SECRET: "Exposed secret",
  MANUAL: "Manual assessment",
};

/**
 * What kind of control keeps failing.
 *
 * Explicitly not "top vulnerability types by CWE" — nothing records a CWE. What is recorded is the
 * seven-way classification below, which answers where the weakness came from rather than what it is
 * called, and the caption says so instead of letting the reader assume a taxonomy that is not there.
 */
export function Categories({ rows }: { rows: SurfacePayload["categories"] }) {
  const slices: Slice[] = rows.map((r) => ({
    key: r.code,
    label: `${CATEGORY_LABEL[r.code] ?? r.code}${r.serious > 0 ? ` — ${r.serious} serious` : ""}`,
    value: r.open,
    population: r.open + r.closed,
  }));
  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle>Where the weaknesses come from</CardTitle></CardHeader>
      <CardContent className="flex flex-col gap-3">
        <BarList slices={slices} empty="No finding has been recorded in scope." tone="warn" />
        <p className="text-[11px] text-muted-foreground">
          Open findings, with the all-time total beside each. This is the classification the platform
          records — where a weakness originated. It is <strong>not</strong> a CWE or OWASP category:
          none is stored, so a chart claiming to be one would be inventing its own axis.
        </p>
      </CardContent>
    </Card>
  );
}

/** The estate by asset type, with what is reachable from the internet and what nobody classified. */
export function Estate({ rows }: { rows: SurfacePayload["assetClasses"] }) {
  const total = rows.reduce((s, r) => s + r.total, 0);
  const facing = rows.reduce((s, r) => s + r.internetFacing, 0);
  const unknown = rows.reduce((s, r) => s + r.unclassified, 0);
  return (
    <Card className="overflow-hidden">
      <CardHeader className="pb-2"><CardTitle>What you have to defend</CardTitle></CardHeader>
      {rows.length === 0 ? (
        <CardContent className="text-sm text-muted-foreground">
          No asset is recorded under any organization you reach.
        </CardContent>
      ) : (
        <>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Asset type</TableHead>
                <TableHead className="text-right">Total</TableHead>
                <TableHead className="text-right">Internet-facing</TableHead>
                <TableHead className="text-right">Exposure unclassified</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.code}>
                  <TableCell className="text-xs font-medium">{r.label}</TableCell>
                  <TableCell className="tabular text-right text-xs">{r.total}</TableCell>
                  <TableCell className="tabular text-right text-xs">
                    {r.internetFacing > 0 ? <strong>{r.internetFacing}</strong> : "—"}
                  </TableCell>
                  <TableCell className="tabular text-right text-xs">
                    {r.unclassified > 0
                      ? <span className="text-tone-unknown">{r.unclassified}</span> : "—"}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <CardContent className="border-t text-[11px] text-muted-foreground">
            {total} assets, {facing} of them internet-facing{unknown > 0
              ? `, and ${unknown} whose exposure nobody has declared` : ""}.
            {unknown > 0 && " An asset with no declared exposure is not an internal asset — it is one nobody has looked at, so it is counted separately rather than folded into the safer number."}
            {" "}Cloud provider, region and container platform are not broken out here because no
            asset records them; that breakdown needs a discovery connector, not a different query.
          </CardContent>
        </>
      )}
    </Card>
  );
}

/** Assets first seen per month, with the running total. */
export function Growth({ rows }: { rows: SurfacePayload["growth"] }) {
  const peak = Math.max(1, ...rows.map((r) => r.cumulative));
  const added = rows.reduce((s, r) => s + r.added, 0);
  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle>Attack surface over time</CardTitle></CardHeader>
      <CardContent className="flex flex-col gap-3">
        {rows.length === 0 ? (
          <p className="text-sm text-muted-foreground">No asset is recorded in scope.</p>
        ) : (
          <>
            {/* The month's new assets sit at the TOP of the running total, which is where they were
                actually added. Drawing them at the bottom would put the newest surface underneath
                everything that predates it and make the column read as a stack of unrelated parts. */}
            <div className="flex items-end gap-1.5" style={{ blockSize: "104px" }}>
              {rows.map((r) => {
                const height = Math.round((80 * r.cumulative) / peak);
                const top = r.added > 0
                  ? Math.max(3, Math.round((80 * r.added) / peak)) : 0;
                return (
                  <div key={r.label} className="flex flex-1 flex-col items-center justify-end gap-1"
                       title={`${r.label}: ${r.added} first seen, ${r.cumulative} known in total`}>
                    <span className="tabular text-[9px] text-muted-foreground">
                      {r.added > 0 ? `+${r.added}` : ""}
                    </span>
                    <div className="flex w-full flex-col justify-end overflow-hidden rounded-sm"
                         style={{ blockSize: `${Math.max(2, height)}px` }}>
                      {top > 0 && <div className="w-full shrink-0 bg-tone-info"
                                       style={{ blockSize: `${top}px` }} />}
                      <div className="w-full flex-1 bg-tone-info/25" />
                    </div>
                    <span className="text-[9px] text-muted-foreground">{r.label.slice(5)}</span>
                  </div>
                );
              })}
            </div>
            <div className="flex gap-4 text-[10px] text-muted-foreground">
              <span className="flex items-center gap-1">
                <span className="inline-block size-2 rounded-sm bg-tone-info" /> first seen that month
              </span>
              <span className="flex items-center gap-1">
                <span className="inline-block size-2 rounded-sm bg-tone-info/25" /> known before it
              </span>
            </div>
            <p className="text-[11px] text-muted-foreground">
              {added} assets became known over the period. This is <strong>discovery</strong>, not
              creation: onboarding an existing inventory looks identical to growth on the first months
              of a deployment, so a spike here is a question rather than a finding.
            </p>
          </>
        )}
      </CardContent>
    </Card>
  );
}

/** Internet-facing assets, ranked by what is open on them — and what has never been looked at. */
export function InternetFacing({ rows }: { rows: SurfacePayload["internetFacing"] }) {
  const never = rows.filter((r) => r.lastAssessedAt === null).length;
  const paging = usePaging(rows);
  return (
    <Card className="overflow-hidden">
      <CardHeader className="pb-2"><CardTitle>Reachable from the internet</CardTitle></CardHeader>
      {rows.length === 0 ? (
        <CardContent className="text-sm text-muted-foreground">
          No asset in your scope is declared or observed as internet-facing. If that is surprising,
          it is more likely an exposure classification gap than an estate with no public surface.
        </CardContent>
      ) : (
        <>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Asset</TableHead>
                <TableHead>Criticality</TableHead>
                <TableHead className="text-right">Open</TableHead>
                <TableHead className="text-right">Serious</TableHead>
                <TableHead>Last assessed</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {paging.rows.map((r) => (
                <TableRow key={r.id}>
                  <TableCell className="max-w-64 truncate text-xs">
                    <Link to={`/applications/${r.id}`}
                          className="font-medium text-primary hover:underline">{r.name}</Link>
                    <span className="pl-1.5 text-[10px] text-muted-foreground">
                      {r.typeCode.toLowerCase()}
                    </span>
                  </TableCell>
                  <TableCell className="text-[11px] text-muted-foreground">
                    {r.criticality === "UNCLASSIFIED" ? "not classified" : r.criticality.toLowerCase()}
                  </TableCell>
                  <TableCell className="tabular text-right text-xs">{r.open}</TableCell>
                  <TableCell className="tabular text-right text-xs">
                    {r.serious > 0 ? <strong className="text-sev-critical">{r.serious}</strong> : "—"}
                  </TableCell>
                  <TableCell>
                    {r.lastAssessedAt
                      ? <span className="font-mono text-[11px]">{r.lastAssessedAt}</span>
                      : <Badge tone="unknown">never</Badge>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Pager paging={paging} unit="assets" />
          {never > 0 && (
            <CardContent className="border-t text-[11px] text-muted-foreground">
              {never} of these have never been assessed. They show zero open findings for that reason
              and not because they are clean — an unassessed asset reachable from the internet is the
              row on this page with the least evidence behind it, which is why it is listed rather
              than ranked away at the bottom of a count.
            </CardContent>
          )}
        </>
      )}
    </Card>
  );
}
