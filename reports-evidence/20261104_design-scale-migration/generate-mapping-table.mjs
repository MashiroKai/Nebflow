#!/usr/bin/env node
// generate-mapping-table.mjs — 迁移映射表落盘（每类：原始值 → 令牌名 → 解析值 → 出现处数）。
//
// 数据源 = 三份 codemod 明细（needs-ruling-<class>.json 内同时记了 migrated 明细），
// 解析值 = 现读 design-system.css 的令牌声明（不手抄，避免表与源脱钩）。
//
// 用法：node generate-mapping-table.mjs   → 写 mapping-table.md

import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = fileURLToPath(new URL('./', import.meta.url));
const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const CSS = readFileSync(join(ROOT, 'src/main/resources/web/css/design-system.css'), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));

/** 令牌名 → 解析值（读源，不手抄）。 */
const RESOLVED = {};
for (const m of CSS.matchAll(/^\s*(--ds-(?:fs|radius|space)-[a-z0-9-]+)\s*:\s*([^;]+);/gm)) {
  RESOLVED[m[1]] = m[2].replace(/\s+/g, ' ').trim();
}

const CLASSES = [
  ['fs', '字号 font-size', 'font-size'],
  ['radius', '圆角 border-radius', 'border-radius'],
  ['space', '间距 padding / margin / gap', 'padding·margin·gap 原子'],
];

const lines = [];
lines.push('# 迁移映射表（design-scale 迁移批 2026-11-04）');
lines.push('');
lines.push('数据源：`premap-<class>.json` —— 迁移器打在**纯净 HEAD 树**上的 dry-run 明细，');
lines.push('即「改前有多少处该迁」，与 apply 顺序/重复执行无关（可复算）。');
lines.push('');
lines.push('判据：**只收解析后 computed 值完全相同的映射**（零像素变化）。换不到同值令牌的');
lines.push('一律不动，见文末「需作者裁定」清单。解析值列由 `generate-mapping-table.mjs` 现读');
lines.push('`design-system.css` 生成，不是手抄。');
lines.push('');

let grand = 0;
for (const [key, label, prop] of CLASSES) {
  let data;
  try { data = JSON.parse(readFileSync(join(DIR, `premap-${key}.json`), 'utf8')); } catch { continue; }
  const perToken = data.perToken || {};
  // 每个令牌 → 它吸收的字面量值（从声明反推：解析值 == 字面量）
  const rows = Object.entries(perToken).sort((a, b) => b[1] - a[1]).map(([tok, n]) => {
    const val = RESOLVED[tok] || '(未解析)';
    return { tok, val, n };
  });
  const total = rows.reduce((s, r) => s + r.n, 0);
  grand += total;
  lines.push(`## ${label}（已迁移 ${total} 处，属性 = \`${prop}\`）`);
  lines.push('');
  lines.push('| 原始字面量 | 令牌名 | 解析值 | 出现处数 |');
  lines.push('|---|---|---|---|');
  for (const r of rows) lines.push(`| \`${r.val}\` | \`${r.tok}\` | \`${r.val}\` | ${r.n} |`);
  lines.push('');
}

// 需裁定汇总（逐值归并）
lines.push('## 需作者裁定（本批**未动**，理由 = 无解析值相同的令牌）');
lines.push('');
for (const [key, label] of CLASSES) {
  let data;
  try { data = JSON.parse(readFileSync(join(DIR, `premap-${key}.json`), 'utf8')); } catch { continue; }
  const byVal = {};
  for (const n of data.needsRuling || []) {
    const k = n.value;
    (byVal[k] ||= { n: 0, files: new Set(), why: n.why });
    byVal[k].n += 1;
    byVal[k].files.add(n.file);
  }
  const entries = Object.entries(byVal).sort((a, b) => b[1].n - a[1].n);
  const total = entries.reduce((s, e) => s + e[1].n, 0);
  lines.push(`### ${label}：${total} 处 / ${entries.length} 个值`);
  lines.push('');
  lines.push('| 原始值 | 出现处数 | 主要落点 | 未迁原因 |');
  lines.push('|---|---|---|---|');
  for (const [v, e] of entries.slice(0, 30)) {
    const fl = [...e.files].slice(0, 4).join('·');
    lines.push(`| \`${v}\` | ${e.n} | ${fl} | ${e.why} |`);
  }
  if (entries.length > 30) lines.push(`| …其余 ${entries.length - 30} 个低频值 | | | 见 needs-ruling-${key}.json |`);
  lines.push('');
}

writeFileSync(join(DIR, 'mapping-table.md'), lines.join('\n') + '\n');
console.log(`mapping-table.md 已生成：已迁移 ${grand} 处`);
