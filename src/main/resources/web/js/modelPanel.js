// modelPanel.js — the /model face: per-role model-chain configuration.
//
// Opened by the /model UI-only slash command (input.js). Data contract is
// GET/PUT /api/agents/:name/model (RestApiRoutes): { mode: 'explicit'|'follow',
// chain: {preferred, fallbacks}|null, effectiveChain, resolvedFrom:
// 'own-chain'|'nebula-chain'|'seed', current, settable }. Only the four
// SchemePolicy.SettableAgents roles (Nebula, project-dispatcher, kernel,
// general) accept writes; every other agent follows Nebula uniformly.
//
// Interaction semantics (interaction card v2 §2–§6):
//   - two modes: unified (edits the Nebula primary chain) / separate (per-role
//     blocks; followers default to the follow state — no copies are
//     materialized until an explicit "configure independently" action forks
//     the role);
//   - every action saves immediately (PUT) + toast — no separate save button;
//   - re-fork control = explicit "re-follow Nebula" with a confirm dialog that
//     previews the chain that will apply;
//   - Nebula has no follow state (self-reference endpoint); an empty Nebula
//     chain degrades to the seed chain.
//
// This module is also the home of the shared model-ref helpers (migrated from
// the retired presets.js): the static provider-grouped model list and the
// read-only chain chips used by agentManager.js / flowViewers.js.

import state from './state.js';
import { key } from './branding.js';
import { t } from './i18n.js';
import { statusIcon, escapeHtml } from './utils.js';
// 弹窗退场批（作者令 2026-10-31②）：手机层 /model 面板铺满屏 + 左上返回（桌面零变化）。
import { mobileSheetBack } from './mobile/popSheet.js';
import { createDepVisibility } from './depVisibility.js';
import { rootNameParams } from './rootName.js';
import { setRow } from './settingsRows.js';

// ── Shared helpers (model refs + chain display) ────────────

