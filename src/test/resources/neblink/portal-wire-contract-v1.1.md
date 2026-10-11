# Portal wire contract v1.1 (phase 3c re-freeze)

Version: v1.1, frozen 2026-10-11. Supersedes v1 (frozen 2026-10-10), which remains the
historical freeze record; this file is the single operative source of truth for the surface
below.

Compatibility with v1: zero breakage. Every v1.1 addition is an optional field or a
clarification on an existing frame - a frame that omits the new fields is exactly a v1 frame
and stays valid in both directions. A v1 reader accepts v1.1 frames and silently drops the
new fields (unknown extra keys, section 1); a v1.1 reader accepts every v1 frame unchanged.
Senders gate the new fields on the peer's announced readiness (rollout notes in sections 2.4
and 2.6): freeze first, then send.

Scope: the wire surface sketched by the portal-first plan
(`~/.nebflow/docs/Nebflow/20261010_portal-first-v3.md`, section 2.7): 13 frames plus the
server endpoint faces of section 7. Where the section 2.7 skeleton and this contract
disagree, this contract wins.

Canonical copies (byte-identical pair, `shasum -a 256` equal at all times):

- Nebflow main repo: `src/test/resources/neblink/portal-wire-contract-v1.1.md`
- neblink-server repo: `.nebflow/Spec/portal-wire-contract-v1.1.md`

Companion vector file (byte-identical pair, same discipline):

- Nebflow main repo: `src/test/resources/neblink/portal-wire-vectors-v1.1.json`
- neblink-server repo: `.nebflow/Spec/portal-wire-vectors-v1.1.json`

Re-freeze discipline: any change to either file bumps the contract version (v1.2, ...), lands
in both repos within one change, and re-verifies byte-identity of both pairs. The main-repo
copy lives under `src/test/resources/neblink/` because the main-repo `.nebflow/` tree is
gitignored and cannot carry a committed truth source; this path choice is the operative one
for judgment criterion 1.

## 1. General envelope

Every frame is a single UTF-8 JSON object per relay WebSocket message and carries two envelope
keys:

- `type` (string, required): frame discriminator. The value is the frame name exactly as listed
  in `## Frame inventory`.
- `proto` (int, required): wire protocol version. v1.1 frames carry `1`. A receiver must reject
  any frame whose `proto` exceeds its supported version (rejection code `proto_unsupported`);
  this rejection has no frame-family exception.

Directions: downlink = server to host; uplink = host to server; bidirectional = both;
host-to-host = between two hosts, relayed through the server.

Encoding baseline:

- Every `data` field is base64, RFC 4648 section 4 standard alphabet, padded.
- UUID fields are lowercase hyphenated 8-4-4-4-12 hex with RFC 4122 version/variant nibbles.
- Unknown `action` on `relay_request` keeps the existing semantics: the host answers with a
  `relay_response` whose `error` equals `"Unknown tool: " + action`.
- An unknown `type` is a contract-drift signal: drop the frame with a diagnostic
  (`unknown_type`). Diagnostics reuse the receiving side's existing redaction face; raw frame
  heads are never logged beyond it.
- A frame that fails validation is dropped with a diagnostic and never partially processed.
- Unknown extra keys are ignored (forward compatibility): a receiver that does not know a key
  decodes the frame without it, and the canonical re-encode emits schema fields only.

Rejection codes (conformance vocabulary): `unknown_type`, `unparseable_json`,
`proto_unsupported`, `missing_required_field`, `type_mismatch`, `invalid_enum`,
`invalid_format`, `invalid_base64`, `field_conflict`.

Freeze decisions on skeleton gaps (registered here so conformance has exactly one answer):

- D1 `projectRoot`: the skeleton preamble cites the existing relay_request `{requestId, action,
  params, projectRoot}` while its field list omits `projectRoot`. Frozen reading:
  `projectRoot` is an optional `relay_request` field, registering current wire behavior.
- D2 uniform envelope: the skeleton rows for the new frames are abbreviated (they omit `type`,
  which dispatch requires), so the envelope keys `type` + `proto` apply to all 13 frames alike.
- D3 `MigrationChunk.sha256` covers the plaintext chunk bytes; the receiving host verifies it
  after AES-GCM decryption.
- D4 the migration orchestration endpoint remains a placeholder (section 3.3).
- D5 the three transport constraint values were placeholders in v1 and are backfilled in v1.1
  from the T-1/T-2 transport measurements (section 4).

