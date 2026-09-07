#!/usr/bin/env node
// verify-flowmap-archive-visibility.cjs — 「未完成节点重启归档与状态保真」批前端验收。
//
// 背景（2026-09-07 作者实测缺陷⑤）：宿主重启后，在飞 running 节点被收敛为
// cancelled/failed（真实终态），但前端链归档规则「批内全终态即整链归档」把死亡
// 现场立刻淡出主图；归档面板状态列只有 glyph（cancelled=减号线，视觉即「-」），
// 哪些死了/死前状态无从查看。
//
// 修复口径：
//   a) 链归档资格收紧为「批内全 completed」——含 failed/cancelled 的链永不自动
//      归档，终态色卡保留主图（24h 后由后端 TTL sweep 清理）；
//   b) 状态渲染保真——归档条目/成员行 + 主图终态卡 glyph 旁恒带状态文本，
//      「-」/空白不再可能。
//
// 覆盖断言：
//   ① deriveArchivedIds 单点：仅全 completed 链成员入归档集（failed/cancelled/
//      running 混合链全保留）
//   ② 主图可见性：异常终态链成员卡保留（死亡现场）；全 completed 链归档退场（回归）
//   ③ 主图终态卡状态词：已取消/失败/已完成 文本在卡上（非孤减号线）
//   ④ 归档面板：仅全 completed 链成条目；条目/成员行状态词非空且非「-」
//   ⑤ 详情窗：异常链行文本「含失败/取消·保留主图待清理」（不再称「链未齐」）
//   ⑥ 双主题截图落盘 .nebflow/Spec/assets/
//
// 自包含打桩（同 verify-flowmap-deps.cjs 模式）：python http.server（8098，
// 非 8080）服务真实前端文件 + page.route 打桩 /api/* + MockWebSocket。
// Run: node scripts/verify-flowmap-archive-visibility.cjs

const { chromium } = require('playwright');
const { spawn, execSync } = require('node:child_process');
const { join, dirname } = require('node:path');
const { mkdirSync } = require('node:fs');

const WEB = join(__dirname, '..', 'src', 'main', 'resources', 'web');
const OUT_DIR = join(__dirname, '..', '.nebflow', 'Spec', 'assets');
const PORT = 8098;
const BASE = `http://127.0.0.1:${PORT}`;

const now = Date.now();
// 链聚簇窗口 ≤120s；各链间隔 300s 防串链。
const node = (over) => ({
  hasWorktree: false, worktree: null, result: null,
  createdAt: now, completedAt: null, startedAt: null, ttlLeftSec: null, ...over,
});
// 链 X（异常终态·重启死亡场景）：n-xa completed + n-xb cancelled（重启收敛）
// 链 Y（异常终态·看门狗场景）：n-y failed（单成员链）
// 链 Z（全 completed · 归档回归）：n-za + n-zb
// 链 W（链未齐回归）：n-wa running + n-wb completed
const NODES = [
  node({ id: 'n-xa', name: '诊断-崩溃现场', status: 'completed', createdAt: now - 400000, completedAt: now - 300000, ttlLeftSec: 80000, hasResult: true, description: '诊断重启死亡根因' }),
  node({ id: 'n-xb', name: '实施-崩溃修复', status: 'cancelled', createdAt: now - 370000, completedAt: now - 60000, ttlLeftSec: 86000, description: '修复中宿主重启被杀' }),
  node({ id: 'n-y', name: '实施-看门狗兜底', status: 'failed', createdAt: now - 100000, completedAt: now - 50000, ttlLeftSec: 86000, description: '死会话看门狗收敛' }),
  node({ id: 'n-za', name: '设计-归档规则', status: 'completed', createdAt: now - 800000, completedAt: now - 700000, ttlLeftSec: 80000, description: '正常完成链成员甲' }),
  node({ id: 'n-zb', name: '实施-归档规则', status: 'completed', createdAt: now - 790000, completedAt: now - 690000, ttlLeftSec: 81000, description: '正常完成链成员乙' }),
  node({ id: 'n-wa', name: '实施-在飞任务', status: 'running', createdAt: now - 30000, startedAt: now - 20000, description: '正在运行' }),
  node({ id: 'n-wb', name: '设计-在飞任务', status: 'completed', createdAt: now - 25000, completedAt: now - 10000, ttlLeftSec: 86000, description: '链未齐已完成成员' }),
];

const results = [];
const ok = (name, cond, extra) => {
  results.push({ name, pass: !!cond });
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (extra !== undefined ? ' — ' + JSON.stringify(extra).slice(0, 300) : ''));
};

