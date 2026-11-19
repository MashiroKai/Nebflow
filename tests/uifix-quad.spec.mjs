// uifix-quad.spec.mjs — UI 修复批 · 四件（作者令 2026-10-20）实施版验收
//
//   ① 智能体面板关不掉 —— 根因 = chat.css .agent-profile-panel 桌面态缺
//      `[hidden]{display:none}` 同伴规则（display:flex 按级联来源压 UA 默认，
//      X/Esc 设 hidden 仍渲染）。断言：X 关 / Esc 关 / 再开再关，关后
//      hidden 属性 + computed display:none + offsetWidth 0。
//   ② Canvas 堆叠按钮在位 —— 回归定位结论：canvas-stack v5 源码无损
//      （index.html canvas-header / split.css / canvas.js 自 v5 落地零 diff），
//      在役实例 :8080 实测供应现版。断言 pin 入口：#deck-fab 存在、可见、
//      钉面板右上 40×40、elementFromPoint 命中、非 canvas 子树、点击开合往返。
//   ③ 叠卡视角重做 —— a 阴影三版（默认 v2；setLook 热切换 peek 22/27/34 +
//      计算阴影互异）；b 卡片快照（markdown 克隆真文本 / mock 回落并存）；
//      c 滚轮重做（触控板连续小 delta 直通 + 面外不劫持 + 鼠标格近一卡 +
//      滑行动量）；d 竖排「滑动关闭」文字零命中；e 卡面 × 关闭（与
//      canvas-stack.spec A10 全通路互补的轻量腿）。
//   ④ header 会话区收纳 —— 中簇横排 [头像 28 + 名字 + 记忆钮]，header 恒定
//      50px（命名态断言——高度质量闸），中心列只此三件。
//
// 运行：node tests/uifix-quad.spec.mjs
// 可选 env：PORT（静态服务端口）、UIFIX_SHOTS_DIR（截图输出目录）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8151);
const BASE = `http://127.0.0.1:${PORT}`;
const TS = process.env.UIFIX_TS || new Date().toISOString().slice(0, 19).replace(/[-:T]/g, '').replace(/(\d{8})(\d{6}).*/, '$1_$2');
const SHOTS_DIR = process.env.UIFIX_SHOTS_DIR || resolve(HERE, `../evidence/${TS}_uifix-quad/shots`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
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
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

function nfFileRoute() {
  const ALLOWED = ['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'ico', 'mp4', 'webm', 'mp3', 'wav', 'woff', 'woff2', 'ttf', 'otf', 'pdf', 'docx', 'xlsx', 'pptx', 'epub', 'js', 'mjs', 'css', 'json', 'md', 'markdown', 'txt'];
  return async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    const ext = (p.split('.').pop() || '').toLowerCase();
    if (!ALLOWED.includes(ext)) return route.fulfill({ status: 400, contentType: 'text/plain', body: 'File type not allowed' });
    return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
  };
}

async function bootPage(browser, { viewport = { width: 1600, height: 1000 }, colorScheme = 'light' } = {}) {
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
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page, errors };
}

const absOf = (name) => `/tmp/uifix-quad/${name}`;
async function openFile(page, name, { itemType = 'markdown', content } = {}) {
  const absPath = absOf(name);
  await page.evaluate(({ name, absPath, itemType, content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:' + absPath, itemType, title: name,
        content: content || `# ${name}\n\n快照验收正文段落 · quad batch\n`,
        absPath, pinned: true,
      },
    }));
  }, { name, absPath, itemType, content });
  await sleep(250);
}
async function openAllTabs(page) {
  for (const f of ['alpha.md', 'bravo.md', 'charlie.md']) await openFile(page, f);
  await sleep(400);
}
async function state(page) { return page.evaluate(() => window.__canvasDeck.state()); }
async function panelRect(page) {
  return page.evaluate(() => {
    const p = document.getElementById('canvas-panel');
    return { x: p.offsetLeft, y: p.offsetTop, width: p.offsetWidth, height: p.offsetHeight,
             right: p.offsetLeft + p.offsetWidth, bottom: p.offsetTop + p.offsetHeight };
  });
}
/** 触控板式 wheel：连续小 delta 流（单事件 <40px 走基速增益，合计≈dy/240 卡）。 */
async function trackpadWheel(page, x, y, dy, { stepPx = 8, gapMs = 8 } = {}) {
  await page.mouse.move(x, y);
  const steps = Math.max(1, Math.round(Math.abs(dy) / stepPx));
  const stepDy = dy / steps;
  for (let i = 0; i < steps; i++) {
    await page.mouse.wheel(0, stepDy);
    await sleep(gapMs);
  }
}

