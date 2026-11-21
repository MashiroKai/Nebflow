#!/usr/bin/env node
// check-input-surface.mjs — 「全站输入框只有一种造型」静态哨兵
// （input-surface-unify 批 2026-11-06；作者裁定逐字：
//   「输入框的这个样式也要成为统一标准 不可以出现第二种」）
//
// Why a gate：本批之前「输入框长什么样」是**各组件各自写的**。
// 同一屏里的两个文本输入件，背景/边框/圆角/阴影/焦点态可以来自四份不同声明：
//   · `.cfg-input`（modal.css:479 + sidebar.css:1421，两份逐字重复的 etched 组合）
//   · `.secrets-input`（secrets.css:364，glass-etched 组合）
//   · `.pp-field input` / `.to-input` / `.mh-search-input` …（各自一份）
//   · 元素级兜底（inputFocus.css 的 `:focus` 蓝描边 + 蓝 border-color）
// 每个都「看起来合理」，所以人工审查抓不住「又长出来一套」——而作者看到的
// 后果是「这个蓝色输入框怎么到处在显示」。
//
// 判据把「一种造型」拆成可机械判定的六条：
//
//  I1 令牌单一源：`--nf-field-*` 只允许在 `css/stand-tokens.css` 定义。
//  I2 造型单一源：**输入面选择器**上的造型声明（背景/边框/圆角/阴影/描边）
//     只允许出现在 `css/inputSurface.css`。别的文件写 = 第二套在长。
//  I3 数值单一源：`css/inputSurface.css` 里输入面规则的造型值必须走
//     `var(--nf-field-*)`（零裸色值 / 零裸 px 圆角 / 零裸阴影）。
//  I4 蓝不进输入面：输入面规则里出现蓝族令牌（focus ring / sapphire /
//     --color-primary / etched-border-focus …）⇒ 红。唯一开口在
//     `css/inputSurface.css` 里、以 `[data-nf-modality="keyboard"]` 为条件的
//     键盘可见焦点规则（那是无障碍，不是第二套）。
//  I5 JS 注入面：`js/**/*.js` 里对输入件 `style.border*/background*/borderRadius/
//     boxShadow` 的赋值同样算「第二套」（CSS 属性串直接写进内联样式）。
//  I6 豁免清单：真需要不同造型的场合逐条具名登记在**规范文件**（单源在仓内
//     `design-language/references/frontend/input-surface-spec.md`，定位见
//     scripts/lib/design-spec-source.mjs）
//     的「例外清单」表里（选择器 + 判据号 + 理由）。脚本内零硬编码豁免；
//     表里空理由 = 红，缺表 = 红（没有规范就没有约束）。
//
// 输入面（input face）的机械定义 —— 不靠人记账：
//   取选择器的**最右复合选择器**，命中任一条即算：
//     a) 类型选择器 `input` / `textarea` / `select`
//     b) class/id 名的**末段**是 input / textarea / select（`.cfg-input` → input）
//     c) 右复合里含伪类 `::placeholder` 且主体命中 a/b
//   这条口径挡得住「换个类名再画一套」吗？—— 挡不住，但 I2 的兜底是：只要
//   右复合命中**元素名** input/textarea/select（全站 90% 的输入件都在这里），
//   任何文件写的造型都会被抓。剩下的漏网由 I3（标准文件零裸值）与 I6 的
//   豁免纪律兜（新增面必须先登记）。已知盲点写进规范的「判据盲点」节，不装作没有。
//
// 用法：
//   node scripts/check-input-surface.mjs              # 门禁（红/绿）
//   node scripts/check-input-surface.mjs --json       # 机器读（逐条命中）
//   node scripts/check-input-surface.mjs --inventory  # 盘点（全部输入面规则 + 造型声明）
//   node scripts/check-input-surface.mjs --root=<dir> # 在另一个检出上跑同一判据
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
const JS_DIR = join(WEB, 'js');
const TOKENS = join(CSS_DIR, 'stand-tokens.css');
/** 唯一造型点：输入面的背景/边框/圆角/阴影/焦点态只许在这里写 */
const STANDARD_FILE = join(CSS_DIR, 'inputSurface.css');
/** 规范正文（单源在仓内 design-language/references/frontend/）。 */
const SPEC_FILE = resolveDesignSpec('references/frontend/input-surface-spec.md');
if (!SPEC_FILE) {
  console.error(`input-surface: ${specMissingMessage('references/frontend/input-surface-spec.md')}`);
  process.exit(2);
}

