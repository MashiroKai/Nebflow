#!/usr/bin/env node
// verify-friendmsg-badge-render.mjs — 批 D「agent 代发 footer 徽标」的**真渲染**验收。
//
// ── 夹具声明（🔴 如实声明，不冒充生产实测）──────────────────────────────
// · 前端文件：`page.route` 从磁盘**服务真实源码树**（默认 `src/main/resources/web/`，
//   可用 `--web <dir>` 指向基线只读副本 ⇒ 同一脚本可跑「前红」），不起任何端口、
//   不碰 :8080、不注册任何 WS 会话（`window.WebSocket` 被 MockWS 顶掉）。
// · 数据面：`/api/*` 一律由本脚本按 **fixture** 应答。夹具按**网关出参契约**构造
//   （`origin` 键随消息出 —— 该契约由 Scala 侧 `FriendMessageFooterBadgeSpec` 钉死；
//   本脚本不重测 Scala 编解码，只测**前端读路径**）。
// · 客户端代码路径：**全部是生产代码**（`messages.js` 的 `frameMessage` / `resolveOut`
//   / 徽标渲染 + `fmMessageCache.js` 的白名单与缓存读写），无任何测试替身。
//
// ── 判据（批 D 任务书 ①–⑥ + 分发器两项增补）───────────────────────────
//   D1  origin='agent' ⇒ `.fm-msg-agent-badge` 计数 1 且文案 = `Agent 代发`(zh)
//       / `Sent by agent`(en)
//   D2  origin='user' ⇒ 计数 0
//   D3  **缺 origin 键** ⇒ 计数 0 且不误显（显式断言 `kind === 'text'` 仍不显）
//   D4  三类（agent / user / 缺键）在**发送侧与接收侧**各跑一次 ⇒ 两侧期望**同构**
//       （作者裁「双方可见」：接收侧**不得恒 0**）
//   D5  全程零 console error / pageerror（只容忍「已登记的启动期轮询 404」）
//   D6  **三路径对照**（对端视角 + 分发器增补）：同一 agent 消息经
//       ① live 帧 ② REST 拉取 ③ L2 消息缓存（命中 / 未命中两态）
//       到达后**均显徽标**，且逐条给 `origin` 字段读数（对象级，读自 L2 缓存槽）
//   D7  **P5 兜底档**（root 裁定，硬）：`senderId` 与 `conv.friend` **两源皆缺席**
//       ⇒ 方向维持 `in`（禁静默翻成 `out`）；并钉两条**不得回归**的邻域：
//       · 单侧在场（`senderId` 在、`conv.friend` 缺）⇒ 仍走既有 `out` 回落（残余，登记）
//       · 本机自播帧（`message_new_self`，服务端**不带** `senderId`）⇒ 仍 `out`
//         （禁把自送消息画到左侧）
//
// ── 用法（worktree 根；**不启任何端口/实例**）──────────────────────────
//   node scripts/verify-friendmsg-badge-render.mjs [--web <dir>] [--out <dir>] [--only <substr>]
//   🔴 证据落**工作区根绝对路径**（worktree 内没有 `.nebflow/`，且 worktree 收尾会被清）：
//     node scripts/verify-friendmsg-badge-render.mjs \
//       --out "/Users/kaiyu/Claude code/Nebflow/.nebflow/evidence/20260914_friendmsg-d/render"
//   前红（同一脚本指基线只读副本；`--web` 必须指向**未被本批修改**的源码树）：
//     node scripts/verify-friendmsg-badge-render.mjs \
//       --web <基线树>/src/main/resources/web \
//       --out "/Users/kaiyu/Claude code/Nebflow/.nebflow/evidence/20260914_friendmsg-d/render-baseline"
// 退出码：0 = 全绿；1 = 任一断言红（逐条打印失败原因）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, extname, normalize, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const argOf = (flag, dflt) => {
  const i = argv.indexOf(flag);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const WEB = normalize(argOf('--web', join(HERE, '..', 'src', 'main', 'resources', 'web')));
const OUT = normalize(argOf('--out', join(HERE, '..', '.nebflow', 'evidence', '20260914_friendmsg-d', 'render')));
const ONLY = argOf('--only', '');

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const PEER = 'u-peer';
const ME = 'u-me';
const CONV_ID = 'c1';
const BASE = 1700000000000;

