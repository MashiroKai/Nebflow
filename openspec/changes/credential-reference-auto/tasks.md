# Tasks

- [x] Persist references instead of values on credential-bearing configuration saves.
- [x] Store secret contents atomically with owner-only permissions (0600 file, 0700 directory).
- [x] Resolve both legacy inline values and references at runtime and on user reveal; keep agent-facing projections value-free.
- [x] Record the reader and the reference on managed reference reads without recording the value.
- [x] Provide an idempotent isolated migration that backs up before writing and supports rollback.
- [ ] Production migration of existing values — NOT authorized and NOT performed; stays on an isolated copy until the owner authorizes it.
- [ ] OS-level audit of direct filesystem reads — NOT implemented (out of the in-process boundary).
