// mobile-popup-retire.spec.mjs — 弹窗退场批（作者令 2026-10-31 三件）验收：
//   ① 检索命中 → 主会话跳转定位（桌面腿 pr41/tmp-msg-jump-verify 已守；本件
//      守手机层腿：关搜索面回聊天根 + #chat 闪击带）。
//   ② 手机端弹窗全清：点名面（LLM 配置 /model 面板、后台任务输出卡）+ 顺带面
//      （secrets / workspacePicker / 通用确认 / 长按菜单）一律「铺满屏 + 左上
//      返回」；桌面同弹层零变化（.mfs-back 隐藏、形态不拉伸）。
//   ③ 设置页展开式配置退役：STT/TTL/JeV 三入口行点击 push 层级子页（折叠体
//      节点收编进子页面体，设置页内 display:none）。
//
// Run: playwright test tests/mobile-popup-retire.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'mpr-root-session';
const NOW = Date.now();
const HISTORY = [
  { type: 'user', text: 'hello omega seed', timestamp: NOW - 3600000 },
  { type: 'ai', text: 'omega reply text', timestamp: NOW - 3500000 },
];

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

async function bootApp(page, colorScheme = 'light') {
  await page.emulateMedia({ colorScheme });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (url.pathname === '/favicon.ico') {
      return route.fulfill({ status: 200, contentType: 'image/x-icon', body: '' });
    }
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p.startsWith(`/api/sessions/${SID}/history`)) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: HISTORY, total: HISTORY.length, sessionId: SID }) });
      }
      // modelPanel 角色读数（钻入守卫用）。
      if (/\/api\/agents\/[^/]+\/model$/.test(p)) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ mode: 'follow', chain: null, effectiveChain: [] }) });
      }
      if (p.startsWith('/api/secrets')) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ secrets: [] }) });
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
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: HISTORY, hasMore: false, offset: HISTORY.length, total: HISTORY.length }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

const backBtn = (scope) => scope.locator(':scope > .mfs-back');

// ── ② 点名面：/model LLM 配置面板 = 铺满屏 + 左上返回 + 返回关面板 ──────────
test('T1 /model 面板手机层铺满屏 + 左上返回（返回=关面板）；角色头下钻/返回列表', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  await page.evaluate(async () => {
    const m = await import('/js/modelPanel.js');
    await m.openModelPanel();
  });
  const overlay = page.locator('#model-panel-overlay');
  await expect(overlay).toBeVisible();
  // 铺满屏：内盒全幅、圆角 0、承接层不透明（页面底）。
  const geo = await page.evaluate(() => {
    const ov = document.getElementById('model-panel-overlay');
    const box = ov.querySelector('.model-panel');
    const cs = getComputedStyle(box);
    const bg = getComputedStyle(ov).backgroundColor;
    return { w: box.getBoundingClientRect().width, r: cs.borderRadius, bg,
             vw: window.innerWidth };
  });
  expect(geo.vw).toBe(375);
  expect(geo.w).toBe(375);
  expect(geo.r).toBe('0px');
  expect(geo.bg).not.toBe('rgba(0, 0, 0, 0)');
  // 注入返回钮（48px g2-fab 同页面壳「<」材质类）。
  const back = backBtn(overlay);
  await expect(back).toBeVisible();
  // 关注者组默认 unified 模式不渲染（panelVisibility 裁决）——先切 separate。
  await page.locator('.mp-mode-btn[data-mode="separate"]').click();
  await expect(page.locator('.mp-followers')).toBeVisible();
  // 下钻：点第一个 follower 角色头 → 只渲染该角色块。
  await page.locator('.mp-roleblock-follower .mp-role-head-toggle').first().click();
  await expect(page.locator('.mp-roleblock-follower')).toHaveCount(1);
  await expect(page.locator('.mp-roleblock-follower .mp-role-head-toggle'))
    .toHaveAttribute('aria-expanded', 'true');
  // 钻入态返回 = 回列表（面板不关；角色体收起——FOLLOWER_ROLES 单角色，
  // 列表/钻入都是 1 块，块数无判据力，开合态才是）。
  await back.click();
  await expect(overlay).toBeVisible();
  await expect(page.locator('.mp-roleblock-follower .mp-role-head-toggle'))
    .toHaveAttribute('aria-expanded', 'false');
  // 列表态返回 = 关面板。
  await back.click();
  await expect(overlay).toHaveCount(0);
  expect(errors).toEqual([]);
});

