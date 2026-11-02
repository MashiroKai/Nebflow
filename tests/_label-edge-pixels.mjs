// _label-edge-pixels.mjs — 标签文字「两端是否被淡化」的像素判据（本批 2026-11-02
// 中签零虚化渐隐批的机械断言 ①② 用；T13① 与实拍脚本共用）。
//
// 判据 = **边缘斜坡带宽度**（edge ramp width，设备像素）：
//   对一帧 label 裁切图逐列求墨迹对比度（该列亮度极差 max−min，0…255）。
//   · 做了水平 alpha 渐隐（`mask-image: linear-gradient(... 9px ...)`）的一侧，
//     从最外侧有墨列往内是一段**连续单调爬升**的斜坡，长度 ≈ 渐隐带（9 CSS px
//     × scale × dsf；375 视口 dsf=2 时实测 14–18 设备像素）。
//   · 实色硬边的一侧，只有字形自身的 1–2 个抗锯齿像素，斜坡长 ≤ 3 设备像素。
//   斜坡带上限阈值取 5：实测有 mask 14–18、无 mask 1–3，两档不重叠 ⇒ 判据稳定，
//   且与具体字形（起笔粗细）无关 —— 这正是「端点 alpha 是否 = 1.0」的可判据代理。
import { decodePng } from '../scripts/lib/png-diff.mjs';

const LUMA = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b;

/** 逐列墨迹对比度。 */
export function columnContrast(pngBuf) {
  const { width, height, data } = decodePng(pngBuf);
  const cols = [];
  for (let x = 0; x < width; x++) {
    let lo = 255;
    let hi = 0;
    for (let y = 0; y < height; y++) {
      const i = (y * width + x) * 4;
      const l = LUMA(data[i], data[i + 1], data[i + 2]);
      if (l < lo) lo = l;
      if (l > hi) hi = l;
    }
    cols.push(hi - lo);
  }
  return { width, height, cols };
}

/**
 * 两端斜坡带读数。
 * @param {Buffer} pngBuf label 区域裁切帧（PNG，建议 deviceScaleFactor=2）
 * @param {{ ink?: number, flat?: number }} [opts]
 *   ink  = 「这一列有墨」的对比度阈值（0…255，默认 8）
 *   flat = 判「仍处斜坡」的对比度上限比例（相对正文列中位数，默认 0.85）
 * @returns {{ width: number, height: number, peak: number,
 *   inkFrom: number|null, inkTo: number|null, interiorMedian: number,
 *   rampLeft: number, rampRight: number, firstRatio: number|null, lastRatio: number|null }}
 */
export function labelEdgeRamp(pngBuf, opts = {}) {
  const ink = opts.ink ?? 8;
  const flat = opts.flat ?? 0.85;
  const { width, height, cols } = columnContrast(pngBuf);
  const peak = Math.max(...cols);
  let inkFrom = null;
  let inkTo = null;
  for (let x = 0; x < width; x++) if (cols[x] >= ink) { inkFrom = x; break; }
  for (let x = width - 1; x >= 0; x--) if (cols[x] >= ink) { inkTo = x; break; }
  if (inkFrom === null || inkTo === null || peak <= 0) {
    return { width, height, peak, inkFrom, inkTo, interiorMedian: 0, rampLeft: 0, rampRight: 0, firstRatio: null, lastRatio: null };
  }
  const span = inkTo - inkFrom + 1;
  const mid = cols.slice(inkFrom + Math.floor(span * 0.2), inkTo - Math.floor(span * 0.2) + 1);
  const sorted = [...mid].sort((a, b) => a - b);
  const interiorMedian = sorted.length ? sorted[Math.floor(sorted.length / 2)] : peak;
  const lim = interiorMedian * flat;
  let rampLeft = 0;
  for (let x = inkFrom; x <= inkTo && cols[x] < lim; x++) rampLeft++;
  let rampRight = 0;
  for (let x = inkTo; x >= inkFrom && cols[x] < lim; x--) rampRight++;
  return {
    width,
    height,
    peak,
    inkFrom,
    inkTo,
    interiorMedian,
    rampLeft,
    rampRight,
    firstRatio: interiorMedian ? +(cols[inkFrom] / interiorMedian).toFixed(3) : null,
    lastRatio: interiorMedian ? +(cols[inkTo] / interiorMedian).toFixed(3) : null,
  };
}
