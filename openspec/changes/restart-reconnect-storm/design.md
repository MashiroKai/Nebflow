# restart-reconnect-storm — design

## The failure, stated as a shape

```
console boot / reconnect
  → GET /api/delegates            (1)
  → Promise.all( GET /api/chains/<key>   for every row key        )   ← 1108
  → Promise.all( GET /api/delegates/<id>/children for every row   )   ← 1108
```

The engine counts every one of those through the tunnel guard's per-envelope-IP
bucket. `tunnelPerIpCap` was 1024/60s, so the round's tail was answered 429 —
**1385 of them in the production log, all `GET /api/chains/<key>`**. The client
treated a 429 as "no reading" and the next WS frame (the panel receives many)
re-entered the same round: `tunnelReq` 1431 → 862 → 528 → 1124 → 1718 across
consecutive windows with `tunnelRejected` frozen at 691. Nothing converges,
because the client asks for more than the window admits and never shrinks its
ask.

Two fixes, at the two ends, and neither is sufficient alone:

- With only the engine fix (raise the cap) the client would simply move its
  storm to a higher threshold — the pattern is unbounded in the row count.
- With only the client fix (batch + budget) a legitimate first paint on a larger
  installation would still be refused, because the budget was derived from
  static-asset arithmetic rather than from the data plane.

## Client: rounds with a budget

`refresh()` becomes a fixed sequence with a bounded request count:

1. `GET /api/delegates` (row projection; `ETag` already served ⇒ unchanged is a
   cheap `304`). Fallback to `/api/chains` when the row shape is absent (old
   engine), exactly as before.
2. `GET /api/chain-digests` — one request for **all** keys. `?since=` makes the
   unchanged case a 57-byte reply.
3. Decide, per key: uncached ∨ digest moved ∨ (active row ∧ past `DETAIL_TTL_MS`)
   ⇒ candidate. Active rows first, the rest ordered by "least recently fetched"
   so no tail row starves. Candidates are sliced to `DETAIL_BUDGET = 24` and the
   child legs to `CHILD_BUDGET = 16`.
4. Execute candidates through `withSlot` (shared semaphore, `MAX_INFLIGHT = 4`).

Steady state on a warm mirror with an unchanged digest ⇒ rows + digests only,
**zero** detail reads. A cold first paint is one row request + one digest request
+ ≤24 details instead of 2217 requests.

`getChainByTask` misses are served from a **lazy queue** with a 50ms gap
(`enqueueLazy` / `drainLazy`) instead of an immediate `fetchChainLazy` per render.

### Retry discipline

Backoff is keyed by **route class** (`rows` / `digests` / `chain` / `children`) —
a per-URL map grows with the key set and is the wrong granularity for a storm
that is defined by a window, not by a URL. Delay = `max(Retry-After, exp(500ms,
cap 60s) × jitter(0.75…1.25))`; the `attempts` counter feeds the exponent. Hits
inside the window return `RATE_LIMITED` **without issuing a request**. The first
429 on a detail/child leg opens a round-level gate so the remaining legs of that
round stand down instead of each discovering the wall. `onReconnect` clears the
buckets (new network path) but keeps the mirror and the digests — the whole point
is that the rebuild reuses what it has.

### Broadcast discipline (a constraint discovered while implementing)

The mirror's `delegate-chain-mirror-updated` event drives a full panel re-render,
and two existing tests (`scripts/tests/subagent-visibility.test.mjs`) assert that
an unchanged poll notifies nothing. Because the new round issues a *budgeted* set
of reads rather than the whole set, "we made requests" no longer implies "something
changed" — so the broadcast now requires a real delta: rows differ, the digest set
is non-empty, or at least one detail/child leg actually wrote a new reading
(`refreshChain` / `refreshChildren` return whether the mirror moved).

## Engine: a budget sized for the data plane, and a summary face sized for the client

- `DefaultTunnelPerIpCap` 1024 → 4096, `DefaultTunnelTotalCap` 10240 → 40960
  (still finite, still counted, no loopback exemption — an envelope value is not a
  trust root, the existing ruling stands).
- New `DefaultTunnelChainsPerIpCap = 2048`, supplied by `ConnGuardConfig.fromEnv`
  so the production default lives at the single assembly entry rather than in the
  case class (which keeps `None` = "the caller decides", and keeps every explicit
  construction in the specs byte-identical in meaning).
- Startup line and `snapshotLine` / `healthJson` now carry the effective per-class
  caps (`tunnelCaps=perIp/chains/files/other`), because the old line printed none
  and triage had to reverse-engineer them.
- `ChainStore.digestsSync` — one directory pass, `sha256(file bytes)` per key,
  oversized/corrupt files skipped (absence = "unknown", the client then reads the
  detail — never a deletion authority).
- `ChainsReadFace.chainDigestsJson(since)` — the payload above; `PresenceRoutes`
  mounts it at `GET /api/chain-digests` (its own segment so it cannot shadow
  `/api/chains/:key`) behind the same `withAuth` + `HttpCache.conditionalOk`.

Measured on an isolated instance seeded with the production data (2328 chain
files, 12 MB): full digest 0.12 s warm / 0.43 s cold, 64519 B; `?since=` hit 57 B;
`If-None-Match` → `304`.

## What this deliberately does not change

- The tunnel face's loopback exemption and its "strictly above the cap" semantics
  (both are documented rulings with tests).
- The 429 body shape and its `Retry-After` header (`PortalTunnelBridgeSpec`).
- `PortalHostFaces.TunnelGuard`'s shape; the bridge still observes then checks.
- The rejection log line (the author asked to keep it — it is the only forensic
  record of *which* path class is being refused).