// ── 夹具构造（三条纪律：id 逐场景唯一 / 缺键用 delete 而不是 undefined / 逐场景只声明它要的）──
/** 一条**入站**（对方所发）消息；`o` 可覆盖任意字段。 */
const M = (id, o = {}) => ({ id, senderId: PEER, kind: 'text', body: `m${id}`, createdAt: BASE + id * 1000, ...o });
/** 一条**本机所发**消息（REST 面 `senderId` = 自己 ⇒ 正向证据判 out）。 */
const MOut = (id, o = {}) => M(id, { senderId: ME, ...o });
/** 一条**缺 `senderId` 键**的消息（P5 用；`delete` ⇒ wire 上真无该键）。 */
const MNoSid = (id, o = {}) => { const m = M(id, o); delete m.senderId; return m; };
/** 真 push / 自播帧（**扁平**信封：`frontendFrame` 把 payload 展平到顶层）。 */
const frame = (event, id, o = {}) => ({
  type: 'friend_event', event, messageId: id, conversationId: CONV_ID,
  kind: 'text', body: `m${id}`, createdAt: BASE + id * 1000, ...o,
});
const CONV = { conversationId: CONV_ID, friend: { userId: PEER, username: 'peer', name: '林小满' }, unreadCount: 0, lastMessage: null };
const CONV_NO_FRIEND = { conversationId: CONV_ID, friend: null, unreadCount: 0, lastMessage: null };

/** 应用启动期的既有轮询端点（与本批徽标面无关；harness 刻意不实现它们）。
  * 显式登记 ⇒ D5 只容忍这些 404，任何**新的** 4xx/5xx 仍然判红。 */
const BOOT_POLL_404 = ['/api/canvas-tabs', '/api/nf-authcheck', '/api/plugins', '/api/agents', '/api/projects'];
const ZH_AGENT = 'Agent 代发';
const EN_AGENT = 'Sent by agent';

