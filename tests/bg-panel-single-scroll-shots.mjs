#!/usr/bin/env node
// bg-panel-single-scroll-shots.mjs — 后台任务面板「单一滚动容器」改造的
// before/after 视觉存证（作者令 2026-11-02「把内容都放进这个面板，可以下滑；
// 现在是分块了，然后分块下滑」）。
//
// 每档 3 拍：① 静止（顶部）② 中段（连续滚动中）③ 滚到底（看末条与 dock 的间距）。
// 修前/修后同夹具同视口，直接肉眼对照「两张卡各滚」vs「整页一滚」。
// 数据面 = WS activeBgTasks 快照 + state.sessionBgAgents 直种，零后端。
// Run: node tests/bg-panel-single-scroll-shots.mjs [--tag before|after]
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const TAG = (process.argv.includes('--tag') ? process.argv[process.argv.indexOf('--tag') + 1] : 'after');
const TS = new Date().toISOString().replace(/[-:]/g, '').replace('T', '_').slice(0, 15);
const OUT = process.env.BGSS_OUT_DIR || join(HERE, '..', 'evidence', `${TS}_bg-panel-single-scroll_${TAG}`);
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'bgss-shots-root';
const ORIGIN = 'http://localhost:8907';
const T0 = Date.now();
const AGENT_ROWS = 24;
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const consoleErrors = [];

