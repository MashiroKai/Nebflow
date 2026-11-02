#!/usr/bin/env node
// mobile-canvas-ring-tabbar-shots.mjs — 手机端 canvas 顶部标签栏重做批证据抓取
// （作者令 2026-11-02 07:22：①图标 bug 修复 ②常驻展开的横向圆环 ③底部小点
// 指示器 ④右上堆叠钮 → 卡片翻页视图）。
//
// 面板（375 × 812，暗/亮双主题，deviceScaleFactor 2）：
//   a1-toolbar-icons      左右两颗悬浮钮图标正常渲染（修前空圆 before 对照见批文档）
//   b1-ring-2tabs         2 标签：当前签居中 + 左右各一枚邻签
//   b2-ring-3tabs         3 标签
//   b3-ring-5tabs         5 标签（环外签隐藏但位姿仍算）
//   c1-dots-1tab          1 标签 ⇒ 小点不显示
//   c2-dots-2tabs         2 标签 ⇒ 2 点
//   c3-dots-3tabs         3 标签 ⇒ 3 点（当前点更亮更大）
//   d1-drag-midframe      长按放大 + 横拖中帧（drag 未松手）
//   e1-deck-cards         卡叠翻页视图（卡标题 / 缩略预览 / 卡内 ✕）
//   e2-deck-paged         纵拖翻页后（前景卡换人，卡叠保持展开）
// 每帧附 JSON 几何探针；收尾钉零 console error。
//
// 用法：node tests/mobile-canvas-ring-tabbar-shots.mjs [outDir]
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || join(HERE, '..', 'evidence', '20261102_mobile-canvas-ring-tabbar__delegate-nebflow-807819e2');
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const SID = 'ring-tabbar-shots';
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
  await sleep(700);
}

const DOC = (i, title) => [`rk-${i}`, title, `rk-type-${i}`];

async function probe(page) {
  return page.evaluate(() => {
    const q = (s) => document.querySelector(s);
    const box = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
    const strip = q('.mf-strip');
    const dots = q('.mf-dots');
    const chips = [...document.querySelectorAll('.mf-strip-chip')].map((c) => ({
      id: c.dataset.tabId, on: c.classList.contains('on'), far: c.classList.contains('is-far'),
      ...box(c),
      rdx: c.style.getPropertyValue('--rdx').trim(),
      rdy: c.style.getPropertyValue('--rdy').trim(),
      rsc: c.style.getPropertyValue('--rsc').trim(),
      op: +getComputedStyle(c).opacity,
      blur: c.style.getPropertyValue('--rbl').trim(),
    }));
    const dotEls = dots ? [...dots.querySelectorAll('.mf-dot')] : [];
    return {
      strip: { ...box(strip), h72: getComputedStyle(strip).height, fade: strip.classList.contains('fade') },
      chips,
      count: q('.mf-cv-count')?.textContent || '',
      countSrOnly: (() => { const b = q('.mf-cv-count').getBoundingClientRect(); return b.width <= 1 && b.height <= 1; })(),
      dots: dots ? {
        hidden: dots.hidden, grip: dots.classList.contains('is-grip'), ...box(dots),
        n: dotEls.length,
        onIdx: dotEls.findIndex((d) => d.classList.contains('on')),
        sizes: dotEls.map((d) => +d.getBoundingClientRect().width.toFixed(1)),
      } : null,
      icons: {
        close: q('.mf-cv-close')?.querySelectorAll('svg').length || 0,
        stack: q('.mf-cv-stack')?.querySelectorAll('svg').length || 0,
        leftoverI: document.querySelectorAll('.mf-cv-toolbar i[data-lucide]').length,
      },
      dockTop: Math.round(q('#mobile-dock').getBoundingClientRect().top),
    };
  });
}

async function shot(page, name, scheme) {
  const p = await probe(page);
  writeFileSync(join(OUT, `${name}-${scheme}.json`), JSON.stringify(p, null, 2));
  await page.screenshot({ path: join(OUT, `${name}-${scheme}.png`) });
  console.log(`shot ${name}-${scheme}: chips=${p.chips.length} on=${p.chips.filter((c) => c.on).map((c) => c.id).join(',')} dots=${p.dots ? p.dots.n : '-'} icons=${JSON.stringify(p.icons)}`);
}

