#!/usr/bin/env node
// check-glass-unify.mjs — 「毛玻璃只有一份」静态哨兵
// （glass-unify 批 2026-11-05；作者令逐字：
//  「我们的毛玻璃效果没有统一，我们stand只允许一个毛玻璃效果……把毛玻璃效果作为
//    一个唯一的标准和术语，放到插件和前端组件。这样我说毛玻璃效果，就能找到那个
//    统一的设计标准。」）
//
// Why a gate：本批之前台账是 128 个含 backdrop-filter 的规则块、散在 33 个文件里，
// 配方有 30 种不同写法（blur 8/10/12/14/16/20/24/26px、saturate 1.1/1.15/1.2/
// 1.3/1.4/1.6/1.8，底色有 .55/.60/.68/.72/.88/.92 六档白度）。作者看到的
// 「图1 半透明能看见底色 / 图2 不透明」就是这个分裂的末端表现——同一句「毛玻璃」
// 在哪个文件里长成什么样，取决于写那一面的人当天的手感。手工审查抓不住 128 处，
// 所以把「配方只有一处」变成机器判据。
//
// ── 五条判据 ────────────────────────────────────────────────────────────
//  G1 双源背衬：**css/glass.css 之外**任何文件出现非 `none` 的 backdrop-filter /
//     -webkit-backdrop-filter ⇒ 红。`none` 放行：那不是第二套毛玻璃，是**摘除**
//     毛玻璃（降级档 @supports / 无 backdrop 引擎回落实底），性质相反不算违规。
//  G2 配方走样：glass.css 内部，每条非 none 的 backdrop-filter 值必须是
//     `var(--nf-glass-filter)`（幕布类 .nf-glass-scrim 允许 `var(--nf-glass-scrim-blur)`）
//     ⇒ 否则红。数字重新长回文件里 = 配方又开始分裂。
//  G3 标准被稀释：stand-tokens.css 的 `--nf-glass-bg` 解析出的 **α 必须 ≥ 0.90**
//     （亮/暗/媒体查询三档都查）。这是本批真正的核心判据：把 α 从 .97 调回 .55
//     就能让「图1」平静地复活，而其它四条都不会红。
//  G4 令牌双源：`--nf-glass-*` 只许在 css/stand-tokens.css 里**定义**（`x: 字面量`）；
//     其它文件出现 `--nf-glass-*: <字面量>` ⇒ 红（引用 `var(--nf-glass-*)` 不限）。
//  G5 绕过工具类：canvas ask 的气泡与输入条必须挂 `.nf-glass`
//     （作者令 2026-11-05 16:50「这三处全部吃唯一毛玻璃标准，不许为 ask 气泡
//      另造一套等效样式」）⇒ js/askDock.js 里造这两个节点时缺类即红。
//
// 判据与术语的**规范侧**（人读条文、例外登记）在
//   src/main/resources/seed/plugins/design-language/skills/design-language/
//   references/glass-spec.md
// 本脚本只做它机械可判的那部分；改判据要先改规范。
//
// 用法：
//   node scripts/check-glass-unify.mjs            # 门禁（红/绿）
//   node scripts/check-glass-unify.mjs --json     # 机器读
//   node scripts/check-glass-unify.mjs --inventory# 盘点（按文件/形态统计）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

// `--root=<dir>` 允许把判据跑在**另一个检出**上（改前/改后对照实证用：
//   git archive <main-tip> | tar -x -C /tmp/before
//   node scripts/check-glass-unify.mjs --root=/tmp/before
// 判据本身与目录无关 —— 同一份脚本、同一套红绿，才能证明「改前多少点、改后零」）。
const ROOT_OVERRIDE = (process.argv.slice(2).find((a) => a.startsWith('--root=')) || '').slice(7);
const ROOT = ROOT_OVERRIDE
  ? fileURLToPath(new URL(`file://${ROOT_OVERRIDE}/`))
  : fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const CSS_DIR = join(WEB, 'css');
