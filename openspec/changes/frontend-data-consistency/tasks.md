# Tasks

- [x] Add conditional Note store, per-file pending timers and source-scoped todo creation with focused tests.
- [x] Add conflict choices, durable local draft metadata and known-base reconnect tests.
- [x] Resolve legacy source migration and preserve tool rearm identity without guessing.
- [x] Add ID-bearing duplicate-name references and fail closed for unknown-origin rearm.
- [x] Implement cross-session rearm with a recoverable write-then-remove order rather than refusing it.
- [x] Add mode-0555 directory (real AtomicJson write failure), duplicate-call and partial-move recovery tests.
- [x] Add the executable real-WS Note protocol entry (`tests/note-protocol-live.mjs`).
- [ ] Run the live entry against a real isolated instance (main session starts it) and cross-platform GUI acceptance.
- [ ] Verify cross-process (not just single-JVM) conditional writes and the surviving-duplicate window under a real crash.
