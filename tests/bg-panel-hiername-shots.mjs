// bg-panel-hiername-shots.mjs — 后台任务面板层级+命名+版式批（作者令 2026-10-31
// 11:51 截图令 + 11:52 追加令④ + 11:58 锚点）视觉证据。
//
// 四件：
//   ① 层级显形 … delegate 组头行首 caret 折叠子行树（默认展开进行中父/终态收起）
//   ② 行首短名 … Delegate 工具 name 参数（≤12 字）行首扫读，标题降次行；无名
//      旧数据回退标题截断；弹窗头名不再显「nebflow」
//   ③ 一屏装完 … 禁横向滚动（overflow-x hidden + 行矩形不出列表右缘），桌面+375
//   ④ 桌面拆两面板 … 任务键只显任务节 / 智能体键只显智能体节；手机端合并不变
//
// 用法：
//   node tests/bg-panel-hiername-shots.mjs                    # 修后（本工作树 web 面）
//
// 产出 = evidence/<批目录>/shots/{desktop-agents,desktop-tasks,mobile-375}-{light,dark}.png
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.WEB_DIR
  ? process.env.WEB_DIR
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261031_bg-panel-hier-name-split__delegate-nebflow-4cbce603', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'bphn-root-session';
const T0 = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

const LONG_TITLE = '液体玻璃实现批·收尾续作（代码已合 main，回归 + 双端截图验证 + 报告落 docs）';
const LONG_TOOL = 'Grep("object DelegateBudget") (path="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-4cbce603/src")';

