// plugins.js — Plugins page (Canvas tab, activity-bar entry «插件»/Plugins).
//
// 2026-09-04 作者裁定（插件面板重设计——统一插件系统 + 每插件一开关）：
//   • 统一插件系统：Skill 与 MCP 已统一为「插件」。页面只呈现插件卡片，
//     skill/MCP 仅作卡片内的内容构成标注语（含 N 个技能·可展开 preview /
//     MCP server 名+transport / 内建工具白名单 +N），不再是独立板块。
//   • 每插件一个启停开关（页面唯一操作件）：
//       on  = POST /api/plugins/:name/approve （trust 表记录当前目录 digest）
//       off = POST /api/plugins/:name/revoke  （删 trust 条目，回落 untrusted）
//     enabled ≡ trusted：不做独立 enabled 字段——trust digest 门底层语义
//     （内容变更即回落待审）不删，开关即目录可见性（GET /plugins/catalog
//     只列 trusted，分发器目录同源）。数据源 GET /api/plugins 全量注册表。
//   • 智能体区块收缩为摘要行：名称/描述/preset 现状，点击进既有 agent
//     详情编辑（openAgentDetail 深链复用）；订阅 chips（PUT skills 写回）
//     与平铺 config row 移除——插件不再逐 agent 配置，每个插件一个开关。
//   • 独立 MCP（state.mcpServers）展示从本页移除；其配置管理入口在设置页
//     （renderSettings 的 settings.mcpServers 区，sidebar.js）——只移展示，
//     不动底层 MCP 机制。
// No new backend contract is invented anywhere; every endpoint above is
// pre-existing and verified in RestApiRoutes.scala (§B.3 面板审批清单) and
// PluginRegistry.scala (approvalManifest).

import { openTab, getTabPane, hasTab, setActiveTab, isCanvasOpen, openCanvas, registerCanvasPanelButton } from './canvas.js';
import { t } from './i18n.js';
import { escapeHtml } from './utils.js';
import { fetchAgents, fetchAgentModel, openAgentDetail } from './agentManager.js';

// ── Helpers ────────────────────────────────────────────────
function esc(s) { return escapeHtml(s); }

/** Short display ref for a model chain (last path segment). */
function shortModel(ref) {
  if (!ref) return '';
  const idx = ref.lastIndexOf('/');
  return idx >= 0 ? ref.slice(idx + 1) : ref;
}

/** Authorized JSON fetch (same token pattern as agentManager.js). */
async function api(path, opts = {}) {
  const tok = localStorage.getItem('nebflow_token') || '';
  if (tok) opts.headers = { ...(opts.headers || {}), Authorization: `Bearer ${tok}` };
  return fetch(path, opts);
}

/** GET /api/plugins — 注册表全量（approvalManifest + 拒载原因）。
 *  Response: { plugins: [manifest…], rejected: [{name, reason}] } */
async function fetchPluginRegistry() {
  const resp = await api('/api/plugins');
  if (!resp.ok) throw new Error(`GET /api/plugins ${resp.status}`);
  return resp.json();
}

/** Classify a manifest's trust state for display.
 *  • trusted → switch on.
 *  • untrusted + digest-changed reason → off + 「内容已变更」 hint
 *    （reason 文案为后端单一事实，前端按其稳定短语分类，仅作展示语义）。
 *  • untrusted otherwise（never approved / 其他）→ off + 常规提示。 */
function trustInfo(manifest) {
  const trust = manifest.trust || { status: 'untrusted', reason: '' };
  if (trust.status === 'trusted') return { on: true, changed: false, reason: '' };
  const reason = String(trust.reason || '');
  const changed = /never approved/i.test(reason)
    ? false
    : /digest|changed|re-approval/i.test(reason);
  return { on: false, changed, reason };
}

// ── Data assembly ──────────────────────────────────────────
/**
 * Load everything the page needs in one pass.
 * @returns {Promise<{registry: object, agents: Array}>}
 */
async function loadPluginsData() {
  const [registry, agents] = await Promise.all([fetchPluginRegistry(), fetchAgents()]);
  return { registry, agents };
}

