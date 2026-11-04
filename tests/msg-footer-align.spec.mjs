// msg-footer-align.spec.mjs — ⑧ 消息下方 footer（时间 / 徽章 / 复制·转发键）几何自验 harness。
//
// 批：ui-final-four 链 ⑧（作者确认「就是时间 / 模型名 / 复制·引用那一行」）。
// 被测面 = 好友 / 群 / 设备三面共用的**同一条** footer 生产点：
//   js/messages.js:3211 `const meta = el('div','fm-msg-meta')`（徽章 + 时间 + 分隔符 + 动作组）
//   样式 = css/sapphire.css:794-886（共享 pill / time / divider）+ css/friends.css:1394-1459（动作组）
//
// 判据（任务书 §判据 1-6，逐条机械断言）：
//   C1 同一 footer 内所有子项中心 y 与 footer 盒中心 y 的最大偏差 ≤ 0.5px
//   C2 相邻 `.fm-msg-act` 间距两两相等（最大差 ≤ 0.5px）
//   C3 footer 与气泡同轴：盒缘（锚定侧 = 0）+ **内容缘**（两侧 = 0，内距与气泡内容缘对齐）
//   C4 三种消息形态（纯文本 / 带引用牌 / 带图片附件）各验一遍 C1/C2/C3
//   C5 手机档按钮热区 ≥ 44×44（真 hit-test 探针；视觉本体尺寸另报）
//   C6 既有行为不破：时间切换 / 复制反馈 / 转发 / 引用 / 多选入口 / agent 徽章 / forwarded 徽章
//
// 运行（同一脚本跑**两份树** ⇒ 改前必红 / 改后必绿；禁 MODE 分支，判据只有一套）：
//   node tests/msg-footer-align.spec.mjs                                     # 改后（本仓树）
//   FA_WEB_ROOT=<77afefc77 导出的 web 树> node tests/msg-footer-align.spec.mjs  # 改前树腿
// 环境变量：
//   FA_ENGINE   chromium（缺省）｜webkit —— 双引擎各跑一遍
//   FA_WEB_ROOT 被测 web 树（缺省 = 本仓 src/main/resources/web）
//   FA_PORT     静态服务端口（缺省 chromium=8390 / webkit=8391；端口被占即报错退出）
//   FA_OUT      逐项几何读数落盘（readings.json）
//   FA_SHOTS    截图落盘目录（三形态 × 两档）
//   FA_LABEL    读数的树标签（before / after；只进读数，不进判据）
//
// 🔴 诚实申报：本 harness 的「后端」= 静态 server 侧的 `/api/*` 契约镜像 + WS 帧镜像，
//   **不是**生产服务端；覆盖面 = 真实浏览器 + 真实模块图 + 真实 CSS + 真实几何 + 真指针
//   命中测试。零实例依赖（不起 gateway、不碰宿主 :6138/:8080/:8097），静态资源由本文件
//   内置 http server 供给（收尾必关）。

import { chromium, webkit } from 'playwright';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = process.env.FA_WEB_ROOT ? resolve(process.env.FA_WEB_ROOT) : join(ROOT, 'src', 'main', 'resources', 'web');
const ENGINE = process.env.FA_ENGINE === 'webkit' ? 'webkit' : 'chromium';
const PORT = Number(process.env.FA_PORT || (ENGINE === 'webkit' ? 8391 : 8390));
const OUT = process.env.FA_OUT || '';
const SHOTS = process.env.FA_SHOTS || '';
const LABEL = process.env.FA_LABEL || 'after';

// ══════════════════════════════════════════════════════════════════════
// 夹具：一个会话里放齐 3 形态 × 2 方向 + agent 徽章行（footer 是所有消息行共用的
// 同一条生产线 ⇒ 三形态必须同会话同屏可比）。
// ══════════════════════════════════════════════════════════════════════
const EPOCH = Math.floor(Date.now() / 1000);
const iso = (s) => new Date(s * 1000).toISOString();
const CONV = 'cFooter';
const PEER = 'u-peer';
/** 图片附件字节：SVG 带显式 width/height ⇒ `<img>` 有真实 naturalSize（400×300）。 */
const SVG_BYTES = '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="300">'
  + '<rect width="400" height="300" fill="#9fc0f0"/></svg>';
const SVG_ATT = (id, name) => ({ id, name, size: SVG_BYTES.length, mime: 'image/svg+xml', state: 'ready' });

/** 每条消息 = 一形态一方向（id 序号 = 读数键）。 */
const MSGS = [
  { id: 501, kind: 'text', senderId: PEER, body: '对方发来的纯文本消息，正文两行以内。', createdAt: iso(EPOCH - 900) },
  { id: 502, kind: 'text', senderId: 'me', body: '我发出的纯文本消息，正文两行以内。', createdAt: iso(EPOCH - 840) },
  { id: 503, kind: 'text', senderId: PEER, body: `> [引用 #501] 对方发来的纯文本消息\n带引用牌的回复（对方侧）。`, createdAt: iso(EPOCH - 780) },
  { id: 504, kind: 'text', senderId: 'me', body: `> [引用 #502] 我发出的纯文本消息\n带引用牌的回复（我方侧）。`, createdAt: iso(EPOCH - 720) },
  { id: 505, kind: 'text', senderId: PEER, body: '带图片附件（对方侧）', createdAt: iso(EPOCH - 660), attachments: [SVG_ATT('att-fa-in', 'photo-in.png')] },
  { id: 506, kind: 'text', senderId: 'me', body: '带图片附件（我方侧）', createdAt: iso(EPOCH - 600), attachments: [SVG_ATT('att-fa-out', 'photo-out.png')] },
  { id: 507, kind: 'text', senderId: 'me', body: 'Agent 代发的消息（带 agent 徽章）。', createdAt: iso(EPOCH - 540), origin: 'agent' },
];

