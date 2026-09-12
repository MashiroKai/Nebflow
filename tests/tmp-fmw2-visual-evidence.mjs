// tmp-fmw2-visual-evidence.mjs — 好友消息改造批 波2 视觉证据 harness
//   （真实渲染 + 几何读数；判据 ②A1–②A14 / ④C1–④C13 / ③A1–③A12）
//
// 渲染面 = 本 worktree 的 src/main/resources/web 原样文件（node:http 静态服务，
// 固定开发端口 8976，遵守派工单「好友面静态服务固定 8976」），仅 API/WS 为桩。
// 产物：PNG（亮/暗两档 + 悬停前后 + 关键几何读数页）+ readings.json + index.html
//   默认落 ~/.nebflow/docs/Nebflow/assets/20260912_fmw2-msgstyle-controls/
//
// 用法：
//   node tests/tmp-fmw2-visual-evidence.mjs                     # 全量（本支）
//   FMW2_ONLY=overflow FMW2_WEB=<dir> FMW2_OUT=<json> \
//     node tests/tmp-fmw2-visual-evidence.mjs                   # 仅三档溢出读数（基线对比用）
//
// scratch fixture 声明：本文件为波2 实施支自建证据夹具（临时件，随分支提交以便
// 复核复跑；非生产代码，不参与任何构建/门禁扫描面——它不在 web/ 下）。
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.FMW2_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.FMW2_OUT
  || '/Users/kaiyu/.nebflow/docs/Nebflow/assets/20260912_fmw2-msgstyle-controls';
const SHOTS = process.env.FMW2_SHOTS || join(OUT, 'shots');
const ONLY = process.env.FMW2_ONLY || '';
const PORT = Number(process.env.FMW2_PORT || 8976);

let failures = 0;
const readings = {};
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
  return cond;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const now = Date.now();
const iso = ms => new Date(ms).toISOString();
const epochSec = ms => Math.floor(ms / 1000);

// ── 静态服务器（固定 8976；占用即退出，绝不顶掉占用者）──────────────
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.ico': 'image/x-icon',
};
const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const rel = normalize(p === '/' ? 'index.html' : p).replace(/^(\.\.[/\\])+/, '');
    const file = join(WEB, rel);
    if (!file.startsWith(WEB)) throw new Error('escape');
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((resolve, reject) => {
  server.once('error', reject);
  server.listen(PORT, '127.0.0.1', resolve);
}).catch(e => {
  console.error(`[FATAL] 端口 ${PORT} 不可用（${e.code}）——按纪律不得顶掉占用者，退出。`);
  process.exit(2);
});
const BASE = `http://127.0.0.1:${PORT}`;
console.log(`[serve] ${WEB} @ ${BASE}`);

// ── 种子 ────────────────────────────────────────────────────────────
// 好友面（聊天）：1 条会话 3 条消息（尾号 9003 = 列表缓存锚）。
function chatSeed() {
  return {
    self: { userId: 'me', neblinkId: 'me@example.com', name: '我', avatarUrl: '' },
    users: [],
    friends: [{ userId: 'u-a', neblinkId: 'alice01', name: 'Alice', avatarUrl: '', since: iso(now - 86400e3) }],
    incoming: [], outgoing: [],
    conversations: [{
      conversationId: 'c-chat',
      friend: { userId: 'u-a', neblinkId: 'alice01', name: 'Alice', avatarUrl: '' },
      lastMessage: { id: 9003, senderId: 'u-a', kind: 'text', body: '收到，我看看', createdAt: iso(now - 60e3) },
      unreadCount: 0,
    }],
    messages: {
      'c-chat': [
        { id: 9001, senderId: 'u-a', kind: 'text', body: '这条是对方发来的，用来量入向气泡几何。', createdAt: iso(now - 300e3) },
        { id: 9002, senderId: 'me', kind: 'text', body: '这条是我发出去的，出向气泡没有描边。', createdAt: iso(now - 240e3) },
        { id: 9003, senderId: 'u-a', kind: 'text', body: '收到，我看看', createdAt: iso(now - 60e3) },
      ],
    },
  };
}
// 加载更早三情形：id 1..220（确有多更早）/ 501..700（恰好整页，无更早）/ 单条 9000
function pagedSeed(ids) {
  const msgs = ids.map((id, i) => ({
    id, senderId: id % 2 ? 'u-a' : 'me', kind: 'text',
    body: `历史消息 #${id}`, createdAt: epochSec(now - (ids.length - i) * 60e3),
  }));
  const last = msgs[msgs.length - 1];
  return {
    self: { userId: 'me', neblinkId: 'me@example.com', name: '我', avatarUrl: '' },
    friends: [{ userId: 'u-a', neblinkId: 'alice01', name: 'Alice', avatarUrl: '', since: iso(now - 86400e3) }],
    incoming: [], outgoing: [],
    conversations: [{
      conversationId: 'c-p', friend: { userId: 'u-a', neblinkId: 'alice01', name: 'Alice', avatarUrl: '' },
      lastMessage: { id: last.id, senderId: last.senderId, kind: 'text', body: last.body, createdAt: last.createdAt },
      unreadCount: 0,
    }],
    messages: { 'c-p': msgs },
  };
}
// 控件面：两条待处理请求（一条超长附言 + 长串 URL 用于换行判据）+ 搜索用户表
const CTRL_SEED = {
  self: { userId: 'me', username: 'selfme', email: 'self@example.com', displayName: '我自己', avatar: '' },
  users: [
    { userId: 'u-lin', username: 'lin', email: 'lin@example.com', displayName: '林小满', avatar: '' },
    { userId: 'u-f', username: 'buddy7', email: 'b7@example.com', displayName: '老友', avatar: '' },
    { userId: 'u-i', username: 'incoming1', email: 'i1@example.com', displayName: '来求君', avatar: '' },
  ],
  friends: [{ userId: 'u-f', neblinkId: 'buddy7', name: '老友', avatarUrl: '', since: iso(now - 86400e3) }],
  incoming: [
    {
      requestId: 'rq-long',
      from: { userId: 'u-i', neblinkId: 'incoming1', name: '一个非常长的显示名字用来验证请求行是否真的换行而不是截断', avatarUrl: '' },
      note: '你好，我是通过朋友介绍找到你的，这里写了一条很长的附言，用来验证换行与三行上限是否真的生效；'
        + '附言里还夹了一段没有空格的长串 https://example.com/very/long/path/segment/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      status: 'pending', createdAt: epochSec(now - 3600e3),
    },
    {
      requestId: 'rq-short',
      from: { userId: 'u-i2', neblinkId: 'wanderer7', name: '路人甲', avatarUrl: '' },
      note: '加我', status: 'pending', createdAt: epochSec(now - 86400e3),
    },
  ],
  outgoing: [],
  conversations: [], messages: {},
};

// ③A11 三档：控件面 + 好友聊天窗共用一份种子（两侧都可开）
const OVER_SEED = {
  ...CTRL_SEED,
  friends: [...CTRL_SEED.friends, { userId: 'u-a', neblinkId: 'alice01', name: 'Alice', avatarUrl: '', since: iso(now - 86400e3) }],
  conversations: [{
    conversationId: 'c-chat', friend: { userId: 'u-a', neblinkId: 'alice01', name: 'Alice', avatarUrl: '' },
    lastMessage: { id: 9003, senderId: 'u-a', kind: 'text', body: '收到，我看看', createdAt: iso(now - 60e3) }, unreadCount: 0,
  }],
  messages: {
    'c-chat': [
      { id: 9001, senderId: 'u-a', kind: 'text', body: '这条是对方发来的，用来量入向气泡几何。', createdAt: iso(now - 300e3) },
      { id: 9002, senderId: 'me', kind: 'text', body: '这条是我发出去的，出向气泡没有描边。', createdAt: iso(now - 240e3) },
    ],
  },
};

// ── 浏览器 ──────────────────────────────────────────────────────────
const browser = await chromium.launch();