/** Per-agent model snapshot for the summary row's preset/model pills. */
async function loadAgentSummaries(agents) {
  return Promise.all(agents.map(async a => ({
    ...a,
    model: await fetchAgentModel(a.name).catch(() => null),
  })));
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

/** Composition annotation: skill count (expandable → previews). */
function compositionSkills(skills, id) {
  if (!skills.length) return '';
  return `<button type="button" class="plugins-comp plugins-comp-toggle" data-expand="${esc(id)}"
    aria-expanded="false" title="${esc(t('plugins.skillPreviewTitle'))}">${esc(t('plugins.compositionSkills', { n: skills.length }))}</button>`;
}

/** Expandable per-skill blocks (id + description + preview excerpt). */
function skillExpandHtml(id, skills) {
  if (!skills.length) return '';
  const items = skills.map(s => `
    <div class="plugins-skill-item">
      <div class="plugins-skill-item-head">
        <span class="plugins-skill-item-id">${esc(s.id)}</span>
        <span class="plugins-skill-item-desc">${esc(s.description || '')}</span>
      </div>
      ${s.preview ? `<pre class="plugins-skill-preview">${esc(s.preview)}</pre>` : ''}
    </div>`).join('');
  return `<div class="plugins-skill-expand" data-expand-for="${esc(id)}" hidden>${items}</div>`;
}

/** Composition annotation: MCP servers (name + transport, command/env in tooltip). */
function compositionMcp(servers) {
  if (!servers.length) return '';
  const label = servers.map(s => `${s.server} (${s.transport || 'none'})`).join('、');
  const tip = servers.map(s => {
    const bits = [s.transport || 'none'];
    if (s.command) bits.push([s.command, ...(s.args || [])].filter(Boolean).join(' '));
    if (s.url) bits.push(s.url);
    if (s.envKeys?.length) bits.push(`env: ${s.envKeys.join(', ')}`);
    return `${s.server}: ${bits.join(' · ')}`;
  }).join('\n');
  return `<span class="plugins-comp" title="${esc(tip)}">${esc(t('plugins.compositionMcp', { servers: label }))}</span>`;
}

/** Composition annotation: builtin tools whitelist extension count. */
function compositionTools(tools) {
  if (!tools.length) return '';
  return `<span class="plugins-comp" title="${esc(tools.join(', '))}">${esc(t('plugins.compositionTools', { n: tools.length }))}</span>`;
}

/** Plugin card: identity + description summary + composition annotations
 *  + the enable switch (the page's only control). */
function renderPluginCard(manifest) {
  const { on, changed, reason } = trustInfo(manifest);
  const skills = Array.isArray(manifest.skills) ? manifest.skills : [];
  const servers = Array.isArray(manifest.mcpServers) ? manifest.mcpServers : [];
  const tools = Array.isArray(manifest.toolsExtension) ? manifest.toolsExtension : [];
  const expandId = `skills-${esc(manifest.name)}`;

  const metaBits = [];
  if (manifest.version) metaBits.push(`v${manifest.version}`);
  if (manifest.author) metaBits.push(t('plugins.author', { author: manifest.author }));

  const switchTitle = on
    ? t('plugins.switchDisableTitle')
    : (changed ? t('plugins.switchReenableTitle') : t('plugins.switchEnableTitle'));

  return `<div class="plugins-card${on ? ' on' : ''}${changed ? ' changed' : ''}" data-plugin="${esc(manifest.name)}">
    <div class="plugins-card-head">
      <div class="plugins-card-id">
        <span class="plugins-card-name">${esc(manifest.name)}</span>
        ${metaBits.length ? `<span class="plugins-card-meta">${esc(metaBits.join(' · '))}</span>` : ''}
      </div>
      <div class="plugins-card-state">
        <span class="plugins-state-pill${on ? ' on' : ''}">${esc(on ? t('plugins.stateOn') : t('plugins.stateOff'))}</span>
        <button type="button" class="plugins-switch${on ? ' on' : ''}" role="switch"
          aria-checked="${on ? 'true' : 'false'}" aria-label="${esc(t('plugins.switchLabel'))}"
          data-plugin-switch="${esc(manifest.name)}" title="${esc(switchTitle)}">
          <span class="plugins-switch-knob"></span>
        </button>
      </div>
    </div>
    ${manifest.description ? `<div class="plugins-card-desc">${esc(manifest.description)}</div>` : ''}
    ${(skills.length || servers.length || tools.length)
      ? `<div class="plugins-card-composition">${compositionSkills(skills, expandId)}${compositionMcp(servers)}${compositionTools(tools)}</div>`
      : ''}
    ${changed ? `<div class="plugins-card-hint">${esc(t('plugins.changedHint'))}</div>` : ''}
    ${(!on && !changed && reason) ? `<div class="plugins-card-hint dim" title="${esc(reason)}">${esc(t('plugins.offHint'))}</div>` : ''}
    ${skillExpandHtml(expandId, skills)}
  </div>`;
}

/** Rejected (load-failed) plugin card — informational only, no switch. */
function renderRejectedCard(entry) {
  return `<div class="plugins-card rejected" data-plugin="${esc(entry.name)}">
    <div class="plugins-card-head">
      <div class="plugins-card-id">
        <span class="plugins-card-name">${esc(entry.name)}</span>
        <span class="plugins-rejected-pill">${esc(t('plugins.rejected'))}</span>
      </div>
    </div>
    <div class="plugins-card-desc">${esc(entry.reason || '')}</div>
  </div>`;
}

/** Agent summary row: name + description + preset/model 现状 — the whole row
 *  deep-links into the per-agent detail editor (openAgentDetail). */
function renderAgentRow(agent) {
  const model = agent.model || {};
  const preset = model.preset || '';
  const resolved = model.preferred || model.current || model.default || '';
  const meta = [
    preset ? esc(preset) : '',
    resolved ? esc(shortModel(resolved)) : '',
  ].filter(Boolean).map(p => `<span class="plugins-agent-meta-pill">${p}</span>`).join('');
  return `<div class="plugins-agent-row" data-detail-agent="${esc(agent.name)}" role="button" tabindex="0"
    title="${esc(t('plugins.detail'))}">
    <span class="plugins-agent-name">${esc(agent.displayName || agent.name)}</span>
    <span class="plugins-agent-desc">${esc(agent.description || '')}</span>
    <span class="plugins-agent-meta">${meta}</span>
    <svg class="plugins-agent-chevron" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
  </div>`;
}

/** Render the whole page into the Plugins tab pane. */
export function renderPlugins() {
  const content = pluginsContentEl();
  if (!content) return;
  content.innerHTML = `<div class="plugins-loading">${esc(t('plugins.loading'))}</div>`;

  Promise.all([loadPluginsData()]).then(async ([{ registry, agents }]) => {
    if (!content.isConnected) return; // tab closed while fetching

    const plugins = (registry.plugins || []).slice().sort((a, b) => a.name.localeCompare(b.name));
    const rejected = (registry.rejected || []).slice().sort((a, b) => a.name.localeCompare(b.name));
    const summaries = await loadAgentSummaries(agents);
    if (!content.isConnected) return;

    const listHtml = `
      <div class="plugins-section" id="plugins-section-plugins">
        <div class="plugins-section-title">${esc(t('plugins.list'))}</div>
        <div class="plugins-section-hint">${esc(t('plugins.listHint'))}</div>
        <div class="plugins-card-list">${(plugins.length || rejected.length)
          ? plugins.map(renderPluginCard).join('') + rejected.map(renderRejectedCard).join('')
          : `<span class="plugins-none">${esc(t('plugins.empty'))}</span>`}</div>
      </div>
      <div class="plugins-section" id="plugins-section-agents">
        <div class="plugins-section-title">${esc(t('plugins.agents'))}</div>
        <div class="plugins-section-hint">${esc(t('plugins.agentsHint'))}</div>
        <div class="plugins-agent-list">${summaries.length
          ? summaries.map(renderAgentRow).join('')
          : `<span class="plugins-none">${esc(t('plugins.noAgents'))}</span>`}</div>
      </div>`;

    content.innerHTML = `
      <div class="plugins-topbar">
        <span class="plugins-title">${esc(t('activity.plugins'))}</span>
        <button class="plugins-refresh-btn" id="plugins-refresh" title="${esc(t('plugins.refresh'))}">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
        </button>
      </div>
      ${listHtml}`;

    bindPluginsEvents(content);
  }).catch(err => {
    if (!content.isConnected) return;
    content.innerHTML = `<div class="plugins-loading">${esc(t('plugins.loadFailed', { error: err?.message || err }))}</div>`;
  });
}

/** Enable/disable one plugin via the trust endpoints, then re-render —
 *  the registry (GET /api/plugins) is the single source of truth for card
 *  state, so a refresh (not a local flip) keeps UI ≡ backend semantics. */
async function setPluginEnabled(name, enable) {
  const path = `/api/plugins/${encodeURIComponent(name)}/${enable ? 'approve' : 'revoke'}`;
  try {
    const resp = await api(path, { method: 'POST' });
    const body = await resp.json().catch(() => ({}));
    if (!resp.ok || body.error) throw new Error(body.error || `HTTP ${resp.status}`);
  } catch (e) {
    window.__showToast?.(String(e?.message || e), 'error');
  }
  renderPlugins(); // even after an error — re-sync with the registry
}

/** Bind interactions: refresh, per-plugin switches, skill-expand toggles,
 *  agent summary rows → per-agent detail tab. */
function bindPluginsEvents(content) {
  content.querySelector('#plugins-refresh')?.addEventListener('click', () => renderPlugins());

  // The one control on the page: per-plugin enable switch.
  content.querySelectorAll('[data-plugin-switch]').forEach(btn => {
    btn.addEventListener('click', () => {
      if (btn.disabled) return;
      btn.disabled = true;
      setPluginEnabled(btn.dataset.pluginSwitch, !btn.classList.contains('on'));
    });
  });

  // Composition annotation: expand/collapse skill previews.
  content.querySelectorAll('.plugins-comp-toggle').forEach(btn => {
    btn.addEventListener('click', () => {
      const block = content.querySelector(`[data-expand-for="${btn.dataset.expand}"]`);
      if (!block) return;
      const show = block.hidden;
      block.hidden = !show;
      btn.setAttribute('aria-expanded', show ? 'true' : 'false');
      btn.classList.toggle('open', show);
    });
  });

  // Agent summary row → per-agent detail tab (full editor: tools/prompt/flows).
  content.querySelectorAll('[data-detail-agent]').forEach(el => {
    el.addEventListener('click', () => openAgentDetail(el.dataset.detailAgent));
    el.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openAgentDetail(el.dataset.detailAgent); }
    });
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