const JS_DIR = join(WEB, 'js');
const TOKENS = join(CSS_DIR, 'stand-tokens.css');
const SINGLE_SOURCE = join(CSS_DIR, 'glass.css');
const ASK_DOCK = join(JS_DIR, 'askDock.js');

/** 唯一合法配方值（G2 白名单）。 */
const ALLOWED_FILTER_VALUES = new Set([
  'var(--nf-glass-filter)',
  'var(--nf-glass-scrim-blur)',
]);

/** G3：玻璃底不透明度下限。低于它 = 作者实拍「图1」那种能看见底色的形态。 */
const MIN_GLASS_ALPHA = 0.9;

const rel = (p) => relative(ROOT, p);

// ── 解析工具 ─────────────────────────────────────────────────────────────

/** 去注释但保留行号（等量换行）。 */
function stripCssComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/**
 * 极简 CSS 块遍历器 → { selector, decls:[{prop,value,line}], line, atStack }。
 * 只关心规则块；at-rule 的 prelude 压栈，用于把命中归到 `@media` 语境。
 */
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
      if (prelude.startsWith('@')) {
        stack.push(prelude.replace(/\s+/g, ' '));
        i++;
        selStart = i;
        continue;
      }
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
    if (ch === '}') {
      if (stack.length) stack.pop();
      i++;
      selStart = i;
      continue;
    }
    i++;
  }
}

/** 颜色字面量 → α。支持 #rrggbbaa / rgba() / rgb(a b c / a)。无法判定返回 null。 */
function alphaOf(value) {
  const v = String(value).trim();
  let m = v.match(/^#([0-9a-f]{8})$/i);
  if (m) return parseInt(m[1].slice(6, 8), 16) / 255;
  m = v.match(/^#([0-9a-f]{6})$/i);
  if (m) return 1;
  m = v.match(/^#([0-9a-f]{3})$/i);
  if (m) return 1;
  m = v.match(/^rgba?\(([^)]*)\)$/i);
  if (m) {
    const parts = m[1].split(/[,/]/).map((s) => s.trim()).filter(Boolean);
    if (parts.length >= 4) {
      const a = parts[3].endsWith('%') ? parseFloat(parts[3]) / 100 : parseFloat(parts[3]);
      return Number.isFinite(a) ? a : null;
    }
    return 1;
  }
  return null;
}

/** 去 JS 行注释与块注释（尽量保住行号）。 */
function stripJsComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length));
}

// ── 扫描 ────────────────────────────────────────────────────────────────

const violations = [];
const stats = { cssFiles: 0, jsFiles: 0, blocks: 0, members: 0, noneOptOuts: 0, files: new Map() };
const add = (v) => violations.push(v);

// G1/G2 —— CSS 全量
for (const f of readdirSync(CSS_DIR).filter((x) => x.endsWith('.css')).sort()) {
  const path = join(CSS_DIR, f);
  const src = readFileSync(path, 'utf8');
  stats.cssFiles++;
  const isSource = path === SINGLE_SOURCE;
  for (const rule of walkRules(src)) {
    for (const d of rule.decls) {
      if (!/^(-webkit-)?backdrop-filter$/.test(d.prop)) continue;
      const value = d.value.replace(/\s*!important\s*$/i, '').trim();
      if (/^none$/i.test(value)) {
        stats.noneOptOuts++;
        continue;
      }
      stats.blocks++;
      stats.files.set(f, (stats.files.get(f) || 0) + 1);
      if (!isSource) {
        add({
          rule: 'G1',
          file: rel(path),
          line: d.line,
          selector: rule.selector,
          detail: `${d.prop}: ${value}`,
          why: '毛玻璃双源：glass.css 之外不许出现 backdrop-filter',
        });
        continue;
      }
      if (!ALLOWED_FILTER_VALUES.has(value)) {
        add({
          rule: 'G2',
          file: rel(path),
          line: d.line,
          selector: rule.selector,
          detail: `${d.prop}: ${value}`,
          why: '配方走样：唯一合法值是 var(--nf-glass-filter)',
        });
      }
    }
  }
}

