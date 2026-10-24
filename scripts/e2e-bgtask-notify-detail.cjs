#!/usr/bin/env node
// e2e-bgtask-notify-detail.cjs — 后台任务终态通知详情回归钉（作者报障 2026-10-24
// 「后台任务通知很不详细甚至有截断——这里是不是存在问题」修复批）。
//
// 挂点：e2e-bgtask-panel-lifecycle.cjs 同款自包含打桩（page.route 服务真实前端
// 文件 + MockWebSocket 走生产 ws.js → main.js 分发路径），注入生产帧形态，钉住：
//   P1 完成帧（exitCode/durationMs/finishedAt 只增键）→ 面板行 exit chip「exit 0」
//      + 耗时冻结（1.6s 后读数不变——旧形态终态后仍在走表谎报「已运行 N 分钟」）
//   P2 失败帧（无 exitCode）→ 失败行、无 exit chip（旧前端帧缺键 = 缺读，零回归）
//   P3 通知气泡：注入 user 帧（source=background-task，文本 = 新引擎 payload 形态）
//      → 默认折叠，展开正文含 `(exit 0 · 41.0s)` 与截断标注（总行数 + jobId 指回），
//      且**只含尾段**（line-01 不在正文——通知面不背大输出）
//   P4 跳全文：面板行点击 → 输出详情卡（REST 全量数据源）→ 40 行全文逐字可读
//      （line-01 与 line-40 都在）——「通知 tail / 详情卡 full」配对语义
//   P5 全程零 console.error / pageerror
//
// Run（worktree 根）：node scripts/e2e-bgtask-notify-detail.cjs
//   NEBFLOW_WEB_DIR=<dir>  可指向隔离副本的 web 树（变异实验验红用）
const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const { join, extname, normalize, resolve } = require('node:path');

const WEB = process.env.NEBFLOW_WEB_DIR
  ? resolve(process.env.NEBFLOW_WEB_DIR)
  : join(__dirname, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2',
};
const ROOT_SID = 'e2e-bg-notify-root';

