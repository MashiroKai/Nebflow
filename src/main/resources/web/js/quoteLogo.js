// quoteLogo.js — 「引用牌类型图标」的唯一来源。
//
// 为什么单独一个模块：引用牌落在**两处**（气泡上方 `.msg-quote-block` 的
// `.msg-quote-icon` / composer 附件条 `.att-ref-icon`），宿主分属 msgQuote.js 与
// reference.js —— 字形若定义在任一家里，另一家要么反向 import（加深既有环）要么
// 复制一份（第二套字形语言）。本模块对**两个宿主都是叶子方向**（msgQuote→reference→
// popArtifacts 的既有环成员集合不变，见 scripts/check-circular.mjs 读数），字形与
// 「类型 → 图标」判定因此只有一份。
//
// 三条类型规则：
//   · 图片/视频 = **真实缩略**（内联 data: preview 直用 `<img>`）；历史腿没有 preview
//     （后端 quote 快照白名单只落 type/name/path，见 WebSocketRoutes.quoteFromRefs）
//     ⇒ 退化为图片字形 QUOTE_LOGO_IMAGE —— 不伪造缩略。
//   · 文档 = **原图标**：`popArtifacts.fileIconStyle` + `.fc-icon.<cls>` 既有色族
//     （单一来源，零第二张扩展名表）。
//   · 文字 = **引用入口图标**（`QUOTE_ENTRY_ICON`，与 composer footer 那颗引用键同一枚，
//     见下方「图标统一」段）。
//
// 图标统一（作者令 2026-11-02 12:58「图标就用跟我们footer那个引用图标一样的吧」）：
// 上一批（引用牌图标化 2026-11-02 11:00）为文字引用牌新设计了 A/B/C 三个候选字形并默认
// 落 A；作者裁定**弃用自制 logo，直接复用 composer footer 引用入口键 `#quote-item-btn`
// 上那颗图标**。该字形此前内联在 msgQuote.js（`QUOTE_SVG`，lucide `quote` 双路径），
// 被 footer 键与 footer 徽章引用钮共用 —— 现在**上提到本模块**成唯一来源
// `QUOTE_ENTRY_ICON`：footer 键、footer 徽章引用钮、两类引用牌宿主（气泡 + chip）全部
// 调它，改一处即四处同步，零第二份 path 抄写。A/B/C 候选随裁定**删除**（无第二调用者，
// 留存即死代码；历史对比帧 evidence/20261102_quote-iconify/after/f7-text-logo-candidates.png
// 保留不删）。
//
// 字形单位：`stroke="currentColor"`（**只复用形状，不复用颜色** —— 各宿主按自己的色
// 上下文着色：footer 键走 `.icon-btn` 的 `--color-frame-text-muted`，引用牌走引用牌
// 文字的 `--color-text-muted` 继承链），零新色值。尺寸由宿主 CSS 单点（`.nf-qicon svg`
// 撑满 16px / chip 18px 盒；`.icon-btn svg` 20px / 手机 --micon）。

import { t } from './i18n.js';
// 「文档 = 原图标」单点：扩展名 → 色族/角标（引擎侧 .file-card 同源，零第二套）。
// 叶子模块 fileIcon.js（不是 popArtifacts.js —— 那会与 msgQuote/reference 成第三/四环）。
import { fileIconStyle, extOf } from './fileIcon.js';

// ── 引用入口图标（唯一来源）──────────────────────────────────────────────
//
// lucide `quote` 双路径原样（footer 引用键自 2026-10-09 起用它，视觉与同族复制钮
// `.msg-copy` 一致）。`data-icon` = 稳定标识：机械断言（图标统一批 assertion ①）
// 读它即可判定「两处是同一枚」，胜过比 SVG 串（属性顺序一变即假红）。
export const QUOTE_ENTRY_ICON_ID = 'quote-entry';
/** 路径表（导出给断言/取证帧直接比对 `d`，禁在别处抄第二份）。 */
export const QUOTE_ENTRY_ICON_PATHS = [
  'M16 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z',
  'M5 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z',
];
/** 引用入口图标的内联 SVG（footer 键 / footer 徽章引用钮 / 两类引用牌宿主同用这一份）。
 *  24 viewBox + `stroke-width="2"` = 该字形原生规格；在 16px 盒里等效描边 ≈1.33px，
 *  与上一批文字 logo 的 1.4 视觉同档，盒尺寸不受影响（`.nf-qicon svg` 撑满盒）。 */
export const QUOTE_ENTRY_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"'
  + ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round"'
  + ` data-icon="${QUOTE_ENTRY_ICON_ID}" aria-hidden="true" focusable="false">`
  + QUOTE_ENTRY_ICON_PATHS.map((d) => `<path d="${d}"/>`).join('')
  + '</svg>';

/** 引用入口图标 markup（与 `QUOTE_ENTRY_ICON` 同值；给「按名取字形」的调用点一个口，
 *  两处宿主都只认这一个函数，禁各自持有字符串副本）。 */
export function quoteEntryIcon() {
  return QUOTE_ENTRY_ICON;
}

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
 *      会把「文字引用」画成图片缩略 ⇒ 前缀优先，正文类（含工具/提问）统一走入口图标；
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
 *  @param {string} [hostClass] 宿主特化类（`msg-quote-icon` / `att-ref-qicon`）
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
    // 机械读数口（引用牌图标化的验收/回归探针读色族，不靠 class 串解析）。
    box.dataset.fcCls = style.cls;
    return box;
  }
  box.innerHTML = quoteEntryIcon();     // 文字 = 引用入口图标（与 footer 键同源同形）
  box.classList.add('is-glyph');
  return box;
}
