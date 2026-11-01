// attcard-unify-shots.mjs — fm-att-card-unify 批（2026-11-02 作者令：好友/群/设备
// 三消息面附件卡统一成**主会话附件卡样式**）的**视觉验收实拍**。
//
// 帧（≥6 + 手机档 + 主会话对照，作者验收面）：
//   attcard-friend-{light,dark}.png   好友窗 × 图片消息（收/发两向）+ 文件消息（收/发两向）
//   attcard-group-{light,dark}.png    群窗 × 同上
//   attcard-device-{light,dark}.png   设备窗（服务端腿 dev:<peer>）× 同上
//   attcard-friend-mobile-375.png     手机档 375×812（亮档，好友窗）
//   attcard-vs-main-light.png         **同页并排对照**：主会话 buildUserMediaRow 行
//                                     （popArtifacts 原装配出口）注入好友流顶部，
//                                     与 fm 卡同帧 —— 证明同一卡语言。
//
// 🔴 零实例依赖（与 imgmsg-send.spec.mjs 同款纪律）：静态资源 = 内置 http server
//   （随机端口，收尾必关）；API/WS = page.route 契约镜像，非生产网关；
//   🔴 禁把本 harness 读数冒充生产实测。收尾判据 = **零 console error / 零 pageerror**
//   （favicon 显式 204，不计资源噪声）。
//
// Run: NODE_PATH=/opt/homebrew/lib/node_modules node tests/attcard-unify-shots.mjs
//   ATT_SHOTS=<dir>  落盘目录（缺省 /tmp/attcard-unify-shots）

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.ATT_SHOTS || '/tmp/attcard-unify-shots';
mkdirSync(OUT, { recursive: true });

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = ms => new Promise(r => setTimeout(r, ms));
const iso = s => new Date(Date.now() + s * 1000).toISOString();