function bgTasks() {
  return { [SID]: [
    { taskId: 'job-1', description: 'Post-merge spec run in base repo', status: 'completed', startedAt: T0 - 90_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 5_000, outputLines: 42 } },
    { taskId: 'job-2', description: '单滚批：面板改造验收夹具任务二', status: 'running', startedAt: T0 - 30_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
    { taskId: 'job-3', description: '单滚批：面板改造验收夹具任务三', status: 'running', startedAt: T0 - 8_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
  ] };
}
function agentRows() {
  const rows = [{ id: 'delegate-fixture-1', info: {
    name: 'delegate-fixture-1', kind: 'Delegate', sessionId: 'delegate-fixture-1',
    status: 'Processing', startedAt: T0 - 120_000, parentSessionId: SID,
    task: '单滚批夹具：delegate 组头（子行 4 条）',
  } }];
  for (let i = 1; i <= 4; i += 1) {
    rows.push({ id: `subtask-fixture-${i}`, info: {
      name: `subtask-fixture-${i}`, kind: 'SubTask', sessionId: `subtask-fixture-${i}`,
      status: 'Processing', startedAt: T0 - (60_000 + i * 1_000), parentSessionId: 'delegate-fixture-1',
      task: `单滚批夹具：派生第 ${i} 条子行`,
    } });
  }
  for (let i = 1; i <= 19; i += 1) {
    rows.push({ id: `node-fixture-${i}`, info: {
      name: `node-fixture-${i}`, kind: 'AgentFlow', sessionId: `node-fixture-${i}`,
      status: i % 3 === 0 ? 'Completed' : 'Processing', startedAt: T0 - (i * 7_000), parentSessionId: SID,
      task: `单滚批夹具：平铺第 ${i} 条（AgentFlow 节点行）`,
    } });
  }
  return rows;
}

async function boot(scheme, engineName) {
  const engine = engineName === 'webkit' ? webkit : chromium;
  const ctx = await engine.launch().then((b) => b.newContext({
    viewport: { width: 375, height: 812 }, colorScheme: scheme, deviceScaleFactor: 2,
    hasTouch: true, locale: 'zh-CN',
  }));
  const page = await ctx.newPage();
  page.on('pageerror', (e) => { if (!NOISE.test(e.message)) consoleErrors.push(`pageerror: ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) consoleErrors.push(`console.error: ${m.text()}`); });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => {}), configurable: true,
    });
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', selfDevice: { id: 'd1', name: 'Mashiros-MacBook-Pro-iso-a659864a' } }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); ses();
    ws.send(JSON.stringify({ type: 'activeBgTasks', tasks: bgTasks() }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') ses();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await page.evaluate(({ sid, rows }) => import('/js/state.js').then((m) => {
    const b = {};
    for (const r of rows) b[r.id] = r.info;
    m.default.sessionBgAgents[sid] = b;
    m.default.updateBgAgentIndicator();
  }), { sid: SID, rows: agentRows() });
  await page.waitForTimeout(600);
  return page;
}

async function openBackground(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForSelector('.mobile-page.live[data-page="workbench"] .mf-hub-row[data-hub-page="background"]', { timeout: 10000 });
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="background"]').click();
  await page.waitForSelector('.mobile-page.live[data-page="background"] .mobile-page-body > #bg-dropdown .bg-task-row', { timeout: 15000 });
  await page.waitForTimeout(500);
}

/** 三个滚动上下文读数（修前两个列表各一 + 修后载体一个）。 */
async function struct(page) {
  return page.evaluate(() => {
    const sec = document.querySelector('.mobile-page[data-page="background"]');
    const body = sec.querySelector('.mobile-page-body');
    const c = body.querySelector('#bg-dropdown');
    const over = (el) => {
      const cs = getComputedStyle(el);
      return (cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight - el.clientHeight > 0;
    };
    const ident = (el) => (el === body ? '.mobile-page-body' : el === c ? '#bg-dropdown'
      : (el.id ? '#' + el.id : '.' + String(el.className || el.tagName).split(' ')[0]));
    return {
      scrollers: [body, ...body.querySelectorAll('*')].filter(over).map((el) => ({
        sel: ident(el), overflowY: getComputedStyle(el).overflowY,
        scrollHeight: el.scrollHeight, clientHeight: el.clientHeight,
        surplus: el.scrollHeight - el.clientHeight,
      })),
      lists: [...c.querySelectorAll('.bg-dropdown-list')].map((l) => ({
        section: String(l.parentElement.className || '').replace('bg-section ', ''),
        rows: l.querySelectorAll('.bg-task-row').length,
        overflowY: getComputedStyle(l).overflowY,
        scrollHeight: l.scrollHeight, clientHeight: l.clientHeight,
        surplus: l.scrollHeight - l.clientHeight,
      })),
      carrier: {
        overflowY: getComputedStyle(c).overflowY,
        scrollHeight: c.scrollHeight, clientHeight: c.clientHeight,
        surplus: c.scrollHeight - c.clientHeight,
      },
    };
  });
}

async function shoot(page, name) {
  await page.screenshot({ path: join(SHOTS, `${name}.png`) });
}

async function leg(scheme, engineName) {
  const tag = `${engineName}-${scheme}`;
  const page = await boot(scheme, engineName);
  try {
    await openBackground(page);
    const st = await struct(page);
    console.log(`[${tag}] scrollers=${JSON.stringify(st.scrollers)}`);
    console.log(`[${tag}] lists=${JSON.stringify(st.lists)}`);
    console.log(`[${tag}] carrier=${JSON.stringify(st.carrier)}`);
    await shoot(page, `${tag}-1-top`);

    // 「用户手势落在哪就滚哪」实测：把每条列表各自滚到底，读载体 scrollTop 是否变化
    // （修前 = 载体不动、列表自己动；修后 = 列表不是滚动器，滚动全归载体）。
    const listScroll = await page.evaluate(() => {
      const c = document.querySelector('.mobile-page[data-page="background"] .mobile-page-body > #bg-dropdown');
      const errs = [];
      for (const l of c.querySelectorAll('.bg-dropdown-list')) {
        const before = l.scrollTop;
        l.scrollTop = l.scrollHeight;
        const moved = l.scrollTop - before;
        errs.push({ section: String(l.parentElement.className || '').replace('bg-section ', ''), moved, carrierTop: c.scrollTop });
        l.scrollTop = 0;
      }
      return { perList: errs, carrierOverflowY: getComputedStyle(c).overflowY };
    });
    console.log(`[${tag}] list-scroll probe=${JSON.stringify(listScroll)}`);
    await shoot(page, `${tag}-2-mid`);

    // 整页滚到底（修后唯一滚动器 = 载体；修前 = 最长的那条列表）
    const bottom = await page.evaluate(() => {
      const sec = document.querySelector('.mobile-page[data-page="background"]');
      const body = sec.querySelector('.mobile-page-body');
      const c = body.querySelector('#bg-dropdown');
      const over = (el) => {
        const cs = getComputedStyle(el);
        return (cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight - el.clientHeight > 0;
      };
      const hit = [body, ...body.querySelectorAll('*')].filter(over);
      // 修前：把每个滚动器都滚到底（模拟用户在各块内分别下滑）
      for (const el of hit) el.scrollTop = el.scrollHeight;
      const dock = document.getElementById('mobile-dock');
      const sections = [...c.querySelectorAll('.bg-section')].filter((s) => s.getClientRects().length);
      const lastSec = sections[sections.length - 1];
      const rows = [...lastSec.querySelectorAll('.bg-task-row')].filter((r) => r.getClientRects().length);
      const last = rows[rows.length - 1];
      const lb = last ? last.getBoundingClientRect() : null;
      return {
        scrollerCount: hit.length,
        lastRowBottom: lb ? Math.round(lb.bottom) : -1,
        lastRowVisible: !!lb && lb.top >= 0 && lb.bottom <= window.innerHeight,
        dockTop: dock ? Math.round(dock.getBoundingClientRect().top) : -1,
        gapToDock: lb ? Math.round((dock ? dock.getBoundingClientRect().top : window.innerHeight) - lb.bottom) : -1,
        groupTitles: [...c.querySelectorAll('.mh-bg-grp, .bg-dropdown-header')].filter((e) => e.getClientRects().length).map((e) => e.textContent.trim()),
      };
    });
    console.log(`[${tag}] bottom=${JSON.stringify(bottom)}`);
    await shoot(page, `${tag}-3-bottom`);

    ok(`${tag} 内容区纵向滚动器数`, bottom.scrollerCount === (TAG === 'before' ? 2 : 1), `n=${bottom.scrollerCount} (scrollers=${JSON.stringify(st.scrollers.map((s) => s.sel))})`);
    ok(`${tag} 分组标题仍在（${bottom.groupTitles.join(' / ')}）`, bottom.groupTitles.some((x) => /智能体/.test(x)));
    ok(`${tag} 滚到底末条可见`, bottom.lastRowVisible);
    ok(`${tag} 滚到底末条不压 dock（间距 ${bottom.gapToDock}px）`, bottom.gapToDock >= 0);
  } finally {
    await page.close();
  }
}

console.log(`evidence → ${OUT}  (tag=${TAG})`);
for (const engineName of ['chromium', 'webkit']) {
  for (const scheme of ['light', 'dark']) {
    await leg(scheme, engineName);
  }
}
ok('零 console error（双引擎×双主题）', consoleErrors.length === 0, consoleErrors.slice(0, 4).join(' | '));
console.log(failures === 0 ? '\nALL PASS' : `\nFAILED: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
