import { useCallback, useEffect, useState } from "react";
import { ChevronDown, ChevronUp, Loader2, Plus, RotateCcw, X } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

interface Environment {
  id: string | null;
  code: string;
  label: string;
  labelVi: string;
  purpose: string | null;
  ordinal: number;
  /** `ACTIVE`, `DEPRECATED`, or `UNDECLARED` for one only the recorded data carries. */
  lifecycleState: string;
  declared: boolean;
  rowVersion: number;
  /**
   * Endpoints currently published in this environment, tenant-wide.
   *
   * `null` where the reader does not hold the manage permission: the count carries no scope
   * predicate and cannot have one, so it is withheld rather than narrowed. Absent is not zero — a
   * zero would be a claim about the estate.
   */
  endpointCount: number | null;
}
interface Payload { environments: Environment[]; mayManage: boolean }

/**
 * The endpoint environment catalogue: which environments the platform asks for a host in.
 *
 * **Why this screen exists.** The environment an endpoint is published in was tenant vocabulary in
 * the schema and a hardcoded pair of fields in each editor — Production and Staging on applications,
 * Production and UAT on projects. Because the inventory offers a domain column only for environments
 * its data already carries, an environment with no write path never became a column: an application's
 * UAT host was unrecordable and therefore absent from every list, filter and count. Declaring one
 * here puts it in both editors, in both column pickers and in both filter bars, with no release
 * (ADR-061).
 *
 * **Three states, not two.** An environment can be active, retired, or present only because recorded
 * data carries it — an importer writing a name nobody declared. The third is shown rather than hidden:
 * suppressing it would hide a recorded host, and the useful action on it is to declare it, which a
 * screen that does not admit it exists cannot offer.
 *
 * **Retiring one does not touch a single endpoint,** which is why the count is beside the button. The
 * edges stay current, keep their column, and reappear in every form the moment it is restored.
 */
