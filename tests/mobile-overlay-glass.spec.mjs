// mobile-overlay-glass.spec.mjs — 手机端浮层三件闸（作者令 2026-11-18 16:36 手机端实拍三条）
//
// 作者原话（逐字）：
//   ①「消息联系人对话（以后统称社交对话）下方输入框 手机端和dock重叠了 而且还是有
//      截断 不是真透过 **真透过是类似canvas这样的**」
//   ②「设置的顶部还是有截断 就顶部返回按钮那一栏 **返回按钮应该也是悬空的按钮**」
//
// 本闸钉三条性质，每条都能抓「重叠 / 裁切 / 实底覆盖」：
//   G1 重叠   —— 社交对话输入条与 dock 的矩形**零相交**且留出呼吸缝（几何相交判据）。
//   G2 裁切   —— 会话流可滚到最后一条消息且**完整可读**（末条矩形落在输入条与 dock
//                之上，且不被任何 overflow 裁剪祖先切到）。
//   G3 真透过 —— dock/输入条玻璃背后**确有内容**且配方与 canvas 参照物逐字同值。
//                · 配方判据：dock / 输入条 / canvas 悬浮圆钮（作者参照物 .g2-fab，
//                  canvas 页实测）三者 backdrop-filter 逐字同值 = 唯一配方令牌
//                  --nf-glass-input-filter；底色 α 落在「透过带」（≤ .35，实底 ≥ .90）。
//                · 像素判据在**严格腿 G3s**（opt-in：NEB_OVERLAY_STRICT_GLASS=1）：
//                  判据**单源** = `scripts/glass-runtime-criteria.mjs`（与运行时闸 R1 共用，
//                  阈值取自设计规范 §9）。不变式：**玻璃贡献只在背后样本可判时才成立** ——
//                  背后有可辨内容（背板 edge ≥ flatEdgeMax）⇒ 该面自己的 backdrop-filter
//                  必须改像素（Δpx ≥ effectMin）；背后平色 / 被不透明层盖住 ⇒ 该样本不可判（豁免）。
//                  该腿**单钉能栅格化 backdrop-filter 的引擎**（channel: 'chromium'）并先自证
//                  引擎能力 —— 默认 headless shell **整只**不栅格化 backdrop-filter，
//                  在它上面量这条永远得 0（既有「sd≡0」假读数的真因之一，见 G3s 头注）。
//                  （2026-11-18 复测批收口：静止态背后为空是净空 134px 的设计属性，
//                    不是缺陷 —— 旧「sd(A) > 1.0」按数字调的写法已废。）
//   G4 顶栏   —— 设置页是**全屏页**（不是弹窗），返回钮是**悬空玻璃钮**，内容从钮背后
//                穿过；顶部/中部/底部三个滚动位置都无裁切（行矩形不被裁剪祖先切到）。
//   G5 形态铁律 —— 手机端设置 = 全屏页形态（viewport 375×812；唯一 .live 页面包住整个
//                视口；#settings-modal 收编为 static / 无底 / 无圆角；无 overlay 幕布）。
//                —— 禁止以弹窗/内嵌面板当手机端验收形态。
//
// 自证检测力（拿当前坏源码当破坏样本）：
//   红：NEB_MOBILE_WEB_ROOT=<主检出>/src/main/resources/web node node_modules/@playwright/test/cli.js test tests/mobile-overlay-glass.spec.mjs
//   绿：node node_modules/@playwright/test/cli.js test tests/mobile-overlay-glass.spec.mjs
//   严格腿：NEB_OVERLAY_STRICT_GLASS=1 node node_modules/@playwright/test/cli.js test tests/mobile-overlay-glass.spec.mjs
//
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-overlay-glass.spec.mjs

import { test, expect, chromium } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.NEB_MOBILE_WEB_ROOT || join(HERE, '..', 'src', 'main', 'resources', 'web');

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'overlay-glass-root-session';
const SEED_MESSAGES = 16;

// ── 最小 PNG 解码（8bit RGB/RGBA、非隔行；无外部依赖 —— 闸必须能在纯 CI 起）──
function decodePng(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a png');
  let off = 8, w = 0, h = 0, depth = 0, color = 0, interlace = 0;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4);
      depth = data[8]; color = data[9]; interlace = data[12];
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    off += 12 + len;
  }
  if (depth !== 8 || interlace !== 0) throw new Error(`unsupported png: depth=${depth} interlace=${interlace}`);
  const ch = color === 6 ? 4 : color === 2 ? 3 : color === 0 ? 1 : -1;
  if (ch < 0) throw new Error('unsupported color type ' + color);
  const raw = inflateSync(Buffer.concat(idat));
  const stride = w * ch;
  const out = Buffer.alloc(h * stride);
  let p = 0;
  for (let y = 0; y < h; y++) {
    const f = raw[p++];
    const line = raw.subarray(p, p + stride); p += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : null;
    for (let x = 0; x < stride; x++) {
      const a = x >= ch ? cur[x - ch] : 0;
      const b = prev ? prev[x] : 0;
      const c = prev && x >= ch ? prev[x - ch] : 0;
      let v = line[x];
      if (f === 1) v += a;
      else if (f === 2) v += b;
      else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) {
        const pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
        v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
      }
      cur[x] = v & 0xff;
    }
  }
  return { width: w, height: h, ch, data: out };
}

