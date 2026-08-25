import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  Building2, ChevronDown, ChevronRight, Download, Loader2, Minus, Pencil, Plus, TrendingDown,
  TrendingUp, X,
} from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { GraphDrawer } from "@/components/GraphDrawer";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Combobox } from "@/components/Combobox";
import { Kpi } from "@/components/Kpi";
import { Pager, usePaging } from "@/components/Paging";
import { RiskCell, type RiskPosture } from "@/components/Risk";
import { ScoreCell } from "@/components/inventory";
import type { AppRow } from "@/pages/ApplicationsPage";

/**
 * What is true of the whole subtree beneath a node, not of the node itself.
 *
 * `applications` therefore differs from the node's own `assetCount`, which counts only what is owned
 * directly and is what the deprecation guard turns on. Both are carried because conflating them
 * would either weaken the guard or understate the estate.
 */
interface Metrics {
  applications: number; neverAssessed: number;
  openNow: number; openBefore: number;
  serious: number; exposedSerious: number;
  lastAssessedAt: string | null; measured: boolean;
}
interface Node {
  id: string; name: string; typeCode: string; depth: number;
  parentName: string | null; parentId: string | null; mayOwnAssets: boolean;
  criticalityCode: string | null; assetCount: number; childCount: number;
  lifecycleState: string; rowVersion: number;
  metrics: Metrics;
  /** Null where the model produced no row for this subtree. Not the same as a score of zero. */
  risk: RiskPosture | null;
}
interface NodeType { id: string; code: string; mayOwnAssets: boolean; ordinal: number }
interface Tier { id: string; code: string; ordinal: number }
interface Payload {
  nodes: Node[]; nodeTypes: NodeType[]; criticalities: Tier[];
  /** One figure over everything the caller reaches, computed in one pass rather than summed here. */
  totals: RiskPosture;
}

const NONE = "__none__";
const ROOT = "__root__";

/**
 * The accountability tree, now editable in place.
 *
 * Editing used to hand off to the server-rendered page. It does not any more, and the reason is more
 * than consistency: the tree is the one screen where seeing the shape while you change it matters,
 * and a round trip to a separate form loses exactly that.
 *
 * **Type and parent are not editable after creation.** That is the service's rule and it is stated
 * here rather than discovered: moving a node rewrites every closure row and every cached ancestor
 * path beneath it, which is a migration and not an edit. Getting the placement right at creation is
 * the point at which it is cheap.
 */
