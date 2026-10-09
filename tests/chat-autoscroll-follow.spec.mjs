// chat-autoscroll-follow.spec.mjs — 主窗聊天面「新消息自动贴底跟随」复现/判据读数
//
// 症状（用户截图 2026-10-08 18:56）：新消息（流式增量 / 注入气泡 / 状态行）到达时
// 聊天区不自动上滚露出新内容；composer 下方还能看到消息文字渗出（= scrollTop 停在
// 旧位，内容继续向下长，从 composer 下缘的无 blur 条带里露出来）。
//
// harness = delegate-ui-twolayer.spec.mjs 同一套：静态路由 WEB 目录 + routeWebSocket
// 假后端 + 真实 ws.js 分发路径注入帧。零后端、零 sbt、零构建。
//
// Run: node tests/chat-autoscroll-follow.spec.mjs
// 可选 env：PORT=8177、HEADLESS=0、SHOTS_DIR=<dir>

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 8177);
const SHOTS_DIR = process.env.SHOTS_DIR || '';
const HEADLESS = process.env.HEADLESS !== '0';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'cas-root-session';
const T0 = Date.now() - 60 * 60 * 1000;

// 历史夹具：40 对 user/ai，足以让 #chat 溢出（每行 ~65px × 80 行 ≈ 5200px）。
const HISTORY = [];
for (let i = 0; i < 40; i++) {
  HISTORY.push({ type: 'user', text: `用户消息 ${i + 1}：请继续处理第 ${i + 1} 项。`, ts: T0 + i * 8000 });
  HISTORY.push({ type: 'ai', text: `回复 ${i + 1}：好的，这一项我已经处理完毕，下面是结果摘要与下一步计划说明。`, ts: T0 + i * 8000 + 4000 });
}

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
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: HISTORY, hasMore: false, offset: 0, total: HISTORY.length }));
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

async function geo(page) {
  return page.evaluate(() => {
    const chat = document.getElementById('chat');
    const pill = document.querySelector('.scroll-new-pill');
    const rows = chat.querySelectorAll('.row');
    const last = rows[rows.length - 1];
    const cs = getComputedStyle(chat);
    return {
      rows: rows.length,
      scrollTop: Math.round(chat.scrollTop),
      scrollHeight: chat.scrollHeight,
      clientHeight: chat.clientHeight,
      dist: Math.round(chat.scrollHeight - chat.scrollTop - chat.clientHeight),
      overflowY: cs.overflowY,
      lastRowBottomVsChat: last ? Math.round(last.getBoundingClientRect().bottom - chat.getBoundingClientRect().bottom) : null,
      docScrollY: Math.round(window.scrollY),
      docScrollable: document.documentElement.scrollHeight > document.documentElement.clientHeight,
      pillHidden: pill ? pill.classList.contains('hidden') : null,
      pillText: pill ? pill.textContent : null,
    };
  });
}

const ok = (name, cond, detail) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + JSON.stringify(detail) : ''}`);
  if (!cond) process.exitCode = 1;
};

const browser = await chromium.launch({ headless: HEADLESS });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('console', (m) => { if (m.type() === 'error') console.log('[console.error]', m.text().slice(0, 300)); });
page.on('pageerror', (e) => console.log('[pageerror]', String(e).slice(0, 400)));

try {
  await bootApp(page);

  // R0 — 载入后应钉底。
  let g = await geo(page);
  console.log('R0 after history load:', JSON.stringify(g));
  ok('R0 history load pins to bottom (dist≤44)', g.dist <= 44, { dist: g.dist });

  // ── 腿 1：live 流式（注入气泡 → thinking → textDelta 若干）──
  await inject(page, { type: 'user', sessionId: SID, injected: true, source: 'delegate', text: '委派任务：检查构建。', timestamp: Date.now(), sender: 'Nebula/nebflow', eventType: 'trigger' });
  await sleep(120);
  await inject(page, { type: 'thinking', sessionId: SID });
  for (let i = 0; i < 6; i++) {
    await inject(page, { type: 'thinkingDelta', sessionId: SID, delta: `思考步骤 ${i}：先检查再验证。\n` });
    await sleep(60);
  }
  for (let i = 0; i < 8; i++) {
    await inject(page, { type: 'textDelta', sessionId: SID, delta: `这是流式回复的第 ${i} 段，包含足够多的文字以撑高气泡内容区域，用来观察视口是否持续跟随贴底。\n\n` });
    await sleep(80);
  }
  await sleep(300);
  g = await geo(page);
  console.log('L1 after stream:', JSON.stringify(g));
  ok('L1 stream follows bottom (dist≤44)', g.dist <= 44, { dist: g.dist });

  // ── 腿 2：done 终态（finishAi 整泡渲染）+ 注入气泡（DELEGATE 卡同族）──
  await inject(page, { type: 'textDone', sessionId: SID, model: 'test-model', durationMs: 4200 });
  await sleep(300);
  g = await geo(page);
  console.log('L2 after textDone:', JSON.stringify(g));
  ok('L2 textDone follows bottom (dist≤44)', g.dist <= 44, { dist: g.dist });

  await inject(page, { type: 'user', sessionId: SID, injected: true, source: 'delegate', sender: 'Nebula/nebflow', eventType: 'completed', text: '子任务完成通知。', timestamp: Date.now(), header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED' });
  await sleep(300);
  g = await geo(page);
  console.log('L3 after injected completed card:', JSON.stringify(g));
  ok('L3 injected card follows bottom (dist≤44)', g.dist <= 44, { dist: g.dist });

  // ── 腿 2c：真实轮次终态 done（finishAi + collapseTurn 收拢过程行）──
  await inject(page, { type: 'thinking', sessionId: SID });
  for (let i = 0; i < 4; i++) {
    await inject(page, { type: 'thinkingDelta', sessionId: SID, delta: `第二轮思考 ${i}。\n` });
    await sleep(50);
  }
  await inject(page, { type: 'textDelta', sessionId: SID, delta: `第二轮回答正文，同样足够长以观察 done 收拢时刻视口是否仍在贴底。\n\n` });
  await sleep(120);
  await inject(page, { type: 'textDone', sessionId: SID, model: 'test-model', durationMs: 3600 });
  await sleep(200);
  await inject(page, { type: 'done', sessionId: SID, model: 'test-model' });
  await sleep(300);
  g = await geo(page);
  console.log('L2c after full done:', JSON.stringify(g));
  ok('L2c done (collapseTurn) keeps bottom (dist≤44)', g.dist <= 44, { dist: g.dist });

  // ── 腿 3：用户上翻 ⇒ 不打扰 + 胶囊报数 ──
  await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = c.scrollHeight - c.clientHeight - 600; });
  await sleep(150);
  await inject(page, { type: 'user', sessionId: SID, injected: true, source: 'delegate', text: '又一条委派通知（用户正在上翻读历史）。', timestamp: Date.now(), sender: 'Nebula/nebflow' });
  await sleep(300);
  g = await geo(page);
  console.log('L4 scrolled-up leg:', JSON.stringify(g));
  ok('L4 scrolled-up: viewport NOT yanked (dist stays ~600)', g.dist > 300, { dist: g.dist });
  ok('L4 scrolled-up: pill shows count', g.pillHidden === false && /1/.test(g.pillText || ''), { pillHidden: g.pillHidden, pillText: g.pillText });

  if (SHOTS_DIR) {
    mkdirSync(SHOTS_DIR, { recursive: true });
    await page.screenshot({ path: join(SHOTS_DIR, 'bottom.png') }).catch(() => {});
  }

  console.log('DONE exitCode=', process.exitCode || 0);
} finally {
  await browser.close();
}
