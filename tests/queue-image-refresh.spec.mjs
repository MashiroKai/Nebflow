// queue-image-refresh.spec.mjs — qattachvault 批（发送图片 path:null 载荷剥离修复）验收。
//
// 缺陷链（file:line 定性，详见 input.js qattachvault 簇注释）：
//   选图（input/attachments.js addFileAttachment ⇒ {type:'image', data, preview}）
//   → WS 未开 enqueueOfflineMessage / LLM 忙 queueMessage（内存项带全量字节）
//   → persistQueue（input.js）把 image 附件剥成 {type,name,mimeType,hash:'',size:0,
//     stripped:true}（data/preview 从不落任何介质）→ 页面刷新 restoreQueue 骨架替换
//   → 派发腿帧 `data: undefined` ⇒ JSON 丢键 ⇒ 服务端（WebSocketRoutes.scala:1621
//     image 落 uploads 需 data.nonEmpty）判「无数据」⇒ 零字节落盘 ⇒ ui.json 落
//     path:null ⇒ 消息已送达、图片文件从未落盘 ⇒ 接收面永远「媒体加载失败」。
//
// 修复面：queueVault.js（IndexedDB 载荷库）+ input.js（入队写字节 / 刷新水合回补 /
// 派发闸拒绝空心帧 ⇒ 显式失败态可重传）。
//
// 本 spec = 真发送链（真 web 树 + 真模块图 + 真页面 reload；WS = routeWebSocket 桩）：
//   A · 断线带图入队 → 整页刷新 → 重连自动补发：帧带**全量图片字节**（跨刷新字节
//      存活）+ 后端落库镜像 = type:image + uploads path 真值；再刷新一次走真历史
//      重放（type:image+path ⇒ .user-media <img>）⇒ **图片可加载断言**（真解码）。
//   B · 载荷真丢（库被清）→ 刷新 → 重连：**拒绝出线**（零空心帧）+ 队列条显式失败态
//      （.failed + 失败注记 + 立即发送退场）+ failed 随 persistQueue 落盘；撤回 =
//      可判读告警 + 附件元数据回输入框；重新添加附件后发送 = **可重传闭环**（帧带全量字节）。
//   C · 在线零回归：正常在线发图即时出线、队列恒空。
//   D · 源面静态钉：入队写字节 / 补发循环跳过 failed / failed 随持久化落盘。
//
// 红线自检：断言面 = 线上帧 + 队列 DOM + localStorage/IDB 读数 + 历史重放渲染，不触碰样式值。
//
// Run: node tests/queue-image-refresh.spec.mjs
// 自包含：静态服务器随机隔离端口 + routeWebSocket 桩，finally 必关。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 's-qvault-e2e';
const UPLOADS_PATH = '/uploads/' + SID + '/1700000000000_refresh-a.png';

// ── FIXTURE（1x1 真 PNG，可解码；🔴 非生产实测）──────────────────────────
const B64_PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const PREVIEW_PNG = 'data:image/png;base64,' + B64_PNG;
const ATT_IMG = { type: 'image', mimeType: 'image/png', data: B64_PNG, name: 'refresh-a.png', preview: PREVIEW_PNG };
// 派发帧右值 = input.js 三条派发腿同一映射的字面期望（非实现镜像）。
const FRAME_ATTS = [{ mimeType: 'image/png', data: B64_PNG, name: 'refresh-a.png', hash: '', size: 0 }];

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩服务端状态 ────────────────────────────────────────────────────
let currentWs = null;
let serverDown = false;        // true = 新连接立即掐断（断线窗）
let connSeq = 0;
const conns = [];              // 每条连接的入站帧日志
/** getHistory 的替身响应（null = 空历史）；真历史重放腿用它喂「已落库形态」。 */
let stubHistory = null;

function isUserContentFrame(m) {
  return !m.type && typeof m.content === 'string';
}

function wireUp(ws) {
  if (serverDown) { try { ws.close(); } catch { /* noop */ } return; }
  currentWs = ws;
  const log = { seq: ++connSeq, inbound: [] };
  conns.push(log);
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    log.inbound.push(m);
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      const messages = Array.isArray(stubHistory) ? stubHistory : [];
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages, hasMore: false, offset: 0, total: messages.length }));
      return;
    }
    if (isUserContentFrame(m)) {
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: true }));
      setTimeout(() => {
        try {
          ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: false }));
          ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId, model: 'mock-llm' }));
        } catch { /* 连接已被测试关闭 */ }
      }, 120);
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '队列载荷库E2E' }], activeId: SID, folders: [] }));
}