/** 取 region 的灰度数组（linear 0-255）+ 统计量。region 用图片像素坐标。 */
function lumaStats(img, x0, y0, rw, rh, step = 2) {
  const vals = [];
  for (let y = y0; y < Math.min(y0 + rh, img.height); y += step) {
    for (let x = x0; x < Math.min(x0 + rw, img.width); x += step) {
      const i = (y * img.width + x) * img.ch;
      vals.push(0.2126 * img.data[i] + 0.7152 * img.data[i + 1] + 0.0722 * img.data[i + 2]);
    }
  }
  return vals;
}
const mean = (a) => a.reduce((s, v) => s + v, 0) / a.length;
function stddev(a) { const m = mean(a); return Math.sqrt(a.reduce((s, v) => s + (v - m) ** 2, 0) / a.length); }
function corr(a, b) {
  const ma = mean(a), mb = mean(b);
  let num = 0, da = 0, db = 0;
  for (let i = 0; i < a.length; i++) { const x = a[i] - ma, y = b[i] - mb; num += x * y; da += x * x; db += y * y; }
  return num / Math.sqrt(da * db || 1);
}
/** 两张同尺寸 PNG 的逐通道平均绝对差（Δpx / 可见度读数）。 */
function meanAbsDiffImg(a, b) {
  const n = Math.min(a.data.length, b.data.length); let s = 0, c = 0;
  for (let i = 0; i < n; i += a.ch) for (let k = 0; k < Math.min(3, a.ch); k++) { s += Math.abs(a.data[i + k] - b.data[i + k]); c++; }
  return c ? s / c : 0;
}
/** 区域内水平相邻像素的亮度平均绝对差 = 背板**平整度**读数（近 0 ⇒ 平色背板）。 */
function edgeEnergy(img, x0, y0, rw, rh) {
  const y1 = Math.min(y0 + rh, img.height), x1 = Math.min(x0 + rw, img.width);
  const L = (x, y) => { const i = (y * img.width + x) * img.ch; return 0.2126 * img.data[i] + 0.7152 * img.data[i + 1] + 0.0722 * img.data[i + 2]; };
  let e = 0, n = 0;
  for (let y = Math.max(0, y0); y < y1; y++) for (let x = Math.max(1, x0 + 1); x < x1; x++) { e += Math.abs(L(x, y) - L(x - 1, y)); n++; }
  return e / Math.max(1, n);
}
/** rgba()/rgb() → [r,g,b,a]；transparent 视为 α 0。 */
function parseColor(s) {
  if (!s) return [0, 0, 0, 0];
  if (s === 'transparent') return [0, 0, 0, 0];
  const m = s.match(/rgba?\(([^)]+)\)/);
  if (!m) return [0, 0, 0, 1];
  const p = m[1].split(/[,\s/]+/).filter(Boolean).map(Number);
  return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1];
}

async function bootApp(page) {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(({ n }) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify({
      self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
      users: [],
      friends: [{ userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', since: Date.now() - 86400e3 }],
      incoming: [], outgoing: [],
      conversations: [{
        conversationId: 'c-p',
        friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '' },
        lastMessage: { id: 220, senderId: 'u-p', kind: 'text', body: '历史消息 #220', createdAt: Date.now() - 60e3 },
        unreadCount: 0, pendingAskCount: 0,
      }],
      messages: {
        'c-p': Array.from({ length: n }, (_, i) => ({
          id: 200 + i, senderId: i % 2 ? 'me' : 'u-p', kind: 'text',
          body: `结论与范围 #${200 + i} — 历史输入有三项已修复错误，但剂量口径仍需复核；这是一条足够长的正文用于观察被浮层裁切的边界。`,
          createdAt: Date.now() - (600 - i * 10) * 1e3,
        })),
      },
    }));
  }, { n: SEED_MESSAGES });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: 'Mashiro' }, peers: [] }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let p; try { p = JSON.parse(raw); } catch { return; }
      if (p.type === 'getConfig') sendConfig();
      else if (p.type === 'getSessions' || p.type === 'listSessions') sendSessions();
      else if (p.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: p.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(400);
}

const rectOf = (sel) => `(() => { const e = document.querySelector(${JSON.stringify(sel)}); if (!e) return null; const r = e.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height, top: r.top, bottom: r.bottom, left: r.left, right: r.right }; })()`;

async function openConversation(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="messages"].live'));
  await page.locator('.mobile-page[data-page="messages"] .mobile-page-body #fm-conversations .fm-conv-row').first().click();
  await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="conversation"].live'));
  await page.waitForTimeout(500);
}

async function openSettings(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="workbench"].live'));
  await page.waitForTimeout(400);
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').first().click();
  await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="settings"].live'));
  await page.waitForTimeout(700);
}

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, deviceScaleFactor: 2 });

test.beforeEach(async ({ page }) => { await bootApp(page); });

test('G1 overlap: the social-conversation composer and the dock never intersect (375x812)', async ({ page }) => {
  await openConversation(page);
  const composer = await page.evaluate(rectOf('.mobile-page[data-page="conversation"] .fm-input-bar'));
  const dock = await page.evaluate(rectOf('#mobile-dock'));
  expect(composer, 'composer .fm-input-bar must exist on the conversation page').not.toBeNull();
  expect(dock).not.toBeNull();
  // 可见性：不是被塌成 0 高或被隐藏
  expect(composer.h).toBeGreaterThan(40);
  expect(composer.w).toBeGreaterThan(200);
  // 几何相交判据（纵向 + 横向都要判，任一维不相交即无重叠；两条都断言更严）
  const yOverlap = Math.max(0, Math.min(composer.bottom, dock.bottom) - Math.max(composer.top, dock.top));
  const xOverlap = Math.max(0, Math.min(composer.right, dock.right) - Math.max(composer.left, dock.left));
  // 实拍：竖向必须完全分离（留呼吸缝），横向同轴是设计要求（同宽同边距）
  expect(yOverlap, `composer(y ${composer.top.toFixed(1)}-${composer.bottom.toFixed(1)}) vs dock(y ${dock.top.toFixed(1)}-${dock.bottom.toFixed(1)}) vertical overlap`).toBe(0);
  expect(xOverlap, 'composer and dock are expected to share the same axis (same width/insets)').toBeGreaterThan(300);
  const gap = dock.top - composer.bottom;
  expect(gap, `breathing gap between composer bottom and dock top (${gap.toFixed(1)}px)`).toBeGreaterThanOrEqual(8);
  // 输入条必须完整落在视口内（不溢出屏幕）
  expect(composer.bottom).toBeLessThanOrEqual(812 + 0.5);
  expect(composer.top).toBeGreaterThan(0);
});

