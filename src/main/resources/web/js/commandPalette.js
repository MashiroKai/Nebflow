// commandPalette.js — 命令面板（keyboard-first 一期 2026-11-01；三期换内核 2026-11-02）。
//
// ── 三期改造：UI 内核从「手写 332 行 DOM」换成 Monaco 自带的 QuickInput ──────
// 本文件仍然拥有**命令注册表**（registerCommand / runCommand / listCommands）——
// 那是数据的单一真相，与 UI 无关；被替换掉的是下面这块**渲染与交互**：
//   · 旧：自建 .nf-palette-overlay/.nf-palette-row 一整套 DOM、自己写 ↑↓ 漫游、
//     自己写子串匹配与分组标题、自己接 focusTrap 层栈。
//   · 新：Monaco 的 QuickInputController（vs/platform/quickinput 的
//     QuickInputController —— VS Code 命令面板同一个组件，见 monacoQuickInput.js
//     的三条实测依据）。列表、过滤、分组分隔、键位提示列、无障碍语义（combobox /
//     aria-activedescendant / role=listbox）全部由它提供。
//
// 能力对照（逐条对齐，`docs/` 里有实测表）：
//   开/关 · 输入即筛 · ↑↓ 漫游 · Enter 执行 · Esc 关并回焦 · 分组标题 ·
//   键位提示列 · 禁用项不可执行 · 无命中提示 —— 一条不少，见 keyboard-check B 组。
//
// 为什么命令表留在本文件而 UI 不在：直键 handler 与面板行必须指向**同一个命令 id**
// （否则就是两套真理）。注册表在这里，UI 只是它的一个视图。

import { listShortcuts } from './keymap.js';
import { getQuickInput, HOST_ID } from './monacoQuickInput.js';
import { setContextKey, KEYS } from './keyContext.js';

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
 * @typedef {Object} PaletteRow
 * @property {'command'|'shortcut'} kind
 * @property {Command|null} cmd
 * @property {string} id
 * @property {string} label
 * @property {string} group
 * @property {string} keyHint
 * @property {boolean} disabled
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

/** @type {any} 当前 QuickPick 实例（Monaco QuickPick） */
let pick = null;
/** @type {PaletteRow[]} 当前可见行（与 QuickPick 的 items 一一对应，去掉分隔符） */
let rows = [];
/** @type {boolean} 正在打开（防重入） */
let opening = false;

/**
 * 面板是否打开。
 * @returns {boolean}
 */
export function isPaletteOpen() {
  return !!pick && pick.visible === true;
}

/**
 * `@BracketLeft` → `[`，`Mod+K` → `Ctrl+K`（mac 显示 ⌘，按平台判一次）。
 * @param {string} combo
 * @returns {string}
 */
