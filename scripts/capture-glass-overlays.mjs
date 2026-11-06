#!/usr/bin/env node
// capture-glass-overlays.mjs — 「顶部悬浮面」改前/改后材质证据采集器
// （glass-top-overlays 批 2026-11-06；作者令逐字：
//  「所有悬浮在顶部 可以透出背景的 都用毛玻璃效果 比如消息和联系人的手机端的
//    搜索框 这个要成为标准」）
//
// 为什么要这支脚本：G7（`scripts/check-glass-unify.mjs`）判的是**静态声明**——
// 某个面浮在顶部、底半透明、却没挂模糊。它读不出「那块玻璃压在真实列表上到底
// 长什么样」，也读不出「文字还够不够黑」。本脚本补的就是这两类**只能在真渲染里
// 拿到**的读数：
//   · 面的计算材质：底色 + α、backdrop-filter 的 blur/saturate、细边、浮影；
//   · 面的**实际渲染**：截图区域的主色（mode，抗文字污染）与平均色 ⇒ 背景色调
//     到底有没有透上来（改前的 α .16 洗色底 vs 改后的 α .55 玻璃）；
//   · 文字对比度：面内正文色 vs 渲染主色，按 WCAG 2.1 相对亮度算比值（AA 4.5）。
//
// 装置：零端口、零进程、真 Chromium（与 tests/ 系 spec 同一套自包含夹具配方）——
//   page.route 从**磁盘**喂静态资源（因此 `--root` 可以把改前的检出树整棵喂进来），
//   routeWebSocket 打桩、`localStorage.fm_api_mock_seed` 打桩社交域。不碰宿主实例
//   6138、不写库、不建会话。
//
// 用法（改前 / 改后同一份脚本、同一组脸）：
//   node scripts/capture-glass-overlays.mjs \
//        --root /tmp/before-tree/src/main/resources/web --tag before \
//        --out reports-evidence/glass-top-overlays__<stamp>
//   node scripts/capture-glass-overlays.mjs \
//        --root src/main/resources/web --tag after --out <同一个目录>
//
// 产出（每脸 × 每主题 × 每视口）：
//   <tag>-<face>-<viewport>-<theme>.png      整屏（面在页里的位置）
//   <tag>-<face>-<viewport>-<theme>-face.png 面周围的窄带（裁切高度含面上下的背景）
//   <tag>-measure.json                       全部读数（材质 / 渲染主色 / 对比度）
//
// 退出码：0 = 采集完成（**不做红绿判定**——判定在门禁 G7 与 tests/ 系 spec 里，
// 本脚本只出读数）；1 = 环境/装配错误。

import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join, extname, normalize, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };

const REPO = fileURLToPath(new URL('..', import.meta.url));
const ROOT_ARG = valOf('--root', 'src/main/resources/web');
const WEB = normalize(isAbsolute(ROOT_ARG) ? ROOT_ARG : join(REPO, ROOT_ARG));
const TAG = valOf('--tag', 'after');
const OUT_ARG = valOf('--out', 'reports-evidence/glass-top-overlays');
const OUT = normalize(isAbsolute(OUT_ARG) ? OUT_ARG : join(REPO, OUT_ARG));
const THEMES = (valOf('--themes', 'light,dark') || '').split(',').filter(Boolean);
const ONLY = (valOf('--only', '') || '').split(',').filter(Boolean);

const VIEWPORTS = {
  mobile: { width: 375, height: 812, hasTouch: true },
  desktop: { width: 1440, height: 900, hasTouch: false },
};

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.gif': 'image/gif',
  '.ico': 'image/x-icon', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.otf': 'font/otf',
  '.map': 'application/json', '.wasm': 'application/wasm',
};

const SID = 'glass-overlays-session';

