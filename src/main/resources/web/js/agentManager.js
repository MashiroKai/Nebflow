// agentManager.js — Agent management as a Canvas tab (VSCode Extensions style).
// The Agents tab lists compact agent cards. Click opens a Canvas detail tab.

import state from './state.js';
import { key } from './branding.js';
import { sendWs } from './ws.js';
import { openTab, getTabPane, hasTab, setActiveTab, isCanvasOpen, openCanvas } from './canvas.js';
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import * as presets from './presets.js';

// ── Helpers ────────────────────────────────────────────────
function getToken() { return localStorage.getItem(key('token')) || ''; }
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
// Lock icon for system-fixed tool chips (inline SVG, no emoji per design rules)
const LOCK_ICON_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>';
// Tools always injected by the system — fallback until the API ships fixedTools
// (Issue removed 2026-08-25 ruling: orchestrator-only, ships via API fixedTools for Nebula)
const FIXED_BASE_TOOLS = ['Read', 'Write', 'Edit', 'Glob', 'Grep', 'Bash'];
const NEBULA_FIXED_TOOLS = [...FIXED_BASE_TOOLS, 'Issue', 'FlowExecute',
  'AgentControl', 'TaskUpdate', 'Delegate', 'Pop', 'AskUserQuestion',
  'TaskCreate', 'Mail', 'Schedule', 'TransferFile'];
function resolveFixedTools(detail) {
  if (Array.isArray(detail?.fixedTools)) return detail.fixedTools;
  if (detail?.name === 'Nebula') return NEBULA_FIXED_TOOLS;
  if (detail?.category === 'team') return [...FIXED_BASE_TOOLS, 'Mail', 'SubTask', 'FlowExecute'];
  if (detail?.category === 'flow') return [...FIXED_BASE_TOOLS, 'FlowReport'];
  return FIXED_BASE_TOOLS;
}

// ── #438 固定工具三类锁样式 (2026-08-27 用户裁定) ──────────────────────────
// 固定集按归属拆两组展示：「系统固定」(six base tools - identical on every
// card) + 「<类别>专属」(this agent class's own injected tools). The owner
// class comes from the AGENT itself (category / orchestrator name), so the
// grouping needs NO new backend metadata and stays correct through the
// pending six-base-tool semantic rework (the contract only changes what lands
// in which bucket, never the two-bucket frame). Cross-card comparison makes
// "哪类固定给谁" readable at a glance.
const FIXED_CLASS_LABEL_KEYS = {
  flow: 'agent.toolsFixedSpecialty.flow',
  team: 'agent.toolsFixedSpecialty.team',
};
/** @param {{name?: string, category?: string} | null} detail */
function fixedClassLabelKey(detail) {
  if (detail?.name === 'Nebula' || !detail?.category) return 'agent.toolsFixedSpecialty.orchestrator';
  return FIXED_CLASS_LABEL_KEYS[detail.category] || 'agent.toolsFixedSpecialty.orchestrator';
}
/** Split a fixed-tool list into [base, specialty], preserving list order. */
function splitFixedTools(fixedTools) {
  const baseSet = new Set(FIXED_BASE_TOOLS);
  const base = fixedTools.filter(t => baseSet.has(t));
  const specialty = fixedTools.filter(t => !baseSet.has(t));
  return { base, specialty };
}
function shortModel(ref) {
  if (!ref) return '';
  const idx = ref.lastIndexOf('/');
  return idx >= 0 ? ref.slice(idx + 1) : ref;
}

// ── API ────────────────────────────────────────────────────
// Fetchers + the skills/flows write-back are exported: plugins.js (the
// agents entry's successor page, 2026-09-04) reuses the exact same data
// paths instead of duplicating contracts. Single write path for
// subscriptions stays here.
export async function fetchAgents() {
  try {
    const resp = await fetch('/api/agents', { headers: authHeaders() });
    if (!resp.ok) return [];
    const data = await resp.json();
    return data.agents || [];
  } catch (e) { return []; }
}

export async function fetchAgentDetail(name) {
  try {
    const resp = await fetch(`/api/agents/${encodeURIComponent(name)}`, { headers: authHeaders() });
    if (!resp.ok) return null;
    return await resp.json();
  } catch (e) { return null; }
}

