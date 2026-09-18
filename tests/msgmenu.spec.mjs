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

async function bootPage() {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
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
  const cnt = document.querySelector('.fm-select-count');
  const fw = document.querySelector('.fm-select-forward');
  const checks = [...document.querySelectorAll('.fm-flow .fm-msg-check')];
  const wraps = [...document.querySelectorAll('.fm-flow .fm-msg')];
  return {
    barPresent: !!bar,
    barHidden: bar ? bar.hidden : null,
    barDisplay: bar ? getComputedStyle(bar).display : null,
    countText: cnt ? cnt.textContent : null,
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
    return {
      hidden: s.hidden,
      display: getComputedStyle(s).display,
      chipCls: chip ? chip.className : null,
      chipRefType: chip ? chip.dataset.refType : null,
      chipRefId: chip ? chip.dataset.refId : null,
      chipText: chip ? chip.textContent : null,
      hasRemove: !!rm,
      removeLabel: rm ? rm.getAttribute('aria-label') : null,
      stripY: +s.getBoundingClientRect().y.toFixed(1),
      inputY: +document.querySelector('.fm-input-bar').getBoundingClientRect().y.toFixed(1),
      chipVisible: chip ? chip.getBoundingClientRect().height > 0 : false,
    };
  });
  put('quote.strip', strip);
  await shot(page, 'quote-state');
  ok('S2 引用态：输入框面显示被引消息摘要（既有引用块组件 + 被引消息 id 钉在 refId）',
    !!strip && strip.hidden === false && strip.chipRefType === 'friend-message' && strip.chipRefId === 'ref:fm:102'
    && /分页君/.test(String(strip.chipText)) && new RegExp(QUOTE_SRC).test(String(strip.chipText)) && strip.chipVisible,
    JSON.stringify(strip));
  ok('S2 引用态：可取消（既有 × 移除键在册）', !!strip && strip.hasRemove === true, JSON.stringify(strip && strip.removeLabel));
  ok('S2 引用态条落在消息流与输入条之间（真几何）', !!strip && strip.stripY < strip.inputY, JSON.stringify(strip && { stripY: strip.stripY, inputY: strip.inputY }));
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
  const sStyle = await page.evaluate(() => {
    const sel = document.querySelector('.fm-flow .fm-msg.fm-selected .fm-msg-check');
    const un = document.querySelector('.fm-flow .fm-msg:not(.fm-selected) .fm-msg-check');
    const bar = document.querySelector('.fm-select-bar');
    const cs = e => { const c = getComputedStyle(e); return { bg: c.backgroundColor, borderColor: c.borderTopColor, color: c.color, w: c.width, h: c.height, left: c.left, top: c.top, display: c.display }; };
    return { selected: sel ? cs(sel) : null, unselected: un ? cs(un) : null, bar: bar ? { display: getComputedStyle(bar).display, paddingLeft: getComputedStyle(bar).paddingLeft } : null };
  });
  put('select.style', sStyle);
  ok('S3 勾选面视觉面（数值）：未选中「无勾」/ 选中取既有 sapphire 15% 底 + sapphire 勾色（零新色值）',
    !!sStyle && sStyle.selected.color !== sStyle.unselected.color
    && sStyle.unselected.color === 'rgba(0, 0, 0, 0)' && /91, 127, 191/.test(sStyle.selected.color)
    && /91, 127, 191/.test(sStyle.selected.bg), JSON.stringify(sStyle));
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
  await page.click('.fm-select-forward');
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
