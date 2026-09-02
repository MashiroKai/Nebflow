// tmp-project-nav-verify.mjs — 子任务 D 自测：Project 面板 → Flow Map 导航三行为。
//   1. 点项目进入 Flow Map 不开新标签页（同一 projects 标签页就地切换）
//   2. Flow Map 视图左上角「返回项目列表」入口 → 回项目列表
//   3. 空 Flow Map（无节点）的项目不可点 / 无响应
// 自带静态服务器（web 目录）+ Playwright route 打桩 /api（不依赖后端、不碰 8080）。
// Run: node tests/tmp-project-nav-verify.mjs

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const WEB_DIR = join(import.meta.dirname, '..', 'src', 'main', 'resources', 'web');
const PORT = 8977;
const BASE = `http://127.0.0.1:${PORT}`;

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务器（仅 web 目录；/api 全部由 Playwright route 打桩）────────────
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.png': 'image/png', '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.jpg': 'image/jpeg',
};
const server = createServer(async (req, res) => {
  try {
    let p = decodeURIComponent((req.url || '/').split('?')[0]);
    if (p === '/') p = '/index.html';
    const file = normalize(join(WEB_DIR, p));
    if (!file.startsWith(normalize(WEB_DIR))) { res.writeHead(403); res.end(); return; }
    const data = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(data);
  } catch {
    res.writeHead(404);
    res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// ── 打桩数据 ──────────────────────────────────────────────────────────────
const now = Date.now();
const iso = (ms) => new Date(ms).toISOString();
const PROJECTS = {
  projects: [
    { name: 'alpha', workspace: '/Users/dev/work/alpha', agentFile: 'alpha', description: '有节点项目', createdAt: iso(now - 7 * 86400e3) },
    { name: 'beta', workspace: '/Users/dev/work/beta', agentFile: 'beta', description: '空 Flow Map 项目', createdAt: iso(now - 5 * 86400e3) },
  ],
};
const ALPHA_FM = {
  nodes: [
    { id: 'n1', name: '研究选题', agent: 'researcher', status: 'completed', in: [], out: 'n2', result: '选题已确定', ttlLeftSec: 300 },
    { id: 'n2', name: '撰写论文', agent: 'writer', status: 'running', in: ['n1'], out: 'Nebula', result: '' },
  ],
  worktrees: [],
  meta: { project: 'alpha', updatedAt: now },
};

// ── 浏览器 ────────────────────────────────────────────────────────────────
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't');
  localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('neblink_locale', 'zh-CN');
});
const page = await ctx.newPage();
page.on('pageerror', (e) => console.log('[pageerror]', e.message));

// 通用 /api 桩先注册（最后注册的优先，specific 覆盖 generic）
await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
await page.route('**/api/projects', (r) => r.fulfill({ json: PROJECTS }));
await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: ALPHA_FM }));
await page.route('**/api/projects/beta/flow-map', (r) => r.fulfill({ status: 404, json: { error: 'not mounted' } }));
// WS 桩：serverWs 供测试向客户端注入 WS 事件（nodeCreated 等触发视图刷新）
let serverWs = null;
await page.routeWebSocket(/\/ws/, (ws) => {
  serverWs = ws;
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
});

await page.goto(BASE + '/index.html');
await page.waitForSelector('#projects-btn', { state: 'attached', timeout: 10000 });
await sleep(600);

// 打开 Project 面板
await page.click('#projects-btn');
await page.waitForSelector('.project-card', { timeout: 10000 });
await sleep(300);

const cardCount = await page.$$eval('.project-card', (els) => els.length);
ok('项目列表渲染 2 个项目卡片', cardCount === 2, `count=${cardCount}`);

// 基线标签数（应用启动自带 teams 等面板标签，故用「点击前后对比」断言不开新标签）
const tabSnapshot = () => page.evaluate(() => ({
  count: document.querySelectorAll('.canvas-tab').length,
  ids: Array.from(document.querySelectorAll('.canvas-tab')).map((e) => e.dataset.tabId),
}));
const baselineTabs = await tabSnapshot();
const baselineTabCount = baselineTabs.count;
ok(`基线标签数 ${baselineTabCount}（启动自带面板标签，不含 flow-map）`,
  baselineTabs.ids.length > 0 && !baselineTabs.ids.some((id) => id.startsWith('flow-map-')),
  JSON.stringify(baselineTabs.ids));

