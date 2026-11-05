#!/usr/bin/env node
// compare-dirs.mjs — A/B 两套采集目录逐张比对（第一层「零像素」的第二条独立证据）
//
// 用法：node compare-dirs.mjs <dirA> <dirB>
// 判据：逐张 comparePng（tolerance 0）。SAME = 逐字节像素相同；NOISE-BOUND = 差异
// 落在同树抖动带内（须配合「同树双拍」读数解释）；DIFF = 真差异。
import { readdirSync, readFileSync, existsSync, writeFileSync } from 'node:fs';
import { join, basename } from 'node:path';
import { comparePng } from '../../scripts/lib/png-diff.mjs';

const [a, b] = process.argv.slice(2);
if (!a || !b) { console.error('用法：compare-dirs.mjs <dirA> <dirB>'); process.exit(2); }
for (const d of [a, b]) if (!existsSync(d)) { console.error(`缺目录：${d}`); process.exit(2); }
const fa = readdirSync(a).filter((n) => n.endsWith('.png')).sort();
const fb = new Set(readdirSync(b).filter((n) => n.endsWith('.png')));
let same = 0, diff = 0, miss = 0;
const rows = [];
for (const n of fa) {
  if (!fb.has(n)) { miss += 1; rows.push({ tag: basename(n, '.png'), status: 'MISS-in-B' }); continue; }
  const d = comparePng(readFileSync(join(a, n)), readFileSync(join(b, n)), { tolerance: 0 });
  if (d.same) { same += 1; rows.push({ tag: basename(n, '.png'), status: 'SAME', bytes: readFileSync(join(a, n)).length }); }
  else {
    diff += 1;
    rows.push({ tag: basename(n, '.png'), status: 'DIFF', diffPixels: d.diffPixels, ratio: d.ratio, bbox: d.bbox });
    console.log(`  DIFF ${basename(n, '.png')}  ${d.diffPixels}px (${(d.ratio * 100).toFixed(4)}%) bbox=${JSON.stringify(d.bbox)}`);
  }
}
for (const n of fb) if (!fa.includes(n)) { miss += 1; rows.push({ tag: basename(n, '.png'), status: 'MISS-in-A' }); }
console.log(`compare ${a} ↔ ${b}: SAME ${same} · DIFF ${diff} · MISS ${miss}`);
const ok = diff === 0 && miss === 0;
if (process.argv.includes('--json')) writeFileSync(process.argv[process.argv.indexOf('--json') + 1], JSON.stringify({ a, b, same, diff, miss, rows }, null, 2) + '\n');
console.log(ok ? '  ✅ PIXEL IDENTICAL' : '  ❌ PIXEL DIFFERS');
process.exit(ok ? 0 : 1);
