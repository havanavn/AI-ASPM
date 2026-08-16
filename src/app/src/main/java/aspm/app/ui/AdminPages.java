package aspm.app.ui;

import aspm.app.identity.AccountService;
import aspm.app.identity.PasswordPolicy;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * User administration and the role/permission surfaces. DOC-07, {@code PRD-AUZ-001}, {@code PRD-IAM-*}.
 *
 * <ul>
 *   <li>{@code GET /users} — the administration list. Class A on {@code iam.user.read}.
 *   <li>{@code GET /users/{id}} — one principal: assignments, credential state, sessions count.
 *   <li>{@code POST /users/{id}/reset} — issue a reset. Class C: restricted reveal, step-up.
 *   <li>{@code POST /users/{id}/roles} and {@code .../roles/revoke} — grant and revoke. Class E.
 *   <li>{@code GET /roles} — the permission matrix. Class A on {@code auz.role.manage}.
 *   <li>{@code GET|POST /security-policy} — the password and session policy. Class A / class E.
 * </ul>
 *
 * <h2>No role name and no organizational level name appears in this file</h2>
 *
 * <p>ADR-027: the permission catalogue is product-fixed, and roles, hierarchy depth and vocabulary are
 * tenant data. So the matrix renders whatever roles the tenant has defined against whatever the catalogue
 * contains, and neither axis is a literal here. The seeded roles — the names an administrator will see
 * first — are rows in {@code deploy/seed-identity.sql}, and a deployment that renames or replaces all five
 * needs no code change. <b>A product with role names compiled into its administration page cannot be sold
 * to the second customer.</b>
 *
 * <h2>What an administrator cannot do</h2>
 *
 * <p>There is no field on any of these pages that sets a password to a chosen value. That is a refusal, not
 * an omission: a credential the administrator knows is a credential whose subsequent use cannot be
 * attributed to the account holder, which silently voids every audit entry the account then produces. The
 * reset issues a single-use token, shown once, and the holder chooses the value.
 */
public final class AdminPages {

    /** Read the administration list. */
    public static final String READ_USERS = "iam.user.read";
    /** Create and update users, and configure the credential policy. */
    public static final String MANAGE_USERS = "iam.user.manage";
    /** Issue a credential reset. Restricted in the catalogue, and step-up. */
    public static final String RESET_CREDENTIAL = "iam.credential.reset";
    /** Compose roles and grant them. */
    public static final String MANAGE_ROLES = "auz.role.manage";

    private final AccountService accounts;
    private final UUID tenantId;

