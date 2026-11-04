#!/usr/bin/env node
// measure-tokens.mjs — 设计规格迁移批（2026-11-04）的实测尺，四类裸字面量逐值直方图。
//
// 判据口径（与 scripts/check-design-tokens.mjs 对齐，但**更细**）：
//   · check-design-tokens 只抓「<11px 字号」与「档外圆角」，且不对间距计数；
//     本批 525 处的迁移量靠它量不出来 ⇒ 需要一份逐类逐值的直方图。
//   · 计数口径 = 声明出现次数（同一行多值按原子拆分计），注释先剔除，
//     `--x: …` 令牌出生行不计消费，`var()` / `calc()` / 语义关键字不计字面量。
//
// 用法：node reports-evidence/20261104_design-scale-migration/measure-tokens.mjs [--json]
// 输出：每类 = 值 → 次数，并按「等值可迁移（有同值令牌）」/「需作者裁定（无同值令牌）」二分。

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const argv0 = process.argv.slice(2);
const WR = argv0.includes('--web-root') ? argv0[argv0.indexOf('--web-root') + 1] : null;
/** 静态根：缺省 = 本工作树；`--web-root <dir>` 可指向别处（如纯净 HEAD 树），
 *  这样「改前 / 改后」用的是**同一把尺**，不会因工具本身改判而虚报。 */
const WEB = WR || join(ROOT, 'src/main/resources/web');
const CSS_DIR = join(WEB, 'css');
const JS_DIR = join(WEB, 'js');
const COMMENT_RE = /\/\*[\s\S]*?\*\//g;

// ── 令牌表（同值 → 令牌名）；值取自 design-system.css 实测声明行 ──────────
// 只收「裸字面量 → 令牌」解析后 **computed 值完全相同** 的映射（零像素变化的前提）。
export const FS_TOKENS = {
  '11px': '--ds-fs-meta', '12px': '--ds-fs-btn', '13px': '--ds-fs-body',
  '14px': '--ds-fs-capsule', '14.5px': '--ds-fs-bubble', '15px': '--ds-fs-title',
  '16px': '--ds-fs-mobile-callout', '17px': '--ds-fs-lead', '20px': '--ds-fs-display',
  '21px': '--ds-fs-card-hero', '28px': '--ds-fs-title-lg', '13.5px': '--ds-fs-row',
};
export const RADIUS_TOKENS = {
  '2px': '--ds-radius-thin', '4px': '--ds-radius-badge', '6px': '--ds-radius-chip',
  '8px': '--ds-radius-input', '10px': '--ds-radius-button', '12px': '--ds-radius-xl',
  '14px': '--ds-radius-card-md', '16px': '--ds-radius-card-lg', '18px': '--ds-radius-2xl',
  '20px': '--ds-radius-shell', '999px': '--ds-radius-pill',
};
export const SPACE_TOKENS = {
  '2px': '--ds-space-half', '4px': '--ds-space-1', '6px': '--ds-space-1h',
  '8px': '--ds-space-2', '10px': '--ds-space-glass-x', '12px': '--ds-space-3',
  '16px': '--ds-space-4', '20px': '--ds-space-5', '24px': '--ds-space-6',
  '32px': '--ds-space-8', '48px': '--ds-space-12', '64px': '--ds-space-16',
};
/** 形态档：不是「档外值」，迁移与否不改变观感语义（0 / 百分比 / 关键字）。 */
const FORMS = new Set(['0', '50%', '100%', 'inherit', 'initial', 'unset']);
const SEMANTIC = new Set(['inherit', 'initial', 'unset', 'revert', 'revert-layer', 'currentcolor']);

const PX_RE = /^-?[\d.]+px$/;
const SPACE_PROPS = new Set([
  'padding', 'padding-top', 'padding-right', 'padding-bottom', 'padding-left',
  'margin', 'margin-top', 'margin-right', 'margin-bottom', 'margin-left',
  'gap', 'row-gap', 'column-gap',
]);

function stripCommentsKeepLines(src) {
  return src.replace(COMMENT_RE, (m) => m.replace(/[^\n]/g, ' '));
}
function isTokenDeclLine(line) { return /^\s*--[a-z0-9-]+\s*:/i.test(line); }

function walk(dir, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, acc);
    else if (['.css', '.js', '.mjs'].includes(extname(p))) acc.push(p);
  }
  return acc;
}

/** 只取裸 px 原子（跳过 var()/calc()/关键字/百分比/em/rem）。 */
function atoms(valueText) {
  const out = [];
  const cleaned = String(valueText).replace(/\bvar\([^)]*\)/g, '').replace(/\bcalc\([^)]*\)/g, '');
  for (const t of cleaned.trim().split(/\s+/)) {
    if (PX_RE.test(t)) out.push(t);
  }
  return out;
}

