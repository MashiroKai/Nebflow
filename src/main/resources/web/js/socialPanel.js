// socialPanel.js — Social interface card panel: render + state machine + focus.
//
// Upstream design (read-only, do not re-derive): `socremote-design`
// (n-5c95367d), implemented face A/B/D/E only —
//   · A  entry: `#social-btn` directly below `#contacts-btn` (smartphone icon),
//        gated the same way the contacts anchor is (button hidden WITH it).
//   · B  modal + one card per SOCIAL_CHANNELS entry + the remote-access section
//        (`#social-section-remote`, always present IN THE DOM — it is now
//        withdrawn from the VISIBLE face by the `hidden` attribute it carries
//        in index.html, see the hide ruling below; the mapping for its four
//        caption ids is kept so no locale key becomes an orphan).
//   · D  "fill and save directly": a pasted credential goes into the POST body
//        ONCE; the backend writes the secret file and the config keeps only a
//        path. The panel never echoes a stored credential back (it renders
//        "written · not echoed" instead).
//   · E  phase-1 terminal state = "configured · not linked" for every card;
//        `connected` is reachable only by flipping the definition layer flag.
//
// 🔴 FACES DELIBERATELY NOT IMPLEMENTED (dispatcher ruling 2026-09-19 21:04:34
//    = face C suspended; the author-face items O1–O5 in the design's §9.3 are
//    NOT decided yet): the remote-access switch semantics, the QR code, the
//    read-only address-export route, the clipboard fallback and the mobile
//    adaptation range. This file therefore renders NO link, NO QR image and NO
//    guessed address FOR THE REMOTE-ACCESS SECTION. `[data-remote-url]` /
//    `[data-copy-link]` do not exist — that is the intended, frozen state of
//    that section, not an oversight. (feiscanbind, 2026-09-27; social-fix,
//    2026-09-28: the FEISHU scan-bind QR is a different face — the approved
//    plan-card §5 main path. It renders the platform verification URL INSIDE
//    the card: one view layer, no sub-dialog, no manual-fill fallback.)
//
// 🔴 HIDE RULING (author, 2026-09-23, Part A): the whole remote-access section
//    is HIDDEN FROM THE VISIBLE FACE, NOT DELETED — `#social-section-remote`
//    carries `hidden` in index.html plus the companion rule in css/social.css.
//    Nothing in this file changes: its DOM subtree stays built, and the four
//    `social-remote-*` entries below keep translating it, so the `social.remote.*`
//    locale keys stay consumed (no orphan keys, no key churn). The section's own
//    CSS body is kept as well. Restoring the section = dropping one attribute.
//    O1–O5 are NOT settled by the hide ruling — they stay suspended exactly as
//    described above. Hidden ≠ deleted is the whole point of this shape.
//
// 🔴 CHANNEL SEAL (socialhide batch, 2026-09-26): the wechat + telegram
//    entries are `hidden: true` at the definition layer (PHASE 3 there). The
//    single render point in [[renderAll]] maps the definition layer's visible
//    set, so a sealed card is never built, never bound and never refreshed:
//    the refresh path ([[refreshCard]]) early-returns on its missing card
//    element, and the state loops only fill data maps (no DOM, no listeners).
//    Same sealed family as the remote-access section above: hide ≠ delete.
//
// Style: css/social.css (new file; sapphire.css is off-limits for this batch).
// Switch component: shared js/toggle.js (`nb-toggle`), never a private copy.
//
// feishu-panel batch: the scanBind card (feishu) renders TWO faces —
//   · not-created: the QR lives IN the card with its status line (auto-begins
//     when the panel opens on a not-created card, and again after an archive
//     — the "recreate" leg).
//   · created: live-app block (contract C1 — which app the running bridge is
//     actually holding; its fingerprint verdict row was removed by the
//     socpanel-min wave-2 batch, see that paragraph), archive (contract C5).
//     The bindings list + default-session control (contracts C3/C4) USED to
//     render here; the socpanel-min batch (author ruling 2026-10-01, last
//     paragraph of this header) removed that display block from the face.
//     The enable switch is folded into archive on this card: enabled=false IS
//     the archive, so no separate disabled face exists.
//   · C1/C3/C4 data comes from the backend contract; an older backend answers
//     404 or omits the fields and every reader degrades to a readable state
//     (never an error, never a blank panel). The live-app line has NO fallback
//     to the stored app_id — that fallback is exactly the green-pill gap
//     (registered ≠ registered to THIS app) the diagnosis report pinned.
//
// social-feishu-fixed batch (author ruling 2026-09-30): the archive control is
// renamed to "unbind" (copy only — identifiers, attributes and the endpoint are
// untouched; the credential-keeping semantics do not change), and the default
// session is FIXED to the Nebula session: the control renders as a single
// selected+disabled option (no picker, no "not set" exit), and boot converges a
// backend still holding another value onto the Nebula id via the existing write
// path ([[pinDefaultSessions]]). Zero backend change rides along.
// 🔴 The 2026-09-30 ruling split into a DISPLAY half and a BEHAVIOUR half; the
//    socpanel-min batch (2026-10-01, next paragraph) removed the display half
//    and kept the behaviour half verbatim: the pinned+disabled control no longer
//    renders, while the fixed pin, [[pinDefaultSessions]] and its boot-time
//    convergence write-back below are untouched.
//
// socpanel-min batch (author ruling 2026-10-01): the session-bindings display
// block is REMOVED from the created face — both the chat→session binding list
// (contract C3) and the default-session control (contract C4's rendered half).
// Nothing about BINDING BEHAVIOUR changes: message routing, the fixed Nebula
// pin, the boot-time convergence write-back ([[pinDefaultSessions]] →
// [[saveDefaultSession]] PUT) and the bridge state flip all stay exactly as they
// were. The C3 display transport is retired with the block it fed
// ([[fetchBindings]] / [[bindingsByChannel]] / FEISHU_BINDINGS_URL are gone);
// the C4 read path survives because the write-back's idempotence test reads it.
// The i18n keys the block consumed are deliberately NOT deleted — they stay as
// orphans for the author to rule on (AGENTS §16: copy is the author's to
// review), so zero locale file changed. Restoring the block = taking it back
// from git history (this batch's parent commit).
// 🔴 That "zero locale file changed" statement held for wave-1 only; the wave-2
//    paragraph below removes exactly one already-orphaned key (K1).
//
// socpanel-min wave-2 batch (author ruling 2026-10-01, follow-up order — the
// minimal-face extension). Removal only; ZERO new user-visible copy (§16), zero
// behaviour change beyond the dead-seam question below:
//   · A1  fingerprint row — gone. `data-fp` / `social-live-warn` have no
//         emitter left; a mismatch still shows as the pill ([[displayStatus]]
//         reads the in-memory reading, never the DOM).
//   · A2  bridge row — gone (`data-live-enabled`, [[enabledText]] retired as
//         its only consumer).
//   · A6  the application row keeps its VALUE but loses its label, and renders
//         ONLY when a real app is known (`live.available && live.appId` — the
//         same condition the value picks on, never a post-render text
//         comparison). An unlinked bridge renders the EMPTY container: the key
//         `social.feishu.live.appUnknown` is simply no longer rendered (kept in
//         the table, now an orphan for the author). The container + `[data-live]`
//         stay — they are SF-1c's anchor and [[refreshCard]]'s re-render target.
//   · A3  the card-wide description line is gone FROM THIS CARD ONLY;
//         [[plainCardHTML]] keeps its own (sealed-family surface, out of scope).
//   · A4  the archive's permanent hint line is gone; the SAME existing copy
//         (social.feishu.archive.hint) now rides the unbind button's `title`,
//         i.e. hover-at-intent. No second copy was authored, none was reworded.
//   · A5  the happy-path credential line is gone — a healthy card says nothing
//         (the pill carries it) and `.social-card-hint` takes its existing
//         `hidden` branch. The contradicted reading and the actionable
//         `configInvalid` hint both stay.
//   · A7  the "channel configuration" section title is gone (index.html node +
//         its [[applyStaticText]] entry). The multiple-visible-cards path is
//         untouched: [[visibleChannels]] / [[cardHTML]] / `.social-card-list`
//         are not modified, so the list still restores itself naturally.
//   · P2/P4  `.social-scan-manual` (0 emitters, a social-fix leftover) is gone,
//         and the `data-scan-inline` attribute is gone — the container and its
//         `data-scan-qr` / `data-scan-code` / `data-scan-status` children stay
//         ([[scanNode]] reads those).
//   · K1  the already-orphaned `social.feishu.defaultSession.none` key is gone
//         from both locale tables (the only locale edit; the author's order
//         names that key alone — the wave-1 orphans stay, awaiting a ruling).
// Behaviour faces deliberately NOT touched: routing, the fixed Nebula pin, the
// boot-time convergence write-back, the bridge state flip, the status pill, the
// archive action, the scan face. The B8 rollback lookup and the B9 `onChange`
// listener are KEPT — they are a deliberately preserved seam for a future real
// picker (the audit's own reading), not accident, so the "may delete" option was
// declined. `prev` is not dead: the failure path restores the last backend truth.
//
// social-fix batch (author ruling 2026-09-28, three changes over the landed
// panel): ① ONE view layer — the QR sub-dialog is retired, the scan block
// renders inside the card; ② the mechanical probe line never reaches the UI —
// the same probe face renders as plain-language credential states; ③ the
// manual-fill form and its collapsed entry are retired — scan-to-create is
// the ONLY creation path. The probe endpoint, the state machine and the
// sealed-channel data face are all unchanged (render layer only).
//
// weixin-scanbind batch (author directive 2026-10-03): a SECOND scanBind card
// joins the panel (weixin-ilink, unsealed). Three generalizations, zero
// behaviour change for the feishu card:
//   · the scan session state is PER CHANNEL ([[activeScans]], one session per
//     card; the one poll timer ticks all live sessions and sweeps itself when
//     the map empties) — [[maybeBeginScan]] auto-begins EVERY visible
//     not-created scanBind card, not just the first;
//   · the scan/archive copy is CHANNEL-SCOPED (`<namespace>.scan.*` /
//     `<namespace>.archive.*`, the namespace derived from the channel's
//     nameKey — see [[copyBase]]) — the feishu keys resolve exactly as before;
//   · the feishu-specific contract faces (C1 connection, C4 default session,
//     the Nebula pin write-back) stay FEISHU-ONLY ([[CONTRACT_FACE_CHANNELS]]):
//     the weixin bridge resolves its default session server-side, and no
//     weixin request may ride the feishu endpoints.
//
// weixin-binditem batch (author order 2026-10-07 09:22, 微信②·绑定完成态 UI
// 重构): the WEIXIN binding face becomes an ITEM ROW (accordion). The five
// states the order pins, and where each lives here:
//   ① default = one row only (icon + 微信 iLink + status pill + chevron);
//      clicking the row expands the QR binding face — the in-card scan block
//      moves INTO the row's expandable body WHOLE ([[itemCardHTML]]), it is
//      hidden, NOT rebuilt (the scan nodes must stay in the DOM: the scan flow
//      re-queries and writes them per tick, and an auto-begun session renders
//      the QR behind the collapsed row so expanding shows it mid-flow);
//   ② scanning — the badge rides the EXISTING state machine unchanged (the
//      pill is [[displayStatus]] on both faces; no scan-state pill is added);
//   ③ binding done — the expansion is dropped ([[tickSession]]'s `done` leg)
//      so the card lands back on the row; the created face's EMPTY live bar is
//      retired with the form ([[liveBlockHTML]] is not rendered on the item
//      face at all — the item-face channel has no C1 source, so that container
//      was permanently empty, which is exactly the empty-bar shape the order
//      retires);
//   ④ the unbind control rides the EXPANDED face (the 取舍 is declared in the
//      batch report: the row stays single-purpose so the accordion toggle can
//      never share a click with a destructive action — no double-target
//      ambiguity on the row, which state ⑤ forbids);
//   ⑤ clicking the expanded row collapses it ([[toggleItemFace]] toggles
//      aria-expanded + the body's `hidden` attribute in place — never a
//      [[renderAll]], whose rebuild would detach the QR box while
//      [[session.lastQrUrl]] still holds the rendered URL and the next tick
//      would skip the re-render).
// Feishu keeps the two-face card byte-for-byte ([[ITEM_FACE_CHANNELS]]); zero
// locale key changes (§16: the row, the badge, the expanded faces and the
// unbind control all reuse existing copy; aria-expanded/aria-controls carry no
// copy). Zero engine change (GET /api/social/channels stays a read-only
// consumer).
//
// weixin-binditem-3cuts batch (author order 2026-10-07, 三小裁 over the landed
// item face — the paragraph above's "Feishu keeps the two-face card" is
// superseded by ruling ③ below):
//   ① unbind fallback — CONFIRMED, zero change: [[archiveChannel]] already
//     drops the expansion and re-lands the card on its row ([[renderAll]]), so
//     unbind completion returns the card to the item view by itself. The
//     evidence frame and the spec pins (WBI-7 / WBI-8d) are the audit trail.
//   ② the item row's chevron is REMOVED ([[itemCardHTML]]): the whole row IS
//     the click target, so the direction hint is noise. `aria-expanded` /
//     `aria-controls` stay — the expand/collapse state still reaches assistive
//     tech through the attributes alone. The `.social-item-chevron` CSS rules
//     left with the node (css/social.css).
//   ③ feishu JOINS the item face ([[ITEM_FACE_CHANNELS]]): the two-face card
//     retires; the created face's C1 live block rides the EXPANDED state
//     ([[itemCardHTML]]'s contract branch — the real-time value is visible
//     only after a click, the same accordion paradigm as the weixin card:
//     default row / click to expand / click to collapse), and
//     [[scanBindCardHTML]] becomes the fallback for a future scanBind channel
//     that has not joined. Zero locale key changes (§16: the feishu row reuses
//     the same name/badge/live/archive copy the two-face card rendered).
//
// 🔴 UNBIND-ROW batch (author order 2026-10-08, 「解绑放到卡片外层」 over the
//    landed item face — supersedes the weixin-binditem ruling ④ above): a
//    CREATED card surfaces the unbind entry ON its collapsed row, so unbinding
//    no longer requires expanding (the author's reference frame: the expanded
//    weixin card was the only place the control lived; the collapsed feishu row
//    showed no action at all). The entry is the row button's SIBLING chip in a
//    `.social-item-line` wrapper — a nested <button> would be invalid HTML and
//    would let the destructive action share the accordion's click, so state ⑤'s
//    no-shared-click guarantee is kept by DOM structure exactly as the body
//    placement kept it. Reused as-is: the `[data-archive]` handler (including
//    the archive.hint hover copy — there is no confirm dialog to inherit), the
//    unplug danger icon, the cfg-btn ghost family; NO glass chrome (the chip
//    must not compete with the pill it sits beside). [[faceCreated]] gates the
//    chip: not-created / binding cards render none. The expanded face keeps
//    every other detail it had (the feishu C1 live block is untouched); the
//    created weixin body is empty by design — its only detail moved to the row.
// Zero engine change throughout (GET /api/social/channels stays read-only).

