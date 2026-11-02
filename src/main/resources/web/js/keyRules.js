// keyRules.js — 键盘规则的**唯一表**（keyboard-first 三期，2026-11-02）。
//
// 一期把键位收进了注册器（keymap.js），但八条 `registerShortcut(...)` 调用仍然散在
// keyboardBoot.js 里、逐条手写；而真正会「按错地方」的业务键（文件树、叠卡）根本
// 没进注册器 —— 它们各自在自己的模块里 `addEventListener('keydown')`，用一把
// `closest('input, textarea, …')` 手写早退来避免误伤输入框。
//
// 三期把「哪个键、在什么状态下、干什么」变成**一张数据表**：
//   · 每条规则必须有 `when`（没有例外的空放行 —— 空 when 会让「EDIT 模式不穿透」
//     这件事无法被证明，而它正是本批的核心目标）。
//   · `when` 是 keyContext.js 的表达式（Monaco ContextKeyExpr 语法）。
//   · handler 只做动作，不做「我现在该不该响应」的判断 —— 判断在 `when` 里。
//
// 表的读法（三段）：① 键盘层自身（两态通用）② 导航（NAV 态）③ 业务键收编
// （文件树 / 叠卡，各自由原模块提供动作函数，表里只有「条件 + 转发」）。
//
// 为什么业务键的 handler 是转发（`treeKeyAction('down', e)`）而不是把逻辑搬进来：
// 逻辑必须贴着它的状态（树的 selectedPaths/roving、叠卡的 deck.pos）走，搬进本表
// 只会制造第二份真相；本表拥有的是**键位与条件**这一层横切关注点。

import { registerShortcut, setMode, getMode } from './keymap.js';
import { KEYS } from './keyContext.js';
import { isImeComposing } from './imeGuard.js';
import { togglePalette, openPalette } from './commandPalette.js';
import { enterEdit, enterMeta, stepCenter, stepSidebar } from './mode.js';
import { treeKeyAction, clearTreeSelection } from './explorer.js';
import { deckKeyAction } from './canvas.js';

/**
 * @typedef {Object} KeyRule
 * @property {string} id          规则 id（= 命令 id，面板/速查/断言共用）
 * @property {string} key         combo（文法见 keymap.js；`@` 前缀 = 物理键）
 * @property {string[]} [aliases] 别名键（同一动作的第二/第三键位）
 * @property {string} when        生效条件（keyContext 表达式，**必填**）
 * @property {string} group       命令面板分组
 * @property {string} description 人读描述（面板与速查视图消费）
 * @property {(e: KeyboardEvent) => void} run
 * @property {boolean} [observe]  观察者（不抢键，其他模块拥有所有权时用）
 * @property {boolean} [preventDefault]
 * @property {boolean} [stopPropagation]
 */

// ── Enter 发送后 edit → meta（跨模块协议，状态机随规则一起搬到这里）─────────
//
// **capture 阶段**预读：`input.js:1846` 的 Enter 处理挂在元素 target 阶段，
// `send()` 会同步清空 textarea —— 等文档 bubble 阶段再读 `input.value` 已经读不到
// 「原本有没有内容」，只能靠猜。
//
// IME：`isImeComposing` 覆盖三种形态（`e.isComposing` / `keyCode 229` /
// 元素级 `data-ime-composing`，见 imeGuard.js:88）。组字中的 Enter 是「上屏
// 候选词」，这里既不消费也不标记 —— 那一次绝不能被当成发送。
/** @type {boolean} 本次 Enter 是否是一次「有内容 · 非组字 · 无 Shift」的发送 */
let enterSendPending = false;

/**
 * @param {KeyboardEvent} e
 * @returns {void}
 */
function onPreKeydownCapture(e) {
  enterSendPending = false;
  if (e.key !== 'Enter' || e.shiftKey) return;
  const t = e.target;
  if (!(t instanceof HTMLTextAreaElement) || t.id !== 'input') return;
  if (isImeComposing(e, t)) return;
  enterSendPending = t.value.trim() !== '';
}

document.addEventListener('keydown', onPreKeydownCapture, true);

/**
 * edit 下的 Enter：**不阻止默认、不停止传播** —— input.js 的发送链路逐字不变；
 * 本规则只在发送那一拍之后把模式交回 meta。
 * @returns {void}
 */
function onEnterSend() {
  if (!enterSendPending) return;
  enterSendPending = false;
  // 退出一拍：让同一次事件里的 input.js send()（值已被它读走）先跑完
  window.setTimeout(() => {
    if (getMode() === 'edit') enterMeta();
    else setMode('meta');
  }, 0);
}

const META = `${KEYS.mode} == meta`;
const EDIT = `${KEYS.mode} == edit`;

