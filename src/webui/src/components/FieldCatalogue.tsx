import { useCallback, useEffect, useState } from "react";
import { ChevronDown, ChevronUp, Loader2, Plus, RotateCcw, X } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { humanise } from "@/components/AttributeFields";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

interface Field {
  id: string; key: string; label: string; labelVi: string; dataType: string;
  permittedValues: string[]; filterable: boolean; required: boolean;
  purpose: string | null; ordinal: number; lifecycleState: string; rowVersion: number;
}
interface Payload {
  types: { code: string; ordinal: number; fieldCount: number }[];
  selectedType: string;
  dataTypes: string[];
  fields: Field[];
  /** Per select field, the values assets are actually holding. Drives the removal guard. */
  valuesInUse: Record<string, string[]>;
  mayManage: boolean;
}

const SELECT_TYPES = ["SINGLE_SELECT", "MULTI_SELECT"];

/**
 * The declared-field catalogue: what the platform asks about each kind of asset.
 *
 * **This screen is the reason nothing else needed changing.** A field added here appears in the
 * relevant editor, in the column picker on the inventory tables, and — if it is filterable — as a
 * filter, with no release. That is ADR-027 in practice: the product supplies the storage kinds, the
 * widgets and the validators; the tenant supplies the questions.
 *
 * Two things are refused rather than offered, and both are refusals about data that already exists:
 * the key and the type of a declared field cannot change, because every recorded value is stored
 * under that key in that shape; and a permitted value cannot be removed while assets still hold it,
 * because removal would not remove it from them — it would leave a value nobody declared, filterable
 * by nothing and explicable by no one.
 */
