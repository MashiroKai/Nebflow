// smartCrop.js — 叠卡封面内容感知取景（作者令 2026-11-01 21:40 ①）
//
// 问题：叠卡收拢面（.pop-stack-view 固定卡脸）走 `object-fit: cover`，默认
// object-position 50% 50% = 几何中心 —— 截图类图片（内容集中上部、下半大面积
// 空白）裁出来一片空（作者 21:37 实拍 IMG_1673：叠卡几乎全空）。
//
// 方案（smartcrop 思路自实现，零依赖、零重依赖引入）：解码后在客户端对降采样
// 位图跑**显著区域滑窗打分**，从 `object-position` 的可平移范围内择优：
//   · 候选窗 = cover 语义下与卡脸同比的窗口（object-fit: cover 只在溢出维可
//     平移 —— 窗宽/窗高贴满另一维，本模块只在该维上滑窗，不缩放不越权）；
//   · 打分 = 边缘密度 + 亮度方差 + 熵（信息量三代理，积分图 O(1)/窗 + 直方图
//     熵），取最高分窗，窗中心换算成 `object-position: x% y%` 写 img 内联样式；
//   · 截图类偏置：底部大面积近均匀行（或纵比 ≥1.9 且上部边缘密度显著高于
//     下部）判为截图类 —— 给顶部偏置（越靠顶加分越多），普通图片零偏置不劣化；
//   · 缓存：结果按图片 src 记忆（同 URL 不重算，账本 blob / data URI 天然稳定）；
//   · 回落：跨域位图污染 / 解码失败 / 尺寸异常 / 任何异常 —— 不写内联样式，
//     原样回落几何中心（消费面零感知）。
//
// 消费点唯一：popArtifacts.js buildMediaStack 的叠卡收拢层（平铺单卡/展开行走
// contain 真实比，无裁剪语义，不在射程）。视频脸不进（逐帧解码无便宜路，回落
// 中心）；探测面：img.dataset.smartCrop = computed | cache-hit | failed。

const CACHE_MAX = 240;
/** @type {Map<string, string>} src → 'x% y%'（FIFO 淘汰，够叠卡会话一轮用）。 */
const cache = new Map();

/** 打分滑窗的降采样上限（工作位图最长边，px）。220×280 卡脸 × 9 档滑窗在此
 *  预算内全窗打分 < 5ms；再大只添噪声不加判别力。 */
const WORK_MAX = 240;
/** 滑窗步数（溢出维 0–100% 均分，含两端）。 */
const STEPS = 8;
/** 截图类判定：下半图近均匀行占比阈值 / 纵比阈值 / 上下边缘密度比阈值。 */
const FLAT_ROW_SPAN = 10;      // 行内亮度 max-min < 此值 ≈ 近均匀行
const FLAT_FRACTION = 0.55;    // 下半图近均匀行占比 ≥ 此值 ⇒ 截图类
const TALL_ASPECT = 1.9;       // h/w ≥ 此值算超纵（手机整屏截图形）
const EDGE_RATIO = 2.2;        // 上 1/3 边缘密度 ≥ 下 1/3 × 此值 ⇒ 内容压顶
/** 截图类顶部偏置强度：score × (1 + bias·(1 − y/100))，y=0（顶）拿满。 */
const TOP_BIAS = 0.35;

/**
 * 给 cover 裁剪的叠卡封面图择优 object-position。
 * @param {HTMLImageElement} img 目标图（.pop-img；load 已驱动的 natural 回调内
 *   调用最自然，未加载态自带 once-load 兜底）。
 * @param {number} boxAspect 卡脸宽高比（w/h；叠卡 = 卡脸实测或 220/280 兜底）。
 * @returns {void}
 */
export function applySmartCrop(img, boxAspect) {
  if (!img || img.dataset.smartCrop) return; // 幂等：同一图只裁一次
  const run = () => {
    img.dataset.smartCrop = 'computed';
    try {
      const key = img.currentSrc || img.src || '';
      if (key && cache.has(key)) {
        img.dataset.smartCrop = 'cache-hit';
        img.style.objectPosition = cache.get(key);
        return;
      }
      const pos = computeCrop(img, boxAspect);
      if (!pos) { img.dataset.smartCrop = 'failed'; return; } // 回落几何中心
      if (key) { cache.set(key, pos); if (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value); }
      img.style.objectPosition = pos;
    } catch {
      img.dataset.smartCrop = 'failed'; // 任何异常 = 回落几何中心
    }
  };
  if (img.complete && img.naturalWidth > 0) run();
  else img.addEventListener('load', run, { once: true });
}

