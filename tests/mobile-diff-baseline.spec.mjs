#!/usr/bin/env node
// mobile-diff-baseline.spec.mjs — 共享差异组件（js/diffView）**手机真全屏形态**
// 验收基准（2026-11-19 迁移批，作者裁定「手机diff 按推荐」）。
//
// 验收形态铁律：375×812 真全屏页（禁用弹窗/内嵌面板）；本 spec 断言的是
// **真实前端源码树**（WEB_DIR，缺省 = 本仓工作树）在触摸上下文里渲染的组件。
//
// 逐条对报告 D1–D9 与硬不变式：
//   M0  载入健康：零 pageerror、零公网请求（同源 vendor 装载）
//   M1  真全屏形态（非弹窗）：视口 375×812、挂载根在 #app 内、无 dialog/overlay
//   M2  D2 行号：.cm-lineNumbers 行号节点 > 0
//   M3  D3 词级差异：.cm-changedText 高亮段 > 0
//   M4  D1 折行缺省开：编辑面 scroller scrollWidth ≤ clientWidth + 1
//   M5  D8 未变段折叠：.cm-collapsedLines 在场；VCS 元信息不进正文
//   M6  D6 触控目标 ≥44px：逐块接受/拒绝钮、折叠带、折行开关
//   M7  D7 字号：正文 computed font-size ≥13px、行高比 ≥1.4
//   M8  D5 对比度：亮/暗两档，正文对复合底色的对比度 ≥4.5:1（getComputedStyle 实测）
//   M9  ask 面逐块接受/拒绝接线（组件核心交互）：点击 → 回调带对 hunkId
//   M10 D9 虚拟化：文档全量行数 vs 已挂 DOM 行数（不再一次性进 DOM）
//   M11 D4 让位带：审阅头段顶距 ≥56px（与 delegate 面同一条常量）
//
// 运行：node tests/mobile-diff-baseline.spec.mjs
// 可选 env：WEB_DIR（红面 = 指向基线树）、PORT（默认 8311）、SHOTS_DIR。

import { chromium } from 'playwright';
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const WEB_DIR = process.env.WEB_DIR || resolve(REPO, 'src/main/resources/web');
const HARNESS_SRC = resolve(HERE, 'fixtures/mobile-diff-baseline/harness.html');
const PORT = Number(process.env.PORT || 8311);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(REPO, 'evidence/mobile-diff-baseline/shots');
if (process.env.SHOTS_DIR || !process.env.SKIP_SHOTS) mkdirSync(SHOTS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/__harness.html') {
    res.writeHead(200, { 'Content-Type': MIME['.html'] });
    res.end(readFileSync(HARNESS_SRC));
    return;
  }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR) || !existsSync(file)) { res.writeHead(404); res.end('not found'); return; }
  res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
};

