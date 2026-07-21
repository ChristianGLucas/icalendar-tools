package nodes;

import axiom.AxiomContext;
import gen.Messages.ICalCalendar;
import gen.Messages.ICalTextOutput;
import net.fortuna.ical4j.model.Calendar;

import java.util.Map;

public class BuildICalendar {

    /**
     * Generate a valid, RFC 5545-folded .ics VCALENDAR document from a structured
     * ICalCalendar (events/todos/journals) — the reverse of ParseICalendar;
     * Parse(Build(x)) round-trips every field. Fills a default PRODID/VERSION when
     * the caller leaves them empty. Rejects a calendar with zero components, or a
     * component missing its required UID, with a structured error rather than
     * emitting invalid iCalendar.
     *
     * <p>{@code ax} is the AxiomContext (ADR-001): every platform capability is
     * reached through it — {@code ax.log()}, {@code ax.secrets()},
     * {@code ax.reflection()}, {@code ax.mutation()}. Node
     * code never talks to the platform directly.
     *
     * @param ax    The AxiomContext: logging, secrets, reflection, mutation.
     * @param input The decoded ICalCalendar for this invocation.
     */
    public static ICalTextOutput buildICalendar(AxiomContext ax, ICalCalendar input) {
        ax.log().info("buildICalendar handling", Map.of());
        if (input.hasError()) {
            return ICalTextOutput.newBuilder().setError(input.getError()).build();
        }
        try {
            Calendar cal = ICal4jHelper.buildCalendar(input);
            String text = ICal4jHelper.render(cal);
            return ICalTextOutput.newBuilder().setIcsText(text).build();
        } catch (ICal4jHelper.IcalException e) {
            return ICalTextOutput.newBuilder().setError(ICal4jHelper.toProtoError(e)).build();
        } catch (Exception e) {
            return ICalTextOutput.newBuilder().setError(ICal4jHelper.internalError(e)).build();
        }
    }
}
