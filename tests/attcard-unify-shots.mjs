// attcard-unify-shots.mjs — fm-att-component-lib 批（2026-11-02 · 作者三点批评：
// ①多图/视频叠卡形态没做 ②fm 卡带边缘/描边 ③要真组件库不要等价适配）的**视觉验收实拍**。
//
// 帧（三面板 × 四形态 × 双主题 + 同页对照 + 手机档）：
//   attcard-{friend,group,device}-{stack,video,mixed,single}-{light,dark}.png
//     stack  = 多图叠卡（3 图：固定 220×280 卡面 + 扇形 + 「展开 3」张数角标）
//     video  = 单视频（主会话视频卡面：<video> + play-ring + dur-chip + vd-progress）
//     mixed  = 混合（图 + 文件：媒体平面卡 + 文件卡同一卡区）
//     single = 单图（媒体平面卡，自比例）
//   attcard-vs-main-{light,dark}.png  **同页并排对照**：主会话 buildUserMediaRow 原装
//     配出口（叠卡行 + 混合行）注入好友流顶，与 fm 同形态同帧 —— 同款证明：
//     逐项 computed-style 断言（圆角/描边/阴影/几何全等，边缘差异必须为零）。
//   attcard-friend-mobile-375.png     手机档 375×812（亮档，好友窗，四形态同屏）。
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
const MP4 = await readFile(join(ROOT, 'tests', 'fixtures', 'attcard-component-lib', 'tiny.mp4'));
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
const vidAtt = (id, name) => ({ id, name, size: MP4.length, mime: 'video/mp4', state: 'ready' });
const fileAtt = (id, name, size) => ({ id, name, size, mime: 'application/pdf', state: 'ready' });

// ── REST 契约镜像 ──────────────────────────────────────────────
const EPOCH = Date.now();
const FRIEND = { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(-86400) };
const row = (id, senderId, at, atts) => ({ id, senderId, kind: 'text', body: '', createdAt: iso(at), attachments: atts });
const deviceRow = (id, senderDeviceId, atMs, atts) => ({ id, senderDeviceId, kind: 'text', body: '', createdAtMs: EPOCH + atMs, attachments: atts });

/** 四形态消息组（三面同构注入）：叠卡(3图) → 单视频 → 混合(图+pdf) → 单图 → 出向叠卡
 *  （senderId me / self-1 ⇒ out 行：主会话 user-media 气泡剪影族的 fm 对照件）。 */
