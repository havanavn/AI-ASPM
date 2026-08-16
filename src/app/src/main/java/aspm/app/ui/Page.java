package aspm.app.ui;

import aspm.app.runtime.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The application shell. DOC-08 §6 information architecture, §7 interaction model, §11 accessibility.
 *
 * <p>ADR-006 asks for "Linear-style density and keyboard-first, Azure-grade information architecture", and
 * the two pull in opposite directions: Linear is shallow and fast, Azure is broad and deep. A platform
 * replacing an issue tracker, a spreadsheet and a mailbox for an AppSec team needs Azure's breadth at
 * Linear's density, which is why the shell is a persistent sidebar with grouped sections plus a command
 * interface — the sidebar carries breadth, the command interface removes the cost of depth.
 *
 * <h2>The hierarchy is not in the code</h2>
 *
 * <p>DOC-08 §6.1: "The hierarchy is tenant-configured with unbounded depth and tenant-named levels
 * (ADR-027). The interface cannot assume four levels, cannot assume level names, and cannot assume a user
 * starts at the top."
 *
 * <p>So no navigation label here names an organizational level. <b>A product with "Business Unit" compiled
 * into its sidebar cannot be sold to the second customer</b>, whose levels are P&amp;Ls or divisions or
 * something else again — {@code PRD-UIX-009} exists for that commercial reason as much as a technical one.
 * Section names are product structure; level names arrive as data through the scope switcher.
 *
 * <h2>What the shell carries so a page cannot omit it</h2>
 *
 * <ul>
 *   <li><b>The current scope</b> ({@code PRD-UIX-011}): "a user uncertain which slice they are viewing
 *       will misread every figure on the page".
 *   <li><b>Breadcrumbs from the caller's scope root</b> ({@code PRD-UIX-010}) — never from a tenant root
 *       they cannot see, because displaying an unreachable ancestor discloses the organization's shape
 *       above them.
 *   <li><b>Skip link, landmarks, and the command interface</b>, so every workflow is completable by
 *       keyboard ({@code INT-UIX-003}).
 *   <li><b>The development-authentication warning</b>, where that is in use.
 * </ul>
 */
public final class Page {



    /** A navigation entry. {@code countKey} is a message key or empty — never a formatted string. */
    public record NavItem(String labelKey, String href, String icon, Optional<Integer> count) {

        public static NavItem of(String labelKey, String href, String icon) {
            return new NavItem(labelKey, href, icon, Optional.empty());
        }

        /**
         * The permission the registered {@code GET} for this href requires, or empty for class G.
         *
         * <p><b>Derived, never declared here.</b> A permission written beside the navigation item is a
         * second copy of the answer, and the copy that drifts is this one — a link shown to a caller who
         * cannot open it, or hidden from one who can. The derivation has two sources, in order; see
         * {@code NAV_PERMISSIONS} for why the second exists.
         */
        public Optional<String> requiredPermission() {
            // *** THE INTERFACE TABLE IS ASKED FIRST, AND THE ORDER IS THE WHOLE CORRECTNESS. ***
            //
            // The registry now holds a class G shell operation for every React route, and class G means
            // "no permission required". Asking the registry first therefore reported that /roles is
            // public — so the sidebar offered role composition to a principal holding nothing, which is
            // the inverse of what this method exists to prevent. It was InterfaceTest that caught it.
            Optional<String> declared = UiApi.navigationPermission(href);
            return declared != null ? declared : NAV_PERMISSIONS.get(href);
        }
    }

