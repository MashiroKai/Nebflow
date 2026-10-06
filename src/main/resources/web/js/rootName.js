// rootName.js — the root agent's DISPLAY name, single read point for the UI.
//
// Mechanism vs display (task book §2 C, hard rule): the root agent's identity
// key is always `"Nebula"` (RootAgentIdentity.Name) — every judgement, protocol
// token, flow-map out-edge and internal role key keeps that literal. What the
// user READS is the display name: `agents/<root>/agent.json`'s `displayName`,
// which onboarding writes when the user names the agent, falling back to the
// mechanism name when unset (zero regression for users who never named it).
//
// Leaf module by design: it imports only `state.js` (itself importing only
// branding.js / brand.js), so any UI module can read the display name without
// risking an import cycle.
import state from './state.js';

/** The root agent's display name. `agentList` already sends
 *  `displayName || name`; the mechanism name is the last-resort fallback. */
export function rootDisplayName() {
  const override = state.rootDisplayName;
  if (override) return override;
  const a = (state.agentsData || []).find((x) => x.name === 'Nebula');
  return (a && (a.displayName || a.name)) || 'Nebula';
}

/** True when the user has actually NAMED the root agent — either the in-session
 *  onboarding rename override (`state.rootDisplayName`, written by
 *  onboarding.js renameEverywhere) or a persisted `displayName` on the agentsData
 *  root row (agentList frame / agent.json). Blank/whitespace-only values count as
 *  unnamed, and a literal `"Nebula"` displayName counts as NAMED (the judgement
 *  reads the key, never the value — that is the whole point of this helper).
 *
 *  Consumers (unnamed-state batch 2026-10-06, author brief §3): N1 header title,
 *  N2 onboarding bubble source labels, N4 AskUser source badge, N5 pending-ask
 *  rows. These faces render NO name at all while unnamed — the mechanism name
 *  `"Nebula"` is a key, never a display fallback for them. 🔴 Never judge the
 *  named state via `rootDisplayName() !== 'Nebula'`: a user-chosen literal
 *  `"Nebula"` would misread as unnamed, and display-vs-judgement is exactly the
 *  line this file's header draws. */
export function isRootNamed() {
  if ((state.rootDisplayName || '').trim()) return true;
  const self = (state.agentsData || []).find((x) => x.name === 'Nebula');
  return Boolean(self && self.displayName && String(self.displayName).trim());
}

/** i18n param bag for strings carrying the agent's name (`{agent}` placeholder). */
export function rootNameParams() {
  return { agent: rootDisplayName() };
}
