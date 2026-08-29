// onboarding.js — chat-native first-run onboarding (onboarding-redesign-spec v1.0).
//
// The old modal wizard (overlay steps + probe-gated fixed greeting) is gone.
// The guide now lives in the main chat stream:
//   S1  Nebula simulated typewriter greeting (pre-recorded, honest about it,
//       skippable — no real LLM, no WS frames, render-layer only)
//   S2  main card Q1→Q2(→U1) with dependsOn reveals → branch dispatch
//   S3c sub cards C/A/B collect baseUrl/apiKey/model → summary bubble
//       (typewritten, masked key) → [Save & Test / Back]
//   S4  saveNewProvider → flushConfigToServer (updateConfig WS frame, NO
//       llm.model write — #339: backend auto-creates the general preset)
//       → probeLlm hard gate → done / attributed error bubble
//
// Trigger: main.js configData handler calls initOnboarding(msg) once per boot.
// Replay: /onboarding slash command + Settings "Re-run Onboarding" both land
// here (replayOnboarding / setOnboardingState('pending') + reload).
// Backend contract unchanged (WebSocketRoutes):
//   getConfig → configData { configured, onboarding: 'pending'|'done'|'skipped'|null }
//   { type:'setOnboardingState', state } → onboardingStateSet { state }
//   { type:'probeLlm' } → probeResult { ok, error? }   (15s backend timeout)

import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { activeView } from './chatView.js';
import { showOptions } from './chat.js';
import { saveNewProvider } from './sidebar.js';
import { t } from './i18n.js';
import { smartScroll } from './utils.js';

const PROBE_TIMEOUT_MS = 20000; // backend times out at 15s; this is the last-resort guard

let started = false;           // trigger once per boot (configData re-fires on config save)
let busy = false;              // save+probe in-flight lock (E17 double-click guard)
let pendingProbe = null;       // { resolve, timer } while a probeLlm is in flight
let activeSim = null;          // current typewriter controller { finish } — for Esc / replay cleanup

// ── probeLlm hard gate (reused from the old wizard) ──────────
onMessage('probeResult', (msg) => {
  if (!pendingProbe) return;
  clearTimeout(pendingProbe.timer);
  const { resolve } = pendingProbe;
  pendingProbe = null;
  resolve({ ok: !!msg.ok, error: msg.error || '' });
});

function probeLlm() {
  return new Promise((resolve) => {
    pendingProbe = { resolve, timer: setTimeout(() => {
      pendingProbe = null;
      resolve({ ok: false, error: t('onboarding.probeTimeout') });
    }, PROBE_TIMEOUT_MS) };
    sendWs({ type: 'probeLlm' });
  });
}

// ── State writes ─────────────────────────────────────────────
function setOnboardingState(next) {
  sendWs({ type: 'setOnboardingState', state: next });
}

// ── Typewriter engine (§4) ───────────────────────────────────
// Pure render-layer simulation: char-by-char into .ob-para divs with
// punctuation pauses, 400ms segment gaps, blinking cursor, skip (bubble click
// / Esc / skip button) → instant full text; reduced-motion → instant full text.
const TYPING_MS = 36;
const PUNCT_PAUSE_MS = 150;
const DASH_PAUSE_MS = 250;
const SEGMENT_GAP_MS = 400;
const DONE_GAP_MS = 600;      // pause after the greeting before the main card

function reducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** Type segments into the .ob-para children of `body`. Returns { finish } —
 *  finish() instantly completes all remaining text and fires onDone once. */
