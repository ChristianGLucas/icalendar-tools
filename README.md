# icalendar-tools

Composable **iCalendar (RFC 5545) calendar-FILE** nodes for the
[Axiom](https://axiomide.com) marketplace, published as
`christiangeorgelucas/icalendar-tools`. Parse a whole `.ics` VCALENDAR
document into structured VEVENTs/VTODOs/VJOURNALs, build a valid `.ics`
document back from that structure, validate a `.ics` document against the
spec, list/filter the VEVENTs in a calendar, and expand a calendar's
recurring VEVENTs into concrete occurrences within a caller-supplied window
— entirely offline and deterministically.

Written in **Java**, wrapping one battle-tested, permissively-licensed
library:

| Concern | Library | License |
|---|---|---|
| iCalendar (RFC 5545) parsing, model, validation, recurrence expansion | [`ical4j`](https://github.com/ical4j/ical4j) (the reference JVM iCalendar library) | BSD-3-Clause |

Every node is **stateless**, **offline** (no network, no API keys, no
signup — ical4j's optional network-backed timezone update mechanisms are
never enabled; timezone resolution always uses the data bundled inside the
jar), and **deterministic** (DTSTAMP is emitted only when the caller
supplies one — never auto-filled from the wall clock).

Distinct from `christiangeorgelucas/recurrence-tools`, which expands a
single bare RFC 5545 RRULE string the caller supplies directly. This
package operates at the **calendar-file level**: parsing/building/
validating whole `.ics` documents (VEVENT/VTODO/VJOURNAL, organizer/
attendees, VALARM, timezones), with `ExpandOccurrences` combining every
recurring VEVENT's RRULE + RDATE + EXDATE already inside a parsed calendar
file — complementary to, not a duplicate of, recurrence-tools' bare-RRULE
API. An `ICalEvent`'s `rrule` + `dtstart` (+ `dtstart_tzid`) fields are
deliberately named and shaped to drop directly into recurrence-tools'
`Recurrence` envelope for bare-RRULE expansion.

## Nodes

| Node | What it does |
|---|---|
| `ParseICalendar` | Parse a `.ics` document into a structured `ICalCalendar` (events/todos/journals with every field). |
| `BuildICalendar` | Generate a valid, RFC 5545-folded `.ics` document from a structured `ICalCalendar` — the reverse of `ParseICalendar`. |
| `ValidateICalendar` | Validate a `.ics` document against RFC 5545, reporting every violation found rather than just the first. |
| `ListEvents` | List the VEVENTs in a `.ics` document, optionally filtered by category or by overlap with an instant window. |
| `ExpandOccurrences` | Expand every VEVENT's RRULE/RDATE/EXDATE into concrete occurrences within a caller-supplied window. |

## Bounds & security

- `.ics` input is capped at 2 MiB, checked before any parsing is attempted.
- A parsed calendar is capped at 10,000 top-level components.
- `ExpandOccurrences`' window span is capped at 50 years and its result
  count at 5,000 occurrences (`truncated=true` flags a window that hit the
  cap rather than running unbounded).
- ical4j's core `.ics` grammar is a hand-rolled line parser, not XML-based
  — there is no XXE/entity-expansion surface in this package's parsing path.
- Malformed input returns a structured `Error { code, message }` rather
  than crashing, for every node.

## Error contract

Every node returns an `Error { code, message }` on malformed input:
`INVALID_ICS`, `INVALID_ARGUMENT`, `LIMIT_EXCEEDED`, or `INTERNAL`.
`ValidateICalendar` is the exception in spirit only — a document that fails
to parse is reported as a validation finding (`valid=false`), not an
operational `Error`, since parseability is exactly what that node reports on.

---

Built for the Axiom marketplace. MIT licensed.