Freeze decisions added in v1.1 (each pins one staging-verified behavior so both repos conform
to a single reading):

- D6 uplink terminator (section 2.4): every `http_response` cycle ends with exactly one
  `more=false` `body_frame`, empty body included. Frozen from the phase 3b staging finding F1
  (an empty-body response with zero `body_frame`s hung the browser stream forever because the
  ingress closes only on `more=false`); the host bridge pins exactly one zero-byte terminator
  for a 204.
- D7 downlink bodyless face (section 2.3): a request without a body sends zero `body_frame`s
  and carries no terminator obligation (the defect-5 reading; the host backstops a stalled
  cycle with an implementation-side first-body-frame timeout). The two directions are
  deliberately different since the F1 fix; the v1 sentence they shared is split accordingly.
- D8 Set-Cookie carrier (section 2.4): `setCookie` rides an array of strings, one element per
  occurrence. The v1 `headers` object cannot host repeated `Set-Cookie` headers (unique JSON
  keys; the two repos also disagree on duplicate-key JSON), so the array is the only lossless
  carrier.
- D9 ws_open headers (section 2.6): the WS open face mirrors the HTTP header object so the
  browser session cookie can cross the bridge verbatim.
- D10 transport backfill (section 4): the values are the measured/contractized faces of
  constants both implementations already run, not new policy.

## 2. Frames

### 2.1 relay_request (downlink)

The existing authenticated relay envelope, carried over unchanged plus the `proto` key.

- `type`: literal `"relay_request"`
- `proto`: int, `1`
- `requestId`: uuid
- `action`: string
- `params`: object, action-specific payload
- `projectRoot`: string, optional (D1)

Behavior: the host performs the action and answers with `relay_response` carrying the same
`requestId`. Unknown action => `relay_response.error = "Unknown tool: " + action`. Legacy
parse-side fallbacks for absent fields (empty requestId/action/params, working-directory
projectRoot) are pre-v1 leniency, not contract behavior.

### 2.2 relay_response (uplink)

- `type`: literal `"relay_response"`
- `proto`: int, `1`
- `requestId`: uuid, echoes the relay_request
- `output`: string, JSON text of the action result; empty string when there is no result
- `error`: string, empty on success, failure message otherwise

Compatibility note: the pre-v1 legacy wire emits relay_request/relay_response without `proto`.
Rollout ordering belongs to the deployment batch; it does not license any deviation from this
contract.

### 2.3 http_request (downlink)

Asks the host to perform an HTTP request on its local face on behalf of the portal.

- `type`: literal `"http_request"`
- `proto`: int, `1`
- `requestId`: uuid
- `method`: string, uppercase HTTP method
- `path`: string, request path including the query string
- `headers`: object, lowercased header name -> string value; the cookie header value is carried
  verbatim

The request body, when the request carries one, follows as 1..n `body_frame` frames with the
same `requestId`, the last carrying `more=false`. A request without a body sends ZERO
`body_frame` frames: the downlink direction carries no terminator obligation, and the absence
of a body is expressed by the head frame alone (D7). The host-side first-body-frame timeout
that backstops a stalled downlink cycle is an implementation constant, not a contract value.

### 2.4 http_response (uplink)

- `type`: literal `"http_response"`
- `proto`: int, `1`
- `requestId`: uuid, echoes the http_request
- `status`: int, HTTP status code
- `headers`: object, lowercased header name -> string value; `set-cookie` MUST NOT appear here
  (see below)
- `setCookie`: array of string, optional; each element is one complete `Set-Cookie` header
  value exactly as the host emitted it, in first-seen order (D8). Absent is encoded by
  omission.

Range and partial-content semantics are carried as-is by status + headers (206 with
content-range).

Uplink terminator obligation (D6): the response body follows as 1..n `body_frame` frames with
the same `requestId`, and EVERY `http_response` cycle ends with exactly one terminating
`body_frame` whose `more` is false - including a response whose body is empty, whose
terminator is a single zero-payload frame (`seq` 0, `data` ""). The terminator frame is the
receiver's ONLY signal to close the browser-facing body stream; closing on any other event
(for example when the response head arrives) is a contract violation, not an optimization: it
double-terminates cycles that carry a body.

Set-Cookie relay obligations (D8):

