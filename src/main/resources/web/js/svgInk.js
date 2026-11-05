// svgInk.js — SVG 墨色归一（rich-render 批 2026-11-05）。
//
// 为什么单点：两条腿都要它 ——
//   · js/svgFence.js（外来 ```svg）：墨色写在**属性**上（`fill="#0f172a"`），
//     挂载前就能算，归一 = 改属性；
//   · js/mermaidRenderer.js（mermaid 产物）：墨色写在 svg 内部的 `<style>` 里，
//     只有挂进文档、`getComputedStyle` 才有值，归一 = 写**行内 style**
//     （行内 style 才压得过那份内部样式表；改属性压不过）。
// 判据同一条：与「有效背景」（文档底色 + 祖先半透明背景逐层合成）的 WCAG 对比度
// 低于 [[MIN_CONTRAST]] 就换成当前主题的正文墨色 token（--color-text）。
//
// 实测动因（本批首轮实证）：mermaid dark 主题下 flowchart 的无形状节点文本
// 计算值仍是 rgb(0,0,0)，在暗底 rgb(19,22,28) 上对比度 1.16:1 —— 肉眼几乎不可读。
// 只靠 mermaid 的 themeVariables 覆盖不足以保证「暗色下可读」，所以加这条 belt，
// 并且它给出的读数是**可机械断言**的（断言取的就是 getComputedStyle 值）。

/** 色彩解析失败时的保底（不透明白）。 */
const WHITE = { r: 255, g: 255, b: 255, a: 1 };

/**
 * CSS 颜色字符串 → {r,g,b,a}（借浏览器自己解析，覆盖 #hex / rgb() / 命名色 /
 * color() 等一切形态）。解析不出来返回 null。
 * @param {string} value
 * @returns {{r:number,g:number,b:number,a:number}|null}
 */
export function parseColor(value) {
  if (!value) return null;
  const probe = document.createElement('span');
  probe.style.color = '';
  probe.style.color = value;
  if (!probe.style.color) return null;
  probe.style.display = 'none';
  document.body.appendChild(probe);
  const computed = getComputedStyle(probe).color;
  probe.remove();
  const m = /rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s/]+([\d.]+))?/.exec(computed);
  if (!m) return null;
  return { r: Number(m[1]), g: Number(m[2]), b: Number(m[3]), a: m[4] === undefined ? 1 : Number(m[4]) };
}

/** WCAG 相对亮度。 @param {{r:number,g:number,b:number}} c @returns {number} */
export function luminance(c) {
  const ch = (v) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b);
}

/** WCAG 对比度（1–21）。 @param {{r:number,g:number,b:number}} a @param {{r:number,g:number,b:number}} b @returns {number} */
export function contrastRatio(a, b) {
  const hi = Math.max(luminance(a), luminance(b));
  const lo = Math.min(luminance(a), luminance(b));
  return (hi + 0.05) / (lo + 0.05);
}

export const MIN_CONTRAST = 3;

/** 读主题 token 的计算色值（不写死颜色）。 @param {string} token @returns {string} */
export function tokenColor(token) {
  const probe = document.createElement('span');
  probe.style.display = 'none';
  probe.style.color = 'var(' + token + ')';
  document.body.appendChild(probe);
  const v = getComputedStyle(probe).color;
  probe.remove();
  return v || '';
}

/** 当前主题的正文墨色。 @returns {string} */
export function themeInk() {
  const v = tokenColor('--color-text');
  return v || 'rgb(0, 0, 0)';
}

/**
 * 就近**实绘形状**的底色：mermaid 的节点/边标签结构是
 * `<g class="node"><rect class="basic label-container"/><g class="label"><text>` /
 * `<g class="edgeLabel"><rect class="background"/>…` —— 文字其实坐在那块形状上，
 * 不是坐在页面底上。暗档实测：白底节点 + 黑字（局部对比 19:1，完全可读），
 * 若拿页面深底做对照就会误判成 1.16:1（本批首轮实证的假红）。
 * @param {Element} el
 * @returns {{r:number,g:number,b:number,a:number}|null} 找不到返回 null
 */