    public AdminPages(DataSource dataSource, UUID tenantId) {
        this.accounts = new AccountService(Objects.requireNonNull(dataSource));
        this.tenantId = Objects.requireNonNull(tenantId, "a tenant is required");
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code GET /users}. */
    public Dispatcher.Response users(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();
        List<AccountService.UserRow> users = accounts.users(tenantId);

        StringBuilder body = new StringBuilder(4096);
        if (request.query().containsKey("granted")) {
            body.append(notice(messages.get("admin.users.granted")));
        }
        if (request.query().containsKey("revoked")) {
            body.append(notice(messages.get("admin.users.revoked")));
        }

        body.append("<div class=\"grid grid-kpi mb-6\">")
                .append(kpi(messages, "admin.users.kpi.total", users.size()))
                .append(kpi(messages, "admin.users.kpi.noMfa",
                        (int) users.stream().filter(u -> !u.mfaEnrolled()).count()))
                .append(kpi(messages, "admin.users.kpi.mustChange",
                        (int) users.stream().filter(AccountService.UserRow::mustChangePassword).count()))
                .append(kpi(messages, "admin.users.kpi.noRole",
                        (int) users.stream().filter(u -> u.liveAssignments() == 0).count()))
                .append("</div>");

        body.append("<div class=\"table-wrap\"><div class=\"table-scroll\">")
                .append("<table class=\"data\"><caption>")
                .append(Html.text(messages.get("admin.users.caption"))).append("</caption><thead><tr>")
                .append(th(messages, "admin.users.col.user"))
                .append(th(messages, "admin.users.col.roles"))
                .append(th(messages, "admin.users.col.state"))
                .append(th(messages, "admin.users.col.lastSeen"))
                .append(th(messages, "admin.users.col.sessions"))
                .append("</tr></thead><tbody>");

        for (AccountService.UserRow user : users) {
            body.append("<tr><td><a class=\"link\" href=\"/users/")
                    .append(Html.text(user.id().toString())).append("\">")
                    .append(Html.text(user.username())).append("</a>")
                    .append("<div class=\"fs-11 muted\">")
                    .append(Html.text(user.displayName())).append("</div></td>");

            // A principal with no live assignment is called out rather than shown as an empty cell.
            // SEC-AUZ-014 denies on an empty grant, so such an account can sign in and reach nothing —
            // which looks to the user like a broken platform and to an administrator like a normal row.
            body.append("<td>").append(user.roleCodes().isEmpty()
                            ? pill("warn", messages.get("admin.users.noRole"))
                            : Html.text(String.join(", ", user.roleCodes())))
                    .append("</td>");

            body.append("<td><span class=\"row gap-1 wrap\">")
                    .append(pill(lifecycleTone(user.lifecycleState()), user.lifecycleState()))
                    .append(user.mfaEnrolled() ? "" : pill("danger", messages.get("admin.users.noMfa")))
                    .append(user.mustChangePassword()
                            ? pill("warn", messages.get("admin.users.mustChange")) : "")
                    .append("</span></td>");

            // PP-1: never authenticated is a different fact from not recorded, and neither is a blank.
            body.append("<td class=\"mono fs-11\">").append(user.lastAuthenticatedAt() == null
                            ? Html.text(messages.get("admin.users.never"))
                            : Html.text(user.lastAuthenticatedAt()))
                    .append("</td>");
            body.append("<td class=\"tabular\">").append(user.liveSessions()).append("</td></tr>");
        }
        body.append("</tbody></table></div></div>");

        if (principal != null && principal.holds(MANAGE_ROLES)) {
            body.append("<p class=\"mt-6 fs-13\"><a class=\"link\" href=\"/roles\">")
                    .append(Html.text(messages.get("admin.users.toMatrix"))).append("</a></p>");
        }

        Page.Context context = Page.Context.of("admin.users.title", "/access", Optional.ofNullable(principal))
                .withSubtitle("admin.users.subtitle")
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("admin.users.title"), Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    /** {@code GET /users/{id}}. */
    public Dispatcher.Response user(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();
        UUID id;
        try {
            id = UUID.fromString(request.pathVariables().get("id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dispatcher.Response.notFound();
        }
        Optional<AccountService.UserRow> found = accounts.user(tenantId, id);
        if (found.isEmpty()) {
            // 404 rather than a message. The list this row came from is scoped by the same read
            // permission, so an identifier that resolves to nothing is either gone or never existed, and
            // the two should not be distinguishable (PRD-API-036 applied to the interface).
            return Dispatcher.Response.notFound();
        }
        AccountService.UserRow user = found.orElseThrow();
        List<Map<String, String>> assignments = accounts.assignmentsOf(tenantId, id);
        List<AccountService.RoleRow> roles = accounts.roles(tenantId);

        StringBuilder body = new StringBuilder(4096);
        boolean elevated = principal != null && principal.stepUpAuthenticated();
        boolean canWrite = principal != null
                && (principal.holds(MANAGE_ROLES) || principal.holds(RESET_CREDENTIAL));
        if (canWrite && !elevated) {
            body.append(Forms.elevationPrompt(messages, "/users/" + id));
        }

        // The reset token, shown exactly once, on the redirect back from the POST.
        //
        // Carried in the query string, which is a real weakness and is stated rather than hidden: a URL
        // lands in browser history and in any proxy access log in front of this tier. It is bounded by the
        // token being single-use and expiring in thirty minutes. The alternative — holding it in a server
        // session — needs a store this tier does not have, and is the correct fix; it is recorded in
        // deploy/README.md rather than pretended away.
        String issued = request.query().get("token");
        if (issued != null && !issued.isBlank()) {
            body.append("<section class=\"card mb-6\"><div class=\"card-header\">")
                    .append("<h2 class=\"card-title\">")
                    .append(Html.text(messages.get("admin.reset.issuedTitle")))
                    .append("</h2></div><div class=\"card-body\">")
                    .append("<p class=\"fs-13\">")
                    .append(Html.text(messages.get("admin.reset.issuedLede")))
                    .append("</p><code class=\"once\">")
                    .append(Html.text(issued))
                    .append("</code><p class=\"fs-12 muted\">")
                    .append(Html.text(messages.get("admin.reset.issuedWarning")))
                    .append("</p></div></section>");
        }

        body.append("<div class=\"grid grid-2 mb-6\">")
                .append(profileCard(messages, user))
                .append(assignmentCard(messages, user, assignments, principal, elevated))
                .append("</div>");

        if (principal != null && principal.holds(MANAGE_ROLES)) {
            body.append(grantCard(messages, user, roles, elevated));
        }
        if (principal != null && principal.holds(RESET_CREDENTIAL)) {
            body.append(resetCard(messages, user, elevated));
        }

        Page.Context context = Page.Context.of("admin.user.title", "/access", Optional.ofNullable(principal))
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        // /access, not /users: the user list lives on the access page and /users has no
                        // route. Same failure as the request breadcrumb — a dead crumb on a detail page
                        // is found by the person who wanted to go back.
                        new Page.Crumb(messages.get("admin.users.title"), Optional.of("/access")),
                        new Page.Crumb(user.username(), Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    /** {@code POST /users/{id}/reset}. Class C — restricted reveal, step-up. */
    public Dispatcher.Response reset(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id;
        try {
            id = UUID.fromString(request.pathVariables().get("id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dispatcher.Response.notFound();
        }
        // Re-validated against the object, not only the path. SEC-AUZ-017: authorizing the path and then
        // acting on the identifier is the broken-object-level-authorization defect this product exists to
        // find in other people's software.
        if (accounts.user(tenantId, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        AccountService.ResetIssued outcome = accounts.resetCredential(tenantId,
                principal.principalId(), id);
        return redirect("/users/" + id + "?token="
                + java.net.URLEncoder.encode(outcome.token(), java.nio.charset.StandardCharsets.UTF_8));
    }

    /** {@code POST /users/{id}/roles}. Class E — authorization configuration. */
    public Dispatcher.Response grant(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id;
        try {
            id = UUID.fromString(request.pathVariables().get("id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dispatcher.Response.notFound();
        }
        if (accounts.user(tenantId, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        UUID roleId;
        try {
            roleId = UUID.fromString(form.getOrDefault("role", ""));
        } catch (IllegalArgumentException e) {
            return redirect("/users/" + id);
        }
        String scopeMode = switch (form.getOrDefault("scope_mode", "")) {
            case "TENANT" -> "TENANT";
            case "NODE_ONLY" -> "NODE_ONLY";
            // Anything else becomes the narrowest useful grant rather than the widest. A malformed scope
            // mode that defaulted to TENANT would turn a typo into a tenant-wide grant.
            default -> "SUBTREE";
        };
        UUID node = null;
        if (!"TENANT".equals(scopeMode)) {
            try {
                node = UUID.fromString(form.getOrDefault("scope_node", ""));
            } catch (IllegalArgumentException e) {
                return redirect("/users/" + id);
            }
        }
        accounts.assignRole(tenantId, principal.principalId(), id, roleId, scopeMode, node);
        return redirect("/users/" + id + "?granted=1");
    }

    /** {@code POST /users/{id}/roles/revoke}. Class E. */
    public Dispatcher.Response revokeGrant(Dispatcher.Request request) throws Exception {
        UUID id;
        try {
            id = UUID.fromString(request.pathVariables().get("id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        UUID assignment;
        try {
            assignment = UUID.fromString(form.getOrDefault("assignment", ""));
        } catch (IllegalArgumentException e) {
            return redirect("/users/" + id);
        }
        accounts.revokeAssignment(tenantId, assignment, "REVOKED_BY_ADMINISTRATOR");
        return redirect("/users/" + id + "?revoked=1");
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code GET /roles}. The RBAC matrix. */
    public Dispatcher.Response roles(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();
        // allRoles, not roles: the matrix shows retired ones too, marked. A retired role that vanished
        // from this page would leave an administrator unable to find it to restore or delete.
        List<AccountService.RoleRow> roles = accounts.allRoles(tenantId);
        List<AccountService.PermissionRow> permissions = accounts.permissions(tenantId);

        StringBuilder body = new StringBuilder(8192);
        if (request.query().containsKey("duplicate")) {
            body.append("<div class=\"banner banner-danger\" role=\"alert\"><div>")
                    .append(Html.text(messages.get("admin.roles.duplicate"))).append("</div></div>");
        }
        if (request.query().containsKey("deleted")) {
            body.append(notice(messages.get("admin.roles.deleted")));
        }
        boolean elevated = principal != null && principal.stepUpAuthenticated();
        if (!elevated) {
            body.append(Forms.elevationPrompt(messages, "/roles"));
        }
        body.append("<div class=\"banner\" role=\"note\"><div><strong>")
                .append(Html.text(messages.get("admin.roles.fixedTitle")))
                .append("</strong> ")
                .append(Html.text(messages.get("admin.roles.fixedBody")))
                .append("</div></div>");

        // Create. Inline on the matrix rather than behind its own page: creating a role is the start of
        // composing one, and the editor it redirects to is where the work actually happens.
        body.append("<section class=\"card mb-6\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("admin.roles.create"))).append("</h2>")
                .append("<p class=\"fs-12 muted\">")
                .append(Html.text(messages.get("admin.roles.createLede")))
                .append("</p></div><div class=\"card-body\">")
                .append("<form method=\"post\" action=\"/roles\">")
                .append(Forms.fieldsetOpen(elevated))
                .append(Forms.idempotencyField())
                .append("<div class=\"form-grid\">")
                .append(Forms.field(messages, "code", "admin.roles.newCode", "text", "", true,
                        messages.get("admin.roles.newCodeHint")))
                .append(Forms.field(messages, "label", "admin.roles.newLabel", "text", "", true,
                        messages.get("admin.roles.newLabelHint")))
                .append(Forms.field(messages, "description", "admin.role.description", "text", "",
                        false, null))
                .append("</div><div class=\"form-actions\">")
                .append("<button class=\"btn btn-primary\" type=\"submit\">")
                .append(Html.text(messages.get("admin.roles.createAction")))
                .append("</button></div>").append(Forms.fieldsetClose())
                .append("</form></div></section>");

        body.append("<div class=\"table-wrap\"><div class=\"matrix-wrap\">")
                .append("<table class=\"matrix\"><caption class=\"visually-hidden\">")
                .append(Html.text(messages.get("admin.roles.caption")))
                .append("</caption><thead><tr>")
                .append("<th class=\"row-head\" scope=\"col\">")
                .append(Html.text(messages.get("admin.roles.col.permission"))).append("</th>");
        for (AccountService.RoleRow role : roles) {
            // The column header is a link to the editor, and a retired role says so in the header rather
            // than only in a colour — a matrix column that grants nothing must not read as one that does.
            body.append("<th scope=\"col\"><a class=\"link\" href=\"/roles/")
                    .append(Html.text(role.id().toString())).append("\">")
                    .append(Html.text(role.label())).append("</a>")
                    .append("<div class=\"fs-11 muted\">")
                    .append(role.active()
                            ? Html.text(messages.get("admin.roles.holders", role.assignmentCount()))
                            : Html.text(messages.get("admin.roles.retired")))
                    .append("</div></th>");
        }
        body.append("</tr></thead><tbody>");

        String domain = null;
        for (AccountService.PermissionRow permission : permissions) {
            if (!permission.domain().equals(domain)) {
                domain = permission.domain();
                body.append("<tr><th class=\"row-head\" scope=\"row\" colspan=\"")
                        .append(roles.size() + 1).append("\"><strong>")
                        .append(Html.text(domain)).append("</strong></th></tr>");
            }
            body.append("<tr><th class=\"row-head\" scope=\"row\">")
                    .append(Html.text(permission.code()));
            // Restricted and step-up are marked on the row, because a matrix that shows only granted or
            // not hides the fact that two of these codes carry conditions beyond the grant.
            if (permission.restricted()) {
                body.append(' ').append(pill("danger", messages.get("admin.roles.restricted")));
            }
            if (permission.requiresStepUp()) {
                body.append(' ').append(pill("info", messages.get("admin.roles.stepUp")));
            }
            body.append("</th>");
            for (AccountService.RoleRow role : roles) {
                boolean granted = role.permissionCodes().contains(permission.code());
                // A glyph as well as a colour. DOC-00 prohibits colour as the sole carrier of meaning,
                // and a grid of green and grey squares is the clearest instance of that failure.
                body.append("<td><span class=\"grant ")
                        .append(granted ? "grant-yes\">&check;" : "grant-no\">&middot;")
                        .append("</span><span class=\"visually-hidden\">")
                        .append(Html.text(messages.get(granted
                                ? "admin.roles.grantedLabel" : "admin.roles.notGrantedLabel")))
                        .append("</span></td>");
            }
            body.append("</tr>");
        }
        body.append("</tbody></table></div></div>");

        Page.Context context = Page.Context.of("admin.roles.title", "/roles", Optional.ofNullable(principal))
                .withSubtitle("admin.roles.subtitle")
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("admin.roles.title"), Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    /**
     * {@code GET /roles/{id}} — the editor for one role.
     *
     * <p>Separate from the matrix because they answer different questions. The matrix answers "who can do
     * what" across every role at once and is read at a glance; the editor answers "what should this role
     * be" and is a form. Making the matrix itself editable would put a hundred checkboxes in one submission
     * where a mistake in any cell is a silent authorization change in a role nobody was looking at.
     */
    public Dispatcher.Response roleEditor(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();
        UUID id;
        try {
            id = UUID.fromString(request.pathVariables().get("id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dispatcher.Response.notFound();
        }
        Optional<AccountService.RoleRow> found = accounts.role(tenantId, id);
        if (found.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        AccountService.RoleRow role = found.orElseThrow();
        List<AccountService.PermissionRow> permissions = accounts.permissions(tenantId);
        AccountService.RoleRemoval removal = accounts.roleRemoval(tenantId, id);

        StringBuilder body = new StringBuilder(8192);
        boolean elevated = principal != null && principal.stepUpAuthenticated();
        if (request.query().containsKey("saved")) {
            body.append(notice(messages.get("admin.role.saved")));
        }
        if (request.query().containsKey("rejected")) {
            body.append("<div class=\"banner banner-danger\" role=\"alert\"><div>")
                    .append(Html.text(messages.get("admin.role.rejected"))).append("</div></div>");
        }
        if (request.query().containsKey("notDeletable")) {
            body.append("<div class=\"banner banner-danger\" role=\"alert\"><div>")
                    .append(Html.text(messages.get("admin.role.notDeletableNow"))).append("</div></div>");
        }
        if (!elevated) {
            body.append(Forms.elevationPrompt(messages, "/roles/" + id));
        }
        if (!role.active()) {
            body.append("<div class=\"banner banner-danger\" role=\"status\"><div><strong>")
                    .append(Html.text(messages.get("admin.role.retiredTitle")))
                    .append("</strong> ")
                    .append(Html.text(messages.get("admin.role.retiredBody")))
                    .append("</div></div>");
        }

        // Identity: the label is editable, the code is not.
        body.append("<form method=\"post\" action=\"/roles/").append(Html.text(id.toString()))
                .append("\"><section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("admin.role.identity"))).append("</h2>")
                .append(role.fromTemplate()
                        ? "<span class=\"pill pill-info\">"
                                + Html.text(messages.get("admin.role.fromTemplate")) + "</span>"
                        : "")
                .append("</div><div class=\"card-body\">")
                .append(Forms.fieldsetOpen(elevated))
                .append(Forms.idempotencyField())
                .append("<div class=\"form-grid\">")
                .append(Forms.field(messages, "label", "admin.role.label", "text", role.label(), true,
                        messages.get("admin.role.labelHint")))
                .append(Forms.field(messages, "description", "admin.role.description", "text",
                        role.description() == null ? "" : role.description(), false, null))
                .append("</div>")
                // The code, shown and not editable. Immutable because derived_from_template and every
                // audit entry reference it; a renamed code turns those into dangling text.
                .append("<p class=\"fs-12 muted mt-6\">")
                .append(Html.text(messages.get("admin.role.codeFixed", role.code())))
                .append("</p>").append(Forms.fieldsetClose()).append("</div></section>");

        // The permission set, grouped by catalogue domain.
        body.append("<section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("admin.role.permissions"))).append("</h2>")
                .append("<span class=\"pill pill-unknown\">")
                .append(role.permissionCodes().size()).append(" / ").append(permissions.size())
                .append("</span></div><div class=\"card-body\">")
                .append(Forms.fieldsetOpen(elevated));
        String domain = null;
        for (AccountService.PermissionRow permission : permissions) {
            if (!permission.domain().equals(domain)) {
                if (domain != null) {
                    body.append("</div>");
                }
                domain = permission.domain();
                body.append("<div class=\"perm-domain\"><div class=\"perm-domain-label\">")
                        .append(Html.text(domain)).append("</div>");
            }
            boolean granted = role.permissionCodes().contains(permission.code());
            body.append("<label class=\"perm-row\">")
                    .append("<input type=\"checkbox\" name=\"permission\" value=")
                    .append(Html.attribute(permission.code()))
                    .append(granted ? " checked" : "").append(">")
                    .append("<span class=\"perm-code\">").append(Html.text(permission.code()))
                    .append("</span>")
                    .append("<span class=\"perm-label\">").append(Html.text(permission.label()))
                    .append("</span>")
                    .append(permission.restricted()
                            ? pill("danger", messages.get("admin.roles.restricted")) : "")
                    .append(permission.requiresStepUp()
                            ? pill("info", messages.get("admin.roles.stepUp")) : "")
                    .append("</label>");
        }
        if (domain != null) {
            body.append("</div>");
        }
        // Why the whole set is submitted rather than one box at a time, on the page: a partial submission
        // that looked like a save would leave a role holding permissions the editor appeared to remove.
        body.append("<p class=\"fs-12 muted mt-6\">")
                .append(Html.text(messages.get("admin.role.replaceNote")))
                .append("</p>").append(Forms.fieldsetClose()).append("</div></section>")
                .append("<div class=\"form-actions\">")
                .append(elevated
                        ? "<button class=\"btn btn-primary\" type=\"submit\">"
                                + Html.text(messages.get("admin.role.save")) + "</button>"
                        : "")
                .append("<a class=\"btn\" href=\"/roles\">")
                .append(Html.text(messages.get("admin.role.cancel")))
                .append("</a></div></form>");

        // Removal, described before it is offered.
        body.append("<section class=\"card mt-6\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("admin.role.removal"))).append("</h2></div>")
                .append("<div class=\"card-body col gap-3\">")
                .append("<p class=\"fs-13\">")
                .append(Html.text(removal.deletable()
                        ? messages.get("admin.role.deletable")
                        : messages.get("admin.role.notDeletable", removal.everAssigned(),
                                removal.liveAssignments())))
                .append("</p><div class=\"form-actions\">");
        if (role.active()) {
            body.append(postButton(messages, "/roles/" + id + "/retire", "admin.role.retire", "",
                    elevated));
        } else {
            body.append(postButton(messages, "/roles/" + id + "/restore", "admin.role.restore",
                    "btn-primary", elevated));
        }
        if (removal.deletable()) {
            body.append(postButton(messages, "/roles/" + id + "/delete", "admin.role.delete",
                    "btn-danger", elevated));
        }
        body.append("</div></div></section>");

        Page.Context context = Page.Context.of("admin.role.title", "/roles",
                        Optional.ofNullable(principal))
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("admin.roles.title"), Optional.of("/roles")),
                        new Page.Crumb(role.label(), Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    /** {@code POST /roles} — create. Class E. */
    public Dispatcher.Response roleCreate(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        Optional<UUID> created = accounts.createRole(tenantId, principal.principalId(),
                form.getOrDefault("code", ""), form.getOrDefault("label", ""),
                form.getOrDefault("description", ""));
        // A new role holds NOTHING. Deliberate: a role created with a default permission set is a grant
        // nobody chose, and the editor is one click away. SEC-AUZ-014 denies on an empty grant, so the
        // role is inert until its permissions are set.
        return created.map(id -> redirect("/roles/" + id + "?saved=1"))
                .orElseGet(() -> redirect("/roles?duplicate=1"));
    }

    /** {@code POST /roles/{id}} — rename and replace the permission set. Class E. */
    public Dispatcher.Response roleSave(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id;
        try {
            id = UUID.fromString(request.pathVariables().get("id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dispatcher.Response.notFound();
        }
        if (accounts.role(tenantId, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        // Every checked box, not the last one. parseForm keeps one value per name, which is right for a
        // policy form and wrong here — a checkbox group is genuinely multi-valued, and using the
        // last-wins parser would have saved exactly one permission per role.
        java.util.Set<String> codes = multi(request.rawForm().orElse(""), "permission");
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        accounts.updateRole(tenantId, principal.principalId(), id, form.get("label"),
                form.get("description"));
        try {
            accounts.setRolePermissions(tenantId, principal.principalId(), id, codes);
        } catch (java.sql.SQLException e) {
            // A code not in the product-fixed catalogue is refused by the foreign key. Reported rather
            // than filtered: filtering would accept a tampered form and grant less than it appeared to.
            return redirect("/roles/" + id + "?rejected=1");
        }
        return redirect("/roles/" + id + "?saved=1");
    }

    /** {@code POST /roles/{id}/retire}. Class E. */
    public Dispatcher.Response roleRetire(Dispatcher.Request request) throws Exception {
        return roleLifecycle(request, true);
    }

    /** {@code POST /roles/{id}/restore}. Class E. */
    public Dispatcher.Response roleRestore(Dispatcher.Request request) throws Exception {
        return roleLifecycle(request, false);
    }

    /** {@code POST /roles/{id}/delete}. Class E, and only for a role never assigned. */
    public Dispatcher.Response roleDelete(Dispatcher.Request request) throws Exception {
        UUID id;
        try {
            id = UUID.fromString(request.pathVariables().get("id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dispatcher.Response.notFound();
        }
        boolean deleted = accounts.deleteRole(tenantId, id);
        // Not deleted means it has been assigned at some point, and the assignment is the record of a
        // decision. The editor offers retirement instead, and says why.
        return redirect(deleted ? "/roles?deleted=1" : "/roles/" + id + "?notDeletable=1");
    }

    private Dispatcher.Response roleLifecycle(Dispatcher.Request request, boolean retire)
            throws Exception {
        Principal principal = request.principal();
        UUID id;
        try {
            id = UUID.fromString(request.pathVariables().get("id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dispatcher.Response.notFound();
        }
        if (retire) {
            accounts.retireRole(tenantId, principal.principalId(), id);
        } else {
            accounts.restoreRole(tenantId, principal.principalId(), id);
        }
        return redirect("/roles/" + id + "?saved=1");
    }

    /** Every value submitted under one field name. A checkbox group is multi-valued. */
    private static java.util.Set<String> multi(String body, String name) {
        java.util.Set<String> values = new java.util.LinkedHashSet<>();
        for (String pair : body.split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, equals),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (name.equals(key)) {
                values.add(java.net.URLDecoder.decode(
                        pair.substring(equals + 1).replace('+', ' '),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static String postButton(Messages messages, String action, String labelKey, String tone,
            boolean elevated) {
        return "<form method=\"post\" action=" + Html.attribute(action) + ">"
                + Forms.fieldsetOpen(elevated)
                + Forms.idempotencyField()
                + "<button class=\"btn btn-sm " + tone + "\" type=\"submit\">"
                + Html.text(messages.get(labelKey)) + "</button>"
                + Forms.fieldsetClose() + "</form>";
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code GET /security-policy}. */
    public Dispatcher.Response policyForm(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();
        PasswordPolicy.Settings policy = accounts.policy(tenantId);
        long corpus = accounts.breachCorpusSize(tenantId);

        StringBuilder body = new StringBuilder(4096);
        boolean elevated = principal != null && principal.stepUpAuthenticated();
        if (request.query().containsKey("saved")) {
            body.append(notice(messages.get("admin.policy.saved")));
        }
        if (request.query().containsKey("rejected")) {
            body.append("<div class=\"banner banner-danger\" role=\"alert\"><div>")
                    .append(Html.text(messages.get("admin.policy.rejected"))).append("</div></div>");
        }
        if (!elevated) {
            body.append(Forms.elevationPrompt(messages, "/security-policy"));
        }
        body.append("<div class=\"banner\" role=\"note\"><div><strong>")
                .append(Html.text(messages.get("admin.policy.noCompositionTitle")))
                .append("</strong> ")
                .append(Html.text(messages.get("admin.policy.noCompositionBody")))
                .append("</div></div>");

        body.append("<form method=\"post\" action=\"/security-policy\">")
                .append(Forms.fieldsetOpen(elevated))
                .append(Forms.idempotencyField())
                .append("<section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("admin.policy.credential"))).append("</h2></div>")
                .append("<div class=\"card-body\"><div class=\"form-grid\">")
                .append(Forms.field(messages, "minimum_length", "admin.policy.minimumLength", "number",
                        String.valueOf(policy.minimumLength()), true,
                        messages.get("admin.policy.minimumLengthHint")))
                .append(Forms.field(messages, "maximum_length", "admin.policy.maximumLength", "number",
                        String.valueOf(policy.maximumLength()), true,
                        messages.get("admin.policy.maximumLengthHint")))
                .append(Forms.field(messages, "reuse_history", "admin.policy.reuseHistory", "number",
                        String.valueOf(policy.reuseHistory()), true,
                        messages.get("admin.policy.reuseHistoryHint")))
                .append(Forms.field(messages, "maximum_age_days", "admin.policy.maximumAge", "number",
                        String.valueOf(policy.maximumAgeDays()), true,
                        messages.get("admin.policy.maximumAgeHint")))
                .append("</div><div class=\"col mt-6 gap-3\">")
                .append(Forms.checkbox(messages, "breach_check_at_set",
                        "admin.policy.breachAtSet", policy.breachCheckAtSet()))
                .append(Forms.checkbox(messages, "breach_check_at_authentication",
                        "admin.policy.breachAtAuth", policy.breachCheckAtAuthentication()))
                .append(Forms.checkbox(messages, "mfa_required_for_all",
                        "admin.policy.mfaForAll", policy.mfaRequiredForAll()))
                .append("</div>")
                // The corpus size beside the switch that depends on it. A breach check enabled over
                // twenty-five entries passes almost everything, which looks exactly like a check that
                // works — PP-1, applied to one of the platform's own controls.
                .append("<p class=\"fs-12 muted mt-6\">")
                .append(Html.text(messages.get(corpus < 1000
                                ? "admin.policy.corpusThin" : "admin.policy.corpusSize", corpus)))
                .append("</p></div></section>");

        body.append("<section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("admin.policy.session"))).append("</h2></div>")
                .append("<div class=\"card-body\"><div class=\"form-grid\">")
                .append(Forms.field(messages, "session_absolute_seconds",
                        "admin.policy.sessionAbsolute", "number",
                        String.valueOf(policy.sessionAbsoluteSeconds()), true,
                        messages.get("admin.policy.sessionAbsoluteHint")))
                .append(Forms.field(messages, "session_idle_seconds", "admin.policy.sessionIdle",
                        "number", String.valueOf(policy.sessionIdleSeconds()), true,
                        messages.get("admin.policy.sessionIdleHint")))
                .append("</div></div></section>");

        body.append("<div class=\"form-actions\"><button class=\"btn btn-primary\" type=\"submit\">")
                .append(Html.text(messages.get("admin.policy.save")))
                .append("</button></div>").append(Forms.fieldsetClose()).append("</form>");

        Page.Context context = Page.Context.of("admin.policy.title", "/security-policy", Optional.ofNullable(principal))
                .withSubtitle("admin.policy.subtitle")
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("admin.policy.title"), Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    /** {@code POST /security-policy}. Class E — configuration, so step-up and a replay key. */
    public Dispatcher.Response policySave(Dispatcher.Request request) throws Exception {
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        PasswordPolicy.Settings current = accounts.policy(tenantId);

        // Every value falls back to the CURRENT one, not to the product default. A field that failed to
        // parse must not silently reset a tenant's configured minimum to 12; the engine's CHECK
        // constraints are the bound on what is acceptable, and a value they reject surfaces as an error
        // rather than as a quiet substitution.
        PasswordPolicy.Settings updated = new PasswordPolicy.Settings(
                integer(form, "minimum_length", current.minimumLength()),
                integer(form, "maximum_length", current.maximumLength()),
                integer(form, "reuse_history", current.reuseHistory()),
                flag(form, "breach_check_at_set", current.breachCheckAtSet()),
                flag(form, "breach_check_at_authentication", current.breachCheckAtAuthentication()),
                integer(form, "maximum_age_days", current.maximumAgeDays()),
                flag(form, "mfa_required_for_all", current.mfaRequiredForAll()),
                integer(form, "session_absolute_seconds", current.sessionAbsoluteSeconds()),
                integer(form, "session_idle_seconds", current.sessionIdleSeconds()));

        try {
            accounts.updatePolicy(tenantId, updated);
        } catch (java.sql.SQLException e) {
            // A constraint violation is the engine refusing a value outside the product bound. Reported
            // as a rejected form rather than a 500: the caller typed something out of range, which is a
            // user error, and the message names the field group rather than echoing the engine's text.
            return redirect("/security-policy?rejected=1");
        }
        return redirect("/security-policy?saved=1");
    }

    // ----------------------------------------------------------------------------------------------

    private static String profileCard(Messages messages, AccountService.UserRow user) {
        return "<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">"
                + Html.text(messages.get("admin.user.profile")) + "</h2></div>"
                + "<div class=\"card-body col gap-3\">"
                + definition(messages.get("admin.user.username"), user.username())
                + definition(messages.get("admin.user.email"),
                        user.email() == null || user.email().isBlank() ? "—" : user.email())
                + definition(messages.get("admin.user.displayName"), user.displayName())
                + definition(messages.get("admin.user.lifecycle"), user.lifecycleState())
                + definition(messages.get("admin.user.mfa"), messages.get(user.mfaEnrolled()
                        ? "admin.user.mfaEnrolled" : "admin.user.mfaNotEnrolled"))
                + definition(messages.get("admin.user.mustChange"), messages.get(
                        user.mustChangePassword() ? "admin.user.yes" : "admin.user.no"))
                + definition(messages.get("admin.user.lastSeen"), user.lastAuthenticatedAt() == null
                        ? messages.get("admin.users.never") : user.lastAuthenticatedAt())
                + definition(messages.get("admin.user.sessions"), String.valueOf(user.liveSessions()))
                + "</div></section>";
    }

    private static String assignmentCard(Messages messages, AccountService.UserRow user,
            List<Map<String, String>> assignments, Principal principal, boolean elevated) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("admin.user.assignments")))
                .append("</h2></div><div class=\"card-body col gap-3\">");
        if (assignments.isEmpty()) {
            out.append("<p class=\"fs-13\">")
                    .append(Html.text(messages.get("admin.user.noAssignments")))
                    .append("</p>");
        }
        for (Map<String, String> assignment : assignments) {
            out.append("<div class=\"row between gap-3\"><div class=\"col\">")
                    .append("<span class=\"fs-13\">")
                    .append(Html.text(assignment.get("role_label"))).append("</span>")
                    // The scope, always. A role shown without its scope is a grant whose reach is
                    // invisible, and DOC-07 §7 makes the scope part of the grant rather than an attribute
                    // of the role.
                    .append("<span class=\"fs-11 muted\">")
                    .append(Html.text("TENANT".equals(assignment.get("scope_mode"))
                            ? messages.get("admin.user.scopeTenant")
                            : messages.get("admin.user.scopeNode", assignment.get("scope_mode"),
                                    assignment.get("scope_node"))))
                    .append("</span></div>");
            if (principal != null && principal.holds(MANAGE_ROLES)) {
                out.append("<form method=\"post\" action=\"/users/")
                        .append(Html.text(user.id().toString())).append("/roles/revoke\">")
                        .append(Forms.fieldsetOpen(elevated))
                        .append(Forms.idempotencyField())
                        .append("<input type=\"hidden\" name=\"assignment\" value=")
                        .append(Html.attribute(assignment.get("id"))).append(">")
                        .append("<button class=\"btn btn-sm\" type=\"submit\">")
                        .append(Html.text(messages.get("admin.user.revoke")))
                        .append("</button>").append(Forms.fieldsetClose()).append("</form>");
            }
            out.append("</div>");
        }
        out.append("</div></section>");
        return out.toString();
    }

    private static String grantCard(Messages messages, AccountService.UserRow user,
            List<AccountService.RoleRow> roles, boolean elevated) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card mb-6\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("admin.user.grant"))).append("</h2>")
                .append("<p class=\"fs-12 muted\">")
                .append(Html.text(messages.get("admin.user.grantLede")))
                .append("</p></div><div class=\"card-body\">")
                .append("<form method=\"post\" action=\"/users/")
                .append(Html.text(user.id().toString())).append("/roles\">")
                .append(Forms.fieldsetOpen(elevated))
                .append(Forms.idempotencyField())
                .append("<div class=\"form-grid\">");

        List<Map.Entry<String, String>> roleOptions = roles.stream()
                .map(role -> Map.entry(role.id().toString(), role.label()))
                .toList();
        out.append(Forms.select(messages, "role", "admin.user.role", roleOptions,
                roleOptions.isEmpty() ? "" : roleOptions.get(0).getKey()));
        out.append(Forms.select(messages, "scope_mode", "admin.user.scopeMode", List.of(
                        Map.entry("SUBTREE", messages.get("admin.user.scopeModeSubtree")),
                        Map.entry("NODE_ONLY", messages.get("admin.user.scopeModeNodeOnly")),
                        Map.entry("TENANT", messages.get("admin.user.scopeModeTenant"))),
                "SUBTREE"));
        // The node is typed as an identifier rather than picked from a list, and that is a gap rather
        // than a design: a scope picker needs the org tree filtered to what the ACTING principal can
        // reach, because a picker listing nodes the granter cannot see leaks the organization's shape.
        // Product principle 4 also makes the picker a usability feature and never the control — the
        // engine's ck_assignment__scope_present is the control.
        out.append(Forms.field(messages, "scope_node", "admin.user.scopeNodeLabel", "text", "", false,
                messages.get("admin.user.scopeNodeHint")));
        out.append("</div><div class=\"form-actions\">")
                .append("<button class=\"btn btn-primary\" type=\"submit\">")
                .append(Html.text(messages.get("admin.user.grantAction")))
                .append("</button></div>").append(Forms.fieldsetClose())
                .append("</form></div></section>");
        return out.toString();
    }

    private static String resetCard(Messages messages, AccountService.UserRow user, boolean elevated) {
        return "<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">"
                + Html.text(messages.get("admin.reset.title")) + "</h2>"
                + "<p class=\"fs-12 muted\">" + Html.text(messages.get("admin.reset.lede"))
                + "</p></div><div class=\"card-body\">"
                // Stated on the page, not only in a comment. An administrator who expects to type a
                // password and finds no field needs to know that is the design.
                + "<p class=\"fs-13\">" + Html.text(messages.get("admin.reset.noChosenValue"))
                + "</p>"
                + "<form method=\"post\" action=\"/users/" + Html.text(user.id().toString())
                + "/reset\">" + Forms.fieldsetOpen(elevated) + "<div class=\"form-actions\">"
                + "<button class=\"btn btn-primary\" type=\"submit\">"
                + Html.text(messages.get("admin.reset.action")) + "</button>"
                + "<span class=\"fs-12 muted\">" + Html.text(messages.get("admin.reset.effect"))
                + "</span></div>" + Forms.fieldsetClose() + "</form></div></section>";
    }

    private static int integer(Map<String, String> form, String name, int fallback) {
        try {
            return Integer.parseInt(form.getOrDefault(name, "").strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean flag(Map<String, String> form, String name, boolean fallback) {
        String value = form.get(name);
        return value == null ? fallback : "true".equalsIgnoreCase(value);
    }

    private static String lifecycleTone(String state) {
        return switch (state) {
            case "ACTIVE" -> "ok";
            case "INVITED" -> "info";
            case "SUSPENDED" -> "danger";
            default -> "unknown";
        };
    }

    private static String kpi(Messages messages, String labelKey, int value) {
        return "<div class=\"card kpi\"><span class=\"kpi-label\">"
                + Html.text(messages.get(labelKey)) + "</span>"
                + "<span class=\"kpi-value\">" + value + "</span></div>";
    }

    private static String th(Messages messages, String key) {
        return "<th scope=\"col\">" + Html.text(messages.get(key)) + "</th>";
    }

    private static String definition(String label, String value) {
        return "<div class=\"row between gap-3\">"
                + "<span class=\"fs-12 muted\">" + Html.text(label) + "</span>"
                + "<span class=\"fs-13\">" + Html.text(value) + "</span></div>";
    }

    private static String pill(String tone, String label) {
        return "<span class=\"pill pill-" + tone + "\">" + Html.text(label) + "</span>";
    }

    private static String notice(String message) {
        return "<div class=\"banner\" role=\"status\">" + Html.text(message) + "</div>";
    }

    private static Dispatcher.Response html(String markup) {
        return new Dispatcher.Response(200, new InterfaceResource.Raw(markup),
                Map.of("Content-Type", "text/html; charset=utf-8"));
    }

    private static Dispatcher.Response redirect(String location) {
        return new Dispatcher.Response(303, new InterfaceResource.Raw(""), Map.of(
                "Location", location, "Content-Type", "text/html; charset=utf-8"));
    }
}
