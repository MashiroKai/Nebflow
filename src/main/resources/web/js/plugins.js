// plugins.js — Plugins page (Canvas tab, activity-bar entry «插件»/Plugins).
//
// 2026-09-04 作者裁定（插件面板重设计——统一插件系统 + 每插件一开关）：
//   • 统一插件系统：Skill 与 MCP 已统一为「插件」。页面只呈现插件卡片，
//     skill/MCP 仅作卡片内的内容构成标注语（含 N 个技能·可展开 preview /
//     MCP server 名+transport / 内建工具白名单 +N），不再是独立板块。
//   • 每插件**两个**操作件（2026-09-12 令 1 收口，设计件 R1 代价① / R9-A：
//     「两个动作在面板上必须视觉可分」）：
//       ┌ 内容开关（主开关，卡片右上，与 state pill 同行）
//       │    on  = POST /api/plugins/:name/approve （授予内容信任，按当前 digest）
//       │    off = POST /api/plugins/:name/revoke  （撤回内容信任——会停用在飞节点的
//       │                                           插件 MCP，**不是**「关闭插件」）
//       └ 派发开关（副控件，卡片底部独立一行 + 分隔线，带自己的标签）
//            on  = POST /api/plugins/:name/enable  （plugins.dispatch.<n>.authorEnabled=true）
//            off = POST /api/plugins/:name/disable （…authorEnabled=false）
//     **令 1 语义（作者 2026-09-12 14:14 原话「插件的开关，应该只影响任务分发器对
//     未来节点的派发，而不能影响目前的」）**：派发开关只写 `plugins.dispatch`
//     （作者意图层，durable）⇒ 关闭只挡住**未来派发**，已在飞/已派发节点零影响
//     （引擎侧闸 B/C/E/D 只判内容面）。
//     **两动作的分家口径（勿混）**：文案分家（本文件 `plugins.switch*` = 内容面、
//     `plugins.dispatch*` = 派发面）；DOM 钩子分家（`data-plugin-switch` = 内容、
//     `data-plugin-dispatch` = 派发）——tests/sidebar-plugins.spec.mjs 与
//     tests/plugins-panel-{redesign,autosync}.spec.mjs 的 approve/revoke 契约
//     挂在 `data-plugin-switch` 上，不得挪用。
//     **未受信插件上零静默空操作**：内容未审批时写 authorEnabled 无论如何都不会
//     生效（`PluginDispatchPolicy.effective = trusted ∧ (authorEnabled ∨ transition)`）
//     ⇒ 派发开关渲染为 disabled + 一行**可行动**注记（「先审批内容」），而不是
//     允许点击后回弹。临时派发授权（transition）生效期间同理（它的有效值由授权层
//     拥有，作者开关写下去也不改变有效值）。
//     目录可见性（GET /plugins/catalog）与分发器目录同源：内容未受信 or 派发被关
//     的包都不进目录行（后者另出一行点名注记）。数据源 GET /api/plugins 全量注册表
//     （manifest.dispatch = 派发面状态）。
//   • 智能体区块收缩为摘要行：名称/描述/preset 现状，点击进既有 agent
//     详情编辑（openAgentDetail 深链复用）；订阅 chips（PUT skills 写回）
//     与平铺 config row 移除——插件不再逐 agent 配置，每个插件两个开关
//     （内容 / 派发，见上一条）。
//   • 独立 MCP（state.mcpServers）展示从本页移除；设置页入口亦随 0905
//     设置清理批移除（MCP 概念由本插件系统全面取代）——前端已不消费
//     state.mcpServers / mcpServersUpdate，底层 MCP 机制保留。
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
//     （2026-09-12 令 1 收口：两条动作各走各的端点——内容开关 approve/revoke、
//     派发开关 enable/disable，乐观翻转 + registry 原地收敛同一条管线。）
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

