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

/** i18n param bag for strings carrying the agent's name (`{agent}` placeholder). */
export function rootNameParams() {
  return { agent: rootDisplayName() };
}