    /**
     * href to required permission, resolved once from the registry.
     *
     * <p>Computed in a static initializer rather than per render: {@code PlatformOperations.registry()}
     * rebuilds the whole catalogue, and doing that inside the page shell would rebuild it on every request
     * for the sake of drawing a sidebar.
     *
     * <p>A navigation href with no registered {@code GET} and no entry in the interface table maps to
     * {@code null} and is <b>hidden</b>, not shown. {@code /assessments} was in this list for weeks with
     * no route behind it, so the sidebar advertised a section that answered 404 — {@code InterfaceTest}
     * now fails the build on a dead link rather than leaving it to be clicked.
     *
     * <p><b>Two sources, in order, and the second is new.</b> The registry answers for pages this tier
     * still renders itself — the guide, the credential policy, the component list. Everything else is a
     * React route served by one class G shell, and a shell requires no permission, so deriving from the
     * registry alone would report that the whole sidebar is public and then hide none of it. For those
     * hrefs the answer comes from {@code UiApi}'s interface table, which is where the React sidebar
     * already declares it — one answer consulted twice, rather than a second copy written here.
     */
    private static final java.util.Map<String, Optional<String>> NAV_PERMISSIONS =
            aspm.app.api.PlatformOperations.registry().all().stream()
                    .filter(operation -> "GET".equalsIgnoreCase(operation.method()))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            aspm.app.api.OperationRegistry.Operation::pathTemplate,
                            aspm.app.api.OperationRegistry.Operation::requiredPermission,
                            (first, second) -> first));

    /**
     * Whether a caller may see a navigation entry at all.
     *
     * <p>Hidden rather than shown-and-refused. The dispatcher answers 404 to a denial so the permission
     * model cannot be mapped by probing ({@code PRD-API-036}); a sidebar that lists the page and then
     * 404s hands back exactly what the 404 withholds. Product principle 7 is the other half of the
     * reason: the largest user population has the narrowest permissions and the least training, and a
     * sidebar full of dead links is how they learn not to trust the sidebar.
     */
    private static boolean visible(NavItem item, Optional<aspm.app.runtime.Principal> principal) {
        Optional<String> required = item.requiredPermission();
        if (required == null) {
            // No registered GET for this href. Hidden, and PageShellTest fails the build for it.
            return false;
        }
        if (required.isEmpty()) {
            return true;
        }
        return principal.isPresent() && principal.orElseThrow().holds(required.orElseThrow());
    }

    /** A group of sections. Groups are product structure, not tenant structure. */
    public record NavGroup(String labelKey, List<NavItem> items) {
    }

    /** One breadcrumb. The first is always the caller's scope root. */
    public record Crumb(String label, Optional<String> href) {
    }

    /** Everything a page supplies to the shell. */
    public record Context(String titleKey, String subtitleKey, String currentHref,
            List<Crumb> breadcrumbs, String actionsHtml, Optional<Principal> principal,
            Optional<String> scopeLabel, boolean developmentAuthentication) {

        /**
         * The principal is <b>required</b>, not optional-by-omission.
         *
         * <p>It used to be neither: {@code of(titleKey, currentHref)} set {@code Optional.empty()} and no
         * page ever supplied one, so {@code context.principal()} was empty on every render. The sidebar
         * could not have filtered by permission even if it had tried, and
         * {@code developmentAuthentication} was hardcoded {@code true} — so the "development
         * authentication is in use" warning showed on every page in every deployment, which is a warning
         * nobody reads.
         *
         * <p>Taking it as a parameter rather than offering a two-argument convenience is deliberate: an
         * overload that omits it is one a new page uses, and that page gets an unfiltered sidebar.
         */
        public static Context of(String titleKey, String currentHref,
                Optional<aspm.app.runtime.Principal> principal) {
            return new Context(titleKey, "", currentHref, List.of(), "", principal,
                    Optional.empty(), devAuthInUse);
        }

        public Context withScope(Optional<String> label) {
            return new Context(titleKey, subtitleKey, currentHref, breadcrumbs, actionsHtml, principal,
                    label, developmentAuthentication);
        }

        public Context withSubtitle(String key) {
            return new Context(titleKey, key, currentHref, breadcrumbs, actionsHtml, principal,
                    scopeLabel, developmentAuthentication);
        }

        public Context withBreadcrumbs(List<Crumb> crumbs) {
            return new Context(titleKey, subtitleKey, currentHref, crumbs, actionsHtml, principal,
                    scopeLabel, developmentAuthentication);
        }

        public Context withActions(String html) {
            return new Context(titleKey, subtitleKey, currentHref, breadcrumbs, html, principal,
                    scopeLabel, developmentAuthentication);
        }
    }

    /**
     * The section navigation.
     *
     * <p>Grouped the way an AppSec team's day is grouped rather than the way the schema is: what is
     * happening now, what the estate is, and what configures the platform. DOC-08 §6.2 lists the
     * functional areas and this is that list with the operational ones first.
     */
    public static final List<NavGroup> NAVIGATION = List.of(
            new NavGroup("nav.group.operate", List.of(
                    NavItem.of("nav.overview", "/overview", Icon.dashboard()),
                    // nav.findings pointed at the generic /findings list and nav.requests at /requests.
                    // Both were server-rendered lists of every row of one table, both are gone, and both
                    // have a scoped React page over the same rows: the vulnerability dashboard and the
                    // assessment board. Pointing the sidebar at the page that exists is the whole fix.
                    NavItem.of("nav.findings", "/vulnerabilities", Icon.finding()),
                    NavItem.of("nav.board", "/board", Icon.request()),
                    // nav.assessments was here, pointing at /ui/assessments, which HAS NO ROUTE: there is
                    // no assessment resource group, so the sidebar advertised a section that answered 404.
                    // Removed rather than routed, because DOC-08's "assessments" area is the request
                    // lifecycle and that is the board — a second page over the same rows would be one
                    // name with two meanings (product principle 10).
                    NavItem.of("nav.workload", "/workload", Icon.dashboard()))),
            new NavGroup("nav.group.estate", List.of(
                    NavItem.of("nav.applications", "/applications", Icon.inventory()),
                    // nav.assets pointed at the generic /ui/assets list, which lists EVERY row of the
                    // asset table — including the applications and features the entry above already
                    // presents, with fewer columns and no composition. Removed from the sidebar because a
                    // person should not be offered the same rows twice under two names — and the generic
                    // list PAGE has since gone entirely, with the interface. GET /api/v1/assets is the
                    // parity that mattered and it is still there.
                    // nav.components was here, pointing at /components — "the technical estate:
                    // everything that is not an application". Removed at the user's request: they could
                    // not tell what the page was for, and a page whose purpose is not legible from its
                    // own screen is a page nobody uses.
                    //
                    // WHAT WENT WITH IT, stated because it was the page's one load-bearing signal: it
                    // was the only surface that listed UNOWNED assets as a queue — assets with no owning
                    // node, which no scope grant reaches and which appear on nobody's dashboard. The
                    // Applications and Dependencies inventories still show ownership per row, so the
                    // fact is visible; the queue is not. If that queue is wanted back it belongs as a
                    // count on the estate the assets are part of, not as a page of its own.
                    NavItem.of("nav.composition", "/composition", Icon.composition()),
                    NavItem.of("nav.organization", "/organization", Icon.organization()))),
            new NavGroup("nav.group.configure", List.of(
                    // nav.nodeTypes pointed at /org-node-types, one of the generic lists. It has no React
                    // page yet, so the entry is removed rather than left advertising a 404 — node types
                    // are still reachable through the API (GET/POST /api/v1/org-node-types).
                    // Users, roles and the credential policy sit under configuration rather than under a
                    // separate "admin" area. A section a person only reaches by knowing it exists is a
                    // section nobody configures — and every one of these is gated on a permission, so a
                    // caller without it gets a redirect rather than a page.
                    // /users was the server-rendered people list. /access is the React one.
                    NavItem.of("nav.users", "/access", Icon.administration()),
                    NavItem.of("nav.roles", "/roles", Icon.administration()),
                    NavItem.of("nav.securityPolicy", "/security-policy", Icon.administration()))),
            // Its own group, and last. It belongs to none of the three above — the guide is not an
            // operational screen, not part of the estate and not configuration — and appending it to
            // one of them would put "How to use this" under a heading that misdescribes it. Last
            // rather than first because somebody arriving to do work should not have to pass a manual
            // to reach the board, and product principle 7's audience finds it when they need it
            // rather than on the way to everything else.
            //
            // The entry carries no permission (class G), so this group is the only one that never
            // disappears — which is the point: a person who can reach nothing else can still reach the
            // page that explains why.
            new NavGroup("nav.group.help", List.of(
                    NavItem.of("nav.guide", "/guide", Icon.guide()))));

    /**
     * Whether the development header resolver is in use, set once at startup.
     *
     * <p>A static rather than a parameter because the answer is a property of the deployment and not of
     * any page, and because the alternative — threading it through thirteen page classes — is how it came
     * to be hardcoded {@code true} in the first place. Defaults to {@code false}: a banner that warns on
     * every page in every deployment is a banner nobody reads, which is worse than no banner because it
     * consumes the attention a real warning needs.
     */
    private static volatile boolean devAuthInUse;

    /** Called by the composition root, which is the only place that knows which resolver was chosen. */
    public static void developmentAuthentication(boolean inUse) {
        devAuthInUse = inUse;
    }

    private Page() {
    }

    /** Renders a complete document. */
    public static String render(Messages messages, Context context, String bodyHtml) {
        Objects.requireNonNull(messages, "messages are required");
        Objects.requireNonNull(context, "a page context is required");
        Objects.requireNonNull(bodyHtml, "a body is required");

        String language = messages.locale().toLanguageTag();
        String direction = isRightToLeft(messages.locale()) ? "rtl" : "ltr";

        StringBuilder out = new StringBuilder(8192);
        out.append("<!DOCTYPE html>\n<html lang=").append(Html.attribute(language))
                .append(" dir=").append(Html.attribute(direction)).append(">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                // No user-scalable=no and no maximum-scale: INT-UIX-004 requires usability at 200% zoom,
                // and either of those forbids it outright.
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta name=\"color-scheme\" content=\"light dark\">\n")
                .append("<title>").append(Html.text(messages.get(context.titleKey()))).append(" · ")
                .append(Html.text(messages.get("app.name"))).append("</title>\n")
                // The same URL the single-page interface uses. One file behind it, so the two
                // interfaces cannot come to show different logos after somebody updates one.
                .append("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/brand/logo.svg\">\n")
                .append("<meta name=\"theme-color\" content=\"#C8102E\">\n")
                .append("<link rel=\"stylesheet\" href=\"/style.css\">\n")
                .append("</head>\n<body>\n");

        out.append("<a class=\"skip-link\" href=\"#main\">")
                .append(Html.text(messages.get("app.skipToContent"))).append("</a>\n");

        out.append("<div class=\"app\">\n");
        brand(out, messages);
        topbar(out, messages, context);
        sidebar(out, messages, context);

        out.append("<main class=\"app-main\" id=\"main\">\n<div class=\"page\">\n");
        if (context.developmentAuthentication()) {
            out.append("<div class=\"banner no-print\" role=\"status\"><strong>")
                    .append(Html.text(messages.get("auth.developmentLabel")))
                    .append("</strong><span>")
                    .append(Html.text(messages.get("auth.developmentWarning")))
                    .append("</span></div>\n");
        }
        breadcrumbs(out, messages, context);
        pageHeader(out, messages, context);
        out.append(bodyHtml).append("\n</div>\n</main>\n");
        out.append("</div>\n");

        commandPalette(out, messages, context.principal());
        out.append("<script src=\"/app.js\" defer></script>\n</body>\n</html>\n");
        return out.toString();
    }

    private static void brand(StringBuilder out, Messages messages) {
        out.append("<div class=\"app-brand\"><span class=\"mark\" aria-hidden=\"true\">A</span>")
                .append("<span>").append(Html.text(messages.get("app.name"))).append("</span></div>\n");
    }

    private static void topbar(StringBuilder out, Messages messages, Context context) {
        out.append("<div class=\"app-topbar\">\n");

        // The command interface. A real control, so it works without script; the script only adds the
        // shortcut (PRD-UIX-013 — no capability may be pointer-only, and none may be script-only either).
        out.append("<button class=\"cmd-trigger\" id=\"cmd-open\" aria-haspopup=\"dialog\">")
                .append(Icon.search())
                .append("<span>").append(Html.text(messages.get("command.hint"))).append("</span>")
                .append("<kbd class=\"kbd\">").append(Html.text(messages.get("command.key")))
                .append("</kbd></button>\n");

        // PRD-UIX-011: the current scope, on every surface presenting scoped data.
        out.append("<div class=\"scope-switch ms-auto\">")
                .append("<span class=\"label\">").append(Html.text(messages.get("scope.label")))
                .append("</span><span class=\"value\">")
                .append(Html.text(context.scopeLabel().orElseGet(() -> messages.get("scope.unset"))))
                .append("</span>").append(Icon.chevron()).append("</div>\n");

        // Theme and density are real form controls with a no-script fallback: PRD-UIX-007 requires the
        // choice to be persisted, which is not implemented — so this sets it for the session only and
        // the label says so rather than implying persistence.
        out.append("<form class=\"row no-print\" method=\"get\" action=\"\">")
                .append("<div class=\"field\"><label for=\"theme\">")
                .append(Html.text(messages.get("theme.label"))).append("</label>")
                .append("<select id=\"theme\" name=\"theme\" data-pref=\"theme\">")
                .append(option("", messages.get("theme.system")))
                .append(option("light", messages.get("theme.light")))
                .append(option("dark", messages.get("theme.dark")))
                .append(option("hc", messages.get("theme.highContrast")))
                .append("</select></div>")
                .append("<div class=\"field\"><label for=\"density\">")
                .append(Html.text(messages.get("density.label"))).append("</label>")
                .append("<select id=\"density\" name=\"density\" data-pref=\"density\">")
                .append(option("", messages.get("density.comfortable")))
                .append(option("compact", messages.get("density.compact")))
                .append("</select></div>")
                .append("<noscript><button class=\"btn btn-sm\" type=\"submit\">")
                .append(Html.text(messages.get("action.apply"))).append("</button></noscript>")
                .append("</form>\n");

        // This was a link to /ui/sign-in labelled "sign out", which SIGNED NOTHING OUT: it rendered the
        // sign-in form while the session cookie stayed live and valid. Anybody who used it on a shared
        // machine left an authenticated session behind believing they had ended it.
        //
        // Sign-out now lives in the sidebar foot as a POST to /sign-out, which revokes the token and
        // clears the cookie. The topbar carries the account link, which is what a person actually reaches
        // for here.
        out.append("<a class=\"btn btn-ghost btn-sm no-print\" href=\"/account\">")
                .append(Html.text(messages.get("nav.account"))).append("</a>\n");
        out.append("</div>\n");
    }

    private static String option(String value, String label) {
        return "<option value=" + Html.attribute(value) + ">" + Html.text(label) + "</option>";
    }

    private static void sidebar(StringBuilder out, Messages messages, Context context) {
        out.append("<nav class=\"app-sidebar\" aria-label=")
                .append(Html.attribute(messages.get("app.mainNavigation"))).append(">\n");
        int index = 0;
        for (NavGroup group : NAVIGATION) {
            // Filtered before the group header is written. A group whose every entry is hidden must not
            // render its label: "Configure" over an empty list still tells the caller a configuration area
            // exists, which is the disclosure the 404 refuses.
            List<NavItem> visible = group.items().stream()
                    .filter(item -> visible(item, context.principal()))
                    .toList();
            if (visible.isEmpty()) {
                continue;
            }
            out.append("<div class=\"nav-group\"><div class=\"nav-label\">")
                    .append(Html.text(messages.get(group.labelKey()))).append("</div><ul>");
            for (NavItem item : visible) {
                boolean current = item.href().equals(context.currentHref());
                // The stagger index drives an entrance delay in CSS, and it rides in the class list.
                // A CLASS rather than an inline custom property: per-element data or not, the
                // Content Security Policy blocks a style attribute, so the delay was never applied.
                // Clamped at the bound the animation itself clamps at, so a longer navigation reuses
                // the last class instead of naming one that does not exist. In the SAME attribute as
                // nav-item, because a second class attribute is ignored by every parser.
                out.append("<li><a class=\"nav-item i-")
                        .append(Math.min(index++, DesignSystem.STAGGER_MAX))
                        .append("\" href=").append(Html.attribute(item.href()));
                if (current) {
                    out.append(" aria-current=\"page\"");
                }
                out.append(">")
                        .append(item.icon()).append("<span>")
                        .append(Html.text(messages.get(item.labelKey()))).append("</span>");
                item.count().ifPresent(count -> out.append("<span class=\"count tabular\">")
                        .append(count).append("</span>"));
                out.append("</a></li>");
            }
            out.append("</ul></div>\n");
        }
        // Every principal reaches their own account, whatever they hold — it is the only route to a
        // password change, and a principal with no role at all must still get there.
        out.append("<div class=\"nav-foot\">")
                .append("<a class=\"nav-item")
                .append("/account".equals(context.currentHref()) ? "\" aria-current=\"page" : "")
                .append("\" href=\"/account\">").append(Icon.administration())
                .append("<span>").append(Html.text(messages.get("nav.account")))
                .append("</span></a>")
                // A real form post, not a link: signing out is a state change, and GET /ui/sign-out was
                // reachable by anything that prefetches a link.
                .append("<form method=\"post\" action=\"/sign-out\">")
                .append("<button class=\"nav-item nav-signout\" type=\"submit\">")
                .append(Icon.chevron()).append("<span>")
                .append(Html.text(messages.get("action.signOut"))).append("</span></button></form>")
                .append("</div>\n");
        out.append("</nav>\n");
    }

    private static void breadcrumbs(StringBuilder out, Messages messages, Context context) {
        if (context.breadcrumbs().isEmpty()) {
            return;
        }
        out.append("<nav class=\"breadcrumbs\" aria-label=")
                .append(Html.attribute(messages.get("app.breadcrumbs"))).append(">");
        for (int i = 0; i < context.breadcrumbs().size(); i++) {
            Crumb crumb = context.breadcrumbs().get(i);
            if (i > 0) {
                out.append("<span class=\"sep\" aria-hidden=\"true\">/</span>");
            }
            if (crumb.href().isPresent()) {
                out.append("<a href=").append(Html.attribute(crumb.href().orElseThrow())).append(">")
                        .append(Html.text(crumb.label())).append("</a>");
            } else {
                out.append("<span aria-current=\"page\">").append(Html.text(crumb.label()))
                        .append("</span>");
            }
        }
        out.append("</nav>\n");
    }

    private static void pageHeader(StringBuilder out, Messages messages, Context context) {
        out.append("<div class=\"page-header\"><div class=\"titles\"><h1>")
                .append(Html.text(messages.get(context.titleKey()))).append("</h1>");
        if (!context.subtitleKey().isEmpty()) {
            out.append("<p class=\"subtitle\">")
                    .append(Html.text(messages.get(context.subtitleKey()))).append("</p>");
        }
        out.append("</div>");
        if (!context.actionsHtml().isEmpty()) {
            out.append("<div class=\"page-actions no-print\">").append(context.actionsHtml())
                    .append("</div>");
        }
        out.append("</div>\n");
    }

    /**
     * The command interface. DOC-08 §7.1: "Single shortcut from anywhere; searches objects, actions, and
     * navigation targets."
     *
     * <p>{@code PRD-UIX-012} requires every object to be reachable by its human-facing code, "because
     * these codes are quoted in conversation, email, and tickets. Requiring hierarchy traversal to reach
     * a code someone just read out is the single most common navigation frustration in tools of this
     * kind."
     *
     * <p>Object search is not implemented — there is no search index yet. The dialog lists navigation
     * targets and says what it cannot do, rather than presenting an empty search box that looks broken.
     */
    private static void commandPalette(StringBuilder out, Messages messages,
            Optional<aspm.app.runtime.Principal> principal) {
        out.append("<dialog class=\"cmdk\" id=\"cmdk\" aria-label=")
                .append(Html.attribute(messages.get("command.title"))).append(">\n")
                .append("<input id=\"cmd-input\" type=\"search\" autocomplete=\"off\" placeholder=")
                .append(Html.attribute(messages.get("command.placeholder"))).append(">\n")
                .append("<div class=\"cmdk-list\">");
        for (NavGroup group : NAVIGATION) {
            // Filtered by the same rule as the sidebar. A palette that lists every page is the disclosure
            // the sidebar filter just removed, arriving through a different element — and it is the more
            // likely leak, because a palette is a search box and searching feels like nothing happened.
            List<NavItem> visible = group.items().stream()
                    .filter(item -> visible(item, principal))
                    .toList();
            if (visible.isEmpty()) {
                continue;
            }
            out.append("<div class=\"cmdk-group\">")
                    .append(Html.text(messages.get(group.labelKey()))).append("</div>");
            for (NavItem item : visible) {
                out.append("<a class=\"cmdk-item\" data-cmd href=").append(Html.attribute(item.href()))
                        .append(">").append(item.icon()).append("<span>")
                        .append(Html.text(messages.get(item.labelKey())))
                        .append("</span><span class=\"hint\">")
                        .append(Html.text(messages.get("command.goTo"))).append("</span></a>");
            }
        }
        out.append("<div class=\"cmdk-group\">")
                .append(Html.text(messages.get("command.unavailableGroup"))).append("</div>")
                .append("<p class=\"cmdk-item subtle\">")
                .append(Html.text(messages.get("command.objectSearchUnavailable")))
                .append("</p>");
        out.append("</div>\n<form method=\"dialog\" class=\"card-footer row between\">")
                .append("<span class=\"subtle fs-12\">")
                .append(Html.text(messages.get("command.escapeHint"))).append("</span>")
                .append("<button class=\"btn btn-sm\">")
                .append(Html.text(messages.get("action.close"))).append("</button></form>\n")
                .append("</dialog>\n");
    }

    private static boolean isRightToLeft(Locale locale) {
        return switch (locale.getLanguage()) {
            case "ar", "he", "fa", "ur" -> true;
            default -> false;
        };
    }
}
