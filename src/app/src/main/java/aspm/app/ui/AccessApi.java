package aspm.app.ui;

import aspm.app.identity.AccountService;
import aspm.app.identity.IdentityService;
import aspm.app.identity.SessionPrincipalResolver;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The JSON behind the account panel and the authorization administration screens.
 *
 * <p>The same rules as {@link UiApi}: not a public API, assembled field by field rather than by
 * serializing a record, and carrying the permission of the server-rendered page it mirrors. What is
 * different here is that two distinct authorization models meet in one file, and conflating them
 * would be a defect in either direction.
 *
 * <h2>The account panel is authorized by IDENTITY; administration by PERMISSION</h2>
 *
 * <p>{@code /api/ui/account} is the caller's own account, so it is class G with the session checked
 * inside the handler — the same deviation, for the same reason, that {@link AccountPages} records.
 * Naming a catalogue permission would lock a principal out of their own profile and their own session
 * list, and {@code /change-password} has to be reachable by a principal holding <b>no role at
 * all</b> because the deployment bootstrap creates exactly that principal. The cost is stated at
 * {@link AccountPages} and in {@code deploy/README.md} rather than hidden: class G declares
 * {@code Classification.PUBLIC}, and this payload contains the caller's own session list with source
 * addresses.
 *
 * <p>Everything under {@code /api/ui/access} is ordinary permission-gated administration.
 *
 * <h2>Step-up is signalled, not simulated</h2>
 *
 * <p>Granting a role is class E and issuing a credential reset is class C, so the dispatcher refuses
 * both without a fresh second factor. For an {@code /api/} path it answers {@code 401
 * STEP_UP_REQUIRED} rather than redirecting, and the interface sends the caller to {@code
 * /step-up} with a return path. <b>Nothing here re-implements that test.</b> A second gate in the
 * client is a gate that can disagree with the first, and the one in the dispatcher is the one that
 * actually holds — this endpoint would refuse an elevated-looking client just the same.
 */
public final class AccessApi {

    private final AccountService accounts;
    private final aspm.app.authz.ObjectAuthority authority;
    private final aspm.app.inventory.InventoryService inventory;
    private final SessionPrincipalResolver resolver;
    private final UUID tenantId;

    public AccessApi(DataSource dataSource, UUID tenantId) {
        Objects.requireNonNull(dataSource, "a data source is required");
        this.accounts = new AccountService(dataSource);
        this.authority = new aspm.app.authz.ObjectAuthority(dataSource);
        this.inventory = new aspm.app.inventory.InventoryService(dataSource);
        this.tenantId = Objects.requireNonNull(tenantId, "a tenant is required");
        this.resolver = new SessionPrincipalResolver(dataSource, tenantId);
    }

    // ==============================================================================================
    // The account panel — GET /api/ui/account
    // ==============================================================================================

