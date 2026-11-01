// usage-mobile-scroll-shots.mjs — 手机档 token 用量面板滚动修复 验红/验绿 实证。
// 用法：node tests/usage-mobile-scroll-shots.mjs <before|after>
//   before = 修复前树（预期 RED：面板/页体均零滚动、底部截断）
//   after  = 修复后树（预期 GREEN：375 深浅主题滚到底 + 桌面回归 + 零 console error）
// 产物 = evidence/20261101_usage-mobile-scroll/shots/*.png + stdout 判定。
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const PHASE = process.argv[2] || 'before';
const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261101_usage-mobile-scroll', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const SID = 'usage-scroll-session-1';

// ── 确定性用量夹具（日期键 = 本地时区 YYYY-MM-DD，与 dateKeyOf 同构）──────
const pad2 = (n) => String(n).padStart(2, '0');
const keyOf = (d) => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
const startOfToday = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d; };
const DAY_MS = 86400000;
const MAGS = [0.25e6, 2.5e6, 25e6, 260e6]; // 命中 cost level 1..4

function dayBucket(dateKey, scale = 1) {
  const seed = [...dateKey].reduce((s, c) => (s * 31 + c.charCodeAt(0)) % 997, 7);
  if (seed < 160) return null; // 部分空日（level-0）
  const mag = MAGS[seed % 4];
  const inp = mag * 0.8 * scale;
  const outp = mag * 0.2 * scale;
  return { key: dateKey, count: 10 + (seed % 40), inputTokens: inp, outputTokens: outp, cacheReadTokens: inp * 0.72, cacheWriteTokens: inp * 0.05, costEquivalent: (inp + outp) / 800 };
}
function inRange(key, from, to) { const ms = new Date(key + 'T00:00:00').getTime(); return ms >= from && ms < to; }

function aggregate(params) {
  const dim = params.get('dim') || '';
  const from = Number(params.get('from') || 0);
  const to = Number(params.get('to') || 0);
  const agentF = params.get('agent') || '';
  const scale = agentF ? 0.45 : 1;
  if (dim === 'day') {
    const buckets = [];
    for (let i = 0; i < 420; i++) {
      const d = startOfToday(); d.setDate(d.getDate() - i);
      const k = keyOf(d);
      if (!inRange(k, from, to)) continue;
      const b = dayBucket(k, scale);
      if (b) buckets.push(b);
    }
    return { buckets, count: buckets.reduce((s, b) => s + b.count, 0), totalInput: buckets.reduce((s, b) => s + b.inputTokens, 0), totalOutput: buckets.reduce((s, b) => s + b.outputTokens, 0), totalCacheRead: buckets.reduce((s, b) => s + b.cacheReadTokens, 0), totalCacheWrite: buckets.reduce((s, b) => s + b.cacheWriteTokens, 0), costEquivalent: buckets.reduce((s, b) => s + b.costEquivalent, 0) };
  }
  if (dim === 'hour') {
    const buckets = [];
    for (let ms = from; ms < to; ms += DAY_MS) {
      const k = keyOf(new Date(ms));
      if (!inRange(k, from, to)) continue;
      for (let h = 0; h < 24; h++) {
        const seed = (h * 37 + Number(k.slice(8, 10))) % 100;
        if (seed < 55) continue;
        const inp = (0.2e6 + seed * 0.09e6) * scale;
        buckets.push({ key: `${k}T${pad2(h)}:00`, count: 1 + (seed % 6), inputTokens: inp, outputTokens: inp * 0.25, cacheReadTokens: inp * 0.7, cacheWriteTokens: inp * 0.04, costEquivalent: inp / 900 });
      }
    }
    return { buckets, count: buckets.length, totalInput: 0, totalOutput: 0, totalCacheRead: 0, totalCacheWrite: 0, costEquivalent: 0 };
  }
  if (dim === 'agent' || dim === 'model' || dim === 'provider') {
    const keys = dim === 'agent' ? ['Nebula', 'delegate-usage-fix', 'delegate-merge', 'qa-probe', 'explorer', 'kernel-1']
      : dim === 'model' ? ['claude-sonnet-4-6', 'claude-opus-4-8', 'gpt-5-2', 'gemini-3-pro', 'glm-5', 'deepseek-v4']
        : ['sapphire', 'kimi', 'glm', 'deepseek'];
    const buckets = keys.map((k, i) => {
      const inp = (2.6e6 + i * 3.1e6) * (i % 2 ? 1 : 0.6) * scale;
      return { key: k, count: 40 - i * 5, inputTokens: inp, outputTokens: inp * 0.22, cacheReadTokens: inp * (0.9 - i * 0.13), cacheWriteTokens: inp * 0.05, costEquivalent: inp / 850 };
    });
    return { buckets, count: buckets.reduce((s, b) => s + b.count, 0), totalInput: 0, totalOutput: 0, totalCacheRead: 0, totalCacheWrite: 0, costEquivalent: 0 };
  }
  const t = aggregate(new URLSearchParams({ dim: 'day', from: String(from), to: String(to) }));
  return { buckets: [], count: t.count, totalInput: t.totalInput, totalOutput: t.totalOutput, totalCacheRead: t.totalCacheRead, totalCacheWrite: t.totalCacheWrite, costEquivalent: t.costEquivalent };
}

