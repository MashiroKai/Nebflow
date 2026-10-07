// plugins.js — Plugins page (Canvas tab, activity-bar entry «插件»/Plugins).
//
// 2026-09-13 作者裁定（插件「无审批」批——「装了就是信任」）：
//   ① 方案 = A「在位即信任 + 保留封禁」（引擎轨；`plugins.trust.*` 记录不再决定
//      装载、零迁移）；② 内容变更 = 不停（内容一变**不**抖掉在飞节点的插件 MCP）。
//   本文件是该批的**前端主交付**，消费两轨共用的**冻结契约 C6**：GET /api/plugins
//   每项含 `trusted` / `blocked`（新）/ `contentChanged`（新）。
//
//   • **条目式形态（2026-10-07 作者 09:40 令，与微信②令同规格）**：默认每插件
//     **仅一条目行**（chevron ＋ 名称 ＋ 版本/作者 meta ＋ 状态药丸 ＋ 一行摘要），
//     点击条目行展开**详情区**（再点收回，accordion 语义自洽）；🔴 零 locale 键值
//     变化——行摘要/详情正文全部现读既有字段（description / skills / mcpServers /
//     toolsExtension / viewerContributions），入口与清单不新造文案。详情区构成：
//       ├ 完整描述（与行摘要同字段；展开态行摘要隐藏防重复）
//       ├ skills 清单（逐 skill id＋描述＋preview 既有投影；多 skill 时清单内滚动）
//       ├ Plugin.md 入口（**包根有该文件才在场**：既有 /api/plugin-assets/<name>/
//       │   Plugin.md 路由探针 200 ⇒ 在场——🔴 引擎/scala 零改动，approvalManifest
//       │   契约无在场字段，探针一次/包/会话缓存；封禁包服务端 fail-closed 404 ⇒
//       │   入口永不在场；点击经**既有 Canvas 通路**（workspace-open-item →
//       │   markdown viewer）呈现内容，本面板只做入口呈现。可见标注 = 文件名
//       │   「Plugin.md」本身（数据投影，非 locale 串），探测/读取失败 toast 走
//       │   setPluginDispatch 同族（原始错误消息，零新串）。
//       ├ 组件面（mcp/skills/org.nebflow 现读投影 = 既有 comp chips 族；org.nebflow
//       │   行 = viewerContributions.viewers[].name 数据投影，无贡献不渲染）
//       └ 启停操作钮 = 既有派发开关**原样收进展开态**（钩子/语义/乐观翻转零变，
//           只动层级——2026-09-14 后它是卡片唯一控件，无封禁钮可收）。
//     展开态默认收起；收敛管线（applyRegistryStates）保持节点身份 ⇒ 展开态跨
//     轮询/开关收敛保持；整页重渲（locale 切换 / tab 恢复）无记忆 ⇒ 默认收起
//     （🔴 不新造持久化）。
//
//   • 统一插件系统（2026-09-04 重设计，形态不变）：Skill 与 MCP 统一为「插件」，
//     页面只呈现插件条目，skill/MCP 仅作条目展开详情区的内容构成标注语（含
//     N 个技能 / MCP server 名+transport / 内建工具白名单 +N）。
//
//   • **条目行形态（2026-10-07 条目式批改口径，原「卡片形态 C7」的头部/控件几何
//     随脸退役）**：
//       ┌ 内容审批开关 `[data-plugin-switch]` **退场**——「在位即信任」下它恒 on，
//       │   留着只是噪声；且它的 off 会停掉在飞节点的插件 MCP（作者 09-12 踩过的坑）。
//       ├ 状态药丸（**条目行右端**）绑 `blocked` / `contentChanged`（**不绑 `trusted`**）：
//       │   已封禁 > 内容已变更 > 默认（在位即信任）三态（优先级见 pluginStatus）。
//       │   🔴 **2026-09-15 作者令（先删文字、后删空框）**：默认态不出文字；
//       │   文字为空 ⇒ **整个药丸元素不渲染**（「删掉空框」）——判据是**元素
//       │   count = 0**，不是「元素在但不可见」（🔴 禁 CSS 遮盖式假修）。
//       │   封禁 / 内容已变更**仍出字且照常渲染**（那是可行动信号，不是说明性小字）。
//       └ 派发开关 `[data-plugin-dispatch]` = **展开详情区内**的**唯一控件**
//           （`.plugins-item-actions` 行，2026-10-07 条目式批自「卡片右上、与药丸
//           同排」收进展开态——只动层级，钩子/语义零变）；🔴 **2026-09-15 作者令：
//           可见文案「任务分发器可见性」小字整体删除**（toggle 本体自明）——
//           `plugins.dispatchLabel` 仅作 **aria-label** 保留（可访问性面；
//           2026-09-14 作者三裁之批一引入，2026-09-15 只删可见文本）。
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
import { contentText } from './contentI18n.js';
import { escapeHtml, statusIcon } from './utils.js';
import { toggleHTML, bindToggle, setToggleState } from './toggle.js';
import { fetchAgents, fetchAgentModel, openAgentDetail } from './agentManager.js';
import { key } from './branding.js';
import { openLoginModal } from './activityBar.js';