/** Classify a manifest's CONTENT-trust state (drives the card's primary
 *  switch + the state pill — the `approve`/`revoke` action).
 *  • trusted → switch on.
 *  • untrusted + digest-changed reason → off + 「内容已变更」 hint
 *  • untrusted otherwise（never approved / 其他）→ off + 常规提示。
 *
 *  TODO(plugin-protocol 线吸收): reason 文案匹配是 legacy 兜底——建议后端在
 *  approvalManifest 的 trust 块增加结构化 `reasonCode` 字段（建议枚举
 *  'digest_changed' | 'never_approved' | 'other'），前端优先消费 reasonCode、
 *  仅在缺失时回落到文案匹配。届时后端 reason 文案可自由改写/本地化，
 *  不再是前端展示分类的单一事实。（checkjs-gate-fix 批 2026-09-05 登记） */
function contentState(manifest) {
  const trust = manifest.trust || { status: 'untrusted', reason: '' };
  if (trust.status === 'trusted') return { trusted: true, changed: false, reason: '' };
  const reason = String(trust.reason || '');
  // Structured code wins when the backend provides it (future reasonCode).
  const code = String(trust.reasonCode || '').toLowerCase().trim();
  if (code) {
    if (code === 'digest_changed' || code === 'changed') return { trusted: false, changed: true, reason };
    return { trusted: false, changed: false, reason }; // unknown code → generic hint
  }
  // Legacy fallback: loose phrase matching over the backend's English copy.
  // i-flag + synonym stems + 中英双语关键词，文案微调不致翻转展示分类；
  // 无法识别的文案按「未变更」处理（保守默认，与既有行为一致）。
  const neverApproved = /never\s+approved|unapproved|no\s+(?:prior\s+)?approval|未(?:曾|经)?(?:批准|审核)|尚未批准/i.test(reason);
  const digestChanged = /digest|changed|modified|mismatch|hash|re-?approv|内容(?:已)?(?:变更|修改)|重新批准/i.test(reason);
  const changed = neverApproved ? false : digestChanged;
  return { trusted: false, changed, reason };
}

/** Classify a manifest's DISPATCH-permission state (令 1 派发面,
 *  `manifest.dispatch`; drives the secondary control at the card's foot).
 *
 *  The switch writes exactly one thing — `dispatch.authorEnabled`（作者意图层）——
 *  so it is ENABLED only when that write is the thing that decides the effective
 *  value. Whenever it is not, the control is rendered `disabled` + an actionable
 *  note (`blocked`) instead of accepting a click that the backend would echo as
 *  `ok:true` while the effective value never moved (设计件 R1 代价① / R9-A）：
 *    • 内容未受信 ⇒ 有效值恒 false（`effective = trusted ∧ (authorEnabled ∨
 *      transition)`）⇒ 先审批内容（主开关）；
 *    • 临时派发授权（transition）生效中 ⇒ 有效值由授权层拥有 ⇒ 到期/清除后
 *      本开关自动按作者意图生效。
 *  标签/提示文案与内容面完全分家（`plugins.dispatch*`），保证两动作可辨。 */
