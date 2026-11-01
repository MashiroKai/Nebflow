// keyboardBoot.js — 键盘层引导（keyboard-first 一期，2026-11-01）。
//
// 为什么是**独立引导入口**（index.html 里一条额外的 module script）而不是塞进
// main.js：main.js 正被「文档与编辑体验」批同期改动，键盘层必须是可整体摘除的
// 一层 —— 独立入口 = 冲突面零、回滚面一行。
//
// 本文件只做装配，不含逻辑：
//   ① 把既有 DOM 动作包成命令（全部靠 click() 既有控件触发，零业务耦合）
//   ② 按 meta/edit 双模态注册直键（策略在 keymap.js，模式面在 mode.js）
//   ③ 处理作者点名的「Enter 发送后 edit→meta」—— 难点在于读值必须在发送**之前**
//      （见 onPreKeydownCapture 注释）
//
// 硬边界：本文件不新增任何业务行为。每个面板命令 = 点一个今天已经存在的控件。

import { registerShortcut, setMode, getMode, isActivatableTarget, isEditableTarget } from './keymap.js';
import { isImeComposing } from './imeGuard.js';
import { registerCommand, togglePalette, openPalette } from './commandPalette.js';
import { initMode, enterEdit, enterMeta, stepCenter, stepSidebar } from './mode.js';

/**
 * 点一个既有控件（命令实现的唯一形态）。
 * @param {string} sel
 * @returns {void}
 */
function clickEl(sel) {
  const el = document.querySelector(sel);
  if (el instanceof HTMLElement) el.click();
}

/** @param {string} id @returns {boolean} 该元素存在且可见 */
function visibleId(id) {
  const el = document.getElementById(id);
  if (!(el instanceof HTMLElement) || el.hidden) return false;
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0;
}

// ── ① 命令表（= 今天已经存在的控件，一个不多） ───────────────────────────

/** @type {Array<{id:string,title:string,group:string,keywords:string[],sel:string,when:(()=>boolean)|null}>} */
const COMMAND_DEFS = [
  { id: 'panel.files', title: '切到文件面板', group: '面板', keywords: ['files', 'explorer', '文件'], sel: '#files-btn', when: null },
  { id: 'panel.projects', title: '切到项目面板', group: '面板', keywords: ['project', '项目'], sel: '#projects-btn', when: null },
  { id: 'panel.plugins', title: '切到插件面板', group: '面板', keywords: ['agents', 'plugin', '插件'], sel: '#agents-btn', when: null },
  { id: 'panel.messages', title: '切到消息面板', group: '面板', keywords: ['messages', '消息', '会话'], sel: '#messages-btn', when: () => visibleId('messages-btn') },
  { id: 'panel.contacts', title: '切到联系人面板', group: '面板', keywords: ['contacts', '联系人'], sel: '#contacts-btn', when: () => visibleId('contacts-btn') },
  { id: 'sidebar.toggle', title: '收起 / 展开侧栏', group: '布局', keywords: ['sidebar', '侧栏'], sel: '#sidebar-toggle', when: null },
  { id: 'canvas.toggle', title: '切换画布面板', group: '布局', keywords: ['canvas', '画布'], sel: '#canvas-toggle-btn', when: null },
  { id: 'canvas.deck', title: '切换叠卡视角', group: '布局', keywords: ['deck', '叠卡', 'stack'], sel: '#deck-fab', when: null },
  { id: 'settings.open', title: '打开设置', group: '入口', keywords: ['settings', 'config', '设置'], sel: '#settings-btn', when: null },
  { id: 'chat.search', title: '搜索聊天记录', group: '入口', keywords: ['search', '搜索'], sel: '#search-btn', when: null },
  { id: 'reminder.open', title: '打开定时任务', group: '入口', keywords: ['reminder', '定时'], sel: '#reminder-btn', when: null },
];

for (const def of COMMAND_DEFS) {
  registerCommand({
    id: def.id,
    title: def.title,
    group: def.group,
    keywords: def.keywords,
    enabled: def.when || (() => true),
    run: () => clickEl(def.sel),
  });
}

registerCommand({
  id: 'palette.help',
  title: '快捷键速查',
  group: '键盘',
  keywords: ['help', 'keymap', '快捷键', '帮助'],
  enabled: () => true,
  run: () => openPalette('快捷键'),
});

registerCommand({
  id: 'mode.edit',
  title: '进入输入模式（edit）',
  group: '键盘',
  keywords: ['edit', 'insert', '输入'],
  enabled: () => true,
  run: () => enterEdit(),
});

registerCommand({
  id: 'mode.meta',
  title: '回到导航模式（meta）',
  group: '键盘',
  keywords: ['meta', 'normal', '导航'],
  enabled: () => true,
  run: () => enterMeta(),
});

// ── ② 直键注册（meta/edit 双模态） ──────────────────────────────────────

