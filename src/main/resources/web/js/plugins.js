// plugins.js — Plugins page (Canvas tab, activity-bar entry «插件»/Plugins).
//
// 2026-09-13 作者裁定（插件「无审批」批——「装了就是信任」）：
//   ① 方案 = A「在位即信任 + 保留封禁」（引擎轨；`plugins.trust.*` 记录不再决定
//      装载、零迁移）；② 内容变更 = 不停（内容一变**不**抖掉在飞节点的插件 MCP）。
//   本文件是该批的**前端主交付**，消费两轨共用的**冻结契约 C6**：GET /api/plugins
//   每项含 `trusted` / `blocked`（新）/ `contentChanged`（新）。
//
//   • 统一插件系统（2026-09-04 重设计，形态不变）：Skill 与 MCP 统一为「插件」，
//     页面只呈现插件卡片，skill/MCP 仅作卡片内的内容构成标注语（含 N 个技能·
//     可展开 preview / MCP server 名+transport / 内建工具白名单 +N）。
//
//   • **卡片形态（C7，2026-09-13 令；**2026-09-14 面板收敛批改口径**）**：
//       ┌ 内容审批开关 `[data-plugin-switch]` **退场**——「在位即信任」下它恒 on，
//       │   留着只是噪声；且它的 off 会停掉在飞节点的插件 MCP（作者 09-12 踩过的坑）。
//       ├ 状态药丸（右上）绑 `blocked` / `contentChanged`（**不绑 `trusted`**）：
//       │   已封禁 > 内容已变更 > 已启用 三态（优先级见 pluginStatus）。
//       └ 派发开关 `[data-plugin-dispatch]` = **卡片右上、与状态药丸同排**的**唯一控件**
//           （`.plugins-card-state` 内，药丸右侧）；文案 = 「任务分发器可见性」
//           （`plugins.dispatchLabel`，2026-09-14 作者三裁之批一）。
//
//   • **封禁 / 解封 UI 全量退场（2026-09-14 作者三裁之批一）**：右上「更多」按钮、
//     其就地菜单行、封禁/解封按钮（`[data-plugin-more]` / `[data-plugin-menu]` /
//     `[data-plugin-block]`）及其动作函数一并删除——作者定方案 B 时**已知并接受**
//     「在飞止损只能走 API / CLI」的代价：POST /api/plugins/:name/revoke（语义 = 封禁，
//     路径名保留 ⇒ 零迁移）/ POST :name/unblock。🔴 **端点 / CLI / 引擎面零改动**
//     （闸 A–E、MCP ≤30s 停一律不动）；`plugins.revoked.<n>` 命名空间、`blocked` 契约
//     字段、`PluginBlockPolicy` 语义**原样保留**——面板仍显示「已封禁」药丸 + 禁用态
//     开关 + 可行动注记（指向 API / CLI），只是不再提供面板入口。
//
//   • **派发开关语义（2026-09-12 令 1，作者原话「插件的开关，应该只影响任务分发器对
//     未来节点的派发，而不能影响目前的」）**：只写 `plugins.dispatch`（作者意图层，
//     durable）⇒ 关闭只挡住**未来派发**，已在飞/已派发节点零影响。
//     有效值 = `trusted ∧ (authorEnabled ∨ transition)`；内容恒受信后 =
//     `authorEnabled ∨ transition` ⇒ 本开关仅在下面两种情形不可用（渲染 disabled +
//     一行**可行动**注记，而不是允许点击后回弹）：
//       (a) 该包**被封禁**（封禁 ⇒ 不进目录 + 闸拒）⇒ 解封走 API / CLI
//           （面板入口已随 2026-09-14 面板收敛批退场）；
//       (b) 临时派发授权（transition）生效中 ⇒ 有效值由授权层拥有。
//     目录可见性（GET /plugins/catalog）与分发器目录同源。
//
//   • **F1「零静默、可行动」纪律（本批延续）**：任何用户动作都必须产生可行动结果。
//     面板上仅存的动作（派发开关）走「乐观翻转 → 后端确认 → 回读注册表原地收敛」，
//     失败即回滚乐观态 + toast 报错，绝不让动作静默无效。（原封禁动作的落盘核对
//     用语 `plugins.actionNoEffect` 已随该动作一并退场——消费方与文案同时删除。）
//
//   • `/approve` 端点**兼容保留但 UI 主线不再调用**（审批语义已退场）。
//   • 智能体区块收缩为摘要行：名称/描述/preset 现状，点击进既有 agent
//     详情编辑（openAgentDetail 深链复用）；订阅 chips（PUT skills 写回）
//     与平铺 config row 移除——插件不再逐 agent 配置，每个插件两个开关
//     （内容 / 派发 —— 其中「内容」面已随 2026-09-13 无审批批退场，现为
//     「派发开关」单控件，见本文件头）。
//   • 独立 MCP（state.mcpServers）展示从本页移除；设置页入口亦随 0905
//     设置清理批移除（MCP 概念由本插件系统全面取代）——前端已不消费
//     state.mcpServers / mcpServersUpdate，底层 MCP 机制保留。
// 2026-09-05 插件面板体验批（零 scala，纯前端）：
//   • 手动刷新按钮移除（按钮元素 + 对应 i18n 键一并清除）——列表
//     实时性由轻量轮询承担。
//   • 实时自动同步：面板可见（document.visibilityState=visible）且激活
//     （canvas 开启 + plugins tab 为 active pane）时，每 6s GET /api/plugins；
//     registry 名称集 diff——新插件按排序位插入卡片（plugins-card-entering
//     短动画）；消失的插件回退整页重渲（renderPlugins 同一管线）；状态
//     （blocked / contentChanged / 派发）变化时逐卡原地更新，零全列表重绘。
//     智能体区块不参与轮询（轻量，只轮插件注册表一个端点）。
//   • 原地状态切换：派发开关点击乐观翻转（switch/pill 即时反馈）→ POST
//     enable/disable → 成功后后台拉 registry 逐卡原地收敛（UI ≡ backend，
//     无全列表重绘）；失败回滚乐观态 + toast 报错。全程无 page reload。
//     （2026-09-13 无审批批曾让封禁/解封复用同一条收敛管线；该动作用户入口已随
//     2026-09-14 面板收敛批删除 ⇒ 本管线现只承载派发开关一条路径。）
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

