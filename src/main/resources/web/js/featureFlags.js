// featureFlags.js — release gating switches (product feature flags).
//
// Friends feature (contacts panel + friend messaging) build-environment split
// (author ruling 2026-09-10, supersedes the "default off everywhere" posture
// of 2026-09-08):
//
//   - CI/CD release bundle (scripts/build-web.mjs, the esbuild chain ci.yml
//     runs before `sbt -D<lowerName>.webdist=1 assembly`): friends are
//     STRIPPED. build-web.mjs injects the compile-time marker
//     `window.__NEBFLOW_RELEASE__ = true` via esbuild `define`, so the guard
//     below folds to `if (true)` and esbuild's dead-code elimination removes
//     the dev branch — friendsEnabled() becomes a physical `return false` in
//     shipped bundles. Entries stay detached at boot (activityBar.js) and the
//     contacts/messages modules never init (no polling, no WS handlers).
//
//   - Local dev (sbt run serves the SOURCE tree directly — no esbuild, no
//     marker property): friends default ON so the entries are visible and
//     debuggable out of the box. `"features": { "friends": false }` in
//     ~/.nebflow/nebflow.json turns them off (two-state debugging).
//
// Flag source = the existing server config channel (no new mechanism): the
// backend serves the install config verbatim via WS `configData` →
// state.parsedConfig (WebSocketRoutes getConfig → ConfigService.getConfig
// reads <home>/nebflow.json per call; brand.conf configFileName).
//
// The decision latches once per boot on the first configData (main.js); a
// config edit takes effect on page reload — no live toggle, by design.
// Device interconnect (NebLink login / device list) is NOT gated (author
// ruling 2026-09-08: Relay-only use is shippable as-is).

import state from './state.js';

/**
 * true when the friends feature (contacts + friend messaging) is enabled.
 *
 * The release guard reads the marker expression INLINE (no intermediate
 * const): esbuild's `define` replaces the member expression textually, so
 * the bundled form is `if (true === true)` → the dev branch is folded away.
 * Dev-tree semantics: absent property → dev defaults (ON unless explicitly
 * `false`).
 */
export function friendsEnabled() {
  if (/** @type {Window & { __NEBFLOW_RELEASE__?: boolean }} */ (window).__NEBFLOW_RELEASE__ === true) {
    return false; // release bundle: stripped (author ruling 2026-09-10)
  }
  return state.parsedConfig?.features?.friends !== false; // dev: default ON
}