/** 形态标签（读数键：plain / quote / image）。 */
const FORM_OF = {
  501: 'plain', 502: 'plain', 503: 'quote', 504: 'quote', 505: 'image', 506: 'image', 507: 'agent',
};
const DIR_OF = (m) => (m.senderId === 'me' ? 'out' : 'in');

const SRV = { calls: [] };
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

/** 契约镜像（**唯一**应答点）：静态 server 侧直答 /api/*，而不只依赖 `page.route`
 *  —— 实测 WebKit 下 `page.route('**\/api\/**')` 对开窗后的 keyset 拉取会漏拦截
 *  （请求落到静态 server 变成 404 ⇒ 消息流空态）。服务端应答 = 双引擎同一条路径。 */
function apiAnswer(pathname, method) {
  if (pathname === '/api/neblink/status') {
    return {
      loggedIn: true,
      relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
      device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' },
      peers: [],
    };
  }
  if (pathname === '/api/conversations') {
    return [{
      conversationId: CONV,
      friend: { userId: PEER, neblinkId: 'peerfriend', name: '对齐检查官', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) },
      lastMessage: MSGS[MSGS.length - 1],
      unreadCount: 0,
    }];
  }
  if (pathname === '/api/friends') {
    return { friends: [{ userId: PEER, neblinkId: 'peerfriend', name: '对齐检查官', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) }], incoming: [], outgoing: [] };
  }
  if (/^\/api\/conversations\/[^/]+\/messages$/.test(pathname)) return MSGS;
  return {};
}

