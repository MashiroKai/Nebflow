// msgqueue-recall-attach.spec.mjs — 消息队列「撤回」带附件消息 ⇒ 附件恢复（自包含，零实例依赖）。
//
// 批：msgqueue-recall-attach-impl 2026-09-14（方案 A · 最小面 · 单函数）。
// 真源：.nebflow/reports/20260914_205108_msgqueue-recall-attach-forensics.md §1.1（面归属 = 甲 ·
//      客户端消息队列撤回）/ §2.2（主因 = input.js recallQueuedItem 不消费 item.attachments）
//      / §4（判据 J1–J8）。
// 修复面：src/main/resources/web/js/input.js 的 recallQueuedItem（唯一改动件）。
//
// 🔴 **载荷形态**：本 spec 的附件载荷（base64 / hash / 文件名）全部为 FIXTURE（人工构造），
//    经 page.evaluate 直接注入模块状态，不来自任何真实运行实例或真实上传链路。
//    🔴 禁把本 fixture 读数冒充生产实测（见节点报告「未证项」）。
// 🔴 **零实例依赖**：不起任何 gateway 实例（无 sbt、无端口占用）；页面全部资源由 page.route
//    从 NEBFLOW_WEB_ROOT 指向的 web 树读盘供给，WS 由 page.routeWebSocket 模拟。
//
// 运行（本仓既有 Playwright 自包含 spec 形态）：
//   node node_modules/@playwright/test/cli.js test tests/msgqueue-recall-attach.spec.mjs
// 改动前对照（验红）：NEBFLOW_WEB_ROOT=<改动前 web 树绝对路径> 同一命令 ⇒ 撤回组（J1–J7）必红，
//   J8 回归组（与修复面零接触）两侧均绿 —— 后者绿证明「夹具 + 选择器 + 帧拦截」本身可用，
//   即前者的红是修复面本身的红，不是夹具坏。

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

const SID = 'mq-recall-root';

// ── FIXTURE 附件载荷（人工构造；🔴 非生产实测）───────────────────────────
// 1x1 PNG（真实可解码，preview 走 chat.js:2479 的 image 分支 ⇒ .att-thumb）。
const B64_PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const PREVIEW_PNG = 'data:image/png;base64,' + B64_PNG;
// 文本附件：走 chat.js:2496 的 else 分支（无 preview）⇒ .att-file。
const B64_TXT = 'cmVjYWxsLW5vdGVzLWNvbnRlbnQ=';           // "recall-notes-content" (21 B)
const HASH_TXT = '3d1a2f7c9b4e05a6c8d2f10b7e93a4c5d6f8091b2c3d4e5f60718293a4b5c6d7';

const ATT_IMG = {
  type: 'image', mimeType: 'image/png', data: B64_PNG,
  name: 'recall-a.png', preview: PREVIEW_PNG,
};
const ATT_TXT = {
  type: 'text', mimeType: 'text/plain', data: B64_TXT,
  name: 'recall-notes.txt', hash: HASH_TXT, size: 21,
};
// 入队形态 = 逐字快照（J2 深比较的右值；与 ATT_* 同形，独立写出以防夹具被改而不自知）。
const ENQ_ATTS = [
  { type: 'image', mimeType: 'image/png', data: B64_PNG, name: 'recall-a.png', preview: PREVIEW_PNG },
  { type: 'text', mimeType: 'text/plain', data: B64_TXT, name: 'recall-notes.txt', hash: HASH_TXT, size: 21 },
];
// 重发帧形态（J4 右值）：input.js:672-676 的映射逐字写在测试里（字面期望，非实现镜像）。
const FRAME_ATTS = [
  { mimeType: 'image/png', data: B64_PNG, name: 'recall-a.png', hash: '', size: 0 },
  { mimeType: 'text/plain', data: B64_TXT, name: 'recall-notes.txt', hash: HASH_TXT, size: 21 },
];