export async function fetchAgentModel(name) {
  try {
    const resp = await fetch(`/api/agents/${encodeURIComponent(name)}/model`, { headers: authHeaders() });
    if (!resp.ok) return null;
    return await resp.json();
  } catch (e) { return null; }
}

export async function fetchSkills() {
  try {
    const resp = await fetch('/api/skills', { headers: authHeaders() });
    if (!resp.ok) return [];
    const data = await resp.json();
    return data.skills || [];
  } catch (e) { return []; }
}

export async function fetchFlows() {
  try {
    const resp = await fetch('/api/flows/list', { headers: authHeaders() });
    if (!resp.ok) return [];
    const data = await resp.json();
    return data.flows || [];
  } catch (e) { return []; }
}

/** PUT agent skills/flows config (persisted to agent.json). */
export async function setAgentSkillsFlows(name, field, value) {
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
  const isNebula = a.name === 'Nebula';
  const isGlobalStandalone = (a.layer === 'global' || !a.layer) && (a.category || 'standalone') === 'standalone';
  // Nebula is the orchestrator, not a delegatable standalone — never shows the badge.
  const showBadge = isGlobalStandalone && !isNebula;
  const badge = showBadge
    ? '<span class="agent-mgr-standalone-badge">可直接委派</span>'
    : '';
  // ⑧ Nebula: orbit icon avatar (identity must be stable — custom avatar not accepted)
  // + orchestrator badge pill. All other cards keep letter/custom-avatar logic.
  const avatar = isNebula
    ? '<span class="agent-mgr-avatar"><i data-lucide="orbit" style="width:16px;height:16px"></i></span>'
    : `<span class="agent-mgr-avatar">${initial}</span>`;
  const orchBadge = isNebula
    ? `<span class="agent-mgr-orchestrator-badge">${esc(t('agents.badge.orchestrator'))}</span>`
    : '';
  return `<div class="agent-mgr-card" data-agent="${name}">
    ${avatar}
    <div class="agent-mgr-info">
      <div class="agent-mgr-name">${display}${badge}${orchBadge}</div>
      <div class="agent-mgr-desc">${desc}</div>
    </div>
    <span class="agent-mgr-model-tag" data-agent="${name}"></span>
  </div>`;
}

// ── Agents Canvas tab ──────────────────────────────────────

/** Open (or focus) the Agents Canvas tab and render the agent list. */
export function openAgents() {
  /* Button pressed state is owned by canvas.js registerCanvasPanelButton. */
  /* Canvas closed + tab already exists: expand the panel before activating
     (same invisible-click bug as Teams/Flows, 2026-08-29). */
  if (!isCanvasOpen()) openCanvas();
  if (hasTab('agents')) {
    setActiveTab('agents');
  } else {
    openTab('agents', t('activity.agents'), { type: 'agents', closable: true });
  }
  renderAgentManager();
}

/** Ensure the scroll container exists inside the Agents tab pane. */
function agentsContentEl() {
  const pane = getTabPane('agents');
  if (!pane) return null;
  let content = pane.querySelector('#agents-content');
  if (!content) {
    content = document.createElement('div');
    content.id = 'agents-content';
    pane.appendChild(content);
  }
  return content;
}

