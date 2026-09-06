// workspace-picker.spec.mjs — ProjectCreate「选择工作区」卡 + 应用内目录浏览器 E2E。
//
// 覆盖面（前端卡片层与弹窗层；后端为 sbt 编译/资产契约覆盖）：
//  - T1 卡片渲染：SVG 文件夹图标 + 「选择工作区」大目标 + 候选 chips 次级化；
//    点击（目标/卡片空白整体）→ 直接打开应用内目录浏览器（workspacePicker.js，
//    2026-09-06 作者拍板：复用文件浏览器「选择目录」设计 + 新建文件夹，不走系统对话框）
//  - T2 应用内浏览器弹窗：fixture 目录树导航 / 新建文件夹 / 选中确认 →
//    askUserAnswer 携带所选路径 + 卡片回显
//  - T3 弹窗取消 → 卡片回待选态（留再次选择/手输余地）
//
// 入站帧注入方式：捕获真实 ws.js 分发入口（state.ws.onmessage 原闭包），
// 之后以 FakeEvent 调用——走真实 GLOBAL/TERMINAL 路由 + onMessage 订阅链。
// 出站帧经 state.ws stub 捕获（askuser.spec.mjs 同款 harness）。
//
// 运行（隔离实例，绝不动 8080 宿主）：
//   java -jar <sbt-launch> "run --home /tmp/nb-ws-picker-home --port 8300 --no-browser"
//   BASE_URL=http://localhost:8300 NEBFLOW_HOME=/tmp/nb-ws-picker-home \
//     npx playwright test tests/workspace-picker.spec.mjs --browser=chromium
//   npx playwright test tests/workspace-picker.spec.mjs --browser=webkit

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8300';
const NEBFLOW_HOME = process.env.NEBFLOW_HOME ?? '/tmp/nb-ws-picker-home';

function defaultToken() {
  try {
    return JSON.parse(readFileSync(join(NEBFLOW_HOME, 'auth.json'), 'utf8'));
  } catch {
    return '';
  }
}
const TOKEN = process.env.NEBFLOW_TOKEN ?? defaultToken();

const SID = 'e2e-ws-picker-session';
const RID = 'req-wsp-1';

const DIR_PICK_ITEM = {
  question: 'ProjectCreate 需要项目工作区路径（E2E fixture 问题文案）',
  dirPicker: true,
  options: [{ label: '/tmp/ws-fixture/alpha' }, { label: '/tmp/ws-fixture/beta' }],
};

