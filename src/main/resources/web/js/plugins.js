// plugins.js — Plugins page (Canvas tab, activity-bar entry «插件»/Plugins).
//
// 2026-09-04 作者裁定（方案 A×2）：
//   • 件 A — Team/Flow 旧入口隐藏封存（见 activityBar.js SIDEBAR_LEGACY_ENTRIES）；
//   • 件 B — 独立「智能体」面板入口移除，#agents-btn 改挂本「插件」页
//     （作者原话：智能体现在没什么好配置，跟插件入口融合）。
//
// Page contents (scope per task, delivered over the verified API surface):
//   1. Plugin catalog — skills (GET /api/skills: name + description) with
//      subscriber agents computed client-side from GET /api/agents +
//      per-agent GET /api/agents/:name (skills array), and MCP servers
//      (live enabled state from state.mcpServers via WS serverConfig /
//      mcpServersUpdate, command detail from GET /api/mcp).
//   2. Agent subscription map — per agent, the skills it can use; chips
//      toggle and persist via PUT /api/agents/:name {skills:[…]} (the exact
//      write path agentManager.js already ships — setAgentSkillsFlows).
//   3. Agent configuration block — preset select + resolved model pill,
//      migrated 1:1 from the sealed agents panel (presets.setAgentPreset).
//      Row name deep-links to the per-agent detail tab (openAgentDetail)
//      for the full editor (tools / prompt / flows).
// No new backend contract is invented anywhere; every endpoint above is
// pre-existing and verified in RestApiRoutes.scala / WebSocketRoutes.scala.

import state from './state.js';
import { openTab, getTabPane, hasTab, setActiveTab, isCanvasOpen, openCanvas, registerCanvasPanelButton } from './canvas.js';
import { t } from './i18n.js';
import { createIconsIn, escapeHtml } from './utils.js';
import * as presets from './presets.js';
import {
  fetchAgents, fetchAgentDetail, fetchAgentModel, fetchSkills,
  setAgentSkillsFlows, openAgentDetail,
} from './agentManager.js';

// ── Helpers ────────────────────────────────────────────────
function esc(s) { return escapeHtml(s); }

/** Short display ref for a model chain (last path segment). */
function shortModel(ref) {
  if (!ref) return '';
  const idx = ref.lastIndexOf('/');
  return idx >= 0 ? ref.slice(idx + 1) : ref;
}

/** One-shot MCP config detail (id → {command?,…}) from GET /api/mcp.
 *  Best-effort enrichment only — the live list source is state.mcpServers. */
async function fetchMcpConfigDetail() {
  try {
    const tok = localStorage.getItem('nebflow_token') || '';
    const resp = await fetch('/api/mcp', tok ? { headers: { Authorization: `Bearer ${tok}` } } : {});
    if (!resp.ok) return {};
    const data = await resp.json();
    return data.mcpServers || {};
  } catch (e) { return {}; }
}

// ── Data assembly ──────────────────────────────────────────
/**
 * Load everything the page needs in one pass.
 * @returns {Promise<{skills: Array, subscribers: Map<string, string[]>, mcpServers: Array, agents: Array, details: Map<string, object>}>}
 */
async function loadPluginsData() {
  const [skills, agents] = await Promise.all([fetchSkills(), fetchAgents()]);

  // Per-agent details in parallel → skill → subscriber names.
  const details = new Map();
  await Promise.all(agents.map(async a => {
    const d = await fetchAgentDetail(a.name);
    if (d) details.set(a.name, d);
  }));
  const subscribers = new Map(skills.map(s => [s.name, []]));
  for (const [name, d] of details) {
    for (const sk of (d.skills || [])) {
      if (subscribers.has(sk)) subscribers.get(sk).push(name);
    }
  }

  // MCP: live id+enabled pairs from the WS-fed state, enriched with config
  // detail (command/args) from the REST endpoint when available.
  const detailMap = await fetchMcpConfigDetail();
  const mcpServers = (state.mcpServers || []).map(s => ({
    id: s.id,
    enabled: s.enabled !== false,
    config: detailMap[s.id] || null,
  }));

  return { skills, subscribers, mcpServers, agents, details };
}

// ── Rendering ──────────────────────────────────────────────

/** Open (or focus) the Plugins Canvas tab and render the page. */
export function openPlugins() {
  /* Button pressed state is owned by canvas.js registerCanvasPanelButton. */
  /* Canvas closed + tab already exists: expand the panel before activating
     (same invisible-click bug as Teams/Flows/Agents, 2026-08-29). */
  if (!isCanvasOpen()) openCanvas();
  if (hasTab('plugins')) {
    setActiveTab('plugins');
  } else {
    openTab('plugins', t('activity.plugins'), { type: 'plugins', closable: true });
  }
  renderPlugins();
}

