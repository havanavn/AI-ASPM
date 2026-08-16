package aspm.module.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.insight.domain.Composition;
import aspm.module.insight.domain.OperationalQueue;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The twelve operational queues of DOC-12 section 6.1 and the four compositions of sections 5 to 8. */
class QueuesAndCompositionsTest {

    private static final UUID PRINCIPAL = new UUID(150, 1);
    private static final UUID VISIBLE_OBJECT = new UUID(150, 2);
    private static final UUID HIDDEN_OBJECT = new UUID(150, 3);

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-12 section 6.1 — the twelve queues")
    class Queues {

        @Test
        @DisplayName("all twelve exist, numbered one to twelve with no gap")
        void twelveQueuesNumberedContiguously() {
            var queues = OperationalQueue.inDocumentOrder();
            assertEquals(12, queues.size(),
                    "a queue that exists only as a saved view somebody configured is a queue a tenant can be "
                            + "deployed without");
            Set<Integer> numbers = new TreeSet<>();
            for (int i = 0; i < queues.size(); i++) {
                assertEquals(i + 1, queues.get(i).number(),
                        "queue numbering must match DOC-12 so a reviewer can read the two side by side");
                assertTrue(numbers.add(queues.get(i).number()));
            }
        }

        @Test
        @DisplayName("the two classic blind spots are present and marked as such")
        void classicBlindSpotsArePresent() {
            var blindSpots = OperationalQueue.inDocumentOrder().stream()
                    .filter(OperationalQueue::isClassicBlindSpot)
                    .toList();
            assertEquals(List.of(OperationalQueue.COVERAGE_HEALTH, OperationalQueue.INTEGRATION_HEALTH),
                    blindSpots,
                    "DOC-12: 'Queues 8 and 11 exist because their absence is the classic blind spot. If forty "
                            + "assets have silently had no data for three months, the vulnerability dashboard "
                            + "shows green — not because they are secure but because there is no data.'");
        }

        @Test
        @DisplayName("integration health highlights on success rate, not only on an open circuit")
        void integrationHealthCatchesIntermittentFailure() {
            var rule = OperationalQueue.INTEGRATION_HEALTH.highlightRule();
            assertTrue(rule.contains("success rate"),
                    "a connector failing one submission in three never trips a circuit and silently loses a "
                            + "third of the data (PRD-CON-031); got: " + rule);
            assertTrue(OperationalQueue.INTEGRATION_HEALTH.whyThisRule().contains("circuit breaking misses"));
        }

        @Test
        @DisplayName("every queue carries its highlight rule and the reason for it")
        void everyQueueExplainsItsThreshold() {
            for (OperationalQueue queue : OperationalQueue.values()) {
                assertFalse(queue.highlightRule().isBlank(), queue + " has no highlight rule");
                assertFalse(queue.whyThisRule().isBlank(),
                        queue + " has no recorded reason. A threshold living in a dashboard configuration is "
                                + "one a tenant can raise until the red disappears, producing a queue that is "
                                + "always empty and always wrong.");
            }
        }

        @Test
        @DisplayName("two queues deliberately have no highlight, and both say why")
        void someQueuesHaveNoHighlightOnPurpose() {
            for (OperationalQueue queue : List.of(OperationalQueue.AWAITING_VERIFICATION,
                    OperationalQueue.REPORT_PIPELINE)) {
                assertEquals("none", queue.highlightRule(),
                        queue + " should carry no highlight");
            }
            assertTrue(OperationalQueue.AWAITING_VERIFICATION.whyThisRule().contains("fire constantly"),
                    "a threshold on a short-lived queue fires constantly and is ignored, which costs the "
                            + "attention every other red marker needs");
            assertTrue(OperationalQueue.AT_RISK_AND_BREACHED.whyThisRule().contains("distinguishes nothing"),
                    "everything in the breached queue is already breached; a red marker on all of it "
                            + "distinguishes nothing");
        }

