package nodes;

import gen.Messages.Error;
import gen.Messages.ICalAlarm;
import gen.Messages.ICalAttendee;
import gen.Messages.ICalCalendar;
import gen.Messages.ICalEvent;
import gen.Messages.ICalJournal;
import gen.Messages.ICalTodo;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.validate.ValidationException;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VJournal;
import net.fortuna.ical4j.model.component.VToDo;
import net.fortuna.ical4j.model.property.Action;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.CalScale;
import net.fortuna.ical4j.model.property.Categories;
import net.fortuna.ical4j.model.property.Clazz;
import net.fortuna.ical4j.model.property.Completed;
import net.fortuna.ical4j.model.property.Created;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Due;
import net.fortuna.ical4j.model.property.Duration;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.LastModified;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.Method;
import net.fortuna.ical4j.model.property.Organizer;
import net.fortuna.ical4j.model.property.PercentComplete;
import net.fortuna.ical4j.model.property.Priority;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.RDate;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.Repeat;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Status;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Transp;
import net.fortuna.ical4j.model.property.Trigger;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Url;
import net.fortuna.ical4j.model.property.Version;
import net.fortuna.ical4j.model.parameter.Cn;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.parameter.Related;
import net.fortuna.ical4j.model.parameter.Role;
import net.fortuna.ical4j.model.parameter.Rsvp;
import net.fortuna.ical4j.model.Dur;

import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Shared ical4j boilerplate for every node in christiangeorgelucas/icalendar-tools:
 * bounded parsing/rendering, and the two-way conversion between ical4j's object
 * model and this package's proto envelope (ICalCalendar/ICalEvent/ICalTodo/
 * ICalJournal/ICalAttendee/ICalAlarm). Centralized here so every node enforces
 * the same input-size bound and the same error contract instead of drifting.
 *
 * <p>Offline only: this class never enables ical4j's optional network-backed
 * timezone update mechanisms (e.g. the IPFS/tzurl.org updating registry) — it
 * always builds a {@link TimeZoneRegistry} from the JVM default factory, which
 * resolves purely against the timezone data bundled inside the ical4j jar. A
 * malformed or hostile .ics document therefore cannot trigger any outbound
 * network call by being parsed or rendered here. The core .ics grammar itself
 * is a hand-rolled line parser (not XML), so there is no XXE/entity-expansion
 * surface in this package's parsing path either.
 *
 * <p>Not a node itself — no Axiom annotations, never registered as an endpoint.
 */
final class ICal4jHelper {

    private ICal4jHelper() {}

    /** Raw .ics input larger than this is rejected before any parsing is attempted. */
    static final int MAX_ICS_BYTES = 2 * 1024 * 1024;

    /** Defensive ceiling on total top-level components in a single calendar. */
    static final int MAX_COMPONENTS = 10_000;

    private static final TimeZoneRegistry TZ_REGISTRY =
            TimeZoneRegistryFactory.getInstance().createRegistry();

    /** A structured failure, carrying the same (code, message) shape as the proto Error. */
    static final class IcalException extends Exception {
        final String code;
        IcalException(String code, String message) {
            super(message);
            this.code = code;
        }
        IcalException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    static Error toProtoError(IcalException e) {
        return Error.newBuilder().setCode(e.code).setMessage(String.valueOf(e.getMessage())).build();
    }

    static Error internalError(Throwable t) {
        return Error.newBuilder().setCode("INTERNAL").setMessage(String.valueOf(t.getMessage())).build();
    }

    // ---- size-bounded parse / render -------------------------------------------------

    static void checkSize(String icsText) throws IcalException {
        if (icsText == null) {
            throw new IcalException("INVALID_ARGUMENT", "ics_text is required");
        }
        int bytes = icsText.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_ICS_BYTES) {
            throw new IcalException("LIMIT_EXCEEDED",
                    "ics_text is " + bytes + " bytes, exceeding the " + MAX_ICS_BYTES + " byte cap");
        }
    }

    static Calendar parse(String icsText) throws IcalException {
        checkSize(icsText);
        try {
            CalendarBuilder builder = new CalendarBuilder();
            Calendar calendar = builder.build(new StringReader(icsText));
            int total = calendar.getComponents().size();
            if (total > MAX_COMPONENTS) {
                throw new IcalException("LIMIT_EXCEEDED",
                        "calendar has " + total + " components, exceeding the " + MAX_COMPONENTS + " cap");
            }
            return calendar;
        } catch (IcalException e) {
            throw e;
        } catch (Exception e) {
            throw new IcalException("INVALID_ICS", "could not parse .ics text: " + e.getMessage(), e);
        }
    }