const inboundUserFramesOf = (content) => conns.flatMap(c => c.inbound).filter(m => isUserContentFrame(m) && m.content === content);

// ── 静态服务器（/uploads/** = 后端 uploads 路由替身：恒 200 回 1x1 PNG）────────
const PNG_BYTES = Buffer.from(B64_PNG, 'base64');
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (path.startsWith('/uploads/')) {
      res.writeHead(200, { 'Content-Type': 'image/png' });
      res.end(PNG_BYTES);
      return;
    }
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}'); // /api/* 探针一律 200 空对象（boot 静音）
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
let ctx = null;
try {
  ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
    // 刻意不清 message_queue 键（addInitScript 每次导航都跑，会抹掉被测持久化轨）。
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));

  const clientConnected = () => page.evaluate(async () => {
    const s = await import('/js/state.js');
    return s.default.connected === true;
  });
  const mediaImgs = () => page.evaluate(() => Array.from(document.querySelectorAll('.row.user-media img'))
    .map(i => ({ src: i.src, w: i.naturalWidth })));
  const queuedItems = () => page.evaluate(() => {
    try {
      const raw = localStorage.getItem(Object.keys(localStorage).find((k) => k.includes('message_queue')));
      return Object.values(JSON.parse(raw || '{}')).flat();
    } catch { return []; }
  });
  /** 走真实 send() 入队/发送（WS 状态决定落 offline 队列还是即时出线）。 */
  const sendViaInput = (text, atts) => page.evaluate(async ({ sid, text, atts }) => {
    const cv = await import('/js/chatView.js');
    const input = await import('/js/input.js');
    const view = cv.findViewBySessionId(sid);
    if (!view) throw new Error('no view for ' + sid);
    view.isSending = false;
    view.skillMode = null; view.compactMode = false; view.stream.askMode = false;
    view.pendingAttachments = atts.map(a => ({ ...a }));
    view.dom.input.value = text;
    input.send();
  }, { sid: SID, text, atts });
  /** 断线窗进入/退出（f1 同款）。 */
  const enterOutage = async () => {
    serverDown = true;
    const seq = conns.length > 0 ? conns[conns.length - 1].seq : 0;
    try { await currentWs.close(); } catch { /* 已断 */ }
    const deadline = Date.now() + 8000;
    while (Date.now() < deadline) {
      if (!(await clientConnected())) return seq;
      await sleep(100);
    }
    throw new Error('client did not observe disconnect in time');
  };
  const waitForUserFrame = async (content, timeoutMs = 20000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const f = inboundUserFramesOf(content);
      if (f.length > 0) return f;
      await sleep(100);
    }
    return inboundUserFramesOf(content);
  };
  const reloadBoot = async () => {
    await page.reload({ waitUntil: 'load' });
    await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  };

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1500);

  // ══ A · 断线带图入队 → 整页刷新 → 重连自动补发（全量字节）→ 历史重放可加载 ══
  const seqA = await enterOutage();
  await sendViaInput('offline img refresh', [ATT_IMG]);
  await sleep(600);

  ok('A1 断线发送零出线', inboundUserFramesOf('offline img refresh').length === 0,
    `inbound=${inboundUserFramesOf('offline img refresh').length}`);
  ok('A2 乐观气泡媒体卡上屏（data URI 字节源）', (await mediaImgs()).some(i => i.src.startsWith('data:image/png')),
    JSON.stringify(await mediaImgs()));
  const qA = await queuedItems();
  ok('A3 入队持久化（offline:true + pinned id + 载荷剥离标记）',
    qA.length === 1 && qA[0].offline === true && qA[0].attachments?.[0]?.stripped === true,
    JSON.stringify(qA.map(i => ({ offline: i.offline, stripped: i.attachments?.[0]?.stripped }))));
  const pinnedA = qA[0]?.clientMessageId;
  const itemIdA = qA[0]?.id;
  ok('A4 载荷库已落字节（IndexedDB，键 = sid:itemId:attIdx）', await page.evaluate(async ({ sid, itemId, b64 }) => {
    const { queueVaultKey, get } = await import('/js/queueVault.js');
    return (await get(queueVaultKey(sid, itemId, 0))) === b64;
  }, { sid: SID, itemId: itemIdA, b64: B64_PNG }));

  serverDown = false; // 刷新后首连即通
  await reloadBoot();
  const framesA = await waitForUserFrame('offline img refresh');
  await sleep(1200); // 帧出线后的呈现收口（materialize 等）
  ok('A5 刷新后重连自动补发恰一帧', framesA.length === 1, `frames=${framesA.length}`);
  ok('A6 补发帧 = 入队钉死的同一 clientMessageId', framesA.length === 1 && framesA[0].clientMessageId === pinnedA,
    `frame=${framesA[0]?.clientMessageId}, pinned=${pinnedA}`);
  ok('A7 补发帧带全量图片字节（跨刷新字节存活，修前 = undefined ⇒ JSON 丢键）',
    framesA.length === 1 && JSON.stringify(framesA[0].attachments) === JSON.stringify(FRAME_ATTS),
    JSON.stringify(framesA[0]?.attachments));
  // 后端落库镜像（判据逐条抄自 WebSocketRoutes.scala:1621/:1734，零实现改动）：
  // image 落 uploads 需 mimeType.startsWith("image/") && data.nonEmpty ⇒ path 真值。
  const mirrorA = (att) => ({
    name: att?.name || '',
    type: (typeof att?.mimeType === 'string' && att.mimeType.startsWith('image/')) ? 'image' : 'file',
    path: (typeof att?.mimeType === 'string' && att.mimeType.startsWith('image/') && typeof att?.data === 'string' && att.data) ? UPLOADS_PATH : null,
  });
  ok('A8 后端落库镜像 = type:image + uploads path 真值（path:null 缺陷形态已闭）',
    framesA.length === 1 && JSON.stringify(mirrorA(framesA[0].attachments[0]))
      === JSON.stringify({ name: 'refresh-a.png', type: 'image', path: UPLOADS_PATH }),
    JSON.stringify(framesA.length === 1 ? mirrorA(framesA[0].attachments[0]) : null));
  ok('A9 队列排空 + 载荷库清账（派发成功即清）', (await queuedItems()).length === 0
    && await page.evaluate(async ({ sid, itemId }) => {
      const { queueVaultKey, get } = await import('/js/queueVault.js');
      return (await get(queueVaultKey(sid, itemId, 0))) === null;
    }, { sid: SID, itemId: itemIdA }));

  // 跨刷新可加载断言（真历史重放）：桩喂「已落库形态」（type:image+path）⇒ boot
  // getHistory → historyPage 整替渲染 → .user-media <img> 走 /uploads/ 路由 ⇒ 真解码。
  stubHistory = [{ type: 'user', text: 'offline img refresh', timestamp: 1700000000000, attachments: [{ name: 'refresh-a.png', type: 'image', path: UPLOADS_PATH }] }];
  await reloadBoot();
  let loaded = null;
  try {
    await page.waitForFunction(() => {
      const img = document.querySelector('.row.user-media img');
      return img && img.naturalWidth > 0;
    }, null, { timeout: 12000 });
    loaded = await mediaImgs();
  } catch { loaded = await mediaImgs(); }
  ok('A10 跨刷新历史重放：媒体行 <img> 走 /uploads/ 路由且真解码（图片可加载）',
    loaded.some(i => i.src.includes(UPLOADS_PATH) && i.w > 0),
    JSON.stringify(loaded));
  stubHistory = null;

  // ══ B · 载荷真丢（库被清）⇒ 拒绝出线 + 显式失败态 + 撤回重编可重传 ═══════════
  const seqB = await enterOutage();
  await sendViaInput('offline img lost', [ATT_IMG]);
  await sleep(600);
  ok('B1 断线入队第二项（offline:true）', (await queuedItems()).length === 1
    && (await queuedItems())[0].offline === true, JSON.stringify(await queuedItems()));
  await page.evaluate(async () => { await (await import('/js/queueVault.js')).wipe(); }); // 模拟真丢

  serverDown = false;
  await reloadBoot();
  await sleep(2500); // boot → 水合（不命中）→ 首连 flush → 派发闸
  const framesB = inboundUserFramesOf('offline img lost');
  ok('B2 载荷真丢 ⇒ 拒绝出线（零空心帧，禁 path:null 形态）', framesB.length === 0, `frames=${framesB.length}`);
  ok('B3 项留队且标 failed（显式失败态，随 persistQueue 落盘）',
    (await queuedItems()).length === 1 && (await queuedItems())[0].failed === true
    && (await queuedItems())[0].failCode === 'payload_lost',
    JSON.stringify(await queuedItems().then(q => q.map(i => ({ failed: i.failed, code: i.failCode })))));
  ok('B4 队列条失败面：.failed 行 + 失败注记 + 「立即发送」退场',
    await page.evaluate(() => {
      const row = document.querySelector('.queue-item.failed');
      return !!row && row.dataset.queueFailCode === 'payload_lost'
        && !!row.querySelector('.queue-item-failed-note')
        && row.querySelector('.queue-item-btn.immediate') === null;
    }));
  ok('B5 失败项不被补发楔死（跳过 failed，队首无假「待送达」补渲）',
    (await page.evaluate(() => document.querySelectorAll('.msg-pending-mark').length)) === 0);

  // 撤回 = 可重传入口：可判读告警 + 附件元数据回输入框。
  const warns = [];
  page.on('console', m => { if (m.type() === 'warning') warns.push(m.text()); });
  await page.click('.queue-item.failed .queue-item-btn.recall');
  await sleep(400);
  ok('B6 撤回：可判读告警（载荷丢失不静默）',
    warns.some(w => w.includes('lost their payload to page-refresh persistence')), JSON.stringify(warns));
  const pendingB = await page.evaluate(async (sid) => {
    const cv = await import('/js/chatView.js');
    return JSON.parse(JSON.stringify(cv.findViewBySessionId(sid).pendingAttachments));
  }, SID);
  ok('B7 撤回：附件元数据回输入框（name/mimeType 在、字节不在 = 待重补）',
    pendingB.length === 1 && pendingB[0].name === 'refresh-a.png' && pendingB[0].mimeType === 'image/png'
    && !pendingB[0].data, JSON.stringify(pendingB));

  // 可重传闭环：用户重新添加附件（重新持有字节）⇒ 在线发送 ⇒ 帧带全量字节。
  await sendViaInput('offline img lost', [ATT_IMG]);
  const framesB2 = await waitForUserFrame('offline img lost');
  ok('B8 重传闭环：重补附件后在线发送恰一帧、带全量字节（path 真值）',
    framesB2.length === 1 && JSON.stringify(framesB2[0].attachments) === JSON.stringify(FRAME_ATTS),
    JSON.stringify(framesB2[0]?.attachments));
  ok('B9 重传后队列排空', (await queuedItems()).length === 0);

  // ══ C · 在线零回归：正常在线发图即时出线、队列恒空 ═══════════════════════
  await sendViaInput('online img direct', [ATT_IMG]);
  const framesC = await waitForUserFrame('online img direct');
  ok('C1 在线发图即时出线、带全量字节', framesC.length === 1
    && JSON.stringify(framesC[0].attachments) === JSON.stringify(FRAME_ATTS));
  ok('C2 在线发图不入队', (await queuedItems()).length === 0);

  // ══ D · 源面静态钉（与实现同树同读数）══════════════════════════════════
  const src = await readFile(join(WEB, 'js', 'input.js'), 'utf8');
  ok('D1 入队即落载荷库（queueMessage → vaultItemPayloads）',
    /vaultItemPayloads\(sid, item\);/.test(src) && /function vaultItemPayloads/.test(src));
  ok('D2 派发闸：补发循环跳过 failed + 载荷丢失拒绝',
    /it\.offline && !it\.failed/.test(src) && /lostPayloadAttsOf\(item\)\.length > 0/.test(src));
  ok('D3 failed 失败态随 persistQueue 落盘（跨刷新失败面不丢）',
    /failed: it\.failed === true/.test(src));
  ok('D4 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 4)));

  console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
} catch (e) {
  failures++;
  console.error('SPEC ERROR:', e);
} finally {
  await browser.close().catch(() => {});
  server.close();
}
process.exit(failures === 0 ? 0 : 1);