/** Ensure the scroll container exists inside the Plugins tab pane. */
function pluginsContentEl() {
  const pane = getTabPane('plugins');
  if (!pane) return null;
  let content = pane.querySelector('#plugins-content');
  if (!content) {
    content = document.createElement('div');
    content.id = 'plugins-content';
    pane.appendChild(content);
  }
  return content;
}

/** Skill catalog card: name + description + subscriber agent chips. */
function renderSkillCard(skill, subscribers) {
  const subs = subscribers || [];
  const subsHtml = subs.length
    ? subs.map(a => `<span class="plugins-subscriber-chip" data-subscriber="${esc(a)}">${esc(a)}</span>`).join('')
    : `<span class="plugins-none">${esc(t('plugins.none'))}</span>`;
  return `<div class="plugins-skill-card" data-skill="${esc(skill.name)}">
    <div class="plugins-skill-head">
      <span class="plugins-skill-name">${esc(skill.name)}</span>
      <span class="plugins-skill-sub-label">${esc(t('plugins.subscribers'))}</span>
    </div>
    ${skill.description ? `<div class="plugins-skill-desc">${esc(skill.description)}</div>` : ''}
    <div class="plugins-subscriber-row">${subsHtml}</div>
  </div>`;
}

/** MCP server card: id + enabled badge + command summary. */
function renderMcpCard(server) {
  const cfg = server.config || {};
  const cmdBits = [cfg.command, ...(Array.isArray(cfg.args) ? cfg.args : [])].filter(Boolean);
  const cmdHtml = cmdBits.length
    ? `<span class="plugins-mcp-cmd" title="${esc(cmdBits.join(' '))}">${esc(cmdBits.join(' '))}</span>`
    : '';
  const on = server.enabled !== false;
  return `<div class="plugins-mcp-card${on ? '' : ' disabled'}" data-mcp="${esc(server.id)}">
    <span class="plugins-mcp-name">${esc(server.id)}</span>
    ${cmdHtml}
    <span class="plugins-mcp-state${on ? '' : ' off'}">${esc(on ? t('plugins.enabled') : t('plugins.disabled'))}</span>
  </div>`;
}

/** Subscription-map row: agent name + toggleable skill chips. */
function renderMapRow(agent, detail, skills) {
  const current = new Set(detail?.skills || []);
  const chips = skills.length
    ? skills.map(s => {
      const checked = current.has(s.name);
      return `<span class="plugins-sub-check${checked ? ' checked' : ''}" data-agent="${esc(agent.name)}" data-skill="${esc(s.name)}" title="${esc(s.description || s.name)}">${esc(s.name)}</span>`;
    }).join('')
    : `<span class="plugins-none">${esc(t('plugins.noSkills'))}</span>`;
  return `<div class="plugins-map-row" data-agent="${esc(agent.name)}">
    <span class="plugins-agent-name" data-detail-agent="${esc(agent.name)}" title="${esc(t('plugins.detail'))}">${esc(agent.displayName || agent.name)}</span>
    <div class="plugins-sub-grid">${chips}</div>
  </div>`;
}

/** Config row: preset select + resolved model pill (migrated from the
 *  sealed agents panel's detail view — same options, same write path). */
function renderConfigRow(agent, model, presetData) {
  const presetName = model?.preset || '';
  const presetList = presetData?.presets || [];
  const current = model?.preferred || model?.current || model?.default || '';
  return `<div class="plugins-config-row" data-agent="${esc(agent.name)}">
    <span class="plugins-agent-name" data-detail-agent="${esc(agent.name)}" title="${esc(t('plugins.detail'))}">${esc(agent.displayName || agent.name)}</span>
    <select class="plugins-preset-select" data-agent="${esc(agent.name)}">
      <option value="">${esc(t('preset.useDefault'))}</option>
      ${presetList.map(p => `<option value="${esc(p.name)}"${p.name === presetName ? ' selected' : ''}>${esc(p.name)}</option>`).join('')}
    </select>
    <span class="plugins-model-tag" data-agent="${esc(agent.name)}">${current ? esc(shortModel(current)) : ''}</span>
  </div>`;
}

