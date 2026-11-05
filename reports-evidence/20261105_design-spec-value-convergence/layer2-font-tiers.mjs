#!/usr/bin/env node
// layer2-font-tiers.mjs — 设计规格 525 处改值收敛批 · 第二层（字号档收敛）
//
// 目标：7–26px 区间的**档外字面量**收敛到 7 个目标档。这一层**会改像素**（这是它的目的），
// 所以判据不是"零差异"，而是：① 每个原值都有明文去向与依据；② 改后区间内不再有档外值；
// ③ 双档（375/1440）截图对照留档；④ 11px 文本下限不被突破。
//
// 同时覆盖 `font-size:` 与 `font:` 简写两种载体 —— 只做前者会得到一个"表格说收敛了、
// 渲染仍在用 11.5px"的假结论（实测简写里还有 140 处档外尺寸）。
//
// 用法：
//   node layer2-font-tiers.mjs             # dry-run（打印映射与计数）
//   node layer2-font-tiers.mjs --apply
//   node layer2-font-tiers.mjs --verify    # 改后：区间内档外值 = 0 的机械断言
//
// 判据守则：
//   · 只在 [7,26]px 区间动；区间外（28/32/36… 大标题族、2–6px 特殊件）不动；
//   · 14px 是**保留档**（定版 §4.3：不并入 13/15），本层只把它令牌化到 --ds-fs-subtitle；
//   · 固定尺寸图标盒内的字符（16–18px 盒里的 2–3 字符）走具名 glyph 槽，
//     不收 11px —— 11px 三字符在 16px 盒里必然溢出（形态保全优先，理由写在令牌注释里）；
//   · 令牌出生行不是消费，跳过。

import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('./', import.meta.url));
const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const WEB = join(ROOT, 'src/main/resources/web');
const APPLY = process.argv.includes('--apply');
const VERIFY = process.argv.includes('--verify');

/**
 * 值 → 目标档（值表即判据表）。依据逐条写在右栏，报告里原样引用。
 * 目标值：11 meta / 12 hint|btn / 14 subtitle / 15 title / 20 display / 28 title-lg / 8 glyph
 */
const TARGET = {
  // ① 下限外文本：11px 是既有硬下限（定版 §4-③），一律收 11
  '7px': '--ds-fs-meta', '8px': '--ds-fs-meta', '8.5px': '--ds-fs-meta', '9px': '--ds-fs-meta',
  '9.5px': '--ds-fs-meta', '10px': '--ds-fs-meta', '10.5px': '--ds-fs-meta',
  // ② 半档：官网 --st-fs-label/-btn 逐值转录，客户端侧收 12（上下都无档，11.5 < 12 < 12.5）
  '11.5px': '--ds-fs-hint', '12.5px': '--ds-fs-hint',
  // ③ 保留档：14px 不并入 13/15（定版 §4-③），只令牌化
  '14px': '--ds-fs-subtitle',
  // ④ 15.5：标题字重（500/600）+ 负字距 = 标题族，就近归 title 15
  '15.5px': '--ds-fs-title',
  // ⑤ 18/19/22：语义分裂（epub 标题 vs 图标字形）但两族的去向在档位上同一是 20
  //    （图标字形 18→20 在 28–32px 盒里不可辨；epub h2 18→20 与 h1 22→20 同级，
  //     靠字重/行距分层的既有做法不变）—— 取 20 而不是 17，避免 17 只为一个字形出生
  '18px': '--ds-fs-display', '19px': '--ds-fs-display', '22px': '--ds-fs-display',
  // ⑥ 26：登录用户码大字展示，归大标题档 28
  '26px': '--ds-fs-title-lg',
  // ⑦ 已在档上的值：本层顺带令牌化（等值，不改像素）—— 否则「区间内无档外值」这条
  //    断言只覆盖档外值，区间里仍会漂着裸 11/12/13/15/16/17/20px（尤其 font 简写里）
  '11px': '--ds-fs-meta', '12px': '--ds-fs-hint', '13px': '--ds-fs-body',
  '15px': '--ds-fs-title', '16px': '--ds-fs-callout', '17px': '--ds-fs-lead',
  '20px': '--ds-fs-display',
};
/**
 * 区间内但**不归本层**的值：13.5 / 14.5 / 21 是组件语义具名槽（会话行 / 主对话气泡 /
 * 过渡卡主标题）。裸字面量指到别处会污染语义（一张工具卡的 21px 不是 card-hero），
 * 故按定版批同样的理由跳过，不算「无去向」。
 */
const SKIP = new Set(['13.5px', '14.5px', '21px']);
/** 固定尺寸图标盒内的字符 → 具名 glyph 槽（8px），不收 11（会溢出 16–18px 盒）。 */
const GLYPH = {
  '--ds-fs-glyph': [
    ['css/chat.css', '.nf-qicon.fc-icon'],                       // 16px 盒 3 字符
    ['css/input.css', '.att-ref-icon .nf-qicon.fc-icon'],        // 16px 盒 3 字符
    ['css/chat.css', '.fm-att-list .file-card .fc-icon'],        // 小 fc 盒
    ['css/chat.css', '.msg-quote-att-file-icon'],                // 引用附件图标盒
    ['css/delegatePanorama.css', '.dvp-exec-sm'],                // 14×14 盒
    ['css/reminder.css', '.reminder-badge'],                     // 14px 圆角计数徽标
  ],
};
/** 12px 双档（btn/hint 同值）：按语境"钮性"挑语义，值恒等。 */
const BUTTON_HINT = /btn|button|send|primary|submit|action|cta/i;
const IN_RANGE = (px) => px >= 7 && px <= 26;

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
const glyphTarget = (rel, selector) => {
  for (const [tok, pairs] of Object.entries(GLYPH)) {
    for (const [f, sel] of pairs) if (f === rel && selector.includes(sel)) return tok;
  }
  return null;
};

