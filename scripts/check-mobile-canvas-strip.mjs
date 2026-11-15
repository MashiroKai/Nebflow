#!/usr/bin/env node
// check-mobile-canvas-strip.mjs — 手机 canvas 顶部环带（方案 C）四条静态判据
// （mobile-canvas-tabs-c 批 2026-11-06；作者 08:33 逐字：
//  「采用方案c 主胶囊要采用毛玻璃底 然后背景的文字不使用胶囊 但是还有电脑端
//    hover 上下选项的那种模糊效果 文字两边不要渐隐」）
//
// Why a gate：这一面（`.mf-strip` / `.mf-strip-chip` / `.mf-strip-label`）在半年里
// 长过 5 套互斥的视觉规则 —— 双侧 52→64 羽化 → 逐签方向 mask → mask 迁 ::after →
// 扁平签 → 方案 C。每一套都是「当时对的」，但没有一套挡得住下一个人把上一套
// 加回来：作者这次的措辞里有**两条是禁令**（文字两边不要渐隐 / 背景的文字不使用
// 胶囊），禁令必须变成机器判据，否则下一次重构它就会静默复活。
//
// ── 四条判据 ───────────────────────────────────────────────────────────────
//  S1 玻璃单源（半透明但没走玻璃 = 红）
//       · 环带脸上任何元素键（`.mf-strip*` 的宿主与伪元素）解析出的底色 α ∈
//         (0, .90) —— 即「这一面会透出底下的东西」—— 而该键**没有有效模糊**
//         （css/glass.css 成员表供给的 backdrop-filter，或 .nf-glass）⇒ 红。
//       · 选中主胶囊 `.mf-strip-chip.on::after` 必须在场且走玻璃。
//         整宽伪元素已退役，不再登记为玻璃成员。
//  S2 禁止水平渐隐 mask（出现即红）
//       · 环带脸上任何 `mask-image`（含 -webkit-）值为**水平**渐变
//         （90deg / 270deg / to left / to right）⇒ 红；任何 non-none 的 mask
//         只要落在 label 上 ⇒ 红（文字两侧连纵向淡出也不许有）。
//       · JS 侧：`navController.js` 给环带写 `mask-image` 或 `--rfade` ⇒ 红。
//       · 保留水平方向零渐隐禁令；整宽纵向渐隐也由 S4 退役。
//  S3 背景的文字不使用胶囊（出现即红）
//       · 3a 签视觉层 `.mf-strip-chip::after` 的**基础档**必须是零材质
//         （底 none / 零描边 / 零投影）—— 胶囊材质只许出现在 `.on` 档
//         （`.mf-strip-chip.on::after`）。两侧签就是「背景那一层的文字」。
//       · 3b 正文宿主（滚过带下的那几层：`.canvas-md-scroll` / `.mf-cv-host` /
//         `.mf-cv-host > .canvas-tab-pane`）不得有底（α > 0）也不得是 pill 圆角
//         —— 正文/标题不加胶囊包层、不套任何 pill/气泡/背景块。
//  S4 透明悬浮：整宽 ::before / ::after 均 content:none。
//       新授权取代旧整宽玻璃带及下缘渐隐；材质仅属于独立按钮和选中胶囊。
//
// 判据的**规范侧**（为什么这么定、方案 C 原稿、偏离说明）在
//   <dataRoot>/projects/stand/.stand/docs/mobile-canvas-tabs-c__<stamp>.md
// 人读口径（玻璃标准本体）在 seed/plugins/design-language/.../glass-spec.md §7。
//
// 用法：
//   node scripts/check-mobile-canvas-strip.mjs              # 门禁（红/绿）
//   node scripts/check-mobile-canvas-strip.mjs --json       # 机器读
//   node scripts/check-mobile-canvas-strip.mjs --root=<dir>  # 跑在另一棵检出上（改前实证用）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT_OVERRIDE = (process.argv.slice(2).find((a) => a.startsWith('--root=')) || '').slice(7);
const ROOT = ROOT_OVERRIDE
  ? fileURLToPath(new URL(`file://${ROOT_OVERRIDE}/`))
  : fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const CSS_DIR = join(WEB, 'css');
