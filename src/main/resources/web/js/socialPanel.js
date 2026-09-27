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
//    that section, not an oversight. (feiscanbind, 2026-09-27: the FEISHU
//    scan-bind QR sub-dialog below is a different face — it is the approved
//    plan-card §5 main path and renders the platform verification URL only.)
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
//   · not-created: the scan QR is the primary entry (auto-opened when the
//     panel opens on a not-created card, and again after an archive — the
//     "recreate" leg); the manual form is a collapsed secondary entry.
//   · created: live-app block (contract C1 — which app the running bridge is
//     actually holding, live fingerprint verdict), chat→session bindings +
//     default-session selector (contracts C3/C4), archive (contract C5).
//     The enable switch is folded into archive on this card: enabled=false IS
//     the archive, so no separate disabled face exists.
//   · C1/C3/C4 data comes from the backend contract; an older backend answers
//     404 or omits the fields and every reader degrades to a readable state
//     (never an error, never a blank panel). The live-app line has NO fallback
//     to the stored app_id — that fallback is exactly the green-pill gap
//     (registered ≠ registered to THIS app) the diagnosis report pinned.

import { t } from './i18n.js';
import { escapeHtml, createIconsIn } from './utils.js';
import { toggleHTML, bindToggle, setToggleState } from './toggle.js';
import { getAuthToken } from './neblink.js';
import {
  SOCIAL_CHANNELS, visibleChannels, channelById, storedKey, channelStatus, channelProblem, fieldValues,
} from './socialChannels.js';

// ── State ────────────────────────────────────────────────────────────────
/** Last config truth read from the backend, keyed by channel id.
 *  @type {Record<string, {enabled?: boolean, fields?: Record<string, string>}>} */
let configByChannel = {};
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
/** Live-connection face per channel id (contract C1): what the probe face
 *  reports about the RUNNING bridge — which app it actually holds and whether
 *  the stored credential fingerprint matches it. Absent fields (older backend)
 *  degrade to ''/'unknown' readings in [[normalizeLive]].
 *  @type {Record<string, {appId: string, appName: string, fingerprint: 'match'|'mismatch'|'unknown'}>} */
let liveByChannel = {};
/** Chat→session bindings per channel id (contract C3). `available: false` =
 *  the endpoint does not exist on this backend — a readable unavailable state,
 *  never an error surface.
 *  @type {Record<string, {available: boolean, items: Array<{chatId: string, chatName: string, sessionId: string, sessionName: string, source: string, time: string}>}>} */
let bindingsByChannel = {};
/** Default-session face per channel id (contract C4): `sessionId: null` =
 *  unset; `available: false` = endpoint absent on this backend.
 *  @type {Record<string, {available: boolean, sessionId: string|null, sessionName: string}>} */
let defaultSessionByChannel = {};
/** Session list for the default-session selector (existing GET /api/sessions).
 *  @type {{available: boolean, items: Array<{id: string, name: string}>}|null} */
let sessionsCache = null;
/** Manual-fill expansion per channel id: the form is the SECONDARY entry and
 *  stays collapsed until the user asks for it.
 *  @type {Record<string, boolean>} */
let manualExpanded = {};
/** The user closed the scan overlay by hand during this panel session — the
 *  auto-open (not-created face ⇒ QR first) must not fight that choice. Reset
 *  on every panel open. */
let scanManualChosen = false;

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
 *  records the live `adapterRegistered` flag (phase 2) next to the triples and
 *  the normalized live-connection face (contract C1). */
async function fetchProbe(id) {
  const resp = await api(`/api/social/probe?channel=${encodeURIComponent(id)}`);
  if (!resp.ok) throw new Error(`GET /api/social/probe ${resp.status}`);
  const data = await resp.json();
  probeByChannel[id] = data && data.secrets ? data.secrets : {};
  registeredByChannel[id] = !!(data && data.adapterRegistered === true);
  liveByChannel[id] = normalizeLive(data);
  return probeByChannel[id];
}