export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function getToken() { return localStorage.getItem(key('token')) || ''; }
function authHeaders() {
  const tok = getToken();
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

export function shortModel(ref) {
  if (!ref) return '';
  const idx = ref.lastIndexOf('/');
  return idx >= 0 ? ref.slice(idx + 1) : ref;
}

/**
 * All model refs for pickers. state.parsedConfig is the live source - it is
 * re-parsed on every configData push, so models added via the provider editor
 * appear immediately. state.allModelRefs (filled once from modelOptions at WS
 * open, never refreshed) is only the fallback for before the first configData.
 * Both sources derive from the same server configRef, so contents are
 * identical modulo freshness.
 */
export function getAllModelRefs() {
  const providers = state.parsedConfig?.llm?.providers;
  if (providers) {
    const refs = [];
    for (const [name, p] of Object.entries(providers)) {
      (p.models || []).forEach(m => refs.push(`${name}/${m.id}`));
    }
    if (refs.length > 0) return refs;
  }
  return state.allModelRefs || [];
}

/** Chain refs from a {preferred, fallbacks} chain object. */
export function chainRefs(chain) {
  return [chain?.preferred, ...(chain?.fallbacks || [])].filter(Boolean);
}

// ── 模型不可用时段（model-schedule-unavailable 批 2026-10-29）──────────────
// GET /agents/:name/model 的 scheduleRestricted 数组 → ref → {windows, active}
// 查找表。面板用它给受限 ref 灰显（active）+ 窗口说明（title/标注）。缺席或
// 空数组 = 无任何时段规则（缺省零行为变化）。
/**
 * Build a ref → {windows, active} map from a GET /agents/:name/model response.
 * @param {{scheduleRestricted?: Array<{ref: string, windows: string[], active: boolean}>}|null} model
 * @returns {Map<string, {windows: string[], active: boolean}>}
 */
export function restrictedMapOf(model) {
  const map = new Map();
  (model?.scheduleRestricted || []).forEach(r => {
    if (r && r.ref && Array.isArray(r.windows) && r.windows.length) {
      map.set(r.ref, { windows: r.windows, active: !!r.active });
    }
  });
  return map;
}

/** Render a model chain as read-only chips: preferred highlighted, fallbacks
 * small, pulsing dot on the currently running model.
 * refs: [preferred, ...fallbacks]; current: running ref;
 * restricted: optional 时段闸查找表（restrictedMapOf）——配置了窗口的 chip 加
 * title 说明，active（当前在窗内）的灰显。缺省 null = 既有消费方零变化。 */
export function chainChipsHtml(refs, current, restricted = null) {
  const list = (refs || []).filter(Boolean);
  if (list.length === 0) {
    return `<div class="mchain-empty">${esc(t('model.seedNote'))}</div>`;
  }
  const chips = list.map((ref, i) => {
    const playing = current && ref === current;
    const res = restricted?.get(ref);
    const resAttr = res ? ` title="${esc(t('model.scheduleRestrictedTitle', { windows: res.windows.join(', ') }))}"` : '';
    const resCls = res?.active ? ' restricted' : '';
    return `<span class="mchain-chip${i === 0 ? ' preferred' : ''}${playing ? ' playing' : ''}${resCls}"${resAttr}>${playing ? '<span class="agent-detail-playing-dot"></span>' : ''}${esc(ref)}${res ? `<span class="mchip-window">${esc(res.windows.join(', '))}</span>` : ''}</span>`;
  }).join('<span class="mchain-arrow">→</span>');
  return `<div class="mchain">${chips}</div>`;
}

/** Chain chips from a GET /agents/:name/model response (effective chain). */
export function resolvedChainChipsHtml(model) {
  return chainChipsHtml(chainRefs(model?.effectiveChain), model?.current || '', restrictedMapOf(model));
}

/** Source note key for a GET /agents/:name/model response. */
export function modelSourceNoteKey(model) {
  if (model?.resolvedFrom === 'own-chain') return 'model.independent';
  if (model?.resolvedFrom === 'nebula-chain') return 'model.followsNebula';
  return 'model.seedNote';
}

// ── Panel state ────────────────────────────────────────────

// P1-2 downstream-role-face convergence batch (2026-10-03): the builtin-merge
// batch merged `kernel` + `general` into the single executor agent; the panel
// therefore lists the CURRENT role names, mirroring SchemePolicy.SettableAgents.
// 面板隐藏名过滤（追加第三修复项 · 作者 2026-10-07 23:13 令；S14 UI 收尾批同族）：
// 判据单源 = BuiltinAgents.PanelHiddenNames（「任务分发器」面板隐藏，14:13 令②）——
// 插件面板三处过滤已落库（RegistryRoutes GET /api/agents 第二过滤点 + plugins.js
// 第三过滤点），本面板是同族第四处消费位：角色枚举先过同一张隐藏名表，面板只留
// 现役可配角色。藏的是可见性不是生命：单取 GET /api/agents/:name/model 读写链
// 模型粒度批（作者令 2026-11-06 20:36「按主agent/ kernel /dispatcher/node/subagent
// 这几类分」）：面板/设置面 = **五类槽**——main(Nebula) / kernel / dispatcher / node /
// subagent。kernel/node/subagent 是引擎侧虚拟模型槽（nebflow.core.ModelRoles：
// 只有 model sidecar、不是完整 agent）；dispatcher 由隐藏态转正（五类之一）。
// 原「执行 agent(nebflow)」不再是配置类——它的 sidecar 仍是 kernel/node/subagent
// 槽为空时的兜底链（引擎面兼容），但面板不再提供入口。
const PANEL_HIDDEN_ROLES = [];
const ALL_ROLES = ['Nebula', 'kernel', 'project-dispatcher', 'node', 'subagent'];
const ROLES = ALL_ROLES.filter((r) => !PANEL_HIDDEN_ROLES.includes(r));
const SMALL_LLM = 'small-llm';
const FOLLOWER_ROLES = ROLES.filter((r) => r !== 'Nebula');
/** Frontend mirror of SchemePolicy.SettableAgents (the /model write gate). */
export const SETTABLE_ROLES = new Set(ROLES);
const ROLE_KEY = {
  'Nebula': 'model.roleNebula',
  'kernel': 'model.roleKernel',
  'project-dispatcher': 'model.roleDispatcher',
  'node': 'model.roleNode',
  'subagent': 'model.roleSubagent',
};

// 配置面风格统一批②（2026-10-31 作者令）：设置页「模型选择顺序」节与 /model
// 面板共享同一份状态/保存通道（本模块的 roleState + PUT /api/agents/:name/
// model）——任一侧保存后 emitChainChange() 通知另一侧即时重渲，改哪边都
// 即时同步（面板侧重渲走 refreshPanel，设置页节重渲走 mountModelChainSection
// 注册的监听）。
/** @type {Set<() => void>} */
const chainListeners = new Set();
/**
 * 订阅链状态变化（任一角色保存 / 动态模型列表刷新后触发）。
 * @param {() => void} fn
 * @returns {() => void} 退订函数
 */
export function onModelChainChange(fn) {
  chainListeners.add(fn);
  return () => chainListeners.delete(fn);
}
function emitChainChange() {
  for (const fn of [...chainListeners]) {
    try { fn(); } catch { /* 单监听异常不阻断其余订阅者 */ }
  }
}

let chainSectionSeq = 0;

let panelEl = null; // the overlay element while the panel is open
let panelMode = 'unified'; // 'unified' | 'separate'
// 弹窗退场批③：手机层角色下钻态（点角色头进该角色全屏配置；返回回列表）。
let drilledRole = null;
let panelVisibility = null; // the banner + followers conditional-display pair
let panelOnKey = null; // the panel's document-level Escape handler
/** role → { data: GET response|null, chain: string[]|null (null = follow), expanded: boolean } */
const roleState = new Map();

function roleLabel(role) { return t(ROLE_KEY[role] || role, rootNameParams()); }

function fetchModelOf(role) {
  return fetch(`/api/agents/${encodeURIComponent(role)}/model`, { headers: authHeaders() })
    .then(r => (r.ok ? r.json() : null))
    .catch(() => null);
}

async function fetchAllRoles() {
  const roles = [...ROLES, SMALL_LLM];
  const results = await Promise.all(roles.map(fetchModelOf));
  roles.forEach((role, i) => {
    const st = roleState.get(role) || { chain: null, expanded: false };
    st.data = results[i];
    // Re-sync the working copy from disk on every fetch. Local edits are
    // persisted through saveChain() before any refresh runs, so this sync
    // never clobbers an in-panel edit.
    st.chain = st.data && st.data.mode === 'explicit' ? chainRefs(st.data.chain) : null;
    roleState.set(role, st);
  });
}

/** 台账 #937：全部角色读数失败 = 面板不可用态（未登录 / 引擎不可达）。
 *  修前表现 = 静默僵面：Nebula 只剩 seed 注记 + 永久禁用的「独立配置」、
 *  follower 展开只有「加载失败」，无一处告诉用户为什么、也无恢复入口
 *  （作者 14:19 报障「能打开但上面都点不了」的成因面）。 */
function allRolesUnreadable() {
  return ROLES.every((r) => !roleState.get(r)?.data);
}

function nebulaEffectiveRefs() {
  return chainRefs(roleState.get('Nebula')?.data?.effectiveChain);
}

function forkedRoles() {
  return FOLLOWER_ROLES.filter(r => (roleState.get(r)?.chain || null) !== null);
}

// ── Writes (per-action save) ───────────────────────────────

async function putChain(role, chain) {
  const body = chain
    ? { model: { preferred: chain[0] || null, fallbacks: chain.slice(1) } }
    : { model: null };
  try {
    const resp = await fetch(`/api/agents/${encodeURIComponent(role)}/model`, {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({}));
      throw new Error(err.error || `HTTP ${resp.status}`);
    }
    const fresh = await resp.json();
    const st = roleState.get(role);
    if (st) {
      st.data = { ...(st.data || {}), ...fresh, settable: true };
      st.chain = fresh.mode === 'explicit' ? chainRefs(fresh.chain) : null;
    }
    window.__showToast?.(t('model.saved'), 'success');
    // Live follow previews: re-read every role (the followers' effectiveChain
    // moves whenever the Nebula chain changes).
    await refreshPanel();
    emitChainChange(); // 设置页回退链节同步腿（配置面风格统一批②）
  } catch (e) {
    window.__showToast?.(String(e.message || e), 'error');
    await refreshPanel(); // re-render from server state (reverts the local copy)
    emitChainChange();
  }
}

const saveChain = (role) => putChain(role, roleState.get(role)?.chain || null);

// ── Inline error helper ────────────────────────────────────
// ui-status-redesign（盘点 #38，S9 同族）：行首 circle-alert 红图标 + 中性正文。

function flashInline(container, msg) {
  const el = container?.querySelector('.mp-inline-err');
  if (!el) return;
  el.innerHTML = statusIcon('circle-alert', 12, 'st-err') + '<span>' + escapeHtml(msg) + '</span>';
  el.classList.add('on');
  setTimeout(() => { el.classList.remove('on'); }, 2500);
}

// ── Chain editor (rows + add) ──────────────────────────────

/** Provider badge + model name for a `provider/model...` ref. */
function refPartsHtml(ref) {
  const idx = ref.indexOf('/');
  const prov = idx >= 0 ? ref.slice(0, idx) : ref;
  const model = idx >= 0 ? ref.slice(idx + 1) : '';
  return `<span class="mp-prov">${esc(prov)}</span><span class="mp-model">${esc(model)}</span>`;
}

function chainRowsHtml(refs, currentRef, restricted = null) {
  // fallback 废除批（作者令 2026-11-06 20:36「不再支持模型fallback」）：链深恒 1
  // ⇒ 行面只剩**当前模型展示**（provider/model + 时段窗徽标）——排序手柄、
  // 首选/回退位次、行内删除一并退役（换模型 = 下方下拉单选；回跟随 = follower
  // 的「恢复跟随」钮）。
  const headIdx = currentRef ? refs.indexOf(currentRef) : -1;
  return refs.map((ref, i) => {
    const dim = headIdx > -1 && i < headIdx;
    const res = restricted?.get(ref);
    // 时段闸灰显（model-schedule-unavailable 批）：active = 当前在不可用窗内
    // ⇒ 复用 .mp-row-unavailable 灰显档；title 说明窗口（与健康层 dim 语义可
    // 叠加——两者都是「这一行这次不会被选中」的可见事实）。
    const schedDim = !!res?.active;
    const resTitle = res ? t('model.scheduleRestrictedTitle', { windows: res.windows.join(', ') }) : '';
    const title = [dim ? t('model.unavailable', { reason: t('model.healthFiltered') }) : '', resTitle]
      .filter(Boolean).join('；');
    return `<div class="agent-detail-model-row mp-row${dim || schedDim ? ' mp-row-unavailable' : ''}" data-idx="${i}"${title ? ` title="${esc(title)}"` : ''}>
      ${refPartsHtml(ref)}
      ${res ? `<span class="mp-window">${esc(res.windows.join(', '))}</span>` : ''}
    </div>`;
  }).join('');
}

function addSelectHtml(refs, chain, restricted = null) {
  const available = refs.filter(r => !chain.includes(r));
  const groups = new Map();
  available.forEach(ref => {
    const idx = ref.indexOf('/');
    const prov = idx >= 0 ? ref.slice(0, idx) : '';
    if (!groups.has(prov)) groups.set(prov, []);
    groups.get(prov).push(ref);
  });
  if (groups.size === 0) return '';
  // 时段闸标注（model-schedule-unavailable 批）：配置了窗口的模型在下拉里带
  // 窗口后缀（可见规则）；不禁用——窗内只是暂不可用，仍允许加进链。
  const optgroups = [...groups.entries()].map(([prov, list]) =>
    `<optgroup label="${esc(prov)}">${list.map(r => {
      const res = restricted?.get(r);
      const label = res ? `${shortModel(r)} · ${t('model.scheduleRestrictedSuffix', { windows: res.windows.join(', ') })}` : shortModel(r);
      return `<option value="${esc(r)}">${esc(label)}</option>`;
    }).join('')}</optgroup>`
  ).join('');
  return `<select class="mp-add-select"><option value="">${esc(t('model.addModel'))}</option>${optgroups}</select>`;
}

/**
 * Editor for one role's working chain: rows (drag to reorder, remove) + the
 * provider-grouped add dropdown. Mutates roleState[role].chain in place; every
 * mutation calls `commit(role)` — the per-action save. Appends itself to
 * `container`.
 */
function buildChainEditor(container, role, commit) {
  const st = roleState.get(role);
  const chain = st.chain;
  if (chain === null) return null;

  const editor = document.createElement('div');
  editor.className = 'mp-editor';
  const allRefs = getAllModelRefs();
  const current = st.data?.current || '';
  const restricted = restrictedMapOf(st.data);
  editor.innerHTML = `
    <div class="mp-rows">${chainRowsHtml(chain, current, restricted)}</div>
    <div class="mp-addrow">
      ${addSelectHtml(allRefs, chain, restricted)}
    </div>
    <div class="mp-inline-err" role="alert"></div>
    <div class="mp-note">${esc(t('model.singleModelNote'))}</div>`;

  // fallback 废除批（作者令 2026-11-06 20:36「不再支持模型fallback」）：编辑面 =
  // **单模型**——下拉单选即整链替换（preferred = 选中项，fallbacks = 空）。原
  // 「拖拽排序（指针版）+ 行内删除」两块随链深 1 退役（单行无序可排、无行可删；
  // follower 回跟随走既有「恢复跟随」钮）。
  const addSel = /** @type {HTMLSelectElement|null} */ (editor.querySelector('.mp-add-select'));
  addSel?.addEventListener('change', () => {
    const ref = addSel.value;
    if (!ref) return;
    if (chain.includes(ref)) { flashInline(editor, t('model.errDuplicate')); addSel.value = ''; return; }
    chain.splice(0, chain.length, ref); // 整链替换（链深恒 1）
    commit(role);
  });

  container.appendChild(editor);
  return editor;
}

// ── Role blocks ────────────────────────────────────────────

function statusChipHtml(role) {
  const st = roleState.get(role);
  const forked = (st?.chain || null) !== null;
  if (role === 'Nebula') {
    return `<span class="mp-status mp-status-main">${esc(t('model.mainChain', rootNameParams()))}</span>`;
  }
  return forked
    ? `<span class="mp-status mp-status-fork">${esc(t('model.independent'))}</span>`
    : `<span class="mp-status">${esc(t('model.followsNebula', rootNameParams()))}</span>`;
}

/** Fork action shared by Nebula (seed → explicit) and followers (follow → fork). */
function buildForkButton(body, role, commit) {
  const refs = nebulaEffectiveRefs();
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'cfg-btn cfg-btn-sm';
  btn.textContent = t('model.makeIndependent');
  if (refs.length === 0) {
    // Nothing to start from (no configured provider / empty install).
    btn.disabled = true;
  } else {
    btn.addEventListener('click', () => {
      const st = roleState.get(role);
      st.chain = refs.slice(0, 1); // fallback 废除批：fork 起点也只取主链首根（链深恒 1）
      commit(role);
    });
  }
  body.appendChild(btn);
}

function buildNebulaBlock(container, commit) {
  const block = document.createElement('div');
  block.className = 'mp-roleblock mp-roleblock-nebula';
  block.innerHTML = `
    <div class="mp-role-head">
      <span class="mp-role-name">${esc(roleLabel('Nebula'))}</span>
      ${statusChipHtml('Nebula')}
    </div>
    <div class="mp-role-body"></div>`;
  const body = /** @type {HTMLElement} */ (block.querySelector('.mp-role-body'));

  const noChain = (roleState.get('Nebula')?.chain || null) === null;
  // Unified mode: the failover-order note + the line naming the followers.
  // （面板 chrome——设置页回退链节不渲染这两行注记。）
  if (!noChain && panelMode === 'unified') {
    const foot = document.createElement('div');
    foot.className = 'mp-foot';
    foot.innerHTML = `
      <div class="mp-note">${esc(t('model.orderNote'))}</div>
      <div class="mp-note"><span class="mp-followers-label">${esc(t('model.followers'))}</span> ${esc(FOLLOWER_ROLES.map(roleLabel).join(' · '))}</div>`;
    body.appendChild(foot);
  }
  buildNebulaChainFace(body, commit);

  container.appendChild(block);
}

/** Nebula 链面共享体（配置面风格统一批②：/model 面板块与设置页顺序节的
 *  同一内容面）——seed 态（seed 注记 + 生效链预览 + 独立配置钮）或显式态
 *  （链编辑器）。两侧逐字节同款，样式与保存通道同源。
 *  精简批（作者令 2026-11-01）：「清空链」钮整块退役——链面只剩排序/加/删。 */
function buildNebulaChainFace(body, commit) {
  const st = roleState.get('Nebula');
  const noChain = (st?.chain || null) === null;

  if (noChain) {
    // Seed state: the effective (seed) chain read-only + the action that
    // starts an explicit chain from it.
    const note = document.createElement('div');
    note.className = 'mp-note mp-note-seed';
    note.textContent = t('model.seedNote');
    body.appendChild(note);
    if (st?.data) {
      const preview = document.createElement('div');
      preview.className = 'mp-preview';
      preview.innerHTML = resolvedChainChipsHtml(st.data);
      body.appendChild(preview);
    }
    buildForkButton(body, 'Nebula', commit);
    return;
  }
  buildChainEditor(body, 'Nebula', commit);
}

function buildFollowerBlock(container, role, commit) {
  const block = document.createElement('div');
  block.className = 'mp-roleblock mp-roleblock-follower';
  block.dataset.role = role;
  const st = roleState.get(role);
  const forked = (st?.chain || null) !== null;
  const previewRefs = st?.chain || nebulaEffectiveRefs();
  block.innerHTML = `
    <div class="mp-role-head mp-role-head-toggle" role="button" tabindex="0" aria-expanded="${st?.expanded ? 'true' : 'false'}">
      <span class="mp-chev${st?.expanded ? ' open' : ''}"><svg viewBox="0 0 10 10" width="10" height="10" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2.5 3.5 5 6l2.5-2.5"/></svg></span>
      <span class="mp-role-name">${esc(roleLabel(role))}</span>
      ${statusChipHtml(role)}
      <span class="mp-head-preview">${esc(previewRefs.map(shortModel).join(' → '))}</span>
    </div>
    <div class="mp-role-body" style="display:${st?.expanded ? '' : 'none'}"></div>`;
  const body = /** @type {HTMLElement} */ (block.querySelector('.mp-role-body'));

  if (st?.expanded) {
    if (!st.data) {
      body.innerHTML = `<div class="mp-note">${esc(t('model.loadFailed'))}</div>`;
    } else if (!forked) {
      // Follow state: live preview of the Nebula primary chain (re-read after
      // every save) + the explicit fork action. No copy is materialized here.
      body.innerHTML = `
        <div class="mp-note">${esc(t('model.followsNebula', rootNameParams()))} — ${esc(t('model.effectivePreview'))}</div>
        <div class="mp-preview">${resolvedChainChipsHtml(st.data)}</div>`;
      if (role === 'kernel') {
        const kn = document.createElement('div');
        kn.className = 'mp-note';
        kn.textContent = t('model.kernelNote');
        body.appendChild(kn);
      }
      buildForkButton(body, role, commit);
    } else {
      // Fork state: own chain editor + the explicit re-follow control.
      body.innerHTML = `<div class="mp-note mp-note-fork">${esc(t('model.independent'))}</div>`;
      if (role === 'kernel') {
        const kn = document.createElement('div');
        kn.className = 'mp-note';
        kn.textContent = t('model.kernelNote');
        body.appendChild(kn);
      }
      buildChainEditor(body, role, commit);
      const foot = document.createElement('div');
      foot.className = 'mp-foot';
      const restoreBtn = document.createElement('button');
      restoreBtn.type = 'button';
      restoreBtn.className = 'cfg-btn cfg-btn-sm mp-restore';
      restoreBtn.textContent = t('model.restoreFollow', rootNameParams());
      restoreBtn.addEventListener('click', () => {
        const preview = nebulaEffectiveRefs().join(' → ') || t('model.seedNote');
        window.__showConfirm?.(
          t('model.restoreFollow', rootNameParams()),
          t('model.restoreFollowConfirm', { chain: preview, ...rootNameParams() }),
          async () => { await putChain(role, null); },
          { tone: 'neutral' },
        );
      });
      foot.appendChild(restoreBtn);
      body.appendChild(foot);
    }
  }

  container.appendChild(block);

  const head = /** @type {HTMLElement} */ (block.querySelector('.mp-role-head-toggle'));
  const toggle = () => {
    const s = roleState.get(role);
    // 弹窗退场批③（作者令「不再有展开式配置」）：手机层点角色头 = 下钻下一
    // 层级（该角色全屏配置），钻入态再点同一头 = 返回列表；桌面保持原位展开。
    if (document.body.classList.contains('mobile-layer')) {
      if (drilledRole === role) {
        drilledRole = null;
        s.expanded = false;
        renderPanel();
        return;
      }
      drilledRole = role;
      s.expanded = true;
      renderPanel(); // full re-render — `block` above is detached afterwards
      panelEl?.querySelector(`.mp-roleblock-follower[data-role="${role}"] .mp-role-body`)
        ?.classList.add('ob-q-reveal');
      return;
    }
    s.expanded = !s.expanded;
    renderPanel(); // full re-render — `block` above is detached afterwards
    if (s.expanded) {
      // Same reveal animation as the AskUser dependsOn branches — applied to the
      // FRESH body element from the re-rendered DOM.
      panelEl?.querySelector(`.mp-roleblock-follower[data-role="${role}"] .mp-role-body`)
        ?.classList.add('ob-q-reveal');
    }
  };
  head.addEventListener('click', toggle);
  head.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
  });
}

