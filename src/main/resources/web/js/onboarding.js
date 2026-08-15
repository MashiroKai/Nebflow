// onboarding.js — First-run onboarding wizard (固定式).
//
// Fixed code, fixed copy, fixed questions — no LLM improvisation. The wizard:
//   step 1 welcome → step 2 provider config (reuses the settings provider
//   modal) → LLM probe hard gate (probeLlm/probeResult) → step 3 done →
//   auto-send the fixed greeting message so Nebula introduces itself and runs
//   the AskUserQuestion survey (the survey card is the only rendering carrier).
//
// Trigger: main.js configData handler calls initOnboarding(msg) once per boot.
// Backend contract (WebSocketRoutes):
//   getConfig → configData { configured: bool, onboarding: 'pending'|'done'|'skipped'|null }
//   { type:'setOnboardingState', state } → onboardingStateSet { state }
//   { type:'probeLlm' } → probeResult { ok, error? }   (15s backend timeout)

import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { injectUserMessage } from './input.js';
import { openProviderWizard } from './sidebar.js';
import { t } from './i18n.js';

// The one fixed greeting — visible in chat history, replayable via /onboarding.
const GREETING_MSG = '/onboarding 你好，我是新用户，刚刚完成初始配置。请向我介绍你自己，然后用 Ask User Question 工具了解我的使用场景、技术背景和偏好。';

const PROBE_TIMEOUT_MS = 20000; // backend times out at 15s; this is the last-resort guard

let started = false;           // trigger once per boot (configData re-fires on config save)
let overlayEl = null;
let pendingProbe = null;       // { resolve, timer } while a probeLlm is in flight

// ── Entry: decide whether onboarding should appear ────────

export function initOnboarding(msg) {
  if (started) return;
  const ob = msg.onboarding ?? null;
  if (ob === 'done' || ob === 'skipped') return;
  started = true;
  if (msg.configured === false) {
    showWizard();
  } else {
    // Returning user (already has providers, no onboarding marker):
    // one-time lightweight greeting offer — never shown again after either choice.
    showReturningPrompt();
  }
}

// ── probeLlm hard gate ────────────────────────────────────
// Nebula must NOT greet before a real LLM round-trip proves the model config
// works. Returns a Promise<bool>.

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

// ── State writes ──────────────────────────────────────────

function setOnboardingState(next) {
  sendWs({ type: 'setOnboardingState', state: next });
}

// ── Wizard ────────────────────────────────────────────────

function showWizard() {
  closeOverlay();
  overlayEl = document.createElement('div');
  overlayEl.className = 'onboarding-overlay';
  overlayEl.innerHTML = `<div class="onboarding-panel" id="onboarding-panel"></div>`;
  document.body.appendChild(overlayEl);
  renderWelcomeStep();
}

function panel() { return overlayEl?.querySelector('#onboarding-panel'); }

function renderWelcomeStep() {
  const p = panel();
  if (!p) return;
  p.innerHTML = `
    <div class="onboarding-title">${t('onboarding.welcomeTitle')}</div>
    <div class="onboarding-sub">${t('onboarding.welcomeSubtitle')}</div>
    <div class="onboarding-actions">
      <button class="cfg-btn cfg-btn-primary" id="ob-start">${t('onboarding.start')}</button>
      <button class="cfg-btn" id="ob-skip">${t('onboarding.skip')}</button>
    </div>`;
  p.querySelector('#ob-start').addEventListener('click', renderProviderStep);
  p.querySelector('#ob-skip').addEventListener('click', () => {
    setOnboardingState('skipped');
    closeOverlay();
  });
}

