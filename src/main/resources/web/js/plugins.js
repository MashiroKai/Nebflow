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
// 2026-09-05 插件面板体验批（零 scala，纯前端）：
//   • 手动刷新按钮移除（按钮元素 + 对应 i18n 键一并清除）——列表
//     实时性由轻量轮询承担。
//   • 实时自动同步：面板可见（document.visibilityState=visible）且激活
//     （canvas 开启 + plugins tab 为 active pane）时，每 6s GET /api/plugins；
//     registry 名称集 diff——新插件按排序位插入卡片（plugins-card-entering
//     短动画）；消失的插件回退整页重渲（renderPlugins 同一管线）；仅信任
//     状态变化时逐卡原地更新，零全列表重绘。智能体区块不参与轮询（轻量，
//     只轮插件注册表一个端点）。
//   • 启停原地状态切换：开关点击乐观翻转（switch/pill 即时反馈）→ POST
//     approve/revoke → 成功后后台拉 registry 逐卡原地收敛（UI ≡ backend，
//     无全列表重绘）；失败回滚乐观态 + toast 报错。全程无 page reload。
//   • 开关换用共享组件 js/toggle.js（nb-toggle：插件面板与设置页统一契约，
//     role=switch + 键盘可操作 + 全局 token 双主题自适应）。
// No new backend contract is invented anywhere; every endpoint above is
// pre-existing and verified in RestApiRoutes.scala (§B.3 面板审批清单) and
// PluginRegistry.scala (approvalManifest).

