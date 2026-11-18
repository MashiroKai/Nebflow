// askMediaPreview.js — AskUser 选项「媒体预览」的数据形态 + 渲染单点
// （askuser-media 批 2026-11-18；作者令 17:52 逐字「要能填充图片和视频 并且支持
// 多张叠卡显示 … 这个也可以给待办应用上」）。
//
// ── 为什么单独一个模块（而不是塞回 chat.js）────────────────────────────────
//  ① **可复用是硬要求**：作者要「这个也可以给待办应用上」⇒ 形态与渲染入口必须
//     离开 `showOptions` 的闭包，才能被第二个宿主（待办决策卡 / 其它面板）import。
//     复用入口 = 本模块导出的 [[buildAskMediaGroup]] + [[askPreviewMediaItems]]
//     （见文件末「复用入口」段）。
//  ② **叠卡不新造**：媒体组（≥2 件）直接喂消息面既有组件 `buildMediaStack`
//     （popArtifacts.js，几何/扇形/拖划/横滑/展开列/键盘全在那一份；
//     形在 design-system.css ⑨ `.ds-media-stack` 族）。本模块**零第二套叠卡**。
//
// ── 数据形态（本批对外契约）──────────────────────────────────────────────
//   pv = { type, colors?, src?, poster?, items? }
//     type='swatch' + colors[1..5]                     → 色带（既有形态，**未动**）
//     type='image'  + src                              → 单图小缩略 56×40（既有，**未动**）
//     type='video'  + src [+ poster]                   → 单段视频大面（静帧 + 点击播放）
//     type='media'  + items[{kind,src,poster?,name?,path?}] → 媒体组（1 件 = 单件大面；
//                                                             ≥2 件 = 叠卡组）
//
//   `src` / `poster` 两种来源都支持：
//     · 远端 URL（`https://…` / `data:…` / 任意 `scheme:`）→ 元素 `src` 直取；
//     · 本地路径（`/abs/x.png`、`~/x.png`）→ 折进 `path`，走消息面既有票腿
//       （`hydrateMedia` 的 `/api/nf-file` 链）——字节永不进模型上下文。
//     判据单点 = 本文件的 [[normalizeAskMedia]]（URL_SCHEME）。
//
// ── 视频交互形态（本批选定，附理由）────────────────────────────────────
//   **静帧 + 可播放**（不是自动静音循环）：
//     · 静帧 = `poster`（给了就用）/ 消息面既有「内容封面」抓帧（videoCover.js，
//       含跨域与解码失败的静默降级）/ 兜底黑底 + 播放键；
//     · 播放 = 点击 `.play-ring` 或原生控件（媒体组件既有行为，非本模块新造）。
//   为什么不自动静音循环：决策卡里同时可能有多段视频，自动播放会**互相争抢**
//   并盖住「哪个选项更适合」的判读；静帧在视觉上与图片面同族（一眼可比），
//   要点开才动 —— 与消息面视频件的既有语义**逐字一致**（同源优先）。
//
// ── 点击语义 ─────────────────────────────────────────────────────────────
//   整面点击 = **选中该选项**（不是打开 Canvas/灯箱）：这是「用媒体来选」的
//   场合，点媒体就该选中。叠卡区的内部手势（拖划/横滑翻页、展开钮、视频播放键）
//   各自消费自己的事件，**不**顺带选中（见 buildAskMediaGroup 的 stopPropagation
//   与 `suppressClick` 语义）。

import { buildMediaStack, buildMediaFlat } from './popArtifacts.js';

/** 带 scheme 的串 = URL（`https:` / `data:` / `blob:` …）；其余视作本地路径。 */
const URL_SCHEME = /^[a-z][a-z0-9+.-]*:/i;

/**
 * 预览形态分类（chat.js 据此决定「旧小缩略」还是「新媒体面」）：
 *   `'swatch'` | `'thumb'`（旧单图 56×40）| `'media'`（视频 / 媒体组）| `null`（无预览）。
 */
export function askPreviewKind(pv) {
  if (!pv || typeof pv !== 'object') return null;
  const type = String(pv.type || '').toLowerCase();
  if (type === 'swatch') {
    const colors = Array.isArray(pv.colors) ? pv.colors.filter((c) => typeof c === 'string' && c.trim()) : [];
    return colors.length ? 'swatch' : null; // 空色板 ⇒ 无预览（既有 E3 语义）
  }
  if (type === 'image') {
    return typeof pv.src === 'string' && pv.src.trim() ? 'thumb' : null; // 旧小缩略，形态零改
  }
  if (type === 'video' || type === 'media') {
    return askPreviewMediaItems(pv).length ? 'media' : null;
  }
  return null;
}

