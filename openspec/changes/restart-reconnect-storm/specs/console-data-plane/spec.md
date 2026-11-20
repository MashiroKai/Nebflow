## ADDED Requirements

### Requirement: [CDP-REQ-001] Bulk summary face for chain reads
The engine SHALL expose one authenticated read face (`GET /api/chain-digests`) that summarises every chain key in a **single** response — `{v, fingerprint, count, digests:{key: digest}}` — so a console client can decide which keys need a detail read without issuing one request per key. The face SHALL hash file bytes only (no JSON parsing, no writes), SHALL skip unreadable or oversized files rather than dropping their keys from the tree, and SHALL answer the conditional-request discipline (`ETag`/`If-None-Match`/`304`) already used by the row face.

#### Scenario: [CDP-SCN-001] One request covers every key
- **WHEN** a client asks `GET /api/chain-digests` on an installation holding N chain records
- **THEN** the engine SHALL answer one payload containing exactly those N keys with a digest each plus a stable `fingerprint`, and SHALL NOT require N requests.

#### Scenario: [CDP-SCN-002] An unchanged tree answers a body-less reply
- **WHEN** the client asks `GET /api/chain-digests?since=<fingerprint it already holds>` and no chain record has changed
- **THEN** the engine SHALL answer `{v, fingerprint, unchanged:true}` with no digest map, and SHALL answer `304` when the request's `If-None-Match` matches.

#### Scenario: [CDP-SCN-003] Only touched keys move
- **WHEN** exactly one chain record is appended to
- **THEN** the next digest payload SHALL differ for that key and SHALL keep every other key's digest unchanged, so a client re-reads one detail instead of the tree.

### Requirement: [CDP-REQ-002] The console reads in rounds, not per key
The console SHALL load chain detail only through a bounded round: one row-projection request, one bulk digest request, then detail reads restricted to keys that are uncached, whose digest moved, or that belong to an active row past a refresh TTL. Each round SHALL be capped (detail reads and star-subagent legs separately) and SHALL run under a shared concurrency limit. A reconnect SHALL reuse the existing mirror and digest state and SHALL refresh only the visible delta.

#### Scenario: [CDP-SCN-004] A steady-state reconnect issues no detail reads
- **WHEN** the console reconnects with a warm mirror and an unchanged digest fingerprint
- **THEN** the round SHALL issue one row request and one digest request and zero chain-detail requests.

#### Scenario: [CDP-SCN-005] A cold console load stays inside the budget
- **WHEN** the console loads against an installation with more rows and keys than the round's budget
- **THEN** the round SHALL issue at most the configured detail budget and child budget, SHALL serve active rows before terminal ones, and SHALL leave the remainder to later rounds and the lazy queue.

#### Scenario: [CDP-SCN-006] Unchanged rounds stay silent
- **WHEN** a round writes no new reading to the mirror
- **THEN** the console SHALL NOT broadcast the mirror-updated event, so no panel re-render is triggered by an empty poll.

### Requirement: [CDP-REQ-003] Rate limiting is answered with bounded patience
When the engine answers `429`, the console SHALL back off per route class (not per URL) with exponential delay and jitter, SHALL treat `Retry-After` as a floor, and SHALL issue no request inside the window. A rate-limited round SHALL stand down its remaining detail and subagent legs instead of letting each leg rediscover the limit. The engine's tunnel face SHALL keep answering `429` with a `Retry-After` header and SHALL keep logging each rejection.

#### Scenario: [CDP-SCN-007] No tight retry loop under a storm
- **WHEN** every detail request is answered `429` with `Retry-After: 5` for a sustained window
- **THEN** the console's detail attempts SHALL be grouped into bursts no wider than the concurrency limit, spaced by the backoff window, and the row face SHALL keep advancing.

### Requirement: [CDP-REQ-004] The tunnel budget suits the data plane and is readable
The tunnel guard's default per-envelope-IP budget SHALL admit a legitimate cold console load of a large installation, SHALL keep a finite cross-IP total, and SHALL give the `chains` route class its own configurable bucket. Every effective cap (general and per class, plus the window length) SHALL appear on the startup line, in the periodic snapshot line and in the health payload. The loopback exemption SHALL remain absent on this face and admission SHALL remain "strictly above the cap is refused".

#### Scenario: [CDP-SCN-008] Operators can read the budget they are being held to
- **WHEN** an instance starts with defaults, or a period snapshot / health read is taken
- **THEN** the tunnel per-IP cap, cross-IP total, window length and all three route-class caps SHALL be printed, and the `chains` class SHALL report a value tighter than the general bucket.

#### Scenario: [CDP-SCN-009] Route classes bucket independently
- **WHEN** one envelope IP exhausts the `chains` class budget
- **THEN** its `files` and `other` budgets SHALL remain available, and the general per-IP cap SHALL still apply on top.
