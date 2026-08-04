// agentManager.js — Agent management panel (VSCode Extensions style).
// Sidebar list shows compact agent cards. Click opens a Canvas detail tab.

import state from './state.js';
import { sendWs } from './ws.js';
import { openTab, getTabPane } from './canvas.js';

// ── Helpers ────────────────────────────────────────────────
function getToken() { return localStorage.getItem('nebflow_token') || ''; }
function authHeaders() {
  const tok = getToken();
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}
function shortModel(ref) {
  if (!ref) return '';
  const idx = ref.lastIndexOf('/');
  return idx >= 0 ? ref.slice(idx + 1) : ref;
}

/** Build model refs from config if state.allModelRefs is empty. */
function getAllModelRefs() {
  let refs = state.allModelRefs || [];
  if (refs.length === 0 && state.parsedConfig?.llm?.providers) {
    refs = [];
    for (const [name, p] of Object.entries(state.parsedConfig.llm.providers)) {
      (p.models || []).forEach(m => refs.push(`${name}/${m.id}`));
    }
  }
  return refs;
}

// ── API ────────────────────────────────────────────────────
async function fetchAgents() {
  try {
    const resp = await fetch('/api/agents', { headers: authHeaders() });
    if (!resp.ok) return [];
    const data = await resp.json();
    return data.agents || [];
  } catch (e) { return []; }
}

async function fetchAgentDetail(name) {
  try {
    const resp = await fetch(`/api/agents/${encodeURIComponent(name)}`, { headers: authHeaders() });
    if (!resp.ok) return null;
    return await resp.json();
  } catch (e) { return null; }
}

async function fetchAgentModel(name) {
  try {
    const resp = await fetch(`/api/agents/${encodeURIComponent(name)}/model`, { headers: authHeaders() });
    if (!resp.ok) return null;
    return await resp.json();
  } catch (e) { return null; }
}

// ── Sidebar list ───────────────────────────────────────────

/** Render the agent list into #agents-content. */
export function renderAgentManager() {
  const content = document.getElementById('agents-content');
  if (!content) return;
  content.innerHTML = `<div class="agent-mgr-loading">Loading...</div>`;

  fetchAgents().then(agents => {
    if (agents.length === 0) {
      content.innerHTML = `<div class="agent-mgr-empty">No agents configured</div>`;
      return;
    }

    content.innerHTML = agents.map(a => {
      const name = esc(a.name);
      const display = esc(a.displayName || a.name);
      const desc = esc(a.description || '');
      const initial = esc((a.displayName || a.name || '?').charAt(0).toUpperCase());
      return `<div class="agent-mgr-card" data-agent="${name}">
        <span class="agent-mgr-avatar">${initial}</span>
        <div class="agent-mgr-info">
          <div class="agent-mgr-name">${display}</div>
          <div class="agent-mgr-desc">${desc}</div>
        </div>
        <span class="agent-mgr-model-tag" data-agent="${name}"></span>
      </div>`;
    }).join('');

    // Bind card clicks (single = preview, double = pinned — VS Code style)
    content.querySelectorAll('.agent-mgr-card').forEach(card => {
      card.addEventListener('click', () => openAgentDetail(card.dataset.agent));
      card.addEventListener('dblclick', () => openAgentDetail(card.dataset.agent, true));
    });

    // Async-populate model tags
    agents.forEach(a => populateModelTag(a.name));
  });
}

/** Fetch model info and inject a short tag into the card. */
async function populateModelTag(name) {
  const model = await fetchAgentModel(name);
  if (!model) return;
  const current = model.current || model.preferred || model.default || '';
  if (!current) return;
  const tag = document.querySelector(`.agent-mgr-model-tag[data-agent="${esc(name)}"]`);
  if (!tag) return;
  const isFallback = model.preferred && current !== model.preferred;
  tag.innerHTML = `<span class="agent-mgr-model-pill${isFallback ? ' fallback' : ''}">${esc(shortModel(current))}</span>`;
}

// ── Canvas detail tab ──────────────────────────────────────

