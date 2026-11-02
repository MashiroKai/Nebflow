#!/usr/bin/env node
'use strict';
/* cssaudit.cjs — 统一按钮玻璃批：CSS 机械清点（2026-11-01）。
 *
 * 对 src/main/resources/web/css/*.css 做三件事（inventory.md 的机械底账）：
 *   ① backdrop-filter 声明清单：逐规则给出 selector、file:line、标准属性值、
 *      同块内是否 -webkit-backdrop-filter 双写（±2 行内判定同块）、none 降级标记；
 *   ② var() 引用审计：全仓 css + js + html 收集自定义属性定义（--x:），
 *      再收集 var(--x 引用；报出「引用了但无定义」的变量（捕捉拼写漂移），
 *      有 fallback 的引用单列（降级链不破，但仍是漂移信号）；
 *   ③ 类名清单：css 里定义的全部类选择器 + js/html 里的 className 挂载点，
 *      供非悬浮按钮清点交叉核对（按钮/ink/ctl 相关子集）。
 * 输出：stdout 汇总 + --out JSON（默认 ../cssaudit-report.json）。
 *
 * 用法：node cssaudit.cjs [--out <file>]
 */

const { readFileSync, readdirSync, writeFileSync } = require('node:fs');
const { join } = require('node:path');

