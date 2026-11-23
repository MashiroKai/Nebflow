#!/usr/bin/env node
// check-focus-ring.mjs — 「全站焦点框只有一种画法」静态闸
// （focus-ring-unify-impl 批 2026-11-22；规范 = design-language/references/frontend/
//   focus-ring-spec.md，作者已批 B 案：单色令牌 + 内缩 2px + 禁光晕）
//
// Why a gate：本批之前「键盘焦点长什么样」是**七个颜色源 × 三种几何 × 点光晕残留**
// 的自由组合。每个组件作者都写了一条自己觉得合理的环，所以人工审查抓不住
// 「又长出第八套」——而作者看到的是「边框怎么有的在外有的在里、有的发蓝光」。
//
// 判据（与规范 §2.2/§4 逐条对应）：
//
//  F1 颜色单源：`:focus` / `:focus-visible` 规则里 outline 的**颜色**只能是
//     `var(--nf-focus-ring)`；`Highlight` 仅允许出现在 `@media (forced-colors: active)`
//     块内（系统高对比，产品色被抹平的既定例外）。字面量（`rgb(var(--sapphire)/α)`、
//     `#2684ff`、`rgba(91,127,191)` 等）一律红。
//  F2 几何单源：画环的焦点规则里 `outline-offset` 只能是内缩 2px
//     （`calc(var(--nf-focus-ring-w) * -1)` 或其等价值 `-2px`）。外扩 / 无 offset 红。
//  F3 无光晕：焦点规则里 `box-shadow` 只能是 `none`（环改由 outline 单环承担）。
//  F4 无裸去环：给 `outline: none|0` 的焦点规则必须在豁免表具名登记（理由非空），
//     否则红 —— 键盘 Tab 到它就是零反馈。
//  F5 豁免只减：豁免条数不得高于实施批登记的 `_baseline`；每条必须能在那份 CSS 里
//     找到对应选择器（防止「记住了但代码早改了」的僵尸豁免）。
//  F6 别名收编：`--btn-ring*` / `--ds-focus-ring` / `--dropdown-focus` / `--nf-field-ring*`
//     全树 0 处（含定义处；定义留着 = 有人随时会重新引用它）。
//  F7 圆角标准（round2 · 作者令 2026-11-22 08:03「我说了要圆角的框」「落为标准」）：
//     画环的基座必须显式落到 ≥6px 档；胶囊/圆形（999px / 50%）放行；短边 <12px 的微格
//     允许 ≤4px（6px 在 11px 格上被钳成圆点，热力图语义被破）；查不到任何圆角声明 = 红。
//     **方角登记豁免机制已废除** —— 台账再带 `squareCorners` 段也是红（机制不得复活）。
//  F8 环不改形状：焦点规则里不得声明 border-radius（环半径 = 组件半径）。实测反例：
//     `#deck-fab:focus-visible{border-radius:6px}` 让圆钮在键盘聚焦时变成圆角方钮。
//
// 已知盲点（诚实记账，见规范 §6「判据盲点」）：本闸只读 CSS 文本，不做级联计算。
// ① 由 JS 内联写出的焦点样式不在判定面；② `:focus-within` 的容器环不判（它不是
// 「控件被键盘聚焦」，是「里面有人聚焦」）；③ F7 用「index.html 加载序 + 圆角声明」
// 近似级联（含 @media 内规则），裸元素基座与 ID 主体基座跳过 —— 这两类由运行时闸
// P-5 按计算样式判（那是「元素到底是不是方角」的权威读数）。
//
// 用法：
//   node scripts/check-focus-ring.mjs              # 门禁（红/绿）
//   node scripts/check-focus-ring.mjs --json       # 机器读（逐条命中）
//   node scripts/check-focus-ring.mjs --inventory  # 盘点（全部焦点规则 + 环声明）
//   node scripts/check-focus-ring.mjs --root=<dir> # 在另一个检出上跑同一判据
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveDesignSpec, specMissingMessage } from './lib/design-spec-source.mjs';