test('G2 clipping: the message flow reaches its last message, readable above composer and dock', async ({ page }) => {
  await openConversation(page);
  const res = await page.evaluate(`(() => {
    const q = (s) => document.querySelector(s);
    const flow = q('.mobile-page[data-page="conversation"] .fm-flow');
    flow.scrollTop = flow.scrollHeight;
    const msgs = [...flow.querySelectorAll('.fm-msg')];
    const last = msgs[msgs.length - 1];
    const lr = last.getBoundingClientRect();
    const comp = q('.mobile-page[data-page="conversation"] .fm-input-bar').getBoundingClientRect();
    const dock = q('#mobile-dock').getBoundingClientRect();
    // 裁剪祖先判据：末条消息不得被任何 overflow 裁剪祖先切到
    const clippedBy = [];
    let n = last.parentElement;
    while (n && n !== document.body) {
      const c = getComputedStyle(n);
      if (c.overflowY === 'hidden' || c.overflowY === 'clip' || c.overflow === 'hidden' || c.overflow === 'clip') {
        const r = n.getBoundingClientRect();
        if (lr.top < r.top - 1 || lr.bottom > r.bottom + 1) clippedBy.push({ tag: n.tagName.toLowerCase() + (n.id ? '#' + n.id : ''), cls: typeof n.className === 'string' ? n.className : '', clipTop: +r.top.toFixed(1), clipBottom: +r.bottom.toFixed(1) });
      }
      n = n.parentElement;
    }
    return { msgCount: msgs.length, lastTop: +lr.top.toFixed(1), lastBottom: +lr.bottom.toFixed(1),
      lastH: +lr.height.toFixed(1), composerTop: +comp.top.toFixed(1), dockTop: +dock.top.toFixed(1),
      scrollTop: flow.scrollTop, scrollHeight: flow.scrollHeight, clientHeight: flow.clientHeight,
      flowPadBottom: getComputedStyle(flow).paddingBottom, clippedBy };
  })()`);
  expect(res.msgCount, 'seeded conversation must render messages').toBeGreaterThan(4);
  expect(res.scrollHeight).toBeGreaterThan(res.clientHeight);     // 真滚动面
  expect(res.clippedBy, 'the last message must not be cut by any clipping ancestor').toEqual([]);
  expect(res.lastTop, 'last message body above the viewport top').toBeGreaterThanOrEqual(0);
  // 完整可读：末条消息整体落在输入条之上（输入条又整体落在 dock 之上）
  expect(res.lastBottom, `last message bottom ${res.lastBottom} vs composer top ${res.composerTop}`).toBeLessThanOrEqual(res.composerTop + 0.5);
  expect(res.lastBottom).toBeLessThanOrEqual(res.dockTop + 0.5);
  expect(res.lastH).toBeGreaterThan(10);
  // 机制判据：滚动末端净空 ≥ 输入条足迹 + dock 让位（净空被误删 ⇒ 立刻红）
  const pad = parseFloat(res.flowPadBottom);
  expect(pad, `flow scroll-end clearance ${res.flowPadBottom}`).toBeGreaterThanOrEqual(120);
});

