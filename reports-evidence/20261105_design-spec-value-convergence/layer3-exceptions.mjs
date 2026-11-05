#!/usr/bin/env node
// layer3-exceptions.mjs — 设计规格 525 处改值收敛批 · 第三层（圆角/间距例外值）
//
// 口径与第二层一致（会改像素）：每个原值有明文去向与依据；改后「例外值清零」是机械断言；
// 硬归并会弄坏形态的那一类**立具名例外槽**并写明理由（任务书点名的判据）。
//
// 这层两个关键判定：
//   ① 1px 间距 → 具名槽 --ds-space-hair（1px，值不变）。缝往小收会把每条发丝缝加倍，
//      而 1px 是「存在感为零、但把两件分开」的形态值 —— 归 2px 是密度退化，不是收敛。
//   ② 1px 圆角 → --ds-radius-thin（2px 细件例外槽），不是 badge 4：四处全是 2px 高的
//      指示条/进度条，细件几何归大档会改形态（且 2px 在 2px 高条上被 CSS 重叠约束钳到
//      同一渲染 ⇒ 像素实际上不变，见 EXCEPTIONS.md）。
//
// 用法：node layer3-exceptions.mjs [--apply|--verify]
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('./', import.meta.url));
const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const WEB = join(ROOT, 'src/main/resources/web');
const APPLY = process.argv.includes('--apply');
const VERIFY = process.argv.includes('--verify');

/** 间距：3/5/7px 的落点按「缝往小收、内距往大收」定（缝两侧都吃视觉重量，内距单侧）。 */
const SPACE_GAP = {
  '1px': '--ds-space-hair', '1.5px': '--ds-space-half', '2.5px': '--ds-space-half',
  '3px': '--ds-space-half', '5px': '--ds-space-1', '6.5px': '--ds-space-1h',
  '7px': '--ds-space-1h', '9px': '--ds-space-2', '11px': '--ds-space-3', '13px': '--ds-space-3',
  '18px': '--ds-space-4',
};
const SPACE_PAD = {
  '1px': '--ds-space-hair', '1.5px': '--ds-space-half', '2.5px': '--ds-space-half',
  '3px': '--ds-space-1', '5px': '--ds-space-1h', '6.5px': '--ds-space-1h',
  '7px': '--ds-space-2', '9px': '--ds-space-2', '11px': '--ds-space-3', '13px': '--ds-space-3',
  '18px': '--ds-space-5',
};
/** 圆角：只做**单值**（组合四值是逐角形态，见 EXCEPTIONS.md §跳过）。 */
const RADIUS = {
  '1px': '--ds-radius-thin', '3px': '--ds-radius-badge', '3.5px': '--ds-radius-badge',
  '5px': '--ds-radius-chip', '7px': '--ds-radius-input', '9px': '--ds-radius-button',
  '11px': '--ds-radius-xl', '13px': '--ds-radius-card-md', '17px': '--ds-radius-2xl',
  '9999px': '--ds-radius-pill',
};
/** 不归本层（有理由，不是漏）：14px 不是档（气泡内距组成）；≥20px 是组件几何尺寸；
 *  负值写不进 var()（需 calc(-1 * …)）；组合四值逐角形态。 */
const SKIP_SPACE = (a) => /^-/.test(a) || a === '14px' || ['22px', '26px', '28px', '30px', '34px', '36px', '38px', '40px', '42px', '56px', '58px', '72px', '19px', '15px'].includes(a);
const SPACE_PROPS = new Set(['padding', 'padding-top', 'padding-right', 'padding-bottom', 'padding-left',
  'margin', 'margin-top', 'margin-right', 'margin-bottom', 'margin-left', 'gap', 'row-gap', 'column-gap']);
const GAPISH = new Set(['gap', 'row-gap', 'column-gap']);

function walk(dir, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, acc);
    else if (['.css', '.js'].includes(extname(p))) acc.push(p);
  }
  return acc;
}
function maskComments(src) {
  const o = src.split(''); let i = 0, c = false;
  while (i < src.length) {
    if (!c && src[i] === '/' && src[i + 1] === '*') { c = true; o[i] = o[i + 1] = ' '; i += 2; continue; }
    if (c) {
      if (src[i] === '*' && src[i + 1] === '/') { o[i] = o[i + 1] = ' '; i += 2; c = false; continue; }
      if (src[i] !== '\n') o[i] = ' ';
    }
    i += 1;
  }
  return o.join('');
}
function topAtoms(v) {
  const out = []; let d = 0, cur = '';
  for (const ch of v) {
    if (ch === '(') d += 1;
    if (ch === ')') d -= 1;
    if (/\s/.test(ch) && d === 0) { if (cur) out.push(cur); cur = ''; } else cur += ch;
  }
  if (cur) out.push(cur);
  return out;
}
const PX = /^-?\d*\.?\d+px$/;