import { openTab, getTabPane, hasTab, setActiveTab, isCanvasOpen, openCanvas, registerCanvasPanelButton } from './canvas.js';
import { t } from './i18n.js';
import { escapeHtml } from './utils.js';
import { toggleHTML, bindToggle, setToggleState } from './toggle.js';
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
        ${toggleHTML({
          on,
          label: t('plugins.switchLabel'),
          title: switchTitle,
          attrs: `data-plugin-switch="${esc(manifest.name)}"`,
        })}
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
      </div>
      ${listHtml}`;

    bindPluginsEvents(content);
  }).catch(err => {
    if (!content.isConnected) return;
    content.innerHTML = `<div class="plugins-loading">${esc(t('plugins.loadFailed', { error: err?.message || err }))}</div>`;
  });
}

/** Optimistic in-place flip of one card's switch + pill (no re-render).
 *  Used on click; the same helper rolled back (= set the OPPOSITE state)
 *  when the POST fails. */
function flipCardState(card, enable) {
  const sw = card.querySelector('[data-plugin-switch]');
  if (sw) {
    setToggleState(sw, enable);
    sw.disabled = true; // in-flight guard — released by state convergence
  }
  const pill = card.querySelector('.plugins-state-pill');
  if (pill) {
    pill.textContent = t(enable ? 'plugins.stateOn' : 'plugins.stateOff');
    pill.classList.toggle('on', enable);
  }
}

/** Release the in-flight guard after a terminal state (converged or rolled
 *  back) so the switch is clickable again. */
function releaseSwitch(card) {
  const sw = card.querySelector('[data-plugin-switch]');
  if (sw) sw.disabled = false;
}

/** Enable/disable one plugin via the trust endpoints. Optimistic UI first
 *  (click already flipped the card), then converge from the registry —
 *  GET /api/plugins stays the single source of truth, applied IN PLACE
 *  (per-card state sync, never a full-list redraw). On failure: roll the
 *  optimistic flip back + toast. No page reload anywhere on this path. */
async function setPluginEnabled(name, enable, card) {
  const path = `/api/plugins/${encodeURIComponent(name)}/${enable ? 'approve' : 'revoke'}`;
  try {
    const resp = await api(path, { method: 'POST' });
    const body = await resp.json().catch(() => ({}));
    if (!resp.ok || body.error) throw new Error(body.error || `HTTP ${resp.status}`);
    const registry = await fetchPluginRegistry().catch(() => null);
    if (registry) applyRegistryStates(registry); // re-enables the switch too
    else releaseSwitch(card); // registry unreachable — keep optimistic state
  } catch (e) {
    flipCardState(card, !enable); // roll back the optimistic flip
    releaseSwitch(card);
    window.__showToast?.(String(e?.message || e), 'error');
  }
}

// ── Live registry sync (poller + post-toggle convergence) ────────────────

/** Registry order used by the full render: trusted plugins first (sorted by
 *  name), then rejected entries (sorted by name) — one flat card list. */
function registryOrder(registry) {
  const plugins = (registry.plugins || []).slice().sort((a, b) => a.name.localeCompare(b.name));
  const rejected = (registry.rejected || []).slice().sort((a, b) => a.name.localeCompare(b.name));
  return { plugins, rejected };
}

/** Update one card's DOM in place to match its manifest (trust state, pill,
 *  hints). Preserves node identity, listeners and the expanded skill block —
 *  this is how state changes avoid any list redraw. */
function applyPluginCardState(card, manifest) {
  const { on, changed, reason } = trustInfo(manifest);
  card.classList.toggle('on', on);
  card.classList.toggle('changed', changed);
  const sw = card.querySelector('[data-plugin-switch]');
  if (sw) {
    setToggleState(sw, on);
    sw.disabled = false;
    sw.title = on
      ? t('plugins.switchDisableTitle')
      : (changed ? t('plugins.switchReenableTitle') : t('plugins.switchEnableTitle'));
  }
  const pill = card.querySelector('.plugins-state-pill');
  if (pill) {
    pill.textContent = t(on ? 'plugins.stateOn' : 'plugins.stateOff');
    pill.classList.toggle('on', on);
  }
  // Amber re-approval hint (digest changed) and the dim off-hint.
  let hint = card.querySelector('.plugins-card-hint:not(.dim)');
  if (changed) {
    if (!hint) {
      hint = document.createElement('div');
      hint.className = 'plugins-card-hint';
      card.insertBefore(hint, card.querySelector('.plugins-skill-expand'));
    }
    hint.textContent = t('plugins.changedHint');
  } else hint?.remove();
  let dim = card.querySelector('.plugins-card-hint.dim');
  if (!on && !changed && reason) {
    if (!dim) {
      dim = document.createElement('div');
      dim.className = 'plugins-card-hint dim';
      card.insertBefore(dim, card.querySelector('.plugins-skill-expand'));
    }
    dim.textContent = t('plugins.offHint');
    dim.title = reason;
  } else dim?.remove();
}

/** Insert a card element at its registry position (trusted block sorted by
 *  name, then rejected block sorted by name). Returns the inserted element. */
function insertCardSorted(list, cardHtml, name, isRejected) {
  const tpl = document.createElement('template');
  tpl.innerHTML = cardHtml.trim();
  const el = tpl.content.firstElementChild;
  const groupOf = (c) => (c.classList.contains('rejected') ? 1 : 0);
  const group = isRejected ? 1 : 0;
  for (const existing of list.querySelectorAll('.plugins-card')) {
    const g = groupOf(existing) - group;
    if (g > 0 || (g === 0 && (existing.dataset.plugin || '').localeCompare(name) > 0)) {
      list.insertBefore(el, existing);
      return el;
    }
  }
  list.appendChild(el);
  return el;
}

/** Converge the visible list with a fresh registry — the ONLY sync entry:
 *  • trust-state drift → per-card in-place update (no redraw);
 *  • brand-new trusted plugins → inserted at sorted position with a short
 *    enter animation;
 *  • new rejected entries → inserted (informational cards);
 *  • disappeared plugins → full re-render fallback (renderPlugins, same
 *    pipeline as the initial load).
 *  Returns the list of newly inserted trusted card elements (may be []). */
function applyRegistryStates(registry) {
  const content = pluginsContentEl();
  const list = content?.querySelector('#plugins-section-plugins .plugins-card-list');
  if (!list) return [];

  const { plugins, rejected } = registryOrder(registry);
  const cardsByName = new Map(
    [...list.querySelectorAll('.plugins-card')].map(c => [c.getAttribute('data-plugin') || '', c]));

  // 1) State-only drift on cards already in the DOM → in-place update.
  for (const m of plugins) {
    const card = cardsByName.get(m.name);
    if (!card || card.classList.contains('rejected')) continue;
    const ti = trustInfo(m);
    const domOn = card.querySelector('[data-plugin-switch]')?.classList.contains('on') ?? false;
    if (domOn !== ti.on || card.classList.contains('changed') !== ti.changed) {
      applyPluginCardState(card, m);
    } else {
      releaseSwitch(card); // states agree — just make sure the switch is live
    }
  }

  // 2) Disappeared plugins (or rejected↔trusted transitions) → full re-render.
  const regNames = new Set(plugins.map(p => p.name));
  const rejectedNames = new Set(rejected.map(r => r.name));
  for (const [name, card] of cardsByName) {
    const wasRejected = card.classList.contains('rejected');
    const stillThere = wasRejected ? rejectedNames.has(name) : regNames.has(name);
    if (!stillThere) { renderPlugins(); return []; }
  }

  // 3) New cards → insert at sorted position; trusted ones get the enter
  //    animation (removed on animationend so repeats retrigger).
  const inserted = [];
  for (const m of plugins) {
    if (cardsByName.has(m.name)) continue;
    const el = insertCardSorted(list, renderPluginCard(m), m.name, false);
    bindCardEvents(el);
    el.classList.add('plugins-card-entering');
    el.addEventListener('animationend', () => el.classList.remove('plugins-card-entering'), { once: true });
    inserted.push(el);
  }
  for (const r of rejected) {
    if (cardsByName.has(r.name)) continue;
    const el = insertCardSorted(list, renderRejectedCard(r), r.name, true);
    el.classList.add('plugins-card-entering');
    el.addEventListener('animationend', () => el.classList.remove('plugins-card-entering'), { once: true });
  }
  return inserted;
}

// ── Poller: keep the list live while the panel is visible & active ───────
// Double gate (author ruling 2026-09-05): the tab must be the ACTIVE canvas
// pane AND the document must be visible. Interval 6s — light (one GET), and
// applyRegistryStates no-ops on identical registries, so a steady backend
// costs zero DOM churn.
const PLUGINS_POLL_MS = 6000;
let pluginsPollTimer = null;

function pluginsPanelActive() {
  if (document.visibilityState !== 'visible') return false;
  if (!isCanvasOpen()) return false;
  const pane = getTabPane('plugins');
  return !!(pane && pane.isConnected && pane.classList.contains('active'));
}

async function pluginsPollTick() {
  if (!pluginsPanelActive()) return;
  try {
    const registry = await fetchPluginRegistry();
    if (!pluginsPanelActive()) return; // tab closed mid-flight
    applyRegistryStates(registry);
  } catch { /* transient — next tick retries */ }
}

function startPluginsPolling() {
  if (pluginsPollTimer !== null) return;
  pluginsPollTimer = setInterval(pluginsPollTick, PLUGINS_POLL_MS);
  // Re-visible after sleep/switch → sync immediately instead of waiting a tick.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') pluginsPollTick();
  });
}

/** Bind one plugin card's interactions (switch + skill-expand toggles).
 *  Shared by the full render and the live-sync insert path. Idempotent via
 *  WeakSets (not expando properties — checkJs zero-new-errors discipline). */
const boundPluginSwitches = new WeakSet();
const boundCompToggles = new WeakSet();

function bindCardEvents(card) {
  const sw = card.querySelector('[data-plugin-switch]');
  if (sw && !boundPluginSwitches.has(sw)) {
    boundPluginSwitches.add(sw);
    sw.addEventListener('click', () => {
      if (sw.disabled) return;
      const name = sw.dataset.pluginSwitch;
      const enable = !sw.classList.contains('on');
      const cardEl = sw.closest('.plugins-card');
      flipCardState(cardEl, enable); // optimistic — converged/rolled back async
      setPluginEnabled(name, enable, cardEl);
    });
  }
  card.querySelectorAll('.plugins-comp-toggle').forEach(btn => {
    if (boundCompToggles.has(btn)) return;
    boundCompToggles.add(btn);
    btn.addEventListener('click', () => {
      const block = card.querySelector(`[data-expand-for="${btn.dataset.expand}"]`);
      if (!block) return;
      const show = block.hidden;
      block.hidden = !show;
      btn.setAttribute('aria-expanded', show ? 'true' : 'false');
      btn.classList.toggle('open', show);
    });
  });
}

/** Bind interactions: per-plugin switches (optimistic in-place toggling),
 *  skill-expand toggles, agent summary rows → per-agent detail tab.
 *  (The manual refresh button was removed 2026-09-05 — the poller keeps the
 *  list live; see startPluginsPolling.) */
function bindPluginsEvents(content) {
  content.querySelectorAll('.plugins-card').forEach(bindCardEvents);

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

// Live list sync while the panel is visible & active (see PLUGINS_POLL_MS).
startPluginsPolling();

window.addEventListener('canvas-tab-restore', (e) => {
  const ce = /** @type {CustomEvent} */ (e);
  if (ce.detail?.id === 'plugins') {
    renderPlugins();
  }
});