/** Render the whole page into the Plugins tab pane. */
export function renderPlugins() {
  const content = pluginsContentEl();
  if (!content) return;
  content.innerHTML = `<div class="plugins-loading">${esc(t('plugins.loading'))}</div>`;

  Promise.all([loadPluginsData(), presets.fetchPresets()]).then(([{ skills, subscribers, mcpServers, agents, details }, presetData]) => {
    if (!content.isConnected) return; // tab closed while fetching

    const models = new Map(); // filled async below (per-agent model fetch)

    const catalogHtml = `
      <div class="plugins-section" id="plugins-section-catalog">
        <div class="plugins-section-title">${esc(t('plugins.catalog'))}</div>
        <div class="plugins-subsection">
          <div class="plugins-subsection-title">${esc(t('plugins.skills'))}</div>
          <div class="plugins-skill-list">${skills.length
            ? skills.map(s => renderSkillCard(s, subscribers.get(s.name))).join('')
            : `<span class="plugins-none">${esc(t('plugins.noSkills'))}</span>`}</div>
        </div>
        <div class="plugins-subsection">
          <div class="plugins-subsection-title">${esc(t('plugins.mcp'))}</div>
          <div class="plugins-mcp-list">${mcpServers.length
            ? mcpServers.map(renderMcpCard).join('')
            : `<span class="plugins-none">${esc(t('plugins.noMcp'))}</span>`}</div>
        </div>
      </div>`;

    const mapHtml = `
      <div class="plugins-section" id="plugins-section-map">
        <div class="plugins-section-title">${esc(t('plugins.map'))}</div>
        <div class="plugins-section-hint">${esc(t('plugins.mapHint'))}</div>
        <div class="plugins-map-list">${agents.length
          ? agents.map(a => renderMapRow(a, details.get(a.name), skills)).join('')
          : `<span class="plugins-none">${esc(t('plugins.noAgents'))}</span>`}</div>
      </div>`;

    const configHtml = `
      <div class="plugins-section" id="plugins-section-config">
        <div class="plugins-section-title">${esc(t('plugins.config'))}</div>
        <div class="plugins-section-hint">${esc(t('plugins.configHint'))}</div>
        <div class="plugins-config-list">${agents.length
          ? agents.map(a => renderConfigRow(a, null, presetData)).join('')
          : ''}</div>
      </div>`;

    content.innerHTML = `
      <div class="plugins-topbar">
        <span class="plugins-title">${esc(t('activity.plugins'))}</span>
        <button class="plugins-refresh-btn" id="plugins-refresh" title="${esc(t('plugins.refresh'))}">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
        </button>
      </div>
      ${catalogHtml}${mapHtml}${configHtml}`;

    bindPluginsEvents(content, agents, details, skills);

    // Model pills: per-agent fetch, then fill (same pattern as the sealed
    // panel's populateModelTag, scoped to this pane).
    agents.forEach(a => {
      fetchAgentModel(a.name).then(model => {
        models.set(a.name, model);
        if (!content.isConnected) return;
        const tag = content.querySelector(`.plugins-model-tag[data-agent="${esc(a.name)}"]`);
        const current = model?.preferred || model?.current || model?.default || '';
        if (tag && current) tag.textContent = shortModel(current);
        const sel = content.querySelector(`.plugins-preset-select[data-agent="${esc(a.name)}"]`);
        if (sel && model?.preset) sel.value = model.preset;
      });
    });
  });
}

/** Bind interactions: refresh, subscription toggles, preset selects,
 *  deep-links to the per-agent detail tab. */
function bindPluginsEvents(content, agents, details, skills) {
  content.querySelector('#plugins-refresh')?.addEventListener('click', () => renderPlugins());

  // Subscription chips — toggle + persist via the proven PUT write path.
  content.querySelectorAll('.plugins-sub-check').forEach(chip => {
    chip.addEventListener('click', () => {
      const agent = chip.dataset.agent;
      const skill = chip.dataset.skill;
      chip.classList.toggle('checked');
      const current = new Set(details.get(agent)?.skills || []);
      if (chip.classList.contains('checked')) current.add(skill);
      else current.delete(skill);
      // Keep the cached detail in sync so repeated toggles don't drift.
      const d = details.get(agent);
      if (d) d.skills = [...current];
      setAgentSkillsFlows(agent, 'skills', [...current]);
    });
  });

  // Preset selects — same write path as the sealed panel's detail view.
  content.querySelectorAll('.plugins-preset-select').forEach(sel => {
    sel.addEventListener('change', async () => {
      const agent = sel.dataset.agent;
      await presets.setAgentPreset(agent, sel.value || null);
      const model = await fetchAgentModel(agent);
      const tag = content.querySelector(`.plugins-model-tag[data-agent="${esc(agent)}"]`);
      const current = model?.preferred || model?.current || model?.default || '';
      if (tag) tag.textContent = current ? shortModel(current) : '';
    });
  });

  // Agent name → per-agent detail tab (full editor: tools / prompt / flows).
  content.querySelectorAll('[data-detail-agent]').forEach(el => {
    el.addEventListener('click', () => openAgentDetail(el.dataset.detailAgent));
  });
}

// ── Wiring ─────────────────────────────────────────────────
// Activity Bar entry (2026-09-04): #agents-btn — the standalone agents
// panel's old toggle — now drives this Plugins page (4-state pressed
// machine in canvas.js unchanged).
registerCanvasPanelButton('plugins', 'agents-btn', () => openPlugins());

window.addEventListener('canvas-tab-restore', (e) => {
  if (e.detail?.id === 'plugins') {
    renderPlugins();
  }
});