const stats = { space: {}, radius: {}, combos: 0, skipped: 0 };
const changes = [];
const residual = [];

for (const abs of walk(join(WEB, 'css')).concat(walk(join(WEB, 'js')))) {
  const rel = relative(WEB, abs).replaceAll('\\', '/');
  const raw = readFileSync(abs, 'utf8');
  const mLines = maskComments(raw).split('\n');
  const rawLines = raw.split('\n');
  const out = rawLines.map((rawLine, li) => {
    const mL = mLines[li];
    if (/^\s*--[a-z0-9-]+\s*:/.test(mL)) return rawLine;
    let line = rawLine;
    const declRe = /(^|[;{,'"\s])([a-z-]+)(\s*:\s*)([^;{}'"]+)/g;
    let m;
    const edits = [];
    while ((m = declRe.exec(mL)) !== null) {
      const prop = m[2].toLowerCase();
      const valueStart = m.index + m[1].length + m[2].length + m[3].length;
      const valueText = m[4];
      if (prop === 'border-radius') {
        const atoms = topAtoms(valueText.trim());
        if (atoms.length !== 1) { stats.combos += 1; continue; }
        const a = atoms[0];
        const tok = RADIUS[a];
        if (!tok) { continue; }
        const lead = valueText.indexOf(a);
        edits.push({ start: valueStart + lead, len: a.length, tok, kind: 'radius', from: a, prop });
      } else if (SPACE_PROPS.has(prop)) {
        const atoms = topAtoms(valueText.trim());
        const map = GAPISH.has(prop) ? SPACE_GAP : SPACE_PAD;
        let cursor = 0;
        for (const a of atoms) {
          const at = valueText.indexOf(a, cursor);
          cursor = at + a.length;
          if (!PX.test(a)) continue;
          if (SKIP_SPACE(a)) { stats.skipped += 1; continue; }
          const tok = map[a];
          if (!tok) continue;
          edits.push({ start: valueStart + at, len: a.length, tok, kind: 'space', from: a, prop });
        }
      }
    }
    for (const e of edits.reverse()) {
      if (VERIFY) { residual.push({ file: rel, line: li + 1, prop: e.prop, from: e.from, tok: e.tok }); continue; }
      line = line.slice(0, e.start) + `var(${e.tok})` + line.slice(e.start + e.len);
      stats[e.kind][e.from] = (stats[e.kind][e.from] || 0) + 1;
      changes.push({ file: rel, line: li + 1, prop: e.prop, from: e.from, to: e.tok });
    }
    return line;
  });
  if (APPLY && out.join('\n') !== raw) writeFileSync(abs, out.join('\n'));
}

const fmt = (o) => Object.entries(o).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}×${v}`).join('  ');
if (VERIFY) {
  console.log(`layer3 exceptions [VERIFY]：仍可收敛的例外原子 = ${residual.length}`);
  for (const r of residual.slice(0, 25)) console.log(`  · ${r.file}:${r.line} ${r.prop}: ${r.from} → ${r.tok}`);
  console.log(residual.length === 0 ? '  ✅ VERIFY: 单值例外已清零（组合/负数/组件尺寸/14px 见 EXCEPTIONS.md）' : '  ❌ VERIFY: 仍有例外');
  process.exit(residual.length === 0 ? 0 : 1);
}
writeFileSync(join(HERE, APPLY ? 'layer3-report.json' : 'layer3-report.dryrun.json'), JSON.stringify({ stats, changes }, null, 2) + '\n');
const total = Object.values(stats.space).reduce((a, b) => a + b, 0) + Object.values(stats.radius).reduce((a, b) => a + b, 0);
console.log(`layer3 exceptions [${APPLY ? 'APPLY' : 'DRY-RUN'}]：改点 ${total}`);
console.log(`  间距：${fmt(stats.space)}`);
console.log(`  圆角：${fmt(stats.radius)}`);
console.log(`  跳过：组合四值圆角 ${stats.combos} 处 · 间距豁免原子 ${stats.skipped} 个（负数 / 14px / 组件尺寸）`);
