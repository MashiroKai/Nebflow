// imgmsg-send.spec.mjs — 好友聊天图片三症状（发得慢 / 不预览 / 气泡跳动）修复的
// **自包含真渲染**验收（真 Chromium + 真模块图 + 真 CSS + 真几何 + 真字节读数）。
//
// 批：imgmsg-impl 2026-09-18（作者五项全裁：③ 预留 13:10 / ② 首帧纯前端先行 /
// ① A1-full 乐观气泡 + 本机句柄直显 / ④ 无百分比进度条 / ⑤ 无过渡）。
//
// 🔴📌 **③ 的取代关系（2026-09-19 01:29 作者令 · visup-ratio 批，勿按旧口径读本文件）**
//   作者 01:29 令逐字要义：「气泡预览图尺寸**按图片真实宽高比** —— 🔴 禁写死 13:10；
//   微信做法 = 按**原图尺寸**决定预览框宽高比；实现侧自定 min/max 夹取防极端长宽比破版；
//   参数按视觉定并在卡面申报。」
//   ⇒ 2026-09-18 的「固定预留比 13:10 + 同一盒 cover 填满」**已被取代**（记 provisional/
//     archived）。本 spec 中一切 ③ 断言已同批改写到新语义，**禁**再按 13:10 描述行为：
//       · 场景 A/③ ：「占位→真帧 Δ=0」→「骨架期零抖动 + **单次**几何变更 = 预测值 +
//         真帧后零重排」（真实比与 Δ=0 数学互斥：骨架期不能预知真实比 ⇒ 旧「零位移」
//         由「帧后零位移 + 单次变更量 == 预测值」承接，见 css/friends.css 段注）；
//       · 场景 B/③ ：「占位槽按 13:10 占住」→「按**真实比**（原图 1600×1200 ⇒ 4:3）占住」；
//       · 场景 D/③ ：「aspect-ratio 读数 = 13/10」→「= **真实比** 4/3」；
//       · 场景 F（**visup-ratio 批新增**）：三形态（横/竖/方）+ 两个越界样本（超扁条/超长条）
//         × 亮/暗双主题 ⇒ 四列读数（原图 W×H / 原图比 / 计算后盒 WxH / 夹取后目标比）+
//         实测渲染框 + 夹取边界 + 不溢出/不顶走下方消息。
//
// 运行（双向红绿钉 —— 同一 harness，两份被测树）：
//   IM_MODE=after  node tests/imgmsg-send.spec.mjs                       # 改后（本批）
//   IM_MODE=before IM_WEB_ROOT=<纯 main 的 web 树> node tests/imgmsg-send.spec.mjs  # 改前（撤修法）
// 环境变量：
//   IM_WEB_ROOT  被测 web 树（缺省 = 本仓 src/main/resources/web）
//   IM_MODE      `after`（缺省）｜`before`（改前基线：症状必须复现 = 红组）
//   IM_OUT       全部读数落盘为 JSON（证据件）
//   IM_SHOTS     非空 ⇒ 场景 F 逐形态×主题落盘截图到该目录（取证件；缺省不落盘）
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
import { writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.IM_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const MODE = process.env.IM_MODE === 'before' ? 'before' : 'after';
const AFTER = MODE === 'after';
const IM_OUT = process.env.IM_OUT || '';
const IM_SHOTS = process.env.IM_SHOTS || '';
/** 只跑指定场景（逗号分隔；缺省 = 全跑）。用途：③ 的**前红**只需场景 F 对基线树跑一遍。 */
const IM_ONLY = (process.env.IM_ONLY || '').split(',').map(s => s.trim()).filter(Boolean);
const want = (n) => IM_ONLY.length === 0 || IM_ONLY.includes(n);

let failures = 0;
const R = { mode: MODE, web: WEB, readings: {} }; // 全部读数（证据件）
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const put = (k, v) => { R.readings[k] = v; console.log(`READ  ${k} = ${JSON.stringify(v)}`); };
/** CSS `<ratio>` 文案（`"1600 / 900"` 或 `"1.7778"`）→ 数值。
 *  ⚠ Chromium 对 `aspect-ratio` 的**计算值不做约分**（`1600 / 900` 原样保留）
 *    ⇒ 比的对齐一律走数值，token 的逐字对齐另做（`data-att-ar`）。 */
const ratioVal = (s) => {
  const m = String(s || '').match(/^\s*([\d.]+)\s*(?:\/\s*([\d.]+)\s*)?$/);
  if (!m) return NaN;
  const b = m[2] === undefined ? 1 : Number(m[2]);
  return b ? Number(m[1]) / b : NaN;
};
const normRatio = (s) => String(s || '').replace(/\s/g, '');

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
// visup-ratio 批（2026-09-19）：③ 三形态 + **两个越界样本**（夹取边界取证）。
const PNG_TALL = pngBytes(900, 1600);      // 竖图（9:16 ⇒ 比 0.5625，界内）
const PNG_SQ = pngBytes(1200, 1200);       // 方图（1:1，界内）
const PNG_STRIP = pngBytes(4000, 200);     // **超扁条**（20:1 ⇒ 夹取到 2:1）
const PNG_COLUMN = pngBytes(200, 4000);    // **超长条**（1:20 ⇒ 夹取到 1:2）
const PNG_TXT = Buffer.from('hello nebflow\n', 'utf8'); // 非图片件（范围闸反例）

// ── REST 契约镜像（唯一「服务端」）─────────────────────────
const EPOCH = Math.floor(Date.now() / 1000);
const iso = s => new Date(s * 1000).toISOString();
const IN_ATT = { id: 'att-in-1', name: 'photo-4x3.png', size: PNG_4x3.length, mime: 'image/png', state: 'ready' };
const IN_ATT_TXT = { id: 'att-in-2', name: 'notes.txt', size: PNG_TXT.length, mime: 'text/plain', state: 'ready' };

/** visup-ratio 批 · 场景 F 的五个形态样本（③ 三形态 + 两个越界样本）。
 *  `ar` = 夹取后**目标比**的期望 token（界内 = 原图比本身，越界 = 夹取界）；
 *  上限盒 `260 × 200` 与夹取界 `[1:2, 2:1]` = `messages.js` 申报参数（本表是它的镜像判据）。 */
const RATIO_CASES = [
  { tag: 'landscape-16x9', conv: 'cR1', mid: 401, w: 1600, h: 900, attId: 'att-r-16x9', png: PNG_16x9, ar: '16 / 9', clamped: false, boxH: 146.25 },
  { tag: 'portrait-9x16', conv: 'cR2', mid: 411, w: 900, h: 1600, attId: 'att-r-tall', png: PNG_TALL, ar: '9 / 16', clamped: false, boxH: 199.11 },
  { tag: 'square-1x1', conv: 'cR3', mid: 421, w: 1200, h: 1200, attId: 'att-r-sq', png: PNG_SQ, ar: '1 / 1', clamped: false, boxH: 200 },
  { tag: 'strip-20x1-clamped', conv: 'cR4', mid: 431, w: 4000, h: 200, attId: 'att-r-strip', png: PNG_STRIP, ar: '2 / 1', clamped: true, boxH: 130 },
  { tag: 'column-1x20-clamped', conv: 'cR5', mid: 441, w: 200, h: 4000, attId: 'att-r-column', png: PNG_COLUMN, ar: '1 / 2', clamped: true, boxH: 200 },
];
/** attachmentId → 真实字节（**唯一**字节源；越界样本也走真位图，禁 mock 图）。 */
const ATT_BYTES = new Map([
  ['att-in-1', PNG_4x3], ['att-in-2', PNG_TXT], ['att-16x9', PNG_16x9],
  ...RATIO_CASES.map(c => [c.attId, c.png]),
]);

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
  // sendstate 批（2026-09-18）加性面：① 首投失败注入（失败红圈场景）；
  // ② §8.6 幂等回放镜像（同键 ⇒ 回原行、**不新增行**）——两者缺席 ⇒ 逐字现状。
  postFail: false,
  keyed: new Map(),
  replays: 0,
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
  // sendstate 批加性面：每场景复位（缺席 ⇒ 逐字现状）
  SRV.postFail = false;
  SRV.keyed = new Map();
  SRV.replays = 0;
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
  // visup-ratio 批 · 场景 F 的会话（每形态一条图片消息 + **下方一条文本消息**
  // ⇒「不顶走下方消息」可量：图盒永不大于上限盒 ⇒ 下一条的顶边位移 ≤ 0）。
  for (const c of RATIO_CASES) {
    SRV.msgs[c.conv] = [
      { id: c.mid, senderId: 'u-p', kind: 'text', body: '', createdAt: iso(EPOCH - 120), attachments: [{ id: c.attId, name: `shot-${c.tag}.png`, size: c.png.length, mime: 'image/png', state: 'ready' }] },
      { id: c.mid + 1, senderId: 'u-p', kind: 'text', body: '这条必须原样留在下面', createdAt: iso(EPOCH - 119) },
    ];
  }
  const last = (cid) => { const a = SRV.msgs[cid] || []; return a[a.length - 1] || null; };
  SRV.convs = [
    { conversationId: 'cA', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) }, lastMessage: last('cA'), unreadCount: 0 },
    { conversationId: 'cB', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) }, lastMessage: last('cB'), unreadCount: 0 },
    { conversationId: 'cC', friend: { userId: 'u-q', neblinkId: 'other', name: '另一好友', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) }, lastMessage: last('cC'), unreadCount: 0 },
    ...RATIO_CASES.map(c => ({ conversationId: c.conv, friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) }, lastMessage: last(c.conv), unreadCount: 0 })),
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

