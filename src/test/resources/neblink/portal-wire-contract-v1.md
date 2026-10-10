# Portal wire contract v1 (phase 0.5 freeze)

Version: v1, frozen 2026-10-10.

Scope: the wire surface sketched by the portal-first plan
(`~/.nebflow/docs/Nebflow/20261010_portal-first-v3.md`, section 2.7): 13 frames plus 3 server
endpoint faces. This file is the single source of truth for that surface. Where the section 2.7
skeleton and this contract disagree, this contract wins. Until phase 0.5 closes on the four
acceptance criteria of the phase book (plan section 8), phase 3 and phase 5 implementation task
books must not open.

Canonical copies (byte-identical pair, `shasum -a 256` equal at all times):

- Nebflow main repo: `src/test/resources/neblink/portal-wire-contract-v1.md`
- neblink-server repo: `.nebflow/Spec/portal-wire-contract-v1.md`

Companion vector file (byte-identical pair, same discipline):

- Nebflow main repo: `src/test/resources/neblink/portal-wire-vectors-v1.json`
- neblink-server repo: `.nebflow/Spec/portal-wire-vectors-v1.json`

Re-freeze discipline: any change to either file (including the pending backfills in sections
3.3 and 4) bumps the contract version (v1.1, v1.2, ...), lands in both repos within one change,
and re-verifies byte-identity of both pairs. The main-repo copy lives under
`src/test/resources/neblink/` because the main-repo `.nebflow/` tree is gitignored and cannot
carry a committed truth source; this path choice is the operative one for judgment criterion 1.

## 1. General envelope

Every frame is a single UTF-8 JSON object per relay WebSocket message and carries two envelope
keys:

- `type` (string, required): frame discriminator. The value is the frame name exactly as listed
  in `## Frame inventory`.
- `proto` (int, required): wire protocol version. v1 frames carry `1`. A receiver must reject
  any frame whose `proto` exceeds its supported version (rejection code `proto_unsupported`).

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
- Unknown extra keys are ignored (forward compatibility).

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
- D4 the migration orchestration endpoint remains a placeholder in v1 (section 3.3).
- D5 the three transport constraint values remain placeholders until T-1 reports (section 4).

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

The request body, when present, follows as 0..n `body_frame` frames with the same `requestId`.

### 2.4 http_response (uplink)

- `type`: literal `"http_response"`
- `proto`: int, `1`
- `requestId`: uuid, echoes the http_request
- `status`: int, HTTP status code
- `headers`: object, lowercased header name -> string value

Range and partial-content semantics are carried as-is by status + headers (206 with
content-range). The response body, when present, follows as 0..n `body_frame` frames with the
same `requestId`.

### 2.5 body_frame (bidirectional)

Streams one HTTP body (request or response direction) for a requestId.

- `type`: literal `"body_frame"`
- `proto`: int, `1`
- `requestId`: uuid, links to the http_request / http_response cycle
- `seq`: int, 0-based, increments by 1 per direction per requestId
- `more`: bool, true means at least one further body_frame follows, false marks the last frame
- `data`: base64 payload slice

### 2.6 ws_open (downlink)

Asks the host to open a WebSocket connection to a local endpoint on behalf of the portal.

- `type`: literal `"ws_open"`
- `proto`: int, `1`
- `requestId`: uuid
- `path`: string, absolute path (leading `/`) of the host-side WS endpoint; v1 faces: `/ws`
  and `/api`
- `subprotocol`: string, optional, WebSocket subprotocol to request

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
one-time use, TTL at most 120 s (LAN fast leg at most 30 s), redeemed-then-destroyed.

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

NOT DEFINED in v1. The path, method, and request/response shapes - including the
confirmation-code submission face of the out-of-band confirmation chain - are deferred to the
phase 5 contract freeze.

Backfill discipline:

- This section must be backfilled by a contract re-freeze (v1.x) before phase 5 implementation
  task books open.
- The backfill lands in both repo copies within one change and re-verifies byte-identity
  (`shasum -a 256` equal).
- Until backfilled, this placeholder must not be read as an implicit definition; nothing here
  may be treated as settled.

## 4. Transport constraints - PLACEHOLDER (backfill pending T-1)

- Max frame size: PLACEHOLDER (backfill pending T-1)
- Heartbeat interval: PLACEHOLDER (backfill pending T-1)
- Backpressure policy: PLACEHOLDER (backfill pending T-1)

Discipline: the values are backfilled from the T-1 transport measurements as a v1.1 re-freeze,
with both repo copies kept byte-identical. Until then these three fields are explicitly
unsettled and must not be treated as fixed. The host's current implementation-side guards (an
8 MiB frame-reassembly cap and a 30 s reassembly deadline) are implementation constants, not
contract values.

## 5. Coexisting frames (outside this freeze)

The relay channel also carries existing frames that stay outside this contract and unchanged:
`ping`, `pong`, `ack` (shape `{"type":"ack","eventId":...}`, frozen byte-for-byte against the
neblink-server relay face), `disconnect`, `device_status_update`, `friend_event`. New frame
names must not collide with these. `agent_mail` is a retired tombstone (drop with a diagnostic;
it must not be revived).

## 6. Conformance vector encoding

The companion file `portal-wire-vectors-v1.json` carries, for every frame in the inventory, at
least one positive and one negative vector:

- positive: `json` = the field-level frame object; `wire` = the expected canonical
  serialization (keys in schema order as documented above, compact separators, base64 per
  section 1).
- negative: `json` (or `raw` for wire-level garbage) plus `expect`, naming the rejection code
  and the offending field.

Both repos must run their conformance suites against the same vector file (the byte-identical
copies above) and agree byte-for-byte on the verdicts.

## 7. Endpoint inventory

endpoint: POST /api/portal/console-ticket
endpoint: GET /api/portal/status
endpoint: PLACEHOLDER migration-orchestration (backfill pending, section 3.3)

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
