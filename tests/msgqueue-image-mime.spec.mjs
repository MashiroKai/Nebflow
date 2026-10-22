// msgqueue-image-mime.spec.mjs — busy 排队丢图片 mimeType ⇒ 重放永久变文件卡（缺陷 #45 批申报，2026-10-09）。
//
// 缺陷链（file:line 实证）：
//   · busy 入队 ⇒ persistQueue（input.js，函数 persistQueue）把 image 附件压成
//     {type,name} 骨架 —— mimeType/data/preview 全剥；
//   · 页面刷新（或任何 restoreQueue 重挂）⇒ state.messageQueue 被 localStorage
//     骨架**替换**（restoreQueue）⇒ 内存里的真载荷没了；
//   · busy 结束 ⇒ drainMessageQueue / 队列条「立即发送」⇒ 重发帧 attachments map
//     `{mimeType: a.mimeType, data: a.data, …}` ⇒ mimeType/data 均 undefined ⇒
//     JSON.stringify 丢键 ⇒ 帧只剩 {name, hash:'', size:0}；
//   · 后端按 mimeType 分类（WebSocketRoutes.scala:1616 读帧，:1734
//     `mimeType.startsWith("image/") ⇒ type:"image"`）⇒ 缺失 ⇒ 落库
//     `{type:"file", path:null}` ⇒ 历史重放永久文件卡（实证
//     ~/.nebflow/sessions/5cc7590a-….ui.json「截屏2026-10-02 09.08.27.png」行）。
// 修复面：persistQueue 的剥离支保留分类元数据 mimeType+hash+size（载荷 data/preview
//   按既有取舍继续不落 localStorage —— 图片动辄数百 KB，数据无刷新时本就在内存队列）。
//
// 🔴 **载荷形态**：本 spec 的 base64/预览全部为 FIXTURE（人工构造的 1x1 PNG），
//    经 page.evaluate 注入模块状态，不来自任何真实运行实例。
// 🔴 **零实例依赖**：不起任何 gateway 实例（无 sbt、无端口占用）；页面资源由
//    page.route 从 NEBFLOW_WEB_ROOT 指向的 web 树读盘供给，WS 由 routeWebSocket 模拟。
// 🔴 改动前对照（验红）：NEBFLOW_WEB_ROOT=<改动前 web 树绝对路径> ⇒ K2（核心腿）
//    必红；K1/K4 与修复面零接触 ⇒ 两侧均绿（夹具+选择器+帧拦截本身可用的证明）。
//
// 运行：node node_modules/@playwright/test/cli.js test tests/msgqueue-image-mime.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

// 改动前/后对照开关：仅测试夹具用（缺省 = 本仓 src/main/resources/web）。
const WEB = process.env.NEBFLOW_WEB_ROOT
  || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'mq-imgmime-root';
const UPLOADS_PATH = '/Users/kaiyu/.nebflow/uploads/' + SID + '/1700000000000_busy-a.png';

// ── FIXTURE（1x1 真 PNG，可解码；🔴 非生产实测）──────────────────────────
const B64_PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const PREVIEW_PNG = 'data:image/png;base64,' + B64_PNG;
const ATT_IMG = {
  type: 'image', mimeType: 'image/png', data: B64_PNG,
  name: 'busy-a.png', preview: PREVIEW_PNG,
};
// 重发帧右值 = input.js send()/sendImmediate/drainMessageQueue 三处同一映射的
// 字面期望（非实现镜像）：{mimeType, data, name, hash:'', size:0}。
const FRAME_ATTS = [{ mimeType: 'image/png', data: B64_PNG, name: 'busy-a.png', hash: '', size: 0 }];
// 修前重发帧（persistQueue 骨架经同一映射）：mimeType/data 均 undefined ⇒ JSON 丢键。
const PREFIX_FRAME_ATTS = [{ name: 'busy-a.png', hash: '', size: 0 }];

// ── 帧拦截：routeWebSocket 的客户端 → 服务端帧在 node 侧落账 ──
const CAP = { frames: [] };
const wireFrames = () => CAP.frames.filter((f) => f && Array.isArray(f.attachments));

