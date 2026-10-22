// tasklist-reconcile-shots.mjs — 重启对账批（2026-10-22）前后对照证据截图。
//
// 样本 = 今日真实重启孤儿（fc5da94a → 84a285e5 接续对 + 1cc53dfe 孤悬行 +
// 24232ae5 退避态僵尸行），两页同源记录、两代管线输出：
//   BEFORE = 旧管线（改前适配层推导）：打断行 failed → 被终态闸隐行；restarting
//            退避态行 → 回落 running（死会话冻结活态僵尸）；接续关系不可见。
//   AFTER  = 新管线（重启对账）：打断行灰态「已中断」+「重启打断」徽标；已接续
//            旧行折进接续者区块（「已由 #N 接续」灰腿，不双行并存）。
// 输出 evidence/20261022_tasklist-restart-reconcile/（暗档）。
// Run: node tests/tasklist-reconcile-shots.mjs
import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261022_tasklist-restart-reconcile');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 'reconcile-shots-session';
const T0 = Date.now();

// ── 今日真实重启孤儿（ delegates/ + subagent-tasks 盘面原文摘录）──────────────
const REAL = {
  oldInterrupted: {
    taskNo: '708', delegateId: 'delegate-nebflow-fc5da94a',
    title: '【重离子链修复+662 收口批】作者已拍板（2026-10-22）：①662 峰纯度 FAIL 处置=接受关闭…',
    executor: 'nebflow', external: false, project: 'stand', createdAt: T0 - 90 * 60_000,
  },
  continuation: {
    taskNo: '712', delegateId: 'delegate-nebflow-84a285e5',
    title: '【重离子链修复+662 收口批 · 断点续作】前会话（delegate-nebflow-fc5da94a）被引擎重启打断…',
    executor: 'nebflow', external: false, project: 'stand', createdAt: T0 - 55 * 60_000,
  },
  loneInterrupted: {
    taskNo: '706', delegateId: 'delegate-nebflow-1cc53dfe',
    title: '【【批报告派发与超时治理 · 三件】次批超时上限 4h→1h…',
    executor: 'nebflow', external: false, project: 'stand', createdAt: T0 - 150 * 60_000,
  },
  staleRestarting: {
    taskNo: '703', delegateId: 'delegate-nebflow-24232ae5',
    title: '【对话历史建库+检索 CLI（stand-recall）】…',
    executor: 'nebflow', external: false, project: 'stand', createdAt: T0 - 240 * 60_000,
  },
};

async function makePage(colorScheme) {
  const page = await (await browser.newContext({
    viewport: { width: 1180, height: 760 }, colorScheme, deviceScaleFactor: 2,
  })).newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
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
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  return page;
}

const browser = await chromium.launch();
mkdirSync(OUT, { recursive: true });

// ── BEFORE：旧管线输出（改前 rowFromRegistry 推导的面）────────────────────────
// fc5da94a/1cc53dfe：taskStatus=failed → 终态闸隐行（尸体系从面板消失或以失败语
// 义混入）；24232ae5：restarting 非终态 → 回落 running（死会话冻结活态僵尸行）；
// 接续关系（fc5da94a → 84a285e5）零可见面。
{
  const page = await makePage('dark');
  await page.evaluate(async ({ R, sid }) => {
    const stub = await import('/js/delegateChainStub.js');
    const { renderTaskList } = await import('/js/taskList.js');
    stub.__setFixture({
      rows: [
        // 接续者：running（标题即旧会话的断点续作简报——旧行文本观感的来源）。
        { ...R.continuation, status: 'running' },
        // 打断行两行：旧管线 = failed 终态 → 面板隐行（此处给 failed，闸会滤掉）。
        { ...R.oldInterrupted, status: 'failed' },
        { ...R.loneInterrupted, status: 'failed' },
        // 退避态僵尸：restarting → 非 ENGINE_TERMINAL → 回落 running（冻结活态）。
        { ...R.staleRestarting, status: 'running' },
      ],
      chains: [], children: {},
    });
    renderTaskList([], undefined, sid);
  }, { R: REAL, sid: SID });
  await page.waitForTimeout(400);
  const card = page.locator('#task-list .task-card');
  if (!await card.count()) {
    // 无卡 = 面板零行（改前终态闸把打断行全隐——旧观感的一部分），整页截，
    // 再把输入区上方的面板槽位裁出来。
    await page.screenshot({ path: join(OUT, 'before-panel-dark.png'), fullPage: false });
  } else {
    await card.screenshot({ path: join(OUT, 'before-panel-dark.png') });
  }
  await page.close();
}

