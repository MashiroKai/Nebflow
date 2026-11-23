#!/usr/bin/env node
// check-button-style-unify.mjs — 全站按钮样式向 canvas 基准统一的静态闸
// （button-style-unify-canvas-ref 批 2026-11-22；作者令逐字：
//  「canvas的按钮设计和手机端主会话以及各面板的按钮设计风格不一样 我们以canvas的为准
//   来把其他位置的统一到这个样式 并且落检查 …canvas有阴影 会好区分按钮一点」）
//
// 基准 = canvas `.canvas-button` 族的配方：阴影 = 统一令牌 `--btn-shadow`（单源，
// design-system.css，值 = --ds-shadow-float → --cv-shadow，亮暗双档在 sapphire.css）。
// 判据（对按钮面选择器的规则块，口径同 scripts/lib/button-face.mjs）：
//
//   U1 单源    `--btn-shadow` 全仓只定义一次，且在 css/design-system.css，值 = var(--ds-shadow-float)。
//   U2 消费    静止态（无伪类的基础规则）且带实底（background 非透明）的按钮面，
//              必须声明 box-shadow: var(--btn-shadow)；无投影 / 裸投影 ⇒ 红
//              （存量见 button-style-unify-baseline.json 的 flat，只许降不许升）。
//   U3 形态    按钮面 box-shadow 只许 none 或 var(--btn-shadow)；字面量投影 ⇒ 红。
//              （与 check-flat-buttons F1 同口径的互证，判据不重复计数：此处管「消费」，
//               F1 管「禁厚度」。）
//   U4 圆角    静止态按钮面 border-radius 只许 var(--…) 令牌；字面量圆角 ⇒ 红
//              （存量 17 处见 baseline 的 radius，只许降不许升；结构各异的圆角可保留，
//               但新增字面量圆角不许）。
//
// 规范单源：design-language/references/frontend/flat-button-spec.md（2026-11-22 取代
// 「按钮面禁影」条款，见其修订注记）。改存量 ⇒ `--update-baseline`（只许在把命中降下来时用）。
//
// 用法：
//   node scripts/check-button-style-unify.mjs                    # 门禁
//   node scripts/check-button-style-unify.mjs --json             # 机器读
//   node scripts/check-button-style-unify.mjs --update-baseline  # 重锚存量（只降不升）
//   BUTTON_UNIFY_CSS_DIR=<dir> node …                            # 负对照用：扫指定目录
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, readdirSync, writeFileSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isButtonSelector, walkRules } from './lib/button-face.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const CSS_DIR = process.env.BUTTON_UNIFY_CSS_DIR
  || join(ROOT, 'src', 'main', 'resources', 'web', 'css');
const TOKEN_HOME = 'design-system.css';
const TOKEN = '--btn-shadow';
const TOKEN_VALUE = 'var(--ds-shadow-float)';
const CONSUME = `var(${TOKEN})`;
const SHADOW_OK = new Set([CONSUME, 'none']);
const BASELINE_PATH = join(ROOT, 'scripts', 'button-style-unify-baseline.json');
const ARGS = new Set(process.argv.slice(2));
const NO_FILL = /^(transparent|none|inherit|unset|initial|0|currentcolor)$/i;

if (!existsSync(CSS_DIR)) {
  console.error(`环境错误：CSS 目录缺失 ${CSS_DIR}`);
  process.exit(2);
}

const files = readdirSync(CSS_DIR).filter((f) => f.endsWith('.css')).sort();
const rel = (f) => relative(ROOT, join(CSS_DIR, f)) || f;
const findings = [];

// ── U1：令牌单源 ─────────────────────────────────────────────────────────
const defs = [];
for (const f of files) {
  const src = readFileSync(join(CSS_DIR, f), 'utf8');
  const re = new RegExp(`(^|[;{\\s])${TOKEN}\\s*:\\s*([^;}]+)`, 'gm');
  let m;
  while ((m = re.exec(src))) defs.push({ file: f, value: m[2].trim() });
}
if (defs.length !== 1) findings.push({ id: 'U1', msg: `${TOKEN} 定义数 = ${defs.length}（必须恰好 1 处）`, at: defs.map((d) => d.file).join(', ') || '-' });
else {
  if (defs[0].file !== TOKEN_HOME) findings.push({ id: 'U1', msg: `${TOKEN} 必须定义在 ${TOKEN_HOME}`, at: defs[0].file });
  if (defs[0].value !== TOKEN_VALUE) findings.push({ id: 'U1', msg: `${TOKEN} 值须为 ${TOKEN_VALUE}（canvas 基准）`, at: `${defs[0].file} = ${defs[0].value}` });
}

