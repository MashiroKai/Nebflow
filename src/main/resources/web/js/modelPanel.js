// modelPanel.js — the /model face: per-role model-chain configuration.
//
// Opened by the /model UI-only slash command (input.js). Data contract is
// GET/PUT /api/agents/:name/model (RestApiRoutes): { mode: 'explicit'|'follow',
// chain: {preferred, fallbacks}|null, effectiveChain, resolvedFrom:
// 'own-chain'|'nebula-chain'|'seed', current, settable }. Only the four
// SchemePolicy.SettableAgents roles (Nebula, project-dispatcher, kernel,
// general) accept writes; every other agent follows Nebula uniformly.
//
// Interaction semantics (interaction card v2 §2–§6):
//   - two modes: unified (edits the Nebula primary chain) / separate (per-role
//     blocks; followers default to the follow state — no copies are
//     materialized until an explicit "configure independently" action forks
//     the role);
//   - every action saves immediately (PUT) + toast — no separate save button;
//   - re-fork control = explicit "re-follow Nebula" with a confirm dialog that
//     previews the chain that will apply;
//   - Nebula has no follow state (self-reference endpoint); an empty Nebula
//     chain degrades to the seed chain.
//
// This module is also the home of the shared model-ref helpers (migrated from
// the retired presets.js): the static provider-grouped model list and the
// read-only chain chips used by agentManager.js / flowViewers.js.

import state from './state.js';
import { key } from './branding.js';
import { t } from './i18n.js';
import { createDepVisibility } from './depVisibility.js';

// ── Shared helpers (model refs + chain display) ────────────

