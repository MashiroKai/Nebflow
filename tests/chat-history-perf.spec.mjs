// chat-history-perf.spec.mjs — 上滑加载聊天记录 ×渲染饿死心跳（台账 #922，
// 作者报障 2026-10-26：向上滑加载很慢，还可能触发刷新/重连）。
//
// 假设链（ticket）：上滑 → 拉历史大页（payload 大）→ 一次性 DOM 插入几百条 →
// JS 主线程长阻塞 → WS 心跳 pong 超时 → 前端 3-miss 自逐出 → 重连/刷新。
// 即「渲染饿死心跳」。修向（本批落地）= 上滑预取 + 停顶续拉 + 分帧构建挂载
// （大工具正文/markdown 空闲升级批）+ 心跳渲染遮蔽门（不放松 3-miss 与重座阈值）。
//
// 本 spec 用**真实前端代码**（静态伺服 src/main/resources/web）+ 桩后端
// （routeWebSocket，300 条重历史会话：60KB 工具正文 ×60、markdown、卡片图）
// 量出四件事：
//   P0 首屏（#923 钉子，2026-10-27）：初载恰好一页 — 首屏行数 = 一页的行数
//                           （卡片行双计；零双挂载/零重复行）且首屏时延
//                           （stub 收到 init getHistory → 首屏行落定）
//                           < PERF_BUDGET_FIRST_MS（默认 3000ms）；
//   P1 上滑分页逐页完成   — 前 4 页手动上滑补进，末段停在顶部由**续拉腿**
//                           自动补齐到 300 条收口（360 .row，卡片行双计）；
//   P2 每页主线程长任务   — 单页落定 < 2.5s 且 longtask 最长 < 300ms
//                           （修前 = 整页一枚 2~4s 长任务，红腿在场）；
//   P3 全程零重连零登出   — 全 spec 恰好 1 条 WS 连接、零 heartbeat 判死、
//                           零 sessionGate 横幅、零 reload；
//   P4 心跳遮蔽腿真实在位 — 渲染门持门期间 pong 被压过宽限窗（桩端压 pong
//                           6.5s > 5s 宽限）⇒ 该 miss 被遮蔽（shelteredMisses≥1
//                           且 hbMisses==0），连接不死（硬上限语义原样）。
//
// 运行：node tests/chat-history-perf.spec.mjs
// 红腿对照：NB_UX_ROOT=<修前树根> NB_CHROME_PATH=<chrome> node tests/chat-history-perf.spec.mjs
// 可选 env：PORT（默认 8199）、SHOTS_DIR、PERF_BUDGET_FIRST_MS（默认 3000）、
//   PERF_BUDGET_PAGE_MS（默认 2500）、PERF_BUDGET_LONGTASK_MS（默认 300）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const SERVE_ROOT = process.env.NB_UX_ROOT || resolve(HERE, '..');
const WEB_DIR = resolve(SERVE_ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8199);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || '/tmp/stand-1026/history-perf';
const PAGE_MS = Number(process.env.PERF_BUDGET_PAGE_MS || 2500);
const LONGTASK_MS = Number(process.env.PERF_BUDGET_LONGTASK_MS || 300);
const FIRST_MS = Number(process.env.PERF_BUDGET_FIRST_MS || 3000);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css',
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

// ── 重历史夹具：300 条，每页 50，页内 = 重工具正文 + 重 markdown + 卡片图 ──
const benchmark20 = process.env.NB_BENCHMARK_20 === '1';
const TOTAL = benchmark20 ? 5000 : 300;
const LIMIT = 50;
// ~60KB 的「文件读取」工具正文（真实形态：Read 大文件全量进 frontendContent）。
const BIG = (seed) => {
  const line = `line ${String(seed).padStart(6, '0')}: const cfg = { path: "/data/node_${seed}.json", retries: 3, timeoutMs: 30000 };\n`;
  let s = '';
  while (s.length < 60000) s += line;
  return s.slice(0, 60000);
};
// ~2.5KB markdown（标题/列表/代码栅栏/表格——marked.parse + KaTeX 升级批的料）。
const MD = (n) => [
  `### 第 ${n} 段结论`, '', `- 要点甲：**路径收敛**后延迟下降 \`${n * 7}ms\``,
  '- 要点乙：`getHistoryPage` 命中缓存，服务端切片 O(1)', '',
  '```scala', `def page(n: Int) = all.slice(${n}, ${n + LIMIT})`, '```', '',
  '| 指标 | 值 |', '| --- | --- |', `| p50 | ${n}ms |`, `| p99 | ${n * 3}ms |`, '',
].join('\n');
const cardRow = (n) => ({
  type: 'tool', label: 'Card', summary: '', isError: false,
  content: '___CARD_HTML___' + JSON.stringify({
    title: `卡片 ${n}`,
    html: `<h3>第 ${n} 页卡片</h3><img src="/api/nf-file?path=/pics/p${n}.png" alt="p${n}">`,
  }),
});
/** 诊断旋钮：NB_NO_CARDS=1 换小工具行（卡片 iframe 渲染是否为长任务主元的对照腿）。 */
const WITH_CARDS = !process.env.NB_NO_CARDS && process.env.NB_SEQUENCE_FIXTURE !== '1';
const ROWS_PER_PAGE = WITH_CARDS ? 60 : 50;   // 卡片消息产出 2 行（工具行 + card-content 行）
const FINAL_ROWS = ROWS_PER_PAGE * (TOTAL / LIMIT);
/** 一页 50 行：10 组 × [user 2KB, ai md 2.5KB, tool 60KB, ai 短, card 图]。
 *  idx = 会话内绝对下标（同一 idx 恒同内容 ⇒ 跨页确定性）。 */