// ── Contract faces: C1 live app / C3 bindings / C4 default session ──────
// feishu-panel: these read the backend contract that lands with the backend
// leg. Every reader is total: a 404 or a missing field degrades to an explicit
// unavailable/unknown reading — the panel never errors and never blanks.
//
// The one hard rule: the live-app line never falls back to the STORED app_id.
// "Which app is the bridge holding" must come from the live face only.

/** C3/C4 endpoints on the feishu social routes. */
const FEISHU_BINDINGS_URL = '/api/social/channels/feishu/bindings';
const FEISHU_DEFAULT_SESSION_URL = '/api/social/channels/feishu/default-session';

/**
 * First non-empty string among the candidates (contract-field tolerance: the
 * backend leg pins the final wire names; until then every plausible location
 * is read and absence degrades — never throws).
 * @param {...unknown} cands
 * @returns {string}
 */
function firstString(...cands) {
  for (const c of cands) { if (typeof c === 'string' && c.trim()) return c.trim(); }
  return '';
}

/**
 * Normalize the probe response's live-connection face (C1). Field names are
 * read at every shape the backend contract may land on (nested under `live`
 * or flat); anything absent → ''/'unknown'.
 * @param {any} data probe response JSON
 * @returns {{appId: string, appName: string, fingerprint: 'match'|'mismatch'|'unknown'}}
 */
function normalizeLive(data) {
  const live = data && typeof data.live === 'object' && data.live ? data.live : {};
  const fp = live.fingerprint ?? data.fingerprint;
  return {
    appId: firstString(live.appId, live.app_id, data.liveAppId, data.live_app_id),
    appName: firstString(live.appName, live.app_name, live.name, data.appName, data.app_name),
    fingerprint: fp === 'match' || fp === 'mismatch' ? fp : 'unknown',
  };
}

/**
 * C3: chat→session bindings for one channel. Non-OK/absent ⇒ available:false.
 * Item field names read at every plausible location; the display name falls
 * back to the raw id so a binding line is never blank.
 * @param {string} channelId
 * @returns {Promise<void>}
 */
async function fetchBindings(channelId) {
  try {
    const resp = await api(FEISHU_BINDINGS_URL);
    if (!resp.ok) throw new Error(`GET bindings ${resp.status}`);
    const data = await resp.json();
    const raw = Array.isArray(data && data.bindings) ? data.bindings
      : Array.isArray(data && data.items) ? data.items : [];
    bindingsByChannel[channelId] = {
      available: true,
      items: raw.map((b) => {
        const chatId = firstString(b && b.chatId, b && b.chat_id);
        const sessionId = firstString(b && b.sessionId, b && b.session_id);
        return {
          chatId,
          chatName: firstString(b && b.chatName, b && b.chat_name) || chatId,
          sessionId,
          sessionName: firstString(b && b.sessionName, b && b.session_name) || sessionId,
          source: firstString(b && b.source) || 'manual',
          time: firstString(b && b.time, b && b.boundAt, b && b.bound_at, b && b.createdAt),
        };
      }),
    };
  } catch {
    bindingsByChannel[channelId] = { available: false, items: [] };
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
      })).filter((s) => s.id),
    };
  } catch {
    sessionsCache = { available: false, items: [] };
  }
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

/** Yes/no reading of one probe bit, translated (avoids a bare `true` in the UI). */
function bit(v) { return v === true ? t('social.value.yes') : t('social.value.no'); }

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
 * Display status = the mechanical state machine ([[channelStatus]]) plus ONE
 * composed reading (contract C2): a fingerprint mismatch means the live
 * bridge is holding a DIFFERENT app than the stored credential — displayed as
 * failed even though the adapter is registered. channelStatus itself is
 * untouched; this composition lives with its only consumer.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function displayStatus(ch) {
  const base = channelStatus(ch, configByChannel[ch.id], probeByChannel[ch.id], registeredByChannel[ch.id]);
  const live = liveByChannel[ch.id];
  if (base === 'connected' && live && live.fingerprint === 'mismatch') return 'failed';
  return base;
}

