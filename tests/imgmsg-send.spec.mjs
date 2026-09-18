// imgmsg-send.spec.mjs — 好友聊天图片三症状（发得慢 / 不预览 / 气泡跳动）修复的
// **自包含真渲染**验收（真 Chromium + 真模块图 + 真 CSS + 真几何 + 真字节读数）。
//
// 批：imgmsg-impl 2026-09-18（作者五项全裁：③ 预留 13:10 / ② 首帧纯前端先行 /
// ① A1-full 乐观气泡 + 本机句柄直显 / ④ 无百分比进度条 / ⑤ 无过渡）。
//
// 运行（双向红绿钉 —— 同一 harness，两份被测树）：
//   IM_MODE=after  node tests/imgmsg-send.spec.mjs                       # 改后（本批）
//   IM_MODE=before IM_WEB_ROOT=<纯 main 的 web 树> node tests/imgmsg-send.spec.mjs  # 改前（撤修法）
// 环境变量：
//   IM_WEB_ROOT  被测 web 树（缺省 = 本仓 src/main/resources/web）
//   IM_MODE      `after`（缺省）｜`before`（改前基线：症状必须复现 = 红组）
//   IM_OUT       全部读数落盘为 JSON（证据件）
//
// 🔴 服务端口径的诚实申报（与 attachkey-idem.spec.mjs 同款纪律）：本 spec 的「服务端」=
//   页内 `page.route('**/api/**')` 的**契约镜像**（§8.6 幂等回放 / keyset 前进游标 /
//   附件字节路由），**不是**生产 neblink-server。本 spec 覆盖的是**前端渲染链**：
//   几何（真实布局）、时点（真实 DOM 变更）、字节来源（真实请求台账 + 真实图片解码）、
//   气泡身份（真实节点身份与逐帧 DOM 快照）。🔴 禁把本 spec 的读数冒充生产实测；
//   「网关 → 生产服务端」一跳由上游批次自己的真链证据承接（本批零后端改动）。
// 🔴 零实例依赖：不起任何 gateway 实例（无 sbt、零端口占用、不触碰宿主 :8080）；
//   静态资源由本 spec 内置的 http server（随机端口）供给，收尾必关（进程清理纪律）。

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { writeFileSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.IM_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const MODE = process.env.IM_MODE === 'before' ? 'before' : 'after';
const AFTER = MODE === 'after';
const IM_OUT = process.env.IM_OUT || '';

let failures = 0;
const R = { mode: MODE, web: WEB, readings: {} }; // 全部读数（证据件）
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const put = (k, v) => { R.readings[k] = v; console.log(`READ  ${k} = ${JSON.stringify(v)}`); };

// ── 真实位图（本 spec 唯一的图片字节源）：手写 PNG 编码器（zlib + CRC32）——
//    「真渲染」要求真位图：真分辨率、真解码、真内禀宽高比（禁 1×1 占位图，
//    那会让 `aspect-ratio` 的读数失去意义）。
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
/** @param {number} w @param {number} h @returns {Buffer} */
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
  ihdr[8] = 8; ihdr[9] = 2; // 8-bit truecolor RGB
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 6 })), chunk('IEND', Buffer.alloc(0)),
  ]);
}
// 4:3（1600×1200 · 主流照片）与 16:9（1600×900 · 宽幅）各一份真实位图。
const PNG_4x3 = pngBytes(1600, 1200);
const PNG_16x9 = pngBytes(1600, 900);
const PNG_TXT = Buffer.from('hello nebflow\n', 'utf8'); // 非图片件（范围闸反例）

// ── REST 契约镜像（唯一「服务端」）─────────────────────────
const EPOCH = Math.floor(Date.now() / 1000);
const iso = s => new Date(s * 1000).toISOString();
const IN_ATT = { id: 'att-in-1', name: 'photo-4x3.png', size: PNG_4x3.length, mime: 'image/png', state: 'ready' };
const IN_ATT_TXT = { id: 'att-in-2', name: 'notes.txt', size: PNG_TXT.length, mime: 'text/plain', state: 'ready' };

const SRV = {
  msgs: {},                 // convId → rows（ASC）
  convs: [],
  friends: [],
  calls: [],                // 每次请求的台账 {p, method, at, body}
  attachSeq: 0,
  uploadDelay: 500,         // 上传响应延迟（给乐观面留可观测窗口）
  postDelay: 500,           // 发消息响应延迟
  dlDelay: 1200,            // 附件字节延迟（给「字节到达前」留可观测窗口）
  postFrame: null,          // 非空 ⇒ 发消息处先自播该帧（回显腿先到的形态）
  attLatch: null,           // 非空 Promise ⇒ 附件字节**闸住**（确定性采样点）
  attNames: {},             // attachmentId → 真实文件名（上传 query 记下）
};
/** 会话 id ↔ 好友 id（发消息路由只带好友 id，落行要落到**正确**会话）。
 *  ⚠ 首版把新行一律写进 cA，而发送发生在 cC ⇒ 行腿永远补不到 ⇒ 改前树量不到
 *  「回环往返」（实测踩到）。 */
