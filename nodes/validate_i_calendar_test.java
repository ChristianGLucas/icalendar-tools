package nodes;

import axiom.AxiomContext;
import gen.Messages.ICalTextInput;
import gen.Messages.ICalValidationResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ValidateICalendarTest {

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
                public int addNode(String pkg, String ver, CanvasPosition pos) { return 0; }
                public void addEdge(int src, int dst, EdgeCondition cond) {}
            };
        }
    }

    @Test
    public void testValidateICalendar_wellFormedCalendarIsValid() {
        AxiomContext ax = new TestContext();
        ICalTextInput input = ICalTextInput.newBuilder().setIcsText(ParseICalendarTest.SAMPLE_ICS).build();
        ICalValidationResult result = ValidateICalendar.validateICalendar(ax, input);
        assertFalse(result.hasError(), "unexpected error: " + result.getError());
        assertTrue(result.getValid(), "expected valid, got errors: " + result.getErrorsList());
        assertEquals(0, result.getErrorsCount());
    }

    @Test
    public void testValidateICalendar_vEventMissingRequiredUidIsInvalid() {
        // RFC 5545 §3.6.1: a VEVENT MUST have a UID. This fixture omits it —
        // hand-verified against the spec, not derived from this package's code.
        String ics = String.join("\r\n",
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//Test//Test//EN",
                "BEGIN:VEVENT",
                "DTSTAMP:20260701T120000Z",
                "DTSTART:20260721T090000Z",
                "SUMMARY:Missing UID",
                "END:VEVENT",
                "END:VCALENDAR",
                "");
        AxiomContext ax = new TestContext();
        ICalValidationResult result = ValidateICalendar.validateICalendar(ax, ICalTextInput.newBuilder().setIcsText(ics).build());
        assertFalse(result.hasError());
        assertFalse(result.getValid());
        assertTrue(result.getErrorsCount() >= 1);
    }

    @Test
    public void testValidateICalendar_garbageInputIsInvalidNotCrash() {
        AxiomContext ax = new TestContext();
        ICalValidationResult result = ValidateICalendar.validateICalendar(ax,
                ICalTextInput.newBuilder().setIcsText("definitely not iCalendar").build());
        assertNotNull(result);
        assertFalse(result.hasError());
        assertFalse(result.getValid());
        assertTrue(result.getErrorsCount() >= 1);
        assertTrue(result.getErrors(0).toLowerCase().contains("parse"));
    }

    @Test
    public void testValidateICalendar_oversizedInputReturnsLimitExceededError() {
        AxiomContext ax = new TestContext();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < (2 * 1024 * 1024) + 1; i++) huge.append('x');
        ICalValidationResult result = ValidateICalendar.validateICalendar(ax,
                ICalTextInput.newBuilder().setIcsText(huge.toString()).build());
        assertTrue(result.hasError());
        assertEquals("LIMIT_EXCEEDED", result.getError().getCode());
    }
}