function typeInto(body, segments, { onDone = null, skipable = true }) {
  const paras = [...body.querySelectorAll('.ob-para')];
  let cancelled = false;
  let seg = 0, pos = 0;
  let timer = null;
  let doneFired = false;

  const cursor = document.createElement('span');
  cursor.className = 'ob-cursor';
  cursor.setAttribute('aria-hidden', 'true');

  function fireDone() {
    if (doneFired) return;
    doneFired = true;
    if (onDone) onDone();
  }
  function clearTimer() { if (timer) { clearTimeout(timer); timer = null; } }
  function fillAll() {
    segments.forEach((s, i) => { if (paras[i] && paras[i].textContent.length < s.length) paras[i].textContent = s; });
  }
  function finish() {
    if (cancelled) return;
    cancelled = true;
    clearTimer();
    fillAll();
    cursor.remove();
    activeSim = null;
    fireDone();
  }
  function pump() {
    if (cancelled) return;
    const text = segments[seg] || '';
    if (pos < text.length) {
      paras[seg].textContent = text.slice(0, pos + 1);
      const ch = text[pos];
      let delay = TYPING_MS;
      if (ch === '—' && text[pos + 1] === '—') delay = DASH_PAUSE_MS;
      else if ('，。？；：！、,.?;:!'.includes(ch)) delay = PUNCT_PAUSE_MS;
      pos++;
      timer = setTimeout(pump, delay);
    } else if (seg + 1 < segments.length) {
      seg++; pos = 0;
      timer = setTimeout(pump, SEGMENT_GAP_MS);
    } else {
      cursor.remove();
      activeSim = null;
      fireDone();
    }
  }

  if (reducedMotion()) {
    fillAll();
    fireDone();
    return { finish: () => {} };
  }
  body.appendChild(cursor);
  activeSim = { finish };
  pump();
  return { finish };
}

/** Build a simulated AI bubble in the chat stream with the typewriter
 *  attached. `segments` = paragraphs of one bubble. Returns the row element. */
function simBubble(segments, { onDone = null, skipable = true, source = 'NEBULA' } = {}) {
  const chat = activeView?.dom?.chat;
  if (!chat) return null;
  const row = document.createElement('div');
  row.className = 'row ai';
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai ob-sim-bubble';
  // Source label — the greeting honestly declares itself pre-recorded (§4.1).
  const src = document.createElement('div');
  src.className = 'ask-user-source';
  src.textContent = source;
  const body = document.createElement('div');
  body.className = 'ob-body';
  segments.forEach(() => {
    const p = document.createElement('div');
    p.className = 'ob-para';
    body.appendChild(p);
  });
  bubble.append(src, body);
  row.appendChild(bubble);
  chat.appendChild(row);
  smartScroll();
  const ctrl = typeInto(body, segments, { onDone, skipable });
  if (skipable && !reducedMotion()) {
    const skip = document.createElement('button');
    skip.type = 'button';
    skip.className = 'ob-skip glass-control';
    skip.textContent = t('onboarding.sim.skip');
    skip.addEventListener('click', ctrl.finish);
    bubble.appendChild(skip);
  }
  return row;
}

// Esc skips the current typewriter (bound once at module scope).
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && activeSim) { activeSim.finish(); }
});

// ── Provider presets (§3.3, v1.0 snapshot) ───────────────────
const PRESETS = {
  zhipu:   { name: 'zhipu',   baseUrl: 'https://open.bigmodel.cn/api/paas/v4/', models: ['glm-4.5', 'glm-4.5-air'] },
  deepseek:{ name: 'deepseek',baseUrl: 'https://api.deepseek.com/v1/',         models: ['deepseek-chat', 'deepseek-reasoner'] },
  kimi:    { name: 'kimi',    baseUrl: 'https://api.moonshot.cn/v1/',          models: ['moonshot-v1-32k', 'moonshot-v1-8k'] },
  compat:  { name: 'custom',  baseUrl: null,                                   models: [] },
  relay:   { name: 'custom',  baseUrl: null,                                   models: [] },
  local:   { name: 'ollama',  baseUrl: 'http://localhost:11434/v1/',           models: ['qwen3:8b', 'llama3.1:8b'] },
};