// ── Rendering ────────────────────────────────────────────────────────────
/**
 * The mechanical probe line: exists / modeOk / readable (arch §5, unchanged by
 * the "fill and save directly" reshape — only its trigger moved to "after save").
 * @param {Record<string, {exists?: boolean, modeOk?: boolean, readable?: boolean}>|undefined} probe
 * @returns {string} translated summary, or '' when nothing was probed yet
 */
function probeSummary(probe) {
  if (!probe) return '';
  const keys = Object.keys(probe);
  if (!keys.length) return '';
  const p = probe[keys[0]];
  if (!p) return '';
  // 🔴 literal field names are kept for machine readability of the reading itself
  return t('social.probe.summary', {
    exists: bit(p.exists), modeOk: bit(p.modeOk), readable: bit(p.readable),
  });
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
  return probeSummary(probeByChannel[ch.id]);
}

/**
 * One card. The scanBind channels (feishu today) render the two-face card;
 * every other channel keeps the plain card shape unchanged.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function cardHTML(ch) {
  return ch.scanBind ? scanBindCardHTML(ch) : plainCardHTML(ch);
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
  const on = !!(cfg && cfg.enabled);
  return `<div class="social-card" data-channel="${escapeHtml(ch.id)}">
      <div class="social-card-head">
        <i data-lucide="${escapeHtml(ch.icon)}" class="social-card-icon"></i>
        <span class="social-card-name">${escapeHtml(t(ch.nameKey))}</span>
        <span class="social-status-pill plugins-state-pill" data-status="${status}">${escapeHtml(t(`social.status.${status}`))}</span>
        ${toggleHTML({
          on,
          label: t(ch.nameKey),
          attrs: `data-toggle-channel="${escapeHtml(ch.id)}"`,
        })}
      </div>
      <div class="social-card-desc">${escapeHtml(t(ch.descKey))}</div>
      <div class="social-card-fields">${fields}</div>
      <div class="social-card-hint"${hint ? '' : ' hidden'}>${escapeHtml(hint)}</div>
      <div class="social-card-actions">
        <button type="button" class="glass-control cfg-btn cfg-btn-sm social-btn-primary" data-save="${escapeHtml(ch.id)}">${escapeHtml(t('social.action.save'))}</button>
        <button type="button" class="glass-control cfg-btn cfg-btn-sm" data-recheck="${escapeHtml(ch.id)}">${escapeHtml(t('social.action.recheck'))}</button>
        <span class="social-save-state" data-save-state="${escapeHtml(ch.id)}" role="status" aria-live="polite"></span>
      </div>
    </div>`;
}

/**
 * The scanBind card (feishu-panel): two faces over one definition entry.
 *   · not-created — the scan action is the primary path, the manual form sits
 *     collapsed behind "改为手填"; the QR sub-dialog auto-opens when the panel
 *     opens on this face ([[maybeAutoScan]]) and again after an archive.
 *   · created — the live-app block (C1), archive (C5) with its one-line
 *     semantics, the bindings block (C3/C4), then the same collapsed manual
 *     form for editing credentials.
 * The head keeps icon + name + status pill; the enable switch is folded into
 * archive (enabled=false IS the archive — no separate disabled face exists).
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function scanBindCardHTML(ch) {
  const created = faceCreated(ch);
  const status = displayStatus(ch);
  const hint = cardHint(ch, status);
  const fields = ch.fields.map((f) => fieldHTML(ch, f)).join('');
  const manualOpen = !!manualExpanded[ch.id];
  const head = `<div class="social-card-head">
        <i data-lucide="${escapeHtml(ch.icon)}" class="social-card-icon"></i>
        <span class="social-card-name">${escapeHtml(t(ch.nameKey))}</span>
        <span class="social-status-pill plugins-state-pill" data-status="${status}">${escapeHtml(t(`social.status.${status}`))}</span>
      </div>`;
  const manualBlock = `
      <button type="button" class="social-scan-manual" data-manual-toggle="${escapeHtml(ch.id)}" aria-expanded="${manualOpen ? 'true' : 'false'}" aria-controls="social-manual-${escapeHtml(ch.id)}">${escapeHtml(t(manualOpen ? 'social.feishu.manualHide' : 'social.feishu.manualToggle'))}</button>
      <div class="social-card-manual" id="social-manual-${escapeHtml(ch.id)}" data-manual="${escapeHtml(ch.id)}"${manualOpen ? '' : ' hidden'}>
        <div class="social-card-fields">${fields}</div>
        <div class="social-card-actions">
          <button type="button" class="glass-control cfg-btn cfg-btn-sm social-btn-primary" data-save="${escapeHtml(ch.id)}">${escapeHtml(t('social.action.save'))}</button>
          <button type="button" class="glass-control cfg-btn cfg-btn-sm" data-recheck="${escapeHtml(ch.id)}">${escapeHtml(t('social.action.recheck'))}</button>
        </div>
      </div>`;
  const face = created
    ? `${liveBlockHTML(ch)}
       <div class="social-card-actions">
         <button type="button" class="glass-control cfg-btn cfg-btn-sm social-btn-danger" data-archive="${escapeHtml(ch.id)}">${escapeHtml(t('social.feishu.archive.action'))}</button>
         <span class="social-save-state" data-save-state="${escapeHtml(ch.id)}" role="status" aria-live="polite"></span>
       </div>
       <div class="social-archive-hint">${escapeHtml(t('social.feishu.archive.hint'))}</div>
       ${bindingsHTML(ch)}
       ${manualBlock}`
    : `<div class="social-card-actions">
         <button type="button" class="glass-control cfg-btn cfg-btn-sm social-btn-primary" data-scanbind="${escapeHtml(ch.id)}">${escapeHtml(t(`social.${ch.id}.scan.action`))}</button>
         <span class="social-save-state" data-save-state="${escapeHtml(ch.id)}" role="status" aria-live="polite"></span>
       </div>
       ${manualBlock}`;
  return `<div class="social-card" data-channel="${escapeHtml(ch.id)}">
      ${head}
      <div class="social-card-desc">${escapeHtml(t(ch.descKey))}</div>
      ${face}
      <div class="social-card-hint"${hint ? '' : ' hidden'}>${escapeHtml(hint)}</div>
    </div>`;
}

/**
 * The created face's "which app is the bridge actually holding" block (C1).
 * Values come from the LIVE probe face only — no fallback to the stored
 * app_id (that fallback is exactly the green-pill gap the diagnosis report
 * pinned: registered ≠ registered to THIS app). A backend without the live
 * fields reads "unknown", never wrong.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function liveBlockHTML(ch) {
  const live = liveByChannel[ch.id] || { appId: '', appName: '', fingerprint: 'unknown' };
  const cfg = configByChannel[ch.id];
  const enabled = !!(cfg && cfg.enabled);
  const appText = live.appId
    ? (live.appName ? `${live.appName} · ${live.appId}` : live.appId)
    : t('social.feishu.live.appUnknown');
  const fp = live.fingerprint;
  const fpKey = fp === 'match' ? 'social.feishu.live.fpMatch'
    : fp === 'mismatch' ? 'social.feishu.live.fpMismatch'
      : 'social.feishu.live.fpUnknown';
  return `<div class="social-live" data-live="${escapeHtml(ch.id)}">
      <div class="social-live-row"><span class="social-live-label">${escapeHtml(t('social.feishu.live.title'))}</span><span class="social-live-value">${escapeHtml(appText)}</span></div>
      <div class="social-live-row"><span class="social-live-label">${escapeHtml(t('social.feishu.live.fingerprint'))}</span><span class="social-live-value${fp === 'mismatch' ? ' social-live-warn' : ''}" data-fp="${fp}">${escapeHtml(t(fpKey))}</span></div>
      <div class="social-live-row"><span class="social-live-label">${escapeHtml(t('social.feishu.live.bridge'))}</span><span class="social-live-value">${escapeHtml(t(enabled ? 'social.feishu.live.bridgeOn' : 'social.feishu.live.bridgeOff'))}</span></div>
    </div>`;
}

/**
 * Chat→session bindings + the default-session selector (C3/C4). Everything
 * degrades: an endpoint absent on this backend renders an explicit
 * "unavailable" line; a present-but-empty list renders the readable empty
 * state. The selector only renders when both the C4 face and the session
 * list are available; the persisted-but-unknown session id still shows as
 * its own option so the selector never lies about what is set.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function bindingsHTML(ch) {
  const b = bindingsByChannel[ch.id] || { available: false, items: [] };
  /** @type {string[]} */
  const lines = [];
  for (const item of b.items) {
    const src = t(item.source === 'auto' ? 'social.feishu.bindings.source.auto' : 'social.feishu.bindings.source.manual');
    /** @type {string[]} */
    const parts = [`${item.chatName} → ${item.sessionName}`, src];
    if (item.time) parts.push(item.time);
    lines.push(`<div class="social-binding-item">${escapeHtml(parts.join(' · '))}</div>`);
  }
  const listBody = !b.available
    ? `<div class="social-binding-empty">${escapeHtml(t('social.feishu.bindings.unavailable'))}</div>`
    : (lines.length
      ? lines.join('')
      : `<div class="social-binding-empty">${escapeHtml(t('social.feishu.bindings.empty'))}</div>`);
  const d = defaultSessionByChannel[ch.id] || { available: false, sessionId: null, sessionName: '' };
  let selector;
  if (d.available && sessionsCache && sessionsCache.available) {
    const known = sessionsCache.items.some((s) => s.id === d.sessionId);
    const current = d.sessionId
      ? [`<option value="${escapeHtml(d.sessionId)}" selected>${escapeHtml(d.sessionName || d.sessionId)}</option>`]
      : [];
    const opts = (known ? [] : current)
      .concat([`<option value=""${d.sessionId ? '' : ' selected'}>${escapeHtml(t('social.feishu.defaultSession.none'))}</option>`])
      .concat(sessionsCache.items.map((s) => `<option value="${escapeHtml(s.id)}"${s.id === d.sessionId ? ' selected' : ''}>${escapeHtml(s.name || s.id)}</option>`))
      .join('');
    selector = `<label class="social-field social-default-session" for="social-feishu-default-session">
        <span class="social-field-label">${escapeHtml(t('social.feishu.defaultSession.label'))}</span>
        <select id="social-feishu-default-session" class="social-input" data-default-session="${escapeHtml(ch.id)}">${opts}</select>
      </label>`;
  } else {
    selector = `<div class="social-binding-empty">${escapeHtml(t('social.feishu.defaultSession.unavailable'))}</div>`;
  }
  return `<div class="social-bindings" data-bindings="${escapeHtml(ch.id)}">
      <div class="social-bindings-title">${escapeHtml(t('social.feishu.bindings.title'))}</div>
      ${listBody}
      ${selector}
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
    pill.textContent = t(`social.status.${status}`);
  }
  const liveEl = card.querySelector('[data-live]');
  if (liveEl instanceof HTMLElement) liveEl.outerHTML = liveBlockHTML(ch);
  const bindEl = card.querySelector('[data-bindings]');
  if (bindEl instanceof HTMLElement) bindEl.outerHTML = bindingsHTML(ch);
  const hintEl = card.querySelector('.social-card-hint');
  const hint = cardHint(ch, status);
  if (hintEl) {
    hintEl.textContent = hint;
    if (hint) hintEl.removeAttribute('hidden'); else hintEl.setAttribute('hidden', '');
  }
  const toggle = card.querySelector('.nb-toggle');
  setToggleState(toggle, !!(configByChannel[ch.id] && configByChannel[ch.id].enabled));
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
    ['social-channels-title', 'social.channels.title'],
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
  } catch {
    configByChannel = {};
  }
  for (const ch of SOCIAL_CHANNELS) {
    try {
      probeByChannel[ch.id] = await fetchProbe(ch.id);
    } catch {
      probeByChannel[ch.id] = {};
    }
  }
  // feishu-panel: bindings + default session ride along with the load (cheap
  // GETs; each degrades to its own unavailable reading on an older backend).
  for (const ch of SOCIAL_CHANNELS) {
    if (!ch.scanBind) continue;
    await fetchBindings(ch.id);
    await fetchDefaultSession(ch.id);
  }
  await fetchSessions();
  renderAll();
  setBusy(false);
  maybeAutoScan();
}

/**
 * @param {HTMLButtonElement} el
 * @param {boolean} on
 */
async function onToggleChange(el, on) {
  const id = el.dataset.toggleChannel || '';
  const ch = channelById(id);
  if (!ch) return;
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
  setSaveState(channelId, t('social.feishu.archive.doing'));
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
    manualExpanded[channelId] = false;
    renderAll();
    setSaveState(channelId, t('social.feishu.archive.done'));
    maybeAutoScan(); // recreate: the archived face auto-begins the QR
  } catch {
    setSaveState(channelId, t('social.action.saveFailed', { code: 'io' }));
  } finally {
    setBusy(false);
  }
}

// ── Default session (contract C4) ────────────────────────────────────────
/**
 * PUT the default session ('' clears it → null). Failure rolls the select
 * back to the last backend truth and says so — the selector never lies.
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

// ── Manual-fill collapse + auto-scan ─────────────────────────────────────
/**
 * Expand/collapse the manual-fill form (the secondary entry, contract §1.3).
 * @param {string} channelId
 */
function toggleManual(channelId) {
  manualExpanded[channelId] = !manualExpanded[channelId];
  const card = cardEl(channelId);
  if (!card) return;
  const open = !!manualExpanded[channelId];
  const wrap = card.querySelector('[data-manual]');
  const btn = card.querySelector('[data-manual-toggle]');
  if (wrap instanceof HTMLElement) wrap.hidden = !open;
  if (btn instanceof HTMLElement) {
    btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    btn.textContent = t(open ? 'social.feishu.manualHide' : 'social.feishu.manualToggle');
  }
}

/**
 * Auto-open the QR sub-dialog when a visible scanBind card sits on its
 * not-created face (QR first on entry; the archive→recreate leg reuses this).
 * Never fights the user: a hand-closed overlay this panel session keeps the
 * auto-open quiet, and a created card or a closed panel never triggers it.
 */
function maybeAutoScan() {
  if (scanManualChosen) return;
  const ov = overlay();
  if (!ov || !ov.classList.contains('on')) return;
  const current = scanOverlay();
  if (current && !current.hidden) return;
  const ch = visibleChannels().find((c) => !!c.scanBind);
  if (!ch || faceCreated(ch)) return;
  void openScanOverlay(ch.id);
}

// ── Scan-bind overlay (feiscanbind batch, 2026-09-27) ────────────────────
// The "scan to create" main path for the feishu card: a glass sub-dialog
// (same family as the panel shell — no dimming backdrop) with the QR code,
// the phone confirmation code, one status line and the manual-fill fallback.
// Exactly ONE poll timer exists ([[scanPollTimer]]); it is cleared on every
// terminal state and on close, so the overlay can never leave a loop behind.
let scanOverlayEl = /** @type {HTMLElement|null} */ (null);
let scanPollTimer = /** @type {number|null} */ (null);
let scanActiveScanId = '';
/** The channel the overlay was opened for. The overlay is a child of the
 *  modal, NOT of the card, so the manual-fill handover (a button inside the
 *  overlay) cannot find its card by DOM ancestry — it reads this instead. */
let scanChannelId = '';
const SCAN_POLL_MS = 2000;
const SCAN_MAX_CONSECUTIVE_ERRORS = 5;

/** @returns {HTMLElement|null} */
function scanOverlay() { return document.getElementById('social-scan'); }

/**
 * Build the overlay DOM once (a child of #social-modal, so the panel's focus
 * trap keeps covering it; renderAll only rewrites the card list, never this).
 * @returns {HTMLElement}
 */
function buildScanOverlay() {
  const host = modal();
  if (!host) throw new Error('social modal missing');
  const el = document.createElement('div');
  el.id = 'social-scan';
  el.className = 'social-scan';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  el.setAttribute('aria-labelledby', 'social-scan-title');
  el.hidden = true;
  el.innerHTML = `
      <div class="social-scan-head">
        <span id="social-scan-title" class="social-scan-title">${escapeHtml(t('social.feishu.scan.title'))}</span>
        <button type="button" id="social-scan-close" class="panel-btn" data-scan-close title="${escapeHtml(t('social.action.close'))}"><i data-lucide="x"></i></button>
      </div>
      <div class="social-scan-body">
        <div class="social-scan-qr" data-scan-qr></div>
        <div class="social-scan-code" data-scan-code hidden></div>
        <div class="social-scan-status" data-scan-status role="status" aria-live="polite"></div>
        <button type="button" class="social-scan-manual" data-scan-manual>${escapeHtml(t('social.feishu.scan.manual'))}</button>
      </div>`;
  host.appendChild(el);
  createIconsIn(el);
  scanOverlayEl = el;
  return el;
}

/**
 * Vendor QR render (web/vendor/qrcode.js, MIT): canvas, white ground + the
 * light-theme text token value as ink. The literals are deliberate — a QR
 * must scan identically in both themes, so it does not follow the palette.
 * @param {HTMLElement} container
 * @param {string} url
 */
function renderQr(container, url) {
  if (typeof qrcode !== 'function') { renderQrFallback(container, url); return; }
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
  if (!ctx) { renderQrFallback(container, url); return; }
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
function renderQrFallback(container, url) {
  const note = document.createElement('div');
  note.className = 'social-scan-fallback';
  note.textContent = t('social.feishu.scan.qrFallback');
  const link = document.createElement('a');
  link.href = url;
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  link.textContent = url;
  container.replaceChildren(note, link);
}

/** @param {string} text */
function setScanStatus(text) {
  const el = scanOverlay()?.querySelector('[data-scan-status]');
  if (el) el.textContent = text;
}

/** @param {string} code empty hides the line */
function setScanUserCode(code) {
  const el = scanOverlay()?.querySelector('[data-scan-code]');
  if (!el) return;
  el.textContent = t('social.feishu.scan.userCode', { code });
  if (code) el.removeAttribute('hidden'); else el.setAttribute('hidden', '');
}

/** Open the overlay, fire `begin` and start polling. */
async function openScanOverlay(channelId = 'feishu') {
  scanChannelId = channelId;
  const el = scanOverlay() || buildScanOverlay();
  el.hidden = false;
  setScanStatus(t('social.feishu.scan.starting'));
  setScanUserCode('');
  const qrBox = el.querySelector('[data-scan-qr]');
  if (qrBox) qrBox.replaceChildren();
  const first = el.querySelector('button');
  if (first instanceof HTMLElement) first.focus();
  let scanId = '';
  try {
    const resp = await api('/api/social/channels/feishu/scan-bind/begin', { method: 'POST' });
    if (!resp.ok) throw new Error(`begin ${resp.status}`);
    const data = await resp.json();
    scanId = typeof data.scanId === 'string' ? data.scanId : '';
  } catch {
    setScanStatus(t('social.feishu.scan.failed', { reason: 'io' }));
    return;
  }
  if (!scanId || el.hidden) return; // closed while the request was in flight
  scanActiveScanId = scanId;
  let lastQrUrl = '';
  let consecutiveErrors = 0;
  /** One poll tick: reads the status face and drives the whole sub-dialog. */
  const tick = async () => {
    if (scanActiveScanId !== scanId || el.hidden) { stopScanPoll(); return; }
    /** @type {Record<string, any>|null} */
    let s = null;
    try {
      const resp = await api(`/api/social/channels/feishu/scan-bind/status?scanId=${encodeURIComponent(scanId)}`);
      if (resp.ok) { s = await resp.json(); consecutiveErrors = 0; }
    } catch { /* transient — counted below */ }
    if (scanActiveScanId !== scanId || el.hidden) { stopScanPoll(); return; }
    if (!s) {
      consecutiveErrors++;
      if (consecutiveErrors >= SCAN_MAX_CONSECUTIVE_ERRORS) {
        stopScanPoll();
        setScanStatus(t('social.feishu.scan.failed', { reason: 'io' }));
      }
      return;
    }
    const state = typeof s.state === 'string' ? s.state : '';
    if (state === 'qr_ready' || state === 'polling') {
      const url = typeof s.qrUrl === 'string' ? s.qrUrl : '';
      if (url && url !== lastQrUrl && qrBox) { lastQrUrl = url; renderQr(/** @type {HTMLElement} */ (qrBox), url); }
      setScanUserCode(typeof s.userCode === 'string' ? s.userCode : '');
      setScanStatus(t(state === 'polling' ? 'social.feishu.scan.confirmed' : 'social.feishu.scan.waiting'));
    } else if (state === 'done') {
      stopScanPoll();
      setScanStatus(t('social.feishu.scan.done'));
      // The card behind flips by backend truth: re-read config + probe so the
      // pill/probe line/adapterRegistered come back current (plan card §5.7).
      setBusy(true);
      try { await loadAll(); } finally { setBusy(false); }
    } else if (state === 'failed') {
      stopScanPoll();
      setScanStatus(t('social.feishu.scan.failed', { reason: typeof s.error === 'string' ? s.error : 'io' }));
    }
  };
  stopScanPoll();
  scanPollTimer = /** @type {any} */ (setInterval(() => { void tick(); }, SCAN_POLL_MS));
  void tick();
}

/** Clear the ONE poll handle (idempotent). */
function stopScanPoll() {
  if (scanPollTimer !== null) { clearInterval(scanPollTimer); scanPollTimer = null; }
}

/** Hide the overlay and tear the poll down (D-2②: the closer owns the exit).
 *  A hand-close also quiets the panel-session auto-open ([[maybeAutoScan]]):
 *  the user just said "not now" and the panel must not reopen the QR behind
 *  their back. */
function closeScanOverlay() {
  scanManualChosen = true;
  scanActiveScanId = '';
  stopScanPoll();
  const el = scanOverlay();
  if (el) el.hidden = true;
  // Focus back to the entry button (the card may have been re-rendered by a
  // `done` refresh — query fresh instead of holding a stale node).
  const btn = /** @type {HTMLElement|null} */ (
    document.querySelector('.social-card[data-channel="feishu"] [data-scanbind]'));
  if (btn) btn.focus();
}

/** Click path for the card button. @param {string} channelId */
function onScanBind(channelId) {
  if (channelId !== 'feishu') return; // single-channel face today (definition-layer flag gates the button)
  void openScanOverlay(channelId);
}

// ── Open / close / focus ─────────────────────────────────────────────────
export function openSocialPanel() {
  const ov = overlay();
  if (!ov) return;
  lastFocusedBeforeOpen = /** @type {HTMLElement|null} */ (document.activeElement);
  scanManualChosen = false; // each panel open gets a fresh auto-scan decision
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
  closeScanOverlay(); // the sub-dialog never outlives the panel (poll torn down)
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
    // Two stages (same contract as the message search dialog): with the scan
    // overlay open, Esc closes ONLY the overlay; the panel closes from the
    // panel level.
    const scan = scanOverlay();
    if (scan && !scan.hidden) { closeScanOverlay(); return; }
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
  const scan = target.closest('[data-scanbind]');
  if (scan instanceof HTMLElement && scan.dataset.scanbind) { onScanBind(scan.dataset.scanbind); return; }
  if (target.closest('[data-scan-close]')) { closeScanOverlay(); return; }
  // "改为手填" (scan overlay fallback): the card form behind the overlay IS
  // the manual path — closing the overlay hands over AND opens the form so
  // the user lands on usable fields, not on another collapsed toggle.
  if (target.closest('[data-scan-manual]')) {
    const id = scanChannelId || 'feishu';
    closeScanOverlay();
    toggleManual(id);
    return;
  }
  const arch = target.closest('[data-archive]');
  if (arch instanceof HTMLElement && arch.dataset.archive) { void archiveChannel(arch.dataset.archive); return; }
  const mt = target.closest('[data-manual-toggle]');
  if (mt instanceof HTMLElement && mt.dataset.manualToggle) { toggleManual(mt.dataset.manualToggle); return; }
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

/** Select changes (the default-session picker; text inputs go through onInput). */
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
