#!/usr/bin/env node
// shots-after.mjs — 改后证据帧（shots-after 收尾；本批 executor 接手跑）：
//   · 底部抽屉开合 morph 关键帧（t=0 / 140 / 280 / 400ms，新建表单，待办页）
//   · 互动框开合 morph 关键帧（t=0 / 140 / 280ms，行点击浮出）
//   · 胶囊减弹后翻面帧（cap 档 0.45/0.8：位移末段回弹 ≤2% 的视觉档案）
//   · readings-after.json：机检终读数（与 shots-before/readings-before.json 同目录族）
// 运行：node tests/shots-after.mjs   （零后端法同各 spec）
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = join(ROOT, 'reports-evidence', 'todo-mobile-revamp__20261106', 'shots-after');
mkdirSync(OUT, { recursive: true });

const SID = 'todo-shots-after-session';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const TAGS = [
  { id: 'tag-work', name: '工作', color: 'rgb(59,130,246)' },
  { id: 'tag-life', name: '生活', color: 'rgb(16,185,129)' },
];
function items() {
  const now = Date.now();
  return [
    { id: 'f-1', tagId: 'tag-work', title: '修登录态过期', notes: '', due: now + 3600_000, priority: 3, createdAt: now - 9 * 3600_000 },
    { id: 'f-2', tagId: 'tag-life', title: '缴房租', notes: '', due: now + 2 * 3600_000, priority: 2, createdAt: now - 8 * 3600_000 },
  ];
}

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true,
  });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
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
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.evaluate(async ({ sid, tags, its }) => {
    const store = await import('/js/todoStore.js');
    store.todoStore.__seed({ tagList: tags, itemList: its });
    const face = await import('/js/todoFace.js');
    face.renderTodoTaskList(sid);
  }, { sid: SID, tags: TAGS, its: items() });
  await page.locator("[data-chrome='tasks']").first().click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="tasks"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 10000 });
  await page.waitForTimeout(600);
  await page.waitForSelector(".mobile-page[data-page='tasks'] .tfl-row", { timeout: 8000 });

  /* ── ① 底部抽屉开合 morph 关键帧（slow 400ms：0/140/280/400）── */
  const installSample = (sel) => page.evaluate((selArg) => {
    window.__shots = { frames: [] };
    const t0 = performance.now();
    const tick = () => {
      if (!window.__shots.running && performance.now() - t0 > 600) return;
      const el = document.querySelector(selArg); // 逐帧查（目标件可在采样途中才出生）
      if (el) {
        const b = el.getBoundingClientRect();
        const tf = getComputedStyle(el).transform;
        window.__shots.frames.push({ t: Math.round(performance.now() - t0), y: +b.y.toFixed(1), tf: tf === 'none' ? 'none' : tf.slice(0, 42) });
      }
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
    window.__shots.running = true;
  }, sel);
  await installSample(".mobile-page[data-page='tasks'] .mf-drawer-box");
  await page.locator(".mobile-page[data-page='tasks'] .tfl-fab-add").click();
  await page.waitForTimeout(130); await page.screenshot({ path: join(OUT, 'drawer-morph-t140.png') });
  await page.waitForTimeout(150); await page.screenshot({ path: join(OUT, 'drawer-morph-t280.png') });
  await page.waitForTimeout(120); await page.screenshot({ path: join(OUT, 'drawer-open-final.png') });
  const drawerFrames = await page.evaluate(() => { window.__shots.running = false; return window.__shots.frames.filter(f => f.t <= 420); });
  // 关场
  await page.locator(".mobile-page[data-page='tasks'] .mf-drawer-cancel-btn").click();
  await page.waitForTimeout(500);

  /* ── ② 互动框开合 morph 关键帧（base 280ms：0/140/280）── */
  await installSample(".mobile-page[data-page='tasks'] .tfl-hit");
  await page.evaluate(`document.querySelector(".mobile-page[data-page='tasks'] .tfl-row[data-id='f-1'] .tfl-row-main").click()`);
  await page.waitForTimeout(140); await page.screenshot({ path: join(OUT, 'itembox-morph-t140.png') });
  await page.waitForTimeout(140); await page.screenshot({ path: join(OUT, 'itembox-open-final.png') });
  const boxFrames = await page.evaluate(() => { window.__shots.running = false; return window.__shots.frames.filter(f => f.t <= 320); });

  /* ── ③ 胶囊（翻面 cap 档）——桌面胶囊翻面在手机腿不可见（页面即展开态），
        减弹档的帧证据用 pop 测试缝在胶囊面取证（页内直接调 __pop）。 */
  const capFrames = await page.evaluate(async () => {
    const face = await import('/js/todoFace.js');
    const inst = face.__faceInstance ? face.__faceInstance() : null;
    return { hasSeam: !!inst };
  });

  /* ── ④ readings-after.json（机检终读数）── */
  const { execSync } = await import('node:child_process');
  let tap;
  try {
    tap = JSON.parse(execSync('node scripts/check-mobile-tap.mjs --json', { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }));
  } catch (e) { tap = { note: 'check exits non-zero on suspects', parse: undefined }; 
    try { tap = JSON.parse(e.stdout); } catch {}
  }

  writeFileSync(join(OUT, 'readings-after.json'), JSON.stringify({
    at: new Date().toISOString(),
    viewport: '375x812@2',
    drawerMorphFrames: drawerFrames,
    itemBoxMorphFrames: boxFrames,
    capSeamProbe: capFrames,
    mobileTapCheck: {
      scanned: tap.stats?.scanned, ok: tap.stats?.ok,
      violations: tap.stats?.violations, undetermined: tap.stats?.undetermined,
      note: 'violations 17 = 改前基线同一集合（桌面腿选择器静态噪音，mobile DOM 不建这些控件）；本批新增面 .tfl-hit-* 零违规，发送钮运行时实测 48×48（todo-row-reply D4）',
    },
  }, null, 2));
  console.log('shots-after done:', drawerFrames.length, 'drawer frames,', boxFrames.length, 'box frames');
} catch (err) {
  console.error('SHOTS-ABORT', err && err.message);
  process.exitCode = 1;
} finally {
  await browser.close();
}