// ── 设置页回退链节（配置面风格统一批②，2026-10-31 作者令「/model 里的回退
// 顺序调整页在设置页原样再放一份，两份共享同一份状态/保存通道，改哪边都
// 即时同步」）────────────────────────────────────────────────────────────
// 内容面 = buildNebulaChainFace 同款（seed 态 / 链编辑器，逐字节同源）；
// 状态 = 本模块 roleState（与 /model 面板同一份）；保存通道 = putChain 单点。
// 任一侧保存 ⇒ emitChainChange ⇒ 本节经 onModelChainChange 监听即时重渲。

/**
 * 节级渲染：Nebula 数据读数失败 ⇒ 台账 #937 同款显式降级面（节级缩小版），
 * 不静默半死。生图一组**与本节的 Nebula 状态互不连坐**——降级面之后照常
 * 追加（该面自己读过，失败即隐藏自己那组）。
 * @param {HTMLElement} container #model-chain-section-body
 */
let settingsActiveRole = 'Nebula';

function renderSettingsRoleSelector(container) {
  const tabs = document.createElement('div');
  tabs.className = 'mp-settings-role-tabs';
  tabs.setAttribute('role', 'tablist');
  tabs.setAttribute('aria-label', t('settings.modelRoleTabs'));
  for (const role of ROLES) {
    const tab = document.createElement('button');
    tab.type = 'button';
    tab.className = 'cfg-btn cfg-btn-sm mp-settings-role-tab';
    tab.textContent = roleLabel(role);
    tab.setAttribute('role', 'tab');
    tab.setAttribute('aria-selected', role === settingsActiveRole ? 'true' : 'false');
    tab.addEventListener('click', () => {
      settingsActiveRole = role;
      renderChainSectionBody(container);
    });
    tabs.appendChild(tab);
  }
  container.appendChild(tabs);

  const role = settingsActiveRole;
  const st = roleState.get(role);
  const panel = document.createElement('div');
  panel.className = 'mp-settings-role-panel';
  panel.setAttribute('role', 'tabpanel');
  if (!st?.data) {
    panel.textContent = t('model.loadFailed');
  } else {
    const effective = chainRefs(st.data.effectiveChain)[0] || '';
    const status = document.createElement('div');
    status.className = 'mp-note';
    status.textContent = role === 'Nebula'
      ? t('model.mainChain', rootNameParams())
      : st.data.mode === 'explicit' ? t('model.independent') : t('model.followsNebula', rootNameParams());
    panel.appendChild(status);
    const select = document.createElement('select');
    select.className = 'mp-add-select mp-settings-role-model';
    select.setAttribute('aria-label', `${roleLabel(role)} — ${t('model.addModel')}`);
    const refs = getAllModelRefs();
    if (role !== 'Nebula') {
      const inherit = document.createElement('option');
      inherit.value = '';
      inherit.textContent = t('model.followsNebula', rootNameParams());
      select.appendChild(inherit);
    }
    for (const ref of refs) {
      const option = document.createElement('option');
      option.value = ref;
      option.textContent = ref;
      if (ref === effective && (role === 'Nebula' || st.data.mode === 'explicit')) option.selected = true;
      select.appendChild(option);
    }
    if (!effective && role === 'Nebula') {
      const placeholder = document.createElement('option');
      placeholder.value = '';
      placeholder.textContent = t('model.seedNote');
      placeholder.selected = true;
      select.insertBefore(placeholder, select.firstChild);
    }
    select.addEventListener('change', () => {
      void putChain(role, select.value ? [select.value] : null);
    });
    panel.appendChild(select);
  }
  container.appendChild(panel);
}

