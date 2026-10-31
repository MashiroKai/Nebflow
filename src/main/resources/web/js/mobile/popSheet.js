// mobile/popSheet.js — 弹窗退场批（2026-10-31 作者令②「手机端不再有任何形式的
// 弹窗界面：所有弹层改铺满屏幕+左上角返回按钮」）的共享注入器。
//
// 桌面模态族（z 310+/1000/10000）整体压在手机页面层（z156/dock 200）之上——
// 未被收编的桌面弹窗在手机上原样弹出。本模块给每一枚这样的弹层注入一枚
// 左上角「<」返回钮（48px g2-fab 基准材质，与页面壳返回钮同几何同材质），
// 返回语义 = 各弹窗自己的取消/关闭路径（调用方注入，禁在本模块自造）。
//
// 形态转换的 CSS 在 mobileLayer.css「弹窗退场批」块（按弹层族分面铺平）；本
// 模块只负责按钮注入与幂等。桌面零变化：
//   · 注入的按钮样式全部挂在 body.mobile-layer 下（mobileLayer.css）；
//   · body:not(.mobile-layer) 时按钮 display:none（视口跨档重排也不漏）。
//
// 模块纪律：叶子模块——只 import utils/branding，禁回边（各弹窗模块单向
// import 本模块），与 navController 零耦合。

import { createIconsIn } from '../utils.js';
import { key } from '../branding.js';

/** 与 navController.js 同源的语言判定（localStorage → html lang → navigator）。 */
function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem(key('locale')) || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

/**
 * 给一枚弹层注入左上角「<」返回钮（幂等；重复调用只更新 onBack）。
 * 按钮带 .mfs-back 标记类——mobileLayer.css 按它挂手机层几何与桌面隐藏。
 * @param {HTMLElement} overlay 弹层载体（positioned 元素——桌面模态族全部满足）
 * @param {() => void} onBack 返回语义（该弹窗自己的取消/关闭路径）
 * @returns {HTMLButtonElement} 注入的返回钮
 */
export function mobileSheetBack(overlay, onBack) {
  if (!(overlay instanceof HTMLElement)) return null;
  // 桌面零变化的第一道闸：非手机层直接不注入（桌面 DOM 逐字节不变——
  // friend-remark 等桌面 spec 按 button 数/textContent 精确断言）。跨档
  // 重排（视口缩进手机层）由各弹窗 show 路径重复调用本函数补注（幂等）。
  if (!document.body.classList.contains('mobile-layer')) return null;
  /** @type {HTMLButtonElement|null} */
  let back = overlay.querySelector(':scope > .mfs-back');
  if (back) {
    // 幂等换绑：卸掉上一任 onBack（同层重开路径不同语义时不叠监听）。
    const prev = /** @type {any} */ (back)._mfsOnBack;
    if (typeof prev === 'function') back.removeEventListener('click', prev);
  }
  if (!back) {
    back = document.createElement('button');
    back.type = 'button';
    // mobile-back-btn = 页面壳「<」同款几何（mobileLayer.css 419-442，挂
    // body.mobile-layer）；g2-fab = 液体玻璃材质；mfs-back = 本批标记
    // （桌面隐藏 + z 序 + 按压态在 mobileLayer.css「弹窗退场批」块）。
    back.className = 'mfs-back mobile-back-btn g2-fab';
    back.innerHTML = '<i data-lucide="chevron-left"></i>';
    overlay.appendChild(back);
    if (typeof lucide !== 'undefined') createIconsIn(back);
  }
  const label = tt('返回', 'Back');
  back.title = label;
  back.setAttribute('aria-label', label);
  const handler = (e) => {
    e.stopPropagation();   // 弹层的点空白关闭监听不吞这一击
    onBack();
  };
  /** @type {any} */ (back)._mfsOnBack = handler;
  back.addEventListener('click', handler);
  return back;
}
