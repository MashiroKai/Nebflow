// agentManager.js — Agent management panel (VSCode Extensions style).
// Sidebar list shows compact agent cards. Click opens a Canvas detail tab.

import state from './state.js';
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

    // Bind card clicks
    content.querySelectorAll('.agent-mgr-card').forEach(card => {
      card.addEventListener('click', () => openAgentDetail(card.dataset.agent));
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
async function openAgentDetail(name) {
  const tabId = `agent:${name}`;
  openTab(tabId, name, { type: 'agent' });
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

function renderAgentDetail(pane, name, detail, model) {
  const displayName = detail?.displayName || detail?.name || name;
  const description = detail?.description || '';
  const extends_ = detail?.extends || '';
  const preferred = model?.preferred || model?.default || '';
  const current = model?.current || preferred;
  const isFallback = current && preferred && current !== preferred;
  const tools = detail?.tools || [];

  // System prompt preview (first 200 chars)
  const prompt = detail?.systemPrompt || '';
  const promptPreview = prompt.substring(0, 200);
  const hasMore = prompt.length > 200;

  // Model list (per-agent: currently just preferred)
  const modelItems = preferred
    ? `<div class="agent-detail-model-row${current === preferred ? ' playing' : ''}">
        ${current === preferred ? '<span class="agent-detail-playing-dot"></span>' : ''}
        <span class="agent-detail-model-name">${esc(preferred)}</span>
        ${isFallback ? `<span class="agent-detail-fallback-tag">running: ${esc(current)}</span>` : ''}
      </div>`
    : '<div class="agent-detail-empty">Using global default</div>';

  const toolsHtml = tools.length > 0
    ? `<div class="agent-detail-tools">${tools.map(t => `<span class="agent-detail-tool">${esc(t)}</span>`).join('')}</div>`
    : '<div class="agent-detail-empty">No tools</div>';

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
        ${modelItems}
      </div>

      <div class="agent-detail-section">
        <div class="agent-detail-label">Tools</div>
        ${toolsHtml}
      </div>

      ${prompt ? `
      <div class="agent-detail-section">
        <div class="agent-detail-label">System Prompt</div>
        <div class="agent-detail-prompt" data-collapsed="${hasMore ? 'true' : 'false'}">${esc(promptPreview)}${hasMore ? '<span class="agent-detail-prompt-more">…</span>' : ''}</div>
      </div>` : ''}
    </div>`;

  // Bind system prompt expand
  if (hasMore) {
    const promptEl = pane.querySelector('.agent-detail-prompt');
    promptEl?.addEventListener('click', () => {
      const collapsed = promptEl.dataset.collapsed === 'true';
      if (collapsed) {
        promptEl.dataset.collapsed = 'false';
        promptEl.textContent = prompt;
      } else {
        promptEl.dataset.collapsed = 'true';
        promptEl.innerHTML = esc(promptPreview) + '<span class="agent-detail-prompt-more">…</span>';
      }
    });
  }
}

// ── Public ─────────────────────────────────────────────────

export function isAgentsPanelActive() {
  const panel = document.getElementById('panel-agents');
  return !!panel && panel.classList.contains('active');
}
