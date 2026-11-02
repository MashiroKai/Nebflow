#!/usr/bin/env node
// capsule-before-shots.mjs — 改前对照帧（HEAD 快照工作树）。
// 用法：CAPSULE_WEB=<HEAD worktree>/src/main/resources/web CAPSULE_OUT=<dir> \
//        node tests/capsule-before-shots.mjs
// 只依赖**旧**选择器（.tfl-capsule / .tfl-card）⇒ 可在改前代码上跑通，产出
// 1280/1440 × 亮/暗 的静止帧 + 尺寸读数，供 docs 的「改前/改后同视口对照」。
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.CAPSULE_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.CAPSULE_OUT || join(HERE, '..', 'evidence', '20261102_todo-capsule', 'shots-before');
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const ROOT_SID = 'capsule-root-session';
const SELF_DEVICE = { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro-iso-a659864a' };
const T0 = Date.now();
const H = 3600_000;
const PORT = 8122;
const r1 = (v) => Math.round(v * 10) / 10;

function seed() {
  return `(async () => {
    const m = await import('/js/todoStore.js');
    const now = ${T0}, H = ${H};
    const list = [];
    for (let i = 0; i < 7; i++) list.push({ id: 'c-' + i, tagId: i % 2 ? 'tag-life' : 'tag-work',
      title: '待办条目 ' + (i + 1), notes: '', due: now + (i + 1) * H, priority: i % 4, createdAt: now - i * H });
    list.push({ id: 'c-overdue', tagId: 'tag-work', title: '已逾期的一条', notes: '',
      due: now - 3 * H, priority: 3, createdAt: now - 30 * H });
    m.todoStore.__seed({ tagList: [
      { id: 'tag-work', name: '工作', color: m.TAG_COLOR_PRESETS[5] },
      { id: 'tag-life', name: '生活', color: m.TAG_COLOR_PRESETS[3] }], itemList: list });
  })()`;
}

const browser = await chromium.launch({ headless: true });
for (const width of [1280, 1440]) {
  for (const scheme of ['light', 'dark']) {
    const ctx = await browser.newContext({ colorScheme: scheme, viewport: { width, height: 900 }, deviceScaleFactor: 2, serviceWorkers: 'block' });
    const page = await ctx.newPage();
    const errors = [];
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    page.on('pageerror', (e) => errors.push(String(e)));
    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let p = url.pathname;
      if (p === '/') p = '/index.html';
      if (p === '/api/nf-asset-stamp') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ stamp: 'e2e-fixed' }) });
      if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Spider' }) });
      if (p.startsWith('/agents/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      const file = normalize(join(WEB, p));
      if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
      try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
      catch { return route.fulfill({ status: 404, body: 'nf' }); }
    });
    await page.routeWebSocket(/\/ws/, (ws) => {
      const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', selfDevice: SELF_DEVICE }));
      const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
      cfg(); ses();
      ws.onMessage((raw) => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'getConfig') cfg();
        else if (m.type === 'getSessions' || m.type === 'listSessions') ses();
        else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      });
    });
    await page.goto(`http://localhost:${PORT}/`);
    await page.waitForFunction(async (sid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
    }, ROOT_SID, { timeout: 20000 });
    await page.evaluate(async (sid) => { const f = await import('/js/todoFace.js'); f.renderTodoTaskList(sid); }, ROOT_SID);
    await page.evaluate(seed());
    await page.waitForTimeout(500);
    const g = await page.evaluate(() => {
      const rect = (s) => { const el = document.querySelector(s); if (!el) return null; const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height, cx: r.x + r.width / 2, cy: r.y + r.height / 2, b: r.bottom }; };
      const cap = document.querySelector('.tfl-capsule');
      const txt = cap ? cap.textContent.replace(/\s+/g, ' ').trim() : null;
      return { cap: rect('.tfl-capsule'), card: rect('.tfl-card'), bar: rect('#input-bar'), text: txt };
    });
    console.log(`[before ${width}-${scheme}]`, JSON.stringify({
      cap: g.cap && { x: r1(g.cap.x), y: r1(g.cap.y), w: r1(g.cap.w), h: r1(g.cap.h) },
      card: g.card && { x: r1(g.card.x), y: r1(g.card.y), w: r1(g.card.w), h: r1(g.card.h) },
      bar: g.bar && { x: r1(g.bar.x), w: r1(g.bar.w), cx: r1(g.bar.cx), y: r1(g.bar.y) },
      text: g.text,
      capWidthRatio: g.cap && g.bar ? r1(g.cap.w / g.bar.w) : null,
      centerOffset: g.cap && g.bar ? r1(g.cap.cx - g.bar.cx) : null,
    }));
    await page.screenshot({ path: join(OUT, `d-${width}-${scheme}-before.png`) });
    await page.locator('#task-list').first().screenshot({ path: join(OUT, `d-${width}-${scheme}-before-el.png`) }).catch(() => {});
    // 改前展开态（点击胶囊 → 老卡容器高度弹簧展开）
    await page.click('.tfl-capsule');
    await page.waitForTimeout(900);
    await page.screenshot({ path: join(OUT, `d-${width}-${scheme}-before-open.png`) });
    console.log(`  console errors: ${errors.length}`);
    await ctx.close();
  }
}
await browser.close();
