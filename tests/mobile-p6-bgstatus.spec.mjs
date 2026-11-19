// mobile-p6-bgstatus.spec.mjs — P6「运行状态」合并面板验收
//（mobile-secondary-screens 实施批；样张 v3.5 P6 + 开放点①②④按设计批推荐；
//  后台合并批 2026-10-23 ⑥：结构归并上移桌面共享单点 bgMergedPanel.js；
//  dock 批换代 2026-10-29「各节目都是独立界面」：入口 = 设置根页工作台行
//  「后台任务」→ 独立整页（悬浮盒 308 居中/紧凑化判据随浮层形态退役，
//  换钉收编页面几何）；桌面图标键（#bg-indicator/#bgagent-indicator）在手机层
//  属退场桌面簇（.header-right display:none），键可见性断言随之退役 ——
//  计数读数改钉页内「后台任务 · N」头。）
//
// 样张→实机 DOM 映射（换代注记）：
//   §8-1 【换代】页面化盒模型 …… #bg-dropdown 收编进 .mobile-page-body：
//        全幅（w=375 / x=0 / radius 0）。
//   在飞任务行 …… 桌面 .bg-task-row 全字段沿用：状态点 + 状态词（六档：
//        running/idle/stuck/cancelling/failed/done，卡死阈值 idle>10min 桌面
//        既有）+ 设备名 chip + origin chip + 行数 + 时长。
//        （设备同级批 2026-11-01：原「本地·远程」kind chip 整对退役——本机任务
//        同样只标设备名，手机层与桌面同源同形。）
//   开放点① 两下拉归并 …… #bgagent-dropdown 幽灵面退场（chat.css 全局
//        display:none），列表节点由 bgMergedPanel.js 收编进载体
//        （载体桌面头「后台任务」= 第一分组题 + 「智能体」分组签）；收口 =
//        页面返回钮（closePageFace 置 hidden → CLOSE_WATCH 对账弹栈，见 T3）。
//   T2/T3 重锚 2026-10-30（存量红挂账清零批）：后台面板两分区换代
//        （8864ecb9c 面板分区批 2026-10-29 ③ · 作者报障「后台任务会和子智能体
//        混在一起」）—— 任务/智能体两列表各居 .bg-section-tasks /
//        .bg-section-agents section 内，不再做 #bg-dropdown 直子；分区签带
//        实时行数（n=0 时签 hidden；n>0 文案 = 「智能体 · N」）。
//   开放点④ 远程 chip …… 退役（设备同级批 2026-11-01「本地/远程这组徽标整对
//        退役」）：远端任务改由设备名 chip 表达（.bg-origin-device）。
//   开放点③ 资源占用条 …… 后端无 CPU/内存/网络字段，不实施不伪造（见实施 spec）。
//
// 数据面 = WS activeBgTasks 快照 + state.sessionBgAgents 直种，零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-p6-bgstatus.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { openHubPage } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'p6-root-session';
const T0 = Date.now();

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

function bgTasksFixture() {
  return { [SID]: [
    { taskId: 'job-1', description: '长跑构建任务', status: 'running', startedAt: T0 - 90_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 5_000, outputLines: 42 } },
    { taskId: 'job-2', description: '[DESKTOP-IJBBEOT] 远端巡检任务', status: 'running', startedAt: T0 - 30_000, sessionId: SID, kind: 'remote', origin: 'device', originLabel: 'DESKTOP-IJBBEOT' },
    { taskId: 'job-3', description: '卡死的长任务', status: 'running', startedAt: T0 - 900_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 700_000, outputLines: 7 } },
  ] };
}

