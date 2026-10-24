// nf-ticket-403-heal.spec.mjs — Pop 图片 403 批（2026-10-24 作者报障「Failed to
// render: HTTP 403 看不到图」反复出现）前端面验收 spec。
//
// 根因形状（修复批取证，详见仓内报告）：
//   · 网关形状 403（X-Nf-Reason: invalid-ticket/expired/path-mismatch）——引擎
//     重启内存票仓清零 / 票过期，页内缓存票变废票；
//   · relay 门形状 403（link-server require_user：JSON 体 {"error":"Missing
//     token"} + 无 X-Nf-Reason）——隧道面中继凭据死，请求根本不到网关。
// 修前取数腿（nfViewerSource → fetchBlobWithProgress）401/403 直抛「HTTP 403」
// 进 viewer 终态错误面板：无重试、无提示升级路径（<img> onerror 腿的 reMint
// 自愈够不到 fetch 抛出面）。本 spec 钉四站：
//
//   站1 网关 403(invalid-ticket) 自愈：首击 403 ⇒ 自动重铸一票重试（nf-ticket
//       恰 2 次铸票）⇒ 第二击 200 ⇒ 渲染成功。
//   站2 持续 403(invalid-ticket)：恰两次 nf-file 请求（不成环）⇒ 终态面板文案
//       带判据 reason（"HTTP 403 (invalid-ticket)"——可见退化）+ 重试键在位。
//   站3 relay 门形状 403（JSON {"error":"Missing token"}）：nf-file 恰 1 次请求
//       （重铸无意义不烧铸票）⇒ 升级可见——sessionGate 横幅（#stand-session-gate，
//       reload 已被测试缝摘掉）+ 终态错误面板在位。
//   站4 确定性判据拒 403(credential-path)：重铸恰一次（铸票 2 次）二次同败即
//       终态（reason 可见 "HTTP 403 (credential-path)"），不成环。
//
// 被测链 = 真实模块链（renderFile → image viewer → nfViewerUrl → nfViewerSource
// → fetchBlobWithProgress；铸票走 installTicketMock；nf-file 走 page.route mock）。
// 服务端对偶腿（403 deny 日志可见性 + tunnel-mark 等权）由 Scala spec
// NfFileDenyLogSpec 钉。
//
// harness 纪律：/sw.js 静态 404（「引擎未重启」形态）——SW 活体化会绕过
// page.route mock（nffile-three-tier.spec.mjs / nffile-503-backoff.spec.mjs 同款）。
//
// 运行：node tests/nf-ticket-403-heal.spec.mjs

import { chromium, webkit } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8163);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 真实可解码 1×1 PNG（nffile-503-backoff 同款字节）
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);
const IMG_PATH = '/tmp/nf403heal/shot.png';

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
 * 决定（每次命中回调）； relayGate 形态（JSON 体、无 X-Nf-Reason）由 status 403
 * + jsonBody 触发。
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
  const hits = []; // 每次命中：{ at, status, reason }
  let hit = 0;
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    hit += 1;
    const answer = await nfFileScript(hit);
    hits.push({ at: Date.now(), status: answer.status, reason: (answer.headers || {})['X-Nf-Reason'] || '' });
    if (answer.jsonBody) {
      return route.fulfill({
        status: answer.status, contentType: 'application/json', body: answer.jsonBody,
        headers: answer.headers || {},
      });
    }
    if (answer.status === 200) {
      return route.fulfill({ status: 200, contentType: 'image/png', body: PNG });
    }
    return route.fulfill({
      status: answer.status, contentType: 'text/plain', body: 'mock denial',
      headers: answer.headers || {},
    });
  });
  const ticketMock = await installTicketMock(page);
  // configData 契约（main.js）：`msg.config` = nebflow.json 原文的**字符串**。
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

/** 打开文档里的图 = Canvas 图片 viewer 渲染腿（renderFile 单点，真实 image viewer）。
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

/** 摘掉 sessionGate 的自愈 reload（断言横幅被拉起而不真导航）+ 冷却清零。 */
const DISARM_GATE = async () => {
  const sg = await import('/js/sessionGate.js');
  sg._sessionGateTestHooks().setReload(() => {});
  sg._sessionGateTestHooks().reset();
  return 'ok';
};

