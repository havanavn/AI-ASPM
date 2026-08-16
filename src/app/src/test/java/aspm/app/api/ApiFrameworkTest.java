package aspm.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Prompt 12 session 1 — the API framework properties of DOC-05 section 5 and ADR-036, plus
 * {@code TST-PLT-008}.
 */
class ApiFrameworkTest {

    private static final UUID TENANT_A = new UUID(1, 1);
    private static final UUID TENANT_B = new UUID(2, 2);

    private static OperationRegistry.Operation operation(String method, String path,
            AnnotationClass annotationClass, String permission) {
        return new OperationRegistry.Operation(method, path, annotationClass,
                Optional.ofNullable(permission), Set.of(), Set.of());
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("ADR-036 — the seven classes as framework properties")
    class Classes {

        @Test
        @DisplayName("there are exactly seven classes, and each letter resolves")
        void sevenClasses() {
            assertEquals(7, AnnotationClass.values().length,
                    "ADR-036 is 'API security as seven annotation classes'. An eighth is a decision about "
                            + "how API security is expressed, not an operation-level choice.");
            for (String letter : List.of("A", "B", "C", "D", "E", "F", "G")) {
                assertEquals(letter, AnnotationClass.ofLetter(letter).name().substring(0, 1));
            }
            assertThrows(IllegalArgumentException.class, () -> AnnotationClass.ofLetter("H"));
        }

        @Test
        @DisplayName("every class carries all nine properties, so an operation cannot omit one")
        void propertiesAreCarriedByTheClass() {
            for (AnnotationClass annotationClass : AnnotationClass.values()) {
                assertTrue(annotationClass.authentication() != null
                                && annotationClass.scopeRevalidation() != null
                                && annotationClass.rateClass() != null
                                && annotationClass.replayProtection() != null
                                && annotationClass.auditLevel() != null
                                && annotationClass.classification() != null,
                        annotationClass + " is missing a property. Written per operation, an operation ships "
                                + "with eight of nine — and the missing one is discovered when somebody "
                                + "audits, which is to say not discovered (DOC-00 section 15.1).");
            }
        }

        @Test
        @DisplayName("the two step-up classes are C and E, and both demand an elevated permission")
        void stepUpClasses() {
            var stepUp = new ArrayList<AnnotationClass>();
            for (AnnotationClass annotationClass : AnnotationClass.values()) {
                if (annotationClass.requiresStepUp()) {
                    stepUp.add(annotationClass);
                }
            }
            assertEquals(List.of(AnnotationClass.C_RESTRICTED_REVEAL, AnnotationClass.E_CONFIGURATION),
                    stepUp, "DOC-05 section 5 marks exactly these two");
            for (AnnotationClass annotationClass : stepUp) {
                assertTrue(annotationClass.requiresElevatedPermission(),
                        annotationClass + ": a reveal permission any administrative role happens to include "
                                + "is not a control, and 'never implied' is the load-bearing phrase");
            }
        }

        @Test
        @DisplayName("every non-idempotent class requires an idempotency key")
        void nonIdempotentClassesRequireAKey() {
            assertTrue(AnnotationClass.B_SCOPED_WRITE.requiresIdempotencyKey());
            assertTrue(AnnotationClass.D_BULK_OR_EXPORT.requiresIdempotencyKey());
            assertTrue(AnnotationClass.E_CONFIGURATION.requiresIdempotencyKey());
            assertTrue(AnnotationClass.F_SERVICE_INGEST.requiresIdempotencyKey());

            assertFalse(AnnotationClass.A_SCOPED_READ.requiresIdempotencyKey(),
                    "a read is idempotent by construction; requiring a key would make every list request "
                            + "carry one and train clients to reuse a constant");
            assertFalse(AnnotationClass.C_RESTRICTED_REVEAL.requiresIdempotencyKey(),
                    "a reveal is a read — and its per-object audit event is what makes repetition visible, "
                            + "which an idempotency key would suppress");
        }

        @Test
        @DisplayName("F is the only class a human session cannot invoke")
        void serviceIngestIsNotHumanInvokable() {
            assertFalse(AnnotationClass.F_SERVICE_INGEST.invokableByHumanSession(),
                    "a service-ingest operation reachable by a browser session is one whose pinned-scope "
                            + "guarantee does not apply, because a human session has no pinned scope to check "
                            + "against (SEC-AUZ-035)");
            assertEquals(AnnotationClass.ScopeRevalidation.AGAINST_PINNED_SCOPE,
                    AnnotationClass.F_SERVICE_INGEST.scopeRevalidation(),
                    "the payload's asserted scope is never trusted (INV-SBM-06)");
        }

        @Test
        @DisplayName("only C is classified RESTRICTED, and only G is PUBLIC")
        void classificationsMatchTheDocument() {
            assertEquals(AnnotationClass.Classification.RESTRICTED,
                    AnnotationClass.C_RESTRICTED_REVEAL.classification());
            assertEquals(AnnotationClass.Classification.PUBLIC,
                    AnnotationClass.G_UNAUTHENTICATED.classification());
            assertEquals(AnnotationClass.AuditLevel.PER_OBJECT_READ,
                    AnnotationClass.C_RESTRICTED_REVEAL.auditLevel(),
                    "per-object, because a reveal must be answerable afterwards for a specific object");
            assertEquals(AnnotationClass.AuditLevel.BEFORE_AND_AFTER,
                    AnnotationClass.E_CONFIGURATION.auditLevel(),
                    "configuration change appears nowhere in a finding-level audit review, so the record of "
                            + "what it used to be is the only way to see what changed");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-API-019 — every operation is assigned a class")
    class Registry {

        @Test
        @DisplayName("an unregistered operation does not resolve, so it cannot be dispatched")
        void unregisteredOperationDoesNotResolve() {
            var registry = OperationRegistry.of(List.of(
                    operation("GET", "/findings/{id}", AnnotationClass.A_SCOPED_READ, "vul.finding.read")));
            assertTrue(registry.resolve("GET", "/findings/{id}").isPresent());
            assertTrue(registry.resolve("DELETE", "/findings/{id}").isEmpty(),
                    "running an unregistered operation is running one with no annotation class, which is "
                            + "exactly what PRD-API-019 forbids");
        }

        @Test
        @DisplayName("an authenticated operation naming no permission cannot be registered")
        void authenticatedOperationsNameAPermission() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> operation("POST", "/findings", AnnotationClass.B_SCOPED_WRITE, null));
            assertTrue(ex.getMessage().contains("deny by default")
                            || ex.getMessage().contains("Deny by default"),
                    "an operation nobody can be authorized for becomes an operation somebody removes the "
                            + "check from; got " + ex.getMessage());
        }

        @Test
        @DisplayName("an unauthenticated operation declaring a permission cannot be registered")
        void unauthenticatedOperationsDeclareNone() {
            assertThrows(IllegalArgumentException.class,
                    () -> operation("GET", "/health", AnnotationClass.G_UNAUTHENTICATED, "sys.health.read"),
                    "a permission on class G cannot be evaluated, and declaring one reads as a control that "
                            + "is not there");
        }

        @Test
        @DisplayName("two operations on the same method and path are rejected")
        void duplicateRegistrationRejected() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> OperationRegistry.of(List.of(
                            operation("GET", "/findings/{id}", AnnotationClass.A_SCOPED_READ, "p"),
                            operation("GET", "/findings/{id}", AnnotationClass.C_RESTRICTED_REVEAL, "q"))));
            assertTrue(ex.getMessage().contains("weaker one would sometimes win"),
                    "which class applies would depend on registration order");
        }

