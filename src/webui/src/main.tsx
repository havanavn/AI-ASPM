import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { api } from "@/lib/api";
import type { Session } from "@/lib/types";
import { Shell } from "@/components/Shell";
import { BoardPage } from "@/pages/BoardPage";
import { RequestPage } from "@/pages/RequestPage";
import { FindingPage } from "@/pages/FindingPage";
import { ApplicationsPage } from "@/pages/ApplicationsPage";
import { ApplicationPage } from "@/pages/ApplicationPage";
import { ApplicationEditPage } from "@/pages/ApplicationEditPage";
import { OrganizationPage } from "@/pages/OrganizationPage";
import { OverviewPage } from "@/pages/OverviewPage";
import { WorkloadPage } from "@/pages/WorkloadPage";
import { CompositionPage } from "@/pages/CompositionPage";
import { PlanningPage } from "@/pages/PlanningPage";
import { SettingsPage } from "@/pages/SettingsPage";
import { VulnerabilitiesPage } from "@/pages/VulnerabilitiesPage";
import { PipelinePage } from "@/pages/PipelinePage";
import { PipelineFindingPage } from "@/pages/PipelineFindingPage";
import { ProjectsPage } from "@/pages/ProjectsPage";
import { NewRequestPage } from "@/pages/NewRequestPage";
import { ProjectPage } from "@/pages/ProjectPage";
import { AccountPage } from "@/pages/AccountPage";
import { GuidePage } from "@/pages/GuidePage";
import { ApiGuidePage } from "@/pages/ApiGuidePage";
import { AccessPage } from "@/pages/AccessPage";
import { AccessUserPage } from "@/pages/AccessUserPage";
import { RolesPage } from "@/pages/RolesPage";
import { RolePage } from "@/pages/RolePage";
import { TooltipProvider } from "@/components/ui/tooltip";
import "./index.css";

function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    api.get<Session>("/api/ui/session").then(setSession).catch(() => setFailed(true));
  }, []);

  if (failed) {
    return (
      <div className="grid min-h-screen place-items-center p-6 text-center text-sm text-muted-foreground">
        The interface could not reach the platform. Reload once the service is available.
      </div>
    );
  }
  if (!session) return <div className="grid min-h-screen place-items-center text-sm text-muted-foreground">Loading…</div>;

  return (
    <TooltipProvider delayDuration={200}>
      <Routes>
        <Route element={<Shell session={session} />}>
          {/* The overview is the landing page again, now that it is converted — it is the page the
              sign-in redirect and every breadcrumb root already point at. */}
          <Route index element={<Navigate to="/overview" replace />} />
          <Route path="overview" element={<OverviewPage />} />
          <Route path="workload" element={<WorkloadPage />} />
          {/* One dashboard. Analytics was a second page answering the same questions;
              the old link keeps working rather than 404ing on somebody's bookmark. */}
          <Route path="analytics" element={<Navigate to="/workload" replace />} />
          <Route path="planning" element={<PlanningPage />} />
          <Route path="vulnerabilities" element={<VulnerabilitiesPage />} />
          <Route path="pipeline" element={<PipelinePage />} />
          <Route path="pipeline/findings/:id" element={<PipelineFindingPage />} />
          <Route path="settings" element={<SettingsPage />} />
          <Route path="composition" element={<CompositionPage />} />
          <Route path="board" element={<BoardPage />} />
          <Route path="board/:id" element={<RequestPage />} />
          <Route path="board/:id/findings/:findingId" element={<FindingPage />} />
          <Route path="applications" element={<ApplicationsPage />} />
          {/* Before applications/:id, so the literal segment wins over the parameter. A route
              order that let "new" be read as an identifier would 404 the create form. */}
          <Route path="applications/new" element={<ApplicationEditPage />} />
          <Route path="applications/:id" element={<ApplicationPage />} />
          <Route path="applications/:id/edit" element={<ApplicationEditPage />} />
          <Route path="projects" element={<ProjectsPage />} />
          <Route path="projects/:id" element={<ProjectPage />} />
          <Route path="requests/new" element={<NewRequestPage />} />
          <Route path="organization" element={<OrganizationPage />} />
          <Route path="account" element={<AccountPage />} />
          {/* The guide had no route here at all, so the sidebar link matched the catch-all below and
              drew an empty page over a document that has existed for weeks. */}
          <Route path="guide" element={<GuidePage />} />
          <Route path="api-guide" element={<ApiGuidePage />} />
          <Route path="access" element={<AccessPage />} />
          <Route path="access/users/:id" element={<AccessUserPage />} />
          <Route path="roles" element={<RolesPage />} />
          <Route path="roles/:id" element={<RolePage />} />
          {/* There is no other interface to hand off to. The server refuses a first segment this
              router does not declare, so reaching here means a shape it owns with nothing behind it. */}
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>
    </TooltipProvider>
  );
}

function NotFound() {
  return (
    <div className="p-6 text-sm text-muted-foreground">
      <p className="mb-2 font-medium text-foreground">That page does not exist.</p>
      <p>Check the address, or <a className="underline" href="/overview">start from the overview</a>.</p>
    </div>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
