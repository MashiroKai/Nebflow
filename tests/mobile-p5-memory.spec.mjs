// mobile-p5-memory.spec.mjs — P5「记忆」读态卡 + 编辑态切换验收
//（mobile-secondary-screens 实施批；样张 v3.5 P5 + 开放点①②③按设计批推荐；
//  面板合并批 2026-10-23 ①③⑤ 更新：记忆面板并入「智能体+记忆」合并面板
//  （#memory-modal 双分区页签），手机记忆键退场 —— 入口 = header 头像 →
//  智能体分区 → 记忆页签；几何改水平居中（作者令 ⑤）。）
//
// 样张→实机 DOM 映射（实施批口径）：
//   §8-1 盒模型 …… #memory-modal：left/right 0 + margin auto 居中 / top 58+safe /
//        308×560 / r16。
//   scope seg 措辞（开放点②）…… .memory-tab 文案 =「智能体 · memory.md /
//        用户 · User.md」（新键 memory.scopeAgent/scopeUser；拆层还原桌面原文）。
//   条目卡（开放点①切分规则采纳）…… .mh-memory-card =「## 标题」分节切
//        （标题 + 摘录两行截断 + scope 文件名脚注）。
//   「编辑」钮（开放点③）…… .mh-memory-edit ⇄ 桌面原 textarea（不做移动
//        编辑器）；Save = 桌面 saveMemory 原样（WS saveMemory 帧）。
//   开放点④ 卡脚更新时间 …… 后端无 mtime 透出，不实施（脚注仅 scope 文件名）。
//
// 数据面 = WS 夹具（getMemory → memoryData，按 scope 给内容），零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-p5-memory.spec.mjs

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

const SID = 'p5-root-session';
const AGENT_MD = [
  '# 记忆',
  '',
  '项目背景速览。',
  '',
  '## 部署偏好',
  '优先使用 8111 端口启动开发服务器。',
  '',
  '## 代码风格',
  '使用中文注释。测试先行。',
].join('\n');
const USER_MD = '## 沟通偏好\n回复保持简短。';

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

async function bootApp(page) {
  /** @type {string[]} */
  const sentTypes = [];
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
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      sentTypes.push(String(parsed.type || ''));
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getMemory') {
        const content = parsed.scope === 'user' ? USER_MD : AGENT_MD;
        ws.send(JSON.stringify({ type: 'memoryData', scope: parsed.scope, content }));
      } else if (parsed.type === 'getHistory') {
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
  return { sentTypes };
}

/** 开面板（面板合并批 ①③ 后形态）：记忆键退场（display:none 守护断言）——
 *  入口 = header 头像 → 合并面板（智能体分区默认）→ 记忆页签 → 读态卡。 */