// ── 场景表（三类 × 两侧 × 三路径 + 缓存两态 + P5 三档）─────────────────
const SCENARIOS = [
  // ---- live 帧路径：发送侧（`message_new_self` 自播；服务端该帧不带 senderId）----
  {
    name: 'live-send-agent',
    frames: [frame('message_new_self', 101, { origin: 'agent' })],
    expect: (s, check) => {
      check(s.badges[101] === 1, 'D1 发送侧·live·agent ⇒ 徽标计数 1', JSON.stringify(s.badges));
      check(s.badgeText[101] === ZH_AGENT, 'D1 文案 = Agent 代发 (zh)', String(s.badgeText[101]));
      check(s.cls[101] === 'out', 'D1 方向仍 out（本批不动方向）', String(s.cls[101]));
      check(cacheOrigin(s, 101) === 'agent', 'D6 对象级 origin 完好（live 帧 → 渲染窗口 → L2）', JSON.stringify(s.cacheMsg(101)));
    },
  },
  {
    name: 'live-send-user',
    frames: [frame('message_new_self', 102, { origin: 'user' })],
    expect: (s, check) => {
      check(s.badges[102] === 0, 'D2 发送侧·live·user ⇒ 计数 0', JSON.stringify(s.badges));
    },
  },
  {
    name: 'live-send-missing',
    frames: [frame('message_new_self', 103)],
    expect: (s, check) => {
      check(s.badges[103] === 0, 'D3 发送侧·live·缺 origin 键 ⇒ 计数 0', JSON.stringify(s.badges));
      check(s.kinds[103] === 'text', 'D3 缺键时 kind 仍 text（不得靠 kind 误显）', String(s.kinds[103]));
      check(s.hasOriginKey[103] === false, 'D3 缺键在对象级仍是「无该键」（不误造键）', JSON.stringify(s.cacheMsg(103)));
    },
  },
  {
    name: 'live-send-agent-en',
    locale: 'en',
    frames: [frame('message_new_self', 104, { origin: 'agent' })],
    expect: (s, check) => {
      check(s.badges[104] === 1, 'D1 发送侧·live·agent(en) ⇒ 计数 1', JSON.stringify(s.badges));
      check(s.badgeText[104] === EN_AGENT, 'D1 文案 = Sent by agent (en)', String(s.badgeText[104]));
    },
  },
  // ---- live 帧路径：接收侧（`message_new` 对方所发；真 push 带 senderId）----
  {
    name: 'live-recv-agent',
    frames: [frame('message_new', 111, { senderId: PEER, origin: 'agent' })],
    expect: (s, check) => {
      check(s.badges[111] === 1, 'D4 接收侧·live·agent ⇒ 计数 1（「双方可见」不得恒 0）', JSON.stringify(s.badges));
      check(s.badgeText[111] === ZH_AGENT, 'D1 接收侧文案同构', String(s.badgeText[111]));
      check(s.cls[111] === 'in', 'D1 接收侧方向 = in', String(s.cls[111]));
      check(cacheOrigin(s, 111) === 'agent', 'D6 对象级 origin 完好（接收侧 live 帧）', JSON.stringify(s.cacheMsg(111)));
    },
  },
  {
    name: 'live-recv-user',
    frames: [frame('message_new', 112, { senderId: PEER, origin: 'user' })],
    expect: (s, check) => {
      check(s.badges[112] === 0, 'D2 接收侧·live·user ⇒ 计数 0', JSON.stringify(s.badges));
    },
  },
  {
    name: 'live-recv-missing',
    frames: [frame('message_new', 113, { senderId: PEER })],
    expect: (s, check) => {
      check(s.badges[113] === 0, 'D3 接收侧·live·缺 origin 键 ⇒ 计数 0', JSON.stringify(s.badges));
      check(s.kinds[113] === 'text', 'D3 缺键 + kind=text ⇒ 仍不显', String(s.kinds[113]));
    },
  },
  // ---- REST 冷路径（**缓存未命中**）：三类 × 两侧 ----
  {
    name: 'rest-cold-recv-agent',
    rest: [M(121, { origin: 'agent' })],
    expect: (s, check) => {
      check(s.badges[121] === 1, 'D6 REST·接收侧·agent ⇒ 计数 1（对端视角 · 第二条路径）', JSON.stringify(s.badges));
      check(s.cls[121] === 'in', 'D6 REST·接收侧方向 = in', String(s.cls[121]));
      check(cacheOrigin(s, 121) === 'agent', 'D6 对象级 origin 完好（REST → 渲染 → L2）', JSON.stringify(s.cacheMsg(121)));
    },
  },
  {
    name: 'rest-cold-recv-user',
    rest: [M(122, { origin: 'user' })],
    expect: (s, check) => {
      check(s.badges[122] === 0, 'D2 REST·接收侧·user ⇒ 计数 0', JSON.stringify(s.badges));
    },
  },
  {
    name: 'rest-cold-recv-missing',
    // `senderId` **在场**（隔离变量：本档只测「缺 origin」；方向档另设于 P5 场景）
    rest: [M(123)],
    expect: (s, check) => {
      check(s.badges[123] === 0, 'D3 REST·缺 origin 键 ⇒ 计数 0', JSON.stringify(s.badges));
      check(s.kinds[123] === 'text', 'D3 REST 缺键 + kind=text ⇒ 不显（显式）', String(s.kinds[123]));
      check(s.hasOriginKey[123] === false, 'D3 REST 缺键在对象级仍是「无该键」', JSON.stringify(s.cacheMsg(123)));
    },
  },
  {
    name: 'rest-cold-send-agent',
    rest: [MOut(124, { origin: 'agent' })],
    expect: (s, check) => {
      check(s.badges[124] === 1, 'D6 REST·发送侧·agent ⇒ 计数 1', JSON.stringify(s.badges));
      check(s.cls[124] === 'out', 'D6 REST·发送侧方向 = out', String(s.cls[124]));
    },
  },
  {
    name: 'rest-cold-send-user',
    rest: [MOut(125, { origin: 'user' })],
    expect: (s, check) => {
      check(s.badges[125] === 0, 'D2 REST·发送侧·user ⇒ 计数 0', JSON.stringify(s.badges));
    },
  },
  // ---- L2 消息缓存**命中**路径（REST 一律返回空页 ⇒ 渲染源只可能是缓存）----
  {
    name: 'cache-hit-recv-agent',
    seed: [M(131, { origin: 'agent' })],
    rest: [],
    expect: (s, check) => {
      check(s.cacheSeeded === true, 'D6 缓存命中档前置：种子写入成功', JSON.stringify(s.cacheSeedStats));
      check(s.restCalls.length > 0 && s.restCalls.every(c => c.returned === 0), 'D6 缓存命中档判读：REST 增量页全空（渲染源 = 缓存）', JSON.stringify(s.restCalls));
      check(s.badges[131] === 1, 'D6 缓存命中·agent ⇒ 计数 1（白名单必须带 origin）', JSON.stringify(s.badges));
      check(s.cls[131] === 'in', 'D6 缓存命中方向 = in', String(s.cls[131]));
      check(cacheOrigin(s, 131) === 'agent', 'D6 缓存命中后对象级 origin 仍完好（往返不经折叠）', JSON.stringify(s.cacheMsg(131)));
    },
  },
  {
    name: 'cache-hit-recv-user',
    seed: [M(132, { origin: 'user' })],
    rest: [],
    expect: (s, check) => {
      check(s.badges[132] === 0, 'D2 缓存命中·user ⇒ 计数 0', JSON.stringify(s.badges));
    },
  },
  {
    name: 'cache-hit-recv-missing',
    seed: [M(133)],
    rest: [],
    expect: (s, check) => {
      check(s.badges[133] === 0, 'D3 缓存命中·缺 origin 键 ⇒ 计数 0', JSON.stringify(s.badges));
      check(s.kinds[133] === 'text', 'D3 缓存命中·缺键 + kind=text ⇒ 不显', String(s.kinds[133]));
    },
  },
  // ---- P5 兜底档（方向判定）----
  {
    name: 'p5-both-absent',
    conv: CONV_NO_FRIEND,
    rest: [MNoSid(141)],
    expect: (s, check) => {
      check(s.cls[141] === 'in', 'D7 两源皆缺席 ⇒ in（P5 修点；修前为 out）', String(s.cls[141]));
      check(s.badges[141] === 0, 'D7 反向对照：缺 origin ⇒ 不显徽标（方向修点不影响徽标判据）', JSON.stringify(s.badges));
    },
  },
  {
    name: 'p5-single-side-senderid',
    conv: CONV_NO_FRIEND,
    rest: [M(142)],
    expect: (s, check) => {
      check(s.cls[142] === 'out', 'D7 单侧在场（senderId 在 / friend 缺）⇒ 既有 out 回落（残余，登记）', String(s.cls[142]));
    },
  },
  {
    name: 'p5-self-replay-no-senderid',
    conv: CONV,
    frames: [frame('message_new_self', 143, { origin: 'agent' })],
    expect: (s, check) => {
      check(s.cls[143] === 'out', 'D7 本机自播帧（无 senderId）仍 out（禁把自送消息画到左侧）', String(s.cls[143]));
      check(s.badges[143] === 1, 'D7 自播 agent 帧仍显徽标', JSON.stringify(s.badges));
    },
  },
];

