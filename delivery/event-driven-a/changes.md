# What

Add independent human reminder schedule persistence and validation, and an occurrence identity value with an independent next-reminder time.

# Why

Legacy triggerAt is a separate surfacing clock, not a due time. New schedules retain the originating region and wall clock.

# Impact

Legacy records remain without a due setting; new one-shot and weekly rules are validated before storage. Occurrence creation and cursor advancement share an atomic session-file replacement, with duplicate keys ignored. The store operation is not wired to a scheduler or write port; cross-process concurrency and notification execution are not established by this change.