test('G3 true see-through: the composer glass shows real, blurred backdrop content (not a solid cover), on the same recipe as canvas', async ({ page }) => {
  await openConversation(page);
  // ① 配方判据：唯一配方令牌（--nf-glass-input-filter）+ 透过档 α（≤ .35）。
  const recipe = await page.evaluate(`(() => {
    const cs = getComputedStyle(document.body);
    const of = (s) => { const e = document.querySelector(s); return e ? getComputedStyle(e) : null; };
    const dockC = of('#mobile-dock');
    const compC = of('.mobile-page[data-page="conversation"] .fm-input-bar');
    return { token: cs.getPropertyValue('--nf-glass-input-filter').trim(),
      bgToken: cs.getPropertyValue('--nf-glass-input-bg').trim(),
      dockFilter: dockC.backdropFilter, dockBg: dockC.backgroundColor,
      composerFilter: compC.backdropFilter, composerBg: compC.backgroundColor,
      composerClass: document.querySelector('.mobile-page[data-page="conversation"] .fm-input-bar').className };
  })()`);
  expect(recipe.token, 'the unique glass recipe token must be declared').toMatch(/blur\(\d+px\) saturate\(/);
  expect(recipe.dockFilter, 'dock must consume the single glass recipe').toBe(recipe.token);
  expect(recipe.composerFilter, 'composer must consume the single glass recipe').toBe(recipe.token);
  expect(recipe.composerClass, 'the composer carries the shared .nf-glass member class').toContain('nf-glass');
  const [, , , da] = parseColor(recipe.dockBg);
  expect(da, `dock background alpha ${da} must sit in the see-through band (<= .35); a solid cover is >= .90`).toBeLessThanOrEqual(0.35);
  const [, , , ca] = parseColor(recipe.composerBg);
  expect(ca, `composer background alpha ${ca}`).toBeLessThanOrEqual(0.35);
  // α 档同源（作者令「同一配方与 α 档，别自创第二种」）
  expect(recipe.dockBg, 'dock and composer must share the exact same glass alpha step').toBe(recipe.composerBg);
  expect(recipe.dockBg).toBe(recipe.bgToken);

  // ② 参照物判据：canvas 页的悬浮圆钮 —— 作者指定的「真透过」参照形态
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="canvas"].live'));
  await page.waitForTimeout(600);
  const canvasRef = await page.evaluate(`(() => {
    const e = document.querySelector('.mobile-page[data-page="canvas"] .mf-cv-toolbar .g2-fab')
      || document.querySelector('.mobile-page[data-page="canvas"] .g2-fab');
    if (!e) return null;
    const c = getComputedStyle(e);
    return { filter: c.backdropFilter, bg: c.backgroundColor,
      token: getComputedStyle(document.body).getPropertyValue('--nf-glass-input-filter').trim() };
  })()`);
  expect(canvasRef, 'the canvas floating button (author-named reference) must exist in the canvas page').not.toBeNull();
  expect(canvasRef.filter, 'canvas reference must carry the same recipe token').toBe(recipe.token);
  expect(canvasRef.bg, 'canvas reference must carry the same alpha step').toBe(recipe.dockBg);

  // ③ 真透过（几何 + 命中判据）：内容**确实在玻璃背后**，不是被挡在玻璃之外
  //    （修前：会话流止于 y=738，输入条在 y=738–800 ⇒ 输入条上缘 = 流下缘，
  //     流里没有任何一条气泡落在玻璃带内 ⇒ 本判据红。）
  await openConversation(page);
  const behindGlass = await page.evaluate(`(() => {
    const q = (s) => document.querySelector(s);
    const flow = q('.mobile-page[data-page="conversation"] .fm-flow');
    const comp = q('.mobile-page[data-page="conversation"] .fm-input-bar');
    // 先把一条气泡贴到输入条玻璃带（否则滚位随机，探针点可能落在气泡之间的空档）
    const msgs0 = [...flow.querySelectorAll('.fm-msg')].filter((e) => { const r = e.getBoundingClientRect(); return r.height > 20 && getComputedStyle(e).display !== 'none'; });
    const max0 = flow.scrollHeight - flow.clientHeight;
    const cr0 = comp.getBoundingClientRect();
    for (const m of msgs0) {
      const r = m.getBoundingClientRect();
      flow.scrollTop = Math.max(0, Math.min(max0, flow.scrollTop + (r.bottom - (cr0.top + 13))));
      const prev = comp.style.visibility; comp.style.visibility = 'hidden';
      const h = document.elementFromPoint(cr0.left + 245, cr0.top + 13);
      comp.style.visibility = prev;
      if (h && h.closest('.fm-msg')) break;
    }
    const compR = comp.getBoundingClientRect();
    const flowR = flow.getBoundingClientRect();
    const probe = (el, x, y) => { const prev = el.style.visibility; el.style.visibility = 'hidden';
      const hit = document.elementFromPoint(x, y); el.style.visibility = prev;
      return hit ? (hit.closest('.fm-msg') ? 'fm-msg' : hit.tagName.toLowerCase() + (hit.id ? '#' + hit.id : '')) : null; };
    const dock = q('#mobile-dock');
    const dockR = dock.getBoundingClientRect();
    return { composerTop: +compR.top.toFixed(1), composerBottom: +compR.bottom.toFixed(1),
      flowTop: +flowR.top.toFixed(1), flowBottom: +flowR.bottom.toFixed(1),
      dockTop: +dockR.top.toFixed(1), dockBottom: +dockR.bottom.toFixed(1),
      hitComposer: probe(comp, compR.left + 245, compR.top + 13),
      hitDock: probe(dock, dockR.left + 245, dockR.top + 13),
      msgsBehindComposer: (() => { const prev = comp.style.visibility; comp.style.visibility = 'hidden';
        const n = [...flow.querySelectorAll('.fm-msg')].filter((e) => { const r = e.getBoundingClientRect(); return r.height > 20 && r.bottom > compR.top + 2 && r.top < compR.bottom - 2; }).length;
        comp.style.visibility = prev; return n; })() };
  })()`);
  expect(behindGlass.composerTop, 'the composer must sit INSIDE the message flow band, i.e. content really runs behind the glass').toBeLessThan(behindGlass.flowBottom);
  expect(behindGlass.hitComposer, 'a message bubble must paint at the composer glass sampling point (content behind the glass)').toBe('fm-msg');
  expect(behindGlass.msgsBehindComposer, 'messages must overlap the composer glass band').toBeGreaterThan(0);
  expect(behindGlass.hitDock, 'a message bubble must paint at the dock glass sampling point (content behind the glass)').toBe('fm-msg');

  // ④ 旧数字腿读数**留档**（仅日志，不再断言、也不承载判据）。
  //    这两条读数在**不栅格化 backdrop-filter 的引擎**里恒为 0 / 1.3 量级 —— 实测（复测批
  //    2026-11-18）：@playwright/test 默认那只浏览器（headless shell）整只不栅格化
  //    backdrop-filter。像素判据见下方「严格腿 G3s」（单钉能栅格化的引擎 + 先自证引擎能力）。
  const g2 = await page.evaluate(`(() => {
    const c = document.querySelector('.mobile-page[data-page="conversation"] .fm-input-bar').getBoundingClientRect();
    return { x: Math.round(c.left) + 200, y: Math.round(c.top) + 4, width: 90, height: 14 };
  })()`);
  const shotA = await page.screenshot({ clip: g2 });
  await page.evaluate(`(() => { const c = document.querySelector('.mobile-page[data-page="conversation"] .fm-input-bar'); c.dataset.__b = c.style.background; c.style.background = 'rgba(19,22,28,1)'; c.dataset.__f = c.style.backdropFilter; c.style.backdropFilter = 'none'; c.style.webkitBackdropFilter = 'none'; })()`);
  await page.waitForTimeout(150);
  const shotD = await page.screenshot({ clip: g2 });
  await page.evaluate(`(() => { const c = document.querySelector('.mobile-page[data-page="conversation"] .fm-input-bar'); c.style.background = c.dataset.__b || ''; c.style.backdropFilter = c.dataset.__f || ''; c.style.webkitBackdropFilter = c.dataset.__f || ''; })()`);
  const imA = decodePng(shotA), imD = decodePng(shotD);
  const sdA = stddev(lumaStats(imA, 0, 0, imA.width, imA.height, 1));
  const absAD = meanAbsDiffImg(imA, imD);
  console.log(`[glass-leg] rest-band sd(A)=${sdA.toFixed(2)} meanAbs(A,D)=${absAD.toFixed(2)}（旧数字腿读数，仅留档；判据见 G3s）`);
});

