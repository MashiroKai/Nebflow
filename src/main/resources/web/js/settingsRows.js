// settingsRows.js — 设置页「iOS 设置行」组件族（单源；settings-ios-rows 批 2026-11-06）//
import { statusIcon } from './utils.js';
//
// 作者令（逐字）：「能点击下一个面板的样式都统一成苹果的这种样式 然后 底部的重启服务
//   去掉 并且这些可点击的按钮 布局规整一点」
//
// 本模块是「能点进下一个面板」的行 / 分组卡 / chevron / 行内动作按钮的**唯一**结构单点。
// 之前有三套近似实现（.secrets-entry 玻璃行卡 / .settings-collapse-toggle 下钻行 /
// 手机层 .mf-hub-row），本批收编成这套：
//   · 卡片  .set-card            （分组卡，一组一卡）
//   · 行    .set-nav             （可点进下一面板：图标位 + 主标题 + 副值 + chevron）
//           .set-row             （不可点进的读数/控件行：同一高度网格、同一左基线）
//   · 副值  .set-nav-val         （**只放一个**读数；同一数值不得在行内出现两次 ——
//                                  机检 scripts/check-settings-ios-rows.mjs 判 R3）
//   · chevron 由本模块唯一发射（lucide `chevron-right`，`.set-nav-chev`）
//   · 动作按钮 .set-btn          （行内动作 + 成组行为按钮，同一尺寸令牌）
//
// 客户端整页由 renderSettings() 一次 innerHTML 拼出，所以这里返回字符串；动态面
// （生图/Jev 子节）用同样的类名接进同一张卡即可，不需要第二套构造函数。

/**
 * chevron 唯一发射点（尺寸由 CSS 令牌 `.set-nav-chev svg` 统一钉死）。
 * 用 statusIcon 直出内联 svg —— renderSettings 一次 innerHTML 后没有 lucide
 * 升级趟（settings 页不再依赖 createIconsIn），`<i data-lucide>` 会永远是空壳。
 * @returns {string}
 */
export function setChevron() {
  return `<span class="set-nav-chev" aria-hidden="true">${statusIcon('chevron-right', 16)}</span>`;
}

/**
 * HTML 文本转义（本地小助手，避免为一个函数把 providersUi.js 拖进本模块的依赖面）。
 * @param {unknown} s
 * @returns {string}
 */
function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/**
 * 分组卡（一组一卡）。
 * @param {{ title?: string, titleId?: string, id?: string, hidden?: boolean, body: string }} o
 * @returns {string}
 */
export function setCard(o) {
  const id = o.id ? ` id="${esc(o.id)}"` : '';
  const style = o.hidden ? ' style="display:none"' : '';
  const tid = o.titleId ? ` id="${esc(o.titleId)}"` : '';
  const title = o.title ? `<div class="set-card-title"${tid}>${o.title}</div>` : '';
  return `<div class="set-card"${id}${style}>${title}${o.body}</div>`;
}

/** 一组同卡行（行间细分隔线由 CSS `+` 兄弟选择器统一出，首行无上分隔、末行无下分隔）。 */
export function setGroup(rows) {
  return `<div class="set-group" role="group">${rows}</div>`;
}

/**
 * 可点进下一面板的行（唯一形态）。
 * @param {{
 *   icon: string,        // 左侧图标位 HTML（statusIcon(...)）
 *   title: string,       // 主标题
 *   id?: string,         // 行按钮 id（既有绑定选择器）
 *   value?: string,      // 右侧副值（**一个**读数；空 = 不发射）
 *   valueTitle?: string, // 副值 title（被截断时全文）
 *   valueId?: string,    // 副值元素 id（既有读数刷新点按 id 重画，如 #stt-status-hint）
 *   meta?: string,       // chevron 前的额外读数面（仅已归一的「状态徽标」，禁重复数值）
 *   expand?: boolean,    // 原地展开行 → chevron 下转（不是「进下一面板」，保持同一行形态）
 *   expanded?: boolean,
 *   controls?: string,
 *   attrs?: string,      // 额外属性（aria-haspopup/aria-controls/data-*）
 *   extra?: string,      // 追加节点（如右侧 status 徽标）
 * }} o
 * @returns {string}
 */
export function setNavRow(o) {
  const id = o.id ? ` id="${esc(o.id)}"` : '';
  const attrs = o.attrs ? ' ' + o.attrs : '';
  const value = o.value
    ? `<span class="set-nav-val"${o.valueId ? ` id="${esc(o.valueId)}"` : ''}${o.valueTitle ? ` title="${esc(o.valueTitle)}"` : ''}>${o.value}</span>`
    : '';
  const meta = o.meta ? `<span class="set-nav-meta">${o.meta}</span>` : '';
  const cls = o.expand ? 'set-nav set-nav-expand' : 'set-nav';
  const aria = o.expand
    ? ` aria-expanded="${o.expanded ? 'true' : 'false'}"${o.controls ? ` aria-controls="${esc(o.controls)}"` : ''}`
    : '';
  return `<button type="button" class="${cls}"${id}${attrs}${aria}>`
    + `<span class="set-nav-ic">${o.icon}</span>`
    // provider-add-tabs 批（溢出零容忍断言）：标签省略号收尾时 title 全文 =
    // 豁免第四件（.set-nav-label css 四件套缺一不可）。
    + `<span class="set-nav-label" title="${esc(o.title)}">${o.title}</span>`
    + value + meta + (o.extra || '')
    + setChevron()
    + '</button>';
}

/**
 * 不可点进的读数行（控件行）：同一网格的右列承载控件。
 * @param {{ title: string, id?: string, control?: string, hint?: string, hintId?: string, hintStyle?: string }} o
 * @returns {string}
 */
export function setRow(o) {
  const id = o.id ? ` id="${esc(o.id)}"` : '';
  const hintId = o.hintId ? ` id="${esc(o.hintId)}"` : '';
  const hintStyle = o.hintStyle ? ` style="${o.hintStyle}"` : '';
  const hint = o.hint ? `<div class="set-row-hint"${hintId}${hintStyle}>${o.hint}</div>` : '';
  // provider-add-tabs 批（溢出零容忍断言）：同 set-nav-label，title 全文豁免件。
  return `<div class="set-row"${id}><span class="set-row-label" title="${esc(o.title)}">${o.title}</span>`
    + `<span class="set-row-ctrl">${o.control || ''}</span></div>${hint}`;
}