async function viewerState(page) {
  return page.evaluate(() => {
    const pane = document.getElementById('probe-pane');
    const img = pane ? pane.querySelector('img') : null;
    const errBox = pane ? pane.querySelector('.canvas-error') : null;
    const retry = pane ? pane.querySelector('[data-nf-retry]') : null;
    const gate = document.getElementById('stand-session-gate');
    return {
      imgLoaded: !!img && img.complete && img.naturalWidth > 0,
      error: !!errBox,
      errText: errBox ? errBox.textContent : '',
      retryBtn: !!retry,
      gateBanner: !!gate,
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

  // ── 站1：网关 403(invalid-ticket) 自愈——重铸一票重试后渲染成功 ──
  {
    const { browser, page, errors, hits, ticketMock } = await bootPage(engine, (n) =>
      n === 1 ? { status: 403, headers: { 'X-Nf-Reason': 'invalid-ticket' } } : { status: 200 });
    await openOrCatch(page);
    try { await page.waitForSelector('#probe-pane img', { timeout: 6000 }); } catch { /* 交给断言判 */ }
    await sleep(250);
    const st = await viewerState(page);
    t('1a 首击 403(invalid-ticket) 重铸重试后渲染成功', st.imgLoaded === true, JSON.stringify(st));
    t('1b nf-file 恰两次请求（一击自愈重试）', hits.length === 2, `hits=${JSON.stringify(hits)}`);
    t('1c 铸票恰两次（初始 + 重铸）', ticketMock.count() === 2, `mints=${ticketMock.count()}`);
    t('1d 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站2：持续 403(invalid-ticket) —— 恰两次即终态 + reason 可见 ──
  {
    const { browser, page, errors, hits, ticketMock } = await bootPage(engine, () =>
      ({ status: 403, headers: { 'X-Nf-Reason': 'invalid-ticket' } }));
    await openOrCatch(page);
    await sleep(800);
    const st = await viewerState(page);
    t('2a 恰两次 nf-file 请求（不成环）', hits.length === 2, `hits=${JSON.stringify(hits)}`);
    t('2b 铸票恰两次（重铸一次后定局）', ticketMock.count() === 2, `mints=${ticketMock.count()}`);
    t('2c 终态文案带判据 reason（HTTP 403 (invalid-ticket)）', st.error === true && st.errText.includes('Failed to render: HTTP 403 (invalid-ticket)'), JSON.stringify(st));
    t('2d 「重试」键在位（data-nf-retry）', st.retryBtn === true);
    t('2e 不拉 sessionGate 横幅（网关形状 ≠ relay 平面死亡）', st.gateBanner === false, JSON.stringify(st));
    t('2f 页面零 JS 异常（失败腿不抛）', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站3：relay 门形状 403（JSON Missing token）—— 升级可见，不烧铸票 ──
  {
    const { browser, page, errors, hits, ticketMock } = await bootPage(engine, () =>
      ({ status: 403, jsonBody: '{"error":"Missing token"}' }));
    try { await page.evaluate(DISARM_GATE); } catch (e) { t('站内异常（disarm）', false, String(e).slice(0, 200)); }
    await openOrCatch(page);
    await sleep(800);
    const st = await viewerState(page);
    t('3a nf-file 恰 1 次请求（relayGate 不重试不烧票）', hits.length === 1, `hits=${JSON.stringify(hits)}`);
    t('3b 铸票恰 1 次（无重铸）', ticketMock.count() === 1, `mints=${ticketMock.count()}`);
    t('3c sessionGate 横幅拉起（升级可见路径）', st.gateBanner === true, JSON.stringify(st));
    t('3d 终态错误面板在位（HTTP 403 文案）', st.error === true && st.errText.includes('HTTP 403'), JSON.stringify(st));
    t('3e 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站4：确定性判据拒 403(credential-path) —— 重铸一次后定局，reason 可见 ──
  {
    const { browser, page, errors, hits, ticketMock } = await bootPage(engine, () =>
      ({ status: 403, headers: { 'X-Nf-Reason': 'credential-path' } }));
    await openOrCatch(page);
    await sleep(800);
    const st = await viewerState(page);
    t('4a 恰两次 nf-file 请求（重铸一次，不成环）', hits.length === 2, `hits=${JSON.stringify(hits)}`);
    t('4b 铸票恰两次', ticketMock.count() === 2, `mints=${ticketMock.count()}`);
    t('4c 终态文案带判据 reason（HTTP 403 (credential-path)）', st.error === true && st.errText.includes('Failed to render: HTTP 403 (credential-path)'), JSON.stringify(st));
    t('4d 不拉 sessionGate 横幅（判据拒 ≠ 凭据面死亡）', st.gateBanner === false, JSON.stringify(st));
    t('4e 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  report[label] = rows;
  for (const [k, v] of Object.entries(rows)) if (v !== 'PASS') fail += 1;
}

server.close();
console.log('\n═══ nf-ticket-403-heal 验收 ═══');
for (const [engine, rows] of Object.entries(report)) {
  console.log(`\n— ${engine} —`);
  for (const [k, v] of Object.entries(rows)) console.log(`  ${v === 'PASS' ? '✓' : '✗'} ${k}${v === 'PASS' ? '' : '  ' + v}`);
}
console.log(fail === 0 ? '\nALL PASS' : `\nFAILED: ${fail}`);
process.exit(fail === 0 ? 0 : 1);