async function bootApp(page) {
  CAP.frames.length = 0;
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    // 后端 uploads 路由替身：恒 200 回 1x1 PNG（重放渲染腿的取字节面）。
    if (p.startsWith('/uploads/')) {
      return route.fulfill({ status: 200, contentType: 'image/png', body: Buffer.from(B64_PNG, 'base64') });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      CAP.frames.push(msg);
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForFunction(async (sid) => {
    const cv = await import('/js/chatView.js');
    const v = cv.findViewBySessionId(sid);
    return !!(v && v.mounted && v.dom && v.dom.input && v.dom.attPreview && v.dom.queueBar);
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(200);
}

/** 走真实入队通路：置 busy ⇒ 输入框带图片附件 ⇒ input.send() ⇒ queueMessage。 */
async function enqueueViaSend(page, text, atts) {
  return page.evaluate(async ({ sid, text, atts }) => {
    const state = (await import('/js/state.js')).default;
    const cv = await import('/js/chatView.js');
    const input = await import('/js/input.js');
    const view = cv.findViewBySessionId(sid);
    if (!view) throw new Error('no view for ' + sid);
    state.busySessionIds.add(sid);          // 模拟「LLM 忙」= 入队前置条件
    view.isSending = false;
    view.skillMode = null; view.compactMode = false; view.stream.askMode = false;
    view.pendingAttachments = atts.map((a) => ({ ...a }));
    view.dom.input.value = text;
    input.send();
    const q = state.messageQueue[sid] || [];
    const item = q[q.length - 1];
    return { queueLen: q.length, itemId: item.id, clientMessageId: item.clientMessageId };
  }, { sid: SID, text, atts });
}

/** 排队呈现回退批：队列动作的 UI 入口 = 乐观气泡右键菜单（msgQuote.js →
 *  input.js runQueuedAction）。寻址 = 行 data-msg-id = clientMessageId。
 *  refreshEquivalent 为页内动作（无 reload），乐观气泡仍在 DOM ⇒ 菜单可点。 */
async function clickQueueMenu(page, clientMessageId, label) {
  await page.evaluate(async ({ clientMessageId, label }) => {
    const row = document.querySelector(`.row.user[data-msg-id="${clientMessageId}"]`);
    if (!row) throw new Error('no queued bubble row for ' + clientMessageId);
    row.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, clientX: 400, clientY: 300 }));
    const btn = [...document.querySelectorAll('.fm-context-menu button[role="menuitem"]')]
      .find((b) => b.textContent === label);
    if (!btn) throw new Error('menu item not found: ' + label);
    btn.click();
  }, { clientMessageId, label });
  await page.waitForTimeout(150);
}

/** 刷新等价动作（msgqueue-recall-attach J6 同款）：persistQueue 已在入队时执行，
 *  restoreQueue 把 localStorage 骨架**替换**回 state.messageQueue；qattachvault 批
 *  追加水合腿（hydrateQueueFromVault：载荷库 IndexedDB 字节灌回内存项）——等价于
 *  真刷新后「boot restoreQueue → 派发闸挂水合之后」的次序，测试 await 之。 */
async function refreshEquivalent(page) {
  return page.evaluate(async (sid) => {
    const state = (await import('/js/state.js')).default;
    const input = await import('/js/input.js');
    input.persistQueue();
    const raw = localStorage.getItem(Object.keys(localStorage).find((k) => k.includes('message_queue')));
    input.restoreQueue();
    await input.hydrateQueueFromVault();
    const q = state.messageQueue[sid] || [];
    return { raw, head: JSON.parse(JSON.stringify(q[0] || null)), queueLen: q.length };
  }, SID);
}

/** 读指定队列项的当前形态（内存态）。 */
async function readQueueItem(page, itemId) {
  return page.evaluate(async ({ sid, itemId }) => {
    const state = (await import('/js/state.js')).default;
    const it = (state.messageQueue[sid] || []).find((i) => i.id === itemId);
    return it ? JSON.parse(JSON.stringify(it)) : null;
  }, { sid: SID, itemId });
}

