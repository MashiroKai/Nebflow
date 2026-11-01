// fm-threeface-shots.mjs — 「fm 三面输入条对齐批」(2026-11-02) 的**真渲染实拍 +
// 几何对照断言** harness。作者批评三点（fmbox 统一 / 叠卡「展开 N」钮位置 / footer
// 同构）都必须以**断言 + 实拍**双重落证，不接受裸看图下结论。
//
// A 三面板 × 双主题实拍（好友 cF / 群 g-1 / 设备 dev:peer-1），每面板每主题四组取景：
//   A0 整窗全景（会话窗全貌，`.fm-chat-overlay` 截图）；
//   A1 输入条三件套全景：.fm-mic-btn + input.cfg-input.fm-input + .fm-attach-btn
//      + .cfg-btn.cfg-btn-primary.fm-send-btn（**设备面语音键 = 本批新补**，缺即 FAIL）；
//   A2 叠卡消息行（3 媒体件）含「展开 N」钮；
//   A3 消息 footer（时间 + 真节点分隔符 + 复制/转发键）。
//   硬断言：三面四键位全在 + 四键逐键 getBoundingClientRect 高度**逐面相等 ±0.5px**。
//   另：A1 帧三面 md5 逐字节相等（像素级同款 = 统一批的最硬证据）。
//
// B 三个三方对照帧（判定 = 断言，图只是旁证）：
//   B1 fm 输入条 vs 主会话 #input-bar（同一页，fm 开窗后另取）：input 盒高/圆角、
//      图标键边长、发送键族类名（computedStyle 读数逐项报数值）+ 发送键可用态取色相等；
//   B2 fm 叠卡「展开 N」钮 vs 主会话（页内 import popArtifacts 的 buildUserMediaRow
//      / buildPopArtifactRow 造对照行，同一页同一帧）：净距 = 32px margin + 16px flex
//      gap = 48px（±1）、pill 中心 ↔ 栈卡视口中心垂直偏差 ≤1px、out/in 侧别镜像一致、
//      fm 侧与主会话侧**逐值相减为零**；并钉死根因面（叠卡组禁挂 .fm-att-media ⇒
//      .pop-stack-area 的 flex-direction 必须是 row）。**验红实证**：页内把
//      `.fm-att-media` 注回叠卡组（只改本页 DOM，不动源码）⇒ flex-direction 必翻
//      column、垂直偏差必 ≫1px、净距必塌 ⇒ 证明上面的断言确有判别力、非恒真。
//   B3 fm footer vs 主会话：**同一 timestamp** 分别构造两侧，断言时间文本逐字相等
//      （today / 昨天 / 本周 / 更早 四档，注入固定时钟 page.clock.setFixedTime）、
//      分隔符 = 真节点 1×10 opacity .2 flex-shrink 0、复制/转发键位序一致。
//
// 🔴 零实例依赖（同 attcard-unify-shots.mjs 纪律）：静态资源 = 内置 http server
//   （随机端口，收尾必关）；REST/WS = page.route 契约镜像，**非生产网关**；
//   🔴 禁把本 harness 读数冒充生产实测。收尾判据 = 零 console error / 零 pageerror。
//
// Run: NODE_PATH=/opt/homebrew/lib/node_modules node tests/fm-threeface-shots.mjs
//   FM_SHOTS=<dir>  落盘目录（缺省 evidence/20261102_fm-threeface-align/shots）

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const MP4 = await readFile(join(ROOT, 'tests', 'fixtures', 'attcard-component-lib', 'tiny.mp4'));
const OUT = process.env.FM_SHOTS
  || join(ROOT, 'evidence', '20261102_fm-threeface-align', 'shots');
mkdirSync(OUT, { recursive: true });

// ── 断言出口 ────────────────────────────────────────────────────────────────
let checks = 0;
let failures = 0;
const ok = (name, cond, extra = '') => {
  checks++;
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = ms => new Promise(r => setTimeout(r, ms));
/** 逐值相等（同一组读数直接相减 ⇒ 差值为零的证据）。 */
const eqNum = (a, b, tol = 0.5) => typeof a === 'number' && typeof b === 'number' && Math.abs(a - b) <= tol;

// ── 固定时钟（footer 四档分桶必须可复现；page.clock.setFixedTime 注入页面）────
const FIXED = new Date(2026, 10, 4, 15, 30, 0).getTime(); // 本地 2026-11-04(周三) 15:30
const OFFSETS = {
  today: -3600 * 1000,            // 14:30（今日）
  yesterday: -24 * 3600 * 1000,   // 昨天 15:30
  week: -48 * 3600 * 1000,        // 11-02(周一) 15:30 ⇒ 本周档
  earlier: -40 * 24 * 3600 * 1000, // 09-25 15:30 ⇒ 日期档
};
const BUCKETS = ['today', 'yesterday', 'week', 'earlier'];
const iso = ms => new Date(ms).toISOString();

// ── 真位图（真解码 / 真内禀比；手写 PNG 编码器 = attcard-unify-shots 同式）──
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
const PNG_43 = pngBytes(1600, 1200);
const PNG_169 = pngBytes(1600, 900);
const PNG_SQ = pngBytes(1200, 1200);
const FILE_BYTES = Buffer.from('%PDF-1.4\nmock bytes — footer/frames visual only\n');
const PREVIEW_URI = 'data:image/png;base64,' + PNG_43.toString('base64');

/** 附件行工厂（wire = AttachmentDto：id/name/size/mime?/state）。 */
const imgAtt = (id, name, png) => ({ id, name, size: png.length, mime: 'image/png', state: 'ready' });
const vidAtt = (id, name) => ({ id, name, size: MP4.length, mime: 'video/mp4', state: 'ready' });
const fileAtt = (id, name, size) => ({ id, name, size, mime: 'application/pdf', state: 'ready' });

// ── REST 契约镜像 ───────────────────────────────────────────────────────────
const FRIEND = { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(FIXED - 86400000) };
const row = (id, senderId, atMs, atts, body) => ({ id, senderId, kind: 'text', body: body || ('m' + id), createdAt: iso(atMs), attachments: atts || [] });
const deviceRow = (id, senderDeviceId, atMs, atts, body) => ({ id, senderDeviceId, kind: 'text', body: body || ('m' + id), createdAtMs: atMs, attachments: atts || [] });

/**
 * 每面板消息组（行号 = 10*p + i，p = 面板号 1/2/3）：
 *   1  入向叠卡（3 图）   → A2 取景 + B2 入向对照件
 *   2  出向叠卡（3 图）   → B2 出向对照件
 *   3..6 footer 四档（today / 昨天 / 本周 / 更早）→ A3 取景 + B3 对照件
 */
const formsFor = (p) => [
  row(10 * p + 1, 'u-p', FIXED + OFFSETS.today, [imgAtt(`att-${p}-s1`, 'stack-a-4x3.png', PNG_43), imgAtt(`att-${p}-s2`, 'stack-b-16x9.png', PNG_169), imgAtt(`att-${p}-s3`, 'stack-c-1x1.png', PNG_SQ)]),
  row(10 * p + 2, 'me', FIXED + OFFSETS.today + 60000, [imgAtt(`att-${p}-o1`, 'out-a-4x3.png', PNG_43), imgAtt(`att-${p}-o2`, 'out-b-16x9.png', PNG_169), imgAtt(`att-${p}-o3`, 'out-c-1x1.png', PNG_SQ)]),
  ...BUCKETS.map((b, i) => row(10 * p + 3 + i, 'u-p', FIXED + OFFSETS[b], [], `footer ${b}`)),
];
const devFormsFor = (p) => [
  deviceRow(10 * p + 1, 'peer-1', FIXED + OFFSETS.today, [imgAtt(`att-${p}-s1`, 'stack-a-4x3.png', PNG_43), imgAtt(`att-${p}-s2`, 'stack-b-16x9.png', PNG_169), imgAtt(`att-${p}-s3`, 'stack-c-1x1.png', PNG_SQ)]),
  deviceRow(10 * p + 2, 'self-1', FIXED + OFFSETS.today + 60000, [imgAtt(`att-${p}-o1`, 'out-a-4x3.png', PNG_43), imgAtt(`att-${p}-o2`, 'out-b-16x9.png', PNG_169), imgAtt(`att-${p}-o3`, 'out-c-1x1.png', PNG_SQ)]),
  ...BUCKETS.map((b, i) => deviceRow(10 * p + 3 + i, 'peer-1', FIXED + OFFSETS[b], [], `footer ${b}`)),
];
const MSGS = { cF: formsFor(1), 'g-1': formsFor(2), 'dev:peer-1': devFormsFor(3) };

const ATT_BYTES = new Map();
for (const p of [1, 2, 3]) {
  ATT_BYTES.set(`att-${p}-s1`, PNG_43); ATT_BYTES.set(`att-${p}-s2`, PNG_169); ATT_BYTES.set(`att-${p}-s3`, PNG_SQ);
  ATT_BYTES.set(`att-${p}-o1`, PNG_43); ATT_BYTES.set(`att-${p}-o2`, PNG_169); ATT_BYTES.set(`att-${p}-o3`, PNG_SQ);
}
const CONVS = [
  { conversationId: 'cF', friend: FRIEND, lastMessage: MSGS.cF[0], unreadCount: 0 },
  { conversationId: 'dev:peer-1', kind: 'device', deviceId: 'peer-1', lastMessage: MSGS['dev:peer-1'][0], unreadCount: 0 },
];
const GROUPS = [{ groupId: 'g-1', title: '项目群', unreadCount: 0, memberCount: 3, role: 'owner', memberAvatars: [], lastMessage: MSGS['g-1'][1] }];
const PANELS = [['friend', 'cF', 1], ['group', 'g-1', 2], ['device', 'dev:peer-1', 3]];

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.mp4': 'video/mp4',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

// ── 内置静态服务 + 页面装配 ─────────────────────────────────────────────────
const browser = await chromium.launch();
const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
    if (p.startsWith('/agents/')) { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return; }
    if (p.startsWith('/api/nf-file')) { res.writeHead(200, { 'Content-Type': 'video/mp4' }); res.end(MP4); return; }
    const file = join(WEB, p === '/' ? 'index.html' : p);
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const frames = [];   // { name, md5, bytes }
const readings = { web: WEB, out: OUT, fixedClock: new Date(FIXED).toISOString(), panels: {}, schemes: {} };

const md5 = buf => createHash('md5').update(buf).digest('hex');

async function bootPage({ colorScheme = 'light', viewport = { width: 1280, height: 900 } } = {}) {
  const ctx = await browser.newContext({ colorScheme, viewport, deviceScaleFactor: 2 });
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
  await page.clock.setFixedTime(new Date(FIXED));

  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const u = new URL(req.url());
    const p = u.pathname;
    const method = req.method();
    if (method === 'GET' && /^\/api\/friends\/attachments\//.test(p)) {
      const id = decodeURIComponent(p.split('/').pop());
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
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: FIXED }], activeId: 'sess-1', folders: [] }));
  });

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(900);
  return { ctx, page, consoleErrors, pageErrors };
}