export function OrganizationPage() {
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const paging = usePaging(data?.nodes ?? []);

  const load = useCallback(() => {
    api.get<Payload>("/api/ui/organization").then(setData).catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  function done(message: string) {
    setEditing(null);
    setCreating(false);
    setNotice(message);
    load();
  }

  if (!data) return <div className="text-sm text-muted-foreground">{error ?? "Loading…"}</div>;

  const totals = data.totals;
  const unmeasured = totals?.scoped ? totals.assets - totals.measuredAssets : 0;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-lg font-semibold tracking-tight">Organization</h1>
          <p className="text-xs text-muted-foreground">
            The accountability tree. Scope is derived from it and never asserted by a client.
          </p>
        </div>
        <Button variant={creating ? "ghost" : "outline"} size="sm"
                onClick={() => { setCreating(!creating); setEditing(null); }}>
          {creating ? <><X className="size-3" /> Cancel</> : <><Plus className="size-3" /> Add a node</>}
        </Button>
      </div>

      {/* Over the whole reachable scope, from the server in one pass. NOT summed from the rows below,
          which overlap: every figure on a node covers its entire subtree, so adding a column up would
          count a finding once per level of the tree above it. */}
      {totals?.scoped && (
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <Kpi label="Nodes" value={data.nodes.length}
               hint="Everything you can reach, at every level" />
          <Kpi label="Applications" value={totals.assets} />
          <Kpi label="Never assessed" value={unmeasured} tone="critical"
               hint="No assessment has ever covered these, so they contribute no findings" />
          <Kpi label="Open findings" value={totals.findings} tone="info"
               hint={totals.posture === null
                 ? "Group risk withheld — not enough of the estate is measured"
                 : `Group risk ${totals.posture} · ${totals.band?.toLowerCase()}`} />
        </div>
      )}

      {notice && (
        <Card><CardContent className="flex items-center justify-between gap-4 text-sm">
          <span>{notice}</span>
          <Button size="sm" variant="ghost" onClick={() => setNotice(null)}>Dismiss</Button>
        </CardContent></Card>
      )}

      {creating && (
        <CreateNode data={data} onSaved={() => done("Node created.")}
                    onCancel={() => setCreating(false)} />
      )}

      <Card className="overflow-hidden">
        <CardHeader className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <CardTitle className="flex items-center gap-2"><Building2 className="size-4" /> Nodes</CardTitle>
            <CardDescription>
              Node types and depth are tenant configuration — nothing here is named in code (ADR-027).
              Open a node to see the applications beneath it. Every figure covers that node's whole
              subtree, so a finding counts against each level accountable for it and the columns are not
              meant to add up.
            </CardDescription>
          </div>
          {/* The whole inventory, not one node's.
              The per-node button inside an expanded row exports that subtree, which is the wrong
              affordance for "give me everything" — it is reachable only after choosing a node, and
              choosing a node is exactly what this reader does not want to do. So the unnarrowed
              export lives at the top, with no `node` parameter: the file is bounded by the caller's
              own scope and by nothing else, which for a reader who can see the whole tree is the
              whole tree. A scoped reader gets their own subtree and the file says so on its Filter
              sheet, because a short file must not be readable as a small estate. */}
          <Button asChild variant="secondary" size="sm" className="shrink-0">
            <a href="/api/ui/applications/export">
              <Download className="size-3.5" /> Export the whole inventory
            </a>
          </Button>
        </CardHeader>
        {data.nodes.length === 0 ? (
          <CardContent className="text-sm text-muted-foreground">No node is configured yet.</CardContent>
        ) : (
          <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Node</TableHead><TableHead>Parent</TableHead>
                <TableHead>Criticality</TableHead>
                <TableHead className="text-right">Risk</TableHead>
                <TableHead className="text-right">Open</TableHead>
                <TableHead className="text-right">Serious &amp; exposed</TableHead>
                <TableHead className="text-right">Applications</TableHead>
                <TableHead>Last assessed</TableHead>
                <TableHead>Lifecycle</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {paging.rows.map((n) => (
                editing === n.id ? (
                  <TableRow key={n.id}>
                    <TableCell colSpan={10} className="bg-muted/30 p-0">
                      <EditNode node={n} tiers={data.criticalities}
                                onSaved={() => done(`Saved ${n.name}.`)}
                                onCancel={() => setEditing(null)} />
                    </TableCell>
                  </TableRow>
                ) : (
                  <NodeRow key={n.id} node={n} open={expanded === n.id}
                           onToggle={() => setExpanded(expanded === n.id ? null : n.id)}
                           onEdit={() => { setEditing(n.id); setCreating(false); setNotice(null); }} />
                )
              ))}
            </TableBody>
          </Table>
          </div>
        )}
        <Pager paging={paging} unit="nodes" />
      </Card>
    </div>
  );
}

/**
 * One node, and — when it is open — the applications beneath it.
 *
 * **Why the drill-down is here rather than only a link to the inventory.** The tree is where somebody
 * decides which part of the group to look at; making them leave it, filter, and come back to compare
 * against the sibling turns one question into three navigations. The link to the full inventory is
 * kept beside the list, because everything the inventory offers — search, the review filters, sorting
 * — belongs there and not duplicated here.
 */
function NodeRow({ node, open, onToggle, onEdit }: {
  node: Node; open: boolean; onToggle: () => void; onEdit: () => void;
}) {
  const m = node.metrics;
  const delta = m.openNow - m.openBefore;
  const Chevron = open ? ChevronDown : ChevronRight;

  return (
    <>
      <TableRow>
        <TableCell>
          <div className="flex items-center gap-1"
               style={{ paddingInlineStart: `${node.depth * 1.1}rem` }}>
            {/* The whole name is the control. A disclosure triangle alone is a target most people
                miss, and the count in the Applications column is the other way in. */}
            <button type="button" onClick={onToggle}
                    className="flex items-center gap-1 text-sm font-medium hover:underline">
              <Chevron className="size-3.5 shrink-0 text-muted-foreground" />
              {node.name}
            </button>
          </div>
          <div className="flex flex-wrap items-center gap-1.5 pt-1 text-[11px] text-muted-foreground"
               style={{ paddingInlineStart: `${node.depth * 1.1 + 1.15}rem` }}>
            <Badge>{node.typeCode}</Badge>
            {node.childCount > 0 && <span>{node.childCount} below</span>}
            {!node.mayOwnAssets && <span>cannot own applications</span>}
          </div>
        </TableCell>
        <TableCell className="text-xs text-muted-foreground">{node.parentName ?? "—"}</TableCell>
        <TableCell>
          {node.criticalityCode ? <Badge tone="high">{node.criticalityCode}</Badge>
                                : <Badge tone="unknown">none</Badge>}
        </TableCell>
        <TableCell className="text-right"><RiskCell row={node.risk} /></TableCell>
        <TableCell className="text-right">
          <span className="tabular text-xs">{m.openNow}</span>
          {/* Direction beside the count, the same way the overview reports it. A number alone cannot
              say whether the unit is gaining or losing ground, which is the question its manager is
              actually asked. */}
          <span className={cn("ml-1.5 inline-flex items-center gap-0.5 text-[11px]",
                              delta > 0 ? "text-sev-critical"
                              : delta < 0 ? "text-tone-ok" : "text-muted-foreground")}
                title={`${m.openBefore} open ninety days ago · ${m.serious} of the ${m.openNow} open now are serious`}>
            {delta > 0 ? <TrendingUp className="size-3" />
              : delta < 0 ? <TrendingDown className="size-3" />
              : <Minus className="size-3" />}
            {delta !== 0 && Math.abs(delta)}
          </span>
        </TableCell>
        <TableCell className="text-right">
          {m.exposedSerious > 0
            ? <Badge tone="critical">{m.exposedSerious}</Badge>
            : <span className="tabular text-xs text-muted-foreground">0</span>}
        </TableCell>
        <TableCell className="text-right">
          {m.applications === 0 ? (
            <span className="tabular text-xs text-muted-foreground">0</span>
          ) : (
            <Link to={`/applications?node=${node.id}`} className="tabular text-xs text-primary hover:underline">
              {m.applications}
            </Link>
          )}
          {/* The count that changes how every other figure on this row should be read: an application
              nobody has assessed contributes no findings, so it makes the row look quieter than the
              node is. */}
          {m.neverAssessed > 0 && (
            <div className="pt-0.5"><Badge tone="critical">{m.neverAssessed} never assessed</Badge></div>
          )}
        </TableCell>
        <TableCell className="font-mono text-[11px]">
          {/* Never is a fact, and a worse one than a stale date. */}
          {m.lastAssessedAt ?? <span className="italic text-tone-unknown">never</span>}
        </TableCell>
        <TableCell>
          <Badge tone={node.lifecycleState === "ACTIVE" ? "ok" : "neutral"}>{node.lifecycleState}</Badge>
        </TableCell>
        <TableCell className="text-right">
          <Button size="sm" variant="ghost" onClick={onEdit}><Pencil className="size-3" /> Edit</Button>
        </TableCell>
      </TableRow>
      {open && (
        <TableRow>
          <TableCell colSpan={10} className="bg-muted/30 p-0">
            <NodeApplications node={node} />
          </TableCell>
        </TableRow>
      )}
    </>
  );
}

/**
 * How many applications the inline list draws per page.
 *
 * This used to be a hard truncation: the list drew the first twenty-five and pointed the reader at
 * the inventory for the rest. Smaller than the shared default of twenty because this table is nested
 * inside an expanded tree row — a hundred rows here pushes every node below it off the screen — and
 * the reader can raise it from the footer if they want more.
 */
const INLINE_PAGE = 15;

/**
 * One shared empty list for the not-yet-loaded case.
 *
 * A fresh `[]` on every render is a new identity, which invalidates the slice memo in `usePaging` on
 * every render of a tree that has other reasons to re-render often.
 */
const EMPTY_APPS: AppRow[] = [];

/**
 * The applications beneath one node, fetched when the node is opened.
 *
 * Fetched on open rather than with the tree: a group with two hundred nodes would otherwise pay for
 * two hundred lists to show one. The server applies the same subtree rule the counts use — the node
 * and everything under it — so the number in the row and the length of this list agree.
 */
function NodeApplications({ node }: { node: Node }) {
  const [rows, setRows] = useState<AppRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Called before the loading and empty returns below, because a hook that runs conditionally
  // desynchronises React's hook order on the render where the fetch lands.
  const paging = usePaging(rows ?? EMPTY_APPS, INLINE_PAGE);

  useEffect(() => {
    let live = true;
    setRows(null);
    setError(null);
    api.get<{ rows: AppRow[] }>(`/api/ui/applications?node=${encodeURIComponent(node.id)}`)
      .then((d) => live && setRows(d.rows))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, [node.id]);

  if (error) return <div className="px-5 py-4 text-sm text-destructive">{error}</div>;
  if (!rows) {
    return (
      <div className="flex items-center gap-2 px-5 py-4 text-xs text-muted-foreground">
        <Loader2 className="size-3 animate-spin" /> Loading applications…
      </div>
    );
  }
  if (rows.length === 0) {
    return (
      <div className="px-5 py-4 text-xs text-muted-foreground">
        {/* Two different facts, and the tree must not blur them. A node that cannot own applications
            is configured that way; one that can and owns none is an accountability gap. */}
        {node.mayOwnAssets
          ? `No application is recorded under ${node.name}. Nothing beneath it is being measured.`
          : `${node.typeCode} nodes cannot own applications. Look at the nodes beneath this one.`}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2 px-5 py-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-xs font-medium">
          {rows.length} application{rows.length === 1 ? "" : "s"} under {node.name}
          <span className="pl-1.5 font-normal text-muted-foreground">
            including every node beneath it
          </span>
        </span>
        <span className="flex items-center gap-2">
          {/* The organization tree and the asset graph are separate structures joined by ownership
              (ADR-001); this is where a reader crosses from one into the other. */}
          <GraphDrawer compact rootId={node.id} label={node.name} />
          {/* Scoped to this node and everything beneath it — the same subtree the list and the count
              above already agree on, so the file cannot disagree with the screen it came from. A
              link rather than a fetch, for the reason the inventory's own export gives; recorded in
              the audit trail with this node as its scope (PRD-API-046). */}
          <Button asChild variant="ghost" size="sm"
                  className="h-6 px-1.5 text-muted-foreground hover:text-foreground"
                  title={`Export the ${rows.length} application${rows.length === 1 ? "" : "s"} under ${node.name} to Excel`}>
            <a href={`/api/ui/applications/export?node=${encodeURIComponent(node.id)}`}
               aria-label={`Export the applications under ${node.name} to Excel`}>
              <Download className="size-3.5" />
            </a>
          </Button>
          <Link to={`/applications?node=${node.id}`} className="text-xs text-primary hover:underline">
            Open in the inventory
          </Link>
        </span>
      </div>
      <div className="overflow-x-auto rounded-md border bg-card">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Application</TableHead>
              <TableHead>Owner</TableHead>
              <TableHead>Criticality</TableHead>
              <TableHead>Exposure</TableHead>
              <TableHead className="text-right">Risk</TableHead>
              <TableHead className="text-right">Findings</TableHead>
              <TableHead>Lifecycle</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {paging.rows.map((a) => (
              <TableRow key={a.id}>
                <TableCell>
                  <Link to={`/applications/${a.id}`} className="text-xs font-medium text-primary hover:underline">
                    {a.name}
                  </Link>
                </TableCell>
                <TableCell className="text-xs text-muted-foreground">
                  {/* The owning node, not this one. Opening a division and seeing the division's name
                      on every row would hide which team actually holds each application. */}
                  {a.owningNodeName ?? <span className="italic">unowned</span>}
                </TableCell>
                <TableCell>
                  {a.criticalityCode
                    ? <Badge tone="high">{a.criticalityCode}{a.criticalityInherited ? " (inherited)" : ""}</Badge>
                    : <Badge tone="unknown">none</Badge>}
                </TableCell>
                <TableCell className="text-xs text-muted-foreground">
                  {a.exposureDeclared ?? "—"}
                  {a.exposureConflict && <Badge tone="warn">observed differs</Badge>}
                </TableCell>
                <TableCell className="text-right">
                  <ScoreCell value={a.riskValue} band={a.riskBand} coverage={a.riskCoverage} />
                </TableCell>
                <TableCell className="tabular text-right text-xs">{a.findingCount}</TableCell>
                <TableCell>
                  <Badge tone={a.lifecycleState === "ACTIVE" ? "ok" : "neutral"}>{a.lifecycleState}</Badge>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {/* Inside the bordered table rather than under it, so a nested table reads as one object.
            The footer states the extent even on a single page: a list that stops without saying so
            reads as "that is all of them", which is the one thing it must not mean here. */}
        <Pager paging={paging} unit="applications" />
      </div>
      {paging.pages > 1 && (
        <p className="text-[11px] text-muted-foreground">
          <Link to={`/applications?node=${node.id}`} className="text-primary hover:underline">
            Open these {rows.length} in the inventory
          </Link>{" "}
          to search, sort and filter them, and to see the declared fields this table has no room for.
        </p>
      )}
    </div>
  );
}

/** Create a node. Type and parent are chosen once, here, and are fixed afterwards. */
function CreateNode({ data, onSaved, onCancel }: {
  data: Payload; onSaved: () => void; onCancel: () => void;
}) {
  const [name, setName] = useState("");
  const [typeId, setTypeId] = useState(data.nodeTypes[0]?.id ?? "");
  const [parentId, setParentId] = useState(ROOT);
  const [tierId, setTierId] = useState(NONE);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function create() {
    setBusy(true);
    setProblem(null);
    try {
      await api.post("/api/ui/organization", {
        name, typeId,
        parentId: parentId === ROOT ? null : parentId,
        criticalityTierId: tierId === NONE ? null : tierId,
      });
      onSaved();
    } catch (e) {
      setProblem((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Add a node</CardTitle>
        <CardDescription>
          Its type decides whether applications can be owned here, and its parent decides who can see
          everything beneath it. Neither can be changed later without a migration, so both are worth a
          moment now.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {problem && <p className="text-sm text-destructive">{problem}</p>}
        <div className="grid gap-4 md:grid-cols-4">
          <div className="flex flex-col gap-1.5">
            <Label>Name<span className="pl-0.5 text-destructive">*</span></Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Payments" />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Type<span className="pl-0.5 text-destructive">*</span></Label>
            <Select value={typeId} onValueChange={setTypeId}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                {data.nodeTypes.map((t) => (
                  <SelectItem key={t.id} value={t.id}>
                    {t.code}{t.mayOwnAssets ? "" : " — cannot own applications"}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Parent</Label>
            {/* Only nodes the caller already reaches are listed, and the server re-checks whatever is
                submitted. A picker is a usability feature, never an authorization control. */}
            <Combobox
              items={[{ id: ROOT, name: "No parent — a root node" },
                      ...data.nodes.map((n) => ({ id: n.id, name: n.name, hint: n.typeCode.toLowerCase() }))]}
              value={parentId} onChange={setParentId}
              placeholder="No parent — a root node" clearLabel="" />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Criticality</Label>
            <Select value={tierId} onValueChange={setTierId}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={NONE}>None</SelectItem>
                {data.criticalities.map((t) => (
                  <SelectItem key={t.id} value={t.id}>{t.code}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
        <div className="flex gap-2">
          <Button size="sm" onClick={create} disabled={busy || !name.trim() || !typeId}>
            {busy && <Loader2 className="size-3 animate-spin" />} Create
          </Button>
          <Button size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>
        </div>
      </CardContent>
    </Card>
  );
}

/** Rename, re-tier, or deprecate one node, in the row it lives in. */
function EditNode({ node, tiers, onSaved, onCancel }: {
  node: Node; tiers: Tier[]; onSaved: () => void; onCancel: () => void;
}) {
  const [name, setName] = useState(node.name);
  const [tierId, setTierId] = useState(
    tiers.find((t) => t.code === node.criticalityCode)?.id ?? NONE);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  const blocked = node.childCount > 0 || node.assetCount > 0;

  async function act(path: string, body: unknown) {
    setBusy(true);
    setProblem(null);
    try {
      await api.post(path, body);
      onSaved();
    } catch (e) {
      setProblem((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-3 p-4">
      {problem && <p className="text-sm text-destructive">{problem}</p>}
      <div className="grid gap-3 md:grid-cols-3">
        <div className="flex flex-col gap-1.5">
          <Label>Name</Label>
          <Input value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label>Criticality</Label>
          <Select value={tierId} onValueChange={setTierId}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value={NONE}>None</SelectItem>
              {tiers.map((t) => <SelectItem key={t.id} value={t.id}>{t.code}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label className="text-muted-foreground">Type and parent</Label>
          <p className="text-[11px] leading-tight text-muted-foreground">
            {node.typeCode} · {node.parentName ?? "root"}. Fixed after creation — moving a node
            rewrites the scope of everything beneath it, which is a migration rather than an edit.
          </p>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" disabled={busy || !name.trim()}
                onClick={() => act(`/api/ui/organization/${node.id}`, {
                  name, criticalityTierId: tierId === NONE ? null : tierId,
                  rowVersion: node.rowVersion,
                })}>
          {busy && <Loader2 className="size-3 animate-spin" />} Save
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>

        <span className="flex-1" />

        {node.lifecycleState === "ACTIVE" && (
          confirming ? (
            <>
              <span className="text-[11px] text-muted-foreground">
                Deprecate {node.name}? It stops appearing in pickers; nothing is deleted.
              </span>
              <Button size="sm" variant="destructive" disabled={busy}
                      onClick={() => act(`/api/ui/organization/${node.id}/deprecate`,
                        { rowVersion: node.rowVersion })}>
                Deprecate
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setConfirming(false)}>No</Button>
            </>
          ) : (
            <>
              {/* Disabled with the reason beside it rather than hidden. A person who cannot find the
                  control assumes it is missing; one who sees why it is unavailable knows what to do. */}
              {blocked && (
                <span className="text-[11px] text-muted-foreground">
                  {node.childCount > 0 && `${node.childCount} child node(s)`}
                  {node.childCount > 0 && node.assetCount > 0 && " and "}
                  {/* "asset", not "application". The guard counts everything owned directly — a
                      repository or an artifact blocks deprecation exactly as an application does, and
                      naming only one of them sends somebody looking for the wrong thing to move. */}
                  {node.assetCount > 0 && `${node.assetCount} directly-owned asset(s)`} still sit here.
                </span>
              )}
              <Button size="sm" variant="outline" disabled={busy || blocked}
                      onClick={() => setConfirming(true)}>
                Deprecate
              </Button>
            </>
          )
        )}
      </div>
    </div>
  );
}
