package nodes;

import axiom.AxiomContext;
import gen.Messages.ICalEventList;
import gen.Messages.ICalListEventsInput;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ListEventsTest {

    static final String TWO_EVENT_ICS = String.join("\r\n",
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Test//Test//EN",
            "BEGIN:VEVENT",
            "UID:work-1@example.com",
            "DTSTAMP:20260701T120000Z",
            "DTSTART:20260721T090000Z",
            "DTEND:20260721T100000Z",
            "SUMMARY:Work Meeting",
            "CATEGORIES:Work",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "UID:personal-1@example.com",
            "DTSTAMP:20260701T120000Z",
            "DTSTART:20260801T090000Z",
            "DTEND:20260801T100000Z",
            "SUMMARY:Dentist",
            "CATEGORIES:Personal",
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
                public int addNode(String pkg, String ver, String node, CanvasPosition pos) { return 0; }
                public void addEdge(int src, int dst, EdgeCondition cond) {}
            };
        }
    }

    @Test
    public void testListEvents_noFiltersReturnsAll() {
        AxiomContext ax = new TestContext();
        ICalEventList result = ListEvents.listEvents(ax, ICalListEventsInput.newBuilder().setIcsText(TWO_EVENT_ICS).build());
        assertFalse(result.hasError());
        assertEquals(2, result.getEventsCount());
    }

    @Test
    public void testListEvents_categoryFilterNarrowsToOne() {
        AxiomContext ax = new TestContext();
        ICalEventList result = ListEvents.listEvents(ax,
                ICalListEventsInput.newBuilder().setIcsText(TWO_EVENT_ICS).setCategoryFilter("work").build());
        assertFalse(result.hasError());
        assertEquals(1, result.getEventsCount());
        assertEquals("work-1@example.com", result.getEvents(0).getUid());
    }

    @Test
    public void testListEvents_windowFilterNarrowsToOverlappingEvent() {
        AxiomContext ax = new TestContext();
        ICalEventList result = ListEvents.listEvents(ax, ICalListEventsInput.newBuilder()
                .setIcsText(TWO_EVENT_ICS)
                .setWindowStart("20260701T000000Z")
                .setWindowEnd("20260731T235959Z")
                .build());
        assertFalse(result.hasError());
        assertEquals(1, result.getEventsCount());
        assertEquals("work-1@example.com", result.getEvents(0).getUid());
    }

    @Test
    public void testListEvents_combinedFiltersCanReturnEmpty() {
        AxiomContext ax = new TestContext();
        ICalEventList result = ListEvents.listEvents(ax, ICalListEventsInput.newBuilder()
                .setIcsText(TWO_EVENT_ICS)
                .setCategoryFilter("Personal")
                .setWindowStart("20260701T000000Z")
                .setWindowEnd("20260731T235959Z")
                .build());
        assertFalse(result.hasError());
        assertEquals(0, result.getEventsCount());
    }

    @Test
    public void testListEvents_onlyOneWindowBoundSetIsInvalidArgument() {
        AxiomContext ax = new TestContext();
        ICalEventList result = ListEvents.listEvents(ax, ICalListEventsInput.newBuilder()
                .setIcsText(TWO_EVENT_ICS)
                .setWindowStart("20260701T000000Z")
                .build());
        assertTrue(result.hasError());
        assertEquals("INVALID_ARGUMENT", result.getError().getCode());
    }
}
