// nffile-503-backoff.spec.mjs — #750 nf-file 媒体面高载 503 硬失败批验收 spec。
//
// 作者实测（2026-10-22 18:52，五 JVM 并行构建压载，load 28）：文档里点图报
// 「Failed to render: HTTP 503」——网关文件腿过载时上游回裸 503，取数腿直抛进
// viewer 终态错误，用户唯一出路是刷新整页。本批三腿修复的前端面验收：
//
//   站1 过载 503 自动退避：首取 503 ⇒ 恰一次自动重试（nf-file 请求数 = 2）⇒
//       第二击 200 ⇒ 渲染成功（img naturalWidth>0）。
//   站2 429 + Retry-After: 1：同族语义——重试前尊重服务端建议等待（两次请求
//       间隔 ≥ ~0.9s；Retry-After 是服务端建议时长，非任何 deadline）。
//   站3 持续 503：**恰两次**请求（一击自动重试，不成环）⇒ 终态错误面板
//       （"Failed to render: HTTP 503"）+「重试」键；改路 200 点重试 ⇒ 渲染
//       成功（手动重拉，不刷新整页）。
//   站4 确定性失败不重试：500（非过载语义）⇒ **恰一次**请求即终态（重试面
//       只对 429/503 开），错误面板 + 重试键照常在位。
//
// 被测链 = **真实模块链**（零 mock 替身进被测链）：renderFile → image viewer →
// nfViewerUrl → nfTicket.ticketUrl（铸票走 installTicketMock）→ nfViewerSource →
// fetchBlobWithProgress（nf-file 走 page.route mock）。服务端对偶腿（NfServeGate
// 的 429 形态）由 Scala spec（NfServeGateSpec）钉，两侧在此由「429 = 可重试」
// 契约拼合。
//
// harness 纪律：/sw.js 静态 404（「引擎未重启」形态）——SW 活体化会绕过
// page.route mock（nffile-three-tier.spec.mjs 同款纪律）。
//
// 运行：node tests/nffile-503-backoff.spec.mjs

import { chromium, webkit } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8157);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 真实可解码 1×1 PNG（imgcard/nffile-three-tier 同款字节）
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);
const IMG_PATH = '/tmp/imgdoor542/shot.png';

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css', '.json': 'application/json', '.png': 'image/png',
};
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  // /sw.js 必须 404（harness 纪律，见文件头）。
  if (p === '/sw.js') { res.writeHead(404); res.end('nf'); return; }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('nf'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const report = {};
let fail = 0;

/**
 * bootPage — 真实 index.html + 真实 viewer 模块链。nf-file mock 行为由 `script`
 * 决定（每次命中回调；`states` 记 [相对 ms, status]，供站2 的间隔断言）。
 * @param {import('playwright').BrowserType} browserType
 * @param {(hit: number) => Promise<{ status: number, headers?: Record<string,string> }> | { status: number, headers?: Record<string,string> }} nfFileScript
 */
async function bootPage(browserType, nfFileScript) {
  const browser = await browserType.launch();
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  const hits = []; // 每次命中：{ at, status }
  let hit = 0;
  const t0 = Date.now();
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    hit += 1;
    const answer = await nfFileScript(hit);
    hits.push({ at: Date.now() - t0, status: answer.status });
    if (answer.status === 200) {
      return route.fulfill({ status: 200, contentType: 'image/png', body: PNG });
    }
    return route.fulfill({
      status: answer.status, contentType: 'text/plain', body: 'mock overload failure',
      headers: answer.headers || {},
    });
  });
  const ticketMock = await installTicketMock(page);
  // configData 契约（main.js:2256）：`msg.config` = nebflow.json 原文的**字符串**。
  const cfgFrame = { type: 'configData', config: JSON.stringify({ features: {} }) };
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify(cfgFrame));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await sleep(300);
  return { browser, page, errors, hits, ticketMock };
}

/** 文档里点图 = Canvas 图片 viewer 渲染腿（renderFile 单点，真实 image viewer）。
 *  Playwright evaluate 单参约束：arg = { path, size }。 */
const OPEN_IMAGE = async ({ path, size }) => {
  const fv = await import('/js/fileViewers.js');
  let pane = document.getElementById('probe-pane');
  if (!pane) {
    pane = document.createElement('div');
    pane.id = 'probe-pane';
    document.body.appendChild(pane);
  }
  pane.innerHTML = '';
  await fv.renderFile(pane, {
    itemType: 'image', absPath: path, fileName: 'shot.png', size, path,
  });
  return 'ok';
};

async function viewerState(page) {
  return page.evaluate(() => {
    const pane = document.getElementById('probe-pane');
    const img = pane ? pane.querySelector('img') : null;
    const errBox = pane ? pane.querySelector('.canvas-error') : null;
    const retry = pane ? pane.querySelector('[data-nf-retry]') : null;
    return {
      imgLoaded: !!img && img.complete && img.naturalWidth > 0,
      error: !!errBox,
      errText: errBox ? errBox.textContent : '',
      retryBtn: !!retry,
    };
  });
}