// ── 真位图（真解码/真内禀比；手写 PNG 编码器 = imgmsg-send.spec 同式）────────
const CRC = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) c = CRC[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}
function pngBytes(w, h) {
  const BANDS = [[224, 96, 96], [96, 176, 224], [120, 200, 140], [232, 200, 96], [176, 132, 224], [240, 240, 240]];
  const raw = Buffer.alloc((w * 3 + 1) * h);
  let o = 0;
  for (let y = 0; y < h; y++) {
    raw[o++] = 0;
    const c = BANDS[Math.floor(y / Math.max(1, h / BANDS.length)) % BANDS.length];
    for (let x = 0; x < w; x++) { raw[o++] = c[0]; raw[o++] = c[1]; raw[o++] = c[2]; }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
    const body = Buffer.concat([Buffer.from(type, 'latin1'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body), 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]);
}
const PNG_43 = pngBytes(1600, 1200);   // 4:3 横
const PNG_169 = pngBytes(1600, 900);   // 16:9 横
const PNG_SQ = pngBytes(1200, 1200);   // 1:1 方
const PNG_TALL = pngBytes(900, 1600);  // 9:16 竖
const FILE_BYTES = Buffer.from('%PDF-1.4\nmock bytes — card visual only\n');

/** 附件行工厂（wire = AttachmentDto：id/name/size/mime?/state）。 */
const imgAtt = (id, name, png) => ({ id, name, size: png.length, mime: 'image/png', state: 'ready' });
const fileAtt = (id, name, size) => ({ id, name, size, mime: 'application/pdf', state: 'ready' });

// ── REST 契约镜像 ──────────────────────────────────────────────
const EPOCH = Date.now();
const FRIEND = { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(-86400) };
const friendRow = (id, senderId, at, atts) => ({ id, senderId, kind: 'text', body: '', createdAt: iso(at), attachments: atts });
const deviceRow = (id, senderDeviceId, atMs, atts) => ({ id, senderDeviceId, kind: 'text', body: '', createdAtMs: EPOCH + atMs, attachments: atts });

const MSGS = {
  cF: [
    friendRow(601, 'u-p', -3600, [imgAtt('att-f-img-in', 'sunrise-4x3.png', PNG_43)]),
    friendRow(602, 'me', -3000, [imgAtt('att-f-img-out', 'city-16x9.png', PNG_169)]),
    friendRow(603, 'u-p', -2400, [fileAtt('att-f-file-in', 'quarterly-report.pdf', 204800)]),
    friendRow(604, 'me', -1800, [fileAtt('att-f-file-out', 'design-spec.md', 15360)]),
  ],
  'g-1': [
    friendRow(651, 'u-p', -7200, [imgAtt('att-g-img-in', 'whiteboard-1x1.png', PNG_SQ)]),
    friendRow(652, 'me', -6600, [imgAtt('att-g-img-out', 'portrait-9x16.png', PNG_TALL)]),
    friendRow(653, 'u-p', -6000, [fileAtt('att-g-file-in', 'meeting-notes.pdf', 88320)]),
    friendRow(654, 'me', -5400, [fileAtt('att-g-file-out', 'roadmap.md', 20480)]),
  ],
  'dev:peer-1': [
    deviceRow(701, 'peer-1', -10800000, [imgAtt('att-d-img-in', 'lab-shot-4x3.png', PNG_43)]),
    deviceRow(702, 'self-1', -10200000, [imgAtt('att-d-img-out', 'screenshot-16x9.png', PNG_169)]),
    deviceRow(703, 'peer-1', -9600000, [fileAtt('att-d-file-in', 'device-log.txt', 4420)]),
    deviceRow(704, 'self-1', -9000000, [fileAtt('att-d-file-out', 'backup.zip', 1048576)]),
  ],
};
const ATT_BYTES = new Map([
  ['att-f-img-in', PNG_43], ['att-f-img-out', PNG_169], ['att-g-img-in', PNG_SQ], ['att-g-img-out', PNG_TALL],
  ['att-d-img-in', PNG_43], ['att-d-img-out', PNG_169],
  ['att-f-file-in', FILE_BYTES], ['att-f-file-out', FILE_BYTES], ['att-g-file-in', FILE_BYTES],
  ['att-g-file-out', FILE_BYTES], ['att-d-file-in', FILE_BYTES], ['att-d-file-out', FILE_BYTES],
]);
const CONVS = [
  { conversationId: 'cF', friend: FRIEND, lastMessage: MSGS.cF[MSGS.cF.length - 1], unreadCount: 0 },
  { conversationId: 'dev:peer-1', kind: 'device', deviceId: 'peer-1', lastMessage: MSGS['dev:peer-1'][0], unreadCount: 0 },
  { conversationId: 'dev:self-1', kind: 'device', deviceId: 'self-1', lastMessage: null, unreadCount: 0 },
];
const GROUPS = [{ groupId: 'g-1', title: '项目群', unreadCount: 0, memberCount: 3, role: 'owner', memberAvatars: [], lastMessage: MSGS['g-1'][MSGS['g-1'].length - 1] }];

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const browser = await chromium.launch();
  const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (path === '/favicon.ico') { res.writeHead(204); res.end(); return; } // 资源噪声，不计错
    if (path.startsWith('/agents/')) { // 会话列表 agent 档案（缺席即回退默认，零行为差）
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return;
    }
    const file = join(WEB, path === '/' ? 'index.html' : path);
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    console.log('404', req.url);
    res.writeHead(404); res.end('not found');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

/** 主会话对照行的 image preview = 同一枚 4:3 真位图的 data URI（buildUserMediaRow
 *  的 inline-preview 腿 ⇒ 零网络出帧）。 */
const PREVIEW_URI = 'data:image/png;base64,' + PNG_43.toString('base64');

async function bootPage({ colorScheme = 'light', viewport = { width: 1280, height: 900 } } = {}) {
  const ctx = await browser.newContext({ colorScheme, viewport });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const consoleErrors = [];
  const pageErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });
  page.on('pageerror', e => pageErrors.push(String(e).slice(0, 300)));

  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const u = new URL(req.url());
    const p = u.pathname;
    const method = req.method();
    if (method === 'GET' && /^\/api\/friends\/attachments\//.test(p)) {
      const id = decodeURIComponent(p.split('/').pop());
      // 契约口径：应用内鉴权路由恒 application/octet-stream（RestApiRoutes 写死）。
      return route.fulfill({ status: 200, contentType: 'application/octet-stream', body: ATT_BYTES.get(id) || FILE_BYTES });
    }
    if (p === '/api/neblink/status') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        loggedIn: true,
        relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
        device: { id: 'self-1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' },
        peers: [{ deviceId: 'peer-1', deviceName: '书房 Mac', name: '书房 Mac', platform: 'macos', online: true, directOnline: true, avatarUrl: '' }],
      }) });
    }
    if (p === '/api/conversations') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONVS) });
    if (p === '/api/friends') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [FRIEND], incoming: [], outgoing: [] }) });
    if (p === '/api/groups') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(GROUPS) });
    if (p === '/api/groups/invites') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ incoming: [] }) });
    const mm = p.match(/^\/api\/conversations\/([^/]+)\/messages$/);
    if (mm) {
      const cid = decodeURIComponent(mm[1]);
      const after = Math.max(0, Math.floor(Number(u.searchParams.get('after')) || 0));
      const rows = (MSGS[cid] || []).filter(x => Number(x.id) > after);
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rows) });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(900);
  return { ctx, page, consoleErrors, pageErrors };
}