const rawRowAt = (idx) => {
  // Every message carries an independently visible, unique sequence token in
  // the logical-order fixture. The mixed fixture remains the performance case.
  if (process.env.NB_SEQUENCE_FIXTURE === '1') return { type: 'user', text: `SEQ-${idx}-END`, timestamp: 1750000000000 + idx * 1000 };
  const k = idx % 5;
  if (k === 0) return { type: 'user', text: `用户消息 ${idx}：` + '这段正文提供真实行高与渲染面。'.repeat(40), timestamp: 1750000000000 + idx * 1000 };
  if (k === 1) return { type: 'ai', text: MD(idx), timestamp: 1750000000000 + idx * 1000 };
  if (k === 2) return { type: 'tool', label: 'Read', summary: `读取 /data/node_${idx}.json`, isError: false, content: BIG(idx) };
  if (k === 3) return { type: 'ai', text: `收到，第 ${idx} 条小结。`, timestamp: 1750000000000 + idx * 1000 };
  return WITH_CARDS ? cardRow(idx) : { type: 'tool', label: 'Note', summary: `备注 ${idx}`, isError: false, content: '小工具正文 ' + idx };
};
const rowAt = (idx) => {
  const row = rawRowAt(idx);
  if (process.env.NB_MIXED_SEQUENCE !== '1') return row;
  const marker = ` SEQ-${idx}-END`;
  if (row.type === 'user' || row.type === 'ai') row.text += marker;
  else if (idx % 5 === 4) row.label += marker;
  else row.summary += marker;
  return row;
};
const pageMessages = (offset, n) => Array.from({ length: n }, (_, i) => rowAt(offset + i));
/** 初载（无 beforeIndex）= 最旧在视页 offset=TOTAL-LIMIT；上滑 = 更旧一页。 */
const pageFor = (m) => {
  const before = m.beforeIndex === undefined ? null : Number(m.beforeIndex);
  const offset = before === null ? TOTAL - LIMIT : Math.max(0, before - LIMIT);
  const n = Math.min(LIMIT, before === null ? LIMIT : before);
  return {
    type: 'historyPage', sessionId: 'sess-1',
    messages: pageMessages(offset, n),
    total: TOTAL, offset, hasMore: offset > 0,
  };
};

let wsConnections = 0;
let initialHistorySends = 0;   // 每次（重）连接的初载 getHistory —— >1 即发生了重连
let initSendAt = 0;            // 首屏时延锚：stub 收到**第一条** init getHistory 的时刻
let heldHistory = null;
let holdPongMs = 0;            // 遮蔽腿：把下一窗 pong 压住 N ms（模拟大帧占道/主线程阻塞）
const histReqs = [];           // 诊断：每次 getHistory 的 beforeIndex 轨迹

const browser = await chromium.launch(process.env.NB_CHROME_PATH
  ? { executablePath: process.env.NB_CHROME_PATH }   // 本机缓存的全量 chromium（headless shell 未装时的旁路）
  : {});
const mobileNoise = process.env.NB_MOBILE_NOISE === '1';
const ctx = await browser.newContext({
  viewport: mobileNoise ? { width: 390, height: 844 } : { width: 1600, height: 1000 },
  deviceScaleFactor: mobileNoise ? 2 : 1,
  colorScheme: 'dark',
});
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  // longtask 观测（P2）：主线程 >50ms 的任务计数/累计/最长。
  window.__lt = { n: 0, total: 0, max: 0 };
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) { window.__lt.n++; window.__lt.total += e.duration; window.__lt.max = Math.max(window.__lt.max, e.duration); }
    }).observe({ entryTypes: ['longtask'] });
  } catch (_) { /* 老内核无 longtask：P2 以 pageMs 兜底 */ }
});
const page = await ctx.newPage();
const errors = [];
const wsLines = [];
let navigated = 0;
page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
page.on('console', (m) => { const t = m.text(); if (/heartbeat|reconnect|pong|parse error/.test(t)) wsLines.push(t.slice(0, 160)); });
page.on('framenavigated', (f) => { if (f === page.mainFrame()) navigated += 1; });

