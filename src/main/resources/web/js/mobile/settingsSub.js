// mobile/settingsSub.js — 弹窗退场批③（2026-10-31 作者令「不再有展开式配置：
// 苹果层级导航——点配置项进下一层级铺满屏配置、左上返回」，meFace 四级层级
// 为先例）：手机层设置页的三处 chevron 折叠体（STT 高级 / 旧工具结果清理 /
// Jev 插件分配）从「点行 → 同页展开」换代为「点行 → push 下一层级全屏页」。
//
// 机制（与 navController 的 overlay 收编 adoptOverlayFace 同一锚点惯例）：
//   · 折叠体节点（#stt-advance-body 等）在子页入栈时 re-parent 进页面体，
//     出栈归还设置页原位——节点内事件绑定随节点走，桌面渲染/绑定零改动；
//   · 设置页重渲（WS echo 的 refreshTtlSectionDom / renderSettings）会造出
//     折叠体的新副本——同步腿按 id 重新取最新节点收编，页面体里旧副本摘除
//     （同桌面「重渲重建 + 重绑」语义，输入态不跨重渲，与桌面折叠体一致）；
//   · 入口行的点击拦截 = document 捕获期委托（先于按钮自身 bubble 监听），
//     手机层命中 → stopPropagation（桌面展开监听因此不跑）+ push 子页；
//     桌面层不拦截，行为逐字不变。
//
// 模块纪律：不 import navController（push 由 navController 注入，meFace 同款）；
// 叶子面 = 无回边。

/** 设置层级子页注册表（id = 路由名，稳定 spec 面）。 */
export const SETTINGS_SUB = [
  { id: 'settings-stt', bodyId: 'stt-advance-body', toggleSel: '#stt-advance-toggle',
    zh: 'STT 高级设置', en: 'STT advanced settings' },
  { id: 'settings-ttl', bodyId: 'ttl-advance-body', toggleSel: '#ttl-advance-toggle',
    zh: '旧工具结果清理', en: 'Tool result cleanup' },
  { id: 'settings-jev', bodyId: 'jev-advance-body', toggleSel: '#jev-advance-toggle',
    zh: 'Jev（插件分配）', en: 'Jev (plugin allocation)' },
];

const SUB_BY_ID = new Map(SETTINGS_SUB.map((d) => [d.id, d]));
/** @type {Map<string, {parent: Node, next: Node|null}>} pageId → 归还锚点。 */
const anchors = new Map();

/** 注入的 push（navController init 时传入）。 */
/** @type {(pageId: string) => void} */
let pushFn = () => {};

/**
 * 子页面内容臂：把对应折叠体节点收编进页面体（幂等；取文档内该 id 的
 * 最新节点——设置页重渲后自动换绑新副本，旧副本摘除）。
 * @param {string} pageId
 * @param {HTMLElement} body 页面体
 */
export function buildSettingsSubFace(pageId, body) {
  const def = SUB_BY_ID.get(pageId);
  if (!def || !body) return;
  adopt(def, body);
}

/** 栈 ↔ 子页面同步（renderPages 汇合点）：在栈即收编，出栈/拆层即归还。 */
export function syncSettingsSubFaces(stack) {
  const mobile = typeof document !== 'undefined' &&
    document.body.classList.contains('mobile-layer');
  for (const def of SETTINGS_SUB) {
    const inStack = Array.isArray(stack) && stack.includes(def.id);
    if (mobile && inStack) {
      const section = document.querySelector(
        `#mobile-pages .mobile-page[data-page="${def.id}"]`);
      const bodyEl = section?.querySelector('.mobile-page-body');
      if (bodyEl instanceof HTMLElement) adopt(def, bodyEl);
    } else {
      restore(def);
    }
  }
}

/** @param {{id: string, bodyId: string}} def @param {HTMLElement} body */
function adopt(def, body) {
  const node = document.getElementById(def.bodyId);
  if (!(node instanceof HTMLElement)) return;
  // 摘旧副本（重渲残留），保最新节点单实例。
  for (const old of body.querySelectorAll(`#${def.bodyId}`)) {
    if (old !== node) old.remove();
  }
  if (node.parentElement === body) { node.hidden = false; return; }
  if (!anchors.has(def.id)) {
    anchors.set(def.id, { parent: node.parentElement, next: node.nextSibling });
  }
  node.hidden = false;
  body.appendChild(node);
}

/** @param {{id: string, bodyId: string, toggleSel: string}} def */
function restore(def) {
  const a = anchors.get(def.id);
  if (!a) return;
  anchors.delete(def.id);
  const node = document.getElementById(def.bodyId);
  if (!(node instanceof HTMLElement)) return;
  if (!a.parent || !a.parent.isConnected) return;
  try {
    a.parent.insertBefore(node, a.next && a.next.isConnected ? a.next : null);
    // 桌面开合态还原：折叠态以入口行 aria-expanded 为准（session 内存旗标的
    // DOM 读数）——收编期 hidden=false 过，直接归还会留下「体展开、头收起」
    // 的错位。
    const tog = document.querySelector(def.toggleSel);
    node.hidden = tog?.getAttribute('aria-expanded') !== 'true';
  } catch { /* 树已变：放弃归还（节点仍在页面体里，设置页重渲会再造副本） */ }
}

/**
 * 初始化：注入 push + document 捕获期入口行委托（只拦手机层；桌面原样穿透）。
 * @param {{push: (pageId: string) => void}} nav
 */
export function initSettingsSub(nav) {
  if (nav && typeof nav.push === 'function') pushFn = nav.push;
  document.addEventListener('click', (e) => {
    if (!document.body.classList.contains('mobile-layer')) return;
    const t = e.target instanceof Element ? e.target : null;
    if (!t) return;
    for (const def of SETTINGS_SUB) {
      if (t.closest(def.toggleSel)) {
        // 先于按钮自身的展开监听（capture 期 stopPropagation 全链截停）。
        e.preventDefault();
        e.stopPropagation();
        pushFn(def.id);
        return;
      }
    }
  }, true);
}
