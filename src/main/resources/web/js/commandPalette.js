// commandPalette.js — 命令面板 MVP（keyboard-first 一期，2026-11-01）。
//
// 为什么需要：全树 108 处 keydown 散在 52 个文件里，键位增长必然撞键。面板是
// 「不撞键的兜底通道」——高频动作给直键，长尾动作只进面板，两者指向**同一个
// 命令 id**（keymap 的直键 handler 也是查命令表执行），不存在两套真理。
//
// MVP 能力：开/关、输入即搜、↑/↓ 漫游、Enter 执行、Esc 关并回焦、分组标题、
// 禁用项。**不做**：文件跳转（Ctrl+P 是浏览器打印保留键，另议）、最近使用、
// 多步命令。
//
// a11y 形态：组合框（combobox）—— 焦点始终在搜索框，↑/↓ 只移动
// `aria-activedescendant` 指向的高亮项，不是「真实焦点」。搜索型列表必须让
// 输入框持续接收键入，这是标准做法；菜单类（右键菜单）才用真实焦点。

import { pushLayer } from './focusTrap.js';
import { listShortcuts, getMode } from './keymap.js';

/**
 * @typedef {Object} Command
 * @property {string} id
 * @property {string} title
 * @property {string} [group]
 * @property {string[]} [keywords]
 * @property {boolean|(() => boolean)} [enabled]
 * @property {() => void} run
 */

/**
 * @typedef {Object} Row
 * @property {Command|null} cmd
 * @property {string} id
 * @property {string} title
 * @property {string} group
 * @property {string} keyHint
 * @property {boolean} disabled
 * @property {() => void} run
 */

/** @type {Map<string, Command>} */
const commands = new Map();

/**
 * 注册一个命令。
 * @param {Command} cmd
 * @returns {() => void} 注销
 */
export function registerCommand(cmd) {
  if (!cmd || !cmd.id || typeof cmd.run !== 'function') {
    throw new Error('commandPalette: registerCommand 需要 { id, title, run }');
  }
  commands.set(cmd.id, cmd);
  return () => { commands.delete(cmd.id); };
}

/**
 * 按 id 执行命令（直键 handler 也走这里 —— 单一执行入口）。
 * @param {string} id
 * @returns {boolean} 是否真的执行了
 */
export function runCommand(id) {
  const cmd = commands.get(id);
  if (!cmd) return false;
  const enabled = typeof cmd.enabled === 'function' ? cmd.enabled() : cmd.enabled !== false;
  if (!enabled) return false;
  cmd.run();
  return true;
}

/** 命令表快照（面板与验收脚本消费）。 @returns {Command[]} */
export function listCommands() {
  return [...commands.values()];
}

/** @type {HTMLElement|null} */
let overlay = null;
/** @type {HTMLInputElement|null} */
let inputEl = null;
/** @type {HTMLElement|null} */
let listEl = null;
/** @type {HTMLElement|null} */
let emptyEl = null;
/** @type {Row[]} 全量候选 */
let rows = [];
/** @type {Row[]} 当前过滤后的候选（索引 = 面板行号） */
let visibleRows = [];
/** @type {number} */
let active = 0;
/** @type {import('./focusTrap.js').Layer|null} */
let layer = null;

/**
 * 面板是否打开。
 * @returns {boolean}
 */
export function isPaletteOpen() {
  return !!overlay && overlay.isConnected && !overlay.hidden;
}

