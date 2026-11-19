#!/usr/bin/env node
// audit-border-static.mjs — 边框类视觉的**静态清点**（本批第 1 步的另一半）。
//
// 与 audit-border-weight.mjs（computed 实测）互补：
//   · 实测面回答「屏幕上哪些线真的看到了」（颜色是解析后的实值，含 α 合成）；
//   · 静态面回答「这条线写在哪一行、值是令牌还是字面量、属于亮档还是暗档」
//     —— 收敛方案与「不许在第二处再写一份」的判据都要靠它。
//
// 扫描面：src/main/resources/web/css/*.css + src/main/resources/web/js/*.js
// （JS 里有注入式 CSS：bgTaskOutputPopup / flowAgentPopup / daemons / activityBar
//  等文件内联 `@media (prefers-color-scheme: dark)` 块，漏扫就会漏掉一半暗档面）
// + src/main/resources/web/index.html 的内联 style。
//
// 判定口径：
//   · 只登记**改变可见描边**的声明：border / border-{top,right,bottom,left} /
//     border-color（族）/ outline（族）/ box-shadow（含 inset 环）/ background-image
//     里的单边 linear-gradient 发丝线。
//   · `border: none|0` / `border-width` / `border-style` 等**不产生颜色**的声明计
//     为 keyword 档，进表但不参与「字面量」告警。
//   · 值分类：token（出现 var(--…)）/ literal（出现 hex/rgb/hsl/命名色）/ 
//     mixed（两者都有）/ keyword（none/0/solid/…，无颜色）。
//
// 用法：
//   node scripts/audit-border-static.mjs                    # 汇总 + 字面量清单
//   node scripts/audit-border-static.mjs --json <path>
//   node scripts/audit-border-static.mjs --all               # 打全表
//   node scripts/audit-border-static.mjs --root <web根>      # 扫另一检出/导出的 web 根
//     （前后对照必需：改前那张表必须在**未改动的静态根**上生成，否则两张表
//      逐字节相同 —— 本批实测过这个自欺：--root 不被支持时「改前」其实是改后。）

import { readFileSync, readdirSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, relative, resolve } from 'node:path';
import { ROOT } from './lib/visual-harness.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const JSON_OUT = valOf('--json');
const ALL = argv.includes('--all');
const ROOT_OVERRIDE = valOf('--root');

const WEB = ROOT_OVERRIDE ? resolve(ROOT_OVERRIDE) : join(ROOT, 'src', 'main', 'resources', 'web');
/** 标签基准：默认相对仓根；--root 指向 <某检出>/src/main/resources/web 时回到该检出根，
 *  使「改前 / 改后」两张表的路径键同形，能逐行比对。 */
const LABEL_BASE = (() => {
  const suffix = join('src', 'main', 'resources', 'web');
  return WEB.endsWith(suffix) ? WEB.slice(0, -(suffix.length + 1)) : WEB;
})();
const NAMED = '\\b(?:transparent|black|white|currentcolor|inherit|initial|unset|revert)\\b';
const LITERAL = new RegExp(`#[0-9a-fA-F]{3,8}\\b|\\brgba?\\(|\\bhsla?\\(|${NAMED}`, 'i');
const HAS_COLOR = new RegExp(`#[0-9a-fA-F]{3,8}\\b|\\brgba?\\(|\\bhsla?\\(|${NAMED}`, 'i');

const BORDER_PROPS = /^(border|border-(top|right|bottom|left|color|top-color|right-color|bottom-color|left-color|block|block-start|block-end|inline|inline-start|inline-end)|outline|outline-color|box-shadow|column-rule|background-image|border-image)/;

/** 收集待扫文件。 */
function files() {
  const out = [];
  const cssDir = join(WEB, 'css');
  for (const f of readdirSync(cssDir)) if (f.endsWith('.css')) out.push(join(cssDir, f));
  const jsDir = join(WEB, 'js');
  for (const f of readdirSync(jsDir)) if (f.endsWith('.js')) out.push(join(jsDir, f));
  out.push(join(WEB, 'index.html'));
  return out;
}

/** 一行里的声明 → {prop, value}（近似：按第一个 `:` 切，去掉 `!important`）。 */
function declOf(line) {
  const t = line.trim().replace(/\/\*.*?\*\//g, '').trim();
  if (!t || t.startsWith('/*') || t.startsWith('//') || !t.includes(':')) return null;
  const i = t.indexOf(':');
  const prop = t.slice(0, i).trim().toLowerCase();
  let value = t.slice(i + 1).replace(/!important/gi, '').trim();
  value = value.replace(/[;,}]\s*$/, '');
  if (!/^[a-z-]+$/.test(prop)) return null;
  return { prop, value };
}

/**
 * 一行的**全部**声明候选。
 *
 * 为什么必须按 `{`/`}` 切开（本批实测的漏面）：本仓大量单行多声明规则
 * （`.a hr, .b hr { border: none; border-top: 1px solid X; margin: … }`），
 * 若整行丢给 declOf，第一条声明的 prop 会读成「选择器 { border」而整行被丢掉
 * ——`.bubble.ai hr` 那条正是这样在旧版静态清点表里**完全不存在**，
 * 也就没人能看见它写的是 `#eee` 还是令牌。
 */
