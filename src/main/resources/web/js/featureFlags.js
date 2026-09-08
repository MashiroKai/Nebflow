// featureFlags.js — release gating switches (product feature flags).
//
// Friends feature (contacts panel + friend messaging) is release-gated OFF by
// default (author ruling 2026-09-08: friend reliability semantics are still
// under audit; release builds ship without user-facing friend entries — a
// release user has no reachable friend backend, so any leftover entry would
// be a dead end).
//
// Flag source = the existing server config channel (no new mechanism): the
// backend serves the install config verbatim via WS `configData` →
// state.parsedConfig (WebSocketRoutes getConfig → ConfigService.getConfig
// reads <home>/nebflow.json per call; brand.conf configFileName). Enable per
// install by adding to ~/.nebflow/nebflow.json:
//
//   "features": { "friends": true }
//
// The decision latches once per boot on the first configData (main.js); a
// config edit takes effect on page reload — no live toggle, by design.
// Device interconnect (NebLink login / device list) is NOT gated (author
// ruling 2026-09-08: Relay-only use is shippable as-is).

import state from './state.js';

/** true when the friends feature (contacts + friend messaging) is enabled. */
export function friendsEnabled() {
  return state.parsedConfig?.features?.friends === true;
}
