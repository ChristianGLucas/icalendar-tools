package nodes;

import axiom.AxiomContext;
import gen.Messages.ICalAlarm;
import gen.Messages.ICalAttendee;
import gen.Messages.ICalCalendar;
import gen.Messages.ICalEvent;
import gen.Messages.ICalTextInput;
import gen.Messages.ICalTextOutput;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BuildICalendarTest {

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

    private static ICalCalendar sampleCalendar() {
        ICalEvent event = ICalEvent.newBuilder()
                .setUid("evt-1@example.com")
                .setSummary("Team Standup")
                .setDescription("Daily sync")
                .setLocation("Conference Room A")
                .setDtstart("20260721T090000Z")
                .setDtend("20260721T100000Z")
                .setStatus("CONFIRMED")
                .addCategories("Work")
                .addCategories("Meeting")
                .setOrganizer(ICalAttendee.newBuilder().setEmail("alice@example.com").setCommonName("Alice Manager").build())
                .setSequence(2)
                .build();
        return ICalCalendar.newBuilder()
                .setProdid("-//Test//Test//EN")
                .setVersion("2.0")
                .addEvents(event)
                .build();
    }

    @Test
    public void testBuildICalendar_knownFieldsProduceExpectedIcsLines() {
        AxiomContext ax = new TestContext();
        ICalTextOutput result = BuildICalendar.buildICalendar(ax, sampleCalendar());

        assertFalse(result.hasError(), "unexpected error: " + result.getError());
        String ics = result.getIcsText();
        // These are the exact RFC 5545 lines ical4j is expected to emit for the
        // fields set above — worked out by hand from the spec, independent of
        // whatever this package's parser would report back.
        assertTrue(ics.contains("BEGIN:VCALENDAR"), ics);
        assertTrue(ics.contains("VERSION:2.0"), ics);
        assertTrue(ics.contains("UID:evt-1@example.com"), ics);
        assertTrue(ics.contains("SUMMARY:Team Standup"), ics);
        assertTrue(ics.contains("DTSTART:20260721T090000Z"), ics);
        assertTrue(ics.contains("DTEND:20260721T100000Z"), ics);
        assertTrue(ics.contains("STATUS:CONFIRMED"), ics);
        assertTrue(ics.contains("SEQUENCE:2"), ics);
        assertTrue(ics.contains("mailto:alice@example.com"), ics);
        assertTrue(ics.contains("CN=Alice Manager"), ics);
        assertTrue(ics.contains("BEGIN:VEVENT") && ics.contains("END:VEVENT"), ics);
    }

    @Test
    public void testBuildICalendar_alarmIsActuallyAttachedToOutput() {
        // Regression test: ical4j's VEvent wires its VALARM children in at
        // construction time — mutating getAlarms() after the fact silently drops
        // them. A first pass of this node did exactly that and produced .ics text
        // with no VALARM block at all despite the caller supplying one.
        AxiomContext ax = new TestContext();
        ICalEvent event = ICalEvent.newBuilder()
                .setUid("evt-alarm@example.com")
                .setSummary("With Alarm")
                .setDtstart("20260721T090000Z")
                .addAlarms(ICalAlarm.newBuilder()
                        .setAction("DISPLAY")
                        .setDescription("Reminder")
                        .setTrigger("-PT15M")
                        .build())
                .build();
        ICalCalendar cal = ICalCalendar.newBuilder().addEvents(event).build();

        ICalTextOutput result = BuildICalendar.buildICalendar(ax, cal);
        assertFalse(result.hasError(), "unexpected error: " + result.getError());
        String ics = result.getIcsText();
        assertTrue(ics.contains("BEGIN:VALARM"), ics);
        assertTrue(ics.contains("END:VALARM"), ics);
        assertTrue(ics.contains("ACTION:DISPLAY"), ics);
        assertTrue(ics.contains("TRIGGER:-PT15M"), ics);

        // And round-trip it through our own parser to confirm the alarm survives.
        ICalCalendar reparsed = ParseICalendar.parseICalendar(ax, ICalTextInput.newBuilder().setIcsText(ics).build());
        assertFalse(reparsed.hasError());
        assertEquals(1, reparsed.getEvents(0).getAlarmsCount());
        assertEquals("DISPLAY", reparsed.getEvents(0).getAlarms(0).getAction());
        assertEquals("-PT15M", reparsed.getEvents(0).getAlarms(0).getTrigger());
    }

    @Test
    public void testBuildICalendar_omittedDtstampIsDeterministicNotWallClock() {
        // Regression test: a first pass silently filled DTSTAMP with the current
        // wall-clock time when the caller left it empty, breaking this node's
        // documented determinism guarantee. Two calls, one second apart, must
        // produce byte-identical output.
        AxiomContext ax = new TestContext();
        ICalCalendar cal = sampleCalendar(); // sampleCalendar() sets no dtstamp
        assertEquals("", cal.getEvents(0).getDtstamp());

        ICalTextOutput first = BuildICalendar.buildICalendar(ax, cal);
        ICalTextOutput second = BuildICalendar.buildICalendar(ax, cal);
        assertFalse(first.hasError());
        assertFalse(second.hasError());
        assertEquals(first.getIcsText(), second.getIcsText());
        assertFalse(first.getIcsText().contains("DTSTAMP"), first.getIcsText());
    }

    @Test
    public void testBuildICalendar_alarmPlusExplicitDtstampProducesExactlyOneDtstamp() {
        // Regression test (independent review, round 1): reconstructing a VEvent via
        // the (PropertyList, ComponentList) constructor to attach VALARM children
        // re-triggers ical4j's own wall-clock DTSTAMP auto-population IN ADDITION TO
        // whatever DTSTAMP the caller supplied — a first pass emitted two DTSTAMP
        // lines whenever an event had both an alarm and a caller-supplied dtstamp,
        // which is invalid RFC 5545 (DTSTAMP MUST NOT occur more than once) and a
        // combination any real Parse -> modify -> Build workflow hits, since
        // ParseICalendar always returns the source's dtstamp.
        AxiomContext ax = new TestContext();
        ICalEvent event = ICalEvent.newBuilder()
                .setUid("evt-dup@example.com")
                .setSummary("Has Alarm And Dtstamp")
                .setDtstart("20260801T100000Z")
                .setDtstamp("20260715T080000Z")
                .addAlarms(ICalAlarm.newBuilder().setAction("DISPLAY").setDescription("Reminder").setTrigger("-PT15M").build())
                .build();
        ICalCalendar cal = ICalCalendar.newBuilder().addEvents(event).build();

        ICalTextOutput result = BuildICalendar.buildICalendar(ax, cal);
        assertFalse(result.hasError(), "unexpected error: " + result.getError());
        String ics = result.getIcsText();
        int dtstampCount = ics.split("DTSTAMP", -1).length - 1;
        assertEquals(1, dtstampCount, "expected exactly one DTSTAMP line, got:\n" + ics);
        assertTrue(ics.contains("DTSTAMP:20260715T080000Z"), ics);

        // The output must itself be spec-valid — feed it through our own validator.
        gen.Messages.ICalValidationResult validation = ValidateICalendar.validateICalendar(ax,
                ICalTextInput.newBuilder().setIcsText(ics).build());
        assertTrue(validation.getValid(), "built .ics failed validation: " + validation.getErrorsList());
    }

    @Test
    public void testBuildICalendar_tzidAnchoredTimeIsNotShiftedByServerDefaultZone() {
        // Regression test (independent review, round 1): parsing the wall-clock
        // digits with the JVM default zone and THEN calling setTimeZone(tz)
        // preserves the absolute instant and re-renders it in tz — it does not
        // reinterpret the digits as belonging to tz. A first pass did exactly that
        // and silently shifted a TZID-anchored DTSTART by the offset delta between
        // the server's default zone and the caller's requested zone (e.g. 14:00
        // New York became 16:00 on a Denver-zoned build host — a 2-hour silent
        // corruption with no error).
        AxiomContext ax = new TestContext();
        ICalEvent event = ICalEvent.newBuilder()
                .setUid("evt-tz@example.com")
                .setSummary("TZ Test")
                .setDtstart("20260601T140000")
                .setDtstartTzid("America/New_York")
                .setDtstamp("20260601T100000Z")
                .build();
        ICalCalendar cal = ICalCalendar.newBuilder().addEvents(event).build();

        ICalTextOutput result = BuildICalendar.buildICalendar(ax, cal);
        assertFalse(result.hasError(), "unexpected error: " + result.getError());
        String ics = result.getIcsText();
        // The literal wall-clock digits the caller supplied MUST survive unchanged.
        assertTrue(ics.contains("TZID=America/New_York:20260601T140000"), ics);

        ICalCalendar reparsed = ParseICalendar.parseICalendar(ax, ICalTextInput.newBuilder().setIcsText(ics).build());
        assertFalse(reparsed.hasError());
        assertEquals("20260601T140000", reparsed.getEvents(0).getDtstart());
        assertEquals("America/New_York", reparsed.getEvents(0).getDtstartTzid());
    }

    @Test
    public void testBuildICalendar_roundTripsThroughParse() {
        AxiomContext ax = new TestContext();
        ICalCalendar original = sampleCalendar();
        ICalTextOutput built = BuildICalendar.buildICalendar(ax, original);
        assertFalse(built.hasError());

        ICalCalendar reparsed = ParseICalendar.parseICalendar(ax, ICalTextInput.newBuilder().setIcsText(built.getIcsText()).build());
        assertFalse(reparsed.hasError());
        assertEquals(1, reparsed.getEventsCount());
        ICalEvent e = reparsed.getEvents(0);
        assertEquals(original.getEvents(0).getUid(), e.getUid());
        assertEquals(original.getEvents(0).getSummary(), e.getSummary());
        assertEquals(original.getEvents(0).getDtstart(), e.getDtstart());
        assertEquals(original.getEvents(0).getDtend(), e.getDtend());
        assertEquals(original.getEvents(0).getStatus(), e.getStatus());
        assertEquals(original.getEvents(0).getCategoriesList(), e.getCategoriesList());
        assertEquals(original.getEvents(0).getOrganizer().getEmail(), e.getOrganizer().getEmail());
        assertEquals(original.getEvents(0).getSequence(), e.getSequence());
    }

    @Test
    public void testBuildICalendar_emptyCalendarReturnsStructuredError() {
        AxiomContext ax = new TestContext();
        ICalTextOutput result = BuildICalendar.buildICalendar(ax, ICalCalendar.newBuilder().build());
        assertTrue(result.hasError());
        assertEquals("INVALID_ARGUMENT", result.getError().getCode());
        assertEquals("", result.getIcsText());
    }

    @Test
    public void testBuildICalendar_missingUidReturnsStructuredError() {
        AxiomContext ax = new TestContext();
        ICalEvent noUid = ICalEvent.newBuilder().setSummary("No UID here").build();
        ICalCalendar cal = ICalCalendar.newBuilder().addEvents(noUid).build();
        ICalTextOutput result = BuildICalendar.buildICalendar(ax, cal);
        assertTrue(result.hasError());
        assertEquals("INVALID_ARGUMENT", result.getError().getCode());
    }
}
