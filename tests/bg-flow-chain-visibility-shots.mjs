// bg-flow-chain-visibility-shots.mjs — Flow 会话可见性批（2026-11-01）实拍证据。
//
// 作者令四条之④：深浅双主题 × 双引擎（Chromium + WebKit）×「有活链 + 全终态」
// 两组场景，全程零 console error 审计。数据面 = WS 帧直种 + delegateChainStub
// 夹具（与 bg-flow-chain-visibility.spec.mjs 同一注入形状——验收与实拍同源）。
//
// 场景：
//   A 活链（延续态）……delegate 注册镜像 completed + 作用域桶快照链行（dispatcher
//     Idle + node Processing ×2）→ 合成延续组头「链运行中」（琥珀）+ 分发器/节点
//     子行 + 徽标计数 4（快照回填路径刷新——自动路径实证）。
//   B1 链全终态（恢复普通终态样式）……live delegate 行 + 链行 → delegate agentDone
//     + 链会话级 done ×2 → 组头回「已完成」灰化普通终态（延续徽标退场）。
//   B2 面板回空（徽标回收）……过 agentDone 2s 清理窗 → 主桶空 + 作用域桶空 →
//     面板收起 + 徽标隐藏（死链零滞留）。
//
// Run: node tests/bg-flow-chain-visibility-shots.mjs
// 落: evidence/<批目录>/shots/<engine>-<theme>-<scene>.png

import { chromium, webkit } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const BATCH = '20261101_161500_bg-flow-chain-visibility__delegate-nebflow-73346fa5';
const OUT = join(HERE, '..', 'evidence', BATCH, 'shots');
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'fcv-root-session';
const DELEGATE_SID = 'delegate-flow-a1b2c3d4';
const T0 = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

/** boot（与验收 spec 同构：路由 mock + WS 存根 + SW/agent.json 探测关注册）。 */
async function makePage(browserType, theme) {
  const browser = await browserType.launch();
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    colorScheme: theme,
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0'); // SW 逃生门：预取不经 page.route
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/agents/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'x' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (m.type === 'getSessions' || m.type === 'listSessions') ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  // 端口 8931（非受限端口——WebKit 网络进程拒 <1024 端口，Chromium 无碍；路由
  // 全 mock，host 任意）。
  await page.goto('http://127.0.0.1:8931/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 20000 });
  await page.waitForTimeout(400);
  return { browser, page, errors };
}

function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

async function setFixture(page, fx) {
  await page.evaluate(async (f) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
  }, fx);
}

