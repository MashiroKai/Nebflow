#!/usr/bin/env node
// verify-zero-pixel.mjs — 「零像素变化」的**可复算判据**（本批验收主判据）。
//
// 判据为什么不是「全部 sha256 相同」：
//   本机（darwin-chromium-151）canvas 场景存在**反走样抖噪**——把同一棵树（未改一个
//   字节）连拍两拍，画面就会在 5–17px 量级抖动（实测见 A1↔A2 / B1↔B2 矩阵）。因此
//   「前后两拍有差异」不能直接判成本批改了像素，必须先把**同树噪声带**量出来。
//
// 判据三条（全部机械可算）：
//   ① SHA-SAME      前后两拍 sha256 逐字节相同 ⇒ 通过（最强证据）。
//   ② NOISE-BOUND   该场景在**同树两拍**里也抖过（进噪声集合），且前后差异 ≤ 该场景
//                   同树抖动的最大值 ⇒ 判为宿主渲染噪声，不是本批的效果。
//   ③ DRIFT         不满足①② ⇒ 真漂移，本批必须解释或回退。
//   「同树」= 同一棵树、同一 web-root、无任何代码改动的两次采集。
//   ②b MULTI-STATE 迁移树的某一拍**逐字节复现**改前那一拍 ⇒ 该场景渲染多态，
//      差异与代码无关（迁移若改了像素，就不可能复现改前字节）。
//
// 用法：
//   node verify-zero-pixel.mjs --pre <dir> --post <dir> --pairs <a1:b1,a2:b2,...>
// 退出码：0 = 无 DRIFT；1 = 有 DRIFT。

import { readFileSync, readdirSync } from 'node:fs';
import { join, basename } from 'node:path';
import { createHash } from 'node:crypto';
import { comparePng } from '../../scripts/lib/png-diff.mjs';

const argv = process.argv.slice(2);
const valOf = (f) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : null; };
const PRE = valOf('--pre');
const POST = valOf('--post');
const PAIRS = (valOf('--pairs') || '').split(',').filter(Boolean).map((p) => p.split(':'));
/** 迁移树上的**额外**采集（同一份代码的另一拍）。用来证明「渲染是多态的」：
 *  若迁移树的某一拍能逐字节复现改前那一拍，则本场景的渲染与代码无关，
 *  差异只能是宿主渲染抖动 —— 迁移若真改了像素，复现改前字节在逻辑上不可能。 */
const ALTS = [];
for (let i = 0; i < argv.length; i += 1) if (argv[i] === '--alt' && argv[i + 1]) ALTS.push(argv[i + 1]);

const sha = (b) => createHash('sha256').update(b).digest('hex');
const read = (dir, name) => readFileSync(join(dir, name));
const list = (dir) => readdirSync(dir).filter((f) => f.endsWith('.png') && !f.endsWith('-diff.png'));

// ── 同树噪声带：同一棵树两次采集之间的差异（本批自身的对照组）──
const noise = {};   // name → {max, samples:[{diff, bbox}]}
for (const [a, b] of PAIRS) {
  let files;
  try { files = list(a); } catch { console.error(`跳过不存在的目录 ${a}`); continue; }
  for (const name of files) {
    let bufB;
    try { bufB = read(b, name); } catch { continue; }
    const bufA = read(a, name);
    if (sha(bufA) === sha(bufB)) continue;
    const r = comparePng(bufA, bufB, { tolerance: 0 });
    const slot = (noise[name] ||= { max: 0, samples: [] });
    slot.max = Math.max(slot.max, r.diffPixels);
    slot.samples.push({ pair: `${a.split('/').pop()}↔${b.split('/').pop()}`, diff: r.diffPixels, bbox: r.bbox });
  }
}

// ── 主判据 ──────────────────────────────────────────────────────────────
const rows = [];
let drift = 0, shaSame = 0, noiseBound = 0;
for (const name of list(PRE).sort()) {
  let bufPost;
  try { bufPost = read(POST, name); } catch { rows.push({ name, verdict: 'MISSING', detail: '改后无此场景图' }); drift++; continue; }
  const bufPre = read(PRE, name);
  if (sha(bufPre) === sha(bufPost)) { shaSame++; rows.push({ name, verdict: 'SHA-SAME', detail: '逐字节相同' }); continue; }
  const r = comparePng(bufPre, bufPost, { tolerance: 0 });
  const n = noise[name];
  if (n && r.diffPixels <= n.max) {
    noiseBound++;
    rows.push({ name, verdict: 'NOISE-BOUND', detail: `diff=${r.diffPixels}px ≤ 同树噪声上限=${n.max}px bbox=${JSON.stringify(r.bbox)} 同树证据=${n.samples.map((s) => s.pair + ':' + s.diff + 'px').join(' ')}` });
  } else {
    // ②b：迁移树的额外拍里有没有逐字节等于改前那一拍的？有 ⇒ 渲染多态，非迁移所致。
    const twin = ALTS.find((d) => { try { return sha(read(d, name)) === sha(bufPre); } catch { return false; } });
    if (twin) {
      noiseBound++;
      rows.push({ name, verdict: 'MULTI-STATE', detail: `diff=${r.diffPixels}px bbox=${JSON.stringify(r.bbox)}；但 ${basename(twin)} 逐字节 == 改前（迁移树复现了改前字节 ⇒ 渲染多态，非迁移所致）` });
      continue;
    }
    drift++;
    rows.push({ name, verdict: 'DRIFT', detail: `diff=${r.diffPixels}px (${(r.ratio * 100).toFixed(3)}%) bbox=${JSON.stringify(r.bbox)}` + (n ? ` 同树噪声上限=${n.max}px` : ' 该场景从未在同树两拍中抖动过，且迁移树也未复现改前字节') });
  }
}

const w = Math.max(...rows.map((r) => r.name.length)) + 2;
for (const r of rows) console.log(`${r.verdict.padEnd(12)} ${r.name.padEnd(w)} ${r.detail}`);
console.log(`\n合计 ${rows.length} 场景：SHA-SAME ${shaSame} · NOISE-BOUND ${noiseBound} · DRIFT ${drift}`);
console.log(drift ? 'VERDICT: FAIL —— 存在超出同树噪声带的像素差异' : 'VERDICT: PASS —— 全部场景逐字节相同或落在同树噪声带内');
process.exit(drift ? 1 : 0);