const require = createRequire(import.meta.url);
function loadPlaywright() {
  const tried = [];
  const cands = [];
  if (process.env.NEBFLOW_PW_PATH) cands.push(process.env.NEBFLOW_PW_PATH);
  cands.push('playwright', 'playwright-core', '@playwright/test');
  for (const c of cands) {
    try { return { mod: require(c), from: c }; } catch (e) { tried.push(`${c} (${String(e.message).split('\n')[0]})`); }
  }
  throw new Error('playwright 不可解析：' + tried.join(', '));
}

// ── 静态服务 + WS 打桩（与 tests/mobile-pinned-search.spec.mjs 同一配方）──────
async function wireContext(context) {
  await context.addInitScript(() => {
    const NOW = Date.now();
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    try { Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true }); } catch { }
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify({
      self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '' },
      users: [{ userId: 'u-new', username: 'xintongxue', email: 'xin@example.com', displayName: '新同学', avatar: '' }],
      friends: [
        { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', since: NOW - 86400e3 },
        { userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '', since: NOW - 172800e3 },
      ],
      incoming: [],
      outgoing: [],
      conversations: [
        {
          conversationId: 'c-p', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '' },
          lastMessage: { id: 220, senderId: 'u-p', kind: 'text', body: '部署计划已经就绪', createdAt: NOW - 60e3 },
          unreadCount: 0,
        },
        {
          conversationId: 'c-k', friend: { userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '' },
          lastMessage: { id: 330, senderId: 'me', kind: 'text', body: '部署清单在这里', createdAt: NOW - 120e3 },
          unreadCount: 0,
        },
      ],
      messages: {
        'c-p': [
          { id: 210, senderId: 'me', kind: 'text', body: '周五能交付吗', createdAt: NOW - 3600e3 },
          { id: 220, senderId: 'u-p', kind: 'text', body: '部署计划已经就绪', createdAt: NOW - 60e3 },
        ],
        'c-k': [{ id: 330, senderId: 'me', kind: 'text', body: '部署清单在这里', createdAt: NOW - 120e3 }],
        'g-1': [{ id: 440, senderId: 'u-p', kind: 'text', body: '部署场地已经订好', createdAt: NOW - 1800e3 }],
      },
      groups: [{
        groupId: 'g-1', title: '周末球局', role: 'owner', memberCount: 2,
        lastMessage: { id: 440, senderId: 'u-p', kind: 'text', body: '部署场地已经订好', createdAt: NOW - 1800e3 },
        unreadCount: 0, lastMessageId: 440, createdAt: NOW - 86400e3 * 7,
      }],
      groupInvites: [],
      groupMembers: {
        'g-1': [
          { userId: 'me', username: null, display_name: '我自己', avatar: null, role: 'owner', joinedAt: 0 },
          { userId: 'u-p', username: null, display_name: '分页君', avatar: null, role: 'member', joinedAt: 0 },
        ],
      },
    }));
  });

  await context.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '我自己' }, peers: [] }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await context.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData',
      config: '{"features":{"friends":true}}',
      configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
}

async function bootPage(browser, viewport, theme) {
  const vp = VIEWPORTS[viewport];
  const context = await browser.newContext({
    viewport: { width: vp.width, height: vp.height },
    hasTouch: vp.hasTouch,
    colorScheme: theme,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
    deviceScaleFactor: 1,
  });
  await wireContext(context);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(400);
  return { page, context, errors };
}

/** 手机层根页切换（dock-key 由 navController 动态建；与 tests/_mobile-dock-nav.mjs 同语义）。 */
async function dockTo(page, id) {
  await page.locator(`#mobile-dock .dock-key[data-dock="${id}"]`).click();
  await page.waitForFunction((pid) => {
    const el = document.querySelector(`.mobile-page[data-page="${pid}"]`);
    return !!el && el.classList.contains('live');
  }, id, { timeout: 8000 });
  await page.waitForTimeout(260);
}

