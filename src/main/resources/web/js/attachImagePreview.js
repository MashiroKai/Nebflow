// attachImagePreview.js — 好友/群图片预览传输批 · **解耦包「压缩件生成」**（作者 2026-09-19 20:4x 裁定）。
//
// 定位：发送侧**压缩件生成器** —— 把用户挑中的图片转成一个「小体积预览件」，供
// 后续（跨仓腿 ① 落地后的**接线批**）随消息载荷申报给对端。本件**只生成**，不申报、
// 不上传、不碰任何网络面。
//
// 🔴 本批范围（作者逐字收窄）：
//   · 含：压缩件生成 + 传输契约声明（本件 + `attachPreviewField.js` + `AttachmentPreviewContract.scala`）；
//   · 不含：传输集成（`RestApiRoutes.scala` / `NeblinkModel.scala` / `messages.js` 的接线，
//     候跨仓腿 ① neblink-server 侧附件 DTO 可选 `preview` 落地后另批）；
//   · 不含：E2E 验收（真渲染收发 round / 兼容矩阵四格 / 下载 sha 一致）——本件**不据其判红判绿**。
//
// ── 参数基线（作者裁定 = 照前身 `imgpreview-impl` 报告 §6 实测表；**禁自行另立口径**）──
//   · 长边钳制 **≤ 1280 px**，🔴 **禁放大**（任一边 ≤ 1280 ⇒ 只重编码，尺寸逐像素不变）；
//   · **WebP q0.82 首选 / JPEG q0.82 兜底**（`canvas.toBlob` 产 `image/webp` 失败或不可用
//     ⇒ 自动落 JPEG；探测与落档**两向**都给读数）；
//   · 单预览**软上限 ~180 KB**（= 184,320 B，**离线取值，非硬闸**）：超限 ⇒ 走**降级梯**
//     （**先降质量 q → 再降长边**），梯的**档位与终止条件逐档给读数**。
//
// 证据锚（只读参照件）：`.nebflow/evidence/20260919_imgpreview-impl/params-measure.md` /
// `params-measure-realphoto.md`（样本口径：壁纸 = 压缩比乐观上界 / 实拍 = 实况 / UI 截图 = 悲观下界）。
//
// ── 出口契约 ──
//   `makePreviewFrame(source, opts)` → `{ blob, mime, width, height, bytes, quality, longEdge,
//   rung, capMet, mode, upscaled, scaleRatio, source:{…}, ladder:[…], probe:{…}, format, formatReason }`
//   即「Blob + 生成元数据（mime / 尺寸 / 字节数 / 实际档位）」。
//
// 🔴 **本件没有「这件是不是图片」的判据**（刻意）：判据单源在既有
//   `attachmentPreview.js::isImageAttachmentName`（ext→itemType 的真源 = `fileViewers.js`）。
//   本件**不开第二张扩展名表**——它只接受「调用方已判定的图片源」。接线批先判名、再调本件。
//
// 🔴 本件**零 DOM 查询、零全局副作用**：除按需建 canvas 外不触碰页面；模块可在无 DOM
//   环境被 import（顶层不访问 `document`），DOM 缺失时以结构化 `ok:false` 收口而非抛栈。

/** 长边钳制上限（作者裁定：≤1280 px）。 */
export const PREVIEW_MAX_LONG_EDGE = 1280;

/** 基线质量档（作者裁定：WebP q0.82 首选 / JPEG q0.82 兜底）。 */
export const PREVIEW_BASE_QUALITY = 0.82;

/** 单预览软上限 = 180 KiB = 184,320 B（**软**：超限走降级梯，不做硬性拒绝）。 */
export const PREVIEW_SOFT_CAP_BYTES = 184320;

/** 软上限的人类可读标签（回显用；禁裸写 "180KB" 而混 MB/MiB 量纲）。 */
export const PREVIEW_SOFT_CAP_LABEL = '180 KiB (184,320 B)';

