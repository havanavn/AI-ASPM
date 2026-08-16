import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, Pencil } from "lucide-react";
import { api } from "@/lib/api";
import { Kpi } from "@/components/Kpi";
import { ApplicationPosture } from "@/components/ApplicationPosture";
import { CadenceCell, ScoreCell, type Cadence } from "@/components/inventory";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Pager, usePaging } from "@/components/Paging";
import { TopWeaknesses } from "@/components/TopWeaknesses";

interface Review {
  requestId: string; code: string; title: string | null; state: string;
  triggerLabel: string | null; startedAt: string | null; startedAtIsIntakeDate: boolean;
  closedAt: string | null; abandoned: boolean; open: boolean;
}
interface Component {
  id: string; name: string; typeCode: string; depth: number; path: string[];
  exposure: string | null; lifecycleState: string; attributes: Record<string, string>;
  findingOpen: number; findingTotal: number; criticalOpen: number; highOpen: number;
  mediumOpen: number; scaOpen: number; acceptedTotal: number;
}
interface Detail {
  id: string; name: string; lifecycleState: string;
  owningNodeName: string | null; ancestorNames: string[];
  criticalityCode: string | null; criticalityInherited: boolean;
  exposureDeclared: string | null; exposureObserved: string | null; exposureConflict: boolean;
  userBase: string; description: string;
  riskValue: number | null; riskBand: string | null; riskCoverage: string | null;
  findingCount: number; requestCount: number;
  components: Component[]; reviews: Review[]; cadence: Cadence | null;
  requests: Record<string, string>[];
}