// ── Helpers ────────────────────────────────────────────────
function esc(s) { return escapeHtml(s); }

/** Short display ref for a model chain (last path segment). */
function shortModel(ref) {
  if (!ref) return '';
  const idx = ref.lastIndexOf('/');
  return idx >= 0 ? ref.slice(idx + 1) : ref;
}

/** Authorized JSON fetch (same token pattern as agentManager.js).
 *
 *  🔴 2026-09-22 作者裁定 (c) 之③（**潜在功能缺陷**，本批最高优先项）：改前此处
 *  硬编码 legacy 字面 token 键（`nebflow_` + `token` 的裸拼写）。品牌改名后该键
 *  失配 ⇒ **有登录态也读不到 token** ⇒ 本面板**永远**失败（外观与「未登录」相同，
 *  却是被 token 键吃掉的）。现取命名空间形态 `key('token')`，与全站同族先例逐字
 *  同形（`agentManager.js:18` / `avatarCache.js:78` / `chatSearch.js:732` /
 *  `daemons.js:263` / `dropbox.js:205` / `neblink.js:261`）。
 *  （🔴 本注释刻意**不**复写那个 legacy 字面量：R1 的判据 = 单引号包裹的 legacy
 *   token 键在**本文件** grep **零命中** —— 注释里留副本会污染读数。）
 *
 *  鉴权失败面（裁定 (c) 之①）：`status ∈ {401,403}` 时派 **既有** 全局事件
 *  `fm-auth-required`（写法照 `friendsApi.js:160/170` 与 `:690` 的
 *  `window.dispatchEvent(new CustomEvent('fm-auth-required'))`）——**不新造形态**，
 *  由既有两个消费方（`messages.js:4989` / `contacts.js:1207`）走
 *  `openLoginModal({ auto: true })`（带 15s burst 重复守卫）。
 *  · 本函数**只**在 `!resp.ok && (401|403)` 时派事件：200 路径零触碰（不读 body、
 *    不改 headers、不改返回值），非鉴权错误（5xx / 404 / 网络错）**不派**事件。
 *  · ⚠ **不搬好友域码表**：`friendsApi.js` 的 `FRIEND_TERMINAL_CODES` /
 *    `GROUP_DOMAIN_ERRORS`（「域语义码不派登录链」判据）是好友/群域正典，
 *    `/api/plugins` 不产这类语义码 ⇒ 跨域挪用 = 新造第二套形态，故此处**只**判状态码。
 *  · 调用方仍拿到原 resp（红线：本函数是**可见性**升级，不是拦截层）。 */
async function api(path, opts = {}) {
  const tok = localStorage.getItem(key('token')) || '';
  if (tok) opts.headers = { ...(opts.headers || {}), Authorization: `Bearer ${tok}` };
  const resp = await fetch(path, opts);
  if (!resp.ok && (resp.status === 401 || resp.status === 403)) {
    window.dispatchEvent(new CustomEvent('fm-auth-required'));
  }
  return resp;
}

/** GET /api/plugins — 注册表全量（approvalManifest + 拒载原因）。
 *  Response: { plugins: [manifest…], rejected: [{name, reason}] }
 *
 *  失败面（本批新增 `.status` 挂载，形态照 `friendsApi.js:154-160`）：错误对象带上
 *  HTTP 状态码，供渲染面分态 —— `401/403` ⇒ 登录引导卡（裁定 (c) 之②），其余
 *  （5xx / 404 / 网络）⇒ 既有「加载失败」文案（原键保留，不删）。 */
