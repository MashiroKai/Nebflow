// quoteLogo.js — 「引用牌类型图标」的唯一来源（引用牌图标化批 2026-11-02 11:00 作者令：
// 「然后引用的消息也需要图标化，文档和图片就是原图标和图片缩略，文字的话就单独设计
// 一个logo」）。
//
// 为什么单独一个模块：引用牌落在**两处**（气泡上方 `.msg-quote-block` 的
// `.msg-quote-icon` / composer 附件条 `.att-ref-icon`），宿主分属 msgQuote.js 与
// reference.js —— 字形若定义在任一家里，另一家要么反向 import（加深既有环）要么
// 复制一份（第二套字形语言）。本模块对**两个宿主都是叶子方向**（msgQuote→reference→
// popArtifacts 的既有环成员集合不变，见 scripts/check-circular.mjs 读数），字形与
// 「类型 → 图标」判定因此只有一份。
//
// 三条类型规则（作者令逐字）：
//   · 图片/视频 = **真实缩略**（内联 data: preview 直用 `<img>`）；历史腿没有 preview
//     （后端 quote 快照白名单只落 type/name/path，见 WebSocketRoutes.quoteFromRefs）
//     ⇒ 退化为图片字形 QUOTE_LOGO_IMAGE —— 不伪造缩略。
//   · 文档 = **原图标**：`popArtifacts.fileIconStyle` + `.fc-icon.<cls>` 既有色族
//     （单一来源，零第二张扩展名表）。
//   · 文字 = 本模块新设计的 logo（默认候选 A；候选对比见 evidence/20261102_quote-iconify/）。
//
// 字形单位：viewBox 0 0 16 16、`stroke="currentColor"`（颜色随宿主文字色），零新色值。

import { t } from './i18n.js';
// 「文档 = 原图标」单点：扩展名 → 色族/角标（引擎侧 .file-card 同源，零第二套）。
// 叶子模块 fileIcon.js（不是 popArtifacts.js —— 那会与 msgQuote/reference 成第三/四环）。
import { fileIconStyle, extOf } from './fileIcon.js';

// 字形（候选对比见文件头；默认 = a）。三个候选**导出**：候选对比的取证帧
// （tests/quote-iconify-shots.mjs F7）直接用同一份字形渲染，禁止在测试里抄第二份。
export const QUOTE_LOGO_CANDIDATES = {
  // A 双引号（上）+ 双横线（下）：读作「引用了一段文字」，16px 下最清晰 —— 默认。
  a: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">'
    + '<path d="M3.2 2.6c-1 0-1.8.8-1.8 1.8v2.2c0 1 .8 1.8 1.8 1.8.5 0 .9-.2 1.2-.5-.2 1.6-1 2.6-2.4 3.2"/>'
    + '<path d="M9.4 2.6c-1 0-1.8.8-1.8 1.8v2.2c0 1 .8 1.8 1.8 1.8.5 0 .9-.2 1.2-.5-.2 1.6-1 2.6-2.4 3.2"/>'
    + '<path d="M3 11.6h10"/><path d="M3 14h6.4"/>'
    + '</svg>',
  // B 票据卡（外框 + 顶部裁切线）+ 左上引号：读作「引用卡」，与文件卡同族；但 16px
  // 下外框占满盒体，与图片缩略的矩形轮廓撞形。
  b: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">'
    + '<path d="M2.4 3.6h11.2v8.8H2.4z"/>'
    + '<path d="M5.6 3.6v2.2"/><path d="M8 3.6v2.2"/>'
    + '<path d="M4.4 9.2c.8 0 1.4-.6 1.4-1.4v-1"/>'
    + '<path d="M6.6 8.9c.6 0 1-.4 1-1V7"/>'
    + '<path d="M9.4 9.2h2.6"/>'
    + '</svg>',
  // C 三横线（文本）+ 右下火焰引号：读作「引文」，动势强；16px 下两件争主次易糊。
  c: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">'
    + '<path d="M2.4 3.4h11.2"/><path d="M2.4 6.6h11.2"/><path d="M2.4 9.8h6.2"/>'
    + '<path d="M10.6 13.4c1.6-.4 2.6-1.6 2.6-3.2 0-.9-.4-1.6-1-2.2-.2.7-.6 1.2-1.2 1.6.2-1.1-.2-2-1.2-2.8.2 1.6-.6 2.4-1.4 3.2-.5.5-.8 1-.8 1.6 0 1 .8 1.8 3 1.8z"/>'
    + '</svg>',
};

