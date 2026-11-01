// keymap.js — 全局快捷键注册器（keyboard-first 一期，2026-11-01）。
//
// 为什么存在：今天全树 108 处 `addEventListener('keydown')` 散在 52 个文件里，
// 没有任何「谁拥有这个 combo」的概念 —— 冲突只能靠注释口头约定。本模块把
// 「一条 combo ↔ 一个命令」抽成唯一真理源：注册、注销、冲突检测、按场景启停、
// **按模式分流**（vim 式 meta/edit 双模态，作者 2026-11-01 裁定）。
//
// 硬纪律（不破坏现状）：只处理**自己注册过的** combo。未命中的按键一律不碰
// 事件（不 preventDefault / 不 stopPropagation），既有的 108 处裸监听照旧工作。
// 迁移期规则 = 一条 combo 只有一个所有者，迁一条删一条旧监听。
//
// 双模态：同一物理键在 meta（导航态）与 edit（输入态）下可以是**两条完全不同的
// 命令**。注册时声明 `mode`；缺省 `'*'` = 两态都生效。冲突检测只在「mode 相交」
// 时报错，所以 `[` 在 meta 与 `[` 在 edit 可以共存。
//
// 用法：
//   import { registerShortcut, setMode, getMode } from './keymap.js';
//   const off = registerShortcut({
//     id: 'nav.prev', combo: '@BracketLeft', mode: 'meta', scope: 'global',
//     description: '中心区：上一个会话', handler: () => focusPrevCenter(),
//   });
//
// combo 文法：`[Mod+][Ctrl+][Alt+][Shift+]<token>`，大小写不敏感。
//   · 普通 token 按 **字符**（e.key）匹配：`Mod+K`、`Alt+ArrowUp`、`Shift+?`
//   · `@` 前缀 token 按 **物理键**（e.code）匹配：`@BracketLeft`、`@BracketRight`、
//     `@Space`、`@KeyJ` —— 全角/半角、输入法、键盘布局都不影响；
//     【】在中文输入法下是 BracketLeft/BracketRight 的**显示形**，物理键不变。
//   · Mod = Windows/Linux 的 Ctrl、macOS 的 Cmd（沿用全仓 `e.metaKey || e.ctrlKey` 口径）。

import { isImeComposing } from './imeGuard.js';

/**
 * vim 式双模态：meta = 导航态，edit = 输入态，'*' = 两态通用。
 * @typedef {'meta'|'edit'|'*'} KeyMode
 */

/**
 * @typedef {Object} ShortcutSpec
 * @property {string} id           唯一 id（同 id 重复注册 = 覆盖）
 * @property {string} combo        combo 文法见文件头（`@` 前缀 = 按物理键 e.code）
 * @property {string} description  人读描述（命令面板 / 速查视图消费）
 * @property {(e: KeyboardEvent) => void} handler
 * @property {string} [scope]      场景名，缺省 'global'（global 恒激活）
 * @property {KeyMode} [mode]      生效模式，缺省 '*'（两态通用）
 * @property {string} [group]      命令面板分组名
 * @property {string[]} [aliases]  额外 combo（同一 handler 的第二键位）
 * @property {boolean} [allowInInput] true = 焦点在输入框时也生效（默认 false）
 * @property {boolean} [preventDefault] 默认 true
 * @property {boolean} [stopPropagation] 默认 true
 */

/** @typedef {ShortcutSpec & {scope: string, mode: KeyMode, canonical: string[]}} Entry */

/** @type {Map<string, Entry[]>} canonical combo → entries */
const byCombo = new Map();
/** @type {Map<string, Entry>} */
const byId = new Map();
/** @type {Set<string>} 已激活的场景（'global' 永不入此集合，恒生效） */
const activeScopes = new Set();
/** @type {KeyMode} */
let currentMode = 'meta';

/** 修饰键别名归一（Ctrl / Cmd / Meta 统一收敛成 Mod）。 */
const MOD_ALIASES = new Set(['mod', 'cmd', 'meta', 'ctrl', 'control', 'command']);

/**
 * 单键归一：单字符键小写；命名键（Escape/ArrowUp/BracketLeft）小写全拼。
 * @param {string} raw
 * @returns {string}
 */
function normKey(raw) {
  return String(raw).trim().toLowerCase();
}

/**
 * 把 combo 文法解析成规范串。
 * 物理键 token 以 `c:` 开头（如 `c:bracketleft`），字符键以 `k:` 开头（如 `k:b`）。
 * @param {string} combo
 * @returns {string}
 */
