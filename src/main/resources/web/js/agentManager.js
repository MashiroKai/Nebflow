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
// Icons for the System Prompt render/source toggle (mirror fileViewers.js)
const CODE_ICON_SVG = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>';
const EYE_ICON_SVG = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
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

async function fetchSkills() {
  try {
    const resp = await fetch('/api/skills', { headers: authHeaders() });
    if (!resp.ok) return [];
    const data = await resp.json();
    return data.skills || [];
  } catch (e) { return []; }
}

async function fetchFlows() {
  try {
    const resp = await fetch('/api/flows/list', { headers: authHeaders() });
    if (!resp.ok) return [];
    const data = await resp.json();
    return data.flows || [];
  } catch (e) { return []; }
}

/** PUT agent skills/flows config (persisted to agent.json). */
async function setAgentSkillsFlows(name, field, value) {
  try {
    await fetch(`/api/agents/${encodeURIComponent(name)}`, {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ [field]: value }),
    });
  } catch (e) { /* non-critical */ }
}

// ── Sidebar list ───────────────────────────────────────────

/** Render a single agent card HTML string. */
function renderAgentCard(a) {
  const name = esc(a.name);
  const display = esc(a.displayName || a.name);
  const desc = esc(a.description || '');
  const initial = esc((a.displayName || a.name || '?').charAt(0).toUpperCase());
  const isGlobalStandalone = (a.layer === 'global' || !a.layer) && (a.category || 'standalone') === 'standalone';
  const badge = isGlobalStandalone
    ? '<span class="agent-mgr-standalone-badge">可直接委派</span>'
    : '';
  return `<div class="agent-mgr-card" data-agent="${name}">
    <span class="agent-mgr-avatar">${initial}</span>
    <div class="agent-mgr-info">
      <div class="agent-mgr-name">${display}${badge}</div>
      <div class="agent-mgr-desc">${desc}</div>
    </div>
    <span class="agent-mgr-model-tag" data-agent="${name}"></span>
  </div>`;
}