/** 首选格式。 */
export const PREVIEW_PREFERRED_MIME = 'image/webp';

/** 兜底格式（WebP 探测失败 / 编码失败时落此档）。 */
export const PREVIEW_FALLBACK_MIME = 'image/jpeg';

/** 降级梯的**长边**档位（递减；顺序 = 「先降质量 → 再降长边」中的后段）。 */
export const PREVIEW_EDGE_STEPS = Object.freeze([1280, 1024, 896, 768]);

/** 降级梯的**质量**档位（递减；同一长边内先逐档降 q）。档 0 = 基线的 0.82。 */
export const PREVIEW_QUALITY_STEPS = Object.freeze([0.82, 0.72, 0.62, 0.52]);

/** 构造降级梯：外层长边（递减）× 内层质量（递减）⇒ 档 0 恒为基线 `q0.82@1280px`。
 *  @returns {Array<{index:number,longEdge:number,quality:number,label:string}>} */
function buildLadder() {
  const out = [];
  for (const longEdge of PREVIEW_EDGE_STEPS) {
    for (const quality of PREVIEW_QUALITY_STEPS) {
      out.push(Object.freeze({ index: out.length, longEdge, quality, label: `q${quality}@${longEdge}px` }));
    }
  }
  return out;
}

/** 降级梯全表（冻结；`index 0` = 基线档，索引递增 = 降级加深）。 */
export const PREVIEW_LADDER = Object.freeze(buildLadder());

/**
 * 按上限等比夹取尺寸 —— **禁放大**。
 *
 * 判据：`scale = min(1, maxEdge / max(w, h))` ⇒ 长边已 ≤ 上限时 `scale === 1`，
 * 返回**逐像素不变**的尺寸（`unchanged: true` = 「原样 / 仅重编码」档）。
 * 结果长边再做一次 `min(…, maxEdge)` 夹取：`Math.round` 在半像素处可能越界 1 px，
 * 而「钳制 ≤1280」是硬承诺，不得被舍入破坏。
 *
 * @param {number} width @param {number} height @param {number} maxEdge
 * @returns {{width:number,height:number,scale:number,unchanged:boolean,longEdge:number}} */
export function fitWithinLimits(width, height, maxEdge) {
  const w = Math.max(1, Math.round(width));
  const h = Math.max(1, Math.round(height));
  const srcLong = Math.max(w, h);
  const edge = Math.max(1, Math.round(maxEdge));
  if (srcLong <= edge) {
    return { width: w, height: h, scale: 1, unchanged: true, longEdge: srcLong };
  }
  const scale = edge / srcLong;
  let outW = Math.max(1, Math.round(w * scale));
  let outH = Math.max(1, Math.round(h * scale));
  if (Math.max(outW, outH) > edge) outW = outW >= outH ? edge : outW;
  if (Math.max(outW, outH) > edge) outH = outH >= outW ? edge : outH;
  return { width: outW, height: outH, scale, unchanged: false, longEdge: Math.max(outW, outH) };
}

/** 提示内存缓存（探测结果进程内不变：同一浏览器会话的能力是常量）。 */
let webpProbeCache = null;

/** 强制重探（**仅给取数/自测用**；接线面不需要，禁在业务路径调用）。 */
export function resetFormatProbe() {
  webpProbeCache = null;
}

/**
 * 能力探测：`canvas.toBlob` 能否产出 `image/webp`。
 *
 * 判据三层（任一不成立即判「不可用」）：
 *   ① `toBlob` 回调拿到**非空** blob；② `blob.type === 'image/webp'`（不回 `image/png`
 *   —— 这正是「不支持时静默降级到 PNG」的浏览器行为）；③ `blob.size > 0`。
 * 抛异常同样记 `supported:false` 并把原因写进 `reason`（禁静默吞）。
 *
 * @returns {Promise<{supported:boolean,mime:string,bytes:number,reason:string}>} */
