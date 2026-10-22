// imgviewer-controls-shots.mjs — 移位批（作者令 2026-10-22 ②）前后对照截图。
//
// 场景 = 图片 pane（截图1 的肇事面）：文件名胶囊（.canvas-header）+ 图片缩放条
// （.canvas-zoom-bar）+ 层叠浮钮（#deck-fab）三件同框。改动前后各跑一遍：
//   SHOTS_DIR=/tmp/.../before node tests/imgviewer-controls-shots.mjs
//   SHOTS_DIR=/tmp/.../after  node tests/imgviewer-controls-shots.mjs
// 断言（轻量几何，与截图互补）：胶囊 x 在面板左缘 1/3 内；缩放条、fab 底右；
// 三件两两矩形零交叠；亮暗双主题各一轮；R0 无运行时错误。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
/** 被测树根（repo root；NB_UX_ROOT = 修前树根，前后对照用）。 */
const SERVE_ROOT = process.env.NB_UX_ROOT || resolve(HERE, '..');
const WEB_DIR = resolve(SERVE_ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8157);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || '/tmp/stand-1022/imgviewer';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const IMG_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="600">' +
  '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
  '<stop offset="0" stop-color="#5b8def"/><stop offset="1" stop-color="#9b6def"/></linearGradient></defs>' +
  '<rect width="960" height="600" fill="url(#g)"/>' +
  '<circle cx="300" cy="200" r="90" fill="#ffd66b"/>' +
  '<path d="M0 600 L300 320 L520 480 L720 340 L960 600 Z" fill="#2e4a7a"/></svg>';

const MD = (name) => `# ${name}\n\n对照用正文，保证 md pane 的底右圆钮簇与图片 pane 的缩放条同框语言。\n\n- 甲\n- 乙\n- 丙\n`;

const results = [];
const ok = (name, cond, detail = '') => results.push(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);

async function runTheme(browser, tag, colorScheme) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: IMG_SVG });
  });
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await sleep(600);
  await page.evaluate(({ content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:/tmp/shift-fixture/巡检路线示意.svg', itemType: 'image', title: '巡检路线示意.svg', content: '', absPath: '/tmp/shift-fixture/巡检路线示意.svg', pinned: true },
    }));
  }, { content: IMG_SVG });
  await page.waitForSelector('.canvas-tab-pane[data-tab-id="file:/tmp/shift-fixture/巡检路线示意.svg"]', { timeout: 10000 });
  await sleep(900);

  const geo = await page.evaluate(() => {
    const panel = document.getElementById('canvas-panel').getBoundingClientRect();
    const rect = (sel, root) => {
      const el = (root || document).querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: r.x, y: r.y, w: r.width, h: r.height, visible: r.width > 0 && r.height > 0 };
    };
    return {
      panel,
      header: rect('.canvas-header'),
      zoom: rect('.canvas-zoom-bar'),
      fab: rect('#deck-fab'),
      fabCS: (() => { const f = document.getElementById('deck-fab'); return f ? getComputedStyle(f).display : 'none'; })(),
    };
  });
  const lap = (a, b) => (!a || !b) ? 0 : Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)) * Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));
  const P = geo.panel;
  ok(`${tag} G1 胶囊居左（左缘落面板左侧 1/3）`, geo.header && geo.header.x - P.x < (P.width / 3), `dx=${(geo.header.x - P.x).toFixed(0)} panelW=${P.width.toFixed(0)}`);
  ok(`${tag} G2 缩放条底右（底缘距面板底 <40px）`, geo.zoom && (P.y + P.height) - (geo.zoom.y + geo.zoom.h) < 40 && (P.x + P.width) - (geo.zoom.x + geo.zoom.w) < 200,
    `db=${(P.y + P.height - geo.zoom.y - geo.zoom.h).toFixed(0)} dr=${(P.x + P.width - geo.zoom.x - geo.zoom.w).toFixed(0)}`);
  ok(`${tag} G3 fab 可见且底右`, geo.fabCS !== 'none' && geo.fab && (P.y + P.height) - (geo.fab.y + geo.fab.h) < 40,
    `display=${geo.fabCS} db=${geo.fab ? (P.y + P.height - geo.fab.y - geo.fab.h).toFixed(0) : 'n/a'}`);
  const laps = [['header∩zoom', lap(geo.header, geo.zoom)], ['header∩fab', lap(geo.header, geo.fab)], ['zoom∩fab', lap(geo.zoom, geo.fab)]];
  ok(`${tag} G4 三件两两零交叠`, laps.every(([, a]) => a === 0), laps.map(([n, a]) => `${n}=${a.toFixed(0)}`).join(' '));

  mkdirSync(SHOTS_DIR, { recursive: true });
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-1-image-tab.png`), clip: { x: Math.max(0, P.x - 8), y: Math.max(0, P.y - 8), width: P.width + 16, height: P.height + 16 } });
  // 底右特写（缩放条 + fab 簇）
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-2-corner.png`), clip: { x: P.x + P.width - 460, y: P.y + P.height - 130, width: 460, height: 130 } });
  // 顶行特写（胶囊）
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-3-top.png`), clip: { x: P.x, y: P.y, width: P.width, height: 84 } });
  ok(`${tag} R0 无 JS 运行时错误`, errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

mkdirSync(SHOTS_DIR, { recursive: true });
const browser = await chromium.launch();
await runTheme(browser, 'dark', 'dark');
await runTheme(browser, 'light', 'light');
await browser.close();
server.close();
console.log(results.join('\n'));
const fail = results.filter((r) => r.startsWith('FAIL')).length;
console.log(`\n═══ imgviewer-controls-shots: ${results.length - fail} PASS / ${fail} FAIL ═══`);
process.exit(fail ? 1 : 0);
