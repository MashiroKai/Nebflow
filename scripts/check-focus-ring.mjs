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
//
// 附（规范 §2.6 圆角规则的登记面，不做级联推断）：`squareCorners` 里的每条「方角
// 造型件」必须写明半径值与非空理由，且选择器确实存在于所声明文件 —— 登记即承诺，
// 承诺要能被核对。
//
// 已知盲点（诚实记账，见规范「判据盲点」节）：本闸只读 CSS 文本，不做级联计算。
// ① 由 JS 内联写出的焦点样式不在判定面；② `:focus-within` 的容器环不判（它不是
// 「控件被键盘聚焦」，是「里面有人聚焦」）；③ 元素实际命中的圆角需运行时复核。
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
  if (!existsSync(LEDGER)) return { ok: false, exemptions: [], squareCorners: [], baseline: 0, error: `台账缺失：${rel(LEDGER)}` };
  let data;
  try {
    data = JSON.parse(readFileSync(LEDGER, 'utf8'));
  } catch (e) {
    return { ok: false, exemptions: [], squareCorners: [], baseline: 0, error: `台账不是合法 JSON：${String(e.message)}` };
  }
  const exemptions = Array.isArray(data.exemptions) ? data.exemptions : [];
  const squareCorners = Array.isArray(data.squareCorners) ? data.squareCorners : [];
  const baseline = Number.isFinite(data._baseline) ? data._baseline : exemptions.length;
  const bad = [
    ...exemptions.filter((r) => !r || !r.selector || !r.reason || String(r.reason).trim().length < 8)
      .map((r) => (r && r.selector) || '(空行)'),
    ...squareCorners.filter((r) => !r || !r.selector || !r.reason || String(r.reason).trim().length < 8)
      .map((r) => (r && r.selector) || '(空行)'),
  ];
  return {
    ok: bad.length === 0,
    exemptions,
    squareCorners,
    baseline,
    error: bad.length ? `台账有空理由行：${bad.join(' / ')}` : '',
  };
}

// ── 扫描 ────────────────────────────────────────────────────────────────
const violations = [];
const inventory = [];
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
// §2.6 圆角登记面核对
for (const row of ledger.squareCorners) {
  if (!row || !row.selector || !row.file) continue;
  const fileKey = [...sourceByRel.keys()].find((k) => k.includes(String(row.file).replace(/`/g, '').trim()));
  if (!fileKey) {
    addViolation('F5', LEDGER, 0, row.selector, `方角登记指向的文件不存在：${row.file}`, 'file');
    continue;
  }
  const sel = String(row.selector).replace(/`/g, '').trim();
  if (!sourceByRel.get(fileKey).replace(/\s+/g, ' ').includes(sel)) {
    addViolation('F5', LEDGER, 0, sel, `僵尸登记：${row.file} 里已找不到这个选择器`, 'selector');
  }
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
    ledger: { ok: ledger.ok, exemptions: ledger.exemptions.length, baseline: ledger.baseline, squareCorners: ledger.squareCorners.length, error: ledger.error },
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
  + `豁免 ${ledger.exemptions.length}/${ledger.baseline} 条、方角登记 ${ledger.squareCorners.length} 条、别名引用 0 处`);
process.exit(0);
