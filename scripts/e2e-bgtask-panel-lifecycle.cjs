#!/usr/bin/env node
// e2e-bgtask-panel-lifecycle.cjs — 后台任务面板「终态行退场」回归钉（2026-09-10 批）。
//
// 挂点理由：既有前端 e2e 面 `scripts/e2e-bgtask-output-card.cjs` 只覆盖「输出详情卡」
// 自身（直接 import bgTaskOutputPopup，不装载 main.js 面板路径），承载不了本批的
// 面板生命周期语义；`shot-bgtask-panel.cjs` 是截图脚本（断言只覆盖静态呈现），
// `tests/*.spec.mjs` 无后台任务面板用例（全仓 grep bg-dropdown/backgroundTaskUpdate
// 只命中 legacy-ui-retire.spec.mjs 的存在性断言）。故新增本最小 e2e：走
// shot-bgtask-panel.cjs 同款自包含打桩（page.route 从磁盘服务真实前端文件 +
// MockWebSocket 走 **生产 ws.js → main.js 分发路径**），注入生产
// `backgroundTaskUpdate` 帧，钉住本批语义：
//   L1 角标数 == 列表行数 == 桶内行数（单一数据源不变量）
//   L2 终态行保留窗口存在（T+5s 仍在）——输出查看入口没被写成 0
//   L3 终态行 TTL（60s）到期从**桶**退场（DOM 不在 ∧ 桶内 taskId 不在 ∧ 角标不含）
//   L4 同窗口非终态行（running / idle / stuck / cancelling）一条都不清（禁「清空整表」）
//   L5 终态行至多 5 条，超出按 finishedAt 逐出最旧
//   L6 R2：详情卡打开期间该行到期**暂停**（跨越 TTL 边界行不消失）；卡片关闭后
//      **重新计时**（+8s 仍在），再满一个 TTL 才消失
//
// 时间：真实 TTL 常量（不注入测试钩子），故本脚本按真实 60s 窗口等待（~3 分钟）。
//
// Run（worktree 根）：node scripts/e2e-bgtask-panel-lifecycle.cjs
//   NEBFLOW_WEB_DIR=<dir>  可指向**隔离副本**的 web 树（变异实验验红用）
// 不起任何端口、不碰 8080；Playwright 一律 browser.close() into finally。
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
const ROOT_SID = 'e2e-bg-root';
const TTL_MS = Number(process.env.BGTASK_TTL_MS || 60000); // 生产常量（main.js TERMINAL_ROW_TTL_MS）
const START = Date.now();

const results = [];
const ok = (name, cond, extra = '') => {
  results.push([name, !!cond]);
  console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra ? `  — ${extra}` : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sinceStart = () => `${((Date.now() - START) / 1000).toFixed(1)}s`;

/** Boot index.html from disk with a MockWebSocket (production ws.js dispatch path). */
async function newPanelPage(browser) {
  const p = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  const errors = [];
  p.on('pageerror', (e) => errors.push({ text: `pageerror: ${e.message}`, url: '' }));
  p.on('console', (m) => {
    if (m.type() === 'error') errors.push({ text: `console.error: ${m.text()}`, url: m.location()?.url || '' });
  });
  await p.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    window.__wsSent = [];
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
      send(d) { try { window.__wsSent.push(typeof d === 'string' ? JSON.parse(d) : d); } catch { window.__wsSent.push(d); } }
      close() {} onopen = null; onmessage = null; onclose = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
  });
  await p.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let pth = decodeURIComponent(url.pathname);
    if (pth === '/') pth = '/index.html';
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
  const bgFrame = (t) => df({
    type: 'backgroundTaskUpdate', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    taskId: t.taskId, description: t.description, status: t.status, startedAt: t.startedAt || (Date.now() - 4000),
    heartbeat: t.heartbeat || null, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
  });
  return { p, df, bgFrame, errors };
}

/** Canonical read (probe precedent: the panel's row DOM is only written while the
 *  dropdown is open ⇒ force close → open, then read DOM + badge + bucket). */
