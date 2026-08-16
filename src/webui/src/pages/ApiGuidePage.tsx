import { useEffect, useMemo, useState } from "react";
import { Search } from "lucide-react";
import { api } from "@/lib/api";
import { Prose } from "@/components/Prose";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PageSize, Pager, usePaging } from "@/components/Paging";

const ANY = "__any__";

interface Operation {
  method: string;
  path: string;
  surface: "v1" | "interface";
  annotationClass: string;
  permission: string | null;
  authentication: string;
  scopeRevalidation: string;
  requiresStepUp: boolean;
  requiresIdempotencyKey: boolean;
  invokableByHumanSession: boolean;
  classification: string;
  rateClass: string;
  /** Generated from the same declarations the request validator enforces — never hand-written. */
  filterable?: string[];
  restricted?: string[];
  writableOnCreate?: string[];
  writableOnUpdate?: string[];
}
interface Payload { html: string; operations: Operation[]; locale: string }

/** The body an operation reads, which depends on the method: a GET reads none. */
function body(o: Operation): string[] {
  if (o.method === "POST") {
    return o.writableOnCreate ?? [];
  }
  if (o.method === "PATCH" || o.method === "PUT") {
    return o.writableOnUpdate ?? [];
  }
  return [];
}

/** One line of field names, or nothing at all where the set is empty. */
function FieldList({ label, fields }: { label: string; fields?: string[] }) {
  if (!fields || fields.length === 0) {
    return null;
  }
  return (
    <div className="mb-0.5">
      <span className="text-muted-foreground">{label}: </span>
      <span className="font-mono">{fields.join(", ")}</span>
    </div>
  );
}

/**
 * The integration guide: prose that had to be written, and a table that must not be.
 *
 * The operation list comes from the platform's own registry — the one the dispatcher enforces and
 * refuses to start without. So it cannot document an endpoint that does not exist, cannot omit one
 * that does, and cannot state a permission or an annotation class that differs from the enforced one.
 * Hand-written API documentation is wrong the first time somebody adds an operation, and stays wrong
 * because nothing disagrees with it.
 *
 * What each class REQUIRES is read off the class rather than restated here for the same reason.
 */
