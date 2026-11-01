// mode.js — vim 式 meta/edit 双模态的**模式面**（keyboard-first 一期，2026-11-01）。
//
// 分工：keymap.js 只管「哪个键在哪个模式下算数」；本模块管「模式是什么、用户怎么
// 看见它、切换时焦点去哪」。两者靠 `nf-mode-change` 事件与 getMode/setMode 通信，
// 互不 import —— 一个纯数据层，一个纯 DOM 层。
//
// 三个视觉/交互不变式（作者 2026-11-01 裁定）：
//   ① 用户必须一眼知道当前在 meta 还是 edit。→ #nf-mode-chip 常驻可见
//      （meta = 描边淡显 NAV；edit = 蓝族填充 EDIT），且**可被视觉基线截图捕捉**。
//   ② meta 下 `[`/`]` 调整「中心区聚焦位置」、`Shift+[`/`Shift+*`]` 在侧边栏条目间移动。
//   ③ `Space` meta→edit、`Enter`（发送后）edit→meta。
//
// 焦点即模式：edit ⇔ 焦点在对话输入框；meta ⇔ 焦点在别处。这条等价关系是防
// 「以为在导航、其实把字打进了输入框」的机械保证 —— 不靠自觉，靠 focus 位置。

import { getMode, setMode } from './keymap.js';

/** 侧边栏「条目」候选（Phase 1 只做移动焦点，不改 Tab 序；Tab 序治理是二期）。 */
const SIDEBAR_ITEM_SEL = [
  '.explorer-item',
  '.fm-conv-row',
  '.fm-row',
  '.project-card',
  '[data-focus-key]',
].join(',');

/** @type {HTMLElement|null} */
let chipEl = null;

/** 语言无关的短标签（不引 i18n 表，避免一次性加 4 条键；形态是符号 + 单词）。 */
const LABEL = { meta: 'NAV', edit: 'EDIT' };

/**
 * 建模式指示器并挂进 #input-wrap（与既有 ASK/SKILL/COMPACT 三个 chip 同一宿主，
 * 但靠右 —— 那三个都在 left:8px，互不遮挡）。
 * @returns {void}
 */
function ensureChip() {
  if (chipEl && chipEl.isConnected) return;
  const wrap = document.getElementById('input-wrap');
  if (!wrap) return; // 输入区未装配（测试壳）⇒ 静默降级
  chipEl = document.createElement('div');
  chipEl.id = 'nf-mode-chip';
  chipEl.className = 'nf-mode-chip';
  chipEl.setAttribute('role', 'status');
  chipEl.setAttribute('aria-live', 'polite');
  chipEl.setAttribute('aria-label', 'keyboard mode');
  chipEl.title = 'Space 进入输入 · Enter 发送后回到导航 · [ ] 中心区 · Shift+[ ] 侧边栏';
  wrap.appendChild(chipEl);
  paint();
}

/** 把当前模式画到 chip 上。 @returns {void} */
function paint() {
  if (!chipEl) return;
  const mode = getMode();
  chipEl.dataset.mode = mode === 'edit' ? 'edit' : 'meta';
  chipEl.innerHTML = '';
  const glyph = document.createElement('span');
  glyph.className = 'nf-mode-chip-glyph';
  glyph.setAttribute('aria-hidden', 'true');
  glyph.textContent = mode === 'edit' ? '\u2038' : '[ ]';
  const word = document.createElement('span');
  word.className = 'nf-mode-chip-label';
  word.textContent = mode === 'edit' ? LABEL.edit : LABEL.meta;
  chipEl.append(glyph, word);
}

/**
 * 进入 edit：焦点落到对话输入框。
 * @returns {void}
 */
export function enterEdit() {
  const input = /** @type {HTMLTextAreaElement|null} */ (document.getElementById('input'));
  setMode('edit');
  if (!input) return;
  input.focus({ preventScroll: true });
  const n = input.value.length;
  try { input.setSelectionRange(n, n); } catch { /* 非 textarea 形态忽略 */ }
}

/**
 * 回到 meta：把焦点搬出输入框，落在一个**稳定的导航锚点**上。
 * 为什么不只是 blur()：blur 后 activeElement = body，`[`/`]` 的第一跳没有参照物，
 * 焦点环也没有落脚点。
 * @returns {void}
 */