function renderChainSectionBody(container) {
  container.replaceChildren();
  renderSettingsRoleSelector(container);
  const small = roleState.get(SMALL_LLM);
  const row = document.createElement('div');
  row.id = 'small-llm-settings-row';
  const refs = getAllModelRefs();
  const selected = small?.data?.mode === 'explicit' ? chainRefs(small.data.chain)[0] : '';
  const options = `<option value="">${esc(t('model.smallLlmFallback'))}</option>` + refs.map(ref =>
    `<option value="${esc(ref)}"${ref === selected ? ' selected' : ''}>${esc(ref)}</option>`
  ).join('');
  row.insertAdjacentHTML('beforeend', setRow({
    title: t('model.smallLlm'),
    control: `<select class="cfg-select set-field" aria-label="${esc(t('model.smallLlm'))}"${small?.data ? '' : ' disabled'}>${options}</select>`,
  }));
  row.querySelector('select').addEventListener('change', (event) => {
    void putChain(SMALL_LLM, event.target.value ? [event.target.value] : null);
  });
  container.appendChild(row);
  // Image generation remains a separate provider selector, not a conversation role.
  const list = document.createElement('div');
  list.className = 'mp-group-list';
  container.appendChild(list);
  renderSwitchGroups(list, { collapsible: true });
}