// ── ② 点名面：后台任务输出卡 = 铺满屏 + 左上返回 ──────────────────────────
test('T2 后台任务输出卡手机层铺满屏 + 左上返回（返回=关闭）', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  await page.evaluate(async () => {
    const m = await import('/js/bgTaskOutputPopup.js');
    m.openBgTaskOutput({ taskId: 'mpr-task-1', description: 'demo task', status: 'completed' });
  });
  const overlay = page.locator('.bgt-overlay');
  await expect(overlay).toBeVisible();
  const geo = await page.evaluate(() => {
    const ov = document.querySelector('.bgt-overlay');
    const box = ov.querySelector('.bgt-modal');
    const cs = getComputedStyle(box);
    const r = box.getBoundingClientRect();
    return { w: r.width, h: r.height, r: cs.borderRadius, vw: window.innerWidth, vh: window.innerHeight };
  });
  expect(geo.w).toBe(geo.vw);
  expect(geo.h).toBe(geo.vh);
  expect(geo.r).toBe('0px');
  const back = backBtn(overlay);
  await expect(back).toBeVisible();
  await back.click();
  await expect(overlay).toHaveCount(0);
  expect(errors).toEqual([]);
});

// ── ② 顺带面：secrets / workspacePicker / 通用确认 / 长按菜单 ──────────────
test('T3 残余弹窗族手机层铺满屏 + 左上返回（secrets/wsp/confirm/长按菜单）', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);

  // secrets 面板。
  await page.evaluate(async () => {
    const m = await import('/js/secretsPanel.js');
    m.openSecretsPanel();
  });
  const secretsOv = page.locator('#secrets-overlay');
  await expect(secretsOv).toBeVisible();
  await expect(backBtn(secretsOv)).toBeVisible();
  await backBtn(secretsOv).click();
  await expect(secretsOv).not.toHaveClass(/on/);

  // workspacePicker：返回 = 取消（onCancel 触发、overlay 摘除）。
  await page.evaluate(async () => {
    window.__mprCancel = 0;
    const m = await import('/js/workspacePicker.js');
    m.openPicker({ onCancel: () => { window.__mprCancel += 1; } });
  });
  const wspOv = page.locator('.wsp-overlay');
  await expect(wspOv).toBeVisible();
  const wspGeo = await page.evaluate(() => {
    const panel = document.querySelector('.wsp-panel');
    const cs = getComputedStyle(panel);
    return { r: cs.borderRadius, w: panel.getBoundingClientRect().width, vw: window.innerWidth };
  });
  expect(wspGeo.w).toBe(wspGeo.vw);
  expect(wspGeo.r).toBe('0px');
  await backBtn(wspOv).click();
  await expect(wspOv).toHaveCount(0);
  expect(await page.evaluate(() => window.__mprCancel)).toBe(1);

  // 通用确认（#modal-overlay）：返回 = 取消（onConfirm 不触发）。
  await page.evaluate(() => {
    window.__mprConfirm = 0;
    window.__showConfirm('t', 'm', () => { window.__mprConfirm += 1; });
  });
  const modalOv = page.locator('#modal-overlay');
  await expect(modalOv).toHaveClass(/on/);
  await expect(backBtn(modalOv)).toBeVisible();
  await backBtn(modalOv).click();
  await expect(modalOv).not.toHaveClass(/on/);
  expect(await page.evaluate(() => window.__mprConfirm)).toBe(0);

  // 长按/右键菜单：全屏动作表 + 返回 = 收菜单。
  await page.evaluate(async () => {
    const m = await import('/js/contextMenu.js');
    m.showPopupMenu(40, 200, [{ label: '动作甲', onClick: () => {} }]);
  });
  const menu = page.locator('.fm-context-menu');
  await expect(menu).toBeVisible();
  const menuGeo = await page.evaluate(() => {
    const el = document.querySelector('.fm-context-menu');
    const r = el.getBoundingClientRect();
    return { w: r.width, h: r.height, vw: window.innerWidth, vh: window.innerHeight };
  });
  expect(menuGeo.w).toBe(menuGeo.vw);
  expect(menuGeo.h).toBe(menuGeo.vh);
  await backBtn(menu).click();
  await expect(menu).toHaveCount(0);
  expect(errors).toEqual([]);
});