export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function getToken() { return localStorage.getItem(key('token')) || ''; }
function authHeaders() {
  const tok = getToken();
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

export function shortModel(ref) {
  if (!ref) return '';
  const idx = ref.lastIndexOf('/');
  return idx >= 0 ? ref.slice(idx + 1) : ref;
}

/**
 * All model refs for pickers. state.parsedConfig is the live source - it is
 * re-parsed on every configData push, so models added via the provider editor
 * appear immediately. state.allModelRefs (filled once from modelOptions at WS
 * open, never refreshed) is only the fallback for before the first configData.
 * Both sources derive from the same server configRef, so contents are
 * identical modulo freshness.
 */
export function getAllModelRefs() {
  const providers = state.parsedConfig?.llm?.providers;
  if (providers) {
    const refs = [];
    for (const [name, p] of Object.entries(providers)) {
      (p.models || []).forEach(m => refs.push(`${name}/${m.id}`));
    }
    if (refs.length > 0) return refs;
  }
  return state.allModelRefs || [];
}

/** Chain refs from a {preferred, fallbacks} chain object. */
export function chainRefs(chain) {
  return [chain?.preferred, ...(chain?.fallbacks || [])].filter(Boolean);
}

/**
 * Render a model chain as read-only chips: preferred highlighted, fallbacks
 * small, pulsing dot on the currently running model.
 * refs: [preferred, ...fallbacks]; current: running ref.
 */
export function chainChipsHtml(refs, current) {
  const list = (refs || []).filter(Boolean);
  if (list.length === 0) {
    return `<div class="mchain-empty">${esc(t('model.seedNote'))}</div>`;
  }
  const chips = list.map((ref, i) => {
    const playing = current && ref === current;
    return `<span class="mchain-chip${i === 0 ? ' preferred' : ''}${playing ? ' playing' : ''}">${playing ? '<span class="agent-detail-playing-dot"></span>' : ''}${esc(ref)}</span>`;
  }).join('<span class="mchain-arrow">→</span>');
  return `<div class="mchain">${chips}</div>`;
}

/** Chain chips from a GET /agents/:name/model response (effective chain). */
export function resolvedChainChipsHtml(model) {
  return chainChipsHtml(chainRefs(model?.effectiveChain), model?.current || '');
}

/** Source note key for a GET /agents/:name/model response. */
export function modelSourceNoteKey(model) {
  if (model?.resolvedFrom === 'own-chain') return 'model.independent';
  if (model?.resolvedFrom === 'nebula-chain') return 'model.followsNebula';
  return 'model.seedNote';
}

// ── Panel state ────────────────────────────────────────────

const ROLES = ['Nebula', 'project-dispatcher', 'kernel', 'general'];
const FOLLOWER_ROLES = ['project-dispatcher', 'kernel', 'general'];
/** Frontend mirror of SchemePolicy.SettableAgents (the /model write gate). */
export const SETTABLE_ROLES = new Set(ROLES);
const ROLE_KEY = {
  'Nebula': 'model.roleNebula',
  'project-dispatcher': 'model.roleDispatcher',
  'kernel': 'model.roleKernel',
  'general': 'model.roleGeneral',
};

/** Probed model ids per provider (dynamic layer, refreshed on demand). */
const probedModels = new Map();

let panelEl = null; // the overlay element while the panel is open
let panelMode = 'unified'; // 'unified' | 'separate'
let panelVisibility = null; // the banner + followers conditional-display pair
/** role → { data: GET response|null, chain: string[]|null (null = follow), expanded: boolean } */
const roleState = new Map();

function roleLabel(role) { return t(ROLE_KEY[role] || role); }

function fetchModelOf(role) {
  return fetch(`/api/agents/${encodeURIComponent(role)}/model`, { headers: authHeaders() })
    .then(r => (r.ok ? r.json() : null))
    .catch(() => null);
}

async function fetchAllRoles() {
  const results = await Promise.all(ROLES.map(fetchModelOf));
  ROLES.forEach((role, i) => {
    const st = roleState.get(role) || { chain: null, expanded: false };
    st.data = results[i];
    // Re-sync the working copy from disk on every fetch. Local edits are
    // persisted through saveChain() before any refresh runs, so this sync
    // never clobbers an in-panel edit.
    st.chain = st.data && st.data.mode === 'explicit' ? chainRefs(st.data.chain) : null;
    roleState.set(role, st);
  });
}

function nebulaEffectiveRefs() {
  return chainRefs(roleState.get('Nebula')?.data?.effectiveChain);
}

function forkedRoles() {
  return FOLLOWER_ROLES.filter(r => (roleState.get(r)?.chain || null) !== null);
}

// ── Writes (per-action save) ───────────────────────────────

async function putChain(role, chain) {
  const body = chain
    ? { model: { preferred: chain[0] || null, fallbacks: chain.slice(1) } }
    : { model: null };
  try {
    const resp = await fetch(`/api/agents/${encodeURIComponent(role)}/model`, {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({}));
      throw new Error(err.error || `HTTP ${resp.status}`);
    }
    const fresh = await resp.json();
    const st = roleState.get(role);
    if (st) {
      st.data = { ...(st.data || {}), ...fresh, settable: true };
      st.chain = fresh.mode === 'explicit' ? chainRefs(fresh.chain) : null;
    }
    window.__showToast?.(t('model.saved'), 'success');
    // Live follow previews: re-read every role (the followers' effectiveChain
    // moves whenever the Nebula chain changes).
    await refreshPanel();
  } catch (e) {
    window.__showToast?.(String(e.message || e), 'error');
    await refreshPanel(); // re-render from server state (reverts the local copy)
  }
}

const saveChain = (role) => putChain(role, roleState.get(role)?.chain || null);

// ── Dynamic model list (POST /provider/models) ─────────────

/**
 * Probe every configured provider for its live model list and merge the
 * results into the picker's dynamic layer. Failures degrade to the static
 * list + manual input (toast), mirroring the settings-page provider dialog.
 */
async function refreshModelList() {
  const providers = state.parsedConfig?.llm?.providers || {};
  const names = Object.keys(providers).filter(n => providers[n]?.baseUrl);
  if (names.length === 0) { flashManualInput(); return; }
  let ok = 0;
  await Promise.all(names.map(async n => {
    try {
      const resp = await fetch('/api/provider/models', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: n, baseUrl: providers[n].baseUrl }),
      });
      if (!resp.ok) return;
      const data = await resp.json();
      const models = (data.models || []).filter(m => typeof m === 'string' && m);
      if (models.length) { probedModels.set(n, models); ok++; }
    } catch (e) { /* per-provider failure: keep the static list for it */ }
  }));
  if (ok === 0) flashManualInput();
  else renderPanel();
}

/** Refresh failed / nothing probeable — reveal the manual input as the fallback. */
function flashManualInput() {
  window.__showToast?.(t('model.refreshFailed'), 'error');
  panelEl?.querySelectorAll('.mp-manual').forEach(el => { el.style.display = ''; });
}

// ── Inline error helper ────────────────────────────────────

function flashInline(container, msg) {
  const el = container?.querySelector('.mp-inline-err');
  if (!el) return;
  el.textContent = msg;
  el.classList.add('on');
  setTimeout(() => { el.classList.remove('on'); }, 2500);
}