- The host bridge relays `Set-Cookie` as one `setCookie` array element per occurrence;
  comma-joining `Set-Cookie` values into the `headers` object is prohibited.
- `set-cookie` MUST NOT appear in `headers` on a v1.1 sender's frames. A frame that carries
  `set-cookie` inside `headers` together with a `setCookie` array is rejected with
  `field_conflict`.
- The server ingress forwards each `setCookie` element as its own `Set-Cookie` response header,
  order preserved, no merging.
- Legacy tolerance: a v1-era frame carrying a single `set-cookie` entry inside `headers`
  remains acceptable to receivers (single-value semantics are loss-free); senders must not
  produce it.

No mid-cycle cancel: the wire defines no cancel frame for an in-flight HTTP cycle. When the
browser side disconnects, the cycle runs to completion on the host and its frames are dropped
at the ingress; a cancel frame would be a future versioned addition, not a v1.x field edit.

Rollout: a receiver that predates v1.1 accepts an `http_response` carrying `setCookie` but
silently drops the field (unknown extra keys, section 1), so relayed session cookies vanish.
Set-Cookie relay goes live only after both ends run v1.1 codecs (freeze first, then send).

### 2.5 body_frame (bidirectional)

Streams one HTTP body (request or response direction) for a requestId.

- `type`: literal `"body_frame"`
- `proto`: int, `1`
- `requestId`: uuid, links to the http_request / http_response cycle
- `seq`: int, 0-based, increments by 1 per direction per requestId
- `more`: bool, true means at least one further body_frame follows, false marks the last frame
- `data`: base64 payload slice

Ordering: `seq` is a 0-based in-cycle ordinal. A `body_frame` with a negative `seq` is
rejected per frame (`invalid_format`). A `body_frame` whose `seq` duplicates or regresses
within an in-flight direction is a stream-level violation: the receiver drops the whole cycle
with a diagnostic and never delivers a partial body.

### 2.6 ws_open (downlink)

Asks the host to open a WebSocket connection to a local endpoint on behalf of the portal.

- `type`: literal `"ws_open"`
- `proto`: int, `1`
- `requestId`: uuid
- `path`: string, absolute path (leading `/`) of the host-side WS endpoint; the path may carry
  a query string, which the host appends to the local upgrade request verbatim; v1 faces:
  `/ws` and `/api`
- `subprotocol`: string, optional, WebSocket subprotocol to request
- `headers`: object, optional, lowercased header name -> string value; the cookie header value
  is carried verbatim (D9). Absent is encoded by omission; schema order places it after
  `subprotocol`.

Rollout: a receiver that predates v1.1 accepts a `ws_open` carrying `headers` but silently
drops the field (unknown extra keys, section 1), so the handshake proceeds without the bridge
headers and session-cookie crossing silently degrades. Tunnel WS sessionization goes live only
after both ends run v1.1 codecs (freeze first, then send).

### 2.7 ws_open_result (uplink)

- `type`: literal `"ws_open_result"`
- `proto`: int, `1`
- `requestId`: uuid, echoes the ws_open
- `accepted`: bool
- `status`: int, HTTP status of the upgrade attempt

### 2.8 ws_frame (bidirectional)

- `type`: literal `"ws_frame"`
- `proto`: int, `1`
- `requestId`: uuid, links to the ws_open cycle
- `kind`: enum, `text` | `binary`
- `data`: base64 frame payload
- `fin`: bool, RFC 6455 final-fragment bit

### 2.9 ws_close (bidirectional)

- `type`: literal `"ws_close"`
- `proto`: int, `1`
- `requestId`: uuid
- `code`: int, RFC 6455 close code
- `reason`: string

### 2.10 ConsoleTicketMint (downlink)

Sent over the existing authenticated relay channel after the server verified the caller's
Logto access token and the account-to-host binding (owner relation).

- `type`: literal `"ConsoleTicketMint"`
- `proto`: int, `1`
- `requestId`: uuid
- `mintId`: uuid, server-generated mint cycle id
- `accountSub`: string, Logto account subject the ticket binds to
- `reqOrigin`: enum, v1 single value `portal`

Behavior anchors: the ticket string is generated on the host (at least 128 random bits) and
stored in the host ticket store (scope console; bound host, accountSub, origin relay-mint);
one-time use, TTL at most 120 s (LAN fast leg at most 30 s), redeemed-then-destroyed. The
mint-round-trip timeout the server applies is an implementation constant, not a contract
value; the TTL ceiling above is the contract face.