export function enterMeta() {
  setMode('meta');
  const active = document.activeElement;
  if (active instanceof HTMLElement && active.id === 'input') active.blur();
  const anchor = document.getElementById('chat');
  if (anchor && anchor !== document.activeElement) makeStopFocusable(anchor).focus({ preventScroll: true });
}

/**
 * 让容器本身可被程序化聚焦（tabindex=-1）：不进 Tab 序、不改语义。
 * @param {HTMLElement} el
 * @returns {HTMLElement}
 */
function makeStopFocusable(el) {
  if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
  el.classList.add('nf-nav-stop');
  return el;
}

/**
 * 中心区聚焦环：消息流 → 画布当前文档。
 * 「调整中心会话框的聚焦位置」的落点：一次按键 = 焦点真的挪一格（画面上的
 * 焦点环跟着走）。若作者本意是「切换会话内容」，映射点同样只在这一处，二期
 * 接会话列表时改 `centerStops()` 即可，键位与仲裁规则不变。
 *
 * 🔴 不含对话输入框（刻意）：本层的不变式是「焦点落在输入框 ⇔ edit 态」，
 * 若把输入框也做成环上的一站，`]` 一落进去就自动转 edit，随后 `[`/`]` 变成
 * 打字符 —— 人在导航态被锁死。所以进输入框的**唯一门**是 Space（vim 里
 * normal 态怎么走都不会进 insert，同一个道理）。
 * @returns {HTMLElement[]}
 */
function centerStops() {
  /** @type {HTMLElement[]} */
  const stops = [];
  const chat = document.getElementById('chat');
  if (chat) stops.push(makeStopFocusable(chat));
  const canvas = document.getElementById('canvas-panel');
  if (canvas && canvas.offsetWidth > 0) stops.push(makeStopFocusable(canvas));
  return stops;
}

/**
 * 在停靠点序列里相对当前位置移动焦点。
 * @param {HTMLElement[]} stops
 * @param {1|-1} dir
 * @returns {boolean} 是否真的移动了
 */
function stepFocus(stops, dir) {
  if (!stops.length) return false;
  const active = /** @type {HTMLElement} */ (document.activeElement);
  let i = stops.indexOf(active);
  if (i < 0) i = dir > 0 ? -1 : 0; // 焦点不在环里：从端点进场
  const n = stops.length;
  const next = stops[((i + dir) % n + n) % n];
  next.focus({ preventScroll: true });
  next.dispatchEvent(new CustomEvent('nf-center-focus', { bubbles: true, detail: { dir } }));
  return true;
}

/**
 * 中心区上一/下一停靠点。
 * @param {1|-1} dir
 * @returns {void}
 */
export function stepCenter(dir) {
  stepFocus(centerStops(), dir);
}

/**
 * 侧边栏条目上一/下一。
 * Phase 1 只搬焦点：给非可聚焦条目临时补 `tabindex="-1"`（**不进 Tab 序**，
 * 因此不改变既有 Tab 链，也不改变任何像素）；Tab 序的整套治理（roving
 * tabindex + 方向键 + Enter 打开）是二期文件树键盘化那一批。
 * @param {1|-1} dir
 * @returns {void}
 */
export function stepSidebar(dir) {
  const panel = document.querySelector('#sidebar-panel .panel.active')
    || document.querySelector('#sidebar-panel');
  if (!(panel instanceof HTMLElement)) return;
  /** @type {HTMLElement[]} */
  const items = [];
  for (const el of panel.querySelectorAll(SIDEBAR_ITEM_SEL)) {
    if (!(el instanceof HTMLElement)) continue;
    if (el.closest('[hidden]')) continue;
    const r = el.getBoundingClientRect();
    if (!r.width || !r.height) continue;
    if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
    items.push(el);
  }
  // 面板内的常驻按钮（新建文件 / 打开文件夹……）也算条目，否则空面板无从落脚
  for (const el of panel.querySelectorAll('button')) {
    if (el instanceof HTMLElement && items.indexOf(el) < 0 && el.tabIndex >= 0) items.push(el);
  }
  if (!items.length) return;
  stepFocus(items, dir);
}

/**
 * 启动模式面：建 chip、跟随模式变化、把输入框焦点与模式绑死。
 * @returns {void}
 */
export function initMode() {
  ensureChip();
  document.addEventListener('nf-mode-change', paint);
  const input = document.getElementById('input');
  if (input) {
    input.addEventListener('focus', () => { setMode('edit'); });
    input.addEventListener('blur', () => { setMode('meta'); });
  }
  paint();
}
