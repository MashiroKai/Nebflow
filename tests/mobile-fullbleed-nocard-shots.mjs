#!/usr/bin/env node
// mobile-fullbleed-nocard-shots.mjs — 「整个窗口就是它」判据腿（口径纠正批 2026-11-05 20:00）。
//
// 作者口径（逐字）：「手机端的待办还是没有铺满，像是这个窗口的卡片一样，这整个窗口
// 都是它的，不用做成一个卡片。」⇒ 判据不是「卡片占满宽高」，而是**不存在那一层卡**：
// 卡片容器被移除 / 降级为无边框宿主。本腿给两件事：
//   ① 读数：卡片链每个节点（.tfl-card / .tfl-panel / .tfl-list-wrap / .tfl-list /
//      页面体 / 页面壳）的计算样式 —— border / border-radius / box-shadow /
//      background —— 逐项判定「还剩几层皮」；
//   ② 截图：改前 / 改后**同一滚动位置**成对拍（top=0 与 scrollTop=280 各一对），
//      外加上作者的列表口径（8 行、暗档）。
//
// 用法：
//   node tests/mobile-fullbleed-nocard-shots.mjs [outDir]
//   TODOFULL_WEB_ROOT=/tmp/xxx SHOT_TAG=before node tests/mobile-fullbleed-nocard-shots.mjs <outDir>
//
// 数据面 = routeWebSocket 帧夹具 + todoStore.__seed；静态面 = page.route 注磁盘 web 树。
// 零后端、零 sbt、不碰 :6138。

import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.TODOFULL_WEB_ROOT
  ? normalize(resolve(process.env.TODOFULL_WEB_ROOT))
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
// dist 脸：bundle 里没有 /js/*.js 模块路径，但要给夹具留一条 __seed 通道 —— 
// TODOFULL_WEB_FALLBACK 指向源树，dist 里没有的文件回落到源树取。
const FALLBACK = process.env.TODOFULL_WEB_FALLBACK
  ? normalize(resolve(process.env.TODOFULL_WEB_FALLBACK)) : null;
const TAG = process.env.SHOT_TAG || 'after';
const OUT = process.argv[2] || `/tmp/todo-nocard-shots/${TAG}`;
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'nocard-session';
const T0 = Date.now();

/* ── 夹具：与作者实拍同口径（8 条待办、含时刻） ── */
function todoItems() {
  const now = Date.now();
  const rows = Array.from({ length: 14 }, (_, i) => ['待办事项 ' + (i + 2), i + 1]);
  return rows.map(([name, h], i) => ({
    id: `nc-${i}`, tagId: null, name, detail: '', due: now + h * 3600_000,
    priority: i === 0 ? 3 : 0, createdAt: now - (9 - i) * 3600_000,
  }));
}
const WS_SEED = process.env.NOCARD_SEED === 'ws';
function wsTasks() {
  if (!WS_SEED) return schedTasks();
  return [
    ...schedTasks(),
    ...todoItems().map((i) => ({
      id: i.id, content: i.name, kind: 'todo', detail: i.detail, priority: i.priority,
      triggerAt: i.due, enabled: true, triggered: false, sessionId: SID,
    })),
  ];
}
function schedTasks() {
  return [
    { id: 'st-1', content: '每小时巡检构建', triggerAt: T0 + 3_600_000, repeat: 'hourly', enabled: true, triggered: false, lastTriggered: T0 - 3_600_000 },
    { id: 'st-2', content: '每天晨报摘要', triggerAt: T0 + 86_400_000, repeat: 'daily', enabled: true, triggered: false },
    { id: 'st-3', content: '每周复盘提醒', triggerAt: T0 + 7 * 86_400_000, repeat: 'weekly', enabled: true, triggered: false },
    { id: 'st-4', content: '单次：回看第 3 节', triggerAt: T0 + 1_800_000, enabled: true, triggered: false },
  ];
}