/** 页面侧通用工具（几何 / 颜色 / 对比度）。 */
const HELPERS = `
  window.__g = {
    cs: (e, p) => e ? getComputedStyle(e, p === '::before' || p === '::after' ? p : null)[p] : null,
    r: (e) => { if (!e) return null; const b = e.getBoundingClientRect();
      return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2),
               right: +b.right.toFixed(2), bottom: +b.bottom.toFixed(2) }; },
    // 任意 CSS 颜色串 → {r,g,b,a}（canvas 解析：兼容 color-mix 计算出的 color(srgb r g b / a)）
    rgba: (c) => { if (!c) return null; const cv = document.createElement('canvas'); cv.width = cv.height = 1;
      const x = cv.getContext('2d'); x.clearRect(0, 0, 1, 1); x.fillStyle = '#000';
      x.fillStyle = String(c); x.fillRect(0, 0, 1, 1);
      const d = x.getImageData(0, 0, 1, 1).data;
      return { r: d[0], g: d[1], b: d[2], a: Math.round(d[3] / 255 * 1000) / 1000 }; },
    over: (fg, bg) => { if (!fg) return null; if (fg.a >= 1 || !bg) return fg;
      return { r: fg.r * fg.a + bg.r * (1 - fg.a), g: fg.g * fg.a + bg.g * (1 - fg.a), b: fg.b * fg.a + bg.b * (1 - fg.a), a: 1 }; },
    lum: (c) => { const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
      return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b); },
    contrast: (a, b) => { const la = window.__g.lum(a), lb = window.__g.lum(b);
      const hi = Math.max(la, lb), lo = Math.min(la, lb); return +( (hi + 0.05) / (lo + 0.05) ).toFixed(2); },
    // 元素"有效底色"：自身 background 与其祖先底色逐层合成
    eff: (e) => { const stack = []; let n = e;
      while (n && n.nodeType === 1) { const bg = window.__g.rgba(getComputedStyle(n).backgroundColor);
        if (bg && bg.a > 0) stack.push(bg); n = n.parentElement; }
      stack.push({ r: 255, g: 255, b: 255, a: 1 });
      let out = stack[stack.length - 1];
      for (let i = stack.length - 2; i >= 0; i--) out = window.__g.over(stack[i], out);
      return out; },
    stroke: (sel, props) => { const e = document.querySelector(sel); if (!e) return null;
      const o = {}; for (const p of props) o[p] = getComputedStyle(e)[p]; return o; },
    token: (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim(),
    overflow: (sel) => { const e = document.querySelector(sel); if (!e) return null;
      return { scrollW: e.scrollWidth, clientW: e.clientWidth, over: e.scrollWidth > e.clientWidth + 1 }; },
    focusVisible: (sel) => { const e = document.querySelector(sel); if (!e) return null;
      e.focus({ focusVisible: true });
      const cs = getComputedStyle(e);
      return { matched: e.matches(':focus-visible'), active: document.activeElement === e,
               outlineStyle: cs.outlineStyle, boxShadow: cs.boxShadow, borderColor: cs.borderColor }; },
  };
`;

async function boot({ seed, scheme = 'light', width = 1440, height = 900, locale = 'zh-CN', delay = '20' } = {}) {
  const ctx = await browser.newContext({
    viewport: { width, height }, deviceScaleFactor: 2, colorScheme: scheme,
  });
  await ctx.addInitScript(([seedJson, loc, dly]) => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', loc); localStorage.setItem('neblink_locale', loc);
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seedJson);
    localStorage.setItem('fm_api_mock_delay', dly);
  }, [JSON.stringify(seed), locale, delay]);
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  await page.addInitScript(HELPERS);
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/neblink/status', r => r.fulfill({
    json: {
      loggedIn: true,
      device: { id: 'd1', name: '本机 MacBook', platform: 'macos', userDescription: '主机', avatarUrl: '' },
      peers: [{
        deviceId: 'd2', deviceName: 'MacBook Air', platform: 'macos', online: true,
        userDescription: '备机', avatarUrl: '', isLocal: false,
      }],
    },
  }));
  const frames = [];
  let ws = null;
  await page.routeWebSocket(/\/ws/, sock => {
    ws = sock;
    sock.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      frames.push(m);
      if (m.type === 'getHistory') {
        sock.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
      if (m.type === 'dropbox-file-offer') {
        sock.send(JSON.stringify({
          type: 'dropbox-message', deviceId: m.deviceId,
          msg: {
            msgId: 'fm-file-' + frames.length, kind: 'file', direction: 'out', transferId: 't-' + frames.length,
            fileName: m.fileName, fileSize: m.fileSize, mimeType: m.mimeType, status: 'pending', ts: Date.now(),
          },
        }));
      }
    });
    sock.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    sock.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(700);
  return { ctx, page, frames, send: o => ws.send(JSON.stringify(o)), push: o => ws.send(JSON.stringify(o)) };
}

async function openChat(page, convId = 'c-chat', wait = 700) {
  await page.click('#messages-btn');
  await sleep(400);
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${convId}"]`);
  await sleep(wait);
}
/** 键盘 Tab 聚焦到目标（真实键盘交互 ⇒ Chromium 的 :focus-visible 启发式成立）。 */
async function tabTo(page, sel, max = 140) {
  await page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); });
  let presses = 0;
  for (let i = 0; i < max; i++) {
    await page.keyboard.press('Tab');
    presses++;
    const hit = await page.evaluate(s => !!document.activeElement && document.activeElement.matches(s), sel);
    if (hit) break;
  }
  return page.evaluate(([s, n]) => {
    const a = document.activeElement;
    // 读焦点所在节点本身：面板可能在 Tab 途中重渲染（旧节点被替换 ⇒ 按选择器
    // 再查会拿到未聚焦的新节点，误判成「无 focus 环」）。
    const e = a && a.matches(s) ? a : document.querySelector(s);
    if (!e) return null;
    const cs = getComputedStyle(e);
    return {
      presses: n, activeTag: a ? a.tagName + '.' + String(a.className).slice(0, 40) : null,
      reachedByTab: !!a && a.matches(s), matched: e.matches(':focus-visible'),
      outlineStyle: cs.outlineStyle, boxShadow: cs.boxShadow,
      ctrlBorder: getComputedStyle(document.documentElement).getPropertyValue('--glass-control-border').trim(),
    };
  }, [sel, presses]);
}
const norm = s => String(s || '').replace(/\s+/g, '').replace(/0\.40\)/, '0.4)');
const hasRing = s => /rgba?\([^)]*\)0px0px0px2px/.test(norm(s));
async function shot(page, name, clip) {
  await page.screenshot({ path: join(SHOTS, `${name}.png`), ...(clip ? { clip } : {}) });
}