### 2.11 ConsoleTicketResult (uplink)

- `type`: literal `"ConsoleTicketResult"`
- `proto`: int, `1`
- `requestId`: uuid, echoes the ConsoleTicketMint
- `mintId`: uuid, echoes the ConsoleTicketMint
- `ticket`: string, optional, present if and only if the mint succeeded
- `expiresInSec`: int, optional, present if and only if the mint succeeded, at most 120
- `error`: enum `rate_limited` | `quiesced` | `unbound`, optional, present if and only if the
  mint failed

The success shape (ticket + expiresInSec, no error) and the failure shape (error only) are
mutually exclusive; a frame carrying both sides is rejected with `field_conflict`.

### 2.12 BindingControl (downlink)

- `type`: literal `"BindingControl"`
- `proto`: int, `1`
- `kind`: enum `revoked` | `stand-down` | `quiesce` | `quiesce-renew` | `quiesce-lift`
- `migrationId`: uuid, optional, present when the control belongs to a migration
- `ttlSec`: int, optional, quiesce TTL; applies to kind `quiesce` / `quiesce-renew`

Semantics anchors (policy, not wire): `revoked` invalidates all sessions and unspent tickets of
the binding; `stand-down` makes the daemon leave the host role while keeping its data
directory; `quiesce` defaults to a 3600 s TTL renewed by the orchestrator roughly every 300 s
over relay, and an expired unrenewed quiesce self-lifts with one audit line.
`quiesce-renew` extends the window of an existing quiesce with a fresh `ttlSec`; it is a
renewal, not a new quiesce, and carries no side effects beyond the extension.
`quiesce-lift` ends the quiesce window early; an explicit lift and a TTL expiry converge on the
same state, and both leave one audit line.

Rebinding propagation (what a guarded seat transfer does to the displaced host's sessions and
unspent tickets) is NOT defined in v1.1; it is deferred to a v1.x backfill tied to the
rebinding-guard ruling (section 8). Until backfilled, `revoked` semantics are frozen for
unbind only and must not be read as settling the rebinding case.

### 2.13 MigrationChunk (host-to-host, relayed via server)

- `type`: literal `"MigrationChunk"`
- `proto`: int, `1`
- `migrationId`: uuid
- `manifestPath`: string, migration manifest path both hosts agreed on
- `chunkIndex`: int, 0-based chunk ordinal
- `sha256`: string, 64 lowercase hex chars, digest of the plaintext chunk bytes (D3)
- `data`: base64, AES-GCM ciphertext of the chunk payload

Anchors: a migration session starts with an ECDH key exchange relayed through the server;
chunks are on the order of 64 MiB with per-chunk sha256 and a resumable sender-side cursor.
Policy values live with the migration orchestrator; only the field shapes above are frozen
here.

## 3. Server endpoints

### 3.1 POST /api/portal/console-ticket

Authentication: `Authorization: Bearer <Logto access token>`. The server verifies the token
(JWKS verification face) and that the token's account holds the target host (binding owner
relation) before minting.

Request body (JSON):

```json
{
  "deviceId": "<host device id string>"
}
```

200 response (JSON):

```json
{
  "ticket": "<one-time console ticket string>",
  "expiresInSec": 30,
  "consoleUrl": "<console entry URL string>"
}
```

4xx response (JSON), `error` is one of:

- `not_bound`: the account does not hold the target host
- `host_offline`: the target host is not connected to the relay
- `link_unstable`: the relay link to the host is degraded
- `rate_limited`: the mint rate budget is exhausted

### 3.2 GET /api/portal/status (incremental fields)

Fields added to the existing status endpoint response:

- `hostPresence`: enum `alive` | `stale` | `gone`
- `heartbeatAgeSec`: int, seconds since the last host heartbeat
- `lanCandidates`: array of objects `{ "ip": string, "uiPort": int }`

Consumed by the direct-LAN probing face (plan section 5.4); these fields carry no credentials.

### 3.3 Migration orchestration endpoint - PLACEHOLDER (backfill pending)

NOT DEFINED in v1.1. The path, method, and request/response shapes - including the
confirmation-code submission face of the out-of-band confirmation chain - are deferred to the
phase 5 contract freeze.