await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
await page.route('**/api/nf-file?*', (r) => r.fulfill({
  status: 200, contentType: 'image/png',
  body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64'),
}));
await page.route('**/api/nf-authcheck', (r) => r.fulfill({ status: 204, headers: { 'x-nebflow-credential': 'session' } }));
await page.routeWebSocket(/\/ws/, (ws) => {
  wsConnections += 1;
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') {
      if (holdPongMs > 0) { const d = holdPongMs; holdPongMs = 0; setTimeout(() => ws.send(JSON.stringify({ type: 'pong' })), d); }
      else ws.send(JSON.stringify({ type: 'pong' }));
      return;
    }
    if (m.type === 'getHistory') {
      if (m.beforeIndex === undefined) {
        initialHistorySends += 1;
        if (!initSendAt) initSendAt = Date.now();
      }
      histReqs.push(m.beforeIndex === undefined ? 'init' : m.beforeIndex);
      if ((process.env.NB_SWITCH_RACE === '1' || process.env.NB_BOUNDARY_POINTS === '1') && m.sessionId === 'sess-1' && m.beforeIndex !== undefined) {
        heldHistory = () => ws.send(JSON.stringify(pageFor(m)));
      } else {
        ws.send(JSON.stringify({ ...pageFor(m), sessionId: m.sessionId }));
      }
    }
  });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
});

const results = [];
const ok = (name, cond, detail = '') => results.push(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
const rowCount = () => page.evaluate(() => document.querySelectorAll('#chat .row').length);
const bannerUp = () => page.evaluate(() => !!document.getElementById('stand-session-gate'));
const ltSnap = () => page.evaluate(() => ({ ...window.__lt }));

await page.goto(BASE + '/index.html' + (process.env.NB_RAPID_GESTURES === '1' ? '?rapid=1' : ''));
await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 20000 });
// P0 首屏（#923）：初载恰好一页的行数落定（卡片行双计）。等「≥ 期望行数」后
// 立刻读数——首屏若健康则恒等；若发生双挂载会一次性越过，行数读数留给 F1 判。
let firstRows = 0;
let firstScreenAt = 0;
try {
  await page.waitForFunction(
    (exp) => document.querySelectorAll('#chat .row').length >= exp,
    ROWS_PER_PAGE,
    { timeout: 15000 },
  );
  firstRows = await rowCount();
  firstScreenAt = Date.now();
} catch (e) {
  mkdirSync(SHOTS_DIR, { recursive: true });
  await page.screenshot({ path: join(SHOTS_DIR, 'debug-no-history.png') }).catch(() => {});
  console.log('DEBUG boot: first page rows did not reach ' + ROWS_PER_PAGE + ' in 15s — ' + String(e?.message || e).slice(0, 200));
  const dbg = await page.evaluate(() => ({
    rows: document.querySelectorAll('#chat .row').length,
    iframes: document.querySelectorAll('#chat iframe').length,
    cardRows: document.querySelectorAll('#chat .row.card-content, #chat .row[class*="card"]').length,
  })).catch(() => 'eval-failed');
  console.log('DEBUG counts:', JSON.stringify(dbg));
  console.log('pageerrors:', JSON.stringify(errors.slice(0, 8), null, 1));
  console.log('console tail:', wsLines.slice(0, 12).join('\n'));
  await browser.close(); server.close(); process.exit(1);
}
await sleep(600); // 初载 md 升级批落地
if (mobileNoise) {
  await page.evaluate(() => { document.documentElement.style.zoom = '1.25'; });
  await sleep(600);
}
// Empty operation control: same row and scroll position across animation frames.
const noise = await page.evaluate(async () => {
  const chat = document.getElementById('chat');
  const bounds = chat.getBoundingClientRect();
  const row = [...chat.querySelectorAll('.row')].find((r) => {
    const rect = r.getBoundingClientRect();
    return rect.bottom > bounds.top && rect.top < bounds.bottom;
  });
  if (!row) return [];
  const top = row.getBoundingClientRect().top - bounds.top;
  const samples = [];
  for (let i = 0; i < 30; i++) {
    await new Promise(requestAnimationFrame);
    samples.push(row.getBoundingClientRect().top - chat.getBoundingClientRect().top - top);
  }
  return samples;
});
console.log('[noise-css-px] ' + JSON.stringify(noise));
const firstScreenMs = initSendAt ? firstScreenAt - initSendAt : -1;
// P0 判据读数（首屏零双挂载/零重复请求的三件证）：
//   ① 行数恰等一页（双挂载会一次性 ×2）——firstRows 本体；
//   ② init getHistory 恰一发（同游标重复请求 bug 的签名 = ['init',...] 里 init>1）；
//   ③ 非卡行文本唯一（卡片行标签恒同「Card」/iframe 空文本是夹具固有重复，剔除后
//      真实内容行——user/ai/read——必须零重复；双挂载页会在 user/ai/read 上现形）。
const bootInitCount = histReqs.filter((r) => r === 'init').length;
const bootHistReqs = histReqs.slice();
const firstDup = await page.evaluate(() => {
  const texts = [...document.querySelectorAll('#chat .row')]
    // 卡族两行全剔（夹具固有重复）：.card-content 行（iframe 空文本）与承载卡片的
    // tool 行（label 恒同 + 本地化文案）——双挂载页的现形位是 user/ai/read 内容行。
    .filter((r) => !r.querySelector('iframe') && !r.classList.contains('card-content'))
    .map((r) => (r.textContent || '').trim())
    .filter((t) => t.length > 12);
  const seen = {}; let dups = 0;
  for (const t of texts) { seen[t] = (seen[t] || 0) + 1; if (seen[t] > 1) dups++; }
  return dups;
});
await page.evaluate(() => { window.__noReloadMarker = { at: Date.now() }; window.__lt = { n: 0, total: 0, max: 0 }; });
const navAtBoot = navigated;