async function bootPage({ colorScheme = null } = {}) {
  const ctx = await browser.newContext(Object.assign({ viewport: { width: 1280, height: 900 } }, colorScheme ? { colorScheme } : {}));
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
      const bytes = ATT_BYTES.get(id) || PNG_4x3;
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
      const key = (body && body.clientMsgId) || null;
      const ids = Array.isArray(body.attachments) ? body.attachments.map(String) : [];
      const names = ids.map((id, i) => SRV.attNames[id] || (SRV.postFrame && SRV.postFrame.names[i]) || `up-${i}`);
      // 🔴 §8.6 幂等回放（**服务端语义的镜像**，不是放水）：同键 ⇒ 回原行、**不新增行**。
      //    键缺席（老服务端形态）⇒ 逐字走原路径。本分支只在「重发同一动作」时命中。
      if (key && SRV.keyed.has(key)) {
        SRV.replays += 1;
        const row = SRV.keyed.get(key);
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messageId: row.id, conversationId: convId, createdAt: row.createdAt }) });
      }
      // sendstate 批（场景 E）：首投**已落行但响应丢失**（超时/断连的真实形态）⇒ 500。
      //   行与键都已登记 ⇒ 重发（同键）**必须**走上面的回放分支、**不得**再落一条。
      if (SRV.postFail) {
        const row = {
          id: 501, senderId: 'me', kind: 'text', body: body.body || '',
          createdAt: iso(EPOCH), attachments: ids.map((id, i) => ({ id, name: names[i], size: PNG_4x3.length, state: 'ready' })),
        };
        SRV.msgs[convId].push(row);
        if (key) SRV.keyed.set(key, row);
        await sleep(300);
        return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: 'server_error' }) });
      }
      if (SRV.postFrame) {
        const f = { ...SRV.postFrame, conversationId: convId, attachments: ids.map((id, i) => ({ id, name: names[i], size: PNG_4x3.length, state: 'ready' })) };
        delete f.names;
        if (WS) WS.send(JSON.stringify(f));
        await sleep(250); // 给回显腿先到的窗口
      }
      await sleep(SRV.postDelay);
      const messageId = 501;
      const row = {
        id: messageId, senderId: 'me', kind: 'text', body: body.body || '',
        createdAt: iso(EPOCH), attachments: ids.map((id, i) => ({ id, name: names[i], size: PNG_4x3.length, state: 'ready' })),
      };
      SRV.msgs[convId].push(row);
      if (key) SRV.keyed.set(key, row);
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

