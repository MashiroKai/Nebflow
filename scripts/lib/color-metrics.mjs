// color-metrics.mjs — CSS 颜色度量的**单一装置**（WCAG 对比度 + CIEDE2000）。
//
// 为什么要独立一层：本批（dark-border-weight）要回答的是「暗档的边框比亮档扎眼
// 多少」，判据有两个且**都要给数**——① WCAG 对比度（无障碍口径：边框对相邻底的
// 比值），② ΔE00（感知口径：两枚颜色在人眼里的距离，ω 与对比度不等价，一条 1px
// 白线对深底的 WCAG 比值可以很高而 ΔE00 也可以很大，但两者对「弱化」的敏感度
// 不同）。两个口径写两份数学是必然跑偏的那种债，故收在这里一份。
//
// 输入一律是**浏览器 computed 形态**的字符串（`rgb(r, g, b)` / `rgba(r, g, b, a)`
// / `#rrggbb` / `#rrggbbaa` / `color(srgb …)` 之外的 modern 形态不在此列），
// 因为消费点（audit 脚本 / 闸）拿到的就是 getComputedStyle 的原文。
//
// 半透明色一律**先合成再比较**：边框色常是 α<1 的白/黑细边，直接拿 α 色报 WCAG
// 是错的（人眼看到的是合成后的实色）。composite() 是唯一合成点。

/** @typedef {[number, number, number, number]} RGBA — 0..255 三通道 + 0..1 α */

const NAMED = {
  transparent: [0, 0, 0, 0], black: [0, 0, 0, 1], white: [255, 255, 255, 1],
};

/**
 * 解析 CSS 颜色串 → [r,g,b,a]（r/g/b 0..255，a 0..1）。无法解析返回 null（不猜）。
 * @param {string} str
 * @returns {RGBA|null}
 */
export function parseColor(str) {
  if (typeof str !== 'string') return null;
  const s = str.trim().toLowerCase();
  if (!s || s === 'none') return null;
  if (Object.prototype.hasOwnProperty.call(NAMED, s)) return NAMED[s].slice();
  let m = /^#([0-9a-f]{3,8})$/.exec(s);
  if (m) {
    const h = m[1];
    if (h.length === 3 || h.length === 4) {
      const v = [...h].map((c) => parseInt(c + c, 16));
      return [v[0], v[1], v[2], h.length === 4 ? v[3] / 255 : 1];
    }
    if (h.length === 6 || h.length === 8) {
      const v = [];
      for (let i = 0; i < h.length; i += 2) v.push(parseInt(h.slice(i, i + 2), 16));
      return [v[0], v[1], v[2], h.length === 8 ? v[3] / 255 : 1];
    }
    return null;
  }
  m = /^rgba?\(([^)]+)\)$/.exec(s);
  if (m) {
    // 空格分隔（现代）与逗号分隔（经典）都接受；α 给百分数也接受。
    const parts = m[1].split(/[\s,/]+/).filter(Boolean);
    if (parts.length < 3) return null;
    const num = (p) => (p.endsWith('%') ? (parseFloat(p) / 100) * 255 : parseFloat(p));
    const r = num(parts[0]); const g = num(parts[1]); const b = num(parts[2]);
    let a = 1;
    if (parts.length >= 4) a = parts[3].endsWith('%') ? parseFloat(parts[3]) / 100 : parseFloat(parts[3]);
    if ([r, g, b, a].some((x) => !Number.isFinite(x))) return null;
    return [r, g, b, a];
  }
  return null;
}

/**
 * 把 fg 合成到 bg 上（α 混合）。fg 与 bg 都是 RGBA；返回**不透明** RGB 三元组。
 * bg 若自身带 α，按叠在**不透明白/黑**上近似不处理 —— 消费点只传实底。
 * @param {RGBA} fg @param {RGBA} bg @returns {[number,number,number]}
 */
export function composite(fg, bg) {
  const a = fg[3];
  return [0, 1, 2].map((i) => Math.round(fg[i] * a + bg[i] * (1 - a)));
}

/** sRGB 通道 0..255 → 线性光 0..1（WCAG 定义）。 */
const lin = (c) => { const v = c / 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };

/** 相对亮度（WCAG 2.x）。 @param {[number,number,number]} rgb */
export function relLum(rgb) { return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2]); }

/**
 * WCAG 对比度（1..21）。两色都视为**已合成实色**。 @param {[number,number,number]} a @param {[number,number,number]} b
 * @returns {number} 保留 3 位
 */
export function wcag(a, b) {
  const la = relLum(a); const lb = relLum(b);
  const hi = Math.max(la, lb); const lo = Math.min(la, lb);
  return +(((hi + 0.05) / (lo + 0.05)).toFixed(3));
}

