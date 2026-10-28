#!/usr/bin/env node
// canvas-ask-dock.spec.mjs — Canvas 文件 ask mode（2026-10-28 实施批；同日 22:42
// v2.1 简化修订）实施验收。
//
// 机制稿 §8 验收条目 → 本 spec 断言映射（前端组件行为面；后端路由/绑定/流式
// 的协议面用 routeWebSocket mock 驱动，形态 = canvas-stack.spec.mjs 孪生）：
//   T0  页面零 pageerror（载入健康面）
//   T1  入口（§8-1/§8-2/§8-3）：.ask-fab 44×44 右下角最靠右、z 序 25 = ask-dock
//       24+1 < 顶行胶囊 30（排他 z 序）、dock 宽 372（E-2 桌面档）
//   T2  开合三通路（§8-1）：入口钮再点 / Esc / 画布空白点击；开 = .closed 摘除
//       + fab.open + 输入聚焦；输入条在按钮上方（transform-origin 100% 100% 锚定）
//   T3  提问 → 气泡流式回答（§8-1）：askOpen 帧先于 askQuestion 帧、sessionId =
//       ask-<8hex>（每文件绑定）；user 气泡即时、typing 指示在场、textDelta
//       增量回填同一 ai 气泡、done 定稿；fab has-sess 活性紫点点亮
//   T4  每文件独立 session（§8-1 作者令⑦）：切卡即切——B 卡 askState 回放 B 的
//       转录；切回 A 复现 A 转录（收起保留，§2/§4）；v2.1 徽标退场后以转录呈现
//   T5  收起保留 + pulse（§8-1）：有消息收起 → .pulse + .has-sess；再开转录在
//   T6  v2.1 减法·转交/回问（§7）：转交钮自 UI 撤除（两箭头减为一枚右侧发送钮）；
//       askHandoffAck 回执收进不可见处（零 sys 气泡）；注入 user 帧（主会话回问）
//       → user 族气泡带「主会话回问：」前缀（问答面两族制）；error 落助手气泡槽
//   T7  主会话零交集（§8-2 视图排他）：ask 流式全程 #chat .bubble 计数不变
//   T8  UI 纪律（§8-2）：dock max-h 316（E-2）、零遮罩（新增阻塞层计数零）、
//       气泡列 touch-action pan-y（#988 列内纵滚原生）
//   T9  移动档（§8-3）：44×44 命中盒、座位在切换条上方、列 max-h 250、
//       气泡列表面横滑零切卡（.mobile-card-peek 不亮起，surface 归属制）
//   T10 v2.1 减法纪律（作者令 2026-10-28 22:42「简约一点…」）：ghost 提示行/
//       session 徽标/转交钮零残留；服务端转录中的 sys 帧渲染面永久过滤
//       （收进不可见处——转录数据仍存、气泡面只出 user/ai 两族）
//
// 运行：node tests/canvas-ask-dock.spec.mjs
// 可选 env：PORT（默认 8216，避开在跑 spec 的固定端口）、ASK_SHOTS_DIR（截图目录）。

import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

/** 客户端/服务端同式的 ask 会话 id 派生（sha256(absPath) 前 8 hex，前缀 ask-）。 */
const sidOf = (absPath) => 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8216);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.ASK_SHOTS_DIR || resolve(HERE, `../evidence/canvas-ask-dock/shots`);
mkdirSync(SHOTS_DIR, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务（serve 真实 web/ 前端源码树，canvas-stack 同款）──
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// ── 断言器 ──
let pass = 0, fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  results.push(line);
  console.log(line);
  if (cond) pass++; else fail++;
}

// ── ask 会话 mock 面（routeWebSocket 的 server 侧）──
/** @type {any} */ let askWs = null;               // 活跃 page 的 ws 句柄（单页 spec）
const inbound = [];                                // page → server 帧留档（协议断言面）
function wsSend(obj) { if (askWs) askWs.send(JSON.stringify(obj)); }
function askStateReply(m, fileName, messages) {
  // askOpen 帧不携 sessionId（服务端按文件派生）——回放帧带同式派生 sid。
  const sid = m.sessionId || sidOf((m.file && m.file.absPath) || '');
  const key = 'ask:' + sid.slice(4, 8);
  wsSend({
    type: 'askState',
    sessionId: sid,
    sessionKey: key,
    file: (m.file && m.file.absPath) || '',
    fileName: fileName,
    messages: messages || [],
  });
}