export function FieldCatalogue() {
  const [type, setType] = useState<string | null>(null);
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Payload>(`/api/ui/settings/fields${type ? `?type=${encodeURIComponent(type)}` : ""}`)
      .then((d) => { setData(d); setType(d.selectedType || null); })
      .catch((e) => setError(e.message));
  }, [type]);
  useEffect(load, [load]);

  function done(message: string) {
    setCreating(false);
    setEditing(null);
    setNotice(message);
    load();
  }

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>;

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Declared fields</CardTitle>
          <CardDescription>
            What this platform asks about each kind of asset. Tenant configuration, not product code
            (ADR-027) — a field added here appears in the editor, in the column pickers on the
            inventory tables, and as a filter if you mark it filterable. No release, no migration.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="flex w-64 flex-col gap-1.5">
            <Label>Asset type</Label>
            <Select value={data.selectedType} onValueChange={(v) => { setType(v); setEditing(null); }}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                {data.types.map((t) => (
                  <SelectItem key={t.code} value={t.code}>
                    {t.code}
                    <span className="pl-1.5 text-muted-foreground">({t.fieldCount})</span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {data.mayManage ? (
            <Button variant={creating ? "ghost" : "outline"} size="sm"
                    onClick={() => { setCreating(!creating); setEditing(null); }}>
              {creating ? <><X className="size-3" /> Cancel</>
                        : <><Plus className="size-3" /> Declare a field</>}
            </Button>
          ) : (
            // Said, not hidden. Somebody who cannot find the button assumes the feature is missing;
            // somebody who is told they lack the permission knows who to ask.
            <p className="text-xs text-muted-foreground">
              Read-only — declaring fields needs <code>cfg.asset.field.manage</code>.
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

      {creating && (
        <FieldForm assetType={data.selectedType} dataTypes={data.dataTypes}
                   onSaved={() => done("Field declared.")} onCancel={() => setCreating(false)} />
      )}

      <Card className="overflow-hidden">
        {data.fields.length === 0 ? (
          <CardContent className="py-8 text-center text-sm text-muted-foreground">
            No field is declared on {data.selectedType || "this type"} yet.
          </CardContent>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Field</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Values</TableHead>
                  <TableHead>Flags</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.fields.map((field, index) => (
                  editing === field.id ? (
                    <TableRow key={field.id}>
                      <TableCell colSpan={6} className="bg-muted/30 p-0">
                        <FieldForm assetType={data.selectedType} dataTypes={data.dataTypes}
                                   existing={field}
                                   inUse={data.valuesInUse[field.key] ?? []}
                                   onSaved={() => done(`Saved ${field.label}.`)}
                                   onCancel={() => setEditing(null)} />
                      </TableCell>
                    </TableRow>
                  ) : (
                    <TableRow key={field.id}
                              className={field.lifecycleState === "ACTIVE" ? "" : "opacity-60"}>
                      <TableCell>
                        <div className="text-sm font-medium">{field.label}</div>
                        <code className="text-[11px] text-muted-foreground">{field.key}</code>
                        {field.purpose && (
                          <div className="max-w-md pt-0.5 text-[11px] leading-tight text-muted-foreground">
                            {field.purpose}
                          </div>
                        )}
                      </TableCell>
                      <TableCell><Badge>{humanise(field.dataType)}</Badge></TableCell>
                      <TableCell className="max-w-xs">
                        {field.permittedValues.length === 0
                          ? <span className="text-xs text-muted-foreground">—</span>
                          : (
                            <span className="flex flex-wrap gap-1">
                              {field.permittedValues.map((v) => (
                                <Badge key={v}
                                       tone={(data.valuesInUse[field.key] ?? []).includes(v)
                                         ? "info" : "neutral"}>
                                  {humanise(v)}
                                </Badge>
                              ))}
                            </span>
                          )}
                      </TableCell>
                      <TableCell className="text-[11px] text-muted-foreground">
                        {field.filterable && <div>filterable</div>}
                        {field.required && <div>required</div>}
                      </TableCell>
                      <TableCell>
                        <Badge tone={field.lifecycleState === "ACTIVE" ? "ok" : "neutral"}>
                          {field.lifecycleState.toLowerCase()}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-right whitespace-nowrap">
                        {data.mayManage && (
                          <>
                            <Button size="sm" variant="ghost" aria-label="Move up"
                                    disabled={index === 0}
                                    onClick={() => api.post(
                                      `/api/ui/settings/fields/${field.id}/move`, { delta: -1 })
                                      .then(load)}>
                              <ChevronUp className="size-3" />
                            </Button>
                            <Button size="sm" variant="ghost" aria-label="Move down"
                                    disabled={index === data.fields.length - 1}
                                    onClick={() => api.post(
                                      `/api/ui/settings/fields/${field.id}/move`, { delta: 1 })
                                      .then(load)}>
                              <ChevronDown className="size-3" />
                            </Button>
                            <Button size="sm" variant="ghost" onClick={() => {
                              setEditing(field.id); setCreating(false); setNotice(null);
                            }}>Edit</Button>
                            <Button size="sm"
                                    variant={field.lifecycleState === "ACTIVE" ? "outline" : "ghost"}
                                    onClick={() => api.post(
                                      `/api/ui/settings/fields/${field.id}/lifecycle`,
                                      { active: field.lifecycleState !== "ACTIVE",
                                        rowVersion: field.rowVersion })
                                      .then(() => done(field.lifecycleState === "ACTIVE"
                                        ? `${field.label} retired. Recorded values are untouched.`
                                        : `${field.label} restored.`))
                                      .catch((e) => setNotice((e as ApiError).message))}>
                              {field.lifecycleState === "ACTIVE"
                                ? "Retire"
                                : <><RotateCcw className="size-3" /> Restore</>}
                            </Button>
                          </>
                        )}
                      </TableCell>
                    </TableRow>
                  )
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Card>

      <p className="text-[11px] leading-relaxed text-muted-foreground">
        <strong>Retiring never deletes.</strong> Values already recorded stay exactly where they are;
        the field disappears from forms and column pickers, and restoring it brings every one of them
        back into view. <strong>The key and the type cannot change</strong> — both are how existing
        values are stored, and changing either would orphan every one of them in a single statement.
        Declare a new field and retire the old one instead; the old values stay readable.
      </p>
    </div>
  );
}

/** Declare a field, or amend one within the bounds that keep its recorded values meaningful. */
function FieldForm({ assetType, dataTypes, existing, inUse = [], onSaved, onCancel }: {
  assetType: string;
  dataTypes: string[];
  existing?: Field;
  inUse?: string[];
  onSaved: () => void;
  onCancel: () => void;
}) {
  const [key, setKey] = useState(existing?.key ?? "");
  const [label, setLabel] = useState(existing?.label ?? "");
  const [labelVi, setLabelVi] = useState(existing?.labelVi ?? "");
  const [dataType, setDataType] = useState(existing?.dataType ?? "TEXT");
  const [values, setValues] = useState<string[]>(existing?.permittedValues ?? []);
  const [draft, setDraft] = useState("");
  const [filterable, setFilterable] = useState(existing?.filterable ?? true);
  const [required, setRequired] = useState(existing?.required ?? false);
  const [purpose, setPurpose] = useState(existing?.purpose ?? "");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const isSelect = SELECT_TYPES.includes(dataType);

  function addValue() {
    // Upper snake case, like every other permitted value in the catalogue. The interface lowercases
    // them for display, so consistency here costs the author nothing and buys a stored vocabulary
    // that reads the same whichever tenant wrote it.
    const next = draft.trim().toUpperCase().replace(/[^A-Z0-9]+/g, "_").replace(/^_|_$/g, "");
    if (next && !values.includes(next)) setValues([...values, next]);
    setDraft("");
  }

  async function save() {
    setBusy(true);
    setProblem(null);
    try {
      const body = {
        assetType, key, label, labelVi, dataType,
        permittedValues: isSelect ? values : [],
        filterable, required, purpose,
        ...(existing ? { rowVersion: existing.rowVersion } : {}),
      };
      await api.post(existing ? `/api/ui/settings/fields/${existing.id}`
                              : "/api/ui/settings/fields", body);
      onSaved();
    } catch (e) {
      setProblem((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className={existing ? "border-0 shadow-none" : ""}>
      <CardHeader>
        <CardTitle>{existing ? `Edit ${existing.label}` : "Declare a field"}</CardTitle>
        <CardDescription>
          On <strong>{assetType}</strong>. Every record of this type gains it — including the ones
          already there, which will show it as not recorded until somebody fills it in.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {problem && <p className="text-sm text-destructive">{problem}</p>}
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          <div className="flex flex-col gap-1.5">
            <Label>Key<span className="pl-0.5 text-destructive">*</span></Label>
            <Input value={key} disabled={!!existing} placeholder="waf"
                   onChange={(e) => setKey(e.target.value)} />
            <p className="text-[11px] leading-tight text-muted-foreground">
              {existing
                ? "Fixed. Every recorded value is stored under this key."
                : "Lower case, digits and underscores. It is the JSON key and the query parameter."}
            </p>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Label (English)<span className="pl-0.5 text-destructive">*</span></Label>
            <Input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="WAF" />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Nhãn (Tiếng Việt)</Label>
            <Input value={labelVi} onChange={(e) => setLabelVi(e.target.value)} placeholder="WAF" />
            <p className="text-[11px] leading-tight text-muted-foreground">
              Optional. Left blank, the English label is used in every locale rather than a gap.
            </p>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Type<span className="pl-0.5 text-destructive">*</span></Label>
            <Select value={dataType} onValueChange={setDataType} disabled={!!existing}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                {dataTypes.map((t) => (
                  <SelectItem key={t} value={t}>{humanise(t)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            {existing && (
              <p className="text-[11px] leading-tight text-muted-foreground">
                Fixed. A single choice and a multiple choice store different documents.
              </p>
            )}
          </div>
        </div>

        {isSelect && (
          <div className="flex flex-col gap-1.5">
            <Label>Dropdown values<span className="pl-0.5 text-destructive">*</span></Label>
            <div className="flex flex-wrap gap-1.5 rounded-md border p-3">
              {values.length === 0 && (
                <span className="text-xs text-muted-foreground">
                  No values yet — a dropdown with no options is a field nobody can complete.
                </span>
              )}
              {values.map((v) => {
                const held = inUse.includes(v);
                return (
                  <Badge key={v} tone={held ? "info" : "neutral"}>
                    {humanise(v)}
                    {/* Removal is refused while assets hold the value — at the server too, not only
                        here. Taking it off the list does not take it off the records. */}
                    {held ? (
                      <span className="pl-1 text-[10px]" title="Recorded on assets — cannot be removed">
                        in use
                      </span>
                    ) : (
                      <button type="button" className="pl-1" aria-label={`Remove ${v}`}
                              onClick={() => setValues(values.filter((x) => x !== v))}>×</button>
                    )}
                  </Badge>
                );
              })}
            </div>
            <div className="flex gap-2">
              <Input className="w-64" value={draft} placeholder="CLOUDFLARE"
                     onChange={(e) => setDraft(e.target.value)}
                     onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addValue(); } }} />
              <Button size="sm" variant="outline" onClick={addValue} disabled={!draft.trim()}>
                Add value
              </Button>
            </div>
          </div>
        )}

        <div className="flex flex-col gap-1.5">
          <Label>Why this field exists</Label>
          <Input value={purpose} onChange={(e) => setPurpose(e.target.value)}
                 placeholder="Whether a mitigation exists while the real fix is built." />
          <p className="text-[11px] leading-tight text-muted-foreground">
            Shown under the field wherever it is edited. A field whose purpose nobody can state is a
            field people fill in wrongly and then filter on, which is worse than leaving it empty.
          </p>
        </div>

        <div className="flex flex-wrap gap-5">
          <label className="flex items-center gap-2 text-sm">
            <Checkbox checked={filterable} onCheckedChange={(v) => setFilterable(v === true)} />
            Offer as a filter and a column
          </label>
          <label className="flex items-center gap-2 text-sm">
            <Checkbox checked={required} onCheckedChange={(v) => setRequired(v === true)} />
            Required
          </label>
        </div>
        {required && (
          <p className="text-[11px] leading-tight text-tone-warn">
            Every existing record is already saved without this. Marking it required does not
            invalidate them — it stops the next person saving one of them without an answer.
          </p>
        )}

        <div className="flex gap-2">
          <Button size="sm" onClick={save}
                  disabled={busy || !key.trim() || !label.trim() || (isSelect && values.length === 0)}>
            {busy && <Loader2 className="size-3 animate-spin" />} {existing ? "Save" : "Declare"}
          </Button>
          <Button size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>
        </div>
      </CardContent>
    </Card>
  );
}