import { t } from './i18n.js';
import { escapeHtml, createIconsIn, statusIcon } from './utils.js';
import { toggleHTML, bindToggle, setToggleState } from './toggle.js';
import { getAuthToken } from './neblink.js';
import {
  SOCIAL_CHANNELS, visibleChannels, channelById, storedKey, channelStatus, channelProblem, fieldValues,
} from './socialChannels.js';

// ── State ────────────────────────────────────────────────────────────────
/** Last config truth read from the backend, keyed by channel id.
 *  @type {Record<string, {enabled?: boolean, fields?: Record<string, string>}>} */
let configByChannel = {};
/** Whether the last `fetchConfig()` SUCCEEDED (feishu-boot-seal, 2026-09-28).
 *  🔴 Without this flag an unready panel is indistinguishable from an empty
 *  one: `configByChannel` is `{}` both before the first read and after a failed
 *  one, so `!!(configByChannel[id] && configByChannel[id].enabled)` renders an
 *  OFF switch for a channel that IS enabled — and a save built off that face
 *  writes `enabled:false`, closing the user's channel on the panel's guess.
 *  Unready ⇒ the switch renders unknown/disabled and [[saveChannel]] refuses.
 *  @type {boolean} */
let configReady = false;
/** Last probe triples (exists / modeOk / readable), keyed by channel id.
 *  @type {Record<string, Record<string, {exists?: boolean, modeOk?: boolean, readable?: boolean}>>} */
let probeByChannel = {};
/** Live adapter registration truth per channel id, read off the probe face's
 *  `adapterRegistered` (feishubridge). A failed probe fetch leaves the last
 *  reading in place — a transient backend hiccup must not fake-unlink a card.
 *  @type {Record<string, boolean>} */
let registeredByChannel = {};
/** Focusable-element trap state. */
let lastFocusedBeforeOpen = /** @type {HTMLElement|null} */ (null);
let initialized = false;
/** Fields the user has typed since the last save, keyed by `<channelId>.<fieldKey>`.
 *  Secret values live ONLY here and ONLY until the POST body is built — they are
 *  never written into the DOM as a value and never read back from the backend.
 *  @type {Record<string, string>} */
let draftValues = {};
/** Live-connection face per channel id (contract C1, endpoint
 *  /api/social/channels/feishu/connection). `available: false` = the endpoint
 *  does not exist on this backend → the block reads unknown everywhere; the
 *  STORED app_id is never a stand-in for the live one (that fallback is the
 *  green-pill gap the diagnosis report pinned). `enabled: null` = unknown.
 *  @type {Record<string, {available: boolean, appId: string, appName: string, fingerprint: 'match'|'mismatch'|'unknown', enabled: boolean|null}>} */
let liveByChannel = {};
/** Default-session face per channel id (contract C4): `sessionId: null` =
 *  unset; `available: false` = endpoint absent on this backend.
 *  @type {Record<string, {available: boolean, sessionId: string|null, sessionName: string}>} */
let defaultSessionByChannel = {};
/** Session list for the default-session selector (existing GET /api/sessions).
 *  `agentName` is retained next to id+name (author ruling 2026-09-30): the
 *  default-session control is pinned to the Nebula session, so the pane has to
 *  be able to tell that session apart. The payload omits the field when it
 *  equals the root identity's name, hence the '' fallback on read.
 *  @type {{available: boolean, items: Array<{id: string, name: string, agentName: string}>}|null} */
let sessionsCache = null;

/** The root agent's identity name — the frontend mirror of the backend's single
 *  source `RootAgentIdentity.Name` (src/main/scala/nebflow/actor/
 *  RootAgentIdentity.scala). A session IS the Nebula session when its
 *  `agentName` is absent or equals this name: the backend reads an absent
 *  agentName as the root identity (SessionStore.listSessionsByAgent,
 *  WebSocketRoutes.isRootIdentitySession). New chats are pinned to it (author
 *  ruling 2026-09-30) — the control offers no other choice.
 *  @type {string} */
const ROOT_AGENT_NAME = 'Nebula';

// ── Backend adapter (same token pattern as plugins.js / agentManager.js) ──
/**
 * Narrow option shape on purpose: this module only ever issues JSON requests,
 * and a `RequestInit`-wide type is what makes the header merge un-inferable
 * (`checkJs` gate: new files must be clean).
 * @typedef {object} ApiOpts
 * @property {string} [method]
 * @property {Record<string, string>} [headers]
 * @property {string} [body]
 */
/**
 * @param {string} path
 * @param {ApiOpts} [opts]
 * @returns {Promise<Response>}
 */
async function api(path, opts = {}) {
  /** @type {Record<string, string>} */
  const headers = { ...(opts.headers || {}) };
  const tok = getAuthToken();
  if (tok) headers['Authorization'] = `Bearer ${tok}`;
  return fetch(path, { ...opts, headers });
}

/** GET /api/social/channels — never returns secret CONTENT, only paths. */
async function fetchConfig() {
  const resp = await api('/api/social/channels');
  if (!resp.ok) throw new Error(`GET /api/social/channels ${resp.status}`);
  const data = await resp.json();
  return data && data.channels ? data.channels : {};
}

/** GET /api/social/probe?channel=<id> — mechanical triple, no content. Also
 *  records the live `adapterRegistered` flag (phase 2) next to the triples.
 *  (The live-connection face is a separate endpoint — [[fetchConnection]].) */
async function fetchProbe(id) {
  const resp = await api(`/api/social/probe?channel=${encodeURIComponent(id)}`);
  if (!resp.ok) throw new Error(`GET /api/social/probe ${resp.status}`);
  const data = await resp.json();
  probeByChannel[id] = data && data.secrets ? data.secrets : {};
  registeredByChannel[id] = !!(data && data.adapterRegistered === true);
  return probeByChannel[id];
}

// ── Contract faces: C1 live app / C4 default session ────────────────────
// feishu-panel: these read the backend contract pinned by the backend leg's
// delivery (dispatcher relay 2026-09-27 21:16 — that relay wins over the
// task-book prose wherever the two differ):
//   C1 GET /api/social/channels/feishu/connection →
//      {channel, enabled, adapterRegistered, connected, liveAppId|null,
//       liveAppIdFp|null, liveAppName|null, storedAppId|null,
//       storedAppIdFp|null, fingerprintMatch:"match"|"mismatch"|"unknown"}
//   C4 GET .../feishu/default-session → {sessionId|null};
//      PUT {"sessionId":"…"} persists, {"sessionId":null} or "" clears.
//   · C3 (GET .../feishu/bindings) — the chat→session binding list — is no
//     longer read: its ONLY consumer was the display block the socpanel-min
//     batch removed (author ruling 2026-10-01; see the header). The endpoint
//     itself is untouched server-side; only this reader is gone. The C4 GET
//     survives the block's removal because the boot-time convergence
//     ([[pinDefaultSessions]]) reads it to decide whether a write is needed.
//
// Every reader is total: a 404 or a missing field degrades to an explicit
// unavailable/unknown reading — the panel never errors and never blanks.
//
// Two pinned semantics: (a) `fingerprintMatch === "unknown"` is an
// information gap, NOT a warning (only "mismatch" reaches the failed face);
// (b) the live-app line never falls back to the STORED app_id — that
// fallback is exactly the green-pill gap the diagnosis report pinned
// (registered ≠ registered to THIS app).

