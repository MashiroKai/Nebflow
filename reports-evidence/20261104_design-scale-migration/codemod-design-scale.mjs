#!/usr/bin/env node
// codemod-design-scale.mjs — 设计规格迁移批（2026-11-04）的机械迁移器。
//
// 铁律：**只做等值迁移** —— 裸字面量 → 解析后 computed 值**完全相同**的令牌。
// 换不到同值令牌的一律不碰（进「需作者裁定」清单，见 needs-ruling-<class>.json）。
// 因此本脚本的产物可证明零像素变化：没有任何一个声明值被取整、近似或改档。
//
// 用法：
//   node reports-evidence/20261104_design-scale-migration/codemod-design-scale.mjs --class fs     [--apply]
//   node reports-evidence/20261104_design-scale-migration/codemod-design-scale.mjs --class radius [--apply]
//   node reports-evidence/20261104_design-scale-migration/codemod-design-scale.mjs --class space  [--apply]
//
// 判据守则（每条都是「不做」的理由）：
//   · 多值圆角（`0 10px 0 8px`）= 组合形态，不是单档 ⇒ 不迁；
//   · `50%` / `0` / 语义关键字 = 形态档，不是数值档 ⇒ 不迁；
//   · 负值（`-1px`）无法写成 `var()`（需 `calc(-1 * …)`）⇒ 不迁；
//   · 值里含 `calc()` / `var()` 混合原子 ⇒ 只迁顶层裸 px 原子，括号内一律不碰；
//   · 令牌出生行（`--x: …`）本来就不算消费 ⇒ 不迁；
//   · 14px 字号是**语义具名槽**（capsule / card-body 两个不同语义同值）⇒ 只在
//     cards.css 与标题胶囊语境迁移，其余进需裁定清单。

import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const WEB = join(ROOT, 'src/main/resources/web');
const CSS_DIR = join(WEB, 'css');
const JS_DIR = join(WEB, 'js');
const OUT_DIR = fileURLToPath(new URL('./', import.meta.url));

const argv = process.argv.slice(2);
const valOf = (f) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : null; };
const CLASS = valOf('--class');
const APPLY = argv.includes('--apply');
const REPORT = valOf('--report') || join(OUT_DIR, `needs-ruling-${CLASS}.json`);

const PX_RE = /^-?\d*\.?\d+px$/;
const SEMANTIC = new Set(['inherit', 'initial', 'unset', 'revert', 'revert-layer', 'currentcolor']);

// ── 等值令牌表（令牌声明的实际值 == 字面量；design-system.css 单点定义，
//    全仓无第二声明点 ⇒ 无作用域覆写风险，已 grep 验证）──
const FS_EXACT = {
  '11px': '--ds-fs-meta', '12px': '--ds-fs-hint', '13px': '--ds-fs-body', '14.5px': '--ds-fs-bubble',
  '15px': '--ds-fs-title', '16px': '--ds-fs-mobile-callout', '17px': '--ds-fs-lead',
  '20px': '--ds-fs-display', '21px': '--ds-fs-card-hero', '28px': '--ds-fs-title-lg',
  '13.5px': '--ds-fs-row',
};
const RADIUS_EXACT = {
  '2px': '--ds-radius-thin', '4px': '--ds-radius-badge', '6px': '--ds-radius-chip',
  '8px': '--ds-radius-input', '10px': '--ds-radius-button', '12px': '--ds-radius-xl',
  '14px': '--ds-radius-card-md', '16px': '--ds-radius-card-lg', '18px': '--ds-radius-2xl',
  '20px': '--ds-radius-shell', '999px': '--ds-radius-pill',
};
const SPACE_EXACT = {
  '2px': '--ds-space-half', '4px': '--ds-space-1', '6px': '--ds-space-1h', '8px': '--ds-space-2',
  '10px': '--ds-space-glass-x', '12px': '--ds-space-3', '16px': '--ds-space-4', '20px': '--ds-space-5',
  '24px': '--ds-space-6', '32px': '--ds-space-8', '48px': '--ds-space-12', '64px': '--ds-space-16',
};
const SPACE_PROPS = new Set([
  'padding', 'padding-top', 'padding-right', 'padding-bottom', 'padding-left',
  'margin', 'margin-top', 'margin-right', 'margin-bottom', 'margin-left',
  'gap', 'row-gap', 'column-gap',
]);
/** 12px 双档（btn/hint 同值）：按语境的「钮性」挑语义，值恒等 ⇒ 不影响像素。 */
const BUTTON_HINT = /btn|button|send|primary|submit|action|cta/i;

