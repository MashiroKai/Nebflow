// backSwipe.js — 次级面板「从左向右滑返回」单点手势层（作者令 2026-11-07 12:17
// 逐字：「然后全局次级面板的ui交互 都应该要能支持从左向右滑返回上一层」）。
//
// 病根（改前实证，见批报告 §件二 清单）：返回手势只有一条腿 ——
// js/mobile/navController.js 的 bindEdgeBack 绑定在**手机层页面栈**（stack 层，
// 详情页 push/pop）上。次级面板（设置 / 搜索 / 记忆 / 规则 / 确认框 / pop sheet
// 详情页…）是**浮层**（.ds-overlay / .mfs 族），根本不在那条栈里 ⇒ 在设置、提供方
// 详情、密钥管理等面板上右滑，要么毫无反应，要么被下层页面栈的手势抢走（把用户
// 想关的面板留在原地、反而退出一层页面）。
//
// 本模块 = **单点手势层 + 面板栈**（不新起第二套手势实现）：
//   · 登记面：pushBackHandler(panelId, onBack, { root }) / popBackHandler(panelId) /
//     releaseAll()；每个登记项 = 「这个面板此刻可以被右滑关闭，关闭动作就是
//     onBack()」。栈语义（后进先出）保证只关**最上面那一个**。
//   · 手势面：document 捕获相 pointerdown/move/up/cancel 一套；左缘带起手（触屏
//     20px = iOS 同档 / 指针设备 64px = 鼠标好点），轴锁 ≥8px 且必须向右，
//     跟手位移带阻尼（40% 视口宽内 1:1，超出 0.35 递推 —— 与 navController
//     edgeDamp 同一套手感，数值逐字同源），提交阈 = dx > max(80, w*0.22) 或
//     （dx > 28 且 dt < 200ms）；不过阈 = 220ms 弹簧回位；pointercancel（系统抢走
//     指针）= 回位不提交。
//   · 不误触：横向滚动区（[data-hscroll] / .ask-col / .note-body / textarea /
//     input / .cr-capsule / .mf-strip / [contenteditable]）起手的手势一律不认领；
//     纵向意图（|dy| ≥ |dx|）当场让位给页面滚动。
//   · 桌面：鼠标拖拽同一条腿（无需触屏）；Esc = 关最上面那一个（键盘用户的等价
//     通路，与既有模态 Esc 收口不双关 —— 只有登记项存在时才接管）。
//   · 与主会话/页面栈的关系（作者「与主会话手势不冲突时主会话优先」）：本层登记
//     项非空即 `backSwipeOwns()` 为真，navController.bindEdgeBack 在 pointerdown
//     时据此让路 —— 浮层在，右滑关浮层；浮层关完，右滑回到页面栈语义。
//   · prefers-reduced-motion：不给跟手位移与弹簧（松手按判定直接提交/取消）。
//
// 自动接线（installBackSwipe）：按二级面板清单观察各面板的开合（class/style/
// hidden 变化），开 → pushBackHandler(id, () => 点该面板既有的关闭钮)，关 →
// popBackHandler(id)。这条链路里**没有第二套关闭逻辑** —— 面板怎么关，还是它
// 自己的关闭钮说了算（滑返只是把那颗钮按剧本点一次）。

const EDGE_BAND_TOUCH = 20;   // 触屏左缘带宽（iOS 同档；navController 同值）
const EDGE_BAND_POINTER = 64; // 指针设备（鼠标/触控笔）起手带宽 —— 鼠标 20px 太难命中
const AXIS_LOCK = 8;          // 轴锁阈值（与 navController 同参）
const FLICK_MS = 200;         // 快弹窗
const SPRING_MS = 220;        // 回位时长
const SPRING_EASE = 'cubic-bezier(0.16, 1, 0.3, 1)';