// ── 帧拦截（J4/J8）：routeWebSocket 的客户端 → 服务端帧在 node 侧落账 ──
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
  // 主视图挂载 + 输入面三锚就位（attPreview / queueBar / input）。
  await page.waitForFunction(async (sid) => {
    const cv = await import('/js/chatView.js');
    const v = cv.findViewBySessionId(sid);
    return !!(v && v.mounted && v.dom && v.dom.input && v.dom.attPreview && v.dom.queueBar);
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(200);
}

// 页内公共句柄（每步独立 evaluate，避免把模块句柄跨上下文传递）。
const PROBE = `const state = (await import('/js/state.js')).default;
  const cv = await import('/js/chatView.js');
  const view = cv.findViewBySessionId(window.__MQ_SID);
  if (!view) throw new Error('no mounted view for ' + window.__MQ_SID);`;

/** 走真实入队通路：置 busy ⇒ 输入框带附件 ⇒ input.send() ⇒ queueMessage（input.js:594-602）。 */
async function enqueueViaSend(page, text, atts) {
  return page.evaluate(async ({ sid, text, atts }) => {
    window.__MQ_SID = sid;
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
    return {
      queueLen: q.length,
      itemId: item.id,
      enqueued: JSON.parse(JSON.stringify(item.attachments)),
      itemText: item.text,
      pendingAfterEnqueue: view.pendingAttachments.length,
    };
  }, { sid: SID, text, atts });
}

/** 再入队（J3 往返）：清空输入框文本除外的一切前置，复用已恢复的 pendingAttachments。 */
async function reenqueueCurrent(page, text) {
  return page.evaluate(async ({ sid, text }) => {
    window.__MQ_SID = sid;
    const state = (await import('/js/state.js')).default;
    const cv = await import('/js/chatView.js');
    const input = await import('/js/input.js');
    const view = cv.findViewBySessionId(sid);
    state.busySessionIds.add(sid);
    view.isSending = false;
    view.dom.input.value = text;
    const pending = JSON.parse(JSON.stringify(view.pendingAttachments));
    input.send();
    const q = state.messageQueue[sid] || [];
    const item = q[q.length - 1];
    return { itemId: item.id, enqueued: JSON.parse(JSON.stringify(item.attachments || [])), pendingAtEnqueue: pending };
  }, { sid: SID, text });
}

/** 读撤回后的输入面状态（pendingAttachments / 输入框文本 / 队列 / 输入框预览 DOM 锚）。 */
async function readRecallState(page) {
  return page.evaluate(async (sid) => {
    window.__MQ_SID = sid;
    const state = (await import('/js/state.js')).default;
    const cv = await import('/js/chatView.js');
    const view = cv.findViewBySessionId(sid);
    const attPreview = view.dom.attPreview;
    return {
      pending: JSON.parse(JSON.stringify(view.pendingAttachments)),
      inputText: view.dom.input.value,
      queue: JSON.parse(JSON.stringify(state.messageQueue[sid] || [])),
      domThumbs: attPreview.querySelectorAll('.att-thumb').length,
      domFiles: attPreview.querySelectorAll('.att-file').length,
      domFileNames: Array.from(attPreview.querySelectorAll('.att-file')).map((e) => e.textContent),
      domTotal: attPreview.children.length,
      queueBarItems: view.dom.queueBar.querySelectorAll('.queue-item[data-queue-id]').length,
      queueBarThumbs: view.dom.queueBar.querySelectorAll('.queue-item-att-thumb').length,
      queueBarFiles: view.dom.queueBar.querySelectorAll('.queue-item-att-file').length,
      queueBarMore: view.dom.queueBar.querySelectorAll('.queue-item-att-more').length,
      queueBarMoreText: view.dom.queueBar.querySelector('.queue-item-att-more')?.textContent ?? null,
    };
  }, SID);
}

/** 走真实 UI 通路撤回：队列条「撤回」按钮（chatQueue.js:172-180 → input.js:804）。 */
async function clickRecall(page, queueId) {
  const sel = queueId === undefined
    ? '.queue-item .queue-item-btn.recall'
    : `.queue-item[data-queue-id="${queueId}"] .queue-item-btn.recall`;
  await page.waitForSelector(sel, { state: 'visible', timeout: 8000 });
  await page.click(sel);
  await page.waitForTimeout(120);
}

/** 召回后重发（会话已 idle）：走正常 send() ⇒ sendWs 帧（input.js:668-680）。 */
async function resendNow(page, text) {
  await page.evaluate(async ({ sid, text }) => {
    window.__MQ_SID = sid;
    const state = (await import('/js/state.js')).default;
    const cv = await import('/js/chatView.js');
    const input = await import('/js/input.js');
    const view = cv.findViewBySessionId(sid);
    state.busySessionIds.delete(sid);       // idle ⇒ 走正常发送面
    view.isSending = false;
    view.dom.input.value = text;
    input.send();
  }, { sid: SID, text });
  await page.waitForTimeout(150);
}

const collectWarns = (page) => {
  const warns = [];
  page.on('console', (m) => { if (m.type() === 'warning') warns.push(m.text()); });
  return warns;
};
const recallWarns = (warns) => warns.filter((w) => w.includes('recallQueuedItem'));

// ═══════════════════════════════════════════════════════════════════════════
// J8 · 回归对照（与修复面零接触 ⇒ 修复前即应通过）
// ═══════════════════════════════════════════════════════════════════════════
test('J8 回归对照：sendImmediate(input.js:853) / drainMessageQueue(input.js:990) 附件行为不变', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // (a) 立即发送：队列条 .queue-item-btn.immediate → sendImmediate
  const enqA = await enqueueViaSend(page, 'j8-immediate', ENQ_ATTS);
  expect(enqA.queueLen).toBe(1);
  expect(enqA.pendingAfterEnqueue).toBe(0);              // 入队即清空输入框（input.js:598）
  const qsA = await readRecallState(page);
  expect(qsA.queueBarItems).toBe(1);
  expect(qsA.queueBarThumbs).toBe(1);                    // chatQueue.js:139-143
  expect(qsA.queueBarFiles).toBe(1);                      // chatQueue.js:145-148

  CAP.frames.length = 0;
  await page.waitForSelector('.queue-item .queue-item-btn.immediate', { state: 'visible' });
  await page.click('.queue-item .queue-item-btn.immediate');
  await page.waitForTimeout(150);
  const frA = wireFrames();
  expect(frA.length, 'sendImmediate 应恰好发出一帧带 attachments 的 user-message').toBe(1);
  expect(frA[0].content).toBe('j8-immediate');
  expect(frA[0].attachments).toStrictEqual(FRAME_ATTS);

  // (b) drain 自动重发：drainMessageQueue（导出函数）
  const enqB = await enqueueViaSend(page, 'j8-drain', ENQ_ATTS);
  expect(enqB.queueLen).toBe(1);
  CAP.frames.length = 0;
  await page.evaluate(async (sid) => {
    const input = await import('/js/input.js');
    input.drainMessageQueue(sid);
  }, SID);
  await page.waitForTimeout(150);
  const frB = wireFrames();
  expect(frB.length, 'drainMessageQueue 应恰好发出一帧带 attachments 的 user-message').toBe(1);
  expect(frB[0].content).toBe('j8-drain');
  expect(frB[0].attachments).toStrictEqual(FRAME_ATTS);

  expect(pageErrors).toEqual([]);
});