/* ── 页内探针：卡片链逐节点计算样式 ── */
const PROBE = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    const f = (v) => +v.toFixed(2);
    return { x: f(b.x), y: f(b.y), w: f(b.width), h: f(b.height), right: f(b.right), bottom: f(b.bottom) }; };
  const desc = (el) => {
    if (!el) return null;
    const cls = typeof el.className === 'string' && el.className.trim()
      ? '.' + el.className.trim().split(/\\s+/).filter(Boolean).join('.') : '';
    return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + cls;
  };
  const skin = (el) => {
    if (!el) return null;
    const cs = getComputedStyle(el);
    const px = (v) => parseFloat(v) || 0;
    const bw = Math.max(px(cs.borderTopWidth), px(cs.borderRightWidth), px(cs.borderBottomWidth), px(cs.borderLeftWidth));
    const radii = [cs.borderTopLeftRadius, cs.borderTopRightRadius, cs.borderBottomRightRadius, cs.borderBottomLeftRadius];
    const rmax = Math.max(...radii.map(px));
    const bg = cs.backgroundColor;
    const bgTransparent = bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent';
    const shadow = cs.boxShadow;
    const shadowNone = !shadow || shadow === 'none';
    // 「还剩一层皮」判据：描边 / 圆角 / 投影 / 有底 任一非零即算卡皮
    const peeling = (bw > 0 ? 1 : 0) + (rmax > 0 ? 1 : 0) + (shadowNone ? 0 : 1) + (bgTransparent ? 0 : 1);
    return {
      el: desc(el), rect: R(el),
      border: { widths: [cs.borderTopWidth, cs.borderRightWidth, cs.borderBottomWidth, cs.borderLeftWidth], color: cs.borderTopColor },
      radius: radii, boxShadow: shadow, background: bg, backgroundImage: cs.backgroundImage,
      position: cs.position, overflow: cs.overflow,
      verdict: peeling === 0 ? 'no-skin' : 'SKIN(' + peeling + '/4)',
    };
  };
  const page = document.querySelector('.mobile-page.live');
  const q = (s) => (page ? page.querySelector(s) : null);
  const card = q('.tfl-card') || q('#reminder-panel');
  const chain = [];
  let n = card;
  while (n && n !== document.body.parentElement) { chain.push(n); n = n.parentElement; }
  const chainOut = chain.map(skin).filter(Boolean);
  const rows = page ? Array.from(page.querySelectorAll('.tfl-row')).slice(0, 2).map(skin) : [];
  // 行与行之间那层「卡」是否还在：卡片自身与页面底色是否一致
  const pageCs = page ? getComputedStyle(page) : null;
  return {
    pageBg: pageCs ? pageCs.backgroundColor : null,
    bodyBg: getComputedStyle(document.body).backgroundColor,
    scroll: (() => {
      const s = q('.tfl-list') || q('.reminder-panel-body');
      return s ? { scrollTop: s.scrollTop, scrollHeight: s.scrollHeight, clientHeight: s.clientHeight } : null;
    })(),
    card: skin(card),
    chain: chainOut,
    firstRows: rows,
    /** 卡片链里有几层「卡皮」——作者要的 0 */
    skinLayers: chainOut.filter((x) => x.verdict !== 'no-skin').map((x) => x.el + ' → ' + x.verdict),
  };
})()`;

const browser = await chromium.launch();
const errors = [];

async function boot() {
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, colorScheme: 'dark', deviceScaleFactor: 2, hasTouch: true, isMobile: true,
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('nebflow_theme', 'dark');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    const send = (f) => route.fulfill({
      status: 200, contentType: MIME[extname(f)] || 'application/octet-stream', body: readFileSync(f),
    });
    try { return send(file); } catch {
      if (FALLBACK) {
        const alt = normalize(join(FALLBACK, p));
        if (alt.startsWith(FALLBACK)) { try { return send(alt); } catch { /* fallthrough */ } }
      }
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const tasks = wsTasks();
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    const sendTasks = () => ws.send(JSON.stringify({ type: 'scheduledTaskList', tasks }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'listScheduledTasks') sendTasks();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    try {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
    } catch { return !!document.querySelector('#mobile-dock'); }
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(WS_SEED ? 1500 : 400);
  return { ctx, page };
}

async function openTasksPage(page) {
  await page.locator("[data-chrome='tasks']").click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="tasks"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 8000 });
  await page.waitForTimeout(900);
}
async function openReminderPage(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForTimeout(350);
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="reminder"]').click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="reminder"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 8000 });
  await page.waitForTimeout(900);
}
async function seed(page) {
  try {
    await page.evaluate((items) => import('/js/todoStore.js').then((m) => { m.todoStore.__seed({ itemList: items }); }), todoItems());
  } catch { /* dist 脸（bundle）无 /js/todoStore.js：数据面走 WS 帧 */ }
  await page.waitForTimeout(400);
}
async function scrollTo(page, top) {
  await page.evaluate((t) => {
    const p = document.querySelector('.mobile-page.live');
    const s = p && (p.querySelector('.tfl-list') || p.querySelector('.reminder-panel-body'));
    if (s) s.scrollTop = t;
  }, top);
  await page.waitForTimeout(350);
  return page.evaluate(() => {
    const p = document.querySelector('.mobile-page.live');
    const s = p && (p.querySelector('.tfl-list') || p.querySelector('.reminder-panel-body'));
    return s ? { scrollTop: s.scrollTop, scrollHeight: s.scrollHeight, clientHeight: s.clientHeight } : null;
  });
}

const reports = { tag: TAG, webRoot: WEB };
async function shot(page, name) { await page.screenshot({ path: join(OUT, `${name}.png`) }); }
async function probe(page, name) { reports[name] = await page.evaluate(PROBE); }

/* ── 待办页：top=0 与 scrollTop=280 各一对（改前/改后同一位置） ── */
{
  const { ctx, page } = await boot();
  await seed(page);
  await openTasksPage(page);
  await scrollTo(page, 0);
  await shot(page, 'tasks-01-top');
  await probe(page, 'tasks-top');

  const sc = await scrollTo(page, 280);
  reports['tasks-scroll-state'] = sc;
  await shot(page, 'tasks-02-scrolled');
  await probe(page, 'tasks-scrolled');
  await ctx.close();
}

/* ── 定时任务页：top=0 ── */
{
  const { ctx, page } = await boot();
  await openReminderPage(page);
  await scrollTo(page, 0);
  await shot(page, 'reminder-01-top');
  await probe(page, 'reminder-top');
  await ctx.close();
}

reports.errors = errors;
writeFileSync(join(OUT, 'probe.json'), JSON.stringify(reports, null, 1));
await browser.close();

const fmt = (x) => (x == null ? '—' : x);
for (const k of ['tasks-top', 'tasks-scrolled', 'reminder-top']) {
  const r = reports[k];
  if (!r) continue;
  console.log(`\n[${TAG}] ${k}  pageBg=${fmt(r.pageBg)} bodyBg=${fmt(r.bodyBg)} scroll=${JSON.stringify(r.scroll)}`);
  console.log(`  card            ${r.card ? r.card.el + '  ' + r.card.verdict : '—'}`);
  for (const c of r.chain) {
    console.log(`   · ${c.el.padEnd(40)} ${c.verdict.padEnd(11)} border=${c.border.widths.join('/')} radius=${c.radius.join('/')} shadow=${c.boxShadow} bg=${c.background}`);
  }
  console.log(`  skinLayers=${JSON.stringify(r.skinLayers)}`);
}
if (errors.length) console.log('\nconsole/page errors:', errors.slice(0, 8));
console.log(`\nshots → ${OUT}`);