if (process.env.NB_BOUNDARY_POINTS === '1') {
  const chat = page.locator('#chat');
  await chat.hover();
  await page.mouse.wheel(0, -1);
  await chat.evaluate((c) => { c.scrollTop = 1000; });
  await page.waitForTimeout(200);
  await page.mouse.wheel(0, -650);
  for (let i = 0; i < 100 && !heldHistory; i++) await page.waitForTimeout(20);
  if (!heldHistory || histReqs.length < 2) throw new Error('boundary history request missing');
  const anchors = await chat.evaluate((c) => {
    const rows = [...c.querySelectorAll('.row')].filter((r) => /SEQ-\d+-END/.test(r.textContent));
    const first = rows[0], last = rows[rows.length - 1];
    if (!first || !last) throw new Error('boundary anchors missing');
    window.__boundary = [first, last].map((row) => ({ row, id: row.textContent.match(/SEQ-\d+-END/)[0], before: row.getBoundingClientRect().top }));
    return window.__boundary.map(({ id, before }) => ({ id, before }));
  });
  heldHistory();
  await page.waitForFunction(() => document.querySelectorAll('#chat .row').length > 60, null, { timeout: 20000 });
  const after = await chat.evaluate((c) => window.__boundary.map((a) => ({ id: a.id, same: c.contains(a.row) && a.row.textContent.includes(a.id), before: a.before, after: a.row.getBoundingClientRect().top, error: Math.abs(a.row.getBoundingClientRect().top - a.before) })));
  const pass = after.every((a) => a.same && a.error <= 2);
  console.log('[boundary-edges] ' + JSON.stringify({ anchors, after, requests: histReqs, pass }));
  await browser.close(); server.close(); process.exit(pass ? 0 : 1);
}

if (process.env.NB_SWITCH_RACE === '1') {
  const chat = page.locator('#chat');
  await chat.hover();
  await page.mouse.wheel(0, -1);
  await chat.evaluate((c) => { c.scrollTop = 1000; });
  await page.waitForTimeout(200);
  await page.mouse.wheel(0, -650);
  for (let i = 0; i < 100 && !heldHistory; i++) await page.waitForTimeout(20);
  if (!heldHistory) throw new Error('old session history request was not captured');
  await page.evaluate(async () => (await import('/js/sidebar.js')).switchToSession('sess-2'));
  await page.waitForTimeout(1000);
  const before = await chat.evaluate((c) => ({ scrollTop: c.scrollTop, texts: [...c.querySelectorAll('.row')].map((r) => r.textContent) }));
  heldHistory();
  await page.waitForTimeout(1000);
  const after = await chat.evaluate((c) => ({ scrollTop: c.scrollTop, texts: [...c.querySelectorAll('.row')].map((r) => r.textContent) }));
  const sequences = after.texts.map((text) => text.match(/SEQ-(\d+)-END/)).filter(Boolean).map((match) => Number(match[1]));
  const unique = new Set(sequences).size === sequences.length && sequences.length === LIMIT;
  const pass = before.scrollTop === after.scrollTop && JSON.stringify(before.texts) === JSON.stringify(after.texts) && unique;
  console.log('[switch-race] ' + JSON.stringify({ beforeTop: before.scrollTop, afterTop: after.scrollTop, beforeRows: before.texts.length, afterRows: after.texts.length, sameRows: JSON.stringify(before.texts) === JSON.stringify(after.texts), sequenceCount: sequences.length, unique, requests: histReqs, pass }));
  await browser.close(); server.close(); process.exit(pass ? 0 : 1);
}

