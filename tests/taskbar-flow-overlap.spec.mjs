// taskbar-flow-overlap.spec.mjs — 任务列表「收起/展开」条与聊天内容零重叠验收。
//
// 需求（作者 2026-10-09 截图派单）：点「N 任务」收起/展开条不得影响背景对话——
// 收起条不得压住消息气泡；展开/收起时聊天内容不被推动、遮挡，滚动位置稳定。
// 预期形态：任务条占据自己的布局空间（文档流），与气泡 boundingBox 零交集；
// 收起/展开只改自身高度，滚动内容零位移（贴底态仅允许钉底补偿量）。
//
// 载体：真页面（src/main/resources/web 静态服务）+ 假后端（route /api/*、
// routeWebSocket 假 WS：configData/sessionList/getHistory→historyPage 40 对
// user/ai；/api/chains → 39 行 delegate 投影）。与 tests/chat-autoscroll-follow
// 同一套 boot 范式；动作全部走 GUI（点击 .task-toggle / 滚轮），零状态注入。
//
// Run: node tests/taskbar-flow-overlap.spec.mjs [--out <证据目录>]

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = (() => {
  const i = process.argv.indexOf('--out');
  return (i > -1 && process.argv[i + 1]) || join(dirname(fileURLToPath(import.meta.url)), '..', 'test-results', 'taskbar-flow-overlap');
})();
mkdirSync(OUT, { recursive: true });

const HEADLESS = process.env.HEADLESS !== '0';
const SID = 'taskbar-root-session';
const ROWS_N = 39;

// 历史夹具：40 对 user/ai —— 长对话足以让 #chat 溢出滚动（chat-autoscroll 同款）。
const T0 = Date.now() - 40 * 8000;
const HISTORY = [];
for (let i = 0; i < 40; i++) {
  HISTORY.push({ type: 'user', text: `用户消息 ${i + 1}：请继续处理第 ${i + 1} 项，完成后给出简要结论与下一步计划，保持与上一条格式一致。`, ts: T0 + i * 8000 });
  HISTORY.push({ type: 'ai', text: `回复 ${i + 1}：这一项已处理完毕。结论：参数与边界都已核对；下一步将按既定顺序继续推进剩余事项，并在全部完成后统一汇总。`, ts: T0 + i * 8000 + 4000 });
}

// /api/chains 夹具：39 行 delegate 投影（rowFromRegistry 吃的字段）。
const CHAINS = Array.from({ length: ROWS_N }, (_, i) => ({
  id: `del-${i + 1}`,
  taskId: `T${1000 + i}`,
  title: `派活任务 ${i + 1}：链路验收与回归面`,
  executor: 'nebflow',
  reportStatus: i % 3 === 0 ? 'finish' : '',
  project: 'general',
  orchestrationMode: 'single',
}));

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/chains') {
      // 延迟回包：让 sessionList 先落位（renderTaskList 拿到 sid），镜像首刷事件
      // 到达时 rerenderWithNodes 才有人接——静态夹具没有 WS node* 帧兜底二次刷。
      await new Promise((r) => setTimeout(r, 1200));
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ rows: CHAINS }) });
    }
    if (p.startsWith('/api/chains/')) {
      return route.fulfill({ status: 404, contentType: 'application/json', body: 'not found' });
    }
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'taskbar-e2e', agentName: 'Nebula' }],
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
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: HISTORY, hasMore: false, offset: 0, total: HISTORY.length }));
      }
    });
  });
  await page.goto('http://127.0.0.1:8931/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  // 任务面板（39 行）渲染齐 + 历史行渲染齐。
  await page.waitForSelector('#task-list.has-tasks .task-card .task-toggle', { timeout: 15000 });
  await page.waitForFunction(() => document.querySelectorAll('#chat .row').length >= 60, null, { timeout: 15000 });
  await page.waitForTimeout(700); // 入场动画/首帧钉底稳定
}

