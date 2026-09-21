// msgmenu.spec.mjs — msgmenu 一期（客户端）的自包含**真渲染**验收
//   批：msgmenu-impl（作者 2026-09-18 19:2x 四答 = 唯一规格）
//   ① 菜单壳扩多项（复制 / 转发给智能助手 / 引用 / 多选转发）——🔴 零删除、零撤回项；
//   ② 引用：输入框面摘要 + 可取消 → 发送 → 气泡内引用块（本侧 + 对端）+ 被引 id 挂
//      dataset（跳转**预留挂点**，跳转本批不写）+ 被引消息不存在 ⇒ 降级显示；
//   ③ 多选转发：选择态（勾选 + 工具条）+ 目标选择器（其他会话）+ 逐条转发保持原顺序；
//   ④ 关窗重开选中归零 / 多选期间不误触 `.fm-retry`。
//
// 运行（双向红绿钉 —— 同一 harness，两份被测树）：
//   node tests/msgmenu.spec.mjs                                       # 改后（本批）
//   MM_MODE=before MM_WEB_ROOT=<纯 main 的 web 树> node tests/msgmenu.spec.mjs
// 环境变量：
//   MM_WEB_ROOT  被测 web 树（缺省 = 本仓 src/main/resources/web）
//   MM_MODE      `after`（缺省）｜`before`（改前基线：症状必须复现 = 红组）
//   MM_OUT       全部读数落盘为 JSON（证据件）
//   MM_SHOTS     真渲染截图落盘目录（缺省不落图）
//
// 🔴 诚实申报（与 imgmsg-send.spec.mjs 同款纪律）：本 spec 的「服务端」= 页内
//   `page.route('**/api/**')` 的**契约镜像**（conversations / keyset messages /
//   好友发消息），**不是**生产 neblink-server。本 spec 覆盖**前端链路**：真实
//   Chromium + 真实模块图 + 真实 CSS + 真实几何 + 真实 DOM 事件（真指针右键/点击）。
//   「对端渲染」腿 = 用 `message_new` 帧从**现有 onMessage 频道**注入一条对端消息
//   （其正文 = 同一编码点产出的引用信封）⇒ 消费路径真实，producer（外仓引用字段）
//   待跨仓契约落地后补跑（本批零外仓改动）。
// 🔴 零实例依赖：不起 gateway 实例（无 sbt、零端口占用、不触碰宿主 :8080）；静态
//   资源由本 spec 内置 http server（随机端口）供给，收尾必关（进程清理纪律）。

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { writeFileSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.MM_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
// ══════════════════════════════════════════════════════════════════════
// 运行模式：
//   after     （缺省）= 本批实施面（quotejump 已落地）
//   before    = **msgmenu 一期**的改前基线（一期落地前的树；`MM_WEB_ROOT` 指旧树）
//   qjbefore  = **quotejump 批**的改前基线（一期已落地的 main 树）⇒ 只跑 S5 红线面
// ══════════════════════════════════════════════════════════════════════
const MODE = process.env.MM_MODE === 'before' ? 'before'
  : process.env.MM_MODE === 'qjbefore' ? 'qjbefore' : 'after';
const AFTER = MODE === 'after';
const QJBEFORE = MODE === 'qjbefore';
const MM_OUT = process.env.MM_OUT || '';
const MM_SHOTS = process.env.MM_SHOTS || '';
// 主题（visup-b 批验收第 1 条「亮/暗双主题」）：`MM_SCHEME=light|dark`（缺省 light —
// 与 Playwright 的默认 colorScheme 一致 ⇒ 既有判据面对照值不变）。同一 harness 跑两遍
// 即得双主题读数 + 双主题截图，**不复制**任何场景代码。
const MM_SCHEME = process.env.MM_SCHEME === 'dark' ? 'dark' : 'light';

let failures = 0;
const R = { mode: MODE, web: WEB, readings: {} };
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const put = (k, v) => { R.readings[k] = v; console.log(`READ  ${k} = ${JSON.stringify(v)}`); };
let shotSeq = 0;
async function shot(page, name) {
  if (!MM_SHOTS) return null;
  await mkdir(MM_SHOTS, { recursive: true });
  shotSeq += 1;
  const p = join(MM_SHOTS, `${String(shotSeq).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: p });
  console.log(`SHOT  ${p}`);
  return p;
}

// ── REST/WS 契约镜像 ───────────────────────────────────────────────────
const EPOCH = Math.floor(Date.now() / 1000);
const iso = s => new Date(s * 1000).toISOString();
const QUOTE_SRC = '明天九点开会';               // 源消息正文（将被引用）
const SRV = {
  msgs: {}, convs: [], friends: [], calls: [], selfSeq: 900,
  postDelay: 60,
};
const FRIEND_CONV = { 'u-p': 'cA', 'u-q': 'cC' };

function resetServer() {
  SRV.calls.length = 0;
  SRV.selfSeq = 900;
  SRV.msgs = {
    cA: [
      { id: 101, senderId: 'u-p', kind: 'text', body: '早上好', createdAt: iso(EPOCH - 300) },
      { id: 102, senderId: 'u-p', kind: 'text', body: QUOTE_SRC, createdAt: iso(EPOCH - 240) },
      { id: 103, senderId: 'u-p', kind: 'text', body: '带上笔记本', createdAt: iso(EPOCH - 180) },
    ],
    cC: [
      { id: 301, senderId: 'u-p', kind: 'text', body: '另一个会话的消息', createdAt: iso(EPOCH - 60) },
    ],
  };
  const last = cid => { const a = SRV.msgs[cid] || []; return a[a.length - 1] || null; };
  const mk = (cid, uid, name, nid) => ({
    conversationId: cid,
    friend: { userId: uid, neblinkId: nid, name, avatarUrl: '', remark: null, since: iso(EPOCH - 86400) },
    lastMessage: last(cid), unreadCount: 0,
  });
  SRV.convs = [mk('cA', 'u-p', '分页君', 'pagfriend'), mk('cC', 'u-q', '另一好友', 'other')];
  SRV.friends = SRV.convs.map(c => c.friend);
}
resetServer();
const callsOf = m => SRV.calls.filter(c => m(c));

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

let WS = null;   // 帧注入器（每个 page 一份）

/** 云 STT 腿的最小夹具（S6 的麦克风反馈读数用）：假 `AudioContext` + 假
 *  `getUserMedia` + 手摇音频泵。**只**在需要 mic 场景的那一轮注入（零波及其他轮）。 */
const VOICE_HARNESS = () => {
  class FakeAC {
    constructor() { this.sampleRate = 48000; this.destination = {}; }
    createMediaStreamSource() { return { connect() {} }; }
    createScriptProcessor() { const p = { onaudioprocess: null, connect() {}, disconnect() {} }; FakeAC.processor = p; return p; }
    createGain() { return { gain: { value: 1 }, connect() {} }; }
    close() { return Promise.resolve(); }
  }
  window.AudioContext = FakeAC;
  window.__mic = {
    pump(seconds) {
      const proc = FakeAC.processor;
      if (!proc || !proc.onaudioprocess) throw new Error('no audio processor (dictation not started?)');
      const chunkMs = (4096 / 48000) * 1000;
      const n = Math.max(1, Math.ceil((seconds * 1000) / chunkMs));
      for (let k = 0; k < n; k++) proc.onaudioprocess({ inputBuffer: { getChannelData: () => { const a = new Float32Array(4096); a.fill(0.3); return a; } } });
    },
  };
  if (!navigator.mediaDevices) Object.defineProperty(navigator, 'mediaDevices', { value: {} });
  navigator.mediaDevices.getUserMedia = async () => ({ getTracks: () => [{ stop() {} }] });
};

async function bootPage(opts = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: MM_SCHEME });
  if (opts.voice) await ctx.addInitScript(VOICE_HARNESS);
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
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify((SRV.msgs[cid] || []).filter(x => Number(x.id) > after).slice(0, limit)) });
    }
    if (/^\/api\/conversations\/[^/]+\/read$/.test(p)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    // 好友发消息腿（转发腿走同一端点）
    const fm = p.match(/^\/api\/friends\/([^/]+)\/messages$/);
    if (method === 'POST' && fm) {
      const uid = decodeURIComponent(fm[1]);
      const cid = FRIEND_CONV[uid] || 'cA';
      await sleep(SRV.postDelay);
      const row = { id: ++SRV.selfSeq, senderId: 'me', kind: 'text', body: body.body || '', createdAt: iso(EPOCH) };
      SRV.msgs[cid].push(row);
      const ci = SRV.convs.findIndex(c => c.conversationId === cid);
      if (ci >= 0) SRV.convs[ci].lastMessage = row;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messageId: row.id, conversationId: cid, createdAt: row.createdAt }) });
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

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(900);
  return { ctx, page, pageErrors };
}

const openPanel = async (page) => { await page.click('#messages-btn'); await sleep(600); };
const openConv = async (page, cid) => { await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(700); };

/** 真指针右键某气泡 → 返回菜单项读数（文本 + role + 视口盒 + elementFromPoint 命中）。 */
async function rightClickBubble(page, mid) {
  const box = await page.evaluate((id) => {
    const m = document.querySelector(`.fm-flow .fm-msg[data-message-id="${id}"]`);
    if (!m) return null;
    const r = m.querySelector('.fm-msg-bubble').getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  }, mid);
  if (!box) return null;
  await page.mouse.click(box.x, box.y, { button: 'right' });
  await sleep(260);
  return page.evaluate(() => {
    const menu = document.querySelector('.fm-context-menu');
    if (!menu) return null;
    const btns = [...menu.querySelectorAll('button[role="menuitem"]')];
    const r = menu.getBoundingClientRect();
    const c = menu.querySelector('button');
    const cr = c ? c.getBoundingClientRect() : null;
    const hit = cr ? document.elementFromPoint(cr.x + cr.width / 2, cr.y + cr.height / 2) : null;
    return {
      labels: btns.map(b => b.textContent),
      count: btns.length,
      role: menu.getAttribute('role'),
      itemRoles: btns.map(b => b.getAttribute('role')),
      box: { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) },
      inViewport: r.right <= window.innerWidth + 0.5 && r.bottom <= window.innerHeight + 0.5 && r.x >= 0 && r.y >= 0,
      hitOwnItem: hit ? (hit.closest('.fm-context-menu') === menu) : false,
      dangerItems: btns.filter(b => b.classList.contains('danger')).length,
    };
  });
}

const clickMenuItem = async (page, label) => {
  await page.click(`.fm-context-menu button[role="menuitem"]:has-text("${label}")`);
  await sleep(300);
};

/** 气泡读数（引用块 + dataset 挂点 + 文本）。 */
const bubbleRead = (page, mid) => page.evaluate((id) => {
  const m = document.querySelector(`.fm-flow .fm-msg[data-message-id="${id}"]`);
  if (!m) return null;
  const q = m.querySelector('.fm-quote-block');
  const note = m.querySelector('.fm-quote-note');
  const txt = m.querySelector('.fm-msg-text');
  const qr = q ? q.getBoundingClientRect() : null;
  return {
    refMessageId: m.dataset.refMessageId || null,
    quoteState: m.dataset.quoteState || null,
    body: m.dataset.body || '',
    hasQuote: !!q,
    quoteText: q ? (q.querySelector('.fm-quote-text') || {}).textContent : null,
    boxRefId: q ? (q.dataset.refMessageId || null) : null,
    boxState: q ? (q.dataset.quoteState || null) : null,
    noteText: note ? note.textContent : null,
    noteHidden: note ? note.hidden : null,
    replyText: txt ? txt.textContent : null,
    quoteBox: qr ? { x: +qr.x.toFixed(1), y: +qr.y.toFixed(1), w: +qr.width.toFixed(1), h: +qr.height.toFixed(1) } : null,
    bubbleH: +m.querySelector('.fm-msg-bubble').getBoundingClientRect().height.toFixed(1),
    cursor: q ? getComputedStyle(q).cursor : null,
  };
}, mid);

const barRead = (page) => page.evaluate(() => {
  const bar = document.querySelector('.fm-select-bar');
  // 🔴 **D5 读点迁移**（作者 2026-09-19 04:22 照案：「计数头部」）——计数宿主由底部
  // 条迁到**窗头**（`.fm-modal-select-count`）。等价强度改写：原来只断言「底部条里
  // 有计数且文案 X」；现在**两向都断言** —— ① 窗头计数文案 X 且多选期可见；
  // ② 底部条内**零**计数件（旧宿主缺席）⇒ 把「计数在哪里」这条设计令钉成机械判据，
  // 而不是把断言降级为「某处有计数」。
  const cnt = document.querySelector('.fm-modal-select-count');
  const legacyCnt = document.querySelector('.fm-select-bar .fm-select-count');
  const fw = document.querySelector('.fm-select-forward');
  const acts = [...document.querySelectorAll('.fm-select-bar .fm-select-act, .fm-select-bar .fm-select-exit')];
  const checks = [...document.querySelectorAll('.fm-flow .fm-msg-check')];
  const wraps = [...document.querySelectorAll('.fm-flow .fm-msg')];
  return {
    barPresent: !!bar,
    barHidden: bar ? bar.hidden : null,
    barDisplay: bar ? getComputedStyle(bar).display : null,
    countText: cnt ? cnt.textContent : null,
    countHidden: cnt ? cnt.hidden : null,
    countDisplay: cnt ? getComputedStyle(cnt).display : null,
    barCountPresent: !!legacyCnt,
    actClasses: acts.map(a => a.className),
    actWidths: acts.map(a => +a.getBoundingClientRect().width.toFixed(1)),
    forwardDisabled: fw ? fw.disabled : null,
    checkCount: checks.length,
    checkRoles: checks.map(c => `${c.getAttribute('role')}:${c.getAttribute('aria-checked')}`),
    checkDataset: checks.map(c => c.dataset.messageId),
    selectedDataset: wraps.map(w => `${w.dataset.messageId}=${w.dataset.selected || ''}`),
    selectedClass: wraps.filter(w => w.classList.contains('fm-selected')).map(w => w.dataset.messageId),
  };
});

// ══════════════════════════════════════════════════════════════════════
// S1 · 菜单壳：项集 / 关闭契约 / 真渲染
// ══════════════════════════════════════════════════════════════════════
async function scenarioMenu(page) {
  console.log(`\n── S1 · 右键菜单项集 · MODE=${MODE}`);
  const menu = await rightClickBubble(page, 102);
  put('menu.1', menu);
  await shot(page, 'menu-open');
  const expect = ['复制', '转发给 agent', '引用', '多选转发'];
  if (AFTER) {
    ok('S1 菜单展开：4 项（复制 / 转发给智能助手 / 引用 / 多选转发）',
      !!menu && menu.count === 4 && expect.every(l => menu.labels.includes(l)), JSON.stringify(menu && menu.labels));
    ok('S1 🔴 零删除/零撤回项（含文案）',
      !!menu && !menu.labels.some(l => /删除|撤回|delete|recall|revoke/i.test(l)) && menu.dangerItems === 0,
      JSON.stringify(menu && { labels: menu.labels, danger: menu.dangerItems }));
    ok('S1 共用菜单组件形态在册（role=menu + 每项 role=menuitem）',
      !!menu && menu.role === 'menu' && menu.itemRoles.every(r => r === 'menuitem'), JSON.stringify(menu && menu.itemRoles));
    ok('S1 浮层真渲染在视口内且在顶层（盒在视口 + elementFromPoint 命中自身）',
      !!menu && menu.inViewport && menu.hitOwnItem, JSON.stringify(menu && { box: menu.box, inViewport: menu.inViewport, hit: menu.hitOwnItem }));
  } else {
    ok('S1 改前基线：右键菜单**只有 1 项**（转发给 agent）', !!menu && menu.count === 1, JSON.stringify(menu && menu.labels));
    ok('S1 改前基线：无「引用」「多选转发」项',
      !!menu && !menu.labels.includes('引用') && !menu.labels.includes('多选转发'), JSON.stringify(menu && menu.labels));
    // 🔴 改前复现的**既有缺陷**（本批实测捕获，见报告 §缺陷）：窗内右键出的共用菜单
    //    被 `.cfg-modal-overlay`（z-index 10000 > 菜单 5000、底色全透明）盖住 ⇒
    //    ① elementFromPoint 打不到菜单项；② 真指针点不到；③ 点击还会被覆层的
    //    `e.target === overlay` 分支当成「点外」把窗关掉。
    ok('S1 改前复现（既有缺陷）：菜单项在覆层之下（elementFromPoint 命中不到自身）',
      !!menu && menu.hitOwnItem === false, JSON.stringify(menu && { hit: menu.hitOwnItem }));
  }
  // Esc（改前：菜单关掉的同时**窗也被关掉** = 同一击两动作的缺陷复现）
  const overlayBefore = await page.evaluate(() => !!document.getElementById('fm-chat-overlay'));
  await page.keyboard.press('Escape');
  await sleep(220);
  const afterEsc = await page.evaluate(() => ({
    menuGone: !document.querySelector('.fm-context-menu'),
    modalAlive: !!document.getElementById('fm-chat-overlay'),
  }));
  put('menu.esc', { overlayBefore, ...afterEsc });
  if (AFTER) {
    ok('S1 Esc 关闭菜单（且窗仍在 = 菜单独占这一下 Esc）',
      afterEsc.menuGone === true && afterEsc.modalAlive === true, JSON.stringify(afterEsc));
  } else {
    ok('S1 改前复现（既有缺陷）：Esc 同一击既关菜单**又关窗**',
      afterEsc.menuGone === true && afterEsc.modalAlive === false, JSON.stringify(afterEsc));
  }
  if (!(await page.evaluate(() => !!document.getElementById('fm-chat-overlay')))) {
    await openConv(page, 'cA');   // 改前缺陷会把窗关掉 ⇒ 续跑前重开
  }
  await rightClickBubble(page, 103);
  await page.mouse.click(640, 500);
  await sleep(220);
  ok('S1 点外关闭菜单', await page.evaluate(() => !document.querySelector('.fm-context-menu')));
  if (AFTER) {
    // 既有项行为一字不变：转发给 agent ⇒ 既有 toast 文案
    await rightClickBubble(page, 102);
    await clickMenuItem(page, '转发给 agent');
    const toast = await page.evaluate(() => {
      const el = document.querySelector('.fm-modal-toast');
      return el && !el.hidden ? el.textContent : null;
    });
    put('menu.forwardToast', toast);
    ok('S1 既有项「转发给智能助手」行为不变（既有落点 + 既有提示，无 pageerror）', toast === '已加入输入框，可附言后发送', JSON.stringify(toast));
  }
}

// ══════════════════════════════════════════════════════════════════════
// S2 · 引用：引用态 → 发送 → 气泡引用块 + dataset 挂点 + 降级 + 无跳转入口
// ══════════════════════════════════════════════════════════════════════
async function scenarioQuote(page, pageErrors) {
  console.log(`\n── S2 · 引用 · MODE=${MODE}`);
  await rightClickBubble(page, 102);
  if (!AFTER) {
    ok('S2 改前基线：无「引用」入口', await page.evaluate(() => !document.querySelector('.fm-context-menu button[role="menuitem"]')?.textContent.includes('引用')));
    await page.keyboard.press('Escape');
    await sleep(150);
    ok('S2 改前基线：无引用态条 / 无引用块',
      await page.evaluate(() => !document.querySelector('.fm-quote-strip') && !document.querySelector('.fm-quote-block')));
    return;
  }
  await clickMenuItem(page, '引用');
  const strip = await page.evaluate(() => {
    const s = document.querySelector('.fm-quote-strip');
    if (!s) return null;
    const chip = s.querySelector('.att-ref');
    const rm = s.querySelector('.att-ref-remove');
    const ib = document.querySelector('.fm-input-bar').getBoundingClientRect();
    // 修正② 口径的**同一枚**读数（输入框面引用条的 ❌ = 主窗口同类件 `closeStyle:'disc'`
    // ⇒ `.fm-quote-remove`，形态由 input.css 的 `.att-remove, .fm-quote-remove` 共享块
    // 单一来源给定）：✕ 字形中心 vs 圆盘中心（`dx`/`dy` = 偏心量，px）。
    const disc = s.querySelector('.fm-quote-remove');
    let glyph = null;
    if (disc) {
      const dr = disc.getBoundingClientRect();
      const svg = disc.querySelector('svg') || disc.querySelector('i');
      const gr = svg ? svg.getBoundingClientRect() : null;
      glyph = {
        hasSvg: !!svg,
        tag: svg ? svg.tagName : null,
        disc: { w: +dr.width.toFixed(2), h: +dr.height.toFixed(2) },
        svgBox: gr ? { w: +gr.width.toFixed(2), h: +gr.height.toFixed(2) } : null,
        dx: gr ? +((gr.x + gr.width / 2) - (dr.x + dr.width / 2)).toFixed(2) : null,
        dy: gr ? +((gr.y + gr.height / 2) - (dr.y + dr.height / 2)).toFixed(2) : null,
        bg: getComputedStyle(disc).backgroundColor,
        radius: getComputedStyle(disc).borderRadius,
        pad: getComputedStyle(disc).padding,
      };
    }
    return {
      hidden: s.hidden,
      display: getComputedStyle(s).display,
      chipCls: chip ? chip.className : null,
      chipRefType: chip ? chip.dataset.refType : null,
      chipRefId: chip ? chip.dataset.refId : null,
      chipText: chip ? chip.textContent : null,
      hasRemove: !!rm,
      removeCls: rm ? rm.className : null,
      removeLabel: rm ? rm.getAttribute('aria-label') : null,
      stripY: +s.getBoundingClientRect().y.toFixed(1),
      inputY: +ib.y.toFixed(1),
      inputBarBottom: +(ib.y + ib.height).toFixed(1),
      chipVisible: chip ? chip.getBoundingClientRect().height > 0 : false,
      disc: glyph,
      padLeft: getComputedStyle(s).paddingLeft,
      padBottom: getComputedStyle(s).paddingBottom,
    };
  });
  put('quote.strip', strip);
  await shot(page, 'quote-state');
  ok('S2 引用态：输入框面显示被引消息摘要（既有引用块组件 + 被引消息 id 钉在 refId）',
    !!strip && strip.hidden === false && strip.chipRefType === 'friend-message' && strip.chipRefId === 'ref:fm:102'
    && /分页君/.test(String(strip.chipText)) && new RegExp(QUOTE_SRC).test(String(strip.chipText)) && strip.chipVisible,
    JSON.stringify(strip));
  ok('S2 引用态：可取消（既有 × 移除键在册）', !!strip && strip.hasRemove === true, JSON.stringify(strip && strip.removeLabel));
  // 🔴 **D2 落位改写（有据变更，非放宽）**：作者 2026-09-19 04:22 照案 ⇒ 引用条由
  //    「输入条**上方**」改为「输入条**下方**」（参考图实测层序 = 输入字段在上 / 引用条在下）。
  //    原断言 `stripY < inputY` 的**方向**随设计令反转；强度**同时加强**：除方向外，追加
  //    「紧邻」（条顶与输入条底间距 ≤ 24px ⇒ 排除悬空/错层到别处）⇒ 仍是「唯一几何关系
  //    被钉死」，不是「换个方向就算过」。
  ok('S2 引用态条落在**输入条下方**且紧邻（D2 落位 · 真几何）',
    !!strip && strip.stripY > strip.inputY && (strip.stripY - strip.inputBarBottom) >= 0 && (strip.stripY - strip.inputBarBottom) <= 24,
    JSON.stringify(strip && { stripY: strip.stripY, inputY: strip.inputY, inputBarBottom: strip.inputBarBottom, gapBelowInput: +(strip.stripY - strip.inputBarBottom).toFixed(1) }));
  // 🔴 **修正①**（作者 2026-09-19 04:22 逐字：「就引用的那个X和样式可以参考我们主窗口」）：
  //    ❌ = **主窗口同类件**（`.att-remove` 共享块 → 16px 实心 `--color-error` 圆盘 + 白色
  //    ✕ SVG），**不落**设计稿的 ⊗ 变体。判据 = 类名在共享块内 + 圆盘几何 = 16px + 圆角 50%
  //    + 实心（非 transparent）+ ✕ 为 SVG 字形（非 `textContent='x'` 文本） + 居中（偏心 ≤ 0.5px）。
  ok('S2 修正①：引用条 ❌ = 主窗口同类件（`.fm-quote-remove` = 共享块选择器之一 · 16px 实心圆盘 · SVG ✕ · 居中 dx=dy=0）',
    !!strip && !!strip.disc && strip.disc.hasSvg === true && strip.disc.tag === 'svg'
    && strip.removeCls.split(/\s+/).includes('fm-quote-remove')
    && strip.removeCls.split(/\s+/).includes('att-ref-remove')
    && strip.disc.disc.w === 16 && strip.disc.disc.h === 16 && strip.disc.radius === '50%'
    && strip.disc.bg === 'rgb(244, 67, 54)'   // --color-error，与主窗口 `.att-remove` 同 token
    && strip.disc.dx === 0 && strip.disc.dy === 0,
    JSON.stringify({ cls: strip && strip.removeCls, disc: strip && strip.disc }));
  // 取消 → 再进入（两条路径都可用）
  await page.click('.fm-quote-strip .att-ref-remove');
  await sleep(200);
  const cancelled = await page.evaluate(() => {
    const s = document.querySelector('.fm-quote-strip');
    return { hidden: s.hidden, chips: s.querySelectorAll('.att-ref').length };
  });
  put('quote.cancelled', cancelled);
  ok('S2 取消：× 后引用态收口（条隐藏、零残留 chip）', cancelled.hidden === true && cancelled.chips === 0, JSON.stringify(cancelled));
  await rightClickBubble(page, 102);
  await clickMenuItem(page, '引用');
  // 发送
  const postsBefore = callsOf(c => c.method === 'POST').length;
  await page.fill('.fm-input', '好的，我准时到');
  await page.click('.fm-send-btn');
  await page.waitForSelector('.fm-flow .fm-msg[data-ref-message-id]', { timeout: 15000 });
  await sleep(600);
  const wire = callsOf(c => c.method === 'POST' && /\/messages$/.test(c.p)).map(c => c.body.body);
  put('quote.wire', wire);
  const sentId = await page.evaluate(() => {
    const m = [...document.querySelectorAll('.fm-flow .fm-msg')].find(x => x.dataset.refMessageId);
    return m ? m.dataset.messageId : null;
  });
  const read = sentId ? await bubbleRead(page, sentId) : null;
  put('quote.bubble', read);
  await shot(page, 'quote-bubble-local');
  ok('S2 发送：引用关系随消息带出（出站正文首行信封含被引 messageId + 摘要）',
    wire.length === 1 && /^> \[引用 #102\] /.test(wire[0]) && new RegExp(QUOTE_SRC).test(wire[0]) && /\n\n好的，我准时到$/.test(wire[0]),
    JSON.stringify(wire));
  ok('S2 本侧：气泡渲染出引用块（摘要 + 可辨识样式 + 卡面真几何）',
    !!read && read.hasQuote && read.quoteText && read.quoteText.includes(QUOTE_SRC) && read.quoteBox && read.quoteBox.h > 0,
    JSON.stringify(read && { quoteText: read.quoteText, box: read.quoteBox }));
  ok('S2 🔴 被引 id 可从**气泡节点 dataset** 读出（跳转预留挂点）',
    !!read && read.refMessageId === '102' && read.boxRefId === '102' && read.quoteState === 'available',
    JSON.stringify(read && { refMessageId: read.refMessageId, box: read.boxRefId, state: read.quoteState }));
  ok('S2 正文只渲染回复（信封不重复显示）', !!read && read.replyText === '好的，我准时到', JSON.stringify(read && read.replyText));
  // 视觉面**数值化**读数（本会话无视觉通道 ⇒ 不靠肉眼判图；色/几何全取计算样式）
  const qStyle = await page.evaluate((id) => {
    const m = document.querySelector(`.fm-flow .fm-msg[data-message-id="${id}"]`);
    if (!m) return null;
    const q = m.querySelector('.fm-quote-block');
    const cs = getComputedStyle(q);
    const bub = getComputedStyle(m.querySelector('.fm-msg-bubble'));
    const tr = m.querySelector('.fm-msg-text').getBoundingClientRect();
    const qr = q.getBoundingClientRect();
    return {
      quote: { background: cs.backgroundColor, boxShadow: cs.boxShadow, fontSize: cs.fontSize, color: cs.color, borderRadius: cs.borderRadius, whiteSpace: cs.whiteSpace, lineHeight: cs.lineHeight },
      bubble: { background: bub.backgroundColor, fontSize: bub.fontSize, lineHeight: bub.lineHeight },
      quoteAboveReply: qr.bottom <= tr.top + 0.5,
      quoteNoLeftAccentBar: cs.borderLeftWidth === '0px' && cs.borderLeftStyle === 'none',
    };
  }, sentId);
  put('quote.style', qStyle);
  ok('S2 引用块视觉面（数值）：卡面/字号降一档/贴合零左侧色条（设计硬约束「无 accent bars」）',
    !!qStyle && qStyle.quoteAboveReply === true && qStyle.quoteNoLeftAccentBar === true
    && qStyle.quote.fontSize === '12px' && qStyle.bubble.fontSize === '13px'
    && qStyle.quote.background !== qStyle.bubble.background,
    JSON.stringify(qStyle));
  // 🔴 **D1**（作者 2026-09-19 04:22 照案）= 引用块改**下沉面**：浮起卡面（`--color-surface`
  //    + 卡面阴影）⇒ 比气泡底再退一档的填充 + **零阴影**；间距 4/8 → 6/10；圆角 6 → 10；
  //    摘要 2 行封顶（内层 `.fm-quote-text`）。
  //    断言强度：**新增**（旧断言只钉「与原气泡底不同」）⇒ 现在钉死材质三件套（背景值 /
  //    零阴影 / 圆角·内边距）+ 夹取面；背景值随主题两档（`MM_SCHEME`）逐值钉。
  const d1 = await page.evaluate((id) => {
    const m = document.querySelector(`.fm-flow .fm-msg[data-message-id="${id}"]`);
    const q = m && m.querySelector('.fm-quote-block');
    if (!q) return null;
    const cs = getComputedStyle(q);
    const t = q.querySelector('.fm-quote-text');
    const ts = t ? getComputedStyle(t) : null;
    return {
      scheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
      background: cs.backgroundColor, boxShadow: cs.boxShadow,
      padding: `${cs.paddingTop} ${cs.paddingRight} ${cs.paddingBottom} ${cs.paddingLeft}`,
      radius: cs.borderRadius, marginBottom: cs.marginBottom,
      textClamp: ts ? (ts.webkitLineClamp || ts.lineClamp || null) : null,
      textDisplay: ts ? ts.display : null,
      textOrient: ts ? (ts.webkitBoxOrient || ts.boxOrient || null) : null,
      textOverflow: ts ? ts.overflow : null,
    };
  }, sentId);
  put('quote.d1', d1);
  const d1src = await readFile(join(WEB, 'css/friends.css'), 'utf8');
  put('quote.d1.src', { declaresWebkitBox: /\.fm-quote-block \.fm-quote-text\s*\{[^}]*display:\s*-webkit-box/.test(d1src), declaresClamp2: /\.fm-quote-block \.fm-quote-text\s*\{[^}]*line-clamp:\s*2/.test(d1src) });
  ok('S2 D1 源码面：夹取声明 = `display:-webkit-box` + `-webkit-line-clamp:2`（声明值可机械核）',
    /\.fm-quote-block \.fm-quote-text\s*\{[^}]*display:\s*-webkit-box/.test(d1src)
    && /\.fm-quote-block \.fm-quote-text\s*\{[^}]*line-clamp:\s*2/.test(d1src));
  ok('S2 D1：引用块 = 下沉面（主题两档填充 + 零阴影 + 6/10 内边距 + 10px 圆角 + 摘要 2 行封顶）',
    !!d1
    && ((d1.scheme === 'light' && d1.background === 'rgba(0, 0, 0, 0.035)')
      || (d1.scheme === 'dark' && d1.background === 'rgba(255, 255, 255, 0.055)'))
    && d1.boxShadow === 'none' && d1.padding === '6px 10px 6px 10px'
    && d1.radius === '10px' && d1.marginBottom === '6px'
    && d1.textClamp === '2' && d1.textOrient === 'vertical' && d1.textOverflow === 'hidden',
    // 🔴 `display` 的**声明值**（`-webkit-box`）由源码面单独核（`quote.d1.src`）；
    //    计算值在本引擎报 `flow-root`（Blink 把遗留 `-webkit-box` 的计算值归一化，
    //    不参与判据 —— 夹取的三件实证：clamp / orient / overflow）。
    JSON.stringify(d1));
  // 🔴 旧断言改写（quotejump 批 · 新令取代旧验收面）：一期在本行断言「引用块**无**跳转
  //    handler（点了零变化）」——那是「跳转不写」时期的判据；作者令「引用**必须可跳**」
  //    到达后该判据与本批**直接冲突**，故改写为**正向面**：入口在场（按钮语义 + 指针）
  //    且点击**真跳**（定位命中 + 高亮挂点）。改前树（`MM_MODE=before`）仍断言旧形态
  //    （无 handler ⇒ 点击零变化）⇒ 同一 harness 双模红绿钉。
  const jump = await page.evaluate(async (id) => {
    const flow = document.querySelector('.fm-flow');
    const m = flow.querySelector(`.fm-msg[data-message-id="${id}"]`);
    const q = m.querySelector('.fm-quote-block');
    const r = q.getBoundingClientRect();
    const before = flow.scrollTop;
    q.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: r.x + 2, clientY: r.y + 2 }));
    await new Promise(res => setTimeout(res, 320));
    const tgt = flow.querySelector('.fm-msg[data-message-id="102"]');
    return {
      role: q.getAttribute('role'), tabindex: q.getAttribute('tabindex'), cursor: getComputedStyle(q).cursor,
      ariaLabel: q.getAttribute('aria-label'),
      coord: q.dataset.refConversationId || null, jumpState: q.dataset.quoteJump || null,
      outcome: q.dataset.quoteJumpOutcome || null,
      scrolled: flow.scrollTop !== before,
      flash: !!(tgt && tgt.classList.contains('fm-quote-target')),
    };
  }, sentId);
  put('quote.jump', jump);
  if (AFTER) {
    ok('S2 🔴 引用块是**可点入口**（role=button + tabindex=0 + 指针 + 可访问名）且点击**真跳**（定位命中 + 高亮挂点）',
      jump.role === 'button' && jump.tabindex === '0' && jump.cursor === 'pointer' && !!jump.ariaLabel
      && jump.jumpState === 'ready' && jump.outcome === 'hit' && jump.flash === true,
      JSON.stringify(jump));
  } else {
    ok('S2 改前基线：引用块**无**跳转 handler（无 role/tabindex、cursor:auto、点击零变化、无高亮）',
      jump.role === null && jump.tabindex === null && jump.cursor === 'auto'
      && jump.outcome === null && jump.flash === false && jump.scrolled === false,
      JSON.stringify(jump));
  }
  // 对端腿：从**既有** onMessage 频道（`friend_event` 帧）注入一条对端消息，
  // 正文 = 同一编码点产出的引用信封 ⇒ 消费路径真实（producer 待跨仓契约落地）。
  const peerBody = `> [引用 #101] 分页君 · 2026-09-18 10:00 · 早上好\n\n对端的回复`;
  const sendPeerFrame = (payload) => {
    if (!WS) return;
    WS.send(JSON.stringify({ type: 'friend_event', event: 'message_new', conversationId: 'cA', ...payload }));
  };
  sendPeerFrame({ messageId: 777, senderId: 'u-p', kind: 'text', body: peerBody, createdAt: new Date().toISOString() });
  await sleep(700);
  const peerRead = await bubbleRead(page, 777);
  put('quote.peer', peerRead);
  ok('S2 对端腿（消费路径真实 · producer 模拟帧）：对端消息也渲染出引用块 + dataset 挂点',
    !!peerRead && peerRead.hasQuote && peerRead.refMessageId === '101' && new RegExp('早上好').test(String(peerRead.quoteText))
    && peerRead.replyText === '对端的回复',
    JSON.stringify(peerRead));
  // 降级：被引 id 不在窗口内
  sendPeerFrame({ messageId: 778, senderId: 'u-p', kind: 'text', body: '> [引用 #999999] 分页君 · 2026-09-18 10:00 · 早已不在窗口里的消息\n\n降级腿', createdAt: new Date().toISOString() });
  await sleep(700);
  const deg = await bubbleRead(page, 778);
  put('quote.degrade', deg);
  ok('S2 降级：被引消息不存在 ⇒ 仍显示摘要 + 「原消息不可用」',
    !!deg && deg.hasQuote && deg.refMessageId === '999999' && deg.quoteState === 'unavailable'
    && deg.noteHidden === false && deg.noteText === '原消息不可用' && String(deg.quoteText).includes('早已不在窗口里的消息'),
    JSON.stringify(deg));
  ok('S2 引用腿零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
  void postsBefore;
}

// ══════════════════════════════════════════════════════════════════════
// S3 · 多选转发：选择态 / 工具条 / Esc·点外 / 目标选择器 / 逐条原序 / 关窗归零
// ══════════════════════════════════════════════════════════════════════
async function scenarioSelect(page, pageErrors, retrySlot = null) {
  console.log(`\n── S3 · 多选转发 · MODE=${MODE}`);
  await rightClickBubble(page, 103);
  if (!AFTER) {
    const before = await page.evaluate(() => ({
      hasItem: !!document.querySelector('.fm-context-menu button[role="menuitem"]') &&
        [...document.querySelectorAll('.fm-context-menu button[role="menuitem"]')].some(b => b.textContent.includes('多选转发')),
      bar: !!document.querySelector('.fm-select-bar'),
      checks: document.querySelectorAll('.fm-flow .fm-msg-check').length,
    }));
    put('select.before', before);
    ok('S3 改前基线：无「多选转发」项 / 零选择态 / 零多选工具条',
      before.hasItem === false && before.bar === false && before.checks === 0, JSON.stringify(before));
    await page.keyboard.press('Escape');
    await sleep(150);
    return;
  }
  // 多选期间不误触 `.fm-retry`（失败气泡的左侧槽位归勾选面）：进多选**前**量下
  // 重发键的真实槽位，进多选后测「不渲染 + 点原槽位零重发」。
  const postsAtFail = callsOf(c => c.method === 'POST').length;
  if (retrySlot) {
    await rightClickBubble(page, 103);
    await clickMenuItem(page, '多选转发');
    const retryDuring = await page.evaluate((r) => {
      const b = document.querySelector('.fm-flow .fm-msg.fm-failed .fm-retry');
      const hit = document.elementFromPoint(r.x, r.y);
      return {
        exists: !!b,
        display: b ? getComputedStyle(b).display : null,
        hitAtOldSlot: hit ? (hit.className || hit.tagName) : null,
      };
    }, retrySlot);
    await page.mouse.click(retrySlot.x, retrySlot.y);
    await sleep(450);
    const postsAfter = callsOf(c => c.method === 'POST').length;
    put('select.retry', { retrySlot, retryDuring, postsAtFail, postsAfter });
    ok('S3 🔴 多选期间不误触 `.fm-retry`：重发键不渲染（display:none）+ 真指针点其原槽位**零重发**',
      retryDuring.display === 'none' && retryDuring.hitAtOldSlot !== 'fm-retry' && postsAfter === postsAtFail,
      JSON.stringify({ retrySlot, retryDuring, postsAtFail, postsAfter }));
    // 那一下点落在消息流空白处 ⇒ 按「点外退出」语义已退多选；窗仍在（Esc 此刻会关窗）
    if (await page.evaluate(() => !document.querySelector('.fm-select-bar').hidden)) {
      await page.keyboard.press('Escape');
      await sleep(220);
    }
    if (!(await page.evaluate(() => !!document.getElementById('fm-chat-overlay')))) await openConv(page, 'cA');
  }
  await rightClickBubble(page, 103);
  await clickMenuItem(page, '多选转发');
  const b0 = await barRead(page);
  put('select.enter', b0);
  await shot(page, 'select-mode');
  const msgCount = await page.evaluate(() => document.querySelectorAll('.fm-flow .fm-msg').length);
  ok('S3 选择态：工具条在场（已选 0 条 / 转发键禁用）+ 每条消息带勾选面（role=checkbox）',
    b0.barHidden === false && b0.barDisplay === 'flex' && b0.countText === '已选 0 条' && b0.forwardDisabled === true
    && b0.checkCount === msgCount && b0.checkRoles.every(r => r.startsWith('checkbox:false')),
    JSON.stringify({ ...b0, msgCount }));
  // 🔴 **D5 结构面**（照案）：① 计数的**唯一**落点 = 窗头（可见）；② 底部条内**零**计数件；
  //    ③ 动作 = **4 键等分**（转发 / 逐条转发 / 合并转发（二期）/ 退出），前 3 枚 `.fm-select-act`
  //    + 1 枚 `.fm-select-exit`；④ 合并转发 = 二期（disabled + `data-phase=2` + 标注 title）。
  //    这条断言是**新增**（旧形态：条内「已选 N 条」+ 2 键非等分）。
  const acts = await page.evaluate(() => [...document.querySelectorAll('.fm-select-bar .fm-select-act, .fm-select-bar .fm-select-exit')]
    .map(b => ({ cls: b.className, disabled: b.disabled, phase: b.dataset.phase || null, title: b.title || '', w: +b.getBoundingClientRect().width.toFixed(1) })));
  put('select.d5', { ...b0, acts });
  const mergeBtn = acts.find(a => a.cls.includes('fm-select-forward-merge'));
  ok('S3 D5：计数落窗头（可见）+ 底部条零计数件 + 4 键等分（含二期合并转发 disabled）',
    b0.countHidden === false && b0.barCountPresent === false
    && acts.length === 4
    && acts.filter(a => a.cls.includes('fm-select-act')).length === 3
    && acts.filter(a => a.cls.includes('fm-select-exit')).length === 1
    && Math.max(...acts.map(a => a.w)) - Math.min(...acts.map(a => a.w)) <= 1.5
    && !!mergeBtn && mergeBtn.disabled === true && mergeBtn.phase === '2' && mergeBtn.title.length > 0,
    JSON.stringify({ countText: b0.countText, countHidden: b0.countHidden, barCountPresent: b0.barCountPresent, acts }));
  // 🔴 勾选面真渲染判据（QA skill §5 硬断言三连：盒在视口 + elementFromPoint 命中自身
  //    + **未被 `.fm-flow` 的 overflow 裁剪**）—— in / out 两向各测一条。
  const geomProbe = await page.evaluate(() => {
    const flow = document.querySelector('.fm-flow');
    const fr = flow.getBoundingClientRect();
    const out = [];
    for (const w of document.querySelectorAll('.fm-flow .fm-msg.fm-selecting')) {
      const c = w.querySelector('.fm-msg-check');
      if (!c) continue;
      const r = c.getBoundingClientRect();
      const cx = r.x + r.width / 2; const cy = r.y + r.height / 2;
      const hit = document.elementFromPoint(cx, cy);
      out.push({
        id: w.dataset.messageId, dir: w.classList.contains('out') ? 'out' : 'in',
        rect: { x: +r.x.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) },
        clippedLeftPx: +Math.max(0, fr.left - r.left).toFixed(1),
        hitSelf: hit === c || (hit && hit.closest('.fm-msg-check') === c),
        hit: hit ? (hit.className || hit.tagName) : null,
      });
    }
    return { flowLeft: +fr.left.toFixed(1), flowPadLeft: getComputedStyle(flow).paddingLeft, overflowX: getComputedStyle(flow).overflowX, checks: out };
  });
  put('select.geom', geomProbe);
  ok('S3 🔴 勾选面真可点：零裁剪 + elementFromPoint 命中自身（in/out 两向都测）',
    geomProbe.checks.length >= 2 && geomProbe.checks.every(c => c.clippedLeftPx === 0 && c.hitSelf === true),
    JSON.stringify(geomProbe));
  // 勾选 N 条（真指针点击勾选面）
  await page.click('.fm-flow .fm-msg[data-message-id="101"] .fm-msg-check');
  await sleep(120);
  await page.click('.fm-flow .fm-msg[data-message-id="103"] .fm-msg-check');
  await sleep(150);
  const b1 = await barRead(page);
  put('select.checked', b1);
  await shot(page, 'select-checked');
  ok('S3 勾选：已选 2 条 + dataset 双面（气泡 data-selected=1 / 勾选面 aria-checked=true）',
    b1.countText === '已选 2 条' && b1.forwardDisabled === false
    && b1.selectedClass.join(',') === '101,103'
    && b1.selectedDataset.includes('101=1') && b1.selectedDataset.includes('103=1') && b1.selectedDataset.includes('102=0'),
    JSON.stringify({ count: b1.countText, cls: b1.selectedClass, ds: b1.selectedDataset }));
  // 视觉面数值化（无视觉通道 ⇒ 不靠肉眼判图）：选中/未选中两档 + 工具条
  // 过渡落定闸（voicefix-testsync 补 · 纯测试侧，期望值零动）：`.fm-msg-check` 带
  // `transition: background/border-color/color .15s`（css/friends.css:1479），而下方读数
  // 是**逐值精确**对位（`color === 'rgb(255, 255, 255)'` 与 `w/h === '22px'`）⇒ 采样若落在
  // 过渡插值中即假红（实测两档中间值 `rgba(...,0.992)` / `rgba(...,0.847)`，非产品回归：
  // 终值即期望值）。等选中面（含子树）的 CSS 过渡真正结束再读；🔴 不改任何期望值、
  // 不删不跳（纯粹消除采样时序，本批外批遗留的既有 flake，逐条登记在批报告）。
  await page.evaluate(async () => {
    const els = [...document.querySelectorAll('.fm-flow .fm-msg.fm-selected .fm-msg-check')];
    const anims = els.flatMap((e) => (e.getAnimations ? e.getAnimations({ subtree: true }) : []));
    await Promise.all(anims.map((a) => a.finished.catch(() => {})));
  });
  const sStyle = await page.evaluate(() => {
    const sel = document.querySelector('.fm-flow .fm-msg.fm-selected .fm-msg-check');
    const un = document.querySelector('.fm-flow .fm-msg:not(.fm-selected) .fm-msg-check');
    const bar = document.querySelector('.fm-select-bar');
    const cs = e => { const c = getComputedStyle(e); return { bg: c.backgroundColor, borderColor: c.borderTopColor, color: c.color, w: c.width, h: c.height, left: c.left, top: c.top, display: c.display }; };
    return { selected: sel ? cs(sel) : null, unselected: un ? cs(un) : null, bar: bar ? { display: getComputedStyle(bar).display, paddingLeft: getComputedStyle(bar).paddingLeft } : null };
  });
  put('select.style', sStyle);
  // 🔴 **D3 改写（有据变更，非放宽）**：作者 2026-09-19 04:22 照案 ⇒ 选中档由
  //    「sapphire 15% 底 + sapphire 勾色」改为 **sapphire 实心 + 白勾**（A 档）。
  //    等价强度：① 未选中仍钉「零填充 + 透明勾」（底色/勾色双面；旧断言只钉勾色）；
  //    ② 选中钉**实心 + 白勾 + 同源描边**（底色与描边逐值 = 既有 `--sapphire` token，
  //    勾色 = `#fff`）——比旧断言多钉一项（描边色）；③ 追加 **D4 几何**（22×22）⇒ 断言
  //    面只增不减。零新色值判据不变：色值仍全部来自既有 `--sapphire`（91,127,191）。
  ok('S3 勾选面视觉面（数值）：未选中「零填充 + 无勾」/ 选中 = sapphire 实心 + 白勾 + 同源描边（零新色值）',
    !!sStyle && sStyle.selected.color !== sStyle.unselected.color
    && sStyle.unselected.color === 'rgba(0, 0, 0, 0)' && sStyle.unselected.bg === 'rgba(0, 0, 0, 0)'
    && /91, 127, 191/.test(sStyle.selected.bg) && /91, 127, 191/.test(sStyle.selected.borderColor)
    && sStyle.selected.color === 'rgb(255, 255, 255)'
    && sStyle.selected.w === '22px' && sStyle.selected.h === '22px'
    && sStyle.unselected.w === '22px' && sStyle.unselected.h === '22px', JSON.stringify(sStyle));
  // 🔴 **D4**（作者 2026-09-19 04:22 照案）：22px 勾选圆落**固定左列** + 与头像**垂直居中**
  //    —— 判据 = ① 进/出两向圆心 x **逐值相等**（同一条左列，容差 0.5px）；
  //              ② 圆心 y == 头像圆心 y（容差 1px）。
  //    这条断言是**新增**（旧形态 left:-16px 时出向圆不在左列 ⇒ 无法成立）。
  // 🔴 头像带中心（40px 档）的**可判据代理**：本实现的 `.fm-msg` 行**不渲染头像**
  //    （气泡式行，`bubbleEl` 无 avatar 子节点）⇒ 「与头像中心对齐」在设计面上的
  //    等价读数 = 圆心 y 落在**行首 40px 带**的中心（rowTop + 20）；同时给出气泡首行
  //    带作为辅助读数（见 select.d4.bubble）。
  const d4 = await page.evaluate(() => {
    const flow = document.querySelector('.fm-flow');
    const fr = flow.getBoundingClientRect();
    const rows = [...flow.querySelectorAll('.fm-msg.fm-selecting')].map((w) => {
      const c = w.querySelector('.fm-msg-check');
      if (!c) return null;
      const cr = c.getBoundingClientRect();
      const wr = w.getBoundingClientRect();
      const bub = w.querySelector('.fm-msg-bubble');
      const br = bub ? bub.getBoundingClientRect() : null;
      return {
        id: w.dataset.messageId,
        dir: w.classList.contains('out') ? 'out' : 'in',
        rowTop: +wr.top.toFixed(2), rowH: +wr.height.toFixed(2),
        checkLeft: +cr.left.toFixed(2), checkCx: +(cr.x + cr.width / 2).toFixed(2),
        checkCy: +(cr.y + cr.height / 2).toFixed(2), checkW: +cr.width.toFixed(2), checkH: +cr.height.toFixed(2),
        cyFromRowTop: +(cr.y + cr.height / 2 - wr.top).toFixed(2),
        overflowBand: +Math.max(0, wr.top - cr.top).toFixed(2),
        bubbleTop: br ? +(br.top - wr.top).toFixed(2) : null, bubbleH: br ? +br.height.toFixed(2) : null,
      };
    }).filter(Boolean);
    return { rows, flowPadBoxLeft: +(fr.left + parseFloat(getComputedStyle(flow).borderLeftWidth || '0')).toFixed(2), flowPadLeft: getComputedStyle(flow).paddingLeft };
  });
  put('select.d4', d4);
  const inRows = d4.rows.filter(r => r.dir === 'in');
  const outRows = d4.rows.filter(r => r.dir === 'out');
  ok('S3 D4：22px 勾选圆落**固定左列**（进/出两向圆心 x 逐值相等 = 25px/25px）+ 圆心 = 行首 40px 头像带中心（rowTop+20）',
    d4.rows.length >= 3 && inRows.length >= 1 && outRows.length >= 1
    && d4.rows.every(r => r.checkW === 22 && r.checkH === 22)
    && Math.abs(inRows[0].checkCx - outRows[0].checkCx) <= 0.5
    && d4.rows.every(r => r.checkLeft === d4.rows[0].checkLeft)
    && d4.rows.every(r => Math.abs(r.cyFromRowTop - 20) <= 0.5),
    JSON.stringify(d4));
  // Esc 退选择态（窗不关）
  await page.keyboard.press('Escape');
  await sleep(220);
  const b2 = await barRead(page);
  put('select.esc', b2);
  ok('S3 Esc 退出选择态（工具条收口 + 勾选面撤除 + 窗仍在）',
    b2.barHidden === true && b2.checkCount === 0
    && (await page.evaluate(() => !!document.getElementById('fm-chat-overlay')))
    && b2.selectedDataset.every(s => !s.includes('=1')),
    JSON.stringify(b2));
  // 再进 → 点外退出（点窗内空白处 = 窗头：既不是气泡也不是工具条）
  await rightClickBubble(page, 102);
  await clickMenuItem(page, '多选转发');
  await page.click('.fm-flow .fm-msg[data-message-id="102"] .fm-msg-check');
  await sleep(120);
  await page.click('#fm-chat-overlay .fm-modal-header');
  await sleep(250);
  const outside = await page.evaluate(() => ({
    barHidden: document.querySelector('.fm-select-bar').hidden,
    checks: document.querySelectorAll('.fm-flow .fm-msg-check').length,
    modalAlive: !!document.getElementById('fm-chat-overlay'),
  }));
  put('select.outside', outside);
  ok('S3 点外退出选择态（窗内空白处点击 ⇒ 退多选，窗不关）',
    outside.barHidden === true && outside.checks === 0 && outside.modalAlive === true, JSON.stringify(outside));
  // 目标选择器 + 逐条转发（原序）
  await rightClickBubble(page, 101);
  await clickMenuItem(page, '多选转发');
  await page.click('.fm-flow .fm-msg[data-message-id="103"] .fm-msg-check');  // 先点后面的
  await sleep(100);
  await page.click('.fm-flow .fm-msg[data-message-id="101"] .fm-msg-check');  // 再点前面的（序判据）
  await sleep(150);
  // 🔴 **D5/D7 改写（有据变更，非放宽）**：作者 2026-09-19 04:22 照案 ⇒ 底部条改为
  //    **等分动作**且语义分裂：「转发」开**转发窗口**（D7，含附言 + 目标列表），
  //    「逐条转发」才走**既有目标选择器**（`showPopupMenu`）。⇒ 本段（目标选择器 +
  //    逐条原序）改由 `.fm-select-forward-each` 触发；等价强度：菜单组件/项集/顺序
  //    判据逐条不变（零放宽），转发窗口另有**专段**（scenarioForwardWindow）。
  await page.click('.fm-select-forward-each');
  await sleep(300);
  const picker = await page.evaluate(() => {
    const menu = document.querySelector('.fm-context-menu');
    if (!menu) return null;
    const r = menu.getBoundingClientRect();
    return {
      labels: [...menu.querySelectorAll('button[role="menuitem"]')].map(b => b.textContent),
      anchorAboveBar: r.top <= document.querySelector('.fm-select-bar').getBoundingClientRect().bottom,
      count: menu.querySelectorAll('button[role="menuitem"]').length,
    };
  });
  put('select.picker', picker);
  await shot(page, 'select-target-picker');
  ok('S3 目标选择器 = 其他会话（既有菜单组件；当前会话不在其中）',
    !!picker && picker.count === 1 && picker.labels.includes('另一好友') && !picker.labels.includes('分页君'),
    JSON.stringify(picker));
  const targetBefore = SRV.msgs.cC.length;
  await clickMenuItem(page, '另一好友');
  await sleep(900);
  const targetTail = SRV.msgs.cC.slice(targetBefore).map(r => r.body);
  put('select.forwarded', { targetTail, calls: callsOf(c => c.method === 'POST').map(c => ({ p: c.p, body: c.body.body })) });
  ok('S3 逐条转发：目标会话实收 N 条 = 选中条数，且**保持原顺序**（窗口序，非点击序）',
    targetTail.length === 2 && targetTail[0] === '早上好' && targetTail[1] === '带上笔记本',
    JSON.stringify(targetTail));
  const afterFwd = await barRead(page);
  put('select.afterForward', afterFwd);
  ok('S3 转发后选择态收口（工具条隐藏 + 勾选面撤除）', afterFwd.barHidden === true && afterFwd.checkCount === 0, JSON.stringify(afterFwd));
  // 关窗重开 ⇒ 选中归零
  await rightClickBubble(page, 102);
  await clickMenuItem(page, '多选转发');
  await page.click('.fm-flow .fm-msg[data-message-id="102"] .fm-msg-check');
  await sleep(150);
  await page.click('.fm-modal-close');
  await sleep(400);
  await openConv(page, 'cA');
  const reopened = await barRead(page);
  put('select.reopen', reopened);
  ok('S3 关窗重开：选中归零（工具条隐藏 + 零勾选面 + 无 selected 残留）',
    reopened.barHidden === true && reopened.checkCount === 0 && reopened.selectedDataset.every(s => !s.includes('=1')),
    JSON.stringify(reopened));
  // 多选期间不误触 `.fm-retry` 的读数在进入选择态时已取（见上方 select.retry）。
  // 落点回执（真渲染）：打开**目标会话**窗口，断言实收 N 条且顺序 = 转发序。
  await page.click('#fm-chat-overlay .fm-modal-close');
  await sleep(400);
  await openConv(page, 'cC');
  const received = await page.$$eval('.fm-flow .fm-msg .fm-msg-text', els => els.map(e => e.textContent));
  put('select.received', received);
  await shot(page, 'target-conversation-received');
  ok('S3 落点回执（真渲染）：目标会话窗口实收 2 条且顺序与转发序一致',
    received.length === 2 && received[0] === '早上好' && received[1] === '带上笔记本', JSON.stringify(received));
  ok('S3 多选腿零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
}

// ══════════════════════════════════════════════════════════════════════
// S4 · **D7 转发窗口**（visup-b 批 · 本批**新增面**：附言 + 目标列表 + chip 回显）
//   作者 2026-09-19 04:22「这个可以实施」= D1–D8 照案；D7 承 D5 的「转发」键语义。
//   全部读数走真渲染（真 Chromium + 真指针 + 真几何 + 真 REST 落点回执）。
// ══════════════════════════════════════════════════════════════════════
async function scenarioForwardWindow(page, pageErrors) {
  console.log(`\n── S4 · D7 转发窗口 · MODE=${MODE}`);
  const panelRead = (p) => p.evaluate(() => {
    const panel = document.querySelector('.fm-fwd-modal');
    const bar = document.querySelector('.fm-select-bar');
    if (!panel) return { present: false, barHidden: bar ? bar.hidden : null, checks: document.querySelectorAll('.fm-flow .fm-msg-check').length };
    const r = panel.getBoundingClientRect();
    const pr = getComputedStyle(panel);
    const rows = [...panel.querySelectorAll('.fm-fwd-row')];
    const check = rows[0] ? rows[0].querySelector('.fm-fwd-check') : null;
    const cr = check ? check.getBoundingClientRect() : null;
    const chip = panel.querySelector('.fm-fwd-chip');
    const chipX = panel.querySelector('.fm-fwd-chip-x');
    const xr = chipX ? chipX.getBoundingClientRect() : null;
    const okBtn = panel.querySelector('.fm-fwd-ok');
    return {
      present: true,
      cls: panel.className,
      role: panel.getAttribute('role'),
      ariaLabel: panel.getAttribute('aria-label'),
      forwardMulti: panel.dataset.forwardMulti || null,
      box: { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) },
      inViewport: r.x >= 0 && r.y >= 0 && r.right <= window.innerWidth + 0.5 && r.bottom <= window.innerHeight + 0.5,
      // 🔴 铁律 1：面板**无自带遮罩**（零背景暗化/模糊）——面板自身不是 overlay，
      //    面板子树内零 overlay 节点；全页 overlay 计数不因开面板而改变（见断言）。
      selfIsOverlay: panel.classList.contains('cfg-modal-overlay'),
      panelOverlayNodes: panel.querySelectorAll('.cfg-modal-overlay').length,
      pageOverlayNodes: document.querySelectorAll('.cfg-modal-overlay').length,
      parentIsChatOverlay: panel.parentElement ? panel.parentElement.id : null,
      backdrop: pr.backdropFilter || pr.webkitBackdropFilter || '',
      note: !!panel.querySelector('.fm-fwd-note'),
      noteRows: (panel.querySelector('.fm-fwd-note') || {}).rows || null,
      noteFocused: document.activeElement === panel.querySelector('.fm-fwd-note'),
      rowCount: rows.length,
      rowNames: rows.map(x => (x.querySelector('.fm-fwd-row-name') || {}).textContent || ''),
      rowSubs: rows.map(x => (x.querySelector('.fm-fwd-row-sub') || {}).textContent || ''),
      rowPressed: rows.map(x => x.getAttribute('aria-pressed')),
      rowIsButton: rows.map(x => x.tagName),
      checkW: cr ? +cr.width.toFixed(1) : null,
      checkH: cr ? +cr.height.toFixed(1) : null,
      checkRole: check ? check.getAttribute('role') : null,
      checkChecked: check ? check.getAttribute('aria-checked') : null,
      chips: panel.querySelectorAll('.fm-fwd-chip').length,
      chipText: chip ? chip.textContent : null,
      chipXLabel: chipX ? chipX.getAttribute('aria-label') : null,
      chipXRole: chipX ? chipX.tagName : null,
      chipXW: xr ? +xr.width.toFixed(1) : null,
      targetName: (panel.querySelector('.fm-fwd-target-name') || {}).textContent || null,
      okDisabled: okBtn ? okBtn.disabled : null,
      okText: okBtn ? okBtn.textContent : null,
      cancelText: (panel.querySelector('.fm-fwd-cancel') || {}).textContent || null,
      headerPad: (panel.querySelector('.fm-modal-header') ? getComputedStyle(panel.querySelector('.fm-modal-header')).padding : null),
      titleWeight: (panel.querySelector('.fm-modal-name') ? getComputedStyle(panel.querySelector('.fm-modal-name')).fontWeight : null),
      radius: pr.borderRadius,
      barHidden: bar ? bar.hidden : null,
      checks: document.querySelectorAll('.fm-flow .fm-msg-check').length,
    };
  });

  await rightClickBubble(page, 103);
  await clickMenuItem(page, '多选转发');
  await page.click('.fm-flow .fm-msg[data-message-id="101"] .fm-msg-check');
  await sleep(120);
  const overlayBefore = await page.evaluate(() => document.querySelectorAll('.cfg-modal-overlay').length);
  await page.click('.fm-select-forward');
  await sleep(300);
  const p0 = await panelRead(page);
  put('fwd.open', { ...p0, overlayBefore });
  await shot(page, 'fwd-window-open');
  ok('S4 D7 🔴 面板形态：`.cfg-modal.fm-fwd-modal`（毛玻璃 = 既有 .cfg-modal，**零自带遮罩**）+ role=dialog + 二期标注在册',
    p0.present === true && p0.cls.includes('cfg-modal') && p0.cls.includes('fm-fwd-modal')
    && p0.role === 'dialog' && !!p0.ariaLabel && p0.forwardMulti === 'phase2'
    && p0.selfIsOverlay === false && p0.panelOverlayNodes === 0 && p0.pageOverlayNodes === overlayBefore
    && p0.parentIsChatOverlay === 'fm-chat-overlay'
    && /blur/.test(p0.backdrop)
    && p0.inViewport === true && p0.radius !== '0px',
    JSON.stringify({ ...p0, overlayBefore }));
  ok('S4 D7 四段构造：附言（2 行 · 自动聚焦）+ 目标列表（真实 button + role=checkbox 勾选圆 22px）+ chip 回显位 + 动作行',
    p0.note === true && p0.noteRows === 2 && p0.noteFocused === true
    && p0.rowCount === 1 && p0.rowNames.join(',') === '另一好友' && p0.rowIsButton.every(t => t === 'BUTTON')
    && p0.checkRole === 'checkbox' && p0.checkChecked === 'false' && p0.checkW === 22 && p0.checkH === 22
    && p0.chips === 0 && p0.targetName === '未选择目标会话' && p0.okDisabled === true && !!p0.cancelText,
    JSON.stringify(p0));
  ok('S4 D7 开面板**不**退多选（底部条仍在场 + 勾选面仍在 + 面板在场）',
    p0.barHidden === false && p0.checks >= 3, JSON.stringify({ barHidden: p0.barHidden, checks: p0.checks }));
  // 选目标（真实 pointer）⇒ 单选语义（点一行换目标；多目标 = 二期）
  await page.click('.fm-fwd-modal .fm-fwd-row');
  await sleep(220);
  const p1 = await panelRead(page);
  put('fwd.picked', p1);
  ok('S4 D7 选目标：行 aria-pressed=true + 勾选圆 aria-checked=true + chip 回显（可单个移除）+ 目标行「名称 · 单聊」+ 发送键解禁',
    p1.rowPressed.join(',') === 'true' && p1.checkChecked === 'true'
    && p1.chips === 1 && /另一好友/.test(String(p1.chipText)) && p1.chipXRole === 'BUTTON' && p1.chipXW === 16
    && p1.targetName === '另一好友 · 单聊' && p1.okDisabled === false && p1.okText.length > 0,
    JSON.stringify(p1));
  await shot(page, 'fwd-window-picked');
  // chip 单删 ⇒ 回到未选（发送键重新禁用、行为 aria-pressed=false）
  await page.click('.fm-fwd-modal .fm-fwd-chip-x');
  await sleep(200);
  const p2 = await panelRead(page);
  put('fwd.chipRemoved', p2);
  ok('S4 D7 chip 单删：回未选态（chip 清零 + 行 aria-pressed=false + 勾选圆 false + 发送键重禁）',
    p2.chips === 0 && p2.rowPressed.join(',') === 'false' && p2.checkChecked === 'false' && p2.okDisabled === true,
    JSON.stringify(p2));
  // 面板内 Esc ⇒ 收面板，但**不退**多选（多选态可原地重开）
  await page.keyboard.press('Escape');
  await sleep(250);
  const p3 = await page.evaluate(() => ({
    panel: !!document.querySelector('.fm-fwd-modal'),
    barHidden: document.querySelector('.fm-select-bar').hidden,
    checks: document.querySelectorAll('.fm-flow .fm-msg-check').length,
    modalAlive: !!document.getElementById('fm-chat-overlay'),
  }));
  put('fwd.esc', p3);
  ok('S4 D7 面板内 Esc：收面板 + 不退多选 + 窗仍在（单一 Esc 只吃一层）',
    p3.panel === false && p3.barHidden === false && p3.checks >= 3 && p3.modalAlive === true, JSON.stringify(p3));
  // 窗内空白一击（面板在场）⇒ 先收面板（多选态保留）
  await page.click('.fm-select-forward');
  await sleep(250);
  await page.click('#fm-chat-overlay .fm-modal-header');
  await sleep(250);
  const p4 = await page.evaluate(() => ({
    panel: !!document.querySelector('.fm-fwd-modal'),
    barHidden: document.querySelector('.fm-select-bar').hidden,
    checks: document.querySelectorAll('.fm-flow .fm-msg-check').length,
  }));
  put('fwd.outsideFirst', p4);
  ok('S4 D7 窗内空白一击：面板在场 ⇒ 只收面板（多选态保留，可原地重开）',
    p4.panel === false && p4.barHidden === false && p4.checks >= 3, JSON.stringify(p4));
  // 再一击（面板不在场）⇒ 既有「点外退出多选」
  await page.click('#fm-chat-overlay .fm-modal-header');
  await sleep(250);
  const p5 = await page.evaluate(() => ({
    barHidden: document.querySelector('.fm-select-bar').hidden,
    checks: document.querySelectorAll('.fm-flow .fm-msg-check').length,
  }));
  put('fwd.outsideSecond', p5);
  ok('S4 D7 面板不在场时的窗内空白一击 = 既有「点外退出多选」（语义未被新面改动）',
    p5.barHidden === true && p5.checks === 0, JSON.stringify(p5));
  // 端到端：勾 2 条 + 附言 ⇒ 目标会话实收「附言 + 2 条原序」
  await rightClickBubble(page, 101);
  await clickMenuItem(page, '多选转发');
  await page.click('.fm-flow .fm-msg[data-message-id="103"] .fm-msg-check');
  await sleep(100);
  await page.click('.fm-flow .fm-msg[data-message-id="101"] .fm-msg-check');
  await sleep(150);
  await page.click('.fm-select-forward');
  await sleep(250);
  await page.fill('.fm-fwd-modal .fm-fwd-note', '见上，转发给你');
  await page.click('.fm-fwd-modal .fm-fwd-row');
  await sleep(180);
  const targetBefore = SRV.msgs.cC.length;
  await page.click('.fm-fwd-modal .fm-fwd-ok');
  await sleep(1200);
  const got = SRV.msgs.cC.slice(targetBefore).map(r => r.body);
  put('fwd.sent', { got, calls: callsOf(c => c.method === 'POST').map(c => c.body.body) });
  await shot(page, 'fwd-sent-receipt');
  ok('S4 D7 端到端：附言先发（独立一条）+ 2 条被转消息按**窗口序**（非点击序），面板随之收口',
    got.length === 3 && got[0] === '见上，转发给你' && got[1] === '早上好' && got[2] === '带上笔记本',
    JSON.stringify(got));
  const after = await page.evaluate(() => ({
    panel: !!document.querySelector('.fm-fwd-modal'),
    barHidden: document.querySelector('.fm-select-bar').hidden,
    checks: document.querySelectorAll('.fm-flow .fm-msg-check').length,
  }));
  put('fwd.afterSend', after);
  ok('S4 D7 发送后收口：面板移除 + 退多选 + 勾选面撤除（复用同一条 exitSelection）',
    after.panel === false && after.barHidden === true && after.checks === 0, JSON.stringify(after));
  ok('S4 转发窗口腿零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
}

// ══════════════════════════════════════════════════════════════════════
// S6 · **修正②（主窗口附件 ❌ 居中）** + **修正④（主窗语音输入形态 + 反馈保留）**
//   两件都在**主窗口**面（真产品入口 `index.html`），故与好友窗场景分开跑。
//   修正② 的「改前」面 = 主树声明块的**逐字重建**（同页同字体同底色渲染 ⇒ 前后同框
//   可比）；像素级读数由 `MM_SHOTS` 落盘后经 `evidence/.../raw/disc_center.py` 出。
//   修正④ 曾判「普通麦克风 + 零 orb 节点」（visup-b，作者 2026-09-19 04:22）；voicefix
//   批（commit `aaaaa7765`，作者 2026-09-21 14:0x ③「把最近的有光球的回退回来」）已把
//   挂载判据**回退** ⇒ 本批（voicefix-testsync）按「纯测试同步、零删除零跳过、断言强度
//   零减」把修正④ 读数改为**新终态对位**：主窗 = 光球形态 + 九态机反馈；反馈类/aria
//   判据（setMicState 侧，行为未改）逐条保留，视觉载体判据（`.icon-btn` 专属的绿底/
//   `voicePulse`/sapphire 色）随旧形态退役，改为光球态（`#mic-orb-wrap.s-*` +
//   `getMicOrb().state`）。新增 S6b = 好友/群窗麦克风在位（voicefix 腿 B 新终态，
//   旧规格零覆盖 ⇒ 最小用例补齐）。
// ══════════════════════════════════════════════════════════════════════
async function scenarioUiFaces(page, pageErrors) {
  console.log(`\n── S6 · 修正②/④ · MODE=${MODE} · scheme=${MM_SCHEME} ──`);
  // ── 修正④ ①（voicefix 新终态）：形态面 = 主输入区液态光球；旧「普通麦克风 + 零
  //    orb 节点」判据随挂载判据回退整体翻转 ⇒ 同一组读数改**正向对位**（判据数不减、
  //    「单一挂载点、不留游离/重复件」的严格度照旧）。启动静止态无 `aria-pressed`
  //    （旧形态在 index.html 硬编码 false；新形态由 setMicState 首态落值 ⇒ 由下方
  //    录音/转写两态读数逐条钉死 true/false）。 ──────────────────────────────────
  const mic = await page.evaluate(async () => {
    const b = document.getElementById('voice-btn');
    const r = b ? b.getBoundingClientRect() : null;
    const cs = b ? getComputedStyle(b) : null;
    const wrap = b ? b.closest('.mic-orb-wrap') : null;
    const micOrb = await import('/js/micOrb.js');
    const o = micOrb.getMicOrb();
    return {
      present: !!b,
      tag: b ? b.tagName : null,
      cls: b ? b.className : null,
      type: b ? b.getAttribute('type') : null,
      title: b ? b.getAttribute('title') : null,
      ariaLabel: b ? b.getAttribute('aria-label') : null,
      ariaPressed: b ? b.getAttribute('aria-pressed') : null,
      box: r ? { w: +r.width.toFixed(1), h: +r.height.toFixed(1) } : null,
      radius: cs ? cs.borderRadius : null,
      wrapId: wrap ? wrap.id : null,
      orbCanvasInBtn: !!(b && b.querySelector('.orb-canvas#mic-canvas')),
      cssOrbInBtn: !!(b && b.querySelector('.css-orb#mic-css-orb')),
      wrapNodes: document.querySelectorAll('.mic-orb-wrap').length,
      bubbleNodes: document.querySelectorAll('.micbubble').length,
      orbCanvasNodes: document.querySelectorAll('.orb-canvas').length,
      orbCssNodes: document.querySelectorAll('.css-orb').length,
      orbRippleNodes: document.querySelectorAll('.orb-ripple').length,
      orbIds: ['mic-canvas', 'mic-css-orb', 'mic-orb-wrap'].filter(id => document.getElementById(id)),
      singleton: !!o,
      singletonBound: !!o && !!b && o.btn === b && o.canvas === (b ? b.querySelector('.orb-canvas') : null),
    };
  });
  put('mic.form', mic);
  await shot(page, 'main-mic-idle');
  ok('S6 修正④① 光球形态面（voicefix 新终态）：`#voice-btn` = 光球钮（`.micbubble` · 64×64 圆键 · 内嵌 `#mic-canvas` + `#mic-css-orb` · 挂 `#mic-orb-wrap`）+ `aria-pressed` 静止态未落值（null，首态由 setMicState 落）',
    mic.present === true && mic.tag === 'BUTTON' && mic.type === 'button'
    && mic.cls.split(/\s+/).includes('micbubble')
    && mic.wrapId === 'mic-orb-wrap' && mic.orbCanvasInBtn && mic.cssOrbInBtn
    && !!mic.title && !!mic.ariaLabel && mic.ariaPressed === null
    && mic.box.w === 64 && mic.box.h === 64 && mic.radius === '50%',
    JSON.stringify(mic));
  ok('S6 修正④① 🔴 单一光球挂载点（DOM 面）：宿主/光球钮/画布/css-orb **各恰一件**（零游离/重复件，涟漪只在按下期出现）+ 三个 id 全在册 + MicOrb 单例绑定产品画布（旧「零命中 + 三 id 全缺席」判据的正向对位，严格度不降）',
    mic.wrapNodes === 1 && mic.bubbleNodes === 1 && mic.orbCanvasNodes === 1 && mic.orbCssNodes === 1
    && mic.orbRippleNodes === 0 && mic.orbIds.length === 3 && mic.singleton === true && mic.singletonBound === true,
    JSON.stringify({ wrap: mic.wrapNodes, bubble: mic.bubbleNodes, canvas: mic.orbCanvasNodes, css: mic.orbCssNodes, ripple: mic.orbRippleNodes, ids: mic.orbIds, bound: mic.singletonBound }));
  // 源码面在位（输入区形态三件：宿主 DOM / 形态 CSS / 挂载点）——「回退」不是只回退
  // DOM：`index.html` 挂载四件齐 + `css/input.css` 材质四件 + 九态类/涟漪标记齐
  // （同「源码面」严格度，方向随退役面翻转改**正向对位**）。
  const srcHits = {};
  const SRC_TOKENS = ['mic-orb-wrap', 'micbubble', 'orb-canvas', 'css-orb'];
  for (const rel of ['index.html', 'css/input.css']) {
    const txt = await readFile(join(WEB, rel), 'utf8');
    srcHits[rel] = SRC_TOKENS.filter(tok => new RegExp(tok.replace(/[-.]/g, '[-.]'), 'i').test(txt));
  }
  const orbCssTxt = await readFile(join(WEB, 'css/input.css'), 'utf8');
  srcHits['css/orb-state-tokens'] = ['orb-ripple', 's-offline', 's-listening', 's-processing']
    .filter(tok => new RegExp(tok.replace(/[-.]/g, '[-.]'), 'i').test(orbCssTxt));
  put('mic.sourceHits', srcHits);
  ok('S6 修正④① 🔴 源码面在位（`index.html` 挂载四件 + `css/input.css` 材质四件 + 九态类/涟漪标记齐备——「回退」不是只回退 DOM）',
    srcHits['index.html'].length === SRC_TOKENS.length && srcHits['css/input.css'].length === SRC_TOKENS.length
    && srcHits['css/orb-state-tokens'].length === 4, JSON.stringify(srcHits));
  // ── 修正④ ②：语音反馈**保留**（录音中 / 转写中 两态真转换读数；voicefix 新终态：
  //  视觉载体 = 光球九态机，`.icon-btn` 专属的绿底/脉冲/`sapphire` 色随旧形态退役） ──
  //  真转换：点 mic（云 STT 路径开 ⇒ 真 `listening`）→ 再点（`stopVoice` ⇒ 真
  //  `processing`，因云腿在等转写应答而**持续在场**）⇒ 两态都可读。
  await page.evaluate(async () => {
    const m = await import('/js/state.js');
    m.default.stt = { sttConfigured: true };
  });
  await page.click('#voice-btn');
  await page.waitForFunction(() => document.getElementById('voice-btn').classList.contains('recording'), null, { timeout: 5000 });
  await sleep(300);   // 等 `.icon-btn`/光球态过渡落定（否则读到插值中间态）
  const rec = await page.evaluate(async () => {
    const b = document.getElementById('voice-btn');
    const cs = getComputedStyle(b);
    const wrap = document.getElementById('mic-orb-wrap');
    const o = (await import('/js/micOrb.js')).getMicOrb();
    return { cls: b.className, ariaPressed: b.getAttribute('aria-pressed'), bg: cs.backgroundColor, color: cs.color, anim: cs.animationName, animDur: cs.animationDuration, wrapCls: wrap ? wrap.className : null, orbState: o ? o.state : null };
  });
  put('mic.recording', rec);
  await shot(page, 'main-mic-recording');
  ok('S6 修正④② 反馈·录音中（新终态）：`.recording` 类 + `aria-pressed=true` 在册（setMicState 侧行为未改）**且**光球九态机可见落态 = `#mic-orb-wrap` 带 `s-listening`、`getMicOrb().state` = "listening"（原微信绿底 + `voicePulse` 计算样式判据随普通麦克风形态退役，视觉载体改为光球态——判据数不减）',
    rec.cls.split(/\s+/).includes('recording') && rec.ariaPressed === 'true'
    && String(rec.wrapCls).split(/\s+/).includes('s-listening') && rec.orbState === 'listening',
    JSON.stringify(rec));
  await page.evaluate(() => window.__mic.pump(0.4));   // 让云腿真有音频（否则退化成 noAudio 错误态）
  await page.click('#voice-btn');
  await sleep(400);
  const proc = await page.evaluate(async () => {
    const b = document.getElementById('voice-btn');
    const cs = getComputedStyle(b);
    const wrap = document.getElementById('mic-orb-wrap');
    const o = (await import('/js/micOrb.js')).getMicOrb();
    return { cls: b.className, ariaPressed: b.getAttribute('aria-pressed'), color: cs.color, anim: cs.animationName, wrapCls: wrap ? wrap.className : null, orbState: o ? o.state : null };
  });
  put('mic.processing', proc);
  await shot(page, 'main-mic-processing');
  ok('S6 修正④② 反馈·转写中（新终态）：`.recording` 撤除 + `.processing` 在册 + `aria-pressed=false` + 零 CSS 动画（读数保留）**且**光球落态 = `#mic-orb-wrap` 带 `s-processing`、`getMicOrb().state` = "processing"（原 sapphire 色判据随 `.mic-btn.processing` 规则退役，视觉载体改为光球态——判据数不减）',
    proc.cls.split(/\s+/).includes('processing') && !proc.cls.split(/\s+/).includes('recording')
    && proc.ariaPressed === 'false' && proc.anim === 'none'
    && String(proc.wrapCls).split(/\s+/).includes('s-processing') && proc.orbState === 'processing',
    JSON.stringify(proc));
  // ── 修正②：主窗口图片/文件附件的 ❌ 居中（前/后同框） ──────────────────────
  const discGeom = await page.evaluate(async () => {
    const chat = await import('/js/chat.js');
    // 中性缩略图（canvas 生成 ⇒ 必为合法 PNG；色 = 面板近邻灰蓝，**非红** ⇒
    // 像素管线的红盘掩码不会被缩略图污染）。
    const cv = document.createElement('canvas');
    cv.width = cv.height = 8;
    const cg = cv.getContext('2d');
    cg.fillStyle = '#3a4150';
    cg.fillRect(0, 0, 8, 8);
    const px = cv.toDataURL('image/png');
    const host = document.getElementById('attachment-preview');
    // ① 改后（产品渲染器：`.att-remove` + 单一 `CLOSE_X_SVG`）
    const after = [{ type: 'image', mimeType: 'image/png', data: '', name: 'shot.png', preview: px },
      { type: 'file', name: 'note.txt' }];
    const probe = document.createElement('div');
    probe.id = 'visupb-probe';
    probe.style.cssText = 'position:fixed;left:24px;top:120px;z-index:99999;display:flex;flex-direction:column;gap:24px;padding:12px;background:#1c202c';
    document.body.appendChild(probe);
    const hostA = document.createElement('div');
    hostA.style.cssText = 'position:relative;display:flex;gap:48px;padding:16px 24px';
    probe.appendChild(hostA);
    chat.renderAttachmentPreview({ attPreviewEl: hostA, attachments: after });
    // ② 改前（主树声明块逐字重建：`git show HEAD:src/main/resources/web/css/input.css`
    //    的 `.att-remove` 原值 + `textContent='x'`）——同页同字体同底色 ⇒ 可比。
    const hostB = document.createElement('div');
    hostB.style.cssText = 'position:relative;display:flex;gap:48px;padding:16px 24px';
    probe.appendChild(hostB);
    const mkOld = () => {
      const wrap = document.createElement('div');
      wrap.style.cssText = 'position:relative;width:96px;height:72px;background:#2a2f3a;border-radius:8px';
      const rm = document.createElement('div');
      rm.className = 'att-remove visupb-old';
      rm.style.cssText = 'position:absolute;top:-4px;right:-4px;width:16px;height:16px;background:var(--color-error);color:#fff;border-radius:50%;font-size:10px;display:flex;align-items:center;justify-content:center;cursor:pointer';
      rm.textContent = 'x';
      wrap.appendChild(rm);
      return wrap;
    };
    hostB.append(mkOld(), mkOld());
    const read = (sel) => [...document.querySelectorAll(sel)].map((el) => {
      const r = el.getBoundingClientRect();
      const svg = el.querySelector('svg');
      const sr = svg ? svg.getBoundingClientRect() : null;
      // 文本字形（改前形态）：用 Range 量**字形盒**（selection rect = em 盒，非 ink 盒
      // ⇒ 只作辅助读数；ink 级读数走像素管线 disc_center.py）。
      const t = el.firstChild && el.firstChild.nodeType === 3 ? el.firstChild : null;
      let glyph = null;
      if (t) {
        const rg = document.createRange();
        rg.selectNodeContents(t);
        const gr = rg.getBoundingClientRect();
        glyph = { w: +gr.width.toFixed(2), h: +gr.height.toFixed(2), dx: +((gr.x + gr.width / 2) - (r.x + r.width / 2)).toFixed(2), dy: +((gr.y + gr.height / 2) - (r.y + r.height / 2)).toFixed(2) };
      }
      return {
        cls: el.className, text: el.textContent,
        disc: { x: +r.x.toFixed(2), y: +r.y.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2) },
        radius: getComputedStyle(el).borderRadius, bg: getComputedStyle(el).backgroundColor,
        svg: sr ? { w: +sr.width.toFixed(2), h: +sr.height.toFixed(2), dx: +((sr.x + sr.width / 2) - (r.x + r.width / 2)).toFixed(2), dy: +((sr.y + sr.height / 2) - (r.y + r.height / 2)).toFixed(2) } : null,
        rangeGlyph: glyph,
      };
    });
    // 探针容器相对定位：把两组的 ❌ 排在**同一 y 基线**上，便于像素切片比较
    const a = read('#visupb-probe > div:nth-child(1) .att-remove');
    const b = read('#visupb-probe > div:nth-child(2) .att-remove');
    return { after: a, before: b, probeBox: (() => { const r = probe.getBoundingClientRect(); return { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; })() };
  });
  put('disc.geom', discGeom);
  await shot(page, 'disc-before-after');
  const aDisc = discGeom.after[0];
  ok('S6 修正②（改后 · 产品渲染面）：`.att-remove` = 16×16 实心圆 + 白色 SVG ✕，**图形中心 == 圆盘中心**（dx=dy=0.00px，容差 0）',
    !!aDisc && aDisc.disc.w === 16 && aDisc.disc.h === 16 && aDisc.radius === '50%'
    && aDisc.text === '' && !!aDisc.svg && aDisc.svg.w === 8 && aDisc.svg.h === 8
    && aDisc.svg.dx === 0 && aDisc.svg.dy === 0,
    JSON.stringify(discGeom.after));
  const bDisc = discGeom.before[0];
  // 🔴 诚实申报：`textContent='x'` 的偏心**在 DOM 侧不可判** —— `Range.getBoundingClientRect()`
  //    量到的是**行盒**（此处置 10px 字体的 12px 行盒，且被 flex 居中 ⇒ dx=dy=0），
  //    而行盒几何对 ink（字形）位置是盲的：偏心量只在**像素级**可见。⇒ 这条断言只钉
  //    「改前形态 = 文本字形 + 行盒 12px」，ink 级前/后读数由像素管线给
  //    （`evidence/.../raw/disc_center.py` 对同一张 `disc-before-after.png` 出数）。
  ok('S6 修正②（改前 · 同页重建）：图形 = 文本 `x`（10px 字体 ⇒ 12px 行盒；DOM 侧对 ink 偏心不可判 ⇒ 必须走像素管线）',
    !!bDisc && bDisc.text === 'x' && !bDisc.svg && !!bDisc.rangeGlyph
    && bDisc.rangeGlyph.h === 12,
    JSON.stringify(discGeom.before));
  // 机械判据：❌ 的**形态**只有**一个**声明块（两处消费者同块 ⇒ 改一处两侧同步，
  // 与 `scripts/check-msgstyle-single-source.mjs` 的单源判据同口径）。
  const cssTxt = await readFile(join(WEB, 'css/input.css'), 'utf8');
  const sharedBlock = /\.att-remove,\s*\n?\s*\.fm-quote-remove\s*\{/.test(cssTxt);
  const svgOnce = (cssTxt.match(/\.att-remove svg,\s*\n\s*\.fm-quote-remove svg\s*\{/g) || []).length === 1;
  put('disc.singleSource', { sharedBlock, svgOnce, hasLegacyFontSize: /\.att-remove \{[^}]*font-size: 10px/.test(cssTxt) });
  ok('S6 修正② 单一来源：`.att-remove, .fm-quote-remove` **同一声明块**（形态一处定义）+ ✕ 尺寸一处定义（`input.css` 机械核）',
    sharedBlock === true && svgOnce === true, JSON.stringify({ sharedBlock, svgOnce }));
  ok('S6 修正② 前后同框可比：改后/改前渲染面的圆盘几何逐值相等（16×16 / 50%）',
    !!aDisc && !!bDisc && aDisc.disc.w === bDisc.disc.w && aDisc.disc.h === bDisc.disc.h
    && aDisc.radius === bDisc.radius && aDisc.bg === bDisc.bg,
    JSON.stringify({ afterW: aDisc && aDisc.disc.w, beforeW: bDisc && bDisc.disc.w, afterBg: aDisc && aDisc.bg, beforeBg: bDisc && bDisc.bg }));
  ok('S6 主窗口面零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
}

// ══════════════════════════════════════════════════════════════════════
// S6b · voicefix 腿 B 新终态（旧规格**零覆盖** ⇒ 本批补**最简用例**）
//   好友/群窗**简单麦克风在位**：形态面 + 两窗**真接线**读数（点一下开 ⇒ 真 listening；
//   好友窗再点一下关 ⇒ processing —— 同一 voiceEngine 单例、同一「点开点关」令）。
//   门 = `conv.kind !== 'device'`（messages.js:1399 单点）：好友窗（conv 无 kind 键）与
//   群窗（kind='group'）同源 ⇒ 本用例取**两个正向面**（各钉一次真接线）；负向面
//   （设备窗不挂）由判词位 voicefix-verify harness B4 运行时夹逼在案，本用例不虚构造
//   假设备会话（最简口径，边界见批报告）。
//   判据方向 = **新终态对位**（旧规格对本面零覆盖 ⇒ 无旧判据可翻转；零删除零跳过照旧）。
// ══════════════════════════════════════════════════════════════════════
async function scenarioFmMicLeg(page, pageErrors) {
  console.log(`\n── S6b · 好友/群窗麦克风（voicefix 腿 B）· MODE=${MODE} · scheme=${MM_SCHEME} ──`);
  // 云 STT 腿开（与 S6 同一夹具口径：VOICE_HARNESS 假 AudioContext + 假 getUserMedia）
  await page.evaluate(async () => {
    const m = await import('/js/state.js');
    m.default.stt = { sttConfigured: true };
  });
  /** 输入条形态读数器（好友窗 / 群窗同一读数器，零复制）。 */
  const readBar = () => page.evaluate(() => {
    const bar = document.querySelector('.fm-input-bar');
    if (!bar) return { barPresent: false };
    const btn = bar.querySelector('.fm-mic-btn');
    const r = btn ? btn.getBoundingClientRect() : null;
    const cs = btn ? getComputedStyle(btn) : null;
    const svg = btn ? btn.querySelector('svg') : null;
    return {
      barPresent: true,
      present: !!btn,
      leftmost: !!btn && bar.children[0] === btn,
      domOrder: [...bar.children].map(e => e.className || e.tagName),
      cls: btn ? btn.className : null,
      tag: btn ? btn.tagName : null,
      type: btn ? btn.getAttribute('type') : null,
      title: btn ? btn.getAttribute('title') : null,
      ariaLabel: btn ? btn.getAttribute('aria-label') : null,
      ariaPressed: btn ? btn.getAttribute('aria-pressed') : null,
      iconSvgCls: svg ? svg.getAttribute('class') : null,
      orbNodes: bar.querySelectorAll('.mic-orb-wrap, .micbubble, .orb-canvas, .css-orb').length,
      box: r ? { w: +r.width.toFixed(1), h: +r.height.toFixed(1) } : null,
      radius: cs ? cs.borderRadius : null,
      inputPresent: !!bar.querySelector('.fm-input'),
      sendPresent: !!bar.querySelector('.fm-send-btn'),
    };
  });
  const micState = () => page.evaluate(() => {
    const b = document.querySelector('.fm-mic-btn');
    return { cls: b ? b.className : null, ariaPressed: b ? b.getAttribute('aria-pressed') : null };
  });
  /** 形态面共同判据（两窗同源，避免复制判据表达式）。 */
  const shapeOk = (m) => m.barPresent === true && m.present === true && m.leftmost === true
    && m.tag === 'BUTTON' && m.type === 'button'
    && String(m.cls).split(/\s+/).includes('icon-btn') && String(m.cls).split(/\s+/).includes('fm-mic-btn')
    && !!m.title && !!m.ariaLabel && m.ariaPressed === 'false'
    && /lucide/.test(String(m.iconSvgCls))
    && m.box && m.box.w === 36 && m.box.h === 36 && m.radius === '50%'
    && m.orbNodes === 0 && m.inputPresent === true && m.sendPresent === true;

  // ── ① 好友窗（conv 无 kind 键 ⇒ 门内第一面）形态面 ─────────────────────
  await openConv(page, 'cA');
  const fm = await readBar();
  put('s6b.friend.form', fm);
  await shot(page, 'friend-mic-idle');
  ok('S6b 好友窗（voicefix 腿 B 新终态）：`.fm-input-bar` **最左** = 简单麦克风键（`.icon-btn.fm-mic-btn` · 36×36 圆键 · lucide `mic` 已换渲染 · `title`/`aria-label` 在册 · 静止 `aria-pressed="false"`）+ 输入框/发送键同条在位',
    shapeOk(fm), JSON.stringify(fm));
  ok('S6b 好友窗 🔴 禁气泡形态（作者 2026-09-19 04:22 修正④ 对**会话窗**仍为现行令）：会话窗输入条零光球件（`.mic-orb-wrap`/`.micbubble`/`.orb-canvas`/`.css-orb` 全 0 节点 —— 气泡/光球是**主窗**形态，不进会话窗）',
    fm.orbNodes === 0, JSON.stringify({ n: fm.orbNodes, domOrder: fm.domOrder }));

  // ── ② 好友窗**真接线**（形态在位 ≠ 接通：点一下开 ⇒ 再点一下关）─────────
  await page.click('.fm-mic-btn');
  await sleep(150);
  // 云腿录音器就绪 = 键真接进 startDictation（处理器在场才能泵音频）；未就绪则重试
  const pumped = await page.waitForFunction(() => {
    try { window.__mic.pump(0.4); return true; } catch { return false; }
  }, null, { timeout: 5000 }).then(() => true).catch(() => false);
  const rec = await micState();
  put('s6b.friend.recording', { ...rec, pumped });
  await shot(page, 'friend-mic-recording');
  ok('S6b 好友窗反馈·录音中：点一下开 ⇒ `.recording` 在场 + `aria-pressed="true"`（同一 `fmSetMicState` 写点）**且**云腿录音器真被拉起（`window.__mic.pump()` 命中音频处理器 ⇒ 键接了 `startDictation`，不是只挂了个 DOM）',
    String(rec.cls).split(/\s+/).includes('recording') && rec.ariaPressed === 'true' && pumped === true,
    JSON.stringify({ ...rec, pumped }));
  // 🔴 `force: true`：`.recording` 态按钮跑无限 `voicePulse` 动画 ⇒ Playwright
  //    actionability「stable」永不满足（判词位 voicefix-verify 假红排查 #4 同款约束）。
  await page.click('.fm-mic-btn', { force: true });
  await sleep(250);
  const proc = await micState();
  put('s6b.friend.processing', proc);
  ok('S6b 好友窗反馈·转写中：再点一下关 ⇒ `.recording` 撤除 + `.processing` 在场 + `aria-pressed="false"`（尾段转写在飞 ⇒ 持续在场，与主窗同一收敛约定）',
    String(proc.cls).split(/\s+/).includes('processing') && !String(proc.cls).split(/\s+/).includes('recording')
    && proc.ariaPressed === 'false',
    JSON.stringify(proc));
  // 关窗收敛（`closeChat` 全路径漏斗调 `modalEls.stopVoice`）
  await page.click('#fm-chat-overlay .fm-modal-close');
  await sleep(400);
  const closed = await page.evaluate(() => ({
    modal: !!document.querySelector('#fm-chat-overlay'),
    micLeft: document.querySelectorAll('.fm-mic-btn').length,
  }));
  put('s6b.friend.closed', closed);
  ok('S6b 好友窗关窗收敛：窗体撤除 + 窗内麦克风键随之离场（`closeChat` 同拍 `stopVoice` 的可见面）',
    closed.modal === false && closed.micLeft === 0, JSON.stringify(closed));

  // ── ③ 群窗（kind='group' ⇒ 门内第二面）形态面 + 真接线 ────────────────
  await openConv(page, GCONV);
  const gp = await readBar();
  put('s6b.group.form', gp);
  await shot(page, 'group-mic-idle');
  ok('S6b 群窗（同一 `conv.kind !== \'device\'` 门的第二面）：群会话窗输入条最左同款麦克风键在位 + 零光球件（好友/群同源，谓词单点）',
    shapeOk(gp), JSON.stringify(gp));
  await page.click('.fm-mic-btn');
  await sleep(150);
  const grec = await micState();
  put('s6b.group.recording', grec);
  ok('S6b 群窗真接线：点一下开 ⇒ `.recording` + `aria-pressed="true"`（群窗 handler 为**独立闭包实例** ⇒ 形态在位之外另钉一次接通，不靠「代码同源」推断）',
    String(grec.cls).split(/\s+/).includes('recording') && grec.ariaPressed === 'true',
    JSON.stringify(grec));

  put('s6b.pageErrors', pageErrors);
  ok('S6b 好友/群窗面零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
}

// ══════════════════════════════════════════════════════════════════════
// S5 · 引用跳转（quotejump 批）：双写载荷 / 坐标双读 / 真跳三路径 / 降级 / 跨会话 / D-1
// ══════════════════════════════════════════════════════════════════════
const JCONV = 'cJ';        // 跳转场：240 条消息（首窗只覆盖最近 200 条 ⇒ #5 在窗口外）
const GCONV = 'gJ';        // 群场（D-1 署名）
const J_TARGET = '5';      // 窗口外目标
const J_QUOTER = '240';    // 引用者（正文信封 + 结构化坐标）

function seedJump() {
  resetServer();
  const rows = [];
  for (let i = 1; i <= 240; i++) {
    rows.push({ id: i, senderId: i % 2 ? 'u-p' : 'me', kind: 'text', body: `历史消息 #${i} 正文`, createdAt: iso(EPOCH - (400 - i)) });
  }
  rows[4] = { ...rows[4], body: '被引的原始消息 #5（窗口外目标）' };
  rows[239] = {
    ...rows[239],
    body: '> [引用 #5] 分页君 · 2026-09-18 09:00 · 被引的原始消息 #5（窗口外目标）\n\n引用者正文',
    replyToMessageId: 5,
    replyToConversationId: JCONV,
  };
  SRV.msgs[JCONV] = rows;
  SRV.convs.push({
    conversationId: JCONV,
    friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(EPOCH - 86400) },
    lastMessage: rows[239], unreadCount: 0,
  });
  SRV.msgs[GCONV] = [{ id: 601, senderId: 'u-aa', kind: 'text', body: '群里的通知：周五团建', createdAt: iso(EPOCH - 300) }];
}

/** 群腿契约镜像（S5 专有；后注册 ⇒ 优先于 bootPage 的 catch-all）。 */
async function routeGroupLeg(page, onCall) {
  await page.route(/\/api\/groups(\?.*)?$/, async (route) => {
    if (route.request().method() !== 'GET') return route.continue();
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify([{ groupId: GCONV, title: '项目组', lastMessage: SRV.msgs[GCONV][0], unreadCount: 0, memberCount: 2, role: 'member', selfUserId: 'me-1' }]),
    });
  });
  await page.route(/\/api\/groups\/[^/]+\/members$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ selfUserId: 'me-1', members: [{ userId: 'u-aa', name: '甲先生', role: 'owner' }, { userId: 'me-1', name: '我', role: 'member' }] }),
  }));
  await page.route(/\/api\/groups\/[^/]+\/messages$/, async (route) => {
    let body = {}; try { body = JSON.parse(route.request().postData() || '{}'); } catch { /* ignore */ }
    onCall({ p: new URL(route.request().url()).pathname, method: 'POST', body });
    const row = { id: 9000 + (SRV.calls.length % 500), senderId: 'me-1', kind: 'text', body: body.body || '', createdAt: iso(EPOCH) };
    SRV.msgs[GCONV].push(row);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messageId: row.id, conversationId: GCONV, createdAt: row.createdAt, selfUserId: 'me-1' }) });
  });
}

