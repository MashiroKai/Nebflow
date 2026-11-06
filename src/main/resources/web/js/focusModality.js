// focusModality.js — 输入嫌体（pointer | keyboard）的单一来源
// （input-surface-unify 批 2026-11-06）
//
// 为什么需要它：浏览器把**文本输入件**的 `:focus-visible` 口径定成「永远是」——
// 鼠标点一下也算键盘可见焦点。于是「蓝只留给键盘可见焦点」这件事纯 CSS 做不到：
// 要么所有人都吃一圈环（作者报障的就是这个），要么谁都吃不到（键盘用户落在
// 看不见的一侧，无障碍塌掉）。
//
// 本模块只做一件事：把「最近一次交互是键盘还是指针」写进
// `document.documentElement.dataset.nfModality`，供 css/inputSurface.css 的焦点
// 规则读取（`html[data-nf-modality="pointer"] … :focus-visible { outline: none }`）。
// 它不碰任何元素状态、不发事件、不改变焦点 —— 焦点可见性仍然完全由 CSS 决定。
//
// 取值规则：
//   · pointer  —— 发生 pointerdown（鼠标/触屏/手写笔）；启动时即置为 pointer
//                 （未交互前不预设用户在键盘上）；
//   · keyboard —— 按下 Tab（键盘导航的唯一起手键）。输入框里打字（字母键）**不**
//                 切到 keyboard：那会让「鼠标点进来、接着打字」的输入框中途长出一圈环。
// 兜底：本模块没加载时属性缺失，CSS 的 `:focus-visible` 环仍然生效（键盘可达性
// 不依赖 JS）；加载后指针态才被压回中性。

const root = document.documentElement;

/** @param {'pointer'|'keyboard'} modality */
function mark(modality) {
  if (root.dataset.nfModality !== modality) root.dataset.nfModality = modality;
}

mark('pointer');

document.addEventListener('pointerdown', () => mark('pointer'), true);
document.addEventListener('keydown', (e) => {
  if (e.key === 'Tab') mark('keyboard');
}, true);