for (const [label, engine] of [['chromium', chromium], ['webkit(Safari引擎族)', webkit]]) {
  const rows = {};
  const t = (name, cond, extra = '') => { rows[name] = cond ? 'PASS' : `FAIL${extra ? ' — ' + extra : ''}`; };
  /** 打开文档里的图（异常安全：evaluate 抛错 = 该站全 FAIL，不留悬挂 promise）。 */
  const openOrCatch = async (page) => {
    try { await page.evaluate(OPEN_IMAGE, { path: IMG_PATH, size: PNG.length }); return true; }
    catch (e) { t('站内异常（open）', false, String(e).slice(0, 200)); return false; }
  };

  // ── 站1：过载 503 自动退避（首击 503 → 一次重试 → 200 渲染）──
  {
    const { browser, page, errors, hits } = await bootPage(engine, (n) => (n === 1 ? { status: 503 } : { status: 200 }));
    await openOrCatch(page);
    try { await page.waitForSelector('#probe-pane img', { timeout: 6000 }); } catch { /* 交给断言判 */ }
    await sleep(250);
    const st = await viewerState(page);
    t('1a 首击 503 自动重试后渲染成功', st.imgLoaded === true, JSON.stringify(st));
    t('1b nf-file 恰两次请求（一击自动重试）', hits.length === 2, `hits=${JSON.stringify(hits)}`);
    t('1c 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站2：429 + Retry-After: 1 —— 重试前尊重服务端建议等待 ──
  {
    const { browser, page, errors, hits } = await bootPage(
      engine,
      (n) => (n === 1 ? { status: 429, headers: { 'Retry-After': '1' } } : { status: 200 })
    );
    await openOrCatch(page);
    try { await page.waitForSelector('#probe-pane img', { timeout: 8000 }); } catch { /* 交给断言判 */ }
    await sleep(250);
    const st = await viewerState(page);
    t('2a 429 自动重试后渲染成功', st.imgLoaded === true, JSON.stringify(st));
    t('2b nf-file 恰两次请求', hits.length === 2, `hits=${JSON.stringify(hits)}`);
    const gap = hits.length === 2 ? hits[1].at - hits[0].at : -1;
    t('2c 间隔尊重 Retry-After: 1（≥ ~0.9s）', gap >= 900, `gap=${gap}ms hits=${JSON.stringify(hits)}`);
    t('2d 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站3：持续 503 —— 恰两次即终态 + 重试键手动重拉 ──
  {
    const { browser, page, errors, hits } = await bootPage(engine, () => ({ status: 503 }));
    await openOrCatch(page);
    await sleep(2600); // 首击 + Retry-After 缺席缺省 1s 退避 + 一击重试，全部落定
    const st = await viewerState(page);
    t('3a 恰两次请求（不成环）', hits.length === 2, `hits=${JSON.stringify(hits)}`);
    t('3b 终态错误面板在位（Failed to render: HTTP 503）', st.error === true && st.errText.includes('Failed to render: HTTP 503'), JSON.stringify(st));
    t('3c 「重试」键在位（data-nf-retry）', st.retryBtn === true);
    // 改路 200（新路由注册在后者优先）→ 点重试 → 渲染成功。
    await page.route(/\/api\/nf-file\?/, async (route) => {
      if (await ticketGuard(route)) return;
      return route.fulfill({ status: 200, contentType: 'image/png', body: PNG });
    });
    try { await page.click('#probe-pane [data-nf-retry]'); } catch (e) { t('站内异常（click retry）', false, String(e).slice(0, 200)); }
    try { await page.waitForSelector('#probe-pane img', { timeout: 6000 }); } catch { /* 交给断言判 */ }
    await sleep(250);
    const ok = await viewerState(page);
    t('3d 点重试后渲染成功（手动重拉）', ok.imgLoaded === true, JSON.stringify(ok));
    t('3e 页面零 JS 异常（失败腿不抛）', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站4：确定性失败（500）不重试——重试面只对 429/503 开 ──
  {
    const { browser, page, errors, hits } = await bootPage(engine, () => ({ status: 500 }));
    await openOrCatch(page);
    await sleep(600);
    const st = await viewerState(page);
    t('4a 500 恰一次请求（立即终态）', hits.length === 1, `hits=${JSON.stringify(hits)}`);
    t('4b 错误面板 + 重试键照常在位', st.error === true && st.errText.includes('HTTP 500') && st.retryBtn === true, JSON.stringify(st));
    t('4c 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  report[label] = rows;
  for (const [k, v] of Object.entries(rows)) if (v !== 'PASS') fail += 1;
}

server.close();
console.log('\n═══ nffile-503-backoff 验收 ═══');
for (const [engine, rows] of Object.entries(report)) {
  console.log(`\n— ${engine} —`);
  for (const [k, v] of Object.entries(rows)) console.log(`  ${v === 'PASS' ? '✓' : '✗'} ${k}${v === 'PASS' ? '' : '  ' + v}`);
}
console.log(fail === 0 ? '\nALL PASS' : `\nFAILED: ${fail}`);
process.exit(fail === 0 ? 0 : 1);
