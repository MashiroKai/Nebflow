# What

Add independent human reminder schedule persistence and validation.

# Why

Legacy triggerAt is a separate surfacing clock, not a due time. New schedules retain the originating region and wall clock.

# Impact

Legacy records remain without a due setting; new one-shot and weekly rules are validated before storage. Notification and scheduling execution remain outside this change.