let pass = 0, fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  results.push(line);
  console.log(line);
  if (cond) pass++; else fail++;
}
const approx = (a, b, tol) => Math.abs(a - b) <= tol;

mkdirSync(SHOTS_DIR, { recursive: true });
const browser = await chromium.launch();

/* ════════ ① 智能体面板关闭链路（cancel / Esc / 重开再关）════════ */
/* 判据换代注记（2026-10-23 spec-hygiene 批）：面板合并批作者令 ①（2026-10-23）
 * 退役 body 级独立面板 #agent-profile-panel —— 头像点击 = openMergedPanel('agent')
 * 打开「智能体 + 记忆」合并面板（#memory-modal.show + #memory-overlay.on，
 * .ap-pane 分区由 ensureMergedChrome 一件式注入；agentProfile.js 头注）。
 * 旧「独立面板 visible/display:flex + .ap-close X 关闭」判据在干净 HEAD 即红，
 * 随迁到合并面板接缝：开 = show+overlay+.ap-pane；关1 = #memory-modal-cancel
 * （旧 .ap-close 随面板退役）；关2 = Esc（main.js 全局 ESC 链点 #memory-overlay
 * → closeMemoryModal）。 */
{
  const { ctx, page, errors } = await bootPage(browser);
  const probe = () => page.evaluate(() => {
    const m = document.getElementById('memory-modal');
    const ov = document.getElementById('memory-overlay');
    if (!m) return { exists: false };
    const show = m.classList.contains('show');
    return { exists: true, show, overlayOn: !!(ov && ov.classList.contains('on')),
             visible: !!(m.offsetWidth || m.offsetHeight),
             apPane: !!m.querySelector('.ap-pane'),
             display: getComputedStyle(m).display };
  });
  const av = await page.evaluate(() => !!document.getElementById('agent-avatar-btn'));
  ok('PANEL-0 会话头头像入口在位', av === true);
  await page.click('#agent-avatar-btn');
  await sleep(350);
  const opened = await probe();
  ok('PANEL-1 头像点击开合并面板智能体分区（#memory-modal.show + overlay.on + .ap-pane 在场）',
    opened.exists && opened.show && opened.overlayOn && opened.visible && opened.apPane, JSON.stringify(opened));
  await page.screenshot({ path: join(SHOTS_DIR, 'panel-open.png') });
  // 关1：合并面板**背板点击**（overlay 点击处理器 → closeMemoryModal；modal.css 418 行注：
  // 智能体分区在场时收起记忆分区动作行 —— #memory-modal-cancel 在 agent 窗格被
  // 设计性隐藏，cancel 接缝不属于本分区脸）。
  await page.mouse.click(8, 8); // 背板角点（modal 卡外、overlay 内）
  await sleep(350);
  const closedByX = await probe();
  ok('PANEL-2 背板点击关闭（show 退 + overlay 退 + 不可见）',
    closedByX.exists && !closedByX.show && !closedByX.overlayOn && !closedByX.visible, JSON.stringify(closedByX));
  await page.screenshot({ path: join(SHOTS_DIR, 'panel-after-x.png') });
  /* 重开 → Esc 关（全局 ESC 链 → #memory-overlay → closeMemoryModal） */
  await page.click('#agent-avatar-btn');
  await sleep(300);
  await page.keyboard.press('Escape');
  await sleep(300);
  const closedByEsc = await probe();
  ok('PANEL-3 Esc 关闭（全局 ESC 链 → overlay → closeMemoryModal，同链路）',
    closedByEsc.exists && !closedByEsc.show && !closedByEsc.overlayOn && !closedByEsc.visible, JSON.stringify(closedByEsc));
  ok('PANEL-4 开关往返零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ ② 堆叠入口钮在位 + 开合往返 ════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  const pr = await panelRect(page);
  const fab = await page.evaluate(() => {
    const el = document.getElementById('deck-fab');
    const r = el.getBoundingClientRect();
    const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return {
      exists: true, display: getComputedStyle(el).display, w: r.width, h: r.height,
      pinnedDy: r.y - (document.getElementById('canvas-panel').offsetTop),
      pinnedDr: (document.getElementById('canvas-panel').offsetLeft + document.getElementById('canvas-panel').offsetWidth) - (r.x + r.width),
      hitOk: !!hit && (hit === el || el.contains(hit)),
      notCanvasChild: !document.getElementById('canvas-panel').contains(el),
      disabled: el.disabled,
      canvasOpen: document.body.classList.contains('canvas-open'),
    };
  });
  ok('FAB-1 入口钮存在且可见（canvas-open 态 display:flex）', fab.display === 'flex' && fab.canvasOpen, JSON.stringify(fab));
  ok('FAB-2 钉区 40×40 · top10/right12 · 命中测试直达', fab.w === 40 && fab.h === 40 &&
    approx(fab.pinnedDy, 10, 1.5) && approx(fab.pinnedDr, 12, 1.5) && fab.hitOk, JSON.stringify(fab));
  ok('FAB-3 有页签时入口可用（disabled=false · body 级非 canvas 子树）',
    fab.disabled === false && fab.notCanvasChild, `disabled=${fab.disabled}`);
  await page.screenshot({ path: join(SHOTS_DIR, 'fab-entry.png') });
  await page.click('#deck-fab');
  await sleep(2600);
  const sOpen = await state(page);
  ok('FAB-4 点击入口 = 进入叠卡（deck.open · 环=页签数）', sOpen.open === true && sOpen.M === sOpen.tabCount,
    `open=${sOpen.open} M=${sOpen.M}`);
  await page.click('#deck-fab');
  await sleep(800);
  const sClosed = await state(page);
  ok('FAB-5 再点退出（deck 关 · 页签保留）', sClosed.open === false && sClosed.tabCount === sOpen.tabCount,
    `open=${sClosed.open} n=${sClosed.tabCount}`);
  ok('FAB-6 入口往返零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ ③a 阴影三版（默认 v2 · setLook 热切换 · peek 档位联动）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  const s0 = await state(page);
  ok('LOOK-1 默认档 v2（peek 27/9）', s0.look === 'v2' && s0.lookPeek.peek1 === 27 && s0.lookPeek.peek2 === 9,
    JSON.stringify(s0.lookPeek));
  await page.click('#deck-fab');
  await sleep(2600);
  const peeks = {};
  const shadows = {};
  for (const v of ['v1', 'v3']) {
    await page.evaluate((vv) => window.__canvasDeck.setLook(vv), v);
    await sleep(1200);                       // 几何重排（间距档位 deckFrame 重算）
    const sv = await state(page);
    peeks[v] = sv.lookPeek.peek1;
    shadows[v] = await page.evaluate(() => {
      const c = document.querySelector('.deck-card[data-dim="0"]') || document.querySelector('.deck-card');
      return getComputedStyle(c).boxShadow;
    });
  }
  await page.evaluate(() => window.__canvasDeck.setLook('v2'));
  const shV2 = await page.evaluate(() => getComputedStyle(document.querySelector('.deck-card[data-dim="0"]') || document.querySelector('.deck-card')).boxShadow);
  ok('LOOK-2 三版露边档位 22/27/34（v2 默认 + setLook 热切换）',
    peeks.v1 === 22 && peeks.v3 === 34, JSON.stringify(peeks));
  ok('LOOK-3 三版前景档阴影互异（作者挑版通道）',
    new Set([shadows.v1, shV2, shadows.v3]).size === 3,
    `v1/v2/v3 shadow tokens distinct`);
  await ctx.close();
}

/* ════════ ③b 卡片快照（真内容克隆 + mock 回落）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  // url 页签（iframe 族）→ 快照回落 mock
  await page.evaluate(() => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'url:https://example.com', itemType: 'url', title: 'https://example.com', url: 'https://example.com' },
    }));
  });
  await sleep(500);
  await page.click('#deck-fab');
  await sleep(3200);
  const snap = await page.evaluate(() => ({
    live: document.querySelectorAll('.card-thumb.has-snap').length,
    mock: document.querySelectorAll('.card-thumb[data-snap="mock"]').length,
    pending: document.querySelectorAll('.card-thumb[data-snap="pending"]').length,
    mdText: (() => {
      const el = document.querySelector('.card-thumb.has-snap .card-snap-inner');
      return el ? el.textContent.slice(0, 80) : '';
    })(),
    mdViewer: !!document.querySelector('.card-thumb.has-snap .card-snap-inner .canvas-md-viewer'),
  }));
  ok('SNAP-1 markdown 页签快照 = 真内容克隆（.canvas-md-viewer 在场 + 正文命中）',
    snap.mdViewer && snap.mdText.includes('快照验收正文段落'), snap.mdText.slice(0, 50));
  ok('SNAP-2 iframe 族页签回落 mock（无克隆）', snap.mock >= 1, `mock=${snap.mock}`);
  ok('SNAP-3 快照分帧补齐零 pending', snap.pending === 0, `pending=${snap.pending}`);
  ok('SNAP-4 零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ ③c 滚轮重做（触控板小 delta / 面外不劫持 / 鼠标格 / 滑行）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await sleep(2600);
  const pr = await panelRect(page);
  const cx = pr.x + pr.width / 2, cy = pr.y + pr.height / 2;

  /* (a) 触控板连续小 delta（8px×15 ≈ 120px 合计 = 0.5 卡）——跟手直通 */
  const chatTopBefore = await page.evaluate(() => document.getElementById('chat').scrollTop);
  await trackpadWheel(page, cx, cy, 120, { stepPx: 8, gapMs: 6 });
  await sleep(150);
  const midTrack = await state(page);
  ok('WHEEL-1 触控板连续小 delta 直通 scrub（120px ≈ 0.5 卡，未贴靠连续语义）',
    midTrack.target > 0.35 && midTrack.target < 0.75, `target=${midTrack.target.toFixed(3)}`);
  const chatTopAfter = await page.evaluate(() => document.getElementById('chat').scrollTop);
  ok('WHEEL-2 叠卡面内捕获：页面滚动不被劫持（#chat scrollTop 不变）',
    chatTopAfter === chatTopBefore, `${chatTopBefore} → ${chatTopAfter}`);

  /* (b) 面外（侧栏上空）双指滚动 = 不驱动擦洗、不 preventDefault（原生语义）。
     先等 WHEEL-1 的滑行动量完全衰减，读基准，再面外滚。 */
  await sleep(900);
  const outBefore = (await state(page)).target;
  await trackpadWheel(page, 60, 500, 240, { stepPx: 12, gapMs: 6 });
  await sleep(120);
  const outAfter = await state(page);
  ok('WHEEL-3 叠卡面外双指 = 不驱动擦洗（target 不动）', approx(outAfter.target, outBefore, 0.02),
    `${outBefore.toFixed(3)} → ${outAfter.target.toFixed(3)}`);

  /* (c) 鼠标格：单事件 |dy|=100 ≥40 → 档位增益 ≈ 0.9 卡 */
  await page.evaluate(() => { window.__canvasDeck.close(); });
  await sleep(700);
  await page.click('#deck-fab');
  await sleep(2600);
  await page.mouse.move(cx, cy);
  await page.mouse.wheel(0, 100);
  await sleep(2400);
  const notched = await state(page);
  ok('WHEEL-4 鼠标格近一卡（100px × 档位增益 ≈ 0.9 卡）',
    approx(notched.pos, 0.9, 0.15), `pos=${notched.pos.toFixed(3)}`);

  /* (d) 滑行动量：快甩（<40px 大步子 ×10，60ms 内）后静默 → 位置继续漂移 */
  await page.evaluate(() => { window.__canvasDeck.close(); });
  await sleep(700);
  await page.click('#deck-fab');
  await sleep(2600);
  await trackpadWheel(page, cx, cy, 320, { stepPx: 32, gapMs: 5 });
  await sleep(60);
  const beforeGlide = (await state(page)).pos;
  await sleep(450);                       // 滑行段（110ms 静默后启动，~340ms 衰减）
  const afterGlide = (await state(page)).pos;
  ok('WHEEL-5 事件流滑行动量（静默后位置继续漂移）', afterGlide > beforeGlide + 0.03,
    `pos ${beforeGlide.toFixed(3)} → ${afterGlide.toFixed(3)}`);
  ok('WHEEL-6 滚轮全通路零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ ③d 竖排「滑动关闭」文字零命中 ════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await sleep(2600);
  const s = await state(page);
  const noText = await page.evaluate(() => ![...document.querySelectorAll('#canvas-panel, #deck-layer')].some((el) => el.textContent.includes('滑动关闭')));
  ok('HINT-1 面板 + 卡组 + 脚条内「滑动关闭」文案零命中',
    s.swipeHints.panel === 0 && s.swipeHints.card === 0 && noText, JSON.stringify(s.swipeHints));
  await ctx.close();
}

/* ════════ ③e 卡面 × 关闭（轻量腿 · 全通路见 canvas-stack.spec A10）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await openAllTabs(page);
  await page.click('#deck-fab');
  await sleep(2600);
  /* 先滚一卡（慢步进少动量），再关**深处**卡：环流就地收敛保相位
     （deckSettleRingRemove pos 不变）——× 钮与右键菜单同一条 deckRequestClose 腿。 */
  const pr = await panelRect(page);
  await trackpadWheel(page, pr.x + pr.width / 2, pr.y + pr.height / 2, 240, { stepPx: 12, gapMs: 40 });
  await sleep(2200);
  const pos0 = (await state(page)).pos;
  const n0 = (await state(page)).tabCount;
  await page.evaluate(() => document.querySelector('.deck-card[data-r="2"] .deck-card-x').click());
  await sleep(3200);
  const s1 = await state(page);
  ok('XCLOSE-1 深处卡 × 点击 = 该页签关闭（deck 保持 · 相位保持 pos 不变）',
    s1.tabCount === n0 - 1 && s1.open === true && approx(s1.pos, pos0, 0.01),
    `n=${s1.tabCount}/${n0} pos=${s1.pos.toFixed(3)} (was ${pos0.toFixed(3)})`);
  ok('XCLOSE-2 环重建后 × 钮补齐', s1.cardXButtons === s1.M, `x=${s1.cardXButtons} M=${s1.M}`);
  await ctx.close();
}

/* ════════ ④ header 会话区收纳（同列头像+名字 · 记忆钮在旁 · 高度闸）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  /* 命名态：profile 面板就地编辑（回车保存 → 会话头同步）——顺带覆盖 ① 的再开路径 */
  await page.click('#agent-avatar-btn');
  await sleep(300);
  await page.fill('.ap-name-input', '星阑');
  await page.keyboard.press('Enter');
  await sleep(350);
  await page.click('.agent-profile-panel .ap-close');
  await sleep(350);
  const hdr = await page.evaluate(() => {
    const center = document.querySelector('#header .header-center');
    const ids = [...center.children].map((el) => el.id || el.className);
    const name = document.getElementById('session-name');
    const av = document.getElementById('agent-avatar-btn');
    const mem = document.getElementById('memory-btn');
    return {
      headerH: document.getElementById('header').offsetHeight,
      centerIds: ids,
      nameText: name.textContent,
      avSize: av.offsetWidth + 'x' + av.offsetHeight,
      memBeside: mem && center.contains(mem),
      nameBeside: name && center.contains(name),
      centerRow: getComputedStyle(center).flexDirection === 'row',
    };
  });
  ok('HDR-1 中心列 = 头像 + 名字 + 记忆钮（其余信息退场）',
    JSON.stringify(hdr.centerIds) === JSON.stringify(['agent-avatar-btn', 'session-name', 'memory-btn']) &&
    hdr.nameBeside && hdr.memBeside, JSON.stringify(hdr.centerIds));
  ok('HDR-2 中簇横排（头像+名字同列）', hdr.centerRow === true);
  ok('HDR-3 头像 28×28 紧凑（原 40 纵排顶 header）', hdr.avSize === '28x28', hdr.avSize);
  ok('HDR-4 header 高度恒定 50px（命名态 · 高度质量闸）', hdr.headerH === 50, `h=${hdr.headerH}`);
  ok('HDR-5 命名经会话头可见（「星阑」）', hdr.nameText === '星阑', `name=${hdr.nameText}`);
  await page.screenshot({ path: join(SHOTS_DIR, 'header-named.png') });
  ok('HDR-6 header 通路零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();

console.log(results.join('\n'));
console.log(`\nuifix-quad: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
