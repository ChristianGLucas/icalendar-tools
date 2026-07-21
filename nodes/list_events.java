package nodes;

import axiom.AxiomContext;
import gen.Messages.Error;
import gen.Messages.ICalEvent;
import gen.Messages.ICalEventList;
import gen.Messages.ICalListEventsInput;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.component.VEvent;

import java.util.Map;

public class ListEvents {

    /**
     * List the VEVENTs in a .ics document, optionally filtered by exact
     * (case-insensitive) category or by overlap with a [window_start, window_end)
     * instant window — leave both filters empty to list every VEVENT. The window
     * filter matches an event if ANY of its occurrences (including recurrence
     * instances expanded from RRULE/RDATE/EXDATE) fall inside the window, not just
     * its own DTSTART/DTEND. Distinct from ParseICalendar: this returns only
     * VEVENTs (never VTODO/VJOURNAL) and supports server-side filtering instead of
     * the caller filtering the full parse result themselves.
     *
     * <p>{@code ax} is the AxiomContext (ADR-001): every platform capability is
     * reached through it — {@code ax.log()}, {@code ax.secrets()},
     * {@code ax.reflection()}, {@code ax.mutation()}. Node
     * code never talks to the platform directly.
     *
     * @param ax    The AxiomContext: logging, secrets, reflection, mutation.
     * @param input The decoded ICalListEventsInput for this invocation.
     */
    public static ICalEventList listEvents(AxiomContext ax, ICalListEventsInput input) {
        ax.log().info("listEvents handling", Map.of());
        if (input.hasError()) {
            return ICalEventList.newBuilder().setError(input.getError()).build();
        }

        boolean hasStart = !input.getWindowStart().isEmpty();
        boolean hasEnd = !input.getWindowEnd().isEmpty();
        if (hasStart != hasEnd) {
            return err("INVALID_ARGUMENT", "window_start and window_end must both be set, or both left empty");
        }

        try {
            Period window = null;
            if (hasStart) {
                DateTime ws = ICal4jHelper.parseWindowInstant(input.getWindowStart());
                DateTime we = ICal4jHelper.parseWindowInstant(input.getWindowEnd());
                if (!we.after(ws)) {
                    return err("INVALID_ARGUMENT", "window_end must be strictly after window_start");
                }
                window = new Period(ws, we);
            }

            Calendar cal = ICal4jHelper.parse(input.getIcsText());
            String categoryFilter = input.getCategoryFilter();

            ICalEventList.Builder out = ICalEventList.newBuilder();
            for (Object o : cal.getComponents(Component.VEVENT)) {
                VEvent v = (VEvent) o;
                ICalEvent conv = ICal4jHelper.eventOf(v);
                if (!categoryFilter.isEmpty() && !hasCategory(conv, categoryFilter)) continue;
                if (window != null && v.calculateRecurrenceSet(window).isEmpty()) continue;
                out.addEvents(conv);
            }
            return out.build();
        } catch (ICal4jHelper.IcalException e) {
            return ICalEventList.newBuilder().setError(ICal4jHelper.toProtoError(e)).build();
        } catch (Exception e) {
            return ICalEventList.newBuilder().setError(ICal4jHelper.internalError(e)).build();
        }
    }

    private static boolean hasCategory(ICalEvent event, String categoryFilter) {
        for (String c : event.getCategoriesList()) {
            if (c.equalsIgnoreCase(categoryFilter)) return true;
        }
        return false;
    }

    private static ICalEventList err(String code, String message) {
        return ICalEventList.newBuilder()
                .setError(Error.newBuilder().setCode(code).setMessage(message).build())
                .build();
    }
}