/** 回聊天根（dock 中键 = dockHome）：聊天根不是 `.mobile-page`，改等顶栏带进场。 */
async function toChatRoot(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
  await page.waitForFunction(() => {
    const band = document.querySelector('#mobile-fab-layer');
    return !!band && band.offsetParent !== null && document.querySelector('.mh-chrome-btn');
  }, null, { timeout: 8000 });
  await page.waitForTimeout(300);
}

// ── 读数 ──────────────────────────────────────────────────────────────────
const STYLE_KEYS = [
  'backgroundColor', 'backgroundImage', 'backdropFilter', 'webkitBackdropFilter',
  'borderTopColor', 'borderTopWidth', 'borderTopStyle', 'boxShadow', 'color',
  'opacity', 'position', 'borderRadius', 'fontSize', 'fontWeight',
];

/** 面的计算样式 + 令牌 + 几何。`textSel` 给出对比度主体（缺省用面自身）。 */
async function probeFace(page, sel, textSel) {
  return page.evaluate(({ sel, textSel, keys }) => {
    const el = document.querySelector(sel);
    if (!el) return { sel, missing: true };
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    const root = getComputedStyle(document.documentElement);
    const out = {
      sel,
      hasNfGlass: el.classList.contains('nf-glass') || el.closest('.nf-glass') !== null,
      cls: typeof el.className === 'string' ? el.className : null,
      rect: { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) },
      tokens: {
        bg: root.getPropertyValue('--nf-glass-bg').trim(),
        filter: root.getPropertyValue('--nf-glass-filter').trim(),
        blur: root.getPropertyValue('--nf-glass-blur').trim(),
        saturate: root.getPropertyValue('--nf-glass-saturate').trim(),
        border: root.getPropertyValue('--nf-glass-border-color').trim(),
      },
    };
    for (const k of keys) out[k] = cs[k] || null;
    const t = textSel ? (el.querySelector(textSel) || document.querySelector(textSel)) : el;
    if (t) {
      const ts = getComputedStyle(t);
      out.text = {
        sel: textSel,
        color: ts.color,
        opacity: ts.opacity,
        fontSize: ts.fontSize,
        fontWeight: ts.fontWeight,
        content: String((t.value !== undefined && t.value !== '') ? t.value : (t.textContent || '')).slice(0, 60),
      };
    }
    return out;
  }, { sel, textSel, keys: STYLE_KEYS });
}

/** 截图区域的主色 + 平均色（主色抗文字污染）。 */
async function regionStats(page, buf) {
  const b64 = buf.toString('base64');
  return page.evaluate(async (data) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + data;
    await img.decode();
    const c = document.createElement('canvas');
    c.width = img.width; c.height = img.height;
    const ctx = c.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(img, 0, 0);
    const d = ctx.getImageData(0, 0, c.width, c.height).data;
    const buckets = new Map();
    let r = 0, g = 0, b = 0, n = 0;
    for (let i = 0; i < d.length; i += 4) {
      const R = d[i], G = d[i + 1], B = d[i + 2];
      r += R; g += G; b += B; n++;
      const k = ((R >> 3) << 10) | ((G >> 3) << 5) | (B >> 3);
      const cur = buckets.get(k) || { n: 0, r: 0, g: 0, b: 0 };
      cur.n++; cur.r += R; cur.g += G; cur.b += B;
      buckets.set(k, cur);
    }
    let best = null;
    for (const v of buckets.values()) if (!best || v.n > best.n) best = v;
    return {
      px: n,
      mean: { r: +(r / n).toFixed(1), g: +(g / n).toFixed(1), b: +(b / n).toFixed(1) },
      mode: { r: +(best.r / best.n).toFixed(1), g: +(best.g / best.n).toFixed(1), b: +(best.b / best.n).toFixed(1), share: +(best.n / n).toFixed(3) },
      buckets: buckets.size,
    };
  }, b64);
}

