// configDrill.js — 配置面层级下钻共享壳 · 桌面浮层半（配置面风格统一批，
// 2026-10-31 作者令：JeV/生图配置面参照 LLM 配置同款层级下钻——桌面浮层玻璃
// 面板 + 手机层级子页，同一内容面两端一致，不再各长各的）。
//
// 分工：
//   · 手机层 = mobile/settingsSub.js（capture 期入口行委托 → navController
//     push 层级子页，收编 #settings-modal 内的 .settings-collapse-body 节点）；
//   · 桌面层 = 本模块（capture 期入口行委托 → 透明承接层 + cfg-modal 玻璃盒，
//     收编同一枚内容节点——节点内事件绑定随节点走，内容面零副本）。
//
// 机制（settingsSub adopt/restore 锚点惯例的桌面版）：
//   · 内容节点（#jev-advance-body 等）常驻设置页 DOM、默认 hidden；
//   · 浮层打开 = 节点收编进 .config-drill-body（hidden=false），关闭 = 按锚点
//     归还（hidden=true）；
//   · 设置页重渲（WS echo 的 renderSettings）会再造内容节点副本——
//     syncConfigDrills() 在渲染尾按 id 重取最新节点收编、摘除旧副本
//     （同 settingsSub 同步腿语义：重渲重建 + 重绑，输入态不跨重渲）；
//   · 跨档重排（视口缩进手机层）：matchMedia 变化即关全部浮层——手机侧入口
//     由 settingsSub 承接，同一节点零冲突。
//
// 模块纪律：叶子面——只 import i18n/utils/popSheet，禁回边（与 navController
// 零耦合，与 settingsSub 以 body class 互斥分工）。

import { t } from './i18n.js';
import { escapeHtml } from './utils.js';
import { mobileSheetBack } from './mobile/popSheet.js';

/**
 * @typedef {Object} DrillDef
 * @property {string} toggleSel 入口行选择器（与 settingsSub 的 toggleSel 同款锚）
 * @property {string} bodyId    内容节点 id
 * @property {string} titleKey  i18n 键（浮层标题，与手机子页标题同源文案）
 */

/** @type {Map<string, DrillDef & {overlay: HTMLElement|null, anchor: {parent: Node|null, next: Node|null}|null, onKey: ((e: KeyboardEvent) => void)|null}>} */
const drills = new Map();

let delegated = false;

/**
 * 注册一枚层级下钻配置面（幂等；toggleSel 为主键，重复注册只换定义）。
 * @param {DrillDef} def
 */
export function registerConfigDrill(def) {
  if (!def || !def.toggleSel || !def.bodyId || !def.titleKey) return;
  const prev = drills.get(def.toggleSel);
  drills.set(def.toggleSel, {
    ...def,
    overlay: prev?.overlay ?? null,
    anchor: prev?.anchor ?? null,
    onKey: prev?.onKey ?? null,
  });
  initDelegation();
}

function initDelegation() {
  if (delegated) return;
  delegated = true;
  // 入口行委托：capture 期（先于行内任何 bubble 监听）；手机层不拦——
  // settingsSub 的同选择器委托接管（两委托以 body.mobile-layer 互斥）。
  document.addEventListener('click', (e) => {
    if (document.body.classList.contains('mobile-layer')) return;
    const el = e.target instanceof Element ? e.target : null;
    if (!el) return;
    for (const [sel, def] of drills) {
      if (el.closest(sel)) {
        e.preventDefault();
        e.stopPropagation();
        openConfigDrill(sel);
        return;
      }
    }
  }, true);
  // 跨档重排：缩进手机层 ⇒ 关全部浮层（层级子页接管，模态族不残留）。
  const mq = window.matchMedia('(max-width: 768px)');
  const onCross = (e) => { if (e.matches) closeAllConfigDrills(); };
  if (typeof mq.addEventListener === 'function') mq.addEventListener('change', onCross);
  else if (typeof mq.addListener === 'function') mq.addListener(onCross);
}

/**
 * 打开浮层：透明承接层 + cfg-modal 玻璃盒，收编内容节点进盒体。
 * 已开 ⇒ 聚焦（单例不叠层）。
 * @param {string} toggleSel
 */