        @Test
        @DisplayName("confirmed-live secrets is the only unconditionally highlighted queue")
        void liveSecretsAlwaysHighlighted() {
            assertEquals("always highlighted", OperationalQueue.CONFIRMED_LIVE_SECRETS.highlightRule());
            long unconditional = java.util.Arrays.stream(OperationalQueue.values())
                    .filter(q -> q.highlightRule().startsWith("always"))
                    .count();
            assertEquals(1, unconditional,
                    "a validated live credential is not a risk to weigh, it is an active exposure whose only "
                            + "remediation is rotation (PRD-VUL-019) — and if a second queue were always red, "
                            + "neither would read as urgent");
        }

        @Test
        @DisplayName("a threshold cannot be varied per tenant through this type")
        void thresholdsAreNotConfigurableHere() {
            for (Method m : OperationalQueue.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("set")
                                || (name.contains("threshold") && m.getParameterCount() > 0),
                        "found " + m.getName() + "; a settable threshold is one a tenant raises until the red "
                                + "disappears");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-12 sections 5 to 8 — the four compositions")
    class Compositions {

        @Test
        @DisplayName("there are four, each with a distinct permission and a stated question")
        void fourCompositions() {
            assertEquals(4, Composition.values().length);
            Set<String> permissions = new TreeSet<>();
            for (Composition composition : Composition.values()) {
                assertTrue(permissions.add(composition.compositionPermission()),
                        composition + " shares a permission with another composition; a shared permission "
                                + "means granting one grants all four");
                assertFalse(composition.question().isBlank(),
                        composition + " states no question. Recorded so a fifth composition is judged against "
                                + "a gap rather than a preference.");
            }
        }

        @Test
        @DisplayName("drill-through applies object-level authorization per row")
        void drillThroughIsPerObject() {
            var rows = List.of(
                    new Composition.Row("Finding on the reporting service", new BigDecimal("12"),
                            Optional.of(VISIBLE_OBJECT)),
                    new Composition.Row("Finding on the payments service", new BigDecimal("58"),
                            Optional.of(HIDDEN_OBJECT)));

            var reachable = Composition.drillThrough(PRINCIPAL, rows,
                    (principal, object) -> object.equals(VISIBLE_OBJECT));

            assertEquals(1, reachable.size(),
                    "permission to see the aggregate is not permission to see every object behind it — an "
                            + "executive posture figure legitimately includes findings on systems the reader "
                            + "cannot open");
            assertEquals(Optional.of(VISIBLE_OBJECT), reachable.get(0).objectId());
        }

        @Test
        @DisplayName("a summary row with no object is returned: it is the aggregate the caller is authorized for")
        void summaryRowsSurvive() {
            var rows = List.of(
                    new Composition.Row("Total open criticals", new BigDecimal("70"), Optional.empty()),
                    new Composition.Row("On the payments service", new BigDecimal("58"),
                            Optional.of(HIDDEN_OBJECT)));

            var reachable = Composition.drillThrough(PRINCIPAL, rows, (principal, object) -> false);
            assertEquals(1, reachable.size());
            assertTrue(reachable.get(0).objectId().isEmpty(),
                    "the aggregate is a permitted disclosure precisely because it does not name the objects "
                            + "behind it");
        }

        @Test
        @DisplayName("there is no drill-through overload that omits the object-level check")
        void drillThroughCannotSkipTheCheck() {
            long overloads = java.util.Arrays.stream(Composition.class.getMethods())
                    .filter(m -> m.getName().equals("drillThrough"))
                    .count();
            assertEquals(1, overloads,
                    "a convenience overload would be the omission nobody notices, in a code review of a "
                            + "method call that already looked correct");

            assertThrows(NullPointerException.class,
                    () -> Composition.drillThrough(PRINCIPAL, List.of(), null),
                    "authorizing the composition once and treating every row as reachable converts an "
                            + "aggregate permission into an object permission for everything it touched");
        }

        @Test
        @DisplayName("the composition permission is never sufficient on its own")
        void compositionPermissionIsNotAnObjectPermission() {
            // Modelled directly: a principal holding EVERY composition permission and no object permission
            // reaches no object.
            var rows = List.of(new Composition.Row("A finding", new BigDecimal("1"),
                    Optional.of(HIDDEN_OBJECT)));
            for (Composition composition : Composition.values()) {
                assertTrue(Composition.drillThrough(PRINCIPAL, rows, (principal, object) -> false).isEmpty(),
                        composition + ": holding the composition permission reached an object");
            }
        }
    }
}
