import { useCallback, useEffect, useState } from "react";
import { AlertTriangle, Bot, Lock, Plus, X } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

interface Row {
  id: string; label: string; providerKind: string; baseUrl: string | null; model: string;
  keyFingerprint: string | null; sendRecordContent: boolean; active: boolean; sealed: boolean;
  keyReference: string | null; lastTestedAt: string | null; lastTestStatus: string | null;
  lastTestDetail: string | null; updatedAt: string | null;
}

/**
 * Where a tenant names the model it wants used, and hands over the credential to reach it.
 *
 * <h2>This configures nothing that runs yet, on purpose</h2>
 *
 * ADR-044 defers AI capability from v1, and PRD-AIC-056 forbids invoking a capability on view. So
 * saving a provider here starts nothing: it establishes that when an analysis agent arrives, the
 * provider is *tenant configuration* rather than one environment variable shared by every tenant on
 * the deployment. That second shape is the one that cannot be undone once real keys are in it.
 *
 * <h2>The key goes in and does not come back</h2>
 *
 * It is sealed with AES-256-GCM before storage and is never returned by any endpoint — absent, not
 * masked (ADR-047). There is also no "show it again", and unlike an issued ingestion credential there
 * is no show-it-once either: the platform did not generate this key, the person pasting it already
 * has it, and echoing it into a response body would put a live third-party credential into a browser
 * cache and whatever logs responses. The fingerprint is a digest prefix — never characters of the key
 * — which is enough to tell one configuration from another.
 *
 * <h2>The switch that matters most is off by default</h2>
 *
 * "May read record content" decides whether finding descriptions, evidence and secrets recovered from
 * customer code may be sent to this provider, or only counts and aggregates. Finding content
 * legitimately contains attacker-authored text, so turning it on is a decision to egress the group's
 * exploitable attack surface to a third party. It is presented as its own choice, with the
 * consequence written next to it, because defaulting it on would make that decision for somebody who
 * only wanted better prose.
 */
