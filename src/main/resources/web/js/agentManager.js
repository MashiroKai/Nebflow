// agentManager.js — Agent management panel in the sidebar.
// Lists all configured agents with model info, tools, and system prompt.
// Accessed via the "users" icon in the Activity Bar.

import state from './state.js';
import { renderCapPills } from './modelCapabilities.js';

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

// ── State ──────────────────────────────────────────────────
let agents = [];
let expandedAgent = null;
let agentDetails = {}; // cache: name → { tools, systemPrompt, ... }
let agentModels = {};  // cache: name → { preferred, current, ... }

// ── API ────────────────────────────────────────────────────
async function fetchAgents() {
  try {
    const resp = await fetch('/api/agents', { headers: authHeaders() });
    if (!resp.ok) return;
    const data = await resp.json();
    agents = data.agents || [];
  } catch (e) { agents = []; }
}

async function fetchAgentDetail(name) {
  try {
    const resp = await fetch(`/api/agents/${encodeURIComponent(name)}`, { headers: authHeaders() });
    if (!resp.ok) return null;
    const data = await resp.json();
    agentDetails[name] = data;
    return data;
  } catch (e) { return null; }
}

async function fetchAgentModel(name) {
  try {
    const resp = await fetch(`/api/agents/${encodeURIComponent(name)}/model`, { headers: authHeaders() });
    if (!resp.ok) return null;
    const data = await resp.json();
    agentModels[name] = data;
    return data;
  } catch (e) { return null; }
}

async function setAgentModel(name, preferred) {
  try {
    await fetch(`/api/agents/${encodeURIComponent(name)}/model`, {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ preferred }),
    });
    await fetchAgentModel(name);
    render();
  } catch (e) { /* non-critical */ }
}

// ── Render ─────────────────────────────────────────────────
function render() {
  const content = document.getElementById('agents-content');
  if (!content) return;

  if (agents.length === 0) {
    content.innerHTML = `<div class="cfg-empty">No agents configured</div>`;
    return;
  }

  content.innerHTML = agents.map(a => {
    const isExpanded = expandedAgent === a.name;
    const model = agentModels[a.name];
    const detail = agentDetails[a.name];

    const preferred = model?.preferred || model?.default || '';
    const current = model?.current || preferred;
    const isFallback = current && preferred && current !== preferred;
    const displayName = a.displayName || a.name;
    const desc = a.description || '';

    // Collapsed card
    if (!isExpanded) {
      return `
        <div class="agent-mgr-card" data-agent="${esc(a.name)}">
          <div class="agent-mgr-head">
            <span class="agent-mgr-name">${esc(displayName)}</span>
            ${current ? `<span class="agent-mgr-model${isFallback ? ' fallback' : ''}">${esc(shortModel(current))}</span>` : ''}
          </div>
          ${desc ? `<div class="agent-mgr-desc">${esc(desc)}</div>` : ''}
        </div>`;
    }

    // Expanded card
    const toolsHtml = detail?.tools?.length
      ? `<div class="agent-mgr-tools">${detail.tools.map(t => `<span class="agent-mgr-tool">${esc(t)}</span>`).join('')}</div>`
      : `<div class="agent-mgr-empty">No tools</div>`;

    const promptHtml = detail?.systemPrompt
      ? `<div class="agent-mgr-prompt" data-collapsed="true">${esc(detail.systemPrompt.substring(0, 200))}${detail.systemPrompt.length > 200 ? '<span class="agent-mgr-prompt-more">…</span>' : ''}</div>`
      : '';

    const allRefs = state.allModelRefs || [];

    return `
      <div class="agent-mgr-card expanded" data-agent="${esc(a.name)}">
        <div class="agent-mgr-head">
          <span class="agent-mgr-name">${esc(displayName)}</span>
          ${isFallback ? `<span class="agent-mgr-model fallback">${esc(shortModel(current))}</span>` : ''}
        </div>
        ${desc ? `<div class="agent-mgr-desc">${esc(desc)}</div>` : ''}
        <div class="agent-mgr-section">
          <div class="agent-mgr-label">Model</div>
          <select class="agent-mgr-select" data-agent="${esc(a.name)}">
            ${preferred
              ? `<option value="${esc(preferred)}" selected>${esc(preferred)}</option>`
              : `<option value="" selected>Global default</option>`}
            ${allRefs.filter(r => r !== preferred).map(r => `<option value="${esc(r)}">${esc(r)}</option>`).join('')}
          </select>
          ${isFallback ? `<div class="agent-mgr-model-note">Running: ${esc(current)} (fallback)</div>` : ''}
        </div>
        <div class="agent-mgr-section">
          <div class="agent-mgr-label">Tools</div>
          ${toolsHtml}
        </div>
        ${promptHtml ? `<div class="agent-mgr-section"><div class="agent-mgr-label">System Prompt</div>${promptHtml}</div>` : ''}
      </div>`;
  }).join('');

  bindEvents();
}

function bindEvents() {
  const content = document.getElementById('agents-content');
  if (!content) return;

  // Card click — toggle expand
  content.querySelectorAll('.agent-mgr-card').forEach(card => {
    card.addEventListener('click', async (e) => {
      // Don't toggle when clicking the select
      if (e.target.tagName === 'SELECT' || e.target.tagName === 'OPTION') return;
      const name = card.dataset.agent;
      if (expandedAgent === name) {
        expandedAgent = null;
        render();
      } else {
        expandedAgent = name;
        // Fetch details + model in parallel
        await Promise.all([fetchAgentDetail(name), fetchAgentModel(name)]);
        render();
      }
    });
  });

  // Model select change
  content.querySelectorAll('.agent-mgr-select').forEach(sel => {
    sel.addEventListener('change', (e) => {
      e.stopPropagation();
      const name = sel.dataset.agent;
      setAgentModel(name, sel.value);
    });
    sel.addEventListener('click', (e) => e.stopPropagation());
  });

  // System prompt expand
  content.querySelectorAll('.agent-mgr-prompt').forEach(el => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      const collapsed = el.dataset.collapsed === 'true';
      if (collapsed) {
        el.dataset.collapsed = 'false';
        const detail = agentDetails[el.closest('.agent-mgr-card')?.dataset.agent];
        if (detail?.systemPrompt) el.textContent = detail.systemPrompt;
      } else {
        el.dataset.collapsed = 'true';
        const detail = agentDetails[el.closest('.agent-mgr-card')?.dataset.agent];
        if (detail?.systemPrompt) el.innerHTML = esc(detail.systemPrompt.substring(0, 200)) + '<span class="agent-mgr-prompt-more">…</span>';
      }
    });
  });
}

function shortModel(ref) {
  const idx = ref.lastIndexOf('/');
  return idx >= 0 ? ref.slice(idx + 1) : ref;
}

// ── Public ─────────────────────────────────────────────────

export function renderAgentManager() {
  const content = document.getElementById('agents-content');
  if (!content) return;
  content.innerHTML = `<div class="cfg-empty">Loading...</div>`;
  fetchAgents().then(() => render());
}

export function isAgentsPanelActive() {
  const panel = document.getElementById('panel-agents');
  return !!panel && panel.classList.contains('active');
}
