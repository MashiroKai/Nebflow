#!/usr/bin/env node
// input-surface.spec.mjs — 输入面唯一标准的「真几何」验收
// （input-surface-unify 批 2026-11-06；作者裁定「这个蓝色输入框怎么到处在显示…
//   就采用我们主对话窗口这种 灰色圆角阴影」+「输入框的这个样式也要成为统一标准
//   不可以出现第二种」）
//
// 装置：零端口零进程（复用 scripts/lib/visual-harness.mjs —— page.route 从磁盘服务
// src/main/resources/web + MockWS 走真实 ws.js 分发路径）。不起实例 ⇒ 结构上不可能
// 碰宿主 6138；NEBFLOW_WEB_ROOT 可指向另一棵 web 树（改前/改后对照用）。
//
// 判据（全部读**浏览器计算值**，不读声明字面量）：
//   P1 零蓝边     ：静息态与**指针聚焦**态，输入面边框色不许是蓝族
//                   （b − max(r,g) ≥ 24 且 b ≥ 90 判蓝 —— 品牌蓝 #3b82f6 的
//                    b−max(r,g) = 116；中性灰三者相近）
//   P2 指针态零环 ：鼠标点进输入框，outline 必须不可见（宽 0 或 style none）
//   P3 键盘态可见 ：Tab 进来必须看得见 —— outline ≥ 2px 且环 vs 框底对比度 ≥ 3:1
//                   （WCAG 1.4.11 / 2.4.11 门槛；这是「去蓝不许吃掉无障碍」的判据）
//   P4 形态对齐   ：盒式输入件 圆角 = 12px、阴影非 none、底不透明度过 0.3
//                   （= 主会话输入框那一套「灰调 + 圆角 + 柔和阴影」的读数）
//   P5 裸件不套框 ：主会话 / 记事本的内层 textarea 边框宽度 0、阴影 none
//                   （外壳给材质，内件不再画第二层边）
//
// 运行：
//   node tests/input-surface.spec.mjs [outDir]
//   改前对照（验红）：
//     git archive <base> src/main/resources/web | tar -x -C /tmp/nf-web-before
//     NEBFLOW_WEB_ROOT=/tmp/nf-web-before/src/main/resources/web \
//       node tests/input-surface.spec.mjs /tmp/out-before --expect-red
//   变异（验判据载力）：--mutate=blue-pointer-ring
//     把指针态重新变成一圈品牌蓝 outline —— **必须转红并打印 RED-CONFIRMED**。
//
// 退出码：0 = 全绿（或 --expect-red 下确实转红）；1 = 有 FAIL；
//         3 = --expect-red 下没转红（判据无载力，等同 FAIL）；2 = 装置错误。

import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession, settle, webRoot } from '../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const argv = process.argv.slice(2);
const flag = (name, def = null) => {
  const hit = argv.find((a) => a === `--${name}` || a.startsWith(`--${name}=`));
  if (!hit) return def;
  const i = hit.indexOf('=');
  return i < 0 ? true : hit.slice(i + 1);
};
const OUT = argv.find((a) => !a.startsWith('--')) || join(ROOT, 'reports-evidence', 'input-surface');
const MUTATE = String(flag('mutate', ''));
const EXPECT_RED = Boolean(flag('expect-red', false));
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

/** 夹具：三件并排 —— 记事本多行输入 / 设置 API Key 输入 / 主会话输入框。
 *  刻意用**产品自己的类名**（.note-dock>.note-body>textarea、.secrets-input）；
 *  主会话那件直接量页面里真实的 `#input`（它在 index.html 里），不复制。
 *  容器底固定为 --color-bg，方便算对比度。 */
const LAB_HTML = `
<div id="nf-field-lab" style="position:fixed;left:0;top:0;z-index:2147483000;display:flex;flex-wrap:wrap;gap:16px;padding:16px;width:min(100vw,980px);background:var(--color-bg)">
  <div data-face="note" style="width:300px;height:150px;display:flex">
    <div class="nf-glass" style="width:100%;height:100%;padding:12px;box-sizing:border-box;border-radius:var(--cv-radius-bar,12px)">
    <div class="note-body" style="width:100%;height:100%;box-sizing:border-box">
      <textarea data-probe="note" placeholder="随手记点什么…" aria-label="记事本"></textarea>
    </div>
    </div>
  </div>
  <div data-face="secret" style="width:300px;display:flex;align-items:flex-start">
    <textarea class="secrets-input" data-probe="secret" placeholder="API Key" aria-label="API Key"></textarea>
  </div>
  <div data-face="composer" style="width:300px;display:flex;align-items:flex-start">
    <div style="width:100%;padding:6px 12px;border-radius:var(--ds-radius-shell);background:var(--nf-glass-bg);border:1px solid var(--nf-glass-border-color);box-shadow:var(--nf-glass-shadow)">
      <textarea class="nf-field--bare" data-probe="composer" style="width:100%;padding:6px 14px;font-size:var(--ds-fs-bubble);line-height:1.4;resize:none" placeholder="问点什么…"></textarea>
    </div>
  </div>
</div>`;