// G1 —— JS / HTML 内联样式面（只认「属性名: 值」形态；`transition: … backdrop-filter …`
// 与 CSS.supports('backdrop-filter', …) 都不是声明，正则天然不命中）
for (const dir of [JS_DIR, WEB]) {
  const files = dir === WEB
    ? readdirSync(WEB).filter((x) => x.endsWith('.html')).map((x) => join(WEB, x))
    : readdirSync(JS_DIR).filter((x) => x.endsWith('.js')).map((x) => join(JS_DIR, x));
  for (const path of files) {
    const raw = readFileSync(path, 'utf8');
    if (!raw.includes('backdrop-filter')) continue;
    stats.jsFiles++;
    const text = stripJsComments(raw);
    const lines = text.split('\n');
    for (let n = 0; n < lines.length; n++) {
      const re = /(?:^|[^-\w])(-webkit-)?backdrop-filter\s*:\s*([^;'"}]*)/g;
      let m;
      while ((m = re.exec(lines[n])) !== null) {
        const value = m[2].trim();
        if (!value) continue;
        if (/^none\b/i.test(value)) {
          stats.noneOptOuts++;
          continue;
        }
        stats.blocks++;
        add({
          rule: 'G1',
          file: rel(path),
          line: n + 1,
          selector: '(内联样式串)',
          detail: `backdrop-filter: ${value.slice(0, 80)}`,
          why: '毛玻璃双源：JS 注入/内联样式里也不许出现 backdrop-filter',
        });
      }
    }
  }
}

// G3 —— 标准本体不许被稀释
const tokenSrc = readFileSync(TOKENS, 'utf8');
const bgDecls = [];
for (const rule of walkRules(tokenSrc)) {
  for (const d of rule.decls) {
    if (d.prop !== '--nf-glass-bg') continue;
    const value = d.value.trim();
    if (/^var\(/.test(value)) continue; // 别名（同一个值，非第二份定义）
    bgDecls.push({ value, line: d.line, atStack: rule.atStack, selector: rule.selector });
  }
}
if (!bgDecls.length) {
  add({
    rule: 'G3',
    file: rel(TOKENS),
    line: 0,
    selector: ':root',
    detail: '--nf-glass-bg 未定义',
    why: '毛玻璃标准本体缺失：设计令牌单一源里找不到 --nf-glass-bg',
  });
}
for (const d of bgDecls) {
  const a = alphaOf(d.value);
  if (a === null) {
    add({
      rule: 'G3',
      file: rel(TOKENS),
      line: d.line,
      selector: d.selector,
      detail: `--nf-glass-bg: ${d.value}`,
      why: '无法解析不透明度（请用 #rrggbbaa 或 rgba()）',
    });
    continue;
  }
  if (a < MIN_GLASS_ALPHA) {
    add({
      rule: 'G3',
      file: rel(TOKENS),
      line: d.line,
      selector: d.selector,
      detail: `--nf-glass-bg: ${d.value}  ⇒ α=${a.toFixed(3)} < ${MIN_GLASS_ALPHA}`,
      why: '标准被稀释成半透明（= 作者实拍图1 那种能看见底色的形态）',
    });
  }
}

// G4 —— 令牌只许有一处出生地
for (const f of readdirSync(CSS_DIR).filter((x) => x.endsWith('.css')).sort()) {
  const path = join(CSS_DIR, f);
  if (path === TOKENS) continue;
  for (const rule of walkRules(readFileSync(path, 'utf8'))) {
    for (const d of rule.decls) {
      if (!/^--nf-glass-/.test(d.prop)) continue;
      if (/^var\(/.test(d.value)) continue;
      add({
        rule: 'G4',
        file: rel(path),
        line: d.line,
        selector: rule.selector,
        detail: `${d.prop}: ${d.value}`,
        why: '令牌双源：--nf-glass-* 只许在 stand-tokens.css 里定义',
      });
    }
  }
}

// G5 —— canvas ask 的气泡/输入条必须走 .nf-glass
if (!existsSync(ASK_DOCK)) {
  add({
    rule: 'G5',
    file: rel(ASK_DOCK),
    line: 0,
    selector: '-',
    detail: 'js/askDock.js 不存在',
    why: '环境异常：ask 面无法核对',
  });
} else {
  const askSrc = readFileSync(ASK_DOCK, 'utf8');
  const checks = [
    { re: /class:\s*'ask-bub nf-glass/, label: 'ask 气泡（ask-bub）', hint: "h('div', { class: 'ask-bub nf-glass …' })" },
    { re: /class:\s*'ask-input nf-glass/, label: 'ask 输入条（ask-input）', hint: "h('div', { class: 'ask-input nf-glass' })" },
  ];
  for (const c of checks) {
    if (!c.re.test(askSrc)) {
      add({
        rule: 'G5',
        file: rel(ASK_DOCK),
        line: 0,
        selector: c.label,
        detail: `缺少 .nf-glass（应形如 ${c.hint}）`,
        why: '绕过唯一毛玻璃工具类：canvas ask 面必须吃同一份标准',
      });
    }
  }
}

// 成员台账（信息面，不判红）
const members = (() => {
  const src = readFileSync(SINGLE_SOURCE, 'utf8');
  let n = 0;
  for (const rule of walkRules(src)) {
    if (rule.decls.some((d) => /^(-webkit-)?backdrop-filter$/.test(d.prop) && !/^none$/i.test(d.value))) n++;
  }
  return n;
})();
stats.members = members;

// ── 输出 ────────────────────────────────────────────────────────────────

const argv = new Set(process.argv.slice(2).filter((a) => !a.startsWith('--root=')));

if (argv.has('--json')) {
  console.log(JSON.stringify({
    ok: violations.length === 0,
    singleSource: rel(SINGLE_SOURCE),
    tokenSource: rel(TOKENS),
    minGlassAlpha: MIN_GLASS_ALPHA,
    blocksWithBackdrop: stats.blocks,
    noneOptOuts: stats.noneOptOuts,
    ruleBlocksInSingleSource: members,
    cssFiles: stats.cssFiles,
    violations,
  }, null, 2));
  process.exit(violations.length ? 1 : 0);
}

if (argv.has('--inventory')) {
  console.log(`CSS 文件 ${stats.cssFiles} · 含 backdrop-filter 的规则块 ${stats.blocks}（none 摘除档 ${stats.noneOptOuts}）`);
  console.log(`单一源 glass.css 内的配方块 ${members} · 违规 ${violations.length}`);
  console.log('\n── 按文件（配方块数）──');
  for (const [f, n] of [...stats.files].sort((a, b) => b[1] - a[1])) console.log(`${String(n).padStart(4)}  ${f}`);
  process.exit(0);
}

if (violations.length) {
  console.error('✗ glass-unify gate FAILED');
  console.error(`  含 backdrop-filter 的配方块 ${stats.blocks} · 单一源内 ${members} · 违规 ${violations.length}`);
  for (const v of violations) {
    console.error(`  ${v.file}:${v.line}  [${v.rule}] ${v.detail}`);
    if (v.selector && v.selector !== '-') console.error(`      ${v.selector}`);
    console.error(`      ${v.why}`);
  }
  console.error('\n处置：把这一面改成 .nf-glass（或加进 css/glass.css 的成员登记表）、');
  console.error(`        配方值只准 var(--nf-glass-filter)、玻璃底 α ≥ ${MIN_GLASS_ALPHA}。`);
  console.error('        规范条文与例外登记：design-language/references/glass-spec.md');
  process.exit(1);
}

console.log(
  `✓ glass-unify gate passed — 配方块 ${stats.blocks} 全部在单一源（${members} 条规则）、` +
  `none 摘除档 ${stats.noneOptOuts}、玻璃底 α ≥ ${MIN_GLASS_ALPHA}、ask 面走 .nf-glass`,
);
process.exit(0);