/** 顶层原子切分（括号内不切）——`calc(1px + 2px)` 是**一个**原子，永不被改写。 */
function topAtoms(value) {
  const out = [];
  let depth = 0, cur = '';
  for (const ch of value) {
    if (ch === '(') depth += 1;
    if (ch === ')') depth -= 1;
    if (/\s/.test(ch) && depth === 0) { if (cur) out.push(cur); cur = ''; } else cur += ch;
  }
  if (cur) out.push(cur);
  return out;
}

/** 注释遮罩（等长空格，行号/列位不变）。 */
function maskComments(src) {
  const out = src.split('');
  let i = 0, inComment = false;
  while (i < src.length) {
    if (!inComment && src[i] === '/' && src[i + 1] === '*') { inComment = true; out[i] = out[i + 1] = ' '; i += 2; continue; }
    if (inComment) {
      if (src[i] === '*' && src[i + 1] === '/') { out[i] = out[i + 1] = ' '; i += 2; inComment = false; continue; }
      if (src[i] !== '\n') out[i] = ' ';
    }
    i += 1;
  }
  return out.join('');
}

function walk(dir, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, acc);
    else if (['.css', '.js', '.mjs'].includes(extname(p))) acc.push(p);
  }
  return acc;
}

const files = [...walk(CSS_DIR), ...walk(JS_DIR)];
const isTokenDecl = (linePrefix) => /^\s*--[a-z0-9-]+\s*:/i.test(linePrefix);

const stats = { migrated: [], perToken: {} };
const needsRuling = [];