// Five real upward wheel inputs. Read the same element after each actual scroll,
// then compare after two frames; the last input crosses the prefetch threshold.
if (process.env.NB_FIVE_WHEELS === '1') {
  const chat = page.locator('#chat');
  await chat.hover();
  await page.mouse.wheel(0, -1);
  await chat.evaluate((c) => { c.scrollTop = 1000; });
  await page.waitForTimeout(300);
  const observations = [];
  let finalAnchor = false;
  for (let i = 0; i < 5; i++) {
    const before = await chat.evaluate((c) => c.scrollTop);
    await page.mouse.wheel(0, -130);
    const sample = await chat.evaluate(async (c) => {
      await new Promise(requestAnimationFrame);
      const bounds = c.getBoundingClientRect();
      const row = [...c.querySelectorAll('.row')].find((r) => { const b = r.getBoundingClientRect(); return b.bottom > bounds.top && b.top < bounds.bottom; });
      const after = c.scrollTop;
      const top = row?.getBoundingClientRect().top - bounds.top;
      window.__fiveLastAnchor = row && { row, top };
      await new Promise(requestAnimationFrame);
      await new Promise(requestAnimationFrame);
      return { after, error: row && c.contains(row) ? Math.abs(row.getBoundingClientRect().top - c.getBoundingClientRect().top - top) : null };
    });
    observations.push({ before, ...sample });
  }
  await page.waitForTimeout(700);
  const finalError = await chat.evaluate((c) => {
    const a = window.__fiveLastAnchor;
    return a && c.contains(a.row) ? Math.abs(a.row.getBoundingClientRect().top - c.getBoundingClientRect().top - a.top) : null;
  });
  finalAnchor = finalError !== null && finalError <= 2;
  const pass = observations.every((o) => o.after < o.before && o.error !== null && o.error <= 2) && histReqs.length > 1 && finalAnchor;
  console.log('[five-wheels] ' + JSON.stringify({ observations, finalError, requests: histReqs, pass }));
  await browser.close(); server.close(); process.exit(pass ? 0 : 1);
}

// Dedicated real-input pagination probe; no test scrollTop writes after input.
if (process.env.NB_WHEEL_PAGE === '1') {
  const chat = page.locator('#chat');
  await chat.hover();
  await page.mouse.wheel(0, -1);
  await chat.evaluate((c) => { c.scrollTop = 1000; });
  await page.waitForTimeout(300);
  const n0 = await rowCount();
  const before = await chat.evaluate((c) => c.scrollTop);
  await page.mouse.wheel(0, -650);
  const capture = await chat.evaluate(async (c) => {
    await new Promise(requestAnimationFrame);
    const bounds = c.getBoundingClientRect();
    const row = [...c.querySelectorAll('.row')].find((r) => { const b = r.getBoundingClientRect(); return b.bottom > bounds.top && b.top < bounds.bottom; });
    window.__realPageAnchor = row && { row, top: row.getBoundingClientRect().top - bounds.top };
    return { scrollTop: c.scrollTop, anchorTop: window.__realPageAnchor?.top };
  });
  await page.waitForFunction((n) => document.querySelectorAll('#chat .row').length > n, n0, { timeout: 20000 });
  await page.waitForTimeout(700);
  const after = await chat.evaluate((c) => {
    const a = window.__realPageAnchor;
    return { scrollTop: c.scrollTop, error: a && c.contains(a.row) ? Math.abs(a.row.getBoundingClientRect().top - c.getBoundingClientRect().top - a.top) : null };
  });
  const pass = capture.scrollTop < before && after.error !== null && after.error <= 2;
  console.log('[real-page] ' + JSON.stringify({ before, capture, after, requests: histReqs, pass }));
  await browser.close(); server.close(); process.exit(pass ? 0 : 1);
}

// Trusted browser input: move the viewport by five upward wheel ticks, then
// retain the visible element while the resulting pagination completes.
let realWheelResult = null;
if (process.env.NB_REAL_WHEEL === '1') {
  const chat = page.locator('#chat');
  await chat.hover();
  const start = await chat.evaluate((c) => c.scrollTop);
  for (let i = 0; i < 5; i++) await page.mouse.wheel(0, -80);
  await page.waitForTimeout(150);
  const end = await chat.evaluate((c) => c.scrollTop);
  const anchor = await chat.evaluate((c) => {
    const bounds = c.getBoundingClientRect();
    const row = [...c.querySelectorAll('.row')].find((r) => { const rect = r.getBoundingClientRect(); return rect.bottom > bounds.top && rect.top < bounds.bottom; });
    window.__wheelAnchor = row && { row, top: row.getBoundingClientRect().top - bounds.top };
    return !!row;
  });
  realWheelResult = { start, end, moved: end < start, anchor };
  console.log('[real-wheel] ' + JSON.stringify(realWheelResult));
}

