import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Loader2 } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Combobox } from "@/components/Combobox";

interface Owner { id: string; name: string; typeCode: string; depth: number }
interface Tier { id: string; code: string; ordinal: number }
/**
 * One environment an endpoint can be published in — tenant data, from the catalogue.
 *
 * This form used to have Production and Staging written into it, while the project form had
 * Production and UAT. Neither could record what the other could, and because the inventory offers a
 * domain column only for environments its data already carries, an application's UAT host was
 * unrecordable and therefore absent from every list, filter and count (ADR-061).
 *
 * `lifecycleState` is `UNDECLARED` for an environment only the data carries and `DEPRECATED` for one
 * the tenant retired. Either appears here only when this record already holds a host in it, so a
 * value is never hidden from the form that would have to clear it.
 */
interface Environment { code: string; label: string; purpose: string | null; lifecycleState: string }
interface Existing {
  id: string; name: string; owningNodeId: string | null;
  exposureDeclared: string | null; criticalityCode: string | null; criticalityInherited: boolean;
  description: string; userBase: string; features: string; tags: string;
  /** Hosts by environment code. Several in one environment is a real state, not a data defect. */
  domains: Record<string, string[]>; repository: string;
  rowVersion: number; lifecycleState: string;
}
interface Editor {
  application: Existing | null;
  owners: Owner[]; tiers: Tier[]; exposures: string[]; environments: Environment[];
  mayRetire: boolean;
}

const INHERIT = "__inherit__";
const UNDECLARED = "__undeclared__";

const EXPOSURE_LABEL: Record<string, string> = {
  INTERNET_PUBLIC: "Internet-facing",
  PARTNER_B2B: "Partner / B2B",
  INTERNAL_ONLY: "Internal only",
  AIR_GAPPED: "Air-gapped",
};

/**
 * Create and edit an application, in the new interface.
 *
 * This replaces a hand-off to the server-rendered editor. The hand-off was not merely inconsistent:
 * a person who followed it lost their filters and their place in a list, and came back to something
 * that looked like a different product. The rules did not move — every field here posts to a handler
 * that calls the same service the old page called, including the optimistic-concurrency check.
 *
 * `rowVersion` is loaded with the record and sent back with the save. Where somebody else edited the
 * application in between the server refuses and this page says so, rather than re-submitting with a
 * fresh version — which would turn the safeguard into a slower way of losing their edit.
 */