export function EndpointEnvironments() {
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Payload>("/api/ui/settings/environments")
      .then(setData).catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  function done(message: string) {
    setCreating(null);
    setEditing(null);
    setNotice(message);
    load();
  }

  async function lifecycle(environment: Environment, active: boolean) {
    if (!environment.id) return;
    try {
      await api.post(`/api/ui/settings/environments/${environment.id}/lifecycle`,
        { active, rowVersion: environment.rowVersion });
      done(active ? `${environment.label} is offered again.`
                  : `${environment.label} is retired. Its ${environment.endpointCount ?? "existing"} `
                    + "endpoint(s) are unchanged and still current.");
    } catch (e) {
      setNotice((e as ApiError).message);
    }
  }

  async function move(environment: Environment, delta: number) {
    if (!environment.id) return;
    try {
      await api.post(`/api/ui/settings/environments/${environment.id}/move`, { delta });
      load();
    } catch (e) {
      setNotice((e as ApiError).message);
    }
  }

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>;

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Endpoint environments</CardTitle>
          <CardDescription>
            Which environments the platform asks for a host in. Tenant configuration, not product
            code (ADR-027) — an environment declared here gets a domain input on the application and
            project editors, a column on both inventory tables, and a filter for whether a host is
            recorded in it. An active environment gets its column even when nothing is recorded,
            because "no host recorded here" is an answer somebody needs.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-end gap-3">
          {data.mayManage ? (
            <Button variant={creating !== null ? "ghost" : "outline"} size="sm"
                    onClick={() => { setCreating(creating === null ? "" : null); setEditing(null); }}>
              {creating !== null ? <><X className="size-3" /> Cancel</>
                                 : <><Plus className="size-3" /> Declare an environment</>}
            </Button>
          ) : (
            // Said, not hidden. Somebody who cannot find the button assumes the feature is missing;
            // somebody told which permission they lack knows who to ask.
            <p className="text-xs text-muted-foreground">
              Read-only — declaring environments needs <code>cfg.asset.field.manage</code>.
            </p>
          )}
        </CardContent>
      </Card>

      {notice && (
        <Card><CardContent className="flex items-center justify-between gap-4 text-sm">
          <span>{notice}</span>
          <Button size="sm" variant="ghost" onClick={() => setNotice(null)}>Dismiss</Button>
        </CardContent></Card>
      )}

      {creating !== null && (
        <EnvironmentForm code={creating} onSaved={done} onCancel={() => setCreating(null)} />
      )}

      <Card className="overflow-hidden">
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Code</TableHead>
                <TableHead>Label</TableHead>
                <TableHead>What it is for</TableHead>
                <TableHead className="text-right">Endpoints</TableHead>
                <TableHead>State</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.environments.map((environment, index) => (
                <TableRow key={environment.code}>
                  <TableCell className="font-mono text-xs">{environment.code}</TableCell>
                  <TableCell className="text-xs">
                    {environment.label}
                    {environment.labelVi && (
                      <span className="block text-[11px] text-muted-foreground">
                        {environment.labelVi}
                      </span>
                    )}
                  </TableCell>
                  <TableCell className="max-w-md text-[11px] leading-tight text-muted-foreground">
                    {environment.purpose ?? (
                      // Not blank. A field whose purpose nobody can state is one people record
                      // inconsistently and then filter on.
                      <span className="italic">no purpose recorded</span>
                    )}
                  </TableCell>
                  <TableCell className="tabular text-right text-xs">
                    {environment.endpointCount === null
                      ? <span className="italic text-muted-foreground">—</span>
                      : environment.endpointCount}
                  </TableCell>
                  <TableCell>
                    {environment.lifecycleState === "ACTIVE"
                      ? <Badge tone="ok">offered</Badge>
                      : environment.lifecycleState === "DEPRECATED"
                        ? <Badge tone="neutral">retired</Badge>
                        : <Badge tone="warn">not declared</Badge>}
                  </TableCell>
                  <TableCell className="text-right">
                    {!data.mayManage ? null : !environment.declared ? (
                      <Button size="sm" variant="outline"
                              onClick={() => { setCreating(environment.code); setEditing(null); }}>
                        Declare
                      </Button>
                    ) : (
                      <div className="flex items-center justify-end gap-1">
                        <Button size="sm" variant="ghost" aria-label={`Move ${environment.code} up`}
                                disabled={index === 0} onClick={() => move(environment, -1)}>
                          <ChevronUp className="size-3" />
                        </Button>
                        <Button size="sm" variant="ghost" aria-label={`Move ${environment.code} down`}
                                disabled={index === data.environments.length - 1}
                                onClick={() => move(environment, 1)}>
                          <ChevronDown className="size-3" />
                        </Button>
                        <Button size="sm" variant="ghost"
                                onClick={() => setEditing(
                                  editing === environment.code ? null : environment.code)}>
                          {editing === environment.code ? "Close" : "Edit"}
                        </Button>
                        {environment.lifecycleState === "ACTIVE" ? (
                          <Button size="sm" variant="ghost"
                                  onClick={() => lifecycle(environment, false)}>
                            Retire
                          </Button>
                        ) : (
                          <Button size="sm" variant="ghost"
                                  onClick={() => lifecycle(environment, true)}>
                            <RotateCcw className="size-3" /> Restore
                          </Button>
                        )}
                      </div>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </Card>

      {editing && (() => {
        const environment = data.environments.find((e) => e.code === editing);
        return environment ? (
          <EnvironmentForm environment={environment} onSaved={done}
                           onCancel={() => setEditing(null)} />
        ) : null;
      })()}
    </div>
  );
}

/**
 * Declare an environment, or amend one.
 *
 * **The code is not editable on an existing row.** It is the value recorded on every edge published
 * in this environment; changing it would orphan all of them in one statement and nothing afterwards
 * would say so. A tenant needing a different code declares one and retires this one, which keeps the
 * recorded edges explicable.
 */
function EnvironmentForm({ environment, code = "", onSaved, onCancel }: {
  environment?: Environment;
  code?: string;
  onSaved: (message: string) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState({
    code: environment?.code ?? code,
    label: environment?.label ?? code,
    labelVi: environment?.labelVi ?? "",
    purpose: environment?.purpose ?? "",
  });
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<{ field?: string; message: string } | null>(null);

  async function submit() {
    setBusy(true);
    setProblem(null);
    try {
      if (environment?.id) {
        await api.post(`/api/ui/settings/environments/${environment.id}`, {
          label: form.label, labelVi: form.labelVi, purpose: form.purpose,
          rowVersion: environment.rowVersion,
        });
        onSaved(`${form.label} updated.`);
      } else {
        await api.post("/api/ui/settings/environments", {
          code: form.code, label: form.label, labelVi: form.labelVi, purpose: form.purpose,
        });
        onSaved(`${form.label} is now offered on both inventory editors.`);
      }
    } catch (e) {
      const err = e as ApiError;
      setProblem({ field: err.field ?? undefined, message: err.message });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm">
          {environment ? `Amend ${environment.code}` : "Declare an environment"}
        </CardTitle>
        <CardDescription>
          {environment
            ? "The code cannot change — every endpoint published in this environment is recorded "
              + "under it."
            : "The code is matched against the environment recorded on an endpoint, so it is upper "
              + "case and holds no spaces or dots."}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="grid gap-4 md:grid-cols-3">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="env-code">Code</Label>
            <Input id="env-code" value={form.code} disabled={!!environment}
                   placeholder="UAT"
                   onChange={(e) => setForm((f) => ({ ...f, code: e.target.value.toUpperCase() }))} />
            {problem?.field === "code" && (
              <span className="text-[11px] leading-tight text-destructive">{problem.message}</span>
            )}
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="env-label">Label (English)</Label>
            <Input id="env-label" value={form.label}
                   onChange={(e) => setForm((f) => ({ ...f, label: e.target.value }))} />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="env-label-vi">Label (Tiếng Việt)</Label>
            <Input id="env-label-vi" value={form.labelVi}
                   onChange={(e) => setForm((f) => ({ ...f, labelVi: e.target.value }))} />
            <span className="text-[11px] leading-tight text-muted-foreground">
              Optional. Left blank, the English label is shown in both locales — which is better than
              a blank label.
            </span>
          </div>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="env-purpose">What it is for</Label>
          <Input id="env-purpose" value={form.purpose}
                 onChange={(e) => setForm((f) => ({ ...f, purpose: e.target.value }))}
                 placeholder="Acceptance testing. Often holds production data behind weaker controls." />
          <span className="text-[11px] leading-tight text-muted-foreground">
            Shown beside the field on both editors and as the column's description. An environment
            whose purpose nobody can state is one people record inconsistently and then filter on.
          </span>
        </div>
        {problem && problem.field !== "code" && (
          <p className="text-xs text-destructive">{problem.message}</p>
        )}
        <div className="flex items-center gap-2">
          <Button size="sm" onClick={submit} disabled={busy || !form.code.trim()}>
            {busy && <Loader2 className="size-3 animate-spin" />}
            {environment ? "Save" : "Declare"}
          </Button>
          <Button size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>
        </div>
      </CardContent>
    </Card>
  );
}