/** 上滑一页（滚到 250px —— 预取带内、停顶续拉阈值上方）并等行数落定。 */
async function scrollUpOnePage(expectRows, deadlineMs = 20000) {
  const t0 = Date.now();
  const before = await page.evaluate(async () => {
    const c = document.getElementById('chat');
    // A real upward gesture cancels the initial bottom-settle window; a synthetic
    // scroll event alone does not, and can be overwritten before the response.
    // The resulting first-sample error was 9510.84375px: diagnostic frames show
    // settleBottom moved scrollTop 250 -> 9796 before the history page mounted.
    // This timing hazard also warrants scrutiny of older single-sample timings;
    // it does not establish that those timings were actually affected.
    // Five synthetic wheel events exercise intent cancellation only; they have
    // no default scroll action and do not prove five real swipes or single-flight.
    for (let i = 0; i < (location.search.includes('rapid=1') ? 5 : 1); i++) c.dispatchEvent(new WheelEvent('wheel', { bubbles: true, deltaY: -1 }));
    await document.fonts.ready;
    c.scrollTop = 250;
    const bounds = c.getBoundingClientRect();
    const row = [...c.querySelectorAll('.row')].find((r) => {
      const rect = r.getBoundingClientRect();
      return rect.bottom > bounds.top && rect.top < bounds.bottom;
    });
    window.__historyTestAnchor = row && { row, top: row.getBoundingClientRect().top - bounds.top };
    const snapshot = () => ({ scrollTop: c.scrollTop, scrollHeight: c.scrollHeight, rect: row?.getBoundingClientRect().toJSON(), fonts: document.fonts.status });
    const start = snapshot();
    c.dispatchEvent(new Event('scroll'));
    await new Promise(requestAnimationFrame);
    const frame1 = snapshot();
    await new Promise(requestAnimationFrame);
    const frame2 = snapshot();
    return { ...start, twoFramesStable: frame1.scrollTop === frame2.scrollTop && frame1.scrollHeight === frame2.scrollHeight && frame1.rect?.top === frame2.rect?.top, frame1, frame2 };
  });
  console.log('[anchor-before] ' + JSON.stringify({ first: expectRows === ROWS_PER_PAGE * 2, ...before }));
  let stable = 0, last = -1;
  for (;;) {
    await sleep(60);
    const n = await rowCount();
    if (n === last && n >= expectRows) { stable++; if (stable >= 3) break; } else stable = 0;
    last = n;
    if (Date.now() - t0 > deadlineMs) break;
  }
  const after = await page.evaluate(async () => {
    const c = document.getElementById('chat');
    const a = window.__historyTestAnchor;
    const snapshot = () => ({ scrollTop: c.scrollTop, scrollHeight: c.scrollHeight, rect: a?.row.getBoundingClientRect().toJSON(), fonts: document.fonts.status });
    const start = snapshot();
    await new Promise(requestAnimationFrame);
    const frame1 = snapshot();
    await new Promise(requestAnimationFrame);
    const frame2 = snapshot();
    return { ...start, twoFramesStable: frame1.scrollTop === frame2.scrollTop && frame1.scrollHeight === frame2.scrollHeight && frame1.rect?.top === frame2.rect?.top, frame1, frame2,
      error: a && c.contains(a.row) ? Math.abs(a.row.getBoundingClientRect().top - c.getBoundingClientRect().top - a.top) : null };
  });
  console.log('[anchor-after] ' + JSON.stringify({ first: expectRows === ROWS_PER_PAGE * 2, ...after }));
  return { ms: Date.now() - t0, rows: last, error: after.error };
}