const server = createServer(async (req, res) => {
  const p0 = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  if (req.method === 'GET' && /^\/api\/friends\/attachments\//.test(p0)) {
    res.writeHead(200, { 'content-type': 'image/svg+xml' });
    res.end(SVG_BYTES);
    return;
  }
  if (p0.startsWith('/api/')) {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(apiAnswer(p0, req.method)));
    return;
  }
  try {
    const file = join(WEB, p0 === '/' ? 'index.html' : p0);
    if (!file.startsWith(WEB)) { res.writeHead(403); res.end('forbidden'); return; }
    const body = await readFile(file);
    res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((done, bad) => {
  server.once('error', bad);
  server.listen(PORT, '127.0.0.1', done);
}).catch((err) => {
  console.error(`FATAL: 静态服务端口 ${PORT} 起不来（${err.code || err.message}）—— 先 lsof 确认端口空。`);
  process.exit(2);
});
const BASE = `http://127.0.0.1:${PORT}`;

// ══════════════════════════════════════════════════════════════════════
// 读数 / 断言台账（同一套判据跑两份树：改前红、改后绿）
// ══════════════════════════════════════════════════════════════════════
/** 引擎面噪声（**非** UI 缺陷）：WebKit 把被取消的在飞 fetch 直接抛成
 *  `Canceled: Canceled`（无 stack、无代码位点；只在模式切换/重渲染中断请求时出现，
 *  桌面档偶发、手机档零次）。唯一被豁免的形态，其余 pageerror 一律照常判红。 */
const PAGEERROR_IGNORE = [/^Canceled: Canceled$/];

const R = { label: LABEL, engine: ENGINE, webRoot: WEB, readings: {}, assertions: [] };
let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  [${LABEL}/${ENGINE}] ${name}${extra ? '  — ' + extra : ''}`);
  R.assertions.push({ name, pass: !!cond, detail: extra });
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let shotSeq = 0;
async function shot(page, name, locator) {
  if (!SHOTS) return null;
  await mkdir(SHOTS, { recursive: true });
  shotSeq += 1;
  const p = join(SHOTS, `${String(shotSeq).padStart(2, '0')}-${name}.png`);
  if (locator) await locator.screenshot({ path: p });
  else await page.screenshot({ path: p });
  console.log(`SHOT  ${p}`);
  return p;
}

// ══════════════════════════════════════════════════════════════════════
// 页面读数器（页内执行；零外部依赖）
// ══════════════════════════════════════════════════════════════════════
function readFooterGeometry() {
  const num = (v) => Math.round(v * 100) / 100;
  const rectOf = (el) => {
    const b = el.getBoundingClientRect();
    return {
      x: num(b.x), y: num(b.y), w: num(b.width), h: num(b.height),
      left: num(b.left), right: num(b.right), top: num(b.top), bottom: num(b.bottom),
      cx: num(b.left + b.width / 2), cy: num(b.top + b.height / 2),
    };
  };
  const parseColor = (s) => {
    if (!s) return null;
    let m = s.match(/^rgba?\(([^)]+)\)$/);
    if (m) {
      const p = m[1].split(/[,\/\s]+/).filter(Boolean).map(Number);
      return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
    }
    m = s.match(/^color\(srgb ([^)]+)\)$/);
    if (m) {
      const p = m[1].split(/[\/\s]+/).filter(Boolean).map(Number);
      const to255 = (v) => Math.round(Math.max(0, Math.min(1, v)) * 255);
      return { r: to255(p[0]), g: to255(p[1]), b: to255(p[2]), a: p.length > 3 ? p[3] : 1 };
    }
    return null;
  };
  const lum = (c) => {
    const f = (v) => { const x = v / 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
  };
  const over = (fg, bg) => {
    const a = fg.a === undefined ? 1 : fg.a;
    return { r: fg.r * a + bg.r * (1 - a), g: fg.g * a + bg.g * (1 - a), b: fg.b * a + bg.b * (1 - a), a: 1 };
  };
  const contrast = (fg, bg) => {
    const l1 = lum(fg), l2 = lum(bg);
    return num((Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05));
  };
  /** 翻译「有效可见色」= 色值 × opacity，叠在指定底上 ⇒ 与底的真实对比度。 */
  const effectiveContrast = (el, bgEl) => {
    const cs = getComputedStyle(el);
    const bgCS = getComputedStyle(bgEl);
    const bgRaw = parseColor(bgCS.backgroundColor);
    // 底若半透明，叠到页面底色上（再兜一层白）
    const pageBg = parseColor(getComputedStyle(document.body).backgroundColor) || { r: 255, g: 255, b: 255, a: 1 };
    const bg = bgRaw && bgRaw.a < 1 ? over(bgRaw, pageBg) : (bgRaw || pageBg);
    const fgRaw = parseColor(cs.color);
    if (!fgRaw) return null;
    const op = Number(cs.opacity);
    const fg = over({ ...fgRaw, a: fgRaw.a * (Number.isFinite(op) ? op : 1) }, bg);
    return { ratio: contrast(fg, bg), fg: cs.color, opacity: op, bg: `rgba(${Math.round(bg.r)},${Math.round(bg.g)},${Math.round(bg.b)},1)` };
  };
  /** 真 hit-test 探针：从元素中心向四向 1px 步进，量出 `elementFromPoint` 仍命中本件的连续盒。 */
  const hitBox = (el) => {
    const r = el.getBoundingClientRect();
    const cx = r.left + r.width / 2;
    const cy = r.top + r.height / 2;
    const owns = (x, y) => {
      if (x < 0 || y < 0 || x > window.innerWidth - 1 || y > window.innerHeight - 1) return false;
      const t = document.elementFromPoint(x, y);
      return !!t && (t === el || el.contains(t) || (t.namespaceURI === 'http://www.w3.org/2000/svg' && el.contains(t.ownerElement)));
    };
    if (!owns(cx, cy)) return { w: 0, h: 0, centerOwns: false, note: 'center-occluded', at: (() => { const t = document.elementFromPoint(cx, cy); return t ? `${t.tagName}.${t.className}` : 'null'; })(), c: [num(cx), num(cy)], rect: { x: num(r.x), y: num(r.y), w: num(r.width), h: num(r.height) }, vp: [window.innerWidth, window.innerHeight] };
    const MAX = 30;
    let l = 0; let rt = 0; let up = 0; let dn = 0;
    while (l < MAX && owns(cx - l - 1, cy)) l++;
    while (rt < MAX && owns(cx + rt + 1, cy)) rt++;
    while (up < MAX && owns(cx, cy - up - 1)) up++;
    while (dn < MAX && owns(cx, cy + dn + 1)) dn++;
    return { w: num(l + rt + 1), h: num(up + dn + 1), centerOwns: true, left: l, right: rt, up, dn };
  };
  /** 内容盒缘（bubble 的 padding+border 内缘）。 */
  const contentBox = (el) => {
    const b = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    const bl = parseFloat(cs.borderLeftWidth) || 0;
    const br = parseFloat(cs.borderRightWidth) || 0;
    const pl = parseFloat(cs.paddingLeft) || 0;
    const pr = parseFloat(cs.paddingRight) || 0;
    return { left: num(b.left + bl + pl), right: num(b.right - br - pr), padL: pl, padR: pr, borderL: bl, borderR: br };
  };

  const out = [];
  for (const wrap of document.querySelectorAll('.fm-flow .fm-msg')) {
    const meta = wrap.querySelector('.fm-msg-meta');
    if (!meta) continue;
    const forms = wrap.querySelector('.fm-quote-block') ? 'quote'
      : (wrap.querySelector('.fm-att-list') ? 'image'
        : (wrap.querySelector('.fm-msg-agent-badge') ? 'agent' : 'plain'));
    const metaRect = rectOf(meta);
    const metaCS = getComputedStyle(meta);
    const metaPadL = parseFloat(metaCS.paddingLeft) || 0;
    const metaPadR = parseFloat(metaCS.paddingRight) || 0;
    const children = [...meta.children].map((c) => {
      const r = rectOf(c);
      const cs = getComputedStyle(c);
      return {
        tag: c.tagName.toLowerCase(), cls: c.className, w: r.w, h: r.h,
        cy: r.cy, dCy: num(Math.abs(r.cy - metaRect.cy)),
        display: cs.display, lineHeight: cs.lineHeight, fontSize: cs.fontSize,
      };
    });
    const buttons = [...meta.querySelectorAll('.fm-msg-act')].map((b) => ({ ...rectOf(b), hit: hitBox(b) }));
    const gaps = [];
    for (let i = 1; i < buttons.length; i++) gaps.push(num(buttons[i].left - buttons[i - 1].right));
    const bubble = wrap.querySelector('.fm-msg-bubble');
    const time = meta.querySelector('.fm-msg-time');    const bubbleRect = bubble ? rectOf(bubble) : null;
    const bubbleContent = bubble ? contentBox(bubble) : null;
    const dir = wrap.classList.contains('out') ? 'out' : 'in';
    out.push({
      id: wrap.dataset.messageId,
      form: forms,
      dir,
      meta: {
        ...metaRect,
        padL: metaPadL, padR: metaPadR,
        contentLeft: num(metaRect.left + metaPadL),
        contentRight: num(metaRect.right - metaPadR),
        bg: metaCS.backgroundColor, shadow: metaCS.boxShadow, radius: metaCS.borderRadius,
        border: metaCS.borderTopWidth + ' ' + metaCS.borderTopStyle + ' ' + metaCS.borderTopColor,
      },
      children,
      maxChildCenterDev: children.length ? num(Math.max(...children.map((c) => c.dCy))) : 0,
      buttons,
      gaps,
      bubble: bubbleRect ? {
        ...bubbleRect,
        content: bubbleContent,
        bg: getComputedStyle(bubble).backgroundColor,
        border: getComputedStyle(bubble).borderTopWidth + ' ' + getComputedStyle(bubble).borderTopStyle,
        ring: getComputedStyle(bubble).boxShadow,
      } : null,
      deltas: bubbleRect ? {
        // ① 盒缘：锚定侧（出向右 / 入向左）与气泡盒缘之差（应 0）
        outerAnchor: num(dir === 'out' ? bubbleRect.right - metaRect.right : metaRect.left - bubbleRect.left),
        outerFar: num(dir === 'out' ? metaRect.left - bubbleRect.left : bubbleRect.right - metaRect.right),
        // ② 锚定侧**内容缘**绝对坐标之差（应 0：footer 内容与气泡内容同列）
        contentAnchor: num(dir === 'out'
          ? num(metaRect.right - metaPadR) - bubbleContent.right
          : num(metaRect.left + metaPadL) - bubbleContent.left),
        // ③ 两侧**内距**与气泡内距之差（应 0：内距与气泡对齐）
        insetLeft: num(metaPadL - (bubbleContent.borderL + bubbleContent.padL)),
        insetRight: num(metaPadR - (bubbleContent.borderR + bubbleContent.padR)),
        // ④ 参照读数（不受判据约束，只进诊断表）
        contentLeft: num(num(metaRect.left + metaPadL) - bubbleContent.left),
        contentRight: num(num(metaRect.right - metaPadR) - bubbleContent.right),
      } : null,
      type: {
        time: time ? { fontSize: getComputedStyle(time).fontSize, family: getComputedStyle(time).fontFamily.split(',')[0], contrast: effectiveContrast(time, meta), hit: hitBox(time) } : null,
        meta: { fontSize: metaCS.fontSize, color: metaCS.color },
        body: bubble ? { fontSize: getComputedStyle(bubble).fontSize } : null,
      },
    });
  }
  return out;
}

// ══════════════════════════════════════════════════════════════════════
// 页面 boot（桌面 / 手机两档）
// ══════════════════════════════════════════════════════════════════════
async function boot(browser, profile) {
  const desktop = profile === 'desktop';
  const ctx = await browser.newContext(Object.assign(
    { viewport: desktop ? { width: 1280, height: 900 } : { width: 390, height: 844 }, colorScheme: 'light', deviceScaleFactor: desktop ? 1 : 2 },
    desktop ? {} : { isMobile: true, hasTouch: true },
  ));
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    // 剪贴板桩（headless 无 granted 权限面，WebKit 下 clipboard.write 需用户手势 ⇒ 桩补齐
    // write/read；行为链 = 真 click → 真 handler → 真 .copied，不伪造 UI 态）
    try {
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: {
          writeText: async () => {}, readText: async () => '',
          write: async () => {}, read: async () => [],
        },
      });
    } catch { /* 只读环境忽略 */ }
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(`${e.message}\n${(e.stack || '').split('\n').slice(0, 4).join(' | ')}`));

  // 🔴 API 应答**不**走 `page.route`：实测 WebKit 漏拦截开窗后的 keyset 拉取（请求落到静态
  // server ⇒ 404 ⇒ 消息流空态）⇒ 改由静态 server 直答（文件上部 `apiAnswer`），双引擎同一条路径。


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
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 20000 });
  await sleep(700);

  if (desktop) {
    await page.click('#messages-btn');
    await sleep(500);
  } else {
    await page.waitForSelector('body.mobile-layer', { timeout: 10000 });
    await page.click('#mobile-dock .dock-key[data-dock="messages"]');
    await page.waitForFunction(() => {
      const el = document.querySelector('.mobile-page[data-page="messages"]');
      return !!el && el.classList.contains('live');
    }, null, { timeout: 10000 });
    await sleep(400);
  }
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${CONV}"]`);
  await page.waitForFunction((n) => document.querySelectorAll('.fm-flow .fm-msg').length >= n, MSGS.length, { timeout: 15000 });
  // 附件字节到位（<img> 有 naturalSize 后卡片几何才稳定）
  await page.waitForFunction(() => {
    const imgs = [...document.querySelectorAll('.fm-flow .fm-att-list img')];
    return imgs.length >= 2 && imgs.every((i) => i.complete && i.naturalWidth > 0);
  }, null, { timeout: 15000 }).catch(() => console.log('WARN 附件卡片未全部出帧（不影响 footer 几何）'));
  await sleep(400);
  return { ctx, page, pageErrors };
}