/** C1/C4 endpoints on the feishu social routes. */
const FEISHU_CONNECTION_URL = '/api/social/channels/feishu/connection';
const FEISHU_DEFAULT_SESSION_URL = '/api/social/channels/feishu/default-session';

/** The channels whose created face reads the feishu-specific contract faces
 *  (C1/C4). weixin-scanbind: deliberately FEISHU-ONLY — these endpoints are
 *  feishu routes, and a weixin card must never fetch its live state from them
 *  (its default session resolves server-side through the bridge seam). The
 *  Nebula pin write-back inherits the same restriction. */
const CONTRACT_FACE_CHANNELS = ['feishu'];

/** The channels whose card renders as an ITEM ROW (accordion) instead of the
 *  two-face card. weixin-binditem (author order 2026-10-07 09:22): the weixin
 *  binding face collapses to ONE row; the working faces live inside the row's
 *  expandable body ([[itemCardHTML]]). weixin-binditem-3cuts (author order
 *  2026-10-07, ruling ③): feishu JOINS the set — the two-face card retires and
 *  the C1 live value rides the EXPANDED face ([[itemCardHTML]]'s contract
 *  branch). The channel-scoped constant is the [[CONTRACT_FACE_CHANNELS]]
 *  shape — one named set, not an id literal in the render path. */
const ITEM_FACE_CHANNELS = ['weixin-ilink', 'feishu'];

/** Which item-face cards are expanded, keyed by channel id. Expansion is VIEW
 *  state only: the body is ALWAYS rendered (the scan nodes must exist — the
 *  scan flow re-queries and writes them per tick), `hidden` only withdraws it
 *  from the visible face. Cleared on panel close (default = the row only) and
 *  on the two transitions the order pins: scan `done` (state ③: auto-collapse
 *  back to the item view) and archive completion (same default-row reading for
 *  the recreate leg). */
const itemExpanded = new Set();

/**
 * First non-empty string among the candidates (contract-field tolerance: a
 * missing/null key degrades — never throws).
 * @param {...unknown} cands
 * @returns {string}
 */
function firstString(...cands) {
  for (const c of cands) { if (typeof c === 'string' && c.trim()) return c.trim(); }
  return '';
}

/**
 * C1: the live-connection face for one channel. Non-OK / absent endpoint ⇒
 * available:false → every field reads unknown, and the UI never substitutes
 * the stored app_id for the live one.
 * @param {string} channelId
 * @returns {Promise<void>}
 */
async function fetchConnection(channelId) {
  try {
    const resp = await api(FEISHU_CONNECTION_URL);
    if (!resp.ok) throw new Error(`GET connection ${resp.status}`);
    const data = await resp.json();
    const fp = data && data.fingerprintMatch;
    liveByChannel[channelId] = {
      available: true,
      appId: firstString(data && data.liveAppId),
      // liveAppName is null by contract today (the SDK exposes no app-name
      // source; the key is reserved) — the display tolerates null and shows
      // the id alone rather than an empty row.
      appName: firstString(data && data.liveAppName),
      fingerprint: fp === 'match' || fp === 'mismatch' ? fp : 'unknown',
      enabled: typeof (data && data.enabled) === 'boolean' ? data.enabled : null,
    };
  } catch {
    liveByChannel[channelId] = { available: false, appId: '', appName: '', fingerprint: 'unknown', enabled: null };
  }
}

/**
 * C4 read: the persisted default session (null = unset). Non-OK/absent ⇒
 * available:false (the selector degrades to a readable note).
 * @param {string} channelId
 * @returns {Promise<void>}
 */
async function fetchDefaultSession(channelId) {
  try {
    const resp = await api(FEISHU_DEFAULT_SESSION_URL);
    if (!resp.ok) throw new Error(`GET default-session ${resp.status}`);
    const data = await resp.json();
    defaultSessionByChannel[channelId] = {
      available: true,
      sessionId: data && typeof data.sessionId === 'string' && data.sessionId ? data.sessionId : null,
      sessionName: firstString(data && data.sessionName, data && data.session_name),
    };
  } catch {
    defaultSessionByChannel[channelId] = { available: false, sessionId: null, sessionName: '' };
  }
}

/** Session list for the default-session selector (existing REST face).
 * @returns {Promise<void>}
 */
async function fetchSessions() {
  try {
    const resp = await api('/api/sessions');
    if (!resp.ok) throw new Error(`GET /api/sessions ${resp.status}`);
    const data = await resp.json();
    const raw = Array.isArray(data && data.sessions) ? data.sessions : [];
    sessionsCache = {
      available: true,
      items: raw.map((s) => ({
        id: firstString(s && s.id, s && s.sessionId),
        name: firstString(s && s.name, s && s.displayName, s && s.title),
        // Retained for the pinned default-session control (author ruling
        // 2026-09-30): '' = absent on the wire = the root identity's session.
        agentName: firstString(s && s.agentName),
      })).filter((s) => s.id),
    };
  } catch {
    sessionsCache = { available: false, items: [] };
  }
}

/**
 * The Nebula session out of the cached session list: a session whose
 * `agentName` is absent (omitted by the encoder when it equals the root name)
 * or names the root identity. Where several qualify, the payload's own order
 * decides — /api/sessions already sorts by recency, so this stays deterministic
 * and never picks a non-root session by name coincidence.
 * @returns {{id: string, name: string, agentName: string}|null}
 */
function nebulaSession() {
  if (!sessionsCache || !sessionsCache.available) return null;
  return sessionsCache.items.find((s) => !s.agentName || s.agentName === ROOT_AGENT_NAME) || null;
}

// ── Selection helpers ────────────────────────────────────────────────────
/** @returns {HTMLElement|null} */
function overlay() { return document.getElementById('social-overlay'); }
/** @returns {HTMLElement|null} */
function modal() { return document.getElementById('social-modal'); }
/** @returns {HTMLElement|null} */
function cardList() { return document.querySelector('#social-section-channels .social-card-list'); }

/**
 * @param {string} channelId
 * @returns {HTMLElement|null}
 */
function cardEl(channelId) {
  return /** @type {HTMLElement|null} */ (
    document.querySelector(`.social-card[data-channel="${channelId}"]`)
  );
}

/**
 * The panel's readiness state, exposed the standard way: `aria-busy` on the
 * channels section while a backend read/write is in flight. It is a real UI
 * state (assistive tech announces it), not a test hook — and it is what makes
 * "the panel has converged on backend truth" observable from outside, so no
 * caller has to guess with a timer.
 * @param {boolean} on
 */
function setBusy(on) {
  const el = document.getElementById('social-section-channels');
  if (el) el.setAttribute('aria-busy', on ? 'true' : 'false');
}

/**
 * The scanBind face discriminator (contract C5): the card face is "created"
 * exactly when the backend holds the channel enabled. Archive (enabled=false)
 * is the only off path — it flips the face back to not-created while the
 * stored credentials stay on disk.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {boolean}
 */
function faceCreated(ch) {
  const cfg = configByChannel[ch.id];
  return !!(cfg && cfg.enabled === true);
}

/**
 * The switch position for one card, read off BACKEND truth only
 * (feishu-boot-seal, 2026-09-28). While the config read is not ready there is
 * no truth to render: the switch renders OFF but is DISABLED (the caller sets
 * `disabled`), which is read as "unknown", never as "the user turned it off".
 * A plain OFF here is what let an unready panel hand `enabled:false` to a save.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {boolean}
 */
function switchOnFor(ch) {
  if (!configReady) return false; // unknown ⇒ off-looking, but disabled (never a claim)
  return !!(configByChannel[ch.id] && configByChannel[ch.id].enabled);
}

/**
 * Display status = the mechanical state machine ([[channelStatus]]) plus ONE
 * composed reading (contract C2): a fingerprint MISMATCH means the live
 * bridge is holding a DIFFERENT app than the stored credential — displayed as
 * failed even though the adapter is registered. "unknown" is an information
 * gap, NOT an alarm (contract C2: 不得渲染为错误态). channelStatus itself is
 * untouched; this composition lives with its only consumer.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function displayStatus(ch) {
  const base = channelStatus(ch, configByChannel[ch.id], probeByChannel[ch.id], registeredByChannel[ch.id]);
  const live = liveByChannel[ch.id];
  if (base === 'connected' && live && live.fingerprint === 'mismatch') return 'failed';
  // bind-transactional 批 (2026-10-20): the card's last-bind error (the engine's
  // rollback arm writes `bindError` onto the stored channel entry) composes as
  // an explicit failed state — a failed bind is NEVER rendered as configured.
  // The hint row (cardHint) carries the exact failed steps; a fresh bind
  // attempt (save) clears the error and the card returns to its base state.
  const bindErr = configByChannel[ch.id] && configByChannel[ch.id].bindError;
  if (bindErr) return 'failed';
  return base;
}

// ── Rendering ────────────────────────────────────────────────────────────
/**
 * The human credential status line (social-fix, author ruling 2026-09-28):
 * the probe FACE still answers the mechanical triples, but the card never
 * shows internal check names — the reading renders as plain language.
 *
 * socpanel-min wave-2 (author ruling 2026-10-01): the happy-path line
 * ("configured · permissions normal") is REMOVED — a healthy card says nothing,
 * because the status pill already carries that state. Only the contradiction
 * still renders here ("missing — scan again"); the happy path returns '' and
 * the caller's `.social-card-hint` takes its existing `hidden` branch.
 * The triple-contradiction path ([[channelProblem]] ⇒ configInvalid) keeps its
 * actionable hint with the file path — this line is the non-invalid reading only.
 * @param {Record<string, {exists?: boolean, modeOk?: boolean, readable?: boolean}>|undefined} probe
 * @returns {string} the scanning-again line, or '' when the credential is clean
 */
function credentialLine(probe) {
  if (!probe) return '';
  const keys = Object.keys(probe);
  if (!keys.length) return '';
  const p = probe[keys[0]];
  if (!p) return '';
  const broken = p.exists === false || p.modeOk === false || p.readable === false;
  return broken ? t('social.credential.missing') : '';
}

/**
 * Per-card hint: the credential path when a probe contradicts the config
 * (W9 requires the path text), the offending pattern otherwise. Reads the SAME
 * contradiction the pill was derived from (`channelProblem`) — a hint can never
 * describe a different problem than the state it sits next to.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @param {string} status
 * @returns {string}
 */