/** 两态通用：命令面板。 */
registerShortcut({
  id: 'palette.toggle',
  combo: 'Mod+K',
  mode: '*',
  scope: 'global',
  group: '键盘',
  description: '命令面板：开 / 关',
  handler: () => togglePalette(),
});

registerShortcut({
  id: 'palette.help',
  combo: 'Mod+/',
  mode: '*',
  scope: 'global',
  group: '键盘',
  description: '快捷键速查',
  handler: () => openPalette('快捷键'),
});

/** meta：`[` / `]` 调整中心区聚焦位置。
 *  键位口径 = **物理键**（@BracketLeft/@BracketRight = e.code），不用字符比较：
 *  中文输入法下 `[` 的 e.key 可能是全角「【」，美式键盘 Shift+[ 的 e.key 是「{」
 *  —— 字符口径在这两种情况下都会漏。 */
registerShortcut({
  id: 'nav.center.prev',
  combo: '@BracketLeft',
  mode: 'meta',
  scope: 'global',
  group: '导航',
  description: '中心区：上一个聚焦位置',
  handler: () => stepCenter(-1),
});

registerShortcut({
  id: 'nav.center.next',
  combo: '@BracketRight',
  mode: 'meta',
  scope: 'global',
  group: '导航',
  description: '中心区：下一个聚焦位置',
  handler: () => stepCenter(1),
});

/** meta：`Shift+[` / `Shift+]` 操作侧边栏。
 *  美式键盘上 Shift+[ 的 `e.key` 是 `{`、Shift+] 是 `}`，字符口径完全匹不到；
 *  这里靠 `@`（e.code）口径恒定命中 BracketLeft/BracketRight。 */
registerShortcut({
  id: 'nav.sidebar.prev',
  combo: 'Shift+@BracketLeft',
  mode: 'meta',
  scope: 'global',
  group: '导航',
  description: '侧边栏：上一条目',
  handler: () => stepSidebar(-1),
});

registerShortcut({
  id: 'nav.sidebar.next',
  combo: 'Shift+@BracketRight',
  mode: 'meta',
  scope: 'global',
  group: '导航',
  description: '侧边栏：下一条目',
  handler: () => stepSidebar(1),
});

/** meta：Space → edit。
 *  冲突纪律（实测 20+ 处 `e.key === ' '` 自绘控件激活，如 plugins.js:1011、
 *  main.js:2161、contacts.js:430）：焦点在可激活控件上时**不接管**，让原生
 *  Space 走激活语义 —— 否则「Tab 到按钮 + 空格」就废了。 */
registerShortcut({
  id: 'mode.enterEdit',
  combo: '@Space',
  mode: 'meta',
  scope: 'global',
  group: '键盘',
  description: '进入输入模式（edit）',
  handler: (e) => {
    if (isActivatableTarget(document.activeElement)) return;
    if (!isEditableTarget(e.target)) enterEdit();
  },
});

// ── ③ Enter：发送后 edit → meta ─────────────────────────────────────────

/** @type {boolean} 本次 Enter 是否是一次「有内容 · 非组字 · 无 Shift」的发送 */
let enterSendPending = false;

/**
 * **capture 阶段**预读。为什么必须提前读：`input.js:1846` 的 Enter 处理挂在元素
 * target 阶段，`send()` 会同步清空 textarea —— 等文档 bubble 阶段再读
 * `input.value` 已经读不到「原本有没有内容」，只能靠猜。
 *
 * IME：`isImeComposing` 覆盖三种形态（`e.isComposing` / `keyCode 229` /
 * 元素级 `data-ime-composing`，见 imeGuard.js:88）。组字中的 Enter 是「上屏
 * 候选词」，这里既不消费也不标记 —— 那一次绝不能被当成发送。
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
 * edit 下的 Enter：**不阻止默认、不停止传播**（`preventDefault:false` /
 * `stopPropagation:false`），input.js 的发送链路逐字不变；本 handler 只在
 * 发送那一拍之后把模式交回 meta。
 */
registerShortcut({
  id: 'mode.sendToMeta',
  combo: 'Enter',
  mode: 'edit',
  scope: 'global',
  allowInInput: true,
  preventDefault: false,
  stopPropagation: false,
  // 观察者注册：Enter 的**所有权在 input.js**（它 preventDefault 并发 send，
  // 见 input.js:1846-1856）。本项不抢键，只在发送那一拍之后补 edit→meta。
  observe: true,
  group: '键盘',
  description: '发送并把焦点交回导航态',
  handler: () => {
    if (!enterSendPending) return;
    enterSendPending = false;
    // 退出一拍：让同一次事件里的 input.js send()（值已被它读走）先跑完
    window.setTimeout(() => {
      if (getMode() === 'edit') enterMeta();
      else setMode('meta');
    }, 0);
  },
});

// ── ④ 启动 ─────────────────────────────────────────────────────────────

initMode();