function renderProviderStep(error = '') {
  const p = panel();
  if (!p) return;
  p.innerHTML = `
    <div class="onboarding-title">${t('onboarding.providerTitle')}</div>
    <div class="onboarding-sub">${t('onboarding.providerHint')}</div>
    ${error ? `<div class="onboarding-error"></div>` : ''}
    <div class="onboarding-actions">
      <button class="cfg-btn cfg-btn-primary" id="ob-add-provider">${t('onboarding.addProvider')}</button>
      <button class="cfg-btn" id="ob-later">${t('onboarding.later')}</button>
    </div>
    <div class="onboarding-status" id="ob-status" style="display:none"></div>`;
  const errEl = p.querySelector('.onboarding-error');
  if (errEl) errEl.textContent = error;

  p.querySelector('#ob-add-provider').addEventListener('click', () => {
    openProviderWizard(() => { void runProbeAndAdvance(); });
  });
  p.querySelector('#ob-later').addEventListener('click', () => {
    // stays 'pending' — wizard re-appears on next launch
    closeOverlay();
  });
}

async function runProbeAndAdvance() {
  const p = panel();
  if (!p) return;
  const statusEl = p.querySelector('#ob-status');
  const btn = p.querySelector('#ob-add-provider');
  if (statusEl) { statusEl.style.display = 'block'; statusEl.textContent = t('onboarding.probing'); }
  if (btn) btn.disabled = true;

  const result = await probeLlm();

  if (result.ok) {
    renderDoneStep();
  } else {
    if (btn) btn.disabled = false;
    if (statusEl) statusEl.style.display = 'none';
    renderProviderStep(t('onboarding.probeFailed', { error: result.error || 'unknown' }));
  }
}

function renderDoneStep() {
  const p = panel();
  if (!p) return;
  p.innerHTML = `
    <div class="onboarding-title">${t('onboarding.doneTitle')}</div>
    <div class="onboarding-sub">${t('onboarding.doneNote')}</div>
    <div class="onboarding-actions">
      <button class="cfg-btn cfg-btn-primary" id="ob-finish">${t('onboarding.finish')}</button>
    </div>`;
  p.querySelector('#ob-finish').addEventListener('click', () => {
    closeOverlay();
    setOnboardingState('done');
    // Fixed greeting — visible in the chat history, sent as a real user
    // message so the whole ask → Nebula → AskUserQuestion chain is stock.
    injectUserMessage(GREETING_MSG);
  });
}

// ── Returning-user lightweight prompt (one-time) ─────────

function showReturningPrompt() {
  closeOverlay();
  overlayEl = document.createElement('div');
  overlayEl.className = 'onboarding-overlay';
  overlayEl.innerHTML = `
    <div class="onboarding-panel" id="onboarding-panel">
      <div class="onboarding-title">${t('onboarding.returnTitle')}</div>
      <div class="onboarding-sub">${t('onboarding.returnHint')}</div>
      <div class="onboarding-status" id="ob-status" style="display:none"></div>
      <div class="onboarding-actions">
        <button class="cfg-btn cfg-btn-primary" id="ob-greet">${t('onboarding.greet')}</button>
        <button class="cfg-btn" id="ob-no">${t('onboarding.noThanks')}</button>
      </div>
    </div>`;
  document.body.appendChild(overlayEl);

  const statusEl = overlayEl.querySelector('#ob-status');
  overlayEl.querySelector('#ob-greet').addEventListener('click', async () => {
    const btn = overlayEl.querySelector('#ob-greet');
    btn.disabled = true;
    if (statusEl) { statusEl.style.display = 'block'; statusEl.textContent = t('onboarding.probing'); }
    const result = await probeLlm(); // hard gate — no greeting without a live model
    if (result.ok) {
      closeOverlay();
      setOnboardingState('done');
      injectUserMessage(GREETING_MSG);
    } else {
      btn.disabled = false;
      if (statusEl) statusEl.textContent = t('onboarding.probeFailed', { error: result.error || 'unknown' });
    }
  });
  overlayEl.querySelector('#ob-no').addEventListener('click', () => {
    setOnboardingState('done'); // asked once, declined — never ask again
    closeOverlay();
  });
}

function closeOverlay() {
  if (overlayEl) { overlayEl.remove(); overlayEl = null; }
}
