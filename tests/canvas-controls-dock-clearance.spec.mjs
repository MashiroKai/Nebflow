#!/usr/bin/env node
// tests/canvas-controls-dock-clearance.spec.mjs — canvas 底部控件行的**可见且可点**闸
// （作者报障 2026-11-18 16:36「canvas的左侧按钮和中间三个点都没了现在」）。
//
// 为什么要有这道闸：仓里既有的 `mobile-canvas-toolbar-layout.spec.mjs` 早就有这条
// 判据、且**长期红着**（手机 375/390/320 全档 32 项红，见本文件跑出来的读数），没人
// 拿它当闸；而 `mobile-canvas-hit-clearance.spec.mjs`（**在册的前端闸**）为了绕开这
// 个 bug 写成「The dock can cover the find button」——它主动跳过不可点的控件，于是
// 「左下两钮 + 中间三点被 dock 吞掉」这一整类缺陷在闸下**结构上不可见**。
// 本闸把「该出现的控件真的可见且可点」写成硬判据：不看 DOM 存在性，看几何 + 命中。
//
// 判据（逐档：手机 375×812 / 桌面 1280×800，亮 + 暗）：
//   M1 控件非零尺寸、几何在视口内（`0 ≤ x` 且 `right ≤ vw` 且 `bottom ≤ vh`）
//   M2 控件在视口底缘的**真底缘**之上：与 #mobile-dock 的矩形零交叠（dock 是
//      fixed/z200 的实体面，压上去 = 画在它底下 = 人眼看不见 + 点不到）
//   M3 控件**可点**：`elementFromPoint(中心)` 命中自身或其后代（可见 ≠ 可点：
//      `opacity:0` / 被更高层覆盖都过不了这一条）
//   M4 底部一行**共中线**：左下两钮 / 中间三点 / 右侧 ask 钮的 cy 极差 ≤ 1px
//      （「同带共中线」是 ask.css 的原语义，行内件不许各走各的底锚）
//   M5 三点条水平居中（cx == vw/2）且点数 == 页签数（≤3）
//   D1 桌面 .canvas-zoom-bar 可见、≥2 成员、成员 48×48 非零
//   D2 桌面组成员可点 + 整组在视口内 + 组中线 == 成员中线
//   D3 桌面组**真的是毛玻璃**（bg α>0 且 backdrop-filter ≠ none）——防「透明化吞钮」
//   D4 桌面 canvas 其它控件（关签钮 / 页签条）照样可见可点（本闸兼职回归）
//
// 装置：scripts/lib/visual-harness.mjs（零端口零进程：page.route 从磁盘服务 web/，
// MockWS 走真实 ws.js 分发）⇒ 结构上不可能碰到宿主实例 6138。
// 用法：node tests/canvas-controls-dock-clearance.spec.mjs [outDir]
// 退出码：0 = 全绿；1 = 有 FAIL；2 = 装置错误。
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession, settle, webRoot } from '../scripts/lib/visual-harness.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const OUT = process.argv[2] && !process.argv[2].startsWith('--')
  ? process.argv[2] : join(ROOT, 'evidence', 'canvas-controls-dock-clearance');
mkdirSync(OUT, { recursive: true });