async function openPanel(page) {
  await expect(page.locator('#memory-btn')).toBeHidden();
  await page.locator('#agent-avatar-btn').click();
  await expect(page.locator('#memory-overlay')).toHaveClass(/on/);
  await expect(page.locator('#memory-modal')).toBeVisible();
  await expect(page.locator('#memory-modal')).toHaveAttribute('data-pane', 'agent');
  await settlePanel(page, { overlaySel: '#memory-overlay', panelSel: '#memory-modal' });
  // 顶层页签切到记忆分区 → 记忆 scope tabs + 读态卡。
  await page.locator('.merged-tab[data-pane="memory"]').click();
  await settlePanel(page, { overlaySel: '#memory-overlay', panelSel: '#memory-modal' });
  await expect(page.locator('.mh-memory-card').first()).toBeVisible();
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
/** @type {{sentTypes: string[]}|null} */
let harness = null;

test('T1 merged panel geometry: top-hung 308×560, centered, radius 16 (avatar path)', async ({ page }) => {
  const errors = watchErrors(page);
  // 开面板（停在智能体分区）验证居中盒模型；再切记忆分区验证读态默认。
  await expect(page.locator('#memory-btn')).toBeHidden();
  await page.locator('#agent-avatar-btn').click();
  await expect(page.locator('#memory-overlay')).toHaveClass(/on/);
  await settlePanel(page, { overlaySel: '#memory-overlay', panelSel: '#memory-modal' });
  const box = await page.locator('#memory-modal').boundingBox();
  expect(box).toBeTruthy();
  expect(box.x).toBe((375 - 308) / 2); // 面板合并批 ⑤：水平居中（原 left 6 退役）
  expect(box.width).toBe(308);
  expect(box.height).toBe(560);
  const radius = await page.locator('#memory-modal').evaluate((el) => getComputedStyle(el).borderRadius);
  expect(radius).toBe('16px');
  // 顶层双页签在场（合并面板注册形态）。
  await expect(page.locator('.merged-tab')).toHaveCount(2);
  await expect(page.locator('#memory-modal .ap-pane')).toBeVisible();
  // 记忆分区：读态默认 —— 卡显、textarea 隐。
  await page.locator('.merged-tab[data-pane="memory"]').click();
  await expect(page.locator('#mh-memory-cards')).toBeVisible();
  await expect(page.locator('#memory-content-input')).toBeHidden();
  expect(errors).toEqual([]);
});

test('T2 scope seg wording「智能体 · memory.md / 用户 · User.md」(open point ②)', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const tabs = page.locator('.memory-tab');
  await expect(tabs).toHaveCount(2);
  expect(await tabs.nth(0).textContent()).toBe('智能体 · memory.md');
  expect(await tabs.nth(1).textContent()).toBe('用户 · User.md');
  expect(errors).toEqual([]);
});

test('T3 entry cards parsed by「##」sections: title + excerpt + scope-file foot (open point ①)', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const cards = page.locator('.mh-memory-card');
  await expect(cards).toHaveCount(3); // 导言卡 + 两个 ## 节
  const titles = await cards.locator('.mh-memory-card-title').allTextContents();
  expect(titles).toEqual(['项目背景速览。', '部署偏好', '代码风格']);
  await expect(cards.nth(1).locator('.mh-memory-card-excerpt')).toHaveText('优先使用 8111 端口启动开发服务器。');
  await expect(cards.nth(1).locator('.mh-memory-card-foot')).toHaveText('memory.md');
  expect(errors).toEqual([]);
});

test('T4 edit toggle ⇄ existing editor; Save = desktop saveMemory WS frame (open point ③)', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);

  const editBtn = page.locator('.mh-memory-edit');
  await expect(editBtn).toHaveText('编辑');
  await editBtn.click();
  // 编辑态：桌面原 textarea + Save/Cancel 显；卡隐。
  await expect(page.locator('#memory-content-input')).toBeVisible();
  await expect(page.locator('#memory-content-input')).toHaveValue(AGENT_MD);
  await expect(page.locator('.mh-memory-cards')).toBeHidden();
  await expect(editBtn).toHaveText('完成');
  expect(await page.evaluate(() => document.body.classList.contains('mh-memory-editing'))).toBe(true);

  // 保存 = 桌面 saveMemory 原样（WS saveMemory 帧走同一路径）。
  await page.locator('#memory-modal-save').click();
  await expect.poll(() => harness?.sentTypes.includes('saveMemory'), { timeout: 5000 }).toBe(true);

  // 完成 → 回读态（按最新内容重切）。
  await editBtn.click();
  await expect(page.locator('#memory-content-input')).toBeHidden();
  await expect(page.locator('.mh-memory-cards')).toBeVisible();
  expect(await page.evaluate(() => document.body.classList.contains('mh-memory-editing'))).toBe(false);
  expect(errors).toEqual([]);
});

test('T5 scope switch: user tab → User.md content + foot, empty scope hint', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);

  await page.locator('.memory-tab[data-scope="user"]').click();
  await expect(page.locator('.memory-tab[data-scope="user"]')).toHaveClass(/active/);
  await expect(page.locator('.mh-memory-card')).toHaveCount(1);
  await expect(page.locator('.mh-memory-card-title')).toHaveText('沟通偏好');
  await expect(page.locator('.mh-memory-card-foot')).toHaveText('User.md');
  await expect(page.locator('.mh-memory-card-excerpt')).toHaveText('回复保持简短。');
  expect(errors).toEqual([]);
});