// ── 断言收集 ─────────────────────────────────────────────
const failures = [];
const notes = [];
let readings = {};
function check(ok, label, detail) {
  if (ok) notes.push(`PASS ${label}`);
  else failures.push(`${label}${detail ? ` — ${detail}` : ''}`);
}
/** 快照读取器（逐条给读数，不吞异常）。 */
function cachedMsgOf(s, id) { return s.cacheMsgs.find(m => String(m.id) === String(id)) || null; }
function cacheOrigin(s, id) { const m = cachedMsgOf(s, id); return m ? m.origin : null; }
/** 对象级 `kind` 读数 —— 🔴 **不读 DOM**（`bubbleEl` 不挂 `data-kind`，DOM 面读不到
  * 真值；若强行读会恒得兜底值 ⇒ 判据变成自证绿）。改读 L2 缓存槽（`slim` 白名单
  * 保留 `kind`，且它正是渲染窗口的序列化）。 */
function cacheKind(s, id) { const m = cachedMsgOf(s, id); return m ? m.kind : null; }

async function waitForAccount(page) {
  for (let i = 0; i < 40; i++) {
    const acct = await page.evaluate(async () => (await import('/js/fmMessageCache.js')).getCacheAccount());
    if (acct) return acct;
    await page.waitForTimeout(250);
  }
  return '';
}