function dispatchState(manifest, trusted) {
  const d = manifest.dispatch || {};
  // 兼容默认（零迁移）：无 dispatch 记录 ⇒ 跟随内容信任面 = true。
  const authorEnabled = typeof d.authorEnabled === 'boolean' ? d.authorEnabled : true;
  const transitionActive = d.transitionActive === true;
  if (!trusted) {
    return { on: false, blocked: 'untrusted',
      title: t('plugins.dispatchBlockedUntrusted'), note: t('plugins.dispatchBlockedUntrusted') };
  }
  if (transitionActive) {
    return { on: d.enabled !== false, blocked: 'transition',
      title: t('plugins.dispatchBlockedTransition'), note: t('plugins.dispatchBlockedTransition') };
  }
  return { on: authorEnabled, blocked: '',
    title: authorEnabled ? t('plugins.dispatchOnTitle') : t('plugins.dispatchOffTitle'),
    note: authorEnabled ? '' : t('plugins.dispatchOffNote') };
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
 *  + the TWO controls（内容开关 approve/revoke；派发开关 enable/disable）。
 *  `[data-plugin-switch]` = 内容面（既有契约钩子，勿挪）；`[data-plugin-dispatch]`
 *  = 派发面（令 1 新增）。 */
function renderPluginCard(manifest) {
  const { trusted, changed, reason } = contentState(manifest);
  const disp = dispatchState(manifest, trusted);
  const skills = Array.isArray(manifest.skills) ? manifest.skills : [];
  const servers = Array.isArray(manifest.mcpServers) ? manifest.mcpServers : [];
  const tools = Array.isArray(manifest.toolsExtension) ? manifest.toolsExtension : [];
  // Raw name (no pre-escaping): HTML attribute interpolation escapes at the
  // template site (data-expand/data-expand-for), and the event handler
  // CSS.escape()s the dataset value before the attribute-selector lookup.
  // The old esc()-here + esc()-at-template double escape mangled names with
  // &/quotes and left the querySelector concatenation one refactor away
  // from a selector injection.
  const expandId = `skills-${manifest.name}`;

  const metaBits = [];
  if (manifest.version) metaBits.push(`v${manifest.version}`);
  if (manifest.author) metaBits.push(t('plugins.author', { author: manifest.author }));

  const switchTitle = trusted
    ? t('plugins.switchDisableTitle')
    : (changed ? t('plugins.switchReenableTitle') : t('plugins.switchEnableTitle'));

  return `<div class="plugins-card${trusted ? ' on' : ''}${changed ? ' changed' : ''}" data-plugin="${esc(manifest.name)}">
    <div class="plugins-card-head">
      <div class="plugins-card-id">
        <span class="plugins-card-name">${esc(manifest.name)}</span>
        ${metaBits.length ? `<span class="plugins-card-meta">${esc(metaBits.join(' · '))}</span>` : ''}
      </div>
      <div class="plugins-card-state">
        <span class="plugins-state-pill${trusted ? ' on' : ''}">${esc(trusted ? t('plugins.stateOn') : t('plugins.stateOff'))}</span>
        ${toggleHTML({
          on: trusted,
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
    <div class="plugins-card-dispatch">
      <span class="plugins-dispatch-label">${esc(t('plugins.dispatchLabel'))}</span>
      ${toggleHTML({
        on: disp.on,
        disabled: !!disp.blocked,
        label: t('plugins.dispatchLabel'),
        title: disp.title,
        attrs: `data-plugin-dispatch="${esc(manifest.name)}"`
          + (disp.blocked ? ` data-dispatch-blocked="${esc(disp.blocked)}"` : ''),
      })}
    </div>
    <div class="plugins-dispatch-note"${disp.note ? '' : ' hidden'}>${esc(disp.note)}</div>
    ${changed ? `<div class="plugins-card-hint">${esc(t('plugins.changedHint'))}</div>` : ''}
    ${(!trusted && !changed && reason) ? `<div class="plugins-card-hint dim" title="${esc(reason)}">${esc(t('plugins.offHint'))}</div>` : ''}
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

/** Optimistic in-place flip of one card's CONTENT switch + pill (no re-render).
 *  Used on click; the same helper rolled back (= set the OPPOSITE state)
 *  when the POST fails. */
function flipCardState(card, trusted) {
  const sw = card.querySelector('[data-plugin-switch]');
  if (sw) {
    setToggleState(sw, trusted);
    sw.disabled = true; // in-flight guard — released by state convergence
  }
  const pill = card.querySelector('.plugins-state-pill');
  if (pill) {
    pill.textContent = t(trusted ? 'plugins.stateOn' : 'plugins.stateOff');
    pill.classList.toggle('on', trusted);
  }
}

/** Optimistic in-place flip of one card's DISPATCH switch (no re-render) —
 *  the 派发面 twin of flipCardState; same in-flight latch discipline. */
function flipDispatchState(card, on) {
  const sw = card.querySelector('[data-plugin-dispatch]');
  if (sw) {
    setToggleState(sw, on);
    sw.disabled = true; // in-flight guard — released by state convergence
  }
}

/** Release the in-flight guards after a terminal state (converged or rolled
 *  back) so both switches are clickable again. The dispatch control is only
 *  released when it is NOT blocked — blocked means "a click could not move the
 *  effective value", which is carried as `data-dispatch-blocked` on the element
 *  (see dispatchState); a blocked control stays disabled by design (零静默空操作). */
function releaseSwitch(card) {
  const sw = card.querySelector('[data-plugin-switch]');
  if (sw) sw.disabled = false;
  const dsw = card.querySelector('[data-plugin-dispatch]');
  if (dsw && !dsw.hasAttribute('data-dispatch-blocked')) dsw.disabled = false;
}

/** Content-trust action (approve / revoke) — POST /api/plugins/:name/(approve|revoke).
 *  Optimistic UI first (click already flipped the switch), then converge from
 *  the registry — GET /api/plugins stays the single source of truth, applied
 *  IN PLACE (per-card state sync, never a full-list redraw). On failure: roll
 *  the optimistic flip back + toast the BACKEND's message (PluginRegistry.approve
 *  / .revoke return actionable errors such as "not found — nothing to approve"),
 *  so no user action can end in a silent no-op. No page reload on this path. */
async function setPluginTrust(name, approve, card) {
  const path = `/api/plugins/${encodeURIComponent(name)}/${approve ? 'approve' : 'revoke'}`;
  try {
    const resp = await api(path, { method: 'POST' });
    const body = await resp.json().catch(() => ({}));
    if (!resp.ok || body.error) throw new Error(body.error || `HTTP ${resp.status}`);
    const registry = await fetchPluginRegistry().catch(() => null);
    if (registry) applyRegistryStates(registry); // re-enables the switch too
    else releaseSwitch(card); // registry unreachable — keep optimistic state
  } catch (e) {
    flipCardState(card, !approve); // roll back the optimistic flip
    releaseSwitch(card);
    window.__showToast?.(String(e?.message || e), 'error');
  }
}

/** Dispatch-permission action (令 1 派发面) — POST /api/plugins/:name/(enable|disable).
 *  Separate endpoint, separate DOM hook, separate copy from the content action
 *  (设计件 R9-A：两个动作在面板上必须视觉可分). Only bound on cards where the
 *  control is interactive (see dispatchState) — the backend write would not move
 *  the effective value otherwise. */
async function setPluginDispatch(name, enable, card) {
  const path = `/api/plugins/${encodeURIComponent(name)}/${enable ? 'enable' : 'disable'}`;
  try {
    const resp = await api(path, { method: 'POST' });
    const body = await resp.json().catch(() => ({}));
    if (!resp.ok || body.error) throw new Error(body.error || `HTTP ${resp.status}`);
    const registry = await fetchPluginRegistry().catch(() => null);
    if (registry) applyRegistryStates(registry); // re-derives both controls
    else releaseSwitch(card); // registry unreachable — keep optimistic state
  } catch (e) {
    flipDispatchState(card, !enable); // roll back the optimistic flip
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

/** Update one card's DOM in place to match its manifest (content trust state,
 *  dispatch state, pill, hints, both switches). Preserves node identity,
 *  listeners and the expanded skill block — this is how state changes avoid
 *  any list redraw. */
function applyPluginCardState(card, manifest) {
  const { trusted, changed, reason } = contentState(manifest);
  const disp = dispatchState(manifest, trusted);
  card.classList.toggle('on', trusted);
  card.classList.toggle('changed', changed);
  const sw = card.querySelector('[data-plugin-switch]');
  if (sw) {
    setToggleState(sw, trusted);
    sw.disabled = false;
    sw.title = trusted
      ? t('plugins.switchDisableTitle')
      : (changed ? t('plugins.switchReenableTitle') : t('plugins.switchEnableTitle'));
  }
  // 派发面（令 1）：开关态 = 作者意图层；blocked 时 disabled + 注明原因。
  const dsw = card.querySelector('[data-plugin-dispatch]');
  if (dsw) {
    setToggleState(dsw, disp.on);
    dsw.disabled = !!disp.blocked;
    dsw.title = disp.title;
    if (disp.blocked) dsw.setAttribute('data-dispatch-blocked', disp.blocked);
    else dsw.removeAttribute('data-dispatch-blocked');
  }
  const dnote = card.querySelector('.plugins-dispatch-note');
  if (dnote) {
    dnote.hidden = !disp.note;
    dnote.textContent = disp.note;
  }
  const pill = card.querySelector('.plugins-state-pill');
  if (pill) {
    pill.textContent = t(trusted ? 'plugins.stateOn' : 'plugins.stateOff');
    pill.classList.toggle('on', trusted);
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
  if (!trusted && !changed && reason) {
    if (!dim) {
      dim = document.createElement('div');
      dim.className = 'plugins-card-hint dim';
      card.insertBefore(dim, card.querySelector('.plugins-skill-expand'));
    }
    dim.textContent = t('plugins.offHint');
    dim.title = reason;
  } else dim?.remove();
}

/** Derived per-card rendering signature (content + dispatch面). Convergence
 *  only touches a card whose signature drifted, so a steady backend still
 *  costs zero DOM writes. */
function cardSignature(manifest) {
  const { trusted, changed } = contentState(manifest);
  const disp = dispatchState(manifest, trusted);
  return `${trusted ? 1 : 0}|${changed ? 1 : 0}|${disp.on ? 1 : 0}|${disp.blocked}|${disp.note}`;
}

/** The same signature read back off the DOM (used to detect drift). */
function domCardSignature(card) {
  const sw = card.querySelector('[data-plugin-switch]');
  const dsw = card.querySelector('[data-plugin-dispatch]');
  const note = card.querySelector('.plugins-dispatch-note');
  return [
    sw?.classList.contains('on') ? 1 : 0,
    card.classList.contains('changed') ? 1 : 0,
    dsw?.classList.contains('on') ? 1 : 0,
    dsw?.getAttribute('data-dispatch-blocked') || '',
    note && !note.hidden ? note.textContent : '',
  ].join('|');
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
    if (domCardSignature(card) !== cardSignature(m)) {
      applyPluginCardState(card, m);
    } else {
      releaseSwitch(card); // states agree — just make sure the switches are live
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

/** Bind one plugin card's interactions (content switch + dispatch switch +
 *  skill-expand toggles). Shared by the full render and the live-sync insert
 *  path. Idempotent via WeakSets (not expando properties — checkJs zero-new-
 *  errors discipline). */
const boundPluginSwitches = new WeakSet();
const boundDispatchSwitches = new WeakSet();
const boundCompToggles = new WeakSet();

function bindCardEvents(card) {
  const sw = card.querySelector('[data-plugin-switch]');
  if (sw && !boundPluginSwitches.has(sw)) {
    boundPluginSwitches.add(sw);
    sw.addEventListener('click', () => {
      if (sw.disabled) return;
      const name = sw.dataset.pluginSwitch;
      const approve = !sw.classList.contains('on');
      const cardEl = sw.closest('.plugins-card');
      flipCardState(cardEl, approve); // optimistic — converged/rolled back async
      setPluginTrust(name, approve, cardEl);
    });
  }
  // 派发开关（令 1 第二动作）：只写 plugins.dispatch；blocked 时控制件自带
  // disabled，故此处点击不可达（零静默空操作）。
  const dsw = card.querySelector('[data-plugin-dispatch]');
  if (dsw && !boundDispatchSwitches.has(dsw)) {
    boundDispatchSwitches.add(dsw);
    dsw.addEventListener('click', () => {
      if (dsw.disabled) return;
      const name = dsw.dataset.pluginDispatch;
      const enable = !dsw.classList.contains('on');
      const cardEl = dsw.closest('.plugins-card');
      flipDispatchState(cardEl, enable); // optimistic — converged/rolled back async
      setPluginDispatch(name, enable, cardEl);
    });
  }
  card.querySelectorAll('.plugins-comp-toggle').forEach(btn => {
    if (boundCompToggles.has(btn)) return;
    boundCompToggles.add(btn);
    btn.addEventListener('click', () => {
      const block = card.querySelector(`[data-expand-for="${CSS.escape(btn.dataset.expand)}"]`);
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

  // Agent summary row → per-agent detail tab (summary / preset / system
  // prompt — the tools/skills/flows capability sections were retired
  // 2026-09-06 with the tool-face batch).
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

window.addEventListener('canvas-tab-restore', (/** @type {CustomEvent} */ e) => {
  // canvas.js dispatches this as a CustomEvent with { id, type } detail.
  if (e.detail?.id === 'plugins') {
    renderPlugins();
  }
});