// ── ③ 设置页展开式配置退役：入口行 → 层级子页 ──────────────────────────────
test('T4 设置页 STT/TTL 入口行点击 push 层级子页（折叠体收编，返回 pop）', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  await dockTo(page, 'workbench');
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);

  // 设置页内折叠体不渲染（display:none），入口行在场。
  const sttHiddenInSettings = await page.evaluate(() => {
    const settingsPage = document.querySelector('.mobile-page[data-page="settings"]');
    const body = settingsPage.querySelector('#stt-advance-body');
    return !body || getComputedStyle(body).display === 'none';
  });
  expect(sttHiddenInSettings).toBe(true);
  await expect(page.locator('#stt-advance-toggle')).toBeVisible();

  // 点 STT 入口行 → 子页收编折叠体。
  await page.locator('#stt-advance-toggle').click();
  await expect(page.locator('.mobile-page[data-page="settings-stt"]')).toHaveClass(/live/);
  const adopted = await page.evaluate(() => {
    const sub = document.querySelector('.mobile-page[data-page="settings-stt"]');
    const body = sub && sub.querySelector('.mobile-page-body #stt-advance-body');
    return !!body && !body.hidden && getComputedStyle(body).display !== 'none';
  });
  expect(adopted).toBe(true);
  await expect(page.locator('.mobile-page[data-page="settings-stt"] #btn-save-stt')).toBeVisible();

  // 页壳「<」返回 → 设置页，折叠体归还（设置页内仍 display:none）。
  await page.locator('.mobile-page[data-page="settings-stt"] .mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);

  // TTL 入口行同样下钻。
  await page.locator('#ttl-advance-toggle').click();
  await expect(page.locator('.mobile-page[data-page="settings-ttl"]')).toHaveClass(/live/);
  await expect(page.locator('.mobile-page[data-page="settings-ttl"] #ttl-advance-body')).toBeVisible();
  expect(errors).toEqual([]);
});

// ── ① 手机层检索命中 → 关搜索面回聊天根 + 主会话闪击带 ──────────────────────
test('T5 手机层检索命中：搜索面关闭 + 回聊天根 + #chat 闪击带', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  // 打开搜索页（dock 工作台无搜索行；搜索面 = FAB 键 → nav search 页）。
  await page.locator('.mh-chrome-btn[data-chrome="search"]').click();
  await expect(page.locator('.mobile-page[data-page="search"]')).toHaveClass(/live/);
  await expect(page.locator('#search-overlay')).toHaveClass(/on/);
  await page.fill('#search-keyword', 'omega');
  await page.waitForSelector('#search-results .search-result', { timeout: 5000 });
  await page.click('#search-results .search-result');
  // act-and-exit + 回聊天根 + 主会话命中行闪击。
  await expect(page.locator('#search-overlay')).not.toHaveClass(/on/);
  await page.waitForFunction(() => {
    const top = window.__nfMobileNav && window.__nfMobileNav.top;
    return top === 'chat';
  }, { timeout: 5000 });
  await page.waitForSelector('#chat .search-hit-flash', { timeout: 5000 });
  expect(errors).toEqual([]);
});

// ── 桌面零变化守卫：同弹层在桌面视口不拉伸、无可见返回钮 ────────────────────
test('T6 桌面守卫：/model 与输出卡形态不拉伸、.mfs-back 隐藏', async ({ page }) => {
  const errors = watchErrors(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await bootApp(page);
  await page.evaluate(async () => {
    const m = await import('/js/modelPanel.js');
    await m.openModelPanel();
    const b = await import('/js/bgTaskOutputPopup.js');
    b.openBgTaskOutput({ taskId: 'mpr-task-2', description: 'demo', status: 'completed' });
  });
  const desktop = await page.evaluate(() => {
    const mp = document.querySelector('#model-panel-overlay .model-panel');
    const bgt = document.querySelector('.bgt-overlay .bgt-modal');
    const vis = (el) => {
      if (!el) return 'missing';
      const b = el.getBoundingClientRect();
      const back = el.parentElement.querySelector(':scope > .mfs-back');
      const cs = getComputedStyle(el);
      return { w: Math.round(b.width), r: cs.borderRadius,
               backDisplay: back ? getComputedStyle(back).display : 'none' };
    };
    return { mp: vis(mp), bgt: vis(bgt) };
  });
  expect(desktop.mp.w).toBeLessThan(600);          // 540px 居中模态，不拉伸
  expect(desktop.mp.r).toBe('20px');               // 圆角保留
  expect(desktop.mp.backDisplay).toBe('none');     // 返回钮桌面隐藏
  expect(desktop.bgt.r).toBe('20px');
  expect(desktop.bgt.backDisplay).toBe('none');
  expect(errors).toEqual([]);
});