async function measureAll(page) {
  const all = [];
  // 布局先静止（首帧消息流可能仍在按帧重排：首条消息的滚动会落到 body 而非 flow）
  await sleep(350);
  for (const m of MSGS) {
    let row = null;
    // 逐行重试：图片卡片字节到达会改变上方行高 ⇒ 滚动锚定把目标行挪走（实测）。
    // 判据 = 目标 footer 首键中心落在视口内且 hit-test 命中自身；拿不到就重滚再量。
    for (let attempt = 0; attempt < 4; attempt++) {
      await page.evaluate((id) => {
        const el = document.querySelector(`.fm-flow .fm-msg[data-message-id="${id}"]`);
        if (el) el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' });
      }, m.id);
      await sleep(180);
      const rows = await page.evaluate(readFooterGeometry);
      row = rows.find((r) => String(r.id) === String(m.id)) || null;
      const hitOk = row && row.buttons.length > 0 && row.buttons.every((b) => b.hit && b.hit.centerOwns);
      if (row && hitOk) break;
      await sleep(220);
    }
    if (row) all.push(row);
  }
  return all;
}

// ══════════════════════════════════════════════════════════════════════
// 判据 C1 / C2 / C3（对每条消息各算一遍）
// ══════════════════════════════════════════════════════════════════════
function judge(row, tag) {
  const res = {};
  res.c1 = row.maxChildCenterDev;
  res.gaps = row.gaps;
  res.gapSpread = row.gaps.length ? Math.round((Math.max(...row.gaps) - Math.min(...row.gaps)) * 100) / 100 : 0;
  res.deltas = row.deltas;
  res.hit = row.buttons.map((b) => ({ w: b.hit.w, h: b.hit.h }));
  return res;
}

