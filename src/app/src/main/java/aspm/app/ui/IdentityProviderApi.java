package aspm.app.ui;

import aspm.app.identity.FederatedSignIn;
import aspm.app.identity.federation.IdentityProviderService;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The administration API for identity providers, consumed by the Access → Identity providers tab.
 * {@code PRD-IAM-001}, {@code PRD-CON-017}, {@code PRD-CON-021}, {@code SEC-SEC-002}.
 *
 * <p>Every operation here is gated by the dispatcher on {@link IdentityProviderService#MANAGE}; writes
 * are class E (step-up plus an idempotency key), because a provider decides who may sign in. The
 * client secret arrives once, in the create or update body, and leaves this class as a reference;
 * nothing here ever returns it, and the representation carries only whether one is held.
 */
public final class IdentityProviderApi {

    private final IdentityProviderService providers;
    private final FederatedSignIn federation;

    public IdentityProviderApi(IdentityProviderService providers, FederatedSignIn federation) {
        this.providers = Objects.requireNonNull(providers);
        this.federation = federation;
    }

    /** {@code GET /api/ui/access/identity-providers}. */
    public Dispatcher.Response list(Dispatcher.Request request) throws SQLException {
        Principal principal = request.principal();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IdentityProviderService.Provider p : providers.list(principal)) {
            rows.add(row(p, providers.groupRoles(principal, p.id())));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows);
        body.put("presets", presets());
        body.put("redirectUri", federation == null ? null : federation.redirectUri());
        body.put("federationConfigured", federation != null);
        body.put("localSignInEnabled", providers.localSignInEnabled(principal.tenantId()));
        body.put("mayManage", principal.holds(IdentityProviderService.MANAGE));
        body.put("elevated", principal.stepUpAuthenticated());
        return json(body);
    }

    /** {@code POST /api/ui/access/identity-providers}. */
    public Dispatcher.Response create(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        IdentityProviderService.Provider created = providers.create(request.principal(), draft(body));
        return json(row(created, List.of()));
    }

    /** {@code POST /api/ui/access/identity-providers/{id}}. */
    public Dispatcher.Response update(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        int rowVersion = body.get("rowVersion") instanceof Number n ? n.intValue() : -1;
        if (rowVersion < 0) {
            return new Dispatcher.Response(400, Map.of("code", "ROW_VERSION_REQUIRED",
                    "message", "the row version being edited is required"), Map.of());
        }
        IdentityProviderService.Provider updated = providers.update(request.principal(), id(request), draft(body), rowVersion);
        return json(row(updated, providers.groupRoles(request.principal(), updated.id())));
    }

    /** {@code POST /api/ui/access/identity-providers/{id}/transition}. */
    public Dispatcher.Response transition(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        String target = String.valueOf(body.getOrDefault("state", ""));
        IdentityProviderService.Provider updated = providers.transition(request.principal(), id(request), target);
        return json(row(updated, providers.groupRoles(request.principal(), updated.id())));
    }

    /** {@code POST /api/ui/access/identity-providers/{id}/test}. */
    public Dispatcher.Response test(Dispatcher.Request request) throws SQLException {
        IdentityProviderService.Provider tested = providers.test(request.principal(), id(request));
        return json(row(tested, providers.groupRoles(request.principal(), tested.id())));
    }

    /** {@code POST /api/ui/access/identity-providers/{id}/group-roles}. */
    public Dispatcher.Response setGroupRoles(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        List<Map<String, String>> mappings = new ArrayList<>();
        if (body.get("mappings") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, String> mapping = new LinkedHashMap<>();
                    m.forEach((k, v) -> mapping.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
                    mappings.add(mapping);
                }
            }
        }
        List<IdentityProviderService.GroupRole> saved = providers.setGroupRoles(request.principal(), id(request), mappings);
        return json(Map.of("groupRoles", saved.stream().map(IdentityProviderApi::groupRole).toList()));
    }

    /** {@code POST /api/ui/access/local-sign-in}. */
    public Dispatcher.Response setLocalSignIn(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        providers.setLocalSignIn(request.principal(), enabled);
        return json(Map.of("localSignInEnabled", enabled));
    }

    /** {@code POST /api/ui/access/users/{id}/break-glass}. */
    public Dispatcher.Response setBreakGlass(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        boolean allowed = Boolean.TRUE.equals(body.get("allowed"));
        providers.setBreakGlass(request.principal(), id(request), allowed);
        return json(Map.of("breakGlassLocalSignIn", allowed));
    }

    // ==============================================================================================

    private static UUID id(Dispatcher.Request request) {
        try {
            return UUID.fromString(request.pathVariables().getOrDefault("id", ""));
        } catch (IllegalArgumentException e) {
            throw new Dispatcher.UnauthorizedException("no such provider");
        }
    }

    private static IdentityProviderService.Draft draft(Map<String, Object> body) {
        IdentityProviderService.Preset preset;
        try {
            preset = IdentityProviderService.Preset.valueOf(String.valueOf(body.getOrDefault("preset", "GENERIC_OIDC")));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the preset is not one this platform knows");
        }
        IdentityProviderService.Draft base = IdentityProviderService.Draft.withPresetDefaults(
                text(body, "code").orElse(""), text(body, "displayName").orElse(""), preset,
                text(body, "issuer").orElse(""), text(body, "clientId").orElse(""),
                text(body, "clientSecret").map(String::toCharArray));
        return new IdentityProviderService.Draft(base.code(), base.displayName(), preset, base.issuer(),
                text(body, "discoveryUrl"), base.clientId(), base.clientSecret(),
                strings(body.get("scopes")).isEmpty() ? base.scopes() : strings(body.get("scopes")),
                text(body, "claimSubject").orElse(base.claimSubject()),
                text(body, "claimUsername").orElse(base.claimUsername()),
                text(body, "claimEmail").orElse(base.claimEmail()),
                text(body, "claimDisplayName").orElse(base.claimDisplayName()),
                body.containsKey("claimGroups") ? text(body, "claimGroups") : base.claimGroups(),
                body.get("jitProvisioning") instanceof Boolean b ? b : base.jitProvisioning(),
                strings(body.get("allowedEmailDomains")),
                Boolean.TRUE.equals(body.get("mfaAssertedByProvider")));
    }

    private static Map<String, Object> row(IdentityProviderService.Provider p, List<IdentityProviderService.GroupRole> groupRoles) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.id().toString());
        row.put("code", p.code());
        row.put("displayName", p.displayName());
        row.put("preset", p.preset().name());
        row.put("presetLabel", p.preset().label);
        row.put("issuer", p.issuer());
        row.put("discoveryUrl", p.discoveryUrl().orElse(null));
        row.put("clientId", p.clientId());
        // Held or not. Never the reference itself either: a reference is safe, and also useless to a
        // reader of this page, and "what does the vault path look like" is one more thing to not answer.
        row.put("clientSecretHeld", p.clientSecretRef().isPresent());
        row.put("scopes", p.scopes());
        row.put("claimSubject", p.claimSubject());
        row.put("claimUsername", p.claimUsername());
        row.put("claimEmail", p.claimEmail());
        row.put("claimDisplayName", p.claimDisplayName());
        row.put("claimGroups", p.claimGroups().orElse(null));
        row.put("jitProvisioning", p.jitProvisioning());
        row.put("allowedEmailDomains", p.allowedEmailDomains());
        row.put("mfaAssertedByProvider", p.mfaAssertedByProvider());
        row.put("lifecycleState", p.lifecycleState());
        row.put("lastTestStatus", p.lastTestStatus().orElse(null));
        row.put("lastTestDetail", p.lastTestDetail().orElse(null));
        row.put("rowVersion", p.rowVersion());
        row.put("startUrl", "/auth/" + p.code() + "/start");
        row.put("groupRoles", groupRoles.stream().map(IdentityProviderApi::groupRole).toList());
        return row;
    }

    private static Map<String, Object> groupRole(IdentityProviderService.GroupRole g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id().toString());
        m.put("group", g.groupValue());
        m.put("roleId", g.roleId().toString());
        m.put("roleCode", g.roleCode());
        m.put("scopeNodeId", g.scopeNodeId().map(UUID::toString).orElse(null));
        m.put("scopeMode", g.scopeMode());
        return m;
    }

    private static List<Map<String, Object>> presets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IdentityProviderService.Preset preset : IdentityProviderService.Preset.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", preset.name());
            m.put("label", preset.label);
            m.put("scopes", preset.scopes);
            m.put("claimSubject", preset.claimSubject);
            m.put("claimUsername", preset.claimUsername);
            m.put("claimEmail", preset.claimEmail);
            m.put("claimDisplayName", preset.claimDisplayName);
            m.put("claimGroups", preset.claimGroups);
            m.put("issuerHint", preset.issuerHint);
            out.add(m);
        }
        return out;
    }

    private static Optional<String> text(Map<String, Object> body, String key) {
        return body.get(key) instanceof String s && !s.isBlank() ? Optional.of(s.strip()) : Optional.empty();
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s.strip());
                }
            }
        }
        return out;
    }

    private static Dispatcher.Response json(Object body) {
        return new Dispatcher.Response(200, body, Map.of("Content-Type", "application/json; charset=utf-8"));
    }
}
