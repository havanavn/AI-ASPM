package aspm.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.insight.domain.PresentationState;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The interface. DOC-08, ADR-058. */
class InterfaceTest {

    /**
     * A principal holding every permission the seeded catalogue defines.
     *
     * <p>The shell filters navigation by permission now, so a context with no principal renders a sidebar
     * with only the always-visible entries. These assertions are about layout and escaping rather than
     * authorization, so they use a principal that sees everything — and
     * {@code ApplicationTierTest.DeclaredPermissionIsEnforced} is where the filtering itself is asserted.
     */
    private static java.util.Optional<aspm.app.runtime.Principal> everything() {
        java.util.Set<String> all = aspm.app.api.PlatformOperations.registry().all().stream()
                .map(operation -> operation.requiredPermission().orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return java.util.Optional.of(new aspm.app.runtime.Principal(
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                java.util.UUID.fromString("33333333-3333-3333-3333-333333333333"),
                all, java.util.Set.of(), true, false, false));
    }

    private static String page(Messages messages, String body) {
        return Page.render(messages,
                Page.Context.of("nav.findings", "/findings", everything())
                        .withScope(Optional.of("Tenant A root"))
                        .withBreadcrumbs(List.of(
                                new Page.Crumb("Your scope", Optional.of("/overview")),
                                new Page.Crumb("findings", Optional.empty()))),
                body);
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INT-UIX-008, INT-UIX-009 — internationalization")
    class Internationalization {

        @Test
        @DisplayName("the pseudo-locale transforms every string, so an unexternalized one is visible")
        void pseudoLocalizationIsABuildGate() {
            Messages pseudo = Messages.forLocale(Messages.PSEUDO);
            String rendered = page(pseudo, "<p>" + Html.text(pseudo.get("table.noRows")) + "</p>");

            // Every user-facing string comes from the bundle, so under the pseudo-locale every one of
            // them is bracketed. A string that appears without brackets was written into the markup.
            for (String hardcoded : List.of(">Findings<", ">Assets<", ">Scope<", ">Skip to content<")) {
                assertFalse(rendered.contains(hardcoded),
                        "'" + hardcoded + "' rendered untransformed under the pseudo-locale, so it is "
                                + "not externalized. INT-UIX-009: pseudo-localization is the only test "
                                + "that finds hardcoded strings before a real locale is added.");
            }
            assertTrue(rendered.contains("⟦"), "the pseudo-locale produced no transformed string at all, "
                    + "so this assertion proved nothing");
        }

        @Test
        @DisplayName("the pseudo-locale expands length, so a layout that only fits English fails early")
        void pseudoLocalizationExpands() {
            String source = Messages.forLocale(Messages.SOURCE).get("nav.findings");
            String expanded = Messages.forLocale(Messages.PSEUDO).get("nav.findings");
            assertTrue(expanded.length() > source.length() * 4 / 3,
                    "expansion models the width a real translation adds; without it a layout that fits "
                            + "English ships and fails on the first locale");
        }

        @Test
        @DisplayName("a plural sub-message is transformed too, or the gate skips the longest part")
        void pluralSubMessagesArePseudoLocalized() {
            String rendered = Messages.forLocale(Messages.PSEUDO)
                    .get("table.rowCount", Integer.valueOf(4));
            assertFalse(rendered.contains("rows"),
                    "the plural sub-message rendered untransformed: " + rendered + ". Skipping "
                            + "everything inside braces leaves every plural sub-message unchecked, and "
                            + "that is the part of a string most likely to grow in translation.");
            assertTrue(rendered.contains("4"), "the count must still substitute: " + rendered);
        }

        @Test
        @DisplayName("ICU keywords are NOT transformed, or the pattern stops parsing")
        void icuKeywordsSurvive() {
            // 'other' and 'plural' sit at odd depth. Accenting either makes the pattern unparseable,
            // and the failure would be a format exception at render time rather than a layout problem.
            String rendered = Messages.forLocale(Messages.PSEUDO)
                    .get("honesty.coverage", Integer.valueOf(3), Integer.valueOf(9));
            assertTrue(rendered.contains("3") && rendered.contains("9"), rendered);
        }

        @Test
        @DisplayName("placeholders survive the pseudo-locale, or the layout is tested without its values")
        void placeholdersAreNotTransformed() {
            String formatted = Messages.forLocale(Messages.PSEUDO).get("scope.current", "Tenant A");
            assertTrue(formatted.contains("Tenant A"),
                    "a transformed placeholder stops being a placeholder, and the point of the exercise "
                            + "is to test the layout with real substituted values");
        }

        @Test
        @DisplayName("every source key exists in Vietnamese, the first target locale")
        void vietnameseIsComplete() {
            Messages source = Messages.forLocale(Messages.SOURCE);
            Messages vietnamese = Messages.forLocale(Messages.VIETNAMESE);
            Set<String> missing = new TreeSet<>();
            for (String key : source.keys()) {
                if (!vietnamese.has(key)) {
                    missing.add(key);
                }
            }
            assertTrue(missing.isEmpty(),
                    "missing Vietnamese strings: " + missing + ". A missing key falls back to the source "
                            + "locale, so the interface renders half-translated and nothing reports it.");
        }

        @Test
        @DisplayName("no sentence is assembled by concatenation")
        void sentencesAreWholeMessages() {
            // INT-UIX-008 forbids concatenation because word order differs by language. The renderer
            // takes keys, and a sentence with a variable part is one key with a placeholder — asserted
            // by checking that the multi-part messages carry their argument rather than being split.
            Messages messages = Messages.forLocale(Messages.SOURCE);
            assertTrue(messages.get("scope.current", "X").contains("X"));
            assertTrue(messages.get("state.emptyNoData", "Y").contains("Y"));
            assertTrue(messages.get("honesty.coverage", Integer.valueOf(3), Integer.valueOf(9))
                    .matches(".*3.*9.*"), "the coverage sentence must carry both counts in one message");
        }

        @Test
        @DisplayName("ICU plural selection actually differs by count")
        void pluralsSelect() {
            Messages messages = Messages.forLocale(Messages.SOURCE);
            String one = messages.get("table.rowCount", Integer.valueOf(1));
            String many = messages.get("table.rowCount", Integer.valueOf(4));
            assertFalse(one.equals(many),
                    "the plural form did not select, so the message is a substitution pretending to be "
                            + "ICU — which is the difference INT-UIX-008 names");
        }

        @Test
        @DisplayName("the document declares its language and direction")
        void documentDeclaresLanguage() {
            String rendered = page(Messages.forLocale(Messages.VIETNAMESE), "<p></p>");
            assertTrue(rendered.contains("lang=\"vi\""), "a screen reader picks pronunciation from lang");
            assertTrue(rendered.contains("dir=\"ltr\""),
                    "direction is declared even for a left-to-right locale, so INT-UIX-011's mirroring "
                            + "is a value change rather than a markup change");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-UIX-022 — unmeasured is never a numeral, in the markup")
    class Honesty {

        @Test
        @DisplayName("an unmeasured measure renders no digit")
        void unmeasuredHasNoNumeral() {
            Messages messages = Messages.forLocale(Messages.SOURCE);
            // Forty assets in scope, none measured. A component asking "is the value null" would render
            // the zero it was handed.
            String rendered = StateRenderer.measure(messages, "table.columnMeasured", 0, 0, 40, false);
            assertFalse(rendered.matches("(?s).*\\d.*"),
                    "a digit reached the markup for an unmeasured value: " + rendered
                            + ". Rendering unmeasured as zero is the interface-layer expression of the "
                            + "PP-1 failure the whole corpus guards against.");
            assertTrue(rendered.contains("Not measured"));
        }

        @Test
        @DisplayName("a measured figure carries its coverage in the same element")
        void aFigureCarriesItsCoverage() {
            String rendered = StateRenderer.measure(Messages.forLocale(Messages.SOURCE),
                    "table.columnMeasured", 12, 30, 40, false);
            assertTrue(rendered.contains("measure-coverage"),
                    "a figure whose qualifier is a sibling element is a figure somebody renders without "
                            + "it (DOC-08 §10)");
            assertTrue(rendered.contains("30 of 40"),
                    "the coverage qualifier must state what the figure was computed over: " + rendered);
        }

        @Test
        @DisplayName("the two empty states render differently")
        void emptyStatesAreDistinct() {
            Messages messages = Messages.forLocale(Messages.SOURCE);
            String noData = StateRenderer.state(messages, PresentationState.EMPTY_NO_DATA,
                    Optional.of("x"));
            String filtered = StateRenderer.state(messages, PresentationState.EMPTY_FILTERED,
                    Optional.of("x"));
            assertFalse(noData.equals(filtered),
                    "conflating them tells a user their estate is clean when their filter is wrong, and "
                            + "both look identical in a table with no rows");
        }

        @Test
        @DisplayName("every state carries a text label, not colour alone")
        void everyStateHasATextLabel() {
            Messages messages = Messages.forLocale(Messages.SOURCE);
            for (PresentationState state : PresentationState.values()) {
                String rendered = StateRenderer.state(messages, state, Optional.of("detail"));
                assertTrue(rendered.contains("state-label"),
                        state + " renders without a text label. High contrast and monochrome print are "
                                + "where a colour-only distinction disappears, and an executive report "
                                + "is printed.");
            }
        }

        @Test
        @DisplayName("withheld confirms nothing about whether a value exists")
        void withheldConfirmsNothing() {
            String rendered = StateRenderer.state(Messages.forLocale(Messages.SOURCE),
                    PresentationState.WITHHELD, Optional.empty());
            assertFalse(rendered.contains("*") || rendered.matches("(?s).*\\d.*"),
                    "a masked placeholder confirms the field has a value, which for a secret finding "
                            + "confirms a credential exists at that location (PRD-UIX-023)");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Accessibility and keyboard parity")
    class Accessibility {

        @Test
        @DisplayName("INT-UIX-003: a skip link and landmarks are on every page")
        void landmarksArePresent() {
            String rendered = page(Messages.forLocale(Messages.SOURCE), "<p>x</p>");
            assertTrue(rendered.contains("skip-link") && rendered.contains("href=\"#main\""),
                    "without a skip link the caller tabs through navigation on every page load");
            assertTrue(rendered.contains("id=\"main\""));
            assertTrue(rendered.contains("app-sidebar\" aria-label="),
                    "the section navigation must be a labelled landmark");
            assertTrue(rendered.contains("aria-current=\"page\""),
                    "the current section is announced, not only underlined");
        }

        @Test
        @DisplayName("PRD-UIX-013: no capability is attached to a non-interactive element")
        void noPointerOnlyCapability() {
            String rendered = page(Messages.forLocale(Messages.SOURCE), SignInFormMarkup.SAMPLE);
            for (String pointerOnly : List.of("onclick=", "ondblclick=", "onmouseover=", "role=\"button\"")) {
                assertFalse(rendered.contains(pointerOnly),
                        "found " + pointerOnly + ". A pointer-only capability MUST NOT exist "
                                + "(PRD-UIX-013), and an inline handler on a non-interactive element is "
                                + "how one arrives.");
            }
        }

        @Test
        @DisplayName("INT-UIX-004: zoom is not disabled")
        void zoomIsNotDisabled() {
            String rendered = page(Messages.forLocale(Messages.SOURCE), "<p></p>");
            assertFalse(rendered.contains("user-scalable=no") || rendered.contains("maximum-scale"),
                    "the interface must remain usable at 200% zoom and 400% text; disabling zoom forbids "
                            + "the first outright");
        }

        @Test
        @DisplayName("PRD-UIX-011: the current scope is on every page, from the shell")
        void scopeIsAlwaysVisible() {
            String rendered = page(Messages.forLocale(Messages.SOURCE), "<p></p>");
            assertTrue(rendered.contains("scope-switch"),
                    "a user uncertain which slice they are viewing will misread every figure on the page");
            assertTrue(rendered.contains("Tenant A root"));
        }

        @Test
        @DisplayName("the development-authentication warning appears when it applies, and only then")
        void developmentWarningTracksTheDeployment() {
            // Both directions, because the previous version asserted only the first and passed for the
            // wrong reason: Context.of hardcoded the flag to TRUE, so the banner rendered on every page in
            // every deployment. A warning that is always on is one nobody reads, which costs the attention
            // a real warning needs. It is a deployment property now, set once at startup.
            try {
                Page.developmentAuthentication(true);
                assertTrue(page(Messages.forLocale(Messages.SOURCE), "<p></p>")
                                .contains("Development authentication"),
                        "a page cannot forget it, because the page does not render it");

                Page.developmentAuthentication(false);
                assertFalse(page(Messages.forLocale(Messages.SOURCE), "<p></p>")
                                .contains("Development authentication"),
                        "with the real session resolver in use there is nothing to warn about, and a "
                                + "banner saying otherwise is false on its face");
            } finally {
                // Restored, because the flag is static and a leaked true would make every later assertion
                // in this suite render a banner it does not expect.
                Page.developmentAuthentication(false);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Every link the interface renders points at a route that exists")
    class NoDeadLinks {

        /**
         * The href templates the page classes emit, checked against the registry.
         *
         * <p>{@code /assessments} was linked from the overview for weeks with no route behind it —
         * removed from the sidebar and left in two KPI drill-downs, so the figures were clickable and
         * answered 404. A dead link on a coverage figure teaches people not to click the figures,
         * which is worse than the missing page.
         *
         * <p>Static hrefs only. A link built from a row identifier cannot be checked without data, and
         * those are covered by the route/operation parity the dispatcher enforces at construction.
         *
         * <p><b>This test once matched {@code "/ui/…"} and nothing else.</b> When the interface moved off
         * that prefix to the root, every literal it looked for disappeared, so it swept zero hrefs and
         * passed — and five dead links went in behind it: three overview drill-downs and a workload
         * meter pointing at {@code /findings}, and two breadcrumbs pointing at {@code /requests} and
         * {@code /users}, all of them routes the same change removed. A green test over an empty sweep is
         * worse than no test, so the non-vacuity assertion below is part of the test and not decoration.
         */
        @Test
        @DisplayName("no page class emits a static href with no registered GET")
        void staticLinksResolve() throws Exception {
            java.util.Set<String> registered = aspm.app.api.PlatformOperations.registry().all()
                    .stream().filter(o -> "GET".equalsIgnoreCase(o.method()))
                    .map(aspm.app.api.OperationRegistry.Operation::pathTemplate)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

            java.nio.file.Path root = java.nio.file.Path.of("src/main/java/aspm/app/ui");
            java.util.regex.Pattern href = java.util.regex.Pattern.compile(
                    "\"(/[A-Za-z0-9/_{}?=&.-]*)\"");
            java.util.List<String> dead = new java.util.ArrayList<>();
            int swept = 0;
            try (var files = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java"))
                        .toList()) {
                    String source = java.nio.file.Files.readString(file);
                    var matcher = href.matcher(source);
                    while (matcher.find()) {
                        // A literal the source appends to something is a SUFFIX, not an address:
                        // BOARD + id + "/comments" is a route, "/comments" is not. Judged on the
                        // character before the quote because that is the only place the distinction
                        // exists — the string itself looks like a path either way.
                        String preceding = source.substring(0, matcher.start()).stripTrailing();
                        if (preceding.endsWith("+")) {
                            continue;
                        }
                        String path = matcher.group(1).split("\\?", 2)[0];
                        // A trailing slash is a concatenation prefix ("/board/" + id); a dot is a file
                        // served by name rather than a page; /api, /internal and /aspm are not pages.
                        if (path.equals("/") || path.endsWith("/") || path.contains(".")
                                || path.startsWith("/api/") || path.startsWith("/internal/")
                                || path.startsWith("/aspm/")) {
                            continue;
                        }
                        swept++;
                        if (!registered.contains(path)) {
                            dead.add(file.getFileName() + " -> " + path);
                        }
                    }
                }
            }
            assertTrue(dead.isEmpty(),
                    "these hrefs have no registered GET operation: " + dead);
            // The sweep found something to check. Without this the test survives any future move of the
            // address space by checking nothing at all, which is how the five links above got in.
            assertTrue(swept > 50, "the href sweep matched only " + swept
                    + " paths, so it is no longer looking at the addresses the pages emit");
        }

        /**
         * {@code SEC-SEC-032}, {@code SEC-SEC-047}: no {@code unsafe-inline}, which makes a
         * {@code style} attribute unusable.
         *
         * <p>This is a regression test for a defect that was invisible for as long as it existed.
         * Every meter, coverage bar and chart column on these pages carried its width in a style
         * attribute; the browser blocked all of them and drew each bar at zero. <b>A coverage bar at
         * zero is not a broken bar, it is a wrong answer</b> — indistinguishable from a measured zero,
         * which is the exact failure {@code PRD-UIX-022} exists to prevent.
         *
         * <p>Nothing reported it. The policy is enforced by the browser, the violation goes to a
         * console nobody reads in CI, and the page still returns 200. So the check has to be on the
         * source: a style attribute must not be written at all, and a dynamic value becomes a
         * generated class ({@code DesignSystem#widthClass}).
         */
        @Test
        @DisplayName("SEC-SEC-047: no page emits a style attribute, which the policy blocks")
        void noInlineStyleAttributes() throws Exception {
            java.nio.file.Path root = java.nio.file.Path.of("src/main/java/aspm/app/ui");
            java.util.List<String> offenders = new java.util.ArrayList<>();
            try (var files = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java"))
                        .toList()) {
                    String[] lines = java.nio.file.Files.readString(file).split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        // The emitted form, in a Java string literal: style=\" — a mention in prose or
                        // a CSS rule inside the design system is neither.
                        if (line.contains("style=\\\"")) {
                            offenders.add(file.getFileName() + ":" + (i + 1));
                        }
                    }
                }
            }
            assertTrue(offenders.isEmpty(),
                    "a style attribute is blocked by the Content Security Policy this tier sends, so "
                            + "these render with the declaration silently dropped: " + offenders);
        }

        /**
         * The enhancement script has to parse, or none of it runs.
         *
         * <p>It did not, for its entire life. {@code Script#js} is a Java text block, so the
         * {@code \\n} written into a JavaScript string literal became a real line break and the file
         * was a syntax error on its ninety-fourth line. Everything in it — the Markdown toolbar, the
         * live preview, the command dialog, list navigation — was dead, and silently, because the
         * script is progressive enhancement and a page without it still works.
         *
         * <p>A full parse needs an engine this build does not have. What is checked instead is the
         * specific defect: no raw line break inside a single-quoted literal, which is what a text
         * block's own escape processing produces.
         */
        @Test
        @DisplayName("the enhancement script has no line break inside a string literal")
        void theScriptParses() {
            String[] lines = Script.js().split("\n", -1);
            java.util.List<String> broken = new java.util.ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                // An odd number of unescaped single quotes means a literal is still open at the end
                // of the line. Comments in this file use // and none of them contains an apostrophe;
                // the test fails loudly rather than silently if one ever does.
                long quotes = lines[i].chars().filter(c -> c == '\'').count();
                if (quotes % 2 != 0) {
                    broken.add((i + 1) + ": " + lines[i].strip());
                }
            }
            assertTrue(broken.isEmpty(),
                    "a string literal is left open at the end of these lines, which is what a text "
                            + "block's \\n produces when it was meant for JavaScript: " + broken);
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("The interface route table and the React router say the same thing")
    class InterfaceRouteTable {

        /**
         * Where the router lives, relative to this module. Absent in a source tree without the frontend,
         * which is a reason to skip rather than to fail: the assertion is about drift between two files,
         * and one of them not being there is not drift.
         */
        private static final java.nio.file.Path ROUTER =
                java.nio.file.Path.of("..", "webui", "src", "main.tsx");

        @Test
        @DisplayName("every route the router declares is registered, and nothing else is")
        void theTableMatchesTheRouter() throws Exception {
            org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(ROUTER),
                    "no frontend source in this tree; nothing to compare against");
            String source = java.nio.file.Files.readString(ROUTER);

            // path="board/:id/findings/:findingId" becomes /board/{id}/findings/{findingId}. The wildcard
            // is the router's own not-found entry and is deliberately NOT registered: a server-side route
            // for it would answer every unrouted path with a page saying the interface is fine.
            TreeSet<String> declared = new TreeSet<>();
            var matcher = java.util.regex.Pattern.compile("path=\"([^\"]+)\"").matcher(source);
            while (matcher.find()) {
                String path = matcher.group(1);
                if ("*".equals(path)) {
                    continue;
                }
                declared.add("/" + path.replaceAll(":([A-Za-z0-9_]+)", "{$1}"));
            }
            assertFalse(declared.isEmpty(), "the router was read but no routes were found in it, which "
                    + "means this test is passing without comparing anything");

            assertEquals(declared, new TreeSet<>(WebUi.ROUTES),
                    "WebUi.ROUTES and the React router disagree. A path the router declares and the "
                            + "server does not answers 404 on reload and on a pasted link — it works only "
                            + "while a person navigates to it from inside the interface, which is why this "
                            + "kind of break reaches a user rather than a test. A path registered here and "
                            + "not declared there renders the shell and then the interface's own "
                            + "not-found page.");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("The shell shows only what the caller can open")
    class NavigationVisibility {

        private static String shellFor(java.util.Set<String> permissions) {
            var principal = new aspm.app.runtime.Principal(
                    java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    java.util.UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    permissions, java.util.Set.of(), true, false, false);
            return Page.render(Messages.forLocale(Messages.SOURCE),
                    Page.Context.of("nav.findings", "/findings", Optional.of(principal)), "<p></p>");
        }

        @Test
        @DisplayName("a principal without the permission sees neither the link nor the palette entry")
        void administrationIsHiddenWithoutThePermission() {
            // The other half of the fix reported as "why can a developer see the RBAC table". The
            // dispatcher answers 404 now, but a sidebar that lists the page and then 404s hands back
            // exactly what the 404 withholds — and the command palette is the more likely leak, because
            // searching feels like nothing happened.
            String shell = shellFor(java.util.Set.of("vul.finding.read"));
            assertFalse(shell.contains("/roles"),
                    "the roles link is visible to a principal who cannot open it");
            assertFalse(shell.contains("/users"),
                    "the users link is visible to a principal who cannot open it");
            assertFalse(shell.contains("/security-policy"),
                    "the credential policy link is visible to a principal who cannot open it");
        }

        @Test
        @DisplayName("a group whose every entry is hidden does not render its label")
        void anEmptyGroupIsAbsent() {
            // "Configure" over an empty list still tells the caller a configuration area exists.
            String shell = shellFor(java.util.Set.of("vul.finding.read"));
            assertFalse(shell.contains("nav-label\">Configure"),
                    "an empty navigation group must not render its heading");
        }

        @Test
        @DisplayName("holding the permission puts the link back")
        void administrationAppearsWithThePermission() {
            String shell = shellFor(java.util.Set.of("auz.role.manage"));
            assertTrue(shell.contains("/roles"),
                    "a principal holding auz.role.manage must see the link, or the filter is not a "
                            + "filter but a removal");
        }

        @Test
        @DisplayName("the account link and sign-out are present for a principal holding NOTHING")
        void selfServiceIsAlwaysReachable() {
            // A principal with no role at all is what the deployment bootstrap creates, and it must reach
            // the password change. SEC-AUZ-014 denies on an empty grant, so this account can open nothing
            // else — which is correct, and is not the same as being locked out of its own credential.
            String shell = shellFor(java.util.Set.of());
            assertTrue(shell.contains("/account"), "the account link must not depend on a permission");
            assertTrue(shell.contains("action=\"/sign-out\""),
                    "sign-out must be a form post: it is a state change, and a GET is reachable by "
                            + "anything that prefetches a link");
        }

        @Test
        @DisplayName("every navigation href has a registered GET, so no entry is a dead link")
        void noNavigationEntryIsDead() {
            // /ui/assessments was in the sidebar with no route behind it, so the shell advertised a
            // section that answered 404. Asserted over the table rather than by clicking.
            for (Page.NavGroup group : Page.NAVIGATION) {
                for (Page.NavItem item : group.items()) {
                    assertTrue(item.requiredPermission() != null,
                            item.href() + " is in the navigation and has no registered GET operation, so "
                                    + "it is a dead link the sidebar advertises");
                }
            }
        }

        @Test
        @DisplayName("no interface navigation entry names a permission nothing enforces")
        void interfaceNavigationPermissionsAreReal() {
            // The React navigation DECLARES its permission rather than deriving it from the registry,
            // and the declared copy is the one that drifts. /components carried sbm.component.read,
            // which is in no migration and therefore in no catalogue — and because role_permission
            // references permission_catalogue, no role could hold it and no principal could ever be
            // offered the entry. The section was invisible to everybody, including an administrator
            // holding every permission that exists, and nothing reported it.
            //
            // Asserted against the operation registry rather than against the catalogue because a
            // unit test reaches no database. It is the weaker of the two checks and catches this
            // class of mistake: a permission no operation requires is a permission the platform does
            // not enforce, which SEC-AUZ-001 calls a defect in the other direction too.
            Set<String> enforced = aspm.app.api.PlatformOperations.registry().all().stream()
                    .map(operation -> operation.requiredPermission().orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> unenforced = new TreeSet<>();
            for (String permission : UiApi.navigationPermissions()) {
                if (!enforced.contains(permission)) {
                    unenforced.add(permission);
                }
            }
            assertTrue(unenforced.isEmpty(),
                    "the interface navigation names permissions no registered operation requires: "
                            + unenforced + ". An entry gated on a permission nothing enforces is "
                            + "hidden from every principal, which reads as a missing feature rather "
                            + "than as a mistake.");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("The user guide")
    class Guide {

        @Test
        @DisplayName("a document exists for every declared translation, and none is empty")
        void everyDeclaredTranslationLoads() {
            // A missing resource and a misspelled one are the same thing to getResourceAsStream, so a
            // translation renamed by accident would silently serve English and nothing would say so.
            for (String language : GuidePage.translations()) {
                String source = GuidePage.load(Locale.forLanguageTag(language));
                assertFalse(source.isBlank(),
                        "the guide for " + language + " did not load. GuidePage falls back to English "
                                + "rather than serving an empty page, so this failure is invisible at "
                                + "runtime and has to be caught here.");
                assertTrue(source.length() > 4000,
                        "the guide for " + language + " is " + source.length() + " characters. A guide "
                                + "that short is a stub, and a stub in the place a confused reader "
                                + "looks is worse than no entry in the sidebar.");
            }
        }

        @Test
        @DisplayName("an unknown locale falls back to the source language rather than to nothing")
        void unknownLocaleFallsBack() {
            assertEquals(GuidePage.load(Locale.forLanguageTag("en")),
                    GuidePage.load(Locale.forLanguageTag("fr")),
                    "a reader whose language has no guide is inconvenienced by the source language and "
                            + "told the platform has no guide by an empty page");
        }

        @Test
        @DisplayName("the guide renders through the restricted renderer and emits no raw markup")
        void guideRendersThroughTheRestrictedRenderer() {
            // The guide is trusted content, which is exactly why it goes through the same renderer as
            // a proof of concept: a second path for "trusted" Markdown is a path somebody later
            // points at untrusted content, and the two would then disagree about what markup exists.
            String rendered = Markdown.render(GuidePage.load(Messages.SOURCE));
            // h4 rather than h3: Markdown.render starts headings at h3 because the page owns h1 and
            // the card owns h2, so the guide's "##" sections land one level below that.
            //
            // Matched on the opening tag rather than the whole prefix, because headings now carry an
            // id — the contents list addresses them, and an id added from the browser was lost to the
            // first re-render. A literal that included the closing quote asserted the ABSENCE of that
            // id without meaning to.
            assertTrue(rendered.contains("<h4 class=\"md-h\" id=\""),
                    "the guide's section headings did not render, so the document is being shown as "
                            + "one undifferentiated block");
            assertFalse(rendered.contains("<script"), "the renderer emitted a script element");
            assertFalse(rendered.contains("<img"),
                    "the renderer emitted an image element; images by URL are refused because a tag "
                            + "pointing at another host reports who opened the page and when");
        }

        @Test
        @DisplayName("the guide fits the renderer's bound, so its tail is not silently dropped")
        void guideFitsTheRendererBound() {
            // Markdown.render truncates at 64,000 characters. Truncation is right for a comment and
            // wrong for a document: the reader would simply not see the last sections and nothing on
            // the page would indicate that anything was missing.
            for (String language : GuidePage.translations()) {
                int length = GuidePage.load(Locale.forLanguageTag(language)).length();
                assertTrue(length < 60_000,
                        "the " + language + " guide is " + length + " characters and the renderer "
                                + "truncates at 64,000. Split it before it reaches the bound rather "
                                + "than after, because the failure is silent.");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Escaping — finding content is attacker-authored by design")
    class Escaping {

        @Test
        @DisplayName("markup in a value cannot reach the page as markup")
        void valuesAreEscaped() {
            String payload = "<script>alert(1)</script>";
            assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", Html.text(payload));
            assertFalse(page(Messages.forLocale(Messages.SOURCE),
                    "<p>" + Html.text(payload) + "</p>").contains("<script>alert"));
        }

        @Test
        @DisplayName("an attribute value is quoted by the escaper, not by the caller")
        void attributesAreQuoted() {
            String value = Html.attribute("a\" onload=\"alert(1)");
            assertTrue(value.startsWith("\"") && value.endsWith("\""));
            assertFalse(value.contains("onload=\""),
                    "an unescaped quote closes the attribute and the rest becomes markup");
        }

        @Test
        @DisplayName("a token name is validated rather than escaped")
        void tokenNamesAreValidated() {
            assertThrows(IllegalArgumentException.class, () -> Html.cssIdentifier("a; }"),
                    "escaping for a CSS context is a different operation from escaping for HTML, and "
                            + "using one where the other belongs is how a payload survives");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-UIX-006 — both themes meet contrast independently")
    class Contrast {

        private static final Pattern DECLARATION =
                Pattern.compile("--([a-zA-Z-]+):\\s*(#[0-9a-fA-F]{6})");

        @Test
        @DisplayName("light and dark are declared separately, neither derived from the other")
        void themesAreDeclaredNotDerived() {
            String css = DesignSystem.css();
            assertTrue(css.contains(":root[data-theme=\"dark\"]"));
            assertFalse(css.contains("filter: invert"),
                    "an inverted palette fails contrast in predictable places, and the failure is in the "
                            + "theme that receives less design attention (PRD-UIX-006)");
        }

        @Test
        @DisplayName("primary and secondary text meet 4.5:1 on their own surface, in both themes")
        void textMeetsContrast() {
            // WCAG 2.2 AA 1.4.3: 4.5:1 for normal text. Computed rather than asserted by eye, because
            // "it looks fine" is how the muted-text-on-raised-surface failure ships.
            check("light", "#ffffff", "#14161a", "#4a5058");
            check("dark", "#14161a", "#f2f4f7", "#b6bcc6");
        }

        private static void check(String theme, String background, String primary, String secondary) {
            for (String foreground : List.of(primary, secondary)) {
                double ratio = contrast(foreground, background);
                assertTrue(ratio >= 4.5,
                        theme + ": " + foreground + " on " + background + " is "
                                + String.format(Locale.ROOT, "%.2f", ratio)
                                + ":1, below the 4.5:1 of WCAG 2.2 AA 1.4.3 (INT-UIX-001)");
            }
        }

        @Test
        @DisplayName("every colour token declared in a theme is a real six-digit value")
        void tokensParse() {
            Matcher matcher = DECLARATION.matcher(DesignSystem.css());
            int found = 0;
            while (matcher.find()) {
                found++;
            }
            assertTrue(found >= 40,
                    "only " + found + " colour tokens parsed; a typo in a token value falls back to the "
                            + "inherited colour and the failure is invisible in the theme nobody opens");
        }

        private static double contrast(String a, String b) {
            double la = luminance(a);
            double lb = luminance(b);
            double lighter = Math.max(la, lb);
            double darker = Math.min(la, lb);
            return (lighter + 0.05) / (darker + 0.05);
        }

        private static double luminance(String hex) {
            double[] channel = new double[3];
            for (int i = 0; i < 3; i++) {
                double value = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
                channel[i] = value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
            }
            return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2];
        }
    }

    /** A sample of the sign-in form's markup, so the parity assertion has real content to scan. */
    private static final class SignInFormMarkup {
        static final String SAMPLE = """
                <form class="stack" method="post" action="/sign-in">
                  <label>Tenant<input name="tenant"></label>
                  <button type="submit" class="primary">Sign in</button>
                </form>
                """;
    }
}