/** @type {{id: string, onBack: (reason: string) => void, root: HTMLElement | null}[]} */
const stack = [];
/** 手势态。 */
let g = null;
/** 已经装了监听（幂等）。 */
let installed = false;
let wiringStarted = false;

const reduceMotion = () => {
  try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch { return false; }
};

/** 登记一个可右滑返回的面板（后进先出 = 只关最上面那一个）。
 * @param {string} panelId @param {(reason: string) => void} onBack
 * @param {{root?: HTMLElement | null}} [opts] root = 跟手位移的对象（缺省 = 不跟手）
 * @returns {{release: () => void, id: string}} */
export function pushBackHandler(panelId, onBack, opts = {}) {
  installBackSwipe();
  const id = String(panelId || '');
  if (!id || typeof onBack !== 'function') return { id, release: () => {} };
  const exist = stack.findIndex((s) => s.id === id);
  if (exist >= 0) stack.splice(exist, 1);          // 幂等：同一面板重开 = 挪到栈顶
  const entry = { id, onBack, root: opts.root || null };
  stack.push(entry);
  document.documentElement.dataset.backSwipeDepth = String(stack.length);
  return {
    id,
    release: () => popBackHandler(id),
  };
}

/** 注销（面板关闭/销毁时调；不在栈里 = 零动作）。 */
export function popBackHandler(panelId) {
  const id = String(panelId || '');
  const i = stack.findIndex((s) => s.id === id);
  if (i >= 0) stack.splice(i, 1);
  document.documentElement.dataset.backSwipeDepth = String(stack.length);
  if (!stack.length) cleanupGesture();
}

/** 清掉「根元素已不可见/已卸载」的残留登记项（面板自己关掉但没调 pop 的情况）。
 *  只清**带 root 的**登记项 —— 无 root 的（纯逻辑面板）由调用方显式 pop。 */
function prune() {
  for (let i = stack.length - 1; i >= 0; i--) {
    const s0 = stack[i];
    if (!s0.root) continue;
    const el = s0.root;
    if (!el.isConnected || !(el.getBoundingClientRect().width > 0)) {
      stack.splice(i, 1);
    }
  }
  document.documentElement.dataset.backSwipeDepth = String(stack.length);
}

/** 当前栈顶（无 = null）。 */
export function topBackHandler() {
  prune();
  return stack.length ? stack[stack.length - 1] : null;
}

/** 是否有可返回的面板（navController 据此让路 —— 浮层优先于页面栈）。 */
export function hasBackHandler() {
  prune();
  return stack.length > 0;
}

/** 关最上面那一个（Esc / 滑返提交走同一条）。@returns {boolean} */
export function runTopBack(reason = 'back') {
  const top = topBackHandler();
  if (!top) return false;
  try { top.onBack(reason); } catch { /* 面板自己的关闭腿出错不阻塞栈 */ }
  return true;
}

/** 手势认领期（navController.bindEdgeBack 在 pointerdown 时读它）。 */
export function backSwipeOwns() {
  return !!g && g.claimed;
}

/* ── 手势实现 ─────────────────────────────────────────────────────────── */

const HSCROLL_SEL = [
  '[data-hscroll]', '.ask-col', '.ask-colwrap', '.note-body', '.cr-capsule',
  '.mf-strip', '.mfs-strip', 'textarea', 'input', 'select', '[contenteditable="true"]',
].join(',');

const damp = (dx, w) => {
  const cap = w * 0.4;
  return dx <= cap ? dx : cap + (dx - cap) * 0.35;
};

const clearTransform = (el) => {
  if (!el) return;
  el.style.transition = '';
  el.style.transform = '';
  delete el.dataset.backSpring;
};

function springBack(el) {
  if (!el || !el.isConnected || reduceMotion()) { clearTransform(el); return; }
  el.dataset.backSpring = '1';
  el.style.transition = `transform ${SPRING_MS}ms ${SPRING_EASE}`;
  el.style.transform = 'translate3d(0,0,0)';
  setTimeout(() => {
    if (el.dataset.backSpring !== '1') return;    // 新一轮手势已夺回清理权
    delete el.dataset.backSpring;
    el.style.transition = '';
    el.style.transform = '';
  }, SPRING_MS + 20);
}