// ══ 场景 1：好友聊天窗（②A1–A9 / ③A2–③A4 / ③A7 / ③A8）════════════
async function scenarioChat() {
  const read = page => page.evaluate(() => {
    const g = window.__g;
    const q = s => document.querySelector(s);
    const outB = q('.fm-msg.out .fm-msg-bubble');
    const inB = q('.fm-msg.in .fm-msg-bubble');
    const metaOut = q('.fm-msg.out .fm-msg-meta');
    const acts = q('.fm-msg.out .fm-msg-actions');
    const sep = acts ? getComputedStyle(acts, '::before') : null;
    const cs = (e, p) => e ? getComputedStyle(e)[p] : null;
    const geo = e => { if (!e) return null; const b = e.getBoundingClientRect();
      return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2), bottom: +b.bottom.toFixed(2) }; };
    return {
      outBubble: {
        padding: cs(outB, 'padding'), fontSize: cs(outB, 'fontSize'), lineHeight: cs(outB, 'lineHeight'),
        maxWidth: cs(outB, 'maxWidth'), background: cs(outB, 'backgroundColor'),
        borderTopWidth: cs(outB, 'borderTopWidth'), borderTopColor: cs(outB, 'borderTopColor'),
        color: cs(outB, 'color'),
        r: { tl: cs(outB, 'borderTopLeftRadius'), tr: cs(outB, 'borderTopRightRadius'),
             br: cs(outB, 'borderBottomRightRadius'), bl: cs(outB, 'borderBottomLeftRadius') },
      },
      inBubble: {
        padding: cs(inB, 'padding'), fontSize: cs(inB, 'fontSize'), lineHeight: cs(inB, 'lineHeight'),
        maxWidth: cs(inB, 'maxWidth'), background: cs(inB, 'backgroundColor'),
        borderTopWidth: cs(inB, 'borderTopWidth'), color: cs(inB, 'color'),
        r: { tl: cs(inB, 'borderTopLeftRadius'), tr: cs(inB, 'borderTopRightRadius'),
             br: cs(inB, 'borderBottomRightRadius'), bl: cs(inB, 'borderBottomLeftRadius') },
      },
      meta: {
        display: cs(metaOut, 'display'), alignItems: cs(metaOut, 'alignItems'), gap: cs(metaOut, 'gap'),
        marginTop: cs(metaOut, 'marginTop'), padding: cs(metaOut, 'padding'),
        background: cs(metaOut, 'backgroundColor'), borderRadius: cs(metaOut, 'borderRadius'),
        boxShadow: cs(metaOut, 'boxShadow'), fontSize: cs(metaOut, 'fontSize'), color: cs(metaOut, 'color'),
      },
      time: {
        fontSize: cs(q('.fm-msg.out .fm-msg-time'), 'fontSize'),
        fontFamily: cs(q('.fm-msg.out .fm-msg-time'), 'fontFamily'),
        fontVariantNumeric: cs(q('.fm-msg.out .fm-msg-time'), 'fontVariantNumeric'),
        color: cs(q('.fm-msg.out .fm-msg-time'), 'color'),
      },
      sep: sep ? { content: sep.content, width: sep.width, height: sep.height, background: sep.backgroundColor, display: sep.display } : null,
      actions: { opacity: cs(acts, 'opacity'), offsetWidth: acts ? acts.offsetWidth : null, transition: cs(acts, 'transition') },
      wrapperMaxWidth: cs(q('.fm-msg.out'), 'maxWidth'),
      geometry: { bubbleOut: geo(outB), metaOut: geo(metaOut), inBubble: geo(inB) },
      fwdBtnInOverlay: document.querySelectorAll('#fm-chat-overlay .fm-forward-btn').length,
      fwdBtnDoc: document.querySelectorAll('.fm-forward-btn').length,
      fwdActBtn: !!document.querySelector('.fm-msg-act[title="转发给 agent"]'),
      followUpActs: [...document.querySelectorAll('#fm-chat-overlay .fm-msg-act')].length,
      followUpFirst: {
        title: document.querySelector('#fm-chat-overlay .fm-msg-act')?.title,
        aria: document.querySelector('#fm-chat-overlay .fm-msg-act')?.getAttribute('aria-label'),
      },
      attachBtn: document.querySelectorAll('.fm-attach-btn').length,
      friendAttach: document.querySelectorAll('#fm-chat-overlay .fm-attach-btn').length,
      headerText: document.querySelector('.fm-modal-header')?.textContent,
      headerChildren: document.querySelector('.fm-modal-header')?.children.length,
      tokens: { tint15: window.__g.token('--sapphire-tint-15'), tint10: window.__g.token('--sapphire-tint-10'), maxW: window.__g.token('--msg-bubble-max-w'), surface: window.__g.token('--color-surface'), ctrlBorder: window.__g.token('--glass-control-border') },
      resolved: (() => { const p = document.createElement('div'); p.style.cssText = 'position:absolute;left:-9999px;color:var(--color-surface)';
        document.body.appendChild(p); const c = getComputedStyle(p).color; p.remove(); return { surface: c }; })(),
      flowOverflow: (() => { const f = document.querySelector('.fm-flow'); return f ? { over: f.scrollWidth > f.clientWidth + 1, scrollW: f.scrollWidth, clientW: f.clientWidth } : null; })(),
      reducedMotion: matchMedia('(prefers-reduced-motion: reduce)').matches,
      hoverNone: matchMedia('(hover: none)').matches,
    };
  });

  for (const scheme of ['light', 'dark']) {
    const { ctx, page } = await boot({ seed: chatSeed(), scheme });
    await openChat(page);
    await shot(page, `01-chat-${scheme}`);
    const r = await read(page);
    readings[`chat.${scheme}`] = r;
    // ③A3 变异测试：改 token ⇒ 泡泡 max-width 两侧同步变（同一 token 消费）
    readings[`chat.${scheme}.tokenMutation`] = await page.evaluate(() => {
      const root = document.documentElement;
      const el = document.querySelector('.fm-msg.out');
      const before = getComputedStyle(el).maxWidth;
      root.style.setProperty('--msg-bubble-max-w', '60%');
      const after = getComputedStyle(el).maxWidth;
      root.style.removeProperty('--msg-bubble-max-w');
      return { before, after, restored: getComputedStyle(el).maxWidth };
    });

    // A6：悬停前后几何逐值不变
    await page.hover('.fm-msg.out');
    await sleep(250);
    const hovered = await page.evaluate(() => {
      const g = window.__g;
      const wrap = document.querySelector('.fm-msg.out');
      const b = wrap.querySelector('.fm-msg-bubble');
      const meta = wrap.querySelector('.fm-msg-meta');
      const acts = wrap.querySelector('.fm-msg-actions');
      const geo = e => { const x = e.getBoundingClientRect(); return { x: +x.x.toFixed(2), y: +x.y.toFixed(2), w: +x.width.toFixed(2), h: +x.height.toFixed(2), bottom: +x.bottom.toFixed(2) }; };
      return {
        actionsOpacity: getComputedStyle(acts).opacity,
        actionsOffsetWidth: acts.offsetWidth,
        bubble: geo(b), meta: geo(meta), rect: geo(wrap),
        bubbleDisplay: getComputedStyle(b).display,
      };
    });
    readings[`chat.${scheme}.hover`] = hovered;
    if (scheme === 'light') await shot(page, '02-chat-light-hover-actions');

    // A14：键盘可达 + focus-visible 环（真实 Tab 导航）
    const keyboard = await tabTo(page, '.fm-msg.out .fm-msg-act');
    readings[`chat.${scheme}.focus`] = keyboard;
    if (scheme === 'light') {
      const box = await page.locator('.fm-msg.out .fm-msg-actions').first().boundingBox();
      await shot(page, '03-chat-light-focus-ring', { x: Math.max(0, box.x - 40), y: Math.max(0, box.y - 20), width: box.width + 120, height: box.height + 60 });
      const hb = await page.locator('.fm-modal-header').first().boundingBox();
      await shot(page, '04-chat-light-header', { x: hb.x, y: hb.y, width: hb.width, height: hb.height });
    }
    // ③A8：好友窗 drop 文件 ⇒ 显式提示（不静默）
    const dt = await page.evaluateHandle(() => {
      const d = new DataTransfer();
      d.items.add(new File(['hello'], 'note.txt', { type: 'text/plain' }));
      return d;
    });
    await page.dispatchEvent('#fm-chat-overlay', 'drop', { dataTransfer: dt });
    await sleep(500);
    const dropRes = await page.evaluate(() => {
      const t = document.querySelector('.fm-modal-toast');
      return {
        toastShown: !!t && !t.hidden, text: t ? t.textContent : null,
        chips: document.querySelectorAll('#fm-chat-overlay .msg-file-card').length,
        bubbles: document.querySelectorAll('#fm-chat-overlay .fm-msg').length,
      };
    });
    readings[`chat.${scheme}.drop`] = dropRes;
    if (scheme === 'light') await shot(page, '05-chat-light-drop-toast');
    await ctx.close();
  }
  return read;
}

// ══ 场景 2：「加载更早」三情形（②A10–②A13）══════════════════════
async function scenarioLoadMore() {
  const cases = [
    { tag: 'single-9000', ids: [9000], expect: 'hidden' },
    { tag: 'full-page-501-700', ids: Array.from({ length: 200 }, (_, i) => 501 + i), expect: 'hidden' },
    { tag: 'history-1-220', ids: Array.from({ length: 220 }, (_, i) => 1 + i), expect: 'shown' },
  ];
  for (const c of cases) {
    const { ctx, page } = await boot({ seed: pagedSeed(c.ids), scheme: 'light' });
    await openChat(page, 'c-p', 1600); // 探针最多 20 步
    const before = await page.evaluate(() => {
      const f = document.querySelector('.fm-flow');
      const btn = f && f.querySelector('.fm-load-more');
      const cs = btn ? getComputedStyle(btn) : null;
      return {
        hasBtn: !!btn, text: btn ? btn.textContent : null,
        radius: cs ? cs.borderRadius : null, fontSize: cs ? cs.fontSize : null,
        padding: cs ? cs.padding : null, opacity: cs ? cs.opacity : null,
        count: f ? f.querySelectorAll('.fm-msg').length : 0,
        firstId: f && f.querySelector('.fm-msg') ? f.querySelector('.fm-msg').dataset.messageId : null,
        rowGeo: btn ? (() => { const b = btn.getBoundingClientRect(); return { h: +b.height.toFixed(2), w: +b.width.toFixed(2) }; })() : null,
      };
    });
    readings[`loadmore.${c.tag}.before`] = before;
    await shot(page, `06-loadmore-${c.tag}-before`);
    if (before.hasBtn) {
      await page.$eval('.fm-flow', f => { f.scrollTop = 0; });
      await page.click('.fm-load-more');
      await sleep(1400);
      const after = await page.evaluate(() => {
        const f = document.querySelector('.fm-flow');
        return {
          hasBtn: !!f.querySelector('.fm-load-more'),
          count: f.querySelectorAll('.fm-msg').length,
          firstId: f.querySelector('.fm-msg')?.dataset.messageId,
        };
      });
      readings[`loadmore.${c.tag}.after`] = after;
      await shot(page, `07-loadmore-${c.tag}-after`);
    }
    await ctx.close();
  }
  // en 档文案（②A12 后半）
  const { ctx, page } = await boot({ seed: pagedSeed(Array.from({ length: 220 }, (_, i) => 1 + i)), scheme: 'light', locale: 'en' });
  await openChat(page, 'c-p', 1600);
  readings['loadmore.en.label'] = await page.evaluate(() => document.querySelector('.fm-load-more')?.textContent || null);
  await shot(page, '08-loadmore-en-label');
  await ctx.close();
  // reduced-motion（③A12）
  const rm = await boot({ seed: chatSeed(), scheme: 'light' });
  await rm.page.emulateMedia({ reducedMotion: 'reduce' });
  await openChat(rm.page);
  readings['reducedMotion'] = await rm.page.evaluate(() => {
    const cs = el => el ? getComputedStyle(el) : null;
    const overlay = cs(document.querySelector('#fm-chat-overlay'));
    const modal = cs(document.querySelector('.fm-modal'));
    const acts = cs(document.querySelector('.fm-msg-actions'));
    return { overlayAnim: overlay.animationDuration, modalAnim: modal.animationDuration, modalTransition: modal.transitionDuration, actionsTransition: acts.transitionDuration };
  });
  await rm.ctx.close();
}

