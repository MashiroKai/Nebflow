// card-persist-fe-search.spec.mjs — Card( filePath ) on the SEARCH surface
// (chain-card-persist-impl · E3-6 · FE leg, 2026-10-01; 2026-10-30 换代).
//
// Author ruling 2026-10-01 【全裁】: E3-6 = 采 — a persisted Card (a Card tool
// call carrying a `filePath`) must surface in message search as a Canvas
// artifact (badge + click-to-open), while the LEGACY html-only Card call must
// NOT activate that leg.
//
// 换代（聊天记录搜索优化批 2026-10-30 作者令①：搜索结果只搜对话——tool 行
// 退出关键词结果）：E3-6 的工件面改走分类 tab——持久化 Card 经 popMediaIndex
// 退化路径在「文件」tab 产出 file 条目（activateMediaEntry → openPopArtifact
// → workspace-open-item，同一 Canvas 单点）；legacy html-only Card 无 filePath
// ⇒ 不产生条目（比旧行面更强的负向判别：行面它还渲染一行，条目面直接缺席）。
//
// The real chain under test (no live gateway, no backend):
//   static server serves src/main/resources/web → boot index.html with a
//   mocked WS sessionList (activeId sess-1) and a mocked
//   GET /api/sessions/sess-1/history returning two `tool` rows →
//   openSearchModal() → 文件 tab → indexMedia degrade → media grid →
//   click the persisted-Card file card → activateMediaEntry() →
//   openPopArtifact() → CustomEvent 'workspace-open-item' (canvas.js then
//   fetches content via pop.readFile — deliberately out of scope here).
//
// Scope discipline (task brief §二C): this is a SPLIT-SURFACE proof — the tool
// input JSON is fed directly by the harness. The merged-state end-to-end leg
// (a real Card(filePath=…) call) is NOT verified here.
//
// Run: node tests/card-persist-fe-search.spec.mjs
// Optional env: PORT=8155 (never 8080 / 8097)

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8155);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// The artifact path fed to the persisted-Card row (a split-surface input; the
// file itself never has to exist — no backend leg is exercised here).
const ART_PATH = '/tmp/nfc-cpf/demo.html';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
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
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

let pass = 0, fail = 0;
const failures = [];
function ok(name, cond, extra = '') {
  if (cond) { pass++; console.log(`PASS  ${name}`); }
  else { fail++; failures.push(name); console.log(`FAIL  ${name}${extra ? '  — ' + extra : ''}`); }
}

// ── The two tool rows (the exact shapes the task brief §二B.1 prescribes) ─────
// Positive: a persisted Card — input carries `filePath`.
const POS_ROW = {
  type: 'tool',
  label: 'Card\n  (demo)',
  summary: `Card\n  (demo)`,
  input: JSON.stringify({ filePath: ART_PATH, title: 'demo' }),
  content: '___CARD_HTML___{"html":"<div id=\\"pos\\">hi</div>","title":"demo"}',
  isError: false,
};
// Negative: the LEGACY Card call — input carries `html` only, no `filePath`.
// Same tool, same card body shape ⇒ the ONLY difference is the input JSON.
const NEG_ROW = {
  type: 'tool',
  label: 'Card\n  (legacy)',
  summary: `Card\n  (legacy)`,
  input: JSON.stringify({ html: '<div id="neg">hi</div>', title: 'legacy' }),
  content: '___CARD_HTML___{"html":"<div id=\\"neg\\">hi</div>","title":"legacy"}',
  isError: false,
};
const HISTORY = { messages: [POS_ROW, NEG_ROW], hasMore: false, offset: 0 };