// ═══════════════════════════════════════════════════════════════════════════
// J1 / J2 / J5 / J7 · 撤回回填（本批修复面）
// ═══════════════════════════════════════════════════════════════════════════
test('J1+J2+J5+J7 撤回回填：附件逐字段恢复 / 预览重绘 / 前后计数可判读 / 不静默', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const warns = collectWarns(page);
  await bootApp(page);

  // ── J1 静态：recallQueuedItem 函数体（行域）内三 token 同时命中 ──
  const src = readFileSync(join(WEB, 'js', 'input.js'), 'utf8');
  const m = src.match(/function recallQueuedItem\(sessionId, item\) \{([\s\S]*?)\n\}/);
  expect(m, 'input.js 必须存在 recallQueuedItem 函数').not.toBeNull();
  const body = m[1];
  const bodyLines = body.split('\n').length;
  for (const tok of ['item.attachments', 'pendingAttachments', 'renderAttachmentPreview']) {
    expect(body.includes(tok), `J1: recallQueuedItem 行域内缺 token ${tok}`).toBe(true);
  }
  expect(bodyLines).toBeGreaterThan(0);

  // ── 入队（真实通路）→ 队列条可见附件指示（撤回前用户看得见）──
  const enq = await enqueueViaSend(page, 'MQ-RECALL-Y', ENQ_ATTS);
  expect(enq.enqueued, 'J1 前提：入队项必须自带 attachments').toStrictEqual(ENQ_ATTS);
  const before = await readRecallState(page);
  expect(before.queueBarItems).toBe(1);
  expect(before.queueBarThumbs).toBe(1);
  expect(before.queueBarFiles).toBe(1);

  // ── 撤回（真实 UI 通路）──
  warns.length = 0;
  await clickRecall(page, enq.itemId);
  const after = await readRecallState(page);

  // J2 回填等价：逐字段深相等
  expect(after.pending, 'J2: 撤回后 pendingAttachments 必须与入队时逐字段深相等').toStrictEqual(enq.enqueued);
  // 文本回填行为逐字不变（input.js:915-918 三条分支之外 = 原样文本）
  expect(after.inputText).toBe('MQ-RECALL-Y');
  // 队列侧：该项已出队
  expect(after.queue.length).toBe(0);
  expect(after.queueBarItems).toBe(0);

  // J7 输入框预览 DOM 锚：1 缩略 + 1 文件条（chat.js:2484 .att-thumb / chat.js:2498 .att-file）
  expect(after.domThumbs, 'J7: 图片附件必须重绘为 .att-thumb').toBe(1);
  expect(after.domFiles, 'J7: 文件附件必须重绘为 .att-file').toBe(1);
  expect(after.domTotal).toBe(2);
  expect(after.domFileNames.join('|')).toContain('recall-notes.txt');

  // J5 不静默（核心）：附件非空 ⇒ 恢复后集合非空 且 有可判读 WARN（禁纯静默）
  const w = recallWarns(warns);
  expect(w.length, 'J5: 撤回带附件的项必须留下 console.warn').toBeGreaterThanOrEqual(1);
  expect(w[0]).toContain('restored 2 attachment(s)');
  expect(w[0]).toContain('(pending 0 -> 2)');      // 前后计数可判读
  expect(after.pending.length).toBeGreaterThan(0);

  // 混合集合语义：输入框已有待发附件时 = 追加（不丢任何一侧、不静默）
  const enq3 = await enqueueViaSend(page, 'MQ-RECALL-MIX', ENQ_ATTS);
  // 模拟「入队后用户又在输入框里加了东西」：撤回前把一份独立的待发附件塞进输入框
  await page.evaluate(async (sid) => {
    const cv = await import('/js/chatView.js');
    const view = cv.findViewBySessionId(sid);
    view.pendingAttachments = [{ type: 'text', mimeType: 'text/plain', data: 'cHJlLWV4aXN0aW5n', name: 'pre-existing.txt', hash: '', size: 12 }];
  }, SID);
  warns.length = 0;
  await clickRecall(page, enq3.itemId);
  const mixed = await readRecallState(page);
  expect(mixed.pending.length, '混合集合 = 追加：既有侧 + 恢复侧都不丢').toBe(3);
  expect(mixed.pending[0].name, '既有侧保持原位').toBe('pre-existing.txt');
  expect(mixed.pending.slice(1), '恢复侧逐字段相等').toStrictEqual(enq3.enqueued);
  const w2 = recallWarns(warns);
  expect(w2.some((t) => t.includes('(pending 1 -> 3)')), '混合集合必须记录前后计数').toBe(true);

  expect(pageErrors).toEqual([]);
});

