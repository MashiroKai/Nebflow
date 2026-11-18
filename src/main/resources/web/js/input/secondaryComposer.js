// input/secondaryComposer.js — 「次级 LLM 输入框」唯一模板
// （secondary LLM input box；todo-mobile 批 2026-11-18 作者令：
//   「把 Canvas Ask 用的那一套输入框整理成统一模板（次级 LLM 输入框），放进站内
//     单源（别在第二处再写一份）。详情页在底部控制条之上用这个模板做一个
//     「回复/恢复这个待办」的输入框（替代图2 里那个脱样式的小「发送」按钮）。」）
//
// ── 这个模块是什么（单源边界）──────────────────────────────────────────────
// 一段「LLM 次级输入条」的形态只有一处装配点：毛玻璃外壳（站内唯一玻璃工具类
// `nf-glass`，材质单源 css/glass.css + stand-tokens.css）、裸件 textarea
// （造型单源 css/inputSurface.css 的 `.nf-field--bare` 成员）、右上发送钮 /
// 中断钮（几何与六态单源 = design-system.css §①b 的 `.ds-turn-btn` 组件类）、
// 两个字形（向上箭头 / 方块）。**消费方只给身份类与文案**——任何面在这里再写
// 一遍「外壳 + 裸输入 + 圆钮 + 字形」就是第二套，本模块的存在就是为了让它没有
// 第二次机会（与 input/composer.js 收口键盘行为、chatUi.js 收口语汇同一手法）。
//
// ── 三个使用面 ───────────────────────────────────────────────────────────
//   ① Canvas Ask（js/askDock.js）——身份类 `ask-input` 由 chatUi.js 的
//     chatComposerClass('ask') 供给（对话 UI 词汇单源，见 scripts/check-chat-ui.mjs），
//     两个钩子类 `ask-send` / `ask-stop` 由调用方传入（既有 spec 选择器）；
//   ② 待办详情页「回复这个待办」（js/mobile/todoAppleFace.js）——身份类
//     `ta-reply-input`，无中断钮（没有可中断的回合 ⇒ 不渲染无能力承载的按钮）；
//   ③ 后续任何「次级 LLM 输入」面，只许调用本工厂。
//
// ── 与行为包的分工（不是第二套行为）──────────────────────────────────────
// 键盘/IME/发送判定/中断状态机 = `input/composer.js` 单源（本模块只**装配**：
// 传 onSend 时用 bindComposerKeys 装同一条键道；中断态仍由消费方用
// createTurnToggle 驱动同一对按钮）。本模块自己只拥有形态与「多行自动长高」
// （主会话 textarea 同式：auto → scrollHeight，封顶 maxHeight）。
//
// 字形单源批（ask-button-unify 2026-11-18，作者令逐字「Ask 的中止按钮中间的方框
// 应该和主会话的一致 发送按钮也和主会话一致吧」）：本模块此前**自带第二套字形**
// ——自建 `svgNode` + `composerSendIcon()`（向上箭头 path）与 `composerStopIcon()`
// （12×12 内缩方块 rect），与主会话 index.html `#send-btn`/`#stop-btn` 的
// lucide `send` / `square` 是两份几何（方框实测 18→9px、线宽 2.4/2 对 2.5）。
// 现态：本模块只产出**与主会话逐字节同形的字形占位** `<i data-lucide="send"|"square">`，
// 由全站唯一转换点 `utils.createIconsIn` 换成同一份 vendored lucide 几何（与
// index.html 的 `data-lucide` 走同一条通路）——第二套字形在此退役。
//
// 依赖方向：叶子模块（import input/composer.js 的键道装配 + utils.js 的图标转换
// 单点；composer.js 只到 imeGuard.js / ws.js）。零回边 ⇒ scripts/check-circular.mjs 不受影响。

import { bindComposerKeys } from './composer.js';
import { createIconsIn } from '../utils.js';

/** 站内唯一玻璃工具类（材质单源 = css/glass.css；本处只做词形登记）。 */
export const SECONDARY_COMPOSER_GLASS = 'nf-glass';

/**
 * 次级 LLM 输入条的完整 class 串（身份类 + 玻璃工具类）。
 * 消费方用本函数取串，别在第二处手写 `nf-glass`（第二处手写 = 单源裂缝）。
 * @param {string} [identity] 该面的身份类（几何/选择器归属该面自己的 CSS）
 * @returns {string}
 */
export function secondaryComposerClass(identity = '') {
  return identity ? `${identity} ${SECONDARY_COMPOSER_GLASS}` : SECONDARY_COMPOSER_GLASS;
}

/** 发送字形名 = 主会话 index.html `#send-btn` 的同一个 lucide 名（字形单源）。 */
export const SEND_ICON = 'send';
/** 中断字形名 = 主会话 index.html `#stop-btn` 的同一个 lucide 名（形成
 *  「圆内切换」的那枚方块；`square` = 18×18 满格 rect，非 Ask 旧的 12×12 内缩件）。 */