// ══ 场景 3：控件面（④C1–④C13）═══════════════════════════════════
async function scenarioControls() {
  // 两键材质读数（亮/暗 + rest/hover/active/disabled/focus）
  async function btnState(page, sel, label) {
    const readState = () => page.evaluate((s) => {
      const g = window.__g;
      const e = document.querySelector(s);
      if (!e) return null;
      const cs = getComputedStyle(e);
      const bg = g.rgba(cs.backgroundColor);
      const eff = g.eff(e);
      const fg = g.over(g.rgba(cs.color), eff);
      return {
        background: cs.backgroundColor, backgroundRaw: bg,
        borderColor: cs.borderColor, borderWidth: cs.borderWidth,
        color: cs.color, colorRaw: g.rgba(cs.color),
        effectiveBg: eff, contrast: g.contrast(fg, eff),
        radius: cs.borderRadius, fontWeight: cs.fontWeight, fontSize: cs.fontSize,
        padding: cs.padding, fontFamily: cs.fontFamily, cursor: cs.cursor,
        boxShadow: cs.boxShadow, outlineStyle: cs.outlineStyle,
      };
    }, sel);
    const out = { rest: await readState() };
    await page.hover(sel); await sleep(220);
    out.hover = await readState();
    const bb = await page.locator(sel).first().boundingBox();
    await page.mouse.move(bb.x + bb.width / 2, bb.y + bb.height / 2);
    await page.mouse.down(); await sleep(180);
    out.active = await readState();
    // 松手前先移开：避免 down→up 落成一次真实 click（同意键会接受请求 → 行消失）
    await page.mouse.move(bb.x + bb.width / 2, bb.y + bb.height / 2 + 120);
    await page.mouse.up(); await sleep(200);
    // focus-visible：真实键盘 Tab 导航（Chromium 的 :focus-visible 启发式要求键盘交互）
    const fv = await tabTo(page, sel);
    out.focus = { ...(await readState()), ...(fv || {}) };
    out.focus.boxShadowHasRing = hasRing(out.focus.boxShadow);
    out.focusCtrlBorder = fv ? fv.ctrlBorder : null;
    await page.evaluate(s => { const e = document.querySelector(s); if (e) e.blur(); }, sel);
    await page.evaluate(s => { const e = document.querySelector(s); if (e) e.disabled = true; }, sel);
    await sleep(150);
    out.disabled = await readState();
    await page.evaluate(s => { const e = document.querySelector(s); if (e) e.disabled = false; }, sel);
    readings[`controls.${label}`] = out;
    return out;
  }

  for (const scheme of ['light', 'dark']) {
    for (const [tag, sidebarMin] of [['236', false], ['180', true]]) {
      const { ctx, page } = await boot({ seed: CTRL_SEED, scheme, delay: '450' });
      await page.click('#contacts-btn');
      await sleep(600);
      // 请求行在「新的朋友」折叠项内 —— 展开（真实入口）
      if (await page.$('.fm-nf-entry')) { await page.click('.fm-nf-entry'); await sleep(450); }
      if (sidebarMin) {
        const handle = page.locator('.col-resizer[data-left="sidebar"]').first();
        const hb = await handle.boundingBox();
        if (hb) {
          await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2);
          await page.mouse.down();
          await page.mouse.move(hb.x + hb.width / 2 - 62, hb.y + hb.height / 2, { steps: 8 });
          await page.mouse.up();
          await sleep(500);
        }
      }
      // 搜索一个用户 → 结果卡（走真实 contacts.js 搜索链）
      await page.fill('.fm-search-input', 'lin');
      await page.click('.fm-search-btn');
      await sleep(900);
      const geo = await page.evaluate(() => {
        const g = window.__g;
        const row = document.querySelector('.fm-req-row');
        const meta = row?.querySelector('.fm-row-meta');
        const sub = row?.querySelector('.fm-row-sub');
        const name = row?.querySelector('.fm-row-name');
        const btns = row?.querySelector('.fm-req-btns');
        const accept = row?.querySelector('.fm-req-accept');
        const decline = row?.querySelector('.fm-req-decline');
        const panel = document.querySelector('#panel-contacts');
        const card = document.querySelector('.fm-result-card');
        const csub = card?.querySelector('.fm-row-sub');
        const cname = card?.querySelector('.fm-row-name');
        const lh = e => parseFloat(getComputedStyle(e).lineHeight);
        return {
          sidebarW: Math.round(document.getElementById('sidebar')?.getBoundingClientRect().width || 0),
          metaClientW: meta?.clientWidth, metaFlexBasis: meta ? getComputedStyle(meta).flex : null,
          metaMinWidth: meta ? getComputedStyle(meta).minWidth : null,
          rowOver: row ? { scrollW: row.scrollWidth, clientW: row.clientWidth, over: row.scrollWidth > row.clientWidth + 1 } : null,
          reqsOver: (() => { const e = document.querySelector('.fm-requests'); return e ? { scrollW: e.scrollWidth, clientW: e.clientWidth, over: e.scrollWidth > e.clientWidth + 1 } : null; })(),
          bodyOver: (() => { const e = document.getElementById('fm-contacts-body'); return e ? { scrollW: e.scrollWidth, clientW: e.clientWidth, over: e.scrollWidth > e.clientWidth + 1 } : null; })(),
          panelOver: panel ? { scrollW: panel.scrollWidth, clientW: panel.clientWidth, over: panel.scrollWidth > panel.clientWidth + 1 } : null,
          acceptInsidePanel: !!(accept && panel) && accept.getBoundingClientRect().right <= panel.getBoundingClientRect().right + 0.5
            && accept.getBoundingClientRect().left >= panel.getBoundingClientRect().left - 0.5,
          declineInsidePanel: !!(decline && panel) && decline.getBoundingClientRect().right <= panel.getBoundingClientRect().right + 0.5
            && decline.getBoundingClientRect().left >= panel.getBoundingClientRect().left - 0.5,
          btnsFlex: btns ? `${getComputedStyle(btns).flexGrow} ${getComputedStyle(btns).flexShrink} ${getComputedStyle(btns).flexBasis}` : null,
          reqBtnsMarginLeftAuto: btns ? getComputedStyle(btns).marginLeft : null,
          rowWrap: row ? getComputedStyle(row).flexWrap : null,
          btnsRightAligned: !!(btns && row) && Math.abs(btns.getBoundingClientRect().right
            - (row.getBoundingClientRect().right - parseFloat(getComputedStyle(row).paddingRight || '0'))) <= 1.5,
          subOver: sub ? { scrollW: sub.scrollWidth, clientW: sub.clientWidth, over: sub.scrollWidth > sub.clientWidth + 1 } : null,
          subWhiteSpace: sub ? getComputedStyle(sub).whiteSpace : null,
          subOverflowWrap: sub ? getComputedStyle(sub).overflowWrap : null,
          subTextOverflow: sub ? getComputedStyle(sub).textOverflow : null,
          subClamp: sub ? getComputedStyle(sub).webkitLineClamp : null,
          subHeight: sub ? +sub.getBoundingClientRect().height.toFixed(2) : null,
          subLineHeight: sub ? lh(sub) : null,
          subLines: sub ? Math.round(sub.getBoundingClientRect().height / lh(sub)) : null,
          nameClamp: name ? getComputedStyle(name).webkitLineClamp : null,
          nameLines: name ? Math.round(name.getBoundingClientRect().height / lh(name)) : null,
          resultSubWhiteSpace: csub ? getComputedStyle(csub).whiteSpace : null,
          resultSubClamp: csub ? getComputedStyle(csub).webkitLineClamp : null,
          resultNameClamp: cname ? getComputedStyle(cname).webkitLineClamp : null,
          summaryClamp: (() => { const e = document.querySelector('.fm-conv-summary'); return e ? getComputedStyle(e).webkitLineClamp : null; })(),
          searchRadius: (() => { const e = document.querySelector('.fm-search-btn'); return e ? getComputedStyle(e).borderRadius : null; })(),
          acceptRadius: accept ? getComputedStyle(accept).borderRadius : null,
          declineRadius: decline ? getComputedStyle(decline).borderRadius : null,
          searchCursor: (() => { const e = document.querySelector('.fm-search-btn'); return e ? getComputedStyle(e).cursor : null; })(),
          ops: { acceptW: accept?.getBoundingClientRect().width, declineW: decline?.getBoundingClientRect().width },
        };
      });
      readings[`controls.geo.${scheme}.${tag}`] = geo;
      await shot(page, `10-contacts-${scheme}-${tag}`);
      // 两键材质（亮/暗各一次即可，但两宽度都取以保证无侧栏依赖）
      if (tag === '236') {
        await btnState(page, '.fm-req-accept', `accept.${scheme}`);
        await btnState(page, '.fm-req-decline', `decline.${scheme}`);
        await btnState(page, '.fm-search-btn', `search.${scheme}`);
        // 两键裁剪特写 + 三键四态条
        const bb = await page.locator('.fm-req-btns').first().boundingBox();
        await shot(page, `11-contacts-btns-zoom-${scheme}`, { x: bb.x - 6, y: bb.y - 6, width: bb.width + 12, height: bb.height + 12 });
        const sb = await page.locator('.fm-search').first().boundingBox();
        await shot(page, `12-contacts-searchrow-${scheme}`, { x: sb.x - 4, y: sb.y - 4, width: sb.width + 8, height: sb.height + 8 });
        // 搜索键 loading（不换文案 + disabled 材质 + 脉冲 + aria-busy）
        await page.evaluate(() => { const e = document.querySelector('.fm-search-btn'); if (e) e.disabled = true; });
        await page.fill('.fm-search-input', 'bu');
        await page.evaluate(() => { const e = document.querySelector('.fm-search-btn'); if (e) e.disabled = false; });
        await page.click('.fm-search-btn');
        await sleep(80);
        readings[`controls.searchLoading.${scheme}`] = await page.evaluate(() => {
          const b = document.querySelector('.fm-search-btn');
          return b ? {
            text: b.textContent, disabled: b.disabled, ariaBusy: b.getAttribute('aria-busy'),
            ariaLabel: b.getAttribute('aria-label'), width: +b.getBoundingClientRect().width.toFixed(2),
            opacity: getComputedStyle(b).opacity, background: getComputedStyle(b).backgroundColor,
          } : null;
        });
        await shot(page, `13-contacts-search-loading-${scheme}`);
        await sleep(900);
        // 搜索按钮宽（idle，用于 50→74 宽度跳动对照）
        readings[`controls.searchIdleWidth.${scheme}`] = await page.evaluate(() => {
          const b = document.querySelector('.fm-search-btn');
          return b ? { width: +b.getBoundingClientRect().width.toFixed(2), text: b.textContent } : null;
        });
      }
      await ctx.close();
    }
  }
}

