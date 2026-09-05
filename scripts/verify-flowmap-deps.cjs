#!/usr/bin/env node
// verify-flowmap-deps.cjs — deps 依赖连接 Flow Map 视觉验收（deps 设计 §1.4）。
//
// 覆盖断言：
//   ① deps 边按下游 n.deps 反向渲染（`~>` 边 id + fm-edge-deps 类 + 三态档位）
//   ② 输出边（=>）不受影响（delivered 档无 deps 类）
//   ③ 六档图例条渲染（输出三态 × 依赖三态）
//   ④ 等待脚注（名(id) / 归档上游 裸id✦ 诚实降级）
//   ⑤ 亮暗双主题截图落盘 ~/.nebflow/docs/Nebflow/20260903_deps-edge-{dark,light}.png
//
// 自包含打桩：python http.server（8097，非 8080）服务真实前端文件 +
// page.route 打桩 /api/* + MockWebSocket 注入真实 ws.js 分发路径。
// Run: node scripts/verify-flowmap-deps.cjs

const { chromium } = require('playwright');
const { spawn } = require('node:child_process');
const { join, dirname } = require('node:path');

const WEB = join(__dirname, '..', 'src', 'main', 'resources', 'web');
const OUT_DIR = join(process.env.HOME, '.nebflow', 'docs', 'Nebflow');
const PORT = 8097;
const BASE = `http://127.0.0.1:${PORT}`;

const now = Date.now();
const node = (over) => ({
  hasWorktree: false, worktree: null, result: null, retries: 0,
  createdAt: now, completedAt: null, startedAt: null, ttlLeftSec: null, ...over,
});

// 场景图（覆盖三档 deps 边 + 输出边 + 等待脚注 + 归档 ✦ 降级）：
//   n-src(completed, out=>n-merge)          → 输出边 delivered
//   n-env(running)                            n-merge(pending, in=[n-src], deps=[n-env, n-archived-gone])
//   n-done(completed, out=Nebula)             n-final(pending, in=[n-src2], deps=[n-done]) → deps-met
//   n-src2(running)                           n-waiter(wiring, deps=[n-blk]) → n-blk blocked → deps-unmet
const NODES = [
  node({ id: 'n-src', name: '调研', agent: 'czt-researcher', status: 'completed', completedAt: now - 60_000, ttlLeftSec: 3600, result: '调研完成……', out: 'n-merge' }),
  node({ id: 'n-env', name: '建工作树', agent: 'Backend', status: 'running', startedAt: now - 30_000 }),
  node({ id: 'n-done', name: '审定', agent: 'design-engineer', status: 'completed', completedAt: now - 120_000, ttlLeftSec: 7200, result: '审定通过', out: 'Nebula' }),
  node({ id: 'n-src2', name: '补充调研', agent: 'czt-researcher', status: 'running', startedAt: now - 10_000 }),
  node({ id: 'n-blk', name: '卡住的子任务', agent: 'Backend', status: 'blocked' }),
  node({ id: 'n-merge', name: '开工', agent: 'swift-dev', status: 'pending', in: ['n-src'], deps: ['n-env'] }),
  node({ id: 'n-final', name: '收尾', agent: 'qa-swift', status: 'pending', in: ['n-src2'], deps: ['n-done'] }),
  node({ id: 'n-waiter', name: '等卡点', agent: 'Manager', status: 'wiring', deps: ['n-blk', 'n-archived-gone'] }),
];

const results = [];
const ok = (name, cond, extra) => {
  results.push({ name, pass: !!cond });
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (extra !== undefined ? ' — ' + JSON.stringify(extra).slice(0, 300) : ''));
};