const ROOT_OVERRIDE = (process.argv.slice(2).find((a) => a.startsWith('--root=')) || '').slice(7);
const ROOT = ROOT_OVERRIDE
  ? fileURLToPath(new URL(`file://${ROOT_OVERRIDE}/`))
  : fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const CSS_DIR = join(WEB, 'css');
const LEDGER = join(ROOT, 'scripts', 'focus-ring-exemptions.json');
/** 规范正文（单源在仓内 design-language/references/frontend/）。 */
const SPEC_FILE = resolveDesignSpec('references/frontend/focus-ring-spec.md');
if (!SPEC_FILE) {
  console.error(`focus-ring: ${specMissingMessage('references/frontend/focus-ring-spec.md')}`);
  process.exit(2);
}

const JSON_OUT = process.argv.includes('--json');
const INVENTORY = process.argv.includes('--inventory');

const rel = (p) => relative(ROOT, p).replace(/\\/g, '/');

/** 焦点伪类（`:focus-within` 刻意不判 —— 见头注盲点②）。 */
const FOCUS_SEL = /:focus(?:-visible)?(?![\w-])/;
const STYLE_WORDS = new Set(['none', 'auto', 'dotted', 'dashed', 'solid', 'double', 'groove',
  'ridge', 'inset', 'outset', 'hidden']);
const WIDTH_WORDS = new Set(['thin', 'medium', 'thick']);
/** 别名令牌族（F6 收编目标；前缀匹配涵盖 -w / -offset 兄弟槽）。 */
const ALIAS_TOKENS = ['--btn-ring', '--ds-focus-ring', '--dropdown-focus', '--nf-field-ring'];
const CANON_COLOR = 'var(--nf-focus-ring)';
const CANON_WIDTH = 'var(--nf-focus-ring-w)';
const CANON_OFFSET = /^calc\(\s*var\(--nf-focus-ring-w\s*\)\s*\*\s*-1\s*\)$/;

function stripCssComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/** 极简 CSS 块遍历器（与 check-input-surface / check-flat-buttons 同口径）。 */
function* walkRules(src) {
  const text = stripCssComments(src);
  const lineOf = (idx) => text.slice(0, idx).split('\n').length;
  let i = 0;
  let selStart = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '{') {
      const prelude = text.slice(selStart, i).trim();
      const bodyStart = i + 1;
      let j = bodyStart;
      let inner = 0;
      while (j < text.length) {
        if (text[j] === '{') inner++;
        else if (text[j] === '}') {
          if (inner === 0) break;
          inner--;
        }
        j++;
      }
      const body = text.slice(bodyStart, j);
      yield { selector: prelude, body, line: lineOf(selStart), offset: selStart, end: j };
      i = j + 1;
      selStart = i;
      continue;
    }
    if (ch === '}' || ch === ';') selStart = i + 1;
    i++;
  }
}

/** 规则体 → 声明对（跳过嵌套块；本仓 CSS 以平铺为主）。 */
function parseDecls(body) {
  const out = [];
  for (const raw of body.split(';')) {
    const k = raw.indexOf(':');
    if (k < 0) continue;
    const prop = raw.slice(0, k).trim().toLowerCase();
    const value = raw.slice(k + 1).replace(/!important/gi, '').trim();
    if (!/^[-a-z@]/.test(prop) || !value) continue;
    out.push({ prop, value });
  }
  return out;
}

/** 按顶层空白切分值（保留 var()/rgba() 里的空格）。 */
function splitTopLevel(value) {
  const parts = [];
  let depth = 0;
  let cur = '';
  for (const ch of value) {
    if (ch === '(') depth++;
    else if (ch === ')') depth--;
    if (depth === 0 && /\s/.test(ch)) {
      if (cur) { parts.push(cur); cur = ''; }
      continue;
    }
    cur += ch;
  }
  if (cur) parts.push(cur);
  return parts;
}

/** outline 简写 → { color, width, style }（未知片段原样带回，供报告）。 */
function parseOutline(value) {
  const v = value.trim();
  if (/^(none|0)$/i.test(v)) return { color: '', width: '', style: 'none', bare: true };
  const parts = splitTopLevel(v);
  const color = [];
  const width = [];
  const style = [];
  for (const p of parts) {
    const low = p.toLowerCase();
    if (STYLE_WORDS.has(low)) { style.push(low); continue; }
    if (WIDTH_WORDS.has(low) || /^-?[\d.]+(px|em|rem|pt|ch|ex|vw|vh|%)$/.test(low) || /var\(--[\w-]*w\)/.test(low)) {
      width.push(p);
      continue;
    }
    color.push(p);
  }
  return { color: color.join(' '), width: width.join(' '), style: style.join(' '), bare: false };
}