/** 纯计算：返回 'x% y%' 或 null（不可裁/失败 → 回落中心）。 */
function computeCrop(img, boxAspect) {
  const natW = img.naturalWidth, natH = img.naturalHeight;
  if (!natW || !natH || !(boxAspect > 0)) return null;
  // 工作位图（等比降采样；跨域无 CORS 时 getImageData 抛错 → 上层回落）。
  const scale = Math.min(1, WORK_MAX / Math.max(natW, natH));
  const w = Math.max(1, Math.round(natW * scale));
  const h = Math.max(1, Math.round(natH * scale));
  const canvas = document.createElement('canvas');
  canvas.width = w; canvas.height = h;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  ctx.drawImage(img, 0, 0, w, h);
  const data = ctx.getImageData(0, 0, w, h).data;

  // 亮度网格 + 边缘网格（|右差| + |下差|；尾列/尾行记 0）。
  const lum = new Float32Array(w * h);
  for (let i = 0, p = 0; i < lum.length; i++, p += 4) {
    lum[i] = 0.299 * data[p] + 0.587 * data[p + 1] + 0.114 * data[p + 2];
  }
  const edge = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x;
      let e = 0;
      if (x + 1 < w) e += Math.abs(lum[i + 1] - lum[i]);
      if (y + 1 < h) e += Math.abs(lum[i + w] - lum[i]);
      edge[i] = e;
    }
  }
  // 积分图（亮度 / 亮度平方 / 边缘）⇒ 任意窗的 sum/var O(1)。
  const intL = integral(lum, w, h), intL2 = integral(sq(lum), w, h), intE = integral(edge, w, h);

  // cover 窗几何（工作坐标）：溢出维滑窗、贴满维固定。
  const aspect = natW / natH;
  let winW, winH, panX, panY; // panX/panY = 该维是否可平移
  if (aspect > boxAspect) { winH = h; winW = Math.max(1, Math.round(h * boxAspect)); panX = true; panY = false; }
  else { winW = w; winH = Math.max(1, Math.round(w / boxAspect)); panX = false; panY = true; }

  // 截图类判定（下半近均匀行占比 / 超纵且内容压顶）。
  const bottomFlat = flatRowFraction(lum, w, h, Math.floor(h / 2), h);
  const topEdge = regionEdgeMean(intE, w, 0, Math.floor(h / 3), w, Math.floor(h / 3));
  const bottomEdge = regionEdgeMean(intE, w, h - Math.floor(h / 3), h - Math.floor(h / 3), w, Math.floor(h / 3));
  const screenshotLike = bottomFlat >= FLAT_FRACTION ||
    (aspect <= 1 / TALL_ASPECT && topEdge >= bottomEdge * EDGE_RATIO);

  // 滑窗打分，取最高。
  let best = null;
  const steps = STEPS;
  for (let s = 0; s <= steps; s++) {
    const t = s / steps; // 0=起（顶/左）1=终（底/右）
    const off = Math.round((panX ? w - winW : h - winH) * t);
    const wx = panX ? off : 0, wy = panY ? off : 0;
    const a = winW * winH;
    const sL = rectSum(intL, w, wx, wy, winW, winH);
    const sL2 = rectSum(intL2, w, wx, wy, winW, winH);
    const sE = rectSum(intE, w, wx, wy, winW, winH);
    const mean = sL / a;
    const variance = Math.max(0, sL2 / a - mean * mean) / (255 * 255);
    const edgeDensity = Math.min(1, (sE / a) / 96); // |∇| 和 ≈0–192 归一
    const entropy = windowEntropy(lum, w, wx, wy, winW, winH);
    let score = 0.45 * edgeDensity + 0.35 * variance + 0.20 * entropy;
    if (screenshotLike) score *= 1 + TOP_BIAS * (1 - t); // 顶部偏置：越顶分越高
    if (!best || score > best.score) best = { score, x: panX ? t * 100 : 50, y: panY ? t * 100 : 50 };
  }
  if (!best) return null;
  return `${best.x.toFixed(1)}% ${best.y.toFixed(1)}%`;
}

/* ── 打分算术小件 ─────────────────────────────────────────────────────── */
function integral(src, w, h) {
  const out = new Float64Array((w + 1) * (h + 1));
  for (let y = 0; y < h; y++) {
    let rowSum = 0;
    for (let x = 0; x < w; x++) {
      rowSum += src[y * w + x];
      out[(y + 1) * (w + 1) + (x + 1)] = out[y * (w + 1) + (x + 1)] + rowSum;
    }
  }
  return out;
}
function rectSum(int, w, x, y, rw, rh) {
  const W = w + 1;
  return int[(y + rh) * W + (x + rw)] - int[y * W + (x + rw)] - int[(y + rh) * W + x] + int[y * W + x];
}
function sq(src) { const o = new Float32Array(src.length); for (let i = 0; i < src.length; i++) o[i] = src[i] * src[i]; return o; }
function regionEdgeMean(intE, w, x, y, rw, rh) { return rectSum(intE, w, x, y, rw, rh) / Math.max(1, rw * rh); }
/** 一行亮度 max-min（近均匀判定）。 */
function rowSpan(lum, w, y) {
  let mn = 255, mx = 0;
  for (let x = 0; x < w; x++) { const v = lum[y * w + x]; if (v < mn) mn = v; if (v > mx) mx = v; }
  return mx - mn;
}
function flatRowFraction(lum, w, h, y0, y1) {
  let flat = 0, total = 0;
  for (let y = y0; y < y1; y++) { total++; if (rowSpan(lum, w, y) < FLAT_ROW_SPAN) flat++; }
  return total ? flat / total : 0;
}
/** 窗内 32-bin 亮度直方图熵（0–1 归一）。 */
function windowEntropy(lum, w, wx, wy, rw, rh) {
  const bins = new Float64Array(32);
  for (let y = wy; y < wy + rh; y++) {
    const row = y * w;
    for (let x = wx; x < wx + rw; x++) bins[Math.min(31, (lum[row + x] / 8) | 0)]++;
  }
  const total = rw * rh;
  let e = 0;
  for (let i = 0; i < 32; i++) {
    if (!bins[i]) continue;
    const p = bins[i] / total;
    e -= p * Math.log2(p);
  }
  return e / 5; // log2(32)=5 满熵归一
}