(async () => {
  mkdirSync(OUT_DIR, { recursive: true });
  // 静态服务（8098，非 8080；用完即清，kill 前 lsof 核对 PID）
  const server = spawn('python3', ['-m', 'http.server', String(PORT)], { cwd: WEB, stdio: 'ignore' });
  let browser = null;
  const killServer = () => { try { server.kill('SIGKILL'); } catch {} };
  // 信号兜底（残留治理 2026-09-05）：SIGINT/SIGTERM 时 Node 不走 finally——
  // 显式清理 browser + 静态服务再退出，进程不漏到脚本外。
  const shutdown = async (code) => {
    try { if (browser) await browser.close(); } catch { /* already closed */ }
    killServer();
    process.exit(code);
  };
  process.on('SIGINT', () => { void shutdown(130); });
  process.on('SIGTERM', () => { void shutdown(143); });

  try {
    await new Promise((r) => setTimeout(r, 800));
    browser = await chromium.launch();
    for (const colorScheme of ['dark', 'light']) {
      const page = await browser.newPage({ viewport: { width: 1920, height: 1200 }, colorScheme });
      page.on('pageerror', (e) => console.log('PAGEERROR', String(e).slice(0, 200)));
      await page.addInitScript(() => {
        localStorage.setItem('nebflow_token', 'shot-token');
        localStorage.setItem('nebflow_locale', 'zh-CN');
        class MockWS {
          static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
          constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
          send() {} close() {} onopen = null; onmessage = null; onclose = null;
        }
        Object.defineProperty(window, 'WebSocket', { value: MockWS });
      });

      await page.route('**/api/**', (route) => {
        const url = new URL(route.request().url());
        if (url.pathname === '/api/projects/demo/flow-map') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
            nodes: NODES, worktrees: [], meta: { project: 'demo', updatedAt: now, archived: 0 },
          }) });
        }
        if (url.pathname === '/api/projects') return route.fulfill({ json: { projects: [] } });
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      });

      await page.goto(`${BASE}/index.html`, { waitUntil: 'domcontentloaded' });
      await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
      await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });

      const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
      await df({ type: 'sessionList', sessionId: 'shot-root', activeId: 'shot-root', sessions: [{ id: 'shot-root', name: 'Nebula', agentName: 'Nebula' }], folders: [] });
      await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
      await df({ type: 'historyPage', sessionId: 'shot-root', messages: [], hasMore: false, offset: 0 });
      await page.waitForTimeout(300);

      await page.evaluate(async () => {
        const pt = await import('/js/projectTab.js');
        pt.openProjectFlowMapAt('demo', '');
      });
      const body = page.locator('.flowmap-view-body');
      await body.waitFor({ state: 'visible', timeout: 8000 });
      await page.waitForSelector('.fm-node', { timeout: 8000 });
      await page.waitForTimeout(700); // 布局/入场动画收敛

      if (colorScheme === 'dark') {
        // ── ① deriveArchivedIds 单点（纯函数口径，任务列表同源）──
        const derived = await page.evaluate(async (nodes) => {
          const m = await import('/js/flowMapArchive.js');
          return Array.from(m.deriveArchivedIds(nodes)).sort();
        }, NODES);
        ok('① deriveArchivedIds 仅全 completed 链（n-za/n-zb）',
          derived.length === 2 && derived[0] === 'n-za' && derived[1] === 'n-zb', derived);

        // ── ② 主图可见性 ──
        const visibleIds = await page.evaluate(() =>
          Array.from(document.querySelectorAll('.fm-node')).map((el) => el.getAttribute('data-node-id')).sort());
        const vis = new Set(visibleIds);
        ok('② 死亡现场保留主图（n-xa/n-xb/n-y 可见）',
          vis.has('n-xa') && vis.has('n-xb') && vis.has('n-y'), visibleIds);
        ok('② 全 completed 链归档退场（n-za/n-zb 不可见）· 回归',
          !vis.has('n-za') && !vis.has('n-zb'), visibleIds);
        ok('② 链未齐回归（n-wa running + n-wb completed 保留）',
          vis.has('n-wa') && vis.has('n-wb'), visibleIds);

        // ── ③ 主图终态卡状态词 ──
        const words = await page.evaluate(() => {
          const out = {};
          for (const el of document.querySelectorAll('.fm-node')) {
            const w = el.querySelector('.fm-st-word');
            out[el.getAttribute('data-node-id')] = w ? w.textContent.trim() : null;
          }
          return out;
        });
        ok('③ cancelled 卡显「已取消」（非孤减号「-」）', words['n-xb'] === '已取消', words['n-xb']);
        ok('③ failed 卡显「失败」', words['n-y'] === '失败', words['n-y']);
        ok('③ completed 保留卡显「已完成」', words['n-xa'] === '已完成' && words['n-wb'] === '已完成',
          { xa: words['n-xa'], wb: words['n-wb'] });
        ok('③ running 卡无终态状态词（不误导）', words['n-wa'] === null, words['n-wa']);

        // ── ④ 归档面板：条目数/状态词保真 ──
        await page.click('[data-testid="archive-toggle"]');
        await page.waitForSelector('[data-testid="archive-panel"].open', { timeout: 5000 });
        await page.waitForTimeout(300);
        const panel = await page.evaluate(() => {
          const entries = Array.from(document.querySelectorAll('[data-testid="archive-entry"]'));
          return {
            badge: document.querySelector('[data-testid="archive-badge"]')?.textContent?.trim(),
            entryCount: entries.length,
            entryWords: entries.map((e) => e.querySelector('.fm-entry-stw')?.textContent?.trim() ?? null),
            entryNames: entries.map((e) => e.querySelector('.fm-entry-name')?.textContent?.trim() ?? null),
          };
        });
        ok('④ 徽章=1（仅全 completed 链归档）', panel.badge === '1' && panel.entryCount === 1, panel);
        ok('④ 条目状态词=已完成（glyph 旁有文本）', panel.entryWords.every((w) => w === '已完成'), panel.entryWords);

        // 展开条目 → 成员行状态词
        await page.click('[data-testid="archive-entry"]');
        await page.waitForTimeout(300);
        const members = await page.evaluate(() =>
          Array.from(document.querySelectorAll('.fm-member')).map((m) => ({
            id: m.getAttribute('data-node'),
            word: m.querySelector('.fm-entry-stw')?.textContent?.trim() ?? null,
          })));
        ok('④ 成员行状态词全非空（2 成员）', members.length === 2 && members.every((m) => m.word === '已完成'), members);
        // 全域保真：面板内任何状态词位不得为空/「-」
        const noDash = await page.evaluate(() =>
          Array.from(document.querySelectorAll('.fm-archive-panel .fm-entry-stw'))
            .every((el) => { const tx = el.textContent.trim(); return tx.length > 0 && tx !== '-' && tx !== '—'; }));
        ok('④ 面板状态词位零「-」/空白（保真硬断言）', noDash);

        // ── ⑤ 详情窗：异常链文本诚实（不再「链未齐」）──
        await page.evaluate(() => {
          const el = document.querySelector('.fm-node[data-node-id="n-xb"]');
          el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
        });
        await page.waitForSelector('[data-testid="detail-panel"].open', { timeout: 5000 });
        await page.waitForTimeout(400);
        const detail = await page.evaluate(() =>
          document.querySelector('[data-testid="detail-panel"]')?.textContent || '');
        ok('⑤ 详情显真实终态「已取消」', detail.includes('已取消'), detail.slice(0, 120));
        ok('⑤ 详情链行=「含失败/取消·保留主图待清理」（非链未齐）',
          detail.includes('含失败/取消·保留主图待清理') && !detail.includes('链未齐·终态保留主图'),
          detail.match(/所属链[^结果]*/)?.[0]?.slice(0, 120));
        // 收详情（Esc），回主图截图
        await page.keyboard.press('Escape');
        await page.waitForTimeout(300);
      }

      // ── ⑥ 截图（主图 + 归档面板展开，双主题）──
      await page.evaluate(() => {
        let el = document.querySelector('.flowmap-card');
        while (el && el !== document.body) {
          el.style.width = '1500px';
          el.style.maxWidth = 'none';
          if (getComputedStyle(el).overflow !== 'visible') el.style.overflow = 'visible';
          el = el.parentElement;
        }
      });
      await page.waitForTimeout(400);
      const shotMain = join(OUT_DIR, `20260907_archive-visibility-main-${colorScheme}.png`);
      await page.locator('.flowmap-card').screenshot({ path: shotMain });
      console.log(`SHOT ${shotMain}`);
      if (colorScheme === 'dark') {
        // 幂等开面板（④ 已开过则跳过；Esc 只关详情不关面板）
        const panelOpen = await page.evaluate(() =>
          !!document.querySelector('[data-testid="archive-panel"].open'));
        if (!panelOpen) {
          await page.click('[data-testid="archive-toggle"]');
          await page.waitForTimeout(300);
        }
        const entryExpanded = await page.evaluate(() =>
          !!document.querySelector('[data-testid="archive-entry"].expanded'));
        if (!entryExpanded) {
          await page.click('[data-testid="archive-entry"]');
          await page.waitForTimeout(300);
        }
        const shotPanel = join(OUT_DIR, '20260907_archive-visibility-panel-dark.png');
        await page.locator('.flowmap-card').screenshot({ path: shotPanel });
        console.log(`SHOT ${shotPanel}`);
      }
      await page.close();
    }

    const failed = results.filter((r) => !r.pass);
    console.log(`\n${results.length - failed.length}/${results.length} PASS`);
    process.exitCode = failed.length ? 1 : 0;
  } finally {
    if (browser) {
      try { await browser.close(); console.log('CLEANUP browser closed'); } catch { /* already closed */ }
    }
    // 清理静态服务：lsof 核对 PID 非宿主（宿主 8080）后再 kill
    try {
      const pid = execSync(`lsof -ti :${PORT}`).toString().trim();
      if (pid) {
        const cmd = execSync(`ps -p ${pid} -o command=`).toString();
        if (cmd.includes('http.server') && !cmd.includes('8080')) {
          process.kill(Number(pid), 'SIGKILL');
          console.log(`CLEANUP static server pid=${pid} killed`);
        }
      }
    } catch { /* port already free */ }
    killServer();
  }
})();
