import { useCallback, useEffect, useState } from "react";
import { Activity, FlaskConical } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * What the AI has been doing, what it may cost, and whether it passes its own harness.
 *
 * **Egress report (PRD-AIC-044).** Which data categories left, to which provider, over the period —
 * from the invocation records, not from a log.
 *
 * **Budget (PRD-AIC-053/054).** A daily token ceiling and a per-person hourly call limit. On exhaustion a
 * capability says it is unavailable; nothing switches to a cheaper model.
 *
 * **Evaluation (PRD-AIC-049/050).** The DOC-10 §10.2 measures over fixed scenarios, run against the
 * active provider on request and recorded against the prompt versions.
 */

interface Budget { dailyTokenBudget: number; perPrincipalHourlyInvocations: number; cacheMinutes: number; retainPrompts: boolean; retainOutputs: boolean }
interface Egress { provider: string; dataCategory: string; calls: number; cached: number; refused: number; tokens: number; injectionSignals: number; lastAt: string | null }
interface Recent { id: string; capability: string; by: string; modelIdentity: string; promptVersion: string; dataCategory: string; outcome: string; refusalCode: string | null; promptTokens: number; completionTokens: number; latencyMs: number; cached: boolean; injectionSignals: number; at: string }
interface Usage { budget: Budget; tokensUsedToday: number; permittedNow: boolean; reason: string; egress: Egress[]; recent: Recent[]; vouchedEndpoints: string[]; mayManage: boolean; days: number }
interface Measure { pass: number; total: number; threshold: number; gate: boolean; percent: number }
interface Run { id: string; modelIdentity: string; promptVersion: string; startedAt: string; finishedAt: string | null; scenarios: number; measures: Record<string, Measure>; failures: { scenario: string; measure: string; why: string; output: string | null }[]; gate: string }

