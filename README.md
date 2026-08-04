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

## Use it from your agent or app

Every node in this package is a **live, auto-scaling API endpoint** on the
[Axiom](https://axiomide.com) marketplace — call it from an AI agent or your own
code, with nothing to self-host.

**📦 See it on the marketplace:**
https://dev.axiomide.com/marketplace/christiangeorgelucas/icalendar-tools@0.1.3

**Hook it up to an AI agent (MCP).** Add Axiom's hosted MCP server to any MCP
client and every node becomes a typed tool your agent can call — search the
catalog, inspect a schema, and invoke it directly.

```bash
# Claude Code
claude mcp add --transport http axiom https://api.axiomide.com/mcp \
  --header "Authorization: Bearer $AXIOM_API_KEY"
```

Claude Desktop, Cursor, or any config-based client:

```json
{
  "mcpServers": {
    "axiom": {
      "type": "http",
      "url": "https://api.axiomide.com/mcp",
      "headers": { "Authorization": "Bearer YOUR_AXIOM_API_KEY" }
    }
  }
}
```

**Call it from the CLI.**

```bash
axiom invoke christiangeorgelucas/icalendar-tools/ParseICalendar --input '{ ... }'
```

**Call it over HTTP.**

```bash
curl -X POST https://api.axiomide.com/invocations/v1/nodes/christiangeorgelucas/icalendar-tools/0.1.3/ParseICalendar \
  -H "Authorization: Bearer $AXIOM_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{ ... }'
```

> Input/output schema for each node is on the marketplace page above, or via
> `axiom inspect node christiangeorgelucas/icalendar-tools/ParseICalendar`.

### Get started free

Install the CLI:

```bash
# macOS / Linux — Homebrew
brew install axiomide/tap/axiom

# macOS / Linux — install script
curl -fsSL https://raw.githubusercontent.com/AxiomIDE/axiom-releases/main/install.sh | sh
```

**Windows:** download the `windows/amd64` `.zip` from the
[releases page](https://github.com/AxiomIDE/axiom-releases/releases), unzip it,
and put `axiom.exe` on your `PATH`.

Then `axiom version` to verify, `axiom login` (GitHub or Google) to authenticate,
and create an API key under **Console → API Keys**. Docs and sign-up at
**[axiomide.com](https://axiomide.com)**.

## Nodes

| Node | What it does |
|---|---|
| `ParseICalendar` | Parse a `.ics` document into a structured `ICalCalendar` (events/todos/journals with every field). |
| `BuildICalendar` | Generate a valid, RFC 5545-folded `.ics` document from a structured `ICalCalendar` — the reverse of `ParseICalendar`. |
| `ValidateICalendar` | Validate a `.ics` document against RFC 5545, reporting every violation found rather than just the first. |
| `ListEvents` | List the VEVENTs in a `.ics` document, optionally filtered by category or by overlap with an instant window. |
| `ExpandOccurrences` | Expand every VEVENT's RRULE/RDATE/EXDATE into concrete occurrences within a caller-supplied window, with RECURRENCE-ID overrides applied. |

### RECURRENCE-ID overrides (RFC 5545 §3.8.4.4)

When someone edits ONE instance of a recurring series — dragging next Tuesday's
standup to a different hour, or cancelling just that one — Google Calendar and
Outlook do not rewrite the RRULE. They append a second VEVENT with the **same
UID** plus a `RECURRENCE-ID` naming the instant it replaces:

```
BEGIN:VEVENT
UID:standup@example.com
DTSTART:20260810T090000Z
RRULE:FREQ=DAILY;COUNT=3
SUMMARY:Daily standup
END:VEVENT
BEGIN:VEVENT
UID:standup@example.com
RECURRENCE-ID:20260810T090000Z      <-- replaces the 10 Aug 09:00 instance
DTSTART:20260810T101500Z            <-- ...which now happens at 10:15
SUMMARY:Daily standup (moved)
END:VEVENT
```

`ExpandOccurrences` **subtracts** the overridden instant from the master series,
so the 10 Aug 09:00 slot comes back **free** — it is not reported as still busy,
and the one real meeting is not counted twice. Each occurrence carries:

| field | meaning |
| --- | --- |
| `recurrence_id` | the master slot this occurrence replaced (empty unless `is_override`) — note this is the **vacated** time, which is exactly what `occurrence_start` is not |
| `is_override` | true when the instance was individually edited |
| `status` | the effective RFC 5545 `STATUS` (`CONFIRMED` / `TENTATIVE` / `CANCELLED`) |

`ParseICalendar` and `ListEvents` surface the same information per event
(`recurrence_id`, `recurrence_id_tzid`, `recurrence_id_range`), and
`BuildICalendar` writes it back — so `Parse(Build(x))` round-trips an override
as an override. That matters: a rebuild that dropped `RECURRENCE-ID` would turn
one edited meeting into two independent ones, manufacturing the very phantom
occurrence described above.

Cancelled instances (`STATUS:CANCELLED`) are **omitted by default** — a cancelled
instance does not happen, so a free/busy or conflict consumer must not see it.
Set `include_cancelled: true` to receive them anyway, each flagged
`status: "CANCELLED"`, e.g. to render a struck-through agenda. The same applies
to a whole series marked `STATUS:CANCELLED`.

`RANGE=THISANDFUTURE` (RFC 5545 §3.2.13) is honoured: the override governs its
own instance **and every later one**, which are shifted by its
(`DTSTART` − `RECURRENCE-ID`) delta and take its summary/location/status.

Subtraction is keyed off the whole calendar rather than the requested window, so
an override that relocates an instance *outside* the window still frees the slot
it vacated *inside* it.

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
