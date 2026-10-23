// mobile-p6-bgstatus.spec.mjs — P6「运行状态」合并面板验收
//（mobile-secondary-screens 实施批；样张 v3.5 P6 + 开放点①②④按设计批推荐；
//  后台合并批 2026-10-23 ⑥ 更新：几何改**水平居中**（作者令 ⑤）+ **内容高
//  自适应紧凑化**（作者令 ⑥「手机端空白太多」，固定 560 高退役）；结构归并
//  上移桌面共享单点 bgMergedPanel.js，断言语义不变。）
//
// 样张→实机 DOM 映射（实施批口径）：
//   §8-1 盒模型 …… #bg-dropdown（合并载体）：left/right 0 + margin auto 居中 /
//        top 58+safe / 宽 308 / 高 = 内容高（max 560 兜底）/ r16。
//   在飞任务行 …… 桌面 .bg-task-row 全字段沿用：状态点 + 状态词（六档：
//        running/idle/stuck/cancelling/failed/done，卡死阈值 idle>10min 桌面
//        既有）+ origin chip + 本地·远程 kind chip + 行数 + 时长。
//   开放点① 两下拉归并 …… #bgagent-dropdown 幽灵面退场（chat.css 全局
//        display:none），列表节点由 bgMergedPanel.js 收编进载体
//        （载体桌面头「后台任务」= 第一分组题 + 「智能体」分组签）；bgagent 键
//        capture 拦截（bgStatusFace.js），点按自播二级液态 morph（手机层键列收回
//        后键不可二连击，收口 = 点面板外 —— 桌面 outside-close 同源，见 T3）。
//   开放点④ 远程 chip …… .bg-task-kind（bg.kind.remote = 远程）。
//   开放点③ 资源占用条 …… 后端无 CPU/内存/网络字段，不实施不伪造（见实施 spec）。
//
// 数据面 = WS activeBgTasks 快照 + state.sessionBgAgents 直种，零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-p6-bgstatus.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { settlePanel } from './_mobile-panel-settle.mjs';

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
    { taskId: 'job-2', description: '远端巡检任务', status: 'running', startedAt: T0 - 30_000, sessionId: SID, kind: 'remote', origin: 'dispatcher', originLabel: 'dispatcher' },
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
  await expect(page.locator('#bgagent-indicator')).toBeVisible();
  // 图标常驻键角标 = 运行中子智能体数（桌面 updateBgAgentIndicator 同源信号，
  // 只数运行行 —— 2026-09-14 author ruling 语义在手机层原样）。
  await expect(page.locator('#bgagent-indicator .bgagent-count')).toHaveText('1');
}

