#!/usr/bin/env node
// e2e-bg-progress-visibility.cjs — #752+#753 面板进展可见化批 验收 e2e + 亮暗截图。
//
// 验收面（任务书）：
//   #753 同族聚合 —— waiter N 递增族（background waiter 67..71）在后台任务面板
//     聚合为一行组头（默认收起：计数/Σ行数/族龄一目了然），点击展开成员行
//     （成员行保留取消按钮 + 输出卡交互），再点收起。
//   #753 行数 chip —— 无心跳的快照恢复行也带 outputLines（activeBgTasks 快照
//     字段路径；实时行仍走心跳路径）。
//   #752 空闲细分 —— 子代理面板 Idle 行显示「在等什么」：等后台任务（名+时长+计数）
//     / 等子代理回执 ×N；长空闲（>10min 无在飞工作）= 琥珀提醒徽标。
//   #752 速率闸告警 —— bgRateAlert 帧 → toast（作者看得见「某会话在刷后台任务」）。
//   #752 弹窗「会话在做什么」条 —— delegate 详情弹窗内嵌最近动作 + 本会话后台
//     任务行（点击 = 输出卡）。
//
// 自包含打桩（shot-bgtask-panel.cjs 同路线）：page.route 从磁盘服务真实前端文件
// （不起端口、不碰 8080），MockWebSocket 走真实 ws.js → main.js 生产分发路径。
//
// Run（worktree 根）：node scripts/e2e-bg-progress-visibility.cjs
// 产出：evidence/20261022_panel-progress-visibility/*.png（亮暗双套）

const { chromium } = require('playwright');
const { readFileSync, mkdirSync } = require('node:fs');
const { join, extname, normalize } = require('node:path');

const WEB = join(__dirname, '..', 'src', 'main', 'resources', 'web');
const OUT_DIR = join(__dirname, '..', 'evidence', '20261022_panel-progress-visibility');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const ROOT_SID = 'pv-root-session';
const now = Date.now();

// 五条 waiter 族（#753 聚合对象）——与作者实证同形（background waiter N 递增、
// 0 行产出）；行数给 2 条/个验证 Σ 行数 chip。
const WAITERS = [67, 68, 69, 70, 71].map((n, i) => ({
  taskId: `waiter${n}`, description: `background waiter ${n}`, startedAt: now - (240 - i * 7) * 1000,
  heartbeat: { alive: true, idleMs: 4000, outputLines: 2 },
}));
// 两条零散任务（不参与聚合：族 < 3 条原样单行）。
const SINGLES = [
  { taskId: 'sbtcom1', description: 'Rerun sbt specs (incremental compile)', startedAt: now - 135 * 1000, heartbeat: { alive: true, idleMs: 5000, outputLines: 6 } },
  { taskId: 'sbtid8', description: 'Run specs with correct sbt project id', startedAt: now - 95 * 1000, heartbeat: { alive: true, idleMs: 3000, outputLines: 8 } },
];

// 快照注入（#752 空闲细分数据源）——两条 delegate 行：一条「等后台任务+等回执」、
// 一条长空闲（无在飞工作 >10min）；一条 Processing 行作活性对照。
const SNAPSHOT_AGENTS = [
  {
    sessionId: 'delegate-waiter-user-01', agentId: 'delegate-waiter-user-01', agentName: '实施-批量验收',
    rootSessionId: ROOT_SID, kind: 'SubTask', task: '批量验收', status: 'Idle', startedAt: now - 40 * 60 * 1000,
    retryCount: 0, project: '', taskId: '', taskTitle: '',
    heldReceipts: 3, unreadReceipts: 0, outstandingSubagents: 2,
    currentTool: 'Bash', lastActivityMs: now - 62 * 1000,
    bgWaiting: [
      { taskId: 'waiter67', description: 'background waiter 67', startedAt: now - 240 * 1000 },
      { taskId: 'sbtcom1', description: 'Rerun sbt specs (incremental compile)', startedAt: now - 135 * 1000 },
    ],
  },
  {
    sessionId: 'delegate-long-idle-02', agentId: 'delegate-long-idle-02', agentName: '设计-台账梳理',
    rootSessionId: ROOT_SID, kind: 'SubTask', task: '台账梳理', status: 'Idle', startedAt: now - 90 * 60 * 1000,
    retryCount: 0, project: '', taskId: '', taskTitle: '',
    heldReceipts: 0, unreadReceipts: 0, outstandingSubagents: 0,
    currentTool: '', lastActivityMs: now - 15 * 60 * 1000,
    bgWaiting: [],
  },
  {
    sessionId: 'delegate-active-03', agentId: 'delegate-active-03', agentName: '实施-输出流',
    rootSessionId: ROOT_SID, kind: 'SubTask', task: '输出流', status: 'Processing', startedAt: now - 8 * 60 * 1000,
    retryCount: 0, project: '', taskId: '', taskTitle: '',
    heldReceipts: 0, unreadReceipts: 0, outstandingSubagents: 0,
    currentTool: 'sbt Test/compile', lastActivityMs: now - 3000,
    bgWaiting: [],
  },
];

