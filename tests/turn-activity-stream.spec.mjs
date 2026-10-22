// turn-activity-stream.spec.mjs — 对话层活性批①：回合活动流条（修「看着像卡死」）
//
// 症状（作者实测澄清）：长回合（尤其委派/后台批并跑的回合）期间，对话面完全
// 静默——委派/后台任务事件后端已推（agentStart / agentToolStart / agentDone /
// delegateWaiting / backgroundTaskUpdate 全部经 wsHub.broadcast），但对话层零消费，
// 用户只能看到最终文本落地。本 spec 钉：这些帧到达 ⇒ 主窗活动条（#activity-strip）
// 实时上屏（默认一行滚动摘要、可展开最近 N 条、同文聚合、done 清空、非在场
// 会话不抢当前条）。
//
// harness = chat-autoscroll-follow.spec.mjs 同一套：静态路由 WEB 目录 +
// routeWebSocket 假后端 + 真实 ws.js 分发路径注入帧。零后端、零 sbt、零构建。
//
// Run: node tests/turn-activity-stream.spec.mjs
// 可选 env：PORT=8188、HEADLESS=0、SHOTS_DIR=<dir>

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 8188);
const SHOTS_DIR = process.env.SHOTS_DIR || '';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'cas-root-session';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
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
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
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
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      }
    });
  });
  await page.goto(`http://127.0.0.1:${PORT}/`);
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(600);
}

/** 走真实 ws.js 分发路径注入一帧。 */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

function stripGeo(page) {
  return page.evaluate(() => {
    const el = document.getElementById('activity-strip');
    if (!el) return null;
    const head = el.querySelector('.activity-strip-head');
    const items = [...el.querySelectorAll('.activity-strip-item')];
    return {
      exists: true,
      visible: el.classList.contains('visible'),
      expanded: el.classList.contains('expanded'),
      sid: el.dataset.sid || '',
      summary: el.querySelector('.activity-strip-summary')?.textContent || '',
      title: el.querySelector('.activity-strip-title')?.textContent || '',
      count: el.querySelector('.activity-strip-count')?.textContent || '',
      itemTexts: items.map((i) => i.querySelector('.activity-item-text')?.textContent || ''),
      headText: head ? head.textContent : '',
    };
  });
}