const quotePayloads = () => callsOf(c => c.method === 'POST').map(c => ({ p: c.p, keys: Object.keys(c.body), refId: c.body.replyToMessageId, body: String(c.body.body || '') }));

async function scenarioJump(page, pageErrors, baseline = false) {
  console.log(`\n── S5 · 引用跳转 · MODE=${MODE}${baseline ? '（本批改前基线）' : ''}`);

  // ── A. 双写载荷（好友腿：结构化坐标 + 信封**同时**在场）─────────────────
  await openConv(page, 'cA');
  await rightClickBubble(page, 102);
  await clickMenuItem(page, '引用');
  await page.fill('.fm-input', '双写载荷读数');
  await page.click('.fm-send-btn');
  await sleep(800);
  const p1 = quotePayloads().pop();
  put('jump.wire.friend', p1);
  if (baseline) {
    ok('S5 改前基线：好友腿出站载荷**无**结构化坐标键（只有信封承载）',
      !!p1 && !p1.keys.includes('replyToMessageId') && /^> \[引用 #102\] /.test(p1.body),
      JSON.stringify(p1 && { keys: p1.keys, first: p1.body.split('\n')[0] }));
  } else {
    ok('S5 双写：好友腿请求体**同时**带结构化坐标 `replyToMessageId`（整数 102）与正文首行信封',
      !!p1 && p1.refId === 102 && typeof p1.refId === 'number' && /^> \[引用 #102\] /.test(p1.body),
      JSON.stringify(p1 && { keys: p1.keys, refId: p1.refId, refType: typeof p1.refId, first: p1.body.split('\n')[0] }));
  }
  // 加性对照：无引用的消息 ⇒ 零新键（旧形态逐字节同形）
  await page.fill('.fm-input', '不带引用的读数');
  await page.click('.fm-send-btn');
  await sleep(700);
  const p2 = quotePayloads().pop();
  put('jump.wire.plain', p2);
  ok('S5 加性判据：无引用消息的请求体**零新键**（`replyToMessageId` 不出现）',
    !!p2 && !p2.keys.includes('replyToMessageId') && !p2.body.startsWith('> [引用'), JSON.stringify(p2 && p2.keys));

  // ── B. 群腿：**禁塞**结构化键 + D-1 署名（引用态条 / 出站信封）──────────
  await page.click('#fm-chat-overlay .fm-modal-close');
  await sleep(350);
  await openConv(page, GCONV);
  await rightClickBubble(page, 601);
  await clickMenuItem(page, '引用');
  await sleep(350);
  const gstrip = await page.evaluate(() => {
    const s = document.querySelector('.fm-quote-strip');
    const chip = s ? s.querySelector('.att-ref') : null;
    return { hidden: s ? s.hidden : null, refId: chip ? chip.dataset.refId : null, text: chip ? chip.textContent : null };
  });
  put('jump.group.strip', gstrip);
  await page.fill('.fm-input', '群里回一句');
  await page.click('.fm-send-btn');
  await sleep(800);
  const pg = quotePayloads().pop();
  const gEnv = pg ? String(pg.body).split('\n')[0] : '';
  put('jump.group.wire', { keys: pg && pg.keys, envelope: gEnv });
  if (baseline) {
    ok('S5 改前基线（D-1）：群会话引用态摘要**缺发送者名**（只有日期 · 预览）',
      !!gstrip && gstrip.hidden === false && gstrip.refId === 'ref:fm:601' && !/甲先生/.test(String(gstrip.text)),
      JSON.stringify(gstrip && gstrip.text));
    ok('S5 改前基线（D-1）：群腿出站信封摘要同样缺发送者名',
      !/甲先生/.test(gEnv) && /^> \[引用 #601\] /.test(gEnv), JSON.stringify(gEnv));
    ok('S5 改前基线：引用块**无**跳转入口（无 role/tabindex、无 handler）',
      await page.evaluate(async () => {
        const q = document.querySelector('.fm-flow .fm-msg[data-message-id] .fm-quote-block');
        if (!q) return false;
        const r = q.getBoundingClientRect();
        const st = document.querySelector('.fm-flow').scrollTop;
        q.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: r.x + 2, clientY: r.y + 2 }));
        await new Promise(res => setTimeout(res, 150));
        return q.getAttribute('role') === null && q.getAttribute('tabindex') === null
          && getComputedStyle(q).cursor === 'auto' && !q.dataset.quoteJumpOutcome
          && document.querySelector('.fm-flow').scrollTop === st;
      }));
    return;
  }
  ok('S5 D-1（① 引用态条）：群会话引用摘要**含发送者名**（既有 `groupSenderNameOf` 名册解析）',
    !!gstrip && /甲先生/.test(String(gstrip.text)) && gstrip.refId === 'ref:fm:601', JSON.stringify(gstrip && gstrip.text));
  ok('S5 D-1（② 出站信封）：群腿信封摘要同样含发送者名',
    /^> \[引用 #601\] 甲先生/.test(gEnv), JSON.stringify(gEnv));
  ok('S5 🔴 群腿**禁塞**结构化引用键（出站键集逐字 = 既有两键 + 信封承载）',
    !!pg && !pg.keys.includes('replyToMessageId') && !pg.keys.includes('replyToConversationId')
    && pg.keys.includes('body') && pg.keys.includes('clientMsgId'), JSON.stringify(pg && pg.keys));
  // D-1（③ 对端气泡块）：从既有 onMessage 频道注入一条群消息，正文 = **本应用自己刚产出的
  // 信封**（`gEnv`，同一编码点）⇒ 该断言挂在**命名链**上（缺名即复红），不是挂在固定串上。
  const gPeer = `${gEnv}\n\n对端的群里回复`;
  if (WS) WS.send(JSON.stringify({ type: 'friend_event', event: 'message_new', conversationId: GCONV, messageId: 8601, senderId: 'u-aa', kind: 'text', body: gPeer, createdAt: new Date().toISOString() }));
  await sleep(700);
  const gBlock = await bubbleRead(page, 8601);
  put('jump.group.bubble', gBlock);
  ok('S5 D-1（③ 对端气泡块）：群消息引用块摘要含发送者名',
    !!gBlock && gBlock.hasQuote && /甲先生/.test(String(gBlock.quoteText)), JSON.stringify(gBlock && gBlock.quoteText));

  // ── C. 坐标双读（帧面结构化键在场 / 缺席两态）──────────────────────────
  await page.click('#fm-chat-overlay .fm-modal-close');
  await sleep(350);
  await openConv(page, 'cA');
  const sendFrameTo = (cid, payload) => {
    if (WS) WS.send(JSON.stringify({ type: 'friend_event', event: 'message_new', conversationId: cid, ...payload }));
  };
  sendFrameTo('cA', { messageId: 751, senderId: 'u-p', kind: 'text', body: '> [引用 #102] 分页君 · 2026-09-18 10:00 · 明天九点开会\n\n带坐标的对端腿', replyToMessageId: 102, replyToConversationId: 'cA' });
  sendFrameTo('cA', { messageId: 752, senderId: 'u-p', kind: 'text', body: '> [引用 #103] 分页君 · 2026-09-18 10:01 · 带上笔记本\n\n不带坐标的对端腿' });
  await sleep(800);
  const c1 = await bubbleRead(page, 751);
  const c2 = await bubbleRead(page, 752);
  put('jump.coord.wire', c1);
  put('jump.coord.fallback', c2);
  const coordRead = await page.evaluate(() => {
    const g = (id) => {
      const m = document.querySelector(`.fm-flow .fm-msg[data-message-id="${id}"]`);
      const q = m && m.querySelector('.fm-quote-block');
      return q ? { id: q.dataset.refMessageId, conv: q.dataset.refConversationId || null, src: q.dataset.quoteJumpSource || null, jump: q.dataset.quoteJump || null } : null;
    };
    return { wire: g(751), envelope: g(752) };
  });
  put('jump.coord', coordRead);
  ok('S5 坐标双读①：帧面带结构化键 ⇒ 坐标 = 结构化坐标（id + 会话）且可点',
    !!coordRead.wire && coordRead.wire.id === '102' && coordRead.wire.conv === 'cA' && coordRead.wire.src === 'wire' && coordRead.wire.jump === 'ready',
    JSON.stringify(coordRead.wire));
  ok('S5 坐标双读②：帧面**无**结构化键（旧服/群腿形态）⇒ 退化 = 信封 id + 本会话兜底（不猜第三方会话）',
    !!coordRead.envelope && coordRead.envelope.id === '103' && coordRead.envelope.conv === 'cA'
    && coordRead.envelope.src === 'envelope' && coordRead.envelope.jump === 'ready',
    JSON.stringify(coordRead.envelope));

  // ── D. 真跳路径（窗户内滚动 + 高亮 / 键盘 / 回退拉取 / 降级 / 跨会话）──
  await page.click('#fm-chat-overlay .fm-modal-close');
  await sleep(350);
  await openConv(page, JCONV);
  await sleep(900);
  const beforeJump = await page.evaluate(() => {
    const flow = document.querySelector('.fm-flow');
    const m = flow.querySelector('.fm-msg[data-message-id="240"]');
    const q = m.querySelector('.fm-quote-block');
    return {
      flowScrollable: flow.scrollHeight > flow.clientHeight + 2,
      scrollTop: flow.scrollTop, scrollHeight: flow.scrollHeight, clientHeight: flow.clientHeight,
      jump: q.dataset.quoteJump, conv: q.dataset.refConversationId, targetLoaded: !!flow.querySelector('.fm-msg[data-message-id="5"]'),
      cursor: getComputedStyle(q).cursor,
    };
  });
  put('jump.outOfWindow.before', beforeJump);
  ok('S5 场建：目标 #5 在**已载窗口之外**（首窗只覆盖最近 200 条）+ 坐标来自结构化键',
    beforeJump.targetLoaded === false && beforeJump.conv === JCONV && beforeJump.jump === 'ready',
    JSON.stringify(beforeJump));
  // 键盘可达（Tab 顺序 + Enter 触发）
  const kbd = await page.evaluate(async () => {
    const box = document.querySelector('.fm-flow .fm-msg[data-message-id="240"] .fm-quote-block');
    box.focus();
    const focused = document.activeElement === box;
    const ring = getComputedStyle(box).outlineStyle !== 'none' || getComputedStyle(box).outlineWidth !== '0px';
    const before = document.querySelector('.fm-flow').scrollTop;
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await new Promise(res => setTimeout(res, 900));
    const tgt = document.querySelector('.fm-flow .fm-msg[data-message-id="5"]');
    const fr = document.querySelector('.fm-flow').getBoundingClientRect();
    const tr = tgt ? tgt.getBoundingClientRect() : null;
    return {
      focused, ring, role: box.getAttribute('role'), tabindex: box.getAttribute('tabindex'),
      outcome: box.dataset.quoteJumpOutcome || null,
      scrolled: document.querySelector('.fm-flow').scrollTop !== before,
      targetLoaded: !!tgt, flash: !!(tgt && tgt.classList.contains('fm-quote-target')),
      inViewport: !!tr && tr.top >= fr.top - 1 && tr.bottom <= fr.bottom + 1,
    };
  });
  put('jump.kbd', kbd);
  await shot(page, 'quote-jump-target');
  ok('S5 键盘可达：块可聚焦（role=button + tabindex=0 + 焦点环）+ Enter 触发**真跳**',
    kbd.focused === true && kbd.role === 'button' && kbd.tabindex === '0' && kbd.ring === true && kbd.outcome === 'hit',
    JSON.stringify(kbd));
  ok('S5 🔴 窗口外目标 ⇒ **按需回退拉取**后真跳（目标入场 + 位移 + 高亮 + 落在视口内）',
    kbd.targetLoaded === true && kbd.scrolled === true && kbd.flash === true && kbd.inViewport === true,
    JSON.stringify(kbd));
  const backfillReqs = callsOf(c => c.method === 'GET' && c.p.includes(`/conversations/${JCONV}/messages`)).map(c => c.query);
  put('jump.backfill.requests', backfillReqs);
  ok('S5 回退拉取读数为**定向 keyset**（有界：不含无界扫描；命中即停 ≤20 步）',
    backfillReqs.length > 0 && backfillReqs.length <= 21, JSON.stringify(backfillReqs));

  // 不可达 ⇒ 诚实降级（禁「点了没反应」）
  sendFrameTo(JCONV, { messageId: 753, senderId: 'u-p', kind: 'text', body: '> [引用 #999999] 分页君 · 2026-09-18 10:02 · 早已不存在\n\n降级腿', replyToMessageId: 999999, replyToConversationId: JCONV });
  await sleep(700);
  const degrade = await page.evaluate(async () => {
    const m = document.querySelector('.fm-flow .fm-msg[data-message-id="753"]');
    const q = m.querySelector('.fm-quote-block');
    const before = document.querySelector('.fm-flow').scrollTop;
    const r = q.getBoundingClientRect();
    q.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: r.x + 2, clientY: r.y + 2 }));
    await new Promise(res => setTimeout(res, 900));
    const toast = document.querySelector('.fm-modal-toast');
    return {
      outcome: q.dataset.quoteJumpOutcome || null, jump: q.dataset.quoteJump || null,
      role: q.getAttribute('role'), tabindex: q.getAttribute('tabindex'), cursor: getComputedStyle(q).cursor,
      note: (m.querySelector('.fm-quote-note') || {}).textContent, noteHidden: (m.querySelector('.fm-quote-note') || {}).hidden,
      toastVisible: !!(toast && !toast.hidden && toast.textContent), toastText: toast ? toast.textContent : null,
      state: q.dataset.quoteState,
      scrolled: document.querySelector('.fm-flow').scrollTop !== before,
    };
  });
  put('jump.degrade', degrade);
  ok('S5 🔴 不可达 ⇒ 诚实降级：块失去可点语义（role/tabindex 撤除 + 指针复位）+ 「原消息不可用」+ **可见**反馈（禁静默）',
    degrade.outcome === 'unavailable' && degrade.jump === 'none' && degrade.role === null && degrade.tabindex === null
    && degrade.cursor === 'auto' && degrade.noteHidden === false && /原消息不可用/.test(String(degrade.note))
    && degrade.toastVisible === true, JSON.stringify(degrade));
  const secondClick = await page.evaluate(async () => {
    const q = document.querySelector('.fm-flow .fm-msg[data-message-id="753"] .fm-quote-block');
    const before = document.querySelector('.fm-flow').scrollTop;
    q.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise(res => setTimeout(res, 300));
    return document.querySelector('.fm-flow').scrollTop === before;
  });
  ok('S5 降级后**零假跳**：再点该块无任何位移（入口已撤除）', secondClick === true);

  // 跨会话坐标 ⇒ 切到目标会话再定位
  await page.click('#fm-chat-overlay .fm-modal-close');
  await sleep(350);
  await openConv(page, 'cC');
  sendFrameTo('cC', { messageId: 754, senderId: 'u-q', kind: 'text', body: '> [引用 #102] 分页君 · 2026-09-18 10:03 · 明天九点开会\n\n跨会话引用腿', replyToMessageId: 102, replyToConversationId: 'cA' });
  await sleep(800);
  const crossBefore = await page.evaluate(() => {
    const q = document.querySelector('.fm-flow .fm-msg[data-message-id="754"] .fm-quote-block');
    return { conv: q.dataset.refConversationId, jump: q.dataset.quoteJump, open: document.querySelector('.fm-conv-row.active') ? document.querySelector('.fm-conv-row.active').dataset.conversationId : null };
  });
  const cross = await page.evaluate(async () => {
    const q = document.querySelector('.fm-flow .fm-msg[data-message-id="754"] .fm-quote-block');
    const r = q.getBoundingClientRect();
    q.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: r.x + 2, clientY: r.y + 2 }));
    await new Promise(res => setTimeout(res, 1400));
    const tgt = document.querySelector('.fm-flow .fm-msg[data-message-id="102"]');
    return {
      switched: !!document.querySelector('.fm-flow .fm-msg[data-message-id="102"]'),
      title: (document.querySelector('.fm-modal-title') || {}).textContent || null,
      targetLoaded: !!tgt, flash: !!(tgt && tgt.classList.contains('fm-quote-target')),
      footerRow: (document.querySelector('.fm-conv-row.active') || {}).dataset ? document.querySelector('.fm-conv-row.active').dataset.conversationId : null,
    };
  });
  put('jump.cross', { before: crossBefore, after: cross });
  ok('S5 🔴 跨会话坐标 ⇒ **先切到目标会话再定位**（目标会话被打开 + 目标节点在场 + 高亮）',
    crossBefore.conv === 'cA' && crossBefore.jump === 'ready' && cross.targetLoaded === true && cross.flash === true,
    JSON.stringify({ before: crossBefore, after: cross }));
  put('pageErrors.s5', pageErrors);
  ok('S5 跳转腿零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
}


// ══════════════════════════════════════════════════════════════════════
// 真渲染截图 + 收尾
// ══════════════════════════════════════════════════════════════════════
try {
  console.log(`msgmenu spec · MODE=${MODE} · WEB=${WEB}`);
  if (QJBEFORE) {
    // 本批（quotejump）改前基线：一期**已落地**的 main 树 ⇒ 只跑跳转/D-1 的红线面
    //（msgmenu 一期的改前基线面在 `MM_MODE=before`，两者基线不同、禁混用）。
    resetServer();
    seedJump();
    const { ctx, page, pageErrors } = await bootPage();
    try {
      await routeGroupLeg(page, (c) => SRV.calls.push(c));
      await openPanel(page);
      await scenarioJump(page, pageErrors, true);
    } finally {
      await ctx.close();
    }
  } else {
  {
    resetServer();
    const { ctx, page, pageErrors } = await bootPage();
    try {
      await openPanel(page);
      await openConv(page, 'cA');
      await scenarioMenu(page);
      await scenarioQuote(page, pageErrors);
      put('pageErrors', pageErrors);
      ok('零 pageerror（全场景）', pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }
  // S3 单独一轮（干净的失败态起点：首投失败 ⇒ 气泡左侧红圈）
  {
    resetServer();
    const { ctx, page, pageErrors } = await bootPage();
    try {
      await openPanel(page);
      await openConv(page, 'cA');
      if (AFTER) {
        // 造一个失败气泡（首投失败注入：500）——「多选期间不误触重发键」的场主
        await page.route('**/api/friends/*/messages', async (route) => {
          await sleep(80);
          return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: 'server_error' }) });
        });
        await page.fill('.fm-input', '会失败的这条');
        await page.click('.fm-send-btn');
        await page.waitForSelector('.fm-flow .fm-msg.fm-failed .fm-retry', { timeout: 10000 });
        await page.unroute('**/api/friends/*/messages');
        const retrySlot = await page.evaluate(() => {
          const b = document.querySelector('.fm-flow .fm-msg.fm-failed .fm-retry');
          const r = b.getBoundingClientRect();
          return { x: r.x + r.width / 2, y: r.y + r.height / 2, display: getComputedStyle(b).display, w: +r.width.toFixed(1) };
        });
        put('select.retrySlotBefore', retrySlot);
        await scenarioSelect(page, pageErrors, retrySlot);
      } else {
        await scenarioSelect(page, pageErrors);
      }
      put('pageErrors.s3', pageErrors);
      ok('零 pageerror（多选场景）', pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }
  // S4 单独一轮（D7 转发窗口：干净选择态起点；本批新增面，只在实施面跑）
  if (AFTER && !QJBEFORE) {
    resetServer();
    const { ctx, page, pageErrors } = await bootPage();
    try {
      await openPanel(page);
      await openConv(page, 'cA');
      await scenarioForwardWindow(page, pageErrors);
      put('pageErrors.s4', pageErrors);
      ok('零 pageerror（转发窗口场景）', pageErrors.length === 0, JSON.stringify(pageErrors));
    } finally {
      await ctx.close();
    }
  }
  // S6 单独一轮（修正②/④：主窗口面 = 麦克风形态/反馈 + 附件 ❌ 居中）
  if (AFTER && !QJBEFORE) {
    resetServer();
    const { ctx, page, pageErrors } = await bootPage({ voice: true });
    try {
      await scenarioUiFaces(page, pageErrors);
    } finally {
      await ctx.close();
    }
  }
  // S6b 单独一轮（voicefix 腿 B 新终态：好友/群窗麦克风在位 + 真接线 —— 旧规格零覆盖）
  //   群场种子复用 S5 既有夹具（seedJump/routeGroupLeg，零新造）；会话窗路由须在
  //   `openPanel` 之前注册（面板开窗即拉 /api/groups）。
  if (AFTER && !QJBEFORE) {
    seedJump();
    const { ctx, page, pageErrors } = await bootPage({ voice: true });
    try {
      await routeGroupLeg(page, (c) => SRV.calls.push(c));
      await openPanel(page);
      await scenarioFmMicLeg(page, pageErrors);
    } finally {
      await ctx.close();
    }
  }
  // S5 单独一轮（quotejump 批：跳转场 + 群场，自有种子 ⇒ 不动 S1-S3 的判据面）
  // 🔴 只在实施面跑（本批改前基线由 `MM_MODE=qjbefore` 的专用轮承担；`MM_MODE=before`
  //    是 msgmenu 一期的基线，其树**没有**引用功能 ⇒ 该轮不适用）。
  if (AFTER) {
    resetServer();
    seedJump();
    const { ctx, page, pageErrors } = await bootPage();
    try {
      await routeGroupLeg(page, (c) => SRV.calls.push(c));
      await openPanel(page);
      await scenarioJump(page, pageErrors, false);
    } finally {
      await ctx.close();
    }
  }
  }
} catch (e) {
  failures++;
  console.error('HARNESS ERROR:', (e && e.stack) || e);
} finally {
  await browser.close();     // 进程清理纪律：异常路径也必须关
  await new Promise(r => server.close(r));
}

console.log(`\n=== msgmenu ${MODE}: failures=${failures} ===`);
if (MM_OUT) {
  try { writeFileSync(MM_OUT, JSON.stringify({ mode: MODE, web: WEB, failures, ...R }, null, 2)); } catch { /* ignore */ }
}
process.exit(failures === 0 ? 0 : 1);