function cardHint(ch, status) {
  if (status === 'configInvalid') {
    const p = channelProblem(ch, configByChannel[ch.id], probeByChannel[ch.id]);
    if (p && p.kind === 'ref') {
      const probe = (probeByChannel[ch.id] || {})[p.field.key] || {};
      return probe.exists === false
        ? t('social.hint.missingRef', { path: p.path })
        : t('social.hint.modeBad', { path: p.path });
    }
    const values = fieldValues(ch, configByChannel[ch.id]);
    const bad = ch.fields.filter((f) => {
      const v = String(values[storedKey(f)] ?? '').trim();
      return !!v && !!f.pattern && !new RegExp(f.pattern).test(v);
    });
    return t('social.hint.pattern', { field: bad.map((f) => t(f.i18n)).join(', ') });
  }
  // bind-transactional 批 (2026-10-20): precedence under configInvalid —
  //   ① the last-bind error verbatim (the engine recorded the exact failed
  //     steps; the pill is failed, the hint names where it died);
  //   ② the runtime health face (reply-loss strikes, `channels[].health`) —
  //     a DEGRADED channel is a warning line, never a flipped pill: the
  //     redelivery leg is recovering the lost replies in the background.
  const cfg = configByChannel[ch.id] || {};
  if (cfg.bindError) return String(cfg.bindError);
  if (cfg.health && Array.isArray(cfg.health.strikes) && cfg.health.strikes.length) {
    const s = cfg.health.strikes[0];
    return t('social.hint.health', { reason: String(s.reason || ''), count: Number(s.count || 0) });
  }
  return credentialLine(probeByChannel[ch.id]);
}

/**
 * One card. The scanBind channels render the two-face card; weixin-binditem:
 * the item-face channels ([[ITEM_FACE_CHANNELS]]) render the accordion card;
 * every other channel keeps the plain card shape unchanged.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function cardHTML(ch) {
  if (ch.scanBind && ITEM_FACE_CHANNELS.includes(ch.id)) return itemCardHTML(ch);
  return ch.scanBind ? scanBindCardHTML(ch) : plainCardHTML(ch);
}

/** Status pill content (ui-status-redesign S13): 图案 pill 保留红底红边，词转中性，
 *  语义形状由词前图标区分（封禁=ban / 配置有误=triangle-alert / 断链失败=unplug）。
 *  @param {string} status channelStatus key
 *  @returns {string} inner HTML（词文本由调用方 escape） */
function pillInner(status, word) {
  const icon = status === 'configInvalid' ? statusIcon('triangle-alert', 11, 'st-err')
    : status === 'failed' ? statusIcon('unplug', 11, 'st-err')
    : '';
  return icon ? `${icon}<span>${escapeHtml(word)}</span>` : escapeHtml(word);
}

/**
 * Plain card (channels without the scan-bind main path): head carries icon +
 * name + `[data-status]` + the shared switch (W4 requires all four inside
 * `.social-card-head`).
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function plainCardHTML(ch) {
  const cfg = configByChannel[ch.id];
  const status = channelStatus(ch, cfg, probeByChannel[ch.id], registeredByChannel[ch.id]);
  const hint = cardHint(ch, status);
  const fields = ch.fields.map((f) => fieldHTML(ch, f)).join('');
  const on = switchOnFor(ch);
  return `<div class="social-card" data-channel="${escapeHtml(ch.id)}">
      <div class="social-card-head">
        <i data-lucide="${escapeHtml(ch.icon)}" class="social-card-icon"></i>
        <span class="social-card-name">${escapeHtml(t(ch.nameKey))}</span>
        <span class="social-status-pill plugins-state-pill" data-status="${status}">${pillInner(status, t(`social.status.${status}`))}</span>
        ${toggleHTML({
          on,
          label: t(ch.nameKey),
          disabled: !configReady,
          attrs: `data-toggle-channel="${escapeHtml(ch.id)}"`,
        })}
      </div>
      <div class="social-card-desc">${escapeHtml(t(ch.descKey))}</div>
      <div class="social-card-fields">${fields}</div>
      <div class="social-card-hint"${hint ? '' : ' hidden'}>${hint ? statusIcon('triangle-alert', 12, 'st-err') + escapeHtml(hint) : ''}</div>
      <div class="social-card-actions">
        <button type="button" class="glass-control cfg-btn cfg-btn-sm social-btn-primary" data-save="${escapeHtml(ch.id)}">${escapeHtml(t('social.action.save'))}</button>
        <button type="button" class="glass-control cfg-btn cfg-btn-sm" data-recheck="${escapeHtml(ch.id)}">${escapeHtml(t('social.action.recheck'))}</button>
        <span class="social-save-state" data-save-state="${escapeHtml(ch.id)}" role="status" aria-live="polite"></span>
      </div>
    </div>`;
}

/**
 * The in-card scan block — ONE source for both faces: the two-face card renders
 * it directly ([[scanBindCardHTML]]); the item face renders it inside the row's
 * expandable body ([[itemCardHTML]]). The container and its `data-scan-qr` /
 * `data-scan-code` / `data-scan-status` children are [[scanNode]]'s anchors;
 * the `data-scan-inline` attribute stays retired (socpanel-min wave-2 P4).
 * @returns {string}
 */
function scanInlineHTML() {
  return `
      <div class="social-scan-inline">
        <div class="social-scan-qr" data-scan-qr></div>
        <div class="social-scan-code" data-scan-code hidden></div>
        <div class="social-scan-status" data-scan-status role="status" aria-live="polite"></div>
      </div>`;
}

