// remove-context-usage-shots.mjs — 上下文用量显示退役批 亮/暗取证截图。
// 场景 = 真实 boot（静态路由 + 假后端 + 真实 ws.js 分发）+ 注入 usageUpdate
// 用量帧（数据在飞）⇒ 头部无用量环。亮暗各一张，存 evidence/。
// Run: node tests/remove-context-usage-shots.mjs

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const TS = process.env.SHOT_TS || new Date().toISOString().replace(/[-:T]/g, '').slice(0, 15);
const OUT = join(HERE, '..', 'evidence', `${TS}_remove-context-usage__delegate-nebflow-8b4f04e9`);
const PORT = Number(process.env.PORT || 8190);
const SID = 'cas-root-session';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const browser = await chromium.launch({ headless: process.env.HEADLESS !== '0' });
mkdirSync(OUT, { recursive: true });

for (const scheme of ['light', 'dark']) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme: scheme });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'shot-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
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
  // 用量帧在飞（数据管道活着）——环显示面不得出现。
  await page.evaluate(async (sid) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify({ type: 'usageUpdate', sessionId: sid, inputTokens: 82000, contextWindow: 128000, outputTokens: 4200, model: 'claude-sonnet', compactThreshold: 0.8 }) });
  }, SID);
  await page.waitForTimeout(500);
  const out = join(OUT, `after-${scheme}-no-ring.png`);
  await page.locator('#main').screenshot({ path: out });
  const ringGone = await page.evaluate(() => !document.getElementById('header-model-info') && document.querySelectorAll('.ctx-ring-wrap').length === 0);
  console.log(`${scheme}: shot=${out} ringGone=${ringGone}`);
  await page.close();
}
await browser.close();
