// shoot-tasklist.mjs — tasklist-display-fixes batch 2026-10-19 evidence leg.
// Before/after capture for the author's three-point screenshot report:
//   ① stderr fragments leak into list rows ([stderr]} / } under external rows)
//      — fixed: engine control lines + punctuation-only fragment lines never
//      render on the row (stderr belongs to the detail view only).
//   ② 「8 tasks」 stats floated mid-air (old 44px indent) — fixed: the count
//      sits at the edge, right beside the collapse toggle (author directive).
//   ③ executor mark crowding the title row (14px, no alignment CSS, nebflow
//      ghost slot) — fixed: 九点② dedup discipline (internal executor = no
//      badge, L1 face precedent) + external badge 12px in the glyph slot rhythm.
//
// Scenario mirrors the author's screenshot: 8 leaf tasks, 2 project groups
// (link-server + stand), two external running rows (codex #567, zcode #563)
// fed a stderr-laden output buffer through the real /api/delegates/:id/output
// endpoint shape (stdout + `[stderr] ` lines interleaved — the same buffer the
// engine's ExternalOutputStore serves; stderr lines included ON PURPOSE so the
// before state reproduces the leak and the after state proves the guard).
//
// Variants: BEFORE shots were captured against HEAD 5f973f36d (pre-fix, first
// capture); AFTER shots run against the fixed tree. The fixture body is byte-
// identical across both runs — only the sources differ.
//
// Run: NODE_PATH=/opt/homebrew/lib/node_modules node shoot-tasklist.mjs [before|after]
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', '..', 'src', 'main', 'resources', 'web');
const PORT = 8193;
const SHOTS = join(HERE, 'shots');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2',
};

const ROOT_SID = 'shoot-root-session';
const T0 = Date.now();

// ── Fixture: the author's 8-task panel (leaf roots only ⇒ stats = 8) ──────
const rows = [
  { taskNo: '571', delegateId: 'delegate-nebflow-571', title: '【查证 · 哪些流程会落到 Logto 原生默认登录页】作者看到 core 托管页截图（Logto 内置默认体验）', executor: '', status: 'running', external: false, project: 'link-server', createdAt: T0 - 20_000 },
  { taskNo: '570', delegateId: 'delegate-nebflow-570', title: '【修复批 #569 · 客户端断线重连自动重发消息（重复气泡）】作者令：客户端存在**重复发送消息**问题', executor: 'nebflow', status: 'running', external: false, project: 'stand', createdAt: T0 - 11 * 60_000 },
  { taskNo: '568', delegateId: 'delegate-nebflow-568', title: '【修复批 #566 · 任务面板陈旧状态 + delegate 砍掉 blocked 语义】作者令两件事', executor: 'nebflow', status: 'running', external: false, project: 'stand', createdAt: T0 - 33 * 60_000 },
  { taskNo: '567', delegateId: 'delegate-codex-567', title: '【冒烟测试 · codex 执行器首次接入验证】你是 Stand 平台新接入的 codex 执行器，这是一次连通性测试', executor: 'codex', status: 'running', external: true, project: 'stand', createdAt: T0 - 33 * 60_000 },
  { taskNo: '563', delegateId: 'delegate-zcode-563', title: '【小修 · avatar slot 模糊排查+修复】（内置模型链今天不稳，本批改走 ZCode 执行器）背景：主仓', executor: 'zcode', status: 'running', external: true, project: 'stand', createdAt: T0 - 37 * 60_000 },
  { taskNo: '554', delegateId: 'delegate-nebflow-554', title: '【迁移段一增量补拷批 · 补救选项 A 执行】背景：#535 段一真跑预拷贝被中断', executor: 'nebflow', status: 'running', external: false, project: 'stand', createdAt: T0 - 5 * 3_600_000 },
  { taskNo: '552', delegateId: 'delegate-nebflow-552', title: '【AskUser 拖拽回复实施批 #551 · 作者已批样张，按修正实施】设计稿与样张已获作者批准', executor: 'nebflow', status: 'blocked', external: false, project: 'stand', createdAt: T0 - 6 * 3_600_000 },
  { taskNo: '537', delegateId: 'delegate-nebflow-537', title: '【数据根迁移段一 #535 · 活动盘安全预拷贝 + 停机窗秒切脚本】作者今晨令', executor: 'nebflow', status: 'running', external: false, project: 'stand', createdAt: T0 - 7 * 3_600_000 },
];

