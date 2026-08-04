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
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
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
                    // Rendered through renderAs, NOT from the raw source value: an
                    // all-day override's DTSTART is a bare DATE ("20260812") while every
                    // other row of the same series is a DATE-TIME ("20260812T000000Z"),
                    // and mixing the two inside one response breaks both parsing and the
                    // lexicographic ordering downstream flows depend on.
                    candidates.add(new Candidate(start, ICalEventOccurrence.newBuilder()
                            .setUid(ov.uid)
                            .setSummary(ov.summary)
                            .setLocation(ov.location)
                            .setOccurrenceStart(renderAs(ov.startDate, start))
                            .setOccurrenceEnd(ov.hasDuration ? renderAs(ov.startDate, end) : "")
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

                // Which master instants can possibly land in the window?
                //
                // An UNGOVERNED instance is emitted as-is, so it must overlap [ws, we].
                // An instance governed by a THISANDFUTURE override with shift Δ and
                // duration D is emitted at [S+Δ, S+Δ+D], so it lands in the window iff
                //
                //     S ∈ [ws − Δ − D,  we − Δ]
                //
                // — the window TRANSLATED by −Δ and widened by D, NOT widened by |Δ|.
                // That distinction is the whole point: each search interval stays
                // (we − ws) + D wide however distant the reschedule, so a series
                // postponed by two years costs no more to resolve than one moved by two
                // hours and needs no cost ceiling. Padding symmetrically instead forces
                // a clamp, and a clamp silently DROPS the propagated instances — telling
                // a booking agent "no conflict" over real meetings, the same
                // under-reporting this node rejects everywhere else.
                //
                // Each interval is searched separately and each instance is emitted by
                // exactly one of them, because which interval owns an instance is
                // decided by governingThisAndFuture(S) — a function of the instance, not
                // of the interval. So there are no duplicates to reconcile.
                List<Override> intervalOwners = new ArrayList<>();
                intervalOwners.add(null); // the plain window, for ungoverned instances
                for (Override ov : overrides) {
                    if (ov.thisAndFuture) intervalOwners.add(ov);
                }

                // The plain interval is widened BACKWARD by the master's own duration
                // when the series is recurring. ical4j filters RDATE-scheduled
                // occurrences on their START falling inside the requested period, with
                // no duration allowance — unlike RRULE-scheduled ones, which it filters
                // on overlap — so an RDATE occurrence STRADDLING window_start would
                // otherwise vanish and a real two-hour meeting would be reported as free
                // time. (Wrong since 0.1.0; the same query answers correctly when the
                // identical occurrence is scheduled by RRULE or by a plain VEVENT.)
                //
                // Only when recurring: a NON-recurring VEVENT already goes through
                // ical4j's overlap filter correctly, and widening its interval would
                // silently flip its half-open boundary rule to a closed one underneath
                // consumers that depend on "an event ending at 10:00 does not conflict
                // with a proposal starting at 10:00".
                //
                // The unconditional overlapsWindow below trims whatever the widening
                // over-collects, and ownership is untouched, so no duplicates arise.
                long masterDuration = recurring ? masterDurationOf(v) : 0;

                for (Override owner : intervalOwners) {
                    Period period;
                    if (owner == null) {
                        period = masterDuration == 0
                                ? new Period(ws, we)
                                : new Period(new DateTime(ws.getTime() - masterDuration), we);
                    } else {
                        long d = owner.hasDuration ? owner.durationMillis : 0;
                        period = new Period(
                                new DateTime(ws.getTime() - owner.shiftMillis - d),
                                new DateTime(we.getTime() - owner.shiftMillis));
                    }

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
                        // Emit an instance only from the interval that owns it, so widening
                        // one interval can never duplicate what another already produced.
                        if (future != owner) {
                            continue;
                        }
                        if (future == null) {
                            if (!overlapsWindow(startMillis, endMillis(p, startMillis), ws, we)) {
                                continue;
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
                                .setOccurrenceStart(renderAs(p.getStart(), shiftedStart))
                                .setOccurrenceEnd(hasEnd ? renderAs(p.getStart(), shiftedEnd) : "")
                                .setIsRecurring(recurring)
                                .setRecurrenceId(startValue)
                                .setIsOverride(true)
                                .setStatus(future.status)
                                .build()));
                    }
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
        /** This override's own DTSTART: the parsed value (for rendering) and its instant. */
        final net.fortuna.ical4j.model.Date startDate;
        final long startMillis;
        /** This override's own end. {@code hasDuration} is false when it has neither
         *  a DTEND nor a DURATION to derive one from. */
        final boolean hasDuration;
        final long durationMillis;

        Override(String uid, String summary, String location, String status, long recurrenceIdMillis,
                 String recurrenceIdValue, boolean thisAndFuture, long shiftMillis,
                 net.fortuna.ical4j.model.Date startDate, long startMillis,
                 boolean hasDuration, long durationMillis) {
            this.uid = uid;
            this.summary = summary;
            this.location = location;
            this.status = status;
            this.recurrenceIdMillis = recurrenceIdMillis;
            this.recurrenceIdValue = recurrenceIdValue;
            this.thisAndFuture = thisAndFuture;
            this.shiftMillis = shiftMillis;
            this.startMillis = startMillis;
            this.startDate = startDate;
            this.hasDuration = hasDuration;
            this.durationMillis = durationMillis;
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
                ridDate.getTime(), renderAs(ridDate, ridDate.getTime()), thisAndFuture, shift,
                dtstart, dtstart.getTime(), hasDuration, durationMillis);
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
     * Renders an instant the way this node reports EVERY occurrence bound: always as a
     * DATE-TIME, carrying {@code reference}'s zone character — UTC against a UTC
     * series, the reference's TZID against a zoned one, floating against a floating
     * one, and UTC for an all-day (VALUE=DATE) bound.
     *
     * <p>This is not cosmetic. {@code ICalEventOccurrence} has no zone field, so the
     * FORM of {@code occurrence_start} is the only thing telling a consumer how to
     * read it, and every row must therefore agree. Two ways of getting that wrong have
     * already shipped as defects in this node's history: emitting shifted instants as
     * UTC while their unshifted siblings stayed local, and emitting an all-day
     * override as a bare 8-character DATE while its siblings stayed
     * {@code …T000000Z}. Both put the same meeting into one response twice in
     * incompatible notations. The second also breaks lexicographic comparison —
     * {@code "20260812" < "20260812T000000Z"} — which downstream flows rely on for
     * chronological ordering of fixed-width UTC strings.
     *
     * <p>Reporting an all-day bound as UTC midnight rather than a bare DATE is what
     * ical4j's own period expansion does (it wraps every period bound in a DateTime),
     * so this matches the form the node has always emitted for all-day occurrences.
     */
    private static String renderAs(net.fortuna.ical4j.model.Date reference, long millis) {
        DateTime out = new DateTime(millis);
        if (reference instanceof DateTime) {
            DateTime ref = (DateTime) reference;
            if (ref.isUtc()) {
                out.setUtc(true);
            } else if (ref.getTimeZone() != null) {
                out.setTimeZone(ref.getTimeZone());
            }
            // Neither UTC nor zoned: RFC 5545 floating time, which is exactly what a
            // bare DateTime renders as. Nothing more to set.
        } else {
            // A VALUE=DATE bound carries no time-of-day and no zone; its instant is UTC
            // midnight, which is how ical4j materializes it into a period.
            out.setUtc(true);
        }
        return String.valueOf(out);
    }

    /**
     * The master VEVENT's own DTSTART→DTEND span (deriving the end from DURATION when
     * that is what the source used), or 0 when it has neither.
     */
    private static long masterDurationOf(VEvent v) {
        // getEndDate(true) constructs a fresh DtEnd when deriving from DURATION, so it
        // is read once rather than three times.
        DtStart start = v.getStartDate();
        DtEnd end = v.getEndDate(true);
        if (start == null || start.getDate() == null || end == null || end.getDate() == null) {
            return 0;
        }
        long d = end.getDate().getTime() - start.getDate().getTime();
        return d > 0 ? d : 0;
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