// ── 只读几何采集（无副作用）───────────────────────────────────────────────
async function geo(page) {
  return page.evaluate(() => {
    const chat = document.getElementById('chat');
    const card = document.querySelector('#task-list .task-card');
    const overlays = document.getElementById('top-overlays');
    const rows = [...chat.querySelectorAll('.row')];
    const chatR = chat.getBoundingClientRect();
    const bandR = overlays.getBoundingClientRect();
    const barR = card ? card.getBoundingClientRect() : null;
    // 可见行 = 与聊天视口相交的行；重叠 = 可见部分与收起条的交集面积。
    const inter = (a, b) => {
      const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
      const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
      return (w > 0 && h > 0) ? w * h : 0;
    };
    const visBox = (r, clip) => ({
      left: Math.max(r.left, clip.left), right: Math.min(r.right, clip.right),
      top: Math.max(r.top, clip.top), bottom: Math.min(r.bottom, clip.bottom),
    });
    let overlapRows = 0, overlapArea = 0;
    const overlaps = [];
    for (const r of rows) {
      const rr = r.getBoundingClientRect();
      const vis = inter(rr, chatR);
      if (vis <= 4) continue;
      // 遮挡按「可见部分」计：滚出视口上缘的行，其被滚动端口裁掉的部分与条
      // 几何相交但用户看不见——不算遮挡。
      const ov = inter(visBox(rr, chatR), barR || bandR);
      if (ov > 4) { overlapRows++; overlapArea += ov; overlaps.push((r.textContent || '').slice(0, 18)); }
    }
    const first = rows[0], last = rows[rows.length - 1];
    // 内容坐标 = rect.top − 视口顶 + scrollTop（滚动端口内容系；与视口本体
    // 是否被带顶动无关——量的是「内容在文档流里动没动」，不是视口边）。
    const contentY = (el) => el ? Math.round(el.getBoundingClientRect().top - chatR.top + chat.scrollTop) : null;
    return {
      rows: rows.length,
      barCollapsed: card ? card.classList.contains('collapsed') : null,
      barRect: barR ? { top: Math.round(barR.top), bottom: Math.round(barR.bottom), h: Math.round(barR.height) } : null,
      bandRect: { top: Math.round(bandR.top), bottom: Math.round(bandR.bottom), h: Math.round(bandR.height) },
      chatRect: { top: Math.round(chatR.top), bottom: Math.round(chatR.bottom) },
      barVsChat: barR ? inter(barR, chatR) : inter(bandR, chatR),
      overlapRows, overlapArea: Math.round(overlapArea), overlaps: overlaps.slice(0, 3),
      scrollTop: Math.round(chat.scrollTop),
      scrollHeight: chat.scrollHeight,
      clientHeight: chat.clientHeight,
      dist: Math.round(chat.scrollHeight - chat.scrollTop - chat.clientHeight),
      firstRowContentY: contentY(first),
      firstRowTopInViewport: first ? Math.round(first.getBoundingClientRect().top) : null,
      lastRowBottomVsChat: last ? Math.round(last.getBoundingClientRect().bottom - chatR.bottom) : null,
    };
  });
}

