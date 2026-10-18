// voiceMode.js — full-duplex voice conversation mode (voice-mode batch).
//
// Entry = the small button under the input-bar mic orb. Entering:
//   1. body.voice-mode-active collapses the input bar (css/voiceMode.css),
//   2. a body-level overlay fades in around a CENTERED MIRROR of the bar orb
//      (micOrb.registerVoiceModeOrb — the bar orb stays the state source),
//   3. the mic opens in voiceEngine CONVERSATION mode: every settled
//      utterance arrives here via onUtterance and auto-sends through the
//      normal send pipeline (busy → queue, frozen → wake — all reused).
//
// While the agent's <voice> TTS plays (chat.js VoicePlayer), outgoing sends
// are HELD and utterances accumulate. Sustained mic input during playback
// barges in: voiceMode fires 'voicebargein' on `window` and the player
// interrupts itself; held text flows on when playback ends. Exit restores
// the input bar; an utterance captured before exit still sends (the engine
// flushes its tail during teardown, flagged here as `draining`).
//
// No backend dependency beyond the existing transcribe WS + /api/tts: with a
// voice-capable agent the loop speaks aloud; otherwise replies still land in
// the chat behind the overlay.

import { t } from './i18n.js';
import { startDictation, stopDictation, setMicVolumeListener } from './voiceEngine.js';
import { registerVoiceModeOrb, notifyVoiceState } from './micOrb.js';
import { showToast } from './modal.js';
import { speakText, ensureVoiceAudible } from './chat.js';
// voiceseal 批（批 #508）：语音封存 flag（features.voice，默认封存）——
// enterVoiceMode 的最终闸（见函数体首行），任何未来入口路径都被它兜住。
import { voiceEnabled } from './featureFlags.js';

const BARGE_LEVEL = 0.22;    // mic level that counts as real speech during playback
const BARGE_MS = 320;        // sustained speech that triggers the barge-in
const SEND_RETRY_MS = 450;   // single retry when send() hits its debounce lock
const CHUNK_MAX = 1800;      // TTS chunk target — long replies read as a sequence

/** @typedef {(text: string) => boolean} SendTextFn */

let active = false;
let draining = false;        // exit started — the engine's tail flush still sends
let agentSpeaking = false;   // a VoicePlayer clip is on the speakers right now
let heldText = '';           // utterance(s) captured while the agent speaks
let bargeStart = 0;          // timestamp of the first sustained loud sample
let modeSessionId = null;    // session the overlay was opened on — filters replies
/** @type {SendTextFn | null} */
let sendText = null;
/** @type {(() => void) | null} */
let unregisterOrb = null;

const el = {};

function byId(id) { return document.getElementById(id); }

function showTranscript(text) {
  if (!el.transcript) return;
  el.transcript.textContent = text;
  el.transcript.hidden = !text;
}

function showCaption(text) {
  if (!el.caption) return;
  el.caption.textContent = text;
  el.caption.hidden = !text;
}

function sendUtterance(text, /** @type {boolean} */ allowRetry = true) {
  if (!sendText || !text) return;
  const ok = sendText(text);
  if (ok) { showTranscript(''); return; }
  // One deferred retry covers send()'s 300ms isSending debounce; a real
  // failure (ws closed) keeps the text on the transcript line + a toast.
  if (allowRetry) {
    setTimeout(() => { if (active || draining) sendUtterance(text, false); }, SEND_RETRY_MS);
    return;
  }
  showTranscript(text);
  showToast(t('voiceMode.sendFailed'), 'error');
}

// ── Agent playback (TTS) side ─────────────────────────────────────────

function onPlaybackStart(/** @type {Event} */ e) {
  agentSpeaking = true;
  const detail = /** @type {CustomEvent} */ (e).detail;
  showCaption(detail && detail.text ? detail.text : '');
  if (el.bubble) el.bubble.classList.add('speaking');
}

function onPlaybackEnd() {
  agentSpeaking = false;
  bargeStart = 0;
  showCaption('');
  if (el.bubble) el.bubble.classList.remove('speaking');
  if (heldText) {
    const text = heldText;
    heldText = '';
    sendUtterance(text);
  }
}