/**
 * The scanBind card (feishu-panel two faces; social-fix 2026-09-28): scan and
 * configure share ONE view layer — no sub-dialog, no manual form.
 *   · not-created — the QR lives IN the card with its status line
 *     ([[beginInlineScan]] auto-runs on this face); scan-to-create is the
 *     ONLY creation path.
 *   · created — the live-app block (C1, narrowed by the socpanel-min batch to
 *     at most the application row) and archive (C5).
 * socpanel-min wave-2 (author ruling 2026-10-01): the card-wide description
 * line is removed from THIS card only ([[plainCardHTML]] keeps its own — it
 * belongs to the sealed-family surface, out of this batch's scope), and the
 * archive's one-line hint moved from a permanent line to the unbind button's
 * `title` (同一既有文案，hover 时机提示). The `data-scan-inline` attribute is
 * gone; the container and its `data-scan-qr` / `data-scan-code` /
 * `data-scan-status` children stay ([[scanNode]] reads them).
 * The head keeps icon + name + status pill; the enable switch is folded into
 * archive (enabled=false IS the archive — no separate disabled face exists).
 * weixin-binditem-3cuts (ruling ③): NO visible channel rides this branch any
 * more — both scanBind cards are item faces ([[ITEM_FACE_CHANNELS]]), so this
 * renderer is the FALLBACK path for a future scanBind channel that has not
 * joined the item face (kept, not deleted: [[cardHTML]]'s routing still names
 * it, and the two-face semantics this function documents stay one constant
 * away).
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function scanBindCardHTML(ch) {
  const created = faceCreated(ch);
  const status = displayStatus(ch);
  const hint = cardHint(ch, status);
  const head = `<div class="social-card-head">
        <i data-lucide="${escapeHtml(ch.icon)}" class="social-card-icon"></i>
        <span class="social-card-name">${escapeHtml(t(ch.nameKey))}</span>
        <span class="social-status-pill plugins-state-pill" data-status="${status}">${pillInner(status, t(`social.status.${status}`))}</span>
      </div>`;
  const scanInline = scanInlineHTML();
  const face = created
    ? `${liveBlockHTML(ch)}
       <div class="social-card-actions">
         <button type="button" class="glass-control cfg-btn cfg-btn-sm social-btn-danger" data-archive="${escapeHtml(ch.id)}" title="${escapeHtml(t(archiveKey(ch.id, 'hint')))}">${statusIcon('unplug', 12, 'st-err')}${escapeHtml(t(archiveKey(ch.id, 'action')))}</button>
         <span class="social-save-state" data-save-state="${escapeHtml(ch.id)}" role="status" aria-live="polite"></span>
       </div>`
    : scanInline;
  // socialux-scanbind (author screenshot ruling 2026-10-06): the scanBind card
  // carries ONE modifier class so its hint line can be axis-centered against
  // the scan-status line in css/social.css (explicit class, not :has() — same
  // convention flowMap.css states for state-dependent card faces).
  return `<div class="social-card social-card-scanbind" data-channel="${escapeHtml(ch.id)}">
      ${head}
      ${face}
      <div class="social-card-hint"${hint ? '' : ' hidden'}>${hint ? statusIcon('triangle-alert', 12, 'st-err') + escapeHtml(hint) : ''}</div>
    </div>`;
}

/**
 * The item-face card (weixin-binditem, author order 2026-10-07 09:22; amended
 * the same day by the 3cuts order — rulings ①②③): the binding face collapses
 * to ONE row — icon + name + the status pill, NO chevron (ruling ②: the WHOLE
 * row is the click target, so a direction hint is noise; the row stays a real
 * <button> and keeps `aria-expanded` / `aria-controls`, which carry the
 * expand/collapse state to assistive tech without any arrow) — and the working
 * faces live inside the row's expandable body:
 *   · not-created (state ①) — the QR binding face ([[scanInlineHTML]]) whole.
 *     The auto-begun session ([[maybeBeginScan]] is unchanged) writes into the
 *     hidden nodes, so expanding shows the QR mid-flow; expanding a card
 *     without a live session (re)begins one ([[toggleItemFace]] —
 *     [[beginInlineScan]] is idempotent).
 *   · created (states ③④) — for the contract channels
 *     ([[CONTRACT_FACE_CHANNELS]], i.e. feishu; ruling ③) the C1 live block:
 *     the real-time value shows ONLY inside the expanded state, the same
 *     accordion paradigm as the weixin card (default row / click to expand /
 *     click to collapse). The weixin item face still renders NO live block:
 *     the weixin channel has no connection endpoint ([[CONTRACT_FACE_CHANNELS]]
 *     is feishu-only), so its live container was permanently empty — exactly
 *     the empty-bar form the first order retired.
 *   ★ unbind-row (author order 2026-10-08): the unbind entry no longer rides
 *     the EXPANDED face — created cards surface it ON the collapsed row (a
 *     low-key chip in the pill's row, unbindable without expanding), so
 *     ruling ④'s "row stays single-purpose" trade is retired. The chip is the
 *     row button's SIBLING (a nested <button> is invalid HTML and would let
 *     the destructive action share the toggle's click — state ⑤'s guarantee,
 *     kept by DOM structure, not by handler ordering). Not-created / binding
 *     cards render no entry ([[faceCreated]] gates it), and the expanded face
 *     keeps every other detail it had (the feishu live block is untouched).
 *     ★ unbind-icon (author order 2026-10-08, same-day amendment): the chip
 *     drops its WORD — the bare unplug glyph (the original unbind button's
 *     broken-link icon) is the whole entry, the archive.hint copy riding the
 *     `title` (hover tooltip = the a11y name + discoverability, per the
 *     ruling). And the bound ROW carries NO application id: the weixin bot id
 *     and the feishu app_id never ride the row — the bound row reads icon +
 *     name + 已接入 pill + unbind icon, nothing else (the feishu live value
 *     stays behind the click, in the body; the weixin bound body is empty).
 * Scan `done` drops the expansion ([[tickSession]]), so the card lands back on
 * the row with the bound badge; archive does the same ([[archiveChannel]] —
 * ruling ①: the unbind fallback to the item view is the SHIPPED behaviour,
 * confirmed with an evidence frame, zero code).
 *   ★ social-fix-confirm (author order 2026-10-09, 已绑定行不可展开＋appId 不再
 *     展示): a CREATED card renders NO interactive face at all — the row is a
 *     static <div> (no [data-item-toggle], no aria-expanded/aria-controls, no
 *     pointer), the expandable body is not built, and the C1 live block is not
 *     rendered: the application id never surfaces on the panel in any state
 *     (the value stays server-side/config-layer; the fingerprint mismatch still
 *     reaches the pill via [[displayStatus]]'s in-memory reading). The accordion
 *     interaction is the not-created face's alone (scan-to-bind needs it); the
 *     unbind entry — now behind the shared glass confirm ([[requestArchive]]) —
 *     is the bound row's only action, and it rides the row line as before.
 * The head keeps the `.social-card-head` class (W4's judging surface) on the
 * row <button> itself — one tab stop, Enter/Space toggles; the status pill is
 * the SAME pill the two-face card rendered (state ②: badge semantics ride the
 * existing state machine unchanged). The hint line stays OUTSIDE the body: a
 * configInvalid reading explains the pill, which is visible while collapsed,
 * so the explanation must be too.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function itemCardHTML(ch) {
  const created = faceCreated(ch);
  const status = displayStatus(ch);
  const hint = cardHint(ch, status);
  const expanded = itemExpanded.has(ch.id);
  const bodyId = `social-item-body-${escapeHtml(ch.id)}`;
  // social-fix-confirm (author order 2026-10-09, 已绑定行不可展开): a BOUND card
  // renders a STATIC row — a <div>, not the accordion <button>: no
  // [data-item-toggle], no aria-expanded / aria-controls, no click face at all
  // (the pointer cursor belongs to the not-created row only — see
  // .social-item-row-bound in css/social.css). The row's composition is
  // unchanged: icon + name + status pill (unbind-icon ② — and no application id
  // anywhere, see the body note below).
  const rowInner = `<i data-lucide="${escapeHtml(ch.icon)}" class="social-card-icon"></i>
        <span class="social-card-name">${escapeHtml(t(ch.nameKey))}</span>
        <span class="social-status-pill plugins-state-pill" data-status="${status}">${pillInner(status, t(`social.status.${status}`))}</span>`;
  const row = created
    ? `<div class="social-card-head social-item-row social-item-row-bound">${rowInner}</div>`
    : `<button type="button" class="social-card-head social-item-row" data-item-toggle="${escapeHtml(ch.id)}"
        aria-expanded="${expanded ? 'true' : 'false'}" aria-controls="${bodyId}">${rowInner}</button>`;
  // unbind-row (author order 2026-10-08), amended by unbind-icon (same day):
  // the unbind entry + its save-state span ride the ROW LINE as the row
  // button's sibling (never a descendant — see the JSDoc above), and the
  // entry is ICON-ONLY — the bare unplug glyph, no word on the row; the
  // archive.hint copy stays on the `title` (hover tooltip; the a11y name),
  // the action word stays in the locale table for the fallback renderer
  // ([[scanBindCardHTML]]) and the save-state flow. Same data-archive
  // handler, same danger icon; the classes are the existing quiet ghost
  // family (cfg-btn/cfg-btn-sm + social-btn-danger), no glass chrome — a
  // secondary action next to the pill, not a competing one.
  const unbind = created
    ? `<button type="button" class="cfg-btn cfg-btn-sm social-btn-danger social-item-unbind" data-archive="${escapeHtml(ch.id)}" title="${escapeHtml(t(archiveKey(ch.id, 'hint')))}">${statusIcon('unplug', 12, 'st-err')}</button>
        <span class="social-save-state" data-save-state="${escapeHtml(ch.id)}" role="status" aria-live="polite"></span>`
    : '';
  // 3cuts ③ rode the contract channels' C1 live value into the EXPANDED body;
  // social-fix-confirm retires that face: a CREATED card builds NO body at all
  // — nothing to expand, and the application id is not rendered on the panel
  // in ANY state (the value stays server-side / config-layer only; the
  // fingerprint verdict still reaches the pill through [[displayStatus]]'s
  // in-memory reading). The scan body is the not-created face's alone.
  const body = created ? '' : scanInlineHTML();
  return `<div class="social-card social-card-scanbind social-card-itemface" data-channel="${escapeHtml(ch.id)}">
      <div class="social-item-line">${row}${unbind}</div>
      ${created ? '' : `<div class="social-item-body" id="${bodyId}" data-item-body${expanded ? '' : ' hidden'}>${body}</div>`}
      <div class="social-card-hint"${hint ? '' : ' hidden'}>${hint ? statusIcon('triangle-alert', 12, 'st-err') + escapeHtml(hint) : ''}</div>
    </div>`;
}

/**
 * Expand/collapse one item-face card (weixin-binditem, states ①⑤): the row
 * toggles IN PLACE — `aria-expanded` and the body's `hidden` attribute flip on
 * the live DOM, never a [[renderAll]] (a rebuild mid-scan would detach the QR
 * box while the session's `lastQrUrl` still holds the rendered URL, and the
 * next poll tick — which re-renders only on a URL CHANGE — would leave the
 * box empty). Expanding a not-created card also (re)begins its scan session:
 * [[beginInlineScan]] is idempotent while a session is live, so this is a
 * no-op on the auto-begun path and a genuine retry after a failed one.
 * @param {string} channelId
 */
function toggleItemFace(channelId) {
  const card = cardEl(channelId);
  if (!card) return;
  const body = card.querySelector('[data-item-body]');
  const row = card.querySelector('[data-item-toggle]');
  if (!(body instanceof HTMLElement) || !(row instanceof HTMLElement)) return;
  const expand = !itemExpanded.has(channelId);
  if (expand) itemExpanded.add(channelId); else itemExpanded.delete(channelId);
  row.setAttribute('aria-expanded', expand ? 'true' : 'false');
  if (expand) body.removeAttribute('hidden'); else body.setAttribute('hidden', '');
  const ch = channelById(channelId);
  if (expand && ch && ch.scanBind && !faceCreated(ch)) void beginInlineScan(channelId);
}

/**
 * The created face's "which app is the bridge actually holding" block
 * (contract C1, endpoint /feishu/connection). Values come from the LIVE face
 * only — no fallback to the stored app_id (that fallback is exactly the
 * green-pill gap the diagnosis report pinned: registered ≠ registered to
 * THIS app). `liveAppName` is null by contract today: the id is shown alone.
 * A backend without the endpoint reads "unknown", never wrong.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function liveBlockHTML(ch) {
  const live = liveByChannel[ch.id]
    || { available: false, appId: '', appName: '', fingerprint: 'unknown', enabled: null };
  // socpanel-min wave-2 (author ruling 2026-10-01): the minimal face keeps at
  // most ONE row — the live application itself. The fingerprint row (C1's
  // `data-fp` verdict + `social-live-warn`), the bridge row (`data-live-enabled`)
  // and this row's label span are all gone; a fingerprint mismatch still reaches
  // the user through the status pill ([[displayStatus]] reads the MEMORY value,
  // never the DOM). The row is CONDITIONAL on the very condition its value picks
  // on (`available && appId`) — an unlinked bridge renders the empty container,
  // never the `appUnknown` explainer copy (that key stays in the locale table,
  // merely unrendered). No post-render text comparison decides this.
  const showApp = !!(live.available && live.appId);
  const appText = showApp
    ? (live.appName ? `${live.appName} · ${live.appId}` : live.appId)
    : '';
  return `<div class="social-live" data-live="${escapeHtml(ch.id)}">
      ${showApp ? `<div class="social-live-row"><span class="social-live-value">${escapeHtml(appText)}</span></div>` : ''}
    </div>`;
}

/**
 * One field row. Secret fields are write-only: `value` is never rendered from
 * the stored config — the stored value is a PATH and is shown as a state badge.
 * @param {any} ch channel definition
 * @param {any} f field definition
 * @returns {string}
 */
function fieldHTML(ch, f) {
  const stored = fieldValues(ch, configByChannel[ch.id])[storedKey(f)];
  const label = escapeHtml(t(f.i18n));
  const id = `social-${ch.id}-${f.key}`;
  if (f.kind === 'secret') {
    const state = stored ? t('social.secret.stored') : t('social.secret.empty');
    return `<label class="social-field" for="${id}">
        <span class="social-field-label">${label}</span>
        <input id="${id}" class="social-input" type="password" autocomplete="off"
               data-field="${escapeHtml(f.key)}" data-secret="1"
               placeholder="${escapeHtml(t('social.secret.placeholder'))}" value="">
        <span class="social-secret-state">${escapeHtml(state)}</span>
      </label>`;
  }
  if (f.kind === 'select') {
    // feishubridge: hidden regions are data, not choices — the sealed lark
    // entry stays in the definition layer but never renders (HIDE, NOT DELETE;
    // restoring = dropping its `hidden` flag).
    const opts = (ch.regions || [])
      .filter((r) => !r.hidden)
      .map((r) => {
        const sel = (stored || f.default) === r.key ? ' selected' : '';
        return `<option value="${escapeHtml(r.key)}"${sel}>${escapeHtml(t(`social.${ch.id}.region.${r.key}`))}</option>`;
      })
      .join('');
    return `<label class="social-field" for="${id}">
        <span class="social-field-label">${label}</span>
        <select id="${id}" class="social-input social-select" data-field="${escapeHtml(f.key)}">${opts}</select>
      </label>`;
  }
  return `<label class="social-field" for="${id}">
      <span class="social-field-label">${label}</span>
      <input id="${id}" class="social-input" type="text" autocomplete="off"
             data-field="${escapeHtml(f.key)}"
             placeholder="${escapeHtml(f.placeholder || '')}"
             value="${escapeHtml(stored || '')}">
    </label>`;
}

/** Full modal render (channels + the remote-access section captions). */
function renderAll() {
  const list = cardList();
  if (!list) return;
  // socialhide (PHASE 3): sealed channels (`hidden: true`) are data, not
  // cards. The visible set comes from the definition layer's ONE filter, so
  // the rendered face and `socialChannelCount()` cannot disagree; the
  // filtered order is the visible card order (§E.3 X3). Restoring a card =
  // dropping its flag in socialChannels.js — nothing changes here.
  list.innerHTML = visibleChannels().map(cardHTML).join('');
  createIconsIn(list);
  bindToggle(list, onToggleChange);
  applyStaticText();
}

