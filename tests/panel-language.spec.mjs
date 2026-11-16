// panel-language.spec.mjs — 面板设计语言批（2026-11-06）真渲染验收
// 作者令（contact-panel-unify 链 3(c)(d)(e) 逐字）：
//   (c)「面板宽度与形态：不要做成弹窗，要运用整个面板的宽度」
//   (d)「按钮去重：不要保留功能重复的按钮，比如已经有返回键了，就不要再放叉号键（X）」
//   (e)「规范统一：顶部文字大小等面板设计，可以参考项目面板，统一整理出一套面板的设计语言」
//
// 断言面：
//   W1 四面板标题 computed 读数按规范分档一致（panel-spec.md §1.1 登记表）：
//      好友信息 / 群信息（同一实现）：标题条 = --ds-panel-title-*（15px/600/1.4）；
//      项目面板：画布胶囊头 = --ds-fs-subtitle/--ds-fw-btn（14px/500）；
//      后台智能体面板：卡名行 = --ds-fs-body/--ds-fw-title（13px/600）。
//   W2 桌面 1440：.pf-modal 宽度 = 所在容器（#profile-face-overlay）宽度（面板全宽，
//      不再是 420px 居中窄卡——改前读数 420/1440）。
//   W3 手机层 375：.pf-modal 宽度 = 覆层宽度（A 族铺平，同一实现）；DOM 无 .pf-close、
//      只有返回键（3(d)：手机有返回键就不放 X）。
//   W4 群信息面板同 W1/W3 口径（同一模块同一行守卫）。
//
// 数据面 = 路由夹具（零后端、零 sbt）：/api/friends / /api/groups 返回 {}
// （profileFace 读不到快照时按传入行建面 = 既有 fail-open 路径）；/api/executors
// 给执行器卡夹具。截图：NF_SHOT_DIR 指定（缺省 reports-evidence/panel-language/<pid>）。
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test \
//        tests/panel-language.spec.mjs --reporter=line

import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOT_DIR = process.env.NF_SHOT_DIR || join(dirname(fileURLToPath(import.meta.url)), '..', 'reports-evidence', 'panel-language', String(process.pid));
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

mkdirSync(SHOT_DIR, { recursive: true });

async function bootApp(page, locale = 'zh-CN') {
  await page.addInitScript((lc) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', lc);
    localStorage.setItem('stand_sw', '0');
  }, locale);
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/executors') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ executors: [{ id: 'nebula', name: 'Nebula', executor: 'nebula', enabled: true, detect: 'ok', builtIn: true }] }),
      });
    }
    if (p.startsWith('/api/') || p.startsWith('/agents/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'nf' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: 'pl-root', name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: 'pl-root',
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === 'pl-root' && s.ws && s.ws.readyState === 1;
  }, null, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 开好友/群信息面板（唯一属主 profileFace 的两个独立入口函数）。 */
async function openProfile(page, kind) {
  return page.evaluate(async (k) => {
    const m = await import('/js/profileFace.js');
    if (k === 'friend') await m.openFriendProfile({ userId: 'u-f1', name: '测试好友', neblinkId: 'nl-001', avatarUrl: '', remark: '', bio: '' });
    else await m.openGroupProfile({ conversationId: 'g-1', title: '测试群', memberCount: 3 });
    const ov = document.getElementById('profile-face-overlay');
    return !!(ov && !ov.hidden && ov.querySelector('.pf-modal'));
  }, kind);
}

/** 标题条 computed 读数（字号/字重/行高）。 */
function readStyle(page, selector) {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const cs = getComputedStyle(el);
    return { fontSize: cs.fontSize, fontWeight: cs.fontWeight, lineHeight: cs.lineHeight };
  }, selector);
}

test('contact pending actions retain failed input and cancel restores baseline', async ({ page }) => {
  await bootApp(page);
  await openProfile(page, 'friend');
  const input = page.locator('[data-pf-input="remark"]');
  const bar = page.locator('.pf-edit-bar');
  await expect(bar).toBeHidden();
  await input.fill('pending');
  await expect(bar).toBeVisible();
  await page.route('**/api/friends/*/remark', route => route.fulfill({ status: 500, body: '{}' }));
  await page.locator('.pf-edit-submit').click();
  await expect(input).toHaveValue('pending');
  await expect(bar).toBeVisible();
  await page.locator('.pf-edit-cancel').click();
  await expect(input).toHaveValue('');
  await expect(bar).toBeHidden();
});

test('contact save serializes a pending field and reads server value back', async ({ page }) => {
  await bootApp(page);
  let writes = 0;
  let stored = '';
  await page.route('**/api/friends', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [{ userId: 'u-f1', name: 'Friend', remark: stored }] }) }));
  await page.route('**/api/friends/*/remark', async route => {
    writes++;
    stored = 'server-value';
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  await openProfile(page, 'friend');
  const input = page.locator('[data-pf-input="remark"]');
  await input.fill('submitted');
  await page.locator('.pf-edit-submit').click();
  await expect(input).toHaveValue('server-value');
  await expect(page.locator('.pf-edit-bar')).toBeHidden();
  expect(writes).toBe(1);
});

