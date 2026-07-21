package nodes;

import axiom.AxiomContext;
import gen.Messages.Error;
import gen.Messages.ICalEvent;
import gen.Messages.ICalEventOccurrence;
import gen.Messages.ICalExpandInput;
import gen.Messages.ICalOccurrenceList;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.PeriodList;
import net.fortuna.ical4j.model.component.VEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ExpandOccurrences {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 5000;
    // A sane ceiling on window span: bounds calculateRecurrenceSet's work per event
    // without having to reimplement RRULE/RDATE/EXDATE merging ourselves.
    private static final long MAX_WINDOW_MILLIS = 50L * 365 * 24 * 3600 * 1000;

    /**
     * Expand every VEVENT in a .ics document — combining its RRULE, RDATE, and
     * EXDATE together per RFC 5545 §3.8.5, and passing non-recurring VEVENTs through
     * unchanged — into concrete occurrences that fall inside a caller-supplied
     * [window_start, window_end) instant window, earliest first. Complementary to
     * christiangeorgelucas/recurrence-tools (which expands one bare RRULE string you
     * supply yourself): this operates on every recurring VEVENT already inside a
     * whole calendar file at once. Capped at `limit` occurrences total (default 500,
     * max 5000); a dense window flags truncated=true instead of running unbounded.
     *
     * <p>{@code ax} is the AxiomContext (ADR-001): every platform capability is
     * reached through it — {@code ax.log()}, {@code ax.secrets()},
     * {@code ax.reflection()}, {@code ax.mutation()}. Node
     * code never talks to the platform directly.
     *
     * @param ax    The AxiomContext: logging, secrets, reflection, mutation.
     * @param input The decoded ICalExpandInput for this invocation.
     */
    public static ICalOccurrenceList expandOccurrences(AxiomContext ax, ICalExpandInput input) {
        ax.log().info("expandOccurrences handling", Map.of());
        if (input.hasError()) {
            return ICalOccurrenceList.newBuilder().setError(input.getError()).build();
        }

        int limit = input.getLimit() == 0 ? DEFAULT_LIMIT : input.getLimit();
        if (limit < 0 || limit > MAX_LIMIT) {
            return err("INVALID_ARGUMENT", "limit must be between 1 and " + MAX_LIMIT);
        }

        try {
            DateTime ws = ICal4jHelper.parseWindowInstant(input.getWindowStart());
            DateTime we = ICal4jHelper.parseWindowInstant(input.getWindowEnd());
            if (!we.after(ws)) {
                return err("INVALID_ARGUMENT", "window_end must be strictly after window_start");
            }
            if (we.getTime() - ws.getTime() > MAX_WINDOW_MILLIS) {
                return err("INVALID_ARGUMENT", "window spans more than 50 years");
            }

            Calendar cal = ICal4jHelper.parse(input.getIcsText());
            Period period = new Period(ws, we);

            List<Candidate> candidates = new ArrayList<>();
            for (Object o : cal.getComponents(Component.VEVENT)) {
                VEvent v = (VEvent) o;
                ICalEvent conv = ICal4jHelper.eventOf(v);
                boolean recurring = !conv.getRrule().isEmpty() || conv.getRdateCount() > 0;
                PeriodList periods = v.calculateRecurrenceSet(period);
                for (Object po : periods) {
                    Period p = (Period) po;
                    ICalEventOccurrence.Builder ob = ICalEventOccurrence.newBuilder()
                            .setUid(conv.getUid())
                            .setSummary(conv.getSummary())
                            .setLocation(conv.getLocation())
                            .setOccurrenceStart(String.valueOf(p.getStart()))
                            .setIsRecurring(recurring);
                    if (p.getEnd() != null) {
                        ob.setOccurrenceEnd(String.valueOf(p.getEnd()));
                    }
                    candidates.add(new Candidate(p.getStart().getTime(), ob.build()));
                }
            }
            candidates.sort(Comparator.comparingLong(c -> c.epochMillis));

            List<ICalEventOccurrence> result = new ArrayList<>();
            boolean truncated = false;
            for (Candidate c : candidates) {
                if (result.size() >= limit) {
                    truncated = true;
                    break;
                }
                result.add(c.occurrence);
            }
            return ICalOccurrenceList.newBuilder().addAllOccurrences(result).setTruncated(truncated).build();
        } catch (ICal4jHelper.IcalException e) {
            return ICalOccurrenceList.newBuilder().setError(ICal4jHelper.toProtoError(e)).build();
        } catch (Exception e) {
            return ICalOccurrenceList.newBuilder().setError(ICal4jHelper.internalError(e)).build();
        }
    }

    private static final class Candidate {
        final long epochMillis;
        final ICalEventOccurrence occurrence;

        Candidate(long epochMillis, ICalEventOccurrence occurrence) {
            this.epochMillis = epochMillis;
            this.occurrence = occurrence;
        }
    }

    private static ICalOccurrenceList err(String code, String message) {
        return ICalOccurrenceList.newBuilder()
                .setError(Error.newBuilder().setCode(code).setMessage(message).build())
                .build();
    }
}