/** 等叠卡层全部出帧（真解码：naturalWidth > 0）。 */
const waitFlowMedia = (page) => page.waitForFunction(() => {
  const imgs = [...document.querySelectorAll('.fm-flow .pop-stack-view .pop-img')];
  return imgs.length >= 3 && imgs.every(i => i.complete && i.naturalWidth > 0);
}, null, { timeout: 20000 }).catch(() => false);

async function openConv(page, cid) {
  await page.click('#messages-btn'); await sleep(600);
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(900);
  await waitFlowMedia(page); await sleep(350);
}
async function switchConv(page, cid) {
  await page.click('.fm-modal-close').catch(async () => { await page.keyboard.press('Escape'); });
  await sleep(400);
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(900);
  await waitFlowMedia(page); await sleep(350);
}

// ── 实拍（clip = 元素盒 + pad；滚动居中后取viewport内裁剪）──────────────────
async function shotSel(page, selector, name, pad = 14, padTop = 0) {
  const loc = page.locator(selector).first();
  if ((await loc.count()) === 0) { ok(`shot ${name} 目标在场`, false, selector); return null; }
  await loc.evaluate(e => e.scrollIntoView({ block: 'center', inline: 'center' })).catch(() => {});
  await sleep(260);
  const box = await loc.boundingBox();
  if (!box) { ok(`shot ${name} 盒可取`, false, selector); return null; }
  return shotClip(page, { x: box.x, y: box.y - (padTop || 0), width: box.width, height: box.height + (padTop || 0) }, name, pad);
}
async function shotWindow(page, name) {
  const ov = await page.$('#fm-chat-overlay');
  if (!ov) { ok(`shot ${name} 窗口在场`, false); return null; }
  await sleep(220);
  const buf = await ov.screenshot();
  const path = join(OUT, name);
  writeFileSync(path, buf);
  frames.push({ name, md5: md5(buf), bytes: buf.length });
  console.log(`SHOT ${name}  ${buf.length}B · md5=${md5(buf).slice(0, 8)}`);
  return path;
}

async function shotUnion(page, selectors, name, pad = 14) {
  const boxes = [];
  for (const s of selectors) {
    const loc = page.locator(s).first();
    if ((await loc.count()) === 0) continue;
    await loc.evaluate(e => e.scrollIntoView({ block: 'center', inline: 'center' })).catch(() => {});
    const b = await loc.boundingBox();
    if (b) boxes.push(b);
  }
  if (!boxes.length) { ok(`shot ${name} 目标在场`, false, selectors.join(' , ')); return null; }
  const x0 = Math.min(...boxes.map(b => b.x));
  const y0 = Math.min(...boxes.map(b => b.y));
  const x1 = Math.max(...boxes.map(b => b.x + b.width));
  const y1 = Math.max(...boxes.map(b => b.y + b.height));
  return shotClip(page, { x: x0, y: y0, width: x1 - x0, height: y1 - y0 }, name, pad);
}
async function shotClip(page, box, name, pad) {
  await sleep(200);
  const vp = page.viewportSize();
  const x = Math.max(0, Math.floor(box.x - pad));
  const y = Math.max(0, Math.floor(box.y - pad));
  const w = Math.min(vp.width - x, Math.ceil(box.width + pad * 2));
  const h = Math.min(vp.height - y, Math.ceil(box.height + pad * 2));
  if (w < 4 || h < 4) { ok(`shot ${name} 裁剪盒有效`, false, JSON.stringify({ x, y, w, h })); return null; }
  const buf = await page.screenshot({ clip: { x, y, width: w, height: h } });
  const path = join(OUT, name);
  writeFileSync(path, buf);
  frames.push({ name, md5: md5(buf), bytes: buf.length });
  console.log(`SHOT ${name}  ${w}×${h} · ${buf.length}B · md5=${md5(buf).slice(0, 8)}`);
  return path;
}