function cleanupGesture() {
  if (g && g.root && g.root.isConnected) springBack(g.root);
  g = null;
}

function onDown(e) {
  if (g) return;
  if (e.pointerType === 'mouse' && e.button !== 0) return;
  const top = topBackHandler();
  if (!top) return;
  if (e.clientX > (e.pointerType === 'touch' ? EDGE_BAND_TOUCH : EDGE_BAND_POINTER)) return;
  const t = /** @type {Element|null} */ (e.target && e.target.nodeType === 1 ? e.target : null);
  if (t && t.closest(HSCROLL_SEL)) return;        // 横向滚动区/输入框起手：不认领
  // 面板自带 root 时，起手点必须落在面板里（面板不铺满视口也不误伤背后内容）。
  if (top.root && t && !top.root.contains(t)) return;
  g = { id: e.pointerId, x0: e.clientX, y0: e.clientY, t0: Date.now(), claimed: false, entry: top, root: top.root || null };
}

function onMove(e) {
  if (!g || e.pointerId !== g.id) return;
  const dx = e.clientX - g.x0;
  const dy = e.clientY - g.y0;
  if (!g.claimed) {
    if (Math.abs(dx) <= AXIS_LOCK && Math.abs(dy) <= AXIS_LOCK) return;
    if (Math.abs(dy) >= Math.abs(dx) || dx <= 0) { g = null; return; }   // 纵向/左滑：让位
    g.claimed = true;
    document.documentElement.dataset.backSwipe = g.entry.id;
    document.body.classList.add('back-sweeping');
    if (reduceMotion()) return;
  }
  if (!g.root || !g.root.isConnected) return;
  const w = g.root.getBoundingClientRect().width || window.innerWidth;
  g.root.dataset.backSpring = '';                 // 夺回清理权
  g.root.style.transition = 'none';
  g.root.style.transform = `translate3d(${Math.max(0, damp(dx, w))}px,0,0)`;
}

function onUp(e) {
  if (!g || e.pointerId !== g.id) return;
  const st = g;
  const claimed = st.claimed;
  const dx = e.clientX - st.x0;
  const dy = e.clientY - st.y0;
  const dt = Date.now() - st.t0;
  g = null;
  document.body.classList.remove('back-sweeping');
  if (!claimed) return;
  const w = (st.root && st.root.isConnected ? st.root.getBoundingClientRect().width : 0) || window.innerWidth;
  /* 提交判据 = navController 同式（位移阈 max(80, 22%宽) 或 短距快弹），但**位移阈
     上限 240px**：浮层跟手对象是整层（视口宽），照抄 22%×1440 = 317px 会让桌面上
     得滑出小半个屏幕才算数 —— 手感阈按「面板尺度」封顶，不是把 22% 改成常量。 */
  const commit = Math.abs(dy) < Math.abs(dx)
    && (dx > Math.max(80, Math.min(240, w * 0.22)) || (dx > 28 && dt < FLICK_MS));
  document.documentElement.dataset.backSwipeLast = JSON.stringify({ dx: Math.round(dx), dy: Math.round(dy), dt, w: Math.round(w), commit });
  if (commit) {
    if (st.root) clearTransform(st.root);
    delete document.documentElement.dataset.backSwipe;
    runTopBack('swipe');
  } else {
    springBack(st.root);
    delete document.documentElement.dataset.backSwipe;
  }
}

function onCancel(e) {
  if (!g || e.pointerId !== g.id) return;
  const st = g;
  g = null;
  document.body.classList.remove('back-sweeping');
  delete document.documentElement.dataset.backSwipe;
  if (st.claimed) springBack(st.root);
}