/** Render the agent list into #agents-content, grouped by layer + scope. */
export function renderAgentManager() {
  const content = document.getElementById('agents-content');
  if (!content) return;
  content.innerHTML = `<div class="agent-mgr-loading">Loading...</div>`;

  fetchAgents().then(agents => {
    if (agents.length === 0) {
      content.innerHTML = `<div class="agent-mgr-empty">No agents configured</div>`;
      return;
    }

    // Group by layer + scope
    const global = agents.filter(a => a.layer === 'global' || !a.layer);
    const teams = {};
    const flows = {};

    agents.forEach(a => {
      if (a.layer === 'team') {
        (teams[a.scope] = teams[a.scope] || []).push(a);
      } else if (a.layer === 'flow') {
        (flows[a.scope] = flows[a.scope] || []).push(a);
      }
    });

    let html = '';

    // Global section
    if (global.length > 0) {
      html += `<div class="agent-mgr-group">`;
      html += `<div class="agent-mgr-group-header">Global Agents</div>`;
      html += global.map(renderAgentCard).join('');
      html += `</div>`;
    }

    // Team sections (sorted by name)
    for (const [team, teamAgents] of Object.entries(teams).sort()) {
      html += `<div class="agent-mgr-group">`;
      html += `<div class="agent-mgr-group-header">Team: ${esc(team)}</div>`;
      html += teamAgents.map(renderAgentCard).join('');
      html += `</div>`;
    }

    // Flow sections (sorted by name)
    for (const [flow, flowAgents] of Object.entries(flows).sort()) {
      html += `<div class="agent-mgr-group">`;
      html += `<div class="agent-mgr-group-header">Flow: ${esc(flow)}</div>`;
      html += flowAgents.map(renderAgentCard).join('');
      html += `</div>`;
    }

    content.innerHTML = html;

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
  // preferred is the configured model — trust it over `current`
  // (current is only a reference from the backend resolution).
  const current = model.preferred || model.current || model.default || '';
  if (!current) return;
  const tag = document.querySelector(`.agent-mgr-model-tag[data-agent="${esc(name)}"]`);
  if (!tag) return;
  const isFallback = model.preferred && current !== model.preferred;
  const isDefault = !model.preferred;
  tag.innerHTML = `<span class="agent-mgr-model-pill${isFallback ? ' fallback' : ''}">${esc(shortModel(current))}</span>${isDefault ? '<span class="agent-mgr-default-badge">默认</span>' : ''}`;
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

  // Whether the agent has its own model config (vs using global default)
  const hasOwnConfig = preferredRaw || (fallbacksRaw && fallbacksRaw.length > 0);

  const allTools = (state.availableTools || []).map(t => typeof t === 'string' ? t : t.name);
  const isAll = tools.includes('*');

  const toolsHtml = `<div class="agent-detail-tools-grid" id="agent-detail-tools-grid">
    ${allTools.map(tname => {
      const checked = isAll || tools.includes(tname);
      return `<span class="agent-detail-tool-check${checked ? ' checked' : ''}" data-tool="${esc(tname)}">${esc(tname)}</span>`;
    }).join('')}
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
        <div class="agent-detail-label">Model${hasOwnConfig ? '' : '<span class="agent-detail-default-badge">默认</span>'}</div>
        <div id="agent-detail-model-list"></div>
      </div>

      <div class="agent-detail-section">
        <div class="agent-detail-label">Tools</div>
        ${toolsHtml}
      </div>

      <div class="agent-detail-section">
        <div class="agent-detail-label">Skills</div>
        <div class="agent-detail-sub-hint">已选 skill 的 name+description 注入此 agent 的 system prompt</div>
        <div class="agent-detail-skills-grid" id="agent-detail-skills-grid"><span class="agent-detail-chips-loading">Loading…</span></div>
      </div>

      <div class="agent-detail-section">
        <div class="agent-detail-label">Flows</div>
        <div class="agent-detail-sub-hint">此 agent 可通过 Delegate(flow=…) 触发的 flow</div>
        <div class="agent-detail-flows-grid" id="agent-detail-flows-grid"><span class="agent-detail-chips-loading">Loading…</span></div>
      </div>

      <div class="agent-detail-section" id="agent-detail-prompt-section">
        <div class="agent-detail-label-row">
          <span class="agent-detail-label">System Prompt</span>
          <button class="agent-detail-prompt-toggle" id="agent-detail-prompt-toggle" title="View source">${CODE_ICON_SVG}</button>
        </div>
        <div class="agent-detail-prompt-render canvas-md-viewer" id="agent-detail-prompt-render"></div>
        <textarea class="agent-detail-prompt-edit" data-agent="${esc(name)}" style="display:none">${esc(prompt)}</textarea>
        <button class="agent-detail-save-btn" id="agent-detail-save-prompt" style="display:none">Save</button>
      </div>
    </div>`;

  // Model section: always render editable drag list.
  // When agent has no own config, pre-fill with global default chain.
  // hasOwnConfig declared above (before template literal) to avoid TDZ.
  const modelListEl = pane.querySelector('#agent-detail-model-list');
  if (modelListEl) {
    let effectiveList = modelList;
    if (!hasOwnConfig) {
      const globalDefault = state.parsedConfig?.llm?.model?.default || '';
      const globalFallbacks = state.parsedConfig?.llm?.model?.fallbacks || [];
      effectiveList = [globalDefault, ...globalFallbacks].filter(Boolean);
    }
    renderModelDragList(modelListEl, name, effectiveList, current, allRefs);
  }

  // System prompt: rendered markdown view ↔ source textarea toggle.
  // Rendered mode is default; Save only shows in source mode.
  const promptRender = pane.querySelector('#agent-detail-prompt-render');
  const promptEdit = pane.querySelector('.agent-detail-prompt-edit');
  const promptToggle = pane.querySelector('#agent-detail-prompt-toggle');
  const saveBtn = pane.querySelector('#agent-detail-save-prompt');

  let renderMd = null;
  let sourceMode = false;
  import('./utils.js').then(({ renderMarkdownWithMath }) => {
    renderMd = renderMarkdownWithMath;
    if (pane.isConnected) promptRender.innerHTML = renderMd(prompt);
  });

  const setPromptMode = (src) => {
    sourceMode = src;
    promptToggle.innerHTML = src ? EYE_ICON_SVG : CODE_ICON_SVG;
    promptToggle.title = src ? 'View rendered' : 'View source';
    promptRender.style.display = src ? 'none' : '';
    promptEdit.style.display = src ? '' : 'none';
    saveBtn.style.display = src ? '' : 'none';
    // Switching back to rendered re-renders the current textarea value
    if (!src && renderMd) promptRender.innerHTML = renderMd(promptEdit.value);
  };

  promptToggle?.addEventListener('click', () => setPromptMode(!sourceMode));

  // Bind system prompt save (source mode only)
  saveBtn?.addEventListener('click', () => {
    const text = promptEdit.value;
    sendWs({ type: 'updateAgentSystemPrompt', name, systemMd: text });
    saveBtn.textContent = 'Saved';
    setTimeout(() => { saveBtn.textContent = 'Save'; }, 1500);
  });

  // Bind tool toggle chips
  const toolsGrid = pane.querySelector('#agent-detail-tools-grid');
  if (toolsGrid) {
    const currentTools = new Set(tools);
    toolsGrid.querySelectorAll('.agent-detail-tool-check').forEach(el => {
      el.addEventListener('click', () => {
        const tool = el.dataset.tool;
        el.classList.toggle('checked');
        if (el.classList.contains('checked')) {
          currentTools.add(tool);
        } else {
          currentTools.delete(tool);
        }
        sendWs({ type: 'updateAgentTools', name, tools: [...currentTools] });
      });
    });
  }

  // Load skills/flows catalogs and render toggle chips (async, non-blocking)
  loadSkillsFlowsSection(pane, name, detail);
}

/**
 * Render the Skills and Flows chip grids. Each chip toggles membership;
 * changes are persisted via PUT /api/agents/:name (agent.json skills/flows).
 */
async function loadSkillsFlowsSection(pane, name, detail) {
  const [skills, flows] = await Promise.all([fetchSkills(), fetchFlows()]);
  if (!pane.isConnected) return; // tab closed while fetching

  const currentSkills = new Set(detail?.skills || []);
  const currentFlows = new Set(detail?.flows || []);

  const skillsGrid = pane.querySelector('#agent-detail-skills-grid');
  if (skillsGrid) {
    if (skills.length === 0) {
      skillsGrid.innerHTML = '<span class="agent-detail-chips-empty">No skills installed</span>';
    } else {
      skillsGrid.innerHTML = '';
      skills.forEach(s => {
        const checked = currentSkills.has(s.name);
        const chip = document.createElement('span');
        chip.className = `agent-detail-skill-check${checked ? ' checked' : ''}`;
        chip.dataset.name = s.name;
        chip.title = s.description || s.name;
        chip.textContent = s.name;
        chip.addEventListener('click', () => {
          chip.classList.toggle('checked');
          if (chip.classList.contains('checked')) currentSkills.add(s.name);
          else currentSkills.delete(s.name);
          setAgentSkillsFlows(name, 'skills', [...currentSkills]);
        });
        skillsGrid.appendChild(chip);
      });
    }
  }

  const flowsGrid = pane.querySelector('#agent-detail-flows-grid');
  if (flowsGrid) {
    if (flows.length === 0) {
      flowsGrid.innerHTML = '<span class="agent-detail-chips-empty">No flows defined</span>';
    } else {
      flowsGrid.innerHTML = '';
      flows.forEach(f => {
        const checked = currentFlows.has(f.name);
        const chip = document.createElement('span');
        chip.className = `agent-detail-flow-check${checked ? ' checked' : ''}`;
        chip.dataset.name = f.name;
        chip.title = f.description || f.name;
        chip.textContent = f.name;
        chip.addEventListener('click', () => {
          chip.classList.toggle('checked');
          if (chip.classList.contains('checked')) currentFlows.add(f.name);
          else currentFlows.delete(f.name);
          setAgentSkillsFlows(name, 'flows', [...currentFlows]);
        });
        flowsGrid.appendChild(chip);
      });
    }
  }
}

// ── Public ─────────────────────────────────────────────────

export function isAgentsPanelActive() {
  const panel = document.getElementById('panel-agents');
  return !!panel && panel.classList.contains('active');
}