function assertGeometry(rows, tag) {
  console.log(`\n── ${tag} · 逐条几何读数`);
  for (const row of rows) {
    const j = judge(row, tag);
    const d = row.deltas || {};
    console.log(`READ  ${tag} #${row.id} ${row.form}/${row.dir} c1=${j.c1} gaps=${JSON.stringify(j.gaps)} `
      + `outerAnchor=${d.outerAnchor} contentAnchor=${d.contentAnchor} insetL=${d.insetLeft} insetR=${d.insetRight} `
      + `time=${row.type.time ? row.type.time.fontSize : 'n/a'} cRatio=${row.type.time && row.type.time.contrast ? row.type.time.contrast.ratio : 'n/a'} `
      + `btnHit=${JSON.stringify(j.hit)} timeHit=${row.type.time && row.type.time.hit ? `${row.type.time.hit.w}x${row.type.time.hit.h}` : 'n/a'}`);
  }

  const maxC1 = rows.length ? Math.max(...rows.map((r) => r.maxChildCenterDev)) : Infinity;
  ok(`C1 [${tag}] footer 子项中心 y 与 footer 盒中心 y 最大偏差 ≤0.5px`,
    rows.length > 0 && maxC1 <= 0.5, `实测最大偏差 = ${maxC1}px（阈值 0.5）`);

  const worstSpread = rows.length
    ? Math.max(...rows.map((r) => Math.round((Math.max(...r.gaps) - Math.min(...r.gaps)) * 100) / 100)) : Infinity;
  ok(`C2 [${tag}] 相邻 .fm-msg-act 间距两两相等（最大差 ≤0.5px）`,
    rows.length > 0 && worstSpread <= 0.5, `逐条间距列表 = ${JSON.stringify(rows.map((r) => r.gaps))}；最大差 = ${worstSpread}px`);

  const withD = rows.filter((r) => r.deltas);
  const outerBad = withD.filter((r) => Math.abs(r.deltas.outerAnchor) > 0.5);
  ok(`C3a [${tag}] footer 盒缘 = 气泡盒缘（锚定侧：出向右 / 入向左，≤0.5px）`,
    withD.length === rows.length && outerBad.length === 0,
    `不达标 ${outerBad.length} 条 ${JSON.stringify(outerBad.map((r) => ({ id: r.id, dir: r.dir, d: r.deltas.outerAnchor })))}`);

  const anchorBad = withD.filter((r) => Math.abs(r.deltas.contentAnchor) > 0.5);
  ok(`C3b [${tag}] footer 内容缘 = 气泡内容缘（锚定侧，≤0.5px）`,
    withD.length === rows.length && anchorBad.length === 0,
    `不达标 ${anchorBad.length} 条 ${JSON.stringify(anchorBad.map((r) => ({ id: r.id, dir: r.dir, d: r.deltas.contentAnchor })))}`);

  const insetBad = withD.filter((r) => Math.abs(r.deltas.insetLeft) > 0.5 || Math.abs(r.deltas.insetRight) > 0.5);
  ok(`C3c [${tag}] footer 两侧内距 = 气泡两侧内距（左/右，≤0.5px）`,
    withD.length === rows.length && insetBad.length === 0,
    `不达标 ${insetBad.length} 条 ${JSON.stringify(insetBad.map((r) => ({ id: r.id, L: r.deltas.insetLeft, R: r.deltas.insetRight })))}`);

  return { maxC1, worstSpread };
}