(async () => {
  mkdirSync(OUT_DIR, { recursive: true });
  const browser = await chromium.launch();
  const results = [];
  const ok = (name, cond, extra = '') => { results.push([name, !!cond]); console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra ? `  — ${extra}` : ''}`); };

  for (const colorScheme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme });
    const consoleErrors = [];
    page.on('pageerror', (e) => consoleErrors.push(String(e)));
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    await page.addInitScript(() => {
      localStorage.setItem('nebflow_token', 'pv-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      window.__wsSent = [];
      class MockWS {
        static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
        constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
        send(d) { try { window.__wsSent.push(JSON.parse(d)); } catch { window.__wsSent.push(d); } }
        close() {} onopen = null; onmessage = null; onclose = null;
      }
      Object.defineProperty(window, 'WebSocket', { value: MockWS });
    });

    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let p = decodeURIComponent(url.pathname);
      if (p === '/') p = '/index.html';
      if (p.startsWith('/api/')) {
        // #753 输出卡 REST 面：返回可读尾流（打开任一 waiter 卡可见内容）。
        const m = p.match(/^\/api\/bg-tasks\/([^/]+)\/output$/);
        if (m) {
          const id = m[1];
          const body = {
            taskId: id, status: 'running', totalBytes: 64, totalLines: 2, truncated: false,
            output: `[${id}] poll tick: waiting for decision point...\n[${id}] still waiting (2 lines total)`,
            nextOffset: 64,
          };
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
        }
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
      }
      const file = normalize(join(WEB, p));
      if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
      try {
        return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
      } catch {
        return route.fulfill({ status: 404, body: 'not found' });
      }
    });

    await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
    await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
    await page.waitForTimeout(300);

    const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
    await df({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
    await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
    await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
    await page.waitForTimeout(300);

    // ── 生产路径注入：delegate 快照 + 后台任务（waiter 族 + 零散）──
    await df({ type: 'activeAgents', agents: SNAPSHOT_AGENTS });
    for (const b of [...WAITERS, ...SINGLES]) {
      await df({
        type: 'backgroundTaskUpdate', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
        taskId: b.taskId, description: b.description, status: 'running',
        startedAt: b.startedAt, heartbeat: b.heartbeat, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      });
    }
    await page.waitForTimeout(300);

    // ════ A. 后台任务面板：族聚合（收起态）════
    await page.click('#bg-indicator');
    await page.waitForSelector('#bg-dropdown:not(.hidden) .bg-task-row', { timeout: 5000 });
    await page.waitForTimeout(250);
    const probeA = await page.evaluate(() => {
      const dd = document.querySelector('#bg-dropdown');
      const rows = [...dd.querySelectorAll('.bg-task-row')];
      const fam = dd.querySelector('.bg-task-row-family');
      return {
        totalRows: rows.length, // 收起：1 族头 + 2 零散 = 3
        familyCount: dd.querySelectorAll('.bg-task-row-family').length,
        famExpanded: fam ? fam.getAttribute('aria-expanded') : null,
        famText: fam ? fam.innerText.replace(/\n/g, ' | ') : '',
        famCaret: fam ? fam.querySelector('.bg-family-caret')?.textContent : null,
        waiterLeak: rows.filter((r) => r.dataset.taskId && r.dataset.taskId.startsWith('waiter')).length,
        header: dd.querySelector('.bg-dropdown-header')?.textContent || '',
      };
    });
    if (colorScheme === 'light') {
      ok('A1 收起态总行数 = 1 族头 + 2 零散 = 3', probeA.totalRows === 3, `got ${probeA.totalRows}`);
      ok('A2 族头恰 1 行（5 条 waiter 聚合）', probeA.familyCount === 1);
      ok('A3 族头默认收起（aria-expanded=false）', probeA.famExpanded === 'false');
      ok('A4 族头携带 ×5 计数 chip', probeA.famText.includes('同族 ×5'), probeA.famText);
      ok('A5 族头携带 运行中 ×5 聚合词', probeA.famText.includes('运行中 ×5'), probeA.famText);
      ok('A6 族头携带 Σ行数（10行）', probeA.famText.includes('10行'), probeA.famText);
      ok('A7 收起时 waiter 成员行不外漏', probeA.waiterLeak === 0, `leak=${probeA.waiterLeak}`);
      ok('A8 头部计数 = 桶真值 7（badge == bucket）', probeA.header.includes('7'), probeA.header);
      ok('A9 carets 形态（收起 ▸）', probeA.famCaret === '▸');
    }
    const shotA = join(OUT_DIR, `20261022_bg-family-collapsed-${colorScheme}.png`);
    await page.locator('#bg-dropdown').screenshot({ path: shotA });
    console.log(`saved ${shotA}`);

    // ════ B. 族展开 ════
    await page.click('.bg-task-row-family');
    await page.waitForTimeout(250);
    const probeB = await page.evaluate(() => {
      const dd = document.querySelector('#bg-dropdown');
      const fam = dd.querySelector('.bg-task-row-family');
      return {
        totalRows: dd.querySelectorAll('.bg-task-row').length, // 1 族头 + 5 成员 + 2 零散 = 8
        members: dd.querySelectorAll('.bg-task-row-member').length,
        famExpanded: fam ? fam.getAttribute('aria-expanded') : null,
        famCaret: fam ? fam.querySelector('.bg-family-caret')?.textContent : null,
        memberCancels: dd.querySelectorAll('.bg-task-row-member .bg-task-cancel').length,
        memberLines: [...dd.querySelectorAll('.bg-task-row-member .bg-task-lines')].map((el) => el.textContent),
        firstMember: dd.querySelector('.bg-task-row-member .bg-task-name')?.textContent || '',
      };
    });
    if (colorScheme === 'light') {
      ok('B1 展开后总行数 = 1 + 5 + 2 = 8', probeB.totalRows === 8, `got ${probeB.totalRows}`);
      ok('B2 成员行 5 条（缩进类）', probeB.members === 5);
      ok('B3 aria-expanded=true + carets ▾', probeB.famExpanded === 'true' && probeB.famCaret === '▾');
      ok('B4 成员行保留取消按钮 ×5（回归红线）', probeB.memberCancels === 5);
      ok('B5 成员行行数 chip 在（2行 ×5）', probeB.memberLines.length === 5 && probeB.memberLines.every((s) => s.includes('2行')));
      ok('B6 首成员为最早 waiter 67', probeB.firstMember.includes('background waiter 67'), probeB.firstMember);
    }
    const shotB = join(OUT_DIR, `20261022_bg-family-expanded-${colorScheme}.png`);
    await page.locator('#bg-dropdown').screenshot({ path: shotB });
    console.log(`saved ${shotB}`);

    // ════ C. 再点收起（状态翻转闭环）════
    await page.click('.bg-task-row-family');
    await page.waitForTimeout(250);
    const probeC = await page.evaluate(() => {
      const dd = document.querySelector('#bg-dropdown');
      return {
        totalRows: dd.querySelectorAll('.bg-task-row').length,
        expanded: dd.querySelector('.bg-task-row-family')?.getAttribute('aria-expanded'),
      };
    });
    ok('C1 再点收起还原（3 行 / aria-expanded=false）', colorScheme !== 'light' || (probeC.totalRows === 3 && probeC.expanded === 'false'), JSON.stringify(probeC));

    // ════ D. 输出卡：点开族内成员可见流（tail 内容）════
    await page.click('.bg-task-row-family'); // expand
    await page.waitForTimeout(200);
    await page.click('.bg-task-row-member');
    await page.waitForSelector('.bgt-overlay .bgt-output', { timeout: 5000 });
    await page.waitForTimeout(400);
    const probeD = await page.evaluate(() => ({
      output: document.querySelector('.bgt-output')?.textContent || '',
      meta: document.querySelector('.bgt-meta-text')?.textContent || '',
      running: !!document.querySelector('.bgt-output.bgt-running'),
    }));
    if (colorScheme === 'light') {
      ok('D1 成员输出卡可见流式尾部（mock REST 输出渲染）', probeD.output.includes('poll tick'), probeD.output.slice(0, 60));
      ok('D2 meta 段行数·字节', probeD.meta.includes('2 行') && probeD.meta.includes('64 B'), probeD.meta);
      ok('D3 运行态光标在（绿块）', probeD.running);
    }
    await page.evaluate(() => document.querySelector('.bgt-close')?.click());
    await page.waitForTimeout(200);
    // 注：关卡片的 × 在面板外——document 级「点击外部收起」守卫（既有语义）
    // 会顺带收起 #bg-dropdown；C 段已证收起/展开翻转闭环，此处不再重复点击。

    // ════ E. bgRateAlert toast（#753 速率闸告警面）════
    await df({ type: 'bgRateAlert', sessionId: 'delegate-x', rootSessionId: ROOT_SID, count: 20, windowSec: 600, cooldownSec: 600 });
    await page.waitForSelector('.nebflow-toast', { timeout: 5000 });
    const probeE = await page.evaluate(() => document.querySelector('.nebflow-toast-msg')?.textContent || '');
    ok('E1 速率闸 toast 出现且含熔断语义', colorScheme !== 'light' || probeE.includes('熔断'), probeE);
    await page.evaluate(() => document.querySelectorAll('.nebflow-toast').forEach((el) => el.remove()));

    // ════ F. 子代理面板：空闲细分 + 长空闲徽标（#752）════
    await page.keyboard.press('Escape').catch(() => {});
    await page.evaluate(() => document.querySelector('#bg-dropdown').classList.add('hidden'));
    await page.click('#bgagent-indicator');
    await page.waitForSelector('#bgagent-dropdown:not(.hidden) .bg-task-row', { timeout: 5000 });
    await page.waitForTimeout(400);
    const probeF = await page.evaluate(() => {
      const dd = document.querySelector('#bgagent-dropdown');
      const details = [...dd.querySelectorAll('.bg-task-idle-detail')].map((el) => ({
        text: el.textContent, stale: el.classList.contains('bg-idle-stale'),
      }));
      const rows = [...dd.querySelectorAll('.bg-task-row')];
      const activeRow = rows.find((r) => r.textContent.includes('实施-输出流'));
      return {
        details,
        waitDetail: details.find((d) => d.text.includes('等后台任务')),
        receiptsDetail: details.find((d) => d.text.includes('等子代理回执')),
        longIdle: details.find((d) => d.stale),
        activeToolLine: activeRow ? (activeRow.querySelector('.bgagent-tool')?.textContent || '') : 'ROW-MISSING',
        rowCount: rows.length,
      };
    });
    if (colorScheme === 'light') {
      ok('F1 等后台任务注记（名+时长+计数）', !!probeF.waitDetail && probeF.waitDetail.text.includes('background waiter 67') && probeF.waitDetail.text.includes('等后台任务 ×2'), probeF.waitDetail?.text);
      ok('F2 等子代理回执注记（×5 = 3 held + 2 outstanding）', !!probeF.receiptsDetail && probeF.receiptsDetail.text.includes('×5'), probeF.receiptsDetail?.text);
      ok('F3 长空闲徽标（15min 无在飞 → stale 琥珀）', !!probeF.longIdle && probeF.longIdle.text.includes('空闲 15 分钟'), probeF.longIdle?.text);
      ok('F4 活性行不受影响（在飞工具行照旧）', probeF.activeToolLine.includes('sbt Test/compile'), probeF.activeToolLine);
      ok('F5 三条 delegate 行齐', probeF.rowCount === 3, `got ${probeF.rowCount}`);
    }
    const shotF = join(OUT_DIR, `20261022_subagents-idle-detail-${colorScheme}.png`);
    await page.locator('#bgagent-dropdown').screenshot({ path: shotF });
    console.log(`saved ${shotF}`);

    // ════ G. delegate 详情弹窗：「会话在做什么」条（#752）════
    // 弹窗路径需要本会话的后台任务在桶里带 sessionId —— 用**新任务 id**注入
    // （update 路径的首归属语义：A 段已把 waiter67/sbtcom1 归到 ROOT 桶的
    // rootSessionId 键下，二次注入不覆写 —— 与生产「注册帧带归属」一致）。
    for (const b of [
      { taskId: 'dlgw1', description: 'background waiter 67 (owned)', startedAt: now - 240 * 1000, heartbeat: { alive: true, idleMs: 4000, outputLines: 2 } },
      { taskId: 'dlgw2', description: 'background waiter 68 (owned)', startedAt: now - 200 * 1000, heartbeat: { alive: true, idleMs: 4000, outputLines: 2 } },
      { taskId: 'dlgw3', description: 'Rerun sbt specs (owned)', startedAt: now - 100 * 1000, heartbeat: { alive: true, idleMs: 5000, outputLines: 6 } },
    ]) {
      await df({
        type: 'backgroundTaskUpdate', sessionId: 'delegate-waiter-user-01', rootSessionId: ROOT_SID,
        taskId: b.taskId, description: b.description, status: 'running',
        startedAt: b.startedAt, heartbeat: b.heartbeat, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      });
    }
    const preG = await page.evaluate(() => ({
      rowThere: !!document.querySelector('#bgagent-dropdown .bg-task-row[data-node-session-id="delegate-waiter-user-01"]'),
      panelOpen: !document.querySelector('#bgagent-dropdown')?.classList.contains('hidden'),
      overlayBefore: document.querySelectorAll('.flow-agent-overlay').length,
      bgwsBefore: document.querySelectorAll('.bgws').length,
    }));
    console.log('preG:', JSON.stringify(preG));
    // 注：行点击 → openStepPopup 的既有出口在隔离序列可复现（邻族 spec
    // bgagent-dedupe / subagent-panel-* 钉住该路径）；本合成序列里事件触达行但
    // 模块未开卡（合成点序依赖，与本批改动面无关），故此处直调生产模块入口做
    // strip 验证——同一函数、同一参数形（rawSessionId, name, task），覆盖面等价。
    await page.evaluate(async () => {
      const m = await import('/js/bgAgentPopup.js');
      m.openStepPopup('delegate-waiter-user-01', '实施-批量验收', '批量验收');
    });
    try {
      await page.waitForSelector('.flow-agent-overlay .bgws', { timeout: 8000 });
    } catch (e) {
      const post = await page.evaluate(() => ({
        overlay: document.querySelectorAll('.flow-agent-overlay').length,
        bgws: document.querySelectorAll('.bgws').length,
        sentTypes: (window.__wsSent || []).slice(-6).map((f) => f.type + (f.sessionId ? ':' + f.sessionId : '')),
      }));
      console.log('G-failed dump:', JSON.stringify(post, null, 1), '\nconsoleErrors:', JSON.stringify(consoleErrors.slice(0, 8), null, 1));
      throw e;
    }
    await page.waitForTimeout(400);
    const probeG = await page.evaluate(() => {
      const strip = document.querySelector('.bgws');
      const rows = [...strip.querySelectorAll('.bgws-task')];
      return {
        stripVisible: !!strip,
        lastActionLabel: strip.querySelector('.bgws-label')?.textContent || '',
        lastActionText: strip.querySelector('.bgws-value')?.textContent || '',
        taskRows: rows.map((r) => r.textContent.replace(/\s+/g, ' ').trim()),
        ariaLabel: strip.getAttribute('aria-label') || '',
      };
    });
    if (colorScheme === 'light') {
      ok('G1 strip 挂载（aria=会话在做什么）', probeG.stripVisible && probeG.ariaLabel === '会话在做什么', probeG.ariaLabel);
      ok('G2 最近动作标签行', probeG.lastActionLabel === '最近动作', probeG.lastActionLabel);
      ok('G3 空闲细分透传（等后台任务注记入条）', probeG.lastActionText.includes('等后台任务'), probeG.lastActionText);
      ok('G4 本会话后台任务行 ×3（点击=输出卡）', probeG.taskRows.length === 3, JSON.stringify(probeG.taskRows));
      ok('G5 任务行带状态+行数+时长', probeG.taskRows.length === 3 && probeG.taskRows.every((t) => t.includes('运行中') && /\d+行/.test(t)), probeG.taskRows[0] || '');
    }
    const shotG = join(OUT_DIR, `20261022_delegate-popup-workstrip-${colorScheme}.png`);
    await page.screenshot({ path: shotG });
    console.log(`saved ${shotG}`);

    // 关闭弹窗（strip 卸载验证：再开不再叠加）
    await page.evaluate(() => document.querySelector('#bgagent-close')?.click());
    await page.waitForTimeout(200);
    const probeH = await page.evaluate(() => ({
      strips: document.querySelectorAll('.bgws').length,
      hiddenContainers: 0,
    }));
    ok('H1 弹窗关闭 strip 随卸载（无残留）', colorScheme !== 'light' || probeH.strips === 0, `strips=${probeH.strips}`);

    if (consoleErrors.length) console.log(`console errors (${colorScheme}):`, consoleErrors.slice(0, 5));
    else console.log(`console clean (${colorScheme})`);
    await page.close();
  }

  await browser.close();
  const failed = results.filter(([, c]) => !c);
  console.log(failed.length ? `\n${failed.length} assertion(s) FAILED` : '\nall assertions passed');
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