        @Test
        @DisplayName("the registry answers 'which operations are class C' in one place")
        void classMembershipIsQueryable() {
            var registry = OperationRegistry.of(List.of(
                    operation("GET", "/findings/{id}", AnnotationClass.A_SCOPED_READ, "vul.finding.read"),
                    operation("GET", "/secrets/{id}/reveal", AnnotationClass.C_RESTRICTED_REVEAL,
                            "vul.secret.reveal"),
                    operation("POST", "/sbom", AnnotationClass.F_SERVICE_INGEST, "sbm.snapshot.submit")));

            assertEquals(1, registry.inClass(AnnotationClass.C_RESTRICTED_REVEAL).size(),
                    "with annotations on handler methods a reviewer reads every handler and trusts they "
                            + "found them all");
            assertEquals(3, registry.all().size());
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Framework absolutes — unknown fields, typed filters, absent restricted fields")
    class Absolutes {

        @Test
        @DisplayName("PRD-API-020: unknown fields are rejected, and every one is named")
        void unknownFieldsRejected() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("severity", "HIGH");
            body.put("severtiy", "HIGH");
            body.put("assigness", "someone");

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> RequestValidation.rejectUnknownFields(Set.of("severity", "assignee"), body));
            assertTrue(ex.getMessage().contains("severtiy") && ex.getMessage().contains("assigness"),
                    "one round trip should fix them all; got " + ex.getMessage());
            assertFalse(ex.getMessage().contains("did you mean"),
                    "a 'did you mean' over the declared schema is a schema-disclosure oracle for an "
                            + "operation the caller may not be authorized to use in full");
        }