/**
 * 一件媒体件的**规范化形态**（对外契约；待办等宿主按同一形状造数据即可复用）。
 * @param {any} m
 * @param {string} [fallbackKind]
 * @returns {{kind:'image'|'video', src:string, path:string, poster:string, name:string}|null}
 */
export function normalizeAskMedia(m, fallbackKind) {
  if (!m || typeof m !== 'object') return null;
  const kind = String(m.kind || m.type || fallbackKind || 'image').toLowerCase();
  if (kind !== 'image' && kind !== 'video') return null;
  const src = typeof m.src === 'string' && m.src.trim() ? m.src.trim() : '';
  const path = typeof m.path === 'string' && m.path.trim() ? m.path.trim() : '';
  const isUrl = !!src && URL_SCHEME.test(src);
  if (!src && !path) return null;
  const poster = typeof m.poster === 'string' && m.poster.trim() ? m.poster.trim() : '';
  return {
    kind,
    src: isUrl ? src : '',
    // `src` 是裸本地路径（无 scheme）⇒ 折进 path 走票腿；显式 path 优先。
    path: path || (isUrl ? '' : src),
    poster,
    name: typeof m.name === 'string' ? m.name : '',
  };
}

/**
 * 预览 → 媒体组（规范化后的件数组）。非媒体形态（swatch / 无预览）⇒ `[]`。
 * 这是**复用入口之一**：宿主拿到数组即可自己接管渲染。
 */
export function askPreviewMediaItems(pv) {
  if (!pv || typeof pv !== 'object') return [];
  const type = String(pv.type || '').toLowerCase();
  if (type === 'swatch' || type === 'image') return []; // 旧两形态不走媒体面
  if (Array.isArray(pv.items)) {
    return pv.items.map((m) => normalizeAskMedia(m, 'image')).filter(Boolean);
  }
  if (type === 'video' || type === 'media') {
    const one = normalizeAskMedia(pv, type === 'video' ? 'video' : 'image');
    return one ? [one] : [];
  }
  return [];
}

/* ── 旧两形态的渲染（自 chat.js 原样搬来，markup 逐字不变 ⇒ 零回归）──────── */

/** 色带槽（既有形态；markup 与改前逐字一致）。
 *  构建走 **DOM 构建器**（不是裸 innerHTML）：本文件是**新文件**，组件契约闸 R5 对
 *  新文件要求五项计数全 0（`scripts/check-component-contract.mjs`）；DOM 构建产物与
 *  改前的字符串拼装**逐节点等价**（同 class、同内联样式）。 */
function buildSwatchPreview(pv) {
  const colors = Array.isArray(pv.colors) ? pv.colors.filter((c) => typeof c === 'string' && c.trim()) : [];
  if (!colors.length) return null;
  const span = document.createElement('span');
  span.className = 'option-preview';
  span.setAttribute('aria-hidden', 'true');
  colors.forEach((c, i) => {
    const stripe = document.createElement('span');
    stripe.className = 'preview-swatch';
    stripe.style.background = c.trim();
    if (i > 0) stripe.style.borderLeft = '1px solid var(--color-surface)';
    span.appendChild(stripe);
  });
  return span;
}

/** 单图小缩略槽（既有形态；markup 与改前逐字一致，含 §6 淡入 / 失败隐藏）。 */
function buildThumbPreview(pv) {
  if (typeof pv.src !== 'string' || !pv.src.trim()) return null;
  const span = document.createElement('span');
  span.className = 'option-preview';
  span.setAttribute('aria-hidden', 'true');
  const img = document.createElement('img');
  img.className = 'preview-img';
  img.src = normalizeSvgDataUri(pv.src);
  img.alt = '';
  img.loading = 'lazy';
  img.addEventListener('load', () => img.classList.add('nf-loaded'));
  img.addEventListener('error', () => { span.style.display = 'none'; });
  span.appendChild(img);
  return span;
}

/** Compat fallback for viewBox-only SVG data-URI previews (author report
 *  2026-09-07) — 自 chat.js 原样搬来，行为与注释逐字不变。 */
