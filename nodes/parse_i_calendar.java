package nodes;

import axiom.AxiomContext;
import gen.Messages.ICalCalendar;
import gen.Messages.ICalTextInput;
import net.fortuna.ical4j.model.Calendar;

import java.util.Map;

public class ParseICalendar {

    /**
     * Parse a .ics VCALENDAR document into a structured ICalCalendar: every VEVENT,
     * VTODO, and VJOURNAL with its fields (summary, start/end, location,
     * organizer/attendees, status, UID, RRULE string, VALARMs, categories, ...), plus
     * PRODID/VERSION/CALSCALE/METHOD. Malformed input (not iCalendar at all, or too
     * large) returns a structured error rather than crashing; individual malformed
     * components are preserved as-is (e.g. a missing UID comes back as an empty uid
     * field) rather than silently dropped, so downstream nodes see exactly what the
     * file contained. Input capped at 2 MiB.
     *
     * <p>{@code ax} is the AxiomContext (ADR-001): every platform capability is
     * reached through it — {@code ax.log()}, {@code ax.secrets()},
     * {@code ax.reflection()}, {@code ax.mutation()}. Node
     * code never talks to the platform directly.
     *
     * @param ax    The AxiomContext: logging, secrets, reflection, mutation.
     * @param input The decoded ICalTextInput for this invocation.
     */
    public static ICalCalendar parseICalendar(AxiomContext ax, ICalTextInput input) {
        ax.log().info("parseICalendar handling", Map.of());
        if (input.hasError()) {
            return ICalCalendar.newBuilder().setError(input.getError()).build();
        }
        try {
            Calendar cal = ICal4jHelper.parse(input.getIcsText());
            return ICal4jHelper.calendarOf(cal);
        } catch (ICal4jHelper.IcalException e) {
            return ICalCalendar.newBuilder().setError(ICal4jHelper.toProtoError(e)).build();
        } catch (Exception e) {
            return ICalCalendar.newBuilder().setError(ICal4jHelper.internalError(e)).build();
        }
    }
}
