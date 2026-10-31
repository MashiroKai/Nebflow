#!/usr/bin/env node
// bg-badge-homecoming-shots.mjs — 后台智能体面板徽标归位批（作者 10-31 23:02 令 +
// 实拍截图）双端视觉证据。
//
// 裁定口径（逐字）：
//   · 顶层行只标一个徽标 = delegate 模式（kernel 或 dispatcher，即 delegate:type），
//     不再出现 Delegate/Subagent 字样混在顶层；
//   · 子级行（下钻展开的下一层）才标 subagent 或 agentflow（视其实际类型）。
//
// 样张矩阵（顶层三种形态 × 子层两族词）：
//   ① kernel 批（row.mode='kernel'）——顶层徽标 kernel；子层 store subagent 行
//      （Subagent）+ live 子行；
//   ② dispatcher 批（row.mode='dispatcher'）——顶层徽标 dispatcher；子层 agentflow
//      flow-step 行（AgentFlow）；
//   ③ 旧盘批（mode 缺席 + orchestrationMode='subagent-star' 历史残留）——顶层
//      **无徽标**（绝不回退 Subagent——乱象根因验红样张）。
// 双端 = 桌面 1280（智能体单族视图）+ 手机 375（合并单面板），dark/light。
//
// 用法：node tests/bg-badge-homecoming-shots.mjs
// 产出 = evidence/20261031_bg-badge-homecoming__delegate-nebflow-7f69c44f/shots/*.png
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261031_bg-badge-homecoming__delegate-nebflow-7f69c44f', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'bbh-root-session';
const T0 = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

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

/** 种三形态顶层批 + 两族子层行（裁定口径样张矩阵）。 */
async function seedScene(page) {
  await page.evaluate((fx) => import('/js/delegateChainStub.js').then((m) => { m.__setFixture(fx); }), {
    rows: [
      { delegateId: 'delegate-bbh-kernel', taskNo: '11', title: '构建产物清单批（极简内核形态）', name: '构建清单批',
        executor: 'nebflow', status: 'running', project: '', createdAt: T0, mode: 'kernel',
        orchestrationMode: 'subagent-star' },
      { delegateId: 'delegate-bbh-dispatch', taskNo: '12', title: '三腿并行实现批（分发器形态）', name: '并行实现批',
        executor: 'nebflow', status: 'running', project: '', createdAt: T0, mode: 'dispatcher',
        orchestrationMode: 'agentflow' },
      { delegateId: 'delegate-bbh-legacy', taskNo: '13', title: '旧盘星型批次（双模式闸关存量）',
        executor: 'nebflow', status: 'running', project: '', createdAt: T0, mode: '',
        orchestrationMode: 'subagent-star' },
    ],
    children: {
      'delegate-bbh-kernel': [
        { taskId: 'subagent-bbh-recon', agentName: 'scout', description: '只读侦察：核对构建产物清单',
          status: 'running', source: 'subagent', spawnedAt: T0 - 120_000 },
      ],
    },
    chains: [{
      taskId: '12', delegateId: 'delegate-bbh-dispatch',
      nodes: [
        { id: 'delegate-bbh-dispatch', kind: 'delegate', label: '#12', description: '', status: 'running', deps: [], executor: '' },
        { id: 'bbh-step-1', kind: 'flow-step', label: '前端腿', description: '', status: 'running', deps: [], executor: '' },
        { id: 'bbh-step-2', kind: 'flow-step', label: '后端腿', description: '', status: 'running', deps: [], executor: '' },
      ],
      worktree: null, verdict: null, mergeQueue: [], frames: [],
    }],
  });
  await page.evaluate(({ sid }) => import('/js/state.js').then((m) => {
    m.default.sessionBgAgents[sid] = {
      'delegate-bbh-kernel': { name: '构建清单批', kind: 'SubTask', sessionId: 'delegate-bbh-kernel',
        status: 'Processing', startedAt: Date.now() - 600_000, task: '构建产物清单批', parentSessionId: sid },
      'delegate-bbh-dispatch': { name: '并行实现批', kind: 'SubTask', sessionId: 'delegate-bbh-dispatch',
        status: 'Processing', startedAt: Date.now() - 300_000, task: '三腿并行实现批', parentSessionId: sid },
      'delegate-bbh-legacy': { name: 'nebflow', kind: 'SubTask', sessionId: 'delegate-bbh-legacy',
        status: 'Processing', startedAt: Date.now() - 60_000, task: '旧盘星型批次', parentSessionId: sid },
    };
    m.default.updateBgAgentIndicator();
  }), { sid: ROOT_SID });
}