// ── 后端分类镜像（判据逐条抄自 WebSocketRoutes.scala，零实现改动）──────────
// :1616 mimeType 缺失 ⇒ ""；:1621 image 分支需 mimeType.startsWith("image/") && data 非空；
// :1734 attJson type = mimeType.startsWith("image/") ? "image" : "file"；path = 已落盘才有。
function backendAttJsonMirror(frameAtt) {
  const mimeType = typeof frameAtt.mimeType === 'string' ? frameAtt.mimeType : '';
  const data = typeof frameAtt.data === 'string' ? frameAtt.data : '';
  const isImage = mimeType.startsWith('image/');
  return {
    name: frameAtt.name || '',
    type: isImage ? 'image' : 'file',
    // image+data ⇒ 落 uploads（savedPaths 命中）⇒ path 真值；其余 ⇒ Json.Null。
    path: isImage && data ? UPLOADS_PATH : null,
  };
}

// ═══════════════════════════════════════════════════════════════════════════
// K1 · 无刷新 drain 重发 = 直接发送逐字同帧（与修复面零接触 ⇒ 两侧均绿的对照腿）
// ═══════════════════════════════════════════════════════════════════════════
test('K1 无刷新 drain：重发帧 attachments 与直接发送映射逐字相等（data 本就在内存队列）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  const enq = await enqueueViaSend(page, 'K1-NO-REFRESH', [ATT_IMG]);
  expect(enq.queueLen).toBe(1);

  CAP.frames.length = 0;
  await page.evaluate(async (sid) => {
    const input = await import('/js/input.js');
    input.drainMessageQueue(sid);
  }, SID);
  await page.waitForTimeout(150);
  const fr = wireFrames();
  expect(fr.length, 'drain 应恰好发出一帧带 attachments 的 user-message').toBe(1);
  expect(fr[0].content).toBe('K1-NO-REFRESH');
  expect(fr[0].attachments, 'K1: 无刷新重发帧 = 直接发送同帧（mimeType+data 齐全）').toStrictEqual(FRAME_ATTS);
  expect(pageErrors).toEqual([]);
});

// ═══════════════════════════════════════════════════════════════════════════
// K2 · 核心腿：刷新等价（restoreQueue 骨架替换 + 载荷库水合）⇒ 内存项字节回补、
//      重发帧带全量 data（跨刷新图片可落盘 = path 真值的前提）
// ═══════════════════════════════════════════════════════════════════════════
test('K2 刷新等价后重发：载荷库水合回补 data/preview ⇒ drain/立即发送帧都带全量图片字节', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  const enq = await enqueueViaSend(page, 'K2-REFRESH-DRAIN', [ATT_IMG]);
  expect(enq.queueLen).toBe(1);

  // 刷新等价：骨架替换 + 水合回补。前提断言 = localStorage 面「剥离照旧」（载荷
  // 不落 localStorage 的既有取舍不变，mimeType/hash/size 分类元数据存活）；内存面
  // 「字节回补」（qattachvault：data 从载荷库灌回，preview 按 mimeType 重建）。
  const restored = await refreshEquivalent(page);
  expect(restored.raw, '前提：persistQueue 必须已写入 localStorage 队列快照').toBeTruthy();
  expect(restored.raw, '既有取舍不变：localStorage 面不落 base64 载荷（骨架 + stripped 标记）')
    .toContain('"stripped":true');
  expect(restored.raw, '既有取舍不变：localStorage 面不落 base64 载荷字节').not.toContain(B64_PNG);
  expect(restored.head).not.toBeNull();
  const att = restored.head.attachments[0];
  expect(att.type).toBe('image');
  expect(att.name).toBe('busy-a.png');
  expect(att.mimeType, '分类判据 mimeType 存活（busyfix-image-mime 沿革）').toBe('image/png');
  expect(att.data, 'qattachvault：载荷库水合 ⇒ 内存项 data 回补（派发帧的字节源）').toBe(B64_PNG);
  expect(att.preview, 'qattachvault：image preview 按 mimeType 从 data 重建（气泡媒体卡字节源）')
    .toBe(PREVIEW_PNG);
  expect(att.stripped, 'qattachvault：回补成功 ⇒ stripped 标记摘除（不再算「载荷已失」）').toBeUndefined();

  // (a) drain 重发腿：帧带 mimeType + 全量 data（服务端据 data 落 uploads ⇒ path 真值）。
  CAP.frames.length = 0;
  await page.evaluate(async (sid) => {
    const input = await import('/js/input.js');
    input.drainMessageQueue(sid);
  }, SID);
  await page.waitForTimeout(250);
  const frA = wireFrames();
  expect(frA.length).toBe(1);
  expect(frA[0].attachments[0].mimeType, 'K2a: 刷新后 drain 帧 mimeType 必须存活').toBe('image/png');
  expect(frA[0].attachments[0].data, 'K2a: 载荷库水合 ⇒ 帧带全量字节（修前 = undefined ⇒ path:null）').toBe(B64_PNG);
  expect(frA[0].attachments[0].name).toBe('busy-a.png');

  // (b) 队列条「立即发送」腿（sendImmediate 同映射）：再入队+再刷新+点按钮。
  const enq2 = await enqueueViaSend(page, 'K2-REFRESH-IMMEDIATE', [ATT_IMG]);
  await refreshEquivalent(page);
  const it2 = await readQueueItem(page, enq2.itemId);
  expect(it2.attachments[0].mimeType).toBe('image/png');
  expect(it2.attachments[0].data, 'K2b 前提：二次刷新等价同样水合回补').toBe(B64_PNG);
  CAP.frames.length = 0;
  await clickQueueMenu(page, enq2.clientMessageId, '立即发送');
  const frB = wireFrames();
  expect(frB.length, '立即发送应恰好发出一帧').toBe(1);
  expect(frB[0].attachments[0].mimeType, 'K2b: 刷新后立即发送帧 mimeType 必须存活').toBe('image/png');
  expect(frB[0].attachments[0].data, 'K2b: 立即发送帧同样带全量字节').toBe(B64_PNG);
  expect(pageErrors).toEqual([]);
});