/** Classify a manifest's CONTENT state from the frozen contract fields
 *  (C6, 2026-09-13 无审批批) — drives the status pill, the card classes and
 *  the block/unblock secondary action.
 *  • `blocked: true`       → 已封禁（引擎轨：不进目录 + 闸拒 + 在飞 MCP ≤30s 停）
 *  • `contentChanged: true`→ 内容已变更（**非拦截**可见性信号——内容已按新版本
 *                            生效；三处各一行：API 字段 / 目录段尾注记 / 启动健康摘要）
 *  • 其余                  → 已启用（在位即信任）
 *
 *  🔴 本函数**不再读 `trusted` 决定展示**：审批语义已退场（作者 2026-09-13 令），
 *  药丸绑 `trusted` 会恒显「已启用」成噪声。`trusted` 字段仍在 payload 里
 *  （契约保留），但本页不消费它。
 *
 *  优先级：blocked > contentChanged > enabled（药丸只能显示一个态；封禁压过其余）。 */
function pluginStatus(manifest) {
  return {
    blocked: manifest.blocked === true,
    contentChanged: manifest.contentChanged === true,
  };
}

/** Pill text + pill/card class for a status triple — single source shared by
 *  the full render (renderPluginCard) and the in-place convergence
 *  (applyPluginCardState), so the two can never drift apart. */
function pillOf(status) {
  const key = status.blocked ? 'blocked' : (status.contentChanged ? 'changed' : 'on');
  const textKey = status.blocked
    ? 'plugins.stateBlocked'
    : (status.contentChanged ? 'plugins.stateChanged' : 'plugins.stateOn');
  return { key, text: t(textKey) };
}

