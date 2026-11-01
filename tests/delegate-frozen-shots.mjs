// delegate-frozen-shots.mjs — 错误冻结批（2026-11-02 · 作者令「冻结态 方便被唤醒
// 接着做」）面板端到端证据。
//
// 验收②：「面板显示冻结徽标（与 standby 区分）/详情卡原因与唤醒入口」+「唤醒 = 在原
// 会话上下文继续」。本件走**真前端**（src/main/resources/web）+ 假 WS 传输，把引擎
// 侧契约帧逐字节喂进去（帧形取自 BackoffSupervisorFreezeSpec / DelegateFreeze：
// delegateFrozen / delegateFrozenDormant / delegateWakeResult；命令 delegateWake）：
//
//   A. delegateFrozen 帧到 ⇒ 行显「已冻结 · <原因>」徽标（与 standby 的「待命（等作者）」
//      显式不同）+ 「唤醒」按钮；tooltip 三要素（不是失败判词的说明 / 原因 / 详情原文）。
//   B. 冻结行优先于 waiting：同行的候合并语义被冻结覆盖（rowState = frozen）。
//   C. 点「唤醒」⇒ 发出 `delegateWake{sessionId}`（停冒泡，不打开详情）。
//   D. delegateWakeResult{ok:true,mode:live} 到 ⇒ 冻结徽标与按钮消失（同会话续跑）。
//   E. delegateWakeResult{ok:false} 到 ⇒ 徽标保留 + toast 可见（绝不静默失败）。
//   F. delegateFrozenDormant 帧 ⇒ tooltip 多「已冻结 N 小时仍无人唤醒」。
//   G. 快照复原：activeAgents 行带 frozenSince + frozenReason=`delegate-frozen:llm-failure`
//      ⇒ 刷新后徽标/原因/唤醒按钮复原（本地按线值映射中文短名）。
//   H. 非本族冻结（frozenReason=`schedule`）⇒ **零**冻结徽标（时间表冻结不受影响）。
//
// 用法：node tests/delegate-frozen-shots.mjs
// 产出 = evidence/20261102_delegate-frozen__delegate-nebflow-b27760ba/shots/*.png
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.WEB_DIR
  ? process.env.WEB_DIR
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261102_delegate-frozen__delegate-nebflow-b27760ba', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'fz-root-session';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

