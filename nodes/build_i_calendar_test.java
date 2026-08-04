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
                public int addNode(String pkg, String ver, String node, CanvasPosition pos) { return 0; }
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

    // A calendar whose recurring series has ONE instance moved via RECURRENCE-ID —
    // what Google Calendar and Outlook emit when someone drags a single occurrence.
    static final String OVERRIDE_ICS = String.join("\r\n",
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Test//Test//EN",
            "BEGIN:VEVENT",
            "UID:rt-1@example.com",
            "DTSTAMP:20260801T000000Z",
            "DTSTART:20260810T090000Z",
            "DTEND:20260810T100000Z",
            "RRULE:FREQ=DAILY;COUNT=3",
            "SUMMARY:Daily standup",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "UID:rt-1@example.com",
            "RECURRENCE-ID:20260811T090000Z",
            "DTSTAMP:20260801T000000Z",
            "DTSTART:20260811T140000Z",
            "DTEND:20260811T150000Z",
            "SUMMARY:Daily standup (moved)",
            "END:VEVENT",
            "END:VCALENDAR",
            "");

    @Test
    public void testRoundTripPreservesRecurrenceIdSoTheOverrideStaysAnOverride() {
        // This is a CORRECTNESS test, not a fidelity nicety. RECURRENCE-ID is what
        // makes the second VEVENT REPLACE the master's 11 Aug instance instead of
        // adding to it. Drop it on the round-trip and the rebuilt calendar has two
        // independent meetings where the user has one — i.e. Parse+Build would
        // MANUFACTURE exactly the phantom occurrence ExpandOccurrences removes.
        AxiomContext ax = new TestContext();

        ICalCalendar parsed = ParseICalendar.parseICalendar(ax,
                ICalTextInput.newBuilder().setIcsText(OVERRIDE_ICS).build());
        assertFalse(parsed.hasError(), "unexpected parse error: " + parsed.getError());
        assertEquals(2, parsed.getEventsCount());

        // ParseICalendar must SURFACE it — before 0.1.3 the field did not exist, so a
        // consumer could not even tell which VEVENT was the override.
        ICalEvent master = parsed.getEvents(0);
        ICalEvent override = parsed.getEvents(1);
        assertEquals("", master.getRecurrenceId(), "the master series carries no RECURRENCE-ID");
        assertEquals("20260811T090000Z", override.getRecurrenceId(),
                "the override must report which instant it replaces");

        // BuildICalendar must EMIT it back.
        ICalTextOutput built = BuildICalendar.buildICalendar(ax, parsed);
        assertFalse(built.hasError(), "unexpected build error: " + built.getError());
        String unfolded = built.getIcsText().replace("\r\n ", "");
        assertTrue(unfolded.contains("RECURRENCE-ID"),
                "the rebuilt .ics dropped RECURRENCE-ID, silently turning one edited meeting into two");

        // ...and the rebuilt document must still parse back to the same override.
        ICalCalendar reparsed = ParseICalendar.parseICalendar(ax,
                ICalTextInput.newBuilder().setIcsText(built.getIcsText()).build());
        assertFalse(reparsed.hasError(), "unexpected reparse error: " + reparsed.getError());
        assertEquals("20260811T090000Z", reparsed.getEvents(1).getRecurrenceId(),
                "Parse(Build(x)) must round-trip RECURRENCE-ID");

        // The end-to-end proof: expanding the REBUILT calendar must give the same
        // three occurrences as expanding the original — no phantom at 11 Aug 09:00.
        gen.Messages.ICalOccurrenceList expanded = ExpandOccurrences.expandOccurrences(ax,
                gen.Messages.ICalExpandInput.newBuilder()
                        .setIcsText(built.getIcsText())
                        .setWindowStart("20260810T000000Z")
                        .setWindowEnd("20260813T000000Z")
                        .build());
        assertFalse(expanded.hasError(), "unexpected expand error: " + expanded.getError());
        List<String> starts = new java.util.ArrayList<>();
        for (gen.Messages.ICalEventOccurrence o : expanded.getOccurrencesList()) {
            starts.add(o.getOccurrenceStart());
        }
        assertEquals(List.of("20260810T090000Z", "20260811T140000Z", "20260812T090000Z"), starts,
                "expanding the REBUILT calendar must not resurrect the vacated 11 Aug 09:00 slot");
    }

    @Test
    public void testRoundTripPreservesRecurrenceIdRangeAndTzid() {
        AxiomContext ax = new TestContext();
        String src = String.join("\r\n",
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN",
                "BEGIN:VEVENT", "UID:rt-2@example.com", "DTSTAMP:20260801T000000Z",
                "DTSTART;TZID=America/New_York:20260810T090000",
                "DTEND;TZID=America/New_York:20260810T100000",
                "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:NY standup", "END:VEVENT",
                "BEGIN:VEVENT", "UID:rt-2@example.com",
                "RECURRENCE-ID;RANGE=THISANDFUTURE;TZID=America/New_York:20260811T090000",
                "DTSTAMP:20260801T000000Z",
                "DTSTART;TZID=America/New_York:20260811T110000",
                "DTEND;TZID=America/New_York:20260811T120000",
                "SUMMARY:NY standup (new time)", "END:VEVENT",
                "END:VCALENDAR", "");

        ICalCalendar parsed = ParseICalendar.parseICalendar(ax,
                ICalTextInput.newBuilder().setIcsText(src).build());
        assertFalse(parsed.hasError(), "unexpected parse error: " + parsed.getError());
        ICalEvent ov = parsed.getEvents(1);
        // Same convention as dtstart/dtstart_tzid: the raw RFC 5545 value, with the
        // zone carried alongside it rather than folded into the string.
        assertEquals("20260811T090000", ov.getRecurrenceId(),
                "the RECURRENCE-ID value is reported verbatim");
        assertEquals("America/New_York", ov.getRecurrenceIdTzid(),
                "losing the TZID would re-anchor the replaced instant to the wrong zone, "
                        + "so the subtraction would miss and the phantom would return");
        assertEquals("THISANDFUTURE", ov.getRecurrenceIdRange(),
                "RANGE decides whether the override governs LATER instances too - losing it "
                        + "would silently narrow the edit to a single occurrence");

        ICalTextOutput built = BuildICalendar.buildICalendar(ax, parsed);
        assertFalse(built.hasError(), "unexpected build error: " + built.getError());
        String unfolded = built.getIcsText().replace("\r\n ", "");
        assertTrue(unfolded.contains("RANGE=THISANDFUTURE"),
                "RANGE=THISANDFUTURE must survive the rebuild");

        ICalCalendar reparsed = ParseICalendar.parseICalendar(ax,
                ICalTextInput.newBuilder().setIcsText(built.getIcsText()).build());
        assertEquals("THISANDFUTURE", reparsed.getEvents(1).getRecurrenceIdRange());
        assertEquals(ov.getRecurrenceId(), reparsed.getEvents(1).getRecurrenceId(),
                "the replaced instant must survive the rebuild unchanged");
        assertEquals("America/New_York", reparsed.getEvents(1).getRecurrenceIdTzid(),
                "the TZID must survive the rebuild too");

        // End-to-end: the REBUILT calendar must expand identically to the original,
        // THISANDFUTURE shift and all. 2026-08 is EDT (UTC-4): 09:00 NY == 13:00Z,
        // 11:00 NY == 15:00Z. Hand-computed truth for [10 Aug, 13 Aug):
        //   10 Aug 13:00Z (untouched) | 11 Aug 15:00Z | 12 Aug 15:00Z
        gen.Messages.ICalOccurrenceList a = ExpandOccurrences.expandOccurrences(ax,
                gen.Messages.ICalExpandInput.newBuilder().setIcsText(src)
                        .setWindowStart("20260810T000000Z").setWindowEnd("20260813T000000Z").build());
        gen.Messages.ICalOccurrenceList b = ExpandOccurrences.expandOccurrences(ax,
                gen.Messages.ICalExpandInput.newBuilder().setIcsText(built.getIcsText())
                        .setWindowStart("20260810T000000Z").setWindowEnd("20260813T000000Z").build());
        assertEquals(3, a.getOccurrencesCount(),
                "the THISANDFUTURE override replaces the 11 Aug instance, it does not add a 4th");
        assertEquals(a.getOccurrencesList(), b.getOccurrencesList(),
                "a rebuilt calendar must expand to exactly the same occurrences as the original");
    }
}