async function bootPage(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    // Passive recorder for the event the click leg must dispatch.
    window.__opened = [];
    window.addEventListener('workspace-open-item', (e) => { window.__opened.push(e.detail); });
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  const consoleErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 200)));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 200)); });

  // Catch-all first; the specific /history route below wins (Playwright matches
  // the most recently registered handler first — same pattern as the existing
  // card suites).
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/api/sessions/*/history**', (r) => r.fulfill({ json: HISTORY }));
  // rootName 显示名探针（identity 批新面）：引擎在生产里于 web 根同层伺服
  // agents/<name>/agent.json；harness 静态树没有该文件 ⇒ 404 console.error 打红
  // ③ 零错误判据。mock 缺名回退态（displayName 缺 ⇒ 机制名兜底），零判据语义变化。
  await page.route('**/agents/*/agent.json', (r) => r.fulfill({ json: {} }));

  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
      // Every other client frame (pop.readFile …) is intentionally un-answered:
      // the Canvas fetch leg is out of scope for this split-surface spec.
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: 'sess-1', agentName: 'Nebula', name: 'T', title: 'T', updatedAt: Date.now() }],
      activeId: 'sess-1',
      folders: [],
    }));
  });

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page, pageErrors, consoleErrors };
}

const browser = await chromium.launch();

let rc = 0;
try {
  const { ctx, page, pageErrors, consoleErrors } = await bootPage(browser);

  // ── Open the search modal, switch to the Files tab (artifact surface
  //     after the 2026-10-30 ruling: tool rows left the keyword results;
  //     persisted Cards surface here through the index degrade leg) ──
  ok('the search opener button is present', (await page.$('#search-btn')) !== null);
  await page.click('#search-btn');
  await page.waitForSelector('#search-overlay.on', { timeout: 5000 });
  await page.click('#search-tab-files');
  await sleep(700);

  // ① entry surface: the persisted Card degrades to exactly ONE file entry
  //    (stable entry id `sess-1:0:0` = <sessionId>:<msgOrd>:<itemIdx>); the
  //    legacy html-only Card produces NO entry (no filePath → no artifact).
  const cards = await page.$$eval('#search-results .search-media-card', (els) =>
    els.map((el) => ({
      entryType: el.dataset.entryType,
      entryId: el.dataset.entryId,
      name: el.querySelector('.search-media-name')?.textContent || '',
    })));
  ok('① exactly one file card (the persisted Card degrade)', cards.length === 1 && cards[0].entryType === 'file', JSON.stringify(cards));
  ok('① the card is the persisted (filePath) entry sess-1:0:0', cards[0]?.entryId === 'sess-1:0:0', JSON.stringify(cards));
  ok('① legacy html-only Card produces no entry (stronger negative)', !cards.some((c) => c.entryId === 'sess-1:1:0'), JSON.stringify(cards));

  // ② click the file card ⇒ workshop-open item with the five fields
  //    (activateMediaEntry → openPopArtifact: same Canvas single point the
  //    old row-artifact leg used; title = baseName(ref) on the grid leg).
  const clicked = await page.evaluate(() => {
    const el = document.querySelector('#search-results .search-media-card[data-entry-id="sess-1:0:0"]');
    if (!el) return false;
    el.click();
    return true;
  });
  ok('② the persisted entry card was clickable', clicked === true);
  await sleep(600);
  const opened = await page.evaluate(() => window.__opened || []);
  ok('② a workspace-open-item event was dispatched', opened.length === 1, JSON.stringify(opened));
  const d = opened[0] || {};
  ok('② detail.absPath == the filePath from the tool input', d.absPath === ART_PATH, String(d.absPath));
  ok('② detail.content === "" (content fetched by Canvas via readFile)', d.content === '', JSON.stringify(d.content));
  ok('② detail.itemType === "" (type resolved after the readFile round trip)', d.itemType === '', JSON.stringify(d.itemType));
  ok('② detail.id === "file:" + filePath', d.id === 'file:' + ART_PATH, String(d.id));
  ok('② detail.title === the entry name (baseName of the filePath)', d.title === 'demo.html', String(d.title));

  // ── ③ zero JS runtime errors (pageerror + console.error) ──────────────────
  ok('③ zero pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  ok('③ zero console.error', consoleErrors.length === 0, consoleErrors.join(' | '));

  await ctx.close();

  console.log(`\n=== ${pass}/${pass + fail} PASS ===`);
  if (fail) { console.log('FAILED: ' + failures.join(' | ')); rc = 1; }
} catch (e) {
  console.log('HARNESS THREW: ' + (e && e.stack ? e.stack : String(e)));
  rc = 2;
} finally {
  try { await browser.close(); } catch { /* already closed */ }
  await new Promise((r) => { server.close(() => r()); setTimeout(r, 2000); });
}
process.exit(rc);