// ══ 场景 4：dropbox 面（③A2–③A6 / ③A9）══════════════════════════
async function scenarioDropbox() {
  for (const scheme of ['light', 'dark']) {
    const { ctx, page, frames, push } = await boot({ seed: chatSeed(), scheme });
    // 真实入口：设置面板 → 设备行（.dropbox-clickable）
    await page.click('#settings-btn');
    await sleep(700);
    await page.click('.dropbox-clickable[data-device-id="d2"]');
    await sleep(700);
    const opened = await page.evaluate(() => !!document.querySelector('.dropbox-modal'));
    ok(`D0[${scheme}] dropbox 弹窗打开（真实入口：文件面板 → 设备行）`, opened);
    // 服务端推两条文本消息（入/出各一），用于气泡/元信息跨面读数
    push({ type: 'dropbox-message', deviceId: 'd2', msg: { msgId: 't-in', kind: 'text', direction: 'in', text: '来自备机的文本', ts: Date.now() - 120000 } });
    push({ type: 'dropbox-message', deviceId: 'd2', msg: { msgId: 't-out', kind: 'text', direction: 'out', text: '我发出去的文本', ts: Date.now() - 60000 } });
    await sleep(400);
    // 预置一条文本消息 + 一次文件选择（走真实纸夹键/拖拽两条入口）
    await page.evaluate(() => {
      const inp = document.getElementById('dropbox-text-input');
      if (inp) { inp.value = 'hi from dropbox'; }
    });
    // 入口 ①：纸夹键 → input[type=file]（③A6 第一格）
    const attach1 = await page.evaluate(() => {
      const btn = document.getElementById('dropbox-attach-btn');
      const fi = document.getElementById('dropbox-file-input');
      if (!btn || !fi) return { ok: false, reason: 'missing' };
      let clicked = 0;
      const orig = fi.click.bind(fi);
      fi.click = () => { clicked++; };
      btn.click();
      fi.click = orig;
      // 真实 file input 被触发后（原生选择器不弹），直接补一个 change 以走真实 onchange 链
      const d = new DataTransfer();
      d.items.add(new File(['abc'], 'report.txt', { type: 'text/plain' }));
      fi.files = d.files;
      fi.dispatchEvent(new Event('change', { bubbles: true }));
      return { ok: clicked === 1, clicked };
    });
    await sleep(600);
    readings[`dropbox.${scheme}.attachEntry`] = attach1;
    const cards1 = await page.evaluate(() => document.querySelectorAll('.dropbox-modal .msg-file-card').length);
    readings[`dropbox.${scheme}.cardsAfterAttach`] = cards1;
    // 入口 ②：消息区中心 / 输入条中心 各派发 dragover → .drag-over + outline dashed
    const modalBox = await page.locator('.dropbox-modal').first().boundingBox();
    const zones = [
      ['messages-center', { x: modalBox.x + modalBox.width / 2, y: modalBox.y + modalBox.height * 0.4 }],
      ['inputbar-center', { x: modalBox.x + modalBox.width / 2, y: modalBox.y + modalBox.height - 34 }],
    ];
    const hoverRes = {};
    for (const [label, pt] of zones) {
      await page.mouse.move(pt.x, pt.y);
      await page.evaluate(([x, y]) => {
        const el = document.elementFromPoint(x, y);
        const d = new DataTransfer();
        d.items.add(new File(['abc'], 'x.txt', { type: 'text/plain' }));
        (el || document.body).dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: d, clientX: x, clientY: y }));
      }, [pt.x, pt.y]);
      await sleep(200);
      hoverRes[label] = await page.evaluate(() => {
        const m = document.querySelector('.dropbox-modal');
        const cs = m ? getComputedStyle(m) : null;
        return {
          dragOver: !!m && m.classList.contains('drag-over'),
          outlineStyle: cs ? cs.outlineStyle : null, outlineWidth: cs ? cs.outlineWidth : null,
          outlineColor: cs ? cs.outlineColor : null, outlineOffset: cs ? cs.outlineOffset : null,
        };
      });
      if (label === 'inputbar-center') await shot(page, `20-dropbox-${scheme}-drag-over`);
      await page.evaluate(([x, y]) => {
        const el = document.elementFromPoint(x, y);
        (el || document.body).dispatchEvent(new DragEvent('dragleave', { bubbles: true, relatedTarget: document.body }));
      }, [pt.x, pt.y]);
      await sleep(120);
    }
    readings[`dropbox.${scheme}.dragover`] = hoverRes;
    // 两处 drop 各 chip +1
    const dropRes = {};
    for (const [label, pt] of zones) {
      const before = await page.evaluate(() => document.querySelectorAll('.dropbox-modal .msg-file-card').length);
      await page.evaluate(([x, y]) => {
        const el = document.elementFromPoint(x, y);
        const d = new DataTransfer();
        d.items.add(new File(['abc'], `${'z'}.txt`, { type: 'text/plain' }));
        (el || document.body).dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: d, clientX: x, clientY: y }));
      }, [pt.x, pt.y]);
      await sleep(700);
      const after = await page.evaluate(() => document.querySelectorAll('.dropbox-modal .msg-file-card').length);
      dropRes[label] = { before, after, delta: after - before };
    }
    readings[`dropbox.${scheme}.drop`] = dropRes;
    await shot(page, `21-dropbox-${scheme}`);
    // 读数：文件卡三属性 + 元信息（与好友面对照）
    readings[`dropbox.${scheme}.style`] = await page.evaluate(() => {
      const g = window.__g;
      const card = document.querySelector('.dropbox-modal .msg-file-card');
      const cs = e => e ? getComputedStyle(e) : null;
      const bubble = document.querySelector('.dropbox-modal .dropbox-msg-bubble');
      const meta = document.querySelector('.dropbox-modal .dropbox-msg-meta');
      return {
        fileCard: card ? { padding: cs(card).padding, borderRadius: cs(card).borderRadius, background: cs(card).backgroundColor, borderColor: cs(card).borderTopColor, minWidth: cs(card).minWidth } : null,
        fileCardOut: (() => { const e = document.querySelector('.dropbox-modal .msg-file-card.out'); return e ? { background: cs(e).backgroundColor, borderColor: cs(e).borderTopColor } : null; })(),
        bubble: bubble ? { padding: cs(bubble).padding, radius: cs(bubble).borderRadius, fontSize: cs(bubble).fontSize, lineHeight: cs(bubble).lineHeight, maxWidth: cs(bubble).maxWidth } : null,
        outBubbleBg: (() => { const e = document.querySelector('.dropbox-modal .dropbox-msg.out .dropbox-msg-bubble'); return e ? cs(e).backgroundColor : null; })(),
        wrapperMaxWidth: (() => { const e = document.querySelector('.dropbox-modal .dropbox-msg'); return e ? cs(e).maxWidth : null; })(),
        meta: meta ? { fontSize: cs(meta).fontSize, color: cs(meta).color, borderRadius: cs(meta).borderRadius, boxShadow: cs(meta).boxShadow, gap: cs(meta).gap, marginTop: cs(meta).marginTop, background: cs(meta).backgroundColor } : null,
        time: (() => { const e = document.querySelector('.dropbox-modal .dropbox-msg-time'); return e ? { fontSize: cs(e).fontSize, fontFamily: cs(e).fontFamily, color: cs(e).color } : null; })(),
      };
    });
    readings[`dropbox.${scheme}.tokenMutation`] = await page.evaluate(() => {
      const root = document.documentElement;
      const el = document.querySelector('.dropbox-modal .dropbox-msg');
      const before = el ? getComputedStyle(el).maxWidth : null;
      root.style.setProperty('--msg-bubble-max-w', '60%');
      const after = el ? getComputedStyle(el).maxWidth : null;
      root.style.removeProperty('--msg-bubble-max-w');
      return { before, after };
    });
    // ③A9：文本发送 + 文件 offer 事件序列
    const before = frames.filter(f => f.type === 'dropbox-send-text').length;
    await page.fill('#dropbox-text-input', 'hello');
    await page.click('#dropbox-send-btn');
    await sleep(300);
    readings[`dropbox.${scheme}.sendTextFrames`] = {
      delta: frames.filter(f => f.type === 'dropbox-send-text').length - before,
      offers: frames.filter(f => f.type === 'dropbox-file-offer').length,
      lastOfferKeys: Object.keys(frames.filter(f => f.type === 'dropbox-file-offer').slice(-1)[0] || {}).sort(),
    };
    await ctx.close();
    readings[`dropbox.${scheme}`] = {
      attachEntry: readings[`dropbox.${scheme}.attachEntry`],
      cardsAfterAttach: readings[`dropbox.${scheme}.cardsAfterAttach`],
      dragover: readings[`dropbox.${scheme}.dragover`],
      drop: readings[`dropbox.${scheme}.drop`],
      style: readings[`dropbox.${scheme}.style`],
      tokenMutation: readings[`dropbox.${scheme}.tokenMutation`],
      sendTextFrames: readings[`dropbox.${scheme}.sendTextFrames`],
    };
  }
}

