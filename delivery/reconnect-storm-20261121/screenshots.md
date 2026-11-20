# Screenshots — restart reconnect storm

- **Viewport:** 390 × 844 CSS px (mobile, matching the phone the author reported
  from), device scale factor 2 (`isMobile`/`hasTouch`), light theme, one browser
  window. Real pixel size of both files is **780 × 1688** (2× DPR); both are
  stills, measured with a PNG IHDR read.
- **Scenario:** one isolated engine instance (`--home <mktemp>` data root, port
  8177 — the production instance on 6138 and the host gateway on 8080 were never
  touched), seeded with a **read-only copy of this machine's production data
  root** (`chains/` 2328 files / 12 MB, `delegates/` 1062 records, seats
  rewritten into the isolated root so the world-seat filter admits them). One
  console session, two hops: the cold hop arrives with a freshly minted
  one-shot console ticket (loopback leg) and the reconnect hop re-opens the
  console over the session cookie it exchanged — i.e. the shape of a phone
  reconnect. `before.png` serves the **base commit bytes** of
  `src/main/resources/web/js/delegateChainProvider.js`
  (`git show ad1d32d78:<path>`, parked as `../evidence/provider-before-main.js`);
  `after.png` serves this branch's bytes of the same file. Nothing else is
  substituted between the two legs — the rest of the front end is byte-identical
  in both.
- **Fixture or command:**
  `node delivery-shots.mjs http://127.0.0.1:8177 "$(cat <isolated>/auth.json)" \
   ../evidence/provider-before-main.js <branch>/src/main/resources/web/js/delegateChainProvider.js .`
  — Playwright/Chromium, the provider module served through a route handler, a
  fresh browser **context per hop** with CDP cache disabled (`Network.setCacheDisabled`),
  and a hard assertion that each hop intercepted the module exactly once (otherwise
  the harness refuses to report readings, so a leg can never silently run the
  wrong bytes). Raw numbers in `readings.json`.

## Real pixel measurements

| File | px | format |
| --- | --- | --- |
| `before.png` | 780 × 1688 (390 × 844 @2x) | still PNG |
| `after.png` | 780 × 1688 (390 × 844 @2x) | still PNG |

## Measured `/api` traffic (same two hops, same data, same engine)

| Leg | Cold load | Reconnect |
| --- | --- | --- |
| `before.png` (base bytes) | **604** requests — 282 `/api/chains/<key>` + 282 `/children` (+ seed reads) | **603** — 282 + 282 again |
| `after.png` (branch bytes) | **125** requests — 2 `/api/delegates` + 2 `/api/chain-digests` + 48 details + 32 child legs | **125** — no fan-out, no replay |

(The console opens two sessions' worth of preload on a first boot, hence two row
faces and two digest faces per hop. Both legs are counted identically, so the
comparison is per-hop on equal terms.)

The dashed caption strip at the bottom of each image is added by the evidence
harness (annotation, not product UI) and states the same per-hop total.

## What these screenshots do **not** prove

- They are not the author's phone. `link.mystand.space` needs the author's phone
  and portal session — that acceptance stays open in `tasks.md`.
- The isolated instance is a first-boot data root, so the console's first screen
  is the onboarding wizard, not the author's populated 1108-row panel. What the
  screenshots do show is that the console shell renders normally on both legs,
  with **no** 「连接断开 · 正在重连」 banner and the plugin panel populated from
  live `/api` responses.
- The request-count difference is the load shape being fixed; it is evidence of
  the storm's removal, not a wall-clock benchmark of the author's device.
