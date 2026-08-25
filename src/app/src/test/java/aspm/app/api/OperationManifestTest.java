package aspm.app.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.resource.ResourceCatalogue;
import aspm.app.resource.ResourceGroup;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Writes {@code _traceability/api-operations.csv} from the real registry. {@code PRD-PLT-012}.
 *
 * <p>Backward traceability asks whether every API operation traces to a requirement, and the matrix
 * generator used to answer it by parsing {@code PlatformOperations.java} for constructor calls. That
 * worked while operations were listed one by one and <b>silently became wrong</b> the moment they were
 * derived from a catalogue: the parser found four calls inside a loop and reported four operations where
 * the platform serves twenty-three.
 *
 * <p>A parser guesses at what the code will do. This runs it. The manifest is written by the build, so it
 * cannot describe a registry that no longer exists — and if the build does not run, the file's timestamp
 * says so.
 */
class OperationManifestTest {

    /**
     * Each group's requirement citations.
     *
     * <p>Checked against the register twice over. An earlier version of this table cited a
     * configuration identifier for asset types that <b>does not exist</b>: no compiler catches a
     * requirement identifier inside a string, and no reviewer did either. A citation that names nothing
     * is worse than none, because it reads as traced.
     *
     * <p>Two checks now cover it, and they cover different things. S13 in {@code :architecture-tests}
     * scans Java and SQL sources — it caught the identifier a second time when this very comment quoted
     * it, which is why the comment describes it rather than repeating it. {@code generate_matrix.py}
     * checks the emitted manifest, which is data rather than source and outside S13's reach.
     */
    private static final Map<String, List<String>> TRACES = Map.of(
            "org-node-types", List.of("CFG-ORG-001", "PRD-ORG-004"),
            "criticality-tiers", List.of("CFG-ORG-001", "PRD-ORG-006"),
            "org-nodes", List.of("PRD-ORG-001", "SEC-AUZ-010", "SEC-AUZ-016"),
            "asset-types", List.of("PRD-AST-001", "PRD-AST-002"),
            "assets", List.of("PRD-AST-001", "PRD-AST-008", "SEC-AUZ-016"),
            "findings", List.of("PRD-VUL-001", "SEC-AUZ-016"),
            "requests", List.of("PRD-PTR-021", "PRD-ASM-003", "SEC-AUZ-016"));

