#!/usr/bin/env node
// layer1-naming-debt.mjs — 设计规格 525 处改值收敛批 · 第一层（零视觉风险层）机械迁移器
//
// 三处命名债，**值逐字不变**（pixel-zero 是本层的唯一验收判据）：
//   A. --ds-space-glass-x（357 处消费 / 366 个 var() 原子）→ 六个布局角色槽（10px 不动）
//   B. --ds-fs-mobile-callout 的 13 处**桌面语境**消费 → 新增桌面槽 --ds-fs-callout（同 16px）
//   C. --ds-fs-capsule + --ds-fs-card-body（同值 14px 异义两槽）→ 合并为 --ds-fs-subtitle（14px）
//
// 用法：
//   node reports-evidence/20261105_design-spec-value-convergence/layer1-naming-debt.mjs          # dry-run
//   node reports-evidence/20261105_design-spec-value-convergence/layer1-naming-debt.mjs --apply
//   node .../layer1-naming-debt.mjs --verify    # 迁移后残余旧名 = 0 的机械断言
//
// 判据守则：
//   · 只改**令牌名**，值（10px / 16px / 14px）一个字不动；
//   · 角色由「属性 × 该 token 在简写值里的原子序号」机械判定（轴 × 内外）——这是
//     CSS 简写定义决定的，不靠选择器语义猜；也不按组件派生（那才是同值别名堆）；
//   · 解析不出角色的声明**不猜**：进 needs-ruling 并 exit 1（迁移必须 100% 可解释）；
//   · 令牌出生行（`--x: …`）不是消费，跳过。

import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('./', import.meta.url));
const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const WEB = join(ROOT, 'src/main/resources/web');
const APPLY = process.argv.includes('--apply');
const VERIFY = process.argv.includes('--verify');

const TOKEN_A = 'var(--ds-space-glass-x)';
const ROLE = {
  'inline-x': '--ds-space-inline-x',       // 内横距：块/控件与内容的左右内距
  'block-y': '--ds-space-block-y',         // 内纵距：块上下内距
  'inset': '--ds-space-inset',             // 四边等内距：padding 单值
  'inline-gap': '--ds-space-inline-gap',   // 行内件缝：同一行相邻件之间
  'stack-gap': '--ds-space-stack-gap',     // 块间纵距：块与块之间的纵向间隙
  'indent-x': '--ds-space-indent-x',       // 行缩进：相对容器左缘的横向偏移
};
const MERGE_14 = { 'var(--ds-fs-capsule)': 'var(--ds-fs-subtitle)', 'var(--ds-fs-card-body)': 'var(--ds-fs-subtitle)' };

/**
 * B：桌面语境的 16px 消费，逐条列名（文件 + 证据标签 + 期望条数）。
 * 「桌面还是手机」不是能从选择器字符串可靠推出的属性（`body.mobile-layer` 前缀只是
 * 其中一种写法），所以本层把证据钉在明文里，改动面可逐行复核；每个文件的命中数
 * 必须严格等于期望值，否则整条拒改（上游微调不会让本表静默失效）。
 */
const DESKTOP_CALLOUT = [
  ['css/split.css', '.canvas-md-viewer h1 — markdown 文档 h1'],
  ['css/split.css', '.epub-reader-content — epub 阅读面正文 16/1.8'],
  ['css/split.css', '.epub-reader-content h3 — epub 小标题'],
  ['css/split.css', '.canvas-txt-scroll — txt 阅读面正文 16/1.8'],
  ['css/chat.css', '.bubble.ai/.bubble.injected/.ask-bub.ai h1 — 桌面气泡内 h1'],
  ['css/sidebar.css', '.cfg-card-remove — 卡片移除钮 × 字形（line-height:1）'],
  ['css/sidebar.css', '.seg-remove — 分段移除钮 × 字形（line-height:1）'],
  ['css/modal.css', '#modal-title — 桌面弹窗标题 600'],
  ['css/modal.css', '#delete-title — 删除弹窗标题 600'],
  ['css/modal.css', '#conflict-title — 冲突弹窗标题 600'],
  ['js/bgTaskOutputPopup.js', '输出弹窗关闭钮 × 字形（line-height:1）'],
  ['js/flowCss.js', '.flow-viewer-close — 画布级关闭钮 × 字形'],
  ['js/flowCss.js', '.flow-mail-content h1 — 邮件卡片 h1'],
];
const DESKTOP_WANT = {};
for (const [f] of DESKTOP_CALLOUT) DESKTOP_WANT[f] = (DESKTOP_WANT[f] || 0) + 1;

