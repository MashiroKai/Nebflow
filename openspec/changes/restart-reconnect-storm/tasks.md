## Implementation and acceptance

- [x] Engine: give the tunnel face a data-plane-calibrated default and an explicit
      `chains` class bucket, supplied from the single `fromEnv` assembly point.
- [x] Engine: print the effective tunnel caps on the startup line and carry the
      per-class caps in `snapshotLine` / `healthJson` (triage must not reverse-engineer them).
- [x] Engine: add `ChainStore.digestsSync` (single pass, no JSON parse) and
      `ChainsReadFace.chainDigestsJson(since)`, mounted as `GET /api/chain-digests`
      behind `withAuth` + `HttpCache.conditionalOk`.
- [x] Client: rewrite `refresh()` into one row request + one digest request + a
      budgeted, active-first detail round (`DETAIL_BUDGET` / `CHILD_BUDGET`) under a
      shared concurrency cap.
- [x] Client: serve `getChainByTask` misses from a rate-limited lazy queue instead of
      an immediate per-render fetch.
- [x] Client: per-route-class exponential backoff with `Retry-After` as a floor and
      jitter; a rate-limited round stands its remaining legs down; reconnect keeps the
      mirror and clears only the buckets.
- [x] Client: broadcast the mirror-updated event only on a real delta (rows, digests,
      or a written detail/child reading) so unchanged polls stay silent.
- [x] Tests: `tests/delegate-chain-bulk-load.test.mjs` (one row + one digest per round,
      budget caps, cache reuse, zero detail replays on a steady-state reconnect, no
      tight retry loop under 429, old-engine fallback), registered in the static profile.
- [x] Tests: `ConnGuardSpec` — production-default calibration, the `chains` bucket being
      its own bucket, per-class caps surfacing in `snapshotLine` / `healthJson`.
- [x] Tests: `ChainsReadFaceSpec` — the digest face covers every key in one payload, the
      fingerprint is idempotent, `?since=` yields the body-less unchanged reply, and only
      the touched key's digest moves.
- [x] Gates: `sbt compile`; `npm run check:static` (83/83); targeted `sbt testOnly` for
      `ConnGuardSpec` / `InfraUnitSpec` / `ChainsReadFaceSpec` / `PortalTunnelBridgeSpec`.
- [x] Evidence: before/after engine-log excerpt, deterministic old-vs-new replay counter
      on the production row/key counts, isolated-instance digest timings, real-browser
      request counts and console screenshots.
- [ ] Author acceptance on a real phone reconnect (`link.mystand.space`, post-restart):
      `tunnelRejected` ≈ 0 through the recovery window and the panel completes in
      seconds. Cannot be produced from this node — it needs the author's phone and
      portal session; the fixes are measured server-side and in a real browser instead.