export function openConfigDrill(toggleSel) {
  const def = drills.get(toggleSel);
  if (!def) return;
  if (def.overlay?.isConnected) {
    /** @type {HTMLElement|null} */ (def.overlay.querySelector('.config-drill-modal'))?.focus();
    return;
  }
  const node = document.getElementById(def.bodyId);
  if (!(node instanceof HTMLElement)) return;
  const overlay = document.createElement('div');
  overlay.className = 'cfg-modal-overlay config-drill-overlay';
  overlay.innerHTML = `
    <div class="cfg-modal config-drill-modal" role="dialog" aria-modal="true"
         aria-label="${escapeHtml(t(def.titleKey))}" tabindex="-1">
      <div class="config-drill-head">
        <span class="config-drill-title">${escapeHtml(t(def.titleKey))}</span>
        <button type="button" class="panel-btn config-drill-close" title="${escapeHtml(t('modal.close'))}" aria-label="${escapeHtml(t('modal.close'))}"><svg viewBox="0 0 12 12" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true"><path d="M3 3l6 6M9 3l-6 6"/></svg></button>
      </div>
      <div class="config-drill-body"></div>
    </div>`;
  document.body.appendChild(overlay);
  def.overlay = overlay;
  def.anchor = { parent: node.parentElement, next: node.nextSibling };
  const slot = /** @type {HTMLElement} */ (overlay.querySelector('.config-drill-body'));
  node.hidden = false;
  slot.appendChild(node);
  // 返回语义挂 popSheet（幂等；非手机层不注入，桌面零痕迹）。
  mobileSheetBack(overlay, () => closeConfigDrill(toggleSel));

  const close = () => closeConfigDrill(toggleSel);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  overlay.querySelector('.config-drill-close')?.addEventListener('click', close);
  // Escape = 只关当前这一层浮层：capture 期 stopPropagation 截停设置页壳等
  // 其他 document Esc 监听（栈式弹层语义，逐层退场）；通用确认面（#modal-
  // overlay，z 更高）在场时让路——确认面自己的 Esc 处理优先。
  def.onKey = (e) => {
    if (e.key !== 'Escape') return;
    if (document.getElementById('modal-overlay')?.classList.contains('on')) return;
    e.stopPropagation();
    close();
  };
  document.addEventListener('keydown', def.onKey, true);
  /** @type {HTMLElement} */ (overlay.querySelector('.config-drill-modal'))?.focus();
}

/**
 * 关闭浮层：内容节点按锚点归还设置页（hidden=true）；焦点回入口行。
 * @param {string} toggleSel
 */
export function closeConfigDrill(toggleSel) {
  const def = drills.get(toggleSel);
  if (!def) return;
  if (def.onKey) document.removeEventListener('keydown', def.onKey);
  def.onKey = null;
  const overlay = def.overlay;
  def.overlay = null;
  const anchor = def.anchor;
  def.anchor = null;
  // 先取节点再摘浮层——内容节点住在浮层子树里，overlay.remove() 之后
  // getElementById 即失联（归还腿成空操作）。
  const node = document.getElementById(def.bodyId);
  overlay?.remove();
  if (node instanceof HTMLElement) {
    if (anchor?.parent?.isConnected) {
      try {
        anchor.parent.insertBefore(node, anchor.next && anchor.next.isConnected ? anchor.next : null);
      } catch { /* 树已变：设置页重渲会再造副本，放弃归还 */ }
    }
    node.hidden = true;
  }
  /** @type {HTMLElement|null} */ (document.querySelector(toggleSel))?.focus?.();
}

/** 全部浮层关闭（跨档重排用）。 */
export function closeAllConfigDrills() {
  for (const sel of [...drills.keys()]) closeConfigDrill(sel);
}

/**
 * 设置页重渲后的浮层同步腿（renderSettings 尾调用）：浮层开着而内容节点
 * 被重渲换掉（收编中的旧副本已脱离文档）⇒ 按 id 重取最新节点收编，旧副本
 * 摘除——与 settingsSub.syncSettingsSubFaces 同一语义。
 */
export function syncConfigDrills() {
  for (const [sel, def] of drills) {
    if (!def.overlay?.isConnected) continue;
    const slot = def.overlay.querySelector('.config-drill-body');
    const cur = slot?.firstElementChild;
    if (cur && cur.isConnected && cur.id === def.bodyId) continue;
    const node = document.getElementById(def.bodyId);
    if (!(node instanceof HTMLElement) || !slot) continue;
    for (const old of slot.querySelectorAll(`#${def.bodyId}`)) {
      if (old !== node) old.remove();
    }
    def.anchor = { parent: node.parentElement, next: node.nextSibling };
    node.hidden = false;
    slot.appendChild(node);
  }
}

/** 判空辅助：该入口的浮层是否开着（spec 探针面）。 */
export function isConfigDrillOpen(toggleSel) {
  return !!drills.get(toggleSel)?.overlay?.isConnected;
}