/** Open a Canvas tab showing the agent detail page. */
async function openAgentDetail(name, pin = false) {
  const tabId = `agent:${name}`;
  openTab(tabId, name, { type: 'agent', pinned: pin });
  const pane = getTabPane(tabId);
  if (!pane) return;

  // Loading state
  pane.innerHTML = `<div class="agent-detail-loading">Loading...</div>`;

  // Fetch detail + model in parallel
  const [detail, model] = await Promise.all([
    fetchAgentDetail(name),
    fetchAgentModel(name),
  ]);

  renderAgentDetail(pane, name, detail, model);
}

/** PUT agent model config (preferred + fallbacks). */
async function setAgentModel(name, ordered) {
  const [preferred, ...fallbacks] = ordered;
  try {
    await fetch(`/api/agents/${encodeURIComponent(name)}/model`, {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ preferred: preferred || null, fallbacks }),
    });
  } catch (e) { /* non-critical */ }
}

/** Render a reusable drag-to-reorder model list into a container.
 *  Returns { destroy } for cleanup (though Canvas tabs don't need it). */
function renderModelDragList(container, name, models, currentModel, allRefs) {
  const playing = currentModel || (models[0] || '');

  const rowsHtml = models.map((ref, i) => {
    const isPlaying = ref === playing;
    return `
    <div class="agent-detail-model-row${isPlaying ? ' playing' : ''}" draggable="true" data-idx="${i}">
      ${isPlaying ? '<span class="agent-detail-playing-dot"></span>' : '<span class="agent-detail-grip">⠿</span>'}
      <span class="agent-detail-model-name">${esc(ref)}</span>
      <span class="agent-detail-model-pos">${i === 0 ? '★' : i}</span>
      <span class="agent-detail-model-remove" data-idx="${i}" title="Remove">×</span>
    </div>`;
  }).join('');

  const available = allRefs.filter(r => !models.includes(r));
  const addHtml = available.length
    ? `<div class="agent-detail-model-add"><select><option value="">+ add model…</option>${available.map(r => `<option value="${esc(r)}">${esc(r)}</option>`).join('')}</select></div>`
    : '';

  container.innerHTML = `${rowsHtml}<div class="agent-detail-empty-spacer"></div>${addHtml}`;

  // Drag reorder
  let dragIdx = null;
  container.querySelectorAll('.agent-detail-model-row').forEach(row => {
    row.addEventListener('dragstart', (e) => {
      dragIdx = Number(row.dataset.idx);
      row.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
    });
    row.addEventListener('dragend', () => {
      row.classList.remove('dragging');
      dragIdx = null;
      container.querySelectorAll('.agent-detail-model-row').forEach(r => r.classList.remove('drag-over'));
    });
    row.addEventListener('dragover', (e) => {
      e.preventDefault();
      container.querySelectorAll('.agent-detail-model-row').forEach(r => r.classList.remove('drag-over'));
      row.classList.add('drag-over');
    });
    row.addEventListener('drop', async (e) => {
      e.preventDefault();
      const overIdx = Number(row.dataset.idx);
      if (dragIdx === null || dragIdx === overIdx) return;
      const next = models.slice();
      const [moved] = next.splice(dragIdx, 1);
      next.splice(overIdx, 0, moved);
      // Persist: send entire ordered list
      await setAgentModel(name, next);
      // Re-render
      renderModelDragList(container, name, next, currentModel, allRefs);
    });
  });

  // Add select
  const addSel = container.querySelector('select');
  if (addSel) {
    addSel.addEventListener('change', async () => {
      const ref = addSel.value;
      if (!ref) return;
      const next = [...models, ref];
      await setAgentModel(name, next);
      renderModelDragList(container, name, next, currentModel, allRefs);
    });
  }

  // Remove buttons
  container.querySelectorAll('.agent-detail-model-remove').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const idx = Number(btn.dataset.idx);
      const next = models.slice();
      next.splice(idx, 1);
      await setAgentModel(name, next);
      renderModelDragList(container, name, next, currentModel, allRefs);
    });
  });
}

