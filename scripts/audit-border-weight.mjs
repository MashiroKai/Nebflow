#!/usr/bin/env node
// audit-border-weight.mjs — 暗档边框「太明显」的**清点装置**（本批第 1、2 步）。
//
// 输出（stdout = 人读表；--json = 机器面）：
//   A. 逐面清点表：signature / 场景 / 边框类（border|outline|inset-ring）/ 边 /
//      宽度 / computed 颜色 / 相邻底色（bgSelf / bgOuter）/ WCAG 对比度 / ΔE00，
//      暗档与亮档各一行（同一 signature 对齐）。
//   B. 降序超标表：按「暗档对比度 − 亮档对比度」降序，标出最明显的若干处。
//   C. 值来源表：把所有出现的边框色按「令牌 or 字面量」聚类（静态面另表见
//      report 的「写死 vs 令牌」段 —— 本脚本只报 computed 实测面）。
//
// 用法：
//   node scripts/audit-border-weight.mjs                 # 全场景
//   node scripts/audit-border-weight.mjs --scenes shell-chat,settings
//   node scripts/audit-border-weight.mjs --json <path>   # 落盘机器面
//   node scripts/audit-border-weight.mjs --top 25

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { loadPlaywright, launchBrowser, createSession, settle, setWebRoot } from './lib/visual-harness.mjs';
import { BORDER_SCENES, COLLECT_BORDERS_FN } from './lib/border-scenes.mjs';
import { borderMetrics, hex } from './lib/color-metrics.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const SCENES_FILTER = valOf('--scenes');
const JSON_OUT = valOf('--json');
const ROOT_OVERRIDE = valOf('--root');
const TOP = Number(valOf('--top', '30'));
const THEMES = (valOf('--themes', 'dark,light') || '').split(',').filter(Boolean);

// `--root` = 把清点跑在**另一个检出/导出**上（改前对照用；与 check-glass-unify 同规）：
//   git archive HEAD src/main/resources/web | tar -x -C /tmp/before
//   node scripts/audit-border-weight.mjs --root /tmp/before/src/main/resources/web
if (ROOT_OVERRIDE) { const r = setWebRoot(ROOT_OVERRIDE); console.log(`# static root = ${r}\n`); }

const scenes = BORDER_SCENES.filter((s) => !SCENES_FILTER || SCENES_FILTER.split(',').includes(s.name));

/** 一行探针读数 → 度量行。 */
function measure(row) {
  const bgSelf = hex(row.bgSelf);
  const bgOuter = hex(row.bgOuter);
  const mSelf = borderMetrics(`rgba(${row.color.join(',')})`, bgSelf);
  const mOuter = borderMetrics(`rgba(${row.color.join(',')})`, bgOuter);
  const selfC = mSelf ? mSelf.contrast : 0;
  const outerC = mOuter ? mOuter.contrast : 0;
  const useSelf = selfC >= outerC;
  const m = useSelf ? mSelf : mOuter;
  return {
    ...row,
    colorCss: `rgba(${row.color.map((v, i) => (i === 3 ? v.toFixed(3) : Math.round(v))).join(', ')})`,
    bgSelf, bgOuter,
    contrast: m ? m.contrast : 0,
    deltaE: m ? m.deltaE : 0,
    ref: useSelf ? 'self' : 'outer',
  };
}

