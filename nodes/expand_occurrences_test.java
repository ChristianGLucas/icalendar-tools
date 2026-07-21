package nodes;

import axiom.AxiomContext;
import gen.Messages.ICalEventOccurrence;
import gen.Messages.ICalExpandInput;
import gen.Messages.ICalOccurrenceList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ExpandOccurrencesTest {

    // One weekly-recurring VEVENT (RRULE FREQ=WEEKLY;COUNT=3 from 2026-07-21, a
    // Tuesday) plus one non-recurring VEVENT on 2026-08-05. The three recurring
    // occurrence dates below (07-21, 07-28, 08-04 — each exactly 7 days apart) were
    // computed BY HAND from the RRULE definition, not by running this node — the
    // independent oracle for this test.
    static final String RECURRING_ICS = String.join("\r\n",
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Test//Test//EN",
            "BEGIN:VEVENT",
            "UID:weekly-1@example.com",
            "DTSTAMP:20260701T120000Z",
            "DTSTART:20260721T090000Z",
            "DTEND:20260721T093000Z",
            "SUMMARY:Weekly Sync",
            "RRULE:FREQ=WEEKLY;COUNT=3",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "UID:single-1@example.com",
            "DTSTAMP:20260701T120000Z",
            "DTSTART:20260805T100000Z",
            "DTEND:20260805T110000Z",
            "SUMMARY:One-off Review",
            "END:VEVENT",
            "END:VCALENDAR",
            "");

    static final class TestContext implements AxiomContext {
        public Logger log() {
            return new Logger() {
                public void debug(String m, Map<String, String> a) {}
                public void info(String m, Map<String, String> a)  {}
                public void warn(String m, Map<String, String> a)  {}
                public void error(String m, Map<String, String> a) {}
            };
        }
        public Secrets secrets() { return name -> Optional.empty(); }
        public String executionId() { return "test-execution-id"; }
        public String flowId() { return "test-flow-id"; }
        public String tenantId() { return "test-tenant-id"; }
        public Reflection reflection() {
            return () -> new FlowReflection() {
                public List<ReflectionNode> nodes() { return List.of(); }
                public List<ReflectionEdge> edges() { return List.of(); }
                public List<ReflectionEdge> loopEdges() { return List.of(); }
                public FlowPosition position() { return new FlowPosition(0, 0, Map.of(), List.of()); }
                public String graphId() { return ""; }
            };
        }
        public Mutation mutation() {
            return () -> new FlowMutation() {
                public int addNode(String pkg, String ver, CanvasPosition pos) { return 0; }
                public void addEdge(int src, int dst, EdgeCondition cond) {}
            };
        }
    }

    @Test
    public void testExpandOccurrences_matchesHandComputedOccurrenceDates() {
        AxiomContext ax = new TestContext();
        ICalOccurrenceList result = ExpandOccurrences.expandOccurrences(ax, ICalExpandInput.newBuilder()
                .setIcsText(RECURRING_ICS)
                .setWindowStart("20260701T000000Z")
                .setWindowEnd("20260901T000000Z")
                .build());

        assertFalse(result.hasError(), "unexpected error: " + result.getError());
        assertFalse(result.getTruncated());
        assertEquals(4, result.getOccurrencesCount());

        List<ICalEventOccurrence> occ = result.getOccurrencesList();
        assertEquals("20260721T090000Z", occ.get(0).getOccurrenceStart());
        assertEquals("20260728T090000Z", occ.get(1).getOccurrenceStart());
        assertEquals("20260804T090000Z", occ.get(2).getOccurrenceStart());
        assertEquals("20260805T100000Z", occ.get(3).getOccurrenceStart());

        assertTrue(occ.get(0).getIsRecurring());
        assertTrue(occ.get(1).getIsRecurring());
        assertTrue(occ.get(2).getIsRecurring());
        assertFalse(occ.get(3).getIsRecurring());

        assertEquals("weekly-1@example.com", occ.get(0).getUid());
        assertEquals("single-1@example.com", occ.get(3).getUid());
        assertEquals("Weekly Sync", occ.get(0).getSummary());
    }

    @Test
    public void testExpandOccurrences_limitTruncatesToEarliestOccurrences() {
        AxiomContext ax = new TestContext();
        ICalOccurrenceList result = ExpandOccurrences.expandOccurrences(ax, ICalExpandInput.newBuilder()
                .setIcsText(RECURRING_ICS)
                .setWindowStart("20260701T000000Z")
                .setWindowEnd("20260901T000000Z")
                .setLimit(2)
                .build());
        assertFalse(result.hasError());
        assertTrue(result.getTruncated());
        assertEquals(2, result.getOccurrencesCount());
        assertEquals("20260721T090000Z", result.getOccurrences(0).getOccurrenceStart());
        assertEquals("20260728T090000Z", result.getOccurrences(1).getOccurrenceStart());
    }

    @Test
    public void testExpandOccurrences_narrowWindowExcludesOutOfRangeOccurrences() {
        AxiomContext ax = new TestContext();
        ICalOccurrenceList result = ExpandOccurrences.expandOccurrences(ax, ICalExpandInput.newBuilder()
                .setIcsText(RECURRING_ICS)
                .setWindowStart("20260722T000000Z")
                .setWindowEnd("20260730T000000Z")
                .build());
        assertFalse(result.hasError());
        assertEquals(1, result.getOccurrencesCount());
        assertEquals("20260728T090000Z", result.getOccurrences(0).getOccurrenceStart());
    }

    @Test
    public void testExpandOccurrences_invalidWindowReturnsStructuredError() {
        AxiomContext ax = new TestContext();
        ICalOccurrenceList result = ExpandOccurrences.expandOccurrences(ax, ICalExpandInput.newBuilder()
                .setIcsText(RECURRING_ICS)
                .setWindowStart("20260901T000000Z")
                .setWindowEnd("20260701T000000Z")
                .build());
        assertTrue(result.hasError());
        assertEquals("INVALID_ARGUMENT", result.getError().getCode());
    }
}