function renderAgentDetail(pane, name, detail, model) {
  const displayName = detail?.displayName || detail?.name || name;
  const description = detail?.description || '';
  const extends_ = detail?.extends || '';
  const preferred = model?.preferred || model?.default || '';
  const current = model?.current || preferred;
  const tools = detail?.tools || [];

  // System prompt
  const prompt = detail?.systemPrompt || '';

  // Model list for drag component — [preferred, ...fallbacks]
  const preferredRaw = model?.preferred || model?.default || '';
  const fallbacksRaw = model?.fallbacks || model?.model?.fallbacks || [];
  const modelList = [preferredRaw, ...fallbacksRaw].filter(Boolean);
  const allRefs = getAllModelRefs();

  const toolsHtml = `<div class="agent-detail-tools" id="agent-detail-tools">
    ${tools.length > 0
      ? tools.map((t, i) => `<span class="agent-detail-tool" data-idx="${i}">${esc(t)}<span class="agent-detail-tool-remove" data-idx="${i}">×</span></span>`).join('')
      : '<span class="agent-detail-empty">No tools</span>'}
  </div>
  <div class="agent-detail-tool-add">
    <input type="text" placeholder="+ add tool" id="agent-detail-tool-input">
  </div>`;

  pane.innerHTML = `
    <div class="agent-detail">
      <div class="agent-detail-header">
        <span class="agent-detail-avatar">${esc(displayName.charAt(0).toUpperCase())}</span>
        <div class="agent-detail-header-info">
          <div class="agent-detail-name">${esc(displayName)}</div>
          ${description ? `<div class="agent-detail-desc">${esc(description)}</div>` : ''}
          ${extends_ ? `<div class="agent-detail-extends">extends: ${esc(extends_)}</div>` : ''}
        </div>
      </div>

      <div class="agent-detail-section">
        <div class="agent-detail-label">Model</div>
        <div id="agent-detail-model-list"></div>
      </div>

      <div class="agent-detail-section">
        <div class="agent-detail-label">Tools</div>
        ${toolsHtml}
      </div>

      <div class="agent-detail-section">
        <div class="agent-detail-label">System Prompt</div>
        <textarea class="agent-detail-prompt-edit" data-agent="${esc(name)}">${esc(prompt)}</textarea>
        <button class="agent-detail-save-btn" id="agent-detail-save-prompt">Save</button>
      </div>
    </div>`;

  // Always render model drag list (even empty — shows add dropdown)
  const modelListEl = pane.querySelector('#agent-detail-model-list');
  if (modelListEl) {
    renderModelDragList(modelListEl, name, modelList, current, allRefs);
  }

  // Bind system prompt save
  pane.querySelector('#agent-detail-save-prompt')?.addEventListener('click', () => {
    const text = pane.querySelector('.agent-detail-prompt-edit').value;
    sendWs({ type: 'updateAgentSystemPrompt', name, systemMd: text });
    const btn = pane.querySelector('#agent-detail-save-prompt');
    btn.textContent = 'Saved';
    setTimeout(() => { btn.textContent = 'Save'; }, 1500);
  });

  // Bind tool remove
  pane.querySelectorAll('.agent-detail-tool-remove').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const idx = Number(btn.dataset.idx);
      const next = tools.slice();
      next.splice(idx, 1);
      sendWs({ type: 'updateAgentTools', name, tools: next });
      renderAgentDetail(pane, name, { ...detail, tools: next }, model);
    });
  });

  // Bind tool add (Enter key)
  const toolInput = pane.querySelector('#agent-detail-tool-input');
  toolInput?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      const val = toolInput.value.trim();
      if (!val) return;
      const next = [...tools, val];
      sendWs({ type: 'updateAgentTools', name, tools: next });
      renderAgentDetail(pane, name, { ...detail, tools: next }, model);
    }
  });
}

// ── Public ─────────────────────────────────────────────────

export function isAgentsPanelActive() {
  const panel = document.getElementById('panel-agents');
  return !!panel && panel.classList.contains('active');
}
