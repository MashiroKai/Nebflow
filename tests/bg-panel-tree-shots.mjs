// bg-panel-tree-shots.mjs — 后台智能体面板三修批（2026-10-29 作者报障三件）视觉证据。
//
// 三件：① 派生树（delegate 组头一级，子行缩进 + 引导竖线，孤儿提升顶层带来源角标）
//       ② 行说明（泛名「subagent」行主名 = 任务摘要首行，实名行附摘要次行）
//       ③ 分区（后台任务 / 智能体 两节独立滚动；手机窄宽两节 = 两张内嵌卡）
//
// 用法：
//   node tests/bg-panel-tree-shots.mjs                       # 修后（本工作树 web 面）
//   WEB_DIR=<主检出>/src/main/resources/web MODE=before \
//     node tests/bg-panel-tree-shots.mjs                     # 修前（归档树复现退化形）
//
// 产出 = evidence/<批目录>/shots/{desktop,narrow}-{light,dark}-{before,after}.png
// 断言仅在 MODE=after 生效（修前形态按退化断言翻转——零噪声复现）。
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.WEB_DIR
  ? process.env.WEB_DIR
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const MODE = process.env.MODE === 'before' ? 'before' : 'after';
const OUT = join(HERE, '..', 'evidence', '20261029_bg-panel-tree-threeshot__delegate-nebflow-ca33e103', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'bpt-root-session';
const T0 = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

async function makePage(browser, colorScheme, narrow) {
  const page = await (await browser.newContext({
    viewport: narrow ? { width: 390, height: 844 } : { width: 1440, height: 900 },
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
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
    ws.send(JSON.stringify({ type: 'activeBgTasks', tasks: { [ROOT_SID]: [
      { taskId: 'job-boot', description: 'Boot isolated LIVE instance on port 8399', status: 'running', startedAt: T0 - 115 * 60_000, sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 4_000, outputLines: 313 } },
      { taskId: 'job-specs', description: 'Run mobile P8-P12 specs', status: 'running', startedAt: T0 - 52 * 60_000, sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 9_000, outputLines: 0 } },
    ] } }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (m.type === 'getSessions' || m.type === 'listSessions') ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  // G3 夹具（delegate 行 + 星型子代理镜像——store 认领源）。
  await page.evaluate((t0) => {
    import('/js/delegateChainStub.js').then((m) => m.__setFixture({
      rows: [
        { delegateId: 'delegate-lexicon-a1b2c3d4', taskNo: '42', title: '词条（术语）引用系统——第二批实施（md/html 硬约束闸）', executor: 'nebflow', status: 'running', project: '', createdAt: t0 },
        { delegateId: 'delegate-plugin-e5f6a7b8', taskNo: '43', title: '插件仓建库批（ops）——为每个插件补齐发布源', executor: 'nebflow', status: 'running', project: '', createdAt: t0 },
      ],
      children: {
        'delegate-lexicon-a1b2c3d4': [
          { taskId: 'subagent-store-1', parentSessionId: 'delegate-lexicon-a1b2c3d4', agentName: 'subagent', prompt: '', description: '只读侦察：盘点词条回退链的既有消费点', status: 'running', retryCount: 0, spawnedAt: t0, completedAt: null, lastError: '', source: 'subagent' },
        ],
        'delegate-plugin-e5f6a7b8': [],
      },
    }));
  }, T0);
  // live/快照行直种（含泛名行 + 孤儿行 + 父可见的 live 子行）。ROOT_SID 以参传
  // （evaluate 回调跑在页面上下文——Node 侧闭包变量不可见）。
  await page.evaluate(({ t0, sid }) => {
    import('/js/state.js').then((s) => {
      s.default.sessionBgAgents[sid] = {
        'delegate-lexicon-a1b2c3d4': { name: 'delegate-lexicon-a1b2c3d4', kind: 'Delegate', sessionId: 'delegate-lexicon-a1b2c3d4', status: 'Processing', startedAt: t0 - 63 * 60_000, parentSessionId: sid },
        'delegate-plugin-e5f6a7b8': { name: 'delegate-plugin-e5f6a7b8', kind: 'Delegate', sessionId: 'delegate-plugin-e5f6a7b8', status: 'Processing', startedAt: t0 - 41 * 60_000, parentSessionId: sid },
        'subagent-live-1': { name: 'subagent', kind: 'Subagent', sessionId: 'subagent-live-1', status: 'Processing', startedAt: t0 - 12 * 60_000, task: '只读侦察：核对 console 面板树形层级与说明断言', parentSessionId: 'delegate-lexicon-a1b2c3d4' },
        'subagent-orphan-1': { name: 'subagent', kind: 'Subagent', sessionId: 'subagent-orphan-1', status: 'Processing', startedAt: t0 - 9 * 60_000, task: '只读侦察：为分区截图准备素材清单与窗口宽度', parentSessionId: 'node-3fa1b2c4' },
        'subagent-orphan-2': { name: 'subagent', kind: 'Subagent', sessionId: 'subagent-orphan-2', status: 'Processing', startedAt: t0 - 6 * 60_000, task: '', parentSessionId: 'node-3fa1b2c4' },
        'delegate-nested-9c8d7e6f': { name: 'delegate-nested-9c8d7e6f', kind: 'Delegate', sessionId: 'delegate-nested-9c8d7e6f', status: 'Processing', startedAt: t0 - 4 * 60_000, task: '', parentSessionId: 'delegate-plugin-e5f6a7b8' },
      };
      s.default.updateBgAgentIndicator();
    });
  }, { t0: T0, sid: ROOT_SID });
  await page.waitForTimeout(400);
  return page;
}

const browser = await chromium.launch();

async function shoot(colorScheme, narrow) {
  const page = await makePage(browser, colorScheme, narrow);
  if (narrow) await page.evaluate(() => document.body.classList.add('mobile-layer'));
  // 开面 = hidden 直开（float dock DOM 不在本 harness）+ 显式触发两节渲染
  // （activeBgTasks 帧在 boot 竞态窗内到达时 updateBgTasksUI 可能尚未接线——
  //  spec 走「点键开面 = renderBgDropdown」路径无此形态；此处直呼渲染面补齐）。
  await page.evaluate(() => document.getElementById('bg-dropdown').classList.remove('hidden'));
  await page.evaluate(() => import('/js/state.js').then((m) => {
    const s = m.default;
    const v = s.getActiveView && s.getActiveView();
    if (s.updateBgTasksUI && v) s.updateBgTasksUI(v);
  }));
  await page.waitForTimeout(500);
  const tag = `${narrow ? 'narrow' : 'desktop'}-${colorScheme}-${MODE}`;
  // 元素级截图（面板 absolute 于 #main——canvas 开合会移动其右缘，clip 不可靠）。
  await page.locator('#bg-dropdown').screenshot({ path: join(OUT, `${tag}.png`) });

  if (MODE === 'after') {
    const audit = await page.evaluate(() => {
      const list = document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list');
      const rows = list ? [...list.querySelectorAll(':scope > .bg-task-row')] : [];
      const name = (r) => r.querySelector('.bg-task-name')?.textContent || '';
      const head = rows.find((r) => r.classList.contains('bg-task-row-head'));
      const child = rows.find((r) => r.classList.contains('bg-task-row-child'));
      const orphan = rows.find((r) => r.querySelector('.bg-task-orphan'));
      return {
        sections: document.querySelectorAll('#bg-dropdown > .bg-section').length,
        headIndent: head ? parseFloat(getComputedStyle(head).paddingLeft) : -1,
        childIndent: child ? parseFloat(getComputedStyle(child).paddingLeft) : -1,
        childGuide: child ? getComputedStyle(child).borderLeftWidth : '0px',
        orphanChip: orphan ? orphan.querySelector('.bg-task-orphan')?.textContent || '' : '',
        hollow: rows.some((r) => name(r) === 'subagent'),
        names: rows.map(name).slice(0, 8),
      };
    });
    ok(`sections=2 (${tag})`, audit.sections === 2, `got ${audit.sections}`);
    ok(`child indent > head (${tag})`, audit.childIndent > audit.headIndent, `${audit.childIndent} > ${audit.headIndent}`);
    ok(`child guide line (${tag})`, parseFloat(audit.childGuide) >= 2, audit.childGuide);
    ok(`orphan source chip (${tag})`, audit.orphanChip.includes('AgentFlow'), audit.orphanChip);
    ok(`no hollow "subagent" (${tag})`, !audit.hollow, audit.names.join(' | '));
  }
  await page.close();
}

await shoot('light', false);
await shoot('dark', false);
await shoot('light', true);
await shoot('dark', true);

await browser.close();
console.log(`shots + audit done (${MODE}) → ${OUT}`);
process.exit(failures ? 1 : 0);