// ── Question tree (§3.2) ─────────────────────────────────────
function mainCardQuestions() {
  return [
    {
      id: 'Q1', question: t('onboarding.q.start'), allowOther: false,
      options: [
        { label: t('onboarding.q.start.config'), desc: t('onboarding.q.start.configDesc') },
        { label: t('onboarding.q.start.browse'), desc: t('onboarding.q.start.browseDesc') },
        { label: t('onboarding.q.start.skip'), desc: t('onboarding.q.start.skipDesc') },
      ],
    },
    {
      id: 'Q2', question: t('onboarding.q.provider'), allowOther: false,
      dependsOn: { ref: 'Q1', equals: t('onboarding.q.start.config') },
      options: [
        { label: t('onboarding.q.provider.zhipu'), desc: t('onboarding.q.provider.zhipuDesc') },
        { label: t('onboarding.q.provider.deepseek'), desc: t('onboarding.q.provider.deepseekDesc') },
        { label: t('onboarding.q.provider.kimi'), desc: t('onboarding.q.provider.kimiDesc') },
        { label: t('onboarding.q.provider.compat'), desc: t('onboarding.q.provider.compatDesc') },
        { label: t('onboarding.q.provider.relay'), desc: t('onboarding.q.provider.relayDesc') },
        { label: t('onboarding.q.provider.local'), desc: t('onboarding.q.provider.localDesc') },
        { label: t('onboarding.q.provider.unsure'), desc: t('onboarding.q.provider.unsureDesc') },
      ],
    },
    {
      id: 'U1', question: t('onboarding.q.unsureUrl'), allowOther: false,
      dependsOn: { ref: 'Q2', equals: t('onboarding.q.provider.unsure') },
      options: [
        { label: t('onboarding.q.unsureUrl.yes') },
        { label: t('onboarding.q.unsureUrl.no') },
      ],
    },
  ];
}

// ── Card rendering (shared with AskUser channel) ─────────────
function renderCard(questions, onConfirm, doneLabel) {
  const chat = activeView?.dom?.chat;
  if (!chat) return;
  const row = document.createElement('div');
  row.className = 'row ai';
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai';
  const src = document.createElement('div');
  src.className = 'ask-user-source';
  src.textContent = 'NEBULA';
  bubble.appendChild(src);
  row.appendChild(bubble);
  chat.appendChild(row);
  smartScroll();
  showOptions(bubble, questions, onConfirm, doneLabel, () => {}, null);
}

// ── Flow orchestration ───────────────────────────────────────
/* SEALED / 封存待启用 (author ruling 2026-08-29 22:56): onboarding is sealed
   pending complete testing - first run / new users go straight to the main
   UI. The flag defaults OFF; code kept intact for future re-enable:
     localStorage.setItem('nebflow.onboarding.enabled', '1')  // then reload */
const ONBOARDING_ENABLED = () => { try { return localStorage.getItem('nebflow.onboarding.enabled') === '1'; } catch (e) { return false; } };
export function initOnboarding(msg) {
  if (!ONBOARDING_ENABLED()) return; // SEALED
  if (started) return;
  const ob = msg.onboarding ?? null;
  if (ob === 'done' || ob === 'skipped') return;   // S0 — never again
  started = true;
  if (msg.configured === false) {
    showSimGreeting();                             // S1 new user
  } else {
    showReturningPrompt();                         // S7 returning user (no probe gate, D2)
  }
}

/** /onboarding slash command — replay the fixed greeting (same as first run). */
export function replayOnboarding() {
  started = false;
  showSimGreeting();
}

function showSimGreeting() {
  if (activeSim) activeSim.finish();
  simBubble([
    t('onboarding.sim.greet1'),
    t('onboarding.sim.greet2'),
    t('onboarding.sim.greet3'),
  ], { onDone: () => { setTimeout(showMainCard, DONE_GAP_MS); } });
}

function showMainCard() {
  renderCard(mainCardQuestions(), dispatchMain, t('onboarding.btn.continue'));
}

function dispatchMain(answers) {
  const [a1, a2, a3] = answers;
  if (a1 === t('onboarding.q.start.browse')) { farewell(t('onboarding.sim.later')); return; }  // S3a
  if (a1 === t('onboarding.q.start.skip')) { setOnboardingState('skipped'); return; }          // S3b
  if (a2 === t('onboarding.q.provider.unsure')) {
    if (a3 === t('onboarding.q.unsureUrl.yes')) openSubCardA();
    else openSubCardB();
  } else {
    const intent = Object.keys(PRESETS).find(k => t('onboarding.q.provider.' + k) === a2);
    openSubCardC(intent || 'compat');
  }
}

function farewell(text) {
  simBubble([text], { onDone: null });
}