async function readPanel(p) {
  return p.evaluate(async () => {
    const d = document.getElementById('bg-dropdown');
    const ind = document.getElementById('bg-indicator');
    if (ind && ind.classList.contains('hidden')) ind.classList.remove('hidden'); // read the badge even at count 0
    if (d && !d.classList.contains('hidden')) { ind?.click(); await new Promise((r) => setTimeout(r, 120)); }
    for (let i = 0; i < 3 && d && d.classList.contains('hidden'); i++) { ind?.click(); await new Promise((r) => setTimeout(r, 150)); }
    const rows = [...document.querySelectorAll('#bg-dropdown .bg-task-row')].map((r) => ({
      state: (r.querySelector('.bg-task-state')?.textContent || '').trim(),
      name: (r.querySelector('.bg-task-name')?.textContent || '').trim(),
      dotCls: r.querySelector('.bg-task-status')?.className || '',
      taskId: r.querySelector('.bg-task-uptime')?.getAttribute('data-task-id') || null,
    }));
    let bucket = null;
    try { const st = (await import('/js/state.js')).default; bucket = st.sessionBgTasks || {}; } catch { /* noop */ }
    return {
      dropdownHidden: d ? d.classList.contains('hidden') : null,
      header: (document.querySelector('#bg-dropdown .bg-dropdown-header')?.textContent || '').trim(),
      badgeCount: (document.querySelector('#bg-indicator .bg-count')?.textContent || '').trim(),
      indicatorHidden: ind ? ind.classList.contains('hidden') : null,
      rows,
      bucket,
      cardOpen: !!document.querySelector('.bgt-overlay'),
    };
  });
}
const bucketIds = (read, sid) => (read.bucket?.[sid] || []).map((x) => x.taskId);
const domIds = (read) => read.rows.map((r) => r.taskId);