// ── 页内读数（三面输入条四键位 + 消息行盒）────────────────────────────────
const readComposer = (page) => page.evaluate(() => {
  const ov = document.querySelector('#fm-chat-overlay');
  const bar = ov && ov.querySelector('.fm-input-bar');
  const r = (el) => {
    if (!el) return null;
    const b = el.getBoundingClientRect();
    const c = getComputedStyle(el);
    return {
      x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2),
      radius: c.borderRadius, display: c.display, cls: el.className || '', tag: el.tagName,
      disabled: !!el.disabled,
    };
  };
  const inp = bar && bar.querySelector('input.fm-input');
  const send = bar && bar.querySelector('.fm-send-btn');
  return {
    hasBar: !!bar,
    bar: r(bar),
    mic: r(bar && bar.querySelector('.fm-mic-btn')),
    input: r(inp),
    attach: r(bar && bar.querySelector('.fm-attach-btn')),
    send: r(send),
    order: bar ? [...bar.children].map(c => c.tagName + (c.className ? '.' + c.className.trim().split(/\s+/).join('.') : '')) : [],
    inputCs: inp ? (() => { const c = getComputedStyle(inp); return { radius: c.borderRadius, height: c.height, fontSize: c.fontSize, bg: c.backgroundColor }; })() : null,
    sendFamilyMember: send ? send.matches(':is(#dropbox-send-btn, .cfg-btn.fm-send-btn, button.fm-verify-send, button.fm-send-chip, button.fm-create-submit)') : null,
    micIcon: !!(bar && bar.querySelector('.fm-mic-btn i[data-lucide="mic"], .fm-mic-btn svg')),
    attachIcon: !!(bar && bar.querySelector('.fm-attach-btn i[data-lucide="paperclip"], .fm-attach-btn svg')),
    sttDisabledBody: document.body.classList.contains('stt-disabled'),
  };
});

/** 四档 footer 行读数（fm 侧）：时间文本 + 分隔符真节点几何 + 键位序。 */
const readFooter = (page, ids) => page.evaluate(async (rowIds) => {
  const { formatFooterTime } = await import('/js/timeFormat.js');
  const { fmtTime } = await import('/js/messages/timeFmt.js');
  const { t } = await import('/js/i18n.js');
  const labels = { copy: t('messages.copy'), forward: t('messages.forwardToAgent') };
  const flow = document.querySelector('.fm-flow');
  const out = {};
  for (const [bucket, id] of Object.entries(rowIds)) {
    const r = flow && flow.querySelector(`.fm-msg[data-message-id="${id}"]`);
    if (!r) { out[bucket] = { found: false }; continue; }
    const meta = r.querySelector('.fm-msg-meta');
    const time = r.querySelector('.fm-msg-time');
    const div = r.querySelector('.fm-msg-divider');
    const actions = r.querySelector('.fm-msg-actions');
    const acts = actions ? [...actions.querySelectorAll('.fm-msg-act')] : [];
    const db = div ? div.getBoundingClientRect() : null;
    const dc = div ? getComputedStyle(div) : null;
    const ts = time ? Number(time.getAttribute('data-ts')) : 0;
    const before = meta ? getComputedStyle(meta, '::before') : null;
    out[bucket] = {
      found: true,
      ts,
      text: time ? time.textContent : null,
      expected: ts ? formatFooterTime(ts) : null,
      // 旧形态（列表行薄版 fmtTime）对照读数：证明本批真的换了 formatter，不是同值换汤
      legacyFmtTime: ts ? fmtTime(ts) : null,
      dataTsTextFormat: time ? time.getAttribute('data-ts-text-format') : null,
      dividerPresent: !!div,
      dividerIsElement: !!div && div.nodeType === 1,
      divider: db ? {
        w: +db.width.toFixed(2), h: +db.height.toFixed(2),
        opacity: dc.opacity, flexShrink: dc.flexShrink, display: dc.display,
      } : null,
      metaPseudoBefore: before ? before.content : null,
      metaChildren: meta ? [...meta.children].map(c => (c.className || '').split(/\s+/)[0]) : [],
      actionsChildren: acts.map(a => ({
        cls: a.className,
        label: a.getAttribute('aria-label') || '',
        title: a.title || '',
        icon: (a.querySelector('i[data-lucide]') || {}).getAttribute
          ? a.querySelector('i[data-lucide]').getAttribute('data-lucide')
          : (a.querySelector('svg') ? 'svg' : ''),
      })),
      gap: meta ? getComputedStyle(meta).gap : null,
    };
  }
  return { labels, rows: out };
}, ids);

/** 主会话 footer 对照件（真 builder：chat/durationBadges.createMsgFooterBadge）。 */
const readMainFooter = (page, tsList) => page.evaluate(async (stamps) => {
  const { createMsgFooterBadge } = await import('/js/chat/durationBadges.js');
  const { formatFooterTime } = await import('/js/timeFormat.js');
  document.getElementById('qa-main-footer-host')?.remove();
  const host = document.createElement('div');
  host.id = 'qa-main-footer-host';
  host.style.cssText = 'position:fixed;left:50%;top:32px;transform:translateX(-50%);z-index:99999;display:flex;flex-direction:column;align-items:flex-start;gap:8px;padding:14px 16px;background:var(--color-surface,#fff);border-radius:12px;box-shadow:0 8px 28px rgba(0,0,0,.28);';
  const cap = document.createElement('div');
  cap.textContent = '主会话 createMsgFooterBadge（同 timestamp 对照）';
  cap.style.cssText = 'font:11px ui-monospace,monospace;opacity:.6;margin-bottom:2px;';
  host.appendChild(cap);
  const col = document.createElement('div');
  col.id = 'qa-main-footer-col';
  col.style.cssText = 'display:flex;flex-direction:column;align-items:flex-start;gap:6px;';
  host.appendChild(col);
  document.body.appendChild(host);
  for (const ts of stamps) {
    const badge = createMsgFooterBadge(ts, 'copy text');
    badge.dataset.qaBucket = String(ts);
    col.appendChild(badge);
  }
  await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
  const out = {};
  for (const badge of col.querySelectorAll('.duration-badge')) {
    const ts = Number(badge.dataset.qaBucket);
    const time = badge.querySelector('.duration-badge-time');
    const div = badge.querySelector('.duration-badge-divider');
    const copy = badge.querySelector('.msg-copy');
    const db = div ? div.getBoundingClientRect() : null;
    const dc = div ? getComputedStyle(div) : null;
    out[String(ts)] = {
      text: time ? time.textContent : null,
      expected: formatFooterTime(ts),
      dividerPresent: !!div,
      divider: db ? { w: +db.width.toFixed(2), h: +db.height.toFixed(2), opacity: dc.opacity, flexShrink: dc.flexShrink } : null,
      children: [...badge.children].map(c => (c.className || '').split(/\s+/)[0]),
      copyFirst: !!copy && badge.lastElementChild === copy,
      badgeBox: (() => { const b = badge.getBoundingClientRect(); return { w: +b.width.toFixed(2), h: +b.height.toFixed(2) }; })(),
      gap: getComputedStyle(badge).gap,
    };
  }
  return out;
}, tsList);