// ── Sub cards ────────────────────────────────────────────────
function openSubCardC(intent) {
  const preset = PRESETS[intent] || PRESETS.compat;
  const questions = [
    { id: 'C1', question: t('onboarding.q.baseurl'), options: preset.baseUrl ? [{ label: preset.baseUrl }] : [], allowOther: true },
    { id: 'C2', question: t('onboarding.q.apikey'), options: intent === 'local' ? [{ label: t('onboarding.q.apikey.none') }] : [], allowOther: true },
    { id: 'C3', question: t('onboarding.q.model'), options: (preset.models || []).map(m => ({ label: m })), allowOther: true },
  ];
  renderCard(questions, (answers) => {
    const data = assembleProviderData(intent, answers);
    showSummary(data, () => openSubCardC(intent));
  }, t('onboarding.btn.summary'));
}

function openSubCardA() {
  const questions = [
    { id: 'A1', question: t('onboarding.q.relayBase'), options: [], allowOther: true },
    { id: 'A2', question: t('onboarding.q.providerName'), options: [], allowOther: true },
    { id: 'A3', question: t('onboarding.q.apikey'), options: [], allowOther: true },
    { id: 'A4', question: t('onboarding.q.model'), options: [], allowOther: true },
  ];
  renderCard(questions, (answers) => {
    const data = assembleFromA(answers);
    showSummary(data, () => openSubCardA());
  }, t('onboarding.btn.summary'));
}

function openSubCardB() {
  renderCard([{
    id: 'B1', question: t('onboarding.q.localEnough'),
    options: [
      { label: t('onboarding.q.localEnough.yes'), desc: t('onboarding.q.localEnough.yesDesc') },
      { label: t('onboarding.q.localEnough.no') },
    ],
  }], (answers) => {
    if (answers[0] === t('onboarding.q.localEnough.yes')) openSubCardC('local');
    else farewell(t('onboarding.sim.tryCloud'));
  }, t('onboarding.btn.continue'));
}

// ── Answers → provider data (§3.4) ───────────────────────────
function maskKey(k) {
  return k ? k.slice(0, 3) + '…•••' : '•••';
}

