// mvp2-device-client.spec.mjs — MVP-2 设备会话切服务端数据源 · 客户端腿验收
// （契约真源 = neblink-server main@4fceff4 §8.1/§8.3/§8.5–§8.8 + 设计卡 §6.3 D1–D10）
//
// 🔴 本 spec 是**同一支探针**跑两棵树（判红⑤「改前不可满足钉」）：
//    · MVP2_WEB_ROOT 未设 ⇒ 跑本仓 `src/main/resources/web`（改后）⇒ 必须全 PASS；
//    · MVP2_WEB_ROOT=<改前树>（`git archive` 出的基线 web/）⇒ 同一支探针必须**红**，
//      且红的**断言名**逐条可读 —— 那就是本改动「改前不可满足」的原文证据。
//
// 🔴 **夹具硬要求（round-2 返工要件，2026-09-15）**：桩必须与**服务端真值面同形**，
//    否则缺陷会被 201 桩掩盖（round-1 的 F1/F2 正是这样被掩盖的）：
//      ① 一个**发送设备**一行会话（`dev:<senderDeviceId>`），`sender_device_id`
//         由会话 id 派生 ⇒ **一条会话内方向恒定**，绝不把两个发送设备混进同一行；
//      ② 本机自己那一行**在场**（`list_device_conversations` 返回账号全部设备行，
//         含本机行；`devicesession_test.rs:377-446` 2 行的真值形状）；
//      ③ `POST /api/devices/{id}/messages` 的路径设备 = **发送设备**，桩**按服务端
//         硬闸判**（`credential_device == device_id` 否则 **403 not_my_device**）——
//         打对端 id 必红，而不是静默 201。
//
// 覆盖：
//   A1~A7 适配层逐字段（判红①）：`{id, body, createdAtMs, origin, ours, attachments}`
//          六字段 + `msgId→id` + 方向重算 P2
//   U1    服务端行进列表（kind/deviceId/unreadCount 角标）
//   O1**  §9.3 按 peer 归并（round-2 F2）：本机行**不单独成窗**、两行**读成一窗**
//   O2**  归并窗方向逐条重算：out/in 计数 + 每条气泡左右
//   O3**  回复打**本机** device id（round-2 F1）+ 桩侧硬闸（打对端 ⇒ 403）
//   R1    设备窗 P10 留存明示句 —— 🔴 **已反极性**（作者 2026-09-15 令 ⑤b：不要显示
//         该信息）⇒ 现断言「零条 + 双语键已删」；原「在场」形态的改前读数见
//         tests/unifyfix-eight-points.spec.mjs 的 R5b 红锚。
//   R2    未读/回执读写往返（原始请求/响应读数，判红②的客户端半程）
//   R3**  已读**按服务端行逐行**上报（对端行带自己的末条 id；本机行无上报面）
//   R4    附件第三分支（服务端 attachments → attachmentCard + 成员闸下载路由）
//   L1    旧数据无新字段不崩 + 降级展示（判红④）
//   L2    旧网关降级档 —— 🔴 **含 ⑥ 反极性断言**（作者 2026-09-15：无通信证据 ⇒
//         不得在消息面板出现；灌入本地缓存证据后才成窗）
//   N1**  本机 device id 缺席 ⇒ **可见禁用**（round-2 F1 要件②）+ 零发送请求
//
// 自包含：静态服务器随机隔离端口 + route 拦截，finally 必关（进程清理纪律）。
// Run: node tests/mvp2-device-client.spec.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = process.env.MVP2_WEB_ROOT
  || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MODE = process.env.MVP2_WEB_ROOT ? 'BEFORE-TREE' : 'AFTER-TREE';

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── wire 形态（契约原形，camelCase = 网关出参）────────────────────────
// 🔴 服务端真值夹具（§8.1 + §9.3）：设备会话以**发送设备**为键 ⇒
//    `dev:dev-alpha` = 本机所发（本机自己那一行，unread 恒 0）、
//    `dev:dev-beta`  = 对端所发（承载未读）。一条会话内 `senderDeviceId` **恒定**。
const SELF = 'dev-alpha';
const PEER = 'dev-beta';
const FRIEND_ME = { userId: 'me', username: 'me', display_name: 'Me', avatar: null };