function declsOf(line) {
  const t = line.replace(/\/\*.*?\*\//g, '');
  if (!t.includes('{')) return [declOf(t)].filter(Boolean);
  const out = [];
  let rest = t;
  while (rest.includes('{')) {
    const open = rest.indexOf('{');
    const close = rest.indexOf('}', open + 1);
    const body = close === -1 ? rest.slice(open + 1) : rest.slice(open + 1, close);
    for (const part of body.split(';')) { const d = declOf(part); if (d) out.push(d); }
    if (close === -1) break;
    rest = rest.slice(close + 1);
  }
  return out;
}

function classify(value) {
  const hasVar = /var\(--/.test(value);
  const hasLit = LITERAL.test(value);
  if (hasVar && hasLit) return 'mixed';
  if (hasVar) return 'token';
  if (hasLit) return 'literal';
  return 'keyword';
}

function scan() {
  const rows = [];
  for (const file of files()) {
    let text;
    try { text = readFileSync(file, 'utf8'); } catch { continue; }
    const lines = text.split('\n');
    const stack = [];
    let selector = '';
    for (let i = 0; i < lines.length; i++) {
      const raw = lines[i];
      const line = raw.replace(/\/\*.*?\*\//g, '');
      if (/^\s*(\/\/|\*|\/\*)/.test(raw) && !/[{;:]/.test(line.replace(/\/\/.*$/, ''))) {
        // 纯注释行：仍然要吃括号以维持深度
      }
      // 先处理闭合
      const closes = (line.match(/\}/g) || []).length;
      const opens = (line.match(/\{/g) || []).length;
      const trimmed = line.trim();

      // 选择器/at-rule 行：`xxx {`（可能同行还有声明）
      const mSel = /^(.*?)\{/.exec(line);
      if (mSel && trimmed.includes('{')) {
        const head = mSel[1].trim().replace(/,$/, '');
        if (head) { stack.push(head); selector = head; } else { stack.push('<block>'); }
      }

      // 声明
      if (trimmed && !trimmed.startsWith('@')) {
        for (const d of declsOf(line)) {
          if (!BORDER_PROPS.test(d.prop)) continue;
          const scopePath = stack.join(' > ');
          const dark = /prefers-color-scheme:\s*dark|data-theme\s*=\s*["']?dark/i.test(scopePath);
          const light = /prefers-color-scheme:\s*light|data-theme\s*=\s*["']?light/i.test(scopePath);
          rows.push({
            file: relative(LABEL_BASE, file), line: i + 1, selector: selector || scopePath,
            scope: dark ? 'dark' : light ? 'light' : 'base', prop: d.prop, value: d.value,
            kind: classify(d.value),
          });
        }
      }
      // 处理块栈
      for (let k = 0; k < closes; k++) stack.pop();
      if (opens > 0 && !mSel) { stack.push('<block>'); }
    }
  }
  return rows;
}

function main() {
  const rows = scan();
  const withColor = rows.filter((r) => HAS_COLOR.test(r.value) || r.kind !== 'keyword');
  const literals = withColor.filter((r) => r.kind === 'literal' || r.kind === 'mixed');

  console.log(`# 静态清点：${rows.length} 条边框类声明（其中产色 ${withColor.length} 条），来自 ${new Set(rows.map((r) => r.file)).size} 个文件\n`);

  const byKind = {}; for (const r of withColor) byKind[r.kind] = (byKind[r.kind] || 0) + 1;
  console.log('## 值来源统计（产色声明）');
  for (const k of ['token', 'mixed', 'literal', 'keyword']) console.log(`  ${k.padEnd(9)} ${String(byKind[k] || 0).padStart(4)}`);

  console.log('\n## 按文件（产色声明数 / 其中字面量）');
  const byFile = new Map();
  for (const r of withColor) {
    if (!byFile.has(r.file)) byFile.set(r.file, { n: 0, lit: 0, darkLit: 0 });
    const v = byFile.get(r.file); v.n++;
    if (r.kind === 'literal' || r.kind === 'mixed') { v.lit++; if (r.scope === 'dark') v.darkLit++; }
  }
  [...byFile.entries()].sort((a, b) => b[1].lit - a[1].lit).forEach(([f, v]) => {
    console.log(`  ${f.padEnd(46)} 产色 ${String(v.n).padStart(3)}  字面量 ${String(v.lit).padStart(3)}  暗档字面量 ${String(v.darkLit).padStart(2)}`);
  });

  console.log('\n## 暗档作用域内的**字面量**边框色（收敛候选：应改走令牌）');
  const darkLit = literals.filter((r) => r.scope === 'dark');
  for (const r of darkLit) console.log(`  ${r.file}:${r.line}  ${r.selector}  ${r.prop}: ${r.value}  [${r.kind}]`);
  console.log(`  —— 合计 ${darkLit.length} 条`);

  console.log('\n## 全站字面量边框色（前 60，按文件序）');
  for (const r of (ALL ? literals : literals.slice(0, 60))) {
    console.log(`  ${r.file}:${r.line}  [${r.scope}]  ${r.selector}  ${r.prop}: ${r.value}`);
  }
  console.log(`  —— 合计 ${literals.length} 条`);

  if (JSON_OUT) {
    mkdirSync(dirname(JSON_OUT), { recursive: true });
    writeFileSync(JSON_OUT, JSON.stringify({ rows, withColor, literals }, null, 2));
    console.log(`\n# json → ${JSON_OUT}`);
  }
}

main();
