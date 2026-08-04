package nodes;

import axiom.AxiomContext;
import gen.Messages.ICalCalendar;
import gen.Messages.ICalEvent;
import gen.Messages.ICalTextInput;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ParseICalendarTest {

    // Hand-authored RFC 5545 fixture. Every expected value below was worked out
    // BY HAND from the RFC 5545 grammar, not derived by running this package's own
    // code — that is what makes this an independent oracle rather than a
    // self-consistency check.
    static final String SAMPLE_ICS = String.join("\r\n",
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Test//Test//EN",
            "CALSCALE:GREGORIAN",
            "BEGIN:VEVENT",
            "UID:evt-1@example.com",
            "DTSTAMP:20260701T120000Z",
            "DTSTART:20260721T090000Z",
            "DTEND:20260721T100000Z",
            "SUMMARY:Team Standup",
            "DESCRIPTION:Daily sync",
            "LOCATION:Conference Room A",
            "STATUS:CONFIRMED",
            "CATEGORIES:Work,Meeting",
            "ORGANIZER;CN=Alice Manager:mailto:alice@example.com",
            "ATTENDEE;CN=Bob Dev;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:bob@example.com",
            "SEQUENCE:2",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "DESCRIPTION:Reminder",
            "TRIGGER:-PT15M",
            "END:VALARM",
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
    public void testParseICalendar_knownFieldsAgainstHandAuthoredFixture() {
        AxiomContext ax = new TestContext();
        ICalTextInput input = ICalTextInput.newBuilder().setIcsText(SAMPLE_ICS).build();
        ICalCalendar result = ParseICalendar.parseICalendar(ax, input);

        assertFalse(result.hasError(), "unexpected error: " + result.getError());
        assertEquals("-//Test//Test//EN", result.getProdid());
        assertEquals("2.0", result.getVersion());
        assertEquals(1, result.getEventsCount());

        ICalEvent e = result.getEvents(0);
        assertEquals("evt-1@example.com", e.getUid());
        assertEquals("Team Standup", e.getSummary());
        assertEquals("Daily sync", e.getDescription());
        assertEquals("Conference Room A", e.getLocation());
        assertEquals("20260721T090000Z", e.getDtstart());
        assertEquals("", e.getDtstartTzid());
        assertEquals("20260721T100000Z", e.getDtend());
        assertFalse(e.getAllDay());
        assertEquals("CONFIRMED", e.getStatus());
        assertEquals("20260701T120000Z", e.getDtstamp());
        assertEquals(2, e.getSequence());
        assertEquals(List.of("Work", "Meeting"), e.getCategoriesList());

        assertEquals("alice@example.com", e.getOrganizer().getEmail());
        assertEquals("Alice Manager", e.getOrganizer().getCommonName());

        assertEquals(1, e.getAttendeesCount());
        assertEquals("bob@example.com", e.getAttendees(0).getEmail());
        assertEquals("Bob Dev", e.getAttendees(0).getCommonName());
        assertEquals("REQ-PARTICIPANT", e.getAttendees(0).getRole());
        assertEquals("ACCEPTED", e.getAttendees(0).getParticipationStatus());
        assertTrue(e.getAttendees(0).getRsvp());

        assertEquals(1, e.getAlarmsCount());
        assertEquals("DISPLAY", e.getAlarms(0).getAction());
        assertEquals("Reminder", e.getAlarms(0).getDescription());
        assertEquals("-PT15M", e.getAlarms(0).getTrigger());
    }

    @Test
    public void testParseICalendar_allDayEvent() {
        String ics = String.join("\r\n",
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//Test//Test//EN",
                "BEGIN:VEVENT",
                "UID:allday-1@example.com",
                "DTSTAMP:20260701T120000Z",
                "DTSTART;VALUE=DATE:20261225",
                "SUMMARY:Christmas Day",
                "END:VEVENT",
                "END:VCALENDAR",
                "");
        AxiomContext ax = new TestContext();
        ICalCalendar result = ParseICalendar.parseICalendar(ax, ICalTextInput.newBuilder().setIcsText(ics).build());
        assertFalse(result.hasError());
        assertEquals(1, result.getEventsCount());
        ICalEvent e = result.getEvents(0);
        assertEquals("20261225", e.getDtstart());
        assertTrue(e.getAllDay());
    }

    @Test
    public void testParseICalendar_malformedInputReturnsStructuredErrorNotCrash() {
        AxiomContext ax = new TestContext();
        ICalTextInput input = ICalTextInput.newBuilder().setIcsText("this is not iCalendar at all").build();
        ICalCalendar result = ParseICalendar.parseICalendar(ax, input);
        assertNotNull(result);
        assertTrue(result.hasError());
        assertEquals("INVALID_ICS", result.getError().getCode());
    }

    @Test
    public void testParseICalendar_largeMalformedInputReturnsStructuredErrorNotCrash() {
        AxiomContext ax = new TestContext();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < (2 * 1024 * 1024) + 1; i++) huge.append('x');
        ICalTextInput input = ICalTextInput.newBuilder().setIcsText(huge.toString()).build();
        ICalCalendar result = ParseICalendar.parseICalendar(ax, input);
        assertNotNull(result);
        assertTrue(result.hasError());
        assertEquals("INVALID_ICS", result.getError().getCode());
    }

    @Test
    public void testParseICalendar_propagatesUpstreamError() {
        AxiomContext ax = new TestContext();
        gen.Messages.Error upstream = gen.Messages.Error.newBuilder()
                .setCode("INVALID_ARGUMENT").setMessage("upstream failed").build();
        ICalTextInput input = ICalTextInput.newBuilder().setError(upstream).build();
        ICalCalendar result = ParseICalendar.parseICalendar(ax, input);
        assertTrue(result.hasError());
        assertEquals("INVALID_ARGUMENT", result.getError().getCode());
        assertEquals("upstream failed", result.getError().getMessage());
    }
}
