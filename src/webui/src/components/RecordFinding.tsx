import { useEffect, useState } from "react";
import { Loader2, Plus, Sparkles, X } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { TaxonomyPicker, TAXONOMY_HELP } from "@/components/TaxonomyPicker";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { RichText } from "@/components/RichTextLazy";

interface TaxonomyOption { code: string; label: string; hint: string }
interface Options {
  severities: { id: string; code: string }[];
  assets: { id: string; name: string; type: string }[];
  contexts: string[];
  defaultContext: string;
  riskCategories: TaxonomyOption[];
  owaspCategories: TaxonomyOption[];
  cwes: TaxonomyOption[];
}

const NO_ASSET = "__none__";
const UNRATED = "__unrated__";

/**
 * Record a finding against a request, in the new interface.
 *
 * This was the last write that still sent an assessor to the server-rendered page, and it is the one
 * they perform most — so the hand-off cost them the most. Both bodies use the same rich-text editor
 * and the same attachment endpoint as the finding detail page, which is what makes an image pasted
 * here behave the way it does everywhere else.
 *
 * The severity defaults to unrated rather than to a middle value. A pre-filled severity is one
 * somebody accepts without deciding, and an unrated finding is visible as unrated everywhere in the
 * product — which is the honest state until an assessor has judged it.
 */