const SERVER_CONVOS = [
  {
    conversationId: 'dev:' + PEER, kind: 'device', deviceId: PEER,
    unreadCount: 2, friend: FRIEND_ME,
    lastMessage: { id: 14, senderId: 'me', kind: 'text', body: 'peer follow-up', createdAt: 1757900020, senderDeviceId: PEER },
  },
  {
    conversationId: 'dev:' + SELF, kind: 'device', deviceId: SELF,
    unreadCount: 0, friend: FRIEND_ME, // §8.1「发送设备自己 0」
    lastMessage: { id: 13, senderId: 'me', kind: 'text', body: 'old row no new fields', createdAt: 1757900010, senderDeviceId: SELF },
  },
  {
    conversationId: 'c-legacy', friend: { userId: 'u1', username: 'lin', display_name: 'Lin', avatar: null },
    lastMessage: null, unreadCount: 0,
  },
];

// 每条会话的消息行（**会话内 senderDeviceId 恒定**；id 全局递增 ⇒ 归并顺序可判）
const MSGS_BY_CONV = {
  ['dev:' + SELF]: [
    { id: 11, senderId: 'me', kind: 'text', body: 'from my mac', createdAt: 1757899990, senderDeviceId: SELF },
    // 旧格式行（判红④）：无 origin / 无 createdAtMs / 无 attachments（字段加性）
    { id: 13, senderId: 'me', kind: 'text', body: 'old row no new fields', createdAt: 1757900010, senderDeviceId: SELF },
  ],
  ['dev:' + PEER]: [
    {
      id: 12, senderId: 'me', kind: 'text', body: '[附件] photo.png', createdAt: 1757900000, senderDeviceId: PEER,
      attachments: [{ id: 'att-1', name: 'photo.png', size: 31, sha256: 'd3e62224', state: 'ready' }],
    },
    { id: 14, senderId: 'me', kind: 'text', body: 'peer follow-up', createdAt: 1757900020, senderDeviceId: PEER },
  ],
};

const captured = {
  read: [], receiptsConvs: [], deviceSendPaths: [], deviceSendStatus: [], attDownload: 0,
  msgsConvs: [],
};

/** 桩路由（服务端真值形状）。`st` 允许每个页面自带一份捕获表（noSelf 页复用）。 */
function installRoutes(page, cap, opts = {}) {
  const statusDevice = opts.selfDeviceId === undefined
    ? { id: SELF, name: 'Mac', platform: 'macos', userDescription: '', avatarUrl: '' }
    : (opts.selfDeviceId === null ? {} : { id: opts.selfDeviceId, name: 'Mac', platform: 'macos' });
  return page.route('**/api/**', async r => {
    const url = new URL(r.request().url());
    const p = decodeURIComponent(url.pathname);
    const m = r.request().method();
    const body = r.request().postData() || '';
    if (p === '/api/neblink/status') {
      return r.fulfill({
        json: {
          loggedIn: true, device: statusDevice,
          peers: [{ deviceId: PEER, deviceName: 'Phone', platform: 'ios', online: true, directOnline: true }],
        },
      });
    }
    if (p === '/api/conversations' && m === 'GET') {
      // opts.noServerDeviceRows：旧网关（无设备会话面）⇒ 只剩 legacy 直聊行（判红④降级档）
      return r.fulfill({ json: opts.noServerDeviceRows ? SERVER_CONVOS.filter(c => c.kind !== 'device') : SERVER_CONVOS });
    }
    if (p.endsWith('/messages') && m === 'GET') {
      const convId = p.slice('/api/conversations/'.length, -'/messages'.length);
      cap.msgsConvs.push(convId);
      return r.fulfill({ json: MSGS_BY_CONV[convId] || [] });
    }
    if (p.endsWith('/read') && m === 'POST') {
      const convId = p.slice('/api/conversations/'.length, -'/read'.length);
      cap.read.push({ convId, body });
      return r.fulfill({ json: { ok: true } });
    }
    if (p.endsWith('/receipts') && m === 'GET') {
      const convId = p.slice('/api/conversations/'.length, -'/receipts'.length);
      cap.receiptsConvs.push(convId);
      // 契约 §8.7：回执行只存在于**发送设备 = 请求设备**的那一行（本机自己的行）
      if (convId !== 'dev:' + SELF) return r.fulfill({ json: { receipts: [], lastSentMessageId: 0, lastReadMessageId: 0 } });
      return r.fulfill({ json: { receipts: [{ messageId: 11, state: 'read' }], lastSentMessageId: 11, lastReadMessageId: 11 } });
    }
    if (p.startsWith('/api/devices/') && p.endsWith('/messages') && m === 'POST') {
      const deviceId = p.slice('/api/devices/'.length, -'/messages'.length);
      cap.deviceSendPaths.push(deviceId);
      // 🔴 服务端硬闸（friends.rs:1305-1320）：路径设备必须是**发送设备**（= 本机）
      if (opts.selfDeviceId === undefined && deviceId !== SELF) {
        cap.deviceSendStatus.push(403);
        return r.fulfill({ status: 403, json: { error: 'not_my_device' } });
      }
      cap.deviceSendStatus.push(201);
      const payload = JSON.parse(body || '{}');
      const replay = payload.clientMsgId === 'dup-1';
      if (!replay && !MSGS_BY_CONV['dev:' + SELF].some(x => x.id === 99)) {
        MSGS_BY_CONV['dev:' + SELF].push({
          id: 99, senderId: 'me', kind: 'text', body: payload.body || '', createdAt: 1757900100, senderDeviceId: SELF,
        });
      }
      return r.fulfill({
        status: 201,
        json: {
          messageId: replay ? 11 : 99, conversationId: 'dev:' + SELF,
          createdAt: 1757900100, createdAtMs: 1757900100123, existing: replay, selfUserId: 'me',
        },
      });
    }
    if (p === '/api/friends/attachments/att-1') { cap.attDownload++; return r.fulfill({ body: 'hello-bytes' }); }
    if (p === '/api/friends') return r.fulfill({ json: { friends: [], incoming: [], outgoing: [] } });
    if (p === '/api/groups') return r.fulfill({ json: [] });
    return r.fulfill({ json: {} });
  });
}