// ── P1+P2：前 4 页手动上滑（每页 60 .row：卡片行双计），逐页计时 ──────────
// NB_BENCHMARK_20 measures the 20th prepend only, using the identical real
// renderer and stable-row wait on either source tree. Setup pages are not samples.
if (benchmark20) {
  for (let i = 1; i <= 20; i++) {
    const r = await scrollUpOnePage(ROWS_PER_PAGE * (1 + i));
    console.log(`[benchmark-page] ${i} ${JSON.stringify(r)}`);
    if (r.rows < ROWS_PER_PAGE * (1 + i)) throw new Error(`page ${i} timed out`);
  }
  await browser.close();
  server.close();
  process.exit(0);
}
const pageTimes = [];
const anchorErrors = [];
// 30 empty-frame samples were zero in both measured viewports; allow CSS pixel rounding.
const ANCHOR_TOLERANCE_CSS_PX = 2;
let rows = await rowCount();
for (let i = 1; i <= 4; i++) {
  const expect = ROWS_PER_PAGE * (1 + i);
  const r = await scrollUpOnePage(expect);
  pageTimes.push(r.ms);
  anchorErrors.push(r.error);
  rows = await rowCount();
}
if (realWheelResult) realWheelResult.errorAfterPagination = await page.evaluate(() => {
  const c = document.getElementById('chat'), a = window.__wheelAnchor;
  return a && c.contains(a.row) ? Math.abs(a.row.getBoundingClientRect().top - c.getBoundingClientRect().top - a.top) : null;
});
const manualRows = rows;
// 末段：停在顶部 ⇒ 停顶续拉腿把剩余页自动补齐到全量收口（卡片行双计）。
const parkT0 = Date.now();
await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = 0; c.dispatchEvent(new Event('scroll')); });
for (;;) {
  await sleep(150);
  rows = await rowCount();
  if (rows >= FINAL_ROWS || Date.now() - parkT0 > 25000) break;
}
const maxPageMs = Math.max(...pageTimes);
// Only user messages carry this fixture's unambiguous sequence marker.
const sequenceFixture = process.env.NB_SEQUENCE_FIXTURE === '1' || process.env.NB_MIXED_SEQUENCE === '1';
const userSequence = await page.evaluate((sequenceFixture) => [...document.querySelectorAll('#chat .row')]
  .map((row) => (row.textContent || '').match(sequenceFixture ? /SEQ-(\d+)-END/ : /用户消息 (\d+)：/))
  .filter(Boolean).map((match) => Number(match[1])), sequenceFixture);
const expectedUsers = Array.from({ length: TOTAL }, (_, i) => i).filter((i) => sequenceFixture || i % 5 === 0);
const userSequenceComplete = JSON.stringify(userSequence) === JSON.stringify(expectedUsers);

// ── P4：心跳遮蔽腿 —— 持门 + 桩端压 pong 6.5s（> 5s 宽限）⇒ miss 被遮蔽 ──
// 真实 hbProbe（spec 缝 _hbTestHooks）发 ping 武装宽限；门在宽限到期时仍 busy
// ⇒ 既有遮蔽旁路计数；随后压住的 pong 到场 ⇒ 计数清零、连接不死。
const gate = await page.evaluate(() => !!window.__nfRenderGate);
let probeArmed = false;
let midStats = null;
if (gate) {
  // 停掉自然心跳拍（30s interval）：其 pong 会迟到地清掉 pendingPong，令受控
  // 窗的宽限检查落空（miss 根本不发生）——受控窗内只允许 probe 的那一发 ping。
  await page.evaluate(() => {
    const st = window.Nebflow && window.Nebflow.state;
    if (st && st.heartbeat) { clearInterval(st.heartbeat); st.heartbeat = null; }
  });
  holdPongMs = 6500;          // 先压桩端的 pong 应答（6.5s > 5s 宽限）——顺序必须在前
  await page.evaluate(() => window.__nfRenderGate.begin());
  probeArmed = await page.evaluate(async () => {
    try {
      const mod = await import('/js/ws.js');
      if (typeof mod._hbTestHooks === 'function') { mod._hbTestHooks().probe(); return true; }
    } catch (_) { /* 缝缺席 */ }
    return false;
  });
  // 读数窗：宽限（5s）已过、压住的 pong（6.5s）未到 —— 遮蔽计数在场。
  // （pong 到场会把 shelteredMisses/misses 清零 —— 那是活性证据，读数必须赶在前面。）
  await sleep(5500);
  midStats = await page.evaluate(async () => {
    try { const mod = await import('/js/ws.js'); return typeof mod.hbStats === 'function' ? mod.hbStats() : null; } catch { return null; }
  });
  await page.evaluate(() => window.__nfRenderGate.end());
}
await sleep(2500);            // 压住的 pong 到场 ⇒ 计数清零（活性证据）、连接不死
const statsAfter = (gate && probeArmed)
  ? await page.evaluate(async () => {
      try { const mod = await import('/js/ws.js'); return typeof mod.hbStats === 'function' ? mod.hbStats() : null; } catch { return null; }
    })
  : null;
const evictLines = wsLines.filter((l) => /3 consecutive misses|force reconnect/.test(l));
const shelterLines = wsLines.filter((l) => /sheltered: render gate busy/.test(l));

ok('P0-a 首屏恰一页（行数 = ' + ROWS_PER_PAGE + '，init 恰一发，内容行零重复）',
  firstRows === ROWS_PER_PAGE && bootInitCount === 1 && firstDup === 0,
  `firstRows=${firstRows} initReqs=${bootInitCount} contentDup=${firstDup} histReqs=${JSON.stringify(bootHistReqs)}`);
ok('P0-b 首屏时延 < ' + FIRST_MS + 'ms（init getHistory → 行落定）',
  firstScreenMs >= 0 && firstScreenMs < FIRST_MS, `firstScreen=${firstScreenMs}ms`);