for (const scheme of ['dark', 'light']) {
  const { ctx, page } = await makePage(scheme);

  // a1 / b1：2 标签（图标正常渲染 + 环 2 签 + 2 点）。
  await seed(page, [DOC(1, '单文件画像的现状形态'), DOC(2, '背景任务设计')]);
  await shot(page, 'a1-toolbar-icons', scheme);
  await shot(page, 'b1-ring-2tabs', scheme);
  await shot(page, 'c2-dots-2tabs', scheme);

  // b2 / c3：3 标签（3 点、当前点更大更亮）。
  await seed(page, [DOC(1, '单文件画像的现状形态'), DOC(2, '背景任务设计'), DOC(3, '7451 lugi 命名规约汇编（超长标题截断档）')]);
  await shot(page, 'b2-ring-3tabs', scheme);
  await shot(page, 'c3-dots-3tabs', scheme);

  // b3：5 标签（环外签隐藏、位姿仍算；小点仍恒 3 枚 = 省略策略）。
  await seed(page, [DOC(1, '环一'), DOC(2, '环二'), DOC(3, '环三'), DOC(4, '环四'), DOC(5, '环五')]);
  await shot(page, 'b3-ring-5tabs', scheme);

  // c1：1 标签（小点不显示）。
  await seed(page, [DOC(9, '独占一签')]);
  await shot(page, 'c1-dots-1tab', scheme);

  // d1：长按放大 + 横拖中帧（未松手，逐签已落地）。
  await seed(page, [DOC(1, '拖一'), DOC(2, '拖二'), DOC(3, '拖三'), DOC(4, '拖四'), DOC(5, '拖五')]);
  const dotsBox = await page.locator('.mf-dots').boundingBox();
  const cx = dotsBox.x + dotsBox.width / 2;
  const cy = dotsBox.y + dotsBox.height / 2;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.waitForTimeout(380);                 // 过长按阈值（260ms）→ .is-grip
  for (let i = 1; i <= 8; i++) {
    await page.mouse.move(cx - (68 * i) / 8, cy);
    await page.waitForTimeout(20);
  }
  await sleep(320);                               // 位姿过渡落定，grip 仍持
  await shot(page, 'd1-drag-midframe', scheme);
  await page.mouse.up();
  await sleep(260);

  // e1 / e2：堆叠卡视图 + 纵拖翻页后。
  await page.locator('.mf-cv-stack').click();
  await sleep(700);
  const deck = await page.evaluate(() => ({
    count: document.querySelector('.mf-stack-count')?.textContent || '',
    cards: [...document.querySelectorAll('.mf-deck-card')].map((c) => {
      const r = c.getBoundingClientRect();
      return {
        id: c.dataset.tabId, on: c.classList.contains('on'),
        y: Math.round(r.y), h: Math.round(r.height),
        dty: c.style.getPropertyValue('--dty').trim(),
        dsc: c.style.getPropertyValue('--dsc').trim(),
        op: +getComputedStyle(c).opacity,
        icon: c.querySelectorAll('.mf-deck-icon svg').length,
        thumb: c.querySelectorAll('.mf-deck-thumb > *').length,
        close: c.querySelectorAll('.mf-deck-x').length,
        title: c.querySelector('.mf-deck-title')?.textContent || '',
      };
    }),
  }));
  writeFileSync(join(OUT, `e1-deck-cards-${scheme}.json`), JSON.stringify(deck, null, 2));
  await page.screenshot({ path: join(OUT, `e1-deck-cards-${scheme}.png`) });
  console.log(`shot e1-deck-cards-${scheme}: ${deck.count} cards=${deck.cards.length} on=${deck.cards.find((c) => c.on)?.id}`);
  // 纵拖上滑翻一页（过 56px 阈 + 松手吸附）。
  const stage = await page.locator('.mf-deck').boundingBox();
  const sx = stage.x + stage.width / 2;
  const sy = stage.y + stage.height / 2;
  await page.mouse.move(sx, sy);
  await page.mouse.down();
  for (let i = 1; i <= 10; i++) { await page.mouse.move(sx, sy - (120 * i) / 10); await page.waitForTimeout(16); }
  await page.mouse.up();
  await sleep(520);
  const paged = await page.evaluate(() => ({
    on: document.querySelector('.mf-deck-card.on')?.dataset.tabId || '',
    chipOn: document.querySelector('.mf-strip-chip.on')?.dataset.tabId || '',
    stackOpen: !document.querySelector('.mf-stack-view').hidden,
  }));
  writeFileSync(join(OUT, `e2-deck-paged-${scheme}.json`), JSON.stringify(paged, null, 2));
  await page.screenshot({ path: join(OUT, `e2-deck-paged-${scheme}.png`) });
  console.log(`shot e2-deck-paged-${scheme}: ${JSON.stringify(paged)}`);
  // 退出回标签视图（✕），留下终帧。
  await page.locator('.mf-stack-x').click();
  await sleep(520);
  await shot(page, 'e3-back-to-tabs', scheme);

  await ctx.close();
}

await browser.close();
console.log(`\nconsole errors: ${consoleErrors.length}`);
if (consoleErrors.length) console.log(consoleErrors.slice(0, 8).join('\n'));
console.log(`DONE → ${OUT}`);
process.exit(consoleErrors.length ? 1 : 0);
