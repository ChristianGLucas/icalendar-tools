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
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.RecurrenceId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExpandOccurrences {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 5000;
    // A sane ceiling on window span: bounds calculateRecurrenceSet's work per event
    // without having to reimplement RRULE/RDATE/EXDATE merging ourselves.
    private static final long MAX_WINDOW_MILLIS = 50L * 365 * 24 * 3600 * 1000;
    // A RANGE=THISANDFUTURE override shifts every later instance by (its DTSTART -
    // its RECURRENCE-ID). To catch instances that shift ACROSS a window edge, the
    // master is expanded over a window padded by that delta — so the PADDING has to
    // be bounded, or a hostile or broken calendar could widen the expansion without
    // limit. The bound applies to the padding ONLY, never to the shift itself: this
    // is a cost ceiling, not a correctness one.
    private static final long MAX_PAD_MILLIS = 366L * 24 * 3600 * 1000;

    private static final String CANCELLED = "CANCELLED";

    /**
     * Expand every VEVENT in a .ics document — combining its RRULE, RDATE, and
     * EXDATE together per RFC 5545 §3.8.5, honouring RECURRENCE-ID overrides per
     * §3.8.4.4, and passing non-recurring VEVENTs through unchanged — into concrete
     * occurrences that fall inside a caller-supplied [window_start, window_end)
     * instant window, earliest first.
     *
     * <p><b>RECURRENCE-ID overrides.</b> When a calendar owner edits ONE instance of
     * a recurring series (the thing Google Calendar and Outlook emit every time
     * someone drags a single meeting), the file carries a second VEVENT with the same
     * UID plus a RECURRENCE-ID naming the instant it replaces. That override
     * <em>replaces</em> the master's instance — it does not add to it. So the master
     * series is expanded with every overridden instant SUBTRACTED, and the override
     * VEVENT supplies that instance instead, carrying {@code recurrence_id} (the
     * vacated slot), {@code is_override=true}, and its own {@code status}. Reporting
     * both would double-count one real meeting and, worse, report the vacated slot as
     * busy — refusing a meeting time the owner actually freed.
     *
     * <p>Subtraction is keyed off the WHOLE calendar, not the window, so an override
     * that moves an instance OUT of the requested window still vacates its original
     * slot inside it. A cancelled instance (STATUS:CANCELLED) likewise vacates its
     * slot and, by default, contributes nothing — see {@code include_cancelled}.
     *
     * <p>{@code RANGE=THISANDFUTURE} is honoured too: the override governs its own
     * instance and every later one, which are shifted by the override's
     * (DTSTART − RECURRENCE-ID) delta and take its summary/location/status — and
     * its DURATION, which RFC 5545 §3.8.4.4 propagates alongside the reschedule
     * ("if the duration of the given recurrence instance is modified, then all
     * subsequen[t] instances are also modified to have this same duration").
     *
     * <p>Capped at {@code limit} occurrences total (default 500, max 5000); a dense
     * window flags truncated=true instead of running unbounded.
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
            boolean includeCancelled = input.getIncludeCancelled();

            // ---- pass 1: index every RECURRENCE-ID override, calendar-wide --------
            // Calendar-wide, NOT window-wide: an override that relocates an instance
            // outside the window must still vacate its original slot inside it.
            List<VEvent> masters = new ArrayList<>();
            Map<String, List<Override>> overridesByUid = new HashMap<>();
            for (Object o : cal.getComponents(Component.VEVENT)) {
                VEvent v = (VEvent) o;
                Override ov = overrideOf(v);
                if (ov == null) {
                    masters.add(v);
                } else {
                    overridesByUid.computeIfAbsent(ov.uid, k -> new ArrayList<>()).add(ov);
                }
            }
            // Latest-first, so "the THISANDFUTURE override governing instant T" is the
            // first one at or before T.
            for (List<Override> l : overridesByUid.values()) {
                l.sort(Comparator.comparingLong((Override x) -> x.recurrenceIdMillis).reversed());
            }

            List<Candidate> candidates = new ArrayList<>();

            // ---- pass 2a: the override VEVENTs themselves ------------------------
            for (List<Override> l : overridesByUid.values()) {
                for (Override ov : l) {
                    if (!includeCancelled && CANCELLED.equalsIgnoreCase(ov.status)) {
                        continue; // cancelled instance: slot already vacated, nothing happens here
                    }
                    // Filtered with THIS node's predicate rather than by handing the window
                    // to calculateRecurrenceSet. An override VEVENT is a NON-recurring
                    // component, and ical4j applies a half-open window rule to those but a
                    // closed one to recurring components — so delegating here would make an
                    // authored override and its own RANGE=THISANDFUTURE siblings disagree at
                    // the window edges, inside a single series. Everything belonging to a
                    // recurring series uses one rule: the recurring one.
                    long start = ov.startMillis;
                    long end = ov.hasDuration ? start + ov.durationMillis : start;
                    if (!overlapsWindow(start, end, ws, we)) {
                        continue;
                    }
                    candidates.add(new Candidate(start, ICalEventOccurrence.newBuilder()
                            .setUid(ov.uid)
                            .setSummary(ov.summary)
                            .setLocation(ov.location)
                            .setOccurrenceStart(ov.startValue)
                            .setOccurrenceEnd(ov.endValue)
                            .setIsRecurring(true)
                            .setRecurrenceId(ov.recurrenceIdValue)
                            .setIsOverride(true)
                            .setStatus(ov.status)
                            .build()));
                }
            }

            // ---- pass 2b: the master series, with overridden instants subtracted --
            for (VEvent v : masters) {
                ICalEvent conv = ICal4jHelper.eventOf(v);
                if (!includeCancelled && CANCELLED.equalsIgnoreCase(conv.getStatus())) {
                    continue; // the whole series is cancelled
                }
                boolean recurring = !conv.getRrule().isEmpty() || conv.getRdateCount() > 0;
                List<Override> overrides = overridesByUid.getOrDefault(conv.getUid(), List.of());

                // A THISANDFUTURE override shifts later instances, possibly across a
                // window edge in either direction — so expand over a padded window and
                // re-filter after shifting. Zero padding when no such override exists,
                // which is the overwhelmingly common case.
                //
                // The pad is a COST bound and is capped; the shift itself is a
                // CORRECTNESS value and never is. Clamping the shift instead would
                // silently report the vacated slots as busy — reintroducing the exact
                // phantom this node exists to remove — so an implausibly distant
                // reschedule may under-report at the window edges, but it will never
                // claim the owner is busy at a time they demonstrably freed.
                // The pad must cover the SHIFT plus the override's DURATION, not the
                // shift alone. A master occurrence can end up overlapping the window
                // purely because the override lengthened it: shift a 09:00–10:00
                // instance by +2h and stretch it to 90 minutes and it runs 11:00–12:30,
                // which overlaps a 12:05–12:25 query even though its shifted START is
                // before the window and its unshifted start is 3h before that.
                long pad = 0;
                for (Override ov : overrides) {
                    if (ov.thisAndFuture) {
                        long reach = Math.abs(ov.shiftMillis) + (ov.hasDuration ? ov.durationMillis : 0);
                        pad = Math.max(pad, Math.min(reach, MAX_PAD_MILLIS));
                    }
                }
                Period period = pad == 0
                        ? new Period(ws, we)
                        : new Period(new DateTime(ws.getTime() - pad), new DateTime(we.getTime() + pad));

                PeriodList periods = v.calculateRecurrenceSet(period);
                for (Object po : periods) {
                    Period p = (Period) po;
                    long startMillis = p.getStart().getTime();
                    String startValue = String.valueOf(p.getStart());

                    Override exact = exactOverride(overrides, startMillis);
                    if (exact != null) {
                        // RFC 5545 §3.8.4.4: the override REPLACES this instance. Emitted
                        // in pass 2a (or deliberately dropped when cancelled) — never here.
                        continue;
                    }

                    Override future = governingThisAndFuture(overrides, startMillis);
                    if (future == null) {
                        if (pad != 0 && !overlapsWindow(startMillis, endMillis(p, startMillis), ws, we)) {
                            continue; // only reachable via the padded expansion above
                        }
                        candidates.add(new Candidate(startMillis, ICalEventOccurrence.newBuilder()
                                .setUid(conv.getUid())
                                .setSummary(conv.getSummary())
                                .setLocation(conv.getLocation())
                                .setOccurrenceStart(startValue)
                                .setOccurrenceEnd(p.getEnd() == null ? "" : String.valueOf(p.getEnd()))
                                .setIsRecurring(recurring)
                                .setStatus(conv.getStatus())
                                .build()));
                        continue;
                    }

                    // Governed by a RANGE=THISANDFUTURE override: shift by its delta and
                    // adopt its summary/location/status — AND its duration.
                    //
                    // RFC 5545 §3.8.4.4: "When the given recurrence instance is
                    // rescheduled, all subsequent instances are also rescheduled by the
                    // same time difference. […] Similarly, if the duration of the given
                    // recurrence instance is modified, then all subsequen[t] instances are
                    // also modified to have this same duration."
                    //
                    // Carrying the MASTER's duration forward instead would misreport in
                    // both directions: a shortened series would keep reporting the minutes
                    // the owner gave back as busy (the same phantom class this release
                    // removes), and a lengthened one would report free time over a real
                    // meeting — the more dangerous error, since a booking agent acts on it.
                    if (!includeCancelled && CANCELLED.equalsIgnoreCase(future.status)) {
                        continue;
                    }
                    long shiftedStart = startMillis + future.shiftMillis;
                    boolean hasEnd = future.hasDuration || p.getEnd() != null;
                    long shiftedEnd = future.hasDuration
                            ? shiftedStart + future.durationMillis
                            : endMillis(p, startMillis) + future.shiftMillis;
                    if (!overlapsWindow(shiftedStart, hasEnd ? shiftedEnd : shiftedStart, ws, we)) {
                        continue;
                    }
                    // Rendered in the SAME form as the master occurrence it displaces, not
                    // forced to UTC: mixing forms inside one series hands the consumer two
                    // rows for the same meeting in incompatible notations, with no field to
                    // tell them apart.
                    candidates.add(new Candidate(shiftedStart, ICalEventOccurrence.newBuilder()
                            .setUid(conv.getUid())
                            .setSummary(future.summary)
                            .setLocation(future.location)
                            .setOccurrenceStart(renderLike(p.getStart(), shiftedStart))
                            .setOccurrenceEnd(hasEnd ? renderLike(p.getStart(), shiftedEnd) : "")
                            .setIsRecurring(recurring)
                            .setRecurrenceId(startValue)
                            .setIsOverride(true)
                            .setStatus(future.status)
                            .build()));
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

    // ---- RECURRENCE-ID override plumbing ------------------------------------------

    /** A single RECURRENCE-ID override VEVENT, pre-resolved for cheap matching. */
    private static final class Override {
        final String uid;
        final String summary;
        final String location;
        final String status;
        /** The replaced instant, as epoch millis (TZID/UTC/floating all resolved). */
        final long recurrenceIdMillis;
        /** The replaced instant's literal RFC 5545 value, for representation-equal matching. */
        final String recurrenceIdValue;
        /** RANGE=THISANDFUTURE — this override governs later instances too. */
        final boolean thisAndFuture;
        /** DTSTART − RECURRENCE-ID: how far THISANDFUTURE moves each later instance. */
        final long shiftMillis;
        /** This override's own DTSTART, as epoch millis and as its literal value. */
        final long startMillis;
        final String startValue;
        /** This override's own end. {@code hasDuration} is false when it has neither
         *  a DTEND nor a DURATION to derive one from. */
        final boolean hasDuration;
        final long durationMillis;
        final String endValue;

        Override(String uid, String summary, String location, String status, long recurrenceIdMillis,
                 String recurrenceIdValue, boolean thisAndFuture, long shiftMillis,
                 long startMillis, String startValue, boolean hasDuration, long durationMillis,
                 String endValue) {
            this.uid = uid;
            this.summary = summary;
            this.location = location;
            this.status = status;
            this.recurrenceIdMillis = recurrenceIdMillis;
            this.recurrenceIdValue = recurrenceIdValue;
            this.thisAndFuture = thisAndFuture;
            this.shiftMillis = shiftMillis;
            this.startMillis = startMillis;
            this.startValue = startValue;
            this.hasDuration = hasDuration;
            this.durationMillis = durationMillis;
            this.endValue = endValue;
        }
    }

    /** Returns null when {@code v} is a master VEVENT (i.e. carries no RECURRENCE-ID). */
    private static Override overrideOf(VEvent v) {
        Property p = null;
        for (Property cand : v.getProperties(Property.RECURRENCE_ID)) {
            p = cand;
            break;
        }
        if (!(p instanceof RecurrenceId)) {
            return null;
        }
        net.fortuna.ical4j.model.Date ridDate = ((RecurrenceId) p).getDate();
        if (ridDate == null) {
            return null; // malformed RECURRENCE-ID: treat as a master rather than silently dropping it
        }
        net.fortuna.ical4j.model.Date dtstart =
                v.getStartDate() == null ? null : v.getStartDate().getDate();
        if (dtstart == null) {
            // An override with no DTSTART names an instant to replace but supplies no
            // replacement. Honouring the suppression would DELETE a real meeting and put
            // nothing back, so this malformed component is treated as a non-override and
            // the master's instance is left standing.
            return null;
        }
        ICalEvent conv = ICal4jHelper.eventOf(v);

        // RANGE=THISANDFUTURE (RFC 5545 §3.2.13) is the only RANGE value this revision
        // of iCalendar defines; THISANDPRIOR is deprecated and MUST NOT be generated.
        net.fortuna.ical4j.model.Parameter range = p.getParameter(net.fortuna.ical4j.model.Parameter.RANGE);
        boolean thisAndFuture = range != null && "THISANDFUTURE".equalsIgnoreCase(String.valueOf(range.getValue()));

        long shift = thisAndFuture ? dtstart.getTime() - ridDate.getTime() : 0;

        net.fortuna.ical4j.model.Date dtend =
                v.getEndDate(true) == null ? null : v.getEndDate(true).getDate();
        boolean hasDuration = dtend != null;
        long durationMillis = hasDuration ? dtend.getTime() - dtstart.getTime() : 0;

        return new Override(conv.getUid(), conv.getSummary(), conv.getLocation(), conv.getStatus(),
                ridDate.getTime(), String.valueOf(ridDate), thisAndFuture, shift,
                dtstart.getTime(), String.valueOf(dtstart), hasDuration, durationMillis,
                hasDuration ? String.valueOf(dtend) : "");
    }

    /**
     * The override that replaces exactly this instant, or null.
     *
     * <p>Matched on the resolved INSTANT, never on the literal digits: a RECURRENCE-ID
     * and the master occurrence it replaces are routinely written in different forms —
     * {@code RECURRENCE-ID:20260810T130000Z} against a {@code TZID=America/New_York}
     * master instance at 09:00 local is the same slot, and a literal comparison would
     * miss it and leave the phantom behind. All-day DATE values and RFC 5545 floating
     * times resolve consistently on both sides, so they need no special case.
     *
     * <p>A literal-value fallback was deliberately NOT kept here: no calendar shape
     * could be found that it rescues, and it can only ever ADD matches — i.e. suppress
     * an occurrence that no override actually replaced. Silently dropping a real
     * meeting is a worse failure than the phantom this node exists to remove.
     */
    private static Override exactOverride(List<Override> overrides, long startMillis) {
        for (Override ov : overrides) {
            if (ov.recurrenceIdMillis == startMillis) {
                return ov;
            }
        }
        return null;
    }

    /**
     * The nearest RANGE=THISANDFUTURE override at or before {@code startMillis}, or null.
     * {@code overrides} is sorted latest-first, so the first match is the nearest — a
     * later THISANDFUTURE override supersedes an earlier one for the instances it covers.
     */
    private static Override governingThisAndFuture(List<Override> overrides, long startMillis) {
        for (Override ov : overrides) {
            if (ov.thisAndFuture && ov.recurrenceIdMillis <= startMillis) {
                return ov;
            }
        }
        return null;
    }

    /**
     * Renders a shifted instant in the SAME RFC 5545 form as the master occurrence it
     * displaces — UTC against a UTC series, the master's TZID against a zoned one, a
     * bare DATE against an all-day one.
     *
     * <p>This is not cosmetic. {@code ICalEventOccurrence} has no zone field, so the
     * form of {@code occurrence_start} is the only thing telling a consumer how to
     * read it. Emitting shifted instants as UTC while their unshifted siblings stay
     * local puts two rows for the same recurring meeting into one response in
     * incompatible notations, with nothing to distinguish them — a renderer or a
     * slot-finder reads the local digits as UTC and lands hours off.
     */
    private static String renderLike(net.fortuna.ical4j.model.Date reference, long millis) {
        if (reference instanceof DateTime) {
            DateTime ref = (DateTime) reference;
            DateTime out = new DateTime(millis);
            if (ref.isUtc()) {
                out.setUtc(true);
            } else if (ref.getTimeZone() != null) {
                out.setTimeZone(ref.getTimeZone());
            }
            // Neither UTC nor zoned: RFC 5545 floating time, which is exactly what a
            // bare DateTime renders as. Nothing more to set.
            return String.valueOf(out);
        }
        return String.valueOf(new net.fortuna.ical4j.model.Date(millis));
    }

    private static long endMillis(Period p, long startMillis) {
        return p.getEnd() == null ? startMillis : p.getEnd().getTime();
    }

    /**
     * Window overlap for occurrences this node had to filter itself — the ones it
     * expanded over a PADDED window and then shifted for RANGE=THISANDFUTURE, which
     * ical4j's own filter therefore never saw.
     *
     * <p>This deliberately reproduces ical4j's semantics for a RECURRING VEVENT,
     * which is what the padded path always expands: {@code calculateRecurrenceSet}
     * is CLOSED at both ends for a recurring event — an occurrence ending exactly at
     * {@code window_start}, or starting exactly at {@code window_end}, IS returned.
     * (Verified against the deployed 0.1.2; note it is half-OPEN at both ends for a
     * NON-recurring VEVENT, an ical4j inconsistency that predates this node and is
     * left untouched here rather than silently changed underneath published
     * consumers.) Matching it exactly is the point: the same calendar must not answer
     * differently depending on whether an unrelated later instance happened to be
     * edited, which is the only thing that turns the padding on.
     */
    private static boolean overlapsWindow(long start, long end, DateTime ws, DateTime we) {
        return start <= we.getTime() && end >= ws.getTime();
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
