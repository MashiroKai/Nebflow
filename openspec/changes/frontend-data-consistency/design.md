# Design and limitations

The Note store atomically writes a versioned JSON record, including a clear tombstone, with per-path in-process locking. Legacy markdown is read until a versioned record exists; unreadable records block writes. Gateway puts require a baseVersion and echo writeId. The dock has per-file timers and in-flight writes.

User-created todos are marked user and appended. Tool and engine records carry explicit source markers. A named rearm keeps the stored record's ID and creation time: the request supplies the new fields, and `Rearm.saved` is what callers report and broadcast. Legacy ownership is adopted only when the producer proves it: a named `kind=task` row (only ScheduleTool names that kind), or a reserved `ask-`/`perm-`/`weekly-report-` prefix. A legacy named todo is ambiguous because the todo UI sends names, so the rearm is refused before any write; unknown rows are never removed by name.

Cross-session rearm moves the record to the requested session in two steps: an atomic single-file write of the destination first, then removal of the source/duplicate copies. `AtomicJson` only guarantees per-file atomic replacement, so there is no multi-file transaction; a failure in the second step leaves the same ID in two files and only logs a warning. The next rearm converges it, so failure loses no record. While the duplicate exists, id-scoped cancel, completion, toggle and fired marks cover **every** copy, and the due/pending/todo read faces collapse the duplicate to one row; only storage and a subsequent rearm still carry two rows. This closes the "cancelled copy keeps firing" and "fires twice in one tick" consequences without inventing a cross-file transaction.

A correlated user create retry compares request fields only: `triggerAt` and `completedAt` are engine/state fields and are deliberately excluded, so a retry after completion is not an identity conflict. The created frame reports the durable row (`saved`), including its real `completed`/`completedAt`.

A mobile parked retry keeps its identity across navigation because a lost receipt must not create a duplicate. If that identity can no longer succeed (another active session, or the row was edited after the receipt was lost), the sheet offers an explicit **discard retry** action: it drops only the retry identity, keeps the text, and allows a fresh create. No stored row is guessed away. The request ID is generated without requiring `crypto.randomUUID` (absent in non-secure contexts) but always in the server-accepted lowercase v4 UUID shape.

## Known wording differences (author review required)

The tool description is prompt-facing and was not edited here. Two phrases are now broader than the implementation:

- `ScheduleTool.scala:110-111` — "Same name + kind replaces the old entry (across all sessions)": only a record with the same **explicit source** (or a producer-proven legacy record) is replaced; a same-name user todo is kept, and an unprovable legacy row makes the rearm fail instead of being replaced.
- `ScheduleTool.scala:204` — "same name + kind replaces the existing entry instead of stacking": same qualification.

The tool result and created frames now report the durable row's stable ID, but the descriptive text still says "replaces". A rearm can also fail with `Tool Execution error: legacy task ownership is unknown…`, which the description does not mention.

## Review follow-up

Dirty text, base version, and conflict state are stored together in one browser key, including empty text. Conflict state retains the server snapshot and provides explicit local/server choices. A fresh server read does not establish the base of an edit begun before that read. Reconnect retains a known base, and a late receipt or stale read cannot lower it; a receipt whose stored text already equals the draft adopts the version without reopening a choice. The note input handler, reconnect resync and flush are named functions so the executable test slice can drive them (openNote/syncNoteForFile still sit outside that slice). ID-bearing todo completion references resolve by ID; ambiguous plain names do not resolve.

`tests/note-protocol-live.mjs` is the executable real-protocol entry (real WS against an isolated instance) covering unknown base, clear, conflict, duplicate write, reconnect and late-write refusals. It was written in this step but not executed here (no instance was started by this agent); `tests/todo-create-sheet.spec.mjs` was executed against the routed web tree (Chromium/WebKit × light/dark).

