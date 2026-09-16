// attachkey-idem.spec.mjs — 附件发送腿幂等键（clientMsgId）＋ (c) 臂可重试提示 的
// **自包含**真渲染验收（真 Chromium + 真模块图 + 真 DOM + 真 fetch，零实例依赖）。
//
// 批：attachkey-impl 2026-09-17（作者裁定 A ＋ 2026-09-16 17:10 的 (c) 口径）。
// 被测面（唯一改动件）：
//   · src/main/resources/web/js/attachUpload.js（键接入 + (c) 臂）
//   · src/main/resources/web/js/messages.js（**仅 1 行**：`function newClientMsgId()`
//     → `export function newClientMsgId()`；字面 diff 断言见报告）
//
// 运行（本仓既有 Playwright 自包含 spec 形态）：
//   AK_MODE=after  node node_modules/@playwright/test/cli.js test tests/attachkey-idem.spec.mjs
//   AK_MODE=before NEBFLOW_WEB_ROOT=<纯 main 的 web 树> 同一命令  ⇒ 改前红组
// 环境变量：
//   NEBFLOW_WEB_ROOT  被测 web 树（缺省 = 本仓 src/main/resources/web）。改动前对照靠它。
//   AK_MODE           `after`（缺省，改后全组）｜ `before`（改前红组：钉1 红 + (c) 红）
//   AK_OUT            把**全部 wire 读数**（请求原文 / 落行台账）落盘为 JSON（证据件）
//
// 🔴 服务端口径的诚实申报：本 spec 的「服务端」= 页内 route handler 的 **§8.6 语义镜像**
//    （同键重复 ⇒ 不新增行、回放原行），**不是**生产 neblink-server。本 spec 覆盖的是
//    **前端契约面**（键何时铸、传不传、重试复不复用、(c) 提示可不可达、重试是否真走原链）；
//    「网关 → 生产服务端」一跳由 P2-b 批自己的真链证据（`.nebflow/evidence/20260916_p2b-impl/`）
//    与本批的真实例腿（报告 §⑦）承接。🔴 禁把本 spec 的读数冒充生产实测。
// 🔴 零实例依赖：不起任何 gateway 实例（无 sbt、零端口占用）；全部资源由 page.route 从
//    NEBFLOW_WEB_ROOT 读盘供给。

import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = process.env.NEBFLOW_WEB_ROOT
  || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MODE = process.env.AK_MODE === 'before' ? 'before' : 'after';
const AK_OUT = process.env.AK_OUT || '';
const ORIGIN = 'http://localhost:1';
const PAGE_PATH = '/atk-fixture.html';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

// 夹具页：**只**挂载被测模块（真模块图：attachUpload.js → messages.js ⇄ attachUpload.js
// 的循环 import 由此真实走一遍）。DOM 层级逐字镜像真窗：overlay > flow（清理点的可达
// 判据 = 挂载容器 isConnected，故层级必须真实）。
const FIXTURE_HTML = `<!doctype html>
<html><head><meta charset="utf-8"><title>attachkey fixture</title></head>
<body>
  <div id="overlay"><div id="flow"></div></div>
  <script type="module">
    window.__AK = { ready: false };
    try {
      window.__AK.m = await import('/js/attachUpload.js');
      window.__AK.err = null;
    } catch (e) {
      window.__AK.err = String((e && e.message) || e);
    }
    window.__AK.ready = true;
  </script>
</body></html>`;