const FRIEND_CONV = { 'u-p': 'cA', 'u-q': 'cC' };
/** 闸住附件字节响应（返回释放函数）——「字节到达前」的采样点是**确定性**的，
 *  不靠 sleep 竞速（改前/改后同一时序）。 */
function holdAttachments() {
  let release;
  SRV.attLatch = new Promise((res) => { release = res; });
  return () => { const p = SRV.attLatch; SRV.attLatch = null; if (p) release(); };
}
function resetServer({ uploadDelay = 500, postDelay = 500, dlDelay = 1200, postFrame = null } = {}) {
  SRV.calls.length = 0;
  SRV.attachSeq = 0;
  SRV.uploadDelay = uploadDelay;
  SRV.postDelay = postDelay;
  SRV.dlDelay = dlDelay;
  SRV.postFrame = postFrame;
  SRV.attLatch = null;
  SRV.attNames = {};
  SRV.msgs = {
    cA: [
      { id: 101, senderId: 'u-p', kind: 'text', body: '', createdAt: iso(EPOCH - 300), attachments: [{ ...IN_ATT }] },
      { id: 102, senderId: 'u-p', kind: 'text', body: '下面的这条消息', createdAt: iso(EPOCH - 240) },
    ],
    cB: [
      { id: 201, senderId: 'u-p', kind: 'text', body: '', createdAt: iso(EPOCH - 200), attachments: [{ id: 'att-16x9', name: 'photo-16x9.png', size: PNG_16x9.length, mime: 'image/png', state: 'ready' }] },
      { id: 202, senderId: 'u-p', kind: 'text', body: '下面这条', createdAt: iso(EPOCH - 180) },
    ],
    cC: [{ id: 301, senderId: 'u-p', kind: 'text', body: '纯文本会话', createdAt: iso(EPOCH - 60) }],
  };
  const last = (cid) => { const a = SRV.msgs[cid] || []; return a[a.length - 1] || null; };
  SRV.convs = [
    { conversationId: 'cA', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) }, lastMessage: last('cA'), unreadCount: 0 },
    { conversationId: 'cB', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) }, lastMessage: last('cB'), unreadCount: 0 },
    { conversationId: 'cC', friend: { userId: 'u-q', neblinkId: 'other', name: '另一好友', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) }, lastMessage: last('cC'), unreadCount: 0 },
  ];
  SRV.friends = SRV.convs.map(c => c.friend);
}
const callsOf = (match) => SRV.calls.filter(c => match(c));

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const browser = await chromium.launch();
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = join(WEB, path === '/' ? 'index.html' : path);
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

/** 帧注入器（每个 page 一份）。 */
let WS = null;

