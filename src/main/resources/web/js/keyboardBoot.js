// keyboardBoot.js — 键盘层引导（keyboard-first 一期，2026-11-01；三期改造 2026-11-02）。
//
// 为什么是**独立引导入口**（index.html 里一条额外的 module script）而不是塞进
// main.js：main.js 正被「文档与编辑体验」批同期改动，键盘层必须是可整体摘除的
// 一层 —— 独立入口 = 冲突面零、回滚面一行。
//
// 本文件只做装配，不含逻辑：
//   ① 把既有 DOM 动作包成命令（全部靠 click() 既有控件触发，零业务耦合）
//   ② 安装**规则表**（keyRules.js）—— 三期起键位不再在本文件逐条手写：
//      八条直键 + 收编来的文件树/叠卡业务键全在 keyRules.js 一张表里，
//      每条带 `when` 上下文条件（keyContext.js）
//   ③ 保焦协议的可观测面（B3-2）
//
// 硬边界：本文件不新增任何业务行为。每个面板命令 = 点一个今天已经存在的控件。

import { initMode, enterEdit, enterMeta } from './mode.js';
import { registerCommand, togglePalette, openPalette } from './commandPalette.js';
import { installKeyRules } from './keyRules.js';
import { installFocusContextKeys } from './keyContext.js';

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

// palette.toggle 既是规则也是命令：面板里能执行「开关命令面板」这件事本身
// （与 Mod+K 指向同一个执行体，不存在两套真理）。
registerCommand({
  id: 'palette.toggle',
  title: '命令面板：开 / 关',
  group: '键盘',
  keywords: ['palette', 'command', '面板', '命令'],
  enabled: () => true,
  run: () => togglePalette(),
});

// ── ② 规则表安装 ────────────────────────────────────────────────────────
//
// 三期之前这里是八条手写 `registerShortcut(...)`（palette ×2、nav ×4、mode ×2），
// 且完全不包含业务键。现在：焦点类上下文键先就位（`when` 要读它们），
// 再装一张表（keyRules.js）—— 本文件不再持有任何键位字面量。
installFocusContextKeys();
installKeyRules();

// ── ③ 保焦协议的可观测面（B3-2）────────────────────────────────────────
//
// `restoreFocusKey` / `popLayer` 在「按 key 找不回焦点」时会派发
// `nf-focus-fallback`。改造前这个事件**一个监听者都没有** —— 协议在派发，
// 业务永远不知道「焦点救不回来」这件事发生过，于是这类丢失只能靠人眼撞见。
//
// 消费者只做两件事（判断：不弹 toast）：
//   · 计数 `window.__nfFocusFallback`（验收断言读它，见 keyboard-check ⑯）；
//   · `console.warn` 一条带 key 的告警（DevTools 可定位到具体是哪个 key 丢了）。
// 为什么不弹可见 toast：能走到这里的情形（列表行被删、DOM 路径变了）本身是
// 可自愈的，焦点已经安全落在容器上；把它做成用户噪音，反而会训练用户忽略提示。
/** @type {{total:number, last:any, byKind:Record<string,number>}} */
const fallbackTrace = { total: 0, last: null, byKind: {} };
(/** @type {any} */ (window)).__nfFocusFallback = fallbackTrace;

document.addEventListener('nf-focus-fallback', (e) => {
  const detail = /** @type {CustomEvent} */ (e).detail || {};
  const kind = detail?.key?.kind || 'unknown';
  fallbackTrace.total += 1;
  fallbackTrace.last = { kind, value: detail?.key?.value ?? null, from: detail?.from ?? null };
  fallbackTrace.byKind[kind] = (fallbackTrace.byKind[kind] || 0) + 1;
  console.warn('[nf-focus] 焦点无法按 key 恢复，已回退到容器', fallbackTrace.last);
});

// ── ④ 启动 ─────────────────────────────────────────────────────────────

initMode();