export function AiUsage() {
  const [usage, setUsage] = useState<Usage | null>(null);
  const [runs, setRuns] = useState<Run[]>([]);
  const [budget, setBudget] = useState<Budget | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Usage>("/api/ui/ai/usage?days=30").then((u) => { setUsage(u); setBudget(u.budget); }).catch(() => setUsage(null));
    api.get<{ rows: Run[] }>("/api/ui/ai/evaluations").then((d) => setRuns(d.rows)).catch(() => setRuns([]));
  }, []);
  useEffect(load, [load]);

  async function saveBudget() {
    if (!budget) return;
    setBusy(true); setError(null);
    try { await api.post("/api/ui/ai/budget", budget); load(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  async function evaluate() {
    setBusy(true); setError(null);
    try { const r = await api.post<Run>("/api/ui/ai/evaluate", {}); setOpen(r.id); load(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  if (!usage) return null;

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center gap-2"><Activity className="size-4 text-primary" /> AI usage, egress and budget</CardTitle>
          <CardDescription>
            Every model call is a record: which capability, who, which provider, what category of data left, what it cost.
            {" "}Today: <span className="font-mono">{usage.tokensUsedToday.toLocaleString()}</span> of <span className="font-mono">{usage.budget.dailyTokenBudget.toLocaleString()}</span> tokens.
            {!usage.permittedNow && <span className="text-destructive"> Unavailable now: {usage.reason}</span>}
            {usage.vouchedEndpoints.length > 0 && <span className="block text-xs">Self-hosted endpoints vouched for by this deployment: {usage.vouchedEndpoints.join(", ")}</span>}
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div>
            <p className="mb-1 text-xs font-medium">What left the boundary in the last {usage.days} days (PRD-AIC-044)</p>
            {usage.egress.length === 0 ? <p className="text-xs text-muted-foreground">Nothing has been sent to a model yet.</p> : (
              <Table>
                <TableHeader><TableRow><TableHead>Provider</TableHead><TableHead>Data category</TableHead><TableHead>Calls</TableHead><TableHead>Cached</TableHead><TableHead>Refused</TableHead><TableHead>Tokens</TableHead><TableHead>Injection signals</TableHead><TableHead>Last</TableHead></TableRow></TableHeader>
                <TableBody>
                  {usage.egress.map((e, i) => (
                    <TableRow key={i}>
                      <TableCell className="text-xs">{e.provider}</TableCell>
                      <TableCell><Badge tone={e.dataCategory === "RECORD" ? "warn" : "neutral"}>{e.dataCategory}</Badge></TableCell>
                      <TableCell className="text-xs">{e.calls}</TableCell><TableCell className="text-xs">{e.cached}</TableCell>
                      <TableCell className="text-xs">{e.refused}</TableCell><TableCell className="text-xs font-mono">{e.tokens.toLocaleString()}</TableCell>
                      <TableCell className="text-xs">{e.injectionSignals > 0 ? <span className="text-tone-warn">{e.injectionSignals}</span> : "0"}</TableCell>
                      <TableCell className="text-xs text-muted-foreground">{e.lastAt?.replace("T", " ").slice(0, 16)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
          {usage.mayManage && budget && (
            <div className="rounded-md border p-3">
              <p className="mb-2 text-xs font-medium">Budget (PRD-AIC-053)</p>
              <div className="grid gap-3 sm:grid-cols-3">
                <div className="flex flex-col gap-1"><Label>Tokens per UTC day</Label><Input type="number" value={budget.dailyTokenBudget} onChange={(e) => setBudget({ ...budget, dailyTokenBudget: Number(e.target.value) })} /></div>
                <div className="flex flex-col gap-1"><Label>Calls per person per hour</Label><Input type="number" value={budget.perPrincipalHourlyInvocations} onChange={(e) => setBudget({ ...budget, perPrincipalHourlyInvocations: Number(e.target.value) })} /></div>
                <div className="flex flex-col gap-1"><Label>Identical-request cache (minutes)</Label><Input type="number" value={budget.cacheMinutes} onChange={(e) => setBudget({ ...budget, cacheMinutes: Number(e.target.value) })} /></div>
              </div>
              <div className="mt-2 flex flex-wrap gap-4 text-xs">
                <label className="flex items-center gap-2"><input type="checkbox" checked={budget.retainOutputs} onChange={(e) => setBudget({ ...budget, retainOutputs: e.target.checked })} /> retain model outputs (needed for the cache)</label>
                <label className="flex items-center gap-2"><input type="checkbox" checked={budget.retainPrompts} onChange={(e) => setBudget({ ...budget, retainPrompts: e.target.checked })} /> retain the assembled prompts</label>
              </div>
              <div className="mt-2"><Button size="sm" disabled={busy} onClick={saveBudget}>Save budget</Button></div>
            </div>
          )}
          <div>
            <p className="mb-1 text-xs font-medium">Recent invocations</p>
            {usage.recent.length === 0 ? <p className="text-xs text-muted-foreground">None.</p> : (
              <Table>
                <TableHeader><TableRow><TableHead>When</TableHead><TableHead>Capability</TableHead><TableHead>By</TableHead><TableHead>Model</TableHead><TableHead>Category</TableHead><TableHead>Outcome</TableHead><TableHead>Tokens</TableHead><TableHead>ms</TableHead></TableRow></TableHeader>
                <TableBody>
                  {usage.recent.slice(0, 15).map((r) => (
                    <TableRow key={r.id}>
                      <TableCell className="text-xs">{r.at.replace("T", " ").slice(0, 19)}</TableCell>
                      <TableCell className="text-xs font-mono">{r.capability}</TableCell>
                      <TableCell className="text-xs">{r.by}</TableCell>
                      <TableCell className="text-xs font-mono">{r.modelIdentity} · {r.promptVersion}</TableCell>
                      <TableCell><Badge tone={r.dataCategory === "RECORD" ? "warn" : "neutral"}>{r.dataCategory}</Badge></TableCell>
                      <TableCell><Badge tone={r.outcome === "OK" || r.outcome === "CACHED" ? "ok" : "critical"}>{r.outcome}{r.refusalCode ? ` · ${r.refusalCode}` : ""}</Badge></TableCell>
                      <TableCell className="text-xs font-mono">{r.promptTokens + r.completionTokens}</TableCell>
                      <TableCell className="text-xs">{r.latencyMs}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
          {error && <p className="text-xs text-destructive">{error}</p>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex-row items-start justify-between pb-2">
          <div>
            <CardTitle className="flex items-center gap-2"><FlaskConical className="size-4 text-primary" /> Evaluation harness</CardTitle>
            <CardDescription>
              The DOC-10 §10.2 measures over fixed scenarios — known facts, planted injections, insufficient data — run against the active
              provider. Absolute measures gate at 100%; injection resistance and refusal correctness at 95%. A failing run is reported, not
              acted on: switching a capability off is a person's decision.
            </CardDescription>
          </div>
          {usage.mayManage && <Button size="sm" disabled={busy} onClick={evaluate}>{busy ? "Running…" : "Run now"}</Button>}
        </CardHeader>
        <CardContent>
          {runs.length === 0 ? <p className="text-xs text-muted-foreground">No run yet.</p> : (
            <div className="flex flex-col gap-2">
              {runs.map((r) => (
                <div key={r.id} className="rounded-md border p-2 text-xs">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={r.gate === "PASS" ? "ok" : r.gate === "FAIL" ? "critical" : "neutral"}>{r.gate}</Badge>
                    <span className="font-mono">{r.modelIdentity}</span>
                    <span className="text-muted-foreground">{r.startedAt.replace("T", " ").slice(0, 16)} · {r.scenarios} scenarios</span>
                    <Button size="sm" variant="ghost" onClick={() => setOpen(open === r.id ? null : r.id)}>{open === r.id ? "Hide" : "Details"}</Button>
                  </div>
                  <div className="mt-1 flex flex-wrap gap-2">
                    {Object.entries(r.measures).map(([k, m]) => (
                      <span key={k} className={`rounded border px-1.5 py-0.5 ${m.gate ? "" : "border-destructive text-destructive"}`} title={`threshold ${m.threshold}%`}>
                        {k.replace(/_/g, " ")} {m.total === 0 ? "—" : `${m.pass}/${m.total}`}
                      </span>
                    ))}
                  </div>
                  {open === r.id && r.failures.length > 0 && (
                    <ul className="mt-2 list-disc pl-4 text-muted-foreground">
                      {r.failures.map((f, i) => <li key={i}><span className="font-mono">{f.scenario}</span> · {f.measure}: {f.why}{f.output ? <span className="block italic">“{f.output.slice(0, 200)}”</span> : null}</li>)}
                    </ul>
                  )}
                  {open === r.id && r.failures.length === 0 && <p className="mt-2 text-tone-ok">Every scenario passed every measure.</p>}
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
