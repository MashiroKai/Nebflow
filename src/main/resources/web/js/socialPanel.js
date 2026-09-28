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
//     actually holding, live fingerprint verdict), chat→session bindings +
//     default-session selector (contracts C3/C4), archive (contract C5).
//     The enable switch is folded into archive on this card: enabled=false IS
//     the archive, so no separate disabled face exists.
//   · C1/C3/C4 data comes from the backend contract; an older backend answers
//     404 or omits the fields and every reader degrades to a readable state
//     (never an error, never a blank panel). The live-app line has NO fallback
//     to the stored app_id — that fallback is exactly the green-pill gap
//     (registered ≠ registered to THIS app) the diagnosis report pinned.
//
// social-fix batch (author ruling 2026-09-28, three changes over the landed
// panel): ① ONE view layer — the QR sub-dialog is retired, the scan block
// renders inside the card; ② the mechanical probe line never reaches the UI —
// the same probe face renders as plain-language credential states; ③ the
// manual-fill form and its collapsed entry are retired — scan-to-create is
// the ONLY creation path. The probe endpoint, the state machine and the
// sealed-channel data face are all unchanged (render layer only).

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

// ── Contract faces: C1 live app / C3 bindings / C4 default session ──────
// feishu-panel: these read the backend contract pinned by the backend leg's
// delivery (dispatcher relay 2026-09-27 21:16 — that relay wins over the
// task-book prose wherever the two differ):
//   C1 GET /api/social/channels/feishu/connection →
//      {channel, enabled, adapterRegistered, connected, liveAppId|null,
//       liveAppIdFp|null, liveAppName|null, storedAppId|null,
//       storedAppIdFp|null, fingerprintMatch:"match"|"mismatch"|"unknown"}
//   C3 GET /api/social/channels/feishu/bindings →
//      {bindings:[{sessionId, sessionName, chatId, source:"auto"|"manual",
//                  boundAt: millis|null}]}
//   C4 GET .../feishu/default-session → {sessionId|null};
//      PUT {"sessionId":"…"} persists, {"sessionId":null} or "" clears.
//
// Every reader is total: a 404 or a missing field degrades to an explicit
// unavailable/unknown reading — the panel never errors and never blanks.
//
// Two pinned semantics: (a) `fingerprintMatch === "unknown"` is an
// information gap, NOT a warning (only "mismatch" reaches the failed face);
// (b) the live-app line never falls back to the STORED app_id — that
// fallback is exactly the green-pill gap the diagnosis report pinned
// (registered ≠ registered to THIS app).

/** C1/C3/C4 endpoints on the feishu social routes. */
const FEISHU_CONNECTION_URL = '/api/social/channels/feishu/connection';
const FEISHU_BINDINGS_URL = '/api/social/channels/feishu/bindings';
const FEISHU_DEFAULT_SESSION_URL = '/api/social/channels/feishu/default-session';

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

/** Epoch-millis (contract C3 `boundAt`) → a compact local timestamp; '' for
 *  null/absent. Numeric input is deliberate: the contract pins millis, not a
 *  formatted string. @param {unknown} v @returns {string} */
