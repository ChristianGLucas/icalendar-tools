package nodes;

import axiom.AxiomContext;
import gen.Messages.ICalTextInput;
import gen.Messages.ICalValidationResult;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.validate.ValidationEntry;
import net.fortuna.ical4j.validate.ValidationException;
import net.fortuna.ical4j.validate.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ValidateICalendar {

    /**
     * Validate a .ics document against RFC 5545: reports valid=false with one entry
     * per problem (a parse failure, or a validation rule broken on some component —
     * e.g. a VEVENT missing its required UID or DTSTAMP) rather than throwing.
     * valid=true means the document both parses and passes ical4j's RFC 5545
     * component validation.
     *
     * <p>{@code ax} is the AxiomContext (ADR-001): every platform capability is
     * reached through it — {@code ax.log()}, {@code ax.secrets()},
     * {@code ax.reflection()}, {@code ax.mutation()}. Node
     * code never talks to the platform directly.
     *
     * @param ax    The AxiomContext: logging, secrets, reflection, mutation.
     * @param input The decoded ICalTextInput for this invocation.
     */
    public static ICalValidationResult validateICalendar(AxiomContext ax, ICalTextInput input) {
        ax.log().info("validateICalendar handling", Map.of());
        if (input.hasError()) {
            return ICalValidationResult.newBuilder().setError(input.getError()).build();
        }

        Calendar cal;
        try {
            cal = ICal4jHelper.parse(input.getIcsText());
        } catch (ICal4jHelper.IcalException e) {
            if ("LIMIT_EXCEEDED".equals(e.code)) {
                return ICalValidationResult.newBuilder().setError(ICal4jHelper.toProtoError(e)).build();
            }
            // A genuine "this is not iCalendar" failure is the substance of what
            // Validate reports, not an operational error — surface it as a finding.
            return ICalValidationResult.newBuilder()
                    .setValid(false)
                    .addErrors("parse error: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            return ICalValidationResult.newBuilder().setError(ICal4jHelper.internalError(e)).build();
        }

        List<String> errors = new ArrayList<>();
        try {
            collectErrors(cal.validate(false), "VCALENDAR", errors);
        } catch (ValidationException e) {
            errors.add("VCALENDAR: " + e.getMessage());
        } catch (Exception e) {
            errors.add("VCALENDAR: " + e.getMessage());
        }
        for (Object o : cal.getComponents()) {
            Component comp = (Component) o;
            try {
                // ical4j's component validators report cardinality/required-property
                // violations (e.g. a missing UID) as ValidationResult entries rather
                // than by throwing — the entries must be read explicitly, or a real
                // violation like a missing UID silently passes as "valid".
                collectErrors(comp.validate(true), comp.getName(), errors);
            } catch (ValidationException e) {
                errors.add(comp.getName() + ": " + e.getMessage());
            } catch (Exception e) {
                errors.add(comp.getName() + ": " + e.getMessage());
            }
        }
        return ICalValidationResult.newBuilder().setValid(errors.isEmpty()).addAllErrors(errors).build();
    }

    private static void collectErrors(ValidationResult result, String context, List<String> errors) {
        if (result == null) return;
        for (ValidationEntry entry : result.getEntries()) {
            if (entry.getSeverity() == ValidationEntry.Severity.ERROR) {
                errors.add(context + ": " + entry.getMessage());
            }
        }
    }
}