// ═══════════════════════════════════════════════════════════════════════════
// J3 / J4 · 往返幂等 + 重发逐字 + 队列往返不变
// ═══════════════════════════════════════════════════════════════════════════
test('J3+J4 往返幂等：入队(带附件)→撤回→再入队 集合与顺序逐字相等；重发帧 attachments 逐字相等', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const warns = collectWarns(page);
  await bootApp(page);

  // 队列里放两个项（② 无附件），验证撤回只动目标项、顺序不变
  const enq1 = await enqueueViaSend(page, 'RT-1', ENQ_ATTS);
  const enq2 = await enqueueViaSend(page, 'RT-2', []);
  expect(enq2.queueLen).toBe(2);
  const item2Snapshot = await page.evaluate(async (sid) => {
    const state = (await import('/js/state.js')).default;
    return JSON.parse(JSON.stringify(state.messageQueue[sid].find((i) => i.text === 'RT-2')));
  }, SID);

  // 撤回第一项（带附件）
  await clickRecall(page, enq1.itemId);
  const afterRecall = await readRecallState(page);
  expect(afterRecall.pending, 'J2（同 J3 前置）：回填逐字段相等').toStrictEqual(enq1.enqueued);
  expect(afterRecall.queue.map((i) => i.text), '撤回只移除目标项，其余项顺序不变').toEqual(['RT-2']);
  expect(afterRecall.queue[0], '未命中项逐字不变').toStrictEqual(item2Snapshot);

  // J3 再入队 ⇒ 附件集合与顺序逐字相等（除 id 由 queueMessage 重新签发 = 既有行为，见报告）
  const re = await reenqueueCurrent(page, 'RT-1');
  expect(re.enqueued, 'J3: 往返后附件集合与顺序逐字相等').toStrictEqual(enq1.enqueued);
  expect(re.pendingAtEnqueue, 'J3 前提：再入队时输入框侧已恢复').toStrictEqual(enq1.enqueued);
  const queueNow = await page.evaluate(async (sid) => {
    const state = (await import('/js/state.js')).default;
    return state.messageQueue[sid].map((i) => i.text);
  }, SID);
  expect(queueNow, '队列往返：未命中项保持原位，恢复项重新排到队尾').toEqual(['RT-2', 'RT-1']);

  // J4 撤回 → 重发：帧 attachments 逐字相等（重发前先撤回第二项，得到干净输入框）
  await clickRecall(page, re.itemId);
  const back = await readRecallState(page);
  expect(back.pending).toStrictEqual(enq1.enqueued);
  CAP.frames.length = 0;
  await resendNow(page, 'RT-1');
  const fr = wireFrames();
  expect(fr.length, 'J4: 重发应恰好发出一帧带 attachments 的 user-message').toBe(1);
  expect(fr[0].attachments, 'J4: 重发帧 attachments[] 与入队时逐字相等').toStrictEqual(FRAME_ATTS);
  // 重发后输入框被清空（既有行为，不得变化）
  const post = await readRecallState(page);
  expect(post.pending.length).toBe(0);
  expect(post.domTotal).toBe(0);

  expect(pageErrors).toEqual([]);
  expect(recallWarns(warns).length).toBeGreaterThanOrEqual(1);
});