// ══════════════════════════════════════════════════════════════════════
// 主流程
// ══════════════════════════════════════════════════════════════════════
const browser = await (ENGINE === 'webkit' ? webkit : chromium).launch();
const result = {};

try {
  // ── 桌面档 ──────────────────────────────────────────────────────────
  const D = await boot(browser, 'desktop');
  const dRows = await measureAll(D.page);
  ok('夹具 [desktop] 三形态 × 两方向 + agent 徽章行全部在册（且 footer 键可按到）',
    dRows.length === MSGS.length && dRows.every((r) => r.buttons.every((b) => b.hit.centerOwns))
    && ['plain', 'quote', 'image'].every((f) => dRows.some((r) => r.form === f))
    && ['out', 'in'].every((d) => dRows.some((r) => r.dir === d)),
    `实际 = ${JSON.stringify(dRows.map((r) => `${r.id}:${r.form}/${r.dir}${r.buttons.every((b) => b.hit.centerOwns) ? '' : '/OFF-VIEWPORT'}`))}`);

  result.desktop = assertGeometry(dRows, 'desktop');
  R.readings.desktop = dRows;

  // 三形态逐项（C4）：形态内 1/2/3 全过
  for (const form of ['plain', 'quote', 'image']) {
    const sub = dRows.filter((r) => r.form === form);
    const c1 = Math.max(...sub.map((r) => r.maxChildCenterDev));
    const spread = Math.max(...sub.map((r) => Math.round((Math.max(...r.gaps) - Math.min(...r.gaps)) * 100) / 100));
    const dOk = sub.every((r) => r.deltas && Math.abs(r.deltas.outerAnchor) <= 0.5
      && Math.abs(r.deltas.contentAnchor) <= 0.5
      && Math.abs(r.deltas.insetLeft) <= 0.5 && Math.abs(r.deltas.insetRight) <= 0.5);
    ok(`C4 [desktop/${form}] 形态内 C1/C2/C3 三项全过（${sub.length} 条：${sub.map((r) => r.dir).join('/')}）`,
      c1 <= 0.5 && spread <= 0.5 && dOk,
      `c1max=${c1} gapSpread=${spread} 对齐=${dOk}`);
  }

  // 时间 / 正文层级读数（诊断项 d，不作独立断言，只进读数）
  const anyRow = dRows[0];
  console.log(`READ  desktop.type.time=${JSON.stringify(anyRow.type.time)} meta=${JSON.stringify(anyRow.type.meta)} body=${JSON.stringify(anyRow.type.body)}`);
  console.log(`READ  desktop.glass.meta=${JSON.stringify({ bg: anyRow.meta.bg, shadow: anyRow.meta.shadow, radius: anyRow.meta.radius, border: anyRow.meta.border })}`);
  const bubbleGlass = dRows.filter((r) => r.bubble).map((r) => ({ id: r.id, dir: r.dir, bg: r.bubble.bg, border: r.bubble.border }));
  console.log(`READ  desktop.glass.bubble=${JSON.stringify(bubbleGlass)}`);

  // 截图（三形态 + agent 行）
  for (const r of dRows) {
    await shot(D.page, `desktop-${r.form}-${r.dir}`, D.page.locator(`.fm-flow .fm-msg[data-message-id="${r.id}"]`));
  }
  await shot(D.page, 'desktop-full');

  // ── C6 既有行为（桌面档 = 行为面主检）───────────────────────────────
  const behavior = await page_eval_behavior(D.page);
  ok('C6 [desktop] 时间切换：.fm-msg-time 可点且文本随 12/24 制重排（data-ts-text 在位）',
    behavior.time.before !== behavior.time.after && behavior.time.hasDataTsText, JSON.stringify(behavior.time));
  ok('C6 [desktop] 复制反馈：click ⇒ .copied + 对勾图标（1500ms 后复位）',
    behavior.copy.copied === true && behavior.copy.iconAfter === 'check' && behavior.copy.iconFinal === 'copy', JSON.stringify(behavior.copy));
  ok('C6 [desktop] 转发：click ⇒ 既有 toast「已加入输入框，可附言后发送」+ forwarded 徽章出现',
    behavior.forward.toast === '已加入输入框，可附言后发送' && behavior.forward.badge === true, JSON.stringify(behavior.forward));
  ok('C6 [desktop] 引用 + 多选入口：右键菜单四项齐备（复制/转发给 agent/引用/多选转发）',
    behavior.menu.labels.length === 4 && ['复制', '转发给 agent', '引用', '多选转发'].every((l) => behavior.menu.labels.includes(l)),
    JSON.stringify(behavior.menu.labels));
  ok('C6 [desktop] 多选入口：进入多选后勾选面在位，Esc/退出后归零',
    behavior.select.checks > 0 && behavior.select.after === 0, JSON.stringify(behavior.select));
  ok('C6 [desktop] agent 徽章在位（Agent 代发行）', behavior.agent.badge === true, JSON.stringify(behavior.agent));
  const dErrs = D.pageErrors.filter((m) => !PAGEERROR_IGNORE.some((re) => re.test(String(m).split('\n')[0])));
  ok('C6 [desktop] 无 pageerror（引擎面 Canceled 噪声除外，见 PAGEERROR_IGNORE）',
    dErrs.length === 0, JSON.stringify(dErrs.slice(0, 3)));

  // 行为改动后的几何复测（转发徽章出现 ⇒ footer 内多一颗徽章，C1 仍须 0）
  const dRows2 = await measureAll(D.page);
  const maxC1b = dRows2.length ? Math.max(...dRows2.map((r) => r.maxChildCenterDev)) : Infinity;
  ok('C6 [desktop] 徽章态介入后 C1 仍 ≤0.5px（徽章出现不破坏同轴）', dRows2.length > 0 && maxC1b <= 0.5,
    `实测 = ${maxC1b}px（行数 ${dRows2.length}）`);
  await D.ctx.close();

  // ── 手机档 ──────────────────────────────────────────────────────────
  const M = await boot(browser, 'mobile');
  const mRows = await measureAll(M.page);
  ok('夹具 [mobile] 三形态 × 两方向全部在册（body.mobile-layer；footer 键可按到）',
    mRows.length === MSGS.length && mRows.every((r) => r.buttons.every((b) => b.hit.centerOwns))
    && ['plain', 'quote', 'image'].every((f) => mRows.some((r) => r.form === f)),
    `实际 = ${JSON.stringify(mRows.map((r) => `${r.id}:${r.form}/${r.dir}${r.buttons.every((b) => b.hit.centerOwns) ? '' : '/OFF-VIEWPORT'}`))}`);
  result.mobile = assertGeometry(mRows, 'mobile');
  R.readings.mobile = mRows;

  for (const form of ['plain', 'quote', 'image']) {
    const sub = mRows.filter((r) => r.form === form);
    const c1 = Math.max(...sub.map((r) => r.maxChildCenterDev));
    const spread = Math.max(...sub.map((r) => Math.round((Math.max(...r.gaps) - Math.min(...r.gaps)) * 100) / 100));
    const dOk = sub.every((r) => r.deltas && Math.abs(r.deltas.outerAnchor) <= 0.5
      && Math.abs(r.deltas.contentAnchor) <= 0.5
      && Math.abs(r.deltas.insetLeft) <= 0.5 && Math.abs(r.deltas.insetRight) <= 0.5);
    ok(`C4 [mobile/${form}] 形态内 C1/C2/C3 三项全过（${sub.length} 条：${sub.map((r) => r.dir).join('/')}）`,
      c1 <= 0.5 && spread <= 0.5 && dOk,
      `c1max=${c1} gapSpread=${spread} 对齐=${dOk}`);
  }

  const hits = mRows.flatMap((r) => r.buttons.map((b) => {
    const hx = b.hit || { w: 0, h: 0 };
    return { id: r.id, w: hx.w, h: hx.h, note: hx.note || '', layoutW: b.w, layoutH: b.h };
  }));
  const badHits = hits.filter((h) => h.w < 44 || h.h < 44);
  if (badHits.length) console.log(`READ  mobile.hitDetail = ${JSON.stringify(mRows.map((r) => r.buttons.map((b) => b.hit)))}`);
  ok('C5 [mobile] 每颗 .fm-msg-act 真热区 ≥44×44（hit-test 探针实测）',
    hits.length > 0 && badHits.length === 0,
    `实测热区 = ${JSON.stringify(hits.map((h) => `${h.id}:${h.w}x${h.h}${h.note ? '(' + h.note + ')' : ''}`))}；不达标 ${badHits.length} 颗`);
  console.log(`READ  mobile.visualBody = ${JSON.stringify(hits.map((h) => `${h.id}:${h.layoutW}x${h.layoutH}`))}`);

  const timeHits = mRows.map((r) => {
    const th = r.type.time && r.type.time.hit ? r.type.time.hit : { w: 0, h: 0 };
    return { id: r.id, w: th.w, h: th.h, note: th.note || '' };
  });
  const badTimeHits = timeHits.filter((h) => h.w < 44 || h.h < 44);
  ok('C5b [mobile] 时间切换件（.fm-msg-time，可点件）真热区 ≥44×44（同档补齐）',
    timeHits.length > 0 && badTimeHits.length === 0,
    `实测 = ${JSON.stringify(timeHits.map((h) => `${h.id}:${h.w}x${h.h}${h.note ? '(' + h.note + ')' : ''}`))}；不达标 ${badTimeHits.length} 颗`);

  const timeHit = await M.page.evaluate(() => {
    const t = document.querySelector('.fm-flow .fm-msg-time');
    if (!t) return null;
    const r = t.getBoundingClientRect();
    return { layoutW: Math.round(r.width * 100) / 100, layoutH: Math.round(r.height * 100) / 100 };
  });
  console.log(`READ  mobile.time.layout = ${JSON.stringify(timeHit)}`);

  for (const r of mRows) {
    await shot(M.page, `mobile-${r.form}-${r.dir}`, M.page.locator(`.fm-flow .fm-msg[data-message-id="${r.id}"]`));
  }
  await shot(M.page, 'mobile-full');

  const mBehavior = await page_eval_behavior(M.page, { light: true });
  ok('C6 [mobile] 时间切换仍可达可点（文本重排）',
    mBehavior.time.before !== mBehavior.time.after, JSON.stringify(mBehavior.time));
  ok('C6 [mobile] 复制反馈仍到位（.copied 生效）', mBehavior.copy.copied === true, JSON.stringify(mBehavior.copy));
  const mErrs = M.pageErrors.filter((m) => !PAGEERROR_IGNORE.some((re) => re.test(String(m).split('\n')[0])));
  ok('C6 [mobile] 无 pageerror（同口径）', mErrs.length === 0, JSON.stringify(mErrs.slice(0, 3)));
  await M.ctx.close();
} finally {
  await browser.close();
  server.close();
}