// ── B3a: 空 Flow Map 项目（beta）不可点 ──────────────────────────────────
const betaTitle = await page.$('.project-card[data-project="beta"] .team-card-title');
const betaEmpty = await betaTitle.evaluate((el) => ({
  cls: el.className,
  hasOpenAttr: el.hasAttribute('data-open-flowmap'),
  tag: el.querySelector('.project-empty-tag')?.textContent || '',
}));
ok('B3a beta 卡片 title 无 data-open-flowmap（不可点）', betaEmpty.hasOpenAttr === false && betaEmpty.cls.includes('empty'), JSON.stringify(betaEmpty));
ok('B3a beta 卡片有「暂无节点」角标', betaEmpty.tag === '暂无节点', betaEmpty.tag);
const betaCard = await page.$('.project-card[data-project="beta"]');
const betaCardEmpty = await betaCard.evaluate((el) => el.hasAttribute('data-empty-flowmap'));
ok('B3b beta 卡片带 data-empty-flowmap="1"', betaCardEmpty);

const alphaTitle = await page.$('.project-card[data-project="alpha"] .team-card-title');
const alphaClickable = await alphaTitle.evaluate((el) => el.hasAttribute('data-open-flowmap'));
ok('B3c alpha（有节点）卡片 title 可点（有 data-open-flowmap）', alphaClickable);

// ── B3d: 点击空项目 → 无响应（无导航、无新标签、无视图切换）──────────────
await page.click('.project-card[data-project="beta"] .team-card-title');
await sleep(300);
const afterBetaClick = await page.evaluate(() => {
  const pane = document.querySelector('.canvas-tab-pane[data-tab-id="projects"]');
  return {
    tabs: document.querySelectorAll('.canvas-tab').length,
    tabIds: Array.from(document.querySelectorAll('.canvas-tab')).map((e) => e.dataset.tabId),
    projectsView: pane?.dataset.projectsView || null,
    hasFlowBody: !!pane?.querySelector('.flowmap-view-body'),
    activePane: document.querySelector('.canvas-tab-pane.active')?.dataset.tabId || null,
  };
});
ok('B3d 点击 beta 无响应：仍是列表视图、无 flow-map 视图、无新标签',
  afterBetaClick.tabs === baselineTabCount
    && !afterBetaClick.tabIds.some((id) => id.startsWith('flow-map-'))
    && afterBetaClick.projectsView === 'list' && !afterBetaClick.hasFlowBody
    && afterBetaClick.activePane === 'projects',
  JSON.stringify(afterBetaClick));
await page.screenshot({ path: '/tmp/project-nav-list-view.png' });

// ── B1: 点有节点的项目 → 同一标签页就地切换（不开新标签）────────────────
await page.click('.project-card[data-project="alpha"] .team-card-title');
await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 5000 });
await sleep(200);
const afterAlphaClick = await page.evaluate(() => {
  const pane = document.querySelector('.canvas-tab-pane[data-tab-id="projects"]');
  return {
    tabs: document.querySelectorAll('.canvas-tab').length,
    tabIds: Array.from(document.querySelectorAll('.canvas-tab')).map((e) => e.dataset.tabId),
    projectsView: pane?.dataset.projectsView || null,
    flowMapProject: pane?.dataset.flowMapProject || null,
    nodeCount: pane?.querySelectorAll('.flowmap-view-body .fm-node').length || 0,
    fmState: pane?.querySelector('.flowmap-view-body')?.dataset.fmState || null,
    hasBackBtn: !!pane?.querySelector('.flowmap-back-btn'),
    backBtnText: pane?.querySelector('.flowmap-back-btn span')?.textContent || '',
  };
});
ok('B1a 不新开标签页：标签数与基线相同、无 flow-map-* 新标签',
  afterAlphaClick.tabs === baselineTabCount
    && !afterAlphaClick.tabIds.some((id) => id.startsWith('flow-map-')), JSON.stringify(afterAlphaClick));
ok('B1b 就地视图：pane 仍为 projects、projectsView=flow-map',
  afterAlphaClick.projectsView === 'flow-map' && afterAlphaClick.flowMapProject === 'alpha', JSON.stringify(afterAlphaClick));
ok('B1c Flow Map 渲染 2 个节点卡片（fmState=nodes）',
  afterAlphaClick.nodeCount === 2 && afterAlphaClick.fmState === 'nodes', `nodes=${afterAlphaClick.nodeCount} state=${afterAlphaClick.fmState}`);
