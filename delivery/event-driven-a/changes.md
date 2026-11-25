# What

Add independent human reminder schedule persistence and validation, and an occurrence identity value with an independent next-reminder time.

# Why

Legacy triggerAt is a separate surfacing clock, not a due time. New schedules retain the originating region and wall clock.

# Impact

Legacy records remain without a due setting; new one-shot and weekly rules are validated before storage. Occurrence creation and cursor advancement share an atomic session-file replacement, with duplicate keys ignored. Store operations use a directory-scoped JVM mutex and an OS file lock. OS lock acquisition uses a cancellable 25 ms retry loop without a built-in limit; the caller controls how long to wait through cancellation or a timeout. The store operation is not wired to a scheduler or write port; notification execution is not established by this change.