async function makePage(browser, colorScheme, mobile) {
  const page = await (await browser.newContext({
    viewport: mobile ? { width: 375, height: 812 } : { width: 1280, height: 800 },
    colorScheme, deviceScaleFactor: 2,
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
    // 根 agent 定义拉取（/agents/<name>/agent.json）——harness 无此夹具，空 JSON 兜底
    // （不修则 boot 期 404 污染零 console 错误断言——面本身无回归）。
    if (p.startsWith('/agents/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    sendSessions();
    ws.send(JSON.stringify({ type: 'activeBgTasks', tasks: { [ROOT_SID]: [
      { taskId: 'job-1', description: 'sbt 全量回归（后台合入批）', status: 'running',
        startedAt: T0 - 45_000, sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
        heartbeat: { idleMs: 4_000, outputLines: 128 } },
      { taskId: 'job-2', description: 'playwright 双端截图取证', status: 'running',
        startedAt: T0 - 12_000, sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
        heartbeat: { idleMs: 1_000, outputLines: 42 } },
    ] } }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  return page;
}

/** 种作者病灶同款场景：命名批（进行中+子树）/无名批（标题回退）/终态批（默认收起）/孤儿。 */
async function seedScene(page) {
  await page.evaluate((fx) => import('/js/delegateChainStub.js').then((m) => { m.__setFixture(fx); }), {
    rows: [
      { delegateId: 'delegate-glass-1', taskNo: '7', title: LONG_TITLE, name: '液体玻璃批',
        executor: 'nebflow', status: 'running', project: '', createdAt: T0 },
      { delegateId: 'delegate-askmode-2', taskNo: '8',
        title: 'ask mode（文件提问）修复批（作者 10-30 令：附件键 + 锁行历史 + 回执归因）',
        executor: 'nebflow', status: 'running', project: '', createdAt: T0 },
      { delegateId: 'delegate-olddone-3', taskNo: '9', title: '深空图源批（已止损收尾）',
        executor: 'nebflow', status: 'completed', project: '', createdAt: T0 - 7200_000 },
    ],
    children: {
      'delegate-glass-1': [
        { taskId: 'subagent-recon-1', parentSessionId: 'delegate-glass-1', agentName: 'subagent',
          prompt: '', description: 'Recon desktop canvas chrome + viewport 三档截图', status: 'running',
          retryCount: 0, spawnedAt: T0 - 600_000, completedAt: null, lastError: '', source: 'subagent' },
        { taskId: 'subagent-recon-2', parentSessionId: 'delegate-glass-1', agentName: 'subagent',
          prompt: '', description: 'Recon mobile canvas page 结构与触控热区', status: 'completed',
          retryCount: 0, spawnedAt: T0 - 3000_000, completedAt: T0 - 600_000, lastError: '', source: 'subagent' },
      ],
      'delegate-askmode-2': [
        { taskId: 'subagent-survey-1', parentSessionId: 'delegate-askmode-2', agentName: 'subagent',
          prompt: '', description: 'Survey frontend + tests for delegate standby state machine', status: 'running',
          retryCount: 1, spawnedAt: T0 - 900_000, completedAt: null, lastError: '', source: 'subagent' },
      ],
      'delegate-olddone-3': [
        { taskId: 'subagent-old-1', parentSessionId: 'delegate-olddone-3', agentName: 'subagent',
          prompt: '', description: '旧批次的收尾子任务', status: 'completed',
          retryCount: 0, spawnedAt: T0 - 7000_000, completedAt: T0 - 7100_000, lastError: '', source: 'subagent' },
      ],
    },
  });
  await page.evaluate(({ sid, t0, LONG_TITLE_MARK, LONG_TOOL_MARK }) => import('/js/state.js').then((m) => {
    m.default.sessionBgAgents[sid] = {
      'delegate-glass-1': { name: 'nebflow', kind: 'Delegate', sessionId: 'delegate-glass-1',
        status: 'Processing', startedAt: t0 - 1800_000, task: LONG_TITLE_MARK,
        parentSessionId: sid, currentTool: LONG_TOOL_MARK },
      'delegate-askmode-2': { name: 'nebflow', kind: 'Delegate', sessionId: 'delegate-askmode-2',
        status: 'Processing', startedAt: t0 - 900_000,
        task: 'ask mode（文件提问）修复批（作者 10-30 令）', parentSessionId: sid },
      'delegate-olddone-3': { name: 'nebflow', kind: 'Delegate', sessionId: 'delegate-olddone-3',
        status: 'Idle', done: true, startedAt: t0 - 7200_000, task: '深空图源批', parentSessionId: sid },
    };
    m.default.updateBgAgentIndicator();
  }), { sid: ROOT_SID, t0: T0, LONG_TITLE_MARK: LONG_TITLE, LONG_TOOL_MARK: LONG_TOOL });
}

const noHScroll = async (page, label) => {
  const m = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    const lists = [...c.querySelectorAll('.bg-dropdown-list')];
    const vis = (el) => el.getClientRects().length > 0;
    let rectEscape = 0;
    for (const l of lists) {
      if (!vis(l)) continue;
      const lr = l.getBoundingClientRect();
      for (const el of l.querySelectorAll('.bg-task-row, .bg-task-row *')) {
        if (!vis(el)) continue;
        const r = el.getBoundingClientRect();
        if (r.width > 0 && r.right > lr.right + 1) rectEscape++;
      }
    }
    return {
      panelOverflowX: getComputedStyle(c).overflowX,
      listOverflowX: lists.map((l) => getComputedStyle(l).overflowX),
      rectEscape,
      panelRightBeyondViewport: c.getBoundingClientRect().right > window.innerWidth + 1,
    };
  });
  ok(`${label}: overflow-x 机制 hidden（panel=${m.panelOverflowX} lists=${m.listOverflowX.join('/')})`,
    m.panelOverflowX === 'hidden' && m.listOverflowX.every((v) => v === 'hidden'));
  ok(`${label}: 行/文本槽布局矩形不出列表右缘`, m.rectEscape === 0, `escape=${m.rectEscape}`);
  ok(`${label}: 面板右缘在视口内`, !m.panelRightBeyondViewport);
};

const browser = await chromium.launch();

// ── 桌面端：智能体单族视图（bgagent 键）──
for (const colorScheme of ['light', 'dark']) {
  const page = await makePage(browser, colorScheme, false);
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
  await seedScene(page);
  await page.click('#bgagent-indicator');
  await page.waitForTimeout(400);

  const checks = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    const list = c.querySelector('.bg-section-agents .bg-dropdown-list');
    const rows = [...list.querySelectorAll(':scope > .bg-task-row')];
    const headNamed = list.querySelector('[data-node-session-id="delegate-glass-1"]');
    const headUnnamed = list.querySelector('[data-node-session-id="delegate-askmode-2"]');
    const headDone = list.querySelector('[data-node-session-id="delegate-olddone-3"]');
    const carets = [...list.querySelectorAll('[data-bg-caret]')];
    return {
      viewClass: c.className,
      tasksSectionDisplay: getComputedStyle(c.querySelector('.bg-section-tasks')).display,
      groupLabel: c.querySelector('.mh-bg-grp')?.textContent || '',
      // 命名批：行首主名 = 短名（② 锚点）；标题降次行。
      namedName: headNamed?.querySelector('.bg-task-name')?.textContent || '',
      namedSub: headNamed?.querySelector('.bg-task-sub')?.textContent || '',
      namedIsNebflow: (headNamed?.querySelector('.bg-task-name')?.textContent || '') === 'nebflow',
      // 无名批：回退标题截断显示。
      unnamedName: headUnnamed?.querySelector('.bg-task-name')?.textContent || '',
      // 进行中父默认展开（子行在场）；终态父默认收起（子行缺席）+ caret 收起态。
      namedChildVisible: !!list.querySelector('[data-child-session="subagent-recon-1"]'),
      doneChildVisible: !!list.querySelector('[data-child-session="subagent-old-1"]'),
      doneCaretCollapsed: headDone?.querySelector('[data-bg-caret]')?.classList.contains('is-collapsed'),
      caretsCollapsed: carets.map((x) => x.classList.contains('is-collapsed')),
      // 树形缩进：子行左 padding > 组头。
      childPad: parseFloat(getComputedStyle(list.querySelector('.bg-task-row-child')).paddingLeft),
      headPad: parseFloat(getComputedStyle(headNamed).paddingLeft),
      doneGreyed: headDone ? getComputedStyle(headDone).opacity : '',
    };
  });
  ok(`desktop-agents(${colorScheme}): 智能体键 = 单族视图（任务节 display:none）`, checks.viewClass.includes('bg-view-agents') && checks.tasksSectionDisplay === 'none');
  ok(`desktop-agents(${colorScheme}): 行首短名「液体玻璃批」（非 nebflow），标题降次行`,
    checks.namedName === '液体玻璃批' && !checks.namedIsNebflow && checks.namedSub.includes('液体玻璃实现批'));
  ok(`desktop-agents(${colorScheme}): 无名批回退标题显示`, checks.unnamedName.includes('ask mode'));
  ok(`desktop-agents(${colorScheme}): 计数签带行数`, /智能体 · \d+/.test(checks.groupLabel));
  ok(`desktop-agents(${colorScheme}): 层级默认规（进行中展开/终态收起）`,
    checks.namedChildVisible === true && checks.doneChildVisible === false && checks.doneCaretCollapsed === true);
  ok(`desktop-agents(${colorScheme}): 子行缩进挂父下`, checks.childPad > checks.headPad);
  ok(`desktop-agents(${colorScheme}): 完成态灰化照旧`, parseFloat(checks.doneGreyed) < 1);
  await noHScroll(page, `desktop-agents(${colorScheme})`);
  ok(`desktop-agents(${colorScheme}): 零 console/pageerror`, errors.length === 0, errors.join(' | ').slice(0, 200));
  await page.screenshot({ path: join(OUT, `desktop-agents-${colorScheme}.png`) });
  console.log(`shot → ${join(OUT, `desktop-agents-${colorScheme}.png`)}`);

  // caret 收起/展开一拍（层级①行为证据）。
  await page.click('[data-bg-caret="delegate-glass-1"]');
  await page.waitForTimeout(200);
  const collapsed = await page.evaluate(() => {
    const list = document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list');
    return {
      childGone: !list.querySelector('[data-child-session="subagent-recon-1"]'),
      caretCollapsed: !!document.querySelector('[data-bg-caret="delegate-glass-1"].is-collapsed'),
    };
  });
  ok(`desktop-agents(${colorScheme}): caret 点击收起子行树`, collapsed.childGone && collapsed.caretCollapsed);
  await page.screenshot({ path: join(OUT, `desktop-agents-collapsed-${colorScheme}.png`) });
  await page.close();
}

// ── 桌面端：任务单族视图（bg 键）──
for (const colorScheme of ['light', 'dark']) {
  const page = await makePage(browser, colorScheme, false);
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
  await seedScene(page);
  await page.click('#bg-indicator');
  await page.waitForTimeout(400);
  const checks = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    return {
      viewClass: c.className,
      agentsSectionDisplay: getComputedStyle(c.querySelector('.bg-section-agents')).display,
      tasksHeader: c.querySelector('.bg-section-tasks .bg-dropdown-header')?.textContent || '',
      taskRowCount: c.querySelectorAll('.bg-section-tasks .bg-task-row').length,
    };
  });
  ok(`desktop-tasks(${colorScheme}): 任务键 = 单族视图（智能体节 display:none）`, checks.viewClass.includes('bg-view-tasks') && checks.agentsSectionDisplay === 'none');
  ok(`desktop-tasks(${colorScheme}): 只列 bg 任务行（${checks.tasksHeader}，${checks.taskRowCount} 行）`,
    checks.tasksHeader.includes('后台任务') && checks.taskRowCount === 2 && !checks.tasksHeader.includes('智能体'));
  await noHScroll(page, `desktop-tasks(${colorScheme})`);
  ok(`desktop-tasks(${colorScheme}): 零 console/pageerror`, errors.length === 0, errors.join(' | ').slice(0, 200));
  await page.screenshot({ path: join(OUT, `desktop-tasks-${colorScheme}.png`) });
  console.log(`shot → ${join(OUT, `desktop-tasks-${colorScheme}.png`)}`);
  await page.close();
}

// ── 375 手机端：合并单面板（追加令④：维持不变）──
for (const colorScheme of ['dark']) {
  const page = await makePage(browser, colorScheme, true);
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
  await seedScene(page);
  await page.evaluate(() => document.getElementById('bgagent-indicator').click());
  await page.waitForTimeout(400);
  const checks = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    return {
      tasksSectionDisplay: getComputedStyle(c.querySelector('.bg-section-tasks')).display,
      agentsSectionDisplay: getComputedStyle(c.querySelector('.bg-section-agents')).display,
      namedName: c.querySelector('[data-node-session-id="delegate-glass-1"] .bg-task-name')?.textContent || '',
    };
  });
  ok(`mobile-375(${colorScheme}): 合并单面板不变（两节同屏）`,
    checks.tasksSectionDisplay !== 'none' && checks.agentsSectionDisplay !== 'none');
  ok(`mobile-375(${colorScheme}): 行首短名同款语义`, checks.namedName === '液体玻璃批');
  await noHScroll(page, `mobile-375(${colorScheme})`);
  ok(`mobile-375(${colorScheme}): 零 console/pageerror`, errors.length === 0, errors.join(' | ').slice(0, 200));
  await page.screenshot({ path: join(OUT, `mobile-375-${colorScheme}.png`) });
  console.log(`shot → ${join(OUT, `mobile-375-${colorScheme}.png`)}`);
  await page.close();
}

await browser.close();
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