export const STOP_ICON = 'square';

/**
 * 字形占位（`<i data-lucide=…>`）——与 index.html 静态标记同形，由
 * `createIconsIn()` 换成 vendored lucide 的同一份几何。本函数**不**自建 SVG：
 * 任何在此处新写的 `<path d=…>` 都是第二套字形。
 * @param {string} name
 * @returns {HTMLElement}
 */
function turnGlyph(name) {
  const i = document.createElement('i');
  i.setAttribute('data-lucide', name);
  return i;
}

/**
 * @typedef {{ root: HTMLElement, input: HTMLTextAreaElement,
 *   sendBtn: HTMLButtonElement, stopBtn: HTMLButtonElement|null, autosize: () => void }} SecondaryComposer
 */

/**
 * 装配一个次级 LLM 输入条。
 *
 * @param {{
 *   identity?: string,          // 身份类（几何归该面自己的 CSS）
 *   class?: string,             // 完整 class 串覆盖（Canvas Ask 传 chatComposerClass('ask')）
 *   className?: string,         // 同上（别名；两个键名取其一即可）
 *   placeholder?: string, ariaLabel?: string,
 *   sendLabel?: string, stopLabel?: string,
 *   sendHook?: string, stopHook?: string,   // 既有 spec 选择器用的身份钩子，拼在组件类之前
 *   withStop?: boolean,          // 无回合可中断的面不许渲染停止钮（默认 false）
 *   rows?: number, maxHeight?: number,
 *   onSend?: (() => void)|null,  // 提供时 = 装配共享键道（Enter 发送 / Shift+Enter 换行 / IME 组字不发送）
 *   keys?: { preamble?: (e: KeyboardEvent) => void, cascade?: (e: KeyboardEvent) => boolean|void },
 *   onInput?: (() => void)|null,
 * }} [opts]
 * @returns {SecondaryComposer}
 */
export function createSecondaryComposer(opts = {}) {
  // `class` 是本工厂的**正式键名**（与 DOM 属性同名，Canvas Ask 面按它传
  // chatComposerClass('ask')；check-glass-unify G5 与 check-chat-ui 都在这行上
  // 核对「ask 输入条吃唯一毛玻璃工具类」）。`className` 是等义别名。
  const {
    identity = '', class: classStr, className,
    placeholder = '', ariaLabel = '',
    sendLabel = '', stopLabel = '',
    sendHook = '', stopHook = '',
    withStop = false,
    rows = 1, maxHeight = 120,
    onSend = null, keys, onInput = null,
  } = opts;

  const root = document.createElement('div');
  root.className = classStr || className || secondaryComposerClass(identity);

  const input = /** @type {HTMLTextAreaElement} */ (document.createElement('textarea'));
  input.rows = rows;
  if (placeholder) input.placeholder = placeholder;
  if (ariaLabel) input.setAttribute('aria-label', ariaLabel);

  // 多行草稿自动长高（主会话 textarea 同式：auto → scrollHeight，封顶留紧凑形态）。
  const autosize = () => {
    input.style.height = 'auto';
    input.style.height = `${Math.min(input.scrollHeight, maxHeight)}px`;
  };
  input.addEventListener('input', () => { autosize(); if (onInput) onInput(); });

  const sendBtn = /** @type {HTMLButtonElement} */ (document.createElement('button'));
  sendBtn.type = 'button';
  sendBtn.className = [sendHook, 'ds-turn-btn', 'ds-turn-btn--send'].filter(Boolean).join(' ');
  if (sendLabel) sendBtn.setAttribute('aria-label', sendLabel);
  sendBtn.appendChild(turnGlyph(SEND_ICON));

  /** @type {HTMLButtonElement|null} */
  let stopBtn = null;
  if (withStop) {
    stopBtn = /** @type {HTMLButtonElement} */ (document.createElement('button'));
    stopBtn.type = 'button';
    stopBtn.className = [stopHook, 'ds-turn-btn', 'ds-turn-btn--stop'].filter(Boolean).join(' ');
    if (stopLabel) stopBtn.setAttribute('aria-label', stopLabel);
    stopBtn.appendChild(turnGlyph(STOP_ICON));
  }

  root.append(input, sendBtn);
  if (stopBtn) root.appendChild(stopBtn);

  // 字形转换：与 index.html 的 `<i data-lucide>` 走同一条替换通路（utils.js 是
  // 全站唯一的 data-lucide → svg 转换点）——消费方不必各自记得转换。
  createIconsIn(root);

  if (onSend) {
    // 键盘/IME/发送判定 = 主会话同一实现（本模块只装配，不复制判定）。
    bindComposerKeys(input, { preamble: keys?.preamble, cascade: keys?.cascade, onSend: () => onSend() });
    sendBtn.addEventListener('click', () => { onSend(); });
  }

  return { root, input, sendBtn, stopBtn, autosize };
}