    static String render(Calendar calendar) throws IcalException {
        try {
            CalendarOutputter out = new CalendarOutputter();
            StringWriter sw = new StringWriter();
            out.output(calendar, sw);
            return sw.toString();
        } catch (ValidationException e) {
            throw new IcalException("INVALID_ARGUMENT", "calendar failed RFC 5545 validation: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IcalException("INTERNAL", "could not render .ics text: " + e.getMessage(), e);
        }
    }

    // ---- generic property helpers ------------------------------------------------------

    private static Property firstProperty(Component c, String name) {
        PropertyList<Property> props = c.getProperties(name);
        Iterator<Property> it = props.iterator();
        return it.hasNext() ? it.next() : null;
    }

    private static String valueOf(Component c, String name) {
        Property p = firstProperty(c, name);
        return p == null ? "" : String.valueOf(p.getValue());
    }

    private static String paramValue(Property p, String paramName) {
        if (p == null) return "";
        Parameter param = p.getParameter(paramName);
        return param == null ? "" : String.valueOf(param.getValue());
    }

    // ---- date value <-> (value, tzid) --------------------------------------------------

    private static String dateValueOf(Component c, String propName) {
        return valueOf(c, propName);
    }

    private static String dateTzidOf(Component c, String propName) {
        Property p = firstProperty(c, propName);
        return paramValue(p, Parameter.TZID);
    }

    /** Parses a bare RFC 5545 DATE or DATE-TIME value (optionally with a TZID) into an ical4j Date. */
    private static net.fortuna.ical4j.model.Date parseDateValue(String value, String tzid) throws IcalException {
        if (value == null || value.isEmpty()) return null;
        try {
            if (!value.contains("T")) {
                return new net.fortuna.ical4j.model.Date(value);
            }
            if (!value.endsWith("Z") && tzid != null && !tzid.isEmpty()) {
                TimeZone tz = TZ_REGISTRY.getTimeZone(tzid);
                if (tz == null) {
                    throw new IcalException("INVALID_ARGUMENT", "unknown timezone id: " + tzid);
                }
                // CRITICAL FIX: parsing with the (String) constructor and then calling
                // setTimeZone(tz) afterward does NOT reinterpret the wall-clock digits
                // as belonging to tz — it preserves the absolute instant parsed under
                // the JVM's default zone and re-renders it in tz, silently shifting the
                // time by the offset delta between the two zones. The (String,
                // TimeZone) constructor parses the literal digits AS wall-clock time in
                // the given zone, which is what a TZID-anchored DTSTART/DTEND means.
                return new DateTime(value, tz);
            }
            return new DateTime(value);
        } catch (IcalException e) {
            throw e;
        } catch (Exception e) {
            throw new IcalException("INVALID_ARGUMENT", "invalid date/time value \"" + value + "\": " + e.getMessage(), e);
        }
    }

    private static void setDateProperty(DateProperty prop, String value, String tzid) throws IcalException {
        net.fortuna.ical4j.model.Date d = parseDateValue(value, tzid);
        if (d != null) {
            prop.setDate(d);
        }
    }

    // ---- ICalAttendee <-> Organizer/Attendee -------------------------------------------

    private static String stripMailto(String calAddress) {
        if (calAddress == null) return "";
        String v = calAddress;
        if (v.regionMatches(true, 0, "mailto:", 0, 7)) {
            v = v.substring(7);
        }
        return v;
    }

    static ICalAttendee attendeeOf(Attendee a) {
        ICalAttendee.Builder b = ICalAttendee.newBuilder()
                .setEmail(stripMailto(String.valueOf(a.getValue())))
                .setCommonName(paramValue(a, Parameter.CN))
                .setRole(paramValue(a, Parameter.ROLE))
                .setParticipationStatus(paramValue(a, Parameter.PARTSTAT));
        String rsvp = paramValue(a, Parameter.RSVP);
        b.setRsvp("TRUE".equalsIgnoreCase(rsvp));
        return b.build();
    }

    static ICalAttendee organizerOf(Organizer o) {
        return ICalAttendee.newBuilder()
                .setEmail(stripMailto(String.valueOf(o.getValue())))
                .setCommonName(paramValue(o, Parameter.CN))
                .build();
    }

    private static String calAddress(String email) {
        if (email.contains(":")) return email; // already a scheme'd CAL-ADDRESS
        return "mailto:" + email;
    }

    static Attendee buildAttendee(ICalAttendee a) throws IcalException {
        try {
            Attendee prop = new Attendee(calAddress(a.getEmail()));
            if (!a.getCommonName().isEmpty()) prop.getParameters().add(new Cn(a.getCommonName()));
            if (!a.getRole().isEmpty()) prop.getParameters().add(new Role(a.getRole()));
            if (!a.getParticipationStatus().isEmpty()) prop.getParameters().add(new PartStat(a.getParticipationStatus()));
            if (a.getRsvp()) prop.getParameters().add(Rsvp.TRUE);
            return prop;
        } catch (Exception e) {
            throw new IcalException("INVALID_ARGUMENT", "invalid attendee \"" + a.getEmail() + "\": " + e.getMessage(), e);
        }
    }

    static Organizer buildOrganizer(ICalAttendee o) throws IcalException {
        try {
            Organizer prop = new Organizer(calAddress(o.getEmail()));
            if (!o.getCommonName().isEmpty()) prop.getParameters().add(new Cn(o.getCommonName()));
            return prop;
        } catch (Exception e) {
            throw new IcalException("INVALID_ARGUMENT", "invalid organizer \"" + o.getEmail() + "\": " + e.getMessage(), e);
        }
    }

    // ---- ICalAlarm <-> VAlarm ------------------------------------------------------------

    static ICalAlarm alarmOf(VAlarm alarm) {
        ICalAlarm.Builder b = ICalAlarm.newBuilder()
                .setAction(valueOf(alarm, Property.ACTION))
                .setDescription(valueOf(alarm, Property.DESCRIPTION))
                .setSummary(valueOf(alarm, Property.SUMMARY))
                .setDuration(valueOf(alarm, Property.DURATION));
        Property trigger = firstProperty(alarm, Property.TRIGGER);
        if (trigger != null) {
            b.setTrigger(String.valueOf(trigger.getValue()));
            b.setTriggerRelated(paramValue(trigger, Parameter.RELATED));
        }
        Property repeat = firstProperty(alarm, Property.REPEAT);
        if (repeat != null) {
            try {
                b.setRepeat(Integer.parseInt(String.valueOf(repeat.getValue())));
            } catch (NumberFormatException ignored) {
            }
        }
        for (Object o : alarm.getProperties(Property.ATTENDEE)) {
            b.addAttendees(attendeeOf((Attendee) o));
        }
        return b.build();
    }

    static VAlarm buildAlarm(ICalAlarm a) throws IcalException {
        try {
            VAlarm alarm = new VAlarm();
            PropertyList<Property> props = alarm.getProperties();
            if (!a.getAction().isEmpty()) props.add(new Action(a.getAction()));
            if (!a.getTrigger().isEmpty()) {
                Trigger trigger = buildTrigger(a.getTrigger());
                if (!a.getTriggerRelated().isEmpty()) {
                    trigger.getParameters().add(new Related(a.getTriggerRelated()));
                }
                props.add(trigger);
            }
            if (!a.getDescription().isEmpty()) props.add(new Description(a.getDescription()));
            if (!a.getSummary().isEmpty()) props.add(new Summary(a.getSummary()));
            if (a.getRepeat() > 0) props.add(new Repeat(a.getRepeat()));
            if (!a.getDuration().isEmpty()) props.add(new Duration(new Dur(a.getDuration())));
            for (ICalAttendee at : a.getAttendeesList()) {
                props.add(buildAttendee(at));
            }
            return alarm;
        } catch (IcalException e) {
            throw e;
        } catch (Exception e) {
            throw new IcalException("INVALID_ARGUMENT", "invalid alarm: " + e.getMessage(), e);
        }
    }

    private static Trigger buildTrigger(String value) throws IcalException {
        try {
            if (!value.isEmpty() && (value.charAt(0) == '+' || value.charAt(0) == '-' || value.charAt(0) == 'P')) {
                return new Trigger(new Dur(value));
            }
            return new Trigger(new DateTime(value));
        } catch (Exception e) {
            throw new IcalException("INVALID_ARGUMENT", "invalid alarm trigger \"" + value + "\": " + e.getMessage(), e);
        }
    }

    // ---- categories / rdate / exdate -----------------------------------------------------

    private static List<String> categoriesOf(Component c) {
        List<String> out = new ArrayList<>();
        for (Object o : c.getProperties(Property.CATEGORIES)) {
            Categories cat = (Categories) o;
            Iterator<String> it = cat.getCategories().iterator();
            while (it.hasNext()) out.add(it.next());
        }
        return out;
    }

    private static void addCategories(Component c, List<String> categories) {
        if (categories.isEmpty()) return;
        Categories cat = new Categories();
        for (String s : categories) cat.getCategories().add(s);
        c.getProperties().add(cat);
    }

    private static List<String> rdateOf(Component c) {
        List<String> out = new ArrayList<>();
        for (Object o : c.getProperties(Property.RDATE)) {
            for (Object d : ((RDate) o).getDates()) {
                out.add(String.valueOf(d));
            }
        }
        return out;
    }

    private static List<String> exdateOf(Component c) {
        List<String> out = new ArrayList<>();
        for (Object o : c.getProperties(Property.EXDATE)) {
            for (Object d : ((ExDate) o).getDates()) {
                out.add(String.valueOf(d));
            }
        }
        return out;
    }

    private static void addRDates(Component c, List<String> values) throws IcalException {
        for (String v : values) {
            try {
                c.getProperties().add(new RDate(new ParameterList(), v));
            } catch (Exception e) {
                throw new IcalException("INVALID_ARGUMENT", "invalid rdate \"" + v + "\": " + e.getMessage(), e);
            }
        }
    }

    private static void addExDates(Component c, List<String> values) throws IcalException {
        for (String v : values) {
            try {
                c.getProperties().add(new ExDate(new ParameterList(), v));
            } catch (Exception e) {
                throw new IcalException("INVALID_ARGUMENT", "invalid exdate \"" + v + "\": " + e.getMessage(), e);
            }
        }
    }

    private static List<ICalAlarm> alarmsOf(Component c) {
        List<ICalAlarm> out = new ArrayList<>();
        Iterable<VAlarm> alarms = null;
        if (c instanceof VEvent) alarms = ((VEvent) c).getAlarms();
        else if (c instanceof VToDo) alarms = ((VToDo) c).getAlarms();
        if (alarms != null) {
            for (VAlarm a : alarms) out.add(alarmOf(a));
        }
        return out;
    }

    /**
     * Parses a caller-supplied window bound (e.g. ICalExpandInput.window_start) into
     * an ical4j DateTime. A bare DATE value (no "T") is treated as midnight floating
     * time, since a window bound is a coarse filter instant, not an authoritative
     * scheduling anchor.
     */
    static DateTime parseWindowInstant(String value) throws IcalException {
        if (value == null || value.isEmpty()) {
            throw new IcalException("INVALID_ARGUMENT", "window bound is required");
        }
        try {
            String v = value.contains("T") ? value : value + "T000000";
            return new DateTime(v);
        } catch (Exception e) {
            throw new IcalException("INVALID_ARGUMENT", "invalid window bound \"" + value + "\": " + e.getMessage(), e);
        }
    }

    /**
     * Some ical4j component constructors auto-populate a DTSTAMP with the current
     * wall-clock time as a convenience default — including, redundantly, when a
     * component is RECONSTRUCTED via the (PropertyList, ComponentList) constructor
     * to attach VALARM children (see buildEvent/buildTodo), which can leave BOTH an
     * auto-generated DTSTAMP and the caller-supplied one present at once (invalid
     * .ics — DTSTAMP MUST NOT occur more than once). This package promises
     * determinism (no wall-clock reads) and exactly the DTSTAMP the caller asked
     * for, so this is called ONCE, last, after any such reconstruction: it removes
     * every DTSTAMP already on the component, then re-adds exactly one if (and only
     * if) the caller supplied a value — never trusting whatever ical4j left behind.
     */
    private static void canonicalizeDtstamp(Component c, String callerDtstamp) throws IcalException {
        c.getProperties().removeIf(p -> Property.DTSTAMP.equals(p.getName()));
        if (callerDtstamp != null && !callerDtstamp.isEmpty()) {
            DtStamp p = new DtStamp();
            setDateProperty(p, callerDtstamp, "");
            c.getProperties().add(p);
        }
    }

    // ---- ICalEvent <-> VEvent -------------------------------------------------------------

    static ICalEvent eventOf(VEvent e) {
        ICalEvent.Builder b = ICalEvent.newBuilder()
                .setUid(valueOf(e, Property.UID))
                .setSummary(valueOf(e, Property.SUMMARY))
                .setDescription(valueOf(e, Property.DESCRIPTION))
                .setLocation(valueOf(e, Property.LOCATION))
                .setDtstart(dateValueOf(e, Property.DTSTART))
                .setDtstartTzid(dateTzidOf(e, Property.DTSTART))
                .setDtend(dateValueOf(e, Property.DTEND))
                .setDtendTzid(dateTzidOf(e, Property.DTEND))
                .setStatus(valueOf(e, Property.STATUS))
                .setRrule(valueOf(e, Property.RRULE))
                .addAllRdate(rdateOf(e))
                .addAllExdate(exdateOf(e))
                .addAllCategories(categoriesOf(e))
                .addAllAlarms(alarmsOf(e))
                .setCreated(valueOf(e, Property.CREATED))
                .setLastModified(valueOf(e, Property.LAST_MODIFIED))
                .setDtstamp(valueOf(e, Property.DTSTAMP))
                .setTransparency(valueOf(e, Property.TRANSP))
                .setClassification(valueOf(e, Property.CLASS))
                .setUrl(valueOf(e, Property.URL));
        String dtstart = dateValueOf(e, Property.DTSTART);
        b.setAllDay(!dtstart.isEmpty() && !dtstart.contains("T"));
        Property seq = firstProperty(e, Property.SEQUENCE);
        if (seq != null) {
            try {
                b.setSequence(Integer.parseInt(String.valueOf(seq.getValue())));
            } catch (NumberFormatException ignored) {
            }
        }
        Property org = firstProperty(e, Property.ORGANIZER);
        if (org != null) b.setOrganizer(organizerOf((Organizer) org));
        for (Object o : e.getProperties(Property.ATTENDEE)) b.addAttendees(attendeeOf((Attendee) o));
        return b.build();
    }

    static VEvent buildEvent(ICalEvent e) throws IcalException {
        try {
            VEvent v = new VEvent();
            PropertyList<Property> props = v.getProperties();
            if (e.getUid().isEmpty()) {
                throw new IcalException("INVALID_ARGUMENT", "VEVENT is missing required uid");
            }
            props.add(new Uid(e.getUid()));
            if (!e.getSummary().isEmpty()) props.add(new Summary(e.getSummary()));
            if (!e.getDescription().isEmpty()) props.add(new Description(e.getDescription()));
            if (!e.getLocation().isEmpty()) props.add(new Location(e.getLocation()));
            if (!e.getDtstart().isEmpty()) {
                DtStart p = new DtStart();
                setDateProperty(p, e.getDtstart(), e.getDtstartTzid());
                props.add(p);
            }
            if (!e.getDtend().isEmpty()) {
                DtEnd p = new DtEnd();
                setDateProperty(p, e.getDtend(), e.getDtendTzid());
                props.add(p);
            }
            if (!e.getStatus().isEmpty()) props.add(new Status(e.getStatus()));
            if (!e.getRrule().isEmpty()) {
                try {
                    props.add(new RRule(e.getRrule()));
                } catch (Exception ex) {
                    throw new IcalException("INVALID_ARGUMENT", "invalid rrule \"" + e.getRrule() + "\": " + ex.getMessage(), ex);
                }
            }
            addRDates(v, e.getRdateList());
            addExDates(v, e.getExdateList());
            if (e.hasOrganizer() && !e.getOrganizer().getEmail().isEmpty()) props.add(buildOrganizer(e.getOrganizer()));
            for (ICalAttendee a : e.getAttendeesList()) props.add(buildAttendee(a));
            addCategories(v, e.getCategoriesList());
            if (e.getSequence() != 0) props.add(new Sequence(e.getSequence()));
            if (!e.getCreated().isEmpty()) {
                Created p = new Created();
                setDateProperty(p, e.getCreated(), "");
                props.add(p);
            }
            if (!e.getLastModified().isEmpty()) {
                LastModified p = new LastModified();
                setDateProperty(p, e.getLastModified(), "");
                props.add(p);
            }
            if (!e.getTransparency().isEmpty()) props.add(new Transp(e.getTransparency()));
            if (!e.getClassification().isEmpty()) props.add(new Clazz(e.getClassification()));
            if (!e.getUrl().isEmpty()) {
                try {
                    props.add(new Url(new URI(e.getUrl())));
                } catch (Exception ex) {
                    throw new IcalException("INVALID_ARGUMENT", "invalid url \"" + e.getUrl() + "\": " + ex.getMessage(), ex);
                }
            }
            if (!e.getAlarmsList().isEmpty()) {
                // VEvent's alarm list is wired in at construction time — mutating
                // getAlarms() after the fact does not attach it to the component.
                net.fortuna.ical4j.model.ComponentList<VAlarm> alarms = new net.fortuna.ical4j.model.ComponentList<>();
                for (ICalAlarm a : e.getAlarmsList()) alarms.add(buildAlarm(a));
                v = new VEvent(props, alarms);
            }
            canonicalizeDtstamp(v, e.getDtstamp());
            return v;
        } catch (IcalException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IcalException("INVALID_ARGUMENT", "invalid event \"" + e.getUid() + "\": " + ex.getMessage(), ex);
        }
    }

    // ---- ICalTodo <-> VToDo ----------------------------------------------------------------

    static ICalTodo todoOf(VToDo t) {
        ICalTodo.Builder b = ICalTodo.newBuilder()
                .setUid(valueOf(t, Property.UID))
                .setSummary(valueOf(t, Property.SUMMARY))
                .setDescription(valueOf(t, Property.DESCRIPTION))
                .setLocation(valueOf(t, Property.LOCATION))
                .setDtstart(dateValueOf(t, Property.DTSTART))
                .setDtstartTzid(dateTzidOf(t, Property.DTSTART))
                .setDue(dateValueOf(t, Property.DUE))
                .setDueTzid(dateTzidOf(t, Property.DUE))
                .setCompleted(valueOf(t, Property.COMPLETED))
                .setStatus(valueOf(t, Property.STATUS))
                .setRrule(valueOf(t, Property.RRULE))
                .addAllRdate(rdateOf(t))
                .addAllExdate(exdateOf(t))
                .addAllCategories(categoriesOf(t))
                .addAllAlarms(alarmsOf(t))
                .setCreated(valueOf(t, Property.CREATED))
                .setLastModified(valueOf(t, Property.LAST_MODIFIED))
                .setDtstamp(valueOf(t, Property.DTSTAMP))
                .setClassification(valueOf(t, Property.CLASS))
                .setUrl(valueOf(t, Property.URL));
        Property pc = firstProperty(t, Property.PERCENT_COMPLETE);
        if (pc != null) {
            try {
                b.setPercentComplete(Integer.parseInt(String.valueOf(pc.getValue())));
            } catch (NumberFormatException ignored) {
            }
        }
        Property pr = firstProperty(t, Property.PRIORITY);
        if (pr != null) {
            try {
                b.setPriority(Integer.parseInt(String.valueOf(pr.getValue())));
            } catch (NumberFormatException ignored) {
            }
        }
        Property seq = firstProperty(t, Property.SEQUENCE);
        if (seq != null) {
            try {
                b.setSequence(Integer.parseInt(String.valueOf(seq.getValue())));
            } catch (NumberFormatException ignored) {
            }
        }
        Property org = firstProperty(t, Property.ORGANIZER);
        if (org != null) b.setOrganizer(organizerOf((Organizer) org));
        for (Object o : t.getProperties(Property.ATTENDEE)) b.addAttendees(attendeeOf((Attendee) o));
        return b.build();
    }

    static VToDo buildTodo(ICalTodo t) throws IcalException {
        try {
            VToDo v = new VToDo();
            PropertyList<Property> props = v.getProperties();
            if (t.getUid().isEmpty()) {
                throw new IcalException("INVALID_ARGUMENT", "VTODO is missing required uid");
            }
            props.add(new Uid(t.getUid()));
            if (!t.getSummary().isEmpty()) props.add(new Summary(t.getSummary()));
            if (!t.getDescription().isEmpty()) props.add(new Description(t.getDescription()));
            if (!t.getLocation().isEmpty()) props.add(new Location(t.getLocation()));
            if (!t.getDtstart().isEmpty()) {
                DtStart p = new DtStart();
                setDateProperty(p, t.getDtstart(), t.getDtstartTzid());
                props.add(p);
            }
            if (!t.getDue().isEmpty()) {
                Due p = new Due();
                setDateProperty(p, t.getDue(), t.getDueTzid());
                props.add(p);
            }
            if (!t.getCompleted().isEmpty()) {
                Completed p = new Completed();
                setDateProperty(p, t.getCompleted(), "");
                props.add(p);
            }
            if (!t.getStatus().isEmpty()) props.add(new Status(t.getStatus()));
            if (t.getPercentComplete() != 0) props.add(new PercentComplete(t.getPercentComplete()));
            if (t.getPriority() != 0) props.add(new Priority(t.getPriority()));
            if (!t.getRrule().isEmpty()) {
                try {
                    props.add(new RRule(t.getRrule()));
                } catch (Exception ex) {
                    throw new IcalException("INVALID_ARGUMENT", "invalid rrule \"" + t.getRrule() + "\": " + ex.getMessage(), ex);
                }
            }
            addRDates(v, t.getRdateList());
            addExDates(v, t.getExdateList());
            if (t.hasOrganizer() && !t.getOrganizer().getEmail().isEmpty()) props.add(buildOrganizer(t.getOrganizer()));
            for (ICalAttendee a : t.getAttendeesList()) props.add(buildAttendee(a));
            addCategories(v, t.getCategoriesList());
            if (t.getSequence() != 0) props.add(new Sequence(t.getSequence()));
            if (!t.getCreated().isEmpty()) {
                Created p = new Created();
                setDateProperty(p, t.getCreated(), "");
                props.add(p);
            }
            if (!t.getLastModified().isEmpty()) {
                LastModified p = new LastModified();
                setDateProperty(p, t.getLastModified(), "");
                props.add(p);
            }
            if (!t.getClassification().isEmpty()) props.add(new Clazz(t.getClassification()));
            if (!t.getUrl().isEmpty()) {
                try {
                    props.add(new Url(new URI(t.getUrl())));
                } catch (Exception ex) {
                    throw new IcalException("INVALID_ARGUMENT", "invalid url \"" + t.getUrl() + "\": " + ex.getMessage(), ex);
                }
            }
            if (!t.getAlarmsList().isEmpty()) {
                net.fortuna.ical4j.model.ComponentList<VAlarm> alarms = new net.fortuna.ical4j.model.ComponentList<>();
                for (ICalAlarm a : t.getAlarmsList()) alarms.add(buildAlarm(a));
                v = new VToDo(props, alarms);
            }
            canonicalizeDtstamp(v, t.getDtstamp());
            return v;
        } catch (IcalException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IcalException("INVALID_ARGUMENT", "invalid todo \"" + t.getUid() + "\": " + ex.getMessage(), ex);
        }
    }

    // ---- ICalJournal <-> VJournal -----------------------------------------------------------

    static ICalJournal journalOf(VJournal j) {
        List<String> descriptions = new ArrayList<>();
        for (Object o : j.getProperties(Property.DESCRIPTION)) {
            descriptions.add(String.valueOf(((Property) o).getValue()));
        }
        ICalJournal.Builder b = ICalJournal.newBuilder()
                .setUid(valueOf(j, Property.UID))
                .setSummary(valueOf(j, Property.SUMMARY))
                .setDescription(String.join("\n\n", descriptions))
                .setDtstart(dateValueOf(j, Property.DTSTART))
                .setDtstartTzid(dateTzidOf(j, Property.DTSTART))
                .setStatus(valueOf(j, Property.STATUS))
                .addAllCategories(categoriesOf(j))
                .setCreated(valueOf(j, Property.CREATED))
                .setLastModified(valueOf(j, Property.LAST_MODIFIED))
                .setDtstamp(valueOf(j, Property.DTSTAMP))
                .setClassification(valueOf(j, Property.CLASS))
                .setUrl(valueOf(j, Property.URL));
        Property seq = firstProperty(j, Property.SEQUENCE);
        if (seq != null) {
            try {
                b.setSequence(Integer.parseInt(String.valueOf(seq.getValue())));
            } catch (NumberFormatException ignored) {
            }
        }
        Property org = firstProperty(j, Property.ORGANIZER);
        if (org != null) b.setOrganizer(organizerOf((Organizer) org));
        for (Object o : j.getProperties(Property.ATTENDEE)) b.addAttendees(attendeeOf((Attendee) o));
        return b.build();
    }

    static VJournal buildJournal(ICalJournal j) throws IcalException {
        try {
            VJournal v = new VJournal();
            PropertyList<Property> props = v.getProperties();
            if (j.getUid().isEmpty()) {
                throw new IcalException("INVALID_ARGUMENT", "VJOURNAL is missing required uid");
            }
            props.add(new Uid(j.getUid()));
            if (!j.getSummary().isEmpty()) props.add(new Summary(j.getSummary()));
            if (!j.getDescription().isEmpty()) props.add(new Description(j.getDescription()));
            if (!j.getDtstart().isEmpty()) {
                DtStart p = new DtStart();
                setDateProperty(p, j.getDtstart(), j.getDtstartTzid());
                props.add(p);
            }
            if (!j.getStatus().isEmpty()) props.add(new Status(j.getStatus()));
            if (j.hasOrganizer() && !j.getOrganizer().getEmail().isEmpty()) props.add(buildOrganizer(j.getOrganizer()));
            for (ICalAttendee a : j.getAttendeesList()) props.add(buildAttendee(a));
            addCategories(v, j.getCategoriesList());
            if (j.getSequence() != 0) props.add(new Sequence(j.getSequence()));
            if (!j.getCreated().isEmpty()) {
                Created p = new Created();
                setDateProperty(p, j.getCreated(), "");
                props.add(p);
            }
            if (!j.getLastModified().isEmpty()) {
                LastModified p = new LastModified();
                setDateProperty(p, j.getLastModified(), "");
                props.add(p);
            }
            if (!j.getClassification().isEmpty()) props.add(new Clazz(j.getClassification()));
            if (!j.getUrl().isEmpty()) {
                try {
                    props.add(new Url(new URI(j.getUrl())));
                } catch (Exception ex) {
                    throw new IcalException("INVALID_ARGUMENT", "invalid url \"" + j.getUrl() + "\": " + ex.getMessage(), ex);
                }
            }
            canonicalizeDtstamp(v, j.getDtstamp());
            return v;
        } catch (IcalException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IcalException("INVALID_ARGUMENT", "invalid journal \"" + j.getUid() + "\": " + ex.getMessage(), ex);
        }
    }

    // ---- ICalCalendar <-> Calendar -----------------------------------------------------------

    static ICalCalendar calendarOf(Calendar cal) {
        ICalCalendar.Builder b = ICalCalendar.newBuilder()
                .setProdid(propOf(cal, Property.PRODID))
                .setVersion(propOf(cal, Property.VERSION))
                .setCalscale(propOf(cal, Property.CALSCALE))
                .setMethod(propOf(cal, Property.METHOD));
        for (Object o : cal.getComponents(Component.VEVENT)) b.addEvents(eventOf((VEvent) o));
        for (Object o : cal.getComponents(Component.VTODO)) b.addTodos(todoOf((VToDo) o));
        for (Object o : cal.getComponents(Component.VJOURNAL)) b.addJournals(journalOf((VJournal) o));
        return b.build();
    }

    private static String propOf(Calendar cal, String name) {
        Property p = firstProperty(cal, name);
        return p == null ? "" : String.valueOf(p.getValue());
    }

    private static Property firstProperty(Calendar cal, String name) {
        Iterator<Property> it = cal.getProperties(name).iterator();
        return it.hasNext() ? it.next() : null;
    }

    static Calendar buildCalendar(ICalCalendar c) throws IcalException {
        if (c.getEventsCount() == 0 && c.getTodosCount() == 0 && c.getJournalsCount() == 0) {
            throw new IcalException("INVALID_ARGUMENT", "calendar has no VEVENT/VTODO/VJOURNAL components");
        }
        Calendar cal = new Calendar();
        PropertyList<Property> props = cal.getProperties();
        String prodid = c.getProdid().isEmpty()
                ? "-//christiangeorgelucas//icalendar-tools//EN"
                : c.getProdid();
        props.add(new ProdId(prodid));
        props.add(new Version(new ParameterList(), c.getVersion().isEmpty() ? "2.0" : c.getVersion()));
        props.add(new CalScale(c.getCalscale().isEmpty() ? "GREGORIAN" : c.getCalscale()));
        if (!c.getMethod().isEmpty()) props.add(new Method(c.getMethod()));
        for (ICalEvent e : c.getEventsList()) cal.getComponents().add(buildEvent(e));
        for (ICalTodo t : c.getTodosList()) cal.getComponents().add(buildTodo(t));
        for (ICalJournal j : c.getJournalsList()) cal.getComponents().add(buildJournal(j));
        return cal;
    }
}