/** 被测面（.probe 是夹具里的 data-probe） */
const FACES = [
  { id: 'note', label: '记事本多行输入', sel: '[data-probe="note"]', boxed: false, bare: true },
  { id: 'secret', label: '设置 API Key 输入', sel: '[data-probe="secret"]', boxed: true, bare: false },
  // 参照物：作者令 2026-11-03 10:11 ④「输入区键盘态不再加高亮」⇒ 它按设计**没有环**，
  // 故只取读数、不进断言（标准以它为基准，本批不改它）。
  { id: 'composer', label: '主会话输入框（参照物）', sel: '[data-probe="composer"]', boxed: false, bare: true, assert: false },
];

const r2 = (n) => Math.round(n * 100) / 100;

function parseColor(s) {
  if (!s) return null;
  // Chrome 对 color-mix() 的计算值序列化成 color(srgb r g b / a)（0..1 分量），
  // 对普通色仍是 rgb()/rgba() —— 两种都要认，否则「读不到」会被当成「不蓝」。
  const cm = /color\(\s*srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+))?\s*\)/.exec(s);
  if (cm) {
    return { r: parseFloat(cm[1]) * 255, g: parseFloat(cm[2]) * 255, b: parseFloat(cm[3]) * 255, a: cm[4] ? parseFloat(cm[4]) : 1 };
  }
  const m = /rgba?\(([^)]+)\)/.exec(s);
  if (!m) return null;
  const p = m[1].split(/[,/]/).map((x) => parseFloat(x.trim()));
  return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
}

/** 相对亮度（WCAG 2.x） */
function lum(c) {
  const f = (v) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
}
function contrast(a, b) {
  const l1 = lum(a), l2 = lum(b);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}
function isBlue(c) {
  return c && c.b - Math.max(c.r, c.g) >= 24 && c.b >= 90;
}
/** α 合成：前景 over 背景 */
function over(fg, bg) {
  const a = fg.a ?? 1;
  return { r: fg.r * a + bg.r * (1 - a), g: fg.g * a + bg.g * (1 - a), b: fg.b * a + bg.b * (1 - a), a: 1 };
}

const failures = [];
const readings = [];
function check(code, ok, detail) {
  if (!ok) failures.push({ code, detail });
  return ok;
}

/** 读一个元素的计算值形态 */
async function measure(page, sel) {
  return page.evaluate((s) => {
    const el = /** @type {HTMLElement} */ (document.querySelector(s));
    if (!el) return null;
    const cs = getComputedStyle(el);
    const pageBg = getComputedStyle(document.body).backgroundColor;
    return {
      borderTopWidth: cs.borderTopWidth,
      borderTopColor: cs.borderTopColor,
      borderRadius: cs.borderTopLeftRadius,
      boxShadow: cs.boxShadow,
      backgroundColor: cs.backgroundColor,
      outlineWidth: cs.outlineWidth,
      outlineStyle: cs.outlineStyle,
      outlineColor: cs.outlineColor,
      outlineOffset: cs.outlineOffset,
      fontSize: cs.fontSize,
      paddingTop: cs.paddingTop,
      paddingLeft: cs.paddingLeft,
      modality: document.documentElement.dataset.nfModality || '(none)',
      pageBg,
    };
  }, sel);
}

// ── 变异腿：把指针态重新变成一圈品牌蓝（验判据载力）──────────────────────
let MUTATION = '';
const MUTATIONS = {
  'blue-pointer-ring': `
    html[data-nf-modality="pointer"] [data-probe] { outline: 2px solid #3b82f6 !important; outline-offset: 0 !important; }
    [data-probe="secret"] { border-color: #3b82f6 !important; }`,
};

async function focusByPointer(page, sel) {
  const box = await page.locator(sel).boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + Math.min(10, box.height / 2));
  await page.mouse.down();
  await page.mouse.up();
  await settle(page, 120);
  // 焦点必须**真的**落到该元素上：否则「没有蓝环」只是「没聚焦」的假绿
  return page.evaluate((s) => document.activeElement === document.querySelector(s), sel);
}

async function focusByKeyboard(page, sel) {
  // keydown 'Tab' 把 modality 切到 keyboard（真键盘事件），随后程序化聚焦 ——
  // 不产生 pointerdown，因此这正是「Tab 进来」的那条通道。
  await page.keyboard.down('Tab');
  await page.evaluate((s) => document.querySelector(s).focus(), sel);
  await page.keyboard.up('Tab');
  await settle(page, 120);
}

