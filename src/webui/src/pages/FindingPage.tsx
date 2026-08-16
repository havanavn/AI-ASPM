import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, Loader2, Pencil, Save, Sparkles, X } from "lucide-react";
import { api } from "@/lib/api";
import { severityTone } from "@/components/tone";
import { Comments, type CommentRow } from "@/components/Comments";
import { FindingLifecycle } from "@/components/FindingLifecycle";
import { TaxonomyPicker, TAXONOMY_HELP } from "@/components/TaxonomyPicker";
import { Prose } from "@/components/Prose";
import { RichText } from "@/components/RichTextLazy";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

interface Detail {
  id: string; requestId: string; title: string; severity: string | null; state: string;
  closureReason: string | null; context: string | null; firstDetectedAt: string | null;
  lastDetectedAt: string | null; acceptedUntil: string | null; assetName: string | null;
  sourceTool: string | null; rowVersion: number;
  description: string | null; proofOfConcept: string | null;
  descriptionHtml: string | null; proofOfConceptHtml: string | null;
  severities: { id: string; code: string }[];
  contexts: string[];
  classification: {
    executiveRiskCategory: string | null; owaspTop10_2025: string | null; cweId: string | null;
    source: string | null; basis: string | null;
  };
  riskCategories: { code: string; label: string; hint: string }[];
  owaspCategories: { code: string; label: string; hint: string }[];
  cwes: { code: string; label: string; hint: string }[];
  comments: CommentRow[];
  mayAct: boolean;
  remediationClaimedAt: string | null;
  remediationClaimedBy: string | null;
  remediationNote: string | null;
  /** Whether this caller may say a fix is in place. The delivery team can; closing stays elsewhere. */
  mayClaimRemediation: boolean;
}