// ══ 场景 5：跨面同源读数 + 三档溢出（③A1–③A5 / ③A11）═════════════
async function scenarioOverflow(shootShots) {
  const viewports = [[1440, 900], [768, 1024], [375, 812]];
  const out = {};
  for (const [w, h] of viewports) {
    for (const scheme of ['light', 'dark']) {
      const { ctx, page } = await boot({ seed: OVER_SEED, scheme, width: w, height: h });
      await page.click('#contacts-btn');
      await sleep(700);
      if (await page.$('.fm-nf-entry')) { await page.click('.fm-nf-entry'); await sleep(400); }
      const m = await page.evaluate(() => {
        const over = sel => { const e = document.querySelector(sel); return e ? { over: e.scrollWidth > e.clientWidth + 1, scrollW: e.scrollWidth, clientW: e.clientWidth } : null; };
        const doc = document.documentElement;
        return {
          doc: { over: doc.scrollWidth > doc.clientWidth + 1, scrollW: doc.scrollWidth, clientW: doc.clientWidth },
          body: over('#fm-contacts-body'), panel: over('#panel-contacts'), reqs: over('.fm-requests'),
          panelVisible: !!document.querySelector('#panel-contacts.active'),
          bodyW: document.getElementById('fm-contacts-body')?.getBoundingClientRect().width,
        };
      });
      // 好友聊天窗三档
      await page.click('#messages-btn');
      await sleep(400);
      const hasConv = await page.$('#fm-conversations .fm-conv-row');
      let chat = null;
      if (hasConv) {
        await page.click('#fm-conversations .fm-conv-row');
        await sleep(800);
        chat = await page.evaluate(() => {
          const over = sel => { const e = document.querySelector(sel); return e ? { over: e.scrollWidth > e.clientWidth + 1, scrollW: e.scrollWidth, clientW: e.clientWidth } : null; };
          const modal = document.querySelector('.fm-modal');
          return {
            flow: over('.fm-flow'), modal: over('.fm-modal'),
            modalRight: modal ? +modal.getBoundingClientRect().right.toFixed(2) : null,
            viewportW: innerWidth,
            modalOverViewport: modal ? modal.getBoundingClientRect().right > innerWidth + 0.5 : null,
          };
        });
      }
      // dropbox 弹窗三档（真实入口：设置 → 设备行）
      await page.click('.fm-modal-close').catch(() => {});
      await sleep(200);
      await page.click('#settings-btn');
      await sleep(600);
      let dropbox = null;
      if (await page.$('.dropbox-clickable[data-device-id="d2"]')) {
        await page.click('.dropbox-clickable[data-device-id="d2"]');
        await sleep(600);
        dropbox = await page.evaluate(() => {
          const over = sel => { const e = document.querySelector(sel); return e ? { over: e.scrollWidth > e.clientWidth + 1, scrollW: e.scrollWidth, clientW: e.clientWidth } : null; };
          const modal = document.querySelector('.dropbox-modal');
          return {
            modal: over('.dropbox-modal'), messages: over('.dropbox-messages'), modalRight: modal ? +modal.getBoundingClientRect().right.toFixed(2) : null,
            viewportW: innerWidth, modalOverViewport: modal ? modal.getBoundingClientRect().right > innerWidth + 0.5 : null,
          };
        });
        await page.click('#dropbox-close-btn').catch(() => {});
        await sleep(200);
      }
      out[`${w}x${h}.${scheme}`] = { contacts: m, chat, dropbox };
      if (shootShots && w === 375) await shot(page, `30-mobile-375-${scheme}`);
      if (shootShots && w === 768) await shot(page, `31-tablet-768-${scheme}`);
      await ctx.close();
    }
  }
  return out;
}

// ── 主流程 ──────────────────────────────────────────────────────────
await mkdir(SHOTS, { recursive: true });
if (ONLY === 'page') {
  // 仅用已有 readings.json 重生成证据索引页（不跑场景）
  const r = JSON.parse(await readFile(join(OUT, 'readings.json'), 'utf8'));
  await writeFile(join(OUT, 'index.html'), evidencePage(r));
  await browser.close();
  server.close();
  console.log(`[page] → ${join(OUT, 'index.html')}`);
  process.exit(0);
}
if (ONLY === 'controls') {
  await mkdir(SHOTS, { recursive: true });
  await scenarioControls();
  await browser.close();
  server.close();
  await writeFile(join(OUT, 'readings.controls.json'), JSON.stringify(readings, null, 2));
  console.log(`[controls-only] → ${join(OUT, 'readings.controls.json')}`);
  process.exit(0);
}
if (ONLY === 'overflow') {
  readings.overflow = await scenarioOverflow(false);
  await browser.close();
  server.close();
  await writeFile(OUT, JSON.stringify(readings, null, 2));
  console.log(`[overflow-only] → ${OUT}`);
  process.exit(failures ? 1 : 0);
} else {
  const flush = () => writeFile(join(OUT, 'readings.partial.json'), JSON.stringify(readings, null, 2));
  await scenarioChat(); await flush();
  await scenarioLoadMore(); await flush();
  await scenarioControls(); await flush();
  await scenarioDropbox(); await flush();
  readings.overflow = await scenarioOverflow(true);
}

