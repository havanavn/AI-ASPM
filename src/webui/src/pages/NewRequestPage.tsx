import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Plus, Trash2, TriangleAlert } from "lucide-react";
import { DateField } from "@/components/DateField";
import { api, ApiError } from "@/lib/api";
import type { ProjectRow } from "@/pages/ProjectsPage";
import { Combobox } from "@/components/Combobox";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

/** Two accounts per role, and the server refuses fewer. See IntakeService. */
const ACCOUNTS_PER_ROLE = 2;

interface AccountDraft { username: string; password: string; mfaEnrolled: boolean; mfaBypassRef: string }
interface RoleDraft { roleName: string; description: string; accounts: AccountDraft[] }
interface EnvDraft {
  envType: string; baseUrl: string; vpnRequired: boolean;
  protectiveControlPresent: boolean; bypassArranged: boolean; bypassMethod: string;
  testWindowConstraints: string;
}

const emptyAccount = (): AccountDraft =>
  ({ username: "", password: "", mfaEnrolled: false, mfaBypassRef: "" });
const emptyRole = (): RoleDraft =>
  ({ roleName: "", description: "", accounts: [emptyAccount(), emptyAccount()] });
const emptyEnv = (): EnvDraft => ({
  envType: "UAT", baseUrl: "", vpnRequired: false, protectiveControlPresent: false,
  bypassArranged: false, bypassMethod: "", testWindowConstraints: "",
});

/**
 * Raising an assessment request.
 *
 * **The requester names a project and nothing else about where it lives.** The application and the
 * organization are filled in from the project through the composition graph and the ownership edge,
 * and they are shown read-only rather than hidden — a requester should be able to see that they
 * picked the right thing, and being able to *edit* them is how a request ends up filed against an
 * application nobody who owns it can see.
 *
 * **The picker only offers projects in your scope, and that is not the control.** The server re-reads
 * the project before it writes anything (product principle 4). A filtered picker is a usability
 * feature; every check that matters happens again on the other side.
 */