async function bootApp(page) {
  /** routeWebSocket 回调内的 ws 发帧句柄，桥出到 bootApp 返回值（回调外 ws 不可见）。 */
  let liveSendBg = null;
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
      // 设备同级批（2026-11-01）：本机任务行设备 chip 读本机设备名（第二源）。
      selfDevice: { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro-iso-a659864a' },
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    const sendBg = (tasks) => ws.send(JSON.stringify({ type: 'activeBgTasks', tasks }));
    liveSendBg = sendBg;
    sendConfig();
    sendSessions();
    sendBg(bgTasksFixture());
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  return { sendBg: (tasks) => liveSendBg?.(tasks) };
}

/** 种一个可见子智能体（renderBgAgentDropdown 数据面直种 + 指标刷新）。 */
async function seedAgent(page) {
  await page.evaluate((sid) => {
    import('/js/state.js').then((m) => {
      m.default.sessionBgAgents[sid] = {
        'subtask-agent-1': {
          name: '调研子agent', kind: 'Delegate', sessionId: 'subtask-agent-1',
          status: 'running', startedAt: Date.now() - 60_000,
        },
      };
      m.default.updateBgAgentIndicator();
    });
  }, SID);
  // dock 批换代：桌面图标键（#bgagent-indicator）属手机层退场簇 —— 键可见性
  // 断言退役；种子判据 = 运行中子智能体已写入 state.sessionBgAgents（桌面合并
  // 面板的数据源；载体开时 navController 补跑 renderBgAgentDropdown 落行）。
  const seeded = await page.evaluate(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return Object.keys(s.sessionBgAgents?.[sid] || {}).length;
  }, SID);
  expect(seeded).toBe(1);
}