/** 主会话输入条读数（#input-bar / #input / #voice-btn / #attach-btn / #send-btn）。 */
const readMainBar = (page) => page.evaluate(() => {
  const r = (el) => {
    if (!el) return null;
    const b = el.getBoundingClientRect();
    const c = getComputedStyle(el);
    return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2), radius: c.borderRadius, display: c.display, cls: el.className || '', tag: el.tagName, bg: c.backgroundColor };
  };
  const bar = document.querySelector('#input-bar');
  const inp = document.querySelector('#input');
  const send = document.querySelector('#send-btn');
  return {
    hasBar: !!bar,
    bar: r(bar),
    input: r(inp),
    inputCs: inp ? (() => { const c = getComputedStyle(inp); return { radius: c.borderRadius, height: c.height, fontSize: c.fontSize, tag: inp.tagName }; })() : null,
    voice: r(document.querySelector('#voice-btn')),
    attach: r(document.querySelector('#attach-btn')),
    attachCs: (() => { const el = document.querySelector('#attach-btn'); if (!el) return null; const c = getComputedStyle(el); return { w: c.width, h: c.height, radius: c.borderRadius }; })(),
    send: send ? { ...r(send), familyMember: send.matches(':is(#dropbox-send-btn, .cfg-btn.fm-send-btn, button.fm-verify-send, button.fm-send-chip, button.fm-create-submit)'), disabled: !!send.disabled } : null,
    barChildren: bar ? [...bar.children].filter(c => c.offsetParent !== null).map(c => c.id || c.className) : [],
  };
});

