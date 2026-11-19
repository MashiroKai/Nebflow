## Why

The author's word-for-word ask (2026-11-18 16:57): 「媒体素材 图片 视频这些 手机端要支持手势放大缩小 电脑端也要支持鼠标滚轮以及触控板手势」 — in-app image/video viewing must support zoom and pan, with each platform's natural input.

Shipped state before this change (measured on `bbd72b34c`, see `design.md` §1):

- The in-app viewer is the gallery overlay (`js/lightbox.js` `openImageGallery`, a full-screen `position:fixed; inset:0` layer) and it is the entry for chat media on **both** platforms.
- It had a **scalar** zoom (`galZoom`) with no translation of its own: wheel zoom was centre-anchored (`paintZoom` wrote `translate(galPanX,galPanY) scale(galZoom)`), `ctrl/meta` wheel was **ignored** (`if (… e.ctrlKey …) return` — so a trackpad pinch did nothing), there was **no multi-pointer pinch pipeline at all** (touching two fingers did nothing), and the drag path wrote `imgEl.style.transform` **directly**, which erased the zoom whenever the user swiped.
- Video in the gallery was a bare `<video controls>` with no zoom; the whole element was excluded from the gesture pipeline.

So: pinch (phone), plain-wheel anchored zoom, trackpad pinch and two-finger pan (desktop) were all missing, and zoom and pan fought each other.

## What Changes

- **One zoom/pan state for the gallery media element** (image *and* video): `(s, tx, ty)` with `js/mediaZoom.js` as the pure-arithmetic single source (clamping, per-axis closure bounds, anchor invariant, pinch, wheel/trackpad classifier, fit-state gesture table), and `galPaint()` in `js/lightbox.js` as the **single writer** of the media element's `transform`. The fit-state drag offsets became a separate *gesture layer* composed in that same paint, so a swipe can no longer erase a zoom.
- **Phone**: two-finger pinch zooms anchored at the finger midpoint (midpoint motion composes as pan); single-finger drag pans when zoomed, rubber-banded outside the bounds and clamped back on release; **over-pan at the axis edge continues into the next/previous image**; pinching below the minimum springs back to fit-screen (it does not close).
- **Desktop**: plain wheel zooms **anchored at the cursor**; trackpad pinch (`wheel{ctrlKey}`, plus `metaKey`) zooms; pixel-step / `deltaX` wheel pans (two fingers); double-click toggles fit ↔ 2× anchored at the click point; drag pans when zoomed; Esc / ←→ unchanged.
- **Video** goes through the same zoom state: pinch on the picture zooms it, drag pans it once zoomed, and any gesture whose start point falls inside the native control band (bottom 56px of the element; the whole element for audio) is **not claimed** by the zoom pipeline.
- **Bounds**: an axis whose scaled content is not wider than the viewport is *not pannable* — on the touch leg it is pinned centred, on the mouse leg it stays free (the shipped desktop behaviour). `ZOOM_MIN` = fit-screen, `ZOOM_MAX` = 5× fit (the pre-existing wheel cap), one cap for both input devices.
- **Transitions**: the 200 ms `transform` transition is held off during a gesture and handed back on release, so a wheel notch lands in the frame where it happened instead of lagging a 200 ms animation behind the hand.

### Scope

- `src/main/resources/web/js/mediaZoom.js` (new), `src/main/resources/web/js/lightbox.js`, `src/main/resources/web/css/lightbox.css`.
- Gates: `scripts/tests/media-zoom.test.mjs` (new, static profile), `tests/media-viewer-zoom.spec.mjs` (new, frontend profile via `scripts/run-checks.mjs`).
- The two shipped media specs (`tests/mobile-image-gallery.spec.mjs`, `tests/media-click-unify.spec.mjs`) are **not edited** — they are the regression face for the entry routing and the gallery basics.

### Non-goals

- **A second viewer.** There is one media viewer (`openImageGallery`); the Canvas image viewer keeps its own pointer pipeline and toolbar, untouched. This change does not merge them.
- **The Canvas video viewer.** `js/viewers/video.js` (the flat in-canvas player) is not made zoomable; the ask is about the media viewer, and that surface's controls are custom DOM, not a native control band.
- **A hardware-true phone**. The touch evidence is Chromium CDP touch simulation; see `Verification limits`.

## Verification limits

`tests/media-viewer-zoom.spec.mjs` drives real DOM in Chromium with CDP `Input.dispatchTouchEvent`, real `page.mouse.wheel`, and real ctrl+wheel `WheelEvent`s against a fixture server: it proves the anchor invariant, the closure bounds, the fit-state gesture preservation, the video control-band yield and the reopen reset, with the *measured* numbers above. It does **not** prove two-finger feel on a physical phone, macOS trackpad pinch on real hardware, or frame rate on a real device — the rAF sample in M8 is a coarse headless tripwire, not a performance claim. Trackpad-vs-wheel classification is a heuristic and its residual ambiguity is written down in `js/mediaZoom.js` (`createWheelClassifier`).