(async () => {
  // 静态服务（8097，非 8080；用完即清，kill 前 lsof 核对 PID）
  const server = spawn('python3', ['-m', 'http.server', String(PORT)], { cwd: WEB, stdio: 'ignore' });
  let browser = null;
  const killServer = () => { try { server.kill('SIGKILL'); } catch {} };
  // 信号兜底（残留治理 2026-09-05）：SIGINT/SIGTERM 时 Node 不走 finally——
  // 显式清理 browser + 静态服务再退出，进程不漏到脚本外。
  // 2026-09-05 修复：browser.close() 是异步 Promise，必须 await 完成后再
  // process.exit —— 否则 exit 同步先行，chromium 进程树被一起带走（泄漏）。
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

      // 静态文件走 8097 真实服务；/api/* 打桩
      await page.route('**/api/**', (route) => {
        const url = new URL(route.request().url());
        if (url.pathname === '/api/projects/demo/flow-map') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
            nodes: NODES, worktrees: [], meta: { project: 'demo', updatedAt: now, archived: 1 },
          }) });
        }
        if (url.pathname === '/api/projects') return route.fulfill({ json: { projects: [] } });
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      });

      await page.goto(`${BASE}/index.html`, { waitUntil: 'domcontentloaded' });
      await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
      await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
      await page.evaluate(async () => {
        const { sessionList, configData, historyPage } = await import('/js/ws.js').then(() => ({})).catch(() => ({}));
        void sessionList; void configData; void historyPage;
      });

      // 注入最小启动事件序列（真实 ws.js 分发路径）
      const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
      await df({ type: 'sessionList', sessionId: 'shot-root', activeId: 'shot-root', sessions: [{ id: 'shot-root', name: 'Nebula', agentName: 'Nebula' }], folders: [] });
      await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
      await df({ type: 'historyPage', sessionId: 'shot-root', messages: [], hasMore: false, offset: 0 });
      await page.waitForTimeout(300);

      // 打开 projects 就地 Flow Map 视图（真实 projectTab 入口）
      await page.evaluate(async () => {
        const pt = await import('/js/projectTab.js');
        pt.openProjectFlowMapAt('demo', '');
      });

      const body = page.locator('.flowmap-view-body');
      await body.waitFor({ state: 'visible', timeout: 8000 });
      await page.waitForSelector('.fm-node', { timeout: 8000 });
      await page.waitForTimeout(600); // 布局/入场动画收敛

      if (colorScheme === 'dark') {
        // ── ① deps 边：反向渲染 + 类与档位 ──
        const edgeInfo = await page.evaluate(() => {
          const get = (id) => {
            const p = document.querySelector(`path[data-edge-id="${id}"]`);
            return p ? p.getAttribute('class') : null;
          };
          return {
            depsWait: get('n-env~>n-merge'),
            depsMet: get('n-done~>n-final'),
            depsUnmet: get('n-blk~>n-waiter'),
            outDelivered: get('n-src=>n-merge'),
          };
        });
        ok('① deps-wait edge (running upstream, orange)', /fm-edge-deps/.test(edgeInfo.depsWait || '') && /deps-wait/.test(edgeInfo.depsWait || ''), edgeInfo.depsWait);
        ok('① deps-met edge (completed upstream, green)', /fm-edge-deps/.test(edgeInfo.depsMet || '') && /deps-met/.test(edgeInfo.depsMet || ''), edgeInfo.depsMet);
        ok('① deps-unmet edge (blocked upstream, grey)', /fm-edge-deps/.test(edgeInfo.depsUnmet || '') && /deps-unmet/.test(edgeInfo.depsUnmet || ''), edgeInfo.depsUnmet);
        ok('② output edge unaffected (delivered, no deps class)', /delivered/.test(edgeInfo.outDelivered || '') && !/fm-edge-deps/.test(edgeInfo.outDelivered || ''), edgeInfo.outDelivered);

        // ── ② 空心箭头（第二类型编码） ──
        const arrowCls = await page.evaluate(() => {
          const c = document.querySelector('circle[data-edge-id="n-env~>n-merge"]');
          return c ? c.getAttribute('class') : null;
        });
        ok('② deps arrow carries fm-edge-deps-arrow (hollow via CSS)', /fm-edge-deps-arrow/.test(arrowCls || ''), arrowCls);

        // ── ③ 六档图例 ──
        const legend = await page.evaluate(() => {
          const el = document.querySelector('[data-testid="fm-legend"]');
          if (!el) return null;
          return { items: el.querySelectorAll('.fm-legend-item').length, text: el.textContent };
        });
        ok('③ legend has 6 items', legend && legend.items === 6, legend && legend.items);
        ok('③ legend bilingual keys render (zh-CN)', legend && legend.text.includes('输出已投递') && legend.text.includes('依赖已满足') && legend.text.includes('依赖未满足'), legend && legend.text);

        // ── ④ 等待脚注（名(id) + 归档 ✦ 降级）──
        const notes = await page.evaluate(() => {
          return Array.from(document.querySelectorAll('.fm-node')).map((el) => ({
            id: el.getAttribute('data-node-id'),
            note: el.querySelector('.fm-wait-note')?.textContent || null,
          }));
        });
        const mergeNote = notes.find((x) => x.id === 'n-merge')?.note || '';
        const finalNote = notes.find((x) => x.id === 'n-final')?.note || '';
        const waiterNote = notes.find((x) => x.id === 'n-waiter')?.note || '';
        ok('④ merge footnote lists in+deps upstreams (名(id))', mergeNote.includes('调研(n-src)') && mergeNote.includes('建工作树(n-env)'), mergeNote);
        ok('④ archived upstream degrades to bare id + ✦', waiterNote.includes('n-archived-gone✦'), waiterNote);
        ok('④ final footnote shows deps-met waiting entry', finalNote.includes('审定(n-done)') && finalNote.includes('补充调研(n-src2)'), finalNote);
        ok('④ wiring waiter footnote lists blocked upstream', waiterNote.includes('卡住的子任务(n-blk)'), waiterNote);
      }

      // ── ⑤ 双主题截图（.solar-card 整卡：画布 + 图例）──
      // 面板列 ~790px < 画布 1130px（5 根节点层）：从卡片逐级向上放宽全部祖先宽度，
      // 让整图入镜（不猜容器类名，遍历兜底）
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
      const out = join(OUT_DIR, `20260903_deps-edge-${colorScheme}.png`);
      await page.locator('.flowmap-card').screenshot({ path: out });
      console.log(`SHOT ${out}`);
      await page.close();
    }

    const failed = results.filter((r) => !r.pass);
    console.log(`\n${results.length - failed.length}/${results.length} PASS`);
    process.exitCode = failed.length ? 1 : 0;
  } finally {
    // browser 异常路径也关（残留治理 2026-09-05）：waitForSelector 超时等中断
    // 异常会跳过 happy-path 的 close，headless chromium 进程树会漏到脚本外。
    // close 移入 finally（幂等，try 内不再重复调）
    if (browser) {
      try { await browser.close(); console.log('CLEANUP browser closed'); } catch { /* already closed */ }
    }
    // 清理静态服务：lsof 核对 PID 非宿主（宿主 8080）后再 kill
    const { execSync } = require('node:child_process');
    try {
      const pid = execSync(`lsof -ti :${PORT}`).toString().trim();
      if (pid) {
        const cmd = execSync(`ps -p ${pid} -o command=`).toString();
        if (cmd.includes('http.server') && !cmd.includes('8080')) {
          process.kill(Number(pid));
          console.log(`CLEANUP static server pid=${pid} killed`);
        } else {
          console.log(`CLEANUP SKIP pid=${pid} unexpected: ${cmd}`);
        }
      }
    } catch { /* port already free */ }
  }
})();