export function ApiGuidePage() {
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hunt, setHunt] = useState("");
  const [surface, setSurface] = useState("v1");
  const [klass, setKlass] = useState(ANY);

  useEffect(() => {
    let live = true;
    api.get<Payload>("/api/ui/api-guide")
      .then((d) => live && setData(d))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, []);

  const classes = useMemo(
    () => [...new Set((data?.operations ?? []).map((o) => o.annotationClass))].sort(),
    [data]);

  const shown = useMemo(() => {
    const needle = hunt.trim().toLowerCase();
    return (data?.operations ?? []).filter((o) =>
      (surface === ANY || o.surface === surface)
      && (klass === ANY || o.annotationClass === klass)
      && (needle === "" || o.path.toLowerCase().includes(needle)
          || (o.permission ?? "").toLowerCase().includes(needle)));
  }, [data, hunt, surface, klass]);

  const paging = usePaging(shown, 25);

  if (error) {
    return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  }
  if (!data) {
    return <div className="text-sm text-muted-foreground">Loading…</div>;
  }

  const v1 = data.operations.filter((o) => o.surface === "v1").length;

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold tracking-tight">API guide</h1>
        <p className="text-xs text-muted-foreground">
          How to call the platform from a pipeline or another system. The table below is generated from
          the platform's own operation registry — {v1} operations on the machine door — so it cannot
          drift from what is enforced.
        </p>
      </div>

      <Card>
        <CardContent className="py-4">
          {data.html
            ? <Prose html={data.html} />
            : <p className="text-sm text-tone-warn">
                The API guide could not be loaded. This is a deployment problem, not an empty guide.
              </p>}
        </CardContent>
      </Card>

      <Card className="overflow-hidden">
        <CardHeader>
          <CardTitle className="text-sm">Every operation, as the platform enforces it</CardTitle>
          <CardDescription>
            Generated from the registry. A blank permission means the operation requires none — it is
            gated by identity or by the session's factor state inside the handler, not by a catalogue
            code.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="flex min-w-56 flex-1 flex-col gap-1">
            <Label htmlFor="api-search">Find a path or a permission</Label>
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
              <Input id="api-search" value={hunt} className="pl-8"
                     placeholder="findings, sbom, ing.findings.import…"
                     onChange={(e) => setHunt(e.target.value)} />
            </div>
          </div>
          <div className="flex w-56 flex-col gap-1">
            <Label>Door</Label>
            {/* Defaulting to v1, because that is the surface somebody integrates against. The
                interface's own operations are here to be recognised in a network tab, not built on. */}
            <Select value={surface} onValueChange={setSurface}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="v1">/api/v1 — for machines</SelectItem>
                <SelectItem value="interface">/api/ui — this interface's own</SelectItem>
                <SelectItem value={ANY}>Both</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="flex w-44 flex-col gap-1">
            <Label>Class</Label>
            <Select value={klass} onValueChange={setKlass}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={ANY}>Any class</SelectItem>
                {classes.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <PageSize paging={paging} />
        </CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-20">Method</TableHead>
              <TableHead>Path</TableHead>
              <TableHead>Permission</TableHead>
              <TableHead>Class</TableHead>
              <TableHead>Requires</TableHead>
              <TableHead>Fields</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {paging.rows.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} className="py-8 text-center text-sm text-muted-foreground">
                  No operation matches that.
                </TableCell>
              </TableRow>
            )}
            {paging.rows.map((o) => (
              <TableRow key={o.method + o.path}>
                <TableCell className="font-mono text-[11px]">{o.method}</TableCell>
                <TableCell className="font-mono text-[11px]">{o.path}</TableCell>
                <TableCell className="font-mono text-[11px]">
                  {o.permission ?? <span className="italic text-tone-unknown">none</span>}
                </TableCell>
                <TableCell className="text-[11px]">{o.annotationClass.replace(/_/g, " ")}</TableCell>
                <TableCell className="flex flex-wrap gap-1 text-[11px]">
                  {/* Read off the class, never restated. A second copy of what a class requires is a
                      copy that disagrees with the dispatcher the first time one of them changes. */}
                  {!o.invokableByHumanSession && <Badge>signed credential only</Badge>}
                  {o.requiresStepUp && <Badge tone="warn">step-up</Badge>}
                  {o.requiresIdempotencyKey && <Badge>idempotency key</Badge>}
                  {o.scopeRevalidation !== "NOT_APPLICABLE" && (
                    <Badge>{o.scopeRevalidation === "PATH_AND_BODY_IDENTIFIERS"
                      ? "scope re-checked on path and body" : "scope re-checked on path"}</Badge>
                  )}
                  {o.classification === "RESTRICTED" && <Badge tone="critical">restricted</Badge>}
                  <Badge>{o.rateClass.toLowerCase()}</Badge>
                </TableCell>
                <TableCell className="text-[11px]">
                  {/* What to put in the body, and what may be filtered on. Without this the table
                      says a caller MAY post to an endpoint and not what to post, which is where a
                      newcomer stops reading and starts guessing.

                      Shown per METHOD, because the writable sets belong to the resource and a GET
                      row listing "body on create" describes a body that operation does not read. */}
                  {o.method === "POST" && <FieldList label="body" fields={o.writableOnCreate} />}
                  {(o.method === "PATCH" || o.method === "PUT")
                    && <FieldList label="body" fields={o.writableOnUpdate} />}
                  <FieldList label="filter by" fields={o.filterable} />
                  <FieldList label="never returned" fields={o.restricted} />
                  {!body(o).length && !o.filterable?.length && !o.restricted?.length && (
                    <span className="italic text-tone-unknown">see the prose above</span>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <Pager paging={paging} unit="operations" />
      </Card>
    </div>
  );
}