// ═══════════════════════════════════════════════════════════════════════════
// J6 · 刷新盲区不静默（persistQueue 剥离 payload 后的项）
// ═══════════════════════════════════════════════════════════════════════════
test('J6 刷新盲区不静默：persistQueue 剥离后的项撤回 ⇒ 必须可判读（console.warn）+ 元数据不丢', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const warns = collectWarns(page);
  await bootApp(page);

  const enq = await enqueueViaSend(page, 'MQ-RECALL-REFRESH', [ATT_IMG]);
  expect(enq.enqueued).toStrictEqual([ATT_IMG]);

  // 刷新等价动作：persistQueue 已在入队时执行过；restoreQueue 重新读回 = 刷新后内存态
  const restoredPremise = await page.evaluate(async (sid) => {
    window.__MQ_SID = sid;
    const state = (await import('/js/state.js')).default;
    const input = await import('/js/input.js');
    input.persistQueue();
    const raw = localStorage.getItem(Object.keys(localStorage).find((k) => k.includes('message_queue')));
    input.restoreQueue();
    const it = state.messageQueue[sid][0];
    return { raw, item: JSON.parse(JSON.stringify(it)) };
  }, SID);

  // 前提断言：data/preview 确已被剥离（若此断言红 = 夹具前提失效，非修复面问题）
  expect(restoredPremise.raw, '前提：persistQueue 必须已写入 localStorage 队列快照').toBeTruthy();
  expect(restoredPremise.item.attachments.length).toBe(1);
  expect(restoredPremise.item.attachments[0].preview, '前提：preview 已被 persistQueue 剥离').toBeUndefined();
  expect(restoredPremise.item.attachments[0].data, '前提：data 已被 persistQueue 剥离').toBeUndefined();
  expect(restoredPremise.item.attachments[0].name).toBe('recall-a.png');

  const qid = restoredPremise.item.id;
  warns.length = 0;
  await clickRecall(page, qid);
  const after = await readRecallState(page);
  const w = recallWarns(warns);

  // J6：必须给可判读提示（本批允许 = console.warn；🔴 未新增任何 locale key）
  expect(w.some((t) => t.includes('lost their payload to page-refresh persistence')),
    'J6: 剥离项撤回必须有可判读告警').toBe(true);
  expect(w.some((t) => t.includes('restored 1 attachment(s)')), 'J6: 同时必须有恢复计数告警').toBe(true);
  // 且不得静默：元数据（name）仍回落输入框 ⇒ 用户看得见「有个同名附件待补」
  expect(after.pending.length).toBe(1);
  expect(after.pending[0].name).toBe('recall-a.png');
  expect(after.domFiles, 'J6: 无 preview 的项回落 .att-file（chat.js:2496 else 分支）').toBe(1);
  expect(after.domFileNames.join('|')).toContain('recall-a.png');

  expect(pageErrors).toEqual([]);
});