export function ApplicationEditPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [editor, setEditor] = useState<Editor | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<{ field?: string; message: string } | null>(null);
  const [form, setForm] = useState({
    name: "", owningNodeId: "", criticalityTierId: INHERIT, exposureDeclared: UNDECLARED,
    description: "", userBase: "", features: "", tags: "", repository: "",
  });
  // Endpoints are held apart from the flat form because their keys are tenant data: the set of
  // environments is not known until the editor payload arrives, so they cannot be fields of a
  // literal. One comma-separated string per environment, which is what the input holds.
  const [domains, setDomains] = useState<Record<string, string>>({});

  useEffect(() => {
    const path = id ? `/api/ui/applications/${id}/editor` : "/api/ui/applications/editor";
    api.get<Editor>(path).then((d) => {
      setEditor(d);
      const a = d.application;
      if (a) {
        setForm({
          name: a.name,
          owningNodeId: a.owningNodeId ?? "",
          // An inherited tier loads as "inherit", not as the inherited value. Preselecting the
          // node's tier would let a person save an assignment they never made, and the application
          // would then stop following its organization the next time that changes.
          criticalityTierId: a.criticalityInherited || !a.criticalityCode ? INHERIT
            : (d.tiers.find((t) => t.code === a.criticalityCode)?.id ?? INHERIT),
          exposureDeclared: a.exposureDeclared ?? UNDECLARED,
          description: a.description, userBase: a.userBase, features: a.features, tags: a.tags,
          repository: a.repository,
        });
        const held: Record<string, string> = {};
        Object.entries(a.domains ?? {}).forEach(([code, hosts]) => {
          held[code] = (hosts ?? []).join(", ");
        });
        setDomains(held);
      }
    }).catch((e) => setError(e.message));
  }, [id]);

  function set(key: keyof typeof form, value: string) {
    setForm((f) => ({ ...f, [key]: value }));
    setProblem(null);
  }

  async function save() {
    if (!editor) return;
    setBusy(true);
    setProblem(null);
    try {
      const body: Record<string, unknown> = {
        name: form.name,
        owningNodeId: form.owningNodeId,
        criticalityTierId: form.criticalityTierId === INHERIT ? null : form.criticalityTierId,
        exposureDeclared: form.exposureDeclared === UNDECLARED ? null : form.exposureDeclared,
        description: form.description, userBase: form.userBase,
        features: form.features, tags: form.tags,
        repository: form.repository,
        // Every environment this form rendered, including the ones left blank — a blank is how
        // somebody clears an endpoint, and the server touches only the environments it is sent.
        domains: Object.fromEntries((editor.environments ?? []).map(
          (e) => [e.code, domains[e.code] ?? ""])),
      };
      if (editor.application) body.rowVersion = editor.application.rowVersion;
      const saved = await api.post<{ id: string }>(
        editor.application ? `/api/ui/applications/${editor.application.id}` : "/api/ui/applications",
        body);
      navigate(`/applications/${saved.id}`);
    } catch (e) {
      const err = e as ApiError;
      setProblem({ field: err.field ?? undefined, message: err.message });
    } finally {
      setBusy(false);
    }
  }

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!editor) return <div className="text-sm text-muted-foreground">Loading…</div>;

  const existing = editor.application;
  const back = existing ? `/applications/${existing.id}` : "/applications";

  return (
    <div className="flex flex-col gap-5">
      <div>
        <Link to={back} className="mb-1 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-3" /> {existing ? existing.name : "All applications"}
        </Link>
        <h1 className="text-lg font-semibold tracking-tight">
          {existing ? "Edit application" : "New application"}
        </h1>
        <p className="text-xs text-muted-foreground">
          Ownership and exposure decide who can see this application and how its findings are scored,
          so both are worth getting right rather than filling in.
        </p>
      </div>

      {editor.owners.length === 0 && (
        <Card className="border-destructive/40">
          <CardContent className="text-sm text-destructive">
            No organization you can reach is allowed to own an asset. An application must sit under a
            node whose <em>type</em> permits it — an administrator can enable that on the node type,
            or place you in scope of one that already has it.
          </CardContent>
        </Card>
      )}

      {problem && (
        <Card className="border-destructive/40">
          <CardContent className="text-sm text-destructive">{problem.message}</CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Identity and ownership</CardTitle>
          <CardDescription>
            Who is accountable for this application, and how exposed it is.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <Field label="Name" required invalid={problem?.field === "name"}>
            <Input value={form.name} onChange={(e) => set("name", e.target.value)}
                   placeholder="Card Issuing" />
          </Field>

          <Field label="Owning organization" required invalid={problem?.field === "owningNodeId"}
                 hint="Only nodes whose type may own assets are listed.">
            {/* A search box rather than a bare select. A group with two hundred nodes makes a
                dropdown a scrolling exercise, and the field people get wrong most often is this one. */}
            <Combobox
              items={editor.owners.map((o) => ({
                id: o.id, name: o.name, hint: o.typeCode.toLowerCase(),
              }))}
              value={form.owningNodeId}
              onChange={(v) => set("owningNodeId", v)}
              placeholder="Choose an organization"
              clearLabel="" />
          </Field>

          <Field label="Business criticality"
                 hint="Leave inherited unless this application differs from its organization.">
            <Select value={form.criticalityTierId}
                    onValueChange={(v) => set("criticalityTierId", v)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={INHERIT}>Inherit from the organization</SelectItem>
                {editor.tiers.map((t) => (
                  <SelectItem key={t.id} value={t.id}>{t.code}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>

          <Field label="Declared exposure"
                 hint="What you intend it to be. Observed exposure is recorded separately and a
                       disagreement between the two is raised rather than resolved silently.">
            <Select value={form.exposureDeclared}
                    onValueChange={(v) => set("exposureDeclared", v)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={UNDECLARED}>Not declared</SelectItem>
                {editor.exposures.map((x) => (
                  <SelectItem key={x} value={x}>{EXPOSURE_LABEL[x] ?? x}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>

          <Field label="User base" hint="Who uses it — staff, customers, partners, the public.">
            <Input value={form.userBase} onChange={(e) => set("userBase", e.target.value)} />
          </Field>

          <Field label="Tags" hint="Comma-separated.">
            <Input value={form.tags} onChange={(e) => set("tags", e.target.value)}
                   placeholder="pci, tier1" />
          </Field>

          <div className="md:col-span-2">
            <Field label="Description">
              <Input value={form.description} onChange={(e) => set("description", e.target.value)} />
            </Field>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Technical footprint</CardTitle>
          <CardDescription>
            Each of these creates or attaches an asset in the graph. The platform stores the
            repository address as an identifier — it never clones, fetches or reads the code.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          {/* One input per declared environment, from the catalogue. Retired and undeclared ones
              appear only where this record already holds a host in them, labelled so nobody reads a
              retired environment as a current choice. */}
          {(editor.environments ?? []).map((environment) => (
            <Field key={environment.code}
                   label={`${environment.label} domain`}
                   hint={environment.lifecycleState === "ACTIVE"
                     ? (environment.purpose ?? undefined)
                     : environment.lifecycleState === "DEPRECATED"
                       ? "This environment is retired. The host recorded here is still current — "
                         + "clear it, or restore the environment in settings."
                       : "Recorded by an import under a name nobody declared. Declare it in "
                         + "settings, or clear the host."}>
              <Input value={domains[environment.code] ?? ""}
                     onChange={(e) => {
                       setDomains((d) => ({ ...d, [environment.code]: e.target.value }));
                       setProblem(null);
                     }}
                     placeholder="pay.example.com" />
            </Field>
          ))}
          <Field label="Repository">
            <Input value={form.repository} onChange={(e) => set("repository", e.target.value)}
                   placeholder="group/payments-api" />
          </Field>
          <div className="md:col-span-3">
            <Field label="Features" hint="Comma-separated. Each becomes a component in the graph.">
              <Input value={form.features} onChange={(e) => set("features", e.target.value)} />
            </Field>
          </div>
        </CardContent>
      </Card>

      <div className="flex items-center gap-2">
        <Button size="sm" onClick={save} disabled={busy || editor.owners.length === 0}>
          {busy && <Loader2 className="size-3 animate-spin" />}
          {existing ? "Save changes" : "Create application"}
        </Button>
        <Button size="sm" variant="ghost" asChild><Link to={back}>Cancel</Link></Button>
      </div>

      {existing && editor.mayRetire && existing.lifecycleState === "ACTIVE" && (
        <Retire application={existing} onRetired={() => navigate("/applications")} />
      )}
    </div>
  );
}

function Field({ label, hint, required, invalid, children }: {
  label: string; hint?: string; required?: boolean; invalid?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label className={invalid ? "text-destructive" : undefined}>
        {label}{required && <span className="pl-0.5 text-destructive">*</span>}
      </Label>
      {children}
      {hint && <span className="text-[11px] leading-tight text-muted-foreground">{hint}</span>}
    </div>
  );
}

/**
 * Retirement, behind a reason.
 *
 * Not a delete. The application's findings, requests and history stay readable — the record of what
 * happened is inviolable — and the reason is required because "why is this gone" is the question
 * somebody asks six months later with nobody left to answer it.
 */
function Retire({ application, onRetired }: { application: Existing; onRetired: () => void }) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function retire() {
    setBusy(true);
    setProblem(null);
    try {
      await api.post(`/api/ui/applications/${application.id}/retire`,
        { reason, rowVersion: application.rowVersion });
      onRetired();
    } catch (e) {
      setProblem((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="border-destructive/30">
      <CardHeader>
        <CardTitle className="text-destructive">Retire this application</CardTitle>
        <CardDescription>
          It leaves the inventory and stops counting towards coverage. Nothing is deleted: its
          findings, reviews and history stay readable, and it can be found again by identifier.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {problem && <p className="text-sm text-destructive">{problem}</p>}
        <Field label="Reason" required
               hint="Recorded against the retirement. Somebody will read this without you there.">
          <Input value={reason} onChange={(e) => setReason(e.target.value)}
                 placeholder="Decommissioned; traffic moved to Merchant Gateway" />
        </Field>
        <div>
          <Button size="sm" variant="destructive" onClick={retire}
                  disabled={busy || reason.trim().length === 0}>
            {busy && <Loader2 className="size-3 animate-spin" />} Retire
          </Button>
          {reason.trim().length === 0 && (
            <span className="pl-2 text-[11px] text-muted-foreground">A reason is required.</span>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