// ── 迷你服务端（§8.6 语义镜像；**行数**与**请求原文**是全部读数的唯一来源）──
const SRV = {
  rows: [],                 // 真落下的行（判据：条数）
  idem: new Map(),          // clientMsgId -> row
  seq: 0,
  attachSeq: 0,
  failOnce: false,          // 「服务端已落行、响应在回程丢了」：置 true 后每个新指纹首次触发
  failedOnce: new Set(),
  wire: [],                 // 每次请求的原文（method/path/json/bytes）
};
const resetServer = () => {
  SRV.rows.length = 0; SRV.idem.clear(); SRV.failedOnce.clear();
  SRV.seq = 0; SRV.attachSeq = 0; SRV.failOnce = false; SRV.wire.length = 0;
};
/** 上游面唯一写路径：带键且该键已落行 ⇒ 回放原行（不新增行）。 */
function land(convId, body, key, attachments) {
  if (key && SRV.idem.has(key)) return { row: SRV.idem.get(key), replayed: true };
  const row = { n: ++SRV.seq, convId, body, key, attachments };
  SRV.rows.push(row);
  if (key) SRV.idem.set(key, row);
  return { row, replayed: false };
}
const rowsWithBody = (b) => SRV.rows.filter(r => r.body === b);
const wireSends = () => SRV.wire.filter(w => /\/messages$/.test(w.path));
const wireUploads = () => SRV.wire.filter(w => w.path === '/api/attachments');

async function boot(page) {
  resetServer();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    let p;
    try { p = decodeURIComponent(url.pathname); } catch { p = url.pathname; }

    if (p === '/' || p === PAGE_PATH) {
      return route.fulfill({ status: 200, contentType: 'text/html', body: FIXTURE_HTML });
    }
    // ── 附件上传（`attachUpload.uploadOne` 的整件一次请求）──
    if (req.method() === 'POST' && p === '/api/attachments') {
      const bytes = (req.postDataBuffer() || Buffer.alloc(0)).length;
      SRV.wire.push({ at: new Date().toISOString(), method: 'POST', path: p, kind: 'upload', query: url.search, bytes });
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ ok: true, attachmentId: 'a' + (++SRV.attachSeq), receivedBytes: bytes, state: 'uploading' }),
      });
    }
    if (req.method() === 'POST' && /^\/api\/attachments\/[^/]+\/cancel$/.test(p)) {
      SRV.wire.push({ at: new Date().toISOString(), method: 'POST', path: p, kind: 'cancel' });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, cancelled: true }) });
    }
    // ── 发送面（好友 / 群；语义逐条对齐 §8.6）──
    const m = p.match(/^\/api\/(friends\/[^/]+|groups\/[^/]+|devices\/[^/]+)\/messages$/);
    if (req.method() === 'POST' && m) {
      const raw = req.postData() || '';
      let j = {};
      try { j = JSON.parse(raw || '{}'); } catch { /* 非 JSON 体照原文处理 */ }
      const body = typeof j.body === 'string' ? j.body : '';
      const key = (typeof j.clientMsgId === 'string' && j.clientMsgId.length > 0) ? j.clientMsgId : null;
      const att = Array.isArray(j.attachments) ? j.attachments : null;
      SRV.wire.push({
        at: new Date().toISOString(), method: 'POST', path: p, kind: 'send',
        body, clientMsgId: key, attachments: att, rawKeys: Object.keys(j), raw,
      });
      const convId = m[1].startsWith('groups/') ? m[1].slice(7) : 'c1';
      const fp = p + '|' + raw;
      if (SRV.failOnce && !SRV.failedOnce.has(fp)) {
        SRV.failedOnce.add(fp);
        land(convId, body, key, att); // 先真落行……
        return route.fulfill({ status: 502, contentType: 'application/json', body: JSON.stringify({ error: 'harness: response lost after landing' }) }); // ……再丢响应
      }
      const out = land(convId, body, key, att);
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ messageId: 'm' + out.row.n, conversationId: out.row.convId, createdAt: 1700000000, existing: out.replayed }),
      });
    }
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, () => { /* 本 fixture 不用 WS：静默吸收 */ });
  await page.goto(ORIGIN + PAGE_PATH);
  await page.waitForFunction(() => window.__AK && window.__AK.ready === true, null, { timeout: 20000 });
  const err = await page.evaluate(() => window.__AK.err);
  expect(err, `夹具页模块图必须无错加载（循环 import 安全性）: ${err}`).toBeNull();
  await page.evaluate(() => {
    const m = window.__AK.m;
    window.__AK.conv = { conversationId: 'c1', kind: 'friend', friend: { userId: 'u1' } };
    m.renderUploadCards(document.getElementById('flow'), 'c1');
  });
}

