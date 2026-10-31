// mobile-taskpanel-active-only-shots.mjs — 375 视口任务面板「活跃过滤批」验证截图。
// 形态沿 mobile-v3-shots.mjs 既有路线：静态 route 引真实 js/css/locale +
// routeWebSocket 注入应答帧 + stub __setFixture 夹具，零后端零 sbt。
// 夹具含 running / ready-to-merge / interrupted / completed / failed 五行族——
// 手机端面板应只出 运行中+排队 两组（interrupted 灰点在运行组内），
// completed/failed 零进列表；桌面 1440 面板（#task-list）现役窗口径不变。
//
// 用法：node tests/mobile-taskpanel-active-only-shots.mjs <outDir>
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || '/tmp/mobile-taskpanel-active-only-shots';
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const SID = 'tpao-smoke-session';

const browser = await chromium.launch();

async function makePage({ width, height, colorScheme, locale = 'zh-CN' }) {
  const page = await (await browser.newContext({ viewport: { width, height }, colorScheme, deviceScaleFactor: 2, hasTouch: width <= 768 })).newPage();
  await page.addInitScript((loc) => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', loc);
  }, locale);
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Smoke', agentName: 'Nebula' }], activeId: SID }) });
    if (p.includes('/agents/Nebula/agent.json')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: '笨笨' }) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '笨笨的会话', agentName: 'Nebula' }], folders: [], activeId: SID }));
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  return page;
}

/** 夹具：运行 4（#61 富成员链）+ 排队 1 + 中断 1 + 终态 2（completed/failed——过滤反证）。 */
async function seedTasks(page) {
  await page.evaluate(() => {
    const now = Date.now();
    const row = (taskNo, title, status, minsAgo, extra = {}) => ({
      taskNo: String(taskNo), delegateId: `delegate-nebflow-t${taskNo}`, title,
      executor: 'nebflow', status, external: false, project: 'Nebflow',
      createdAt: now - minsAgo * 60_000, orchestrationMode: '', archivedAt: null, ...extra,
    });
    const node = (id, label, status, deps = []) => ({ id, kind: deps.length ? 'flow-step' : 'delegate', label, description: '', status, deps, executor: '' });
    const rows = [
      row(61, '移动端 v3 落地', 'running', 3),
      row(62, '规格对齐回归', 'running', 9),
      row(63, '证据链补全', 'running', 17),
      row(64, '构建脚本梳理', 'running', 26),
      row(67, 'wiki 图谱 UX', 'ready-to-merge', 95, { mergeQueuePos: { position: 1, total: 1, readyAt: now - 40_000, branch: 'delegate/wiki-ux', baseBranch: 'main' } }),
      row(70, '附件 in-flow', 'completed', 190),
      row(71, '登录卡单键', 'failed', 240),
      row(72, '走廊退役', 'interrupted', 320),
    ];
    const chains = [
      chain(61, [node('delegate-nebflow-t61', '#61', 'running'), node('s61a', '顶栏悬浮态', 'completed', ['delegate-nebflow-t61']), node('s61b', '液态下拉列', 'running', ['s61a']), node('s61c', '任务浮层六级卡', 'running', ['s61b']), node('s61d', 'profile 面板', 'pending', ['s61c'])]),
      chain(62, [node('delegate-nebflow-t62', '#62', 'running'), node('s62a', 'spec 骨架', 'completed', ['delegate-nebflow-t62']), node('s62b', '开合断言', 'running', ['s62a'])]),
    ];
    function chain(taskNo, nodes) {
      return { taskId: String(taskNo), delegateId: `delegate-nebflow-t${taskNo}`, nodes, worktree: null, verdict: null, mergeQueue: [], frames: [] };
    }
    import('/js/delegateChainStub.js').then((stub) => {
      stub.__setFixture({ rows, chains, children: {} });
      window.dispatchEvent(new Event('delegate-chain-mirror-updated'));
    });
  });
  await page.waitForTimeout(200);
}

/** 读数钉：分组标签 / 卡数 / 终态行缺席 / interrupted 灰点。 */
async function probe(page, sel) {
  return page.evaluate((root) => {
    const panel = document.querySelector(root);
    if (!panel) return { error: 'panel not found' };
    return {
      stats: panel.querySelector('.tp-stats')?.textContent || null,
      groups: [...panel.querySelectorAll('.tp-grp')].map((el) => el.textContent),
      cards: [...panel.querySelectorAll('.tp-card .tp-ttl')].map((el) => el.textContent),
      runningCards: panel.querySelectorAll('.tp-card.tp-running').length,
      queuedCards: panel.querySelectorAll('.tp-card.tp-queued').length,
      doneCards: panel.querySelectorAll('.tp-card.tp-done').length,
      stopGlyphs: panel.querySelectorAll('.tp-card .tp-g-stop').length,
    };
  }, sel);
}

// ── 手机 375：任务页（dock 批形态——聊天头任务键 push 页）──
for (const theme of ['dark', 'light']) {
  const page = await makePage({ width: 375, height: 812, colorScheme: theme });
  await seedTasks(page);
  await page.click('.mh-chrome-btn[data-chrome="tasks"]');
  await page.waitForTimeout(600);
  const probeData = await probe(page, '.mobile-page[data-page="tasks"] #mobile-task-panel');
  console.log(`[m375 ${theme}]`, JSON.stringify(probeData, null, 2));
  await page.screenshot({ path: join(OUT, `m375-${theme}-taskpanel.png`) });
  await page.close();
}

// ── 桌面 1440：现役窗口径不变（running + ready-to-merge 在列；终态退场）──
{
  const page = await makePage({ width: 1440, height: 900, colorScheme: 'dark' });
  await seedTasks(page);
  await page.evaluate(async () => {
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, 'tpao-smoke-session');
  });
  await page.waitForTimeout(400);
  const data = await page.evaluate(() => ({
    heads: [...document.querySelectorAll('#task-list .task-chain-head')].map((el) => el.dataset.taskNo),
    text: document.querySelector('#task-list')?.textContent || '',
  }));
  console.log('[desk1440]', JSON.stringify(data, null, 2));
  await page.screenshot({ path: join(OUT, 'desk1440-tasklist.png') });
  await page.close();
}

await browser.close();
console.log('shots saved to ' + OUT);
