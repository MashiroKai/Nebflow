#!/usr/bin/env node
// verify-friendattach-render.mjs — 4b 腿 A-2「附件呈现面」的**真渲染**验收
// （亮/暗双主题 × 三视口）。
//
// ── 夹具声明（🔴 如实声明，不冒充生产实测）──────────────────────────────
// · 前端文件：`page.route` 从磁盘**服务真实源码树**（默认 `src/main/resources/web/`，
//   可用 `--web <dir>` 指向基线只读副本 ⇒ 同一脚本可跑「前红」），不起任何端口、
//   不碰 :8080。
// · 数据面：`/api/*` 一律由本脚本按 **fixture** 应答（附件消息 4 条 + 下载字节）。
//   夹具 = 桩载荷，**不是**生产实例读数。
// · 客户端代码路径：**全部是生产代码**（messages.js 的渲染 + friendsApi 的取字节 +
//   friends.css 的样式），无任何测试替身。
//
// ── 断言（每条对应线面契约的一节；前红后绿）────────────────────────────
//   A2-1 `ready` 附件 = 卡片 + 下载按钮 + 真名/大小（§B.7 ①）
//   A2-2 `expired` = 灰态卡片 + 真名/大小 + 「附件已过期」+ **无**按钮（§B.7 ②1）
//   A2-3 `uploading` / 越界 state = 可判读降级文案 + 无按钮（禁静默丢弃/禁假按钮）
//   A2-4 §B.4 占位正文只在**逐字相等**时隐藏（非占位正文照常显示）
//   A2-5 下载走**应用内鉴权路由**（URL = /api/friends/attachments/<id> 且带 Authorization）
//   A2-6 下载成功 ⇒ 「已下载」；元数据 ready 但服务端 410 ⇒ **就地升级**「附件已过期」
//        （终态，不同于「下载失败，点击重试」，§B.7 ③）
//   A2-7 全程零 console error / 零 pageerror
//   A2-8 取字节 500 ⇒ state=failed + 「下载失败，点击重试」+ 按钮仍可用（**不**折叠成 expired）
//
// 用法（worktree 根）：node scripts/verify-friendattach-render.mjs [--web <dir>] [--out <dir>]
// 退出码：0 = 全绿；1 = 任一断言红（逐条打印失败原因）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, extname, normalize, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const argOf = (flag, dflt) => {
  const i = argv.indexOf(flag);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const WEB = normalize(argOf('--web', join(HERE, '..', 'src', 'main', 'resources', 'web')));
const OUT = normalize(argOf('--out', join(HERE, '..', '.nebflow', 'evidence', '20260914_friendattach-gate', 'render')));

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const now = Date.now();
const CONV = { conversationId: 'c1', friend: { userId: 'u-peer', username: 'peer', display_name: '林小满' }, unreadCount: 0, lastMessage: null };

// 夹具消息：覆盖 ready / expired / uploading / 越界 state / 空正文 / 410 升级腿。
const MESSAGES = [
  { id: 1, senderId: 'u-peer', kind: 'text', body: '纯文本消息', createdAt: now - 6000 },
  {
    id: 2, senderId: 'u-peer', kind: 'text', body: '[附件] report.pdf', createdAt: now - 5000,
    attachments: [{ id: 'att-ready', name: 'report.pdf', size: 10485760, mime: 'application/pdf', sha256: 'aa'.repeat(32), state: 'ready' }],
  },
  {
    id: 3, senderId: 'u-peer', kind: 'text', body: '[附件] photo.png', createdAt: now - 4000,
    attachments: [{ id: 'att-gone', name: 'photo.png', size: 20480, sha256: 'bb'.repeat(32), state: 'expired' }],
  },
  {
    id: 4, senderId: 'u-peer', kind: 'text', body: '这两件是我手写的正文（不是占位正文）', createdAt: now - 3000,
    attachments: [
      { id: 'att-upload', name: 'notes.txt', size: 12, sha256: 'cc'.repeat(32), state: 'uploading' },
      { id: 'att-weird', name: 'mystery.bin', size: 3, sha256: 'dd'.repeat(32), state: 'pending' },
    ],
  },
  {
    id: 5, senderId: 'u-peer', kind: 'text', body: '', createdAt: now - 2000,
    attachments: [{ id: 'att-410', name: 'vanished.zip', size: 999, sha256: 'ee'.repeat(32), state: 'ready' }],
  },
];
CONV.lastMessage = MESSAGES[4];

const PDF_BYTES = Buffer.from('%PDF-1.4 fixture bytes for the render harness\n');
const ZIP_GONE = JSON.stringify({ error: 'attachment_expired' });