    @Test
    @DisplayName("PRD-PLT-012: the manifest is written from the registry, not parsed from source")
    void writeManifest() throws IOException {
        OperationRegistry registry = PlatformOperations.registry();
        assertFalse(registry.all().isEmpty(), "an empty registry writes an empty manifest, and a gate "
                + "over an empty set passes for the wrong reason");

        List<String> lines = new ArrayList<>();
        lines.add("method,path,annotation_class,permission,resource_group,traces_to");
        for (OperationRegistry.Operation operation : registry.all()) {
            String group = groupOf(operation.pathTemplate());
            // Operations belonging to no resource group trace to the rule they are written against.
            // The service document exists because of PRD-API-036 — it is what stops the friendly
            // endpoint disclosing the operation list. The interface assets and the sign-in page exist
            // because of DOC-08's accessibility and internationalization obligations.
            List<String> traces = switch (operation.pathTemplate()) {
                // The service document, at /api since the root became the interface.
                case "/api" -> List.of("PRD-API-036");
                case "/style.css" -> List.of("PRD-UIX-006", "INT-UIX-004");
                // Session housekeeping. SEC-SEC-012 owns the session list this prunes, SEC-SEC-016 the
                // expiry it prunes by, and OPS-DEP-007 the reason it is a call on an existing tick
                // rather than a timer inside a tier that runs in more than one replica.
                case "/api/v1/session-reap" ->
                        List.of("SEC-SEC-012", "SEC-SEC-016", "OPS-DEP-007");
                // The product mark. PRD-UIX-006 is the single design language it belongs to, and
                // INT-UIX-004 the accessibility floor its alt text and contrast answer to. One file
                // behind two URLs, so the two interfaces cannot show different logos.
                case "/brand/logo.svg", "/brand/icon-180.png" ->
                        List.of("PRD-UIX-006", "INT-UIX-004");
                case "/app.js" -> List.of("PRD-UIX-013", "INT-UIX-003");
                // ADR-059's authentication surfaces. Each traces to the requirement that shapes it:
                // SEC-SEC-016 forbids the reset path disclosing existence, SEC-SEC-003 makes the second
                // factor requirable, SEC-SEC-009 regenerates the session on privilege change.
                // One address each now. These were listed twice — once under /ui, once under /app — while
                // the same gate answered at two prefixes.
                case "/sign-in" ->
                        List.of("SEC-SEC-005", "SEC-SEC-013", "SEC-TEN-004");
                case "/mfa" -> List.of("SEC-SEC-003", "SEC-SEC-009");
                case "/mfa-enrol" ->
                        List.of("SEC-SEC-003", "PRD-IAM-002");
                case "/forgot-password" -> List.of("SEC-SEC-016");
                case "/sign-out" -> List.of("SEC-SEC-011", "SEC-SEC-012");
                // The self-service surfaces. SEC-SEC-012 is the own-session list "with source context";
                // PRD-IAM-007 is the configurable credential policy; SEC-SEC-006 is the breach check at
                // set, which is where the change form applies it.
                case "/account" -> List.of("SEC-SEC-012", "PRD-IAM-007");
                case "/account/sessions/revoke" -> List.of("SEC-SEC-012", "SEC-SEC-011");
                case "/change-password" -> List.of("PRD-IAM-007", "SEC-SEC-006");
                // Step-up. PRD-IAM-003 requires it "for defined sensitive operations"; SEC-SEC-004
                // enumerates them, and "authorization configuration change" is why the grant routes are
                // class E rather than class B.
                case "/step-up" -> List.of("PRD-IAM-003", "SEC-SEC-004");
                // User administration. PRD-AUZ-002 is the reason a grant carries a scope at all:
                // authorization combines permission WITH a scope predicate, so a role assignment without
                // one is half an authorization.
                case "/users", "/users/{id}" ->
                        List.of("PRD-AUZ-001", "PRD-AUZ-002", "SEC-AUZ-017");
                case "/users/{id}/reset" -> List.of("SEC-SEC-016", "SEC-SEC-004");
                case "/users/{id}/roles", "/users/{id}/roles/revoke" ->
                        List.of("PRD-AUZ-002", "PRD-AUZ-003", "SEC-SEC-004");
                // Role composition. PRD-AUZ-001 is the product-fixed catalogue with tenant-composed
                // roles — the requirement that makes an editable role set correct rather than a liberty.
                case "/roles", "/roles/{id}", "/roles/{id}/retire",
                        "/roles/{id}/restore", "/roles/{id}/delete" ->
                        List.of("PRD-AUZ-001", "SEC-SEC-004");
                // The application inventory. PRD-AST-001 is the asset inventory itself; PRD-AST-008 is
                // the exposure record whose declared/observed conflict this list refuses to hide;
                // SEC-AUZ-016 is why the scope predicate is composed into the query rather than applied
                // to its result.
                // The technical estate. PRD-AST-005 is the ownership claim, which is what an unowned
                // asset is missing and what this page exists to surface.
                // The assessment board and the findings recorded on it. PRD-ASM-003 is the request;
                // PRD-VUL-001 the finding; SEC-AUZ-017 the object-level revalidation on every route
                // that reaches a finding through the request that produced it.
                case "/board/{id}/transitions", "/board/{id}/assign" ->
                        List.of("PRD-WRK-031", "PRD-WRK-032", "PRD-ASM-003");
                case "/board/{id}/findings/{findingId}/close" ->
                        List.of("PRD-VUL-001", "SEC-AUZ-017");
                case "/board", "/board/{id}", "/board/{id}/comments" ->
                        List.of("PRD-ASM-003", "PRD-PTR-021", "SEC-AUZ-016");
                case "/board/{id}/findings", "/board/{id}/findings/{findingId}",
                        "/board/{id}/findings/{findingId}/comments" ->
                        List.of("PRD-VUL-001", "PRD-ASM-003", "SEC-AUZ-017");
                case "/board/{id}/findings/{findingId}/accept",
                        "/board/{id}/findings/{findingId}/reopen" ->
                        List.of("PRD-VUL-001", "SEC-SEC-004");
                case "/applications", "/applications/new", "/applications/{id}",
                        "/applications/{id}/edit", "/applications/{id}/retire",
                        "/applications/{id}/components", "/applications/{id}/components/new",
                        "/applications/{id}/components/{componentId}",
                        "/applications/{id}/components/{componentId}/detach" ->
                        List.of("PRD-AST-001", "PRD-AST-008", "SEC-AUZ-016", "SEC-AUZ-017");
                // The hierarchy. PRD-ORG-001 is the org node; SEC-AUZ-010 and SEC-AUZ-016 are the scope
                // rules the tree exists to serve.
                case "/organization", "/organization/{id}", "/organization/{id}/deprecate" ->
                        List.of("PRD-ORG-001", "SEC-AUZ-010", "SEC-AUZ-016");
                case "/security-policy" -> List.of("PRD-IAM-007", "PRD-IAM-006", "SEC-SEC-006");
                // The retired prefixes, as redirects. PRD-API-036 is why they redirect rather than 404
                // selectively: an unrouted path answering 404 while a routed one redirected was an oracle
                // for which interface routes exist.
                case "/ui", "/ui/{a}", "/ui/{a}/{b}", "/ui/{a}/{b}/{c}", "/ui/{a}/{b}/{c}/{d}",
                     "/app", "/app/{a}", "/app/{a}/{b}", "/app/{a}/{b}/{c}", "/app/{a}/{b}/{c}/{d}" ->
                        List.of("PRD-API-036", "INT-UIX-003");
                case "/api/v1/requests/{id}/transitions", "/requests/{id}/transitions" ->
                        List.of("PRD-WRK-031", "PRD-WRK-032", "PRD-WRK-036");
                // ADR-023 makes the push API the only automated ingestion path; PRD-API-038 requires
                // the quality score and every warning in the response; PRD-API-039 forbids rejecting an
                // unknown artifact; PRD-SBM-056 is why coverage includes assets that never submitted.
                case "/api/v1/sbom-submissions" ->
                        List.of("PRD-API-038", "PRD-API-039", "PRD-SBM-037");
                // The scan-report door. PRD-ING-024 is the registered parser (a definition, not a code
                // branch); PRD-ING-027 is the whole-document rejection of an undeclared version;
                // PRD-ING-040 is why an unmappable severity is reported ungraded rather than defaulted;
                // PRD-ING-041 is the counts-by-disposition in the response.
                case "/api/v1/finding-imports" ->
                        List.of("PRD-ING-024", "PRD-ING-027", "PRD-ING-040", "PRD-ING-041");
                case "/api/v1/coverage-states", "/composition" ->
                        List.of("PRD-SBM-056", "PRD-SBM-032");
                case "/workload" ->
                        List.of("PRD-CAP-005", "PRD-CAP-008", "PRD-CAP-013", "PRD-CAP-014");
                // Inline images pasted into a write-up or a comment. PRD-ASM-007 is the obligation
                // (structured evidence attached per finding); PRD-ASM-012 is how such content must be
                // handled, and the rule applied here is stricter than the "validate declared type
                // against content signature" it asks for, because the declared type is never consulted
                // at all — the type comes from the bytes and nothing else.
                //
                // DEVIATION, recorded rather than hidden. PRD-API-034 requires attachment content to be
                // served from an origin distinct from the API, via short-lived signed references, with
                // non-inline disposition; PRD-PTR-005 requires a malware scan before content becomes
                // retrievable. Neither holds for this surface. An image rendered inside a paragraph
                // cannot have non-inline disposition, and no scanner is deployed. The controls that are
                // present — type derived from bytes, SVG refused, nosniff, and a per-response policy of
                // default-src 'none' with sandbox — attack the same risk by a different route, but they
                // are not those controls and this is not those requirements being met. The gap is
                // acceptable while uploads come from the assessing team and is not acceptable once this
                // surface accepts content from a requesting project team; that decision needs an ADR
                // before it does.
                // The React interface. The shell and its assets exist for the same reasons the
                // stylesheet and the progressive-enhancement script do — PRD-UIX-006's single design
                // language and INT-UIX-004's externalized presentation.
                case "/", "/{section}", "/{section}/{id}",
                     "/{section}/{id}/{sub}", "/{section}/{id}/{sub}/{subId}",
                     "/assets/{name}" ->
                        List.of("PRD-UIX-006", "INT-UIX-004");
                // The JSON behind it. It traces to the same requirements as the board it renders,
                // because it is the same board: PRD-PTR-021 is the request queue, PRD-ASM-003 the
                // assessment record, SEC-AUZ-016 the scope composed in retrieval rather than after it.
                case "/api/ui/session", "/api/ui/board", "/api/ui/board/{id}",
                     "/api/ui/board/{id}/transitions", "/api/ui/board/{id}/assign",
                     "/api/ui/people" ->
                        List.of("PRD-PTR-021", "PRD-ASM-003", "SEC-AUZ-016");
                // Comments and the finding write-up. PRD-ASM-007 is evidence attached per finding,
                // PRD-ASM-012 how such content must be handled; the same deviation recorded against
                // the attachment routes applies, and for the same reason.
                case "/api/ui/board/{id}/comments",
                     "/api/ui/board/{id}/findings/{findingId}",
                     "/api/ui/board/{id}/findings/{findingId}/comments" ->
                        List.of("PRD-ASM-007", "PRD-ASM-012", "PRD-VUL-001");
                // The inventory and the accountability tree, as JSON. Same requirements as the pages.
                case "/api/ui/applications", "/api/ui/applications/{id}" ->
                        List.of("PRD-AST-001", "PRD-AST-008", "SEC-AUZ-016");
                // The application security posture dashboard. PRD-UIX-011 is the dashboard whose
                // current scope and population must be legible, PRD-UIX-022 forbids rendering a
                // numeral for a value nothing measured — which is most of what this endpoint's null
                // handling exists for — and SEC-AUZ-016 is the scope decision, made once against the
                // application before any figure under it is computed.
                // Software composition. PRD-SBM-056 is the coverage requirement that keeps an asset
                // which never submitted visible; PRD-SBM-035 the canonicalized component identity the
                // searches resolve through; SEC-AUZ-016 the scope composed into every one of them.
                // Ingestion credentials. PRD-IAM-003 requires step-up for defined sensitive
                // operations and SEC-SEC-004 enumerates credential issuance among them; PRD-AUZ-002
                // is why the credential carries a pinned scope rather than inheriting one.
                case "/api/ui/service-credentials", "/api/ui/service-credentials/{id}/revoke" ->
                        List.of("PRD-IAM-003", "PRD-AUZ-002", "SEC-SEC-004");
                // Vulnerability alerts. PRD-NTF-001 is the notification obligation; SEC-AUZ-016 the
                // scope on the read; PRD-SBM-056 why the subscription carries a scope of its own.
                // Scheduled re-scanning. PRD-SBM-056 is the coverage requirement it exists to keep
                // honest — a snapshot never re-checked reports the knowledge of the day it was
                // pushed; PRD-SBM-035 the canonical component identity the results resolve through.
                case "/api/v1/rescans/pending", "/api/v1/rescans/{id}",
                     "/api/ui/rescan-schedule" ->
                        List.of("PRD-SBM-035", "PRD-SBM-056", "SEC-AUZ-016");
                case "/api/ui/alerts", "/api/ui/alerts/{id}/active" ->
                        List.of("PRD-NTF-001", "PRD-SBM-056", "SEC-AUZ-016");
                case "/api/ui/dependencies/export" ->
                        List.of("PRD-SBM-035", "PRD-SBM-056", "SEC-AUZ-016");
                case "/api/ui/dependencies/artifact/{id}/retire",
                     "/api/ui/dependencies/artifact/{id}/sbom" ->
                        List.of("PRD-AST-001", "PRD-SBM-056", "SEC-AUZ-017");
                case "/api/ui/dependencies", "/api/ui/dependencies/tree", "/api/ui/dependencies/node",
                     "/api/ui/dependencies/advisories", "/api/ui/dependencies/components",
                     "/api/ui/dependencies/locations", "/api/ui/dependencies/graph" ->
                        List.of("PRD-SBM-035", "PRD-SBM-056", "SEC-AUZ-016");
                // Planning the periodic assessment of applications. PRD-AST-001 is the application
                // inventory the plan is drawn over; PRD-ASM-003 the assessment record every bar on
                // the Gantt is; PRD-CAP-005 the capacity the plan has to fit inside; PRD-UIX-011 the
                // scope shown on a surface presenting scoped data; SEC-AUZ-016 the scope composed
                // into the retrieval rather than filtered afterwards, which matters more here than
                // usual — the rows ARE counts and the counts must be of what the caller may see.
                // The review interval the whole plan is measured against. PRD-ASM-002 is the
                // assessment types the interval governs; CFG-ASM-001 the tenant-configurable
                // assessment vocabulary this belongs to; SEC-AUZ-017 the step-up the write carries.
                // The vulnerability dashboard. PRD-VUL-013 is the recurrence this page can filter on,
                // PRD-VUL-015 the false-positive and acceptance state it distinguishes from a fix,
                // PRD-UIX-011 the scope shown on a surface presenting scoped data, and SEC-AUZ-016 the
                // predicate composed into every count rather than applied after it.
                // The suggestion ledger. PRD-AIC-056 is the rule that nothing is invoked on view;
                // CFG-AIC-001 the per-capability provider, model and permitted data category the
                // catalogue holds; SEC-AUZ-017 the re-read of a suggestion identifier at decision.
                case "/api/ui/suggestions", "/api/ui/suggestions/{id}/decide",
                     "/api/ui/agents/{code}/run", "/api/ui/agents/analyse" ->
                        List.of("PRD-AIC-056", "CFG-AIC-001", "SEC-AUZ-017");
                // Classification. PRD-VUL-015 owns the determination recorded against a finding,
                // PRD-AIC-056 the rule that nothing is invoked on view, and SEC-AUZ-016 the scope the
                // taxonomy is read under.
                case "/api/ui/top-weaknesses" ->
                        List.of("PRD-VUL-013", "PRD-UIX-011", "SEC-AUZ-016");
                case "/api/ui/findings/classify" ->
                        List.of("PRD-VUL-015", "PRD-AIC-056", "SEC-AUZ-016");
                case "/api/ui/vulnerabilities", "/api/ui/vulnerabilities/export" ->
                        List.of("PRD-VUL-013", "PRD-VUL-015", "PRD-UIX-011", "SEC-AUZ-016");
                // AI provider configuration. PRD-AIC-056 is the rule that a capability is never
                // invoked on view — the reason this stores configuration and calls nothing;
                // CFG-AIC-001 the tenant-configurable AI surface it belongs to; SEC-PTR-007 the
                // sealed custody the key is held under; SEC-AUZ-017 the step-up both writes carry.
                case "/api/ui/ai-providers", "/api/ui/ai-providers/{id}/active" ->
                        List.of("PRD-AIC-056", "CFG-AIC-001", "SEC-PTR-007", "SEC-AUZ-017");
                case "/api/ui/review-policy", "/api/ui/review-policy/{id}" ->
                        List.of("PRD-ASM-002", "CFG-ASM-001", "SEC-AUZ-017");
                case "/api/ui/assessment-plan" ->
                        List.of("PRD-AST-001", "PRD-ASM-003", "PRD-CAP-005", "PRD-UIX-011",
                                "SEC-AUZ-016");
                // The plan. PRD-ASM-015 is the dated window itself; -016 keeps it out of every
                // in-flight figure; -017 is several per year and the retained cancellation; -018 the
                // explicit conversion. SEC-AUZ-016 the scope predicate, applied over every target in
                // a batch inside the transaction that writes it.
                case "/api/ui/assessment-plan/windows",
                     "/api/ui/assessment-plan/windows/{id}" ->
                        List.of("PRD-ASM-015", "PRD-ASM-016", "PRD-ASM-017", "PRD-ASM-018",
                                "SEC-AUZ-016");
                case "/api/ui/applications/{id}/posture", "/api/ui/projects/{id}/posture" ->
                        List.of("PRD-AST-001", "PRD-UIX-011", "PRD-UIX-022", "SEC-AUZ-016");
                // Projects. PRD-AST-001 is the asset inventory, PRD-AST-005 the ownership claim a
                // project makes concrete — it is the level that names the team accountable for a
                // branch of an application — and SEC-AUZ-016 the scope composed into the query.
                // Intake. PRD-PTR-021 is the request queue and what a request must carry before it
                // can start; PRD-ASM-003 the assessment record it becomes.
                case "/api/ui/requests", "/api/ui/projects/{id}/requests" ->
                        List.of("PRD-PTR-021", "PRD-ASM-003", "SEC-AUZ-017");
                // Object-level authority. PRD-AUZ-002 is authorization as permission AND scope —
                // these grants are the per-object half of it; SEC-AUZ-014 denies on an absent grant;
                // SEC-AUZ-017 is the re-read before every write.
                case "/api/ui/projects/{id}/access", "/api/ui/projects/{id}/access/revoke",
                     "/api/ui/board/{id}/participants",
                     "/api/ui/board/{id}/participants/remove" ->
                        List.of("PRD-AUZ-002", "SEC-AUZ-014", "SEC-AUZ-017");
                // A claimed fix. PRD-VUL-001 is the finding; PRD-WRK-032 the transition rules a claim
                // deliberately does NOT invoke, because a claim is not a closure.
                case "/api/ui/board/{id}/findings/{findingId}/remediation" ->
                        List.of("PRD-VUL-001", "PRD-WRK-032", "SEC-AUZ-017");
                case "/api/ui/projects", "/api/ui/projects/{id}" ->
                        List.of("PRD-AST-001", "PRD-AST-005", "SEC-AUZ-016");
                // The project record editor. PRD-AST-014 is the tenant-defined custom attributes it
                // reads the catalogue for and writes values into — including the validation that
                // requirement asks for, which is why the check is at the write and not only in the
                // dropdown. PRD-AST-016 is the technical contact, distinct from the OrgNode owner, and
                // this is the operation that finally records one. PRD-AST-004 covers the typed
                // relationships the domains and the repository are written as, rather than as text on
                // the project. SEC-AUZ-017 is the re-read of the project before writing through its
                // identifier.
                // The declared-field catalogue itself. PRD-AST-014 is tenant-defined custom
                // attributes per asset type — this is the surface that defines them, where the
                // project editor is the surface that fills them in. PRD-TEN-004 keeps that
                // configuration isolated per tenant. PRD-AUZ-006 is deny-by-default, which is what a
                // tenant with nobody holding cfg.asset.field.manage has to mean: nobody edits the
                // catalogue, rather than everybody.
                // The host reverse lookup. PRD-AST-002 is the domain asset type it searches;
                // PRD-AST-004 the published-on relationship that makes a host findable from the asset
                // it serves; SEC-AUZ-016 the scope, which is composed from the attached asset because
                // a domain has no owner of its own.
                case "/api/ui/hosts" ->
                        List.of("PRD-AST-002", "PRD-AST-004", "SEC-AUZ-016");
                case "/api/ui/settings/fields", "/api/ui/settings/fields/{id}",
                     "/api/ui/settings/fields/{id}/lifecycle",
                     "/api/ui/settings/fields/{id}/move" ->
                        List.of("PRD-AST-014", "PRD-TEN-004", "PRD-AUZ-006");
                // The endpoint environment catalogue. CFG-AST-002 is the requirement that the
                // environment an endpoint is published in is tenant vocabulary rather than an
                // enumeration in code — the defect it closes was two hardcoded pairs in two editors.
                // PRD-AST-004 is the published-on relationship the vocabulary labels; PRD-TEN-004
                // keeps the configuration isolated per tenant; PRD-AUZ-006 is deny-by-default, which
                // is what a tenant with nobody holding cfg.asset.field.manage has to mean.
                // The estate graph. PRD-AST-001 is the single Asset aggregate it walks; PRD-AST-004
                // the relationships it draws; PRD-ORG-001 the organization tree it crosses into;
                // SEC-AUZ-016 the scope predicate, applied at retrieval because a graph is an
                // aggregate and filtering one afterwards is how a node nobody may see gets drawn.
                // The inventory export. PRD-AST-001 is the aggregate it lists; PRD-API-046 the
                // obligation an export carries — the scope applied and the record count, stated in
                // the file AND in the audit event; SEC-AUZ-016 the scope predicate, which it inherits
                // by calling the list handler rather than by carrying a second copy of it.
                case "/api/ui/applications/export" ->
                        List.of("PRD-AST-001", "PRD-API-046", "SEC-AUZ-016");
                case "/api/ui/graph/{id}" ->
                        List.of("PRD-AST-001", "PRD-AST-004", "PRD-ORG-001", "SEC-AUZ-016");
                case "/api/ui/settings/environments", "/api/ui/settings/environments/{id}",
                     "/api/ui/settings/environments/{id}/lifecycle",
                     "/api/ui/settings/environments/{id}/move" ->
                        List.of("CFG-AST-002", "PRD-AST-004", "PRD-TEN-004", "PRD-AUZ-006");
                case "/api/ui/projects/{id}/editor" ->
                        List.of("PRD-AST-004", "PRD-AST-014", "PRD-AST-016", "SEC-AUZ-017");
                case "/api/ui/organization" ->
                        List.of("PRD-ORG-001", "SEC-AUZ-010", "SEC-AUZ-016");
                // The write side of the React interface. Each traces to the SAME requirements as the
                // server-rendered editor it replaces, because it is the same operation reached through
                // a different representation — a second set of traces would be the first sign the two
                // tiers had started meaning different things.
                //
                // The application editor: PRD-AST-001 the inventory, PRD-AST-008 the ownership record
                // it writes, SEC-AUZ-016 the scope composed into the owner list, SEC-AUZ-017 the
                // re-read of the submitted owner before it is used.
                case "/api/ui/applications/editor", "/api/ui/applications/{id}/editor" ->
                        List.of("PRD-AST-001", "PRD-AST-008", "SEC-AUZ-016");
                // Retirement. PRD-AST-012 is the lifecycle transition an asset may take without its
                // findings and history being destroyed with it.
                case "/api/ui/applications/{id}/retire" ->
                        List.of("PRD-AST-001", "PRD-AST-012", "SEC-AUZ-017");
                // Node creation and rename. PRD-ORG-003 is the single-parent rule the parent picker
                // must not be able to break; PRD-ORG-006 the criticality this form assigns.
                case "/api/ui/organization/{id}", "/api/ui/organization/{id}/deprecate" ->
                        List.of("PRD-ORG-001", "PRD-ORG-003", "PRD-ORG-006", "SEC-AUZ-017");
                // Role composition. PRD-AUZ-001 is the product-fixed catalogue a role is composed
                // from; PRD-AUZ-002 is authorization as permission AND scope, which is why nothing on
                // this surface chooses a scope; PRD-AUZ-006 is deny-by-default, which is what a role
                // created with no permission has to mean.
                // Recording a finding from the React interface. Same requirements as the
                // server-rendered form: PRD-VUL-001 is the finding, PRD-ASM-012 the engagement record
                // it belongs to, SEC-AUZ-017 the re-read of the request before writing through it.
                case "/api/ui/board/{id}/finding-form", "/api/ui/board/{id}/findings" ->
                        List.of("PRD-VUL-001", "PRD-ASM-012", "SEC-AUZ-017");
                case "/api/ui/roles", "/api/ui/roles/{id}", "/api/ui/roles/{id}/retire",
                     "/api/ui/roles/{id}/restore", "/api/ui/roles/{id}/delete" ->
                        List.of("PRD-AUZ-001", "PRD-AUZ-002", "PRD-AUZ-006");
                // The three dashboards as JSON. Each traces to the same requirements as the
                // server-rendered page it mirrors, because it answers the same question — and each
                // additionally cites the honesty requirement its payload exists to keep: the value is
                // absent rather than zero where the population was not measured.
                case "/api/ui/overview" ->
                        List.of("PRD-UIX-011", "PRD-UIX-022", "SEC-AUZ-016");
                case "/api/ui/teams", "/api/ui/teams/members", "/api/ui/teams/{id}/retire" ->
                        List.of("PRD-CAP-001", "PRD-CAP-013", "PRD-AUZ-001");
                case "/api/ui/workload", "/api/ui/workload/analytics" ->
                        List.of("PRD-CAP-005", "PRD-CAP-008", "PRD-CAP-013", "PRD-CAP-014");
                case "/api/ui/composition" ->
                        List.of("PRD-SBM-056", "PRD-SBM-032");
                // Submission health per integration credential. PRD-SBM-024 is the requirement itself;
                // ADR-023 names it as the mitigation for the push endpoint being a single point of
                // failure, and PRD-SBM-032 is cited because what the panel reports on is coverage —
                // a broken pipeline matters through the artifacts that stop being inventoried.
                case "/api/ui/sbom-submission-health" ->
                        List.of("PRD-SBM-024", "PRD-SBM-032");
                // The account panel and the authorization administration screens, as JSON. Same
                // requirements as the pages they mirror: SEC-SEC-012 is the caller's own session
                // list, PRD-AUZ-001 the product-fixed catalogue the matrix renders, and SEC-AUZ-014
                // the reason a principal with no live assignment is counted rather than shown blank.
                case "/api/ui/account", "/api/ui/account/sessions/revoke" ->
                        List.of("SEC-SEC-012", "SEC-SEC-016");
                // The keepalive. SEC-SEC-010 is the requirement it serves: the idle limit it postpones
                // and the absolute limit it cannot. SEC-SEC-011 because the touch it performs is the
                // same per-request write that keeps revocation reaching a live session — a keepalive
                // that bypassed that would hold a revoked session open.
                case "/api/ui/session/keepalive" ->
                        List.of("SEC-SEC-010", "SEC-SEC-011");
                // One finding, reached without an assessment request. PRD-VUL-002 is the requirement it
                // serves — findings from EVERY source, automated ingestion included, go through the same
                // record — and a source with no way to open its own findings does not meet it.
                // PRD-VUL-004 is what the page shows beside the title: source, tool, tool version and
                // rule identifier, which is what tells a developer whether a report is theirs to act on.
                // SEC-AUZ-016 because a lookup by identifier is exactly where a scope predicate must be
                // IN the query rather than applied to its result.
                case "/api/ui/findings/{id}" ->
                        List.of("PRD-VUL-002", "PRD-VUL-004", "SEC-AUZ-016");
                case "/api/ui/access", "/api/ui/access/users/{id}" ->
                        List.of("PRD-AUZ-001", "SEC-AUZ-014");
                case "/api/ui/access/users/{id}/roles",
                     "/api/ui/access/users/{id}/roles/revoke" ->
                        List.of("PRD-AUZ-001", "SEC-AUZ-017");
                case "/api/ui/access/users/{id}/reset" ->
                        List.of("PRD-IAM-007", "SEC-SEC-016", "SEC-AUZ-017");
                // The user guide. PRD-PLT-007 requires onboarding to be "a documented, repeatable
                // procedure"; this is the half of that a person reaches without being handed anything,
                // and product principle 7 is why it has to be: the largest user population has the
                // narrowest permissions and the least training, and they do not read what is not in
                // front of them. INT-UIX-008 because the prose is a per-locale classpath resource —
                // no sentence of it is compiled into GuidePage, which is what the requirement asks
                // for; a bundle key per paragraph would satisfy the letter and make the document
                // unmaintainable.
                //
                // Stated plainly: this operation ENFORCES nothing. It explains. That is unusual
                // enough in this table to be worth writing down rather than leaving a reader to
                // wonder which control they missed.
                // The guide, and the API guide beside it. Both trace where the server-rendered guide
                // traced: PRD-PLT-007 is the obligation to explain the platform to its users, INT-UIX-008
                // the externalized presentation the documents are written as.
                case "/guide", "/api-guide", "/api/ui/guide", "/api/ui/api-guide" ->
                        List.of("PRD-PLT-007", "INT-UIX-008");
                case "/board/{id}/attachments", "/attachments/{id}" ->
                        List.of("PRD-ASM-007", "PRD-ASM-012", "PRD-PTR-005");
                // Every interface template, resolved from the one list the router and the server share
                // rather than enumerated again here. Written as a guard in the default arm because a case
                // label has to be a constant, and duplicating twenty-five paths into this switch is
                // exactly the second copy WebUi.ROUTES exists to avoid.
                default -> aspm.app.ui.WebUi.ROUTES.contains(operation.pathTemplate())
                        ? List.of("PRD-UIX-006", "INT-UIX-004")
                        : TRACES.getOrDefault(group, List.of());
            };
            assertFalse(traces.isEmpty(),
                    operation.method() + " " + operation.pathTemplate() + " belongs to resource group '"
                            + group + "', which cites no requirement. An operation tracing to nothing is "
                            + "the undocumented functionality PRD-PLT-012 is about: attack surface and "
                            + "test burden nobody agreed to accept.");
            lines.add(String.join(",",
                    operation.method(),
                    operation.pathTemplate(),
                    operation.annotationClass().name(),
                    operation.requiredPermission().orElse("unauthenticated"),
                    group,
                    String.join(" ", traces)));
        }

        Path root = Path.of(System.getProperty("aspm.corpusRoot", ".."));
        Path manifest = root.resolve("_traceability").resolve("api-operations.csv");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        assertTrue(Files.exists(manifest));
    }

    @Test
    @DisplayName("every registered group is in the trace table, and every trace table entry is a group")
    void theTraceTableMatchesTheCatalogue() {
        for (ResourceGroup group : ResourceCatalogue.all()) {
            assertTrue(TRACES.containsKey(group.name()),
                    group.name() + " is registered and cites no requirement. Adding a group without one "
                            + "is how an API grows a surface nobody agreed to.");
        }
        for (String named : TRACES.keySet()) {
            assertTrue(ResourceCatalogue.all().stream().anyMatch(g -> g.name().equals(named)),
                    named + " is in the trace table and is not a registered group, so the table is "
                            + "describing something that does not exist");
        }
    }

    /**
     * The resource group a path belongs to.
     *
     * <p>{@code /api/v1/<group>} and {@code /<group>} are the same group presented two ways, and they
     * trace to the same requirements — a page is a read of the rows the API returns. Treating them
     * separately reported every interface page as untraced.
     */
    private static String groupOf(String pathTemplate) {
        String[] parts = pathTemplate.split("/", -1);
        if (parts.length > 3 && "api".equals(parts[1])) {
            return parts[3];
        }
        if (parts.length > 2 && "ui".equals(parts[1])) {
            return parts[2];
        }
        return pathTemplate;
    }
}