/* tools → 批目录 → evidence → worktree 根（三层上级） */
const ROOT = join(__dirname, '..', '..', '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const CSS_DIR = join(WEB, 'css');
const argv = process.argv.slice(2);
const oi = argv.indexOf('--out');
const OUT = oi >= 0 ? argv[oi + 1] : join(__dirname, '..', 'cssaudit-report.json');

/** 极简 CSS 块切分：剥注释后按 {} 配对切规则；@media/@supports 记为 context。 */
function parseBlocks(css, file) {
  const noComment = css.replace(/\/\*[\s\S]*?\*\//g, (m) => ' '.repeat(m.length)); // 保行号
  const out = [];
  const stack = [];
  let i = 0;
  const lineAt = (pos) => css.slice(0, pos).split('\n').length;
  let buf = '';
  let bufStart = -1;
  while (i < noComment.length) {
    const ch = noComment[i];
    if (ch === '{') {
      const sel = buf.trim();
      if (!bufStart) bufStart = i;
      stack.push({ sel, startLine: lineAt(i - buf.length) });
      buf = '';
      bufStart = -1;
    } else if (ch === '}') {
      const blk = stack.pop();
      if (blk) {
        const body = blk.body || '';
        out.push({ file, selector: blk.sel, line: blk.startLine, body, context: stack.map((s) => s.sel).join(' > ') });
      }
      buf = '';
    } else {
      buf += ch;
      if (!bufStart) bufStart = i;
      if (stack.length) {
        const top = stack[stack.length - 1];
        top.body = (top.body || '') + ch;
      }
    }
    i++;
  }
  return out;
}

const cssFiles = readdirSync(CSS_DIR).filter((f) => f.endsWith('.css')).sort();
const blocks = [];
for (const f of cssFiles) {
  const css = readFileSync(join(CSS_DIR, f), 'utf8');
  blocks.push(...parseBlocks(css, f));
}

/* ① backdrop-filter 清单 */
const bdRows = [];
for (const b of blocks) {
  const lines = b.body.split('\n');
  lines.forEach((ln, idx) => {
    const m = ln.match(/^(\s*)(-webkit-)?backdrop-filter\s*:\s*([^;]+);/);
    if (!m) return;
    const isWebkit = !!m[2];
    const val = m[3].trim();
    // 同块内反向双写判定：±3 行内找另一形态
    const win = lines.slice(Math.max(0, idx - 3), idx + 4).join('\n');
    const dual = isWebkit ? /(^|\n)\s*backdrop-filter\s*:/.test(win) : /(^|\n)\s*-webkit-backdrop-filter\s*:/.test(win);
    bdRows.push({
      file: b.file, line: b.line + idx + 1, selector: b.selector.slice(0, 110), context: b.context.slice(0, 60),
      kind: isWebkit ? '-webkit-' : 'standard', value: val.slice(0, 60),
      dualWritten: dual, degradation: /none/.test(val),
    });
  });
}

/* ② var 引用审计：定义收集（css + js/html 内联 setProperty/字符串） */
const defined = new Map();
const addDef = (name, where) => { if (!defined.has(name)) defined.set(name, []); defined.get(name).push(where); };
for (const f of cssFiles) {
  const css = readFileSync(join(CSS_DIR, f), 'utf8');
  for (const m of css.matchAll(/(--[a-zA-Z][\w-]*)\s*:/g)) addDef(m[1], `css/${f}`);
}
const scanDirs = [join(WEB, 'js'), WEB];
const scanFiles = [];
const walk = (dir, depth) => {
  if (depth > 3) return;
  let ents;
  try { ents = readdirSync(dir, { withFileTypes: true }); } catch { return; }
  for (const e of ents) {
    if (e.isDirectory()) walk(join(dir, e.name), depth + 1);
    else if (/\.(c?js|mjs|html)$/.test(e.name)) scanFiles.push(join(dir, e.name));
  }
};
walk(scanDirs[0], 0);
walk(scanDirs[1], 0);
const refs = [];
for (const file of scanFiles) {
  const src = readFileSync(file, 'utf8');
  const rel = file.slice(WEB.length + 1);
  for (const m of src.matchAll(/var\((--[a-zA-Z][\w-]*)/g)) refs.push({ name: m[1], where: rel });
  for (const m of src.matchAll(/(--[a-zA-Z][\w-]*)\s*:/g)) addDef(m[1], rel); // js 内联 style/模板
  for (const m of src.matchAll(/setProperty\(\s*['"](--[\w-]+)['"]/g)) addDef(m[1], rel);
}
const undefinedRefs = {};
for (const r of refs) {
  if (!defined.has(r.name)) (undefinedRefs[r.name] ||= []).push(r.where);
}
const fallbackRefs = {};
for (const f of cssFiles) {
  const css = readFileSync(join(CSS_DIR, f), 'utf8');
  const lines = css.split('\n');
  lines.forEach((ln, i) => {
    for (const m of ln.matchAll(/var\((--[a-zA-Z][\w-]*)\s*,/g)) {
      if (!defined.has(m[1])) (fallbackRefs[m[1]] ||= []).push(`css/${f}:${i + 1}`);
    }
  });
}

/* ③ 类名清单（按钮相关子集 + 全量计数） */
const classDefs = new Set();
for (const b of blocks) {
  for (const m of b.selector.matchAll(/\.([a-zA-Z][\w-]*)/g)) classDefs.add(m[1]);
}
const BTN_RE = /btn|button|^ctl|ink|^fab|^pill|send|close|toggle|chip|action/;
const btnClasses = [...classDefs].filter((c) => BTN_RE.test(c)).sort();
const jsClassUse = {};
for (const file of scanFiles) {
  const src = readFileSync(file, 'utf8');
  const rel = file.slice(WEB.length + 1);
  for (const m of src.matchAll(/className\s*=\s*[`'"]([^`'"]+)[`'"]/g)) {
    for (const c of m[1].split(/\s+/)) (jsClassUse[c] ||= new Set()).add(rel);
  }
}

const summary = {
  files: cssFiles.length,
  backdropDecls: bdRows.length,
  backdropStandard: bdRows.filter((r) => r.kind === 'standard').length,
  backdropWebkit: bdRows.filter((r) => r.kind === '-webkit-').length,
  backdropNotDual: bdRows.filter((r) => !r.dualWritten && !r.degradation).length,
  degradationRows: bdRows.filter((r) => r.degradation).length,
  varNamesDefined: defined.size,
  varRefsTotal: refs.length,
  varRefsUndefinedNoFallback: Object.keys(undefinedRefs),
  varRefsUndefinedWithFallback: Object.keys(fallbackRefs),
  classDefsTotal: classDefs.size,
  btnLikeClasses: btnClasses,
};
writeFileSync(OUT, JSON.stringify({ summary, backdrop: bdRows, undefinedRefs, fallbackRefs, jsClassUse: Object.fromEntries(Object.entries(jsClassUse).map(([k, v]) => [k, [...v]])) }, null, 2));
console.log(JSON.stringify(summary, null, 2));
console.log(`cssaudit.cjs done → ${OUT}`);