async function readCache(page) {
  return page.evaluate(async () => {
    const mod = await import('/js/fmMessageCache.js');
    const c = mod.loadConversation('c1');
    const stats = mod.cacheStats();
    if (!c) return { hit: false, stats, msgs: [] };
    return {
      hit: true, stats, watermark: c.watermark, fresh: c.fresh,
      msgs: c.msgs.map(m => ({
        id: m.id,
        kind: m.kind,
        origin: Object.prototype.hasOwnProperty.call(m, 'origin') ? m.origin : null,
        hasOriginKey: Object.prototype.hasOwnProperty.call(m, 'origin'),
        hasAttachmentsKey: Object.prototype.hasOwnProperty.call(m, 'attachments'),
      })),
    };
  });
}

async function snapshot(page) {
  const dom = await page.evaluate(() => {
    const out = { badges: {}, badgeText: {}, cls: {}, domIds: [] };
    for (const wrap of document.querySelectorAll('.fm-msg')) {
      const id = wrap.dataset.messageId;
      out.domIds.push(id);
      out.badges[id] = wrap.querySelectorAll('.fm-msg-agent-badge').length;
      const b = wrap.querySelector('.fm-msg-agent-badge');
      out.badgeText[id] = b ? b.textContent : null;
      out.cls[id] = wrap.classList.contains('out') ? 'out' : (wrap.classList.contains('in') ? 'in' : '?');
    }
    return out;
  });
  const cache = await readCache(page);
  const cacheMsgs = cache.msgs || [];
  return Object.assign({
    cacheMsgs,
    cacheStats: cache.stats,
    cacheHit: cache.hit,
    // `kind` 走**对象级**读数（DOM 面无 `data-kind`，读 DOM 会恒得兜底值 = 自证绿）。
    kinds: Object.fromEntries(cacheMsgs.map(m => [String(m.id), m.kind])),
    kindOf: (id) => cacheKind({ cacheMsgs }, id),
    hasOriginKey: Object.fromEntries(cacheMsgs.map(m => [String(m.id), m.hasOriginKey])),
    cacheMsg: (id) => cachedMsgOf({ cacheMsgs }, id),
    originOf: (id) => cacheOrigin({ cacheMsgs }, id),
  }, dom);
}

