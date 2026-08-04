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
                public int addNode(String pkg, String ver, String node, CanvasPosition pos) { return 0; }
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

    // ====================================================================
    // RFC 5545 §3.8.4.4 RECURRENCE-ID overrides.
    //
    // Regression suite for the 0.1.2 defect: the master RRULE was expanded
    // WITHOUT subtracting overridden instants, so a moved or cancelled
    // instance left a PHANTOM occupying its original slot. Downstream, the
    // published christiangeorgelucas/calendar-slot-finder flow answered
    // "no slot is free" for a window in which the phantom was the only thing
    // blocking it (executions ab1fafbe… wrong vs ce3ae211… control).
    //
    // Every expected occurrence set below is hand-computed from the RRULE and
    // the override's RECURRENCE-ID, and each assertion names the REASON
    // (which instant, which flags) rather than only the count — a count-only
    // assertion passes for the wrong reason as soon as one phantom is traded
    // for one dropped real occurrence.
    // ====================================================================

    /** Builds a VCALENDAR from raw lines, CRLF-folded as RFC 5545 requires. */
    private static String ics(String... lines) {
        StringBuilder sb = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n");
        for (String l : lines) sb.append(l).append("\r\n");
        return sb.append("END:VCALENDAR\r\n").toString();
    }

    private static ICalOccurrenceList expand(String icsText, String from, String to) {
        return ExpandOccurrences.expandOccurrences(new TestContext(), ICalExpandInput.newBuilder()
                .setIcsText(icsText).setWindowStart(from).setWindowEnd(to).build());
    }

    private static ICalOccurrenceList expandIncludingCancelled(String icsText, String from, String to) {
        return ExpandOccurrences.expandOccurrences(new TestContext(), ICalExpandInput.newBuilder()
                .setIcsText(icsText).setWindowStart(from).setWindowEnd(to)
                .setIncludeCancelled(true).build());
    }

    private static List<String> startsOf(ICalOccurrenceList r) {
        List<String> out = new java.util.ArrayList<>();
        for (ICalEventOccurrence o : r.getOccurrencesList()) out.add(o.getOccurrenceStart());
        return out;
    }

    // A daily standup 09:00-10:00Z on 2026-08-10, -11, -12, whose FIRST instance the
    // owner dragged to 10:15-11:15. Hand-computed truth for [08-10, 08-13):
    //   10 Aug 10:15Z (the moved instance)  |  11 Aug 09:00Z  |  12 Aug 09:00Z
    // The 10 Aug 09:00Z slot is VACATED and must not appear.
    static final String MOVED_ICS = ics(
            "BEGIN:VEVENT", "UID:rec1", "DTSTAMP:20260801T000000Z",
            "DTSTART:20260810T090000Z", "DTEND:20260810T100000Z",
            "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:Daily standup", "END:VEVENT",
            "BEGIN:VEVENT", "UID:rec1", "RECURRENCE-ID:20260810T090000Z", "DTSTAMP:20260801T000000Z",
            "DTSTART:20260810T101500Z", "DTEND:20260810T111500Z",
            "SUMMARY:Daily standup (moved)", "END:VEVENT");

    @Test
    public void testMovedInstance_vacatesOriginalSlotAndReportsOnlyTheNewOne() {
        ICalOccurrenceList r = expand(MOVED_ICS, "20260810T000000Z", "20260813T000000Z");
        assertFalse(r.hasError(), "unexpected error: " + r.getError());

        // THE defect: 0.1.2 returned four, including a phantom at 20260810T090000Z.
        assertEquals(List.of("20260810T101500Z", "20260811T090000Z", "20260812T090000Z"), startsOf(r),
                "the master's 10 Aug 09:00 instance was replaced by the override and must be subtracted");

        ICalEventOccurrence moved = r.getOccurrences(0);
        assertTrue(moved.getIsOverride(), "the moved instance must be flagged as an override");
        assertEquals("20260810T090000Z", moved.getRecurrenceId(),
                "recurrence_id must name the VACATED slot, so a consumer can tell the 09:00 hour is now free");
        assertEquals("Daily standup (moved)", moved.getSummary());
        assertEquals("20260810T111500Z", moved.getOccurrenceEnd());
        assertTrue(moved.getIsRecurring(), "an override instance still belongs to a recurring series");

        // The untouched instances must stay plain: no override flags leaking across.
        assertFalse(r.getOccurrences(1).getIsOverride());
        assertEquals("", r.getOccurrences(1).getRecurrenceId());
        assertEquals("Daily standup", r.getOccurrences(1).getSummary());
    }

    @Test
    public void testMovedInstance_narrowWindowOnTheVacatedSlotIsEmpty() {
        // The precise shape that made calendar-slot-finder refuse a free hour:
        // ask only about 09:00-10:00, the slot the owner moved OUT of.
        ICalOccurrenceList r = expand(MOVED_ICS, "20260810T090000Z", "20260810T100000Z");
        assertFalse(r.hasError(), "unexpected error: " + r.getError());
        assertEquals(List.of(), startsOf(r), "the vacated 09:00-10:00 hour is FREE, not busy");
    }

    @Test
    public void testCancelledInstance_isExcludedByDefaultAndVacatesItsSlot() {
        String cancelled = ics(
                "BEGIN:VEVENT", "UID:rec1", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000Z", "DTEND:20260810T100000Z",
                "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:Daily standup", "END:VEVENT",
                "BEGIN:VEVENT", "UID:rec1", "RECURRENCE-ID:20260810T090000Z", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000Z", "DTEND:20260810T100000Z",
                "STATUS:CANCELLED", "SUMMARY:Daily standup", "END:VEVENT");

        ICalOccurrenceList r = expand(cancelled, "20260810T000000Z", "20260813T000000Z");
        assertFalse(r.hasError(), "unexpected error: " + r.getError());
        assertEquals(List.of("20260811T090000Z", "20260812T090000Z"), startsOf(r),
                "a cancelled instance does not happen: neither the master's slot nor the override may be reported");

        // ...but the information is recoverable, not destroyed.
        ICalOccurrenceList withCancelled =
                expandIncludingCancelled(cancelled, "20260810T000000Z", "20260813T000000Z");
        assertEquals(List.of("20260810T090000Z", "20260811T090000Z", "20260812T090000Z"),
                startsOf(withCancelled));
        assertEquals("CANCELLED", withCancelled.getOccurrences(0).getStatus());
        assertTrue(withCancelled.getOccurrences(0).getIsOverride());
        assertEquals("20260810T090000Z", withCancelled.getOccurrences(0).getRecurrenceId());
    }

    @Test
    public void testWholeSeriesCancelled_isExcludedByDefault() {
        String s = ics(
                "BEGIN:VEVENT", "UID:dead", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000Z", "DTEND:20260810T100000Z",
                "RRULE:FREQ=DAILY;COUNT=3", "STATUS:CANCELLED", "SUMMARY:Cancelled series", "END:VEVENT",
                "BEGIN:VEVENT", "UID:live", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260811T140000Z", "DTEND:20260811T150000Z",
                "STATUS:CONFIRMED", "SUMMARY:Real meeting", "END:VEVENT");
        ICalOccurrenceList r = expand(s, "20260810T000000Z", "20260813T000000Z");
        assertEquals(List.of("20260811T140000Z"), startsOf(r),
                "an entirely cancelled series contributes no busy time");
        assertEquals("CONFIRMED", r.getOccurrences(0).getStatus(),
                "status must be surfaced so a consumer can distinguish CONFIRMED from TENTATIVE");

        assertEquals(4, expandIncludingCancelled(s, "20260810T000000Z", "20260813T000000Z")
                .getOccurrencesCount(), "include_cancelled restores the whole cancelled series");
    }

    @Test
    public void testOverrideMovedOutsideWindow_stillVacatesTheOriginalSlotInsideIt() {
        // The instance is dragged from 10 Aug 09:00 to 20 Aug 09:00 — far outside the
        // queried window. The override VEVENT is therefore invisible to a window-scoped
        // scan, but the slot it vacated is INSIDE the window and must be freed anyway.
        String s = ics(
                "BEGIN:VEVENT", "UID:rec1", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000Z", "DTEND:20260810T100000Z",
                "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:Daily standup", "END:VEVENT",
                "BEGIN:VEVENT", "UID:rec1", "RECURRENCE-ID:20260810T090000Z", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260820T090000Z", "DTEND:20260820T100000Z",
                "SUMMARY:Daily standup (postponed)", "END:VEVENT");

        assertEquals(List.of("20260811T090000Z", "20260812T090000Z"),
                startsOf(expand(s, "20260810T000000Z", "20260813T000000Z")),
                "an override that relocates an instance out of the window must still vacate its slot");

        // ...and the relocated instance is reported when the window is moved to meet it.
        ICalOccurrenceList later = expand(s, "20260819T000000Z", "20260821T000000Z");
        assertEquals(List.of("20260820T090000Z"), startsOf(later));
        assertTrue(later.getOccurrences(0).getIsOverride());
        assertEquals("20260810T090000Z", later.getOccurrences(0).getRecurrenceId());
    }

    @Test
    public void testThisAndFuture_shiftsThisInstanceAndEveryLaterOne() {
        // RANGE=THISANDFUTURE (RFC 5545 §3.2.13): from 11 Aug on, the standup moves
        // 09:00 -> 11:00 (a +2h delta) and is renamed. Hand-computed truth for
        // [08-10, 08-14):  10 Aug 09:00 (untouched) | 11 Aug 11:00 | 12 Aug 11:00 | 13 Aug 11:00
        String s = ics(
                "BEGIN:VEVENT", "UID:rec1", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000Z", "DTEND:20260810T100000Z",
                "RRULE:FREQ=DAILY;COUNT=4", "SUMMARY:Daily standup", "END:VEVENT",
                "BEGIN:VEVENT", "UID:rec1", "RECURRENCE-ID;RANGE=THISANDFUTURE:20260811T090000Z",
                "DTSTAMP:20260801T000000Z",
                "DTSTART:20260811T110000Z", "DTEND:20260811T120000Z",
                "SUMMARY:Daily standup (new time)", "END:VEVENT");

        ICalOccurrenceList r = expand(s, "20260810T000000Z", "20260814T000000Z");
        assertFalse(r.hasError(), "unexpected error: " + r.getError());
        assertEquals(List.of("20260810T090000Z", "20260811T110000Z", "20260812T110000Z", "20260813T110000Z"),
                startsOf(r), "THISANDFUTURE shifts its own instance AND every later one by the same delta");

        assertFalse(r.getOccurrences(0).getIsOverride(), "the instance BEFORE the override is untouched");
        assertEquals("Daily standup", r.getOccurrences(0).getSummary());

        assertTrue(r.getOccurrences(1).getIsOverride());
        assertEquals("Daily standup (new time)", r.getOccurrences(1).getSummary());

        // The propagated (not explicitly authored) instances carry the override's
        // properties and name the master slot they displaced.
        assertTrue(r.getOccurrences(2).getIsOverride());
        assertEquals("Daily standup (new time)", r.getOccurrences(2).getSummary());
        assertEquals("20260812T090000Z", r.getOccurrences(2).getRecurrenceId());
        assertEquals("20260812T120000Z", r.getOccurrences(2).getOccurrenceEnd());
    }

    @Test
    public void testThisAndFuture_shiftAcrossTheWindowEdgeIsNotLost() {
        // The 13 Aug 09:00 master instance sits OUTSIDE a window ending 13 Aug 10:00
        // only after being shifted; before the shift it is inside. Conversely a -3h
        // shift can pull an instance INTO the window. Both directions must be
        // filtered on the SHIFTED instant, not the master's.
        String s = ics(
                "BEGIN:VEVENT", "UID:rec1", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000Z", "DTEND:20260810T093000Z",
                "RRULE:FREQ=DAILY;COUNT=4", "SUMMARY:Standup", "END:VEVENT",
                "BEGIN:VEVENT", "UID:rec1", "RECURRENCE-ID;RANGE=THISANDFUTURE:20260811T090000Z",
                "DTSTAMP:20260801T000000Z",
                "DTSTART:20260811T230000Z", "DTEND:20260811T233000Z",
                "SUMMARY:Standup (late)", "END:VEVENT");

        // +14h shift. Window [13 Aug 00:00, 13 Aug 12:00): the master's 13 Aug 09:00
        // instance shifts to 13 Aug 23:00 and leaves the window; the 12 Aug instance
        // shifts to 12 Aug 23:00 and is also out. Nothing should be reported.
        assertEquals(List.of(), startsOf(expand(s, "20260813T000000Z", "20260813T120000Z")),
                "an instance shifted OUT of the window must not be reported at its unshifted time");

        // Window [13 Aug 12:00, 14 Aug 00:00) catches the shifted 13 Aug instance,
        // whose UNSHIFTED time (09:00) lies outside this window entirely.
        ICalOccurrenceList r = expand(s, "20260813T120000Z", "20260814T000000Z");
        assertEquals(List.of("20260813T230000Z"), startsOf(r),
                "an instance shifted INTO the window must be found even though its master slot is outside");
        assertEquals("20260813T090000Z", r.getOccurrences(0).getRecurrenceId());
    }

    @Test
    public void testTzidRecurrenceIdMatchesUtcRenderedMasterInstance() {
        // Google/Outlook write TZID-anchored recurring events. The RECURRENCE-ID is
        // then a LOCAL wall-clock value while the expanded master instance resolves to
        // an absolute instant — matching on the instant, not the literal digits, is
        // what makes the subtraction work here. 2026-08-10 is EDT (UTC-4), so
        // 09:00 New York == 13:00Z.
        String s = ics(
                "BEGIN:VEVENT", "UID:tz1", "DTSTAMP:20260801T000000Z",
                "DTSTART;TZID=America/New_York:20260810T090000",
                "DTEND;TZID=America/New_York:20260810T100000",
                "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:NY standup", "END:VEVENT",
                "BEGIN:VEVENT", "UID:tz1", "RECURRENCE-ID;TZID=America/New_York:20260810T090000",
                "DTSTAMP:20260801T000000Z",
                "DTSTART;TZID=America/New_York:20260810T140000",
                "DTEND;TZID=America/New_York:20260810T150000",
                "SUMMARY:NY standup (moved)", "END:VEVENT");

        ICalOccurrenceList r = expand(s, "20260810T000000Z", "20260811T000000Z");
        assertFalse(r.hasError(), "unexpected error: " + r.getError());
        assertEquals(1, r.getOccurrencesCount(),
                "the TZID-form override must subtract the master's 13:00Z instance, not sit beside it");
        assertTrue(r.getOccurrences(0).getIsOverride());
        assertEquals("NY standup (moved)", r.getOccurrences(0).getSummary());
    }

    @Test
    public void testAllDayRecurrenceIdOverride() {
        // All-day (VALUE=DATE) recurring event with one day moved. DATE values carry
        // no instant, so the subtraction falls back to literal-value matching.
        String s = ics(
                "BEGIN:VEVENT", "UID:ad1", "DTSTAMP:20260801T000000Z",
                "DTSTART;VALUE=DATE:20260810", "DTEND;VALUE=DATE:20260811",
                "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:All-day block", "END:VEVENT",
                "BEGIN:VEVENT", "UID:ad1", "RECURRENCE-ID;VALUE=DATE:20260811", "DTSTAMP:20260801T000000Z",
                "DTSTART;VALUE=DATE:20260814", "DTEND;VALUE=DATE:20260815",
                "SUMMARY:All-day block (moved)", "END:VEVENT");

        ICalOccurrenceList r = expand(s, "20260810T000000Z", "20260813T000000Z");
        assertFalse(r.hasError(), "unexpected error: " + r.getError());
        // ical4j materializes an all-day (VALUE=DATE) occurrence as UTC midnight — the
        // rendering 0.1.2 already produced (verified against the deployed 0.1.2 on
        // Google's real all-day holiday calendar), so it is pinned here unchanged.
        assertEquals(List.of("20260810T000000Z", "20260812T000000Z"), startsOf(r),
                "the 11 Aug all-day instance was moved to 14 Aug and must be subtracted from 11 Aug");
    }

    @Test
    public void testUtcFormRecurrenceIdMatchesTzidAnchoredMasterInstance() {
        // Outlook and several CalDAV servers anchor the master series to a TZID but
        // write the RECURRENCE-ID as a UTC instant. The two sides then share no
        // literal digits at all, so ONLY instant-based matching subtracts correctly.
        // 2026-08-10 is EDT (UTC-4): 09:00 New York == 13:00Z.
        String s = ics(
                "BEGIN:VEVENT", "UID:tz2", "DTSTAMP:20260801T000000Z",
                "DTSTART;TZID=America/New_York:20260810T090000",
                "DTEND;TZID=America/New_York:20260810T100000",
                "RRULE:FREQ=DAILY;COUNT=2", "SUMMARY:NY standup", "END:VEVENT",
                "BEGIN:VEVENT", "UID:tz2", "RECURRENCE-ID:20260810T130000Z", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T180000Z", "DTEND:20260810T190000Z",
                "SUMMARY:NY standup (moved)", "END:VEVENT");

        ICalOccurrenceList r = expand(s, "20260810T000000Z", "20260811T000000Z");
        assertFalse(r.hasError(), "unexpected error: " + r.getError());
        assertEquals(1, r.getOccurrencesCount(),
                "a UTC-form RECURRENCE-ID must subtract the TZID-anchored master instance at the same INSTANT");
        assertEquals("NY standup (moved)", r.getOccurrences(0).getSummary());
        assertTrue(r.getOccurrences(0).getIsOverride());
    }

    @Test
    public void testFloatingTimeRecurrenceIdOverride() {
        // RFC 5545 floating time: neither side carries a zone. Both resolve against
        // the same reference, so the instants agree and the subtraction holds.
        String s = ics(
                "BEGIN:VEVENT", "UID:fl1", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000", "DTEND:20260810T100000",
                "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:Floating standup", "END:VEVENT",
                "BEGIN:VEVENT", "UID:fl1", "RECURRENCE-ID:20260811T090000", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260811T140000", "DTEND:20260811T150000",
                "SUMMARY:Floating standup (moved)", "END:VEVENT");

        ICalOccurrenceList r = expand(s, "20260810T000000Z", "20260813T000000Z");
        assertFalse(r.hasError(), "unexpected error: " + r.getError());
        assertEquals(3, r.getOccurrencesCount(),
                "the moved floating instance must replace, not duplicate, the 11 Aug slot");
        long overrides = r.getOccurrencesList().stream().filter(ICalEventOccurrence::getIsOverride).count();
        assertEquals(1, overrides, "exactly one occurrence may be the override");
        for (ICalEventOccurrence o : r.getOccurrencesList()) {
            assertFalse(o.getOccurrenceStart().startsWith("20260811T09"),
                    "the vacated 11 Aug 09:00 floating slot must not be reported");
        }
    }

    @Test
    public void testThisAndFutureShiftPreservesFullDurationOfALongOccurrence() {
        // The shifted path re-derives start AND end itself. ical4j does NOT clip a
        // returned period to the query window (verified against the deployed 0.1.2:
        // a 6-day event queried with a 1-day window inside it comes back with its
        // true 20260810->20260816 bounds). If it ever did, shifting a clipped bound
        // would silently corrupt the instant — so the full duration is pinned here.
        String s = ics(
                "BEGIN:VEVENT", "UID:long1", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T000000Z", "DTEND:20260812T000000Z",
                "RRULE:FREQ=WEEKLY;COUNT=2", "SUMMARY:Long block", "END:VEVENT",
                "BEGIN:VEVENT", "UID:long1", "RECURRENCE-ID;RANGE=THISANDFUTURE:20260817T000000Z",
                "DTSTAMP:20260801T000000Z",
                "DTSTART:20260817T060000Z", "DTEND:20260819T060000Z",
                "SUMMARY:Long block (shifted)", "END:VEVENT");

        // Query a 1-hour window sitting INSIDE the shifted second occurrence.
        ICalOccurrenceList r = expand(s, "20260818T000000Z", "20260818T010000Z");
        assertEquals(List.of("20260817T060000Z"), startsOf(r),
                "a multi-day occurrence overlapping the window is returned with its TRUE start");
        assertEquals("20260819T060000Z", r.getOccurrences(0).getOccurrenceEnd(),
                "the +6h shift must move the end by the same delta, preserving the 2-day duration");
    }

    @Test
    public void testPaddedExpansionUsesTheSameWindowBoundarySemanticsAsTheNormalPath() {
        // When a RANGE=THISANDFUTURE override exists, the master is expanded over a
        // PADDED window and re-filtered by this node's own overlap predicate instead
        // of ical4j's. Those two filters must agree, or the same calendar answers
        // differently depending on whether an unrelated later instance was edited.
        //
        // The window filter is half-open at BOTH ends: an occurrence ending exactly
        // at window_start does NOT overlap, and one starting exactly at window_end
        // does NOT either. Pinned here on the PADDED path.
        String withTaf = ics(
                "BEGIN:VEVENT", "UID:b1", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000Z", "DTEND:20260810T100000Z",
                "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:Boundary", "END:VEVENT",
                "BEGIN:VEVENT", "UID:b1", "RECURRENCE-ID;RANGE=THISANDFUTURE:20260812T090000Z",
                "DTSTAMP:20260801T000000Z",
                "DTSTART:20260812T190000Z", "DTEND:20260812T200000Z",
                "SUMMARY:Boundary (moved)", "END:VEVENT");
        String withoutTaf = ics(
                "BEGIN:VEVENT", "UID:b1", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000Z", "DTEND:20260810T100000Z",
                "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:Boundary", "END:VEVENT");

        // Window starts exactly when the 10 Aug instance ENDS. ical4j's filter is
        // CLOSED at both ends for a RECURRING event, so that instance IS returned.
        // (It is half-OPEN for a non-recurring VEVENT — an ical4j inconsistency that
        // predates this node. What is pinned here is that the padded path does not
        // introduce a THIRD behaviour.)
        List<String> padded = startsOf(expand(withTaf, "20260810T100000Z", "20260812T000000Z"));
        List<String> unpadded = startsOf(expand(withoutTaf, "20260810T100000Z", "20260812T000000Z"));
        assertEquals(List.of("20260810T090000Z", "20260811T090000Z"), unpadded,
                "recurring occurrences touching window_start are included (ical4j path)");
        assertEquals(unpadded, padded,
                "the padded path must apply the SAME boundary rule as the normal path — the "
                        + "answer must not change just because an unrelated later instance was edited");

        // ...and the mirror case: window ends exactly when the 11 Aug instance STARTS.
        List<String> padded2 = startsOf(expand(withTaf, "20260810T120000Z", "20260811T090000Z"));
        List<String> unpadded2 = startsOf(expand(withoutTaf, "20260810T120000Z", "20260811T090000Z"));
        assertEquals(List.of("20260811T090000Z"), unpadded2,
                "recurring occurrences touching window_end are included (ical4j path)");
        assertEquals(unpadded2, padded2,
                "the padded path must agree at the closing edge too");
    }

    @Test
    public void testMultipleOverridesOnOneSeries() {
        // Two separate instances edited; both must be subtracted, and each override
        // must attach to its OWN recurrence_id rather than the first one found.
        String s = ics(
                "BEGIN:VEVENT", "UID:rec1", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000Z", "DTEND:20260810T100000Z",
                "RRULE:FREQ=DAILY;COUNT=4", "SUMMARY:Standup", "END:VEVENT",
                "BEGIN:VEVENT", "UID:rec1", "RECURRENCE-ID:20260810T090000Z", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T160000Z", "DTEND:20260810T170000Z", "SUMMARY:Standup A", "END:VEVENT",
                "BEGIN:VEVENT", "UID:rec1", "RECURRENCE-ID:20260812T090000Z", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260812T170000Z", "DTEND:20260812T180000Z", "SUMMARY:Standup B", "END:VEVENT");

        ICalOccurrenceList r = expand(s, "20260810T000000Z", "20260814T000000Z");
        assertEquals(List.of("20260810T160000Z", "20260811T090000Z", "20260812T170000Z", "20260813T090000Z"),
                startsOf(r));
        assertEquals("20260810T090000Z", r.getOccurrences(0).getRecurrenceId());
        assertEquals("Standup A", r.getOccurrences(0).getSummary());
        assertFalse(r.getOccurrences(1).getIsOverride());
        assertEquals("20260812T090000Z", r.getOccurrences(2).getRecurrenceId());
        assertEquals("Standup B", r.getOccurrences(2).getSummary());
    }

    @Test
    public void testOverrideOnOneUidDoesNotSuppressAnotherUidAtTheSameInstant() {
        // The override index is keyed by UID. A same-instant meeting in a DIFFERENT
        // series must survive — otherwise the fix would trade a phantom for a
        // silently DROPPED real meeting, which is the worse failure.
        String s = ics(
                "BEGIN:VEVENT", "UID:seriesA", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000Z", "DTEND:20260810T100000Z",
                "RRULE:FREQ=DAILY;COUNT=2", "SUMMARY:A", "END:VEVENT",
                "BEGIN:VEVENT", "UID:seriesA", "RECURRENCE-ID:20260810T090000Z", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T150000Z", "DTEND:20260810T160000Z", "SUMMARY:A moved", "END:VEVENT",
                "BEGIN:VEVENT", "UID:seriesB", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T090000Z", "DTEND:20260810T100000Z", "SUMMARY:B", "END:VEVENT");

        ICalOccurrenceList r = expand(s, "20260810T000000Z", "20260811T000000Z");
        assertEquals(List.of("20260810T090000Z", "20260810T150000Z"), startsOf(r));
        assertEquals("seriesB", r.getOccurrences(0).getUid(),
                "series B's real 09:00 meeting must survive series A's override at the same instant");
        assertFalse(r.getOccurrences(0).getIsOverride());
    }

    @Test
    public void testNonRecurringOverrideStyleEventIsUnaffected() {
        // A lone VEVENT carrying a RECURRENCE-ID but with no master in the file (a
        // common fragment of a scheduling message) must still be reported — dropping
        // it because its master is absent would lose a real meeting.
        String s = ics(
                "BEGIN:VEVENT", "UID:orphan", "RECURRENCE-ID:20260810T090000Z", "DTSTAMP:20260801T000000Z",
                "DTSTART:20260810T101500Z", "DTEND:20260810T111500Z", "SUMMARY:Orphan override", "END:VEVENT");
        ICalOccurrenceList r = expand(s, "20260810T000000Z", "20260811T000000Z");
        assertEquals(List.of("20260810T101500Z"), startsOf(r));
        assertTrue(r.getOccurrences(0).getIsOverride());
        assertEquals("20260810T090000Z", r.getOccurrences(0).getRecurrenceId());
    }

    // ---- real-world exporter output ------------------------------------------
    //
    // Verbatim excerpt of the "Holidays in United States" calendar published by
    // Google (real_google_usa.ics in the sibling calendar-conflict-check lane) —
    // exporter output, not hand-rolled: VALUE=DATE all-day events, folded
    // DESCRIPTION lines, escaped commas, X- properties and STATUS:CONFIRMED. It
    // carries no RECURRENCE-ID, which is exactly what makes it the no-regression
    // oracle: the override machinery must be completely inert here.

    static final String REAL_GOOGLE_EXCERPT = String.join("\r\n",
            "BEGIN:VCALENDAR",
            "PRODID:-//Google Inc//Google Calendar 70.9054//EN",
            "VERSION:2.0",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "X-WR-CALNAME:Holidays in United States",
            "X-WR-TIMEZONE:UTC",
            "X-WR-CALDESC:Holidays and Observances in United States",
            "BEGIN:VEVENT",
            "DTSTART;VALUE=DATE:20210118",
            "DTEND;VALUE=DATE:20210119",
            "DTSTAMP:20260804T102314Z",
            "UID:20210118_09lrg7ebq0id94snlvledsknmc@google.com",
            "CLASS:PUBLIC",
            "CREATED:20240603T101345Z",
            "DESCRIPTION:Public holiday",
            "LAST-MODIFIED:20240603T101345Z",
            "SEQUENCE:0",
            "STATUS:CONFIRMED",
            "SUMMARY:Martin Luther King Jr. Day",
            "TRANSP:TRANSPARENT",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;VALUE=DATE:20210314",
            "DTEND;VALUE=DATE:20210315",
            "DTSTAMP:20260804T102314Z",
            "UID:20210314_eut7bu0kn9gl5458v3anfjb1hc@google.com",
            "CLASS:PUBLIC",
            "CREATED:20240603T101345Z",
            "DESCRIPTION:Observance\\nTo hide observances\\, go to Google Calendar Setting",
            " s > Holidays in United States",
            "LAST-MODIFIED:20240603T101345Z",
            "SEQUENCE:0",
            "STATUS:CONFIRMED",
            "SUMMARY:Daylight Saving Time starts",
            "TRANSP:TRANSPARENT",
            "END:VEVENT",
            "END:VCALENDAR",
            "");

    @Test
    public void testRealGoogleExportIsUnaffectedByTheOverrideFix() {
        ICalOccurrenceList r = expand(REAL_GOOGLE_EXCERPT, "20210101T000000Z", "20220101T000000Z");
        assertFalse(r.hasError(), "unexpected error: " + r.getError());
        assertEquals(List.of("20210118T000000Z", "20210314T000000Z"), startsOf(r),
                "a real exporter calendar with no RECURRENCE-ID must expand exactly as before");
        for (ICalEventOccurrence o : r.getOccurrencesList()) {
            assertFalse(o.getIsOverride(), "no override flag may appear on a calendar with no overrides");
            assertEquals("", o.getRecurrenceId());
            assertEquals("CONFIRMED", o.getStatus());
            assertFalse(o.getIsRecurring());
        }
        assertEquals("Martin Luther King Jr. Day", r.getOccurrences(0).getSummary());
        assertEquals("Daylight Saving Time starts", r.getOccurrences(1).getSummary(),
                "the folded DESCRIPTION line must not have corrupted the following properties");
    }
}