// The stderr-laden buffer, exactly the shape ExternalOutputStore serves
// (stdout raw + `[stderr] ` prefixed lines, interleaved). IDENTICAL for the
// before and after runs.
const OUTPUT_BODIES = {
  // codex #567 — author's symptom: the row showed `[stderr]}`.
  'delegate-codex-567': {
    output: 'Analyzing workspace structure\n[stderr] 2026-10-19T07:29:01.114Z WARN stream: chunk parse retry\npatch plan written (3 files)\n}\n[stderr] }',
    nextOffset: 4317, exitCode: null,
  },
  // zcode #563 — author's symptom: the row showed `}`.
  'delegate-zcode-563': {
    output: '{"type":"turn.started"}\nReading main repo status\napplying patch — 3 files touched\n{\n  "applied": true\n}',
    nextOffset: 988, exitCode: null,
  },
};

function startServer() {
  return new Promise((resolveDone) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(p)] || 'application/octet-stream' });
        res.end(data);
      } catch { res.writeHead(404); res.end('not found'); }
    });
    server.listen(PORT, '127.0.0.1', () => resolveDone(server));
  });
}

async function boot(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'en'); // author's env: en UI, zh data
  });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  // The output endpoint LAST registered → wins over the generic api route.
  await page.route(/\/api\/delegates\/[^/]+\/output/, (route) => {
    const url = new URL(route.request().url());
    const id = decodeURIComponent(url.pathname.split('/')[3]);
    const body = OUTPUT_BODIES[id];
    if (!body) return route.fulfill({ status: 404, body: 'no buffer' });
    return route.fulfill({ json: body });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'autostartStatus') ws.send(JSON.stringify({ type: 'autostartStatusResult', enabled: false, supported: true }));
    });
  });
}

async function loadFixture(page) {
  await page.evaluate(async (fx) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(fx);
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, 'shoot-root-session');
  }, { rows, chains: [], children: {} });
  // S16 progress poll fires on render + rounds every 3s; wait out one round so
  // the external meta lines settle (before: leak visible / after: guarded).
  await page.waitForTimeout(900);
}

const server = await startServer();
const base = `http://127.0.0.1:${PORT}`;
await mkdir(SHOTS, { recursive: true });
const browser = await chromium.launch();
const report = [];

try {
  for (const variant of process.argv.slice(2).length ? process.argv.slice(2) : ['after']) {
    for (const scheme of ['dark', 'light']) {
      const ctx = await browser.newContext({
        viewport: { width: 1440, height: 900 }, colorScheme: scheme, deviceScaleFactor: 1,
      });
      const page = await ctx.newPage();
      await boot(page);
      await page.goto(base, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1800);
      await loadFixture(page);

      const tag = `${variant}-${scheme}`;
      // Slot 1: the whole panel (header + all 8 rows).
      await page.locator('#task-list').screenshot({ path: join(SHOTS, `${tag}-panel.png`) });

      // Slot 2: the two external rows at 2× for icon/tail detail reading.
      const ctx2 = await browser.newContext({
        viewport: { width: 1440, height: 900 }, colorScheme: scheme, deviceScaleFactor: 2,
      });
      const page2 = await ctx2.newPage();
      await boot(page2);
      await page2.goto(base, { waitUntil: 'domcontentloaded' });
      await page2.waitForTimeout(1800);
      await loadFixture(page2);
      for (const no of ['567', '563']) {
        await page2.locator(`.task-chain-head[data-task-no="${no}"]`)
          .screenshot({ path: join(SHOTS, `${tag}-row-${no}.png`) });
      }

      // Readings: stats alignment + icon geometry + row meta text.
      const reading = await page.evaluate(() => {
        const box = (el) => {
          if (!el) return null;
          const r = el.getBoundingClientRect();
          return { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) };
        };
        const stats = document.querySelector('#task-list .task-stats');
        const toggle = document.querySelector('#task-list .task-toggle');
        const out = {
          stats: stats ? { text: stats.textContent, marginLeft: getComputedStyle(stats).marginLeft, box: box(stats) } : null,
          toggleBox: box(toggle),
          rows: {},
        };
        document.querySelectorAll('#task-list .task-chain-head').forEach((row) => {
          const no = row.dataset.taskNo;
          const icon = row.querySelector('.task-delegate-icon');
          const glyph = row.querySelector('.task-node-spin, .task-node-dot, .task-node-box');
          const meta = row.querySelector('.task-node-meta');
          out.rows[no] = {
            icon: icon ? { box: box(icon), html: icon.innerHTML.slice(0, 80) } : null,
            glyphBox: box(glyph),
            meta: meta ? meta.textContent : null,
          };
        });
        return out;
      });
      report.push({ tag, ...reading });
      console.log(`[${tag}]`, JSON.stringify(reading));
      await ctx.close();
      await ctx2.close();
    }
  }
  await writeFile(join(SHOTS, 'report.json'), JSON.stringify(report, null, 2));
  console.log('OK shots written to', SHOTS);
} finally {
  await browser.close();
  server.close();
}