const waitFlowImages = (page) => page.waitForFunction(() => {
  const imgs = [...document.querySelectorAll('.fm-flow .fm-att-media .pop-img')];
  return imgs.length > 0 && imgs.every(i => i.complete && i.naturalWidth > 0);
}, null, { timeout: 15000 }).catch(() => false);

async function openConv(page, cid) {
  await page.click('#messages-btn'); await sleep(600);
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(900);
  await waitFlowImages(page); await sleep(400);
  // 取景锚定：真帧落位（竖图长卡）会顶开流内容 ⇒ 显式贴底，帧内容确定。
  await page.evaluate(() => { const f = document.querySelector('.fm-flow'); if (f) f.scrollTop = f.scrollHeight; });
  await sleep(250);
}

async function shotChat(page, name, extra = {}) {
  const overlay = await page.$('#fm-chat-overlay');
  const path = join(OUT, name);
  if (overlay) await overlay.screenshot({ path }).catch(() => {});
  const cardProbe = await page.evaluate(() => {
    const media = [...document.querySelectorAll('.fm-flow .fm-att-media .pop-media-flat')].length;
    const fileCards = [...document.querySelectorAll('.fm-flow .file-card.fm-att')].length;
    const dlKeys = [...document.querySelectorAll('.fm-flow .fm-att-dl')].length;
    return { media, fileCards, dlKeys };
  });
  console.log(`SHOT ${name}  cards=${JSON.stringify(cardProbe)}${extra.note ? '  ' + extra.note : ''}`);
  ok(`shot ${name} 落盘 + 卡形态（媒体面/文件面/下载键在场）`,
    cardProbe.media >= 2 && cardProbe.fileCards >= 2 && cardProbe.dlKeys >= 2,
    JSON.stringify(cardProbe));
  return cardProbe;
}