/** Split a (possibly long) reply into TTS-sized chunks at paragraph /
 *  sentence seams so a full reply reads as one uninterrupted sequence. */
function speakableChunks(/** @type {string} */ raw) {
  const text = (raw || '').replace(/\u00a0/g, ' ').replace(/[ \t]+/g, ' ').trim();
  if (!text) return [];
  const chunks = [];
  let rest = text;
  while (rest.length > CHUNK_MAX) {
    const win = rest.slice(0, CHUNK_MAX);
    let cut = -1;
    for (const seam of [win.lastIndexOf('\n'), win.lastIndexOf('。'), win.lastIndexOf('. '), win.lastIndexOf('，'), win.lastIndexOf(', '), win.lastIndexOf(' ')]) {
      if (seam > CHUNK_MAX * 0.5) { cut = seam + 1; break; }
    }
    if (cut <= 0) cut = CHUNK_MAX;
    chunks.push(rest.slice(0, cut).trim());
    rest = rest.slice(cut).trim();
  }
  if (rest) chunks.push(rest);
  return chunks;
}

/** Full-reply read (voice-mode batch): the finished AI message arrives here
 *  from chat.js's final render and goes into the shared TTS queue whole —
 *  no <voice> gating. Non-matching sessions are ignored. */
function onAiReply(/** @type {Event} */ e) {
  if (!active) return;
  const detail = /** @type {CustomEvent} */ (e).detail || {};
  if (modeSessionId && detail.sessionId && detail.sessionId !== modeSessionId) return;
  for (const chunk of speakableChunks(detail.text)) speakText(chunk);
}

/** Mic level feed while the mode is open: sustained loudness during agent
 *  speech barges in (cancels TTS so the user's utterance flows on). */
function onMicVolume(/** @type {number} */ v) {
  if (!agentSpeaking || v < BARGE_LEVEL) {
    bargeStart = 0;
    return;
  }
  const now = performance.now();
  if (!bargeStart) {
    bargeStart = now;
  } else if (now - bargeStart >= BARGE_MS) {
    bargeStart = 0;
    window.dispatchEvent(new CustomEvent('voicebargein'));
  }
}

// ── Engine callbacks (conversation mode) ──────────────────────────────

function engineCallbacks() {
  return {
    /** Live partial transcript under the orb. */
    onInterim(/** @type {string} */ text) {
      if (!draining) showTranscript(text || '');
    },
    /** A settled utterance — auto-send, or hold while the agent speaks. */
    onUtterance(/** @type {string} */ text) {
      const utter = (text || '').trim();
      if (!utter) return;
      if (agentSpeaking && !draining) {
        heldText = heldText ? heldText + ' ' + utter : utter;
        showTranscript(utter);
        return;
      }
      sendUtterance(utter, !draining);
    },
    /** @param {string} st @param {string} [data] */
    onState(st, data) {
      notifyVoiceState(st);
      if (st === 'error') {
        // Mic problems are fatal to the mode — surface and fall back to the
        // normal input bar (data is already classified + i18n'd upstream).
        showToast(data || t('voiceMode.sendFailed'), 'error');
        exitVoiceMode();
      }
    },
  };
}

// ── Enter / exit ──────────────────────────────────────────────────────

/**
 * Open the full-duplex voice overlay. `deps` comes from input.js (keeps this
 * module acyclic with the send pipeline):
 *   - sendText(text) → boolean: true when the message went out (input.js
 *     detects send()'s ws-closed path by whether the value was cleared).
 *   - ensureDictationIdle(): stops push-to-talk dictation if it is live —
 *     the two modes share one mic/engine session.
 *   - getSessionId(): the session the overlay was opened on — agent replies
 *     for other sessions are not read aloud.
 * @param {{ sendText: SendTextFn, ensureDictationIdle?: () => void,
 *           getSessionId?: () => string|null }} depsIn
 */