// ── Chain editor (rows + add) ──────────────────────────────

/** Provider badge + model name for a `provider/model...` ref. */
function refPartsHtml(ref) {
  const idx = ref.indexOf('/');
  const prov = idx >= 0 ? ref.slice(0, idx) : ref;
  const model = idx >= 0 ? ref.slice(idx + 1) : '';
  return `<span class="mp-prov">${esc(prov)}</span><span class="mp-model">${esc(model)}</span>`;
}

function chainRowsHtml(refs, currentRef) {
  const headIdx = currentRef ? refs.indexOf(currentRef) : -1;
  return refs.map((ref, i) => {
    const dim = headIdx > -1 && i < headIdx;
    return `<div class="agent-detail-model-row mp-row${dim ? ' mp-row-unavailable' : ''}" draggable="true" data-idx="${i}"${dim ? ` title="${esc(t('model.unavailable', { reason: t('model.healthFiltered') }))}"` : ''}>
      <span class="agent-detail-grip">⠿</span>
      ${refPartsHtml(ref)}
      <span class="mp-pos">${i === 0 ? esc(t('model.chainHead')) : esc(t('model.chainFallback'))}</span>
      <span class="agent-detail-model-remove mp-remove" data-idx="${i}" title="×">×</span>
    </div>`;
  }).join('');
}

function addSelectHtml(refs, chain) {
  const available = refs.filter(r => !chain.includes(r));
  const groups = new Map();
  available.forEach(ref => {
    const idx = ref.indexOf('/');
    const prov = idx >= 0 ? ref.slice(0, idx) : '';
    if (!groups.has(prov)) groups.set(prov, []);
    groups.get(prov).push(ref);
  });
  if (groups.size === 0) return '';
  const optgroups = [...groups.entries()].map(([prov, list]) =>
    `<optgroup label="${esc(prov)}">${list.map(r => `<option value="${esc(r)}">${esc(shortModel(r))}</option>`).join('')}</optgroup>`
  ).join('');
  return `<select class="mp-add-select"><option value="">${esc(t('model.addModel'))}</option>${optgroups}</select>`;
}

/**
 * Editor for one role's working chain: rows (drag to reorder, remove) + the
 * provider-grouped add dropdown + refresh + manual input. Mutates
 * roleState[role].chain in place; every mutation calls `commit(role)` — the
 * per-action save. Appends itself to `container`.
 */
function buildChainEditor(container, role, commit) {
  const st = roleState.get(role);
  const chain = st.chain;
  if (chain === null) return null;

  const editor = document.createElement('div');
  editor.className = 'mp-editor';
  const probed = [];
  probedModels.forEach((list, prov) => list.forEach(m => probed.push(`${prov}/${m}`)));
  const allRefs = [...new Set([...getAllModelRefs(), ...probed])];
  const current = st.data?.current || '';
  editor.innerHTML = `
    <div class="mp-rows">${chainRowsHtml(chain, current)}</div>
    <div class="mp-addrow">
      ${addSelectHtml(allRefs, chain)}
      <button type="button" class="cfg-btn cfg-btn-sm mp-refresh">${esc(t('model.refreshList'))}</button>
      <button type="button" class="cfg-btn cfg-btn-sm mp-manual-toggle">${esc(t('model.manualInput'))}</button>
      <input type="text" class="cfg-input mp-manual" style="display:none" placeholder="${esc(t('model.manualPlaceholder'))}">
    </div>
    <div class="mp-inline-err" role="alert"></div>
    ${chain.length === 1 ? `<div class="mp-note">${esc(t('model.singleModelNote'))}</div>` : ''}`;

  // Drag reorder (first = preferred)
  let dragIdx = null;
  editor.querySelectorAll('.mp-row').forEach(row => {
    const el = /** @type {HTMLElement} */ (row);
    el.addEventListener('dragstart', (e) => {
      dragIdx = Number(el.dataset.idx);
      el.classList.add('dragging');
      (/** @type {DragEvent} */ (e)).dataTransfer.effectAllowed = 'move';
    });
    el.addEventListener('dragend', () => {
      el.classList.remove('dragging');
      dragIdx = null;
      editor.querySelectorAll('.mp-row').forEach(r => r.classList.remove('drag-over'));
    });
    el.addEventListener('dragover', (e) => {
      e.preventDefault();
      editor.querySelectorAll('.mp-row').forEach(r => r.classList.remove('drag-over'));
      el.classList.add('drag-over');
    });
    el.addEventListener('drop', (e) => {
      e.preventDefault();
      const over = Number(el.dataset.idx);
      if (dragIdx === null || dragIdx === over) return;
      const [moved] = chain.splice(dragIdx, 1);
      chain.splice(over, 0, moved);
      commit(role);
    });
  });

  // Remove — the last remaining row cannot be deleted (a chain keeps at least
  // one model; the seed fallback is reachable through the explicit controls:
  // clear-chain on Nebula, re-follow on followers).
  editor.querySelectorAll('.mp-remove').forEach(btn => {
    (/** @type {HTMLElement} */ (btn)).addEventListener('click', (e) => {
      e.stopPropagation();
      if (chain.length <= 1) { flashInline(editor, t('model.errMinOne')); return; }
      chain.splice(Number((/** @type {HTMLElement} */ (btn)).dataset.idx), 1);
      commit(role);
    });
  });

  // Add from the provider-grouped dropdown
  const addSel = /** @type {HTMLSelectElement|null} */ (editor.querySelector('.mp-add-select'));
  addSel?.addEventListener('change', () => {
    const ref = addSel.value;
    if (!ref) return;
    if (chain.includes(ref)) { flashInline(editor, t('model.errDuplicate')); addSel.value = ''; return; }
    chain.push(ref);
    commit(role);
  });

  // Manual input fallback (revealed on refresh failure or on demand)
  const manual = /** @type {HTMLInputElement|null} */ (editor.querySelector('.mp-manual'));
  editor.querySelector('.mp-manual-toggle')?.addEventListener('click', () => {
    if (manual) { manual.style.display = ''; manual.focus(); }
  });
  manual?.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    const ref = manual.value.trim();
    if (!ref) return;
    if (chain.includes(ref)) { flashInline(editor, t('model.errDuplicate')); return; }
    chain.push(ref);
    manual.value = '';
    commit(role);
  });

  editor.querySelector('.mp-refresh')?.addEventListener('click', () => { refreshModelList(); });

  container.appendChild(editor);
  return editor;
}