export function canonicalCombo(combo) {
  const parts = String(combo).split('+').map((p) => p.trim()).filter(Boolean);
  if (!parts.length) return '';
  const rawKey = parts[parts.length - 1];
  const isCode = rawKey.startsWith('@');
  const key = normKey(isCode ? rawKey.slice(1) : rawKey);
  /** @type {Set<string>} */
  const mods = new Set();
  for (const p of parts.slice(0, -1)) {
    const low = p.toLowerCase();
    if (MOD_ALIASES.has(low)) mods.add('mod');
    else if (low === 'alt' || low === 'option') mods.add('alt');
    else if (low === 'shift') mods.add('shift');
  }
  const order = ['mod', 'alt', 'shift'].filter((m) => mods.has(m));
  return order.concat((isCode ? 'c:' : 'k:') + key).join('+');
}

/** @param {KeyboardEvent} e @returns {string[]} 已按 mod/alt/shift 归一序排好的修饰键 */
function modsOf(e) {
  const mods = [];
  if (e.metaKey || e.ctrlKey) mods.push('mod');
  if (e.altKey) mods.push('alt');
  if (e.shiftKey) mods.push('shift');
  return mods;
}

/**
 * 从键盘事件算**字符**规范串 —— 与 canonicalCombo 逐字同构（冲突检测的前提）。
 * @param {KeyboardEvent} e
 * @returns {string}
 */
function canonicalFromEvent(e) {
  return modsOf(e).concat('k:' + normKey(e.key)).join('+');
}

/**
 * 事件的**物理键**规范串（e.code 口径，布局/输入法无关）。
 * @param {KeyboardEvent} e
 * @returns {string}
 */
function canonicalFromCode(e) {
  return modsOf(e).concat('c:' + normKey(e.code)).join('+');
}

/**
 * 事件目标是否在「文本输入态」内 —— 键盘优先最容易被误伤的一面：
 * 在输入框里把 `Alt+1` 当面板切换，用户就打不出那个字符了。
 * @param {EventTarget | null} t
 * @returns {boolean}
 */
export function isEditableTarget(t) {
  if (!(t instanceof HTMLElement)) return false;
  const tag = t.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
  return t.isContentEditable === true;
}

/**
 * 聚焦元素是否是「自己用 Space/Enter 激活」的自绘控件（button / role 族 / 带 tabindex）。
 * meta 模式下 Space 不许抢它的原生激活语义。
 * @param {Element | null} el
 * @returns {boolean}
 */
export function isActivatableTarget(el) {
  if (!(el instanceof HTMLElement)) return false;
  if (el.tagName === 'BUTTON' || el.tagName === 'A') return true;
  const role = el.getAttribute('role');
  if (role === 'button' || role === 'checkbox' || role === 'menuitem' || role === 'option' || role === 'tab') return true;
  return el.hasAttribute('tabindex');
}

/**
 * 场景是否生效。'global' 恒生效。
 * @param {string} scope
 * @returns {boolean}
 */
export function isScopeActive(scope) {
  return scope === 'global' || activeScopes.has(scope);
}

/**
 * 启停一个场景（幂等）。
 * @param {string} scope
 * @param {boolean} [on]
 * @returns {void}
 */
export function enableScope(scope, on = true) {
  if (scope === 'global') return; // global 不可停用 —— 停用它等于静默废掉整层
  if (on) activeScopes.add(scope);
  else activeScopes.delete(scope);
  notifyChange();
}

/** 当前模式。 @returns {KeyMode} */
export function getMode() {
  return currentMode;
}

/**
 * 切换模式（meta ↔ edit）。注册器只负责「哪个键在哪个模式下算数」，
 * 焦点迁移由 mode.js 负责（关注点分离：注册器不碰 DOM）。
 * @param {KeyMode} mode
 * @returns {void}
 */
export function setMode(mode) {
  if (mode !== 'meta' && mode !== 'edit') return;
  if (mode === currentMode) return;
  const from = currentMode;
  currentMode = mode;
  notifyChange();
  document.dispatchEvent(new CustomEvent('nf-mode-change', { detail: { from, to: mode } }));
}

/**
 * @param {KeyMode} m
 * @returns {boolean} 该注册项在当前模式下是否生效
 */
function isModeActive(m) {
  return m === '*' || m === currentMode;
}

/**
 * 模式是否相交（冲突检测用）：'*' 与任何模式相交。
 * @param {KeyMode} a
 * @param {KeyMode} b
 * @returns {boolean}
 */
function modesOverlap(a, b) {
  return a === b || a === '*' || b === '*';
}

/**
 * 注册一条快捷键。
 *
 * 冲突策略 = **注册期硬错**（同 scope、同 combo、**mode 相交**的二次注册直接抛），
 * 因为运行期静默二选一是最难查的一类 bug。同一 combo 在不同 mode 下可以共存
 * —— 那正是双模态的用法，不是冲突。
 *
 * @param {ShortcutSpec} spec
 * @returns {() => void} 注销函数
 */