// 对比度工具（页面内注入）：相对亮度 + 复合。
const CONTRAST_FN = `
window._srgb=function(c){c/=255;return c<=0.03928?c/12.92:Math.pow((c+0.055)/1.055,2.4);};
window._lum=function(r,g,b){return 0.2126*window._srgb(r)+0.7152*window._srgb(g)+0.0722*window._srgb(b);};
window._parse=function(s){const m=s.match(/rgba?\\(([^)]+)\\)/);if(!m)return null;const p=m[1].split(',').map(x=>parseFloat(x));return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1};};
window._comp=function(fg,bg){const a=fg.a;return {r:fg.r*a+bg.r*(1-a),g:fg.g*a+bg.g*(1-a),b:fg.b*a+bg.b*(1-a),a:1};};
window._ratio=function(f,b){const l1=window._lum(f.r,f.g,f.b),l2=window._lum(b.r,b.g,b.b);const hi=Math.max(l1,l2),lo=Math.min(l1,l2);return (hi+0.05)/(lo+0.05);};
window._parseHex=function(s){const m=s.match(/#([0-9a-f]{6})/i);if(!m)return null;const n=parseInt(m[1],16);return {r:(n>>16)&255,g:(n>>8)&255,b:n&255,a:1};};
window._color=function(s){return window._parse(s)||window._parseHex(s);};
`;

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true,
    colorScheme: 'light',
  });
  const page = await ctx.newPage();
  const errors = [];
  const external = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  page.on('request', (r) => {
    const u = r.url();
    if (!u.startsWith(BASE) && !u.startsWith('data:') && !u.startsWith('blob:')) external.push(u);
  });
  await page.addInitScript({ content: CONTRAST_FN });
  await page.goto(BASE + '/__harness.html');
  await page.waitForFunction('window.__ready === true', null, { timeout: 20000 });

  ok('M0a 载入健康：零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  ok('M0b 离线可用：零非本机请求（随包分发 vendor，同源装载）', external.length === 0, external.slice(0, 3).join(' | '));

  // ── M1 真全屏形态 ────────────────────────────────────────────────────────
  const form = await page.evaluate(() => ({
    w: window.innerWidth, h: window.innerHeight,
    dialogs: document.querySelectorAll('dialog,[role="dialog"],.modal,.overlay').length,
    appW: document.getElementById('app').getBoundingClientRect().width,
    appH: document.getElementById('app').getBoundingClientRect().height,
    mounted: !!document.querySelector('#page-delegate .nf-dv .cm-editor'),
  }));
  ok('M1 真全屏手机形态（375×812，非弹窗/内嵌面板）',
    form.w === 375 && form.h === 812 && form.dialogs === 0 && form.appW === 375 && form.appH === 812 && form.mounted,
    JSON.stringify(form));

  // ── M2 行号（D2）────────────────────────────────────────────────────────
  const ln = await page.evaluate(() => ({
    gutters: document.querySelectorAll('#page-delegate .cm-gutters').length,
    nums: document.querySelectorAll('#page-delegate .cm-lineNumbers .cm-gutterElement').length,
  }));
  ok('M2 D2 行号：行号栏 + 行号节点在场', ln.gutters > 0 && ln.nums > 0, JSON.stringify(ln));

  // ── M3 词级差异（D3）────────────────────────────────────────────────────
  const word = await page.evaluate(() => document.querySelectorAll('#page-delegate .cm-changedText').length);
  ok('M3 D3 词级（行内）差异高亮段 > 0', word > 0, String(word));

  // ── M4 折行缺省（D1）────────────────────────────────────────────────────
  const wrap = await page.evaluate(() => {
    const sc = document.querySelector('#page-delegate .cm-scroller');
    const btn = document.querySelector('#page-delegate .nf-dv-wrapbtn');
    return { sw: sc.scrollWidth, cw: sc.clientWidth, pressed: btn && btn.getAttribute('aria-pressed') };
  });
  ok('M4 D1 折行缺省开（长行不溢出横滚）',
    wrap.sw <= wrap.cw + 1 && wrap.pressed === 'true',
    `scrollWidth ${wrap.sw} / clientWidth ${wrap.cw} / aria-pressed ${wrap.pressed}`);

  // ── M5 折叠 + VCS 元信息丢弃（D8）───────────────────────────────────────
  const fold = await page.evaluate(() => {
    const text = (document.querySelector('#page-delegate .cm-content') || {}).textContent || '';
    return {
      foldWid: document.querySelectorAll('#page-delegate .cm-collapsedLines').length,
      hasGit: text.includes('diff --git') || text.includes('index 1a2b3c4') || text.includes('+++ b/src'),
    };
  });
  ok('M5 D8 未变段折叠在场 + VCS 元信息不进正文',
    fold.foldWid > 0 && fold.hasGit === false, JSON.stringify(fold));

  await page.screenshot({ path: join(SHOTS_DIR, 'm-delegate-light.png') });

  // ── M6 触控目标 ≥44（D6）───────────────────────────────────────────────
  await page.evaluate(() => window.__showPage('review'));
  await page.waitForTimeout(120);
  const hit = await page.evaluate(() => {
    const hs = (sel) => [...document.querySelectorAll(sel)].map((e) => e.getBoundingClientRect().height);
    return {
      chunk: hs('#page-review .nf-dv-chunkbtn'),
      fold: hs('#page-review .cm-collapsedLines'),
      wrap: hs('#page-review .nf-dv-wrapbtn'),
    };
  });
  const minHit = (arr) => (arr.length ? Math.min(...arr) : NaN);
  ok('M6 D6 触控目标 ≥44px（逐块钮 / 折叠带 / 折行开关）',
    hit.chunk.length > 0 && minHit(hit.chunk) >= 44 && hit.fold.length > 0 && minHit(hit.fold) >= 44
      && minHit(hit.wrap) >= 44,
    `chunk ${hit.chunk} fold ${hit.fold} wrap ${hit.wrap}`);

  // ── M7 字号（D7）───────────────────────────────────────────────────────
  const fs = await page.evaluate(() => {
    const el = document.querySelector('#page-delegate .cm-content');
    const cs = getComputedStyle(el);
    return { size: parseFloat(cs.fontSize), lh: parseFloat(cs.lineHeight) / parseFloat(cs.fontSize) };
  });
  ok('M7 D7 正文字号 ≥13px 且行高比 ≥1.4', fs.size >= 13 && fs.lh >= 1.4, JSON.stringify(fs));

  // ── M8 对比度（D5，亮/暗两档实测）───────────────────────────────────────
  const measureContrast = async () => page.evaluate(() => {
    const editor = document.querySelector('#page-delegate .cm-editor');
    const baseBg = _color(getComputedStyle(editor).backgroundColor);
    const fg = _color(getComputedStyle(editor).color);
    const rows = [
      ['add', document.querySelector('#page-delegate .cm-changedLine')],
      ['del', document.querySelector('#page-delegate .cm-deletedChunk')],
      ['ctx', document.querySelector('#page-delegate .cm-line')],
    ];
    const out = {};
    for (const [k, el] of rows) {
      if (!el) { out[k] = null; continue; }
      const bg = _color(getComputedStyle(el).backgroundColor);
      const comp = bg && bg.a < 1 ? _comp(bg, baseBg) : (bg || baseBg);
      out[k] = +_ratio(fg, comp).toFixed(2);
    }
    return out;
  });
  const light = await measureContrast();
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForTimeout(120);
  const dark = await measureContrast();
  const allPass = (m) => Object.values(m).every((v) => v == null || v >= 4.5);
  ok('M8 D5 对比度 ≥4.5:1（亮档：新增/删除/未变行）', allPass(light), JSON.stringify(light));
  ok('M8b D5 对比度 ≥4.5:1（暗档：新增/删除/未变行）', allPass(dark), JSON.stringify(dark));
  await page.screenshot({ path: join(SHOTS_DIR, 'm-delegate-dark.png') });
  await page.emulateMedia({ colorScheme: 'light' });

  // ── M9 逐块接受/拒绝接线 ────────────────────────────────────────────────
  await page.waitForTimeout(120);
  await page.evaluate(() => window.__resetChunkActions());
  const acceptBtn = page.locator('#page-review .nf-dv-chunkbtn-accept').first();
  await acceptBtn.click();
  await page.waitForTimeout(80);
  let actions = await page.evaluate(() => window.__chunkActions());
  const acceptOk = actions.length === 1 && actions[0].action === 'accept' && /^h\d$/.test(actions[0].hunkId);
  await page.evaluate(() => window.__resetChunkActions());
  const rejectBtn = page.locator('#page-review .nf-dv-chunkbtn-reject').first();
  await rejectBtn.click();
  await page.waitForTimeout(80);
  actions = await page.evaluate(() => window.__chunkActions());
  const rejectOk = actions.length === 1 && actions[0].action === 'reject';
  ok('M9 ask 面逐块接受/拒绝接线（点击 → 回调带 hunkId/action）',
    acceptOk && rejectOk, JSON.stringify(actions));
  await page.screenshot({ path: join(SHOTS_DIR, 'm-review-light.png') });

  // ── M10 虚拟化（D9）────────────────────────────────────────────────────
  const virt = await page.evaluate(() => {
    const api = window.__api('delegate');
    return {
      docLines: api.view.state.doc.lines,
      mounted: document.querySelectorAll('#page-delegate .cm-line').length,
    };
  });
  ok('M10 D9 虚拟化：文档全量行数 > 已挂 DOM 行数（不再一次性进 DOM）',
    virt.docLines > virt.mounted + 5, JSON.stringify(virt));

  // ── M11 让位带（D4）───────────────────────────────────────────────────
  const band = await page.evaluate(() => {
    const head = document.querySelector('#page-review .nf-ar-head');
    const shell = document.querySelector('#page-review .nf-ar-shell');
    const top = shell.getBoundingClientRect().top - document.getElementById('page-review').getBoundingClientRect().top;
    return { headOffset: head.getBoundingClientRect().top - shell.getBoundingClientRect().top, shellPad: getComputedStyle(shell).paddingTop, shellTop: top };
  });
  ok('M11 D4 ask 面让位带：审阅头段顶距 ≥56px',
    band.headOffset >= 56 || parseFloat(band.shellPad) >= 56, JSON.stringify(band));

  ok('M0c 收尾零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
} catch (e) {
  ok('SPEC 驱动中断（红面：组件未接线/关键元素缺失）', false, String((e && e.message) || e).slice(0, 240));
} finally {
  await browser.close();
  server.close();
}
console.log(`\n${fail === 0 ? '✅' : '❌'} mobile-diff-baseline: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