const formsFor = (p) => [
  row(10 * p + 1, 'u-p', -3600, [imgAtt(`att-${p}-s1`, 'stack-a-4x3.png', PNG_43), imgAtt(`att-${p}-s2`, 'stack-b-16x9.png', PNG_169), imgAtt(`att-${p}-s3`, 'stack-c-1x1.png', PNG_SQ)]),
  row(10 * p + 2, 'u-p', -3000, [vidAtt(`att-${p}-v1`, 'clip-320x240.mp4')]),
  row(10 * p + 3, 'u-p', -2400, [imgAtt(`att-${p}-m1`, 'chart-16x9.png', PNG_169), fileAtt(`att-${p}-m2`, 'quarterly-report.pdf', 204800)]),
  row(10 * p + 4, 'u-p', -1800, [imgAtt(`att-${p}-g1`, 'solo-tall-9x16.png', PNG_TALL)]),
  row(10 * p + 5, 'me', -1200, [imgAtt(`att-${p}-o1`, 'out-a-4x3.png', PNG_43), imgAtt(`att-${p}-o2`, 'out-b-16x9.png', PNG_169), imgAtt(`att-${p}-o3`, 'out-c-1x1.png', PNG_SQ)]),
];
const devFormsFor = (p) => [
  deviceRow(10 * p + 1, 'peer-1', -3600000, [imgAtt(`att-${p}-s1`, 'stack-a-4x3.png', PNG_43), imgAtt(`att-${p}-s2`, 'stack-b-16x9.png', PNG_169), imgAtt(`att-${p}-s3`, 'stack-c-1x1.png', PNG_SQ)]),
  deviceRow(10 * p + 2, 'peer-1', -3000000, [vidAtt(`att-${p}-v1`, 'clip-320x240.mp4')]),
  deviceRow(10 * p + 3, 'peer-1', -2400000, [imgAtt(`att-${p}-m1`, 'chart-16x9.png', PNG_169), fileAtt(`att-${p}-m2`, 'quarterly-report.pdf', 204800)]),
  deviceRow(10 * p + 4, 'peer-1', -1800000, [imgAtt(`att-${p}-g1`, 'solo-tall-9x16.png', PNG_TALL)]),
  deviceRow(10 * p + 5, 'self-1', -1200000, [imgAtt(`att-${p}-o1`, 'out-a-4x3.png', PNG_43), imgAtt(`att-${p}-o2`, 'out-b-16x9.png', PNG_169), imgAtt(`att-${p}-o3`, 'out-c-1x1.png', PNG_SQ)]),
];
const MSGS = {
  cF: formsFor(1),
  'g-1': formsFor(2),
  'dev:peer-1': devFormsFor(3),
};
const ATT_BYTES = new Map([
  ['att-1-s1', PNG_43], ['att-1-s2', PNG_169], ['att-1-s3', PNG_SQ], ['att-1-v1', MP4], ['att-1-m1', PNG_169], ['att-1-m2', FILE_BYTES], ['att-1-g1', PNG_TALL],
  ['att-1-o1', PNG_43], ['att-1-o2', PNG_169], ['att-1-o3', PNG_SQ],
  ['att-2-s1', PNG_43], ['att-2-s2', PNG_169], ['att-2-s3', PNG_SQ], ['att-2-v1', MP4], ['att-2-m1', PNG_169], ['att-2-m2', FILE_BYTES], ['att-2-g1', PNG_TALL],
  ['att-2-o1', PNG_43], ['att-2-o2', PNG_169], ['att-2-o3', PNG_SQ],
  ['att-3-s1', PNG_43], ['att-3-s2', PNG_169], ['att-3-s3', PNG_SQ], ['att-3-v1', MP4], ['att-3-m1', PNG_169], ['att-3-m2', FILE_BYTES], ['att-3-g1', PNG_TALL],
  ['att-3-o1', PNG_43], ['att-3-o2', PNG_169], ['att-3-o3', PNG_SQ],
]);
const CONVS = [
  { conversationId: 'cF', friend: FRIEND, lastMessage: MSGS.cF[MSGS.cF.length - 1], unreadCount: 0 },
  { conversationId: 'dev:peer-1', kind: 'device', deviceId: 'peer-1', lastMessage: MSGS['dev:peer-1'][0], unreadCount: 0 },
  { conversationId: 'dev:self-1', kind: 'device', deviceId: 'self-1', lastMessage: null, unreadCount: 0 },
];
const GROUPS = [{ groupId: 'g-1', title: '项目群', unreadCount: 0, memberCount: 3, role: 'owner', memberAvatars: [], lastMessage: MSGS['g-1'][MSGS['g-1'].length - 1] }];

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.mp4': 'video/mp4',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const browser = await chromium.launch();
const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; } // 资源噪声，不计错
    if (p.startsWith('/agents/')) { // 会话列表 agent 档案（缺席即回退默认，零行为差）
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return;
    }
    if (p.startsWith('/api/nf-file')) { // 主会话对照行的 nf-file 票腿 → mp4 真字节
      res.writeHead(200, { 'Content-Type': 'video/mp4' }); res.end(MP4); return;
    }
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

/** 主会话对照行的 inline preview = 同一枚 4:3 真位图 data URI（buildUserMediaRow 的
 *  inline-preview 腿 ⇒ 零网络出帧）。 */
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

/** 等四形态全部出帧：叠卡层图 + 视频元数据 + 平面卡图全就绪。 */
const waitFlowMedia = (page) => page.waitForFunction(() => {
  const imgs = [...document.querySelectorAll('.fm-flow .fm-att-media .pop-img')];
  if (!imgs.length || !imgs.every(i => i.complete && i.naturalWidth > 0)) return false;
  const vids = [...document.querySelectorAll('.fm-flow .fm-att-media video.pop-video')];
  return vids.length > 0 && vids.every(v => v.readyState >= 1);
}, null, { timeout: 20000 }).catch(() => false);

async function openConv(page, cid) {
  await page.click('#messages-btn'); await sleep(600);
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(900);
  await waitFlowMedia(page); await sleep(400);
}