async function runViewport(browser, { width, height, tag }) {
  const s = await createSession(browser, { width, height, colorScheme: 'light' });
  const page = s.page;
  await s.boot();
  await page.evaluate((html) => {
    const host = document.createElement('div');
    host.innerHTML = html;
    document.body.appendChild(host);
  }, LAB_HTML);
  if (MUTATION) await page.addStyleTag({ content: MUTATION });
  await settle(page, 400);

  const lab = page.locator('#nf-field-lab');
  const out = { tag, width, height, faces: {} };

  for (const face of FACES) {
    // ① 静息态
    await page.evaluate(() => document.activeElement && document.activeElement.blur && document.activeElement.blur());
    const rest = await measure(page, face.sel);

    // ② 指针聚焦
    const gotFocus = await focusByPointer(page, face.sel);
    const pointer = await measure(page, face.sel);
    // 三件并排的**指针聚焦态**（焦点落在这件上，另外两件同框）
    await lab.screenshot({ path: join(SHOTS, `${tag}-${face.id}-pointer.png`) }).catch(() => {});
    await page.evaluate(() => document.activeElement && document.activeElement.blur && document.activeElement.blur());

    // ③ 键盘聚焦
    await focusByKeyboard(page, face.sel);
    const keyboard = await measure(page, face.sel);
    await lab.screenshot({ path: join(SHOTS, `${tag}-${face.id}-keyboard.png`) }).catch(() => {});

    out.faces[face.id] = { rest, pointer, keyboard, pointerFocused: gotFocus, keyboardFocused: await page.evaluate((s) => document.activeElement === document.querySelector(s), face.sel) };
    await page.evaluate(() => document.activeElement && document.activeElement.blur && document.activeElement.blur());
  }
  await lab.screenshot({ path: join(SHOTS, `${tag}-rest.png`) }).catch(() => {});

  // 参照物读数（标准以它为准，本批不改它）：真实的 #input-bar 外壳 + 内层 #input
  out.reference = {
    bar: await measure(page, '#input-bar'),
    inner: await measure(page, '#input'),
  };
  await s.close();
  return out;
}

// ── 主流程 ─────────────────────────────────────────────────────────────
MUTATION = MUTATIONS[MUTATE] || '';
if (MUTATE && !MUTATION) {
  console.error(`input-surface: unknown mutation "${MUTATE}" (known: ${Object.keys(MUTATIONS).join(', ')})`);
  process.exit(2);
}

const { mod: pw } = loadPlaywright();
const { browser, label: browserLabel } = await launchBrowser(pw);
const results = [];
try {
  results.push(await runViewport(browser, { width: 1440, height: 900, tag: 'desktop' }));
  results.push(await runViewport(browser, { width: 375, height: 812, tag: 'mobile' }));
} finally {
  await browser.close();
}