/** 开载体：后台键（桌面自身 toggle 路径）。 */
async function openViaBgKey(page) {
  await page.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await page.locator('#bg-indicator').click();
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await settlePanel(page, { overlaySel: '#bg-dropdown', panelSel: '#bg-dropdown', openClass: 'hidden' });
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

test('T1 merged panel geometry: centered 308×content-height (≤560), radius 16 (bg key path)', async ({ page }) => {
  const errors = watchErrors(page);
  await openViaBgKey(page);
  // 终态几何用 poll 采样（双读稳定性判据）：spring 过冲会在终值附近穿越
  // x=居中值/scale=1（单样可踩中），faceMo 重放也可能晚起第二场形变 —— 要求
  // 相隔 120ms 两次采样全等 ∧ transform 归位，才算真终态。
  const geo = () => page.evaluate(() => {
    const m = document.getElementById('bg-dropdown');
    const snap = () => {
      const r = m.getBoundingClientRect();
      return JSON.stringify({
        x: r.x, w: Math.round(r.width), h: Math.round(r.height),
        radius: getComputedStyle(m).borderRadius, tf: getComputedStyle(m).transform,
        vw: window.innerWidth,
      });
    };
    const a = snap();
    return new Promise((resolve) => setTimeout(() => {
      const b = snap();
      resolve(a === b ? JSON.parse(b) : { x: NaN });
    }, 120));
  });
  // 后台合并批 ⑥：居中（x = (vw-308)/2）+ 内容高自适应紧凑化（3 行 ≪ 560，
  // 面板不再撑满固定高 —— 「手机端空白太多」紧凑化断言）。
  await expect.poll(geo, { timeout: 8000 }).toEqual({
    x: (375 - 308) / 2, w: 308, h: expect.any(Number), radius: '16px', tf: 'none', vw: 375,
  });
  const h = (await page.locator('#bg-dropdown').boundingBox())?.height ?? 0;
  expect(h, 'compact: content-height, not fixed 560').toBeGreaterThan(80);
  expect(h).toBeLessThanOrEqual(560);
  // 幽灵面恒退场。
  expect(await page.locator('#bgagent-dropdown').evaluate((el) => getComputedStyle(el).display)).toBe('none');
  expect(errors).toEqual([]);
});

test('T2 task rows: status word / origin chip / local·remote kind chip / lines / uptime (open point ②④)', async ({ page }) => {
  const errors = watchErrors(page);
  await openViaBgKey(page);
  const rows = page.locator('#bg-dropdown > .bg-dropdown-list').first().locator('.bg-task-row');
  await expect(rows).toHaveCount(3);

  // 行 1：运行中 + Nebula origin + 本地 chip + 行数 + 时长。
  const r1 = rows.nth(0);
  await expect(r1.locator('.bg-task-state')).toHaveText('运行中');
  await expect(r1.locator('.bg-task-origin')).toHaveText('Nebula');
  await expect(r1.locator('.bg-task-kind')).toHaveText('本地');
  await expect(r1.locator('.bg-task-lines')).toHaveText('42行');
  await expect(r1.locator('.bg-task-uptime')).toContainText('m');

  // 行 2：远程 chip（开放点④）。
  await expect(rows.nth(1).locator('.bg-task-kind')).toHaveText('远程');

  // 行 3：卡死阈值沿用（idle>10min → 超过 10 分钟无输出）。
  await expect(rows.nth(2).locator('.bg-task-state')).toHaveText('超过 10 分钟无输出');

  // 分组题：载体桌面头 = 第一分组题（renderBgDropdown 写活计数），收编列表前一枚「智能体」签。
  await expect(page.locator('#bg-dropdown .bg-dropdown-header')).toHaveText('后台任务 · 3');
  const grps = page.locator('#bg-dropdown .mh-bg-grp');
  await expect(grps).toHaveCount(1);
  expect(await grps.nth(0).textContent()).toBe('智能体');
  // 手机小批（2026-10-21 图标常驻键）：键角标 = 后台任务计数（桌面 updateBgTasksUI
  // 同源信号 —— badge == 面板行数，桌面 §3 不变量在手机层同样成立）。
  await expect(page.locator('#bg-indicator')).toBeVisible();
  await expect(page.locator('#bg-indicator .bg-count')).toHaveText('3');
  expect(errors).toEqual([]);
});

test('T3 merge: bgagent key opens the CARRIER (ghost stays retired), outside-click closes, re-open keeps adoption', async ({ page }) => {
  const errors = watchErrors(page);
  await seedAgent(page);

  // 开：bgagent 键 → 载体开（幽灵面不落地）。
  await page.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await page.locator('#bgagent-indicator').click();
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await expect(page.locator('#bgagent-dropdown')).toBeHidden();
  await settlePanel(page, { overlaySel: '#bg-dropdown', panelSel: '#bg-dropdown', openClass: 'hidden' });

  // 收编断言：智能体分组签 + 子智能体行在载体内（列表节点 re-parent）。
  const agentsGrp = page.locator('#bg-dropdown .mh-bg-grp', { hasText: '智能体' });
  await expect(agentsGrp).toHaveCount(1);
  const adoptedList = page.locator('#bg-dropdown > .bg-dropdown-list').last();
  await expect(adoptedList.locator('.bg-task-row', { hasText: '调研子agent' })).toHaveCount(1);
  // 收编列表 = 幽灵面的原列表节点（identity 保持 → 桌面重渲命中）：其父 = 载体。
  expect(await adoptedList.evaluate((el) => el.parentElement?.id || '')).toBe('bg-dropdown');

  // 关（手机层真路径）：键列在键击后自动收回，键不可二连击；重开键列的
  // hd 点按目标在载体/后台键之外 → 桌面 outside-close（main.js document
  // bubble 处理器）收载体 —— 面板收口语义 = 桌面同源，零新增开合路径。
  // 注意：这记 hd 点按同时把键列重新展开（收列/开列只看列自身态），故
  // 下一步直接点键，不再补 hd（补一记会把列收回去，键被折叠裁掉不可点）。
  await page.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await expect(page.locator('#bg-dropdown')).toBeHidden();

  // 再开（回归：拦截 handler 无内部奇偶态 —— 恒自 classList 读态，开合
  // 循环无漂移；幽灵面恒退场、收编列表不回流）。
  await page.locator('#bgagent-indicator').click();
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await expect(page.locator('#bgagent-dropdown')).toBeHidden();
  await settlePanel(page, { overlaySel: '#bg-dropdown', panelSel: '#bg-dropdown', openClass: 'hidden' });
  await expect(adoptedList.locator('.bg-task-row', { hasText: '调研子agent' })).toHaveCount(1);
  expect(await adoptedList.evaluate((el) => el.parentElement?.id || '')).toBe('bg-dropdown');
  expect(errors).toEqual([]);
});

test('T4 empty drain: panel auto-hides when all tasks end (desktop auto-hide intact)', async ({ page }) => {
  const errors = watchErrors(page);
  await openViaBgKey(page);
  // 推空快照（activeBgTasks 同源路径）→ updateBgTasksUI 计数 0 自动隐藏载体。
  harness?.sendBg({});
  await expect(page.locator('#bg-dropdown')).toBeHidden({ timeout: 8000 });
  // 手机小批（2026-10-21 图标常驻键）：排空后键本体常驻（图标不退场）、角标
  // 隐藏（.hidden = 桌面 count-0 信号源，CSS 只藏角标 —— 零新轮询零新监听）。
  await expect(page.locator('#bg-indicator')).toBeVisible();
  await expect(page.locator('#bg-indicator .bg-count')).toBeHidden();
  await expect(page.locator('#bg-indicator svg.mh-persist-ico')).toBeVisible();
  expect(errors).toEqual([]);
});
