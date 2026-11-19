## Implementation

- [x] Extract the zoom arithmetic / closure bounds / classifier / fit-state gesture table into `js/mediaZoom.js` (pure, zero-DOM).
- [x] Make `galPaint()` the single writer of the media element's `transform` and compose the shipped drag offsets as a separate gesture layer (fixes "swipe erases zoom").
- [x] Phone: multi-pointer pinch pipeline with the finger midpoint as anchor and the midpoint motion composed as pan; single-finger pan when zoomed (rubber band + release clamp + pinned axes); over-pan past the axis edge pages to the neighbouring image.
- [x] Phone: pinching below the minimum springs back to fit-screen (chosen over "close", see the spec's scenario for the reason).
- [x] Desktop: plain wheel zoom anchored at the cursor; `ctrl`/`meta` wheel (trackpad pinch) zoom; pixel-step / `deltaX` wheel pan; double-click fit ↔ 2× anchored at the click point (decided by hit test, not event target).
- [x] Video: same zoom state; the native control band (bottom 56 px) is never claimed; a zoomed drag pans the video and swallows its trailing click.
- [x] Hold the 200 ms transform transition off during a gesture, hand it back on release (snap-back and double-click restore stay smooth).
- [x] Reset zoom/pan on every re-open and on every page change.

## Verification and acceptance

- [x] Unit gate (static profile): `scripts/tests/media-zoom.test.mjs` — 8 tests over clamping, the anchor invariant, per-axis bounds, rubber band, pinch (anchor + midpoint follow + below-minimum damping), the fit-state gesture table, wheel/trackpad classification, wheel factors.
- [x] Browser gate (frontend profile): `tests/media-viewer-zoom.spec.mjs` — M1–M8 (pinch anchor, pan bounds, edge-continuation paging, below-minimum spring-back that does not close, shipped gestures after a zoom round-trip, reopen reset, video control band, rAF sample) and D1–D4 (cursor-anchored wheel, trackpad pinch + two-finger pan, double-click + Esc/arrows, drag pan clamped).
- [x] Regression face unchanged: `tests/mobile-image-gallery.spec.mjs` (G1–G6, V1, D0, desktop interactions) and `tests/media-click-unify.spec.mjs` (D1–D3, M1–M3) edited zero lines and pass.
- [x] Break → red / restore → green for the unit gate and for the browser gate's core assertions (exit codes recorded in the batch report).
- [x] Static gate and frontend gate exit codes recorded; `js-types` and the `build-web` orphan guard pass.
- [ ] Landing on the shared integration branch: root's serial step. This branch is not merged, pushed or tagged here.
- [ ] Two-finger feel and trackpad pinch on real hardware: pending the author's own hands (see the proposal's verification limits).