const rows = [];
let pass = 0, fail = 0;
const record = (id, name, ok, readout) => {
  rows.push({ id, name, verdict: ok ? 'PASS' : 'FAIL', readout });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}\n        ${readout}`);
  if (ok) pass += 1; else fail += 1;
};
const R2 = (n) => Math.round(n * 100) / 100;

/** 页内探针：返回底部控件行 + dock + 桌面上排的几何与命中读数。 */
const PROBE = `(() => {
  const R = (el) => { const r = el.getBoundingClientRect(); return { x: +r.x.toFixed(2), y: +r.y.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2),
    right: +r.right.toFixed(2), bottom: +r.bottom.toFixed(2), cx: +(r.x + r.width / 2).toFixed(2), cy: +(r.y + r.height / 2).toFixed(2) }; };
  const vis = (el) => { if (!el) return false; const s = getComputedStyle(el);
    return el.getClientRects().length > 0 && s.display !== 'none' && s.visibility === 'visible' && Number(s.opacity) > 0; };
  const hit = (el) => { const r = el.getBoundingClientRect(); const t = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return !!t && (t === el || el.contains(t) || t.contains(el)); };
  const one = (el, name) => { if (!el) return { name, missing: true }; const s = getComputedStyle(el);
    return { name, cls: String(el.className), visible: vis(el), hittable: hit(el), box: R(el),
      bg: s.backgroundColor, bd: s.backdropFilter || s.webkitBackdropFilter, display: s.display, z: s.zIndex }; };
  const dock = document.querySelector('#mobile-dock');
  const dots = document.querySelector('.mf-dots');
  const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane.active') || document.querySelector('.canvas-tab-pane.active') || document.querySelector('.canvas-tab-pane');
  const host = document.querySelector('.mf-cv-host');
  const mob = ['.canvas-find-toggle', '.canvas-ref-select', '.canvas-source-toggle'].map((sel) => one(pane && pane.querySelector(sel), sel));
  const bar = document.querySelector('.canvas-zoom-bar');
  const members = bar ? [...bar.querySelectorAll('.canvas-zoom-btn')] : [];
  return {
    vw: innerWidth, vh: innerHeight, mobileLayer: document.body.classList.contains('mobile-layer'),
    dock: dock ? { box: R(dock), z: getComputedStyle(dock).zIndex, display: getComputedStyle(dock).display } : null,
    dots: dots ? { ...one(dots, '.mf-dots'), count: dots.children.length } : { name: '.mf-dots', missing: true },
    left: mob,
    askFab: one([...document.querySelectorAll('.ask-fab')].find((e) => !e.classList.contains('note-fab')), '.ask-fab'),
    noteFab: one([...document.querySelectorAll('.ask-fab')].find((e) => e.classList.contains('note-fab')), '.ask-fab.note-fab'),
    host: host ? R(host) : null,
    bar: one(bar, '.canvas-zoom-bar'),
    members: members.map((m) => one(m, '.canvas-zoom-btn')),
    closeBtn: one(document.querySelector('#canvas-close-btn') || document.querySelector('#canvas-head-close'), 'canvas close'),
    tabBar: one(document.querySelector('#canvas-tab-bar .canvas-tab'), '#canvas-tab-bar .canvas-tab'),
    // 全量清点：canvas 区内**凡可见**的控件（.canvas-button 挂类件 + 缩放组）——
    // 回归闸就是在这一串上逐件过判据，不预设哪一枚该在。
    sweep: [...document.querySelectorAll('.canvas-button, .canvas-zoom-bar')].filter(vis)
      .map((el) => one(el, el.className)),
  };
})()`;

/** 一个控件在**纸面判据**上的三条：非零尺寸 / 在视口内 / 未被 dock 覆盖。
 * 返回 null = 通过，否则返回失败说明。 */
function shapeProblem(c, vw, vh, dock) {
  if (!c || c.missing) return 'missing';
  if (!c.visible) return 'not-visible';
  if (c.box.w <= 0 || c.box.h <= 0) return `zero-size ${c.box.w}x${c.box.h}`;
  if (c.box.x < -0.5 || c.box.right > vw + 0.5 || c.box.y < -0.5 || c.box.bottom > vh + 0.5) {
    return `outside-viewport ${JSON.stringify(c.box)}`;
  }
  if (dock) {
    const overlapX = Math.min(c.box.right, dock.box.right) - Math.max(c.box.x, dock.box.x);
    const overlapY = Math.min(c.box.bottom, dock.box.bottom) - Math.max(c.box.y, dock.box.y);
    if (overlapX > 0.5 && overlapY > 0.5) {
      return `under-dock overlap=${R2(overlapX)}x${R2(overlapY)} dock=${JSON.stringify(dock.box)} box=${JSON.stringify(c.box)}`;
    }
  }
  if (!c.hittable) return 'not-hittable (elementFromPoint hits another layer)';
  return null;
}

const { mod } = loadPlaywright();
const { browser, label } = await launchBrowser(mod);
const readings = [];
const IMG_URI = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="400"><rect width="640" height="400" fill="#22304a"/></svg>');

try {
  // ══ 手机腿 375×812：左下两钮 + 中间三点 + 右侧 ask 钮 ══════════════════════
  for (const theme of ['dark', 'light']) {
    const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true });
    const session = await createSession({ newPage: (o) => ctx.newPage(o) }, { width: 375, height: 812, colorScheme: theme, baseURL: 'https://stand.test/' });
    const { page } = session;
    await page.setViewportSize({ width: 375, height: 812 });
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
    await session.boot();
    await page.waitForSelector('body.mobile-layer');
    // 三张文档卡（一张 html = 引用钮在场；两点/三点指示器在 n≥2 才出）。
    await page.evaluate(async () => {
      const html = '<!doctype html><html><body><h1>Canvas first line</h1>' + '<p>Readable canvas content below floating controls.</p>'.repeat(40) + '</body></html>';
      const md = '# Canvas first line\n\n' + 'Readable canvas content below floating controls.\n\n'.repeat(40);
      const items = [
        { id: 'file:/tmp/cvcc-1.html', itemType: 'html', title: 'cvcc-1', content: html, absPath: '/tmp/cvcc-1.html' },
        { id: 'file:/tmp/cvcc-2.md', itemType: 'markdown', title: 'cvcc-2', content: md, absPath: '/tmp/cvcc-2.md' },
        { id: 'file:/tmp/cvcc-3.md', itemType: 'markdown', title: 'cvcc-3', content: md, absPath: '/tmp/cvcc-3.md' },
      ];
      for (const d of items) window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { ...d, pinned: true, size: d.content.length } }));
      const nav = await import('/js/mobile/navController.js');
      nav.dockTo('canvas');
      const canvas = await import('/js/canvas.js');
      canvas.setActiveTab(nav.docTabs()[0].id);   // html 面 ⇒ 引用钮 + 查找钮同排
    });
    await page.waitForSelector('.mf-cv-host > .canvas-tab-pane');
    await settle(page, 250);
    const g = await page.evaluate(PROBE);
    readings.push({ scene: `mobile-375x812-${theme}`, ...g });
    const tag = `mobile-375x812-${theme}`;

    const row = [g.left[0], g.left[1], g.dots, g.askFab];
    const problems = row.map((c) => [c.name, shapeProblem(c, g.vw, g.vh, g.dock)]);
    record(`${tag}-M1/M2/M3`, '左下两钮 + 中间三点 + ask 钮：非零尺寸 / 在视口内 / 未被 dock 覆盖 / 可点',
      problems.every(([, p]) => p === null),
      problems.map(([n, p]) => `${n}:${p || 'ok'}(${JSON.stringify(row.find((c) => c.name === n).box)})`).join(' | ')
      + ` dock=${JSON.stringify(g.dock && g.dock.box)}`);

    const cys = row.map((c) => c.box.cy);
    record(`${tag}-M4`, '底部一行共中线：cy 极差 ≤ 1px（左下两钮 / 三点条 / ask 钮）',
      Math.max(...cys) - Math.min(...cys) <= 1,
      `cy=${JSON.stringify(cys)} spread=${R2(Math.max(...cys) - Math.min(...cys))}`);

    record(`${tag}-M5`, '三点条水平居中且点数 = 页签数（≤3）',
      !g.dots.missing && Math.abs(g.dots.box.cx - g.vw / 2) <= 0.5 && g.dots.count === 3,
      `cx=${g.dots.box && g.dots.box.cx} vw/2=${g.vw / 2} count=${g.dots.count}`);

    record(`${tag}-M6`, '无 JS 异常（打桩未覆盖的 404 不计）', session.errors.length === 0, `errors=${JSON.stringify(session.errors)}`);
    await ctx.close();
  }

  // ══ 桌面腿 1280×800：canvas 组 + 其它控件 ════════════════════════════════
  for (const theme of ['dark', 'light']) {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const session = await createSession({ newPage: (o) => ctx.newPage(o) }, { width: 1280, height: 800, colorScheme: theme, baseURL: 'https://stand.test/' });
    const { page } = session;
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
    await session.boot();
    await page.evaluate((d) => { window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d })); }, {
      id: 'file:/tmp/cvcc-desk.svg', itemType: 'image', title: 'cvcc-desk.svg', pinned: true,
      content: '', absPath: '/tmp/cvcc-desk.svg', size: 0, mtimeMs: 1757000000000, objectUrl: IMG_URI,
    });
    await page.waitForSelector('.canvas-tab-pane .canvas-zoom-bar', { state: 'visible', timeout: 15000 });
    await settle(page, 250);
    const g = await page.evaluate(PROBE);
    readings.push({ scene: `desktop-1280x800-${theme}`, ...g });
    const tag = `desktop-1280x800-${theme}`;

    const mprobs = g.members.map((m) => shapeProblem(m, g.vw, g.vh, null));
    record(`${tag}-D1`, 'canvas 组可见、≥2 成员、成员非零且 48×48',
      g.bar.visible && g.members.length >= 2 && mprobs.every((p) => p === null)
        && g.members.every((m) => m.box.w === 48 && m.box.h === 48),
      `bar=${JSON.stringify(g.bar.box)} members=${JSON.stringify(g.members.map((m) => `${m.box.w}x${m.box.h}`))} problems=${JSON.stringify(mprobs)}`);

    const bprob = shapeProblem(g.bar, g.vw, g.vh, null);
    record(`${tag}-D2`, '组在视口内 / 未被遮挡 / 组中线 = 成员中线（≤1px）',
      bprob === null && Math.abs(g.bar.box.cy - g.members[0].box.cy) <= 1,
      `bar-problem=${bprob || 'ok'} bar.cy=${g.bar.box.cy} member.cy=${g.members[0].box.cy}`);

    const alpha = /rgba?\(([^)]+)\)/.exec(g.bar.bg || '');
    const a = alpha ? Number((alpha[1].split(',')[3] ?? '1').trim()) : 0;
    record(`${tag}-D3`, '组是真毛玻璃：bg α > 0 且 backdrop-filter ≠ none',
      a > 0 && !!g.bar.bd && g.bar.bd !== 'none', `bg=${g.bar.bg} α=${a} backdrop-filter=${g.bar.bd}`);

    // D4 = 桌面全量清点（兼职回归闸）：canvas 区内**凡可见**的控件逐个过同一套
    // 纸面判据 —— 右侧/顶部/环带任何一枚被这批误伤（透明化/裁掉/被压）都会在此露头。
    const swept = g.sweep.map((c) => [c, shapeProblem(c, g.vw, g.vh, null)]);
    record(`${tag}-D4`, '桌面 canvas 区内所有可见控件：非零尺寸 / 在视口内 / 可点（回归清点）',
      swept.length > 0 && swept.every(([, p]) => p === null),
      `n=${swept.length} ` + swept.map(([c, p]) => `${c.cls || c.name}:${p || 'ok'}`).join(' | '));

    record(`${tag}-D5`, '无 JS 异常（打桩未覆盖的 404 不计）', session.errors.length === 0, `errors=${JSON.stringify(session.errors)}`);
    await ctx.close();
  }
} finally {
  await browser.close();
  writeFileSync(join(OUT, 'canvas-controls-dock-clearance.json'), JSON.stringify({ browser: label, webRoot: webRoot(), rows, readings }, null, 2));
}

console.log(`\n${pass}/${pass + fail} PASS, ${fail} FAIL  (browser=${label}, webRoot=${webRoot()})`);
console.log(`json: ${join(OUT, 'canvas-controls-dock-clearance.json')}`);
if (fail > 0) {
  console.error(`\ncanvas 底部控件行闸判红 ${fail} 项：控件必须在视口内、不被 dock 覆盖、非零尺寸、中心可点。`);
  assert.fail(`${fail} canvas control(s) not visible-and-hittable`);
}