// ── Role blocks ────────────────────────────────────────────

function statusChipHtml(role) {
  const st = roleState.get(role);
  const forked = (st?.chain || null) !== null;
  if (role === 'Nebula') {
    return `<span class="mp-status mp-status-main">${esc(t('model.mainChain'))}</span>`;
  }
  return forked
    ? `<span class="mp-status mp-status-fork">${esc(t('model.independent'))}</span>`
    : `<span class="mp-status">${esc(t('model.followsNebula'))}</span>`;
}

/** Fork action shared by Nebula (seed → explicit) and followers (follow → fork). */
function buildForkButton(body, role, commit) {
  const refs = nebulaEffectiveRefs();
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'cfg-btn cfg-btn-sm';
  btn.textContent = t('model.makeIndependent');
  if (refs.length === 0) {
    // Nothing to start from (no configured provider / empty install).
    btn.disabled = true;
  } else {
    btn.addEventListener('click', () => {
      const st = roleState.get(role);
      st.chain = refs.slice();
      commit(role);
    });
  }
  body.appendChild(btn);
}

function buildNebulaBlock(container, commit) {
  const block = document.createElement('div');
  block.className = 'mp-roleblock mp-roleblock-nebula';
  const st = roleState.get('Nebula');
  const noChain = (st?.chain || null) === null;
  block.innerHTML = `
    <div class="mp-role-head">
      <span class="mp-role-name">${esc(roleLabel('Nebula'))}</span>
      ${statusChipHtml('Nebula')}
    </div>
    <div class="mp-role-body"></div>`;
  const body = /** @type {HTMLElement} */ (block.querySelector('.mp-role-body'));

  if (noChain) {
    // Seed state: the effective (seed) chain read-only + the action that
    // starts an explicit chain from it.
    const note = document.createElement('div');
    note.className = 'mp-note mp-note-seed';
    note.textContent = t('model.seedNote');
    body.appendChild(note);
    if (st?.data) {
      const preview = document.createElement('div');
      preview.className = 'mp-preview';
      preview.innerHTML = resolvedChainChipsHtml(st.data);
      body.appendChild(preview);
    }
    buildForkButton(body, 'Nebula', commit);
  } else {
    // Unified mode: the failover-order note + the line naming the followers.
    if (panelMode === 'unified') {
      const foot = document.createElement('div');
      foot.className = 'mp-foot';
      foot.innerHTML = `
        <div class="mp-note">${esc(t('model.orderNote'))}</div>
        <div class="mp-note"><span class="mp-followers-label">${esc(t('model.followers'))}</span> ${esc(FOLLOWER_ROLES.map(roleLabel).join(' · '))}</div>`;
      body.appendChild(foot);
    }
    buildChainEditor(body, 'Nebula', commit);
    // Explicit chain only: the way back to the seed state.
    const foot = document.createElement('div');
    foot.className = 'mp-foot';
    const clearBtn = document.createElement('button');
    clearBtn.type = 'button';
    clearBtn.className = 'cfg-btn cfg-btn-sm mp-clear';
    clearBtn.textContent = t('model.clearChain');
    clearBtn.addEventListener('click', () => {
      window.__showConfirm?.(t('model.clearChain'), t('model.clearChainConfirm'), async () => {
        await putChain('Nebula', null);
      }, { tone: 'neutral' });
    });
    foot.appendChild(clearBtn);
    body.appendChild(foot);
  }

  container.appendChild(block);
}