function normalizeSvgDataUri(src) {
  if (typeof src !== 'string' || !src.startsWith('data:image/svg+xml')) return src;
  const comma = src.indexOf(',');
  if (comma < 0) return src;
  const header = src.slice(0, comma + 1);
  const body = src.slice(comma + 1);
  let svg;
  if (/;base64/i.test(header)) {
    try {
      svg = new TextDecoder().decode(Uint8Array.from(atob(body), (ch) => ch.charCodeAt(0)));
    } catch { return src; }
  } else {
    try { svg = decodeURIComponent(body); } catch { return src; }
  }
  const tag = svg.match(/<svg\b[^>]*>/i);
  if (!tag) return src;
  if (/\bwidth\s*=|\bheight\s*=/i.test(tag[0])) return src;
  const vb = tag[0].match(/\bviewBox\s*=\s*(?:"([^"]*)"|'([^']*)')/i);
  if (!vb) return src;
  const dims = (vb[1] ?? vb[2]).trim().split(/[\s,]+/).map(Number);
  if (dims.length !== 4 || dims.some((n) => !Number.isFinite(n)) || dims[2] <= 0 || dims[3] <= 0) return src;
  const fixed = svg.replace(tag[0], tag[0].replace(/^<svg/i, `<svg width="${dims[2]}" height="${dims[3]}" `));
  if (/;base64/i.test(header)) {
    const bytes = new TextEncoder().encode(fixed);
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return header + btoa(bin);
  }
  return header + encodeURIComponent(fixed);
}

/* ── 新媒体面 ───────────────────────────────────────────────────────────── */

/**
 * **媒体组渲染（复用入口）** —— 1 件 = 单件大面（图/视频，同消息面 flat 卡），
 * ≥2 件 = **消息面既有叠卡组件**的一张卡脸 + 露边 + 展开 N（零第二套）。
 *
 * @param {Array<any>} items 归一化后的媒体件（[[askPreviewMediaItems]] 的产物）
 * @param {{onPick?: (item:any, index:number) => void}} [opts]
 *   `onPick` = 叠卡整面/展开行点击的动作。**缺省不动作**（宿主自己在根上挂点击）。
 * @returns {HTMLDivElement|null}
 */
export function buildAskMediaGroup(items, opts = {}) {
  const list = (items || []).filter(Boolean);
  if (!list.length) return null;
  const wrap = document.createElement('div');
  wrap.className = 'option-preview-media';
  const pick = typeof opts.onPick === 'function' ? opts.onPick : null;
  if (list.length === 1) {
    wrap.classList.add('option-preview-media--single');
    // 单件面 = 消息面同款 flat 卡（`onOpen: null` ⇒ 不挂面内点击 = 整面点击交给
    // 选项按钮自己；`corner: null` ⇒ 不挂 Canvas 角标）。图片面/视频面同族。
    wrap.appendChild(buildMediaFlat(list[0], { onOpen: null, corner: null }));
    return wrap;
  }
  const stack = buildMediaStack(list, {
    onOpen: (item, index) => { if (pick) pick(item, index); },
  });
  stack.classList.add('option-media-stack');
  // 叠卡区内部的点击一律不冒泡到选项按钮：选中动作**只**由 `onOpen`（真点击）
  // 单点驱动 ⇒ 拖划/横滑翻页（组件置 `suppressClick`）与「展开 N」钮都不会
  // 顺手把选项选掉。视频播放键/控件自带 stopPropagation（组件内）。
  stack.addEventListener('click', (e) => e.stopPropagation());
  wrap.appendChild(stack);
  return wrap;
}

/**
 * 选项预览的**唯一渲染入口**（chat.js 两个调用点共用）。
 * @param {any} pv `option.preview`
 * @param {{onPick?: (item:any, index:number) => void}} [opts]
 * @returns {HTMLElement|null} 无预览 / 形态不可渲染 ⇒ `null`（调用方按无预览渲染）
 */
export function buildAskPreviewNode(pv, opts = {}) {
  switch (askPreviewKind(pv)) {
    case 'swatch': return buildSwatchPreview(pv);
    case 'thumb': return buildThumbPreview(pv);
    case 'media': return buildAskMediaGroup(askPreviewMediaItems(pv), opts);
    default: return null;
  }
}