const stats = { perValue: {}, perToken: {}, total: 0, shorthand: 0 };
const changes = [];

const files = [...walk(join(WEB, 'css')), ...walk(join(WEB, 'js'))];
let verifyHits = [];

for (const abs of files) {
  const rel = relative(WEB, abs).replaceAll('\\', '/');
  const raw = readFileSync(abs, 'utf8');
  const mLines = maskComments(raw).split('\n');
  const rawLines = raw.split('\n');
  let selector = '';
  const out = rawLines.map((rawLine, li) => {
    const mL = mLines[li];
    const sel = mL.match(/^\s*([^{}@/][^{}]*)\{/);
    if (sel) selector = sel[1].trim();
    if (/^\s*--[a-z0-9-]+\s*:/.test(mL)) return rawLine;              // 令牌出生行
    let line = rawLine;
    // ── font-size: Npx ── 与 ── font: <wt> Npx[/<lh>] <family> ──
    const declRe = /(^|[;{,'"\s])(font-size|font)(\s*:\s*)([^;{}'"]+)/g;
    let m;
    const edits = [];
    while ((m = declRe.exec(mL)) !== null) {
      const prop = m[2].toLowerCase();
      const valueStart = m.index + m[1].length + m[2].length + m[3].length;
      let valueText = m[4];
      if (prop === 'font') {
        // 简写里第一个 px 原子 = 字号（行高在 `/` 之后，族名不含 px）
        const sm = valueText.match(/([0-9]*\.?[0-9]+px)/);
        if (!sm) continue;
        const px = parseFloat(sm[1]);
        if (!IN_RANGE(px) || SKIP.has(sm[1])) continue;
        edits.push({ start: valueStart + sm.index, text: sm[1], prop, px });
      } else {
        const sm = valueText.trim().match(/^([0-9]*\.?[0-9]+px)$/);
        if (!sm) continue;
        const px = parseFloat(sm[1]);
        if (!IN_RANGE(px) || SKIP.has(sm[1])) continue;
        const lead = valueText.indexOf(sm[1]);
        edits.push({ start: valueStart + lead, text: sm[1], prop, px });
      }
    }
    for (const e of edits.reverse()) {
      const key = `${e.text}`;
      if (VERIFY) { verifyHits.push({ file: rel, line: li + 1, px: key, prop: e.prop }); continue; }
      let tok = glyphTarget(rel, selector) || TARGET[key];
      if (!tok) { verifyHits.push({ file: rel, line: li + 1, px: key, prop: e.prop, why: '无去向' }); continue; }
      if ((tok === '--ds-fs-hint' || tok === '--ds-fs-meta') && BUTTON_HINT.test(selector) && tok === '--ds-fs-hint') tok = '--ds-fs-btn';
      line = line.slice(0, e.start) + `var(${tok})` + line.slice(e.start + e.text.length);
      stats.perValue[key] = (stats.perValue[key] || 0) + 1;
      stats.perToken[tok] = (stats.perToken[tok] || 0) + 1;
      if (e.prop === 'font') stats.shorthand += 1;
      stats.total += 1;
      changes.push({ file: rel, line: li + 1, prop: e.prop, from: key, to: tok, selector: selector.slice(0, 60) });
    }
    return line;
  });
  if (APPLY && out.join('\n') !== raw) writeFileSync(abs, out.join('\n'));
}

const valList = Object.entries(stats.perValue).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}×${v}`).join('  ');
if (VERIFY) {
  const bad = verifyHits;
  console.log(`layer2 font-tiers [VERIFY]：区间 [7,26]px 内档外字面量 = ${bad.length}`);
  for (const h of bad.slice(0, 30)) console.log(`  · ${h.file}:${h.line} ${h.prop} ${h.px}${h.why ? ' (' + h.why + ')' : ''}`);
  console.log(bad.length === 0 ? '  ✅ VERIFY: 档外值已清零' : '  ❌ VERIFY: 仍有档外值');
  process.exit(bad.length === 0 ? 0 : 1);
}
writeFileSync(join(HERE, APPLY ? 'layer2-report.json' : 'layer2-report.dryrun.json'),
  JSON.stringify({ stats, changes }, null, 2) + '\n');
console.log(`layer2 font-tiers [${APPLY ? 'APPLY' : 'DRY-RUN'}]：改点 ${stats.total}（其中 font 简写 ${stats.shorthand}）`);
console.log(`  原值 → ${valList}`);
console.log(`  目标档：${Object.entries(stats.perToken).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}×${v}`).join('  ')}`);
if (verifyHits.length) { console.log(`  ⚠ 无去向 ${verifyHits.length} 处：`); for (const h of verifyHits.slice(0, 10)) console.log(`    ${h.file}:${h.line} ${h.px}`); }
process.exit(0);