export async function probeWebpSupport() {
  if (webpProbeCache) return webpProbeCache;
  webpProbeCache = await runFormatProbe();
  return webpProbeCache;
}

/** 探测实现体（不缓存）。 */
async function runFormatProbe() {
  if (!canProbeFormat()) {
    return { supported: false, mime: '', bytes: 0, reason: 'no-dom-canvas' };
  }
  try {
    const probe = createCanvas(8, 8);
    const ctx = probe.getContext('2d');
    if (!ctx) return { supported: false, mime: '', bytes: 0, reason: 'no-2d-context' };
    // 8×8 渐变：给编码器真实可压的内容，避免纯色块让部分实现提前返回空 blob。
    const grad = ctx.createLinearGradient(0, 0, 8, 8);
    grad.addColorStop(0, '#000000');
    grad.addColorStop(1, '#ffffff');
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, 8, 8);
    const blob = await canvasToBlob(probe, PREVIEW_PREFERRED_MIME, PREVIEW_BASE_QUALITY);
    if (!blob) return { supported: false, mime: '', bytes: 0, reason: 'to-blob-null' };
    if (blob.type !== PREVIEW_PREFERRED_MIME) {
      return { supported: false, mime: blob.type, bytes: blob.size, reason: 'type-not-webp' };
    }
    if (blob.size <= 0) return { supported: false, mime: blob.type, bytes: 0, reason: 'empty-blob' };
    return { supported: true, mime: blob.type, bytes: blob.size, reason: 'ok' };
  } catch (err) {
    return { supported: false, mime: '', bytes: 0, reason: `probe-threw:${errText(err)}` };
  }
}

/** DOM 面是否够用（用能力检测而非 UA 嗅探）。 @returns {boolean} */
function canProbeFormat() {
  return typeof document !== 'undefined' && typeof document.createElement === 'function';
}

/** @param {number} w @param {number} h @returns {HTMLCanvasElement} */
function createCanvas(w, h) {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  return c;
}

/** 错误对象 → 一行文本（结果里只带字符串，禁把异常对象透出去）。 @returns {string} */
function errText(err) {
  return err && err.message ? String(err.message) : String(err);
}

/** `HTMLCanvasElement.toBlob` 的 promise 包装：**永不 reject**（失败 ⇒ `null`，由调用方落兜底）。 */
function canvasToBlob(canvas, mime, quality) {
  return new Promise((resolve) => {
    try {
      if (typeof canvas.toBlob !== 'function') { resolve(null); return; }
      canvas.toBlob((blob) => { resolve(blob || null); }, mime, quality);
    } catch (err) {
      resolve(null);
    }
  });
}

/** 源形态归类：`Blob|File`（有字节）vs `HTMLImageElement|ImageBitmap`（已解码）。
 *  @returns {'blob'|'image'|'unknown'} */
function sourceKind(source) {
  if (!source) return 'unknown';
  if (typeof Blob !== 'undefined' && source instanceof Blob) return 'blob';
  if (typeof source.width === 'number' && typeof source.height === 'number') return 'image';
  return 'unknown';
}

/** 源的静态读数（体积/类型/名称），**不解码**。 @returns {{kind:string,bytes:number,mime:string,name:string}} */
export function describePreviewSource(source) {
  const kind = sourceKind(source);
  const bytes = kind === 'blob' && typeof source.size === 'number' ? source.size : 0;
  const mime = kind === 'blob' && source.type ? String(source.type) : '';
  const name = source && source.name ? String(source.name) : '';
  return { kind, bytes, mime, name };
}

/**
 * 解码源 → `{width,height,draw,release}`。
 *
 * 三条腿按序尝试（都走平台既有面，禁自建解码器）：
 *   ① 已解码源（`HTMLImageElement` / `ImageBitmap`）直接用；
 *   ② `createImageBitmap(blob)`（现代路径，且**可 close 释放**）；
 *   ③ `<img>` + object URL（老路径，收尾必 revoke）。
 *
 * @param {any} source
 * @returns {Promise<{width:number,height:number,draw:any,release:()=>void}>} */