function assembleProviderData(intent, answers) {
  const preset = PRESETS[intent] || PRESETS.compat;
  let baseUrl = (answers[0] || '').trim();
  let apiKey = (answers[1] || '').trim();
  const model = (answers[2] || '').trim();
  if (intent === 'local' && apiKey === t('onboarding.q.apikey.none')) apiKey = '';
  const errors = [];
  if (!/^https?:\/\//i.test(baseUrl)) errors.push(t('onboarding.summary.errUrl'));
  if (intent !== 'local' && !apiKey) errors.push(t('onboarding.summary.errKey'));
  if (!model) errors.push(t('onboarding.summary.errModel'));
  if (errors.length) return { error: errors.join('；'), _intent: intent };
  if (!baseUrl.endsWith('/')) baseUrl += '/';
  return {
    name: preset.name, baseUrl, apiKey, protocol: 'openai',
    models: [{ id: model }], _intent: intent,
  };
}

function assembleFromA(answers) {
  let baseUrl = (answers[0] || '').trim();
  const name = ((answers[1] || '').trim() || 'custom');
  const apiKey = (answers[2] || '').trim();
  const model = (answers[3] || '').trim();
  const errors = [];
  if (!/^https?:\/\//i.test(baseUrl)) errors.push(t('onboarding.summary.errUrl'));
  if (!apiKey) errors.push(t('onboarding.summary.errKey'));
  if (!model) errors.push(t('onboarding.summary.errModel'));
  if (/\s/.test(name)) errors.push(t('onboarding.summary.errName'));
  if (errors.length) return { error: errors.join('；'), _intent: 'unsure-a' };
  if (!baseUrl.endsWith('/')) baseUrl += '/';
  return { name, baseUrl, apiKey, protocol: 'openai', models: [{ id: model }], _intent: 'unsure-a' };
}

// ── Summary / save / done / error bubbles (§4.3/§5) ──────────
function showSummary(data, onBack) {
  if (data.error) {
    // Field-level attribution — red error line, no save (E7/E8).
    simBubble([data.error], { onDone: () => summaryActions(data, onBack) });
    return;
  }
  const text = t('onboarding.summary.text', {
    name: data.name,
    baseUrl: data.baseUrl,
    key: maskKey(data.apiKey),
    model: data.models[0].id,
  });
  simBubble([text], { onDone: () => summaryActions(data, onBack) });
}

function summaryActions(data, onBack) {
  const actions = document.createElement('div');
  actions.className = 'ob-actions';
  const saveBtn = document.createElement('button');
  saveBtn.type = 'button';
  saveBtn.className = 'ob-primary glass-control';
  saveBtn.textContent = t('onboarding.btn.testAndSave');
  saveBtn.addEventListener('click', () => { void testAndSave(data); });
  const backBtn = document.createElement('button');
  backBtn.type = 'button';
  backBtn.className = 'glass-control';
  backBtn.textContent = t('onboarding.btn.back');
  backBtn.addEventListener('click', onBack);
  actions.append(saveBtn, backBtn);
  // Append to the latest sim bubble
  const row = chatRows().pop();
  if (row) row.querySelector('.bubble')?.appendChild(actions);
}

function chatRows() {
  const chat = activeView?.dom?.chat;
  return chat ? [...chat.querySelectorAll('.row')] : [];
}

async function testAndSave(data) {
  if (busy) return;                       // E17: one save+probe at a time
  busy = true;
  const row = chatRows().pop();
  const bubble = row?.querySelector('.bubble');
  if (bubble) {
    const status = document.createElement('div');
    status.className = 'ob-probing';
    status.textContent = t('onboarding.probing');
    bubble.appendChild(status);
    bubble.querySelectorAll('.ob-actions button').forEach(b => { b.disabled = true; });
  }
  // One write path (§5.1): saveNewProvider → flushConfigToServer →
  // updateConfig WS frame. No llm.model / preset-chain writes (#339 — the
  // backend auto-creates the `general` default preset).
  saveNewProvider(data.name, {
    baseUrl: data.baseUrl,
    apiKey: data.apiKey,
    protocol: data.protocol,
    models: data.models,
  });
  const result = await probeLlm();
  busy = false;
  if (result.ok) {
    doneBubble(data);
    setOnboardingState('done');
  } else {
    errorBubble(result, data);
  }
}

function doneBubble(data) {
  const text = t('onboarding.done.summary', {
    provider: data.name,
    model: data.models[0].id,
    preset: 'general',
  });
  simBubble([text], { onDone: null });
}

function errorBubble(result, data) {
  const raw = result.error || '';
  let hint = t('onboarding.err.other', { error: raw });
  const e = raw.toLowerCase();
  if (/401|403|unauthorized|invalid api key|auth/i.test(e)) hint = t('onboarding.err.auth');
  else if (/404|econnrefused|enotfound|timeout|connect/i.test(e)) hint = t('onboarding.err.endpoint');
  else if (/model not found|model not exist|400/i.test(e)) hint = t('onboarding.err.model');
  if (data.name === 'ollama') hint = t('onboarding.err.ollama'); // E14 local not running
  simBubble([hint], { onDone: () => errorActions(data) });
}

function errorActions(data) {
  const actions = document.createElement('div');
  actions.className = 'ob-actions';
  const backBtn = document.createElement('button');
  backBtn.type = 'button';
  backBtn.className = 'glass-control';
  backBtn.textContent = t('onboarding.btn.back');
  backBtn.addEventListener('click', () => {
    if (data._intent === 'unsure-a') openSubCardA();
    else openSubCardC(data._intent);
  });
  const laterBtn = document.createElement('button');
  laterBtn.type = 'button';
  laterBtn.className = 'glass-control';
  laterBtn.textContent = t('onboarding.later');
  laterBtn.addEventListener('click', () => farewell(t('onboarding.sim.later')));
  actions.append(backBtn, laterBtn);
  const row = chatRows().pop();
  if (row) row.querySelector('.bubble')?.appendChild(actions);
}

// ── Returning user (S7, no probe gate — D2) ──────────────────
function showReturningPrompt() {
  simBubble([t('onboarding.sim.returnGreet')], {
    onDone: showPrefCard,
    skipable: false,
  });
}

function showPrefCard() {
  renderCard([{
    id: 'P1', question: t('onboarding.q.pref'),
    options: [
      { label: t('onboarding.q.pref.code') },
      { label: t('onboarding.q.pref.doc') },
      { label: t('onboarding.q.pref.research') },
      { label: t('onboarding.q.pref.other') },
    ],
  }], () => farewell(t('onboarding.sim.prefDone')), t('onboarding.btn.continue'));
}
