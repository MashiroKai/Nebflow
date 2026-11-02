// profile-device-update-retire.spec.mjs — 件五（profile-settings 批，作者令 2026-11-02）：
// 「联系人里的设备更新去掉，现在统一由官网进行统一更新」机械验收。
//
// 判据（全部 = 联系人面**展开后**的 DOM 读数，375×812 手机层，friends 面开启）：
//   A. `[data-device-update-btn]` 命中数 = 0（hotupdate 批 3 · G7 的单按钮整组退役）。
//   B. `[data-device-update-state]` 命中数 = 0（行内状态文本回显面退役）。
//   C. 设备段内含「更新此设备」/「更新」文本的按钮数 = 0（含 `.neblink-peer-update-btn`
//      / `.neblink-ch-btn` 类名面与任意 button 文本面——防「留按钮只换文案」）。
//   D. 零死链：设备段内零指向更新页面的 `<a href>`（href 命中 update/upgrade/官网门户）。
//   E. 零空白占位：每行子节点仍为四件（icon → name → presence → status），无空 button /
//      空 span / 零宽 flex 占位（防「摘了内容留壳」）。
//   F. 零 console error（本批退役不得引入任何运行期报错）。
//   G. 行仍然可点进设备会话（退役只摘更新面，行激活语义不变）。
//
// 形态照 tests/mobile-topbar.spec.mjs 的静态服务器 + WS 夹具写法：零后端、零真实端口。
//
// 验红实证（判红纪律「改前不可满足」）：`RETIRE_WEB_ROOT=<改前树 web/>`（如
// `git archive HEAD | tar -x -C /tmp/baseline`）⇒ A/B/C/E 四条必须**逐条红**
// （改前对端行各挂 1 枚 `.neblink-peer-update-btn` + 一枚行内状态文本 ⇒ 行子节点 6 件）。
// Run:
//   node node_modules/@playwright/test/cli.js test tests/profile-device-update-retire.spec.mjs
//   RETIRE_WEB_ROOT=/tmp/retire-baseline/src/main/resources/web \
//     node node_modules/@playwright/test/cli.js test tests/profile-device-update-retire.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';

const WEB = process.env.RETIRE_WEB_ROOT || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'device-update-retire-session';
const SELF = { id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', userDescription: '', displayName: '测试用户', email: 'me@example.com' };
const PEERS = [
  { deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true, directOnline: true },
  { deviceId: 'dev-desk', deviceName: 'DESKTOP-IJBBEOT', platform: 'windows', online: true, directOnline: false, relayAvailable: true },
];

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

/** 静态路由 + WS 夹具；contacts/messages 面 LIVE（features.friends=true）。 */
async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ loggedIn: true, device: SELF, peers: PEERS }),
        });
      }
      if (p === '/api/friends') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [], incoming: [], outgoing: [] }) });
      }
      if (p === '/api/conversations') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
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
      type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(400);
}

/** 进联系人页 → 展开设备段 → 等行渲染。 */
async function openDeviceSection(page) {
  await dockTo(page, 'contacts');
  await page.locator('[data-devices-entry="1"]').click();
  await expect(page.locator('[data-device-section="1"]')).toHaveCount(1, { timeout: 5000 });
  await expect(page.locator('[data-device-section="1"] .fm-device-row')).toHaveCount(3);
}

test('件五 A-F 联系人设备段零更新面（按钮/状态/死链/占位/console）', async ({ page }) => {
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));

  await bootApp(page);
  await openDeviceSection(page);
  const section = page.locator('[data-device-section="1"]');

  // ── A. 更新钮零命中（旧契约 = 对端行各 1 枚）──
  await expect(page.locator('[data-device-update-btn]')).toHaveCount(0);
  await expect(page.locator('.neblink-peer-update-btn')).toHaveCount(0);
  // ── B. 行内状态文本零命中 ──
  await expect(page.locator('[data-device-update-state]')).toHaveCount(0);
  // ── C. 含更新文案的按钮零命中（类名面 + 文本面双探针）──
  const updateTextBtns = section.locator('button', { hasText: /更新|Update/i });
  await expect(updateTextBtns).toHaveCount(0);
  // 页内任何「更新此设备」字面（含非按钮形态）也不得残留
  await expect(section.getByText('更新此设备', { exact: true })).toHaveCount(0);

  // ── D. 零死链：设备段内无指向更新/官网的 <a href> ──
  const deadLinks = await section.evaluate((el) => {
    return [...el.querySelectorAll('a[href]')].map((a) => a.getAttribute('href'))
      .filter((h) => /update|upgrade|mystand\.space|profile/i.test(h || ''));
  });
  expect(deadLinks).toEqual([]);

  // ── E. 零空白占位：每行四件 + 无空子节点/零宽占位 ──
  const rowShape = await section.evaluate((el) => {
    const rows = [...el.querySelectorAll('.fm-device-row')];
    return rows.map((row) => {
      const kids = [...row.children];
      return {
        name: (row.querySelector('.neblink-peer-name') || {}).textContent || '',
        kidCount: kids.length,
        // 空壳探针：任何子元素既无文本、也无 SVG/图标、也无宽度 ⇒ 空白占位
        emptyKids: kids.filter((k) => {
          const hasText = (k.textContent || '').trim() !== '';
          const hasIcon = !!k.querySelector('svg, i[data-lucide]') || k.tagName.toLowerCase() === 'svg';
          const w = k.getBoundingClientRect().width;
          return !hasText && !hasIcon && w === 0;
        }).map((k) => k.className || k.tagName),
        buttons: row.querySelectorAll('button').length,
        kids: kids.map((k) => k.className || k.tagName),
      };
    });
  });
  expect(rowShape).toHaveLength(3);
  for (const r of rowShape) {
    // 四件：.neblink-peer-icon / .neblink-peer-name / .neblink-presence / .neblink-peer-status
    expect(r.kidCount).toBe(4);
    expect(r.buttons).toBe(0);
    expect(r.emptyKids).toEqual([]);
  }
  expect(rowShape.map((r) => r.name)).toEqual(['Mashiro-Mac', 'KAI', 'DESKTOP-IJBBEOT']);

  // ── F. 零 console error ──
  expect(consoleErrors).toEqual([]);

  await page.screenshot({ path: join(dirname(fileURLToPath(import.meta.url)), '..', '.nebflow', 'evidence',
    '20261102_profile-device-update-retire', 'a-contacts-device-section.png') }).catch(() => {});
});

test('件五 G 行激活不回退：点设备行仍开设备会话窗（只摘更新面）', async ({ page }) => {
  await bootApp(page);
  await openDeviceSection(page);
  const rows = page.locator('[data-device-section="1"] .fm-device-row');
  await rows.nth(1).click(); // KAI
  await expect(page.locator('.mobile-page[data-page="conversation"] .mobile-page-body > .fm-modal')).toHaveCount(1, { timeout: 5000 });
  await expect(page.locator('.mobile-page[data-page="conversation"] .fm-modal .fm-modal-name')).toHaveText('KAI');
  // 会话窗内同样无更新面（窗头/窗体都不该冒出更新键）
  await expect(page.locator('.fm-modal [data-device-update-btn]')).toHaveCount(0);
});
