## 1. Measured state before this change (`bbd72b34c`)

`js/lightbox.js` (gallery section) as shipped:

| Question | Answer at `bbd72b34c` | Where |
|---|---|---|
| Phone: tap an image attachment | In-app full-screen gallery overlay (`.nf-lightbox[data-nf-lb-gallery=on]`, `position:fixed; inset:0`), swipe to page, drag-down to close | `js/mediaOpenRoute.js:143` → `js/lightbox.js:914` `openImageGallery` |
| Phone: pinch to zoom | **Nothing.** No multi-pointer pipeline; a second pointer was ignored while the first drove paging | `js/lightbox.js:948-1038` (only `galDrag` = one pointer) |
| Phone: pan when zoomed | Partial: mouse-only `galMouseDrag`, and it wrote `imgEl.style.transform` directly | `js/lightbox.js:972-991` |
| Desktop: click an image in chat | **The same gallery** (`a7fa1c531` "Draft shared desktop media gallery"); file-tree / explicit corner entries still open Canvas | `js/mediaOpenRoute.js:143-155` |
| Desktop: mouse wheel | Zoom, ×1.2 per notch, **centre-anchored** (no pivot), capped at 5 | `js/lightbox.js:955-961` |
| Desktop: trackpad pinch (`wheel{ctrlKey}`) | **Ignored** — `e.ctrlKey` returned early | `js/lightbox.js:956` |
| Desktop: trackpad two-finger pan | Not available (plain wheel was zoom) | same rule |
| Desktop: double-click | Toggle fit ↔ 2×, **centre-anchored**, pan reset to 0 | `js/lightbox.js:962-965` |
| Desktop: Esc / ←→ | Work (kept) | `js/lightbox.js:966-971` |
| Video in the gallery | Bare `<video controls>`, **no zoom**; the whole element was excluded from gestures | `js/lightbox.js:847-862`, `:981` |
| Canvas image viewer (other surface) | Already had pinch/pan/dbl-click/wheel+ctrl | `js/viewers/image.js:41-221` |

## 2. Coordinate convention (one derivation, written down)

The media element is centred by the overlay's flex layout and keeps the CSS default `transform-origin: center`, so **the element's transform origin is the viewport centre**. Every translate and every pivot therefore lives in *viewport-centre-relative* coordinates, where the anchor invariant is

```
screen p = content-point · s + t          →   t' = p − (p − t)·k,  k = s'/s
```

`js/viewers/image.js` states the same invariant with the element's top-left as the origin (its `<img>` is absolutely placed at 0,0). Same invariant, different origin — not two zoom models.

Bounds per axis, with `content = layout-size × s`:

- `content ≥ viewport`: pannable, clamped to `±(content − viewport)/2`;
- `content < viewport`: not pannable — touch leg pins it centred (`pin = true`), mouse leg leaves it free (`pin = false`, the shipped desktop behaviour).

## 3. Bounds and cap

`ZOOM_MIN = 1` (fit-screen — the element is already laid out as a contain box, so `s=1, t=0` *is* "fit and centred", which also makes it the snap-back target). `ZOOM_MAX = 5`:

- same number as the shipped wheel cap (`Math.min(5, …)`), so pinch and wheel cannot stop at two different maxima;
- on an upright phone a 1170×2532 screenshot fits at ≈0.31× native pixels, so ×5 ≈ 1.5× native — just past 1:1 where text is crisp;
- past that, one-finger panning on a phone is "hunting for a needle" and adds no readable information. The Canvas viewer's 8× is mouse semantics (no finger occlusion) and is deliberately not shared.

## 4. Gesture table (the co-existence rule the ask requires)

| pointers | state | gesture | outcome |
|---|---|---|---|
| 2 | any | pinch | zoom anchored at the finger midpoint; midpoint motion composes as pan |
| 1 | fit (s=1) | horizontal | page to next/previous (edge rubber-band ×0.3, never past the group end) |
| 1 | fit | downward | close (follow-the-finger + fade, commit past threshold) |
| 1 | fit | other | spring back |
| 1 | zoomed | drag | pan, rubber-banded outside the bounds, clamped on release |
| 1 | zoomed | drag past the axis bound (> 72 px) | **page** (WeChat-style edge-continuation; ←→ survives zooming) |
| 1 | zoomed | downward | pans (close is **not** armed while zoomed — close stays on backdrop tap / back button / back gesture / Esc) |
| wheel | any | notch / `ctrlKey` | zoom anchored at the cursor |
| wheel | any | pixel steps or `deltaX` | two-finger pan |
| dblclick | any | — | fit ↔ 2× anchored at the click point (images only; video keeps native full-screen dbl-click) |

The fit-state thresholds and commit rules are byte-identical to the shipped ones — they moved from inline arithmetic into `mediaZoom.fitDragOutcome` so they can be asserted, not so they can change.

## 5. Video and the native control band

Gallery video is a native `<video controls>`: the play / scrub / volume controls live in a UA-rendered bar that JS cannot address by selector. The rule is therefore **geometric and stated**: any pointer or wheel whose start point falls inside the bottom 56 px band of the video element (the whole element for audio) is left to the element itself — the zoom pipeline never claims it. Measured band height of the Chromium/Safari native control bar is 48–56 px; taking the upper bound guarantees the entire bar is yielded. Away from the band the video is a normal zoom target; while zoomed, a drag pans it and the trailing compatibility `click` (which would otherwise toggle play/pause) is swallowed.

## 6. Two real bugs this batch found (not test artefacts)

1. **Zoom was erased by a swipe.** The drag path wrote `imgEl.style.transform` directly; at zoom 2 a horizontal drag reset the transform to a bare `translateX`. Fixed by the single-writer paint (`zoom ∘ gesture layer`).
2. **Double-click restore was unreachable while zoomed.** Arming the mouse pan calls `setPointerCapture`, and pointer capture retargets the derived `click`/`dblclick` to the capture element — measured: `e.target` was `.nf-lightbox` while `document.elementFromPoint` was the `<img>`. The old guard `e.target === imgEl` therefore dropped every restore double-click on a zoomed image. The handler now decides by **hit test** (is the point inside the media box) instead of by event target.

## 7. Placement

- `js/mediaZoom.js` — pure, zero-DOM, zero-import: the only place the zoom arithmetic and the classifier exist, and the only place a `node --test` gate can reach. Both consumers of a number (pinch, wheel) read the same functions, so the two input devices cannot drift into two hand feels.
- `js/lightbox.js` — the gallery already owns the overlay, the page index and the fit-state gestures; the zoom state belongs next to that state, not in a new module that would need the overlay to exist first.
- CSS changes are additive and token-only: `.nf-lightbox-av` gains the same transform-origin/transition discipline the `<img>` already had.
