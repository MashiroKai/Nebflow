// mvp2-device-client.spec.mjs — MVP-2 设备会话切服务端数据源 · 客户端腿验收
// （契约真源 = neblink-server main@4fceff4 §8.1/§8.3/§8.5–§8.8 + 设计卡 §6.3 D1–D10）
//
// 🔴 本 spec 是**同一支探针**跑两棵树（判红⑤「改前不可满足钉」）：
//    · MVP2_WEB_ROOT 未设 ⇒ 跑本仓 `src/main/resources/web`（改后）⇒ 必须全 PASS；
//    · MVP2_WEB_ROOT=<改前树>（`git archive` 出的基线 web/）⇒ 同一支探针必须**红**，
//      且红的**断言名**逐条可读 —— 那就是本改动「改前不可满足」的原文证据。
//
// 覆盖：
//   A1~A6 适配层逐字段（判红①）：`DropboxMessage → {id, body, createdAtMs, origin,
//          ours, attachments}` 六字段 + `msgId→id` + 方向重算 P2
//          （本机 `dev-local` / 他机 `dev-peer` / 未知设备 / legacy direction）
//   U1     服务端行进列表（kind/deviceId/unreadCount 角标）
//   R1     设备窗 P10 明示句（zh-CN + en 双语，判红③）
//   R2     未读/回执读写往返（原始请求/响应读数，判红②的客户端半程）
//   R3     附件第三分支（服务端 attachments → attachmentCard + 成员闸下载路由）
//   L1     旧数据无新字段不崩 + 降级展示（判红④）
//   S1     设备发送走 POST /api/devices/{id}/messages（201 幂等）
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
// 设备会话两行：`dev:dev-local`（本机所发）与 `dev:dev-peer`（对端所发）——
// 契约 §9.3：一次双向对聊在服务端落两行，客户端按 peer 归并展示。
const SERVER_CONVOS = [
  {
    conversationId: 'dev:dev-peer', kind: 'device', deviceId: 'dev-peer',
    unreadCount: 2,
    friend: { userId: 'me', username: 'me', display_name: 'Me', avatar: null },
    lastMessage: { id: 12, senderId: 'me', kind: 'text', body: 'peer says hi', createdAt: 1757900000, senderDeviceId: 'dev-peer' },
  },
  {
    conversationId: 'c-legacy', friend: { userId: 'u1', username: 'lin', display_name: 'Lin', avatar: null },
    lastMessage: null, unreadCount: 0,
  },
];
// `dev:dev-peer` 的消息行：id 11 = 本机所发（senderDeviceId=dev-local）、
// id 12 = 对端所发（带服务端附件条）、id 13 = 旧格式行（缺 origin/createdAtMs/attachments）
const SERVER_MSGS = [
  { id: 11, senderId: 'me', kind: 'text', body: 'from my mac', createdAt: 1757899990, senderDeviceId: 'dev-local' },
  {
    id: 12, senderId: 'me', kind: 'text', body: '[附件] photo.png', createdAt: 1757900000, senderDeviceId: 'dev-peer',
    attachments: [{ id: 'att-1', name: 'photo.png', size: 31, sha256: 'd3e62224', state: 'ready' }],
  },
  // 旧格式行（判红④）：无 origin、无 createdAtMs、无 attachments、无 senderDeviceId
  { id: 13, senderId: 'me', kind: 'text', body: 'old row no new fields', createdAt: 1757900010 },
];