async function setup(page, item = DIR_PICK_ITEM, requestId = RID) {
  await page.goto(`${BASE_URL}/?token=${TOKEN}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(600);

  // 首跑 onboarding 向导拦截指针 —— 先关（askuser.spec.mjs 同款）
  const overlay = page.locator('.onboarding-overlay');
  if (await overlay.isVisible().catch(() => false)) {
    const dismiss = overlay.locator('#ob-skip, #ob-no');
    if (await dismiss.count()) {
      await dismiss.first().click();
      await overlay.waitFor({ state: 'detached', timeout: 3000 }).catch(() => {});
    }
  }

  await page.evaluate(() => localStorage.removeItem('nebflow_askuser_drafts'));
  await page.evaluate(() => {
    const c = document.getElementById('chat');
    return c ? Promise.resolve() : Promise.resolve();
  });

  await page.evaluate(async ({ item, requestId }) => {
    const { renderAskUser } = await import('/js/chat.js');
    const { chatViews, setActiveView } = await import('/js/chatView.js');
    const state = (await import('/js/state.js')).default;
    setActiveView(chatViews.primary);
    window.__captured = [];
    // 捕获真实分发入口（GLOBAL/TERMINAL 路由 + onMessage 订阅链），再替换出站
    window.__origOnMessage = state.ws && state.ws.onmessage ? state.ws.onmessage : null;
    state.ws = { readyState: 1, send: (data) => window.__captured.push(JSON.parse(data)) };
    renderAskUser([item], 'e2e-ws-picker-session', 'E2E', requestId);
  }, { item, requestId });

  // 应用内浏览器（workspacePicker.js）经 wsBrowse.list/mkdir（GLOBAL 路由）驱动，
  // 注入入站帧需真实 ws.js 分发入口已捕获（app boot 时已 import）。
  if (!(await page.evaluate(() => !!window.__origOnMessage))) {
    // 兜底：极冷页面 socket 尚未握上 → 直接从 ws.js 不可取；此时用重试式注入器
    throw new Error('real ws dispatch entry not captured — isolated instance WS not connected?');
  }
}

/** 注入入站帧（真实分发链）。 */
async function inject(page, msg) {
  await page.evaluate((m) => { window.__origOnMessage({ data: JSON.stringify(m) }); }, msg);
}

function lastCard(page) {
  return page.locator('.option-box').last();
}

// ============================================================
// T1 — 卡片渲染 + 点击出站
// ============================================================

test.describe('workspace-picker card', () => {
  test('T1 SVG 图标 + 选择工作区大目标；点击直接打开应用内目录浏览器；候选 chips 次级化', async ({ page }) => {
    await setup(page);
    const box = lastCard(page);
    const target = box.locator('.ws-pick-target');

    await expect(box.locator('.option-q')).toBeVisible();
    await expect(target).toBeVisible();
    // 内联 SVG 文件夹图标（禁 emoji 先例）：描边风格 path 存在
    await expect(target.locator('svg path').first()).toBeVisible();
    const svgBox = await target.locator('svg').boundingBox();
    expect(svgBox.height).toBeGreaterThanOrEqual(24); // 醒目大图标
    // 标题（zh 或 en 环境均可命中）
    await expect(target.locator('.ws-pick-title')).toHaveText(/选择工作区|Pick Workspace/);
    await expect(target.locator('.ws-pick-hint')).toContainText(/应用内目录浏览器|in-app folder browser/i);

    // 候选 chips 次级化：仍是 option-btn（机制不变）但带 demote 类
    const chips = box.locator('.option-btn.ws-pick-candidate');
    await expect(chips).toHaveCount(2);
    const chipBox = await chips.first().boundingBox();
    expect(chipBox.height).toBeLessThan(40); // 视觉弱化，非主体

    // 点击大目标 → 直接打开应用内目录浏览器（workspacePicker.js），发 wsBrowse.list 出站帧
    await target.click();
    await expect(page.locator('.wsp-overlay')).toBeVisible();
    const captured = await page.evaluate(() => window.__captured);
    expect(captured).toEqual([
      { type: 'wsBrowse.list', path: '~', sessionId: SID },
    ]);
    await expect(target).toHaveClass(/picking/); // 选择中态

    // 关闭弹窗（取消）→ 回待选态，再走手输路径兜底：Other… 输入 + 确认可独立作答
    await page.locator('.wsp-cancel').click();
    await expect(target).not.toHaveClass(/picking/);
    await box.locator('.option-btn', { hasText: /其他|Other/ }).last().click();
    await box.locator('.option-custom-input').fill('/tmp/manual-path');
    await box.locator('.option-confirm').click();
    const after = await page.evaluate(() => window.__captured);
    expect(after).toEqual([
      { type: 'wsBrowse.list', path: '~', sessionId: SID },
      { type: 'askUserAnswer', sessionId: SID, answers: ['/tmp/manual-path'], requestId: RID },
    ]);
  });

  // （旧 T2 path 事件 / T3 cancelled 事件随 workspaceDirPicked 移除已不适用：
  //  2026-09-06 作者拍板改为直接打开应用内目录浏览器，选中/取消全部经浏览器
  //  onPick/onCancel 驱动，见下方 T2/T3 浏览器流程测试。）

  // T2 — 应用内浏览器（导航/新建/选中全链）：点击目标直接打开
  test('T2 应用内浏览器弹窗：fixture 目录树导航+新建文件夹+选中回传', async ({ page }) => {
    await setup(page);
    const box = lastCard(page);
    await box.locator('.ws-pick-target').click();

    const overlay = page.locator('.wsp-overlay');
    await expect(overlay).toBeVisible();
    const panel = overlay.locator('.wsp-panel');
    await expect(panel.locator('.wsp-title')).toHaveText(/选择工作区|Pick Workspace/);

    // 首屏请求 = '~'（后端展开）；回 fixture 根
    const sendListFor = (path, entries) => inject(page, {
      type: 'wsBrowseList', path, home: '/Users/e2e', entries,
    });
    await expect.poll(() => page.evaluate(() => window.__captured.some(m => m.type === 'wsBrowse.list' && m.path === '~'))).toBe(true);
    await sendListFor('/Users/e2e', ['fixture-root']);

    // 点行进入 fixture-root
    await panel.locator('.wsp-row.wsp-dir', { hasText: 'fixture-root' }).click();
    await expect.poll(() => page.evaluate(() => window.__captured.some(m => m.type === 'wsBrowse.list' && m.path === '/Users/e2e/fixture-root'))).toBe(true);
    await sendListFor('/Users/e2e/fixture-root', ['ws-a', 'ws-b']);

    // 进入 ws-a
    await panel.locator('.wsp-row.wsp-dir', { hasText: 'ws-a' }).click();
    await expect.poll(() => page.evaluate(() => window.__captured.some(m => m.type === 'wsBrowse.list' && m.path === '/Users/e2e/fixture-root/ws-a'))).toBe(true);
    await sendListFor('/Users/e2e/fixture-root/ws-a', []);

    // 新建文件夹（行内输入，不弹 prompt）→ wsBrowse.mkdir
    await panel.locator('.wsp-mkdir').click();
    const mkdirRow = panel.locator('.wsp-mkdir-row');
    await expect(mkdirRow).toBeVisible();
    await mkdirRow.locator('.wsp-mkdir-input').fill('ws-new');
    await mkdirRow.locator('.wsp-mkdir-ok').click();
    await expect.poll(() => page.evaluate(() => window.__captured.some(m => m.type === 'wsBrowse.mkdir' && m.path === '/Users/e2e/fixture-root/ws-a' && m.name === 'ws-new'))).toBe(true);
    await inject(page, { type: 'wsBrowseMkdir', ok: true, path: '/Users/e2e/fixture-root/ws-a/ws-new' });
    // 创建成功 → 自动进入新目录（列表请求）
    await expect.poll(() => page.evaluate(() => window.__captured.some(m => m.type === 'wsBrowse.list' && m.path === '/Users/e2e/fixture-root/ws-a/ws-new'))).toBe(true);
    await sendListFor('/Users/e2e/fixture-root/ws-a/ws-new', []);

    // 选中此目录 → 弹窗关 + askUserAnswer 携带新建路径 + 卡片回显
    await panel.locator('.wsp-pick').click();
    await expect(overlay).toHaveCount(0);
    const captured = await page.evaluate(() => window.__captured);
    expect(captured).toContainEqual({
      type: 'askUserAnswer', sessionId: SID, answers: ['/Users/e2e/fixture-root/ws-a/ws-new'], requestId: RID,
    });
    await expect(box.locator('.ws-pick-echo')).toContainText('/Users/e2e/fixture-root/ws-a/ws-new');
    await expect(box.locator('.option-answer')).toContainText('/Users/e2e/fixture-root/ws-a/ws-new');
  });

  // T3 — 弹窗取消 → 卡片回待选态（留再次选择/手输余地）
  test('T3 弹窗取消 → 卡片回待选态', async ({ page }) => {
    await setup(page);
    const box = lastCard(page);
    const target = box.locator('.ws-pick-target');
    await target.click();

    const overlay = page.locator('.wsp-overlay');
    await expect(overlay).toBeVisible();
    await expect.poll(() => page.evaluate(() => window.__captured.some(m => m.type === 'wsBrowse.list'))).toBe(true);
    await overlay.locator('.wsp-cancel').click(); // 取消 = 回待选态
    await expect(overlay).toHaveCount(0);
    await expect(target).not.toHaveClass(/picking/);
    await expect(target.locator('.ws-pick-title')).toHaveText(/选择工作区|Pick Workspace/);
    const captured = await page.evaluate(() => window.__captured);
    expect(captured.filter(m => m.type === 'askUserAnswer')).toHaveLength(0);
  });
});