/** 文字引用牌 logo（默认候选 A）。 */
export const QUOTE_LOGO_TEXT = QUOTE_LOGO_CANDIDATES.a;

/** 图片/视频引用牌在**没有内联缩略**时的字形（历史腿；不伪造缩略）。 */
export const QUOTE_LOGO_IMAGE = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">'
  + '<rect x="2.2" y="2.8" width="11.6" height="10.4" rx="1.6"/>'
  + '<circle cx="6" cy="6.4" r="1.1"/>'
  + '<path d="M2.6 11.4l3.2-3 2.4 2.2 2-1.8 3.2 3"/>'
  + '</svg>';

/** spec → 类型（'image' | 'file' | 'text'）。
 *
 *  spec = `{ excerpt, attachments, itemIndex }`（引用块视图 / 引用 ref.content 的
 *  共同投影）。判定顺序：
 *   ① `itemIndex` 是整数 ⇒ 叠卡单张（内容必是那一张图/视频）；
 *   ② 摘要前缀 = 类型单点（`excerptOf` 的同一份 t() 词表：`[图片]` / `[文件]`）；
 *      —— 文本行引用会**合入兄弟媒体附件**（微信整消息语义），只看 attachments[0]
 *      会把「文字引用」画成图片缩略 ⇒ 前缀优先，正文类（含工具/提问）统一走文字 logo；
 *   ③ 兜底 = 'text'。 */
export function quoteKindOf(spec) {
  const q = spec || {};
  if (Number.isInteger(q.itemIndex)) return 'image';
  const ex = String(q.excerpt || '');
  if (ex.startsWith(t('chat.quoteTypeImage'))) return 'image';
  if (ex.startsWith(t('chat.quoteTypeFile'))) return 'file';
  return 'text';
}

/** 引用牌类型图标元素（两处宿主同一实现）。
 *  @param {{excerpt?:string, attachments?:Array, itemIndex?:number|null}} spec
 *  @param {string} [hostClass] 宿主特化类（`msg-quote-icon` / `att-ref-thumb`）
 *  @returns {HTMLElement} `.nf-qicon`（尺寸/圆角/裁切由 CSS 单点，见 chat.css）。 */
export function quoteTypeIcon(spec, hostClass) {
  const box = document.createElement('span');
  box.className = 'nf-qicon' + (hostClass ? ' ' + hostClass : '');
  box.setAttribute('aria-hidden', 'true');
  const atts = Array.isArray(spec && spec.attachments) ? spec.attachments : [];
  const first = atts[0] || null;
  const kind = quoteKindOf(spec);
  if (kind === 'image') {
    const preview = first && typeof first.preview === 'string' && first.preview.startsWith('data:') ? first.preview : '';
    if (preview) {
      const img = document.createElement('img');
      img.className = 'nf-qicon-img';
      img.src = preview;
      img.alt = '';
      img.draggable = false;
      box.appendChild(img);
      box.classList.add('is-thumb');
    } else {
      box.innerHTML = QUOTE_LOGO_IMAGE;   // 历史腿无 preview ⇒ 图片字形
      box.classList.add('is-glyph');
    }
    return box;
  }
  if (kind === 'file') {
    const name = String((first && first.name) || (first && first.path) || '');
    const style = fileIconStyle(extOf(name));
    box.classList.add('fc-icon', style.cls, 'is-file');
    box.textContent = style.label;
    // 机械读数口（引用牌图标化批的验收/回归探针读色族，不靠 class 串解析）。
    box.dataset.fcCls = style.cls;
    return box;
  }
  box.innerHTML = QUOTE_LOGO_TEXT;
  box.classList.add('is-glyph');
  return box;
}
