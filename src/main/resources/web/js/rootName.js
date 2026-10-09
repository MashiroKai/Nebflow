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

/** The root agent's display name. Read chain: in-session onboarding override →
 *  the agent.json probe's authoritative read (rootName boot race: the header can
 *  render before agentList arrives — the file leg keeps the named home's REAL
 *  name on that first paint, and it outranks the agentsData row so a fallback-
 *  poisoned row can never mask the user's persisted name) → agentsData row →
 *  mechanism name as the last-resort fallback. Steady state the file and the row
 *  agree, since the gateway derives the row from the same file. */
export function rootDisplayName() {
  const override = state.rootDisplayName;
  if (override) return override;
  const a = (state.agentsData || []).find((x) => x.name === 'Nebula');
  return state.rootAgentFileName || (a && (a.displayName || a.name)) || 'Nebula';
}

/** True when the user has actually NAMED the root agent — either the in-session
 *  onboarding rename override (`state.rootDisplayName`, written by
 *  onboarding.js renameEverywhere) or a persisted `displayName` in the root
 *  agent's `agent.json` (read asynchronously by `initRootNameProbe`). Blank/
 *  whitespace-only values count as unnamed, and a literal `"Nebula"` displayName
 *  counts as NAMED (the judgement reads the KEY, never the value — that is the
 *  whole point of this helper).
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
  // 🔴 The old second leg — a `displayName` on the agentsData root row — is
  // RETIRED (askuser-bubblename batch 2026-10-06, author B-1 必达): the agentList
  // payload is fallback-poisoned. The gateway folds the unset case server-side
  // (`a.displayName.getOrElse(a.name)` → WsSessionChatHandlers.handleListAgents),
  // so a fresh, never-named home ships `displayName: "Nebula"` and this leg read
  // TRUE on every real boot — N1/N2/N4/N5 all showed their labels (the author's
  // screenshot-2 NEBULA residue). The row carries zero bits the agent.json probe
  // doesn't, so the persisted-name verdict now comes from the probe alone.
  return state.rootAgentFileNamed === true;
}

/** Authoritative persisted-name probe (askuser-bubblename batch, author
 *  2026-10-06 19:43 令 B-1): reads the ONE artifact the server's own display
 *  read point reads — `agents/Nebula/agent.json`'s `displayName` KEY
 *  (BuiltinAgents.displayNameForRoot), served by the gateway's static
 *  `/agents/<name>/...` route. 404 (no file = never named) and a blank/missing
 *  key both resolve UNNAMED; a non-blank key resolves NAMED. The key's PRESENCE
 *  is judged, never its value — a user-chosen literal `"Nebula"` stays NAMED
 *  (the exact trap this file's header bans).
 *
 *  Fire-and-forget at boot (main.js, beside `connect()`); `nebflow-root-name-
 *  change` re-renders the faces when the verdict lands (sidebar.js owns the
 *  listener — the header corrects itself within one local round-trip). Fail
 *  direction is UNNAMED (network/parse error ⇒ false): the pre-naming discipline
 *  (zero NEBULA before the user names the agent, B-1 必达) outranks a transient
 *  miss. Idempotent — one fetch per page load, verdict on
 *  `state.rootAgentFileNamed` (null = unsettled). */
let probePromise = null;
export function initRootNameProbe() {
  if (probePromise) return probePromise;
  probePromise = fetch('agents/Nebula/agent.json', { cache: 'no-store' })
    .then((r) => (r.ok ? r.json() : null))
    .then((j) => {
      const dn = (j && typeof j === 'object') ? j.displayName : null;
      const named = Boolean(typeof dn === 'string' && dn.trim());
      state.rootAgentFileNamed = named;
      // The name itself feeds the DISPLAY chain (rootDisplayName boot-race leg —
      // see its doc): the file is what the server's own displayNameForRoot reads.
      state.rootAgentFileName = named ? String(dn).trim() : null;
    })
    .catch(() => { state.rootAgentFileNamed = false; state.rootAgentFileName = null; })
    .then(() => {
      window.dispatchEvent(new CustomEvent('nebflow-root-name-change'));
    });
  return probePromise;
}

/** i18n param bag for strings carrying the agent's name (`{agent}` placeholder). */
export function rootNameParams() {
  return { agent: rootDisplayName() };
}