/** 裁定口径审计：顶层徽标 ∈ {kernel, dispatcher, ''} 且绝无 Delegate/Subagent；
 *  子层徽标 ∈ {Subagent, AgentFlow, …}（视实际类型）。 */
async function auditAndShoot(page, label, file) {
  await page.waitForTimeout(400);
  const a = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    const list = c.querySelector('.bg-section-agents .bg-dropdown-list');
    const heads = [...list.querySelectorAll(':scope > .bg-task-row.bg-task-row-head')];
    const kids = [...list.querySelectorAll(':scope > .bg-task-row.bg-task-row-child')];
    const word = (row) => row.querySelector('.bg-task-kind')?.textContent || '';
    return {
      headWords: heads.map((h) => `${h.getAttribute('data-node-session-id')}=${word(h)}`),
      kidWords: kids.map((k) => word(k)),
      topForbidden: [...list.querySelectorAll(':scope > .bg-task-row:not(.bg-task-row-child) .bg-task-kind')]
        .map((el) => el.textContent)
        .filter((w) => w === 'Delegate' || w === 'Subagent'),
    };
  });
  ok(`${label}: 顶层无 Delegate/Subagent 字样`, a.topForbidden.length === 0, a.topForbidden.join(',') || 'clean');
  const kernelHead = a.headWords.find((w) => w.startsWith('delegate-bbh-kernel=')) || '';
  const dispHead = a.headWords.find((w) => w.startsWith('delegate-bbh-dispatch=')) || '';
  const legacyHead = a.headWords.find((w) => w.startsWith('delegate-bbh-legacy=')) || '';
  ok(`${label}: kernel 批顶层徽标 = kernel`, kernelHead === 'delegate-bbh-kernel=kernel', kernelHead);
  ok(`${label}: dispatcher 批顶层徽标 = dispatcher`, dispHead === 'delegate-bbh-dispatch=dispatcher', dispHead);
  ok(`${label}: 旧盘批（mode 缺席）顶层无徽标（不回退 Subagent）`, legacyHead === 'delegate-bbh-legacy=', legacyHead);
  ok(`${label}: 子层 subagent/agentflow 词在场`,
    a.kidWords.includes('Subagent') && a.kidWords.includes('AgentFlow'), a.kidWords.join(','));
  await page.screenshot({ path: file });
  console.log(`shot → ${file}`);
}

const browser = await chromium.launch();

// ── 桌面端（智能体单族视图，light + dark）──
for (const colorScheme of ['light', 'dark']) {
  const page = await makePage(browser, colorScheme, false);
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
  await seedScene(page);
  await page.click('#bgagent-indicator');
  await auditAndShoot(page, `desktop(${colorScheme})`, join(OUT, `desktop-${colorScheme}.png`));
  ok(`desktop(${colorScheme}): 零 console/pageerror`, errors.length === 0, errors.join(' | ').slice(0, 200));
  await page.close();
}

// ── 手机 375（合并单面板，dark）──
{
  const page = await makePage(browser, 'dark', true);
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
  await seedScene(page);
  await page.evaluate(() => document.getElementById('bgagent-indicator').click());
  await auditAndShoot(page, 'mobile-375(dark)', join(OUT, 'mobile-375-dark.png'));
  ok('mobile-375(dark): 零 console/pageerror', errors.length === 0, errors.join(' | ').slice(0, 200));
  await page.close();
}

await browser.close();
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