async function decodePreviewSource(source) {
  const kind = sourceKind(source);
  if (kind === 'image') {
    const w = Number(source.naturalWidth || source.width) || 0;
    const h = Number(source.naturalHeight || source.height) || 0;
    if (w <= 0 || h <= 0) throw new Error('decoded-image-has-no-size');
    return { width: w, height: h, draw: source, release: () => {} };
  }
  if (kind !== 'blob') throw new Error('unsupported-source');
  if (typeof createImageBitmap === 'function') {
    const bitmap = await createImageBitmap(source);
    return {
      width: bitmap.width,
      height: bitmap.height,
      draw: bitmap,
      release: () => { if (typeof bitmap.close === 'function') bitmap.close(); },
    };
  }
  if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
    throw new Error('no-decode-path');
  }
  const url = URL.createObjectURL(source);
  try {
    const img = await loadHtmlImage(url);
    return {
      width: img.naturalWidth,
      height: img.naturalHeight,
      draw: img,
      release: () => URL.revokeObjectURL(url),
    };
  } catch (err) {
    URL.revokeObjectURL(url);
    throw err;
  }
}

/** `<img>` 解码（`decode()` 优先，失败退 `onload`）。 */
function loadHtmlImage(url) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('image-load-failed'));
    img.src = url;
  });
}

/** 把解码源画进目标尺寸的 canvas。 */
function renderToCanvas(decoded, width, height) {
  const canvas = createCanvas(width, height);
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('no-2d-context');
  if ('imageSmoothingEnabled' in ctx) ctx.imageSmoothingEnabled = true;
  if ('imageSmoothingQuality' in ctx) ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(decoded.draw, 0, 0, width, height);
  return canvas;
}

/**
 * 生成预览压缩件 —— **本件的唯一出口**。
 *
 * @param {any} source `Blob`/`File`（有字节）或已解码的 `HTMLImageElement`/`ImageBitmap`。
 * @param {{softCapBytes?:number,forceFormat?:string,probe?:object,maxSourceBytes?:number}} [opts]
 *   · `softCapBytes` — 覆盖软上限（取数/自测用；业务路径不传 ⇒ 走 [[PREVIEW_SOFT_CAP_BYTES]]）；
 *   · `forceFormat` — 强制落 [[PREVIEW_FALLBACK_MIME]]（**取「兜底档读数」用**：等价于
 *     「能力探测失败/不可用」这一分支，探测与落档两向读数由此取得）；
 *   · `probe` — 注入探测结果（跳过真实探测；取数/自测用）。
 * @returns {Promise<object>} 见文件头「出口契约」。`ok:false` ⇒ 调用方落**「无预览」分支**
 *   （契约字段可选 ⇒ 现行为不变），`reason` 给出机器可读原因，`ladder` 仍带逐档读数。 */