async function bootPage() {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));

  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const u = new URL(req.url());
    const p = u.pathname;
    const method = req.method();
    let body = {};
    try { body = JSON.parse(req.postData() || '{}'); } catch { body = {}; }
    SRV.calls.push({ p, method, at: Date.now(), query: u.search, body });

    // 附件字节路由（**真字节**；可选闸/延迟 = 确定性观测窗口）
    if (method === 'GET' && /^\/api\/friends\/attachments\//.test(p)) {
      const id = decodeURIComponent(p.split('/').pop());
      const bytes = id === 'att-in-2' ? PNG_TXT : (id === 'att-16x9' ? PNG_16x9 : PNG_4x3);
      if (SRV.attLatch) await SRV.attLatch;
      else await sleep(SRV.dlDelay);
      return route.fulfill({ status: 200, contentType: id === 'att-in-2' ? 'text/plain' : 'image/png', body: bytes });
    }
    // 上传（返回真附件 id；加性延迟）
    if (method === 'POST' && p === '/api/attachments') {
      await sleep(SRV.uploadDelay);
      SRV.attachSeq += 1;
      const attachmentId = `att-up-${SRV.attachSeq}`;
      // 🔴 记下**真实文件名**（上传路由的 query 是名字的唯一来源）：行腿回带的附件名
      //    必须是真图片名，否则 `isImageAttachmentName` 不成立 ⇒ 直显槽不挂 ⇒
      //    「回环往返」读数恒 0（首版用 `up-0` 踩到）。
      SRV.attNames[attachmentId] = u.searchParams.get('name') || '';
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, attachmentId }) });
    }
    // 发消息（先可选自播帧 ⇒ 回显腿先到形态；再加性返回 messageId）
    if (method === 'POST' && /^\/api\/friends\/[^/]+\/messages$/.test(p)) {
      const uid = decodeURIComponent(p.split('/')[3]);
      const convId = FRIEND_CONV[uid] || 'cA';
      const ids = Array.isArray(body.attachments) ? body.attachments.map(String) : [];
      const names = ids.map((id, i) => SRV.attNames[id] || (SRV.postFrame && SRV.postFrame.names[i]) || `up-${i}`);
      if (SRV.postFrame) {
        const f = { ...SRV.postFrame, conversationId: convId, attachments: ids.map((id, i) => ({ id, name: names[i], size: PNG_4x3.length, state: 'ready' })) };
        delete f.names;
        if (WS) WS.send(JSON.stringify(f));
        await sleep(250); // 给回显腿先到的窗口
      }
      await sleep(SRV.postDelay);
      const messageId = 501;
      SRV.msgs[convId].push({
        id: messageId, senderId: 'me', kind: 'text', body: body.body || '',
        createdAt: iso(EPOCH), attachments: ids.map((id, i) => ({ id, name: names[i], size: PNG_4x3.length, state: 'ready' })),
      });
      const ci = SRV.convs.findIndex(c => c.conversationId === convId);
      if (ci >= 0) SRV.convs[ci].lastMessage = SRV.msgs[convId][SRV.msgs[convId].length - 1];
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messageId, conversationId: convId, createdAt: iso(EPOCH) }) });
    }
    if (p === '/api/neblink/status') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' }, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' }, peers: [] }) });
    }
    if (p === '/api/conversations') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SRV.convs) });
    }
    if (p === '/api/friends') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: SRV.friends, incoming: [], outgoing: [] }) });
    }
    const mm = p.match(/^\/api\/conversations\/([^/]+)\/messages$/);
    if (mm) {
      const cid = decodeURIComponent(mm[1]);
      const after = Math.max(0, Math.floor(Number(u.searchParams.get('after')) || 0));
      const limit = Math.min(200, Math.max(1, Math.floor(Number(u.searchParams.get('limit')) || 50)));
      const rows = (SRV.msgs[cid] || []).filter(x => Number(x.id) > after).slice(0, limit);
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rows) });
    }
    if (/^\/api\/conversations\/[^/]+\/read$/.test(p)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    WS = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });

  // 页内采样器（**读的是真 DOM 的每帧形态**）：逐 rAF 记录发送气泡与整流 DOM 快照。
  await page.addInitScript(() => {
    const S = { placeholder: 0, frame: 0, confirm: 0, bubble: 0, srcs: [], dup: [], dupMax: 0, ids: [], clickAt: 0, samples: 0 };
    window.__IM = S;
    const frameInfo = (m) => {
      const box = m.querySelector('.fm-att-inline');
      const img = m.querySelector('.fm-att-inline img');
      return {
        boxH: box ? +box.getBoundingClientRect().height.toFixed(2) : null,
        hasSrc: !!(img && img.getAttribute('src')),
        src: img ? (img.dataset.attSrc || '') : '',
      };
    };
    const tick = () => {
      S.samples++;
      const msgs = [...document.querySelectorAll('.fm-flow .fm-msg')];
      // ① 整流逐帧快照（重复面判据：**任一帧**里同 id 出现 ≥2 次即失败）
      const byId = {};
      for (const m of msgs) {
        const id = m.dataset.messageId;
        byId[id] = (byId[id] || 0) + 1;
      }
      const sig = msgs.map(m => m.dataset.messageId).join(',');
      if (sig !== S.ids.join(',')) {
        S.ids = msgs.map(m => m.dataset.messageId);
        S.dup.push({ t: Date.now(), n: msgs.length, dup: Object.entries(byId).filter(([, n]) => n > 1).map(([k, n]) => `${k}x${n}`) });
        for (const n of Object.values(byId)) if (n > S.dupMax) S.dupMax = n;
      }
      // ② 发送侧探针 = 流里**最后一条带附件卡的**气泡（两种模式下同一判据）
      const last = [...msgs].reverse().find(m => m.querySelector('.fm-att'));
      if (last) {
        const f = frameInfo(last);
        if (!S.bubble) S.bubble = Date.now();
        if (f.boxH > 0 && !S.placeholder) S.placeholder = Date.now();
        if (f.hasSrc && !S.frame) { S.frame = Date.now(); }
        if (f.src && S.srcs[S.srcs.length - 1] !== f.src) S.srcs.push(f.src);
        if (last.dataset.sendPhase === 'confirmed' && !S.confirm) S.confirm = Date.now();
      }
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(900);
  return { ctx, page, pageErrors };
}

async function openPanel(page) { await page.click('#messages-btn'); await sleep(600); }
async function openConv(page, cid) { await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(700); }
async function domIds(page) { return page.$$eval('.fm-flow .fm-msg', ws => ws.map(w => w.dataset.messageId)); }
async function setFiles(page, files) { await page.setInputFiles('.fm-modal input[type=file]', files); }
const imgFile = (name, buf) => ({ name, mimeType: 'image/png', buffer: buf });
const txtFile = (name, buf) => ({ name, mimeType: 'text/plain', buffer: buf });

/** 几何探针（滚动无关：量的是「图片气泡 → 下一条」的**相对**间距）。 */
async function geom(page, id) {
  return page.evaluate((mid) => {
    const msgs = [...document.querySelectorAll('.fm-flow .fm-msg')];
    const m = msgs.find(x => String(x.dataset.messageId) === String(mid));
    if (!m) return null;
    const box = m.querySelector('.fm-att-inline');
    const img = box ? box.querySelector('img') : null;
    const idx = msgs.indexOf(m);
    const next = msgs[idx + 1] || null;
    return {
      boxH: box ? +box.getBoundingClientRect().height.toFixed(2) : null,
      imgH: img ? +img.getBoundingClientRect().height.toFixed(2) : null,
      imgW: img ? +img.getBoundingClientRect().width.toFixed(2) : null,
      natural: img ? `${img.naturalWidth}x${img.naturalHeight}` : null,
      src: img ? (img.dataset.attSrc || '') : '',
      gapToNext: next ? +(next.getBoundingClientRect().top - m.getBoundingClientRect().top).toFixed(2) : null,
      cardH: +m.getBoundingClientRect().height.toFixed(2),
    };
  }, id);
}
const waitImgLoaded = (page, id) => page.waitForFunction((mid) => {
  const msgs = [...document.querySelectorAll('.fm-flow .fm-msg')];
  const m = msgs.find(x => String(x.dataset.messageId) === String(mid));
  const img = m && m.querySelector('.fm-att-inline img');
  return !!(img && img.getAttribute('src') && img.complete && img.naturalWidth > 0);
}, id, { timeout: 15000 });

// ══════════════════════════════════════════════════════════════════════
// 场景 A · ③ 预留零位移（**接收侧**几何探针：图片出现在历史中段，下方还有一条消息）
// ══════════════════════════════════════════════════════════════════════
async function scenarioA() {
  console.log(`\n── 场景 A · ③ 预留零位移（改前/改后同一探针）· MODE=${MODE}`);
  for (const [label, convId, mid, w, h] of [
    ['4:3', 'cA', 101, 1600, 1200],
    ['16:9', 'cB', 201, 1600, 900],
  ]) {
    resetServer({ dlDelay: 1200 });
    const release = holdAttachments(); // 附件字节**闸住** ⇒「字节到达前」是确定性采样点
    const { ctx, page, pageErrors } = await bootPage();
    try {
      await openPanel(page);
      await openConv(page, convId);
      // 等图片气泡的直显槽出现（此刻字节被闸住 ⇒ 这一采样点在「字节到达前」）
      // ⚠ 必须用 `attached`：改前树的空槽**零高度**（= 不可见）——「不可见」本身
      //   就是 ③ 的症状读数，故不能等 visible（实测踩到）。
      await page.waitForSelector(`.fm-flow .fm-msg[data-message-id="${mid}"] .fm-att-inline`, { state: 'attached', timeout: 15000 });
      await sleep(120);
      const g1 = await geom(page, mid);
      release();
      await waitImgLoaded(page, mid);
      await sleep(150);
      const g2 = await geom(page, mid);
      const delta = (g1 && g1.gapToNext !== null && g2 && g2.gapToNext !== null)
        ? +(g2.gapToNext - g1.gapToNext).toFixed(2) : null;
      const expectBefore = Math.round(260 * h / w * 100) / 100;
      put(`geom.recv.${label}`, { pre: g1, post: g2, deltaGap: delta, expectBeforeDelta: expectBefore, pageErrors });
      ok(`A/③ ${label} 字节到达前 = 预留槽已占位（改前 0 / 改后 200）`,
        AFTER ? (g1.boxH === 200) : (g1.boxH === 0),
        JSON.stringify({ boxH: g1.boxH, imgH: g1.imgH, natural: g1.natural }));
      if (AFTER) {
        ok(`A/③ ${label} 零位移（占位→真帧 Δ=0）`, delta === 0,
          JSON.stringify({ delta, pre: g1.gapToNext, post: g2.gapToNext }));
      } else {
        ok(`A/③ ${label} 症状复现（改前）：下方消息被顶走 ≈${expectBefore}px`,
          delta !== null && Math.abs(delta - expectBefore) <= 3,
          JSON.stringify({ delta, expectBefore }));
      }
      ok(`A/③ ${label} 真图解码（真位图读数）`, g2.natural === `${w}x${h}`,
        JSON.stringify({ natural: g2.natural, boxH: g2.boxH, imgW: g2.imgW, imgH: g2.imgH }));
      if (label === '16:9') put('geom.recv.16x9.finalBoxH', { boxH: g2.boxH, imgH: g2.imgH, imgW: g2.imgW });
      ok('A 无 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      release(); // 兜底释放（异常路径不留悬挂请求）
      await ctx.close();
    }
  }
}

// ══════════════════════════════════════════════════════════════════════
// 场景 B · ① 乐观面先出现 + ② 首帧 + ④ 无百分比 + 零重复面 + 回环消失
// ══════════════════════════════════════════════════════════════════════
async function scenarioB({ postFrame = false, tag = 'B' } = {}) {
  console.log(`\n── 场景 ${tag} · 发送侧（${postFrame ? '回显腿先到' : '响应腿先到'}）· MODE=${MODE}`);
  resetServer({ uploadDelay: 600, postDelay: 600, dlDelay: 400, postFrame: postFrame ? { type: 'friend_event', event: 'message_new_self', conversationId: 'cA', messageId: 501, senderId: 'me', kind: 'text', body: '', origin: 'user', createdAt: iso(EPOCH), createdAtMs: EPOCH * 1000, names: ['harbor-4x3.png'] } : null });
  const { ctx, page, pageErrors } = await bootPage();
  try {
    // 🔴 必须是**闭包**（调用时取快照）：本场景在发送**之前**就定义它们，
    //    数组快照会永远是空的（首版实测踩到）。
    const postRespAt = () => (callsOf(c => c.method === 'POST' && /\/messages$/.test(c.p))[0] || {}).at || 0;
    const postSent = () => callsOf(c => c.method === 'POST' && /\/messages$/.test(c.p));
    await openPanel(page);
    await openConv(page, 'cC'); // 干净会话（无图片历史）
    await page.evaluate(() => { window.__IM.clickAt = Date.now(); });
    const tClick = Date.now();
    await setFiles(page, [imgFile('harbor-4x3.png', PNG_4x3)]);
    // 乐观面上屏的观测窗口（上传延迟 600ms + POST 延迟 600ms）
    await sleep(250);
    const early = await page.evaluate(async () => {
      const m = document.querySelector('.fm-flow .fm-msg[data-send-phase]');
      const bars = document.querySelectorAll('.fm-upload-progress').length;
      const cards = document.querySelectorAll('.fm-upload').length;
      if (!m) return { bars, cards, phase: null };
      m.dataset.probeToken = 'TOK-1'; // 节点身份标记（「原地接管」判据）
      const box = m.querySelector('.fm-att-inline');
      const img = box ? box.querySelector('img') : null;
      const out = {
        phase: m.dataset.sendPhase,
        id: m.dataset.messageId,
        boxH: box ? +box.getBoundingClientRect().height.toFixed(2) : null,
        hasSrc: !!(img && img.getAttribute('src')),
        src: img ? (img.dataset.attSrc || '') : '',
        bars,
        cards,
        frameNatural: img ? `${img.naturalWidth}x${img.naturalHeight}` : null,
        frameBytes: null,
      };
      // ② 本地小图的**真字节读数**（把当前帧的 blob 拉出来量尺寸；不是断言文本）
      if (img && img.getAttribute('src')) {
        try { out.frameBytes = (await (await fetch(img.src)).blob()).size; } catch { /* non-critical */ }
      }
      return out;
    });
    // B2：回显腿先到（帧在响应前 250ms 发出）⇒ 取「响应到达前」的 DOM 快照（确定性时点）
    let claimedBeforeResponse = null;
    if (postFrame) {
      const t0 = Date.now();
      while (!postSent().length && Date.now() - t0 < 8000) await sleep(20);
      await sleep(120); // 帧已发（POST 入账即发帧）、响应未到（+250ms 才 fulfill）
      claimedBeforeResponse = await page.evaluate(() => {
        const m = document.querySelector('.fm-flow .fm-msg[data-probe-token]');
        return m ? { id: m.dataset.messageId, phase: m.dataset.sendPhase || '' } : null;
      });
    }
    const postSentAll = callsOf(c => c.method === 'POST');
    // 等确认面落定
    await page.waitForFunction(() => {
      const m = document.querySelector('.fm-flow .fm-msg');
      return window.__IM.confirm > 0 || (m && !document.querySelector('.fm-msg[data-send-phase="local-pending"]'));
    }, null, { timeout: 20000 }).catch(() => {});
    await sleep(1200); // 让 refreshAfterAttachSend / 清扫链跑完
    const S = await page.evaluate(() => ({ ...window.__IM, clickAt: window.__IM.clickAt }));
    const ids = await domIds(page);
    const final = await page.evaluate(() => {
      const msgs = [...document.querySelectorAll('.fm-flow .fm-msg')];
      const m = msgs.find(x => x.dataset.probeToken || (x.querySelector('.fm-att') && x.dataset.messageId === '501')) || msgs[msgs.length - 1];
      const box = m.querySelector('.fm-att-inline');
      const img = box ? box.querySelector('img') : null;
      return {
        id: m.dataset.messageId,
        token: m.dataset.probeToken || '',
        phase: m.dataset.sendPhase || '',
        src: img ? (img.dataset.attSrc || '') : '',
        boxH: box ? +box.getBoundingClientRect().height.toFixed(2) : null,
        nodeCount: msgs.filter(x => x.dataset.messageId === m.dataset.messageId).length,
        msgCount: msgs.length,
        progressBars: document.querySelectorAll('.fm-upload-progress').length,
        uploadCards: document.querySelectorAll('.fm-upload').length,
      };
    });
    const attachGets = callsOf(c => c.method === 'GET' && /^\/api\/friends\/attachments\//.test(c.p));
    const wirePost = postSent().length ? postSent()[0].body : null;
    const rel = (t) => (t && S.clickAt) ? t - S.clickAt : null;
    put(`send.${tag}`, {
      early, final, claimedBeforeResponse, sourceFileBytes: PNG_4x3.length, sourceFilePixels: '1600x1200',
      timeline: { placeholder: rel(S.placeholder), frame: rel(S.frame), confirm: rel(S.confirm), bubble: rel(S.bubble) },
      postResponseAt: rel(postRespAt()), srcSeq: S.srcs, dupMax: S.dupMax, dupEvents: S.dup, idsFinal: ids,
      attachGets: attachGets.map(c => c.p), wirePost, postSent: postSent().map(c => ({ p: c.p, at: c.at, body: c.body })), postAll: postSentAll.map(c => c.p), pageErrors,
    });

    if (AFTER) {
      ok(`B/① 乐观面先于服务端确认在屏（+250ms 时已在屏且未确认）`,
        !!early && early.phase === 'local-pending' && early.id.startsWith('fm-tmp-'),
        JSON.stringify(early));
      ok('B/① 乐观面上屏时点 < 发消息响应时点', rel(S.bubble) !== null && rel(S.bubble) < rel(postRespAt()),
        JSON.stringify({ bubble: rel(S.bubble), postResponse: rel(postRespAt()) }));
      ok('B/① 乐观面在屏时，机会窗口内**只有一个面**（无重复面）', final.nodeCount === 1 && S.dupMax === 1,
        JSON.stringify({ nodeCount: final.nodeCount, dupMax: S.dupMax, dupEvents: S.dup.filter(e => e.dup.length) }));
      ok('B/① 原地接管：节点身份（probeToken）在确认后仍在同一节点',
        final.token === 'TOK-1' && String(final.id) === '501', JSON.stringify({ id: final.id, token: final.token }));
      ok('B/① 确认面字节 = 本机句柄（零服务端往返）',
        final.src === 'local-handle' && attachGets.length === 0,
        JSON.stringify({ src: final.src, attachGets: attachGets.map(c => c.p) }));
      ok('B/② 首帧 = 本地小图（非服务端字节）', (early && early.src === 'local-thumb') && S.srcs[0] === 'local-thumb',
        JSON.stringify({ earlySrc: early && early.src, srcSeq: S.srcs }));
      ok('B/② 占位出现时点 ≤ 首帧时点（骨架先行）',
        S.placeholder > 0 && S.frame > 0 && S.placeholder <= S.frame + 1,
        JSON.stringify({ placeholder: rel(S.placeholder), frame: rel(S.frame) }));
      ok('B/② 乐观面在屏时占位槽已按 13:10 占住高度', early && early.boxH === 200,
        JSON.stringify({ boxH: early && early.boxH }));
      ok('B/② 首帧 = **本地生成的小图**（真字节读数：像素已降采样且字节远小于原图）',
        !!early && early.frameNatural !== null && early.frameNatural !== '1600x1200'
        && typeof early.frameBytes === 'number' && early.frameBytes > 0 && early.frameBytes < PNG_4x3.length,
        JSON.stringify({ frameNatural: early && early.frameNatural, frameBytes: early && early.frameBytes, sourceBytes: PNG_4x3.length }));
      ok('B/④ 本路径上传期间**零百分比进度条**（反馈 = 乐观直显）', early && early.bars === 0 && final.progressBars === 0,
        JSON.stringify({ barsDuring: early && early.bars, barsFinal: final.progressBars }));
      ok('B wire 面：请求体带 attachments = 本机上传回执 id', !!wirePost && Array.isArray(wirePost.attachments) && wirePost.attachments.length === 1 && /^att-up-/.test(wirePost.attachments[0]),
        JSON.stringify(wirePost));
      if (postFrame) {
        ok('B2/① 回显腿先到：帧到达即**原地认领**（响应前节点已换真 id，仍只有一个面）',
          !!claimedBeforeResponse && String(claimedBeforeResponse.id) === '501' && final.token === 'TOK-1',
          JSON.stringify({ claimedBeforeResponse, token: final.token }));
      }
    } else {
      ok('B/① 症状复现（改前）：气泡要等服务端行回来才上屏（上传在飞期间屏上无气泡）',
        !early || early.phase === null || early.phase === undefined,
        JSON.stringify({ early }));
      ok('B/① 症状复现（改前）：确认后仍把自己刚传的图整件取回来（回环往返 = 1 次）', attachGets.length === 1,
        JSON.stringify({ attachGets: attachGets.map(c => c.p) }));
      ok('B/④ 症状复现（改前）：上传在飞期间有百分比进度条', !!early && early.bars >= 1,
        JSON.stringify({ barsDuring: early && early.bars }));
      ok('B/② 症状复现（改前）：首帧字节 = 服务端（无本地小图）', S.srcs.length === 0 || S.srcs[0] === 'server',
        JSON.stringify({ srcSeq: S.srcs }));
      ok('B/① 症状复现（改前）：气泡上屏时点 ≥ 发消息响应时点',
        S.bubble === null || rel(S.bubble) === null || rel(S.bubble) >= rel(postRespAt()),
        JSON.stringify({ bubble: rel(S.bubble), postResponse: rel(postRespAt()) }));
    }
    ok('B 无 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
  } finally {
    await ctx.close();
  }
}

// ══════════════════════════════════════════════════════════════════════
// 场景 C · 范围闸反例（非图片 / 混批 ⇒ 既有路径逐字不变）
// ══════════════════════════════════════════════════════════════════════
async function scenarioC() {
  console.log(`\n── 场景 C · 范围闸反例（非图片件）· MODE=${MODE}`);
  resetServer({ uploadDelay: 500, postDelay: 500, dlDelay: 300 });
  const { ctx, page, pageErrors } = await bootPage();
  try {
    await openPanel(page);
    await openConv(page, 'cC');
    await setFiles(page, [txtFile('notes.txt', PNG_TXT)]);
    await sleep(300);
    const mid = await page.evaluate(() => {
      const m = document.querySelector('.fm-flow .fm-msg[data-send-phase]');
      return {
        optimistic: !!m,
        bars: document.querySelectorAll('.fm-upload-progress').length,
        cards: document.querySelectorAll('.fm-upload').length,
        inline: document.querySelectorAll('.fm-msg .fm-att-inline').length,
      };
    });
    await sleep(2200);
    const finalInline = await page.evaluate(() => document.querySelectorAll('.fm-msg .fm-att-inline').length);
    put('scope.nonImage', { mid, finalInline, pageErrors });
    ok('C 非图片件 = 零乐观面（逐字走既有路径）', mid.optimistic === false, JSON.stringify(mid));
    ok('C 非图片件 = 上传卡在（既有呈现）+ 零图片直显槽', mid.cards >= 1 && mid.inline === 0 && finalInline === 0,
      JSON.stringify({ cards: mid.cards, inline: mid.inline, finalInline }));
    ok('C 无 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
  } finally {
    await ctx.close();
  }
}

// ══════════════════════════════════════════════════════════════════════
// 场景 D · 接收侧零回归（入站图片 / 入站非图片）+ ⑤ 无过渡读数
// ══════════════════════════════════════════════════════════════════════
async function scenarioD() {
  console.log(`\n── 场景 D · 接收侧零回归 + ⑤ 无过渡 · MODE=${MODE}`);
  resetServer({ dlDelay: 200 });
  const { ctx, page, pageErrors } = await bootPage();
  try {
    await openPanel(page);
    await openConv(page, 'cA');
    // 入站图片（att-in-1）：接收侧**不该**有任何本批语义（无 send-phase / 走服务端字节）
    await waitImgLoaded(page, 101).catch(() => {});
    const recv = await page.evaluate(() => {
      const m = document.querySelector('.fm-flow .fm-msg[data-message-id="101"]');
      const img = m && m.querySelector('.fm-att-inline img');
      return {
        hasLocalFace: !!m && m.classList.contains('out') === false && m.dataset.sendPhase === undefined,
        sendPhase: m ? (m.dataset.sendPhase || '') : null,
        src: img ? (img.dataset.attSrc || '') : '',
        natural: img ? `${img.naturalWidth}x${img.naturalHeight}` : null,
        alt: img ? img.getAttribute('alt') : null,
        dirOut: m ? m.classList.contains('out') : null,
      };
    });
    const gets = callsOf(c => c.method === 'GET' && /^\/api\/friends\/attachments\//.test(c.p));
    // ⑤ 无过渡 + ③ aspect-ratio 读数（在**含直显槽**的会话上取计算样式）
    const trans = await page.evaluate(() => {
      const box = document.querySelector('.fm-msg .fm-att-inline');
      const img = box ? box.querySelector('img') : null;
      const cs = (e) => { const s = getComputedStyle(e); return { transitionProperty: s.transitionProperty, transitionDuration: s.transitionDuration, animationName: s.animationName, aspectRatio: s.aspectRatio }; };
      return { box: box ? cs(box) : null, img: img ? cs(img) : null, msg: cs(document.querySelector('.fm-msg')) };
    });
    // 入站非图片（att-in-2）
    SRV.msgs.cC.push({ id: 302, senderId: 'u-p', kind: 'text', body: '', createdAt: iso(EPOCH - 30), attachments: [{ ...IN_ATT_TXT }] });
    await page.click('.fm-modal-close');
    await sleep(400);
    await openConv(page, 'cC');
    await sleep(900);
    const txtSlot = await page.evaluate(() => document.querySelectorAll('.fm-msg .fm-att-inline').length);
    put('recv', { recv, attachGets: gets.map(c => c.p), txtSlot, trans, pageErrors });
    ok('D 接收侧：入站图片走服务端字节腿（本批零语义）',
      (AFTER ? recv.src === 'server' : recv.src === '') && gets.length === 1
      && recv.natural === '1600x1200' && recv.dirOut === false,
      JSON.stringify({ recv, gets: gets.map(c => c.p) }));
    ok('D 接收侧：无 data-send-phase（未挂发送侧语义）', recv.sendPhase === '' || recv.sendPhase === null,
      JSON.stringify({ sendPhase: recv.sendPhase }));
    ok('D 接收侧：非图片件零直显槽（非图片 ⇒ 零行为变化）', txtSlot === 0, JSON.stringify({ txtSlot }));
    ok('D ⑤ 无过渡（内联槽与图片的计算样式 transition/animation）',
      !!trans.box && trans.box.transitionDuration === '0s' && trans.box.animationName === 'none'
      && !!trans.img && trans.img.transitionDuration === '0s' && trans.img.animationName === 'none'
      && trans.msg.transitionDuration === '0s',
      JSON.stringify(trans));
    ok('D ③ aspect-ratio 读数（改后 13/10 / 改前 auto）',
      !!trans.img && (AFTER ? (trans.img.aspectRatio.replace(/\s/g, '') === '13/10') : (trans.img.aspectRatio === 'auto')),
      JSON.stringify({ aspectRatio: trans.img && trans.img.aspectRatio }));
    ok('D 无 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
  } finally {
    await ctx.close();
  }
}

// ══════════════════════════════════════════════════════════════════════
try {
  console.log(`imgmsg-send spec · MODE=${MODE} · WEB=${WEB}`);
  await scenarioA();
  await scenarioB({ postFrame: false, tag: 'B' });
  if (AFTER) await scenarioB({ postFrame: true, tag: 'B2' }); // 回显腿先到（强键认领）
  await scenarioC();
  await scenarioD();
} catch (e) {
  failures++;
  console.error('HARNESS ERROR:', e && e.stack || e);
} finally {
  try { await browser.close(); } catch { /* non-critical */ }
  try { await new Promise(r => server.close(r)); } catch { /* non-critical */ }
  if (IM_OUT) {
    try { writeFileSync(IM_OUT, JSON.stringify({ mode: MODE, web: WEB, failures, ...R }, null, 2)); }
    catch (e) { console.error('write IM_OUT failed', e && e.message); }
  }
  console.log(`\n=== ${MODE}: failures=${failures} ===`);
  process.exit(failures === 0 ? 0 : 1);
}
