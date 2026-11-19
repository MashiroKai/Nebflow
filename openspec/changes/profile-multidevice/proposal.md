## Why

The personal profile surface grew four ways to reach one sheet of paper: a desktop overlay, a mobile page, three mobile subpages (`me-user` / `me-avatar` / `me-bind`) and an "Edit profile" button. Every one of them renders the same read-only readings, three of them add a "no endpoint yet" hint, and the edit button opens a face that has no editable control. A user who wants to change their nickname is led through four dead ends and never told where editing actually happens.

At the same time the console is multi-device but presents itself as single-device. `GET /api/neblink/status` already returns this device plus every paired peer with name, platform, `lastSeen`, freshness-based `online` and P2P `directOnline`, yet the only sign-out control in the profile area is an account-wide "Sign out" in the settings account card. Nothing tells the user which device that button signs out, and nothing lets them act on a specific device. Sign-out is in fact device-local (each device stores its own credential in its own `device.json`), so the UI was strictly less precise than the mechanism.

## What Changes

- Collapse the mobile profile to a **single page** (`me`): the three subpages and the "Edit profile" empty action are retired in the navigation table itself, so `pushPage('me-user' | 'me-avatar' | 'me-bind')` no longer reaches anything — retired, not hidden.
- Keep identity **read-only** and replace the dead-end hints with **editing guidance**: the account page on the website is named as the place where identity is edited (plain text reading, no `<a href>`, no `window.open` — the previously ruled-out website exit stays retired; the honest reading is where, not a fake in-app edit).
- Add a **device list** to both profile surfaces (desktop overlay `#settings-profile-overlay` and the mobile `me` page), rendered from one shared markup and one shared action implementation (`js/profileDevices.js`), showing per device: name, platform, recent activity (`lastSeen`), status (online / offline / unknown — never guessed) and a current-device marker.
- Give **each device row its own sign-out**:
  - current device -> the existing single logout chain (`openEndSessionHandoff(false)`: local credential teardown + provider RP logout), because that chain carries the SSO handoff this device alone owns;
  - any other device -> `POST /api/neblink/devices/{deviceId}/sign-out` with that row's own id.
- Add the smallest safe backend capability behind that call: `POST /api/neblink/devices/:deviceId/sign-out` gated by the same credential gate as `POST /neblink/logout`, validating `deviceId` against the local roster (self id or a real peer; anything else is `404` — the route is not an arbitrary-id proxy), performing the same `performLocalLogout` teardown for the self id, and returning an explicit `409 {"code":"remote-sign-out-unsupported"}` for a peer.

### Scope

- Frontend: `js/profileDevices.js` (new), `js/settingsIdentity.js`, `js/mobile/meFace.js`, `js/mobile/navController.js`, `css/settingsRows.css`, locales `zh-CN` / `en`.
- Backend: one route in `gateway/NeblinkRoutes.scala`.
- Tests: `tests/profile-multidevice.spec.mjs` (new); `tests/settings-identity-card.spec.mjs` and `tests/mobile-round2.spec.mjs` updated for the retired subpages. Repair batch (2026-11-18) adds `tests/profile-devices-geometry.spec.mjs` (new) and rewrites `tests/mobile-topbar.spec.mjs` T2/T3.

### Non-goals

- **Remote device credential termination.** Signing device B out from device A requires a peer-directed command channel that the receiving device honours. The "remote update" channel over P2P was retired by author ruling; this change does not rebuild it. The peer leg therefore answers with a truthful `409` rather than a fabricated success, and the UI says "sign out on that device itself". The isolation property this change does guarantee is the one the ask is about: acting on one device never signs out another.
- **Multi-device-transparent console sessions.** Console sessions (`GatewaySessionStore.SessionRecord`) carry no device id, so this change does not attempt to make a browser console session device-scoped. It is documented here as the known boundary: the console is transparent to which device it reaches, while credentials and sign-out are device-scoped.
- Editing identity in-app, avatar upload, GitHub / Google binding reads — all still absent upstream, unchanged by this change.

## Placement (why these files)

- `js/profileDevices.js` — one readout (`getNeblinkState()`, i.e. `GET /api/neblink/status`) and one action implementation, consumed by both surfaces. Two faces rendering two lists from two fetches is the failure this placement prevents.
- `js/settingsIdentity.js` — already owns the identity readout (`getIdentityReadout`) and the desktop profile overlay; the device section is appended there rather than in a new overlay.
- `js/mobile/meFace.js` — already the mobile profile body builder; the subpage builders are deleted here.
- `css/settingsRows.css` — where the shared settings/profile row material lives; the new `.pd-*` rules consume existing tokens only (no new colours, no new grey surfaces; the row separator reuses `--color-frame-border-light`).
- `NeblinkRoutes.scala` — the `/neblink` domain already owns `logout` and `auth/end-session`; a device-scoped spelling of the same teardown belongs next to them, not in `AuthRoutes`.

## Verification limits

`tests/profile-multidevice.spec.mjs` drives a stubbed REST/WS surface in a real browser (mobile and desktop viewports): it proves the rendered list contents, the current-device marker, that the peer row issues exactly one request carrying exactly its own `deviceId` and no other, that the peer row never triggers the end-session chain, that the current-device row triggers it, and that no edit action, subpage, editable control, anchor or `window.open` exists. It does **not** prove live cross-device behaviour, does not contact a real gateway, and does not establish rendered geometry, safe areas, occlusion or contrast — those need a real instance and screenshots. Passing static lint is not acceptance evidence for any of it.

## Repair batch (2026-11-18, independent review)

An independent review of this change found three blockers; the intent above is unchanged by the repair.

- **The backend leg did not compile.** `IO.pure(...)` wrapped the http4s DSL responses in the peer (`409`) and unknown-device (`404`) arms, so the `for` comprehension's arms met at `Response[IO] | IO[Response[IO]]` and `yield resp` failed with `[E007] Type Mismatch` (line 585). The DSL values are now returned directly; `sbt compile` is green.
- **The mobile device card was clipped by flex shrink.** `.mobile-page-body` is both the scroll container and a column flex container, and `.mf-hub-rows` carries `overflow: hidden` — per spec an `overflow` non-`visible` flex item gets an automatic minimum size of zero, so the card was compressed (content 300px, box 101.3px at 390×844) with `scrollHeight == clientHeight` (no scroll range) and 4 of 6 rows unreachable by pointer. Both `.mf-me-fields` blocks are now `flex: none`, and the per-row sign-out key reaches the mobile 44px hit floor (`--mtap`).
- **`profileDevices.js ensureDeviceListLive` repainted only the first list.** `document.querySelector('[data-device-list="1"]')` returned the first list only, so a second live list stayed stale forever; one status tick now repaints every live list.

Verification added by the repair: `tests/profile-devices-geometry.spec.mjs` (layout truth, per-row touch reachability, the mobile 44×44 hit box, a coordinate tap for the peer row, and an anti-vacuous detector self-check) and a live-repaint test in `tests/profile-multidevice.spec.mjs`. `tests/mobile-topbar.spec.mjs` T3 (retired subpages) and T2 (already red before the repair — the settings-page identity card was removed by the 2026-11-07 account-hero-dedupe ruling, commit `488d8f8bb`) were rewritten to the current product.
