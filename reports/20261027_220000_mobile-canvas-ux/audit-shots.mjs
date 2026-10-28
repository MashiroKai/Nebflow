// audit-shots.mjs — 现状审计实截（手机视口 375×812 / 390×844）。
// 复用 mobile-canvas-sheet.spec 的 mock 装配（同一 boot 路径），开 3 张文档卡后
// 逐交互位实截 + 热区量测（按钮盒 / 缘带几何输出 JSON）。
// Run: node reports/20261027_220000_mobile-canvas-ux/audit-shots.mjs
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/@playwright/test/index.mjs';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = dirname(fileURLToPath(import.meta.url));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'sheet-root-session';

async function boot(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let parsed; try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:18081/');
  for (let i = 0; i < 60; i++) {
    const ok = await page.evaluate(async () => {
      try {
        const s = (await import('/js/state.js')).default;
        return !!(s.activeSessionId && s.ws && s.ws.readyState === 1);
      } catch { return false; }
    }).catch(() => false);
    if (ok) break;
    await page.waitForTimeout(250);
  }
}

async function openCards(page, n) {
  const err = await page.evaluate((cnt) => {
    try {
      const defs = [['doc-a', '项目计划书.md', 'sheet-a'], ['doc-b', '架构图.svg', 'sheet-b'], ['doc-c', '会议纪要.txt', 'sheet-c']];
      return Promise.all([import('/js/canvas.js'), import('/js/mobile/navController.js')]).then(([canvas, nav]) => {
        nav.touchLayer();
        for (let i = 0; i < cnt; i++) { const [id, t, ty] = defs[i]; canvas.openTab(id, t, { type: ty }); }
        return null;
      }).catch((e) => 'import/canvas: ' + (e?.message || e));
    } catch (e) { return 'sync: ' + (e?.message || e); }
  }, n);
  if (err) throw new Error('openCards failed: ' + err);
  for (let i = 0; i < 40; i++) {
    if (await page.evaluate(() => document.body.classList.contains('mf-cards-live')).catch(() => false)) break;
    await page.waitForTimeout(250);
  }
  await page.waitForTimeout(500);
}

async function metrics(page) {
  return page.evaluate(() => {
    const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) }; };
    const q = (s) => document.querySelector(s);
    return {
      sheet: r(q('#canvas-panel')),
      head: r(q('#canvas-panel .canvas-header')),
      cardClose: r(q('.mobile-card-close')),
      cardPop: r(q('.mobile-card-pop')),
      edgeBottom: r(q('.mobile-canvas-edgezone')),
      edgeSideL: r(q('.mobile-canvas-edgezone-side[data-side="left"]')),
      edgeSideR: r(q('.mobile-canvas-edgezone-side[data-side="right"]')),
      count: q('.mobile-card-count')?.textContent ?? null,
      title: q('.mobile-card-title')?.textContent ?? null,
      pager: !!q('.mobile-card-pager'),
      switchbar: !!q('.mobile-card-switchbar'),
      canvasTitleVisible: (() => { const t = q('#canvas-panel .canvas-title'); if (!t) return false; const s = getComputedStyle(t); return s.display !== 'none' && t.getBoundingClientRect().width > 0; })(),
    };
  });
}

const browser = await chromium.launch();
const shots = [];
for (const [w, h, tag] of [[375, 812, '375x812'], [390, 844, '390x844']]) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, hasTouch: true, colorScheme: 'dark', deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  page.on('requestfailed', (r) => console.log(`[${tag}] REQFAIL:`, r.url(), r.failure()?.errorText));
  page.on('response', (r) => { if (r.status() >= 400) console.log(`[${tag}] HTTP`, r.status(), r.url()); });
  await boot(page);
  await openCards(page, 3);
  await page.screenshot({ path: join(OUT, `audit-1-sheet-${tag}.png`) });

  const m = await metrics(page);
  shots.push({ tag, metrics: m });

  // 长按引用钮现状
  const head = await page.locator('#canvas-panel .canvas-header').boundingBox();
  await page.mouse.move(head.x + head.width / 2, head.y + head.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(600);
  await page.screenshot({ path: join(OUT, `audit-2-longpress-${tag}.png`) });
  await page.mouse.up();

  // 关掉第 3 张卡后的两卡态（看计数徽章）
  await page.evaluate(async () => { const c = await import('/js/canvas.js'); c.closeTab('doc-c'); });
  await page.waitForTimeout(600);
  await page.screenshot({ path: join(OUT, `audit-3-two-cards-${tag}.png`) });
  await ctx.close();
}
await browser.close();
console.log(JSON.stringify(shots, null, 2));