/** α / blur / saturate 解析 + WCAG 对比度。 */
function parseAlpha(color) {
  if (!color) return null;
  const m = /rgba?\(([^)]+)\)/.exec(color);
  if (!m) return null;
  const parts = m[1].split(/[\s,/]+/).filter(Boolean);
  if (parts.length < 4) return 1;
  const a = parts[3].endsWith('%') ? parseFloat(parts[3]) / 100 : parseFloat(parts[3]);
  return Number.isFinite(a) ? +a.toFixed(3) : null;
}
function parseFilter(v) {
  if (!v || v === 'none') return { blur: null, saturate: null, raw: v || 'none' };
  const blur = /blur\(\s*([\d.]+)px\s*\)/.exec(v);
  const sat = /saturate\(\s*([\d.]+)\s*\)/.exec(v);
  return { blur: blur ? +blur[1] : null, saturate: sat ? +sat[1] : null, raw: v };
}
function relLum(c) {
  const f = (x) => { const s = x / 255; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
  return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
}
function contrast(a, b) {
  const l1 = relLum(a), l2 = relLum(b);
  const hi = Math.max(l1, l2), lo = Math.min(l1, l2);
  return +((hi + 0.05) / (lo + 0.05)).toFixed(2);
}
function parseColor(c) {
  const m = /rgba?\(([^)]+)\)/.exec(c || '');
  if (!m) return null;
  const p = m[1].split(/[\s,/]+/).filter(Boolean).map(Number);
  return { r: p[0], g: p[1], b: p[2] };
}

/** 注入 sticky 等待条 + 它下面的一段正文（证「背景内容看得见 / 看不见」）。
 *  顺序 = 条在前、正文墙在后，再把滚区滚一段 ⇒ 条被钉在 `top: 8px`、正文从它
 *  底下穿过去（这才是该面的真实形态：改前 α .15 洗色底，正文直接透上来）。 */
async function injectRetryStatus(page) {
  const pinned = await page.evaluate(() => {
    if (document.getElementById('capture-retry-status')) return null;
    const host = document.querySelector('#chat') || document.querySelector('#main') || document.body;
    const bar = document.createElement('div');
    bar.id = 'capture-retry-status';
    bar.className = 'retry-status';
    bar.textContent = '正在重新连接…';
    const filler = document.createElement('div');
    filler.id = 'capture-retry-filler';
    filler.style.cssText = 'padding:12px 16px;font:400 15px/1.75 -apple-system,sans-serif;color:#333;background:transparent;';
    filler.textContent = '部署计划已经就绪。周五交付的清单包含三项：网关回滚脚本、静态门禁读数、以及两页搜索条的材质对照。'
      .repeat(10);
    host.insertBefore(bar, host.firstChild);
    bar.after(filler);
    return { host: host.id || host.tagName };
  });
  await page.waitForTimeout(150);
  // 滚动让正文墙从钉住的条底下通过。
  await page.evaluate(() => {
    const host = document.querySelector('#chat') || document.querySelector('#main') || document.body;
    if (host.scrollHeight > host.clientHeight) host.scrollTop = Math.min(220, host.scrollHeight - host.clientHeight);
  });
  await page.waitForTimeout(320);
  return pinned;
}