// ── AFTER：新管线（重启对账）───────────────────────────────────────────────
// 打断行灰态显形（「已中断」+「重启打断」徽标）；已接续旧行折进接续者区块
// （「已由 #712 接续」灰腿）；退避态僵尸经 boot 对账翻 interrupte 灰行。
{
  const page = await makePage('dark');
  const flagAt = (mins) => T0 - mins * 60_000; // Node 侧预算（浏览器上下文无 T0）
  const afterRows = [
    // 接续者（活态）——其区块承接折叠腿。
    { ...REAL.continuation, status: 'running' },
    // 打断+已接续：折进接续者区块（foldedRowsOf），零独立区块。
    { ...REAL.oldInterrupted, status: 'interrupted', restartInterruptedAt: flagAt(88), continuedBy: REAL.continuation.delegateId },
    // 打断+未接续：独立灰行。
    { ...REAL.loneInterrupted, status: 'interrupted', restartInterruptedAt: flagAt(148), continuedBy: '' },
    // 退避态僵尸：boot 对账后 = interrupted 灰行（不再冻结 running）。
    { ...REAL.staleRestarting, status: 'interrupted', restartInterruptedAt: flagAt(238), continuedBy: '' },
  ];
  await page.evaluate(async ({ R, sid }) => {
    const stub = await import('/js/delegateChainStub.js');
    const { renderTaskList } = await import('/js/taskList.js');
    stub.__setFixture({ rows: R.rows, chains: [], children: {} });
    renderTaskList([], undefined, sid);
  }, { R: { rows: afterRows }, sid: SID });
  await page.waitForTimeout(400);
  const card = page.locator('#task-list .task-card');
  await card.screenshot({ path: join(OUT, 'after-panel-dark.png') });

  // 展开接续者区块（成员 + 折叠腿同框）。
  await page.locator('.task-chain-head[data-task-no="712"]').click();
  await page.waitForTimeout(350);
  await card.screenshot({ path: join(OUT, 'after-panel-dark-expanded.png') });

  // 全景审计面：打断卡灰态 + 顶徽标 + 接续关系 chip。
  await page.evaluate(async () => {
    const m = await import('/js/delegatePanoramaView.js');
    m.openDelegatePanorama('stand', {});
  });
  await page.waitForSelector('[data-view="delegate-panorama"] .dvp-nd', { timeout: 10000 });
  await page.waitForTimeout(500);
  // 打断卡单体（st-stop 灰态 + 顶徽标 + continued chip）。
  const stopCard = page.locator('[data-view="delegate-panorama"] .dvp-nd.st-stop').first();
  await stopCard.screenshot({ path: join(OUT, 'after-panorama-card-dark.png') });
  // 终态泳道同框：画布滚到最右再整幅截。
  await page.evaluate(() => {
    const pane = document.querySelector('[data-view="delegate-panorama"]');
    const scroller = pane && (pane.querySelector('.solar-canvas') || pane.querySelector('.canvas-tab-scroll') || pane);
    if (scroller) scroller.scrollLeft = scroller.scrollWidth;
    const world = pane && (pane.querySelector('.dvp-canvas') || pane.firstElementChild);
    if (world && world.scrollIntoView) world.scrollIntoView({ block: 'start', inline: 'end' });
  });
  await page.waitForTimeout(300);
  const pano = page.locator('[data-view="delegate-panorama"]').first();
  await pano.screenshot({ path: join(OUT, 'after-panorama-dark.png') });
  await page.close();
}

await browser.close();
console.log(`shots saved to ${OUT}`);