ok('B2a Flow Map 视图左上角有「返回项目列表」按钮',
  afterAlphaClick.hasBackBtn && afterAlphaClick.backBtnText === '返回项目列表', afterAlphaClick.backBtnText);

// ── TTL 倒计时在就地视图生效（flowMapTab ticker 找到 projects pane）────────
const ttlText = () => page.$eval('.flowmap-view-body [data-ttl-node]', (el) => el.textContent).catch(() => null);
const ttl0 = await ttlText();
await sleep(1300);
const ttl1 = await ttlText();
ok('TTL 就地视图倒计时运行（n1 300s → 递减）', ttl0 && ttl1 && ttl1 !== ttl0, `${ttl0} → ${ttl1}`);

// ── WS 事件驱动刷新：就地视图节点变更后重新拉取（不闪回列表）──────────────
const refreshFm = new Promise((resolve) => {
  page.route('**/api/projects/alpha/flow-map', async (r) => {
    // 刷新拉取返回 3 节点（新增 pending 节点）——模拟服务端节点变更（后注册的 route 优先）
    const fm = { nodes: [...ALPHA_FM.nodes, { id: 'n3', name: '新增节点', agent: 'qa', status: 'pending', in: ['n2'], out: 'Nebula' }], worktrees: [], meta: { project: 'alpha', updatedAt: Date.now() } };
    await r.fulfill({ json: fm });
    resolve();
  });
});
serverWs.send(JSON.stringify({ type: 'nodeCreated', project: 'alpha', nodeId: 'n3' }));
await refreshFm;
await sleep(500);
const afterWs = await page.evaluate(() => {
  const pane = document.querySelector('.canvas-tab-pane[data-tab-id="projects"]');
  return {
    projectsView: pane?.dataset.projectsView || null,
    nodeCount: pane?.querySelectorAll('.flowmap-view-body .fm-node').length || 0,
    hasNavBar: !!pane?.querySelector('.flowmap-nav-bar'),
  };
});
ok('WS 节点变更刷新就地视图：仍 flow-map 视图、3 节点、nav-bar 保留',
  afterWs.projectsView === 'flow-map' && afterWs.nodeCount === 3 && afterWs.hasNavBar, JSON.stringify(afterWs));

// ── B2b: 点返回按钮 → 回到项目列表（同标签页）───────────────────────────
await page.click('.flowmap-back-btn');
await page.waitForSelector('.project-card', { timeout: 5000 });
await sleep(200);
const afterBack = await page.evaluate(() => {
  const pane = document.querySelector('.canvas-tab-pane[data-tab-id="projects"]');
  return {
    tabs: document.querySelectorAll('.canvas-tab').length,
    projectsView: pane?.dataset.projectsView || null,
    flowMapProject: pane?.dataset.flowMapProject ?? null,
    hasFlowBody: !!pane?.querySelector('.flowmap-view-body'),
    hasNavBar: !!pane?.querySelector('.flowmap-nav-bar'),
    cardCount: pane?.querySelectorAll('.project-card').length || 0,
  };
});
ok('B2b 返回项目列表：projectsView=list、无 flow-map 骨架、卡片回来、标签数不变',
  afterBack.tabs === baselineTabCount && afterBack.projectsView === 'list' && afterBack.flowMapProject === null
    && !afterBack.hasFlowBody && !afterBack.hasNavBar && afterBack.cardCount === 2,
  JSON.stringify(afterBack));

// ── B1 回归：返回后再点 alpha → 再次同标签进入（可往返）──────────────────
await page.click('.project-card[data-project="alpha"] .team-card-title');
await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 5000 });
await sleep(200);
const again = await page.evaluate(() => ({
  tabs: document.querySelectorAll('.canvas-tab').length,
  projectsView: document.querySelector('.canvas-tab-pane[data-tab-id="projects"]')?.dataset.projectsView || null,
  nodeCount: document.querySelectorAll('.flowmap-view-body .fm-node').length,
}));
// WS 刷新测试后 flow-map 桩仍是 3 节点版 → 往返后应渲染 3 节点（验证服务端快照为权威）
ok('B1 往返：返回后再次点击 → 仍同标签进入、节点照常渲染',
  again.tabs === baselineTabCount && again.projectsView === 'flow-map' && again.nodeCount === 3, JSON.stringify(again));

await page.screenshot({ path: '/tmp/project-nav-flowmap-view.png' });

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
await browser.close();
server.close();
process.exit(failures === 0 ? 0 : 1);