/** In-place convergence for one card (no full re-render ⇒ typing is not lost). */
function refreshCard(channelId) {
  const ch = channelById(channelId);
  const card = cardEl(channelId);
  if (!ch || !card) return;
  const status = displayStatus(ch);
  const pill = card.querySelector('.social-status-pill');
  if (pill) {
    pill.setAttribute('data-status', status);
    pill.innerHTML = pillInner(status, t(`social.status.${status}`));
  }
  const liveEl = card.querySelector('[data-live]');
  if (liveEl instanceof HTMLElement) liveEl.outerHTML = liveBlockHTML(ch);
  const hintEl = card.querySelector('.social-card-hint');
  const hint = cardHint(ch, status);
  if (hintEl) {
    hintEl.innerHTML = hint ? statusIcon('triangle-alert', 12, 'st-err') + escapeHtml(hint) : '';
    if (hint) hintEl.removeAttribute('hidden'); else hintEl.setAttribute('hidden', '');
  }
  // The shared component only ever renders a <button> (toggle.js); assert it
  // for checkJs so the touched file stays checkJs-clean (TS won't narrow a
  // compound selector on its own — same shape as toggle.js's own note).
  const toggle = /** @type {HTMLButtonElement | null} */ (card.querySelector('.nb-toggle'));
  setToggleState(toggle, switchOnFor(ch));
  if (toggle) toggle.disabled = !configReady;
  card.querySelectorAll('.social-field').forEach((label) => {
    const input = label.querySelector('[data-field]');
    if (!input) return;
    const key = input.getAttribute('data-field') || '';
    const field = ch.fields.find((f) => f.key === key);
    if (!field || field.kind !== 'secret') return;
    const badge = label.querySelector('.social-secret-state');
    if (!badge) return;
    const stored = fieldValues(ch, configByChannel[ch.id])[storedKey(field)];
    badge.textContent = stored ? t('social.secret.stored') : t('social.secret.empty');
  });
}

/** Captions living in index.html (the shell is text-free; i18n.js is not ours
 *  to edit, so the panel translates its own subtree). */
function applyStaticText() {
  const map = [
    ['social-modal-title', 'social.title'],
    ['social-modal-close', 'social.action.close', 'title'],
    ['social-remote-title', 'social.remote.title'],
    ['social-remote-suspended', 'social.remote.suspended'],
    ['social-remote-safety-key', 'social.remote.safetyKey'],
    ['social-remote-safety-plain', 'social.remote.safetyPlain'],
    ['social-btn', 'social.btn.title', 'title'],
  ];
  for (const [id, key, attr] of map) {
    const el = document.getElementById(id);
    if (!el) continue;
    if (attr) el.setAttribute(attr, t(key)); else el.textContent = t(key);
  }
}

// ── Load / save ──────────────────────────────────────────────────────────
/** Read config + probe for every channel, then the C3/C4 faces for the
 *  scanBind channels; failures degrade to "not configured" / "unavailable"
 *  readings instead of blanking the panel. Ends with the auto-scan decision
 *  (not-created face ⇒ QR first). */
async function loadAll() {
  try {
    configByChannel = await fetchConfig();
    configReady = true;
  } catch {
    configByChannel = {};
    configReady = false; // unready ≠ "everything is off" — the switch must not lie
  }
  for (const ch of SOCIAL_CHANNELS) {
    try {
      probeByChannel[ch.id] = await fetchProbe(ch.id);
    } catch {
      probeByChannel[ch.id] = {};
    }
  }
  // feishu-panel: C1 + C4 faces ride along with the load (cheap GETs; each
  // degrades to its own unavailable reading on an older backend). C4 is read
  // for its BEHAVIOUR half only ([[pinDefaultSessions]] needs the stored value
  // to decide whether the convergence write is due) — its rendered control went
  // away with the socpanel-min batch, 2026-10-01. C3 was a display-only
  // transport and left with its only consumer.
  // weixin-scanbind: the faces stay FEISHU-ONLY ([[CONTRACT_FACE_CHANNELS]]) —
  // a second scanBind card must not fetch feishu's endpoints.
  for (const ch of SOCIAL_CHANNELS) {
    if (!ch.scanBind || !CONTRACT_FACE_CHANNELS.includes(ch.id)) continue;
    await fetchConnection(ch.id);
    await fetchDefaultSession(ch.id);
  }
  await fetchSessions();
  renderAll();
  setBusy(false);
  maybeBeginScan();
  // AFTER the render: the write-back's "saved" feedback lands in the live
  // render, and the card already shows the pinned Nebula option.
  await pinDefaultSessions();
}

/**
 * @param {HTMLButtonElement} el
 * @param {boolean} on
 */
async function onToggleChange(el, on) {
  const id = el.dataset.toggleChannel || '';
  const ch = channelById(id);
  if (!ch) return;
  // Not-ready panel: the switch is disabled, but a stale node from before the
  // last failed read could still deliver a click here — restore the rendered
  // state and refuse without a request (feishu-boot-seal, 2026-09-28).
  if (!configReady) {
    setToggleState(el, false);
    setSaveState(id, t('social.action.notReady'));
    return;
  }
  const ok = await saveChannel(id, on);
  if (!ok) setToggleState(el, !on); // rollback to backend truth
}

/**
 * The single write path (§D.3): field values + any typed secret go into the
 * request body ONCE; the reply's probe triple drives the pill/hint.
 * @param {string} channelId
 * @param {boolean} [enabledOverride]
 * @returns {Promise<boolean>}
 */
async function saveChannel(channelId, enabledOverride) {
  const ch = channelById(channelId);
  const card = cardEl(channelId);
  if (!ch || !card) return false;
  // 🔴 Unready panel ⇒ REFUSE, do not guess (feishu-boot-seal, 2026-09-28). The
  // switch renders off-disabled in that state, so a save here would send
  // `enabled:false` for a channel the backend may well have enabled — the exact
  // write that closed the user's channel on 2026-09-28 18:00:06. Only an
  // explicit caller-supplied override (a real user action) may proceed.
  if (!configReady && enabledOverride === undefined) {
    setSaveState(channelId, t('social.action.notReady'));
    return false;
  }
  const toggle = card.querySelector('.nb-toggle');
  const enabled = enabledOverride !== undefined
    ? enabledOverride
    : !!(toggle && toggle.classList.contains('on'));
  /** @type {Record<string, string>} */
  const fields = {};
  for (const f of ch.fields) {
    if (f.kind === 'secret') {
      const typed = draftValues[`${channelId}.${f.key}`];
      if (typed) fields[f.key] = typed; // plaintext: this request body, once
    } else {
      const input = card.querySelector(`[data-field="${f.key}"]`);
      const v = input && 'value' in input ? String(input.value) : '';
      fields[f.key] = v || f.default || '';
    }
  }
  setSaveState(channelId, t('social.action.saving'));
  setBusy(true);
  try {
    const resp = await api(`/api/social/channels/${encodeURIComponent(channelId)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled, fields }),
    });
    if (!resp.ok) {
      setSaveState(channelId, t('social.action.saveFailed', { code: resp.status }));
      return false;
    }
    const data = await resp.json();
    if (data && data.probe) probeByChannel[channelId] = data.probe;
    // The credential plaintext is gone from memory at this point; the stored
    // value the backend kept is a PATH, so a plain re-read cannot echo it back.
    for (const f of ch.fields) delete draftValues[`${channelId}.${f.key}`];
    configByChannel = await fetchConfig();            // step 2 → step 3: probe re-read
    try { probeByChannel[channelId] = await fetchProbe(channelId); } catch { /* keep POST probe */ }
    renderAll();
    setSaveState(channelId, t('social.action.saved'));
    return true;
  } catch {
    setSaveState(channelId, t('social.action.saveFailed', { code: 'io' }));
    return false;
  } finally {
    setBusy(false);
  }
}

/**
 * @param {string} channelId
 * @param {string} text
 */
function setSaveState(channelId, text) {
  const el = document.querySelector(`[data-save-state="${channelId}"]`);
  if (el) el.textContent = text;
}

/** Re-read the mechanical probe for one channel (manual "check again"). */
async function recheck(channelId) {
  try {
    probeByChannel[channelId] = await fetchProbe(channelId);
  } catch {
    probeByChannel[channelId] = {};
  }
  refreshCard(channelId);
}

// ── Archive (contract C5) ────────────────────────────────────────────────
/**
 * The unbind entry's door: the row icon opens the SHARED glass confirm
 * ([[window.__showConfirm]] — the secretsPanel.js requestDelete precedent,
 * visual with the global dialog), and only the confirm runs the EXISTING
 * [[archiveChannel]]. The channel name is interpolated into the body copy
 * ({name} → the channel's display name); the danger confirm button carries the
 * channel's existing archive.action word (「解除绑定」) via [[showConfirm]]'s
 * confirmLabel opt, 取消 sits beside it. Without the confirm helper the request
 * is refused — never unbind on an unconfirmed click (requestDelete's own
 * discipline). No fallback direct-run: a missing helper is a boot bug, and
 * failing closed is the safe reading for a destructive action.
 * @param {string} channelId
 */
function requestArchive(channelId) {
  const ch = channelById(channelId);
  if (!ch || !ch.scanBind) return;
  if (typeof window.__showConfirm !== 'function') return;
  window.__showConfirm(
    t('social.archive.confirmTitle'),
    t('social.archive.confirmMsg', { name: t(ch.nameKey) }),
    () => void archiveChannel(channelId),
    { confirmLabel: t(archiveKey(channelId, 'action')) },
  );
}

/**
 * The archive action: enabled=false through the EXISTING save path. The empty
 * fields object hits the backend's surgical merge — every stored value,
 * credential references included, survives; no delete path exists anywhere on
 * this wire. The card face flips back to not-created (the face discriminator
 * is the enabled flag) and the recreate leg auto-opens the QR.
 * @param {string} channelId
 * @returns {Promise<void>}
 */
async function archiveChannel(channelId) {
  const ch = channelById(channelId);
  if (!ch || !ch.scanBind) return;
  setSaveState(channelId, t(archiveKey(channelId, 'doing')));
  setBusy(true);
  try {
    const resp = await api(`/api/social/channels/${encodeURIComponent(channelId)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: false, fields: {} }),
    });
    if (!resp.ok) {
      setSaveState(channelId, t('social.action.saveFailed', { code: resp.status }));
      return;
    }
    // Typed-but-unsaved drafts die with the archive (zero surprise: the face
    // is not-created now, nothing the user typed should resurface later).
    for (const f of ch.fields) delete draftValues[`${channelId}.${f.key}`];
    configByChannel = await fetchConfig();
    try { probeByChannel[channelId] = await fetchProbe(channelId); } catch { /* keep */ }
    // weixin-binditem: unbind returns the card to its DEFAULT item view (the
    // order's default = the row only); the recreate leg below re-begins the
    // scan behind the collapsed row ([[maybeBeginScan]]), so expanding shows
    // the fresh QR.
    itemExpanded.delete(channelId);
    renderAll();
    setSaveState(channelId, t(archiveKey(channelId, 'done')));
    maybeBeginScan(); // recreate: the archived face auto-begins the QR
  } catch {
    setSaveState(channelId, t('social.action.saveFailed', { code: 'io' }));
  } finally {
    setBusy(false);
  }
}