// ═══════════════════════════════════════════════════════════════════════════
// K3 · 后端落库形态镜像：WebSocketRoutes.scala:1616/1734 判据跑在捕获帧上
//      qattachvault：刷新帧（水合回补）⇒ image + uploads path 真值（跨刷新可落盘）；
//      修前帧（mimeType 缺失）⇒ file+null 缺陷形态存档。
// ═══════════════════════════════════════════════════════════════════════════
test('K3 后端分类镜像：无刷新帧与刷新帧（水合回补）⇒ 均 image+uploads；修前帧 ⇒ file+null（缺陷形态存档）', async ({ page }) => {
  await bootApp(page);

  // 帧 1：无刷新 drain（mimeType+data 齐全）。
  await enqueueViaSend(page, 'K3-NO-REFRESH', [ATT_IMG]);
  CAP.frames.length = 0;
  await page.evaluate(async (sid) => { (await import('/js/input.js')).drainMessageQueue(sid); }, SID);
  await page.waitForTimeout(150);
  const frameNoRefresh = wireFrames()[0].attachments[0];

  // 帧 2：刷新等价后 drain（骨架替换 + 载荷库水合回补 data）。
  await enqueueViaSend(page, 'K3-REFRESH', [ATT_IMG]);
  await refreshEquivalent(page);
  CAP.frames.length = 0;
  await page.evaluate(async (sid) => { (await import('/js/input.js')).drainMessageQueue(sid); }, SID);
  await page.waitForTimeout(250);
  const frameRefresh = wireFrames()[0].attachments[0];

  // 修前重发帧 = persistQueue 骨架经同一映射（mimeType/data 均 undefined ⇒ 丢键）。
  // 独立写出缺陷形态（字面期望），证明镜像判据真的能把「修前」判成 file。
  const framePreFix = PREFIX_FRAME_ATTS[0];

  expect(backendAttJsonMirror(frameNoRefresh),
    '无刷新重发：后端必落 type:image + uploads path（= 非 busy 直接发送）')
    .toStrictEqual({ name: 'busy-a.png', type: 'image', path: UPLOADS_PATH });
  expect(backendAttJsonMirror(frameRefresh),
    '刷新后重发（qattachvault）：水合回补 data ⇒ 落 uploads + path 真值（修前 = path:null 永久「媒体加载失败」）')
    .toStrictEqual({ name: 'busy-a.png', type: 'image', path: UPLOADS_PATH });
  expect(backendAttJsonMirror(framePreFix),
    '缺陷存档：修前帧（mimeType 缺失）正是 type:file + path:null 的重放文件卡')
    .toStrictEqual({ name: 'busy-a.png', type: 'file', path: null });
});