/** 开载体：设置根页工作台行「后台任务」→ push 页（面收编进页面体）。 */
async function openViaHub(page) {
  await openHubPage(page, 'background');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test.beforeEach(async ({ page }) => {
  harness = await bootApp(page);
});
/** @type {{sendBg: (tasks: any) => void}|null} */
let harness = null;

test('T1 page geometry: adopted full-bleed in background page, ghost retired (dock 批换代判据)', async ({ page }) => {
  const errors = watchErrors(page);
  await openViaHub(page);
  // 收编页面几何 poll（滑入 transform 归位后采样）。
  const geo = () => page.evaluate(() => {
    const m = document.getElementById('bg-dropdown');
    const r = m.getBoundingClientRect();
    return { x: Math.round(r.x), w: Math.round(r.width), radius: getComputedStyle(m).borderRadius, vw: window.innerWidth };
  });
  await expect.poll(geo, { timeout: 8000 }).toEqual({
    x: 0, w: 375, radius: '0px', vw: 375,
  });
  // 幽灵面恒退场。
  expect(await page.locator('#bgagent-dropdown').evaluate((el) => getComputedStyle(el).display)).toBe('none');
  expect(errors).toEqual([]);
});

test('T2 task rows: status word / device-name chip / no local·remote kind chip / lines / uptime (open point ②)', async ({ page }) => {
  const errors = watchErrors(page);
  await openViaHub(page);
  // 面板分区批换代：任务列表居 .bg-section-tasks 内（非 #bg-dropdown 直子）。
  const rows = page.locator('#bg-dropdown .bg-section-tasks .bg-dropdown-list').first().locator('.bg-task-row');
  await expect(rows).toHaveCount(3);

  // 行 1：运行中 + 本机设备名 chip + Nebula origin + 行数 + 时长。
  const r1 = rows.nth(0);
  await expect(r1.locator('.bg-task-state')).toHaveText('运行中');
  // 设备同级批：本机任务同样标设备名（剥 -iso 后缀），不再有「本地」字样。
  await expect(r1.locator('.bg-origin-device')).toHaveText('Mashiros-MacBook-Pro');
  await expect(r1.locator('.bg-origin-nebula')).toHaveText('Nebula');
  await expect(r1.locator('.bg-task-kind')).toHaveCount(0);
  await expect(r1.locator('.bg-task-lines')).toHaveText('42行');
  await expect(r1.locator('.bg-task-uptime')).toContainText('m');

  // 行 2：远端任务 = 对端设备名（同档同类，无「远程」字样）。
  await expect(rows.nth(1).locator('.bg-origin-device')).toHaveText('DESKTOP-IJBBEOT');
  await expect(rows.nth(1).locator('.bg-task-kind')).toHaveCount(0);

  // 行 3：卡死阈值沿用（idle>10min → 超过 10 分钟无输出）。
  await expect(rows.nth(2).locator('.bg-task-state')).toHaveText('超过 10 分钟无输出');

  // 分组题：任务节头「后台任务 · N」（renderBgDropdown 写活计数）。
  await expect(page.locator('#bg-dropdown .bg-dropdown-header')).toHaveText('后台任务 · 3');
  // 智能体节：本夹具未种子智能体 ⇒ 空节收签（签 hidden，n=0 无「· N」后缀）。
  const agentsSec = page.locator('#bg-dropdown .bg-section-agents');
  await expect(agentsSec).toHaveCount(1);
  const grp = agentsSec.locator('.mh-bg-grp');
  await expect(grp).toHaveCount(1);
  await expect(grp).toBeHidden();
  expect(await grp.textContent()).toBe('智能体');
  // dock 批换代：桌面图标键（#bg-indicator）属手机层退场簇 —— 键可见性断言
  // 退役；计数读数 = 页内「后台任务 · 3」头（上行已断言）。
  expect(errors).toEqual([]);
});

test('T3 merge: agents group rides the CARRIER page (ghost stays retired), back-out closes, re-open keeps adoption', async ({ page }) => {
  const errors = watchErrors(page);
  await seedAgent(page);

  // 开：工作台行「后台任务」→ 载体开（幽灵面不落地；子智能体已种 → 智能体
  // 分组随桌面合并逻辑同场建出）。
  await openViaHub(page);
  await expect(page.locator('#bgagent-dropdown')).toBeHidden();

  // 收编断言：智能体分组签 + 子智能体行在载体内（列表节点 re-parent 进
  // .bg-section-agents；分区签带实时行数「智能体 · 1」）。
  const agentsSec = page.locator('#bg-dropdown .bg-section-agents');
  await expect(agentsSec).toHaveCount(1);
  const grp = agentsSec.locator('.mh-bg-grp');
  await expect(grp).toHaveCount(1);
  await expect(grp).toBeVisible();
  expect(await grp.textContent()).toBe('智能体 · 1');
  const adoptedList = agentsSec.locator('.bg-dropdown-list');
  await expect(adoptedList.locator('.bg-task-row', { hasText: '调研子agent' })).toHaveCount(1);
  // 收编列表 = 幽灵面的原列表节点（identity 保持 → 桌面重渲命中）：其节 = 载体
  // 内智能体分区（列表不再做 #bg-dropdown 直子）。
  expect(await adoptedList.evaluate((el) => el.parentElement?.className || '')).toBe('bg-section bg-section-agents');

  // 关（dock 批真路径）：页面返回钮 → closePageFace 置 hidden → CLOSE_WATCH
  // 对账弹栈（回工作台根页）—— 收口语义 = 页面栈同源，零新增开合路径。
  await page.locator('.mobile-page[data-page="background"] .mobile-back-btn').click();
  await expect(page.locator('#bg-dropdown')).toBeHidden();
  await expect(page.locator('.mobile-page[data-page="background"]')).not.toHaveClass(/live/);

  // 再开（回归：拦截 handler 无内部奇偶态 —— 恒自 classList 读态，开合
  // 循环无漂移；幽灵面恒退场、收编列表不回流）。
  await openViaHub(page);
  await expect(page.locator('#bgagent-dropdown')).toBeHidden();
  await expect(adoptedList.locator('.bg-task-row', { hasText: '调研子agent' })).toHaveCount(1);
  expect(await adoptedList.evaluate((el) => el.parentElement?.className || '')).toBe('bg-section bg-section-agents');
  expect(errors).toEqual([]);
});

test('T4 empty drain: page pops when all tasks end (desktop auto-hide intact, dock 批换代)', async ({ page }) => {
  const errors = watchErrors(page);
  await openViaHub(page);
  // 推空快照（activeBgTasks 同源路径）→ updateBgTasksUI 计数 0 自动隐藏载体
  // → CLOSE_WATCH 对账弹栈（回工作台根页）。
  harness?.sendBg({});
  await expect(page.locator('#bg-dropdown')).toBeHidden({ timeout: 8000 });
  await expect(page.locator('.mobile-page[data-page="background"]')).not.toHaveClass(/live/);
  expect(await page.evaluate(() => window.__nfMobileNav?.top)).toBe('workbench');
  // dock 批换代：桌面图标键角标断言退役（键属手机层退场簇）—— 排空判据 =
  // 载体 hidden + 栈弹回工作台根页（上行）。
  expect(errors).toEqual([]);
});