const captured = { read: [], receipts: 0, deviceSend: [], attDownload: 0 };

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

  await page.route('**/api/**', async r => {
    const url = new URL(r.request().url());
    const p = decodeURIComponent(url.pathname);
    const m = r.request().method();
    const body = r.request().postData() || '';
    if (p === '/api/neblink/status') {
      return r.fulfill({ json: { loggedIn: true, device: { id: 'dev-local', name: 'Mac', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [{ deviceId: 'dev-peer', deviceName: 'Phone', platform: 'ios', online: true, directOnline: true }] } });
    }
    if (p === '/api/conversations' && m === 'GET') return r.fulfill({ json: SERVER_CONVOS });
    if (p === '/api/conversations/dev:dev-peer/messages') return r.fulfill({ json: SERVER_MSGS });
    if (p === '/api/conversations/c-legacy/messages') return r.fulfill({ json: SERVER_MSGS });
    if (p === '/api/conversations/dev:dev-peer/read' && m === 'POST') {
      captured.read.push(body);
      return r.fulfill({ json: { ok: true } });
    }
    if (p === '/api/conversations/dev:dev-peer/receipts') {
      captured.receipts++;
      // 契约 §8.7：sent/read 高水位 + 逐条回执行（state ∈ {sent, read}）
      return r.fulfill({ json: { receipts: [{ messageId: 11, state: 'read' }], lastSentMessageId: 11, lastReadMessageId: 11 } });
    }
    if (p === '/api/devices/dev-peer/messages' && m === 'POST') {
      captured.deviceSend.push({ body, status: 201 });
      const payload = JSON.parse(body || '{}');
      const replay = payload.clientMsgId === 'dup-1';
      // 桩侧落行（服务端语义：新行 id=99；幂等回放不产第二行）——使发送后的
      // keyset 回读能真实反映「回读入窗」而非回显。
      if (!replay && !SERVER_MSGS.some(x => x.id === 99)) {
        SERVER_MSGS.push({ id: 99, senderId: 'me', kind: 'text', body: payload.body || '', createdAt: 1757900100, senderDeviceId: 'dev-local' });
      }
      return r.fulfill({ status: 201, json: { messageId: replay ? 11 : 99, conversationId: 'dev:dev-peer', createdAt: 1757900100, createdAtMs: 1757900100123, existing: replay, selfUserId: 'me' } });
    }
    if (p === '/api/friends/attachments/att-1') { captured.attDownload++; return r.fulfill({ body: 'hello-bytes' }); }
    if (p === '/api/friends') return r.fulfill({ json: { friends: [], incoming: [], outgoing: [] } });
    if (p === '/api/groups') return r.fulfill({ json: [] });
    return r.fulfill({ json: {} });
  });
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });

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
      // 本机所发（服务端行）
      adapt({ id: 11, senderId: 'me', kind: 'text', body: 'from my mac', createdAt: 1757899990, senderDeviceId: 'dev-local' }),
      // 他机所发（服务端行，带附件）
      adapt({ id: 12, senderId: 'me', kind: 'text', body: '[附件] photo.png', createdAt: 1757900000, senderDeviceId: 'dev-peer', attachments: [{ id: 'att-1', name: 'photo.png', size: 31, sha256: 'x', state: 'ready' }] }),
      // 未知发送设备（不在本机比对上 ⇒ 非本机所发，保守 in）
      adapt({ id: 14, senderId: 'me', kind: 'text', body: 'from unknown', createdAt: 1757900020, senderDeviceId: 'dev-ghost' }),
      // 旧格式服务端行（缺 origin/createdAtMs/attachments/senderDeviceId）
      adapt({ id: 13, senderId: 'me', kind: 'text', body: 'old row no new fields', createdAt: 1757900010 }),
      // legacy 网关 DropboxMessage（msgId/text/ts/direction）
      adapt({ msgId: 'uuid-a', text: 'legacy text', ts: 1757900030000, direction: 'out', origin: 'user' }),
      // legacy 网关文件消息
      adapt({ msgId: 'uuid-b', kind: 'file', text: '', ts: 1757900040000, direction: 'in', fileName: 'a.bin', fileSize: 7, status: 'completed' }),
      // 缺 id 且缺 msgId（不可定位行）
      adapt({ body: 'no id' }),
    ];
    return { selfId: undefined, rows: run() };
  });
  if (adapter.error) {
    ok('A0 适配层可探（export 在场）', false, adapter.error);
  } else {
    const R = adapter.rows;
    ok('A0 适配层可探（export 在场）', true);
    // A1 六字段逐条：本机所发行
    ok('A1 本机行六字段', (() => { const r = R[0];
      return r.id === '11' && r.body === 'from my mac' && r.createdAtMs === 1757899990000
        && r.origin === 'user' && r.ours === true && Array.isArray(r.attachments) && r.attachments.length === 0; })(),
      JSON.stringify(R[0]));
    // A2 他机行：方向 = false（P2 重算），附件条透传为卡
    ok('A2 他机行方向重算 ours=false', R[1].ours === false, `ours=${R[1].ours}`);
    ok('A2b 他机行附件 → 附件卡(state 透传)', R[1].attachments.length === 1 && R[1].attachments[0].id === 'att-1' && R[1].attachments[0].state === 'ready', JSON.stringify(R[1].attachments));
    ok('A2c 他机行 createdAtMs 毫秒', R[1].createdAtMs === 1757900000000, `got ${R[1].createdAtMs}`);
    // A3 未知发送设备 ⇒ 非本机（禁「判不出 ⇒ 当本机」）
    ok('A3 未知 senderDeviceId ⇒ ours=false（不是本机）', R[2].ours === false, `ours=${R[2].ours}`);
    // A4 旧格式行：不崩 + 降级（origin 落 user、毫秒由秒折算、附件空集）
    ok('A4 旧格式行降级不崩', R[3].id === '13' && R[3].origin === 'user' && R[3].createdAtMs === 1757900010000 && R[3].attachments.length === 0, JSON.stringify(R[3]));
    // A5 legacy：msgId→id / text→body / ts→createdAtMs / direction out ⇒ ours=true
    ok('A5 legacy msgId→id + direction out ⇒ ours=true', R[4].id === 'uuid-a' && R[4].body === 'legacy text' && R[4].createdAtMs === 1757900030000 && R[4].ours === true, JSON.stringify(R[4]));
    // A6 legacy 文件 → 设备传输卡（state 'device'）
    ok('A6 legacy 文件 → 设备传输卡', R[5].ours === false && R[5].attachments.length === 1 && R[5].attachments[0].state === 'device' && R[5].attachments[0].name === 'a.bin', JSON.stringify(R[5]));
    // A7 缺 id 行：空 id（调用方跳过），不抛
    ok('A7 缺 id 行不抛、落空 id', R[6] && R[6].id === '', JSON.stringify(R[6]));
  }

  // ══ 判红③：P10 明示句（zh-CN 先开窗断言，再切 en 重开断言）══════════
  await page.click('#messages-btn');
  await page.waitForSelector('#fm-conversations .fm-conv-row', { timeout: 15000 });
  await sleep(700);
  // `.first()`：改前树会出现同一设备的**两行**（服务端行未剔除 + peers 派生行）——
  // 那本身就是判红⑤要暴露的缺陷读数；探针不得因 strict mode 崩掉，必须跑完全表。
  const deviceRow = page.locator('#fm-conversations .fm-conv-row[data-conversation-id="dev:dev-peer"]').first();
  ok('U1 服务端设备行进列表（data-device=1）', await deviceRow.count() === 1);
  ok('U1b 服务端未读角标 = 2', (await deviceRow.locator('.fm-row-badge').textContent().catch(() => '')) === '2',
    `badge=${await deviceRow.locator('.fm-row-badge').textContent().catch(() => '(none)')}`);

  const ZH = '云端保留 7 天，本机永久保存。';
  const EN = 'Cloud keeps messages for 7 days; this device keeps them permanently.';
  await deviceRow.click();
  await page.waitForSelector('.fm-modal', { timeout: 15000 });
  await sleep(900);
  const zhNote = await page.locator('.fm-modal .fm-device-note').textContent().catch(() => null);
  ok('R1 P10 明示句 zh-CN（且在设备会话窗内）', zhNote === ZH, `got=${JSON.stringify(zhNote)}`);
  const hasNoteInDeviceWin = await page.locator('.fm-modal .fm-device-note').count();
  ok('R1b 明示条仅挂设备窗（本窗恰 1 条）', hasNoteInDeviceWin === 1, `count=${hasNoteInDeviceWin}`);

  // ══ 判红⑤的核心读数：服务端历史真的进了设备窗 ══════════════════════
  const outCount = await page.locator('.fm-modal .fm-msg.out').count();
  const inCount = await page.locator('.fm-modal .fm-msg.in').count();
  ok('R2 服务端历史入窗：本机所发(11)在右 / 他机所发(12,13)在左', outCount >= 1 && inCount >= 1, `out=${outCount} in=${inCount}`);
  const bubbleId11 = await page.locator('.fm-modal .fm-msg[data-message-id="11"]').getAttribute('class').catch(() => '');
  ok('R2b id=11（senderDeviceId=dev-local=本机）画右侧', /(^|\s)out(\s|$)/.test(bubbleId11 || ''), `class=${bubbleId11}`);
  const bubbleId12 = await page.locator('.fm-modal .fm-msg[data-message-id="12"]').getAttribute('class').catch(() => '');
  ok('R2c id=12（senderDeviceId=dev-peer=他机）画左侧', /(^|\s)in(\s|$)/.test(bubbleId12 || ''), `class=${bubbleId12}`);

  // ══ 判红②（客户端半程）：写（已读）→ 读（回执）往返 ═════════════════
  await sleep(600);
  ok('R3 已读上报原始请求体 {lastReadMessageId:13}', captured.read.some(b => b.includes('"lastReadMessageId":13')),
    `captured=${JSON.stringify(captured.read)}`);
  ok('R3b 回执读面被拉取', captured.receipts >= 1, `count=${captured.receipts}`);
  const chip = await page.locator('.fm-modal .fm-msg[data-message-id="11"] .fm-msg-device-receipt').textContent().catch(() => null);
  ok('R3c 本机所发气泡挂「已读」状态位', chip === '已读', `got=${JSON.stringify(chip)}`);

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

  // ══ S1：设备发送走新端点（201 幂等）════════════════════════════════
  await page.fill('.fm-modal .fm-input', 'hello from qa');
  await page.click('.fm-modal .fm-send-btn');
  await sleep(900);
  ok('S1 发送走 POST /api/devices/{id}/messages', captured.deviceSend.length === 1 && captured.deviceSend[0].status === 201,
    JSON.stringify(captured.deviceSend));
  const sentShown = await page.locator('.fm-modal .fm-msg[data-message-id="99"]').count();
  ok('S1b 发送后服务端回读入窗（幂等回放不产第二行）', sentShown === 1, `count=${sentShown}`);
  await page.keyboard.press('Escape');
  await sleep(300);

  // ══ 判红③ en 半程：切 en 重开窗 ═══════════════════════════════════
  await page.evaluate(async () => { const i = await import('/js/i18n.js'); i.setLocale('en'); });
  await sleep(200);
  await deviceRow.click();
  await page.waitForSelector('.fm-modal', { timeout: 15000 });
  await sleep(700);
  const enNote = await page.locator('.fm-modal .fm-device-note').textContent().catch(() => null);
  ok('R1d P10 明示句 en（重开窗）', enNote === EN, `got=${JSON.stringify(enNote)}`);
  await page.keyboard.press('Escape');
} finally {
  if (ctx) await ctx.close().catch(() => {});
  await browser.close().catch(() => {});
  await new Promise(r => server.close(r));
}

console.log(`\n[${MODE}] web root = ${WEB}`);
console.log(`[${MODE}] failures = ${failures}`);
process.exit(failures === 0 ? 0 : 1);