/** 一个 css/js 文件里的四类统计。 */
export function measureFile(abs) {
  const isCss = extname(abs) === '.css';
  const src = stripCommentsKeepLines(readFileSync(abs, 'utf8'));
  const fs = {}, radius = {}, space = {};
  const bump = (bag, v) => { bag[v] = (bag[v] || 0) + 1; };
  src.split('\n').forEach((line) => {
    if (isCss && isTokenDeclLine(line)) return;
    const declRe = isCss
      ? /(^|[;{\s])([a-z-]+)\s*:\s*([^;}]+)/g
      : /(^|[;{,'"\s])([a-z-]+)\s*:\s*([^;}]+)/g;
    for (const decl of line.matchAll(declRe)) {
      const prop = decl[2].toLowerCase();
      let raw = decl[3].trim();
      if (!isCss) { const qi = raw.search(/['"]/); if (qi >= 0) raw = raw.slice(0, qi).trim(); }
      if (prop === 'font-size') {
        const first = raw.split(/\s+/)[0];
        if (SEMANTIC.has(first.toLowerCase())) return;
        if (PX_RE.test(first)) bump(fs, first);
      } else if (prop === 'border-radius') {
        const first = raw.split(/\s+/)[0];
        if (SEMANTIC.has(first.toLowerCase())) return;
        if (PX_RE.test(first) || first === '0' || first.endsWith('%')) bump(radius, first);
      } else if (SPACE_PROPS.has(prop)) {
        for (const a of atoms(raw)) bump(space, a);
      }
    }
  });
  return { fs, radius, space };
}

function merge(into, from) {
  for (const [k, v] of Object.entries(from)) into[k] = (into[k] || 0) + v;
  return into;
}

export function measureAll() {
  const files = [...walk(CSS_DIR), ...walk(JS_DIR)];
  const totals = { fs: {}, radius: {}, space: {} };
  const perFile = {};
  for (const abs of files) {
    const rel = relative(WEB, abs).replaceAll('\\', '/');
    const m = measureFile(abs);
    perFile[rel] = m;
    merge(totals.fs, m.fs); merge(totals.radius, m.radius); merge(totals.space, m.space);
  }
  return { totals, perFile };
}

/** 二分：等值可迁移 vs 需作者裁定；形态档单列。 */
export function split(bag, tokenMap) {
  const migratable = [], needsRuling = [], forms = [];
  for (const [v, n] of Object.entries(bag).sort((a, b) => b[1] - a[1])) {
    if (FORMS.has(v)) { forms.push({ value: v, count: n }); continue; }
    if (tokenMap[v]) migratable.push({ value: v, count: n, token: tokenMap[v] });
    else needsRuling.push({ value: v, count: n });
  }
  const sum = (a) => a.reduce((s, x) => s + x.count, 0);
  return { migratable, needsRuling, forms, migratableTotal: sum(migratable), needsRulingTotal: sum(needsRuling), formsTotal: sum(forms) };
}

const CLASSES = { fs: FS_TOKENS, radius: RADIUS_TOKENS, space: SPACE_TOKENS };

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith('measure-tokens.mjs')) {
  const { totals, perFile } = measureAll();
  const json = process.argv.includes('--json');
  const out = {};
  for (const [klass, map] of Object.entries(CLASSES)) {
    out[klass] = split(totals[klass], map);
  }
  if (json) {
    console.log(JSON.stringify({ totals, ...out, perFile }, null, 2));
  } else {
    const LABEL = { fs: '字号 font-size', radius: '圆角 border-radius', space: '间距 padding/margin/gap' };
    for (const [klass, r] of Object.entries(out)) {
      const all = r.migratableTotal + r.needsRulingTotal + r.formsTotal;
      console.log(`\n── ${LABEL[klass]}：裸字面量共 ${all}（等值可迁移 ${r.migratableTotal} / 需裁定 ${r.needsRulingTotal} / 形态档 ${r.formsTotal}）`);
      console.log(`   可迁移：${r.migratable.map(x => `${x.value}×${x.count}→${x.token}`).join('  ')}`);
      if (r.needsRuling.length) console.log(`   需裁定：${r.needsRuling.map(x => `${x.value}×${x.count}`).join('  ')}`);
      if (r.forms.length) console.log(`   形态档：${r.forms.map(x => `${x.value}×${x.count}`).join('  ')}`);
    }
  }
}