/** 一次真实发送动作（真 File 对象 → 真上传 → 真发送）。 */
async function action(page, names, text) {
  return page.evaluate(async ({ names, text }) => {
    const m = window.__AK.m;
    const files = names.map((n) => new File([new Uint8Array([7, 7, 7, 7])], n, { type: 'application/octet-stream' }));
    return await m.sendFiles(window.__AK.conv, files, text);
  }, { names, text });
}

/**
 * 复刻 `messages.js:2463-2485 sendAttachCurrent` 的调用序：`sendFiles` ⇒ **成功则触达
 * 唯一清理点**（`clearSettledUploads`，`messages.js:2484`）；失败面照原样**不**清理
 * （正文与卡留在原地，fail-closed）。🔴 清理点本身 `messages.js` 本批禁改（仅 1 行 hunk），
 * 故 harness 必须在**同一位置**替它触发 —— 这是本 spec 唯一「替调用方说话」的地方，
 * 已在报告显式申报。
 */
async function actionCurrent(page, names, text) {
  const r = await action(page, names, text);
  if (r && r.ok) await page.evaluate(() => window.__AK.m.clearSettledUploads('c1'));
  return r;
}

/** 页内状态读数（模块状态 + DOM 读数，同一趟取，防两趟之间形态漂移）。 */
async function probe(page) {
  return page.evaluate(() => {
    const m = window.__AK.m;
    const items = m.uploadsOf('c1');
    return {
      items: items.map(i => ({ uploadId: i.uploadId, name: i.name, state: i.state, code: i.code, error: i.error, key: i.action && i.action.clientMsgId })),
      hints: typeof m.retryHintsOf === 'function'
        ? m.retryHintsOf('c1').map(h => ({ clientMsgId: h.clientMsgId, count: h.fileNames.length, message: h.message }))
        : null,
      hasRetryHintsApi: typeof m.retryHintsOf === 'function',
      dom: {
        cards: [...document.querySelectorAll('#flow .fm-upload')].map(c => ({
          state: c.dataset.uploadState, id: c.dataset.uploadId, hintKey: c.dataset.hintKey,
          name: c.querySelector('.fm-upload-name') ? c.querySelector('.fm-upload-name').textContent : null,
          note: c.querySelector('.fm-upload-note') ? c.querySelector('.fm-upload-note').textContent : null,
          retryBtn: !!c.querySelector('[data-hint-retry]'),
          retryText: c.querySelector('[data-hint-retry]') ? c.querySelector('[data-hint-retry]').textContent : null,
        })),
        listExists: !!document.querySelector('#flow .fm-upload-list'),
        listConnected: document.querySelector('#flow .fm-upload-list') ? document.querySelector('#flow .fm-upload-list').isConnected : null,
        listHidden: document.querySelector('#flow .fm-upload-list') ? document.querySelector('#flow .fm-upload-list').hidden : null,
      },
    };
  });
}

const clickRetry = (page) => page.click('#flow [data-hint-retry]');
const hintCount = (page) => page.evaluate(() => document.querySelectorAll('#flow [data-upload-hint]').length);
const waitHint = (page, n) => page.waitForFunction(
  (want) => document.querySelectorAll('#flow [data-upload-hint]').length === want, n, { timeout: 15000 });
/** 等**本次**重试的请求真的落到 wire 上（提示数不变 ⇒ waitHint 会立刻返回，不足以定序）。 */
async function waitWire(page, pred, ms = 15000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (pred()) return;
    await page.waitForTimeout(50);
  }
  throw new Error('waitWire timeout');
}

