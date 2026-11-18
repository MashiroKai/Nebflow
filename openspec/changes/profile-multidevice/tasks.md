## Implementation

- [x] Collapse the mobile personal profile to one page: retire `me-user` / `me-avatar` / `me-bind` from the navigation table and drop the empty "Edit profile" action (`js/mobile/navController.js`, `js/mobile/meFace.js`).
- [x] Keep identity read-only and replace the dead-end hints with plain-text editing guidance naming the website account page (`js/mobile/meFace.js`, `js/settingsIdentity.js`).
- [x] Add one shared device list — name / platform / recent activity / status / current-device marker — built once by `js/profileDevices.js` through the shared light-DOM builder, mounted by both the desktop profile overlay and the mobile profile page.
- [x] Give every row its own sign-out: current device through the existing `openEndSessionHandoff(false)` chain, peer rows through `POST /api/neblink/devices/{deviceId}/sign-out` with that row's own id.
- [x] Add the smallest safe backend leg: same credential gate as `/neblink/logout`, roster-validated id (`404` otherwise), `performLocalLogout` for the self id, explicit `409 remote-sign-out-unsupported` for a peer (`gateway/NeblinkRoutes.scala`).
- [x] Fix the two defects the independent review found: the route did not compile (DSL response wrapped in `IO.pure` again), and the mobile device card was flex-shrunk with 4 of 6 rows unreachable by pointer.
- [x] Repair the live-repaint defect: one status tick repaints every mounted device list, not only the first.
- [x] Repair the component-contract regression this batch introduced: the shared list is now built with the shared builder (no raw HTML-string assignment in any file, counts back to baseline).

## Verification and acceptance

- [x] Playwright suites for the batch: `tests/profile-multidevice.spec.mjs`, `tests/profile-devices-geometry.spec.mjs`, `tests/mobile-topbar.spec.mjs` (T2/T3 rewritten to the current product), `tests/settings-identity-card.spec.mjs`, `tests/mobile-round2.spec.mjs` — 22/22 passed.
- [x] Dynamic evidence: `tests/profile-multidevice-video.mjs` records 375×812 (mobile) and 1280×800 (desktop) runs — browse the device list, sign out one device, show the other devices are unaffected — 18/18 checks passed, zero script errors.
- [x] Static gates: `check:static` (only the pre-existing `local-coupling` and `sdd` failures plus the worktree-manifest false red remain; `component-contract` and `js-types` are green) and `check:frontend` exit 0.
- [x] `sbt compile` exit 0 on the batch tip.
- [ ] Land the plugin rule: the reusable design rule is carried verbatim in `plugin-doc-delta.md` for the `stand-dev` plugin. Not landed — the plugin source is a separate repository whose local checkout sits inside the protected production data root; landing it is a separately authorized live-configuration change.
- [ ] Author word-for-word review of the batch. Not implied by these edits.
- [ ] Live cross-device proof (a real second device honouring a remote sign-out). Out of scope by the change's own non-goals; the recorded evidence is fixture-driven and says so.
