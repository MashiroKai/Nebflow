#!/usr/bin/env node
// shot-bgtask-panel.cjs — 后台任务面板重设计批（2026-09-07）视觉验收截图。
//
// 验收面（任务书）：
//   ① 后台任务面板对齐 subagent 面板设计语言（状态点+文字成对 / 两行式行 /
//     来源 chip / kind chip / uptime 右对齐 / 空态 / 键盘可达）。
//   ② 每任务来源标注三类：Nebula / dispatcher/<project> / 节点名。
//   ③ 防重叠：会话行进 subagent 面板、任务行进后台任务面板，节点来源任务
//     只在后台任务面板出现并标注来源，subagent 面板不重复渲染任务。
//
// 自包含打桩（同 shot-node-detail-config.cjs 路线）：page.route 从磁盘服务真实
// 前端文件（不起任何端口、不碰 8080），MockWebSocket 走真实 ws.js 分发路径——
// 后台任务经生产 backgroundTaskUpdate 帧注入（带 origin/originLabel/kind 新字段），
// subagent 行经生产 agentStart 帧注入。空态经生产 activeBgTasks 快照清空后强开。
//
// Run（worktree 根）：node scripts/shot-bgtask-panel.cjs
// 产出：.nebflow/Spec/assets/bgtask-panel-redesign/*.png（gitignore 层，不进 repo）

const { chromium } = require('playwright');
const { readFileSync, mkdirSync } = require('node:fs');
const { join, extname, normalize } = require('node:path');

const WEB = join(__dirname, '..', 'src', 'main', 'resources', 'web');
const OUT_DIR = join(__dirname, '..', '.nebflow', 'Spec', 'assets', 'bgtask-panel-redesign');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const ROOT_SID = 'shot-root';
const NODE_NAME = '实施-后台任务面板重设计';
const now = Date.now();

// 四条后台任务覆盖：三类来源 × 状态阶梯（active/idle/stuck/cancelling）。
const BG_FRAMES = [
  { taskId: 'a1b2c3d4', description: 'sbt -batch compile（增量编译验证）', origin: 'nebula', originLabel: 'Nebula', kind: 'local',
    heartbeat: { alive: true, idleMs: 5000, outputLines: 42 }, startedAt: now - 95_000 },
  { taskId: 'e5f6a7b8', description: 'node scripts/verify-web-assets.mjs（前端门禁）', origin: 'dispatcher', originLabel: 'dispatcher/nebflow', kind: 'local',
    heartbeat: { alive: true, idleMs: 200_000, outputLines: 128 }, startedAt: now - 1_400_000 },
  { taskId: 'c9d0e1f2', description: 'sbt test（BgTaskRegistry origin 回归）', origin: 'node', originLabel: NODE_NAME, kind: 'remote',
    heartbeat: { alive: true, idleMs: 700_000, outputLines: 860 }, startedAt: now - 5_400_000 },
  { taskId: '33445566', description: 'python3 -m http.server 8099（静态预览）', origin: 'node', originLabel: NODE_NAME, kind: 'local',
    cancelling: true, startedAt: now - 300_000 },
];

// 两条 subagent 会话行：与任务③④同源的节点会话 + 与任务②同源的分发器会话。
const AGENT_FRAMES = [
  { agentId: 'mail-node1', name: NODE_NAME, taskDescription: '后台任务面板重设计实施', nodeSessionId: 'node-abc12345' },
  { agentId: 'mail-disp1', name: 'dispatcher/nebflow', taskDescription: '项目任务分发', nodeSessionId: 'dispatcher-def67890' },
];