export async function makePreviewFrame(source, opts) {
  const options = opts || {};
  const cap = Number.isFinite(options.softCapBytes) ? Number(options.softCapBytes) : PREVIEW_SOFT_CAP_BYTES;
  const sourceInfo = describePreviewSource(source);
  const base = {
    ok: false,
    reason: '',
    blob: null,
    mime: '',
    width: 0,
    height: 0,
    bytes: 0,
    quality: PREVIEW_BASE_QUALITY,
    longEdge: 0,
    rung: -1,
    capMet: false,
    mode: 'none',
    upscaled: false,
    scaleRatio: 1,
    source: { ...sourceInfo, width: 0, height: 0 },
    ladder: [],
    probe: { supported: false, mime: '', bytes: 0, reason: 'not-probed' },
    format: '',
    formatReason: '',
    softCapBytes: cap,
  };

  // ── 格式选路：探测（或注入/强制兜底）──────────────
  let probe;
  if (options.probe && typeof options.probe === 'object') {
    probe = { supported: !!options.probe.supported, mime: String(options.probe.mime || ''), bytes: Number(options.probe.bytes) || 0, reason: String(options.probe.reason || 'injected') };
  } else if (options.forceFormat === PREVIEW_FALLBACK_MIME) {
    probe = { supported: false, mime: '', bytes: 0, reason: 'forced-fallback' };
  } else {
    probe = await probeWebpSupport();
  }
  const useWebp = probe.supported && options.forceFormat !== PREVIEW_FALLBACK_MIME;
  const format = useWebp ? PREVIEW_PREFERRED_MIME : PREVIEW_FALLBACK_MIME;
  const formatReason = useWebp ? 'probe-supported' : (probe.reason || 'probe-failed');
  base.probe = probe;
  base.format = format;
  base.formatReason = formatReason;

  // ── 解码 ────────────────────────────────────────
  let decoded;
  try {
    decoded = await decodePreviewSource(source);
  } catch (err) {
    base.reason = `decode-failed:${errText(err)}`;
    return base;
  }
  base.source.width = decoded.width;
  base.source.height = decoded.height;

  try {
    // ── 逐档编码（降级梯）─────────────────────────
    let chosen = null;
    for (const rung of PREVIEW_LADDER) {
      const dims = fitWithinLimits(decoded.width, decoded.height, rung.longEdge);
      let blob = null;
      try {
        blob = await canvasToBlob(renderToCanvas(decoded, dims.width, dims.height), format, rung.quality);
      } catch (err) {
        blob = null;
      }
      const reading = {
        rung: rung.index,
        label: rung.label,
        quality: rung.quality,
        longEdge: rung.longEdge,
        width: dims.width,
        height: dims.height,
        bytes: blob ? blob.size : -1,
        mime: blob ? blob.type : '',
        capMet: !!blob && blob.size <= cap,
        smaller: !!blob && sourceInfo.bytes > 0 && blob.size < sourceInfo.bytes,
        unchanged: dims.unchanged,
      };
      base.ladder.push(reading);
      if (!blob) continue;
      // 终止条件（两条都成立才收档）：① 体积 ≤ 软上限；② **比原件小**
      // （② 是「预览」二字的语义底线 —— 不比原件小就没有发它的意义；源码未变、
      //  高熵小图重编码后可能变大，此时把 ① 之外的档继续试到底）。
      if (reading.capMet && reading.smaller) { chosen = { blob, reading, dims }; break; }
    }

    // ── 收档与读数 ─────────────────────────────────
    const last = base.ladder.length ? base.ladder[base.ladder.length - 1] : null;
    const pick = chosen ? chosen.reading : null;
    if (pick) {
      base.ok = true;
      base.blob = chosen.blob;
      base.mime = pick.mime || format;
      base.width = pick.width;
      base.height = pick.height;
      base.bytes = pick.bytes;
      base.quality = pick.quality;
      base.longEdge = Math.max(pick.width, pick.height);
      base.rung = pick.rung;
      base.capMet = pick.capMet;
      base.mode = pick.unchanged ? 'reencode-only' : 'downscaled';
    } else if (last) {
      // 梯走到底仍未同时满足两条终止条件 ⇒ **不发预览**（结构化拒，带逐档读数）。
      base.reason = last.bytes > 0
        ? (last.capMet ? 'no-gain-over-source' : 'soft-cap-not-met')
        : 'encode-failed';
      base.bytes = last.bytes > 0 ? last.bytes : 0;
      base.width = last.width;
      base.height = last.height;
      base.longEdge = Math.max(last.width, last.height);
      base.rung = last.rung;
      base.capMet = last.capMet;
    } else {
      base.reason = 'encode-failed';
    }
    base.upscaled = base.width > decoded.width || base.height > decoded.height;
    base.scaleRatio = decoded.width > 0 ? Number((base.width / decoded.width).toFixed(6)) : 1;
    return base;
  } finally {
    decoded.release();
  }
}