async function main() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  console.log(`# browser = ${label}  scenes = ${scenes.map((s) => s.name).join(',')}\n`);

  /** @type {Map<string, {theme:string, scene:string, row:any}[]>} */
  const byKey = new Map();
  const all = [];

  for (const scene of scenes) {
    for (const vp of scene.viewports) {
      for (const theme of THEMES) {
        const s = await createSession(browser, { colorScheme: theme, width: vp.width, height: vp.height, clock: scene.clock });
        try {
          await scene.setup(s);
          await settle(s.page, 250);
          const rows = await s.page.evaluate(COLLECT_BORDERS_FN);
          for (const r of rows) {
            const m = measure(r);
            const entry = { theme, scene: scene.name, vp: `${vp.width}x${vp.height}`, ...m };
            all.push(entry);
            const key = `${scene.name}|${vp.width}x${vp.height}|${m.sig}|${m.kind}|${m.prop}|${m.colorCss}`;
            if (!byKey.has(key)) byKey.set(key, []);
            byKey.get(key).push(entry);
          }
          console.log(`  [ok] ${scene.name} ${theme} ${vp.width}x${vp.height} → ${rows.length} 条边框读数${s.errors.length ? `  pageErrors=${s.errors.length}` : ''}`);
        } catch (e) {
          console.log(`  [ERR] ${scene.name} ${theme}: ${String(e.message).split('\n')[0]}`);
        } finally {
          await s.close();
        }
      }
    }
  }
  await browser.close();

  // ── A. 逐面清点表（暗 + 亮对齐）────────────────────────────────────────
  // 同一 (场景,sig,kind,colorCss) 去重 —— 四边同色同宽算**一面**（否则一条
  // 边框会在表里出现四次，看不出「几处边框太明显」）。theme 两档各取最大对比度。
  const grouped = new Map();
  for (const e of all) {
    const k = `${e.scene}|${e.sig}|${e.kind}|${e.colorCss}`;
    if (!grouped.has(k)) grouped.set(k, { scene: e.scene, sig: e.sig, kind: e.kind, prop: e.prop, width: e.width, colorCss: e.colorCss, bgSelf: e.bgSelf, ref: e.ref, themes: {}, count: 0, sides: new Set() });
    const g = grouped.get(k);
    g.count++;
    g.sides.add(e.prop);
    const prev = g.themes[e.theme];
    if (!prev || e.contrast > prev.contrast) {
      g.themes[e.theme] = { contrast: e.contrast, deltaE: e.deltaE, bg: e.ref === 'self' ? e.bgSelf : e.bgOuter };
    }
  }
  const flat = [...grouped.values()];
  flat.sort((a, b) => ((b.themes.dark || {}).contrast || 0) - ((a.themes.dark || {}).contrast || 0));

  console.log('\n## A. 边框清点表（computed；暗档 max contrast 降序）\n');
  console.log('| # | 场景 | selector(sig) | 类 | 边数 | 宽 | computed 色 | 暗 底 | 暗 ratio | 暗 ΔE00 | 亮 底 | 亮 ratio | 亮 ΔE00 | 暗−亮 |');
  console.log('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|');
  flat.forEach((g, i) => {
    const d = g.themes.dark || {}; const l = g.themes.light || {};
    const dc = d.contrast ?? null; const lc = l.contrast ?? null;
    const gap = dc !== null && lc !== null ? +(dc - lc).toFixed(3) : '';
    console.log(`| ${i + 1} | ${g.scene} | \`${g.sig}\` | ${g.kind} | ${g.sides.size} | ${g.width}px | \`${g.colorCss}\` | ${d.bg ?? '-'} | ${dc ?? '-'} | ${d.deltaE ?? '-'} | ${l.bg ?? '-'} | ${lc ?? '-'} | ${l.deltaE ?? '-'} | ${gap} |`);
  });

  console.log(`\n## B. 超标降序（暗档 − 亮档 对比度差；同面两档都有读数才算）\n`);
  const gaps = flat
    .filter((g) => g.themes.dark && g.themes.light)
    .map((g) => ({ g, gap: +(g.themes.dark.contrast - g.themes.light.contrast).toFixed(3) }))
    .sort((a, b) => b.gap - a.gap);
  console.log('| # | 场景 | selector | 类 | 暗 ratio | 亮 ratio | 暗−亮 |');
  console.log('|---|---|---|---|---|---|---|');
  gaps.slice(0, TOP).forEach((x, i) => {
    console.log(`| ${i + 1} | ${x.g.scene} | \`${x.g.sig}\` | ${x.g.kind} | ${x.g.themes.dark.contrast} | ${x.g.themes.light.contrast} | ${x.gap} |`);
  });

  // ── C. 值聚类（computed 颜色 → 出现次数）────────────────────────────────
  console.log('\n## C. computed 边框色聚类（按暗档）\n');
  const byColor = new Map();
  for (const e of all) {
    if (e.theme !== 'dark') continue;
    const k = e.colorCss;
    if (!byColor.has(k)) byColor.set(k, { n: 0, sigs: new Set(), maxC: 0 });
    const v = byColor.get(k); v.n++; v.sigs.add(e.sig); v.maxC = Math.max(v.maxC, e.contrast);
  }
  [...byColor.entries()].sort((a, b) => b[1].maxC - a[1].maxC).forEach(([c, v]) => {
    console.log(`  ${c.padEnd(30)} 条数 ${String(v.n).padStart(4)}  面数 ${String(v.sigs.size).padStart(3)}  max ratio ${v.maxC}`);
  });

  if (JSON_OUT) {
    mkdirSync(dirname(JSON_OUT), { recursive: true });
    writeFileSync(JSON_OUT, JSON.stringify({ generatedAt: new Date().toISOString(), browser: label, rows: all, grouped: flat }, null, 2));
    console.log(`\n# json → ${JSON_OUT}`);
  }
}

main().catch((e) => { console.error(e); process.exit(2); });
