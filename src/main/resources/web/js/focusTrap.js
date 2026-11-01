// focusTrap.js — 焦点陷阱 / 关闭回焦 / 重渲保焦协议（keyboard-first 一期，2026-11-01）。
//
// 三个问题各对应一个能力：
//   ① 模态打开后 Tab 能穿到层外（实测：设置模态第一步 Tab 就逃到 #input）
//      → pushLayer / popLayer
//   ② 关闭后焦点掉到 BODY（实测 Esc 后 activeElement === body）
//      → 打开时记「稳定 key」，关闭时按 key 回焦
//   ③ innerHTML 重渲丢焦点（全树 628 处重写，实测旧节点 isConnected === false）
//      → captureFocusKey / restoreFocusKey：**焦点不存节点，存 key**
//
// 为什么 ③ 不能靠 MutationObserver 自动保焦：全局观察会在「用户主动点了别处」
// 时把焦点抢回来，产生难以复现的输入错位；且每帧重渲都触发，性能不可控。
// 显式接线（业务重渲前 capture、重渲后 restore）才是可维护的形态。
//
// 层栈（layer stack）：Esc 只由**栈顶**消费，避免与既有 20+ 处裸 Esc 监听打架
// —— 没有层的容器不注册任何 Esc 行为，既有行为逐字不变。