const errors = [];
async function boot({ width, height, scheme, touch }) {
  const context = await browser.newContext({
    viewport: { width, height }, colorScheme: scheme, deviceScaleFactor: 2, hasTouch: touch,
  });
  const page = await context.newPage();
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`[${scheme} ${width}] console.error: ${m.text()}`); });
  page.on('pageerror', (e) => errors.push(`[${scheme} ${width}] pageerror: ${e.message}`));
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    // swReg.js 逃生门：mock 源上 /sw.js 取回必败（SW 脚本请求不过 page.route，
    // 打到 localhost:1 = 浏览器层报 "fetching the script" 噪声）。生产引擎
    // /sw.js 在线无此错 —— 这里按 swReg 文件头的降级约定关掉注册。
    localStorage.setItem('stand_sw', '0');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (url.pathname === '/api/usage/aggregate') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(aggregate(url.searchParams)) });
    }
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    }
    if (p.includes('/agents/Nebula/agent.json')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { errors.push(`[harness] static 404: ${p}`); return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: JSON.stringify({ llm: { providers: { sapphire: { models: [{ id: 'claude-sonnet-4-6' }, { id: 'claude-opus-4-8' }] } } } }), configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root 的会话', agentName: 'Nebula' }], folders: [], activeId: SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'getProjects' || m.type === 'listProjects') ws.send(JSON.stringify({ type: 'projects', projects: [] }));
    });
  });
  await page.goto('http://localhost:1/');
  const expectMobile = width <= 768;
  await page.waitForFunction((want) => document.body.classList.contains('mobile-layer') === want, expectMobile, { timeout: 20000 });
  await page.waitForTimeout(1400);
  return { context, page };
}

// 真触屏拖拽（走浏览器输入管线 → 原生滚动；区别于 JS scrollTo）。
async function touchDrag(page, x, y0, y1) {
  const cdp = await (page.context()).newCDPSession(page);
  const steps = 10;
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y: y0 }] });
  for (let i = 1; i <= steps; i++) {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y0 + ((y1 - y0) * i) / steps }] });
    await page.waitForTimeout(12);
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach();
}

