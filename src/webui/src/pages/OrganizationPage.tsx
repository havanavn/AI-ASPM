import { useCallback, useEffect, useState } from "react";
import { Building2, Loader2, Pencil, Plus, X } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Combobox } from "@/components/Combobox";
import { Pager, usePaging } from "@/components/Paging";

interface Node {
  id: string; name: string; typeCode: string; depth: number;
  parentName: string | null; parentId: string | null; mayOwnAssets: boolean;
  criticalityCode: string | null; assetCount: number; childCount: number;
  lifecycleState: string; rowVersion: number;
}
interface NodeType { id: string; code: string; mayOwnAssets: boolean; ordinal: number }
interface Tier { id: string; code: string; ordinal: number }
interface Payload { nodes: Node[]; nodeTypes: NodeType[]; criticalities: Tier[] }

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
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><Building2 className="size-4" /> Nodes</CardTitle>
          <CardDescription>
            Node types and depth are tenant configuration — nothing here is named in code (ADR-027).
          </CardDescription>
        </CardHeader>
        {data.nodes.length === 0 ? (
          <CardContent className="text-sm text-muted-foreground">No node is configured yet.</CardContent>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Node</TableHead><TableHead>Type</TableHead><TableHead>Parent</TableHead>
                <TableHead>Criticality</TableHead>
                <TableHead className="text-right">Applications</TableHead>
                <TableHead className="text-right">Children</TableHead>
                <TableHead>Lifecycle</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {paging.rows.map((n) => (
                editing === n.id ? (
                  <TableRow key={n.id}>
                    <TableCell colSpan={8} className="bg-muted/30 p-0">
                      <EditNode node={n} tiers={data.criticalities}
                                onSaved={() => done(`Saved ${n.name}.`)}
                                onCancel={() => setEditing(null)} />
                    </TableCell>
                  </TableRow>
                ) : (
                  <TableRow key={n.id}>
                    <TableCell>
                      <span style={{ paddingInlineStart: `${n.depth * 1.1}rem` }} className="text-sm font-medium">
                        {n.name}
                      </span>
                      {!n.mayOwnAssets && (
                        <div className="text-[11px] text-muted-foreground"
                             style={{ paddingInlineStart: `${n.depth * 1.1}rem` }}>
                          cannot own applications
                        </div>
                      )}
                    </TableCell>
                    <TableCell><Badge>{n.typeCode}</Badge></TableCell>
                    <TableCell className="text-xs text-muted-foreground">{n.parentName ?? "—"}</TableCell>
                    <TableCell>
                      {n.criticalityCode ? <Badge tone="high">{n.criticalityCode}</Badge>
                                         : <Badge tone="unknown">none</Badge>}
                    </TableCell>
                    <TableCell className="tabular text-right">{n.assetCount}</TableCell>
                    <TableCell className="tabular text-right">{n.childCount}</TableCell>
                    <TableCell>
                      <Badge tone={n.lifecycleState === "ACTIVE" ? "ok" : "neutral"}>{n.lifecycleState}</Badge>
                    </TableCell>
                    <TableCell className="text-right">
                      <Button size="sm" variant="ghost"
                              onClick={() => { setEditing(n.id); setCreating(false); setNotice(null); }}>
                        <Pencil className="size-3" /> Edit
                      </Button>
                    </TableCell>
                  </TableRow>
                )
              ))}
            </TableBody>
          </Table>
        )}
        <Pager paging={paging} unit="nodes" />
      </Card>
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
                  {node.assetCount > 0 && `${node.assetCount} application(s)`} still sit here.
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
