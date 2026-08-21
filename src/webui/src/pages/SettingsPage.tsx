import { Link, useSearchParams } from "react-router-dom";
import { CalendarCog, Settings2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { AiProviders } from "@/components/AiProviders";
import { AlertSubscriptions, RescanSchedule } from "@/components/ServiceCredentials";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { EndpointEnvironments } from "@/components/EndpointEnvironments";
import { FieldCatalogue } from "@/components/FieldCatalogue";

/**
 * Platform configuration — how the platform behaves, as distinct from who may use it.
 *
 * <h2>The line this page draws, and where it deliberately does not fall</h2>
 *
 * Access answers **who**: which people exist, what roles they hold, which projects and teams they are
 * accountable for, and which non-interactive identities may call in. Settings answers **how the
 * platform behaves**: where it sends things, what it goes and looks at on its own, and which third
 * parties it talks to.
 *
 * That line puts three surfaces here and deliberately leaves one behind:
 *
 * <ul>
 *   <li><b>AI model providers</b> — new. A model endpoint plus a credential, and a decision about what
 *       may leave the platform.
 *   <li><b>Vulnerability alert destinations</b> — moved from Access. A webhook destination is not an
 *       identity; nobody signs in as it and it holds no permission. It decides what leaves.
 *   <li><b>Scheduled re-scanning</b> — moved from Access. A cadence is behaviour, not authorization.
 *   <li><b>Ingestion credentials stay in Access.</b> The comment already in that page is right: a
 *       signing credential IS a non-interactive identity with a permission and a scope, and the fact
 *       that pipelines use it does not make it configuration. Moving it here to make this page tidier
 *       would file an access-control object under settings.
 * </ul>
 *
 * <h2>Why the review interval is linked rather than moved</h2>
 *
 * The assessment review interval is configuration by every test above, and it is the one thing here
 * that is NOT on this page. It stays on the planning dashboard because it is the setting that page is
 * entirely derived from — every due date and overdue state is computed from it — and somebody reading
 * a due date they disagree with needs the number in arm's reach, not two screens away. Duplicating
 * the editor would be worse than either choice: two forms writing one row diverge in validation and
 * one of them eventually becomes the wrong one. So it is linked from here, once.
 */
export function SettingsPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get("tab");
  const tab = requested === "integrations" ? "integrations"
    : requested === "fields" ? "fields" : "ai";

  return (
    <div className="flex flex-col gap-4 p-4 md:p-6">
      <header>
        <h1 className="flex items-center gap-2 text-lg font-semibold tracking-tight">
          <Settings2 className="size-5 text-primary" /> Configuration
        </h1>
        <p className="text-sm text-muted-foreground">
          How the platform behaves — what it sends out, what it looks at on its own, and which
          third-party models it may use. Who may use the platform is under Access.
        </p>
      </header>

      <div className="flex gap-1 border-b border-border">
        <TabLink active={tab === "ai"} onClick={() => setParams({}, { replace: true })}>
          AI models
        </TabLink>
        <TabLink active={tab === "fields"}
                 onClick={() => setParams({ tab: "fields" }, { replace: true })}>
          Asset fields
        </TabLink>
        <TabLink active={tab === "integrations"}
                 onClick={() => setParams({ tab: "integrations" }, { replace: true })}>
          Outbound and scheduled
        </TabLink>
      </div>

      {tab === "fields" ? (
        // Both halves of the inventory's vocabulary, on one tab. A declared field is what the
        // platform asks ABOUT an asset; an endpoint environment is where it asks for a host. They are
        // administered by the same person under the same permission, and a separate tab for the
        // second would hide it from whoever came here to configure the first.
        <div className="flex flex-col gap-6">
          <FieldCatalogue />
          <EndpointEnvironments />
        </div>
      ) : tab === "ai" ? (
        <div className="flex flex-col gap-5">
          <AiProviders />
          <Card>
            <CardHeader className="pb-2">
              <CardTitle>What an agent will and will not be allowed to do</CardTitle>
              <CardDescription>
                Stated here rather than left to be discovered, because these are the rules the
                dashboard agents are being built against.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <ul className="flex flex-col gap-2 text-xs text-muted-foreground">
                <li>
                  <span className="font-medium text-foreground">It never produces a number.</span>{" "}
                  An agent writes the prose; every figure in it is bound to a field from a query. A
                  model that computes a severity count is a model that can be wrong about one, with
                  nothing to check it against.
                </li>
                <li>
                  <span className="font-medium text-foreground">It only ever suggests.</span>{" "}
                  Output lands in a suggestion ledger. Anything entering the record of what happened
                  does so as an attributed human action, not as a model's write.
                </li>
                <li>
                  <span className="font-medium text-foreground">Nothing runs on page load.</span>{" "}
                  Analysis is requested or scheduled, never invoked because somebody opened a
                  dashboard — otherwise cost and third-party egress become a function of browsing.
                </li>
                <li>
                  <span className="font-medium text-foreground">
                    Every statement carries what produced it.
                  </span>{" "}
                  Provider, model and prompt version, alongside the figures the statement rests on. An
                  observation nobody can challenge is an opinion.
                </li>
                <li>
                  <span className="font-medium text-foreground">
                    Ingested text is treated as hostile.
                  </span>{" "}
                  Finding content legitimately contains attacker-authored strings — that is what a
                  finding is. Anything reaching a model is data to be described, never instructions to
                  be followed, which is why record content is withheld by default above.
                </li>
              </ul>
            </CardContent>
          </Card>
        </div>
      ) : (
        <div className="flex flex-col gap-5">
          {/* What leaves. */}
          <AlertSubscriptions />
          {/* What the platform goes and looks at on its own. */}
          <RescanSchedule />
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2">
                <CalendarCog className="size-4 text-primary" /> Assessment review interval
              </CardTitle>
              <CardDescription>
                Configuration, but edited on the dashboard it governs.
              </CardDescription>
            </CardHeader>
            <CardContent className="text-xs text-muted-foreground">
              How long an application at each criticality may go between full reviews is set at the
              foot of the{" "}
              <Link to="/planning" className="text-primary hover:underline">assessment plan</Link>,
              beside the due dates it computes. It is linked rather than copied here: two forms
              writing one row diverge in validation, and one of them becomes the wrong one.
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  );
}

function TabLink({ active, onClick, children }: {
  active: boolean; onClick: () => void; children: React.ReactNode;
}) {
  return (
    <button type="button" onClick={onClick}
            className={cn("-mb-px border-b-2 px-3 py-2 text-sm transition-colors",
              active ? "border-primary font-medium text-primary"
                     : "border-transparent text-muted-foreground hover:text-foreground")}>
      {children}
    </button>
  );
}