/** 逐帧几何轨迹（**visup-ratio 批**：③ 新占位语义的机械判据面）。
 *  每个 rAF 记一次槽盒高 + 「是否已点 src」；Node 侧据此判：
 *    · 骨架期（未点 src）盒高**唯一** ⇒ 零抖动；
 *    · 全生命周期盒高**恰好两个取值** ⇒ 「骨架 ↔ 真帧」只发生**一次**几何变更；
 *    · **凡已点 src 的帧，盒高即最终值** ⇒ 图片从不以错误比例被绘制、绘制后零重排。 */
const startFrameTrace = (page, mid) => page.evaluate((id) => {
  const w = /** @type {any} */ (window);
  w.__RT = { all: [], done: false };
  const rec = () => {
    const m = document.querySelector(`.fm-flow .fm-msg[data-message-id="${id}"]`);
    const box = m ? m.querySelector('.fm-att-inline') : null;
    const img = box ? box.querySelector('img') : null;
    if (box) {
      const r = box.getBoundingClientRect();
      w.__RT.all.push({
        h: +r.height.toFixed(2),
        w: +r.width.toFixed(2),
        src: !!(img && img.getAttribute('src')),
      });
    }
    if (!w.__RT.done) requestAnimationFrame(rec);
  };
  requestAnimationFrame(rec);
}, mid);
const stopFrameTrace = (page) => page.evaluate(() => {
  const w = /** @type {any} */ (window);
  w.__RT.done = true;
  const all = w.__RT.all;
  const distinct = [...new Set(all.map(f => f.h))];
  return {
    n: all.length,
    distinctH: distinct,
    preH: [...new Set(all.filter(f => !f.src).map(f => f.h))],
    postH: [...new Set(all.filter(f => f.src).map(f => f.h))],
    firstSrcIdx: all.findIndex(f => f.src),
    last: all[all.length - 1] || null,
  };
});

