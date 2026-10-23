// bg-merged-panel.spec.mjs — 后台合并面板验收（作者令 2026-10-23 ⑥：
// 后台任务 + 后台子智能体两面板归并为**一个后台面板**，分节组织，桌面端同步受益）。
//
// 桌面形态断言：
//   · 结构归并 …… bgMergedPanel.js（main.js 组装根一次，视口无关）：子智能体
//     列表节点 re-parent 进 #bg-dropdown（「智能体」分组签下）；幽灵面
//     #bgagent-dropdown 恒 display:none（节点保留作状态载体）。
//   · 双键同面板 …… bg 键与 bgagent 键都开 #bg-dropdown；面板分两节
//     （任务节 = 既有 renderBgDropdown 行，智能体节 = 既有 renderBgAgentDropdown
//     两级行），取数/渲染/事件路径零重造。
//   · 空节收签 …… 子智能体 0 行 → 「智能体」签隐藏（头部计数语言同源）。
//   · Esc/外点关闭沿用桌面既有路径。
//
// 数据面 = WS activeBgTasks 快照 + state.sessionBgAgents 直种，零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/bg-merged-panel.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'bgm-root-session';
const T0 = Date.now();

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
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
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.send(JSON.stringify({ type: 'activeBgTasks', tasks: { [ROOT_SID]: [
      { taskId: 'job-1', description: '合并面板样例任务', status: 'running', startedAt: T0 - 45_000, sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 4_000, outputLines: 9 } },
    ] } }));
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 种一个可见子智能体并刷新指示器（renderBgAgentDropdown 数据面直种）。 */
async function seedAgent(page) {
  await page.evaluate((sid) => {
    import('/js/state.js').then((m) => {
      m.default.sessionBgAgents[sid] = {
        'delegate-bgm-1': {
          name: '合并样例子agent', kind: 'Delegate', sessionId: 'delegate-bgm-1',
          status: 'running', startedAt: Date.now() - 30_000,
        },
      };
      m.default.updateBgAgentIndicator();
    });
  }, ROOT_SID);
  await expect(page.locator('#bgagent-indicator')).toBeVisible();
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('T1 merge structure: agent list adopted into the carrier, ghost retired (desktop)', async ({ page }) => {
  const errors = watchErrors(page);
  await seedAgent(page);

  // 结构（boot 即成，无需开面板）：收编列表父 = 载体；「智能体」签在场；
  // 幽灵面 display:none；幽灵面列表节点被搬空（收编 = move，不是复制）。
  const structure = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    const g = document.getElementById('bgagent-dropdown');
    const lists = c.querySelectorAll(':scope > .bg-dropdown-list');
    return {
      // 收编 = move（appendChild）：幽灵面内不再有列表节点；载体尾列表即原节点。
      ghostStillHasList: !!g.querySelector('.bg-dropdown-list'),
      carrierListCount: lists.length,
      carrierLastIsList: lists.length ? lists[lists.length - 1].className.includes('bg-dropdown-list') : false,
      grpText: c.querySelector('.mh-bg-grp')?.textContent || '',
      ghostDisplay: getComputedStyle(g).display,
      carrierDisplayFlex: getComputedStyle(c).display === 'flex',
      carrierMaxHeight: getComputedStyle(c).maxHeight,
    };
  });
  expect(structure.ghostStillHasList).toBe(false);
  expect(structure.carrierListCount).toBe(2); // 任务节 + 智能体节
  expect(structure.carrierLastIsList).toBe(true);
  expect(structure.grpText).toBe('智能体');
  expect(structure.ghostDisplay).toBe('none');
  expect(errors).toEqual([]);

  // 布局（开面板后才有盒）：flex column + max-height 收口（紧凑化）。
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  const layout = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    return {
      displayFlex: getComputedStyle(c).display === 'flex',
      maxHeight: getComputedStyle(c).maxHeight,
    };
  });
  expect(layout.displayFlex).toBe(true);
  expect(layout.maxHeight).not.toBe('none');
});

test('T2 both header keys open the ONE panel; both sections render; Esc closes', async ({ page }) => {
  const errors = watchErrors(page);
  await seedAgent(page);

  // bg 键 → 面板开（任务节 1 行 + 智能体节 1 行，两节同面板）。
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  const taskRows = page.locator('#bg-dropdown > .bg-dropdown-list').first().locator('.bg-task-row');
  await expect(taskRows).toHaveCount(1);
  await expect(taskRows.first()).toContainText('合并面板样例任务');
  const agentRows = page.locator('#bg-dropdown > .bg-dropdown-list:last-child .bg-task-row');
  await expect(agentRows).toHaveCount(1);
  await expect(agentRows.first()).toContainText('合并样例子agent');
  await expect(page.locator('#bg-dropdown .bg-dropdown-header')).toHaveText('后台任务 · 1');
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toBeVisible();

  // Esc → 关（桌面既有 Esc 链：幽灵面（恒隐）跳过 → 载体收）。
  await page.keyboard.press('Escape');
  await expect(page.locator('#bg-dropdown')).toHaveClass(/hidden/);

  // bgagent 键 → 同一面板再开（幽灵面不落地）。
  await page.click('#bgagent-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await expect(page.locator('#bgagent-dropdown')).toBeHidden();
  await expect(agentRows).toHaveCount(1);
  await expect(page.locator('#bgagent-indicator')).toHaveAttribute('aria-expanded', 'true');

  // 外点 → 关（桌面 outside-close 同源）。
  await page.mouse.click(10, 400);
  await expect(page.locator('#bg-dropdown')).toHaveClass(/hidden/);
  expect(errors).toEqual([]);
});

test('T3 empty agents section hides its group label; panel stays for tasks (merged semantics)', async ({ page }) => {
  const errors = watchErrors(page);
  // 零子智能体：开面板 → 「智能体」签隐藏，任务节照常。
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toBeHidden();
  await expect(page.locator('#bg-dropdown > .bg-dropdown-list').first().locator('.bg-task-row')).toHaveCount(1);

  // 种入子智能体 → 签即现（MutationObserver 空节收签的反向腿）。
  await seedAgent(page);
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toBeVisible();
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toHaveText('智能体');
  expect(errors).toEqual([]);
});