const INDEX_HTML = join(WEB, 'index.html');
const GLASS = join(CSS_DIR, 'glass.css');
const MOBILE_LAYER = join(CSS_DIR, 'mobileLayer.css');
const NAV = join(WEB, 'js', 'mobile', 'navController.js');

/** 正文宿主（滚过带下的那几层）：不得有底、不得是 pill。 */
const CONTENT_FACES = new Set(['.canvas-md-scroll', '.mf-cv-host', '.canvas-tab-pane']);

const rel = (p) => relative(ROOT, p);

// ── 极简 CSS 遍历（与 check-glass-unify.mjs 同族；去注释保行号）────────────
function stripCssComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}
function* walkRules(src) {
  const text = stripCssComments(src);
  const lineOf = (idx) => text.slice(0, idx).split('\n').length;
  let i = 0;
  const stack = [];
  let selStart = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '{') {
      const prelude = text.slice(selStart, i).trim();
      if (prelude.startsWith('@')) { stack.push(prelude.replace(/\s+/g, ' ')); i++; selStart = i; continue; }
      const bodyStart = i + 1;
      let j = bodyStart;
      let inner = 0;
      while (j < text.length) {
        if (text[j] === '{') inner++;
        else if (text[j] === '}') { if (inner === 0) break; inner--; }
        j++;
      }
      const body = text.slice(bodyStart, j);
      const decls = [];
      let cur = '';
      let declStart = bodyStart;
      for (let k = 0; k <= body.length; k++) {
        const c = body[k];
        if (c === ';' || k === body.length) {
          const raw = cur;
          const colon = raw.indexOf(':');
          if (colon > 0) {
            decls.push({
              prop: raw.slice(0, colon).trim().toLowerCase(),
              value: raw.slice(colon + 1).trim().replace(/\s+/g, ' '),
              line: lineOf(declStart),
            });
          }
          cur = '';
          declStart = bodyStart + k + 1;
        } else {
          if (c === '\n') declStart = bodyStart + k + 1;
          cur += c;
        }
      }
      yield { selector: prelude.replace(/\s+/g, ' '), decls, line: lineOf(selStart), atStack: [...stack] };
      i = j + 1;
      selStart = i;
      continue;
    }
    if (ch === '}') { if (stack.length) stack.pop(); i++; selStart = i; continue; }
    i++;
  }
}

/** 选择器切分 / 元素键（宿主 + 伪元素；与 check-glass-unify 判据同口径）。 */
function splitTop(s, seps) {
  const out = []; let depth = 0; let cur = '';
  for (const ch of s) {
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (depth === 0 && seps.test(ch)) { out.push(cur); cur = ''; continue; }
    cur += ch;
  }
  out.push(cur);
  return out.map((x) => x.trim()).filter(Boolean);
}
const subjectOf = (cx) => { const p = splitTop(cx, /[\s>+~]/); return p[p.length - 1] || ''; };
const pseudoOf = (c) => { const m = c.match(/::([a-z-]+)(\([^()]*\))?/i); return m ? `::${m[1]}` : ''; };
const baseOf = (c) => c
  .replace(/:not\([^()]*\)/g, '')
  .replace(/::[a-z-]+(\([^()]*\))?/gi, '')
  .replace(/:[a-z-]+(\([^()]*\))?/gi, '')
  .trim();
const keyOf = (cx) => { const s = subjectOf(cx); return baseOf(s) + pseudoOf(s); };
/** 环带脸 = 基名以 `.mf-strip` 起头的任何元素键（含 `.mf-strip.fade` /
 *  `.mf-strip-chip.side-l` 这类带状态类的变体、以及它们的伪元素）—— 禁令面必须
 *  覆盖状态变体，否则「换个类名再挂一次 mask」就绕过去了。 */
