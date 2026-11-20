# restart-reconnect-storm

## Why

Restarting the engine left the author's phone console stuck on
「连接断开 · 正在重连 / 加载中」 with slow, janky interaction (measured
2026-11-21 01:38 through `link.mystand.space`). The engine log was unambiguous:
the console replayed `GET /api/chains/<key>` **once per chain key** on every
reconnect (the N+1 pattern — 1108 delegate rows on the production data root), the
tunnel guard refused the burst on its per-envelope-IP budget, and **1385 requests
were answered 429** (all of them `GET /api/chains/<key>`, reason
`tunnel-per-ip-cap`). The client then retried in a tight loop — the conn-guard
snapshot shows `tunnelReq` at 1431 in a single 60s window, climbing again to 1718
after the next reconnect. A chain list that can never finish loading is what the
author saw as a permanent spinner plus jank.

Two independent defects produce it, and both are in scope:

1. **The client fans out.** `delegateChainProvider.refresh()` fires
   `Promise.all` over *every* row key plus *every* internal row's `/children`
   leg, has no global concurrency cap, no per-round budget and no jitter on
   retry (its backoff map is keyed per URL, so it grows with the key set and its
   stored entry is read *before* being written — the hit test is confused).
2. **The engine's tunnel budget is calibrated for a different load.** The
   1024-requests-per-window default was derived from "two static asset trees plus
   bootstrap", not from the data plane: a single legitimate cold console load of
   this installation's 1108 rows already sits at the threshold, so the *legal*
   first paint is self-refused. The startup line never printed the tunnel caps at
   all, so triage had to reverse-engineer them from `/api/health`.

## What Changes

- **A bulk digest face on the read side** (`GET /api/chain-digests`): one request
  returns `{v, fingerprint, count, digests: {key: sha256-prefix}}` for every chain
  key (single pass over the chain directory, no JSON parsing, no write). A caller
  that already holds the fingerprint asks with `?since=<fingerprint>` and gets
  `{v, fingerprint, unchanged: true}` — the zero-delta steady state. `ETag` /
  `If-None-Match` / `304` reuse the existing `HttpCache` discipline.
- **The console polls in rounds, not per key**: one row-list request, one digest
  request, then detail reads only for keys that are (a) uncached, (b) whose digest
  moved, or (c) active rows past a TTL — under a per-round budget (24 details /
  16 child legs) and a shared concurrency cap (4). The mirror is reused across
  reconnects; only the visible delta is refreshed. 429 now backs off per **route
  class** (not per URL) with exponential delay, `Retry-After` as a floor, and
  ±25% jitter, and a rate-limited round stops issuing further detail reads
  instead of retrying into the wall. A reconnect clears the backoff buckets but
  never the mirror.
- **The tunnel budget is configurable and honest about its classes**: the default
  per-envelope-IP budget rises to 4096 per window (total 40960), the `chains`
  class gets its own 2048 default (tighter than the general bucket because its
  per-request cost is the highest), and the startup line plus the conn-guard
  snapshot/health readings now carry every effective tunnel cap. The loopback
  exemption stays absent on this face, the "strictly above the cap" admission
  semantics stay, the rejection log stays, and the 429 answer keeps its
  `Retry-After` header.
- **Gates that would have caught it**: a deterministic replay counter compares the
  old and new provider on the production row/key counts, and a committed test
  pins the four invariants (one row request + one digest request per round,
  cache reuse inside the TTL, zero detail requests on a steady-state reconnect,
  no tight retry loop under 429).