// 证据落盘（逐测试累积，收尾一次写；避免后写覆盖前写）
const DUMPS = [];
// 🔴 必须**深拷贝**：`SRV.rows/wire` 是同一个数组对象，逐测试 reset 是就地清空 ⇒
//    存引用会让所有 dump 都读到最后一次的状态（首版实测踩到：证据全串味）。
const persist = (name) => DUMPS.push({
  test: name, mode: MODE, web: WEB,
  rows: JSON.parse(JSON.stringify(SRV.rows)),
  wire: JSON.parse(JSON.stringify(SRV.wire)),
});
test.afterAll(() => {
  if (AK_OUT) writeFileSync(AK_OUT, JSON.stringify({ mode: MODE, web: WEB, dumps: DUMPS }, null, 2));
});

// ════════════════════════════════════════════════════════════════════════
// 改前红组（AK_MODE=before · NEBFLOW_WEB_ROOT = 纯 main 的 web 树）
// ════════════════════════════════════════════════════════════════════════
test.describe('改前基线（main：无键臂、无 (c) 臂）', () => {
  test.skip(MODE !== 'before', '仅 before 档运行');

  test('钉1 红：同一次发送动作被重复提交 ⇒ 落 2 条', async ({ page }) => {
    await boot(page);
    // ① 动作 A：上传成功、发消息在「已落行但响应丢失」上失败（502）⇒ 现网真实形态
    SRV.failOnce = true;
    const r1 = await actionCurrent(page, ['a.txt'], 'T-A');
    expect(r1.ok).toBe(false);
    const p1 = await probe(page);
    expect(rowsWithBody('T-A').length, '首投：服务端已落 1 行（响应丢失）').toBe(1);
    expect(p1.dom.cards.filter(c => c.state === 'failed').length).toBe(1);
    // ② 同一动作重复提交（改前唯一可达的「重试同一批附件」= 重新提交同一批 / 重复点击）
    SRV.failOnce = false; // 让第二次提交能走完，读数才是纯「重复提交落几条」
    const r2 = await actionCurrent(page, ['a.txt'], 'T-A');
    expect(r2.ok).toBe(true);
    const w = wireSends();
    // 改前红读数：两条落行 + 两次请求体**均无** clientMsgId 键（结构性成因）
    expect(rowsWithBody('T-A').length, '改前：同动作重复提交 ⇒ 落 2 条').toBe(2);
    expect(w.length).toBe(2);
    expect(w.map(x => x.clientMsgId)).toEqual([null, null]);
    expect(w[0].rawKeys.includes('clientMsgId')).toBe(false);
    persist('钉1 红');
  });

  test('(c) 红：下一次成功发送的清理 ⇒ 失败卡被静默清、不可找回', async ({ page }) => {
    await boot(page);
    SRV.failOnce = true;
    await actionCurrent(page, ['a.txt'], 'T-A');            // 动作 A 失败（卡 FAILED）
    SRV.failOnce = false;
    await actionCurrent(page, ['b.txt'], 'T-B');            // 动作 B 成功 ⇒ 清理点被触达
    const p = await probe(page);
    expect(rowsWithBody('T-A').length).toBe(1);
    expect(p.items.length, 'A 的失败卡已被清理点静默清掉').toBe(0);
    expect(p.dom.cards.length).toBe(0);
    expect(p.dom.cards.filter(c => c.retryBtn).length, '改前：无任何重试入口（不可找回）').toBe(0);
    expect(p.hints, '改前连 (c) 面 API 都不存在').toBeNull();
    persist('(c) 红');
  });

  test('零回归基线：两个独立动作各落一条；成功态卡照常清零', async ({ page }) => {
    await boot(page);
    await actionCurrent(page, ['a.txt'], 'T-A');
    await actionCurrent(page, ['b.txt'], 'T-B');
    const p = await probe(page);
    expect(rowsWithBody('T-A').length).toBe(1);
    expect(rowsWithBody('T-B').length).toBe(1);
    expect(p.items.length, '成功态（SENT）卡在清理点照常清零').toBe(0);
    expect(wireSends().map(x => x.clientMsgId)).toEqual([null, null]);
    persist('改前零回归基线');
  });
});