// ── CIEDE2000（ΔE00）──────────────────────────────────────────────────────
/** sRGB → XYZ(D65) → Lab。 @param {[number,number,number]} rgb @returns {[number,number,number]} */
export function rgb2lab(rgb) {
  const r = lin(rgb[0]); const g = lin(rgb[1]); const b = lin(rgb[2]);
  // sRGB → XYZ，D65
  const x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047;
  const y = (r * 0.2126729 + g * 0.7151522 + b * 0.0721750);
  const z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) / 1.08883;
  const f = (t) => (t > 216 / 24389 ? Math.cbrt(t) : (841 / 108) * t + 4 / 29);
  const fx = f(x); const fy = f(y); const fz = f(z);
  return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
}

/**
 * CIEDE2000 色差。实现按 Sharma/Wu/Dalal 的标准式（含 kL=kC=kH=1）。
 * @param {[number,number,number]} rgb1 @param {[number,number,number]} rgb2 @returns {number} 保留 3 位
 */
export function deltaE00(rgb1, rgb2) {
  const [L1, a1, b1] = rgb2lab(rgb1);
  const [L2, a2, b2] = rgb2lab(rgb2);
  const C1 = Math.hypot(a1, b1); const C2 = Math.hypot(a2, b2);
  const Cbar = (C1 + C2) / 2;
  const G = 0.5 * (1 - Math.sqrt(Cbar ** 7 / (Cbar ** 7 + 25 ** 7)));
  const ap1 = (1 + G) * a1; const ap2 = (1 + G) * a2;
  const Cp1 = Math.hypot(ap1, b1); const Cp2 = Math.hypot(ap2, b2);
  const hp = (b, ap) => { if (b === 0 && ap === 0) return 0; const h = Math.atan2(b, ap) * 180 / Math.PI; return h < 0 ? h + 360 : h; };
  const hp1 = hp(b1, ap1); const hp2 = hp(b2, ap2);
  const dLp = L2 - L1; const dCp = Cp2 - Cp1;
  let dhp = 0;
  if (Cp1 * Cp2 !== 0) {
    dhp = hp2 - hp1;
    if (dhp > 180) dhp -= 360; else if (dhp < -180) dhp += 360;
  }
  const dHp = 2 * Math.sqrt(Cp1 * Cp2) * Math.sin((dhp / 2) * Math.PI / 180);
  const Lbp = (L1 + L2) / 2; const Cbp = (Cp1 + Cp2) / 2;
  let hbp;
  if (Cp1 * Cp2 === 0) hbp = hp1 + hp2;
  else if (Math.abs(hp1 - hp2) <= 180) hbp = (hp1 + hp2) / 2;
  else if (hp1 + hp2 < 360) hbp = (hp1 + hp2 + 360) / 2;
  else hbp = (hp1 + hp2 - 360) / 2;
  const rad = (d) => d * Math.PI / 180;
  const T = 1 - 0.17 * Math.cos(rad(hbp - 30)) + 0.24 * Math.cos(rad(2 * hbp))
    + 0.32 * Math.cos(rad(3 * hbp + 6)) - 0.20 * Math.cos(rad(4 * hbp - 63));
  const dTheta = 30 * Math.exp(-(((hbp - 275) / 25) ** 2));
  const RC = 2 * Math.sqrt(Cbp ** 7 / (Cbp ** 7 + 25 ** 7));
  const SL = 1 + (0.015 * (Lbp - 50) ** 2) / Math.sqrt(20 + (Lbp - 50) ** 2);
  const SC = 1 + 0.045 * Cbp;
  const SH = 1 + 0.015 * Cbp * T;
  const RT = -Math.sin(rad(2 * dTheta)) * RC;
  const t = (dLp / SL) ** 2 + (dCp / SC) ** 2 + (dHp / SH) ** 2 + RT * (dCp / SC) * (dHp / SH);
  return +Math.sqrt(t).toFixed(3);
}

/**
 * 一次性算出「半透明边框色 ÷ 相邻实底」的两条读数。
 * @param {string} borderCss computed border-color
 * @param {string} bgCss computed 相邻背景色（不透明）
 * @returns {{contrast:number, deltaE:number, rgb:[number,number,number], bgRgb:[number,number,number]}|null}
 */
export function borderMetrics(borderCss, bgCss) {
  const fg = parseColor(borderCss); const bg = parseColor(bgCss);
  if (!fg || !bg) return null;
  const solidBg = bg[3] >= 1 ? [bg[0], bg[1], bg[2]] : composite(bg, [255, 255, 255]);
  const solidFg = fg[3] >= 1 ? [fg[0], fg[1], fg[2]] : composite(fg, solidBg);
  return {
    contrast: wcag(solidFg, solidBg),
    deltaE: deltaE00(solidFg, solidBg),
    rgb: solidFg,
    bgRgb: solidBg,
  };
}

/** hex 打印（证据表用）。 @param {[number,number,number]} rgb */
export function hex(rgb) { return `#${rgb.map((v) => Math.round(v).toString(16).padStart(2, '0')).join('')}`; }