const browser = await chromium.launch();
const results = [];
const check = (name, ok, detail) => { results.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  ${detail}`); };

// ══ 手机档 375（深/浅两主题）══════════════════════════════════════════
for (const scheme of ['dark', 'light']) {
  const { context, page } = await boot({ width: 375, height: 812, scheme, touch: true });
  // 工作台 → token 用量 行进详情页。
  await page.waitForSelector('#mobile-dock', { timeout: 15000 });
  await page.click('.dock-key[data-dock="workbench"]');
  await page.waitForSelector('.mobile-page[data-page="workbench"].live', { timeout: 8000 });
  await page.click('.mf-hub-row[data-hub-page="usage"]');
  await page.waitForSelector('.mobile-page[data-page="usage"].live', { timeout: 8000 });
  await page.waitForSelector('#usage-dashboard .ud-cell[data-date]', { timeout: 15000 });
  await page.waitForTimeout(900);
  // 开到最深内容：热力图格 → 24h 曲线 → 明细抽屉（rank + 表格）。
  await page.locator('#usage-grid .ud-cell[data-date]').last().click();
  await page.waitForSelector('#usage-curve #usage-curve-body svg', { timeout: 10000 });
  await page.locator('#usage-curve .ud-drill-open').click();
  await page.waitForSelector('#usage-drill .ud-table tbody tr', { timeout: 10000 });
  await page.waitForTimeout(600);

  const probe = await page.evaluate(() => {
    const dash = document.getElementById('usage-dashboard');
    const body = dash.closest('.mobile-page-body');
    const cs = getComputedStyle(dash);
    const ds = dash.style;
    return {
      dashOverflowY: cs.overflowY,
      dashScroll: { top: dash.scrollTop, ch: dash.clientHeight, sh: dash.scrollHeight },
      bodyScroll: { top: body.scrollTop, ch: body.clientHeight, sh: body.scrollHeight },
      dashFlex: cs.flex, dashMinH: cs.minHeight, inlineFlex: ds.flex || '',
      lastRow: (() => { const rows = dash.querySelectorAll('#usage-drill .ud-table tbody tr'); const r = rows[rows.length - 1]; if (!r) return null; const b = r.getBoundingClientRect(); return { bottom: Math.round(b.bottom), h: Math.round(b.height) }; })(),
      vh: window.innerHeight,
    };
  });
  console.log(`[probe ${scheme}]`, JSON.stringify(probe));
  const canScroll = probe.dashScroll.sh - probe.dashScroll.ch > 40 || probe.bodyScroll.sh - probe.bodyScroll.ch > 40;
  check(`${scheme}: 内容超屏（可滚前提）`, canScroll, `dash surplus=${probe.dashScroll.sh - probe.dashScroll.ch} body surplus=${probe.bodyScroll.sh - probe.bodyScroll.ch}`);
  check(`${scheme}: 滚动容器 overflow-y=auto`, probe.dashOverflowY === 'auto' || probe.bodyScroll.sh - probe.bodyScroll.ch > 40, `#usage-dashboard overflow-y=${probe.dashOverflowY}`);

  // 触屏上划拖拽（自下而上 → 内容上滚）。
  const beforeTop = await page.evaluate(() => {
    const dash = document.getElementById('usage-dashboard');
    const body = dash.closest('.mobile-page-body');
    return Math.max(dash.scrollTop, body.scrollTop);
  });
  await touchDrag(page, 187, 640, 180);
  await page.waitForTimeout(700);
  const afterTop = await page.evaluate(() => {
    const dash = document.getElementById('usage-dashboard');
    const body = dash.closest('.mobile-page-body');
    return Math.max(dash.scrollTop, body.scrollTop);
  });
  check(`${scheme}: 触屏拖拽跟手（scrollTop 前进）`, afterTop > beforeTop + 80, `scrollTop ${beforeTop} → ${afterTop}`);
  // 手指态帧：拖拽松手瞬间的真实滚动位（before = 卡死原位；after = 跟手中景）。
  await page.screenshot({ path: join(OUT, `usage-375-${scheme}-${PHASE}-drag.png`) });

  // 拖拽到底 + 断言最后一个统计块（明细表末行）完整可见。
  await page.evaluate(() => {
    const dash = document.getElementById('usage-dashboard');
    const body = dash.closest('.mobile-page-body');
    const s = dash.scrollHeight - dash.clientHeight > 4 ? dash : body;
    s.scrollTop = s.scrollHeight;
  });
  await page.waitForTimeout(500);
  const bottom = await page.evaluate(() => {
    const rows = document.querySelectorAll('#usage-drill .ud-table tbody tr');
    const last = rows[rows.length - 1];
    const drill = document.getElementById('usage-drill');
    const b = last.getBoundingClientRect();
    return { lastRowBottom: Math.round(b.bottom), lastRowTop: Math.round(b.top), drillBottom: Math.round(drill.getBoundingClientRect().bottom), vh: window.innerHeight };
  });
  check(`${scheme}: 滚到底 = 末行完整可见（含底部留白）`, bottom.lastRowBottom > 0 && bottom.lastRowBottom <= bottom.vh && bottom.drillBottom <= bottom.vh + 1, `lastRowBottom=${bottom.lastRowBottom} drillBottom=${bottom.drillBottom} vh=${bottom.vh}`);
  // 底部帧：after = 手指可达终态（验收帧）；before = 仅 JS scrollTop 可达
  //（overflow:hidden 盒对脚本滚动开放、对手势封闭）——红证以 -drag 帧为准。
  await page.screenshot({ path: join(OUT, `usage-375-${scheme}-${PHASE}-bottom.png`) });
  await context.close();
}

// ══ 桌面回归（1366×850，零手机层）═════════════════════════════════════
{
  const { context, page } = await boot({ width: 1366, height: 850, scheme: 'dark', touch: false });
  await page.waitForSelector('#usage-btn', { timeout: 15000 });
  await page.click('#usage-btn');
  await page.waitForSelector('#usage-overlay:not([hidden])', { timeout: 8000 });
  await page.waitForSelector('#usage-dashboard .ud-cell[data-date]', { timeout: 15000 });
  await page.waitForTimeout(800);
  await page.locator('#usage-grid .ud-cell[data-date]').last().click();
  await page.waitForSelector('#usage-curve #usage-curve-body svg', { timeout: 10000 });
  await page.locator('#usage-curve .ud-drill-open').click();
  await page.waitForSelector('#usage-drill .ud-table tbody tr', { timeout: 10000 });
  await page.waitForTimeout(500);
  const desk = await page.evaluate(() => {
    const dash = document.getElementById('usage-dashboard');
    const rows = dash.querySelectorAll('#usage-drill .ud-table tbody tr');
    const last = rows[rows.length - 1];
    dash.scrollTop = dash.scrollHeight;
    return new Promise((res) => setTimeout(() => {
      const b = last.getBoundingClientRect();
      res({ overflowY: getComputedStyle(dash).overflowY, scrolled: dash.scrollTop, lastRowBottom: Math.round(b.bottom), vh: window.innerHeight });
    }, 300));
  });
  console.log(`[probe desktop]`, JSON.stringify(desk));
  check('desktop: 面板自滚到底、末行可见', desk.scrolled > 40 && desk.lastRowBottom <= desk.vh && desk.overflowY === 'auto', `scrollTop=${desk.scrolled} lastRowBottom=${desk.lastRowBottom} overflowY=${desk.overflowY}`);
  await page.screenshot({ path: join(OUT, `usage-desktop-${PHASE}.png`) });
  await context.close();
}

await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n== ${PHASE} == ${results.length - failed.length}/${results.length} checks passed`);
if (errors.length) { console.log('console errors:'); for (const e of errors) console.log('  ' + e); }
if (failed.length || errors.length) process.exit(1);
console.log('ALL GREEN');