export function prettyCombo(combo) {
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

/**
 * 收集候选：命令表（可执行）+ 没有对应命令的直键（只展示键位，不可执行）。
 * 直键的键位提示从 keymap 注册表现取 —— 命令与直键指向同一个 id，键位永远同步。
 * @returns {PaletteRow[]}
 */
function collectRows() {
  /** @type {Map<string, string>} */
  const keyById = new Map();
  for (const s of listShortcuts()) keyById.set(s.id, prettyCombo(s.combo));

  /** @type {PaletteRow[]} */
  const out = [];
  for (const cmd of commands.values()) {
    const enabled = typeof cmd.enabled === 'function' ? cmd.enabled() : cmd.enabled !== false;
    out.push({
      kind: 'command', cmd, id: cmd.id, label: cmd.title,
      group: cmd.group || '命令', keyHint: keyById.get(cmd.id) || '', disabled: !enabled,
    });
  }
  // 纯直键行：只展示（面板里执行它们需要「命令化」，那是另一件事；改造前也是只读）。
  // 按当前模式过滤：按不出来的键不列（与改造前同一口径）。
  const listed = new Set(commands.keys());
  for (const s of listShortcuts()) {
    if (listed.has(s.id)) continue;
    out.push({
      kind: 'shortcut', cmd: null, id: s.id, label: s.description || s.id,
      group: '快捷键（直键）', keyHint: prettyCombo(s.combo), disabled: true,
    });
  }
  return out;
}

/**
 * 匹配打分（命令名 / 分组 / id / 键位任一命中；越小越靠前，-1 = 不命中）。
 * 与改造前同一口径 —— 分组名可搜（「面板」能筛出整组）是既有能力，不许退化。
 * @param {PaletteRow} row
 * @param {string} q
 * @returns {number}
 */
function score(row, q) {
  if (!q) return 0;
  const hay = `${row.label} ${row.group} ${row.id} ${row.keyHint}`.toLowerCase();
  return hay.indexOf(q);
}

/** 无命中时的占位行（改造前面板底部那行「没有匹配的命令」；这里是列表里的一行，
 *  disabled ⇒ 回车不会执行，aria 上也是「不可选」）。 */
const EMPTY_ROW_ID = '__nf_palette_empty__';

/**
 * 把行装配成 QuickPick 的 items（分组 → separator）。
 * @param {PaletteRow[]} visible
 * @returns {any[]}
 */
function toItems(visible) {
  /** @type {any[]} */
  const items = [];
  if (!visible.length) {
    return [{ label: '没有匹配的命令', disabled: true, _rowId: EMPTY_ROW_ID }];
  }
  let lastGroup = '';
  for (const row of visible) {
    if (row.group !== lastGroup) {
      lastGroup = row.group;
      items.push({ type: 'separator', label: row.group });
    }
    items.push({
      label: row.label,
      description: row.keyHint,
      disabled: row.disabled,
      // 面板行 id 进 a11y 面（断言也读它，用来确认「选中的是哪一条」）
      _rowId: row.id,
    });
  }
  return items;
}

/**
 * 按输入重算可见行并灌进面板（**我们自己筛**，见下方 filterValue 注释）。
 * @param {string} value
 * @returns {void}
 */
function applyFilter(value) {
  if (!pick) return;
  const q = String(value || '').trim().toLowerCase();
  const scored = rows
    .map((row, i) => ({ row, i, s: score(row, q) }))
    .filter((x) => x.s >= 0)
    .sort((a, b) => (a.s === b.s ? a.i - b.i : a.s - b.s))
    .map((x) => x.row);
  pick.items = /** @type {any} */ (toItems(scored));
}

/** 把当前候选集灌进面板（并按当前输入切一次）。 @returns {void} */
function refresh() {
  if (!pick) return;
  rows = collectRows();
  /* 为什么关掉 Monaco 自己的匹配：它的 QuickInputList 只拿 `label` / `description`
     做匹配（`matchOnLabel` / `matchOnDescription` 是布尔开关，没有可插拔的谓词），
     而「分组名可搜」是旧面板的既有能力（敲「布局」要筛出整组）——分组名既不在
     label 也不在 description 里，交给它筛这一条就没了。
     做法：`filterValue()` 恒返回空串（列表因此永远「全量显示我们给的 items」），
     真正的筛在 `applyFilter` 里按旧口径（label + 分组 + id + 键位 子串，命中位置
     越靠前越前，同分保持原序）算完再赋值 `items`。⇒ 命中口径与旧面板逐字相同，
     Monaco 只负责壳/键盘导航/虚拟列表。 */
  pick.filterValue = () => '';
  pick.onDidChangeValue((/** @type {string} */ v) => applyFilter(v));
  applyFilter(pick.value || '');
}

/**
 * 执行第 index 行可见项（index 来自 onDidAccept 的 activeItems）。
 * @param {any} item
 * @returns {void}
 */
function acceptItem(item) {
  if (!item) return;
  const id = item._rowId;
  const row = rows.find((r) => r.id === id);
  if (!row || row.disabled || !row.cmd) return; // 禁用行/只读行/占位行：不执行、面板不关
  closePalette();
  // 命令在面板关闭之后跑：命令自己可能马上再开一个层（设置/搜索），
  // 顺序反了会把它的回焦抢掉。
  window.setTimeout(() => { row.cmd && row.cmd.run(); }, 0);
}

/**
 * 拿到（或创建）QuickPick 实例。
 * @returns {Promise<any>}
 */
async function ensurePick() {
  if (pick) return pick;
  const controller = await getQuickInput();
  const p = controller.createQuickPick();
  p.placeholder = '输入命令名…（↑↓ 选择 · Enter 执行 · Esc 关闭）';
  p.matchOnDescription = true;
  p.sortByLabel = false;
  p.ignoreFocusOut = false;
  p.onDidAccept(() => {
    if (pick) acceptItem(pick.activeItems[0]);
  });
  p.onDidHide(() => {
    setContextKey(KEYS.paletteOpen, false);
  });
  pick = p;
  return p;
}

/**
 * 打开面板。
 * @param {string} [initialQuery]
 * @returns {void}
 */
export function openPalette(initialQuery = '') {
  if (opening || isPaletteOpen()) return;
  opening = true;
  void (async () => {
    try {
      const p = await ensurePick();
      p.value = initialQuery;
      refresh();
      p.show();
      setContextKey(KEYS.paletteOpen, true);
    } catch (e) {
      // 加载失败必须可见（项目纪律：失败不许静默）：控制台红字 + 一个可定位的标记，
      // 断言直接读 `window.__nfPaletteError`。
      console.error('[nf-palette] Monaco quick input 不可用', e);
      /** @type {any} */ (window).__nfPaletteError = String((e && /** @type {any} */ (e).message) || e);
    } finally {
      opening = false;
    }
  })();
}

/**
 * 关闭面板（回焦由组件负责：它记住打开前的焦点元素并还回去）。
 * @returns {void}
 */
export function closePalette() {
  if (!pick) return;
  pick.hide();
  pick.value = '';
  setContextKey(KEYS.paletteOpen, false);
}

/** 开/关切换。 @returns {void} */
export function togglePalette() {
  if (isPaletteOpen()) closePalette();
  else openPalette();
}

/** 面板宿主元素 id（断言与 CSS 共用）。 */
export { HOST_ID };