const JSON_OUT = process.argv.includes('--json');
const INVENTORY = process.argv.includes('--inventory');

const rel = (p) => relative(ROOT, p);

/** 造型声明（输入面「长什么样」的全部通道） */
const PAINT_PROPS = new Set([
  'background', 'background-color', 'background-image', 'background-blend-mode',
  'border', 'border-color', 'border-width', 'border-style',
  'border-top', 'border-right', 'border-bottom', 'border-left',
  'border-top-color', 'border-right-color', 'border-bottom-color', 'border-left-color',
  'border-top-width', 'border-right-width', 'border-bottom-width', 'border-left-width',
  'border-top-style', 'border-right-style', 'border-bottom-style', 'border-left-style',
  'border-radius', 'border-top-left-radius', 'border-top-right-radius',
  'border-bottom-left-radius', 'border-bottom-right-radius',
  'box-shadow', 'outline', 'outline-color', 'outline-width', 'outline-style',
  'outline-offset', 'backdrop-filter', '-webkit-backdrop-filter',
]);

/** 蓝族（品牌蓝只属于「非输入面」的焦点环与链接） */
const BLUE_TOKENS = [
  '--btn-focus-ring', '--nf-focus-ring', '--color-primary', '--ds-focus-ring',
  '--glass-etched-border-focus', '--btn-ring', '--sapphire', '--sapphire-refraction',
];
const BLUE_LITERAL = /#(?:3b82f6|60a5fa|5b7fbf|2563eb|1d4ed8)\b|\brgba?\(\s*91\s*,\s*127\s*,\s*191/i;

const INPUT_ELEMENTS = new Set(['input', 'textarea', 'select']);
const NAME_TAIL = new Set(['input', 'inputs', 'textarea', 'textareas', 'select', 'selects']);

function stripCssComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/** 极简 CSS 块遍历器（与 check-flat-buttons / check-toggle-unify 同口径）。 */
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
      yield { selector: prelude, body, line: lineOf(selStart) };
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

/** 选择器组里最右复合选择器的「身份判据」。 */
function rightmostCompound(sel) {
  // 去伪元素（::placeholder 等）后再取最后一段
  return sel
    .replace(/::?[a-z-]+(\([^)]*\))?/gi, ' ')
    .split(/\s*[>+~]\s*|\s+/)
    .filter(Boolean)
    .pop() || '';
}