export function paintedShapeBackground(el) {
  let n = el.parentElement;
  let hops = 0;
  while (n && hops < 6) {
    const shape = n.querySelector(':scope > rect, :scope > circle, :scope > ellipse, :scope > polygon, :scope > path');
    if (shape) {
      const c = parseColor(getComputedStyle(shape).fill);
      if (c && c.a > 0.05) return c;
    }
    n = n.parentElement;
    hops++;
  }
  return null;
}

/**
 * 有效背景：优先取「就近实绘形状」的底色（文字坐在形状上），否则退回
 * 「文档底色 + 祖先半透明背景合成」。
 * @param {Element|null} el
 * @returns {{r:number,g:number,b:number,a:number}}
 */
export function effectiveBackground(el) {
  const local = el ? paintedShapeBackground(el) : null;
  if (local) return local;
  /** @type {{r:number,g:number,b:number,a:number}} */
  let base = WHITE;
  for (const cand of [document.body, document.documentElement]) {
    const c = cand ? parseColor(getComputedStyle(cand).backgroundColor) : null;
    if (c && c.a >= 0.99) { base = c; break; }
  }
  const stack = [];
  let n = el ? el.parentElement : null;
  while (n && stack.length < 14) {
    const bg = parseColor(getComputedStyle(n).backgroundColor);
    if (bg && bg.a > 0) stack.push(bg);
    n = n.parentElement;
  }
  let acc = { ...base };
  for (let i = stack.length - 1; i >= 0; i--) {
    const top = stack[i];
    acc = {
      r: top.r * top.a + acc.r * (1 - top.a),
      g: top.g * top.a + acc.g * (1 - top.a),
      b: top.b * top.a + acc.b * (1 - top.a),
      a: 1,
    };
  }
  return acc;
}

/**
 * 已挂载 SVG 的墨色归一（mermaid 腿）：按**计算值**判，改行内 style。
 * @param {Element} root 已经 append 到文档里的 svg
 * @returns {number} 被改写的节点数（读数列 / 断言锚点）
 */
export function normalizeComputedInk(root) {
  const texts = Array.from(root.querySelectorAll('text, tspan'));
  if (!texts.length) return 0;
  const bg = effectiveBackground(texts[0]);
  const ink = themeInk();
  let fixed = 0;
  for (const el of texts) {
    const fill = parseColor(getComputedStyle(el).fill);
    if (!fill || fill.a === 0) continue;
    if (contrastRatio(fill, bg) < MIN_CONTRAST) {
      // 行内 style 才压得过 svg 内部的 <style>（表现属性压不过）。
      /** @type {HTMLElement} */ (el).style.fill = ink;
      fixed++;
    }
  }
  return fixed;
}

/**
 * 未挂载（游离）外来 SVG 的墨色归一（```svg 腿）：按**属性值**判，改属性；
 * 缺 fill 的可见元素补主题墨色（XML 默认 fill 恒黑，暗底上等于隐形）。
 * @param {Element} root 尚未挂载的 svg 根
 * @returns {number} 被改写的属性数
 */
export function normalizeAttrInk(root) {
  const bg = effectiveBackground(document.body);
  const ink = themeInk();
  const els = [root, ...Array.from(root.querySelectorAll('*'))];
  let fixed = 0;
  for (const el of els) {
    const isRoot = el === root;
    for (const attr of ['fill', 'stroke']) {
      const raw = el.getAttribute(attr);
      if (raw === null) {
        if (attr === 'fill' && !isRoot && el.tagName !== 'defs') { el.setAttribute('fill', ink); fixed++; }
        continue;
      }
      const value = raw.trim();
      if (!value || value === 'none' || value === 'currentColor' || value.startsWith('url(')) continue;
      const rgb = parseColor(value);
      if (!rgb) continue;
      if (contrastRatio(rgb, bg) < MIN_CONTRAST) { el.setAttribute(attr, ink); fixed++; }
    }
  }
  root.setAttribute('color', ink);
  return fixed;
}