async function installWs(page) {
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });
}

const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
let ctx = null;
try {
  ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await installRoutes(page, captured);
  await installWs(page);

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(1200);
  // 本机设备 id 必须先落 state（方向重算 P2 的比对基准），探针前显式取一次。
  await page.evaluate(async () => { const n = await import('/js/neblink.js'); await n.fetchNeblinkStatus(); });
  await sleep(300);

  // ══ 判红①：适配层逐字段（纯函数直测，不经过 DOM）═══════════════════
  const adapter = await page.evaluate(async () => {
    const mod = await import('/js/messages.js');
    const adapt = mod.adaptDeviceMessage;
    if (typeof adapt !== 'function') return { error: 'adaptDeviceMessage is not exported: ' + typeof adapt };
    const run = () => [
      adapt({ id: 11, senderId: 'me', kind: 'text', body: 'from my mac', createdAt: 1757899990, senderDeviceId: 'dev-alpha' }),
      adapt({ id: 12, senderId: 'me', kind: 'text', body: '[附件] photo.png', createdAt: 1757900000, senderDeviceId: 'dev-beta', attachments: [{ id: 'att-1', name: 'photo.png', size: 31, sha256: 'x', state: 'ready' }] }),
      adapt({ id: 14, senderId: 'me', kind: 'text', body: 'from unknown', createdAt: 1757900020, senderDeviceId: 'dev-ghost' }),
      adapt({ id: 13, senderId: 'me', kind: 'text', body: 'old row no new fields', createdAt: 1757900010 }),
      adapt({ msgId: 'uuid-a', text: 'legacy text', ts: 1757900030000, direction: 'out', origin: 'user' }),
      adapt({ msgId: 'uuid-b', kind: 'file', text: '', ts: 1757900040000, direction: 'in', fileName: 'a.bin', fileSize: 7, status: 'completed' }),
      adapt({ body: 'no id' }),
      adapt(null),
    ];
    return { rows: run() };
  });
  if (adapter.error) {
    ok('A0 适配层可探（export 在场）', false, adapter.error);
  } else {
    const R = adapter.rows;
    ok('A0 适配层可探（export 在场）', true);
    ok('A1 本机行六字段', (() => { const r = R[0];
      return r.id === '11' && r.body === 'from my mac' && r.createdAtMs === 1757899990000
        && r.origin === 'user' && r.ours === true && Array.isArray(r.attachments) && r.attachments.length === 0; })(),
      JSON.stringify(R[0]));
    ok('A2 他机行方向重算 ours=false', R[1].ours === false, `ours=${R[1].ours}`);
    ok('A2b 他机行附件 → 附件卡(state 透传)', R[1].attachments.length === 1 && R[1].attachments[0].id === 'att-1' && R[1].attachments[0].state === 'ready', JSON.stringify(R[1].attachments));
    ok('A2c 他机行 createdAtMs 毫秒', R[1].createdAtMs === 1757900000000, `got ${R[1].createdAtMs}`);
    ok('A3 未知 senderDeviceId ⇒ ours=false（不是本机）', R[2].ours === false, `ours=${R[2].ours}`);
    ok('A4 旧格式行降级不崩', R[3].id === '13' && R[3].origin === 'user' && R[3].createdAtMs === 1757900010000 && R[3].attachments.length === 0, JSON.stringify(R[3]));
    ok('A5 legacy msgId→id + direction out ⇒ ours=true', R[4].id === 'uuid-a' && R[4].body === 'legacy text' && R[4].createdAtMs === 1757900030000 && R[4].ours === true, JSON.stringify(R[4]));
    ok('A6 legacy 文件 → 设备传输卡', R[5].ours === false && R[5].attachments.length === 1 && R[5].attachments[0].state === 'device' && R[5].attachments[0].name === 'a.bin', JSON.stringify(R[5]));
    ok('A7 缺 id 行不抛、落空 id', R[6] && R[6].id === '', JSON.stringify(R[6]));
    ok('A8 非对象输入不抛（null）', R[7] && R[7].id === '' && R[7].ours === false, JSON.stringify(R[7]));
  }

  // ══ 列表面：设备窗行（§9.3 归并 ⇒ 一个 peer 恰一窗）════════════════
  await page.click('#messages-btn');
  await page.waitForSelector('#fm-conversations .fm-conv-row', { timeout: 15000 });
  await sleep(700);
  const deviceRows = page.locator('#fm-conversations .fm-conv-row[data-device="1"]');
  const deviceRowIds = await deviceRows.evaluateAll(els => els.map(e => e.dataset.conversationId));
  // 🔴 O1：本机自己那一行**不单独成窗**（真值夹具里它在场 ⇒ 必须被归并掉）
  ok('O1 本机自己那行不成窗（设备窗恰 1 条 = 对端）', deviceRowIds.length === 1,
    `windows=${JSON.stringify(deviceRowIds)}`);
  ok('O1b 无 dev:dev-alpha 窗', !deviceRowIds.includes('dev:' + SELF), `windows=${JSON.stringify(deviceRowIds)}`);
  const deviceRow = page.locator('#fm-conversations .fm-conv-row[data-conversation-id="dev:' + PEER + '"]').first();
  ok('U1 服务端设备行进列表（data-device=1）', await deviceRow.count() === 1);
  ok('U1b 服务端未读角标 = 2（本机行 0 + 对端行 2）', (await deviceRow.locator('.fm-row-badge').textContent().catch(() => '')) === '2',
    `badge=${await deviceRow.locator('.fm-row-badge').textContent().catch(() => '(none)')}`);

  // R1（原「P10 留存明示句在场」）**已反极性**：作者 2026-09-15 令 ⑤b 明确要求
  // **不要显示**「Cloud keeps messages for 7 days; this device keeps them
  // permanently. 这样的信息」⇒ 原 P10 要求被取代，本断言改为「该句零残留」。
  // （红锚复现 = 同一声明在改前树仍为在场 ⇒ 见 unifyfix-eight-points.spec.mjs R5b。）
  const ZH = '云端保留 7 天，本机永久保存。';
  await deviceRow.click();
  await page.waitForSelector('.fm-modal', { timeout: 15000 });
  await sleep(900);
  const zhNoteCount = await page.locator('.fm-modal .fm-device-note').count();
  ok('R1 ⑤b 替代断言：设备窗零留存明示条（.fm-device-note 0 条）', zhNoteCount === 0, `count=${zhNoteCount}`);
  const zhLeak = await page.evaluate(z => document.body.innerText.includes(z) || document.body.innerText.includes('Cloud keeps messages'),
    ZH);
  ok('R1b ⑤b 替代断言：留存句在全文零残留（zh 面 + en 子串）', zhLeak === false, `leak=${zhLeak}`);

  // ══ 判红⑤ + F2：两行归并成一窗，方向逐条重算 ═══════════════════════
  const outCount = await page.locator('.fm-modal .fm-msg.out').count();
  const inCount = await page.locator('.fm-modal .fm-msg.in').count();
  ok('O2 归并窗 out/in 计数正确（11,13 → out=2；12,14 → in=2）', outCount === 2 && inCount === 2, `out=${outCount} in=${inCount}`);
  const cls11 = await page.locator('.fm-modal .fm-msg[data-message-id="11"]').getAttribute('class').catch(() => '');
  const cls12 = await page.locator('.fm-modal .fm-msg[data-message-id="12"]').getAttribute('class').catch(() => '');
  const cls13 = await page.locator('.fm-modal .fm-msg[data-message-id="13"]').getAttribute('class').catch(() => '');
  const cls14 = await page.locator('.fm-modal .fm-msg[data-message-id="14"]').getAttribute('class').catch(() => '');
  ok('O2b 对端窗内逐条方向（11/13=本机所发⇒右，12/14=对端所发⇒左）',
    /(^|\s)out(\s|$)/.test(cls11 || '') && /(^|\s)out(\s|$)/.test(cls13 || '')
      && /(^|\s)in(\s|$)/.test(cls12 || '') && /(^|\s)in(\s|$)/.test(cls14 || ''),
    `11="${cls11}" 12="${cls12}" 13="${cls13}" 14="${cls14}"`);
  const msgsConvs = [...new Set(captured.msgsConvs)].sort();
  ok('O2c 归并取数读了两条会话（本机行 + 对端行）',
    msgsConvs.includes('dev:' + SELF) && msgsConvs.includes('dev:' + PEER), `convs=${JSON.stringify(msgsConvs)}`);

  // ══ 判红② + R3：已读**按服务端行逐行**上报 / 回执读**本机那一行** ═══
  await sleep(600);
  const readByConv = new Map(captured.read.map(x => [x.convId, x.body]));
  ok('R3 已读上报带对端行自己的末条 id（dev:dev-beta → 14）',
    (readByConv.get('dev:' + PEER) || '').includes('"lastReadMessageId":14'), `captured=${JSON.stringify(captured.read)}`);
  ok('R3b 本机行无未读 ⇒ 不上报（dev:dev-alpha 零请求）', !readByConv.has('dev:' + SELF), `captured=${JSON.stringify(captured.read)}`);
  ok('R3c 回执读面拉的是**本机所发那一行**（dev:dev-alpha）',
    captured.receiptsConvs.length >= 1 && captured.receiptsConvs.every(c => c === 'dev:' + SELF), `convs=${JSON.stringify(captured.receiptsConvs)}`);
  const chip = await page.locator('.fm-modal .fm-msg[data-message-id="11"] .fm-msg-device-receipt').textContent().catch(() => null);
  ok('R3d 本机所发气泡挂「已读」状态位', chip === '已读', `got=${JSON.stringify(chip)}`);

  // ══ 判红④：旧格式行不崩 + 降级展示（真实 DOM 内）═══════════════════
  const oldRow = await page.locator('.fm-modal .fm-msg[data-message-id="13"]').count();
  ok('L1 旧格式行（缺 origin/createdAtMs/attachments）照常渲染', oldRow === 1, `count=${oldRow}`);
  const oldBody = await page.locator('.fm-modal .fm-msg[data-message-id="13"] .fm-msg-text').textContent().catch(() => null);
  ok('L1b 旧格式行正文降级可读', oldBody === 'old row no new fields', `got=${JSON.stringify(oldBody)}`);
  ok('L1c 全程零 pageerror', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));

  // ══ 附件第三分支：附件卡 + 成员闸下载路由（E3）══════════════════════
  const attCard = await page.locator('.fm-modal .fm-msg[data-message-id="12"] .fm-att').count();
  ok('R4 服务端附件条 → 附件卡', attCard === 1, `count=${attCard}`);
  const dlBtn = page.locator('.fm-modal .fm-msg[data-message-id="12"] .fm-att-dl');
  if (await dlBtn.count()) {
    await dlBtn.click();
    await sleep(700);
    ok('R4b 附件下载走既有鉴权路由 /api/friends/attachments/{id}', captured.attDownload === 1, `count=${captured.attDownload}`);
  } else {
    ok('R4b 附件下载键在场', false, 'no .fm-att-dl in device bubble');
  }

  // ══ F1：回复打**本机** device id（桩侧按服务端硬闸判）═══════════════
  await page.fill('.fm-modal .fm-input', 'hello from qa');
  await page.click('.fm-modal .fm-send-btn');
  await sleep(900);
  ok('S1 发送打本机 device id（POST /api/devices/dev-alpha/messages）',
    captured.deviceSendPaths.length >= 1 && captured.deviceSendPaths.every(p => p === SELF),
    `paths=${JSON.stringify(captured.deviceSendPaths)}`);
  ok('S1b 桩未触发 not_my_device（无 403；即未打对端 id）',
    captured.deviceSendStatus.every(s => s === 201), `status=${JSON.stringify(captured.deviceSendStatus)}`);
  const sentShown = await page.locator('.fm-modal .fm-msg[data-message-id="99"]').count();
  ok('S1c 发送后服务端回读入窗（幂等回放不产第二行）', sentShown === 1, `count=${sentShown}`);
  const sentCls = await page.locator('.fm-modal .fm-msg[data-message-id="99"]').getAttribute('class').catch(() => '');
  ok('S1d 我发出的新行在同一窗且画右侧', /(^|\s)out(\s|$)/.test(sentCls || ''), `class=${sentCls}`);
  await page.keyboard.press('Escape');
  await sleep(300);

  // ══ 判红③ en 半程：切 en 重开窗 ═══════════════════════════════════
  await page.evaluate(async () => { const i = await import('/js/i18n.js'); i.setLocale('en'); });
  await sleep(200);
  await deviceRow.click();
  await page.waitForSelector('.fm-modal', { timeout: 15000 });
  await sleep(700);
  // ⑤b 同批（2026-09-15）：en 面同断言，且**键本体**已删（禁死键）。
  const enNoteCount = await page.locator('.fm-modal .fm-device-note').count();
  ok('R1d ⑤b 替代断言：en 面设备窗零留存明示条', enNoteCount === 0, `count=${enNoteCount}`);
  const enKeyGone = await page.evaluate(async () => {
    const m = (await import('/js/locales/en.js')).default;
    const zh = (await import('/js/locales/zh-CN.js')).default;
    return { en: m['messages.deviceRetention'], zh: zh['messages.deviceRetention'] };
  });
  ok('R1e ⑤b 替代断言：messages.deviceRetention 双语键已删（无死键）',
    enKeyGone.en === undefined && enKeyGone.zh === undefined, JSON.stringify(enKeyGone));
  await page.keyboard.press('Escape');
  await sleep(300);

  // ══ F1 要件②：本机 device id 缺席 ⇒ **可见禁用** + 零发送请求 ═══════
  // 同页换桩（`unroute` + reload）：新开 page 会退到后台，Playwright 的可见性判定
  // 在非活动 tab 上不稳（本探针可靠性优先）；换桩后重载等价于新会话。
  const cap2 = { read: [], receiptsConvs: [], deviceSendPaths: [], deviceSendStatus: [], attDownload: 0, msgsConvs: [] };
  await page.unroute('**/api/**');
  await installRoutes(page, cap2, { selfDeviceId: null }); // status.device = {}（无 id）
  await page.reload();
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(1200);
  await page.evaluate(async () => { const n = await import('/js/neblink.js'); await n.fetchNeblinkStatus(); });
  await sleep(300);
  await page.click('#messages-btn');
  await sleep(1500);
  // 面板活动态跨 reload 复位不定 ⇒ 只在未呈现时点开（点了会变成「关闭」）
  if (!(await page.locator('#fm-conversations .fm-conv-row').first().isVisible().catch(() => false))) {
    await page.click('#messages-btn');
  }
  await page.waitForSelector('#fm-conversations .fm-conv-row[data-device="1"]', { timeout: 15000 });
  await sleep(600);
  await page.locator('#fm-conversations .fm-conv-row[data-device="1"]').first().click();
  await page.waitForSelector('.fm-modal', { timeout: 15000 });
  await sleep(700);
  const inputDisabled = await page.locator('.fm-modal .fm-input').isDisabled().catch(() => false);
  ok('N1 本机 id 缺席 ⇒ 输入框禁用（可见）', inputDisabled === true, `disabled=${inputDisabled}`);
  const barText = await page.locator('.fm-modal .fm-blocked-bar').textContent().catch(() => null);
  ok('N1b 只读栏给出可见原因（文案在场）', typeof barText === 'string' && barText.trim().length > 0, `bar=${JSON.stringify(barText)}`);
  const sendDisabled = await page.locator('.fm-modal .fm-send-btn').isDisabled().catch(() => false);
  ok('N1c 发送键同闸禁用', sendDisabled === true, `disabled=${sendDisabled}`);
  // 硬上：把禁用态强行解除后点发送 ⇒ 仍然零请求（第二道闸）
  await page.evaluate(() => {
    const el = document.querySelector('.fm-modal .fm-input');
    el.disabled = false; el.value = 'should not be sent';
    const btn = document.querySelector('.fm-modal .fm-send-btn'); btn.disabled = false; btn.click();
  });
  await sleep(600);
  ok('N1d 身份缺席时零发送请求（绝不打对端 id）', cap2.deviceSendPaths.length === 0, `paths=${JSON.stringify(cap2.deviceSendPaths)}`);
  ok('N1e 该轮零 pageerror', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));

  // ══ 判红④（降级档）：旧网关无设备会话面 ⇒ peers 派生腿（legacy）不崩 ═
  // 🔴 本档**已被作者 2026-09-15 的 ⑥ 取代一半**：原断言「无服务端行 ⇒ peers 派生窗
  // 仍在」正是作者要禁的行为（未通信设备直接出现在消息面板）。本档改两步跑：
  //   ① 无任何通信证据（无服务端行 + 无本地缓存）⇒ 消息面板**零设备行**；
  //   ② 灌入本地缓存（= 与该设备通信过）⇒ 同一条 peers 派生腿照旧复现（名字走 peers 单点）。
  const cap3 = { read: [], receiptsConvs: [], deviceSendPaths: [], deviceSendStatus: [], attDownload: 0, msgsConvs: [] };
  await page.unroute('**/api/**');
  await installRoutes(page, cap3, { noServerDeviceRows: true });
  await page.reload();
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(1500);
  await page.evaluate(async () => { const n = await import('/js/neblink.js'); await n.fetchNeblinkStatus(); });
  await sleep(300);
  if (!(await page.locator('#fm-conversations .fm-conv-row').first().isVisible().catch(() => false))) {
    await page.click('#messages-btn');
  }
  await sleep(700);
  const noEvidenceRows = await page.locator('#fm-conversations .fm-conv-row[data-device="1"]').count();
  ok('L2 ⑥ 替代断言：旧网关 + 无通信证据 ⇒ 消息面板零设备行（「peers 派生窗仍在」已被作者 06 令取代）',
    noEvidenceRows === 0, `rows=${noEvidenceRows}`);

  // ② 通信后的正向读数（本地缓存 = 「与它通信过」的本地半程证据）
  const legacySeeded = await page.evaluate(async () => {
    const c = await import('/js/fmDropboxCache.js');
    return c.saveDeviceMessages('dev-beta', [{ msgId: 'legacy-1', kind: 'text', direction: 'out', ts: Date.now(), text: 'legacy hello' }]);
  });
  await page.reload();
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(1500);
  await page.evaluate(async () => { const n = await import('/js/neblink.js'); await n.fetchNeblinkStatus(); });
  await sleep(300);
  if (!(await page.locator('#fm-conversations .fm-conv-row').first().isVisible().catch(() => false))) {
    await page.click('#messages-btn');
  }
  await page.waitForSelector('#fm-conversations .fm-conv-row[data-device="1"]', { timeout: 15000 });
  await sleep(500);
  const legacyName = await page.locator('#fm-conversations .fm-conv-row[data-device="1"] .fm-row-name').first().textContent().catch(() => null);
  ok('L2b 已通信（本地缓存证据）⇒ peers 派生腿照旧成窗（名字走 peers 单点）',
    legacySeeded === 1 && legacyName === 'Phone', `seeded=${legacySeeded} name=${JSON.stringify(legacyName)}`);
  ok('L2c 该档零服务端设备取数（legacy 腿不假装有服务端面）', cap3.msgsConvs.every(c => !c.startsWith('dev:')), `convs=${JSON.stringify([...new Set(cap3.msgsConvs)])}`);
  ok('L2d 该档零 pageerror', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
} finally {
  if (ctx) await ctx.close().catch(() => {});
  await browser.close().catch(() => {});
  await new Promise(r => server.close(r));
}

console.log(`\n[${MODE}] web root = ${WEB}`);
console.log(`[${MODE}] failures = ${failures}`);
process.exit(failures === 0 ? 0 : 1);