// ════════════════════════════════════════════════════════════════════════
// 改后组（AK_MODE=after · 本支 web 树）
// ════════════════════════════════════════════════════════════════════════
test.describe('改后（本支：键臂 + (c) 臂）', () => {
  test.skip(MODE !== 'after', '仅 after 档运行');

  test('钉2 绿 + 正向零回归：同动作重试只落 1 条；不同动作照常各落一条', async ({ page }) => {
    await boot(page);
    // ① 动作 A：上传成功、发消息「已落行但响应丢失」⇒ FAILED 卡（键存活在卡上）
    SRV.failOnce = true;
    const r1 = await actionCurrent(page, ['a.txt'], 'T-A');
    expect(r1.ok).toBe(false);
    const p1 = await probe(page);
    expect(p1.items.filter(i => i.state === 'failed').map(i => i.code)).toEqual(['message_send_failed']);
    expect(rowsWithBody('T-A').length).toBe(1);
    // 钉 ⑤-2「失败卡存键」：失败卡携带本动作的键，且与 wire 上发出的键逐字相同
    const failedKeys = p1.items.filter(i => i.state === 'failed').map(i => i.key);
    expect(failedKeys.length).toBe(1);
    expect(typeof failedKeys[0]).toBe('string');
    expect(failedKeys[0].length).toBeGreaterThan(0);
    const wireA = wireSends()[0];
    expect(wireA.clientMsgId, '键确实在**请求体**里（网络面读数，非代码阅读）').toBe(failedKeys[0]);
    expect(wireA.rawKeys.includes('clientMsgId')).toBe(true);
    expect(wireA.attachments && wireA.attachments.length).toBe(1);
    const keyA = failedKeys[0];
    // ② 动作 B（**不同**动作）：成功 ⇒ 各落一条 + 触达清理点
    SRV.failOnce = false;
    await actionCurrent(page, ['b.txt'], 'T-B');
    const wireB = wireSends()[1];
    expect(wireB.clientMsgId, '不同动作 ⇒ 新键').not.toBe(keyA);
    expect(rowsWithBody('T-B').length).toBe(1);
    // ③ (c) 臂：清理点上 A 的失败卡**转成一条可重试提示**（不是静默清）
    const p2 = await probe(page);
    expect(p2.items.length, 'A 的失败卡已从卡列表转出').toBe(0);
    expect(p2.hints.length, '(c)：失败动作 ⇒ 恰一条可重试提示').toBe(1);
    expect(p2.hints[0].clientMsgId, '提示携带的是**同一**动作键').toBe(keyA);
    const hintDom = p2.dom.cards.filter(c => c.state === 'retry');
    expect(hintDom.length).toBe(1);
    expect(hintDom[0].hintKey).toBe(keyA);
    expect(hintDom[0].retryBtn, '重试入口真实可达（非死文案）').toBe(true);
    expect(hintDom[0].note, '失败线索不静默丢失').toContain('消息');
    const uploadsBeforeRetry = wireUploads().length;
    // ④ 点重试 ⇒ **真**重走原上传/发送腿，且复用**同一**键
    await clickRetry(page);
    await waitHint(page, 0);
    expect(wireUploads().length, '重试真触发上传腿（新增一次 /api/attachments 请求）').toBeGreaterThan(uploadsBeforeRetry);
    const lastSend = wireSends().slice(-1)[0];
    expect(lastSend.clientMsgId, '重试 = 同动作 ⇒ 复用同一键').toBe(keyA);
    expect(rowsWithBody('T-A').length, '钉2：同动作重试后仍只 1 条（服务端按同键回放）').toBe(1);
    expect(rowsWithBody('T-B').length).toBe(1);
    expect(SRV.rows.length).toBe(2);
    // ④b 与改前**同形对照**（钉1 红 = 两次提交两次落行）：同一动作被提交了 **2 次**
    //     （首投 + 重试）却**只落 1 行** —— 这一对读数是键臂唯一判据的直接对照。
    const sendsA = wireSends().filter(x => x.body === 'T-A');
    expect(sendsA.length, 'T-A 被提交了 2 次（首投 + 重试）').toBe(2);
    expect(new Set(sendsA.map(x => x.clientMsgId)).size, '两次提交 = 同一键').toBe(1);
    expect(rowsWithBody('T-A').length, '但只落 1 条（改前同场景 = 2 条）').toBe(1);
    // ⑤ 正向零回归：再两个**独立**动作 ⇒ 照常各落一条、各得新键
    await actionCurrent(page, ['c.txt'], 'T-C');
    await actionCurrent(page, ['d.txt'], 'T-D');
    expect(rowsWithBody('T-C').length).toBe(1);
    expect(rowsWithBody('T-D').length).toBe(1);
    const keysAll = wireSends().map(x => x.clientMsgId);
    const tail = keysAll.slice(-2);
    expect(tail.every(k => typeof k === 'string' && k.length > 0)).toBe(true);
    expect(new Set(tail).size, '两个新动作各得新键').toBe(2);
    expect(tail.includes(keyA), '新动作 ≠ 旧动作键（无恒键）').toBe(false);
    // ⑥ ②口径对号（显式申报，防误读）：**同一批件 + 新正文** = ② 的「不同动作（新正文）
    //    ⇒ 新键」⇒ 照常**各落一条**。这不是回归 —— 它就是 ② 明文规定的另一侧判据
    //    （「🔴 只验一侧 = 判据不完整」）。同动作的唯一重试入口 = (c) 提示。
    await actionCurrent(page, ['a.txt'], 'T-A2');
    expect(rowsWithBody('T-A2').length, '同批件新正文 = 新动作 ⇒ 照常落一条').toBe(1);
    expect(wireSends().slice(-1)[0].clientMsgId, '新动作 ⇒ 新键').not.toBe(keyA);
    expect(rowsWithBody('T-A').length, '旧动作的行不受影响').toBe(1);
    persist('钉2 绿 + 零回归');
  });

  test('键生命周期钉（stub 级）：一次动作一枚 / 失败卡存键 / 重试复用同键 / 重复触发同键', async ({ page }) => {
    await boot(page);
    // ①「动作入口铸键、一次动作一枚」：一个含 2 件的动作 = 2 次上传 + 1 次发送 ⇒ 恰 1 个键
    await actionCurrent(page, ['x1.txt', 'x2.txt'], 'T-X');
    const w1 = wireSends();
    expect(w1.length).toBe(1);
    expect(w1[0].attachments.length, '2 件同属一动作').toBe(2);
    const keyX = w1[0].clientMsgId;
    expect(typeof keyX === 'string' && keyX.length > 0).toBe(true);
    expect(wireUploads().length).toBe(2);
    // ② 失败卡存键（2 件动作在**发送面**失败 ⇒ 2 张 FAILED 卡，同属一动作、同键）
    SRV.failOnce = true;
    const rf = await actionCurrent(page, ['y1.txt', 'y2.txt'], 'T-Y');
    expect(rf.ok).toBe(false);
    const pF = await probe(page);
    const failed = pF.items.filter(i => i.state === 'failed');
    expect(failed.length).toBe(2);
    const keysY = new Set(failed.map(i => i.key).filter(Boolean));
    expect(keysY.size, '失败卡存的键 = 本动作那**一枚**').toBe(1);
    const keyY = [...keysY][0];
    expect(wireSends().slice(-1)[0].clientMsgId).toBe(keyY);
    // ③ (c) 提示：同键的失败件聚合成**一条**提示（不是每件一条）
    SRV.failOnce = false;
    await actionCurrent(page, ['z.txt'], 'T-Z');            // 成功动作 ⇒ 触达清理点
    const pH = await probe(page);
    expect(pH.hints.length).toBe(1);
    expect(pH.hints[0].clientMsgId).toBe(keyY);
    expect(pH.hints[0].count, '提示覆盖整批 2 件（重试 = 重走整批动作）').toBe(2);
    // ④ 重试**点击两次** ⇒ 两次都必须是**同一键**（同动作重复触发 = 同一键）
    SRV.failOnce = true;                              // 让重试再失败一次，入口得以留存以便二点
    const n0 = wireSends().length;
    await clickRetry(page);
    await waitWire(page, () => wireSends().length === n0 + 1);
    await waitHint(page, 1);
    const n1 = wireSends().length;
    await clickRetry(page);
    await waitWire(page, () => wireSends().length === n1 + 1);
    await waitHint(page, 1);
    const retries = wireSends().slice(-2).map(x => x.clientMsgId);
    expect(retries, '同动作重复触发 = 同一键').toEqual([keyY, keyY]);
    expect(rowsWithBody('T-Y').length, '重试失败也不新增行（同键回放）').toBe(1);
    // ⑤ 恢复：第三次重试成功 ⇒ 提示退场（不留悬空重试入口），行数仍 1
    SRV.failOnce = false;
    await clickRetry(page);
    await waitHint(page, 0);
    const pEnd = await probe(page);
    expect(pEnd.hints.length).toBe(0);
    expect(pEnd.dom.cards.filter(c => c.retryBtn).length).toBe(0);
    expect(rowsWithBody('T-Y').length).toBe(1);
    expect(new Set([keyX, keyY]).size).toBe(2);
    persist('键生命周期钉');
  });

  test('(c) 范围原则 · 对号：关窗（卡片随窗销毁）⇒ 维持全清（零提示）', async ({ page }) => {
    await boot(page);
    SRV.failOnce = true;
    await actionCurrent(page, ['a.txt'], 'T-A');            // FAILED 卡（键在卡上）
    const before = await probe(page);
    expect(before.items.filter(i => i.state === 'failed').length).toBe(1);
    expect(before.dom.listConnected).toBe(true);
    // 复刻关窗序（messages.js:629-646）：overlay.remove() ⇒ clearSettledUploads ⇒ detachUploadCards
    const closed = await page.evaluate(() => {
      const m = window.__AK.m;
      document.getElementById('overlay').remove();   // = closeChat() 的 modalEls.overlay.remove()
      m.clearSettledUploads('c1');
      const out = { hints: m.retryHintsOf('c1').length, items: m.uploadsOf('c1').length };
      m.detachUploadCards();
      return out;
    });
    expect(closed.hints, '窗销毁档 ⇒ 维持全清（连提示一起清）').toBe(0);
    expect(closed.items).toBe(0);
    expect(await hintCount(page), 'DOM 里也不留重试入口').toBe(0);
    persist('(c) 关窗对号');
  });

  test('零回归：上传失败（未发消息）⇒ 卡就地留存；成功态卡照常清零', async ({ page }) => {
    await boot(page);
    // 上传面失败（非发送面）：/api/attachments 回 500 ⇒ 不发消息、卡留原地
    await page.route((u) => u.pathname === '/api/attachments', (route) =>
      route.fulfill({
        status: 500, contentType: 'application/json',
        body: JSON.stringify({ ok: false, code: 'upload_failed', error: 'harness: upload fail' }),
      }));
    const r = await actionCurrent(page, ['boom.txt'], 'T-BOOM');
    expect(r.ok).toBe(false);
    const p = await probe(page);
    expect(p.items.filter(i => i.state === 'failed').length).toBe(1);
    expect(wireSends().length, '上传未成功 ⇒ 一个字节都不发消息').toBe(0);
    const p1 = await probe(page);
    expect(p1.dom.cards.filter(c => c.state === 'failed').length, '上传失败卡就地留存（fail-closed 可见）').toBe(1);
    // 成功路径零回归（同树另起一页）：两独立动作各落一条 + 成功态卡清零
    const page2 = await page.context().newPage();
    await boot(page2);
    await actionCurrent(page2, ['a.txt'], 'T-A');
    await actionCurrent(page2, ['b.txt'], 'T-B');
    const p2 = await probe(page2);
    expect(rowsWithBody('T-A').length).toBe(1);
    expect(rowsWithBody('T-B').length).toBe(1);
    expect(p2.items.length, '成功态（SENT）卡照常清零').toBe(0);
    persist('零回归');
  });
});