function nfFileRoute() {
  return async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
  };
}

async function bootPage(browser, { viewport = { width: 1600, height: 1000 }, colorScheme = 'dark' } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRoute());
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    askWs = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      inbound.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'askOpen') {
        // askOpen 帧不携 sessionId（服务端按 file.absPath 派生）——mock 同式推导。
        const name = (m.file && m.file.name) || 'file';
        const sid = m.sessionId || sidOf((m.file && m.file.absPath) || '');
        askStateReply(m, name, sessions.msgs[sid] || []);
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(600);
  return { ctx, page, errors };
}

/** 移动档 boot（body.mobile-layer 自举，#activity-bar 手机层隐藏不等待）。 */
async function bootMobilePage(browser) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: 'dark', hasTouch: true });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRoute());
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    askWs = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      inbound.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'askOpen') {
        // askOpen 帧不携 sessionId（服务端按 file.absPath 派生）——mock 同式推导。
        const name = (m.file && m.file.name) || 'file';
        const sid = m.sessionId || sidOf((m.file && m.file.absPath) || '');
        askStateReply(m, name, sessions.msgs[sid] || []);
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), { timeout: 20000 });
  await sleep(800);
  return { ctx, page, errors };
}

/** 每文件 ask 转录的 mock 存取（T4 独立 session 用：askState 回放各自转录）。 */
const sessions = { msgs: {} };
function expectTranscript(sid, msgs) { sessions.msgs[sid] = msgs; }

async function openFile(page, name, { state = 'visible' } = {}) {
  const absPath = `/tmp/canvas-ask-fixture/${name}`;
  await page.evaluate(({ name, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:' + absPath, itemType: 'markdown', title: name, content: `# ${name}\n\nmock body\n`,
        absPath, pinned: true,
      },
    }));
  }, { name, absPath });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state, timeout: 10000 });
  await sleep(300);
  return absPath;
}

const geo = (page) => page.evaluate(() => {
  const panel = document.getElementById('canvas-panel');
  const fab = document.querySelector('.ask-fab');
  const dock = document.querySelector('.ask-dock');
  const head = document.querySelector('#canvas-slide .canvas-header');
  const pr = panel.getBoundingClientRect();
  const fr = fab ? fab.getBoundingClientRect() : null;
  const cs = fab ? getComputedStyle(fab) : null;
  const dcs = dock ? getComputedStyle(dock) : null;
  const hcs = head ? getComputedStyle(head) : null;
  return {
    fab: fr ? { x: fr.x, y: fr.y, w: fr.width, h: fr.height, right: fr.right, bottom: fr.bottom } : null,
    panel: { right: pr.right, bottom: pr.bottom },
    fabZ: cs ? cs.zIndex : null,
    dockZ: dcs ? dcs.zIndex : null,
    headZ: hcs ? hcs.zIndex : null,
    dockW: dcs ? dcs.width : null,
    dockClosed: dock ? dock.classList.contains('closed') : null,
    dockMaxH: dcs ? dcs.maxHeight : null,
  };
});

async function shot(page, name) {
  try { await page.screenshot({ path: join(SHOTS_DIR, name + '.png'), fullPage: false }); } catch { /* headless 沙箱截不到就算了 */ }
}

