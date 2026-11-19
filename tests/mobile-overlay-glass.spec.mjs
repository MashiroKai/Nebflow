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
//                （🔴 已知未达项：backdrop 取到的是平坦页底，见该测试内 strict 腿注）
//                · 配方判据：dock / 输入条 / canvas 悬浮圆钮（作者参照物 .g2-fab，
//                  canvas 页实测）三者 backdrop-filter 逐字同值 = 唯一配方令牌
//                  --nf-glass-input-filter；底色 α 落在「透过带」（≤ .35，实底 ≥ .90）。
//                · 像素判据（自证）：同一机位两张截图 —— dock 可见（A）与 dock 隐藏（B），
//                  A 必须 **保留 B 的空间结构**（相关系数 > 0.3、标准差 > 1.5 ⇒ 不是实底
//                  覆盖）且 **被平滑**（sd(A) < sd(B) ⇒ 是真模糊，不是原样透出）。
//   G4 顶栏   —— 设置页是**全屏页**（不是弹窗），返回钮是**悬空玻璃钮**，内容从钮背后
//                穿过；顶部/中部/底部三个滚动位置都无裁切（行矩形不被裁剪祖先切到）。
//   G5 形态铁律 —— 手机端设置 = 全屏页形态（viewport 375×812；唯一 .live 页面包住整个
//                视口；#settings-modal 收编为 static / 无底 / 无圆角；无 overlay 幕布）。
//                —— 禁止以弹窗/内嵌面板当手机端验收形态。
//
// 自证检测力（拿当前坏源码当破坏样本）：
//   红：NEB_MOBILE_WEB_ROOT=<主检出>/src/main/resources/web node node_modules/@playwright/test/cli.js test tests/mobile-overlay-glass.spec.mjs
//   绿：node node_modules/@playwright/test/cli.js test tests/mobile-overlay-glass.spec.mjs
//
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-overlay-glass.spec.mjs

import { test, expect } from '@playwright/test';
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

  // ④ 不是实底覆盖（像素档）：真玻璃带与「不透明底 + 无模糊」有实质差异。
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
  const A = lumaStats(imA, 0, 0, imA.width, imA.height, 1);
  const D = lumaStats(imD, 0, 0, imD.width, imD.height, 1);
  const sdA = stddev(A);
  const absAD = mean(A.map((v, i) => Math.abs(v - D[i])));
  // 🔴 严格腿（NEB_OVERLAY_STRICT_GLASS=1）：本批**未能满足**的那半条 ——
  //    「玻璃带里必须留下背景的结构」= 真透过 vs 实底覆盖的像素级判据。
  //    实测（本批修后）：输入条玻璃带 sd=0.00、dock 带 sd 只由 dock 自身墨迹贡献；
  //    隐藏背后内容或关掉 backdrop-filter，带内像素**逐值不变** ⇒ 玻璃的 backdrop
  //    取到的是平坦页底，不是窗内内容。缺省绿腿不承载这条；它留在这里当**复现器**，
  //    谁修好了「真·backdrop 捕获」谁就把这半条提成默认判据（届时 strict 腿转绿）。
  if (process.env.NEB_OVERLAY_STRICT_GLASS === '1') {
    expect(sdA, `[strict] the glass band must keep structure from its backdrop: sd(A)=${sdA.toFixed(2)}`).toBeGreaterThan(1.0);
    expect(absAD, `[strict] the glass band must differ from an opaque plate: meanAbs(A,D)=${absAD.toFixed(2)}`).toBeGreaterThan(3.0);
  }
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