/** 惰性建 DOM（只在第一次打开时建，不污染首帧）。 @returns {void} */
function build() {
  if (overlay) return;
  overlay = document.createElement('div');
  overlay.id = 'nf-palette-overlay';
  overlay.className = 'nf-palette-overlay';
  overlay.hidden = true;
  overlay.innerHTML =
    '<div class="nf-palette" role="dialog" aria-modal="true" aria-label="命令面板">' +
      '<div class="nf-palette-head">' +
        '<input id="nf-palette-input" class="nf-palette-input" type="text" autocomplete="off" spellcheck="false"' +
        ' role="combobox" aria-expanded="true" aria-controls="nf-palette-list" aria-autocomplete="list"' +
        ' placeholder="输入命令名…（↑↓ 选择 · Enter 执行 · Esc 关闭）">' +
      '</div>' +
      '<div id="nf-palette-list" class="nf-palette-list" role="listbox" aria-label="命令"></div>' +
      '<div class="nf-palette-empty" hidden>没有匹配的命令</div>' +
    '</div>';
  document.body.appendChild(overlay);

  inputEl = /** @type {HTMLInputElement} */ (overlay.querySelector('#nf-palette-input'));
  listEl = /** @type {HTMLElement} */ (overlay.querySelector('#nf-palette-list'));
  emptyEl = /** @type {HTMLElement} */ (overlay.querySelector('.nf-palette-empty'));

  inputEl.addEventListener('input', () => render());
  inputEl.addEventListener('keydown', onInputKeydown);
  overlay.addEventListener('mousedown', (e) => { if (e.target === overlay) closePalette(); });
  listEl.addEventListener('click', (e) => {
    const t = /** @type {HTMLElement} */ (e.target);
    const row = t.closest('.nf-palette-row');
    if (!(row instanceof HTMLElement)) return;
    const idx = Number(row.dataset.idx);
    if (Number.isFinite(idx)) runRow(idx);
  });
}

/** 收集候选：命令表 + 已注册快捷键（快捷键行只展示键位，不可执行）。 @returns {void} */
function collect() {
  /** @type {Row[]} */
  const out = [];
  for (const cmd of commands.values()) {
    const enabled = typeof cmd.enabled === 'function' ? cmd.enabled() : cmd.enabled !== false;
    out.push({
      cmd, id: cmd.id, title: cmd.title, group: cmd.group || '命令', keyHint: '',
      disabled: !enabled, run: () => { closePalette(); cmd.run(); },
    });
  }
  const mode = getMode();
  for (const s of listShortcuts()) {
    if (s.mode !== '*' && s.mode !== mode) continue; // 当前模式下按不出来的键不列
    out.push({
      cmd: null, id: 'shortcut:' + s.id, title: s.description || s.id,
      group: '快捷键（直键）',
      keyHint: prettyCombo(s.combo) + (s.mode === '*' ? '' : ` · ${s.mode === 'edit' ? '输入态' : '导航态'}`),
      disabled: true, run: () => {},
    });
  }
  rows = out;
}

/**
 * `@BracketLeft` → `[`，`Mod+K` → `Ctrl+K`（mac 显示 ⌘，按平台判一次）。
 * @param {string} combo
 * @returns {string}
 */
function prettyCombo(combo) {
  const isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
  return String(combo).split('+').map((p) => {
    const low = p.toLowerCase();
    if (low === 'mod') return isMac ? '\u2318' : 'Ctrl';
    if (low === 'alt') return isMac ? '\u2325' : 'Alt';
    if (low === 'shift') return 'Shift';
    if (p.startsWith('@')) {
      const code = p.slice(1);
      if (code === 'BracketLeft') return '[';
      if (code === 'BracketRight') return ']';
      if (code === 'Space') return 'Space';
      return code.replace(/^Key/, '').replace(/^Digit/, '');
    }
    return p.replace(/^Arrow/, '');
  }).join('+');
}

/** @returns {string} */
function query() {
  return (inputEl && inputEl.value ? inputEl.value : '').trim().toLowerCase();
}

/**
 * 子串匹配（命令名 / 分组 / id / 键位任一命中）。
 * @param {Row} row
 * @param {string} q
 * @returns {number} -1 = 不命中；否则越小越靠前
 */
function score(row, q) {
  if (!q) return 0;
  const hay = [row.title, row.group, row.id, row.keyHint].join(' ').toLowerCase();
  return hay.indexOf(q);
}