// ═════════════════════════════════════════════════════════════════════════════
try {
  console.log(`fm-threeface-align shots · WEB=${WEB}`);
  console.log(`OUT=${OUT} · fixedClock=${new Date(FIXED).toString()}`);

  // ══ A：三面板 × 双主题实拍 + 输入条三面能力/几何硬断言 ══════════════════
  for (const scheme of ['light', 'dark']) {
    const { ctx, page, consoleErrors, pageErrors } = await bootPage({ colorScheme: scheme });
    const perPanel = {};
    try {
      for (const [panel, cid, p] of PANELS) {
        if (panel === 'friend') await openConv(page, cid);
        else await switchConv(page, cid);

        // ── A 段读数（四键位 + 序 + 图标）──
        const comp = await readComposer(page);
        perPanel[panel] = comp;

        // A0 整窗全景（会话窗全貌：头 / 流 / 输入条；取景第四组）
        await shotWindow(page, `${panel}-A0-window-${scheme}.png`);
        // A1 输入条三件套全景
        await shotSel(page, '#fm-chat-overlay .fm-input-bar', `${panel}-A1-composer-${scheme}.png`, 16);
        // A2 叠卡消息行（3 件媒体 + 「展开 N」钮）
        const stackProbe = await page.evaluate((rid) => {
          const r = document.querySelector(`.fm-flow .fm-msg[data-message-id="${rid}"]`);
          if (!r) return { found: false };
          const pill = r.querySelector('.fm-att-stack > .pill');
          const view = r.querySelector('.fm-att-stack .pop-stack-view');
          const vb = view ? view.getBoundingClientRect() : null;
          return {
            found: true,
            hasGroup: !!r.querySelector('.fm-att-stack'),
            groupIsArea: !!r.querySelector('.fm-att-stack') && r.querySelector('.fm-att-stack').classList.contains('pop-stack-area'),
            layers: r.querySelectorAll('.fm-att-stack .pop-stack-view .media-layer').length,
            pillText: pill ? pill.textContent.trim() : '',
            viewW: vb ? +vb.width.toFixed(1) : null,
            viewH: vb ? +vb.height.toFixed(1) : null,
          };
        }, String(10 * p + 1));
        await shotSel(page, `.fm-flow .fm-msg[data-message-id="${10 * p + 1}"]`, `${panel}-A2-stack-${scheme}.png`, 16);
        ok(`[${scheme}][${panel}] A2 叠卡行（3 层 + 「展开 3」钮 + 220×280 卡面）`,
          stackProbe.found && stackProbe.hasGroup && stackProbe.layers === 3 && /3/.test(stackProbe.pillText)
            && stackProbe.viewW === 220 && Math.abs(stackProbe.viewH - 280) <= 1,
          JSON.stringify(stackProbe));

        // A3 消息 footer（时间 + 真节点分隔符 + 复制/转发）
        const footRowId = 10 * p + 3; // today 档（纯时钟，最短最稳）
        await shotSel(page, `.fm-flow .fm-msg[data-message-id="${footRowId}"] .fm-msg-meta`, `${panel}-A3-footer-${scheme}.png`, 16);
        const footProbe = await page.evaluate((rid) => {
          const r = document.querySelector(`.fm-flow .fm-msg[data-message-id="${rid}"]`);
          if (!r) return { found: false };
          const meta = r.querySelector('.fm-msg-meta');
          const div = r.querySelector('.fm-msg-divider');
          const db = div ? div.getBoundingClientRect() : null;
          const dc = div ? getComputedStyle(div) : null;
          return {
            found: true,
            time: (r.querySelector('.fm-msg-time') || {}).textContent || '',
            divider: db ? { w: +db.width.toFixed(2), h: +db.height.toFixed(2), opacity: dc.opacity, flexShrink: dc.flexShrink } : null,
            acts: r.querySelectorAll('.fm-msg-actions .fm-msg-act').length,
            pseudo: meta ? getComputedStyle(meta, '::before').content : null,
          };
        }, String(footRowId));
        ok(`[${scheme}][${panel}] A3 footer（时间 + 1×10 真节点分隔符 + 复制/转发 2 键；伪元素已退役）`,
          footProbe.found && !!footProbe.divider
            && footProbe.divider.w === 1 && footProbe.divider.h === 10
            && footProbe.divider.opacity === '0.2' && footProbe.divider.flexShrink === '0'
            && footProbe.acts === 2 && footProbe.pseudo === 'none',
          JSON.stringify(footProbe));
      }

      readings.schemes[scheme] = { composer: perPanel };

      // ── A 段硬断言：三面能力清单 + 四键高逐面相等 ──
      const attachVerdict = await page.evaluate(async () => {
        const m = await import('/js/attachUpload.js');
        // 好友行 = 单聊行（kind 从不是 'friend'，身份由 conv.friend 证明 —— attachUpload 头注）
        return {
          friend: m.attachAvailable({ conversationId: 'cF', friend: { userId: 'u-p' } }),
          group: m.attachAvailable({ conversationId: 'g-1', kind: 'group' }),
          device: m.attachAvailable({ conversationId: 'dev:peer-1', kind: 'device' }),
          friendTarget: m.attachTargetOf({ conversationId: 'cF', friend: { userId: 'u-p' } }),
        };
      });
      readings.schemes[scheme].attachVerdict = attachVerdict;
      console.log(`READ attachAvailable: ${JSON.stringify(attachVerdict)}`);

      for (const panel of ['friend', 'group', 'device']) {
        const c = perPanel[panel];
        const attachExpected = panel === 'device' ? true : attachVerdict[panel] === true;
        ok(`[${scheme}][${panel}] .fm-mic-btn 在场且可见（设备面 = 本批新补语音键）`,
          !!c.mic && c.mic.display !== 'none' && c.mic.h > 0, JSON.stringify(c.mic));
        ok(`[${scheme}][${panel}] input.cfg-input.fm-input 在场`, !!c.input && c.input.tag === 'INPUT' && c.input.h > 0, JSON.stringify(c.input));
        ok(`[${scheme}][${panel}] .fm-attach-btn ${attachExpected ? '在场（canAttach=true）' : '按 canAttach=false 缺席'}`,
          attachExpected ? (!!c.attach && c.attach.h > 0) : !c.attach,
          JSON.stringify({ attach: c.attach, canAttach: attachExpected, attachAvailable: attachVerdict[panel] }));
        ok(`[${scheme}][${panel}] .cfg-btn.cfg-btn-primary.fm-send-btn 在场`, !!c.send && c.send.h > 0 && c.sendFamilyMember === true, JSON.stringify(c.send));
      }
      // 四键高逐面相等（±0.5px）
      for (const key of ['mic', 'input', 'attach', 'send']) {
        const hs = ['friend', 'group', 'device'].map(pn => (perPanel[pn][key] || {}).h);
        const allNum = hs.every(h => typeof h === 'number');
        ok(`[${scheme}] 四键高逐面相等：${key} 高 ${JSON.stringify(hs)}（friendly/g/d，±0.5px）`,
          allNum && eqNum(hs[0], hs[1], 0.5) && eqNum(hs[1], hs[2], 0.5), JSON.stringify(hs));
      }
      // 键位序三面一致（DOM 序 = [mic, input, (attach, hidden file input), send]）
      const orderKey = pn => perPanel[pn].order.filter(t => !t.startsWith('INPUT.')).join(' | ');
      ok(`[${scheme}] 三面键位序（DOM 序）一致`,
        orderKey('friend') === orderKey('group') && orderKey('group') === orderKey('device'),
        JSON.stringify({ friend: orderKey('friend'), group: orderKey('group'), device: orderKey('device') }));
      // 图标字形：麦克风 lucide mic / 纸夹 paperclip 已渲染
      ok(`[${scheme}] 三面麦克风 = lucide mic、纸夹 = lucide paperclip（同族字形）`,
        ['friend', 'group', 'device'].every(pn => perPanel[pn].micIcon && perPanel[pn].attachIcon),
        JSON.stringify(['friend', 'group', 'device'].map(pn => ({ p: pn, mic: perPanel[pn].micIcon, clip: perPanel[pn].attachIcon }))));

      ok(`[${scheme}] 零 console error`, consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok(`[${scheme}] 零 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }

  // ══ B1：fm 输入条 vs 主会话 #input-bar（同一页；fm 开窗后另取）══════════
  for (const scheme of ['light', 'dark']) {
    const { ctx, page, consoleErrors, pageErrors } = await bootPage({ colorScheme: scheme });
    try {
      await openConv(page, 'cF');
      const fmSide = await readComposer(page);
      // 输入条可用态取色：两侧都填字 ⇒ 都处于「可发送绿」
      await page.fill('#fm-chat-overlay .fm-input-bar input.fm-input', 'qa text');
      await page.fill('#input', 'qa text');
      await sleep(320);
      const fmSendOn = await page.evaluate(() => {
        const s = document.querySelector('#fm-chat-overlay .fm-send-btn');
        const c = getComputedStyle(s);
        return { disabled: s.disabled, bg: c.backgroundColor, color: c.color, radius: c.borderRadius, borderColor: c.borderTopColor };
      });
      const main = await readMainBar(page);
      await sleep(150);

      // 实拍 A：fm 输入条（本帧 = B1 fm 侧）
      await shotSel(page, '#fm-chat-overlay .fm-input-bar', `B1-fm-composer-${scheme}.png`, 16);
      // 实拍 B：主会话 #input-bar（关掉遮挡【只为本帧取景】⇒ 截图后恢复）
      await page.evaluate(() => { const ov = document.querySelector('#fm-chat-overlay'); if (ov) ov.style.visibility = 'hidden'; });
      await sleep(250);
      await shotSel(page, '#input-bar', `B1-main-inputbar-${scheme}.png`, 16);
      await page.evaluate(() => { const ov = document.querySelector('#fm-chat-overlay'); if (ov) ov.style.visibility = ''; });
      await sleep(150);

      const b1 = { fm: fmSide, fmSendOn, main };
      readings.schemes[scheme].b1 = b1;

      ok(`[${scheme}][B1] 两侧输入条同页并置且已布局（fm 开窗时另取 #input-bar）`,
        fmSide.hasBar && fmSide.bar.h > 0 && main.hasBar && main.bar.h > 0,
        JSON.stringify({ fmBar: fmSide.bar, mainBar: main.bar }));
      // 图标键边长：fm 麦克风 = `.icon-btn` 裸档 36×36，与主会话 #attach-btn（同 `.icon-btn` 裸档）逐值相等
      const micW = fmSide.mic && fmSide.mic.w; const micH = fmSide.mic && fmSide.mic.h;
      const mainAttachW = main.attach && main.attach.w; const mainAttachH = main.attach && main.attach.h;
      ok(`[${scheme}][B1] 图标键边长：fm .fm-mic-btn ${micW}×${micH} == 主会话 #attach-btn ${mainAttachW}×${mainAttachH}（同 .icon-btn 裸档）`,
        eqNum(micW, mainAttachW, 0.5) && eqNum(micH, mainAttachH, 0.5) && micW === 36,
        JSON.stringify({ mic: fmSide.mic, mainAttach: main.attach }));
      // 纸夹键：fm 走设备面既有 `.dropbox-attach-btn` 32 档（三面共用同一枚键）
      ok(`[${scheme}][B1] 纸夹键：fm .fm-attach-btn ${fmSide.attach && fmSide.attach.w}×${fmSide.attach && fmSide.attach.h} == 设备面 .dropbox-attach-btn 既有 32 档（主会话 #attach-btn = ${mainAttachW}，差异已申报）`,
        !!fmSide.attach && fmSide.attach.w === 32 && fmSide.attach.h === 32,
        JSON.stringify({ fmAttach: fmSide.attach, mainAttach: main.attach }));
      // input 盒：fm = 单行 input.cfg-input（8px 圆角 / 36px 高）；主会话 = 多行 textarea（18px / ~32.3px）
      ok(`[${scheme}][B1] 输入盒读数：fm radius=${fmSide.inputCs && fmSide.inputCs.radius} h=${fmSide.input && fmSide.input.h}（单行 input.cfg-input）· 主会话 radius=${main.inputCs && main.inputCs.radius} h=${main.input && main.input.h}（多行 textarea）—— 形状差异照录，不冒充同值`,
        !!fmSide.inputCs && fmSide.inputCs.radius === '8px' && eqNum(fmSide.input.h, 36, 0.5)
          && !!main.inputCs && main.inputCs.radius === '18px' && main.input.h > 24 && main.input.h < 64
          && main.inputCs.tag === 'TEXTAREA' && fmSide.input.tag === 'INPUT',
        JSON.stringify({ fm: fmSide.inputCs, main: main.inputCs }));
      // 发送键族类名
      ok(`[${scheme}][B1] 发送键族：fm .cfg-btn.cfg-btn-primary.fm-send-btn 命中发送族 :is() = ${fmSide.sendFamilyMember}；主会话 #send-btn 命中 = ${main.send && main.send.familyMember}（主键 = input.css 基座，不属本族列表 —— 差异照录）`,
        fmSide.sendFamilyMember === true && main.send && main.send.familyMember === false,
        JSON.stringify({ fmSend: fmSide.send, mainSend: main.send }));
      // 可用态取色：两侧同色（发送族绿单源）
      ok(`[${scheme}][B1] 发送键可用态取色相等：fm ${fmSendOn.bg} == 主会话 ${main.send && main.send.bg}（绿 = 全站发送族单源）`,
        !!main.send && fmSendOn.bg === main.send.bg && !fmSendOn.disabled && !main.send.disabled,
        JSON.stringify({ fm: fmSendOn, main: main.send }));
      console.log(`READ [${scheme}][B1] ${JSON.stringify({ fmInput: fmSide.inputCs, mainInput: main.inputCs, fmSend: fmSendOn, mainSend: main.send, mic: fmSide.mic, fmAttach: fmSide.attach, mainAttach: main.attach })}`);

      ok(`[${scheme}][B1] 零 console error`, consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok(`[${scheme}][B1] 零 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }

  // ══ B2：fm 叠卡「展开 N」钮 vs 主会话（页内造对照行，同页同帧）══════════
  for (const scheme of ['light', 'dark']) {
    const { ctx, page, consoleErrors, pageErrors } = await bootPage({ colorScheme: scheme });
    try {
      await openConv(page, 'cF');
      const injected = await page.evaluate(async (preview) => {
        const { buildUserMediaRow, buildPopArtifactRow } = await import('/js/popArtifacts.js');
        const flow = document.querySelector('.fm-flow');
        const fmIn = flow.querySelector('.fm-msg[data-message-id="11"]');
        const fmOut = flow.querySelector('.fm-msg[data-message-id="12"]');
        if (!fmIn || !fmOut) return { ok: false, noRows: true };
        const mainIn = buildPopArtifactRow({ items: [
          { kind: 'image', name: 'llm-a-4x3.png', path: '/tmp/llm-a.png', src: preview, ext: 'png' },
          { kind: 'image', name: 'llm-b-16x9.png', path: '/tmp/llm-b.png', src: preview, ext: 'png' },
          { kind: 'image', name: 'llm-c-1x1.png', path: '/tmp/llm-c.png', src: preview, ext: 'png' },
        ], fileRefs: { failed: 0 }, warnings: [] }, { ts: Date.now() });
        const mainOut = buildUserMediaRow([
          { type: 'image', name: 'out-a-4x3.png', path: '', preview, size: 9526 },
          { type: 'image', name: 'out-b-16x9.png', path: '', preview, size: 9526 },
          { type: 'image', name: 'out-c-1x1.png', path: '', preview, size: 9526 },
        ], { ts: Date.now() });
        if (!mainIn || !mainOut) return { ok: false, noBuild: true };
        mainIn.id = 'qa-main-in';
        mainOut.id = 'qa-main-out';
        mainIn.dataset.qaRole = 'main-in';
        mainOut.dataset.qaRole = 'main-out';
        fmIn.before(mainIn);
        fmOut.before(mainOut);
        await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
        return { ok: true, mainInClass: mainIn.className, mainOutClass: mainOut.className };
      }, PREVIEW_URI).catch(e => ({ ok: false, err: String(e).slice(0, 300) }));
      ok(`[${scheme}][B2] 主会话对照行注入成功（入向 Pop 行 + 出向用户媒体行）`,
        !!injected && injected.ok === true, JSON.stringify(injected));
      await sleep(400);

      const b2 = await page.evaluate(() => {
        const rect = el => { const b = el.getBoundingClientRect(); return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2), right: +b.right.toFixed(2) }; };
        const read = (root, label) => {
          if (!root) return { label, missing: true };
          const area = root.querySelector('.pop-stack-area');
          const pill = root.querySelector('.pop-stack-area > .pill');
          const view = root.querySelector('.pop-stack-view');
          if (!area || !pill || !view) return { label, missing: 'child' };
          const ar = rect(area); const pr = rect(pill); const vr = rect(view);
          const acs = getComputedStyle(area); const pcs = getComputedStyle(pill);
          const pillCx = pr.x + pr.w / 2; const viewCx = vr.x + vr.w / 2;
          const side = pillCx < viewCx ? 'left' : 'right';
          const gap = side === 'left' ? +(vr.x - pr.right).toFixed(2) : +(pr.x - vr.right).toFixed(2);
          return {
            label,
            rootClass: root.className,
            area: ar, pill: pr, view: vr,
            side, gap,
            pillCenterY: +(pr.y + pr.h / 2).toFixed(2),
            viewCenterY: +(vr.y + vr.h / 2).toFixed(2),
            vDelta: +Math.abs(pillCx * 0 + (pr.y + pr.h / 2) - (vr.y + vr.h / 2)).toFixed(2),
            areaFlexDirection: acs.flexDirection,
            areaAlignItems: acs.alignItems,
            areaGap: acs.gap,
            pillOrder: pcs.order,
            pillMarginLeft: pcs.marginLeft,
            pillMarginRight: pcs.marginRight,
            hasFmAttMedia: area.classList.contains('fm-att-media'),
            hasFmAttStack: area.classList.contains('fm-att-stack'),
            pillText: pill.textContent.trim(),
          };
        };
        const q = s => document.querySelector(s);
        return {
          fmIn: read(q('.fm-flow .fm-msg[data-message-id="11"]'), 'fm-in'),
          fmOut: read(q('.fm-flow .fm-msg[data-message-id="12"]'), 'fm-out'),
          mainIn: read(q('#qa-main-in'), 'main-in'),
          mainOut: read(q('#qa-main-out'), 'main-out'),
        };
      });
      readings.schemes[scheme].b2 = b2;
      console.log(`READ [${scheme}][B2] ${JSON.stringify(b2)}`);

      // 实拍（同一帧内 fm 叠卡行 + 主会话对照行）
      await shotUnion(page, ['.fm-flow .fm-msg[data-message-id="11"]', '#qa-main-in'], `B2-stack-pill-vs-main-in-${scheme}.png`, 16);
      await shotUnion(page, ['#qa-main-out', '.fm-flow .fm-msg[data-message-id="12"]'], `B2-stack-pill-vs-main-out-${scheme}.png`, 16);

      // 48px 净距（32 margin + 16 gap），四行逐行
      for (const k of ['fmIn', 'fmOut', 'mainIn', 'mainOut']) {
        const s = b2[k];
        ok(`[${scheme}][B2] ${k} 展开钮↔栈卡视口净距 = 32px margin + 16px flex gap = 48px（±1）`,
          !s.missing && Math.abs(s.gap - 48) <= 1,
          JSON.stringify({ gap: s.gap, side: s.side, margin: s.side === 'left' ? s.pillMarginRight : s.pillMarginLeft, areaGap: s.areaGap }));
        ok(`[${scheme}][B2] ${k} 钮中心 ↔ 栈卡视口中心垂直偏差 ≤1px`,
          !s.missing && s.vDelta <= 1, JSON.stringify({ vDelta: s.vDelta, pillCenterY: s.pillCenterY, viewCenterY: s.viewCenterY }));
      }
      // fm 侧与主会话侧逐值相减 = 0
      for (const side of ['In', 'Out']) {
        const fm = b2['fm' + side]; const mn = b2['main' + side];
        ok(`[${scheme}][B2] ${side === 'In' ? '入向' : '出向'}：fm 与主会话逐值相减为零（净距 ${fm.gap}−${mn.gap}=${(fm.gap - mn.gap).toFixed(2)} · 垂直偏差 ${fm.vDelta}−${mn.vDelta}=${(fm.vDelta - mn.vDelta).toFixed(2)} · 卡面 ${fm.view.w}×${fm.view.h} vs ${mn.view.w}×${mn.view.h}）`,
          eqNum(fm.gap, mn.gap, 0.5) && eqNum(fm.vDelta, mn.vDelta, 0.5)
            && eqNum(fm.view.w, mn.view.w, 0.5) && eqNum(fm.view.h, mn.view.h, 0.5)
            && fm.side === mn.side,
          JSON.stringify({ fm, main: mn }));
      }
      // 侧别镜像：出向 = 钮在左（朝会话中轴）、入向 = 钮在右；fm 与主会话同镜像
      ok(`[${scheme}][B2] out/in 侧别镜像一致（出向钮在左 + margin-right 32 · 入向钮在右 + order:3 + margin-left 32），fm 与主会话同镜像`,
        b2.fmOut.side === 'left' && b2.mainOut.side === 'left'
          && b2.fmIn.side === 'right' && b2.mainIn.side === 'right'
          && b2.fmOut.pillMarginRight === '32px' && b2.fmIn.pillMarginLeft === '32px'
          && b2.fmIn.pillOrder === '3' && b2.mainIn.pillOrder === '3',
        JSON.stringify({ fmOut: { side: b2.fmOut.side, mr: b2.fmOut.pillMarginRight }, fmIn: { side: b2.fmIn.side, ml: b2.fmIn.pillMarginLeft, order: b2.fmIn.pillOrder }, mainIn: { side: b2.mainIn.side, order: b2.mainIn.pillOrder }, mainOut: { side: b2.mainOut.side } }));
      // 根因面钉死：叠卡组禁挂 fm-att-media ⇒ .pop-stack-area 仍是 row
      ok(`[${scheme}][B2] 根因面：fm 叠卡组未挂 .fm-att-media（曾经 column 压塌行几何）且 .pop-stack-area flex-direction = row`,
        b2.fmIn.hasFmAttMedia === false && b2.fmOut.hasFmAttMedia === false
          && b2.fmIn.areaFlexDirection === 'row' && b2.fmOut.areaFlexDirection === 'row'
          && b2.fmIn.hasFmAttStack === true,
        JSON.stringify({ fmIn: { cls: b2.fmIn.rootClass, dir: b2.fmIn.areaFlexDirection, fmAttMedia: b2.fmIn.hasFmAttMedia }, fmOut: { cls: b2.fmOut.rootClass, dir: b2.fmOut.areaFlexDirection, fmAttMedia: b2.fmOut.hasFmAttMedia } }));
      // 「展开 N」钮同文
      ok(`[${scheme}][B2] 「展开 N」同文：fm=${b2.fmIn.pillText} / 主会话=${b2.mainIn.pillText}`,
        b2.fmIn.pillText === b2.mainIn.pillText && /3/.test(b2.fmIn.pillText),
        JSON.stringify({ fm: b2.fmIn.pillText, main: b2.mainIn.pillText }));

      // ── 验红实证（mutation · 只改本页 DOM，勿改源）：把旧形态 `.fm-att-media`
      //    临时注回叠卡组 ⇒ flex-direction 必翻成 column、净距/垂直偏差必变 ⇒ 上面的
      //    断言确有判别力（不是恒真断言）。──
      const redB2 = await page.evaluate(() => {
        const area = document.querySelector('.fm-flow .fm-msg[data-message-id="11"] .fm-att-stack');
        if (!area) return { ok: false };
        const read = () => {
          const pill = area.querySelector(':scope > .pill');
          const view = area.querySelector('.pop-stack-view');
          const pr = pill.getBoundingClientRect(); const vr = view.getBoundingClientRect();
          const c = getComputedStyle(area);
          const pcx = pr.x + pr.width / 2; const vcx = vr.x + vr.width / 2;
          return {
            flexDirection: c.flexDirection,
            // 垂直居中判据（= 主断言用的同一个量）
            vDelta: +Math.abs((pr.y + pr.height / 2) - (vr.y + vr.height / 2)).toFixed(2),
            // 水平净距（按钮在左 / 右两侧的统一口径）
            hGap: +(pcx < vcx ? vr.x - pr.right : pr.x - vr.right).toFixed(2),
          };
        };
        const before = read();
        area.classList.add('fm-att-media');           // ← 旧形态（本批已退役的挂类）
        const mutated = read();
        area.classList.remove('fm-att-media');
        const restored = read();
        return { ok: true, before, mutated, restored };
      });
      readings.schemes[scheme].b2red = redB2;
      ok(`[${scheme}][B2] 验红实证：注回 .fm-att-media ⇒ flex-direction 翻 column、垂直居中破（vDelta ${redB2.mutated && redB2.mutated.vDelta}px ≫ 1px）、水平净距塌（${redB2.mutated && redB2.mutated.hGap}）⇒ 上面的 48px / ≤1px 断言确有判别力，非恒真`,
        !!redB2.ok
          && redB2.before.flexDirection === 'row' && Math.abs(redB2.before.hGap - 48) <= 1 && redB2.before.vDelta <= 1
          && redB2.mutated.flexDirection === 'column' && redB2.mutated.vDelta > 1
          && redB2.restored.flexDirection === 'row' && Math.abs(redB2.restored.hGap - 48) <= 1 && redB2.restored.vDelta <= 1,
        JSON.stringify(redB2));

      ok(`[${scheme}][B2] 零 console error`, consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok(`[${scheme}][B2] 零 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }

  // ══ B3：fm footer vs 主会话（同一 timestamp 两侧构造 · 四档）════════════
  for (const scheme of ['light', 'dark']) {
    const { ctx, page, consoleErrors, pageErrors } = await bootPage({ colorScheme: scheme });
    try {
      await openConv(page, 'cF');
      const ids = { today: '13', yesterday: '14', week: '15', earlier: '16' };
      const fmFootRead = await readFooter(page, ids);
      const fmFoot = fmFootRead.rows;
      const labels = fmFootRead.labels;
      const tsList = BUCKETS.map(b => fmFoot[b].ts);
      const mainFoot = await readMainFooter(page, tsList);
      readings.schemes[scheme].b3 = {
        labels,
        fm: fmFoot,
        main: mainFoot,
        buckets: BUCKETS.map((b, i) => ({ bucket: b, ts: tsList[i], fm: fmFoot[b].text, main: mainFoot[String(tsList[i])].text })),
      };
      console.log(`READ [${scheme}][B3] buckets=${JSON.stringify(readings.schemes[scheme].b3.buckets)} labels=${JSON.stringify(labels)}`);

      await shotSel(page, '.fm-flow .fm-msg[data-message-id="13"] .fm-msg-meta', `B3-fm-footer-today-${scheme}.png`, 16);
      await shotSel(page, '.fm-flow .fm-msg[data-message-id="16"] .fm-msg-meta', `B3-fm-footer-earlier-${scheme}.png`, 16);
      await shotSel(page, '#qa-main-footer-host', `B3-main-footer-${scheme}.png`, 10);
      await page.evaluate(() => { document.getElementById('qa-main-footer-host')?.remove(); });

      // 四档齐全（today / 昨天 / 本周 / 更早 —— 注入固定时钟实现跨档）
      ok(`[${scheme}][B3] 四档 footer 分桶齐全（today / 昨天 / 本周 / 更早）`,
        BUCKETS.every(b => fmFoot[b].found && !!fmFoot[b].text)
          && fmFoot.today.text !== fmFoot.yesterday.text
          && fmFoot.yesterday.text !== fmFoot.week.text
          && fmFoot.week.text !== fmFoot.earlier.text,
        JSON.stringify(BUCKETS.map(b => ({ b, text: fmFoot[b].text }))));

      for (const b of BUCKETS) {
        const f = fmFoot[b]; const m = mainFoot[String(f.ts)];
        ok(`[${scheme}][B3] ${b}：fm 时间文本 == formatFooterTime(ts) 输出（逐字）`,
          f.text === f.expected, JSON.stringify({ text: f.text, expected: f.expected, ts: f.ts }));
        ok(`[${scheme}][B3] ${b}：fm ${JSON.stringify(f.text)} == 主会话同一 timestamp ${JSON.stringify(m && m.text)}（逐字相等）`,
          !!m && f.text === m.text && m.text === m.expected, JSON.stringify({ fm: f.text, main: m && m.text }));
        ok(`[${scheme}][B3] ${b}：分隔符 = 真节点 1×10 / opacity .2 / flex-shrink 0（fm 与主会话同值）`,
          !!f.divider && f.divider.w === 1 && f.divider.h === 10 && f.divider.opacity === '0.2' && f.divider.flexShrink === '0'
            && !!m.divider && m.divider.w === 1 && m.divider.h === 10 && m.divider.opacity === '0.2' && m.divider.flexShrink === '0',
          JSON.stringify({ fm: f.divider, main: m && m.divider }));
        ok(`[${scheme}][B3] ${b}：footer 结构同构（time → divider → 动作）且 .fm-msg-actions::before 伪元素已退役`,
          f.metaChildren[0] === 'fm-msg-time' && f.metaChildren[1] === 'fm-msg-divider' && f.metaChildren[2] === 'fm-msg-actions'
            && f.metaPseudoBefore === 'none' && f.dividerIsElement === true
            && Array.isArray(m.children) && m.children[0] === 'duration-badge-time' && m.children[1] === 'duration-badge-divider',
          JSON.stringify({ fmChildren: f.metaChildren, mainChildren: m && m.children, pseudo: f.metaPseudoBefore }));
      }
      // 换 formatter 的实证：旧薄版 fmtTime（列表行口径）在「昨天 / 本周 / 更早」三档
      // 与 formatFooterTime 不同 —— fm footer 现在读的是后者（不是同值换汤）。
      ok(`[${scheme}][B3] formatter 换血实证：旧 fmtTime 输出 ${JSON.stringify(BUCKETS.map(b => fmFoot[b].legacyFmtTime))} ≠ 现 formatFooterTime 输出 ${JSON.stringify(BUCKETS.map(b => fmFoot[b].text))}（昨天/本周/更早三档必须不同；today 同值 = 纯时钟）`,
        fmFoot.today.legacyFmtTime === fmFoot.today.text
          && fmFoot.yesterday.legacyFmtTime !== fmFoot.yesterday.text
          && fmFoot.week.legacyFmtTime !== fmFoot.week.text
          && fmFoot.earlier.legacyFmtTime !== fmFoot.earlier.text,
        JSON.stringify(BUCKETS.map(b => ({ b, legacy: fmFoot[b].legacyFmtTime, now: fmFoot[b].text }))));
      // 复制/转发键位序（复制在前、转发在后；按 i18n 标签判，不猜图标名）+ 主会话复制键居末
      ok(`[${scheme}][B3] 复制/转发键位序一致（fm = [复制, 转发]，主会话复制键 = badge 末子节点），时间→分隔符→动作 同序`,
        BUCKETS.every((b) => {
          const acts = fmFoot[b].actionsChildren;
          return acts.length === 2
            && acts[0].label === labels.copy && acts[0].title === labels.copy
            && acts[1].label === labels.forward
            && acts[0].cls.split(/\s+/).includes('fm-msg-act') && acts[1].cls.split(/\s+/).includes('fm-msg-act');
        })
          && Object.values(mainFoot).every(m => m.copyFirst === true)
          && labels.copy !== labels.forward,
        JSON.stringify({ labels, acts: fmFoot.today.actionsChildren, mainCopyLast: Object.values(mainFoot).map(m => m.copyFirst) }));

      ok(`[${scheme}][B3] 零 console error`, consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok(`[${scheme}][B3] 零 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }

  // ── PNG 去重（md5）统计：总帧数 / 去重后张数 / 逐组口径 ──
  const groupOf = (name) => (name.match(/(A0|A1|A2|A3|B1|B2|B3)-/) || [])[1] || 'other';
  const byGroup = {};
  for (const f of frames) {
    const g = groupOf(f.name);
    byGroup[g] = byGroup[g] || { total: 0, md5: [] };
    byGroup[g].total++;
    byGroup[g].md5.push(f.md5);
  }
  const seen = new Set();
  let dup = 0;
  for (const f of frames) { if (seen.has(f.md5)) dup++; else seen.add(f.md5); }
  const groupSummary = Object.fromEntries(Object.entries(byGroup)
    .map(([g, v]) => [g, { total: v.total, unique: new Set(v.md5).size }]));
  readings.frames = { total: frames.length, unique: seen.size, duplicates: dup, byGroup: groupSummary, list: frames };
  console.log(`\nFRAMES total=${frames.length} unique=${seen.size} duplicates=${dup} byGroup=${JSON.stringify(groupSummary)}`);
  ok('实拍帧齐全：A 段 3 面板 × 4 组（A0 整窗 / A1 输入条 / A2 叠卡 / A3 footer）× 2 主题 = 24 帧 + B 段对照帧 ≥12（实计 ' + frames.length + ' 帧）',
    frames.length >= 34
      && groupSummary.A0 && groupSummary.A0.total === 6 && groupSummary.A0.unique === 6
      && groupSummary.A1 && groupSummary.A1.total === 6
      && groupSummary.A2 && groupSummary.A2.total === 6 && groupSummary.A3 && groupSummary.A3.total === 6,
    JSON.stringify(groupSummary));
  // 三面输入条每面板每主题至少三组取景（A1/A2/A3），并逐面板逐主题在场
  for (const scheme of ['light', 'dark']) {
    for (const panel of ['friend', 'group', 'device']) {
      const want = ['A1-composer', 'A2-stack', 'A3-footer'].map(g => `${panel}-${g}-${scheme}.png`);
      ok(`[${scheme}][${panel}] 三组取景齐全（输入条 / 叠卡行 / footer）`,
        want.every(n => frames.some(f => f.name === n)), JSON.stringify({ want, missing: want.filter(n => !frames.some(f => f.name === n)) }));
    }
  }
  // 🔴 A1 帧逐字节同款 = 三面输入条「像素级统一」的最硬证据（md5 相等即零像素差）
  for (const scheme of ['light', 'dark']) {
    const names = ['friend', 'group', 'device'].map(pn => `${pn}-A1-composer-${scheme}.png`);
    const hashes = names.map(n => (frames.find(f => f.name === n) || {}).md5);
    ok(`[${scheme}] A1 三面输入条实拍帧 md5 逐字节相等（像素级同款 = 统一批的硬证据）`,
      hashes.every(h => !!h) && new Set(hashes).size === 1, JSON.stringify({ names, hashes }));
  }
  // A2 帧各面板内容不同（叠卡行消息内容不同 ⇒ 不允许误去重）
  ok('A2 叠卡帧 6 张内容互异（3 面板 × 2 主题逐帧唯一）',
    groupSummary.A2 && new Set(byGroup.A2.md5).size === 6, JSON.stringify(groupSummary.A2));
  // 跨组误去重检测：A2/A3 不得与 A1 同码
  ok('帧组间无误去重（A0 整窗帧逐面板唯一；A1 输入条帧三面同码 = 预期的像素级统一）',
    !!groupSummary.A0 && groupSummary.A0.unique === 6 && !!groupSummary.A1 && groupSummary.A1.unique === 2,
    JSON.stringify(groupSummary));
} catch (e) {
  failures++;
  checks++;
  console.error('HARNESS ERROR:', (e && e.stack) || e);
} finally {
  try { await browser.close(); } catch { /* non-critical */ }
  try { await new Promise(r => server.close(r)); } catch { /* non-critical */ }
  readings.summary = { checks, failures, frames: frames.length };
  writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
  console.log(`\n=== fm-threeface-align shots: TOTAL=${checks} FAILURES=${failures} FRAMES=${frames.length} · OUT=${OUT} ===`);
  process.exit(failures === 0 ? 0 : 1);
}