export function AiProviders() {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [mayManage, setMayManage] = useState(false);
  const [custody, setCustody] = useState(true);
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; field?: string } | null>(null);

  const [label, setLabel] = useState("");
  const [kind, setKind] = useState("");
  const [model, setModel] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [sendContent, setSendContent] = useState(false);

  const load = useCallback(() => {
    api.get<{ rows: Row[]; mayManage: boolean; custodyAvailable: boolean }>("/api/ui/ai-providers")
      .then((d) => { setRows(d.rows); setMayManage(d.mayManage); setCustody(d.custodyAvailable); })
      .catch(() => setRows([]));
  }, []);
  useEffect(load, [load]);

  function reset() {
    setAdding(false); setLabel(""); setKind(""); setModel(""); setBaseUrl("");
    setApiKey(""); setSendContent(false); setError(null);
  }

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await api.post("/api/ui/ai-providers", {
        label, providerKind: kind, model,
        baseUrl: baseUrl.trim() || null,
        apiKey,
        sendRecordContent: sendContent,
      });
      reset();
      load();
    } catch (e) {
      setError(e instanceof ApiError
        ? { message: e.message, field: e.field ?? undefined }
        : { message: "The provider could not be saved." });
    } finally {
      setBusy(false);
    }
  }

  async function toggle(row: Row) {
    setBusy(true);
    try {
      await api.post(`/api/ui/ai-providers/${row.id}/active`, { active: !row.active });
      load();
    } catch (e) {
      setError({ message: e instanceof ApiError ? e.message : "That could not be changed." });
    } finally {
      setBusy(false);
    }
  }

  if (!rows) return null;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2">
          <Bot className="size-4 text-primary" /> AI model providers
        </CardTitle>
        <CardDescription>
          Which model each dashboard's analysis will use, and the credential to reach it. Saving a
          provider starts nothing — no capability is invoked until one is explicitly requested, and
          every figure an agent writes will still come from a query, never from the model.
          {!mayManage && (
            <span className="mt-1 flex items-center gap-1.5 text-tone-unknown">
              <Lock className="size-3" /> You can see what is configured but not change it.
            </span>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {!custody && (
          <p className="flex items-start gap-1.5 rounded border border-sev-high/40 bg-sev-high/10 p-2
                        text-xs text-sev-high">
            <AlertTriangle className="mt-0.5 size-3.5 shrink-0" />
            This deployment has no credential key configured, so it cannot hold a provider key. Set
            <code className="mx-1 font-mono">ASPM_CREDENTIAL_KEY</code> first — a key stored in
            plaintext is not offered as a fallback.
          </p>
        )}
        {error && (
          <p className="text-xs text-sev-critical">
            {error.message}{error.field ? ` (${error.field})` : ""}
          </p>
        )}

        {rows.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Configuration</TableHead>
                <TableHead>Model</TableHead>
                <TableHead>Endpoint</TableHead>
                <TableHead>Key</TableHead>
                <TableHead>Record content</TableHead>
                <TableHead>State</TableHead>
                {mayManage && <TableHead className="w-24" />}
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.id}>
                  <TableCell>
                    <div className="font-medium">{row.label}</div>
                    <div className="text-[11px] text-muted-foreground">{row.providerKind}</div>
                  </TableCell>
                  <TableCell className="font-mono text-[11px]">{row.model}</TableCell>
                  <TableCell className="max-w-56 truncate font-mono text-[11px]">
                    {row.baseUrl ?? (
                      <span className="italic text-tone-unknown">provider default</span>
                    )}
                  </TableCell>
                  <TableCell>
                    {row.keyReference
                      ? <Badge tone="info">external ref</Badge>
                      : row.sealed
                        ? <span className="font-mono text-[11px] text-muted-foreground">
                            sealed · {row.keyFingerprint}
                          </span>
                        : <Badge tone="critical">missing</Badge>}
                  </TableCell>
                  <TableCell>
                    {/* Written out, both ways. "May read record content" is the egress decision, and
                        a blank cell for the safe case would make the dangerous one easy to miss. */}
                    {row.sendRecordContent
                      ? <Badge tone="warn">may be sent</Badge>
                      : <Badge tone="ok">aggregates only</Badge>}
                  </TableCell>
                  <TableCell>
                    {row.active ? <Badge tone="ok">active</Badge> : <Badge tone="neutral">off</Badge>}
                  </TableCell>
                  {mayManage && (
                    <TableCell>
                      <Button size="sm" variant="ghost" disabled={busy} onClick={() => void toggle(row)}>
                        {row.active ? "Turn off" : "Turn on"}
                      </Button>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}

        {rows.length === 0 && (
          <p className="text-xs italic text-tone-unknown">
            No provider configured. Dashboard analysis will fall back to the deterministic rules it
            uses today, which is a working state rather than a broken one.
          </p>
        )}

        {mayManage && !adding && (
          <div>
            <Button size="sm" variant="secondary" disabled={!custody} onClick={() => setAdding(true)}>
              <Plus className="size-3.5" /> Add a provider
            </Button>
          </div>
        )}

        {mayManage && adding && (
          <div className="flex flex-col gap-3 rounded border border-border p-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="flex flex-col gap-1">
                <Label htmlFor="ai-label">Name</Label>
                <Input id="ai-label" value={label} placeholder="Analysis — primary"
                       onChange={(e) => setLabel(e.target.value)} />
              </div>
              <div className="flex flex-col gap-1">
                <Label htmlFor="ai-kind">Provider</Label>
                {/* Free text, not a dropdown of today's vendors. ADR-027 forbids a fixed enumeration
                    for a tenant-configurable surface, and OQ-027's assumption is explicit that a
                    tenant may point at its own inference server — the case a vendor list excludes. */}
                <Input id="ai-kind" value={kind} placeholder="anthropic · openai · self-hosted"
                       onChange={(e) => setKind(e.target.value)} />
              </div>
              <div className="flex flex-col gap-1">
                <Label htmlFor="ai-model">Model</Label>
                <Input id="ai-model" value={model} placeholder="claude-sonnet-5"
                       onChange={(e) => setModel(e.target.value)} />
              </div>
              <div className="flex flex-col gap-1">
                <Label htmlFor="ai-url">Endpoint <span className="text-muted-foreground">(optional)</span></Label>
                <Input id="ai-url" value={baseUrl} placeholder="https://your-inference-host/v1"
                       onChange={(e) => setBaseUrl(e.target.value)} />
                <span className="text-[10px] text-muted-foreground">
                  Leave empty for the provider's own endpoint. Must be https and outside private
                  address ranges — the same guard the alert destinations use.
                </span>
              </div>
            </div>

            <div className="flex flex-col gap-1">
              <Label htmlFor="ai-key">API key</Label>
              <Input id="ai-key" type="password" autoComplete="off" value={apiKey}
                     placeholder="pasted once; sealed on save"
                     onChange={(e) => setApiKey(e.target.value)} />
              <span className="text-[10px] text-muted-foreground">
                Sealed with AES-256-GCM and never returned by any endpoint. There is no “show again” —
                keep your own copy.
              </span>
            </div>

            <label className="flex cursor-pointer items-start gap-2 rounded border border-sev-high/30
                              bg-sev-high/5 p-2">
              <input type="checkbox" className="mt-0.5" checked={sendContent}
                     onChange={(e) => setSendContent(e.target.checked)} />
              <span className="text-xs">
                <span className="font-medium">Let this provider read record content</span>
                <span className="block text-[11px] text-muted-foreground">
                  Off means only counts and aggregates are sent. On means finding descriptions,
                  evidence and secrets recovered from customer code may leave the platform for this
                  provider to read. Leave it off unless the provider is one the group would trust with
                  its own attack surface.
                </span>
              </span>
            </label>

            <div className="flex gap-2">
              <Button size="sm" disabled={busy || !label || !kind || !model || !apiKey}
                      onClick={() => void save()}>Save provider</Button>
              <Button size="sm" variant="ghost" disabled={busy} onClick={reset}>
                <X className="size-3.5" /> Cancel
              </Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
