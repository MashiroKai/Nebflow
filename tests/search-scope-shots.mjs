// nf-search-scope-shots.mjs — 会话搜索范围收窄批（2026-11-01）实拍脚本。
//
// 双引擎 × 两个关键词 = 4 帧：
//   engine-index  = 服务端索引引擎（GET /api/search/chat 真响应体）
//   engine-legacy = 旧全扫引擎（把 /api/search/chat 打成 503 ⇒ 前端回落
//                   fetchSessionList + 逐会话 history —— 与改前逐字同一路径）
//   kw-bg   = 只出现在后台 agent 会话里的词 ⇒ 必须零结果
//   kw-main = 只出现在主会话里的词 ⇒ 必须命中
//
// 数据来源 = SearchScopeRoutesSpec E1 从**真路由栈**导出的响应体
// （evidence/search-scope/api/*.json），不是手写夹具；静态资源 = 仓内 web/ 真文件。
//
// 跑法（先跑 sbt "testOnly nebflow.gateway.SearchScopeRoutesSpec" 生成 api/ 真响应体）：
//   node tests/search-scope-shots.mjs
//   PLAYWRIGHT_ROOT="<主仓>" node tests/search-scope-shots.mjs   # worktree 无 node_modules 时

import { createRequire } from 'node:module';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, extname, normalize, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
// playright 解析根：本仓 node_modules（worktree 未装依赖时可用 PLAYWRIGHT_ROOT 指主仓）
const require = createRequire(join(process.env.PLAYWRIGHT_ROOT || REPO, 'package.json'));
const { chromium } = require('@playwright/test');

const WEB = join(REPO, 'src', 'main', 'resources', 'web');
const EV = join(REPO, 'evidence', 'search-scope');
const API = join(EV, 'api');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const read = (f) => JSON.parse(readFileSync(join(API, f), 'utf8'));
const sessionsSearchScope = read('sessions-search-scope.json');
const historyMain = read('history-main.json');
const searchMain = read('search-main.json');
const searchBg = read('search-bg.json');
const MAIN_SID = historyMain.sessionId;
const MAIN_KW = '主会专属词灯塔';
const BG_KW = '后台专属词流星';

mkdirSync(EV, { recursive: true });

async function boot(page, legacy) {
  await page.setViewportSize({ width: 1400, height: 900 });
  await page.addInitScript(() => {
    localStorage.setItem('stand_token', 'e2e-token');
    localStorage.setItem('stand_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';

    if (p === '/api/search/chat') {
      if (legacy) {
        // 强制旧全扫：索引面不可用（与索引未就绪时的真实降级同形）
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: '{"error":"search-index unavailable","reason":"forced-legacy(evidence)"}' });
      }
      const q = url.searchParams.get('q') || '';
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(q.includes(MAIN_KW) ? searchMain : searchBg) });
    }
    if (p.startsWith('/api/sessions/') && p.endsWith('/history')) {
      const sid = p.split('/')[3];
      const body = sid === MAIN_SID ? historyMain
        : { sessionId: sid, messages: [], total: 0 };
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    }
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sessionsSearchScope) });
    }
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200,
        contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: sessionsSearchScope.sessions,
      folders: sessionsSearchScope.folders || [], activeId: MAIN_SID,
    }));
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
    sendConfig();
    sendSessions();
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, MAIN_SID, { timeout: 20000 });
  await page.waitForTimeout(300);
}

async function shoot(page, legacy, kw, out, expectHits) {
  await boot(page, legacy);
  await page.locator('#search-btn').click();
  await page.waitForFunction(() => document.getElementById('search-overlay')?.classList.contains('on'),
    undefined, { timeout: 10000 });
  await page.waitForTimeout(400);
  // 范围 = 全部会话（= 跨会话检索面，作者报障的那一档；'current' 档只搜当前会话，
  // 对本批判据无意义）。收窄后这一档的清单里只剩主会话。
  await page.locator('#search-scope').selectOption('all');
  await page.locator('#search-keyword').fill(kw);
  if (expectHits) {
    await page.waitForFunction(
      () => document.querySelectorAll('#search-results .search-result').length > 0,
      undefined, { timeout: 15000 });
  } else {
    await page.waitForFunction(() => {
      const hint = document.querySelector('#search-results .search-hint');
      return !!hint && hint.textContent.includes('无匹配结果');
    }, undefined, { timeout: 15000 });
  }
  await page.waitForTimeout(400);
  const rows = await page.locator('#search-results .search-result').count();
  const status = await page.locator('#search-status').textContent();
  await page.screenshot({ path: join(EV, out) });
  console.log(`[shot] ${out} rows=${rows} status=${JSON.stringify((status || '').trim())}`);
  return rows;
}

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({ colorScheme: 'dark', hasTouch: true });
  const page = await ctx.newPage();
  const bgIndex = await shoot(page, false, BG_KW, 'engine-index-bg-zero.png', false);
  const mainIndex = await shoot(page, false, MAIN_KW, 'engine-index-main-hit.png', true);
  const bgLegacy = await shoot(page, true, BG_KW, 'engine-legacy-bg-zero.png', false);
  const mainLegacy = await shoot(page, true, MAIN_KW, 'engine-legacy-main-hit.png', true);
  const ok = bgIndex === 0 && mainIndex > 0 && bgLegacy === 0 && mainLegacy > 0;
  console.log(`[result] index(bg=${bgIndex},main=${mainIndex}) legacy(bg=${bgLegacy},main=${mainLegacy}) => ${ok ? 'PASS' : 'FAIL'}`);
  process.exitCode = ok ? 0 : 1;
} finally {
  await browser.close();
}