// ── 脸定义 ────────────────────────────────────────────────────────────────
// band = 裁切上下留白（包含面上下的背景，才看得出「背景有没有透上来」）。
const FACES = [
  {
    id: 'messages-search', viewport: 'mobile', label: '消息页 · 手机搜索条（点名面）',
    sel: '.mobile-page[data-page="messages"] .mh-search-bar', textSel: '.mh-search-input',
    band: 74,
    open: async (page) => { await dockTo(page, 'messages'); },
  },
  {
    id: 'contacts-search', viewport: 'mobile', label: '联系人页 · 手机搜索条（点名面）',
    sel: '.mobile-page[data-page="contacts"] .mh-search-bar', textSel: '.mh-search-input',
    band: 74,
    open: async (page) => { await dockTo(page, 'contacts'); },
  },
  {
    id: 'mobile-chrome', viewport: 'mobile', label: '手机顶栏（零画带）与其中的玻璃圆钮',
    sel: '#mobile-fab-layer', textSel: '.mh-chrome-btn',
    band: 40,
    open: async (page) => { await toChatRoot(page); },
  },
  {
    id: 'retry-status', viewport: 'mobile', label: '气泡流上方 sticky 等待条（手机）',
    sel: '#capture-retry-status', textSel: '#capture-retry-status',
    band: 60,
    open: async (page) => { await toChatRoot(page); await injectRetryStatus(page); },
  },
  {
    id: 'desktop-header', viewport: 'desktop', label: '桌面顶栏 #header',
    sel: '#header', textSel: null,
    band: 26,
    open: async () => {},
  },
  {
    id: 'canvas-header', viewport: 'desktop', label: '桌面 Canvas 顶部标签条',
    sel: '#canvas-panel .canvas-header', textSel: '.canvas-tab',
    band: 26,
    open: async () => {},
  },
  {
    id: 'retry-status', viewport: 'desktop', label: '气泡流上方 sticky 等待条（桌面）',
    sel: '#capture-retry-status', textSel: '#capture-retry-status',
    band: 60,
    open: async (page) => { await injectRetryStatus(page); },
  },
  {
    id: 'canvas-strip', viewport: 'mobile', label: '手机 canvas 环标签条（具名豁免面）',
    sel: '.mf-strip', textSel: '.mf-strip-label',
    band: 44,
    optional: true,
    open: async (page) => { await dockTo(page, 'canvas'); await page.waitForTimeout(400); },
  },
];

