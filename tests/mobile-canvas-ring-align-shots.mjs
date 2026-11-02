#!/usr/bin/env node
// mobile-canvas-ring-align-shots.mjs — 对齐修正批证据抓取（作者令 2026-11-02 09:00）
//
// 覆盖（375 × 812，暗/亮双主题，deviceScaleFactor 2）：
//   a1-ring-2tabs     2 签（中签 + 一枚邻签）
//   a2-ring-3tabs     3 签（中签居中、左右邻签同线）
//   a3-ring-5tabs     5 签（|k|≥2 归零 = 绕到圈外）
//   a4-ring-8tabs     8 签 + 超长标题（验渐隐无省略号）
//   a5-long-titles    3 签全超长标题（三签都溢出 ⇒ 三签都走 mask 淡出）
//   a6-center-zoom    中签放大对照（同视口下点邻签换中签，看放大比）
//   a7-drag-midframe  长按放大 + 横拖中帧（位姿过渡中途）
//   a8-expanded       点活签展开全名（.is-expanded 档 + 零省略号）
//   a9-dark-fade      暗色下边缘极限（2 签推到边缘看淡化/模糊层次）
// 每帧附 JSON 几何探针（cy 极差 / 中签 vs 邻签尺寸比 / textOverflow / mask / 热区）。
// 收尾钉零 console error。
//
// 改前对照不在此脚本生成：直接从上一批 evidence 目录取同视口帧（见批文档）。
//
// 用法：node tests/mobile-canvas-ring-align-shots.mjs [outDir]
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || join(HERE, '..', 'evidence', '20261102_canvas-ring-tabbar-align');
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const SID = 'ring-align-shots';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch();
const consoleErrors = [];