for (const abs of files) {
  const rel = relative(WEB, abs).replaceAll('\\', '/');
  const isCss = extname(abs) === '.css';
  const raw = readFileSync(abs, 'utf8');
  const masked = maskComments(raw);
  const rawLines = raw.split('\n');
  const maskedLines = masked.split('\n');
  let changed = 0;
  let selector = '';
  const newLines = rawLines.map((rawLine, idx) => {
    const mLine = maskedLines[idx];
    if (isCss) {
      const sel = mLine.match(/^\s*([^{}@/][^{}]*)\{/);
      if (sel) selector = sel[1].trim();
    }
    if (isCss && isTokenDecl(mLine)) return rawLine;

    let line = rawLine;
    const declRe = /(^|[;{])(\s*)([a-zA-Z-]+)(\s*:\s*)([^;{}]+)/g;
    let m;
    const edits = [];
    while ((m = declRe.exec(mLine)) !== null) {
      const prop = m[3].toLowerCase();
      const valueStart = m.index + m[1].length + m[2].length + m[3].length + m[4].length;
      const valueText = m[5];
      let replacement = null;
      let why = null;
      let recordVal = null;   // 需裁定表里的「值」列：档外原子优先，便于逐值归并

      // 「需裁定」只装**真问题**：仍含裸 px 且无同值令牌的原子。已是 var()/calc()/
      // em/%/关键字的声明＝已合规或非本批对象，静默放行（否则清单被噪声淹没）。
      if (CLASS === 'fs' && prop === 'font-size') {
        const atoms = topAtoms(valueText.replace(/!important$/i, '').trim());
        if (atoms.length === 1 && PX_RE.test(atoms[0])) {
          const v = atoms[0];
          if (v === '14px') {
            const semanticSlot = /cards\.css$/.test(rel) ? '--ds-fs-card-body'
              : /capsule/i.test(selector) ? '--ds-fs-capsule' : null;
            if (semanticSlot) replacement = `var(${semanticSlot})`;
            else why = '14px 是语义具名槽（capsule/card-body 同值异义），本处语境不明确';
          } else if (FS_EXACT[v]) {
            let tok = FS_EXACT[v];
            if (v === '12px') tok = (BUTTON_HINT.test(selector) || BUTTON_HINT.test(line.slice(0, 90))) ? '--ds-fs-btn' : '--ds-fs-hint';
            if (v === '15px' && /mobileLayer\.css$/.test(rel)) tok = '--ds-fs-mobile-sub';
            replacement = `var(${tok})`;
          } else why = `字号 ${v} 无同值令牌（改档即改像素）`;
        } else {
          const leftovers = atoms.filter((a) => PX_RE.test(a) && !FS_EXACT[a]);
          if (leftovers.length) { why = `字号档外值 ${[...new Set(leftovers)].join('/')}（无同值令牌）`; recordVal = leftovers.join(' '); }
        }
      } else if (CLASS === 'radius' && prop === 'border-radius') {
        const atoms = topAtoms(valueText.replace(/!important$/i, '').trim());
        if (atoms.length === 1) {
          const v = atoms[0];
          if (RADIUS_EXACT[v]) {
            let tok = RADIUS_EXACT[v];
            if (v === '10px' && /card/i.test(selector)) tok = '--ds-radius-card-sm';
            replacement = `var(${tok})`;
          } else if (v === '0' || v.endsWith('%') || SEMANTIC.has(v.toLowerCase()) || /var\(|calc\(/.test(v)) { /* 形态档：不迁 */ }
          else why = `圆角 ${v} 无同值令牌（改档即改像素）`;
        } else if (atoms.length > 1) why = `圆角组合值（${valueText.trim()}）—— 组合形态无人裁定，保字面量`;
      } else if (CLASS === 'space' && SPACE_PROPS.has(prop)) {
        const atoms = topAtoms(valueText.replace(/!important$/i, '').trim());
        const mapped = atoms.map((a) => (SPACE_EXACT[a] ? SPACE_EXACT[a] : a));
        if (mapped.some((x, i) => x !== atoms[i])) {
          replacement = valueText.replace(valueText.trim(), mapped.map((x) => (x.startsWith('--') ? `var(${x})` : x)).join(' '));
          for (const a of atoms) if (SPACE_EXACT[a]) stats.perToken[SPACE_EXACT[a]] = (stats.perToken[SPACE_EXACT[a]] || 0) + 1;
        }
        const leftovers = atoms.filter((a) => PX_RE.test(a) && !SPACE_EXACT[a]);
        if (leftovers.length) { why = `间距档外值 ${[...new Set(leftovers)].join('/')}（4px 栅格 + 两例外档均无同值令牌）`; recordVal = leftovers.join(' '); }
      }

      if (replacement !== null) {
        edits.push({ start: valueStart, end: valueStart + valueText.length, text: replacement });
        if (CLASS !== 'space') {
          const tok = replacement.match(/--[a-z0-9-]+/)[0];
          stats.perToken[tok] = (stats.perToken[tok] || 0) + 1;
        }
        stats.migrated.push({ file: rel, line: idx + 1, prop, from: valueText.trim(), to: replacement.trim() });
      } else if (why) {
        needsRuling.push({ file: rel, line: idx + 1, prop, value: recordVal || valueText.trim(), decl: valueText.trim(), why });
      }
    }
    for (const e of edits.reverse()) line = line.slice(0, e.start) + e.text + line.slice(e.end);
    if (line !== rawLine) changed += 1;
    return line;
  });
  if (APPLY && changed) writeFileSync(abs, newLines.join('\n'));
}

const perFile = {};
for (const r of stats.migrated) perFile[r.file] = (perFile[r.file] || 0) + 1;
writeFileSync(REPORT, JSON.stringify({ class: CLASS, applied: APPLY, totalMigrated: stats.migrated.length, perToken: stats.perToken, perFile, needsRuling }, null, 2) + '\n');

console.log(`${APPLY ? '应用' : '试验'} class=${CLASS}：迁移 ${stats.migrated.length} 处 / 需裁定 ${needsRuling.length} 处`);
console.log('  逐令牌：' + Object.entries(stats.perToken).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}×${v}`).join('  '));
console.log('  逐文件：' + Object.entries(perFile).sort((a, b) => b[1] - a[1]).slice(0, 14).map(([k, v]) => `${k}×${v}`).join('  '));
if (needsRuling.length) {
  const byVal = {};
  for (const n of needsRuling) byVal[n.value] = (byVal[n.value] || 0) + 1;
  console.log('  需裁定值：' + Object.entries(byVal).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}×${v}`).join('  '));
}
console.log(`  明细 → ${relative(ROOT, REPORT)}`);
