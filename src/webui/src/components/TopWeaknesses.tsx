import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

interface Row { key: string; label: string; total: number; months: number[] }
interface Payload { months: string[]; category: Row[]; owasp: Row[]; cwe: Row[] }

const DIMENSIONS = [
  { key: "category" as const, title: "By executive risk category",
    note: "How the business groups it. This is the table an executive reads." },
  { key: "owasp" as const, title: "By OWASP Top 10:2025",
    note: "The published taxonomy. “Not in the OWASP Top 10:2025” is a conclusion, not a gap." },
  { key: "cwe" as const, title: "By CWE",
    note: "The weakness class. CWE-UNKNOWN means nobody could determine it — not that it is unusual." },
];

/**
 * The most common weaknesses, by month.
 *
 * <h2>Columns per month, not one total</h2>
 *
 * A total says what the estate is made of; monthly columns say whether it is getting better, which is
 * the only version anybody can act on. "Injection is our biggest category" leads nowhere. "Injection
 * was our biggest category and has halved since the training" leads somewhere.
 *
 * <h2>Three months by default</h2>
 *
 * Short enough that the columns are about now, long enough that there is a shape to see. One month
 * would be a list with a redundant column.
 *
 * <h2>Unclassified is a row, never a silent omission</h2>
 *
 * 658 findings predate these fields. A top-ten table that dropped them would report a classified
 * estate and be wrong by more than it was right — so they appear as “(not yet classified)”, which is
 * both the honest label and the prompt to do something about it.
 */
export function TopWeaknesses({ asset, months = 3, title = "Most common weaknesses" }: {
  /** Limits to one application or project. Omitted on the overview, which asks about everything. */
  asset?: string;
  months?: number;
  title?: string;
}) {
  const [data, setData] = useState<Payload | null>(null);

  const load = useCallback(() => {
    const q = new URLSearchParams({ months: String(months) });
    if (asset) q.set("asset", asset);
    api.get<Payload>(`/api/ui/top-weaknesses?${q.toString()}`).then(setData).catch(() => setData(null));
  }, [asset, months]);
  useEffect(load, [load]);

  if (!data) return null;
  const empty = DIMENSIONS.every((d) => data[d.key].length === 0);

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle>{title}</CardTitle>
        <CardDescription>
          The last {data.months.length} months, a column each, so a category that is growing looks
          different from one that is merely large. Counted by when each finding was FIRST detected —
          so a month's column is what appeared then, not what a scanner happened to re-confirm.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        {empty && (
          <p className="text-xs italic text-tone-unknown">
            Nothing detected in this window.
          </p>
        )}
        {!empty && DIMENSIONS.map((d) => (
          <div key={d.key} className="flex flex-col gap-1">
            <span className="text-sm font-medium">{d.title}</span>
            <span className="text-[11px] text-muted-foreground">{d.note}</span>
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Weakness</TableHead>
                    {data.months.map((m) => (
                      // MM/YY, with the year. Bare month numbers wrap around and stop being readable
                      // the moment a window crosses December.
                      <TableHead key={m} className="text-right">
                        {m.slice(5)}/{m.slice(2, 4)}
                      </TableHead>
                    ))}
                    <TableHead className="text-right">Total</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data[d.key].map((row) => {
                    const first = row.months[0] ?? 0;
                    const last = row.months[row.months.length - 1] ?? 0;
                    return (
                      <TableRow key={row.key}>
                        <TableCell className="text-xs">
                          {row.label}
                          {/* Direction, only where both ends have something to compare. A trend
                              against a month with nothing in it is not a trend. */}
                          {first > 0 && last !== first && (
                            <span className={last > first ? "pl-1 text-sev-high"
                              : "pl-1 text-tone-ok"}>
                              {last > first ? "↑" : "↓"}
                            </span>
                          )}
                        </TableCell>
                        {row.months.map((n, i) => (
                          <TableCell key={i} className="tabular text-right text-xs">
                            {n === 0 ? <span className="text-muted-foreground">·</span> : n}
                          </TableCell>
                        ))}
                        <TableCell className="tabular text-right text-xs font-medium">
                          {row.total}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                  {data[d.key].length === 0 && (
                    <TableRow>
                      <TableCell colSpan={data.months.length + 2}
                                 className="text-center text-xs text-muted-foreground">
                        Nothing classified this way yet.
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </div>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
