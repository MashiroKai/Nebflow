# Event-driven reminder creation time zones

## Status and scope

Proposed implementation pending author confirmation. Covers the current batch only; earlier branch slices have no retrospective change record. A reminder due is independent of the existing daily todo surface. Device zones are per-message, not persisted as message metadata. Merged inputs retain a zone only if every source has the same valid region; mail has no source. Ambiguity persists through the turn and is recalculated on the next turn.

## Requirements and acceptance scenarios

### Input and creation provenance

- Given a todo creation with no `triggerAt` or `triggerAt` equal to zero, when either write port executes, then the old no-due todo behavior remains unchanged.
- Given a WS todo creation with a nonzero numeric `triggerAt` and a valid device IANA region in the frame, when the handler creates it, then its due instant and creation region persist. WS `triggerAt` remains numeric; old clients without a region receive a clarification error rather than a host-zone default.
- Given a Schedule todo creation with a due input containing an explicit IANA region, when the tool creates it, then that region determines creation; otherwise a valid ToolContext device region is used. If neither exists the tool requests clarification. A numeric offset alone determines the instant, not a region.
- Given a Schedule input without a region and with a device region, then local time is interpreted in that region; with neither region the write fails for clarification. Relative words require the region before their calendar date can be resolved, and the original wording survives clarification.
- Given a once input with an explicit offset, the instant is computed from that offset and the offset is stored as Some; given no input offset it is None. The existing model has `final case class Once(dueAt: Long, zoneId: String, wallClock: String, originalOffset: String)` and its decoder reads `zoneId` and `originalOffset` as strings and validates the latter with `ZoneOffset.of`. Optional representation must decode old string values as Some and missing or null as None, validating present values. An older binary may reject new records: author must decide migration or dual-write before merge. The persisted zoneId stays a required valid IANA string: a stored record missing zoneId fails decoding; only pre-write parsing may represent a missing region.
- Given a weekly due, persist the weekday, local minute and creation region and compute its first occurrence without a fixed-duration week. A WS weekly triggerAt not on a whole minute is rejected with `weekly-requires-whole-minute`, naming triggerAt and its received value; it is never rounded. Whether sub-minute weekly reminders or rounding should be supported remains for author confirmation. Invalid or incomplete rules and invalid regions are rejected. Both write ports have once and weekly acceptance coverage where their supported inputs permit it.

### Explicit correction and display

- Given a created reminder, its receipt and card show creation region and wall clock. When the device region differs, show the converted local time and its region as well. Explicit correction changes the rule without rewriting already generated occurrences; no automatic travel-based zone change occurs. Test both real write ports and frontend rendering.

### Calendar ambiguity

- Given a local time in a DST gap, use the first valid instant after the gap and mark the adjustment. Given an overlap, use the earlier instant and mark its chosen offset.
- Given an ambiguous or past expression, request confirmation rather than silently rolling it forward; accept an unambiguous future expression. Cross-midnight date arithmetic uses the creation region's calendar.

### Evidence and exclusions

- Exercise old records, both write ports, valid and invalid regions, offset and offset-free input, cross-midnight, DST gaps and overlaps, different device regions, occurrence deduplication, crash retry and expiry with individually discriminating red/green tests.
- Instance interpretation uses its bound rule version; tick, rearm and direct new-rule saves use the current rule. No new instance-consumption path, instance state or reconciliation protocol is introduced; generated instances are not rescheduled here. Notification exactly-once and real network delivery are not claimed.
- Q3–Q6 and these input and compatibility decisions are Spider rulings pending author confirmation. Q7 thresholds remain open; automatic catch-up is outside this batch. Before merge, author review must decide whether earlier slices need their own OpenSpec change records.