/** 拉取 Nebula 最新读数 + 两面状态投影 → 重绘节内容（seq 守卫丢弃陈旧响应）。 */
async function refreshChainSection(container) {
  const seq = ++chainSectionSeq;
  await Promise.all([fetchFaceStatuses(), fetchAllRoles()]);
  if (seq !== chainSectionSeq) return;
  if (container.isConnected) renderChainSectionBody(container);
}

/**
 * 挂载设置页「模型回退链」节内容（sidebar.js renderSettings 尾调用；重渲前
 * 先调上一任返回的卸载函数）。返回卸载函数。
 * @param {HTMLElement|null} container #model-chain-section-body
 * @returns {() => void}
 */
export function mountModelChainSection(container) {
  if (!container) return () => {};
  let alive = true;
  void refreshChainSection(container);
  const off = onModelChainChange(() => {
    if (alive && container.isConnected) void refreshChainSection(container);
  });
  return () => { alive = false; off(); };
}

// ── 生图 切换分组（jev-imagegen-align-llm 批 2026-11-02，作者令第 4 条；
// jev-retire 批 2026-11-16 起只剩生图一面——JeV 面随 JeV 全量退役）
// 契约 §I-3 定案：这一面**不是** agent 模型回退链（没有「角色」，是单
// provider 单 model 的配置节）——/model 里的组是「该配置节当前使用哪个
// provider」的**指针切换器**，写 `imagegen.defaultProvider`。
// 故写通道独立于 PUT /api/agents/:name/model，也**不进** ALL_ROLES / ROLES /
// FOLLOWER_ROLES / SETTABLE_ROLES（那四行被 roles-hiddenfilter spec 逐字钉死）。
//
// 🔴 契约 §E.4 / §I-2 三条结构铁钉（违反即红）：
//   ① 分组 class = `.mp-group[data-group=…]`，**绝不** `.mp-roleblock`
//      （model-panel-degraded.spec.mjs:148/163 钉 `.mp-roleblock` 计数恒为 2）；
//   ② 切换控件只有 `<select>`（model-chain-slim.spec.mjs:217/227 钉设置页节内
//      `<button>` 数恒为 0）；
//   ③ 两句 hint 放**分组内**、不进节头 `.cfg-hint`（同 spec:189/204-206 钉节头
//      说明 = 单行高）。
// 视觉基线（§I-1）：选**方案 B**——设置页节的两组默认折叠，折叠控件是原生
// `<details>/<summary>`（不是 <button>），首屏零新增可见高度。