/** 应用启动期的既有轮询端点（与本批附件面无关；harness 刻意不实现它们）。
  * 显式登记 ⇒ A2-7 只容忍这些 404，任何**新的** 4xx/5xx 仍然判红。 */
const BOOT_POLL_404 = ['/api/canvas-tabs', '/api/nf-authcheck', '/api/plugins', '/api/agents', '/api/projects'];

const failures = [];
const notes = [];
const seen = { downloadRequests: [] };
// A2-8：置真 ⇒ 下一次 att-ready 取字节回答 500（同页内制造可重试失败腿）。
let failDownload = false;

function check(ok, label, detail) {
  if (ok) notes.push(`PASS ${label}`);
  else failures.push(`${label}${detail ? ` — ${detail}` : ''}`);
}

async function run(browser, colorScheme, viewport, label) {
  const context = await browser.newContext({ colorScheme, viewport, acceptDownloads: true });
  const page = await context.newPage();
  const consoleErrors = [];
  const notFound = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(String(e)));
  page.on('response', (r) => { if (r.status() >= 400) notFound.push(`${r.status()} ${new URL(r.url()).pathname}`); });

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'render-harness-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
      send() {} close() {} onopen = null; onmessage = null; onclose = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
  });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/neblink/status') {
        return route.fulfill({ json: { loggedIn: true, device: { id: 'd1', name: 'RenderMac', platform: 'macos', capabilities: {} } } });
      }
      if (p === '/api/conversations') return route.fulfill({ json: [CONV] });
      if (p === '/api/friends') return route.fulfill({ json: { friends: [CONV.friend], incoming: [], outgoing: [] } });
      if (p === '/api/conversations/c1/messages') return route.fulfill({ json: MESSAGES });
      if (p === '/api/conversations/c1/read') return route.fulfill({ json: { ok: true } });
      if (p === '/api/friends/attachments/att-ready') {
        seen.downloadRequests.push({ path: p, auth: route.request().headers()['authorization'] || '' });
        if (failDownload) return route.fulfill({ status: 500, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: 'internal' }) });
        return route.fulfill({
          status: 200,
          headers: {
            'Content-Type': 'application/octet-stream',
            'Content-Disposition': "attachment; filename*=UTF-8''report.pdf",
            'X-Attachment-Sha256': 'aa'.repeat(32),
          },
          body: PDF_BYTES,
        });
      }
      if (p === '/api/friends/attachments/att-410') {
        seen.downloadRequests.push({ path: p, auth: route.request().headers()['authorization'] || '' });
        return route.fulfill({ status: 410, contentType: 'application/json', body: ZIP_GONE });
      }
      return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 20000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
  await df({ type: 'sessionList', sessionId: 'render-root', activeId: 'render-root', sessions: [{ id: 'render-root', name: 'Nebula', agentName: 'Nebula' }], folders: [] });
  await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
  await df({ type: 'historyPage', sessionId: 'render-root', messages: [], hasMore: false, offset: 0 });
  await page.waitForTimeout(400);

  // 打开消息面板（生产入口：活动栏按钮）→ 点会话行 → 聊天窗挂载
  await page.click('#activity-bar [data-panel-btn="messages"]');
  await page.waitForSelector('#panel-messages .fm-row', { timeout: 15000 });
  await page.click('#panel-messages .fm-row');
  await page.waitForSelector('.fm-msg[data-message-id="5"]', { timeout: 15000 });
  await page.waitForTimeout(250);

  const snap = await page.evaluate(() => {
    const q = (sel, root = document) => root.querySelector(sel);
    const card = (msgId, attId) => q(`.fm-msg[data-message-id="${msgId}"] .fm-att[data-att-id="${attId}"]`);
    const cardInfo = (msgId, attId) => {
      const c = card(msgId, attId);
      if (!c) return null;
      return {
        state: c.dataset.attState,
        text: c.textContent,
        hasButton: !!q('.fm-att-dl', c),
        name: q('.fm-att-name', c)?.textContent || '',
        size: q('.fm-att-size', c)?.textContent || '',
      };
    };
    return {
      msg1Cards: document.querySelectorAll('.fm-msg[data-message-id="1"] .fm-att').length,
      msg1Text: q('.fm-msg[data-message-id="1"] .fm-msg-bubble')?.textContent || '',
      msg2: cardInfo(2, 'att-ready'),
      // 正文取 `.fm-msg-text`（气泡正文节点）：占位正文被隐藏时该节点**不存在**
      msg2Text: q('.fm-msg[data-message-id="2"] .fm-msg-text')?.textContent ?? null,
      msg2Cards: document.querySelectorAll('.fm-msg[data-message-id="2"] .fm-att').length,
      msg3: cardInfo(3, 'att-gone'),
      msg3Text: q('.fm-msg[data-message-id="3"] .fm-msg-text')?.textContent ?? null,
      msg4: [cardInfo(4, 'att-upload'), cardInfo(4, 'att-weird')],
      msg4Text: q('.fm-msg[data-message-id="4"] .fm-msg-text')?.textContent ?? null,
      msg5: cardInfo(5, 'att-410'),
      attCount: document.querySelectorAll('.fm-att').length,
    };
  });

  check(snap.msg1Cards === 0 && snap.msg1Text.includes('纯文本消息'), `${label} A2-0 纯文本消息零附件卡片`, JSON.stringify(snap.msg1Text));
  // A2-1
  check(!!snap.msg2 && snap.msg2.state === 'ready', `${label} A2-1 ready 卡片在位`, JSON.stringify(snap.msg2));
  check(!!snap.msg2?.hasButton, `${label} A2-1 ready 有下载按钮`);
  check(snap.msg2?.name === 'report.pdf' && snap.msg2?.size === '10 MB', `${label} A2-1 ready 真名/大小`, JSON.stringify([snap.msg2?.name, snap.msg2?.size]));
  // A2-4 占位正文隐藏（逐字相等 ⇒ 不渲染正文节点）
  check(snap.msg2Text === null && snap.msg2Cards === 1, `${label} A2-4 §B.4 占位正文隐藏（正文节点不存在）`, JSON.stringify(snap.msg2Text));
  // A2-2 expired
  check(!!snap.msg3 && snap.msg3.state === 'expired', `${label} A2-2 expired 卡片在位`, JSON.stringify(snap.msg3));
  check(!!snap.msg3 && !snap.msg3.hasButton, `${label} A2-2 expired 无下载按钮（禁假按钮）`);
  check(!!snap.msg3 && snap.msg3.name === 'photo.png' && snap.msg3.size === '20 KB', `${label} A2-2 expired 保留真名/大小`, JSON.stringify([snap.msg3?.name, snap.msg3?.size]));
  check(!!snap.msg3 && snap.msg3.text.includes('附件已过期'), `${label} A2-2 expired 文案可判读`, JSON.stringify(snap.msg3?.text));
  check(snap.msg3Text === null, `${label} A2-4 expired 占位正文同样隐藏`, JSON.stringify(snap.msg3Text));
  // A2-3 uploading / 越界
  check(!!snap.msg4[0] && snap.msg4[0].state === 'uploading' && !snap.msg4[0].hasButton, `${label} A2-3 uploading 卡不可下载`, JSON.stringify(snap.msg4[0]));
  check(!!snap.msg4[1] && snap.msg4[1].state === 'unreadable' && !snap.msg4[1].hasButton, `${label} A2-3 越界 state 降级可判读`, JSON.stringify(snap.msg4[1]));
  check((snap.msg4Text || '').includes('不是占位正文'), `${label} A2-4 非占位正文照常显示`, JSON.stringify(snap.msg4Text));
  check((snap.msg5?.body || true) && snap.msg5?.state === 'ready', `${label} A2-5 410 腿起始态 = ready`, JSON.stringify(snap.msg5));

  // A2-5/A2-6 下载腿（真点击）。基线（无附件面）没有按钮 ⇒ 不抛异常，判红并继续。
  seen.downloadRequests.length = 0;
  const clickAndWait = async (sel, note) => {
    try {
      await page.click(sel, { timeout: 8000 });
      await page.waitForTimeout(500);
      return true;
    } catch (e) {
      failures.push(`${label} ${note} — ${String(e).split('\n')[0]}`);
      return false;
    }
  };
  const clickedOk = await clickAndWait('.fm-msg[data-message-id="2"] .fm-att-dl', 'A2-5 下载按钮不可点（附件面缺失？）');
  const afterOk = await page.evaluate(() => {
    const c = document.querySelector('.fm-msg[data-message-id="2"] .fm-att');
    return { text: c?.textContent || '', state: c?.dataset.attState };
  });
  const dl = seen.downloadRequests.find((r) => r.path === '/api/friends/attachments/att-ready');
  check(!!dl, `${label} A2-5 下载走 /api/friends/attachments/<id>（应用内鉴权路由）`, JSON.stringify(seen.downloadRequests));
  check(!!dl && dl.auth.startsWith('Bearer '), `${label} A2-5 下载请求带 Authorization`, dl ? dl.auth : '<none>');
  if (clickedOk) check(/已下载/.test(afterOk.text), `${label} A2-6 下载成功后卡片可判读`, JSON.stringify(afterOk.text));

  const clicked410 = await clickAndWait('.fm-msg[data-message-id="5"] .fm-att-dl', 'A2-6 410 腿按钮不可点');
  const after410 = await page.evaluate(() => {
    const c = document.querySelector('.fm-msg[data-message-id="5"] .fm-att');
    return { text: c?.textContent || '', state: c?.dataset.attState, hasButton: !!c?.querySelector('.fm-att-dl') };
  });
  if (clicked410) {
    check(after410.state === 'expired' && after410.text.includes('附件已过期'), `${label} A2-6 服务端 410 ⇒ 就地升级「附件已过期」（终态）`, JSON.stringify(after410));
    check(!after410.hasButton, `${label} A2-6 升级后不再提供重试（与「下载失败」区分）`);
  }

  // A2-8 失败态（可重试）：同一件重下、本次服务端 500 ⇒ 「下载失败，点击重试」+ 按钮仍在。
  // （与 410 的终态**必须可区分**：只有这条腿证明两态没有被折叠成一个。）
  failDownload = true;
  const clickedFail = await clickAndWait('.fm-msg[data-message-id="2"] .fm-att-dl', 'A2-8 重试按钮不可点');
  const afterFail = await page.evaluate(() => {
    const c = document.querySelector('.fm-msg[data-message-id="2"] .fm-att');
    return { text: c?.textContent || '', state: c?.dataset.attState, hasButton: !!c?.querySelector('.fm-att-dl'), btnDisabled: !!c?.querySelector('.fm-att-dl')?.disabled };
  });
  failDownload = false;
  if (clickedFail) {
    check(afterFail.state === 'failed', `${label} A2-8 传输失败 ⇒ state=failed（不折叠成 expired）`, JSON.stringify(afterFail));
    check(afterFail.text.includes('下载失败，点击重试'), `${label} A2-8 失败文案可判读`, JSON.stringify(afterFail.text));
    check(afterFail.hasButton && !afterFail.btnDisabled, `${label} A2-8 失败后可重试（按钮仍在且可用）`, JSON.stringify(afterFail));
  }

  // A2-7 错误面：本批面**零错误**；非本批的 404 只允许出现在已登记的应用启动轮询清单里
  //      （harness 不实现这些端点 ⇒ 404 ⇒ 浏览器打一条 console error，与本批无关）。
  const unexpected = consoleErrors.filter((e) => !e.includes('Failed to load resource'));
  check(unexpected.length === 0, `${label} A2-7 零 pageerror / 非资源错误`, JSON.stringify(unexpected.slice(0, 3)));
  check(!consoleErrors.some((e) => e.includes('attachments')), `${label} A2-7 附件面零错误`, JSON.stringify(consoleErrors.slice(0, 3)));
  seen.notFound = (seen.notFound || []).concat(notFound);
  const strayStatus = notFound.filter(
    // 本批**刻意制造**的两条非 2xx 腿：410（服务端权威过期）与 500（A2-8 可重试失败）。
    (s) => !BOOT_POLL_404.some((p) => s.endsWith(p)) &&
      !s.endsWith('/api/friends/attachments/att-410') && !s.endsWith('/api/friends/attachments/att-ready')
  );
  check(strayStatus.length === 0, `${label} A2-7 非登记 404 为零`, JSON.stringify(strayStatus));

  mkdirSync(OUT, { recursive: true });
  const shot = join(OUT, `friendattach-${label}.png`);
  await page.locator('.fm-modal').screenshot({ path: shot });
  notes.push(`SHOT ${shot}`);

  await context.close();
}

(async () => {
  const browser = await chromium.launch();
  const VIEWPORTS = [
    { name: 'wide-1440', width: 1440, height: 900 },
    { name: 'mid-1024', width: 1024, height: 768 },
    { name: 'narrow-390', width: 390, height: 844 },
  ];
  try {
    for (const scheme of ['dark', 'light']) {
      for (const vp of VIEWPORTS) {
        await run(browser, scheme, { width: vp.width, height: vp.height }, `${scheme}-${vp.name}`);
      }
    }
  } finally {
    await browser.close();
  }
  const summary = { web: WEB, notes, failures, downloads: seen.downloadRequests, notFound: [...new Set(seen.notFound || [])] };
  mkdirSync(OUT, { recursive: true });
  writeFileSync(join(OUT, 'assertions.json'), JSON.stringify(summary, null, 2));
  console.log(`web tree = ${WEB}`);
  for (const n of notes) console.log(`  ${n}`);
  console.log(`checks: ${notes.filter((n) => n.startsWith('PASS')).length} pass / ${failures.length} fail`);
  for (const f of failures) console.log(`  FAIL ${f}`);
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