// ── 严格腿 G3s：真透过的**像素判据**（判据**单源**：与运行时闸 R1 共用
//    `scripts/glass-runtime-criteria.mjs`；阈值来自references/frontend/glass-runtime-spec.md §9.2 的 ```glass-runtime-criteria 块，
//    本文件**零阈值拷贝**）──────────────────────────────────────────────────────
//
// 不变式：**玻璃贡献只在背后样本可判时才成立** ——
//   · 背后有可辨内容（背板边缘能量 ≥ flatEdgeMax）⇒ 该面**自己**的 backdrop-filter 必须改像素（Δpx ≥ effectMin）；
//   · 背后是平色背板（模糊常量色仍得常量色）/ 该面被不透明层盖住 ⇒ 该样本**不可判** = 豁免（逐样本具名，不是阈值放宽）。
//
// 为什么不再写「sd(A) > 1.0 / meanAbs(A,D) > 3.0」：那是按数字调，且**在错的引擎上**调。
//
// 🔴 引擎前提（2026-11-18 复测批实测 —— 这是「严格腿一直红」的真因之一，不是产品缺陷）：
//   `backdrop-filter` 只在**能栅格化它的引擎**里才改像素。`@playwright/test` 默认那只浏览器
//   （headless shell）**整只**不栅格化 backdrop-filter：同一棵树、同一屏、同一只玻璃，
//   默认引擎 Δpx ≡ 0；本机 full chromium（`channel: 'chromium'` —— 运行时闸 R1 用的正是这一只）
//   同屏 Δpx = 33.15。在默认引擎上量「玻璃真不真采样」永远得 0 ⇒ 既有「玻璃纸」假红与
//   「sd ≡ 0」假读数。故本腿：① 只跑 full chromium；② 先**自证引擎能力**（合成对照面开/关
//   backdrop-filter 必须改像素），能力不成立就 **SKIP**（CI 档 SKIP 记 FAIL），绝不静默绿 ——
//   这是「不能判」与「判绿」的分界线。
test.describe('strict see-through pixel leg', () => {
  test.skip(process.env.NEB_OVERLAY_STRICT_GLASS !== '1', 'strict 像素腿是 opt-in：设 NEB_OVERLAY_STRICT_GLASS=1');

  test('G3s see-through: the composer glass really samples its backdrop whenever the sample is judgeable', async () => {
    const { CRITERIA, glassSampleVerdict } = await import('../scripts/glass-runtime-criteria.mjs');
    // 本腿**自起**那只 full chromium（`channel: 'chromium'`，与运行时闸 R1 同一只）——
    // test.use({channel}) 在 describe 里不允许（会强开新 worker），而默认那只 headless shell
    // 整只不栅格化 backdrop-filter，量不了这条判据（见本段头注）。
    let browser = null;
    try { browser = await chromium.launch({ channel: 'chromium' }); }
    catch (e) {
      const why = `full chromium（channel: 'chromium'）不可用，无法栅格化 backdrop-filter：${e.message.split('\n')[0]}`;
      console.log(`SKIP  G3s ${why}`);
      if (process.env.CI) expect(false, `SKIP in CI counts as FAIL: ${why}`).toBe(true);
      test.skip(true, why);
      return;
    }
    const context = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true, isMobile: true, colorScheme: 'dark', locale: 'zh-CN', deviceScaleFactor: 2 });
    try {
      const page = await context.newPage();
      await bootApp(page);

    // ⓪ 引擎能力自证：合成对照面（高对比条纹背板 + blur 玻璃面），开/关 backdrop-filter 必须改像素。
    await page.evaluate(() => {
      const host = document.createElement('div');
      host.id = 'nb-glass-cap-probe';
      host.style.cssText = 'position:fixed;left:0;top:0;width:120px;height:60px;z-index:2147483647;pointer-events:none';
      const back = document.createElement('div');
      back.style.cssText = 'position:absolute;inset:0;background:repeating-linear-gradient(90deg,#000 0 6px,#fff 6px 12px)';
      const glass = document.createElement('div');
      glass.id = 'nb-glass-cap-probe-glass';
      glass.style.cssText = 'position:absolute;inset:0;backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px)';
      host.append(back, glass); document.body.appendChild(host);
    });
    await page.waitForTimeout(120);
    const capClip = { x: 0, y: 0, width: 120, height: 60 };
    const capOn = decodePng(await page.screenshot({ clip: capClip }));
    await page.evaluate(() => { const g = document.getElementById('nb-glass-cap-probe-glass'); g.style.backdropFilter = 'none'; g.style.webkitBackdropFilter = 'none'; void g.offsetHeight; });
    await page.waitForTimeout(120);
    const capOff = decodePng(await page.screenshot({ clip: capClip }));
    await page.evaluate(() => { document.getElementById('nb-glass-cap-probe')?.remove(); });
    const capability = meanAbsDiffImg(capOn, capOff);
    if (capability < CRITERIA.effectMin) {
      const why = `引擎不栅格化 backdrop-filter（合成对照面开/关逐像素差 ${capability.toFixed(3)} < ${CRITERIA.effectMin}）⇒ 玻璃像素样本不可判`;
      console.log(`SKIP  G3s ${why}`);
      if (process.env.CI) expect(capability, `SKIP in CI counts as FAIL: ${why}`).toBeGreaterThanOrEqual(CRITERIA.effectMin);
      test.skip(true, why);
      return;
    }

    await openConversation(page);
    const composerSel = '.mobile-page[data-page="conversation"] .fm-input-bar';
    const band = await page.evaluate(`(() => { const c = document.querySelector(${JSON.stringify(composerSel)}).getBoundingClientRect(); return { x: Math.round(c.left) + 200, y: Math.round(c.top) + 4, width: 90, height: 14 }; })()`);
    // settleBottom 的 1200ms「进场落底武装窗」每帧把滚动位拽回底部；用户意图事件即拆窗。
    // 这里模拟「用户上翻读历史」—— 正是「真透过」必须成立的状态。
    const disarm = () => page.evaluate(`(() => { for (const t of ['touchmove', 'touchstart', 'wheel', 'pointerdown']) document.dispatchEvent(new Event(t, { bubbles: true })); })()`);
    const armAt = (d) => page.evaluate(({ d }) => { const f = document.querySelector('.mobile-page[data-page="conversation"] .fm-flow'); const max = f.scrollHeight - f.clientHeight; f.scrollTop = Math.max(0, Math.min(max, max - d)); return +f.scrollTop.toFixed(1); }, { d });
    const hitAtBand = () => page.evaluate(({ x, y }) => { const c = document.querySelector('.mobile-page[data-page="conversation"] .fm-input-bar'); const p = c.style.visibility; c.style.visibility = 'hidden'; void c.offsetHeight; const h = document.elementFromPoint(x, y); c.style.visibility = p; void c.offsetHeight; return h ? (h.closest('.fm-msg') ? 'fm-msg' : h.tagName.toLowerCase() + (h.id ? '#' + h.id : '')) : null; }, { x: band.x + 45, y: band.y + 7 });
    const setComposer = (props) => page.evaluate(({ p, sel }) => {
      const c = document.querySelector(sel);
      c.dataset.__sp = c.dataset.__sp || JSON.stringify({ f: c.style.backdropFilter, w: c.style.webkitBackdropFilter, v: c.style.visibility });
      Object.assign(c.style, p);
      void c.offsetHeight;                    // 强制布局：样式变更后 backdrop 根必须重建（否则合成层留旧 backdrop）
    }, { p: props, sel: composerSel });
    const restoreComposer = () => page.evaluate((sel) => { const c = document.querySelector(sel); const s = JSON.parse(c.dataset.__sp || '{}'); c.style.backdropFilter = s.f || ''; c.style.webkitBackdropFilter = s.w || ''; c.style.visibility = s.v || ''; void c.offsetHeight; }, composerSel);
    const shot = async () => decodePng(await page.screenshot({ clip: band }));
    /** 藏起玻璃面 ⇒ 量它**背后**的背板平整度（平色背板 ⇒ 该样本不可判）。 */
    const backdropNow = async () => { await setComposer({ visibility: 'hidden' }); await page.waitForTimeout(90); const im = await shot(); await restoreComposer(); return edgeEnergy(im, 0, 0, im.width, im.height); };

    // ① 扫滚动位，找**背后可辨**的样本（可判样本才承载判据）；找不到 ⇒ 判据前提不成立 ⇒ 红。
    const trials = [];
    let sample = null;
    for (const d of [90, 60, 140, 300, 200, 40, 20, 420, 560]) {
      await disarm();
      const top = await armAt(d);
      await page.waitForTimeout(70);
      const hit = await hitAtBand();
      const edge = await backdropNow();
      trials.push({ offsetFromBottom: d, scrollTop: top, hit, backdropEdge: +edge.toFixed(2) });
      if (hit === 'fm-msg' && edge >= CRITERIA.flatEdgeMax) { sample = { d, hit, backdropEdge: edge }; break; }
    }
    expect(sample, `[strict] 必须能装配出一个「背后有可辨内容」的可判样本（扫位读数：${JSON.stringify(trials)}）`).not.toBeNull();

    // ② 在可判样本上取三条读数：Δpx（本相 vs 只摘该面自身 backdrop-filter）、可见度（本相 vs 藏起该面）、背板 edge
    await disarm(); await armAt(sample.d); await page.waitForTimeout(70);
    const glass = await shot();
    await setComposer({ backdropFilter: 'none', webkitBackdropFilter: 'none' }); await page.waitForTimeout(90);
    const noGlass = await shot();
    await restoreComposer();
    await setComposer({ visibility: 'hidden' }); await page.waitForTimeout(90);
    const hidden = await shot();
    await restoreComposer();
    const deltaPx = meanAbsDiffImg(glass, noGlass);
    const visibleDelta = meanAbsDiffImg(glass, hidden);
    const bandEdge = edgeEnergy(hidden, 0, 0, hidden.width, hidden.height);
    const v = glassSampleVerdict({ deltaPx, backdropEdge: bandEdge, cover: { visibleDelta, occluders: [] } });
    const sdArmed = stddev(lumaStats(glass, 0, 0, glass.width, glass.height, 1));
    console.log(`[strict] 引擎能力 Δpx=${capability.toFixed(2)}；armed sample offset=${sample.d}px hit=${sample.hit} Δpx=${deltaPx.toFixed(3)} 背板edge=${bandEdge.toFixed(2)} 可见度=${visibleDelta.toFixed(3)} sd(A)=${sdArmed.toFixed(2)} ⇒ ${v.status}；扫位 ${JSON.stringify(trials)}`);
    expect(v.judgeable, `[strict] 该样本必须可判（背后已有可辨内容）：Δpx=${deltaPx.toFixed(3)} 背板edge=${bandEdge.toFixed(2)} — ${v.reason}`).toBe(true);
    expect(v.ok, `[strict] 玻璃必须真采到背板：Δpx=${deltaPx.toFixed(3)}（effectMin=${CRITERIA.effectMin}）— ${v.reason}`).toBe(true);

    // ③ 静止态留档：净空 134px ⇒ 背后为空 ⇒ 平色背板 ⇒ 不可判（设计属性，非缺陷）。它必须**不红**
    //    （要么真采样、要么豁免），但不得被当成缺陷追。
    await disarm(); await armAt(0); await page.waitForTimeout(70);
    const restGlass = await shot();
    await setComposer({ backdropFilter: 'none', webkitBackdropFilter: 'none' }); await page.waitForTimeout(90);
    const restNoGlass = await shot();
    await restoreComposer();
    await setComposer({ visibility: 'hidden' }); await page.waitForTimeout(90);
    const restHidden = await shot();
    await restoreComposer();
    const restEdge = edgeEnergy(restHidden, 0, 0, restHidden.width, restHidden.height);
    const restDelta = meanAbsDiffImg(restGlass, restNoGlass);
    const restV = glassSampleVerdict({ deltaPx: restDelta, backdropEdge: restEdge, cover: { visibleDelta: meanAbsDiffImg(restGlass, restHidden), occluders: [] } });
    console.log(`[strict] rest sample Δpx=${restDelta.toFixed(3)} 背板edge=${restEdge.toFixed(2)} ⇒ ${restV.status}（净空设计所定：静止态背后为空）`);
    expect(restV.ok, `[strict] 静止态样本不得判红（背后为空是净空设计属性）：${restV.status} — ${restV.reason}`).toBe(true);
    } finally {
      await context.close();
      await browser.close();
    }
  });
});