// ── W1+W2：桌面档（1440×900）─────────────────────────────────────────────
test.describe('desktop 1440', () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test('W1 好友信息面板标题 = 15px/600/1.4 档（--ds-panel-title-*）', async ({ page }) => {
    await bootApp(page);
    expect(await openProfile(page, 'friend')).toBe(true);
    const title = await readStyle(page, '#profile-face-overlay .cfg-modal-title');
    console.log('[W1 friend title]', JSON.stringify(title));
    expect(title.fontSize).toBe('15px');
    expect(title.fontWeight).toBe('600');
    // 1.4 × 15 = 21px（computed 解析值）
    expect(title.lineHeight).toBe('21px');
    const hero = await readStyle(page, '#profile-face-overlay .pf-hero-name');
    console.log('[W1 friend hero]', JSON.stringify(hero));
    expect(hero.fontSize).toBe('15px');
    expect(hero.fontWeight).toBe('600');
    await page.locator('#profile-face-overlay .pf-modal').screenshot({ path: join(SHOT_DIR, 'w1-friend-1440.png') });
  });

  test('W2 桌面 .pf-modal 全宽 = 所在容器宽（不再 420 窄卡）', async ({ page }) => {
    await bootApp(page);
    expect(await openProfile(page, 'friend')).toBe(true);
    const w = await page.evaluate(() => {
      const m = document.querySelector('#profile-face-overlay .pf-modal').getBoundingClientRect();
      const ov = document.getElementById('profile-face-overlay').getBoundingClientRect();
      return { modal: Math.round(m.width), container: Math.round(ov.width), viewport: window.innerWidth };
    });
    console.log('[W2 1440 widths]', JSON.stringify(w));
    expect(w.viewport).toBe(1440);
    expect(w.modal).toBe(1440);
    expect(w.modal).toBe(w.container);
  });

  test('W1b 群信息面板同一实现同档（标题 + 全宽）', async ({ page }) => {
    await bootApp(page);
    expect(await openProfile(page, 'group')).toBe(true);
    const title = await readStyle(page, '#profile-face-overlay .cfg-modal-title');
    console.log('[W1b group title]', JSON.stringify(title));
    expect(title.fontSize).toBe('15px');
    expect(title.fontWeight).toBe('600');
    const w = await page.evaluate(() => {
      const m = document.querySelector('#profile-face-overlay .pf-modal').getBoundingClientRect();
      const ov = document.getElementById('profile-face-overlay').getBoundingClientRect();
      return { modal: Math.round(m.width), container: Math.round(ov.width) };
    });
    console.log('[W1b 1440 widths]', JSON.stringify(w));
    expect(w.modal).toBe(1440);
    expect(w.modal).toBe(w.container);
    await page.locator('#profile-face-overlay .pf-modal').screenshot({ path: join(SHOT_DIR, 'w1b-group-1440.png') });
  });

  test('W1c 项目面板胶囊头 = 14px/500 档（--ds-fs-subtitle/--ds-fw-btn）', async ({ page }) => {
    await bootApp(page);
    await page.evaluate(async () => {
      const m = await import('/js/projectTab.js');
      m.openProjectsTab();
    });
    await page.waitForSelector('.canvas-header', { timeout: 8000 });
    const head = await readStyle(page, '.canvas-header');
    console.log('[W1c projects capsule]', JSON.stringify(head));
    expect(head.fontSize).toBe('14px');
    expect(head.fontWeight).toBe('500');
    await page.locator('.canvas-header').first().screenshot({ path: join(SHOT_DIR, 'w1c-projects-capsule.png') });
  });

  test('W1d 后台智能体面板卡名行 = 13px/600 档（--ds-fs-body/--ds-fw-title）', async ({ page }) => {
    await bootApp(page);
    await page.evaluate(async () => {
      const m = await import('/js/executorsPanel.js');
      m.openExecutors();
    });
    await page.waitForSelector('.executor-card-name', { timeout: 8000 });
    const name = await readStyle(page, '.executor-card-name');
    console.log('[W1d executor card name]', JSON.stringify(name));
    expect(name.fontSize).toBe('13px');
    expect(name.fontWeight).toBe('600');
  });
});

// ── W3+W4：手机层（375×812，body.mobile-layer）───────────────────────────
test.describe('mobile layer 375', () => {
  test.use({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });

  test('W3 手机层 .pf-modal 全宽 + 无 .pf-close 只有返回键（好友）', async ({ page }) => {
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 10000 });
    expect(await openProfile(page, 'friend')).toBe(true);
    const w = await page.evaluate(() => {
      const m = document.querySelector('#profile-face-overlay .pf-modal').getBoundingClientRect();
      const ov = document.getElementById('profile-face-overlay').getBoundingClientRect();
      return {
        modal: Math.round(m.width), container: Math.round(ov.width), viewport: window.innerWidth,
        closeX: document.querySelectorAll('#profile-face-overlay .pf-close').length,
        back: document.querySelectorAll('#profile-face-overlay .mfs-back').length,
      };
    });
    console.log('[W3 375 widths + buttons]', JSON.stringify(w));
    expect(w.viewport).toBe(375);
    expect(w.modal).toBe(375);
    expect(w.modal).toBe(w.container);
    // 3(d)：手机层返回键在 ⇒ X 键不建（DOM 级）
    expect(w.closeX).toBe(0);
    expect(w.back).toBe(1);
    await page.screenshot({ path: join(SHOT_DIR, 'w3-friend-375.png'), fullPage: false });
  });

  test('W4 手机层群信息面板同口径（无 X、有返回键、全宽）', async ({ page }) => {
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 10000 });
    expect(await openProfile(page, 'group')).toBe(true);
    const w = await page.evaluate(() => ({
      modal: Math.round(document.querySelector('#profile-face-overlay .pf-modal').getBoundingClientRect().width),
      container: Math.round(document.getElementById('profile-face-overlay').getBoundingClientRect().width),
      closeX: document.querySelectorAll('#profile-face-overlay .pf-close').length,
      back: document.querySelectorAll('#profile-face-overlay .mfs-back').length,
    }));
    console.log('[W4 375 group]', JSON.stringify(w));
    expect(w.modal).toBe(375);
    expect(w.modal).toBe(w.container);
    expect(w.closeX).toBe(0);
    expect(w.back).toBe(1);
  });
});