// ── 豁免台账（读 JSON，脚本内零硬编码豁免）───────────────────────────────
function loadLedger() {
  if (!existsSync(LEDGER)) return { ok: false, exemptions: [], baseline: 0, error: `台账缺失：${rel(LEDGER)}` };
  let data;
  try {
    data = JSON.parse(readFileSync(LEDGER, 'utf8'));
  } catch (e) {
    return { ok: false, exemptions: [], baseline: 0, error: `台账不是合法 JSON：${String(e.message)}` };
  }
  const exemptions = Array.isArray(data.exemptions) ? data.exemptions : [];
  const baseline = Number.isFinite(data._baseline) ? data._baseline : exemptions.length;
  const bad = exemptions
    .filter((r) => !r || !r.selector || !r.reason || String(r.reason).trim().length < 8)
    .map((r) => (r && r.selector) || '(空行)');
  // round2：方角登记段（squareCorners）已废除 —— 台账若还留着它 = 红（机制不得复活）。
  const legacySquare = Array.isArray(data.squareCorners) ? data.squareCorners.length : 0;
  const badLegacy = legacySquare
    ? `台账仍带 squareCorners 段（${legacySquare} 条）—— round2 已废除方角登记机制，圆角改由 F7 几何判据核对`
    : '';
  return {
    ok: bad.length === 0 && !badLegacy,
    exemptions,
    baseline,
    legacySquare,
    error: bad.length ? `台账有空理由行：${bad.join(' / ')}` : badLegacy,
  };
}

// ── 扫描 ────────────────────────────────────────────────────────────────
const violations = [];
const inventory = [];
/** F7 用：所有画环的基座（文件 + 行 + 基座），循环后统一判圆角。 */
const ringBaseRows = [];
const ledger = loadLedger();

function addViolation(code, file, line, selector, detail, prop) {
  violations.push({ code, file: rel(file), line, selector: selector.trim().replace(/\s+/g, ' '), detail, prop });
}

const cssFiles = existsSync(CSS_DIR)
  ? readdirSync(CSS_DIR).filter((f) => f.endsWith('.css')).sort().map((f) => join(CSS_DIR, f))
  : [];
if (!cssFiles.length) {
  console.error(`focus-ring: css dir not found: ${CSS_DIR}`);
  process.exit(2);
}