/** 逐消息取景 + 实拍 + 形态断言。rowId = 10*p + i + 1（p = 面板号 1/2/3）。 */
const FORM_KEYS = ['stack', 'video', 'mixed', 'single'];
const panelP = { friend: 1, group: 2, device: 3 };
async function shotForms(page, panel, scheme) {
  for (let i = 0; i < FORM_KEYS.length; i++) {
    const key = FORM_KEYS[i];
    const rowId = String(10 * panelP[panel] + i + 1);
    const probe = await page.evaluate((rid) => {
      const flow = document.querySelector('.fm-flow');
      const r = flow && flow.querySelector(`.fm-msg[data-message-id="${rid}"]`);
      if (!r) return { found: false };
      r.scrollIntoView({ block: 'center' });
      const q = (s) => r.querySelector(s);
      const stackView = q('.fm-att-stack .pop-stack-view');
      const pill = q('.fm-att-stack .pill');
      const video = q('.fm-att-media video.pop-video');
      const flat = q('.fm-att-media .pop-media-flat');
      const fileCard = q('.file-card.fm-att');
      const bubble = q('.fm-msg-bubble');
      const sr = stackView ? stackView.getBoundingClientRect() : null;
      return {
        found: true,
        stack: !!stackView,
        stackW: sr ? +sr.width.toFixed(1) : null,
        stackH: sr ? +sr.height.toFixed(1) : null,
        layers: r.querySelectorAll('.fm-att-stack .pop-stack-view .media-layer').length,
        pillText: pill ? pill.textContent : '',
        video: !!video,
        videoReady: video ? video.readyState : 0,
        durChip: q('.dur-chip') ? q('.dur-chip').textContent : '',
        videoHasRing: !!q('.play-ring'),
        videoHasProgress: !!q('.vd-progress'),
        flat: !!flat,
        flatImgOk: flat && flat.querySelector('img') ? (flat.querySelector('img').naturalWidth > 0) : false,
        fileCard: !!fileCard,
        // 🔴 卡出泡（作者批评②）：附件-only 消息**不渲染泡** —— 卡不带任何泡 chrome。
        bubbleChrome: !!bubble,
        // 🔴 卡面自身零描边：媒体面 border-style 必须 none（主会话同款）。
        mediaBorderStyle: (flat || stackView) ? getComputedStyle(flat || stackView).borderStyle : '',
        dlKey: !!r.querySelector('.fm-att-dl'),
      };
    }, rowId);
    await sleep(350);
    const overlay = await page.$('#fm-chat-overlay');
    const name = `attcard-${panel}-${key}-${scheme}.png`;
    const path = join(OUT, name);
    if (overlay) await overlay.screenshot({ path }).catch(() => {});
    if (!probe.found) { ok(`shot ${name} 消息行在场`, false, 'row not found'); continue; }
    const expect = {
      stack: p => p.stack && p.layers === 3 && p.stackW === 220 && Math.abs(p.stackH - 280) <= 1 && /3/.test(p.pillText) && !p.bubbleChrome && p.mediaBorderStyle === 'none',
      video: p => p.video && p.videoReady >= 1 && /:\d\d/.test(p.durChip) && p.videoHasRing && p.videoHasProgress && !p.fileCard && !p.bubbleChrome,
      mixed: p => p.flat && p.flatImgOk && p.fileCard && !p.stack && !p.bubbleChrome,
      single: p => p.flat && p.flatImgOk && !p.fileCard && !p.bubbleChrome,
    }[key];
    const detail = JSON.stringify({ stack: probe.stack, w: probe.stackW, h: probe.stackH, layers: probe.layers, pill: probe.pillText, video: probe.video, chip: probe.durChip, ring: probe.videoHasRing, flat: probe.flat, img: probe.flatImgOk, file: probe.fileCard, bubble: probe.bubbleChrome, border: probe.mediaBorderStyle, dl: probe.dlKey });
    ok(`shot ${name} 形态判据（${key}）`, !!expect && expect(probe), detail);
  }
}