async function run(browser, scn) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
  const page = await context.newPage();
  const consoleErrors = [];
  const notFound = [];
  const seen = { restCalls: [] };
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(String(e)));
  page.on('response', (r) => { if (r.status() >= 400) notFound.push(`${r.status()} ${new URL(r.url()).pathname}`); });

  const conv = scn.conv || CONV;
  await page.addInitScript(({ locale }) => {
    localStorage.setItem('nebflow_token', 'render-harness-token');
    localStorage.setItem('nebflow_locale', locale);
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
      send() {} close() {} onopen = null; onmessage = null; onclose = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
  }, { locale: scn.locale || 'zh-CN' });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/neblink/status') {
        return route.fulfill({ json: { loggedIn: true, device: { id: 'd1', deviceId: 'd1', name: 'RenderMac', platform: 'macos', capabilities: {} } } });
      }
      if (p === '/api/conversations') return route.fulfill({ json: [conv] });
      if (p === '/api/friends') return route.fulfill({ json: { friends: conv.friend ? [conv.friend] : [], incoming: [], outgoing: [] } });
      if (p === `/api/conversations/${CONV_ID}/messages`) {
        const after = Number(url.searchParams.get('after') || 0);
        const limit = Math.min(200, Math.max(1, Number(url.searchParams.get('limit') || 50)));
        const all = (scn.rest || []).filter(m => Number(m.id) > after).slice(0, limit);
        // 🔴 缓存命中档的**判读读数**：本页返回 0 条 ⇒ 渲染源只可能是 L2 缓存。
        seen.restCalls.push({ after, limit, returned: all.length });
        return route.fulfill({ json: all });
      }
      if (p === `/api/conversations/${CONV_ID}/read`) return route.fulfill({ json: { ok: true } });
      return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 20000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
  await df({ type: 'sessionList', sessionId: 'render-root', activeId: 'render-root', sessions: [{ id: 'render-root', name: 'Nebula', agentName: 'Nebula' }], folders: [] });
  await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
  await df({ type: 'historyPage', sessionId: 'render-root', messages: [], hasMore: false, offset: 0 });
  await page.waitForTimeout(400);

  // 打开消息面板（生产入口：活动栏按钮）——会话列表先落地，`conversations` 才有内容，
  // 否则 live 帧会落进「会话缓存缺失」早退分支（W13），判据就读不到气泡。
  await page.click('#activity-bar [data-panel-btn="messages"]');
  await page.waitForSelector('#panel-messages .fm-row', { timeout: 15000 });
  await page.waitForTimeout(200);

  // 账号分区就绪（`neblink.js` 状态帧之后 `setCacheAccount` 才落位；缓存读写依赖它）。
  const acct = await waitForAccount(page);

  // 缓存命中档：**用生产模块**写入 L2 种子（走的是真 `slim` 白名单 ⇒ 三态读数可信）。
  let cacheSeedStats = null;
  if (scn.seed) {
    cacheSeedStats = await page.evaluate(async (msgs) => {
      const mod = await import('/js/fmMessageCache.js');
      mod.saveConversation('c1', msgs, { watermark: 900, lastMessageAt: Date.now() });
      return mod.cacheStats();
    }, scn.seed);
  }

  // 打开会话（生产入口：点会话行）
  await page.click('#panel-messages .fm-row');
  await page.waitForSelector('#fm-chat-overlay', { timeout: 15000 });
  await page.waitForTimeout(400);

  // 投递 live 帧（真 push / 自播帧逐帧）
  for (const f of scn.frames || []) await df(f);
  if ((scn.frames || []).length) await page.waitForTimeout(300);

  const snap = await snapshot(page);
  snap.restCalls = seen.restCalls;
  snap.cacheSeeded = !!scn.seed;
  snap.cacheSeedStats = cacheSeedStats;
  snap.acct = acct;

  await page.screenshot({ path: join(OUT, `friendmsg-badge-${scn.name}.png`) }).catch(() => {});

  // 每条判据 + 每条场景的**零错误**判据（D5）
  scn.expect(snap, check);
  const newErrors = notFound.filter(n => !BOOT_POLL_404.some(p => n.endsWith(p)));
  check(consoleErrors.length === 0, `D5 console error / pageerror 为零`, consoleErrors.slice(0, 3).join(' | '));
  check(newErrors.length === 0, `D5 非登记 4xx/5xx 为零`, newErrors.slice(0, 3).join(' | '));

  readings[scn.name] = {
    acct, domIds: snap.domIds, badges: snap.badges, badgeText: snap.badgeText, cls: snap.cls,
    kinds: snap.kinds, hasOriginKey: snap.hasOriginKey,
    cacheHit: snap.cacheHit, cacheStats: snap.cacheStats, cacheMsgs: snap.cacheMsgs,
    restCalls: snap.restCalls, cacheSeeded: snap.cacheSeeded, cacheSeedStats: snap.cacheSeedStats,
    consoleErrors, notFound: newErrors,
  };
  await context.close();
}

async function main() {
  mkdirSync(OUT, { recursive: true });
  const list = SCENARIOS.filter(s => !ONLY || s.name.includes(ONLY));
  console.log(`web tree = ${WEB}`);
  console.log(`scenarios = ${list.length}${ONLY ? ` (--only ${ONLY})` : ''}`);
  const browser = await chromium.launch();
  try {
    for (const scn of list) {
      const before = failures.length;
      await run(browser, scn);
      console.log(`── ${scn.name}: ${failures.length === before ? 'GREEN' : `RED(${failures.length - before})`}`);
    }
  } finally {
    await browser.close();
  }
  writeFileSync(join(OUT, 'assertions.json'), JSON.stringify({ web: WEB, notes, failures, readings }, null, 2));
  for (const n of notes) console.log(`  ${n}`);
  if (failures.length) {
    console.log(`\nFAILURES (${failures.length}):`);
    for (const f of failures) console.log(`  FAIL ${f}`);
  }
  console.log(`\n${failures.length ? 'RENDER_RED' : 'RENDER_GREEN'} failures=${failures.length} notes=${notes.length} out=${OUT}`);
  process.exit(failures.length ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });
