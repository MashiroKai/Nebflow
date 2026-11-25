# Event-driven reminder creation time zones

## Status and scope

Input contract approved by the author (28d33e78); other explicitly identified Spider rulings remain pending author confirmation. Covers the current batch only; earlier branch slices have no retrospective change record. A reminder due is independent of the existing daily todo surface. Message zones remain per-message; merged inputs retain a zone only if every source has the same valid region. Author subsequently chose the most recently reported device as the fallback when the initiating message has no device zone. A separate persistent per-device latest-report record and creation-source audit are therefore required.

## Requirements and acceptance scenarios

### Input and creation provenance

- Given a todo creation with no `triggerAt` or `triggerAt` equal to zero, when either write port executes, then the old no-due todo behavior remains unchanged.
- Given a WS todo creation with a nonzero numeric `triggerAt` and a valid device IANA region in the frame, when the handler creates it, then its due instant and creation region persist. WS `triggerAt` remains numeric; old clients without a region receive a clarification error rather than a host-zone default.
- Given a Schedule todo creation with a due input containing an explicit IANA region, when the tool creates it, then that region determines creation; otherwise a valid ToolContext device region is used. If neither exists the tool requests clarification. A numeric offset alone determines the instant, not a region.
- Given a Schedule input without a region and with a device region, then local time is interpreted in that region; with neither region the write fails for clarification. Relative words require the region before their calendar date can be resolved, and the original wording survives clarification.
- Given a once input with an explicit offset, the instant is computed from that offset and the offset is stored as Some; given no input offset it is None. The existing model has `final case class Once(dueAt: Long, zoneId: String, wallClock: String, originalOffset: String)` and its decoder reads `zoneId` and `originalOffset` as strings and validates the latter with `ZoneOffset.of`. Optional representation must decode old string values as Some and missing or null as None, validating present values. An older binary may reject new records: author must decide migration or dual-write before merge. The persisted zoneId stays a required valid IANA string: a stored record missing zoneId fails decoding; only pre-write parsing may represent a missing region.
- Given a weekly due, persist the weekday, local minute and creation region and compute its first occurrence without a fixed-duration week. A WS weekly triggerAt not on a whole minute is rejected with `weekly-requires-whole-minute`, naming triggerAt and its received value; it is never rounded. Whether sub-minute weekly reminders or rounding should be supported remains for author confirmation. Invalid or incomplete rules and invalid regions are rejected. Both write ports have once and weekly acceptance coverage where their supported inputs permit it.

### Latest device fallback

- On an authenticated device identification report with a valid IANA zone, persist one latest observation per device identity: zone, server receipt timestamp and frame source. Invalid or absent zones do not overwrite a valid observation. Use browser:<clientId> for legal self-declared client IDs; this is not trusted device proof and a collision within the same authenticated principal can misattribute a report. Missing or invalid IDs and unauthenticated frames are ignored. Trusted registered-device association is future hardening. Device records may be offline and remain eligible; select the greatest receipt timestamp, then lexicographically smallest identity for ties. No reports means clarification.
- Given an explicitly sourceless channel or scheduled turn, the Schedule write uses the latest observation and persists audit provenance including its device identity and timestamp. Explicit region and initiating device take precedence. Given no observation, creation requests clarification. A merged ambiguous turn must never fall back. Record authenticated identify reports even when takeover is disabled; unauthenticated reports must not be stored. Keep ambiguity provenance in per-turn memory only. Older records decode without new optional audit fields.

### Rule change and occurrence settlement

- Author chose rescheduling already generated, not-yet-triggered occurrences after explicit creation-zone correction. The prior no-rescheduling/no-new-instance-state exclusion is superseded. Untriggered means an occurrence without a persisted consumption marker, including already due but undelivered instances; no future-instance pre-generation is introduced. Under one storage lock, edits recalculate untriggered instances preserving stable identity and bind the new rule version; future series also use the new rule. A recalculated instant at or before edit time is marked missed with reason, never delivered as catch-up; this missed policy is a conservative default pending author confirmation. Already triggered instances do not change.
- The occurrence's identity remains stable across rescheduling; persist any required stable ID and settlement state compatibly with old records. Engine consumption into the delivery path constitutes triggering and settles at most once even when external delivery fails. Concurrent edit and trigger serialize by persisted order. Tests must discriminate future series, overdue untriggered rescheduling with stable identity, missed recalculation without catch-up, preserved triggered history, both edit/trigger orderings, and replay of an untriggered instance after restart; removal of rescheduling must make the appropriate assertions red.
- New persisted device observations and occurrence/source-audit fields may not be understood by older builds; migration or dual-write needs author decision before merge. These features are not evidence of external exactly-once notification.

### Explicit correction and display

- Given a created reminder, its receipt and card show creation region and wall clock. When the device region differs, show the converted local time and its region as well. Explicit correction reschedules future untriggered occurrences under the rule above; no automatic travel-based zone change occurs. Test both real write ports and frontend rendering.

### Calendar ambiguity

- Given a local time in a DST gap, use the first valid instant after the gap and mark the adjustment. Given an overlap, use the earlier instant and mark its chosen offset.
- Given an ambiguous or past expression, request confirmation rather than silently rolling it forward; accept an unambiguous future expression. Cross-midnight date arithmetic uses the creation region's calendar.

### Evidence and exclusions

- Exercise old records, both write ports, valid and invalid regions, offset and offset-free input, cross-midnight, DST gaps and overlaps, different device regions, occurrence deduplication, crash retry and expiry with individually discriminating red/green tests.
- Instance interpretation uses its bound rule version; tick, rearm and direct new-rule saves use the current rule. A store-level atomic claim and settlement seam is required for the author's rescheduling decision; this slice adds no delivery frame, actor delivery wiring, or external notification exactly-once guarantee. Visible due delivery and replay belong to B/D after A integration; timezone presentation remains in this batch. Notification exactly-once and real network delivery are not claimed.
- The four write-port input contract decisions are author-approved (28d33e78); Q3–Q6 and separately identified compatibility and implementation decisions remain Spider rulings pending author confirmation. Q7 thresholds remain open; automatic catch-up is outside this batch. Before merge, author review must decide whether earlier slices need their own OpenSpec change records.