function formatMillis(v) {
  const n = typeof v === 'number' ? v : (typeof v === 'string' && v.trim() && !Number.isNaN(Number(v)) ? Number(v) : null);
  if (n === null || n <= 0) return '';
  const d = new Date(n);
  if (Number.isNaN(d.getTime())) return '';
  const p = (x) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
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
        const sessionId = firstString(b && b.sessionId, b && b.session_id);
        // Contract C3: items carry no chat display name (chat id only), and a
        // missing `source` key (historical rows) reads as "manual".
        const chatId = firstString(b && b.chatId, b && b.chat_id);
        const src = firstString(b && b.source);
        return {
          chatId,
          chatName: chatId,
          sessionId,
          sessionName: firstString(b && b.sessionName, b && b.session_name) || sessionId,
          source: src === 'auto' ? 'auto' : 'manual',
          time: formatMillis(b && b.boundAt),
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
  return base;
}

// ── Rendering ────────────────────────────────────────────────────────────
/**
 * The human credential status line (social-fix, author ruling 2026-09-28):
 * the probe FACE still answers the mechanical triples, but the card never
 * shows internal check names — the reading renders as plain language, two
 * states (+ empty when nothing was probed yet):
 *   · any contradiction in the probed credential ⇒ "missing — scan again"
 *   · otherwise ⇒ "configured · permissions normal".
 * The triple-contradiction path ([[channelProblem]] ⇒ configInvalid) keeps
 * its actionable hint with the file path — this line is the non-invalid
 * reading only.
 * @param {Record<string, {exists?: boolean, modeOk?: boolean, readable?: boolean}>|undefined} probe
 * @returns {string} translated status, or '' when nothing was probed yet
 */
function credentialLine(probe) {
  if (!probe) return '';
  const keys = Object.keys(probe);
  if (!keys.length) return '';
  const p = probe[keys[0]];
  if (!p) return '';
  const broken = p.exists === false || p.modeOk === false || p.readable === false;
  return t(broken ? 'social.credential.missing' : 'social.credential.ok');
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
  return credentialLine(probeByChannel[ch.id]);
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
  const on = switchOnFor(ch);
  return `<div class="social-card" data-channel="${escapeHtml(ch.id)}">
      <div class="social-card-head">
        <i data-lucide="${escapeHtml(ch.icon)}" class="social-card-icon"></i>
        <span class="social-card-name">${escapeHtml(t(ch.nameKey))}</span>
        <span class="social-status-pill plugins-state-pill" data-status="${status}">${escapeHtml(t(`social.status.${status}`))}</span>
        ${toggleHTML({
          on,
          label: t(ch.nameKey),
          disabled: !configReady,
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
 * The scanBind card (feishu-panel two faces; social-fix 2026-09-28): scan and
 * configure share ONE view layer — no sub-dialog, no manual form.
 *   · not-created — the QR lives IN the card with its status line
 *     ([[beginInlineScan]] auto-runs on this face); scan-to-create is the
 *     ONLY creation path.
 *   · created — the live-app block (C1), archive (C5) with its one-line
 *     semantics, and the bindings block (C3/C4).
 * The head keeps icon + name + status pill; the enable switch is folded into
 * archive (enabled=false IS the archive — no separate disabled face exists).
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
        <span class="social-status-pill plugins-state-pill" data-status="${status}">${escapeHtml(t(`social.status.${status}`))}</span>
      </div>`;
  const scanInline = `
      <div class="social-scan-inline" data-scan-inline="${escapeHtml(ch.id)}">
        <div class="social-scan-qr" data-scan-qr></div>
        <div class="social-scan-code" data-scan-code hidden></div>
        <div class="social-scan-status" data-scan-status role="status" aria-live="polite"></div>
      </div>`;
  const face = created
    ? `${liveBlockHTML(ch)}
       <div class="social-card-actions">
         <button type="button" class="glass-control cfg-btn cfg-btn-sm social-btn-danger" data-archive="${escapeHtml(ch.id)}">${escapeHtml(t('social.feishu.archive.action'))}</button>
         <span class="social-save-state" data-save-state="${escapeHtml(ch.id)}" role="status" aria-live="polite"></span>
       </div>
       <div class="social-archive-hint">${escapeHtml(t('social.feishu.archive.hint'))}</div>
       ${bindingsHTML(ch)}`
    : scanInline;
  return `<div class="social-card" data-channel="${escapeHtml(ch.id)}">
      ${head}
      <div class="social-card-desc">${escapeHtml(t(ch.descKey))}</div>
      ${face}
      <div class="social-card-hint"${hint ? '' : ' hidden'}>${escapeHtml(hint)}</div>
    </div>`;
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
  const appText = live.available
    ? (live.appId
      ? (live.appName ? `${live.appName} · ${live.appId}` : live.appId)
      : t('social.feishu.live.appUnknown'))
    : t('social.feishu.live.appUnknown');
  const fp = live.fingerprint;
  const fpKey = fp === 'match' ? 'social.feishu.live.fpMatch'
    : fp === 'mismatch' ? 'social.feishu.live.fpMismatch'
      : 'social.feishu.live.fpUnknown';
  // Bridge enabled state: the C1 face's own reading when it answers at all
  // (null = unknown); the config flag is only the stand-in for an OLD backend
  // that has no connection endpoint — a stored flag is not a live claim.
  const enabledText = live.available
    ? (live.enabled === null
      ? t('social.feishu.live.fpUnknown')
      : t(live.enabled ? 'social.feishu.live.bridgeOn' : 'social.feishu.live.bridgeOff'))
    : t(!!((configByChannel[ch.id] || {}).enabled) ? 'social.feishu.live.bridgeOn' : 'social.feishu.live.bridgeOff');
  return `<div class="social-live" data-live="${escapeHtml(ch.id)}">
      <div class="social-live-row"><span class="social-live-label">${escapeHtml(t('social.feishu.live.title'))}</span><span class="social-live-value">${escapeHtml(appText)}</span></div>
      <div class="social-live-row"><span class="social-live-label">${escapeHtml(t('social.feishu.live.fingerprint'))}</span><span class="social-live-value${fp === 'mismatch' ? ' social-live-warn' : ''}" data-fp="${fp}">${escapeHtml(t(fpKey))}</span></div>
      <div class="social-live-row"><span class="social-live-label">${escapeHtml(t('social.feishu.live.bridge'))}</span><span class="social-live-value" data-live-enabled>${escapeHtml(enabledText)}</span></div>
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
  // feishu-panel: C1 + C3 + C4 faces ride along with the load (cheap GETs;
  // each degrades to its own unavailable reading on an older backend).
  for (const ch of SOCIAL_CHANNELS) {
    if (!ch.scanBind) continue;
    await fetchConnection(ch.id);
    await fetchBindings(ch.id);
    await fetchDefaultSession(ch.id);
  }
  await fetchSessions();
  renderAll();
  setBusy(false);
  maybeBeginScan();
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
    renderAll();
    setSaveState(channelId, t('social.feishu.archive.done'));
    maybeBeginScan(); // recreate: the archived face auto-begins the QR
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

// ── Auto-scan (in-card, social-fix) ──────────────────────────────────────
/**
 * Auto-begin the in-card scan when a visible scanBind card sits on its
 * not-created face (QR first on entry; the archive→recreate leg reuses this).
 * A created card, a closed panel or an already-running session never
 * triggers it.
 */
function maybeBeginScan() {
  const ov = overlay();
  if (!ov || !ov.classList.contains('on')) return;
  const ch = visibleChannels().find((c) => !!c.scanBind);
  if (!ch || faceCreated(ch)) return;
  void beginInlineScan(ch.id);
}

// ── In-card scan block (feiscanbind 2026-09-27; social-fix 2026-09-28) ────
// The "scan to create" main path for the feishu card renders INSIDE the card
// (one view layer with the configuration — no sub-dialog, no overlay face,
// no manual-fill fallback). Exactly ONE poll timer exists ([[scanPollTimer]]);
// it is cleared on every terminal state and on panel close, so the card can
// never leave a loop behind.
let scanPollTimer = /** @type {number|null} */ (null);
let scanActiveScanId = '';
const SCAN_POLL_MS = 2000;
const SCAN_MAX_CONSECUTIVE_ERRORS = 5;

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

/** @param {string} channelId @param {string} text */
function setScanStatus(channelId, text) {
  const el = scanNode(channelId, '[data-scan-status]');
  if (el) el.textContent = text;
}

/** @param {string} channelId @param {string} code empty hides the line */
function setScanUserCode(channelId, code) {
  const el = scanNode(channelId, '[data-scan-code]');
  if (!el) return;
  el.textContent = t('social.feishu.scan.userCode', { code });
  if (code) el.removeAttribute('hidden'); else el.setAttribute('hidden', '');
}

/**
 * Begin a scan-bind session and poll it INTO the card (one view layer — the
 * status/QR nodes are re-queried from the card on every write, so renderAll
 * rebuilding the card between polls never detaches a write). Terminal states
 * clear the session handle; `done` re-reads backend truth, which flips the
 * SAME card to its created face.
 * @param {string} channelId
 * @returns {Promise<void>}
 */
async function beginInlineScan(channelId) {
  const ch = channelById(channelId);
  const card = cardEl(channelId);
  if (!ch || !ch.scanBind || !card) return;
  if (faceCreated(ch)) return;  // the created face has no scan block
  if (scanActiveScanId) return; // exactly one session at a time
  setScanStatus(channelId, t('social.feishu.scan.starting'));
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
    setScanStatus(channelId, t('social.feishu.scan.failed', { reason: 'io' }));
    return;
  }
  if (!scanId || scanActiveScanId) return; // a session started while in flight
  scanActiveScanId = scanId;
  let lastQrUrl = '';
  let consecutiveErrors = 0;
  /** The session is only live while the panel is open, the card exists and
   *  still shows its not-created face. */
  const sessionLive = () => scanActiveScanId === scanId
    && !!cardEl(channelId)
    && !faceCreated(ch)
    && !!(overlay() && overlay().classList.contains('on'));
  /** One poll tick: reads the status face and drives the in-card block. */
  const tick = async () => {
    if (!sessionLive()) { stopScanPoll(); return; }
    /** @type {Record<string, any>|null} */
    let s = null;
    try {
      const resp = await api(`/api/social/channels/${encodeURIComponent(channelId)}/scan-bind/status?scanId=${encodeURIComponent(scanId)}`);
      if (resp.ok) { s = await resp.json(); consecutiveErrors = 0; }
    } catch { /* transient — counted below */ }
    if (!sessionLive()) { stopScanPoll(); return; }
    if (!s) {
      consecutiveErrors++;
      if (consecutiveErrors >= SCAN_MAX_CONSECUTIVE_ERRORS) {
        stopScanPoll();
        scanActiveScanId = '';
        setScanStatus(channelId, t('social.feishu.scan.failed', { reason: 'io' }));
      }
      return;
    }
    const state = typeof s.state === 'string' ? s.state : '';
    if (state === 'qr_ready' || state === 'polling') {
      const url = typeof s.qrUrl === 'string' ? s.qrUrl : '';
      if (url && url !== lastQrUrl) {
        const box = scanNode(channelId, '[data-scan-qr]');
        if (box) { lastQrUrl = url; renderQr(box, url); }
      }
      setScanUserCode(channelId, typeof s.userCode === 'string' ? s.userCode : '');
      setScanStatus(channelId, t(state === 'polling' ? 'social.feishu.scan.confirmed' : 'social.feishu.scan.waiting'));
    } else if (state === 'done') {
      stopScanPoll();
      scanActiveScanId = '';
      setScanStatus(channelId, t('social.feishu.scan.done'));
      // The card behind flips by backend truth: re-read config + probe so the
      // pill/credential line/adapterRegistered come back current (plan card §5.7).
      setBusy(true);
      try { await loadAll(); } finally { setBusy(false); }
    } else if (state === 'failed') {
      stopScanPoll();
      scanActiveScanId = '';
      setScanStatus(channelId, t('social.feishu.scan.failed', { reason: typeof s.error === 'string' ? s.error : 'io' }));
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
  scanActiveScanId = '';
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
  const arch = target.closest('[data-archive]');
  if (arch instanceof HTMLElement && arch.dataset.archive) { void archiveChannel(arch.dataset.archive); return; }
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