const ok = (name, cond, detail) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + JSON.stringify(detail) : ''}`);
  if (!cond) process.exitCode = 1;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const shot = (page, name) => page.screenshot({ path: join(OUT, name) }).then(() => console.log(`[shot] ${join(OUT, name)}`));

const browser = await chromium.launch({ headless: HEADLESS });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('console', (m) => { if (m.type() === 'error') console.log('[console.error]', m.text().slice(0, 240)); });
page.on('pageerror', (e) => console.log('[pageerror]', String(e).slice(0, 300)));

try {
  await bootApp(page);
  const toggle = page.locator('#task-list .task-card .task-toggle');
  ok('boot: 收起钮唯一可见', await toggle.count() === 1);

  // 归一化：显式滚轮压到最底（鼠标落在聊天区，避开任务条自身的滚动体），
  // 保证「贴底」前提可复现（boot 钉底与晚到渲染有竞态，不作为判据前提）。
  await page.mouse.move(720, 700);
  await page.mouse.wheel(0, 3000);
  await sleep(400);

  // ── T1 展开态 @ 贴底：任务条 vs 可见气泡零重叠 ──
  let g = await geo(page);
  console.log('T1 expanded@bottom:', JSON.stringify(g));
  await shot(page, 't1_expanded_at_bottom.png');
  ok('T1 任务条/浮层带与聊天视口零交集', g.barVsChat === 0, { barVsChat: g.barVsChat });
  ok('T1 可见气泡零遮挡', g.overlapRows === 0, { overlapRows: g.overlapRows, overlapArea: g.overlapArea, sample: g.overlaps });
  ok('T1 贴底（dist≤44）', g.dist <= 44, { dist: g.dist });

  // ── T2 贴底收起：内容零位移，收起后仍贴底（允许钉底补偿）──
  await toggle.click();
  await sleep(500); // max-height .3s / grid-rows .28s 过渡落定
  g = await geo(page);
  console.log('T2 collapsed@bottom:', JSON.stringify(g));
  await shot(page, 't2_collapsed_at_bottom.png');
  ok('T2 收起生效', g.barCollapsed === true);
  ok('T2 收起条与气泡零遮挡', g.overlapRows === 0, { overlapRows: g.overlapRows, sample: g.overlaps });
  ok('T2 仍贴底（dist≤44，钉底补偿）', g.dist <= 44, { dist: g.dist });
  const t2FirstContentY = g.firstRowContentY;

  // ── T3 贴底展开：同 T2 ──
  await toggle.click();
  await sleep(500);
  g = await geo(page);
  console.log('T3 expanded@bottom:', JSON.stringify(g));
  await shot(page, 't3_expanded_again.png');
  ok('T3 展开生效', g.barCollapsed === false);
  ok('T3 展开条与气泡零遮挡', g.overlapRows === 0, { overlapRows: g.overlapRows, sample: g.overlaps });
  ok('T3 仍贴底（dist≤44）', g.dist <= 44, { dist: g.dist });
  ok('T3 内容零位移（首行内容坐标不变）', g.firstRowContentY === t2FirstContentY && g.firstRowContentY !== null, { t2: t2FirstContentY, t3: g.firstRowContentY });

  // ── T4 中段阅读位：滚轮上滚 ~900px 后收起/展开，阅读位严格不动 + 零遮挡 ──
  // 鼠标坐标取聊天区中段（展开带底 514 之下）——悬在任务条上会滚它的内滚动体。
  await page.mouse.move(720, 700);
  await page.mouse.wheel(0, -900);
  await sleep(400);
  const before = await geo(page);
  ok('T4 前置：上滚确已离开底部', before.scrollTop > 0 && before.dist > 44, { scrollTop: before.scrollTop, dist: before.dist });
  console.log('T4 mid-scroll before:', JSON.stringify(before));
  await shot(page, 't4a_midscroll_expanded.png');

  await toggle.click(); // 收起
  await sleep(500);
  g = await geo(page);
  console.log('T4 mid-scroll collapsed:', JSON.stringify(g));
  await shot(page, 't4b_midscroll_collapsed.png');
  ok('T4 收起：scrollTop 严格不变', g.scrollTop === before.scrollTop, { before: before.scrollTop, after: g.scrollTop });
  ok('T4 收起：首行内容坐标严格不变', g.firstRowContentY === before.firstRowContentY, { before: before.firstRowContentY, after: g.firstRowContentY });
  ok('T4 收起：零遮挡', g.overlapRows === 0, { overlapRows: g.overlapRows, sample: g.overlaps });

  await toggle.click(); // 展开
  await sleep(500);
  g = await geo(page);
  console.log('T4 mid-scroll expanded:', JSON.stringify(g));
  await shot(page, 't4c_midscroll_reexpanded.png');
  ok('T4 展开：scrollTop 严格不变', g.scrollTop === before.scrollTop, { before: before.scrollTop, after: g.scrollTop });
  ok('T4 展开：首行内容坐标严格不变', g.firstRowContentY === before.firstRowContentY, { before: before.firstRowContentY, after: g.firstRowContentY });
  ok('T4 展开：零遮挡', g.overlapRows === 0, { overlapRows: g.overlapRows, sample: g.overlaps });
} finally {
  await browser.close();
}
console.log(process.exitCode ? '\n== FAIL ==' : '\n== ALL PASS ==');