function topAtoms(v) {
  const out = []; let depth = 0, cur = '';
  for (const ch of v) {
    if (ch === '(') depth += 1;
    if (ch === ')') depth -= 1;
    if (/\s/.test(ch) && depth === 0) { if (cur) out.push(cur); cur = ''; } else cur += ch;
  }
  if (cur) out.push(cur);
  return out;
}
function walk(dir, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, acc);
    else if (['.css', '.js'].includes(extname(p))) acc.push(p);
  }
  return acc;
}
/** 注释遮罩（等长空格）：判据只看真代码，行列位置不变。 */
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

/** 属性 + token 在值里的原子序号 + 原子总数 ⇒ 角色（轴 × 内外）。 */
function resolveRole(prop, idx, count) {
  if (prop === 'gap' || prop === 'column-gap') return 'inline-gap';
  if (prop === 'row-gap') return 'stack-gap';
  if (prop === 'padding-left' || prop === 'padding-right') return 'inline-x';
  if (prop === 'padding-top' || prop === 'padding-bottom') return 'block-y';
  if (prop === 'margin-left' || prop === 'margin-right') return 'indent-x';
  if (prop === 'margin-top' || prop === 'margin-bottom') return 'stack-gap';
  if (prop === 'padding') {
    if (count === 1) return 'inset';
    if (count === 2) return idx === 0 ? 'block-y' : 'inline-x';
    if (count === 3) return idx === 1 ? 'inline-x' : 'block-y';
    if (count === 4) return idx % 2 === 0 ? 'block-y' : 'inline-x';
  }
  if (prop === 'margin') {
    if (count === 1) return null;                                   // 四边等 margin：无单一角色
    if (count === 2) return idx === 0 ? 'stack-gap' : 'indent-x';
    if (count === 3) return idx === 1 ? 'indent-x' : 'stack-gap';
    if (count === 4) return idx % 2 === 0 ? 'stack-gap' : 'indent-x';
  }
  return null;
}