/** 作用域桶快照链行（activeAgents 快照恢复路径的真实形状——刷新场景）。 */
function chainSnapshot() {
  return {
    type: 'activeAgents',
    agents: [
      { sessionId: 'dispatcher-20b0e8ea', agentId: 'dispatcher-20b0e8ea', agentName: 'dispatcher/Nebflow', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Idle', startedAt: T0 - 12 * 60_000, project: 'Nebflow', parentSessionId: '' },
      { sessionId: 'node-7846cc03', agentId: 'node-7846cc03', agentName: '侦察节点', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 9 * 60_000, project: 'Nebflow', parentSessionId: '', task: '只读侦察：盘点回流卡消费点' },
      { sessionId: 'node-eb2a5aa3', agentId: 'node-eb2a5aa3', agentName: '实现节点', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 6 * 60_000, project: 'Nebflow', parentSessionId: '', task: '实现：回流卡收编（含 wire 探针）' },
    ],
  };
}

const LIST = '#bg-dropdown .bg-section-agents .bg-dropdown-list';

async function auditActiveChain(page, tag) {
  const a = await page.evaluate(({ sid }) => {
    const list = document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list');
    const rows = list ? [...list.querySelectorAll(':scope > .bg-task-row')] : [];
    const head = rows.find((r) => r.classList.contains('bg-task-row-head'));
    const kids = rows.filter((r) => r.classList.contains('bg-task-row-child'));
    return {
      badge: document.querySelector('#bgagent-indicator .bgagent-count')?.textContent || '',
      headState: head?.querySelector('.bg-task-state')?.textContent || '',
      headChain: !!head?.querySelector('.bg-task-state.bg-task-chain'),
      headMode: head?.querySelector('.bg-task-kind')?.textContent || '',
      kidKinds: kids.map((r) => r.querySelector('.bg-task-kind')?.textContent || ''),
      kidCount: kids.length,
      headPad: head ? parseFloat(getComputedStyle(head).paddingLeft) : -1,
      kidPad: kids[0] ? parseFloat(getComputedStyle(kids[0]).paddingLeft) : -1,
      sectionLabel: document.querySelector('#bg-dropdown .bg-section-agents .mh-bg-grp')?.textContent || '',
      sid,
    };
  }, { sid: ROOT_SID });
  ok(`A badge=4 (${tag})`, a.badge === '4', a.badge);
  ok(`A head 链运行中 amber (${tag})`, a.headState === '链运行中' && a.headChain, a.headState);
  ok(`A head mode=dispatcher (${tag})`, a.headMode === 'dispatcher', a.headMode);
  ok(`A kids=3 分发器置顶 (${tag})`, a.kidCount === 3 && a.kidKinds[0] === '分发器' && a.kidKinds[1] === '节点', a.kidKinds.join('|'));
  ok(`A child indent (${tag})`, a.kidPad > a.headPad, `${a.kidPad} > ${a.headPad}`);
  ok(`A section label · 4 (${tag})`, a.sectionLabel.includes('· 4'), a.sectionLabel);
}

async function auditTerminalStyle(page, tag) {
  const a = await page.evaluate(() => {
    const list = document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list');
    const rows = list ? [...list.querySelectorAll(':scope > .bg-task-row')] : [];
    const head = rows.find((r) => r.classList.contains('bg-task-row-head'));
    return {
      headState: head?.querySelector('.bg-task-state')?.textContent || '',
      headChain: !!head?.querySelector('.bg-task-chain'),
      headDoneCls: head ? /done/.test(head.className) : false,
      kids: rows.filter((r) => r.classList.contains('bg-task-row-child')).length,
    };
  });
  ok(`B1 head 已完成普通终态 (${tag})`, a.headState === '已完成' && !a.headChain && a.headDoneCls, `${a.headState} chain=${a.headChain} doneCls=${a.headDoneCls}`);
}

async function auditDrained(page, tag) {
  const a = await page.evaluate(() => ({
    badgeHidden: document.getElementById('bgagent-indicator')?.classList.contains('hidden'),
    rows: document.querySelectorAll('#bg-dropdown .bg-section-agents .bg-dropdown-list .bg-task-row').length,
  }));
  // 口径对齐验收 spec T4（权威面）：行集回空 + 徽标回收。合并载体 count=0 清空
  // 列表（面板元素留存——空态自动收起 = 列表留白，既有批语义）。
  ok(`B2 badge 回收 (${tag})`, a.badgeHidden === true, String(a.badgeHidden));
  ok(`B2 面板回空 (${tag})`, a.rows === 0, `rows=${a.rows}`);
}

async function assertClean(errors, tag) {
  ok(`console 零错误 (${tag})`, errors.length === 0, errors.slice(0, 3).join(' ‖ '));
}

for (const [engineName, browserType] of [['chromium', chromium], ['webkit', webkit]]) {
  for (const theme of ['dark', 'light']) {
    const tag = `${engineName}-${theme}`;

    // ── 场景 A：活链（延续态）─────────────────────────────────────────
    {
      const { browser, page, errors } = await makePage(browserType, theme);
      await setFixture(page, { rows: [{ taskNo: '91', delegateId: DELEGATE_SID, title: '分发器链批次——回流卡收编实施', name: '链批', executor: 'nebflow', status: 'completed', external: false, project: '', createdAt: T0 - 66 * 60_000, orchestrationMode: 'agentflow', mode: 'dispatcher', archivedAt: null, mergeQueue: null, mergeQueuePos: null }] });
      await inject(page, chainSnapshot());
      await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
      await page.click('#bgagent-indicator');
      await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
      await page.waitForTimeout(400);
      await auditActiveChain(page, tag);
      await assertClean(errors, `${tag}-A`);
      await page.screenshot({ path: join(OUT, `${tag}-A-active-chain.png`), fullPage: false });
      await browser.close();
    }

    // ── 场景 B：链全终态（B1 恢复普通终态样式 → B2 面板回空徽标回收）────
    {
      const { browser, page, errors } = await makePage(browserType, theme);
      await setFixture(page, { rows: [] });
      // live delegate 行 + 链行（live 帧路径——agentStart 三键同构）。
      await inject(page, { type: 'agentStart', sessionId: ROOT_SID, rootSessionId: ROOT_SID, agentId: DELEGATE_SID, nodeSessionId: DELEGATE_SID, name: 'Explorer', taskDescription: '分发器链批次——回流卡收编实施' });
      await inject(page, { type: 'agentStart', sessionId: DELEGATE_SID, rootSessionId: DELEGATE_SID, agentId: 'dispatcher-20b0e8ea', nodeSessionId: 'dispatcher-20b0e8ea', name: 'dispatcher/Nebflow', taskDescription: '', project: 'Nebflow' });
      await inject(page, { type: 'agentStart', sessionId: DELEGATE_SID, rootSessionId: DELEGATE_SID, agentId: 'node-7846cc03', nodeSessionId: 'node-7846cc03', name: '侦察节点', taskDescription: '只读侦察：盘点回流卡消费点', project: 'Nebflow' });
      await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
      await page.click('#bgagent-indicator');
      await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
      await page.waitForTimeout(300);
      // 链全终态：delegate agentDone（终态置位）+ 链行会话级 done（立即删行）。
      await inject(page, { type: 'agentDone', sessionId: ROOT_SID, rootSessionId: ROOT_SID, agentId: DELEGATE_SID, nodeSessionId: DELEGATE_SID });
      await inject(page, { type: 'done', sessionId: 'node-7846cc03' });
      await inject(page, { type: 'done', sessionId: 'dispatcher-20b0e8ea' });
      await page.waitForTimeout(400);
      await auditTerminalStyle(page, tag);
      await page.screenshot({ path: join(OUT, `${tag}-B1-chain-terminal-style.png`), fullPage: false });
      // 过 agentDone 2s 清理窗：主桶空 + 作用域桶空 → 面板收起 + 徽标隐藏。
      await page.waitForTimeout(2300);
      await auditDrained(page, tag);
      await assertClean(errors, `${tag}-B`);
      await page.screenshot({ path: join(OUT, `${tag}-B2-drained-badge-recycled.png`), fullPage: false });
      await browser.close();
    }
  }
}

console.log(`shots + audit done → ${OUT}`);
process.exit(failures ? 1 : 0);