const ok = (name, cond, detail) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + JSON.stringify(detail) : ''}`);
  if (!cond) process.exitCode = 1;
};

const browser = await chromium.launch({ headless: process.env.HEADLESS !== '0' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('console', (m) => { if (m.type() === 'error') console.log('[console.error]', m.text().slice(0, 300)); });
page.on('pageerror', (e) => console.log('[pageerror]', String(e).slice(0, 400)));

try {
  await bootApp(page);

  // R0 — 初始：无活动 ⇒ 条不可见。
  let g = await stripGeo(page);
  ok('R0 strip present in DOM', g && g.exists === true);
  ok('R0 strip hidden before any activity', g && g.visible === false, { visible: g && g.visible });

  // 腿 1 — 批派发（agentStart）。
  await inject(page, {
    type: 'agentStart', sessionId: SID, rootSessionId: SID,
    agentId: 'delegate-nebflow-b1', nodeSessionId: 'delegate-nebflow-b1-x',
    name: '实现批', agentType: 'delegate', taskTitle: '活动流批',
  });
  await sleep(120);
  g = await stripGeo(page);
  ok('A1 agentStart shows strip', g.visible === true, g);
  ok('A2 strip bound to owning session', g.sid === SID, { sid: g.sid });
  ok('A3 dispatch line rendered', g.summary.includes('实现批') && g.summary.includes('活动流批'), { summary: g.summary });
  ok('A4 count reads 1', g.count.includes('1'), { count: g.count });

  // 腿 2 — 子代理工具调用（agentToolStart，label = 后端 summarizeToolCall 摘要）。
  await inject(page, {
    type: 'agentToolStart', sessionId: SID, rootSessionId: SID,
    agentId: 'delegate-nebflow-b1', nodeSessionId: 'delegate-nebflow-b1-x',
    label: 'Read src/main/scala/nebflow/gateway/WsHub.scala',
  });
  await sleep(120);
  g = await stripGeo(page);
  ok('B1 tool line becomes latest summary', g.summary.includes('Read src/main/scala/nebflow/gateway/WsHub.scala'), { summary: g.summary });
  ok('B2 tool line carries batch short-name prefix', g.summary.startsWith('实现批 ·'), { summary: g.summary });

  // 腿 3 — 同文连发聚合（1.5s 窗内同 label 重复 ⇒ 计数 ×2，不新增行）。
  await inject(page, {
    type: 'agentToolStart', sessionId: SID, rootSessionId: SID,
    agentId: 'delegate-nebflow-b1', nodeSessionId: 'delegate-nebflow-b1-x',
    label: 'Read src/main/scala/nebflow/gateway/WsHub.scala',
  });
  await sleep(120);
  g = await stripGeo(page);
  ok('C1 duplicate tool line coalesces (×2)', g.summary.includes('×2'), { summary: g.summary });

  // 腿 4 — 后台任务状态变化（计数口径：total = Σ entry.count，同文聚合仍 +1/次）。
  await inject(page, {
    type: 'backgroundTaskUpdate', sessionId: SID, rootSessionId: SID,
    taskId: 't1', description: 'sbt compile', status: 'running',
  });
  await sleep(150);
  g = await stripGeo(page);
  ok('D1 bg task line rendered', g.summary.includes('sbt compile') && g.summary.includes('running'), { summary: g.summary });
  ok('D2 event count grows to 4 (1 dispatch + 2 tool + 1 bgtask)', g.count.includes('4'), { count: g.count });

  // 腿 5 — 待合并（delegateWaiting：sessionId = 委派子会话，路由修复后须达处理器）。
  await inject(page, { type: 'delegateWaiting', sessionId: 'delegate-nebflow-b1-x', mode: 'merge' });
  await sleep(150);
  g = await stripGeo(page);
  ok('E0 waiting line becomes latest summary', g.summary.includes('实现批'), { summary: g.summary });
  ok('E0b event count grows to 5', g.count.includes('5'), { count: g.count });

  // 腿 6 — 展开：最近条目列表（新在上）。
  await page.click('#activity-strip .activity-strip-head');
  await sleep(120);
  g = await stripGeo(page);
  ok('E1 expand toggles class', g.expanded === true);
  ok('E2 expanded list has 4 entries', g.itemTexts.length === 4, { n: g.itemTexts.length });
  ok('E3 newest first is the waiting line', (g.itemTexts[0] || '').includes('实现批'), { first: g.itemTexts[0] });
  ok('E4 bg-task line retained in list', g.itemTexts.some((tx) => tx.includes('sbt compile')), { texts: g.itemTexts });

  if (SHOTS_DIR) {
    const { mkdirSync } = await import('node:fs');
    mkdirSync(SHOTS_DIR, { recursive: true });
    await page.screenshot({ path: join(SHOTS_DIR, 'activity-stream-expanded.png'), clip: { x: 640, y: 300, width: 800, height: 600 } });
  }

  // 腿 6 — 收起：再点恢复一行摘要。
  await page.click('#activity-strip .activity-strip-head');
  await sleep(120);
  g = await stripGeo(page);
  ok('F1 collapse toggles back', g.expanded === false);

  // 腿 7 — 非在场会话的活动不抢当前条（只记账）。
  await inject(page, {
    type: 'agentToolStart', sessionId: 'other-session', rootSessionId: 'other-session',
    agentId: 'delegate-other', nodeSessionId: 'delegate-other-x', label: 'Bash ls',
  });
  await sleep(120);
  g = await stripGeo(page);
  ok('G1 foreign-session activity does not repaint strip', g.sid === SID && g.summary.includes('实现批'), { summary: g.summary });
  const foreignBooked = await page.evaluate(async () => {
    const s = (await import('/js/state.js')).default;
    return (s.sessionActivity['other-session'] || []).length;
  });
  ok('G2 foreign-session activity still booked', foreignBooked === 1, { n: foreignBooked });

  // 腿 8 — 回合终态（done）⇒ 条清空隐藏。
  await inject(page, { type: 'done', sessionId: SID });
  await sleep(200);
  g = await stripGeo(page);
  ok('H1 done clears strip', g.visible === false, g);
  const bookCleared = await page.evaluate(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return !s.sessionActivity[sid];
  }, SID);
  ok('H2 done clears ledger', bookCleared === true);

  console.log('\nturn-activity-stream.spec done');
} finally {
  await browser.close();
}