// ── 主体 ──────────────────────────────────────────────────────────────────
async function main() {
  mkdirSync(OUT, { recursive: true });
  const { mod: pwMod, from } = loadPlaywright();
  const pw = pwMod.playwright || pwMod;
  const browser = await pw.chromium.launch({ headless: true });

  const results = {
    tag: TAG, webRoot: WEB, playwrightFrom: from,
    generatedAt: new Date().toISOString(),
    viewports: { mobile: '375x812', desktop: '1440x900' },
    faces: {},
  };

  const faces = FACES.filter((f) => !ONLY.length || ONLY.includes(f.id));

  for (const face of faces) {
    for (const theme of THEMES) {
      const key = `${face.id}|${face.viewport}|${theme}`;
      let session;
      try {
        session = await bootPage(browser, face.viewport, theme);
      } catch (e) {
        results.faces[key] = { id: face.id, viewport: face.viewport, theme, bootError: String(e).split('\n')[0] };
        continue;
      }
      const { page, context, errors } = session;
      try {
        await face.open(page);
        await page.waitForTimeout(220);
        await page.evaluate(() => { if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); });

        const st = await probeFace(page, face.sel, face.textSel);
        if (st.missing) {
          results.faces[key] = { id: face.id, viewport: face.viewport, theme, label: face.label, missing: true, optional: !!face.optional, pageErrors: errors.slice(0, 3) };
          console.log(`  ${theme.padEnd(5)} ${face.viewport.padEnd(7)} ${face.id.padEnd(16)} — 面不在场（${face.optional ? '可选' : '必需'}）`);
          continue;
        }

        const base = `${TAG}-${face.id}-${face.viewport}-${theme}`;
        await page.screenshot({ path: join(OUT, `${base}.png`), animations: 'disabled', caret: 'hide' });

        const vp = VIEWPORTS[face.viewport];
        const y0 = Math.max(0, Math.round(st.rect.y) - face.band);
        const y1 = Math.min(vp.height, Math.round(st.rect.y + st.rect.h) + face.band);
        const clip = { x: 0, y: y0, width: vp.width, height: Math.max(8, y1 - y0) };
        const cropBuf = await page.screenshot({ path: join(OUT, `${base}-face.png`), clip, animations: 'disabled', caret: 'hide' });
        // 面内窄带（去掉 4px 圆角/描边边）：主色与对比度取这里，避免被面上下的
        // 页面底色拉走（面上下的背景另外由 -face.png 那条宽带负责展示）。
        const inset = 4;
        const core = {
          x: Math.max(0, Math.round(st.rect.x) + inset),
          y: Math.max(0, Math.round(st.rect.y) + inset),
          width: Math.max(4, Math.round(st.rect.w) - inset * 2),
          height: Math.max(4, Math.round(st.rect.h) - inset * 2),
        };
        const coreBuf = await page.screenshot({ path: join(OUT, `${base}-core.png`), clip: core, animations: 'disabled', caret: 'hide' });
        const stats = await regionStats(page, coreBuf);
        const bandStats = await regionStats(page, cropBuf);

        const filter = parseFilter(
          st.backdropFilter && st.backdropFilter !== 'none' ? st.backdropFilter : st.webkitBackdropFilter,
        );
        const alpha = parseAlpha(st.backgroundColor);
        const textColor = st.text ? parseColor(st.text.color) : null;

        const read = {
          id: face.id, viewport: face.viewport, theme, label: face.label,
          sel: face.sel, rect: st.rect, clip, core,
          hasNfGlass: st.hasNfGlass, cls: st.cls,
          background: { css: st.backgroundColor, image: st.backgroundImage, alpha },
          filter, border: st.borderTopColor, shadow: st.boxShadow === 'none' ? null : st.boxShadow,
          color: st.color, position: st.position, tokens: st.tokens,
          rendered: stats,
          renderedBand: bandStats,
          text: st.text ? {
            ...st.text,
            contrastVsMode: textColor ? contrast(textColor, stats.mode) : null,
            contrastVsMean: textColor ? contrast(textColor, stats.mean) : null,
          } : null,
          verdict: (() => {
            const isGlass = !!filter.blur && filter.blur >= 32 && alpha !== null && alpha >= 0.45 && alpha <= 0.75;
            const isZeroPaint = (alpha === 0 || st.backgroundColor === 'rgba(0, 0, 0, 0)') && !filter.blur;
            return {
              material: isGlass ? '毛玻璃' : (isZeroPaint ? '零画（带本体不画东西）' : (filter.blur ? `有模糊但 α=${alpha} 跑出区间` : '平涂（无模糊）')),
              glassCompliant: isGlass,
            };
          })(),
          pageErrors: errors.slice(0, 3),
        };
        results.faces[key] = read;
        console.log(`  ${theme.padEnd(5)} ${face.viewport.padEnd(7)} ${face.id.padEnd(16)} α=${String(alpha).padEnd(5)} blur=${String(filter.blur).padEnd(4)} sat=${String(filter.saturate).padEnd(4)} mode=rgb(${stats.mode.r},${stats.mode.g},${stats.mode.b}) ${read.verdict.material}${read.text && read.text.contrastVsMode ? ` 文字对比 ${read.text.contrastVsMode}` : ''}`);
      } catch (e) {
        results.faces[key] = { id: face.id, viewport: face.viewport, theme, label: face.label, captureError: String(e).split('\n')[0] };
        console.log(`  ${theme.padEnd(5)} ${face.viewport.padEnd(7)} ${face.id.padEnd(16)} ✗ ${String(e).split('\n')[0]}`);
      } finally {
        await context.close();
      }
    }
  }

  await browser.close();
  const measurePath = join(OUT, `${TAG}-measure.json`);
  // `--only` 是重采某几面的窄口：已有读数要保留（否则整份 JSON 被 4 条覆盖）。
  if (ONLY.length && existsSync(measurePath)) {
    try {
      const prev = JSON.parse(readFileSync(measurePath, 'utf8'));
      results.faces = { ...prev.faces, ...results.faces };
    } catch { }
  }
  writeFileSync(measurePath, JSON.stringify(results, null, 2));
  console.log(`✓ ${TAG} 采集完成：${Object.keys(results.faces).length} 条读数 → ${measurePath}`);
}

main().catch((e) => { console.error(e); process.exit(1); });