(async () => {
  const browser = await chromium.launch();
  try {
    // ══ 主场景（页 A）：L1 不变量 + L2 保留窗口 + L3 TTL 桶级退场 + L4 非终态零清除 + L6 R2 ══
    {
      const { p, bgFrame, errors } = await newPanelPage(browser);
      const EXPIRED = 'aaaa0001';   // 终态行甲：不开卡片 → 到期退场
      const CARDED = 'aaaa0002';    // 终态行乙：详情卡打开跨越 TTL → 暂停
      const RUN = 'bbbb0001';
      const IDLE = 'bbbb0002';
      const STUCK = 'bbbb0003';
      const CANC = 'bbbb0004';
      const T0_MAIN = Date.now();   // 终态帧注入时刻（TTL 计时基准）
      await bgFrame({ taskId: EXPIRED, description: '终态甲（到期退场探针）', status: 'completed' });
      await bgFrame({ taskId: CARDED, description: '终态乙（详情卡暂停探针）', status: 'completed' });
      await bgFrame({ taskId: RUN, description: '运行中探针', status: 'running', heartbeat: { alive: true, idleMs: 1000, outputLines: 3 } });
      await bgFrame({ taskId: IDLE, description: '空闲探针', status: 'running', heartbeat: { alive: true, idleMs: 200000, outputLines: 3 } });
      await bgFrame({ taskId: STUCK, description: '卡死探针', status: 'running', heartbeat: { alive: true, idleMs: 700000, outputLines: 3 } });
      await bgFrame({ taskId: CANC, description: '取消中探针', status: 'cancelling' });

      // L2/L1（T+5s，远早于 TTL）
      await sleep(5000);
      const r5 = await readPanel(p);
      ok('L1 badge == list rows == bucket rows (T+5s)', r5.badgeCount === String(r5.rows.length) && r5.rows.length === (r5.bucket?.[ROOT_SID] || []).length,
        `badge=${r5.badgeCount} rows=${r5.rows.length} bucket=${(r5.bucket?.[ROOT_SID] || []).length} header=${r5.header}`);
      ok('L2 terminal rows still listed at T+5s (retention window > 0 — output entry preserved)',
        domIds(r5).includes(EXPIRED) && domIds(r5).includes(CARDED), `domIds=${JSON.stringify(domIds(r5))}`);
      ok('L4 non-terminal rows (running/idle/stuck/cancelling) all listed at T+5s',
        [RUN, IDLE, STUCK, CANC].every((id) => domIds(r5).includes(id)), `domIds=${JSON.stringify(domIds(r5))}`);
      ok('L4 status ladder rendered (distinct 状态点 active/idle/stuck/done + 卡死/取消中 labels)',
        r5.rows.some((x) => /bg-status-active/.test(x.dotCls)) && r5.rows.some((x) => /bg-status-idle/.test(x.dotCls))
        && r5.rows.some((x) => /bg-status-stuck/.test(x.dotCls)) && r5.rows.some((x) => /bg-status-done/.test(x.dotCls))
        && r5.rows.some((x) => x.state === '运行中') && r5.rows.some((x) => x.state.includes('取消中'))
        && r5.rows.some((x) => x.state.includes('10 分钟')),
        `states=${JSON.stringify(r5.rows.map((x) => x.state))} dots=${JSON.stringify(r5.rows.map((x) => x.dotCls))}`);

      // L6 前置：真实 UI 路径打开终态乙的详情卡（面板行 click → openBgTaskOutput）
      const openRes = await p.evaluate(async (tid) => {
        const ind = document.getElementById('bg-indicator');
        const d = document.getElementById('bg-dropdown');
        for (let i = 0; i < 3 && d && d.classList.contains('hidden'); i++) { ind?.click(); await new Promise((r) => setTimeout(r, 150)); }
        const row = [...document.querySelectorAll('#bg-dropdown .bg-task-row')]
          .find((r) => r.querySelector('.bg-task-uptime')?.getAttribute('data-task-id') === tid);
        if (!row) return { rowFound: false };
        row.click();
        return { rowFound: true };
      }, CARDED);
      await sleep(700);
      const cardInfo = await p.evaluate(() => {
        const ov = document.querySelector('.bgt-overlay');
        return ov ? { open: true, taskId: ov.getAttribute('data-task-id') } : { open: false, taskId: null };
      });
      ok('L6 precondition: output card open on the terminal row (real row-click path)', openRes.rowFound && cardInfo.open,
        `rowFound=${openRes.rowFound} card=${JSON.stringify(cardInfo)}`);
      ok('L6 precondition: the card carries the row identity (data-task-id = the R2 pause key)',
        cardInfo.taskId === CARDED, `cardTaskId=${cardInfo.taskId} expected=${CARDED}`);

      // 跨越 TTL 边界（卡片保持打开）：等到终态帧注入后 TTL+10s
      while (Date.now() - T0_MAIN < TTL_MS + 10000) await sleep(1000);
      const rTtl = await readPanel(p);
      const idsTtl = domIds(rTtl);
      const bIdsTtl = bucketIds(rTtl, ROOT_SID);
      ok(`L3 terminal row expired from the DOM at T+${Math.round((Date.now() - T0_MAIN) / 1000)}s`,
        !idsTtl.includes(EXPIRED), `domIds=${JSON.stringify(idsTtl)} sinceTerminalMs=${Date.now() - T0_MAIN}`);
      ok('L3 expired taskId gone FROM THE BUCKET (eviction is bucket-level, not DOM-only)',
        !bIdsTtl.includes(EXPIRED), `bucketIds=${JSON.stringify(bIdsTtl)}`);
      ok('L3 badge no longer counts the expired row (badge == list rows == bucket rows)',
        rTtl.badgeCount === String(rTtl.rows.length) && rTtl.rows.length === (rTtl.bucket?.[ROOT_SID] || []).length,
        `badge=${rTtl.badgeCount} rows=${rTtl.rows.length} bucket=${(rTtl.bucket?.[ROOT_SID] || []).length}`);
      ok('L6 reading #1: the carded row is STILL LISTED (DOM + bucket) while its card stays open across the TTL boundary',
        rTtl.cardOpen && idsTtl.includes(CARDED) && bIdsTtl.includes(CARDED),
        `cardOpen=${rTtl.cardOpen} domIds=${JSON.stringify(idsTtl)} bucketIds=${JSON.stringify(bIdsTtl)}`);
      ok('L4 non-terminal rows all SURVIVE the terminal TTL boundary (no table wipe)',
        [RUN, IDLE, STUCK, CANC].every((id) => idsTtl.includes(id) && bIdsTtl.includes(id)),
        `domIds=${JSON.stringify(idsTtl)} bucketIds=${JSON.stringify(bIdsTtl)}`);

      // L6 关闭卡片 → 重新计时（不是「关闭即到期」）
      await p.evaluate(() => document.querySelector('.bgt-overlay .bgt-close')?.click());
      const CLOSED_AT = Date.now();
      await sleep(500);
      const cardClosed = await p.evaluate(() => !document.querySelector('.bgt-overlay'));
      await sleep(8000);
      const r8 = await readPanel(p);
      ok('L6 reading #2a: row still listed +8s after the card closed (countdown RESTARTS, no expire-on-close)',
        cardClosed && domIds(r8).includes(CARDED) && bucketIds(r8, ROOT_SID).includes(CARDED),
        `cardClosed=${cardClosed} domIds=${JSON.stringify(domIds(r8))}`);

      while (Date.now() - CLOSED_AT < TTL_MS + 5000) await sleep(1000);
      const rEnd = await readPanel(p);
      ok('L6 reading #2b: row GONE (DOM + bucket + badge) after a full restarted TTL from close',
        !domIds(rEnd).includes(CARDED) && !bucketIds(rEnd, ROOT_SID).includes(CARDED)
        && rEnd.badgeCount === String(rEnd.rows.length),
        `domIds=${JSON.stringify(domIds(rEnd))} bucketIds=${JSON.stringify(bucketIds(rEnd, ROOT_SID))} badge=${rEnd.badgeCount} rows=${rEnd.rows.length}`);
      ok('L4 non-terminal rows STILL listed at the very end (never evicted — 60s+ beyond the boundary)',
        [RUN, IDLE, STUCK, CANC].every((id) => domIds(rEnd).includes(id) && bucketIds(rEnd, ROOT_SID).includes(id)),
        `domIds=${JSON.stringify(domIds(rEnd))} bucketIds=${JSON.stringify(bucketIds(rEnd, ROOT_SID))}`);
      ok('L1 badge == list rows == bucket rows at the very end', rEnd.badgeCount === String(rEnd.rows.length)
        && rEnd.rows.length === (rEnd.bucket?.[ROOT_SID] || []).length,
        `badge=${rEnd.badgeCount} rows=${rEnd.rows.length} bucket=${(rEnd.bucket?.[ROOT_SID] || []).length}`);

      // /api/* 在本装置里一律 404（不起后端）——只算真错误（页面脚本/资源）
      const real = errors.filter((e) => !e.url.includes('/api/'));
      ok('zero real console/page errors (main scenario)', real.length === 0, real.slice(0, 3).map((e) => e.text).join(' ; '));
      console.log(`[main scenario done ${sinceStart()}]`);
      await p.close();
    }

    // ══ L5 上限：终态行至多 5 条，超出逐出最旧 ══
    {
      const { p, bgFrame, errors } = await newPanelPage(browser);
      const ids = [];
      for (let i = 0; i < 6; i++) {
        const id = `cccc000${i + 1}`;
        ids.push(id);
        await bgFrame({ taskId: id, description: `终态上限探针 ${i + 1}`, status: 'completed' }); // 依次更晚 = finishedAt 递增
        await sleep(60);
      }
      await sleep(2500);
      const r = await readPanel(p);
      const bIds = bucketIds(r, ROOT_SID);
      ok('L5 bucket retains at most 5 terminal rows', bIds.length === 5, `bucketIds=${JSON.stringify(bIds)}`);
      ok('L5 the OLDEST terminal row (by finishedAt) is the one evicted', bIds.length === 5 && !bIds.includes(ids[0]),
        `evicted=${ids[0]} bucketIds=${JSON.stringify(bIds)}`);
      ok('L5 list and badge agree with the capped bucket (5/5/5)',
        r.rows.length === 5 && r.badgeCount === '5' && r.rows.length === bIds.length,
        `rows=${r.rows.length} badge=${r.badgeCount} bucket=${bIds.length} header=${r.header}`);
      const real = errors.filter((e) => !e.url.includes('/api/'));
      ok('zero real console/page errors (cap scenario)', real.length === 0, real.slice(0, 3).map((e) => e.text).join(' ; '));
      await p.close();
    }
  } finally {
    await browser.close();
  }
  const failed = results.filter(([, c]) => !c);
  console.log(failed.length ? `\n${failed.length} assertion(s) FAILED` : `\nall ${results.length} assertions passed (${sinceStart()})`);
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