for (const v of results) {
  for (const face of FACES) {
    const f = v.faces[face.id];
    if (face.assert === false) { readings.push({ viewport: v.tag, face: face.label, 静息边框: f ? `${f.rest.borderTopWidth} ${f.rest.borderTopColor}` : 'n/a', 指针边框: f ? `${f.pointer.borderTopWidth} ${f.pointer.borderTopColor}` : 'n/a', 指针环: f ? `${f.pointer.outlineWidth} ${f.pointer.outlineStyle}` : 'n/a', 键盘环: f ? `${f.keyboard.outlineWidth} ${f.keyboard.outlineStyle} ${f.keyboard.outlineColor}` : 'n/a', 圆角: f ? f.pointer.borderRadius : 'n/a', 阴影: f && f.pointer.boxShadow !== 'none' ? 'on' : 'none', 底: f ? f.pointer.backgroundColor : 'n/a', 字号: f ? f.pointer.fontSize : 'n/a' }); continue; }
    if (!f || !f.pointer || !f.keyboard) {
      // 量不到 = 判据看不见现场，必须红（不许「缺读数 ⇒ 绿」）
      check('P0', false, `${v.tag}/${face.label}: 读数缺失（${face.sel}）`);
      continue;
    }
    const p = f.pointer;
    const bg = parseColor(p.backgroundColor);
    const pageBg = parseColor(p.pageBg) || { r: 255, g: 255, b: 255, a: 1 };
    const effBg = bg && bg.a < 1 ? over(bg, pageBg) : bg || pageBg;
    const border = parseColor(p.borderTopColor);
    const ring = parseColor(f.keyboard.outlineColor);

    // P1 零蓝边（静息 + 指针聚焦）
    check('P1', !isBlue(parseColor(f.rest.borderTopColor)) && !isBlue(border),
      `${v.tag}/${face.label}: 边框 ${f.rest.borderTopColor} → ${p.borderTopColor}（蓝族）`);

    // P0 聚焦真的发生了（防「没聚焦 ⇒ 没环 ⇒ 假绿」）
    check('P0', f.pointerFocused === true, `${v.tag}/${face.label}: 指针点击未把焦点落到输入件上（读数不可信）`);

    // P2 指针态零环
    const pw2 = p.outlineStyle === 'none' ? 0 : parseFloat(p.outlineWidth) || 0;
    check('P2', pw2 === 0,
      `${v.tag}/${face.label}: 指针聚焦仍出环 outline=${p.outlineWidth} ${p.outlineStyle} ${p.outlineColor}`);

    // P3 键盘态可见
    const kw = f.keyboard.outlineStyle === 'none' ? 0 : parseFloat(f.keyboard.outlineWidth) || 0;
    const kc = ring && effBg ? contrast(ring, effBg) : 0;
    check('P3', kw >= 2 && kc >= 3,
      `${v.tag}/${face.label}: 键盘聚焦环 ${kw}px / 对比度 ${r2(kc)}:1（需 ≥2px 且 ≥3:1；环=${f.keyboard.outlineColor} 底=${p.backgroundColor}）`);

    if (face.boxed) {
      // P4 形态对齐
      const radius = parseFloat(p.borderRadius);
      const hasShadow = p.boxShadow && p.boxShadow !== 'none';
      const alpha = bg ? bg.a : 0;
      check('P4', Math.abs(radius - 12) <= 0.5 && hasShadow && alpha >= 0.3,
        `${v.tag}/${face.label}: 圆角 ${p.borderRadius} / 阴影 ${hasShadow ? 'on' : 'none'} / 底 α ${alpha}`);
    }
    if (face.bare) {
      // P5 裸件不套框
      check('P5', parseFloat(p.borderTopWidth) === 0 && (!p.boxShadow || p.boxShadow === 'none') && !isBlue(border),
        `${v.tag}/${face.label}: 裸件仍有框 ${p.borderTopWidth} ${p.borderTopColor} / shadow=${p.boxShadow}`);
    }

    readings.push({
      viewport: v.tag, face: face.label,
      静息边框: `${f.rest.borderTopWidth} ${f.rest.borderTopColor}`,
      指针边框: `${p.borderTopWidth} ${p.borderTopColor}`,
      指针环: `${p.outlineWidth} ${p.outlineStyle}`,
      键盘环: `${f.keyboard.outlineWidth} ${f.keyboard.outlineColor} (对比 ${r2(kc)}:1)`,
      圆角: p.borderRadius, 阴影: p.boxShadow === 'none' ? 'none' : 'on',
      底: p.backgroundColor, 字号: p.fontSize,
    });
  }
}

writeFileSync(join(OUT, 'readings.json'), JSON.stringify({ webRoot: webRoot(), browser: browserLabel, mutate: MUTATE || null, readings, failures }, null, 2));

console.log(`input-surface 真几何验收 — webRoot=${webRoot()} browser=${browserLabel}${MUTATE ? ` mutate=${MUTATE}` : ''}\n`);
for (const r of readings) {
  console.log(`[${r.viewport}] ${r.face}`);
  console.log(`    静息边框 ${r.静息边框} | 指针边框 ${r.指针边框} | 指针环 ${r.指针环}`);
  console.log(`    键盘环 ${r.键盘环} | 圆角 ${r.圆角} | 阴影 ${r.阴影} | 底 ${r.底} | 字号 ${r.字号}`);
}
for (const v of results) {
  if (!v.reference) continue;
  const { bar, inner } = v.reference;
  if (bar) console.log(`[${v.tag}] 参照物 #input-bar：圆角 ${bar.borderRadius} | 底 ${bar.backgroundColor} | 阴影 ${bar.boxShadow === 'none' ? 'none' : 'on'} | 边框 ${bar.borderTopWidth} ${bar.borderTopColor}`);
  if (inner) console.log(`[${v.tag}] 参照物 #input   ：边框 ${inner.borderTopWidth} | 阴影 ${inner.boxShadow === 'none' ? 'none' : 'on'} | 字号 ${inner.fontSize} | 内距 ${inner.paddingTop}/${inner.paddingLeft}`);
}
console.log('');

if (failures.length) {
  console.log(`FAIL ${failures.length} 项：`);
  for (const f of failures) console.log(`  ${f.code}  ${f.detail}`);
  if (EXPECT_RED) {
    console.log('\nRED-CONFIRMED — 判据在改前树上确实转红（不是恒真）。');
    process.exit(0);
  }
  process.exit(1);
}
if (EXPECT_RED) {
  console.log('FAIL(expected) — --expect-red 但判据全绿：说明这些断言抓不到改前的形态（判据无载力）。');
  process.exit(3);
}
console.log('PASS — P1 零蓝边 / P2 指针态零环 / P3 键盘态可见 / P4 形态对齐 / P5 裸件不套框（两个视口 × 三件）。');
process.exit(0);
