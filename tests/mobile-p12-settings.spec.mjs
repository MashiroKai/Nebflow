// mobile-p12-settings.spec.mjs — P12「设置」右列推送页验收
//（mobile-secondary-screens 实施批；样张 v3.5 P12 + 开放点①②③按设计批推荐，
//  主键规格 = v3.6① 绿键退役裁定字面。
//  面板合并批 2026-10-23 ①④⑤ 更新：设置页 profile 入口行**退役**（作者令④
//  「手机端设置里去掉智能体面板的按钮 —— 入口已由 header 头像承担」）；旧独立
//  #agent-profile-panel 弹窗退役（智能体内容 = 合并面板 .ap-pane 分区，入口 =
//  header 头像）；v3.6 非绿键守卫对 .cfg-btn-primary（设置模态保存类主键）延续。）
//
// 样张→实机 DOM 映射（实施批口径，面板合并批修订）：
//   页范式 …… 右列键推入 .mobile-page（壳既有）；内容面 = 桌面 #settings-modal
//        （renderSettings 七分组，零重排）—— 页体首块即设置面（无入口行）。
//   智能体分区 …… .ap-pane 一件式（合并面板内）：头像预览（点击即换头像，
//        作者令② 无「设置头像」钮）/名称行内回车存/同源文案（profile.note 同键）。
//   v3.6① 非绿键 …… .cfg-btn-primary 手机层 = light #111827/白字 hover
//        #374151；dark #e8eaed/#13161c hover #fff；500 字重。守卫：页内无按钮
//        计算背景 = 品牌绿。
//
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-p12-settings.spec.mjs

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

const SID = 'p12-root-session';

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

async function bootApp(page, colorScheme = 'dark') {
  await page.emulateMedia({ colorScheme });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    // harness：stub 掉 SW 注册（swReg.js 在 localhost 安全上下文 register /sw.js；
    // 404 会记「fetching the script」console error 混进 errors 断言）。
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
      // agent 档案缺省（app 走默认腿，浏览器对非 2xx 记 console error 故给 200 空对象）。
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
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
}

async function pushSettings(page) {
  await page.locator('.mh-dd[data-dd="right"] .mh-dd-hd').click();
  await page.locator('.mh-dd[data-dd="right"] [data-face="settings"]').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test('T1 settings opens with NO profile entry row; agent surface lives in the merged panel via header avatar', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, 'dark');
  await pushSettings(page);

  // 面板合并批 ④：设置页零智能体入口行（页体首块 = 设置面，不是 .profile-entry），
  // 也无旧独立面板解隐路径。
  await expect(page.locator('.profile-entry')).toHaveCount(0);
  const body = page.locator('.mobile-page[data-page="settings"] .mobile-page-body');
  const firstId = await body.evaluate((el) => el.firstElementChild?.id || '');
  expect(firstId).toBe('settings-modal');

  // 出页回根 → header 头像 = 唯一智能体入口：开合并面板（智能体分区）。
  await page.locator('.mobile-page[data-page="settings"] .mobile-page-header .mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).not.toHaveClass(/live/);
  await page.locator('#agent-avatar-btn').click();
  await expect(page.locator('#memory-overlay')).toHaveClass(/on/);
  await expect(page.locator('#memory-modal')).toHaveAttribute('data-pane', 'agent');

  // 同源文案（profile.note i18n 同键 = agentProfile.js 渲染面）；头像盒 = 按钮
  //（作者令②：点击头像即换头像，无「设置头像」钮）；旧重置/上传钮不存在。
  await expect(page.locator('#memory-modal .ap-note')).toContainText('头像本地优先保存');
  await expect(page.locator('#memory-modal .ap-name-input')).toBeVisible();
  await expect(page.locator('#memory-modal .ap-avatar-box')).toHaveAttribute('aria-label', '上传头像');
  await expect(page.locator('#memory-modal .ap-btnrow, #memory-modal .ap-btn-primary, #memory-modal .ap-btn-ghost')).toHaveCount(0);
  // 旧 body 级独立面板已退役（DOM 不存在）。
  expect(await page.locator('#agent-profile-panel').count()).toBe(0);
  // 点外收面（overlay 自带点外关闭 = 弹窗语义完整）。
  await page.keyboard.press('Escape');
  await expect(page.locator('#memory-overlay')).not.toHaveClass(/on/);
  expect(errors).toEqual([]);
});