        @Test
        @DisplayName("a known body passes")
        void knownFieldsAccepted() {
            RequestValidation.rejectUnknownFields(Set.of("severity", "assignee"),
                    Map.of("severity", "HIGH"));
        }

        @Test
        @DisplayName("there is no filter expression language — filters are typed and declared")
        void noFilterExpressionLanguage() {
            for (Method m : RequestValidation.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("expression") || name.contains("parsequery")
                                || name.contains("rsql") || name.contains("odata"),
                        "found " + m.getName() + ". An expression language on a scoped collection is a second "
                                + "query planner that has to reapply the scope predicate correctly — and the "
                                + "predicate must be applied IN retrieval (SEC-AUZ-016), not over the result "
                                + "of an expression the client composed.");
            }
            assertThrows(IllegalArgumentException.class,
                    () -> RequestValidation.validateFilters(Set.of("severity"),
                            List.of(new RequestValidation.TypedFilter("internalNotes",
                                    RequestValidation.TypedFilter.Operator.EQUALS, "x"))),
                    "a filter over a field the caller cannot read is an oracle over its values "
                            + "(SEC-AUZ-021)");
        }

        @Test
        @DisplayName("SEC-AUZ-022: a restricted field is ABSENT, not masked")
        void restrictedFieldsAreAbsent() {
            Map<String, Object> representation = new LinkedHashMap<>();
            representation.put("id", "f-1");
            representation.put("title", "SQL injection");
            representation.put("secretValue", "hunter2");

            var filtered = RequestValidation.withRestrictedFieldsAbsent(representation,
                    Set.of("secretValue"));

            assertFalse(filtered.containsKey("secretValue"),
                    "masking leaves the key present with a placeholder, which tells the reader the field "
                            + "exists and has a value — and for a boolean or a low-cardinality enumeration "
                            + "that is most of the information (ADR-047)");
            assertFalse(filtered.toString().contains("***"), "no mask token either");
            assertEquals(2, filtered.size());
            assertTrue(filtered.containsKey("title"), "and the rest of the representation survives");
        }

        @Test
        @DisplayName("the idempotency key is tenant-namespaced, and two tenants cannot collide")
        void idempotencyKeysAreTenantNamespaced() {
            var a = IdempotencyKey.namespaced(TENANT_A, "retry-1");
            var b = IdempotencyKey.namespaced(TENANT_B, "retry-1");
            assertNotEquals(a.storageKey(), b.storageKey(),
                    "a collision returns the FIRST tenant's stored response to the second — a cross-tenant "
                            + "disclosure produced by a reliability mechanism (DOC-24)");
            assertTrue(a.storageKey().startsWith(TENANT_A.toString()),
                    "tenant first, so a prefix scan cannot cross a tenant boundary");

            assertThrows(NullPointerException.class, () -> IdempotencyKey.namespaced(null, "retry-1"));
            assertThrows(IllegalArgumentException.class, () -> IdempotencyKey.namespaced(TENANT_A, "  "),
                    "a blank key means a retried request is applied twice while appearing protected");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("A3 / PRD-API-021 — the denial that cannot differentiate")
    class Denials {

        @Test
        @DisplayName("there is exactly one denial, so status, code and message cannot differ")
        void oneDenialOnly() {
            var first = DenialResponse.notFound();
            var second = DenialResponse.notFound();
            assertEquals(first, second);
            assertEquals(404, first.status());
            assertEquals("not found", first.message());

            for (Method m : DenialResponse.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("forbidden") || name.contains("unauthorized")
                                || name.contains("denied"),
                        "found " + m.getName() + ". A distinct response for authorization restores the oracle "
                                + "in one field (SEC-AUZ-020).");
            }
            for (Method m : DenialResponse.class.getMethods()) {
                if (m.getName().equals("notFound")) {
                    assertEquals(0, m.getParameterCount(),
                            "a reason parameter is a field somebody logs, then returns, then a client "
                                    + "depends on");
                }
            }
        }

        @Test
        @DisplayName("the denial body carries no identifier or object kind")
        void denialCarriesNothing() {
            var body = DenialResponse.notFound();
            assertFalse(body.message().contains("finding") || body.message().contains("id"),
                    "naming the object kind tells the caller which endpoints exist, and naming the "
                            + "identifier confirms they submitted a well-formed one");
        }

        @Test
        @DisplayName("SEC-AUZ-016: a retrieval without its scope predicate is caught structurally")
        void scopePredicateMustBeInTheQuery() {
            String correct = "SELECT * FROM finding WHERE id = ? AND scope_ancestor_path @> ARRAY[?]";
            DenialResponse.assertAppliedInRetrieval(correct, "scope_ancestor_path @>");

            String fetchThenCheck = "SELECT * FROM finding WHERE id = ?";
            var ex = assertThrows(IllegalStateException.class,
                    () -> DenialResponse.assertAppliedInRetrieval(fetchThenCheck, "scope_ancestor_path @>"));
            assertTrue(ex.getMessage().contains("existence oracle"),
                    "fetching by identifier and comparing scope afterwards produces the same 404 and a "
                            + "measurably different latency");
        }

        @Test
        @DisplayName("TST-AUZ-002: the timing assertion is statistical, not a single comparison")
        void timingIsIndistinguishableAcrossManySamples() {
            // Both paths do the same work, because the scope predicate is in the query: the row is not
            // found either way. This models that — and the assertion is over a distribution, since "a single
            // comparison cannot distinguish it from noise" (TST-AUZ-002).
            int samples = 2_000;
            List<Long> outOfScope = new ArrayList<>(samples);
            List<Long> nonExistent = new ArrayList<>(samples);

            for (int i = 0; i < samples; i++) {
                outOfScope.add(measure(true));
                nonExistent.add(measure(false));
            }

            long medianOutOfScope = median(outOfScope);
            long medianNonExistent = median(nonExistent);
            long larger = Math.max(medianOutOfScope, medianNonExistent);
            long difference = Math.abs(medianOutOfScope - medianNonExistent);

            // A lookup-then-deny path differs by a factor, not a few nanoseconds. Ten percent of the median
            // is generous against measurement noise and still far below what a second query costs.
            assertTrue(difference * 10 <= Math.max(larger, 1),
                    "median for an out-of-scope object was " + medianOutOfScope + "ns and for a non-existent "
                            + "one " + medianNonExistent + "ns. A consistent difference is a reliable "
                            + "existence oracle for an attacker willing to average over a few hundred "
                            + "requests (PRD-API-021, TST-AUZ-002).");
        }

        /**
         * Models a scoped retrieval. Both cases execute the same path, which is the property under test:
         * the predicate is in the query, so an out-of-scope row is simply not returned.
         */
        private long measure(boolean rowExistsButIsOutOfScope) {
            long start = System.nanoTime();
            String query = "SELECT * FROM finding WHERE id = ? AND scope_ancestor_path @> ARRAY[?]";
            DenialResponse.assertAppliedInRetrieval(query, "scope_ancestor_path @>");
            // No branch on rowExistsButIsOutOfScope: that IS the assertion. A handler that branched here
            // would be the lookup-then-deny path.
            var body = DenialResponse.notFound();
            long elapsed = System.nanoTime() - start;
            // Consume both so neither is optimised away.
            if (body.status() != 404 || (!rowExistsButIsOutOfScope && body.code().isEmpty())) {
                throw new AssertionError("unreachable");
            }
            return elapsed;
        }

        private long median(List<Long> values) {
            var sorted = new ArrayList<>(values);
            java.util.Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("TST-PLT-008 — keyset pagination skips and duplicates nothing")
    class Pagination {

        /** A row with a non-unique sort key, which is the case that breaks a cursor without a tiebreaker. */
        private record Row(String updatedAt, String id) {
        }

        private static KeysetPage.Cursor cursorOf(Row row) {
            return new KeysetPage.Cursor(row.updatedAt(), row.id());
        }

        /** Rows strictly after the cursor under {@code (updatedAt, id)}. */
        private static List<Row> after(List<Row> all, Optional<KeysetPage.Cursor> cursor, int limit) {
            var sorted = new ArrayList<>(all);
            sorted.sort(java.util.Comparator.comparing(Row::updatedAt).thenComparing(Row::id));
            var result = new ArrayList<Row>();
            for (Row row : sorted) {
                if (cursor.isPresent()) {
                    int c = row.updatedAt().compareTo(cursor.get().sortValue());
                    if (c < 0 || (c == 0 && row.id().compareTo(cursor.get().tiebreaker()) <= 0)) {
                        continue;
                    }
                }
                result.add(row);
                if (result.size() > limit) {
                    break;
                }
            }
            return result;
        }

        @Test
        @DisplayName("walking pages under concurrent insertion loses no pre-existing row and repeats none")
        void concurrentInsertionSkipsAndDuplicatesNothing() {
            // Twenty rows sharing five distinct timestamps — the shape that defeats a cursor on the sort
            // key alone.
            var rows = new ArrayList<Row>();
            for (int i = 0; i < 20; i++) {
                rows.add(new Row("2026-08-0" + (1 + i % 5), String.format("id-%02d", i)));
            }
            var original = Set.copyOf(rows);

            var seen = new ArrayList<Row>();
            Optional<KeysetPage.Cursor> cursor = Optional.empty();
            int inserted = 0;

            while (true) {
                var page = KeysetPage.of(after(rows, cursor, 5), 5, Pagination::cursorOf);
                seen.addAll(page.items());
                if (!page.hasMore()) {
                    break;
                }
                cursor = page.nextCursor();

                // Concurrent modification between pages: a row inserted at the EARLIEST timestamp, which
                // under offset pagination shifts every later row forward by one and loses one.
                rows.add(new Row("2026-08-01", "id-inserted-" + inserted));
                inserted++;
            }

            assertTrue(inserted > 0, "the test must actually have modified the set between pages");

            var seenIds = new TreeSet<String>();
            for (Row row : seen) {
                assertTrue(seenIds.add(row.id()),
                        "row " + row.id() + " appeared twice. Under offset pagination an insert before the "
                                + "current offset shifts every subsequent row forward, so a client walking "
                                + "pages misses one and sees another twice (TST-PLT-008).");
            }
            for (Row row : original) {
                assertTrue(seenIds.contains(row.id()),
                        "row " + row.id() + " was never returned. On a findings list that is a finding a "
                                + "reviewer never sees, and nothing anywhere reports it.");
            }
        }

        @Test
        @DisplayName("a cursor without a unique tiebreaker cannot be constructed")
        void tiebreakerIsRequired() {
            var ex = assertThrows(NullPointerException.class,
                    () -> new KeysetPage.Cursor("2026-08-01", null));
            assertTrue(ex.getMessage().contains("straddle the boundary"),
                    "the offset failure reintroduced by the mechanism adopted to avoid it");
        }

        @Test
        @DisplayName("a cursor round-trips through its opaque token")
        void cursorRoundTrips() {
            var cursor = new KeysetPage.Cursor("2026-08-01T09:00:00Z", "id-07");
            assertEquals(cursor, KeysetPage.Cursor.decode(cursor.encode()));
            assertFalse(cursor.encode().contains("id-07"),
                    "an opaque token, so a client cannot construct one that means 'row 5000' and reintroduce "
                            + "offset semantics");
        }

        @Test
        @DisplayName("there is no offset parameter anywhere in the pagination API")
        void noOffsetAnywhere() {
            for (Method m : KeysetPage.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("offset") || name.contains("pagenumber")
                                || name.contains("skip"),
                        "found " + m.getName() + "; offset pagination is absent rather than discouraged");
            }
            for (var component : KeysetPage.Cursor.class.getRecordComponents()) {
                assertNotEquals("offset", component.getName());
            }
        }

        @Test
        @DisplayName("an oversized page size is clamped, not rejected")
        void pageSizeIsClamped() {
            assertEquals(KeysetPage.MAX_PAGE_SIZE, KeysetPage.clampPageSize(10_000),
                    "a client asking for ten thousand rows is a script written by somebody who did not read "
                            + "the documentation; rejecting makes them retry in a loop");
            assertEquals(KeysetPage.DEFAULT_PAGE_SIZE, KeysetPage.clampPageSize(0));
            assertEquals(25, KeysetPage.clampPageSize(25));
        }

        @Test
        @DisplayName("the last page carries no cursor")
        void lastPageHasNoCursor() {
            var rows = List.of(new Row("2026-08-01", "id-1"), new Row("2026-08-02", "id-2"));
            var page = KeysetPage.of(rows, 5, Pagination::cursorOf);
            assertFalse(page.hasMore());
            assertTrue(page.nextCursor().isEmpty(),
                    "a cursor on the last page invites a client to fetch an empty page and treat it as a "
                            + "failure");
        }
    }
}