/**
 * 切换「面」的静态描述（读数端点 / 指针字段 / 标题键 / data-group 名）。
 * 单点定义：读数、渲染、写通道三处都从这里取，禁第二份字面量。
 * @type {Array<{group: 'imagegen', endpoint: string, pointer: string, titleKey: string}>}
 */
const SWITCH_FACES = [
  { group: 'imagegen', endpoint: '/api/config/imagegen', pointer: 'defaultProvider', titleKey: 'model.groupImagegen' },
];

/** group → 状态投影（`null` = 该面读数失败 ⇒ 该分组隐藏）。 */
const /** @type {Map<string, any>} */ faceState = new Map();

/**
 * 读一面的状态投影（GET /api/config/imagegen）。
 * @param {{endpoint: string}} face
 * @returns {Promise<any|null>} 非 2xx / 非对象 / 异常 ⇒ null
 */
async function fetchFaceStatus(face) {
  try {
    const resp = await fetch(face.endpoint, { headers: authHeaders() });
    if (!resp.ok) return null;
    const data = await resp.json();
    return data && typeof data === 'object' ? data : null;
  } catch {
    return null;
  }
}

/** 并行重读各面投影：一面失败只影响那一面（互不连坐）。 */
async function fetchFaceStatuses() {
  const results = await Promise.all(SWITCH_FACES.map(fetchFaceStatus));
  SWITCH_FACES.forEach((face, i) => faceState.set(face.group, results[i]));
}

/**
 * 该面已配置的 provider（名 → 条目）。投影缺 `providers` / 非对象 = 空。
 * @param {any} status
 * @returns {Record<string, any>}
 */
function faceProvidersOf(status) {
  const p = status?.providers;
  return p && typeof p === 'object' ? /** @type {Record<string, any>} */ (p) : {};
}

/**
 * 选项文案 `name · model`（模型器名缺席时只留 provider 名——不造假字段）。
 * @param {string} name
 * @param {any} entry
 * @returns {string}
 */
function switchOptionLabel(name, entry) {
  const model = entry && typeof entry.model === 'string' ? entry.model : '';
  return model ? `${name} · ${model}` : name;
}

/**
 * 一个切换分组的 DOM（生图 × /model 面板 / 设置页节 = **同一次产出**，
 * 差别只在 `opts.collapsible`）。
 *  - 读数失败 ⇒ 返回 null（调用方不渲染该组，面板不炸）；
 *  - 未配置任何 provider ⇒ 组内一行空态提示 + 禁用的 select（绝不造假选项）。
 * @param {(typeof SWITCH_FACES)[number]} face
 * @param {{collapsible?: boolean}} [opts] true ⇒ 折叠形态（<details>/<summary>，默认合上）
 * @returns {HTMLElement|null}
 */
function buildSwitchGroup(face, opts = {}) {
  const status = faceState.get(face.group);
  if (!status) return null;
  const providers = faceProvidersOf(status);
  const names = Object.keys(providers);
  const current = typeof status[face.pointer] === 'string' ? status[face.pointer] : '';
  const collapsible = opts.collapsible === true;

  const root = document.createElement(collapsible ? 'details' : 'div');
  root.className = 'mp-group';
  root.dataset.group = face.group;

  const head = document.createElement(collapsible ? 'summary' : 'div');
  head.className = 'mp-group-head';
  const title = document.createElement('span');
  title.className = 'mp-group-title';
  title.textContent = t(face.titleKey);
  // provider-add-tabs 批（溢出零容忍断言）：组名窄框省略号收尾（css 四件套），
  // title 全文 = 豁免第四件 + 窄屏可悬停读全名。
  title.title = t(face.titleKey);
  head.appendChild(title);
  if (current) {
    const cur = document.createElement('span');
    cur.className = 'mp-group-cur';
    cur.textContent = current;
    head.appendChild(cur);
  }

  const body = document.createElement('div');
  body.className = 'mp-group-body';

  if (names.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'mp-group-empty';
    empty.textContent = t('settings.cfgCard.emptyHint');
    body.appendChild(empty);
  }

  const sel = document.createElement('select');
  sel.className = 'mp-group-switch';
  sel.setAttribute('aria-label', `${t(face.titleKey)} — ${t('model.groupSwitch')}`);
  if (names.length === 0) {
    sel.disabled = true;
    const ph = document.createElement('option');
    ph.value = '';
    ph.textContent = t('settings.cfgCard.keyMissing');
    sel.appendChild(ph);
  } else {
    if (!names.includes(current)) {
      // 指针缺席/悬空（该节还没选过 provider）：占位行不当成「已选第 0 项」。
      const ph = document.createElement('option');
      ph.value = '';
      ph.textContent = t('settings.cfgCard.keyMissing');
      ph.selected = true;
      sel.appendChild(ph);
    }
    for (const name of names) {
      const opt = document.createElement('option');
      opt.value = name;
      opt.textContent = switchOptionLabel(name, providers[name]);
      if (name === current) opt.selected = true;
      sel.appendChild(opt);
    }
    sel.addEventListener('change', () => {
      const next = sel.value;
      if (!next || next === current) return;
      void applyFaceSwitch(face, next);
    });
  }

  body.appendChild(sel);
  root.appendChild(head);
  root.appendChild(body);
  return root;
}

/**
 * 把切换分组渲染进容器（**两处唯一实现**：/model 面板的 `.mp-groups-slot` 与
 * 设置页 `#model-chain-section-body` 尾部的 `.mp-group-list`）。
 * 所有面都读不到 ⇒ 容器留空且隐藏（不占位、不炸面）。
 * @param {HTMLElement|null} container
 * @param {{collapsible?: boolean}} [opts]
 */
