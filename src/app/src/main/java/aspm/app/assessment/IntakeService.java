package aspm.app.assessment;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Raising an assessment request. {@code PRD-PTR-021}, {@code PRD-ASM-003}, DOC-09 §3.
 *
 * <h2>The requester names a project and nothing else about where it lives</h2>
 *
 * <p>The application and the organization node are <b>derived</b> from the project through the
 * composition graph and the ownership edge. A form that asked for all three would be a form where
 * two of the answers can contradict the inventory, and the contradiction surfaces months later as a
 * request filed against an application nobody who owns it can see.
 *
 * <p>Product principle 4 is the harder half: the picker only offers projects in the caller's scope,
 * and <b>that is a usability feature, never the control</b>. {@link #create} re-reads the project
 * through the scoped query before it writes anything, so a client that posts an identifier it was
 * never offered gets the same answer as one that posts a made-up one.
 *
 * <h2>Two accounts per role, and why the rule is here rather than in the form</h2>
 *
 * <p>{@code PRD-PTR-021} asks intake to capture what an assessment needs to actually start. An
 * authorization test needs two accounts at the same privilege level — one to act and one to be acted
 * upon — and a role supplied with a single account cannot be tested for horizontal privilege
 * escalation at all. That is the defect class this product exists to find, so a request that would
 * make it untestable is refused rather than accepted and discovered on the first day of testing.
 *
 * <p>The rule lives in the service because a browser-side check is a check an API caller skips.
 */
public final class IntakeService {

    /** How many accounts a named role must carry. See the class note. */
    public static final int ACCOUNTS_PER_ROLE = 2;

    /** One application role, and the accounts provided for it. */
    public record RoleAccounts(String roleName, String description, List<Account> accounts) {
    }

    /**
     * One test account.
     *
     * <p>{@code credentialRef} is a REFERENCE, and the schema enforces the shape — {@code vault:…},
     * {@code onepassword:…}. A password typed into this field would be a credential stored in a
     * platform that already concentrates more of them than anything it protects.
     */
    public record Account(String username, String credentialRef, String password,
            boolean mfaEnrolled, String mfaBypassRef) {
    }

    /** One environment the assessment may touch. */
    public record Environment(String envType, String baseUrl, boolean vpnRequired,
            boolean protectiveControlPresent, boolean bypassArranged, String bypassMethod,
            String testWindowConstraints) {
    }

    /** Everything the intake form collects. */
    public record Draft(String title, UUID projectId, UUID triggerId, String detail,
            java.time.LocalDate dueAt, List<RoleAccounts> roles, List<Environment> environments,
            Integer apiCount, String gitRepository, String technologyStack, String notes) {
    }

    /** Why a draft was refused, in a form the interface can put beside the offending field. */
    public static final class RejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final String field;

        RejectedException(String code, String field, String message) {
            super(message);
            this.code = code;
            this.field = field;
        }

        public String code() {
            return code;
        }

        public String field() {
            return field;
        }
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());
    private final aspm.app.inventory.ProjectQuery projects;
    private final aspm.app.authz.ObjectAuthority authority;
    private final CredentialCustody custody =
            CredentialCustody.from(System.getenv());

    public IntakeService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
        this.projects = new aspm.app.inventory.ProjectQuery(dataSource);
        this.authority = new aspm.app.authz.ObjectAuthority(dataSource);
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Creates a request and everything intake collected with it, in one transaction.
     *
     * <p>One transaction because the pieces are not independently useful: a request with no role
     * accounts is a request an assessor cannot start, and a half-written intake form that survived a
     * failure would look complete on the board.
     *
     * @return the new request's identifier and code
     */
    public Created create(Principal principal, Draft draft) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required");
        validate(draft);

        // Re-read through the SCOPED query. SEC-AUZ-017: the picker was filtered for convenience and
        // this is the control. A project the caller cannot reach resolves to nothing here.
        var project = projects.project(principal, draft.projectId())
                .orElseThrow(() -> new RejectedException("PROJECT_UNKNOWN", "projectId",
                        "that project is not one you can raise a request for"));
        // *** THE AUTHORITY CHECK, AND IT WAS MISSING. ***
        //
        // The first version computed `raisable` for the picker and never consulted it here, so a
        // developer with no grant on a project could still POST its identifier and raise a request
        // against it. Caught by driving the API as that developer rather than by reading the code —
        // being able to SEE a project and being allowed to ask for work against it are two questions,
        // and only the first one was being asked.
        //
        // Product principle 4 in one line: the filtered picker is a usability feature, never the
        // control. This is the control.
        if (!authority.mayRaiseRequestFor(principal, project.id())) {
            throw new RejectedException("PROJECT_NOT_DELEGATED", "projectId",
                    "you can see that project but have not been given the right to request an "
                            + "assessment of it. Its owner, or the security team, can grant that.");
        }
        if (project.owningNodeId() == null) {
            // Refused rather than defaulted. requested_org_node_id is NOT NULL and decides who can
            // see the request afterwards; picking a node on the requester's behalf would file it
            // somewhere nobody expects.
            throw new RejectedException("PROJECT_UNOWNED", "projectId",
                    "that project has no owning team, so a request has no organization to belong to");
        }

        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                UUID typeId = singleAssessmentType(connection);
                String code = nextCode(connection);
                UUID requestId = insertRequest(connection, principal, draft, project, typeId, code);
                insertScopeAssets(connection, principal, requestId, project,
                        fullApplicationReview(connection, draft.triggerId()));
                insertRoleAccounts(connection, principal, requestId, draft);
                insertEnvironments(connection, principal, requestId, draft);
                audit.domainChange(connection, principal, "assessment_request",
                        aspm.kernel.audit.contract.DomainChangeKind.CREATED, requestId,
                        aspm.app.audit.AuditScopes.ofNode(connection, project.owningNodeId()),
                        java.util.Map.of("code", code,
                                "project_id", project.id().toString(),
                                // Whether credentials were handed over with the request. It is the
                                // fact that decides what has to be destroyed at closure, and by then
                                // the secret is gone and cannot answer for itself.
                                "role_accounts", Integer.valueOf(
                                        draft.roles() == null ? 0 : draft.roles().size())));
                connection.commit();
                return new Created(requestId, code);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** The identifiers a caller needs to navigate to what they just created. */
    public record Created(UUID id, String code) {
    }

    // ----------------------------------------------------------------------------------------------

    private void validate(Draft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank()) {
            throw new RejectedException("TITLE_REQUIRED", "title",
                    "a request needs a title somebody scanning the board can recognise");
        }
        if (draft.title().length() > 200) {
            throw new RejectedException("TITLE_TOO_LONG", "title",
                    "a title over 200 characters is a description; put it in the detail");
        }
        if (draft.projectId() == null) {
            throw new RejectedException("PROJECT_REQUIRED", "projectId",
                    "choose the project being assessed");
        }
        if (draft.detail() == null || draft.detail().isBlank()) {
            throw new RejectedException("DETAIL_REQUIRED", "detail",
                    "say what is to be assessed; an assessor cannot scope from a title");
        }
        // Required, and the requester's answer is a REQUEST rather than a commitment — the assessor
        // adjusts it once the scope is understood. Asking for it here is what makes the difference
        // between the two visible, and a request with no date is one nothing can report as late
        // (PP-6: waiting is visible and attributed).
        if (draft.dueAt() == null) {
            throw new RejectedException("DUE_DATE_REQUIRED", "dueAt",
                    "say when this is needed by. The assessor can move it once they have scoped the "
                            + "work, but a request with no date is one nothing can report as late.");
        }
        if (draft.dueAt().isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC))) {
            throw new RejectedException("DUE_DATE_PAST", "dueAt", "that date has passed");
        }
        // Accounts are OPTIONAL. Some assessments need none — an unauthenticated surface review, a
        // dependency audit — and demanding them made the form refuse work it should accept. Where a
        // role IS named the two-accounts rule still holds, because a named role with one account is
        // a role that cannot be tested for horizontal privilege escalation.
        Set<String> seenRoles = new LinkedHashSet<>();
        for (RoleAccounts role : draft.roles() == null ? List.<RoleAccounts>of() : draft.roles()) {
            if (isBlankRole(role)) {
                continue;
            }
            String name = role.roleName() == null ? "" : role.roleName().strip();
            if (name.isEmpty()) {
                throw new RejectedException("ROLE_NAME_REQUIRED", "roles", "every role needs a name");
            }
            if (!seenRoles.add(name.toLowerCase(java.util.Locale.ROOT))) {
                throw new RejectedException("ROLE_DUPLICATED", "roles",
                        "'" + name + "' is named twice; put both accounts under one entry");
            }
            List<Account> accounts = role.accounts() == null ? List.of() : role.accounts();
            List<Account> usable = accounts.stream()
                    .filter(a -> a.username() != null && !a.username().isBlank())
                    .toList();
            if (usable.size() < ACCOUNTS_PER_ROLE) {
                throw new RejectedException("ROLE_NEEDS_TWO_ACCOUNTS", "roles",
                        "'" + name + "' has " + usable.size() + " account"
                                + (usable.size() == 1 ? "" : "s") + ". Two are needed at the same "
                                + "privilege level: testing whether one user can reach another "
                                + "user's data needs a second user to reach.");
            }
            Set<String> seenUsernames = new LinkedHashSet<>();
            for (Account account : usable) {
                if (!seenUsernames.add(account.username().strip().toLowerCase(java.util.Locale.ROOT))) {
                    throw new RejectedException("ACCOUNT_DUPLICATED", "roles",
                            "'" + account.username() + "' is listed twice under '" + name
                                    + "'; the same account twice does not make two accounts");
                }
                boolean hasPassword = account.password() != null && !account.password().isBlank();
                boolean hasReference = account.credentialRef() != null
                        && !account.credentialRef().isBlank();
                if (hasPassword && account.password().length() > CredentialCustody.MAX_LENGTH) {
                    throw new RejectedException("CREDENTIAL_TOO_LONG", "roles",
                            "'" + account.username() + "' has a credential longer than "
                                    + CredentialCustody.MAX_LENGTH + " characters; check what was "
                                    + "pasted");
                }
                if (hasPassword && !custody.available()) {
                    // Refused, never stored in the clear. PP-9: a deployment with no key cannot hold
                    // a password, and saying so is the only honest option — the alternative that gets
                    // written under pressure is a plaintext fallback.
                    throw new RejectedException("CUSTODY_UNAVAILABLE", "roles",
                            "this deployment has no credential key configured, so it cannot hold a "
                                    + "password. Submit a reference to where the credential lives, "
                                    + "or ask an administrator to configure "
                                    + CredentialCustody.KEY_VARIABLE + ".");
                }
                if (hasReference && !account.credentialRef().matches("^[a-z0-9][a-z0-9+.-]{1,31}:.+")) {
                    throw new RejectedException("CREDENTIAL_NOT_A_REFERENCE", "roles",
                            "'" + account.credentialRef() + "' is not a reference. Use a scheme and "
                                    + "a location, such as vault:payments/uat/admin or "
                                    + "teams:sent to the assessor.");
                }
                if (account.mfaEnrolled()
                        && (account.mfaBypassRef() == null || account.mfaBypassRef().isBlank())) {
                    throw new RejectedException("MFA_BYPASS_REQUIRED", "roles",
                            "'" + account.username() + "' has a second factor and no way for the "
                                    + "assessor to satisfy it, which stops testing on day one");
                }
            }
        }

        for (Environment environment : draft.environments() == null
                ? List.<Environment>of() : draft.environments()) {
            if (environment.baseUrl() == null || environment.baseUrl().isBlank()) {
                throw new RejectedException("ENVIRONMENT_URL_REQUIRED", "environments",
                        "every environment needs the address the assessor will point at");
            }
            if (!ENV_TYPES.contains(environment.envType())) {
                throw new RejectedException("ENVIRONMENT_TYPE_INVALID", "environments",
                        "'" + environment.envType() + "' is not an environment this platform accepts");
            }
            if (environment.protectiveControlPresent() && !environment.bypassArranged()) {
                // The schema enforces this too. Said here because the requester can act on it: a WAF
                // in front of a test target means the assessment measures the WAF.
                throw new RejectedException("BYPASS_REQUIRED", "environments",
                        "a protective control is in front of " + environment.baseUrl()
                                + " and no bypass is arranged, so the assessment would test the "
                                + "control rather than the application");
            }
            if (environment.bypassArranged()
                    && (environment.bypassMethod() == null || environment.bypassMethod().isBlank())) {
                throw new RejectedException("BYPASS_METHOD_REQUIRED", "environments",
                        "say how the bypass works, or the assessor cannot use it");
            }
        }

        if (draft.apiCount() != null && (draft.apiCount() < 0 || draft.apiCount() > 100000)) {
            throw new RejectedException("API_COUNT_INVALID", "apiCount",
                    "that is not a plausible number of endpoints");
        }
    }

    /**
     * Whether this reason for assessing means the whole application.
     *
     * <p>Read from {@code counts_as_full_review} on the tenant's own trigger row, never from a code.
     * DOC-09 and ADR-027 make the trigger list tenant data — a tenant that calls its full review
     * something else keeps the behaviour, and a hardcoded code here would silently stop pulling in
     * sibling projects the first time somebody renamed it.
     */
    private static boolean fullApplicationReview(Connection connection, UUID triggerId)
            throws SQLException {
        if (triggerId == null) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT counts_as_full_review FROM assessment_trigger WHERE id = ?")) {
            statement.setObject(1, triggerId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getBoolean(1);
            }
        }
    }

    private static final Set<String> ENV_TYPES =
            Set.of("UAT", "STAGING", "PREPROD", "PROD_READONLY");

    /**
     * The one assessment type this deployment configures.
     *
     * <p>Chosen rather than asked for. A tenant with one type would be asking every requester to
     * answer a question with one possible answer; a tenant with several needs a picker, and this
     * throws rather than guessing so the gap is visible the moment a second type is configured.
     */
    private static UUID singleAssessmentType(Connection connection) throws SQLException {
        List<UUID> types = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM assessment_type ORDER BY id");
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                types.add(results.getObject(1, UUID.class));
            }
        }
        if (types.isEmpty()) {
            throw new RejectedException("NO_ASSESSMENT_TYPE", null,
                    "no assessment type is configured, so a request has no workflow to follow");
        }
        if (types.size() > 1) {
            throw new RejectedException("ASSESSMENT_TYPE_AMBIGUOUS", null,
                    "this deployment configures more than one assessment type and the intake form "
                            + "does not yet ask which one applies");
        }
        return types.get(0);
    }

    /**
     * The next request code.
     *
     * <p>Derived from the highest code in the current year rather than from a count, because a count
     * reuses a number as soon as anything is deleted. The unique constraint is the real guard: two
     * concurrent requests race here, the loser's insert fails, and the caller retries.
     */
    private static String nextCode(Connection connection) throws SQLException {
        int year = java.time.Year.now(java.time.ZoneOffset.UTC).getValue();
        String prefix = "REQ-" + year + "-";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT max(substring(request_code from '[0-9]+$')::int) FROM assessment_request "
                        + " WHERE request_code LIKE ?")) {
            statement.setString(1, prefix + "%");
            try (ResultSet results = statement.executeQuery()) {
                int next = results.next() ? results.getInt(1) + 1 : 1;
                return prefix + String.format(java.util.Locale.ROOT, "%04d", next);
            }
        }
    }

    private UUID insertRequest(Connection connection, Principal principal, Draft draft,
            aspm.app.inventory.ProjectQuery.Project project, UUID typeId, String code)
            throws SQLException {
        // The scope descriptors are denormalized onto the request at creation, which is what every
        // scoped read filters on afterwards. Resolved from the project's owning node, never supplied.
        Map<String, Object> context = orgContext(connection, project.owningNodeId());

        Map<String, Object> profile = new LinkedHashMap<>();
        if (draft.apiCount() != null) {
            profile.put("api_count", draft.apiCount());
        }
        putIfPresent(profile, "git_repository", draft.gitRepository());
        putIfPresent(profile, "technology_stack", draft.technologyStack());
        putIfPresent(profile, "notes", draft.notes());
        profile.put("detail", draft.detail().strip());
        profile.put("project_id", project.id().toString());
        profile.put("project_name", project.name());
        if (project.applicationId() != null) {
            profile.put("application_id", project.applicationId().toString());
            profile.put("application_name", project.applicationName());
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO assessment_request (tenant_id, request_code, type_id, "
                        + "  requested_org_node_id, state, title, technical_profile, requested_by, "
                        + "  created_by, updated_by, submitted_at, scope_node_id, "
                        + "  scope_ancestor_path, scope_node_type_id, scope_criticality_id, "
                        + "  scope_hierarchy_ver, scope_resolved_at, due_at, trigger_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, now(), ?, ?, ?, ?, ?, now(), "
                        + "        ?, ?) RETURNING id")) {
            statement.setObject(1, principal.tenantId());
            statement.setString(2, code);
            statement.setObject(3, typeId);
            statement.setObject(4, project.owningNodeId());
            statement.setString(5, INITIAL_STATE);
            statement.setString(6, draft.title().strip());
            statement.setString(7, aspm.app.runtime.Json.write(profile));
            statement.setObject(8, principal.principalId());
            statement.setObject(9, principal.principalId());
            statement.setObject(10, principal.principalId());
            statement.setObject(11, project.owningNodeId());
            statement.setArray(12, connection.createArrayOf("uuid",
                    ((List<?>) context.get("ancestors")).toArray()));
            statement.setObject(13, context.get("typeId"));
            statement.setObject(14, context.get("criticalityId"));
            statement.setLong(15, ((Number) context.get("hierarchyVersion")).longValue());
            statement.setObject(16, draft.dueAt().atStartOfDay(java.time.ZoneOffset.UTC)
                    .toOffsetDateTime());
            statement.setObject(17, draft.triggerId());
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getObject(1, UUID.class);
            }
        }
    }

    /**
     * The state a new request starts in.
     *
     * <p>The lowest {@code display_order} of the active workflow definition would be the general
     * answer; this deployment's six-state workflow starts at OPEN and the transition machine refuses
     * anything it does not recognise, so a wrong value here fails loudly at the first move rather
     * than quietly.
     */
    private static final String INITIAL_STATE = "OPEN";

    private static Map<String, Object> orgContext(Connection connection, UUID nodeId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT n.type_id, coalesce(n.criticality_tier_id, "
                        + "        (SELECT id FROM criticality_tier ORDER BY ordinal LIMIT 1)), "
                        + "       (SELECT max(hierarchy_version) FROM org_closure), "
                        + "       (SELECT array_agg(cl.ancestor_id ORDER BY cl.depth DESC) "
                        + "          FROM org_closure cl WHERE cl.descendant_id = n.id) "
                        + "  FROM org_node n WHERE n.id = ?")) {
            statement.setObject(1, nodeId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new RejectedException("ORG_NODE_MISSING", "projectId",
                            "the team that owns that project no longer exists");
                }
                java.sql.Array path = results.getArray(4);
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("typeId", results.getObject(1, UUID.class));
                context.put("criticalityId", results.getObject(2, UUID.class));
                context.put("hierarchyVersion", results.getLong(3));
                context.put("ancestors", path == null ? List.of() : List.of((Object[]) path.getArray()));
                return context;
            }
        }
    }

    /**
     * The project the requester chose, and the application derived from it.
     *
     * <p>Both are stored. See the header of V029: the graph answers where a project sits now, these
     * rows answer what the request was raised against, and time makes those different questions.
     */
    private static void insertScopeAssets(Connection connection, Principal principal, UUID requestId,
            aspm.app.inventory.ProjectQuery.Project project, boolean fullApplicationReview)
            throws SQLException {
        // A FULL APPLICATION REVIEW covers the application, so it covers every project under it.
        //
        // Enumerated at intake and written as rows, not resolved at read time. A review that says
        // "the whole application" and is later reported against a project list that has since grown
        // would claim coverage of work nobody assessed — PP-1, in the direction that flatters. What
        // the reviewers agreed to cover is what existed on the day they agreed.
        if (fullApplicationReview && project.applicationId() != null) {
            try (PreparedStatement siblings = connection.prepareStatement(
                    "INSERT INTO assessment_request_scope_asset (tenant_id, request_id, asset_id, "
                            + "named_by_requester, created_by) "
                            + "SELECT ?, ?, c.asset_id, false, ? "
                            + "  FROM asset_composition c "
                            + "  JOIN asset a ON a.id = c.asset_id "
                            + "  JOIN asset_type t ON t.id = a.type_id AND t.code = 'PROJECT' "
                            + " WHERE c.root_id = ? AND c.depth > 0 "
                            + "ON CONFLICT DO NOTHING")) {
                siblings.setObject(1, principal.tenantId());
                siblings.setObject(2, requestId);
                siblings.setObject(3, principal.principalId());
                siblings.setObject(4, project.applicationId());
                siblings.executeUpdate();
            }
        }
        insertScopeAssets(connection, principal, requestId, project);
    }

    // NOTE ON ORDER, because it was wrong once. The sibling sweep writes named_by_requester = false
    // and the requester's own row writes true, and both use ON CONFLICT DO NOTHING — so whichever
    // runs first wins. Running the sweep first marked the project the requester actually chose as
    // "pulled in automatically", which is exactly the distinction the column exists to record. The
    // requester's row is therefore written LAST, and it upserts rather than skipping.

    private static void insertScopeAssets(Connection connection, Principal principal, UUID requestId,
            aspm.app.inventory.ProjectQuery.Project project) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO assessment_request_scope_asset (tenant_id, request_id, asset_id, "
                        + "named_by_requester, created_by) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, request_id, asset_id) DO UPDATE "
                        + "   SET named_by_requester = excluded.named_by_requester "
                        + " WHERE excluded.named_by_requester")) {
            statement.setObject(1, principal.tenantId());
            statement.setObject(2, requestId);
            statement.setObject(3, project.id());
            statement.setBoolean(4, true);
            statement.setObject(5, principal.principalId());
            statement.addBatch();
            if (project.applicationId() != null) {
                statement.setObject(1, principal.tenantId());
                statement.setObject(2, requestId);
                statement.setObject(3, project.applicationId());
                statement.setBoolean(4, false);
                statement.setObject(5, principal.principalId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertRoleAccounts(Connection connection, Principal principal, UUID requestId,
            Draft draft) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO assessment_request_role_account (tenant_id, request_id, role_name, "
                        + "role_description, username, credential_ref, mfa_enrolled, mfa_bypass_ref, "
                        + "created_by, secret_ciphertext, secret_nonce, secret_algorithm, "
                        + "secret_stored_at, secret_stored_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (RoleAccounts role : draft.roles() == null ? List.<RoleAccounts>of() : draft.roles()) {
                if (isBlankRole(role)) {
                    continue;
                }
                for (Account account : role.accounts()) {
                    if (account.username() == null || account.username().isBlank()) {
                        continue;
                    }
                    boolean hasPassword = account.password() != null
                            && !account.password().isBlank();
                    CredentialCustody.Sealed sealed =
                            hasPassword ? custody.seal(account.password()) : null;
                    statement.setObject(1, principal.tenantId());
                    statement.setObject(2, requestId);
                    statement.setString(3, role.roleName().strip());
                    statement.setString(4, blankToNull(role.description()));
                    statement.setString(5, account.username().strip());
                    statement.setString(6, blankToNull(account.credentialRef()));
                    statement.setBoolean(7, account.mfaEnrolled());
                    statement.setString(8, blankToNull(account.mfaBypassRef()));
                    statement.setObject(9, principal.principalId());
                    statement.setBytes(10, sealed == null ? null : sealed.ciphertext());
                    statement.setBytes(11, sealed == null ? null : sealed.nonce());
                    statement.setString(12, sealed == null ? null : sealed.algorithm());
                    statement.setObject(13, sealed == null ? null
                            : java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
                    statement.setObject(14, sealed == null ? null : principal.principalId());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private static void insertEnvironments(Connection connection, Principal principal, UUID requestId,
            Draft draft) throws SQLException {
        if (draft.environments() == null || draft.environments().isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO assessment_request_environment (tenant_id, request_id, env_type, "
                        + "base_url, protective_control_present, bypass_arranged, bypass_method, "
                        + "vpn_required, test_window_constraints, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Environment environment : draft.environments()) {
                statement.setObject(1, principal.tenantId());
                statement.setObject(2, requestId);
                statement.setString(3, environment.envType());
                statement.setString(4, environment.baseUrl().strip());
                statement.setBoolean(5, environment.protectiveControlPresent());
                statement.setBoolean(6, environment.bypassArranged());
                statement.setString(7, blankToNull(environment.bypassMethod()));
                statement.setBoolean(8, environment.vpnRequired());
                statement.setString(9, blankToNull(environment.testWindowConstraints()));
                statement.setObject(10, principal.principalId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * Destroys every credential this request holds. Called when it reaches a terminal state.
     *
     * <p>This is the control that makes custody defensible at all — see the header of V033. The
     * engagement is the whole justification for holding the value, so the value does not outlive it,
     * and it does not depend on anybody remembering.
     *
     * <p><b>The tombstone survives the value.</b> {@code secret_purged_at} and a reason stay behind,
     * because "there was a credential here and it was destroyed on this date" is what an auditor
     * asks, and a row of NULLs cannot tell that apart from a credential never lodged.
     *
     * <p>{@code rotation_required} is set at the same time and is a SEPARATE obligation: destroying
     * our copy does not change the password on the customer's system, and a credential an assessor
     * held for three weeks needs rotating whatever this platform does with its copy.
     *
     * @return how many credentials were destroyed
     */
    public int purgeCredentials(Principal principal, UUID requestId, String reason)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE assessment_request_role_account "
                                + "   SET secret_ciphertext = NULL, secret_nonce = NULL, "
                                + "       secret_algorithm = NULL, secret_purged_at = now(), "
                                + "       secret_purge_reason = ?, rotation_required = true "
                                + " WHERE request_id = ? AND secret_ciphertext IS NOT NULL")) {
            statement.setString(1, reason);
            statement.setObject(2, requestId);
            int applied = statement.executeUpdate();
            if (applied > 0) {
                // A destroyed credential is the one thing here that cannot be reconstructed from the
                // record afterwards: the row keeps the account name and loses the secret, so without
                // this the trail would show a credential that was never used and never say why.
                audit.event(connection, principal,
                        aspm.kernel.audit.contract.AuditEventType.ERASURE_EXECUTED,
                        requestId, aspm.app.audit.AuditScopes.ofRequest(connection, requestId),
                        java.util.Map.of("subject", "assessment_request_role_account",
                                "request_id", requestId.toString(),
                                "secrets_destroyed", Integer.valueOf(applied),
                                "reason", reason == null ? "" : reason));
            }
            connection.commit();
            return applied;
        }
    }

    // ----------------------------------------------------------------------------------------------

    /** One row of a project's request list. */
    public record ProjectRequest(UUID id, String code, String title, String state,
            String stateCategory, String createdAt, String dueAt, String requestedBy,
            long findingOpen, long findingTotal) {
    }

    /** A page of them, with the total so the interface can say how many pages there are. */
    public record Page(List<ProjectRequest> rows, int page, int size, long total) {
    }

    /**
     * Requests raised against one project, newest first, one page at a time.
     *
     * <p>Paginated in the QUERY rather than in the interface. A project accumulates requests for as
     * long as it exists, and a panel that reads them all to show ten is a panel that gets slower
     * every quarter until somebody notices.
     */
    public Page requestsForProject(Principal principal, UUID projectId, int page, int size)
            throws SQLException {
        int bounded = Math.max(1, Math.min(100, size));
        int offset = Math.max(0, page) * bounded;
        // Scope-checked through the project, so a project the caller cannot reach yields no requests
        // rather than somebody else's list.
        if (projects.project(principal, projectId).isEmpty()) {
            return new Page(List.of(), 0, bounded, 0);
        }

        List<ProjectRequest> rows = new ArrayList<>();
        long total = 0;
        try (Connection connection = open(principal)) {
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT count(*) FROM assessment_request_scope_asset sa "
                            + " WHERE sa.asset_id = ?")) {
                count.setObject(1, projectId);
                try (ResultSet results = count.executeQuery()) {
                    results.next();
                    total = results.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT b.id, b.request_code, b.title, b.state, b.state_category, "
                            + "       to_char(b.created_at, 'YYYY-MM-DD HH24:MI'), "
                            + "       to_char(b.due_at, 'YYYY-MM-DD'), "
                            + "       coalesce(p.display_name, ''), b.finding_open, b.finding_total "
                            + "  FROM assessment_request_scope_asset sa "
                            + "  JOIN request_board b ON b.id = sa.request_id "
                            + "  LEFT JOIN principal p ON p.id = b.requested_by "
                            + " WHERE sa.asset_id = ? "
                            + " ORDER BY b.created_at DESC LIMIT ? OFFSET ?")) {
                statement.setObject(1, projectId);
                statement.setInt(2, bounded);
                statement.setInt(3, offset);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        rows.add(new ProjectRequest(results.getObject(1, UUID.class),
                                results.getString(2), results.getString(3), results.getString(4),
                                results.getString(5), results.getString(6), results.getString(7),
                                results.getString(8), results.getLong(9), results.getLong(10)));
                    }
                }
            }
        }
        return new Page(List.copyOf(rows), Math.max(0, page), bounded, total);
    }

    // ----------------------------------------------------------------------------------------------

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.strip());
        }
    }

    /** A role the requester started and left empty. Skipped rather than refused. */
    private static boolean isBlankRole(RoleAccounts role) {
        boolean noName = role.roleName() == null || role.roleName().isBlank();
        boolean noAccounts = role.accounts() == null || role.accounts().stream()
                .noneMatch(a -> a.username() != null && !a.username().isBlank());
        return noName && noAccounts;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Connection open(Principal principal) throws SQLException {
        return TenantConnections.open(dataSource, principal);
    }
}