// ══════════════════════════════════════════════════════════════════════
// 场景 A · ③ 占位语义（**接收侧**几何探针：图片出现在历史中段，下方还有一条消息）
//   改前（无槽）⇒ 下方消息被顶走 w/h×260；
//   2026-09-18 基线（固定 13:10）⇒ Δ=0（下方消息完全不动）；
//   2026-09-19 visup-ratio（真实比 + 夹取）⇒ **新占位语义**：骨架期零抖动，
//     骨架 ↔ 真帧**恰好一次**几何变更且量值 = 预测值，真帧后零重排。
// ══════════════════════════════════════════════════════════════════════
async function scenarioA() {
  console.log(`\n── 场景 A · ③ 占位语义（改前/改后同一探针）· MODE=${MODE}`);
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
      await startFrameTrace(page, mid);
      await sleep(120);
      const g1 = await geom(page, mid);
      await sleep(120);
      const g1b = await geom(page, mid); // 骨架期第二采样（零抖动的对照点）
      release();
      await waitImgLoaded(page, mid);
      await sleep(150);
      const g2 = await geom(page, mid);
      await sleep(200);
      const g3 = await geom(page, mid); // 真帧后第二采样（零重排的对照点）
      const trace = await stopFrameTrace(page);
      const delta = (g1 && g1.gapToNext !== null && g2 && g2.gapToNext !== null)
        ? +(g2.gapToNext - g1.gapToNext).toFixed(2) : null;
      const deltaPost = (g2 && g2.gapToNext !== null && g3 && g3.gapToNext !== null)
        ? +(g3.gapToNext - g2.gapToNext).toFixed(2) : null;
      const expectBefore = Math.round(260 * h / w * 100) / 100;
      // ③ 新语义预测值：真帧盒高 = floor(min(260, 200·r) …) —— 与 `messages.js::inlineBoxFor` 同式。
      const r = w / h;
      const rc = Math.min(2, Math.max(0.5, r));
      const predW = Math.floor(rc >= 260 / 200 ? 260 : 200 * rc);
      const predH = Math.round((predW / rc) * 100) / 100;
      const predDelta = +(predH - 200).toFixed(2); // 骨架盒（上限盒）高 200 ⇒ 变更量
      put(`geom.recv.${label}`, {
        pre: g1, pre2: g1b, post: g2, post2: g3, deltaGap: delta, deltaPost, trace,
        expectBeforeDelta: expectBefore, predict: { r: Math.round(rc * 1e4) / 1e4, w: predW, h: predH, delta: predDelta }, pageErrors,
      });
      ok(`A/③ ${label} 字节到达前 = 预留槽已占位（改前 0 / 改后 200）`,
        AFTER ? (g1.boxH === 200) : (g1.boxH === 0),
        JSON.stringify({ boxH: g1.boxH, imgH: g1.imgH, natural: g1.natural }));
      if (AFTER) {
        // ── ③ 新占位语义三条（取代旧「占位→真帧 Δ=0」；真实比与 Δ=0 数学互斥）──
        ok(`A/③ ${label} 骨架期零抖动（未点 src 期间盒高唯一）`,
          trace.preH.length === 1 && trace.preH[0] === 200,
          JSON.stringify({ preH: trace.preH, deltaInSkeleton: g1b && g1 && +(g1b.boxH - g1.boxH).toFixed(2) }));
        ok(`A/③ ${label} 单次几何变更 = 预测值（骨架 ${200} ⇒ 真帧 ${predH}，Δ=${predDelta}）`,
          delta !== null && Math.abs(delta - predDelta) <= 0.6 && trace.distinctH.length === 2,
          JSON.stringify({ delta, predDelta, distinctH: trace.distinctH, predict: { w: predW, h: predH } }));
        ok(`A/③ ${label} 真帧后零重排（凡已点 src 的帧盒高即最终值 + 帧后 Δ=0）`,
          trace.postH.length === 1 && trace.postH[0] === predH && deltaPost === 0,
          JSON.stringify({ postH: trace.postH, predH, deltaPost, n: trace.n }));
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
    // ③ 首帧读数（**确定性采样点**：帧一落地即取，不靠 +250ms 竞速——「机会窗口」面
    //    （进度条/条幅/环）仍固定 +250ms 采样；本探针先起跑、后取值，**不推迟**那次采样）。
    const firstFrameP = page.waitForFunction(() => {
      const m = document.querySelector('.fm-flow .fm-msg[data-send-phase]')
        || document.querySelector('.fm-flow .fm-msg');
      const img = m ? m.querySelector('.fm-att-inline img') : null;
      if (!img || !img.getAttribute('src')) return null;
      const r = img.getBoundingClientRect();
      return {
        src: img.dataset.attSrc || '',
        url: img.src,   // 帧字节读数在**同一 URL** 上后置取（确定性采样点不变）
        boxH: +r.height.toFixed(2), boxW: +r.width.toFixed(2),
        boxAr: getComputedStyle(img).aspectRatio,
        attNat: img.dataset.attNat || '', attAr: img.dataset.attAr || '', attBox: img.dataset.attBox || '',
        frameNatural: img.naturalWidth ? `${img.naturalWidth}x${img.naturalHeight}` : null,
      };
    }, null, { timeout: 10000 }).catch(() => null);
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
      // ── sendstate 批读数面（发送态载面 = 气泡左侧环 / 旧条幅退场）──────────────
      const sending = document.querySelector('.fm-flow .fm-msg.fm-sending');
      const ring = sending ? getComputedStyle(sending, '::before') : null;
      const list = document.querySelector('.fm-flow .fm-upload-list');
      const out = {
        phase: m.dataset.sendPhase,
        id: m.dataset.messageId,
        boxH: box ? +box.getBoundingClientRect().height.toFixed(2) : null,
        hasSrc: !!(img && img.getAttribute('src')),
        src: img ? (img.dataset.attSrc || '') : '',
        // ③ 读数面（visup-ratio 批）：原图内禀尺寸 / 夹取后比率 token / 计算盒 / 计算样式比。
        attNat: img ? (img.dataset.attNat || '') : '',
        attAr: img ? (img.dataset.attAr || '') : '',
        attBox: img ? (img.dataset.attBox || '') : '',
        boxAr: img ? getComputedStyle(img).aspectRatio : '',
        bars,
        cards,
        frameNatural: img ? `${img.naturalWidth}x${img.naturalHeight}` : null,
        frameBytes: null,
        // 旧「正在发送」条幅的机械判据：① 文本宿主普查 ② 卡数 ③ 卡容器矩形
        bannerTextHosts: [...document.querySelectorAll('#fm-chat-overlay *')]
          .filter(e => e.children.length === 0 && (e.textContent || '').trim() === '正在发送').length,
        cardRects: [...document.querySelectorAll('.fm-flow .fm-upload')].map(c => {
          const r = c.getBoundingClientRect();
          return { w: +r.width.toFixed(1), h: +r.height.toFixed(1), display: getComputedStyle(c).display };
        }),
        listHidden: list ? list.hidden : null,
        // 新发送态载面（气泡左侧环）的计算样式读数
        sendingCount: document.querySelectorAll('.fm-flow .fm-msg.fm-sending').length,
        ring: ring ? {
          content: ring.content, animationName: ring.animationName, animationDuration: ring.animationDuration,
          position: ring.position, left: ring.left, top: ring.top, width: ring.width, height: ring.height,
          borderTopColor: ring.borderTopColor, borderRadius: ring.borderRadius,
        } : null,
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
    // ③ 首帧读数取值（探针在 `setFiles` 后即已起跑 ⇒ 值 = **帧落地那一刻**的形态）。
    const firstFrame = firstFrameP ? await firstFrameP.then(h => h.jsonValue()).catch(() => null) : null;
    // 帧字节体积：在同一枚 URL 上后置取（本机帧 ↔ 原图 的字节对比读数）。
    if (firstFrame && firstFrame.url) {
      firstFrame.frameBytes = await page.evaluate(async (u) => {
        try { return (await (await fetch(u)).blob()).size; } catch { return null; }
      }, firstFrame.url).catch(() => null);
    }
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
        // ③ 读数面（visup-ratio 批）：确认面接管后盒比**必须**与首帧一致（原地换键不改几何）。
        attAr: img ? (img.dataset.attAr || '') : '',
        attBox: img ? (img.dataset.attBox || '') : '',
        boxAr: img ? getComputedStyle(img).aspectRatio : '',
        nodeCount: msgs.filter(x => x.dataset.messageId === m.dataset.messageId).length,
        msgCount: msgs.length,
        progressBars: document.querySelectorAll('.fm-upload-progress').length,
        uploadCards: document.querySelectorAll('.fm-upload').length,
        // ── sendstate 批：成功态**无痕**的两条机械判据（spinner 载面 + 旧条幅）────
        sendingCount: document.querySelectorAll('.fm-flow .fm-msg.fm-sending').length,
        bannerTextHosts: [...document.querySelectorAll('#fm-chat-overlay *')]
          .filter(e => e.children.length === 0 && (e.textContent || '').trim() === '正在发送').length,
        listHidden: (() => { const l = document.querySelector('.fm-flow .fm-upload-list'); return l ? l.hidden : null; })(),
      };
    });
    const attachGets = callsOf(c => c.method === 'GET' && /^\/api\/friends\/attachments\//.test(c.p));
    const wirePost = postSent().length ? postSent()[0].body : null;
    const rel = (t) => (t && S.clickAt) ? t - S.clickAt : null;
    put(`send.${tag}`, {
      early, firstFrame, final, claimedBeforeResponse, sourceFileBytes: PNG_4x3.length, sourceFilePixels: '1600x1200',
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
      ok('B/② 首帧 = 本地小图（非服务端字节）', (!!firstFrame && firstFrame.src === 'local-thumb') && S.srcs[0] === 'local-thumb',
        JSON.stringify({ firstFrameSrc: firstFrame && firstFrame.src, srcSeq: S.srcs, earlySrc: early && early.src }));
      ok('B/② 占位出现时点 ≤ 首帧时点（骨架先行）',
        S.placeholder > 0 && S.frame > 0 && S.placeholder <= S.frame + 1,
        JSON.stringify({ placeholder: rel(S.placeholder), frame: rel(S.frame) }));
      ok('B/② 首帧落地时占位槽已按**真实比**占住高度（③ 2026-09-19 令：原图 1600×1200 ⇒ 4:3 ⇒ 盒 260×195）',
        !!firstFrame && firstFrame.boxH === 195 && firstFrame.attBox === '260x195' && firstFrame.attAr === '1600 / 1200'
        && Math.abs(ratioVal(firstFrame.boxAr) - 4 / 3) <= 1e-4,
        JSON.stringify({ boxH: firstFrame && firstFrame.boxH, boxW: firstFrame && firstFrame.boxW, attBox: firstFrame && firstFrame.attBox, attAr: firstFrame && firstFrame.attAr, boxAr: firstFrame && firstFrame.boxAr, attNat: firstFrame && firstFrame.attNat }));
      ok('B/② 首帧 = **本地生成的小图**（真字节读数：像素已降采样且字节远小于原图）',
        !!firstFrame && firstFrame.frameNatural !== null && firstFrame.frameNatural !== '1600x1200'
        && typeof firstFrame.frameBytes === 'number' && firstFrame.frameBytes > 0 && firstFrame.frameBytes < PNG_4x3.length,
        JSON.stringify({ frameNatural: firstFrame && firstFrame.frameNatural, frameBytes: firstFrame && firstFrame.frameBytes, sourceBytes: PNG_4x3.length, earlyNatural: early && early.frameNatural }));
      // ③ 确认面**原地接管**不得改变几何（字节源换成本机句柄 = 同一份图、同一比）。
      ok('B/③ 确认面接管后盒比/盒尺寸与首帧一致（原地换键不改几何）',
        !!final && final.boxH === 195 && final.attBox === '260x195'
        && Math.abs(ratioVal(final.boxAr) - 4 / 3) <= 1e-4,
        JSON.stringify({ boxH: final.boxH, attBox: final.attBox, boxAr: final.boxAr, attAr: final.attAr }));
      ok('B/④ 本路径上传期间**零百分比进度条**（反馈 = 乐观直显）', early && early.bars === 0 && final.progressBars === 0,
        JSON.stringify({ barsDuring: early && early.bars, barsFinal: final.progressBars }));
      // ── sendstate 批（2026-09-18 · 作者设计令）新增契约：发送态载面 = 气泡左侧环，
      //    旧「正在发送」条幅**整条退场**；成功态**无痕**（`.fm-sending` 摘除）。────────
      put(`banner.${tag}`, { early: { textHosts: early && early.bannerTextHosts, cards: early && early.cards, cardRects: early && early.cardRects, listHidden: early && early.listHidden, sendingCount: early && early.sendingCount, ring: early && early.ring }, final: { textHosts: final.bannerTextHosts, uploadCards: final.uploadCards, sendingCount: final.sendingCount, listHidden: final.listHidden } });
      if (AFTER) {
        ok('B/条幅 发送中：旧「正在发送」条幅**不存在**（文本宿主 0 + 卡 0 + 容器隐藏）',
          !!early && early.bannerTextHosts === 0 && early.cards === 0 && early.listHidden !== false,
          JSON.stringify({ textHosts: early && early.bannerTextHosts, cards: early && early.cards, listHidden: early && early.listHidden, cardRects: early && early.cardRects }));
        ok('B/环 发送中：气泡左侧 14px 环在转（2px 描边 + sapphire 弧顶 + 0.8s/圈 + 绝对定位 -22px/11px）',
          !!early && !!early.ring && early.ring.animationName === 'fm-send-spin'
          && early.ring.animationDuration === '0.8s' && early.ring.position === 'absolute'
          && early.ring.left === '-22px' && early.ring.top === '11px'
          && early.ring.width === '14px' && early.ring.height === '14px'
          && early.ring.borderTopColor === 'rgb(91, 127, 191)' && early.ring.borderRadius === '50%',
          JSON.stringify(early && early.ring));
        ok('B/环 发送中：时间戳脉冲**已退位**（一次只留一个载面）',
          !!early && !!early.ring && early.ring.content === '""',
          JSON.stringify({ ringContent: early && early.ring && early.ring.content }));
        ok('B/无痕 成功：确认面接管后 `.fm-sending` **已摘**（无残留环）+ 无条幅残条',
          final.sendingCount === 0 && final.bannerTextHosts === 0 && final.uploadCards === 0,
          JSON.stringify({ sendingCount: final.sendingCount, textHosts: final.bannerTextHosts, uploadCards: final.uploadCards }));
      } else {
        ok('B/条幅 症状复现（改前）：发送中条幅在（文本宿主 1 + 上传卡 1 + 容器可见）',
          !!early && early.bannerTextHosts === 1 && early.cards === 1 && early.listHidden === false,
          JSON.stringify({ textHosts: early && early.bannerTextHosts, cards: early && early.cards, listHidden: early && early.listHidden, cardRects: early && early.cardRects }));
        ok('B/环 症状复现（改前）：无气泡左侧环（`::before` 无内容）',
          !!early && !!early.ring && early.ring.content === 'none',
          JSON.stringify(early && early.ring));
        ok('B/无痕 缺陷复现（改前）：确认后 `.fm-sending` **仍在**（状态载面不收口）',
          final.sendingCount >= 1,
          JSON.stringify({ sendingCount: final.sendingCount }));
      }
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
    ok('D ③ aspect-ratio 读数（**visup-ratio 批**：改后 = 真实比 4/3；改前基线 auto）',
      !!trans.img && (AFTER ? Math.abs(ratioVal(trans.img.aspectRatio) - 4 / 3) <= 1e-4 : (trans.img.aspectRatio === 'auto')),
      JSON.stringify({ aspectRatio: trans.img && trans.img.aspectRatio }));
    ok('D 无 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
  } finally {
    await ctx.close();
  }
}

// ══════════════════════════════════════════════════════════════════════
// 场景 E · sendstate 批：失败标识**气泡左侧** + **真指针点击**同键重发（不多落一条）
// ══════════════════════════════════════════════════════════════════════
async function scenarioE() {
  console.log(`\n── 场景 E · 失败红圈左移 + 点击同键重发 · MODE=${MODE}`);
  resetServer({ uploadDelay: 400, postDelay: 400, dlDelay: 300 });
  SRV.postFail = true; // 首投必失败（失败面的确定性起点）
  const { ctx, page, pageErrors } = await bootPage();
  try {
    await openPanel(page);
    await openConv(page, 'cC');
    await page.fill('.fm-input', '在吗');
    await page.click('.fm-send-btn');
    await page.waitForSelector('.fm-flow .fm-msg.fm-failed .fm-retry', { timeout: 10000 });
    await sleep(300);
    // ① 几何 + 命中 + 字形（**真指针悬停**后读数 = 真实交互态，不是画出来的差别）
    const rr = await page.evaluate(() => {
      const b = document.querySelector('.fm-flow .fm-msg.fm-failed .fm-retry');
      if (!b) return null;
      const r = b.getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
    });
    if (rr) { await page.mouse.move(rr.x, rr.y); await sleep(220); }
    const marker = await page.evaluate(() => {
      const wrap = document.querySelector('.fm-flow .fm-msg.fm-failed');
      const b = wrap ? wrap.querySelector('.fm-retry') : null;
      const bub = wrap ? wrap.querySelector('.fm-msg-bubble') : null;
      if (!b) return null;
      const br = b.getBoundingClientRect();
      const ur = bub.getBoundingClientRect();
      const cs = getComputedStyle(b);
      const hit = document.elementFromPoint(br.x + br.width / 2, br.y + br.height / 2);
      return {
        glyph: b.textContent, tag: b.tagName,
        position: cs.position, left: cs.left, top: cs.top, marginTop: cs.marginTop,
        width: cs.width, height: cs.height, borderColor: cs.borderTopColor, radius: cs.borderRadius,
        background: cs.backgroundColor, cursor: cs.cursor, pointerEvents: cs.pointerEvents,
        rect: { x: +br.x.toFixed(1), y: +br.y.toFixed(1), w: +br.width.toFixed(1), h: +br.height.toFixed(1) },
        bubbleRect: { x: +ur.x.toFixed(1), y: +ur.y.toFixed(1), w: +ur.width.toFixed(1), h: +ur.height.toFixed(1) },
        leftOfBubble: br.x + br.width <= ur.x,
        belowBubble: br.y >= ur.y + ur.height,
        hit: hit ? (hit.className || hit.tagName) : null,
        // 同心判据（与发送态环同槽位）：红圈中心 y − 气泡首行中心 y（气泡 padding-top 8 + 行高 19.5/2）
        centerDeltaY: +((br.y + br.height / 2) - (ur.y + 8 + 19.5 / 2)).toFixed(2),
      };
    });
    // ② 真指针点击 ⇒ 走既有同键重发通道（首投失败注入解除）
    SRV.postFail = false;
    if (rr) { await page.mouse.click(rr.x, rr.y); }
    await page.waitForFunction(() => [...document.querySelectorAll('.fm-flow .fm-msg')]
      .some(m => String(m.dataset.messageId) === '501'), null, { timeout: 15000 }).catch(() => {});
    await sleep(900);
    const after = await page.evaluate(() => {
      const msgs = [...document.querySelectorAll('.fm-flow .fm-msg')];
      const mid = msgs.length ? msgs[msgs.length - 1].dataset.messageId : null;
      return {
        mid,
        nodeCount: msgs.filter(m => String(m.dataset.messageId) === String(mid)).length,
        msgCount: msgs.length,
        retryLeft: document.querySelectorAll('.fm-flow .fm-msg.fm-failed .fm-retry').length,
        failedLeft: document.querySelectorAll('.fm-flow .fm-msg.fm-failed').length,
      };
    });
    const posts = callsOf(c => c.method === 'POST' && /\/messages$/.test(c.p));
    const rows = (SRV.msgs.cC || []).filter(r => r.body === '在吗');
    const S = await page.evaluate(() => ({ dupMax: window.__IM.dupMax, dupEvents: window.__IM.dup.filter(e => e.dup.length) }));
    put('fail.marker', { marker: marker, after: after, posts: posts.map(c => c.body && c.body.clientMsgId), replays: SRV.replays, rowsForAction: rows.map(r => r.id), dupMax: S.dupMax, dupEvents: S.dupEvents, pageErrors });
    if (AFTER) {
      ok('E/B② 失败标识在**气泡左侧**（绝对定位 -26px/9px，18px 红圈，与气泡首行同心）',
        !!marker && marker.position === 'absolute' && marker.left === '-26px' && marker.top === '9px'
        && marker.width === '18px' && marker.height === '18px' && marker.leftOfBubble === true && !marker.belowBubble
        && Math.abs(marker.centerDeltaY) <= 1,
        JSON.stringify(marker));
      ok('E/B② 字形 = 既有红圈「!」（**非 emoji**）+ `--color-error` 描边',
        !!marker && marker.glyph === '!' && marker.tag === 'BUTTON' && marker.borderColor === 'rgb(244, 67, 54)' && marker.radius === '50%',
        JSON.stringify(marker && { glyph: marker.glyph, borderColor: marker.borderColor, radius: marker.radius }));
      ok('E/B② 可点：`elementFromPoint` 命中按钮本体 + hover 出既有 `--color-frame-hover` 底',
        !!marker && /fm-retry/.test(String(marker.hit)) && marker.pointerEvents === 'auto' && marker.cursor === 'pointer'
        && marker.background === 'rgb(229, 230, 233)',
        JSON.stringify(marker && { hit: marker.hit, cursor: marker.cursor, background: marker.background }));
    } else {
      ok('E 症状复现（改前）：失败标识在气泡**下方**（流内 static，非左侧）',
        !!marker && marker.position === 'static' && marker.belowBubble === true && marker.leftOfBubble === false,
        JSON.stringify(marker));
      ok('E 症状复现（改前）：hover 无背景反馈（既有 `.fm-retry` 无 hover 规则）',
        !!marker && marker.background === 'rgba(0, 0, 0, 0)',
        JSON.stringify({ background: marker && marker.background }));
    }
    // ③ 重发幂等（两模式共同不变量）：同键 ⇒ 服务端回放 ⇒ **不多落一条**
    ok('E/B② 点击重发走既有通道：2 次 POST **同一** `clientMsgId`（键复用）',
      posts.length === 2 && posts[0].body.clientMsgId && posts[0].body.clientMsgId === posts[1].body.clientMsgId,
      JSON.stringify(posts.map(c => c.body.clientMsgId)));
    ok('E/B② 不多落一条：本动作落行 = 1（服务端按同键回放，replays ≥ 1）+ 气泡节点 = 1',
      rows.length === 1 && SRV.replays >= 1 && after.nodeCount === 1 && S.dupMax === 1,
      JSON.stringify({ rows: rows.map(r => r.id), replays: SRV.replays, nodeCount: after.nodeCount, dupMax: S.dupMax, dupEvents: S.dupEvents }));
    ok('E 点击后失败面收口（红圈与失败气泡一并退场）', after.retryLeft === 0 && after.failedLeft === 0,
      JSON.stringify(after));
    ok('E 无 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
  } finally {
    await ctx.close();
  }
}

// ══════════════════════════════════════════════════════════════════════
// 场景 F · ③ **真实宽高比 + min/max 夹取**（visup-ratio 批 · 作者 2026-09-19 01:29 令）
//
//   三形态（横 16:9 / 竖 9:16 / 方 1:1）+ **两个越界样本**（超扁条 20:1 / 超长条 1:20），
//   亮 / 暗**双主题**逐形态同拍。逐形态给四列读数：
//     原图 W×H · 原图比 · 计算后盒 WxH（JS 单点写入的 `data-att-box`）· 夹取后目标比，
//   外加**实测渲染框**（`getBoundingClientRect`，与声明面交叉验证）。
//   边界判据：盒 ≤ 上限盒 260×200（不溢出容器、不顶走下方消息）；越界样本必须被夹到
//   界内（`2 / 1` / `1 / 2`）且**不破版**。
//   改前（`IM_MODE=before`）= 症状组：盒比恒为固定 13:10，与图无关。
//   ⚠ **已知伴随读数（本批显式申报，非静默）**：`.fm-msg` 是 flex 列里的 `fit-content`
//     项，附件卡 max-content ≈ 「文件名行 + 图片盒宽 + 18px」且被上限盒档 384.27px 截顶
//     ⇒ 盒宽 < ~150px 时气泡比改前**变窄**（实测竖图样本 −29.58px）。
//     **不变量**：气泡**永不大于**改前（改前各形态恒 384.27）、卡内/流内零横向溢出、
//     下方消息不被顶走（本场景逐形态断言）。是否把气泡宽钉死留待视觉向（B 段）裁定。
// ══════════════════════════════════════════════════════════════════════
const CEIL_W = 260, CEIL_H = 200, AR_MIN = 0.5, AR_MAX = 2;
async function scenarioF(scheme) {
  console.log(`\n── 场景 F · ③ 真实比 + 夹取（${scheme}）· MODE=${MODE}`);
  if (IM_SHOTS) { try { mkdirSync(IM_SHOTS, { recursive: true }); } catch { /* non-critical */ } }
  for (const c of RATIO_CASES) {
    resetServer({ dlDelay: 200 });
    const { ctx, page, pageErrors } = await bootPage({ colorScheme: scheme });
    try {
      await openPanel(page);
      await openConv(page, c.conv);
      await waitImgLoaded(page, c.mid).catch(() => {});
      await sleep(200);
      const r = await page.evaluate((mid) => {
        const m = document.querySelector(`.fm-flow .fm-msg[data-message-id="${mid}"]`);
        const box = m ? m.querySelector('.fm-att-inline') : null;
        const img = box ? box.querySelector('img') : null;
        const i = img ? img.getBoundingClientRect() : null;
        const b = box ? box.getBoundingClientRect() : null;
        const cm = m ? m.getBoundingClientRect() : null;
        const nx = m && m.nextElementSibling ? m.nextElementSibling.getBoundingClientRect() : null;
        const flow = document.querySelector('.fm-flow');
        return {
          natW: img ? img.naturalWidth : 0, natH: img ? img.naturalHeight : 0,
          // **预览框** = `<img>` 自身（`.fm-att-inline` 是 `flex:1 1 100%` 的满宽容器 ⇒ 量它无意义）。
          boxW: i ? +i.width.toFixed(2) : null, boxH: i ? +i.height.toFixed(2) : null,
          slotW: b ? +b.width.toFixed(2) : null,
          ar: img ? getComputedStyle(img).aspectRatio : '',
          cssWidth: img ? getComputedStyle(img).width : '',
          attNat: img ? (img.dataset.attNat || '') : '',
          attAr: img ? (img.dataset.attAr || '') : '',
          attBox: img ? (img.dataset.attBox || '') : '',
          cardScrollW: m ? m.scrollWidth : null, cardClientW: m ? m.clientWidth : null,
          msgW: cm ? +cm.width.toFixed(2) : null,
          cardW: (() => { const c = m && m.querySelector('.fm-att'); return c ? +c.getBoundingClientRect().width.toFixed(2) : null; })(),
          cardBoxW: (() => { const c = m && m.querySelector('.fm-att'); return c ? +c.scrollWidth.toFixed(2) : null; })(),
          flowScrollW: flow ? flow.scrollWidth : null, flowClientW: flow ? flow.clientWidth : null,
          flowOffsetW: flow ? flow.offsetWidth : null,
          msgOffsetW: m ? m.offsetWidth : null,
          bubbleW: (() => { const b = m && m.querySelector('.fm-bubble, .fm-msg-body'); return b ? +b.getBoundingClientRect().width.toFixed(2) : null; })(),
          bubbleMaxW: (() => { const b = m && m.querySelector('.fm-bubble, .fm-msg-body'); return b ? getComputedStyle(b).maxWidth : ''; })(),
          nameW: (() => { const n = m && m.querySelector('.fm-att-name'); return n ? +n.getBoundingClientRect().width.toFixed(2) : null; })(),
          attMaxW: (() => { const c2 = m && m.querySelector('.fm-att'); return c2 ? getComputedStyle(c2).maxWidth : ''; })(),
          nextTop: nx ? +nx.top.toFixed(2) : null,
          msgBottom: cm ? +cm.bottom.toFixed(2) : null,
        };
      }, c.mid);
      // 预测值（与 `messages.js::inlineBoxFor` **同式**，独立复算 ⇒ 禁「实现自证自己」）。
      const raw = c.w / c.h;
      const apx = Math.min(AR_MAX, Math.max(AR_MIN, raw));
      const predW = Math.floor(apx >= CEIL_W / CEIL_H ? CEIL_W : CEIL_H * apx);
      const predH = Math.round((predW / apx) * 100) / 100;
      const measuredAr = r.boxW && r.boxH ? +(r.boxW / r.boxH).toFixed(4) : null;
      const expectToken = c.clamped ? (apx === AR_MAX ? '2 / 1' : '1 / 2') : `${c.w} / ${c.h}`;
      put(`ratio.${c.tag}.${scheme}`, {
        nat: `${c.w}x${c.h}`, natRatio: +(raw.toFixed(4)), clamp: { min: AR_MIN, max: AR_MAX },
        clamped: c.clamped, target: { ar: c.ar, apx: +(apx.toFixed(4)), token: expectToken },
        calcBox: `${predW}x${predH}`, declared: r.attBox, measured: { w: r.boxW, h: r.boxH, ar: measuredAr },
        cssWidth: r.cssWidth, computedAr: r.ar, attNat: r.attNat, attAr: r.attAr, slotW: r.slotW,
        overflow: { card: (r.cardScrollW || 0) - (r.cardClientW || 0), flow: (r.flowScrollW || 0) - (r.flowClientW || 0) },
        widths: { msg: r.msgW, msgOffset: r.msgOffsetW, card: r.cardW, cardScroll: r.cardBoxW, slot: r.slotW, bubbleW: r.bubbleW, bubbleMaxW: r.bubbleMaxW, nameW: r.nameW, attMaxW: r.attMaxW, flowClient: r.flowClientW, flowOffset: r.flowOffsetW },
        gapToNext: (r.nextTop !== null && r.msgBottom !== null) ? +(r.nextTop - r.msgBottom).toFixed(2) : null,
        pageErrors,
      });
      const norm = (s) => normRatio(s);
      if (AFTER) {
        ok(`F/③ [${scheme}] ${c.tag} 真图解码 = 原图内禀 ${c.w}×${c.h}`,
          r.natW === c.w && r.natH === c.h, JSON.stringify({ nat: `${r.natW}x${r.natH}` }));
        ok(`F/③ [${scheme}] ${c.tag} 盒比 == 原图比夹取后值（声明 ${c.ar} · 实测框 ${r.boxW}×${r.boxH}）`,
          Math.abs(ratioVal(r.ar) - apx) <= 5e-4 && norm(r.attAr) === norm(expectToken)
          && measuredAr !== null && Math.abs(measuredAr - apx) <= 5e-3,
          JSON.stringify({ computedAr: r.ar, attAr: r.attAr, expectToken, measuredAr, target: apx }));
        ok(`F/③ [${scheme}] ${c.tag} 计算盒 == 独立复算 ${predW}x${predH}（宽高均 ≤ 上限盒 260×200）`,
          r.attBox === `${predW}x${predH}` && Math.abs(r.boxW - predW) <= 1 && Math.abs(r.boxH - predH) <= 1
          && r.boxW <= CEIL_W + 0.01 && r.boxH <= CEIL_H + 0.01,
          JSON.stringify({ declared: r.attBox, predict: `${predW}x${predH}`, measured: { w: r.boxW, h: r.boxH }, cssWidth: r.cssWidth }));
        ok(`F/③ [${scheme}] ${c.tag} 不破版（卡内/流内无横向溢出 + 下方消息未被顶走）`,
          ((r.cardScrollW || 0) - (r.cardClientW || 0)) <= 0 && ((r.flowScrollW || 0) - (r.flowClientW || 0)) <= 0
          && r.nextTop !== null && r.msgBottom !== null && r.nextTop >= r.msgBottom - 0.01,
          JSON.stringify({ cardOverflow: (r.cardScrollW || 0) - (r.cardClientW || 0), flowOverflow: (r.flowScrollW || 0) - (r.flowClientW || 0), nextTop: r.nextTop, msgBottom: r.msgBottom }));
        if (c.clamped) {
          ok(`F/③ [${scheme}] ${c.tag} **越界样本被夹到界内**（原图比 ${raw.toFixed(2)} ⇒ ${c.ar}）且不破版`,
            Math.abs(apx - (norm(c.ar) === '2/1' ? AR_MAX : AR_MIN)) <= 1e-9
            && Math.abs(ratioVal(r.ar) - apx) <= 5e-4 && r.boxH <= CEIL_H + 0.01,
            JSON.stringify({ rawRatio: raw, clampedTo: c.ar, attAr: r.attAr, box: r.attBox, boxH: r.boxH }));
        }
      } else {
        // 红组（改前）**双基线**：本批的「改前」= 2026-09-18 imgmsg 基线（固定 13:10 盒，
        // 与图无关）；更早基线（imgmsg 之前）= 槽根本没有比例（盒高 0 / `auto`）。
        const prev13 = norm(r.ar) === '13/10';
        ok(`F/③ [${scheme}] ${c.tag} 症状复现（改前）：盒比与图无关（13:10 固定盒 / 更早基线 = 槽未占位）`,
          prev13 ? ((c.w / c.h) !== (13 / 10) ? r.boxH === 200 : true) : (norm(r.ar) === '' && (r.boxH || 0) === 0),
          JSON.stringify({ computedAr: r.ar, boxH: r.boxH, natRatio: +raw.toFixed(4), baseline: prev13 ? 'imgmsg-13:10' : 'pre-imgmsg' }));
      }
      if (IM_SHOTS) {
        const el = await page.$(`.fm-flow .fm-msg[data-message-id="${c.mid}"]`);
        if (el) await el.screenshot({ path: join(IM_SHOTS, `f-${c.tag}-${scheme}.png`) }).catch(() => {});
      }
      ok(`F [${scheme}] ${c.tag} 无 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }
}

// ══════════════════════════════════════════════════════════════════════
try {
  console.log(`imgmsg-send spec · MODE=${MODE} · WEB=${WEB}`);
  if (want('A')) await scenarioA();
  if (want('B')) await scenarioB({ postFrame: false, tag: 'B' });
  if (AFTER && want('B')) await scenarioB({ postFrame: true, tag: 'B2' }); // 回显腿先到（强键认领）
  if (want('C')) await scenarioC();
  if (want('D')) await scenarioD();
  if (want('E')) await scenarioE();
  if (want('F')) await scenarioF('light');
  if (want('F')) await scenarioF('dark');
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