/** Render the agent list into the Agents Canvas tab, grouped by layer + scope. */
export function renderAgentManager() {
  const content = agentsContentEl();
  if (!content) return;
  content.innerHTML = `<div class="agent-mgr-loading">${t('agentManager.loading')}</div>`;

  fetchAgents().then(agents => {
    if (agents.length === 0) {
      content.innerHTML = `<div class="agent-mgr-empty">${t('agentManager.noAgents')}</div>`;
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

    // ⑧ Orchestrator section — Nebula pinned at top in its own group
    // (entity-icons-visual-spec §3.2). Nebula is extracted from the global
    // standalone list: it understands intent / dispatches / accepts, it is
    // not a delegatable executor like the standalone agents below.
    const nebula = global.find(a => a.name === 'Nebula');
    const standalone = global.filter(a => a.name !== 'Nebula');

    if (nebula) {
      html += `<div class="agent-mgr-group agent-mgr-group-orchestrator">`;
      html += `<div class="agent-mgr-group-header orchestrator"><i data-lucide="orbit" style="width:14px;height:14px"></i>${esc(t('agents.group.orchestrator'))}</div>`;
      html += renderAgentCard(nebula);
      html += `</div>`;
    }

    // Standalone section (was "Global Agents" — Nebula moved out above).
    // Empty group is not rendered (existing convention).
    if (standalone.length > 0) {
      html += `<div class="agent-mgr-group">`;
      html += `<div class="agent-mgr-group-header">${esc(t('agents.group.standalone'))}</div>`;
      html += standalone.map(renderAgentCard).join('');
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
    // Replace <i data-lucide> placeholders (orchestrator group header + Nebula avatar)
    createIconsIn(content);

    // Bind card clicks (single = preview, double = pinned — VS Code style)
    content.querySelectorAll('.agent-mgr-card').forEach(card => {
      card.addEventListener('click', () => openAgentDetail(card.dataset.agent));
      card.addEventListener('dblclick', () => openAgentDetail(card.dataset.agent, true));
    });

    // Async-populate model tags
    agents.forEach(a => populateModelTag(a.name));
  });
}

/** Fetch model info and inject a short tag into the card.
 *  Shows `方案: <displayName> · <model>` when the agent references a preset;
 *  a "默认方案" badge when resolving via the default preset. */
async function populateModelTag(name) {
  const [model, presetData] = await Promise.all([fetchAgentModel(name), presets.fetchPresets()]);
  if (!model) return;
  // preferred is the configured model — trust it over `current`
  // (current is only a reference from the backend resolution).
  const current = model.preferred || model.current || model.default || '';
  if (!current) return;
  const tag = document.querySelector(`.agent-mgr-model-tag[data-agent="${esc(name)}"]`);
  if (!tag) return;
  const presetName = model.preset || '';
  const preset = presetName ? (presetData?.presets || []).find(p => p.name === presetName) : null;
  const label = preset
    ? `${t('preset.pillPrefix')}${preset.name} · ${shortModel(current)}`
    : shortModel(current);
  const isFallback = model.preferred && current !== model.preferred;
  // resolvedFrom arrives with backend P2; fall back to legacy heuristic without it
  const showDefaultBadge = model.resolvedFrom
    ? (model.resolvedFrom === 'default-preset' || model.resolvedFrom === 'global')
    : !model.preferred;
  tag.innerHTML = `<span class="agent-mgr-model-pill${isFallback ? ' fallback' : ''}">${esc(label)}</span>${showDefaultBadge ? `<span class="agent-mgr-default-badge">${t('preset.defaultBadge')}</span>` : ''}`;
}

// ── Canvas detail tab ──────────────────────────────────────

/** Open a Canvas tab showing the agent detail page.
 *  Exported for plugins.js: the plugins page's subscription-map rows deep-
 *  link here for full per-agent editing (tools / prompt / flows). The detail
 *  tab is per-agent content, NOT the sealed standalone list entry. */
export async function openAgentDetail(name, pin = false) {
  const tabId = `agent:${name}`;
  openTab(tabId, name, { type: 'agent', pinned: pin });
  const pane = getTabPane(tabId);
  if (!pane) return;

  // Loading state
  pane.innerHTML = `<div class="agent-detail-loading">${t('agentManager.loading')}</div>`;

  // Fetch detail + model + presets in parallel
  const [detail, model, presetData] = await Promise.all([
    fetchAgentDetail(name),
    fetchAgentModel(name),
    presets.fetchPresets(),
  ]);

  renderAgentDetail(pane, name, detail, model, presetData);
}

/** Re-render the read-only model chain + current-run line after a preset change. */
function renderResolvedModel(pane, model) {
  const chainEl = pane.querySelector('#agent-detail-preset-chain');
  if (chainEl) chainEl.innerHTML = presets.resolvedChainHtml(model || {});
  const currentEl = pane.querySelector('#agent-detail-model-current');
  if (currentEl) {
    const current = model?.current || model?.preferred || model?.default || '';
    const isFallback = model?.preferred && current && current !== model.preferred;
    currentEl.innerHTML = current
      ? `${t('preset.current')}: <span class="agent-detail-current-ref">${esc(current)}</span>${isFallback ? `<span class="agent-detail-fallback-dot" title="fallback"></span>` : ''}`
      : '';
  }
}

function renderAgentDetail(pane, name, detail, model, presetData) {
  const displayName = detail?.displayName || detail?.name || name;
  const description = detail?.description || '';
  const extends_ = detail?.extends || '';
  const tools = detail?.tools || [];

  // System prompt
  const prompt = detail?.systemPrompt || '';

  // Preset (P4): agent references a named preset; chain is read-only.
  const presetName = model?.preset || '';
  const resolvedFrom = model?.resolvedFrom || '';
  const presetList = presetData?.presets || [];
  const defaultPreset = presetList.find(p => p.name === presetData?.defaultPreset);
  const showDefaultBadge = resolvedFrom
    ? (resolvedFrom === 'default-preset' || resolvedFrom === 'global')
    : !presetName;

  const allTools = (state.availableTools || []).map(t => typeof t === 'string' ? t : t.name);
  const isAll = tools.includes('*');

  // Split tools into system-fixed (read-only) and user-configurable (toggleable).
  const fixedTools = resolveFixedTools(detail);
  const configurableTools = allTools.filter(tname => !fixedTools.includes(tname));

  // #438: two-bucket display - base six (same on every card) + this class's
  // own specialty tools, labeled by owner class so cards are distinguishable
  // at a glance. Specialty may be empty -> single base group only.
  const { base: fixedBase, specialty: fixedSpecialty } = splitFixedTools(fixedTools);
  const fixedChip = tname =>
    `<span class="agent-detail-tool-check fixed" title="${esc(t('agent.toolsFixedTip'))}">${LOCK_ICON_SVG}${esc(tname)}</span>`;
  const specialtyLabelKey = fixedClassLabelKey(detail);
  const fixedGroupsHtml = [
    ...(fixedBase.length ? [{
      key: 'base',
      label: t('agent.toolsFixedLabel'),
      chips: fixedBase.map(fixedChip).join(''),
    }] : []),
    ...(fixedSpecialty.length ? [{
      key: 'specialty',
      label: t(specialtyLabelKey),
      chips: fixedSpecialty.map(fixedChip).join(''),
    }] : []),
  ];
  const fixedToolsHtml = fixedGroupsHtml.map(g => `
    <div class="agent-detail-tools-fixed-label${g.key === 'specialty' ? ' agent-detail-tools-specialty-label' : ''}" data-fixed-group="${g.key}">${esc(g.label)}</div>
    <div class="agent-detail-tools-grid" data-fixed-group-grid="${g.key}">${g.chips}</div>`).join('');

  const toolsHtml = `${fixedToolsHtml}
    ${fixedTools.length ? `<div class="agent-detail-tools-config-label">${t('agent.toolsConfigLabel')}</div>` : ''}
    <div class="agent-detail-tools-grid" id="agent-detail-tools-grid">
      ${configurableTools.map(tname => {
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
        <div class="agent-detail-label">Model${showDefaultBadge ? `<span class="agent-detail-default-badge">${t('preset.defaultBadge')}</span>` : ''}</div>
        <select class="agent-detail-preset-select" id="agent-detail-preset-select">
          <option value="">${t('preset.useDefault')}${defaultPreset ? `（${esc(defaultPreset.name)}）` : ''}</option>
          ${presetList.map(p => `<option value="${esc(p.name)}"${p.name === presetName ? ' selected' : ''}>${esc(p.name)}</option>`).join('')}
        </select>
        <div class="agent-detail-preset-chain" id="agent-detail-preset-chain"></div>
        <div class="agent-detail-model-current" id="agent-detail-model-current"></div>
      </div>

      <div class="agent-detail-section">
        <div class="agent-detail-label">${t('agentManager.tools')}</div>
        ${toolsHtml}
      </div>

      <div class="agent-detail-section">
        <div class="agent-detail-label">${t('agentManager.skills')}</div>
        <div class="agent-detail-sub-hint">已选 skill 的 name+description 注入此 agent 的 system prompt</div>
        <div class="agent-detail-skills-grid" id="agent-detail-skills-grid"><span class="agent-detail-chips-loading">Loading…</span></div>
      </div>

      <div class="agent-detail-section">
        <div class="agent-detail-label">${t('agentManager.flows')}</div>
        <div class="agent-detail-sub-hint">此 agent 可通过 FlowTrigger(flow=…) 触发的 flow</div>
        <div class="agent-detail-flows-grid" id="agent-detail-flows-grid"><span class="agent-detail-chips-loading">Loading…</span></div>
      </div>

      <div class="agent-detail-section" id="agent-detail-prompt-section">
        <div class="agent-detail-label-row">
          <span class="agent-detail-label">${t('agentManager.systemPrompt')}</span>
          <button class="agent-detail-prompt-toggle" id="agent-detail-prompt-toggle" title="${t('agentManager.viewSource')}">${CODE_ICON_SVG}</button>
        </div>
        <div class="agent-detail-prompt-render canvas-md-viewer" id="agent-detail-prompt-render"></div>
        <textarea class="agent-detail-prompt-edit" data-agent="${esc(name)}" style="display:none">${esc(prompt)}</textarea>
        <button class="agent-detail-save-btn" id="agent-detail-save-prompt" style="display:none">${t('agentManager.save')}</button>
      </div>
    </div>`;

  // Model section (P4): preset dropdown + read-only resolved chain.
  renderResolvedModel(pane, model);
  const presetSel = pane.querySelector('#agent-detail-preset-select');
  presetSel?.addEventListener('change', async () => {
    await presets.setAgentPreset(name, presetSel.value || null);
    const fresh = await fetchAgentModel(name);
    if (pane.isConnected) renderResolvedModel(pane, fresh);
    populateModelTag(name); // keep the sidebar card pill in sync
  });

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
    promptToggle.title = src ? t('agentManager.viewRendered') : t('agentManager.viewSource');
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
    saveBtn.textContent = t('agentManager.saved');
    setTimeout(() => { saveBtn.textContent = t('agentManager.save'); }, 1500);
  });

  // Bind tool toggle chips — configurable tools only; fixed tools are never sent.
  const toolsGrid = pane.querySelector('#agent-detail-tools-grid');
  if (toolsGrid) {
    const currentConfig = new Set(
      isAll ? configurableTools : tools.filter(tname => !fixedTools.includes(tname))
    );
    toolsGrid.querySelectorAll('.agent-detail-tool-check').forEach(el => {
      el.addEventListener('click', () => {
        const tool = el.dataset.tool;
        el.classList.toggle('checked');
        if (el.classList.contains('checked')) {
          currentConfig.add(tool);
        } else {
          currentConfig.delete(tool);
        }
        sendWs({ type: 'updateAgentTools', name, tools: [...currentConfig] });
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
      skillsGrid.innerHTML = `<span class="agent-detail-chips-empty">${t('agentManager.noSkills')}</span>`;
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
      flowsGrid.innerHTML = `<span class="agent-detail-chips-empty">${t('agentManager.noFlows')}</span>`;
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

/** Whether the Agents Canvas tab currently exists. */
export function isAgentsTabOpen() {
  return hasTab('agents');
}

// 2026-09-04 作者裁定：独立「智能体」面板入口移除，#agents-btn 由 plugins.js
// 接管（改挂「插件」页）。本模块保留为封存实现：
//   • openAgents / renderAgentManager / openAgentDetail 函数全部保留（隐藏
//     入口 ≠ 删除），openAgentDetail 被插件页订阅映射行深链复用；
//   • 不再 registerCanvasPanelButton——活动栏按钮的按下态同步归 plugins.js；
//   • canvas-tab-restore 仍监听 'agents'：旧会话持久化的智能体标签页刷新后
//     照常渲染（保留函数只藏入口，不悬挂已存在的标签页）。
window.addEventListener('canvas-tab-restore', (e) => {
  if (e.detail?.id === 'agents') {
    renderAgentManager();
  }
});