const isFaceKey = (key) => /^\.mf-strip(?:-chip|-label)?(?:[.:#\[]|$)/.test(baseOf(key));

/** CSS 加载序（index.html 的 <link> 次序；缺的补尾）。 */
function cssLoadOrder() {
  const order = [];
  if (existsSync(INDEX_HTML)) {
    const html = readFileSync(INDEX_HTML, 'utf8');
    const re = /<link[^>]*>/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
      const tag = m[0];
      if (!/rel\s*=\s*["']stylesheet["']/i.test(tag)) continue;
      const href = (tag.match(/href\s*=\s*["']([^"']+)["']/i) || [])[1];
      if (!href || !href.startsWith('css/')) continue;
      const f = href.slice(4).split(/[?#]/)[0];
      if (existsSync(join(CSS_DIR, f)) && !order.includes(f)) order.push(f);
    }
  }
  for (const f of readdirSync(CSS_DIR).filter((x) => x.endsWith('.css')).sort()) {
    if (!order.includes(f)) order.push(f);
  }
  return order;
}

const violations = [];
const add = (v) => violations.push(v);
const relPath = (p) => rel(p);

if (!existsSync(MOBILE_LAYER)) {
  console.error(`环境错误：找不到 ${rel(MOBILE_LAYER)}`);
  process.exit(2);
}

const order = cssLoadOrder();
const rulesOf = new Map();
for (const f of order) {
  try { rulesOf.set(f, [...walkRules(readFileSync(join(CSS_DIR, f), 'utf8'))]); }
  catch { rulesOf.set(f, []); }
}

// ── 有效声明表（后声明者胜；降级档 @supports not … 不算「加材质」）───────────
const isDegrade = (r) => r.atStack.some((a) => /^@supports\s+not\b/i.test(a));
const faceDecls = new Map();   // key -> { bg, bgFile, bgLine, bgs:[], blur, blurFile, blurLine, mask:[], radius, border, shadow }
let seq = 0;
for (const f of order) {
  for (const r of rulesOf.get(f) || []) {
    seq++;
    if (isDegrade(r)) continue;
    for (const cx of splitTop(r.selector, /,/)) {
      const key = keyOf(cx);
      if (!key || !isFaceKey(key)) continue;
      const cur = faceDecls.get(key) || {
        key, bg: null, bgFile: null, bgLine: 0, blur: false, blurFile: null, blurLine: 0,
        masks: [], radius: null, borders: [], shadows: [],
      };
      for (const d of r.decls) {
        if (d.prop === 'background' || d.prop === 'background-color') {
          cur.bg = d.value; cur.bgFile = f; cur.bgLine = r.line;
        } else if (/^(-webkit-)?backdrop-filter$/.test(d.prop)) {
          const v = d.value.replace(/\s*!important\s*$/i, '').trim();
          cur.blur = !/^none$/i.test(v);
          if (cur.blur) { cur.blurFile = f; cur.blurLine = r.line; }
        } else if (/^(-webkit-)?mask-image$/.test(d.prop) || d.prop === 'mask' || d.prop === '-webkit-mask') {
          const v = d.value.replace(/\s*!important\s*$/i, '').trim();
          if (!/^none$/i.test(v)) cur.masks.push({ prop: d.prop, value: v, file: f, line: r.line, selector: r.selector });
        } else if (d.prop === 'border-radius') {
          cur.radius = d.value;
        } else if (/^border(-(top|bottom|left|right))?$/.test(d.prop)) {
          cur.borders.push({ value: d.value, file: f, line: r.line, selector: r.selector });
        } else if (d.prop === 'box-shadow') {
          cur.shadows.push({ value: d.value, file: f, line: r.line, selector: r.selector });
        }
      }
      faceDecls.set(key, cur);
    }
  }
}

// ── 令牌解析（--x: 值；按加载序覆盖；calc 只解一层 var()，够本面用）─────────
const tokenDefs = new Map();
for (const f of order) {
  for (const r of rulesOf.get(f) || []) {
    for (const d of r.decls) if (d.prop.startsWith('--')) tokenDefs.set(d.prop, d.value);
  }
}
function deref(v, depth = 0) {
  if (depth > 10) return v;
  const s = String(v).trim();
  const m = s.match(/^var\(\s*(--[a-z0-9-]+)\s*(?:,([\s\S]*))?\)$/i);
  if (m) {
    const hit = tokenDefs.get(m[1]);
    if (hit !== undefined) return deref(hit, depth + 1);
    if (m[2]) return deref(m[2].replace(/^,/, ''), depth + 1);
  }
  return s;
}
/** 颜色 → α（#rrggbbaa / rgba() / rgb(a b / a) / 关键字）。无法判定 → null。 */
function alphaOf(value) {
  const v = deref(value);
  let m = v.match(/^#([0-9a-f]{8})$/i);
  if (m) return parseInt(m[1].slice(6, 8), 16) / 255;
  if (/^#([0-9a-f]{6}|[0-9a-f]{3})$/i.test(v)) return 1;
  if (/^rgba?\(/i.test(v)) {
    const inner = v.slice(v.indexOf('(') + 1, v.lastIndexOf(')'));
    if (inner.includes('/')) {
      const tail = inner.slice(inner.lastIndexOf('/') + 1).trim();
      const a = tail.endsWith('%') ? parseFloat(tail) / 100 : parseFloat(tail);
      return Number.isFinite(a) ? a : null;
    }
    const parts = inner.split(',').map((x) => x.trim()).filter(Boolean);
    if (parts.length >= 4) {
      const a = parts[3].endsWith('%') ? parseFloat(parts[3]) / 100 : parseFloat(parts[3]);
      return Number.isFinite(a) ? a : null;
    }
    return 1;
  }
  if (/^(transparent|none|initial|unset)$/i.test(v)) return 0;
  return null;      // 渐变 / 复合背景：α 不可判（S1 不看，S4 单独钉令牌）
}

/** 复合背景里取「最不透」的那一层 α（`background: 渐变 …, var(--glass-bg)` ⇒ .549）：
 *  逐层切顶层逗号；渐变层跳过（它自带色，但玻璃底通常写在最后一层）。 */
function alphaOfComposite(value) {
  const v = String(value).trim();
  const layers = [];
  let depth = 0; let cur = '';
  for (const ch of v) {
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (ch === ',' && depth === 0) { layers.push(cur); cur = ''; continue; }
    cur += ch;
  }
  layers.push(cur);
  let best = null;
  for (const raw of layers) {
    const layer = raw.trim().split(/\s+/).slice(0, 6).join(' ');
    const direct = alphaOf(raw.trim());
    if (direct !== null && (best === null || direct > best)) best = direct;
    else if (direct === null) {
      // 渐变 / 位置写法：把「/」前的第一段当 color 试一次
      const head = layer.split('/')[0].trim();
      const m2 = head.match(/(?:^|\s)((?:var\([^()]*\)|#[0-9a-f]{3,8}|rgba?\([^()]*\)|transparent|none))$/i);
      if (m2) {
        const a2 = alphaOf(m2[1]);
        if (a2 !== null && (best === null || a2 > best)) best = a2;
      }
    }
  }
  return best;
}

// ── S1 玻璃单源 ───────────────────────────────────────────────────────────
const glassMemberKeys = new Set();
for (const r of rulesOf.get('glass.css') || []) {
  const hasBlur = r.decls.some((d) => /^(-webkit-)?backdrop-filter$/.test(d.prop) && !/^none$/i.test(d.value.trim()));
  if (!hasBlur) continue;
  for (const cx of splitTop(r.selector, /,/)) glassMemberKeys.add(keyOf(cx));
}
/** 有效模糊 = 成员表供给的 backdrop-filter（后声明者胜已在 faceDecls 里算过）。 */
const isGlassed = (e) => !!e && e.blur && glassMemberKeys.has(e.key);

for (const [key, e] of faceDecls) {
  const a = e.bg ? (alphaOf(e.bg) ?? alphaOfComposite(e.bg)) : null;
  if (a === null || a <= 0 || a >= 0.90) continue;      // 零画 / 不透明 / 不可判：不是可透出面
  if (isGlassed(e)) continue;
  add({
    rule: 'S1', file: relPath(join(CSS_DIR, e.bgFile || 'mobileLayer.css')), line: e.bgLine || 0,
    selector: key, detail: `α=${a.toFixed(3)} 无模糊（${e.bg}）`,
    why: '环带可透出面必须走唯一毛玻璃标准（成员表 / .nf-glass）：半透明 + 零模糊 = 底下的正文直接穿上来',
  });
}
// 选中主胶囊必须在场且走玻璃
for (const must of ['.mf-strip-chip.on::after']) {
  const e = faceDecls.get(must);
  if (!e) {
    add({
      rule: 'S1', file: rel(MOBILE_LAYER), line: 0, selector: must,
      detail: '方案 C 的登记面缺席',
      why: '主胶囊玻璃层缺失：作者令「主胶囊要采用毛玻璃底」没有载体',
    });
    continue;
  }
  if (!isGlassed(e)) {
    add({
      rule: 'S1', file: rel(GLASS), line: 0, selector: must,
      detail: '未进 css/glass.css 成员表（或成员表里没有模糊声明）',
      why: '登记面必须吃唯一毛玻璃配方（G1/G2）；自写 backdrop-filter 是第二套',
    });
  }
}

// ── S2 水平渐隐 mask 禁令 ─────────────────────────────────────────────────
const HORIZ = /(?:^|[\s,(])-?(?:90|270)deg|to\s+(?:left|right)\b/i;
for (const [key, e] of faceDecls) {
  for (const m of e.masks) {
    const horiz = HORIZ.test(m.value);
    const onLabel = /label/.test(key);
    if (horiz || onLabel) {
      add({
        rule: 'S2', file: relPath(join(CSS_DIR, m.file)), line: m.line, selector: key,
        detail: `${m.prop}: ${m.value.slice(0, 90)}`,
        why: horiz
          ? '水平渐隐禁令：作者令「文字两边不要渐隐」—— 水平方向零渐隐'
          : '文字层 mask 禁令：label 上任何非 none 的 mask 都会把文字端点化开',
      });
    }
  }
}
// JS 侧：环带不许写 mask / --rfade
if (existsSync(NAV)) {
  const navRaw = readFileSync(NAV, 'utf8');
  const nav = navRaw
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length));
  nav.split('\n').forEach((line, i) => {
    if (/(?:-webkit-)?maskImage\s*[:=]|setProperty\(\s*['"]--rfade/.test(line)) {
      add({
        rule: 'S2', file: rel(NAV), line: i + 1, selector: '(JS 内联样式)',
        detail: line.trim().slice(0, 90),
        why: '环带水平渐隐禁令：JS 侧同禁令（mask / --rfade 一律不许写）',
      });
    }
  });
}

// ── S3 背景的文字不使用胶囊 ────────────────────────────────────────────────
{
  const e = faceDecls.get('.mf-strip-chip::after');
  if (e) {
    const a = e.bg ? alphaOf(e.bg) : 0;
    const hasBorder = e.borders.some((b) => !/^(0|none)\b/i.test(b.value) && !/^transparent\b/i.test(b.value.split(/\s+/).pop() || ''));
    const hasShadow = e.shadows.some((s) => !/^none$/i.test(s.value));
    const opaqueBg = a === null ? !/^none$/i.test(String(e.bg)) : a > 0;
    if (opaqueBg || hasBorder || hasShadow) {
      add({
        rule: 'S3a', file: relPath(join(CSS_DIR, e.bgFile || 'mobileLayer.css')), line: e.bgLine || 0,
        selector: '.mf-strip-chip::after',
        detail: `底=${e.bg || 'none'} · 描边=${hasBorder} · 投影=${hasShadow}`,
        why: '两侧签 = 背景那一层的文字，必须零材质（胶囊只许出现在 .on 档）',
      });
    }
  }
  // 3b 正文宿主：无底 + 非 pill
  for (const [key, e] of faceDecls) void key;
  for (const f of order) {
    for (const r of rulesOf.get(f) || []) {
      for (const cx of splitTop(r.selector, /,/)) {
        const b = baseOf(subjectOf(cx));
        if (!CONTENT_FACES.has(b)) continue;
        const bg = r.decls.find((d) => d.prop === 'background' || d.prop === 'background-color');
        if (bg) {
          const a = alphaOf(bg.value);
          if (a !== 0 && !(a === null && /^none$/i.test(bg.value))) {
            add({
              rule: 'S3b', file: relPath(join(CSS_DIR, f)), line: r.line, selector: keyOf(cx),
              detail: `background: ${bg.value.slice(0, 80)}`,
              why: '正文宿主不得有底：滚过带下的正文/标题不加胶囊包层、不套任何 pill/气泡/背景块',
            });
          }
        }
        const rad = r.decls.find((d) => d.prop === 'border-radius');
        if (rad && /(999|pill)/i.test(rad.value)) {
          add({
            rule: 'S3b', file: relPath(join(CSS_DIR, f)), line: r.line, selector: keyOf(cx),
            detail: `border-radius: ${rad.value}`,
            why: '正文宿主不得是 pill 形：正文不是胶囊',
          });
        }
      }
    }
  }
}

// ── S4 Zero-paint floating band: no full-width glass or fade carrier. ──────
for (const pseudo of ['::before', '::after']) {
  const key = `.mf-strip${pseudo}`;
  const rules = [...(rulesOf.get('mobileLayer.css') || [])].filter(r => splitTop(r.selector, /,/).some(cx => keyOf(cx) === key));
  const contents = rules.flatMap(r => r.decls.filter(d => d.prop === 'content'));
  if (contents.at(-1)?.value !== 'none') add({
    rule: 'S4', file: rel(MOBILE_LAYER), line: rules.at(-1)?.line || 0, selector: key,
    detail: 'Full-width strip pseudo-element is not retired',
    why: 'The band must be transparent; only individual controls own glass.',
  });
}

// ── 输出 ──────────────────────────────────────────────────────────────────
const argv = new Set(process.argv.slice(2).filter((a) => !a.startsWith('--root=')));
if (argv.has('--json')) {
  console.log(JSON.stringify({
    ok: violations.length === 0,
    root: ROOT,
    faceKeys: [...faceDecls.keys()].sort(),
    glassMembers: [...glassMemberKeys].filter(isFaceKey).sort(),
    floatingBand: true,
    violations,
  }, null, 2));
  process.exit(violations.length ? 1 : 0);
}

if (violations.length) {
  console.error('✗ mobile-canvas-strip gate FAILED');
  console.error(`  环带面键 ${faceDecls.size} 个 · 违规 ${violations.length}`);
  for (const v of violations) {
    console.error(`  ${v.file}:${v.line}  [${v.rule}] ${v.selector}`);
    console.error(`      ${v.detail}`);
    console.error(`      ${v.why}`);
  }
  console.error('\n处置：环带（.mf-strip / .mf-strip-chip / .mf-strip-label）四条判据 =');
  console.error('      S1 可透出面走 css/glass.css 成员表；S2 水平方向零 mask；');
  console.error('      S3 胶囊只许 .on 档有材质、正文宿主零底；S4 整宽玻璃及渐隐伪元素零画。');
  process.exit(1);
}

console.log(
  `✓ mobile-canvas-strip gate passed — 环带面键 ${faceDecls.size} 个（玻璃成员 ${[...glassMemberKeys].filter(isFaceKey).length}）· `
  + '水平 mask 0 处 · 非主胶囊材质 0 处 · transparent floating band',
);
process.exit(0);