export function RecordFinding({ requestId, onRecorded }: {
  requestId: string; onRecorded: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [options, setOptions] = useState<Options | null>(null);
  const [title, setTitle] = useState("");
  const [severityId, setSeverityId] = useState(UNRATED);
  const [assetId, setAssetId] = useState(NO_ASSET);
  const [context, setContext] = useState("");
  const [description, setDescription] = useState("");
  const [poc, setPoc] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  // The three classifications. Empty by default and required to submit — see the note on `record`.
  const [category, setCategory] = useState("");
  const [owasp, setOwasp] = useState("");
  const [cwe, setCwe] = useState("");
  // ASSESSOR unless a proposal was accepted. Reset to ASSESSOR the moment somebody edits a field by
  // hand, because from then on the value is theirs and labelling it AI-assisted would misattribute a
  // human judgement to a machine.
  const [source, setSource] = useState<"ASSESSOR" | "AI_ASSISTED">("ASSESSOR");
  const [basis, setBasis] = useState("");
  const [analysing, setAnalysing] = useState(false);

  useEffect(() => {
    if (!open || options) return;
    api.get<Options>(`/api/ui/board/${requestId}/finding-form`).then((d) => {
      setOptions(d);
      setContext(d.defaultContext);
    }).catch((e) => setProblem((e as ApiError).message));
  }, [open, options, requestId]);

  async function record() {
    setBusy(true);
    setProblem(null);
    try {
      await api.post(`/api/ui/board/${requestId}/findings`, {
        title,
        severityId: severityId === UNRATED ? null : severityId,
        assetId: assetId === NO_ASSET ? null : assetId,
        context, description, proofOfConcept: poc,
        executiveRiskCategory: category, owaspTop10_2025: owasp, cweId: cwe,
        classificationSource: source, classificationBasis: basis || null,
      });
      setTitle(""); setSeverityId(UNRATED); setAssetId(NO_ASSET);
      setDescription(""); setPoc("");
      setCategory(""); setOwasp(""); setCwe(""); setSource("ASSESSOR"); setBasis("");
      setOpen(false);
      onRecorded();
    } catch (e) {
      setProblem((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  }

  /**
   * Asks the classifier to read what has been typed and fill the three fields.
   *
   * The answer lands in the FORM, not in the database. Whoever pressed this reads it, changes what they
   * disagree with, and their submit is the write — which is what makes classification AI-assisted
   * without AI ever writing a finding.
   */
  async function analyse() {
    setAnalysing(true);
    setProblem(null);
    try {
      const p = await api.post<{
        executiveRiskCategory: string; owaspTop10_2025: string; cweId: string;
        basis: string; confidence: string;
      }>("/api/ui/findings/classify", { title, description, findingClass: "MANUAL" });
      setCategory(p.executiveRiskCategory);
      setOwasp(p.owaspTop10_2025);
      setCwe(p.cweId);
      setSource("AI_ASSISTED");
      setBasis(`${p.basis} (${p.confidence.toLowerCase()} confidence)`);
    } catch (e) {
      setProblem((e as ApiError).message);
    } finally {
      setAnalysing(false);
    }
  }

  /** Edited by hand from here on, so the value is the person's and is labelled that way. */
  function typed<T>(set: (v: T) => void) {
    return (v: T) => { set(v); setSource("ASSESSOR"); setBasis(""); };
  }

  const missing = [
    category ? null : "executive risk category",
    owasp ? null : "OWASP Top 10:2025",
    cwe ? null : "CWE",
  ].filter(Boolean) as string[];

  if (!open) {
    return (
      <div>
        <Button variant="outline" size="sm" onClick={() => setOpen(true)}>
          <Plus className="size-3" /> Record a finding
        </Button>
      </div>
    );
  }

  return (
    <Card>
      <CardHeader className="flex-row items-start justify-between gap-4">
        <div>
          <CardTitle>Record a finding</CardTitle>
          <CardDescription>
            It is attributed to you and to this engagement. Description and proof of concept accept
            pasted images, which are stored as attachments on this request rather than embedded in
            the text.
          </CardDescription>
        </div>
        <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
          <X className="size-3" /> Close
        </Button>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {problem && <p className="text-sm text-destructive">{problem}</p>}

        <div className="grid gap-4 md:grid-cols-4">
          <div className="flex flex-col gap-1.5 md:col-span-2">
            <Label>Title<span className="pl-0.5 text-destructive">*</span></Label>
            <Input value={title} onChange={(e) => setTitle(e.target.value)}
                   placeholder="SQL injection in the settlement export" />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Severity</Label>
            <Select value={severityId} onValueChange={setSeverityId}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={UNRATED}>Not rated yet</SelectItem>
                {(options?.severities ?? []).map((s) => (
                  <SelectItem key={s.id} value={s.id}>{s.code}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <span className="text-[11px] text-muted-foreground">
              Left unrated it stays visibly unrated rather than quietly counting as low.
            </span>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Context</Label>
            <Select value={context} onValueChange={setContext}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                {(options?.contexts ?? []).map((c) => (
                  <SelectItem key={c} value={c}>{c.toLowerCase().replace(/_/g, " ")}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5 md:col-span-2">
            <Label>Against which asset</Label>
            <Select value={assetId} onValueChange={setAssetId}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_ASSET}>Not tied to one asset</SelectItem>
                {(options?.assets ?? []).map((a) => (
                  <SelectItem key={a.id} value={a.id}>{a.name} · {a.type.toLowerCase()}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <span className="text-[11px] text-muted-foreground">
              {/* Only what the engagement declared. Naming an asset the request never covered would
                  put a weakness on something nobody tested, and the coverage figures would be wrong. */}
              Only assets this request declared in scope. Leaving it unset is fine for a finding about
              the system as a whole.
            </span>
          </div>
        </div>

        <div className="flex flex-col gap-1.5">
          <Label>Description</Label>
          <RichText value={description} onChange={setDescription}
                    uploadTo={`/board/${requestId}/attachments`} minHeight="10rem" />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label>Proof of concept</Label>
          <RichText value={poc} onChange={setPoc}
                    uploadTo={`/board/${requestId}/attachments`} minHeight="12rem" />
        </div>

        {/* Classification. Its own block, above the submit row, because all three are required and a
            required field buried between two optional ones is a required field people miss. */}
        <div className="flex flex-col gap-3 rounded border border-border p-3">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <div>
              <span className="text-sm font-medium">How this is classified</span>
              <p className="text-[11px] text-muted-foreground">
                All three are needed to record it. The executive category is what the business-facing
                reports group by; OWASP and CWE are what a security reader looks for.
              </p>
            </div>
            {/* Reads what has been typed and fills the three fields. Nothing is saved by pressing it —
                the answer lands here for you to change, and recording it is still your action. */}
            <Button size="sm" variant="secondary" disabled={analysing || !title.trim()}
                    title={title.trim()
                      ? "Read the title and description and propose all three"
                      : "Type a title first — there is nothing to read yet"}
                    onClick={() => void analyse()}>
              {analysing ? <Loader2 className="size-3 animate-spin" /> : <Sparkles className="size-3" />}
              {analysing ? "Analysing…" : "Analyse with AI"}
            </Button>
          </div>

          <div className="grid gap-3 md:grid-cols-3">
            {/* One component for all three, and the same one the edit form uses. Two copies of a
                picker diverge, and the copy nobody is looking at is the one that keeps the old
                behaviour. The required marker stays here because it is a property of THIS form —
                submission is blocked without all three; editing an old finding is not. */}
            <TaxonomyPicker id="rf-category" label="Executive risk category *"
                            help={TAXONOMY_HELP.category}
                            options={options?.riskCategories ?? []}
                            value={category} onChange={typed(setCategory)} placeholder="Pick one" />
            <TaxonomyPicker id="rf-owasp" label="OWASP Top 10:2025 *"
                            help={TAXONOMY_HELP.owasp}
                            options={options?.owaspCategories ?? []}
                            value={owasp} onChange={typed(setOwasp)} placeholder="Pick one" />
            <TaxonomyPicker id="rf-cwe" label="CWE *"
                            help={TAXONOMY_HELP.cwe}
                            options={options?.cwes ?? []}
                            value={cwe} onChange={typed(setCwe)} placeholder="Pick one" />
          </div>

          {source === "AI_ASSISTED" && basis && (
            <p className="rounded bg-muted p-2 text-[11px] text-muted-foreground">
              <span className="font-medium text-foreground">Proposed, not decided:</span> {basis}.
              Change anything you disagree with — recording it is your submission, and it will be
              marked AI-assisted only if you leave these values as they are.
            </p>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" onClick={record}
                  disabled={busy || !title.trim() || !options || missing.length > 0}>
            {busy && <Loader2 className="size-3 animate-spin" />} Record it
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
          {/* Names what is missing rather than only disabling the button. A greyed-out submit with no
              reason is the commonest way a form wastes somebody's afternoon. */}
          {missing.length > 0 && (
            <span className="text-[11px] text-tone-warn">
              Still needed: {missing.join(", ")}.
            </span>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
