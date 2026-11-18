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
// 依赖方向：叶子模块（只 import input/composer.js 的键道装配；composer.js 只
// 到 imeGuard.js / ws.js）。零回边 ⇒ scripts/check-circular.mjs 不受影响。

import { bindComposerKeys } from './composer.js';

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

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * 造一个 SVG 命名空间节点（`<svg>`/`<path>` 必须走 createElementNS，否则图标
 * 整块不绘制；零 HTML 串、零 innerHTML —— 组件契约 R5 计数为 0）。
 * @param {string} tag
 * @param {Record<string, string>} [attrs]
 * @param {SVGElement[]} [children]
 * @returns {SVGElement}
 */
function svgNode(tag, attrs, children) {
  const node = /** @type {SVGElement} */ (document.createElementNS(SVG_NS, tag));
  if (attrs) for (const [name, value] of Object.entries(attrs)) node.setAttribute(name, value);
  if (children) for (const child of children) node.appendChild(child);
  return node;
}

const STROKE = { fill: 'none', stroke: 'currentColor', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' };

/**
 * 发送字形（与主会话 #send-btn 同一线性语言；Canvas Ask 批冻结的原字形，
 * 本批收进单源后逐字节不变）。
 * @returns {SVGElement}
 */
export function composerSendIcon() {
  return svgNode('svg', { viewBox: '0 0 24 24', 'stroke-width': '2.4', ...STROKE },
    [svgNode('path', { d: 'M12 19V5M5 12l7-7 7 7' })]);
}

/**
 * 中断字形（lucide square 族；与主会话 #stop-btn 同语）。
 * @returns {SVGElement}
 */
export function composerStopIcon() {
  return svgNode('svg', { viewBox: '0 0 24 24', 'stroke-width': '2.4', ...STROKE },
    [svgNode('rect', { x: '6', y: '6', width: '12', height: '12', rx: '2' })]);
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
  sendBtn.appendChild(composerSendIcon());

  /** @type {HTMLButtonElement|null} */
  let stopBtn = null;
  if (withStop) {
    stopBtn = /** @type {HTMLButtonElement} */ (document.createElement('button'));
    stopBtn.type = 'button';
    stopBtn.className = [stopHook, 'ds-turn-btn', 'ds-turn-btn--stop'].filter(Boolean).join(' ');
    if (stopLabel) stopBtn.setAttribute('aria-label', stopLabel);
    stopBtn.appendChild(composerStopIcon());
  }

  root.append(input, sendBtn);
  if (stopBtn) root.appendChild(stopBtn);

  if (onSend) {
    // 键盘/IME/发送判定 = 主会话同一实现（本模块只装配，不复制判定）。
    bindComposerKeys(input, { preamble: keys?.preamble, cascade: keys?.cascade, onSend: () => onSend() });
    sendBtn.addEventListener('click', () => { onSend(); });
  }

  return { root, input, sendBtn, stopBtn, autosize };
}
