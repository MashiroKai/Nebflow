// mobile-v3-shots.mjs — v3 实施批视觉自验截图（mobile-mockup-375 v3 落地对照）。
// 形态沿 activity-bar-shots.mjs 既有路线：静态 route 引真实 js/css/locale +
// routeWebSocket 注入应答帧，零后端。产物 = evidence 目录（由参数指定）。
//
// 用法：node tests/mobile-v3-shots.mjs <outDir>
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || '/tmp/mobile-v3-shots';
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const SID = 'smoke-session-1';

const browser = await chromium.launch();

async function makePage(colorScheme, { width = 375, height = 812 } = {}) {
  const page = await (await browser.newContext({ viewport: { width, height }, colorScheme, deviceScaleFactor: 2, hasTouch: width <= 768 })).newPage();
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'smoke-token'));
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
    // delegate 链夹具经 stub 注入（tests 内 fixture seam 走 REST 的走 API mock 即可）。
  });
  await page.goto('http://localhost:1/');
  await page.waitForTimeout(2200);
  return page;
}

/** delegate 链夹具（经 stub __setFixture，tasklist-chain-blocks.spec 同一夹具缝）：
 *  12 卡 = 运行 6（两条富成员链验成员预览 +N 溢出）+ 排队 3 + 已完成 3。 */
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
      row(65, '键位映射审计', 'running', 41),
      row(66, '皮肤 token 巡检', 'running', 58),
      row(67, 'wiki 图谱 UX', 'ready-to-merge', 95, { mergeQueuePos: { position: 1, total: 3, readyAt: now - 40_000, branch: 'delegate/wiki-ux', baseBranch: 'main' } }),
      row(68, '侧栏重构收尾', 'ready-to-merge', 120, { mergeQueuePos: { position: 2, total: 3, readyAt: now - 30_000, branch: 'delegate/sidebar-2', baseBranch: 'main' } }),
      row(69, '会话头居中', 'ready-to-merge', 150, { mergeQueuePos: { position: 3, total: 3, readyAt: now - 20_000, branch: 'delegate/head-center', baseBranch: 'main' } }),
      row(70, '附件 in-flow', 'completed', 190),
      row(71, '登录卡单键', 'failed', 240),
      row(72, '走廊退役', 'completed', 320),
    ];
    const chain = (taskNo, nodes) => ({
      taskId: String(taskNo), delegateId: `delegate-nebflow-t${taskNo}`,
      nodes, worktree: null, verdict: null, mergeQueue: [], frames: [],
    });
    const chains = [
      // 富成员：root + 4 步骤（成员预览 2 行 +「+2」溢出）；薄链/无链行照常成卡
      chain(61, [node('delegate-nebflow-t61', '#61', 'running'), node('s61a', '顶栏悬浮态', 'completed', ['delegate-nebflow-t61']), node('s61b', '液态下拉列', 'running', ['s61a']), node('s61c', '任务浮层六级卡', 'running', ['s61b']), node('s61d', 'profile 面板', 'pending', ['s61c'])]),
      chain(62, [node('delegate-nebflow-t62', '#62', 'running'), node('s62a', 'spec 骨架', 'completed', ['delegate-nebflow-t62']), node('s62b', '开合断言', 'running', ['s62a'])]),
      chain(63, [node('delegate-nebflow-t63', '#63', 'running'), node('s63a', '截图逐一对照', 'running', ['delegate-nebflow-t63'])]),
      chain(64, [node('delegate-nebflow-t64', '#64', 'running'), node('s64a', 'vite 对账', 'completed', ['delegate-nebflow-t64']), node('s64b', '缓存键核验', 'completed', ['s64a']), node('s64c', '产物 diff', 'running', ['s64b'])]),
    ];
    import('/js/delegateChainStub.js').then((stub) => {
      stub.__setFixture({ rows, chains, children: {} });
      window.dispatchEvent(new Event('delegate-chain-mirror-updated'));
    });
  });
  await page.waitForTimeout(200);
}

// ── 手机 375 ──
for (const theme of ['dark', 'light']) {
  const page = await makePage(theme);
  await seedTasks(page);
  await page.screenshot({ path: join(OUT, `m375-${theme}-01-chat.png`) });

  // 左列液态下拉（展开态）
  await page.click('.mh-dd[data-dd="left"] .mh-dd-hd');
  await page.waitForTimeout(900);
  await page.screenshot({ path: join(OUT, `m375-${theme}-02-dd-open.png`) });

  // 任务浮层（二级液态）
  await page.click('#mobile-task-key');
  await page.waitForTimeout(1100);
  await page.screenshot({ path: join(OUT, `m375-${theme}-03-taskpanel.png`) });
  await page.click('.mh-tpanel .tp-x');
  await page.waitForTimeout(700);

  // 搜索面板（同款二级液态）
  await page.click('.mh-dd[data-dd="left"] .mh-dd-hd');
  await page.waitForTimeout(700);
  await page.click('#search-btn');
  await page.waitForTimeout(1100);
  await page.screenshot({ path: join(OUT, `m375-${theme}-04-search-morph.png`) });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(400);

  // profile 面板（点头像）
  await page.click('#agent-avatar-btn');
  await page.waitForTimeout(1100);
  await page.screenshot({ path: join(OUT, `m375-${theme}-05-profile.png`) });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);

  // 右列下拉
  await page.click('.mh-dd[data-dd="right"] .mh-dd-hd');
  await page.waitForTimeout(900);
  await page.screenshot({ path: join(OUT, `m375-${theme}-06-dd-right.png`) });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);

  // 登录卡（v3④ 单键制 + lockup）：openLoginModal 公共入口（activityBar.js），
  // deferPopup=true（无用户手势不开弹窗 —— 面板自身路径）；idle 卡即样张形态。
  await page.evaluate(async () => {
    const m = await import('/js/activityBar.js');
    m.openLoginModal({ deferPopup: true });
  });
  await page.waitForTimeout(900);
  await page.screenshot({ path: join(OUT, `m375-${theme}-07-login-card.png`) });
  await page.close();
}

// ── 桌面 1440 会话头 ──
{
  const page = await makePage('dark', { width: 1440, height: 900 });
  await page.screenshot({ path: join(OUT, 'desk-dark-01-header.png'), clip: { x: 300, y: 0, width: 900, height: 260 } });
  await page.click('#agent-avatar-btn');
  await page.waitForTimeout(500);
  await page.screenshot({ path: join(OUT, 'desk-dark-02-profile.png') });
  await page.close();
}

await browser.close();
console.log('shots saved to ' + OUT);