// ── Default session (contract C4) ────────────────────────────────────────
/**
 * PUT the default session ('' clears it → null). Failure restores the last
 * backend truth and says so — the face never lies about what is set.
 *
 * Called from two places: the automatic write-back after boot (the backend
 * still holding a non-Nebula value is converged onto the Nebula session) and
 * the change listener. Since author ruling 2026-09-30 the rendered control is
 * the pinned, disabled single option — there is no picker to roll back, so the
 * `[data-default-session]` lookup below no longer resolves and the failure path
 * only reports itself. The lookup is kept as the seam a real picker would use
 * (it must NOT be re-pointed at the pinned control: rolling a fixed value back
 * to a stale one would render a value the backend never accepted).
 * @param {string} channelId
 * @param {string} sessionId
 * @returns {Promise<void>}
 */
async function saveDefaultSession(channelId, sessionId) {
  const card = cardEl(channelId);
  const select = card ? card.querySelector('[data-default-session]') : null;
  const prev = defaultSessionByChannel[channelId]
    || { available: true, sessionId: null, sessionName: '' };
  try {
    const resp = await api(FEISHU_DEFAULT_SESSION_URL, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: sessionId || null }),
    });
    if (!resp.ok) throw new Error(`PUT default-session ${resp.status}`);
    const named = sessionsCache
      ? /** @type {Array<{id: string, name: string}>} */ (sessionsCache.items)
        .find((s) => s.id === sessionId)
      : undefined;
    defaultSessionByChannel[channelId] = {
      available: true,
      sessionId: sessionId || null,
      sessionName: named ? named.name : '',
    };
    setSaveState(channelId, t('social.feishu.defaultSession.saved'));
  } catch {
    defaultSessionByChannel[channelId] = {
      available: prev.available, sessionId: prev.sessionId, sessionName: prev.sessionName,
    };
    if (select instanceof HTMLSelectElement) select.value = prev.sessionId || '';
    setSaveState(channelId, t('social.feishu.defaultSession.saveFailed', { code: 'io' }));
  }
}

/**
 * Converge the backend on the pin (author ruling 2026-09-30): every scanBind
 * channel whose stored default session is not the Nebula session gets a PUT to
 * the Nebula id through the EXISTING write path. Idempotent by construction —
 * once converged the condition is false and no request is made, so repeated
 * boots do not re-write. Each channel is independent: a backend that has no
 * Nebula session (or no session list at all) is left exactly as it is, and a
 * failure already reports itself in the card (saveDefaultSession).
 */
async function pinDefaultSessions() {
  const neb = nebulaSession();
  if (!neb) return; // no Nebula session ⇒ degrade silently, never guess an id
  for (const ch of SOCIAL_CHANNELS) {
    // The C4 write path is a feishu route — feishu-only (weixin-scanbind).
    if (!ch.scanBind || !CONTRACT_FACE_CHANNELS.includes(ch.id)) continue;
    const d = defaultSessionByChannel[ch.id];
    if (!d || !d.available || d.sessionId === neb.id) continue;
    await saveDefaultSession(ch.id, neb.id);
  }
}

// ── Auto-scan (in-card, social-fix; multi-card since weixin-scanbind) ─────
/**
 * Auto-begin the in-card scan on EVERY visible scanBind card sitting on its
 * not-created face (QR first on entry; the archive→recreate leg reuses this).
 * A created card, a closed panel or a card that already owns a session never
 * triggers one. weixin-binditem: item-face cards begin theirs too — the
 * session writes into the row's hidden body, so the QR is already rendered
 * when the row is expanded (state ① keeps the auto-begin semantics; only the
 * DOM target moved behind the row).
 */
function maybeBeginScan() {
  const ov = overlay();
  if (!ov || !ov.classList.contains('on')) return;
  for (const ch of visibleChannels().filter((c) => !!c.scanBind)) {
    if (faceCreated(ch)) continue;
    void beginInlineScan(ch.id);
  }
}

// ── In-card scan block (feiscanbind 2026-09-27; social-fix 2026-09-28;
//    multi-card since weixin-scanbind 2026-10-03) ──────────────────────────
// The "scan to create" main path renders INSIDE each scanBind card (one view
// layer with the configuration — no sub-dialog, no overlay face, no
// manual-fill fallback). Since weixin-scanbind the session state is PER
// CHANNEL ([[activeScans]]: exactly one session per card); ONE poll timer
// drives every live session and sweeps itself away when the map empties, so
// no card can ever leave a loop behind.
let scanPollTimer = /** @type {number|null} */ (null);
/** channelId → its live session ({scanId, lastQrUrl, consecutiveErrors}). */
const activeScans = new Map();
const SCAN_POLL_MS = 2000;
const SCAN_MAX_CONSECUTIVE_ERRORS = 5;

/** The channel's copy namespace: the segment of `nameKey` before its last dot
 *  (`social.feishu` / `social.weixinIlink`). The channel id is NOT the
 *  namespace for every channel — `weixin-ilink`'s copy lives under the
 *  camelCase `weixinIlink` — so the namespace is derived from the ONE field
 *  that already carries it ([[nameKey]]), never re-derived from the id
 *  (a raw key rendered on the weixin card was exactly that bug). */
function copyBase(channelId) {
  const ch = channelById(channelId);
  const k = (ch && ch.nameKey) || '';
  const i = k.lastIndexOf('.');
  return i > 0 ? k.slice(0, i) : `social.${channelId}`;
}

/** Channel-scoped scan copy: `<namespace>.scan.<name>` — the feishu keys
 *  resolve exactly as before; the weixin card carries its own set (the copy
 *  names the right app on each card). */
function scanKey(channelId, name) {
  return `${copyBase(channelId)}.scan.${name}`;
}

/** Channel-scoped archive copy: `<namespace>.archive.<name>`. */
function archiveKey(channelId, name) {
  return `${copyBase(channelId)}.archive.${name}`;
}

/**
 * One scan node of a card (the scan nodes live INSIDE the card since
 * social-fix — renderAll may rebuild them between polls, so callers re-query
 * instead of holding nodes).
 * @param {string} channelId
 * @param {string} sel
 * @returns {HTMLElement|null}
 */
function scanNode(channelId, sel) {
  const card = cardEl(channelId);
  return card ? /** @type {HTMLElement|null} */ (card.querySelector(sel)) : null;
}

/**
 * On-demand vendor QR loader (console-load-perf 批 2026-10-21)：qrcode.js 已摘出
 * 首开瀑布（index.html 不再预载，58KB 原始字节/首开少一个阻塞脚本位），首次真实
 * 需要渲染二维码时才注入。Promise 缓存 = 只注一次；加载失败 → 调用方走既有
 * renderQrFallback（URL 本身可用，与 vendor 缺席时同一出口）。
 * @returns {Promise<void>}
 */
let qrVendorPromise = null;
function ensureVendorQr() {
  if (typeof qrcode === 'function') return Promise.resolve();
  if (!qrVendorPromise) {
    qrVendorPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = '/vendor/qrcode.js';
      script.onload = () => resolve();
      script.onerror = () => {
        qrVendorPromise = null; // 失败可重试：下次渲染再注入
        reject(new Error('vendor qrcode.js load failed'));
      };
      document.head.appendChild(script);
    });
  }
  return qrVendorPromise;
}

/**
 * Vendor QR render (web/vendor/qrcode.js, MIT): canvas, white ground + the
 * light-theme text token value as ink. The literals are deliberate — a QR
 * must scan identically in both themes, so it does not follow the palette.
 * @param {string} channelId
 * @param {HTMLElement} container
 * @param {string} url
 */
function renderQr(channelId, container, url) {
  if (typeof qrcode !== 'function') {
    // 按需加载位（原版此处直接走 fallback——现在先试一次懒注入，QR 首用晚 ~一个
    // 脚本往返，其余行为不变；加载失败仍走同一 fallback）。
    ensureVendorQr()
      .then(() => {
        if (typeof qrcode === 'function') drawQr(channelId, container, url);
        else renderQrFallback(channelId, container, url);
      })
      .catch(() => renderQrFallback(channelId, container, url));
    return;
  }
  drawQr(channelId, container, url);
}

/** renderQr 的绘制腿（console-load-perf 批拆出，字节等价原实现）。 */
function drawQr(channelId, container, url) {
  const qr = qrcode(0, 'M');
  qr.addData(url);
  qr.make();
  const count = qr.getModuleCount();
  const cell = 5;
  const quiet = 4 * cell; // 4-module quiet zone
  const canvas = document.createElement('canvas');
  canvas.className = 'social-scan-canvas';
  canvas.width = count * cell + quiet * 2;
  canvas.height = count * cell + quiet * 2;
  const ctx = canvas.getContext('2d');
  if (!ctx) { renderQrFallback(channelId, container, url); return; }
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = '#1b1e26';
  for (let r = 0; r < count; r++) {
    for (let c = 0; c < count; c++) {
      if (qr.isDark(r, c)) ctx.fillRect(quiet + c * cell, quiet + r * cell, cell, cell);
    }
  }
  container.replaceChildren(canvas);
}

/** Vendor missing / canvas refused: the URL itself stays the usable path. */
function renderQrFallback(channelId, container, url) {
  const note = document.createElement('div');
  note.className = 'social-scan-fallback';
  note.textContent = t(scanKey(channelId, 'qrFallback'));
  const link = document.createElement('a');
  link.href = url;
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  link.textContent = url;
  container.replaceChildren(note, link);
}

/** @param {string} channelId @param {string} text */
function setScanStatus(channelId, text) {
  const el = scanNode(channelId, '[data-scan-status]');
  if (el) el.textContent = text;
}

/** @param {string} channelId @param {string} code empty hides the line */
function setScanUserCode(channelId, code) {
  const el = scanNode(channelId, '[data-scan-code]');
  if (!el) return;
  el.textContent = t(scanKey(channelId, 'userCode'), { code });
  if (code) el.removeAttribute('hidden'); else el.setAttribute('hidden', '');
}

/**
 * Begin a scan-bind session for ONE card and poll it INTO the card (one view
 * layer — the status/QR nodes are re-queried from the card on every write, so
 * renderAll rebuilding the card between polls never detaches a write). Each
 * scanBind card owns at most one session; terminal states delete the entry
 * and the poll timer sweeps itself when no session is left. `done` re-reads
 * backend truth, which flips the SAME card to its created face.
 * @param {string} channelId
 * @returns {Promise<void>}
 */