/** 该规则是否位于 forced-colors 媒体块内（按文件内索引区间判定）。 */
function forcedColorRanges(text) {
  const ranges = [];
  const re = /@media[^{]*forced-colors\s*:\s*active[^{]*\{/gi;
  let m;
  while ((m = re.exec(text))) {
    const start = m.index + m[0].length;
    let depth = 1;
    let i = start;
    while (i < text.length && depth > 0) {
      if (text[i] === '{') depth++;
      else if (text[i] === '}') depth--;
      i++;
    }
    ranges.push([start, i]);
  }
  return ranges;
}

/** 选择器组切分：括号感知（`:is(…, …)` / `:where(…, …)` 里的逗号不是分隔符）。 */
function splitSelectorGroup(selector) {
  const out = [];
  let depth = 0;
  let cur = '';
  for (const ch of selector) {
    if (ch === '(') depth++;
    else if (ch === ')') depth--;
    if (ch === ',' && depth === 0) { out.push(cur); cur = ''; continue; }
    cur += ch;
  }
  if (cur.trim()) out.push(cur);
  return out.map((s) => s.trim()).filter(Boolean);
}

/** 选择器的「基座」：去掉焦点伪类后的同一元素/同一组元素（配对判定用）。 */
function selectorBase(sel) {
  return sel.replace(/:focus(?:-visible)?(?![\w-])/g, '').replace(/\s+/g, ' ').trim();
}

/** 豁免匹配：file 片段 + 选择器片段都命中才算（比整串相等宽松，便于写人话）。 */
function exemptionFor(selector, relPath) {
  return ledger.exemptions.find((r) => {
    if (!r || !r.selector) return false;
    const filePart = String(r.file || '').replace(/`/g, '').trim();
    if (filePart && !relPath.includes(filePart)) return false;
    return selector.replace(/\s+/g, ' ').includes(String(r.selector).replace(/`/g, '').trim());
  });
}

for (const file of cssFiles) {
  const raw = readFileSync(file, 'utf8');
  const text = stripCssComments(raw);
  const fcRanges = forcedColorRanges(text);
  const inForcedColors = (idx) => fcRanges.some(([s, e]) => idx >= s && idx < e);
  const relPath = rel(file);

  // 先扫一遍「同一文件里哪些基座已经有环规则」—— F4 的「裸」指全文件都没有配对环，
  // 不是「这一条规则里没写环」（.canvas-video-stage:focus{none} + :focus-visible 环
  // 这种拆写法是合法的：一条管指针、一条管键盘）。
  const rules = [...walkRules(raw)];
  const ringBases = new Set();
  for (const rule of rules) {
    const sels = splitSelectorGroup(rule.selector);
    if (!sels.some((s) => FOCUS_SEL.test(s))) continue;
    const draws = parseDecls(rule.body).some((d) => {
      if (d.prop !== 'outline') return false;
      const o = parseOutline(d.value);
      return !o.bare && o.style !== 'none';
    });
    if (!draws) continue;
    for (const s of sels) if (FOCUS_SEL.test(s)) ringBases.add(selectorBase(s));
  }

  // F7 的输入：把「画环的基座」记进全树清单（圆角核对在循环之后统一做）。
  for (const rule of rules) {
    const sels = splitSelectorGroup(rule.selector);
    for (const s of sels) {
      if (!FOCUS_SEL.test(s)) continue;
      const draws = parseDecls(rule.body).some((d) => {
        if (d.prop !== 'outline') return false;
        const o = parseOutline(d.value);
        return !o.bare && o.style !== 'none';
      });
      if (!draws) continue;
      ringBaseRows.push({ file: relPath, line: rule.line, base: selectorBase(s) });
    }
  }

  for (const rule of rules) {
    const sels = splitSelectorGroup(rule.selector);
    const focusSels = sels.filter((s) => FOCUS_SEL.test(s));
    if (!focusSels.length) continue;
    const decls = parseDecls(rule.body);
    const outlineDecls = decls.filter((d) => d.prop === 'outline' || d.prop === 'outline-color'
      || d.prop === 'outline-offset' || d.prop === 'box-shadow');
    if (INVENTORY && outlineDecls.length) {
      inventory.push({
        file: relPath,
        line: rule.line,
        selector: rule.selector.trim().replace(/\s+/g, ' '),
        decls: outlineDecls.map((d) => `${d.prop}: ${d.value}`),
        forcedColors: inForcedColors(rule.offset),
      });
    }

    const forced = inForcedColors(rule.offset);
    const bare = [];

    for (const d of decls) {
      if (d.prop === 'outline' || d.prop === 'outline-color') {
        const o = parseOutline(d.value);
        if (o.bare) { bare.push(d); continue; }
        // F1 颜色单源
        const color = o.color;
        const colorOk = color === CANON_COLOR
          || (forced && /^highlight$/i.test(color))
          || (d.prop === 'outline-color' && forced && /^highlight$/i.test(d.value.trim()));
        if (!colorOk) {
          addViolation('F1', file, rule.line, rule.selector,
            `${d.prop}: ${d.value} —— 焦点色只能取 ${CANON_COLOR}${forced ? '（forced-colors 内可为 Highlight）' : ''}`, d.prop);
        }
        // 宽度同属环几何：令牌化，别让某一条悄悄写成 3px
        if (o.width && o.width !== CANON_WIDTH && !/^2px$/.test(o.width)) {
          addViolation('F2', file, rule.line, rule.selector,
            `${d.prop}: ${d.value} —— 环宽只能取 ${CANON_WIDTH}（等价值 2px）`, 'outline-width');
        }
      }
      if (d.prop === 'outline-offset') {
        // F2 几何单源
        const v = d.value.trim();
        if (!CANON_OFFSET.test(v) && v !== '-2px') {
          addViolation('F2', file, rule.line, rule.selector,
            `outline-offset: ${v} —— 只能是内缩 calc(var(--nf-focus-ring-w) * -1)（等价值 -2px）`, 'outline-offset');
        }
      }
      if (d.prop === 'box-shadow') {
        // F3 无光晕
        if (!/^(none|0)$/i.test(d.value.trim())) {
          addViolation('F3', file, rule.line, rule.selector,
            `box-shadow: ${d.value} —— 焦点不许用光晕/投影环，环由 outline 承担`, 'box-shadow');
        }
      }
      if (d.prop === 'border-radius') {
        // F8 环半径 = 组件半径：焦点规则自己写半径 = 「聚焦瞬间改组件形状」。
        // 实测反例（round2 首跑）：`#deck-fab:focus-visible { border-radius: 6px }` 让圆钮
        // 在键盘聚焦时变成圆角方钮。组件方角 ⇒ 改组件（F7），不要在环规则里补。
        addViolation('F8', file, rule.line, rule.selector,
          `border-radius: ${d.value} —— 焦点规则不得声明圆角（环半径 = 组件半径，规范 §2.6）`,
          'border-radius');
      }
    }

    // F4 无裸去环（「裸」= 同文件里这个基座任何焦点规则都没画环）
    const paired = focusSels.some((s) => ringBases.has(selectorBase(s)));
    if (bare.length && !paired && !exemptionFor(rule.selector, relPath)) {
      addViolation('F4', file, rule.line, rule.selector,
        `outline: ${bare[0].value} —— 同文件内该基座无配对环，且未在 ${rel(LEDGER)} 登记（键盘 Tab 到 = 零反馈）`, 'outline');
    }
  }
}

// F5 豁免只减（含僵尸豁免核对）
if (ledger.exemptions.length > ledger.baseline) {
  addViolation('F5', LEDGER, 0, '(豁免台账)',
    `豁免 ${ledger.exemptions.length} 条 > 基线 ${ledger.baseline} 条 —— 只许减不许增`, '_baseline');
}
const sourceByRel = new Map(cssFiles.map((f) => [rel(f), stripCssComments(readFileSync(f, 'utf8'))]));
for (const row of ledger.exemptions) {
  if (!row || !row.selector || !row.file) continue;
  const fileKey = [...sourceByRel.keys()].find((k) => k.includes(String(row.file).replace(/`/g, '').trim()));
  if (!fileKey) {
    addViolation('F5', LEDGER, 0, row.selector, `豁免指向的文件不存在：${row.file}`, 'file');
    continue;
  }
  const sel = String(row.selector).replace(/`/g, '').trim();
  if (!sourceByRel.get(fileKey).replace(/\s+/g, ' ').includes(sel.replace(/\s+/g, ' '))) {
    addViolation('F5', LEDGER, 0, sel, `僵尸豁免：${row.file} 里已找不到这个选择器`, 'selector');
  }
}
// ── F7 圆角标准（作者令 2026-11-22 08:03「我说了要圆角的框」「落为标准」；规范 §2.6）
// 方角登记豁免机制已废除：圆角不再靠台账自证，改由**几何判据**核对 —— 画环的基座
// 必须是圆角件：标准档 ≥6px；胶囊/圆形（999px / 50%）放行；短边 <12px 的微格允许
// 2px（否则 6px 在 11px 格上被钳成圆点，热力图语义被破）。无任何圆角声明 = UA 方角
// = 红。元素级基座（`textarea` 这类裸元素选择器）不判 —— 它的圆角由上下文决定，
// 静态闸不做级联（规范 §6 盲点①），那部分由运行时闸 P-5 在真像素上判。
const MIN_RADIUS_PX = 6;
const MICRO_RADIUS_MAX_PX = 4;

/** 递归遍历（含 @media / @supports 等 at-rule 内的规则）：F7 判形状必须看得见
 *  「在媒体查询里才生效的圆角」，否则 `.ds-btn` 这类被 @media 包着的基座会被误判成方角。
 *  line 为近似值（at-rule 内规则的行号按块内相对位置给出）。 */
function* walkRulesDeep(src, lineBase = 1) {
  for (const rule of walkRules(src)) {
    const isAt = /^@/.test(rule.selector.trim());
    if (isAt) {
      const innerBase = lineBase + src.slice(0, rule.offset + rule.selector.length).split('\n').length - 1;
      yield* walkRulesDeep(rule.body, innerBase);
      continue;
    }
    yield rule;
  }
}

function buildShapeIndex() {
  const tokens = new Map();
  const decls = [];
  // 级联顺序 = index.html 的 <link rel="stylesheet"> 顺序（真实加载序）；不在 html 里的
  // 文件排在最后（按文件名），只会影响「谁最后写」的近似，不会漏判方角。
  const order = new Map();
  try {
    const html = readFileSync(join(WEB, 'index.html'), 'utf8');
    let i = 0;
    for (const m of html.matchAll(/<link[^>]+rel="stylesheet"[^>]+href="([^"]+)"/g)) {
      const name = m[1].split('/').pop();
      if (!order.has(name)) order.set(name, i++);
    }
  } catch { /* 没有 index.html 就退化为文件名字典序 */ }
  for (const file of cssFiles) {
    const name = file.split('/').pop();
    const ordinal = order.has(name) ? order.get(name) : 1000;
    const src = stripCssComments(readFileSync(file, 'utf8'));
    for (const m of src.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) tokens.set(m[1], m[2].trim());
    let seq = 0;
    for (const rule of walkRulesDeep(src)) {
      seq++;
      const body = rule.body;
      const grab = (prop) => {
        const m = body.match(new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+);`));
        return m ? m[1].trim() : null;
      };
      const radius = grab('border-radius');
      const width = grab('width');
      const height = grab('height');
      if (!radius && !width && !height) continue;
      decls.push({
        file: rel(file),
        ord: ordinal * 100000 + seq,
        line: rule.line,
        selector: rule.selector.trim().replace(/\s+/g, ' '),
        radius,
        width: resolveLen(width, tokens),
        height: resolveLen(height, tokens),
      });
    }
  }
  return { decls, tokens };
}

/** `var(--x)` / `var(--x, fallback)` → 解析成字面值（深度上限防环）。 */
function resolveToken(value, tokens, depth = 0) {
  if (!value || depth > 6) return value;
  const m = /^var\(\s*(--[\w-]+)\s*(?:,\s*([^)]+))?\)$/.exec(value.trim());
  if (!m) return value.trim();
  if (tokens.has(m[1])) return resolveToken(tokens.get(m[1]), tokens, depth + 1);
  return m[2] ? resolveToken(m[2], tokens, depth + 1) : value.trim();
}

/** 长度字面量 → px 数（解析不出返回 null：交给运行时判）。 */
function resolveLen(value, tokens) {
  if (!value) return null;
  const v = resolveToken(value, tokens);
  const m = /^(-?[\d.]+)px$/.exec(String(v).trim());
  return m ? Number(m[1]) : null;
}

/** 半径声明 → px 数 / 'pill' / 'circle' / null（未知）。 */
function radiusPx(value, tokens) {
  if (!value) return null;
  const parts = String(value).trim().split(/\s+/).map((p) => resolveToken(p, tokens));
  if (parts.some((p) => p === '50%')) return 'circle';
  const nums = parts.map((p) => {
    const m = /^(-?[\d.]+)px$/.exec(String(p).trim());
    if (m) return Number(m[1]);
    if (/^999px$/.test(String(p).trim())) return 999;
    if (/^50%$/.test(String(p).trim())) return 999; // 圆形
    return null;
  });
  if (nums.includes(999)) return 'pill';
  if (nums.some((n) => n == null)) return null;
  return Math.max(...nums);
}

/** 基座 → 候选基座（去掉尾随伪类/属性段；`:is()/:where()` 组展开成成员）。
 *  作用：`.ud-cell[data-date]` 的圆角写在 `.ud-cell` 上；`:is(#send-btn, …)` 的圆角
 *  写在成员族上 —— 这两种都是「同一个件换了写法」，不是方角。 */
function baseCandidates(base) {
  const out = new Set([base]);
  const isGroup = /^:(is|where)\(/.exec(base);
  if (isGroup) {
    const inner = base.slice(base.indexOf('(') + 1, base.lastIndexOf(')'));
    for (const part of splitSelectorGroup(inner)) {
      out.add(part.replace(/\s+/g, ' ').trim());
    }
  }
  // 后代链的「主体复合选择器」（`#bg-dropdown .bg-task-row` → `.bg-task-row`）与其类名：
  // 圆角一般写在主体那一族上，写成带祖先后缀的复制品是同一个件。
  for (const cand of [...out]) {
    const subject = (cand.match(/([^\s>+~]+)$/) || [])[1];
    if (!subject || subject === cand) continue;
    out.add(subject);
    for (const tok of subject.match(/[.#][\w-]+/g) || []) out.add(tok);
  }
  for (const cand of [...out]) {
    let cur = cand;
    for (;;) {
      const next = cur.replace(/(::?[\w-]+(\([^)]*\))?|\[[^\]]+\])$/, '').trim();
      if (next === cur || !next) break;
      out.add(next);
      cur = next;
    }
  }
  return [...out].filter(Boolean);
}

/** 基座 → 形状声明（按选择器组逐段匹配，边界感知；按**加载序 + 行号**近似级联，
 *  取最后一条圆角声明为准 —— 静态闸不做特异性计算，见规范 §6 盲点①）。 */
function shapeFor(base, index) {
  const hits = [];
  const cands = baseCandidates(base);
  for (const d of index.decls) {
    for (const sel of splitSelectorGroup(d.selector)) {
      const norm = sel.replace(/\s+/g, ' ').trim();
      if (!norm) continue;
      for (const cand of cands) {
        const esc = cand.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        // 以 `.` / `#` 开头的基座：左边可以是复合选择器的成员边界（`pre.perm-cmd`），
        // 因此不要求左侧分隔符（点号本身就是分隔）；右侧必须是词边界（`.perm-cmd`
        // 不得命中 `.perm-cmd-toggle`）。其余形式（属性/标签开头）保留左右分隔。
        const re = cand.startsWith('.') || cand.startsWith('#')
          ? new RegExp(`${esc}(?![\\w-])`)
          : new RegExp(`(^|[\\s(,>+~])${esc}(?![\\w-])`);
        if (!re.test(norm)) continue;
        hits.push(d);
        break;
      }
    }
  }
  return hits.sort((a, b) => a.ord - b.ord);
}

const shapeIndex = buildShapeIndex();
const seenShape = new Set();
for (const row of ringBaseRows) {
  const base = String(row.base || '').replace(/\s+/g, ' ').trim();
  if (!base) continue;
  // 元素级基座（裸标签）与 ID 主体基座不判：
  //  · 裸标签（`textarea`）—— 圆角由上下文决定；
  //  · ID 主体（`#send-btn`）—— 它的形状来自元素上的**类**（如 `.icon-btn` 50%），
  //    静态闸看不见元素实际带了哪些类。
  // 这两类都由运行时闸 P-5 在真像素上判（规范 §6 盲点①）。
  const subject = (base.match(/([^\s>+~]+)$/) || [])[1] || '';
  const elementOnly = !/[.#\[]/.test(subject);
  const idSubject = subject.startsWith('#');
  if (elementOnly || idSubject) continue;
  if (exemptionFor(base, row.file)) continue;
  const key = `${row.file}::${base}`;
  if (seenShape.has(key)) continue;
  seenShape.add(key);
  const hits = shapeFor(base, shapeIndex);
  const radiusHits = hits.filter((h) => h.radius);
  const last = radiusHits.length ? radiusHits[radiusHits.length - 1] : null;
  const eff = last ? radiusPx(last.radius, shapeIndex.tokens) : null;
  const micro = hits.some((h) => (h.width != null && h.width > 0 && h.width < 12)
    || (h.height != null && h.height > 0 && h.height < 12));
  if (!last) {
    addViolation('F7', join(ROOT, row.file), row.line, base,
      '焦点环基座在整个 CSS 树里查不到任何圆角声明（= UA 方角）—— 可交互件必须显式落到 '
      + `≥${MIN_RADIUS_PX}px 档（--ds-radius-chip / -input / -button），微格按 §2.6 取 2px`, 'border-radius');
    continue;
  }
  if (eff === 'pill' || eff === 'circle') continue;
  if (eff == null) continue; // 解析不出（calc / inherit）⇒ 交给运行时
  if (eff >= MIN_RADIUS_PX) continue;
  if (micro && eff <= MICRO_RADIUS_MAX_PX) continue;
  addViolation('F7', join(ROOT, row.file), row.line, base,
    `焦点环基座圆角 ${eff}px < 标准档 ${MIN_RADIUS_PX}px（规范 §2.6；生效声明 = ${last.file}:${last.line} `
    + `"${last.radius}"）—— 方角登记豁免机制已废除，改组件圆角而不是登记`, 'border-radius');
}

// F6 别名收编（全树 0 处，含定义处）
for (const file of cssFiles) {
  const src = stripCssComments(readFileSync(file, 'utf8'));
  const lineOf = (idx) => src.slice(0, idx).split('\n').length;
  for (const token of ALIAS_TOKENS) {
    const re = new RegExp(`${token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[\\w-]*\\s*:`, 'g');
    let m;
    while ((m = re.exec(src))) {
      const name = m[0].replace(/\s*:$/, '');
      addViolation('F6', file, lineOf(m.index), name, `${name} 已收编进 --nf-focus-ring —— 定义处也要删`, name);
    }
  }
}

// ── 输出 ────────────────────────────────────────────────────────────────
if (JSON_OUT) {
  console.log(JSON.stringify({
    ok: violations.length === 0 && ledger.ok,
    ledger: { ok: ledger.ok, exemptions: ledger.exemptions.length, baseline: ledger.baseline, legacySquareCorners: ledger.legacySquare, error: ledger.error },
    ringBases: ringBaseRows.length,
    violations,
  }, null, 2));
  process.exit(violations.length === 0 && ledger.ok ? 0 : 1);
}

if (INVENTORY) {
  console.log(`focus-ring inventory — ${inventory.length} 条焦点环规则\n`);
  const byFile = new Map();
  for (const row of inventory) {
    if (!byFile.has(row.file)) byFile.set(row.file, []);
    byFile.get(row.file).push(row);
  }
  for (const [f, rows] of [...byFile.entries()].sort((a, b) => b[1].length - a[1].length)) {
    console.log(`## ${f}  (${rows.length})`);
    for (const r of rows) console.log(`  ${r.file}:${r.line}${r.forcedColors ? ' [forced-colors]' : ''}  ${r.selector}\n      ${r.decls.join('; ')}`);
    console.log('');
  }
  console.log(`TOTAL=${inventory.length} FILES=${byFile.size}`);
  process.exit(0);
}

const codes = [...new Set(violations.map((v) => v.code))].sort();
if (violations.length || !ledger.ok) {
  console.error(`focus-ring: RED — ${violations.length} 违规${codes.length ? `（${codes.join('/')}）` : ''}`);
  if (!ledger.ok) console.error(`  豁免台账：${ledger.error}`);
  const byFile = new Map();
  for (const v of violations) {
    if (!byFile.has(v.file)) byFile.set(v.file, []);
    byFile.get(v.file).push(v);
  }
  for (const [f, rows] of [...byFile.entries()].sort((a, b) => b[1].length - a[1].length)) {
    console.error(`\n  ${f}  (${rows.length})`);
    for (const r of rows) console.error(`    ${r.code}  ${f}:${r.line}  ${r.selector}\n        ${r.detail}`);
  }
  process.exit(1);
}
console.log(`focus-ring: OK — 焦点环单规则单色源（${CANON_WIDTH} solid ${CANON_COLOR} · 内缩 · 禁光晕）；`
  + `豁免 ${ledger.exemptions.length}/${ledger.baseline} 条、圆角标准 F7 覆盖 ${ringBaseRows.length} 个环基座`
  + `（方角登记机制已废除）、别名引用 0 处`);
process.exit(0);