test('G4 settings top bar: floating glass back button, content passes behind it, no truncation at top/middle/bottom', async ({ page }) => {
  await openSettings(page);
  // ① 悬空钮：绝对定位 + 48 圆 + 唯一玻璃配方 + 透过档 α
  const btn = await page.evaluate(`(() => {
    const e = document.querySelector('.mobile-page[data-page="settings"] .mobile-back-btn');
    const c = getComputedStyle(e); const r = e.getBoundingClientRect();
    const cs = getComputedStyle(document.body);
    return { position: c.position, w: r.width, h: r.height, radius: c.borderRadius, top: r.top, bottom: r.bottom,
      left: r.left, zIndex: c.zIndex, filter: c.backdropFilter, bg: c.backgroundColor,
      token: cs.getPropertyValue('--nf-glass-input-filter').trim(), opacity: c.opacity, visibility: c.visibility };
  })()`);
  expect(btn.position, 'the back button must be a floating (absolutely positioned) control').toBe('absolute');
  expect(btn.w).toBeCloseTo(48, 0); expect(btn.h).toBeCloseTo(48, 0);
  expect(btn.radius).toBe('50%');
  expect(btn.filter, 'the floating back button must carry the single glass recipe').toBe(btn.token);
  const [,,,ba] = parseColor(btn.bg);
  expect(ba, `back button glass alpha ${ba}`).toBeLessThanOrEqual(0.35);
  expect(btn.top).toBeGreaterThanOrEqual(8);
  expect(Number(btn.zIndex), 'the button must float above the content').toBeGreaterThanOrEqual(2);
  expect(btn.visibility).toBe('visible');

  // ② 钮下没有实底栏：顶部带内不存在不透明（α ≥ .5）且横跨 ≥ 300px 的面
  const bars = await page.evaluate(`(() => {
    const bad = [];
    document.querySelectorAll('.mobile-page[data-page="settings"] *').forEach((e) => {
      const r = e.getBoundingClientRect();
      if (r.width >= 300 && r.height >= 6 && r.height <= 60 && r.top < 100 && r.bottom > 0) {
        const c = getComputedStyle(e);
        const m = (c.backgroundColor || '').match(/rgba?\\(([^)]+)\\)/);
        const a = m ? (m[1].split(/[,\\s/]+/).filter(Boolean).length > 3 ? Number(m[1].split(/[,\\s/]+/).filter(Boolean)[3]) : 1) : 0;
        if (a >= 0.5) bad.push({ tag: e.tagName.toLowerCase() + (e.id ? '#' + e.id : ''), cls: typeof e.className === 'string' ? e.className.trim().split(/\\s+/).slice(0,3).join('.') : '', bg: c.backgroundColor, alpha: a, rect: { top: +r.top.toFixed(1), h: +r.height.toFixed(1) } });
      }
    });
    return bad;
  })()`);
  expect(bars, 'no opaque (alpha >= .5) full-width bar may sit in the settings top band').toEqual([]);

  // ③ 三个滚动位置：滚域上缘贴屏幕顶（无 y=72 硬切线），行不被裁剪祖先切到
  const positions = ['top', 'middle', 'bottom'];
  for (const pos of positions) {
    const st = await page.evaluate(`(() => {
      const q = (s) => document.querySelector(s);
      const sc = document.querySelector('.mobile-page[data-page="settings"] #settings-content');
      const max = sc.scrollHeight - sc.clientHeight;
      sc.scrollTop = ${JSON.stringify(pos)} === 'top' ? 0 : ${JSON.stringify(pos)} === 'middle' ? Math.round(max / 2) : max;
      const srect = sc.getBoundingClientRect();
      const dock = q('#mobile-dock').getBoundingClientRect();
      const btnR = q('.mobile-page[data-page="settings"] .mobile-back-btn').getBoundingClientRect();
      const rows = [...document.querySelectorAll('.mobile-page[data-page="settings"] .sp-panel-group, .mobile-page[data-page="settings"] .set-card')]
        .map((e) => e.getBoundingClientRect()).filter((r) => r.height > 0);
      // 裁剪祖先判据：滚域（sc）自身之外的裁剪盒不得切到**可见行**（抓「返回按钮那一栏」的实底栏/硬切线）
      const outsideCut = [];
      for (const e of document.querySelectorAll('.mobile-page[data-page="settings"] .sp-panel-group, .mobile-page[data-page="settings"] .set-card')) {
        const r = e.getBoundingClientRect();
        if (r.height <= 0 || r.bottom < srect.top || r.top > srect.bottom) continue;
        let n = e.parentElement;
        while (n && n !== document.body) {
          if (n === sc) break;                       // 滚域自己的视口是合法边界
          const c = getComputedStyle(n);
          if (c.overflowY === 'hidden' || c.overflowY === 'clip') {
            const nr = n.getBoundingClientRect();
            if (r.top < nr.top - 1 || r.bottom > nr.bottom + 1) outsideCut.push({ tag: n.tagName.toLowerCase() + (n.id ? '#' + n.id : ''), cls: typeof n.className === 'string' ? n.className.trim().split(/\\s+/).slice(0,2).join('.') : '', clip: [+nr.top.toFixed(1), +nr.bottom.toFixed(1)], row: [+r.top.toFixed(1), +r.bottom.toFixed(1)] });
          }
          n = n.parentElement;
        }
      }
      const visibleRows = rows.filter((r) => r.bottom > srect.top && r.top < srect.bottom);
      const behindBtn = visibleRows.some((r) => r.top < btnR.bottom && r.bottom > btnR.top);
      return { scrollTop: sc.scrollTop, max, scrollHeight: sc.scrollHeight, clientHeight: sc.clientHeight,
        scrollerTop: +srect.top.toFixed(1), scrollerBottom: +srect.bottom.toFixed(1), dockTop: +dock.top.toFixed(1),
        firstRowTop: rows.length ? Math.min(...rows.map((r) => r.top)) : null,
        lastRowBottom: rows.length ? Math.max(...rows.map((r) => r.bottom)) : null,
        btnBottom: +btnR.bottom.toFixed(1),
        behindBtn, outsideCut, visibleCount: visibleRows.length };
    })()`);
    // 滚域上缘必须贴屏幕顶 —— 否则又是一条「返回按钮那一栏下面」的硬切线
    expect(st.scrollerTop, `[${pos}] the settings scroller must start at the screen top (no bar boundary)`).toBeLessThanOrEqual(1.5);
    expect(st.outsideCut, `[${pos}] no clip box outside the scroller may cut a visible settings row (a solid bar would)`).toEqual([]);
    expect(st.visibleCount, `[${pos}] settings rows must be visible`).toBeGreaterThan(0);
    if (pos === 'top') {
      // 静止态：首行不被悬浮钮压住
      expect(st.firstRowTop, `[top] first row top ${st.firstRowTop} must clear the floating back button bottom ${st.btnBottom}`).toBeGreaterThanOrEqual(st.btnBottom);
    }
    if (pos === 'middle') {
      // 中部：内容**从钮背后穿过**（悬空的判据；被实底栏挡住时恒 false）
      expect(st.behindBtn, '[middle] content must pass behind the floating back button (a solid bar would block it)').toBe(true);
    }
    if (pos === 'bottom') {
      // 底部：滚到底（scrollHeight 全可达）且末行完整落在滚域内（不截断）
      expect(st.scrollTop, '[bottom] the settings scroller must reach its end').toBeGreaterThanOrEqual(st.max - 2);
      expect(st.lastRowBottom, `[bottom] last row bottom ${st.lastRowBottom} must be fully inside the scroll viewport (bottom ${st.scrollerBottom})`).toBeLessThanOrEqual(st.scrollerBottom + 0.5);
      expect(st.lastRowBottom, '[bottom] the last row must be reachable above the dock').toBeLessThanOrEqual(st.dockTop + 0.5);
    }
  }
});