/** Classify a manifest's DISPATCH-permission state (令 1 派发面,
 *  `manifest.dispatch`; drives the card's single control — the top-right
 *  「任务分发器可见性」 switch, 2026-09-14 面板收敛批).
 *
 *  The switch writes exactly one thing — `dispatch.authorEnabled`（作者意图层）——
 *  so it is ENABLED only when that write is the thing that decides the effective
 *  value. Whenever it is not, the control is rendered `disabled` + an actionable
 *  note (`blocked`) instead of accepting a click that the backend would echo as
 *  `ok:true` while the effective value never moved (设计件 R1 代价① / R9-A）：
 *    • 该包**被封禁** ⇒ 有效值恒 false（封禁 ⇒ 不进目录 + 闸拒）⇒ 解封走 API / CLI；
 *    • 临时派发授权（transition）生效中 ⇒ 有效值由授权层拥有 ⇒ 到期/清除后
 *      本开关自动按作者意图生效。
 *  （2026-09-14 面板收敛批：原文「标签/提示文案与封禁动作完全分家，保证两动作可辨」
 *  随封禁 UI 退场失效——面板现只剩本控件一个动作。）*/
function dispatchState(manifest, blocked) {
  const d = manifest.dispatch || {};
  // 兼容默认（零迁移）：无 dispatch 记录 ⇒ 跟随内容信任面 = true。
  const authorEnabled = typeof d.authorEnabled === 'boolean' ? d.authorEnabled : true;
  const transitionActive = d.transitionActive === true;
  if (blocked) {
    return { on: false, blocked: 'blocked',
      title: t('plugins.dispatchBlockedBlocked'), note: t('plugins.dispatchBlockedBlocked') };
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
 *  + the status pill and the dispatch switch **on the same row** (card top-right).
 *
 *  DOM hooks (contract for tests AND for any future refactor):
 *    `[data-plugin-dispatch]` — 任务分发器可见性开关（令 1 既有钩子，保留不动；
 *                               2026-09-14 面板收敛批：位置改到卡片右上、与药丸同排）
 *    `.plugins-card-state`    — 右上角状态区（药丸 + 开关同排的容器）
 *  🔴 2026-09-14 面板收敛批退场钩子（**生产代码零使用**，判据见报告静态腿）：
 *    `[data-plugin-more]` / `[data-plugin-menu]` / `[data-plugin-block]`
 *    ——「更多」按钮 + 就地菜单行 + 封禁/解封按钮（作者三裁之批一：面板不再提供
 *    封禁入口，在飞止损只走 API / CLI）。
 *  🔴 内容审批开关（hook `data-plugin-switch`）已随 2026-09-13 无审批批**退场**：
 *  生产代码零功能性使用。现场判据（逐字可复算）：
 *    • `grep -rn "data-plugin-switch" src/main/resources/web/ | grep -vcE ':[0-9]+:[[:space:]]*(\*|//)'`
 *      ⇒ **0**（该 hook 的全部命中都在本文件的注释内：`:14` 与本节；无选择器、
 *      模板或事件绑定的功能性使用）；
 *    • `grep -rn "data-plugin-switch" src/main/scala/` ⇒ **0**；
 *    • `grep -rn "data-plugin-switch" tests/` ⇒ 命中全为注释与「零残留」断言读取
 *      （`contentSwitchCount` / `contentSwitchThere` ⇒ `=== 0` / `false`），
 *      无任何正向使用。
 *  （2026-09-13 复核 R2：原判据 `grep -rn "pluginSwitch\|plugin-switch" src/ tests/`
 *  = 0 为**假**——实测 13 命中，且该式能匹配到自身 ⇒ 按上列可复算判据重写。） */
function renderPluginCard(manifest) {
  const status = pluginStatus(manifest);
  const disp = dispatchState(manifest, status.blocked);
  const pill = pillOf(status);
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

  return `<div class="plugins-card${status.blocked ? ' blocked' : (status.contentChanged ? ' changed' : ' on')}" data-plugin="${esc(manifest.name)}">
    <div class="plugins-card-head">
      <div class="plugins-card-id">
        <span class="plugins-card-name">${esc(manifest.name)}</span>
        ${metaBits.length ? `<span class="plugins-card-meta">${esc(metaBits.join(' · '))}</span>` : ''}
      </div>
      <div class="plugins-card-state">
        <span class="plugins-state-pill ${pill.key}">${esc(pill.text)}</span>
        <span class="plugins-card-dispatch">
          <span class="plugins-dispatch-label">${esc(t('plugins.dispatchLabel'))}</span>
          ${toggleHTML({
            on: disp.on,
            disabled: !!disp.blocked,
            label: t('plugins.dispatchLabel'),
            title: disp.title,
            attrs: `data-plugin-dispatch="${esc(manifest.name)}"`
              + (disp.blocked ? ` data-dispatch-blocked="${esc(disp.blocked)}"` : ''),
          })}
        </span>
      </div>
    </div>
    <div class="plugins-dispatch-note"${disp.note ? '' : ' hidden'}>${esc(disp.note)}</div>
    ${manifest.description ? `<div class="plugins-card-desc">${esc(manifest.description)}</div>` : ''}
    ${(skills.length || servers.length || tools.length)
      ? `<div class="plugins-card-composition">${compositionSkills(skills, expandId)}${compositionMcp(servers)}${compositionTools(tools)}</div>`
      : ''}
    ${status.contentChanged ? `<div class="plugins-card-hint changed">${esc(t('plugins.changedHint'))}</div>` : ''}
    ${status.blocked ? `<div class="plugins-card-hint blocked">${esc(t('plugins.blockedHint'))}</div>` : ''}
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

/** Optimistic in-place flip of one card's DISPATCH switch (no re-render) —
 *  the only optimistic path left on the card (the content switch retired
 *  2026-09-13), same in-flight latch discipline. */
function flipDispatchState(card, on) {
  const sw = card.querySelector('[data-plugin-dispatch]');
  if (sw) {
    setToggleState(sw, on);
    sw.disabled = true; // in-flight guard — released by state convergence
  }
}

/** Release the in-flight guards after a terminal state (converged or rolled
 *  back) so the card's controls are clickable again. The dispatch control is
 *  only released when it is NOT blocked — blocked means "a click could not move
 *  the effective value", which is carried as `data-dispatch-blocked` on the
 *  element (see dispatchState); a blocked control stays disabled by design
 *  (零静默空操作)。
 *  （2026-09-14 面板收敛批：原同时释放的封禁/解封按钮已随该动作退场。） */
function releaseCardActions(card) {
  const dsw = card?.querySelector('[data-plugin-dispatch]');
  if (dsw && !dsw.hasAttribute('data-dispatch-blocked')) dsw.disabled = false;
}

/** Dispatch-permission action (令 1 派发面) — POST /api/plugins/:name/(enable|disable).
 *  Only bound on cards where the control is interactive (see dispatchState) —
 *  the backend write would not move the effective value otherwise.
 *  （2026-09-14 面板收敛批：原「与封禁动作钩子/文案分家（设计件 R9-A 视觉可分）」
 *  的定语已随封禁 UI 退场失效 —— 面板现只剩这一个控件。） */
async function setPluginDispatch(name, enable, card) {
  const path = `/api/plugins/${encodeURIComponent(name)}/${enable ? 'enable' : 'disable'}`;
  try {
    const resp = await api(path, { method: 'POST' });
    const body = await resp.json().catch(() => ({}));
    if (!resp.ok || body.error) throw new Error(body.error || `HTTP ${resp.status}`);
    const registry = await fetchPluginRegistry().catch(() => null);
    if (registry) applyRegistryStates(registry); // re-derives both controls
    else releaseCardActions(card); // registry unreachable — keep optimistic state
  } catch (e) {
    flipDispatchState(card, !enable); // roll back the optimistic flip
    releaseCardActions(card);
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

/** Update one card's DOM in place to match its manifest (status pill / card
 *  classes, dispatch state, hints). Preserves node identity, listeners and the
 *  expanded skill block — this is how state changes avoid any list redraw.
 *  （2026-09-14 面板收敛批：原「次级动作行的展开态不在这里重置」一段随该行退场
 *  删除——面板已无就地菜单。） */
function applyPluginCardState(card, manifest) {
  const status = pluginStatus(manifest);
  const disp = dispatchState(manifest, status.blocked);
  const pill = pillOf(status);
  card.classList.toggle('on', !status.blocked && !status.contentChanged);
  card.classList.toggle('changed', status.contentChanged);
  card.classList.toggle('blocked', status.blocked);
  // 状态药丸：绑 blocked / contentChanged（不绑 trusted）。
  const pillEl = card.querySelector('.plugins-state-pill');
  if (pillEl) {
    pillEl.textContent = pill.text;
    pillEl.classList.remove('on', 'changed', 'blocked');
    pillEl.classList.add(pill.key);
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
  // 两条卡片级提示：内容已变更（amber，非拦截可见性）/ 已封禁（error 族，可行动）。
  setCardHint(card, 'changed', status.contentChanged, t('plugins.changedHint'));
  setCardHint(card, 'blocked', status.blocked, t('plugins.blockedHint'));
}

/** Insert / update / remove one card-level hint row (kept before the skill
 *  expand block, created on demand). */
function setCardHint(card, kind, show, text) {
  let el = card.querySelector(`.plugins-card-hint.${kind}`);
  if (!show) { el?.remove(); return; }
  if (!el) {
    el = document.createElement('div');
    el.className = `plugins-card-hint ${kind}`;
    card.insertBefore(el, card.querySelector('.plugins-skill-expand'));
  }
  el.textContent = text;
}

/** Derived per-card rendering signature (status + dispatch面). Convergence
 *  only touches a card whose signature drifted, so a steady backend still
 *  costs zero DOM writes. */
function cardSignature(manifest) {
  const status = pluginStatus(manifest);
  const disp = dispatchState(manifest, status.blocked);
  return `${status.blocked ? 1 : 0}|${status.contentChanged ? 1 : 0}|${disp.on ? 1 : 0}|${disp.blocked}|${disp.note}`;
}

/** The same signature read back off the DOM (used to detect drift). */
function domCardSignature(card) {
  const dsw = card.querySelector('[data-plugin-dispatch]');
  const note = card.querySelector('.plugins-dispatch-note');
  return [
    card.classList.contains('blocked') ? 1 : 0,
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
 *  • status / dispatch drift → per-card in-place update (no redraw);
 *  • brand-new plugins → inserted at sorted position with a short enter
 *    animation;
 *  • new rejected entries → inserted (informational cards);
 *  • disappeared plugins → full re-render fallback (renderPlugins, same
 *    pipeline as the initial load).
 *  Returns the list of newly inserted card elements (may be []). */
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
      releaseCardActions(card); // states agree — just make sure the controls are live
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

/** Bind one plugin card's interactions (the dispatch switch + skill-expand
 *  toggles). Shared by the full render and the live-sync insert path. Idempotent
 *  via WeakSets (not expando properties — checkJs zero-new-errors discipline).
 *  （2026-09-14 面板收敛批：「更多」菜单开合与封禁/解封两条绑定随该 UI 退场。） */
const boundDispatchSwitches = new WeakSet();
const boundCompToggles = new WeakSet();

function bindCardEvents(card) {
  // 派发开关（令 1 派发面）：只写 plugins.dispatch；blocked 时控制件自带
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

/** Bind interactions: the dispatch switch (optimistic in-place toggling),
 *  skill-expand toggles, agent summary rows → per-agent detail tab.
 *  (The manual refresh button was removed 2026-09-05 — the poller keeps the
 *  list live; see startPluginsPolling. The 更多 secondary action was removed
 *  2026-09-14 with the block/unblock UI.) */
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