// ═════════════════════════════════════════════════════════════════
try {
  console.log(`attcard-component-lib shots · WEB=${WEB} · OUT=${OUT}`);
  for (const scheme of ['light', 'dark']) {
    const { ctx, page, consoleErrors, pageErrors } = await bootPage({ colorScheme: scheme });
    try {
      for (const [panel, cid] of [['friend', 'cF'], ['group', 'g-1'], ['device', 'dev:peer-1']]) {
        if (panel !== 'friend') {
          await page.click('.fm-modal-close').catch(async () => { await page.keyboard.press('Escape'); });
          await sleep(400);
          await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(900);
          await waitFlowMedia(page); await sleep(400);
        } else {
          await openConv(page, cid);
        }
        await shotForms(page, panel, scheme);
      }
      ok(`[${scheme}] 零 console error`, consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok(`[${scheme}] 零 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }

  // ── 同页并排对照（同款证明 · 边缘差异必须为零）────────────────────────────
  // 两侧各一对：fm 入向叠卡 ↔ 主会话 LLM Pop 行（板卡族）；fm 出向叠卡 ↔ 主会话
  // 用户媒体行（气泡剪影族，attach-bubble-unify 令）——同一组件库、按行侧取形。
  for (const scheme of ['light', 'dark']) {
    const { ctx, page, consoleErrors, pageErrors } = await bootPage({ colorScheme: scheme });
    try {
      await openConv(page, 'cF');
      const injected = await page.evaluate(async (preview) => {
        const { buildUserMediaRow, buildPopArtifactRow } = await import('/js/popArtifacts.js');
        const flow = document.querySelector('.fm-flow');
        // 主会话原装配出口：LLM Pop 叠卡行（板卡族）+ 用户媒体叠卡行（剪影族）
        const mainIn = buildPopArtifactRow({ items: [
          { kind: 'image', name: 'llm-a-4x3.png', path: '/tmp/llm-a.png', src: preview, ext: 'png' },
          { kind: 'image', name: 'llm-b-16x9.png', path: '/tmp/llm-b.png', src: preview, ext: 'png' },
          { kind: 'image', name: 'llm-c-1x1.png', path: '/tmp/llm-c.png', src: preview, ext: 'png' },
          { kind: 'file', name: 'llm-report.pdf', path: '/tmp/llm-report.pdf', ext: 'pdf', size: 204800 },
        ], fileRefs: { failed: 0 }, warnings: [] }, { ts: Date.now() });
        const mainOut = buildUserMediaRow([
          { type: 'image', name: 'out-a-4x3.png', path: '', preview, size: 9526 },
          { type: 'image', name: 'out-b-16x9.png', path: '', preview, size: 9526 },
          { type: 'image', name: 'out-c-1x1.png', path: '', preview, size: 9526 },
        ], { ts: Date.now() });
        if (!mainIn || !mainOut) return { ok: false };
        const fmInRow = flow.querySelector('.fm-msg[data-message-id="11"]');
        const fmOutRow = flow.querySelector('.fm-msg[data-message-id="15"]');
        if (!fmInRow || !fmOutRow) return { ok: false, noRows: true };
        fmInRow.before(mainIn);
        fmOutRow.before(mainOut);
        await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
        const styleOf = (el) => {
          if (!el) return null;
          const cs = getComputedStyle(el);
          const r = el.getBoundingClientRect();
          return {
            radius: cs.borderRadius, borderStyle: cs.borderStyle, borderWidth: cs.borderTopWidth,
            borderColor: cs.borderTopColor, shadow: cs.boxShadow, bg: cs.backgroundColor,
            w: +r.width.toFixed(1), h: +r.height.toFixed(1),
          };
        };
        const layer = (root) => root.querySelector('.pop-stack-view .media-layer.layer-front');
        const fileCard = (root) => root.querySelector('.file-card');
        const stackBox = (root) => root.querySelector('.pop-stack-view');
        const pillText = (root) => (root.querySelector('.pill') || {}).textContent || '';
        return {
          ok: true,
          inPair: {
            fm: styleOf(layer(fmInRow)), main: styleOf(layer(mainIn)),
            fmBox: styleOf(stackBox(fmInRow)), mainBox: styleOf(stackBox(mainIn)),
            fmPill: pillText(fmInRow), mainPill: pillText(mainIn),
            mainFile: styleOf(fileCard(mainIn)),
          },
          outPair: { fm: styleOf(layer(fmOutRow)), main: styleOf(layer(mainOut)) },
          mixedFile: styleOf(flow.querySelector('.fm-msg[data-message-id="13"] .file-card')),
          inRowTop: (() => { fmInRow.scrollIntoView({ block: 'start' }); const r = fmInRow.getBoundingClientRect(); const f = flow.getBoundingClientRect(); return r.top >= f.top - 4; })(),
        };
      }, PREVIEW_URI).catch(e => ({ ok: false, err: String(e).slice(0, 200) }));
      await sleep(500);
      ok('对照帧：主会话行注入成功（入向 Pop 行 + 出向用户行）', !!injected && injected.ok, JSON.stringify(injected && !injected.ok ? injected : { ok: true }));
      if (injected && injected.ok) {
        const same = (a, b) => !!a && !!b && a.radius === b.radius && a.borderStyle === b.borderStyle && a.borderWidth === b.borderWidth && a.borderColor === b.borderColor && a.shadow === b.shadow && a.bg === b.bg;
        const geoSame = (a, b) => !!a && !!b && a.w === b.w && a.h === b.h;
        ok(`[${scheme}] 边缘差异为零（入向）：fm 叠卡层 ↔ 主会话 LLM Pop 叠卡层 computed-style 全等`, same(injected.inPair.fm, injected.inPair.main), JSON.stringify({ fm: injected.inPair.fm, main: injected.inPair.main }));
        ok(`[${scheme}] 叠卡盒几何全等（220×280 同一面）`, geoSame(injected.inPair.fmBox, injected.inPair.mainBox) && injected.inPair.fmBox.w === 220, JSON.stringify({ fm: injected.inPair.fmBox, main: injected.inPair.mainBox }));
        ok(`[${scheme}] 张数角标同款（「展开 3」同文）`, injected.inPair.fmPill === injected.inPair.mainPill && /3/.test(injected.inPair.fmPill), JSON.stringify({ fm: injected.inPair.fmPill, main: injected.inPair.mainPill }));
        ok(`[${scheme}] 边缘差异为零（出向）：fm 叠卡层 ↔ 主会话用户媒体行叠卡层 computed-style 全等（气泡剪影族）`, same(injected.outPair.fm, injected.outPair.main), JSON.stringify({ fm: injected.outPair.fm, main: injected.outPair.main }));
        ok(`[${scheme}] 边缘差异为零：fm 入向文件卡 ↔ 主会话 Pop 文件卡 computed-style 全等`, same(injected.mixedFile, injected.inPair.mainFile), JSON.stringify({ fm: injected.mixedFile, main: injected.inPair.mainFile }));
      }
      const overlay = await page.$('#fm-chat-overlay');
      const name = `attcard-vs-main-in-${scheme}.png`;
      if (overlay) await overlay.screenshot({ path: join(OUT, name) }).catch(() => {});
      console.log(`SHOT ${name}`);
      ok(`[${scheme}][vs-main] 零 console error`, consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok(`[${scheme}][vs-main] 零 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
      // 出向对照帧：滚到出向叠卡对
      await page.evaluate(() => {
        const flow = document.querySelector('.fm-flow');
        const r = flow.querySelector('.fm-msg[data-message-id="15"]');
        if (r) r.scrollIntoView({ block: 'center' });
      });
      await sleep(400);
      const overlay2 = await page.$('#fm-chat-overlay');
      const name2 = `attcard-vs-main-out-${scheme}.png`;
      if (overlay2) await overlay2.screenshot({ path: join(OUT, name2) }).catch(() => {});
      console.log(`SHOT ${name2}`);
    } finally {
      await ctx.close();
    }
  }

  // ── 手机档（375×812，亮档，好友窗，四形态同屏纵排）────────────────────────
  {
    const { ctx, page, consoleErrors, pageErrors } = await bootPage({ colorScheme: 'light', viewport: { width: 375, height: 812 } });
    try {
      await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
      await page.click('.dock-key[data-dock="messages"]');
      await sleep(800);
      await page.click('#fm-conversations .fm-conv-row[data-conversation-id="cF"]');
      await sleep(900);
      await waitFlowMedia(page); await sleep(400);
      const cardProbe = await page.evaluate(() => {
        const flow = document.querySelector('.fm-flow');
        const stack = flow.querySelector('.fm-att-stack .pop-stack-view');
        const sr = stack ? stack.getBoundingClientRect() : null;
        return {
          stack: !!stack,
          stackW: sr ? +sr.width.toFixed(1) : null,
          video: !!flow.querySelector('.fm-att-media video.pop-video'),
          mixed: !!flow.querySelector('.fm-msg[data-message-id="13"] .file-card'),
          mobile: document.body.classList.contains('mobile-layer'),
          hOverflow: flow.scrollWidth > flow.clientWidth + 1,
        };
      });
      await page.evaluate(() => { const f = document.querySelector('.fm-flow'); if (f) f.scrollTop = f.scrollHeight; });
      await sleep(300);
      const shotPath = join(OUT, 'attcard-friend-mobile-375.png');
      const overlay = await page.$('#fm-chat-overlay');
      if (overlay) await overlay.screenshot({ path: shotPath }).catch(async () => { await page.screenshot({ path: shotPath }); });
      else await page.screenshot({ path: shotPath });
      console.log(`SHOT attcard-friend-mobile-375.png  cards=${JSON.stringify(cardProbe)}`);
      ok('shot attcard-friend-mobile-375.png 手机档四形态（叠卡 220 盒 + 视频 + 混合；零横向溢出）',
        cardProbe.mobile && cardProbe.stack && cardProbe.stackW === 220 && cardProbe.video && cardProbe.mixed && !cardProbe.hOverflow,
        JSON.stringify(cardProbe));
      ok('[mobile] 零 console error', consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok('[mobile] 零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
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
  console.log(`\n=== attcard-component-lib shots: failures=${failures} · OUT=${OUT} ===`);
  process.exit(failures === 0 ? 0 : 1);
}