test('G5 acceptance shape: mobile settings is a FULL-SCREEN page, never a popup/embedded panel', async ({ page }) => {
  await openSettings(page);
  const shape = await page.evaluate(`(() => {
    const q = (s) => document.querySelector(s);
    const page = q('.mobile-page[data-page="settings"]');
    const pr = page.getBoundingClientRect();
    const modal = q('.mobile-page[data-page="settings"] #settings-modal');
    const mc = getComputedStyle(modal);
    const mr = modal.getBoundingClientRect();
    const overlay = document.getElementById('settings-overlay');
    const oc = overlay ? getComputedStyle(overlay) : null;
    const topAt = (x, y) => { const el = document.elementFromPoint(x, y); const stack = document.elementsFromPoint(x, y).slice(0, 3)
      .map(e => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (typeof e.className === 'string' && e.className ? '.' + e.className.trim().split(/\\s+/).slice(0,2).join('.') : ''));
      return { inside: !!el && page.contains(el), tag: stack[0], stack }; };
    return { vw: window.innerWidth, vh: window.innerHeight,
      liveCount: document.querySelectorAll('.mobile-page.live').length,
      pageRect: { x: pr.x, y: pr.y, w: pr.width, h: pr.height },
      pagePos: getComputedStyle(page).position, pageBg: getComputedStyle(page).backgroundColor,
      modalPosition: mc.position, modalBg: mc.backgroundColor, modalRadius: mc.borderRadius,
      modalTop: +mr.top.toFixed(1), modalHeight: +mr.height.toFixed(1),
      modalBorder: mc.borderTopWidth,
      overlayBg: oc ? oc.backgroundColor : null, overlayPointer: oc ? oc.pointerEvents : null,
      topCenter: topAt(187, 406), topBottom: topAt(187, 730),
      backBtn: topAt(24, 40),
      bodyPadTop: getComputedStyle(q('.mobile-page[data-page="settings"] .mobile-page-body')).paddingTop };
  })()`);
  // 视口 = 作者真实看到的手机形态
  expect(shape.vw).toBe(375); expect(shape.vh).toBe(812);
  // 全屏页：唯一 .live，页盒 = 整个视口
  expect(shape.liveCount).toBe(1);
  expect(shape.pageRect).toEqual({ x: 0, y: 0, w: 375, h: 812 });
  expect(shape.pagePos).toBe('absolute');
  // 非弹窗：收编面是 static、无底、无圆角、无边框，且铺满页体
  expect(shape.modalPosition, 'the adopted settings panel must not be a floating dialog').toBe('static');
  const [,,,ma] = parseColor(shape.modalBg);
  expect(ma, 'the adopted settings panel must have no dialog background').toBeLessThanOrEqual(0.05);
  expect(parseFloat(shape.modalRadius)).toBe(0);
  expect(parseFloat(shape.modalBorder)).toBe(0);
  expect(shape.modalTop).toBeLessThanOrEqual(1.5);
  // 幕布缺席（弹窗形态的标志）
  if (shape.overlayBg !== null) {
    const [,,,oa] = parseColor(shape.overlayBg);
    expect(oa, 'no modal scrim may be painted under the settings page').toBeLessThanOrEqual(0.05);
  }
  // 页体顶让位归零（滚域贴顶），设置内容自己承担静止态让位
  expect(parseFloat(shape.bodyPadTop), 'the settings page body must not reserve a top bar band').toBe(0);
  // 视口中央与底缘命中的都是设置页自身（不是内嵌面板/其他层）
  expect(shape.topCenter.inside, 'the settings page must be the topmost layer in the viewport').toBe(true);
  expect(shape.topBottom.inside, 'the settings page must own the viewport down to the dock').toBe(true);
  expect(shape.backBtn.tag, 'the floating back button must be the topmost element at its own centre').toContain('back-btn');
  expect(shape.pageBg, 'the full-screen page must paint the app ground').toBe('rgb(19, 22, 28)');
});
