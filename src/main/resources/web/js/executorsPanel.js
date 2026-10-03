// executorsPanel.js — the /Agents executor picker, a Canvas tab
// (executor-registry batch, 2026-10-03).
//
// One card per registered executor: logo mark, name, description, detection
// state + resolved path, default switch. Clicking "set default" PUTs
// /api/executors/default; the backend is the single authority (unknown id /
// invalid write are refused there). The adapterReady=false row (hermes, this
// batch) is display-only.

import { openTab, getTabPane, hasTab, setActiveTab, isCanvasOpen, openCanvas } from './canvas.js';
import { t } from './i18n.js';
import { key } from './branding.js';
import { executorIcon } from './executorIcons.js';

function authHeaders() {
  const tok = localStorage.getItem(key('token')) || '';
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

async function fetchExecutors() {
  try {
    const resp = await fetch('/api/executors', { headers: authHeaders() });
    if (!resp.ok) return [];
    const data = await resp.json();
    return data.executors || [];
  } catch (e) { return []; }
}

/** Open (or focus) the Executors Canvas tab and render. */
export function openExecutors() {
  if (!isCanvasOpen()) openCanvas();
  if (hasTab('executors')) {
    setActiveTab('executors');
  } else {
    openTab('executors', t('activity.agents'), { type: 'executors', closable: true });
  }
  renderExecutors();
}

function contentEl() {
  const pane = getTabPane('executors');
  if (!pane) return null;
  let el = pane.querySelector('#executors-content');
  if (!el) {
    el = document.createElement('div');
    el.id = 'executors-content';
    pane.appendChild(el);
  }
  return el;
}

function cardHtml(ex) {
  const icon = executorIcon(ex.id, 20);
  const badge = ex.isDefault
    ? `<span class="executor-card-badge default">${esc(t('executors.defaultBadge'))}</span>`
    : (ex.enabled ? '' : `<span class="executor-card-badge off">${esc(t('executors.disabledBadge'))}</span>`);
  const detect = ex.external
    ? (ex.detected
        ? `<span class="executor-card-detect ok" title="${esc(ex.path || '')}">${esc(t('executors.detected'))}</span>`
        : `<span class="executor-card-detect miss">${esc(t('executors.notDetected'))}</span>`)
    : `<span class="executor-card-detect ok">${esc(t('executors.builtIn'))}</span>`;
  const pending = !ex.adapterReady
    ? `<div class="executor-card-pending">${esc(t('executors.adapterPending'))}</div>`
    : '';
  const pathLine = ex.path && ex.path !== '(built-in)'
    ? `<div class="executor-card-path" title="${esc(ex.path)}">${esc(ex.path)}</div>`
    : '';
  const action = (!ex.isDefault && ex.enabled)
    ? `<button class="executor-card-set" data-id="${esc(ex.id)}">${esc(t('executors.setDefault'))}</button>`
    : '';
  return `<div class="executor-card${ex.isDefault ? ' is-default' : ''}" data-id="${esc(ex.id)}">
    <span class="executor-card-icon">${icon}</span>
    <div class="executor-card-info">
      <div class="executor-card-name">${esc(ex.displayName)}${badge}${detect}</div>
      <div class="executor-card-desc">${esc(ex.description || '')}</div>
      ${pathLine}${pending}
    </div>
    ${action}
  </div>`;
}

export async function renderExecutors() {
  const el = contentEl();
  if (!el) return;
  el.innerHTML = `<div class="executor-loading">${esc(t('agentManager.loading'))}</div>`;
  const executors = await fetchExecutors();
  if (!executors.length) {
    el.innerHTML = `<div class="executor-loading">${esc(t('executors.empty'))}</div>`;
    return;
  }
  el.innerHTML = `
    <div class="executor-hint">${esc(t('executors.hint'))}</div>
    <div class="executor-list">${executors.map(cardHtml).join('')}</div>`;
  el.querySelectorAll('.executor-card-set').forEach(btn => {
    btn.addEventListener('click', async () => {
      const id = btn.dataset.id;
      btn.disabled = true;
      try {
        const resp = await fetch('/api/executors/default', {
          method: 'PUT',
          headers: { ...authHeaders(), 'Content-Type': 'application/json' },
          body: JSON.stringify({ id })
        });
        if (!resp.ok) {
          const body = await resp.json().catch(() => ({}));
          console.warn('set default executor failed', body.error || resp.status);
        }
      } catch (e) { /* network error stays silent; re-render shows truth */ }
      renderExecutors();
    });
  });
}

// Restore a persisted executors tab after refresh (canvas-tab-restore contract,
// same as agentManager.js).
window.addEventListener('canvas-tab-restore', (e) => {
  if (e.detail?.id === 'executors') renderExecutors();
});