/** 取 valueStart 起的逻辑值（跨行拼接到分隔符），返回 { value, endLine } */
function logicalValue(mLines, li, valueStart) {
  let value = '';
  let j = li;
  for (; j < mLines.length; j += 1) {
    const seg = j === li ? mLines[j].slice(valueStart) : mLines[j];
    const term = seg.search(/[;{}'"]/);
    if (term >= 0) { value += seg.slice(0, term); break; }
    value += seg + ' ';
  }
  return value;
}

const stats = { A: {}, B: 0, C: 0, files: {} };
const ruling = [];
const residual = [];

function migrateA(raw, rel) {
  const rawLines = raw.split('\n'), mLines = maskComments(raw).split('\n');
  let changed = 0;
  const out = rawLines.map((rawLine, li) => {
    if (!rawLine.includes(TOKEN_A)) return rawLine;
    const mL = mLines[li];
    if (/^\s*--[a-z0-9-]+\s*:/.test(mL)) return rawLine;             // 令牌出生行
    const occ = [];
    for (let i = mL.indexOf(TOKEN_A); i >= 0; i = mL.indexOf(TOKEN_A, i + 1)) occ.push(i);
    let line = rawLine;
    for (const at of [...occ].reverse()) {
      const m = /([a-zA-Z-]+)\s*:\s*([^;{}'"]*)$/.exec(mL.slice(0, at));
      if (!m) { ruling.push({ file: rel, line: li + 1, why: '行列内找不到属性起点', text: rawLine.trim().slice(0, 120) }); continue; }
      const prop = m[1].toLowerCase();
      const valueStart = m.index + m[0].length - m[2].length;
      const value = logicalValue(mLines, li, valueStart);
      const atoms = topAtoms(value.trim());
      const idx = topAtoms(mL.slice(valueStart, at).trim()).length;
      const role = resolveRole(prop, idx, atoms.length);
      if (!role) { ruling.push({ file: rel, line: li + 1, why: `角色无法判定 prop=${prop} idx=${idx}/${atoms.length}`, text: rawLine.trim().slice(0, 120) }); continue; }
      const tok = ROLE[role];
      line = line.slice(0, at) + `var(${tok})` + line.slice(at + TOKEN_A.length);
      stats.A[tok] = (stats.A[tok] || 0) + 1;
      changed += 1;
    }
    return line;
  });
  return { text: out.join('\n'), changed };
}

function migrateB(text, rel) {
  const want = DESKTOP_WANT[rel] || 0;
  if (!want) return { text, changed: 0 };
  const lines = text.split('\n'), mLines = maskComments(text).split('\n');
  const hits = [];
  for (let i = 0; i < mLines.length; i += 1) {
    if (mLines[i].includes('var(--ds-fs-mobile-callout)') && !/^\s*--[a-z0-9-]+\s*:/.test(mLines[i])) hits.push(i);
  }
  if (hits.length !== want) {
    ruling.push({ file: rel, why: `桌面 16px 消费点数量漂移：期望 ${want} 实得 ${hits.length}`, text: hits.map((i) => `${i + 1}:${mLines[i].trim().slice(0, 60)}`).join(' | ') });
    return { text, changed: 0 };
  }
  for (const i of hits) lines[i] = lines[i].split('var(--ds-fs-mobile-callout)').join('var(--ds-fs-callout)');
  stats.B += hits.length;
  return { text: lines.join('\n'), changed: hits.length };
}

function migrateC(text) {
  let n = 0;
  for (const [from, to] of Object.entries(MERGE_14)) {
    const c = text.split(from).length - 1;
    if (c) { text = text.split(from).join(to); n += c; }
  }
  stats.C += n;
  return { text, changed: n };
}

const files = [...walk(join(WEB, 'css')), ...walk(join(WEB, 'js'))];
let totalA = 0, totalB = 0, totalC = 0, changedFiles = 0;

for (const abs of files) {
  const rel = relative(WEB, abs).replaceAll('\\', '/');
  const raw = readFileSync(abs, 'utf8');
  if (!raw.includes('--ds-space-glass-x') && !raw.includes('--ds-fs-mobile-callout')
    && !raw.includes('--ds-fs-capsule') && !raw.includes('--ds-fs-card-body')) continue;
  const a = migrateA(raw, rel);
  const b = migrateB(a.text, rel);
  const c = migrateC(b.text);
  totalA += a.changed; totalB += b.changed; totalC += c.changed;
  stats.files[rel] = { A: a.changed, B: b.changed, C: c.changed };
  if (APPLY) {
    writeFileSync(abs, c.text);
    if (a.changed + b.changed + c.changed) changedFiles += 1;
    // 残余检查（apply 后回读磁盘，不看内存）
    const after = readFileSync(abs, 'utf8');
    for (const t of ['var(--ds-space-glass-x)', 'var(--ds-fs-capsule)', 'var(--ds-fs-card-body)']) {
      const n = after.split(t).length - 1;
      if (n) residual.push({ file: rel, token: t, n });
    }
  }
}

console.log(`layer1 naming-debt [${APPLY ? 'APPLY' : 'DRY-RUN'}${VERIFY ? ' VERIFY' : ''}]`);
console.log(`  A glass-x → 角色槽：${totalA} 个 var() 原子`);
console.log('    ' + Object.entries(stats.A).sort((x, y) => y[1] - x[1]).map(([k, v]) => `${k}×${v}`).join('  '));
console.log(`  B 桌面 16px → --ds-fs-callout：${totalB} 处（期望 ${DESKTOP_CALLOUT.length}）`);
console.log(`  C 14px 两槽 → --ds-fs-subtitle：${totalC} 处`);
console.log('  逐文件 top：' + Object.entries(stats.files).filter(([, v]) => v.A + v.B + v.C)
  .sort((x, y) => (y[1].A + y[1].B + y[1].C) - (x[1].A + x[1].B + x[1].C))
  .slice(0, 12).map(([k, v]) => `${k}×${v.A + v.B + v.C}`).join('  '));
if (residual.length) { console.log('  ⚠ 残余旧名：'); for (const r of residual) console.log(`    ${r.file}: ${r.token} ×${r.n}`); }
if (ruling.length) {
  console.log(`  ⚠ needs-ruling ${ruling.length} 条（拒猜，须清零才可 apply）：`);
  for (const r of ruling.slice(0, 25)) console.log(`    ${r.file}:${r.line ?? '-'} ${r.why} :: ${r.text ?? ''}`);
}
if (APPLY) writeFileSync(join(HERE, 'layer1-report.json'), JSON.stringify({ stats, totalA, totalB, totalC, changedFiles, residual, ruling }, null, 2) + '\n');
else writeFileSync(join(HERE, 'layer1-report.dryrun.json'), JSON.stringify({ stats, totalA, totalB, totalC, residual, ruling }, null, 2) + '\n');
if (VERIFY) {
  const ok = residual.length === 0 && ruling.length === 0;
  console.log(ok ? '  ✅ VERIFY: 旧名残余 0 / 拒猜 0' : '  ❌ VERIFY: 仍有残余或拒猜');
  process.exit(ok ? 0 : 1);
}
process.exit(ruling.length ? 1 : 0);
