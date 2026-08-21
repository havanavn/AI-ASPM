import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Loader2 } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { AttributeFields, type AttributeValue, type FieldDefinition } from "@/components/AttributeFields";
import { Combobox } from "@/components/Combobox";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { humanise } from "@/components/AttributeFields";

const INHERIT = "__inherit__";
const UNDECLARED = "__undeclared__";
const NOBODY = "__nobody__";

interface Record_ {
  id: string; name: string; applicationName: string | null; owningNodeName: string | null;
  exposureDeclared: string | null; criticalityCode: string | null; criticalityInherited: boolean;
  technicalContactId: string | null; technicalContactName: string | null;
  attributes: Record<string, AttributeValue>;
  /** Hosts by environment code. Several in one environment is a real state, not a data defect. */
  domains: Record<string, string[]>;
  repository: string; repositoryBranch: string;
  rowVersion: number; lifecycleState: string;
}
/** One environment an endpoint can be published in — tenant data, from the catalogue (ADR-061). */
interface Environment { code: string; label: string; purpose: string | null; lifecycleState: string }
interface Editor {
  project: Record_;
  fields: FieldDefinition[];
  tiers: { id: string; code: string; ordinal: number }[];
  exposures: string[];
  environments: Environment[];
  people: { id: string; name: string }[];
}

/**
 * The project record.
 *
 * **Three groups, and the grouping is the argument.** Identity and accountability first, because a
 * record nobody owns cannot be acted on whatever else it says. Then where it runs — the domains and
 * the repository, which are the only fields here that are *not* stored on the project: each is an
 * edge to a shared asset, so two projects on one host are two edges to one domain and an advisory
 * reaching that host reaches both. Then everything the tenant declared.
 *
 * **The declared group is not written into this file.** It is rendered from the catalogue the server
 * sends, so an administrator adding a field to the PROJECT type sees it appear here with no release.
 * That is ADR-027 working: the product supplies the storage kinds and the widgets, the tenant
 * supplies the fields.
 */