export function enterVoiceMode(depsIn) {
  // voiceseal 批（批 #508）：封存终闸 —— flag OFF（默认）时全双工语音模式
  // 不可进入（正常入口 input.js 拖动手势已被 gate；此处兜住一切直呼路径，
  // 引擎会话/TTS 队列/WS transcribe 腿均不启动）。flag ON 完整恢复。
  if (!voiceEnabled()) return;
  if (active) return;
  if (!depsIn || typeof depsIn.sendText !== 'function') return;
  el.overlay = byId('voice-mode-overlay');
  el.orbWrap = byId('vmo-orb-wrap');
  el.bubble = /** @type {HTMLElement|null} */ (document.querySelector('#voice-mode-overlay .vmo-bubble'));
  el.caption = byId('vmo-caption');
  el.transcript = byId('vmo-transcript');
  el.hint = byId('vmo-hint');
  el.exit = byId('vmo-exit');
  if (!el.overlay || !el.orbWrap) return;
  if (typeof depsIn.ensureDictationIdle === 'function') depsIn.ensureDictationIdle();

  sendText = depsIn.sendText;
  modeSessionId = typeof depsIn.getSessionId === 'function' ? depsIn.getSessionId() : null;
  active = true;
  draining = false;
  agentSpeaking = false;
  heldText = '';
  bargeStart = 0;

  document.body.classList.add('voice-mode-active');
  // Compact in-composer form: the overlay lives INSIDE #input-area, so its
  // absolute box is exactly the composer's (original size and position — the
  // bar's own children fade under it; see css/voiceMode.css). Body-level
  // remains the resting parent so nothing renders before the first entry.
  const inputArea = byId('input-area');
  if (inputArea && el.overlay.parentElement !== inputArea) inputArea.appendChild(el.overlay);
  ensureVoiceAudible();
  el.overlay.classList.add('on');
  if (el.hint) el.hint.textContent = t('voiceMode.hint');
  showTranscript('');
  showCaption('');

  unregisterOrb = registerVoiceModeOrb(el.orbWrap);
  window.addEventListener('voiceplayer:start', onPlaybackStart);
  window.addEventListener('voiceplayer:end', onPlaybackEnd);
  window.addEventListener('aireply', onAiReply);
  setMicVolumeListener(onMicVolume);
  if (el.exit) el.exit.addEventListener('click', onExitClick);
  document.addEventListener('keydown', onKeydown);

  startDictation(engineCallbacks(), { conversation: true });
}

function onExitClick(/** @type {MouseEvent} */ e) {
  e.preventDefault();
  exitVoiceMode();
}

function onKeydown(/** @type {KeyboardEvent} */ e) {
  if (e.key === 'Escape') {
    e.preventDefault();
    exitVoiceMode();
  }
}

/** Close the overlay, stop the mic, restore the input bar. Idempotent. */
export function exitVoiceMode() {
  if (!active) return;
  active = false;
  draining = true;

  // An utterance held back by agent speech is real speech — let it go.
  if (heldText) {
    const text = heldText;
    heldText = '';
    sendUtterance(text, false);
  }

  document.body.classList.remove('voice-mode-active');
  if (el.overlay) el.overlay.classList.remove('on');
  window.removeEventListener('voiceplayer:start', onPlaybackStart);
  window.removeEventListener('voiceplayer:end', onPlaybackEnd);
  window.removeEventListener('aireply', onAiReply);
  setMicVolumeListener(null);
  if (el.exit) el.exit.removeEventListener('click', onExitClick);
  document.removeEventListener('keydown', onKeydown);
  if (unregisterOrb) { unregisterOrb(); unregisterOrb = null; }
  if (el.bubble) el.bubble.classList.remove('speaking');
  showTranscript('');
  showCaption('');
  agentSpeaking = false;
  bargeStart = 0;
  modeSessionId = null;

  // Engine teardown flushes any captured tail through onUtterance; the
  // callbacks and deps stay attached so that flush still sends. `draining`
  // resets on the next enter.
  stopDictation();
}

/** Whether the voice overlay is currently open (test/consumer hook). */
export function isVoiceModeActive() {
  return active;
}