ok('A1 前插后保持同一可见行位置', anchorErrors.every((v) => v !== null && v <= ANCHOR_TOLERANCE_CSS_PX),
  `errors=${JSON.stringify(anchorErrors)} tolerance=${ANCHOR_TOLERANCE_CSS_PX} CSS px`);
if (process.env.NB_REAL_WHEEL === '1') ok('A3 真实 wheel 后滚动及锚定', !!realWheelResult?.moved && realWheelResult.errorAfterPagination !== null && realWheelResult.errorAfterPagination <= ANCHOR_TOLERANCE_CSS_PX, `wheel=${JSON.stringify(realWheelResult)} errors=${JSON.stringify(anchorErrors)}`);
ok('A2 已标记消息序号连续无缺口', userSequenceComplete,
  `observed=${JSON.stringify(userSequence)} expected=${JSON.stringify(expectedUsers)}`);
ok('A4 相邻页边界补页不跳', anchorErrors.slice(1, 3).every((v) => v !== null && v <= ANCHOR_TOLERANCE_CSS_PX),
  `errors=${JSON.stringify(anchorErrors.slice(1, 3))}`);
ok('P1 手动上滑 4 页补进 + 停顶续拉收口全量（卡片行双计）',
  manualRows === ROWS_PER_PAGE * 5 && rows === FINAL_ROWS, `manual=${manualRows} final=${rows}/${FINAL_ROWS} times=${pageTimes.join(',')}ms`);
ok('P2 单页落定 < ' + PAGE_MS + 'ms', maxPageMs < PAGE_MS, `maxPage=${maxPageMs}ms`);
const longtasks = await ltSnap();
ok('P2b 单窗 longtask 最长 < ' + LONGTASK_MS + 'ms（无渲染长阻塞）', longtasks.max < LONGTASK_MS,
  `longtask n=${longtasks.n} total=${Math.round(longtasks.total)}ms max=${Math.round(longtasks.max)}ms`);
ok('P3-a 全程恰好 1 条 WS 连接（零重连）', wsConnections === 1, `connections=${wsConnections}`);
ok('P3-b 零重连初载（getHistory 初载帧只发 1 次）', initialHistorySends === 1, `initial=${initialHistorySends}`);
ok('P3-c 零 heartbeat 判死/强重连日志', evictLines.length === 0, evictLines.slice(0, 2).join(' | ') || 'none');
ok('P3-d 零 sessionGate 横幅', !(await bannerUp()));
ok('P3-e 零 reload（页面标记跨全程存活）',
  await page.evaluate(() => !!window.__noReloadMarker) && navigated === navAtBoot,
  `navigated=${navigated - navAtBoot}`);
ok('P4-a render gate 诊断面在位（window.__nfRenderGate + _hbTestHooks）', gate && probeArmed);
ok('P4-b 遮蔽窗：持门期 pong 迟到被遮蔽（读数窗 sheltered≥1 且 misses==0；pong 到场后清零）',
  !!midStats && midStats.shelteredMisses >= 1 && midStats.misses === 0 &&
  !!statsAfter && statsAfter.misses === 0,
  `mid=${JSON.stringify(midStats)} after=${JSON.stringify(statsAfter)} debug=${shelterLines.length}条`);
ok('R0 主文档无 JS 运行时错误', errors.length === 0, errors.slice(0, 3).join(' | '));

mkdirSync(SHOTS_DIR, { recursive: true });
await page.screenshot({ path: join(SHOTS_DIR, 'final-state.png'), fullPage: false });

console.log(results.join('\n'));
console.log(`\n[metrics] firstScreen=${firstScreenMs}ms firstRows=${firstRows} contentDup=${firstDup}`);
console.log(`[metrics] pageTimes=${pageTimes.join(',')}ms maxPage=${maxPageMs}ms longtask=${JSON.stringify(longtasks)} wsConnections=${wsConnections} initial=${initialHistorySends}`);
console.log(`[metrics] histReqs=${JSON.stringify(histReqs)}`);
console.log('[metrics] domDup=' + JSON.stringify(await page.evaluate(() => {
  const texts = [...document.querySelectorAll('#chat .row')].map(r => (r.textContent || '').slice(0, 36));
  const seen = {}; let dups = 0;
  for (const t of texts) { seen[t] = (seen[t] || 0) + 1; if (seen[t] > 1) dups++; }
  return { total: texts.length, unique: Object.keys(seen).length, dupRows: dups };
})));
const fail = results.filter((r) => r.startsWith('FAIL')).length;
console.log(`\n═══ chat-history-perf: ${results.length - fail} PASS / ${fail} FAIL ═══`);
await browser.close();
server.close();
process.exit(fail ? 1 : 0);