(async () => {
  mkdirSync(OUT_DIR, { recursive: true });
  const browser = await chromium.launch();
  const results = [];
  const ok = (name, cond) => { results.push([name, !!cond]); console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}`); };

  for (const colorScheme of ['dark', 'light']) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme });
    const consoleErrors = [];
    page.on('pageerror', (e) => consoleErrors.push(String(e)));
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    await page.addInitScript(() => {
      localStorage.setItem('nebflow_token', 'shot-token');
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
    await page.waitForTimeout(400);

    // ── 生产路径注入：subagent 会话行 + 后台任务行 ──
    for (const a of AGENT_FRAMES) {
      await df({ type: 'agentStart', sessionId: ROOT_SID, rootSessionId: ROOT_SID, ...a });
    }
    for (const b of BG_FRAMES) {
      await df({
        type: 'backgroundTaskUpdate', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
        taskId: b.taskId, description: b.description, status: b.cancelling ? 'cancelling' : 'running',
        startedAt: b.startedAt, heartbeat: b.heartbeat || null,
        kind: b.kind, origin: b.origin, originLabel: b.originLabel,
      });
    }
    await page.waitForTimeout(300);

    // ── 截图 1：后台任务面板（三类来源标签 + 状态阶梯）──
    await page.click('#bg-indicator');
    await page.waitForSelector('#bg-dropdown:not(.hidden) .bg-task-row', { timeout: 5000 });
    await page.waitForTimeout(250);

    // 断言面（廉价高值，随截图同跑）
    const probe1 = await page.evaluate(() => {
      const dd = document.querySelector('#bg-dropdown');
      const rows = [...dd.querySelectorAll('.bg-task-row')];
      return {
        rowCount: rows.length,
        origins: [...dd.querySelectorAll('.bg-task-origin')].map((el) => el.textContent),
        originCats: [...dd.querySelectorAll('.bg-task-origin')].map((el) => [...el.classList].find((c) => c.startsWith('bg-origin-'))),
        states: [...dd.querySelectorAll('.bg-task-state')].map((el) => el.textContent),
        dots: [...dd.querySelectorAll('.bg-task-status')].map((el) => el.className),
        kinds: [...dd.querySelectorAll('.bg-task-kind')].map((el) => el.textContent),
        uptimes: dd.querySelectorAll('.bg-task-uptime[data-task-id]').length,
        cancels: dd.querySelectorAll('.bg-task-cancel').length,
        listRole: dd.querySelector('.bg-dropdown-list')?.getAttribute('role'),
        rowA11y: rows.every((r) => r.getAttribute('role') === 'listitem' && r.tabIndex === 0),
        nameLines: dd.querySelectorAll('.bg-task-name-line .bg-task-name').length,
        indicatorA11y: (() => { const i = document.querySelector('#bg-indicator'); return i.getAttribute('role') === 'button' && i.getAttribute('aria-expanded') === 'true'; })(),
      };
    });
    if (colorScheme === 'dark') {
      ok('bg panel rows = 4', probe1.rowCount === 4);
      ok('origin chips cover nebula/dispatcher/node', JSON.stringify(probe1.originCats) === JSON.stringify(['bg-origin-nebula', 'bg-origin-dispatcher', 'bg-origin-node', 'bg-origin-node']));
      ok('origin labels: Nebula / dispatcher/nebflow / node name', probe1.origins[0] === 'Nebula' && probe1.origins[1] === 'dispatcher/nebflow' && probe1.origins[2] === NODE_NAME);
      ok('state labels paired with dots (running/cancelling/stuck)', probe1.states.includes('运行中') && probe1.states.includes('取消中...') && probe1.states.includes('超过 10 分钟无输出'));
      ok('dots: active/idle/stuck/done(cancelling)', probe1.dots.some((c) => c.includes('bg-status-active')) && probe1.dots.some((c) => c.includes('bg-status-idle')) && probe1.dots.some((c) => c.includes('bg-status-stuck')) && probe1.dots.some((c) => c.includes('bg-status-done')));
      ok('kind chips local/remote rendered', probe1.kinds.includes('本地') && probe1.kinds.includes('远程'));
      ok('uptime right-aligned slots = 4', probe1.uptimes === 4);
      ok('cancel buttons = 4 (回归红线保留)', probe1.cancels === 4);
      ok('list role=list + rows listitem/tabindex', probe1.listRole === 'list' && probe1.rowA11y);
      ok('two-line rows (name line present)', probe1.nameLines === 4);
      ok('indicator a11y (role=button, aria-expanded)', probe1.indicatorA11y);
    }
    const shot1 = join(OUT_DIR, `20260907_bgtask-panel-origins-${colorScheme}.png`);
    await page.locator('#bg-dropdown').screenshot({ path: shot1 });
    console.log(`saved ${shot1}`);

    // ── 截图 2：双面板同屏（防重叠对照）——仅为取景把 bgagent 下拉左移，属截图
    //    编排非样式改动；两下拉本就同锚点（top:50px right:24px）互斥打开。
    //    空白启动 fallback 会自动打开 canvas 插件页（main.js:3179 openPlugins），
    //    取景时隐藏 canvas 列避免遮挡下拉。
    await page.click('#bg-indicator'); // close bg
    await page.click('#bgagent-indicator'); // open subagent
    await page.waitForSelector('#bgagent-dropdown:not(.hidden) .bg-task-row', { timeout: 5000 });
    await page.evaluate(() => {
      const bg = document.querySelector('#bg-dropdown');
      const agent = document.querySelector('#bgagent-dropdown');
      const canvas = document.querySelector('#canvas-panel');
      if (canvas) canvas.style.display = 'none'; // capture-only
      agent.style.right = '424px'; // capture-only offset
      bg.classList.remove('hidden'); // re-open both for the side-by-side shot
    });
    await page.waitForTimeout(250);
    const probe2 = await page.evaluate(() => {
      const bgText = document.querySelector('#bg-dropdown').innerText;
      const agentText = document.querySelector('#bgagent-dropdown').innerText;
      const taskDescs = ['sbt -batch compile（增量编译验证）', 'sbt test（BgTaskRegistry origin 回归）'];
      return {
        agentRows: document.querySelectorAll('#bgagent-dropdown .bg-task-row').length,
        taskLeak: taskDescs.some((dsc) => agentText.includes(dsc)),
        nodeRowInAgent: agentText.includes('实施-后台任务面板重设计'),
        nodeTaskInBg: bgText.includes('sbt test（BgTaskRegistry origin 回归）'),
      };
    });
    if (colorScheme === 'dark') {
      ok('subagent panel: 2 session rows (node + dispatcher)', probe2.agentRows === 2);
      ok('防重叠: task rows NOT duplicated in subagent panel', !probe2.taskLeak);
      ok('node session row in subagent panel (kind Flow)', probe2.nodeRowInAgent);
      ok('node-origin task only in bg panel with origin chip', probe2.nodeTaskInBg);
    }
    const shot2 = join(OUT_DIR, `20260907_bgtask-vs-subagent-overlap-${colorScheme}.png`);
    await page.screenshot({ path: shot2, clip: { x: 1440 - 860, y: 0, width: 860, height: 480 } });
    console.log(`saved ${shot2}`);

    // ── 截图 3：空态（生产 activeBgTasks 快照清空 → 强开指示器）──
    await page.keyboard.press('Escape').catch(() => {});
    await page.evaluate(() => {
      document.querySelector('#bg-dropdown').classList.add('hidden');
      document.querySelector('#bgagent-dropdown').classList.add('hidden');
      document.querySelector('#bgagent-dropdown').style.right = '';
      const canvas = document.querySelector('#canvas-panel');
      if (canvas) canvas.style.display = '';
    });
    await df({ type: 'activeBgTasks', tasks: {} });
    await page.waitForTimeout(200);
    await page.evaluate(() => {
      const ind = document.querySelector('#bg-indicator');
      ind.classList.remove('hidden'); // 空态验收：强制可达（平时 count=0 指示器隐藏）
      ind.click();
    });
    await page.waitForTimeout(250);
    const probe3 = await page.evaluate(() => {
      const dd = document.querySelector('#bg-dropdown');
      return { empty: dd.querySelector('.bg-dropdown-empty')?.textContent || '', visible: !dd.classList.contains('hidden') };
    });
    if (colorScheme === 'dark') ok('empty state renders i18n line', probe3.visible && probe3.empty.includes('后台任务'));
    const shot3 = join(OUT_DIR, `20260907_bgtask-panel-empty-${colorScheme}.png`);
    await page.locator('#bg-dropdown').screenshot({ path: shot3 });
    console.log(`saved ${shot3}`);

    // console 无新增错误哨兵
    if (consoleErrors.length) console.log(`console errors (${colorScheme}):`, consoleErrors.slice(0, 5));
    else console.log(`console clean (${colorScheme})`);
    await page.close();
  }

  await browser.close();
  const failed = results.filter(([, c]) => !c);
  console.log(failed.length ? `\n${failed.length} assertion(s) FAILED` : '\nall assertions passed');
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