function buildFollowerBlock(container, role, commit) {
  const block = document.createElement('div');
  block.className = 'mp-roleblock mp-roleblock-follower';
  block.dataset.role = role;
  const st = roleState.get(role);
  const forked = (st?.chain || null) !== null;
  const previewRefs = st?.chain || nebulaEffectiveRefs();
  block.innerHTML = `
    <div class="mp-role-head mp-role-head-toggle" role="button" tabindex="0" aria-expanded="${st?.expanded ? 'true' : 'false'}">
      <span class="mp-chev${st?.expanded ? ' open' : ''}"><svg viewBox="0 0 10 10" width="10" height="10" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2.5 3.5 5 6l2.5-2.5"/></svg></span>
      <span class="mp-role-name">${esc(roleLabel(role))}</span>
      ${statusChipHtml(role)}
      <span class="mp-head-preview">${esc(previewRefs.map(shortModel).join(' → '))}</span>
    </div>
    <div class="mp-role-body" style="display:${st?.expanded ? '' : 'none'}"></div>`;
  const body = /** @type {HTMLElement} */ (block.querySelector('.mp-role-body'));

  if (st?.expanded) {
    if (!st.data) {
      body.innerHTML = `<div class="mp-note">${esc(t('model.loadFailed'))}</div>`;
    } else if (!forked) {
      // Follow state: live preview of the Nebula primary chain (re-read after
      // every save) + the explicit fork action. No copy is materialized here.
      body.innerHTML = `
        <div class="mp-note">${esc(t('model.followsNebula'))} — ${esc(t('model.effectivePreview'))}</div>
        <div class="mp-preview">${resolvedChainChipsHtml(st.data)}</div>`;
      buildForkButton(body, role, commit);
    } else {
      // Fork state: own chain editor + the explicit re-follow control.
      body.innerHTML = `<div class="mp-note mp-note-fork">${esc(t('model.independent'))}</div>`;
      if (role === 'kernel') {
        const kn = document.createElement('div');
        kn.className = 'mp-note';
        kn.textContent = t('model.kernelNote');
        body.appendChild(kn);
      }
      buildChainEditor(body, role, commit);
      const foot = document.createElement('div');
      foot.className = 'mp-foot';
      const restoreBtn = document.createElement('button');
      restoreBtn.type = 'button';
      restoreBtn.className = 'cfg-btn cfg-btn-sm mp-restore';
      restoreBtn.textContent = t('model.restoreFollow');
      restoreBtn.addEventListener('click', () => {
        const preview = nebulaEffectiveRefs().join(' → ') || t('model.seedNote');
        window.__showConfirm?.(
          t('model.restoreFollow'),
          t('model.restoreFollowConfirm', { chain: preview }),
          async () => { await putChain(role, null); },
          { tone: 'neutral' },
        );
      });
      foot.appendChild(restoreBtn);
      body.appendChild(foot);
    }
  }

  container.appendChild(block);

  const head = /** @type {HTMLElement} */ (block.querySelector('.mp-role-head-toggle'));
  const toggle = () => {
    const s = roleState.get(role);
    s.expanded = !s.expanded;
    renderPanel(); // full re-render — `block` above is detached afterwards
    if (s.expanded) {
      // Same reveal animation as the AskUser dependsOn branches — applied to
      // the FRESH body element from the re-rendered DOM.
      panelEl?.querySelector(`.mp-roleblock-follower[data-role="${role}"] .mp-role-body`)
        ?.classList.add('ob-q-reveal');
    }
  };
  head.addEventListener('click', toggle);
  head.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
  });
}