// ── U2/U3/U4：按钮面消费（逐选择器分量判定；同一分量跨规则合并）──────────
// 为什么逐分量：逗号列表里只要有一个分量是按钮，lib 的整列判定会把非按钮分量
// （输入框、容器）一并拉进来；按钮面的判定必须落在分量本身。
// 为什么跨规则合并：canvas 族的面色在一条规则、投影在「Canvas 单钮共享面」另一条，
// 同一分量只要任一规则消费了令牌即视为已统一（与 CSS 级联的实际呈现一致）。
/** 顶层逗号切分（括号内的逗号属于 :is()/:not() 等伪类参数，不切）。 */
function splitTop(sel) {
  const out = []; let depth = 0; let cur = '';
  for (const ch of sel) {
    if (ch === '(' || ch === '[') depth++;
    else if (ch === ')' || ch === ']') depth = Math.max(0, depth - 1);
    if (ch === ',' && depth === 0) { out.push(cur); cur = ''; } else cur += ch;
  }
  out.push(cur);
  return out;
}
/** 分量的「族名」= 末段类/ID（canvas 投影在 `.canvas-button.X` 规则里，族名同为 X）。 */
const familyOf = (p) => (p.match(/[.#][\w-]+/g) || []).slice(-1)[0] || p;
const parts = new Map(); // key -> { file, line, sel, solid, token, shadowBad:[], radius:null, base }
for (const f of files) {
  const src = readFileSync(join(CSS_DIR, f), 'utf8');
  for (const r of walkRules(src)) {
    for (const raw of splitTop(r.selector)) {
      const p = raw.trim().replace(/\s+/g, ' ');
      if (!p || !isButtonSelector(p)) continue;
      const k = parts.get(p) || { file: f, line: r.line, sel: p, base: !/[:&\[]/.test(p), solid: false, token: false, shadowBad: [], radius: null };
      const decls = r.decls;
      for (const d of decls) {
        if (d.prop === 'box-shadow') {
          if (d.value.trim() === CONSUME) k.token = true;
          else if (!SHADOW_OK.has(d.value.trim())) k.shadowBad.push({ value: d.value.slice(0, 80), line: d.line });
        }
        if (/^background(-color)?$/.test(d.prop) && !NO_FILL.test(d.value.replace(/!important/i, '').trim())) k.solid = true;
        if (d.prop === 'border-radius' && !/var\(/.test(d.value)) k.radius = { value: d.value.replace(/\s+/g, ' '), line: d.line };
      }
      parts.set(p, k);
    }
  }
}
const flatKeys = [];   // U2 存量 key
const radiusKeys = []; // U4 存量 key
const consumedFamilies = new Set([...parts.values()].filter((k) => k.token).map((k) => familyOf(k.sel)));
for (const [p, k] of parts) {
  const at = `${rel(k.file)}:${k.line} ${p}`;
  for (const s of k.shadowBad) findings.push({ id: 'U3', msg: `按钮面字面量投影：${s.value}`, at: `${rel(k.file)}:${s.line} ${p}` });
  if (k.base && k.solid && !k.token && !consumedFamilies.has(familyOf(p))) {
    const key = `${p}`;
    flatKeys.push(key);
    findings.push({ id: 'U2', msg: '实底按钮面未消费统一阴影令牌 var(--btn-shadow)', at, key });
  }
  // 圆角：形状值（50% 圆 / 999px 药丸 / 0）不算样式漂移，只抓 px 字面量（8px 之类）。
  if (k.base && k.radius && /\b(?!999px\b|0\b)\d+(\.\d+)?px\b/.test(k.radius.value)) {
    const key = `${p}::${k.radius.value}`;
    radiusKeys.push(key);
    findings.push({ id: 'U4', msg: `按钮面字面量圆角：${k.radius.value}`, at: `${rel(k.file)}:${k.radius.line} ${p}`, key });
  }
}

// ── 存量基线（只许降不许升）────────────────────────────────────────────
const baseline = existsSync(BASELINE_PATH) ? JSON.parse(readFileSync(BASELINE_PATH, 'utf8')) : { U2: [], U4: [] };
if (ARGS.has('--update-baseline')) {
  writeFileSync(BASELINE_PATH, JSON.stringify({
    _comment: 'check-button-style-unify.mjs 存量基线（button-style-unify-canvas-ref 批 2026-11-22）：U2 = 判定保留的非统一阴影实底静止面，U4 = 判定保留的字面量圆角静止面。只许降不许升；降下来后 --update-baseline 重锚。',
    generatedAt: new Date().toISOString(),
    U2: [...new Set(flatKeys)].sort(),
    U4: [...new Set(radiusKeys)].sort(),
  }, null, 2) + '\n');
  console.log(`baseline 已重锚：U2 ${new Set(flatKeys).size} · U4 ${new Set(radiusKeys).size}`);
  process.exit(0);
}
const allowU2 = new Set(baseline.U2 || []);
const allowU4 = new Set(baseline.U4 || []);
const violations = findings.filter((x) => {
  if (x.id === 'U2') return !allowU2.has(x.key);
  if (x.id === 'U4') return !allowU4.has(x.key);
  return true;
});
const staleBaseline = [...allowU2].filter((k) => !flatKeys.includes(k)).length + [...allowU4].filter((k) => !radiusKeys.includes(k)).length;

if (ARGS.has('--json')) {
  console.log(JSON.stringify({ files: files.length, baselineU2: allowU2.size, baselineU4: allowU4.size, violations, staleBaseline }, null, 2));
  process.exit(violations.length ? 1 : 0);
}

if (violations.length) {
  console.error('✗ button-style-unify gate FAILED');
  console.error(`  违规 ${violations.length}（U1 ${violations.filter((v) => v.id === 'U1').length} · U2 ${violations.filter((v) => v.id === 'U2').length} · U3 ${violations.filter((v) => v.id === 'U3').length} · U4 ${violations.filter((v) => v.id === 'U4').length}）`);
  for (const v of violations.slice(0, 200)) console.error(`  [${v.id}] ${v.at}\n      ${v.msg}`);
  console.error('\n处置：静止实底按钮面加 `box-shadow: var(--btn-shadow);`（不要自写阴影字面量）；圆角走令牌。');
  console.error('确需保留的存量 ⇒ 写进基线并在批报告点名理由；新增存量不许。');
  process.exit(1);
}
if (staleBaseline) console.log(`note: 基线里有 ${staleBaseline} 条已不再命中，可用 --update-baseline 降档`);
console.log(`✓ button-style-unify gate passed — ${files.length} 个 CSS；U2 存量 ${allowU2.size} · U4 存量 ${allowU4.size}；0 违规`);
process.exit(0);