/** 可聚焦件的机械选择器（与 chat.js:2213 既有口径一致，另补 role 族）。 */
const FOCUSABLE_SEL = [
  'button:not([disabled])',
  'a[href]',
  'input:not([disabled])',
  'textarea:not([disabled])',
  'select:not([disabled])',
  '[contenteditable="true"]',
  '[role="button"]:not([aria-disabled="true"])',
  '[role="menuitem"]',
  '[role="option"]',
  '[role="checkbox"]',
  '[role="tab"]',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * @typedef {Object} FocusKey
 * @property {'id'|'data'|'path'} kind
 * @property {string} value
 */

/**
 * @typedef {Object} LayerOpts
 * @property {HTMLElement|null} [initial]   打开时要落焦的元素；缺省落焦容器本身
 * @property {(() => void)|null} [onEscape] 栈顶消费 Escape 时调用（capture 阶段，先于既有 bubble 监听）
 * @property {boolean} [contain]            true = 焦点被抢走后拉回容器（默认 false）
 * @property {string} [name]                调试/验收用层名
 */

/**
 * @typedef {Object} Layer
 * @property {HTMLElement} container
 * @property {string} name
 * @property {() => void} release
 */

/**
 * @typedef {Object} LayerEntry
 * @property {HTMLElement} container
 * @property {LayerOpts} opts
 * @property {FocusKey|null} restoreKey
 * @property {HTMLElement|null} restoreEl
 * @property {string} name
 */

/** @type {LayerEntry[]} */
const stack = [];
/** @type {boolean} */
let listening = false;

/**
 * 让任意元素可被程序化聚焦（不改 DOM 语义），并压下自带描边
 * （容器承接焦点时不该出现一个「画在盒子外面的大环」）。
 * @param {HTMLElement} el
 * @returns {HTMLElement}
 */
export function ensureFocusable(el) {
  if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
  el.classList.add('nf-trap-root');
  return el;
}

/**
 * 容器内当前可 Tab 到的元素（可见性 + tabindex 双重过滤）。
 * @param {HTMLElement} container
 * @returns {HTMLElement[]}
 */
export function focusables(container) {
  /** @type {HTMLElement[]} */
  const out = [];
  for (const node of container.querySelectorAll(FOCUSABLE_SEL)) {
    if (!(node instanceof HTMLElement)) continue;
    if (node.tabIndex < 0) continue;
    if (node.hasAttribute('disabled') || node.getAttribute('aria-disabled') === 'true') continue;
    if (node.closest('[hidden]')) continue;
    const r = node.getBoundingClientRect();
    if (!r.width || !r.height) continue; // display:none / 折叠态
    out.push(node);
  }
  return out;
}

/**
 * 在 root 内给当前焦点元素算一个**跨重渲稳定**的 key。
 * 优先级：`#id` → `[data-focus-key]` → DOM 路径（兜底，浅树可用）。
 * @param {HTMLElement} root
 * @returns {FocusKey|null} 焦点不在 root 内 ⇒ null
 */
export function captureFocusKey(root) {
  const el = document.activeElement;
  if (!(el instanceof HTMLElement) || !root.contains(el)) return null;
  if (el.id) return { kind: 'id', value: el.id };
  const dk = el.getAttribute('data-focus-key');
  if (dk) return { kind: 'data', value: dk };
  return { kind: 'path', value: domPath(el, root) };
}

/**
 * 重渲之后按 key 找回新树里的对应节点并落焦。
 * 找不到 → 回退到 root 本身（并派发 `nf-focus-fallback` 供业务观察）。
 * @param {HTMLElement} root
 * @param {FocusKey|null} key
 * @returns {boolean} 精确命中 true / 走了回退 false
 */
export function restoreFocusKey(root, key) {
  if (!key) return false;
  /** @type {HTMLElement|null} */
  let hit = null;
  if (key.kind === 'id') {
    const el = document.getElementById(key.value);
    hit = el instanceof HTMLElement && root.contains(el) ? el : null;
  } else if (key.kind === 'data') {
    const el = root.querySelector(`[data-focus-key="${cssEscape(key.value)}"]`);
    hit = el instanceof HTMLElement ? el : null;
  } else {
    hit = byPath(root, key.value);
  }
  if (hit && hit.isConnected) {
    hit.focus({ preventScroll: true });
    return true;
  }
  ensureFocusable(root);
  root.focus({ preventScroll: true });
  root.dispatchEvent(new CustomEvent('nf-focus-fallback', { bubbles: true, detail: { key } }));
  return false;
}

/**
 * @param {HTMLElement} el
 * @param {HTMLElement} root
 * @returns {string}
 */
function domPath(el, root) {
  /** @type {number[]} */
  const idx = [];
  let cur = /** @type {Element|null} */ (el);
  while (cur && cur !== root) {
    const parent = cur.parentElement;
    if (!parent) break;
    idx.unshift(Array.prototype.indexOf.call(parent.children, cur));
    cur = parent;
  }
  return idx.join('/');
}

/**
 * @param {HTMLElement} root
 * @param {string} path
 * @returns {HTMLElement|null}
 */
function byPath(root, path) {
  if (!path) return null;
  /** @type {Element} */
  let cur = root;
  for (const seg of path.split('/')) {
    const pick = cur.children[Number(seg)];
    if (!pick) return null;
    cur = pick;
  }
  return cur instanceof HTMLElement ? cur : null;
}

/**
 * @param {string} s
 * @returns {string}
 */
function cssEscape(s) {
  return String(s).replace(/["\\]/g, '\\$&');
}

// ── 层栈 ────────────────────────────────────────────────────────────────

/**
 * 打开一层：记住「谁有焦点」→ 落焦 → 装上 Tab 陷阱。
 * @param {HTMLElement} container
 * @param {LayerOpts} [opts]
 * @returns {Layer}
 */
export function pushLayer(container, opts = {}) {
  const prev = document.activeElement;
  const restoreEl = prev instanceof HTMLElement ? prev : null;
  const restoreKey = restoreEl && restoreEl.id
    ? /** @type {FocusKey} */ ({ kind: 'id', value: restoreEl.id })
    : null;

  /** @type {LayerEntry} */
  const entry = {
    container,
    opts,
    restoreEl,
    restoreKey,
    name: opts.name || container.id || String(container.className) || 'layer',
  };
  stack.push(entry);

  ensureFocusable(container);
  // 落焦到容器本身（而非首个控件）是**故意**的：确认类模态若默认落焦在确认钮上，
  // 一个 Enter 就会执行破坏性动作。容器承接焦点后，第一次 Tab 才进第一个控件。
  const target = opts.initial || container;
  target.focus({ preventScroll: true });

  install();
  return { container, name: entry.name, release: () => { popLayer(container); } };
}

/**
 * 关闭一层并回焦。
 * @param {HTMLElement} container
 * @returns {boolean} 是否真的弹出了东西
 */
export function popLayer(container) {
  const i = stack.findIndex((l) => l.container === container);
  if (i < 0) return false;
  const [entry] = stack.splice(i, 1);
  // 回焦：优先「元素还在文档里」，否则按 id 在新树里找（重渲场景）
  /** @type {HTMLElement|null} */
  let target = entry.restoreEl && entry.restoreEl.isConnected ? entry.restoreEl : null;
  if (!target && entry.restoreKey && entry.restoreKey.kind === 'id') {
    const el = document.getElementById(entry.restoreKey.value);
    if (el instanceof HTMLElement) target = el;
  }
  if (target) target.focus({ preventScroll: true });
  if (!stack.length) uninstall();
  return true;
}

/** 当前层栈深度（验收脚本消费）。 @returns {number} */
export function layerDepth() {
  return stack.length;
}

/** 栈顶层名（调试/验收）。 @returns {string|null} */
export function topLayerName() {
  return stack.length ? stack[stack.length - 1].name : null;
}

/** 清空层栈（测试收尾用）。 @returns {void} */
export function clearLayers() {
  stack.length = 0;
  uninstall();
}

/** @returns {void} */
function install() {
  if (listening) return;
  document.addEventListener('keydown', onLayerKeydown, true);
  document.addEventListener('focusin', onFocusIn, true);
  listening = true;
}

/** @returns {void} */
function uninstall() {
  if (!listening) return;
  document.removeEventListener('keydown', onLayerKeydown, true);
  document.removeEventListener('focusin', onFocusIn, true);
  listening = false;
}

/**
 * capture 阶段的层栈按键处理：Escape 只给**声明了 onEscape** 的栈顶，
 * Tab/Shift+Tab 在栈顶容器内首尾相接。
 * @param {KeyboardEvent} e
 * @returns {void}
 */
function onLayerKeydown(e) {
  const top = stack[stack.length - 1];
  if (!top || !top.container.isConnected) return;

  if (e.key === 'Escape' && typeof top.opts.onEscape === 'function') {
    e.preventDefault();
    e.stopPropagation();
    top.opts.onEscape();
    return;
  }
  if (e.key !== 'Tab') return;

  const items = focusables(top.container);
  if (!items.length) {
    e.preventDefault();
    top.container.focus({ preventScroll: true });
    return;
  }
  const first = items[0];
  const last = items[items.length - 1];
  const active = document.activeElement;
  if (!(active instanceof HTMLElement) || !top.container.contains(active)) {
    e.preventDefault();
    (e.shiftKey ? last : first).focus({ preventScroll: true });
    return;
  }
  if (!e.shiftKey && active === last) {
    e.preventDefault();
    first.focus({ preventScroll: true });
  } else if (e.shiftKey && active === first) {
    e.preventDefault();
    last.focus({ preventScroll: true });
  }
}

/**
 * 焦点被抢到层外时拉回（仅 contain:true 的层）。默认不开 —— 打开会让既有的
 * 异步落焦（如 showNewSessionModal 的 setTimeout modalInput.focus）被弹回。
 * @param {FocusEvent} e
 * @returns {void}
 */
function onFocusIn(e) {
  const top = stack[stack.length - 1];
  if (!top || top.opts.contain !== true) return;
  if (!top.container.isConnected) return;
  const t = e.target;
  if (t instanceof Node && top.container.contains(t)) return;
  top.container.focus({ preventScroll: true });
}