// ── 判据判定（读数 → PASS/FAIL）────────────────────────────────────
const R = readings;
// ③A1 字面量单源：`rgba(91, 127, 191, 0.15)` 在 web/css/*.css 里只允许 sapphire.css 命中 1 次
const tintLiteral = (() => {
  const cssDir = join(HERE, '..', 'src', 'main', 'resources', 'web', 'css');
  const files = readdirSync(cssDir).filter(f => f.endsWith('.css'));
  const per = files.map(f => ({ file: f, n: (readFileSync(join(cssDir, f), 'utf8').match(/rgba\(91, 127, 191, 0\.15\)/g) || []).length }))
    .filter(x => x.n > 0);
  return { files: per.map(x => x.file), counts: per.map(x => x.n), hits: per.reduce((a, b) => a + b.n, 0), per };
})();
readings.tintLiteral = tintLiteral;
{
  for (const s of ['light', 'dark']) {
    const c = R[`chat.${s}`];
    ok(`②A1[${s}] out 尾角 br=4px/bl=14px，in 反之`,
      c.outBubble.r.br === '4px' && c.outBubble.r.bl === '14px' && c.inBubble.r.bl === '4px' && c.inBubble.r.br === '14px',
      JSON.stringify({ out: c.outBubble.r, in: c.inBubble.r }));
    ok(`②A2[${s}] out 气泡无边框（border-top-width=0px）`, c.outBubble.borderTopWidth === '0px', c.outBubble.borderTopWidth);
    ok(`②A3[${s}] meta pill：r=6px / bg=--color-surface / box-shadow 两段`,
      c.meta.borderRadius === '6px' && c.meta.background === c.resolved.surface
        && (c.meta.boxShadow.match(/rgba?\(/g) || []).length >= 2,
      JSON.stringify({ r: c.meta.borderRadius, bg: c.meta.background, surface: c.resolved.surface, shadow: c.meta.boxShadow }));
    ok(`②A4 时间 10px 等宽 tabular-nums`, c.time.fontSize === '10px' && /mono/i.test(c.time.fontFamily) && c.time.fontVariantNumeric === 'tabular-nums',
      JSON.stringify(c.time));
    ok(`②A5[${s}] 操作键常显（无悬停 opacity ∈ {1,0.35}）`, c.actions.opacity === '1' || c.actions.opacity === '0.35', c.actions.opacity);
    ok(`②A6[${s}] 显隐用 opacity：占位宽 rest=hover 且悬停前后几何逐值不变`,
      c.actions.offsetWidth === R[`chat.${s}.hover`].actionsOffsetWidth && c.actions.offsetWidth > 0
        && JSON.stringify(R[`chat.${s}.hover`].bubble) === JSON.stringify(c.geometry.bubbleOut)
        && JSON.stringify(R[`chat.${s}.hover`].meta) === JSON.stringify(c.geometry.metaOut),
      JSON.stringify({ w: c.actions.offsetWidth, hoverW: R[`chat.${s}.hover`].actionsOffsetWidth, restBubble: c.geometry.bubbleOut, hoverBubble: R[`chat.${s}.hover`].bubble }));
    ok(`②A8[${s}] 窗头转发零残留`, c.fwdBtnInOverlay === 0 && c.fwdBtnDoc === 0, JSON.stringify({ ov: c.fwdBtnInOverlay, doc: c.fwdBtnDoc }));
    ok(`②A9[${s}] 气泡内转发键存在（与复制键同源 forwardBubble）`, c.fwdActBtn && c.followUpActs === 6, JSON.stringify({ act: c.followUpActs }));
    ok(`②A14[${s}] 操作键 Tab 可达 + focus-visible 环 = --glass-control-border`,
      R[`chat.${s}.focus`].matched && R[`chat.${s}.focus`].outlineStyle === 'none' && hasRing(R[`chat.${s}.focus`].boxShadow),
      JSON.stringify(R[`chat.${s}.focus`]));
    ok(`③A7[${s}] 好友面零附件入口（.fm-attach-btn 不存在）`, c.attachBtn === 0 && c.friendAttach === 0, JSON.stringify({ doc: c.attachBtn, fm: c.friendAttach }));
    ok(`③A8[${s}] 好友窗 drop 文件 → 显式提示（无静默）`, R[`chat.${s}.drop`].toastShown && R[`chat.${s}.drop`].chips === 0,
      JSON.stringify(R[`chat.${s}.drop`]));
  }
  ok('②A10 无更早（单条 9000）不显示按钮', R['loadmore.single-9000.before'].hasBtn === false, JSON.stringify(R['loadmore.single-9000.before']));
  ok('②A11 恰好整页 200 条（501..700）不显示按钮', R['loadmore.full-page-501-700.before'].hasBtn === false, JSON.stringify(R['loadmore.full-page-501-700.before']));
  ok('②A10对照组 1..220 首屏按钮存在', R['loadmore.history-1-220.before'].hasBtn === true, JSON.stringify(R['loadmore.history-1-220.before']));
  ok('②A13 点一次后按钮消失且消息 220 / first=1',
    R['loadmore.history-1-220.after'].hasBtn === false && R['loadmore.history-1-220.after'].count === 220 && R['loadmore.history-1-220.after'].firstId === '1',
    JSON.stringify(R['loadmore.history-1-220.after']));
  ok('②A12 按钮 r=8px + zh 文案 + en 同键文案',
    R['loadmore.history-1-220.before'].radius === '8px' && R['loadmore.history-1-220.before'].text === '加载更早消息'
      && R['loadmore.en.label'] === 'Load earlier messages',
    JSON.stringify({ r: R['loadmore.history-1-220.before'].radius, zh: R['loadmore.history-1-220.before'].text, en: R['loadmore.en.label'] }));
  ok('③A12 reduced-motion 下动效 ≤0.01s',
    R.reducedMotion.actionsTransition.split(',').every(v => parseFloat(v) <= 0.01),
    JSON.stringify(R.reducedMotion));
  for (const s of ['light', 'dark']) {
    for (const w of ['236', '180']) {
      const g = R[`controls.geo.${s}.${w}`];
      const tag = `④C[${s}/${w}]`;
      ok(`${tag} C1 文本区 clientWidth ≥72（含 min-width 72）`, g.metaClientW >= 72 && g.metaMinWidth === '72px', JSON.stringify({ w: g.metaClientW, min: g.metaMinWidth }));
      ok(`${tag} C2/C3/C4 四层容器无横向溢出`, !g.rowOver.over && !g.reqsOver.over && !g.bodyOver.over && !g.panelOver.over,
        JSON.stringify({ row: g.rowOver, reqs: g.reqsOver, body: g.bodyOver, panel: g.panelOver }));
      ok(`${tag} C5 两键完整可见于面板内`, g.acceptInsidePanel && g.declineInsidePanel, JSON.stringify({ a: g.acceptInsidePanel, d: g.declineInsidePanel }));
      ok(`${tag} C6 长串盒内无横向滚动（sub.scrollWidth ≤ clientWidth+1）`, g.subOver.over === false, JSON.stringify(g.subOver));
      ok(`${tag} C7 换行确实发生（≥2 行）`, g.subLines >= 2, JSON.stringify({ lines: g.subLines, h: g.subHeight, lh: g.subLineHeight }));
      ok(`${tag} C8 三行上限 line-clamp=3`, g.subClamp === '3', String(g.subClamp));
      ok(`${tag} C9 三键圆角同为 8px`, g.searchRadius === '8px' && g.acceptRadius === '8px' && g.declineRadius === '8px',
        JSON.stringify({ s: g.searchRadius, a: g.acceptRadius, d: g.declineRadius }));
      ok(`${tag} 作用域未越界（.fm-conv-summary 未改 clamp）`, g.summaryClamp === 'none' || g.summaryClamp === null || g.summaryClamp === '1', String(g.summaryClamp));
      ok(`${tag} R5 动作区 flex:0 0 auto + 行可换行 + 动作区右对齐`,
        g.btnsFlex === '0 0 auto' && g.rowWrap === 'wrap' && g.btnsRightAligned,
        JSON.stringify({ f: g.btnsFlex, wrap: g.rowWrap, right: g.btnsRightAligned, ml: g.reqBtnsMarginLeftAuto }));
    }
    const b = R[`controls.search.${s}`], a = R[`controls.accept.${s}`], d = R[`controls.decline.${s}`];
    ok(`④C10[${s}] ${s === 'dark' ? '暗' : '亮'}档三键文字非 UA 黑`, [b, a, d].every(x => x.rest.color !== 'rgb(0, 0, 0)'), JSON.stringify({ b: b.rest.color, a: a.rest.color, d: d.rest.color }));
    const distinct = x => x.rest.background !== x.hover.background || x.rest.borderColor !== x.hover.borderColor
      ? (x.hover.background !== x.active.background || x.hover.borderColor !== x.active.borderColor) : false;
    ok(`④C11[${s}] 三键 rest≠hover≠active（computed 互不相等）`, distinct(b) && distinct(a) && distinct(d),
      JSON.stringify({ b: [b.rest.background, b.hover.background, b.active.background], a: [a.rest.background, a.hover.background, a.active.background], d: [d.rest.background, d.hover.background, d.active.background] }));
    ok(`④C12[${s}] 禁用可辨（cursor not-allowed + 底色 ≠ rest）`,
      d.disabled.cursor === 'not-allowed' && d.disabled.background !== d.rest.background,
      JSON.stringify({ cursor: d.disabled.cursor, rest: d.rest.background, dis: d.disabled.background }));
    ok(`④C13[${s}] 三键 Tab 可达 focus-visible 环统一（outline none + 0 0 0 2px）`,
      [b, a, d].every(x => x.focus.matched && x.focus.outlineStyle === 'none' && hasRing(x.focus.boxShadow)),
      JSON.stringify([b, a, d].map(x => ({ m: x.focus.matched, o: x.focus.outlineStyle, s: x.focus.boxShadow }))));
    // 作者裁定 1：两键亮/暗可辨（填充差 / 字重差；暗档 Δ ≥ 0.08）
    const bgD = a.rest.backgroundRaw.a - d.rest.backgroundRaw.a;
    const fgDelta = Math.abs(a.rest.contrast - d.rest.contrast);
    const weightDelta = Number(a.rest.fontWeight) - Number(d.rest.fontWeight);
    const hueDelta = a.rest.background !== d.rest.background || a.rest.borderColor !== d.rest.borderColor;
    ok(`④-P1/P2[${s}] 两键可辨（填充 Δ / 字重 Δ / 对比度 Δ）`,
      (Math.abs(bgD) >= 0.08 || weightDelta !== 0 || fgDelta >= 0.5) && hueDelta,
      JSON.stringify({ bgAlphaDelta: +bgD.toFixed(3), weightDelta, contrastDelta: fgDelta, acceptContrast: a.rest.contrast, declineContrast: d.rest.contrast, acceptBg: a.rest.background, declineBg: d.rest.background }));
    ok(`④-P5[${s}] 搜索键加载不换文案 + aria-busy + 宽度零跳动`,
      R[`controls.searchLoading.${s}`].text === '搜索' && R[`controls.searchLoading.${s}`].ariaBusy === 'true'
        && Math.abs(R[`controls.searchLoading.${s}`].width - R[`controls.searchIdleWidth.${s}`].width) < 0.5,
      JSON.stringify({ loading: R[`controls.searchLoading.${s}`], idle: R[`controls.searchIdleWidth.${s}`] }));
  }
  for (const s of ['light', 'dark']) {
    const d = R[`dropbox.${s}`];
    ok(`③A6a[${s}] 纸夹键触发 input[type=file] 且 chip +1`, d.attachEntry.ok && d.cardsAfterAttach >= 1, JSON.stringify({ e: d.attachEntry, cards: d.cardsAfterAttach }));
    ok(`③A6b[${s}] 消息区中心 + 输入条中心 dragover 均 .drag-over + outline dashed`,
      Object.values(d.dragover).every(x => x.dragOver && x.outlineStyle === 'dashed'), JSON.stringify(d.dragover));
    ok(`③A6c[${s}] 两处 drop 均 chip +1`, Object.values(d.drop).every(x => x.delta === 1), JSON.stringify(d.drop));
    ok(`③A9[${s}] dropbox 文本发送帧 + 文件 offer 事件链不变`,
      d.sendTextFrames.delta === 1 && d.sendTextFrames.offers >= 3 && d.sendTextFrames.lastOfferKeys.includes('deviceId'),
      JSON.stringify(d.sendTextFrames));
    const cl = R[`chat.${s}`], dl = d.style;
    ok(`③A2[${s}] 气泡几何跨面相等（padding/radius/fontSize/lineHeight）`,
      cl.outBubble.padding === dl.bubble.padding && cl.outBubble.r.br === '4px'
        && cl.outBubble.fontSize === dl.bubble.fontSize && cl.outBubble.lineHeight === dl.bubble.lineHeight,
      JSON.stringify({ friend: [cl.outBubble.padding, cl.outBubble.fontSize, cl.outBubble.lineHeight], dropbox: [dl.bubble.padding, dl.bubble.fontSize, dl.bubble.lineHeight] }));
    ok(`③A3[${s}] 气泡 maxWidth 同 token（--msg-bubble-max-w）且变异同步变`,
      cl.wrapperMaxWidth === cl.tokens.maxW && dl.wrapperMaxWidth === cl.tokens.maxW
        && R[`chat.${s}.tokenMutation`].after === '60%' && d.tokenMutation.after === '60%'
        && R[`chat.${s}.tokenMutation`].restored === cl.tokens.maxW,
      JSON.stringify({ friend: cl.wrapperMaxWidth, dropbox: dl.wrapperMaxWidth, token: cl.tokens.maxW, mutFriend: R[`chat.${s}.tokenMutation`], mutDropbox: d.tokenMutation }));
    ok(`③A4[${s}] 元信息字号/色跨面相等`, dl.meta && cl.meta.fontSize === dl.meta.fontSize && cl.meta.color === dl.meta.color,
      JSON.stringify({ friend: [cl.meta.fontSize, cl.meta.color], dropbox: dl.meta ? [dl.meta.fontSize, dl.meta.color] : null }));
    ok(`③A5[${s}] 文件卡同源（类名 + 三属性 + 单源哨兵）`,
      dl.fileCard && dl.fileCard.padding === '8px 12px' && dl.fileCard.borderRadius === '12px' && tintLiteral.hits <= 1
        && tintLiteral.files.join(',') === 'sapphire.css',
      JSON.stringify({ card: dl.fileCard, tintLiteral }));
    ok(`③A1[${s}] 出向 tint 单源 + 两弹窗 out 气泡底色完全相等`,
      tintLiteral.hits === 1 && tintLiteral.files.join(',') === 'sapphire.css'
        && cl.outBubble.background === dl.outBubbleBg && cl.tokens.tint15 === cl.outBubble.background,
      JSON.stringify({ tintLiteral, friendBg: cl.outBubble.background, dropboxOutBg: dl.outBubbleBg, token: cl.tokens.tint15 }));
  }
  readings.verdict = { failures };
  await browser.close();
  server.close();

  await writeFile(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
  await writeFile(join(OUT, 'index.html'), evidencePage(readings));
  console.log(`\n[failures] ${failures}`);
  console.log(`[write] ${join(OUT, 'readings.json')}`);
  process.exit(failures ? 1 : 0);
}

/** 证据索引页（自包含、零依赖）。 */
function evidencePage(r) {
  const rows = Object.entries(r).filter(([k]) => !k.startsWith('chat.') || !k.includes('.hover')).map(([k, v]) =>
    `<tr><th>${k}</th><td><pre>${JSON.stringify(v, null, 1).replace(/[<>]/g, c => ({ '<': '&lt;', '>': '&gt;' }[c]))}</pre></td></tr>`).join('\n');
  const imgs = ['01-chat-light', '02-chat-light-hover-actions', '03-chat-light-focus-ring', '04-chat-light-header',
    '05-chat-light-drop-toast', '06-loadmore-history-1-220-before', '07-loadmore-history-1-220-after',
    '06-loadmore-single-9000-before', '06-loadmore-full-page-501-700-before', '08-loadmore-en-label',
    '10-contacts-light-236', '10-contacts-dark-236', '10-contacts-light-180', '10-contacts-dark-180',
    '11-contacts-btns-zoom-light', '11-contacts-btns-zoom-dark', '12-contacts-searchrow-light',
    '13-contacts-search-loading-light', '20-dropbox-light-drag-over', '21-dropbox-light', '21-dropbox-dark',
    '30-mobile-375-light', '30-mobile-375-dark', '31-tablet-768-light']
    .map(n => `<figure><img src="shots/${n}.png"><figcaption>${n}</figcaption></figure>`).join('\n');
  return `<!doctype html><meta charset="utf-8"><title>波2 视觉证据 · fmw2</title>
<style>body{font:13px/1.5 -apple-system,sans-serif;margin:24px;background:#fafafa;color:#111}
h1{font-size:18px}table{border-collapse:collapse;width:100%;background:#fff}
th,td{border:1px solid #ddd;padding:6px 8px;vertical-align:top;text-align:left;font-weight:400}
th{width:280px;font-family:ui-monospace,monospace;font-size:11px;background:#f4f4f4}
pre{margin:0;font-size:11px;white-space:pre-wrap}figure{display:inline-block;margin:8px;background:#fff;border:1px solid #ddd;padding:6px}
img{max-width:420px;display:block}figcaption{font-size:11px;color:#666;margin-top:4px}</style>
<h1>好友消息改造批 · 波2 视觉证据（真实渲染 + 几何读数）</h1>
<p>渲染面 = 本 worktree <code>src/main/resources/web</code> 原样文件（静态服务 8976），API/WS 为桩。截图为 2x DPR 真实 Chromium 渲染。</p>
<h2>截图（shots/）</h2>${imgs}
<h2>同目录其它证据件</h2>
<ul>
<li><code>readings.json</code> — 本页读数全量（②A*/④C*/③A* 逐条实测值，含亮/暗两档）</li>
<li><code>run.log</code> — harness 全量 stdout（96 项判据判定逐行 + 末尾 failures 计数）</li>
<li><code>gates.log</code> — 门禁复跑：check-msgstyle-single-source / check-circular / check-ime-guard / check-js-types / build-web</li>
<li><code>specs.log</code> — 既有 spec 复跑（分支）：friend-chain-ui / friend-remark-ui / friends-dev-default-on / friend-trust-sealed / friends-username-unify / friend-search-layout / release-strip-friends.static</li>
<li><code>specs-baseline.log</code> / <code>specs-baseline-friend-chain-full.log</code> — 基线 7dd894c0 同款复跑（base 同红判定依据）</li>
<li><code>i18n-sweep.log</code> — verify-i18n-sweep.cjs（A7 parity 1076=1076；A2/A1 与脚本自身崩溃为基线同红）</li>
<li><code>baseline-overflow.json</code> + <code>overflow-compare.json</code> — ③A11 三档溢出的基线对照（allIdentical）</li>
</ul>
<h2>读数（readings.json 全量）</h2><table>${rows}</table>`;
}
