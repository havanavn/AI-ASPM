import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, Boxes, ChevronLeft, ChevronRight, Plus } from "lucide-react";
import { api } from "@/lib/api";
import type { ProjectRow } from "@/pages/ProjectsPage";
import { ApplicationPosture } from "@/components/ApplicationPosture";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { stateTone } from "@/components/tone";
import { ProjectAccess } from "@/components/ProjectAccess";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PageSize, Pager, usePaging, PAGE_SIZES } from "@/components/Paging";
import { TopWeaknesses } from "@/components/TopWeaknesses";

interface Component {
  id: string; name: string; typeCode: string; depth: number; path: string[];
  edgeType: string | null; lifecycleState: string; exposure: string | null;
  findingOpen: number; findingTotal: number; criticalOpen: number; highOpen: number;
  scaOpen: number; acceptedTotal: number;
}
interface Detail extends ProjectRow {
  components: Component[];
  requests: { id: string; code: string; state: string; created_at: string }[];
}

/**
 * One project.
 *
 * The same questions the application page answers, asked of a smaller thing: what is in it, what is
 * wrong with it, who is answerable, and what has been assessed. The difference that matters is the
 * breadcrumb — a project always names the application above it, because that is the relationship an
 * assessment request resolves through.
 */
export function ProjectPage() {
  const { id = "" } = useParams();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const components = usePaging(detail?.components ?? []);

  useEffect(() => {
    let live = true;
    api.get<Detail>(`/api/ui/projects/${id}`)
      .then((d) => live && setDetail(d))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, [id]);

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!detail) return <div className="text-sm text-muted-foreground">Loading…</div>;

  return (
    <div className="flex flex-col gap-5">
      <div>
        <Link to="/projects"
              className="mb-1 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-3" /> Projects
        </Link>
        <h1 className="text-lg font-semibold tracking-tight">{detail.name}</h1>
        <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
          {detail.applicationId ? (
            <>
              part of{" "}
              <Link to={`/applications/${detail.applicationId}`}
                    className="text-primary hover:underline">{detail.applicationName}</Link>
            </>
          ) : (
            <Badge tone="warn">under no application</Badge>
          )}
          <span>·</span>
          <span>delivered by {detail.owningNodeName ?? "nobody recorded"}</span>
        </p>
        {detail.description && <p className="mt-1 max-w-3xl text-sm">{detail.description}</p>}
      </div>

      {/* The four-tile row that used to sit here is gone. The posture panel below opens with the
          same four figures and eleven more, and two rows of headline numbers — computed by two
          different queries — is how a page comes to disagree with itself. Risk accepted keeps its
          place: it is named under the severity chart whenever it is not zero. */}
      {/* The same posture panel the application page carries, asked about this project instead.
          One component and one endpoint: a project is an asset of the same aggregate (ADR-009), so
          a second implementation here would be a second answer to the same question. */}
      <ApplicationPosture applicationId={id} kind="projects" subject="project" />

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle>Profile</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
            <Field label="Team accountable" value={detail.owningNodeName} />
            <Field label="Reporting line"
                   value={detail.ownerAncestors.length ? detail.ownerAncestors.join(" › ") : null} />
            <Field label="Lifecycle" value={detail.lifecycleState} />
            <div>
              <div className="text-xs text-muted-foreground">Criticality</div>
              <div className="text-sm">
                {detail.criticalityCode ?? "—"}
                {detail.criticalityInherited && (
                  <span className="ml-1 text-[11px] text-muted-foreground">inherited</span>
                )}
              </div>
            </div>
            <div>
              <div className="text-xs text-muted-foreground">Exposure</div>
              <div className="text-sm">
                {detail.exposureDeclared ?? "—"}
                {/* Declared and observed disagreeing is a finding about the inventory itself, so it
                    is shown rather than resolved silently in favour of either. */}
                {detail.exposureConflict && (
                  <Badge tone="critical" className="ml-1">
                    observed {detail.exposureObserved}
                  </Badge>
                )}
              </div>
            </div>
            <div>
              <div className="text-xs text-muted-foreground">Last finding detected</div>
              <div className="font-mono text-xs">
                {detail.lastDetectedAt ?? <span className="italic text-tone-unknown">never</span>}
              </div>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Coverage</CardTitle>
            <CardDescription>What is known about this project's posture.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 text-sm">
            <Field label="Parts recorded" value={String(detail.componentCount)} />
            <Field label="Dependency findings open" value={String(detail.scaOpen)} />
            <Field label="Requests raised" value={String(detail.requestCount)} />
          </CardContent>
        </Card>
      </div>

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Boxes className="size-4" /> What this project contains
          </CardTitle>
          <CardDescription>
            Everything beneath the project, at any depth. Findings on these roll up into the figures
            above.
          </CardDescription>
        </CardHeader>
        {detail.components.length === 0 ? (
          <CardContent className="text-sm text-muted-foreground">
            Nothing has been recorded under this project yet.
          </CardContent>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Part</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Exposure</TableHead>
                <TableHead className="text-right">Open</TableHead>
                <TableHead className="text-right">Severe</TableHead>
                <TableHead className="text-right">Dependency</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {components.rows.map((c) => (
                <TableRow key={c.id}>
                  <TableCell>
                    <span style={{ paddingInlineStart: `${(c.depth - 1) * 1.1}rem` }}
                          className="text-sm">{c.name}</span>
                    {c.path.length > 1 && (
                      <div className="text-[11px] text-muted-foreground">{c.path.join(" / ")}</div>
                    )}
                  </TableCell>
                  <TableCell className="font-mono text-[11px]">{c.typeCode}</TableCell>
                  <TableCell className="text-xs">{c.exposure ?? "—"}</TableCell>
                  <TableCell className="tabular text-right text-xs">{c.findingOpen}</TableCell>
                  <TableCell className="tabular text-right text-xs">
                    {c.criticalOpen + c.highOpen > 0
                      ? <Badge tone="critical">{c.criticalOpen + c.highOpen}</Badge>
                      : <span className="text-muted-foreground">0</span>}
                  </TableCell>
                  <TableCell className="tabular text-right text-xs">{c.scaOpen}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        <Pager paging={components} unit="parts" />
      </Card>

      <ProjectAccess projectId={detail.id} />

      {/* LAST on the page, deliberately. This is the section that grows without bound as a project
          accumulates assessments; anything fixed placed below it would be pushed off the screen
          within a year. Paginated in the QUERY, not here — see IntakeService. */}
      <RequestHistory projectId={detail.id} />

      {/* Scoped to this record, so the same three tables answer "what keeps
          happening here" as answer it for the whole estate above. */}
      <TopWeaknesses asset={id} />
    </div>
  );
}

interface RequestRow {
  id: string; code: string; title: string | null; state: string; stateCategory: string | null;
  createdAt: string | null; dueAt: string | null; requestedBy: string;
  findingOpen: number; findingTotal: number;
}

function RequestHistory({ projectId }: { projectId: string }) {
  const [page, setPage] = useState(0);
  // Paged in the QUERY, so the size is a request parameter rather than a slice. The control is the
  // same one every other table carries — a reader should not have to know which side of the wire
  // the paging happens on to work out how to ask for more rows.
  const [size, setSize] = useState<number>(PAGE_SIZES[0]);
  const [data, setData] = useState<{ rows: RequestRow[]; page: number; size: number; total: number } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<{ rows: RequestRow[]; page: number; size: number; total: number }>(
      `/api/ui/projects/${projectId}/requests?page=${page}&size=${size}`)
      .then(setData).catch((e) => setError(e.message));
  }, [projectId, page, size]);
  useEffect(load, [load]);

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex-row items-center justify-between">
        <div>
          <CardTitle>Assessment requests</CardTitle>
          <CardDescription>
            Raised against this project, newest first.
            {data && data.total > 0 && ` ${data.total} in total.`}
          </CardDescription>
        </div>
        <Button size="sm" asChild>
          <Link to={`/requests/new?project=${projectId}`}><Plus /> Request an assessment</Link>
        </Button>
      </CardHeader>

      {error && <CardContent className="text-sm text-destructive">{error}</CardContent>}
      {data && data.rows.length === 0 ? (
        // Not "0 assessments". Nothing requested and nothing found are different claims, and only
        // one of them means the project is in good shape.
        <CardContent className="py-8 text-center text-sm text-muted-foreground">
          No assessment has been requested against this project.
        </CardContent>
      ) : (
        <>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Request</TableHead>
                <TableHead>Raised by</TableHead>
                <TableHead>Raised</TableHead>
                <TableHead>Due</TableHead>
                <TableHead className="text-right">Findings</TableHead>
                <TableHead>State</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(data?.rows ?? []).map((r) => (
                <TableRow key={r.id}>
                  <TableCell className="max-w-72">
                    <Link to={`/board/${r.id}`}
                          className="font-medium text-primary hover:underline">
                      {r.title ?? r.code}
                    </Link>
                    <div className="font-mono text-[11px] text-muted-foreground">{r.code}</div>
                  </TableCell>
                  <TableCell className="text-xs">{r.requestedBy || "—"}</TableCell>
                  <TableCell className="font-mono text-[11px]">{r.createdAt ?? "—"}</TableCell>
                  <TableCell className="font-mono text-[11px]">{r.dueAt ?? "—"}</TableCell>
                  <TableCell className="tabular text-right text-xs">
                    {r.findingTotal === 0
                      ? <span className="italic text-muted-foreground">none</span>
                      : `${r.findingOpen} / ${r.findingTotal}`}
                  </TableCell>
                  <TableCell><Badge tone={stateTone(r.stateCategory)}>{r.state}</Badge></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {/* Gated on the SMALLEST offered size, not on the current one. Gating on data.size hid
              the control the moment somebody chose a hundred rows over a set of thirty — and with
              it, the only way back to twenty. */}
          {data && data.total > PAGE_SIZES[0] && (
            <div className="flex flex-wrap items-center justify-between gap-3 border-t px-5 py-3 text-xs">
              <span className="tabular text-muted-foreground">
                Showing {data.page * data.size + 1}–
                {Math.min(data.total, (data.page + 1) * data.size)} of {data.total} requests
              </span>
              <div className="flex items-center gap-3">
                <PageSize size={size} onChange={(s) => { setSize(s); setPage(0); }} />
                <span className="tabular text-muted-foreground">Page {data.page + 1} of {pages}</span>
                <Button variant="outline" size="sm" disabled={page === 0}
                        onClick={() => setPage(page - 1)}><ChevronLeft /> Previous</Button>
                <Button variant="outline" size="sm" disabled={page + 1 >= pages}
                        onClick={() => setPage(page + 1)}>Next <ChevronRight /></Button>
              </div>
            </div>
          )}
        </>
      )}
    </Card>
  );
}

function Field({ label, value }: { label: string; value: string | null }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-sm">{value ?? <span className="text-muted-foreground">—</span>}</div>

    </div>
  );
}