function compoundIdentity(compound) {
  const bare = compound.replace(/\[[^\]]*\]/g, '');
  const element = (/^([a-z][a-z0-9-]*)/i.exec(bare.replace(/^[#.]/, '')) || [])[1] || '';
  const names = [...bare.matchAll(/[#.]([A-Za-z0-9_-]+)/g)].map((m) => m[1]);
  return { element: element.toLowerCase(), names };
}

/** 该选择器是不是「输入面」？ */
function isInputFace(sel) {
  const raw = sel.trim();
  if (!raw || /^@/.test(raw)) return false;
  const compound = rightmostCompound(raw);
  if (!compound) return false;
  const { element, names } = compoundIdentity(compound);
  if (INPUT_ELEMENTS.has(element)) return true;
  return names.some((n) => {
    const segs = n.split(/[-_]/).filter(Boolean);
    return segs.length > 0 && NAME_TAIL.has(segs[segs.length - 1]);
  });
}

// ── 豁免清单（读规范文件，不在脚本里硬编码）──────────────────────────────
function loadExceptions() {
  if (!existsSync(SPEC_FILE)) return { ok: false, rows: [], error: `规范文件缺失：${SPEC_FILE}` };
  const text = readFileSync(SPEC_FILE, 'utf8');
  const headingMatch = /^##+[^\n]*例外清单[^\n]*$/m.exec(text);
  if (!headingMatch) return { ok: false, rows: [], error: '规范文件里没有「## 例外清单」节' };
  const start = headingMatch.index;
  const tail = text.slice(start);
  const rows = [];
  let inTable = false;
  for (const line of tail.split('\n')) {
    const t = line.trim();
    if (t.startsWith('## ') && !/例外清单/.test(t)) break;
    if (!inTable) {
      if (/^\|/.test(t) && /判据/.test(t)) inTable = true;
      continue;
    }
    if (!/^\|/.test(t)) continue;
    const cells = t.replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
    if (!cells.length || /^-+:?$/.test(cells[0]) || cells.every((c) => /^:?-+:?$/.test(c))) continue;
    const [selector, code, reason] = cells;
    if (!selector) continue;
    rows.push({ selector, code: code || '', reason: reason || '', raw: t });
  }
  const bad = rows.filter((r) => !r.reason || r.reason.length < 4);
  return { ok: bad.length === 0, rows, error: bad.length ? `例外清单有空理由行：${bad.map((b) => b.selector).join(' / ')}` : '' };
}

/** 豁免匹配：选择器片段包含关系（豁免表写「片段」，命中即放行）。 */
function exemptedFor(exceptions, file, selector) {
  const relPath = rel(file).replace(/\\/g, '/');
  return exceptions.find((r) => {
    const spec = r.selector;
    const [rawSel, rawFile] = spec.includes(' ⇢ ') ? spec.split(' ⇢ ') : [spec, ''];
    // 规范表里用 markdown 反引号包选择器（人读友好）——判据一律去反引号比较
    const selPart = rawSel.replace(/`/g, '').trim();
    const filePart = rawFile.replace(/`/g, '').trim();
    if (filePart && !relPath.includes(filePart)) return false;
    return selector.replace(/`/g, '').includes(selPart);
  });
}

// ── 扫描 ────────────────────────────────────────────────────────────────
const violations = [];
const inventory = [];

function addViolation(code, file, line, selector, detail, prop) {
  violations.push({ code, file: rel(file), line, selector: selector.trim().replace(/\s+/g, ' '), detail, prop });
}

const cssFiles = existsSync(CSS_DIR)
  ? readdirSync(CSS_DIR).filter((f) => f.endsWith('.css')).sort().map((f) => join(CSS_DIR, f))
  : [];

if (!cssFiles.length) {
  console.error(`input-surface: css dir not found: ${CSS_DIR}`);
  process.exit(2);
}

const exceptions = loadExceptions();

// I1 令牌单一源
for (const file of cssFiles) {
  const src = stripCssComments(readFileSync(file, 'utf8'));
  const lineOf = (idx) => src.slice(0, idx).split('\n').length;
  const re = /--nf-field-[a-z0-9-]+\s*:/gi;
  let m;
  while ((m = re.exec(src))) {
    if (file === TOKENS) continue;
    addViolation('I1', file, lineOf(m.index), m[0].replace(/\s*:$/, ''), '`--nf-field-*` 令牌只允许在 stand-tokens.css 定义', m[0].replace(/\s*:$/, ''));
  }
}

for (const file of cssFiles) {
  const isStandard = file === STANDARD_FILE;
  const src = readFileSync(file, 'utf8');
  const text = stripCssComments(src);
  for (const rule of walkRules(src)) {
    const sels = rule.selector.split(',').map((s) => s.trim()).filter(Boolean);
    const faces = sels.filter(isInputFace);
    if (!faces.length) continue;
    const decls = parseDecls(rule.body);
    const paint = decls.filter((d) => PAINT_PROPS.has(d.prop));
    // 非输入面的选择器混在同一条规则里 ⇒ 不判造型（那是别的东西的面）
    const mixed = sels.length !== faces.length;
    if (INVENTORY && paint.length) {
      inventory.push({
        file: rel(file), line: rule.line,
        selector: rule.selector.trim().replace(/\s+/g, ' '),
        paint: paint.map((d) => `${d.prop}: ${d.value}`),
      });
    }
    if (mixed || !paint.length) continue;

    if (isStandard) {
      // I3 数值单一源：造型值必须走 --nf-field-* 令牌
      for (const d of paint) {
        if (d.prop === 'outline-offset' || d.prop === 'outline-style' || d.prop === 'border-style') continue;
        if (!/var\(--nf-field-/.test(d.value) && d.value.trim() !== 'none' && d.value.trim() !== '0' && !/^0(px)?$/.test(d.value.trim())) {
          addViolation('I3', file, rule.line, rule.selector, `${d.prop}: ${d.value} —— 标准文件里的造型值必须取 var(--nf-field-*)`, d.prop);
        }
      }
    } else {
      const open = exemptedFor(exceptions.rows, file, rule.selector);
      if (!open) {
        addViolation('I2', file, rule.line, rule.selector,
          `输入面造型声明（${paint.map((d) => d.prop).join('/')}）—— 只允许 css/inputSurface.css`, paint[0].prop);
      }
    }

    // I4 蓝不进输入面
    for (const d of decls) {
      const hit = BLUE_TOKENS.find((t) => d.value.includes(t)) || (BLUE_LITERAL.test(d.value) ? '蓝字面量' : '');
      if (!hit) continue;
      const keyboardOnly = isStandard && /data-nf-modality\s*=\s*["']?keyboard/.test(
        text.slice(Math.max(0, text.indexOf(rule.selector) - 400), text.indexOf(rule.selector) + rule.selector.length),
      );
      if (keyboardOnly) continue;
      addViolation('I4', file, rule.line, rule.selector, `${d.prop}: ${d.value} —— 蓝族令牌/字面量不许进输入面（${hit}）`, d.prop);
    }
  }
}

// I5 JS 注入面
function* walkJs(dir) {
  if (!existsSync(dir)) return;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) yield* walkJs(p);
    else if (entry.name.endsWith('.js')) yield p;
  }
}
const JS_STYLE_PAINT = /([A-Za-z_$][\w$]*)\.style\.(border|borderColor|borderRadius|background|backgroundColor|boxShadow|outline)\s*=/g;
for (const file of walkJs(JS_DIR)) {
  const src = readFileSync(file, 'utf8');
  const lines = src.split('\n');
  let m;
  JS_STYLE_PAINT.lastIndex = 0;
  while ((m = JS_STYLE_PAINT.exec(src))) {
    // 只有「接收者名字本身就是输入件」才算（dot.style.background 之类不是）
    if (!/input|textarea|select|field/i.test(m[1])) continue;
    const line = src.slice(0, m.index).split('\n').length;
    addViolation('I5', file, line, `(${m[1]}.style.${m[2]})`,
      '内联造型赋值 —— 输入面造型只能来自 css/inputSurface.css', lines[line - 1]?.trim().slice(0, 120) || '');
  }
}

// ── 输出 ────────────────────────────────────────────────────────────────
if (JSON_OUT) {
  console.log(JSON.stringify({
    ok: violations.length === 0 && exceptions.ok,
    exceptions: { ok: exceptions.ok, count: exceptions.rows.length, error: exceptions.error },
    violations,
  }, null, 2));
  process.exit(violations.length === 0 && exceptions.ok ? 0 : 1);
}

if (INVENTORY) {
  console.log(`input-surface inventory — ${inventory.length} 条输入面造型规则\n`);
  const byFile = new Map();
  for (const row of inventory) {
    if (!byFile.has(row.file)) byFile.set(row.file, []);
    byFile.get(row.file).push(row);
  }
  for (const [f, rows] of [...byFile.entries()].sort((a, b) => b[1].length - a[1].length)) {
    console.log(`## ${f}  (${rows.length})`);
    for (const r of rows) console.log(`  ${r.file}:${r.line}  ${r.selector}\n      ${r.paint.join('; ')}`);
    console.log('');
  }
  const counts = {};
  for (const row of inventory) counts[row.file] = (counts[row.file] || 0) + 1;
  console.log(`TOTAL=${inventory.length} FILES=${Object.keys(counts).length}`);
  process.exit(0);
}

const codes = [...new Set(violations.map((v) => v.code))].sort();
if (violations.length || !exceptions.ok) {
  console.error(`input-surface: RED — ${violations.length} 违规${codes.length ? `（${codes.join('/')}）` : ''}`);
  if (!exceptions.ok) console.error(`  豁免表：${exceptions.error}`);
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
console.log(`input-surface: OK — 输入面造型单源（css/inputSurface.css），零第二套（豁免 ${exceptions.rows.length} 条已具名登记）`);
process.exit(0);