async function beginInlineScan(channelId) {
  const ch = channelById(channelId);
  const card = cardEl(channelId);
  if (!ch || !ch.scanBind || !card) return;
  if (faceCreated(ch)) return;          // the created face has no scan block
  if (activeScans.has(channelId)) return; // exactly one session per card
  setScanStatus(channelId, t(scanKey(channelId, 'starting')));
  setScanUserCode(channelId, '');
  const qrBox = scanNode(channelId, '[data-scan-qr]');
  if (qrBox) qrBox.replaceChildren();
  let scanId = '';
  try {
    const resp = await api(`/api/social/channels/${encodeURIComponent(channelId)}/scan-bind/begin`, { method: 'POST' });
    if (!resp.ok) throw new Error(`begin ${resp.status}`);
    const data = await resp.json();
    scanId = typeof data.scanId === 'string' ? data.scanId : '';
  } catch {
    setScanStatus(channelId, t(scanKey(channelId, 'failed'), { reason: 'io' }));
    return;
  }
  if (!scanId || activeScans.has(channelId)) return; // a session started while in flight
  const session = { scanId, lastQrUrl: '', consecutiveErrors: 0 };
  activeScans.set(channelId, session);
  ensureScanPoll();
  tickSession(channelId, session); // first tick, no 2 s wait
}

/** One timer drives EVERY live session (weixin-scanbind: the sessions are per
 *  channel; the timer is process-wide). */
function ensureScanPoll() {
  if (scanPollTimer === null) {
    scanPollTimer = /** @type {any} */ (setInterval(() => {
      for (const [id, session] of [...activeScans]) tickSession(id, session);
    }, SCAN_POLL_MS));
  }
}

/** Drop dead entries; when no session is left the timer sweeps itself away. */
function sweepScanPoll() {
  if (scanPollTimer !== null && activeScans.size === 0) {
    clearInterval(scanPollTimer);
    scanPollTimer = null;
  }
}

/**
 * One poll tick for a single session: reads the status face and drives the
 * in-card block. The session is only live while the panel is open, the card
 * exists and still shows its not-created face; a dead session deletes its own
 * entry and the timer sweeps itself when none is left.
 */
function tickSession(channelId, session) {
  const ch = channelById(channelId);
  if (!ch) { activeScans.delete(channelId); sweepScanPoll(); return; }
  const sessionLive = () => activeScans.get(channelId) === session
    && !!cardEl(channelId)
    && !faceCreated(ch)
    && !!(overlay() && overlay().classList.contains('on'));
  const finish = () => { activeScans.delete(channelId); sweepScanPoll(); };
  void (async () => {
    if (!sessionLive()) { finish(); return; }
    /** @type {Record<string, any>|null} */
    let s = null;
    try {
      const resp = await api(`/api/social/channels/${encodeURIComponent(channelId)}/scan-bind/status?scanId=${encodeURIComponent(session.scanId)}`);
      if (resp.ok) { s = await resp.json(); session.consecutiveErrors = 0; }
    } catch { /* transient — counted below */ }
    if (!sessionLive()) { activeScans.delete(channelId); sweepScanPoll(); return; }
    if (!s) {
      session.consecutiveErrors++;
      if (session.consecutiveErrors >= SCAN_MAX_CONSECUTIVE_ERRORS) {
        activeScans.delete(channelId);
        sweepScanPoll();
        setScanStatus(channelId, t(scanKey(channelId, 'failed'), { reason: 'io' }));
      }
      return;
    }
    const state = typeof s.state === 'string' ? s.state : '';
    if (state === 'qr_ready' || state === 'polling') {
      const url = typeof s.qrUrl === 'string' ? s.qrUrl : '';
      if (url && url !== session.lastQrUrl) {
        const box = scanNode(channelId, '[data-scan-qr]');
        if (box) { session.lastQrUrl = url; renderQr(channelId, box, url); }
      }
      setScanUserCode(channelId, typeof s.userCode === 'string' ? s.userCode : '');
      setScanStatus(channelId, t(scanKey(channelId, state === 'polling' ? 'confirmed' : 'waiting')));
    } else if (state === 'done') {
      activeScans.delete(channelId);
      sweepScanPoll();
      setScanStatus(channelId, t(scanKey(channelId, 'done')));
      // weixin-binditem (state ③): binding done ⇒ the binding face withdraws
      // back to the item row. Dropping the expansion here makes the loadAll()
      // rebuild below land the card on its collapsed row, badge carrying the
      // bound state ([[displayStatus]] reads the re-read backend truth).
      itemExpanded.delete(channelId);
      // The card behind flips by backend truth: re-read config + probe so the
      // pill/credential line/adapterRegistered come back current (plan card §5.7).
      setBusy(true);
      try { await loadAll(); } finally { setBusy(false); }
    } else if (state === 'failed') {
      activeScans.delete(channelId);
      sweepScanPoll();
      setScanStatus(channelId, t(scanKey(channelId, 'failed'), { reason: typeof s.error === 'string' ? s.error : 'io' }));
    }
  })();
}

/** Clear the ONE poll handle (idempotent). */
function stopScanPoll() {
  if (scanPollTimer !== null) { clearInterval(scanPollTimer); scanPollTimer = null; }
}

// ── Open / close / focus ─────────────────────────────────────────────────
export function openSocialPanel() {
  const ov = overlay();
  if (!ov) return;
  lastFocusedBeforeOpen = /** @type {HTMLElement|null} */ (document.activeElement);
  ov.classList.add('on');
  renderAll();
  setBusy(true); // set BEFORE the load so an observer can never read the previous round's value
  const first = modal()?.querySelector('button, input, select');
  if (first && 'focus' in first) /** @type {HTMLElement} */ (first).focus();
  loadAll();
}

export function closeSocialPanel() {
  const ov = overlay();
  if (!ov) return;
  // The in-card scan poll never outlives the panel (the closer owns the exit).
  stopScanPoll();
  activeScans.clear();
  // weixin-binditem: expansion is session-scoped view state — reopening the
  // panel starts from the default (the row only).
  itemExpanded.clear();
  ov.classList.remove('on');
  // Esc/close always returns focus to the entry button (W13).
  const btn = document.getElementById('social-btn');
  if (btn) btn.focus();
  else lastFocusedBeforeOpen?.focus?.();
}

/** The modal has no dimming backdrop (design §B/W3) — clicking the empty
 *  overlay area is the click-outside affordance. */
function onOverlayClick(ev) {
  if (ev.target === overlay()) closeSocialPanel();
}

/** Tab must not escape the open dialog (W13). */
function onKeydown(ev) {
  const ov = overlay();
  if (!ov || !ov.classList.contains('on')) return;
  if (ev.key === 'Escape') {
    ev.preventDefault();
    closeSocialPanel();
    return;
  }
  if (ev.key !== 'Tab') return;
  const m = modal();
  if (!m) return;
  const focusables = /** @type {HTMLElement[]} */ ([
    ...m.querySelectorAll('button, input, select, [href], [tabindex]:not([tabindex="-1"])'),
  ]).filter((el) => !el.hasAttribute('disabled') && el.offsetParent !== null);
  if (!focusables.length) return;
  const first = focusables[0];
  const last = focusables[focusables.length - 1];
  const active = /** @type {HTMLElement|null} */ (document.activeElement);
  if (ev.shiftKey && (active === first || !m.contains(active))) {
    ev.preventDefault();
    last.focus();
  } else if (!ev.shiftKey && active === last) {
    ev.preventDefault();
    first.focus();
  }
}

function onDocumentClick(ev) {
  const target = /** @type {HTMLElement|null} */ (ev.target instanceof Element ? ev.target : null);
  if (!target) return;
  if (target.closest('#social-btn')) { openSocialPanel(); return; }
  if (target.closest('#social-modal-close')) { closeSocialPanel(); return; }
  // weixin-binditem: the item row's toggle. Checked BEFORE the action buttons:
  // the row is the head, so everything inside it (icon, name, pill — the 3cuts
  // order removed the chevron) toggles — and the unbind button, which since the
  // unbind-row order (2026-10-08) lives on the row LINE as the row's SIBLING,
  // can never reach this branch (it is not a descendant of the row), so a
  // destructive action and the accordion toggle can never share one click
  // (state ⑤ — now guaranteed by DOM structure, as before).
  const item = target.closest('[data-item-toggle]');
  if (item instanceof HTMLElement && item.dataset.itemToggle) { toggleItemFace(item.dataset.itemToggle); return; }
  const arch = target.closest('[data-archive]');
  // social-fix-confirm (2026-10-09): the unbind icon opens the shared glass
  // confirm first ([[requestArchive]]); the EXISTING archive flow runs on
  // confirm only.
  if (arch instanceof HTMLElement && arch.dataset.archive) { requestArchive(arch.dataset.archive); return; }
  const save = target.closest('[data-save]');
  if (save instanceof HTMLElement && save.dataset.save) { saveChannel(save.dataset.save); return; }
  const re = target.closest('[data-recheck]');
  if (re instanceof HTMLElement && re.dataset.recheck) { recheck(re.dataset.recheck); }
}

function onInput(ev) {
  const el = /** @type {HTMLInputElement|null} */ (ev.target instanceof HTMLInputElement ? ev.target : null);
  if (!el || !el.dataset.field) return;
  const card = el.closest('.social-card');
  if (!(card instanceof HTMLElement)) return;
  const id = card.dataset.channel || '';
  if (el.dataset.secret === '1') draftValues[`${id}.${el.dataset.field}`] = el.value;
}

/** Select changes. There is no user-choosable select left on this face (the
 *  default session is pinned to Nebula and disabled), and the pinned control
 *  carries `data-default-session-fixed` — deliberately NOT the old
 *  `data-default-session`, which was this listener's hook. Keeping the listener
 *  has no effect on the fixed control, and it stays the seam any future
 *  genuine picker would use. */
function onChange(ev) {
  const el = /** @type {HTMLSelectElement|null} */ (ev.target instanceof HTMLSelectElement ? ev.target : null);
  if (!el || !el.hasAttribute('data-default-session')) return;
  const card = el.closest('.social-card');
  if (!(card instanceof HTMLElement) || !card.dataset.channel) return;
  void saveDefaultSession(card.dataset.channel, el.value);
}

/** Boot wiring. Idempotent; document-level so the entry button survives the
 *  friends gate detaching/re-attaching it (activityBar.js). */
export function initSocialPanel() {
  if (initialized) return;
  initialized = true;
  document.addEventListener('click', onDocumentClick);
  document.addEventListener('keydown', onKeydown);
  document.addEventListener('input', onInput);
  document.addEventListener('change', onChange);
  overlay()?.addEventListener('click', onOverlayClick);
  window.addEventListener('locale-changed', () => { applyStaticText(); renderAll(); });
  applyStaticText();
}