// ═══════════════════════════════════════════════════════════════════════════
const browser = await chromium.launch();
try {
  // ── 桌面（暗档基准）──
  const { page, errors } = await bootPage(browser);
  const absA = await openFile(page, '记忆检索调优笔记.md');
  const absB = await openFile(page, 'wiki-graph-redesign-design.md');
  await openFile(page, '记忆检索调优笔记.md'); // 收敛 active 回 A
  await sleep(400);

  // T0 载入健康
  ok('T0 页面零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));

  // T1 入口几何 + z 序
  const g1 = await geo(page);
  ok('T1a 入口钮 44×44（命中盒=视觉径）', !!g1.fab && Math.abs(g1.fab.w - 44) < 1.5 && Math.abs(g1.fab.h - 44) < 1.5, JSON.stringify(g1.fab));
  ok('T1b 右下角最靠右（右缘距面板右缘 ~14px）', !!g1.fab && Math.abs(g1.panel.right - g1.fab.right - 14) < 3, `gap=${g1.panel.right - (g1.fab ? g1.fab.right : 0)}`);
  // T1c 座位契约：基准 14px；角落圆钮簇（缩放条等）可见时实测让位（浮件上沿+12，
  // seatRefreshButton 同思路）。本环境 zoom-bar 在场 → 期望值按同式实测推导。
  const seatExpect = await page.evaluate(() => {
    const panel = document.getElementById('canvas-panel');
    if (!panel) return 14;
    const pr = panel.getBoundingClientRect();
    let occupied = 0;
    panel.querySelectorAll('.canvas-zoom-bar, .canvas-refresh-toggle, .canvas-source-toggle, .canvas-ref-toggle').forEach((f) => {
      const h = /** @type {HTMLElement} */ (f);
      if (h.hidden || h.offsetParent === null) return;
      const r = h.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) return;
      occupied = Math.max(occupied, pr.bottom - r.top);
    });
    return occupied > 0 ? occupied + 12 : 14;
  });
  ok('T1c 座位：基准 14px，角落浮件在场时实测让位（上沿+12）', !!g1.fab && Math.abs(g1.panel.bottom - g1.fab.bottom - seatExpect) < 3, `gap=${g1.panel.bottom - (g1.fab ? g1.fab.bottom : 0)} expected=${seatExpect}`);
  ok('T1d z 序排他：fab 25 / dock 24 < 顶行胶囊 30', g1.fabZ === '25' && g1.dockZ === '24' && Number(g1.headZ) >= 30, `fab=${g1.fabZ} dock=${g1.dockZ} head=${g1.headZ}`);
  ok('T1e dock 桌面档宽 372px', g1.dockW === '372px', g1.dockW);

  // T2 开合三通路
  await page.click('.ask-fab');
  await sleep(400);
  let g2 = await geo(page);
  const fabOpen = await page.evaluate(() => document.querySelector('.ask-fab').classList.contains('open'));
  const inputFocused = await page.evaluate(() => document.activeElement === document.querySelector('.ask-dock input'));
  ok('T2a 点击入口开：dock 摘 .closed + fab.open + 输入聚焦', !g2.dockClosed && fabOpen && inputFocused, `closed=${g2.dockClosed} open=${fabOpen} focus=${inputFocused}`);
  const anchored = await page.evaluate(() => {
    const d = document.querySelector('.ask-dock');
    const [ox, oy] = getComputedStyle(d).transformOrigin.split(' ').map(parseFloat);
    return { ox, oy, w: d.offsetWidth, h: d.offsetHeight };
  });
  ok('T2b transform-origin 锚定按钮圆心（100% 100% ⇒ 锚在 dock 右下角）',
    Math.abs(anchored.ox - anchored.w) < 2 && Math.abs(anchored.oy - anchored.h) < 2, JSON.stringify(anchored));
  await page.keyboard.press('Escape');
  await sleep(400);
  g2 = await geo(page);
  ok('T2c 通路② Esc 收起', g2.dockClosed === true);
  await page.click('.ask-fab');
  await sleep(350);
  await page.evaluate(() => document.getElementById('canvas-content').dispatchEvent(new MouseEvent('click', { bubbles: true })));
  await sleep(350);
  g2 = await geo(page);
  ok('T2d 通路③ 画布空白点击收起', g2.dockClosed === true);

  // T3 提问 → 流式回填（mock 协议面）
  inbound.length = 0;
  await page.click('.ask-fab');
  await sleep(350);
  await page.fill('.ask-dock input', 'τ 为什么取 30 天？');
  await page.press('.ask-dock input', 'Enter');
  await sleep(200);
  const askOpenIdx = inbound.findIndex((m) => m.type === 'askOpen');
  const askQ = inbound.find((m) => m.type === 'askQuestion');
  const sidRe = /^ask-[0-9a-f]{8}$/;
  ok('T3a askOpen 先行且 sessionId=ask-<8hex>（每文件绑定）', askOpenIdx >= 0 && !!askQ && askOpenIdx < inbound.indexOf(askQ) && sidRe.test(askQ.sessionId), JSON.stringify({ openIdx: askOpenIdx, sid: askQ && askQ.sessionId }));
  ok('T3b askQuestion 携带提问原文', !!askQ && askQ.content === 'τ 为什么取 30 天？');
  const askedSid = askQ ? askQ.sessionId : '';
  // 主会话 id 随帧上报（转交目标刷新面）
  ok('T3c askQuestion 携带 mainSessionId（§7 转交目标）', !!askQ && typeof askQ.mainSessionId === 'string' && askQ.mainSessionId.length > 0, String(askQ && askQ.mainSessionId));
  // mock 服务端流式应答
  const userBub = await page.waitForSelector('.ask-dock .ask-bub.user', { timeout: 3000 });
  const userText = await userBub.textContent();
  ok('T3d user 气泡即时呈现', userText.includes('τ 为什么取 30 天？'), userText.slice(0, 40));
  await page.waitForSelector('.ask-dock .ask-bub.typing', { timeout: 2000 }).then(() => ok('T3e typing 指示在场', true)).catch(() => ok('T3e typing 指示在场', false));
  for (const d of ['笔记「近因加权」节：', 'τ=30 半衰期 ≈21 天。', '<cite>§近因加权</cite>']) {
    wsSend({ type: 'textDelta', sessionId: askedSid, delta: d });
    await sleep(60);
  }
  const aiMid = await page.evaluate(() => {
    const nodes = [...document.querySelectorAll('.ask-dock .ask-bub.ai')];
    return nodes.length ? nodes[nodes.length - 1].textContent : '';
  });
  ok('T3f textDelta 增量回填同一 ai 气泡', aiMid.includes('τ=30 半衰期 ≈21 天。'), aiMid.slice(0, 50));
  wsSend({ type: 'done', sessionId: askedSid });
  await sleep(250);
  const aiFinal = await page.evaluate(() => {
    const nodes = [...document.querySelectorAll('.ask-dock .ask-bub.ai')];
    return nodes.length ? nodes[nodes.length - 1].textContent : '';
  });
  ok('T3g done 定稿（文本保持 + typing 撤除）', aiFinal.includes('τ=30 半衰期 ≈21 天') && !(await page.$('.ask-dock .ask-bub.typing')), aiFinal.slice(-30));
  const fabState = await page.evaluate(() => {
    const f = document.querySelector('.ask-fab');
    return { hasSess: f.classList.contains('has-sess'), dot: getComputedStyle(f.querySelector('.ask-sess-dot')).opacity };
  });
  ok('T3h fab 活性紫点点亮（has-sess）', fabState.hasSess && parseFloat(fabState.dot) > 0.9, JSON.stringify(fabState));
  await shot(page, 't3-conversation');
  // mock 服务端持久化面：A 的转录入库（T5 再开 askOpen 重放需复现——服务端为权威）。
  expectTranscript(askedSid, [
    { role: 'user', text: 'τ 为什么取 30 天？' },
    { role: 'ai', text: '笔记「近因加权」节：τ=30 半衰期 ≈21 天。<cite>§近因加权</cite>' },
  ]);

  // T4 每文件独立 session：切卡即切（B 转录先登记——askOpen(B) 自动回放）
  expectTranscript(sidOf(absB), [
    { role: 'user', text: '引用边是从哪来的？' },
    { role: 'ai', text: 'WikiIndex 抽取文档内链接与 ^锚 为引用边。' },
  ]);
  inbound.length = 0; // 切卡协议面从零起量（A 的 askOpen 已在 T3 留档）
  await openFile(page, 'wiki-graph-redesign-design.md'); // 切到 B（dock 开着 → 即刻切 session）
  await sleep(500);
  const askOpenB = inbound.find((m) => m.type === 'askOpen');
  ok('T4a 切卡后 askOpen 指向 B 文件（sid 随文件派生）',
    !!askOpenB && !!askOpenB.file && sidOf(askOpenB.file.absPath) === sidOf(absB) && sidOf(absB) !== askedSid,
    `${askedSid} → ${askOpenB && askOpenB.file ? askOpenB.file.absPath : '(no askOpen)'}`);
  const badgeB = await page.evaluate(() => ({
    retired: document.querySelectorAll('.ask-dock .ask-sess, .ask-ghost, .ask-handoff').length,
    firstUser: (document.querySelector('.ask-dock .ask-bub.user') || {}).textContent || '',
  }));
  ok('T4b v2.1 减法：ghost/徽标/转交钮零残留，切卡即切 B 转录',
    badgeB.retired === 0 && badgeB.firstUser.includes('引用边是从哪来的'), JSON.stringify(badgeB));
  ok('T4c 气泡列切 B 转录', badgeB.firstUser.includes('引用边是从哪来的'), badgeB.firstUser.slice(0, 30));
  // 切回 A → A 转录复现（收起保留口径）
  await openFile(page, '记忆检索调优笔记.md');
  await sleep(400);
  const backA = await page.evaluate(() => ({
    user: (document.querySelector('.ask-dock .ask-bub.user') || {}).textContent || '',
    ai: (document.querySelector('.ask-dock .ask-bub.ai') || {}).textContent || '',
  }));
  ok('T4d 切回 A：会话/转录复现（收起保留口径；v2.1 起仅以气泡面呈现）',
    backA.user.includes('τ 为什么取 30 天') && backA.ai.includes('近因加权'),
    JSON.stringify(backA).slice(0, 120));
  await shot(page, 't4-session-switch');

  // T5 收起保留 + pulse
  await page.click('.ask-fab'); // 收起（有消息）
  await sleep(400);
  const t5 = await page.evaluate(() => {
    const f = document.querySelector('.ask-fab');
    return { closed: document.querySelector('.ask-dock').classList.contains('closed'), pulse: f.classList.contains('pulse'), hasSess: f.classList.contains('has-sess') };
  });
  ok('T5 收起后 pulse 提示可唤回 + 会话保留', t5.closed && t5.pulse && t5.hasSess, JSON.stringify(t5));
  await page.click('.ask-fab');
  await sleep(350);
  const t5b = await page.evaluate(() => ({
    open: !document.querySelector('.ask-dock').classList.contains('closed'),
    user: (document.querySelector('.ask-dock .ask-bub.user') || {}).textContent || '',
  }));
  ok('T5b 再开复现', t5b.open && t5b.user.includes('τ 为什么取 30 天'), JSON.stringify(t5b).slice(0, 80));

  // T6 v2.1 减法·转交/回问（§7 机制不变，呈现面减法）
  inbound.length = 0;
  const t6a = await page.evaluate(() => {
    const row = document.querySelector('.ask-dock .ask-input');
    const btns = row ? [...row.querySelectorAll('button')] : [];
    return { n: btns.length, cls: btns.map((b) => b.className), hasHandoff: !!document.querySelector('.ask-dock .ask-handoff') };
  });
  ok('T6a v2.1 单箭头：转交钮退场，输入条右侧唯一按钮 = .ask-send',
    !t6a.hasHandoff && t6a.n === 1 && t6a.cls[0] === 'ask-send', JSON.stringify(t6a));
  // 转交回执（服务端 ack 帧）收进不可见处：零 sys 气泡、气泡总数不变
  const before6b = await page.evaluate(() => document.querySelectorAll('.ask-dock .ask-bub').length);
  wsSend({ type: 'askHandoffAck', sessionId: askedSid, ok: true, question: 'τ 为什么取 30 天？' });
  await sleep(300);
  const t6b = await page.evaluate(() => ({
    n: document.querySelectorAll('.ask-dock .ask-bub').length,
    sys: document.querySelectorAll('.ask-dock .ask-bub.sys').length,
  }));
  ok('T6b v2.1 回执收进不可见处：ack 帧后零 sys 气泡、气泡面不生长',
    t6b.sys === 0 && t6b.n === before6b, `bubbles=${before6b}→${t6b.n} sys=${t6b.sys}`);
  // 主会话回问（注入 user 帧进 ask 会话）→ §7 契约：以用户消息形态呈现
  wsSend({ type: 'user', sessionId: askedSid, injected: true, text: '主会话回问：需要我帮你做什么？' });
  await sleep(300);
  const t6c = await page.evaluate(() => {
    const users = [...document.querySelectorAll('.ask-dock .ask-bub.user')];
    return { last: users.length ? users[users.length - 1].textContent : '', sys: document.querySelectorAll('.ask-dock .ask-bub.sys').length };
  });
  ok('T6c 主会话回问以 user 族呈现且带前缀（问答面两族制）',
    t6c.last.includes('主会话回问：需要我帮你做什么') && t6c.sys === 0, t6c.last.slice(0, 50));
  // 错误呈现 v2.1：不起 sys 气泡——文本落助手气泡槽
  wsSend({ type: 'error', sessionId: askedSid, message: 'mock 失败' });
  await sleep(300);
  const t6d = await page.evaluate(() => {
    const ais = [...document.querySelectorAll('.ask-dock .ask-bub.ai')];
    return { last: ais.length ? ais[ais.length - 1].textContent : '', sys: document.querySelectorAll('.ask-dock .ask-bub.sys').length };
  });
  ok('T6d 错误落助手气泡槽（零 sys 呈现）', t6d.last.includes('出错了：mock 失败') && t6d.sys === 0, t6d.last.slice(0, 40));
  await shot(page, 't6-handoff-reply');

  // T7 主会话零交集（视图排他）
  const chatCount = await page.evaluate(() => document.querySelectorAll('#chat .bubble').length);
  for (const d of ['背景流式', '探测']) wsSend({ type: 'textDelta', sessionId: askedSid, delta: d });
  await sleep(250);
  const chatCount2 = await page.evaluate(() => document.querySelectorAll('#chat .bubble').length);
  ok('T7 ask 流式全程 #chat 气泡计数不变（零交集）', chatCount === chatCount2, `${chatCount} → ${chatCount2}`);

  // T8 UI 纪律
  const t8 = await geo(page);
  const colwrapMaxH = await page.evaluate(() => getComputedStyle(document.querySelector('.ask-colwrap')).maxHeight);
  ok('T8a 列 max-h 316（E-2 桌面档，小巧不占 canvas）', colwrapMaxH === '316px', colwrapMaxH);
  const maskCount = await page.evaluate(() => document.querySelectorAll('.ask-overlay-mask, .nf-modal-backdrop-ask').length);
  ok('T8b 零遮罩零面板（无窗体形态）', maskCount === 0);
  const colTouch = await page.evaluate(() => getComputedStyle(document.querySelector('.ask-col')).touchAction);
  ok('T8c 气泡列 touch-action: pan-y（#988 列内纵滚原生）', colTouch === 'pan-y', colTouch);

  // T10 v2.1 减法纪律（作者令 2026-10-28 22:42「简约一点。不需要两个箭头…」）
  const t10a = await page.evaluate(() => ({
    ghost: document.querySelectorAll('.ask-ghost').length,
    sess: document.querySelectorAll('.ask-dock .ask-sess').length,
    handoff: document.querySelectorAll('.ask-handoff').length,
  }));
  ok('T10a v2.1 退场件零残留（ghost 提示行 / session 徽标 / 转交钮）',
    t10a.ghost === 0 && t10a.sess === 0 && t10a.handoff === 0, JSON.stringify(t10a));
  // 服务端转录仍含 sys 帧（askState 重放）→ 渲染面永久过滤（收进不可见处）
  wsSend({
    type: 'askState', sessionId: askedSid, sessionKey: 'ask:' + askedSid.slice(4, 8),
    file: '/tmp/canvas-ask-fixture/记忆检索调优笔记.md', fileName: '记忆检索调优笔记.md',
    messages: [
      { role: 'user', text: 'τ 为什么取 30 天？' },
      { role: 'ai', text: '半衰期 ≈21 天。' },
      { role: 'sys', text: '↗ 已发送到主会话 · τ 为什么取 30 天？' },
      { role: 'sys', text: '状态噪声类示例' },
    ],
  });
  await sleep(300);
  const t10b = await page.evaluate(() => {
    const col = document.querySelector('.ask-dock .ask-col');
    return {
      sys: col.querySelectorAll('.ask-bub.sys').length,
      user: col.querySelectorAll('.ask-bub.user').length,
      ai: col.querySelectorAll('.ask-bub.ai').length,
      hasReceiptText: col.textContent.includes('已发送到主会话') || col.textContent.includes('状态噪声类'),
    };
  });
  ok('T10b 服务端转录 sys 帧渲染面永久过滤（气泡面只出 user/ai 两族）',
    t10b.sys === 0 && t10b.user === 1 && t10b.ai === 1 && !t10b.hasReceiptText, JSON.stringify(t10b));

  await page.close();

  // ── 移动档（body.mobile-layer 自举，#988 手势纪律）──
  const mob = await bootMobilePage(browser);
  const isMobile = await mob.page.evaluate(() => document.body.classList.contains('mobile-layer'));
  if (isMobile) {
    // 移动卡层：pane 在卡层展开前 hidden → openFile 只等 attached；
    // 先 openFile（事件建卡）再点卡层键（右抽屉 → 画板卡层展开）。
    await openFile(mob.page, '记忆检索调优笔记.md', { state: 'attached' });
    await sleep(400);
    await mob.page.evaluate(() => {
      const key = document.querySelector('.mh-dd[data-dd="right"] [data-face="canvas"]');
      if (key) key.click();
    });
    await sleep(800);
    await mob.page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:/tmp/canvas-ask-fixture/记忆检索调优笔记.md"]`, { state: 'visible', timeout: 8000 }).catch(() => {});
    await sleep(400);
    const m1 = await mob.page.evaluate(() => {
      const panel = document.getElementById('canvas-panel');
      const fab = document.querySelector('.ask-fab');
      if (!fab) return null;
      const pr = panel.getBoundingClientRect();
      const fr = fab.getBoundingClientRect();
      const bar = panel.querySelector('.mobile-card-switchbar');
      const br = bar ? bar.getBoundingClientRect() : null;
      return {
        w: fr.width, h: fr.height,
        fabBottomToPanel: pr.bottom - fr.bottom,
        barTopToPanel: br ? pr.bottom - br.top : null,
        fabRight: pr.right - fr.right,
        colMaxH: getComputedStyle(document.querySelector('.ask-col')).maxHeight,
      };
    });
    ok('T9a 移动入口钮 44×44', !!m1 && Math.abs(m1.w - 44) < 1.5 && Math.abs(m1.h - 44) < 1.5, JSON.stringify(m1));
    ok('T9b 座位在切换条上方（最靠右 · 让位实测）', !!m1 && m1.barTopToPanel !== null && m1.fabBottomToPanel >= m1.barTopToPanel + 8 && m1.fabRight <= 14, JSON.stringify(m1));
    ok('T9c 列 max-h 250（E-2 移动档）', m1 && m1.colMaxH === '250px', m1 && m1.colMaxH);
    // 气泡列表面横滑零切卡（surface 归属制：peek 不亮）
    await mob.page.click('.ask-fab');
    await sleep(500);
    const colBox = await mob.page.evaluate(() => {
      const c = document.querySelector('.ask-col');
      const r = c.getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + r.height / 2, visible: r.width > 0 };
    });
    if (colBox.visible) {
      // pointer 流（触屏通路走 pointer events；canvasGestures 以 pointerdown 起步）
      await mob.page.mouse.move(colBox.x, colBox.y);
      await mob.page.mouse.down();
      for (let i = 1; i <= 6; i++) {
        await mob.page.mouse.move(colBox.x + i * 15, colBox.y, { steps: 1 });
        await sleep(30);
      }
      await mob.page.mouse.up();
      await sleep(300);
      const peek = await mob.page.evaluate(() => {
        const p = document.querySelector('.mobile-card-peek.show');
        const dock = document.querySelector('.ask-dock');
        return { peek: !!p, dockClosed: dock ? dock.classList.contains('closed') : null };
      });
      ok('T9d 气泡列表面横滑零切卡（peek 不亮 + dock 不被误关）', !peek.peek && peek.dockClosed === false, JSON.stringify(peek));
    } else {
      ok('T9d 气泡列表面横滑零切卡（skip：列不可见）', true, 'dock 未展开——覆盖面见桌面档');
    }
    await shot(mob.page, 't9-mobile');
  } else {
    ok('T9 移动档（skip：本环境 body.mobile-layer 未激活）', true, 'bootstrap 视口判据未命中');
  }
  await mob.page.close();

  // ── 汇总 ──
  console.log(`\n=== canvas-ask-dock: ${pass} pass, ${fail} fail ===`);
} finally {
  await browser.close();
  server.close();
}
process.exit(fail ? 1 : 0);