/** 页面 + 假 WS：命令侧记录全部上行帧（唤醒断言），下行由用例主动 send。 */
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
  // 上行帧收集（window.__sent）——唤醒命令的判红锚。
  await page.addInitScript(() => { window.__sent = []; });
  const senders = [];
  await page.routeWebSocket(/\/ws/, (ws) => {
    senders.push((obj) => ws.send(JSON.stringify(obj)));
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
  // 上行记录挂在页面里（routeWebSocket 回调在 node 侧，读 m 后写进页面）。
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  // 页面内收集上行帧：包一层 WebSocket.prototype.send（在任何 sendWs 之前装）。
  await page.evaluate(() => {
    window.__sentFrames = [];
    const proto = window.WebSocket.prototype;
    const orig = proto.send;
    proto.send = function (data) {
      try { window.__sentFrames.push(JSON.parse(data)); } catch { /* binary */ }
      return orig.call(this, data);
    };
  });
  // 面板重渲染单点（真实路径：终态/徽标管线走 refreshAllBgAgentIndicators——
  // renderBgAgentDropdown 只刷 activeView，而本 harness 的 primary 视图在未挂载
  // 聊天窗时 activeView 为空；refresh 会逐视图换场重算，与生产同款）。
  const render = async () => {
    await page.evaluate(async () => {
      const m = await import('/js/main/bgAgents.js');
      m.refreshAllBgAgentIndicators();
    });
    await page.waitForTimeout(120);
  };
  const push = async (obj) => {
    for (const s of senders) s(obj);
    await page.waitForTimeout(220);
    await render();
  };
  return { page, push, render };
}

/** 种场景：冻结行 + 待命对照 + 候合并对照 + 运行对照 + 非本族冻结对照。 */
async function seedScene(page) {
  await page.evaluate(({ sid }) => import('/js/state.js').then((m) => {
    const t0 = Date.now();
    m.default.sessionBgAgents[sid] = {
      // ① 冻结行（错误冻结）：先按「running」入册，再喂 delegateFrozen 帧（真路径）。
      'delegate-frozen-1': { name: 'nebflow', kind: 'Delegate', sessionId: 'delegate-frozen-1',
        status: 'Processing', startedAt: t0 - 5400_000,
        task: '错误冻结批（作者令：错误状态的 delegate 保留冻结态，方便被唤醒接着做）', parentSessionId: sid },
      // ② 待命对照：standby 语义不被冻结污染。
      'delegate-standby-2': { name: 'nebflow', kind: 'Delegate', sessionId: 'delegate-standby-2',
        status: 'Waiting', startedAt: t0 - 1800_000,
        task: '待命对照行（自愿停靠，等作者给活）', parentSessionId: sid,
        standbySince: t0 - 240_000,
        waiting: { mode: 'standby', detail: '等作者下一条 Mail 再续——已申报 delegate_report(standby)' } },
      // ③ 候合并对照行。
      'delegate-merge-3': { name: 'nebflow', kind: 'Delegate', sessionId: 'delegate-merge-3',
        status: 'Waiting', startedAt: t0 - 900_000,
        task: '候合并对照行（工作相完成，等合并窗口）', parentSessionId: sid },
      // ④ 活运行对照行。
      'delegate-running-4': { name: 'nebflow', kind: 'Delegate', sessionId: 'delegate-running-4',
        status: 'Processing', startedAt: t0 - 300_000, currentTool: 'Bash("sbt test")', parentSessionId: sid },
    };
    m.default.updateBgAgentIndicator();
  }), { sid: ROOT_SID });
}

const browser = await chromium.launch();

for (const colorScheme of ['light', 'dark']) {
  for (const mobile of [false, true]) {
    const label = `${mobile ? 'mobile-375' : 'desktop'}-${colorScheme}`;
    const { page, push, render } = await makePage(browser, colorScheme, mobile);
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
    await seedScene(page);
    await page.evaluate(() => document.getElementById('bgagent-indicator').click());
    await page.waitForTimeout(300);
    await render();

    // ── A/B：delegateFrozen 帧（引擎侧 BackoffSupervisor.freezeAndHold 的同形帧）──
    await push({
      type: 'delegateFrozen',
      sessionId: 'delegate-frozen-1',
      rootSessionId: ROOT_SID,
      agentId: 'delegate-frozen-1',
      frozenSince: Date.now() - 600_000,
      frozenReason: 'llm-failure',
      frozenReasonLabel: '模型层故障',
      frozenDetail: 'provider anthropic/claude-x: 529 overloaded - all candidates exhausted',
      wakeAddress: 'delegate:delegate-frozen-1',
      childAlive: true,
    });
    const a = await page.evaluate(() => {
      const c = document.getElementById('bg-dropdown');
      const row = c.querySelector('[data-node-session-id="delegate-frozen-1"]');
      const badge = row?.querySelector('.bg-task-frozen');
      const wake = row?.querySelector('.bg-task-wake');
      return {
        badge: badge?.textContent || '',
        tip: badge?.getAttribute('title') || '',
        wakeText: wake?.textContent || '',
        wakeTip: wake?.getAttribute('title') || '',
        waitingOnSameRow: row?.querySelector('.bg-task-waiting')?.textContent || '',
      };
    });
    ok(`${label}: A 冻结徽标带原因`, a.badge.includes('已冻结') && a.badge.includes('模型层故障'), `got: "${a.badge}"`);
    ok(`${label}: A tooltip 声明「不是失败判词」`, a.tip.includes('这不是失败判词'), `got: "${a.tip.slice(0, 80)}"`);
    ok(`${label}: A tooltip 带原因与错误原文`, a.tip.includes('原因：模型层故障') && a.tip.includes('529 overloaded'), `got: "${a.tip.slice(0, 160)}"`);
    ok(`${label}: A 有「唤醒」按钮`, a.wakeText.includes('唤醒'), `got: "${a.wakeText}"`);
    ok(`${label}: A 唤醒按钮 tooltip = 同会话续跑`, a.wakeTip.includes('在原会话上下文上继续'), `got: "${a.wakeTip}"`);
    ok(`${label}: B 冻结优先于 waiting（同行零 waiting 徽标）`, a.waitingOnSameRow === '', `got: "${a.waitingOnSameRow}"`);

    await page.screenshot({ path: join(OUT, `${label}-frozen-badge.png`), fullPage: false });

    // ── C：点唤醒 ⇒ 上行 delegateWake ──
    await page.evaluate(() => document.querySelector('[data-bg-wake="delegate-frozen-1"]')?.click());
    await page.waitForTimeout(300);
    const woke = await page.evaluate(() =>
      (window.__sentFrames || []).filter((f) => f && f.type === 'delegateWake'));
    ok(`${label}: C 点唤醒发出 delegateWake{sessionId}`, woke.length === 1 && woke[0].sessionId === 'delegate-frozen-1',
      `got: ${JSON.stringify(woke)}`);

    // ── D：唤醒成功回执 ⇒ 徽标撤掉（同会话续跑，行回普通进行中）──
    await push({ type: 'delegateWakeResult', ok: true, mode: 'live', sessionId: 'delegate-frozen-1',
      message: 'Woken in the SAME session' });
    const afterOk = await page.evaluate(() => {
      const c = document.getElementById('bg-dropdown');
      const row = c.querySelector('[data-node-session-id="delegate-frozen-1"]');
      return { badge: row?.querySelector('.bg-task-frozen')?.textContent || '',
        wake: row?.querySelector('.bg-task-wake')?.textContent || '' };
    });
    ok(`${label}: D 唤醒成功 ⇒ 徽标消失`, afterOk.badge === '' && afterOk.wake === '', `got: ${JSON.stringify(afterOk)}`);
    await page.screenshot({ path: join(OUT, `${label}-frozen-woken.png`), fullPage: false });

    // ── E：失败回执 ⇒ 徽标保留（再次冻结/再点唤醒）+ toast ──
    await push({
      type: 'delegateFrozen', sessionId: 'delegate-frozen-1', rootSessionId: ROOT_SID,
      frozenSince: Date.now(), frozenReason: 'tool-failure', frozenReasonLabel: '工具层故障',
      frozenDetail: 'tool execution crashed: BashTool pipeline abort', wakeAddress: 'delegate:delegate-frozen-1',
    });
    await push({ type: 'delegateWakeResult', ok: false, mode: 'revive-failed', sessionId: 'delegate-frozen-1',
      error: 'could not wake the frozen delegate: no task-ledger row (record + transcript are kept)' });
    const afterFail = await page.evaluate(() => {
      const c = document.getElementById('bg-dropdown');
      const row = c.querySelector('[data-node-session-id="delegate-frozen-1"]');
      return { badge: row?.querySelector('.bg-task-frozen')?.textContent || '',
        toast: document.querySelector('.toast, [class*="toast"]')?.textContent || '' };
    });
    ok(`${label}: E 唤醒失败 ⇒ 徽标保留（绝不静默丢冻结态）`, afterFail.badge.includes('已冻结'), `got: "${afterFail.badge}"`);
    ok(`${label}: E 唤醒失败 ⇒ toast 可见`, afterFail.toast.includes('唤醒') || afterFail.toast.length > 0, `got: "${afterFail.toast.slice(0, 80)}"`);
    await page.screenshot({ path: join(OUT, `${label}-frozen-wake-failed.png`), fullPage: false });

    // ── F：长期冻结提示（dormantSweep 帧）──
    await push({ type: 'delegateFrozenDormant', sessionId: 'delegate-frozen-1', rootSessionId: ROOT_SID,
      frozenSince: Date.now() - 30 * 3600_000, frozenReason: 'delegate-frozen:llm-failure',
      ageHours: 30, retentionHours: 336 });
    const dormant = await page.evaluate(() =>
      document.querySelector('[data-node-session-id="delegate-frozen-1"] .bg-task-frozen')?.getAttribute('title') || '');
    ok(`${label}: F 长期冻结 tooltip 带冻结龄`, dormant.includes('30 小时'), `got: "${dormant.slice(-80)}"`);
    await page.screenshot({ path: join(OUT, `${label}-frozen-dormant.png`), fullPage: false });

    // ── 对照行零回归 ──
    const ctrl = await page.evaluate(() => {
      const c = document.getElementById('bg-dropdown');
      const standby = c.querySelector('[data-node-session-id="delegate-standby-2"]');
      const merge = c.querySelector('[data-node-session-id="delegate-merge-3"]');
      const run = c.querySelector('[data-node-session-id="delegate-running-4"]');
      return {
        standby: standby?.querySelector('.bg-task-waiting')?.textContent || '',
        standbyFrozen: !!standby?.querySelector('.bg-task-frozen'),
        merge: merge?.querySelector('.bg-task-waiting')?.textContent || '',
        runFrozen: !!run?.querySelector('.bg-task-frozen'),
      };
    });
    ok(`${label}: 待命行保持「待命（等作者）」且无冻结徽标`, ctrl.standby.includes('待命') && !ctrl.standbyFrozen, `got: "${ctrl.standby}"`);
    ok(`${label}: 候合并行保持「等待合并轮」`, ctrl.merge.includes('等待合并轮'), `got: "${ctrl.merge}"`);
    ok(`${label}: 活运行行零冻结徽标`, !ctrl.runFrozen, `got: frozen=${ctrl.runFrozen}`);
    ok(`${label}: 零 console 错误`, errors.length === 0, errors.slice(0, 3).join(' | '));

    await page.screenshot({ path: join(OUT, `${label}-frozen-full-panel.png`), fullPage: false });
    page.close();
  }
}

// ── G/H：快照复原脸 + 非本族冻结零污染（桌面 light 单跑）──
{
  const { page, push, render } = await makePage(browser, 'light', false);
  await page.evaluate(({ sid }) => import('/js/state.js').then((m) => {
    m.default.sessionBgAgents[sid] = {};
  }), { sid: ROOT_SID });
  await push({
    type: 'activeAgents',
    agents: [
      // G：错误冻结行（刷新恢复面）——后端 activeAgentEntryJson 的同形行。
      { sessionId: 'delegate-frozen-1', agentId: 'delegate-frozen-1', agentName: 'nebflow', kind: 'Delegate',
        rootSessionId: ROOT_SID, status: 'Frozen', task: '错误冻结批 · 刷新恢复脸',
        startedAt: Date.now() - 3600_000, frozenSince: Date.now() - 900_000,
        frozenReason: 'delegate-frozen:llm-failure', wakeAddress: 'delegate:delegate-frozen-1' },
      // H：时间表冻结（非本族）——不得渲染错误冻结徽标。
      { sessionId: 'delegate-sched-9', agentId: 'delegate-sched-9', agentName: 'nebflow', kind: 'Delegate',
        rootSessionId: ROOT_SID, status: 'Frozen', task: '时间表冻结对照（非本族）',
        startedAt: Date.now() - 3600_000, frozenSince: Date.now() - 900_000, frozenReason: 'schedule' },
    ],
  });
  await page.evaluate(() => document.getElementById('bgagent-indicator').click());
  await page.waitForTimeout(300);
  await render();
  const snap = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    const fz = c.querySelector('[data-node-session-id="delegate-frozen-1"]');
    const sch = c.querySelector('[data-node-session-id="delegate-sched-9"]');
    const badge = fz?.querySelector('.bg-task-frozen');
    return {
      badge: badge?.textContent || '',
      tip: badge?.getAttribute('title') || '',
      wake: fz?.querySelector('.bg-task-wake')?.textContent || '',
      schedFrozen: !!sch?.querySelector('.bg-task-frozen'),
      rowCount: c.querySelectorAll('[data-node-session-id]').length,
    };
  });
  ok('G 快照复原：冻结行刷新后仍有徽标', snap.badge.includes('已冻结'), `got: "${snap.badge}"`);
  ok('G 快照复原：原因按线值本地映射（模型层故障）', snap.badge.includes('模型层故障'), `got: "${snap.badge}"`);
  ok('G 快照复原：唤醒按钮在场', snap.wake.includes('唤醒'), `got: "${snap.wake}"`);
  ok('H 非本族冻结（schedule）零错误冻结徽标', !snap.schedFrozen, `got: ${snap.schedFrozen}`);
  ok('行渲染非空（面板确有行）', snap.rowCount >= 2, `got: ${snap.rowCount}`);
  await page.screenshot({ path: join(OUT, 'desktop-light-snapshot-restore.png'), fullPage: false });
  page.close();
}

await browser.close();
console.log(failures === 0 ? 'ALL PASS' : `FAILURES: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