// ═══════════════════════════════════════════════════════════════════════════
// J7 · 队列条附件指示锚（3 缩略 + 「+N」；chatQueue.js:136/140/147/153）——两侧均绿
// ═══════════════════════════════════════════════════════════════════════════
test('J7 锚：队列条附件指示 ≤3 缩略 + 「+N」（既有面，两侧均绿）+ 撤回后 4 件全绘（修复面）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  const four = [0, 1, 2, 3].map((i) => ({
    type: 'image', mimeType: 'image/png', data: B64_PNG, name: `q${i}.png`, preview: PREVIEW_PNG,
  }));
  const enq = await enqueueViaSend(page, 'J7-INDICATOR', four);
  const st = await readRecallState(page);
  expect(st.queueBarItems).toBe(1);
  expect(st.queueBarThumbs, 'chatQueue.js:137-143：≤3 个缩略图').toBe(3);
  expect(st.queueBarMore, 'chatQueue.js:151-156：超出部分标 +N').toBe(1);
  expect(st.queueBarMoreText).toBe('+1');
  expect(enq.itemId).toBeGreaterThan(0);
  // ↑ 以上为「队列条附件指示」既有面（chatQueue.js，本批禁改）：验红侧亦须全绿。
  // ↓ 以下为撤回回填的修复面：验红侧必红。
  await clickRecall(page, enq.itemId);
  const after = await readRecallState(page);
  expect(after.domThumbs).toBe(4);
  expect(after.pending.length).toBe(4);

  expect(pageErrors).toEqual([]);
});