// ═════════════════════════════════════════════════════════════════
try {
  console.log(`attcard-unify shots · WEB=${WEB} · OUT=${OUT}`);
  for (const scheme of ['light', 'dark']) {
    const { ctx, page, consoleErrors, pageErrors } = await bootPage({ colorScheme: scheme });
    try {
      await openConv(page, 'cF');
      await shotChat(page, `attcard-friend-${scheme}.png`);
      // 群窗
      await page.click('.fm-modal-close').catch(async () => { await page.keyboard.press('Escape'); });
      await sleep(400);
      await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="g-1"]`); await sleep(900);
      await waitFlowImages(page); await sleep(400);
      await shotChat(page, `attcard-group-${scheme}.png`);
      // 设备窗
      await page.click('.fm-modal-close').catch(async () => { await page.keyboard.press('Escape'); });
      await sleep(400);
      await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="dev:peer-1"]`); await sleep(900);
      await waitFlowImages(page); await sleep(400);
      await shotChat(page, `attcard-device-${scheme}.png`);
      ok(`[${scheme}] 零 console error`, consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok(`[${scheme}] 零 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }

  // 手机档（375×812，亮档，好友窗）：≤768px 命中手机交互层（body.mobile-layer），
  // 桌面 rail 键隐藏 ⇒ 走 dock 消息键导航（socialFaces 收编同一份 #fm-conversations）。
  {
    const { ctx, page, consoleErrors, pageErrors } = await bootPage({ colorScheme: 'light', viewport: { width: 375, height: 812 } });
    try {
      await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
      await page.click('.dock-key[data-dock="messages"]');
      await sleep(800);
      await page.click('#fm-conversations .fm-conv-row[data-conversation-id="cF"]');
      await sleep(900);
      await waitFlowImages(page); await sleep(400);
      const shotPath = join(OUT, 'attcard-friend-mobile-375.png');
      const overlay = await page.$('#fm-chat-overlay');
      if (overlay) await overlay.screenshot({ path: shotPath }).catch(async () => { await page.screenshot({ path: shotPath }); });
      else await page.screenshot({ path: shotPath });
      const cardProbe = await page.evaluate(() => ({
        media: document.querySelectorAll('.fm-att-media .pop-media-flat').length,
        fileCards: document.querySelectorAll('.file-card.fm-att').length,
        dlKeys: document.querySelectorAll('.fm-att-dl').length,
        mobile: document.body.classList.contains('mobile-layer'),
      }));
      console.log(`SHOT attcard-friend-mobile-375.png  cards=${JSON.stringify(cardProbe)}`);
      ok('shot attcard-friend-mobile-375.png 手机档卡形态（媒体面/文件面/下载键在场）',
        cardProbe.media >= 2 && cardProbe.fileCards >= 2 && cardProbe.dlKeys >= 2 && cardProbe.mobile,
        JSON.stringify(cardProbe));
      ok('[mobile] 零 console error', consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok('[mobile] 零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }

  // 主会话并排对照帧：主会话 buildUserMediaRow 原装配出口注入好友流顶部，与 fm 卡同帧。
  {
    const { ctx, page, consoleErrors, pageErrors } = await bootPage({ colorScheme: 'light' });
    try {
      await openConv(page, 'cF');
      const injected = await page.evaluate(async (preview) => {
        const { buildUserMediaRow } = await import('/js/popArtifacts.js');
        const row = buildUserMediaRow([
          { type: 'image', name: 'sunrise-4x3.png', path: '', preview, size: 9526 },
          { type: 'file', name: 'quarterly-report.pdf', path: '/tmp/quarterly-report.pdf', size: 204800 },
        ], { ts: Date.now() });
        if (!row) return { ok: false };
        const flow = document.querySelector('.fm-flow');
        flow.insertBefore(row, flow.firstChild);
        await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
        // 对照行插在流顶 ⇒ 滚回顶部入画（缺省开窗贴底，注入行在折叠线上方）
        flow.scrollTop = 0;
        const sc = flow.parentElement;
        if (sc && sc.scrollTop) sc.scrollTop = 0;
        await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
        return {
          ok: true,
          mainMedia: !!row.querySelector('.pop-media-flat .pop-img'),
          mainFile: !!row.querySelector('.file-card'),
          mainMediaAr: (row.querySelector('.pop-media-flat') || {}).dataset ? (row.querySelector('.pop-media-flat').dataset.nfRatio || '') : '',
          inView: (() => { const r = row.getBoundingClientRect(); const f = flow.getBoundingClientRect(); return r.top >= f.top - 2 && r.top < f.bottom; })(),
        };
      }, PREVIEW_URI).catch(e => ({ ok: false, err: String(e).slice(0, 200) }));
      await sleep(500);
      ok('对照帧：主会话行注入成功且在画内（媒体面 + 文件面）', !!injected && injected.ok && injected.mainMedia && injected.mainFile && injected.inView, JSON.stringify(injected));
      await shotChat(page, 'attcard-vs-main-light.png', { note: 'top row = 主会话 buildUserMediaRow' });
      ok('[vs-main] 零 console error', consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok('[vs-main] 零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }
} catch (e) {
  failures++;
  console.error('HARNESS ERROR:', e && e.stack || e);
} finally {
  try { await browser.close(); } catch { /* non-critical */ }
  try { await new Promise(r => server.close(r)); } catch { /* non-critical */ }
  writeFileSync(join(OUT, 'shots-index.json'), JSON.stringify({ out: OUT, failures, at: new Date().toISOString() }, null, 2));
  console.log(`\n=== attcard-unify shots: failures=${failures} · OUT=${OUT} ===`);
  process.exit(failures === 0 ? 0 : 1);
}