export function ProjectEditPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const [editor, setEditor] = useState<Editor | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<{ field?: string; message: string } | null>(null);

  const [name, setName] = useState("");
  const [tier, setTier] = useState(INHERIT);
  const [exposure, setExposure] = useState(UNDECLARED);
  const [contact, setContact] = useState(NOBODY);
  // One comma-separated string per environment, keyed by code. Not two named fields: the pair that
  // used to be here — Production and UAT — disagreed with the application form's Production and
  // Staging, so neither form could record what the other could (ADR-061).
  const [domains, setDomains] = useState<Record<string, string>>({});
  const [repo, setRepo] = useState("");
  const [branch, setBranch] = useState("");
  const [attributes, setAttributes] = useState<Record<string, AttributeValue>>({});

  useEffect(() => {
    let live = true;
    api.get<Editor>(`/api/ui/projects/${id}/editor`).then((d) => {
      if (!live) return;
      setEditor(d);
      setName(d.project.name);
      // An INHERITED tier is loaded as "inherit", not as the code it currently resolves to. Loading
      // the resolved value and saving it would silently convert an inherited tier into an assigned
      // one, which is a change nobody made and nothing would flag.
      setTier(d.project.criticalityInherited || !d.project.criticalityCode ? INHERIT
        : (d.tiers.find((t) => t.code === d.project.criticalityCode)?.id ?? INHERIT));
      setExposure(d.project.exposureDeclared ?? UNDECLARED);
      setContact(d.project.technicalContactId ?? NOBODY);
      const held: Record<string, string> = {};
      Object.entries(d.project.domains ?? {}).forEach(([code, hosts]) => {
        held[code] = (hosts ?? []).join(", ");
      });
      setDomains(held);
      setRepo(d.project.repository);
      setBranch(d.project.repositoryBranch);
      setAttributes(d.project.attributes ?? {});
    }).catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, [id]);

  async function save() {
    if (!editor) return;
    setBusy(true);
    setProblem(null);
    try {
      await api.post(`/api/ui/projects/${id}/editor`, {
        name,
        criticalityTierId: tier === INHERIT ? null : tier,
        exposureDeclared: exposure === UNDECLARED ? null : exposure,
        technicalContactId: contact === NOBODY ? null : contact,
        // Every environment the form rendered, blanks included: a blank clears that environment's
        // endpoints, and the server leaves alone any environment it is not sent.
        domains: Object.fromEntries((editor.environments ?? []).map(
          (e) => [e.code, domains[e.code] ?? ""])),
        repository: repo, repositoryBranch: branch,
        attributes,
        rowVersion: editor.project.rowVersion,
      });
      navigate(`/projects/${id}`);
    } catch (e) {
      const err = e as ApiError;
      setProblem({ field: err.field ?? undefined, message: err.message });
    } finally {
      setBusy(false);
    }
  }

  if (error) return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  if (!editor) return <div className="text-sm text-muted-foreground">Loading…</div>;

  return (
    <div className="flex flex-col gap-5">
      <div>
        <Link to={`/projects/${id}`}
              className="mb-1 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-3" /> {editor.project.name}
        </Link>
        <h1 className="text-lg font-semibold tracking-tight">Edit project record</h1>
        <p className="text-xs text-muted-foreground">
          {editor.project.applicationName
            ? <>Part of {editor.project.applicationName}.{" "}</>
            : null}
          Owned by {editor.project.owningNodeName ?? "nobody recorded"} — the owning organization is
          changed from the inventory, because moving a project rewrites the scope of everything
          recorded against it.
        </p>
      </div>

      {problem && (
        <Card><CardContent className="text-sm text-destructive">
          {problem.message}
        </CardContent></Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Identity and accountability</CardTitle>
          <CardDescription>
            Who answers for this, and how much it matters. A record nobody owns cannot be acted on
            whatever else it says.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-5 md:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="name">Name<span className="pl-0.5 text-destructive">*</span></Label>
            <Input id="name" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Technical contact</Label>
            {/* A person, not the team. The accountable owner is often a manager; the person who can
                answer a question about this at nine on a Friday is often not (PRD-AST-016). */}
            <Combobox
              items={[{ id: NOBODY, name: "— nobody recorded —" }, ...editor.people]}
              value={contact} onChange={setContact}
              placeholder="— nobody recorded —" clearLabel="" />
            <p className="text-[11px] leading-tight text-muted-foreground">
              The person to call. The delivery team below is who is accountable; this is who answers.
            </p>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Business criticality</Label>
            <Select value={tier} onValueChange={setTier}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={INHERIT}>Inherit from the owning organization</SelectItem>
                {editor.tiers.map((t) => <SelectItem key={t.id} value={t.id}>{t.code}</SelectItem>)}
              </SelectContent>
            </Select>
            <p className="text-[11px] leading-tight text-muted-foreground">
              Inheritance is the default so onboarding does not stall on a decision per project; an
              override is the exception and is recorded as one.
            </p>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Exposure</Label>
            <Select value={exposure} onValueChange={setExposure}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={UNDECLARED}>— not declared —</SelectItem>
                {editor.exposures.map((e) => (
                  <SelectItem key={e} value={e}>{humanise(e)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-[11px] leading-tight text-muted-foreground">
              How far the reachability extends. The risk model weighs this. <em>How</em> it is reached
              — direct, ZTNA, VPN — is the separate field below, because a broker in front changes the
              attack path without changing the exposure.
            </p>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Where it runs, and what builds it</CardTitle>
          <CardDescription>
            These are the only fields on this page not stored on the project. Each becomes an edge to
            a shared asset, so two projects on one host are two edges to one domain — which is what
            makes "everything reachable at this host" answerable at all. The environments are the
            tenant's own: whoever administers the catalogue decides which ones this form asks for.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-5 md:grid-cols-2">
          {/* One input per environment the tenant declares. Comma-separated, because a project
              published on two hosts in one environment is a real state — the single-valued field
              this replaces showed the first and closed the edge to the second on save. */}
          {(editor.environments ?? []).map((environment) => (
            <div key={environment.code} className="flex flex-col gap-1.5">
              <Label htmlFor={`domain-${environment.code}`}>{environment.label} domain</Label>
              <Input id={`domain-${environment.code}`} value={domains[environment.code] ?? ""}
                     onChange={(e) =>
                       setDomains((d) => ({ ...d, [environment.code]: e.target.value }))}
                     placeholder="pay.example.vn" />
              {environment.lifecycleState === "ACTIVE" ? (
                environment.purpose && (
                  <p className="text-[11px] leading-tight text-muted-foreground">
                    {environment.purpose}
                  </p>
                )
              ) : (
                <p className="text-[11px] leading-tight text-tone-warn">
                  {environment.lifecycleState === "DEPRECATED"
                    ? "This environment is retired, and this host is still current. Clear it, or "
                      + "restore the environment in settings."
                    : "Recorded by an import under a name nobody declared. Declare it in settings, "
                      + "or clear the host."}
                </p>
              )}
            </div>
          ))}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="repo">Source repository</Label>
            <Input id="repo" value={repo} onChange={(e) => setRepo(e.target.value)}
                   placeholder="git@git.example.vn:payments/api.git" />
            <p className="text-[11px] leading-tight text-muted-foreground">
              A reference, never a clone. The platform does not fetch, clone or store source code and
              holds no Git credentials (ADR-024) — this is a name in an inventory.
            </p>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="branch">Branch</Label>
            <Input id="branch" value={branch} onChange={(e) => setBranch(e.target.value)}
                   placeholder="main" />
            <p className="text-[11px] leading-tight text-muted-foreground">
              Recorded against this project's link to the repository, not against the repository — one
              repository builds several projects from several branches.
            </p>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Declared fields</CardTitle>
          <CardDescription>
            Configured by this tenant, not built into the product (ADR-027). An administrator adds a
            field once and every project gains it — including the dropdown values, which is why two
            people recording the same fact record it the same way.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <AttributeFields fields={editor.fields} values={attributes} disabled={busy}
                           onChange={(key, value) =>
                             setAttributes((prev) => ({ ...prev, [key]: value }))} />
        </CardContent>
      </Card>

      <div className="flex gap-2">
        <Button onClick={save} disabled={busy || !name.trim()}>
          {busy && <Loader2 className="size-3 animate-spin" />} Save
        </Button>
        <Button variant="ghost" asChild><Link to={`/projects/${id}`}>Cancel</Link></Button>
      </div>
    </div>
  );
}