Backfill discipline:

- This section must be backfilled by a contract re-freeze (v1.x) before phase 5 implementation
  task books open.
- The backfill lands in both repo copies within one change and re-verifies byte-identity
  (`shasum -a 256` equal).
- Until backfilled, this placeholder must not be read as an implicit definition; nothing here
  may be treated as settled.

## 4. Transport constraints (backfilled from T-1/T-2 in v1.1)

- Max frame size: 256 KiB. A sender slices an HTTP body into `body_frame` payloads of at most
  256 KiB; this is the contract face of the chunking constants both implementations already run
  (the server ingress `DEFAULT_PORTAL_BODY_CHUNK_BYTES` and the host bridge `BodyChunkBytes`,
  both 256 x 1024 bytes). The host-side reassembly guards (an 8 MiB frame cap and a 30 s
  reassembly deadline) remain implementation constants, not contract values.
- Heartbeat interval: 3 s. This is the relay-link liveness ping interval; it is landed together
  with the liveness-retuning batch, which moves the server ping interval, the missed-pong
  threshold, and the client heartbeat as one set - the miss window must keep a 2x margin over
  the client heartbeat interval (one delayed heartbeat must not flip a live link offline), and
  the silent-partition detection line (2 x interval + phase + close propagation) must stay
  within the 10 s close-propagation budget. Until that batch lands, the code-side values remain
  the previous implementation constants; this section is the contract target they converge on.
- Backpressure policy: per-cycle queues are bounded; when a queue overflows, the receiver drops
  that request/response cycle with a diagnostic and the browser gets a readable 4xx/5xx; the
  relay read loop never blocks.

Discipline: terminal/timeout status codes and the head/body/WS-idle timeout constants are
implementation constants, not contract values. These three fields were backfilled from the
T-1/T-2 staging measurements (phase 3c batch 4); any later change lands as a v1.x re-freeze
with both repo copies byte-identical.

## 5. Coexisting frames (outside this freeze)

The relay channel also carries existing frames that stay outside this contract and unchanged:
`ping`, `pong`, `ack` (shape `{"type":"ack","eventId":...}`, frozen byte-for-byte against the
neblink-server relay face), `disconnect`, `device_status_update`, `friend_event`. New frame
names must not collide with these. `agent_mail` is a retired tombstone (drop with a diagnostic;
it must not be revived).

## 6. Conformance vector encoding

The companion file `portal-wire-vectors-v1.1.json` carries, for every frame in the inventory,
at least one positive and one negative vector:

- positive: `json` = the field-level frame object; `wire` = the expected canonical
  serialization (keys in schema order as documented above, compact separators, base64 per
  section 1).
- negative: `json` (or `raw` for wire-level garbage) plus `expect`, naming the rejection code
  and the offending field.

The 28 frozen v1 vectors are preserved byte-for-byte in v1.1; v1.1 appends vectors for the new
optional fields (ws_open `headers`, http_response `setCookie`, each with a positive, a
negative, and a field-absent compatibility vector) and for the newly pinned clarifications
(proto above supported, ws_open path with query string, negative seq, unknown-extra-keys
tolerance) - 40 vectors total.

Both repos must run their conformance suites against the same vector file (the byte-identical
copies above) and agree byte-for-byte on the verdicts.

## 7. Endpoint inventory

endpoint: POST /api/portal/console-ticket
endpoint: GET /api/portal/status
endpoint: GET /api/portal/console-ws (browser console WS ingress over the relay bridge)
endpoint: GET /ws (console-source bridge alias; same gate and pump as console-ws)
endpoint: PLACEHOLDER migration-orchestration (backfill pending, section 3.3)

## 8. Deferred items (registered for later re-freezes)

- v2 binary carriage (a binary framing mode for high-volume tunnel faces) is out of v1.1
  scope; it would be a versioned addition, not a v1.x field edit.
- Rebinding propagation semantics (section 2.12) are deferred to a v1.x backfill tied to the
  rebinding-guard ruling; nothing in this contract settles them.

## Frame inventory

frame: relay_request
frame: relay_response
frame: http_request
frame: http_response
frame: body_frame
frame: ws_open
frame: ws_open_result
frame: ws_frame
frame: ws_close
frame: ConsoleTicketMint
frame: ConsoleTicketResult
frame: BindingControl
frame: MigrationChunk