export function NewRequestPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();

  const [projects, setProjects] = useState<ProjectRow[]>([]);
  /** Every project, for showing what a full review would cover — not for the picker. */
  const [allProjects, setAllProjects] = useState<ProjectRow[]>([]);
  const [projectId, setProjectId] = useState(params.get("project") ?? "");
  // Pre-filled from the assessment plan's "Schedule" action. The plan can say what KIND of review is
  // owed and when, because both are derived from the tenant's own interval; it deliberately cannot
  // say which project, because scope is the one thing a client must never assert on the user's
  // behalf (product principle 4). So these three arrive filled and the project usually does not.
  const [title, setTitle] = useState(params.get("title") ?? "");
  const [detail, setDetail] = useState("");
  const [apiCount, setApiCount] = useState("");
  const [gitRepository, setGitRepository] = useState("");
  const [technologyStack, setTechnologyStack] = useState("");
  const [notes, setNotes] = useState("");
  const [roles, setRoles] = useState<RoleDraft[]>([emptyRole()]);
  const [trigger, setTrigger] = useState(params.get("trigger") ?? "");
  const [dueAt, setDueAt] = useState(params.get("due") ?? "");
  const [triggers, setTriggers] = useState<{ id: string; label: string; countsAsFullReview: boolean }[]>([]);
  const [environments, setEnvironments] = useState<EnvDraft[]>([emptyEnv()]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; field: string | null } | null>(null);

  useEffect(() => {
    // Only the projects this caller may RAISE against, not every project they can see. The two are
    // different sets, and offering the wider one meant filling the entire form and being refused at
    // the end — a control that silently loses somebody's work, which is the thing this codebase
    // rejects everywhere else. The server decides which is which; this only stops the form asking a
    // question whose answer is already no.
    api.get<{ rows: ProjectRow[]; raisable: string[] }>("/api/ui/projects")
      .then((d) => {
        setAllProjects(d.rows);
        setProjects(d.rows.filter((r) => d.raisable.includes(r.id)));
      })
      .catch((e) => setError({ message: e.message, field: null }));
    // The reasons this tenant recognises, and which of them mean the whole application.
    api.get<{ triggers: { id: string; label: string; countsAsFullReview: boolean }[] }>(
      "/api/ui/board").then((d) => setTriggers(d.triggers)).catch(() => setTriggers([]));
  }, []);

  const project = useMemo(
    () => projects.find((p) => p.id === projectId) ?? null, [projects, projectId]);

  function patchRole(index: number, patch: Partial<RoleDraft>) {
    setRoles((current) => current.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  }
  function patchAccount(role: number, account: number, patch: Partial<AccountDraft>) {
    setRoles((current) => current.map((r, i) => i !== role ? r : {
      ...r, accounts: r.accounts.map((a, j) => (j === account ? { ...a, ...patch } : a)),
    }));
  }
  function patchEnv(index: number, patch: Partial<EnvDraft>) {
    setEnvironments((current) => current.map((e, i) => (i === index ? { ...e, ...patch } : e)));
  }

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const created = await api.post<{ id: string; code: string }>("/api/ui/requests", {
        title, projectId, triggerId: trigger || null, detail, dueAt,
        apiCount: apiCount.trim() === "" ? null : Number(apiCount),
        gitRepository, technologyStack, notes,
        roles: roles.map((r) => ({
          roleName: r.roleName, description: r.description,
          accounts: r.accounts.filter((a) => a.username.trim() !== ""),
        })),
        environments: environments.filter((e) => e.baseUrl.trim() !== ""),
      });
      navigate(`/board/${created.id}`);
    } catch (e) {
      if (e instanceof ApiError) {
        // The server names the field it refused. Put the message where the person can act on it
        // rather than at the top of a form they then have to search.
        const body = e as ApiError & { field?: string };
        setError({ message: e.message, field: body.field ?? null });
      } else {
        setError({ message: String(e), field: null });
      }
    } finally {
      setBusy(false);
    }
  }

  // Only a role somebody actually started. Accounts are optional now — an unauthenticated surface
  // review needs none — so an untouched role block must not block submission.
  const started = roles.filter(
    (r) => r.roleName.trim() !== "" || r.accounts.some((a) => a.username.trim() !== ""));
  const rolesShort = started.filter(
    (r) => r.accounts.filter((a) => a.username.trim() !== "").length < ACCOUNTS_PER_ROLE);
  const fullReview = triggers.find((t) => t.id === trigger)?.countsAsFullReview ?? false;
  const siblings = project?.applicationId
    ? allProjects.filter((p) => p.applicationId === project.applicationId) : [];

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold tracking-tight">Request an assessment</h1>
        <p className="text-xs text-muted-foreground">
          Pick the project. Everything an assessor needs to actually start is on this form, because a
          request that arrives without it waits.
        </p>
      </div>

      {projects.length === 0 && !error && (
        <Card className="border-tone-warn/40">
          <CardContent className="text-sm">
            There is no project you can raise a request against. Being able to see a project and
            being allowed to ask for work against it are separate — a project's owner, or the
            security team, grants the second on the project's own page.
          </CardContent>
        </Card>
      )}

      {error && (
        <Card className="border-destructive/50">
          <CardContent className="flex items-start gap-2 text-sm text-destructive">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" />
            <span>{error.message}</span>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader><CardTitle>What, and for whom</CardTitle></CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <Label htmlFor="title">Title</Label>
            <Input id="title" value={title} maxLength={200}
                   placeholder="Quarterly penetration test — refunds"
                   onChange={(e) => setTitle(e.target.value)} />
          </div>

          <div className="grid gap-4 sm:grid-cols-3">
            <div className="flex flex-col gap-1">
              <Label>Project</Label>
              <Combobox
                items={projects.map((p) => ({
                  id: p.id, name: p.name,
                  hint: [p.applicationName, p.owningNodeName].filter(Boolean).join(" · "),
                }))}
                value={projectId} onChange={setProjectId}
                placeholder="Search a project" clearLabel="" />
            </div>
            {/* Derived, and shown so the requester can see they picked the right thing. Read-only
                because these are answers the graph gives, not answers a requester has. */}
            <Derived label="Application" value={project?.applicationName ?? null}
                     missing="No application above this project" />
            <Derived label="Organization" value={project?.owningNodeName ?? null}
                     missing="This project has no owning team" />
          </div>

          {project && project.ownerAncestors.length > 0 && (
            <p className="text-[11px] text-muted-foreground">
              Filed under {project.ownerAncestors.join(" › ")} › {project.owningNodeName}
            </p>
          )}

          <div className="grid gap-4 sm:grid-cols-3">
            <div className="flex flex-col gap-1 sm:col-span-2">
              <Label>Why this is being assessed</Label>
              <Select value={trigger} onValueChange={setTrigger}>
                <SelectTrigger><SelectValue placeholder="Choose a reason" /></SelectTrigger>
                <SelectContent>
                  {triggers.map((t) => (
                    <SelectItem key={t.id} value={t.id}>
                      {t.label}{t.countsAsFullReview ? " ★" : ""}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor="due">Needed by</Label>
              <DateField id="due" value={dueAt} onChange={setDueAt} />
              {/* Whose date this is, said plainly. A requester who thinks they are setting a
                  commitment and finds it moved reads that as the platform ignoring them. */}
              <p className="text-[11px] text-muted-foreground">
                What you need. The assessor sets the real date once scoped.
              </p>
            </div>
          </div>

          {/* A full application review covers everything under the application, so the sibling
              projects are listed BEFORE submission rather than discovered afterwards — the scope of
              a review is the thing a requester most needs to see they got right. Switching back to
              a narrower reason returns to the single project already chosen; it is never cleared. */}
          {fullReview && project?.applicationId && (
            <div className="rounded-md border border-tone-info/40 bg-tone-info/5 p-3 text-xs">
              <strong>Whole application.</strong> This covers {project.applicationName} and every
              project under it:
              <ul className="mt-1.5 flex flex-wrap gap-1.5">
                {siblings.map((sp) => (
                  <li key={sp.id}>
                    <Badge tone={sp.id === project.id ? "info" : "neutral"}>{sp.name}</Badge>
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div className="flex flex-col gap-1">
            <Label htmlFor="detail">What is to be assessed</Label>
            <textarea id="detail" value={detail} rows={5}
                      onChange={(e) => setDetail(e.target.value)}
                      placeholder="The flows, the boundaries, anything out of bounds, and what changed since the last assessment."
                      className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring" />
            <p className="text-[11px] text-muted-foreground">
              An assessor cannot scope from a title. Say what changed and what must not be touched.
            </p>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Accounts, by role</CardTitle>
          <CardDescription>
            Every role needs <strong>two</strong> accounts at the same privilege level. One account
            cannot be used to test whether a user can reach another user's data, and that is the
            defect class this whole platform exists to find.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-5">
          {roles.map((role, roleIndex) => {
            const filled = role.accounts.filter((a) => a.username.trim() !== "").length;
            return (
              <div key={roleIndex} className="flex flex-col gap-3 rounded-md border p-3">
                <div className="flex flex-wrap items-end gap-3">
                  <div className="flex min-w-44 flex-1 flex-col gap-1">
                    <Label>Role name</Label>
                    <Input value={role.roleName} placeholder="Administrator, Merchant, Read-only…"
                           onChange={(e) => patchRole(roleIndex, { roleName: e.target.value })} />
                  </div>
                  <div className="flex min-w-52 flex-[2] flex-col gap-1">
                    <Label>What this role can do</Label>
                    <Input value={role.description} placeholder="Optional, but it shapes the test"
                           onChange={(e) => patchRole(roleIndex, { description: e.target.value })} />
                  </div>
                  <Badge tone={filled >= ACCOUNTS_PER_ROLE ? "ok" : "warn"}>
                    {filled} of {ACCOUNTS_PER_ROLE}
                  </Badge>
                  {roles.length > 1 && (
                    <Button variant="ghost" size="sm" aria-label="Remove this role"
                            onClick={() => setRoles(roles.filter((_, i) => i !== roleIndex))}>
                      <Trash2 />
                    </Button>
                  )}
                </div>

                {role.accounts.map((account, accountIndex) => (
                  <div key={accountIndex} className="grid gap-2 sm:grid-cols-12">
                    <div className="flex flex-col gap-1 sm:col-span-4">
                      <Label className="text-[11px]">Username</Label>
                      <Input value={account.username}
                             onChange={(e) => patchAccount(roleIndex, accountIndex, { username: e.target.value })} />
                    </div>
                    <div className="flex flex-col gap-1 sm:col-span-5">
                      <Label className="text-[11px]">Password</Label>
                      <Input type="password" value={account.password} autoComplete="off"
                             onChange={(e) => patchAccount(roleIndex, accountIndex, { password: e.target.value })} />
                    </div>
                    <div className="flex items-end gap-2 sm:col-span-3">
                      <label className="flex items-center gap-1.5 pb-2 text-xs">
                        <input type="checkbox" checked={account.mfaEnrolled}
                               onChange={(e) => patchAccount(roleIndex, accountIndex, { mfaEnrolled: e.target.checked })} />
                        Second factor
                      </label>
                      {account.mfaEnrolled && (
                        <Input value={account.mfaBypassRef} placeholder="How to satisfy it"
                               onChange={(e) => patchAccount(roleIndex, accountIndex, { mfaBypassRef: e.target.value })} />
                      )}
                    </div>
                  </div>
                ))}

                <div>
                  <Button variant="outline" size="sm"
                          onClick={() => patchRole(roleIndex,
                            { accounts: [...role.accounts, emptyAccount()] })}>
                    <Plus /> Another account
                  </Button>
                </div>
              </div>
            );
          })}
          <div>
            <Button variant="outline" size="sm" onClick={() => setRoles([...roles, emptyRole()])}>
              <Plus /> Another role
            </Button>
          </div>
          {/* The promise, stated where the password is typed rather than in a policy page nobody
              opens. It is kept by the transition itself: closing the request destroys the value in
              the same transaction, so there is no window where the board shows a closed request
              whose password is still held. */}
          <div className="rounded-md border border-tone-info/40 bg-tone-info/5 p-3 text-xs">
            <strong>These passwords are destroyed when the request is closed.</strong> They are
            encrypted while held, never appear in an export, a notification or a report, and are
            masked everywhere until somebody with the reveal permission asks for them — which is
            recorded. Rotate them on your side afterwards anyway: destroying our copy does not
            change the password on your system.
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Where it runs</CardTitle>
          <CardDescription>
            A protective control in front of a test target means the assessment measures the control.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {environments.map((environment, index) => (
            <div key={index} className="flex flex-col gap-3 rounded-md border p-3">
              <div className="flex flex-wrap items-end gap-3">
                <div className="flex w-40 flex-col gap-1">
                  <Label>Environment</Label>
                  <Select value={environment.envType}
                          onValueChange={(v) => patchEnv(index, { envType: v })}>
                    <SelectTrigger><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="UAT">UAT</SelectItem>
                      <SelectItem value="STAGING">Staging</SelectItem>
                      <SelectItem value="PREPROD">Pre-production</SelectItem>
                      <SelectItem value="PROD_READONLY">Production, read only</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="flex min-w-64 flex-1 flex-col gap-1">
                  <Label>Address</Label>
                  <Input value={environment.baseUrl} placeholder="https://uat.payments.example"
                         onChange={(e) => patchEnv(index, { baseUrl: e.target.value })} />
                </div>
                {environments.length > 1 && (
                  <Button variant="ghost" size="sm" aria-label="Remove this environment"
                          onClick={() => setEnvironments(environments.filter((_, i) => i !== index))}>
                    <Trash2 />
                  </Button>
                )}
              </div>
              <div className="flex flex-wrap items-center gap-4 text-xs">
                <label className="flex items-center gap-1.5">
                  <input type="checkbox" checked={environment.vpnRequired}
                         onChange={(e) => patchEnv(index, { vpnRequired: e.target.checked })} />
                  VPN required
                </label>
                <label className="flex items-center gap-1.5">
                  <input type="checkbox" checked={environment.protectiveControlPresent}
                         onChange={(e) => patchEnv(index, { protectiveControlPresent: e.target.checked })} />
                  WAF or similar in front
                </label>
                {environment.protectiveControlPresent && (
                  <>
                    <label className="flex items-center gap-1.5">
                      <input type="checkbox" checked={environment.bypassArranged}
                             onChange={(e) => patchEnv(index, { bypassArranged: e.target.checked })} />
                      Bypass arranged
                    </label>
                    <Input className="h-8 w-64" value={environment.bypassMethod}
                           placeholder="How the bypass works"
                           onChange={(e) => patchEnv(index, { bypassMethod: e.target.value })} />
                  </>
                )}
              </div>
              <Input value={environment.testWindowConstraints}
                     placeholder="Test window constraints, if any"
                     onChange={(e) => patchEnv(index, { testWindowConstraints: e.target.value })} />
            </div>
          ))}
          <div>
            <Button variant="outline" size="sm"
                    onClick={() => setEnvironments([...environments, emptyEnv()])}>
              <Plus /> Another environment
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Technical profile</CardTitle></CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-3">
          <div className="flex flex-col gap-1">
            <Label htmlFor="apis">Number of API endpoints</Label>
            <Input id="apis" inputMode="numeric" value={apiCount}
                   onChange={(e) => setApiCount(e.target.value.replace(/[^0-9]/g, ""))} />
            <p className="text-[11px] text-muted-foreground">Drives the effort estimate.</p>
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="repo">Git repository</Label>
            <Input id="repo" value={gitRepository} placeholder="group/payments-api"
                   onChange={(e) => setGitRepository(e.target.value)} />
            {/* The platform never clones it. Recorded so an assessor knows where the code lives. */}
            <p className="text-[11px] text-muted-foreground">
              Recorded, never cloned — the platform stores no repository credentials.
            </p>
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="stack">Technology stack</Label>
            <Input id="stack" value={technologyStack} placeholder="Java 21, Postgres, React"
                   onChange={(e) => setTechnologyStack(e.target.value)} />
          </div>
          <div className="flex flex-col gap-1 sm:col-span-3">
            <Label htmlFor="notes">Anything else an assessor should know</Label>
            <Input id="notes" value={notes} onChange={(e) => setNotes(e.target.value)} />
          </div>
        </CardContent>
      </Card>

      <div className="flex items-center justify-between gap-4">
        <p className="text-xs text-muted-foreground">
          {rolesShort.length > 0
            ? `${rolesShort.length} role${rolesShort.length === 1 ? "" : "s"} still short of ${ACCOUNTS_PER_ROLE} accounts.`
            : started.length === 0
              ? "No test accounts — fine for an unauthenticated review."
              : "Ready to submit."}
        </p>
        <Button disabled={busy} onClick={submit}>
          {busy ? "Raising…" : "Raise the request"}
        </Button>
      </div>
    </div>
  );
}

function Derived({ label, value, missing }: { label: string; value: string | null; missing: string }) {
  return (
    <div className="flex flex-col gap-1">
      <Label>{label}</Label>
      <div className="flex h-9 items-center rounded-md border border-dashed px-3 text-sm">
        {value ?? <span className="text-xs italic text-tone-unknown">{missing}</span>}
      </div>
      <p className="text-[11px] text-muted-foreground">Filled in from the project.</p>
    </div>
  );
}