/** @type {KeyRule[]} */
export const KEY_RULES = [
  // ── ① 键盘层自身（两态通用：面板是「不撞键的兜底通道」，任何时候都得能开）──
  {
    id: 'palette.toggle', key: 'Mod+K', when: 'true', group: '键盘',
    description: '命令面板：开 / 关', run: () => togglePalette(),
  },
  {
    id: 'palette.help', key: 'Mod+/', when: 'true', group: '键盘',
    description: '快捷键速查', run: () => openPalette('快捷键'),
  },

  // ── ② 导航（NAV 态）────────────────────────────────────────────────────
  // 键位口径 = **物理键**（@BracketLeft/@BracketRight = e.code），不用字符比较：
  // 中文输入法下 `[` 的 e.key 可能是全角「【」，美式键盘 Shift+[ 的 e.key 是「{」
  // —— 字符口径在这两种情况下都会漏。
  {
    id: 'nav.center.prev', key: '@BracketLeft', when: META, group: '导航',
    description: '中心区：上一个聚焦位置', run: () => stepCenter(-1),
  },
  {
    id: 'nav.center.next', key: '@BracketRight', when: META, group: '导航',
    description: '中心区：下一个聚焦位置', run: () => stepCenter(1),
  },
  {
    id: 'nav.sidebar.prev', key: 'Shift+@BracketLeft', when: META, group: '导航',
    description: '侧边栏：上一条目', run: () => stepSidebar(-1),
  },
  {
    id: 'nav.sidebar.next', key: 'Shift+@BracketRight', when: META, group: '导航',
    description: '侧边栏：下一条目', run: () => stepSidebar(1),
  },

  // ── ③ 模式切换 ─────────────────────────────────────────────────────────
  // Space → edit。冲突纪律（20+ 处 `e.key === ' '` 自绘控件激活，如
  // plugins.js:1011、main.js:2161、contacts.js:430）：焦点在可激活控件上时**不接管**，
  // 让原生 Space 走激活语义 —— 否则「Tab 到按钮 + 空格」就废了。
  // 三期：这条纪律从 handler 里的两句 if 变成 when 子句（`!activatableFocus`）。
  {
    id: 'mode.enterEdit', key: '@Space', when: `${META} && !activatableFocus && !textInputFocus`,
    group: '键盘', description: '进入输入模式（edit）', run: () => enterEdit(),
  },
  {
    id: 'mode.sendToMeta', key: 'Enter', when: `${EDIT} && textInputFocus`,
    group: '键盘', description: '发送并把焦点交回导航态',
    // 观察者注册：Enter 的所有权在 input.js（它 preventDefault 并发 send）。
    observe: true, preventDefault: false, stopPropagation: false,
    run: () => onEnterSend(),
  },

  // ── ④ 文件树（原 explorer.js:2001 容器监听 → 规则）─────────────────────
  // 条件 = 焦点在树行上（`explorerTreeFocus` 由 keyContext 现算）。改造前这条腿还带
  // 一句 `closest('input, textarea, [contenteditable]')` 早退 —— 输入态判断在本表
  // 由 `when` 统一表达，模块不再自己判。
  {
    id: 'explorer.tree.down', key: 'ArrowDown', when: 'explorerTreeFocus', group: '文件树',
    description: '文件树：下一行', run: () => treeKeyAction('down'),
  },
  {
    id: 'explorer.tree.up', key: 'ArrowUp', when: 'explorerTreeFocus', group: '文件树',
    description: '文件树：上一行', run: () => treeKeyAction('up'),
  },
  {
    id: 'explorer.tree.home', key: 'Home', when: 'explorerTreeFocus', group: '文件树',
    description: '文件树：首行', run: () => treeKeyAction('home'),
  },
  {
    id: 'explorer.tree.end', key: 'End', when: 'explorerTreeFocus', group: '文件树',
    description: '文件树：末行', run: () => treeKeyAction('end'),
  },
  {
    id: 'explorer.tree.expand', key: 'ArrowRight', when: 'explorerTreeFocus', group: '文件树',
    description: '文件树：展开目录 / 进子行', run: () => treeKeyAction('expand'),
  },
  {
    id: 'explorer.tree.collapse', key: 'ArrowLeft', when: 'explorerTreeFocus', group: '文件树',
    description: '文件树：收起目录 / 回父行', run: () => treeKeyAction('collapse'),
  },
  {
    id: 'explorer.tree.activate', key: 'Enter', when: 'explorerTreeFocus', group: '文件树',
    description: '文件树：打开文件 / 切换目录', run: () => treeKeyAction('activate'),
  },
  // Esc 清选择（原 explorer.js:2013 文档级监听）。**这条是 EDIT 模式穿透的
  // 典型现场**：改造前在对话输入框里按 Esc 也会把文件树的选中项清掉（那条腿只排除了
  // 「焦点在新建输入行内」），三期的 when 把「输入态」显式写进条件。
  // `!deckOpen`：叠卡在场时 Esc 属于叠卡（层级更高），不与清选择抢同一个键。
  {
    id: 'explorer.selection.clear', key: 'Escape', when: 'explorerSelection && !textInputFocus && !deckOpen',
    group: '文件树', description: '文件树：清除选中', run: () => clearTreeSelection(),
  },

  // ── ⑤ 叠卡（原 canvas.js:2087 层内监听 + :3431 文档级监听 → 规则）────────
  // 改造前的两处监听靠 `e.stopPropagation()`（卡内截断）来避免「一次 Enter 两个动作」，
  // 并在文档级那条里手写 `closest('input, textarea, …')` 早退。现在两件事都是 `when`：
  //   · 卡内动作 = `deckCardFocus`
  //   · 环级动作 = `deckOpen && !deckCardFocus`
  // 顺序即优先级（`deck.card.*` 在 `deck.select` 之前），不再需要跨监听的冒泡博弈。
  // `!explorerTreeFocus`：树在场时方向键归树（焦点所在面的动作优先于背景面的擦洗）。
  {
    id: 'deck.card.activate', key: 'Enter', aliases: ['@Space'], when: 'deckOpen && deckCardFocus',
    group: '叠卡', description: '叠卡：激活聚焦卡', run: (e) => deckKeyAction('activateCard', e),
  },
  {
    id: 'deck.card.close', key: 'Delete', when: 'deckOpen && deckCardFocus',
    group: '叠卡', description: '叠卡：关闭聚焦卡', run: (e) => deckKeyAction('closeCard', e),
  },
  {
    id: 'deck.next', key: 'ArrowDown', aliases: ['ArrowRight', 'PageDown'],
    when: 'deckOpen && !textInputFocus && !deckCardFocus && !explorerTreeFocus',
    group: '叠卡', description: '叠卡：下一张', run: () => deckKeyAction('next'),
  },
  {
    id: 'deck.prev', key: 'ArrowUp', aliases: ['ArrowLeft', 'PageUp'],
    when: 'deckOpen && !textInputFocus && !deckCardFocus && !explorerTreeFocus',
    group: '叠卡', description: '叠卡：上一张', run: () => deckKeyAction('prev'),
  },
  {
    id: 'deck.close', key: 'Escape', when: 'deckOpen && !textInputFocus',
    group: '叠卡', description: '叠卡：收回', run: () => deckKeyAction('close'),
  },
  {
    id: 'deck.select', key: 'Enter',
    when: 'deckOpen && !textInputFocus && !deckCardFocus && !explorerTreeFocus',
    group: '叠卡', description: '叠卡：换入前景卡', run: () => deckKeyAction('select'),
  },

  // ── ⑥ 画布另两条文档级 Esc（原 canvas.js:4157 / :4809 → 规则）──────────
  // 条件同样来自**派生**上下文键（canvasRingOpen / cardWindow），不靠模块自己判。
  //
  // 两条都补了 `!textInputFocus`：改造前它们是**无条件**的文档级 `keydown`
  // （`if (e.key === 'Escape' && ringState) closeRing()` / `if (e.key === 'Escape')
  // requestMergeSelf()`），也就是「在对话输入框里敲 Esc 会顺手收掉胶囊环」。
  // 本批的口径是「EDIT（= 焦点在输入框）下业务键不穿透」，这两条不补就是漏网。
  // 卡片窗那一支不会因此失效：卡窗是独立页面、没有对话输入框，只有卡内编辑器
  // 聚焦时 textInputFocus 才为真 —— 而那时候的 Esc 归编辑器（关补全）所有。
  {
    id: 'canvas.ring.close', key: 'Escape', when: 'canvasRingOpen && !textInputFocus',
    group: '画布', description: '画布：收起胶囊环', run: () => deckKeyAction('closeRing'),
  },
  {
    id: 'canvas.mergeSelf', key: 'Escape', when: 'cardWindow && !textInputFocus',
    group: '画布', description: '卡片窗：合并回主窗', run: () => deckKeyAction('mergeSelf'),
  },
];

/**
 * 安装规则表（幂等：重复调用先撤旧注册）。返回撤销函数。
 * @returns {() => void}
 */
export function installKeyRules() {
  /** @type {Array<() => void>} */
  const offs = [];
  for (const r of KEY_RULES) {
    offs.push(registerShortcut(/** @type {any} */ ({
      id: r.id,
      combo: r.key,
      aliases: r.aliases,
      when: r.when,
      scope: 'global',
      group: r.group,
      description: r.description,
      observe: r.observe,
      preventDefault: r.preventDefault,
      stopPropagation: r.stopPropagation,
      handler: r.run,
    })));
  }
  return () => { for (const off of offs) off(); };
}

/** 表内规则的只读快照（面板「快捷键速查」与断言消费）。 @returns {KeyRule[]} */
export function listKeyRules() {
  return KEY_RULES.slice();
}