async function makePage(colorScheme) {
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, colorScheme, deviceScaleFactor: 2, hasTouch: true,
  });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${colorScheme}] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${colorScheme}] pageerror: ${e.message}`));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: '证据抓取', agentName: 'Nebula' }], activeId: SID }) });
    if (p.includes('/agents/Nebula/agent.json')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: '笨笨' }) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '证据抓取', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await sleep(900);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(700);
  return { ctx, page };
}

/** 清空页签 + 灌入 N 张文档卡（每卡独立 type 避开预览组替换）。 */
async function seed(page, docs) {
  await page.evaluate(async () => {
    const c = await import('/js/canvas.js');
    const n = await import('/js/mobile/navController.js');
    for (const d of n.docTabs()) c.closeTab(d.id);
  });
  await sleep(220);
  await page.evaluate((list) => import('/js/canvas.js').then((canvas) => {
    for (const [id, title, type] of list) canvas.openTab(id, title, { type });
  }), docs);
  await sleep(760);
}

const DOC = (i, title) => [`ra-${i}`, title, `ra-type-${i}`];

async function probe(page) {
  return page.evaluate(() => {
    const q = (s) => document.querySelector(s);
    const box = (el) => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: +r.left.toFixed(1), y: +r.top.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1), cx: +(r.left + r.width / 2).toFixed(2), cy: +(r.top + r.height / 2).toFixed(2) };
    };
    const strip = q('.mf-strip');
    const closeB = q('.mf-cv-close').getBoundingClientRect();
    const stackB = q('.mf-cv-stack').getBoundingClientRect();
    const chips = [...document.querySelectorAll('.mf-strip-chip')].map((c) => {
      const cs = getComputedStyle(c);
      const label = c.querySelector('.mf-strip-label');
      const lcs = label ? getComputedStyle(label) : null;
      const before = getComputedStyle(c, '::before');
      return {
        id: c.dataset.tabId, on: c.classList.contains('on'), far: c.classList.contains('is-far'),
        expanded: c.classList.contains('is-expanded'),
        ...box(c),
        op: +cs.opacity, blur: cs.filter,
        rdx: c.style.getPropertyValue('--rdx').trim(),
        rsc: c.style.getPropertyValue('--rsc').trim(),
        rbl: c.style.getPropertyValue('--rbl').trim(),
        radius: cs.borderRadius, bg: cs.backgroundImage, bgColor: cs.backgroundColor,
        borderTop: cs.borderTopColor, weight: cs.fontWeight, size: cs.fontSize,
        textOverflow: lcs ? lcs.textOverflow : null,
        labelMask: lcs ? (lcs.maskImage || lcs.webkitMaskImage) : null,
        labelW: label ? +label.getBoundingClientRect().width.toFixed(1) : null,
        labelSW: label ? label.scrollWidth : null,
        hitW: +parseFloat(before.width).toFixed(1), hitH: +parseFloat(before.height).toFixed(1),
        text: label ? label.textContent : '', title: c.title,
        ellipsisInText: label ? label.textContent.includes('\u2026') : false,
      };
    });
    const shown = chips.filter((c) => c.op > 0.01);
    const on = chips.find((c) => c.on);
    const side = shown.filter((c) => !c.on).sort((a, b) => b.w - a.w)[0] || null;
    return {
      strip: { ...box(strip), fade: strip.classList.contains('fade'), mask: getComputedStyle(strip).maskImage },
      fabCy: { close: +(closeB.top + closeB.height / 2).toFixed(2), stack: +(stackB.top + stackB.height / 2).toFixed(2) },
      fabEdge: { closeRight: +closeB.right.toFixed(1), stackLeft: +stackB.left.toFixed(1) },
      chips,
      // ── 五条机械断言的同源读数（探针面，T13 在 spec 里硬断言）──
      cySpread: shown.length ? +(Math.max(...shown.map((c) => c.cy)) - Math.min(...shown.map((c) => c.cy))).toFixed(3) : null,
      sizeRatio: on && side ? { onW: on.w, sideW: side.w, wRatio: +(on.w / side.w).toFixed(3), hRatio: +(on.h / side.h).toFixed(3) } : null,
      allClip: chips.every((c) => c.textOverflow === 'clip'),
      anyEllipsis: chips.some((c) => c.ellipsisInText),
      minHit: Math.min(...chips.map((c) => Math.min(c.hitW, c.hitH))),
    };
  });
}

async function shot(page, name, scheme) {
  const p = await probe(page);
  writeFileSync(join(OUT, `${name}-${scheme}.json`), JSON.stringify(p, null, 2));
  await page.screenshot({ path: join(OUT, `${name}-${scheme}.png`) });
  const on = p.chips.find((c) => c.on);
  console.log(`shot ${name}-${scheme}: chips=${p.chips.length} on=${on ? on.id : '-'} cySpread=${p.cySpread} ratio=${p.sizeRatio ? p.sizeRatio.wRatio : '-'} clip=${p.allClip} ellipsis=${p.anyEllipsis} minHit=${p.minHit}`);
}

const LONG = '7451 lugi 命名规约汇编（超长标题档·验零省略号）';

for (const scheme of ['dark', 'light']) {
  const { ctx, page } = await makePage(scheme);

  await seed(page, [DOC(1, '单文件画像的现状形态'), DOC(2, '背景任务设计')]);
  await shot(page, 'a1-ring-2tabs', scheme);

  await seed(page, [DOC(1, '单文件画像的现状形态'), DOC(2, '背景任务设计'), DOC(3, LONG)]);
  await shot(page, 'a2-ring-3tabs', scheme);

  // 5 签：中签给一个稍长标题（读得出「中签更大 + 文字更多」），其余短标题。
  await seed(page, [
    DOC(1, '环一'), DOC(2, '环二'), DOC(3, '环三'), DOC(4, '环四'),
    DOC(5, '7451 命名规约'),
  ]);
  await shot(page, 'a3-ring-5tabs', scheme);

  await seed(page, [1, 2, 3, 4, 5, 6, 7, 8].map((i) => DOC(i, i === 8 ? LONG : `签${i}`)));
  await shot(page, 'a4-ring-8tabs', scheme);

  await seed(page, [
    DOC(1, '7451 lugi 命名规约汇编（超长标题档 A）'),
    DOC(2, '背景任务设计 · 队列与重试（超长标题档 B）'),
    DOC(3, '单文件画像的现状形态与技术债盘点（超长标题档 C）'),
  ]);
  await shot(page, 'a5-long-titles', scheme);

  // 中签放大对照：点邻签 → 它变中签（同视口，看放大后的样子与两侧陪衬）
  const sideChip = await page.evaluate(() => {
    const c = [...document.querySelectorAll('.mf-strip-chip')].find((el) => !el.classList.contains('on') && !el.classList.contains('is-far'));
    return c ? c.dataset.tabId : null;
  });
  if (sideChip) {
    await page.locator(`.mf-strip-chip[data-tab-id="${sideChip}"]`).click();
    await sleep(560);
  }
  await shot(page, 'a6-center-zoom', scheme);

  // 展开全名（点活签 = .is-expanded，2600ms 自动收）
  await page.locator('.mf-strip-chip.on').click();
  await sleep(420);
  await shot(page, 'a8-expanded', scheme);
  await sleep(2600);   // 等自动收（避免跨帧干扰）

  // 拖动中帧：长按小点放大后横拖，指针未松手时抓帧
  await seed(page, [DOC(1, '拖一'), DOC(2, '拖二'), DOC(3, '拖三'), DOC(4, '拖四'), DOC(5, '拖五')]);
  const dotsBox = await page.locator('.mf-dots').boundingBox();
  const dcx = dotsBox.x + dotsBox.width / 2;
  const dcy = dotsBox.y + dotsBox.height / 2;
  await page.mouse.move(dcx, dcy);
  await page.mouse.down();
  await page.waitForTimeout(380);                 // 过长按阈值（260ms）→ .is-grip
  for (let i = 1; i <= 8; i++) {
    await page.mouse.move(dcx - (68 * i) / 8, dcy);
    await page.waitForTimeout(20);
  }
  await sleep(320);                               // 位姿过渡落定，grip 仍持
  await shot(page, 'a7-drag-midframe', scheme);
  await page.mouse.up();
  await sleep(300);

  // 暗色/亮色各给一张「边缘极限」：2 签推到极限（邻签贴 mask 起头）
  await seed(page, [DOC(1, '边一'), DOC(2, LONG)]);
  await shot(page, 'a9-edge-fade', scheme);

  await ctx.close();
}

await browser.close();
console.log(`\nconsole errors: ${consoleErrors.length}`);
if (consoleErrors.length) console.log(consoleErrors.slice(0, 8).join('\n'));
console.log(`DONE → ${OUT}`);
process.exit(consoleErrors.length ? 1 : 0);