const results = [];
const ok = (name, cond, extra = '') => {
  results.push([name, !!cond]);
  console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra ? `  — ${extra}` : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.launch();
  const errors = [];
  try {
    const p = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
    p.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    p.on('console', (m) => {
      // 打桩面噪音不计（网络层）：未 mock 的 /api/* 恒 404（boot 探测必然打到）+
      // 模块脚本加载噪音；只钉真实 JS 运行时错误（pageerror / 逻辑 console.error）。
      if (m.type() === 'error' && !/Failed to load resource|fetching the script/.test(m.text())) {
        errors.push(`console.error: ${m.text()}`);
      }
    });

    // MockWebSocket（生产 ws.js 分发路径）+ 静态文件打桩 + REST 全量输出打桩（P4）
    const FULL_LINES = Array.from({ length: 40 }, (_, i) => `line-${String(i + 1).padStart(2, '0')}`);
    const FULL_OUTPUT = FULL_LINES.join('\n');
    await p.addInitScript(() => {
      localStorage.setItem('nebflow_token', 'e2e-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      class MockWS {
        static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
        constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
        send() {} close() {} onopen = null; onmessage = null; onclose = null;
      }
      Object.defineProperty(window, 'WebSocket', { value: MockWS });
    });
    await p.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let pth = decodeURIComponent(url.pathname);
      if (pth === '/') pth = '/index.html';
      if (pth.startsWith('/api/bg-tasks/') && pth.endsWith('/output')) {
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({
          taskId: 'p4job0001', status: 'completed', totalBytes: FULL_OUTPUT.length,
          totalLines: FULL_LINES.length, truncated: false, output: FULL_OUTPUT,
          nextOffset: FULL_OUTPUT.length, finishedAtMs: Date.now(), exitCode: 0, errorHint: null,
        }) });
      }
      if (pth.startsWith('/api/')) return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
      const file = normalize(join(WEB, pth));
      if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
      try {
        return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
      } catch { return route.fulfill({ status: 404, body: 'not found' }); }
    });
    await p.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
    await p.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
    await p.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
    await p.waitForTimeout(250);
    const df = (frame) => p.evaluate((f) => window.__wsMock.onmessage({ data: JSON.stringify(f) }), frame);
    await df({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
    await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
    await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
    await p.waitForTimeout(400);

    // ── 生产帧形态（新引擎负载）：完成帧只增键 exitCode/durationMs/finishedAt ──
    await df({ type: 'backgroundTaskUpdate', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
      taskId: 'p1done0001', description: '成功探针（exit chip + 冻结时长）', status: 'completed',
      exitCode: 0, durationMs: 65000, finishedAt: Date.now(), kind: 'local', origin: 'nebula', originLabel: 'Nebula' });
    await df({ type: 'backgroundTaskUpdate', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
      taskId: 'p2fail0001', description: '失败探针（无退出码形态）', status: 'failed',
      exitCode: null, durationMs: 1200, finishedAt: Date.now(), kind: 'local', origin: 'nebula', originLabel: 'Nebula' });
    await df({ type: 'backgroundTaskUpdate', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
      taskId: 'p4job0001', description: '跳全文探针（tail / full 配对）', status: 'completed',
      exitCode: 0, durationMs: 41000, finishedAt: Date.now(), kind: 'local', origin: 'nebula', originLabel: 'Nebula' });

    // 打开面板读行（canonical read：终态行只在 dropdown 打开时写入 DOM）
    const openPanel = async () => p.evaluate(async () => {
      const ind = document.getElementById('bg-indicator');
      const d = document.getElementById('bg-dropdown');
      for (let i = 0; i < 3 && d && d.classList.contains('hidden'); i++) { ind?.click(); await new Promise((r) => setTimeout(r, 150)); }
      return !d.classList.contains('hidden');
    });
    await openPanel();
    const rowOf = (tid) => p.evaluate((id) => {
      const row = [...document.querySelectorAll('#bg-dropdown .bg-task-row[data-task-id]')]
        .find((r) => r.getAttribute('data-task-id') === id);
      if (!row) return null;
      return {
        exit: row.querySelector('.bg-task-exit')?.textContent || null,
        state: (row.querySelector('.bg-task-state')?.textContent || '').trim(),
        uptime: row.querySelector('.bg-task-uptime')?.textContent || null,
      };
    }, tid);

    // P1 完成：exit chip + 冻结时长（1.6s 跨两个 ticker 拍，读数必须不变）
    const p1a = await rowOf('p1done0001');
    ok('P1a 完成行 exit chip = exit 0', p1a?.exit === 'exit 0', `exit=${p1a?.exit}`);
    ok('P1b 完成行耗时 = 1m 5s（终态帧 durationMs）', p1a?.uptime === '1m 5s', `uptime=${p1a?.uptime}`);
    await sleep(1600);
    const p1c = await rowOf('p1done0001');
    ok('P1c 耗时冻结（1.6s 后读数不变——终态后不再走表）', p1c?.uptime === '1m 5s', `uptime=${p1c?.uptime}`);

    // P2 失败：失败态 + 无 exit chip（引擎失败腿无退出码；缺键零渲染）
    const p2 = await rowOf('p2fail0001');
    ok('P2a 失败行状态 = 失败', p2?.state === '失败', `state=${p2?.state}`);
    ok('P2b 失败行无 exit chip（无退出码形态）', p2 ? p2.exit === null : false, `exit=${p2?.exit}`);

    // P4 前置（行仍在）：面板行 exit chip
    const p4row = await rowOf('p4job0001');
    ok('P4a 跳全文探针行 exit chip = exit 0', p4row?.exit === 'exit 0', `exit=${p4row?.exit}`);

    // ── P3 通知气泡（生产帧：source=background-task + 引擎 header + 新 payload）──
    const tail = FULL_LINES.slice(20).join('\n');
    const payloadText =
      '[Background task completed] "render all" (exit 0 · 41.0s):\n' + tail +
      '\n[truncated: showing last 20 of 40 lines — full output: background-tasks panel, job p4job0001]';
    await df({ type: 'user', sessionId: ROOT_SID, injected: true, source: 'background-task',
      eventType: 'completed', timestamp: Date.now(),
      header: 'BACKGROUND · Nebula · Completed', text: payloadText });
    await p.waitForTimeout(300);
    const bubblePre = await p.evaluate(() => {
      const rows = [...document.querySelectorAll('#chat .row.user')];
      const row = rows.find((r) => r.querySelector('.injected-source-label')?.textContent?.includes('BACKGROUND'));
      if (!row) return null;
      const label = row.querySelector('.injected-source-label');
      const content = row.querySelector('.bubble.injected > div:last-child');
      const collapsed = !!(content && content.style.display === 'none');
      if (collapsed) label?.click(); // 生产交互：安静标签即展开点
      return { found: !!row, header: label?.textContent || '', collapsed };
    });
    await p.waitForTimeout(200);
    const bubble = await p.evaluate(() => {
      const rows = [...document.querySelectorAll('#chat .row.user')];
      const row = rows.find((r) => r.querySelector('.injected-source-label')?.textContent?.includes('BACKGROUND'));
      const content = row?.querySelector('.bubble.injected > div:last-child');
      return { body: content?.textContent || '' };
    });
    bubble.found = bubblePre?.found; bubble.header = bubblePre?.header;
    ok('P3a 通知气泡渲染（BACKGROUND header）', !!bubble?.found && /BACKGROUND/.test(bubble.header || ''), `header=${bubble?.header}`);
    ok('P3a2 气泡默认折叠（点击展开后正文可见）', bubblePre?.collapsed === true, `collapsed=${bubblePre?.collapsed}`);
    ok('P3b 展开正文含退出码与耗时 (exit 0 · 41.0s)', /exit 0 · 41\.0s/.test(bubble?.body || ''));
    ok('P3c 正文只含尾段 + 截断标注（总行数 + jobId 指回）',
      bubble?.body?.includes('[truncated: showing last 20 of 40 lines') && bubble.body.includes('job p4job0001'));
    ok('P3d 正文不含头部行 line-01（通知面不背大输出）', !(bubble?.body || '').includes('line-01'));

    // ── P4 跳全文：面板行点击 → 详情卡 REST 全量 ──
    await p.evaluate(() => {
      const row = [...document.querySelectorAll('#bg-dropdown .bg-task-row[data-task-id]')]
        .find((r) => r.getAttribute('data-task-id') === 'p4job0001');
      row?.click();
    });
    await p.waitForTimeout(900);
    const card = await p.evaluate(() => {
      const ov = document.querySelector('.bgt-overlay');
      if (!ov) return { open: false };
      const pre = ov.querySelector('.bgt-output');
      return { open: true, text: pre?.textContent || '' };
    });
    ok('P4b 行点击 → 输出详情卡打开', !!card.open);
    ok('P4c 详情卡 = 全文（line-01 与 line-40 都在——REST 全量数据源）',
      !!card.open && card.text.includes('line-01') && card.text.includes('line-40'),
      `len=${card.text?.length}`);

    // P5 零新增错误
    ok('P5 全程零 console.error / pageerror', errors.length === 0, errors.slice(0, 3).join(' | '));

    // 证据截图（面板展开态）
    await p.screenshot({ path: join(__dirname, '..', 'evidence', '20261024_bg-notify-detail', 'panel.png'), fullPage: false }).catch(() => {});
  } finally {
    await browser.close().catch(() => {});
  }
  const failed = results.filter(([, pass]) => !pass);
  console.log(`\n${results.length - failed.length}/${results.length} assertions passed`);
  process.exit(failed.length === 0 ? 0 : 1);
})();