// ═══════════════════════════════════════════════════════════════════════════
// K4 · 重放渲染：真模块 buildUserAttachmentRows —— image 行出媒体预览（uploads
//      取字节、像素真解码）；修前 file 行只出文件卡（负例对照）。
//      （src→200→像素腿由 uploads-restore-image A1-A3 家族独立覆盖，此处引用之。）
// ═══════════════════════════════════════════════════════════════════════════
test('K4 重放渲染：修复后落库形态（type:image+path）重放出媒体预览；修前形态（type:file path:null）= 文件卡', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  const rows = await page.evaluate(async ({ uploadsPath }) => {
    const persistence = await import('/js/persistence.js');
    const mk = (atts) => {
      const row = document.createElement('div');
      const mediaRow = persistence.buildUserAttachmentRows(row, atts);
      if (mediaRow) {
        // 行插到 body 顶部 = 重放用户视口内（popArtifacts.js:316 loading='lazy'
        // 对视口外行不取字节；历史重放可见 ⇒ 行在视口内，测试对齐之）。
        document.body.insertBefore(mediaRow, document.body.firstChild);
        mediaRow.scrollIntoView({ block: 'center' });
        return mediaRow.outerHTML;
      }
      return null;
    };
    return {
      fixed: mk([{ name: 'busy-a.png', type: 'image', path: uploadsPath }]),
      preFix: mk([{ name: 'busy-a.png', type: 'file', path: null }]),
    };
  }, { uploadsPath: UPLOADS_PATH });
  expect(pageErrors).toEqual([]);

  // 修复后：独立媒体行 + <img>，src 走 uploads 路由（resolver 产出编码形态）。
  expect(rows.fixed, 'type:image+path ⇒ 必须产出 .user-media 媒体行').toContain('user-media');
  expect(rows.fixed).toContain('<img');
  expect(rows.fixed).toContain('/uploads/');

  // 修前负例：type:file+path:null ⇒ 同一行构建器但只出文件卡（'PNG' 徽章面），无 <img>。
  expect(rows.preFix, '修前形态 ⇒ 文件卡面（重放文件卡形态存档）').toContain('file-card');
  expect(rows.preFix).not.toContain('<img');

  // 真解码：img 挂载后 naturalWidth>0（路由替身 200 回 1x1 PNG）。
  // 注意 popArtifacts.js:316 `img.loading='lazy'`：行在视口外时 Chromium 不取字节
  // —— 与重放用户行为对齐：行滚入视口（历史重放可见）⇒ 取字节腿触发。
  await page.waitForFunction(() => {
    const img = document.querySelector('.row.user-media img');
    return img && img.naturalWidth > 0;
  }, null, { timeout: 8000 });
  const src = await page.evaluate(() => document.querySelector('.row.user-media img').src);
  expect(decodeURIComponent(new URL(src).pathname)).toBe('/uploads/' + SID + '/1700000000000_busy-a.png');
  // 页面上恰好：1 个媒体预览（修复后腿）+ 1 个文件卡（修前存档腿）。
  expect(await page.evaluate(() => document.querySelectorAll('.row.user-media img').length)).toBe(1);
  expect(await page.evaluate(() => document.querySelectorAll('.file-card').length)).toBe(1);
});