/** 重画列表。 @returns {void} */
function render() {
  if (!listEl || !inputEl) return;
  const q = query();
  visibleRows = rows
    .map((row, i) => ({ row, i, s: score(row, q) }))
    .filter((x) => x.s >= 0)
    .sort((a, b) => (a.s === b.s ? a.i - b.i : a.s - b.s))
    .map((x) => x.row);

  listEl.innerHTML = '';
  if (!visibleRows.length) {
    if (emptyEl) emptyEl.hidden = false;
    inputEl.removeAttribute('aria-activedescendant');
    active = 0;
    return;
  }
  if (emptyEl) emptyEl.hidden = true;

  let lastGroup = '';
  visibleRows.forEach((row, i) => {
    if (row.group !== lastGroup) {
      lastGroup = row.group;
      const g = document.createElement('div');
      g.className = 'nf-palette-group';
      g.textContent = row.group;
      listEl.appendChild(g);
    }
    const el = document.createElement('div');
    el.className = 'nf-palette-row' + (row.disabled ? ' is-disabled' : '');
    el.id = 'nf-palette-row-' + i;
    el.dataset.idx = String(i);
    el.setAttribute('role', 'option');
    el.setAttribute('aria-selected', 'false');
    el.setAttribute('aria-disabled', String(row.disabled));
    const t = document.createElement('span');
    t.className = 'nf-palette-title';
    t.textContent = row.title;
    el.appendChild(t);
    if (row.keyHint) {
      const k = document.createElement('span');
      k.className = 'nf-palette-key';
      k.textContent = row.keyHint;
      el.appendChild(k);
    }
    listEl.appendChild(el);
  });

  active = Math.max(0, Math.min(active, visibleRows.length - 1));
  highlight();
}

/** 刷新高亮（aria-activedescendant 口径）。 @returns {void} */
function highlight() {
  if (!listEl || !inputEl) return;
  const items = listEl.querySelectorAll('.nf-palette-row');
  const cur = items[active];
  items.forEach((el, i) => el.setAttribute('aria-selected', String(i === active)));
  if (cur instanceof HTMLElement) {
    cur.scrollIntoView({ block: 'nearest' });
    if (cur.getAttribute('aria-disabled') !== 'true') inputEl.setAttribute('aria-activedescendant', cur.id);
  }
}

/**
 * 执行第 idx 行。
 * @param {number} idx
 * @returns {void}
 */
function runRow(idx) {
  const row = visibleRows[idx];
  if (!row || row.disabled) return;
  row.run();
}

/**
 * @param {KeyboardEvent} e
 * @returns {void}
 */
function onInputKeydown(e) {
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    if (visibleRows.length) { active = (active + 1) % visibleRows.length; highlight(); }
    return;
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault();
    if (visibleRows.length) { active = (active - 1 + visibleRows.length) % visibleRows.length; highlight(); }
    return;
  }
  if (e.key === 'Enter') {
    e.preventDefault();
    runRow(active);
  }
  // Esc 由层栈（focusTrap 的 onEscape）统一消费，这里不重复处理
}

/**
 * 打开面板。
 * @param {string} [initialQuery]
 * @returns {void}
 */
export function openPalette(initialQuery = '') {
  build();
  if (!overlay || !inputEl) return;
  overlay.hidden = false;
  active = 0;
  inputEl.value = initialQuery;
  collect();
  render();
  const panel = /** @type {HTMLElement} */ (overlay.querySelector('.nf-palette'));
  layer = pushLayer(panel, { initial: inputEl, contain: true, name: 'palette', onEscape: closePalette });
  inputEl.focus({ preventScroll: true });
  const n = inputEl.value.length;
  inputEl.setSelectionRange(n, n);
}

/**
 * 关闭面板（回焦由层栈负责：回到打开前的元素）。
 * @returns {void}
 */
export function closePalette() {
  if (!overlay) return;
  overlay.hidden = true;
  visibleRows = [];
  const l = layer;
  layer = null;
  if (l) l.release();
}

/** 开/关切换。 @returns {void} */
export function togglePalette() {
  if (isPaletteOpen()) closePalette();
  else openPalette();
}