function onKey(e) {
  if (e.key !== 'Escape' || e.defaultPrevented) return;
  if (!stack.length) return;
  if (runTopBack('esc')) e.preventDefault();
}

/** 装手势（幂等）。自动接线也在这里初始化一次。 */
export function installBackSwipe() {
  if (installed) return;
  installed = true;
  document.addEventListener('pointerdown', onDown, true);
  document.addEventListener('pointermove', onMove, true);
  document.addEventListener('pointerup', onUp, true);
  document.addEventListener('pointercancel', onCancel, true);
  document.addEventListener('keydown', onKey);
  // 横向意图的 touchmove 截流（与 navController 同一条理由：浏览器滚动会把
  // pointer 流掐断；只在认领后、只在横向时 preventDefault）。
  document.addEventListener('touchmove', (e) => {
    if (!g || !g.claimed) return;
    const t = e.changedTouches && e.changedTouches[0];
    if (!t) return;
    if (Math.abs(t.clientX - g.x0) > Math.abs(t.clientY - g.y0)) e.preventDefault();
  }, { passive: false, capture: true });
}

/* ── 自动接线：二级面板清单 → 各自既有的关闭钮 ─────────────────────────
 * 表里每一项 = { overlay, close, root }：overlay/close 是选择器，root 是跟手位移
 * 的对象（缺省 = overlay 自身，浮层整层随手指右移）。
 * 开合观察 = 该 overlay 的可见性（display ≠ none ∧ 有尺寸）；变化即 push/pop。
 * 判据集中在这张表：新增面板只需在此登记一行，不写第二套逻辑。 */
const PANELS = [
  // 设置（含其子页；子页内部的返回由 settings 自己的按钮承担 —— 面板级滑返 = 关面板）
  { key: 'settings', overlay: '#settings-overlay', close: '#settings-modal-close' },
  // 搜索（消息/文件/图片/POP 记录）
  { key: 'search', overlay: '#search-overlay', close: '#search-modal-close' },
  // 记忆编辑
  { key: 'memory', overlay: '#memory-overlay', close: '#memory-modal-cancel' },
  // 项目规则
  { key: 'rules', overlay: '#rules-overlay', close: '#rules-modal-cancel' },
  // 确认族（modal-box：删除/冲突等二选一）
  { key: 'confirm', overlay: '#modal-overlay', close: '#modal-cancel' },
  { key: 'delete', overlay: '#delete-overlay', close: '#delete-cancel' },
  { key: 'conflict', overlay: '#conflict-overlay', close: '#conflict-cancel' },
];

const visible = (el) => {
  if (!el || !el.isConnected) return false;
  const cs = getComputedStyle(el);
  if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity === 0) return false;
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0;
};

const wired = new Map();

function syncPanels() {
  for (const p of PANELS) {
    const ov = document.querySelector(p.overlay);
    const on = visible(ov);
    const has = wired.get(p.key);
    if (on && !has) {
      const root = /** @type {HTMLElement|null} */ (ov);
      const closeBtn = p.close ? document.querySelector(p.close) : null;
      const handle = pushBackHandler(p.key, () => {
        closeBtn?.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
      }, { root });
      wired.set(p.key, handle);
    } else if (!on && has) {
      has.release();
      wired.delete(p.key);
    }
  }
}

/** 装自动接线（幂等；main.js 初始化时调一次即可）。 */
export function installPanelBackSwipeWire() {
  if (wiringStarted) return;
  wiringStarted = true;
  installBackSwipe();
  const mo = new MutationObserver(() => syncPanels());
  mo.observe(document.body, { attributes: true, attributeFilter: ['class', 'style', 'hidden'], subtree: true, childList: true });
  syncPanels();
}

/** 供 popSheet 等「自己创建面板」的模块显式登记（同一栈，不另起一套）。 */
export function registerOverlayBack(panelId, onBack, root) {
  return pushBackHandler(panelId, onBack, { root });
}