    /**
     * The caller's own profile, credential state and live sessions.
     *
     * <p>{@code current} marks the session making this request. It is the one control that makes the
     * list actionable: without it a person revoking "the session from an address I do not recognise"
     * cannot tell which row is the browser they are sitting in, and signing themselves out is the
     * most likely outcome.
     */
    public Dispatcher.Response account(Dispatcher.Request request) throws Exception {
        Optional<IdentityService.Session> found = fullyAuthenticated(request);
        if (found.isEmpty()) {
            return unauthenticated();
        }
        IdentityService.Session session = found.orElseThrow();
        Optional<AccountService.UserRow> me = accounts.user(tenantId, session.principalId());

        List<Map<String, Object>> sessions = new ArrayList<>();
        for (Map<String, Object> row : resolver.identity()
                .ownSessions(tenantId, session.principalId())) {
            Map<String, Object> entry = new LinkedHashMap<>(row);
            entry.put("current", session.id().toString().equals(row.get("id")));
            sessions.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("principalId", session.principalId().toString());
        body.put("username", me.map(AccountService.UserRow::username).orElse(null));
        body.put("displayName", me.map(AccountService.UserRow::displayName).orElse(null));
        body.put("email", me.map(AccountService.UserRow::email).orElse(null));
        body.put("lifecycleState", me.map(AccountService.UserRow::lifecycleState).orElse(null));
        body.put("mfaEnrolled", me.map(AccountService.UserRow::mfaEnrolled).orElse(Boolean.FALSE));
        body.put("mustChangePassword",
                me.map(AccountService.UserRow::mustChangePassword).orElse(Boolean.FALSE));
        // Null rather than a placeholder date. Never authenticated is a different fact from not
        // recorded, and a dash in a date column reads as neither.
        body.put("lastAuthenticatedAt",
                me.map(AccountService.UserRow::lastAuthenticatedAt).orElse(null));
        body.put("roles", me.map(AccountService.UserRow::roleCodes).orElse(List.of()));
        body.put("assignments", accounts.assignmentsOf(tenantId, session.principalId()));
        body.put("sessions", sessions);
        body.put("factorState", session.factorState());
        return json(body);
    }

    /**
     * {@code POST /api/ui/account/sessions/revoke}.
     *
     * <p>The identifier arrives from the client and is <b>not an authorization</b>. The principal
     * comes from the cookie and the update carries both, so a caller who edits the request revokes
     * nothing of anybody else's — product principle 4, and a session list is a scope like any other.
     */
    public Dispatcher.Response revokeSession(Dispatcher.Request request) throws Exception {
        Optional<IdentityService.Session> found = fullyAuthenticated(request);
        if (found.isEmpty()) {
            return unauthenticated();
        }
        IdentityService.Session session = found.orElseThrow();
        UUID target = uuid(text(request.body().orElse(Map.of()).get("session")));
        if (target == null) {
            return new Dispatcher.Response(400,
                    Map.of("code", "SESSION_REQUIRED", "message", "name the session to revoke"),
                    Map.of());
        }
        if (target.equals(session.id())) {
            // Revoking the session you are using is signing out, and signing out is more than a
            // revocation: it also clears the cookie. Answered as an instruction rather than done
            // here, because a second implementation that revoked without clearing would leave the
            // browser presenting a dead token on every request.
            return json(Map.of("signOut", true));
        }
        boolean revoked = resolver.identity()
                .revokeOwnSession(tenantId, session.principalId(), target);
        return json(Map.of("revoked", revoked));
    }

    // ==============================================================================================
    // Administration — GET /api/ui/access
    // ==============================================================================================

    /**
     * Everyone, every tenant role, and the product-fixed permission catalogue.
     *
     * <p>The three arrive together because the screen is one screen: a matrix of roles against
     * permissions above a list of who holds what. Splitting them into three requests would make the
     * matrix render before it knows its own columns.
     *
     * <p>No role name and no organizational level name is a literal anywhere in this method (ADR-027).
     * The matrix renders whatever roles the tenant defined against whatever the catalogue contains.
     */
    /**
     * Every permission the catalogue defines, grouped by domain.
     *
     * <p>Product-fixed, unlike the roles built from it (ADR-027). Grouped by the domain the catalogue
     * already records, because a flat list of a hundred and fifty checkboxes is a list nobody reads
     * before ticking — and because the grouping is then a fact about the catalogue rather than a
     * taxonomy invented for one screen and diverging from the next.
     *
     * <p>Static and shared: the role editor and the service-credential form both offer this list, and
     * two builders of one catalogue drift into two different lists.
     */
    static List<Map<String, Object>> permissionCatalogue(AccountService accounts,
            java.util.UUID tenantId) throws java.sql.SQLException {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (AccountService.PermissionRow permission : accounts.permissions(tenantId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", permission.code());
            entry.put("label", permission.label());
            // Both flags reach the screen. A permission that reveals restricted data and one that
            // demands a second factor are the two an administrator should hesitate over, and a
            // checkbox that looks like every other checkbox is how they get ticked in a batch.
            entry.put("restricted", permission.restricted());
            entry.put("requiresStepUp", permission.requiresStepUp());
            // Upper-cased, because the catalogue itself is not consistent: `asm` and `ASM` are both
            // present, seeded by different migrations, and grouping on the raw value drew the same
            // domain twice with its permissions split between the two. The data is left alone — a
            // migration that rewrote it would be editing the product's own catalogue to tidy a
            // display — and the screens agree because they both group through here.
            grouped.computeIfAbsent(permission.domain() == null ? "OTHER"
                    : permission.domain().toUpperCase(java.util.Locale.ROOT),
                    key -> new ArrayList<>()).add(entry);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> group : new java.util.TreeMap<>(grouped)
                .entrySet()) {
            out.add(Map.of("group", group.getKey(), "permissions", group.getValue()));
        }
        return out;
    }

    public Dispatcher.Response access(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();

        List<Map<String, Object>> users = new ArrayList<>();
        long noMfa = 0;
        long mustChange = 0;
        long noRole = 0;
        for (AccountService.UserRow user : accounts.users(tenantId)) {
            if (!user.mfaEnrolled()) {
                noMfa++;
            }
            if (user.mustChangePassword()) {
                mustChange++;
            }
            if (user.liveAssignments() == 0) {
                noRole++;
            }
            users.add(user(user));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("users", users);

        // The catalogue the credential form ticks from. It used to send one hardcoded permission —

        // every credential ever issued here carries exactly `sbm.sbom.submit`, whatever it was for —

        // so a pipeline that needed to submit scan results could not be given the permission to.

        body.put("permissionCatalogue", permissionCatalogue(accounts, tenantId));        // A principal with no live assignment is a fact worth counting, not an empty cell.
        // SEC-AUZ-014 denies on an empty grant, so such an account signs in and reaches nothing —
        // which looks like a broken platform to them and like an ordinary row to an administrator.
        body.put("totals", Map.of("users", users.size(), "withoutSecondFactor", noMfa,
                "mustChangePassword", mustChange, "withoutRole", noRole));
        // allRoles, not roles: retired ones are shown and marked. A retired role that vanished would
        // leave an administrator unable to find it to restore or delete.
        body.put("roles", accounts.allRoles(tenantId).stream().map(AccessApi::role).toList());
        body.put("permissions", accounts.permissions(tenantId).stream()
                .map(p -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("code", p.code());
                    entry.put("domain", p.domain());
                    entry.put("label", p.label());
                    entry.put("restricted", p.restricted());
                    entry.put("requiresStepUp", p.requiresStepUp());
                    return entry;
                }).toList());
        // For DISABLING controls. Every one is re-checked at the operation, and the class C and E
        // operations are additionally refused without a fresh second factor.
        body.put("mayGrant", principal != null && principal.holds(AdminPages.MANAGE_ROLES));
        body.put("mayReset", principal != null && principal.holds(AdminPages.RESET_CREDENTIAL));
        body.put("elevated", principal != null && principal.stepUpAuthenticated());
        // The other half of authorization, on the same screen. A role says what somebody may do
        // across a slice of the organization; an asset grant says what they own and what they may
        // ask for. Reading them in two places is how an access review misses one of them.
        body.put("assetGrants", assetGrants(authority.allGrants(principal)));
        return json(body);
    }

    /** {@code GET /api/ui/access/users/{id}}. */
    public Dispatcher.Response user(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        Optional<AccountService.UserRow> found = accounts.user(tenantId, id);
        if (found.isEmpty()) {
            // The same 404 a non-existent principal gets. The list this identifier came from is
            // gated by the same read permission, so gone and never-existed must not be told apart.
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>(user(found.orElseThrow()));
        body.put("assignments", accounts.assignmentsOf(tenantId, id));
        // What this person owns and may request against, which an offboarding check has to see
        // before the account is disabled — an unowned project is a project nobody can delegate on.
        body.put("assetGrants", assetGrants(authority.grantsOf(principal, id)));
        body.put("roles", accounts.roles(tenantId).stream().map(AccessApi::role).toList());
        body.put("mayGrant", principal != null && principal.holds(AdminPages.MANAGE_ROLES));
        body.put("mayReset", principal != null && principal.holds(AdminPages.RESET_CREDENTIAL));
        body.put("elevated", principal != null && principal.stepUpAuthenticated());
        return json(body);
    }

    /** {@code POST /api/ui/access/users/{id}/roles} — grant. Class E. */
    public Dispatcher.Response grant(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        // Re-read before writing through it (SEC-AUZ-017). Authorizing the path and then acting on
        // the identifier is the defect class this product exists to find in other people's software.
        if (id == null || accounts.user(tenantId, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> payload = request.body().orElse(Map.of());
        UUID roleId = uuid(text(payload.get("role")));
        if (roleId == null) {
            return new Dispatcher.Response(400,
                    Map.of("code", "ROLE_REQUIRED", "message", "name the role to grant"), Map.of());
        }
        // Re-read, like the principal above. Passing the identifier straight to the insert let a role
        // that does not exist reach the database, and a foreign key violation surfaced as a 500 with
        // a correlation identifier — an administrator with a stale page got an internal error where
        // the honest answer is "that role is gone".
        if (accounts.role(tenantId, roleId).isEmpty()) {
            return new Dispatcher.Response(400, Map.of("code", "ROLE_UNKNOWN",
                    "message", "that role does not exist"), Map.of());
        }
        String requested = text(payload.get("scopeMode"));
        String scopeMode = switch (requested == null ? "" : requested) {
            case "TENANT" -> "TENANT";
            case "NODE_ONLY" -> "NODE_ONLY";
            // Anything else becomes the NARROWEST useful grant, never the widest. A malformed scope
            // mode defaulting to TENANT would turn a typo into a tenant-wide grant.
            default -> "SUBTREE";
        };
        UUID node = null;
        if (!"TENANT".equals(scopeMode)) {
            node = uuid(text(payload.get("scopeNode")));
            if (node == null) {
                return new Dispatcher.Response(400, Map.of("code", "SCOPE_NODE_REQUIRED",
                        "message", "a grant that is not tenant-wide needs a node"), Map.of());
            }
            // The node must be one the GRANTER can see, not merely one that exists. Granting over a
            // subtree you cannot reach yourself is how an administrator scoped to one division hands
            // somebody authority over another — and the check is against the same scoped query the
            // picker was filled from, so the control and the convenience cannot drift apart.
            UUID target = node;
            boolean visible = inventory.nodes(principal, false).stream()
                    .anyMatch(candidate -> candidate.id().equals(target));
            if (!visible) {
                return new Dispatcher.Response(400, Map.of("code", "SCOPE_NODE_UNKNOWN",
                        "message", "that node is not one you can grant over"), Map.of());
            }
        }
        boolean granted = accounts.assignRole(tenantId, principal.principalId(), id, roleId,
                scopeMode, node);
        return json(Map.of("granted", granted));
    }

    /** {@code POST /api/ui/access/users/{id}/roles/revoke}. Class E. */
    public Dispatcher.Response revokeGrant(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || accounts.user(tenantId, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        UUID assignment = uuid(text(request.body().orElse(Map.of()).get("assignment")));
        if (assignment == null) {
            return new Dispatcher.Response(400, Map.of("code", "ASSIGNMENT_REQUIRED",
                    "message", "name the assignment to revoke"), Map.of());
        }
        boolean revoked = accounts.revokeAssignment(tenantId, assignment,
                "REVOKED_BY_ADMINISTRATOR");
        return json(Map.of("revoked", revoked));
    }

    /**
     * {@code POST /api/ui/access/users/{id}/reset}. Class C — restricted reveal, step-up.
     *
     * <p>The token is in the RESPONSE BODY, not a query string. The server-rendered page carries it
     * back on a redirect, which puts a bearer credential for somebody's account into browser history
     * and into any proxy log in front of this tier; that weakness is recorded rather than copied.
     *
     * <p>There is no field anywhere that sets a password to a chosen value, and that is a refusal
     * rather than an omission: a credential the administrator knows is one whose subsequent use
     * cannot be attributed to the account holder, which silently voids every audit entry that account
     * then produces.
     */
    public Dispatcher.Response reset(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || accounts.user(tenantId, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        AccountService.ResetIssued issued = accounts.resetCredential(tenantId,
                principal.principalId(), id);
        return json(Map.of("token", issued.token(),
                "expiresAt", issued.expiresAt().toString(),
                "sessionsRevoked", issued.sessionsRevoked()));
    }

    // ==============================================================================================

    private static List<Map<String, Object>> assetGrants(
            List<aspm.app.authz.ObjectAuthority.Grant> source) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var grant : source) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", grant.id().toString());
            entry.put("assetId", grant.assetId().toString());
            entry.put("assetName", grant.assetName());
            entry.put("principalId", grant.principalId().toString());
            entry.put("displayName", grant.principalName());
            entry.put("username", grant.username());
            entry.put("capability", grant.capability());
            entry.put("grantedAt", grant.grantedAt());
            entry.put("grantedBy", grant.grantedByName());
            out.add(entry);
        }
        return out;
    }

    private static Map<String, Object> user(AccountService.UserRow user) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", user.id().toString());
        entry.put("username", user.username());
        entry.put("displayName", user.displayName());
        entry.put("email", user.email());
        entry.put("lifecycleState", user.lifecycleState());
        entry.put("mustChangePassword", user.mustChangePassword());
        entry.put("mfaEnrolled", user.mfaEnrolled());
        entry.put("lastAuthenticatedAt", user.lastAuthenticatedAt());
        entry.put("liveAssignments", user.liveAssignments());
        entry.put("liveSessions", user.liveSessions());
        entry.put("roleCodes", user.roleCodes());
        return entry;
    }

    private static Map<String, Object> role(AccountService.RoleRow row) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", row.id().toString());
        entry.put("code", row.code());
        entry.put("label", row.label());
        entry.put("description", row.description());
        entry.put("permissionCodes", List.copyOf(row.permissionCodes()));
        entry.put("assignmentCount", row.assignmentCount());
        entry.put("active", row.active());
        entry.put("fromTemplate", row.fromTemplate());
        return entry;
    }

    /**
     * {@code GET /api/ui/session/keepalive}. Keeps a session alive while somebody is actually using it.
     *
     * <h2>The defect this closes</h2>
     *
     * <p>The idle limit is enforced on {@code last_seen_at}, which only moves when a request resolves a
     * session. In a keyboard-first single-page interface that is not the same as "somebody is working":
     * writing up a finding, reading a long report or filling a form makes NO request at all. So a person
     * typing for thirty-one minutes was signed out mid-sentence, and the interface then navigated to
     * sign-in and took the unsaved write-up with it. Reported as "it logs me out while I am working",
     * which is exactly what it did.
     *
     * <h2>Why this does not defeat the idle limit</h2>
     *
     * <p>The caller only sends this when there has been real interaction since the last one AND the tab
     * is visible — the client half of the control. An unattended machine stops sending it and times out
     * on schedule, which is what {@code SEC-SEC-010}'s rationale is about: sessions accumulating on
     * shared and personal devices. A timer that pinged unconditionally would turn the idle limit into no
     * limit, and that is the trap this shape avoids.
     *
     * <p>The ABSOLUTE limit is untouched and unreachable from here. Whatever somebody does, the session
     * dies at {@code absolute_expires_at} — the 12-hour product ceiling still holds.
     *
     * <h2>It reports the remaining window</h2>
     *
     * <p>So the interface can warn before the session goes rather than after. Being told "you were
     * signed out" is not a warning; it is an obituary.
     *
     * <p>Class G, like the rest of this file's self-service routes: the subject is the caller's own
     * session and a catalogue permission would lock a principal out of keeping their own session alive.
     * The session check is in the handler, and the touch happens inside {@code sessionFor} — the same
     * write every authenticated request performs, which is why this needs no privileges of its own.
     */
    public Dispatcher.Response keepalive(Dispatcher.Request request) throws java.sql.SQLException {
        var session = fullyAuthenticated(request);
        if (session.isEmpty()) {
            return unauthenticated();
        }
        // The touch already happened inside fullyAuthenticated, so this reads the window AFTER it —
        // reporting the time the caller has just been granted rather than the time they had before.
        var remaining = identity().sessionWindow(tenantId, session.orElseThrow().id());
        return json(Map.of(
                "ok", Boolean.TRUE,
                // Seconds until the idle limit bites if nothing further happens, and until the absolute
                // limit regardless. Both, because they run out for different reasons and only one of
                // them can be postponed by working.
                "idleSecondsLeft", Long.valueOf(remaining.idleSecondsLeft()),
                "absoluteSecondsLeft", Long.valueOf(remaining.absoluteSecondsLeft())));
    }

    /**
     * The session behind the request, and only once it has completed the second factor.
     *
     * <p>These routes are class G, so the dispatcher authenticates nothing for them. A
     * {@code PASSWORD_ONLY} session reaching the account panel would read the caller's session list
     * before they had finished proving who they are.
     */
    private Optional<IdentityService.Session> fullyAuthenticated(Dispatcher.Request request) {
        return resolver.sessionFor(request.headers())
                .filter(session -> "FULLY_AUTHENTICATED".equals(session.factorState()));
    }

    /** The identity service behind the resolver, for the session window the keepalive reports. */
    private IdentityService identity() {
        return resolver.identity();
    }

    private static Dispatcher.Response unauthenticated() {
        return new Dispatcher.Response(401, Map.of("code", "UNAUTHENTICATED",
                "message", "sign in to see your account"), Map.of());
    }

    private static Dispatcher.Response json(Object body) {
        return new Dispatcher.Response(200, body,
                Map.of("Content-Type", "application/json; charset=utf-8"));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
