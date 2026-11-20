# Restart reconnect storm — what changed and why

## What

Two ends of one failure chain, both touched.

**Client (`src/main/resources/web/js/delegateChainProvider.js`)** — the console's
chain provider no longer replays `GET /api/chains/<key>` once per row:

| Concern | Before | After |
| --- | --- | --- |
| Round shape | 1 row request + `Promise.all` over **every** row key + **every** row's `/children` | 1 row request + 1 bulk digest request + a **budgeted** detail round |
| Requests per round (prod shape: 1108 rows / 2328 keys) | 2217 | 42 |
| Concurrency | unbounded | shared semaphore, `MAX_INFLIGHT = 4` |
| Per-round budget | none | `DETAIL_BUDGET = 24`, `CHILD_BUDGET = 16`, active rows first, tail rotates by "least recently fetched" |
| 429 handling | per-URL backoff map (grows with the key set; entry read before written) | per **route class**, exponential + ±25% jitter, `Retry-After` as a floor, no request inside the window; a rate-limited round stands its remaining legs down |
| Cache across reconnect | rebuilt from scratch | mirror + digests kept; reconnect clears only the backoff buckets |
| Cache miss (`getChainByTask`) | immediate fetch on every render | lazy queue, 50 ms gap, single-flight |
| Mirror-updated broadcast | fired whenever a round completed | fires only on a real delta (rows changed ∨ digest set non-empty ∨ a detail/child leg wrote a new reading) |

**Engine (`connguard.scala`, `ChainStore.scala`, `ChainsReadFace.scala`,
`PresenceRoutes.scala`)**:

- `DefaultTunnelPerIpCap` 1024 → **4096**, `DefaultTunnelTotalCap` 10240 → **40960**
  (per 60 s window; still finite, still counted, loopback still not exempt).
- New `DefaultTunnelChainsPerIpCap = 2048` — the `chains` class keeps its own,
  tighter bucket (its per-request cost is the highest) and the production default
  is supplied from the single `fromEnv` assembly point, not from a case-class
  default that would create a second source of truth.
- `GET /api/chain-digests` — one request summarises **every** chain key
  (`{v, fingerprint, count, digests:{key: sha16}}`), `?since=<fingerprint>` gives
  a body-less `{unchanged:true}`, and `ETag`/`If-None-Match`/`304` reuse
  `HttpCache`. Cost on the production data (2328 files / 12 MB): 0.12 s warm,
  0.43 s cold, 64519 B; `since` hit = 57 B.
- Startup line, periodic snapshot line and `healthJson` now print every effective
  tunnel cap (`tunnelCaps=perIp/chains/files/other`) — the old line printed none,
  so triage had to reverse-engineer them.

## Why

The author's phone console sat on 「连接断开 · 正在重连 / 加载中」 with jank after
a restart (2026-11-21 01:40). The engine log explains it exactly:

- **1385** tunnel-guard rejections, reason `tunnel-per-ip-cap`, and **every one**
  of them `GET /api/chains/<key>` — no other path.
- conn-guard snapshots: `tunnelReq` 1431 → 862 → 528 → 1124 → 1718 with
  `tunnelRejected` 0 → 530 → 691 → (frozen at 691 for eight windows) → 1385.
  That is a client in a tight retry loop against a fixed window.

Neither half alone is fixable in isolation: raising only the engine budget moves
the storm to a higher threshold (it scales with the row count), and fixing only
the client still leaves a *legitimate* first paint refused by a budget that was
derived from static-asset arithmetic rather than from the data plane.

## Impact

- **Recovery**: a reconnect costs two requests when nothing moved, and 42 in the
  worst case instead of 2217 — a 98% reduction in tunnel traffic on the author's
  actual row/key counts, and the client can no longer fan out past 4 concurrent
  detail reads.
- **Latency/UI**: the chain list can complete in one round instead of a storm the
  tunnel guard interrupts; the panel is no longer re-rendered by empty polls.
- **Blast radius**: the tunnel face's documented rulings are untouched (no
  loopback exemption, "strictly above the cap" admission, rejection log kept,
  429 keeps its `Retry-After` header). The 429 body shape and
  `PortalHostFaces.TunnelGuard` are unchanged, so `PortalTunnelBridgeSpec` stays
  green. Installations without the new digest route (older engines) degrade to
  the previous behaviour minus the storm: the budgeted, capped round still
  applies, driven only by "uncached / active row due".
- **New public route**: `GET /api/chain-digests` (authenticated, read-only, no
  writes, no new authority — it hashes file bytes and reports *whether* a key
  changed; the row and detail faces remain the only readings).