async function fetchPluginRegistry() {
  const resp = await api('/api/plugins');
  if (!resp.ok) {
    const err = /** @type {Error & {status?: number}} */ (new Error(`GET /api/plugins ${resp.status}`));
    err.status = resp.status;
    throw err;
  }
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

/** Pill class + pill text for a status triple — single source shared by the
 *  full render (renderPluginCard) and the in-place convergence
 *  (applyPluginCardState), so the two can never drift apart.
 *
 *  🔴 在位即信任 ⇒「已启用」是默认态的冗余小字（2026-09-15 作者令：开关态由
 *  toggle 本体自明，面板不留说明性小字）⇒ 默认态**不出文字**。
 *  🔴 **2026-09-15 作者裁定「删掉空框」**：`text === ''` ⇒ 渲染点**不生成 pill 元素**
 *  （`applyPluginCardState` 侧则**移除**既有元素）——判据 = DOM 里 `.plugins-state-pill`
 *  count = 0，🔴 **不是**「元素在、用 CSS 藏起来」。`blocked` / `changed` 仍出字并
 *  照常渲染（那是可行动信号）。`key` 仅在出字时被消费（元素缺席 ⇒ 无类可挂）。 */
function pillOf(status) {
  const key = status.blocked ? 'blocked' : (status.contentChanged ? 'changed' : 'on');
  const text = status.blocked
    ? t('plugins.stateBlocked')
    : (status.contentChanged ? t('plugins.stateChanged') : '');
  return { key, text };
}

/** Pill inner HTML（ui-status-redesign，S13 / 盘点 #52）：pill = 图案载体（红底
 *  红边保留），字转中性、词前 ban 图标（封禁形状语义）；amber changed 档不在
 *  本批 54 点范围（红系盘点），字形不变。 */
function pillInnerHtml(pill) {
  const icon = pill.key === 'blocked' ? statusIcon('ban', 10, 'st-err') : '';
  return icon + escapeHtml(pill.text);
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
  // 2026-09-15 作者令：「派发已关闭——只影响未来派发；已在跑的节点保持其插件许可」
  // 小字整体删除（开关态由 toggle 本体自明）⇒ 普通关态不再出 note。
  // （blocked / transition 两态的 note 保留——那是**可行动**注记，非说明性小字。）
  return { on: authorEnabled, blocked: '',
    title: authorEnabled ? t('plugins.dispatchOnTitle') : t('plugins.dispatchOffTitle'),
    note: '' };
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

/** Composition annotation: skill count（2026-10-07 条目式批：组件面投影 chip——
 *  逐 skill 清单改由详情区 `.plugins-item-skills` 直接呈现，本 chip 不再是展开
 *  toggle；`plugins.skillPreviewTitle` 的「展开」提示随之退场（title 属性删除，
 *  零键值变化）。 */
function compositionSkills(skills) {
  if (!skills.length) return '';
  return `<span class="plugins-comp">${esc(t('plugins.compositionSkills', { n: skills.length }))}</span>`;
}

/** 详情区 per-skill 清单（既有投影原样：id ＋ 描述 ＋ preview 摘录）。
 *  🔴 契约无 per-skill 状态字段（PluginSkill = id/name/description/path，
 *  approvalManifest 下发 id/description/preview）⇒ 不新造；skill 可用性随
 *  插件级状态药丸。多 skill 时 `.plugins-item-skills` 内滚动（CSS max-height）。 */
function skillListHtml(skills) {
  if (!skills.length) return '';
  const items = skills.map(s => `
    <div class="plugins-skill-item">
      <div class="plugins-skill-item-head">
        <span class="plugins-skill-item-id">${esc(s.id)}</span>
        <span class="plugins-skill-item-desc">${esc(contentText('skill', s.id, 'desc', s.description || ''))}</span>
      </div>
      ${s.preview ? `<pre class="plugins-skill-preview">${esc(s.preview)}</pre>` : ''}
    </div>`).join('');
  return `<div class="plugins-item-skills">${items}</div>`;
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

/** Composition annotation: org.nebflow/viewers.json 现读投影（canvas-media 批
 *  2026-10-05 OD-2=V-C 内联下发的 `viewerContributions`）——viewer 名清单。
 *  🔴 纯数据投影：前缀 = 包内目录命名空间字面量 `org.nebflow`（插件包布局事实，
 *  非 locale 串），条目 = viewers[].name 声明数据；无贡献 ⇒ 不渲染（seed 12 包
 *  现状全无 viewers.json ⇒ 本 chip 现网零在场，为未来声明包预备）。 */
function compositionViewers(manifest) {
  const vc = manifest.viewerContributions;
  const names = (vc && typeof vc === 'object' && !Array.isArray(vc) && Array.isArray(vc.viewers))
    ? vc.viewers.map(v => (v && typeof v === 'object' && typeof v.name === 'string') ? v.name : '').filter(Boolean)
    : [];
  if (!names.length) return '';
  return `<span class="plugins-comp" title="${esc(names.join(', '))}">${esc(`org.nebflow: ${names.join('、')}`)}</span>`;
}

/** 条目行下行 chevron（2026-10-07 补充令改形：与社交面板 .social-item-chevron
 *  同语言——chevron-down 展开翻转 180°；纯图标零文案）。 */
const ITEM_CHEVRON_SVG = '<svg class="plugins-item-chevron" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="6 9 12 15 18 9"/></svg>';

/** Plugin item row (multi-line card-item form, 2026-10-07 补充令五点口径):
 *  line 1 = 完整 puzzle 图标 + 名称（独立行不截断，长名自然换行）+ 版本/作者
 *  meta；右上 = 状态药丸 + chevron-down（展开翻转 180°，社交条目行同语言）；
 *  line 2 = 一行摘要（独立行、最多两行 line-clamp 超出省略）。行高随内容自适应。
 *  the whole row toggles the detail area (accordion 语义 = 点击展开 / 再点收回,
 *  逐条目独立自洽).
 *
 *  DOM hooks (contract for tests AND for any future refactor):
 *    `[data-plugin-item]`     — 条目行（点击/键盘 toggle 展开态；role=button +
 *                               aria-expanded/aria-controls 可访问性面）
 *    `.plugins-item-detail`   — 展开详情区（默认 hidden；accordion 载体）
 *    `[data-plugin-dispatch]` — 任务分发器可见性开关（令 1 既有钩子，保留不动；
 *                               2026-10-07 条目式批：层级改到详情区 actions 行）
 *    `.plugins-card-state`    — 条目行右上状态区（药丸 + chevron 载体）
 *    `[data-plugin-doc]`      — Plugin.md 入口（探针在场才显形；默认 hidden）
 *  🔴 2026-09-14 面板收敛批退场钩子（**生产代码零使用**，判据见报告静态腿）：
 *    `[data-plugin-more]` / `[data-plugin-menu]` / `[data-plugin-block]`
 *  🔴 内容审批开关（hook `data-plugin-switch`）已随 2026-09-13 无审批批**退场**。
 *  🔴 旧「skills chip toggle」机构（`.plugins-comp-toggle` / `[data-expand-for]` /
 *     `.plugins-skill-expand`）随 2026-10-07 条目式批退场——skills 清单改由详情区
 *     直接呈现（2026-10-07 前的面板形态见 git 历史）。 */
function renderPluginCard(manifest) {
  const status = pluginStatus(manifest);
  const disp = dispatchState(manifest, status.blocked);
  const pill = pillOf(status);
  const skills = Array.isArray(manifest.skills) ? manifest.skills : [];
  const servers = Array.isArray(manifest.mcpServers) ? manifest.mcpServers : [];
  const tools = Array.isArray(manifest.toolsExtension) ? manifest.toolsExtension : [];
  // Raw name (no pre-escaping): HTML attribute interpolation escapes at the
  // template site (data-plugin-item/aria-controls/data-plugin-doc), same
  // discipline the old data-expand hook carried.
  const name = manifest.name;
  const detailId = `plugins-detail-${name}`;

  const metaBits = [];
  if (manifest.version) metaBits.push(`v${manifest.version}`);
  if (manifest.author) metaBits.push(t('plugins.author', { author: manifest.author }));

  const desc = contentText('plugin', name, 'desc', manifest.description || '');
  const composition = compositionSkills(skills) + compositionMcp(servers)
    + compositionTools(tools) + compositionViewers(manifest);

  return `<div class="plugins-card${status.blocked ? ' blocked' : (status.contentChanged ? ' changed' : ' on')}" data-plugin="${esc(name)}">
    <div class="plugins-card-head" data-plugin-item="${esc(name)}" role="button" tabindex="0"
      aria-expanded="false" aria-controls="${esc(detailId)}" title="${esc(t('plugins.detail'))}">
      <div class="plugins-item-id">
        ${statusIcon('puzzle', 14, 'plugins-item-icon')}
        <span class="plugins-card-name">${esc(contentText('plugin', name, 'name', name))}</span>
        ${metaBits.length ? `<span class="plugins-card-meta">${esc(metaBits.join(' · '))}</span>` : ''}
      </div>
      <div class="plugins-card-state">
        ${pill.text ? `<span class="plugins-state-pill ${pill.key}">${pillInnerHtml(pill)}</span>` : ''}
        ${ITEM_CHEVRON_SVG}
      </div>
      <span class="plugins-item-summary">${esc(desc)}</span>
    </div>
    <div class="plugins-item-detail" id="${esc(detailId)}" hidden>
      ${manifest.description ? `<div class="plugins-card-desc">${esc(desc)}</div>` : ''}
      <div class="plugins-dispatch-note"${disp.note ? '' : ' hidden'}>${esc(disp.note)}</div>
      ${status.contentChanged ? `<div class="plugins-card-hint changed">${esc(t('plugins.changedHint'))}</div>` : ''}
      ${status.blocked ? `<div class="plugins-card-hint blocked">${esc(t('plugins.blockedHint'))}</div>` : ''}
      ${composition ? `<div class="plugins-card-composition">${composition}</div>` : ''}
      <button type="button" class="plugins-item-doc" data-plugin-doc="${esc(name)}" hidden>${statusIcon('file-text', 12, '')}Plugin.md</button>
      <div class="plugins-item-actions">
        <span class="plugins-card-dispatch">
          ${toggleHTML({
            on: disp.on,
            disabled: !!disp.blocked,
            // 可见的「任务分发器可见性」小字已整体删除（2026-09-15 作者令：开关
            // 本体自明）。取舍 = 保留**可访问性面**：aria-label 仍用该 key
            // （🔴 不得把 toggle 的 aria-label 删成空）。
            label: t('plugins.dispatchLabel'),
            title: disp.title,
            attrs: `data-plugin-dispatch="${esc(name)}"`
              + (disp.blocked ? ` data-dispatch-blocked="${esc(disp.blocked)}"` : ''),
          })}
        </span>
      </div>
      ${skillListHtml(skills)}
    </div>
  </div>`;
}

/** Rejected (load-failed) plugin row — informational only, no controls, not
 *  expandable（无详情可展开 ⇒ 无 chevron/role=button；拒载原因即条目行摘要；
 *  多行卡式同构：图标＋名称行 / 摘要行，badge 常在——2026-10-07 补充令改形）。 */
function renderRejectedCard(entry) {
  return `<div class="plugins-card rejected" data-plugin="${esc(entry.name)}">
    <div class="plugins-card-head">
      <div class="plugins-item-id">
        ${statusIcon('puzzle', 14, 'plugins-item-icon')}
        <span class="plugins-card-name">${esc(entry.name)}</span>
      </div>
      <div class="plugins-card-state">
        <span class="plugins-rejected-pill">${esc(t('plugins.rejected'))}</span>
      </div>
      <span class="plugins-item-summary">${esc(entry.reason || '')}</span>
    </div>
  </div>`;
}

/** Agent summary row: name + description + preset/model 现状 — the whole row
 *  deep-links into the per-agent detail editor (openAgentDetail).
 *  builtin-def 批（2026-10-03 作者令①）：代码定义的四件内置 agent 带只读徽标——
 *  面板对内置 agent 只读查看，编辑器进详情页也是只读（builtin 标记来自
 *  GET /api/agents 的 `builtin` 字段）。 */
function renderAgentRow(agent) {
  const model = agent.model || {};
  const preset = model.preset || '';
  const resolved = model.preferred || model.current || model.default || '';
  const meta = [
    preset ? esc(preset) : '',
    resolved ? esc(shortModel(resolved)) : '',
  ].filter(Boolean).map(p => `<span class="plugins-agent-meta-pill">${p}</span>`).join('');
  const builtinBadge = agent.builtin
    ? `<span class="plugins-agent-builtin-badge" title="${esc(t('plugins.builtinTitle'))}">${esc(t('plugins.builtinBadge'))}</span>`
    : '';
  return `<div class="plugins-agent-row" data-detail-agent="${esc(agent.name)}" role="button" tabindex="0"
    title="${esc(t('plugins.detail'))}">
    <span class="plugins-agent-name">${esc(contentText('agent', agent.name, 'name', agent.displayName || agent.name))}${builtinBadge}</span>
    <span class="plugins-agent-desc">${esc(contentText('agent', agent.name, 'desc', agent.description || ''))}</span>
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

    // Agents section first (author directive 2026-09-25: agent roster on top
    // of the plugins tab); blocks are order-swapped verbatim, internals untouched.
    const listHtml = `
      <div class="plugins-section" id="plugins-section-agents">
        <div class="plugins-section-title">${esc(t('plugins.agents'))}</div>
        <div class="plugins-section-hint">${esc(t('plugins.agentsHint'))}</div>
        <div class="plugins-agent-list">${summaries.length
          ? summaries.map(renderAgentRow).join('')
          : `<span class="plugins-none">${esc(t('plugins.noAgents'))}</span>`}</div>
      </div>
      <div class="plugins-section" id="plugins-section-plugins">
        <div class="plugins-section-title">${esc(t('plugins.list'))}</div>
        <div class="plugins-section-hint">${esc(t('plugins.listHint'))}</div>
        <div class="plugins-card-list">${(plugins.length || rejected.length)
          ? plugins.map(renderPluginCard).join('') + rejected.map(renderRejectedCard).join('')
          : `<span class="plugins-none">${esc(t('plugins.empty'))}</span>`}</div>
      </div>`;

    content.innerHTML = `
      <div class="plugins-topbar">
        <span class="plugins-title">${esc(t('activity.plugins'))}</span>
      </div>
      ${listHtml}`;

    bindPluginsEvents(content);
  }).catch(err => {
    if (!content.isConnected) return;
    // 失败分态（2026-09-22 作者裁定 (c) 之②）：401/403 ⇒ 面板内**可见兜底**登录引导卡；
    // 其余（5xx / 404 / 网络 / 解析）⇒ 既有「加载失败」文案（原键**保留**）。
    // 分工逐字照 `contacts.js:747` 注释所述（「401/403→登录失效卡+重登按钮
    // （`fm-auth-required` 全局链保留，卡片为兜底可见反馈）」）：链负责弹窗、
    // 卡片负责**面板内可见反馈**（用户没看到弹窗时面板不留白、不误报「加载失败」）。
    if (err?.status === 401 || err?.status === 403) renderAuthRequired(content);
    else content.innerHTML = `<div class="plugins-loading">${esc(t('plugins.loadFailed', { error: err?.message || err }))}</div>`;
  });
}

/** 登录引导卡（本批裁定 (c) 之②「未登录 · 需注册」可见降级）—— 形态照
 *  `contacts.js:794-810 buildSearchError()` 的 `auth` 分态：`fm-empty` 语义文案
 *  + `fm-result-foot` 内的 `glass-control fm-login-btn` 重登键 → `openLoginModal()`。
 *  🔴 只**复用**既有 `fm-*` 类与 `.fm-login-btn`（登录键族正典，sapphire.css:538
 *  起）：零新 class 形态、零新 token、零新色值；不往 CSS 面加任何东西
 *  （本批改动面 = `web/js/plugins.js` + `locales/{en,zh-CN}.js`）。
 *  重登键是**真手势** ⇒ 按族内既有纪律走无参 `openLoginModal()`（不加 `{auto:true}`：
 *  自动档是给非手势调用方的 burst 守卫，手势调用不受限，同四枚既有键逐字同形）。 */
function renderAuthRequired(content) {
  content.innerHTML = `
    <div class="plugins-topbar">
      <span class="plugins-title">${esc(t('activity.plugins'))}</span>
    </div>
    <div class="plugins-section">
      <div class="fm-result-card fm-search-error" role="alert" data-plugins-auth-card>
        <div class="fm-empty">${esc(t('plugins.authRequired'))}</div>
        <div class="fm-result-foot">
          <button type="button" class="glass-control fm-login-btn" data-plugins-relogin>${esc(t('plugins.relogin'))}</button>
        </div>
      </div>
    </div>`;
  content.querySelector('[data-plugins-relogin]')?.addEventListener('click', () => openLoginModal());
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
 *  accordion expansion state — this is how state changes avoid any list redraw.
 *  （2026-09-14 面板收敛批：原「次级动作行的展开态不在这里重置」一段随该行退场
 *  删除——面板已无就地菜单。2026-10-07 条目式批：展开态 = `.expanded` class +
 *  detail `[hidden]` 属性，均不在本函数触碰面 ⇒ 跨轮询/开关收敛保持。） */
function applyPluginCardState(card, manifest) {
  const status = pluginStatus(manifest);
  const disp = dispatchState(manifest, status.blocked);
  const pill = pillOf(status);
  card.classList.toggle('on', !status.blocked && !status.contentChanged);
  card.classList.toggle('changed', status.contentChanged);
  card.classList.toggle('blocked', status.blocked);
  // 状态药丸：绑 blocked / contentChanged（不绑 trusted）。🔴 空文案态 ⇒ **零元素**
  // （作者 2026-09-15 令「删掉空框」）：在位的移除、缺位的**不创建**；有文字态
  // （changed / blocked）照常新建 / 更新 —— 收敛路径与整页重渲共用 `pillOf()` 单一判据。
  let pillEl = card.querySelector('.plugins-state-pill');
  if (!pill.text) {
    pillEl?.remove();
  } else if (pillEl) {
    pillEl.innerHTML = pillInnerHtml(pill);
    pillEl.classList.remove('on', 'changed', 'blocked');
    pillEl.classList.add(pill.key);
  } else {
    const stateBox = card.querySelector('.plugins-card-state');
    if (stateBox) {
      pillEl = document.createElement('span');
      pillEl.className = `plugins-state-pill ${pill.key}`;
      pillEl.innerHTML = pillInnerHtml(pill);
      stateBox.insertBefore(pillEl, stateBox.firstChild);
    }
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

/** Insert / update / remove one card-level hint row (kept inside the expanded
 *  detail area, before the skill list — created on demand).
 *  ui-status-redesign（盘点 #52）：blocked 提示条 = 行首 ban 图标 + 中性正文
 *  （红底红边图案由 CSS 保留）。
 *  2026-10-07 条目式批：锚点自已退场的 `.plugins-skill-expand` 改挂
 *  `.plugins-item-skills`（详情区末位的 skills 清单）。 */
function setCardHint(card, kind, show, text) {
  let el = card.querySelector(`.plugins-card-hint.${kind}`);
  if (!show) { el?.remove(); return; }
  if (!el) {
    el = document.createElement('div');
    el.className = `plugins-card-hint ${kind}`;
    card.insertBefore(el, card.querySelector('.plugins-item-skills'));
  }
  el.innerHTML = (kind === 'blocked' ? statusIcon('ban', 11, 'st-err') : '') + escapeHtml(text);
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
    // 登录引导卡在屏时**没有**卡片列表（`applyRegistryStates` 找不到目标列表会直接
    // 归零返回）⇒ 登录成功后必须整页重渲，否则用户「重登 → 成功」后面板仍停在引导卡
    // 上（= 动作静默无效，违本文件头的 F1「零静默、可行动」纪律）。判据 = 引导卡元素
    // 在场（二值、可断言）；在屏时这一拍改走 `renderPlugins()` 原地收敛。
    if (pluginsContentEl()?.querySelector('[data-plugins-auth-card]')) { renderPlugins(); return; }
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

// 切语言即重渲（contenti18n 批）：插件名/描述与 agent 名/描述走 locale 映射
// （js/contentI18n.js），语言一变已渲染的 DOM 必须跟着重渲——本批前此处零监听。
// 面板可见且为活动 pane 时才重渲，沿用 pluginsPanelActive() 既有判据：不可见时
// 用户看不到旧文案，而重新激活该 tab 走 openPlugins() → renderPlugins() 自然取新值。
window.addEventListener('locale-changed', () => {
  if (pluginsPanelActive()) renderPlugins();
});

// ── Plugin.md 入口（2026-10-07 条目式批）───────────────────────────────────
// 在场判据 = 既有 /api/plugin-assets/<name>/Plugin.md 路由 200（StaticRoutes
// .pluginAssetsRoutes；GET-only，鉴权 = cookie `nebflow_token` 或 `?token=` 参数
// ——登录流不落 cookie ⇒ 照 persistence.js:432 / ws.js:519 的 `?token=` 同族
// 先例显式携带）。🔴 引擎/scala 零改动红线：approvalManifest 契约无在场字段，
// 探针是唯一零 scala 判据；一次/包/会话缓存（Promise<string|null>）。
// 封禁包：服务端 fail-closed 404（「封禁包永不内联」同向）⇒ 入口永不在场。
// 点击 = 点击时现取正文 → 既有 Canvas 通路（workspace-open-item → markdown
// viewer）；失败 toast 走 setPluginDispatch 同族（原始错误消息，零新串）。

/** Cached per-name probe: resolves to the doc text when present, else null.
 *  Never rejects (network/HTTP failures degrade to null ⇒ 入口不显形). */
const pluginMdDocs = new Map();

function pluginMdUrl(name) {
  const tok = localStorage.getItem(key('token')) || '';
  return `/api/plugin-assets/${encodeURIComponent(name)}/Plugin.md`
    + (tok ? `?token=${encodeURIComponent(tok)}` : '');
}

function probePluginMd(name) {
  if (!pluginMdDocs.has(name)) {
    pluginMdDocs.set(name, fetch(pluginMdUrl(name)).then(r => (r.ok ? r.text() : null)).catch(() => null));
  }
  return pluginMdDocs.get(name);
}

/** Fresh fetch at click time (single source of truth; throws on failure). */
async function fetchPluginMd(name) {
  const resp = await fetch(pluginMdUrl(name));
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.text();
}

/** Reveal the entry when (and only when) the package root has Plugin.md.
 *  Idempotent via WeakSet（本文件既有纪律：不用 expando 属性）。The probe
 *  resolves once per name; a later re-render reuses it (no re-fetch). */
const mountedDocEntries = new WeakSet();

function mountPluginMdEntry(card) {
  const btn = card?.querySelector('[data-plugin-doc]');
  const name = card?.getAttribute('data-plugin');
  if (!btn || !name || mountedDocEntries.has(btn)) return;
  mountedDocEntries.add(btn);
  probePluginMd(name).then(text => {
    if (text && btn.isConnected) btn.hidden = false; // 在场 ⇒ 入口在场；否则永缺席
  });
  btn.addEventListener('click', async () => {
    try {
      const content = await fetchPluginMd(name);
      // 既有 Canvas 通路（explorer.js fileContent 臂同款事件契约 → canvas.js
      // openWorkspaceItem → fileViewers markdown viewer）——本面板只做入口。
      window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
        id: `plugin-md:${name}`,
        title: 'Plugin.md',
        itemType: 'markdown',
        content,
        pinned: false,
        background: false,
      }}));
    } catch (e) {
      window.__showToast?.(String(e?.message || e), 'error');
    }
  });
}

/** Bind one plugin item's interactions (row accordion toggle, dispatch switch,
 *  Plugin.md entry). Shared by the full render and the live-sync insert path.
 *  Idempotent via WeakSets (not expando properties — checkJs zero-new-errors
 *  discipline).
 *  （2026-09-14 面板收敛批：「更多」菜单开合与封禁/解封两条绑定随该 UI 退场；
 *  2026-10-07 条目式批：skills chip toggle 绑定随该机构退场，改为条目行
 *  accordion toggle。） */
const boundDispatchSwitches = new WeakSet();
const boundItemRows = new WeakSet();

function bindCardEvents(card) {
  // 条目行 accordion（2026-10-07 条目式批）：点击/Enter/Space 展开⇄收回详情区，
  // aria-expanded 同步；逐条目独立（互不挤占）。
  const row = card.querySelector('[data-plugin-item]');
  if (row && !boundItemRows.has(row)) {
    boundItemRows.add(row);
    const toggleItem = () => {
      const detail = card.querySelector('.plugins-item-detail');
      if (!detail) return;
      const show = detail.hidden;
      detail.hidden = !show;
      card.classList.toggle('expanded', show);
      row.setAttribute('aria-expanded', show ? 'true' : 'false');
    };
    row.addEventListener('click', toggleItem);
    row.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleItem(); }
    });
  }
  // 派发开关（令 1 派发面）：只写 plugins.dispatch；blocked 时控制件自带
  // disabled，故此处点击不可达（零静默空操作）。开关本体收在展开详情区内
  // （2026-10-07 条目式批只动层级），语义与收敛管线零变。
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
  mountPluginMdEntry(card);
}

/** Bind interactions: the item-row accordion toggle, the dispatch switch
 *  (optimistic in-place toggling), the Plugin.md entry, agent summary rows →
 *  per-agent detail tab.
 *  (The manual refresh button was removed 2026-09-05 — the poller keeps the
 *  list live; see startPluginsPolling. The 更多 secondary action was removed
 *  2026-09-14 with the block/unblock UI. The skills chip-toggle was removed
 *  2026-10-07 with the itemized form — the skill list now lives in the detail
 *  area.) */
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