// ═══════════════════════════════════════════════════════════════════════════
// K5 · 撤回两腿（qattachvault）：
//      (a) 库命中（常态）：刷新等价后撤回 ⇒ 字节回补、无「载荷丢失」告警，
//          回填件带 data+preview = 原样可重发；
//      (b) 库不命中（真丢：库被清）：撤回 ⇒ stripped 告警照旧 + 元数据回落，
//          且该项在派发闸下拒绝出线（失败可见，禁空心帧）。
// ═══════════════════════════════════════════════════════════════════════════
test('K5 撤回：库命中 ⇒ 字节回补原样可重发（无告警）；库丢失 ⇒ 告警照旧 + 派发闸拒发', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const warns = [];
  page.on('console', (m) => { if (m.type() === 'warning') warns.push(m.text()); });
  await bootApp(page);

  // (a) 库命中腿。
  const enq = await enqueueViaSend(page, 'K5-RECALL', [ATT_IMG]);
  await refreshEquivalent(page);
  warns.length = 0;

  await clickQueueMenu(page, enq.clientMessageId, '撤回');

  expect(warns.some((w) => w.includes('lost their payload to page-refresh persistence')),
    'K5a: 载荷已回补 ⇒ 不得再报「载荷丢失」告警').toBe(false);
  const pending = await page.evaluate(async (sid) => {
    const cv = await import('/js/chatView.js');
    return JSON.parse(JSON.stringify(cv.findViewBySessionId(sid).pendingAttachments));
  }, SID);
  expect(pending.length).toBe(1);
  expect(pending[0].data, 'K5a: 回填件带完整字节（qattachvault 水合）——撤回即原样可重发').toBe(B64_PNG);
  expect(pending[0].preview).toBe(PREVIEW_PNG);
  expect(pending[0].mimeType).toBe('image/png');
  expect(pending[0].name).toBe('busy-a.png');

  // (b) 库不命中腿（wipe 模拟真丢）⇒ 派发闸拒发 + 告警照旧。
  const enq2 = await enqueueViaSend(page, 'K5-RECALL-LOST', [ATT_IMG]);
  await page.evaluate(async () => { await (await import('/js/queueVault.js')).wipe(); });
  await refreshEquivalent(page); // 水合不中 ⇒ stripped 保留
  const lost = await readQueueItem(page, enq2.itemId);
  expect(lost.attachments[0].data, 'K5b 前提：库已清 ⇒ 字节不回补').toBeUndefined();
  expect(lost.attachments[0].stripped, 'K5b 前提：stripped 保留 = 载荷已失判据在场').toBe(true);

  CAP.frames.length = 0;
  await page.evaluate(async (sid) => {
    const state = (await import('/js/state.js')).default;
    state.busySessionIds.delete(sid); // 闸腿直接走 drain 派发台（不再入队）
    (await import('/js/input.js')).drainMessageQueue(sid);
  }, SID);
  await page.waitForTimeout(250);
  expect(wireFrames().length, 'K5b: 载荷丢失 ⇒ 拒绝出线（禁空心帧/path:null 形态）').toBe(0);
  const lostAfter = await readQueueItem(page, enq2.itemId);
  expect(lostAfter.failed, 'K5b: 派发闸 ⇒ 项标 failed（显式失败态）').toBe(true);
  expect(lostAfter.failCode).toBe('payload_lost');
  expect(warns.length === 0 || warns.every((w) => !w.includes('K5b-noise')), 'K5b: 无异常告警').toBe(true);

  // 失败面可见（排队呈现回退批重锚）：头行红色失败计数 + 「立即发送」对失败项
  // 不在菜单给出；撤回 = 可重传入口（气泡右键菜单）。
  await page.waitForSelector('#queue-bar .queue-bar-failed', { state: 'visible', timeout: 8000 });
  expect(await page.locator('#queue-bar .queue-bar-failed').textContent()).toContain('1 条发送失败');
  const failMenuLabels = await page.evaluate(async (clientMessageId) => {
    const row = document.querySelector(`.row.user[data-msg-id="${clientMessageId}"]`);
    row.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, clientX: 400, clientY: 300 }));
    return [...document.querySelectorAll('.fm-context-menu button[role="menuitem"]')].map((b) => b.textContent);
  }, enq2.clientMessageId);
  expect(failMenuLabels, 'K5b: 失败项菜单无「立即发送」').not.toContain('立即发送');
  expect(failMenuLabels).toContain('撤回');
  await page.evaluate(() => document.querySelector('.fm-context-menu')?.remove());
  warns.length = 0;
  await clickQueueMenu(page, enq2.clientMessageId, '撤回');
  expect(warns.some((w) => w.includes('lost their payload to page-refresh persistence')),
    'K5b: 载荷真丢的撤回 ⇒ 可判读告警照旧（stripped 标记权威支）').toBe(true);
  expect(pageErrors).toEqual([]);
});
