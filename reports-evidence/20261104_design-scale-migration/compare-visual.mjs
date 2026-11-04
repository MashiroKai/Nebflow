#!/usr/bin/env node
// compare-visual.mjs — 迁移前后**逐字节**视觉对照（零像素变化的判据器）。
//
// 为什么要自己写：`make check-visual --max-ratio 0` 比的是**入库基准图**
// （tests/visual/baseline/），而本机 darwin-chromium-151 基准与当前渲染本就有
// 存量差异/缺失（实测 canvas-code-tab-dark diff=63px、16 场景 MISS）——拿它判
// 「本批有没有改像素」会把存量噪声算到本批头上。故本器改比**同一台机、同一轮
// 前后两拍**（pre-change-run1 vs post-change-*），噪声只在两拍之间，判据干净。
//
// 判据三级：
//   ① SHA-SAME      两拍 sha256 逐字节相同 —— 最强证据（本机 23/28 场景如此）；
//   ② NOISE-BOUND   渲染噪声场景（本机 5 个）：差异像素数 ≤ 干净树上跑两次
//                    （pre-change-run1 vs pre-change-run2）的噪声带 —— 即
//                    「前后差异不超过本机不加任何改动时的抖动」；
//   ③ DRIFT         超噪声带 ⇒ 真漂移，必须逐条解释或回退。
//
// 用法：node compare-visual.mjs <beforeDir> <afterDir> [noiseDir]
// 退出码：0 = 全部①或②；1 = 存在③。

import { readFileSync, readdirSync } from 'node:fs';
import { join, basename } from 'node:path';
import { createHash } from 'node:crypto';
import { comparePng } from '../../scripts/lib/png-diff.mjs';

const [beforeDir, afterDir, noiseDir] = process.argv.slice(2);
const sha = (buf) => createHash('sha256').update(buf).digest('hex');
const scenarios = readdirSync(beforeDir).filter((f) => f.endsWith('.png') && !f.endsWith('-diff.png')).sort();

const rows = [];
let drift = 0;
for (const name of scenarios) {
  let after;
  try { after = readFileSync(join(afterDir, name)); } catch { rows.push({ name, verdict: 'MISSING', detail: '改后无此场景图' }); drift += 1; continue; }
  const before = readFileSync(join(beforeDir, name));
  if (sha(before) === sha(after)) { rows.push({ name, verdict: 'SHA-SAME', detail: '逐字节相同' }); continue; }
  const r = comparePng(before, after, { tolerance: 0 });
  let noise = -1;
  if (noiseDir) {
    try { noise = comparePng(readFileSync(join(noiseDir, name)), before, { tolerance: 0 }).diffPixels; } catch { /* 无噪声参照 */ }
  }
  const within = noise >= 0 && r.diffPixels <= noise;
  if (!within) drift += 1;
  rows.push({
    name,
    verdict: within ? 'NOISE-BOUND' : 'DRIFT',
    detail: `diff=${r.diffPixels}px (${(r.ratio * 100).toFixed(3)}%)` + (noise >= 0 ? ` 噪声带=${noise}px` : ' 无噪声参照')
      + (r.bbox ? ` bbox=${JSON.stringify(r.bbox)}` : ''),
  });
}

const width = Math.max(...rows.map((r) => r.name.length)) + 2;
for (const r of rows) console.log(`${r.verdict.padEnd(12)} ${r.name.padEnd(width)} ${r.detail}`);
const shaSame = rows.filter((r) => r.verdict === 'SHA-SAME').length;
const noiseB = rows.filter((r) => r.verdict === 'NOISE-BOUND').length;
console.log(`\n合计 ${rows.length} 场景：SHA-SAME ${shaSame} · NOISE-BOUND ${noiseB} · DRIFT ${drift}`);
process.exit(drift ? 1 : 0);