export function registerShortcut(spec) {
  if (!spec || !spec.id || !spec.combo || typeof spec.handler !== 'function') {
    throw new Error('keymap: registerShortcut 需要 { id, combo, description, handler }');
  }
  const scope = spec.scope || 'global';
  const mode = /** @type {KeyMode} */ (spec.mode || '*');
  const canonicals = [spec.combo, ...(spec.aliases || [])].map(canonicalCombo).filter(Boolean);
  if (!canonicals.length) throw new Error(`keymap: combo 无法解析 (${spec.id}: ${spec.combo})`);

  if (byId.has(spec.id)) unregister(spec.id); // 同 id 覆盖 = 幂等重入

  for (const c of canonicals) {
    const bucket = byCombo.get(c) || [];
    const clash = bucket.find((s) => s.scope === scope && modesOverlap(s.mode, mode));
    if (clash) {
      throw new Error(
        `keymap: combo 冲突 "${c}" —— "${spec.id}"(mode=${mode}) 与 "${clash.id}"(mode=${clash.mode}) 同在 scope=${scope}`,
      );
    }
  }

  /** @type {Entry} */
  const entry = Object.assign({}, spec, { scope, mode, canonical: canonicals });
  byId.set(spec.id, entry);
  for (const c of canonicals) {
    const bucket = byCombo.get(c) || [];
    bucket.push(entry);
    byCombo.set(c, bucket);
  }
  notifyChange();
  return () => unregister(spec.id);
}

/**
 * 注销一条。
 * @param {string} id
 * @returns {boolean} 是否真的删掉了东西
 */
export function unregister(id) {
  const entry = byId.get(id);
  if (!entry) return false;
  byId.delete(id);
  for (const c of entry.canonical) {
    const bucket = byCombo.get(c);
    if (!bucket) continue;
    const i = bucket.indexOf(entry);
    if (i >= 0) bucket.splice(i, 1);
    if (!bucket.length) byCombo.delete(c);
  }
  notifyChange();
  return true;
}

/** 清空全部注册（测试/收尾用；生产路径不该调用）。 @returns {void} */
export function clearAll() {
  byCombo.clear();
  byId.clear();
  activeScopes.clear();
  notifyChange();
}

/**
 * 列出全部注册项（命令面板 / 速查视图 / 验收脚本消费）。
 * @returns {Array<{id: string, combo: string, scope: string, mode: KeyMode, description: string, group: string, active: boolean}>}
 */
export function listShortcuts() {
  const out = [];
  for (const entry of byId.values()) {
    out.push({
      id: entry.id,
      combo: entry.combo,
      scope: entry.scope,
      mode: entry.mode,
      description: entry.description || '',
      group: entry.group || '',
      active: isScopeActive(entry.scope),
    });
  }
  out.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return out;
}

/** 变更订阅（命令面板 / 模式指示器消费）。 @type {Set<() => void>} */
const listeners = new Set();

/**
 * 订阅注册表/模式变更。
 * @param {() => void} fn
 * @returns {() => void} 退订
 */
export function onKeymapChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/** @returns {void} */
function notifyChange() {
  for (const fn of listeners) {
    try { fn(); } catch { /* 订阅者异常不得污染注册表 */ }
  }
}

/**
 * 唯一 keydown 入口。裁决顺序见设计文档 §3.2：
 *   defaultPrevented → IME 守卫 → combo 命中（物理键口径优先于字符口径）→
 *   模式过滤 → 场景过滤 → 输入态过滤 → 执行。
 * 未命中一律不碰事件。
 * @param {KeyboardEvent} e
 * @returns {void}
 */
function onKeydown(e) {
  if (e.defaultPrevented) return; // 上层（capture 层栈 / 原生）已处理，让位

  const target = e.target;
  const editable = isEditableTarget(target);
  // ⑤ IME 收归（复用全仓唯一谓词 imeGuard.isImeComposing）：
  // 组字期间的 Enter 是「上屏候选词」，**绝不能**被当成发送或模式切换。
  if (isImeComposing(e, /** @type {HTMLInputElement} */ (target))) return;

  /** @type {Entry[]} */
  const hits = [];
  if (e.code) {
    const byCode = byCombo.get(canonicalFromCode(e));
    if (byCode) hits.push(...byCode);
  }
  const byKey = byCombo.get(canonicalFromEvent(e));
  // 物理键口径排前：`Shift+[` 在美式键盘上 key 是 `{`，字符口径会漏，code 口径恒为 BracketLeft
  if (byKey) for (const s of byKey) if (hits.indexOf(s) < 0) hits.push(s);
  if (!hits.length) return;

  for (const spec of hits) {
    if (!isModeActive(spec.mode)) continue;
    if (!isScopeActive(spec.scope)) continue;
    if (editable && spec.allowInInput !== true) continue;
    spec.handler(e);
    if (spec.preventDefault !== false) e.preventDefault();
    if (spec.stopPropagation !== false) e.stopPropagation();
    return;
  }
}

document.addEventListener('keydown', onKeydown);