async function page_eval_behavior(page, opts = {}) {
  const out = {};
  // 时间切换
  const timeSel = `.fm-flow .fm-msg[data-message-id="501"] .fm-msg-time`;
  out.time = await page.evaluate((sel) => {
    const t = document.querySelector(sel);
    return { before: t ? t.textContent : null, hasDataTsText: t ? t.hasAttribute('data-ts-text') : false };
  }, timeSel);
  await page.click(timeSel);
  await sleep(200);
  out.time.after = await page.evaluate((sel) => (document.querySelector(sel) || {}).textContent, timeSel);
  await page.click(timeSel); // 复位
  await sleep(150);

  // 复制反馈
  const copySel = `.fm-flow .fm-msg[data-message-id="502"] .fm-msg-act`;
  await page.click(copySel);
  await sleep(180);
  out.copy = await page.evaluate((sel) => {
    const b = document.querySelector(sel);
    if (!b) return { copied: false, iconAfter: null, iconFinal: null };
    const ico = b.querySelector('i') || b.querySelector('svg');
    return { copied: b.classList.contains('copied'), iconAfter: ico ? ico.getAttribute('data-lucide') || (ico.getAttribute('class') || '') : null, iconFinal: null };
  }, copySel);
  await sleep(1700);
  out.copy.iconFinal = await page.evaluate((sel) => {
    const b = document.querySelector(sel);
    const ico = b ? (b.querySelector('i') || b.querySelector('svg')) : null;
    return ico ? (ico.getAttribute('data-lucide') || (ico.getAttribute('class') || '')) : null;
  }, copySel);
  out.copy.iconAfter = String(out.copy.iconAfter || '').includes('check') ? 'check' : String(out.copy.iconAfter || '');
  out.copy.iconFinal = String(out.copy.iconFinal || '').includes('copy') ? 'copy' : String(out.copy.iconFinal || '');

  // 转发（第 2 颗 = forward）
  if (opts.light) return out;
  const fwdSel = `.fm-flow .fm-msg[data-message-id="502"] .fm-msg-actions .fm-msg-act:nth-child(2)`;
  await page.click(fwdSel);
  await sleep(600);
  out.forward = await page.evaluate(() => {
    const toast = document.querySelector('.fm-modal-toast');
    return { toast: toast && !toast.hidden ? toast.textContent : null };
  });
  // forwarded 徽章：生产写入点 = `fm-refs-sent`（js/input.js:2254 在**引用真正上路**时
  // dispatch，messages.js:3819 onRefsSent ⇒ stampForwarded）。此处从**同一条消费路径**
  // 投一帧（不伪造 DOM、不手写徽章），验证徽章插入 footer 后几何仍同轴。
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('fm-refs-sent', { detail: { messageIds: ['502'] } })));
  await sleep(200);
  out.forward.badge = await page.evaluate(() => !!document.querySelector('.fm-flow .fm-msg[data-message-id="502"] .fm-msg-forwarded-badge'));

  // 右键菜单（引用 / 多选入口）
  const bub = await page.evaluate(() => {
    const m = document.querySelector('.fm-flow .fm-msg[data-message-id="501"] .fm-msg-bubble');
    if (!m) return null;
    const r = m.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  });
  out.menu = { labels: [] };
  if (bub) {
    await page.mouse.click(bub.x, bub.y, { button: 'right' });
    await sleep(260);
    out.menu.labels = await page.evaluate(() => [...document.querySelectorAll('.fm-context-menu button[role="menuitem"]')].map((b) => b.textContent));
    if (out.menu.labels.includes('多选转发')) {
      await page.click('.fm-context-menu button[role="menuitem"]:has-text("多选转发")');
      await sleep(300);
      out.select = await page.evaluate(() => ({
        checks: document.querySelectorAll('.fm-flow .fm-msg-check').length,
        after: 0,
      }));
      // 退出多选 = 既有退出钮（Esc 在输入框聚焦时先清稿，不保证退出多选）
      await page.click('.fm-select-bar .fm-select-exit').catch(() => {});
      await sleep(300);
      out.select.after = await page.evaluate(() => document.querySelectorAll('.fm-flow .fm-msg-check').length);
    } else {
      await page.keyboard.press('Escape');
      await sleep(150);
      out.select = { checks: 0, after: 0 };
    }
  } else {
    out.select = { checks: 0, after: 0 };
  }

  out.agent = await page.evaluate(() => ({
    badge: !!document.querySelector('.fm-flow .fm-msg[data-message-id="507"] .fm-msg-agent-badge'),
  }));
  return out;
}

// ── 落盘 + 退出码 ─────────────────────────────────────────────────────
if (OUT) {
  await mkdir(dirname(OUT), { recursive: true });
  await writeFile(OUT, JSON.stringify(R, null, 2));
  console.log(`\nREADINGS ${OUT}`);
}
console.log(`\n${failures === 0 ? 'GREEN' : 'RED'}  [${LABEL}/${ENGINE}] failures=${failures} / assertions=${R.assertions.length}`);
process.exit(failures === 0 ? 0 : 1);