function renderSwitchGroups(container, opts = {}) {
  if (!container) return;
  // 折叠态跨重渲保留（设置页每次保存后整节重绘，用户展开的组不该自己合上）。
  const openGroups = new Set(
    [...container.querySelectorAll('details.mp-group[open]')].map((d) => /** @type {HTMLElement} */ (d).dataset.group),
  );
  /** @type {HTMLElement[]} */
  const groups = [];
  for (const face of SWITCH_FACES) {
    const el = buildSwitchGroup(face, opts);
    if (!el) continue;
    if (el instanceof HTMLDetailsElement && openGroups.has(face.group)) el.open = true;
    groups.push(el);
  }
  container.replaceChildren(...groups);
  container.style.display = groups.length ? '' : 'none';
}

/** /model 面板的切换分组槽（面板未开 ⇒ no-op；不触发整面板重渲）。 */
function renderPanelGroups() {
  const slot = /** @type {HTMLElement|null} */ (panelEl?.querySelector('.mp-groups-slot') || null);
  renderSwitchGroups(slot, { collapsible: false });
}

/**
 * 生图切换载荷（PUT /api/config/imagegen 是**整节替换**语义 ⇒ 先读回现有节再
 * 合并写，绝不把 providers 写没）：
 *  - 活快照 `state.parsedConfig.imagegen` 优先（保真：未进投影的字段如
 *    `timeoutMs` 逐字保留），投影 `providers` 兜底（快照缺席/过期）；
 *  - `keyConfigured` 是投影字段，永不回写。
 * @param {string} name
 * @returns {{enabled: boolean, defaultProvider: string, providers: Record<string, any>}}
 */
function imagegenSwitchPayload(name) {
  const live = /** @type {any} */ (state.parsedConfig)?.imagegen;
  const liveProviders = live && typeof live === 'object' && live.providers && typeof live.providers === 'object'
    ? /** @type {Record<string, any>} */ (live.providers)
    : null;
  const projection = faceProvidersOf(faceState.get('imagegen'));
  /** @type {Record<string, any>} */
  const providers = {};
  for (const [provName, entry] of Object.entries(projection)) {
    // 投影给必填的 type/model + 可选 baseUrl/keyRef（派生节的最小可解码形态）。
    /** @type {Record<string, any>} */
    const row = { type: entry.type, model: entry.model };
    if (entry.baseUrl != null) row.baseUrl = entry.baseUrl;
    if (entry.keyRef != null) row.keyRef = entry.keyRef;
    // 活快照补投影没覆盖的字段（timeoutMs 等），绝不覆盖投影已定的键。
    const src = liveProviders && liveProviders[provName] && typeof liveProviders[provName] === 'object'
      ? /** @type {Record<string, any>} */ (liveProviders[provName]) : null;
    if (src) {
      for (const [k, v] of Object.entries(src)) if (!(k in row)) row[k] = v;
    }
    providers[provName] = row;
  }
  const enabled = typeof live?.enabled === 'boolean'
    ? live.enabled
    : !!(faceState.get('imagegen')?.enabled);
  return { enabled, defaultProvider: name, providers };
}

/**
 * 切换某面当前使用的 provider（指针切换，不是回退链）。成功后 toast
 * `model.groupSwitched` 并重渲分组——重渲走**既有** onModelChainChange 订阅
 * （与 putChain 同口径，不引第二套事件）。
 * @param {(typeof SWITCH_FACES)[number]} face
 * @param {string} name 目标 provider 名
 */
async function applyFaceSwitch(face, name) {
  const body = imagegenSwitchPayload(name);
  try {
    const resp = await fetch(face.endpoint, {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({}));
      throw new Error(/** @type {any} */ (err).error || `HTTP ${resp.status}`);
    }
    window.__showToast?.(t('model.groupSwitched', { name }), 'success');
  } catch (e) {
    window.__showToast?.(String(/** @type {any} */ (e).message || e), 'error');
  }
  await fetchFaceStatuses(); // 失败也回读真态（下拉回到服务端值）
  renderPanelGroups();
  emitChainChange(); // 设置页节同步腿（既有订阅，零新事件）
}

// ── Panel skeleton + render ────────────────────────────────

function renderBannerContent() {
  const banner = panelEl?.querySelector('.mp-banner');
  if (!banner) return;
  const roles = forkedRoles().map(roleLabel).join(' · ');
  banner.innerHTML = `<span class="mp-banner-text">${esc(t('model.divergedBar', { roles }))}</span><button type="button" class="cfg-btn cfg-btn-sm mp-banner-view">${esc(t('model.divergedView'))}</button>`;
  banner.querySelector('.mp-banner-view')?.addEventListener('click', () => setMode('separate'));
}

/** 台账 #937：降级面 —— 显式失败态 + 原因 + 重试（recovery entry）。
 *  渲染进 .mp-body（替下 per-role blocks）。槽位（.mp-nebula-slot /
 *  .mp-followers）清空但不销毁 —— panelVisibility 持有 .mp-followers 的注册
 *  柄，节点一旦重建恢复腿就指空；僵死 blocks 出 DOM（spec 钉 count=0），
 *  恢复时由 renderPanel 原地重建。头部模式钮照常可点（点了也回同一降级面
 *  —— 状态一致，不假活）。 */
function renderPanelLoadError() {
  if (!panelEl) return;
  const body = panelEl.querySelector('.mp-body');
  if (!body) return;
  for (const sel of ['.mp-nebula-slot', '.mp-followers', '.mp-groups-slot']) {
    const slot = body.querySelector(sel);
    if (!slot) continue;
    slot.replaceChildren();
    slot.style.display = 'none';
  }
  // 分叉横幅的可见性归 panelVisibility 管，降级态直接压掉（恢复渲染时
  // panelVisibility.update() 会重新裁决）。
  const banner = panelEl.querySelector('.mp-banner');
  if (banner) banner.style.display = 'none';
  let face = body.querySelector('.mp-load-error');
  if (!face) {
    face = document.createElement('div');
    face.className = 'mp-load-error';
    face.setAttribute('role', 'alert');
    const icon = document.createElement('div');
    icon.className = 'mp-load-error-icon';
    icon.innerHTML = statusIcon('circle-alert', 20, 'st-err');
    const title = document.createElement('div');
    title.className = 'mp-load-error-title';
    title.textContent = t('model.loadFailed');
    const hint = document.createElement('div');
    hint.className = 'mp-load-error-hint';
    hint.textContent = t('model.loadFailedHint');
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'cfg-btn cfg-btn-sm mp-load-error-retry';
    retry.textContent = t('model.retry');
    retry.addEventListener('click', () => {
      retry.disabled = true;
      void openModelPanelRefresh();
    });
    face.append(icon, title, hint, retry);
    body.appendChild(face);
  }
  face.style.display = '';
}

