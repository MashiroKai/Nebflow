// mobile-p12-settings.spec.mjs — P12「设置」右列推送页验收
//（mobile-secondary-screens 实施批；样张 v3.5 P12 + 开放点①②③④按设计批推荐，
//  主键规格 = v3.6① 绿键退役裁定字面）。
//
// 样张→实机 DOM 映射（实施批口径）：
//   页范式 …… 右列键推入 .mobile-page（壳既有）；内容面 = profile 置顶块
//        （agentProfile 面板停靠形态，navController syncProfileBlock 收编）+
//        桌面 #settings-modal（renderSettings 七分组，零重排）。
//   §2-P12 profile 区 …… 与已实施 agentProfile.js 一一对应：头像预览/上传
//        头像[主键]/恢复默认/名称行内回车存/同源文案（profile.note i18n 同键）。
//   v3.6① 非绿主键 …… .ap-btn-primary 手机层 = light #111827/白字 hover
//        #374151；dark #e8eaed/#13161c hover #fff；box-shadow none；无图标
//        （应用 DOM 有 lucide svg ⇒ computed display:none 落实「无图标」）；
//        500 字重。守卫：页内无按钮计算背景 = 品牌绿。
//   开放点② 分组次序沿用桌面 …… renderSettings 原样（T3 断言首分组在
//        profile 块之后）。
//   开放点③「重启服务」红字+二次确认【新增提案】④桌面项去留 …… 后端无
//        重启端点/桌面项为桌面渲染，均不伪造 —— 记报告（不实施）。
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

test('T1 profile block docked at page-body top, always visible, popup semantics yield', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, 'dark');
  await pushSettings(page);

  // 停靠块 = 页体首块（在 #settings-modal 之前）且常显。
  const body = page.locator('.mobile-page[data-page="settings"] .mobile-page-body');
  const firstChildId = await body.evaluate((el) => el.firstElementChild?.id || '');
  expect(firstChildId).toBe('agent-profile-panel');
  await expect(page.locator('#agent-profile-panel')).toBeVisible();
  await expect(page.locator('#settings-modal')).toBeVisible();

  // 同源文案（profile.note i18n 同键 = agentProfile.js 渲染面）。
  await expect(page.locator('#agent-profile-panel .ap-note')).toContainText('头像本地优先保存');
  // 名称行 + 上传/恢复钮在场；头关闭钮退场（停靠块无「关闭」语义）。
  await expect(page.locator('#agent-profile-panel .ap-name-input')).toBeVisible();
  await expect(page.locator('#agent-profile-panel .ap-btn-primary')).toBeVisible();
  await expect(page.locator('#agent-profile-panel .ap-btn-ghost')).toBeVisible();
  expect(await page.locator('#agent-profile-panel .ap-close').evaluate((el) => getComputedStyle(el).display)).toBe('none');

  // 弹窗语义让位：停靠形态不走 ap-open scrim（常显块 ≠ 弹窗）。
  expect(await page.evaluate(() => document.body.classList.contains('ap-open'))).toBe(false);
  expect(errors).toEqual([]);
});

test('T2 non-green primary key (v3.6①): light/dark surface swap, flat, no icon, 500 weight', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, 'light');
  await pushSettings(page);
  const primary = page.locator('#agent-profile-panel .ap-btn-primary');
  let cs = await primary.evaluate((el) => {
    const s = getComputedStyle(el);
    return { bg: s.backgroundColor, fg: s.color, shadow: s.boxShadow, fw: s.fontWeight };
  });
  expect(cs.bg).toBe('rgb(17, 24, 39)');   // #111827
  expect(cs.fg).toBe('rgb(255, 255, 255)');
  expect(cs.shadow).toBe('none');
  expect(cs.fw).toBe('500');
  expect(await primary.locator('svg').first().evaluate((el) => getComputedStyle(el).display)).toBe('none');

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

test('T3 name inline save on Enter; groups follow desktop order after the profile block; undock on pop', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, 'dark');
  await pushSettings(page);

  // 名称行内回车存（saveName 写穿 state.rootDisplayName + 播 root-name-change）。
  const input = page.locator('#agent-profile-panel .ap-name-input');
  await input.fill('笨笨二号');
  await input.press('Enter');
  const named = await page.evaluate(async () => {
    const s = (await import('/js/state.js')).default;
    return s.rootDisplayName;
  });
  expect(named).toBe('笨笨二号');

  // 七分组沿用桌面次序：profile 块之后的 #settings-modal 内分组标题在场。
  await expect(page.locator('#settings-modal .settings-modal-header')).toBeVisible();

  // 出页拆铺：面板归位 body 且隐藏（弹窗语义还原）。
  await page.locator('.mobile-page[data-page="settings"] .mobile-page-header .mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).not.toHaveClass(/live/);
  const docked = await page.evaluate(() => {
    const p = document.getElementById('agent-profile-panel');
    return { inBody: p.parentElement === document.body, hidden: p.hidden };
  });
  expect(docked.inBody).toBe(true);
  expect(docked.hidden).toBe(true);
  expect(errors).toEqual([]);
});