export function FindingPage() {
  const { id = "", findingId = "" } = useParams();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<{
    title: string; severity: string; context: string; description: string; poc: string;
    // The three classifications, plus where the current values came from. Carried in the draft rather
    // than read from `detail` so an edit in progress is not lost when the finding reloads.
    category: string; owasp: string; cwe: string;
    source: "ASSESSOR" | "AI_ASSISTED"; basis: string;
  } | null>(null);
  const [analysing, setAnalysing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const path = `/api/ui/board/${id}/findings/${findingId}`;
  const load = useCallback(() => {
    api.get<Detail>(path).then((d) => { setDetail(d); setEditing(false); })
       .catch((e) => setError(e.message));
  }, [path]);
  useEffect(load, [load]);

  function startEditing() {
    if (!detail) return;
    setDraft({
      title: detail.title,
      severity: detail.severities.find((s) => s.code === detail.severity)?.id ?? "",
      context: detail.context ?? "",
      description: detail.description ?? "",
      poc: detail.proofOfConcept ?? "",
      category: detail.classification.executiveRiskCategory ?? "",
      owasp: detail.classification.owaspTop10_2025 ?? "",
      cwe: detail.classification.cweId ?? "",
      // Whatever it was recorded as. Editing any of the three by hand resets it to ASSESSOR, because
      // from that point the value is this person's judgement, not a proposal they left alone.
      source: (detail.classification.source as "ASSESSOR" | "AI_ASSISTED") ?? "ASSESSOR",
      basis: detail.classification.basis ?? "",
    });
    setEditing(true);
  }

  /** Same endpoint the create form uses. Fills the draft; saving is still this person's action. */
  async function analyse() {
    if (!draft) return;
    setAnalysing(true); setError(null);
    try {
      const p = await api.post<{
        executiveRiskCategory: string; owaspTop10_2025: string; cweId: string;
        basis: string; confidence: string;
      }>("/api/ui/findings/classify", {
        title: draft.title, description: draft.description, findingClass: detail?.sourceTool ?? null,
      });
      setDraft({ ...draft, category: p.executiveRiskCategory, owasp: p.owaspTop10_2025,
                 cwe: p.cweId, source: "AI_ASSISTED",
                 basis: `${p.basis} (${p.confidence.toLowerCase()} confidence)` });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setAnalysing(false); }
  }

  async function save() {
    if (!detail || !draft) return;
    setBusy(true); setError(null);
    try {
      await api.post(path, {
        title: draft.title, severity: draft.severity, context: draft.context,
        description: draft.description, proofOfConcept: draft.poc,
        executiveRiskCategory: draft.category, owaspTop10_2025: draft.owasp, cweId: draft.cwe,
        classificationSource: draft.source, classificationBasis: draft.basis || null,
        // The version the editor opened at. The server refuses the write if anything moved, so two
        // people editing the same write-up produces a conflict somebody sees rather than a silent loss.
        rowVersion: detail.rowVersion,
      });
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  if (!detail) return <div className="text-sm text-muted-foreground">{error ?? "Loading…"}</div>;
  const uploadTo = `/board/${id}/attachments`;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <Link to={`/board/${id}`} className="mb-1 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
            <ArrowLeft className="size-3" /> Back to the request
          </Link>
          <h1 className="text-lg font-semibold tracking-tight">{detail.title}</h1>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <Badge tone={severityTone(detail.severity)}>{detail.severity ?? "unrated"}</Badge>
            {/* No state badge here. It used to show the coarse OPEN/CLOSED axis, so a finding the
                delivery team had reported fixed read "OPEN" in the header while the Status card
                immediately below read "Fixed — awaiting verification". Two labels for one fact,
                disagreeing on screen, is how somebody concludes the control does not work — which is
                exactly what happened. One name, one meaning, one place (PP-10): the Status card. */}
            {detail.assetName && <span className="text-xs text-muted-foreground">{detail.assetName}</span>}
          </div>
        </div>
        {detail.mayAct && !editing && (
          <Button variant="outline" size="sm" onClick={startEditing}><Pencil /> Edit</Button>
        )}
      </div>

      {error && (
        <div className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
          {error}
        </div>
      )}

      {/* Above the write-up. Somebody opening a finding is deciding what to do with it, and the
          state plus the moves available is that decision; the prose is what they read to make it. */}
      <FindingLifecycle findingId={findingId} onMoved={load} />

      {editing && draft ? (
        <Card>
          <CardHeader>
            <CardTitle>Write-up</CardTitle>
            <CardDescription>Stored as Markdown — the same text the record renders from.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="flex flex-col gap-1 sm:col-span-3">
                <Label htmlFor="title">Title</Label>
                <Input id="title" value={draft.title}
                       onChange={(e) => setDraft({ ...draft, title: e.target.value })} />
              </div>
              <div className="flex flex-col gap-1">
                <Label>Severity</Label>
                <Select value={draft.severity} onValueChange={(v) => setDraft({ ...draft, severity: v })}>
                  <SelectTrigger><SelectValue placeholder="Unrated" /></SelectTrigger>
                  <SelectContent>
                    {detail.severities.map((s) => <SelectItem key={s.id} value={s.id}>{s.code}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1">
                <Label>How it was found</Label>
                <Select value={draft.context} onValueChange={(v) => setDraft({ ...draft, context: v })}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {detail.contexts.map((c) => <SelectItem key={c} value={c}>{c.replace(/_/g, " ").toLowerCase()}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            </div>

            {/* The three classifications, editable here as well as on the create form. Unlike the
                create form these are NOT required: 658 findings predate the fields, and demanding a
                classification to fix a typo in a two-year-old write-up would make those records
                unsavable. The gate belongs where records enter, not where they are corrected. */}
            <div className="flex flex-col gap-2 rounded border border-border p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-sm font-medium">Classification</span>
                <Button size="sm" variant="secondary" disabled={analysing || !draft.title.trim()}
                        onClick={() => void analyse()}>
                  {analysing ? <Loader2 className="size-3 animate-spin" />
                             : <Sparkles className="size-3" />}
                  {analysing ? "Analysing…" : "Analyse with AI"}
                </Button>
              </div>
              {/* The same three-column grid, now built from the component the create form also uses.
                  Two copies of a picker diverge, and the copy nobody is looking at keeps the old
                  behaviour. */}
              <div className="grid gap-3 md:grid-cols-3">
                <TaxonomyPicker id="fp-category" label="Executive risk category"
                                help={TAXONOMY_HELP.category} options={detail.riskCategories}
                                value={draft.category}
                                onChange={(v) => setDraft({ ...draft, category: v,
                                                            source: "ASSESSOR", basis: "" })} />
                <TaxonomyPicker id="fp-owasp" label="OWASP Top 10:2025"
                                help={TAXONOMY_HELP.owasp} options={detail.owaspCategories}
                                value={draft.owasp}
                                onChange={(v) => setDraft({ ...draft, owasp: v,
                                                            source: "ASSESSOR", basis: "" })} />
                <TaxonomyPicker id="fp-cwe" label="CWE"
                                help={TAXONOMY_HELP.cwe} options={detail.cwes}
                                value={draft.cwe}
                                onChange={(v) => setDraft({ ...draft, cwe: v,
                                                            source: "ASSESSOR", basis: "" })} />
              </div>
            </div>

            <div className="flex flex-col gap-1">
              <Label>Description</Label>
              <RichText value={draft.description} onChange={(v) => setDraft({ ...draft, description: v })}
                        uploadTo={uploadTo} finding={findingId} minHeight="12rem" />
            </div>
            <div className="flex flex-col gap-1">
              <Label>Proof of concept</Label>
              <RichText value={draft.poc} onChange={(v) => setDraft({ ...draft, poc: v })}
                        uploadTo={uploadTo} finding={findingId} minHeight="16rem" />
            </div>

            <div className="flex justify-end gap-2">
              <Button variant="ghost" size="sm" onClick={() => setEditing(false)}><X /> Cancel</Button>
              <Button size="sm" disabled={busy} onClick={save}><Save /> {busy ? "Saving…" : "Save"}</Button>
            </div>
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-5 lg:grid-cols-2">
          <Card>
            <CardHeader><CardTitle>Description</CardTitle></CardHeader>
            <CardContent><Prose html={detail.descriptionHtml} empty="No description recorded." /></CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Proof of concept</CardTitle>
              <CardDescription>Expected to contain hostile content. Rendered, never executed.</CardDescription>
            </CardHeader>
            <CardContent><Prose html={detail.proofOfConceptHtml} empty="No proof of concept recorded." /></CardContent>
          </Card>
        </div>
      )}


      <Card>
        <CardHeader><CardTitle>Detection</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-4">
          <Fact label="First detected" value={detail.firstDetectedAt} mono />
          <Fact label="Last confirmed" value={detail.lastDetectedAt} mono />
          <Fact label="How it was found" value={detail.context?.replace(/_/g, " ").toLowerCase() ?? null} />
          <Fact label="Source" value={detail.sourceTool} />
        </CardContent>
      </Card>

      <Comments comments={detail.comments} postTo={`${path}/comments`} uploadTo={uploadTo}
                finding={findingId}
                onPosted={(comments) => setDetail({ ...detail, comments })} />
    </div>
  );
}

function Fact({ label, value, mono }: { label: string; value: string | null; mono?: boolean }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={mono ? "font-mono text-xs" : "text-sm"}>
        {value ?? <span className="text-muted-foreground">—</span>}
      </div>
    </div>
  );
}