/** 台账 #937：带守卫的刷新腿（openModelPanel 与降级面重试共用）—— 渲染链
 *  任何异常都落到显式降级面（console 之外用户可见），面板绝不留在无声半死态。 */
async function openModelPanelRefresh() {
  try {
    await refreshPanel();
  } catch (e) {
    renderPanelLoadError();
  }
}

function renderPanel() {
  if (!panelEl) return;
  const nebula = /** @type {HTMLElement} */ (panelEl.querySelector('.mp-nebula-slot'));
  const followers = /** @type {HTMLElement} */ (panelEl.querySelector('.mp-followers'));
  if (!nebula || !followers) return;
  // 台账 #937：全角色读数失败 ⇒ 降级面替下 per-role blocks（partial 失败仍走
  // per-block「加载失败」—— 可用的部分照常可用，只有整体不可用才整面降级）。
  // 短路在造块之前：降级态不做无用功，setMode 反复点击幂等。
  if (allRolesUnreadable()) {
    renderPanelLoadError();
    return;
  }  // 恢复腿：出降级态先拆降级面 + 复原被降级面压掉的槽位可见性。
  panelEl.querySelector('.mp-load-error')?.remove();
  nebula.style.display = '';
  nebula.replaceChildren();
  followers.replaceChildren();
  const commit = (role) => { saveChain(role); };
  // 弹窗退场批③：手机层下钻态 = 只渲染被钻角色块（Nebula 槽与横幅让位），
  // 面板自带左上返回（popSheet）回列表；桌面 drilledRole 恒 null ⇒ 零变化。
  const drill = document.body.classList.contains('mobile-layer') ? drilledRole : null;
  if (drill) {
    nebula.style.display = 'none';
    followers.style.display = '';
    buildFollowerBlock(followers, drill, commit);
    const banner = panelEl.querySelector('.mp-banner');
    if (banner) banner.style.display = 'none';
    // 下钻态只看该角色 —— 生图分组让位（回列表时 renderPanel 重建）。
    const groupsSlot = /** @type {HTMLElement|null} */ (panelEl.querySelector('.mp-groups-slot'));
    if (groupsSlot) groupsSlot.style.display = 'none';
    return;
  }
  buildNebulaBlock(nebula, commit);
  FOLLOWER_ROLES.forEach(role => buildFollowerBlock(followers, role, commit));
  renderBannerContent();
  // 生图切换分组（与设置页节同一次产出；手机下钻态已让位）。
  renderPanelGroups();
  // Block visibility = f(mode, fork state) — the shared widget decides which
  // of the banner / follower group is showing.
  panelVisibility?.update();
}

async function refreshPanel() {
  // 角色链 + 两面状态投影并行读数（互不连坐：任一面失败只影响它自己那组）。
  await Promise.all([fetchAllRoles(), fetchFaceStatuses()]);
  renderPanel();
}

function setMode(mode) {
  panelMode = mode;
  panelEl?.querySelectorAll('.mp-mode-btn').forEach(btn => {
    const on = btn.dataset.mode === mode;
    btn.classList.toggle('on', on);
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
  });
  renderPanel();
}

function buildPanel() {
  const overlay = document.createElement('div');
  overlay.className = 'cfg-modal-overlay';
  overlay.id = 'model-panel-overlay';
  overlay.innerHTML = `
    <div class="model-panel ds-layer" tabindex="-1" role="dialog" aria-label="${esc(t('model.title'))}">
      <div class="mp-header">
        <span class="mp-title">${esc(t('model.title'))}</span>
        <div class="mp-modes" role="group" aria-label="${esc(t('model.title'))}">
          <button type="button" class="mp-mode-btn on" data-mode="unified" aria-pressed="true">${esc(t('model.modeUnified'))}</button>
          <button type="button" class="mp-mode-btn" data-mode="separate" aria-pressed="false">${esc(t('model.modeSeparate'))}</button>
        </div>
        <button type="button" class="mp-close panel-btn" title="×"><svg viewBox="0 0 12 12" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true"><path d="M3 3l6 6M9 3l-6 6"/></svg></button>
      </div>
      <div class="mp-banner" style="display:none"></div>
      <div class="mp-body">
        <div class="mp-nebula-slot"></div>
        <div class="mp-followers" style="display:none"></div>
        <div class="mp-groups-slot"></div>
      </div>
    </div>`;
  document.body.appendChild(overlay);
// 弹窗退场批②③：返回 = 钻入态先回角色列表，列表态再关面板。
  mobileSheetBack(overlay, () => {
    if (drilledRole) {
      const st = roleState.get(drilledRole);
      drilledRole = null;
      if (st) st.expanded = false;
      renderPanel();
      return;
    }
    closeModelPanel();
  });

  // The conditional-display pair: item 0 = divergence banner (unified mode ∧
  // at least one follower forked), item 1 = follower group (separate mode).
  panelVisibility = createDepVisibility({
    isVisible: (i) => (i === 0
      ? panelMode === 'unified' && forkedRoles().length > 0
      : panelMode === 'separate'),
  });
  panelVisibility.register(/** @type {HTMLElement} */ (overlay.querySelector('.mp-banner')));
  panelVisibility.register(/** @type {HTMLElement} */ (overlay.querySelector('.mp-followers')));

  const close = () => closeModelPanel();
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  overlay.querySelector('.mp-close')?.addEventListener('click', close);
  overlay.querySelector('.mp-mode-btn[data-mode="unified"]')?.addEventListener('click', () => setMode('unified'));
  overlay.querySelector('.mp-mode-btn[data-mode="separate"]')?.addEventListener('click', () => setMode('separate'));
  // Escape closes the panel (own listener — the global modal Escape handler
  // only manages #modal-overlay); removed with the panel.
  panelOnKey = (e) => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', panelOnKey);

  return overlay;
}

/** Open (or focus) the /model panel. UI-only command entry. */
export async function openModelPanel() {
  // Boundary: the panel is a singleton — a repeated /model focuses the open
  // instance instead of stacking another one.
  if (panelEl) {
    /** @type {HTMLElement} */ (panelEl.querySelector('.model-panel'))?.focus();
    return;
  }
  panelEl = buildPanel();
  /** @type {HTMLElement} */ (panelEl.querySelector('.model-panel'))?.focus();
  await openModelPanelRefresh();
}

function closeModelPanel() {
  if (panelOnKey) document.removeEventListener('keydown', panelOnKey);
  panelOnKey = null;
  panelEl?.remove();
  panelEl = null;
  panelVisibility = null;
}