test('T2 non-green primary key (v3.6①): cfg-btn-primary light/dark surface swap, flat, 500 weight', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, 'light');
  await pushSettings(page);
  const primary = page.locator('#settings-modal .cfg-btn-primary').first();
  let cs = await primary.evaluate((el) => {
    const s = getComputedStyle(el);
    return { bg: s.backgroundColor, fg: s.color, shadow: s.boxShadow, fw: s.fontWeight };
  });
  expect(cs.bg).toBe('rgb(17, 24, 39)');   // #111827
  expect(cs.fg).toBe('rgb(255, 255, 255)');
  expect(cs.shadow).toBe('none');
  expect(cs.fw).toBe('500');

  // dark：前景/背景互换（#e8eaed 底 / #13161c 字）。媒体翻转触发 .18s 背景过渡
  // —— 等过渡收尾再读 computed。
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForTimeout(400);
  cs = await primary.evaluate((el) => {
    const s = getComputedStyle(el);
    return { bg: s.backgroundColor, fg: s.color };
  });
  expect(cs.bg).toBe('rgb(232, 234, 237)'); // #e8eaed
  expect(cs.fg).toBe('rgb(19, 22, 28)');    // #13161c

  // v3.6 守卫（对位样张 §3-5，按 v3.6 §1 裁定收窄）：除行内开关 nb-toggle
  // （共享组件 accent，作者 2026-09-06 已裁「明确维持的绿」）外，页内按钮
  // 计算背景 ≠ 品牌绿 rgb(7,193,96)。
  const greenInfo = await page.locator('.mobile-page[data-page="settings"] button').evaluateAll((els) =>
    JSON.stringify(els.filter((el) => !el.classList.contains('nb-toggle')
      && getComputedStyle(el).backgroundColor === 'rgb(7, 193, 96)')
      .map((el) => `${el.className}|${el.getAttribute('aria-label') || el.title || ''}`)));
  expect(greenInfo, `non-toggle green buttons: ${greenInfo}`).toBe('[]');
  expect(errors).toEqual([]);
});

test('T3 name inline save on Enter via merged panel; settings groups follow desktop order; no entry row residue on pop', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, 'dark');
  await pushSettings(page);

  // 名称行内回车存（入口 = header 头像 → 合并面板智能体分区；saveName 写穿
  // state.rootDisplayName + 播 root-name-change）。
  await page.locator('.mobile-page[data-page="settings"] .mobile-page-header .mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).not.toHaveClass(/live/);
  await page.locator('#agent-avatar-btn').click();
  await expect(page.locator('#memory-overlay')).toHaveClass(/on/);
  const input = page.locator('#memory-modal .ap-name-input');
  await input.fill('笨笨二号');
  await input.press('Enter');
  const named = await page.evaluate(async () => {
    const s = (await import('/js/state.js')).default;
    return s.rootDisplayName;
  });
  expect(named).toBe('笨笨二号');

  // 七分组沿用桌面次序：#settings-modal 内分组标题在场（收编进设置页）。
  await page.keyboard.press('Escape');
  await page.locator('.mh-dd[data-dd="right"] .mh-dd-hd').click();
  await page.locator('.mh-dd[data-dd="right"] [data-face="settings"]').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
  await expect(page.locator('#settings-modal .settings-modal-header')).toBeVisible();

  // 出页清场：零入口行残留（面板合并批 ④）。
  await page.locator('.mobile-page[data-page="settings"] .mobile-page-header .mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).not.toHaveClass(/live/);
  expect(await page.locator('.profile-entry').count()).toBe(0);
  expect(errors).toEqual([]);
});