// ── Panel skeleton + render ────────────────────────────────

function renderBannerContent() {
  const banner = panelEl?.querySelector('.mp-banner');
  if (!banner) return;
  const roles = forkedRoles().map(roleLabel).join(' · ');
  banner.innerHTML = `<span class="mp-banner-text">${esc(t('model.divergedBar', { roles }))}</span><button type="button" class="cfg-btn cfg-btn-sm mp-banner-view">${esc(t('model.divergedView'))}</button>`;
  banner.querySelector('.mp-banner-view')?.addEventListener('click', () => setMode('separate'));
}

function renderPanel() {
  if (!panelEl) return;
  const nebula = /** @type {HTMLElement} */ (panelEl.querySelector('.mp-nebula-slot'));
  const followers = /** @type {HTMLElement} */ (panelEl.querySelector('.mp-followers'));
  nebula.replaceChildren();
  followers.replaceChildren();
  const commit = (role) => { saveChain(role); };
  buildNebulaBlock(nebula, commit);
  FOLLOWER_ROLES.forEach(role => buildFollowerBlock(followers, role, commit));
  renderBannerContent();
  // Block visibility = f(mode, fork state) — the shared widget decides which
  // of the banner / follower group is showing.
  panelVisibility?.update();
}

async function refreshPanel() {
  await fetchAllRoles();
  renderPanel();
}

function setMode(mode) {
  panelMode = mode;
  panelEl?.querySelectorAll('.mp-mode-btn').forEach(btn => {
    const on = btn.dataset.mode === mode;
    btn.classList.toggle('on', on);
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
  });
  renderPanel();
}

function buildPanel() {
  const overlay = document.createElement('div');
  overlay.className = 'cfg-modal-overlay';
  overlay.id = 'model-panel-overlay';
  overlay.innerHTML = `
    <div class="model-panel" tabindex="-1" role="dialog" aria-label="${esc(t('model.title'))}">
      <div class="mp-header">
        <span class="mp-title">${esc(t('model.title'))}</span>
        <div class="mp-modes" role="group" aria-label="${esc(t('model.title'))}">
          <button type="button" class="mp-mode-btn on" data-mode="unified" aria-pressed="true">${esc(t('model.modeUnified'))}</button>
          <button type="button" class="mp-mode-btn" data-mode="separate" aria-pressed="false">${esc(t('model.modeSeparate'))}</button>
        </div>
        <button type="button" class="mp-close panel-btn" title="×"><svg viewBox="0 0 12 12" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true"><path d="M3 3l6 6M9 3l-6 6"/></svg></button>
      </div>
      <div class="mp-banner" style="display:none"></div>
      <div class="mp-body">
        <div class="mp-nebula-slot"></div>
        <div class="mp-followers" style="display:none"></div>
      </div>
    </div>`;
  document.body.appendChild(overlay);

  // The conditional-display pair: item 0 = divergence banner (unified mode ∧
  // at least one follower forked), item 1 = follower group (separate mode).
  panelVisibility = createDepVisibility({
    isVisible: (i) => (i === 0
      ? panelMode === 'unified' && forkedRoles().length > 0
      : panelMode === 'separate'),
  });
  panelVisibility.register(/** @type {HTMLElement} */ (overlay.querySelector('.mp-banner')));
  panelVisibility.register(/** @type {HTMLElement} */ (overlay.querySelector('.mp-followers')));

  const close = () => closeModelPanel();
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  overlay.querySelector('.mp-close')?.addEventListener('click', close);
  overlay.querySelector('.mp-mode-btn[data-mode="unified"]')?.addEventListener('click', () => setMode('unified'));
  overlay.querySelector('.mp-mode-btn[data-mode="separate"]')?.addEventListener('click', () => setMode('separate'));

  return overlay;
}

/** Open (or focus) the /model panel. UI-only command entry. */
export async function openModelPanel() {
  // Boundary: the panel is a singleton — a repeated /model focuses the open
  // instance instead of stacking another one.
  if (panelEl) {
    /** @type {HTMLElement} */ (panelEl.querySelector('.model-panel'))?.focus();
    return;
  }
  panelEl = buildPanel();
  /** @type {HTMLElement} */ (panelEl.querySelector('.model-panel'))?.focus();
  await refreshPanel();
}

function closeModelPanel() {
  panelEl?.remove();
  panelEl = null;
  panelVisibility = null;
}