export function ApplicationPage() {
  const { id = "" } = useParams();
  const [app, setApp] = useState<Detail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const reviews = usePaging(app?.reviews ?? []);
  const components = usePaging(app?.components ?? []);

  useEffect(() => {
    api.get<Detail>(`/api/ui/applications/${id}`).then(setApp).catch((e) => setError(e.message));
  }, [id]);

  if (!app) return <div className="text-sm text-muted-foreground">{error ?? "Loading…"}</div>;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <Link to="/applications" className="mb-1 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
            <ArrowLeft className="size-3" /> All applications
          </Link>
          <h1 className="truncate text-lg font-semibold tracking-tight">{app.name}</h1>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            {app.ancestorNames.length > 0 && <span>{app.ancestorNames.join(" › ")}</span>}
            {app.criticalityCode && <Badge tone="high">{app.criticalityCode}</Badge>}
            <Badge tone={app.exposureConflict ? "warn" : "neutral"}>{app.exposureDeclared ?? "undeclared"}</Badge>
            <Badge tone={app.lifecycleState === "ACTIVE" ? "ok" : "neutral"}>{app.lifecycleState}</Badge>
          </div>
        </div>
        <Button variant="outline" size="sm" asChild>
          <Link to={`/applications/${app.id}/edit`}><Pencil className="size-3" /> Edit</Link>
        </Button>
      </div>

      {/* Coverage BEFORE counts, deliberately. Every number below is qualified by whether anything
          has looked at this application, and leading with the counts presumes they mean something. */}
      <Card>
        <CardHeader>
          <CardTitle>Whole-application reviews</CardTitle>
          <CardDescription>
            Reviews covering the entire application, as distinct from a change or pre-go-live review.
            Only these discharge the periodic obligation.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Kpi label="Completed" value={app.cadence?.completed ?? 0} />
            <Kpi label="Last completed" value={app.cadence?.lastAt ?? "never"} />
            <Kpi label="Next due" value={app.cadence?.nextDueAt ?? (app.cadence?.status ?? "—")} />
            <Kpi label="Abandoned" value={app.cadence?.abandoned ?? 0} tone="warn" />
          </div>
          {app.reviews.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No whole-application review has been recorded for this application.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Review</TableHead><TableHead>Reason</TableHead>
                  <TableHead>Started</TableHead><TableHead>Closed</TableHead><TableHead>State</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {reviews.rows.map((r) => (
                  <TableRow key={r.requestId}>
                    <TableCell>
                      <Link to={`/board/${r.requestId}`} className="font-medium text-primary hover:underline">
                        {r.title ?? r.code}
                      </Link>
                      <div className="font-mono text-[11px] text-muted-foreground">{r.code}</div>
                    </TableCell>
                    <TableCell className="text-xs">{r.triggerLabel}</TableCell>
                    <TableCell className="font-mono text-[11px]">
                      {r.startedAt ?? "—"}
                      {r.startedAtIsIntakeDate && r.startedAt && (
                        <div className="text-[11px] text-muted-foreground">intake date — no start recorded</div>
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-[11px]">
                      {r.closedAt ?? <span className="text-muted-foreground">{r.open ? "still open" : "not recorded"}</span>}
                    </TableCell>
                    <TableCell>
                      <Badge tone={r.abandoned ? "warn" : r.open ? "info" : "ok"}>{r.state}</Badge>
                      {r.abandoned && (
                        <div className="text-[11px] text-muted-foreground">does not count towards the cycle</div>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {/* Inside the card body rather than flush to its edge, because this table sits under a
              KPI row instead of filling a card of its own. */}
          <Pager paging={reviews} unit="reviews" />
        </CardContent>
      </Card>

      {/* The posture dashboard. Placed above the profile deliberately: somebody opening an
          application is asking what condition it is in, and the profile is the answer to a question
          they only ask afterwards. It loads on its own request, so a slow aggregate never delays
          the identity of the page. */}
      <ApplicationPosture applicationId={app.id} />

      <div className="grid gap-5 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle>Profile</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
            <Fact label="Owner" value={app.owningNodeName} />
            <Fact label="Users" value={app.userBase || null} />
            <Fact label="Declared exposure" value={app.exposureDeclared} />
            <Fact label="Observed exposure" value={app.exposureObserved} />
            <Fact label="Findings" value={String(app.findingCount)} />
            <Fact label="Requests" value={String(app.requestCount)} />
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>Risk score</CardTitle></CardHeader>
          <CardContent>
            <ScoreCell value={app.riskValue} band={app.riskBand} coverage={app.riskCoverage} />
            <div className="mt-3"><CadenceCell cadence={app.cadence} /></div>
          </CardContent>
        </Card>
      </div>

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle>Composition</CardTitle>
          <CardDescription>Features, services and the security facts each one carries.</CardDescription>
        </CardHeader>
        {app.components.length === 0 ? (
          <CardContent className="text-sm text-muted-foreground">
            Nothing has been recorded beneath this application yet.
          </CardContent>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Part</TableHead><TableHead>Type</TableHead><TableHead>Exposure</TableHead>
                <TableHead className="text-right">Open</TableHead>
                <TableHead className="text-right">Critical / High / Medium</TableHead>
                <TableHead className="text-right">SCA</TableHead>
                <TableHead className="text-right">Accepted</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {components.rows.map((c) => (
                <TableRow key={c.id}>
                  {/* A project is the level that has a dashboard of its own, so it is a link. The
                      other part types — features, services, domains, repositories — have no page to
                      open yet, and a link that goes nowhere costs more than plain text: the reader
                      learns the column is not clickable and stops trying the rows that are. */}
                  <TableCell>
                    <span style={{ paddingInlineStart: `${(c.depth - 1) * 1.1}rem` }} className="text-sm">
                      {c.typeCode === "PROJECT"
                        ? <Link to={`/projects/${c.id}`} className="font-medium text-primary hover:underline">
                            {c.name}
                          </Link>
                        : c.name}
                    </span>
                  </TableCell>
                  <TableCell><Badge>{c.typeCode}</Badge></TableCell>
                  <TableCell className="text-xs">{c.exposure ?? "—"}</TableCell>
                  <TableCell className="tabular text-right">{c.findingOpen}</TableCell>
                  <TableCell className="tabular text-right text-xs">
                    <span className="text-sev-critical">{c.criticalOpen}</span>{" / "}
                    <span className="text-sev-high">{c.highOpen}</span>{" / "}
                    <span className="text-sev-medium">{c.mediumOpen}</span>
                  </TableCell>
                  <TableCell className="tabular text-right">{c.scaOpen}</TableCell>
                  <TableCell className="tabular text-right">{c.acceptedTotal}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        <Pager paging={components} unit="parts" />
      </Card>

      {/* Scoped to this record, so the same three tables answer "what keeps
          happening here" as answer it for the whole estate above. */}
      <TopWeaknesses asset={id} />
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string | null }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-sm">{value ?? <span className="text-muted-foreground">—</span>}</div>

    </div>
  );
}
